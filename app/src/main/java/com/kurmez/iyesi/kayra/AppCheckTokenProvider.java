package com.kurmez.iyesi.kayra;

import static com.google.firebase.appcheck.FirebaseAppCheck.*;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.android.gms.tasks.OnFailureListener;
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

import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.kayra.QR.QR;
import com.kurmez.iyesi.kayra.QR.QrRouteResolver;
import com.kurmez.iyesi.kayra.appCheck.PlayEnvDiagnostics;
import com.kurmez.iyesi.kayra.appCheck.TopActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
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
// imports (varsa tekrar etmeyin)
import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.kurmez.iyesi.kurmes.Kurmes;


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
    // --- Membership Guard (CustomClaims-only) ---
    private static final java.util.concurrent.atomic.AtomicBoolean sRedirecting =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // Hedef ve whitelist (tam sınıf adları)
    private static final String IYE_ACTIVITY_QNAME = "com.kurmez.iyesi.kayra.IyeActivity";
    private static final java.util.Set<String> GUARD_WHITELIST =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    IYE_ACTIVITY_QNAME,
                    "com.kurmez.iyesi.Login",
                    "com.kurmez.iyesi.Register",
                    "com.kurmez.iyesi.umay.Welcome"
            ));

    // Sadece bir kez kurmak için guard
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
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

        getInstance().getAppCheckToken(force)
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
// Örn. App.java (Application.onCreate):
        /**QR.installResolver((ctx, route) -> {
            List<String> seg = route.getPathSegments(); // iyesi://souls/123 → ["souls","123"]
            String first = seg.isEmpty() ? "" : seg.get(0).toLowerCase(Locale.ROOT);

            switch (first) {
                case "souls": {
                    String id = seg.size() > 1 ? seg.get(1) : null;
                    Intent i = new Intent(ctx, com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity.class);
                    if (id != null) i.putExtra("soulId", id);
                    return i;
                }
                case "sokak": {
                    Intent i = new Intent(ctx, com.kurmez.iyesi.umay.SokakActivity.class);
                    if (route.getQueryParameter("lat") != null) {
                        i.putExtra("lat", route.getQueryParameter("lat"));
                        i.putExtra("lng", route.getQueryParameter("lng"));
                    }
                    return i;
                }
                case "sahiplendirme": {
                    String id = seg.size() > 1 ? seg.get(1) : null;
                    Intent i = new Intent(ctx, com.kurmez.iyesi.umay.sahiplendirme.Sahiplendirme.class);
                    if (id != null) i.putExtra("postId", id);
                    return i;
                }
                case "qr": {
                    if (seg.size() > 2 && "admin".equalsIgnoreCase(seg.get(1))) {
                        Intent i = new Intent(ctx, com.kurmez.iyesi.kayra.QR.QRAdmin.class);
                        i.putExtra("code", seg.get(2));
                        return i;
                    }
                    break;
                }
                case "kurmes": {
                    Intent i = new Intent(ctx, Kurmes.class);
                    return i;
                }
            }
            // Bilinmeyen route → ana ekran
            return new Intent(ctx, com.kurmez.iyesi.MainActivity.class);
        });*/
        QR.installResolver(new QrRouteResolver());

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
            getInstance()
                    .getAppCheckToken(false)
                    .addOnSuccessListener(t -> Log.d(TAG, "warm-up AppCheck OK"))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "warm-up fail; forcing refresh: " + e.getMessage());
                        getInstance().getAppCheckToken(true)
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
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(@NonNull Activity a) {  }
            @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
            @Override public void onActivityStarted(@NonNull Activity a) {}
            @Override public void onActivityPaused(@NonNull Activity a) {}
            @Override public void onActivityStopped(@NonNull Activity a) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
            @Override public void onActivityDestroyed(@NonNull Activity a) {}
        });
    }

    private void configureAppCheckProvider(PlayEnvDiagnostics.PlayEnvStatus env) {
        FirebaseAppCheck appCheck = getInstance();

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
    private static boolean isRegisteredUser(@NonNull FirebaseUser u) {
        return !u.isAnonymous();
    }

    @SuppressWarnings("unchecked")
    private static boolean isMemberFromClaims(@Nullable java.util.Map<String,Object> claims) {
        if (claims == null) return false;
        Object roles = claims.get("roles");
        boolean hasRole = (roles instanceof java.util.List && !((java.util.List<?>) roles).isEmpty())
                || (roles instanceof String && !((String) roles).trim().isEmpty());
        boolean memberFlag = Boolean.TRUE.equals(claims.get("isMember"))
                || Boolean.TRUE.equals(claims.get("profileComplete"));
        return hasRole || memberFlag;
    }

    private static void gotoActivity(@NonNull Activity a, @NonNull String qname, @Nullable String reason) {
        a.runOnUiThread(() -> {
            Intent i = new Intent();
            i.setClassName(a.getPackageName(), qname);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (reason != null) i.putExtra("reason", reason);
            if (qname == IYE_ACTIVITY_QNAME){a.startActivity(IyeActivity.intentFromGuard(a.getBaseContext()));}
            else {a.startActivity(i);}
            a.finish(); // yığın şişmesin
        });
    }

    public static void runMembershipGuard(@NonNull Activity a) {
        String qname = a.getClass().getName();
        if (GUARD_WHITELIST.contains(qname)) {
            Log.d("Guard", "skip (whitelist): " + qname);
            return;
        }

        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) { Log.d("Guard","user=null → guest pass"); return; }
        if (!isRegisteredUser(u)) { Log.d("Guard","user=anonymous → guest pass"); return; }

        u.getIdToken(false)
                .addOnSuccessListener(res -> {
                    java.util.Map<String,Object> claims = res.getClaims();
                    Log.d("Guard", "claimsKeys=" + (claims==null? "null" : claims.keySet()));

                    // claims yok/boş → IyeActivity
                    if (claims == null || claims.isEmpty()) {
                        if (sRedirecting.compareAndSet(false, true)) {
                            try { gotoActivity(a, IYE_ACTIVITY_QNAME, "claims_null"); }
                            finally { sRedirecting.set(false); }
                        }
                        return;
                    }

                    // üye değil → IyeActivity
                    if (!isMemberFromClaims(claims)) {
                        if (sRedirecting.compareAndSet(false, true)) {
                            try { gotoActivity(a, IYE_ACTIVITY_QNAME, "not_member"); }
                            finally { sRedirecting.set(false); }
                        }
                    } else {
                        Log.d("Guard","member=true → pass");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w("Guard","getIdToken(false) failed → IyeActivity", e);
                    if (sRedirecting.compareAndSet(false, true)) {
                        try { gotoActivity(a, IYE_ACTIVITY_QNAME, "claims_error"); }
                        finally { sRedirecting.set(false); }
                    }
                });
    }


    // 1) Uygulama açılışında çağrılacak init
    public static void init(Context appContext) {
        if (INITIALIZED.getAndSet(true)) return;        // idempotent

        FirebaseApp.initializeApp(appContext);

        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        if (BuildConfig.DEBUG) {
            appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
        } else {
            appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
        }

        // X-Firebase-Locale uyarılarını susturur
        FirebaseAuth.getInstance().setLanguageCode("tr");
    }

    // 2) App Check token hazır olunca çalıştır
    public static void whenReady(Runnable action, java.util.function.Consumer<Exception> onFail) {
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
                .addOnSuccessListener(t -> action.run())
                .addOnFailureListener(e ->
                        FirebaseAppCheck.getInstance().getAppCheckToken(true) // tek seferlik force refresh
                                .addOnSuccessListener(t2 -> action.run())
                                .addOnFailureListener((OnFailureListener) onFail)
                );
    }


}