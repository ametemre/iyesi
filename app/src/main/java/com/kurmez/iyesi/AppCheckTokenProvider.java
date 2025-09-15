package com.kurmez.iyesi;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.AppCheckProviderFactory;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import com.kurmez.iyesi.kayra.appCheck.PlayEnvDiagnostics;
import com.kurmez.iyesi.kayra.appCheck.TopActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Application + App Check helper:
 * - App Check & ID token cache
 * - Tek interceptor: X-Firebase-AppCheck + Authorization ekler
 * - 401/403'te force refresh + tek retry
 *
 * AndroidManifest.xml:
 * <application
 *     android:name=".AppCheckTokenProvider"
 *     ... />
 */
public class AppCheckTokenProvider extends Application {

    private static final String TAG = "AppCheckTP";
    private static volatile AppCheckTokenProvider sInstance;

    // Cloud Functions URL (Gradle'dan override edilebilir)
    private static final String DEFAULT_CF_URL =
            (BuildConfig.CF_URL_APP_SEND != null && !BuildConfig.CF_URL_APP_SEND.isEmpty())
                    ? BuildConfig.CF_URL_APP_SEND
                    : "https://us-central1-iyesi-e8d4f.cloudfunctions.net/appSend";

    // QA vb. varyantlarda release'te de debug provider'a düşebilmek için:
    // build.gradle(:app) -> defaultConfig:
    // buildConfigField "boolean", "APP_CHECK_ALLOW_DEBUG_FALLBACK", "true"
    private static final boolean ALLOW_DEBUG_FALLBACK = safeBooleanBC("APP_CHECK_ALLOW_DEBUG_FALLBACK", false);

    private static boolean safeBooleanBC(String field, boolean def) {
        try {
            Field f = BuildConfig.class.getField(field);
            return f.getBoolean(null);
        } catch (Throwable ignore) { return def; }
    }

    /* ------------------------------------------------------------------------
     * App Check token cache
     * --------------------------------------------------------------------- */
    private static volatile @Nullable String sAppCheckCached;
    private static volatile long sAppCheckExpMs; // epoch ms (yaklaşık)

    public interface AppCheckCb { void onReady(@Nullable String token); }
    public interface IdTokCb   { void onReady(@Nullable String idToken); }

