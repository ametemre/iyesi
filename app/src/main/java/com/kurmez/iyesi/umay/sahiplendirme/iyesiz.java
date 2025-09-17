// Companion.java
package com.kurmez.iyesi.umay.sahiplendirme;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Kayıt sonrası detay ekranı.
 * Öncelik: (1) Intent extras → anında göster, (2) RTDB by requestKey → kesin veriler,
 * (3) CF endpoint fallback (deviceId + node).
 *
 * Ek: Uzun basışla (image) CustomClaims inline-edit → updateClaims (callable) → token yenile → UI tazele
 */
public class iyesiz extends AppCompatActivity {

    /* ====== Intent Keys (Founded/ExplorePrivate ile uyumlu) ====== */
    public static final String EXTRA_SPECIES    = "species";
    public static final String EXTRA_BREED      = "breed";
    public static final String EXTRA_FOUNDDATE  = "foundDate";
    public static final String EXTRA_PLACE      = "foundPlace";
    public static final String EXTRA_PHOTO      = "photoUrl";
    public static final String EXTRA_WHO        = "profileId";   // TODO: UI'de "who" alanı username mi, profileId mi? (mevcut davranış korunur)
    public static final String EXTRA_STATUS     = "status";
    public static final String EXTRA_ID         = "id";
    public static final String EXTRA_DEVICE_ID  = "deviceId";    // opsiyonel
    public static final String EXTRA_NODE       = "node";        // opsiyonel (default: soul_inneed)
    public static final String EXTRA_REQUESTKEY = "requestKey";  // RTDB anahtarı

    /* ====== UI ====== */
    private ImageView img;
    private TextView tvTitle, tvDate, tvPlace, tvWho;

    /* ====== Net / Helpers ====== */
    private final OkHttpClient http = new OkHttpClient();
    private HeaderHelper headerHelper;

    /* ====== State ====== */
    private String node = "soul_inneed";
    private String requestKey;
    private String deviceId;

    /* ====== Claims Edit Entegrasyonu ====== */
    private FirebaseFunctions functions;
    private final Map<String, Object> claimCache = new HashMap<>();
    private boolean claimsLoadedOnce = false;

    private static final String FN_UPDATE_CLAIMS = "updateClaims";

    public static final class ClaimsKeys {
        public static final String UID        = "uid";
        public static final String ROLE       = "role";
        public static final String USERNAME   = "username";
        public static final String EMAIL      = "email";
        public static final String PHONE      = "phone";
        public static final String LOCATION   = "location";
        public static final String AVATAR_URL = "avatarUrl";
        private ClaimsKeys() {}
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_companion);

        headerHelper = new HeaderHelper(iyesiz.this);
        functions    = FirebaseFunctions.getInstance("us-central1");

        img     = findViewById(R.id.companion_image);
        tvTitle = findViewById(R.id.soul_companion);
        tvDate  = findViewById(R.id.found_Date);
        tvPlace = findViewById(R.id.found_place);
        tvWho   = findViewById(R.id.veterineary_ulgen);

        /* ---- 1) Intent’ten gelenleri anında göster ---- */
        String species   = nz(getIntent().getStringExtra(EXTRA_SPECIES));
        String breed     = nz(getIntent().getStringExtra(EXTRA_BREED));
        String foundDate = nz(getIntent().getStringExtra(EXTRA_FOUNDDATE));
        String place     = nz(getIntent().getStringExtra(EXTRA_PLACE));
        String photoUrl  = nz(getIntent().getStringExtra(EXTRA_PHOTO));
        String who       = nz(getIntent().getStringExtra(EXTRA_WHO));
        bindToUi(species, breed, foundDate, place, photoUrl, who);

        /* ---- 2) Parametreleri toparla ---- */
        node = nz(getIntent().getStringExtra(EXTRA_NODE));
        if (node.isEmpty()) node = "soul_inneed";
        requestKey = nz(getIntent().getStringExtra(EXTRA_REQUESTKEY));
        try {
            headerHelper.refreshHeader(iyesiz.this);
        } catch (Exception e) {
            Log.e("UserError", e.getMessage());
        }
        @SuppressLint("HardwareIds")
        String fallback = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        deviceId = nz(getIntent().getStringExtra(EXTRA_DEVICE_ID));
        if (deviceId.isEmpty()) deviceId = fallback;

