package com.kurmez.iyesi.utilities.helper;

import static com.kurmez.iyesi.utilities.helper.TFLiteModelInspector.loadModelFile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sokak.SokakActivity;
import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.MiniFabs;

import org.tensorflow.lite.Interpreter;

import java.io.IOException;
import java.nio.MappedByteBuffer;

public class Actions {
    private static final String TAG = "Actions";
    private FloatingActionButton selectedFab = null; // Track the selected FAB
    private MiniFabs miniFabs;   // saha değişkeni
    Activity host;
    Context context;
    Intent intent;
    public Actions(MiniFabs miniFabs, Activity host, Context context) {
        setMiniFabs(miniFabs);
        this.miniFabs = miniFabs;
        this.host   = host;
        this.context  = context;
    }

    interface Action {
        void execute();
    }
    public Action onFabClick(FloatingActionButton clickedFab) {
        if (selectedFab == clickedFab) {
            // If clicking the same FAB, deselect it and set it back to Teal
            clickedFab.setBackgroundTintList(ColorStateList.valueOf(Color.RED)); // Teal
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
        Action action = null;

        //onFabClickActionKurmes(view,clickedFab);//şimdilik sadece kodu çağırıyor. sonra kodun çağrıldığı yere göre iki fonksyon arasından seçim yapılacak.


        // Execute the function if not null
        if (action != null) {
            action.execute();
        } else {
            Log.w("FAB", "Unknown FAB clicked!");
        }
        return action;
    }
    public void onFabSelected(FloatingActionButton fab) {
        // önceki seçimi temizle
        if (selectedFab != null) {
            miniFabs.selectFab(fab);
            // Your existing FAB-action logic:
            onFabClick(fab);
        }
        // yenisini seç
        selectedFab = fab;

        //miniFabs.highlightFab(fab);
    }
    public void performSelectedAction(FloatingActionButton selectedFab) {
        if (selectedFab == null) return;
        int id = selectedFab.getId();
        if (id == R.id.fab_9) {
            host.startActivity(new Intent(host, SokakActivity.class));
            //host.startActivity(intent);
        } else if (id == R.id.fab_2) {
            //host.startActivity(new Intent(host, DogActivity.class));
        } else if (id == R.id.fab_3) {
        } else if (id == R.id.fab_4) {
        } else if (id == R.id.fab_5) {
        } else if (id == R.id.fab_6) {
        } else if (id == R.id.fab_7) {
        } else if (id == R.id.fab_8) {
        } else if (id == R.id.fab_1) {
        } else if (id == R.id.besleme_fab) {
        } else if (id == R.id.bolge_fab) {
        } else if (id == R.id.nakil_fab) {
            host.finish();
            //host.startActivity(new Intent(host, CatActivity.class));
        }



        // isteğe bağlı: seçimi sıfırla
        //miniFabs.resetFabAppearance(selectedFab);
        selectedFab = null;
    }
    public Action onFabClickActionKurmes(View view , FloatingActionButton clickedFab) {
        Action action = null;
        if (view.getId() == R.id.fab_1) {
            action = this::actionOne;
            //kurmes.currentState = Kurmes.State.KEDI;
        } else if (view.getId() == R.id.fab_2) {
            action = this::actionTwo;
            //kurmes.currentState = Kurmes.State.KOPEK;
        } else if (view.getId() == R.id.fab_3) {
            action = this::actionThree;
            //kurmes.currentState = Kurmes.State.KURT;
        } else if (view.getId() == R.id.fab_4) {
            action = this::actionFour;
        } else if (view.getId() == R.id.fab_5) {
            action = this::actionFive;
        } else if (view.getId() == R.id.fab_6) {
            action = this::actionSix;
        } else if (view.getId() == R.id.fab_7) {
            action = this::actionSeven;
        } else if (view.getId() == R.id.fab_8) {
            action = this::actionEight;
        } else if (view.getId() == R.id.fab_9) {
            action = this::actionNine;
        }
        return action;
    }

    private void actionOne() {
        //kurmes.cameraState(true);
        //CatSpeciesRecognition();
        Log.d("Action", "Action One Executed!");
    }
    private void actionTwo() {
        //kurmes.SetLabelText("denedik");
        //DogSpeciesRecognition();
        Log.d("Action", "Action Two Executed!");
    }
    private void actionThree() {
        //WolfSpeciesRecognition();
        Log.d("Action", "Action Three Executed!");
    }
    private void actionFour() {
        //CrowSpeciesRecognition();
        Log.d("Action", "Action Four Executed!");
    }
    private void actionFive() {
        //HawkSpeciesRecognition();
        Log.d("Action", "Action Five Executed!");
    }
    private void actionSix() {
        //EagleSpeciesRecognition();
        Log.d("Action", "Action Six Executed!");
    }
    private void actionSeven() {
        //KeklikSpeciesRecognition();
        Log.d("Action", "Action Seven Executed!");
    }
    private void actionEight() {
        //PidgeonSpeciesRecognition();
        Log.d("Action", "Action Eight Executed!");
    }
    private void actionNine() {
        /*
        Interpreter tflite;
        try {
            MappedByteBuffer modelBuffer = loadModelFile(kurmes.getAssets(), "yolov8n.tflite");
            tflite = new Interpreter(modelBuffer);
        } catch (IOException e) {
            Log.e(TAG, "Model yüklenirken hata", e);
        }
        try {
            kurmes.aiContent = new Ai(context, "yolov8n.tflite", "yolov8n.tflite");
            kurmes.currentState = Kurmes.State.OBJECT_DETECTION;   // burayı ekleyin
            Log.d("Action", "Content Detection başlatıldı");
        } catch (IOException e) {
            Log.e(TAG, "Failed to load TFLite models", e);
        }
    }*/
    }
    /** Eğer ihtiyaç varsa runtime’da MiniFabs örneğini değiştirmek için setter */
    public void setMiniFabs(MiniFabs miniFabs) {
        if (miniFabs == null) {
            throw new IllegalArgumentException("MiniFabs must not be null");
        }
        this.miniFabs = miniFabs;
    }
}