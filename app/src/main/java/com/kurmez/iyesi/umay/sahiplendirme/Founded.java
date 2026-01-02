package com.kurmez.iyesi.umay.sahiplendirme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.location.Address;
import android.location.Geocoder;
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
import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Soul;
import com.kurmez.iyesi.kurmes.utilities.adapters.ImageSliderAdapter;
import com.kurmez.iyesi.kurmes.utilities.helper.CFObligations;
import com.kurmez.iyesi.kayra.AppCheckTokenProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class Founded extends AppCompatActivity {

    private static final String L = "Founded";
    private static final boolean VERBOSE_JSON = true;
    private static final int MAX_LOG_CHARS = 4000;
    private boolean retried = false;

    private JSONObject payload;
    private volatile boolean isSubmitting = false;

    // UI
    private ViewPager2 photosSlider;
    private View saveBtn;
    private AutoCompleteTextView speciesInput;
    private EditText dateView;
    private EditText placeView;

    // Permission constants
    public static final String PERM_READ_EXTERNAL = Manifest.permission.READ_EXTERNAL_STORAGE;  // API<33
    public static final String PERM_READ_MEDIA_IMAGES = Manifest.permission.READ_MEDIA_IMAGES;  // API 33+

    // Auth/State
    @Nullable private FirebaseAuth mAuth;
    @Nullable private FirebaseUser user;
    private FirebaseAuth.AuthStateListener authListener;
    private volatile boolean isAnonSigningIn = false;

    // CF / backend
    private CFObligations cfObl;

    // Location
    private FusedLocationProviderClient fusedLocationClient;
    private final CancellationTokenSource placeCts = new CancellationTokenSource();
    private static final int REQ_LOC_FOR_PLACE = 2013;
    @Nullable private String lastAdminPath = null;
    private Double lastLat, lastLng;

    // Photos
    public static final int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    public static final int REQUEST_IMAGE_CAPTURE = 1034;
    private final List<Bitmap> photoList = new ArrayList<>();
    private ImageSliderAdapter sliderAdapter;

    // Tıklama akışlarında, izin sonrası devam ettirmek için
    private @Nullable Runnable pendingAfterLocation;

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long t0 = System.currentTimeMillis();
        Log.i(L, "onCreate() → GİRİŞ");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        // Auth
        mAuth = FirebaseAuth.getInstance();
        user = mAuth.getCurrentUser();

        // CFObligations init (region: us-central1)
        cfObl = new CFObligations(this, "iyesi-aef03", "us-central1", /*verboseJson*/ true);

        // UI bind
        speciesInput = findViewById(R.id.companion_species);
        dateView     = findViewById(R.id.companion_found_date);
        placeView    = findViewById(R.id.companion_found_place);
        photosSlider = findViewById(R.id.founded_photos_slider);
        saveBtn      = findViewById(R.id.save_companion_button);

        if (saveBtn != null) {
            saveBtn.setEnabled(true);
            saveBtn.setClickable(true);
            saveBtn.bringToFront();
            saveBtn.setOnClickListener(this::onRegisterCompanionClick);
            saveBtn.setOnLongClickListener(v -> { Log.d(L, "saveBtn long click"); return true; });
        } else {
            Log.e(L, "[BOOT] saveBtn NOT FOUND in activity_founded layout!");
        }

        saveBtn.post(() -> Log.d(
                "SaveState",
                "enabled=" + saveBtn.isEnabled() +
                        " clickable=" + saveBtn.isClickable() +
                        " visible=" + (saveBtn.getVisibility()==View.VISIBLE) +
                        " alpha=" + saveBtn.getAlpha()
        ));

        // Auth listener
        authListener = fa -> {
            user = fa.getCurrentUser();
            boolean ready = (user != null);
            Log.i(L, "Auth state → ready=" + ready);
            if (ready) { checkPendingOnStart(); }
        };
        mAuth.addAuthStateListener(authListener);

        // Gerekirse anonim giriş
        ensureAnonymousAuthIfNeeded();

        // ViewPager2 adapter
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photosSlider.setAdapter(sliderAdapter);

        // Varsayılan tarih
        if (dateView.getText() == null || dateView.getText().toString().trim().isEmpty()) {
            dateView.setText(todayIsoDate());
        }

        // İzin / picker
        requestGalleryPermissionIfNeeded();

        // Konum client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        tryFillLastLocation();

        // Place alanı tıklamaları
        placeView.setOnClickListener(v -> autoFillPlaceFromLocation());
        placeView.setOnLongClickListener(v -> { autoFillPlaceFromLocation(); return true; });

        // Intent’ten gelen tahmin (species)
        String predicted = getIntent().getStringExtra("predictedSpecies");
        if (predicted != null) {
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_dropdown_item_1line, new String[]{predicted});
            speciesInput.setAdapter(adapter);
            speciesInput.setText(predicted, false);
            Log.d(L, "predictedSpecies=" + predicted);
        }

        // Foto girişleri
        takePhotosFromIntentOrFinish();
        logIntentIfAny();
        logUiState("onCreate/end", /*userInitiated=*/false);

        Log.i(L, "onCreate() → ÇIKIŞ (" + (System.currentTimeMillis() - t0) + " ms)");
    }

    // sınıf içine (örn. SAVE FLOW bölümünden önce) ekle
    private void logIntentIfAny() {
        Intent it = getIntent();
        if (it == null) { Log.d(L, "[UI] intent=null"); return; }
        Log.d(L, "[UI] intent extras="
                + " snapshot=" + it.hasExtra("snapshot")
                + " photoUris=" + it.getStringArrayListExtra("photoUris")
                + " photoPaths=" + it.getStringArrayListExtra("photoPaths")
                + " predictedSpecies=" + it.getStringExtra("predictedSpecies"));
    }

    // ESAS DEĞİŞİKLİK
    private void logUiState(String where, boolean userInitiated) {
        FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
        if (isAnonSigningIn || cur == null) {
            if (userInitiated) {
                Toast.makeText(this, "Bağlantı hazırlanıyor, lütfen tekrar deneyin…", Toast.LENGTH_SHORT).show();
            }
            Log.w(L, "[UI] not ready | isAnonSigningIn=" + isAnonSigningIn + " cur=" + (cur==null) + " @" + where);
            return;
        }
        String uid = (cur.isAnonymous() ? "anon:"+cur.getUid() : cur.getUid());
        String species = textOf(speciesInput);
        String date    = textOf(dateView);
        String place   = textOf(placeView);
        int photos     = (photoList == null ? -1 : photoList.size());
        Log.i(L, "[UI] " + where + " | uid=" + uid + " | submitting=" + isSubmitting
                + " | species=" + species + " | date=" + date + " | place=" + place
                + " | lastAdminPath=" + lastAdminPath
                + " | lastLatLng=" + (lastLat==null?"-":lastLat) + "," + (lastLng==null?"-":lastLng)
                + " | photos=" + photos);
    }

    // LOCATION → place alanını otomatik doldur
    private void autoFillPlaceFromLocation() {
        Log.i(L, "autoFillPlaceFromLocation()");
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(L, "Location permission missing → requesting…");
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOC_FOR_PLACE
            );
            return;
        }

        try {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, placeCts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            Log.i(L, "currentLocation ok lat=" + loc.getLatitude() + " lng=" + loc.getLongitude());
                            setCoordsToPlace(loc);
                            ensureAdminPathAsync(loc.getLatitude(), loc.getLongitude(), ap -> {
                                lastAdminPath = ap;
                                String pretty = (ap == null) ? formatLatLng(loc.getLatitude(), loc.getLongitude()) : ap;
                                placeView.setText(pretty);
                                Toast.makeText(this, "Bulunduğu yer güncellendi", Toast.LENGTH_SHORT).show();
                            });
                        } else {
                            Log.w(L, "currentLocation null → fallback to lastLocation");
                            fillPlaceFromLastLocationFallback();
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.w(L, "currentLocation failed: " + e.getMessage());
                        fillPlaceFromLastLocationFallback();
                    });
        } catch (Exception e) {
            Log.w(L, "currentLocation exception: " + e.getMessage());
            fillPlaceFromLastLocationFallback();
        }
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void fillPlaceFromLastLocationFallback() {
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(l -> {
                    if (l != null) {
                        Log.i(L, "lastLocation ok lat=" + l.getLatitude() + " lng=" + l.getLongitude());
                        setCoordsToPlace(l);
                        ensureAdminPathAsync(l.getLatitude(), l.getLongitude(), ap -> {
                            lastAdminPath = ap;
                            String pretty = (ap == null) ? formatLatLng(l.getLatitude(), l.getLongitude()) : ap;
                            placeView.setText(pretty);
                            Toast.makeText(this, "Bulunduğu yer güncellendi", Toast.LENGTH_SHORT).show();
                        });
                    } else {
                        Log.w(L, "lastLocation still null");
                        Toast.makeText(this, "Konum alınamadı. Lütfen GPS'i açın.", Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e ->
                        Log.w(L, "lastLocation failed: " + (e == null ? "-" : e.getMessage())));
    }

    @Override
    protected void onDestroy() {
        placeCts.cancel();
        if (mAuth != null && authListener != null) mAuth.removeAuthStateListener(authListener);
        super.onDestroy();
    }

    /* ============================== UI EVENTS ============================== */

    public void onPickImage(View view) {
        Log.d(L, "onPickImage()");
        try {
            pickMultiple.launch("image/*");
        } catch (Exception e) {
            Log.w(L, "PhotoPicker not available, fallback to ACTION_GET_CONTENT");
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.setType("image/*");
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
            }
        }
    }

    public void onStartCamera(View view) {
        Log.d(L, "onStartCamera()");
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    private final ActivityResultLauncher<String> pickMultiple =
            registerForActivityResult(new GetMultipleContents(), uris -> {
                Log.i(L, "pickMultiple() → GİRİŞ count=" + (uris == null ? 0 : uris.size()));
                if (uris != null) {
                    int ok = 0, fail = 0;
                    for (Uri u : uris) {
                        Bitmap b = decodeBitmapFromUri(u);
                        if (b != null) { photoList.add(b); ok++; } else { fail++; }
                    }
                    notifySlider();
                    Log.i(L, "pickMultiple() → ÇIKIŞ ok=" + ok + " fail=" + fail + " totalPhotos=" + photoList.size());
                }
            });

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        Log.d(L, "onActivityResult() req=" + requestCode + " result=" + resultCode);
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        int before = photoList.size();
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
            notifySlider();
            Log.i(L, "onActivityResult(GALLERY) added=" + (photoList.size() - before) + " total=" + photoList.size());
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            Bundle extras = data.getExtras();
            if (extras != null) {
                Object o = extras.get("data");
                if (o instanceof Bitmap) {
                    photoList.add((Bitmap) o);
                    notifySlider();
                    Log.i(L, "onActivityResult(CAMERA) added=1 total=" + photoList.size());
                }
            }
        }
    }

    /* ============================ CLICK FLOW ============================ */

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void onRegisterCompanionClick(View v) {
        Log.w(L, "[CLICK] onRegisterCompanionClick → GİRİŞ");
        logUiState("beforeClick", /*userInitiated=*/true);

        if (isSubmitting) {
            Log.w(L, "[CLICK] ignored: isSubmitting=true");
            return;
        }

        if (photoList == null || photoList.isEmpty()) {
            Toast.makeText(this, "Lütfen en az bir fotoğraf seçin.", Toast.LENGTH_SHORT).show();
            return;
        }

        // SADECE anonim giriş aktif şekilde sürerken blokla
        if (isAnonSigningIn) {
            Toast.makeText(this, "Bağlantı hazırlanıyor, lütfen tekrar deneyin…", Toast.LENGTH_SHORT).show();
            Log.w(L, "[CLICK] ignored: isAnonSigningIn=true");
            return;
        }

        // place alanı kontrolü
        String place = placeView.getText() == null ? "" : placeView.getText().toString().trim();
        boolean looksLikeCoords = place.matches("^\\s*-?\\d+(\\.\\d+)?\\s*,\\s*-?\\d+(\\.\\d+)?\\s*$");
        Log.d(L, "[CLICK] place=\"" + place + "\" looksLikeCoords=" + looksLikeCoords);

        if (place.isEmpty() || looksLikeCoords) {
            Log.d(L, "[CLICK] ensureLocationThen → save");
            ensureLocationThen(this::saveCompanionAsync);
        } else {
            Log.d(L, "[CLICK] direct save");
            saveCompanionAsync();
        }
        Log.i(L, "[CLICK] onRegisterCompanionClick → ÇIKIŞ");
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    private void ensureLocationThen(@NonNull Runnable next) {
        Log.i(L, "[LOC] ensureLocationThen → GİRİŞ");
        pendingAfterLocation = next;

        if (!hasFineLocationPermission()) {
            Log.d(L, "[LOC] requesting ACCESS_FINE_LOCATION");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOC_FOR_PLACE);
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        Log.i(L, "[LOC] lastLocation lat=" + loc.getLatitude() + " lng=" + loc.getLongitude());
                        setCoordsToPlace(loc);
                        ensureAdminPathAsync(loc.getLatitude(), loc.getLongitude(), ap -> {
                            Log.d(L, "[LOC] adminPath from lastLocation = " + ap);
                            if (ap != null && !ap.isEmpty()) placeView.setText(ap);
                            runPendingAfterLocation();
                        });
                    } else {
                        Log.w(L, "[LOC] lastLocation null → runPending");
                        runPendingAfterLocation();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(L, "[LOC] lastLocation fail: " + e.getMessage());
                    runPendingAfterLocation();
                });
    }

    private void runPendingAfterLocation() {
        Log.d(L, "[LOC] runPendingAfterLocation()");
        Runnable r = pendingAfterLocation;
        pendingAfterLocation = null;
        if (r != null) r.run();
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOC_FOR_PLACE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Log.d(L, "onRequestPermissionsResult(REQ_LOC_FOR_PLACE) granted=" + granted);
            if (granted) {
                if (pendingAfterLocation != null) {
                    ensureLocationThen(() -> { if (pendingAfterLocation == null) saveCompanionAsync(); });
                } else {
                    autoFillPlaceFromLocation();
                }
            } else {
                runPendingAfterLocation(); // eldeki verilerle sürdür
            }
        }
    }

    /* ============================ PHOTO FLOW =========================== */

    private void takePhotosFromIntentOrFinish() {
        Log.i(L, "takePhotosFromIntentOrFinish() → GİRİŞ");
        int added = 0;

        byte[] snap = getIntent().getByteArrayExtra("snapshot");
        if (snap != null && snap.length > 0) {
            Bitmap bmp = BitmapFactory.decodeByteArray(snap, 0, snap.length);
            if (bmp != null) {
                photoList.add(bmp); added++;
                Log.d(L, "snapshot added size=" + snap.length + " bytes "
                        + "w=" + bmp.getWidth() + " h=" + bmp.getHeight());
                String predicted = getIntent().getStringExtra("predictedSpecies");
                if (predicted != null && speciesInput != null) speciesInput.setText(predicted);
                notifySlider();
                Log.i(L, "takePhotosFromIntentOrFinish() → ÇIKIŞ (snapshot) total=" + photoList.size());
                return;
            }
        }

        ArrayList<String> uris = getIntent().getStringArrayListExtra("photoUris");
        if (uris != null && !uris.isEmpty()) {
            for (String s : uris) {
                Bitmap b = decodeBitmapFromUri(Uri.parse(s));
                if (b != null) { photoList.add(b); added++; }
            }
            notifySlider();
            Log.i(L, "takePhotosFromIntentOrFinish() → ÇIKIŞ (uris) added=" + added + " total=" + photoList.size());
            return;
        }

        ArrayList<String> paths = getIntent().getStringArrayListExtra("photoPaths");
        if (paths != null && !paths.isEmpty()) {
            for (String p : paths) {
                Bitmap b = BitmapFactory.decodeFile(p);
                if (b != null) { photoList.add(b); added++; }
            }
            notifySlider();
            Log.i(L, "takePhotosFromIntentOrFinish() → ÇIKIŞ (paths) added=" + added + " total=" + photoList.size());
            return;
        }

        Toast.makeText(this, "Foto bulunamadı. Lütfen seçin.", Toast.LENGTH_SHORT).show();
        if (pickMultiple != null) pickMultiple.launch("image/*");
        Log.i(L, "takePhotosFromIntentOrFinish() → ÇIKIŞ (picker launched)");
    }

    private void notifySlider() {
        if (sliderAdapter != null) sliderAdapter.notifyDataSetChanged();
    }

    private Bitmap decodeBitmapFromUri(Uri uri) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap raw = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
                Bitmap out = downscale(raw, 1600);
                Log.d(L, "decodeBitmapFromUri28+ w=" + raw.getWidth() + " h=" + raw.getHeight()
                        + " -> w=" + out.getWidth() + " h=" + out.getHeight());
                return out;
            } else {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) return null;
                byte[] bytes = readAllBytes(is);
                Bitmap raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                InputStream is2 = new java.io.ByteArrayInputStream(bytes);
                int degrees = getExifRotation(is2);
                if (is2 != null) is2.close();
                Bitmap fixed = rotate(raw, degrees);
                Bitmap out = downscale(fixed, 1600);
                Log.d(L, "decodeBitmapFromUri w=" + raw.getWidth() + " h=" + raw.getHeight()
                        + " rot=" + degrees + " -> w=" + out.getWidth() + " h=" + out.getHeight());
                return out;
            }
        } catch (Exception e) {
            Log.e(L, "decode error: " + e.getMessage());
            return null;
        }
    }

    private static Bitmap downscale(Bitmap src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min(1f, maxSide / (float) Math.max(w, h));
        if (scale >= 0.999f) return src;
        int nw = Math.round(w * scale), nh = Math.round(h * scale);
        return Bitmap.createScaledBitmap(src, nw, nh, true);
    }

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

    private static byte[] readAllBytes(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        is.close();
        return baos.toByteArray();
    }

    /* ============================ SAVE FLOW ============================ */

    private void saveCompanionAsync() {
        long t0 = System.currentTimeMillis();
        FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
        boolean authenticated = (cur != null);
        Log.i(L, "[SAVE] saveCompanionAsync → GİRİŞ auth=" + authenticated + " isAnon=" + (authenticated && cur.isAnonymous()));
        logUiState("saveCompanionAsync/enter",false);

        String id;
        if (authenticated) {
            id = cur.getUid();
            Log.i(L, "[SAVE] Authenticated (uid=" + id + ") → performSubmit");
            performSubmitCompanion(id);
            Log.i(L, "[SAVE] ÇIKIŞ (direct submit) dt=" + (System.currentTimeMillis() - t0) + " ms");
            return;
        } else {
            id = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        }
        final String deviceIdForCb = id;

        Log.i(L, "[SAVE] CF checkPendingCompanion START deviceId=" + id);
        cfObl.checkPendingCompanion(id, new CFObligations.PendingListener() {
            @Override
            public void onResult(@Nullable JSONObject companion) {
                Log.i(L, "[SAVE] checkPendingCompanion OK has=" + (companion != null));
                runOnUiThread(() -> {
                    if (companion == null) {
                        Log.d(L, "[SAVE] pending yok → performSubmit");
                        performSubmitCompanion(deviceIdForCb);
                    } else {
                        Log.d(L, "[SAVE] pending VAR → openCompanion");
                        openCompanionFromJson(deviceIdForCb, companion);
                    }
                });
            }
            @Override
            public void onError(@NonNull Throwable error, int httpCode) {
                String msg = String.valueOf(error.getMessage());
                Log.w(L, "[SAVE] checkPending ERROR code=" + httpCode + " msg=" + msg);

                if (msg != null && msg.toLowerCase(java.util.Locale.US).contains("not authenticated")) {
                    Log.i(L, "[SAVE] not authenticated → ensureAnonymousAuthThen → performSubmit");
                    ensureAnonymousAuthThen(() -> performSubmitCompanion(deviceIdForCb));
                    return;
                }

                if (httpCode == 404) {
                    Log.i(L, "[SAVE] pending 404 → performSubmit");
                    runOnUiThread(() -> performSubmitCompanion(deviceIdForCb));
                    return;
                }

                runOnUiThread(() -> {
                    if (httpCode == 401 || httpCode == 403) {
                        Toast.makeText(Founded.this,
                                "Doğrulama hatası (" + httpCode + "). Lütfen oturum açın ve uygulamayı doğrulayın.",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(Founded.this,
                                "Sunucu hatası: " + (error.getMessage() == null ? "-" : error.getMessage()),
                                Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void performSubmitCompanion(String actorId) {
        if (isSubmitting) {
            Log.w(L, "[SUBMIT] already submitting → ignore");
            return;
        }
        setSubmitting(true);
        long t0 = System.currentTimeMillis();
        Log.i(L, "[SUBMIT] performSubmitCompanion → GİRİŞ actorId=" + actorId);
        logUiState("performSubmitCompanion/enter",false);

        try {
            String species    = safeOrTodo(textOf(speciesInput));
            String breed      = "TODO";
            String age        = "TODO";
            String health     = "TODO";
            String foundDateI = textOf(dateView);
            String foundPlace = textOf(placeView);

            String foundDate = foundDateI.isEmpty() ? todayIsoDate() : normalizeFoundDate(foundDateI);
            if (!foundDate.equals(foundDateI)) {
                Log.d(L, "[SUBMIT] normalize date: " + foundDateI + " → " + foundDate);
                dateView.setText(foundDate);
            }

            double latNum, lngNum;
            double[] parsed = parseLatLng(foundPlace);
            if (parsed != null) {
                latNum = parsed[0]; lngNum = parsed[1];
                Log.d(L, "[SUBMIT] parsed latLng from place: " + latNum + "," + lngNum);
                ensureAdminPathAsync(latNum, lngNum, ap -> {
                    lastAdminPath = ap;
                    Log.d(L, "[SUBMIT] adminPath resolved: " + ap);
                    String pretty = (ap == null ? formatLatLng(latNum, lngNum) : ap);
                    placeView.setText(pretty);
                });
                foundPlace = formatLatLng(latNum, lngNum);
            } else if (lastLat != null && lastLng != null) {
                latNum = lastLat; lngNum = lastLng;
                Log.d(L, "[SUBMIT] using lastLatLng: " + latNum + "," + lngNum);
                ensureAdminPathAsync(lastLat, lastLng, ap -> {
                    lastAdminPath = ap;
                    Log.d(L, "[SUBMIT] adminPath from lastLatLng: " + ap);
                    if (ap != null) placeView.setText(ap);
                });
            } else {
                Log.e(L, "[SUBMIT] konum yok → abort");
                Toast.makeText(this, "Konum alınamadı. 'lat, lng' girin veya konum izni verin.", Toast.LENGTH_LONG).show();
                setSubmitting(false);
                return;
            }

            if (foundDate.isEmpty()) {
                Log.e(L, "[SUBMIT] tarih boş → abort");
                dateView.setError("Tarih gerekli (yyyy-MM-dd)");
                setSubmitting(false);
                return;
            }
            if (photoList.isEmpty()) {
                Log.e(L, "[SUBMIT] foto yok → abort");
                Toast.makeText(this, "Fotoğraf yok!", Toast.LENGTH_SHORT).show();
                setSubmitting(false);
                return;
            }

            long timestamp = System.currentTimeMillis();

            Soul soul = new Soul.Builder()
                    .species(species)
                    .breed(breed)
                    .age(age)
                    .health(health)
                    .foundDate(foundDate)
                    .foundLocation( lastAdminPath != null ? lastAdminPath
                            : (placeView.getText()==null ? foundPlace
                            : placeView.getText().toString()) )
                    .timestamp(timestamp)
                    .ts(timestamp)
                    .status("pending")
                    .latLng(latNum, lngNum)
                    .build();

            // JSON (imageUrl sonradan eklenecek)
            payload = soul.toJson();
            FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
            payload.put("actorKind",  (cur != null) ? "uid" : "device");
            payload.put("deviceId",   actorId);
            payload.put("requestKind","soul_inneed");

            String ap = placeView.getText() == null ? null : placeView.getText().toString().trim();
            if (ap != null && ap.contains("/")) payload.put("adminPath", ap);
            if (lastAdminPath != null) payload.put("adminPath", lastAdminPath);

            if (VERBOSE_JSON) logChunked("[SUBMIT] payload (pre-image)", payload.toString());

            // FOTOĞRAFI ÖNCE STORAGE'A YÜKLE → URL ile submit
            uploadPhotoThenSubmit(payload, photoList.get(0));

            Log.i(L, "[SUBMIT] performSubmitCompanion → ÇIKIŞ prepare dt=" + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            Log.e(L, "[SUBMIT] fatal", t);
            setSubmitting(false);
            Toast.makeText(this, "Beklenmeyen hata: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Fotoğrafı Firebase Storage'a yükler; başarılıysa imageUrl ekleyip submit eder.
     *  Hata alırsa placeholder ile submit eder (retry edilen eski davranışla uyumlu). */
    private void uploadPhotoThenSubmit(@NonNull JSONObject payload, @NonNull Bitmap bmp) {
        try {
            FirebaseUser cur = FirebaseAuth.getInstance().getCurrentUser();
            String owner = (cur != null ? cur.getUid() : Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));

            byte[] jpeg = encodeJpegBytesSmart(bmp);
            int kb = jpeg.length / 1024;
            Log.d(L, "[UPLOAD] jpeg size=" + kb + "KB");

            String filename = "souls/" + owner + "/" + System.currentTimeMillis() + "/image.jpg";
            StorageReference ref = FirebaseStorage.getInstance().getReference().child(filename);

            UploadTask task = ref.putBytes(jpeg, new StorageMetadata.Builder()
                    .setContentType("image/jpeg").build());

            task.addOnSuccessListener(snap ->
                    ref.getDownloadUrl().addOnSuccessListener(uri -> {
                        try {
                            payload.put("imageUrl", uri.toString());
                        } catch (Exception ignore) {}
                        Log.i(L, "[UPLOAD] SUCCESS → " + uri);
                        submitWithRetry(payload, false);
                    }).addOnFailureListener(e -> {
                        Log.w(L, "[UPLOAD] getDownloadUrl FAIL: " + e.getMessage());
                        try { payload.put("imageUrl", "placeholder://holder"); } catch (Exception ignore) {}
                        submitWithRetry(payload, true);
                    })
            ).addOnFailureListener(e -> {
                Log.w(L, "[UPLOAD] putBytes FAIL: " + (e==null?"-":e.getMessage()));
                try { payload.put("imageUrl", "placeholder://holder"); } catch (Exception ignore) {}
                submitWithRetry(payload, true);
            });

        } catch (Throwable t) {
            Log.w(L, "[UPLOAD] unexpected: " + t.getMessage());
            try { payload.put("imageUrl", "placeholder://holder"); } catch (Exception ignore) {}
            submitWithRetry(payload, true);
        }
    }

    private void submitWithRetry(@NonNull JSONObject payload, boolean retried) {
        this.payload = payload;
        this.retried = retried;
        long t0 = System.currentTimeMillis();
        Log.i(L, "CF submitSoulInNeed START" + (retried ? " (retry/placeholder)" : ""));
        // CFObligations iç retry’ı açık: image hatasında tek sefer fallback zaten var
        cfObl.submitSoulInNeed(payload, /*retryEnabled*/ true, new CFObligations.SubmitListener() {
            @Override
            public void onSuccess(@NonNull JSONObject resp) {
                long dt = System.currentTimeMillis() - t0;
                Log.i(L, "CF submitSoulInNeed END OK (" + dt + " ms)");
                if (VERBOSE_JSON && resp != null) logChunked("submit.response", resp.toString());
                runOnUiThread(() -> {
                    setSubmitting(false);
                    try {
                        String key = resp.optString("key", null);
                        if (key == null || key.isEmpty()) {
                            Toast.makeText(Founded.this, "Yanıt anahtarı alınamadı", Toast.LENGTH_LONG).show();
                            return;
                        }
// Founded.java (onSuccess içinde)
                        Intent i = new Intent(Founded.this, Companion.class);
                        i.putExtra("requestKey", key);
                        i.putExtra("node", "soul_inneed");

// UI’yı hemen doldurabilmek için payload’dan alanları geçir
                        i.putExtra("species",      payload.optString("species"));
                        i.putExtra("breed",        payload.optString("breed", "TODO"));
                        i.putExtra("foundDate",    payload.optString("foundDate"));
                        i.putExtra("foundPlace",   payload.optString("adminPath",
                                payload.optString("foundLocation")));
                        i.putExtra("photoUrl",     payload.optString("imageUrl",
                                payload.optString("imageResId", "")));
                        i.putExtra("profileId",    FirebaseAuth.getInstance().getCurrentUser()!=null
                                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : "");
                        i.putExtra("deviceId",     payload.optString("deviceId",""));

                        startActivity(i);
                        finish();

                    } catch (Exception je) {
                        Toast.makeText(Founded.this, "Yanıt çözümlenemedi", Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(@NonNull Throwable error, int httpCode, boolean wasRetried) {
                long dt = System.currentTimeMillis() - t0;
                String msg = String.valueOf(error.getMessage());
                Log.w(L, "CF submitSoulInNeed END ERROR (" + dt + " ms): " + msg + " code=" + httpCode + " retried=" + wasRetried);

                runOnUiThread(() -> {
                    setSubmitting(false);
                    Toast.makeText(Founded.this,
                            "Sunucu hatası: " + (msg == null || msg.isEmpty() ? "-" : msg),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openCompanionFromJson(@NonNull String deviceId, @NonNull JSONObject companion) {
        Log.i(L, "openCompanionFromJson() → deviceId=" + deviceId);
        if (VERBOSE_JSON) logChunked("pending.companion", companion.toString());
        Intent intent = new Intent(Founded.this, Companion.class);
        intent.putExtra("deviceId", deviceId);
        intent.putExtra("species", companion.optString("species"));
        intent.putExtra("foundDate", companion.optString("foundDate"));
        intent.putExtra("foundPlace", companion.optString("foundLocation"));
        intent.putExtra("photoUrl", companion.optString("imageResId", companion.optString("imageUrl")));
        intent.putExtra("profileId", companion.optString("finderName", companion.optString("finder")));
        intent.putExtra("node", "soul_inneed");
        startActivity(intent);
        finish();
    }

    private void checkPendingOnStart() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        Log.i(L, "CF checkPendingCompanion START (onStart) deviceId=" + deviceId);
        cfObl.checkPendingCompanion(deviceId, new CFObligations.PendingListener() {
            @Override public void onResult(@Nullable JSONObject companion) {
                Log.i(L, "CF checkPendingCompanion END (onStart) OK has=" + (companion != null));
                if (companion != null) runOnUiThread(() -> openCompanionFromJson(deviceId, companion));
            }
            @Override public void onError(@NonNull Throwable error, int httpCode) {
                Log.w(L, "CF checkPendingCompanion END (onStart) ERROR code=" + httpCode + " msg=" + (error.getMessage() == null ? "null" : error.getMessage()));
                if (httpCode == 401 || httpCode == 403) {
                    runOnUiThread(() -> Toast.makeText(Founded.this,
                            "Doğrulama hatası (" + httpCode + "). Lütfen oturum açın ve uygulamayı doğrulayın.",
                            Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    /* ============================== LOCATION ============================== */

    private boolean hasFineLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void tryFillLastLocation() {
        if (!hasFineLocationPermission()) {
            Log.d(L, "tryFillLastLocation() → permission missing");
            return;
        }
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(loc -> {
                    if (loc != null) {
                        setCoordsToPlace(loc);
                        Log.d(L, "lastLocation ok lat=" + lastLat + " lng=" + lastLng);
                        ensureAdminPathAsync(lastLat, lastLng, adminPath -> {
                            if (adminPath != null) placeView.setText(adminPath);
                        });
                    } else {
                        Log.d(L, "lastLocation is null");
                    }
                })
                .addOnFailureListener(e -> Log.e(L, "lastLocation err: " + e.getMessage()));
    }

    private void setCoordsToPlace(@NonNull Location loc) {
        lastLat = loc.getLatitude();
        lastLng = loc.getLongitude();
        placeView.setText(formatLatLng(lastLat, lastLng));
    }

    private static String formatLatLng(double lat, double lng) {
        return String.format(Locale.US, "%f, %f", lat, lng);
    }

    /** Geocoder ile TR/İL metni üretir. Başarısız olursa callback'e null gönderir. */
    private interface AdminPathCb { void onReady(@Nullable String adminPath); }

    private void ensureAdminPathAsync(double lat, double lng, @NonNull AdminPathCb cb) {
        Log.d(L, "ensureAdminPathAsync() → GİRİŞ lat=" + lat + " lng=" + lng);
        final Locale tr = new Locale("tr", "TR");
        final Geocoder geocoder = new Geocoder(this, tr);

        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lng, 1, new Geocoder.GeocodeListener() {
                @Override public void onGeocode(@NonNull List<Address> results) {
                    String ap = extractAdminPath(results);
                    Log.d(L, "ensureAdminPathAsync() API33 onGeocode → " + ap);
                    runOnUiThread(() -> cb.onReady(ap));
                }
                @Override public void onError(@Nullable String errorMessage) {
                    Log.w(L, "Geocoder onError: " + errorMessage);
                    runOnUiThread(() -> cb.onReady(null));
                }
            });
        } else {
            new Thread(() -> {
                try {
                    List<Address> res = geocoder.getFromLocation(lat, lng, 1);
                    String ap = extractAdminPath(res);
                    Log.d(L, "ensureAdminPathAsync() legacy → " + ap);
                    runOnUiThread(() -> cb.onReady(ap));
                } catch (Exception e) {
                    Log.w(L, "Geocoder error: " + e.getMessage());
                    runOnUiThread(() -> cb.onReady(null));
                }
            }).start();
        }
    }

    @Nullable
    private String extractAdminPath(@Nullable List<Address> res) {
        if (res == null || res.isEmpty()) return null;
        Address a = res.get(0);
        String admin = a.getAdminArea();            // örn: Ankara
        String countryCode = a.getCountryCode();    // örn: TR
        if (admin == null || countryCode == null) return null;
        String ap = (countryCode + "/" + admin).toUpperCase(new Locale("tr", "TR")); // TR/ANKARA
        Log.d(L, "extractAdminPath → " + ap + " (locality=" + a.getLocality() + ", subAdmin=" + a.getSubAdminArea() + ")");
        return ap;
    }

    /* ================================ UTILS ================================ */

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

    private void requestGalleryPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ActivityCompat.checkSelfPermission(this, PERM_READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                Log.d(L, "Request PERM_READ_MEDIA_IMAGES");
                ActivityCompat.requestPermissions(this, new String[]{PERM_READ_MEDIA_IMAGES}, 2001);
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, PERM_READ_EXTERNAL) != PackageManager.PERMISSION_GRANTED) {
                Log.d(L, "Request PERM_READ_EXTERNAL");
                ActivityCompat.requestPermissions(this, new String[]{PERM_READ_EXTERNAL}, 2002);
            }
        }
    }

    @NonNull
    private String normalizeFoundDate(@NonNull String in) {
        String s = in.trim();
        if (s.isEmpty()) return s;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.time.format.DateTimeFormatter[] fmts = new java.time.format.DateTimeFormatter[]{
                        java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy"),
                        java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy"),
                        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
                };
                java.time.LocalDate d = null;
                for (java.time.format.DateTimeFormatter f : fmts) {
                    try { d = java.time.LocalDate.parse(s, f); break; } catch (Exception ignore) {}
                }
                if (d == null) return s;
                return d.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
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
                if (last != null) Log.w(L, "normalizeFoundDate: " + last.getMessage());
                return s;
            }
        } catch (Throwable t) {
            Log.w(L, "normalizeFoundDate err: " + t.getMessage());
            return s;
        }
    }

    @Nullable
    private double[] parseLatLng(@Nullable String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
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

    /** Yüksek kalite/JPEG baytlarını döndürür (base64 değil). */
    private byte[] encodeJpegBytesSmart(@NonNull Bitmap bmp) {
        int ow = bmp.getWidth(), oh = bmp.getHeight();
        Bitmap scaled = downscale(bmp, 1600);
        int sw = scaled.getWidth(), sh = scaled.getHeight();
        int quality = 85;
        byte[] out;
        do {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            out = baos.toByteArray();
            if (out.length > 1_500_000 && quality > 60) {
                quality -= 5;
            } else break;
        } while (quality >= 60);
        int kb = out.length / 1024;
        Log.d(L, "encodeJpegBytesSmart orig=" + ow + "x" + oh
                + " scaled=" + sw + "x" + sh
                + " quality=" + quality + " size=" + kb + "KB");
        return out;
    }

    /** Eski kullanım kalırsa diye: base64 gerekli olursa hala var. */
    private String encodeBitmapSmart(@NonNull Bitmap bmp) {
        int ow = bmp.getWidth(), oh = bmp.getHeight();
        Bitmap scaled = downscale(bmp, 1600);
        int sw = scaled.getWidth(), sh = scaled.getHeight();
        int quality = 85;
        byte[] out;
        do {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            out = baos.toByteArray();
            if (out.length > 1_500_000 && quality > 60) {
                quality -= 5;
            } else break;
        } while (quality >= 60);
        int kb = out.length / 1024;
        Log.d(L, "encodeBitmapSmart base64 orig=" + ow + "x" + oh
                + " scaled=" + sw + "x" + sh
                + " quality=" + quality + " size=" + kb + "KB");
        return Base64.encodeToString(out, Base64.NO_WRAP);
    }

    private void setSubmitting(boolean submitting) {
        isSubmitting = submitting;
        if (saveBtn != null) saveBtn.setEnabled(!submitting);
        Log.d(L, "setSubmitting(" + submitting + ")");
    }

    @NonNull
    private String todayIsoDate() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                java.time.ZoneId zone = java.time.ZoneId.of("Europe/Istanbul");
                return java.time.LocalDate.now(zone)
                        .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
                fmt.setTimeZone(java.util.TimeZone.getTimeZone("Europe/Istanbul"));
                return fmt.format(new java.util.Date());
            }
        } catch (Throwable t) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
            return fmt.format(new java.util.Date());
        }
    }

    private static void logChunked(String prefix, String text) {
        if (text == null) { Log.d(L, prefix + " <null>"); return; }
        for (int i = 0; i < text.length(); i += MAX_LOG_CHARS) {
            Log.d(L, prefix + ": " + text.substring(i, Math.min(i + MAX_LOG_CHARS, text.length())));
        }
    }

    /* ============================ AUTH HELPERS ============================ */

    private void ensureAnonymousAuthIfNeeded() {
        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() != null) {
            user = mAuth.getCurrentUser();
            Log.d(L, "ensureAnonymousAuthIfNeeded(): has user uid=" + user.getUid());
            return;
        }
        // App seviyesinde kurulu yardımcıyı kullan (Application sınıfında yazdığımız cache’li fonksiyon)
        AppCheckTokenProvider.getAppCheckTokenCached(true, t -> {
            Log.d(L, "AppCheck warmup before anon sign-in, hasToken=" + (t != null));
            Log.i(L, "No Firebase user → signing in anonymously…");
            isAnonSigningIn = true;
            mAuth.signInAnonymously()
                    .addOnSuccessListener(r -> {
                        user = mAuth.getCurrentUser();
                        isAnonSigningIn = false;
                        Log.i(L, "Anonymous sign-in OK uid=" + (user != null ? user.getUid() : "-"));
                    })
                    .addOnFailureListener(e -> {
                        isAnonSigningIn = false;
                        Log.w(L, "Anonymous sign-in FAIL: " + (e==null? "-" : e.getMessage()));
                    });
        });
    }

    private void ensureAnonymousAuthThen(@NonNull Runnable next) {
        mAuth = FirebaseAuth.getInstance();
        if (mAuth.getCurrentUser() != null) {
            user = mAuth.getCurrentUser();
            next.run();
            return;
        }
        isAnonSigningIn = true;
        Log.i(L, "ensureAnonymousAuthThen(): signing in anonymously…");
        mAuth.signInAnonymously()
                .addOnSuccessListener(r -> {
                    user = mAuth.getCurrentUser();
                    isAnonSigningIn = false;
                    Log.i(L, "Anonymous sign-in OK uid=" + (user != null ? user.getUid() : "-"));
                    next.run();
                })
                .addOnFailureListener(e -> {
                    isAnonSigningIn = false;
                    Log.w(L, "Anonymous sign-in FAIL: " + (e==null? "-" : e.getMessage()));
                    next.run(); // yine de ilerlet (backend kurallarına göre reddedebilir)
                });
    }

    /* ============================ HTTP STATUS ============================ */

    private static int inferHttpStatus(Throwable error) {
        if (error == null) return -1;
        final String m = String.valueOf(error.getMessage());
        if (m.contains("401") || m.contains("UNAUTHENTICATED")) return 401;
        if (m.contains("403") || m.contains("PERMISSION_DENIED") || m.contains("AppCheck")) return 403;
        if (m.contains("404") || m.contains("NOT_FOUND")) return 404;
        if (m.contains("500")) return 500;
        return -1;
    }
}
