package com.kurmez.iyesi.sokak;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.MiniFabs;

public class SokakActivity extends FragmentActivity {
    private FloatingActionButton selectedFab = null; // Track the selected FAB

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
    private FrameLayout rootLayout;
    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private GeoJsonLayer layerCountry, layerProvince, layerDistrict;
    private final String[] levels = {"ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"};
    // Harita işlemlerini devredecek Harita nesnesi
    private Harita harita;
    // SokakActivity içine, class-level’da:
    private boolean isFabOpen = false;
    private Animation fabOpenAnim, fabCloseAnim, rotateForwardAnim, rotateBackwardAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);
        // 1) Animasyonları yükle
        rootLayout       = findViewById(android.R.id.content);
        fabOpenAnim = AnimationUtils.loadAnimation(this, R.anim.fab_open);
        fabCloseAnim = AnimationUtils.loadAnimation(this, R.anim.fab_close);
        rotateForwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_forward);
        rotateBackwardAnim = AnimationUtils.loadAnimation(this, R.anim.rotate_backward);
        initializeSpinners();
        initializeFABs();
        View.OnTouchListener outsideListener = (v, ev) -> {
            return miniFabs.handleOutsideTouch(ev);
        };
        // Yalnızca harita ile ilgili başlatmayı Harita sınıfına devret
        harita = new Harita(this);
    }

    /**
     * İkinci, üçüncü, dördüncü ve beşinci satırdaki spinner/ikonlar için setup.
     * Birinci spinner artık Harita içinde initSpinner() ile ayarlanıyor.
     */
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
    }


    private void initializeFABs() {

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
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                // Renkleri güncelle (kırmızı-beyaz)
                miniFabs.selectFab((FloatingActionButton) v);

                // İşleminizi burada yapın
                int id = v.getId();
                if (id == R.id.besleme_fab) {
                    // Örneğin: harita.enableBeslemeMode();
                } else if (id == R.id.bolge_fab) {
                    // Bölge işlemi
                } else if (id == R.id.nakil_fab) {
                    // Nakil işlemi
                }
                // İsterseniz miniFAB menüsünü kapatmak için:
                miniFabs.collapse();
            });
        }
        // Draggable MainFab
        mainFab.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private int lastAction;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = view.getX() - event.getRawX();
                        dY = view.getY() - event.getRawY();
                        lastAction = MotionEvent.ACTION_DOWN;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        view.setX(event.getRawX() + dX);
                        view.setY(event.getRawY() + dY);
                        lastAction = MotionEvent.ACTION_MOVE;
                        return true;
                    case MotionEvent.ACTION_UP:
                        return lastAction == MotionEvent.ACTION_MOVE;
                    default:
                        return false;
                }
            }
        });

        mainFab.setOnClickListener(v -> animateFAB());
        // 5) Alt-FAB’lara (örneğin) tıklanınca menünün kapanıp işlemi tetikleyecek kodu ekleyin
        beslemeFab.setOnClickListener(v -> {
            animateFAB();
            // … burada “besleme” işlemini başlatın …
        });
        bolgeFab.setOnClickListener(v -> {
            animateFAB();
            // … burada “bölge” işlemini başlatın …
        });
        nakilFab.setOnClickListener(v -> {
            animateFAB();
            // … burada “nakil” işlemini başlatın …
        });

        soundFab.setOnClickListener(v -> {
            animateFAB();
            // … burada “ses” işlemini başlatın …
        });
        miniFabs.applyDefaultColors();
        setupDraggableFAB(miniFabs);
        // Wire each miniFAB to call selectFab() + your onFabClick logic
        for (FloatingActionButton fab : miniFabs.getFabs()) {
            fab.setOnClickListener(v -> {
                // Highlight selection
                miniFabs.selectFab((FloatingActionButton) v);
                // Your existing FAB-action logic:
                onFabClick(v, (FloatingActionButton) v);
            });
        }
