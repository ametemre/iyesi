//Companion.java
package com.kurmez.iyesi.umay.sahiplendirme;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.kurmez.iyesi.R;
import android.provider.Settings;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;

import android.widget.Toast;

// Silinecekler:


public class Companion extends AppCompatActivity {
    private String species;
    private String foundDate;
    private String foundPlace;
    private String photoUrl;
    private String profileId;
    private String requestKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_companion);

        // 1. intent parametrelerini al
        String deviceId = getIntent().getStringExtra("deviceId");
        String node = getIntent().getStringExtra("node");
        if (deviceId == null) {
            @SuppressLint("HardwareIds")
            String fallbackDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            deviceId = fallbackDeviceId;
        }
        if (node == null) node = "soul_inneed";

        String url = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/getCompanionByDevice?deviceId=" + deviceId + "&node=" + node;

        // 3. Veri çek
        new OkHttpClient().newCall(new Request.Builder().url(url).build())
                .enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        runOnUiThread(() -> Toast.makeText(Companion.this,
                                "Sunucuya bağlanılamadı", Toast.LENGTH_LONG).show());
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        if (!response.isSuccessful()) return;

                        try {
                            JSONObject j = new JSONObject(response.body().string());

                            final String species   = j.optString("species");
                            final String foundDate = j.optString("foundDate");
                            final String foundPlace= j.optString("foundLocation");
                            final String photoUrl  = j.optString("imageResId");
                            final String profileId = j.optString("finderName");
                            final boolean approved = j.optBoolean("approved", false);

                            runOnUiThread(() -> {
                                populateUI(species, foundDate, foundPlace, photoUrl, profileId);
                                if (!approved) showWaitingPopup();
                            });

                        } catch (JSONException e) {
                            runOnUiThread(() -> Toast.makeText(Companion.this,
                                    "Veri çözülemedi", Toast.LENGTH_SHORT).show());
                        }
                    }
                });
    }
    private void populateUI(String species, String foundDate, String foundPlace, String photoUrl, String profileId) {
        ImageView companionImage = findViewById(R.id.companion_image);
        TextView soulCompanion   = findViewById(R.id.soul_companion);
        TextView foundDateView   = findViewById(R.id.found_Date);
        TextView foundPlaceView  = findViewById(R.id.found_place);
        TextView ulgenView       = findViewById(R.id.veterineary_ulgen);

        soulCompanion.setText(species != null ? species : "N/A");
        foundDateView.setText(foundDate != null ? foundDate : "N/A");
        foundPlaceView.setText(foundPlace != null ? foundPlace : "N/A");
        ulgenView.setText(profileId != null ? profileId : "N/A");

        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.holder)
                    .error(R.drawable.star)
                    .into(companionImage);
        } else {
            companionImage.setImageResource(R.drawable.holder);
        }
    }

    /**
     * Displays a popup dialog indicating waiting for Ülgen's response.
     */
    private void showWaitingPopup() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Waiting For Ülgen's Approval");
        builder.setMessage(
                "Veterinarians in our community are checking your application and will respond to you ASAP with the necessary medical attention.\n\n" +
                        "Please do not touch, move, or pet the soul you have found. It may not look harmful, but street animals might carry diseases that could harm you, or you may harm the soul.\n\n" +
                        "You will receive the necessary professional help ASAP.");
        builder.setCancelable(false); // Prevent dismissal
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
}
