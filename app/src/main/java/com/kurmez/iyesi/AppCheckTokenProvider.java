package com.kurmez.iyesi;

import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.AppCheckTokenResult;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Application + App Check bootstrapper.
 *
 * Manifest:
 * <application
 *     android:name="com.kurmez.iyesi.AppCheckTokenProvider"
 *     ... />
 */
public class AppCheckTokenProvider extends Application {
    public static final String TAG = "AppCheckTP";

    // Uygulama düzeyi context cache (bazı eski kodların ihtiyaçları için).
    private static volatile @Nullable Context sApp;

    /** Application yansıma ile örneklenebilsin diye public ve parametresiz olmalı. */
    public AppCheckTokenProvider() {
        super();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            // Firebase init — birden fazla çağrı güvenlidir (idempotent)
            FirebaseApp.initializeApp(this);

            // App Check → Play Integrity sağlayıcısı
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
            appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
            );

            // Eski yardımcılar için app context sakla
            sApp = getApplicationContext();

            Log.i(TAG, "onCreate → Firebase & AppCheck initialized (PlayIntegrity).");
        } catch (Throwable t) {
            Log.e(TAG, "onCreate failed initializing Firebase/AppCheck", t);
        }
    }

    /* =========================================================
     *  Public helpers (geriye uyumlu imzalar)
     * =======================================================*/

    /**
     * Activity/Service tarafında manuel init eden eski kodlar için opsiyonel.
     */
    public static void init(@Nullable Context context) {
        if (context != null) {
            sApp = context.getApplicationContext();
        }
    }

    /**
     * Uygulama context’i (null ise Application henüz yaratılmamıştır).
     */
    public static @Nullable Context getAppContext() {
        return sApp;
    }

    /**
     * Basit kullanım: sadece token’a bakan tek parametreli lambda.
     * Örn: AppCheckTokenProvider.getAppCheckTokenCached(true, t -> Log.d("TAG", t));
     */
    public static void getAppCheckTokenCached(boolean forceRefresh,
                                              @NonNull StringCallback onToken) {
        getAppCheckTokenCached(forceRefresh, new PassSkipCallback() {
            @Override public void onPassed(@NonNull String appCheckToken) {
                try {
                    onToken.accept(appCheckToken);
                } catch (Throwable t) {
                    Log.e(TAG, "onToken callback threw", t);
                }
            }
            @Override public void onSkippedOrDismissed(@NonNull Exception cause) {
                Log.w(TAG, "AppCheck token unavailable", cause);
            }
        });
    }

    /**
     * Detaylı kullanım: başarılı/başarısız ayrımı isteyenler için.
     * Başarıda onPassed(token), hata/skip durumunda onSkippedOrDismissed(Exception).
     */
    public static void getAppCheckTokenCached(boolean forceRefresh,
                                              @NonNull PassSkipCallback cb) {
        try {
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
            appCheck.getAppCheckToken(forceRefresh)
                    .addOnSuccessListener(result -> {
                        String token = (result != null) ? result.getToken() : null;
                        if (token == null || token.isEmpty()) {
                            cb.onSkippedOrDismissed(new IllegalStateException("Empty AppCheck token"));
                        } else {
                            cb.onPassed(token);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "getAppCheckToken failed", e);
                        cb.onSkippedOrDismissed(e);
                    });
        } catch (Throwable t) {
            Log.e(TAG, "getAppCheckTokenCached threw", t);
            cb.onSkippedOrDismissed(new RuntimeException("AppCheck not initialized", t));
        }
    }

    /**
     * Task tabanlı senkron ihtiyaçlar için yardımcı. UI thread’de çağırmayın.
     * @return App Check token string
     * @throws ExecutionException, TimeoutException, InterruptedException
     */
    public static @NonNull String getTokenAsync(boolean forceRefresh)
            throws ExecutionException, TimeoutException, InterruptedException {
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        Task<AppCheckToken> task = appCheck.getAppCheckToken(forceRefresh);
        AppCheckToken res = Tasks.await(task, 15, TimeUnit.SECONDS);
        String token = (res != null) ? res.getToken() : null;
        if (token == null || token.isEmpty()) {
            throw new ExecutionException(new IllegalStateException("Empty AppCheck token"));
        }
        return token;
    }

    /* =========================================================
     *  Play Store / GMS yönlendirme yardımcıları (opsiyonel)
     *  Integrity preflight’ı başarısızsa UI’dan çağırabilirsiniz.
     * =======================================================*/

    /** Google Play services (com.google.android.gms) sayfasını açar. */
    public static void openPlayServicesInPlayStore(@NonNull Context ctx) {
        openGoogleAppInPlayStore(ctx, "com.google.android.gms");
    }

    /** Google Play Store uygulamasının kendi sayfasını açar. */
    public static void openPlayStoreApp(@NonNull Context ctx) {
        openGoogleAppInPlayStore(ctx, "com.android.vending");
    }

    /** İstediğin paket için Play Store sayfasını açar. */
    public static void openGoogleAppInPlayStore(@NonNull Context ctx,
                                                @NonNull String packageName) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + packageName));
            intent.setPackage("com.android.vending");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // Play Store cihazda yoksa tarayıcıya düş
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(web);
        } catch (Throwable t) {
            Log.e(TAG, "openGoogleAppInPlayStore failed", t);
        }
    }

    /* =========================================================
     *  Callback tipleri
     * =======================================================*/

    /** Tek parametreli success callback (Java 8 Consumer alternatifi). */
    public interface StringCallback {
        void accept(@NonNull String token);
    }

    /** Başarılı/başarısız ayrımı isteyen çağrılar için. */
    public interface PassSkipCallback {
        void onPassed(@NonNull String appCheckToken);
        void onSkippedOrDismissed(@NonNull Exception cause);
    }
}
