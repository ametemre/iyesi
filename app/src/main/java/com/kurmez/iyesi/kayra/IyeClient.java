// =============================
// File 2: app/src/main/java/com/kurmez/iyesi/kayra/IyeProfileClient.java
// =============================
package com.kurmez.iyesi.kayra;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.RequiresApi;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * IyeProfileClient — dış sınıflarla ilişkiler (FirebaseAuth, AppCheck, Cloud Functions, OkHttp)
 * Activity'den ayrıldı; sadece callback ile sonuç döner.
 */
public class IyeClient {
    private static final String TAG = "IyeProfileClient";

    // Cloud Functions callable isimleri
    private static final String FN_UPDATE_PROFILE = "updateProfile";
    private static final String FN_ECHO_ME        = "echoMe";

    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final FirebaseApp app;
    private final FirebaseFunctions fns;
    private final String region;
    private final OkHttpClient http;

    public IyeClient(FirebaseApp app, String region) {
        this.app = app;
        this.region = region;
        this.fns = FirebaseFunctions.getInstance(app, region);
        this.http = new OkHttpClient();
    }

    // ========== Claims ==========
    public interface ClaimsCallback { void onSuccess(Map<String,Object> claims); void onFailure(String error); }
    public void refreshClaims(FirebaseUser user, ClaimsCallback cb) {
        if (user == null) { cb.onFailure("user=null"); return; }
        user.getIdToken(true)
                .addOnSuccessListener(tr -> {
                    Map<String,Object> claims = tr.getClaims();
                    if (claims == null) claims = new HashMap<>();
                    Map<String, Object> finalClaims = claims;
                    runMain(() -> cb.onSuccess(finalClaims));
                })
                .addOnFailureListener(e -> runMain(() -> cb.onFailure(e.getMessage())));
    }

    // ========== Update ==========
    public interface UpdateCallback { void onSuccess(boolean viaSdk); void onFailure(String error); }

