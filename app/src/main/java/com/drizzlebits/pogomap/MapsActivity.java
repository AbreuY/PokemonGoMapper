package com.drizzlebits.pogomap;

import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.os.Bundle;

import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ToggleButton;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.firebase.crash.FirebaseCrash;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;

public class MapsActivity extends AppCompatActivity implements OnMapReadyCallback, PokemonManager.PokemonListener,
        GmsLocationFinder.ConnectionListener, PokemonManager.LocationFinder {
    private static final String TAG = MapsActivity.class.getSimpleName();

    private static final int PERMISSIONS_REQUEST_FINE_LOCATION = 1337;

    private static final String ADS_ID = "ca-app-pub-8757602030251852~5570954726";

    private static final String BUNDLE_KEY_CAMERA = "camera";
    private static final String BUNDLE_KEY_FILTER = "filter";

    private static final Object sDataLock = new Object();

    private PokemonManager mPokemonManager;
    private GoogleMap mMap;
    private ToggleButton mLocationToggle;
    private TextView mZoomFilterText;
    private GmsLocationFinder mLocationFinder;
    private CameraPosition mSavedCameraPosition;

    private HashMap<Pokemon, Marker> mPokemonMarkers;

    private Runnable mLoadExistingRunnable;

    private boolean[] mFilter;
    private boolean[] mTempFilter;
    private boolean mZoomFilter;
    private int mNumVisiblePokemon;

    private Handler mMainHandler;
    private Runnable mUpdateLocationRunnable;
    private LatLng mMapLocation;
    private boolean mMapLocationOn;
    private Marker mDebugMarker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);

        mMainHandler = new Handler(getMainLooper());
        mUpdateLocationRunnable = new Runnable() {
            @Override
            public void run() {
                mMapLocation = mMap.getCameraPosition().target;
                mMainHandler.postDelayed(this, 1000);
            }
        };

        mZoomFilterText = (TextView) findViewById(R.id.zoom_filter_text);

        mLocationToggle = (ToggleButton) findViewById(R.id.location_toggle);
        mLocationToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mMapLocationOn = isChecked;

                if (isChecked) {
                    mMainHandler.post(mUpdateLocationRunnable);
                } else {
                    mMainHandler.removeCallbacks(mUpdateLocationRunnable);
                }
            }
        });

        mPokemonManager = PokemonManager.getInstance(this);
        mPokemonManager.setLocationFinder(this);

        mPokemonMarkers = new HashMap<>();

        if (savedInstanceState != null) {
            mSavedCameraPosition = savedInstanceState.getParcelable(BUNDLE_KEY_CAMERA);
            mFilter = savedInstanceState.getBooleanArray(BUNDLE_KEY_FILTER);
        }
        if (mFilter == null) {
            String json = getPrefs().getString(BUNDLE_KEY_FILTER, null);
            if (json != null) {
                Type type = new TypeToken<boolean[]>() {
                }.getType();
                mFilter = new Gson().fromJson(json, type);
            } else {
                mFilter = new boolean[mPokemonManager.getNumPokemon()];
                Arrays.fill(mFilter, true);
            }
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ android.Manifest.permission.ACCESS_FINE_LOCATION },
                    PERMISSIONS_REQUEST_FINE_LOCATION);
        } else {
            connectToPokemon();
        }

        MobileAds.initialize(getApplicationContext(), ADS_ID);
        AdView mAdView = (AdView) findViewById(R.id.adView);
        AdRequest adRequest = new AdRequest.Builder()/*.addTestDevice("3BB53778AAAF2CE1AF6ADE3B706393DA")*/.build();
        mAdView.loadAd(adRequest);
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
                        mFilter = Arrays.copyOf(mTempFilter, mTempFilter.length);
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
                PokemonNetwork.getInstance(this).logOut();
                finish();
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }

    private void applyFilter() {
        synchronized (sDataLock) {
            mNumVisiblePokemon = 0;
            for (Pokemon pokemon : mPokemonMarkers.keySet()) {
                boolean visible = mFilter[pokemon.Number - 1];
                mPokemonMarkers.get(pokemon).setVisible(visible);
                if (visible) mNumVisiblePokemon++;
            }

            if (checkZoomFiltered()) {
                for (Pokemon pokemon : mPokemonMarkers.keySet()) {
                    mPokemonMarkers.get(pokemon).setVisible(false);
                }
            }
            mZoomFilterText.setVisibility(mZoomFilter ? View.VISIBLE : View.GONE);
        }

        getPrefs().edit().putString(BUNDLE_KEY_FILTER, new Gson().toJson(mFilter)).apply();
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
        mLocationFinder = GmsLocationFinder.getInstance(this);
        if (!mLocationFinder.isReady()) {
            mLocationFinder.addListener(this);
            mLocationFinder.init();
        } else {
            onGmsLocationConnected();
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

        mMap.setOnCameraChangeListener(new GoogleMap.OnCameraChangeListener() {
            @Override
            public void onCameraChange(CameraPosition cameraPosition) {
                synchronized (sDataLock) {
                    mSavedCameraPosition = cameraPosition;

                    boolean curZoomFilter = mZoomFilter;
                    checkZoomFiltered();
                    if (curZoomFilter != mZoomFilter) return;

                    for (Pokemon pokemon : mPokemonMarkers.keySet()) {
                        mPokemonMarkers.get(pokemon).setVisible(!mZoomFilter && mFilter[pokemon.Number - 1]);
                    }
                    mZoomFilterText.setVisibility(mZoomFilter ? View.VISIBLE : View.GONE);
                }
            }
        });

        //mMap.setOnCameraChangeListener(new ClusterManager<PokemonClusterItem>(this, mMap));

        if (mLocationFinder != null && mLocationFinder.isReady()) {
            startPollingForPokemon();
        }
    }

    private boolean checkZoomFiltered() {
        float zoomFactor = Math.min(100, mNumVisiblePokemon) / mSavedCameraPosition.zoom * 2f;
        mZoomFilter = mSavedCameraPosition.zoom < zoomFactor;
        return mZoomFilter;
    }

    @Override
    public void onGmsLocationConnected() {
        if (mMap != null) {
            startPollingForPokemon();
        }
    }

    @Override
    public void onGmsLocationDisconnected() {

    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBooleanArray(BUNDLE_KEY_FILTER, mFilter);
        outState.putParcelable(BUNDLE_KEY_CAMERA, mMap.getCameraPosition());

        super.onSaveInstanceState(outState);
    }

    @SuppressWarnings("MissingPermission")
    private void startPollingForPokemon() {
        mPokemonManager.startSearching();
        mMap.setMyLocationEnabled(true);

        if (mSavedCameraPosition == null) {
            Location location = mLocationFinder.getMyLocation();
            LatLng loc = new LatLng(location.getLatitude(), location.getLongitude());
        /*mMap.addMarker(new MarkerOptions().position(loc).title("You")
                .icon(BitmapDescriptorFactory.fromResource(R.mipmap.ic_location)));*/
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(loc, 17));
        } else {
            mMap.moveCamera(CameraUpdateFactory.newCameraPosition(mSavedCameraPosition));
        }

        if (mLoadExistingRunnable == null) {
            mLoadExistingRunnable = new Runnable() {
                @Override
                public void run() {
                    synchronized (sDataLock) {
                        Pokemon[] pokemons = mPokemonManager.getPokemon();
                        for (Pokemon pokemon : pokemons) {
                            addPokemonToMap(pokemon);
                        }

                        mPokemonManager.setPokemonListener(MapsActivity.this);
                        mLoadExistingRunnable = null;
                    }
                }
            };
            runOnUiThread(mLoadExistingRunnable);
        }
    }

    @Override
    public void onPokemonFound(final Pokemon pokemon) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mPokemonMarkers.containsKey(pokemon)) {
                    FirebaseCrash.report(new Throwable("Duplicate pokemon: " + pokemon));
                    return;
                }

                addPokemonToMap(pokemon);
            }
        });
    }

    @Override
    public void onPokemonExpired(final Pokemon pokemon) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (sDataLock) {
                    mPokemonMarkers.remove(pokemon).remove();
                }
            }
        });
    }

    private void addPokemonToMap(Pokemon pokemon) {
        synchronized (sDataLock) {
            LatLng loc = new LatLng(pokemon.latitude, pokemon.longitude);

            SimpleDateFormat formatter = new SimpleDateFormat("hh:mm:ss a");
            String dateString = formatter.format(new Date(pokemon.expirationTime));
            String title = pokemon.Name + " disappears at " + dateString;

            Marker marker = mMap.addMarker(new MarkerOptions().position(loc).title(title)
                    .icon(BitmapDescriptorFactory.fromResource(mPokemonManager.getIconResByNumber(pokemon.Number))));

            mPokemonMarkers.put(pokemon, marker);

            if (!mFilter[pokemon.Number - 1]) {
                marker.setVisible(false);
            } else {
                mNumVisiblePokemon++;
            }
        }
    }

    @Override
    public LatLng getLocation() {
        if (mMapLocationOn) {
            return mMapLocation;
        } else {
            Location loc = mLocationFinder.getMyLocation();
            return new LatLng(loc.getLatitude(), loc.getLongitude());
        }
    }

    @Override
    public boolean isReady() {
        return mLocationFinder.isReady();
    }

    @Override
    public void drawDebugMarker(final LatLng loc) {
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mDebugMarker != null) {
                    mDebugMarker.remove();
                }
                mDebugMarker = mMap.addMarker(new MarkerOptions().position(loc));
            }
        });
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
            holder.itemView.setBackgroundColor(Color.argb(20 - (20 / ((position % 2) + 1)), 0, 0, 0));

            holder.mCheckBox.setChecked(mTempFilter[position]);
            holder.mIcon.setImageResource(mPokemonManager.getIconResByNumber(position + 1));
            holder.mName.setText(mPokemonManager.getNameByNumber(position + 1));

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean checked = holder.mCheckBox.isChecked();
                    holder.mCheckBox.setChecked(!checked);
                    mTempFilter[holder.getAdapterPosition()] = !checked;
                }
            });
        }

        @Override
        public int getItemCount() {
            return mPokemonManager.getNumPokemon();
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(TAG, MODE_PRIVATE);
    }
}
