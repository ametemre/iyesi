package com.kurmez.iyesi.umay.sahiplendirme;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.location.Address;
import android.location.Geocoder;
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
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;

import com.kurmez.iyesi.kurmes.utilities.adapters.ImageSliderAdapter;
import com.kurmez.iyesi.R;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class Founded extends AppCompatActivity {

    private static final int IMAGE_PICK = 100;
    public final String LOG_TAG = "MLImageHelper";
    public final static int CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE = 1034;
    private static final int REQUEST_IMAGE_CAPTURE = 1;
    public final static int PICK_IMAGE_ACTIVITY_REQUEST_CODE = 1064;
    public final static int REQUEST_READ_EXTERNAL_STORAGE = 2031;

    File photoFile;
    private List<Bitmap> photoList;
    private ImageView inputImageView;
    private AutoCompleteTextView outputTextView;
    private EditText dateView, placeView;
    private ImageSliderAdapter sliderAdapter;

    @SuppressLint("ObsoleteSdkInt")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);
        photoList = new ArrayList<>();
        checkPendingCompanionAndRedirect();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_READ_EXTERNAL_STORAGE);
            }
        }
        //inputImageView = findViewById(R.id.slider_image);
        outputTextView = findViewById(R.id.companion_species);
        dateView       = findViewById(R.id.companion_found_date);
        placeView      = findViewById(R.id.companion_found_place);
        // Retrieve the photos passed from Found activity
        // 1) Intent’ten tekil snapshot verisi gelmiş mi bak
        byte[] snapshotData = getIntent().getByteArrayExtra("snapshot");
        if (snapshotData != null) {
            // Tek fotoğraf modu
            Bitmap snapshot = BitmapFactory.decodeByteArray(snapshotData, 0, snapshotData.length);
            photoList.add(snapshot);

        } else {
            // Eski fotoğraf listesi modu
            ArrayList<Bitmap> list = getIntent().getParcelableArrayListExtra("photos");
            if (list != null) photoList.addAll(list);
        }
        if (photoList == null || photoList.isEmpty()) {
            Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        // Set up the image slider
        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        findViewById(R.id.take_anotherphoto_button).setOnClickListener(v -> finish()); // Return to capture screen
        findViewById(R.id.save_companion_button)
                .setOnClickListener(v -> {
                    Log.d("Founded", "save_companion_button clicked!");
                    saveCompanionAsync();

        });
        // --- Cinsi (Species) ---
        String predicted = getIntent().getStringExtra("predictedSpecies");
        if (predicted != null) {
            // Sadece bir öneri de olsa adapter’a koy
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    new String[]{ predicted }
            );
            outputTextView.setAdapter(adapter);
            outputTextView.setText(predicted, false);
            // 2) Direkt showDropDown() yerine view.post ile ertele
            outputTextView.post(() -> {
                // Burada artık token hazır, açılır liste sorunsuz gösterilir
                outputTextView.showDropDown();
            });
        }

        // --- Bulunduğu Tarih (Found Date) ---
        EditText dateView = findViewById(R.id.companion_found_date);
        String today = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                .format(new Date());
        dateView.setText(today);

        // --- Bulunduğu Yer (Found Place) ---
        EditText placeView = findViewById(R.id.companion_found_place);
        FusedLocationProviderClient locClient =
                LocationServices.getFusedLocationProviderClient(this);
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION },
                    42);
        } else {
            locClient.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
                            try {
                                List<Address> list =
                                        geocoder.getFromLocation(loc.getLatitude(),
                                                loc.getLongitude(), 1);
                                if (!list.isEmpty()) {
                                    Address a = list.get(0);
                                    placeView.setText(
                                            a.getLocality() + ", " + a.getCountryName()
                                    );
                                }
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    });
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_READ_EXTERNAL_STORAGE) {
            // Dizi boş mu diye kontrol et
            if (permissions.length > 0 && grantResults.length > 0) {
                String perm = permissions[0];
                boolean granted = (grantResults[0] == PackageManager.PERMISSION_GRANTED);
                Log.d(LOG_TAG, "grant result for " + perm + " is " + granted);

                if (!granted) {
                    Toast.makeText(this,
                            "Galeriden resim seçmek için izin gerekli",
                            Toast.LENGTH_SHORT).show();
                    // İzin reddedildiyse istersen kullanıcıya yönlendirme yapabilirsiniz
                }
            } else {
                Log.w(LOG_TAG,
                        "onRequestPermissionsResult: boş permissions/grantResults");
            }
        }
        // Eğer başka permission requestCode’larınız varsa onlar için de ayrı case’ler ekleyin
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK) return;

        switch (requestCode) {
            case PICK_IMAGE_ACTIVITY_REQUEST_CODE:
                // Galeriden birden fazla seçimi desteklemek için:
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    for (int i = 0; i < count; i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        Bitmap bmp = loadFromUri(uri);
                        photoList.add(bmp);
                    }
                } else if (data.getData() != null) {
                    Bitmap bmp = loadFromUri(data.getData());
                    photoList.add(bmp);
                }
                break;

            case REQUEST_IMAGE_CAPTURE:
            case CAPTURE_IMAGE_ACTIVITY_REQUEST_CODE:
                // Eğer kamera ile dosyaya yazdırdıysan getCapturedImage()’ı kullan:
                Bitmap camBmp = getCapturedImage();    // veya rotateIfRequired vs.
                photoList.add(camBmp);
                break;
        }
        // Slider’ı güncelle
        sliderAdapter.notifyDataSetChanged();
    }
    private void saveCompanionAsync() {
        // 1) Device ID ve kontrol URL'si
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String checkUrl = "https://us-central1-iyesi-a651a.cloudfunctions.net/checkPendingCompanion?deviceId=" + deviceId;

        OkHttpClient client = new OkHttpClient();

        Request checkRequest = new Request.Builder()
                .url(checkUrl)
                .get()
                .build();

        client.newCall(checkRequest).enqueue(new Callback() {
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String body = response.body().string();

                if (body.trim().equals("false")) {
                    // Kayıt yok → devam et ve kaydı oluştur
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
                        Log.e("Founded", "Parse error: " + e.getMessage());
                    }
                }
            }

            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("Founded", "Connection error: " + e.getMessage());
            }
        });
    }
    private void performSubmitCompanion(String deviceId) {
        // Field checks
        String species   = outputTextView.getText().toString().trim();
        String foundDate = dateView.getText().toString().trim();
        String foundPlace= placeView.getText().toString().trim();

        if (species.isEmpty()) { outputTextView.setError("Cinsi girin"); return; }
        if (foundDate.isEmpty()){ dateView.setError("Tarihi girin"); return; }
        if (foundPlace.isEmpty()){ placeView.setError("Yeri girin"); return; }
        if (photoList.isEmpty()){ Toast.makeText(this,"Fotoğraf yok!",Toast.LENGTH_SHORT).show(); return; }

        // Bitmap → Base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        photoList.get(0).compress(Bitmap.CompressFormat.PNG, 100, baos);
        String imgB64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

        // JSON oluştur
        JSONObject payload = new JSONObject();
        try {
            payload.put("deviceId",      deviceId);
            payload.put("species",       species);
            payload.put("foundDate",     foundDate);
            payload.put("foundLocation", foundPlace);
            payload.put("imageBase64",   imgB64);
        } catch (JSONException e) {
            e.printStackTrace();
            Toast.makeText(this,"Veri oluşturulamadı",Toast.LENGTH_SHORT).show();
            return;
        }

        // Cloud Function'a POST
        OkHttpClient client = new OkHttpClient();
        RequestBody body = RequestBody.create(
                payload.toString(),
                MediaType.get("application/json; charset=utf-8")
        );
        Request request = new Request.Builder()
                .url("https://us-central1-iyesi-a651a.cloudfunctions.net/submitSoulInNeed")
                .post(body)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                runOnUiThread(() ->
                        Toast.makeText(Founded.this,
                                "İstek gönderilemedi: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }

            @Override public void onResponse(Call call, Response response) throws IOException {
                String resp = response.body().string();
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
                            je.printStackTrace();
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

    public void onPickImage(View view) {
        // create Intent to take a picture and return control to the calling application
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");

        // If you call startActivityForResult() using an intent that no app can handle, your app will crash.
        // So as long as the result is not null, it's safe to use the intent.
        if (intent.resolveActivity(getPackageManager()) != null) {
            // Start the image capture intent to take photo
            startActivityForResult(intent, PICK_IMAGE_ACTIVITY_REQUEST_CODE);
        }
    }
    public void onStartCamera(View view){
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }
    //protected abstract void runDetection(Bitmap bitmap);
    private Bitmap getCapturedImage() {
        // Get the dimensions of the View
        int targetW = inputImageView.getWidth();
        int targetH = inputImageView.getHeight();

        BitmapFactory.Options bmOptions = new BitmapFactory.Options();
        bmOptions.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(photoFile.getAbsolutePath());
        int photoW = bmOptions.outWidth;
        int photoH = bmOptions.outHeight;
        int scaleFactor = Math.max(1, Math.min(photoW / targetW, photoH /targetH));

        bmOptions = new BitmapFactory.Options();
        bmOptions.inJustDecodeBounds = false;
        bmOptions.inSampleSize = scaleFactor;
        bmOptions.inMutable = true;
        return BitmapFactory.decodeFile(photoFile.getAbsolutePath(), bmOptions);
    }
    protected Bitmap loadFromUri(Uri photoUri) {
        Bitmap image = null;
        try {
            if (Build.VERSION.SDK_INT > 27) {
                ImageDecoder.Source source =
                        ImageDecoder.createSource(
                                this.getContentResolver(), photoUri
                        );
                image = ImageDecoder.decodeBitmap(source);
            } else {
                image = MediaStore.Images.Media
                        .getBitmap(this.getContentResolver(), photoUri);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return image;
    }
    private void checkPendingCompanionAndRedirect() {
        String deviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String checkUrl = "https://us-central1-iyesi-a651a.cloudfunctions.net/checkPendingCompanion?deviceId=" + deviceId;

        new OkHttpClient().newCall(new Request.Builder().url(checkUrl).get().build())
                .enqueue(new Callback() {
                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        String body = response.body().string();
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
                            Log.e("Founded", "Parse error: " + e.getMessage());
                        }
                    }

                    @Override
                    public void onFailure(Call call, IOException e) {
                        Log.e("Founded", "Connection error: " + e.getMessage());
                    }
                });
    }
}