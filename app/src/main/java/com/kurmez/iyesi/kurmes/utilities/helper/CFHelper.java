package com.kurmez.iyesi.kurmes.utilities.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.BuildConfig;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableReference;
import com.kurmez.iyesi.AppCheckTokenProvider;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.social.Profile;

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
public class CFHelper {

    // ------------------------------------------------------------
    // Listener (UI geri bildirimleri için) — JENERİK
    // ------------------------------------------------------------
    public interface Listener<T> {
        default void onRoleRefreshed(@Nullable String role) {}
        default void onCallFailed(@NonNull String apiName, @NonNull Throwable error) {}
        default void onPriorityPets(@NonNull List<T> pets) {}
    }

    // ------------------------------------------------------------
    // Alanlar
    // ------------------------------------------------------------
    private static final String TAG = "CFHelper";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    // (Bazı eski UI bağımlılıkları için muhafaza edilen alanlar — kullanılmıyorsa zararsız)
    @Nullable private android.widget.Spinner dbPathSpinner;
    @Nullable private android.widget.ArrayAdapter<String> pathAdapter;
    @NonNull  private final List<String> pathItems = new ArrayList<>();
    @NonNull  private final Deque<String> pathStack = new ArrayDeque<>();
    @Nullable private DatabaseReference browseRef; // gezginin o anki referansı

    private final Context appContext;
    private final OkHttpClient http;
    private final FirebaseAuth auth;
    private final FirebaseAppCheck appCheck;
    private final FirebaseFunctions functions;
    private final Handler main = new Handler(Looper.getMainLooper());

    private String baseHttpUrl; // örn: https://us-central1-<PROJECT_ID>.cloudfunctions.net
    private final String region;
    private String pathPrefix = ""; // ops. /v1 gibi

    /** İstemci tarafında saklanan rol bilgisi (sunucudan getRole ile çekilir). */
    private volatile @Nullable String userRole;

    /** Opsiyonel cihaz kimliği — header olarak iletilir. */
    private volatile @Nullable String deviceId;

    /** Uygulamaya dönecek callback — bu sınıfta Soul için tipledik. */
    private final @Nullable Listener<Soul> listener;

    /** Son çağrının (GET) URL’ini debug için tutmak istersen */
    public @Nullable String urlStr;

    // ------------------------------------------------------------
    // Yapıcılar
    // ------------------------------------------------------------
    public CFHelper(@NonNull Context ctx,
                    @NonNull String projectId,
                    @NonNull String region,
                    @Nullable Listener<Soul> listener) {
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

    // eski ctor’u geriye dönük koru (region=us-central1)
    public CFHelper(@NonNull Context ctx,
                    @NonNull String projectId,
                    @Nullable Listener<Soul> listener) {
        this(ctx, projectId, "us-central1", listener);
    }

    // ------------------------------------------------------------
    // Opsiyonel ayarlar
    // ------------------------------------------------------------
    /** /v1 gibi bir prefix istiyorsan ayarla. Boş veya null ise kaldırır. */
    public void setPathPrefix(@Nullable String prefix) {
        if (prefix == null) prefix = "";
        this.pathPrefix = prefix.isEmpty() ? "" : (prefix.startsWith("/") ? prefix : "/" + prefix);
    }

    /** Prod/Emu/Proxy ortamları için taban URL’i override et. */
    public void overrideBaseHttpUrl(@NonNull String absoluteBase) {
        this.baseHttpUrl = absoluteBase;
    }

    /** Cihaz kimliği header’ı için (X-Device-Id). */
    public void setDeviceId(@Nullable String deviceId) {
        this.deviceId = (deviceId == null || deviceId.trim().isEmpty()) ? null : deviceId.trim();
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

        // Force refresh for every call
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
            if (asciiRole != null) hb.add("X-User-Role", asciiRole);
        }

        if (deviceId != null && !deviceId.isEmpty()) {
            hb.add("X-Device-Id", deviceId);
        }

        return hb.build();
    }

