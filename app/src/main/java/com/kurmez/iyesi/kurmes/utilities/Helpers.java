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
import com.kurmez.iyesi.kayra.appCheck.TopActivity;
import com.kurmez.iyesi.kurmes.social.Profile;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Ortak yardımcılar + Cloud Functions HTTP yardımcıları */
public class Helpers {
    private static final String L = "Helper";
    private CFHelper cf;
    private String userRole;
    private static final List<String> ALLOWED_ROLES = Arrays.asList(
            "İye", "Körmös", "Körmes", "Ülgen", "Tengri", "Ağaç"
    );
    /* ===================== Mevcut yardımcılar (korundu) ===================== */

    void showToast(String message, Context context) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    public static void showToastSafe(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    /* ======================= ROLE / ACCESS FLOW ======================= */
    public void resolveRoleAndFetch(Context ctx, Activity activity) {
        Log.i(L, "resolveRoleAndFetch() → GİRİŞ");
        cf = new CFHelper(ctx,"iyesi-e8d4f",null);
        new Thread(() -> cf.refreshRole(role -> {
            Log.d(L, "refreshRole() → ÇIKIŞ role=" + role);
            if (role != null) {
                activity.runOnUiThread(() -> handleRole(role,activity,ctx));
            } else {
                Log.w(L, "Role bulunamadı → finish()");
                activity.runOnUiThread(() -> {
                    Helpers.showToastSafe(ctx,"Rol bulunamadı");
                    activity.finish();
                });
            }
        })).start();
    }
    private void handleRole(String role, Activity activity,Context ctx) {
        long t0 = System.currentTimeMillis();
        Log.i(L, "handleRole() → GİRİŞ roleRaw=" + role);

        if (role == null || !isAllowed(role)) {
            Log.w(L, "Erişim reddedildi: " + role);
            Helpers.showToastSafe(ctx,"Bu sayfaya erişim yetkiniz yok: " + role);
            activity.finish();
            return;
        }
        userRole = normalizeRole(role);
        Log.d(L, "role normalized=" + userRole + " → fetchAllFromRTDB()");

        Log.i(L, "handleRole() → ÇIKIŞ (" + (System.currentTimeMillis() - t0) + " ms)");
    }
    private boolean isAllowed(String roleRaw) {
        if (roleRaw == null) return false;
        for (String r : ALLOWED_ROLES) {
            if (r.equalsIgnoreCase(roleRaw)) return true;
        }
        return false;
    }

    public String normalizeRole(String r) {
        if (r == null) return "İye";
        if (r.equalsIgnoreCase("Tengri")) return "Tengri";
        if (r.equalsIgnoreCase("Ülgen") || r.equalsIgnoreCase("Ulgen")) return "Ülgen";
        if (r.equalsIgnoreCase("Körmös") || r.equalsIgnoreCase("Körmes") || r.equalsIgnoreCase("Kormos")) return "Körmös";
        if (r.equalsIgnoreCase("Ağaç") || r.equalsIgnoreCase("Agac")) return "Ağaç";
        return "İye";
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
}
