package com.kurmez.iyesi.utilities.Ai;

import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public class AiMultiRunner {
    private final ExecutorService executor;
    private final Semaphore semaphore;
    private static final String TAG = "AiMultiRunner";

    public AiMultiRunner(int threadCount) {
        this.executor = Executors.newFixedThreadPool(threadCount);
        this.semaphore = new Semaphore(threadCount); // Aynı anda en fazla threadCount iş çalışsın
    }

    public void run(Runnable task) {
        if (semaphore.tryAcquire()) {
            executor.submit(() -> {
                try {
                    task.run();
                } catch (Exception e) {
                    Log.e(TAG, "Task failed", e);
                } finally {
                    semaphore.release();
                }
            });
        } else {
            Log.d(TAG, "AI yükleme yoğun - bu frame atlandı");
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
