package com.kurmez.iyesi.kurmes.utilities.helper;

import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.callFunction;
import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.endpointAsync;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.BuildConfig;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableReference;
import com.kurmez.iyesi.kayra.Classes.Harita;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.social.Iyesi;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;
import com.kurmez.iyesi.umay.SokakActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * CFHelper — Cloud Functions istemci yardımcı sınıfı
 *
 * - Her çağrıda ID token YENİLENİR (force refresh).
 * - App Check token'ı (varsa) eklenir.
 * - userRole sınıf içinde tutulur (header’a yalnızca ASCII ise eklenir).
 * - HTTP (onRequest) ve Callable (onCall) uçları desteklenir.
 *
 * NOT: Bu sınıf UI bağımlılığı içermez. Activity/Toast/Context’e özel işler
 *      bu sınıf DIŞINDA (ör. Founded.java) yapılmalıdır.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class CFHelper {
    public interface Listener<T> {
        default void onRoleRefreshed(@Nullable String role) {}
        default void onCallFailed(@NonNull String apiName, @NonNull Throwable error) {}
        default void onPriorityPets(@NonNull List<T> pets) {}
    }//------------------------------------------------------------ Listener (UI geri bildirimleri için) — JENERİK

    private static final String TAG = "CFHelper";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Context appContext;
    private final OkHttpClient http;
    private final FirebaseAuth auth;
    private final FirebaseAppCheck appCheck;
    private final FirebaseFunctions functions;
    private final Handler main = new Handler(Looper.getMainLooper());

    public static String baseHttpUrl; // örn: https://us-central1-<PROJECT_ID>.cloudfunctions.net
    private final String region;

    private volatile @Nullable String userRole;//--------------------------------------------------- İstemci tarafında saklanan rol bilgisi (sunucudan getRole ile çekilir).
    private volatile @Nullable String deviceId;//--------------------------------------------------- Opsiyonel cihaz kimliği — header olarak iletilir.
    private final @Nullable Listener<Soul> listener;//---------------------------------------------- Uygulamaya dönecek callback — bu sınıfta Soul için tipledik
    public @Nullable String urlStr;//--------------------------------------------------------------- Son çağrının (GET) URL’ini debug için tutmak istersen

    // ------------------------------------------------------------
    // Yapıcılar
    // ------------------------------------------------------------
    public CFHelper(@NonNull Context ctx, @NonNull String projectId, @NonNull String region, @Nullable Listener<Soul> listener) {
        this.appContext = ctx.getApplicationContext();
        this.listener = listener;
        this.auth = FirebaseAuth.getInstance();
        this.appCheck = FirebaseAppCheck.getInstance();
        this.functions = FirebaseFunctions.getInstance(region); // callable doğru bölge
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.region = region;
        this.baseHttpUrl = "https://" + region + "-" + projectId + ".cloudfunctions.net";
    }
    public CFHelper(@NonNull Context ctx, @NonNull String projectId, @Nullable Listener<Soul> listener) {
        this(ctx, projectId, "us-central1", listener);
    }// eski ctor’u geriye dönük koru (region=us-central1)
    public interface RoleCallback {
        void onRoleFetched(@Nullable String role);
    }
    private @Nullable String extractRoleFromJson(@Nullable JSONObject src) {
        if (src == null) return null;

        // 1) Düz alanlar
        String role = src.optString("role", null);
        if (role != null && !role.trim().isEmpty()) return role.trim();

        // 2) roles[]
        JSONArray rolesArr = src.optJSONArray("roles");
        if (rolesArr != null && rolesArr.length() > 0) {
            String v = rolesArr.optString(0, null);
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }

        // 3) customAttributes (stringleşmiş JSON ya da obje)
        Object ca = src.opt("customAttributes");
        if (ca instanceof String) {
            try {
                JSONObject caObj = new JSONObject((String) ca);
                String v = extractRoleFromJson(caObj);
                if (v != null) return v;
            } catch (Exception ignore) {}
        } else if (ca instanceof JSONObject) {
            String v = extractRoleFromJson((JSONObject) ca);
            if (v != null) return v;
        }

        // 4) claims objesi
        JSONObject claims = src.optJSONObject("claims");
        if (claims != null) {
            String v = extractRoleFromJson(claims);
            if (v != null) return v;
        }

        // 5) user objesi içinde olabilir
        JSONObject user = src.optJSONObject("user");
        if (user != null) {
            String v = extractRoleFromJson(user);
            if (v != null) return v;
        }

        return null;
    }

    public interface UsersCallback {
        void onSuccess(@NonNull List<Iyesi> users);
        void onError(@NonNull Throwable error);
    }    // ----------------------------------------------------- Messaging / Users ----------

    /** /listAllUsersHttp (GET) → List<Profile> */
    public void listAllUsers(@Nullable Integer limit, @Nullable String pageToken, @NonNull UsersCallback cb) {
        Map<String, String> q = new HashMap<>();
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (pageToken != null) q.put("pageToken", pageToken);

        endpointAsync("/listAllUsersHttp", q, null, /*post=*/false, new JsonHelper.EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                try {
                    List<Iyesi> list = parseUsers(resp); // BG
                    main.post(() -> cb.onSuccess(list));   // UI
                } catch (Throwable e) {
                    main.post(() -> cb.onError(e));
                }
            }
            @Override public void onError(Throwable error) { main.post(() -> cb.onError(error)); }
        });
    }
    public void refreshRole(@NonNull CFHelper.RoleCallback callback) {
        new Thread(() -> {
            try {
                JSONObject r = null;
                try {
                    r = JsonHelper.doGetJson("/getRole", null); // bazı projelerde çalışır
                } catch (Throwable getErr) {
                    // GET 405 vs. durumunda POST fallback
                    try {
                        r = JsonHelper.doPostJson("/getRole", new JSONObject());
                    } catch (Throwable postErr) {
                        throw postErr; // ikisi de patlarsa dış yakalama çalışır
                    }
                }

                // Gelen JSON’u tek noktadan çöz
                String role = extractRoleFromJson(r);

                // Unicode normalize + trim
                if (role != null) {
                    role = role.trim();
                    if (role.isEmpty()) role = null;
                }

                this.userRole = role;

                if (listener != null) main.post(() -> listener.onRoleRefreshed(this.userRole));
                final String outRole = this.userRole;
                main.post(() -> callback.onRoleFetched(outRole));
            } catch (Throwable e) {
                if (listener != null) main.post(() -> listener.onCallFailed("getRole", e));
                Log.e(TAG, "refreshRole failed", e);
                main.post(() -> callback.onRoleFetched(null));
            }
        }).start();
    }
    private ArrayList<Iyesi> parseUsers(@NonNull JSONObject root) throws Exception {
        ArrayList<Iyesi> out = new ArrayList<>();
        boolean success = root.optBoolean("success", true);
        boolean ok      = root.optBoolean("ok", true);

        JSONArray arr =
                root.optJSONArray("users") != null ? root.optJSONArray("users") :
                        root.optJSONArray("list")  != null ? root.optJSONArray("list")  :
                                root.optJSONArray("items") != null ? root.optJSONArray("items") :
                                        (root.has("data") && root.opt("data") instanceof JSONArray) ? root.optJSONArray("data") :
                                                null;

        // {users:{uid:{...}}} varyantı
        if (arr == null && root.opt("users") instanceof JSONObject) {
            JSONObject usersObj = root.getJSONObject("users");
            arr = new JSONArray();
            Iterator<String> it = usersObj.keys();
            while (it.hasNext()) arr.put(usersObj.getJSONObject(it.next()));
        }

        if (arr == null && !(success || ok)) {
            String msg = root.optString("message", root.optString("error", "Server error"));
            throw new IllegalStateException(msg);
        }
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;

            String uid       = it.optString("uid", it.optString("id", ""));
            String username  = it.optString("username", it.optString("displayName", ""));
            String email     = it.optString("email", "");
            String location  = it.optString("location", "");
            String phone     = it.optString("phone", "");
            String role      = it.optString("role", it.optString("userRole", ""));
            String avatarUrl = it.optString("avatarUrl",
                    it.optString("photoUrl",
                            it.optString("photoURL", "")));

            out.add(new Iyesi(uid, username, email, location, phone, role, avatarUrl));
        }
        return out;
    }

    // ---------- Sahiplendirme / Priority Pets ----------
    /**  */
    public void fetchPriorityPets() {
        endpointAsync("/getPriorityPets", null, null, /*post=*/false, new JsonHelper.EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                try {
                    List<Soul> list = parsePriorityPets(resp); // BG
                    if (listener != null) main.post(() -> listener.onPriorityPets(list)); // UI
                } catch (Throwable parseErr) {
                    if (listener != null) main.post(() -> listener.onCallFailed("getPriorityPets.parse", parseErr));
                }
            }
            @Override public void onError(Throwable error) {
                if (listener != null) main.post(() -> listener.onCallFailed("getPriorityPets", error));
            }
        });
    }// -------------------------------------------------------- GET /getPriorityPets ve sonucu Listener’a List<Soul> olarak aktarır.
    private ArrayList<Soul> parsePriorityPets(@NonNull JSONObject root) throws Exception {
        ArrayList<Soul> out = new ArrayList<>();
        if (!root.optBoolean("success", false)) {
            throw new IllegalStateException(root.optString("message", "Server error"));
        }
        JSONArray arr = root.optJSONArray("pets");
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);

            String name          = item.optString("name");
            String species       = item.optString("species");
            String breed         = item.optString("breed");
            String health        = item.optString("health");
            String foundDate     = item.optString("foundDate");
            String foundLocation = item.optString("foundLocation");
            String imageResId    = item.optString("imageUrl"); // yeni alan adı
            String finderName    = "";                         // artık gelmiyor
            long   timestamp     = item.optLong("timestamp", System.currentTimeMillis());

            Soul pc = new Soul(
                    /*name=*/name,
                    /*species=*/species,
                    /*breed=*/breed,
                    /*age=*/"",
                    /*health=*/health,
                    /*foundDate=*/foundDate,
                    /*foundLocation=*/foundLocation,
                    /*veterinary=*/"",
                    /*imageResId=*/imageResId,
                    /*finderName=*/finderName,
                    /*timestamp=*/timestamp
            );
            out.add(pc);
        }
        return out;
    }
    public void submitSoulInNeed(@NonNull JSONObject payload, @NonNull JsonHelper.EndpointCallback cb) {
        endpointAsync("/submitSoulInNeed", null, payload, /*post=*/true, new JsonHelper.EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) { main.post(() -> cb.onSuccess(resp)); }

            @Override public void onError(Throwable error) {
                if (error instanceof HttpException) {
                    int code = ((HttpException) error).code;
                    if (code == 404) {
                        // Bölge/route uyuşmazlığı: callable fallback
                        try {
                            JSONObject r = callFunction("submitSoulInNeed", payload);
                            main.post(() -> cb.onSuccess(r));
                        } catch (Throwable callErr) {
                            main.post(() -> cb.onError(callErr));
                        }
                        return;
                    }
                }
                main.post(() -> cb.onError(error));
            }
        });
    }// POST /submitSoulInNeed — 404’te callable fallback dener.
    public interface PendingCallback {
        /** companion = null → pending yok demektir. */
        void onResult(@Nullable JSONObject companion);
        void onError(@NonNull Throwable error);
    }    // --------------------------------------------------- Pending companion (deviceId ile) ----------
    public void checkPendingCompanion(@NonNull String deviceId, @NonNull PendingCallback cb) {
        Map<String, String> q = new HashMap<>();
        q.put("deviceId", deviceId);

        endpointAsync("/checkPendingCompanion", q, null, /*post=*/false, new JsonHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                try {
                    boolean has = resp.optBoolean("has",
                            resp.optBoolean("hasPending", resp.has("companion")));

                    JSONObject comp = resp.optJSONObject("companion");
                    if (comp == null && (has || resp.length() > 0)) {
                        comp = resp;
                        has = true;
                    }
                    final JSONObject result = (has ? comp : null);
                    main.post(() -> cb.onResult(result));
                } catch (Throwable parseErr) {
                    main.post(() -> cb.onError(parseErr));
                }
            }
            @Override
            public void onError(Throwable error) {
                // Sadece logla ve çağırana ilet (UI/Activity tarafında ele alınacak)
                Log.w(TAG, "checkPendingCompanion error: " + (error == null ? "-" : error.getMessage()));
                main.post(() -> cb.onError(error));
            }
        });
    }
    public JSONObject listPendingCompanions(double lat, double lng, int radiusM, int limit) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("lat", String.valueOf(lat));
        q.put("lng", String.valueOf(lng));
        q.put("radiusM", String.valueOf(radiusM));
        q.put("limit", String.valueOf(limit));
        return JsonHelper.doGetJson("/listPendingCompanions", q);
    }

    // ------------------------------------------------------------
    // Getter/Setter
    // ------------------------------------------------------------
    public @Nullable String getUserRole() { return userRole; }
    public void setUserRole(@Nullable String role) { this.userRole = role; }
    public @NonNull  String getRegion() { return region; }
    public @NonNull  String getBaseHttpUrl() { return baseHttpUrl; }
    // ------------------------------------------------------------
    // Hatalar
    // ------------------------------------------------------------
    /** HTTP hatası için kendi exception’ımız; android.net.http.HttpException KULLANMIYORUZ. */
    public static class HttpException extends IOException {
        public final int code;
        public final String body;
        public HttpException(int code, String body) {
            super("HTTP " + code + " — " + body);
            this.code = code; this.body = body;
        }
    }
}
