package com.kurmez.iyesi.kurmes.utilities.helper;

import static com.kurmez.iyesi.kurmes.utilities.helper.CFHelper.baseHttpUrl;
import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.buildAuthHeaders;
import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.refreshTokensBlocking;
import static com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient.JSON;

import android.os.Build;
import android.util.Log;

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
import okhttp3.Headers;
import okhttp3.MediaType;
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

import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
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
            return toJson(body);
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
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static String buildUrl(@NonNull String path, @Nullable Map<String, String> query) {
        StringBuilder url = new StringBuilder(baseHttpUrl);

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
}
