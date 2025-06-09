package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.kurmez.iyesi.utilities.helper.TFLiteModelInspector.loadModelFile;

/**
 * Ai sınıfı: TensorFlow Lite modellerini yükler, inference yapar ve kaynakları yönetir.
 * Input tensor boyut uyuşmazlıklarını önlemek için predictVideo içinde doğrulama içerir.
 */
public class Ai {
    private static final String TAG = "AiModel";

    private final Interpreter videoInterpreter;
    private final Interpreter soundInterpreter;
    private final GpuDelegate gpuDelegate;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AssetManager assetManager;
    private final int[] videoInputShape;

    /**
     * @param context        Android Context
     * @param soundModelName Ses modeli dosyası (assets altı), null ise pasif
     * @param videoModelName Video modeli dosyası (assets altı), null ise pasif
     */
    public Ai(@NonNull Context context,
              @Nullable String soundModelName,
              @Nullable String videoModelName) throws IOException {
        assetManager  = context.getAssets();
        mainHandler   = new Handler(Looper.getMainLooper());
        executor      = Executors.newFixedThreadPool(2);

        // GPU delegate ekleme
        Interpreter.Options opts = new Interpreter.Options();
        GpuDelegate delegate = null;
        try {
            delegate = new GpuDelegate();
            opts.addDelegate(delegate);
            Log.i(TAG, "GPU delegate eklendi");
        } catch (Exception e) {
            Log.w(TAG, "GPU delegate kullanılamadı, CPU moduna dönülüyor", e);
        }
        gpuDelegate = delegate;

        // Video Interpreter oluşturma
        if (videoModelName != null && !videoModelName.isEmpty()) {
            MappedByteBuffer modelBuf = loadModelFile(assetManager, videoModelName);
            videoInterpreter = new Interpreter(modelBuf, opts);
            // Modelin sabit input shape'ini oku
            Tensor inTensor = videoInterpreter.getInputTensor(0);
            videoInputShape = inTensor.shape().clone(); // [1, height, width, channels]
            try {
                videoInterpreter.allocateTensors();
            } catch (IllegalStateException e) {
                Log.w(TAG, "allocateTensors hatası, model sabit input shape kullanılıyor", e);
            }
            dumpOutputTensorDetails(videoInterpreter);
        } else {
            videoInterpreter = null;
            videoInputShape = null;
        }

        // Sound Interpreter oluşturma (isteğe bağlı)
        if (soundModelName != null && !soundModelName.isEmpty()) {
            MappedByteBuffer sb = loadModelFile(assetManager, soundModelName);
            soundInterpreter = new Interpreter(sb, opts);
        } else {
            soundInterpreter = null;
        }
    }

    /**
     * Video modelinin giriş yüksekliğini döner
     */
    public int getInputHeight() {
        return videoInputShape != null ? videoInputShape[1] : 0;
    }

    /**
     * Video modelinin giriş genişliğini döner
     */
    public int getInputWidth() {
        return videoInputShape != null ? videoInputShape[2] : 0;
    }

    /**
     * predictVideo öncesi inputTensor boyutlarını kontrol eder
     */
    private boolean validateInputShape(float[][][][] input) {
        if (videoInputShape == null) return false;
        int b = input.length;
        int h = input[0].length;
        int w = input[0][0].length;
        int c = input[0][0][0].length;
        boolean ok = (b == videoInputShape[0] && h == videoInputShape[1]
                && w == videoInputShape[2] && c == videoInputShape[3]);
        if (!ok) {
            Log.e(TAG, String.format(
                    "Input shape mismatch: model expects %s but got [%d,%d,%d,%d]",
                    Arrays.toString(videoInputShape), b, h, w, c));
        }
        return ok;
    }

    /**
     * Video modeli için asenkron inference. Callback UI thread'te çağrılır.
     * Input tensor boyutları modelin beklediği shape ile eşleşmezse çalışmaz.
     * @param inputTensor Normalize edilmiş [1][H][W][C] float tensör
     * @param callback    Sonuç olarak [batch][N][C] boyutlu raw çıkış verir
     */
    public void predictVideo(@NonNull final float[][][][] inputTensor,
                             @NonNull final Consumer<float[][][]> callback) {
        executor.execute(() -> {
            if (videoInterpreter == null) {
                mainHandler.post(() -> callback.accept(new float[0][0][0]));
                return;
            }
            if (!validateInputShape(inputTensor)) {
                mainHandler.post(() -> callback.accept(new float[0][0][0]));
                return;
            }
            Tensor out = videoInterpreter.getOutputTensor(0);
            int[] shape = out.shape();
            float[][][] output = new float[shape[0]][shape[1]][shape[2]];
            try {
                videoInterpreter.run(inputTensor, output);
                mainHandler.post(() -> callback.accept(output));
            } catch (Exception e) {
                Log.e(TAG, "Video inference hatası", e);
                mainHandler.post(() -> callback.accept(new float[0][0][0]));
            }
        });
    }

    /**
     * Sound modeli için asenkron inference. Callback UI thread'te çağrılır.
     * @param input    [1][feature_length] float tensör
     * @param callback Çıktıyı 1D float array olarak döner
     */
    public void predictSound(@NonNull final float[][] input,
                             @NonNull final Consumer<float[]> callback) {
        executor.execute(() -> {
            if (soundInterpreter == null) {
                mainHandler.post(() -> callback.accept(new float[0]));
                return;
            }
            Tensor out = soundInterpreter.getOutputTensor(0);
            int len = Arrays.stream(out.shape()).reduce(1, (a, b) -> a * b);
            float[] output = new float[len];
            try {
                soundInterpreter.run(input, output);
                mainHandler.post(() -> callback.accept(output));
            } catch (Exception e) {
                Log.e(TAG, "Sound inference hatası", e);
                mainHandler.post(() -> callback.accept(new float[0]));
            }
        });
    }

    /** Interpreter ve GPU delegate kaynaklarını serbest bırakır */
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
        if (videoInterpreter != null) videoInterpreter.close();
        if (soundInterpreter != null) soundInterpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
    }

    /** Çıktı tensör detaylarını Logcat'e yazar */
    private void dumpOutputTensorDetails(Interpreter interp) {
        int outCount = interp.getOutputTensorCount();
        for (int i = 0; i < outCount; i++) {
            Tensor t = interp.getOutputTensor(i);
            Log.i(TAG, String.format(
                    "Output[%d] name=%s, shape=%s, dtype=%s, quant=(scale=%.6f, zeroPoint=%d)",
                    i, t.name(), Arrays.toString(t.shape()), t.dataType(),
                    t.quantizationParams().getScale(), t.quantizationParams().getZeroPoint()
            ));
        }
    }
    /**
     * Senkron video inference (onCameraFrame içinde kullanılabilir)
     */
    public float[][][] predictVideoSync(@NonNull float[][][][] input) {
        if (videoInterpreter == null) return new float[0][0][0];
        // Çıktı shape
        int[] s = videoInterpreter.getOutputTensor(0).shape(); // [1, N, C]
        float[][][] output = new float[s[0]][s[1]][s[2]];
        videoInterpreter.run(input, output);
        return output;
    }


}