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

    @Override public Response intercept(Chain chain) throws IOException {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) throw new IOException("No user");

        String idToken = ensureIdToken(user, /*force*/ false);
        String appCheck = ensureAppCheck(/*force*/ false); // debug'da null olabilir

        Request.Builder rb = chain.request().newBuilder()
                .header("Authorization", "Bearer " + idToken);

        if (appCheck != null && !appCheck.isEmpty()) {
            rb.header("X-Firebase-AppCheck", appCheck);
        }

        // İsteğe bağlı cihaz kimliği (kullanıyorsan)
        String deviceId = getDeviceId();
        if (deviceId != null) rb.header("X-Device-Id", deviceId);

        Response resp = chain.proceed(rb.build());
        if (!resp.isSuccessful()) {
            String err = null;
            if (resp.body() != null) {
                err = resp.peekBody(1024 * 1024).string(); // tercih: peekBody tüketmez
            }
        }
        // 401 ise: bir defa idToken'ı zorla yenileyip replay et
        if (resp.code() == 401) {
            resp.close();
            idToken = ensureIdToken(user, /*force*/ true);
            Request retry = chain.request().newBuilder()
                    .header("Authorization", "Bearer " + idToken)
                    .build();
            return chain.proceed(retry);
        }
        return resp;
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
