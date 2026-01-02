// app/src/main/java/com/kurmez/iyesi/kurmes/social/content/Explore.java
package com.kurmez.iyesi.kurmes.social.content;

import static com.kurmez.iyesi.kayra.AppCheckTokenProvider.runMembershipGuard;
import static com.kurmez.iyesi.kayra.Classes.Souls.Soul.parseSouls;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Iye;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;

import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;
import com.kurmez.iyesi.kurmes.utilities.clients.CFClient;
import com.kurmez.iyesi.umay.sahiplendirme.Companion;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.Request;

public class Explore extends AppCompatActivity {
    private static final String TAG = "ExploreActivity";

    // Erişim yetkili roller
    List<String> allowedRoles = Arrays.asList("İye", "Körmes", "Ülgen", "Tengri", "Ağaç");

    // Firebase
    private FirebaseAuth auth;
    private FirebaseUser user;
    private FirebaseFunctions functions;
    private FirebaseFirestore firestore;

    // Kullanıcı rolü
    private String userRole;

    // Kritik mod bayrağı
    private boolean criticalMode = false;
    private HeaderHelper headerHelper;
    // UI referansları
    private FrameLayout criticalRoot;
    private SwipeRefreshLayout swipeRefresh;
    private FrameLayout headerCard, pathRow;
    private ImageView ivAvatar, ivChevron, btnOverflow;
    private TextView tvUserName, tvPath, emptyView;
    private CheckBox cbNeedsCare;
    private ProgressBar progress;

    // Ana liste: ListView + CompanionAdapter
    private ListView listView;

    // Veri kaynakları
    private final List<Soul> companions = new ArrayList<>();
    private final List<Content> contentList = new ArrayList<>(); // (opsiyonel: başka akışlar için)
    private CompanionAdapter companionAdapter; // ListView adaptörü (Base/Array)

    // Ağ/CF
    private CFClient cf;
    private ExecutorService io;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore);
        headerHelper  = new HeaderHelper(Explore.this);
        // 1) Auth kontrolü
        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();
        if (user == null || user.isAnonymous()) {
            Helpers.showToastSafe(this, "Lütfen önce giriş yapın.");
            finish();
            return;
        }
        //headerHelper.refreshHeader(Explore.this,null);
