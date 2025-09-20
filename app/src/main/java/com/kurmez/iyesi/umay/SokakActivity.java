package com.kurmez.iyesi.umay;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Harita;
import com.kurmez.iyesi.kayra.Classes.ui.MarkerDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;

import org.json.JSONArray;
import org.json.JSONObject;

public class SokakActivity extends FragmentActivity implements MarkerDetailsBottomSheet.Host {
    private JSONObject catsJson,dogsJson,criticalJson;
    private JSONArray catsArr,dogsArr,criticalArr,allSoulsAround;
    private FloatingActionButton selectedFab = null; // Track the selected FAB
    public Kurmes kurmes;
    private boolean isMarkerModeActive = false;
    private boolean isMarkerMode = false;
    private float startX, startY;
    private VelocityTracker velocityTracker = null;
    private FloatingActionButton fabDraggable, fabSound;
    private float dX, dY;
    private float mainFabX, mainFabY; // Stores main FAB's position
    private long pressStartTime;
    private boolean isDragging = false;
    private final int LONG_PRESS_THRESHOLD = 2000; // 2 seconds
    private final int DRAG_THRESHOLD = 20; // Minimum movement to consider a drag
    private MiniFabs miniFabs;
    private FloatingActionButton mainFab, beslemeFab, bolgeFab, nakilFab, soundFab;
    private GestureDetector gestureDetector;
    private View touchOverlay;
    private TextView populasyon,kayip,kedi,kopek,kormez;
    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private GeoJsonLayer layerCountry, layerProvince, layerDistrict;
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};
    // Harita işlemlerini devredecek Harita nesnesi
    private Harita harita;
    // SokakActivity içine, class-level’da:
    private boolean isFabOpen,isMarkerActive = false;
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;
    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);
        // 1) Animasyonları yükle
        if (!ensureLoggedInOrGoLogin()) return;
        initializeViews();
        initializeSpinners();
        initializeFABs();

        // 1) Kümeleri hazırla
        catsArr = new org.json.JSONArray();
        dogsArr = new org.json.JSONArray();
        criticalArr = new org.json.JSONArray();
        allSoulsAround = new org.json.JSONArray();

        // Yalnızca harita ile ilgili başlatmayı Harita sınıfına devret
        harita = new Harita(this);
        harita.fetchMarkersNearby(null, 2500, 150);
        harita.initGesture(this);  // YENİ: Harita kendi gesture’ını kurar
        harita.fetchNearbySouls(100, new Harita.SoulsJsonCallback() {
            @Override public void onSuccess(@NonNull String rawJson,
                                            @NonNull org.json.JSONArray souls,
                                            @NonNull String adminPath) {
                allSoulsAround = souls;
                // 2) Tüm Soul kayıtlarını tara ve ilgili kümelere ekle
                for (int i = 0; i < souls.length(); i++) {
                    org.json.JSONObject s = souls.optJSONObject(i);
                    if (s == null) continue;

                    String species = norm(optMulti(s, "species", "type", "kind", "animal"));
                    if (species.isEmpty()) {
                        org.json.JSONObject meta = s.optJSONObject("meta");
                        if (meta != null) species = norm(meta.optString("species", ""));
                    }
                    String status = norm(optMulti(s, "status", "health", "state"));
                    // health içinden de kritik sinyalleri yakala
                    org.json.JSONObject health = s.optJSONObject("health");
                    if (status.isEmpty() && health != null) {
                        status = norm(health.optString("level", health.optString("state", "")));
                    }

                    boolean isCat  = isCat(species);
                    boolean isDog  = isDog(species);
                    boolean isCrit = isCritical(status, health, s);

                    if (isCat)  catsArr.put(s);
                    if (isDog)  dogsArr.put(s);
                    if (isCrit) criticalArr.put(s);
                }

                // 3) Her biri kendi verisini içeren JSON objeleri
                catsJson = new org.json.JSONObject();
                dogsJson = new org.json.JSONObject();
                criticalJson = new org.json.JSONObject();
                try {
                    catsJson.put("adminPath", adminPath).put("count", catsArr.length()).put("souls", catsArr);
                    dogsJson.put("adminPath", adminPath).put("count", dogsArr.length()).put("souls", dogsArr);
                    criticalJson.put("adminPath", adminPath).put("count", criticalArr.length()).put("souls", criticalArr);
                } catch (org.json.JSONException ignore) {}

                // 4) İstediğin şekilde devret/kullan
                String catsJsonStr = catsJson.toString();
                String dogsJsonStr = dogsJson.toString();
                String criticalJsonStr = criticalJson.toString();
                populasyon.setText(allSoulsAround.length());
                kedi.setText(catsArr.length());
                kopek.setText(dogsArr.length());
                kormez.setText(criticalArr.length());
                Log.d("SoulsJSON", "cats="+catsArr.length()+" dogs="+dogsArr.length()+" critical="+criticalArr.length());
                // ör: telemetryUpload(catsJsonStr, dogsJsonStr, criticalJsonStr);
                // ör: showToast/istatistik
                Toast.makeText(SokakActivity.this, "Kedi: "+catsArr.length()+" • Köpek: "+dogsArr.length()+" • Critical: "+criticalArr.length(), Toast.LENGTH_LONG).show();
            }

            @Override public void onError(@NonNull String message) {
                Toast.makeText(SokakActivity.this, message, Toast.LENGTH_SHORT).show();
            }

        });


