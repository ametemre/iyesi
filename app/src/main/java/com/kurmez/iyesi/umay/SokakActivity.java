package com.kurmez.iyesi.umay;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
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

import androidx.annotation.RequiresPermission;
import androidx.fragment.app.FragmentActivity;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.kayra.Classes.ui.NodeDetailsBottomSheet;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.kurmes.utilities.helper.Actions;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;

public class SokakActivity extends FragmentActivity implements NodeDetailsBottomSheet.Host, Harita.LockModeListener, Harita.NodeCreationListener {
    private FloatingActionButton selectedFab = null;
    public Kurmes kurmes;

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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sokak);

        if (!ensureLoggedInOrGoLogin()) return;

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
    }

    private void initializeHarita() {
        harita = new Harita(this);
        harita.setLockModeListener(this);
        harita.setNodeCreationListener(this);
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
                if (harita != null && harita.isReady()) {
                    harita.fetchNodesNearby(null, 5000, 200);
                    Log.d("SokakActivity", "Marker'lar yükleniyor...");
                    harita.setMyLocationIconEnabled(true);
                } else {
                    handler.postDelayed(this, 1000);
                    Log.d("SokakActivity", "Harita hazır değil, tekrar deneniyor...");
                }
            }
        };
        handler.postDelayed(checkMapReady, 1000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d("SokakActivity", "onResume - Harita durumu: " + (harita != null ? "Mevcut" : "Null"));
        if (harita != null) {
            Log.d("SokakActivity", "Harita hazır: " + harita.isReady());
            Log.d("SokakActivity", "Konum izni: " + Harita.hasLocationPermission(this));
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
        if (harita != null) {
            harita.cleanup();
        }
    }
}