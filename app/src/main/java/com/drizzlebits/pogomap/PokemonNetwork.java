package com.drizzlebits.pogomap;

import POGOProtos.Map.Pokemon.WildPokemonOuterClass;
import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.crash.FirebaseCrash;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.MapObjects;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.PtcLogin;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.google.common.geometry.S2CellId;
import com.pokegoapi.google.common.geometry.S2LatLng;
import okhttp3.OkHttpClient;

import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;

public class PokemonNetwork {
    private static final String TAG = PokemonNetwork.class.getSimpleName();

    private static final long LOCATION_UPDATE_POLL = 1000; // 1 second
    private static final long RESET_TIMEOUT = 1000 * 60 * 2; // 2 minutes
    private static final long RESET_THREDHOLD = 1000 * 30; // 30 seconds
    private static final long THREAD_SLEEP = 100; // 1/10 second;
    private static final long THREAD_DISCONNECT_SLEEP = 1000 * 5; // 5 seconds

    private static final int S2CELL_LEVEL = 15;

    private static final String PREFS_KEY_LOGIN_SERVICE = "login_service";
    private static final String PREFS_KEY_GOOGLE_ID_TOKEN = "google_id_token";
    private static final String PREFS_KEY_GOOGLE_REFRESH_TOKEN = "google_refresh_token";
    private static final String PREFS_KEY_PTC_USERNAME = "ptc_username";
    private static final String PREFS_KEY_PTC_PASSWORD = "ptc_password";

    private static PokemonNetwork sInstance;

    public enum LoginService {
        GOOGLE,
        PTC
    }

