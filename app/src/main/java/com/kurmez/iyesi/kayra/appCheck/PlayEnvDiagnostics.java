package com.kurmez.iyesi.kayra.appCheck;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

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
import com.google.android.play.core.integrity.model.IntegrityErrorCode;
import com.google.firebase.appcheck.FirebaseAppCheck;


import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.auth.FirebaseAuth;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PlayEnvDiagnostics — Play Integrity + Firebase App Check ortam teşhisi ve kurulum yardımcıları.
 *
 * Java 17 dostu:
 *  - instanceof pattern matching
 *  - var
 *
 * Sağladıkları:
 *  - App Check provider kurulumu: Debug / PlayIntegrity (tek giriş noktası)
 *  - TLS sağlayıcı kurulumu (GmsCore_OpenSSL)
 *  - Preflight: ağ, GMS/Play Store, kurulum kaynağı, Integrity erişilebilirliği
 *  - Kısa özet için toast yardımcısı
 */
public final class PlayEnvDiagnostics {

    public static final String TAG = "PlayEnvDiag";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean APP_CHECK_INSTALLED = new AtomicBoolean(false);

    private PlayEnvDiagnostics() {}

    // --------------------------------------------------------------------------------------------
    // Modeller
    // --------------------------------------------------------------------------------------------

    public enum PlayEnvStatus {
        OK,
        INTEGRITY_UNAVAILABLE_OR_BLOCKED,
        NONCE_TOO_SHORT,
        PLAY_STORE_MISSING_OR_DISABLED,
        GMSCORE_MISSING_OR_OUTDATED,
        DEBUG_PROVIDER_ACTIVE,
        API_NOT_AVAILABLE,
        NETWORK_ERROR,
        RATE_LIMITED,
        INTERNAL_ERROR,
        UNKNOWN_ERROR,
        APP_NOT_INSTALLED_FROM_PLAY
    }

    public static final class PlayEnvReport {
        public PlayEnvStatus status = PlayEnvStatus.UNKNOWN_ERROR;
        public boolean isEmulator;
        public boolean playStoreInstalled;
        public boolean gmsAvailable;
        public int gmsAvailabilityCode = ConnectionResult.SUCCESS;
        public @Nullable String installerPackageName;
        public @Nullable Integer integrityErrorCode;
        public boolean integrityReachable;
        public boolean appCheckDebugActive;
        public boolean hasNetwork;
        public long gmsVersion;
        public long playStoreVersion;

        @NonNull @Override public String toString() {
            return "PlayEnvReport{" +
                    "status=" + status +
                    ", isEmulator=" + isEmulator +
                    ", playStoreInstalled=" + playStoreInstalled +
                    ", gmsAvailable=" + gmsAvailable +
                    ", gmsAvailabilityCode=" + gmsAvailabilityCode +
                    ", installerPackageName='" + installerPackageName + '\'' +
                    ", integrityErrorCode=" + integrityErrorCode +
                    ", integrityReachable=" + integrityReachable +
                    ", appCheckDebugActive=" + appCheckDebugActive +
                    ", hasNetwork=" + hasNetwork +
                    ", gmsVersion=" + gmsVersion +
                    ", playStoreVersion=" + playStoreVersion +
                    '}';
        }
    }

    public interface ReportCallback {
        @MainThread void onReport(@NonNull PlayEnvReport report);
    }

    // --------------------------------------------------------------------------------------------
    // Kamu Metotları
    // --------------------------------------------------------------------------------------------

    /**
     * App Check provider kurulumunu tek noktadan yapar.
     *
     * @param application Application
     * @param allowDebugInDebugBuild DEBUG build'ta Debug provider kullan (Console'da Debug device Allow gerekli)
     * @param allowDebugFallbackRelease Release'te Integrity erişilemiyorsa Debug'a düş (genelde false)
     *//*
    public static void installAppCheckProvider(
            @NonNull Application application,
            boolean allowDebugInDebugBuild,
            boolean allowDebugFallbackRelease
    ) {
        if (APP_CHECK_INSTALLED.get()) {
            Log.d(TAG, "AppCheck provider already installed; skipping.");
            return;
        }
        try {
            final boolean isDebug = isDebugBuild(application);
            if (isDebug && allowDebugInDebugBuild) {
                FirebaseAppCheck.getInstance()
                        .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
                APP_CHECK_INSTALLED.set(true);
                Log.i(TAG, "AppCheck provider installed: Debug (fallback=" + allowDebugFallbackRelease + ")");
                return;
            }

            // Default: Play Integrity
            FirebaseAppCheck.getInstance()
                    .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
            APP_CHECK_INSTALLED.set(true);
            Log.i(TAG, "AppCheck provider installed: PlayIntegrity (fallback=" + allowDebugFallbackRelease + ")");
        } catch (Throwable t) {
            Log.w(TAG, "AppCheck provider install failed", t);
            if (allowDebugFallbackRelease) {
                try {
                    FirebaseAppCheck.getInstance()
                            .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
                    APP_CHECK_INSTALLED.set(true);
                    Log.i(TAG, "AppCheck provider installed: Debug (due to fallback)");
                } catch (Throwable t2) {
                    Log.e(TAG, "AppCheck fallback to Debug failed", t2);
                }
            }
        }
    }*/

