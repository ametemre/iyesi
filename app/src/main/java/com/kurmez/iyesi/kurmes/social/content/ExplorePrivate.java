package com.kurmez.iyesi.kurmes.social.content;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
import com.kurmez.iyesi.kurmes.social.iye;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.ContentAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;
import com.kurmez.iyesi.umay.sahiplendirme.iyesiz;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Lists pending companion records for privileged roles and allows quick actions on each item.
 * "İlgi gerektiren" kayıtlar (eksik alan, yeni kayıt, foto yok vb.) üstte gösterilir.
 * GİRİŞ/ÇIKIŞ noktaları yoğun biçimde Log ile işaretlendi.
 */
public class ExplorePrivate extends AppCompatActivity {

    /* ========================== LOG & FLAGS ========================== */
    private static final String L = "ExplorePrivate";
    private static final boolean VERBOSE_JSON = true;   // JSON’ları detaylı bas
    private static final int MAX_LOG_CHARS = 4000;      // Logcat chunk sınırı

    /* ============================ MODEL ============================== */
    private static class Row {
        String key;
        Soul soul;
        String imageUrl;
        int score;
        long ts;
        String displayText;
    }

    /* ======================= CONST / PERMISSIONS ===================== */
    // DOĞRU RTDB URL (default-rtdb alan adı)
    private static final String RTDB_URL = "https://iyesi-e8d4f-default-rtdb.firebaseio.com/";
    private static final List<String> ALLOWED_ROLES = Arrays.asList(
            "İye", "Körmös", "Körmes", "Ülgen", "Tengri", "Ağaç"
    );
    private static final boolean FEATURE_PENDING_COMPANIONS = false;
    private static final int REQ_LOC = 42;

    /* ============================ STATE ============================== */
    private final List<Content> items = new ArrayList<>();
    private final List<Soul> souls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private HeaderHelper headerHelper;
    private RecyclerView recyclerView;
    private ContentAdapter adapter;

    private CFHelper cf;
    private FirebaseUser user;
    private String userRole;
    private DatabaseReference pendingRef;
    private FusedLocationProviderClient fused;

    // CANLI DİNLEME
    private ValueEventListener liveListener;

    /* =========================== LIFECYCLE =========================== */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long t0 = System.currentTimeMillis();
        Log.i(L, "onCreate() → GİRİŞ");
        headerHelper = new HeaderHelper(ExplorePrivate.this);
        setContentView(R.layout.activity_explore_private);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            Log.w(L, "onCreate() → ÇIKIŞ (USER NULL) | Giriş gerekli");
            Toast.makeText(this, "Bu sayfayı görüntülemek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Log.d(L, "onCreate() user=" + user.getUid() + " email=" + user.getEmail());
        headerHelper.refreshHeader(ExplorePrivate.this);

        FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);
        pendingRef = db.getReference("Pending/Companion/soul_inneed");
        Log.d(L, "RTDB path = " + pendingRef.getPath().toString());

