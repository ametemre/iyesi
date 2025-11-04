package com.kurmez.iyesi.umay.sokak;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import android.util.Log;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.LatLng;
import com.kurmez.iyesi.umay.sokak.Managers.HaritaManager;
import com.kurmez.iyesi.umay.sokak.Managers.LocationManager;
import com.kurmez.iyesi.umay.sokak.Managers.MarkerManager;

/**
 * Harita - map wrapper / coordinator
 * - onMapReady içinde detaylı loglar eklendi
 * - focusOnMyLocation() eklendi (lastLocation + fallback getCurrentLocation)
 * - addDebugTestMarker() eklendi (hızlı test için)
 * - fetchNodesNearby() için yer tutucu + log
 *
 * NOT: HaritaManager/MarkerManager sınıflarının sağladığı methodlara göre
 * marker ekleme/temizleme çağrılarını MarkerManager üzerinden yapman gerekiyor.
 * Buradaki metotlar log ve çağrı noktası sağlar; veri kaynağını (Firestore vb.)
 * fetchNodesNearby içinde entegre et.
 */
public class Harita implements OnMapReadyCallback {
    private static final String TAG = "Harita";

    private HaritaManager haritaManager;
    private LocationManager locationManager;
    private MarkerManager markerManager;
    private FragmentActivity activity;

    private boolean hasCenteredOnce = false; // ilk lokasyonda kamerayı taşıma kontrolü

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
        Log.d(TAG, "Harita ctor - başlatılıyor");
        this.haritaManager = new HaritaManager(activity);
        this.locationManager = new LocationManager(activity);
        // MarkerManager oluşturulacak onMapReady içinde (GoogleMap referansı gerekli)
        this.haritaManager.getMapAsync(this);
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        Log.d(TAG, "onMapReady called. googleMap != null ? " + (googleMap != null));
        try {
            this.markerManager = new MarkerManager(googleMap, activity);
            Log.d(TAG, "MarkerManager oluşturuldu.");
        } catch (Exception e) {
            Log.e(TAG, "MarkerManager oluşturulurken hata: ", e);
        }

        // HaritaManager içindeki googleMap referansını doğrula/logla (varsa)
        try {
            GoogleMap gm = haritaManager.getGoogleMap();
            Log.d(TAG, "haritaManager.getGoogleMap() sonuc: " + (gm != null));
        } catch (Exception e) {
            Log.w(TAG, "haritaManager.getGoogleMap() çağrılırken hata (opsiyonel): " + e.getMessage());
        }

        // Quick debug: test marker ekleme (opsiyonel, prod'da kapat)
        addDebugTestMarker();

