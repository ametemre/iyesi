package com.kurmez.iyesi.kurmes.utilities.helper;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableReference;
import com.kurmez.iyesi.kurmes.social.Profile;
import com.kurmez.iyesi.kurmes.social.content.ExplorePrivate;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.umay.sahiplendirme.Soul;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;


/**
 * CFHelper — Cloud Functions istemci yardımcı sınıfı
 *
 * - Her çağrıda ID token YENİLENİR (force refresh).
 * - App Check token'ı (varsa) eklenir.
 * - userRole sınıf içinde tutulur (header’a yalnızca ASCII ise eklenir).
 * - HTTP (onRequest) ve Callable (onCall) uçları desteklenir.
 */
public class CFHelper {

    // ------------------------------------------------------------
    // Listener (UI geri bildirimleri için)
    // ------------------------------------------------------------
    public interface Listener {
        default void onRoleRefreshed(@Nullable String role) {}
        default void onCallFailed(@NonNull String apiName, @NonNull Throwable error) {}
        default void onPriorityPets(@NonNull java.util.List<Soul> pets) {}
    }

    // ------------------------------------------------------------
    // Alanlar
    // ------------------------------------------------------------
    private static final String TAG = "CFHelper";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    // alanlara ekleyin
    private Spinner dbPathSpinner;
    private ArrayAdapter<String> pathAdapter;
    private final List<String> pathItems = new ArrayList<>();
    private final Deque<String> pathStack = new ArrayDeque<>();
    private DatabaseReference browseRef; // gezginin o anki referansı

    private final Context appContext;
    private final OkHttpClient http;
    private final FirebaseAuth auth;
    private final FirebaseAppCheck appCheck;
    private final FirebaseFunctions functions;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** https://us-central1-<PROJECT_ID>.cloudfunctions.net */
// 1) Alanı değiştir
//- private final String baseHttpUrl;
private String baseHttpUrl;
private final String region;



    /** İstemci tarafında saklanan rol bilgisi (sunucudan getRole ile çekilir). */
    private volatile @Nullable String userRole;

    private final @Nullable Listener listener;

// 2) Yeni ctor
public CFHelper(@NonNull Context context, @NonNull String projectId, @NonNull String region, @Nullable Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.auth = FirebaseAuth.getInstance();
        this.appCheck = FirebaseAppCheck.getInstance();
        this.functions = FirebaseFunctions.getInstance(region); // callable için doğru bölge
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
        this.region = region;
        this.baseHttpUrl = "https://" + region + "-" + projectId + ".cloudfunctions.net";
}

    // 3) Geriye dönük uyumlu eski ctor
    public CFHelper(@NonNull Context context,
                    @NonNull String projectId,
                    @Nullable Listener listener) {
        this(context, projectId, "us-central1", listener);
    }
    // (opsiyonel) Manuel override
    public void overrideBaseHttpUrl(@NonNull String absoluteBase) {
    this.baseHttpUrl = absoluteBase;
}

    // ------------------------------------------------------------
    // Kimlik / Token
    // ------------------------------------------------------------
    private static class Tokens {
        final String idToken;
        @Nullable final String appCheckToken;
        Tokens(String idToken, @Nullable String appCheckToken) {
            this.idToken = idToken; this.appCheckToken = appCheckToken;
        }
    }

    /** Her çağrıda taze ID token ve (varsa) App Check token al. */
    private Tokens refreshTokensBlocking() throws Exception {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) throw new IllegalStateException("Not authenticated");

        // Force refresh for every call (isteğe göre)
        String idTok = Tasks.await(user.getIdToken(true)).getToken();
        if (idTok == null || idTok.isEmpty()) throw new IllegalStateException("Empty ID token");

