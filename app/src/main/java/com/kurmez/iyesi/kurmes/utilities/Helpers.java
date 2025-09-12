package com.kurmez.iyesi.kurmes.utilities;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.AppCheckTokenProvider;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.TopActivity;
import com.kurmez.iyesi.kurmes.social.Profile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Ortak yardımcılar + Cloud Functions HTTP yardımcıları */
public class Helpers {

    /* ===================== Mevcut yardımcılar (korundu) ===================== */

    void showToast(String message, Context context) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void showToastSafe(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    /** Sunucudan rol bilgisini çeker. (Örnek mevcut fonksiyon) */
    public static Task<String> getRoleFunction() {
        TaskCompletionSource<String> taskSource = new TaskCompletionSource<>();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            taskSource.setException(new Exception("Kullanıcı oturum açmamış!"));
            return taskSource.getTask();
        }
        user.getIdToken(true).addOnSuccessListener(getTokenResult -> {
            String idToken = getTokenResult.getToken();
            OkHttpClient client = new OkHttpClient();
            Request request = new Request.Builder()
                    .url("https://us-central1-iyesi-e8d4f.cloudfunctions.net/getRole")
                    .addHeader("Authorization", "Bearer " + idToken)
                    .post(RequestBody.create("{\"data\":{}}",
                            MediaType.parse("application/json")))
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    taskSource.setException(e);
                }
                @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        taskSource.setException(new IOException(
                                "HTTP " + response.code() + " - " + responseBody));
                        return;
                    }
                    try {
                        JSONObject json = new JSONObject(responseBody);
                        taskSource.setResult(json.optString("role", null));
                    } catch (JSONException e) {
                        taskSource.setException(e);
                    }
                }
            });
        }).addOnFailureListener(taskSource::setException);
        return taskSource.getTask();
    }

    /** JSON -> Profile list (örnek mevcut) */
    public static List<Profile> parseProfiles(String jsonBody) throws JSONException {
        JSONObject root = new JSONObject(jsonBody);
        JSONArray users = root.optJSONArray("users");
        List<Profile> list = new ArrayList<>();
        if (users == null) return list;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.getJSONObject(i);
            String uid        = u.optString("uid", "");
            String email      = u.optString("email", "");
            String dispName   = u.optString("displayName", "").trim();
            String username   = !dispName.isEmpty() ? dispName : email;
            String location   = u.optString("location", "");
            String phone      = u.optString("phone", "");
            String role       = u.optString("role", "");
            String avatar_url = u.optString("avatar","");
            list.add(new Profile(uid, username, email, location, phone, role, avatar_url));
        }
        return list;
    }

    /** Header menü yardımcıları */
    public static class ConversationHeaderHelper { // ← static yapıldı
        public static void setupHeader(AppCompatActivity activity,
                                       int menuResId,
                                       PopupMenu.OnMenuItemClickListener listener) {
            ImageButton btnMore = activity.findViewById(R.id.btnMore);
            if (btnMore == null) return;
            btnMore.setOnClickListener(view -> {
                PopupMenu popup = new PopupMenu(activity, view);
                popup.getMenuInflater().inflate(menuResId, popup.getMenu());
                popup.setOnMenuItemClickListener(listener);
                popup.show();
            });
        }
    }

    /* ===================== Yeni: Auth/AppCheck Token Yardımcıları ===================== */

    /** Oturum açmış kullanıcının ID token'ını döner. */
    public static Task<String> getIdTokenOnce() {
        TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            tcs.setException(new Exception("No logged-in user"));
            return tcs.getTask();
        }
        user.getIdToken(true)
                .addOnSuccessListener(r -> tcs.setResult(r.getToken()))
                .addOnFailureListener(tcs::setException);
        return tcs.getTask();
    }

    /** App Check token (varsa) döner; başarısızsa boş string verir. (SDK: AppCheckToken) */
    public static Task<String> getAppCheckTokenSoft() {
        TaskCompletionSource<String> tcs = new TaskCompletionSource<>();
        try {
            FirebaseAppCheck.getInstance()
                    .getAppCheckToken(true)
                    .addOnSuccessListener(new OnSuccessListener<AppCheckToken>() {
                        @Override public void onSuccess(AppCheckToken token) {
                            tcs.setResult(token != null ? token.getToken() : "");
                            Log.v("AppCheck Loaded...",tcs.toString());
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override public void onFailure(@NonNull Exception e) {
                            tcs.setResult(""); // AppCheck kapalı/başarısız → boş
                            Log.e("AppCheckError",e.getMessage());
                        }
                    });
        } catch (Throwable t) {
            tcs.setResult("");
            Log.e("AppCheckError",t.getMessage());
        }
        return tcs.getTask();
    }

    /* ===================== Yeni: Yetkili HTTP Yardımcıları ===================== */

    /** Authorization + (ops.) AppCheck + DeviceId başlıklarıyla JSON POST */
    public static void authorizedPostJson(
            Context ctx,
            String fullUrl,
            JSONObject body,
            @Nullable String deviceId,
            boolean includeAppCheck,
            Callback callback
    ) {
        Task<String> tId = getIdTokenOnce();
        Task<String> tApp = includeAppCheck ? getAppCheckTokenSoft() : Tasks.forResult("");
        Tasks.whenAllSuccess(tId, tApp).addOnSuccessListener(list -> {
            String idToken = (String) list.get(0);
            String appCheck = includeAppCheck ? (String) list.get(1) : "";

            MediaType MT_JSON = MediaType.parse("application/json; charset=utf-8");
            RequestBody reqBody = RequestBody.create(body.toString(), MT_JSON);

            Request.Builder rb = new Request.Builder()
                    .url(fullUrl)
                    .post(reqBody)
                    .addHeader("Authorization", "Bearer " + idToken);

            if (includeAppCheck && appCheck != null && !appCheck.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appCheck);
            }
            if (deviceId != null && !deviceId.isEmpty()) {
                rb.addHeader("X-Device-Id", deviceId);
            }
            new OkHttpClient().newCall(rb.build()).enqueue(callback);
        }).addOnFailureListener(e -> {
            if (callback != null) {
                callback.onFailure(null, new IOException(e.getMessage(), e));
            } else {
                showToastSafe(ctx, "Auth/AppCheck token alınamadı: " + e.getMessage());
            }
        });
    }

    /** Authorization + (ops.) AppCheck + DeviceId başlıklarıyla GET */
    public static void authorizedGetJson(
            Context ctx,
            String fullUrl,
            @Nullable String deviceId,
            boolean includeAppCheck,
            Callback callback
    ) {
        Task<String> tId = (FirebaseAuth.getInstance().getCurrentUser() != null)
                ? getIdTokenOnce() : Tasks.forResult("");  // misafir ise boş bırak
        Task<String> tApp = includeAppCheck ? getAppCheckTokenSoft() : Tasks.forResult("");
        Tasks.whenAllSuccess(tId, tApp).addOnSuccessListener(list -> {
            String idToken = (String) list.get(0);
            String appCheck = includeAppCheck ? (String) list.get(1) : "";

            Request.Builder rb = new Request.Builder().url(fullUrl).get();
            if (idToken != null && !idToken.isEmpty()) {
                rb.addHeader("Authorization", "Bearer " + idToken);
            }
            if (includeAppCheck && appCheck != null && !appCheck.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appCheck);
            }
            if (deviceId != null && !deviceId.isEmpty()) {
                rb.addHeader("X-Device-Id", deviceId);
            }
            new OkHttpClient().newCall(rb.build()).enqueue(callback);
        }).addOnFailureListener(e -> {
            if (callback != null) {
                callback.onFailure(null, new IOException(e.getMessage(), e));
            } else {
                showToastSafe(ctx, "Auth/AppCheck token alınamadı: " + e.getMessage());
            }
        });
    }
    @Nullable
    private static String toAsciiRole(@NonNull String s) {
        String mapped = s
                .replace('Ç','C').replace('ç','c')
                .replace('Ğ','G').replace('ğ','g')
                .replace('İ','I').replace('ı','i')
                .replace('Ö','O').replace('ö','o')
                .replace('Ş','S').replace('ş','s')
                .replace('Ü','U').replace('ü','u');
        for (int i = 0; i < mapped.length(); i++) {
            char c = mapped.charAt(i);
            if (c < 0x20 || c > 0x7E) return null; // ASCII dışı karakter varsa header eklemeyelim
        }
        return mapped;
    }


    /* ===================== Yeni: Marker Uçları için sarmalayıcı ===================== */

    /**
     * markerCreate uçuna uygun gövdeyi hazırlar ve gönderir.
     * baseUrl ör.: "https://us-central1-iyesi-e8d4f.cloudfunctions.net"
     */
    public static void createMarkerOnCloud(
            Context ctx,
            String baseUrl,
            double lat,
            double lng,
            String type,
            @Nullable String species,
            @Nullable String category,
            @Nullable String note,
            @Nullable String deviceId,
            Callback callback
    ) {
        try {
            JSONObject body = new JSONObject();
            body.put("type", type);
            body.put("lat", lat);
            body.put("lng", lng);
            if (species != null)  body.put("species", species);
            if (category != null) body.put("category", category);
            if (note != null)     body.put("note", note);

            String url = baseUrl + "/markerCreate";
            authorizedPostJson(ctx, url, body, deviceId, /*includeAppCheck=*/true, callback);
        } catch (JSONException e) {
            if (callback != null) {
                callback.onFailure(null, new IOException("JSON build failed", e));
            } else {
                showToastSafe(ctx, "İstek hazırlanamadı: " + e.getMessage());
            }
        }
    }
    public final class UIHelper {

        // Güvenli Context seçimi: Activity > Application
        private static Context pickUiContext(@Nullable Context ctx) {
            if (ctx instanceof Activity) return ctx;
            Activity top = TopActivity.activity(); // bizim lifecycle tracker
            if (top != null && !top.isFinishing()) return top;
            return AppCheckTokenProvider.app(); // son çare (Toast gibi işler için ok)
        }

        // Örnek: Toast
        public static void toast(@Nullable Context ctx, String msg) {
            Context c = pickUiContext(ctx);
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show());
        }

        // Örnek: Dialog (Activity şart)
        public static void confirm(@Nullable Activity act,
                                   String title, String msg,
                                   Runnable onOk) {
            Activity a = (act != null) ? act : TopActivity.activity();
            if (a == null || a.isFinishing()) return; // Activity yoksa dialog açma

            a.runOnUiThread(() ->
                    new androidx.appcompat.app.AlertDialog.Builder(a)
                            .setTitle(title).setMessage(msg)
                            .setPositiveButton("OK", (d, w) -> { if (onOk != null) onOk.run(); })
                            .setNegativeButton("İptal", null)
                            .show()
            );
        }

        // Örnek: Activity başlatma
        public static void openSettings(@Nullable Activity act) {
            Activity a = (act != null) ? act : TopActivity.activity();
            if (a == null || a.isFinishing()) return;
            a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_SETTINGS));
        }
    }

}
