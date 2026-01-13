package com.kurmez.iyesi.umay;

import static com.kurmez.iyesi.kayra.AppCheckTokenProvider.runMembershipGuard;
import static com.kurmez.iyesi.kayra.Classes.Souls.Soul.parseSouls;
import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.getCustomClaims;

import com.google.android.gms.tasks.Tasks;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul; // tek ve doğru Soul
import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;


import android.Manifest;
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

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.social.content.Explore;
import com.kurmez.iyesi.kurmes.social.message.Messaging;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;
import com.kurmez.iyesi.kurmes.utilities.clients.CFClient;
import com.kurmez.iyesi.kurmes.utilities.helper.net.FirebaseAuthenticator;
import com.kurmez.iyesi.kurmes.utilities.helper.net.FirebaseHeadersInterceptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

public class Welcome extends AppCompatActivity {
    private static final String TAG = "WelcomeActivity";
    private static final String CF_GET_PRIORITY = "https://us-central1-iyesi-aef03.cloudfunctions.net/getPriorityPets";
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
            }).build();
    private CFClient cf = new CFClient("https://us-central1-iyesi-aef03.cloudfunctions.net");
    private String claimsJson;
    private FirebaseAuth mAuth;
    private FirebaseUser user;
    private String role;

    // QR extras (QrRouteResolver)
    private static final String EXTRA_QR_KIND      = "qr_kind";      // "baksi"
    private static final String EXTRA_QR_COUNTRY   = "qr_country";   // "TR"
    private static final String EXTRA_QR_CITY      = "qr_city";      // "ISTANBUL"
    private static final String EXTRA_QR_BAKSI_ID  = "qr_baksi_id";  // Firestore doc id

    public static String nz(String s) { return s == null ? "" : s; }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome_v2);

        // QR: Veteriner (Baksi) modu
        // Route: iyesi://baksi/{COUNTRY}/{CITY}/{BAKSI_ID}
        // TODO: "veteriner özelleri" ekran/feature seti burada netleştirilip aktive edilecek.
        try {
            Intent it = getIntent();
            if (it != null) {
                boolean vetMode = it.getBooleanExtra("vetMode", false);
                String kind = it.getStringExtra(EXTRA_QR_KIND);
                if (vetMode || "baksi".equalsIgnoreCase(kind)) {
                    String country = it.getStringExtra(EXTRA_QR_COUNTRY);
                    String city = it.getStringExtra(EXTRA_QR_CITY);
                    String baksiId = it.getStringExtra(EXTRA_QR_BAKSI_ID);
                    Log.i(TAG, "QR Baksi: vetMode=" + vetMode + " country=" + country + " city=" + city + " baksiId=" + baksiId);
                    Helpers.showToastSafe(this, "Veteriner modu aktif (QR)"); // geçici geri bildirim
                    // TODO: CF /baksiDetails ile tekil baksi çek + UI/flow aktive et.
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "QR Baksi extras parse failed", t);
        }

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
        // ÖNCE: Hardcoded "İnternet bağlantısı yok. Lütfen bağlantınızı kontrol edin."
        // ŞİMDİ: String resource kullanımı
        if (netInfo == null || !netInfo.isConnected()) {Helpers.showToastSafe(this, getString(R.string.welcome_toast_no_internet));}
        // Login kontrolü
        mAuth = FirebaseAuth.getInstance();
        user = mAuth.getCurrentUser();
        if (user == null || user.isAnonymous()) {
            // ÖNCE: Hardcoded "Devam etmek için giriş yapmalısınız."
            // ŞİMDİ: String resource kullanımı - Message.java ile aynı string resource
            Toast.makeText(this, getString(R.string.message_toast_login_required), Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        } else {
            new Thread(() -> {
                Log.i("ThreadBaşladı", "Role: ... ");
                try {
                    // ID token'ı al ve claims'leri logla
                    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                    if (user != null) {
                        idToken = Tasks.await(user.getIdToken(true)).getToken();
                        String claimsJson = CFHelper.getCustomClaims(idToken);
                        Log.d("CustomClaimsRaw", "Raw claims: " + claimsJson);
                        // JSON parse et (opsiyonel)
                        if (claimsJson != null) {
                            JSONObject claims = new JSONObject(claimsJson);
                            role = claims.optString("role", "unknown");
                            JSONArray roles = claims.optJSONArray("roles");
                            Log.d("CustomClaimsParsed", "Role: " + role + ", Roles: " + (roles != null ? roles.toString() : "null"));
                        }
                    }
                    // Mevcut rol kontrolü
                    cf.refreshRole(role -> {
                        Log.i("CustomClaims", "Role: " + role);
                        this.role = role;
                        // ÖNCE: Hardcoded "Ülgen Yada Tanrı" ve "Bu işlemi sadece Ülgen ve Tengri yapabilir."
                        // ŞİMDİ: String resource kullanımı - "Bu işlemi sadece Ülgen ve Tengri yapabilir." Messaging.java ile aynı
                        if (Objects.equals(role, "Ülgen") || Objects.equals(role, "Tengri")) {
                            Toast.makeText(this, getString(R.string.welcome_toast_ulgen_or_tengri), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(this, getString(R.string.messaging_toast_admin_only), Toast.LENGTH_LONG).show();
                        }
                        // Refresh the ID token to get updated claims
                        //user = FirebaseAuth.getInstance().getCurrentUser();
                        if (user != null) {
                            user.getIdToken(true).addOnSuccessListener(tokenResult -> {
                                idToken = tokenResult.getToken(); // Update idToken with the new value
                                runOnUiThread(() -> {
                                    // Use the refreshed role and updated token data
                                    username.setText(role + ":" + user.getEmail() + "\n" + user.getUid());
                                    Log.v("Claims",getCustomClaims(idToken));
                                });
                            }).addOnFailureListener(e -> {
                                Log.e("TokenRefresh", "Failed to refresh token", e);
                                runOnUiThread(() -> {
                                    // Fallback to the role from refreshRole if token refresh fails
                                    //username.setText(role + ":" + user.getEmail() + "\n" + user.getUid() + "\n" + getCustomClaims(idToken));
                                });
                            });
                        }
                    },this);
                } catch (Exception e) {
                    Log.e("Messaging", "Token/Claims fetch failed", e);
                }
            }).start();
            //username.setText(role + ":" + claimsJson + user.getEmail() + user.getUid());
        }

        CFClient.WhereBuilder wb = new CFClient.WhereBuilder().eq("status", "adoptable");

        cf.getTokens((idTok, appTok) -> {
            String url = "https://us-central1-iyesi-aef03.cloudfunctions.net/listSoulsByFields?col=Souls&where=status:eq:adoptable&limit=3";
            Request.Builder rb = new Request.Builder().url(url).get()
                    .addHeader("Authorization", "Bearer " + idTok);
            if (appTok != null && !appTok.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appTok);
            }
            cf.listSoulsByFields(wb, 100, new CFClient.JsonCallback() {
                private static final String TAG = "CF";

                private void logChunked(String prefix, String text) {
                    if (text == null) { Log.d(TAG, prefix + " <null>"); return; }
                    final int MAX = 1000;
                    for (int i = 0; i < text.length(); i += MAX) {
                        Log.d(TAG, prefix + " " + text.substring(i, Math.min(i + MAX, text.length())));
                    }
                }
                @Override public void onSuccess(@NonNull JSONObject json) {
                    // Pretty + chunked
                    String pretty;
                    try {
                        pretty = json.toString(2);
                    } catch (Exception e) {          // JSONException veya herhangi bir checked/unchecked
                        pretty = json.toString();    // indent olmadan düz string
                    }
                    //logChunked("raw json:", pretty);

                    List<Soul> parsed = parseSouls(json);
                    if (parsed == null) parsed = Collections.emptyList();
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
                        JSONArray probe = json.optJSONArray("data");
                        if (probe == null) probe = json.optJSONArray("items");
                        if (probe == null) probe = json.optJSONArray("souls");
                        if (probe != null && probe.length() > 0) {
                            JSONObject first = probe.optJSONObject(0);
                            Log.d(TAG, "first item probe=" + (first != null ? first.toString() : "null"));
                        }

                        // ÖNCE: Hardcoded "Boş liste döndü"
                        // ŞİMDİ: String resource kullanımı
                        Toast.makeText(Welcome.this, getString(R.string.welcome_toast_empty_list), Toast.LENGTH_SHORT).show();
                    }

                    companions.clear();
                    companions.addAll(parsed);
                    adapter.notifyDataSetChanged();
                }

                @Override public void onError(@NonNull Throwable t) {
                    Log.e(TAG, "listSoulsByFields", t);
                    // ÖNCE: Hardcoded "Veri alınamadı: " + t.getMessage()
                    // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                    String errorMsg = t.getMessage() == null ? "-" : t.getMessage();
                    Toast.makeText(Welcome.this, getString(R.string.welcome_toast_data_fetch_error, errorMsg), Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }, e -> Log.e("TOK", "token fail", e));
        runMembershipGuard(this);
    }
    private void setupUIListeners() {
        imgWelcome.setOnClickListener(v -> {startActivity(new Intent(this, Explore.class));});
        imgWelcome.setOnLongClickListener(v -> {startActivity(new Intent(this, SokakActivity.class));finish();return true;});
        quitButton.setOnClickListener(v -> {FirebaseAuth.getInstance().signOut();finish();});
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= companions.size()) {
                Log.w(TAG, "onItemClick: bad position=" + position + " size=" + companions.size());
                return;
            }

            // (isteğe bağlı) double-click debounce
            view.setEnabled(false);
            view.postDelayed(() -> view.setEnabled(true), 600);

            Soul s = companions.get(position);
            // ÖNCE: Hardcoded "Selected: " + name
            // ŞİMDİ: String resource kullanımı - format string ile name parametresi
            Toast.makeText(this, getString(R.string.welcome_toast_item_selected, nz(s.getName())), Toast.LENGTH_SHORT).show();

            Intent intent = new Intent(Welcome.this, com.kurmez.iyesi.umay.sahiplendirme.Companion.class);
            // ⚠️ DÜZELTİLENLER:
            intent.putExtra("species",    nz(s.getSpecies()));          // önce breed gönderiliyordu
            intent.putExtra("breed",      nz(s.getBreed()));            // breed’i ayrıca yolla
            intent.putExtra("foundDate",  nz(s.getFoundDate()));
            intent.putExtra("foundPlace", nz(s.getFoundLocation()));    // model alanıyla uyumlu
            intent.putExtra("photoUrl",   nz(s.getImageUrl()));
            intent.putExtra("profileId",  nz(s.getFinderName()));
            intent.putExtra("status",     nz(s.getStatus()));
            intent.putExtra("id",         nz(s.getId()));               // detay ekranı için faydalı

            startActivity(intent);
        });
        messageButton.setOnClickListener(v -> startActivity(new Intent(this, Messaging.class)));
        // ÖNCE: Hardcoded "Bildirim özelliği yakında gelecek"
        // ŞİMDİ: String resource kullanımı
        notificationButton.setOnClickListener(v -> Helpers.showToastSafe(this, getString(R.string.welcome_toast_notification_coming_soon)));
    }
}