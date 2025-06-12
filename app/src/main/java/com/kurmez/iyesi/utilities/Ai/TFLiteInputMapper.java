package com.kurmez.iyesi.utilities.Ai;

import static com.kurmez.iyesi.kurmes.Kurmes.DETECTION_INPUT_SIZE;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import androidx.annotation.FloatRange;
import androidx.annotation.NonNull;

import com.kurmez.iyesi.kurmes.Kurmes;

import org.opencv.core.Mat;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * TFLiteInputMapper maps between camera‐frame coordinates and
 * TensorFlow Lite model input coordinates, preserving aspect ratio
 * by scaling and padding equally.
 *
 * Immutable and thread‐safe after construction.
 */
public final class TFLiteInputMapper {
    private final int frameWidth;
    private final int frameHeight;
    private final int inputWidth;
    private final int inputHeight;

    private final float scaleFactor;
    private final int offsetX;
    private final int offsetY;
    private final int scaledWidth;
    private final int scaledHeight;

    /**
     * @param frameWidth  Width of the camera frame in pixels (must be >0)
     * @param frameHeight Height of the camera frame in pixels (must be >0)
     * @param inputWidth  Width expected by the model in pixels (must be >0)
     * @param inputHeight Height expected by the model in pixels (must be >0)
     * @throws IllegalArgumentException if any dimension ≤ 0
     */
    public TFLiteInputMapper(
            int frameWidth,
            int frameHeight,
            int inputWidth,
            int inputHeight
    ) {
        if (frameWidth <= 0 || frameHeight <= 0 ||
                inputWidth  <= 0 || inputHeight <= 0) {
            throw new IllegalArgumentException(
                    "All dimensions must be positive: " +
                            "frame=" + frameWidth + "x" + frameHeight + ", " +
                            "input=" + inputWidth + "x" + inputHeight
            );
        }

        this.frameWidth  = frameWidth;
        this.frameHeight = frameHeight;
        this.inputWidth  = inputWidth;
        this.inputHeight = inputHeight;

        float frameRatio = (float) frameWidth  / frameHeight;
        float inputRatio = (float) inputWidth  / inputHeight;

        if (frameRatio > inputRatio) {
            // Frame is wider: scale by height, pad left/right
            this.scaleFactor = (float) inputHeight / frameHeight;
            this.scaledWidth  = Math.round(frameWidth * scaleFactor);
            this.scaledHeight =  inputHeight;
            this.offsetX      = (inputWidth - scaledWidth) / 2;
            this.offsetY      = 0;
        } else {
            // Frame is taller or equal: scale by width, pad top/bottom
            this.scaleFactor = (float) inputWidth / frameWidth;
            this.scaledWidth  =  inputWidth;
            this.scaledHeight = Math.round(frameHeight * scaleFactor);
            this.offsetX      = 0;
            this.offsetY      = (inputHeight - scaledHeight) / 2;
        }
    }

    /** Returns the computed scale factor (frame → model). */
    public float getScaleFactor() {
        return scaleFactor;
    }

    /** Horizontal padding added after scaling (px). */
    public int getOffsetX() {
        return offsetX;
    }

    /** Vertical padding added after scaling (px). */
    public int getOffsetY() {
        return offsetY;
    }

    /** Width of the scaled frame (px). */
    public int getScaledWidth() {
        return scaledWidth;
    }

    /** Height of the scaled frame (px). */
    public int getScaledHeight() {
        return scaledHeight;
    }

    /**
     * Maps a point from the original frame coordinate system to
     * the model's input coordinate system.
     *
     * @param x X in frame coords (px), ≥ 0
     * @param y Y in frame coords (px), ≥ 0
     * @return a PointF in input coords
     */
    @NonNull
    public PointF mapPoint(
            @FloatRange(from = 0) float x,
            @FloatRange(from = 0) float y
    ) {
        float tx = x * scaleFactor + offsetX;
        float ty = y * scaleFactor + offsetY;
        return new PointF(tx, ty);
    }

    /**
     * Maps a rectangle from the original frame to the model's input.
     *
     * @param rect the RectF in frame coords
     * @return a new RectF in input coords
     */
    @NonNull
    public RectF mapRect(@NonNull RectF rect) {
        PointF topLeft     = mapPoint(rect.left,  rect.top);
        PointF bottomRight = mapPoint(rect.right, rect.bottom);
        return new RectF(
                topLeft.x,
                topLeft.y,
                bottomRight.x,
                bottomRight.y
        );
    }

    /**
     * Inverse maps a rectangle from model input space back to
     * original frame coordinates.
     *
     * @param inputRect RectF in input coords
     * @return mapped RectF in frame coords
     */
    @NonNull
    public RectF inverseMapRect(@NonNull RectF inputRect) {
        float inv = 1f / scaleFactor;
        float left   = (inputRect.left   - offsetX) * inv;
        float top    = (inputRect.top    - offsetY) * inv;
        float right  = (inputRect.right  - offsetX) * inv;
        float bottom = (inputRect.bottom - offsetY) * inv;
        return new RectF(left, top, right, bottom);
    }
    public static Bitmap resizeBitmap(Bitmap bmp, int maxSize) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        float scale = (float)maxSize / Math.max(w, h);
        return Bitmap.createScaledBitmap(bmp, (int)(w*scale), (int)(h*scale), true);
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
    /** Mat → [1][SIZE][SIZE][3] tensor (stub) */
    private float[][][][] preprocessImage(Mat frame) {
        // TODO: gerçek boyutlandırma & normalization ekleyin
        return new float[1][DETECTION_INPUT_SIZE][DETECTION_INPUT_SIZE][3];
    }
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
    /** rawData() çağırılmadan önce inputData ve bitmap set edin
    public void setRawInput(ByteBuffer buffer, Bitmap bmp) {
        this.inputData = buffer;
        this.bitmap    = bmp;
    }
     */
    /**
     * Modeli çalıştırır, ham çıktıyı okur, NMS uygular ve
     * sonuçları yeni Detection nesneleri olarak loglar.
     *//*
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
     Disk yazma izni isteme
     private void requestStoragePermission() {
     if (context instanceof Activity) {
     ActivityCompat.requestPermissions(
     (Activity)context,
     new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
     REQUEST_STORAGE_PERMISSION
     );
     }
     }
     */
    private float sigmoid(float x) {
        return (float)(1.0 / (1.0 + Math.exp(-x)));
    }
/**
    private List<Integer> nonMaxSuppression(List<RectF> boxes,
                                            List<Float> scores,
                                            float threshold) {
        List<Integer> keep  = new ArrayList<>();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) order.add(i);
        Collections.sort(order, (i, j) -> Float.compare(scores.get(j), scores.get(i)));
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

     * OpenCV ile object detection görselleştirmesi

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
     }*/
    /**
     * Video ve ses verisiyle tür tespiti akışı

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
     } */
    /**
     * Ekran görüntüsü yakala ve disk kaydet

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
     */
    /**
     * Ses özelliği çıkarımı (stub)

     private float[][] captureAudioFeatures() {
     // TODO: PCM → MFCC dönüşümü ekleyin
     return new float[128][];
     }
     */
}
