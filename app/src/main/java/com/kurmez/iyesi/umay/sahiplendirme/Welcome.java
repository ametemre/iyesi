package com.kurmez.iyesi.umay.sahiplendirme;

import static android.widget.Toast.LENGTH_SHORT;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.social.content.Explore;
import com.kurmez.iyesi.kurmes.social.content.ExplorePrivate;
import com.kurmez.iyesi.kurmes.social.message.Messaging;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Welcome extends AppCompatActivity {
    private static final String TAG = "WelcomeActivity";
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-a651a.cloudfunctions.net/getPriorityPets";

    private ImageView imgWelcome;
    private ImageButton quitButton;
    private ImageButton messageButton;
    private ImageButton notificationButton;
    private TextView username;
    private ListView listView;
    private String idToken;
    private CompanionAdapter adapter;
    private final List<Soul> companions = new ArrayList<>();
    private final OkHttpClient httpClient = new OkHttpClient.Builder().addInterceptor(chain -> {
                Request req = chain.request();
                Log.d("HTTP-REQ", req.method() + " " + req.url());
                for (String name : req.headers().names()) {
                    Log.d("HTTP-REQ", name + ": " + req.header(name));
                }
                return chain.proceed(req);
            }).build();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome_v2);

        // UI bileşenlerini bağla
        imgWelcome = findViewById(R.id.img_welcome);
        quitButton = findViewById(R.id.quit);
        messageButton = findViewById(R.id.message);
        notificationButton = findViewById(R.id.notification);
        listView = findViewById(R.id.list_view);
        username = findViewById(R.id.username_validation);
        // Adapter kurulumu
        adapter = new CompanionAdapter(this, companions);
        listView.setAdapter(adapter);

        // UI event listener'ları
        setupUIListeners();


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
        } else {
            username.setText(user.getEmail());
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
    }
    private void setupUIListeners() {
        imgWelcome.setOnClickListener(v -> {
            startActivity(new Intent(this, Explore.class));
        });

        imgWelcome.setOnLongClickListener(v -> {
            startActivity(new Intent(this, ExplorePrivate.class));
            return true;
        });

        quitButton.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            finish();
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // Get the selected companion
            Soul selectedCompanion = companions.get(position);
            Toast.makeText(this, "Selected: " + selectedCompanion.getBreed(), Toast.LENGTH_SHORT).show();

            // Navigate to Companion activity with the selected item's data
            Intent intent = new Intent(Welcome.this, Companion.class);
            intent.putExtra("species", selectedCompanion.getBreed());
            intent.putExtra("foundDate", selectedCompanion.getFoundDate());
            intent.putExtra("foundPlace", selectedCompanion.getFoundLocation());
            intent.putExtra("photoUrl", selectedCompanion.getImageResId());
            intent.putExtra("profileId", selectedCompanion.getFinderName());
            startActivity(intent);
        });
        // Diğer butonlar için basit işlevler
        messageButton.setOnClickListener(v ->
                startActivity(new Intent(this, Messaging.class))
        );
        notificationButton.setOnClickListener(v -> Helpers.showToastSafe(this,"Bildirim özelliği yakında gelecek"));
    }

    /**
     * getPriorityPets Cloud Function'ına GET isteği yapar.
     * Authorization header'a ID Token eklenir.
     */
    private void fetchPriorityPets() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Kullanıcı girişi yok", Toast.LENGTH_SHORT).show();
            return;
        }

        user.getIdToken(true).addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.e("TOKEN_REFRESH", "Token refresh failed", task.getException());
                Toast.makeText(Welcome.this, "Token alınamadı", Toast.LENGTH_SHORT).show();
                return;
            }

            String idToken = task.getResult().getToken();
            if (idToken == null || idToken.isEmpty()) {
                Toast.makeText(Welcome.this, "Token boş", Toast.LENGTH_SHORT).show();
                return;
            }

            Request request = new Request.Builder()
                    .url(CF_GET_PRIORITY)
                    .addHeader("Authorization", "Bearer " + idToken)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("HTTP-ERROR", "İstek başarısız: " + e.getMessage());
                    runOnUiThread(() -> Toast.makeText(
                            Welcome.this,
                            "Sunucuya bağlanılamadı",
                            Toast.LENGTH_SHORT
                    ).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String body = response.body().string();
                    Log.d("HTTP-RESPONSE", "Response: " + body);

                    runOnUiThread(() -> {
                        if (!response.isSuccessful()) {
                            Toast.makeText(
                                    Welcome.this,
                                    "Sunucu hatası: " + response.code(),
                                    Toast.LENGTH_SHORT
                            ).show();
                            return;
                        }
                        parsePriorityPets(body);
                    });
                }
            });
        });
    }

    /**
     * getPriorityPets'ten dönen JSON'u ayrıştırır.
     */
    private void parsePriorityPets(String json) {
        try {
            JSONObject root   = new JSONObject(json);
            // check success flag
            if (!root.optBoolean("success",false)) {
                Toast.makeText(this, root.optString("message","Sunucu hatası"), Toast.LENGTH_SHORT).show();
                return;
            }
            // grab the new key
                    JSONArray arr     = root.optJSONArray("pets");
            companions.clear();

            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.getJSONObject(i);

                String name          = item.optString("name");
                String species       = item.optString("species");
                String breed         = item.optString("breed");
                String health        = item.optString("health");
                String foundDate     = item.optString("foundDate");
                String foundLocation = item.optString("foundLocation");
                // Cloud function now returns "imageUrl"
                String imageResId    = item.optString("imageUrl");
                // there is no longer a finderName in this payload
                String finderName    = "";
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
                companions.add(pc);
            }
            runOnUiThread(adapter::notifyDataSetChanged);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}