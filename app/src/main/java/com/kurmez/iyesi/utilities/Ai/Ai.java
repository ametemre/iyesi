package com.kurmez.iyesi.utilities.Ai;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Consumer;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.support.common.FileUtil;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.MappedByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Ai class: Manages TensorFlow Lite models with thread-safe operations,
 * GPU acceleration, and robust error handling.
 */
public class Ai implements AutoCloseable {
    private static final String TAG = "AiModel";
    private static final float[][][] EMPTY_VIDEO_OUTPUT = new float[0][0][0];

    // Core components
    private final Interpreter videoInterpreter;
    private final Interpreter soundInterpreter;
    private final GpuDelegate gpuDelegate;
    private final ExecutorService executor;
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
            "yolov8n.tflite",      "coco_labels.txt",
            "DogBreed.tflite",     "dogbreed_labels.txt",
            "MyCustom.tflite",     "custom_labels.txt"
    );
    private static final Map<String, Float> MODEL_THRESHOLDS = Map.of(
            "yolov8n.tflite",      0.5f,
            "DogBreed.tflite",     0.3f,
            "MyCustom.tflite",     0.4f
    );
    public Ai(
            @NonNull Context context,
            @Nullable String soundModelPath,
            @Nullable String videoModelPath,
            @Nullable String labelsPath
    ) throws IOException {
        // Initialize core components
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AiWorker-" + System.currentTimeMillis());
            t.setPriority(Thread.NORM_PRIORITY - 1); // Slightly lower priority
            return t;
        });

        // Configure GPU delegate
        this.gpuDelegate = initGpuDelegate(context);
        Interpreter.Options options = createInterpreterOptions(gpuDelegate);

        // Load models
        AssetManager assets = context.getAssets();
        this.videoInterpreter = initModel(assets, videoModelPath, options, context);
        this.soundInterpreter = initModel(assets, soundModelPath, options, context);

        // Extract model metadata
        this.videoInputShape = videoInterpreter != null ?
                videoInterpreter.getInputTensor(0).shape() : new int[0];
        this.videoOutputShape = videoInterpreter != null ?
                videoInterpreter.getOutputTensor(0).shape() : new int[0];
        this.soundOutputLength = soundInterpreter != null ?
                calculateOutputLength(soundInterpreter.getOutputTensor(0)) : 0;
        // + Label dosyasını belirle ve yükle
        String labelFile = labelsPath != null
                ? labelsPath
                : MODEL_LABEL_FILES.getOrDefault(videoModelPath, "coco_labels.txt");
