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
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import com.kurmez.iyesi.kayra.TopActivity;

import org.json.JSONObject;

import java.io.IOException;
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
 * Application + App Check helper (cached token + OkHttp interceptor).
 *
 * Manifest:
 *   <application
 *       android:name=".AppCheckTokenProvider"
 *       ... />
 */
public class AppCheckTokenProvider extends Application {

    private static final String TAG = "AppCheckTP";
    private static AppCheckTokenProvider sInstance;

    // Varsayılan CF URL (tek parametreli overload için)
    private static final String DEFAULT_CF_URL =
            "https://us-central1-iyesi-e8d4f.cloudfunctions.net/appSend";

    // -----------------------------
    // AppCheck token cache
    // -----------------------------
    private static volatile @Nullable String sAppCheckCached;
    private static volatile long sAppCheckExpMs; // epoch ms

    public interface AppCheckCb { void onReady(@Nullable String token); }
    public interface IdTokCb   { void onReady(@Nullable String idToken); }

    /**
     * App Check token'ını getirir. Cache uygunsa cache'den döner; değilse SDK'dan çeker.
     * force=true verilirse yenilemeye zorlar.
     */
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
                        // Yaklaşık 55 dk cache (SDK public expire süresi vermiyor)
                        sAppCheckExpMs = System.currentTimeMillis() + 55 * 60_000L;
                        cb.onReady(sAppCheckCached);
                    } else {
                        Log.w(TAG, "getAppCheckToken: null token");
                        cb.onReady(null);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getAppCheckToken(force=" + force + ") failed: " + e.getMessage());
                    cb.onReady(null);
                });
    }

    // -----------------------------
    // OkHttp Interceptor (AppCheck)
    // -----------------------------
    private static final Interceptor APPCHECK_INTERCEPTOR = chain -> {
        Request orig = chain.request();

        // 1) Önce cache'den dene (kısa bekleme)
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] holder = new String[1];
        getAppCheckTokenCached(false, t -> { holder[0] = t; latch.countDown(); });
        try { latch.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

        // 1.a) İlk atışta token yoksa → force refresh dene (kısa bekleme)
        if (holder[0] == null) {
            final CountDownLatch latchF = new CountDownLatch(1);
            getAppCheckTokenCached(true, t -> { holder[0] = t; latchF.countDown(); });
            try { latchF.await(2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }

        Request.Builder b1 = orig.newBuilder();
        if (holder[0] != null) b1.header("X-Firebase-AppCheck", holder[0]);
        Response rsp = chain.proceed(b1.build());

        // 2) 401/403 ise → tek sefer daha force-refresh ile yeniden dene
        if (rsp.code() == 401 || rsp.code() == 403) {
            rsp.close();
            final CountDownLatch latch2 = new CountDownLatch(1);
            final String[] fresh = new String[1];
            getAppCheckTokenCached(true, t -> { fresh[0] = t; latch2.countDown(); });
            try { latch2.await(2000, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            Request.Builder b2 = orig.newBuilder();
            if (fresh[0] != null) b2.header("X-Firebase-AppCheck", fresh[0]);
            return chain.proceed(b2.build());
        }

        return rsp;
    };

    /** Reusable OkHttp client (X-Firebase-AppCheck otomatik eklenir). */
    public static OkHttpClient clientWithAppCheck() {
        return new OkHttpClient.Builder()
                .addInterceptor(APPCHECK_INTERCEPTOR)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // -----------------------------
    // ID Token yardımcıları (opsiyonel)
    // -----------------------------

    /**
     * Kullanıcı yoksa anonim giriş yapar ve ID token döndürür; varsa direkt ID token döndürür.
     * Başarısız olursa null döner.
     */
    public static void getIdTokenEnsuringAnon(@NonNull IdTokCb cb) {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser u = auth.getCurrentUser();

        Task<AuthResult> ensureAnon = (u != null)
                ? Tasks.forResult(null)
                : auth.signInAnonymously();

        ensureAnon
                .onSuccessTask(v -> {
                    FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
                    if (cur == null) return Tasks.forException(new IllegalStateException("No user after anon sign-in"));
                    return cur.getIdToken(true);
                })
                .addOnSuccessListener((GetTokenResult r) -> cb.onReady(r.getToken()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getIdTokenEnsuringAnon failed", e);
                    cb.onReady(null);
                });
    }

    // -----------------------------
    // TLS Provider (isteğe bağlı ama faydalı)
    // -----------------------------
    private void safeInstallProviderIfNeeded(Context ctx) {
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int status = api.isGooglePlayServicesAvailable(ctx);
        if (status == ConnectionResult.SUCCESS) {
            ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
                @Override public void onProviderInstalled() {
                    Log.d(TAG, "TLS Provider yüklendi");
                }
                @Override public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                    Log.w(TAG, "TLS Provider yüklenemedi, code=" + errorCode);
                }
            });
        } else {
            Log.w(TAG, "Google Play Services yok/uygun değil (code=" + status + "), ProviderInstaller atlandı");
        }
    }

    // -----------------------------
    // Application lifecycle
    // -----------------------------
    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        TopActivity.init(this);

        FirebaseApp.initializeApp(this);

        // App Check provider'ı tam olarak 1 kez kur
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        appCheck.installAppCheckProviderFactory(
                BuildConfig.DEBUG
                        ? DebugAppCheckProviderFactory.getInstance()
                        : PlayIntegrityAppCheckProviderFactory.getInstance()
        );

        // TLS provider kurulumu (opsiyonel ama SSL hatalarını azaltır)
        safeInstallProviderIfNeeded(this);

        // App Check warm-up (cache → yoksa üret)
        FirebaseAppCheck.getInstance()
                .getAppCheckToken(false)
                .addOnFailureListener(e -> FirebaseAppCheck.getInstance().getAppCheckToken(true));

        // ID token warm-up: oturum varsa refresh et; yoksa burada otomatik anon açmıyoruz (akışına bırak)
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseAuth.AuthStateListener st = new FirebaseAuth.AuthStateListener() {
            @Override public void onAuthStateChanged(@NonNull FirebaseAuth fa) {
                FirebaseUser u = fa.getCurrentUser();
                if (u != null) {
                    u.getIdToken(false).addOnFailureListener(err -> u.getIdToken(true));
                } else {
                    Log.w(TAG, "startup: Kullanıcı yok (gerekirse çağrı öncesi anon signin yapılacak).");
                }
                fa.removeAuthStateListener(this);
            }
        };
        auth.addAuthStateListener(st);

        if (BuildConfig.DEBUG) {
            Log.w(TAG, "APP_CHECK_DEBUG: Bu cihazın Debug token'ını Firebase Console > App Check > Debug devices altında 'Allow' etmeyi unutma.");
        }
    }

    public static Context app() { return sInstance; }

    // -----------------------------
    // ÖRNEK: AppCheck + Auth ile HTTP çağrı
    // -----------------------------

    /** Overload: URL vermeden çağırmak için (MainActivity kullanımınla uyumlu). */
    public void sendRequestWithAppCheckAndAuth(@NonNull JSONObject payload) {
        sendRequestWithAppCheckAndAuth(DEFAULT_CF_URL, payload);
    }

    /** Asıl uygulama: URL + payload. */
    public void sendRequestWithAppCheckAndAuth(@NonNull String url, @NonNull JSONObject payload) {
        OkHttpClient ok = clientWithAppCheck();

        // 1) ID token (gerekirse anonim)
        getIdTokenEnsuringAnon(idToken -> {
            if (idToken == null) {
                Log.e(TAG, "ID token alınamadı; isteği gönderemem.");
                return;
            }

            // 2) İstek gövdesi
            RequestBody body = RequestBody.create(
                    payload.toString(),
                    MediaType.get("application/json; charset=utf-8")
            );

            // 3) Authorization başlığı (App Check başlığını interceptor ekleyecek)
            Request req = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + idToken)
                    .post(body)
                    .build();

            ok.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "HTTP çağrı hatası", e);
                }

                @Override public void onResponse(@NonNull Call call, @NonNull Response rsp) throws IOException {
                    String b = (rsp.body() != null) ? rsp.body().string() : "";
                    Log.d(TAG, "HTTP " + rsp.code() + " | body=" + b);
                }
            });
        });
    }
}
