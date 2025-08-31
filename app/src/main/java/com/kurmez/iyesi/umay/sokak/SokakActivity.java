package com.kurmez.iyesi.umay.sokak;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Point;
import android.os.Bundle;
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
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;

public class SokakActivity extends FragmentActivity implements com.kurmez.iyesi.umay.sokak.ui.MarkerDetailsBottomSheet.Host {

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
    private View touchOverlay;    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
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

        initializeSpinners();
        initializeFABs();
        // Yalnızca harita ile ilgili başlatmayı Harita sınıfına devret
        harita = new Harita(this);
        harita.fetchMarkersNearby(null, 2500, 150);
        harita.initGesture(this);  // YENİ: Harita kendi gesture’ını kurar

// Overlay kur
        touchOverlay = findViewById(R.id.map_overlay);
        if (touchOverlay != null) {
            // Artık jestler map view’da; overlay olay almamalı
            touchOverlay.setOnTouchListener(null);
            touchOverlay.setClickable(false);
            touchOverlay.setVisibility(View.GONE); // istersen tamamen kapat
        }

    }
    private boolean ensureLoggedInOrGoLogin() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
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
/*
        // 1. Spinner (Ülke/ADM seviyesi)
        String[] levelOptions = {"ADM0", "OSM"};
        ArrayAdapter<String> adapterCountry = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, levelOptions
        );
        spinners[0].setAdapter(adapterCountry);
*/
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
                animateFAB();
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
    }
    private void animateFAB() {
        if (isFabOpen) {
            if (touchOverlay != null) touchOverlay.setClickable(false);

            // Menü zaten açıksa: kapatma animasyonları
            mainFab.startAnimation(rotateBackwardAnim);
            beslemeFab.startAnimation(fabCloseAnim);
            bolgeFab.startAnimation(fabCloseAnim);
            nakilFab.startAnimation(fabCloseAnim);
            soundFab.startAnimation(fabCloseAnim);

            // Hepsini tıklanamaz ve görünmez yap
            beslemeFab.setClickable(false);
            bolgeFab.setClickable(false);
            nakilFab.setClickable(false);
            soundFab.setClickable(false);

            // Görünürlüğü GONE yap
            beslemeFab.setVisibility(View.GONE);
            bolgeFab.setVisibility(View.GONE);
            nakilFab.setVisibility(View.GONE);
            soundFab.setVisibility(View.GONE);
                  // Menü kapandı → placement modu kapansın → overlay tünel modunda kalır
            harita.setMode(Harita.MapMode.DEFAULT);
            isFabOpen = false;
        } else {
            // Menü kapalıysa: açma animasyonları
            mainFab.startAnimation(rotateForwardAnim);

            // Önce görünür yap, sonra fab_open animasyonu çalışsın
            beslemeFab.setVisibility(View.VISIBLE);
            bolgeFab.setVisibility(View.VISIBLE);
            nakilFab.setVisibility(View.VISIBLE);
            soundFab.setVisibility(View.VISIBLE);

            beslemeFab.startAnimation(fabOpenAnim);
            bolgeFab.startAnimation(fabOpenAnim);
            nakilFab.startAnimation(fabOpenAnim);
            soundFab.startAnimation(fabOpenAnim);

            // Tıklanabilir olsunlar
            beslemeFab.setClickable(true);
            bolgeFab.setClickable(true);
            nakilFab.setClickable(true);
            soundFab.setClickable(true);

            isFabOpen = true;
            // menü kapanırken (animateFAB() içinde kapatma dalında) en sona ekle:
            //harita.setMode(Harita.MapMode.DEFAULT);
        }
    }    // 3.2. animateFAB() metodu: aç/kapa mantığı
    @Override
    public void onRequestMarkerReposition(@androidx.annotation.NonNull String markerId) {
        if (harita != null) {
            harita.startRepositionMode(markerId);
        }
    }
}
