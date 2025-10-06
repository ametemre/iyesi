package com.kurmez.iyesi.umay.sokak.Managers;

import android.content.Context;
import android.graphics.Point;
import android.util.Log;

import com.google.android.gms.maps.*;
import com.google.android.gms.maps.model.*;
import com.kurmez.iyesi.R; // EKLENDİ

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

public class HaritaManager implements OnMapReadyCallback {
    private GoogleMap mMap;
    private FragmentActivity activity;
    private boolean mapReady = false;
    private OnMapReadyCallback externalCallback;

    public HaritaManager(FragmentActivity activity) {
        this.activity = activity;
        initializeMap();
    }

    // Harita.java'dan çağrılan metod EKLENDİ
    public void getMapAsync(OnMapReadyCallback callback) {
        this.externalCallback = callback;
        initializeMap();
    }

    private void initializeMap() {
        SupportMapFragment mapFragment = (SupportMapFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        } else {
            Log.e("HaritaManager", "Map fragment bulunamadı! R.id.map kontrol edin.");
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        mapReady = true;
        configureMapSettings();

        // External callback'i çağır (Harita.java'ya bildirim)
        if (externalCallback != null) {
            externalCallback.onMapReady(googleMap);
        }
    }

    private void configureMapSettings() {
        if (mMap != null) {
            mMap.getUiSettings().setAllGesturesEnabled(true);
            mMap.getUiSettings().setScrollGesturesEnabledDuringRotateOrZoom(true);
            mMap.getUiSettings().setZoomControlsEnabled(true);
            mMap.getUiSettings().setCompassEnabled(true);

            try {
                // Resource kontrolü - eğer map_style_json yoksa atla
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

    public void animateCamera(LatLng position, float zoom, int duration) {
        if (mMap != null) {
            mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, zoom), duration, null);
        }
    }

    public LatLng screenPointToLatLng(Point point) {
        return mMap != null ? mMap.getProjection().fromScreenLocation(point) : null;
    }

    public Point latLngToScreenPoint(LatLng latLng) {
        return mMap != null ? mMap.getProjection().toScreenLocation(latLng) : null;
    }

    public boolean isMapReady() {
        return mapReady && mMap != null;
    }

    public GoogleMap getGoogleMap() {
        return mMap;
    }

    // Harita kontrolleri için yardımcı metodlar EKLENDİ
    public void setMyLocationEnabled(boolean enabled) {
        if (mMap != null) {
            try {
                mMap.setMyLocationEnabled(enabled);
            } catch (SecurityException e) {
                Log.e("HaritaManager", "Konum izni gerekli: " + e.getMessage());
            }
        }
    }

    public void setOnMapClickListener(GoogleMap.OnMapClickListener listener) {
        if (mMap != null) {
            mMap.setOnMapClickListener(listener);
        }
    }

    public void setOnMapLongClickListener(GoogleMap.OnMapLongClickListener listener) {
        if (mMap != null) {
            mMap.setOnMapLongClickListener(listener);
        }
    }

    public void setOnMarkerClickListener(GoogleMap.OnMarkerClickListener listener) {
        if (mMap != null) {
            mMap.setOnMarkerClickListener(listener);
        }
    }

    public void cleanup() {
        if (mMap != null) {
            mMap.clear();
            mMap = null;
        }
        mapReady = false;
    }
}