package com.kurmez.iyesi.kurmes.social.content;

import static com.kurmez.iyesi.kayra.Classes.data.Soul.parseSouls;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.utilities.adapters.CompanionAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore — ExplorePrivate görünümündeki gibi üstte kullanıcı kartı + yol satırı,
 * altta Welcome/CompanionAdapter kart listesi.
 * Veri: GET /listSoulsByFields, health="critical" (sabit) + needsCare (opsiyonel).
 */
public class Explore extends AppCompatActivity {

    private static final String TAG = "Explore";

    // ---------- Header (user card) ----------
    private FrameLayout headerCard;
    private ImageView ivAvatar;
    private TextView tvUserName;
    private ImageView btnOverflow;

    // ---------- Path row ----------
    private FrameLayout pathRow;
    private TextView tvPath;
    private ImageView ivChevron;

    // ---------- Filter ----------
    private CheckBox cbNeedsCare;

    // ---------- List ----------
    private SwipeRefreshLayout swipeRefresh;
    private ListView listView;
    private ProgressBar progress;
    private TextView emptyView;

    // ---------- Data ----------
    private final List<Soul> companions = new ArrayList<>();
    private CompanionAdapter adapter;

    // ---------- Infra ----------
    private CFClient cf;
    private ExecutorService io;

    // Layout constants
    private static final int HEADER_TOP = 8;       // dp
    private static final int HEADER_RADIUS = 16;   // dp
    private static final int AVATAR_SIZE = 40;     // dp
    private static final int HEADER_H = 64;        // ~dp (padding + avatar yüksekliği)
    private static final int PATH_TOP_MARGIN = 68; // dp (header altı)
    private static final int PATH_ROW_H = 36;      // ~dp
    private static final int CB_TOP_MARGIN = 108;  // dp (header+path altı)
    private static final int LIST_TOP_MARGIN = 156;// dp (header+path+checkbox altı)

    // =============================================================================================
    // Lifecycle
    // =============================================================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        io = Executors.newFixedThreadPool(2);

        FrameLayout root = new FrameLayout(this);

