package com.artemshvadskiy.pokemongomapper;

import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.location.Location;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.os.Bundle;

import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.PriorityQueue;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, PokemonNetwork.PokemonListener, GmsLocationFinder.ConnectionListener {
    private static final int PERMISSIONS_REQUEST_FINE_LOCATION = 1337;

    private static final Object sLock = new Object();

    private PokemonNetwork mPokemonNetwork;
    private GoogleMap mMap;
    private GmsLocationFinder mLocationFinder;

    private PriorityQueue<Pokemon> mExpirationQueue;
    private HashMap<String, Pokemon> mPokemonBySpawnId;
    private HashMap<Integer, List<Pokemon>> mPokemonByNumber;
    private Pokemon[] mPokemonData;

    private boolean[] mFilter;
    private boolean[] mTempFilter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

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

        String json = null;
        try {
            InputStream is = getResources().openRawResource(R.raw.pokemon);
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            json = new String(buffer, "UTF-8");
        } catch (IOException ex) {
            ex.printStackTrace();
            Log.e("pokemon", "unable to load pokemon data");
            return;
        }

        Gson gson = new GsonBuilder().create();

        Type listType = new TypeToken<ArrayList<Pokemon>>(){}.getType();
        List<Pokemon> pokemons = gson.fromJson(json, listType);
        mPokemonData = new Pokemon[pokemons.size() + 1];
        for (Pokemon pokemon : pokemons) {
            mPokemonData[pokemon.Number] = pokemon;
        }

        mFilter = new boolean[pokemons.size() + 1];
        Arrays.fill(mFilter, true);

        mPokemonByNumber = new HashMap<>();

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            /*// Should we show an explanation?
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, android.Manifest.permission.ACCESS_FINE_LOCATION)) {

                // Show an expanation to the user *asynchronously* -- don't block
                // this thread waiting for the user's response! After the user
                // sees the explanation, try again to request the permission.

            } else {*/

                // No explanation needed, we can request the permission.

                ActivityCompat.requestPermissions(this,
                        new String[]{ android.Manifest.permission.ACCESS_FINE_LOCATION },
                        PERMISSIONS_REQUEST_FINE_LOCATION);

                // MY_PERMISSIONS_REQUEST_READ_CONTACTS is an
                // app-defined int constant. The callback method gets the
                // result of the request.
            //}
        } else {
            connectToPokemon();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }

                    synchronized (sLock) {
                        while (!mExpirationQueue.isEmpty()) {
                            final Pokemon pokemon = mExpirationQueue.peek();
                            if (pokemon.expirationTime < System.currentTimeMillis()) {
                                mExpirationQueue.remove();

                                mPokemonByNumber.get(pokemon.Number).remove(pokemon);
                                mPokemonBySpawnId.get(pokemon.spawnId);

                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        pokemon.marker.remove();
                                    }
                                });
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

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.map_action_bar, menu);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_filter:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setMessage("Filter by pokemon");

                mTempFilter = Arrays.copyOf(mFilter, mFilter.length);

                View layout = getLayoutInflater().inflate(R.layout.filter_dialog, null);

                final RecyclerView list = (RecyclerView) layout.findViewById(R.id.filter_list);
                list.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
                list.setAdapter(new PokemonRecyclerAdapter());

                layout.findViewById(R.id.select_all).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Arrays.fill(mTempFilter, true);
                        list.getAdapter().notifyDataSetChanged();
                    }
                });

                layout.findViewById(R.id.select_none).setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Arrays.fill(mTempFilter, false);
                        list.getAdapter().notifyDataSetChanged();
                    }
                });

                builder.setView(layout);

                builder.setPositiveButton("Apply", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        mFilter = mTempFilter;
                        applyFilter();
                    }
                });

                builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // noop
                    }
                });

                builder.show();


                return true;

            case R.id.action_signout:
                PokemonNetwork.getInstance(this).signOut();
                finish();
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }

    private void applyFilter() {
        for (Integer pokemonNumber : mPokemonByNumber.keySet()) {
            List<Pokemon> pokemons = mPokemonByNumber.get(pokemonNumber);
            for (Pokemon pokemon : pokemons) {
                pokemon.marker.setVisible(mFilter[pokemonNumber]);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_FINE_LOCATION: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    // permission was granted, yay! Do the
                    // contacts-related task you need to do.
                    connectToPokemon();

                } else {

                    // permission denied, boo! Disable the
                    // functionality that depends on this permission.
                    finish();
                }
                return;
            }

            // other 'case' lines to check for other
            // permissions this app might request
        }
    }

    private void connectToPokemon() {
        mPokemonNetwork = PokemonNetwork.getInstance(this);
        mPokemonNetwork.startSearching(this);

        mLocationFinder = GmsLocationFinder.getInstance(this);
        if (!mLocationFinder.isReady()) {
            mLocationFinder.addListener(this);
            mLocationFinder.init();
        } else {
            onConnected();
        }
    }

    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        if (mLocationFinder != null && mLocationFinder.isReady()) {
            zoomToSelf();
        }
    }

    @Override
    public void onPokemonFound(final String spawnId, final double lat, final double lng, final int pokemonId, final long timeTilHidden) {
        if (mMap == null) return;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (sLock) {
                    if (mPokemonBySpawnId.containsKey(spawnId)) return;

                    LatLng loc = new LatLng(lat, lng);

                    Pokemon pokemon = new Pokemon(mPokemonData[pokemonId]);
                    pokemon.expirationTime = System.currentTimeMillis() + timeTilHidden;

                    SimpleDateFormat formatter = new SimpleDateFormat("hh:mm:ssa");
                    String dateString = formatter.format(new Date(pokemon.expirationTime));
                    String title = pokemon.Name + " disappears at: " + dateString;

                    Marker marker = mMap.addMarker(new MarkerOptions().position(loc).title(title)
                            .icon(BitmapDescriptorFactory.fromResource(getIconRes(pokemonId))));

                    pokemon.marker = marker;
                    mPokemonBySpawnId.put(spawnId, pokemon);

                    List<Pokemon> samePokemon = mPokemonByNumber.get(pokemonId);
                    if (samePokemon == null) {
                        samePokemon = new ArrayList<>();
                        mPokemonByNumber.put(pokemonId, samePokemon);
                    }
                    samePokemon.add(pokemon);

                    mExpirationQueue.add(pokemon);

                    if (!mFilter[pokemonId]) {
                        marker.setVisible(false);
                    }
                    //mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 18));
                }
            }
        });
    }

    private int getIconRes(int pokemonId) {
        String name = mPokemonData[pokemonId].getResourceName();

        Resources resources = getResources();
        final int resourceId = resources.getIdentifier(name, "drawable", getPackageName());
        if (resourceId == 0) {
            Log.e("artem", "failed resolution of pokemon: " + name);
        }
        return resourceId;
    }

    @Override
    public void onConnected() {
        if (mMap != null) {
            zoomToSelf();
        }
    }

    @Override
    public void onDisconnected() {

    }

    private void zoomToSelf() {
        mMap.setMyLocationEnabled(true);

        Location location = mLocationFinder.getMyLocation();
        LatLng loc = new LatLng(location.getLatitude(), location.getLongitude());
        /*mMap.addMarker(new MarkerOptions().position(loc).title("You")
                .icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_location)));*/
        mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(loc, 17));
    }

    private class PokemonViewHolder extends RecyclerView.ViewHolder {
        private CheckBox mCheckBox;
        private ImageView mIcon;
        private TextView mName;

        public PokemonViewHolder(View v) {
            super(v);

            mCheckBox = (CheckBox) v.findViewById(R.id.enabled);
            mIcon = (ImageView) v.findViewById(R.id.icon);
            mName = (TextView) v.findViewById(R.id.name);
        }
    }

    private class PokemonRecyclerAdapter extends RecyclerView.Adapter<PokemonViewHolder> {
        @Override
        public PokemonViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = getLayoutInflater().inflate(R.layout.pokemon_filter_list_item, parent, false);
            return new PokemonViewHolder(v);
        }

        @Override
        public void onBindViewHolder(final PokemonViewHolder holder, int position) {
            position++;
            holder.mCheckBox.setChecked(mTempFilter[position]);
            holder.mIcon.setImageResource(getIconRes(position));
            holder.mName.setText(mPokemonData[position].Name);

            holder.mCheckBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mTempFilter[holder.getAdapterPosition() + 1] = isChecked;
                }
            });
        }

        @Override
        public int getItemCount() {
            return mPokemonData.length - 1;
        }
    }
}