    // ------------------------------------------------------------
    // URL yardımcıları
    // ------------------------------------------------------------
    private String buildUrl(@NonNull String path, @Nullable Map<String,String> query) {
        StringBuilder url = new StringBuilder(baseHttpUrl);
        if (!pathPrefix.isEmpty()) url.append(pathPrefix);
        url.append(path);
        if (query != null && !query.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append("&");
                first = false;
                url.append(e.getKey()).append("=").append(Util.urlEncode(e.getValue()));
            }
        }
        return url.toString();
    }

    // ------------------------------------------------------------
    // HTTP yardımcıları
    // ------------------------------------------------------------
    private JSONObject doGetJson(String path, @Nullable Map<String, String> query) throws Exception {
        Tokens t = refreshTokensBlocking();
        String url = buildUrl(path, query);
        this.urlStr = url;
        Log.d(TAG, "GET  " + url);

        Request req = new Request.Builder()
                .url(url)
                .headers(buildAuthHeaders(t))
                .get()
                .build();
        if (BuildConfig.DEBUG) {
            boolean hasAC = req.header("X-Firebase-AppCheck") != null;
            boolean hasAuth = req.header("Authorization") != null;
            Log.d(TAG, "[POST] " + url + " | AppCheck=" + (hasAC?"yes":"no") + " Auth=" + (hasAuth?"yes":"no"));
        }
        try (Response resp = http.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful()) throw new HttpException(resp.code(), body);
            return toJson(body);
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
    }

    private JSONObject doPostJson(String path, @Nullable JSONObject json) throws Exception {
        Tokens t = refreshTokensBlocking();
        String url = buildUrl(path, null);
        String payload = (json == null ? "{}" : json.toString());
        RequestBody body = RequestBody.create(JSON, payload);

        Request req = new Request.Builder()
                .url(url)
                .headers(buildAuthHeaders(t))
                .post(body)
                .build();

        Log.d(TAG, "POST " + url);
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
        // Callable tarafında SDK token’ı taşır; yine de force refresh yapıyoruz
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

    private void endpointAsync(@NonNull String path,
                               @Nullable Map<String,String> query,
                               @Nullable JSONObject body,
                               boolean post,
                               @NonNull EndpointCallback cb) {
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
    // Dış API’ler
    // ------------------------------------------------------------
    private static String getCustomClaims(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length >= 2) {
                String payload = parts[1];
                byte[] decoded = Base64.decode(payload, Base64.URL_SAFE);
                return new String(decoded, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

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

    public void refreshRole(@NonNull RoleCallback callback) {
        new Thread(() -> {
            try {
                JSONObject r = null;
                try {
                    r = doGetJson("/getRole", null); // bazı projelerde çalışır
                } catch (Throwable getErr) {
                    // GET 405 vs. durumunda POST fallback
                    try {
                        r = doPostJson("/getRole", new JSONObject());
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

    // ---------- Messaging / Users ----------
    public interface UsersCallback {
        void onSuccess(@NonNull List<Profile> users);
        void onError(@NonNull Throwable error);
    }

    /** /listAllUsersHttp (GET) → List<Profile> */
    public void listAllUsers(@Nullable Integer limit,
                             @Nullable String pageToken,
                             @NonNull UsersCallback cb) {
        Map<String, String> q = new HashMap<>();
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (pageToken != null) q.put("pageToken", pageToken);

        endpointAsync("/listAllUsersHttp", q, null, /*post=*/false, new EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                try {
                    List<Profile> list = parseUsers(resp); // BG
                    main.post(() -> cb.onSuccess(list));   // UI
                } catch (Throwable e) {
                    main.post(() -> cb.onError(e));
                }
            }
            @Override public void onError(Throwable error) { main.post(() -> cb.onError(error)); }
        });
    }

    private ArrayList<Profile> parseUsers(@NonNull JSONObject root) throws Exception {
        ArrayList<Profile> out = new ArrayList<>();
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
    }

    /** İstersen ham JSON’a da erişmek için. */
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

    /** POST /submitSoulInNeed — 404’te callable fallback dener. */
    public void submitSoulInNeed(@NonNull JSONObject payload, @NonNull EndpointCallback cb) {
        endpointAsync("/submitSoulInNeed", null, payload, /*post=*/true, new EndpointCallback() {
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

    // ---------- Pending companion (deviceId ile) ----------
    public interface PendingCallback {
        /** companion = null → pending yok demektir. */
        void onResult(@Nullable JSONObject companion);
        void onError(@NonNull Throwable error);
    }

    public void checkPendingCompanion(@NonNull String deviceId, @NonNull PendingCallback cb) {
        Map<String, String> q = new HashMap<>();
        q.put("deviceId", deviceId);

        endpointAsync("/checkPendingCompanion", q, null, /*post=*/false, new EndpointCallback() {
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

    // Örn: kaynak erişimi, SharedPreferences vs. — UI yok
    public static void saveFlag(@Nullable Context ctx, String key, boolean v) {
        Context app = ctx != null ? ctx.getApplicationContext() : AppCheckTokenProvider.app();
        app.getSharedPreferences("cf", Context.MODE_PRIVATE)
                .edit().putBoolean(key, v).apply();
    }
}
