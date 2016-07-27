package com.drizzlebits.pogomap;

import POGOProtos.Map.Pokemon.WildPokemonOuterClass;
import POGOProtos.Networking.Envelopes.RequestEnvelopeOuterClass;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.maps.model.LatLng;
import com.google.firebase.crash.FirebaseCrash;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.MapObjects;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.Login;
import com.pokegoapi.auth.PtcLogin;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.exceptions.RemoteServerException;
import com.pokegoapi.google.common.geometry.S2CellId;
import com.pokegoapi.google.common.geometry.S2LatLng;
import okhttp3.OkHttpClient;

import java.net.SocketTimeoutException;
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

    private static final String PREFS_KEY_ID_TOKEN = "id_token";
    private static final String PREFS_KEY_REFRESH_TOKEN = "refresh_token";
    private static final String PREFS_KEY_TOKEN_SERVICE = "token_service";

    private static PokemonNetwork sInstance;

    public enum LoginService {
        GOOGLE,
        PTC;

        public Login getLogin(OkHttpClient httpClient) {
            switch (this) {
                case PTC:
                    return new PtcLogin(httpClient);
                case GOOGLE:
                default:
                    return new GoogleLogin(httpClient);
            }
        }
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
                    if (e.getCause() instanceof UnknownHostException || e.getCause() instanceof SocketTimeoutException) {
                        mPokemonListener.onError(false);
                        disconnected = true;
                        FirebaseCrash.report(e);
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

        String idToken = prefs.getString(PREFS_KEY_ID_TOKEN, null);
        if (idToken == null) {
            return false;
        }

        String tokenMethod = prefs.getString(PREFS_KEY_TOKEN_SERVICE, null);
        if (tokenMethod == null) {
            return false;
        }

        String refreshToken = prefs.getString(PREFS_KEY_REFRESH_TOKEN, null);

        RequestEnvelopeOuterClass.RequestEnvelope.AuthInfo auth;
        try {
            LoginService loginService = LoginService.valueOf(tokenMethod);
            Login login = loginService.getLogin(mHttpClient);
            auth = refreshToken == null ? login.login(idToken) : login.login(idToken, refreshToken);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            return false;
        }

        try {
            mGo = new PokemonGo(auth, mHttpClient);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            prefs.edit().remove(PREFS_KEY_ID_TOKEN).remove(PREFS_KEY_TOKEN_SERVICE).remove(PREFS_KEY_REFRESH_TOKEN).apply();
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

        return login(auth, LoginService.PTC);
    }

    public boolean loginGoogle(String idToken, String refreshToken) {
        boolean success = login(new GoogleLogin(mHttpClient).login(idToken, refreshToken), LoginService.GOOGLE);
        if (success) {
            getPrefs().edit().putString(PREFS_KEY_REFRESH_TOKEN, refreshToken).apply();
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

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString(PREFS_KEY_ID_TOKEN, auth.getToken().getContents());
        prefs.putString(PREFS_KEY_TOKEN_SERVICE, method.name());
        prefs.apply();

        tryStartSearching();
        return true;
    }

    public void logOut() {
        if (mPokemonThread != null) {
            mPokemonThread.interrupt();
        }

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString(PREFS_KEY_ID_TOKEN, null);
        prefs.putString(PREFS_KEY_REFRESH_TOKEN, null);
        prefs.putString(PREFS_KEY_TOKEN_SERVICE, null);
        prefs.apply();
    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
    }
}
