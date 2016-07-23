package com.pokegomapco.pokemongomapper;

public class Pokemon {
    // JSON fields
    int Number;
    String Name;

    // App fields
    String spawnId;
    long expirationTime;
    double latitude;
    double longitude;

    public Pokemon(Pokemon other) {
        Number = other.Number;
        Name = other.Name;
    }

    public String getResourceName() {
        return "ic_pokemon_" + Name.toLowerCase().replace(" ", "_").replace(".", "").replace("\'","");
    }
}