    /** App Check token'ını ısıt (isteğe bağlı). */
    public static void warmUpAppCheck() {
        try {
            FirebaseAppCheck.getInstance().getAppCheckToken(false)
                    .addOnSuccessListener(token -> Log.d(TAG, "warm-up AppCheck OK"))
                    .addOnFailureListener(e -> Log.w(TAG, "warm-up AppCheck failed", e));
        } catch (Throwable t) {
            Log.w(TAG, "warm-up AppCheck threw", t);
        }
    }

    /** TLS provider (GmsCore_OpenSSL) kurulumu. */
    public static void ensureTlsProvider(@NonNull Context ctx) {
        try {
            ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
                @Override public void onProviderInstalled() {
                    Log.d(TAG, "TLS Provider yüklendi (GmsCore_OpenSSL)");
                }
                @Override public void onProviderInstallFailed(int errorCode, @Nullable Intent intent) {
                    Log.w(TAG, "TLS Provider install failed: " + errorCode);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "TLS Provider install threw", t);
        }
    }


// -------------------------------------------------------------------------------------------- Kamu Metotları


// --------------------------------------------------------------------------------------------
// Geriye Dönük Uyum (Legacy)
// --------------------------------------------------------------------------------------------
/**
 +     * @deprecated Yerine {@link #preflight(Context, ReportCallback)} kullanın.
 +     * Hızlı (senkron) ortam çıkarımı yapar; Integrity token talebi göndermez.
 +     */
        @Deprecated
        public static PlayEnvDiagnostics.PlayEnvStatus initPreflight(@NonNull Context ctx) {
    PlayEnvDiagnostics.PlayEnvReport r = quickReport(ctx);
    Log.d(TAG, "initPreflight (legacy) → " + r.status + " | installer=" + r.installerPackageName);
    return r.status;}
/** @deprecated Uyum için Application aşırı yüklemesi. */
        @Deprecated
public static PlayEnvDiagnostics.PlayEnvStatus initPreflight(@NonNull Application app) {return initPreflight((Context) app.getApplicationContext());}
/** Hızlı senkron ortam raporu (Integrity erişilebilirliği test edilmez). */
private static PlayEnvDiagnostics.PlayEnvReport quickReport(@NonNull Context ctx) {
    final var report = new PlayEnvDiagnostics.PlayEnvReport();
    report.isEmulator = isProbablyEmulator();
    report.installerPackageName = resolveInstallerPackage(ctx);
    report.playStoreInstalled = isPackageInstalled(ctx, "com.android.vending");
    report.appCheckDebugActive = isAppCheckDebugActive();
    report.hasNetwork = isOnline(ctx);
    final var gaa = GoogleApiAvailability.getInstance();
    int gmsCode = gaa.isGooglePlayServicesAvailable(ctx);
    report.gmsAvailabilityCode = gmsCode;
    report.gmsAvailable = (gmsCode == ConnectionResult.SUCCESS);
    if (!report.hasNetwork) {
        report.status = PlayEnvStatus.NETWORK_ERROR;
        return report;
    }
    if (!report.gmsAvailable) {
        report.status = PlayEnvStatus.GMSCORE_MISSING_OR_OUTDATED;
        return report;
    }
    if (!report.playStoreInstalled) {
        report.status = PlayEnvStatus.PLAY_STORE_MISSING_OR_DISABLED;
        return report;
    }
    if (report.installerPackageName == null || !"com.android.vending".equals(report.installerPackageName)) {
        report.status = PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY;
        return report;
    }
    report.status = PlayEnvStatus.OK;
    return report;
        }
// Kamu Metotları
// --------------------------------------------------------------------------------------------

/**
    /**
     * Integrity preflight: Ağ, GMS/Play Store, kurulum kaynağı ve Integrity erişilebilirliği.
     * Sonuç main thread'e callback ile döner.
     */
    public static void preflight(@NonNull Context ctx, @NonNull ReportCallback cb) {
        final var report = new PlayEnvReport();

        report.isEmulator = isProbablyEmulator();
        report.installerPackageName = resolveInstallerPackage(ctx);
        report.playStoreInstalled = isPackageInstalled(ctx, "com.android.vending");
        report.appCheckDebugActive = isAppCheckDebugActive();
        report.hasNetwork = isOnline(ctx);
        report.gmsVersion = pkgVer(ctx, "com.google.android.gms");
        report.playStoreVersion = pkgVer(ctx, "com.android.vending");

        final var gaa = GoogleApiAvailability.getInstance();
        int gmsCode = gaa.isGooglePlayServicesAvailable(ctx);
        report.gmsAvailabilityCode = gmsCode;
        report.gmsAvailable = (gmsCode == ConnectionResult.SUCCESS);

        // Ön hızlı kararlar
        if (!report.hasNetwork) {
            report.status = PlayEnvStatus.NETWORK_ERROR;
            post(cb, report);
            return;
        }
        if (!report.gmsAvailable) {
            report.status = PlayEnvStatus.GMSCORE_MISSING_OR_OUTDATED;
            post(cb, report);
            return;
        }
        if (!report.playStoreInstalled) {
            report.status = PlayEnvStatus.PLAY_STORE_MISSING_OR_DISABLED;
            post(cb, report);
            return;
        }

        // Yan yükleme tespiti (bilgilendirici): Play dışı kurulum Integrity sonuçlarını etkiler.
        if (report.installerPackageName == null || !"com.android.vending".equals(report.installerPackageName)) {
            report.status = PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY;
            // Devam edip erişilebilirlik testini de yapalım; sonuç integrityReachable ile görülecek.
        }

        IO.execute(() -> {
            try {
                var im = IntegrityManagerFactory.create(ctx.getApplicationContext());
                String uid = nullSafeUid();
                String nonceB64 = NonceUtil.nextNonce(uid);

                var req = IntegrityTokenRequest.builder()
                        .setNonce(nonceB64)
                        .build();

                im.requestIntegrityToken(req)
                        .addOnSuccessListener(response -> {
                            report.integrityReachable = true;
                            // Mevcut durum "APP_NOT_INSTALLED_FROM_PLAY" seçiliyse koru; yoksa OK de.
                            if (report.status != PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY) {
                                report.status = PlayEnvStatus.OK;
                            }
                            post(cb, report);
                        })
                        .addOnFailureListener(e -> {
                            mapIntegrityFailure(e, report);
                            post(cb, report);
                        });
            } catch (Throwable t) {
                Log.w(TAG, "Integrity preflight threw", t);
                if (report.status == PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY) {
                    // Durumu koru; ama erişilemiyor olarak işaretle
                    report.integrityReachable = false;
                } else {
                    report.status = PlayEnvStatus.UNKNOWN_ERROR;
                }
                post(cb, report);
            }
        });
    }

