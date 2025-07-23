package com.kurmez.iyesi.utilities;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.opengl.GLES20;
import android.opengl.GLES31;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import java.util.List;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.kurmez.iyesi.utilities.Ai.delegate.TFLiteInputPreprocessor;
import com.kurmez.iyesi.utilities.Ai.Ai;
import com.kurmez.iyesi.utilities.Ai.Detection;

public class RTPipeline {
    private static final String TAG = "RTPipeline";
    private final Semaphore gpuSemaphore = new Semaphore(5);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Logs basic GPU information: vendor, renderer, version, and compute limits.
     * Call this once during app startup if desired.
     */
    public void logGpuInfo() {
        String vendor   = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String version  = GLES20.glGetString(GLES20.GL_VERSION);
        Log.i(TAG, "GPU Vendor: " + vendor);
        Log.i(TAG, "GPU Renderer: " + renderer);
        Log.i(TAG, "GPU Version: " + version);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            int[] wgCountX = new int[1], wgCountY = new int[1], wgCountZ = new int[1], invoc = new int[1];
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, wgCountX, 0);
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, wgCountY, 0);
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, wgCountZ, 0);
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, invoc, 0);
            Log.i(TAG, String.format("Max Compute Work-Group Count: x=%d, y=%d, z=%d",
                    wgCountX[0], wgCountY[0], wgCountZ[0]));
            Log.i(TAG, "Max Compute Work-Group Invocations: " + invoc[0]);
        }
    }

    /**
     * Asynchronously processes a frame through the TFLite model on the GPU,
     * limiting concurrency to up to 5 parallel inferences.
     *
     * @param frame           OpenCV Mat frame (RGBA).
     * @param ai              AI helper instance wrapping the TFLite interpreter.
     * @param resultCallback  Callback receiving the list of detections on the main thread.
     */
    public void handleRTAsync(Mat frame, Ai ai, Consumer<List<Detection>> resultCallback) {
        if (frame == null || frame.empty()) {
            resultCallback.accept(Collections.emptyList());
            return;
        }

        new Thread(() -> {
            boolean acquired = false;
            try {
                acquired = gpuSemaphore.tryAcquire(1, 200, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    Log.w(TAG, "Max GPU concurrency reached, skipping frame");
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 1) Mat → Bitmap
                Bitmap inputBmp = matToBitmap(frame);
                if (inputBmp == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 2) Scale + pad to model input size
                Bitmap modelBmp = TFLiteInputPreprocessor.scaleAndPadBitmap(
                        inputBmp, ai.getInputWidth(), ai.getInputHeight());
                if (modelBmp == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 3) Bitmap → Tensor
                float[][][][] inputTensor = TFLiteInputPreprocessor.bitmapToInputTensor(modelBmp);
                if (inputTensor == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 4) Inference on GPU delegate
                ai.predictVideo(inputTensor, rawOutput -> {
                    // 5) Post-process outputs into Detection objects
                    List<Detection> dets = parseDetections(rawOutput, ai);
                    postResult(dets, resultCallback);
                    gpuSemaphore.release();
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (acquired) {
                    gpuSemaphore.release();
                }
                postResult(Collections.emptyList(), resultCallback);
            }
        }).start();
    }

    /**
     * Posts the results back to the main thread.
     */
    private void postResult(List<Detection> dets, Consumer<List<Detection>> cb) {
        mainHandler.post(() -> cb.accept(dets));
    }

    /**
     * Converts an OpenCV Mat to an Android Bitmap.
     */
    private Bitmap matToBitmap(Mat mat) {
        if (mat == null || mat.empty()) return null;
        Bitmap bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Config.ARGB_8888);
        Utils.matToBitmap(mat, bmp);
        return bmp;
    }

    /**
     * Stub for parsing raw model output into Detection objects.
     * Replace with your actual parsing and NMS logic.
     */
    private List<Detection> parseDetections(float[][][] rawOutput, Ai ai) {
        // TODO: implement detection parsing and non-max suppression
        return Collections.emptyList();
    }
/*    // sınıf seviyesinde, maksimum 5 paralel GPU inferans için semaphore
    private final Semaphore gpuSemaphore = new Semaphore(5);
    // Android ana thread’e dönüş için handler
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String TAG = "RTPipeline";

    // GPU bilgilerini bir kerelik log’lamak için (örn. uygulama başlatılırken çağır)
    public void logGpuInfo() {
        String vendor   = GLES20.glGetString(GLES20.GL_VENDOR);
        String renderer = GLES20.glGetString(GLES20.GL_RENDERER);
        String version  = GLES20.glGetString(GLES20.GL_VERSION);
        Log.i(TAG, "GPU Vendor: " + vendor);
        Log.i(TAG, "GPU Renderer: " + renderer);
        Log.i(TAG, "GPU Version: " + version);

        // Eğer OpenGL ES 3.1+ destekliyorsa, compute work-group sınırlarını al
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            int[] wgCountX = new int[1], wgCountY = new int[1], wgCountZ = new int[1], invoc = new int[1];
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, wgCountX, 0);
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 1, wgCountY, 0);
            GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 2, wgCountZ, 0);
            GLES31.glGetIntegerv(GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, invoc, 0);
            Log.i(TAG, String.format(
                    "Max Compute Work-Group Count: x=%d, y=%d, z=%d",
                    wgCountX[0], wgCountY[0], wgCountZ[0]));
            Log.i(TAG, "Max Compute Work-Group Invocations: " + invoc[0]);
        }
    }

    // handleRT’i asenkron, callback’li versiyon
    public void handleRTAsync(Mat frame, Ai ai, androidx.core.util.Consumer<List<Detection>> resultCallback) {
        if (frame == null || frame.empty()) {
            resultCallback.accept(Collections.emptyList());
            return;
        }

        new Thread(() -> {
            // Semaphore ile paralel GPU inferans sayısını sınırlıyoruz
            boolean acquired = false;
            try {
                acquired = gpuSemaphore.tryAcquire(1, 200, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    Log.w(TAG, "Max GPU concurrency reached, skipping frame");
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 1) Mat → Bitmap
                Bitmap inputBmp = matToBitmap(frame);
                if (inputBmp == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 2) Ölçek + padding
                Bitmap modelBmp = TFLiteInputPreprocessor
                        .scaleAndPadBitmap(inputBmp, ai.getInputWidth(), ai.getInputHeight());
                if (modelBmp == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 3) Bitmap → input tensor
                float[][][][] inputTensor = TFLiteInputPreprocessor
                        .bitmapToInputTensor(modelBmp);
                if (inputTensor == null) {
                    postResult(Collections.emptyList(), resultCallback);
                    return;
                }

                // 4) Inference (asenkron, GPU delegate arkasında çalışacak)
                ai.predictVideo(inputTensor, rawOutput -> {
                    // 5) Post-processing
                    List<Detection> dets = parseDetections(rawOutput, ai);
                    postResult(dets, resultCallback);
                    gpuSemaphore.release();
                });

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (acquired) gpuSemaphore.release();
                postResult(Collections.emptyList(), resultCallback);
            }
        }).start();
    }

    // Ana thread’e dönüp sonucu callback’e ver
    private void postResult(List<Detection> dets, androidx.core.util.Consumer<List<Detection>> cb) {
        mainHandler.post(() -> cb.accept(dets));
    }*/
}
