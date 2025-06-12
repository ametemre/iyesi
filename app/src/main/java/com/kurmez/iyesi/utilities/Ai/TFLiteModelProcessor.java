package com.kurmez.iyesi.utilities.Ai;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * TFLite modelini yükleyip, inference çalıştırmak için sınıf.
 */
public class TFLiteModelProcessor implements TFLiteHelpers.ModelProcessor, AutoCloseable {
    private static final String TAG = "TFLiteModelProcessor";

    private final Interpreter interpreter;
    private final GpuDelegate gpuDelegate;

    /**
     * Modeli yükler ve Interpreter’ı hazırlar.
     *
     * @param assets    AssetManager örneği
     * @param modelPath Asset dizininde model dosya yolu (örn: "models/my_model.tflite")
     * @param useGpu    GPU delegate kullanılacak mı?
     * @throws IOException Model dosyası yüklenemezse
     */
    public TFLiteModelProcessor(@NonNull AssetManager assets,
                                @NonNull String modelPath,
                                boolean useGpu) throws IOException {
        Interpreter.Options options = new Interpreter.Options();

        if (useGpu) {
            gpuDelegate = new GpuDelegate();
            options.addDelegate(gpuDelegate);
            Log.i(TAG, "GPU delegate kullanılıyor.");
        } else {
            gpuDelegate = null;
            Log.i(TAG, "GPU delegate kullanılmıyor, CPU modunda çalışıyor.");
        }

        interpreter = new Interpreter(loadModelFile(assets, modelPath), options);

        Log.i(TAG, "Interpreter başarıyla oluşturuldu.");
    }

    /**
     * ByteBuffer girişini inference eder ve çıktıyı döner.
     *
     * @param inputBuffer Önceden hazırlanmış giriş buffer
     * @param outputShape Model çıkış tensor boyutları [batch, detections, attributes]
     * @return Model çıktısı
     */
    public float[][][] runInference(@NonNull ByteBuffer inputBuffer, @NonNull int[] outputShape) {
        float[][][] output = new float[outputShape[0]][outputShape[1]][outputShape[2]];
        interpreter.run(inputBuffer, output);
        return output;
    }

    /**
     * Interpreter'ı kapatır ve GPU delegate varsa onu da kapatır.
     */
    @Override
    public void close() {
        interpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();
        Log.i(TAG, "Interpreter ve GPU delegate kapatıldı.");
    }

    /**
     * Model dosyasını belleğe yükler.
     *
     * @param mgr       AssetManager
     * @param modelPath Model dosya yolu
     */
    private static MappedByteBuffer loadModelFile(AssetManager mgr, String modelPath) throws IOException {
        try (AssetFileDescriptor fd = mgr.openFd(modelPath);
             FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
             FileChannel fileChannel = fis.getChannel()) {
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        }
    }

    /**
     * Interpreter instance'ını dışarıya açar (opsiyonel kullanım için).
     */
    public Interpreter getInterpreter() {
        return interpreter;
    }
}