/*
        beslemeFab.setOnClickListener(v -> {
            miniFabs.collapse();
            // Harita katmanından bağımsız olarak besleme modu başlatılır
            harita.enableBeslemeMode();
        });

        bolgeFab.setOnClickListener(v -> {
            miniFabs.collapse();
            // Bölge seçme işlevi (yalnızca uygulamaya ait veri üretimi)
            // (Buraya gerektiğinde kendi mantığınızı ekleyebilirsiniz)
        });

        nakilFab.setOnClickListener(v -> {
            miniFabs.collapse();
            // Nakil işlevi (yalnızca uygulamaya ait veri üretimi)
            // (Buraya gerektiğinde kendi mantığınızı ekleyebilirsiniz)
        });*/
    }

    // 3.2. animateFAB() metodu: aç/kapa mantığı
    private void animateFAB() {
        if (isFabOpen) {
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
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupDraggableFAB(MiniFabs miniFabs) {
        mainFab.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // Başlangıç pozisyonlarını ve zaman damgasını ayarla
                    dX = v.getX() - event.getRawX();
                    dY = v.getY() - event.getRawY();
                    mainFabX = v.getX();
                    mainFabY = v.getY();
                    isDragging = false;
                    pressStartTime = System.currentTimeMillis();
                    // VelocityTracker hazırla
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                    velocityTracker.addMovement(event);
                    //SetLabelText("Ready !");
                    return true;

                case MotionEvent.ACTION_MOVE:
                    // Yeni pozisyonu hesapla
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;
                    // Sürükleme eşiğini kontrol et
                    if (Math.abs(newX - v.getX()) > DRAG_THRESHOLD ||
                            Math.abs(newY - v.getY()) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    // Hız takibi
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    // FAB ve miniFAB’ları taşı
                    v.setX(newX);
                    v.setY(newY);
                    miniFabs.move(newX - mainFabX, newY - mainFabY);
                    mainFabX = newX;
                    mainFabY = newY;
                    return true;

                case MotionEvent.ACTION_UP:
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                    if (!isDragging) {
                        long pressDuration = System.currentTimeMillis() - pressStartTime;
                        if (pressDuration < LONG_PRESS_THRESHOLD) {
                            // Kısa tıklama: miniFAB menüsünü toggle et
                            miniFabs.toggle();
                        } else {
                            // Uzun basış
                            //handleLongClick();
                        }
                    } else {
                        // Sürükleme sonrası momentumlu animasyon
                        float vx = velocityTracker.getXVelocity();
                        float vy = velocityTracker.getYVelocity();
                        miniFabs.animateMomentumGravity(v, vx, vy,rootLayout);
                    }
                    return true;

                default:
                    return false;
            }
        });
    }
    public SokakActivity.Action onFabClick(View view, FloatingActionButton clickedFab) {
        if (selectedFab == clickedFab) {
            // If clicking the same FAB, deselect it and set it back to Teal
            clickedFab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008080"))); // Teal
            miniFabs.resetIconColor(clickedFab); // Restore icon color
            selectedFab = null;
        } else {
            // Deselect previous FAB if there was one
            if (selectedFab != null) {
                selectedFab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#008080"))); // Teal
                miniFabs.resetIconColor(selectedFab);
            }

            // Select new FAB and set to Red
            clickedFab.setBackgroundTintList(ColorStateList.valueOf(Color.RED)); // Red
            miniFabs.applyWhiteColorFilter(clickedFab); // Change icon to White
            selectedFab = clickedFab;
        }
        clickedFab.invalidate(); // Force UI refresh
        clickedFab.requestLayout(); // Ensure layout updates
        Log.d("FAB", "onFabClick called");
        SokakActivity.Action action = null;
        // Execute the function if not null
        if (action != null) {
            action.execute();
        } else {
            Log.w("FAB", "Unknown FAB clicked!");
        }
        return action;
    }

    interface Action {
        void execute();
    }
}
