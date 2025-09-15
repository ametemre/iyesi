package com.kurmez.iyesi.kayra;

import android.app.Activity;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.IntRange;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;

import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityServiceException;
import com.google.android.play.core.integrity.IntegrityTokenRequest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Play ortam teşhisi + TLS Provider kurulum yardımcıları.
 *
 * Kullanım (Application.onCreate):
 *   PlayEnvDiagnostics.PlayEnvStatus st = PlayEnvDiagnostics.diagnose(getApplicationContext());
 *   Log.d("PlayEnvDiag", "status=" + st);
 *
 * Eğer GMS uyumsuzsa ilk Activity’de:
 *   PlayEnvDiagnostics.resolveGmsAvailability(this, 9000);
 *
 * TLS provider:
 *   PlayEnvDiagnostics.ensureTlsProviderAsync(getApplicationContext());
 */
public final class PlayEnvDiagnostics {

    private static final String TAG = "PlayEnvDiag";
    private static final String PKG_PLAY_STORE = "com.android.vending";

    private PlayEnvDiagnostics() {}

    /** Ortamın üst seviye özeti. */
    public enum PlayEnvStatus {
        OK,
        PLAY_STORE_MISSING_OR_DISABLED,
        GMSCORE_MISSING_OR_OUTDATED,
        APP_NOT_INSTALLED_FROM_PLAY,
        INTEGRITY_UNAVAILABLE_OR_BLOCKED
    }

    /** Hızlı genel teşhis. Ağır işlem yapmaz; ~1 sn bloklayabilir. */
    @NonNull
    public static PlayEnvStatus diagnose(@NonNull Context ctx) {
        // 1) Play Store var mı ve etkin mi?
        if (!isPlayStoreEnabled(ctx)) {
            Log.w(TAG, "Play Store yok ya da devre dışı");
            return PlayEnvStatus.PLAY_STORE_MISSING_OR_DISABLED;
        }

        // 2) GMS Core uygun mu?
        if (!isGmsAvailable(ctx)) {
            Log.w(TAG, "Google Play services uygun değil/güncel değil");
            //PlayStoreFixer.openPlayStoreForPackage(ctx, "com.android.vending"); // Play Store sayfası
            PendingIntent pi = androidx.core.app.TaskStackBuilder.create(ctx)
                    .addNextIntentWithParentStack(
                            new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + ctx.getPackageName()))
                                    .setPackage("com.android.vending"))
                    .getPendingIntent(1001,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
// Bildirime pi’yi ver; kullanıcı dokununca açılır (BAL yok).

            return PlayEnvStatus.GMSCORE_MISSING_OR_OUTDATED;
        }

        // 3) Kurulum kaynağı Play Store mu? (bazı ROM'larda null dönebilir)
        if (!isInstalledFromPlay(ctx)) {
            Log.w(TAG, "Uygulama Play Store'dan kurulmamış görünüyor");
            return PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY;
        }

