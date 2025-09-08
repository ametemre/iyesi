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
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.data.Soul;
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
    // class içinde:
    private volatile boolean isSubmitting = false;

    // -------- Constants
    private static final String TAG = "Founded";
    // Gallery picker
    public static final int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    // Camera capture (thumbnail döner; tam çözünürlük için FileProvider kurman gerekir)
    public static final int REQUEST_IMAGE_CAPTURE = 1034;
    // Storage izinleri
    public static final String PERM_READ_EXTERNAL = Manifest.permission.READ_EXTERNAL_STORAGE;         // API<33
    public static final String PERM_READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES;         // API 33+
    private FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

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
        cf = new CFHelper(this, "iyesi-e8d4f", null);

// onCreate(...) içinde, super.onCreate(...)’dan SONRA:
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        // Intent’ten foto(lar)ı al (yoksa ekranı kapat)
        takePhotosFromIntentOrFinish();
        boolean hasIncomingPhoto = getIntent().hasExtra("snapshot") || getIntent().hasExtra("photoUris") || getIntent().hasExtra("photoPaths");
        if (dateView.getText() == null || dateView.getText().toString().trim().isEmpty()) {dateView.setText(todayIsoDate());}
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

    // ------------------------------------------------------------import
    // helper: büyük resmi downscale et
    private static Bitmap downscale(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, maxSide / (float)Math.max(w, h));
        if (scale >= 0.999f) return src;
        int nw = Math.round(w * scale), nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

    // helper: EXIF rotasyon (API<29)
    private static int getExifRotation(@NonNull InputStream is) {
        try {
            android.media.ExifInterface exif = new android.media.ExifInterface(is);
            int o = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL);
            switch (o) {
                case android.media.ExifInterface.ORIENTATION_ROTATE_90:  return 90;
                case android.media.ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case android.media.ExifInterface.ORIENTATION_ROTATE_270: return 270;
            }
        } catch (Exception ignore) {}
        return 0;
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        if (degrees == 0) return src;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    private Bitmap decodeBitmapFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap raw = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                // API28+’da EXIF rotasyonu ImageDecoder çoğu cihazda uygular,
                // ama garanti değil; yine de tek tip downscale uygula:
                return downscale(raw, 1600);
            } else {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return null;
                // EXIF okumak için bir kopya stream aç
                byte[] bytes = readAllBytes(is);
                Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                InputStream is2 = new java.io.ByteArrayInputStream(bytes);
                int degrees = getExifRotation(is2);
                if (is2 != null) is2.close();
                Bitmap fixed = rotate(raw, degrees);
                return downscale(fixed, 1600);
            }
        } catch (Exception e) {
            Log.e(TAG, "decode error: " + e.getMessage());
            return null;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    // ------------------------------------------------------------import
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

    // ------------------------------------------------------------
    // Save flow
    // ------------------------------------------------------------
    void saveCompanionAsync() {
        boolean isUnauthenticated = (user == null);
        String id;
        if (!isUnauthenticated) {
            String userID = user.getUid();
            id = userID;
            // Oturum AÇIK → pending akışını atla, doğrudan submit et
            Log.i(TAG, "Authenticated user detected (uid=" + user.getUid() + "), skipping pending check.");
            performSubmitCompanion(userID);
            return;
        }else {
            String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            id = deviceId;
        }
        // 1) Önce bu cihaz için pending var mı?
        //    Varsa Companion ekranına yönlendir, yoksa submit et.
        Log.i(TAG, "CF checkPendingCompanion START (save) deviceId=" + id);
        cf.checkPendingCompanion(id, new CFHelper.PendingCallback() {
            @Override
            public void onResult(JSONObject companion) {
                Log.i(TAG, "CF checkPendingCompanion END (save) OK has=" + (companion != null));

                if (companion == null) {
                    runOnUiThread(() -> performSubmitCompanion(id));
                } else {
                    runOnUiThread(() -> openCompanionFromJson(id, companion));
                }
            }

            @Override
            public void onError(Throwable error) {
                int code = inferHttpStatus(error);
                Log.i(TAG, "CF checkPendingCompanion END (save) ERROR code=" + code + " msg=" + (error==null ? "null" : String.valueOf(error.getMessage())));
                if (code == 404) {
                    // Pending yokmuş gibi kabul et → submit et
                    runOnUiThread(() -> performSubmitCompanion(id));
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

    private void performSubmitCompanion(String actorId) {
        if (isSubmitting) {
            Log.d(TAG, "performSubmitCompanion: already submitting, ignore duplicate tap.");
            return;
        }

        // 0) UI kilidi
        setSubmitting(true);

        try {
            // 1) Alanları topla
            String species    = safeOrTodo(textOf(speciesInput));
            String breed      = "TODO";  // model dolduracak
            String age        = "TODO";  // model dolduracak
            String health     = "TODO";  // model dolduracak
            String foundDateI = textOf(dateView);
            String foundPlace = textOf(placeView);

            // 2) Tarihi ISO-8601'e normalize et (girildiyse)
            String foundDate = foundDateI.isEmpty() ? todayIsoDate() : normalizeFoundDate(foundDateI);
            if (!foundDate.equals(foundDateI)) {
                dateView.setText(foundDate); // UI'yı da senkronla
            }
            // 3) Konumu belirle: (a) kullanıcı girişi parse, yoksa (b) lastLat/lng, yoksa hata
            double latNum, lngNum;
            double[] parsed = parseLatLng(foundPlace);
            if (parsed != null) {
                latNum = parsed[0]; lngNum = parsed[1];
                foundPlace = formatLatLng(latNum, lngNum); // normalize
            } else if (lastLat != null && lastLng != null) {
                latNum = lastLat; lngNum = lastLng;
                foundPlace = formatLatLng(latNum, lngNum);
                placeView.setText(foundPlace);
            } else {
                Toast.makeText(this, "Konum alınamadı. Lütfen izin verin veya 'lat, lng' girin.", Toast.LENGTH_LONG).show();
                setSubmitting(false);
                return;
            }

            // 4) Zorunlu alan kontrolleri (species boşsa otomatik "TODO" bırakıyoruz; foundDate zorunlu)
            if (foundDate.isEmpty()) {
                dateView.setError("Tarih gerekli (yyyy-MM-dd)");
                setSubmitting(false);
                return;
            }
            if (photoList.isEmpty()) {
                Toast.makeText(this, "Fotoğraf yok!", Toast.LENGTH_SHORT).show();
                setSubmitting(false);
                return;
            }

            // 5) Görseli akıllı şekilde JPEG Base64'e çevir
            String imgB64 = encodeBitmapSmart(photoList.get(0));

            // 6) Payload
            long timestamp = System.currentTimeMillis();
            //---------------------------------------------------------------------------------------import

// Soul modeline birebir doldur
            Soul soul = new Soul.Builder()
                    .species(species)
                    .breed(breed)
                    .age(age)
                    .health(health)
                    .foundDate(foundDate)               // ISO-8601 (yyyy-MM-dd)
                    .foundLocation(foundPlace)          // "lat, lng" string
                    .timestamp(timestamp)               // zorunlu zaman damgası
                    .ts(timestamp)                      // sorgular için (opsiyonel ama faydalı)
                    .status("pending")                  // yeni kayıtlar için varsayılan durum
                    .latLng(latNum, lngNum)             // hem GeoPoint hem kökte lat/lng çıkacak
                    .build();

// Soul → JSONObject (toJson: location{lat,lng} + kökte lat/lng’yi yazar)
            JSONObject payload = soul.toJson();

// Taşıma katmanına ait alanları (modele yazmadan) üstte ekle
            payload.put("imageBase64", encodeBitmapSmart(photoList.get(0))); // sadece upload için
            payload.put("actorKind",  (user != null) ? "uid" : "device");    // backend ayırt etsin
            payload.put("deviceId",   actorId);                              // uid veya ANDROID_ID

// (opsiyonel ama tutarlı olur) akış tipini belirtmek istersen:
            payload.put("requestKind", "soul_inneed");

// 7) Submit (değişmedi)
            Log.i(TAG, "CF submitSoulInNeed START");
            //---------------------------------------------------------------------------------------import
            cf.submitSoulInNeed(payload, new CFHelper.EndpointCallback() {
                @Override
                public void onSuccess(JSONObject resp) {
                    Log.i(TAG, "CF submitSoulInNeed END OK");
                    runOnUiThread(() -> {
                        setSubmitting(false);
                        try {
                            String key = resp.getString("key"); // backend bu alanı dönmeli
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
                    runOnUiThread(() -> {
                        setSubmitting(false);
                        Toast.makeText(Founded.this,
                                "Sunucu hatası: " + (error==null? "-" : error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    });
                }
            });

        } catch (Throwable t) {
            Log.e(TAG, "performSubmitCompanion fatal: ", t);
            setSubmitting(false);
            Toast.makeText(this, "Beklenmeyen hata: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    @NonNull
    private String todayIsoDate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.time.ZoneId zone = java.time.ZoneId.of("Europe/Istanbul"); // isteğe bağlı: sabit TR
                return java.time.LocalDate.now(zone)
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("Europe/Istanbul")); // isteğe bağlı: sabit TR
                return fmt.format(new java.util.Date());
            }
        } catch (Throwable t) {
            // Beklenmedik durumda sistem varsayılanı
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return fmt.format(new java.util.Date());
        }
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
    // --- foundDate → ISO-8601 (yyyy-MM-dd)
    @NonNull
    private String normalizeFoundDate(@NonNull String in) {
        String s = in.trim();
        if (s.isEmpty()) return s;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                // yaygın biçimler: dd.MM.yyyy, dd/MM/yyyy, yyyy-MM-dd
                java.time.format.DateTimeFormatter[] fmts = new java.time.format.DateTimeFormatter[]{
                        java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy"),
                        java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy"),
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                };
                java.time.LocalDate d = null;
                for (java.time.format.DateTimeFormatter f : fmts) {
                    try { d = java.time.LocalDate.parse(s, f); break; } catch (Exception ignore) {}
                }
                if (d == null) {
                    // son çare: gün/ay/yıl sıralamasını tahmin etmeye çalışma → olduğu gibi bırak
                    return s;
                }
                return d.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE); // yyyy-MM-dd
            } else {
                java.text.ParseException last = null;
                String[] patterns = {"d.M.yyyy", "d/M/yyyy", "yyyy-MM-dd"};
                for (String p : patterns) {
                    try {
                        java.text.SimpleDateFormat inFmt = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                        inFmt.setLenient(false);
                        java.util.Date d = inFmt.parse(s);
                        java.text.SimpleDateFormat outFmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                        outFmt.setLenient(false);
                        return outFmt.format(d);
                    } catch (java.text.ParseException e) { last = e; }
                }
                if (last != null) Log.w(TAG, "normalizeFoundDate: " + last.getMessage());
                return s;
            }
        } catch (Throwable t) {
            Log.w(TAG, "normalizeFoundDate err: " + t.getMessage());
            return s;
        }
    }

    // --- "lat, lng" serbest girişini parse et (boşsa null)
    @Nullable
    private double[] parseLatLng(@Nullable String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        // virgül veya boşluk ayırıcıya toleranslı:
        s = s.replaceAll("[\\s]+", " ");
        String[] parts = s.split("[, ]+");
        if (parts.length < 2) return null;
        try {
            double lat = Double.parseDouble(parts[0]);
            double lng = Double.parseDouble(parts[1]);
            if (lat < -90 || lat > 90 || lng < -180 || lng > 180) return null;
            return new double[]{lat, lng};
        } catch (Exception ignore) {
            return null;
        }
    }

    // --- JPEG(85) + 1600px limit + 1.5MB hedefleyerek kalite ayarla
    private String encodeBitmapSmart(@NonNull Bitmap bmp) {
        Bitmap scaled = downscale(bmp, 1600);
        int quality = 85;
        byte[] out;
        do {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            out = baos.toByteArray();
            // 1.5 MB hedef; çok büyükse kaliteyi azalt
            if (out.length > (1_500_000) && quality > 60) {
                quality -= 5;
            } else break;
        } while (quality >= 60);
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private void setSubmitting(boolean submitting) {
        isSubmitting = submitting;
        View btn = findViewById(R.id.save_companion_button);
        if (btn != null) btn.setEnabled(!submitting);
    }

}