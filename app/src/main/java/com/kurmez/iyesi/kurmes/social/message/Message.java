package com.kurmez.iyesi.kurmes.social.message;

import android.content.Intent;
import android.os.Bundle;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;

import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;


import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Message extends AppCompatActivity {
    private FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
    private static final String GW_PREFIX = "/v1"; // basePath yoksa "" yap
    public static final String EXTRA_MODE = "mode"; // "bluetooth" veya "blockchain"
    public static final String MODE_BLUETOOTH = "bluetooth";
    public static final String MODE_BLOCKCHAIN = "blockchain";
    public static final String EXTRA_TARGET_USER_ID = "targetUserId";
    public static final String EXTRA_TARGET_USER_NAME = "targetUserName";
    // class alanları
    //private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String CF_URL = "https://us-central1-iyesi-aef03.cloudfunctions.net/appSend";
    private static final String BASE = "https://iye-gw-5bszr9sz.uc.gateway.dev"; // Gateway hostname
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static String gwUrl(String path){ return BASE + GW_PREFIX + path; }

    // (İsterseniz tekil (singleton) client tutun)
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
    /*
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build();*/
    private RecyclerView rvMessages;
    private EditText etMessage;
    private ImageButton btnSend;
    private MessageAdapter adapter;
    private List<MessageItem> messageList = new ArrayList<>();

    private String mode;
    private String targetUserId;
    private String targetUserName;
    private static boolean appCheckInFlight = false;
    private static String cachedAppCheckToken = null;
    private static long cachedAppCheckExpiry = 0L; // epoch ms
    private static final long SAFETY_MARGIN_MS = 60_000L; // 1 dk
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_messaging);
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        }

        // ➊ Intent’ten modu ve muhatabı al
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null) mode = MODE_BLOCKCHAIN;
        targetUserId   = getIntent().getStringExtra(EXTRA_TARGET_USER_ID);
        targetUserName = getIntent().getStringExtra(EXTRA_TARGET_USER_NAME);

        // ➋ Header’daki kullanıcı adını set et
        TextView headerName = findViewById(R.id.tvUsername);
        headerName.setText(targetUserName != null ? targetUserName : "Konuşma");
        // ➌ View’ları bağla
        rvMessages = findViewById(R.id.rvMessages);
        etMessage  = findViewById(R.id.etMessage);
        btnSend    = findViewById(R.id.btnSend);

        // ➍ RecyclerView & Adapter kurulumu
        rvMessages.setNestedScrollingEnabled(false);
        adapter = new MessageAdapter(messageList);
        rvMessages.setLayoutManager(new LinearLayoutManager(this));
        rvMessages.setAdapter(adapter);

        // ➎ Gönder butonu
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (text.isEmpty()) return;
            etMessage.setText("");

            // ➏ önce UI’a ekle
            MessageItem outgoing = new MessageItem(text, true /*giden*/);
            addMessage(outgoing);

            // ➐ ardından ilgili kanala yolla
            if (MODE_BLUETOOTH.equals(mode)) {
                sendBluetoothMessage(text);
            } else {
                user.getIdToken(false) // cache'i kullan; sorun olursa true ile tazele
                        .addOnSuccessListener(result -> {
                            String idToken = result.getToken();
                            sendBlockchainMessage(text);
                        })
                        .addOnFailureListener(e -> {
                            // İsterseniz idToken'sız da deneyebilirsiniz (sunucu zorunlu kılıyorsa çağırmayın)
                            // sendBlockchainMessage(user.getUid(), targetUserId, text, null);
                            Log.e("ID token alınamadı: " + e, Objects.requireNonNull(e.getMessage()));
                        });
            }
        });
        Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_messaging_options, item -> {
            int id = item.getItemId();

            if (id == R.id.action_block) {
                Toast.makeText(this, "Engellendi", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_mute) {
                Toast.makeText(this, "Sessize alındı", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_delete) {
                Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show();
                return true;
            } else if (id == R.id.action_follow) {
                Toast.makeText(this, "Takip işlemi", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        // TODO: Gelen mesajları dinleyip onMessageReceived(...) ile UI’a yansıt
    }

    /** Bluetooth ile mesaj gönderme stub’u */
    private void sendBluetoothMessage(String text) {
        PrivateCom.sendResponseToBluetoothDevice(this, text);
        // TODO: Bluetooth kanalından yolla,
        //       gelen cevabı onMessageReceived(...) ile al.
    }
    /**
     * App Check + (varsa) Firebase Auth ile yetkilendirilmiş istek atar.
     * Gerekirse anonim oturum açar ve ID token üretir.
*/
    private void sendBlockchainMessage(String text) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return;

        user.getIdToken(true).addOnSuccessListener(idTokenResult -> {
            String idToken = idTokenResult.getToken();

            FirebaseAppCheck.getInstance().getAppCheckToken(true)
                    .addOnSuccessListener(appCheckTokenResult -> {
                        String appCheckToken = appCheckTokenResult.getToken();
                        sendAuthenticatedRequest(idToken, appCheckToken, text);
                    })
                    .addOnFailureListener(e -> {
                        Log.e("APP_CHECK", "Token alınamadı", e);
                        Toast.makeText(Message.this,
                                "Güvenlik doğrulaması başarısız", Toast.LENGTH_SHORT).show();
                    });
        });
    }

    private void sendAuthenticatedRequest(String idToken, String appCheckToken, String text) {
        try {
            // Şifreleme verileri (gerçek uygulamada kripto işlemleri yapılmalı)
            JSONObject enc = new JSONObject();
            enc.put("ephPubKeyB64", Base64.encodeToString("public_key_placeholder".getBytes(), Base64.NO_WRAP));
            enc.put("nonceB64", Base64.encodeToString("nonce_value".getBytes(), Base64.NO_WRAP));
            enc.put("ciphertextB64", Base64.encodeToString(text.getBytes(), Base64.NO_WRAP));

            // Meta veriler (unique mesaj ID'si üret)
            String msgId = UUID.randomUUID().toString();
            JSONObject meta = new JSONObject();
            meta.put("msgId", msgId);
            meta.put("timestamp", System.currentTimeMillis());

            // Ana istek gövdesi
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("toUid", targetUserId);
            bodyJson.put("enc", enc);
            bodyJson.put("meta", meta);

            // HTTP isteğini oluştur
            sendViaGateway(idToken,appCheckToken,bodyJson);

        } catch (Exception e) {
            Log.e("REQUEST", "JSON oluşturma hatası", e);
            runOnUiThread(() -> Toast.makeText(Message.this,
                    "İstek hazırlama hatası: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }
    /**
     * Gateway üzerinden güvenli POST.
     * @param idToken Firebase ID token (kullanıcı)
     * @param appCheckToken App Check token (opsiyonel ama önerilir)
     * @param bodyJson Gövde (JSON)
     */
    private void sendViaGateway(String idToken, String appCheckToken, JSONObject bodyJson) {
        try {
            RequestBody body = RequestBody.create(bodyJson.toString(), JSON);

            Request request = new Request.Builder()
                    // OpenAPI'nizde basePath '/v1' ise "/v1/appSend" kullanın.
                    .url(gwUrl("/appSend"))
                    .url(BASE + "/appSend")
                    .post(body)

                    // 1) Gateway doğrulaması için: Authorization zorunlu
                    .addHeader("Authorization", "Bearer " + idToken)

                    // 2) Backend doğrulaması için: X-Firebase-Authorization
                    // (Gateway backend'e giderken Authorization'ı SA JWT'si ile değiştirebilir.)
                    .addHeader("X-Firebase-Authorization", "Bearer " + idToken)

                    // App Check (HTTP için standart header)
                    .addHeader("X-Firebase-AppCheck", appCheckToken != null ? appCheckToken : "")

                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json; charset=utf-8")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override public void onFailure(Call call, IOException e) {
                    // Burada UI thread'e post edip kullanıcıya gösterebilirsiniz
                    android.util.Log.e("HTTP", "Request failed", e);
                }

                @Override public void onResponse(Call call, Response response) throws IOException {
                    String respBody = response.body() != null ? response.body().string() : "";
                    android.util.Log.d("HTTP", "code=" + response.code() + " body=" + respBody);

                    if (!response.isSuccessful()) {
                        // 401 -> genelde Authorization header yok/yanlış
                        // 403 -> App Check, rol, kural vb.
                        // 404 -> path yanlışı (/v1/appSend vs /appSend)
                        // Hata akışınızı burada yönetin
                    } else {
                        // Başarılı yanıt akışı
                    }
                }
            });
        } catch (Exception e) {
            android.util.Log.e("HTTP", "Build/Send error", e);
        }
    }
    /** InputStream -> String */
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) baos.write(buf, 0, r);
            return baos.toString("UTF-8");
        }
    }


/*    private void sendBlockchainMessage(String text) {
        // TODO: Cloud Functions veya kendi chain’inize yolla,
        //       yanıtı onMessageReceived(...) ile al.
    }*/

    /** Gelen mesaj geldiğinde UI’a ekle */
    private void onMessageReceived(MessageItem incoming) {
        runOnUiThread(() -> addMessage(incoming));
    }

    /** Listeye bir item ekle ve aşağı kaydır */
    private void addMessage(MessageItem msg) {
        messageList.add(msg);
        adapter.notifyItemInserted(messageList.size() - 1);
        rvMessages.scrollToPosition(messageList.size() - 1);
    }

    // ——————————————————————————
    // Basit mesaj modeli
    static class MessageItem {
        String text;
        boolean isSent;   // true = giden, false = gelen
        // ileride timestamp, senderId vb. eklenebilir

        MessageItem(String text, boolean isSent) {
            this.text   = text;
            this.isSent = isSent;
        }
    }

    // ——————————————————————————
    // RecyclerView Adapter
    static class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.VH> {
        private final List<MessageItem> items;
        MessageAdapter(List<MessageItem> items) { this.items = items; }

        @Override
        public int getItemViewType(int position) {
            return items.get(position).isSent ? R.layout.item_message_outgoing : R.layout.item_message_incoming;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(viewType, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int pos) {
            holder.tv.setText(items.get(pos).text);
        }

        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tv;
            VH(View v) {
                super(v);
                tv = v.findViewById(R.id.tvMessage);
            }
        }
    }
}
