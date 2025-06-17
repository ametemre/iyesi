package com.kurmez.iyesi.utilities.delegate;

// TFLiteModelInspector.java

import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.view.TextureView;

import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.io.FileInputStream;
import java.io.IOException;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.image.TensorImage;

public class TFLiteModelInspector {
    private static final String TAG = "TFLiteModelInspector";
    private Interpreter interpreter;
    private GpuDelegate gpuDelegate;
    private final ByteBuffer modelBuffer;
    // Model metadata
    private int[] videoInputShape;
    private int[] videoOutputShape;
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];
    private TFLiteInputPreprocessor tfLiteInputPreprocessor;
    private int soundOutputLength;

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
/*    public TFLiteModelInspector(ByteBuffer modelBuffer, GpuDelegate.Options gpuOptions) {
        this.modelBuffer = modelBuffer;
        try {
            gpuDelegate = new GpuDelegate(gpuOptions);
            Interpreter.Options opts = new Interpreter.Options()
                    .addDelegate(gpuDelegate)
                    .setNumThreads(1);
            interpreter = new Interpreter(modelBuffer, opts);
        } catch (Exception e) {
            Log.w(TAG, "GPU delegate init failed, falling back to CPU", e);
            interpreter = new Interpreter(modelBuffer,
                    new Interpreter.Options().setNumThreads(
                            Runtime.getRuntime().availableProcessors()));
        }
    }*/
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

    public float[][][] processVideoInput(float[][][][] inputArray) {
        /**
         * Runs inference on a 4D float array ([1][H][W][C]).
         */
        // 1. Input tensor shape
        int[] shape = interpreter.getInputTensor(0).shape(); // [1, H, W, C]
        int batch = shape[0], height = shape[1], width = shape[2], channels = shape[3];

        // 2. Prepare ByteBuffer
        int byteCount = batch * height * width * channels * Float.BYTES;
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(byteCount)
                .order(ByteOrder.nativeOrder());
        // 3. Flatten and put floats
        float[] flat = flatten4D(inputArray);
        for (float v : flat) {
            inputBuffer.putFloat(v);
        }
        inputBuffer.rewind();

        // 4. Prepare output array
        int[] outShape = interpreter.getOutputTensor(0).shape(); // e.g. [1, N, M]
        float[][][] output = new float[outShape[0]][outShape[1]][outShape[2]];

// 5. Run inference with null-check ve fallback
        if (interpreter != null && inputBuffer != null && output != null) {
            try {
                interpreter.run(inputBuffer, output);
            } catch (Exception gpuFail) {
                Log.e(TAG, "GPU inference failed, switching to CPU", gpuFail);
                // GPU interpreter’ı kapat
                interpreter.close();
                if (gpuDelegate != null) {
                    gpuDelegate.close();
                    gpuDelegate = null;
                }
                // CPU interpreter
                Interpreter cpuInterp = new Interpreter(modelBuffer,
                        new Interpreter.Options()
                                .setNumThreads(
                                        Runtime.getRuntime().availableProcessors()));
                if (cpuInterp != null) {
                    cpuInterp.run(inputBuffer, output);
                    cpuInterp.close();
                } else {
                    Log.e(TAG, "CPU interpreter oluşturulamadı, inference atlandı");
                }
            }
        } else {
            Log.e(TAG, "Interpreter ya da buffer’lar null, inference atlandı");
        }


        return output;
    }

    private float[] flatten4D(float[][][][] array) {
        int b = array.length;
        int h = array[0].length;
        int w = array[0][0].length;
        int c = array[0][0][0].length;
        float[] flat = new float[b * h * w * c];
        int idx = 0;
        for (int bi = 0; bi < b; bi++) {
            for (int hi = 0; hi < h; hi++) {
                for (int wi = 0; wi < w; wi++) {
                    for (int ci = 0; ci < c; ci++) {
                        flat[idx++] = array[bi][hi][wi][ci];
                    }
                }
            }
        }
        return flat;
    }// Helper: flatten a 4D float array to 1D

    public void close() {
        interpreter.close();
        if (gpuDelegate != null) {
            gpuDelegate.close();
        }
    }
}

