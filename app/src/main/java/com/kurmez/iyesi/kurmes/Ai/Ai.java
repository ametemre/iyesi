package com.kurmez.iyesi.kurmes.Ai;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Ai {
    private static final String TAG = "Ai";
    private final Interpreter soundInterpreter;
    private final Interpreter videoInterpreter;
    private GpuDelegate gpuDelegate;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** 
     * @param context Android context for loading assets
     * @param soundModelAsset  path under assets/ (e.g. "sound.tflite")
     * @param videoModelAsset  path under assets/ (e.g. "liveVideo.tflite")
     */
    public Ai(Context context, String soundModelAsset, String videoModelAsset) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        // Try GPU delegate, fallback silently to CPU
        try {
            gpuDelegate = new GpuDelegate();
            options.addDelegate(gpuDelegate);
        } catch (Exception e) {
            Log.w(TAG, "GPU delegate not available, using CPU", e);
        }

        MappedByteBuffer soundBuf = loadModel(context, soundModelAsset);
        MappedByteBuffer videoBuf = loadModel(context, videoModelAsset);

        soundInterpreter = new Interpreter(soundBuf, options);
        videoInterpreter = new Interpreter(videoBuf, options);
    }

    private MappedByteBuffer loadModel(Context ctx, String assetPath) throws IOException {
        AssetFileDescriptor afd = ctx.getAssets().openFd(assetPath);
        try (FileInputStream fis = new FileInputStream(afd.getFileDescriptor())) {
            FileChannel fc = fis.getChannel();
            return fc.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getLength());
        }
    }

    /** 
     * Run sound model on background thread.
     * @param input 2D float array shaped [1][feature_length]
     * @param callback invoked with result array when done
     */
    public void predictSound(float[][] input, ResultCallback<float[]> callback) {
        executor.execute(() -> {
            float[] output = allocateOutput(soundInterpreter);
            try {
                soundInterpreter.run(input, output);
            } catch (Exception e) {
                Log.e(TAG, "Sound inference error", e);
            }
            callback.onResult(output);
        });
    }

    /** 
     * Run video model on background thread.
     * @param input 4D float array shaped [1][h][w][3]
     * @param callback invoked with result array when done
     */
    public void predictVideo(float[][][][] input, ResultCallback<float[][]> callback) {
        executor.execute(() -> {
            float[][] output = allocate2DOutput(videoInterpreter);
            try {
                videoInterpreter.run(input, output);
            } catch (Exception e) {
                Log.e(TAG, "Video inference error", e);
            }
            callback.onResult(output);
        });
    }

    private float[] allocateOutput(Interpreter interp) {
        int[] shape = interp.getOutputTensor(0).shape(); // e.g. [1, numClasses]
        int length = Arrays.stream(shape).reduce(1, (a, b) -> a * b);
        return new float[length];
    }

    private float[][] allocate2DOutput(Interpreter interp) {
        int[] shape = interp.getOutputTensor(0).shape(); // e.g. [1, N, M]
        return new float[shape[0]][shape[1]];
    }

    /** 
     * Close interpreters and delegates to free native resources.
     */
    public void close() {
        try {
            soundInterpreter.close();
            videoInterpreter.close();
            if (gpuDelegate != null) {
                gpuDelegate.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "Error closing interpreters or delegates", e);
        }
        executor.shutdown();
    }

    /** Callback interface for async results */
    public interface ResultCallback<T> {
        void onResult(T result);
    }
}
