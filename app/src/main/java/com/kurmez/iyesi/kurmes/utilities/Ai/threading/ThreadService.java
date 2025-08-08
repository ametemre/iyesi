package com.kurmez.iyesi.kurmes.utilities.Ai.threading;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.tensorflow.lite.Delegate;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Unified thread management service with integrated resource monitoring
 * and priority-based task scheduling.
 */
public class ThreadService {
    private static volatile ThreadService instance;

    // Priority executors
    private final ExecutorService computationExecutor;
    private final ExecutorService ioExecutor;
    private final ExecutorService tfliteExecutor;
    private final ScheduledExecutorService monitorExecutor;
    private final Handler mainHandler;

    // Resource tracking
    private long lastCpuUsage;
    private long lastGpuUsage;
    private boolean isMonitoring;

    public ThreadService() {
        int cores = Threading.getCpuCoreCount();

        // Priority-based thread pools
        computationExecutor = Executors.newFixedThreadPool(
                Math.max(2, cores / 2),
                new PriorityThreadFactory(Thread.NORM_PRIORITY + 1)
        );

        ioExecutor = Executors.newCachedThreadPool(
                new PriorityThreadFactory(Thread.NORM_PRIORITY)
        );

        tfliteExecutor = Executors.newSingleThreadExecutor(
                new PriorityThreadFactory(Thread.MAX_PRIORITY)
        );

        // Resource monitoring executor
        monitorExecutor = Executors.newSingleThreadScheduledExecutor();
        mainHandler = new Handler(Looper.getMainLooper());
    }
    public static synchronized ThreadService getInstance() {
        if (instance == null) {
            synchronized (ThreadService.class) {
                if (instance == null) {
                    instance = new ThreadService();
                }
            }
        }
        return instance;
    }

    /* Task Submission API */
    public Future<?> submitComputation(Runnable task) {
        return computationExecutor.submit(task);
    }
    public <T> Future<T> submitComputation(Callable<T> task) {return computationExecutor.submit(task);}
    public Future<?> submitIo(Runnable task) {
        return ioExecutor.submit(task);
    }
    public <T> Future<T> submitIo(Callable<T> task) {
        return ioExecutor.submit(task);
    }
    public Future<?> submitTflite(Runnable task) {
        return tfliteExecutor.submit(task);
    }
    public <T> Future<T> submitTflite(Callable<T> task) {
        return tfliteExecutor.submit(task);
    }

    /* Delegate Management */
    public Delegate getOrCreateDelegate(String key, Context context) {return Threading.getOrCreateDelegate(key, context);}
    public void releaseDelegate(String key) {
        Threading.releaseDelegate(key);
    }

    /* System Monitoring */
    public void startResourceMonitoring() {
        if (isMonitoring) return;

        isMonitoring = true;
        monitorExecutor.scheduleWithFixedDelay(() -> {
            MetricsSnapshot m = MetricsCollector.getSnapshot();
            Log.d("ResourceMonitor", m.toString());
        }, 0, 1, TimeUnit.SECONDS);
    }
    private double calculateGpuLoad(Threading.GpuInfo gpuInfo) {
        // Simplified GPU load calculation
        double maxCapacity = gpuInfo.maxWorkGroupCount[0] *
                gpuInfo.maxWorkGroupCount[1] *
                gpuInfo.maxWorkGroupCount[2];

        // Placeholder - actual usage tracking would require OpenGL queries
        return Math.min(0.75, Math.random() * 0.5 + 0.25);
    }
    public void stopResourceMonitoring() {
        isMonitoring = false;
        monitorExecutor.shutdownNow();
    }

    /* Thread Management */
    public void runOnMainThread(Runnable runnable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run();
        } else {
            mainHandler.post(runnable);
        }
    }
    public void shutdown() {
        // Phase 1: Initiate graceful shutdown
        computationExecutor.shutdown();
        ioExecutor.shutdown();
        tfliteExecutor.shutdown();

        // Phase 2: Release all hardware resources
        Threading.releaseAllDelegates();

        // Phase 3: Force shutdown if needed
        try {
            if (!computationExecutor.awaitTermination(3, TimeUnit.SECONDS))
                computationExecutor.shutdownNow();

            if (!ioExecutor.awaitTermination(3, TimeUnit.SECONDS))
                ioExecutor.shutdownNow();

            if (!tfliteExecutor.awaitTermination(3, TimeUnit.SECONDS))
                tfliteExecutor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Stop monitoring
        stopResourceMonitoring();
        monitorExecutor.shutdownNow();
    }

    /* Priority Thread Factory */
    private static class PriorityThreadFactory implements java.util.concurrent.ThreadFactory {
        private final int priority;

        public PriorityThreadFactory(int priority) {
            this.priority = priority;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(priority);
            return t;
        }
    }

    /* Hardware Info Proxies */
    public boolean isHighEndDevice(Context context) {return Threading.isHighEndDevice(context);}
    public boolean shouldUseFp16(Context context) {
        return Threading.shouldUseFp16(context);
    }
    public Threading.GpuInfo getGpuInfo() {
        return Threading.getGpuInfo();
    }
}