    /**
     * payload beklenen alanlar: { profilDuzenleme: true, json: <String>, alsoWriteToFirestore: boolean }
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    public void updateProfile(Map<String, Object> payload,
                              boolean preferHttp,
                              UpdateCallback cb) {
        AppCheckTokenProvider.whenReady(
                () -> {
                    FirebaseUser uNow = FirebaseAuth.getInstance(app).getCurrentUser();
                    if (uNow == null) { runMain(() -> cb.onFailure("user=null")); return; }

                    uNow.getIdToken(true)
                            .addOnSuccessListener(tr -> {
                                String freshIdToken = tr.getToken();
                                        // 👇 GÖVDEYE idToken EKLE
                                                Map<String, Object> sendPayload = new HashMap<>(payload);
                                sendPayload.put("idToken", freshIdToken);
                                if (preferHttp) {
                                    callCallableOverHttp(FN_UPDATE_PROFILE, sendPayload, freshIdToken,

                                            () -> runMain(() -> cb.onSuccess(false)),
                                            err -> {
                                                if (looksUnauthorized(err)) {
                                                    callCallableWithSdk(FN_UPDATE_PROFILE, sendPayload,
                                                            () -> runMain(() -> cb.onSuccess(true)),
                                                            e -> runMain(() -> cb.onFailure(e)));
                                                } else {
                                                    runMain(() -> cb.onFailure(err));
                                                }
                                            });
                                } else {
                                    callCallableWithSdk(FN_UPDATE_PROFILE, sendPayload,
                                            () -> runMain(() -> cb.onSuccess(true)),
                                            e -> runMain(() -> cb.onFailure(e)));
                                }
                            })
                            .addOnFailureListener(e -> runMain(() -> cb.onFailure("Token alınamadı: " + e.getMessage())));
                },
                e -> runMain(() -> cb.onFailure("AppCheck whenReady FAIL: " + e.getMessage()))
        );
    }

    // ========== Debug ==========
    public void logAuthAndAppCheck() {
        FirebaseUser uNow = FirebaseAuth.getInstance(app).getCurrentUser();
        if (uNow == null) { Log.e("AUTH","user=null"); return; }
        uNow.getIdToken(true).addOnSuccessListener(tr -> {
            String idTok = tr.getToken();
            Log.d("AUTH", "idToken.len=" + (idTok==null?0:idTok.length()));
            logJwtProject(idTok);
            FirebaseAppCheck.getInstance(app)
                    .getAppCheckToken(true)
                    .addOnSuccessListener(ac -> Log.d("APPCHECK","token.len=" + (ac.getToken()==null?0:ac.getToken().length())))
                    .addOnFailureListener(e -> Log.e("APPCHECK","getAppCheckToken FAIL: "+e.getMessage()));
        }).addOnFailureListener(e -> Log.e("AUTH","getIdToken FAIL: "+e.getMessage()));
    }

    private void logJwtProject(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return;
            String payload = new String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE| android.util.Base64.NO_WRAP));
            Log.d("AUTH", "jwt.payload=" + payload);
        } catch (Throwable t) {
            Log.e("AUTH","jwt decode err", t);
        }
    }

    // ========== Callable over HTTP ==========
    private void callCallableOverHttp(String functionName,
                                      Map<String, Object> payload,
                                      String idTokenForAuthHeader,
                                      Runnable onSuccess,
                                      Consumer<String> onFailure) {
        FirebaseAppCheck.getInstance(app).getAppCheckToken(/*forceRefresh=*/true)
                .addOnSuccessListener(ac -> doCallableHttpPost(functionName, payload, idTokenForAuthHeader, ac.getToken(), onSuccess, onFailure))
                .addOnFailureListener(ex -> {
                    Log.e(TAG, "[callCallableOverHttp] getAppCheckToken FAIL: " + ex.getMessage(), ex);
                    onFailure.accept("AppCheck token alınamadı: " + ex.getMessage());
                });
    }

    private String callableUrl(String functionName) {
        String projectId = app.getOptions().getProjectId();
        return "https://" + region + "-" + projectId + ".cloudfunctions.net/" + functionName;
    }

    private void doCallableHttpPost(String functionName,
                                    Map<String, Object> payload,
                                    String idTokenForAuthHeader,
                                    String appCheckToken,
                                    Runnable onSuccess,
                                    Consumer<String> onFailure) {
        try {
            JSONObject root = new JSONObject();
            root.put("data", new JSONObject(payload));

            String url = callableUrl(functionName);
            String gmpid = app.getOptions().getApplicationId(); // 1:...:android:...
            String xFirebaseClient = "fire-android/" + (Build.VERSION.RELEASE == null ? "0" : Build.VERSION.RELEASE);

            RequestBody body = RequestBody.create(root.toString(), JSON_MEDIA);
            Request req = new Request.Builder()
                    .url(url)
                    .post(body)
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .addHeader("Authorization", "Bearer " + (idTokenForAuthHeader == null ? "" : idTokenForAuthHeader))
                    .addHeader("X-Firebase-AppCheck", appCheckToken == null ? "" : appCheckToken)
                    .addHeader("X-Firebase-GMPID", gmpid == null ? "" : gmpid)
                    .addHeader("X-Firebase-Client", xFirebaseClient)
                    .build();

            Log.d(TAG, "[HTTP] POST " + url);
            http.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "[HTTP] onFailure: " + e.getMessage(), e);
                    runMain(() -> onFailure.accept(e.getMessage()));
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    String respStr = response.body() != null ? response.body().string() : null;
                    Log.d(TAG, "[HTTP] code=" + response.code() + " body=" + respStr);
                    if (response.isSuccessful()) {
                        runMain(onSuccess);
                    } else {
                        String msg = "HTTP " + response.code() + (respStr == null ? "" : (" " + respStr));
                        runMain(() -> onFailure.accept(msg));
                    }
                }
            });
        } catch (Exception ex) {
            Log.e(TAG, "[HTTP] exception: " + ex.getMessage(), ex);
            onFailure.accept(ex.getMessage());
        }
    }

    private void callCallableWithSdk(String functionName,
                                     Map<String, Object> payload,
                                     Runnable onSuccess,
                                     Consumer<String> onFailure) {
        fns.getHttpsCallable(functionName)
                .call(payload)
                .addOnSuccessListener(r -> runMain(onSuccess))
                .addOnFailureListener(e -> {
                    String msg = (e instanceof FirebaseFunctionsException)
                            ? "[SDK] " + ((FirebaseFunctionsException) e).getCode() + " " + e.getMessage()
                            : "[SDK] " + e.getMessage();
                    runMain(() -> onFailure.accept(msg));
                });
    }

    private boolean looksUnauthorized(String errMsg) {
        if (errMsg == null) return false;
        String m = errMsg.toLowerCase();
        return m.contains("401") || m.contains("403") || m.contains("unauthorized") || m.contains("permission");
    }

    private static void runMain(Runnable r) { new Handler(Looper.getMainLooper()).post(r); }
}
