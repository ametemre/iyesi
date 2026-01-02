package com.kurmez.iyesi.kurmes.utilities.Ai.delegate;

// TFLiteModelInspector.java

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.nio.ByteBuffer;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.io.FileInputStream;
import java.io.IOException;

import org.tensorflow.lite.Tensor;

public class TFLiteModelInspector {
    private static final String TAG = "TFLiteModelInspector";
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private final ByteBuffer modelBuffer;
    public TFLiteModelInspector(ByteBuffer modelBuffer, GpuDelegate gpuDelegate) {
        this.modelBuffer = modelBuffer;
        this.gpuDelegate = gpuDelegate;
        Interpreter.Options opts = new Interpreter.Options()
                .setNumThreads(1);
        if (gpuDelegate != null) {
            opts.addDelegate(gpuDelegate);
        }
        this.interpreter = new Interpreter(modelBuffer, opts);
    }
    public static void main(String[] args,Interpreter tflite) {
        // Modeli yükle
        //Interpreter tflite = new Interpreter(loadModelFile("dump/my_birds_model.tflite"));

        // Giriş ve çıkış detaylarını al
        int inputCount = tflite.getInputTensorCount();
        int outputCount = tflite.getOutputTensorCount();

        System.out.println("Giriş Tensorları:");
        for (int i = 0; i < inputCount; i++) {
            Tensor inputTensor = tflite.getInputTensor(i);
            System.out.println(i + ": " + inputTensor.name() + " - Şekil: " + arrayToString(inputTensor.shape()) + " - Tip: " + inputTensor.dataType());
        }

        System.out.println("\nÇıkış Tensorları:");
        for (int i = 0; i < outputCount; i++) {
            Tensor outputTensor = tflite.getOutputTensor(i);
            System.out.println(i + ": " + outputTensor.name() + " - Şekil: " + arrayToString(outputTensor.shape()) + " - Tip: " + outputTensor.dataType());
        }

        // Modeli kapat
        tflite.close();

    }


    public static MappedByteBuffer loadModelFile( // -----------------------------------------------.tflite modelini belleğe yükleme
            AssetManager mgr, String modelPath) throws IOException {
        AssetFileDescriptor fd = mgr.openFd(modelPath);
        FileInputStream is = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = is.getChannel();
        long start = fd.getStartOffset();
        long len   = fd.getDeclaredLength();
        return channel.map(FileChannel.MapMode.READ_ONLY, start, len);
    }


    private static String arrayToString(int[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }// ---------------------------------------Dizi şeklindeki tensor boyutlarını stringe çevir
    public void close() {
        interpreter.close();
        if (gpuDelegate != null) {
            gpuDelegate.close();
        }
    }
}

