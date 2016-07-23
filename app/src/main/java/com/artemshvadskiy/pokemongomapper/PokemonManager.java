package com.artemshvadskiy.pokemongomapper;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

public class PokemonManager implements PokemonNetwork.PokemonListener {
    private static final String TAG = "PKGOMAP.PokemonManager";

    private static final Object sPokemonDataLock = new Object();

    public interface PokemonListener {
        void onPokemonFound(Pokemon pokemon);
        void onPokemonExpired(Pokemon pokemon);
    }

    private static PokemonManager sInstance;

    public synchronized static PokemonManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PokemonManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private final Context mContext;
    private final Handler mMainHandler;

    private final PokemonNetwork mPokemonNetwork;
    private final PokemonDatabase.PokemonDbHelper mPokemonDbHelper;
    private final SQLiteDatabase mPokemonDb;

    private final PriorityQueue<Pokemon> mExpirationQueue;
    private final HashMap<String, Pokemon> mPokemonBySpawnId;
    private final Pokemon[] mPokemonData;

    private PokemonListener mPokemonListener;

    private PokemonManager(Context context) {
        mContext = context;
        mMainHandler = new Handler(Looper.getMainLooper());

        mPokemonNetwork = PokemonNetwork.getInstance(context);
        mPokemonNetwork.startSearching(this);

        mExpirationQueue = new PriorityQueue<>(100, new Comparator<Pokemon>() {
            @Override
            public int compare(Pokemon lhs, Pokemon rhs) {
                if (lhs.expirationTime == rhs.expirationTime) {
                    return 0;
                } else if (lhs.expirationTime < rhs.expirationTime) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
        mPokemonBySpawnId = new HashMap<>();

        mPokemonDbHelper = new PokemonDatabase.PokemonDbHelper(mContext);
        mPokemonDb = mPokemonDbHelper.getWritableDatabase();

        String json = null;
        try {
            InputStream is = mContext.getResources().openRawResource(R.raw.pokemon);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            Log.e(TAG, "unable to load pokemon data");
        }

        Gson gson = new GsonBuilder().create();

        Type listType = new TypeToken<ArrayList<Pokemon>>(){}.getType();
        List<Pokemon> pokemons = gson.fromJson(json, listType);

        if (pokemons == null) {
            mPokemonData = new Pokemon[0];
        } else {
            // 0 will always be blank, since pokemon numbers start at 1
            mPokemonData = new Pokemon[pokemons.size() + 1];
            for (Pokemon pokemon : pokemons) {
                mPokemonData[pokemon.Number] = pokemon;
            }
        }

        loadDbPokemon();

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    synchronized (sPokemonDataLock) {
                        while (!mExpirationQueue.isEmpty()) {
                            final Pokemon pokemon = mExpirationQueue.peek();
                            if (pokemon.expirationTime < System.currentTimeMillis()) {
                                mExpirationQueue.remove();
                                mPokemonBySpawnId.remove(pokemon.spawnId);
                                mPokemonListener.onPokemonExpired(pokemon);
                            } else {
                                break;
                            }
                        }
                    }

                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }).start();
    }

    private void loadDbPokemon() {
        String[] projection = {
                PokemonDatabase.PokemonEntry.COLUMN_NAME_SPAWN_ID,
                PokemonDatabase.PokemonEntry.COLUMN_NAME_LATITUDE,
                PokemonDatabase.PokemonEntry.COLUMN_NAME_LONGITUDE,
                PokemonDatabase.PokemonEntry.COLUMN_NAME_POKEMON_NUMBER,
                PokemonDatabase.PokemonEntry.COLUMN_NAME_EXPIRATION_TIME
        };

        String where = PokemonDatabase.PokemonEntry.COLUMN_NAME_EXPIRATION_TIME + " < ?";
        String[] whereArgs = new String[] { String.valueOf(System.currentTimeMillis()) };

        mPokemonDb.delete(
                PokemonDatabase.PokemonEntry.TABLE_NAME,
                where,
                whereArgs
                );

        Cursor c = mPokemonDb.query(
                PokemonDatabase.PokemonEntry.TABLE_NAME,  // The table to query
                projection,                               // The columns to return
                null,                                // The columns for the WHERE clause
                null,                            // The values for the WHERE clause
                null,                                     // don't group the rows
                null,                                     // don't filter by row groups
                null                                 // The sort order
        );

        c.moveToFirst();
        while (!c.isAfterLast()) {
            String spawnId = c.getString(c.getColumnIndex(PokemonDatabase.PokemonEntry.COLUMN_NAME_SPAWN_ID));
            double lat = c.getDouble(c.getColumnIndex(PokemonDatabase.PokemonEntry.COLUMN_NAME_LATITUDE));
            double lng = c.getDouble(c.getColumnIndex(PokemonDatabase.PokemonEntry.COLUMN_NAME_LONGITUDE));
            int pokemonNumber = c.getInt(c.getColumnIndex(PokemonDatabase.PokemonEntry.COLUMN_NAME_POKEMON_NUMBER));
            long expirationTime = c.getLong(c.getColumnIndex(PokemonDatabase.PokemonEntry.COLUMN_NAME_EXPIRATION_TIME));
            addPokemon(false, spawnId, lat, lng, pokemonNumber, expirationTime);
            c.moveToNext();
        }

        c.close();
    }

    public int getNumPokemon() {
        return mPokemonData.length - 1;
    }

    public void setPokemonListener(PokemonListener listener) {
        mPokemonListener = listener;
    }

    public Collection<Pokemon> getPokemon() {
        return mPokemonBySpawnId.values();
    }

    public int getIconResByNumber(int pokemonNumber) {
        String name = mPokemonData[pokemonNumber].getResourceName();

        Resources resources = mContext.getResources();
        final int resourceId = resources.getIdentifier(name, "drawable", mContext.getPackageName());
        if (resourceId == 0) {
            Log.e(TAG, "failed resolution of pokemon: " + name);
        }
        return resourceId;
    }

    public String getNameByNumber(int pokemonNumber) {
        return mPokemonData[pokemonNumber].Name;
    }

    @Override
    public void onPokemonFound(String spawnId, double lat, double lng, int pokemonNumber, long timeTilHidden) {
        addPokemon(true, spawnId, lat, lng, pokemonNumber, System.currentTimeMillis() + timeTilHidden);
    }

    private void addPokemon(boolean writeDb, String spawnId, double lat, double lng, int pokemonNumber, long expirationTime) {
        synchronized (sPokemonDataLock) {
            if (mPokemonBySpawnId.containsKey(spawnId)) return;

            Pokemon pokemon = new Pokemon(mPokemonData[pokemonNumber]);
            pokemon.expirationTime = expirationTime;
            pokemon.latitude = lat;
            pokemon.longitude = lng;

            mPokemonBySpawnId.put(spawnId, pokemon);

            mExpirationQueue.add(pokemon);

            if (writeDb) {
                ContentValues values = new ContentValues();
                values.put(PokemonDatabase.PokemonEntry.COLUMN_NAME_SPAWN_ID, spawnId);
                values.put(PokemonDatabase.PokemonEntry.COLUMN_NAME_LATITUDE, lat);
                values.put(PokemonDatabase.PokemonEntry.COLUMN_NAME_LONGITUDE, lng);
                values.put(PokemonDatabase.PokemonEntry.COLUMN_NAME_POKEMON_NUMBER, pokemonNumber);
                values.put(PokemonDatabase.PokemonEntry.COLUMN_NAME_EXPIRATION_TIME, pokemon.expirationTime);

                // Insert the new row, returning the primary key value of the new row
                long newRowId = mPokemonDb.insert(
                        PokemonDatabase.PokemonEntry.TABLE_NAME,
                        null,
                        values);
            }

            if (mPokemonListener != null) {
                mPokemonListener.onPokemonFound(pokemon);
            }
        }
    }
}
