package com.kurmez.iyesi.utilities.Ai;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import com.kurmez.iyesi.kurmes.Kurmes;

import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public class Detection {
    // ————————————————
    // ❶ Detection sonucu alanları
    // ————————————————
    public final String label;
    public final int classId;
    public final float x1, y1, x2, y2, score;
    // DetectionActivity.java
    private static final float SCORE_THRESHOLD = 0.9f;  // 50% üzeri kesin kabul

    // ————————————————————————
    // ❷ Inference ve yardımcı alanlar
    // ————————————————————————
    private final Context context;
    private final Interpreter interpreter;
    private final Ai aiContent;
    private ByteBuffer inputData;
    private Bitmap bitmap;

    private int frameCount = 0;
    private static final int SKIP_FRAMES = 5;
    private static final int DETECTION_INPUT_SIZE = 640;

    private final List<float[][][]> videoBuffer = new ArrayList<>();
    private final List<float[]> soundBuffer = new ArrayList<>();
    private static final int VIDEO_THRESHOLD = 3;
    private static final int SOUND_THRESHOLD = 3;

    private Kurmes.State currentState;
    private static final String TAG = "Detection";

    private final List<Bitmap> photoList = new ArrayList<>();
    private static final int REQUEST_STORAGE_PERMISSION = 1001;

    /**
     * Yapıcı: Hem AI motoru, hem Interpreter, hem Context,
     * hem de tekil bir tespit verisi alır.
     */
    public Detection(Ai aiContent,
                     Interpreter interpreter,
                     Context context,
                     int classId,
                     float score,
                     float x1,
                     float y1,
                     float x2,
                     float y2,
                     String label) {
        this.aiContent   = aiContent;
        this.interpreter = interpreter;
        this.context     = context;

        this.classId = classId;
        this.score   = score;
        this.x1 = x1; this.y1 = y1;
        this.x2 = x2; this.y2 = y2;
        this.label = label;
    }

    /** rawData() çağırılmadan önce inputData ve bitmap set edin */
    public void setRawInput(ByteBuffer buffer, Bitmap bmp) {
        this.inputData = buffer;
        this.bitmap    = bmp;
    }

    /**
     * Modeli çalıştırır, ham çıktıyı okur, NMS uygular ve
     * sonuçları yeni Detection nesneleri olarak loglar.
     */
    public void rawData() {
        if (interpreter == null || inputData == null || bitmap == null) {
            Log.e(TAG, "Interpreter, inputData veya bitmap eksik!");
            return;
        }

        // Çıktı tensor boyutları: [1][84][8400]
        float[][][] out = new float[1][84][8400];
        interpreter.run(inputData, out);

        float[][] raw      = out[0];
        int numChannels    = raw.length;    // 84
        int numCells       = raw[0].length; // 8400
        int numClasses     = numChannels - 5;

        List<RectF> boxList     = new ArrayList<>();
        List<Float> scoreList   = new ArrayList<>();
        List<Integer> classList = new ArrayList<>();

        float scoreThreshold = 0.25f;
        int imgW = bitmap.getWidth();
        int imgH = bitmap.getHeight();

        // Hücre başına tespit çıkarımı
        for (int c = 0; c < numCells; c++) {
            float cx = raw[0][c];
            float cy = raw[1][c];
            float w  = raw[2][c];
            float h  = raw[3][c];

            float objness = sigmoid(raw[4][c]);

            // En iyi sınıf olasılığı
            float bestConf = 0f;
            int bestCls    = -1;
            for (int k = 0; k < numClasses; k++) {
                float s = sigmoid(raw[5 + k][c]);
                if (s > bestConf) {
                    bestConf = s;
                    bestCls  = k;
                }
            }

            float finalScore = objness * bestConf;
            if (finalScore < scoreThreshold) continue;

            float x1 = (cx - w/2f) * imgW;
            float y1 = (cy - h/2f) * imgH;
            float x2 = (cx + w/2f) * imgW;
            float y2 = (cy + h/2f) * imgH;

            boxList.add(new RectF(x1, y1, x2, y2));
            scoreList.add(finalScore);
            classList.add(bestCls);
        }

        // NMS
        List<Integer> keep = nonMaxSuppression(boxList, scoreList, 0.45f);

        // Son tespitleri logla
        for (int idx : keep) {
            Detection det = new Detection(
                    aiContent, interpreter, context,
                    classList.get(idx),
                    scoreList.get(idx),
                    boxList.get(idx).left,
                    boxList.get(idx).top,
                    boxList.get(idx).right,
                    boxList.get(idx).bottom,
                    getLabelName(classList.get(idx))
            );
            Log.i(TAG, String.format(
                    "cls=%d (%s) score=%.2f box=[%.0f,%.0f,%.0f,%.0f]",
                    det.classId, det.label,
                    det.score, det.x1, det.y1, det.x2, det.y2
            ));
        }
    }
    public void handleRT(Mat frame,Ai ai){
        Mat rgba = new Mat();
        if (frame.channels() == 3) {
            Imgproc.cvtColor(frame, rgba, Imgproc.COLOR_RGB2RGBA);
        } else {
            rgba = frame;
        }
        Bitmap frameBitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, frameBitmap);
// 1. Mapper’ı oluştur
// 1. Mapper’ı oluştur
        int srcW = frame.cols();
        int srcH = frame.rows();
        TFLiteInputMapper mapper = null;
        if (frame.width()>0 && frame.height()>0) {
            mapper = new TFLiteInputMapper(frame.width(), frame.height(), ai.getInputWidth(), ai.getInputHeight());
        }else {
            mapper = new TFLiteInputMapper(srcW, srcH, ai.getInputWidth(), ai.getInputHeight());
        }
// 2. Frame’i ölçekle
        Bitmap scaled = Bitmap.createScaledBitmap(
                frameBitmap,
                mapper.getScaledWidth(),
                mapper.getScaledHeight(),
                true
        );


// 3. Siyah (veya istediğiniz renk) bir input Bitmap yarat
        Bitmap inputBmp = Bitmap.createBitmap(
                ai.getInputWidth(), ai.getInputHeight(), Objects.requireNonNull(frameBitmap.getConfig())
        );
        Canvas c = new Canvas(inputBmp);
        c.drawColor(Color.BLACK);
        c.drawBitmap(scaled, mapper.getOffsetX(), mapper.getOffsetY(), null);

// 4. ByteBuffer’a dönüştürme (örnek)
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(4 * ai.getInputWidth() * ai.getInputHeight() * 3)
                .order(ByteOrder.nativeOrder());
        for (int y = 0; y < ai.getInputHeight(); y++) {
            for (int x = 0; x < ai.getInputWidth(); x++) {
                int pixel = inputBmp.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255f);
                inputBuffer.putFloat(((pixel >> 8)  & 0xFF) / 255f);
                inputBuffer.putFloat(((pixel)       & 0xFF) / 255f);
            }
        }
        inputBuffer.rewind();
        ai.predictVideo(bitmapToInputTensor(inputBmp),rawOutput -> {
            // 1) Ham çıktıyı Detection objelerine dönüştür
            //    parseDetections metodu Detection.java içinde tanımlı
            List<Detection> dets = parseDetections(rawOutput);  // :contentReference[oaicite:0]{index=0}

            // 2) UI thread’ine geç ve tespitleri çiz
            runOnUiThread(() -> {
                for (Detection d : dets) {
                    // Üst-sol ve alt-sağ köşe noktaları
                    Point tl = new Point(d.x1, d.y1);
                    Point br = new Point(d.x2, d.y2);
                    // Yeşil çerçeve
                    if (d.score > SCORE_THRESHOLD) {
                    Imgproc.rectangle(frame, tl, br, new Scalar(0, 255, 0), 2);
                    // Beyaz etiket yazısı
                    Imgproc.putText(
                            frame,
                            d.label + String.format(" %.2f", d.score),
                            new Point(d.x1, d.y1 - 8),
                            Imgproc.FONT_HERSHEY_SIMPLEX,
                            0.6,
                            new Scalar(255, 255, 255),
                            2
                    );

                        Log.w(TAG, "AI modeli yüklendi ve çalışıyor..." + d.label + d.score);
                    }

                }
            });
        });
    }
    /**
     * detections listesinden, score'u threshold'dan yüksek olanların
     * etiketlerini döner.
     */


    private void matchPercepts(Kurmes.State state,
                               List<float[][][]> vidBuf,
                               List<float[]> sndBuf) {
        float[] latest = sndBuf.get(sndBuf.size() - 1);
        int idx = argmax(latest);
        switch (state) {
            case KEDI:
                //Log.i(TAG, "Detected cat sound: " + Kurmes.KEDI_SOUNDS[idx]);
                break;
            case KOPEK:
                //Log.i(TAG, "Detected dog sound: " + Kurmes.KOPEK_SOUNDS[idx]);
                break;
            case KURT:
                //Log.i(TAG, "Detected wolf sound: " + Kurmes.KURT_SOUNDS[idx]);
                break;
            case KARGA:
                //Log.i(TAG, "Detected crow sound: " + Kurmes.KARGA_SOUNDS[idx]);
                break;
        }
    }

    private int argmax(float[] array) {
        int maxIdx = 0;
        float maxVal = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > maxVal) {
                maxVal = array[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    /** Mat → [1][SIZE][SIZE][3] tensor (stub) */
    private float[][][][] preprocessImage(Mat frame) {
        // TODO: gerçek boyutlandırma & normalization ekleyin
        return new float[1][DETECTION_INPUT_SIZE][DETECTION_INPUT_SIZE][3];
    }

    /** Bitmap → [1][H][W][3] */
    private float[][][][] bitmapToInputTensor(Bitmap bmp) {
        int W = bmp.getWidth(), H = bmp.getHeight();
        float[][][][] tensor = new float[1][H][W][3];
        int[] pixels = new int[W*H];
        bmp.getPixels(pixels, 0, W, 0, 0, W, H);
        for (int j = 0; j < H; j++) {
            for (int i = 0; i < W; i++) {
                int p = pixels[j*W + i];
                tensor[0][j][i][0] = ((p>>16)&0xFF)/255f;
                tensor[0][j][i][1] = ((p>>8)&0xFF)/255f;
                tensor[0][j][i][2] = (p&0xFF)/255f;
            }
        }
        return tensor;
    }

    public static Bitmap resizeBitmap(Bitmap bmp, int maxSize) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = (float)maxSize / Math.max(w, h);
        return Bitmap.createScaledBitmap(bmp, (int)(w*scale), (int)(h*scale), true);
    }

    /**
     * Modelin float[][][] çıktısı → Detection listesi
     */
    public List<Detection> parseDetections(float[][][] output) {
        List<Detection> list = new ArrayList<>();
        if (output == null || output.length == 0 || output[0] == null) return list;
        for (float[] row : output[0]) {
            if (row == null || row.length < 6) continue;
            float s = row[4];
            if (s < 0.5f) continue;
            int cls = (int) row[5];
            list.add(new Detection(
                    aiContent, interpreter, context,
                    cls, s,
                    row[0], row[1], row[2], row[3],
                    getLabelName(cls)
            ));
        }
        return list;
    }

    public String getLabelName(int classId) {
        String[] labels = {"person","bicycle","car", /* … */};
        if (classId >= 0 && classId < labels.length) return labels[classId];
        return "cls" + classId;
    }
    /**
     * OpenCV ile object detection görselleştirmesi
     */
    public void handleObjectDetection(Mat frame, Ai ai) {
        if (++frameCount % SKIP_FRAMES != 0) return;

        Bitmap bmp = Bitmap.createBitmap(frame.cols(), frame.rows(),
                Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(frame, bmp);

        float[][][][] input = bitmapToInputTensor(bmp);
        ai.predictVideo(input, output -> {
            runOnUiThread(() -> {
                List<Detection> dets = parseDetections(output);
                for (Detection d : dets) {
                    Point tl = new Point(d.x1, d.y1);
                    Point br = new Point(d.x2, d.y2);
                    Imgproc.rectangle(frame, tl, br, new Scalar(0,255,0), 2);
                    Imgproc.putText(frame, d.label, tl,
                            Imgproc.FONT_HERSHEY_SIMPLEX,
                            0.5, new Scalar(255,255,255), 2);
                }
            });
        });
    }
    /**
     * Video ve ses verisiyle tür tespiti akışı
     */
    public void handleSpecies(Mat frame,Ai ai) {
        if (ai == null) {
            Log.w(TAG, "AI modeli yok, inference atlanıyor");
            return;
        }
        float[][][][] imgTensor = preprocessImage(frame);
        ai.predictVideo(imgTensor, videoOut -> {
            synchronized (videoBuffer) { videoBuffer.add(videoOut); }
        });

        float[][] audioTensor = captureAudioFeatures();
        ai.predictSound(audioTensor, soundOut -> {
            synchronized (soundBuffer) { soundBuffer.add(soundOut); }
        });

        if (videoBuffer.size() >= VIDEO_THRESHOLD &&
                soundBuffer.size() >= SOUND_THRESHOLD) {
            runOnUiThread(() -> matchPercepts(currentState, videoBuffer, soundBuffer));
            videoBuffer.clear();
            soundBuffer.clear();
        }
    }
    /**
     * Ekran görüntüsü yakala ve disk kaydet
     */
    public void capturePhoto(Mat rgb) {
        if (rgb == null || rgb.empty()) {
            Toast.makeText(context, "No valid image to capture", Toast.LENGTH_SHORT).show();
            return;
        }
        Bitmap bmp = Bitmap.createBitmap(rgb.cols(), rgb.rows(),
                Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgb, bmp);

        Bitmap resized = resizeBitmap(bmp, 225);
        photoList.add(resized);

        File dir = context.getExternalFilesDir(null);
        File file = new File(dir, "Kurmes_Capture_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            resized.compress(Bitmap.CompressFormat.JPEG, 100, out);
            Toast.makeText(context,
                    "Photo saved: " + file.getAbsolutePath(),
                    Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.e(TAG, "Failed to save photo", e);
            Toast.makeText(context, "Failed to save photo", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Ses özelliği çıkarımı (stub)
     */
    private float[][] captureAudioFeatures() {
        // TODO: PCM → MFCC dönüşümü ekleyin
        return new float[128][];
    }

    /** UI thread’e geçiş */
    private void runOnUiThread(Runnable r) {
        if (context instanceof Activity) {
            ((Activity)context).runOnUiThread(r);
        }
    }

    /** Disk yazma izni isteme */
    private void requestStoragePermission() {
        if (context instanceof Activity) {
            ActivityCompat.requestPermissions(
                    (Activity)context,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION
            );
        }
    }

    private float sigmoid(float x) {
        return (float)(1.0 / (1.0 + Math.exp(-x)));
    }

    private List<Integer> nonMaxSuppression(List<RectF> boxes,
                                            List<Float> scores,
                                            float threshold) {
        List<Integer> keep  = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) order.add(i);
        Collections.sort(order, (i,j) -> Float.compare(scores.get(j), scores.get(i)));
        while (!order.isEmpty()) {
            int idx = order.remove(0);
            keep.add(idx);
            Iterator<Integer> it = order.iterator();
            while (it.hasNext()) {
                if (iou(boxes.get(idx), boxes.get(it.next())) > threshold) {
                    it.remove();
                }
            }
        }
        return keep;
    }

    private float iou(RectF a, RectF b) {
        float left   = Math.max(a.left,   b.left);
        float top    = Math.max(a.top,    b.top);
        float right  = Math.min(a.right,  b.right);
        float bottom = Math.min(a.bottom, b.bottom);
        float inter  = Math.max(0, right - left) * Math.max(0, bottom - top);
        float union  = a.width()*a.height() + b.width()*b.height() - inter;
        return inter / union;
    }
}
