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
    private final Object lock = new Object(); // thread-safe olması için
    private Kurmes.State currentState;
    private Executor executor;
    private static final String TAG = "Detection";

    private final List<Bitmap> photoList = new ArrayList<>();
    private static final int REQUEST_STORAGE_PERMISSION = 1001;

    public Detection(Ai ai, Interpreter interpreter, Context context, int classId, float score, float x1, float y1, float x2, float y2, String label) {
        this.ai   = ai;
        this.interpreter = interpreter;
        this.context     = context;

        this.classId = classId;
        this.score   = score;
        this.x1 = x1; this.y1 = y1;
        this.x2 = x2; this.y2 = y2;
        this.label = label;
        initiateDetection(ai,frame);
    }

    private void initiateDetection(Ai ai,Bitmap frame) {
        this.mapper = new TFLiteInputMapper(frame.getWidth(),frame.getHeight(),ai.getInputWidth(),ai.getInputHeight());
        this.preProcess =  new TFLiteInputPreprocessor(mapper);
    }

    public void handleRT(Mat frame, Ai ai) {
        Log.i(TAG, "Inference başlıyor...");
        synchronized (lock) {
            if (frame == null || frame.empty()) {
                Log.e("handleRT", "Giriş frame boş.");
                return;
            }
            Log.i(TAG, "Inference başladı");
            // 1. RGB to RGBA
            Mat rgba = new Mat();
            if (frame.channels() == 3) {
                Imgproc.cvtColor(frame, rgba, Imgproc.COLOR_RGB2RGBA);
            } else {
                rgba = frame.clone();
            }
            int w = rgba.cols();
            int h = rgba.rows();
            if (w <= 0 || h <= 0) {
                Log.e("handleRT", "RGBA boyutu geçersiz: " + w + "x" + h);
                rgba.release();
                return;
            }

            if (reusableFrameBitmap == null || reusableFrameBitmap.getWidth() != w || reusableFrameBitmap.getHeight() != h || reusableFrameBitmap.isRecycled()) {
                if (reusableFrameBitmap != null && !reusableFrameBitmap.isRecycled()) {
                    reusableFrameBitmap.recycle();
                }
                reusableFrameBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }

            try {
                Utils.matToBitmap(rgba, reusableFrameBitmap);
            } catch (Exception e) {
                Log.e("handleRT", "matToBitmap hatası: " + e.getMessage());
                rgba.release();
                return;
            }
            rgba.release();

            // 2. Scale + Padding için mapper
            TFLiteInputMapper mapper = new TFLiteInputMapper(w, h, ai.getInputWidth(), ai.getInputHeight());

            if (reusableScaledBitmap == null ||
                    reusableScaledBitmap.getWidth() != ai.getInputWidth() ||
                    reusableScaledBitmap.getHeight() != ai.getInputHeight() ||
                    reusableScaledBitmap.isRecycled()) {

                if (reusableScaledBitmap != null && !reusableScaledBitmap.isRecycled()) {
                    reusableScaledBitmap.recycle();
                }

                reusableScaledBitmap = Bitmap.createBitmap(
                        ai.getInputWidth(),
                        ai.getInputHeight(),
                        Bitmap.Config.ARGB_8888
                );
            }

            Bitmap tempScaled = Bitmap.createScaledBitmap(reusableFrameBitmap, mapper.getScaledWidth(), mapper.getScaledHeight(), true);
            Canvas canvas = new Canvas(reusableScaledBitmap);
            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(tempScaled, mapper.getOffsetX(), mapper.getOffsetY(), null);
            tempScaled.recycle();

            // 3. Bitmap'i modelin istediği ByteBuffer formatına dönüştür
            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * ai.getInputWidth() * ai.getInputHeight() * 3)
                    .order(ByteOrder.nativeOrder());

            for (int y = 0; y < ai.getInputHeight(); y++) {
                for (int x = 0; x < ai.getInputWidth(); x++) {
                    int pixel = reusableScaledBitmap.getPixel(x, y);
                    inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255f); // R
                    inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255f);  // G
                    inputBuffer.putFloat((pixel & 0xFF) / 255f);         // B
                }
            }
            inputBuffer.rewind();

            //ai.run(() -> {
            // 4. GPU'da arka planda çalıştır
            //Threading.runOnBackground(() -> {
            // 4. GPU'da arka planda çalıştır
            //Threading.runOnBackground(() -> {
            ai.predictVideo(preProcess.bitmapToInputTensor(reusableScaledBitmap), rawOutput -> {
                if (rawOutput == null || rawOutput.length < 1) {
                    Log.e(TAG, "Inference çıktısı beklenenden kısa: batch boyutu " +
                            (rawOutput==null? "null" : rawOutput.length));
                    return;
                }
                decodeExecutor.execute(() -> {
                    // rawOutput: float[1][C][N]
                    float[][][] modelOut = rawOutput;
                    // 1) Batch boyutunu at
                    //float[][] raw = modelOut[0];
                    float[][] raw = rawOutput[0];  // <— burası çok önemli
                    int C = raw.length;       // kanal sayısı
                    int N = raw[0].length;    // hücre sayısı

                    // 2) Sabit kanallar
                    float[] boxCx      = raw[0];
                    float[] boxCy      = raw[1];
                    float[] boxW       = raw[2];
                    float[] boxH       = raw[3];
                    float[] objectness = raw[4];

                    // 3) Dinamik sınıf kanalları
                    List<String> labels = ai.getLabels();      // modelin etiket listesi
                    int numClasses = C - 5;                     // = labels.size()
                    Map<String, float[]> classScores = new HashMap<>();
                    for (int i = 0; i < numClasses; i++) {
                        // Kanal raw[5 + i] → labels.get(i)
                        classScores.put(labels.get(i), raw[5 + i]);
                    }

                    // Örnek: “dog” sınıfı üzerinden skorlar:
                    float[] dogScores = classScores.get("dog");
                    if (dogScores != null) {
                        Log.i(TAG, "Dog skorları[0] = " + dogScores[0]);
                    }

                    // 4) İsterseniz Non-Max Suppression’dan önce her hücreyi kendi sınıfı ile eşleyin
                    List<Detection> dets = new ArrayList<>();
                    for (int c = 0; c < N; c++) {
                        if (objectness[c] < 0.5f) continue;
                        // en yüksek sınıfı bul
                        int bestCls = 0;
                        float bestScore = 0f;
                        for (int i = 0; i < numClasses; i++) {
                            float s = raw[5 + i][c];
                            if (s > bestScore) {
                                bestScore = s;
                                bestCls   = i;
                            }
                        }
                        float cx = boxCx[c], cy = boxCy[c];
                        float wb  = boxW[c],  hb  = boxH[c];
                        // … burdan Detected box hesaplaması vs.
                        //   dets.add(new Detection(..., bestCls, bestScore, ...));
                    }
                });
                // 5) Çizim
                //runOnUiThread(() -> ai.drawDetections(frame, dets));
            });
            //});
        }
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
}
