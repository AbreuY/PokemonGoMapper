package com.artemshvadskiy.pokemongomapper;

import POGOProtos.Map.Pokemon.WildPokemonOuterClass;
import POGOProtos.Networking.EnvelopesOuterClass;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;
import com.pokegoapi.api.PokemonGo;
import com.pokegoapi.api.map.Map;
import com.pokegoapi.api.map.MapObjects;
import com.pokegoapi.auth.GoogleLogin;
import com.pokegoapi.auth.Login;
import com.pokegoapi.auth.PTCLogin;
import com.pokegoapi.exceptions.LoginFailedException;
import com.pokegoapi.google.common.geometry.S2CellId;
import com.pokegoapi.google.common.geometry.S2LatLng;
import okhttp3.OkHttpClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

public class PokemonNetwork implements GmsLocationFinder.ConnectionListener {
    private static final long LOCATION_UPDATE_POLL = 1000 * 10; // 10 seconds
    private static final long RESET_TIMEOUT = 1000 * 60 * 5; // 5 minutes

    private static final String TAG = "PokemonGoMapper";

    private static PokemonNetwork sInstance;

    public enum LoginMethod {
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
            sInstance = new PokemonNetwork(context);
        }

        return sInstance;
    }

    public interface PokemonListener {
        void onPokemonFound(String spawnId, double lat, double lng, int pokemonId, long timeTilHidden);
    }

    private final Context mContext;
    private final OkHttpClient mHttpClient;
    private final GmsLocationFinder mLocationFinder;

    private Thread mPokemonThread;

    private class PokemonThread extends Thread {

        public PokemonThread() {
            super(new Runnable() {
                @Override
                public void run() {
                    HashSet<S2CellId> visitedCells = new HashSet<>();
                    ArrayDeque<S2CellId> cellQueue = new ArrayDeque<>();
                    Location loc;
                    S2CellId locCell = null;
                    long resetTime = 0;
                    long locationTime = 0;

                    while(true) {
                        if (Thread.currentThread().isInterrupted()) {
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
                            S2CellId newLocCell = S2CellId.fromLatLng(S2LatLng.fromDegrees(loc.getLatitude(), loc.getLongitude())).parent(15);
                            if (newLocCell != locCell) {
                                locCell = newLocCell;
                            }
                            cellQueue.clear();
                            cellQueue.push(locCell);
                            locationTime = time + LOCATION_UPDATE_POLL;
                        }

                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Interrupted.", e);
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
                        if (mapObjects == null) continue;

                        Collection<WildPokemonOuterClass.WildPokemon> wildPokemons = mapObjects.getWildPokemons();
                        if (wildPokemons == null) continue;

                        for (WildPokemonOuterClass.WildPokemon pokemon : wildPokemons) {
                            mPokemonListener.onPokemonFound(pokemon.getSpawnpointId(), pokemon.getLatitude(), pokemon.getLongitude(), pokemon.getPokemonData().getPokemonId().getNumber(), pokemon.getTimeTillHiddenMs());
                        }

                        // Find new neighbors
                        ArrayList<S2CellId> newCells = new ArrayList<>();
                        curCell.getAllNeighbors(15, newCells);
                        for (S2CellId cell : newCells) {
                            if (!visitedCells.contains(cell)) {
                                cellQueue.add(cell);
                            }
                        }

                        cellQueue.pop();
                        visitedCells.add(curCell);
                    }
                }
            });
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
            onConnected();
        }
    }

    public void startSearching(PokemonListener listener) {
        mPokemonListener = listener;
        if (mLocationFinder.isReady()) {
            mPokemonThread = new PokemonThread();
            mPokemonThread.start();
        }
    }

    public boolean trySavedLogin() {
        SharedPreferences prefs = getPrefs();

        String token = prefs.getString("token", null);
        if (token == null) {
            return false;
        }

        String tokenMethod = prefs.getString("token_login_method", null);
        if (tokenMethod == null) {
            /*prefs.edit().putString("token_login_method", null);
            prefs.edit().putString("token", null);*/
            return false;
        }

        EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth;
        try {
            LoginMethod loginMethod = LoginMethod.valueOf(tokenMethod);
            auth = loginMethod.getLogin(mHttpClient).login(token);
        } catch (Exception e) {
            // shouldn't happen. need to refactor login to not throw
            Log.e(TAG, "Login failed.", e);
            return false;
        }

        mGo = new PokemonGo(auth, mHttpClient);
        if (mGo.getPlayerProfile() == null) {
            prefs.edit().remove("token").remove("token_login_method").apply();
            return false;
        }

        Log.e(TAG, "Logged in: " + mGo.getPlayerProfile().getUsername());
        return true;
    }


    /*
    public boolean relogin(final LoginCallback callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth;
                try {
                    String token = mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE).getString("token", null);
                    auth = new PTCLogin(mHttpClient).login(token);
                } catch (LoginFailedException e) {
                    Log.e(TAG, "Login failed.", e);
                    callback.onError(e.getLocalizedMessage());
                    return;
                }

                mGo = new PokemonGo(auth, mHttpClient);
                Log.e(TAG, "Logged in: " + mGo.getPlayerProfile().getUsername());

                callback.onSuccess();
            }
        }).start();
    }*/

    public boolean loginPTC(String username, String password) {
        EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth;
        try {
            auth = new PTCLogin(mHttpClient).login(username, password);
        } catch (Exception e) {
            Log.e(TAG, "Login failed.", e);
            return false;
        }

        login(auth, LoginMethod.PTC);
        return true;
    }

    public void loginGoogle(String token) {
        login(new GoogleLogin(mHttpClient).login(token), LoginMethod.GOOGLE);
    }

    private void login(EnvelopesOuterClass.Envelopes.RequestEnvelope.AuthInfo auth, LoginMethod method) {
        mGo = new PokemonGo(auth, mHttpClient);
        Log.e(TAG, "Logged in: " + mGo.getPlayerProfile().getUsername());

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString("token", auth.getToken().getContents());
        prefs.putString("token_login_method", method.name());
        prefs.apply();
    }

    public void signOut() {
        mPokemonThread.interrupt();

        SharedPreferences.Editor prefs = getPrefs().edit();
        prefs.putString("token", null);
        prefs.putString("token_login_method", null);
        prefs.apply();
    }

    @Override
    public void onConnected() {
        if (mGo != null && mPokemonListener != null) {
            mPokemonThread.start();
        }
    }

    @Override
    public void onDisconnected() {

    }

    private SharedPreferences getPrefs() {
        return mContext.getSharedPreferences(TAG, Context.MODE_PRIVATE);
    }
}
