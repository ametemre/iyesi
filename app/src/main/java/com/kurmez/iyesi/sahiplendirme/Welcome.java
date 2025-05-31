package com.kurmez.iyesi.sahiplendirme;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;
import android.content.Intent;
import android.os.Bundle;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.MainActivity;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sahiplendirme.PetCompanion;
import com.kurmez.iyesi.sahiplendirme.Sahiplendirme;
import com.kurmez.iyesi.social.ExplorePrivate;
import com.kurmez.iyesi.utilities.CompanionAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Welcome Activity:
 * - Uygulamanın ana karşılama ekranı.
 * - “Priority Pets” Cloud Function’dan hayvan listesini çeker.
 * - Kullanıcı etkileşimlerine (dokunma, buton tıklama) göre veri yeniler.
 * - Firebase ID Token ile her isteği kimlik doğrulamalı.
 */
public class Welcome extends AppCompatActivity {
    String gatewayUrl = "https://iyesi-gateway-abc123-uc.a.gateway.dev/getPriorityPets?limit=5";
    // Çıkış için MainActivity.Quit() metodunu kullanıyoruz.
    private MainActivity mainActivity = new MainActivity();

    // Liste görüntülemek için ListView ve adapter
    private ListView listView;
    private CompanionAdapter adapter;
    private final List<PetCompanion> companions = new ArrayList<>();

    // HTTP istekleri için OkHttpClient
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(chain -> {
                Request req = chain.request();
                Log.d("HTTP-REQ", req.method() + " " + req.url());
                for (String name : req.headers().names()) {
                    Log.d("HTTP-REQ", name + ": " + req.header(name));
                }
                return chain.proceed(req);
            })
            .build();

    // Firebase ID Token (Bearer olarak header'a eklenecek)
    private String idToken;

    // --- Cloud Function uç noktaları (güncellendi) ---
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-a651a.cloudfunctions.net/getPriorityPets";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome_v2);

        // UI bileşenleri
        ImageView imgWelcome    = findViewById(R.id.img_welcome);
        listView                = findViewById(R.id.list_view);
        Button quit             = findViewById(R.id.quit);
        Button messageBtn       = findViewById(R.id.message);
        Button notificationBtn  = findViewById(R.id.notification);

        // ListView + adapter kurulumu
        adapter = new CompanionAdapter(this, companions);
        listView.setAdapter(adapter);

        // İnternet bağlantısını kontrol et
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
        if (netInfo == null || !netInfo.isConnected()) {
            Toast.makeText(this, "İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin.", Toast.LENGTH_LONG).show();
            // Yine de UI açılabilir, sadece veri çekimi engellenir
        }

