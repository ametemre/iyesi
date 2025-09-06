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
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.social.Profile;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.ContentAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kayra.Classes.Soul;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Lists pending companion records for privileged roles and allows quick actions on each item.
 */
public class ExplorePrivate extends AppCompatActivity {
    private static final String TAG = "ExplorePrivate";
    // bir sabit tanımla
    private static final String RTDB_URL = "https://iyesi-e8d4f.firebaseio.com"; // konsoldaki link
    private static final List<String> ALLOWED_ROLES = Arrays.asList(
            "İye", "Körmös", "Ülgen", "Tengri",
            "İYE", "iye", "Körmes", "Kormos", "KORMOS", "KÖRMÖS",
            "ULGEN", "ÜLGEN", "Ulgen", "TENGRI", "TENGRİ"
    );

    private final List<Content> items = new ArrayList<>();
    private final List<Soul> souls = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();

    private RecyclerView recyclerView;
    private ContentAdapter adapter;

    private CFHelper cf;
    private FirebaseUser user;
    private String userRole;
    private DatabaseReference pendingRef;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_private);


// kullanım
        FirebaseDatabase db = FirebaseDatabase.getInstance(RTDB_URL);

        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Bu sayfayı görüntülemek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        recyclerView = findViewById(R.id.recycler_private_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(items, this);
        recyclerView.setAdapter(adapter);

        View header = findViewById(R.id.profile_header);
        if (header != null) {
            header.setOnClickListener(v -> startActivity(new Intent(this, Profile.class)));
        }

        cf = new CFHelper(this, "iyesi-e8d4f", null);
        pendingRef = db.getReference("Pending/Companion/soul_inneed");
        FusedLocationProviderClient loc = LocationServices.getFusedLocationProviderClient(this);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 42);
            return;
        }

        loc.getLastLocation().addOnSuccessListener(l -> {
            double lat = (l != null) ? l.getLatitude()  : 41.015137; // fallback
            double lng = (l != null) ? l.getLongitude() : 28.97953;
            fetchPendingViaCF(lat, lng);  // zaten sende var
        });

        attachItemTouchHandlers();
        resolveRoleAndFetch();
    }
    // Sınıf içine, TAG altına ekle
    private static void logLong(String tag, String msg) {
        if (msg == null) return;
        final int max = 4000;
        for (int i = 0; i < msg.length(); i += max) {
            Log.i(tag, msg.substring(i, Math.min(i + max, msg.length())));
        }
    }

    private void resolveRoleAndFetch() {
        new Thread(() -> {
            String role = cf.refreshRole();
            runOnUiThread(() -> handleRole(role));
        }).start();
    }

    /**
     * RTDB'den tüm kayıtları oku ve listele
     */
    private void fetchAllFromRTDB() {
        pendingRef.get()
                .addOnSuccessListener(snap -> {
                    Log.i(TAG, "RTDB get() OK. children=" + snap.getChildrenCount());

                    items.clear();
                    souls.clear();
                    keys.clear();

                    int idx = 0;
                    for (DataSnapshot child : snap.getChildren()) {
                        idx++;
                        final String key = child.getKey();

                        @SuppressWarnings("unchecked")
                        Map<String, Object> map = (Map<String, Object>) child.getValue();
                        if (map == null) {
                            Log.i(TAG, "child[" + idx + "] key=" + key + " -> value=null (skip)");
                            continue;
                        }

                        // Ham JSON
                        JSONObject o = new JSONObject(map);
                        try { o.put("id", key); } catch (Exception ignore) {}
                        String raw = o.toString();

                        // Uzun JSON'u parça parça logla (Logcat limiti ~4K)
                        final int CHUNK = 4000;
                        for (int i = 0; i < raw.length(); i += CHUNK) {
                            Log.i(TAG, "child[" + idx + "] key=" + key + " json=" +
                                    raw.substring(i, Math.min(i + CHUNK, raw.length())));
                        }

                        // Parse → Soul
                        Soul s = Soul.fromJson(o);
                        if (s == null) {
                            // Fallback: alan adları farklı ise minimum alanlarla oluştur
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

                        // Parse özeti
                        Log.i(TAG, "parsed[" + idx + "] key=" + key
                                + " species=" + safe(s.getSpecies())
                                + " finder="  + safe(s.getFinderName())
                                + " ts="      + s.getTimestamp());

                        // Liste öğeleri
                        keys.add(key);
                        souls.add(s);

                        String text = "Tür: " + safe(s.getSpecies())
                                + "\nKayıt sahibi: " + safe(s.getFinderName())
                                + "\nKonum: " + safe(s.getFoundLocation());
                        items.add(new Content(s.getImageResId(), text, 0, 0, true));
                    }

                    adapter.notifyDataSetChanged();
                    Log.i(TAG, "RTDB loaded count=" + keys.size());
                    Helpers.showToastSafe(this, "ExplorePrivate: " + keys.size() + " kayıt yüklendi");
                })
                .addOnFailureListener(e -> {
                    Log.i(TAG, "RTDB get() FAILED: " + e.getMessage(), e);
                    Helpers.showToastSafe(this, "RTDB hata: " + e.getMessage());
                });
    }


    private void handleRole(String role) {
        if (role == null || !isAllowed(role)) {
            Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        userRole = normalizeRole(role);
        Log.i(TAG, "role ok => " + userRole + " | source=RTDB(all)");


        // İSTEK: CheckPendingCompanion ile kontrol ettiğin RTDB yolundaki TÜM kayıtlar
        // => Konum istemeden doğrudan RTDB'den tamamını çekiyoruz.

        try {
            Log.e("HandleRole", "Fetching...");

            fetchAllFromRTDB();
        } catch (Exception e) {
            Log.e("Error:",e.getMessage());
        }

        // İstersen aşağıdaki konum bazlı CF çağrısını menüden 'Yakınımdakiler' seçeneği olarak koruyabilirsin.
        // fetchPendingViaCF(41.015137, 28.97953);
    }

    private void fetchPendingViaCF(double lat, double lng) {
        new Thread(() -> {
            try {
                JSONObject json = cf.listPendingCompanions(lat, lng, 50000, 100);
                runOnUiThread(() -> handlePendingResponse(json));
            } catch (Exception e) {
                Log.e(TAG, "CF error", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Cloud Function hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void handlePendingResponse(@NonNull JSONObject json) {
        boolean ok = json.optBoolean("success", json.optBoolean("ok", false));
        if (!ok) {
            Helpers.showToastSafe(this, json.optString("error", "CF hata"));
            return;
        }

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
            if (s == null) s = new Soul(
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
            souls.add(s);

            String text = "Tür: " + safe(s.getSpecies())
                    + "\nKayıt sahibi: " + safe(s.getFinderName())
                    + "\nKonum: " + safe(s.getFoundLocation());
            items.add(new Content(s.getImageResId(), text, 0, 0, true));
        }

        adapter.notifyDataSetChanged();
        Helpers.showToastSafe(this, "ExplorePrivate: " + n + " kayıt yüklendi");
    }

    private void attachItemTouchHandlers() {
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            final GestureDetector detector = new GestureDetector(ExplorePrivate.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onSingleTapUp(MotionEvent e) { return true; }
                        @Override public void onLongPress(MotionEvent e) {
                            View child = recyclerView.findChildViewUnder(e.getX(), e.getY());
                            if (child != null) {
                                int pos = recyclerView.getChildAdapterPosition(child);
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

        Soul soul = souls.get(position);
        String key = keys.get(position);

        Intent i = new Intent(this, com.kurmez.iyesi.umay.sahiplendirme.Companion.class);
        i.putExtra("requestKey", key);
        i.putExtra("node", "soul_inneed");
        i.putExtra("species", soul.getSpecies());
        i.putExtra("foundDate", soul.getFoundDate());
        i.putExtra("foundLocation", soul.getFoundLocation());
        i.putExtra("imageResId", soul.getImageResId());
        startActivity(i);
    }

    private void showQuickActionsDialog(int position) {
        if (position < 0 || position >= keys.size()) return;
        String key = keys.get(position);

        String[] roles = {"İye", "Körmös", "Ülgen", "Tengri"};
        new AlertDialog.Builder(this)
                .setTitle("Hızlı İşlemler")
                .setItems(new String[]{
                        "Bu kaydı tamamla",
                        "Rolü değiştir…",
                        "Sil"
                }, (d, which) -> {
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
        pendingRef.child(key).child("status").setValue("completed")
                .addOnSuccessListener(v -> Toast.makeText(this, "Tamamlandı ✓", Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void updateCurrentRole(@NonNull String key, @NonNull String newRole) {
        pendingRef.child(key).child("currentRole").setValue(newRole)
                .addOnSuccessListener(v -> Toast.makeText(this, "Rol güncellendi: " + newRole, Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void deletePending(@NonNull String key) {
        new AlertDialog.Builder(this)
                .setTitle("Silinsin mi?")
                .setMessage("Bu kaydı kalıcı olarak silmek istiyor musunuz?")
                .setPositiveButton("Sil", (d, w) ->
                        pendingRef.child(key).removeValue()
                                .addOnSuccessListener(v -> Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show())
                                .addOnFailureListener(e -> Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show()))
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private boolean isAllowed(String roleRaw) {
        if (roleRaw == null) return false;
        for (String r : ALLOWED_ROLES) {
            if (r.equalsIgnoreCase(roleRaw)) return true;
        }
        return false;
    }

    private String normalizeRole(String r) {
        if (r == null) return "İye";
        if (r.equalsIgnoreCase("Tengri")) return "Tengri";
        if (r.equalsIgnoreCase("Ülgen") || r.equalsIgnoreCase("ULGEN") || r.equalsIgnoreCase("ÜLGEN") || r.equalsIgnoreCase("Ulgen")) return "Ülgen";
        if (r.equalsIgnoreCase("Körmös") || r.equalsIgnoreCase("Körmes") || r.equalsIgnoreCase("Kormos") || r.equalsIgnoreCase("KÖRMÖS") || r.equalsIgnoreCase("KÖRMES") || r.equalsIgnoreCase("KORMOS")) return "Körmös";
        return "İye";
    }

    private String safe(String s) {
        return s == null ? "-" : s;
    }
}
