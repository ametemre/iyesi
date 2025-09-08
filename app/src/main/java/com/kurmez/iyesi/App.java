package com.kurmez.iyesi;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.security.ProviderInstaller;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.kayra.TopActivity;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class App extends Application {
    private static final String TAG = "MyApp";
    private static App sInstance;

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        TopActivity.init(this);

        FirebaseApp.initializeApp(this);

        // App Check provider seçimi
        if (BuildConfig.DEBUG) {
            FirebaseAppCheck.getInstance()
                    .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance());
        } else {
            FirebaseAppCheck.getInstance()
                    .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
        }

        // 🔹 BAŞLANGIÇ KONTROLLERİ
        startupChecks();  // <-- ekle

        // (İsteğe bağlı) developer log’u
        FirebaseAppCheck.getInstance().getAppCheckToken(true)
                .addOnSuccessListener(t -> Log.d("APPCHECK", "Token alındı, uzunluk=" + (t != null && t.getToken() != null ? t.getToken().length() : 0)))
                .addOnFailureListener(e -> Log.w("APPCHECK", "Token alınamadı: " + e.getMessage()));
    }
    private void startupChecks() {
        // 1) TLS Provider (Play Services varsa)
        safeInstallProviderIfNeeded(this);

        // 2) App Check token ısındırma (cache’den çekmeyi dene, yoksa üret)
        FirebaseAppCheck.getInstance()
                .getAppCheckToken(false) // önce cache
                .addOnFailureListener(e -> FirebaseAppCheck.getInstance().getAppCheckToken(true));

        // 3) Auth state kontrol + gerekirse ID token ısındırma
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseAuth.AuthStateListener listener = new FirebaseAuth.AuthStateListener() {
            @Override public void onAuthStateChanged(@androidx.annotation.NonNull FirebaseAuth fa) {
                FirebaseUser u = fa.getCurrentUser();
                if (u != null) {
                    // ID token’ı önceden üret, CF çağrısında gecikme olmasın
                    u.getIdToken(false).addOnFailureListener(err -> u.getIdToken(true));
                } else {
                    Log.w(TAG, "Kullanıcı oturumu yok (startup). Giriş ekranında yakalanmalı.");
                }
                // Tek seferlik ısındırma için listener'ı kaldır
                fa.removeAuthStateListener(this);
            }
        };
        auth.addAuthStateListener(listener);
    }

    public static Context app() { return sInstance; } // Uygulama context'i

    /**
     * Eğer cihazda Google Play Services uygunsa ProviderInstaller'ı başlatır,
     * değilse atlayıp DEVELOPER_ERROR log'larını önler.
     */

    private void safeInstallProviderIfNeeded(Context ctx) {
        GoogleApiAvailability api = GoogleApiAvailability.getInstance();
        int status = api.isGooglePlayServicesAvailable(ctx);
        if (status == ConnectionResult.SUCCESS) {
            ProviderInstaller.installIfNeededAsync(ctx, new ProviderInstaller.ProviderInstallListener() {
                @Override
                public void onProviderInstalled() {
                    Log.d(TAG, "Provider başarıyla yüklendi");
                }

                @Override
                public void onProviderInstallFailed(int errorCode, Intent recoveryIntent) {
                    Log.w(TAG, "Provider yüklemesi başarısız, code: " + errorCode);
                }
            });
        } else {
            Log.w(TAG, "Google Play Services mevcut değil (code: " + status + "), ProviderInstaller atlandı");
        }
    }
    public void sendRequestWithAppCheckAndAuth(JSONObject payload) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Log.e("Message", "Kullanıcı yok");
            return;
        }
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        // 1) Auth ID token
        user.getIdToken(true).addOnSuccessListener(idTok -> {
            String idToken = idTok.getToken();

            // 2) App Check token
            FirebaseAppCheck.getInstance().getAppCheckToken(true)
                    .addOnSuccessListener(appTok -> {
                        String appCheckToken = appTok.getToken();

                        RequestBody body = RequestBody.create(
                                payload.toString(), MediaType.get("application/json; charset=utf-8"));
                        Log.i("App Check token", appCheckToken.toString());
                        Request req = new Request.Builder()
                                .url("https://us-central1-iyesi-e8d4f.cloudfunctions.net/appSend")
                                .addHeader("Authorization", "Bearer " + idToken)     // Firebase Auth
                                .addHeader("X-Firebase-AppCheck", appCheckToken)      // App Check
                                .post(body)
                                .build();

                        okHttpClient.newCall(req).enqueue(new Callback() {
                            @Override public void onFailure(Call call, IOException e) {
                                Log.e("Message", "İstek hatası", e);
                            }
                            @Override public void onResponse(Call call, Response rsp) throws IOException {
                                String b = rsp.body() != null ? rsp.body().string() : "";
                                Log.d("Message", "HTTP " + rsp.code() + " | body=" + b);
                                Log.d("Message", "HTTP " + rsp.code() + " " + rsp.message());
                            }
                        });
                    })
                    .addOnFailureListener(e -> {
                        Log.e("Message", "App Check token alınamadı", e);
                        // İsterseniz burada kullanıcıya kısa bir uyarı gösterin
                    });

        }).addOnFailureListener(e -> {
            Log.e("Message", "Auth ID token alınamadı", e);
        });

    }

}
