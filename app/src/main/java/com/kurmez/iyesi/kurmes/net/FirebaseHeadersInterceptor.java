package com.kurmez.iyesi.kurmes.net;

import android.provider.Settings;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.IOException;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

// ------------------------ Support: Headers Interceptor ------------------------
public final class FirebaseHeadersInterceptor implements Interceptor {

    private static final long TOKEN_TTL_MS = 4 * 60 * 1000; // 4dk: SDK zaten cache ediyor, biz de minimum tekrar kontrolü
    private volatile String cachedIdToken;
    private volatile long cachedIdTokenAt = 0L;

    private volatile String cachedAppCheck;
    private volatile long cachedAppCheckAt = 0L;
    @Override
    public Response intercept(Chain chain) throws IOException {
        Request original = chain.request();

        // 1) Kullanıcı ve tokenları al
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            // anonim login vs. burada yapıyorsan yap; yoksa 401 dönebilir
            throw new IOException("No FirebaseUser");
        }

        // <-- BURAYA: senin metodun çağrısı -->
        String idToken = ensureIdToken(user, /*force=*/false);     // senin verdiğin metod
        String appCheck = ensureAppCheck(/*force=*/false);         // mevcutsa senin AppCheck metodu

        // 2) Header’ları isteğe ekle
        Request.Builder rb = original.newBuilder()
                .header("Authorization", "Bearer " + idToken);

        if (appCheck != null && !appCheck.isEmpty()) {
            rb.header("X-Firebase-AppCheck", appCheck);
        }

        Response rsp = chain.proceed(rb.build());

        // 3) 401/403 gelirse 1 kez force refresh + retry (opsiyonel ama önerilir)
        if (rsp.code() == 401 || rsp.code() == 403) {
            rsp.close(); // eski response'u kapat
            String freshId = ensureIdToken(user, /*force=*/true);
            String freshApp = ensureAppCheck(/*force=*/true);

            Request.Builder retry = original.newBuilder()
                    .header("Authorization", "Bearer " + freshId);
            if (freshApp != null && !freshApp.isEmpty()) {
                retry.header("X-Firebase-AppCheck", freshApp);
            }
            return chain.proceed(retry.build());
        }

        return rsp;
    }


    private String ensureIdToken(FirebaseUser user, boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && cachedIdToken != null && (now - cachedIdTokenAt) < TOKEN_TTL_MS) {
            return cachedIdToken;
        }
        try {
            String t = Tasks.await(user.getIdToken(force)).getToken();
            if (t == null || t.isEmpty()) throw new IOException("Empty ID token");
            cachedIdToken = t; cachedIdTokenAt = now;
            return t;
        } catch (Exception e) {
            throw new IOException("Failed to get ID token", e);
        }
    }

    private String ensureAppCheck(boolean force) throws IOException {
        long now = System.currentTimeMillis();
        if (!force && cachedAppCheck != null && (now - cachedAppCheckAt) < TOKEN_TTL_MS) {
            return cachedAppCheck;
        }
        try {
            var task = FirebaseAppCheck.getInstance().getAppCheckToken(true);
            var result = Tasks.await(task);
            String t = result != null ? result.getToken() : null;
            cachedAppCheck = t; cachedAppCheckAt = now;
            return t;
        } catch (Exception e) {
            // App Check zorunlu değilse sessiz geç; zorunluysa CF zaten 403 döndürecek
            return null;
        }
    }

    private String getDeviceId() {
        try {
            // Uygulamanın herhangi bir yerde sakladığın bir Context erişimin varsa buraya koyabilirsin.
            // Global bir context yoksa bu header'ı kullanma.
            return Settings.Secure.getString(
                    // replace with your UI/Application context getter if needed
                    com.kurmez.iyesi.kayra.TopActivity.uiContext().getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
        } catch (Exception ignore) {
            return null;
        }
    }
}
