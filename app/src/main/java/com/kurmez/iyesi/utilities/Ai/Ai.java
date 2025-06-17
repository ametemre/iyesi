package com.kurmez.iyesi.utilities.Ai;

import static com.kurmez.iyesi.utilities.delegate.Threading.initGpuDelegate;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import com.kurmez.iyesi.utilities.delegate.TFLiteModelInspector;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Ai implements AutoCloseable {
    private static final String TAG = "AiModel";
    // Sınıf seviyesinde (Ai.java içinde):
    private MappedByteBuffer modelBuffer;
    private Interpreter videoInterpreter;
    private GpuDelegate gpuDelegate;
    private float[][][] outputBuffer;          // Örn: new float[...][...][...]
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    //private final ExecutorService executor;
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];
    private final Context context;
    private final Interpreter soundInterpreter;
    private Interpreter interpreter = null;
    private final int[] videoInputShape;
    private final int[] videoOutputShape;
    private final int soundOutputLength;
    private final List<String> labels;
    private String labelsPath;
    public String getLabelsPath(){
        return labelsPath;
    }
    private String path;
    public String getPath(){
        return path;
    }
    private final float scoreThreshold;
    private float[][][] output;
    // Statik model path → label ve threshold eşlemeleri
    private static final Map<String, String> MODEL_LABEL_FILES = Map.of(
            "yolov8n.tflite",                               "coco_labels.txt",
            "DogBreed.tflite",                              "dogbreed_labels.txt",
            "MyCustom.tflite",                              "custom_labels.txt",
            "ml_model/dog/dog/DogBreedLabels.tflite",       "ml_model/dog/dog/DogBreedLabels.txt",
            "ml_model/animal_ml_model.tflite",              "ml_model/animal_ml_model_labels.txt",
            "ml_model/dump/yamnet_classification.tflite",   "ml_model/dump/labelmap.txt",
            "ml_model/dump/mobilenet_v2.tflite",            "ml_model/dump/labels.txt"
    );
    private static final Map<String, Float> MODEL_THRESHOLDS = Map.of(
            "yolov8n.tflite",      0.5f,
            "DogBreed.tflite",     0.3f,
            "MyCustom.tflite",     0.4f,
            "ml_model/dump/mobilenet_v2.tflite", 0.5f,
            "ml_model/dump/yamnet_classification.tflite", 0.6f,
            "ml_model/animal_ml_model.tflite", 0.7f,
            "ml_model/dog/dog/DogBreedLabels.tflite",0.8f
    );

    public Ai(@NonNull Context context, @Nullable String soundModelPath, @Nullable String videoModelPath, @Nullable String labelsPath) throws IOException {
        this.context = context;
/*        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AiWorker-" + System.currentTimeMillis());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.gpuDelegate = initGpuDelegate(context);*/
        AssetManager assets = context.getAssets();
        Interpreter.Options options = createInterpreterOptions(gpuDelegate);

        this.videoInterpreter = initModel(assets, videoModelPath, options, context);
        this.soundInterpreter = initModel(assets, soundModelPath, options, context);

        this.videoInputShape = videoInterpreter != null ? videoInterpreter.getInputTensor(0).shape() : new int[0];
        this.path = videoModelPath;
        this.videoOutputShape = videoInterpreter != null ? videoInterpreter.getOutputTensor(0).shape() : new int[0];
        this.soundOutputLength = soundInterpreter != null ? calculateOutputLength(soundInterpreter.getOutputTensor(0)) : 0;

        String labelFile = labelsPath != null ? labelsPath : MODEL_LABEL_FILES.getOrDefault(videoModelPath, "coco_labels.txt");
        this.labels = FileUtil.loadLabels(context.getAssets().open(labelFile), Charset.forName("UTF-8"));
        this.scoreThreshold = MODEL_THRESHOLDS.getOrDefault(videoModelPath, 0.5f);
        this.labelsPath = labelsPath;
    }

    private Interpreter.Options createInterpreterOptions(GpuDelegate delegate) {
        Interpreter.Options options = new Interpreter.Options().setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        if (delegate != null) options.addDelegate(delegate);
        return options;
    }
    private int calculateOutputLength(Tensor tensor) {
        int[] shape = tensor.shape();
        int length = 1;
        for (int dim : shape) length *= dim;
        return length;
    }
    private Interpreter initModel(AssetManager assets, String modelPath, Interpreter.Options baseOptions, Context context) {
        if (modelPath == null || modelPath.isEmpty()) return null;
        try {
            this.modelBuffer = TFLiteModelInspector.loadModelFile(assets, modelPath);

            // Try GPU first
            try {
                gpuDelegate = initGpuDelegate(context);
                if (gpuDelegate != null) {
                    Interpreter.Options gpuOptions = new Interpreter.Options();
                    gpuOptions.addDelegate(gpuDelegate);
                    interpreter = new Interpreter(modelBuffer, gpuOptions);
                    lastUsedDelegate = "GPU";
                    Log.i(TAG, "Model loaded with GPU delegate");
                    return interpreter;
                }
            } catch (Exception e) {
                Log.w(TAG, "GPU delegate failed, try NNAPI", e);
                if (gpuDelegate != null) gpuDelegate.close();
            }

            // Try NNAPI
            try {
                Interpreter.Options nnapiOptions = new Interpreter.Options();
                nnapiOptions.setUseNNAPI(true);
                interpreter = new Interpreter(modelBuffer, nnapiOptions);
                lastUsedDelegate = "NNAPI";
                Log.i(TAG, "Model loaded with NNAPI delegate");
                return interpreter;
            } catch (Exception e) {
                Log.w(TAG, "NNAPI failed, try CPU", e);
            }

            // Last resort: CPU
            try {
                interpreter = new Interpreter(modelBuffer, baseOptions);
                lastUsedDelegate = "CPU";
                Log.i(TAG, "Model loaded with CPU");
                return interpreter;
            } catch (Exception e) {
                Log.e(TAG, "CPU load failed", e);
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Asset load failed", e);
            return null;
        }
    }

    // Inference (video/frame)
    public void predictVideo(final float[][][][] input, final Consumer<float[][][]> callback) {
/*        if (executor.isShutdown() || executor.isTerminated()) {
            Log.w(TAG, "predictVideo: executor kapalı, atlanıyor");
            callback.accept(EMPTY_VIDEO_OUTPUT);
            return;
        }
        Log.d(TAG, "-predictVideo- çağırıldı...");
        executor.execute(() -> {*/
            try {
                // 1. GPU ile dene
                synchronized (videoInterpreter) {
                    this.videoInterpreter.run(input, outputBuffer);
                }
                Log.d(TAG, "Inference GPU ile tamamlandı.");
            } catch (Exception gpuEx) {
                Log.w(TAG, "GPU inference başarısız, CPU'ya geçiliyor", gpuEx);

                // 2. GPU delegate kapat / interpreter yenile
                try {
                    videoInterpreter.close();
                    if (gpuDelegate != null) {
                        gpuDelegate.close();
                        gpuDelegate = null;
                    }
                } catch (Exception ignore) { /* zaten kapanmış olabilir */ }

                // 3. CPU-only interpreter oluştur
                Interpreter.Options cpuOpts = new Interpreter.Options()
                        .setNumThreads(Runtime.getRuntime().availableProcessors());
                videoInterpreter = new Interpreter(modelBuffer, cpuOpts);

                // 4. CPU ile tekrar dene
                try {
                    synchronized (videoInterpreter) {
                        videoInterpreter.run(input, outputBuffer);
                    }
                    Log.d(TAG, "Inference CPU ile tamamlandı.");
                } catch (Exception cpuEx) {
                    Log.e(TAG, "CPU inference de başarısız oldu", cpuEx);
                    outputBuffer = EMPTY_VIDEO_OUTPUT;  // Tamamen başarısızsa boş çıktı
                }
            }

            // 5. Sonucu UI thread’e yolla
            mainHandler.post(() -> callback.accept(outputBuffer));
        //});
    }

    // Inference (sound)
    public void predictSound(@NonNull float[][] input, @NonNull Consumer<float[]> callback) {
        //executor.execute(() -> {
            try {
                float[] output = new float[soundOutputLength];
                soundInterpreter.run(input, output);
                mainHandler.post(() -> callback.accept(output));
            } catch (Exception e) {
                Log.e(TAG, "Sound prediction failed", e);
                mainHandler.post(() -> callback.accept(new float[0]));
            }
        //});
    }
    public String getInputShapeInfo(Interpreter interpreter) {
        if (interpreter == null) return "interpreter=null";
        StringBuilder sb = new StringBuilder();
        int inputCount = interpreter.getInputTensorCount();
        for (int i = 0; i < inputCount; i++) {
            sb.append("Input[").append(i).append("] shape=")
                    .append(Arrays.toString(interpreter.getInputTensor(i).shape()))
                    .append(" type=").append(interpreter.getInputTensor(i).dataType().name())
                    .append("; ");
        }
        return sb.toString();
    }
    public String getOutputShapeInfo(Interpreter interpreter) {
        if (interpreter == null) return "interpreter=null";
        StringBuilder sb = new StringBuilder();
        int outputCount = interpreter.getOutputTensorCount();
        for (int i = 0; i < outputCount; i++) {
            sb.append("Output[").append(i).append("] shape=")
                    .append(Arrays.toString(interpreter.getOutputTensor(i).shape()))
                    .append(" type=").append(interpreter.getOutputTensor(i).dataType().name())
                    .append("; ");
        }
        return sb.toString();
    }
    // Getter'lar
    public Interpreter getVideoInterpreter() { return videoInterpreter; }
    public Interpreter getSoundInterpreter() { return soundInterpreter; }
    //public ExecutorService getExecutor() { return executor; }
    public GpuDelegate getGpuDelegate() { return gpuDelegate; }
    public int getInputWidth() { return videoInputShape.length >= 3 ? videoInputShape[2] : 0; }
    public int getInputHeight() { return videoInputShape.length >= 2 ? videoInputShape[1] : 0; }
    public List<String> getLabels() { return labels; }

    // Kaynak temizliği
    @Override
    public void close() {
        /*
        // Executor'u güvenli şekilde kapat
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
*/
        // Model interpreter’ları kapat
        try {
            if (videoInterpreter != null) videoInterpreter.close();
        } catch (Exception e) {
            Log.w(TAG, "videoInterpreter kapatılamadı", e);
        }
        try {
            if (soundInterpreter != null) soundInterpreter.close();
        } catch (Exception e) {
            Log.w(TAG, "soundInterpreter kapatılamadı", e);
        }
        try {
            if (gpuDelegate != null) gpuDelegate.close();
        } catch (Exception e) {
            Log.w(TAG, "gpuDelegate kapatılamadı", e);
        }

        // Tüm referansları null’la
        // (Bunlar opsiyonel ama erişim hatalarını azaltır)
        // executor = null;
        // videoInterpreter = null;
        // soundInterpreter = null;
        // gpuDelegate = null;

        Log.i(TAG, "Resources released");
    }
    private String lastUsedDelegate = "NONE";
    public String getLastUsedDelegate() { return lastUsedDelegate; }

    public Interpreter getVideoInterpereter() {
        return videoInterpreter;
    }
}
