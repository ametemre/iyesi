package com.kurmez.iyesi.umay;

import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kayra.Classes.Soul; // tek ve doğru Soul
import androidx.annotation.NonNull;
import java.util.List;


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
import androidx.annotation.Nullable;
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
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/getPriorityPets";
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

        // UI bileşenleri
        imgWelcome = findViewById(R.id.img_welcome);
        quitButton = findViewById(R.id.quit);
        messageButton = findViewById(R.id.message);
        notificationButton = findViewById(R.id.notification);
        listView = findViewById(R.id.list_view);
        username = findViewById(R.id.username_validation);

        adapter = new CompanionAdapter(this, companions);
        listView.setAdapter(adapter);

        setupUIListeners();

        // İnternet kontrolü
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm != null ? cm.getActiveNetworkInfo() : null;
        if (netInfo == null || !netInfo.isConnected()) {
            Helpers.showToastSafe(this, "İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin.");
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

        // CFHelper

        cf = new CFHelper(
                    this /* context */,
                    "PROJECT_ID",        // örn: iyesi-a651a
                    "us-central1",       // bölge
                    new CFHelper.Listener<Soul>() {
                        @Override
                        public void onPriorityPets(@NonNull List<Soul> pets) {
                            // TODO: UI’ni güncelle
                        }
                        @Override
                        public void onCallFailed(@NonNull String apiName, @NonNull Throwable error) {
                            // TODO: hata göster
                        }
                        @Override
                        public void onRoleRefreshed(@Nullable String role) {
                            // opsiyonel
                        }
                    }
            );
            // opsiyonel ama önerilir: cihaz kimliğini header’a ekleyin
            //cf.setDeviceId(deviceIdString);

            // Rolü arka planda ve token’lar hazır olunca çek
            // cf.refreshRoleWhenReady();

            // Liste verilerini çek
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
            intent.putExtra("photoUrl", selectedCompanion.getImageUrl());
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