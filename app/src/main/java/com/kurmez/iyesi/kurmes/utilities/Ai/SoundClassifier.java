package com.kurmez.iyesi.kurmes.utilities.Ai;

import android.content.Context;
import android.media.AudioRecord;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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

public class SoundClassifier {
    private static final String TAG = "SoundClassifier";
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String modelPath;
    private final float probabilityThreshold;
    private final OnClassificationResultListener resultListener;

    private AudioClassifier classifier;
    private TensorAudio tensor;
    private AudioRecord record;
    private Timer timer;
    private TimerTask timerTask;

    public interface OnClassificationResultListener {
        void onResult(String result);
    }

    public SoundClassifier(Context context,
                           String modelPath,
                           float probabilityThreshold,
                           OnClassificationResultListener listener) {
        this.context = context.getApplicationContext();
        this.modelPath = modelPath;
        this.probabilityThreshold = probabilityThreshold;
        this.resultListener = listener;
    }

    public void startRecording() {
        stopRecording();

        new Thread(() -> {
            try {
                classifier = AudioClassifier.createFromFile(context, modelPath);
                tensor = classifier.createInputTensorAudio();
                record = classifier.createAudioRecord();
                record.startRecording();

                timer = new Timer();
                timerTask = new TimerTask() {
                    @Override
                    public void run() {
                        if (record == null) return;

                        Log.d(TAG, "Processing audio input");
                        int numberOfSamples = tensor.load(record);
                        List<Classifications> output = classifier.classify(tensor);

                        // Process classification results
                        List<Category> finalOutput = new ArrayList<>();
                        for (Category category : output.get(0).getCategories()) {
                            if (category.getLabel().equals("Bird") &&
                                    category.getScore() > probabilityThreshold) {
                                finalOutput.add(category);
                            }
                        }

                        Collections.sort(finalOutput, (o1, o2) ->
                                Float.compare(o2.getScore(), o1.getScore()));

                        // Build result string
                        StringBuilder outputStr = new StringBuilder();
                        for (Category category : finalOutput) {
                            outputStr.append(category.getLabel())
                                    .append(": ")
                                    .append(String.format("%.2f", category.getScore()))
                                    .append("\n");
                        }

                        // Send results to main thread
                        String result = finalOutput.isEmpty() ?
                                "Listening..." : outputStr.toString();

                        mainHandler.post(() -> {
                            if (resultListener != null) {
                                resultListener.onResult(result);
                            }
                        });
                    }
                };

                timer.scheduleAtFixedRate(timerTask, 1, 500);
            } catch (IOException e) {
                Log.e(TAG, "Model loading error", e);
                mainHandler.post(() -> {
                    if (resultListener != null) {
                        resultListener.onResult("Error loading model");
                    }
                });
            }
        }).start();
    }

    public void stopRecording() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        if (record != null) {
            record.stop();
            record.release();
            record = null;
        }
    }
}