    public synchronized static PokemonNetwork getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PokemonNetwork(context.getApplicationContext());
        }

        return sInstance;
    }

    public interface PokemonListener {
        void onPokemonFound(String spawnId, double lat, double lng, int pokemonNumber, long timeTilHidden);
        void onError(boolean logout);
    }

    private final Context mContext;
    private final OkHttpClient mHttpClient;

    private Thread mPokemonThread;

    private class PokemonThread extends Thread {
        @Override
        public void run() {
            HashMap<Long, Long> cellTimes = new HashMap<>();
            long currentCellTime = 0;
            ArrayDeque<S2CellId> cellQueue = new ArrayDeque<>();
            LatLng loc;
            S2CellId locCell = null;
            long locationTime = 0;
            boolean disconnected = false;

            while (!isInterrupted()) {
                try {
                    Thread.sleep(disconnected ? THREAD_DISCONNECT_SLEEP : THREAD_SLEEP);
                } catch (InterruptedException e) {
                    break;
                }

                long time = System.currentTimeMillis();

                // Update center point if we've moved out of the original cell
                if (time > locationTime) {
                    loc = mLocationFinder.getLocation();
                    if (loc == null) continue;
                    S2CellId newLocCell = S2CellId.fromLatLng(S2LatLng.fromDegrees(loc.latitude, loc.longitude)).parent(S2CELL_LEVEL);
                    if (cellQueue.isEmpty() || locCell == null || newLocCell.pos() != locCell.pos()) {
                        locCell = newLocCell;
                        cellQueue.clear();
                        cellQueue.push(locCell);
                    }
                    locationTime = time + LOCATION_UPDATE_POLL;
                }

                // Re-check the cell we're in, in case the data is old and needs updating
                if (time > currentCellTime + RESET_TIMEOUT) {
                    cellQueue.clear();
                    cellQueue.add(locCell);
                }

                // Process current cell
                S2CellId curCell = cellQueue.peek();
                if (curCell == null) {
                    // What? How?
                    currentCellTime = 0;
                    continue;
                } else {
                    cellQueue.pop();
                }
                S2LatLng latLng = curCell.toLatLng();
                //mLocationFinder.drawDebugMarker(new LatLng(latLng.latDegrees(), latLng.lngDegrees()));

                MapObjects mapObjects;
                try {
                    mapObjects = mGo.getMap().getMapObjects(latLng.latDegrees(), latLng.lngDegrees(), 1);
                } catch (RemoteServerException e) {
                    FirebaseCrash.report(e);
                    if (e.getCause() instanceof UnknownHostException) {
                        mPokemonListener.onError(false);
                        disconnected = true;
                    }
                    continue;
                } catch (LoginFailedException e) {
                    FirebaseCrash.report(e);
                    if (trySavedLogin()) {
                        continue;
                    } else {
                        mPokemonListener.onError(true);
                        break;
                    }
                } catch (Exception e) {
                    FirebaseCrash.report(e);
                    continue;
                }
                disconnected = false;

                Collection<WildPokemonOuterClass.WildPokemon> wildPokemons = mapObjects.getWildPokemons();
                if (wildPokemons == null) {
                    FirebaseCrash.report(new Exception("Null wild pokemon"));
                    continue;
                }

                time = System.currentTimeMillis();
                for (WildPokemonOuterClass.WildPokemon pokemon : wildPokemons) {
                    mPokemonListener.onPokemonFound(pokemon.getSpawnPointId(), pokemon.getLatitude(), pokemon.getLongitude(),
                            pokemon.getPokemonData().getPokemonId().getNumber(), time + pokemon.getTimeTillHiddenMs());
                }

                // Find new neighbors
                ArrayList<S2CellId> currentNeighbors = new ArrayList<>();
                curCell.getAllNeighbors(S2CELL_LEVEL, currentNeighbors);
                for (S2CellId cell : currentNeighbors) {
                    Long cellTime = cellTimes.get(cell.id());
                    if (cellTime == null || time > cellTime + RESET_TIMEOUT - RESET_THREDHOLD) {
                        cellQueue.add(cell);
                    }
                }

                // None of our neighbors are old enough. Lets expand outward until we find something valid to search.
                HashSet<S2CellId> processedNeighbors = new HashSet<>();
                processedNeighbors.addAll(currentNeighbors);
                while (cellQueue.isEmpty()) {
                    // Get the neighbors of our neighbors.
                    HashSet<S2CellId> unprocessedNeighbors = new HashSet<>();
                    for (S2CellId cell : processedNeighbors) {
                        ArrayList<S2CellId> cellNeighbors = new ArrayList<>();
                        cell.getAllNeighbors(S2CELL_LEVEL, cellNeighbors);
                        unprocessedNeighbors.addAll(cellNeighbors);
                    }

                    // Remove neighbors we've already checked, so we only have the outer layer of neighbors.
                    unprocessedNeighbors.removeAll(processedNeighbors);

                    // Check if any of these are old enough
                    for (S2CellId cell : unprocessedNeighbors) {
                        Long cellTime = cellTimes.get(cell.id());
                        if (cellTime == null || time > cellTime + RESET_TIMEOUT - RESET_THREDHOLD) {
                            cellQueue.add(cell);
                        }
                    }
                    processedNeighbors = unprocessedNeighbors;
                }

                cellTimes.put(curCell.id(), time);
                if (curCell.id() == locCell.id()) {
                    currentCellTime = time;
                }
            }
            mPokemonThread = null;
        }
    }

    private PokemonGo mGo;
    private PokemonListener mPokemonListener;
    private PokemonManager.LocationFinder mLocationFinder;

    private PokemonNetwork(Context context) {
        mContext = context;

        mHttpClient = new OkHttpClient();

        startSearching(mPokemonListener);
    }

    public void setLocationFinder(PokemonManager.LocationFinder finder) {
        mLocationFinder = finder;
    }

    public void startSearching(PokemonListener listener) {
        mPokemonListener = listener;
        if (mGo != null && mLocationFinder.isReady() && mPokemonThread == null) {
            mPokemonThread = new PokemonThread();
            mPokemonThread.start();
        }
    }

    private void tryStartSearching() {
        if (mPokemonListener != null) {
            startSearching(mPokemonListener);
        }
    }

    public boolean trySavedLogin() {
        SharedPreferences prefs = getPrefs();

        String login = prefs.getString(PREFS_KEY_LOGIN_SERVICE, null);
        if (login == null) return false;

        LoginService loginService = LoginService.valueOf(login);
        try {
            RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo auth;
            switch (loginService) {
                case GOOGLE:
                    String idToken = prefs.getString(PREFS_KEY_GOOGLE_ID_TOKEN, null);
                    String refreshToken = prefs.getString(PREFS_KEY_GOOGLE_REFRESH_TOKEN, null);
                    if (idToken == null || refreshToken == null) return false;

                    auth = new GoogleLogin(mHttpClient).login(idToken, refreshToken);
                    break;
                case PTC:
                    String username = prefs.getString(PREFS_KEY_PTC_USERNAME, null);
                    String password = prefs.getString(PREFS_KEY_PTC_PASSWORD, null);
                    if (username == null || password == null) return false;

                    auth = new PtcLogin(mHttpClient).login(decrypt(username), decrypt(password));
                    break;
                default:
                    return false;
            }

            mGo = new PokemonGo(auth, mHttpClient);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            logOut();
            return false;
        }

        return true;
    }

    public boolean loginPTC(String username, String password) {
        RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo auth;
        try {
            auth = new PtcLogin(mHttpClient).login(username, password);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            return false;
        }

        boolean success = login(auth, LoginService.PTC);
        if (success) {
            // This feels dirty, but PTC has no way to refresh login, and these accounts should
            // be throwaways anyway. We will weakly encrypt just to add a tiny bit of safety.
            getPrefs().edit()
                    .putString(PREFS_KEY_PTC_USERNAME, encrypt(username))
                    .putString(PREFS_KEY_PTC_PASSWORD, encrypt(password))
                    .apply();
        }
        return success;
    }

    public boolean loginGoogle(String idToken, String refreshToken) {
        boolean success = login(new GoogleLogin(mHttpClient).login(idToken, refreshToken), LoginService.GOOGLE);
        if (success) {
            getPrefs().edit()
                    .putString(PREFS_KEY_GOOGLE_ID_TOKEN, idToken)
                    .putString(PREFS_KEY_GOOGLE_REFRESH_TOKEN, refreshToken)
                    .apply();
        }
        return success;
    }

    private boolean login(RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo auth, LoginService method) {
        try {
            mGo = new PokemonGo(auth, mHttpClient);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            return false;
        }

        getPrefs().edit()
                .putString(PREFS_KEY_LOGIN_SERVICE, method.name())
                .apply();

        tryStartSearching();
        return true;
    }

    public void logOut() {
        if (mPokemonThread != null) {
            mPokemonThread.interrupt();
        }

        getPrefs().edit()
                .remove(PREFS_KEY_LOGIN_SERVICE)
                .remove(PREFS_KEY_GOOGLE_REFRESH_TOKEN)
                .remove(PREFS_KEY_PTC_USERNAME)
                .remove(PREFS_KEY_PTC_PASSWORD)
                .apply();
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
    }

    private static String encrypt(String input) {
        // Simple encryption, not very strong!
        return Base64.encodeToString(input.getBytes(), Base64.DEFAULT);
    }

    private static String decrypt(String input) {
        return new String(Base64.decode(input, Base64.DEFAULT));
    }
}
