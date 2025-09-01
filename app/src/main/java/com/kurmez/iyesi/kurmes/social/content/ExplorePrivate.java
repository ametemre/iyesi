package com.kurmez.iyesi.kurmes.social.content;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import org.json.JSONObject;
import org.json.JSONArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.social.Profile;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.ContentAdapter;
import com.kurmez.iyesi.umay.sahiplendirme.Soul;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
// imports ekleyin:
import java.text.SimpleDateFormat;
import java.util.Date;
// imports (dosyanın başına ekle)
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.widget.ScrollView;
// …
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Pending/Companion kuyruğundaki kayıtları, kullanıcının rolüne göre listeler.
 * Tengri: tümünü görür. Diğer roller: currentRole == kullanıcı rolü.
 * Tıklayınca düzenleme ekranına yönlendirir (Companion activity örneği).
 * Uzun basma ile hızlı aksiyonlar (rol değiştir, tamamla) sunar.
 */
public class ExplorePrivate extends AppCompatActivity {
    private static final String TAG = "ExplorePrivate";
    private CFHelper cf;
    // alanlara ekleyin
    private Spinner dbPathSpinner;
    private ArrayAdapter<String> pathAdapter;
    private final List<String> pathItems = new ArrayList<>();
    private final Deque<String> pathStack = new ArrayDeque<>();
    private DatabaseReference browseRef; // gezginin o anki referansı
    // sınıf içine ekle
    private static final String DUMP_PREFIX = "👁 Döküm: ";
    private static final int DUMP_MAX_DEPTH = 6;       // güvenli derinlik
    private static final int DUMP_MAX_CHILDREN = 200;  // düğüm başına limit
    // Desteklenen roller (çeşitli yazım varyantlarıyla)
    private static final List<String> allowedRoles = Arrays.asList(
            "İye", "İYE", "iye",
            "Körmös", "Körmes", "Kormos", "KORMOS", "KÖRMÖS", "KÖRMES",
            "Ülgen", "ULGEN", "ÜLGEN", "Ulgen",
            "Tengri", "TENGRI", "TENGRİ", "Tengri"
    );
    private Query activeQuery = null;
    private ValueEventListener pendingListener = null;

    private RecyclerView recyclerView;
    private ContentAdapter adapter;
    private final List<Content> items = new ArrayList<>();
    private final List<Soul> souls    = new ArrayList<>();
    private final List<String> keys   = new ArrayList<>();
    private FirebaseAuth auth = FirebaseAuth.getInstance();;
    private FirebaseUser user = auth.getCurrentUser();
    private FirebaseFirestore firestore;

    private DatabaseReference pendingRef;

