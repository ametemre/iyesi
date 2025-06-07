// Ai.java
package com.kurmez.iyesi.kurmes.Ai;

import static android.os.Build.VERSION_CODES.M;
import static android.os.Build.VERSION_CODES.N;
import static com.kurmez.iyesi.kurmes.helper.TFLiteModelInspector.loadModelFile;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

/**
 * Ai sınıfı, hem ses hem de video (.tflite) modellerini arka planda
 * (UI thread dışı) çalıştırmak için tasarlanmıştır. Sonuçları
 * verdiğiniz callback aracılığıyla döner; UI güncellemesi ise
 * caller tarafında Threading.runOnUi kullanılarak yapılmalıdır.
 */
public class Ai {
    private static final String TAG = "Ai";
    private final Interpreter soundInterpreter;
    private final Interpreter videoInterpreter;
    private GpuDelegate gpuDelegate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Context context;        // ➊ Context’i saklayacak alan

    /**
     * @param context          Android context for loading assets
     * @param soundModelAsset  Path under assets/ (örneğin "sound.tflite")
     * @param videoModelAsset  Path under assets/ (örneğin "liveVideo.tflite")
     * @throws IOException     Model dosyası yüklenemezse fırlatılır
     */
    public Ai(@Nullable Context context, String soundModelAsset, String videoModelAsset, Context context1) throws IOException {
        this.context = context1;
        Interpreter.Options options = new Interpreter.Options();
        Interpreter tflite;
        // GPU delegate denemesi, başarısız olursa CPU fall-back
        try {
            gpuDelegate = new GpuDelegate();
            options.addDelegate(gpuDelegate);
        } catch (Exception e) {
            Log.w(TAG, "GPU delegate not available, using CPU only", e);
        }

        MappedByteBuffer soundBuf = loadModel(context, soundModelAsset);
        MappedByteBuffer videoBuf = loadModel(context, videoModelAsset);

        // Sound modeli yalnızca sağlanmışsa yükle
        if (soundModelAsset != null) {
            soundBuf = loadModel(context, soundModelAsset);
            soundInterpreter = new Interpreter(soundBuf, options);
        } else {
            soundInterpreter = null;
        }
        // Sound modeli yalnızca sağlanmışsa yükle
        if (soundModelAsset != null) {
            videoBuf = loadModel(context, videoModelAsset);
            videoInterpreter = new Interpreter(videoBuf, options);
        } else {
            videoInterpreter = null;
        }
    }

    /**
     * assets/ dizininden .tflite dosyasını MappedByteBuffer olarak yükler.
     */
    private MappedByteBuffer loadModel(Context ctx, String assetPath) throws IOException {
        AssetFileDescriptor afd = ctx.getAssets().openFd(assetPath);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel fc = fis.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
        }
    }

    /**
     * Sound model inference’ı: input = [1][feature_length],
     * callback UI thread’i değil, executor havuzundaki bir thread’te çağrılır.
     *
     * @param input     2D float array shaped [1][feature_length]
     * @param callback  Sonucu almak için: onResult(null) hatada, onResult(outputArray) başarıda
     */
    public void predictSound(float[][] input, ResultCallback<float[]> callback) {
        executor.execute(() -> {
            float[] output = allocate1DOutput(soundInterpreter);
            try {
                soundInterpreter.run(input, output);
                callback.onResult(output);
            } catch (Exception e) {
                Log.e(TAG, "Sound inference error", e);
                callback.onResult(null);
            }
        });
    }
    /**
     * Video model inference’ı: input = [1][h][w][3],
     * callback executor havuzundaki bir thread’te çağrılır.
     *
     * @param input     4D float array shaped [1][height][width][3]
     * @param callback  Sonucu almak için: onResult(null) hatada, onResult(outputArray) başarıda
     */
    public void predictVideo(float[][][][] input, ResultCallback<float[][][]> callback) {
        executor.execute(() -> {
            float[][][] output = allocate3DOutput(videoInterpreter);
            try {
                videoInterpreter.run(input, output);
                callback.onResult(output);
            } catch (Exception e) {
                Log.e(TAG, "Video inference error", e);
                callback.onResult(null);
            }
        });
    }
    /**
     * Tek boyutlu çıkış tensörü için gerekli uzunluk hesaplayıp 1D float dizisi döner.
     */
    private float[] allocate1DOutput(Interpreter interp) {
        int[] shape = interp.getOutputTensor(0).shape(); // örn: [1, numClasses]
        int length = Arrays.stream(shape).reduce(1, (a, b) -> a * b);
        return new float[length];
    }
    /**
     * Üç boyutlu çıkış tensörü için uygun 3D float dizisi oluşturur.
     * Eğer tensör shape.length != 3 ise, tüm boyutları düzleştirir.
     */
    private float[][][] allocate3DOutput(Interpreter interp) {
        int[] shape = interp.getOutputTensor(0).shape(); // örn: [1, N, M]
        if (shape.length == 3) {
            return new float[shape[0]][shape[1]][shape[2]];
        }
        int total = Arrays.stream(shape).reduce(1, (a, b) -> a * b);
        return new float[1][1][total];
    }

    /**
     * Interpreter ve delegate kaynaklarını serbest bırakır.
     * Executor havuzu önce shutdown() ile bırakılır, ardından
     * interpreter ve delegate’i kapatır.
     */
    public void close() {
        // Yeni iş kabul edilmesin, mevcut işlerin bitmesini bekle
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }

        // Interpreter’ları kapat
        try {
            soundInterpreter.close();
            videoInterpreter.close();
        } catch (Exception e) {
            Log.w(TAG, "Error closing interpreters", e);
        }

        // GPU delegate varsa kapat
        if (gpuDelegate != null) {
            try {
                gpuDelegate.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing GPU delegate", e);
            }
        }
    }
    /**
     * assets altındaki modele ilişkin byte buffer’ı döndürür
     */
    private void onRunModelClicked(Context context) {
        try {
            // 1) Model dosyasını yükle
            MappedByteBuffer modelBuffer = loadModelFile(getAssets(), "yolov8n.tflite");

            // 2) Interpreter oluştur
            Interpreter tflite = new Interpreter(modelBuffer);

            // 3) Girdi ve çıktı buffer’ları hazırla (örneğin demo amaçlı boş bir tensor)
            //    -> Gerçek kullanımda Bitmap -> float tensor dönüşümü yapmalısın
            int H = 320, W = 320;
            ByteBuffer input = ByteBuffer.allocateDirect(1 * H * W * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            // …input.putFloat(...) ile pixelleri koy

            float[][][] output = new float[1][N][M];  // modelinin çıktısına göre N,M’yi ayarla

            // 4) İnferansı çalıştır
            tflite.run(input, output);

            // 5) Sonuçları işle
            //    örn: Log.d(TAG, "Sonuç: " + Arrays.deepToString(output));

        } catch (IOException e) {
            Log.e(TAG, "Model yüklenirken hata", e);
            Toast.makeText(context, "Model yüklenemedi", Toast.LENGTH_SHORT).show();
        }
    }
    /** Callback interface for async sonuçlar */
    public interface ResultCallback<T> {
        void onResult(T result);
    }
    // ➌ getAssets() metodu
    private AssetManager getAssets() {
        return context.getAssets();
    }
}
