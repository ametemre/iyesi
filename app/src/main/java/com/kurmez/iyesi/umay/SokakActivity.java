
package com.kurmez.iyesi.umay;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static com.kurmez.iyesi.kayra.Classes.data.Soul.parseSouls;
import static com.kurmez.iyesi.kurmes.utilities.Helpers.normalizeAdminPathForServer;
import static com.kurmez.iyesi.kurmes.utilities.PrivateCom.showToast;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Harita;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kayra.Classes.ui.MarkerDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.social.content.Explore;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;
import com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

import okhttp3.Address;

/**
 * SokakActivity – Aşırı detaylı loglama sürümü
 * Her önemli giriş/çıkış, UI etkileşimi, harita/JSON akışı ve lifecycle adımı loglanır.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class SokakActivity extends FragmentActivity implements MarkerDetailsBottomSheet.Host {
    private static final String TAG = "SokakActivity";


    // ---------- State ----------
    private JSONArray catsArr, dogsArr, criticalArr, allSoulsAround;

    private MiniFabs miniFabs;
    private FloatingActionButton mainFab, beslemeFab, bolgeFab, nakilFab, soundFab;
    private View touchOverlay;
    private TextView populasyon, kayip, kedi, kopek, kormez;

    @SuppressWarnings("FieldCanBeLocal")
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};
    private ActivityResultLauncher<String[]> permissionLauncher;

    // Animations
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;
    private boolean isFetched = false;
    // Harita delegesi
    private Harita harita;
    private String adminPath;
    private JSONArray fetchedData;
    private LinearLayout panel;

    // ---------- Lifecycle ----------
    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);

        long t0 = SystemClock.elapsedRealtime();
        Log.d(TAG, "onCreate() ⏱️ start");
        if (getIntent() != null) {
            Log.d(TAG, "onCreate() intent=" + getIntent());
            logBundle(getIntent().getExtras(), "onCreate() extras");
        }

        // UI referansları
        initializeViews();   // sayaçları 0’la ve logla
        initializeSpinners();// seçimleri logla
        initializeFABs();    // FAB akışı ve loglar

        // Overlay
        touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            touchOverlay.setOnTouchListener((v, e) -> {
                //Log.d(TAG, "overlayTouch action=" + e.getAction() + " x=" + e.getX() + " y=" + e.getY());
                return v.isClickable(); // clickable ise haritaya iletmeyelim
            });
            touchOverlay.setClickable(false);
            Log.d(TAG, "touchOverlay configured. clickable=false, visibility=" + touchOverlay.getVisibility());
        } else {
            Log.w(TAG, "touchOverlay is NULL");
        }


        // 2) MapFragment’i ANA THREAD’de al (executor kullanma)
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            initializeMap(mapFragment);
        } else {
            Log.e(TAG, "SupportMapFragment is NULL (R.id.map?)");
        }
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        //Log.v(TAG, "dispatchTouchEvent action=" + event.getAction() + " x=" + event.getX() + " y=" + event.getY());
        panel.setVisibility(GONE);
        // MiniFAB menüsünü kapatmak için dış dokunuşu verir
        if (miniFabs != null && miniFabs.handleOutsideTouch(event)) {
            Log.v(TAG, "miniFabs consumed touch");
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override public void onRequestMarkerReposition(@NonNull String markerId) {
        Log.d(TAG, "onRequestMarkerReposition id=" + markerId);
        if (harita != null) {
            try {
                harita.startRepositionMode(markerId);
            } catch (Throwable t) {
                Log.e(TAG, "startRepositionMode error", t);
            }
        }
    }
    // ---------- UI init ----------
    private void initializeMap(SupportMapFragment mapFragment) {

        mapFragment.getMapAsync(googleMap -> {
            com.google.android.gms.maps.model.LatLng c2 = googleMap.getCameraPosition().target;
            // 1) Harita örneğini HEMEN oluştur
            harita = new Harita(c2.latitude,c2.longitude,this);
            harita.setPermissionLauncher(permissionLauncher);
            // 3) Önce haritayı ilişkilendir
            harita.attachMap(googleMap);
            harita.setHasAppCheckToken(true);
            harita.setHasAuthIdToken(true);
            // 4) Jestler ve yakın marker’lar (Map hazır)
            harita.initGesture(this);
            harita.fetchMarkersNearby(/*center*/ null, /*radiusM*/ 2500, /*limit*/ 150);

            // 5) adminPath senkron türetilecekse (konum hazırsa):
            //String adminPath = harita.getAdminPathSync(this, /*optional*/ null);
            // küçük bir retry: 600ms sonra tekrar dene
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                // JSON kümeleri

                Harita.ensureAdminPathAsync(c2.latitude, c2.longitude, ap2 -> {
                    if (ap2 == null) {
                        Log.w(TAG, "adminPath async=null (geocoder yanıtsız). Akış sürüyor.");
                        return;
                    }
                    adminPath = normalizeAdminPathForServer(ap2).toString();

                    Explore explore = new Explore();
                    java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                        try {
                            //allSoulsAround = (JSONArray) explore.fetchSouls(adminPath);
                            explore.fetchSouls(adminPath, new Explore.SoulsJsonCallback() {
                                @Override
                                public void onSuccess(@NonNull JSONArray items, @NonNull List<Soul> parsed, @NonNull JSONObject raw) {
                                    Log.d(TAG, "items.length=" + items.length());
                                    try {
                                        catsArr = new JSONArray();
                                        dogsArr = new JSONArray();
                                        criticalArr = new JSONArray();
                                        allSoulsAround = new JSONArray();
                                        JsonHelper.splitSoulsIntoArrays(items, catsArr, dogsArr, criticalArr);
                                        Log.d("ExploreActivity","All=" + items.length() + "cats=" + catsArr.length() + " dogs=" + dogsArr.length() + " critical=" + criticalArr.length());
                                        allSoulsAround = items;
                                        panel.setVisibility(VISIBLE);
                                        updateCountersOnUi();
                                    } catch (JSONException e) {
                                        Log.e(TAG + "Hata :", e.getMessage());
                                    }
                                }

                                @Override
                                public void onError(@NonNull Throwable t) {

                                }
                            });
                            runOnUiThread(() -> {
                                Log.d(TAG, "Fetched Data: " + adminPath.toString() + String.valueOf(allSoulsAround));
                                isFetched = true;
                                // TODO: UI güncelle (marker ekle vb.)
                            });
                        } catch (Exception e) {
                            Log.e(TAG, "fetchSouls error: " + e.getMessage(), e);
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Sunucu hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show()
                            );
                        }
                    });
                }, this);
                if (adminPath == null) {
                    Log.w(TAG, "adminPath sync=null (konum/zoom hazır değil ya da geocoder yanıt vermedi)");
                    return;
                }
            },600);
        });
    }
    private void initializeViews() {
        Log.d(TAG, "initializeViews()");
        panel      = findViewById(R.id.shelterInfoPanel);
        populasyon = findViewById(R.id.tvPopulationValues);
        kayip      = findViewById(R.id.tvMissingValue);
        kedi       = findViewById(R.id.spinnerCat);
        kopek      = findViewById(R.id.spinnerDog);
        kormez     = findViewById(R.id.spinnerBlind);
    }
    private void initializeSpinners() {
        Log.d(TAG, "initializeSpinners()");
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

        // 1. Seviye
        String[] levelOptions = {"ADM0", "OSM"};
        ArrayAdapter<String> adapterCountry =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, levelOptions);
        spinners[0].setAdapter(adapterCountry);

        // 2. Tür
        String[] speciesOptions = {"Kedi", "Köpek", "Kuş", "Vahşi", "İstenmeyen"};
        ArrayAdapter<String> adapterSpecies =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, speciesOptions);
        spinners[1].setAdapter(adapterSpecies);

        // 3. Kategori
        String[] categoryOptions = {"Beslenme", "Yuva", "Su", "AvYemleme", "Hepsi"};
        ArrayAdapter<String> adapterCategory =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, categoryOptions);
        spinners[2].setAdapter(adapterCategory);

        spinners[3].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));
        spinners[4].setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{}));

        // Görünürlük
        for (int i = 1; i < rows.length; i++) rows[i].setVisibility(GONE);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int idx = -1;
                for (int i = 0; i < spinners.length; i++) if (spinners[i] == parent) { idx = i; break; }
                Log.d(TAG, "spinner[" + idx + "] selected → " + parent.getItemAtPosition(pos));

                for (int j = idx + 1; j < rows.length; j++) rows[j].setVisibility(GONE);
                if (idx + 1 < rows.length) rows[idx + 1].setVisibility(VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                int idx = -1;
                for (int i = 0; i < spinners.length; i++) if (spinners[i] == parent) { idx = i; break; }
                Log.d(TAG, "spinner[" + idx + "] nothing selected");
                for (int j = idx + 1; j < rows.length; j++) rows[j].setVisibility(GONE);
            }
        };
        for (Spinner s : spinners) s.setOnItemSelectedListener(listener);
    }
    private void initializeFABs() {
        Log.d(TAG, "initializeFABs()");
        fabOpenAnim       = AnimationUtils.loadAnimation(this, R.anim.fab_open);
        fabCloseAnim      = AnimationUtils.loadAnimation(this, R.anim.fab_close);
        rotateForwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_forward);
        rotateBackwardAnim= AnimationUtils.loadAnimation(this, R.anim.rotate_backward);

        mainFab   = findViewById(R.id.main_fab);
        beslemeFab= findViewById(R.id.besleme_fab);
        bolgeFab  = findViewById(R.id.bolge_fab);
        nakilFab  = findViewById(R.id.nakil_fab);
        soundFab  = findViewById(R.id.sound_fab);

        beslemeFab.setVisibility(GONE);
        bolgeFab.setVisibility(GONE);
        nakilFab.setVisibility(GONE);
        soundFab.setVisibility(GONE);

        int[] miniFabIds = new int[]{R.id.besleme_fab, R.id.bolge_fab, R.id.nakil_fab};
        miniFabs = new MiniFabs(this, mainFab, soundFab, miniFabIds);
        miniFabs.applyDefaultColors();
        miniFabs.setupDraggableFAB(this, miniFabs, mainFab);

        // Action hub
        Actions actions = new Actions(miniFabs, this, this);

        // Main sound FAB
        soundFab.setOnClickListener(v -> {
            Log.d(TAG, "soundFab clicked. selectedFab=" + (miniFabs.getSelectedFab() == null ? "null" : miniFabs.getSelectedFab().getId()));
            if (miniFabs.getSelectedFab() != null) {
                actions.performSelectedAction(miniFabs.getSelectedFab());
            } else {
                Toast.makeText(this, "Önce bir miniFAB seçin", Toast.LENGTH_SHORT).show();
            }
        });

        // Each mini FAB
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                Log.d(TAG, "miniFAB clicked id=" + v.getId());
                miniFabs.selectFab((FloatingActionButton) v);
                int id = v.getId();
                if (id == R.id.besleme_fab) {
                    if (harita != null) harita.setMode(Harita.MapMode.FEEDING);
                    Log.d(TAG, "Harita mode=FEEDING");
                } else if (id == R.id.bolge_fab) {
                    if (harita != null) harita.setMode(Harita.MapMode.NEST);
                    Log.d(TAG, "Harita mode=NEST");
                } else if (id == R.id.nakil_fab) {
                    if (harita != null) harita.setMode(Harita.MapMode.TASK);
                    Log.d(TAG, "Harita mode=TASK");
                }
                if (touchOverlay != null) touchOverlay.setClickable(true);
            });
        }
        mainFab.setVisibility(VISIBLE);
    }
    // ---------- Counters ----------
    private void updateCountersOnUi() {
        runOnUiThread(() -> {
            safeSetText(populasyon,  String.valueOf(allSoulsAround.length()));
            safeSetText(kedi,        String.valueOf(catsArr.length()));
            safeSetText(kopek,       String.valueOf(dogsArr.length()));
            safeSetText(kormez,      String.valueOf(criticalArr.length()));
            Log.d(TAG, "UI updated → pop=" + allSoulsAround.length()
                    + " cat=" + catsArr.length()
                    + " dog=" + dogsArr.length()
                    + " critical=" + criticalArr.length());
        });
    }
    private void safeSetText(@Nullable TextView tv, @NonNull String text) {
        if (tv == null) {
            Log.w(TAG, "safeSetText on NULL TextView text=" + text);
            return;
        }
        tv.setText(text);
    }
    private static void logBundle(@Nullable Bundle b, String label) {
        if (b == null) {
            Log.d(TAG, label + ": <no extras>");
            return;
        }
        StringBuilder sb = new StringBuilder(label).append(": ");
        for (String k : b.keySet()) {
            Object v = b.get(k);
            sb.append(k).append("=").append(v).append(" | ");
        }
        Log.d(TAG, sb.toString());
    }
}