package com.kurmez.iyesi.utilities.Ai;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;

/**
 * TensorFlow Lite model detaylarını konsola veya log'a yazdırır.
 */
public class TFLiteModelInspector {
    private static final String TAG = "TFLiteModelInspector";

    /**
     * Modelin giriş ve çıkış tensor detaylarını loglar.
     *
     * @param interpreter Hazır interpreter nesnesi
     */
    public static void inspect(@NonNull Interpreter interpreter) {
        int inputCount = interpreter.getInputTensorCount();
        int outputCount = interpreter.getOutputTensorCount();

        Log.i(TAG, "Giriş Tensorları:");
        for (int i = 0; i < inputCount; i++) {
            Tensor tensor = interpreter.getInputTensor(i);
            Log.i(TAG, String.format("IN[%d]: %s, Şekil: %s, Tip: %s",
                    i, tensor.name(), Arrays.toString(tensor.shape()), tensor.dataType()));
        }

        Log.i(TAG, "Çıkış Tensorları:");
        for (int i = 0; i < outputCount; i++) {
            Tensor tensor = interpreter.getOutputTensor(i);
            Log.i(TAG, String.format("OUT[%d]: %s, Şekil: %s, Tip: %s",
                    i, tensor.name(), Arrays.toString(tensor.shape()), tensor.dataType()));
        }
    }
    // .tflite modelini belleğe yükleme
    public static MappedByteBuffer loadModelFile(AssetManager mgr, String modelPath) throws IOException {
        AssetFileDescriptor fd = mgr.openFd(modelPath);
        FileInputStream is = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = is.getChannel();
        long start = fd.getStartOffset();
        long len   = fd.getDeclaredLength();
        return channel.map(FileChannel.MapMode.READ_ONLY, start, len);
    }
}
