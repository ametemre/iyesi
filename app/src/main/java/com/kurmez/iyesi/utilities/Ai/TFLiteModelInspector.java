package com.kurmez.iyesi.utilities.Ai;

import static org.opencv.android.NativeCameraView.TAG;

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
import java.util.Arrays;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.image.TensorImage;

public class TFLiteModelInspector {
    // Model metadata
    private int[] videoInputShape;
    private int[] videoOutputShape;
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];
    private TFLiteInputPreprocessor tfLiteInputPreprocessor;
    private int soundOutputLength;
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
    public int[] getVideoOutputShape() {
        return videoOutputShape.clone();
    }
    private void logModelTensorInfo(Interpreter interpreter,boolean swich) {
        if (swich) {
            Interpreter videoInterpreter = interpreter;
            // Video modeli tensor bilgileri
            if (videoInterpreter != null) {
                int inCount = videoInterpreter.getInputTensorCount();
                int outCount = videoInterpreter.getOutputTensorCount();
                Log.i(TAG, String.format("Video Model Loaded → InputTensorCount=%d, OutputTensorCount=%d", inCount, outCount));

                // Input tensor’leri
                for (int i = 0; i < inCount; i++) {
                    Tensor t = videoInterpreter.getInputTensor(i);
                    Log.i(TAG, String.format(
                            "  [In %d] name=%s shape=%s type=%s",
                            i, t.name(),
                            Arrays.toString(t.shape()),
                            t.dataType()
                    ));
                }
                // Output tensor’leri
                for (int i = 0; i < outCount; i++) {
                    Tensor t = videoInterpreter.getOutputTensor(i);
                    Log.i(TAG, String.format(
                            "  [Out %d] name=%s shape=%s type=%s",
                            i, t.name(),
                            Arrays.toString(t.shape()),
                            t.dataType()
                    ));
                }
            }
        }else {
            // Ses modeli tensor bilgileri
            Interpreter soundInterpreter = interpreter;
            if (soundInterpreter != null) {
                int inCount = soundInterpreter.getInputTensorCount();
                int outCount = soundInterpreter.getOutputTensorCount();
                Log.i(TAG, String.format("Sound Model Loaded → InputTensorCount=%d, OutputTensorCount=%d", inCount, outCount));

                for (int i = 0; i < inCount; i++) {
                    Tensor t = soundInterpreter.getInputTensor(i);
                    Log.i(TAG, String.format(
                            "  [In %d] name=%s shape=%s type=%s",
                            i, t.name(),
                            Arrays.toString(t.shape()),
                            t.dataType()
                    ));
                }
                for (int i = 0; i < outCount; i++) {
                    Tensor t = soundInterpreter.getOutputTensor(i);
                    Log.i(TAG, String.format(
                            "  [Out %d] name=%s shape=%s type=%s",
                            i, t.name(),
                            Arrays.toString(t.shape()),
                            t.dataType()
                    ));
                }
            }
        }
    }
    private boolean validateVideoInput(float[][][][] input, Interpreter videoInterpreter ) {
        if (videoInterpreter == null) return false;
        // Null/boyut kontrolleri
        if (input == null || input.length == 0 || input[0] == null || input[0].length == 0 ||
                input[0][0] == null || input[0][0].length == 0 || input[0][0][0] == null) {
            Log.e(TAG, "Input tensor null veya boyutsuz");
            return false;
        }
        int[] inputShape = videoInterpreter.getInputTensor(0).shape();
        // inputShape 4 boyutlu mu? (ör: [1,224,224,3])
        if (inputShape.length != 4) {
            Log.e(TAG, "Model input tensor shape 4 değil: " + java.util.Arrays.toString(inputShape));
            return false;
        }
        // Her bir eksende boyut karşılaştırması
        if (input.length != inputShape[0]) return false;
        if (input[0].length != inputShape[1]) return false;
        if (input[0][0].length != inputShape[2]) return false;
        if (input[0][0][0].length != inputShape[3]) return false;
        return true;
    }
    public float[][][] processVideoInput(float[][][][] input, Interpreter videoInterpreter) {
        if (!validateVideoInput(input, videoInterpreter)) {
            Log.e(TAG, "Geçersiz input tensor (null/eksik boyut/uyumsuz shape)");
            return EMPTY_VIDEO_OUTPUT;
        }

        float[][][] output = EMPTY_VIDEO_OUTPUT;
        int[] videoOutputShape = videoInterpreter.getOutputTensor(0).shape();

        try {
            if (videoOutputShape == null || videoOutputShape.length == 0) {
                Log.e(TAG, "Model output shape boş veya null!");
                return EMPTY_VIDEO_OUTPUT;
            }
            switch (videoOutputShape.length) {
                case 3:
                    output = new float[videoOutputShape[0]][videoOutputShape[1]][videoOutputShape[2]];
                    videoInterpreter.run(input, output);
                    break;
                case 2:
                    float[][] tmp = new float[videoOutputShape[0]][videoOutputShape[1]];
                    videoInterpreter.run(input, tmp);
                    // 2D'yi 3D'ye çevir, helper fonksiyonun varsa (ör: [n, classes] -> [n, classes, 1])
                    output = tfLiteInputPreprocessor.convert2DTo3D(tmp);
                    break;
                default:
                    Log.e(TAG, "Unsupported output shape rank: " + videoOutputShape.length);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "processVideoInput: Çıkarım sırasında hata!", e);
            output = EMPTY_VIDEO_OUTPUT;
        }
        return output;
    }
}

