// Companion.java
package com.kurmez.iyesi.umay.sahiplendirme;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Iye;
import com.kurmez.iyesi.kurmes.utilities.helper.HeaderHelper;

import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * Kayıt sonrası detay ekranı.
 * Öncelik: (1) Intent extras → anında göster, (2) RTDB by requestKey → kesin veriler,
 * (3) CF endpoint fallback (deviceId + node).
 */
public class Companion extends AppCompatActivity {

    /* ====== Intent Keys (Founded/ExplorePrivate ile uyumlu) ====== */
    public static final String EXTRA_SPECIES    = "species";
    public static final String EXTRA_BREED      = "breed";
    public static final String EXTRA_FOUNDDATE  = "foundDate";
    public static final String EXTRA_PLACE      = "foundPlace";
    public static final String EXTRA_PHOTO      = "photoUrl";
    public static final String EXTRA_WHO        = "profileId";
    public static final String EXTRA_STATUS     = "status";
    public static final String EXTRA_ID         = "id";
    public static final String EXTRA_DEVICE_ID  = "deviceId";   // opsiyonel
    public static final String EXTRA_NODE       = "node";       // opsiyonel (default: soul_inneed)
    public static final String EXTRA_REQUESTKEY = "requestKey"; // RTDB anahtarı

    /* ====== UI ====== */
    private ImageView img;
    private TextView tvTitle, tvDate, tvPlace, tvWho;

    /* ====== Net ====== */
    private final OkHttpClient http = new OkHttpClient();
    private HeaderHelper headerHelper;
    /* ====== State ====== */
    private String node = "soul_inneed";
    private String requestKey;
    private String deviceId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_companion);
        headerHelper = new HeaderHelper(Companion.this);

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
            //headerHelper.refreshHeader(Companion.this,null);
            headerHelper.refreshHeaderWithIye(null, new Iye());
        } catch (Exception e) {
            Log.e("UserError",e.getMessage());
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
    }

    /* --------------------------------- UI --------------------------------- */

    private void bindToUi(String species, String breed,
                          String foundDate, String place,
                          String photoUrl, String who) {

        String title = species.isEmpty() ? "Companion" : species + (breed.isEmpty() ? "" : " • " + breed);
        tvTitle.setText(title);
        tvDate.setText(!foundDate.isEmpty() ? foundDate : "—");
        tvPlace.setText(!place.isEmpty() ? place : "—");
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
        FirebaseDatabase.getInstance("https://iyesi-aef03-default-rtdb.firebaseio.com")
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
        String url = "https://us-central1-iyesi-aef03.cloudfunctions.net/getCompanionByDevice"
                + "?deviceId=" + deviceId
                + "&node=" + node;

        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(Companion.this, "Sunucuya bağlanılamadı", Toast.LENGTH_LONG).show());
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
                            Toast.makeText(Companion.this, "Veri çözülemedi", Toast.LENGTH_SHORT).show());
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
}
