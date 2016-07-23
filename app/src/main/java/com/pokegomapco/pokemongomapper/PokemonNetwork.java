package com.pokegomapco.pokemongomapper;

import POGOProtos.Map.Pokemon.MapPokemonOuterClass;
import POGOProtos.Map.Pokemon.NearbyPokemonOuterClass;
import POGOProtos.Map.Pokemon.WildPokemonOuterClass;
import POGOProtos.Networking.EnvelopesOuterClass;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;
import com.google.firebase.crash.FirebaseCrash;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.MapObjects;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.Login;
import com.pokegoapi.auth.PTCLogin;
import com.pokegoapi.google.common.geometry.S2CellId;
import com.pokegoapi.google.common.geometry.S2LatLng;
import okhttp3.OkHttpClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

public class PokemonNetwork implements GmsLocationFinder.ConnectionListener {
    private static final String TAG = PokemonNetwork.class.getSimpleName();

    private static final long LOCATION_UPDATE_POLL = 1000 * 10; // 10 seconds
    private static final long RESET_TIMEOUT = 1000 * 60 * 10; // 5 minutes
    private static final long THEAD_SLEEP = 1000; // 1 second;

    private static final int S2CELL_LEVEL = 15;

    private static final String PREFS_KEY_TOKEN = "token";
    private static final String PREFS_KEY_TOKEN_SERVICE = "token_service";

    private static PokemonNetwork sInstance;

    public enum LoginService {
        GOOGLE,
        PTC;

