package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.Log;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.umay.SokakActivity;
import com.kurmez.iyesi.kurmes.utilities.Ai.Ai;
import com.kurmez.iyesi.kurmes.utilities.MiniFabs;
import com.kurmez.iyesi.umay.Welcome;
import com.kurmez.iyesi.umay.sahiplendirme.Founded;

import java.io.IOException;

public class Actions {
    private static final String TAG = "Actions";
    private FloatingActionButton selectedFab = null; // Track the selected FAB
    private Ai ai = null;
    private MiniFabs miniFabs;   // saha değişkeni
    Activity host;
    Context context;
    Intent intent;
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
    interface Action {
        void execute();
    }
    public Actions(MiniFabs miniFabs, Activity host, Context context) {
        setMiniFabs(miniFabs);
        this.miniFabs = miniFabs;
        this.host   = host;
        this.context  = context;
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
    public Ai performSelectedAction(FloatingActionButton selectedFab,Context context) {
        this.context=context;
        /*CompatibilityList compatList = new CompatibilityList();
        boolean isGpuSupported = compatList.isDelegateSupportedOnThisDevice();
        Log.i(TAG, "Cihazda GPU delegate desteği: " + isGpuSupported);
        if (!isGpuSupported) {
            Helpers.showToastSafe(context,"Cihazda GPU delegate desteği yok, CPU ile çalışacak.");
        }
        if (selectedFab == null) {
            Log.w(TAG, "performSelectedAction: selectedFab is null!");
            return null;
        }*/

        int id = selectedFab.getId();
        try {
            if (id == R.id.fab_9) {
                Log.i(TAG, "SokakActivity başlatılıyor.");
                host.startActivity(new Intent(host, SokakActivity.class));
            } else if (id == R.id.fab_2) {
                Log.i(TAG, "DogBreedLabels.tflite yükleniyor...");
                ai = new Ai(host, null, "ml_model/dog/dog/DogBreedLabels.tflite", "ml_model/dog/dog/DogBreedLabels.txt");
                Log.i(TAG, "DogBreedLabels.tflite başarıyla yüklendi.");
            } else if (id == R.id.fab_3) {
                Log.i(TAG, "animal_ml_model.tflite yükleniyor...");
                ai = new Ai(host, null, "ml_model/animal_ml_model.tflite", "ml_model/animal_ml_model_labels.txt");
                Log.i(TAG, "animal_ml_model.tflite başarıyla yüklendi.");
            } else if (id == R.id.fab_4) {
                Log.i(TAG, "yamnet_classification.tflite yükleniyor...");
                ai = new Ai(host, null, "ml_model/dump/yamnet_classification.tflite", "ml_model/dump/labelmap.txt");
                Log.i(TAG, "yamnet_classification.tflite başarıyla yüklendi.");
            } else if (id == R.id.fab_5) {
                Log.i(TAG, "mobilenet_v2.tflite yükleniyor...");
                ai = new Ai(host, null, "ml_model/dump/mobilenet_v2.tflite", "ml_model/dump/labels.txt");
                Log.i(TAG, "mobilenet_v2.tflite başarıyla yüklendi.");
            } else if (id == R.id.fab_6) {
                Log.i(TAG, "yolov8n.tflite yükleniyor...");
                ai = new Ai(host, null, "yolov8n.tflite", "coco_labels.txt");
                Log.i(TAG, "yolov8n.tflite başarıyla yüklendi.");
            } else if (id == R.id.ülgen_fab) {host.startActivity(new Intent(host, Founded.class));
            } else if (id == R.id.acil_fab) {host.startActivity(new Intent(host, Kurmes.class));
            } else if (id == R.id.coban_fab) {host.startActivity(new Intent(host, Welcome.class));}
            // Diğer FAB id'leri için de benzer şekilde devam ettir...
            // threading.availableCPU() vs. loglamak istersen ekle.
        } catch (IOException e) {
            Log.e(TAG, "Model yükleme hatası: " + e.getMessage(), e);
            ai = null; // Hatalı ise null'a çek.
        }
        if (ai != null) {
            Log.i(TAG, "Model " + ai.getLastUsedDelegate() + " delegate ile yüklendi.");
            try {
                Log.i(TAG, "Model Input Tensor Info: " + ai.getInputShapeInfo(ai.getVideoInterpreter()));
                Log.i(TAG, "Model Output Tensor Info: " + ai.getOutputShapeInfo(ai.getVideoInterpreter()));
            } catch (Exception ex) {
                Log.e(TAG, "Tensor info alınamadı: " + ex.getMessage());
            }
        } else {
            Log.w(TAG, "Ai nesnesi null, model yüklenemedi.");
        }
        return ai;
    }
    public void setMiniFabs(MiniFabs miniFabs) {
        if (miniFabs == null) {
            throw new IllegalArgumentException("MiniFabs must not be null");
        }
        this.miniFabs = miniFabs;
    }
}