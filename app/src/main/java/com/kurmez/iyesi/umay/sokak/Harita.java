package com.kurmez.iyesi.umay.sokak;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.kurmez.iyesi.umay.sokak.Managers.HaritaManager;
import com.kurmez.iyesi.umay.sokak.Managers.LocationManager;
import com.kurmez.iyesi.umay.sokak.Managers.MarkerManager;

// Harita.java (güncellenmiş)
public class Harita implements OnMapReadyCallback {
    private HaritaManager haritaManager;
    private LocationManager locationManager;
    private MarkerManager markerManager;
    private FragmentActivity activity;

    public Harita(FragmentActivity activity) {
        this.activity = activity;
        this.haritaManager = new HaritaManager(activity);
        this.locationManager = new LocationManager(activity);

        // MarkerManager harita hazır olduğunda oluşturulacak
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.markerManager = new MarkerManager(googleMap, activity);
        // Diğer başlangıç işlemleri...
    }

    // Mevcut metodlar yönetici sınıflara yönlendirilecek
    public void placeDraggableMarker(LatLng location) {
        // markerManager kullanılarak implemente edilecek
    }

    public void cleanup() {
        if (haritaManager != null) haritaManager.cleanup();
        if (locationManager != null) locationManager.cleanup();
        if (markerManager != null) markerManager.cleanup();
    }
}