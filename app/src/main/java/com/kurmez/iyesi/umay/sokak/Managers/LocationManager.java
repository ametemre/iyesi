// LocationManager.java
package com.kurmez.iyesi.umay.sokak.Managers;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.*;
import android.widget.Toast;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import com.google.android.gms.location.*;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.CancellationTokenSource;
import java.util.List;
import java.util.Locale;

public class LocationManager {
    private FragmentActivity activity;
    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;
    private LatLng currentLocation;
    private LocationManagerListener listener;

    public interface LocationManagerListener {
        void onLocationReceived(LatLng location);
        void onLocationError(String error);
    }

    public LocationManager(FragmentActivity activity) {
        this.activity = activity;
        this.locationClient = LocationServices.getFusedLocationProviderClient(activity);
    }

    public void setListener(LocationManagerListener listener) {
        this.listener = listener;
    }

    @SuppressLint("MissingPermission")
    public void getCurrentLocation() {
        if (!hasLocationPermission()) {
            if (listener != null) {
                listener.onLocationError("Konum izni gerekli");
            }
            return;
        }

        CancellationToken token = new CancellationTokenSource().getToken();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, token)
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                        this.currentLocation = latLng;
                        if (listener != null) {
                            listener.onLocationReceived(latLng);
                        }
                    } else {
                        getLastLocation();
                    }
                })
                .addOnFailureListener(e -> {
                    getLastLocation();
                });
    }

    @SuppressLint("MissingPermission")
    private void getLastLocation() {
        locationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        LatLng latLng = new LatLng(location.getLatitude(), location.getLongitude());
                        this.currentLocation = latLng;
                        if (listener != null) {
                            listener.onLocationReceived(latLng);
                        }
                    } else {
                        if (listener != null) {
                            listener.onLocationError("Konum alınamadı");
                        }
                    }
                })
                .addOnFailureListener(e -> {
                    if (listener != null) {
                        listener.onLocationError("Konum hatası: " + e.getMessage());
                    }
                });
    }

    public boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public String getLocationAddress(Context context, Location location) {
        try {
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(location.getLatitude(),
                    location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                String city = address.getLocality() != null ? address.getLocality()
                        : (address.getSubAdminArea() != null ? address.getSubAdminArea() : "");
                String country = address.getCountryName() != null ? address.getCountryName() : "";
                String composed = (city + (city.isEmpty() ? "" : ", ") + country).trim();
                if (!composed.isEmpty()) return composed;
            }
        } catch (Exception ignore) {
            // Geocoder servis yoksa
        }
        return location.getLatitude() + ", " + location.getLongitude();
    }

    public static boolean hasLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    public void cleanup() {
        if (locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }
}