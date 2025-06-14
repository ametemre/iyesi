package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function; // Sınıf başına eklenmeli (eğer yoksa)

/**
 * Enhanced Ai class: Manages TensorFlow Lite models with thread-safe operations,
 * GPU acceleration, and robust error handling.
 */
public class Ai implements AutoCloseable {
    private static final String TAG = "AiModel";
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];
    private volatile boolean busy = false;
    private Context context;
    // Core components
    private final Interpreter videoInterpreter;
    private final Interpreter soundInterpreter;
    private TFLiteModelInspector tfLiteModelInspector;
    private final GpuDelegate gpuDelegate;
    private Threading threading;
    private ExecutorService executor;
    private final Handler mainHandler;

    // Model metadata
    private final int[] videoInputShape;
    private final int[] videoOutputShape;
    private final int soundOutputLength;
    // + Yeni eklenenler →
    private final List<String> labels;
    private final float       scoreThreshold;

    // Statik konfigürasyon (isterseniz JSON’dan da yükleyebilirsiniz)
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
        this.executor = Executors.newFixedThreadPool(2, r -> {Thread t = new Thread(r, "AiWorker-" + System.currentTimeMillis());t.setPriority(Thread.NORM_PRIORITY - 1); /* Slightly lower priority*/return t;});
        this.gpuDelegate = threading.initGpuDelegate(context);
        // Load models
        inspectModel(videoModelPath);
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
        //logModelTensorInfo();
    }
    private Interpreter.Options createInterpreterOptions(GpuDelegate delegate) {
        Interpreter.Options options = new Interpreter.Options()
                .setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        if (delegate != null) {
            options.addDelegate(delegate);
        }
        return options;
    }
    private int calculateOutputLength(Tensor tensor) {
        int[] shape = tensor.shape();
        int length = 1;
        for (int dim : shape) {
            length *= dim;
        }
        return length;
    }
    // --- Helper Methods ---
    private Interpreter initModel(AssetManager assets, String modelPath, Interpreter.Options options, Context context) throws IOException {
        if (modelPath == null || modelPath.isEmpty()) {
            return null;
        }

        // Load model once
        MappedByteBuffer modelBuffer = TFLiteModelInspector.loadModelFile(assets, modelPath);
        Interpreter interpreter = null;

        // Stage 1: Try with GPU
        GpuDelegate gpuDelegate = threading.initGpuDelegate(context);
        if (gpuDelegate != null) {
            try {
                Interpreter.Options gpuOptions = new Interpreter.Options(options);
                gpuOptions.addDelegate(gpuDelegate);
                interpreter = new Interpreter(modelBuffer, gpuOptions);
                Log.i(TAG, "Model loaded with GPU acceleration");
                return interpreter;
            } catch (Exception e) {
                Log.w(TAG, "GPU acceleration failed", e);
                gpuDelegate.close();
            }
        }

        // Stage 2: Try with NNAPI
        try {
            Interpreter.Options nnapiOptions = new Interpreter.Options(options);
            nnapiOptions.setUseNNAPI(true);
            interpreter = new Interpreter(modelBuffer, nnapiOptions);
            Log.i(TAG, "Model loaded with NNAPI");
            return interpreter;
        } catch (Exception e) {
            Log.w(TAG, "NNAPI acceleration failed", e);
        }

        // Stage 3: Fallback to CPU
        try {
            interpreter = new Interpreter(modelBuffer, options);
            interpreter.allocateTensors();
            Log.i(TAG, "Model loaded with CPU");
            return interpreter;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize model", e);
            throw new RuntimeException("Model initialization failed", e);
        }
    }
    public void inspectModel(String videoModelPath) {
        try {
            MappedByteBuffer modelBuf =
                    TFLiteModelInspector.loadModelFile(context.getAssets(), videoModelPath);
            Interpreter tflite = new Interpreter(modelBuf);
            Log.i(TAG, "Output tensor count: " + tflite.getOutputTensorCount());
            for (int i = 0; i < tflite.getOutputTensorCount(); i++) {
                Tensor t = tflite.getOutputTensor(i);
                Log.i(TAG,
                        String.format("[%d] name=%s shape=%s type=%s",
                                i,
                                t.name(),
                                Arrays.toString(t.shape()),
                                t.dataType().name()
                        )
                );
            }
            tflite.close();
        } catch (IOException e) {
            Log.e(TAG, "Model inspection failed", e);
        }
    }




    public void predictVideo(@NonNull float[][][][] input, @NonNull Consumer<float[][][]> callback) {
        try {
            executor.execute(() -> {
                try {
                    float[][][] output = tfLiteModelInspector.processVideoInput(input,videoInterpreter);
                    mainHandler.post(() -> callback.accept(output));
                } catch (Exception e) {
                    Log.e(TAG, "Video prediction failed", e);
                    mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
                }
            });
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "predictVideo: executor shut down, skipping task", e);
            // İstersen callback’e boş bir çıktı dönebilirsin:
            mainHandler.post(() -> callback.accept(EMPTY_VIDEO_OUTPUT));
        } catch (Exception e) {
            Log.w(TAG, "predictVideo: executor crashed, skipping task", e);
            throw new RuntimeException(e);
        }
    }
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

    // --- Getters ---
    public Interpreter getVideoInterpreter() {
        if (videoInterpreter != null) {
            return videoInterpreter;
        }
        return videoInterpreter;
    }
    public Interpreter getSoundInterpreter() {
        if (soundInterpreter != null) {
            return soundInterpreter;
        }
        return soundInterpreter;
    }
    public ExecutorService getExecutor() {
        if (executor != null) {
            return executor;
        }
        return executor;
    }
    public GpuDelegate getGpuDelegate() {
        if (gpuDelegate != null) {
            return gpuDelegate;
        }
        return gpuDelegate;
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
    public int getInputWidth() {
        return videoInputShape.length >= 3 ? videoInputShape[2] : 0;
    }
    public int getInputHeight() {
        return videoInputShape.length >= 2 ? videoInputShape[1] : 0;
    }
    public List<String> getLabels() {
        return labels;
    }
    // --- Resource Management ---
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (videoInterpreter != null) videoInterpreter.close();
        if (soundInterpreter != null) soundInterpreter.close();
        if (gpuDelegate != null) gpuDelegate.close();

        Log.i(TAG, "Resources released");
    }
}
