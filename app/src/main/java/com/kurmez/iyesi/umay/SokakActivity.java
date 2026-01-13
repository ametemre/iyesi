package com.kurmez.iyesi.umay;
import static com.kurmez.iyesi.umay.sokak.Harita.hasLocationPermission;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Geocoder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Nodes.Node;
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.utilities.helper.BaksiHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.AdminPathKey;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.umay.sokak.Managers.NodeManager;
import com.kurmez.iyesi.umay.sokak.Cache.NodeCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.location.Address;
import android.location.Geocoder;
// imports
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
/**
 * SokakActivity (Draft / Roadmap embedded)
 *
 * ===========================
 *  ROLE → DEFAULT LAYER POLİTİKASI
 * ===========================
 * - Tengri: Sorumluluğundaki yerler ∩ "son birkaç gün" eklenenler
 * - İye: Sadece sorumluluğundaki yerler
 * - Körmöz: Yakındaki veterinerler + acil yardım istenen alanlar
 * - Ülgen: 1) bakımı geciken alanlar, yoksa 2) acil yardım alanları, yoksa 3) 5km yakınındaki tüm alanlar
 * - Anonim: Sadece yakındaki veterinerler (Layer UI kapalı)
 *
 * ===========================
 *  TODO - DATA ETİKETLEME / SCHEMA NOTLARI
 * ===========================
 * 1) Node dokümanı (örn. "nodes"):
 *  - id (docId)
 *  - type: "Yuva"|"Besleme"|"Nakil"|"Bolge"|...
 *  - adminPath: String (standart path)
 *  - location.lat/lng/geohash
 *  - createdAt, createdByUid
 *  - responsibleUid OR responsibleUids[]
 *  - emergency.isActive, emergency.requestedAt
 *  - care.lastCareAt, care.careIntervalHours
 *
 * 2) Baksi (Veteriner) dokümanı (Baksi.js modeline göre):
 *  - /Baksi/{countryKey}/Cities/{cityKey}/Baksi/{vetUid}
 *  - adminPath: Baksi.js buildAdminPath ile birebir
 *  - isActive
 *  - location.lat/lng/geohash
 *  - phone, website, place_id, updatedAt/createdAt
 *
 * 3) "adminPath yoksa yaz" akışı:
 *  - App: reverseGeocode -> (country, city, district) + normalize -> adminPath
 *  - Firestore: BaksiUpdates/adminPathDocId(adminPath) var mı?
 *  - Yoksa: CloudFunction (Baksi.js) updateBaksi tetikle (rate-limit + lock)
 *  - Sonra: Firestore’dan Baksi yükle
 */