// Eğer labelFile assets altında ise:
// Ya da InputStream versiyonu:
        this.labels = FileUtil.loadLabels(
                context.getAssets().open(labelFile),
                Charset.forName("UTF-8")
        );

        // + Eşik değerini al
        this.scoreThreshold = MODEL_THRESHOLDS.getOrDefault(videoModelPath, 0.5f);
        logModelDetails();
    }
    public List<String> getLabelsAboveThreshold(List<Detection> detections, float threshold) {
        List<String> labels = new ArrayList<>();
        for (Detection d : detections) {
            if (d.score > threshold) {
                labels.add(d.label);
            }
        }
        return labels;
    }
    // + Yardımcı getter’lar
    public List<String> getLabels() {
        return labels;
    }
    public float getScoreThreshold() {
        return scoreThreshold;
    }
    private GpuDelegate initGpuDelegate(Context context) {
        try {
            // Skip GPU on known problematic devices
            if (shouldSkipGpuForDevice()) {
                Log.w(TAG, "Skipping GPU for this device model");
                return null;
            }
            // Check GPU compatibility
            CompatibilityList compatList = new CompatibilityList();
            if (!compatList.isDelegateSupportedOnThisDevice()) {
                Log.w(TAG, "GPU delegate not supported on this device");
                return null;
            }

            // Create GPU delegate with options compatible with 2.12.0
            GpuDelegate.Options options = new GpuDelegate.Options();

            // Set options using reflection to maintain compatibility
            try {
                // These methods were introduced in later versions but we try to use them if available
                Method setPrecisionMethod = GpuDelegate.Options.class.getMethod("setPrecisionLossAllowed", boolean.class);
                setPrecisionMethod.invoke(options, true);

                // Inference preference setting (2.12.0 uses different constants)
                Method setInferencePrefMethod = GpuDelegate.Options.class.getMethod("setInferencePreference", int.class);
                Field sustainedSpeedField = GpuDelegate.Options.class.getField("INFERENCE_PREFERENCE_SUSTAINED_SPEED");
                setInferencePrefMethod.invoke(options, sustainedSpeedField.getInt(null));
            } catch (NoSuchMethodException | NoSuchFieldException e) {
                Log.d(TAG, "Advanced GPU options not available in this version, using defaults");
            } catch (Exception e) {
                Log.w(TAG, "Error setting GPU options", e);
            }

            // Try to create delegate
            GpuDelegate delegate = new GpuDelegate(options);
            // Simple test to verify delegate works
            try {
                Log.i(TAG, "GPU delegate initialized successfully");
                Interpreter.Options interpreterOptions = new Interpreter.Options();
                interpreterOptions.addDelegate(delegate);
                // Test with a tiny model if possible
                return delegate;
            } catch (Exception testException) {
                delegate.close();
                Log.w(TAG, "GPU delegate failed basic test", testException);
                return null;
            }

        } catch (Exception e) {
            Log.w(TAG, "GPU acceleration unavailable", e);
            return null;
        }
    }
    private boolean shouldSkipGpuForDevice() {
        // Add problematic device patterns here
        String[] problematicPatterns = {
                "Xiaomi", "Redmi", "Poco",  // Example problematic brands
                "M2004J19C"                 // Example problematic model
        };

        String model = Build.MODEL.toLowerCase();
        String manufacturer = Build.MANUFACTURER.toLowerCase();

        for (String pattern : problematicPatterns) {
            if (model.contains(pattern.toLowerCase()) ||
                    manufacturer.contains(pattern.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    private Interpreter.Options createInterpreterOptions(GpuDelegate delegate) {
        Interpreter.Options options = new Interpreter.Options()
                .setNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        if (delegate != null) {
            options.addDelegate(delegate);
        }
        return options;
    }

    private Interpreter initModel(AssetManager assets, String modelPath,
                                  Interpreter.Options options, Context context)
            throws IOException {
        if (modelPath == null || modelPath.isEmpty()) {
            return null;
        }

        // Load model once
        MappedByteBuffer modelBuffer = TFLiteModelInspector.loadModelFile(assets, modelPath);
        Interpreter interpreter = null;

        // Stage 1: Try with GPU
        GpuDelegate gpuDelegate = initGpuDelegate(context);
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
    private int calculateOutputLength(Tensor tensor) {
        int[] shape = tensor.shape();
        int length = 1;
        for (int dim : shape) {
            length *= dim;
        }
        return length;
    }

    // --- Public API ---
    public void predictVideo(
            @NonNull float[][][][] input,
            @NonNull Consumer<float[][][]> callback
    ) {
        try {
            executor.execute(() -> {
                try {
                    float[][][] output = processVideoInput(input);
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

    public float[][][] predictVideoSync(float[][][][] input) {
        if (videoInterpreter == null || !validateVideoInput(input)) {
            return EMPTY_VIDEO_OUTPUT;
        }

        try {
            float[][][] output = new float[videoOutputShape[0]][videoOutputShape[1]][videoOutputShape[2]];
            videoInterpreter.run(input, output);
            return output;
        } catch (Exception e) {
            Log.e(TAG, "Sync video prediction failed", e);
            return EMPTY_VIDEO_OUTPUT;
        }
    }

    public void predictSound(
            @NonNull float[][] input,
            @NonNull Consumer<float[]> callback
    ) {
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

    // --- Helper Methods ---
    private float[][][] processVideoInput(float[][][][] input) {
        if (!validateVideoInput(input)) {
            return EMPTY_VIDEO_OUTPUT;
        }

        float[][][] output;
        switch (videoOutputShape.length) {
            case 3:
                output = new float[videoOutputShape[0]][videoOutputShape[1]][videoOutputShape[2]];
                videoInterpreter.run(input, output);
                break;
            case 2:
                float[][] tmp = new float[videoOutputShape[0]][videoOutputShape[1]];
                videoInterpreter.run(input, tmp);
                output = convert2DTo3D(tmp);
                break;
            default:
                Log.e(TAG, "Unsupported output shape rank: " + videoOutputShape.length);
                output = EMPTY_VIDEO_OUTPUT;
        }
        return output;
    }

    private boolean validateVideoInput(float[][][][] input) {
        if (videoInterpreter == null) return false;
        if (input.length != videoInputShape[0]) return false;
        if (input[0].length != videoInputShape[1]) return false;
        if (input[0][0].length != videoInputShape[2]) return false;
        if (input[0][0][0].length != videoInputShape[3]) return false;
        return true;
    }

    private float[][][] convert2DTo3D(float[][] input) {
        float[][][] output = new float[input.length][1][input[0].length];
        for (int i = 0; i < input.length; i++) {
            System.arraycopy(input[i], 0, output[i][0], 0, input[i].length);
        }
        return output;
    }

    private void logModelDetails() {
        if (videoInterpreter != null) {
            Log.i(TAG, String.format(
                    "Video Model: Input=%s, Output=%s",
                    Arrays.toString(videoInputShape),
                    Arrays.toString(videoOutputShape)
            ));
        }
        if (soundInterpreter != null) {
            Log.i(TAG, "Sound Model Output Length: " + soundOutputLength);
        }
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

    // --- Getters ---
    public int getInputWidth() {
        return videoInputShape.length >= 3 ? videoInputShape[2] : 0;
    }

    public int getInputHeight() {
        return videoInputShape.length >= 2 ? videoInputShape[1] : 0;
    }

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
}