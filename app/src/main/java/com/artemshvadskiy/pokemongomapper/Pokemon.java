package com.artemshvadskiy.pokemongomapper;

import com.google.android.gms.maps.model.Marker;

/**
 * Created by Artem on 7/21/2016.
 */
public class Pokemon {
    // JSON fields
    int Number;
    String Name;

    // App fields
    String spawnId;
    long expirationTime;
    Marker marker;

    public Pokemon(Pokemon other) {
        Number = other.Number;
        Name = other.Name;
    }

    public String getResourceName() {
        return "ic_pokemon_" + Name.toLowerCase().replace(" ", "_").replace(".", "").replace("\'","");
    }
}
