// PlayEnvDiagnostics.java
package com.kurmez.iyesi.kayra;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.NetworkSecurityPolicy;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.IntegrityServiceException;

import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class PlayEnvDiagnostics {
    private static final String TAG = "PlayEnvDiag";
    private static final String PKG_PLAY_STORE = "com.android.vending";
    private static final String PKG_GMS = "com.google.android.gms";
    // İstersen minimum sürüm eşiği koyabilirsin:
    private static final int MIN_GMS_VERSION_CODE = com.google.android.gms.common.GoogleApiAvailability.GOOGLE_PLAY_SERVICES_VERSION_CODE;

    private PlayEnvDiagnostics() {}

    /** App, Play’den mi yüklü? (installer kontrolü) */
    public static boolean isInstalledFromPlay(@NonNull Context ctx) {
        String installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
        return PKG_PLAY_STORE.equals(installer);
    }

    /** Play Store cihazda var ve etkin mi? */
    public static boolean isPlayStorePresentAndEnabled(@NonNull Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(PKG_PLAY_STORE, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** GMS Core (Play Services) var mı ve sürüm yeterli mi? */
    public static int checkGooglePlayServices(@NonNull Context ctx) {
        int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx);
        // SUCCESS ise iyi. Değilse kullanıcıya çözüm sunulabilir (update/enable/install)
        return status;
    }

    /** GMS sürüm kodunu döndür (0 => bulunamadı) */
    public static int getGmsVersionCode(@NonNull Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG_GMS, 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Play Store sürüm kodunu döndür (0 => yok) */
    public static int getPlayStoreVersionCode(@NonNull Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG_PLAY_STORE, 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** TLS provider kurulabiliyor mu? (ProviderInstaller) */
// PlayEnvDiagnostics.ensureTlsProvider(...)
    public static boolean ensureTlsProvider(@NonNull Context ctx) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};

        // import: com.google.android.gms.security.ProviderInstaller.ProviderInstallListener;
        ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
            @Override
            public void onProviderInstalled() {
                ok[0] = true;
                latch.countDown();
            }

            @Override
            public void onProviderInstallFailed(int errorCode, @Nullable Intent recoveryIntent) {
                // İyileştirilebilir bir durumsa kullanıcıya çözüm bildirimi gösterebilirsin:
                GoogleApiAvailability gms = GoogleApiAvailability.getInstance();
                if (gms.isUserResolvableError(errorCode)) {
                    // İstersen bildirim göster:
                    // gms.showErrorNotification(ctx, errorCode);
                    Log.w(TAG, "TLS provider install needs user action: " + errorCode);
                } else {
                    Log.w(TAG, "TLS provider install failed: code=" + errorCode);
                }
                latch.countDown();
            }
        });

        try { latch.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return ok[0];
    }


    /** Play Integrity'ye kısa ping: başarı veya hata kodu döndür. */
    public static IntegrityProbeResult probeIntegrity(@NonNull Context ctx) {
        IntegrityManager im = IntegrityManagerFactory.create(ctx);
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String nonceB64 = Base64.encodeToString(nonce, Base64.NO_WRAP | Base64.URL_SAFE);
        IntegrityTokenRequest req = IntegrityTokenRequest.builder()
                .setNonce(nonceB64)
                // .setCloudProjectNumber(238523750447L) // istersen sabitle; eşleşme şart!
                .build();

        final CountDownLatch latch = new CountDownLatch(1);
        final IntegrityProbeResult out = new IntegrityProbeResult();

        im.requestIntegrityToken(req)
                .addOnSuccessListener(r -> {
                    out.success = true;
                    out.tokenPrefix = r.token().substring(0, Math.min(16, r.token().length()));
                    latch.countDown();
                })
                .addOnFailureListener(ex -> {
                    out.success = false;
                    if (ex instanceof IntegrityServiceException) {
                        IntegrityServiceException ie = (IntegrityServiceException) ex;
                        out.errorCode = ie.getErrorCode();
                        out.errorMessage = ie.getMessage();
                    } else {
                        out.errorCode = Integer.MIN_VALUE;
                        out.errorMessage = ex.getMessage();
                    }
                    latch.countDown();
                });

        try { latch.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return out;
    }

    /** Toplu rapor. Logcat’e bas ve döndür. */
    public static String buildReport(@NonNull Context ctx) {
        StringBuilder sb = new StringBuilder();
        boolean fromPlay = isInstalledFromPlay(ctx);
        boolean hasPlayStore = isPlayStorePresentAndEnabled(ctx);
        int gmsStatus = checkGooglePlayServices(ctx);
        int gmsVc = getGmsVersionCode(ctx);
        int psVc = getPlayStoreVersionCode(ctx);
        boolean tlsOk = ensureTlsProvider(ctx);
        IntegrityProbeResult pr = probeIntegrity(ctx);

        sb.append("=== Play Env Report ===\n");
        sb.append("Installed from Play: ").append(fromPlay).append('\n');
        sb.append("Play Store present/enabled: ").append(hasPlayStore)
                .append(" (vc=").append(psVc).append(")\n");
        sb.append("GMS status: ").append(gmsStatusToString(gmsStatus))
                .append(" (vc=").append(gmsVc).append(", min=").append(MIN_GMS_VERSION_CODE).append(")\n");
        sb.append("TLS Provider install ok: ").append(tlsOk).append('\n');
        sb.append("Cleartext permitted (global): ").append(NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted()).append('\n');
        sb.append("Integrity ping: ").append(pr.success ? "OK token" : "FAIL")
                .append(pr.success ? (" (token~=" + pr.tokenPrefix + "...)") :
                        (" (code=" + pr.errorCode + ", msg=" + safe(pr.errorMessage) + ")"))
                .append('\n');

        Log.i(TAG, sb.toString());
        return sb.toString();
    }

    private static String safe(String s) { return TextUtils.isEmpty(s) ? "-" : s; }

    private static String gmsStatusToString(int status) {
        switch (status) {
            case ConnectionResult.SUCCESS: return "SUCCESS";
            case ConnectionResult.SERVICE_MISSING: return "SERVICE_MISSING";
            case ConnectionResult.SERVICE_UPDATING: return "SERVICE_UPDATING";
            case ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED: return "SERVICE_VERSION_UPDATE_REQUIRED";
            case ConnectionResult.SERVICE_DISABLED: return "SERVICE_DISABLED";
            case ConnectionResult.SERVICE_INVALID: return "SERVICE_INVALID";
            default: return "ERROR_" + status;
        }
    }

    public static class IntegrityProbeResult {
        public boolean success;
        public int errorCode;
        public String errorMessage;
        public String tokenPrefix;
    }
}
