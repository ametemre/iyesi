// Companion.java
package com.kurmez.iyesi.umay.sahiplendirme;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.kurmez.iyesi.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Companion extends AppCompatActivity {

    // ---- Intent extra key'leri (Welcome ile bire bir) ----
    public static final String EXTRA_SPECIES   = "species";
    public static final String EXTRA_BREED     = "breed";
    public static final String EXTRA_FOUNDDATE = "foundDate";
    public static final String EXTRA_PLACE     = "foundPlace";
    public static final String EXTRA_PHOTO     = "photoUrl";
    public static final String EXTRA_WHO       = "profileId";
    public static final String EXTRA_STATUS    = "status";
    public static final String EXTRA_ID        = "id";
    public static final String EXTRA_DEVICE_ID = "deviceId";   // opsiyonel
    public static final String EXTRA_NODE      = "node";       // opsiyonel (default: soul_inneed)

    // ---- UI referansları ----
    private ImageView imgCompanion;
    private TextView tvCompanion;
    private TextView tvDate;
    private TextView tvPlace;
    private TextView tvWho;

    // ---- Ağ ----
    private final OkHttpClient http = new OkHttpClient();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_companion);

        // UI bind
        imgCompanion = findViewById(R.id.companion_image);
        // R.id.soul_text bir ImageView (logo) — kullanıma gerek yok ama layout'ta kalabilir
        tvCompanion  = findViewById(R.id.soul_companion);
        tvDate       = findViewById(R.id.found_Date);   // XML'de D büyük
        tvPlace      = findViewById(R.id.found_place);
        tvWho        = findViewById(R.id.veterineary_ulgen);

        // 1) Intent'ten gelen veriyi hemen göster (anında feedback)
        Intent in = getIntent();
        String species   = nz(in.getStringExtra(EXTRA_SPECIES));
        String breed     = nz(in.getStringExtra(EXTRA_BREED));
        String foundDate = nz(in.getStringExtra(EXTRA_FOUNDDATE));
        String place     = nz(in.getStringExtra(EXTRA_PLACE));
        String photoUrl  = nz(in.getStringExtra(EXTRA_PHOTO));
        String who       = nz(in.getStringExtra(EXTRA_WHO));
        // String status = nz(in.getStringExtra(EXTRA_STATUS)); // istersen bir TextView'a yaz

        bindToUi(species, breed, foundDate, place, photoUrl, who);

        // 2) Opsiyonel: cihaz/node ile backend'den güncel durum çek
        String deviceId = in.getStringExtra(EXTRA_DEVICE_ID);
        String node     = in.getStringExtra(EXTRA_NODE);
        if (deviceId == null) {
            @SuppressLint("HardwareIds")
            String fallbackDeviceId =
                    Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            deviceId = fallbackDeviceId;
        }
        if (node == null || node.isEmpty()) node = "soul_inneed";

        fetchCompanionFromServer(deviceId, node);
    }

    /* --------------------------------- UI --------------------------------- */

    private void bindToUi(String species, String breed,
                          String foundDate, String place,
                          String photoUrl, String who) {

        String title = species.isEmpty() ? "Companion"
                : species + (breed.isEmpty() ? "" : " • " + breed);
        tvCompanion.setText(title);
        tvDate.setText(!foundDate.isEmpty() ? foundDate : "—");
        tvPlace.setText(!place.isEmpty() ? place : "—");
        tvWho.setText(!who.isEmpty() ? who : "—");

        if (!photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.holder)
                    .error(R.drawable.holder)
                    .into(imgCompanion);
        } else {
            imgCompanion.setImageResource(R.drawable.holder);
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /* ------------------------------ Networking ----------------------------- */

    private void fetchCompanionFromServer(String deviceId, String node) {
        String url = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/getCompanionByDevice"
                + "?deviceId=" + deviceId
                + "&node=" + node;

        Request req = new Request.Builder().url(url).get().build();
        http.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(Companion.this,
                                "Sunucuya bağlanılamadı", Toast.LENGTH_LONG).show());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) return;

                String body = response.body().string();
                try {
                    JSONObject j = new JSONObject(body);

                    // Sunucudan gelen alan adları:
                    String species   = nz(j.optString("species"));
                    String breed     = nz(j.optString("breed")); // varsa
                    String foundDate = nz(j.optString("foundDate"));
                    String foundPlace= nz(j.optString("foundLocation"));
                    String photoUrl  = nz(j.optString("imageResId", j.optString("imageUrl")));
                    String profileId = nz(j.optString("finderName"));
                    boolean approved = j.optBoolean("approved", false);

                    runOnUiThread(() -> {
                        // Sunucunun değerleri boş değilse UI'yı güncelle
                        bindToUi(species, breed, foundDate, foundPlace, photoUrl, profileId);
                        if (!approved) showWaitingPopup();
                    });

                } catch (JSONException e) {
                    runOnUiThread(() ->
                            Toast.makeText(Companion.this,
                                    "Veri çözülemedi", Toast.LENGTH_SHORT).show());
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