        /* ---- 3) Veri tazeleme akışı ---- */
        if (!requestKey.isEmpty()) {
            fetchFromRTDB(requestKey, node);             // ana yol
        } else {
            fetchFromEndpoint(deviceId, node);           // yedek yol
        }

        /* ---- 4) Claims’i hazırla (UI açılışında 1 kez) ---- */
        preloadClaims();

        /* ---- 5) Uzun basışla profil düzenleme (Custom Claims) ---- */
        img.setOnLongClickListener(v -> {
            if (!claimsLoadedOnce) {
                Toast.makeText(this, "Profil verisi yükleniyor, tekrar deneyin.", Toast.LENGTH_SHORT).show();
                preloadClaims(); // arka planda da tetikle
                return true;
            }
            openClaimsEditDialog();
            return true;
        });
    }

    /* --------------------------------- UI --------------------------------- */

    private void bindToUi(String species, String breed,
                          String foundDate, String place,
                          String photoUrl, String who) {

        String title = species.isEmpty() ? "Companion" : species + (breed.isEmpty() ? "" : " • " + breed);
        tvTitle.setText(title);
        tvDate.setText(!foundDate.isEmpty() ? foundDate : "—");
        tvPlace.setText(!place.isEmpty() ? place : "—");

        // TODO: Mevcutta tvWho "profileId" gösteriyor gibi. Claims güncellemesi sonrası kullanıcı adı göstermek istenirse,
        //  aşağıda token yenileme sonrası tvWho'yu 'username' ile güncelliyoruz. İstersen bu davranışı kapatabilirsin.
        tvWho.setText(!who.isEmpty() ? who : "—");

        if (!photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.holder)
                    .error(R.drawable.holder)
                    .into(img);
        } else {
            img.setImageResource(R.drawable.holder);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /* ------------------------------ RTDB First ----------------------------- */

    /**
     * RTDB: Pending/Companion/{node}/{key}
     * ExplorePrivate ve Founded ile aynı şema.
     */
    private void fetchFromRTDB(String key, String node) {
        FirebaseDatabase.getInstance("https://iyesi-e8d4f-default-rtdb.firebaseio.com")
                .getReference("Pending/Companion")
                .child(node)
                .child(key)
                .get()
                .addOnSuccessListener(snap -> {
                    if (snap != null && snap.exists()) {
                        applySnapshotToUi(snap);
                    } else {
                        // Key yanlış / henüz yazılmadı → endpoint’e düş
                        fetchFromEndpoint(deviceId, node);
                    }
                })
                .addOnFailureListener(e -> {
                    // Ağ/rules hatası → endpoint’e düş
                    fetchFromEndpoint(deviceId, node);
                });
    }

    private void applySnapshotToUi(DataSnapshot snap) {
        String species    = nz(asString(snap.child("species")));
        String breed      = nz(asString(snap.child("breed")));
        String foundDate  = nz(asString(snap.child("foundDate")));
        String foundPlace = nz(asString(snap.child("foundLocation")));
        String photoUrl   = nz(asString(snap.child("imageResId")));
        if (photoUrl.isEmpty()) photoUrl = nz(asString(snap.child("imageUrl")));
        String who        = nz(asString(snap.child("finderName")));
        boolean approved  = asBoolean(snap.child("approved"), false);

        // intent’ten boş gelen alanlar varsa sunucu/RTDB değeriyle güncelle
        bindToUi(species, breed, foundDate, foundPlace, photoUrl, who);

        if (!approved) showWaitingPopup();
    }

    private static String asString(DataSnapshot n) {
        Object v = n.getValue();
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean asBoolean(DataSnapshot n, boolean def) {
        Object v = n.getValue();
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String)  return "true".equalsIgnoreCase((String) v);
        return def;
    }

    /* ------------------------------ HTTP Fallback ----------------------------- */

    private void fetchFromEndpoint(String deviceId, String node) {
        String url = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/getCompanionByDevice"
                + "?deviceId=" + deviceId
                + "&node=" + node;

        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(iyesiz.this, "Sunucuya bağlanılamadı", Toast.LENGTH_LONG).show());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;

                String body = response.body().string();
                try {
                    JSONObject j = new JSONObject(body);

                    String species    = nz(j.optString("species"));
                    String breed      = nz(j.optString("breed"));
                    String foundDate  = nz(j.optString("foundDate"));
                    String foundPlace = nz(j.optString("foundLocation"));
                    String photoUrl   = nz(j.optString("imageResId", j.optString("imageUrl")));
                    String who        = nz(j.optString("finderName", j.optString("finder")));
                    boolean approved  = j.optBoolean("approved", false);

                    runOnUiThread(() -> {
                        bindToUi(species, breed, foundDate, foundPlace, photoUrl, who);
                        if (!approved) showWaitingPopup();
                    });

                } catch (Exception ignore) {
                    runOnUiThread(() ->
                            Toast.makeText(iyesiz.this, "Veri çözülemedi", Toast.LENGTH_SHORT).show());
                }
            }
        });
    }

    /* ------------------------------ Dialog -------------------------------- */

    /** Ülgen onayı bekleniyor uyarısı */
    private void showWaitingPopup() {
        new AlertDialog.Builder(this)
                .setTitle("Waiting For Ülgen's Approval")
                .setMessage(
                        "Veterinarians in our community are checking your application and will respond to you ASAP with the necessary medical attention.\n\n" +
                                "Please do not touch, move, or pet the soul you have found. It may not look harmful, but street animals might carry diseases that could harm you, or you may harm the soul.\n\n" +
                                "You will receive the necessary professional help ASAP.")
                .setCancelable(false)
                .setNegativeButton("Close", (d, w) -> d.dismiss())
                .show();
    }

    /* ==============================
       =  Claims Edit Entegrasyonu  =
       ============================== */

    /** Açılışta 1 kez claims getir (mevcut token ile). */
    private void preloadClaims() {
        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        if (current == null || current.isAnonymous()) {
            // Anon kullanıcı: profil düzenleme kapalı
            claimsLoadedOnce = false;
            return;
        }
        current.getIdToken(false)
                .addOnSuccessListener(this::onTokenReadyForClaims)
                .addOnFailureListener(e -> Log.e("CompanionClaims", "Token alınamadı: " + e.getMessage()));
    }

    private void onTokenReadyForClaims(GetTokenResult tr) {
        Map<String, Object> claims = tr.getClaims();
        claimCache.clear();
        if (claims != null) claimCache.putAll(claims);
        claimsLoadedOnce = true;
        // DEBUG: Log.d("CompanionClaims", "loaded: " + claimCache);
    }

    /** Uzun basışla açılan basit inline-edit diyalogu (username/email/phone/location). */
    private void openClaimsEditDialog() {
        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        if (current == null || current.isAnonymous()) {
            Toast.makeText(this, "Giriş yapmalısın", Toast.LENGTH_LONG).show();
            return;
        }

        final LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        container.setPadding(pad, pad, pad, pad);

        // Basit edit alanları
        final EditField efUsername = new EditField(this, "Kullanıcı adı", getStringClaim(ClaimsKeys.USERNAME), InputType.TYPE_CLASS_TEXT);
        final EditField efEmail    = new EditField(this, "E-posta",       getStringClaim(ClaimsKeys.EMAIL),    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        final EditField efPhone    = new EditField(this, "Telefon",       getStringClaim(ClaimsKeys.PHONE),    InputType.TYPE_CLASS_PHONE);
        final EditField efLoc      = new EditField(this, "Konum",         getStringClaim(ClaimsKeys.LOCATION), InputType.TYPE_CLASS_TEXT);

        container.addView(efUsername.root);
        container.addView(efEmail.root);
        container.addView(efPhone.root);
        container.addView(efLoc.root);

        new AlertDialog.Builder(this)
                .setTitle("Profil Düzenle")
                .setView(container)
                .setPositiveButton("Kaydet", (d, w) -> {
                    Map<String, Object> updates = new HashMap<>();
                    updates.put(ClaimsKeys.USERNAME, efUsername.get());
                    updates.put(ClaimsKeys.EMAIL,    efEmail.get());
                    updates.put(ClaimsKeys.PHONE,    efPhone.get());
                    updates.put(ClaimsKeys.LOCATION, efLoc.get());
                    performClaimsUpdateFiltered(updates);
                })
                .setNegativeButton("İptal", (d, w) -> d.dismiss())
                .show();
    }

    /** Yalnızca değişen alanları server’a gönder → updateClaims → token yenile → UI tazele. */
    private void performClaimsUpdateFiltered(Map<String, Object> updatesRaw) {
        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        if (current == null || current.isAnonymous()) {
            Toast.makeText(this, "Giriş yapmalısın", Toast.LENGTH_LONG).show();
            return;
        }

        // uid/role asla gönderme
        updatesRaw.remove(ClaimsKeys.UID);
        updatesRaw.remove(ClaimsKeys.ROLE);

        // Filtre: yalnızca değişen alanlar
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> e : updatesRaw.entrySet()) {
            String key = e.getKey();
            String oldVal = getStringClaim(key);
            String newVal = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
            if (!equalsNullable(oldVal, newVal)) {
                filtered.put(key, newVal);
            }
        }

        if (filtered.isEmpty()) {
            Toast.makeText(this, "Değişiklik yok.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Basit client-side doğrulama (server tarafında whitelist+validation MUTLAKA olmalı)
        if (filtered.containsKey(ClaimsKeys.EMAIL)) {
            String e = String.valueOf(filtered.get(ClaimsKeys.EMAIL));
            if (!TextUtils.isEmpty(e) && !Patterns.EMAIL_ADDRESS.matcher(e).matches()) {
                Toast.makeText(this, "Geçersiz e-posta", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        // Gönderim
        Map<String, Object> payload = new HashMap<>();
        payload.put("updates", filtered);

        Toast.makeText(this, "Güncelleniyor…", Toast.LENGTH_SHORT).show();

        // İsteğe bağlı: çağrıdan önce yeni token (istek imzası gerekmiyorsa şart değil)
        current.getIdToken(true).addOnSuccessListener(tr -> {
            functions.getHttpsCallable(FN_UPDATE_CLAIMS)
                    .call(payload)
                    .addOnSuccessListener(this::onUpdateClaimsSuccess)
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Güncelleme başarısız: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Token alınamadı: " + e.getMessage(), Toast.LENGTH_LONG).show()
        );
    }

    private void onUpdateClaimsSuccess(HttpsCallableResult result) {
        FirebaseUser current = FirebaseAuth.getInstance().getCurrentUser();
        if (current == null) {
            Toast.makeText(this, "Kullanıcı oturumu yok.", Toast.LENGTH_LONG).show();
            return;
        }

        // Sunucu kabul etti → yeni claims’i görmek için token’ı yenile
        Task<GetTokenResult> t = current.getIdToken(true);
        t.addOnSuccessListener(tr -> {
            Map<String, Object> claims = tr.getClaims();
            claimCache.clear();
            if (claims != null) claimCache.putAll(claims);

            // UI tazelemesi (örnek): tvWho alanını username ile güncelle (isteğe bağlı)
            // TODO: Eğer tvWho her zaman "profileId" göstermeli ise bu satırı kaldır.
            String newUsername = getStringClaim(ClaimsKeys.USERNAME);
            if (!TextUtils.isEmpty(newUsername)) {
                tvWho.setText(newUsername);
            }

            Toast.makeText(this, "Profil güncellendi.", Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e ->
                Toast.makeText(this, "Token yenileme hatası: " + e.getMessage(), Toast.LENGTH_LONG).show()
        );
    }

    /* ----------------------------- Yardımcılar ----------------------------- */

    private String getStringClaim(String key) {
        Object v = claimCache.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean equalsNullable(String a, String b) {
        if (TextUtils.isEmpty(a) && TextUtils.isEmpty(b)) return true;
        if (a == null) return false;
        return a.equals(b);
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    /* Basit bir "label + edit" satırı (dialog için) */
    private static class EditField {
        final LinearLayout root;
        final TextView label;
        final androidx.appcompat.widget.AppCompatEditText input;

        EditField(AppCompatActivity act, String title, String initial, int inputType) {
            root = new LinearLayout(act);
            root.setOrientation(LinearLayout.VERTICAL);
            int p = Math.round(8 * act.getResources().getDisplayMetrics().density);
            root.setPadding(0, p, 0, p);

            label = new TextView(act);
            label.setText(title);
            label.setTextSize(14f);

            input = new androidx.appcompat.widget.AppCompatEditText(act);
            input.setSingleLine(true);
            input.setInputType(inputType);
            input.setText(initial == null ? "" : initial);
            input.setHint(title);

            root.addView(label);
            root.addView(input);
        }

        String get() {
            return input.getText() == null ? "" : input.getText().toString().trim();
        }
    }
}
