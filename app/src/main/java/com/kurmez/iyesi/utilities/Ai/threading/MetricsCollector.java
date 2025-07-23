package com.kurmez.iyesi.utilities.Ai.threading;

import android.os.Debug;

import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {
    private static final int NUM_CORES = Runtime.getRuntime().availableProcessors();

    // Önceki CPU zaman damgası
    private static final AtomicLong prevCpuTime = new AtomicLong(0);
    private static final AtomicLong prevTimestamp = new AtomicLong(0);

    public static MetricsSnapshot getSnapshot() {
        long nowTs = System.currentTimeMillis();
        long nowCpu = Debug.threadCpuTimeNanos();

        long lastTs = prevTimestamp.getAndSet(nowTs);
        long lastCpu = prevCpuTime.getAndSet(nowCpu);

        float cpuPct = 0f;
        if (lastTs > 0) {
            long deltaCpu = nowCpu - lastCpu;
            long deltaTsNanos = (nowTs - lastTs) * 1_000_000L;
            cpuPct = (float) deltaCpu / (deltaTsNanos * NUM_CORES) * 100f;
        }

        // Heap ve native bellek kullanımı
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long nativeUsed = Debug.getNativeHeapAllocatedSize();

        // GPU zaman ölçümü şimdilik desteklenmiyor
        Float gpuMs = null;

        return new MetricsSnapshot(cpuPct, gpuMs, heapUsed, nativeUsed, nowTs);
    }
}
