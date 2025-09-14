package com.kurmez.iyesi.kurmes.social.content;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Explore extends AppCompatActivity {

    private static final String TAG = "Explore";

    // ---- UI ----
    private SwipeRefreshLayout swipeRefresh;
    private RecyclerView recycler;
    private SoulsAdapter adapter;
    private ProgressBar progress;
    private TextView emptyView;
    private CheckBox cbNeedsCare;

    // ---- Data / State ----
    private final List<Soul> data = new ArrayList<>();
    private String nextPageToken = null;
    private boolean loading = false;
    private boolean reachedEnd = false;

    // ---- Infra ----
    private ExecutorService io;
    private CFClient cf; // OkHttp tabanlı istemci

    // ---- URL yardımcıları ----
    private static String trimRightSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
    private static String trimLeftSlash(String s) {
        if (s == null) return "";
        return s.startsWith("/") ? s.substring(1) : s;
    }
    private static String joinUrl(String base, String path) {
        String b = trimRightSlash(base);
        String p = trimLeftSlash(path);
        return b + "/" + p;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        io = Executors.newFixedThreadPool(2);

        // ---- Kök layoutu programatik kuruyoruz ----
        FrameLayout root = new FrameLayout(this);

        swipeRefresh = new SwipeRefreshLayout(this);
        root.addView(swipeRefresh, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout content = new FrameLayout(this);
        swipeRefresh.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SoulsAdapter(data);
        recycler.setAdapter(adapter);

        emptyView = new TextView(this);
        emptyView.setText("Kayıt bulunamadı.");
        emptyView.setVisibility(View.GONE);
        emptyView.setGravity(Gravity.CENTER);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        FrameLayout.LayoutParams lpProg = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpProg.gravity = Gravity.CENTER;

        cbNeedsCare = new CheckBox(this);
        cbNeedsCare.setText("Bakım ihtiyacı olanlar (needsCare)");
        cbNeedsCare.setChecked(false);
        FrameLayout.LayoutParams lpCb = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lpCb.topMargin = dp(this, 8);
        lpCb.leftMargin = dp(this, 12);
        lpCb.rightMargin = dp(this, 12);

        FrameLayout.LayoutParams lpList = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lpList.topMargin = dp(this, 48);

        content.addView(recycler, lpList);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(cbNeedsCare, lpCb);
        root.addView(progress, lpProg);

        setContentView(root);

        // Swipe refresh
        swipeRefresh.setOnRefreshListener(() -> refresh(true));
        // Filtre değişince yeniden yükle
        cbNeedsCare.setOnCheckedChangeListener((buttonView, isChecked) -> refresh(true));

        // Sonsuz kaydırma
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                if (loading || reachedEnd) return;

                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;

                int visible = lm.getChildCount();
                int total = lm.getItemCount();
                int first = lm.findFirstVisibleItemPosition();

                if (first + visible >= total - 4) {
                    fetchSouls(false);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        // CFClient (interceptor'lar CFClient içinde zaten setli)
        this.cf = new CFClient();

        Log.i(TAG, "CF base=" + BuildConfig.CF_BASE_URL + " path=" + BuildConfig.CF_PATH_SOULS_SEARCH);

        // İlk yükleme
        refresh(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (io != null) io.shutdownNow();
        if (recycler != null) recycler.setAdapter(null);
    }

    // ---- Helpers ----

    private void refresh(boolean fromUser) {
        if (fromUser) showToast("Yenileniyor…");
        nextPageToken = null;
        reachedEnd = false;
        data.clear();
        adapter.notifyDataSetChanged();
        fetchSouls(true);
    }

    private void setLoading(boolean state) {
        loading = state;
        runOnUiThread(() -> {
            progress.setVisibility(state && data.isEmpty() ? View.VISIBLE : View.GONE);
            if (!state) swipeRefresh.setRefreshing(false);
        });
    }

    private static int dp(Context c, int d) {
        float den = c.getResources().getDisplayMetrics().density;
        return Math.round(d * den);
    }

    /** Sunucudan souls çeker. */
// ... [previous code remains the same]

    /** Sunucudan souls çeker. */
    private void fetchSouls(boolean isFirstPage) {
        if (loading) return;
        setLoading(true);

        final boolean needsCare = cbNeedsCare != null && cbNeedsCare.isChecked();
        final String pageToken = nextPageToken;

        io.execute(() -> {
            try {
                // Build query parameters
                String whereClause = "health:eq:critical";
                if (needsCare) {
                    whereClause += ",needsCare:eq:true";
                }

                JSONObject body = new JSONObject();
                body.put("where", whereClause);
                body.put("limit", 20);
                if (pageToken != null && !pageToken.isEmpty()) {
                    body.put("pageToken", pageToken);
                }

                String url = joinUrl(BuildConfig.CF_BASE_URL, BuildConfig.CF_PATH_SOULS_SEARCH);
                Log.d(TAG, "fetchSouls POST " + url + " body=" + body);
                JSONObject resJson = cf.postJson(url, body);
                Log.d(TAG, "fetchSouls response = " + resJson);
                handleResponse(resJson);

            } catch (IOException ioEx) {
                Log.e(TAG, "fetchSouls failed IO: " + ioEx.getMessage(), ioEx);
                showHttpErrorToast(ioEx);
            } catch (JSONException jx) {
                Log.e(TAG, "fetchSouls JSON parse error: " + jx.getMessage(), jx);
                showToast("Veri çözümlenirken hata oluştu.");
            } catch (Throwable t) {
                Log.e(TAG, "fetchSouls unexpected: " + t.getMessage(), t);
                showToast("Beklenmeyen bir hata oluştu.");
            } finally {
                setLoading(false);
                renderEmptyState();
            }
        });
    }

// ... [rest of the code remains the same]

    @MainThread
    private void renderEmptyState() {
        runOnUiThread(() -> {
            boolean empty = data.isEmpty();
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            recycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        });
    }

    private void handleResponse(JSONObject res) throws JSONException {
        if (res == null) {
            showToast("Boş yanıt alındı.");
            return;
        }

        boolean success = res.optBoolean("success", true);
        if (!success) {
            String error = res.optString("error", "unknown");
            String field = res.optString("field", "");
            Log.e(TAG, "API error: " + error + (field.isEmpty() ? "" : (" field=" + field)));
            if (Objects.equals(error, "invalid-field") && !field.isEmpty()) {
                showToast("Geçersiz alan: " + field + " — şemayı kontrol edin.");
            } else {
                showToast("Sunucu hatası: " + error);
            }
            return;
        }

        JSONArray arr = res.optJSONArray("items");
        String npt = res.optString("nextPageToken", null);

        if (arr == null || arr.length() == 0) {
            nextPageToken = null;
            reachedEnd = true;
            runOnUiThread(() -> adapter.notifyDataSetChanged());
            return;
        }

        List<Soul> fresh = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;

            Soul s = tryParseSoul(o);
            if (s != null) fresh.add(s);
        }

        nextPageToken = (npt == null || npt.isEmpty()) ? null : npt;
        reachedEnd = (nextPageToken == null);

        runOnUiThread(() -> {
            int start = data.size();
            data.addAll(fresh);
            adapter.notifyItemRangeInserted(start, fresh.size());
        });
    }

    private Soul tryParseSoul(JSONObject o) {
        try {
            try { return Soul.fromJson(o); } catch (Throwable ignore) {}

            Soul s = new Soul();
            if (has(o, "id")) setField(s, "id", o.optString("id", null));
            if (has(o, "name")) setField(s, "name", o.optString("name", null));
            if (has(o, "species")) setField(s, "species", o.optString("species", null));
            if (has(o, "imageUrl")) setField(s, "imageUrl", o.optString("imageUrl", null));
            if (has(o, "needsCare")) setBooleanField(s, "needsCare", o.optBoolean("needsCare", false));
            // Health bilgisini de al
            if (has(o, "health")) setField(s, "health", o.optString("health", null));
            return s;

        } catch (Throwable t) {
            Log.w(TAG, "Soul parse skipped: " + t.getMessage());
            return null;
        }
    }

    private boolean has(JSONObject o, String k) {
        return o.has(k) && !o.isNull(k);
    }

    private void setField(Soul s, String field, String val) {
        try {
            java.lang.reflect.Field f = Soul.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(s, val);
        } catch (Throwable ignored) {}
    }

    private void setBooleanField(Soul s, String field, boolean val) {
        try {
            java.lang.reflect.Field f = Soul.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setBoolean(s, val);
        } catch (Throwable ignored) {}
    }

    private void showHttpErrorToast(IOException ioEx) {
        String msg = ioEx.getMessage();
        Log.e(TAG, "HTTP error: " + msg);
        String human = "Ağ hatası";

        try {
            if (msg != null && msg.contains("{") && msg.contains("}")) {
                int i = msg.indexOf('{');
                int j = msg.lastIndexOf('}');
                if (i >= 0 && j > i) {
                    String jsonStr = msg.substring(i, j + 1);
                    JSONObject err = new JSONObject(jsonStr);
                    String error = err.optString("error", "");
                    String field = err.optString("field", "");
                    if ("invalid-field".equals(error)) {
                        human = "Geçersiz alan: " + (field.isEmpty() ? "(bilinmiyor)" : field);
                    } else if (!error.isEmpty()) {
                        human = "Sunucu hatası: " + error;
                    }
                }
            }
        } catch (Throwable ignored) { }

        showToast(human);
    }

    private void showToast(String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }

    // ---------------- RecyclerView Adapter (basit) ----------------

    private static class SoulsAdapter extends RecyclerView.Adapter<SoulVH> {
        private final List<Soul> items;
        SoulsAdapter(List<Soul> items) { this.items = items; }

        @NonNull
        @Override
        public SoulVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            Context ctx = parent.getContext();
            FrameLayout row = new FrameLayout(ctx);

            TextView tv = new TextView(ctx);
            tv.setId(View.generateViewId());
            tv.setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10));

            row.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            return new SoulVH(row, tv);
        }

        @Override
        public void onBindViewHolder(@NonNull SoulVH holder, int position) {
            Soul s = items.get(position);
            String title = safe(getField(s, "name"), "(İsimsiz)");
            String species = safe(getField(s, "species"), "");
            boolean needsCare = getBooleanField(s, "needsCare");
            String health = safe(getField(s, "health"), "");
            String line = title
                    + (species.isEmpty() ? "" : " · " + species)
                    + (needsCare ? " · ❤️ needsCare" : "")
                    + (health.equals("critical") ? " · ⚠️ Critical" : "");
            holder.text.setText(line);
        }

        @Override
        public int getItemCount() { return items.size(); }

        private static String safe(String v, String def) { return v == null ? def : v; }

        private static String getField(Soul s, String field) {
            try {
                java.lang.reflect.Field f = Soul.class.getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(s);
                return v == null ? null : String.valueOf(v);
            } catch (Throwable ignored) { }
            return null;
        }

        private static boolean getBooleanField(Soul s, String field) {
            try {
                java.lang.reflect.Field f = Soul.class.getDeclaredField(field);
                f.setAccessible(true);
                return f.getBoolean(s);
            } catch (Throwable ignored) { }
            return false;
        }

        private static int dp(Context c, int d) {
            float den = c.getResources().getDisplayMetrics().density;
            return Math.round(d * den);
        }
    }

    private static class SoulVH extends RecyclerView.ViewHolder {
        final TextView text;
        SoulVH(@NonNull View itemView, @NonNull TextView text) {
            super(itemView);
            this.text = text;
        }
    }
}