    /** Kısa özet mesajını kullanıcıya göster (isteğe bağlı). */
    public static void toastSummary(@NonNull Context ctx, @NonNull PlayEnvReport r) {
        final String msg = switch (r.status) {
            case OK -> "Play Integrity: OK";
            case INTEGRITY_UNAVAILABLE_OR_BLOCKED -> "Integrity: erişilemedi/engellendi (emülatör veya ROM kısıtı)";
            case PLAY_STORE_MISSING_OR_DISABLED -> "Play Store yüklü değil";
            case GMSCORE_MISSING_OR_OUTDATED -> "Google Play Services güncel değil";
            case NONCE_TOO_SHORT -> "Nonce hatası";
            case API_NOT_AVAILABLE -> "Integrity API bu cihazda kullanılamıyor";
            case NETWORK_ERROR -> "Ağ yok / ağ hatası";
            case RATE_LIMITED -> "Integrity rate limit";
            case INTERNAL_ERROR -> "Integrity internal error";
            case APP_NOT_INSTALLED_FROM_PLAY -> "Uygulama Play Store’dan kurulu değil";
            case DEBUG_PROVIDER_ACTIVE -> "Debug AppCheck aktif";
            default -> "Integrity: bilinmeyen durum";
        };
        Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
    }

    // --------------------------------------------------------------------------------------------
    // Yardımcılar
    // --------------------------------------------------------------------------------------------

