package com.kurmez.iyesi.utilities;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Terminator {
    private static final String TAG = "Terminator";

    // Dependencies to be injected
    private final ExecutorService executor;
    private final Interpreter videoInterpreter;
    private final Interpreter soundInterpreter;
    private final GpuDelegate gpuDelegate;
    private final List<float[][][]> videoBuffer;
    private final List<float[]> soundBuffer;
    private final Context context;

    // State tracking
    private enum AppState { IDLE, RUNNING }
    private AppState currentState = AppState.IDLE;
    private int frameCount = 0;

    public Terminator(ExecutorService executor,
                      Interpreter videoInterpreter,
                      Interpreter soundInterpreter,
                      GpuDelegate gpuDelegate,
                      List<float[][][]> videoBuffer,
                      List<float[]> soundBuffer,
                      Context context) {
        this.executor = executor;
        this.videoInterpreter = videoInterpreter;
        this.soundInterpreter = soundInterpreter;
        this.gpuDelegate = gpuDelegate;
        this.videoBuffer = videoBuffer;
        this.soundBuffer = soundBuffer;
        this.context = context;
    }

    /**
     * Emergency cleanup function to terminate all AI operations
     */
    public synchronized void emergencyShutdown() {
        Log.i(TAG, "Initiating emergency shutdown");

        try {
            // 1. Shutdown executors
            shutdownExecutor();

            // 2. Release interpreters
            closeInterpreters();

            // 3. Release GPU resources
            closeGpuDelegate();

            // 4. Clear buffers
            clearBuffers();

            // 5. System cleanup
            systemCleanup();

            // 6. Update state
            updateAppState();Log.i(TAG, "Emergency shutdown complete");
        } catch (Exception e) {
            Log.w(TAG, "AI modeli çakıldı !" + e.toString());
            throw new RuntimeException(e);
        }



    }

    private void shutdownExecutor() {
        if (executor != null) {
            // 1) Yeni görev almayı kes
            executor.shutdown();
            try {
                // 2) Çalışan görevlerin makul süre içinde bitmesini bekle
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    // hâlâ bitmemişse zorla kes
                    executor.shutdownNow();
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        Log.w(TAG, "Executor did not terminate");
                    }
                }
            } catch (InterruptedException e) {
                // beklerken interrupt yediysen de zorla kapat
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(100, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "Executor termination timeout");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Executor termination interrupted", e);
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeInterpreters() {
        try {
            if (videoInterpreter != null) {
                videoInterpreter.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Video interpreter close failed", e);
        }

        try {
            if (soundInterpreter != null) {
                soundInterpreter.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sound interpreter close failed", e);
        }
    }

    private void closeGpuDelegate() {
        try {
            if (gpuDelegate != null) {
                gpuDelegate.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "GPU delegate close failed", e);
        }
    }

    private void clearBuffers() {
        if (videoBuffer != null) {
            synchronized (videoBuffer) {
                videoBuffer.clear();
            }
        }
        if (soundBuffer != null) {
            synchronized (soundBuffer) {
                soundBuffer.clear();
            }
        }
    }

    private void systemCleanup() {
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        logMemoryStatus();
    }

    private void updateAppState() {
        currentState = AppState.IDLE;
        frameCount = 0;
    }

    private void logMemoryStatus() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        Log.i(TAG, String.format(
                "Memory: Used=%.1fMB, Free=%.1fMB",
                used / (1024f * 1024f),
                runtime.freeMemory() / (1024f * 1024f)
        ));
    }

    /**
     * Restarts AI operations with fresh resources
     */
    public synchronized void restartOperations() {
        if (currentState == AppState.RUNNING) {
            Log.w(TAG, "Operations already running");
            return;
        }

        try {
            Log.i(TAG, "Restarting operations");
            // Reinitialize your resources here
            currentState = AppState.RUNNING;
            Toast.makeText(context, "AI operations restarted", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Restart failed", e);
            currentState = AppState.IDLE;
        }
    }

    /**
     * Checks if operations are currently running
     */
    public boolean isRunning() {
        return currentState == AppState.RUNNING;
    }

    // UI callbacks
    public void onStopButtonClicked(View view) {
        emergencyShutdown();
        Toast.makeText(context, "AI operations stopped", Toast.LENGTH_SHORT).show();
    }
    public boolean toggleBoolean(boolean boolVal) {
        return !boolVal;
    }
}