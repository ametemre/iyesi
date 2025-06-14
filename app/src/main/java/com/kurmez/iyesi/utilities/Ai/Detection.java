package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.core.util.Function;

import com.kurmez.iyesi.kurmes.Kurmes;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import org.tensorflow.lite.Interpreter;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Detection {
    // ————————————————
    // ❶ Detection sonucu alanları
    // ————————————————
    public final String label;
    public final int classId;
    public final float x1, y1, x2, y2, score;
    // DetectionActivity.java
    private static final float SCORE_THRESHOLD = 0.9f;  // 50% üzeri kesin kabul
    float scoreThreshold = 0.25f;
    public float getScoreThreshold() {
        return scoreThreshold;
    }
    private List<String> labels;
    // ————————————————————————
    // ❷ Inference ve yardımcı alanlar
    // ————————————————————————
    private final Context context;
    private final Interpreter interpreter;
    private Ai ai;
    private Bitmap frame;
    private TFLiteInputMapper mapper;
    private TFLiteInputPreprocessor preProcess;
    private ByteBuffer inputData;
    private Bitmap bitmap;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private int frameCount = 0;
    private static final int SKIP_FRAMES = 5;
    private static final int DETECTION_INPUT_SIZE = 640;

    private final List<float[][][]> videoBuffer = new ArrayList<>();
    private final List<float[]> soundBuffer = new ArrayList<>();
    private static final int VIDEO_THRESHOLD = 3;
    private static final int SOUND_THRESHOLD = 3;
    private Bitmap reusableFrameBitmap = null;
    private Bitmap reusableScaledBitmap = null;
    private ByteBuffer reusableInputBuffer = null;
    private final Object lock = new Object(); // thread-safe olması için
    private Kurmes.State currentState;
    private Executor executor;
    private static final String TAG = "Detection";

    private final List<Bitmap> photoList = new ArrayList<>();
    private static final int REQUEST_STORAGE_PERMISSION = 1001;

    public Detection(Ai ai, Interpreter interpreter, Context context,
                     int classId, float score, float x1, float y1, float x2, float y2, String label) {
        this.ai = ai;
        this.interpreter = interpreter;
        this.context = context;
        this.classId = classId;
        this.score = score;
        this.x1 = x1; this.y1 = y1;
        this.x2 = x2; this.y2 = y2;
        this.label = label;
        // initiateDetection(ai, frame); // GEREKSİZ!
    }


    private void initiateDetection(Ai ai,Bitmap frame) {
        this.mapper = new TFLiteInputMapper(frame.getWidth(),frame.getHeight(),ai.getInputWidth(),ai.getInputHeight());
        this.preProcess =  new TFLiteInputPreprocessor(mapper);
    }


    public String getLabelName(int classId) {
        String[] labels = {"person","bicycle","car", /* … */};
        if (classId >= 0 && classId < labels.length) return labels[classId];
        return "cls" + classId;
    }

    public List<String> getLabelsAboveThreshold(List<Detection> detections, float threshold) {
        List<String> labels = new ArrayList<>();
        for (Detection d : detections) {
            if (d.score > threshold) {
                labels.add(d.label);
            }
        }
        return labels;
    }
    public List<Detection> parseDetections(float[][][] output) {
        /**
         * Modelin float[][][] çıktısı → Detection listesi
         */
        List<Detection> list = new ArrayList<>();
        if (output == null || output.length == 0 || output[0] == null) return list;
        for (float[] row : output[0]) {
            if (row == null || row.length < 6) continue;
            float s = row[4];
            if (s < 0.5f) continue;
            int cls = (int) row[5];
            list.add(new Detection(
                    ai, interpreter, context,
                    cls, s,
                    row[0], row[1], row[2], row[3],
                    getLabelName(cls)
            ));
        }
        return list;
    }
    public List<Detection> parseDetectionsScored(float[][][] output, Interpreter videoInterpreter,Ai ai) {
        this.ai = ai;
        List<Detection> result = new ArrayList<>();
        if (output == null || output.length == 0) {
            return result;  // boşsa hemen döner
        }
        for (float[] row : output[0]) {
            if (row == null || row.length < 6) continue;
            float score = row[4];
            if (score < scoreThreshold) continue;

            int classId = -1;
            float maxClassScore = -1f;
            for (int i = 5; i < row.length; i++) {
                if (row[i] > maxClassScore) {
                    maxClassScore = row[i];
                    classId = i - 5;
                }
            }
            if (classId < 0) continue;

            float x  = row[0],    y  = row[1];
            float w  = row[2],    h  = row[3];
            float x1 = x - w/2f,  y1 = y - h/2f;
            float x2 = x + w/2f,  y2 = y + h/2f;
            String label = labels.get(classId);

            result.add(new Detection(
                    ai,                    // Ai instance
                    videoInterpreter,        // hangi interpreter’la
                    context,                 // Activity/Context
                    classId,
                    maxClassScore,
                    x1, y1, x2, y2,
                    label
            ));
        }
        return result;
    }
    // --- RT PIPELINE ENTRYPOINT ---
    public void handleRT(Mat frame, Ai ai) {
        if (frame == null || frame.empty()) return;

        // 1. Mat → Bitmap
        Bitmap inputBitmap = matToBitmap(frame);
        if (inputBitmap == null) return;

        // 2. Ölçek+paddingle modele uygun bitmap’e dönüştür (TFLiteInputPreprocessor)
        Bitmap modelBitmap = TFLiteInputPreprocessor.scaleAndPadBitmap(
                inputBitmap, ai.getInputWidth(), ai.getInputHeight());
        if (modelBitmap == null) return;

        // 3. Bitmap → Tensor
        float[][][][] inputTensor = TFLiteInputPreprocessor.bitmapToInputTensor(modelBitmap);
        if (inputTensor == null) return;

        // 4. Inference (Ai.java)
        ai.predictVideo(inputTensor, rawOutput -> {
            // 5. Çıktı post-processing (parseDetections veya benzeri fonksiyon)
            List<Detection> detections = parseDetections(rawOutput, ai);
            // 6. ToDo: UI update veya threading ile ana thread'e aktar
            // Threading.runOnUiThread(() -> ...);
        });
    }

    // --- UTIL: Mat to Bitmap ---
    private Bitmap matToBitmap(Mat frame) {
        // ToDo: TFLiteInputPreprocessor içinde varsa oradan çağır!
        try {
            int w = frame.cols(), h = frame.rows();
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            org.opencv.android.Utils.matToBitmap(frame, bmp);
            return bmp;
        } catch (Exception e) {
            Log.e(TAG, "matToBitmap error: " + e.getMessage());
            return null;
        }
    }

    // --- Detection Post-Processing (parseDetections) ---
    private List<Detection> parseDetections(float[][][] rawOutput, Ai ai) {
        // ToDo: AI modeline özel detection parsing
        // Bu örnek basit bir şablondur:
        // float[][] raw = rawOutput[0]; ...
        return new ArrayList<>(); // ya da parse et
    }
}