        // Eğer izinler uygunsa otomatik olarak konuma odaklanma dene
        if (hasLocationPermission(activity)) {
            focusOnMyLocation();
        } else {
            Log.w(TAG, "onMapReady: konum izni yok, focusAtılamıyor.");
        }
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
        Log.d(TAG, "MapMode set: " + mode);
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
        Log.d(TAG, "MarkerPlacementMode başlatıldı");
    }

    public void stopMarkerPlacementMode() {
        isMarkerPlacementMode = false;
        isRepositionMode = false;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(false);
        }
        Log.d(TAG, "MarkerPlacementMode durduruldu");
    }

    public void startRepositionMode(String markerId) {
        isRepositionMode = true;
        repositionMarkerId = markerId;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(true);
        }
        Log.d(TAG, "Reposition mode başlatıldı. markerId=" + markerId);
    }

    // Touch handling
    public boolean handleOverlayTouch(MotionEvent event) {
        if (isMarkerPlacementMode || isRepositionMode) {
            // TODO: burada gerçek marker placement/reposition logic çalışacak.
            Log.d(TAG, "handleOverlayTouch: marker/place event yakalandı: " + event.getAction());
            // Döndürülen true, üstteki overlay'in dokunmayı tüketmesini sağlar.
            return true;
        }
        return false;
    }

    // Node type selection
    public void showNodeTypeSelectionDialog() {
        // Implement node type selection dialog (activity reference ile)
        Log.d(TAG, "showNodeTypeSelectionDialog çağrıldı (henüz implement yok)");
    }

    /**
     * fetchNodesNearby:
     * - Buraya veri kaynağı (Firestore / REST) entegrasyonu gelecek.
     * - Şu an sadece log'lar + markerManager üzerinden kaydetme noktası sağlanıyor.
     */
    public void fetchNodesNearby(LatLng center, double radius, int limit) {
        Log.d(TAG, "fetchNodesNearby çağrıldı. center=" + center + " radius=" + radius + " limit=" + limit);

        // 1) Önce markerManager mevcut mu kontrol et
        if (markerManager == null) {
            Log.w(TAG, "fetchNodesNearby: markerManager null - marker eklenemiyor");
            return;
        }

        // 2) Mevcut marker'ları temizleyin (MarkerManager API'sine göre değiştir)
        try {
            markerManager.clearAllMarkers(); // Eğer MarkerManager böyle bir method içeriyorsa kullan.
            Log.d(TAG, "markerManager.clearAllMarkers() çağrıldı.");
        } catch (Throwable t) {
            Log.w(TAG, "markerManager.clearAllMarkers() çağrısı başarısız veya method yok: " + t.getMessage());
        }

        // 3) TODO: Buraya gerçek veri çağrısı koy.
        // Örnek: Firestore sorgusu -> success -> nodes listesi -> UI thread'de marker ekle
        // Şimdilik debug amaçlı boş liste işleyelim ve log atalım:
        Log.d(TAG, "fetchNodesNearby: veri kaynağı entegrasyonu yapılmadı (TODO). Eğer gerçek veri yoksa marker görünmez.");
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void setMyLocationIconEnabled(boolean enabled) {
        if (haritaManager.getGoogleMap() != null) {
            try {
                haritaManager.getGoogleMap().setMyLocationEnabled(enabled);
                Log.d(TAG, "MyLocation icon setMyLocationEnabled(" + enabled + ")");
            } catch (SecurityException se) {
                Log.e(TAG, "setMyLocationIconEnabled - izin hatası: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "setMyLocationIconEnabled - beklenmedik hata: ", e);
            }
        } else {
            Log.w(TAG, "setMyLocationIconEnabled: googleMap null");
        }
    }

    // Utility methods
    public boolean isReady() {
        boolean ready = haritaManager != null && haritaManager.isMapReady();
        Log.d(TAG, "isReady() -> " + ready);
        return ready;
    }

    // Konum izni kontrolü için yardımcı metod
    public static boolean hasLocationPermission(Context context) {
        boolean fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    /**
     * focusOnMyLocation
     * - İzin varsa önce getLastLocation() dener, null ise getCurrentLocation() ile fallback yapar.
     * - googleMap != null ise animateCamera ile odaklama yapar.
     * - hasCenteredOnce sayesinde sadece ilk başarılı lokasyonda kamera taşınır (isteğe bağlı).
     */
    public void focusOnMyLocation() {
        Log.d(TAG, "focusOnMyLocation çağrıldı. hasCenteredOnce=" + hasCenteredOnce);
        if (hasCenteredOnce) {
            Log.d(TAG, "focusOnMyLocation: zaten bir kere odaklandı, tekrar etmiyor.");
            return;
        }

        if (!hasLocationPermission(activity)) {
            Log.w(TAG, "focusOnMyLocation: konum izni yok");
            return;
        }

        FusedLocationProviderClient fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity);
        try {
            fused.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            LatLng me = new LatLng(loc.getLatitude(), loc.getLongitude());
                            animateCameraTo(me, 15f);
                            hasCenteredOnce = true;
                            Log.d(TAG, "focusOnMyLocation: lastLocation ile odaklandı -> " + me);
                        } else {
                            Log.w(TAG, "focusOnMyLocation: lastLocation null, getCurrentLocation ile denenecek.");
                            // fallback
                            requestCurrentLocationForCenter(fused);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "focusOnMyLocation: getLastLocation hata, fallback çalışıyor", e);
                        requestCurrentLocationForCenter(fused);
                    });
        } catch (SecurityException se) {
            Log.e(TAG, "focusOnMyLocation: SecurityException (izin eksik)", se);
        }
    }

    // Animasyon kapsayıcı
    public void animateCameraTo(LatLng latLng, float zoom) {
        activity.runOnUiThread(() -> {
            try {
                GoogleMap gm = haritaManager.getGoogleMap();
                if (gm != null) {
                    gm.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, zoom));
                    Log.d(TAG, "animateCameraTo çağrıldı: " + latLng + " zoom=" + zoom);
                } else {
                    Log.w(TAG, "animateCameraTo: googleMap null, kamera taşınamadı");
                }
            } catch (Exception e) {
                Log.e(TAG, "animateCameraTo sırasında hata: ", e);
            }
        });
    }

    // Fallback getCurrentLocation ile tek seferlik center
    private void requestCurrentLocationForCenter(FusedLocationProviderClient fused) {
        com.google.android.gms.tasks.CancellationTokenSource cts = new com.google.android.gms.tasks.CancellationTokenSource();
        try {
            fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            LatLng me = new LatLng(loc.getLatitude(), loc.getLongitude());
                            animateCameraTo(me, 15f);
                            hasCenteredOnce = true;
                            Log.d(TAG, "requestCurrentLocationForCenter: currentLocation ile odaklandı -> " + me);
                        } else {
                            Log.w(TAG, "requestCurrentLocationForCenter: currentLocation null");
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "requestCurrentLocationForCenter hata", e));
        } catch (SecurityException se) {
            Log.e(TAG, "requestCurrentLocationForCenter SecurityException", se);
        }
    }

    // Debug helper: harita hazırken kolayca bir test marker ekler
    public void addDebugTestMarker() {
        try {
            GoogleMap gm = haritaManager.getGoogleMap();
            if (gm != null) {
                LatLng testPos = new LatLng(41.008239, 28.978359); // İstanbul (örnek)
                // MarkerManager üzerinden eklemeyi tercih et, yoksa doğrudan googleMap.addMarker() çağr
                try {
                    markerManager.addDebugMarker(testPos, "TEST"); // MarkerManager böyle bir method içerebilir
                    Log.d(TAG, "addDebugTestMarker: MarkerManager ile test marker istendi");
                } catch (Throwable t) {
                    // Fallback: doğrudan googleMap.addMarker (MarkerManager yoksa)
                    gm.addMarker(new com.google.android.gms.maps.model.MarkerOptions().position(testPos).title("TEST"));
                    gm.moveCamera(CameraUpdateFactory.newLatLngZoom(testPos, 12f));
                    Log.d(TAG, "addDebugTestMarker: googleMap.addMarker fallback ile test marker eklendi");
                }
            } else {
                Log.w(TAG, "addDebugTestMarker: googleMap null, marker eklenemiyor");
            }
        } catch (Exception e) {
            Log.e(TAG, "addDebugTestMarker hata: ", e);
        }
    }

    public void placeDraggableNode(LatLng location) {
        if (markerManager != null) {
            Log.d(TAG, "placeDraggableMarker çağrıldı: " + location);
            try {
                markerManager.placeDraggableMarker(location,null,null);
            } catch (Throwable t) {
                Log.w(TAG, "placeDraggableMarker: MarkerManager.placeDraggableMarker yok veya hata: " + t.getMessage());
            }
        } else {
            Log.w(TAG, "placeDraggableMarker: markerManager null");
        }
    }
    public MarkerManager getNodeManager() {
        return markerManager;
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
        Log.d(TAG, "cleanup çağrıldı");
        if (haritaManager != null) {
            try {
                haritaManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "haritaManager.cleanup hata: " + t.getMessage());
            }
        }
        if (locationManager != null) {
            try {
                locationManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "locationManager.cleanup hata: " + t.getMessage());
            }
        }
        if (markerManager != null) {
            try {
                markerManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "markerManager.cleanup hata: " + t.getMessage());
            }
        }
    }
}
