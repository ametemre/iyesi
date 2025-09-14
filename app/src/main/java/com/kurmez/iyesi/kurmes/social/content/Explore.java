package com.kurmez.iyesi.kurmes.social.content;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.kurmez.iyesi.BuildConfig;
import com.kurmez.iyesi.kurmes.net.CFClient;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Explore — server'dan (Cloud Functions/Run) Souls listesini çeker ve gösterir.
 * 401/APP_CHECK_MISSING vb. hataları önlemek için:
 *  - CFClient.withAutoAuth(...) kullanır (interceptor AppCheck + Auth ekler).
 *  - Token parametrelerini NULL geçer.
 */
public class Explore extends AppCompatActivity {

    private static final String TAG = "Explore";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private RecyclerView recycler;
    private SoulsAdapter adapter;

    // Basit veri modeli
    static class SoulItem {
        final String title;
        final String date;
        final String location;
        SoulItem(String title, String date, String location) {
            this.title = title;
            this.date = date;
            this.location = location;
        }
    }

    // Basit adapter (programatik satır tasarımı)
    static class SoulsAdapter extends RecyclerView.Adapter<SoulsAdapter.VH> {
        final ArrayList<SoulItem> data = new ArrayList<>();
        static class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;
            VH(@NonNull LinearLayout root, TextView title, TextView subtitle) {
                super(root);
                this.title = title;
                this.subtitle = subtitle;
            }
        }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(28, 24, 28, 24);
            TextView t = new TextView(parent.getContext());
            t.setTextSize(16f);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setSingleLine(true);
            TextView s = new TextView(parent.getContext());
            s.setTextSize(13f);
            s.setEllipsize(TextUtils.TruncateAt.END);
            s.setMaxLines(2);
            row.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(s, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new VH(row, t, s);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int i) {
            SoulItem it = data.get(i);
            h.title.setText(it.title);
            h.subtitle.setText(it.date + "  •  " + it.location);
        }
        @Override public int getItemCount() { return data.size(); }
        void replaceAll(ArrayList<SoulItem> list) {
            data.clear();
            data.addAll(list);
            notifyDataSetChanged();
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Programatik basit ekran
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView header = new TextView(this);
        header.setText("Explore");
        header.setTextSize(18f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setGravity(Gravity.CENTER_HORIZONTAL);
        header.setPadding(0, 24, 0, 12);
        recycler = new RecyclerView(this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        adapter = new SoulsAdapter();
        recycler.setAdapter(adapter);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        // Veriyi çek
        fetchSouls();
    }

    /** Server'dan listeyi çeker. */
    private void fetchSouls() {
        // 401/APP_CHECK_MISSING'i önleyen client
        final CFClient cf = CFClient.withAutoAuth(BuildConfig.CF_BASE_URL);

        io.execute(() -> {
            try {
                // Örnek where: adminPathPrefix=TR  (kendi ihtiyacına göre düzenle)
                String where = "adminPathPrefix:TR";
                int limit = 50;
                String q = "where=" + urlEncode(where) + "&limit=" + limit + "&col=Souls";
                String path = "/listSoulsByFields?" + q;

                // Token parametreleri NULL → interceptor ekler
                JSONObject json = cf.getJson(path, /*idToken*/ null, /*appCheck*/ null);

                ArrayList<SoulItem> list = parseSouls(json);
                main.post(() -> adapter.replaceAll(list));

            } catch (Exception e) {
                Log.e(TAG, "fetchSouls failed", e);
                main.post(() -> {
                    adapter.replaceAll(new ArrayList<>());
                    // Basit hata başlığı
                    if (recycler.getChildCount() == 0) {
                        TextView t = new TextView(this);
                        t.setText("Yüklenemedi: " + e.getMessage());
                        t.setPadding(28, 28, 28, 28);
                        t.setTextSize(14f);
                        t.setGravity(Gravity.CENTER);
                        ((ViewGroup) recycler.getParent()).addView(t);
                    }
                });
            }
        });
    }

    /** JSON parse (örnek sözleşme bekleniyor). Kendi backend çıktına göre düzenle. */
    @NonNull
    private ArrayList<SoulItem> parseSouls(@NonNull JSONObject json) throws JSONException {
        ArrayList<SoulItem> out = new ArrayList<>();
        // Örnek şema:
        // { "ok": true, "items": [ { "title": "...", "date": 1694102400000, "adminPath": "TR/ADANA", ... }, ... ] }
        boolean ok = json.optBoolean("ok", false);
        if (!ok) {
            // bazen {ok:false, err:"APPCHECK_MISSING"} vs. gelebilir
            String err = json.optString("err");
            throw new IOException("Server error: " + (TextUtils.isEmpty(err) ? json.toString() : err));
        }
        JSONArray arr = json.optJSONArray("items");
        if (arr == null) return out;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;

            String title = it.optString("title", it.optString("breed", "mixed"));
            String loc = it.optString("adminPath", "");
            long ts = it.optLong("date", it.optLong("createdAt", 0L));
            String dateStr = (ts > 0) ? formatDate(ts) : "";

            out.add(new SoulItem(title, dateStr, loc));
        }
        return out;
    }

    private static String urlEncode(@NonNull String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    private static String formatDate(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(epochMs));
    }
}
