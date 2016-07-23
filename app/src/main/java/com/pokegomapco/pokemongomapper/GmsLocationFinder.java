package com.pokegomapco.pokemongomapper;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import java.util.HashSet;

public class GmsLocationFinder implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    private static final String TAG = "PKGOMAP.GmsLocationFinder";

    private static GmsLocationFinder sInstance;

    public synchronized static GmsLocationFinder getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GmsLocationFinder(context.getApplicationContext());
        }
        return sInstance;
    }

    public interface ConnectionListener {
        void onGmsLocationConnected();
        void onGmsLocationDisconnected();
    }

    private final Context mContext;
    private final GoogleApiClient mGoogleApiClient;
    private final HashSet<ConnectionListener> mListeners;

    private boolean mReady;

    private GmsLocationFinder(Context context) {
        mContext = context;

        mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        mListeners = new HashSet<>();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        mReady = true;
        for (ConnectionListener listener : mListeners) {
            listener.onGmsLocationConnected();
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        mReady = false;
        for (ConnectionListener listener : mListeners) {
            listener.onGmsLocationDisconnected();
        }
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    public void init() {
        mGoogleApiClient.connect();
    }

    public void addListener(ConnectionListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(ConnectionListener listener) {
        mListeners.remove(listener);
    }

    public boolean isReady() {
        return mReady;
    }

    public Location getMyLocation() {
        int permissionCheck = ContextCompat.checkSelfPermission(mContext, Manifest.permission.ACCESS_FINE_LOCATION);
        if (permissionCheck == PackageManager.PERMISSION_DENIED) {
            Log.e(TAG, "Location permission not granted.");
            return null;
        }

        return LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
    }
}
