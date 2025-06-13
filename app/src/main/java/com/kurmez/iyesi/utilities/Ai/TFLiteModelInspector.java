package com.kurmez.iyesi.utilities.Ai;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.util.Log;
import android.view.TextureView;

import com.kurmez.iyesi.kurmes.Kurmes;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.io.FileInputStream;
import java.io.IOException;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.image.TensorImage;

public class TFLiteModelInspector {
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

    // .tflite modelini belleğe yükleme
    public static MappedByteBuffer loadModelFile(AssetManager mgr, String modelPath) throws IOException {
        AssetFileDescriptor fd = mgr.openFd(modelPath);
        FileInputStream is = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = is.getChannel();
        long start = fd.getStartOffset();
        long len   = fd.getDeclaredLength();
        return channel.map(FileChannel.MapMode.READ_ONLY, start, len);
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
    private void imageSizes(TextureView textureView){
        try {
            Bitmap bmp = textureView.getBitmap();
            Log.i("Tensor Size :", String.format(
                    "Camera Bitmap: width=%d, height=%d",
                    bmp.getWidth(), bmp.getHeight()
            ));

            TensorImage tImg = new TensorImage(DataType.FLOAT32);
            tImg.load(bmp);
            Log.i("Don't Show Toolbar", String.format(
                    "TensorImage after load: width=%d, height=%d",
                    tImg.getWidth(), tImg.getHeight()
            ));
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

    }
}

