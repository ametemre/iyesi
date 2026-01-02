package com.kurmez.iyesi.kurmes.utilities.Ai;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.label.Category;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class VideoClassifier {
    private static final String TAG = "VideoClassifier";
    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String modelPath;
    private final float probabilityThreshold;
    private final OnClassificationResultListener resultListener;
    private final Ai ai;

    private Interpreter interpreter;
    private List<String> labels;
    private int inputWidth;
    private int inputHeight;
    private TensorImage inputImageBuffer;
    private ImageProcessor imageProcessor;

    public interface OnClassificationResultListener {
        void onResult(String result);
    }

    public VideoClassifier(Context context,
                           Ai ai,
                           String modelPath,
                           float probabilityThreshold,
                           OnClassificationResultListener listener) {
        this.context = context.getApplicationContext();
        this.ai = ai;
        this.modelPath = modelPath;
        this.probabilityThreshold = probabilityThreshold;
        this.resultListener = listener;
        initializeModel();
    }

    private String processResults(float[] probabilities) {
        // Find top results
        PriorityQueue<Category> pq = new PriorityQueue<>(
                Math.max(1, (int) (probabilities.length * 0.25)),
                Comparator.comparingDouble(Category::getScore).reversed()
        );

        for (int i = 0; i < probabilities.length; i++) {
            if (probabilities[i] > probabilityThreshold) {
                String label = (i < labels.size()) ? labels.get(i) : "Class " + i;
                pq.add(new Category(label, probabilities[i]));
            }
        }

        // Build result string
        StringBuilder result = new StringBuilder();
        int count = 0;
        while (!pq.isEmpty() && count < 3) {
            Category category = pq.poll();
            result.append(category.getLabel())
                    .append(": ")
                    .append(String.format("%.1f", category.getScore() * 100))
                    .append("%\n");
            count++;
        }

        return result.length() > 0 ? result.toString() : "No detections";
    }

    public void classifyFrame(Bitmap frame) {
        if (interpreter == null) {
            Log.w(TAG, "Classifier not initialized yet");
            return;
        }

        new Thread(() -> {
            try {
                // Preprocess image
                inputImageBuffer.load(frame);
                inputImageBuffer = imageProcessor.process(inputImageBuffer);
                ByteBuffer inputBuffer = inputImageBuffer.getBuffer();

                // Run inference
                TensorBuffer outputBuffer = TensorBuffer.createFixedSize(
                        interpreter.getOutputTensor(0).shape(),
                        interpreter.getOutputTensor(0).dataType()
                );
                interpreter.run(inputBuffer, outputBuffer.getBuffer().rewind());

                // Process results
                float[] probabilities = outputBuffer.getFloatArray();
                String result = processResults(probabilities);

                // Send to main thread
                mainHandler.post(() -> {
                    if (resultListener != null) {
                        resultListener.onResult(result);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Classification error", e);
                mainHandler.post(() -> {
                    if (resultListener != null) {
                        resultListener.onResult("Classification error");
                    }
                });
            }
        }).start();
    }
    private void initializeModel() {
        new Thread(() -> {
            try {
                // Load TFLite model
                ByteBuffer model = FileUtil.loadMappedFile(context, modelPath);
                Interpreter.Options options = new Interpreter.Options();
                options.setNumThreads(Runtime.getRuntime().availableProcessors());
                interpreter = new Interpreter(model, options);

                // Get input tensor shape
                Tensor inputTensor = interpreter.getInputTensor(0);
                int[] inputShape = inputTensor.shape();
                inputHeight = inputShape[1];
                inputWidth = inputShape[2];
                DataType inputType = inputTensor.dataType();

                // Create image processor
                imageProcessor = new ImageProcessor.Builder()
                        .add(new ResizeWithCropOrPadOp(inputHeight, inputWidth))
                        .add(new ResizeOp(inputHeight, inputWidth, ResizeOp.ResizeMethod.BILINEAR))
                        .add(new NormalizeOp(0f, 255f)) // Normalize to [0,1]
                        .build();

                // Initialize input buffer
                inputImageBuffer = new TensorImage(inputType);

                // Load labels
                try {
                    labels = FileUtil.loadLabels(context, ai.getLabelsPath());
                } catch (Exception e) {
                    Log.e(TAG, "Label file not found, using default", e);
                    labels = Collections.emptyList();
                }

                Log.i(TAG, "Model initialized. Input size: " + inputWidth + "x" + inputHeight);
            } catch (IOException e) {
                Log.e(TAG, "Model initialization failed", e);
                mainHandler.post(() -> {
                    if (resultListener != null) {
                        resultListener.onResult("Model load error");
                    }
                });
            }
        }).start();
    }
    public void close() {
        if (interpreter != null) {
            interpreter.close();
            interpreter = null;
        }
    }
}