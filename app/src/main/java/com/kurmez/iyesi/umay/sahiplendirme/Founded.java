package com.kurmez.iyesi.umay.sahiplendirme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/*
 * TODO (Founded.java) — Kalanlar
 * 1) Görsel yükünü azalt: EXIF rotation + JPEG(85) + 1600px limit (CF tarafı boyut limitlerini netleştir)
 * 2) Photo Picker (Android 13+) ve Activity Result API'ye geçiş (izin akışını sadeleştir)
 * 3) App Check/ID Token header’larını CFHelper üzerinden gönder (403’leri minimize et)
 * 4) Çift tıklama ile çift submit’i önlemek için isSubmitting bayrağı ve buton disable/enable
 * 5) foundDate formatını ISO-8601'e taşı (yyyy-MM-dd)
 * 6) foundLocation için kullanıcıya “lat, lng” format doğrulama/yardımcı girdi
 */

public class Founded extends AppCompatActivity {

    // -------- Constants
    private static final String TAG = "Founded";
    // Gallery picker
    public static final int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    // Camera capture (thumbnail döner; tam çözünürlük için FileProvider kurman gerekir)
    public static final int REQUEST_IMAGE_CAPTURE = 1034;
    // Storage izinleri
    public static final String PERM_READ_EXTERNAL = Manifest.permission.READ_EXTERNAL_STORAGE;         // API<33
    public static final String PERM_READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES;         // API 33+

    // -------- CF Helper
    private CFHelper cf;
    // -------- UI
    private AutoCompleteTextView speciesInput;
    private EditText dateView;
    private EditText placeView;

    // -------- Data
    private final List<Bitmap> photoList = new ArrayList<>();
    private ImageSliderAdapter sliderAdapter;

    // -------- Location
    private FusedLocationProviderClient fusedLocationClient;
    private Double lastLat, lastLng;


