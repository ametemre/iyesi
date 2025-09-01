package com.kurmez.iyesi.umay.sahiplendirme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.adapters.ImageSliderAdapter;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Founded extends AppCompatActivity {

    // -------- Constants
    private static final String TAG = "Founded";
    private static final int IMAGE_PICK = 100;
    public static final int CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE = 1034;
    private static final int REQUEST_IMAGE_CAPTURE = 1;
    public static final int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    public static final int REQUEST_READ_EXTERNAL_STORAGE = 2031;
    private static final int REQUEST_FINE_LOCATION = 42;

    // -------- Cloud Functions
    private static final String CF_CHECK_PENDING =
            "https://us-central1-iyesi-a651a.cloudfunctions.net/checkPendingCompanion";
    private static final String CF_SUBMIT =
            "https://us-central1-iyesi-a651a.cloudfunctions.net/submitSoulInNeed";

    // -------- UI / State
    private List<Bitmap> photoList;
    private ImageSliderAdapter sliderAdapter;
    private ImageView inputImageView; // (opsiyonel: kamera dönüşünde boyut hes.)
    private AutoCompleteTextView outputTextView; // species
    private EditText dateView, placeView;

    // Camera temp
    File photoFile;

    // Location
    private FusedLocationProviderClient locClient;
    private Double lastLat = null, lastLng = null; // FoundPlace yalnızca lat,lng olacak

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------
    @SuppressLint({"ObsoleteSdkInt", "NewApi"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        // Views
        // inputImageView = findViewById(R.id.slider_image); // slider içinde göst. edildiği için opsiyonel
        outputTextView = findViewById(R.id.companion_species);
        dateView       = findViewById(R.id.companion_found_date);
        placeView      = findViewById(R.id.companion_found_place);

        // Photos
        photoList = new ArrayList<>();
        takePhotosFromIntentOrFinish();

        // Slider
        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        // Permission: external read (galeri)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        REQUEST_READ_EXTERNAL_STORAGE
                );
            }
        }

        // Species: öneri geldiyse göster
        String predicted = getIntent().getStringExtra("predictedSpecies");
        if (predicted != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    new String[]{predicted}
            );
            outputTextView.setAdapter(adapter);
            outputTextView.setText(predicted, false);
            outputTextView.post(outputTextView::showDropDown);
        }

        // Found Date: bugün
        String today = new java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                .format(new java.util.Date());
        dateView.setText(today);

        // Location client & Found Place = SADECE lat,lng
        locClient = LocationServices.getFusedLocationProviderClient(this);
        fillCoordsFromLastLocation(); // FoundPlace alanına yalnızca "lat, lng" yazdırır

        // Buttons
        findViewById(R.id.take_anotherphoto_button).setOnClickListener(v -> finish());
        findViewById(R.id.save_companion_button).setOnClickListener(v -> {
            Log.d(TAG, "save_companion_button clicked!");
            saveCompanionAsync();
        });

        // Pending varsa yönlendir
        checkPendingCompanionAndRedirect();
    }

    // ------------------------------------------------------------
    // Photos intake
    // ------------------------------------------------------------
    private void takePhotosFromIntentOrFinish() {
        // Tek snapshot
        byte[] snapshotData = getIntent().getByteArrayExtra("snapshot");
        if (snapshotData != null) {
            Bitmap snapshot = BitmapFactory.decodeByteArray(snapshotData, 0, snapshotData.length);
            photoList.add(snapshot);
        } else {
            // Çoklu veya tekli foto
            ArrayList<Bitmap> list = getIntent().getParcelableArrayListExtra("photos");
            if (list != null) photoList.addAll(list);
        }
        if (photoList.isEmpty()) {
            Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    // ------------------------------------------------------------
    // Permissions results
    // ------------------------------------------------------------
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_READ_EXTERNAL_STORAGE) {
            if (permissions.length > 0 && grantResults.length > 0) {
                boolean granted = (grantResults[0] == PackageManager.PERMISSION_GRANTED);
                if (!granted) {
                    Toast.makeText(this,
                            "Galeriden resim seçmek için izin gerekli",
                            Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.w(TAG, "onRequestPermissionsResult: boş permissions/grantResults");
            }
        } else if (requestCode == REQUEST_FINE_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fillCoordsFromLastLocation();
            } else {
                Toast.makeText(this, "Konum izni verilmedi.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------
    // Activity results (galeri / kamera)
    // ------------------------------------------------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK) return;

        switch (requestCode) {
            case PICK_IMAGE_ACTIVITY_REQUEST_CODE:
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        Bitmap bmp = loadFromUri(uri);
                        if (bmp != null) photoList.add(bmp);
                    }
                } else if (data.getData() != null) {
                    Bitmap bmp = loadFromUri(data.getData());
                    if (bmp != null) photoList.add(bmp);
                }
                sliderAdapter.notifyDataSetChanged();
                break;

            case REQUEST_IMAGE_CAPTURE:
            case CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE:
                Bitmap camBmp = getCapturedImage();
                if (camBmp != null) {
                    photoList.add(camBmp);
                    sliderAdapter.notifyDataSetChanged();
                }
                break;
        }
    }

    // ------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------
    private void saveCompanionAsync() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String checkUrl = CF_CHECK_PENDING + "?deviceId=" + deviceId;

        OkHttpClient http = new OkHttpClient();
        Request checkRequest = new Request.Builder().url(checkUrl).get().build();

        http.newCall(checkRequest).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String body = response.body() != null ? response.body().string() : "";
                if (body.trim().equals("false")) {
                    runOnUiThread(() -> performSubmitCompanion(deviceId));
                } else {
                    try {
                        JSONObject json = new JSONObject(body);
                        JSONObject companion = json.getJSONObject("companion");
                        Intent intent = new Intent(Founded.this, Companion.class);
                        intent.putExtra("deviceId", deviceId);
                        intent.putExtra("species", companion.optString("species"));
                        intent.putExtra("foundDate", companion.optString("foundDate"));
                        intent.putExtra("foundLocation", companion.optString("foundLocation"));
                        intent.putExtra("imageResId", companion.optString("imageResId"));
                        intent.putExtra("node", "soul_inneed");
                        startActivity(intent);
                        finish();
                    } catch (JSONException e) {
                        Log.e(TAG, "Parse error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "Connection error: " + e.getMessage());
            }
        });
    }

    private void performSubmitCompanion(String deviceId) {
        // Zorunlular (ileride AI ile tahmin edilecekler için "TODO" placeholder)
        String species = safeOrTodo(textOf(outputTextView));
        String breed   = "TODO";
        String age     = "TODO";
        String health  = "TODO";

        // Otomatikler
        String foundDate = textOf(dateView);
        long   timestamp = System.currentTimeMillis();

        // FoundPlace: SADECE "lat, lng"
        String foundPlace = textOf(placeView);
        if (foundPlace.isEmpty() && lastLat != null && lastLng != null) {
            foundPlace = formatLatLng(lastLat, lastLng);
            placeView.setText(foundPlace);
        }

        if (species.isEmpty()) { outputTextView.setError("Cinsi girin veya TODO kalabilir"); return; }
        if (foundDate.isEmpty()){ dateView.setError("Tarihi girin"); return; }
        if (foundPlace.isEmpty()){
            Toast.makeText(this, "Konum alınamadı. Lütfen izin verin veya elle girin.", Toast.LENGTH_LONG).show();
            return;
        }
        if (photoList.isEmpty()){ Toast.makeText(this,"Fotoğraf yok!",Toast.LENGTH_SHORT).show(); return; }

        // Görsel → Base64
        String imgB64 = encodeToBase64(photoList.get(0), Bitmap.CompressFormat.PNG, 100);

        // Payload
        JSONObject payload = new JSONObject();
        try {
            // Soul zorunlu (senin belirttiğin alanlar)
            payload.put("species",       species);
            payload.put("breed",         breed);
            payload.put("age",           age);
            payload.put("health",        health);

            payload.put("foundDate",     foundDate);
            payload.put("foundLocation", foundPlace); // ← SADECE "lat, lng" içerir
            payload.put("timestamp",     timestamp);

            // Ek: ham koordinatları da gönder (sunucuda işlemek kolay olur)
            if (lastLat != null && lastLng != null) {
                payload.put("lat", lastLat);
                payload.put("lng", lastLng);
            }

            // Görsel verisi (imageResId sunucuda üretilecek)
            payload.put("imageBase64",   imgB64);

            // deviceId bilgi amaçlı
            payload.put("deviceId",      deviceId);

            // finderName ve imageResId → sunucu tarafında atanacak
        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this,"Veri oluşturulamadı",Toast.LENGTH_SHORT).show();
            return;
        }

        OkHttpClient http = new OkHttpClient();
        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.get("application/json; charset=utf-8")
        );
        Request request = new Request.Builder()
                .url(CF_SUBMIT)
                .post(body)
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(Founded.this,
                                "İstek gönderilemedi: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String resp = response.body() != null ? response.body().string() : "";
                runOnUiThread(() -> {
                    if (response.isSuccessful()) {
                        try {
                            String key = new JSONObject(resp).getString("key");
                            Intent i = new Intent(Founded.this, Companion.class);
                            i.putExtra("requestKey", key);
                            i.putExtra("node", "soul_inneed");
                            startActivity(i);
                            finish();
                        } catch (JSONException je) {
                            Toast.makeText(Founded.this, "Yanıt çözümlenemedi", Toast.LENGTH_LONG).show();
                        }
                    } else {
                        Toast.makeText(Founded.this,
                                "Sunucu hatası: " + resp,
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------
    // Location helpers: FoundPlace = yalnızca "lat, lng"
    // ------------------------------------------------------------
    private boolean hasFineLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void fillCoordsFromLastLocation() {
        if (!hasFineLocationPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_FINE_LOCATION
            );
            return;
        }
        locClient.getLastLocation().addOnSuccessListener(loc -> {
            if (loc == null) return;
            setCoordsToPlace(loc);
        }).addOnFailureListener(e -> Log.e(TAG, "LastLocation error: " + e.getMessage()));
    }

    private void setCoordsToPlace(@NonNull Location loc) {
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        String txt = formatLatLng(lastLat, lastLng); // "41.01500, 28.97900"
        placeView.setText(txt);                      // YALNIZCA lat,lng yaz
    }

    private String formatLatLng(double lat, double lng) {
        return String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng);
    }

    // ------------------------------------------------------------
    // UI helpers
    // ------------------------------------------------------------
    private String textOf(EditText et) {
        return et.getText() == null ? "" : et.getText().toString().trim();
    }
    private String textOf(AutoCompleteTextView tv) {
        return tv.getText() == null ? "" : tv.getText().toString().trim();
    }
    private String safeOrTodo(String s) {
        if (s == null) return "TODO";
        String t = s.trim();
        return t.isEmpty() ? "TODO" : t;
    }
    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------
    // Media helpers
    // ------------------------------------------------------------
    private String encodeToBase64(Bitmap bmp, Bitmap.CompressFormat fmt, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(fmt, quality, baos);
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

    private Bitmap getCapturedImage() {
        if (photoFile == null) return null;
        return BitmapFactory.decodeFile(photoFile.getAbsolutePath());
    }

    private Bitmap loadFromUri(Uri photoUri) {
        try {
            if (Build.VERSION.SDK_INT > 27) {
                ImageDecoder.Source source =
                        ImageDecoder.createSource(this.getContentResolver(), photoUri);
                return ImageDecoder.decodeBitmap(source);
            } else {
                return MediaStore.Images.Media.getBitmap(this.getContentResolver(), photoUri);
            }
        } catch (IOException e) {
            Log.e(TAG, "loadFromUri: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------
    // Optional navigation to existing pending record
    // ------------------------------------------------------------
    private void checkPendingCompanionAndRedirect() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String checkUrl = CF_CHECK_PENDING + "?deviceId=" + deviceId;

        new OkHttpClient().newCall(new Request.Builder().url(checkUrl).get().build())
                .enqueue(new Callback() {
                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                        String body = response.body() != null ? response.body().string() : "";
                        if (body.trim().equals("false")) return;

                        try {
                            JSONObject json = new JSONObject(body);
                            JSONObject companion = json.getJSONObject("companion");

                            Intent intent = new Intent(Founded.this, Companion.class);
                            intent.putExtra("deviceId", deviceId);
                            intent.putExtra("species", companion.optString("species"));
                            intent.putExtra("foundDate", companion.optString("foundDate"));
                            intent.putExtra("foundLocation", companion.optString("foundLocation"));
                            intent.putExtra("imageResId", companion.optString("imageResId"));
                            intent.putExtra("node", "soul_inneed");
                            startActivity(intent);
                            finish();
                        } catch (JSONException e) {
                            Log.e(TAG, "Parse error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        Log.e(TAG, "Connection error: " + e.getMessage());
                    }
                });
    }

    // ------------------------------------------------------------
    // Pickers (optional buttons)
    // ------------------------------------------------------------
    public void onPickImage(android.view.View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
        }
    }

    public void onStartCamera(android.view.View view) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }
}