        swipeRefresh = new SwipeRefreshLayout(this);
        root.addView(swipeRefresh, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        swipeRefresh.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // -------- User Header (ExplorePrivate stili) --------
        headerCard = new FrameLayout(this);
        headerCard.setBackground(roundedBg(0xFFFFFFFF, HEADER_RADIUS));
        headerCard.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        headerCard.setElevation(dp(this, 2));

        FrameLayout.LayoutParams lpHeader = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpHeader.topMargin = dp(this, HEADER_TOP);
        lpHeader.leftMargin = dp(this, 12);
        lpHeader.rightMargin = dp(this, 12);
        content.addView(headerCard, lpHeader);

        ivAvatar = new ImageView(this);
        ivAvatar.setId(View.generateViewId());
        ivAvatar.setImageResource(android.R.drawable.sym_def_app_icon); // placeholder
        FrameLayout.LayoutParams lpAvatar = new FrameLayout.LayoutParams(
                dp(this, AVATAR_SIZE), dp(this, AVATAR_SIZE));
        lpAvatar.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        headerCard.addView(ivAvatar, lpAvatar);

        tvUserName = new TextView(this);
        tvUserName.setText("KullanıcıAdı");
        tvUserName.setTextSize(18);
        tvUserName.setTypeface(Typeface.DEFAULT_BOLD);
        FrameLayout.LayoutParams lpName = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpName.leftMargin = dp(this, AVATAR_SIZE + 12);
        lpName.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        headerCard.addView(tvUserName, lpName);

        btnOverflow = new ImageView(this);
        btnOverflow.setImageResource(android.R.drawable.ic_menu_more);
        FrameLayout.LayoutParams lpOv = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpOv.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        headerCard.addView(btnOverflow, lpOv);

        btnOverflow.setOnClickListener(v -> {
            PopupMenu pm = new PopupMenu(this, btnOverflow);
            pm.getMenu().add("Profil");
            pm.getMenu().add("Ayarlar");
            pm.getMenu().add("Çıkış");
            pm.setOnMenuItemClickListener(mi -> {
                showToast(mi.getTitle().toString());
                return true;
            });
            pm.show();
        });

        // -------- Path Row ("Yol seçilmedi") --------
        pathRow = new FrameLayout(this);
        FrameLayout.LayoutParams lpPath = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPath.topMargin = dp(this, PATH_TOP_MARGIN); // header altına yerleştir
        lpPath.leftMargin = dp(this, 12);
        lpPath.rightMargin = dp(this, 12);
        content.addView(pathRow, lpPath);

        tvPath = new TextView(this);
        tvPath.setText("Yol seçilmedi");
        tvPath.setTextSize(14);
        tvPath.setPadding(0, dp(this, 8), 0, dp(this, 8));
        FrameLayout.LayoutParams lpPathText = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpPathText.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
        pathRow.addView(tvPath, lpPathText);

        ivChevron = new ImageView(this);
        ivChevron.setImageResource(android.R.drawable.arrow_down_float);
        FrameLayout.LayoutParams lpCh = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCh.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        pathRow.addView(ivChevron, lpCh);

        View.OnClickListener choosePath = v -> showToast("Yol seçimi açılacak");
        tvPath.setOnClickListener(choosePath);
        ivChevron.setOnClickListener(choosePath);

        // -------- needsCare filtresi (header + path altına) --------
        cbNeedsCare = new CheckBox(this);
        cbNeedsCare.setText("Sadece bakım ihtiyacı olanlar (needsCare)");
        cbNeedsCare.setChecked(false);
        FrameLayout.LayoutParams lpCb = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCb.topMargin = dp(this, CB_TOP_MARGIN);
        lpCb.leftMargin = dp(this, 12);
        lpCb.rightMargin = dp(this, 12);
        content.addView(cbNeedsCare, lpCb);

        // -------- ListView + CompanionAdapter (Welcome görünümü) --------
        listView = new ListView(this);
        adapter = new CompanionAdapter(this, companions);
        listView.setAdapter(adapter);

        FrameLayout.LayoutParams lpList = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpList.topMargin = dp(this, LIST_TOP_MARGIN);
        content.addView(listView, lpList);

        // Boş görünüm
        emptyView = new TextView(this);
        emptyView.setText("Kayıt bulunamadı.");
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Progress
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams lpProg = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpProg.gravity = Gravity.CENTER;
        root.addView(progress, lpProg);

        setContentView(root);

        // Etkileşimler
        //swipeRefresh.setOnRefreshListener(() -> refresh(true));
        cbNeedsCare.setOnCheckedChangeListener((b, c) -> refresh(true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Base URL tam kök olmalı: https://us-central1-<proj>.cloudfunctions.net
        this.cf = new CFClient(BuildConfig.CF_BASE_URL);
        Log.i(TAG, "CF base=" + BuildConfig.CF_BASE_URL + " (GET listSoulsByFields, health=critical)");

        // İstersen gerçek kullanıcıyı burada bağla
        bindUser("KullanıcıAdı", null);

        refresh(false);
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
        adapter.notifyDataSetChanged();
        fetchSouls();
    }

    /** Tek kanal: GET /listSoulsByFields — health="critical" sabit, needsCare opsiyonel. */
    private void fetchSouls() {
        setLoading(true);

        final boolean needsCare = cbNeedsCare != null && cbNeedsCare.isChecked();
        final CFClient.WhereBuilder wb = new CFClient.WhereBuilder().eq("health", "critical");
        if (needsCare) wb.eq("needsCare", "true");

        ensureIo();
        io.execute(() -> cf.listSoulsByFields(wb, 20, new CFClient.JsonCallback() {
            @Override public void onSuccess(@NonNull JSONObject json) {
                try {
                    List<Soul> parsed = parseSouls(json);
                    if (parsed == null) parsed = java.util.Collections.emptyList();

                    final List<Soul> finalParsed = parsed;
                    runOnUiThread(() -> {
                        companions.clear();
                        companions.addAll(finalParsed);
                        adapter.notifyDataSetChanged();
                        setLoading(false);
                        renderEmptyState();
                    });
                } catch (Throwable e) {
                    Log.e(TAG, "parse error", e);
                    showToast("Veri çözümlenirken hata.");
                    setLoading(false);
                    renderEmptyState();
                }
            }

            @Override public void onError(@NonNull Throwable t) {
                Log.e(TAG, "listSoulsByFields", t);
                showToast("Veri alınamadı: " + t.getMessage());
                setLoading(false);
                renderEmptyState();
            }
        }));
    }

    // =============================================================================================
    // UI helpers
    // =============================================================================================
    private void bindUser(String name, android.graphics.Bitmap avatarBmp) {
        if (name != null && !name.isEmpty()) tvUserName.setText(name);
        if (avatarBmp != null) ivAvatar.setImageBitmap(avatarBmp);
        // Avatarı daire yapmak istersen:
        // ivAvatar.setBackground(roundedBg(0xFFECECEC, AVATAR_SIZE));
        // ivAvatar.setClipToOutline(true);
    }

    private void setLoading(boolean state) {
        runOnUiThread(() -> {
            if (state && companions.isEmpty()) {
                progress.setVisibility(View.VISIBLE);
            } else {
                progress.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
            }
        });
    }

    private void renderEmptyState() {
        boolean empty = companions.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void showToast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    private static int dp(Context c, int d) {
        float den = c.getResources().getDisplayMetrics().density;
        return Math.round(d * den);
    }

    private GradientDrawable roundedBg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(this, (int) radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, r, r, r, r});
        return d;
    }

    private void ensureIo() {
        if (io == null || io.isShutdown()) io = Executors.newFixedThreadPool(2);
    }
}
