package com.kurmez.iyesi.umay;



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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Soul;
import com.kurmez.iyesi.kurmes.social.content.Explore;
import com.kurmez.iyesi.kurmes.social.content.ExplorePrivate;
import com.kurmez.iyesi.kurmes.social.message.Messaging;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;

import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public class Welcome extends AppCompatActivity {
    private static final String TAG = "WelcomeActivity";
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-a651a.cloudfunctions.net/getPriorityPets";
    private CFHelper cf;
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
            Helpers.showToastSafe(this, "İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin.");
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
        // CFHelper kurulumu (proje id’nizi kullanın)
        cf = new CFHelper(this, "iyesi-a651a", new CFHelper.Listener(){
            @Override public void onPriorityPets(@NonNull java.util.List<Soul> pets) {
                // UI’yi ana threade taşı
                        runOnUiThread(() -> {
                            companions.clear();
                            companions.addAll(pets);
                            adapter.notifyDataSetChanged();
                        });
            }
            @Override public void onCallFailed(@NonNull String api, @NonNull Throwable err) {
                runOnUiThread(() ->
                        Helpers.showToastSafe(Welcome.this, "Sunucuya bağlanılamadı")
                );
            }
        });
        // (opsiyonel) rolü önceden çekip cache’e yazalım
        cf.refreshRole();
        // Veri çek
        cf.fetchPriorityPets();
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
            Intent intent = new Intent(Welcome.this, com.kurmez.iyesi.umay.sahiplendirme.Companion.class);
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
}