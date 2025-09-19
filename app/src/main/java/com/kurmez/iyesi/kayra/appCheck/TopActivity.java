package com.kurmez.iyesi.kayra.appCheck;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.kurmez.iyesi.R;

import java.lang.ref.WeakReference;

/**
 * TopActivity
 * - Başlatılabilir küçük bir tanılama ekranı (dialog).
 * - Uygulama genelinde en son RESUMED Activity'yi WeakReference ile takip eder.
 * - Uygulama ön planda mı bilgisini tutar (startedCount).
 * - UI için uygun Context (Activity varsa o, yoksa Application) döndürür.
 * - startActivitySafely / openUrl yardımcıları sunar.
 *
 * Kullanım:
 *   - Application.onCreate() içinde TopActivity.init(application) çağır.
 *   - Tanılama açmak için: startActivity(new Intent(ctx, TopActivity.class));
 *
 * TODO: İstersen buradaki dialog içeriğini GmsIntegrityPreflight/PlayEnvDiagnostics
 *       verileriyle zenginleştir.
 */
public class TopActivity extends AppCompatActivity {

    // --------- Global registry (statik) ---------
    private static volatile WeakReference<Activity> top = new WeakReference<>(null);
    private static volatile int startedCount = 0;      // >0 ise foreground
    private static volatile boolean initialized = false;
    private static volatile Application appRef;

    /** Application yaşam döngüsüne hook. */
    public static synchronized void init(@NonNull Application app) {
        if (initialized) return;
        app.registerActivityLifecycleCallbacks(new Registry());
        appRef = app;
        initialized = true;
    }

    private static void ensureInit() {
        if (!initialized || appRef == null) {
            throw new IllegalStateException("TopActivity.init(Application) çağrılmadı.");
        }
    }

    /** Son aktif (RESUMED) Activity; yoksa null. */
    @Nullable public static Activity activity() { return top.get(); }

    /** Alias: Son aktif (RESUMED) Activity; yoksa null. */
    @Nullable public static Activity current() { return activity(); }

    /** Uygulama ön planda mı? */
    public static boolean isForeground() { return startedCount > 0; }

    /** UI için uygun Context: Activity varsa o; yoksa Application. */
    @NonNull public static Context uiContext() {
        ensureInit();
        Activity a = top.get();
        if (a != null && !a.isFinishing() && !a.isDestroyed()) return a;
        return appRef; // Application context
    }

    /** Application context (her zaman mevcut olmalı). */
    @NonNull public static Context appContext() { ensureInit(); return appRef; }

    /** Ana (UI) threade runnable postla. */
    public static void runOnUi(@NonNull Runnable r) {
        Activity a = top.get();
        if (a != null) {
            a.runOnUiThread(r);
        } else {
            new Handler(Looper.getMainLooper()).post(r);
        }
    }

    /**
     * Güvenli startActivity: Bir Activity mevcutsa ondan, yoksa Application context ile NEW_TASK.
     * true => başlatıldı, false => başlatılamadı.
     */
    public static boolean startActivitySafely(@NonNull Intent intent) {
        try {
            Context ctx = uiContext();
            if (!(ctx instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Hızlı URL açma yardımcıcısı. */
    public static boolean openUrl(@NonNull String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        return startActivitySafely(i);
    }

    // --------- Activity (tanılama ekranı) ---------
    public TopActivity() { /* public no-arg ctor */ }

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // PlayEnvDiagnostics raporunu al ve göster
        PlayEnvDiagnostics.preflight(this, report -> {
            displayDiagnosticReport(report);
        });
        // Küçük bir tanılama dialogu göster; kapandığında Activity'yi bitir.
        new AlertDialog.Builder(this)
                .setTitle("Tanılama")
                .setMessage(
                        "Ortam kontrolleri için kısa yollar:\n\n" +
                                "• Google Play Hizmetleri bilgisi\n" +
                                "• Play Store sayfaları\n" +
                                "• Uygulama bilgisi\n\n" +
                                "Bu ekran sadece yardımcıdır; uygulamanın akışını engellemez.")
                .setPositiveButton("Kapat", (d, w) -> finish())
                .setNeutralButton("Play Store (Uygulama)", (d, w) ->
                        startActivitySafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Play Store (Güncelle)", (d, w) -> {
                    // Play Store uygulamasını açmayı dener; yoksa web'e düşer.
                    boolean ok = openUrl("market://details?id=com.android.vending");
                    if (!ok) openUrl("https://play.google.com/store/apps/details?id=com.android.vending");
                })
                .setOnDismissListener(di -> finish())
                .show();
    }
    private void displayDiagnosticReport(PlayEnvDiagnostics.PlayEnvReport report) {
        TextView reportView = findViewById(R.id.tvTitle);
        String reportText = String.format(
                "Installer: %s\nGMS Version: %d\nIntegrity Reachable: %b\nAppCheck Debug: %b",
                report.installerPackageName,
                report.gmsVersion,
                report.integrityReachable,
                report.appCheckDebugActive
        );
        reportView.setText(reportText);
    }
    // --------- İç kayıtçı: Application.ActivityLifecycleCallbacks ---------
    private static final class Registry implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { /* no-op */ }
        @Override public void onActivityStarted(@NonNull Activity a) { startedCount++; }
        @Override public void onActivityResumed(@NonNull Activity a) { top = new WeakReference<>(a); }
        @Override public void onActivityPaused(@NonNull Activity a) {
            Activity cur = top.get(); if (cur == a) top = new WeakReference<>(null);
        }
        @Override public void onActivityStopped(@NonNull Activity a) { if (startedCount > 0) startedCount--; }
        @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { /* no-op */ }
        @Override public void onActivityDestroyed(@NonNull Activity a) {
            Activity cur = top.get(); if (cur == a) top = new WeakReference<>(null);
        }
    }
}