public class SokakActivity extends FragmentActivity
        implements NodeDetailsBottomSheet.Host,
        Harita.LockModeListener,
        Harita.NodeCreationListener {
    private CFHelper cfHelper;
    private static final String TAG = "SokakActivity";
    private static final int REQ_LOCATION = 1001;
    private static final String CF_PROJECT_ID = "iyesi-aef03";
    private static final String CF_REGION = "us-central1";
    @Nullable
    private String lastKnownAdminPath = null; // TODO: reverseGeocode ile set edilecek
    @Nullable
    private String lastKnownCountry = null; // Geocoder'dan alınan country
    @Nullable
    private String lastKnownCity = null; // Geocoder'dan alınan city
    private final ExecutorService geoExec = Executors.newSingleThreadExecutor();
    private volatile boolean resolvingAdminPath = false;
    private FusedLocationProviderClient fusedClient;

    // QR deep-link extras (QrRouteResolver)
    private static final String EXTRA_QR_KIND    = "qr_kind";     // "node"
    private static final String EXTRA_QR_COUNTRY = "qr_country";  // "TR"
    private static final String EXTRA_QR_CITY    = "qr_city";     // "ISTANBUL"
    private static final String EXTRA_QR_NODE_ID = "qr_node_id";  // Firestore doc id

    @Nullable private String pendingQrNodeId;
    @Nullable private String pendingQrCountry;
    @Nullable private String pendingQrCity;

    private interface AdminPathCb { void onResult(@Nullable String adminPath); }
    // resolveAdminPathFromLocation aynı anda birden fazla yerden çağrılabiliyor (node + vets).
    // resolvingAdminPath=true iken callback'i drop etmek, bazı akışların "hiç çalışmıyor" gibi görünmesine sebep oluyor.
    // Bu yüzden pending callback'leri kuyruklayıp tek resolve sonucu ile hepsini cevaplıyoruz.
    private final List<AdminPathCb> pendingAdminPathCallbacks = new ArrayList<>();
    // ===== Roles =====
    private enum Role { TENGRI, ULGEN, KORMOZ, IYE, AGAC, ANON }

    // ===== Layers =====
    private enum Layer {
        VETS_NEARBY,
        EMERGENCY_AREAS,
        RESPONSIBLE_AREAS,
        RESPONSIBLE_NEWLY_ADDED,
        OVERDUE_CARE_AREAS,
        ALL_WITHIN_5KM,
        SHELTERS,
        FEEDING_AREAS,
        NESTING_AREAS,
        TASKS
    }

    // ===== State =====
    private Role currentRole = Role.ANON;
    private LatLng lastKnownLocation = null;

    // ===== UI =====
    private ProgressBar progressBar;
    private View lockOverlay;
    private View mapOverlay;
    private LinearLayout spinnerContainer;

    private TextView layerLabel1, layerLabel2, layerLabel3, layerLabel4, layerLabel5;
    private TextView layerLabel6, layerLabel7, layerLabel8, layerLabel9;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton clear6, clear7, clear8, clear9;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private ImageButton toggle6, toggle7, toggle8, toggle9;
    
    // Layer visibility state
    private boolean[] layerVisible = new boolean[9];

    // ===== Map / Helpers =====
    private Harita harita;
    private BaksiHelper baksiHelper;
    private NodeCache nodeCache;

    // ===== MiniFabs =====
    private MiniFabs miniFabs;
    private View ulgenFab;
    private View cobanFab;
    private View acilFab;
    private FloatingActionButton soundFab;
    private FloatingActionButton mainFab;
    private boolean isLockedMode = false;
    private View row1,row2,row3,row4,row5,row6,row7,row8,row9;


    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);

        // Cache instance'ını oluştur (Context ile persistent storage desteği)
        nodeCache = new NodeCache(this);
        Log.d(TAG, "NodeCache initialized with persistent storage");

        bindViews();
        initSpinnerUiDraftOnly();
        initMapOverlayRouting();
        initMiniFabs();

        // CFHelper oluştur (Cloud Functions çağrıları için)
        if (cfHelper == null) {
            cfHelper = new CFHelper(this, CF_PROJECT_ID, CF_REGION, null);
        }

        // Harita wrapper
        harita = new Harita(this);
        harita.setLockModeListener(this);
        harita.setNodeCreationListener(this);
        harita.setMarkersUpdatedListener(() -> {
            // Harita.onCameraIdle / fetchNodesNearby sonrası gelen marker'lar default visible gelir.
            // Toggle durumlarına göre hepsini tekrar uygula ki "toggle kapalıyken görünür" problemi olmasın.
            updateMarkerVisibilityForLayers();
        });
        harita.setCFHelper(cfHelper); // CFHelper'ı Harita'ya set et
        harita.setNodeCache(nodeCache); // NodeCache'i Harita'ya set et (marker detayları için)
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        // Not: AdminPath/Country/City çözümlemesini konum izni verildikten sonra (aşağıdaki akışta)
        // yapmak daha doğru; aksi halde ilk açılışta izin yokken null konum yüzünden gereksiz toast/log oluşuyor.

        // Auth/Role zinciri (anon izinli)
        ensureUserOrAnonymousThenResolveRole(role -> {
            Log.i("Role:",role.toString());
            currentRole = role;

            // Harita tarafında task marker erişim politikasını role göre set et
            // - "Acil" task: sadece TENGRI/ULGEN/KORMOZ (AGAC dahil ediyoruz)
            // - "Görev" task: TENGRI/ULGEN/KORMOZ + IYE (AGAC dahil)
            if (harita != null) {
                boolean canSeeUrgentTasks =
                        (currentRole == Role.TENGRI || currentRole == Role.ULGEN || currentRole == Role.KORMOZ);
                boolean canSeeTasks =
                        (currentRole == Role.TENGRI || currentRole == Role.ULGEN || currentRole == Role.KORMOZ || currentRole == Role.IYE);
                harita.setTaskAccessPolicy(canSeeTasks, canSeeUrgentTasks);

                // Ağaç ve Anon: non-vet node verisine erişemez → fetch hard-block + auto-fetch kapalı
                boolean canFetchNodes = !(currentRole == Role.AGAC || currentRole == Role.ANON);
                boolean autoFetchNodes = canFetchNodes; // aynı politika
                harita.setNodesAccessPolicy(canFetchNodes, autoFetchNodes);
            }
            
            // Role'e göre UI erişim kontrolü
            applyLayerUiAccessPolicy(currentRole);
            
            // Role'e göre row görünürlüğünü ayarla
            setRowVisibilityForRole(currentRole);

            // Map hazır + konum al - role'e göre default layer'ları yükle
            waitMapReadyThen(() -> ensureLocationPermissionThen(() -> {
                fetchLastKnownLocation(loc -> {
                    lastKnownLocation = loc;
                    
                    // Role'e göre layer görünürlüğünü ayarla (marker'lar henüz yok, sadece UI güncelleniyor)
                    updateLayerVisibilityForRole(currentRole);
                    
                    // Role'e göre default layer'ları yükle
                    // Veriler yüklendikten sonra marker görünürlüğü güncellenecek
                    if (loc != null) {
                        applyRoleDefaultLayers(currentRole, loc);
                        
                        // Veriler yüklendikten sonra marker görünürlüğünü güncelle
                        // loadNearbyVets ve loadNearbyNodes içinde zaten güncelleniyor,
                        // ama ekstra güvence için burada da güncelliyoruz
                        Handler handler = new Handler(Looper.getMainLooper());
                        handler.postDelayed(() -> {
                            updateMarkerVisibilityForLayers();
                            Log.d(TAG, "onCreate: Marker görünürlüğü son kontrol edildi");
                        }, 2000); // 2 saniye sonra son kontrol
                    }
                });
            }));
        });

        // QR ile geldiysek (node) country/city'yi erken set et ki cache doğru dalı load edebilsin.
        consumeQrIntentIfAny();
    }

    /**
     * QR route: iyesi://node/{COUNTRY}/{CITY}/{NODE_ID}
     * Target: Sokak + Harita + NodeDetailsBottomSheet
     */
    private void consumeQrIntentIfAny() {
        try {
            Intent it = getIntent();
            if (it == null) return;
            String kind = it.getStringExtra(EXTRA_QR_KIND);
            if (!"node".equalsIgnoreCase(kind)) return;

            pendingQrCountry = it.getStringExtra(EXTRA_QR_COUNTRY);
            pendingQrCity = it.getStringExtra(EXTRA_QR_CITY);
            pendingQrNodeId = it.getStringExtra(EXTRA_QR_NODE_ID);

            if (pendingQrNodeId == null || pendingQrNodeId.trim().isEmpty()) return;

            // country/city verilmişse Harita + Cache'e set et
            if (pendingQrCountry != null && pendingQrCity != null) {
                lastKnownCountry = pendingQrCountry;
                lastKnownCity = pendingQrCity;
                if (harita != null) {
                    harita.setCountryAndCity(pendingQrCountry, pendingQrCity);
                }
                if (nodeCache != null) {
                    nodeCache.loadFromDatabase(pendingQrCountry, pendingQrCity);
                }
            }

            // Map hazır olduğunda node detayını açmayı dene.
            waitMapReadyThen(() -> {
                if (harita != null && pendingQrNodeId != null) {
                    harita.openNodeDetailsById(pendingQrCountry, pendingQrCity, pendingQrNodeId);
                    // Not: CF /markerDetails henüz yoksa Harita TODO loglayıp çıkacak.
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "consumeQrIntentIfAny failed", t);
        }
    }
    private static String normalizeCode(String value) {
        if (value == null) return "";
        String s = value.trim().toLowerCase(Locale.ROOT);

        // TR karakter normalize (Baksi.js ile aynı mantık) :contentReference[oaicite:4]{index=4}
        s = s.replace("ı","i").replace("ğ","g").replace("ü","u")
                .replace("ş","s").replace("ö","o").replace("ç","c");

        // diacritics temizle (opsiyonel ama iyi)
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // boşluk/özel -> underscore
        s = s.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");

        if (s.length() > 60) s = s.substring(0, 60);
        return s;
    }

    /**
     * Yeni sözleşme: Latin + UPPERCASE normalizeKey (DBRoadmap.md)
     * - TR karakterleri Latin'e indirger
     * - Diacritics temizler
     * - [A-Z0-9_] dışını '_' yapar
     * - max 60
     */
    private static String normalizeKeyUpper(String value) {
        // Back-compat wrapper (eski çağrıları kırmamak için)
        return AdminPathKey.normalizeKey(value);
    }

    /**
     * UmayAna.js normKey fonksiyonu gibi normalize eder
     * - Boşlukları '_' yapar
     * - Uppercase yapar
     * - Firestore docId safety için '/' kontrolü yapar
     */
    private static String normalizeCityForUmayAna(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        String s = value.trim();
        
        // Roadmap normalizeKey (Latin + UPPERCASE) ile uyumlu
        String out = AdminPathKey.normalizeKey(s);
        return out.isEmpty() ? null : out;
    }
    private static String buildAdminPath(String countryRaw, String cityRaw) {
        String countryKey = normalizeKeyUpper(countryRaw);
        String cityKey = normalizeKeyUpper(cityRaw);
        if (countryKey.isEmpty() || cityKey.isEmpty()) return null;

        // Yeni sözleşme (AdminPath min): COUNTRY/CITY
        // TODO(CF): /findNearbyBaksi endpoint'i bu formatı parse edecek şekilde rollout edilmeli (dual-read süreci).
        return countryKey + "/" + cityKey;
    }

    private static String firstNonEmpty(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null) {
                String t = x.trim();
                if (!t.isEmpty()) return t;
            }
        }
        return null;
    }
    private void resolveAdminPathFromLocation(@NonNull LatLng loc, @NonNull AdminPathCb cb) {
        if (loc == null) {
            // Null location guard - fetchLastKnownLocation can return null
            cb.onResult(null);
            return;
        }

        synchronized (pendingAdminPathCallbacks) {
            pendingAdminPathCallbacks.add(cb);
            if (resolvingAdminPath) return;
        resolvingAdminPath = true;
        }

        geoExec.execute(() -> {
            String out = null;
            try {
                // Bazı cihazlarda Geocoder.isPresent() false dönebilir ama yine de çalışabiliyor;
                // bu yüzden gate'lemiyoruz, sadece logluyoruz.
                Log.d(TAG, "resolveAdminPathFromLocation: Geocoder.isPresent=" + Geocoder.isPresent());

                    Geocoder geocoder = new Geocoder(this, new Locale("tr", "TR"));
                    Log.d(TAG, "resolveAdminPathFromLocation: Calling geocoder for lat=" + loc.latitude + ", lng=" + loc.longitude);
                    List<Address> list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1);
                    if (list != null && !list.isEmpty()) {
                        Address a = list.get(0);
                        Log.d(TAG, "resolveAdminPathFromLocation: Got address - countryCode=" + a.getCountryCode() + 
                                ", countryName=" + a.getCountryName() + 
                                ", adminArea=" + a.getAdminArea() + 
                                ", subAdminArea=" + a.getSubAdminArea() + 
                                ", locality=" + a.getLocality());

                        // country: mümkünse COUNTRY CODE
                        String countryRaw = firstNonEmpty(a.getCountryCode(), a.getCountryName());
                        
                        // Kıbrıs kontrolü: Lat/lng'den Kıbrıs'ta olup olmadığını kontrol et
                        // Kıbrıs koordinatları: ~35.0-35.7 lat, ~32.0-34.6 lng
                        boolean isCyprus = (loc.latitude >= 34.5 && loc.latitude <= 35.8 && 
                                           loc.longitude >= 32.0 && loc.longitude <= 34.8);
                        
                        String country;
                        if (isCyprus) {
                            country = "CY"; // Kıbrıs için CY kullan
                            Log.d(TAG, "resolveAdminPathFromLocation: Kıbrıs tespit edildi, country='CY' kullanılıyor");
                        } else if (countryRaw != null && !countryRaw.trim().isEmpty()) {
                            country = countryRaw.trim().toUpperCase();
                        } else {
                            country = "TR"; // Varsayılan country code
                            Log.d(TAG, "resolveAdminPathFromLocation: Country bilgisi yok, varsayılan 'TR' kullanılıyor");
                        }

                        // city: Türkiye'de çoğu zaman AdminArea = il (Adana, İstanbul)
                        // Eğer adminArea yoksa locality kullan (örn: Girne)
                        String cityRaw = firstNonEmpty(a.getAdminArea(), a.getSubAdminArea(), a.getLocality());
                        
                        // UmayAna.js normKey gibi normalize et: boşlukları '_' yap, uppercase
                        String city = normalizeCityForUmayAna(cityRaw);

                        Log.d(TAG, "resolveAdminPathFromLocation: Extracted country=" + country + " (raw=" + countryRaw + 
                                "), city=" + city + " (raw=" + cityRaw + ")");
                        
                        // Country ve city'yi sakla (markersNearby için gerekli)
                        final String finalCountry = country;
                        final String finalCity = city;
                        runOnUiThread(() -> {
                            lastKnownCountry = finalCountry;
                            lastKnownCity = finalCity;
                            // Harita'ya da set et (kamera hareket listener'ı için)
                            if (harita != null) {
                                harita.setCountryAndCity(finalCountry, finalCity);
                            }
                            // Cache'i yükle (country ve city bilgisi alındığında)
                            if (nodeCache != null) {
                                Log.d(TAG, "resolveAdminPathFromLocation: Cache yükleniyor - country=" + finalCountry + " city=" + finalCity);
                                nodeCache.loadFromDatabase(finalCountry, finalCity);
                            }
                        });
                        
                        out = buildAdminPath(country, city);
                        Log.d(TAG, "resolveAdminPathFromLocation: Built adminPath=" + out);
                    } else {
                        Log.w(TAG, "resolveAdminPathFromLocation: Address list is null or empty");
                }
            } catch (IOException e) {
                Log.e(TAG, "resolveAdminPathFromLocation: IOException", e);
                out = null;
            } catch (Exception e) {
                Log.e(TAG, "resolveAdminPathFromLocation: Unexpected error", e);
                out = null;
            }

            final String result = out;
            runOnUiThread(() -> {
                List<AdminPathCb> callbacks;
                synchronized (pendingAdminPathCallbacks) {
                resolvingAdminPath = false;
                    callbacks = new ArrayList<>(pendingAdminPathCallbacks);
                    pendingAdminPathCallbacks.clear();
                }
                for (AdminPathCb w : callbacks) {
                    try {
                        w.onResult(result);
                    } catch (Throwable t) {
                        Log.w(TAG, "resolveAdminPathFromLocation: callback error", t);
                    }
                }
            });
        });
    }

    // =========================
    // UI Binding
    // =========================

    private void bindViews() {
        row1 = findViewById(R.id.row_spinner_1);
        row2 = findViewById(R.id.row_spinner_2);
        row3 = findViewById(R.id.row_spinner_3);
        row4 = findViewById(R.id.row_spinner_4);
        row5 = findViewById(R.id.row_spinner_5);
        row6 = findViewById(R.id.row_spinner_6);
        row7 = findViewById(R.id.row_spinner_7);
        row8 = findViewById(R.id.row_spinner_8);
        row9 = findViewById(R.id.row_spinner_9);

        progressBar = findViewById(R.id.progress_bar);
        lockOverlay = findViewById(R.id.lock_mode_overlay);
        mapOverlay = findViewById(R.id.map_overlay);
        spinnerContainer = findViewById(R.id.spinner_container);

        layerLabel1 = findViewById(R.id.layer_label_1);
        layerLabel2 = findViewById(R.id.layer_label_2);
        layerLabel3 = findViewById(R.id.layer_label_3);
        layerLabel4 = findViewById(R.id.layer_label_4);
        layerLabel5 = findViewById(R.id.layer_label_5);
        layerLabel6 = findViewById(R.id.layer_label_6);
        layerLabel7 = findViewById(R.id.layer_label_7);
        layerLabel8 = findViewById(R.id.layer_label_8);
        layerLabel9 = findViewById(R.id.layer_label_9);

        clear1 = findViewById(R.id.btn_clear_1);
        clear2 = findViewById(R.id.btn_clear_2);
        clear3 = findViewById(R.id.btn_clear_3);
        clear4 = findViewById(R.id.btn_clear_4);
        clear5 = findViewById(R.id.btn_clear_5);
        clear6 = findViewById(R.id.btn_clear_6);
        clear7 = findViewById(R.id.btn_clear_7);
        clear8 = findViewById(R.id.btn_clear_8);
        clear9 = findViewById(R.id.btn_clear_9);

        toggle1 = findViewById(R.id.btn_toggle_1);
        toggle2 = findViewById(R.id.btn_toggle_2);
        toggle3 = findViewById(R.id.btn_toggle_3);
        toggle4 = findViewById(R.id.btn_toggle_4);
        toggle5 = findViewById(R.id.btn_toggle_5);
        toggle6 = findViewById(R.id.btn_toggle_6);
        toggle7 = findViewById(R.id.btn_toggle_7);
        toggle8 = findViewById(R.id.btn_toggle_8);
        toggle9 = findViewById(R.id.btn_toggle_9);

        // Initialize layer visibility (all hidden by default - will be set based on role)
        for (int i = 0; i < layerVisible.length; i++) {
            layerVisible[i] = false;
        }

        mainFab = findViewById(R.id.main_fab);
        ulgenFab = findViewById(R.id.ülgen_fab);
        cobanFab = findViewById(R.id.coban_fab);
        acilFab = findViewById(R.id.acil_fab);
        soundFab = findViewById(R.id.sound_fab);

        // başlangıç
        setProgressVisible(false);
        lockOverlay.setVisibility(View.GONE);
    }

    /**
     * Layer UI'ı başlat - toggle ve clear butonlarını bağla
     */
    private void initSpinnerUiDraftOnly() {
        // Layer 1: Veterinerler
        toggle1.setOnClickListener(v -> toggleLayer(0, Layer.VETS_NEARBY));
        clear1.setOnClickListener(v -> clearLayer(0, Layer.VETS_NEARBY));
        
        // Layer 2: Acil Yardım
        toggle2.setOnClickListener(v -> toggleLayer(1, Layer.EMERGENCY_AREAS));
        clear2.setOnClickListener(v -> clearLayer(1, Layer.EMERGENCY_AREAS));
        
        // Layer 3: Sorumlu Alanlar
        toggle3.setOnClickListener(v -> toggleLayer(2, Layer.RESPONSIBLE_AREAS));
        clear3.setOnClickListener(v -> clearLayer(2, Layer.RESPONSIBLE_AREAS));
        
        // Layer 4: Yeni Eklenenler
        toggle4.setOnClickListener(v -> toggleLayer(3, Layer.RESPONSIBLE_NEWLY_ADDED));
        clear4.setOnClickListener(v -> clearLayer(3, Layer.RESPONSIBLE_NEWLY_ADDED));
        
        // Layer 5: Bakımı Gecikenler
        toggle5.setOnClickListener(v -> toggleLayer(4, Layer.OVERDUE_CARE_AREAS));
        clear5.setOnClickListener(v -> clearLayer(4, Layer.OVERDUE_CARE_AREAS));
        
        // Layer 6: Barınaklar
        toggle6.setOnClickListener(v -> toggleLayer(5, Layer.SHELTERS));
        clear6.setOnClickListener(v -> clearLayer(5, Layer.SHELTERS));
        
        // Layer 7: Besleme Alanları
        toggle7.setOnClickListener(v -> toggleLayer(6, Layer.FEEDING_AREAS));
        clear7.setOnClickListener(v -> clearLayer(6, Layer.FEEDING_AREAS));
        
        // Layer 8: Yuvalanma Alanları
        toggle8.setOnClickListener(v -> toggleLayer(7, Layer.NESTING_AREAS));
        clear8.setOnClickListener(v -> clearLayer(7, Layer.NESTING_AREAS));

        // Layer 9: Görevler
        if (toggle9 != null) toggle9.setOnClickListener(v -> toggleLayer(8, Layer.TASKS));
        if (clear9 != null) clear9.setOnClickListener(v -> clearLayer(8, Layer.TASKS));
    }

    private void toggleLayer(int index, Layer layer) {
        layerVisible[index] = !layerVisible[index];
        updateToggleIcon(index);
        
        boolean visible = layerVisible[index];
        
        // Layer açılıyorsa ve veriler yoksa, verileri yükle
        // Not: lastKnownLocation null olsa bile loadDataForLayer artık konumu alıp retry ediyor.
        if (visible) {
            loadDataForLayer(layer);
        }
        
        // Layer'a göre marker'ları göster/gizle (layer mapping kullanarak)
        String layerString = getLayerString(layer);
        if (layerString != null && nodeCache != null) {
            List<Marker> markers = nodeCache.getMarkersByLayer(layerString);
            
            for (Marker marker : markers) {
                if (marker != null) {
                    marker.setVisible(visible);
                }
            }
            
            Log.d(TAG, "Layer toggled: " + layer + " (" + layerString + ") -> " + 
                (visible ? "visible" : "hidden") + " (" + markers.size() + " markers)");
        } else {
            // Fallback: Type'a göre (backward compatibility)
            String type = getTypeForLayer(layer);
            if (type != null && nodeCache != null) {
                List<Marker> markers = nodeCache.getMarkersByType(type);
                
                for (Marker marker : markers) {
                    if (marker != null) {
                        marker.setVisible(visible);
                    }
                }
                
                Log.d(TAG, "Layer toggled (fallback): " + layer + " (" + type + ") -> " + 
                    (visible ? "visible" : "hidden") + " (" + markers.size() + " markers)");
            } else {
                Log.d(TAG, "Layer toggled: " + layer + " -> " + 
                    (visible ? "visible" : "hidden") + " (no markers found)");
            }
        }

        // Güvenlik: toggle sonrası tüm marker'lar için görünürlüğü yeniden uygula.
        // Böylece layer etiketi sonradan set edilen veya edge-case marker'lar da doğru state'e gelir.
        updateMarkerVisibilityForLayers();
    }
    
    /**
     * Layer için gerekli verileri yükle
     * Layer toggle edildiğinde ve veriler yoksa çağrılır
     */
    private void loadDataForLayer(Layer layer) {
        if (lastKnownLocation == null) {
            Log.w(TAG, "loadDataForLayer: Konum bilgisi yok, önce konum alınıp tekrar denenecek");
            ensureLocationPermissionThen(() -> fetchLastKnownLocation(loc -> {
                lastKnownLocation = loc;
                if (lastKnownLocation != null) {
                    loadDataForLayer(layer); // retry
                } else {
                    Log.w(TAG, "loadDataForLayer: Konum alınamadı, veriler yüklenemiyor");
                    // ÖNCE: Hardcoded "Konum alınamadı."
                    // ŞİMDİ: String resource kullanımı
                    Toast.makeText(this, getString(R.string.sokak_toast_location_unavailable), Toast.LENGTH_SHORT).show();
                }
            }));
            return;
        }
        
        switch (layer) {
            case VETS_NEARBY:
                // Veterinerler için veri yükle
                loadNearbyVets(lastKnownLocation);
                break;
            case TASKS:
            case FEEDING_AREAS:
            case NESTING_AREAS:
            case SHELTERS:
            case EMERGENCY_AREAS:
            case RESPONSIBLE_AREAS:
            case RESPONSIBLE_NEWLY_ADDED:
            case OVERDUE_CARE_AREAS:
            case ALL_WITHIN_5KM:
                // Node'lar için veri yükle (tüm node tipleri için)
                loadNearbyNodes(lastKnownLocation);
                break;
            default:
                Log.d(TAG, "loadDataForLayer: Bilinmeyen layer: " + layer);
                break;
        }
    }
    
    private void clearLayer(int index, Layer layer) {
        layerVisible[index] = false;
        updateToggleIcon(index);
        
        // Marker'ları ve cache'i temizle
        if (layer == Layer.TASKS) {
            // TASKS layer'ında hem "Görev" hem de "Acil" tipleri olabilir
            clearMarkersAndCacheByType("Görev");
            clearMarkersAndCacheByType("Gorev");
            clearMarkersAndCacheByType("Acil");
        } else {
        String type = getTypeForLayer(layer);
        if (type != null) {
            clearMarkersAndCacheByType(type);
            }
        }
        
        Log.d(TAG, "Layer cleared: " + layer);
    }
    
    /**
     * Layer enum'ından marker type string'ine dönüştür
     */
    private String getTypeForLayer(Layer layer) {
        if (layer == null) return null;
        switch (layer) {
            case VETS_NEARBY:
                return "Baksi";
            case EMERGENCY_AREAS:
                return "Emergency";
            case RESPONSIBLE_AREAS:
                return "Responsible";
            case RESPONSIBLE_NEWLY_ADDED:
                return "ResponsibleNew";
            case OVERDUE_CARE_AREAS:
                return "Overdue";
            case ALL_WITHIN_5KM:
                return "All5km";
            case SHELTERS:
                // ÖNCE: Hardcoded "Barınak"
                // ŞİMDİ: String resource kullanımı - çeviri desteği için
                return getString(R.string.sokak_layer_type_shelter);
            case FEEDING_AREAS:
                // ÖNCE: Hardcoded "Besleme"
                // ŞİMDİ: String resource kullanımı
                return getString(R.string.sokak_layer_type_feeding);
            case NESTING_AREAS:
                // ÖNCE: Hardcoded "Yuva"
                // ŞİMDİ: String resource kullanımı
                return getString(R.string.sokak_layer_type_nest);
            case TASKS:
                // ÖNCE: Hardcoded "Görev"
                // ŞİMDİ: String resource kullanımı
                return getString(R.string.sokak_layer_type_task);
            default:
                return null;
        }
    }
    
    /**
     * Marker type string'inden Layer enum'ına dönüştür
     * Node tipine göre hangi layer'a ait olduğunu belirler
     */
    private Layer getLayerForType(String type) {
        if (type == null) return null;
        switch (type) {
            case "Baksi":
                return Layer.VETS_NEARBY;
            case "Emergency":
                return Layer.EMERGENCY_AREAS;
            case "Responsible":
                return Layer.RESPONSIBLE_AREAS;
            case "ResponsibleNew":
                return Layer.RESPONSIBLE_NEWLY_ADDED;
            case "Overdue":
                return Layer.OVERDUE_CARE_AREAS;
            // ÖNCE: Hardcoded "Görev", "Gorev", "Acil", "AcilTask", "Urgent", "Barınak", "Besleme", "Yuva"
            // ŞİMDİ: String resource ile karşılaştırma - çeviri desteği için
            case "Görev":
            case "Gorev":
                return Layer.TASKS;
            case "Acil":
            case "AcilTask":
            case "Urgent":
                return Layer.TASKS;
            case "All5km":
                return Layer.ALL_WITHIN_5KM;
            default:
                // String resource ile karşılaştır (çeviri desteği)
                String shelterStr = getString(R.string.sokak_layer_type_shelter);
                String feedingStr = getString(R.string.sokak_layer_type_feeding);
                String nestStr = getString(R.string.sokak_layer_type_nest);
                String taskStr = getString(R.string.sokak_layer_type_task);
                if (type.equals(shelterStr)) return Layer.SHELTERS;
                if (type.equals(feedingStr)) return Layer.FEEDING_AREAS;
                if (type.equals(nestStr)) return Layer.NESTING_AREAS;
                if (type.equals(taskStr)) return Layer.TASKS;
                // Eski hardcoded değerlerle de karşılaştır (backward compatibility)
                if (type.equals("Barınak")) return Layer.SHELTERS;
                if (type.equals("Besleme")) return Layer.FEEDING_AREAS;
                if (type.equals("Yuva")) return Layer.NESTING_AREAS;
                return null;
        }
    }
    
    /**
     * Layer enum'ını string'e dönüştür (cache için)
     */
    private String getLayerString(Layer layer) {
        if (layer == null) return null;
        return layer.name(); // "VETS_NEARBY", "FEEDING_AREAS", vb.
    }
    
    private void updateToggleIcon(int index) {
        ImageButton toggle = getToggleButton(index);
        if (toggle != null) {
            int iconRes = layerVisible[index] 
                ? R.drawable.ic_eye_open_24dp 
                : R.drawable.ic_eye_closed_24dp;
            toggle.setImageResource(iconRes);
            Log.d(TAG, "updateToggleIcon: Layer " + index + " -> " + (layerVisible[index] ? "OPEN" : "CLOSED"));
        } else {
            Log.w(TAG, "updateToggleIcon: Toggle button " + index + " is null!");
        }
    }
    
    private void updateAllToggleIcons() {
        for (int i = 0; i < layerVisible.length; i++) {
            updateToggleIcon(i);
        }
    }
    
    private ImageButton getToggleButton(int index) {
        switch (index) {
            case 0: return toggle1;
            case 1: return toggle2;
            case 2: return toggle3;
            case 3: return toggle4;
            case 4: return toggle5;
            case 5: return toggle6;
            case 6: return toggle7;
            case 7: return toggle8;
            case 8: return toggle9;
            default: return null;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initMapOverlayRouting() {
        // Harita "marker placement / reposition" modundayken overlay dokunmayı tüketir.
        mapOverlay.setOnTouchListener((v, event) -> harita != null && harita.handleOverlayTouch(event));
        // lockOverlay (kilit mod) sadece “bloklayıcı perde” gibi davranır.
        lockOverlay.setOnTouchListener((v, event) -> {
            // kilitliyken map etkileşimini engelle
            return true;
        });
    }

    private void initMiniFabs() {
        // MiniFabs: main_fab tıklanınca kapanıp mini fab’lar açılır (animasyon MiniFabs içinde)
        // Sürükleme: mainFab kapalıyken sürüklenebilir; açılınca mainFab zaten GONE olacağı için sürükleme fiilen devre dışı kalır.
        int[] miniFabIds = new int[]{R.id.ülgen_fab, R.id.coban_fab, R.id.acil_fab};
        miniFabs = new MiniFabs(this, mainFab, soundFab, miniFabIds);

        miniFabs.setupDraggableOnly(this, mainFab);

        ulgenFab.setOnClickListener(v -> {
            // TODO(ULGEN FAB): Ülgen aksiyon menüsü / overdue zinciri / admin işleri
            // ÖNCE: Hardcoded "Ülgen FAB (TODO)"
            // ŞİMDİ: String resource kullanımı
            Toast.makeText(this, getString(R.string.sokak_toast_ulgen_fab_todo), Toast.LENGTH_SHORT).show();
            miniFabs.toggleWithAnimation();
            startActivity(new Intent(this, Welcome.class));
        });

        cobanFab.setOnClickListener(v -> {
            // TODO(COBAN FAB): Harita kilit mod / yerleştirme modu gibi bir aksiyon
            toggleLockMode();
            miniFabs.toggleWithAnimation();
            startActivity(new Intent(this, Kurmes.class));
        });

        acilFab.setOnClickListener(v -> {
            // TODO(ACIL FAB): Acil yardım layer/aksiyon
            // ÖNCE: Hardcoded "Acil FAB (TODO)"
            // ŞİMDİ: String resource kullanımı
            Toast.makeText(this, getString(R.string.sokak_toast_acil_fab_todo), Toast.LENGTH_SHORT).show();
            miniFabs.toggleWithAnimation();
        });

        soundFab.setOnClickListener(v -> {
            // TODO(SOUND): sessize al / bildirim sesi vs
            // ÖNCE: Hardcoded "Sound (TODO)"
            // ŞİMDİ: String resource kullanımı
            Toast.makeText(this, getString(R.string.sokak_toast_sound_todo), Toast.LENGTH_SHORT).show();
        });
    }

    private void toggleLockMode() {
        isLockedMode = !isLockedMode;
        onLockModeChanged(isLockedMode);
    }

    // MiniFabs dışına tıklanınca kapanma desteği
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (miniFabs != null) {
            miniFabs.handleOutsideTouch(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    // =========================
    // Auth + Role
    // =========================

    private interface RoleCallback { void onRole(Role role); }

    private void ensureUserOrAnonymousThenResolveRole(RoleCallback cb) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();

        if (user == null) {
            // Anon sign-in (rules auth istiyorsa işe yarar; anon kullanıcıya UI kapatacağız)
            auth.signInAnonymously()
                    .addOnSuccessListener(r -> resolveRoleFromUser(auth.getCurrentUser(), cb))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Anon sign-in failed: " + e.getMessage());
                        cb.onRole(Role.ANON);
                    });
        } else {
            resolveRoleFromUser(user, cb);
        }
    }

    private void resolveRoleFromUser(FirebaseUser user, RoleCallback cb) {
        if (user == null) {
            cb.onRole(Role.ANON);
            return;
        }

        if (user.isAnonymous()) {
            cb.onRole(Role.ANON);
            return;
        }

        // Custom Claims'ten rol oku
        user.getIdToken(true)
                .addOnSuccessListener(tokenResult -> {
                    try {
                        // Token'dan claims'i parse et
                        String idToken = tokenResult.getToken();
                        String claimsJson = CFHelper.getCustomClaims(idToken);
                        
                        if (claimsJson != null) {
                            org.json.JSONObject claims = new org.json.JSONObject(claimsJson);
                            String roleStr = claims.optString("role", null);
                            
                            if (roleStr != null && !roleStr.trim().isEmpty()) {
                                // Role string'ini normalize et ve enum'a çevir
                                Role role = parseRoleFromString(roleStr);
                                Log.d(TAG, "resolveRoleFromUser: Role bulundu - " + roleStr + " -> " + role);
                                cb.onRole(role);
                                return;
                            }
                        }
                        
                        // Claims'te role yoksa veya parse edilemediyse default: AGAC
                        Log.w(TAG, "resolveRoleFromUser: Custom claims'te role bulunamadı, default AGAC kullanılıyor");
                        cb.onRole(Role.AGAC);
                    } catch (Exception e) {
                        Log.e(TAG, "resolveRoleFromUser: Claims parse hatası", e);
                        // Hata durumunda default: AGAC
                        cb.onRole(Role.AGAC);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "resolveRoleFromUser: Token alınamadı", e);
                    // Token alınamazsa default: AGAC
                    cb.onRole(Role.AGAC);
                });
    }

    /**
     * Role string'ini (örn. "Tengri", "Ülgen", "Körmöz", "İye", "Ağaç") Role enum'una çevirir
     */
    private Role parseRoleFromString(String roleStr) {
        if (roleStr == null || roleStr.trim().isEmpty()) {
            return Role.AGAC; // Default
        }

        // Normalizasyon kaldırıldı - direkt string karşılaştırması
        String roleStrTrimmed = roleStr != null ? roleStr.trim() : "";
        
        // Role mapping - direkt eşleştirme (normalizasyon yok)
        if (roleStrTrimmed.equalsIgnoreCase("Tengri")) {
            return Role.TENGRI;
        } else if (roleStrTrimmed.equalsIgnoreCase("Ülgen") || roleStrTrimmed.equalsIgnoreCase("Ulgen")) {
            return Role.ULGEN;
        } else if (roleStrTrimmed.equalsIgnoreCase("Körmöz") || roleStrTrimmed.equalsIgnoreCase("Kormoz")) {
            return Role.KORMOZ;
        } else if (roleStrTrimmed.equalsIgnoreCase("İye") || roleStrTrimmed.equalsIgnoreCase("Iye")) {
            return Role.IYE;
        } else if (roleStrTrimmed.equalsIgnoreCase("Ağaç") || roleStrTrimmed.equalsIgnoreCase("Agac")) {
            return Role.AGAC;
        } else if (roleStrTrimmed.equalsIgnoreCase("Anon") || roleStrTrimmed.equalsIgnoreCase("Anonymous")) {
            return Role.ANON;
        } else {
            Log.w(TAG, "parseRoleFromString: Bilinmeyen role string: " + roleStr + ", default AGAC");
            return Role.AGAC; // Default fallback
        }
    }

    /**
     * Role'e göre row_spinner görünürlüğünü ayarlar.
     * Her role için ilgili layer row'larını gösterir/gizler.
     */
    private void setRowVisibilityForRole(Role role) {
        if (role == null) {
            Log.w(TAG, "setRowVisibilityForRole: Role null, tüm row'lar gizleniyor");
            if (row1 != null) row1.setVisibility(View.GONE);
            if (row2 != null) row2.setVisibility(View.GONE);
            if (row3 != null) row3.setVisibility(View.GONE);
            if (row4 != null) row4.setVisibility(View.GONE);
            if (row5 != null) row5.setVisibility(View.GONE);
            if (row6 != null) row6.setVisibility(View.GONE);
            if (row7 != null) row7.setVisibility(View.GONE);
            if (row8 != null) row8.setVisibility(View.GONE);
            if (row9 != null) row9.setVisibility(View.GONE);
            return;
        }
        
        // Önce tüm row'ları gizle
        if (row1 != null) row1.setVisibility(View.GONE);
        if (row2 != null) row2.setVisibility(View.GONE);
        if (row3 != null) row3.setVisibility(View.GONE);
        if (row4 != null) row4.setVisibility(View.GONE);
        if (row5 != null) row5.setVisibility(View.GONE);
        if (row6 != null) row6.setVisibility(View.GONE);
        if (row7 != null) row7.setVisibility(View.GONE);
        if (row8 != null) row8.setVisibility(View.GONE);
        if (row9 != null) row9.setVisibility(View.GONE);
        
        // Role'e göre ilgili row'ları göster
        switch (role) {
            case TENGRI:
                // Tengri: Sorumluluğundaki yerler ∩ "son birkaç gün" eklenenler
                // Not: Veterinerler tüm roller için faydalı bir katman; otomatik açmayacağız ama UI'da görünsün.
                if (row1 != null) row1.setVisibility(View.VISIBLE); // VETS_NEARBY (manual)
                if (row3 != null) row3.setVisibility(View.VISIBLE); // RESPONSIBLE_AREAS
                if (row4 != null) row4.setVisibility(View.VISIBLE); // RESPONSIBLE_NEWLY_ADDED
                // İstenen node toggle'ları (manual): Barınak/Besleme/Yuva/Görev
                if (row6 != null) row6.setVisibility(View.VISIBLE); // SHELTERS
                if (row7 != null) row7.setVisibility(View.VISIBLE); // FEEDING_AREAS
                if (row8 != null) row8.setVisibility(View.VISIBLE); // NESTING_AREAS
                if (row9 != null) row9.setVisibility(View.VISIBLE); // TASKS
                break;
            case IYE:
                // İye: Sadece sorumluluğundaki yerler
                if (row1 != null) row1.setVisibility(View.VISIBLE); // VETS_NEARBY (manual)
                if (row3 != null) row3.setVisibility(View.VISIBLE); // RESPONSIBLE_AREAS
                // Görevler: İye kullanıcıları "Görev" etiketli kayıtları görebilir (toggle satırı görünsün)
                if (row9 != null) row9.setVisibility(View.VISIBLE); // TASKS (manual)
                break;
            case KORMOZ:
                // Körmöz: Yakındaki veterinerler + acil yardım istenen alanlar
                if (row1 != null) row1.setVisibility(View.VISIBLE); // VETS_NEARBY
                if (row2 != null) row2.setVisibility(View.VISIBLE); // EMERGENCY_AREAS
                // Görevler: Körmöz "Acil" ve "Görev" etiketli kayıtları görebilir
                if (row9 != null) row9.setVisibility(View.VISIBLE); // TASKS (manual)
                break;
            case ULGEN:
                // Ülgen: 1) bakımı geciken alanlar, yoksa 2) acil yardım alanları, yoksa 3) 5km yakınındaki tüm alanlar
                if (row1 != null) row1.setVisibility(View.VISIBLE); // VETS_NEARBY (manual)
                if (row5 != null) row5.setVisibility(View.VISIBLE); // OVERDUE_CARE_AREAS
                if (row2 != null) row2.setVisibility(View.VISIBLE); // EMERGENCY_AREAS
                if (row6 != null) row6.setVisibility(View.VISIBLE); // SHELTERS
                if (row7 != null) row7.setVisibility(View.VISIBLE); // FEEDING_AREAS
                if (row8 != null) row8.setVisibility(View.VISIBLE); // NESTING_AREAS
                if (row9 != null) row9.setVisibility(View.VISIBLE); // TASKS (manual)
                break;
            case AGAC:
                // Ağaç: EN YETKİSİZ - sadece veterinerleri (ve kendi konumu) görebilir.
                if (row1 != null) row1.setVisibility(View.VISIBLE); // VETS_NEARBY (manual)
                break;
            case ANON:
                // Anonim: Layer UI kapalı (zaten applyLayerUiAccessPolicy'de spinnerContainer GONE yapılıyor)
                // Row'lar zaten gizli kalacak
                break;
        }
        
        Log.d(TAG, "setRowVisibilityForRole: Role=" + role + " için row görünürlüğü ayarlandı");
    }

    private void applyLayerUiAccessPolicy(Role role) {
        // Anonim kullanıcı layer UI (spinner_container) göremeyecek
        spinnerContainer.setVisibility(role == Role.ANON ? View.GONE : View.VISIBLE);

        // Role normalizasyonu kaldırıldı - layer görünürlüğü kullanıcı tarafından kontrol edilecek
        // Tüm layer'lar başlangıçta kapalı (kullanıcı toggle butonları ile açabilir)
        for (int i = 0; i < layerVisible.length; i++) {
            layerVisible[i] = false;
        }
        updateAllToggleIcons();

        // Anon ve Ağaç mini fab menüsünü görmesin (Ağaç en yetkisiz)
        if (role == Role.ANON || role == Role.AGAC) {
            mainFab.setVisibility(View.GONE);
            ulgenFab.setVisibility(View.GONE);
            cobanFab.setVisibility(View.GONE);
            acilFab.setVisibility(View.GONE);
            soundFab.setVisibility(View.GONE);
        } else {
            mainFab.setVisibility(View.VISIBLE);
            // miniFabs zaten kapalı durumda mini'leri GONE tutar; burada zorlamıyoruz
            soundFab.setVisibility(View.GONE);
        }
    }
    
    /**
     * Role'e göre layer görünürlüğünü ayarlar.
     * Her role için ilgili layer'ları otomatik olarak açar.
     */
    private void updateLayerVisibilityForRole(Role role) {
        if (role == null) {
            Log.w(TAG, "updateLayerVisibilityForRole: Role null, tüm layer'lar kapalı");
            for (int i = 0; i < layerVisible.length; i++) {
                layerVisible[i] = false;
            }
            updateAllToggleIcons();
            updateMarkerVisibilityForLayers();
            return;
        }
        
        // Önce tüm layer'ları kapat
        for (int i = 0; i < layerVisible.length; i++) {
            layerVisible[i] = false;
        }
        
        // Role'e göre ilgili layer'ları aç
        switch (role) {
            case TENGRI:
                // Tengri: Sorumluluğundaki yerler ∩ "son birkaç gün" eklenenler
                layerVisible[2] = true; // RESPONSIBLE_AREAS (index 2)
                layerVisible[3] = true; // RESPONSIBLE_NEWLY_ADDED (index 3)
                break;
            case IYE:
                // İye: Sadece sorumluluğundaki yerler
                layerVisible[2] = true; // RESPONSIBLE_AREAS (index 2)
                break;
            case KORMOZ:
                // Körmöz: Yakındaki veterinerler + acil yardım istenen alanlar
                layerVisible[0] = true; // VETS_NEARBY (index 0)
                layerVisible[1] = true; // EMERGENCY_AREAS (index 1)
                break;
            case ULGEN:
                // Ülgen: 1) bakımı geciken alanlar, yoksa 2) acil yardım alanları, yoksa 3) 5km yakınındaki tüm alanlar
                layerVisible[4] = true; // OVERDUE_CARE_AREAS (index 4)
                layerVisible[1] = true; // EMERGENCY_AREAS (index 1)
                layerVisible[5] = true; // SHELTERS (index 5)
                layerVisible[6] = true; // FEEDING_AREAS (index 6)
                layerVisible[7] = true; // NESTING_AREAS (index 7)
                break;
            case AGAC:
                // Ağaç: EN YETKİSİZ - sadece veterinerleri (ve kendi konumu) görebilir.
                // Varsayılan olarak hiçbir layer açık değil.
                break;
            case ANON:
                // Anonim: Sadece yakındaki veterinerler (Layer UI kapalı ama veri yüklenecek)
                layerVisible[0] = true; // VETS_NEARBY (index 0)
                break;
        }
        
        // Toggle icon'ları güncelle
        updateAllToggleIcons();
        
        // Marker görünürlüğünü güncelle
        updateMarkerVisibilityForLayers();
        
        Log.d(TAG, "updateLayerVisibilityForRole: Role=" + role + " için layer görünürlüğü ayarlandı");
    }
    
    /**
     * Layer görünürlük durumuna göre marker'ların görünürlüğünü güncelle
     * Layer mapping kullanarak marker'ları yönetir
     */
    private void updateMarkerVisibilityForLayers() {
        if (nodeCache == null) {
            Log.w(TAG, "updateMarkerVisibilityForLayers: nodeCache null");
            return;
        }
        
        // Layer enum'ından layerVisible array index'ine mapping
        // layerVisible array sırası: VETS_NEARBY(0), EMERGENCY_AREAS(1), RESPONSIBLE_AREAS(2), 
        // RESPONSIBLE_NEWLY_ADDED(3), OVERDUE_CARE_AREAS(4), SHELTERS(5), FEEDING_AREAS(6), NESTING_AREAS(7), TASKS(8)
        Layer[] layers = {
            Layer.VETS_NEARBY,           // index 0
            Layer.EMERGENCY_AREAS,       // index 1
            Layer.RESPONSIBLE_AREAS,     // index 2
            Layer.RESPONSIBLE_NEWLY_ADDED, // index 3
            Layer.OVERDUE_CARE_AREAS,    // index 4
            Layer.SHELTERS,              // index 5
            Layer.FEEDING_AREAS,         // index 6
            Layer.NESTING_AREAS,         // index 7
            Layer.TASKS                  // index 8
        };
        
        for (int i = 0; i < layers.length && i < layerVisible.length; i++) {
            Layer layer = layers[i];
            String layerString = getLayerString(layer);
            if (layerString != null) {
                List<Marker> markers = nodeCache.getMarkersByLayer(layerString);
                boolean visible = layerVisible[i];
                
                for (Marker marker : markers) {
                    if (marker != null) {
                        marker.setVisible(visible);
                    }
                }
                
                if (markers.size() > 0) {
                    Log.d(TAG, "updateMarkerVisibilityForLayers: " + layerString + " (index " + i + ") -> " + 
                        (visible ? "visible" : "hidden") + " (" + markers.size() + " markers)");
                }
            }
        }
        
        // ALL_WITHIN_5KM layer'ı için de kontrol et (ama layerVisible array'inde yok, o yüzden skip)
        // Eğer gelecekte eklenirse buraya eklenebilir
    }

    // =========================
    // Map ready + Location
    // =========================

    private interface SimpleCb { void run(); }
    private interface LocationCb { void onLocation(LatLng loc); }

    private void waitMapReadyThen(SimpleCb cb) {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                tries++;
                if (harita != null && harita.isReady() && harita.getNodeManager() != null) {
                    cb.run();
                    return;
                }
                if (tries > 40) { // ~8sn
                    Log.w(TAG, "Map not ready in time.");
                    cb.run(); // yine de akışı bozmayalım
                    return;
                }
                h.postDelayed(this, 200);
            }
        }, 200);
    }

    private void ensureLocationPermissionThen(SimpleCb cb) {
        if (hasLocationPermission(this)) {
            cb.run();
            return;
        }
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQ_LOCATION);

        // izin sonucu gelince cb’yi çağırmak için basit bir “retry loop”
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            int tries = 0;
            @Override
            public void run() {
                tries++;
                if (hasLocationPermission(SokakActivity.this)) {
                    cb.run();
                    return;
                }
                if (tries > 30) {
                    // ÖNCE: Hardcoded "Konum izni verilmedi."
                    // ŞİMDİ: String resource kullanımı
                    Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_location_permission_denied), Toast.LENGTH_LONG).show();
                    cb.run();
                    return;
                }
                h.postDelayed(this, 200);
            }
        }, 200);
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void fetchLastKnownLocation(LocationCb cb) {
        if (!hasLocationPermission(this)) { // senin permission kontrolün
            cb.onLocation(null);
            return;
        }

        fusedClient.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        cb.onLocation(new LatLng(loc.getLatitude(), loc.getLongitude()));
                        return;
                    }

                    // getLastLocation() null dönebilir (özellikle ilk açılış/izin sonrası).
                    // Bu durumda aktif bir konum isteği yapalım.
                    try {
                        com.google.android.gms.tasks.CancellationTokenSource cts =
                                new com.google.android.gms.tasks.CancellationTokenSource();
                        fusedClient.getCurrentLocation(
                                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                        cts.getToken())
                                .addOnSuccessListener(cur -> {
                                    if (cur != null) cb.onLocation(new LatLng(cur.getLatitude(), cur.getLongitude()));
                    else cb.onLocation(null);
                })
                .addOnFailureListener(e -> cb.onLocation(null));
                    } catch (SecurityException se) {
                        cb.onLocation(null);
                    }
                })
                .addOnFailureListener(e -> {
                    // lastLocation başarısız olduysa da aktif istek ile fallback
                    try {
                        com.google.android.gms.tasks.CancellationTokenSource cts =
                                new com.google.android.gms.tasks.CancellationTokenSource();
                        fusedClient.getCurrentLocation(
                                        com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                                        cts.getToken())
                                .addOnSuccessListener(cur -> {
                                    if (cur != null) cb.onLocation(new LatLng(cur.getLatitude(), cur.getLongitude()));
                                    else cb.onLocation(null);
                                })
                                .addOnFailureListener(err -> cb.onLocation(null));
                    } catch (SecurityException se) {
                        cb.onLocation(null);
                    }
                });
    }

    // =========================
    // Role default layers + loading
    // =========================

    /**
     * Role'e göre default layer'ları yükler.
     * Her role için ilgili verileri otomatik olarak yükler.
     */
    private void applyRoleDefaultLayers(Role role, LatLng userLocation) {
        if (role == null || userLocation == null) {
            Log.w(TAG, "applyRoleDefaultLayers: Role veya location null, veri yüklenemiyor");
            return;
        }
        
        Log.d(TAG, "applyRoleDefaultLayers: Role=" + role + " için default layer'lar yükleniyor");
        
        // Role'e göre ilgili verileri yükle
        switch (role) {
            case TENGRI:
                // Tengri: Sorumluluğundaki yerler ∩ "son birkaç gün" eklenenler
                loadNearbyNodes(userLocation); // RESPONSIBLE_AREAS ve RESPONSIBLE_NEWLY_ADDED için
                break;
            case IYE:
                // İye: Sadece sorumluluğundaki yerler
                loadNearbyNodes(userLocation); // RESPONSIBLE_AREAS için
                break;
            case KORMOZ:
                // Körmöz: Yakındaki veterinerler + acil yardım istenen alanlar
                loadNearbyVets(userLocation); // VETS_NEARBY için
                loadNearbyNodes(userLocation); // EMERGENCY_AREAS için
                break;
            case ULGEN:
                // Ülgen: 1) bakımı geciken alanlar, yoksa 2) acil yardım alanları, yoksa 3) 5km yakınındaki tüm alanlar
                // Öncelik sırasına göre yükleme yapılacak (önce OVERDUE_CARE_AREAS, sonra EMERGENCY_AREAS, sonra diğerleri)
                loadNearbyNodes(userLocation); // Tüm node tipleri için
                break;
            case AGAC:
                // Ağaç: Kullanıcı manuel olarak layer'ları açacak, otomatik yükleme yok
                break;
            case ANON:
                // Anonim: Sadece yakındaki veterinerler
                loadNearbyVets(userLocation); // VETS_NEARBY için
                break;
        }
    }

    /**
     * Yakındaki Nodes'ları Cloud Functions üzerinden yükler ve haritaya ekler
     */
    private void loadNearbyNodes(@NonNull LatLng center) {
        if (harita == null) {
            Log.w(TAG, "loadNearbyNodes: harita null");
            return;
        }

        // Country ve city bilgisi yoksa önce geocoder'dan al
        if (lastKnownCountry == null || lastKnownCity == null) {
            Log.d(TAG, "loadNearbyNodes: Country/city bilgisi yok, geocoder'dan alınıyor...");
            resolveAdminPathFromLocation(center, adminPath -> {
                if (lastKnownCountry != null && lastKnownCity != null) {
                    Log.d(TAG, "loadNearbyNodes: Country/city alındı, Nodes yükleniyor - country=" + 
                            lastKnownCountry + " city=" + lastKnownCity);
                    // Varsayılan: 5km radius, max 200 marker
                    harita.fetchNodesNearby(center, 5000.0, 200, lastKnownCountry, lastKnownCity);
                } else {
                    Log.w(TAG, "loadNearbyNodes: Country/city alınamadı, Nodes yüklenemiyor");
                }
            });
            return;
        }

        Log.d(TAG, "loadNearbyNodes: Nodes yükleniyor - center=" + center + 
                " country=" + lastKnownCountry + " city=" + lastKnownCity);
        
        // Harita.java'daki fetchNodesNearby metodunu çağır
        // Varsayılan: 5km radius, max 200 marker
        harita.fetchNodesNearby(center, 5000.0, 200, lastKnownCountry, lastKnownCity);
        
        // Marker'lar eklendikten sonra görünürlüğü güncelle
        // fetchNodesNearby async çalıştığı için bir delay ile güncelleme yapıyoruz
        // Not: İdeal çözüm Harita.fetchNodesNearby içinde callback olacak, şimdilik delay ile çözüyoruz
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            if (nodeCache != null) {
                updateMarkerVisibilityForLayers();
                Log.d(TAG, "loadNearbyNodes: Marker görünürlüğü güncellendi");
            }
        }, 1500); // 1.5 saniye sonra güncelle (marker'ların eklenmesi için yeterli süre)
    }

    /**
     * Veterinerleri gösterme:
     *  - CACHE-FIRST: Önce cache'den oku
     *  - Cache'de yeterli veri yoksa Cloud Functions'tan çek
     *  - Veritabanından çekilen verileri cache'e kaydet
     */
    private void loadNearbyVets(@NonNull LatLng center) {
        // Map / NodeManager hazır değilse marker ekleyemeyiz.
        if (harita == null || !harita.isReady() || harita.getNodeManager() == null) {
            Log.w(TAG, "loadNearbyVets: map/nodeManager not ready yet, will retry once");
            waitMapReadyThen(() -> {
                if (harita == null || !harita.isReady() || harita.getNodeManager() == null) {
                    Log.w(TAG, "loadNearbyVets: map/nodeManager still not ready, skipping");
                    return;
                }
                loadNearbyVets(center);
            });
            return;
        }

        // CACHE-FIRST: Önce cache'den oku
        if (nodeCache != null && lastKnownCountry != null && lastKnownCity != null) {
            List<JSONObject> cachedBaksi = nodeCache.getBaksiNearby(center, 5000.0);
            
            // Cache'de yeterli veri varsa (en az 3 tane) kullan
            if (cachedBaksi.size() >= 3) {
                Log.d(TAG, "loadNearbyVets: Cache'den " + cachedBaksi.size() + " baksi bulundu, haritaya ekleniyor...");
                
                NodeManager nodeManager = harita.getNodeManager();
                if (nodeManager != null) {
                    int added = 0;
                    for (JSONObject baksi : cachedBaksi) {
                        try {
                            String id = baksi.optString("id", baksi.optString("uid", "vet_" + added));
                            String markerId = "baksi_" + id;
                            
                            // location.lat/lng
                            double lat = Double.NaN;
                            double lng = Double.NaN;
                            JSONObject loc = baksi.optJSONObject("location");
                            if (loc != null) {
                                lat = loc.optDouble("lat", Double.NaN);
                                lng = loc.optDouble("lng", Double.NaN);
                            }
                            if (!Double.isFinite(lat)) lat = baksi.optDouble("lat", Double.NaN);
                            if (!Double.isFinite(lng)) lng = baksi.optDouble("lng", Double.NaN);
                            
                            if (!Double.isFinite(lat) || !Double.isFinite(lng)) continue;
                            // ÖNCE: Hardcoded "Veteriner" fallback
                            // ŞİMDİ: String resource kullanımı
                            String title = baksi.optString("clinicName", baksi.optString("username", getString(R.string.sokak_label_veteriner_fallback)));
                            String snippet = baksi.optString("clinicAddress", baksi.optString("address", ""));
                            
                            com.google.android.gms.maps.model.BitmapDescriptor icon = nodeManager.getCustomIcon("Baksi");
                            com.google.android.gms.maps.model.MarkerOptions options = new com.google.android.gms.maps.model.MarkerOptions()
                                    .position(new LatLng(lat, lng))
                                    .title(title)
                                    .snippet(snippet)
                                    .icon(icon);
                            
                            com.google.android.gms.maps.model.Marker m = nodeManager.addMarker(options, "Baksi", markerId);
                            if (m != null) {
                                added++;
                                // Layer bilgisi ile cache'e ekle
                                nodeCache.putMarker(markerId, m, "Baksi", getLayerString(Layer.VETS_NEARBY));
                                m.setVisible(layerVisible[0]); // VETS_NEARBY
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "loadNearbyVets: Cache baksi parse hatası", e);
                        }
                    }
                    
                    Log.d(TAG, "loadNearbyVets: Cache'den " + added + " baksi haritaya eklendi");
                    if (added > 0) {
                        // Marker'lar eklendikten sonra görünürlüğü güncelle
                        updateMarkerVisibilityForLayers();
                        return; // Cache'den yeterli veri bulundu, veritabanına gitme
                    }
                }
            } else {
                Log.d(TAG, "loadNearbyVets: Cache'de yeterli veri yok (" + cachedBaksi.size() + " < 3), veritabanından çekiliyor...");
            }
        }
        
        // Cache'de yeterli veri yoksa veritabanından çek
        if (lastKnownAdminPath == null || lastKnownAdminPath.trim().isEmpty()) {
            resolveAdminPathFromLocation(center, ap -> {
                lastKnownAdminPath = ap;
                if (ap != null) {
                    Log.d(TAG, "loadNearbyVets: AdminPath alındı, veterinerler yükleniyor: " + ap);
                    loadNearbyVets(center);
                } else {
                    Log.w(TAG, "loadNearbyVets: AdminPath üretilemedi, veterinerler yüklenemiyor");
                    // ÖNCE: Hardcoded "Konum bilgisi alınamadı. Veterinerler gösterilemiyor."
                    // ŞİMDİ: String resource kullanımı
                    Toast.makeText(this, getString(R.string.sokak_toast_location_info_unavailable), Toast.LENGTH_LONG).show();
                }
            });
            return;
        }

        // Layer/role kontrolünü zaten üst akışta yapıyorsun; burada sadece güvenlik:
        if (harita == null) return;

        // 1) adminPath zorunlu (bu adımı reverseGeocode ile bir önceki dilimde çözeceğiz)
        final String adminPath = lastKnownAdminPath; // TODO: reverseGeocode -> normalize -> buildAdminPath
        if (adminPath == null || adminPath.trim().isEmpty()) {
            // ÖNCE: Hardcoded "AdminPath hazır değil (reverseGeocode gerekli)."
            // ŞİMDİ: String resource kullanımı
            Toast.makeText(this, getString(R.string.sokak_toast_adminpath_not_ready), Toast.LENGTH_SHORT).show();
            return;
        }

        setProgressVisible(true);

        if (cfHelper == null) cfHelper = new CFHelper(this, CF_PROJECT_ID, CF_REGION, null);
        final int radiusM = 5000; // şimdilik sabit; sonra layer’a göre değiştiririz.

        // Debug: Gönderilen parametreleri logla
        Log.d(TAG, "findNearbyBaksi çağrılıyor - adminPath: " + adminPath + ", radiusM: " + radiusM + 
                ", lat: " + center.latitude + ", lng: " + center.longitude);

        cfHelper.findNearbyBaksi(adminPath, radiusM, center.latitude, center.longitude, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                runOnUiThread(() -> {
                    try {
                        setProgressVisible(false);

                        boolean ok = resp.optBoolean("ok", resp.optBoolean("success", false));
                        if (!ok) {
                            // ÖNCE: Hardcoded "unknown" fallback
                            // ŞİMDİ: String resource kullanımı
                            String msg = resp.optString("message", resp.optString("error", getString(R.string.sokak_error_unknown)));
                            Log.e(TAG, "findNearbyBaksi response hatası: " + resp.toString());
                            // ÖNCE: Hardcoded "findNearbyBaksi hata: " + msg
                            // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                            Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_find_nearby_baksi_error, msg), Toast.LENGTH_LONG).show();
                            return;
                        }

                        JSONArray items = resp.optJSONArray("items");
                        if (items == null) items = new JSONArray();

                        NodeManager nodeManager = harita.getNodeManager();
                        if (nodeManager == null) {
                            // ÖNCE: Hardcoded "NodeManager null"
                            // ŞİMDİ: String resource kullanımı
                            Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_node_manager_null), Toast.LENGTH_SHORT).show();
                            return;
                        }

                        int added = 0;

                        for (int i = 0; i < items.length(); i++) {
                            JSONObject it = items.optJSONObject(i);
                            if (it == null) continue;

                            // id/uid
                            String id = it.optString("id", it.optString("uid", "vet_" + i));
                            String markerId = "baksi_" + id;

                            // clinicName
                            // ÖNCE: Hardcoded "Veteriner" fallback
                            // ŞİMDİ: String resource kullanımı
                            String title = it.optString("clinicName",
                                    it.optString("username", getString(R.string.sokak_label_veteriner_fallback)));

                            // address (sen "adress" demişsin; biz olası tüm alanları okuyalım)
                            String snippet = it.optString("adress",
                                    it.optString("clinicAddress",
                                            it.optString("address", "")));

                            // location.lat/lng (fallback: root lat/lng)
                            double lat = Double.NaN;
                            double lng = Double.NaN;

                            JSONObject loc = it.optJSONObject("location");
                            if (loc != null) {
                                lat = loc.optDouble("lat", Double.NaN);
                                lng = loc.optDouble("lng", Double.NaN);
                            }
                            if (!Double.isFinite(lat)) lat = it.optDouble("lat", Double.NaN);
                            if (!Double.isFinite(lng)) lng = it.optDouble("lng", Double.NaN);

                            if (!Double.isFinite(lat) || !Double.isFinite(lng)) continue;

                            // NodeManager'dan özel Baksi ikonunu al
                            com.google.android.gms.maps.model.BitmapDescriptor icon = nodeManager.getCustomIcon("Baksi");
                            
                            MarkerOptions options = new MarkerOptions()
                                    .position(new LatLng(lat, lng))
                                    .title(title)
                                    .snippet(snippet)
                                    .icon(icon);

                            Marker m = nodeManager.addMarker(options, "Baksi", markerId);
                            if (m != null) {
                                added++;
                                // Cache'e ekle
                                // Baksi'yi cache'e ekle (hem memory hem persistent storage)
                                // Country ve city'yi adminPath'den çıkar veya lastKnownCountry/City kullan
                                String baksiCountry = extractCountryFromAdminPath(adminPath);
                                String baksiCity = extractCityFromAdminPath(adminPath);
                                if (baksiCountry == null) baksiCountry = lastKnownCountry;
                                if (baksiCity == null) baksiCity = lastKnownCity;
                                nodeCache.putBaksi(id, it, baksiCountry, baksiCity, System.currentTimeMillis());
                                // Layer bilgisi ile cache'e ekle
                                nodeCache.putMarker(markerId, m, "Baksi", getLayerString(Layer.VETS_NEARBY));
                                
                                // Marker görünürlüğünü layer durumuna göre ayarla
                                boolean shouldBeVisible = layerVisible[0]; // VETS_NEARBY = index 0
                                m.setVisible(shouldBeVisible);
                            }
                        }
                        
                        // Cache istatistiklerini logla
                        NodeCache.CacheStats stats = nodeCache.getStats();
                        Log.d(TAG, "findNearbyBaksi: Cache stats - " + stats.toString());
                        
                        // Marker'lar eklendikten sonra görünürlüğü güncelle
                        updateMarkerVisibilityForLayers();

                        // ÖNCE: Hardcoded "X veteriner yüklendi (Xkm)" ve hardcoded "km"/"m" birimleri
                        // ŞİMDİ: String resource kullanımı - format string ile sayı ve radius parametreleri, birimler lokalize edildi
                        String radiusText = radiusM >= 1000 
                            ? String.format(getString(R.string.format_distance_km), radiusM / 1000f, getString(R.string.unit_kilometer))
                            : String.format(getString(R.string.format_distance_m), radiusM, getString(R.string.unit_meter));
                        Toast.makeText(SokakActivity.this,
                                getString(R.string.sokak_toast_vets_loaded, added, radiusText),
                                Toast.LENGTH_SHORT).show();

                    } catch (Throwable t) {
                        setProgressVisible(false);
                        Log.e(TAG, "findNearbyBaksi parse hatası", t);
                        // ÖNCE: Hardcoded "Parse hata: " + t.getMessage()
                        // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                        String errorMsg = t.getMessage() == null ? "-" : t.getMessage();
                        Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_parse_error, errorMsg), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    setProgressVisible(false);
                    
                    // HttpException ise detaylı bilgi göster
                    // ÖNCE: Hardcoded "unknown" fallback
                    // ŞİMDİ: String resource kullanımı
                    String errorMsg = getString(R.string.sokak_error_unknown);
                    String errorDetails = "";
                    
                    if (error != null) {
                        errorMsg = error.getMessage();
                        
                        // CFHelper.HttpException kontrolü
                        if (error instanceof CFHelper.HttpException) {
                            CFHelper.HttpException httpErr = (CFHelper.HttpException) error;
                            errorDetails = "HTTP " + httpErr.code + " - Response body: " + httpErr.body;
                            Log.e(TAG, "findNearbyBaksi HTTP hatası: " + errorDetails);
                            
                            // Response body'den hata mesajını çıkarmaya çalış
                            try {
                                JSONObject errorJson = new JSONObject(httpErr.body);
                                String serverMsg = errorJson.optString("error", 
                                        errorJson.optString("message", 
                                                errorJson.optString("details", "")));
                                if (!serverMsg.isEmpty()) {
                                    errorMsg = serverMsg;
                                }
                            } catch (Exception ignored) {
                                // JSON parse edilemezse body'yi direkt göster
                                if (httpErr.body != null && !httpErr.body.isEmpty()) {
                                    errorMsg = httpErr.body.length() > 100 
                                            ? httpErr.body.substring(0, 100) + "..." 
                                            : httpErr.body;
                                }
                            }
                        } else {
                            Log.e(TAG, "findNearbyBaksi çağrı hatası", error);
                        }
                    }
                    
                    String fullError = errorMsg;
                    if (!errorDetails.isEmpty()) {
                        fullError += "\n" + errorDetails;
                    }
                    
                    // ÖNCE: Hardcoded "findNearbyBaksi hatası: " + fullError
                    // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                    Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_find_nearby_baksi_full_error, fullError), Toast.LENGTH_LONG).show();
                });
            }
        });
    }


    private void initBaksiHelperIfNeeded() {
        if (baksiHelper != null) return;
        if (harita == null || harita.getNodeManager() == null) return;

        baksiHelper = new BaksiHelper(this, harita.getNodeManager(), new BaksiHelper.BaksiHelperListener() {
            @Override
            public void onVetsLoaded(int count, double radius) {
                runOnUiThread(() -> {
                    setProgressVisible(false);
                    // ÖNCE: Hardcoded count + " veteriner (" + radiusText + ")" ve hardcoded "km"/"m" birimleri
                    // ŞİMDİ: String resource kullanımı - format string ile sayı ve radius parametreleri, birimler lokalize edildi
                    String radiusText = (radius >= 1000) 
                        ? String.format(getString(R.string.format_distance_km), radius / 1000.0, getString(R.string.unit_kilometer))
                        : String.format(getString(R.string.format_distance_m), radius, getString(R.string.unit_meter));
                    Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_vets_count, count, radiusText), Toast.LENGTH_SHORT).show();

                    // TODO: Places yerine Firestore (Baksi collection) üzerinden yükleme + adminPath yoksa CF tetikleme
                });
            }

            @Override
            public void onVetsLoadFailed(String error) {
                runOnUiThread(() -> {
                    setProgressVisible(false);
                    Log.e(TAG, "Vets load failed: " + error);
                    // ÖNCE: Hardcoded "Veteriner yükleme hatası: " + error
                    // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                    Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_vets_load_error, error), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onVetMarkerAdded(Marker marker) {
                Log.d(TAG, "Vet marker added: " + (marker != null ? marker.getTitle() : "null"));
                
                // Marker'ı cache'e ekle
                if (marker != null && nodeCache != null) {
                    Object tag = marker.getTag();
                    String markerId = tag != null ? tag.toString() : "baksi_" + System.currentTimeMillis();
                    // Layer bilgisi ile cache'e ekle
                    nodeCache.putMarker(markerId, marker, "Baksi", getLayerString(Layer.VETS_NEARBY));
                    Log.d(TAG, "Vet marker cached: " + markerId);
                }
            }

            @Override
            public void onSearchRadiusSuggested(double nextRadius, String radiusLabel) {
                runOnUiThread(() -> {
                    // TODO: Kullanıcıya "yarıçapı büyüt" prompt'u
                    // ÖNCE: Hardcoded "Aramayı genişlet: " + radiusLabel
                    // ŞİMDİ: String resource kullanımı - format string ile radius label parametresi
                    Toast.makeText(SokakActivity.this, getString(R.string.sokak_toast_expand_search, radiusLabel), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void setProgressVisible(boolean visible) {
        if (progressBar == null) return;
        progressBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible) {
            progressBar.setIndeterminate(false);
            progressBar.setProgress(0);
        }
    }

    // =========================
    // Harita callbacks
    // =========================

    @Override
    public void onLockModeChanged(boolean locked) {
        isLockedMode = locked;
        lockOverlay.setVisibility(locked ? View.VISIBLE : View.GONE);

        // map_overlay dokunmaları Harita.handleOverlayTouch ile yönetiliyor
        // locked durumda istersen map_overlay’i clickable yapabilirsin, ama şu an lockOverlay zaten blokluyor.
        Log.d(TAG, "Lock mode changed: " + locked);
    }

    @Override
    public void onNodeCreated(String nodeId, String nodeType) {
        // TODO: Node create akışı tamamlandığında UI güncelle
        // ÖNCE: Hardcoded "Node created: " + nodeType + " (" + nodeId + ")"
        // ŞİMDİ: String resource kullanımı - format string ile node type ve id parametreleri
        Toast.makeText(this, getString(R.string.sokak_toast_node_created, nodeType, nodeId), Toast.LENGTH_SHORT).show();
        onLockModeChanged(false);
    }

    @Override
    public void onNodeCreationFailed(String error) {
        // ÖNCE: Hardcoded "Node create failed: " + error
        // ŞİMDİ: String resource kullanımı - format string ile error mesajı
        Toast.makeText(this, getString(R.string.sokak_toast_node_create_failed, error), Toast.LENGTH_LONG).show();
        onLockModeChanged(false);
    }
    
    // =========================
    // Cache Management Methods
    // =========================
    
    /**
     * Belirli bir tip için marker'ları ve cache'i temizle
     */
    private void clearMarkersAndCacheByType(String type) {
        if (harita == null || harita.getNodeManager() == null) return;
        
        // NodeManager'dan marker'ları temizle
        harita.getNodeManager().removeMarkersByType(type);
        
        // Cache'den de temizle
        if (nodeCache != null) {
            nodeCache.clearByType(type);
            Log.d(TAG, "clearMarkersAndCacheByType: type=" + type + " - cache cleared");
        }
    }
    
    /**
     * Tüm marker'ları ve cache'i temizle
     */
    private void clearAllMarkersAndCache() {
        if (harita == null || harita.getNodeManager() == null) return;
        
        // NodeManager'dan tüm marker'ları temizle
        harita.getNodeManager().clearAllMarkers();
        
        // Cache'i de temizle
        if (nodeCache != null) {
            nodeCache.clearAll();
            Log.d(TAG, "clearAllMarkersAndCache: all cache cleared");
        }
    }
    
    /**
     * Cache'den Node ekle veya güncelle
     */
    private void updateNodeInCache(Node node) {
        if (nodeCache == null || node == null || node.getId() == null) return;
        nodeCache.putNode(node.getId(), node);
        Log.d(TAG, "updateNodeInCache: node id=" + node.getId() + " updated");
    }
    
    /**
     * Cache'den Node sil
     */
    private void removeNodeFromCache(String nodeId) {
        if (nodeCache == null || nodeId == null) return;
        nodeCache.removeNode(nodeId);
        Log.d(TAG, "removeNodeFromCache: node id=" + nodeId + " removed");
    }
    
    /**
     * Cache'den marker sil (markerId'ye göre)
     */
    private void removeMarkerFromCache(String markerId) {
        if (nodeCache == null || markerId == null) return;
        nodeCache.removeMarker(markerId);
        Log.d(TAG, "removeMarkerFromCache: marker id=" + markerId + " removed");
    }
    
    /**
     * Cache istatistiklerini logla
     */
    private void logCacheStats() {
        if (nodeCache == null) return;
        NodeCache.CacheStats stats = nodeCache.getStats();
        Log.d(TAG, "Cache Stats: " + stats.toString());
    }

    // =========================
    // NodeDetailsBottomSheet.Host
    // =========================

    @Override
    public void onRequestMarkerReposition(String markerId) {
        if (harita == null) return;
        harita.startRepositionMode(markerId);
        onLockModeChanged(true);
        // ÖNCE: Hardcoded "Marker taşıma modu: " + markerId
        // ŞİMDİ: String resource kullanımı - format string ile marker id parametresi
        Toast.makeText(this, getString(R.string.sokak_toast_marker_reposition_mode, markerId), Toast.LENGTH_SHORT).show();
    }

    // =========================
    // Permissions
    // =========================

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean granted = false;
            for (int r : grantResults) {
                if (r == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            Log.d(TAG, "Location permission result: " + granted);
        }
        // Harita içinde de static handler var (kullanmaya devam edebilirsin)
        Harita.onRequestPermissionsResult(this, requestCode, grantResults);
    }

    /**
     * AdminPath'den country bilgisini çıkar
     */
    private String extractCountryFromAdminPath(String adminPath) {
        if (adminPath == null || adminPath.trim().isEmpty()) return null;

        // Supported formats (case-insensitive):
        // - Baksi/{country}/{city}
        // - Baksi/{country}/Cities/{city}
        // - Baksi/{country}/Cities/{city}/Baksi/{vetUid}
        // - {country}/{city}
        // - {country}/Cities/{city}
        String[] raw = adminPath.trim().replaceAll("^/+", "").replaceAll("/+$", "").split("/");
        if (raw.length == 0) return null;

        int idx = 0;
        if (raw[0].equalsIgnoreCase("Baksi")) idx = 1;
        if (raw.length <= idx) return null;

        String country = raw[idx];
        if (country == null || country.trim().isEmpty()) return null;
        return country.trim().toUpperCase(Locale.ROOT);
    }
    
    /**
     * AdminPath'den city bilgisini çıkar
     */
    private String extractCityFromAdminPath(String adminPath) {
        if (adminPath == null || adminPath.trim().isEmpty()) return null;

        // Supported formats (case-insensitive):
        // - Baksi/{country}/{city}
        // - Baksi/{country}/Cities/{city}
        // - Baksi/{country}/Cities/{city}/Baksi/{vetUid}
        // - {country}/{city}
        // - {country}/Cities/{city}
        String[] raw = adminPath.trim().replaceAll("^/+", "").replaceAll("/+$", "").split("/");
        if (raw.length < 2) return null;

        int idx = 0;
        if (raw[0].equalsIgnoreCase("Baksi")) idx = 1;
        if (raw.length <= idx + 1) return null;

        // If "Cities" segment exists, city is the next segment; otherwise it's the next segment directly.
        int cityIdx;
        if (raw.length > idx + 2 && raw[idx + 1].equalsIgnoreCase("Cities")) {
            cityIdx = idx + 2;
        } else {
            cityIdx = idx + 1;
        }
        if (raw.length <= cityIdx) return null;

        String city = raw[cityIdx];
        if (city == null || city.trim().isEmpty()) return null;
        return city.trim().toUpperCase(Locale.ROOT);
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (harita != null) harita.cleanup();
            // Cache'i temizleme - persistent storage'da kalması için clearAll() çağrılmayacak
            // Sadece memory cache temizlenebilir (marker referansları)
            if (nodeCache != null) {
                // Marker cache'i temizle (UI objesi, memory'de kalmasına gerek yok)
                // Node ve Baksi cache'i persistent storage'da kalacak
                Log.d(TAG, "onDestroy: Cache persistent storage'da korunuyor");
            }
        } catch (Throwable t) {
            Log.w(TAG, "onDestroy error: " + t.getMessage());
        }
    }
}
