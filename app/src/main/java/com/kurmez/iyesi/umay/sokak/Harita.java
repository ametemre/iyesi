package com.kurmez.iyesi.umay.sokak;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.model.LatLng;
import com.kurmez.iyesi.umay.sokak.Managers.HaritaManager;
import com.kurmez.iyesi.umay.sokak.Managers.LocationManager;
import com.kurmez.iyesi.umay.sokak.Managers.MarkerManager;

public class Harita implements OnMapReadyCallback {
    private HaritaManager haritaManager;
    private LocationManager locationManager;
    private MarkerManager markerManager;
    private FragmentActivity activity;

    // Listener interfaces
    public interface LockModeListener {
        void onLockModeChanged(boolean locked);
    }

    public interface NodeCreationListener {
        void onNodeCreated(String nodeId, String nodeType);
        void onNodeCreationFailed(String error);
    }

    // Map modes enum
    public enum MapMode {
        DEFAULT, FEEDING, NEST, SHELTER, TASK
    }

    private LockModeListener lockModeListener;
    private NodeCreationListener nodeCreationListener;
    private MapMode currentMode = MapMode.DEFAULT;
    private boolean isMarkerPlacementMode = false;
    private boolean isRepositionMode = false;
    private String repositionMarkerId;

    public Harita(FragmentActivity activity) {
        this.activity = activity;
        this.haritaManager = new HaritaManager(activity);
        this.locationManager = new LocationManager(activity);
        this.haritaManager.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        this.markerManager = new MarkerManager(googleMap, activity);
    }

    // Listener setters
    public void setLockModeListener(LockModeListener listener) {
        this.lockModeListener = listener;
    }

    public void setNodeCreationListener(NodeCreationListener listener) {
        this.nodeCreationListener = listener;
    }

    // Mode management
    public void setMode(MapMode mode) {
        this.currentMode = mode;
    }

    public MapMode getCurrentMode() {
        return currentMode;
    }

    // Marker placement mode methods
    public void startMarkerPlacementMode() {
        isMarkerPlacementMode = true;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(true);
        }
    }

    public void stopMarkerPlacementMode() {
        isMarkerPlacementMode = false;
        isRepositionMode = false;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(false);
        }
    }

    public void startRepositionMode(String markerId) {
        isRepositionMode = true;
        repositionMarkerId = markerId;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(true);
        }
    }

    // Touch handling
    public boolean handleOverlayTouch(MotionEvent event) {
        if (isMarkerPlacementMode || isRepositionMode) {
            // Handle marker placement logic here
            return true;
        }
        return false;
    }

    // Node type selection
    public void showNodeTypeSelectionDialog() {
        // Implement node type selection dialog
    }

    // Data methods
    public void fetchNodesNearby(LatLng center, double radius, int limit) {
        // Implement node fetching logic
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void setMyLocationIconEnabled(boolean enabled) {
        if (haritaManager.getGoogleMap() != null) {
            haritaManager.getGoogleMap().setMyLocationEnabled(enabled);
        }
    }

    // Utility methods
    public boolean isReady() {
        return haritaManager != null && haritaManager.isMapReady();
    }


    // Konum izni kontrolü için yardımcı metod
    public static boolean hasLocationPermission(Context context) {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }
    public void placeDraggableMarker(LatLng location) {
        if (markerManager != null) {
            // Implement draggable marker placement
        }
    }
    public static void askAndFill(Context context, EditText editText) {
        // Konum izni kontrolü
        if (!hasLocationPermission(context)) {
            Toast.makeText(context, "Konum izni gerekli", Toast.LENGTH_SHORT).show();
            return;
        }

        // Hemen kullanıcıya dönüş (UI thread) — görünür geri bildirim
        editText.post(() -> editText.setText("Konum alınıyor..."));

        // FusedLocation client
        FusedLocationProviderClient fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context);

        // Önce getLastLocation ile hızlı bir sonuç dene
        try {
            fused.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            double lat = loc.getLatitude();
                            double lon = loc.getLongitude();
                            String formatted = String.format(java.util.Locale.US,
                                    "LangLat: %.6f,%.6f  —  AvatarLoc:%.6f,%.6f",
                                    lat, lon, lat, lon);
                            // UI thread güvenli güncelleme
                            editText.post(() -> {
                                editText.setText(formatted);
                                // opsiyonel: koordinatları tag'e kaydet
                                editText.setTag(lat + "," + lon);
                            });
                        } else {
                            // lastLocation null ise aktif istek yap
                            requestCurrentLocation(fused, editText);
                        }
                    })
                    .addOnFailureListener(e -> {
                        // hata olursa fallback aktif istek
                        requestCurrentLocation(fused, editText);
                    });
        } catch (SecurityException se) {
            // Güvenlik exception'ı olursa kullanıcıya bildir
            editText.post(() -> editText.setText("Konum alınamadı (izin)."));
        }
    }

    // Yardımcı private method: aktif istek (getCurrentLocation) yapar
    private static void requestCurrentLocation(FusedLocationProviderClient fused, EditText editText) {
        // CancellationTokenSource yerel olarak oluştur
        com.google.android.gms.tasks.CancellationTokenSource cts = new com.google.android.gms.tasks.CancellationTokenSource();
        try {
            fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            double lat = loc.getLatitude();
                            double lon = loc.getLongitude();
                            String formatted = String.format(java.util.Locale.US, "long: %.6f  —  lat:%.6f", lat, lon);
                            editText.post(() -> {
                                editText.setText(formatted);
                                editText.setTag(lat + "," + lon);
                            });
                        } else {
                            editText.post(() -> editText.setText("Konum alınamadı"));
                        }
                    })
                    .addOnFailureListener(e -> {
                        editText.post(() -> editText.setText("Konum alınamadı"));
                    });
        } catch (SecurityException se) {
            editText.post(() -> editText.setText("Konum alınamadı (izin)."));
        }
    }


    /**
     * İzin sonuçları için callback
     */
    public static void onRequestPermissionsResult(FragmentActivity activity, int requestCode, int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // İzin verildi
                Toast.makeText(activity, "Konum izni verildi", Toast.LENGTH_SHORT).show();
            } else {
                // İzin reddedildi
                Toast.makeText(activity, "Konum izni reddedildi", Toast.LENGTH_LONG).show();
            }
        }
    }

    // İzin kodu için sabit
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;


    public void cleanup() {
        if (haritaManager != null) haritaManager.cleanup();
        if (locationManager != null) locationManager.cleanup();
        if (markerManager != null) markerManager.cleanup();
    }
}