    /** App Check token getir (cache + force opsiyonu). */
    public static void getAppCheckTokenCached(boolean force, @NonNull AppCheckCb cb) {
        long now = System.currentTimeMillis();
        if (!force && sAppCheckCached != null && now < (sAppCheckExpMs - 60_000L)) {
            cb.onReady(sAppCheckCached);
            return;
        }

        FirebaseAppCheck.getInstance().getAppCheckToken(force)
                .addOnSuccessListener((AppCheckToken t) -> {
                    if (t != null && t.getToken() != null) {
                        sAppCheckCached = t.getToken();
                        // AppCheckToken'ın public expiry bilgisi yok → ~55 dk cache
                        sAppCheckExpMs = System.currentTimeMillis() + 55 * 60_000L;
                        Log.d(TAG, "AppCheck refreshed (len=" + sAppCheckCached.length() + ")");
                        cb.onReady(sAppCheckCached);
                    } else {
                        Log.w(TAG, "getAppCheckToken: null token");
                        cb.onReady(null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getAppCheckToken(force=" + force + ") failed: " + e);
                    cb.onReady(null);
                });
    }

    /* ------------------------------------------------------------------------
     * ID token cache
     * --------------------------------------------------------------------- */
    private static volatile @Nullable String sIdTokenCached;
    private static volatile long sIdTokenAtMs; // ne zaman alındı
    private static final long IDTOKEN_TTL_MS = 30 * 60_000L; // 30 dk

    private static @Nullable String getIdTokenCachedBlocking(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && sIdTokenCached != null && (now - sIdTokenAtMs) < IDTOKEN_TTL_MS) {
            return sIdTokenCached;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] holder = new String[1];
        final Exception[] err = new Exception[1];

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnSuccessListener(ar -> {
                        FirebaseUser uu = FirebaseAuth.getInstance().getCurrentUser();
                        if (uu == null) { err[0] = new IllegalStateException("No user after anon"); latch.countDown(); return; }
                        uu.getIdToken(true)
                                .addOnSuccessListener(tr -> { holder[0] = tr.getToken(); latch.countDown(); })
                                .addOnFailureListener(e -> { err[0] = e; latch.countDown(); });
                    })
                    .addOnFailureListener(e -> { err[0] = e; latch.countDown(); });
        } else {
            u.getIdToken(force)
                    .addOnSuccessListener(tr -> { holder[0] = tr.getToken(); latch.countDown(); })
                    .addOnFailureListener(e -> { err[0] = e; latch.countDown(); });
        }

        try { latch.await(3000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        if (holder[0] == null) throw new IOException("Failed to get ID token", err[0]);
        sIdTokenCached = holder[0]; sIdTokenAtMs = now;
        return holder[0];
    }

    /* ------------------------------------------------------------------------
     * Tek Interceptor: AppCheck + ID token + 401/403'te tek retry
     * --------------------------------------------------------------------- */
    private static final Interceptor FIREBASE_HEADERS_INTERCEPTOR = chain -> {
        Request orig = chain.request();
        try { Log.d("HTTP", "→ " + orig.method() + " " + orig.url().encodedPath()); } catch (Throwable ignore) {}

        // 1) App Check token (önce cache; olmazsa force). Bulamazsak header'ı atlarız.
        String appTok = null;
        {
            final CountDownLatch latch = new CountDownLatch(1);
            final String[] h = new String[1];
            getAppCheckTokenCached(false, t -> { h[0] = t; latch.countDown(); });
            try { latch.await(1200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            if (h[0] == null) {
                final CountDownLatch latchF = new CountDownLatch(1);
                getAppCheckTokenCached(true, t -> { h[0] = t; latchF.countDown(); });
                try { latchF.await(1800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            }
            appTok = h[0]; // null olabilir
        }

        // 2) ID token (cache)
        String idTok = getIdTokenCachedBlocking(false);

        Request.Builder rb = orig.newBuilder()
                .header("Authorization", "Bearer " + idTok);

        if (appTok != null) {
            rb.header("X-Firebase-AppCheck", appTok);
        } else {
            Log.w(TAG, "No App Check token available; sending WITHOUT X-Firebase-AppCheck");
        }

        Response rsp = chain.proceed(rb.build());

        if (rsp.code() == 401 || rsp.code() == 403) {
            // Mevcut yanıtı kapat, sonra yenile.
            try { rsp.close(); } catch (Throwable ignore) {}
            Log.w("HTTP", "401/403 → force refresh & retry: " + orig.url().encodedPath());

            // Force refresh ikisi de
            final CountDownLatch latch2 = new CountDownLatch(1);
            final String[] freshApp = new String[1];
            getAppCheckTokenCached(true, t -> { freshApp[0] = t; latch2.countDown(); });
            try { latch2.await(1800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            String freshId = getIdTokenCachedBlocking(true);

            Request.Builder rb2 = orig.newBuilder()
                    .header("Authorization", "Bearer " + freshId);

            if (freshApp[0] != null) {
                rb2.header("X-Firebase-AppCheck", freshApp[0]);
            }

            Response rr = chain.proceed(rb2.build());
            try { Log.d("HTTP", "← " + rr.code() + " " + orig.url().encodedPath()); } catch (Throwable ignore) {}
            return rr;
        }

        try { Log.d("HTTP", "← " + rsp.code() + " " + orig.url().encodedPath()); } catch (Throwable ignore) {}
        return rsp;
    };

    /** Reusable OkHttp client (AppCheck + Auth header otomatik). */
    public static OkHttpClient clientWithAppCheck() {
        return new OkHttpClient.Builder()
                .addInterceptor(FIREBASE_HEADERS_INTERCEPTOR)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /* ------------------------------------------------------------------------
     * ID token yardımcıları (async kullanım isteyen yerler için)
     * --------------------------------------------------------------------- */
    public static void getIdTokenEnsuringAnon(@NonNull IdTokCb cb) {
        // Önce App Check’i ısıt (başarısız olursa da devam edebiliriz)
        getAppCheckTokenCached(false, t1 -> proceedSignIn(cb));
    }

    private static void proceedSignIn(@NonNull IdTokCb cb) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser u = auth.getCurrentUser();

        Task<AuthResult> ensureAnon = (u != null)
                ? Tasks.forResult((AuthResult) null)
                : auth.signInAnonymously();

        ensureAnon
                .onSuccessTask(v -> {
                    FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
                    if (cur == null) return Tasks.forException(new IllegalStateException("No user after anon sign-in"));
                    return cur.getIdToken(true); // ilk çağrıda tazele
                })
                .addOnSuccessListener((GetTokenResult r) -> cb.onReady(r.getToken()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getIdTokenEnsuringAnon failed", e);
                    cb.onReady(null);
                });
    }

    /* ------------------------------------------------------------------------
     * TLS Provider (opsiyonel ama faydalı)
     * --------------------------------------------------------------------- */
    private void safeInstallProviderIfNeeded(Context ctx) {
        try {
            GoogleApiAvailability api = GoogleApiAvailability.getInstance();
            int status = api.isGooglePlayServicesAvailable(ctx);
            if (status == ConnectionResult.SUCCESS) {
                ProviderInstaller.installIfNeededAsync(
                        ctx.getApplicationContext(),
                        new ProviderInstaller.ProviderInstallListener() {
                            @Override public void onProviderInstalled() {
                                Log.d(TAG, "TLS Provider yüklendi");
                            }
                            @Override public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                                Log.w(TAG, "TLS Provider yüklenemedi, code=" + errorCode);
                            }
                        }
                );
            } else {
                Log.w(TAG, "GMS yok/uygun değil (code=" + status + "), ProviderInstaller atlandı");
            }
        } catch (Throwable t) {
            Log.w(TAG, "ProviderInstaller threw; continuing.", t);
        }
    }

    /* ------------------------------------------------------------------------
     * Application lifecycle
     * --------------------------------------------------------------------- */
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        TopActivity.init(this);

        // 0) Play ortamı teşhisi (hızlı)
        PlayEnvDiagnostics.PlayEnvStatus env = PlayEnvDiagnostics.initPreflight(this);

        // 1) Firebase init
        try {
            FirebaseApp.initializeApp(this);
        } catch (Throwable t) {
            Log.e(TAG, "Firebase init failed", t);
        }

        // 2) App Check provider’ı kur (Firebase init'ten hemen sonra)
        configureAppCheckProvider(env);

        // 3) TLS provider (opsiyonel)
        safeInstallProviderIfNeeded(this);

        // 4) App Check warm-up (başarısız olursa force dener, uygulamayı bloklamaz)
        try {
            FirebaseAppCheck.getInstance()
                    .getAppCheckToken(false)
                    .addOnSuccessListener(t -> Log.d(TAG, "warm-up AppCheck OK"))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "warm-up fail; forcing refresh: " + e.getMessage());
                        FirebaseAppCheck.getInstance().getAppCheckToken(true)
                                .addOnSuccessListener(t2 -> Log.d(TAG, "warm-up force OK"))
                                .addOnFailureListener(err -> Log.e(TAG, "warm-up force fail", err));
                    });
        } catch (Throwable t) {
            Log.w(TAG, "AppCheck warm-up skipped (runtime missing?)", t);
        }

        // 5) ID token warm-up (oturum varsa refresh)
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseAuth.AuthStateListener st = new FirebaseAuth.AuthStateListener() {
            @Override public void onAuthStateChanged(@NonNull FirebaseAuth fa) {
                FirebaseUser u = fa.getCurrentUser();
                if (u != null) {
                    u.getIdToken(false).addOnFailureListener(err -> u.getIdToken(true));
                } else {
                    Log.w(TAG, "startup: Kullanıcı yok (gerektiğinde anon signin yapılacak).");
                }
                fa.removeAuthStateListener(this);
            }
        };
        auth.addAuthStateListener(st);

        if (BuildConfig.DEBUG) {
            Log.w(TAG, "APP_CHECK_DEBUG: Debug cihazını Console > App Check > Debug devices altında 'Allow' etmeyi unutma.");
        }
    }

    private void configureAppCheckProvider(PlayEnvDiagnostics.PlayEnvStatus env) {
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();

        boolean shouldUseDebug =
                BuildConfig.DEBUG
                        || (ALLOW_DEBUG_FALLBACK
                        && (env == PlayEnvDiagnostics.PlayEnvStatus.INTEGRITY_UNAVAILABLE_OR_BLOCKED
                        || env == PlayEnvDiagnostics.PlayEnvStatus.APP_NOT_INSTALLED_FROM_PLAY
                        || env == PlayEnvDiagnostics.PlayEnvStatus.PLAY_STORE_MISSING_OR_DISABLED));

        try {
            if (shouldUseDebug) {
                // Debug provider; reflection ile (release’te dependency yoksa crash olmasın)
                try {
                    Class<?> clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");
                    Object factory = clazz.getMethod("getInstance").invoke(null);
                    appCheck.installAppCheckProviderFactory((AppCheckProviderFactory) factory);
                    Log.i(TAG, "AppCheck provider installed: Debug (fallback=" + (!BuildConfig.DEBUG) + ")");
                } catch (Throwable e) {
                    Log.w(TAG, "Debug AppCheck provider unavailable; falling back to PlayIntegrity", e);
                    appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
                    Log.i(TAG, "AppCheck provider installed: PlayIntegrity");
                }
            } else {
                // Release varyantında Play Integrity
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
                Log.i(TAG, "AppCheck provider installed: PlayIntegrity");
            }
        } catch (Throwable t) {
            // Bu catch düşerse “No AppCheckProvider installed” görünebilir.
            Log.e(TAG, "AppCheck provider install FAILED", t);
        }
    }

    public static Context app() { return sInstance; }

    /* ------------------------------------------------------------------------
     * ÖRNEK: AppCheck + Auth ile HTTP çağrı
     * --------------------------------------------------------------------- */

    /** Overload: URL vermeden çağırmak için. */
    public void sendRequestWithAppCheckAndAuth(@NonNull JSONObject payload) {
        sendRequestWithAppCheckAndAuth(DEFAULT_CF_URL, payload);
    }

    /** Asıl uygulama: URL + payload. */
    public void sendRequestWithAppCheckAndAuth(@NonNull String url, @NonNull JSONObject payload) {
        OkHttpClient ok = clientWithAppCheck();

        // İstek gövdesi
        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.get("application/json; charset=utf-8")
        );

        // Burada Authorization eklemeye gerek yok; interceptor ekleyecek.
        // Yine de ilk isteğin hızlı olması için ID token'ı önceden çekmek iyi olur:
        try { getIdTokenCachedBlocking(false); } catch (IOException ignore) {}

        Request req = new Request.Builder()
                .url(url)
                .post(body)
                .build();

        ok.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "HTTP çağrı hatası", e);
            }
            @Override public void onResponse(@NonNull Call call, @NonNull Response rsp) throws IOException {
                String b = (rsp.body() != null) ? rsp.body().string() : "";
                Log.d(TAG, "HTTP " + rsp.code() + " | body=" + b);
                if (rsp.body() != null) rsp.close();
            }
        });
    }
}
