package com.kurmez.iyesi.kurmes.utilities.helper.net;

import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.getTokens;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.kayra.Classes.Harita;
import com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper;
import com.kurmez.iyesi.umay.SokakActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * CFClient — Cloud Functions HTTP istemcisi.
 *
 * Özellikler:
 *  - Firebase ID Token + App Check header ekleme (FirebaseHeadersInterceptor ile)
 *  - GET/POST/PATCH/DELETE yardımcıları
 *  - 2xx dışındaki yanıtları IOException ile (body dahil) fırlatır
 *  - execToString / execToJson / getJson / postJson imzaları korunmuştur
 */
public class CFClient {

    public static final String TAG = "CFClient";
    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final ExecutorService io;
    private final Handler main;
    private @Nullable String baseUrl;    // İsteğe bağlı taban URL (Welcome/SoulsManager gibi sınıflar path veriyorsa kullanılır)

    public CFClient(@NonNull OkHttpClient client) {
        this.http = client;
        this.io = Executors.newFixedThreadPool(2);
        this.main = new Handler(Looper.getMainLooper());
    }//Dışarıdan sağlanan OkHttpClient ile.
    public CFClient(@NonNull String baseUrl) {
        this(buildDefaultClient());
        // Sonda / varsa kaldır
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }//------------------------------------------------ Base URL alan kurucu. Örn: new CFClient("https://us-central1-...cloudfunctions.net")
    // ----------------------------- PUBLIC SYNC API -----------------------------
    @NonNull
    public JSONObject patchJson(@NonNull String url, @NonNull JSONObject body) throws IOException {
        Request req = new Request.Builder()
                .url(url)
                .patch(RequestBody.create(body.toString(), JSON))
                .build();
        return execToJson(req);
    }//PATCH (application/json).
    // ------------------- Düşük seviyeli token'lı HTTP metodları ------------------
    public Response get(@NonNull String pathOrUrl, @Nullable String idToken, @Nullable String appCheckToken) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(resolveUrl(pathOrUrl))
                .get();
        if (idToken != null && !idToken.isEmpty()) {
            b.header("Authorization", "Bearer " + idToken);
        }
        if (appCheckToken != null && !appCheckToken.isEmpty()) {
            b.header("X-Firebase-AppCheck", appCheckToken);
        }
        return http.newCall(b.build()).execute();
    }//GET (token'lı) — try-with-resources için Response döner.
    public Response post(@NonNull String pathOrUrl, @NonNull String rawJson, @Nullable String idToken, @Nullable String appCheckToken) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(resolveUrl(pathOrUrl))
                .post(RequestBody.create(rawJson, JSON));
        if (idToken != null && !idToken.isEmpty()) {
            b.header("Authorization", "Bearer " + idToken);
        }
        if (appCheckToken != null && !appCheckToken.isEmpty()) {
            b.header("X-Firebase-AppCheck", appCheckToken);
        }
        return http.newCall(b.build()).execute();
    }//POST (raw JSON + token) — try-with-resources için Response döner.
    public Response patch(@NonNull String pathOrUrl, @NonNull String rawJson, @Nullable String idToken, @Nullable String appCheckToken) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(resolveUrl(pathOrUrl))
                .patch(RequestBody.create(rawJson, JSON));
        if (idToken != null && !idToken.isEmpty()) {
            b.header("Authorization", "Bearer " + idToken);
        }
        if (appCheckToken != null && !appCheckToken.isEmpty()) {
            b.header("X-Firebase-AppCheck", appCheckToken);
        }
        return http.newCall(b.build()).execute();
    }//PATCH (raw JSON + token) — try-with-resources için Response döner.
    public Response delete(@NonNull String pathOrUrl, @Nullable String idToken, @Nullable String appCheckToken) throws IOException {
        Request.Builder b = new Request.Builder()
                .url(resolveUrl(pathOrUrl))
                .delete();
        if (idToken != null && !idToken.isEmpty()) {
            b.header("Authorization", "Bearer " + idToken);
        }
        if (appCheckToken != null && !appCheckToken.isEmpty()) {
            b.header("X-Firebase-AppCheck", appCheckToken);
        }
        return http.newCall(b.build()).execute();
    }//DELETE (token'lı) — try-with-resources için Response döner.
    // ----------------------------- CORE EXEC HELPERS -----------------------------
    @NonNull
    public String execToString(@NonNull Request req) throws IOException {
        try (Response resp = http.newCall(req).execute()) {
            String respBody = (resp.body() != null) ? resp.body().string() : "";
            if (!resp.isSuccessful()) {
                // Stacktrace'inde görülen formatla uyumlu bir hata mesajı üret.
                String msg = "CF HTTP " + resp.code();
                if (respBody != null && !respBody.isEmpty()) {
                    msg += " " + respBody;
                }
                throw new IOException(msg);
            }
            return (respBody == null) ? "" : respBody;
        }
    }//--------------------- İsteği çalıştırır, 2xx değilse IOException fırlatır ve hata body'sini mesajın içine gömer.
    @NonNull
    public JSONObject execToJson(@NonNull Request req) throws IOException {
        String s = execToString(req);
        try {
            return (s == null || s.isEmpty()) ? new JSONObject() : new JSONObject(s);
        } catch (JSONException jx) {
            throw new IOException("Invalid JSON: " + jx.getMessage(), jx);
        }
    }//------------------- İsteği çalıştırır ve JSON döndürür; parse edilemezse IOException.
    // ----------------------------- ASYNC (İSTEĞE BAĞLI) ----------------------------
    public interface JsonCallback {
        void onSuccess(@NonNull JSONObject obj);
        void onError(@NonNull Throwable t);
    }
    // ----------------------------- INTERNALS -----------------------------
    private static OkHttpClient buildDefaultClient() {
        return new OkHttpClient.Builder()
                // Ağ hatalarında hızlı toparlanma
                .retryOnConnectionFailure(true)
                // Zaman aşımı ayarları
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                // Bağlantı havuzu
                .connectionPool(new ConnectionPool(5, 5, TimeUnit.MINUTES))
                // HTTP 1.1 tercih (gerekirse)
                .protocols(java.util.Arrays.asList(Protocol.HTTP_1_1))
                // Kimlik ve başlıklar
                .authenticator(new FirebaseAuthenticator())               // 401'lerde token tazele
                .addInterceptor(new FirebaseHeadersInterceptor())          // Authorization, AppCheck, Device-Id vs.
                .build();
    }
    private String resolveUrl(String pathOrUrl) {
        if (pathOrUrl == null) return null;
        if (baseUrl == null) return pathOrUrl;
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) return pathOrUrl;
        if (!pathOrUrl.startsWith("/")) pathOrUrl = "/" + pathOrUrl;
        return baseUrl + pathOrUrl;
    }    // URL çözümleyici: Tam URL ise aynen, değilse baseUrl + path
    private void postOk(@NonNull JsonCallback cb, @NonNull JSONObject obj) {
        main.post(() -> cb.onSuccess(obj));
    }
    private void postErr(@NonNull JsonCallback cb, @NonNull Throwable t) {
        main.post(() -> cb.onError(t));
    }
    private static void logChunked(String tag, String prefix, String text) {
        if (text == null) return;
        for (String line : text.split("\n")) {
            Log.d(tag, prefix + line);
        }
    }
    /**
     * Server: /listSoulsByFields?where=...&limit=...&col=Souls
     * Where string, WhereBuilder ile üretilir.
     */
    public void listSoulsByFields(@NonNull WhereBuilder where, int limit, @NonNull JsonCallback cb) {
        final String TAG = "CFClient";
        getTokens((idTok, appTok) -> io.execute(() -> {
            final long t0 = System.currentTimeMillis();
            try {
                final String whereStr = where.build();
                final String q = whereStr + "&limit=" + limit + "&col=Souls";
                final String url = "/listSoulsByFields?" + q;

                Log.d(TAG, "WB=" + whereStr);
                Log.d(TAG, "→ GET " + url);

                // Geri uyumlu get(...) kullansak bile try-with-resources ile kesin kapatıyoruz.
                try (Response resp = get(url, idTok, appTok)) {
                    Log.d(TAG, "← HTTP " + resp.code() + " " + resp.message()
                            + " (" + (System.currentTimeMillis() - t0) + " ms)");

                    if (!resp.isSuccessful()) {
                        String m = "HTTP " + resp.code() + " " + resp.message();
                        if (resp.body() != null) m += " | " + resp.body().string();
                        throw new IOException(m);
                    }

                    final String body = Objects.requireNonNull(resp.body()).string();

                    // JSON'ı tek kez parse et, pretty bundan türet
                    JSONObject json = new JSONObject(body);
                    String pretty = json.toString(2);

                    // Parçalı log (kesilmeden gör)
                    //logChunked(TAG, "JSON:", pretty);

                    postOk(cb, json);
                }
            } catch (Throwable e) {
                Log.e(TAG, "listSoulsByFields failed", e);
                postErr(cb, e);
            }
        }), e -> {
            Log.e(TAG, "getTokens failed", e);
            postErr(cb, e);
        });
    }
    // ----------------------------- WhereBuilder (opsiyonel) -----------------------------
    // Welcome.java gibi sınıflardaki basit filtreleme kullanımını derletecek minimal sürüm.
    public static class WhereBuilder {
        private final StringBuilder sb = new StringBuilder();
        private static String enc(String s){
            try {
                return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
            } catch (Exception e) { return s; }
        }

        public WhereBuilder eq(String field, String value) { append(field, "eq", value); return this; }
        public WhereBuilder ne(String field, String value) { append(field, "ne", value); return this; }
        public WhereBuilder gt(String field, String value) { append(field, "gt", value); return this; }
        public WhereBuilder gte(String field, String value){ append(field, "gte", value); return this; }
        public WhereBuilder lt(String field, String value) { append(field, "lt", value); return this; }
        public WhereBuilder lte(String field, String value){ append(field, "lte", value); return this; }

        private void append(String f, String op, String v) {
            if (sb.length() > 0) sb.append("&");
            sb.append("where=").append(enc(f)).append(":").append(op).append(":").append(enc(v));
        }
        public String build() { return sb.toString(); }
    }
}