        public Login getLogin(OkHttpClient httpClient) {
            switch (this) {
                case PTC:
                    return new PTCLogin(httpClient);
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
    }

    private final Context mContext;
    private final OkHttpClient mHttpClient;
    private final GmsLocationFinder mLocationFinder;

    private Thread mPokemonThread;

    private class PokemonThread extends Thread {
        @Override
        public void run() {
            HashSet<S2CellId> visitedCells = new HashSet<>();
            ArrayDeque<S2CellId> cellQueue = new ArrayDeque<>();
            Location loc;
            S2CellId locCell = null;
            long resetTime = 0;
            long locationTime = 0;

            while (!isInterrupted()) {
                try {
                    Thread.sleep(THEAD_SLEEP);
                } catch (InterruptedException e) {
                    //Log.e(TAG, "Interrupted.", e);
                    return;
                }

                // Reset the visited cells, so we update with new pokemon
                long time = System.currentTimeMillis();
                if (time > resetTime) {
                    cellQueue.clear();
                    visitedCells.clear();
                    resetTime = time + RESET_TIMEOUT;
                }

                // Update center point if we've moved out of the original cell
                if (time > locationTime) {
                    loc = mLocationFinder.getMyLocation();
                    if (loc == null) continue;
                    S2CellId newLocCell = S2CellId.fromLatLng(S2LatLng.fromDegrees(loc.getLatitude(), loc.getLongitude())).parent(S2CELL_LEVEL);
                    if (newLocCell != locCell) {
                        locCell = newLocCell;
                        cellQueue.push(locCell);
                    }
                    locationTime = time + LOCATION_UPDATE_POLL;
                }

                // Process current cell
                S2CellId curCell = cellQueue.peek();

                // Shouldn't happen, so lets just reset
                if (curCell == null) {
                    locationTime = resetTime = 0;
                    continue;
                }

                S2LatLng latLng = curCell.toLatLng();

                MapObjects mapObjects = mGo.getMap().getMapObjects(latLng.latDegrees(), latLng.lngDegrees(), 1);
                if (mapObjects == null) {
                    FirebaseCrash.report(new Exception("Null map objects"));
                    continue;
                }

                Collection<WildPokemonOuterClass.WildPokemon> wildPokemons = mapObjects.getWildPokemons();
                if (wildPokemons == null) {
                    FirebaseCrash.report(new Exception("Null wild pokemon"));
                    continue;
                }

                time = System.currentTimeMillis();
                for (WildPokemonOuterClass.WildPokemon pokemon : wildPokemons) {
                    mPokemonListener.onPokemonFound(pokemon.getSpawnpointId(), pokemon.getLatitude(), pokemon.getLongitude(), pokemon.getPokemonData().getPokemonId().getNumber(), time + pokemon.getTimeTillHiddenMs());
                }

                /*Collection<MapPokemonOuterClass.MapPokemon> catchablePokemons = mapObjects.getCatchablePokemons();
                if (catchablePokemons == null) {
                    FirebaseCrash.report(new Exception("Null catchable pokemon"));
                    continue;
                }

                for (MapPokemonOuterClass.MapPokemon pokemon : catchablePokemons) {
                    mPokemonListener.onPokemonFound(pokemon.getSpawnpointId(), pokemon.getLatitude(), pokemon.getLongitude(), pokemon.getPokemonIdValue(), pokemon.getExpirationTimestampMs());
                }*/

                // Find new neighbors
                ArrayList<S2CellId> newCells = new ArrayList<>();
                curCell.getAllNeighbors(S2CELL_LEVEL, newCells);
                for (S2CellId cell : newCells) {
                    if (!visitedCells.contains(cell)) {
                        cellQueue.add(cell);
                    }
                }

                cellQueue.pop();
                visitedCells.add(curCell);
            }
        }
    }

    private PokemonGo mGo;
    private PokemonListener mPokemonListener;

    private PokemonNetwork(Context context) {
        mContext = context;

        mHttpClient = new OkHttpClient();

        mLocationFinder = GmsLocationFinder.getInstance(mContext);
        if (!mLocationFinder.isReady()) {
            mLocationFinder.addListener(this);
            mLocationFinder.init();
        } else {
            onGmsLocationConnected();
        }
    }

    public void startSearching(PokemonListener listener) {
        mPokemonListener = listener;
        if (mLocationFinder.isReady()) {
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

        String token = prefs.getString(PREFS_KEY_TOKEN, null);
        if (token == null) {
            return false;
        }

        String tokenMethod = prefs.getString(PREFS_KEY_TOKEN_SERVICE, null);
        if (tokenMethod == null) {
            /*prefs.edit().putString("token_login_method", null);
            prefs.edit().putString("token", null);*/
            return false;
        }

        EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth;
        try {
            LoginService loginService = LoginService.valueOf(tokenMethod);
            auth = loginService.getLogin(mHttpClient).login(token);
        } catch (Exception e) {
            // shouldn't happen. need to refactor login to not throw
            FirebaseCrash.report(e);
            return false;
        }

        mGo = new PokemonGo(auth, mHttpClient);
        if (mGo.getPlayerProfile() == null) {
            prefs.edit().remove(PREFS_KEY_TOKEN).remove(PREFS_KEY_TOKEN_SERVICE).apply();
            return false;
        }

        return true;
    }

    public boolean loginPTC(String username, String password) {
        EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth;
        try {
            auth = new PTCLogin(mHttpClient).login(username, password);
        } catch (Exception e) {
            FirebaseCrash.report(e);
            return false;
        }

        login(auth, LoginService.PTC);
        return true;
    }

    public void loginGoogle(String token) {
        login(new GoogleLogin(mHttpClient).login(token), LoginService.GOOGLE);
    }

    private void login(EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth, LoginService method) {
        mGo = new PokemonGo(auth, mHttpClient);

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString(PREFS_KEY_TOKEN, auth.getToken().getContents());
        prefs.putString(PREFS_KEY_TOKEN_SERVICE, method.name());
        prefs.apply();

        tryStartSearching();
    }

    public void logOut() {
        mPokemonThread.interrupt();

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString(PREFS_KEY_TOKEN, null);
        prefs.putString(PREFS_KEY_TOKEN_SERVICE, null);
        prefs.apply();
    }

    @Override
    public void onGmsLocationConnected() {
        if (mGo != null && mPokemonListener != null) {
            mPokemonThread.start();
        }
    }

    @Override
    public void onGmsLocationDisconnected() {

    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
    }
}
