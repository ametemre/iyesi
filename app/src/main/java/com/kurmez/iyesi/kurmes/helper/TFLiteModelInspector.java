package com.kurmez.iyesi.kurmes.helper;

import java.nio.MappedByteBuffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.io.FileInputStream;
import java.io.IOException;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

public class TFLiteModelInspector {
    public static void main(String[] args) {
        try {
            // Modeli yükle
            Interpreter tflite = new Interpreter(loadModelFile("my_birds_model.tflite"));

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

        } catch (IOException e) {
            System.err.println("Model yüklenirken hata oluştu: " + e.getMessage());
        }
    }

    // .tflite modelini belleğe yükleme
    private static MappedByteBuffer loadModelFile(String modelPath) throws IOException {
        FileInputStream fileInputStream = new FileInputStream(modelPath);
        FileChannel fileChannel = fileInputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
    }

    // Dizi şeklindeki tensor boyutlarını stringe çevir
    private static String arrayToString(int[] array) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}

