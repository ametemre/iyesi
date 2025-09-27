package com.kurmez.iyesi.kurmes.utilities.helper;


import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.buildAuthHeaders;
import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.refreshTokensBlocking;
import static com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient.JSON;

import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import com.kurmez.iyesi.kayra.Classes.data.Iye;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.BuildConfig;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableReference;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RequiresApi(api = Build.VERSION_CODES.N)
public class JsonHelper {
    private static String TAG = "JsonHelper";
    private FirebaseFunctions functions;
    private final static OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    @RequiresApi(api = Build.VERSION_CODES.N)
    public JSONObject getPriorityPets() throws Exception {
        return doGetJson("/getPriorityPets", null);
    }
    // JsonHelper.java veya uygun bir utils sınıfına ekle
    public static void splitSoulsIntoArrays(Object jsonLike, JSONArray catsArr, JSONArray dogsArr, JSONArray criticalArr) throws org.json.JSONException {
        // 1) items dizisini çıkar
        JSONArray items = null;
        if (jsonLike instanceof String) {
            String t = ((String) jsonLike).trim();
            if (t.startsWith("[")) {
                items = new JSONArray(t);
            } else {
                JSONObject obj = new JSONObject(t);
                items = obj.optJSONArray("items");            // beklenen alan
                if (items == null) items = obj.optJSONArray("data"); // olası alternatif
                if (items == null) items = new JSONArray();   // yoksa boş
            }
        } else if (jsonLike instanceof JSONObject) {
            JSONObject obj = (JSONObject) jsonLike;
            items = obj.optJSONArray("items");
            if (items == null) items = obj.optJSONArray("data");
            if (items == null) items = new JSONArray();
        } else if (jsonLike instanceof JSONArray) {
            items = (JSONArray) jsonLike;
        } else {
            items = new JSONArray();
        }

        // 2) Tür ve sağlık durumuna göre ayır
        for (int i = 0; i < items.length(); i++) {
            Object o = items.opt(i);
            if (!(o instanceof JSONObject)) continue;
            JSONObject soul = (JSONObject) o;

            String species = soul.optString("species", "");
            String health  = soul.optString("health", "");

            if ("cat".equalsIgnoreCase(species)) {
                catsArr.put(soul);
            } else if ("dog".equalsIgnoreCase(species)) {
                dogsArr.put(soul);
            }

            if ("critical".equalsIgnoreCase(health)) {
                criticalArr.put(soul);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static JSONObject doGetJson(String path, @Nullable Map<String, String> query) throws Exception {
        FireBaseHelper.Tokens t = refreshTokensBlocking();
        String url = buildUrl(path, query);
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
            if (!resp.isSuccessful()) throw new CFHelper.HttpException(resp.code(), body);
            // ... OkHttp çağrısı sonrası:
            String raw = body; // örn: response.body().string()

// İsteği ve ham cevabı logla
            logLong("JsonHelper", "GET " + path + "  RAW_JSON(len=" + raw.length() + ")\n" + pretty(raw));

// Güvenli parse: üst seviye JSONArray gelirse sarıp döndür
            JSONObject result;
            String ti = raw.trim();
            if (ti.startsWith("[")) {
                // Objeye sar: { "items": [...] , "_wrapped": true }
                result = new JSONObject();
                result.put("_wrapped", true);
                result.put("items", new JSONArray(ti));
            } else {
                result = new JSONObject(ti);
            }

            return result;

           // return toJson(body);
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }

    }// HTTP yardımcıları
    @RequiresApi(api = Build.VERSION_CODES.N)
    static JSONObject doPostJson(String path, @Nullable JSONObject json) throws Exception {
        FireBaseHelper.Tokens t = refreshTokensBlocking();
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
            if (!resp.isSuccessful()) throw new CFHelper.HttpException(resp.code(), respBody);
            return toJson(respBody);
        }
    }
    // --- DEBUG JSON HELPERS ---
    private static void logLong(@NonNull String tag, @Nullable String msg) {
        if (msg == null) return;
        final int CHUNK = 3000;
        for (int i = 0; i < msg.length(); i += CHUNK) {
            Log.d(tag, msg.substring(i, Math.min(msg.length(), i + CHUNK)));
        }
    }

    @NonNull
    private static String pretty(@NonNull String raw) {
        try {
            String t = raw.trim();
            if (t.startsWith("[")) {
                return new org.json.JSONArray(t).toString(2);
            } else {
                return new org.json.JSONObject(t).toString(2);
            }
        } catch (Exception ignore) {
            // JSON değilse ham döndür
            return raw;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public static String buildUrl(@NonNull String path, @Nullable Map<String, String> query) {
        StringBuilder url = new StringBuilder(CFHelper.baseHttpUrl);

        url.append(path);
        if (query != null && !query.isEmpty()) {
            url.append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : query.entrySet()) {
                if (!first) url.append("&");
                first = false;
                url.append(e.getKey()).append("=").append(JsonHelper.Util.urlEncode(e.getValue()));
            }
        }
        return url.toString();
    }//------- URL yardımcıları
    static JSONObject callFunction(String name, @Nullable JSONObject data) throws Exception {
        // Callable tarafında SDK token’ı taşır; yine de force refresh yapıyoruz
        FirebaseFunctions functions = FirebaseFunctions.getInstance();
        if (functions == null) {
            refreshTokensBlocking();
        }
        HttpsCallableReference ref = functions.getHttpsCallable(name);
        Map<String, Object> map = (data == null) ? Collections.emptyMap() : JsonHelper.Util.jsonToMap(data);
        Object result = Tasks.await(ref.call(map)).getData();
        return toJsonFromObject(result);
    }
    public interface EndpointCallback {
        void onSuccess(JSONObject resp);
        void onError(Throwable error);
    }//------------------------------------------------------- Genel endpoint yürütücüsü (async)
    static void endpointAsync(@NonNull String path, @Nullable Map<String,String> query, @Nullable JSONObject body, boolean post, @NonNull EndpointCallback cb) {
        new Thread(() -> {
            try {
                JSONObject resp = post ? doPostJson(path, body) : doGetJson(path, query);
                cb.onSuccess(resp);
            } catch (Throwable t) {
                cb.onError(t);
            }
        }).start();
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
    // Yardımcılar
    // ------------------------------------------------------------
    @Nullable
    static String toAsciiRole(@NonNull String s) {
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
    // ---------- Marker uçları (örnek sarmalayıcılar) ----------
    public JSONObject markersNearby(double lat, double lng, int radiusM, int limit, @Nullable String type, @Nullable String cityKey) throws Exception {
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
    public static class Util {
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
    // IyeActivity (veya uygun bir helper sınıfı) içine ekle:

// ...

    /** Iye.java -> sunucu JSON (profil güncelle) */
    public static Map<String, Object> buildJsonFromIye(Iye iye, @Nullable Map<String, Object> claims) {
        Map<String, Object> j = new HashMap<>();
        if (!TextUtils.isEmpty(iye.getUsername())) j.put("username", iye.getUsername());
        if (!TextUtils.isEmpty(iye.getEmail()))    j.put("email",    iye.getEmail());     // sadece claims aynası
        if (!TextUtils.isEmpty(iye.getLocation())) j.put("location", iye.getLocation());
        if (!TextUtils.isEmpty(iye.getPhone()))    j.put("phone",    iye.getPhone());
        if (!TextUtils.isEmpty(iye.getAvatarUrl())) j.put("avatarUrl", iye.getAvatarUrl());

        // Claims'te tutuluyorsa kısa anahtarları da geçir (varsa):
        if (claims != null) {
            Object ak = claims.get(Iye.ClaimsKeys.AVATAR_KEY);
            Object ar = claims.get(Iye.ClaimsKeys.AVATAR_REV);
            if (ak instanceof String && !TextUtils.isEmpty((String) ak)) j.put("avatarKey", ak);
            if (ar instanceof String && !TextUtils.isEmpty((String) ar)) j.put("avatarRev", ar);
        }
        // DİKKAT: uid/role göndermiyoruz!
        return j;
    }

    /** Tek alan güncellemek için küçük yardımcı (TR veya EN anahtar gönderebilirsin) */
    public static Map<String, Object> buildSingleFieldJson(String keyTrOrEn, String value) {
        Map<String, Object> j = new HashMap<>();
        j.put(keyTrOrEn, value);   // "telefon" da olur, server normalize ediyor
        return j;
    }

}
