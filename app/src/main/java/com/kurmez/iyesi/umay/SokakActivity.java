
package com.kurmez.iyesi.umay;

import static com.kurmez.iyesi.kayra.Classes.data.Soul.parseSouls;
import static com.kurmez.iyesi.kurmes.utilities.PrivateCom.showToast;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

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
public class SokakActivity extends FragmentActivity implements MarkerDetailsBottomSheet.Host {
    private static final String TAG = "SokakActivity";


    // ---------- State ----------
    private JSONArray catsArr, dogsArr, criticalArr, allSoulsAround;

    private MiniFabs miniFabs;
    private FloatingActionButton mainFab, beslemeFab, bolgeFab, nakilFab, soundFab;
    private View touchOverlay;
    private Explore explore = new Explore();
    private TextView populasyon, kayip, kedi, kopek, kormez;

    @SuppressWarnings("FieldCanBeLocal")
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};

    // Animations
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;
    private boolean isFetched = false;
    // Harita delegesi
    private Harita harita;
    private String adminPath;
    private List<Soul> fetchedData;
    // ---------- Lifecycle ----------
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);
        long t0 = SystemClock.elapsedRealtime();
        Log.d(TAG, "onCreate() ⏱️ start");
        if (getIntent() != null) {
            Log.d(TAG, "onCreate() intent=" + getIntent());
            logBundle(getIntent().getExtras(), "onCreate() extras");
        }

        // JSON kümeleri
        catsArr = new JSONArray();
        dogsArr = new JSONArray();
        criticalArr = new JSONArray();
        allSoulsAround = new JSONArray();

        // UI referansları
        initializeViews();   // sayaçları 0’la ve logla
        initializeSpinners();// seçimleri logla
        initializeFABs();    // FAB akışı ve loglar

        // Overlay
        touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            touchOverlay.setOnTouchListener((v, e) -> {
                Log.d(TAG, "overlayTouch action=" + e.getAction() + " x=" + e.getX() + " y=" + e.getY());
                return v.isClickable(); // clickable ise haritaya iletmeyelim
            });
            touchOverlay.setClickable(false);
            Log.d(TAG, "touchOverlay configured. clickable=false, visibility=" + touchOverlay.getVisibility());
        } else {
            Log.w(TAG, "touchOverlay is NULL");
        }

        try {
            // Harita
            harita = new Harita(this);
            harita.initGesture(this);
            harita.fetchMarkersNearby(null, 2500, 150);
            Log.d(TAG, "Harita() created");
            if (!isFetched){
                try {
                    explore = new Explore();
                    adminPath = Harita.extractAdminPath(res);//-----------------------uygularmısın.
                    fetchedData = explore.fetchSouls(adminPath);
                    Log.d("Fetched Data",fetchedData.toString());
                    isFetched = !isFetched;
                } catch (Exception e) {
                    Log.e(TAG + "Error :",e.getMessage());
                }
            }
        } catch (Exception e) {
            Log.e(TAG,"Harita :" +e.getMessage());
        }
    }


    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        Log.v(TAG, "dispatchTouchEvent action=" + event.getAction() + " x=" + event.getX() + " y=" + event.getY());
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
    private void initializeViews() {
        Log.d(TAG, "initializeViews()");
        populasyon = findViewById(R.id.tvPopulationValue);
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
        for (int i = 1; i < rows.length; i++) rows[i].setVisibility(View.GONE);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                int idx = -1;
                for (int i = 0; i < spinners.length; i++) if (spinners[i] == parent) { idx = i; break; }
                Log.d(TAG, "spinner[" + idx + "] selected → " + parent.getItemAtPosition(pos));

                for (int j = idx + 1; j < rows.length; j++) rows[j].setVisibility(View.GONE);
                if (idx + 1 < rows.length) rows[idx + 1].setVisibility(View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
                int idx = -1;
                for (int i = 0; i < spinners.length; i++) if (spinners[i] == parent) { idx = i; break; }
                Log.d(TAG, "spinner[" + idx + "] nothing selected");
                for (int j = idx + 1; j < rows.length; j++) rows[j].setVisibility(View.GONE);
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

        beslemeFab.setVisibility(View.GONE);
        bolgeFab.setVisibility(View.GONE);
        nakilFab.setVisibility(View.GONE);
        soundFab.setVisibility(View.GONE);

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
        mainFab.setVisibility(View.VISIBLE);
    }
    // ---------- Counters ----------
    private void updateCountersOnUi() {
        runOnUiThread(() -> {
            safeSetText(populasyon, String.valueOf(allSoulsAround.length()));
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