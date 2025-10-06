package com.kurmez.iyesi.umay;

import static com.kurmez.iyesi.kayra.AppCheckTokenProvider.runMembershipGuard;
import static com.kurmez.iyesi.kayra.Classes.data.Soul.parseSouls;

import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kayra.Classes.data.Soul; // tek ve doğru Soul
import androidx.annotation.NonNull;

import java.io.IOException;
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

import androidx.annotation.Nullable;
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
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;
import com.kurmez.iyesi.kurmes.utilities.helper.net.FirebaseAuthenticator;
import com.kurmez.iyesi.kurmes.utilities.helper.net.FirebaseHeadersInterceptor;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Welcome extends AppCompatActivity {
    private static final String TAG = "WelcomeActivity";
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/getPriorityPets";
    //private CFHelper cf;
    private ImageView imgWelcome;
    private ImageButton quitButton;
    private ImageButton messageButton;
    private ImageButton notificationButton;
    private TextView username;
    private ListView listView;
    private String idToken;
    private CompanionAdapter adapter;
    private final List<Soul> companions = new ArrayList<>();
    private final OkHttpClient httpClient =new OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .authenticator(new FirebaseAuthenticator())
            .addInterceptor(new FirebaseHeadersInterceptor()) // <-- ID + AppCheck header’larını ekleyen kısım
            .addInterceptor(chain -> { // LOG için geçici
                Request req = chain.request();
                android.util.Log.d("HTTP", req.method()+" "+req.url());
                android.util.Log.d("HTTP", "Authorization: " + req.header("Authorization"));
                android.util.Log.d("HTTP", "X-Firebase-AppCheck: " + req.header("X-Firebase-AppCheck"));
                return chain.proceed(req);
            })
            .build();
    private CFClient cf = new CFClient("https://us-central1-iyesi-e8d4f.cloudfunctions.net");
    public static String nz(String s) { return s == null ? "" : s; }


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
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        } else {
            username.setText(user.getEmail());
        }

        // CFHelper


        CFClient.WhereBuilder wb = new CFClient.WhereBuilder().eq("status", "adoptable");


        cf.getTokens((idTok, appTok) -> {
            String url = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/listSoulsByFields?col=Souls&where=status:eq:adoptable&limit=3";
            Request.Builder rb = new Request.Builder().url(url).get()
                    .addHeader("Authorization", "Bearer " + idTok);
            if (appTok != null && !appTok.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appTok);
            }
// YENİ (DOĞRU - async)
            cf.listSoulsByFields(wb, 100, new CFClient.JsonCallback() {
                private static final String TAG = "CF";

                private void logChunked(String prefix, String text) {
                    if (text == null) { Log.d(TAG, prefix + " <null>"); return; }
                    final int MAX = 1000;
                    for (int i = 0; i < text.length(); i += MAX) {
                        Log.d(TAG, prefix + " " + text.substring(i, Math.min(i + MAX, text.length())));
                    }
                }

                @Override public void onSuccess(@NonNull org.json.JSONObject json) {
                    // Pretty + chunked
                    String pretty;
                    try {
                        pretty = json.toString(2);
                    } catch (Exception e) {          // JSONException veya herhangi bir checked/unchecked
                        pretty = json.toString();    // indent olmadan düz string
                    }
                    logChunked("raw json:", pretty);

                    List<Soul> parsed = parseSouls(json);
                    if (parsed == null) parsed = java.util.Collections.emptyList();
                    Log.d(TAG, "parsed.size=" + parsed.size());

                    if (parsed.isEmpty()) {
                        // Güvenli key yazdır
                        String keys = (json.names() != null) ? json.names().toString() : "<no-keys>";
                        Log.w(TAG, "Empty parsed. Keys=" + keys);

                        // Sık şema propları
                        int dataLen = json.optJSONArray("data") != null ? json.optJSONArray("data").length() : -1;
                        int itemsLen = json.optJSONArray("items") != null ? json.optJSONArray("items").length() : -1;
                        int soulsLen = json.optJSONArray("souls") != null ? json.optJSONArray("Souls").length() : -1;
                        Log.w(TAG, "ok=" + json.optBoolean("ok")
                                + " total=" + json.optInt("total", -1)
                                + " data.length=" + dataLen
                                + " items.length=" + itemsLen
                                + " Souls.length=" + soulsLen);

                        // İlk eleman probesi
                        org.json.JSONArray probe = json.optJSONArray("data");
                        if (probe == null) probe = json.optJSONArray("items");
                        if (probe == null) probe = json.optJSONArray("souls");
                        if (probe != null && probe.length() > 0) {
                            org.json.JSONObject first = probe.optJSONObject(0);
                            Log.d(TAG, "first item probe=" + (first != null ? first.toString() : "null"));
                        }

                        Toast.makeText(Welcome.this, "Boş liste döndü", Toast.LENGTH_SHORT).show();
                    }

                    companions.clear();
                    companions.addAll(parsed);
                    adapter.notifyDataSetChanged();
                }

                @Override public void onError(@NonNull Throwable t) {
                    Log.e(TAG, "listSoulsByFields", t);
                    Toast.makeText(Welcome.this, "Veri alınamadı: " + t.getMessage(), Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }, e -> android.util.Log.e("TOK", "token fail", e));
        runMembershipGuard(this);
    }
    private void setupUIListeners() {
        imgWelcome.setOnClickListener(v -> {
            startActivity(new Intent(this, Explore.class));
        });

        imgWelcome.setOnLongClickListener(v -> {
            //if (Objects.equals(role, "Ülgen")) {
                startActivity(new Intent(this, SokakActivity.class));
                finish();
            //} else {
                //startActivity(new Intent(this, ExplorePrivate.class));
            //}
            return true;
        });

        quitButton.setOnClickListener(v -> {
            FirebaseAuth.getInstance().signOut();
            finish();
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= companions.size()) {
                Log.w(TAG, "onItemClick: bad position=" + position + " size=" + companions.size());
                return;
            }

            // (isteğe bağlı) double-click debounce
            view.setEnabled(false);
            view.postDelayed(() -> view.setEnabled(true), 600);

            Soul s = companions.get(position);
            Toast.makeText(this, "Selected: " + nz(s.getName()), Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(Welcome.this, com.kurmez.iyesi.umay.sahiplendirme.Companion.class);
            // ⚠️ DÜZELTİLENLER:
            intent.putExtra("species", nz(s.getSpecies()));          // önce breed gönderiliyordu
            intent.putExtra("breed",   nz(s.getBreed()));            // breed’i ayrıca yolla
            intent.putExtra("foundDate",  nz(s.getFoundDate()));
            intent.putExtra("foundPlace", nz(s.getFoundLocation())); // model alanıyla uyumlu
            intent.putExtra("photoUrl",   nz(s.getImageUrl()));
            intent.putExtra("profileId",  nz(s.getFinderName()));
            intent.putExtra("status",     nz(s.getStatus()));
            intent.putExtra("id",         nz(s.getId()));            // detay ekranı için faydalı

            startActivity(intent);
        });

        // Diğer butonlar için basit işlevler
        messageButton.setOnClickListener(v ->
                startActivity(new Intent(this, Messaging.class))
        );
        notificationButton.setOnClickListener(v -> Helpers.showToastSafe(this,"Bildirim özelliği yakında gelecek"));
    }

}