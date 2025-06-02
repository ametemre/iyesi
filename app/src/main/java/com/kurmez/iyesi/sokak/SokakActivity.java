package com.kurmez.iyesi.sokak;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.utilities.MiniFabs;

public class SokakActivity extends FragmentActivity {

    private MiniFabs miniFabs;
    private FloatingActionButton mainFab, beslemeFab, bolgeFab, nakilFab, soundFab;
    private Spinner spinner1, spinner2, spinner3, spinner4, spinner5;
    private ImageButton clear1, clear2, clear3, clear4, clear5;
    private ImageButton toggle1, toggle2, toggle3, toggle4, toggle5;
    private GeoJsonLayer layerCountry, layerProvince, layerDistrict;
    private final String[] levels = {"ADM5","ADM4","ADM3","ADM2","ADM1","ADM0","OSM"};
    // Harita işlemlerini devredecek Harita nesnesi
    private Harita harita;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);

        initializeSpinners();
        initializeFABs();

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

        int[] miniFabIds = new int[]{R.id.besleme_fab, R.id.bolge_fab, R.id.nakil_fab};
        miniFabs = new MiniFabs(this, mainFab, soundFab, miniFabIds);

        mainFab.setVisibility(View.VISIBLE);

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

        mainFab.setOnClickListener(v -> miniFabs.toggle());

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
        });
    }
}