        // 4) Integrity'ye hızlı prob
        if (!quickIntegrityProbe(ctx, /*timeoutMs=*/1200)) {
            Log.w(TAG, "Integrity API erişilemedi veya engellendi");
            //PlayStoreFixer.openPlayStoreForPackage(ctx, "com.android.vending"); // Play Store sayfası
            PendingIntent pi = androidx.core.app.TaskStackBuilder.create(ctx)
                    .addNextIntentWithParentStack(
                            new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + ctx.getPackageName()))
                                    .setPackage("com.android.vending"))
                    .getPendingIntent(1001,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
// Bildirime pi’yi ver; kullanıcı dokununca açılır (BAL yok).

            return PlayEnvStatus.INTEGRITY_UNAVAILABLE_OR_BLOCKED;
        }

        return PlayEnvStatus.OK;
    }

    /** Play Store uygulaması mevcut ve etkin mi? */
    public static boolean isPlayStoreEnabled(@NonNull Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(PKG_PLAY_STORE, 0);
            return ai != null && ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Google Play services hazır mı? */
    public static boolean isGmsAvailable(@NonNull Context ctx) {
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx);
        boolean ok = (code == ConnectionResult.SUCCESS);
        if (!ok) Log.w(TAG, "GMS status=" + code);
        return ok;
    }

    /** Uygulama Play Store’dan mı kurulmuş? Bazı ROM’larda null gelebilir → o zaman esnek davran. */
    public static boolean isInstalledFromPlay(@NonNull Context ctx) {
        try {
            @Nullable String installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
            if (installer == null) {
                // MIUI/HyperOS bazen null döner; kesin hüküm vermeyelim.
                Log.d(TAG, "installerPackageName=null (ROM davranışı olabilir)");
                return true;
            }
            boolean ok = PKG_PLAY_STORE.equals(installer);
            if (!ok) Log.w(TAG, "installer=" + installer + " (Play değil)");
            //PlayStoreFixer.openPlayStoreForPackage(ctx, "com.android.vending"); // Play Store sayfası
            PendingIntent pi = androidx.core.app.TaskStackBuilder.create(ctx)
                    .addNextIntentWithParentStack(
                            new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("market://details?id=" + ctx.getPackageName()))
                                    .setPackage("com.android.vending"))
                    .getPendingIntent(1001,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
// Bildirime pi’yi ver; kullanıcı dokununca açılır (BAL yok).


            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "installerPackageName okunamadı", t);
            return true; // Saptanamadı → engelleme.
        }
    }

    /**
     * Integrity servisine "ulaşabiliyor muyuz" hızlı testi.
     * Başarılıysa true döner. -2 (PLAY_STORE_NOT_FOUND) dahil tüm hatalarda false.
     */
    public static boolean quickIntegrityProbe(@NonNull Context ctx,
                                              @IntRange(from = 300, to = 5000) int timeoutMs) {
        final IntegrityManager im;
        try {
            im = IntegrityManagerFactory.create(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "IntegrityManagerFactory.create() başarısız", t);
            return false;
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = { false };

        IntegrityTokenRequest req = IntegrityTokenRequest.builder()
                .setNonce("ping") // Yanıtı kullanmıyoruz; sadece servis erişimi testi.
                .build();

        im.requestIntegrityToken(req)
                .addOnSuccessListener(response -> {
                    ok[0] = true;
                    latch.countDown();
                })
                .addOnFailureListener(e -> {
                    int code = (e instanceof IntegrityServiceException)
                            ? ((IntegrityServiceException) e).getErrorCode()
                            : Integer.MIN_VALUE;
                    Log.w(TAG, "Integrity probe failed, code=" + code + ", e=" + e);
                    latch.countDown();
                });

        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }

    /**
     * TLS provider’ı güvenli şekilde kurar; UI gerekmez.
     * Not: Başarısız olsa bile çoğu ağ çağrısı çalışır; ama eski TLS zincirlerinde kritik olabilir.
     */
    public static void ensureTlsProviderAsync(@NonNull Context ctx) {
        // Önce GMS uygun mu?
        if (!isGmsAvailable(ctx)) {
            Log.w(TAG, "GMS uygun değil; TLS provider kurulumu atlanıyor");
            return;
        }

        ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
            @Override public void onProviderInstalled() {
                Log.d(TAG, "TLS Provider yüklendi (GmsCore_OpenSSL)");
            }

            @Override public void onProviderInstallFailed(int errorCode, @Nullable Intent recoveryIntent) {
                Log.w(TAG, "TLS Provider yüklenemedi, errorCode=" + errorCode + " intent=" + recoveryIntent);
            }
        });
    }

    /**
     * GMS uygun değilse çözüm diyaloğunu açar.
     * Bunu yalnızca Activity bağlamında ve UI thread’de çağır.
     */
    @MainThread
    public static void resolveGmsAvailability(@NonNull Activity activity,
                                              @IntRange(from = 1) int requestCode) {
        GoogleApiAvailability gaa = GoogleApiAvailability.getInstance();
        int code = gaa.isGooglePlayServicesAvailable(activity);
        if (code == ConnectionResult.SUCCESS) return;

        if (gaa.isUserResolvableError(code)) {
            gaa.getErrorDialog(activity, code, requestCode).show();
        } else {
            Log.w(TAG, "GMS hatası kullanıcı tarafından çözülemez: code=" + code);
        }
    }

    /* ------------------------------------------------------------
     * Kolaylaştırıcı: Application içinden tek çağrıda yap
     * ------------------------------------------------------------ */
    public static @NonNull PlayEnvStatus initPreflight(@NonNull Application app) {
        PlayEnvStatus st = diagnose(app.getApplicationContext());
        Log.d(TAG, "preflight status=" + st);

        // TLS provider denemesi (başarısız olsa da zararı yok)
        ensureTlsProviderAsync(app.getApplicationContext());

        return st;
    }
}
