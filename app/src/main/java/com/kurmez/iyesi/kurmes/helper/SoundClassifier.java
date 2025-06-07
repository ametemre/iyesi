package com.kurmez.iyesi.kurmes.helper;

import android.content.Context;
import android.media.AudioRecord;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.kurmez.iyesi.kurmes.Kurmes;

import org.tensorflow.lite.support.audio.TensorAudio;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.task.audio.classifier.AudioClassifier;
import org.tensorflow.lite.task.audio.classifier.Classifications;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class SoundClassifier extends Kurmes {
    private Context context;  // 📌 Context değişkeni eklei
    private Kurmes kurmesActivity;
    protected String modelPath = "ml_model/dump/my_birds_model.tflite";
    float probabilityThreshold = 0.3f;
    AudioClassifier classifier;
    private TensorAudio tensor;
    private AudioRecord record;
    private Timer timer; // Declare a Timer object
    private TimerTask timerTask; // Declare the TimerTask
    private OnClassificationResultListener resultListener;

    private TextView labelText;  // Add this member variable

    public SoundClassifier(Context context, TextView labelText, OnClassificationResultListener listener,Kurmes activity) {
        this.context = context;
        this.kurmesActivity = activity;
        this.labelText = labelText;  // Assign the TextView reference
        this.resultListener = listener;
    }
    public void updateLabel(String text) {
        if (kurmesActivity != null) {
            kurmesActivity.SetLabelText(text);
        }
    }

    public interface OnClassificationResultListener {
        void onResult(String result);
    }

    public void onStartRecording(View view) {
        kurmesActivity.SetLabelText("Recording !");
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        Log.d("AvailableProcessors", "Number of available threads: " + availableProcessors);
        //        Toast.makeText(this, "AvailableProcessors" + "Number of available threads: " + availableProcessors, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            onStopRecording(view);
            try {
                //classifier = AudioClassifier.createFromFile(this, modelPath);
                classifier = AudioClassifier.createFromFile(context, modelPath);  // 📌 context kullan
                tensor = classifier.createInputTensorAudio();
                record = classifier.createAudioRecord();
                record.startRecording();
                timer = new Timer();
                TimerTask timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        if (record == null) return; // Prevent crash if record is null
                        if (SoundClassifier.class.getSimpleName() == null){
                            //stop Camera, stop audio, refresh Kurmes
                        }
                        Log.d(SoundClassifier.class.getSimpleName(), "timer task triggered");
                        int numberOfSamples = tensor.load(record);
                        List<Classifications> output = classifier.classify(tensor);

                        List<Category> finalOutput = new ArrayList<>();
                        for (Category category : output.get(0).getCategories()) {
                            if (category.getLabel().equals("Bird") && category.getScore() > probabilityThreshold) {
                                finalOutput.add(category);
                            }
                        }
                        List<Category> finalOutput2 = new ArrayList<>();
                        for (Category category : output.get(1).getCategories()) {
                            if (category.getScore() > probabilityThreshold) {
                                finalOutput2.add(category);
                            }
                        }

                        Collections.sort(finalOutput, (o1, o2) -> Float.compare(o2.getScore(), o1.getScore()));

                        StringBuilder outputStr = new StringBuilder();
                        for (Category category : finalOutput) {
                            outputStr.append(category.getLabel())
                                    .append(category.getScore())
                                    .append(category.getDisplayName())
                                    .append(category.getIndex());
/*                            outputStr.append(category.getLabel()).append(":* ").append(category.getScore()).append("\n");
                            outputStr.append(category.getLabel()).append(":*** ").append(category.getScore()).append("\n");
                            outputStr.append(category.getLabel())
                                    .append(":** ").append(category.getScore())
                                    .append(", ").append(category.getDisplayName()).append("\n");*/
                        }

                        runOnUiThread(() -> {
                            if (finalOutput.isEmpty()) {
                                kurmesActivity.SetLabelText("Listening...");
                            } else {
                                for (Category category : finalOutput) {
                                    outputStr.append(category.getLabel()).append(": ")
                                            .append(category.getScore()).append("\n");
                                }
                                kurmesActivity.SetLabelText(outputStr.toString());
                            }
                            if (finalOutput2.isEmpty()) {
                                kurmesActivity.SetLabelText("Dinliyor...");
                                //labelText.setText("Tanımlama yapılamadı.");
                            } else {
                                for (Category category : finalOutput2) {
                                    outputStr.append(category.getLabel()).append(": ").append(category.getScore()).append("\n");
                                    kurmesActivity.updateDetectedSounds(finalOutput2);
                                }
                                kurmesActivity.SetLabelText(outputStr.toString());
                                //labelText.setText(outputStr.toString());  // Use the TextView reference
                            }
                        });
                    }
                };
                new Timer().scheduleAtFixedRate(timerTask, 1, 500);
            } catch (IOException e) {
                Log.e("SoundClassifier", "Model yükleme hatası", e);
            }
        }).start();
    }
    public void onStopRecording(View view) {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null; // Reset the task
        }
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null; // Reset the timer
        }
        if (record != null) {
            record.stop();
            record.release();
            record = null;
        }
        kurmesActivity.SetLabelText("Stopped Recording");
    }

    public List<String> printModelDetails(int get) {
        List<String> result = new ArrayList<>();
        try {
            classifier = AudioClassifier.createFromFile(context, modelPath);  // 📌 context kullan
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            // Modelden özellikleri almak için ilgili yöntemleri kullanabilirsiniz
            List<Classifications> labels = classifier.classify(tensor); // Modelin label'ları
            int numberOfClasses = labels.size(); // Sınıf sayısı

            Log.d("ModelDetails", "Sınıf Sayısı: " + numberOfClasses);
            result.add(numberOfClasses + "&");
            Log.d("ModelDetails", "Label'lar: " + labels.toString());
            for (Category category : labels.get(get).getCategories()) {
                result.add(category.toString());
                if (category.getLabel().equals("Bird") && category.getScore() > probabilityThreshold) {
                    //result.add(category);
                }
            }
            // Gerekirse, label'ların detaylı incelemesi
            for (Classifications label : labels) {
                Log.d("ModelDetails", "Label: " + label.toString());
                result.add(label.toString());
            }
        } catch (Exception e) {
            Log.e("ModelDetails", "Model özelliklerini alma hatası", e);
            //return "null";
        }
        //return "null";
        return result;
    }
}