    private static void mapIntegrityFailure(Throwable e, PlayEnvReport report) {
        report.integrityReachable = false;
        if (e instanceof IntegrityServiceException ise) {
            int code = ise.getErrorCode();
            report.integrityErrorCode = code;
            switch (code) {
                case IntegrityErrorCode.NONCE_TOO_SHORT:
                case IntegrityErrorCode.NONCE_IS_NOT_BASE64:
                case IntegrityErrorCode.NONCE_TOO_LONG:
                    report.status = PlayEnvStatus.NONCE_TOO_SHORT; // toplulaştırılmış nonce hatası
                    break;
                case IntegrityErrorCode.API_NOT_AVAILABLE:
                    report.status = PlayEnvStatus.API_NOT_AVAILABLE;
                    break;
                case IntegrityErrorCode.NETWORK_ERROR:
                    report.status = PlayEnvStatus.NETWORK_ERROR;
                    break;
                case IntegrityErrorCode.PLAY_STORE_NOT_FOUND:
                case IntegrityErrorCode.APP_NOT_INSTALLED:
                case IntegrityErrorCode.PLAY_STORE_ACCOUNT_NOT_FOUND:
                    report.status = PlayEnvStatus.PLAY_STORE_MISSING_OR_DISABLED;
                    break;
                case IntegrityErrorCode.TOO_MANY_REQUESTS:
                    report.status = PlayEnvStatus.RATE_LIMITED;
                    break;
                case IntegrityErrorCode.INTERNAL_ERROR:
                    report.status = PlayEnvStatus.INTERNAL_ERROR;
                    break;
                default:
                    report.status = PlayEnvStatus.INTEGRITY_UNAVAILABLE_OR_BLOCKED;
                    break;
            }
            Log.w(TAG, "Integrity failure, code=" + code, e);
        } else {
            report.status = PlayEnvStatus.UNKNOWN_ERROR;
            Log.w(TAG, "Integrity failure (unknown)", e);
        }
    }

    private static boolean isDebugBuild(@NonNull Context ctx) {
        try {
            ApplicationInfo ai = ctx.getApplicationInfo();
            return (ai != null) && ((ai.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPackageInstalled(@NonNull Context ctx, @NonNull String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static String resolveInstallerPackage(@NonNull Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = pm.getInstallSourceInfo(ctx.getPackageName());
                return info != null ? info.getInstallingPackageName() : null;
            } else {
                return pm.getInstallerPackageName(ctx.getPackageName());
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isProbablyEmulator() {
        final String fingerprint = Build.FINGERPRINT;
        final String product = Build.PRODUCT;
        final String model = Build.MODEL;
        return (fingerprint != null && fingerprint.contains("generic"))
                || (product != null && (product.contains("sdk") || product.contains("emulator")))
                || (model != null && model.contains("Android SDK built for x86"));
    }

    private static boolean isOnline(@NonNull Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network n = cm.getActiveNetwork();
                if (n == null) return false;
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                return nc != null && (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        || nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            } else {
                @SuppressWarnings("deprecation")
                android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                return ni != null && ni.isConnected();
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private static long pkgVer(@NonNull Context ctx, @NonNull String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return ctx.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
            } else {
                //noinspection deprecation
                return ctx.getPackageManager().getPackageInfo(pkg, 0).versionCode;
            }
        } catch (Throwable t) {
            return 0L;
        }
    }

    @Nullable
    private static String nullSafeUid() {
        try {
            return FirebaseAuth.getInstance().getUid();
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isAppCheckDebugActive() {
        try {
            Class<?> cls = Class.forName("com.kurmez.iyesi.kayra.appCheck.AppCheckTP");
            var m = cls.getDeclaredMethod("isDebugProviderActive");
            Object ret = m.invoke(null);
            return (ret instanceof Boolean b) && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void post(@NonNull ReportCallback cb, @NonNull PlayEnvReport report) {
        MAIN.post(() -> {
            Log.d(TAG, "preflight report: " + report);
            cb.onReport(report);
        });
    }
}