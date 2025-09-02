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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.adapters.ImageSliderAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Founded — Foto(lar) + temel bilgilerle "soul_inneed" kaydı oluşturur.
 * ExplorePrivate içinde listelenmesi için backend /submitSoulInNeed ve /listPendingCompanions ile uyumludur.
 */
public class Founded extends AppCompatActivity {

    // -------- Constants
    private static final String TAG = "Founded";
    // Gallery picker
    public static final int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    // Camera capture (thumbnail döner; tam çözünürlük için FileProvider kurman gerekir)
    public static final int REQUEST_IMAGE_CAPTURE = 1034;
    // Storage izinleri
    public static final int REQUEST_READ_MEDIA_IMAGES    = 2032; // API 33+
    public static final int REQUEST_READ_EXTERNAL_STORAGE = 2031; // API 23–32
    // Location izni
    private static final int REQUEST_FINE_LOCATION = 42;

    // -------- UI / State
    private final List<Bitmap> photoList = new ArrayList<>();
    private ImageSliderAdapter sliderAdapter;
    private AutoCompleteTextView outputTextView; // species
    private EditText dateView, placeView;

    // Camera temp (tam çözünürlük istiyorsan FileProvider ile üret)
    private File photoFile;

    // Location
    private FusedLocationProviderClient locClient;
    private Double lastLat = null, lastLng = null; // FoundPlace yalnızca "lat, lng" yazılacak

    // Cloud Functions helper
    private CFHelper cf;

    // ------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------
    @SuppressLint({"ObsoleteSdkInt", "NewApi"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        // Views
        outputTextView = findViewById(R.id.companion_species);
        dateView       = findViewById(R.id.companion_found_date);
        placeView      = findViewById(R.id.companion_found_place);

        // Slider
        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        // Intent’ten foto(lar)ı al (yoksa ekranı kapat)
        takePhotosFromIntentOrFinish();

        // Storage izinlerini iste (API’ye göre doğru izin)
        requestGalleryPermissionIfNeeded();

        // Species tahmini geldiyse göster
        String predicted = getIntent().getStringExtra("predictedSpecies");
        if (predicted != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, new String[]{predicted});
            outputTextView.setAdapter(adapter);
            outputTextView.setText(predicted, false);
            outputTextView.post(outputTextView::showDropDown);
        }

        // Found Date: bugün
        String today = new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                .format(new java.util.Date());
        dateView.setText(today);

        // Location client & Found Place = SADECE "lat, lng"
        locClient = LocationServices.getFusedLocationProviderClient(this);
        fillCoordsFromLastLocation();

        // CF Helper
        // TODO: Projenin CFHelper yapısına göre ctor’u seç:
        //  - Eğer CFHelper(Context, String projectId, Listener) ise:
        //      cf = new CFHelper(this, "iyesi-a651a", null);
        //  - Eğer bölge parametresi alan sürümü kullanıyorsan:
        //      cf = new CFHelper(this, "iyesi-a651a", "us-central1", null);
        cf = new CFHelper(this, "iyesi-a651a", null);

        // Buttons:
        // XML'de:
        //  - ViewPager2: android:onClick="onPickImage"
        //  - take_anotherphoto_button: android:onClick="onStartCamera"
        // Save butonunu programatik bağlıyoruz:
        findViewById(R.id.save_companion_button).setOnClickListener(v -> {
            Log.d(TAG, "save_companion_button clicked!");
            saveCompanionAsync();
        });

        // Uygulama açılışında cihaz için pending kayıt var mı? Varsa Companion’a yönlendir.
        checkPendingOnStart();
    }

