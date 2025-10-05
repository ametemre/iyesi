// HaritaManager.java
package com.kurmez.iyesi.umay.sokak.Managers;

import android.content.Context;
import android.graphics.Point;
import android.util.Log;

import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

public class HaritaManager implements OnMapReadyCallback {
    private GoogleMap mMap;
    private FragmentActivity activity;
    private boolean mapReady = false;

    public HaritaManager(FragmentActivity activity) {
        this.activity = activity;
        initializeMap();
    }

    private void initializeMap() {
        SupportMapFragment mapFragment = (SupportMapFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mapReady = true;
        configureMapSettings();
    }

    private void configureMapSettings() {
        if (mMap != null) {
            mMap.getUiSettings().setAllGesturesEnabled(true);
            mMap.getUiSettings().setScrollGesturesEnabledDuringRotateOrZoom(true);

            try {
                boolean success = mMap.setMapStyle(
                        MapStyleOptions.loadRawResourceStyle(activity, R.raw.map_style_json));
                if (!success) {
                    Log.e("HaritaManager", "Harita stili yüklenemedi.");
                }
            } catch (Exception e) {
                Log.e("HaritaManager", "Harita stil uygulama hatası: " + e.getMessage());
            }
        }
    }

    public void moveCamera(LatLng position, float zoom) {
        if (mMap != null) {
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
        }
    }

    public void animateCamera(LatLng position, float zoom) {
        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom));
        }
    }

    public LatLng screenPointToLatLng(Point point) {
        return mMap != null ? mMap.getProjection().fromScreenLocation(point) : null;
    }

    public boolean isMapReady() {
        return mapReady && mMap != null;
    }

    public GoogleMap getGoogleMap() {
        return mMap;
    }

    public void cleanup() {
        if (mMap != null) {
            mMap.clear();
        }
    }
}