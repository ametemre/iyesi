// app/src/main/java/com/kurmez/iyesi/kurmes/social/content/Explore.java
package com.kurmez.iyesi.kurmes.social.content;

import static com.kurmez.iyesi.kayra.Classes.data.Soul.parseSouls;
import static com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper.getTokens;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
// Modern "messaging-like" swipe helper for ListView (drag-based, thresholded)
import android.graphics.Color;
import android.view.animation.DecelerateInterpolator;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;

import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;
import com.kurmez.iyesi.umay.sahiplendirme.Companion;

import org.json.JSONArray;
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
    public CFClient cf;
    public ExecutorService io;

    @SuppressLint("ClickableViewAccessibility")
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
        headerHelper.refreshHeader(Explore.this);

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

        // 4) Tokenları al ve rol/kayıtları yükle (örnek)
        getTokens((idTok, appTok) -> {
            // Örnek test endpoint (gerekmiyorsa kaldırılabilir)
            String url = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/listSoulsByFields?col=Souls&where=status:eq:adoptable&limit=3";
            Request.Builder rb = new Request.Builder().url(url).get()
                    .addHeader("Authorization", "Bearer " + idTok);
            if (appTok != null && !appTok.isEmpty()) {
                rb.addHeader("X-Firebase-AppCheck", appTok);
            }

            // Header menü/refresh
            Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_explore_options, item -> {
                int id = item.getItemId();
                if (id == R.id.action_refresh) {
                    refresh(true);
                    return true;
                }
                if (id == R.id.action_toggle_critical) {
                    criticalMode = !criticalMode;
                    // future: enter/exit critical mode
                    return true;
                }
                return false;
            });
        }, e -> Log.e(TAG, "token fail", e));

        // Item tıklaması → Companion detay
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= companions.size()) return true;
            Soul s = companions.get(position);
            Intent intent = new Intent(this, Companion.class);
            intent.putExtra(Companion.EXTRA_SPECIES, s.getSpecies());
            intent.putExtra(Companion.EXTRA_BREED, s.getBreed());
            intent.putExtra(Companion.EXTRA_FOUNDDATE, s.getFoundDate());
            // Gerekirse id/imageUrl vb. ekleyebilirsin
            startActivity(intent);
            return true;
        });

        // Sağ/sol kaydırma
        listView.setOnTouchListener(new SimpleSwipeHelper(this, listView, new SimpleSwipeHelper.Callback() {
            @Override public void onSwipedLeft(int position) {
                if (position < 0 || position >= companions.size()) return;
                Soul s = companions.get(position);
                Log.d(TAG, "sola kaydı: pos=" + position + " id=" + safeSoulId(s));
            }

            @Override public void onSwipedRight(int position) {
                if (position < 0 || position >= companions.size()) return;
                Soul s = companions.get(position);
                markAdoptableAndGood(s, position);
            }
        },null));
    }

    // Soul ID alanını sınıfına göre uyarlayın (id, docId, soulId vs.)
    private String safeSoulId(Soul s) {
        try {
            if (s.getId() != null) return s.getId();
        } catch (Throwable ignore) {}
        return null;
    }

    // Explore.java -> markAdoptableAndGood(...) gövdesini şu şekilde değiştir
    private void markAdoptableAndGood(Soul soul, int positionInList) {
        String id = safeSoulId(soul);
        if (id == null) { Helpers.showToastSafe(Explore.this,"Kayıt ID bulunamadı."); return; }

        setLoading(true);
        ensureIo();
        io.execute(() -> {
            try {
                // /updateSoulById?id=... endpoint'ine PATCH
                org.json.JSONObject body = new org.json.JSONObject()
                        .put("status", "adoptable")
                        .put("health", "good");

                // Base URL CFClient içinde zaten ayarlı: new CFClient(BuildConfig.CF_BASE_URL)
                org.json.JSONObject resp = cf.patchJson("/updateSoulById?id=" + id, body);

                runOnUiThread(() -> {
                    // Listeden kaldır ve UI'ı güncelle
                    if (positionInList >= 0 && positionInList < companions.size()) {
                        companions.remove(positionInList);
                    } else {
                        for (int i = 0; i < companions.size(); i++) {
                            String sid = safeSoulId(companions.get(i));
                            if (id.equals(sid)) { companions.remove(i); break; }
                        }
                    }
                    companionAdapter.notifyDataSetChanged();
                    renderEmptyState();
                    setLoading(false);
                });
            } catch (Throwable t) {
                Log.e(TAG, "CF update failed", t);
                runOnUiThread(() -> {
                    Helpers.showToastSafe(Explore.this,"Güncelleme başarısız: " + t.getMessage());
                    setLoading(false);
                });
            }
        });
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



    private void bindUser(String name, android.graphics.Bitmap avatarBmp) {
        if (tvUserName != null && name != null && !name.isEmpty()) tvUserName.setText(name);
        if (ivAvatar != null && avatarBmp != null) ivAvatar.setImageBitmap(avatarBmp);
    }

    public void ensureIo() {
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
    // Sınıf alanları:


    private void ensureCf() {
        if (cf == null) {
            cf = new CFClient(BuildConfig.CF_BASE_URL);
            Log.d(TAG, "CFClient init (lazy)");
        }
    }

    private void refresh(boolean fromUser) {
        if (fromUser) Helpers.showToastSafe(this,"Yenileniyor…");
        companions.clear();
        contentList.clear();
        companionAdapter.notifyDataSetChanged();
        //fetchSouls(null);//fetchSouls("health","critical");
    }
    /** health="critical" sabit, needsCare opsiyonel (cbNeedsCare). Limit=220.  */
    public void fetchSouls(@Nullable String myAdminPath, @NonNull SoulsJsonCallback cb) {
        ensureCf();
        try {
            setLoading(true);
            if (myAdminPath == null) myAdminPath = "TR/Yalova";
            Log.i(TAG, "myAdminPath=" + myAdminPath);

            final boolean needsCare = cbNeedsCare != null && cbNeedsCare.isChecked();
            final CFClient.WhereBuilder wb = new CFClient.WhereBuilder().eq("adminPath", myAdminPath);
            if (needsCare) wb.eq("needsCare", "true"); // alan boolean ise true verin

            ensureIo();
            io.execute(() -> {
                ensureCf();
                cf.listSoulsByFields(wb, 220, new CFClient.JsonCallback() {
                    @Override public void onSuccess(@NonNull JSONObject json) {
                        try {
                            // 1) Diziyi güvenle yakala (items → data → souls)
                            JSONArray items = json.optJSONArray("items");
                            if (items == null) items = json.optJSONArray("data");
                            if (items == null) items = json.optJSONArray("souls"); // NOT: "Souls" değil "souls"

                            if (items == null) items = new JSONArray();

                            // 2) Mevcut parse akışınızı koruyun
                            List<Soul> parsed = parseSouls(json);
                            if (parsed == null) parsed = java.util.Collections.emptyList();
                            Log.d(TAG, "parsed.size=" + parsed.size());

                            // 3) UI güncelle
                            final List<Soul> finalParsed = parsed;
                            final JSONArray finalItems = new JSONArray(items.toString()); // defensif kopya
                            runOnUiThread(() -> {
                                companions.clear();
                                companions.addAll(finalParsed);
                                if (companionAdapter != null) companionAdapter.notifyDataSetChanged();
                                setLoading(false);
                                renderEmptyState();
                            });

                            // 4) Çağıran tarafa hem JSONArray hem List ver
                            cb.onSuccess(finalItems, finalParsed, json);

                        } catch (Throwable e) {
                            Log.e(TAG, "parse error", e);
                            Helpers.showToastSafe(Explore.this,"Veri çözümlenirken hata.");
                            runOnUiThread(() -> { setLoading(false); renderEmptyState(); });
                            cb.onError(e);
                        }
                    }
                    @Override public void onError(@NonNull Throwable t) {
                        Log.e(TAG, "listSoulsByFields", t);
                        Helpers.showToastSafe(Explore.this,"Veri alınamadı: " + t.getMessage());
                        runOnUiThread(() -> { setLoading(false); renderEmptyState(); });
                        cb.onError(t);
                    }
                });
            });
        } catch (Exception e) {
            Log.e(TAG + "Error :", e.getMessage());
            cb.onError(e);
        }
    }
    public interface SoulsJsonCallback {
        void onSuccess(@NonNull JSONArray items, @NonNull List<Soul> parsed, @NonNull JSONObject raw);
        void onError(@NonNull Throwable t);
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



final class SimpleSwipeHelper implements View.OnTouchListener {
    private final RecyclerView recyclerView;

    interface Callback {
        void onSwipedLeft(int position);
        void onSwipedRight(int position);
    }

    private static final float SWIPE_THRESHOLD = 0.66f; // Messaging: 2/3
    private final ListView listView;
    private final Callback cb;
    private final int touchSlop;

    private float downX, downY;
    private boolean swiping = false;
    private int activePos = ListView.INVALID_POSITION;
    private View activeChild = null;

    SimpleSwipeHelper(Context ctx, @Nullable ListView lv, Callback cb, @Nullable RecyclerView rv) {
        if (lv != null){rv=null;}
        if (rv != null){lv=null;}
        this.recyclerView = rv;
        this.listView = lv;
        this.cb = cb;
        this.touchSlop = ViewConfiguration.get(ctx).getScaledTouchSlop();
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                activePos = listView.pointToPosition((int) downX, (int) downY);
                if (activePos != ListView.INVALID_POSITION) {
                    int first = listView.getFirstVisiblePosition();
                    int childIdx = activePos - first;
                    if (childIdx >= 0 && childIdx < listView.getChildCount()) {
                        activeChild = listView.getChildAt(childIdx);
                    }
                }
                swiping = false;
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (activeChild == null) break;
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;

                if (!swiping) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        swiping = true;
                        listView.requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (swiping) {
                    activeChild.setTranslationX(dx);
                    // --- messaging-like color transition ---
                    float width = Math.max(1, activeChild.getWidth());
                    float progress = Math.min(Math.abs(dx) / width, 1f)/4;
                    if (dx >= 0) {
                        // beyaz -> kırmızı
                        int red = (int) (255 * progress);
                        int gb  = (int) (255 * (1 - progress));
                        activeChild.setBackgroundColor(Color.rgb(red, gb, gb));
                        if (progress >= SWIPE_THRESHOLD) activeChild.setBackgroundColor(Color.RED);
                    } else {
                        // beyaz -> mavi
                        int blue = (int) (255 * progress);
                        int rg   = (int) (255 * (1 - progress));
                        activeChild.setBackgroundColor(Color.rgb(rg, rg, blue));
                    }
                    return true; // swipe sırasında liste scroll’u engelle
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                finishOrReset();
                break;
            }
        }
        return false; // normal tıklama/scroll akışı
    }

    private void finishOrReset() {
        if (activeChild == null) { resetState(); return; }
        float dx = activeChild.getTranslationX();
        float width = Math.max(1, activeChild.getWidth());
        float progress = Math.min(Math.abs(dx) / width, 1f);

        if (swiping && progress >= SWIPE_THRESHOLD) {
            final int pos = activePos;
            if (dx > 0) {
                // sağa: messaging’deki "aksiyonu çalıştır ve resetle" davranışını,
                // burada -> backend update + listeden çıkarma için callback'e bırakıyoruz.
                // Küçük bir çıkış animasyonu:
                activeChild.animate()
                        .translationX(width)
                        .setDuration(120)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(() -> {
                            if (pos != ListView.INVALID_POSITION) cb.onSwipedRight(pos);
                            // Görünümü sıfırla (yeniden kullanıma karşı)
//                            resetView(activeChild);
                        }).start();
            } else {
                // sola: sadece logla ve resetle (Messaging’de tamamlanmayan swipe resetlenir)
                cb.onSwipedLeft(pos);
                animateReset(activeChild);
            }
        } else {
            // eşik aşılmadı -> reset
            animateReset(activeChild);
        }
        resetState();
    }

    private void animateReset(View v) {
        v.animate()
                .translationX(0f)
                .setDuration(120)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> v.setBackgroundColor(Color.WHITE))
                .start();
    }

    private void resetView(View v) {
        v.setTranslationX(0f);
        v.setBackgroundColor(Color.WHITE);
    }

    private void resetState() {
        swiping = false;
        activePos = ListView.INVALID_POSITION;
        activeChild = null;
        listView.requestDisallowInterceptTouchEvent(false);
    }
}