    // ------------------------------------------------------------
    // Photos intake
    // ------------------------------------------------------------
    private void takePhotosFromIntentOrFinish() {
        // Tek snapshot
        byte[] snapshotData = getIntent().getByteArrayExtra("snapshot");
        if (snapshotData != null) {
            Bitmap snapshot = BitmapFactory.decodeByteArray(snapshotData, 0, snapshotData.length);
            if (snapshot != null) photoList.add(snapshot);
        } else {
            // Çoklu/tekli foto
            ArrayList<Bitmap> list = getIntent().getParcelableArrayListExtra("photos");
            if (list != null) photoList.addAll(list);
        }

        if (photoList.isEmpty()) {
            Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        sliderAdapter.notifyDataSetChanged();
    }

    // Storage izin akışı (API 33+ ve altı)
    private void requestGalleryPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES}, REQUEST_READ_MEDIA_IMAGES);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            }
        }
    }

    // ------------------------------------------------------------
    // Permissions results
    // ------------------------------------------------------------
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_READ_MEDIA_IMAGES || requestCode == REQUEST_READ_EXTERNAL_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Galeriden resim seçmek için izin gerekli", Toast.LENGTH_SHORT).show();
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

        if (requestCode == PICK_IMAGE_ACTIVITY_REQUEST_CODE) {
            // Çoklu seçim
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    // DİKKAT: getUri() — parantez gerekli
                    Uri uri = data.getClipData().getItemAt(i).getUri();
                    Bitmap bmp = loadFromUri(uri);
                    if (bmp != null) photoList.add(bmp);
                }
            } else if (data.getData() != null) {
                Bitmap bmp = loadFromUri(data.getData());
                if (bmp != null) photoList.add(bmp);
            }
            sliderAdapter.notifyDataSetChanged();
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            // Kamera thumbnail (extras->"data") ya da photoFile varsa ondan
            Bitmap camBmp = (data != null && data.getExtras() != null)
                    ? (Bitmap) data.getExtras().get("data")
                    : null;
            if (camBmp == null) camBmp = getCapturedImage();
            if (camBmp != null) {
                photoList.add(camBmp);
                sliderAdapter.notifyDataSetChanged();
            }
        }
    }

    // ------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------
    private void saveCompanionAsync() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // 1) Önce bu cihaz için pending var mı?
        //    Varsa Companion ekranına yönlendir, yoksa submit et.
        cf.checkPendingCompanion(deviceId, new CFHelper.PendingCallback() {
            @Override
            public void onResult(JSONObject companion) {
                if (companion == null) {
                    runOnUiThread(() -> performSubmitCompanion(deviceId));
                } else {
                    runOnUiThread(() -> openCompanionFromJson(deviceId, companion));
                }
            }

            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "checkPendingCompanion error: " + error.getMessage());
                // TODO: 404 (function yok) veya 403 (App Check) durumunda kullanıcıyı bloke etmeyelim:
                runOnUiThread(() -> performSubmitCompanion(deviceId));
            }
        });
    }

    private void performSubmitCompanion(String deviceId) {
        // Zorunlu/AI ile doldurulacak alanlar için basit placeholder
        String species = safeOrTodo(textOf(outputTextView));
        String breed   = "TODO";  // TODO: modelden doldurulacak
        String age     = "TODO";  // TODO: modelden doldurulacak
        String health  = "TODO";  // TODO: modelden doldurulacak

        String foundDate = textOf(dateView);
        long   timestamp = System.currentTimeMillis();

        // FoundPlace: SADECE "lat, lng"
        String foundPlace = textOf(placeView);
        if (foundPlace.isEmpty() && lastLat != null && lastLng != null) {
            foundPlace = formatLatLng(lastLat, lastLng);
            placeView.setText(foundPlace);
        }

        // Basit doğrulamalar
        if (species.isEmpty()) { outputTextView.setError("Cinsi girin veya TODO kalabilir"); return; }
        if (foundDate.isEmpty()){ dateView.setError("Tarihi girin"); return; }
        if (foundPlace.isEmpty()){
            Toast.makeText(this, "Konum alınamadı. Lütfen izin verin veya elle girin.", Toast.LENGTH_LONG).show();
            return;
        }
        if (photoList.isEmpty()){ Toast.makeText(this,"Fotoğraf yok!",Toast.LENGTH_SHORT).show(); return; }

        // Görsel → Base64 (ilk foto)
        String imgB64 = encodeToBase64(photoList.get(0), Bitmap.CompressFormat.PNG, 100);

        // Payload
        JSONObject payload = new JSONObject();
        try {
            payload.put("species",       species);
            payload.put("breed",         breed);
            payload.put("age",           age);
            payload.put("health",        health);

            payload.put("foundDate",     foundDate);
            payload.put("foundLocation", foundPlace); // yalnızca "lat, lng"
            payload.put("timestamp",     timestamp);

            // Ham koordinatları ekle (sunucu tarafında pratik olur)
            if (lastLat != null && lastLng != null) {
                payload.put("lat", lastLat);
                payload.put("lng", lastLng);
            }

            // Görsel verisi (imageResId sunucuda üretilecek)
            payload.put("imageBase64",   imgB64);

            // Cihaz bilgisi
            payload.put("deviceId",      deviceId);

            // finderName ve imageResId → sunucu tarafında atanacak
        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this,"Veri oluşturulamadı",Toast.LENGTH_SHORT).show();
            return;
        }

        // 2) Submit
        cf.submitSoulInNeed(payload, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                runOnUiThread(() -> {
                    try {
                        // TODO: backend "key" döndürmeli. Yoksa yanıt şemasını kontrol et.
                        String key = resp.getString("key");
                        Intent i = new Intent(Founded.this, Companion.class);
                        i.putExtra("requestKey", key);
                        i.putExtra("node", "soul_inneed");
                        startActivity(i);
                        finish();
                    } catch (JSONException je) {
                        Toast.makeText(Founded.this, "Yanıt çözümlenemedi", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                runOnUiThread(() ->
                        Toast.makeText(Founded.this,
                                "Sunucu hatası: " + error.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        });
    }

    // Pending varsa Companion’a geç
    private void openCompanionFromJson(@NonNull String deviceId, @NonNull JSONObject companion) {
        Intent intent = new Intent(Founded.this, Companion.class);
        intent.putExtra("deviceId", deviceId);
        intent.putExtra("species", companion.optString("species"));
        intent.putExtra("foundDate", companion.optString("foundDate"));
        intent.putExtra("foundLocation", companion.optString("foundLocation"));
        intent.putExtra("imageResId", companion.optString("imageResId"));
        intent.putExtra("node", "soul_inneed");
        startActivity(intent);
        finish();
    }

    // Açılışta kontrol (kullanıcıyı otomatik devam ettirmek için)
    private void checkPendingOnStart() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        cf.checkPendingCompanion(deviceId, new CFHelper.PendingCallback() {
            @Override public void onResult(JSONObject companion) {
                if (companion != null) runOnUiThread(() -> openCompanionFromJson(deviceId, companion));
            }
            @Override public void onError(Throwable error) {
                Log.w(TAG, "checkPendingOnStart error: " + error.getMessage());
                // TODO: 404 ise muhtemelen fonksiyon deploy edilmemiştir ya da isim/region farklıdır.
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
                    this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
            return;
        }
        if (locClient == null) locClient = LocationServices.getFusedLocationProviderClient(this);
        locClient.getLastLocation()
                .addOnSuccessListener(loc -> { if (loc != null) setCoordsToPlace(loc); })
                .addOnFailureListener(e -> Log.e(TAG, "LastLocation error: " + e.getMessage()));
    }

    private void setCoordsToPlace(@NonNull Location loc) {
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        String txt = formatLatLng(lastLat, lastLng); // "41.01500, 28.97900"
        placeView.setText(txt);
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
        // TODO: Tam çözünürlük için FileProvider ile photoFile oluşturup kamera intentine EXTRA_OUTPUT ver.
        return BitmapFactory.decodeFile(photoFile.getAbsolutePath());
    }

    private Bitmap loadFromUri(Uri photoUri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
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
    // Pickers (XML onClick’leri bunları çağırıyor)
    // ------------------------------------------------------------
    public void onPickImage(android.view.View view) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
        }
    }

    public void onStartCamera(android.view.View view) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // TODO: Tam boy fotoğraf istiyorsan FileProvider kur ve EXTRA_OUTPUT ile uri ver.
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }
}