        String appCheckTok = null;
        try {
            AppCheckToken t = Tasks.await(appCheck.getAppCheckToken(false));
            if (t != null && t.getToken() != null && !t.getToken().isEmpty()) {
                appCheckTok = t.getToken();
            }
        } catch (Exception ignore) {
            // App Check zorunlu değilse sessiz geç
        }
        return new Tokens(idTok, appCheckTok);
    }

    private Headers buildAuthHeaders(@NonNull Tokens t) {
        Headers.Builder hb = new Headers.Builder()
                // Sunucu çoğunlukla Authorization: Bearer <ID_TOKEN> bekler
                .add("Authorization", "Bearer " + t.idToken)
                // Bazı yardımcılar X-Firebase-Authorization da kabul ediyor
                .add("X-Firebase-Authorization", "Bearer " + t.idToken);

        if (t.appCheckToken != null) {
            hb.add("X-Firebase-AppCheck", t.appCheckToken);
        }

        // İstemci tarafı gözlem için rol header’ı (ASCII zorunluluğu!)
        if (userRole != null && !userRole.isEmpty()) {
            String asciiRole = toAsciiRole(userRole);
            if (asciiRole != null) {
                hb.add("X-User-Role", asciiRole);
            }
        }
        return hb.build();
    }
    // Founded.java ile uyumlu:
    public interface PendingCallback {
        /** companion = null → pending yok demektir. */
        void onResult(@Nullable JSONObject companion);
        void onError(@NonNull Throwable error);
    }

    // ------------------------------------------------------------
    // HTTP yardımcıları
    // ------------------------------------------------------------
    private JSONObject doGetJson(String path, @Nullable Map<String, String> query) throws Exception {
        Tokens t = refreshTokensBlocking();
        StringBuilder url = new StringBuilder(baseHttpUrl).append(path);
        if (query != null && !query.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append("&");
                first = false;
                url.append(e.getKey()).append("=").append(Util.urlEncode(e.getValue()));
            }
        }
        Request req = new Request.Builder()
                .url(url.toString())
                .headers(buildAuthHeaders(t))
                .get()
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new HttpException(resp.code(), body);
            return toJson(body);
        }
    }

    private JSONObject doPostJson(String path, @Nullable JSONObject json) throws Exception {
        Tokens t = refreshTokensBlocking();
        String payload = (json == null ? "{}" : json.toString());
        // OkHttp3 imzası: RequestBody.create(MediaType, String)
        RequestBody body = RequestBody.create(JSON, payload);

        Request req = new Request.Builder()
                .url(baseHttpUrl + path)
                .headers(buildAuthHeaders(t))
                .post(body)
                .build();

        try (Response resp = http.newCall(req).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new HttpException(resp.code(), respBody);
            return toJson(respBody);
        }
    }

    // ------------------------------------------------------------
    // Callable yardımcıları
    // ------------------------------------------------------------
    private JSONObject callFunction(String name, @Nullable JSONObject data) throws Exception {
        // Callable tarafında SDK token’ı taşır; ama biz yine de force refresh yapıyoruz
        refreshTokensBlocking();
        HttpsCallableReference ref = functions.getHttpsCallable(name);
        Map<String, Object> map = (data == null) ? Collections.emptyMap() : Util.jsonToMap(data);
        Object result = Tasks.await(ref.call(map)).getData();
        return toJsonFromObject(result);
    }
    // ------------------------------------------------------------
    // Genel endpoint yürütücüsü (async)
    // ------------------------------------------------------------
    public interface EndpointCallback {
        void onSuccess(JSONObject resp);
        void onError(Throwable error);
    }

    private void endpointAsync(
            @NonNull String path,
            @Nullable Map<String,String> query,
            @Nullable JSONObject body,
            boolean post,
            @NonNull EndpointCallback cb
    ) {
        new Thread(() -> {
            try {
                JSONObject resp = post ? doPostJson(path, body) : doGetJson(path, query);
                cb.onSuccess(resp);
            } catch (Throwable t) {
                cb.onError(t);
            }
        }).start();
    }

    // ------------------------------------------------------------
    // Yüksek seviye API’ler
    // ------------------------------------------------------------

    /** Sunucudan rolü çek ve sınıf değişkenine yaz. */
    public @Nullable String refreshRole() {
        try {
            JSONObject r = doPostJson("/getRole", new JSONObject());
            String role = r.optString("role", null);
            this.userRole = (role != null && role.isEmpty()) ? null : role;
            if (listener != null) listener.onRoleRefreshed(this.userRole);
            return this.userRole;
        } catch (Throwable e) {
            if (listener != null) listener.onCallFailed("getRole", e);
            Log.e(TAG, "refreshRole failed", e);
            return null;
        }
    }

    // ---------- Messaging / Users ----------

    public interface UsersCallback {
        void onSuccess(java.util.List<Profile> users);
        void onError(Throwable error);
    }

    /** /listAllUsersHttp (POST) → List<Profile> */
    public void listAllUsers(@Nullable Integer limit,
                             @Nullable String pageToken,
                             @NonNull UsersCallback cb) {
        JSONObject body = new JSONObject();
        try {
            if (limit != null) body.put("limit", limit);
            if (pageToken != null) body.put("pageToken", pageToken);
        } catch (JSONException ignore) {}

        endpointAsync("/listAllUsersHttp", null, body, /*post=*/true, new EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                try {
                    java.util.List<Profile> list = parseUsers(resp); // parse BACKGROUND'da
                    main.post(() -> cb.onSuccess(list));             // callback UI'da
                } catch (Throwable e) {
                    main.post(() -> cb.onError(e));
                }
            }
            @Override public void onError(Throwable error) { main.post(() -> cb.onError(error)); }
        });
    }

    private ArrayList<Profile> parseUsers(@NonNull JSONObject root) throws Exception {
        ArrayList<Profile> out = new ArrayList<>();
        // Toleranslı okuma: success/ok bayrakları opsiyonel olabilir
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
            // Avatar: doğrudan avatarUrl varsa onu kullan; yoksa photoUrl/photoURL
            String avatarUrl = it.optString("avatarUrl",
                    it.optString("photoUrl",
                            it.optString("photoURL", "")));

            out.add(new Profile(uid, username, email, location, phone, role, avatarUrl));
        }
        return out;
    }

    // ---------- Sahiplendirme / Priority Pets ----------

    /** GET /getPriorityPets ve sonucu Listener’a List<Soul> olarak aktarır. */
    public void fetchPriorityPets() {
        endpointAsync("/getPriorityPets", null, null, /*post=*/false, new EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                try {
                    java.util.List<Soul> list = parsePriorityPets(resp); // BACKGROUND
                    if (listener != null) main.post(() -> listener.onPriorityPets(list)); // UI
                } catch (Throwable parseErr) {
                    if (listener != null) main.post(() -> listener.onCallFailed("getPriorityPets.parse", parseErr));
                }
            }
            @Override public void onError(Throwable error) {
                if (listener != null) main.post(() -> listener.onCallFailed("getPriorityPets", error));
            }
        });
    }
    public void submitSoulInNeed(@NonNull JSONObject payload,
                                 @NonNull EndpointCallback cb) {
        endpointAsync("/submitSoulInNeed",
                /*query=*/null,
                /*body=*/payload,
                /*post=*/true,
                new EndpointCallback() {
                    @Override public void onSuccess(JSONObject resp) { main.post(() -> cb.onSuccess(resp)); }
                    @Override public void onError(Throwable error) { main.post(() -> cb.onError(error)); }
                });
    }


    /** İstersen ham JSON’a da erişmek için */
    public JSONObject getPriorityPets() throws Exception {
        return doGetJson("/getPriorityPets", null);
    }

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

    // ---------- Marker uçları (örnek sarmalayıcılar) ----------
    public JSONObject markersNearby(double lat, double lng, int radiusM, int limit,
                                    @Nullable String type, @Nullable String cityKey) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("lat", String.valueOf(lat));
        q.put("lng", String.valueOf(lng));
        q.put("radiusM", String.valueOf(radiusM));
        q.put("limit", String.valueOf(limit));
        if (type != null) q.put("type", type);
        if (cityKey != null) q.put("cityKey", cityKey);
        return doGetJson("/markersNearby", q);
    }

    public JSONObject markerCreate(@NonNull JSONObject body) throws Exception {
        return doPostJson("/markerCreate", body);
    }

    public JSONObject listPendingCompanions(double lat, double lng, int radiusM, int limit) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("lat", String.valueOf(lat));
        q.put("lng", String.valueOf(lng));
        q.put("radiusM", String.valueOf(radiusM));
        q.put("limit", String.valueOf(limit));
        return doGetJson("/listPendingCompanions", q);
    }

    public JSONObject markerDetails(@NonNull String id) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("id", id);
        return doGetJson("/markerDetails", q);
    }

    public JSONObject markerSouls(@NonNull String id, int limit) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("id", id);
        q.put("limit", String.valueOf(limit));
        return doGetJson("/markerSouls", q);
    }

    public JSONObject markerInteract(@NonNull JSONObject body) throws Exception {
        return doPostJson("/markerInteract", body);
    }

    // ---------- Mesajlaşma (HTTP) ----------
    public JSONObject appSend(@NonNull JSONObject body) throws Exception {
        return doPostJson("/appSend", body);
    }

    public JSONObject appGet(@NonNull String withUid, int limit, @Nullable String beforeMsgId) throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("withUid", withUid);
        q.put("limit", String.valueOf(limit));
        if (beforeMsgId != null) q.put("before", beforeMsgId);
        return doGetJson("/appGet", q);
    }

    // ---------- Callable örnekleri ----------
    public JSONObject callCreateUser(@NonNull JSONObject data) throws Exception {
        return callFunction("createUser", data);
    }

    public JSONObject callAssignRole(@NonNull JSONObject data) throws Exception {
        return callFunction("assignRole", data);
    }

    public JSONObject callUpdateClaims(@NonNull JSONObject data) throws Exception {
        return callFunction("updateClaims", data);
    }

    public JSONObject callEchoMe() throws Exception {
        return callFunction("echoMe", new JSONObject());
    }

    // ------------------------------------------------------------
    // Dış API: role getter/setter
    // ------------------------------------------------------------
    public @Nullable String getUserRole() { return userRole; }
    public void setUserRole(@Nullable String role) { this.userRole = role; }

    // ------------------------------------------------------------
    // Hatalar
    // ------------------------------------------------------------
    public static class HttpException extends IOException {
        public final int code;
        public final String body;
        public HttpException(int code, String body) {
            super("HTTP " + code + " — " + body);
            this.code = code; this.body = body;
        }
    }

    // ------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------
    @Nullable
    private static String toAsciiRole(@NonNull String s) {
        // Türkçe karakterleri indirger; ASCII dışı kalırsa null döner
        String mapped = s
                .replace('Ç','C').replace('ç','c')
                .replace('Ğ','G').replace('ğ','g')
                .replace('İ','I').replace('ı','i')
                .replace('Ö','O').replace('ö','o')
                .replace('Ş','S').replace('ş','s')
                .replace('Ü','U').replace('ü','u');
        for (int i = 0; i < mapped.length(); i++) {
            char c = mapped.charAt(i);
            if (c < 0x20 || c > 0x7E) return null; // ASCII dışı varsa header eklemeyelim
        }
        return mapped;
    }

    private static JSONObject toJson(String s) throws JSONException {
        if (s == null || s.isEmpty()) return new JSONObject();
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return new JSONObject();
        char c = trimmed.charAt(0);
        if (c == '[') {
            JSONArray arr = new JSONArray(s);
            JSONObject out = new JSONObject();
            out.put("_", arr);
            return out;
        }
        return new JSONObject(s);
    }
    public void checkPendingCompanion(@NonNull String deviceId,
                                      @NonNull PendingCallback cb) {
        Map<String, String> q = new HashMap<>();
        q.put("deviceId", deviceId);

        endpointAsync("/checkPendingCompanion", q, /*body=*/null, /*post=*/false,
                new EndpointCallback() {
                    @Override public void onSuccess(JSONObject resp) {
                        try {
                            // Esnek yanıt yorumlama
                            boolean has = resp.optBoolean("has",
                                    resp.optBoolean("hasPending", resp.has("companion")));
                            JSONObject comp = resp.optJSONObject("companion");

                            // Bazı backend’ler companion’ı köke koyabilir:
                            if (comp == null && has && resp.length() > 0) {
                                // ‘companion’ alanı yoksa ama başka alanlar varsa tüm kökü companion say
                                comp = resp;
                            }

                            final JSONObject result = (has ? comp : null);
                            main.post(() -> cb.onResult(result));
                        } catch (Throwable parseErr) {
                            main.post(() -> cb.onError(parseErr));
                        }
                    }
                    @Override public void onError(Throwable error) {
                        main.post(() -> cb.onError(error));
                    }
                });
    }

    private static JSONObject toJsonFromObject(Object o) throws JSONException {
        if (o == null) return new JSONObject();
        if (o instanceof Map) return new JSONObject((Map<?, ?>) o);
        if (o instanceof String) return toJson((String) o);
        JSONObject out = new JSONObject();
        out.put("data", String.valueOf(o));
        return out;
    }

    private static class Util {
        static String urlEncode(String s) {
            try {
                return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return s;
            }
        }
        @SuppressWarnings("unchecked")
        static Map<String, Object> jsonToMap(JSONObject json) {
            Map<String, Object> map = new HashMap<>();
            if (json == null) return map;
            JSONArray names = json.names();
            if (names == null) return map;
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                Object v = json.opt(k);
                map.put(k, v);
            }
            return map;
        }
    }
}