headerHelper.refreshHeaderWithIye(null, new Iye());
        // 2) Firestore init (profil/rol vb.)
        firestore = FirebaseFirestore.getInstance();
        this.cf = new CFClient(BuildConfig.CF_BASE_URL);

        // 3) UI bind
        listView = findViewById(R.id.recycler_explore); // XML’de ListView olmalı
        emptyView = findViewById(R.id.tv_empty);
        progress = findViewById(R.id.progress);

        // ListView boş görünümü bağla (aynı parent altında olmalı)
        if (listView != null && emptyView != null) {
            listView.setEmptyView(emptyView);
        }

        // CompanionAdapter: ListView sürümü (Context + List<Soul>)
        companionAdapter = new CompanionAdapter(this, companions);
        listView.setAdapter(companionAdapter);

        // Profil header tıklaması (opsiyonel)
        View profileHeader = findViewById(R.id.profile_header);
        if (profileHeader != null) {
            profileHeader.setOnClickListener(v -> {
                try {
                    // Not: Bu Intent çalışmayabilir; gerçek bir Profile Activity yoksa kaldırılabilir.
                    startActivity(new Intent(this, ContactsContract.Profile.class));
                } catch (Throwable ignored) {
                }
            });
        }

        // 4) Tokenları al ve rol/kayıtları yükle
        cf.getTokens((idTok, appTok) -> {
            // Örnek test endpoint (gerekmiyorsa kaldırılabilir)
            String url = "https://us-central1-iyesi-aef03.cloudfunctions.net/listSoulsByFields?col=Souls&where=status:eq:adoptable&limit=3";
            Request.Builder rb = new Request.Builder().url(url).get()
                    .addHeader("Authorization", "Bearer " + idTok);
            if (appTok != null && !appTok.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appTok);
            }

            // Rol doğrulama + veri çekme
/*            Helpers.getRoleFunction()
                    .addOnSuccessListener(role -> {
                        if (role == null) {
                            Helpers.showToastSafe(this, "Rol atanmadı!");
                            finish();
                            return;
                        }
                        userRole = role;
                        if (!allowedRoles.contains(role)) {
                            Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        functions = FirebaseFunctions.getInstance();
                        fetchSouls(); // ilk yükleme
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Rol sorgusu hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
*/
            // Header menü/refresh
            Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_explore_options, item -> {
                int id = item.getItemId();
                if (id == R.id.action_refresh) {
                    if (criticalMode) {
                        // future: refreshCritical(true);
                        refresh(true);
                    } else {
                        refresh(true);
                    }
                    return true;
                }
                if (id == R.id.action_toggle_critical) {
                    criticalMode = !criticalMode;
                    if (criticalMode) {
                        // future: enterCriticalMode();
                    } else {
                        // future: exitCriticalMode();
                    }
                    return true;
                }
                return false;
            });
        }, e -> Log.e(TAG, "token fail", e));
        // 👇 Tıklanabilirlik burada eklendi
        listView.setOnItemClickListener((parent, view, position, id) -> {
            Soul soul = companions.get(position);
            Intent intent = new Intent(this, Companion.class);
            intent.putExtra(Companion.EXTRA_SPECIES, soul.getSpecies());
            intent.putExtra(Companion.EXTRA_BREED, soul.getBreed());
            intent.putExtra(Companion.EXTRA_FOUNDDATE, soul.getFoundDate());
            // Diğer field'lar gerekiyorsa buraya ekleyebilirsin (örneğin soulId, imageUrl)
            startActivity(intent);
        });
        runMembershipGuard(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (criticalMode) {
            this.cf = new CFClient(BuildConfig.CF_BASE_URL);
            Log.i(TAG, "CF base=" + BuildConfig.CF_BASE_URL + " (GET listSoulsByFields, health=critical)");
        }
        bindUser("KullanıcıAdı", null);
        refresh(false);
    }

    /** health="critical" sabit, needsCare opsiyonel (cbNeedsCare). Limit=20. */
    private void fetchSouls() {
        setLoading(true);

        final boolean needsCare = cbNeedsCare != null && cbNeedsCare.isChecked();
        final CFClient.WhereBuilder wb = new CFClient.WhereBuilder().eq("health", "critical");
        if (needsCare) wb.eq("needsCare", "true");

        ensureIo();
        io.execute(() -> cf.listSoulsByFields(wb, 20, new CFClient.JsonCallback() {

            private void logChunked(String prefix, String text) {
                if (text == null) { Log.d(TAG, prefix + " <null>"); return; }
                final int MAX = 1000;
                for (int i = 0; i < text.length(); i += MAX) {
                    Log.d(TAG, prefix + " " + text.substring(i, Math.min(i + MAX, text.length())));
                }
            }

            @Override
            public void onSuccess(@NonNull JSONObject json) {
                try {
                    String pretty;
                    try { pretty = json.toString(2); } catch (Exception e) { pretty = json.toString(); }
                    //logChunked("raw json:", pretty);

                    // Souls'u parse et
                    List<Soul> parsed = parseSouls(json);
                    if (parsed == null) parsed = java.util.Collections.emptyList();
                    Log.d(TAG, "parsed.size=" + parsed.size());

                    if (parsed.isEmpty()) {
                        String keys = (json.names() != null) ? json.names().toString() : "<no-keys>";
                        Log.w(TAG, "Empty parsed. Keys=" + keys);
                        int dataLen = json.optJSONArray("data") != null ? json.optJSONArray("data").length() : -1;
                        int itemsLen = json.optJSONArray("items") != null ? json.optJSONArray("items").length() : -1;
                        int soulsLen = json.optJSONArray("souls") != null ? json.optJSONArray("Souls").length() : -1;
                        Log.w(TAG, "ok=" + json.optBoolean("ok")
                                + " total=" + json.optInt("total", -1)
                                + " data.length=" + dataLen
                                + " items.length=" + itemsLen
                                + " Souls.length=" + soulsLen);

                        org.json.JSONArray probe = json.optJSONArray("data");
                        if (probe == null) probe = json.optJSONArray("items");
                        if (probe == null) probe = json.optJSONArray("souls");
                        if (probe != null && probe.length() > 0) {
                            org.json.JSONObject first = probe.optJSONObject(0);
                            Log.d(TAG, "first item probe=" + (first != null ? first.toString() : "null"));
                        }
                        showToast("Boş liste döndü");
                    }

                    final List<Soul> finalParsed = parsed;
                    runOnUiThread(() -> {
                        companions.clear();
                        companions.addAll(finalParsed);
                        Log.d(TAG, "UI companions.size=" + companions.size());
                        companionAdapter.notifyDataSetChanged();
                        setLoading(false);
                        renderEmptyState();
                    });

                } catch (Throwable e) {
                    Log.e(TAG, "parse error", e);
                    showToast("Veri çözümlenirken hata.");
                    runOnUiThread(() -> {
                        setLoading(false);
                        renderEmptyState();
                    });
                }
            }

            @Override
            public void onError(@NonNull Throwable t) {
                Log.e(TAG, "listSoulsByFields", t);
                showToast("Veri alınamadı: " + t.getMessage());
                runOnUiThread(() -> {
                    setLoading(false);
                    renderEmptyState();
                });
            }
        }));
    }

    private void bindUser(String name, android.graphics.Bitmap avatarBmp) {
        if (tvUserName != null && name != null && !name.isEmpty()) tvUserName.setText(name);
        if (ivAvatar != null && avatarBmp != null) ivAvatar.setImageBitmap(avatarBmp);
    }

    private void ensureIo() {
        if (io == null || io.isShutdown()) io = Executors.newFixedThreadPool(2);
    }

    private void setLoading(boolean state) {
        runOnUiThread(() -> {
            if (progress == null) return;
            if (state && companions.isEmpty()) {
                progress.setVisibility(View.VISIBLE);
            } else {
                progress.setVisibility(View.GONE);
                if (swipeRefresh != null) swipeRefresh.setRefreshing(false);
            }
        });
    }

    private void renderEmptyState() {
        // Boşluk kontrolü COMPANIONS üzerinden yapılmalı
        boolean empty = companions.isEmpty();
        if (emptyView != null) emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (listView != null) listView.setVisibility(empty ? View.GONE : View.VISIBLE);

        // ListView için emptyView zaten set edildi; parent hiyerarşisi uygunsa otomatik çalışır.
        // Bu görünürlük yönetimi, parent hiyerarşisi uygun değilse fallback olarak kalır.
    }

    private void showToast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static int dp(Context c, int d) {
        float den = c.getResources().getDisplayMetrics().density;
        return Math.round(d * den);
    }

    // (Opsiyonel) Public Completed Posts örnek akışı — ayrı adapter varsa orayı kullan
    private void fetchPublicCompletedPosts() {
        if (functions == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("onlyCompleted", true);
        payload.put("onlyPublic", true);

        functions.getHttpsCallable("getPosts")
                .call(payload)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "getPosts failed", task.getException());
                        Toast.makeText(this, "Gönderiler yüklenemedi.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    HttpsCallableResult result = task.getResult();
                    parsePosts(result.getData());
                });
    }

    @SuppressWarnings("unchecked")
    private void parsePosts(Object data) {
        contentList.clear();
        try {
            Map<String, Object> resultMap = (Map<String, Object>) data;
            List<Map<String, Object>> posts = (List<Map<String, Object>>) resultMap.get("posts");
            if (posts != null) {
                for (Map<String, Object> post : posts) {
                    Boolean isPublic = (Boolean) post.get("isPublic");
                    String status = (String) post.get("status");
                    if (!Boolean.TRUE.equals(isPublic) || !"completed".equalsIgnoreCase(status)) continue;

                    String mediaUrl = post.get("mediaUrl") != null ? (String) post.get("mediaUrl") : "";
                    String contentText = post.get("content") != null ? (String) post.get("content") : "";
                    String ownerId = post.get("ownerId") != null ? (String) post.get("ownerId") : "";
                    String displayText = contentText + "\nKayıt sahibi: " + ownerId;

                    Content item = new Content(mediaUrl, displayText, 0, 0, false);
                    contentList.add(item);
                }
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "parsePosts hatası", e);
        }

        // Bu ekran companions’ı listeliyor; contentList ayrı bir akışsa ayrı adapter kullanın.
        // Şimdilik yalnızca companions’ın adapter’ını güncellemek yeterli.
        companionAdapter.notifyDataSetChanged();
    }

    private GradientDrawable roundedBg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(this, (int) radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
        return d;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) io.shutdownNow();
        if (listView != null) listView.setAdapter(null);
    }

    // =============================================================================================
    // Data flow
    // =============================================================================================
    private void refresh(boolean fromUser) {
        if (fromUser) showToast("Yenileniyor…");
        companions.clear();
        contentList.clear();
        companionAdapter.notifyDataSetChanged();
        fetchSouls();
    }

    // ----------------------------------------------------------------------
    // Placeholder Content sınıfı (eğer başka bir yerde tanımlı değilse)
    // ----------------------------------------------------------------------
    public static class Content {
        public final String mediaUrl;
        public final String text;
        public final int likeCount;
        public final int commentCount;
        public final boolean isPinned;

        public Content(String mediaUrl, String text, int likeCount, int commentCount, boolean isPinned) {
            this.mediaUrl = mediaUrl;
            this.text = text;
            this.likeCount = likeCount;
            this.commentCount = commentCount;
            this.isPinned = isPinned;
        }
    }
}
