package com.kurmez.iyesi.kurmes.utilities.Ai.threading;

public class MetricsSnapshot {
    public final float cpuUsagePct;
    public final Float gpuTimeMs;      // null olabilir
    public final long heapUsageBytes;
    public final long nativeUsageBytes;
    public final long timestamp;       // millis

    public MetricsSnapshot(float cpuUsagePct,
                           Float gpuTimeMs,
                           long heapUsageBytes,
                           long nativeUsageBytes,
                           long timestamp) {
        this.cpuUsagePct = cpuUsagePct;
        this.gpuTimeMs = gpuTimeMs;
        this.heapUsageBytes = heapUsageBytes;
        this.nativeUsageBytes = nativeUsageBytes;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return String.format(
                "time=%d, CPU=%.2f%%, GPU=%s ms, Heap=%d KB, Native=%d KB",
                timestamp,
                cpuUsagePct,
                gpuTimeMs != null ? String.format("%.2f", gpuTimeMs) : "n/a",
                heapUsageBytes / 1024,
                nativeUsageBytes / 1024
        );
    }
}
