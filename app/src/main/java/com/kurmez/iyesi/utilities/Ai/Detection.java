package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputMapper;
import com.kurmez.iyesi.utilities.delegate.TFLiteInputPreprocessor;

import org.opencv.android.Utils;
import org.opencv.core.Mat;

import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
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
    // Sınıflandırma eşik değeri (confidence threshold)
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    // Renk tanımları (BGR formatında)
    private static final Scalar GREEN = new Scalar(0, 255, 0);
    private static final int BOX_THICKNESS = 2;
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
            this.mapper = new TFLiteInputMapper(ai,this.incomingFrame,context);
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
    public Mat drawDetections(Mat originalFrame, List<float[]> detections) {
        // Orijinal çerçevenin kopyasını oluştur
        Mat resultFrame = originalFrame.clone();

        // Görüntü boyutlarını al
        int width = resultFrame.cols();
        int height = resultFrame.rows();

        for (float[] detection : detections) {
            // En yüksek skorlu sınıfı bul
            int classId = -1;
            float maxConfidence = 0f;
            for (int i = 4; i < detection.length; i++) {
                if (detection[i] > maxConfidence) {
                    maxConfidence = detection[i];
                    classId = i - 4;
                }
            }

            // Güven skoru eşik değerini geçiyorsa
            if (maxConfidence > CONFIDENCE_THRESHOLD) {
                // Bounding box koordinatlarını hesapla
                float x_center = detection[0] * width;
                float y_center = detection[1] * height;
                float box_width = detection[2] * width;
                float box_height = detection[3] * height;

                // Sol üst köşe koordinatları
                int x = (int) (x_center - box_width / 2);
                int y = (int) (y_center - box_height / 2);

                // Koordinatları görüntü sınırlarına kırp
                x = Math.max(0, Math.min(x, width - 1));
                y = Math.max(0, Math.min(y, height - 1));
                int right = Math.max(0, Math.min(x + (int)box_width, width - 1));
                int bottom = Math.max(0, Math.min(y + (int)box_height, height - 1));

                // Yeşil dikdörtgen çiz
                Imgproc.rectangle(
                        resultFrame,
                        new Point(x, y),
                        new Point(right, bottom),
                        GREEN,
                        BOX_THICKNESS
                );

                // Sınıf bilgisi ve güven skorunu yazdır (isteğe bağlı)
                String label = String.format("%s: %.2f", getClassName(classId), maxConfidence);
                Imgproc.putText(
                        resultFrame,
                        label,
                        new Point(x, y - 5),
                        Imgproc.FONT_HERSHEY_SIMPLEX,
                        0.5,
                        GREEN,
                        1
                );
            }
        }

        return resultFrame;
    }

    private String getClassName(int classId) {
        // COCO veri seti sınıfları (80 sınıf)
        String[] classNames = {
                "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
                "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
                "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe",
                "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard",
                "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
                "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana",
                "apple", "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
                "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
                "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink", "refrigerator",
                "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
        };

        return (classId >= 0 && classId < classNames.length)
                ? classNames[classId]
                : "Unknown";
    }
}