    private String userRole = null; // normalize edilmiş rol (görünen hâli)

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_private);
        // Auth
        if (user == null) {
            Toast.makeText(this, "Bu sayfayı görüntülemek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        firestore = FirebaseFirestore.getInstance();

        // UI
        recyclerView = findViewById(R.id.recycler_private_explore);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // NOT: 'items' yerine sınıf alanınız hangi listeyse onu verin (örn. contentList)
        adapter = new ContentAdapter(items, this);

        recyclerView.setAdapter(adapter);

        View profileHeader = findViewById(R.id.profile_header);
        if (profileHeader != null) {
            profileHeader.setOnClickListener(v -> startActivity(new Intent(ExplorePrivate.this, Profile.class)));
        }
        attachItemTouchHandlers();
        dbPathSpinner = findViewById(R.id.spinner_db_path);
        pathAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, pathItems);
        dbPathSpinner.setAdapter(pathAdapter);

        initPathBrowser(); // aşağıda
/*

*/
        // Rolü al → erişim yetkisi kontrol → listeyi bağla
        // CFHelper
        cf = new CFHelper(this, "iyesi-a651a", null);
        // Rolü arka planda çek → yetki kontrolü → konum → CF çağrısı
        new Thread(() -> {
            String role = cf.refreshRole(); // HTTP /getRole
            runOnUiThread(() -> {
                if (role == null) {
                    Toast.makeText(this, "Rol atanmadı!", Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                if (!isAllowed(role)) {
                    Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                userRole = normalizeRole(role);
                // Konum al ve listeyi CF'den çek
                FusedLocationProviderClient loc = LocationServices.getFusedLocationProviderClient(this);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, 42);
                } else {
                    loc.getLastLocation().addOnSuccessListener(l -> {
                        if (l != null) {
                            fetchPendingViaCF(l.getLatitude(), l.getLongitude());
                        } else {
                            fetchPendingViaCF(41.015137, 28.979530); // İstanbul fallback
                        }
                    }).addOnFailureListener(e -> {
                        Helpers.showToastSafe(this, "Konum alınamadı, varsayılan kullanılacak");
                        fetchPendingViaCF(41.015137, 28.979530);
                    });
                }
            });
                }).start();
    }

    private void checkRoleAndGetContents() {

        try {
            Helpers.getRoleFunction()
                    .addOnSuccessListener(role -> {
                        try {
                            if ("Ülgen".equals(role) || "Tengri".equals(role)) {
                                Log.d(TAG, "getRoleFunction() success → role=" + role);
                                userRole = normalizeRole(role);

                                //attachPendingListener(userRole); // aşağıdaki try/catch'li sürüm
                                FusedLocationProviderClient loc = LocationServices.getFusedLocationProviderClient(this);
                                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                    ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, 42);
                                } else {
                                    loc.getLastLocation().addOnSuccessListener(l -> {
                                        if (l != null) {
                                            fetchPendingViaCF(l.getLatitude(), l.getLongitude());
                                        } else {
                                            // fallback: İstanbul koordinatı gibi bir varsayılan
                                            fetchPendingViaCF(41.015137, 28.979530);
                                        }
                                    }).addOnFailureListener(e -> {
                                        Helpers.showToastSafe(this, "Konum alınamadı, varsayılan kullanılacak");
                                        fetchPendingViaCF(41.015137, 28.979530);
                                    });
                                }                            } else {
                                Toast.makeText(this,
                                        "Bu işlemi sadece Ülgen ve Tengri yapabilir.",
                                        Toast.LENGTH_LONG).show();
                            }
                            if (role == null) {
                                Toast.makeText(this, "Rol atanmadı!", Toast.LENGTH_SHORT).show();
                                finish();
                                return;
                            }

                            if (!isAllowed(role)) {
                                Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_LONG).show();
                                finish();
                                return;
                            }
                        } catch (Exception inner) {
                            Log.e(TAG, "Role success branch error", inner);
                            Toast.makeText(this, "Rol işlenemedi: " + inner.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "getRoleFunction() failure", e);
                        Toast.makeText(this, "Rol sorgusu hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        finish();
                    });
        } catch (Exception outer) {
            Log.e(TAG, "getRoleFunction() call threw", outer);
            Toast.makeText(this, "Rol sorgusu başlatılamadı: " + outer.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pendingRef != null && pendingListener != null) {
            pendingRef.removeEventListener(pendingListener);
        }
    }

    // ---------------------------
    // Liste ve tıklamalar
    // ---------------------------
    private void attachItemTouchHandlers() {
        GestureDetector tapDetector = new GestureDetector(
                this,
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

        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                View child = rv.findChildViewUnder(e.getX(), e.getY());
                if (child != null && tapDetector.onTouchEvent(e)) {
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

    @Nullable private Soul mapSnapshotToSoul(DataSnapshot snap){ return snap.getValue(Soul.class); }

    private void fetchPendingViaCF(double lat, double lng) {
        Helpers.showToastSafe(this, "ExplorePrivate: CF çağrısı başlıyor…");
        new Thread(() -> {
            try {
                // CFHelper: Auth + AppCheck + token refresh içerden
                        JSONObject json = cf.listPendingCompanions(lat, lng, /*radiusM=*/50000, /*limit=*/100);
                // JSON’u UI thread’de işle
                        runOnUiThread(() -> handlePendingResponse(json));
            } catch (Exception ex) {
                Log.e(TAG, "CF hata", ex);
                runOnUiThread(() ->
                        Helpers.showToastSafe(ExplorePrivate.this, "ExplorePrivate: CF hata: " + ex.getMessage()));
            }
        }).start();
    }
    private void handlePendingResponse(@NonNull JSONObject json) {
        try {
            // 1) success || ok
            final boolean ok = json.optBoolean("success", json.optBoolean("ok", false));
            if (!ok) {
                Helpers.showToastSafe(this, json.optString("error", "CF hata"));
                return;
            }

            // 2) items
            final JSONArray arr = json.optJSONArray("items");
            final int n = (arr != null) ? arr.length() : 0;

            items.clear(); keys.clear(); souls.clear();

            for (int i = 0; i < n; i++) {
                final JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;

                // id || key (boşsa atla)
                final String id = o.optString("id", o.optString("key", ""));
                if (TextUtils.isEmpty(id)) continue;
                keys.add(id);

                // Önce fromJson dene, yoksa manuel map
                Soul s = Soul.fromJson(o);
                if (s == null) {
                    String species       = o.optString("species", "ToDo");
                    String breed         = o.optString("breed",   "ToDo");
                    String age           = o.optString("age",     "ToDo");
                    String health        = o.optString("health",  "ToDo");
                    String foundDate     = o.optString("foundDate", "");
                    String foundLocation = o.optString("foundLocation", "");
                    String imageResId    = o.optString("imageResId", "");
                    String finderName    = o.optString("finderName", "");
                    long   timestamp     = o.optLong("timestamp", 0L);

                    s = new Soul(
                            /*name*/ null,
                            species, breed, age, health,
                            foundDate, foundLocation,
                            /*veterinary*/ null,
                            imageResId, finderName, timestamp
                    );
                }
                souls.add(s);

                // Satır metni
                String text = "Tür: " + safe(s.getSpecies())
                        + "\nKayıt sahibi: " + safe(s.getFinderName())
                        + "\nKonum: " + safe(s.getFoundLocation());

                items.add(new Content(o.optString("imageResId", ""), text, 0, 0, true));
            }

            adapter.notifyDataSetChanged();
            Helpers.showToastSafe(this, "ExplorePrivate: " + n + " kayıt yüklendi");

        } catch (Exception ex) {
            Log.e(TAG, "CF parse hatası", ex);
            Helpers.showToastSafe(this, "ExplorePrivate: parse hatası: " + ex.getMessage());
        }
    }

    private void loadFromRtdb(@NonNull String absPath) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference().child(absPath.startsWith("/") ? absPath.substring(1) : absPath);

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                items.clear(); keys.clear(); souls.clear();
                for (DataSnapshot child : snap.getChildren()) {
                    Soul s = mapSnapshotToSoul(child); // varsa mevcut mapper'ınız
                    if (s != null) {
                        items.add(new Content(null,
                                "Tür: " + safe(s.getSpecies())
                                        + "\nKayıt sahibi: " + safe(s.getFinderName())
                                        + "\nKonum: " + safe(s.getFoundLocation()),
                                0, 0, true));
                    } else {
                        // Alanlar eksikse de görün: generic fallback
                        items.add(new Content(null, "Key: " + child.getKey() + "\n" + String.valueOf(child.getValue()), 0, 0, true));
                    }
                    keys.add(child.getKey());
                }
                adapter.notifyDataSetChanged();
                Helpers.showToastSafe(ExplorePrivate.this, "RTDB: " + snap.getChildrenCount() + " kayıt yüklendi");
            }
            @Override public void onCancelled(@NonNull DatabaseError e) {
                Helpers.showToastSafe(ExplorePrivate.this, "RTDB okuma hatası: " + e.getMessage());
            }
        });
    }
    private boolean onHeaderMenuItem(MenuItem item) {
        if (item.getItemId() == R.id.action_filter) {
            Toast.makeText(this, "Filtre uygulandı", Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    // ---------------------------
    // DB dinleyici
    // ---------------------------
    private void attachPendingListener(@NonNull String role) {
        Helpers.ConversationHeaderHelper.setupHeader(
                this,
                R.menu.menu_explore_private_options,
                this::onHeaderMenuItem
        );

        // RTDB path: Pending/Companion/soul_inneed
        pendingRef = FirebaseDatabase.getInstance()
                .getReference()
                .child("Pending")
                .child("Companion")
                .child("soul_inneed");
        try {
            // BAŞLANGIÇ LOGU (çağrı girişi)
            Helpers.showToastSafe(ExplorePrivate.this, "ExplorePrivate: veri çekiliyor… role=" + role);

            Query q = isSuperRole(role) ? pendingRef : pendingRef.orderByChild("currentRole").equalTo(role);

            if (pendingListener != null && activeQuery != null) {
                try {
                    activeQuery.removeEventListener(pendingListener);
                    Log.d(TAG, "Eski listener kaldırıldı: " + activeQuery.getRef().getPath());
                } catch (Exception rmEx) {
                    Log.w(TAG, "Listener kaldırılamadı (devam)", rmEx);
                }
            }

            pendingListener = new ValueEventListener() {
                @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                    final long t0 = android.os.SystemClock.uptimeMillis();

                    // ORTA LOG (onDataChange başlangıcı)
                    Helpers.showToastSafe(ExplorePrivate.this,
                            "ExplorePrivate: snapshot geldi (" + snapshot.getChildrenCount() + ")");

                    try {
                        items.clear();
                        souls.clear();
                        keys.clear();

                        for (DataSnapshot child : snapshot.getChildren()) {
                            String status = child.child("status").getValue(String.class);
                            if ("completed".equalsIgnoreCase(status)) continue;

                            Soul soul = mapSnapshotToSoul(child);
                            if (soul == null) continue;

                            if (!isSuperRole(role)) {
                                String cr = child.child("currentRole").getValue(String.class);
                                if (!matchesRoleAlias(cr, role)) continue;
                            }

                            String key = child.getKey();
                            String text = "Tür: " + safe(soul.getSpecies())
                                    + "\nKayıt sahibi: " + safe(soul.getFinderName())
                                    + "\nKonum: " + safe(soul.getFoundLocation());

                            items.add(new Content(soul.getImageResId(), text, 0, 0, true));
                            souls.add(soul);
                            keys.add(key);
                        }

                        adapter.notifyDataSetChanged();

                        // BİTİŞ LOGU (çağrı çıkışı)
                        long dt = android.os.SystemClock.uptimeMillis() - t0;
                        Helpers.showToastSafe(ExplorePrivate.this,
                                "ExplorePrivate: yükleme tamam • " + items.size() + " kayıt • " + dt + "ms");
                    } catch (Exception ex) {
                        Log.e(TAG, "onDataChange işlem hatası", ex);
                        Helpers.showToastSafe(ExplorePrivate.this,
                                "ExplorePrivate: işlem hatası: " + ex.getMessage());
                    }
                }

                @Override public void onCancelled(@NonNull DatabaseError error) {
                    Log.e(TAG, "Veri okunamadı", error.toException());
                    Helpers.showToastSafe(ExplorePrivate.this,
                            "ExplorePrivate: Firebase iptal/hata: " + error.getMessage());
                }
            };

            activeQuery = q;
            q.addValueEventListener(pendingListener);
            Log.d(TAG, "Yeni listener eklendi. Path=" + pendingRef.getPath()
                    + " superRole=" + isSuperRole(role));
        } catch (Exception outer) {
            Log.e(TAG, "attachPendingListener() başarısız", outer);
            Helpers.showToastSafe(ExplorePrivate.this,
                    "ExplorePrivate: bağlanamadı: " + outer.getMessage());
        }
    }



    // ---------------------------
    // Düzenleme / Hızlı aksiyonlar
    // ---------------------------
    private void openEditor(int position) {
        if (position < 0 || position >= keys.size()) return;

        Soul soul = souls.get(position);
        String key = keys.get(position);

        // Burada kendi detay/düzenleme ekranına yönlendirebilirsin.
        // Aşağıda Companion aktivitesine örnek bir geçiş var:
        Intent i = new Intent(ExplorePrivate.this, com.kurmez.iyesi.umay.sahiplendirme.Companion.class);
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

        final String[] roles = new String[]{"İye", "Körmös", "Ülgen", "Tengri"};
        AlertDialog.Builder b = new AlertDialog.Builder(this)
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
                            // Rol seçtirme
                            new AlertDialog.Builder(this)
                                    .setTitle("Yeni Rol Seç")
                                    .setItems(roles, (d2, idx) -> updateCurrentRole(key, roles[idx]))
                                    .show();
                            break;
                        case 2:
                            deletePending(key);
                            break;
                    }
                });
        b.show();
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
                                .addOnFailureListener(e -> Toast.makeText(this, "Hata: " + e.getMessage(), Toast.LENGTH_SHORT).show())
                )
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    // ---------------------------
    // Yardımcılar
    // ---------------------------
    private boolean isAllowed(String roleRaw) {
        if (roleRaw == null) return false;
        for (String r : allowedRoles) {
            if (r.equalsIgnoreCase(roleRaw)) return true;
        }
        return false;
    }

    private String normalizeRole(String r) {
        if (r == null) return "İye";
        String n = r.trim();
        if (equalsAny(n, "Tengri", "TENGRI", "TENGRİ")) return "Tengri";
        if (equalsAny(n, "Ülgen", "ULGEN", "ÜLGEN", "Ulgen")) return "Ülgen";
        if (equalsAny(n, "Körmös", "Körmes", "Kormos", "KÖRMÖS", "KÖRMES", "KORMOS")) return "Körmös";
        return "İye";
    }

    public static boolean isSuperRole(String role) {
        // Tengri = süper yetki
        return "Tengri".equalsIgnoreCase(role);
    }

    private boolean matchesRoleAlias(String currentRole, String wantedRole) {
        if (currentRole == null || wantedRole == null) return false;
        String cr = normalizeRole(currentRole);
        String wr = normalizeRole(wantedRole);
        return cr.equalsIgnoreCase(wr);
    }

    private boolean equalsAny(String s, String... arr) {
        for (String a : arr) if (a.equalsIgnoreCase(s)) return true;
        return false;
    }
    private static final String UP_ITEM = "↩︎ .. (yukarı)";
    private static final String USE_ITEM = "📍 Bu yolu kullan";

    private String currentPathStr(Context context) {
        if (pathStack.isEmpty()) return "";
        return TextUtils.join("/", pathStack);
    }

    private void initPathBrowser() {
        pathItems.clear();
        pathItems.add("Yol seç…");

        // 1) ÖNCE ZORLA DOLDUR (hemen görünür olacak)
        String[] candidates = {
                "/Pending/Companion/soul_inneed",
                "/Pending/Companion",
                "/markers", "/markerInteractions", "/souls", "/users", "/roles"
        };
        Collections.addAll(pathItems, candidates);
        pathAdapter.notifyDataSetChanged(); // Bu noktada "Yol seç + adaylar" görünmeli

        // 2) SONRA DOĞRULA: RTDB'de yoksa listeden çıkar
        for (String p : candidates) {
            tryReadOnce(p.substring(1), exists -> {
                if (!exists) { pathItems.remove(p); }
                pathAdapter.notifyDataSetChanged();
            });
        }

        dbPathSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String pick = pathItems.get(pos);

                if (pick.startsWith(DUMP_PREFIX)) {            // "👁 Döküm: /..."
                    String absPath = pick.substring(DUMP_PREFIX.length()).trim();
                    dumpPathAsJson(absPath);
                    return;
                }

                if (pick.startsWith("/")) {                    // "/Pending/Companion" veya "/.../child"
                    attachListToPath(pick);                      // ⚠️ YENİ: Listeyi bu yola bağla
                    DatabaseReference ref = FirebaseDatabase.getInstance()
                            .getReference().child(pick.substring(1));
                    loadChildrenIntoSpinner(ref, pick);          // Gezgin için çocukları güncelle
                    return;
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private void attachListToPath(@NonNull String absPath) {
        // Eski dinleyiciyi kapat
        try {
            if (pendingListener != null && activeQuery != null) {
                activeQuery.removeEventListener(pendingListener);
            }
        } catch (Exception ignore) {}

        pendingRef = FirebaseDatabase.getInstance()
                .getReference().child(absPath.startsWith("/") ? absPath.substring(1) : absPath);

        // İLK AŞAMA: filtresiz oku (currentRole alanı eksikse de kayıt gelir)
        activeQuery = pendingRef;

        pendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                items.clear(); keys.clear(); souls.clear();

                for (DataSnapshot child : snapshot.getChildren()) {
                    Soul s = mapSnapshotToSoul(child);    // varsa mevcut mappingini kullan
                    if (s != null) {
                        items.add(new Content(
                                child.child("imageResId").getValue(String.class),
                                "Tür: " + safe(s.getSpecies())
                                        + "\nKayıt sahibi: " + safe(s.getFinderName())
                                        + "\nKonum: " + safe(s.getFoundLocation()),
                                0, 0, true));
                    } else {
                        // Fallback: generic gösterim (mapping alanları yoksa)
                        items.add(new Content(
                                null,
                                "Key: " + child.getKey() + "\n" + String.valueOf(child.getValue()),
                                0, 0, true));
                    }
                    keys.add(child.getKey());
                }

                adapter.notifyDataSetChanged();
                Helpers.showToastSafe(ExplorePrivate.this,
                        "Bağlandı: " + absPath + " — " + snapshot.getChildrenCount() + " kayıt");
            }

            @Override public void onCancelled(@NonNull DatabaseError error) {
                Helpers.showToastSafe(ExplorePrivate.this, "Listeleme hatası: " + error.getMessage());
            }
        };

        activeQuery.addValueEventListener(pendingListener);
    }

    private void dumpPathAsJson(@NonNull String absPath) {
        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference().child(absPath.startsWith("/") ? absPath.substring(1) : absPath);

        Helpers.showToastSafe(this, "Döküm hazırlanıyor: " + absPath);
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                try {
                    Object plain = toPlainValue(snap, /*depth=*/0);
                    String json = (plain instanceof Map)
                            ? new org.json.JSONObject((Map<?,?>) plain).toString(2)
                            : org.json.JSONObject.wrap(plain).toString();

                    showDumpDialog(absPath, json);
                } catch (Exception e) {
                    Helpers.showToastSafe(ExplorePrivate.this, "Döküm hatası: " + e.getMessage());
                }
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Helpers.showToastSafe(ExplorePrivate.this, "Döküm iptal/hata: " + error.getMessage());
            }
        });
    }

    private Object toPlainValue(@NonNull DataSnapshot snap, int depth) {
        if (!snap.hasChildren() || depth >= DUMP_MAX_DEPTH) {
            // yaprak ya da derinlik sınırı
            return snap.getValue();
        }
        // çocukları sırayla sakla (LinkedHashMap ile düzen korunsun)
        Map<String,Object> map = new LinkedHashMap<>();
        int count = 0;
        for (DataSnapshot c : snap.getChildren()) {
            if (count >= DUMP_MAX_CHILDREN) {
                map.put("…", "(+" + (snap.getChildrenCount() - count) + " daha)");
                break;
            }
            map.put(c.getKey(), toPlainValue(c, depth + 1));
            count++;
        }
        return map;
    }

    private void showDumpDialog(@NonNull String absPath, @NonNull String json) {
        TextView tv = new TextView(this);
        tv.setTextIsSelectable(true);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setText(json);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("RTDB Döküm — " + absPath)
                .setView(sv)
                .setPositiveButton("Kapat", null)
                .setNeutralButton("Kopyala", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("RTDB Dump " + absPath, json));
                        Helpers.showToastSafe(this, "Panoya kopyalandı");
                    }
                })
                .show();
    }


    private void tryReadOnce(String path, java.util.function.Consumer<Boolean> cb) {
        FirebaseDatabase.getInstance().getReference().child(path)
                .limitToFirst(1)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override public void onDataChange(@NonNull DataSnapshot s) { cb.accept(s.exists()); }
                    @Override public void onCancelled(@NonNull DatabaseError e) { cb.accept(false); }
                });
    }

    private void loadChildrenIntoSpinner(DatabaseReference ref, String baseLabel) {
        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                pathItems.clear();
                pathItems.add(baseLabel);                       // başlık
                pathItems.add(DUMP_PREFIX + baseLabel);         // 👁 Döküm satırı
                for (DataSnapshot c : snap.getChildren()) {
                    pathItems.add(baseLabel + "/" + c.getKey());  // çocuklar
                }
                pathAdapter.notifyDataSetChanged();
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Helpers.showToastSafe(ExplorePrivate.this, "Yol okunamadı: " + error.getMessage());
            }
        });
    }



    private void loadChildrenIntoSpinner(Context context) {
        if (browseRef == null) return;
        browseRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snap) {
                pathItems.clear();
                if (!pathStack.isEmpty()) pathItems.add(UP_ITEM);
                pathItems.add(USE_ITEM);

                // Çocuk anahtarları
                for (DataSnapshot child : snap.getChildren()) {
                    pathItems.add(child.getKey());
                }
                pathAdapter.notifyDataSetChanged();
                Helpers.showToastSafe(context, "Yol: /" + currentPathStr(context) + "  (çocuklar: " + (snap.getChildrenCount()) + ")");
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Helpers.showToastSafe(context, "Yol okunamadı: " + error.getMessage());
            }
        });
    }

    /** Kullanıcı “📍 Bu yolu kullan” dediğinde yapılacak iş */
    private void onPathCommitted(@NonNull String absPath) {
        // 1) İstersen bu yolu ana listeleyiciye bağlayalım (RTDB listener’ı buraya tak)
        //    Var olan dinleyiciyi kapat, yeni yola geçir:
        try {
            if (pendingListener != null && activeQuery != null) {
                activeQuery.removeEventListener(pendingListener);
            }
        } catch (Exception ignore) {}

        pendingRef = FirebaseDatabase.getInstance().getReference().child(absPath.substring(1));
        // super rol değilse currentRole filtresi uygula (mevcut mantığınıza paralel)
        Query q = ExplorePrivate.isSuperRole(userRole) ? pendingRef : pendingRef.orderByChild("currentRole").equalTo(userRole);
        activeQuery = q;

        pendingListener = new ValueEventListener() {
            @Override public void onDataChange(@NonNull DataSnapshot snapshot) {
                items.clear(); souls.clear(); keys.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    // mevcut mapleme fonksiyonunuz:
                    Soul s = mapSnapshotToSoul(child); // sizde zaten var, aynıını kullanın :contentReference[oaicite:1]{index=1}
                    if (s == null) continue;
                    items.add(new Content(
                            child.child("imageResId").getValue(String.class),
                            "Tür: " + safe(s.getSpecies())
                                    + "\nKayıt sahibi: " + safe(s.getFinderName())
                                    + "\nKonum: " + safe(s.getFoundLocation()),
                            0, 0, true));
                    keys.add(child.getKey());
                    souls.add(s);
                }
                adapter.notifyDataSetChanged();
                Helpers.showToastSafe(ExplorePrivate.this, "Liste: " + snapshot.getChildrenCount() + " kayıt (/" + absPath + ")");
            }
            @Override public void onCancelled(@NonNull DatabaseError error) {
                Helpers.showToastSafe(ExplorePrivate.this, "Listeleme hatası: " + error.getMessage());
            }
        };
        q.addValueEventListener(pendingListener);
    }

    private String safe(String s) { return s == null ? "-" : s; }
}
