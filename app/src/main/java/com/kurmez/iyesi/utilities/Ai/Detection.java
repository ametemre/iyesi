package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputMapper;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputPreprocessor;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Detection {
    public Mat incomingFrame;
    public Mat getIncomingFrame(){
        return incomingFrame;
    }

    // ————————————————
    // ❶ Detection sonucu alanları
    // ————————————————
    private static final float SCORE_THRESHOLD = 0.9f;  // 50% üzeri kesin kabul
    float scoreThreshold = 0.25f;
    private List<Detection> detections;
    private boolean isInitiated = false;

    public float getScoreThreshold() {
        return scoreThreshold;
    }
    private List<String> labels;
    // ————————————————————————
    // ❷ Inference ve yardımcı alanlar
    // ————————————————————————
    private final Context context;
    private final Interpreter interpreter;
    private Ai ai;
    private Bitmap frame;
    private TFLiteInputMapper mapper;
    private TFLiteInputPreprocessor preProcess;
    private ByteBuffer inputData;
    private Bitmap bitmap;
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor();
    private int frameCount = 0;
    private static final int SKIP_FRAMES = 5;
    private static final int DETECTION_INPUT_SIZE = 640;

    private final List<float[][][]> videoBuffer = new ArrayList<>();
    private final List<float[]> soundBuffer = new ArrayList<>();
    private static final int VIDEO_THRESHOLD = 3;
    private static final int SOUND_THRESHOLD = 3;
    private Bitmap reusableFrameBitmap = null;
    private ExecutorService executorService;
    private Bitmap reusableScaledBitmap = null;
    private ByteBuffer reusableInputBuffer = null;
    private final Object lock = new Object(); // thread-safe olması için
    private Kurmes.State currentState;
    private Executor executor;
    private static final String TAG = "Detection";

    private final List<Bitmap> photoList = new ArrayList<>();
    private static final int REQUEST_STORAGE_PERMISSION = 1001;

    public Detection(Ai ai, Interpreter interpreter, Context context/*, int classId, float score, float x1, float y1, float x2, float y2, String label*/) {
        this.ai = ai;
        this.interpreter = interpreter;
        this.context = context;
    }
    public Mat process(Mat incomingFrame){
        try {
            this.incomingFrame = incomingFrame;
            this.mapper = new TFLiteInputMapper(ai,incomingFrame,context);
            this.preProcess =  new TFLiteInputPreprocessor(mapper,interpreter);
        } catch (Exception e) {
            Log.e(TAG,"Error:" + e);
            throw new RuntimeException(e);
        }
        return mapper.map();
    }
    // Detection.java'ya yeni metod ekleyin
    public List<float[]> runInference(Mat frame) {
        try {
            // 1. Giriş verisini hazırla
            Bitmap inputBitmap = matToBitmap(frame);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(inputBitmap);

            // 2. Çıktı tensörünün şeklini al ve uygun dizi oluştur
            Tensor outputTensor = interpreter.getOutputTensor(0);
            int[] outputShape = outputTensor.shape(); // [1, 84, 8400]
            float[][][] outputArray = new float[outputShape[0]][outputShape[1]][outputShape[2]];

            // 3. Modeli çalıştır
            Map<Integer, Object> outputs = new HashMap<>();
            outputs.put(0, outputArray);
            interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

            // 4. Çıktıyı yeniden düzenle: [8400, 84] formatına getir
            List<float[]> outputList = new ArrayList<>();
            for (int i = 0; i < outputShape[2]; i++) { // 8400 nesne
                float[] features = new float[outputShape[1]]; // 84 özellik
                for (int j = 0; j < outputShape[1]; j++) {
                    features[j] = outputArray[0][j][i];
                }
                outputList.add(features);
            }

            // Log kontrolü (isteğe bağlı)
            Log.d(TAG, "Toplam tespit: " + outputList.size());
            return outputList;
        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // Yardımcı metod: Mat -> Bitmap dönüşümü
    public Bitmap matToBitmap(Mat mat) {
        Bitmap bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, bitmap);
        return bitmap;
    }

    // Yardımcı metod: Bitmap -> ByteBuffer dönüşümü
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes());
        inputBuffer.order(ByteOrder.nativeOrder());

        // Normalizasyon (model gereksinimlerine göre ayarlayın)
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int pixel = bitmap.getPixel(x, y);
                inputBuffer.putFloat(((pixel >> 16) & 0xFF) / 255.0f); // R
                inputBuffer.putFloat(((pixel >> 8) & 0xFF) / 255.0f);  // G
                inputBuffer.putFloat((pixel & 0xFF) / 255.0f);         // B
            }
        }
        return inputBuffer;
    }
}