        // Login kontrolü
        FirebaseAuth mAuth = FirebaseAuth.getInstance();
        FirebaseUser user = mAuth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        }

        // Token alma + hata yönetimi
        try {
            user.getIdToken(true)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful() || task.getResult() == null) {
                            Exception e = task.getException();
                            Log.e("TOKEN_ERROR", "Token alınamadı", e);
                            Toast.makeText(
                                    Welcome.this,
                                    "Token alınırken hata oluştu. Lütfen tekrar giriş yapın.",
                                    Toast.LENGTH_LONG
                            ).show();
                            finish();
                            return;
                        }

                        idToken = task.getResult().getToken();
                        if (idToken == null || idToken.isEmpty()) {
                            Toast.makeText(
                                    Welcome.this,
                                    "Token boş geldi! Lütfen tekrar deneyin.",
                                    Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }

                        // Tüm kontroller geçildiyse veriyi çek
                        try {
                            fetchPriorityPets();
                        } catch (Exception e) {
                            Log.e("FETCH_ERROR", "Öncelikli hayvanlar yüklenirken hata", e);
                            runOnUiThread(() ->
                                    Toast.makeText(
                                            Welcome.this,
                                            "Veri yüklenirken beklenmedik bir hata oluştu.",
                                            Toast.LENGTH_SHORT
                                    ).show()
                            );
                        }
                    });
        } catch (Exception e) {
            Log.e("TOKEN_ERROR", "Beklenmedik hata", e);
            Toast.makeText(
                    this,
                    "Belirsiz bir hata oluştu. Uygulamayı yeniden başlatın.",
                    Toast.LENGTH_LONG
            ).show();
            finish();
            return;
        }

        // Kısa tıklamada private feed ekranına geç
        imgWelcome.setOnClickListener(v -> {
            startActivity(new Intent(Welcome.this, ExplorePrivate.class));
            Toast.makeText(Welcome.this, "Private Explore açıldı", Toast.LENGTH_SHORT).show();
        });

        // Uzun tıklamada sahiplendirme formuna geç
        imgWelcome.setOnLongClickListener(v -> {
            startActivity(new Intent(Welcome.this, Sahiplendirme.class));
            Toast.makeText(Welcome.this, "Sahiplendirme formu açıldı", Toast.LENGTH_SHORT).show();
            return true;
        });

        // Mesaj gönderme (ilk listedeki hayvana test mesajı)
        messageBtn.setOnClickListener(v -> {
            if (companions.isEmpty()) {
                Toast.makeText(Welcome.this, "Henüz hayvan listesi boş.", Toast.LENGTH_SHORT).show();
                return;
            }
            PetCompanion first = companions.get(0);
            //sendMessage(first.getFinderName(), "Merhaba!", 42);
        });

        // Çıkış butonu: uygulamayı kapat
        quit.setOnClickListener(v -> {
            mainActivity.Quit();
            finish();
        });
    }


    /**
     * getPriorityPets Cloud Function'ına GET isteği yapar.
     * Authorization header'a ID Token eklenir.
     */
    private void fetchPriorityPets() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;
        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.e("TOKEN_REFRESH", "Token refresh failed", task.getException());
                return;
            }

            idToken = task.getResult().getToken();
            if (idToken == null || idToken.isEmpty()) return;

            // İsteği yeni token ile gönder
            Request request = new Request.Builder()
                    .url(CF_GET_PRIORITY)
                    .addHeader("Authorization", "Bearer " + idToken)
                    .build();

            // Mevcut HTTP istek kodunuz...
        });

        if (idToken == null || idToken.isEmpty()) return;

        Request request = new Request.Builder()
                .url(CF_GET_PRIORITY)
                .addHeader("Authorization", "Bearer " + idToken)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.d("HTTP-RESP", "Failure: " + e.getMessage());
                runOnUiThread(() ->
                        Toast.makeText(
                                Welcome.this,
                                "Öncelikli hayvanlar yüklenemedi",
                                Toast.LENGTH_SHORT
                        ).show()
                );
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();
                Log.d("HTTP-RESP", "Code: " + response.code() + ", Body: " + body);

                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        parsePriorityPets(body);
                    } else {
                        try {
                            JSONObject errorJson = new JSONObject(body);
                            String errorMsg = errorJson.optString("details", "Unknown error");
                            Toast.makeText(Welcome.this, "Error: " + errorMsg, Toast.LENGTH_LONG).show();
                        } catch (JSONException e) {
                            Toast.makeText(Welcome.this, "Error: " + body, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    /**
     * getPriorityPets'ten dönen JSON'u ayrıştırır.
     */
    private void parsePriorityPets(String json) {
        try {
            JSONObject root   = new JSONObject(json);
            JSONArray arr     = root.getJSONArray("pets");
            companions.clear();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);

                String name          = item.optString("name");
                String species       = item.optString("species");
                String breed         = item.optString("breed");
                String health        = item.optString("health");
                String foundDate     = item.optString("foundDate");
                String foundLocation = item.optString("foundLocation");
                String imageResId    = item.optString("imageResId");
                String finderName    = item.optString("finderName");
                long   timestamp     = item.optLong("timestamp");

                PetCompanion pc = new PetCompanion(
                        name,
                        species,
                        breed,
                        "",
                        health,
                        foundDate,
                        foundLocation,
                        "",
                        imageResId,
                        finderName,
                        timestamp
                );
                companions.add(pc);
            }

            runOnUiThread(adapter::notifyDataSetChanged);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}