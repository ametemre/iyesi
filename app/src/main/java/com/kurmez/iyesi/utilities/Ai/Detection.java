package com.kurmez.iyesi.utilities.Ai;

import com.kurmez.iyesi.utilities.Ai.threading.ThreadService;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.utilities.Ai.delegate.TFLiteInputMapper;
import com.kurmez.iyesi.utilities.Ai.delegate.TFLiteInputPreprocessor;

import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Detection {
    public int classId;
    public String label;
    public float score;
    public float x1, y1, x2, y2;
    public List<DetectionResult> d;

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

    public Detection(Ai ai, Interpreter interpreter, Context context, int classId, float score, float x1, float y1, float x2, float y2, String label) {
        this.ai = ai;
        this.interpreter = interpreter;
        this.context = context;
        this.classId = classId;
        this.label   = label;
        this.score   = score;
        this.x1 = x1; this.y1 = y1;
        this.x2 = x2; this.y2 = y2;
        this.d = new ArrayList<>();
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
    public List<DetectionResult> createDetectionList(float[][] output2D, Mat inputFrame) {
        List<DetectionResult> results = new ArrayList<>();

        if (output2D == null || inputFrame == null) {
            return results;
        }

        int width = inputFrame.cols();
        int height = inputFrame.rows();

        for (float[] features : output2D) {
            if (features == null || features.length < 5) {
                continue;
            }

            // 1) En iyi sınıfı ve skorunu bul
            int bestClass = -1;
            float bestScore = 0f;
            for (int c = 4; c < features.length; c++) {
                if (features[c] > bestScore) {
                    bestScore = features[c];
                    bestClass = c - 4;
                }
            }

            // 2) Eşik kontrolü
            if (bestScore > CONFIDENCE_THRESHOLD) {
                // 3) Box koordinatlarını orijinal frame boyutuna ölçekle
                float xCenter = features[0] * width;
                float yCenter = features[1] * height;
                float boxW = features[2] * width;
                float boxH = features[3] * height;

                // 4) Detection nesnesini oluştur
                DetectionResult det = new DetectionResult(
                        bestClass,
                        getClassName(bestClass),
                        bestScore,
                        xCenter - boxW / 2, // x1
                        yCenter - boxH / 2, // y1
                        xCenter + boxW / 2, // x2
                        yCenter + boxH / 2  // y2
                );

                results.add(det);
            }
        }
        fetchDetections(results);
        return results;
    }
    private void fetchDetections(List<DetectionResult> detectedCategories) {
        List<DetectionResult> results = new ArrayList<>();
        for (DetectionResult d : detectedCategories) {
            //d.label;
        }
    }
    public List<float[]> runInference(Mat frame, Context context, LinearLayout layout) {
        int width = frame.cols(); // Orijinal frame genişliği
        int height = frame.rows(); // Orijinal frame yüksekliği
        List<float[]> outputList = new ArrayList<>();
        d = new ArrayList<>();
        try {
            // 2. Çıktı tensörünün şeklini al

            // 1. Giriş verisini hazırla
            Bitmap inputBitmap = matToBitmap(frame);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(inputBitmap);
            // 2. Çıktı tensörünün şeklini al ve uygun dizi oluştur
            Tensor outputTensor = interpreter.getOutputTensor(0);
            int[] outputShape = outputTensor.shape(); // [1, 84, 8400]
            // 3) Boyuta göre inferans
            int a = 0;
            if (outputShape.length == 2) {
                // 2D çıktı: [batch, features]
                float[][] output2D = new float[outputShape[0]][outputShape[1]];
                interpreter.run(inputBuffer, output2D);

                for (float[] features : output2D) {
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }

            } else if (outputShape.length == 3) {
                // 3D çıktı: [batch, featureCount, objectCount]
                float[][][] output3D = new float[outputShape[0]][outputShape[1]][outputShape[2]];
                Map<Integer, Object> outputs = new HashMap<>();
                outputs.put(0, output3D);
                interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

                // [featureCount, objectCount] → Listeye çevir
                // ... (önceki kodlar)
                for (int obj = 0; obj < outputShape[2]; obj++) {
                    float[] features = new float[outputShape[1]];
                    for (int feat = 0; feat < outputShape[1]; feat++) {
                        features[feat] = output3D[0][feat][obj];
                    }
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }

            } else if (outputShape.length == 4) {
                // 3D çıktı: [batch, featureCount, objectCount]
                float[][][][] output4D = new float[outputShape[0]][outputShape[1]][outputShape[2]][outputShape[3]];
                Map<Integer, Object> outputs = new HashMap<>();
                outputs.put(0, output4D);
                interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

                // [featureCount, objectCount] → Listeye çevir
                for (int obj = 0; obj < outputShape[3]; obj++) {
                    float[] features = new float[outputShape[1]];
                    for (int feat = 0; feat < outputShape[1]; feat++) {
                        features[feat] = output4D[0][feat][0][obj]; // Düzeltildi
                    }
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }
            }

            // UI güncellemesi via ThreadService
            ThreadService.getInstance().runOnMainThread(() -> updateDetectionList(d, layout, context));
            return outputList;
        } catch (Exception e) {
            Log.e(TAG, "Inference error 253: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    // Detection.java'ya yeni metod ekleyin
    public List<DetectionResult> runInferenceDet(Mat frame, Context context, LinearLayout layout) {
        int width = frame.cols();
        int height = frame.rows();
        d = new ArrayList<>();

        try {
            Bitmap inputBitmap = matToBitmap(frame);
            ByteBuffer inputBuffer = convertBitmapToByteBuffer(inputBitmap);
            Tensor outputTensor = interpreter.getOutputTensor(0);
            int[] outputShape = outputTensor.shape();

            if (outputShape.length == 2) {
                float[][] output2D = new float[outputShape[0]][outputShape[1]];
                interpreter.run(inputBuffer, output2D);

                for (float[] features : output2D) {
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }
            }
            else if (outputShape.length == 3) {
                float[][][] output3D = new float[outputShape[0]][outputShape[1]][outputShape[2]];
                Map<Integer, Object> outputs = new HashMap<>();
                outputs.put(0, output3D);
                interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

                for (int obj = 0; obj < outputShape[2]; obj++) {
                    float[] features = new float[outputShape[1]];
                    for (int feat = 0; feat < outputShape[1]; feat++) {
                        features[feat] = output3D[0][feat][obj];
                    }
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }
            }
            else if (outputShape.length == 4) {
                float[][][][] output4D = new float[outputShape[0]][outputShape[1]][outputShape[2]][outputShape[3]];
                Map<Integer, Object> outputs = new HashMap<>();
                outputs.put(0, output4D);
                interpreter.runForMultipleInputsOutputs(new Object[]{inputBuffer}, outputs);

                for (int obj = 0; obj < outputShape[3]; obj++) {
                    float[] features = new float[outputShape[1]];
                    for (int feat = 0; feat < outputShape[1]; feat++) {
                        features[feat] = output4D[0][feat][0][obj];
                    }
                    DetectionResult det = parseDetection(features, width, height);
                    if (det != null) d.add(det);
                }
            }

            ThreadService.getInstance().runOnMainThread(() -> updateDetectionList(d, layout, context));
            return d;
        } catch (Exception e) {
            Log.e(TAG, "Inference error 312: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    // Yardımcı metod: Ham çıktıyı DetectionResult'a dönüştürür
    private DetectionResult parseDetection(float[] features, int width, int height) {
        // En yüksek skorlu sınıfı bul
        int bestClass = -1;
        float bestScore = 0f;
        for (int c = 4; c < features.length; c++) {
            if (features[c] > bestScore) {
                bestScore = features[c];
                bestClass = c - 4; // İlk 4 eleman box koordinatları
            }
        }

        // Güven eşiğini kontrol et
        if (bestScore < CONFIDENCE_THRESHOLD) {
            return null;
        }

        // Bounding box koordinatlarını hesapla
        float xCenter = features[0] * width;
        float yCenter = features[1] * height;
        float boxW = features[2] * width;
        float boxH = features[3] * height;

        return new DetectionResult(
                bestClass,
                getClassName(bestClass), // Sınıf adını al
                bestScore,
                xCenter - boxW / 2, // x1
                yCenter - boxH / 2, // y1
                xCenter + boxW / 2, // x2
                yCenter + boxH / 2  // y2
        );
    }
    public Bitmap matToBitmapSafe(Mat src) {
        // 1) Girişin boyutlarını ve tipini kontrol edin
        if (src.empty()) {
            throw new IllegalArgumentException("Boş Mat gelmiş!");
        }

        // 2) RGBA 8-bit’e çevirin (src.channels()==3 ise; eğer zaten 4 ise klon alın)
        Mat rgba = new Mat();
        if (src.channels() == 4 && src.type() == CvType.CV_8UC4) {
            src.copyTo(rgba);
        } else if (src.channels() == 3) {
            Imgproc.cvtColor(src, rgba, Imgproc.COLOR_BGR2RGBA);
        } else if (src.channels() == 1) {
            Imgproc.cvtColor(src, rgba, Imgproc.COLOR_GRAY2RGBA);
        } else {
            throw new IllegalArgumentException("Beklenmeyen kanal sayısı: " + src.channels());
        }

        // 3) Bitmap oluşturup doldurun
        Bitmap bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, bmp);
        return bmp;
    }

    // Yardımcı metod: Mat -> Bitmap dönüşümü
    public Bitmap matToBitmap(Mat src) {
        // 1) Girişin boyutlarını ve tipini kontrol edin
        if (src.empty()) {
            throw new IllegalArgumentException("Boş Mat gelmiş!");
        }

        // 2) RGBA 8-bit’e çevirin (src.channels()==3 ise; eğer zaten 4 ise klon alın)
        Mat rgba = new Mat();
        if (src.channels() == 4 && src.type() == CvType.CV_8UC4) {
            src.copyTo(rgba);
        } else if (src.channels() == 3) {
            Imgproc.cvtColor(src, rgba, Imgproc.COLOR_BGR2RGBA);
        } else if (src.channels() == 1) {
            Imgproc.cvtColor(src, rgba, Imgproc.COLOR_GRAY2RGBA);
        } else {
            throw new IllegalArgumentException("Beklenmeyen kanal sayısı: " + src.channels());
        }

        // 3) Bitmap oluşturup doldurun
        Bitmap bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rgba, bmp);
        return bmp;
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
    public List<DetectionResult> getDets(@Nullable DetectionResult det) {
        if (det != null) {
            d.add(det);
        }
        return d;
    }
    public Mat drawDetections(Mat originalFrame, List<DetectionResult> detections) {
        // Orijinal çerçevenin kopyasını oluştur
        Mat resultFrame = originalFrame.clone();

        // Görüntü boyutlarını al
        int width = resultFrame.cols();
        int height = resultFrame.rows();

        for (DetectionResult detection : detections) {
            if (detection.getScore() < CONFIDENCE_THRESHOLD) continue;

            // Koordinatları al
            int x = (int) detection.getX1();
            int y = (int) detection.getY1();
            int right = (int) detection.getX2();
            int bottom = (int) detection.getY2();

            // Dikdörtgen çiz
            Imgproc.rectangle(
                    resultFrame,
                    new Point(x, y),
                    new Point(right, bottom),
                    GREEN,
                    BOX_THICKNESS
            );

            // Etiket yazdır
            String label = String.format("%s: %.2f", detection.getLabel(), detection.getScore());
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

    @SuppressLint("SetTextI18n")
    public void updateDetectionList(List<DetectionResult> detectedCategories, LinearLayout detectedSoundsLayout,Context context) {

        detectedSoundsLayout.removeAllViews(); // Clear previous results

        for (DetectionResult category : detectedCategories) {
            float confidence = category.getScore();
            if (confidence > 0.79) { // Only show confidence > 79%
                TextView textView = new TextView(context);
                textView.setText(category.getScore() + "---" + category.getClassId() + "---" + category.getLabel() + " - " + String.format("%.2f", confidence * 100) + "%");
                textView.setTextSize(16);
                textView.setTextColor(Color.WHITE);
                textView.setPadding(10, 10, 10, 10);

                detectedSoundsLayout.addView(textView);
            }
        }

        // If no high-confidence results, show "No strong detection"
        if (detectedSoundsLayout.getChildCount() == 0) {
            TextView noResultView = new TextView(context);
            noResultView.setText("No strong detections");
            noResultView.setTextSize(16);
            noResultView.setTextColor(Color.CYAN);
            noResultView.setPadding(10, 10, 10, 10);
            detectedSoundsLayout.addView(noResultView);
        }
    }
}
/** Sadece sonuçları tutmak için basit POJO */
class DetectionResult {
    public int    classId;
    public String label;
    public float  score;
    public float  x1, y1, x2, y2;

    public DetectionResult(int classId, String label, float score,
                           float x1, float y1, float x2, float y2) {
        this.classId = classId;
        this.label = label;
        this.score = score;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
    }

    public int    getClassId() { return classId; }
    public String getLabel()   { return label; }
    public float  getScore()   { return score; }
    public float  getX1()      { return x1; }
    public float  getY1()      { return y1; }
    public float  getX2()      { return x2; }
    public float  getY2()      { return y2; }
}