    // Modern picker (çoklu)
    private ActivityResultLauncher<String> pickMultiple = registerForActivityResult(
            new GetMultipleContents(), uris -> {
                if (uris != null) {
                    for (Uri u : uris) {
                        Bitmap b = decodeBitmapFromUri(u);
                        if (b != null) photoList.add(b);
                    }
                    if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        speciesInput = findViewById(R.id.companion_species);
        dateView     = findViewById(R.id.companion_found_date);
        placeView    = findViewById(R.id.companion_found_place);
        cf = new CFHelper(this, "iyesi-a651a", null);

// onCreate(...) içinde, super.onCreate(...)’dan SONRA:
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        // Intent’ten foto(lar)ı al (yoksa ekranı kapat)
        takePhotosFromIntentOrFinish();
        boolean hasIncomingPhoto =
                getIntent().hasExtra("snapshot") ||
                        getIntent().hasExtra("photoUris") ||
                        getIntent().hasExtra("photoPaths");

        if (!hasIncomingPhoto) {
            checkPendingOnStart();  // sadece foto gelmemişse
        }
// varsa, onStart kontrolünü tetikleme — kaydet butonunda bir kez kontrol edeceğiz

        // Storage izinlerini iste (API’ye göre doğru izin)
        requestGalleryPermissionIfNeeded();

        // Species tahmini geldiyse göster
        String predicted = getIntent().getStringExtra("predictedSpecies");
        if (predicted != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, new String[]{predicted});
            speciesInput.setAdapter(adapter);
            speciesInput.setText(predicted, false);
        }

        // Konum denemesi
        tryFillLastLocation();

        // Kaydet
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
        // 1) Kurmes → byte[] "snapshot"
        byte[] snap = getIntent().getByteArrayExtra("snapshot");
        if (snap != null && snap.length > 0) {
            Bitmap bmp = BitmapFactory.decodeByteArray(snap, 0, snap.length);
            if (bmp != null) {
                photoList.add(bmp);
                if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();

                // Kurmes’in gönderdiği tahmin varsa doldur
                String predicted = getIntent().getStringExtra("predictedSpecies");
                if (predicted != null && speciesInput != null) {
                    speciesInput.setText(predicted);
                }
                return; // burada bitir; başka formata bakma
            }
        }

        // 2) (İsteğe bağlı) URI listesi desteği
        ArrayList<String> uris = getIntent().getStringArrayListExtra("photoUris");
        if (uris != null && !uris.isEmpty()) {
            for (String s : uris) {
                Bitmap b = decodeBitmapFromUri(Uri.parse(s));
                if (b != null) photoList.add(b);
            }
            if (!photoList.isEmpty()) {
                if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
                return;
            }
        }

        // 3) (Eski) path listesi desteği
        ArrayList<String> paths = getIntent().getStringArrayListExtra("photoPaths");
        if (paths != null && !paths.isEmpty()) {
            for (String p : paths) {
                Bitmap b = BitmapFactory.decodeFile(p);
                if (b != null) photoList.add(b);
            }
            if (!photoList.isEmpty()) {
                if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
                return;
            }
        }

        // 4) Hiçbiri yoksa kullanıcıyı dışarı atma; seçtir
        Toast.makeText(this, "Foto bulunamadı. Lütfen seçin.", Toast.LENGTH_SHORT).show();
        if (pickMultiple != null) pickMultiple.launch("image/*");
    }


    private Bitmap decodeBitmapFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                return ImageDecoder.decodeBitmap(src);
            } else {
                InputStream is = getContentResolver().openInputStream(uri);
                Bitmap b = BitmapFactory.decodeStream(is);
                if (is != null) is.close();
                return b;
            }
        } catch (Exception e) {
            Log.e(TAG, "decode error: " + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------
    void saveCompanionAsync() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        // 1) Önce bu cihaz için pending var mı?
        //    Varsa Companion ekranına yönlendir, yoksa submit et.
        Log.i(TAG, "CF checkPendingCompanion START (save) deviceId=" + deviceId);
        cf.checkPendingCompanion(deviceId, new CFHelper.PendingCallback() {
            @Override
            public void onResult(JSONObject companion) {
                Log.i(TAG, "CF checkPendingCompanion END (save) OK has=" + (companion != null));

                if (companion == null) {
                    runOnUiThread(() -> performSubmitCompanion(deviceId));
                } else {
                    runOnUiThread(() -> openCompanionFromJson(deviceId, companion));
                }
            }

            @Override
            public void onError(Throwable error) {
                int code = inferHttpStatus(error);
                Log.i(TAG, "CF checkPendingCompanion END (save) ERROR code=" + code + " msg=" + (error==null ? "null" : String.valueOf(error.getMessage())));
                if (code == 404) {
                    // Pending yokmuş gibi kabul et → submit et
                    runOnUiThread(() -> performSubmitCompanion(deviceId));
                } else if (code == 401 || code == 403) {
                    runOnUiThread(() -> Toast.makeText(Founded.this,
                            "Doğrulama hatası (" + code + "). Lütfen oturum açın ve uygulamayı doğrulayın.",
                            Toast.LENGTH_LONG).show());
                } else {
                    runOnUiThread(() -> Toast.makeText(Founded.this,
                            "Sunucu hatası: " + (error==null? "-" : error.getMessage()),
                            Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private void performSubmitCompanion(String deviceId) {
        // Zorunlu/AI ile doldurulacak alanlar için basit placeholder
        String species = safeOrTodo(textOf(speciesInput));
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
        if (species.isEmpty()) { speciesInput.setError("Cinsi girin veya TODO kalabilir"); return; }
        if (foundDate.isEmpty()){ dateView.setError("Tarihi girin"); return; }
        if (foundPlace.isEmpty()){
            Toast.makeText(this, "Konum alınamadı. Lütfen izin verin veya elle girin.", Toast.LENGTH_LONG).show();
            return;
        }
        if (photoList.isEmpty()){ Toast.makeText(this,"Fotoğraf yok!",Toast.LENGTH_SHORT).show(); return; }

        // Görsel → Base64 (ilk foto)
        String imgB64 = encodeToBase64(photoList.get(0), Bitmap.CompressFormat.PNG, 85);

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
        Log.i(TAG, "CF submitSoulInNeed START");
        cf.submitSoulInNeed(payload, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                Log.i(TAG, "CF submitSoulInNeed END OK");
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
                Log.i(TAG, "CF submitSoulInNeed END ERROR: " + (error==null? "-" : String.valueOf(error.getMessage())));
                runOnUiThread(() ->
                        Toast.makeText(Founded.this,
                                "Sunucu hatası: " + (error==null? "-" : error.getMessage()),
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
        Log.i(TAG, "CF checkPendingCompanion START (onStart) deviceId=" + deviceId);
        cf.checkPendingCompanion(deviceId, new CFHelper.PendingCallback() {
            @Override public void onResult(JSONObject companion) {
                Log.i(TAG, "CF checkPendingCompanion END (onStart) OK has=" + (companion != null));
                if (companion != null) {
                    runOnUiThread(() -> openCompanionFromJson(deviceId, companion));
                }
            }
            @Override public void onError(Throwable error) {
                int code = inferHttpStatus(error);
                Log.i(TAG, "CF checkPendingCompanion END (onStart) ERROR code=" + code + " msg=" + (error==null ? "null" : String.valueOf(error.getMessage())));
                if (code == 401 || code == 403) {
                    runOnUiThread(() -> Toast.makeText(Founded.this,
                            "Doğrulama hatası (" + code + "). Lütfen oturum açın ve uygulamayı doğrulayın.",
                            Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    // ------------------------------------------------------------
    // XML'deki onClick ile uyumlu: ViewPager2'ye tıklayınca galeri aç
    // ------------------------------------------------------------
    public void onPickImage(View view) {
        // Modern: çoklu seçim
        try {
            pickMultiple.launch("image/*");
        } catch (Exception e) {
            // Eski yöntem yedeği
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.setType("image/*");
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
            }
        }
    }

    public void onStartCamera(View view) {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // TODO: Tam boy fotoğraf istiyorsan FileProvider kur ve EXTRA_OUTPUT ile uri ver.
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == PICK_IMAGE_ACTIVITY_REQUEST_CODE) {
            if (data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    Bitmap b = decodeBitmapFromUri(u);
                    if (b != null) photoList.add(b);
                }
            } else if (data.getData() != null) {
                Uri u = data.getData();
                Bitmap b = decodeBitmapFromUri(u);
                if (b != null) photoList.add(b);
            }
            if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            // Çoğu cihazda küçük bir thumbnail döner
            Bundle extras = data.getExtras();
            if (extras != null) {
                Object o = extras.get("data");
                if (o instanceof Bitmap) {
                    photoList.add((Bitmap) o);
                    if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
                }
            }
        }
    }

    // ------------------------------------------------------------
    // CF/HTTP yardımcıları
    // ------------------------------------------------------------
    private static int inferHttpStatus(Throwable error) {
        if (error == null) return -1;
        final String m = String.valueOf(error.getMessage());
        // Çoğu durumda hata mesajında "HTTP 404", "status=403", "NOT_FOUND" vb. bulunur
        if (m.contains("401") || m.contains("UNAUTHENTICATED")) return 401;
        if (m.contains("403") || m.contains("PERMISSION_DENIED") || m.contains("AppCheck")) return 403;
        if (m.contains("404") || m.contains("NOT_FOUND")) return 404;
        if (m.contains("500")) return 500;
        return -1;
    }

    // ------------------------------------------------------------
    // Location helpers: FoundPlace = yalnızca "lat, lng"
    // ------------------------------------------------------------
    private boolean hasFineLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void tryFillLastLocation() {
        if (!hasFineLocationPermission()) return;
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) setCoordsToPlace(loc);
                })
                .addOnFailureListener(e -> Log.e(TAG, "lastLocation: " + e.getMessage()));
    }

    private void setCoordsToPlace(@NonNull Location loc) {
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        placeView.setText(formatLatLng(lastLat, lastLng));
    }

    private static String formatLatLng(double lat, double lng) {
        return String.format("%f, %f", lat, lng);
    }

    // ------------------------------------------------------------
    // Utils
    // ------------------------------------------------------------
    private String encodeToBase64(Bitmap bmp, Bitmap.CompressFormat fmt, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bmp.compress(fmt, quality, baos);
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
    }

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
    private void requestGalleryPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, PERM_READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{PERM_READ_MEDIA_IMAGES}, 2001);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, PERM_READ_EXTERNAL) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{PERM_READ_EXTERNAL}, 2002);
            }
        }
    }
}