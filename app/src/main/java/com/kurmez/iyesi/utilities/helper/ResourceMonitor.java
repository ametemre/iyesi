package com.kurmez.iyesi.utilities.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;

import com.kurmez.iyesi.utilities.Ai.threading.MetricsCollector;
import com.kurmez.iyesi.utilities.Ai.threading.MetricsSnapshot;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ResourceMonitor {
    private static final String TAG = "ResourceMonitor";

    private TextView output;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Executor ve bu executor'da çalışan plan (future)
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> future;

    private final Runnable ticker = () -> {
        MetricsSnapshot snapshot = MetricsCollector.getSnapshot();
        String text = snapshot.toString();

        Log.d(TAG, text);
        mainHandler.post(() -> output.setText(text));
    };

    public ResourceMonitor(Context ctx, TextView tv) {
        this.output = tv;
    }

    /** Her saniye snapshot alır; start() defalarca çağrılsa da
     * eskisini iptal edip yenisini planlar, executor hep açık kalır. */
    public void start() {
        // Varsa önceki işi iptal et
        if (future != null && !future.isCancelled()) {
            future.cancel(false);
        }
        // Yeniden planla
        future = scheduler.scheduleAtFixedRate(ticker, 0, 1, TimeUnit.SECONDS);
    }

    /** Yalnızca planlanan işi iptal eder. Executor yaşam döngüsü devam eder. */
    public void stop() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }
}
