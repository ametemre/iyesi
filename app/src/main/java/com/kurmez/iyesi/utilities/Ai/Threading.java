// Threading.java
package com.kurmez.iyesi.utilities.Ai;

import android.os.Handler;
import android.os.Looper;

/**
 * Helper sınıf: UI thread dışındaki işleri çalıştırmak ve
 * UI güncellemelerini main thread’e post etmek için kullanılır.
 */
public class Threading {
    // UI thread’e görev göndermek için global handler
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Verilen Runnable’ı yeni bir background thread’te çalıştırır.
     */
    public static void runOnBackground(Runnable task) {
        new Thread(task).start();
    }

    /**
     * Verilen Runnable’ı UI (main) thread’te çalıştırmak üzere post eder.
     */
    public static void runOnUi(Runnable task) {
        mainHandler.post(task);
    }
    // ToDo : GPU worker
    // ToDo : CPU worker
    // ToDo : orchestrator worker
    // ToDo : MemoryControl
}
