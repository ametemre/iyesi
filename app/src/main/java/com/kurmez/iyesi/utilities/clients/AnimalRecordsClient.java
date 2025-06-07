package com.kurmez.iyesi.utilities.clients;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.io.IOException;
import java.util.Map;

import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;

/**
 * FullComment: Hayvan kayıtları için CRUD işlemlerini sağlayan HTTP istemcisi.
 * - Firebase ID Token ile her isteği kimlik doğrulamalı.
 * - ToDo: Uç nokta URL'lerinizi güncelleyin.
 */
public class AnimalRecordsClient {
    // ToDo: Projenize göre base URL'i ayarlayın
    private static final String BASE_URL = "https://YOUR_REGION-YOUR_PROJECT.cloudfunctions.net";

    // FullComment: CRUD endpoint'leri
    private static final String ENDPOINT_CREATE = "/createPet";
    private static final String ENDPOINT_UPDATE = "/updatePet";
    private static final String ENDPOINT_DELETE = "/deletePet";
    private static final String ENDPOINT_LIST   = "/listPets";

    private final OkHttpClient client = new OkHttpClient();

    /**
     * FullComment: Basit callback arayüzü, yanıt gövdesi veya hata döner.
     */
    public interface HttpCallback {
        void onSuccess(String body);
        void onError(Exception e);
    }

    /**
     * FullComment: Yeni hayvan kaydı oluşturur.
     * ToDo: "fields" içine gerekli parametreleri ekleyin.
     */
    public void createAnimal(Map<String, String> fields, HttpCallback callback) {
        callWithBody(ENDPOINT_CREATE, fields, callback);
    }

    /**
     * FullComment: Mevcut kaydı günceller.
     * ToDo: "fields" içine id ve güncellenecek alanları ekleyin.
     */
    public void updateAnimal(Map<String, String> fields, HttpCallback callback) {
        callWithBody(ENDPOINT_UPDATE, fields, callback);
    }

    /**
     * FullComment: Kaydı siler.
     * ToDo: "id" alanını fields içinde gönderin.
     */
    public void deleteAnimal(String id, HttpCallback callback) {
        callWithBody(ENDPOINT_DELETE, Map.of("id", id), callback);
    }

    /**
     * FullComment: Hayvan listesi getirir.
     * @param limit Maksimum kayıt sayısı
     */
    public void listAnimals(int limit, HttpCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("User not logged in"));
            return;
        }
        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                callback.onError(task.getException());
                return;
            }
            String token = task.getResult().getToken();
            if (token == null) {
                callback.onError(new IllegalStateException("Token alınamadı"));
                return;
            }
            // URL oluştur
            HttpUrl url = HttpUrl.parse(BASE_URL + ENDPOINT_LIST).newBuilder()
                    .addQueryParameter("limit", String.valueOf(limit))
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer " + token)
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onError(e);
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        callback.onError(new IOException("HTTP code: " + response.code()));
                        return;
                    }
                    callback.onSuccess(response.body().string());
                }
            });
        });
    }

    /**
     * FullComment: POST ve update için genel metod.
     */
    private void callWithBody(String endpoint, Map<String,String> fields, HttpCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onError(new IllegalStateException("User not logged in"));
            return;
        }
        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                callback.onError(task.getException());
                return;
            }
            String token = task.getResult().getToken();
            if (token == null) {
                callback.onError(new IllegalStateException("Token alınamadı"));
                return;
            }
            // Form body oluştur
            FormBody.Builder formBuilder = new FormBody.Builder();
            for (Map.Entry<String,String> entry : fields.entrySet()) {
                formBuilder.add(entry.getKey(), entry.getValue());
            }
            RequestBody body = formBuilder.build();
            Request request = new Request.Builder()
                    .url(BASE_URL + endpoint)
                    .addHeader("Authorization", "Bearer " + token)
                    .post(body)
                    .build();
            client.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    callback.onError(e);
                }
                @Override public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        callback.onError(new IOException("HTTP code: " + response.code()));
                        return;
                    }
                    callback.onSuccess(response.body().string());
                }
            });
        });
    }
}
