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
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.utilities.helper.BaksiHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.umay.sokak.Managers.NodeManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
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
    private final ExecutorService geoExec = Executors.newSingleThreadExecutor();
    private volatile boolean resolvingAdminPath = false;
    private FusedLocationProviderClient fusedClient;

    private interface AdminPathCb { void onResult(@Nullable String adminPath); }
    // ===== Roles =====
    private enum Role { TENGRI, ULGEN, KORMOZ, IYE, AGAC, ANON }

    // ===== Layers =====
    private enum Layer {
        VETS_NEARBY,
        EMERGENCY_AREAS,
        RESPONSIBLE_AREAS,
        RESPONSIBLE_NEWLY_ADDED,
        OVERDUE_CARE_AREAS,
        ALL_WITHIN_5KM
    }

    // ===== State =====
    private Role currentRole = Role.ANON;
    private LatLng lastKnownLocation = null;

    // ===== UI =====
    private ProgressBar progressBar;
    private View lockOverlay;
    private View mapOverlay;
    private LinearLayout spinnerContainer;

    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;

    // ===== Map / Helpers =====
    private Harita harita;
    private BaksiHelper baksiHelper;

    // ===== MiniFabs =====
    private MiniFabs miniFabs;
    private View ulgenFab;
    private View cobanFab;
    private View acilFab;
    private FloatingActionButton soundFab;
    private FloatingActionButton mainFab;
    private boolean isLockedMode = false;

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);

        bindViews();
        initSpinnerUiDraftOnly();
        initMapOverlayRouting();
        initMiniFabs();

        // Harita wrapper
        harita = new Harita(this);
        harita.setLockModeListener(this);
        harita.setNodeCreationListener(this);
        fusedClient = LocationServices.getFusedLocationProviderClient(this);
        fetchLastKnownLocation(loc -> {
            lastKnownLocation = loc;

            resolveAdminPathFromLocation(loc, adminPath -> {
                lastKnownAdminPath = adminPath;

                if (lastKnownAdminPath == null) {
                    Toast.makeText(this, "AdminPath üretilemedi (geocoder).", Toast.LENGTH_LONG).show();
                    return;
                }

                applyRoleDefaultLayers(currentRole, lastKnownLocation);
            });
        });

        // Auth/Role zinciri (anon izinli)
        ensureUserOrAnonymousThenResolveRole(role -> {
            currentRole = role;
            applyLayerUiAccessPolicy(currentRole);

            // Map hazır + konum al + role default layer yükle
            waitMapReadyThen(() -> ensureLocationPermissionThen(() -> {
                fetchLastKnownLocation(loc -> {
                    lastKnownLocation = loc;
                    applyRoleDefaultLayers(currentRole, lastKnownLocation);
                });
            }));
        });
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
    private static String buildAdminPath(String countryRaw, String cityRaw) {
        String countryKey = normalizeCode(countryRaw);
        String cityKey = normalizeCode(cityRaw);
        if (countryKey.isEmpty() || cityKey.isEmpty()) return null;

        // CF’nin beklediği format :contentReference[oaicite:5]{index=5}
        return "Baksi/" + countryKey + "/Cities/" + cityKey;
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
        if (resolvingAdminPath) return;
        resolvingAdminPath = true;

        geoExec.execute(() -> {
            String out = null;
            try {
                if (!Geocoder.isPresent()) {
                    // Emulator/cihazda geocoder yoksa null döneriz (sonra CF ile çözeriz)
                    out = null;
                } else {
                    Geocoder geocoder = new Geocoder(this, new Locale("tr", "TR"));
                    List<Address> list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1);
                    if (list != null && !list.isEmpty()) {
                        Address a = list.get(0);

                        // country: mümkünse COUNTRY CODE (TR) → normalize => "tr"
                        String country = firstNonEmpty(a.getCountryCode(), a.getCountryName());

                        // city: Türkiye’de çoğu zaman AdminArea = il (Adana, İstanbul)
                        String city = firstNonEmpty(a.getAdminArea(), a.getSubAdminArea(), a.getLocality());

                        out = buildAdminPath(country, city);
                    }
                }
            } catch (IOException ignored) {
                out = null;
            }

            final String result = out;
            runOnUiThread(() -> {
                resolvingAdminPath = false;
                cb.onResult(result);
            });
        });
    }

    // =========================
    // UI Binding
    // =========================

    private void bindViews() {
        progressBar = findViewById(R.id.progress_bar);
        lockOverlay = findViewById(R.id.lock_mode_overlay);
        mapOverlay = findViewById(R.id.map_overlay);
        spinnerContainer = findViewById(R.id.spinner_container);

        spinner1 = findViewById(R.id.spinner_level_1);
        spinner2 = findViewById(R.id.spinner_level_2);
        spinner3 = findViewById(R.id.spinner_level_3);
        spinner4 = findViewById(R.id.spinner_level_4);
        spinner5 = findViewById(R.id.spinner_level_5);

        clear1 = findViewById(R.id.btn_clear_1);
        clear2 = findViewById(R.id.btn_clear_2);
        clear3 = findViewById(R.id.btn_clear_3);
        clear4 = findViewById(R.id.btn_clear_4);
        clear5 = findViewById(R.id.btn_clear_5);

        toggle1 = findViewById(R.id.btn_toggle_1);
        toggle2 = findViewById(R.id.btn_toggle_2);
        toggle3 = findViewById(R.id.btn_toggle_3);
        toggle4 = findViewById(R.id.btn_toggle_4);
        toggle5 = findViewById(R.id.btn_toggle_5);

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
     * Spinner UI şu an referans (taslak).
     * TODO: Layer seçimini bu spinner/row yapısına bağla (multi-layer enable/disable).
     */
    private void initSpinnerUiDraftOnly() {
        View.OnClickListener clearNoop = v -> Toast.makeText(this, "TODO: Clear layer", Toast.LENGTH_SHORT).show();
        View.OnClickListener toggleNoop = v -> Toast.makeText(this, "TODO: Toggle layer", Toast.LENGTH_SHORT).show();

        clear1.setOnClickListener(clearNoop);
        clear2.setOnClickListener(clearNoop);
        clear3.setOnClickListener(clearNoop);
        clear4.setOnClickListener(clearNoop);
        clear5.setOnClickListener(clearNoop);

        toggle1.setOnClickListener(toggleNoop);
        toggle2.setOnClickListener(toggleNoop);
        toggle3.setOnClickListener(toggleNoop);
        toggle4.setOnClickListener(toggleNoop);
        toggle5.setOnClickListener(toggleNoop);
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
            Toast.makeText(this, "Ülgen FAB (TODO)", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Acil FAB (TODO)", Toast.LENGTH_SHORT).show();
            miniFabs.toggleWithAnimation();
        });

        soundFab.setOnClickListener(v -> {
            // TODO(SOUND): sessize al / bildirim sesi vs
            Toast.makeText(this, "Sound (TODO)", Toast.LENGTH_SHORT).show();
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

        // TODO: Custom Claims’ten rol oku (CFHelper / user.getIdToken)
        // Şimdilik: login var ama claim yoksa "AĞAÇ" gibi davran.
        cb.onRole(Role.AGAC);
    }

    private void applyLayerUiAccessPolicy(Role role) {
        // Anonim kullanıcı layer UI (spinner_container) göremeyecek
        spinnerContainer.setVisibility(role == Role.ANON ? View.GONE : View.VISIBLE);

        // Anon kullanıcı mini fab menüsünü de görmesin
        if (role == Role.ANON) {
            mainFab.setVisibility(View.GONE);
            ulgenFab.setVisibility(View.GONE);
            cobanFab.setVisibility(View.GONE);
            acilFab.setVisibility(View.GONE);
            soundFab.setVisibility(View.GONE);
        } else {
            mainFab.setVisibility(View.VISIBLE);
            // miniFabs zaten kapalı durumda mini’leri GONE tutar; burada zorlamıyoruz
            soundFab.setVisibility(View.GONE);
        }
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
                    Toast.makeText(SokakActivity.this, "Konum izni verilmedi.", Toast.LENGTH_LONG).show();
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
                    if (loc != null) cb.onLocation(new LatLng(loc.getLatitude(), loc.getLongitude()));
                    else cb.onLocation(null);
                })
                .addOnFailureListener(e -> cb.onLocation(null));
    }

    // =========================
    // Role default layers + loading
    // =========================

    private void applyRoleDefaultLayers(Role role, LatLng userLocation) {
        if (userLocation == null) {
            Log.w(TAG, "No location yet; cannot apply role layers.");
            return;
        }

        // TODO: nodeManager.clearAllMarkers() / layer bazlı clear
        // Şimdilik: sadece vets yüklemesini role göre tetikleyeceğiz.

        switch (role) {
            case ANON:
                loadNearbyVets(userLocation);
                break;
            case KORMOZ:
                loadNearbyVets(userLocation);
                // TODO: loadEmergencyAreas(userLocation)
                break;
            case ULGEN:
                // TODO: overdue -> emergency -> allWithin5km fallback zinciri
                loadNearbyVets(userLocation); // en azından vet göster
                break;
            case IYE:
                // TODO: loadResponsibleAreas(userUid)
                loadNearbyVets(userLocation); // opsiyonel: istersen kapat
                break;
            case TENGRI:
                // TODO: loadResponsibleNewlyAdded(userUid, lastDays=3..7)
                loadNearbyVets(userLocation); // opsiyonel
                break;
            case AGAC:
            default:
                loadNearbyVets(userLocation);
                // TODO: default layer seti
                break;
        }
    }

    /**
     * Veterinerleri gösterme:
     *  - Şu an BaksiHelper.startProgressiveVetSearch ile başlatıyor.
     *  - TODO: adminPath cache-miss ise CloudFunction tetikle + Firestore’dan oku.
     */
