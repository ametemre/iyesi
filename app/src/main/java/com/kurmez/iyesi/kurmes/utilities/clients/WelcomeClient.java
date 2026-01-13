package com.kurmez.iyesi.kurmes.utilities.clients;

import android.content.Context;
import android.util.Log;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WelcomeClient {
    private static final String TAG = "WelcomeClient";
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-aef03.cloudfunctions.net/getPriorityPets";

    private String idToken;
    // Diğer gerekli değişkenler (adapter, companions vb.)
    private final List<Soul> companions = new ArrayList<>();
    // Optimize edilmiş HTTP istemcisi
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(chain -> {

                Request req = chain.request();
                //Log.d(TAG, "HTTP Request: " + req.method() + " " + req.url());
                Log.d(TAG, "Authorization Header: " + req.header("Authorization"));
                return chain.proceed(req);
            })
            .build();

    private void checkAuthAndGetToken(Context context) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null) {
            Log.w(TAG, "Kullanıcı oturumu bulunamadı, giriş ekranına yönlendiriliyor");
            return;
        }

        user.getIdToken(true)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "Token alma başarısız", task.getException());
                        // ÖNCE: Hardcoded "Oturum bilgileri alınamadı. Lütfen tekrar giriş yapın."
                        // ŞİMDİ: String resource kullanımı
                        showToast(context.getString(R.string.welcome_client_toast_session_info_not_available), context);
                        return;
                    }

                    GetTokenResult result = task.getResult();
                    if (result == null || result.getToken() == null) {
                        Log.e(TAG, "Token sonucu boş");
                        // ÖNCE: Hardcoded "Oturum bilgileri geçersiz. Lütfen tekrar giriş yapın."
                        // ŞİMDİ: String resource kullanımı
                        showToast(context.getString(R.string.welcome_client_toast_session_info_invalid), context);
                        return;
                    }

                    idToken = result.getToken();
                    Log.d(TAG, "Token başarıyla alındı. İlk 10 karakter: " +
                            (idToken.length() > 10 ? idToken.substring(0, 10) + "..." : idToken));

                    // Token alındıktan sonra pet bilgilerini getir
                    fetchPriorityPets(idToken,context);
                });
    }


    private void fetchPriorityPets(String idToken, Context context) {
        if (idToken == null || idToken.isEmpty()) {
            Log.e(TAG, "fetchPriorityPets: Geçersiz token");
            // ÖNCE: Hardcoded "Oturum bilgileri geçersiz. Lütfen tekrar giriş yapın."
            // ŞİMDİ: String resource kullanımı
            showToast(context.getString(R.string.welcome_client_toast_session_info_invalid), context);
            return;
        }

        Log.d(TAG, "Sunucuya istek gönderiliyor...");
        Request request = new Request.Builder()
                .url(CF_GET_PRIORITY)
                .addHeader("Authorization", "Bearer " + idToken)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Sunucu bağlantı hatası: " + e.getMessage());
                //runOnUiThread(() -> showToast("Sunucuya bağlanılamadı: " + e.getMessage(),context));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    final int statusCode = response.code();
                    final String responseBody = response.body() != null ? response.body().string() : "{}";

                    Log.d(TAG, "Sunucu yanıtı: " + statusCode);
                    Log.d(TAG, "Yanıt gövdesi: " + responseBody);

                    //runOnUiThread(() -> {if (response.isSuccessful()) {parsePriorityPets(responseBody, context);} else {handleHttpError(statusCode, responseBody,context);}});
                } finally {
                    if (response.body() != null) {
                        response.body().close();
                    }
                }
            }
        });
    }

    private void handleHttpError(int statusCode, String responseBody, Context context) {
        Log.e(TAG, "HTTP Hatası: " + statusCode + " | Yanıt: " + responseBody);

        // ÖNCE: Hardcoded error mesajları
        // ŞİMDİ: String resource kullanımı
        String errorMessage = context.getString(R.string.welcome_client_toast_server_error, statusCode);

        switch (statusCode) {
            case 401:
                errorMessage = context.getString(R.string.welcome_client_toast_auth_error);
                FirebaseAuth.getInstance().signOut();
                break;
            case 403:
                errorMessage = context.getString(R.string.welcome_client_toast_access_denied);
                break;
            case 404:
                errorMessage = context.getString(R.string.welcome_client_toast_resource_not_found);
                break;
            case 500:
                errorMessage = context.getString(R.string.welcome_client_toast_server_internal_error);
                break;
            default:
                try {
                    JSONObject json = new JSONObject(responseBody);
                    if (json.has("error")) {
                        errorMessage = json.getString("error");
                    } else if (json.has("message")) {
                        errorMessage = json.getString("message");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Hata yanıtı ayrıştırılamadı", e);
                }
                break;
        }

        showToast(errorMessage,context);
    }



    private void showToast(String message, Context context) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }


}