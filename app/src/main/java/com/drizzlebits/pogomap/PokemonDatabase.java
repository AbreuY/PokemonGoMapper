package com.drizzlebits.pogomap;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

public class PokemonDatabase {
    /* Inner class that defines the table contents */
    public static abstract class PokemonEntry implements BaseColumns {
        public static final String TABLE_NAME = "pokemon";
        public static final String COLUMN_NAME_SPAWN_ID = "spawnId";
        public static final String COLUMN_NAME_LATITUDE = "latitude";
        public static final String COLUMN_NAME_LONGITUDE = "longitude";
        public static final String COLUMN_NAME_POKEMON_NUMBER = "pokemonNumber";
        public static final String COLUMN_NAME_EXPIRATION_TIME = "expirationTime";
    }

    private static final String TEXT_TYPE = " TEXT";
    private static final String INTEGER_TYPE = " INTEGER";
    private static final String FLOAT_TYPE = " REAL";
    private static final String COMMA_SEP = ",";
    private static final String SQL_CREATE_ENTRIES =
            "CREATE TABLE " + PokemonEntry.TABLE_NAME + " (" +
                    PokemonEntry._ID + INTEGER_TYPE + " PRIMARY KEY" + COMMA_SEP +
                    PokemonEntry.COLUMN_NAME_SPAWN_ID + TEXT_TYPE + COMMA_SEP +
                    PokemonEntry.COLUMN_NAME_LATITUDE + FLOAT_TYPE + COMMA_SEP +
                    PokemonEntry.COLUMN_NAME_LONGITUDE + FLOAT_TYPE + COMMA_SEP +
                    PokemonEntry.COLUMN_NAME_POKEMON_NUMBER + INTEGER_TYPE + COMMA_SEP +
                    PokemonEntry.COLUMN_NAME_EXPIRATION_TIME + INTEGER_TYPE +
                    //... // Any other options for the CREATE command
                    " )";

    private static final String SQL_DELETE_ENTRIES =
            "DROP TABLE IF EXISTS " + PokemonEntry.TABLE_NAME;

    public static class PokemonDbHelper extends SQLiteOpenHelper {
        // If you change the database schema, you must increment the database version.
        public static final int DATABASE_VERSION = 1;
        public static final String DATABASE_NAME = "Pokemon.db";

        public PokemonDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(SQL_CREATE_ENTRIES);
        }
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // This database is only a cache for online data, so its upgrade policy is
            // to simply to discard the data and start over
            db.execSQL(SQL_DELETE_ENTRIES);
            onCreate(db);
        }
        public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            onUpgrade(db, oldVersion, newVersion);
        }
    }
}
