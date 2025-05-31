package com.kurmez.iyesi.utilities;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Call;
import okhttp3.Callback;
import java.io.IOException;

/**
 * FullComment: Sadeleştirilmiş HTTP istemcisi sadece "Priority Pets" Cloud Function çağrısı içindir.
 * - ToDo: BASE_URL ve ENDPOINT değerlerini projenize göre güncelleyin.
 */
public class CloudFunctionsClient {
    /**
     * ToDo: Cloud Functions base URL'inizi girin (region-project formatında).
     */
    private static final String BASE_URL = "https://YOUR_REGION-YOUR_PROJECT.cloudfunctions.net";
    /**
     * FullComment: getPriorityPets fonksiyonu için endpoint.
     */
    private static final String ENDPOINT_GET_PRIORITY = "/getPriorityPets";

    private final OkHttpClient client = new OkHttpClient();

    /**
     * FullComment: Basit callback, başarılı gövde veya hata döner.
     */
    public interface HttpCallback {
        /**
         * FullComment: HTTP isteği başarılı olduğunda JSON gövdesini gönderir.
         */
        void onSuccess(String body);
        /**
         * FullComment: Hata durumunda çağrılır.
         */
        void onError(Exception e);
    }

    /**
     * FullComment: "limit" parametresi ile Cloud Function'ı GET olarak çağırır.
     * @param limit ToDo: Gerekirse başka query parametreleri ekleyin.
     * @param callback Cevabı işlemek için callback.
     */
    public void fetchPriorityPets(int limit, HttpCallback callback) {
        // ToDo: Kullanıcı oturum kontrolü
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
            if (token == null || token.isEmpty()) {
                callback.onError(new IllegalStateException("Token alınamadı"));
                return;
            }

            HttpUrl url = HttpUrl.parse(BASE_URL + ENDPOINT_GET_PRIORITY)
                    .newBuilder()
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
                    String body = response.body().string();
                    callback.onSuccess(body);
                }
            });
        });
    }
}