// Overlay kur
        touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            // Artık jestler map view’da; overlay olay almamalı
            touchOverlay.setOnTouchListener(null);
            touchOverlay.setClickable(false);
            touchOverlay.setVisibility(View.GONE); // istersen tamamen kapat
        }

    }

    private void initializeViews() {
        populasyon = findViewById(R.id.tvPopulationValue);
        kayip = findViewById(R.id.tvMissingValue);
        kedi = findViewById(R.id.spinnerCat);
        kopek = findViewById(R.id.spinnerDog);
        kormez = findViewById(R.id.spinnerBlind);
//        populasyon.setText(allSoulsAround.length());
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }
    private static String optMulti(org.json.JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return "";
    }
    private static boolean isCat(String sp) {
        sp = norm(sp);
        return sp.equals("cat") || sp.contains("kedi") || sp.contains("felis");
    }
    private static boolean isDog(String sp) {
        sp = norm(sp);
        return sp.equals("dog") || sp.contains("köpek") || sp.contains("canis");
    }
    private static boolean isCritical(String status, org.json.JSONObject health, org.json.JSONObject root) {
        String st = norm(status);
        if (st.equals("critical") || st.equals("acil") || st.equals("urgent")) return true;

        if (health != null) {
            int sev = health.optInt("severity", -1);
            if (sev >= 3) return true;
            String lvl = norm(health.optString("level", ""));
            if (lvl.equals("critical")) return true;
        }
        org.json.JSONArray tags = root.optJSONArray("tags");
        if (tags != null) {
            for (int j = 0; j < tags.length(); j++) {
                String t = norm(tags.optString(j, ""));
                if (t.equals("critical") || t.equals("acil") || t.equals("urgent")) return true;
            }
        }
        return false;
    }

    private boolean ensureLoggedInOrGoLogin() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return false;
        }
        return true;
    }
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // Dışarı tıklamada miniFAB menüsünü kapatma (varsa)
        if (miniFabs != null && miniFabs.handleOutsideTouch(event)) return true;

        // <-- ÖNEMLİ: gestureDetector KULLANMA!
        // if (gestureDetector != null) gestureDetector.onTouchEvent(event); // SİL

        return super.dispatchTouchEvent(event);
    }

    private void initializeSpinners() {
        // Satırları saran LinearLayout referansları
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

        // 1. Spinner (Ülke/ADM seviyesi)
        String[] levelOptions = {"ADM0", "OSM"};
        ArrayAdapter<String> adapterCountry = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, levelOptions
        );
        spinners[0].setAdapter(adapterCountry);

        // 2. Spinner (Tür seçimi)
        String[] speciesOptions = {"Kedi", "Köpek", "Kuş", "Vahşi", "İstenmeyen"};
        ArrayAdapter<String> adapterSpecies = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, speciesOptions
        );
        spinners[1].setAdapter(adapterSpecies);

        // 3. Spinner (Kategori seçimi)
        String[] categoryOptions = {"Beslenme", "Yuva", "Su", "AvYemleme", "Hepsi"};
        ArrayAdapter<String> adapterCategory = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, categoryOptions
        );
        spinners[2].setAdapter(adapterCategory);

        // Diğer spinnerlar için örnek boş adapter (ileride doldurulacak)
        spinners[3].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));
        spinners[4].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));

        // Yalnızca ilk satır görünür, diğerleri gizli
        for (int i = 1; i < rows.length; i++) rows[i].setVisibility(View.GONE);

        // Satır açma/kapatma fonksiyonu
        AdapterView.OnItemSelectedListener[] listeners = new AdapterView.OnItemSelectedListener[5];
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            listeners[i] = new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    // Sadece bir sonraki satır açılır, diğerleri gizli kalır
                    for (int j = idx + 1; j < rows.length; j++) {
                        rows[j].setVisibility(View.GONE);
                    }
                    if (idx + 1 < rows.length) {
                        rows[idx + 1].setVisibility(View.VISIBLE);
                    }
                    // Burada: seçime göre layer yükleyebilir veya ileride fonksiyon ekleyebilirsin
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
        beslemeFab = findViewById(R.id.besleme_fab);
        bolgeFab = findViewById(R.id.bolge_fab);
        nakilFab = findViewById(R.id.nakil_fab);
        soundFab = findViewById(R.id.sound_fab);

        // Başlangıçta alt FAB'lar gizli (XML’de zaten visibility="gone")
        beslemeFab.setVisibility(View.GONE);
        bolgeFab.setVisibility(View.GONE);
        nakilFab.setVisibility(View.GONE);
        soundFab.setVisibility(View.GONE);

        int[] miniFabIds = new int[]{R.id.besleme_fab, R.id.bolge_fab, R.id.nakil_fab};
        miniFabs = new MiniFabs(this, mainFab, soundFab, miniFabIds);


        mainFab.setVisibility(View.VISIBLE);
        // 3) Alt-FAB’lara tıklayınca seçili hâle getir + kendi işlevinizi çağırın
        Actions actions = new Actions(miniFabs, this, this /* or getApplicationContext() */ );
        soundFab.setOnClickListener(v -> {
            if (miniFabs.getSelectedFab() != null) {
                actions.performSelectedAction(miniFabs.getSelectedFab());
            } else {
                Toast.makeText(this, "Önce bir miniFAB seçin", Toast.LENGTH_SHORT).show();
            }
        });
        miniFabs.applyDefaultColors();
        miniFabs.setupDraggableFAB(this,miniFabs,mainFab);
        // Wire each miniFAB to call selectFab() + your onFabClick logic
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                // Highlight selection
                miniFabs.selectFab((FloatingActionButton) v);
                // initializeFABs() içinde, her miniFAB tıklamasında:
                // FAB → Mod
                int id = v.getId();
                if (id == R.id.besleme_fab)      harita.setMode(Harita.MapMode.FEEDING);
                else if (id == R.id.bolge_fab)   harita.setMode(Harita.MapMode.NEST);     // örn. “bölge”yi Yuva’ya eşliyorsan değiştir
                else if (id == R.id.nakil_fab)   harita.setMode(Harita.MapMode.TASK);
                // istersen burada actions.onFabClick(...) da çağrılabilir


                 // Overlay kapısı placement modunda açık (dokunuşlar overlay'de yakalanıp haritaya gitmez)
                if (touchOverlay != null) touchOverlay.setClickable(true);

                // Your existing FAB-action logic:
                //actions.onFabClick((FloatingActionButton) v);   /* Burdaki aksyon yapısı daha sonra "Harita.java" dosyasını sadeleştirmede kullanılmalı */
            });
        }
    }    // 3.2. animateFAB() metodu: aç/kapa mantığı
    @Override
    public void onRequestMarkerReposition(@androidx.annotation.NonNull String markerId) {
        if (harita != null) {
            harita.startRepositionMode(markerId);
        }
    }
}
