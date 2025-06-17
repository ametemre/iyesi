package com.kurmez.iyesi.utilities.delegate;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manages isolated executors for CPU-bound and TFLite/GPU-bound tasks,
 * and provides a main-thread handler for UI callbacks.
 */
public class ThreadService {
    private static ThreadService instance;

    // CPU-bound executor (background tasks, preprocessing)
    private final ExecutorService cpuExecutor;

    // TFLite/GPU-bound executor (isolated single thread for model inference)
    private final ExecutorService tfliteExecutor;

    // Handler for posting results back to the main (UI) thread
    private final Handler mainHandler;

    private ThreadService() {
        // Reserve one core for system/UI, use the rest for CPU tasks
        int cpuCores = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        cpuExecutor = Executors.newFixedThreadPool(cpuCores);

        // Single thread for TFLite/GPU to avoid concurrent delegate conflicts
        tfliteExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "TFLite-Thread");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });

        // Main thread handler for UI callbacks
        mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Singleton instance getter.
     */
    public static synchronized ThreadService getInstance() {
        if (instance == null) {
            instance = new ThreadService();
        }
        return instance;
    }

    /**
     * Submit a CPU-bound Runnable task.
     */
    public Future<?> submitCpu(Runnable task) {
        return cpuExecutor.submit(task);
    }

    /**
     * Submit a CPU-bound Callable task.
     */
    public <T> Future<T> submitCpu(Callable<T> task) {
        return cpuExecutor.submit(task);
    }

    /**
     * Submit a TFLite/GPU-bound Runnable task.
     */
    public Future<?> submitTflite(Runnable task) {
        return tfliteExecutor.submit(task);
    }

    /**
     * Submit a TFLite/GPU-bound Callable task.
     */
    public <T> Future<T> submitTflite(Callable<T> task) {
        return tfliteExecutor.submit(task);
    }

    /**
     * Post a Runnable to the main (UI) thread.
     */
    public void postToMain(Runnable runnable) {
        mainHandler.post(runnable);
    }

    /**
     * Shutdown both executors gracefully.
     */
    public void shutdown() {
        shutdownExecutor(cpuExecutor);
        shutdownExecutor(tfliteExecutor);
    }

    private void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
