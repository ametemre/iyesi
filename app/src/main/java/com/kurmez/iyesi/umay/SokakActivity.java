package com.kurmez.iyesi.umay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import android.text.TextUtils;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.libraries.places.api.Places;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;
import com.kurmez.iyesi.kurmes.utilities.helper.BaksiHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.umay.sokak.Managers.LocationManager;

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.firebase.firestore.GeoPoint;

public class SokakActivity extends FragmentActivity implements NodeDetailsBottomSheet.Host, Harita.LockModeListener, Harita.NodeCreationListener {
    private String TAG = "SokakActivity";
    private FloatingActionButton selectedFab = null;
    public Kurmes kurmes;
    private LocationManager locationManager;
    private BaksiHelper baksiHelper;
    private int baksiInitRetryCount = 0;
    private static final int MAX_BAKSI_INIT_RETRY = 5;
    // Simplified marker mode state
    private boolean isMarkerMode = false;

    // FAB related variables
    private View lockModeOverlay;
    private boolean isLockedMode = false;
    private MiniFabs miniFabs;
    private FloatingActionButton mainFab, beslemeFab, bolgeFab, nakilFab, soundFab;

    // Spinner related
    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};

    // Harita instance
    private Harita harita;
    private View touchOverlay;
    // Animation variables
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;

    private LatLng lastKnownLocation;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);
        Log.d(TAG, "=== SOKAK ACTIVITY BAŞLANGIÇ ===");

        if (!ensureLoggedInOrGoLogin()) return;

        // Places API başlatma
        initializePlacesAPI();
        initializeHarita();
        initializeSpinners();
        initializeFABs();
        initializeLockModeOverlay();

        touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            touchOverlay.setOnTouchListener(null);
            touchOverlay.setClickable(false);
            touchOverlay.setVisibility(View.GONE);
        }
        Log.d(TAG, "=== SOKAK ACTIVITY BAŞLANGIÇ TAMAMLANDI ===");
    }

    private void initializePlacesAPI() {
        if (!Places.isInitialized()) {
            String apiKey = null;
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
                Bundle bundle = ai.metaData;
                apiKey = bundle.getString("com.google.android.geo.API_KEY");
                Log.d(TAG, "Manifest'ten API key alındı: " + (apiKey != null ? "EVET" : "HAYIR"));
            } catch (Exception e) {
                Log.e(TAG, "Manifest'ten API key alınamadı: " + e.getMessage());
            }

            if (apiKey == null || apiKey.isEmpty()) {
                Log.e(TAG, "Google Places API key bulunamadı!");
                Toast.makeText(this, "API key yapılandırması eksik", Toast.LENGTH_LONG).show();
                return;
            }

            try {
                Places.initialize(getApplicationContext(), apiKey);
                Log.d(TAG, "Places başarıyla initialize edildi");
            } catch (Exception e) {
                Log.e(TAG, "Places initialize hatası: " + e.getMessage());
            }
        } else {
            Log.d(TAG, "Places zaten initialize edilmiş");
        }
    }

    private void initializeHarita() {
        Log.d(TAG, "Harita başlatılıyor...");
        harita = new Harita(this);
        harita.setLockModeListener(this);
        harita.setNodeCreationListener(this);

        // LocationManager'ı başlat - EKLENDİ
        locationManager = new LocationManager(this);

        setupMapWithMarkers();
    }

    private void initializeBaksiHelper() {
        Log.d(TAG, "BaksiHelper başlatılıyor...");

        if (harita == null || harita.getNodeManager() == null) {
            if (baksiInitRetryCount < MAX_BAKSI_INIT_RETRY) {
                baksiInitRetryCount++;
                Log.w(TAG, "NodeManager hazır değil, tekrar deneme " + baksiInitRetryCount + "/" + MAX_BAKSI_INIT_RETRY);
                new Handler(Looper.getMainLooper()).postDelayed(this::initializeBaksiHelper, 2000);
            } else {
                Log.e(TAG, "BaksiHelper başlatılamadı: Max deneme aşıldı");
                Toast.makeText(this, "Harita bileşenleri yüklenemedi", Toast.LENGTH_LONG).show();
            }
            return;
        }

        baksiInitRetryCount = 0;

        try {
            baksiHelper = new BaksiHelper(this, harita.getNodeManager(), new BaksiHelper.BaksiHelperListener() {
                @Override
                public void onVetsLoaded(int count, double radius) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "BaksiHelper: " + count + " veteriner bulundu, yarıçap: " + radius + "m");
                        if (count > 0) {
                            String radiusText = radius + "m";
                            if (radius >= 1000) {
                                radiusText = String.format("%.1fkm", radius / 1000.0);
                            }
                            Toast.makeText(SokakActivity.this, count + " veteriner bulundu (" + radiusText + ")", Toast.LENGTH_SHORT).show();
                        } else {
                            suggestWiderSearch(radius);
                        }
                    });
                }

                @Override
                public void onVetsLoadFailed(String error) {
                    runOnUiThread(() -> {
                        Log.e(TAG, "BaksiHelper hatası: " + error);

                        // PERMISSION_DENIED hatası durumunda tekrar deneme yapma
                        if (error.contains("PERMISSION_DENIED") || error.contains("insufficient permissions")) {
                            Toast.makeText(SokakActivity.this,
                                    "Veteriner verilerine erişim izniniz yok. Lütfen yetkilendirme ayarlarını kontrol edin.",
                                    Toast.LENGTH_LONG).show();
                            return; // Daha fazla işlem yapma
                        }

                        // Diğer hatalar için
                        if (!error.contains("Firebase") && !error.contains("permission")) {
                            Toast.makeText(SokakActivity.this, "Veteriner yükleme hatası: " + error, Toast.LENGTH_LONG).show();
                        }
                    });
                }

                @Override
                public void onVetMarkerAdded(Marker marker) {
                    Log.d(TAG, "BaksiHelper: Veteriner marker eklendi: " + marker.getTitle());
                }

                @Override
                public void onSearchRadiusSuggested(double nextRadius, String radiusLabel) {
                    runOnUiThread(() -> {
                        showRadiusSearchDialog(nextRadius, radiusLabel);
                    });
                }
            });
            Log.d(TAG, "BaksiHelper başarıyla başlatıldı");
        } catch (Exception e) {
            Log.e(TAG, "BaksiHelper başlatma hatası: " + e.getMessage(), e);
            runOnUiThread(() -> {
                Toast.makeText(SokakActivity.this,
                        "BaksiHelper başlatılamadı: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            });
        }
    }
    private void loadNearbyBaksiVetsFromFirestore(LatLng userLocation, double radiusInMeters) {
        if (userLocation == null) {
            Log.w(TAG, "Kullanıcı konumu yok, veteriner yüklenemedi");
            return;
        }

        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        com.google.firebase.firestore.GeoPoint geoPoint = new com.google.firebase.firestore.GeoPoint(
                userLocation.latitude, userLocation.longitude
        );

        // Yakınlık sorgusu (GeoFire veya manuel sınır kutusu ile)
        double lat = userLocation.latitude;
        double lng = userLocation.longitude;

        // Basit sınır kutusu (bounding box) ile yaklaşık sorgu
        double distanceKm = radiusInMeters / 1000.0;
        double earthRadius = 6371; // km
        double latDelta = (distanceKm / earthRadius) * (180 / Math.PI);
        double lngDelta = latDelta / Math.cos(Math.toRadians(lat));

        com.google.firebase.firestore.GeoPoint southwest = new com.google.firebase.firestore.GeoPoint(
                lat - latDelta, lng - lngDelta
        );
        com.google.firebase.firestore.GeoPoint northeast = new com.google.firebase.firestore.GeoPoint(
                lat + latDelta, lng + lngDelta
        );
        db.collection("Bakşi")
                .whereEqualTo("hasClinic", true) // sadece klinikler
                .whereGreaterThanOrEqualTo("location.lat", southwest.getLatitude())
                .whereLessThanOrEqualTo("location.lat", northeast.getLatitude())
                .whereGreaterThanOrEqualTo("location.lng", southwest.getLongitude())
                .whereLessThanOrEqualTo("location.lng", northeast.getLongitude())
                .get()
                .addOnSuccessListener(queryDocumentSnapshots -> {
                    List<Marker> addedMarkers = new ArrayList<>();

                    for (com.google.firebase.firestore.DocumentSnapshot doc : queryDocumentSnapshots) {
                        try {
                            Map<String, Object> locationMap = (Map<String, Object>) doc.get("location");
                            if (locationMap == null) continue;

                            Double latDoc = (Double) locationMap.get("lat");
                            Double lngDoc = (Double) locationMap.get("lng");
                            if (latDoc == null || lngDoc == null) continue;

                            String name = doc.getString("username");
                            if (name == null || name.isEmpty()) name = "Veteriner Kliniği";

                            Boolean verified = doc.getBoolean("verified");
                            Long colorLong = doc.getLong("markerColor");
                            int markerColor = (colorLong != null) ? colorLong.intValue() : 0xFF0000FF; // mavi varsayılan

                            LatLng vetLocation = new LatLng(latDoc, lngDoc);

                            // Mesafe kontrolü (bounding box sonrası kesin kontrol)
                            double distance = calculateDistance(userLocation, vetLocation);
                            if (distance > radiusInMeters) continue;

                            // Özel ikon (örneğin mavi haç)
                            BitmapDescriptor icon = getVeterinerIcon(markerColor, verified == Boolean.TRUE);

                            MarkerOptions markerOptions = new MarkerOptions()
                                    .position(vetLocation)
                                    .title(name)
                                    .snippet("Veteriner Kliniği" + (verified == Boolean.TRUE ? " ✓ Doğrulanmış" : ""))
                                    .icon(icon);

                            Marker marker = harita.getGoogleMap().addMarker(markerOptions);
                            if (marker != null) {
                                marker.setTag(doc.getId()); // place_id veya doc id
                                addedMarkers.add(marker);
                            }

                        } catch (Exception e) {
                            Log.e(TAG, "Veteriner marker eklenirken hata: " + doc.getId(), e);
                        }
                    }

                    Log.d(TAG, addedMarkers.size() + " veteriner markerı eklendi (" + radiusInMeters + "m)");
                    runOnUiThread(() -> {
                        if (addedMarkers.isEmpty()) {
                            suggestWiderSearch(radiusInMeters / 1000.0);
                        } else {
                            Toast.makeText(this, addedMarkers.size() + " veteriner bulundu", Toast.LENGTH_SHORT).show();
                        }
                    });

                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Bakşi veterinerleri yüklenemedi", e);
                    runOnUiThread(() -> Toast.makeText(this, "Veterinerler yüklenemedi", Toast.LENGTH_SHORT).show());
                });
    }
    /**
     * Daha geniş arama öneren dialog
     */
    private void suggestWiderSearch(double currentRadius) {
        if (currentRadius == 150) {
            // 150m'de bulunamadı, 1.5km öner
            showRadiusSearchDialog(1500, "1.5km");
        } else if (currentRadius == 1500) {
            // 1.5km'de bulunamadı, 15km öner
            showRadiusSearchDialog(15000, "15km");
        } else {
            // 15km'de de bulunamadı
            Toast.makeText(this, "15km çapında da veteriner bulunamadı", Toast.LENGTH_LONG).show();
        }
    }
    private double calculateDistance(LatLng a, LatLng b) {
        double earthRadius = 6371000; // metre
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLng = Math.toRadians(b.longitude - a.longitude);
        double sindLat = Math.sin(dLat / 2);
        double sindLng = Math.sin(dLng / 2);
        double va1 = Math.pow(sindLat, 2) + Math.pow(sindLng, 2)
                * Math.cos(Math.toRadians(a.latitude)) * Math.cos(Math.toRadians(b.latitude));
        double va2 = 2 * Math.atan2(Math.sqrt(va1), Math.sqrt(1 - va1));
        return earthRadius * va2;
    }

    private BitmapDescriptor getVeterinerIcon(int colorArgb, boolean verified) {
        // Check if the color is primarily blue/black (red and green components are 0)
        if (Color.red(colorArgb) == 0 && Color.green(colorArgb) == 0) {
            // Use default blue marker
            return BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE);
        } else {
            // Create custom bitmap for other colors
            Bitmap bitmap = createVeterinerBitmap(colorArgb, verified);
            return BitmapDescriptorFactory.fromBitmap(bitmap);
        }
    }

    // Daha güzel ikon için (opsiyonel)
    private Bitmap createVeterinerBitmap(int color, boolean verified) {
        // Burada drawable'dan bir ikon alıp renklendirebilirsin
        // Örnek: R.drawable.ic_veteriner
        return BitmapFactory.decodeResource(getResources(), R.drawable.map_marker);
    }
    /**
     * Yarıçap arama dialog'u göster
     */
    private void showRadiusSearchDialog(double radius, String radiusLabel) {
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Arama Genişletilsin mi?")
                    .setMessage(radiusLabel + " çapında arama yapılsın mı?")
                    .setPositiveButton("Evet", (dialog, which) -> {
                        if (lastKnownLocation != null && baksiHelper != null) {
                            Log.d(TAG, radiusLabel + " çapında arama başlatılıyor");
                            baksiHelper.searchVetsInRadius(lastKnownLocation, radius);
                        } else {
                            Toast.makeText(this, "Konum bilgisi mevcut değil", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Hayır", (dialog, which) -> {
                        Toast.makeText(this, "Arama " + radiusLabel + " ile sınırlandı", Toast.LENGTH_SHORT).show();
                    })
                    .setCancelable(true)
                    .setOnCancelListener(dialog -> {
                        Toast.makeText(this, "Arama iptal edildi", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        } catch (Exception e) {
            Log.e(TAG, "Dialog gösterim hatası: " + e.getMessage());
        }
    }

    private void initializeLockModeOverlay() {
        lockModeOverlay = findViewById(R.id.lock_mode_overlay);
        if (lockModeOverlay == null) {
            lockModeOverlay = new View(this);
            lockModeOverlay.setBackgroundColor(Color.TRANSPARENT);
        }

        View touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            touchOverlay.setOnTouchListener((v, event) -> {
                if (isMarkerMode && harita != null) {
                    return harita.handleOverlayTouch(event);
                }
                return false;
            });
        }
    }

    public void createYuvaMarker(LatLng location) {
        CFHelper cf = new CFHelper(this, "iyesi-e8d4f", "us-central1", new CFHelper.Listener() {});
        cf.refreshRole(role -> {
            if ("Tengri".equals(role)) {
                showYuvaNodeDialog(location);
            } else {
                Toast.makeText(this, "Bu işlem için Tengri yetkisi gerekli", Toast.LENGTH_LONG).show();
                Log.e("RoleCheckFailed", "Tengri rolü gerekli");
            }
        });
    }

    private void showYuvaNodeDialog(LatLng location) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_yuva_node, null);
        builder.setView(dialogView);

        TextInputEditText nameInput = dialogView.findViewById(R.id.input_node_name);
        TextInputEditText descInput = dialogView.findViewById(R.id.input_node_description);

        builder.setTitle("Yuva Node Oluştur")
                .setPositiveButton("Kaydet", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    String description = descInput.getText().toString().trim();

                    if (TextUtils.isEmpty(name)) {
                        Toast.makeText(this, "Node adı gerekli", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    saveYuvaNodeToFirestore(location, name, description);
                })
                .setNegativeButton("İptal", (dialog, which) -> {
                    dialog.dismiss();
                    stopMarkerPlacement();
                })
                .setCancelable(false);

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void saveYuvaNodeToFirestore(LatLng location, String name, String description) {
        com.google.firebase.firestore.FirebaseFirestore db = com.google.firebase.firestore.FirebaseFirestore.getInstance();
        Map<String, Object> node = new HashMap<>();
        node.put("type", "Yuva");
        node.put("location", new GeoPoint(location.latitude, location.longitude));
        node.put("name", name);
        node.put("description", description);
        node.put("createdAt", com.google.firebase.firestore.FieldValue.serverTimestamp());

        db.collection("nodes")
                .add(node)
                .addOnSuccessListener(documentReference -> {
                    String nodeId = documentReference.getId();
                    Log.d("SokakActivity", "YuvaNode eklendi, ID: " + nodeId);
                    Toast.makeText(this, "YuvaNode oluşturuldu: " + name, Toast.LENGTH_SHORT).show();

                    if (harita != null && harita.getNodeManager() != null) {
                        com.google.android.gms.maps.model.MarkerOptions options = new com.google.android.gms.maps.model.MarkerOptions()
                                .position(location)
                                .title("Yuva: " + name)
                                .icon(harita.getNodeManager().getCustomIcon("Yuva"));
                        harita.getNodeManager().addMarker(options, "Yuva", nodeId);

                        harita.animateCameraTo(location, 15f);
                    }
                    stopMarkerPlacement();
                })
                .addOnFailureListener(e -> {
                    Log.e("SokakActivity", "YuvaNode ekleme hatası: " + e.getMessage());
                    Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    stopMarkerPlacement();
                });
    }

    @Override
    public void onLockModeChanged(boolean locked) {
        isLockedMode = locked;
        isMarkerMode = locked;

        runOnUiThread(() -> {
            if (locked) {
                if (lockModeOverlay != null) {
                    lockModeOverlay.setVisibility(View.VISIBLE);
                    lockModeOverlay.setClickable(true);
                    lockModeOverlay.setOnClickListener(v -> {
                        Toast.makeText(this, "Marker yerleştirme modu aktif. Haritaya uzun basın.", Toast.LENGTH_SHORT).show();
                    });
                }
                hideFABs();
            } else {
                if (lockModeOverlay != null) {
                    lockModeOverlay.setVisibility(View.GONE);
                    lockModeOverlay.setClickable(false);
                }
                showFABs();
            }
        });
    }

    public void startMarkerPlacement() {
        if (harita != null) {
            harita.startMarkerPlacementMode();
            isMarkerMode = true;
        }
    }

    public void stopMarkerPlacement() {
        if (harita != null) {
            harita.stopMarkerPlacementMode();
            isMarkerMode = false;
        }
    }

    private void hideFABs() {
        runOnUiThread(() -> {
            if (mainFab != null) mainFab.setVisibility(View.GONE);
            if (beslemeFab != null) beslemeFab.setVisibility(View.GONE);
            if (bolgeFab != null) bolgeFab.setVisibility(View.GONE);
            if (nakilFab != null) nakilFab.setVisibility(View.GONE);
            if (soundFab != null) soundFab.setVisibility(View.GONE);
        });
    }

    private void showFABs() {
        runOnUiThread(() -> {
            if (mainFab != null) mainFab.setVisibility(View.VISIBLE);
        });
    }

    private boolean ensureLoggedInOrGoLogin() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return false;
        }
        Log.d(TAG, "Kullanıcı girişi doğrulandı: " + user.getEmail());
        return true;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (miniFabs != null && miniFabs.handleOutsideTouch(event)) {
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private void setupMapWithMarkers() {
        final Handler handler = new Handler();
        final Runnable checkMapReady = new Runnable() {
            @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
            @Override
            public void run() {
                Log.d(TAG, "Harita hazırlık kontrolü...");
                if (harita != null && harita.isReady()) {
                    Log.d(TAG, "Harita hazır, node'lar yükleniyor...");

                    try {
                        harita.fetchNodesNearby(null, 5000, 200);
                        harita.setMyLocationIconEnabled(true);

                        // LocationManager null kontrolü - EKLENDİ
                        if (locationManager == null) {
                            locationManager = new LocationManager(SokakActivity.this);
                            Log.d(TAG, "setupMapWithMarkers: LocationManager başlatıldı");
                        }

                        // BaksiHelper'ı harita hazır olduğunda başlat
                        initializeBaksiHelper();

                        Log.d(TAG, "Veteriner yükleme başlatılıyor...");
                        loadNearbyVets();
                    } catch (Exception e) {
                        Log.e(TAG, "Harita hazırlık hatası: " + e.getMessage());
                        // 3sn sonra tekrar dene
                        handler.postDelayed(this, 3000);
                    }
                } else {
                    Log.d(TAG, "Harita henüz hazır değil, 1sn bekleniyor...");
                    handler.postDelayed(this, 1000);
                }
            }
        };
        handler.postDelayed(checkMapReady, 1000);
    }

    private void loadNearbyVets() {
        // locationManager null kontrolü
        if (locationManager == null) {
            locationManager = new LocationManager(this);
            Log.d(TAG, "LocationManager başlatıldı");
        }

        locationManager.setListener(new LocationManager.LocationManagerListener() {
            @Override
            public void onLocationReceived(LatLng location) {
                lastKnownLocation = location;
                Log.d(TAG, "Konum alındı: " + location);

                // Alternatif 1: NodeManager üzerinden veteriner yükle
                if (harita != null && harita.getNodeManager() != null) {
                    //harita.getNodeManager().loadVetNodes();
                }

                // Alternatif 2: Basit veteriner yükleme - DÜZELTME: Sonsuz döngüyü engelle
                // Bu satırı silin veya yorum yapın:
                // loadNearbyVets(); // BU SONSUZ DÖNGÜYE NEDEN OLUYOR!

                // Bunun yerine doğrudan veteriner yükleme metodunu çağırın:
                startProgressiveBaksiSearch(location);
            }

            @Override
            public void onLocationError(String error) {
                Log.e(TAG, "Konum alınamadı: " + error);
                // fallback: node manager'dan veteriner yükle
                if (harita != null && harita.getNodeManager() != null) {
                    harita.getNodeManager().loadVetNodes();
                }
            }
        });
        locationManager.getCurrentLocation();
    }
    private void startProgressiveBaksiSearch(LatLng location) {
        //loadNearbyBaksiVetsFromFirestore(location, 1500); // 1.5km başla
        // suggestWiderSearch() içinde genişlet:
        // loadNearbyBaksiVets(location, 5000);
        // loadNearbyBaksiVets(location, 15000);
    }
/*
    @SuppressLint("MissingPermission")
    private void loadNearbyVets() {
        Log.d(TAG, "=== VETERİNER YÜKLEME BAŞLANGIÇ ===");

        if (harita == null || !harita.isReady()) {
            Log.w(TAG, "Harita hazır değil, veteriner yükleme ertelendi");
            new Handler().postDelayed(this::loadNearbyVets, 2000);
            return;
        }

        // İzin kontrolü
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            Log.w(TAG, "Konum izinleri gerekli");
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    LOCATION_PERMISSION_REQUEST_CODE
            );
            return;
        }

        if (baksiHelper == null) {
            Log.w(TAG, "BaksiHelper null, yeniden başlatılıyor...");
            initializeBaksiHelper();
            if (baksiHelper == null) {
                Log.e(TAG, "BaksiHelper hala null, veteriner yükleme iptal");
                return;
            }
        }

        if (locationManager == null) {
            locationManager = new LocationManager(this);
            Log.d(TAG, "LocationManager oluşturuldu");
        }

        locationManager.setListener(new LocationManager.LocationManagerListener() {
            @Override
            public void onLocationReceived(LatLng location) {
                Log.d(TAG, "Konum alındı: " + location);
                lastKnownLocation = location;

                if (baksiHelper != null) {
                    Log.d(TAG, "Kademeli veteriner arama başlatılıyor (150m)...");
                    baksiHelper.startProgressiveVetSearch(location);
                } else {
                    Log.e(TAG, "BaksiHelper null! Veteriner yüklenemedi.");
                }
            }

            @Override
            public void onLocationError(String error) {
                Log.e(TAG, "Konum alınamadı: " + error);
                runOnUiThread(() -> {
                    Toast.makeText(SokakActivity.this,
                            "Konum alınamadı: " + error,
                            Toast.LENGTH_SHORT).show();

                    // Fallback: son bilinen konumu kullan veya varsayılan konum
                    if (lastKnownLocation != null) {
                        Log.d(TAG, "Son bilinen konum kullanılıyor: " + lastKnownLocation);
                        baksiHelper.startProgressiveVetSearch(lastKnownLocation);
                    }
                });
            }
        });

        Log.d(TAG, "Konum alınıyor...");
        locationManager.getCurrentLocation();
    }
*/
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Log.d(TAG, "Konum izinleri alındı, veteriner yükleme başlatılıyor");
                loadNearbyVets();
            } else {
                Log.w(TAG, "Konum izinleri reddedildi");
                Toast.makeText(this, "Konum izinleri olmadan veterinerler gösterilemez", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume - Harita durumu: " + (harita != null ? "Mevcut" : "Null"));

        try {
            if (harita != null) {
                Log.d(TAG, "Harita hazır: " + harita.isReady());
                Log.d(TAG, "Konum izni: " + (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED));

                // LocationManager kontrolü - EKLENDİ
                if (locationManager == null) {
                    locationManager = new LocationManager(this);
                    Log.d(TAG, "onResume: LocationManager başlatıldı");
                }

                // Haritayı yeniden etkinleştir
                if (harita.isReady()) {
                    // Harita onResume işlemleri
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "onResume hatası: " + e.getMessage());
        }        Log.d(TAG, "onResume - Harita durumu: " + (harita != null ? "Mevcut" : "Null"));

    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            if (harita != null) {
                // Harita onPause işlemleri
            }
        } catch (Exception e) {
            Log.e(TAG, "onPause hatası: " + e.getMessage());
        }
    }

    private void initializeSpinners() {
        LinearLayout[] rows = {
                findViewById(R.id.row_spinner_1),
                findViewById(R.id.row_spinner_2),
                findViewById(R.id.row_spinner_3),
                findViewById(R.id.row_spinner_4),
                findViewById(R.id.row_spinner_5)
        };

        Spinner[] spinners = {
                findViewById(R.id.spinner_level_1),
                findViewById(R.id.spinner_level_2),
                findViewById(R.id.spinner_level_3),
                findViewById(R.id.spinner_level_4),
                findViewById(R.id.spinner_level_5)
        };

        String[] speciesOptions = {"Kedi", "Köpek", "Kuş", "Vahşi", "İstenmeyen"};
        ArrayAdapter<String> adapterSpecies = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, speciesOptions
        );
        spinners[1].setAdapter(adapterSpecies);

        String[] categoryOptions = {"Beslenme", "Yuva", "Su", "AvYemleme", "Hepsi"};
        ArrayAdapter<String> adapterCategory = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, categoryOptions
        );
        spinners[2].setAdapter(adapterCategory);

        spinners[3].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));
        spinners[4].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));

        for (int i = 1; i < rows.length; i++) rows[i].setVisibility(View.GONE);

        AdapterView.OnItemSelectedListener[] listeners = new AdapterView.OnItemSelectedListener[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            listeners[i] = new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    for (int j = idx + 1; j < rows.length; j++) {
                        rows[j].setVisibility(View.GONE);
                    }
                    if (idx + 1 < rows.length) {
                        rows[idx + 1].setVisibility(View.VISIBLE);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    for (int j = idx + 1; j < rows.length; j++) {
                        rows[j].setVisibility(View.GONE);
                    }
                }
            };
            spinners[i].setOnItemSelectedListener(listeners[i]);
        }
        selectSpinnerValue(spinners[3], "ADM'");
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> adapter = (ArrayAdapter<String>) spinner.getAdapter();
        int position = adapter.getPosition(value);
        if (position >= 0) {
            spinner.setSelection(position);
        }
    }

    private void initializeFABs() {
        fabOpenAnim = AnimationUtils.loadAnimation(this, R.anim.fab_open);
        fabCloseAnim = AnimationUtils.loadAnimation(this, R.anim.fab_close);
        rotateForwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_forward);
        rotateBackwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_backward);

        mainFab = findViewById(R.id.main_fab);
        beslemeFab = findViewById(R.id.ülgen_fab);
        bolgeFab = findViewById(R.id.coban_fab);
        nakilFab = findViewById(R.id.acil_fab);
        soundFab = findViewById(R.id.sound_fab);

        beslemeFab.setVisibility(View.GONE);
        bolgeFab.setVisibility(View.GONE);
        nakilFab.setVisibility(View.GONE);
        soundFab.setVisibility(View.GONE);

        int[] miniFabIds = new int[]{R.id.ülgen_fab, R.id.coban_fab, R.id.acil_fab};
        miniFabs = new MiniFabs(this, mainFab, soundFab, miniFabIds);
        miniFabs.setAnimations(fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim);

        mainFab.setVisibility(View.VISIBLE);

        Actions actions = new Actions(miniFabs, this, this);
        soundFab.setOnClickListener(v -> {
            if (miniFabs.getSelectedFab() != null) {
                actions.performSelectedAction(miniFabs.getSelectedFab(), this);
                miniFabs.toggle();
            } else {
                Toast.makeText(this, "Önce bir miniFAB seçin", Toast.LENGTH_SHORT).show();
            }
        });

        miniFabs.applyDefaultColors();
        miniFabs.setupDraggableOnly(this, mainFab);

        setupFABClickListeners();
    }

    private void setupFABClickListeners() {
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                miniFabs.selectFab((FloatingActionButton) v);

                int id = v.getId();
                Harita.MapMode mode = Harita.MapMode.DEFAULT;

                if (id == R.id.ülgen_fab) {
                    mode = Harita.MapMode.FEEDING;
                } else if (id == R.id.coban_fab) {
                    mode = Harita.MapMode.NEST;
                } else if (id == R.id.acil_fab) {
                    mode = Harita.MapMode.TASK;
                }

                if (harita != null) {
                    harita.setMode(mode);
                    startNodeCreationProcess();
                }
            });
        }
    }

    public void startNodeCreationProcess() {
        CFHelper cf = new CFHelper(this, "iyesi-e8d4f","us-central1", new CFHelper.Listener(){});
        cf.refreshRole(role -> {
            Log.i("CustomClaims", "Role: " + role);
            if ("Tengri".equals(role)) {
                if (harita != null) {
                    harita.startMarkerPlacementMode();
                    new Handler().postDelayed(() -> {
                        harita.showNodeTypeSelectionDialog();
                    }, 500);
                }
            } else {
                Toast.makeText(this, "Bu işlem için yetkiniz yok", Toast.LENGTH_LONG).show();
                Log.e("RoleCheckFailed", "Tengri rolü gerekli");
            }
        });
    }

    @Override
    public void onRequestMarkerReposition(@androidx.annotation.NonNull String markerId) {
        if (harita != null) {
            harita.startRepositionMode(markerId);
            isMarkerMode = true;
        }
    }

    @Override
    public void onNodeCreated(String nodeId, String nodeType) {
        runOnUiThread(() -> {
            showNodeCreationSuccessDialog(nodeId, nodeType);
            stopMarkerPlacement();
        });
    }

    @Override
    public void onNodeCreationFailed(String error) {
        runOnUiThread(() -> {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        });
    }

    private void showNodeCreationSuccessDialog(String nodeId, String nodeType) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("Node Oluşturuldu ✓")
                .setMessage("Node ID: " + nodeId + "\nTür: " + nodeType)
                .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
                .setNeutralButton("Foto", (dialog, which) -> {
                    Toast.makeText(this, "Foto özelliği yakında eklenecek", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "SokakActivity sonlandırılıyor");
        if (harita != null) {
            harita.cleanup();
        }
    }
}