// SokakActivity içinde bir field önerisi:
// private CFHelper cfHelper;
// private String lastKnownAdminPath; // (reverseGeocode adımında dolduracağız)

    private void loadNearbyVets(@NonNull LatLng center) {
        if (lastKnownAdminPath == null || lastKnownAdminPath.trim().isEmpty()) {
            resolveAdminPathFromLocation(center, ap -> {
                lastKnownAdminPath = ap;
                if (ap != null) loadNearbyVets(center);
                else Toast.makeText(this, "AdminPath üretilemedi.", Toast.LENGTH_LONG).show();
            });
            return;
        }

        // Layer/role kontrolünü zaten üst akışta yapıyorsun; burada sadece güvenlik:
        if (harita == null) return;

        // 1) adminPath zorunlu (bu adımı reverseGeocode ile bir önceki dilimde çözeceğiz)
        final String adminPath = lastKnownAdminPath; // TODO: reverseGeocode -> normalize -> buildAdminPath
        if (adminPath == null || adminPath.trim().isEmpty()) {
            Toast.makeText(this, "AdminPath hazır değil (reverseGeocode gerekli).", Toast.LENGTH_SHORT).show();
            return;
        }

        setProgressVisible(true);

        if (cfHelper == null) cfHelper = new CFHelper(this, CF_PROJECT_ID, CF_REGION, null);
        final int radiusM = 5000; // şimdilik sabit; sonra layer’a göre değiştiririz.

        cfHelper.findNearbyBaksi(adminPath, radiusM, center.latitude, center.longitude, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                runOnUiThread(() -> {
                    try {
                        setProgressVisible(false);

                        boolean ok = resp.optBoolean("ok", resp.optBoolean("success", false));
                        if (!ok) {
                            String msg = resp.optString("message", resp.optString("error", "unknown"));
                            Toast.makeText(SokakActivity.this, "findNearbyBaksi hata: " + msg, Toast.LENGTH_LONG).show();
                            return;
                        }

                        JSONArray items = resp.optJSONArray("items");
                        if (items == null) items = new JSONArray();

                        NodeManager nodeManager = harita.getNodeManager();
                        if (nodeManager == null) {
                            Toast.makeText(SokakActivity.this, "NodeManager null", Toast.LENGTH_SHORT).show();
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
                            String title = it.optString("clinicName",
                                    it.optString("username", "Veteriner"));

                            // address (sen “adress” demişsin; biz olası tüm alanları okuyalım)
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

                            MarkerOptions options = new MarkerOptions()
                                    .position(new LatLng(lat, lng))
                                    .title(title)
                                    .snippet(snippet)
                                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE));

                            Marker m = nodeManager.addMarker(options, "Baksi", markerId);
                            if (m != null) added++;
                        }

                        Toast.makeText(SokakActivity.this,
                                added + " veteriner yüklendi (" + (radiusM >= 1000 ? (radiusM / 1000f) + "km" : radiusM + "m") + ")",
                                Toast.LENGTH_SHORT).show();

                    } catch (Throwable t) {
                        setProgressVisible(false);
                        Toast.makeText(SokakActivity.this, "Parse hata: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() -> {
                    setProgressVisible(false);
                    String msg = (error != null ? error.getMessage() : "unknown");
                    Toast.makeText(SokakActivity.this, "findNearbyBaksi çağrı hatası: " + msg, Toast.LENGTH_LONG).show();
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
                    String radiusText = (radius >= 1000) ? String.format("%.1fkm", radius / 1000.0) : (radius + "m");
                    Toast.makeText(SokakActivity.this, count + " veteriner (" + radiusText + ")", Toast.LENGTH_SHORT).show();

                    // TODO: Places yerine Firestore (Baksi collection) üzerinden yükleme + adminPath yoksa CF tetikleme
                });
            }

            @Override
            public void onVetsLoadFailed(String error) {
                runOnUiThread(() -> {
                    setProgressVisible(false);
                    Log.e(TAG, "Vets load failed: " + error);
                    Toast.makeText(SokakActivity.this, "Veteriner yükleme hatası: " + error, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onVetMarkerAdded(Marker marker) {
                Log.d(TAG, "Vet marker added: " + (marker != null ? marker.getTitle() : "null"));
            }

            @Override
            public void onSearchRadiusSuggested(double nextRadius, String radiusLabel) {
                runOnUiThread(() -> {
                    // TODO: Kullanıcıya “yarıçapı büyüt” prompt’u
                    Toast.makeText(SokakActivity.this, "Aramayı genişlet: " + radiusLabel, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, "Node created: " + nodeType + " (" + nodeId + ")", Toast.LENGTH_SHORT).show();
        onLockModeChanged(false);
    }

    @Override
    public void onNodeCreationFailed(String error) {
        Toast.makeText(this, "Node create failed: " + error, Toast.LENGTH_LONG).show();
        onLockModeChanged(false);
    }

    // =========================
    // NodeDetailsBottomSheet.Host
    // =========================

    @Override
    public void onRequestMarkerReposition(String markerId) {
        if (harita == null) return;
        harita.startRepositionMode(markerId);
        onLockModeChanged(true);
        Toast.makeText(this, "Marker taşıma modu: " + markerId, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (harita != null) harita.cleanup();
        } catch (Throwable t) {
            Log.w(TAG, "harita.cleanup error: " + t.getMessage());
        }
    }
}
