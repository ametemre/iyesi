package com.kurmez.iyesi;

import android.app.Activity;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
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
    private static volatile AppCheckTokenProvider sInstance;

    // Tercihen Gradle'dan yönet: build.gradle (app) -> buildConfigField
    // defaultConfig { buildConfigField "String", "CF_URL_APP_SEND", "\"https://us-central1-iyesi-e8d4f.cloudfunctions.net/appSend\"" }
    private static final String DEFAULT_CF_URL =
            safeDefault(BuildConfig.CF_URL_APP_SEND, "https://us-central1-iyesi-e8d4f.cloudfunctions.net/appSend");

    // -----------------------------
    // AppCheck token cache
    // -----------------------------
    private static volatile @Nullable String sAppCheckCached;
    private static volatile long sAppCheckExpMs; // epoch ms (yaklaşık)

    public interface AppCheckCb { void onReady(@Nullable String token); }
    public interface IdTokCb   { void onReady(@Nullable String idToken); }

    private static String safeDefault(@Nullable String v, @NonNull String def) {
        return (v == null || v.isEmpty()) ? def : v;
    }

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
                        // AppCheckToken şu an expiry vermiyor → 55 dk tahmini cache
                        sAppCheckExpMs = System.currentTimeMillis() + 55 * 60_000L;
                        cb.onReady(sAppCheckCached);
                    } else {
                        Log.w(TAG, "getAppCheckToken: null token");
                        cb.onReady(null);
                    }
                })
                .addOnFailureListener(e -> {
                    String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";
                    Log.w(TAG, "getAppCheckToken(force=" + force + ") failed: " + msg);
                    // Integrity -2 → resmi Play Store yok / eski → otomatik yönlendir
                    if (looksLikeIntegrityMinusTwo(msg)) {
                        handleIntegrityMinusTwo();
                    }
                    cb.onReady(null);
                });
    }

    // -----------------------------
    // OkHttp Interceptor (AppCheck)
    // -----------------------------
    private static final Interceptor APPCHECK_INTERCEPTOR = chain -> {
        Request orig = chain.request();

        // 1) Cache → kısa bekleme (bloklama OkHttp worker thread'inde; main thread değil)
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] holder = new String[1];
        getAppCheckTokenCached(false, t -> { holder[0] = t; latch.countDown(); });
        try { latch.await(1200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

        // 1.a) Token yoksa → bir defa force refresh dene
        if (holder[0] == null) {
            final CountDownLatch latchF = new CountDownLatch(1);
            getAppCheckTokenCached(true, t -> { holder[0] = t; latchF.countDown(); });
            try { latchF.await(1800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
        }

        if (holder[0] == null) {
            // Enforcement açıksa boş atış yapma
            throw new IOException("App Check token yok; isteği iptal ediyorum (enforced).");
        }

        Request withHdr = orig.newBuilder()
                .header("X-Firebase-AppCheck", holder[0])
                .build();

        Response rsp = chain.proceed(withHdr);

        // 2) 401/403 → tek sefer daha force-refresh ile yeniden dene
        if (rsp.code() == 401 || rsp.code() == 403) {
            rsp.close();
            final CountDownLatch latch2 = new CountDownLatch(1);
            final String[] fresh = new String[1];
            getAppCheckTokenCached(true, t -> { fresh[0] = t; latch2.countDown(); });
            try { latch2.await(1800, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

            if (fresh[0] == null) {
                throw new IOException("App Check refresh başarısız; 401/403 sonrası abort.");
            }

            Request retry = orig.newBuilder()
                    .header("X-Firebase-AppCheck", fresh[0])
                    .build();
            return chain.proceed(retry);
        }

        return rsp;
    };

    /** Reusable OkHttp client (X-Firebase-AppCheck otomatik eklenir). */
    public static OkHttpClient clientWithAppCheck() {
        return new OkHttpClient.Builder()
                .addInterceptor(APPCHECK_INTERCEPTOR)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // -----------------------------
    // ID Token yardımcıları (App Check hazır olmadan Auth'a girme)
    // -----------------------------
    public static void getIdTokenEnsuringAnon(@NonNull IdTokCb cb) {
        // 1) App Check token (önce cache, yoksa force)
        getAppCheckTokenCached(false, t1 -> {
            if (t1 == null) {
                getAppCheckTokenCached(true, t2 -> {
                    if (t2 == null) {
                        Log.e(TAG, "App Check token alınamadı; anon sign-in denemeyeceğim.");
                        cb.onReady(null);
                    } else {
                        proceedSignIn(cb);
                    }
                });
            } else {
                proceedSignIn(cb);
            }
        });
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
                    // true → tazele; release’te ilk çağrılarda 401 riskini düşürür
                    return cur.getIdToken(true);
                })
                .addOnSuccessListener((GetTokenResult r) -> cb.onReady(r.getToken()))
                .addOnFailureListener(e -> {
                    Log.e(TAG, "getIdTokenEnsuringAnon failed", e);
                    cb.onReady(null);
                });
    }

    // -----------------------------
    // TLS Provider (opsiyonel ama faydalı)
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
        configureAppCheckProvider();

        // TLS provider kurulumu (opsiyonel)
        safeInstallProviderIfNeeded(this);

        // Cihaz önkoşulları (Play Store & GMS) – activity hazır olunca kontrol et
        postEnsurePlayPrerequisites();

        // App Check warm-up (cache → yoksa üret) + log
        FirebaseAppCheck.getInstance()
                .getAppCheckToken(false)
                .addOnSuccessListener(t -> Log.d(TAG, "warm-up AppCheck OK"))
                .addOnFailureListener(e -> {
                    String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";
                    Log.w(TAG, "warm-up fail; forcing refresh: " + msg);
                    if (looksLikeIntegrityMinusTwo(msg)) {
                        handleIntegrityMinusTwo();
                    }
                    FirebaseAppCheck.getInstance().getAppCheckToken(true)
                            .addOnSuccessListener(t2 -> Log.d(TAG, "warm-up force OK"))
                            .addOnFailureListener(err -> {
                                Log.e(TAG, "warm-up force fail", err);
                                if (err != null && looksLikeIntegrityMinusTwo(String.valueOf(err.getMessage()))) {
                                    handleIntegrityMinusTwo();
                                }
                            });
                });

        // ID token warm-up: oturum varsa refresh et; yoksa burada otomatik anon açmıyoruz
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

    private void configureAppCheckProvider() {
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();

        if (BuildConfig.DEBUG) {
            // Debug varyantında Debug Provider; reflection ile yükle ki release AAB'da sınıf bulunamasa da crash olmasın
            try {
                Class<?> clazz = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");
                Object factory = clazz.getMethod("getInstance").invoke(null);
                appCheck.installAppCheckProviderFactory((AppCheckProviderFactory) factory);
            } catch (Throwable e) {
                Log.w(TAG, "Debug AppCheck provider unavailable", e);
            }
        } else {
            // Release varyantında Play Integrity
            appCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
            );
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

        // 1) ID token (gerekirse anonim) — App Check token hazır olmadan Auth'a girmeyeceğiz
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

    // =====================================================================
    //                        Play Store / GMS yardımcıları
    // =====================================================================

    /** Açılışta, Activity hazır olduğunda Play/GMS önkoşullarını kontrol et. */
    private void postEnsurePlayPrerequisites() {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Activity a = TopActivity.current();
            if (a != null) ensurePlayPrerequisites(a);
        }, 800); // kısa gecikme: ilk activity attach olsun
    }

    /** Integrity -2 yakalandığında otomatik Play Store detay sayfasını aç. */
    private static void handleIntegrityMinusTwo() {
        new Handler(Looper.getMainLooper()).post(() -> {
            Activity a = TopActivity.current();
            if (a != null) {
                openPlayDetails(a, "com.android.vending");
            } else {
                Log.w(TAG, "No foreground activity to open Play Store.");
            }
        });
    }

    /** Resmi Play Store / GMS hazır mı? Değilse çözüm ekranlarına yönlendir. */
    private static void ensurePlayPrerequisites(@NonNull Activity activity) {
        boolean ok = true;

        // 1) Google Play services
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int gms = api.isGooglePlayServicesAvailable(activity);
        if (gms != ConnectionResult.SUCCESS) {
            ok = false;
            try {
                if (api.isUserResolvableError(gms)) {
                    api.getErrorDialog(activity, gms, 1001).show();
                } else {
                    openPlayDetails(activity, "com.google.android.gms");
                }
            } catch (Throwable t) {
                openPlayDetails(activity, "com.google.android.gms");
            }
        }

        // 2) Play Store var mı ve çok eski mi?
        if (!isInstalled(activity.getPackageManager(), "com.android.vending")) {
            ok = false;
            openPlayDetails(activity, "com.android.vending");
        } else {
            try {
                PackageInfo pi = activity.getPackageManager().getPackageInfo("com.android.vending", 0);
                long minVersionCode = 83200000L; // örnek eşik (~v38+). İstersen yükselt.
                if (pi.getLongVersionCode() < minVersionCode) {
                    ok = false;
                    openPlayDetails(activity, "com.android.vending");
                }
            } catch (Exception ignore) { /* no-op */ }
        }

        Log.d(TAG, "ensurePlayPrerequisites -> " + ok);
    }

    private static boolean isInstalled(PackageManager pm, String pkg) {
        try { pm.getPackageInfo(pkg, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private static void openPlayDetails(@NonNull Activity activity, @NonNull String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            i.setPackage("com.android.vending");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Intent web = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
            web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(web);
        }
    }

    private static boolean looksLikeIntegrityMinusTwo(@NonNull String message) {
        // Firebase/AppCheck/PlayCore farklı metinlerle -2'yi raporlayabiliyor
        String m = message.toLowerCase();
        return m.contains("integrity api error (-2)")
                || m.contains("play store app is either not installed")
                || m.contains("unknown calling package") // bazı cihazlarda benzer kök neden
                || m.contains("phenotype.api is not available"); // play services özelliği eksikliği
    }
}
