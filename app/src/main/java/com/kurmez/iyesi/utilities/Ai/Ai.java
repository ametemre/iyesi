package com.kurmez.iyesi.utilities.Ai;

import static com.kurmez.iyesi.utilities.Ai.Threading.initGpuDelegate;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class Ai implements AutoCloseable {
    private static final String TAG = "AiModel";
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];
    private final Context context;
    private final Interpreter videoInterpreter;
    private final Interpreter soundInterpreter;
    private Interpreter interpreter = null;
    private GpuDelegate gpuDelegate;
    private final ExecutorService executor;
    private final Handler mainHandler;
    TFLiteModelInspector tfLiteModelInspector;
    private final int[] videoInputShape;
    private final int[] videoOutputShape;
    private final int soundOutputLength;
    private final List<String> labels;
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
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AiWorker-" + System.currentTimeMillis());
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        });
        this.gpuDelegate = initGpuDelegate(context);
        this.tfLiteModelInspector = new TFLiteModelInspector();

        AssetManager assets = context.getAssets();
        Interpreter.Options options = createInterpreterOptions(gpuDelegate);

        this.videoInterpreter = initModel(assets, videoModelPath, options, context);
        this.soundInterpreter = initModel(assets, soundModelPath, options, context);

        this.videoInputShape = videoInterpreter != null ? videoInterpreter.getInputTensor(0).shape() : new int[0];
        this.videoOutputShape = videoInterpreter != null ? videoInterpreter.getOutputTensor(0).shape() : new int[0];
        this.soundOutputLength = soundInterpreter != null ? calculateOutputLength(soundInterpreter.getOutputTensor(0)) : 0;

        String labelFile = labelsPath != null ? labelsPath : MODEL_LABEL_FILES.getOrDefault(videoModelPath, "coco_labels.txt");
        this.labels = FileUtil.loadLabels(context.getAssets().open(labelFile), Charset.forName("UTF-8"));
        this.scoreThreshold = MODEL_THRESHOLDS.getOrDefault(videoModelPath, 0.5f);
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
            MappedByteBuffer modelBuffer = TFLiteModelInspector.loadModelFile(assets, modelPath);

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
        if (executor.isShutdown() || executor.isTerminated()) {
            Log.w(TAG, "predictVideo: executor closed, skipping task");
            mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    // Kapanma sırasında başka task olmasın:
                    if (videoInterpreter == null) {
                        Log.e(TAG, "Interpreter kapalı");
                        mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
                        return;
                    }
                    float[][][] output;
                    synchronized (videoInterpreter) {
                        output = tfLiteModelInspector.processVideoInput(input, videoInterpreter);
                    }
                    mainHandler.post(() -> callback.accept(output));
                } catch (Exception e) {
                    Log.e(TAG, "Video prediction failed", e);
                    mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "predictVideo: executor shut down, skipping task", e);
            mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
        }
    }

    // Inference (sound)
    public void predictSound(@NonNull float[][] input, @NonNull Consumer<float[]> callback) {
        executor.execute(() -> {
            try {
                float[] output = new float[soundOutputLength];
                soundInterpreter.run(input, output);
                mainHandler.post(() -> callback.accept(output));
            } catch (Exception e) {
                Log.e(TAG, "Sound prediction failed", e);
                mainHandler.post(() -> callback.accept(new float[0]));
            }
        });
    }
    public String getInputShapeInfo(Interpreter interpreter) {
        if (interpreter == null) return "interpreter=null";
        StringBuilder sb = new StringBuilder();
        int inputCount = interpreter.getInputTensorCount();
        for (int i = 0; i < inputCount; i++) {
            sb.append("Input[").append(i).append("] shape=")
                    .append(java.util.Arrays.toString(interpreter.getInputTensor(i).shape()))
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
                    .append(java.util.Arrays.toString(interpreter.getOutputTensor(i).shape()))
                    .append(" type=").append(interpreter.getOutputTensor(i).dataType().name())
                    .append("; ");
        }
        return sb.toString();
    }
    // Getter'lar
    public Interpreter getVideoInterpreter() { return videoInterpreter; }
    public Interpreter getSoundInterpreter() { return soundInterpreter; }
    public ExecutorService getExecutor() { return executor; }
    public GpuDelegate getGpuDelegate() { return gpuDelegate; }
    public int getInputWidth() { return videoInputShape.length >= 3 ? videoInputShape[2] : 0; }
    public int getInputHeight() { return videoInputShape.length >= 2 ? videoInputShape[1] : 0; }
    public List<String> getLabels() { return labels; }

    // Kaynak temizliği
    @Override
    public void close() {
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
}
