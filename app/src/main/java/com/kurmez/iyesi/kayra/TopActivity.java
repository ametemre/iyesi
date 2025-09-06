package com.kurmez.iyesi.kayra;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * TopActivity
 * - En son RESUMED olan Activity'yi WeakReference ile takip eder.
 * - Uygulama ön planda mı bilgisini (startedCount) tutar.
 * - UI için uygun Context (Activity varsa o, yoksa Application) döndürür.
 */
public final class TopActivity implements Application.ActivityLifecycleCallbacks {

    private static volatile WeakReference<Activity> top = new WeakReference<>(null);
    private static volatile int startedCount = 0;      // >0 ise foreground
    private static volatile boolean initialized = false;
    private static volatile Application appRef;

    private TopActivity() {}

    // ---- Init ----
    public static synchronized void init(@NonNull Application app) {
        if (initialized) return;
        app.registerActivityLifecycleCallbacks(new TopActivity());
        appRef = app;
        initialized = true;
    }

    private static void ensureInit() {
        if (!initialized || appRef == null) {
            throw new IllegalStateException("TopActivity.init(Application) çağrılmadı.");
        }
    }

    // ---- Accessors ----
    /** Son aktif (RESUMED) Activity; yoksa null. */
    @Nullable public static Activity activity() {
        return top.get();
    }

    /** Uygulama ön planda mı? */
    public static boolean isForeground() {
        return startedCount > 0;
    }

    /** UI için uygun Context: Activity varsa o; yoksa Application. */
    @NonNull public static Context uiContext() {
        ensureInit();
        Activity a = top.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
        return appRef; // Application context
    }

    /** Application context (her zaman mevcut olmalı). */
    @NonNull public static Context appContext() {
        ensureInit();
        return appRef;
    }

    /** Ana (UI) threade runnable postla. */
    public static void runOnUi(@NonNull Runnable r) {
        Activity a = top.get();
        if (a != null) {
            a.runOnUiThread(r);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        }
    }

    // ---- Lifecycle ----
    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { /* no-op */ }
    @Override public void onActivityStarted(@NonNull Activity a) { startedCount++; }
    @Override public void onActivityResumed(@NonNull Activity a) { top = new WeakReference<>(a); }

    @Override public void onActivityPaused(@NonNull Activity a) {
        Activity cur = top.get();
        if (cur == a) top = new WeakReference<>(null);
    }

    @Override public void onActivityStopped(@NonNull Activity a) {
        if (startedCount > 0) startedCount--;
    }

    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { /* no-op */ }

    @Override public void onActivityDestroyed(@NonNull Activity a) {
        Activity cur = top.get();
        if (cur == a) top = new WeakReference<>(null);
    }
}