        recyclerView = findViewById(R.id.recycler_private_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(items, this);
        recyclerView.setAdapter(adapter);

        View header = findViewById(R.id.profile_header);
        if (header != null) {
            header.setOnClickListener(v -> {
                Log.d(L, "Header click → Profile");
                startActivity(new Intent(this, iye.class));
            });
        }

        cf = new CFHelper(this, "iyesi-e8d4f", null);
        fused = LocationServices.getFusedLocationProviderClient(this);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(L, "Konum izni yok → requestPermissions");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOC);
        } else {
            Log.d(L, "Konum izni mevcut → requestLastLocationMaybeFetch()");
            requestLastLocationMaybeFetch();
        }

        attachItemTouchHandlers();
        try {
            Helpers helper = new Helpers();
            helper.resolveRoleAndFetch(this,this);
            // İlk çekim (tek seferlik)
            fetchAllFromRTDB();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Log.i(L, "onCreate() → ÇIKIŞ (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    @Override
    protected void onStart() {
        super.onStart();
        attachLiveListener(); // CANLI dinleme başlat
    }

    @Override
    protected void onStop() {
        super.onStop();
        detachLiveListener(); // CANLI dinleme durdur
    }

    /* ======================= PRIORITY SCORING ======================== */
    private static int computeAttentionScore(@NonNull Soul s) {
        int score = 0;
        if (isEmpty(s.getHealth())) score += 3;
        if (isEmpty(s.getAge()))    score += 2;
        if (isEmpty(s.getSpecies())) score += 2;
        if (isEmpty(s.getBreed()))   score += 1;
        String img = !isEmpty(s.getImageResId()) ? s.getImageResId() : s.getImageUrl();
        if (isEmpty(img)) score += 2;
        if (isEmpty(s.getFoundLocation())) score += 2;
        long now = System.currentTimeMillis();
        long ts  = s.getTimestamp() > 0 ? s.getTimestamp() : 0;
        long dt  = ts > 0 ? (now - ts) : Long.MAX_VALUE;
        final long H = 60L * 60L * 1000L;
        if (dt <= 24 * H)       score += 2;
        else if (dt <= 72 * H)  score += 1;
        String h = s.getHealth() == null ? "" : s.getHealth().toLowerCase();
        if (h.contains("critical") || h.contains("acil") || h.contains("urgent")) score += 2;
        return score;
    }

    private static boolean isEmpty(String s) { return s == null || s.isEmpty(); }
    private static String  nz(String s)      { return s == null ? "" : s; }
    private String safe(String s)            { return s == null ? "-" : s; }

    /* ======================= ROLE / ACCESS FLOW ======================= */
    // (Mevcut akışını korudum)

    /* ============================ RTDB FETCH ========================== */
    /**
     * RTDB'den tüm kayıtları oku, skorla ve listele (önceliklendirilmiş).
     * GİRİŞ: path, çocuk sayısı
     * ÇIKIŞ: sıralı kayıt adedi, örnek anahtarlar
     */
    private void fetchAllFromRTDB() {
        long t0 = System.currentTimeMillis();
        Log.i(L, "fetchAllFromRTDB() → GİRİŞ path=" + pendingRef.getPath());

        pendingRef.get()
                .addOnSuccessListener(snap -> {
                    long t1 = System.currentTimeMillis();
                    Log.i(L, "RTDB get() OK in " + (t1 - t0) + " ms | children=" + snap.getChildrenCount());
                    applySnapshot(snap, /*source=*/"get()");
                })
                .addOnFailureListener(e -> {
                    Log.e(L, "RTDB get() FAILED: " + e.getMessage(), e);
                    toastForReadError(e);
                });
    }

    /** CANLI dinlemeyi bağlar (ValueEventListener). */
    private void attachLiveListener() {
        if (liveListener != null) return;
        Log.d(L, "attachLiveListener()");
        liveListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(L, "live.onDataChange children=" + snapshot.getChildrenCount());
                applySnapshot(snapshot, /*source=*/"live");
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Log.e(L, "live.onCancelled: " + error.getMessage(), error.toException());
                toastForReadError(error.toException());
            }
        };
        pendingRef.addValueEventListener(liveListener);
    }

    /** CANLI dinlemeyi kaldırır. */
    private void detachLiveListener() {
        if (liveListener == null) return;
        Log.d(L, "detachLiveListener()");
        pendingRef.removeEventListener(liveListener);
        liveListener = null;
    }

    /** Ortak snapshot işleyici (sıralama + adapter güncelleme). */
    private void applySnapshot(@NonNull DataSnapshot snap, @NonNull String source) {
        List<Row> rows = new ArrayList<>();
        int idx = 0;

        for (DataSnapshot child : snap.getChildren()) {
            idx++;
            final String key = child.getKey();

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) child.getValue();
            if (map == null) {
                Log.w(L, "child[" + idx + "] key=" + key + " → value=null (skip)");
                continue;
            }

            JSONObject o = new JSONObject(map);
            try { o.put("id", key); } catch (Exception ignore) {}

            if (VERBOSE_JSON) logChunked("RTDB.child[" + idx + "]." + key, o.toString());

            Soul s = Soul.fromJson(o);
            if (s == null) {
                s = new Soul(
                        null,
                        o.optString("species"),
                        o.optString("breed"),
                        o.optString("age"),
                        o.optString("health"),
                        o.optString("foundDate"),
                        o.optString("foundLocation"),
                        null,
                        o.optString("imageResId", o.optString("imageUrl")),
                        o.optString("finderName", o.optString("finder")),
                        o.optLong("timestamp", 0)
                );
            }

            String imageUrl = !isEmpty(s.getImageResId()) ? s.getImageResId() : s.getImageUrl();
            String text = "Tür: " + safe(s.getSpecies())
                    + "\nKayıt sahibi: " + safe(s.getFinderName())
                    + "\nKonum: " + safe(s.getFoundLocation());

            int sc = computeAttentionScore(s);
            long ts = s.getTimestamp();

            Row r = new Row();
            r.key = key;
            r.soul = s;
            r.imageUrl = imageUrl;
            r.score = sc;
            r.ts = ts;
            r.displayText = text;

            rows.add(r);
        }

        // Sıralama: score desc, ts desc
        rows.sort((a, b) -> {
            if (b.score != a.score) return Integer.compare(b.score, a.score);
            return Long.compare(b.ts, a.ts);
        });

        items.clear();
        souls.clear();
        keys.clear();

        for (Row r : rows) {
            keys.add(r.key);
            souls.add(r.soul);
            String badge = r.score >= 5 ? "★ " : (r.score >= 3 ? "• " : "");
            items.add(new Content(r.imageUrl, badge + r.displayText, 0, 0, true));
        }

        adapter.notifyDataSetChanged();
        Log.i(L, "applySnapshot(" + source + ") → count=" + keys.size() + " | " + previewKeys(keys));
        Helpers.showToastSafe(this, "ExplorePrivate: " + keys.size() + " kayıt (" + source + ")");
    }

    /* =========================== CF (optional) ============================ */
    private void fetchPendingViaCF(double lat, double lng) {
        Log.i(L, "fetchPendingViaCF() → GİRİŞ lat=" + lat + " lng=" + lng + " flag=" + FEATURE_PENDING_COMPANIONS);
        if (!FEATURE_PENDING_COMPANIONS) {
            Log.i(L, "fetchPendingViaCF() → ÇIKIŞ (flag=false)");
            return;
        }

        long t0 = System.currentTimeMillis();
        new Thread(() -> {
            try {
                JSONObject json = cf.listPendingCompanions(lat, lng, 50_000, 100);
                Log.d(L, "CF.listPendingCompanions() → ÇIKIŞ in " + (System.currentTimeMillis() - t0) + " ms");
                runOnUiThread(() -> handlePendingResponse(json));
            } catch (Exception e) {
                Log.e(L, "CF error", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Cloud Function hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void handlePendingResponse(@NonNull JSONObject json) {
        Log.i(L, "handlePendingResponse() → GİRİŞ");
        boolean ok = json.optBoolean("success", json.optBoolean("ok", false));
        if (!ok) {
            String err = json.optString("error", "CF hata");
            Log.w(L, "handlePendingResponse() not ok → " + err);
            Helpers.showToastSafe(this, err);
            return;
        }

        if (VERBOSE_JSON) logChunked("CF.response", json.toString());

        JSONArray arr = json.optJSONArray("items");
        int n = arr == null ? 0 : arr.length();

        items.clear();
        souls.clear();
        keys.clear();

        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;

            String id = o.optString("id", o.optString("key", ""));
            if (id.isEmpty()) continue;
            keys.add(id);

            Soul s = Soul.fromJson(o);
            if (s == null) {
                s = new Soul(
                        null,
                        o.optString("species"),
                        o.optString("breed"),
                        o.optString("age"),
                        o.optString("health"),
                        o.optString("foundDate"),
                        o.optString("foundLocation"),
                        null,
                        o.optString("imageResId", o.optString("imageUrl")),
                        o.optString("finderName", o.optString("finder")),
                        o.optLong("timestamp", 0)
                );
            }
            souls.add(s);

            String imageUrl = !isEmpty(s.getImageResId()) ? s.getImageResId() : s.getImageUrl();
            String text = "Tür: " + safe(s.getSpecies())
                    + "\nKayıt sahibi: " + safe(s.getFinderName())
                    + "\nKonum: " + safe(s.getFoundLocation());
            items.add(new Content(imageUrl, text, 0, 0, true));
        }

        adapter.notifyDataSetChanged();
        Log.i(L, "handlePendingResponse() → ÇIKIŞ count=" + n + " | " + previewKeys(keys));
        Helpers.showToastSafe(this, "ExplorePrivate: " + n + " kayıt yüklendi");
    }

    /* ======================= ITEM INTERACTIONS ======================== */
    private void attachItemTouchHandlers() {
        Log.d(L, "attachItemTouchHandlers()");
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            final GestureDetector detector = new GestureDetector(ExplorePrivate.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onSingleTapUp(MotionEvent e) { return true; }
                        @Override public void onLongPress(MotionEvent e) {
                            View child = recyclerView.findChildViewUnder(e.getX(), e.getY());
                            if (child != null) {
                                int pos = recyclerView.getChildAdapterPosition(child);
                                Log.d(L, "onLongPress pos=" + pos);
                                if (pos >= 0 && pos < keys.size()) {
                                    showQuickActionsDialog(pos);
                                }
                            }
                        }
                    });

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child != null && detector.onTouchEvent(e)) {
                    int pos = rv.getChildAdapterPosition(child);
                    Log.d(L, "onSingleTap pos=" + pos);
                    if (pos >= 0 && pos < keys.size()) {
                        openEditor(pos);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    private void openEditor(int position) {
        if (position < 0 || position >= keys.size()) return;

        Soul s = souls.get(position);
        String key = keys.get(position);

        Intent i = new Intent(this, iyesiz.class);
        i.putExtra("requestKey", key);
        i.putExtra("node", "soul_inneed");

        i.putExtra("species", nz(s.getSpecies()));
        i.putExtra("breed",   nz(s.getBreed()));
        i.putExtra("foundDate", nz(s.getFoundDate()));
        i.putExtra("foundPlace", nz(s.getFoundLocation()));
        String imageUrl = !isEmpty(s.getImageResId()) ? s.getImageResId() : s.getImageUrl();
        i.putExtra("photoUrl", nz(imageUrl));
        i.putExtra("profileId", nz(s.getFinderName()));

        Log.i(L, "openEditor() → GİRİŞ intentExtras: "
                + "key=" + key
                + " species=" + nz(s.getSpecies())
                + " breed=" + nz(s.getBreed())
                + " date=" + nz(s.getFoundDate())
                + " place=" + nz(s.getFoundLocation())
                + " photo=" + nz(imageUrl)
                + " who=" + nz(s.getFinderName()));

        startActivity(i);
    }

    private void showQuickActionsDialog(int position) {
        if (position < 0 || position >= keys.size()) return;
        String key = keys.get(position);

        Log.d(L, "showQuickActionsDialog() key=" + key);

        String[] roles = {"İye", "Körmös", "Ülgen", "Tengri"};
        new AlertDialog.Builder(this)
                .setTitle("Hızlı İşlemler")
                .setItems(new String[]{
                        "Bu kaydı tamamla",
                        "Rolü değiştir…",
                        "Sil"
                }, (d, which) -> {
                    Log.d(L, "QuickAction which=" + which + " key=" + key);
                    switch (which) {
                        case 0:
                            updateStatusCompleted(key);
                            break;
                        case 1:
                            new AlertDialog.Builder(this)
                                    .setTitle("Yeni Rol Seç")
                                    .setItems(roles, (d2, idx) -> updateCurrentRole(key, roles[idx]))
                                    .show();
                            break;
                        case 2:
                            deletePending(key);
                            break;
                    }
                })
                .show();
    }

    private void updateStatusCompleted(@NonNull String key) {
        Log.i(L, "updateStatusCompleted() → GİRİŞ key=" + key);
        pendingRef.child(key).child("status").setValue("completed")
                .addOnSuccessListener(v -> {
                    Log.i(L, "updateStatusCompleted() → ÇIKIŞ OK key=" + key);
                    Toast.makeText(this, "Tamamlandı ✓", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(L, "updateStatusCompleted() → ÇIKIŞ FAIL key=" + key + " msg=" + e.getMessage(), e);
                    Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void updateCurrentRole(@NonNull String key, @NonNull String newRole) {
        Log.i(L, "updateCurrentRole() → GİRİŞ key=" + key + " newRole=" + newRole);
        pendingRef.child(key).child("currentRole").setValue(newRole)
                .addOnSuccessListener(v -> {
                    Log.i(L, "updateCurrentRole() → ÇIKIŞ OK");
                    Toast.makeText(this, "Rol güncellendi: " + newRole, Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Log.e(L, "updateCurrentRole() → ÇIKIŞ FAIL msg=" + e.getMessage(), e);
                    Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
    }

    private void deletePending(@NonNull String key) {
        Log.w(L, "deletePending() → GİRİŞ key=" + key);
        new AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage("Bu kaydı kalıcı olarak silmek istiyor musunuz?")
                .setPositiveButton("Sil", (d, w) ->
                        pendingRef.child(key).removeValue()
                                .addOnSuccessListener(v -> {
                                    Log.w(L, "deletePending() → ÇIKIŞ OK key=" + key);
                                    Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show();
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(L, "deletePending() → ÇIKIŞ FAIL key=" + key + " msg=" + e.getMessage(), e);
                                    Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                }))
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    /* ============================ UTILITIES =========================== */
    private static void logChunked(String prefix, String text) {
        if (text == null) {
            Log.d(L, prefix + " <null>");
            return;
        }
        for (int i = 0; i < text.length(); i += MAX_LOG_CHARS) {
            Log.d(L, prefix + ": " + text.substring(i, Math.min(i + MAX_LOG_CHARS, text.length())));
        }
    }

    private static String previewKeys(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        int n = list.size();
        String head = list.get(0);
        String tail = list.get(n - 1);
        return "[first=" + head + ", last=" + (n > 1 ? tail : head) + ", n=" + n + "]";
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void requestLastLocationMaybeFetch() {
        Log.d(L, "requestLastLocationMaybeFetch()");
        fused.getLastLocation().addOnSuccessListener(l -> {
            double lat = (l != null) ? l.getLatitude() : 41.015137;
            double lng = (l != null) ? l.getLongitude() : 28.97953;
            Log.d(L, "lastLocation lat=" + lat + " lng=" + lng);
            fetchPendingViaCF(lat, lng);
        });
    }

    /* ================== PERMISSION RESULT HANDLING =================== */
    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(L, "onRequestPermissionsResult() req=" + requestCode);
        if (requestCode == REQ_LOC) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(L, "LOCATION GRANTED → requestLastLocationMaybeFetch()");
                requestLastLocationMaybeFetch();
            } else {
                Log.w(L, "LOCATION DENIED");
                fetchPendingViaCF(41.015137, 28.97953); // sadece flag açıksa çalışır
            }
        }
    }

    /* ======================== ERROR → USER TOAST ===================== */
    private void toastForReadError(Exception e) {
        String msg = e != null ? e.getMessage() : "bilinmeyen";
        String userMsg = "Veri okunamadı";
        String low = msg != null ? msg.toLowerCase() : "";
        if (low.contains("permission")) userMsg = "İzin reddedildi (Rules/App Check?)";
        else if (low.contains("app check") || low.contains("appcheck")) userMsg = "App Check doğrulaması eksik";
        else if (low.contains("network")) userMsg = "Ağ hatası";
        Helpers.showToastSafe(this, userMsg + " • " + (msg == null ? "" : msg));
    }
}
