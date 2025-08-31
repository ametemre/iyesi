package com.kurmez.iyesi.umay.sokak;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.maps.android.data.geojson.GeoJsonLayer;

import org.json.JSONObject;
import org.json.JSONException;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.MapStyleOptions;
import com.kurmez.iyesi.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import android.graphics.Point;
import android.view.GestureDetector;

import org.json.JSONArray;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.Nullable;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckTokenResult;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import org.json.JSONObject;
import okhttp3.HttpUrl;
/**
 * Harita sınıfı:
 * - GitHub’daki GeoBoundaries “main/releaseData/gbOpen” klasöründen
 *   ADM5 → ADM4 → ADM3 → ADM2 → ADM1 → ADM0 sırasıyla deneme yapar.
 * - Eğer hiçbir GeoBoundaries seviyesi bulunamazsa OSM fallback’i kullanır.
 * - Ekranın sağ üstündeki Spinner üzerinden “ADM5, ADM4, ADM3, ADM2, ADM1, ADM0, OSM”
 *   seçeneklerinden kullanıcı istediğini manuel olarak seçip yükleyebilir.
 * - Çizim işlemi GeoSon.filterAndDraw metoduna devredilmiştir.
 */
public class Harita implements OnMapReadyCallback {
    private boolean isMarking = false;
    private static final float MARKER_WIDTH_DP  = 48f;
    private static final float ICON_DP          = 20f;
    private static final float ICON_OFFSET_Y_DP = 13f;   // yukarı kaydırma
    private static final float ICON_OFFSET_X_DP = 1f;   // sağa kaydırma
    private static final String TAG = "Harita";
    private String currentCountryCode2,currentCountryCode3;
    private final List<String> levelOptions = Arrays.asList("ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM");
    private static final String GITHUB_BASE =""; //"https://github.com/wmgeolab/geoBoundaries/raw/refs/heads/main/releaseData/gbOpen/";// “main” branch altındaki releaseData klasörü (raw GitHub URL)
    private Marker draggableMarker;
    private GestureDetector gestureDetector;
    private GoogleMap mMap;
    public enum MapMode { DEFAULT, FEEDING, NEST, SHELTER, TASK }  // Görev=TASK
    private GeoJsonLayer layerCountry,layerProvince,layerDistrict;
    private final FragmentActivity activity;
    private Marker tempMarker;
    private final FusedLocationProviderClient locationClient;
    private final ActivityResultLauncher<String[]> permissionLauncher;
    private Spinner spinnerLevels;
    private boolean mapReady,countryResolved,ready = false;
    private LatLng centerPoint;
    private final double radiusMeters = 50000; // Örneğin 50 km
    private static final Map<String, BitmapDescriptor> iconCache = new HashMap<>();
    private static final String CF_BASE = "https://us-central1-iyesi-a651a.cloudfunctions.net";

    private final java.util.List<com.google.android.gms.maps.model.Marker> renderedMarkers = new java.util.ArrayList<>();



    private MapMode mode = MapMode.DEFAULT;
    private boolean isPlacing = false;
    private GestureDetector placementDetector;



    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        ready = true;
        mapReady = true;
        // Harita ayarlarını yapılandır

        mMap.getUiSettings().setAllGesturesEnabled(true);
        mMap.getUiSettings().setScrollGesturesEnabledDuringRotateOrZoom(true);
        // Harita stilini uygula
        try {
            boolean success = mMap.setMapStyle(
                    MapStyleOptions.loadRawResourceStyle(activity, R.raw.map_style_json));
            if (!success) {
                Log.e(TAG, "Harita stili yüklenemedi.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Harita stil uygulama hatası: " + e.getMessage());
        }

        // Konum izinlerini kontrol et
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                || ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {

            permissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        } else {
            getUserLocationAndLoadInitial();
        }
        // Harita.onMapReady(...) sonunda
        mMap.setOnMarkerClickListener(m -> {
            if (isPlacementMode()) return true; // yerleştirme modunda marker etkileşimini kapat
            // burada detay panelini aç (BottomSheet vs.)
            // String id = (String) m.getTag();
            // MarkerDetailsBottomSheet.newInstance(id, ...).show(...)
            String markerId = (String) m.getTag();   // create’de set edeceğiz
            if (markerId != null) {
                openMarkerDetails(markerId);         // aşağıdaki metod
            }
            return true; // tıklamayı tükettik, default infoWindow göstermeyelim
        });
    }
    private void openMarkerDetails(String markerId) {
        // ui/MarkerDetailsBottomSheet.java kullan
        try {
            com.kurmez.iyesi.umay.sokak.ui.MarkerDetailsBottomSheet
                    .newInstance(markerId, /* opsiyonel MarkerType */ null)
                    .show(activity.getSupportFragmentManager(), "marker_details");
        } catch (Exception e) {
            android.widget.Toast.makeText(activity, "Detay açılamadı", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    public void confirmMarkerLocation() {
        if (draggableMarker != null) {
            draggableMarker.setDraggable(false);
            openMarkerTypeSelectionDialog(draggableMarker);
            // draggableMarker = null;  // ← KALDIR (iş tamamlanınca temizle)
        }
    }
    private void openMarkerTypeSelectionDialog(Marker marker) {
        // "Default" yerine "Görev"
        final String[] types = {"Besleme", "Yuva", "Barınak", "Görev"};

        new AlertDialog.Builder(activity)
                .setTitle("Konum Türü Seçiniz")
                .setItems(types, (dialog, which) -> {
                    final String selectedType = types[which];

                    // Başlık + ikon
                    marker.setTitle(selectedType + " Noktası");
                    marker.setIcon(getCustomIcon(selectedType)); // mevcut fabrika/ikon fonksiyonun

                    // Cihaz ID (opsiyonel başlık)
                    final String deviceId = android.provider.Settings.Secure.getString(
                            activity.getContentResolver(),
                            android.provider.Settings.Secure.ANDROID_ID
                    );
                    FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
                    if (u == null) {
                        Toast.makeText(activity, "Devam etmek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
                        // activity.startActivity(new Intent(activity, com.kurmez.iyesi.Login.class));
                        return;
                    }
                    // Cloud Functions: markerCreate
                    Helpers.createMarkerOnCloud(
                            activity,
                            CF_BASE, // sınıf başında sabitle: "https://us-central1-iyesi-a651a.cloudfunctions.net"
                            marker.getPosition().latitude,
                            marker.getPosition().longitude,
                            selectedType,
                            /*species*/ null,
                            /*category*/ null,
                            /*note*/ null,
                            /*deviceId*/ deviceId,
                            new okhttp3.Callback() {
                                @Override public void onFailure(@NonNull okhttp3.Call call, @NonNull java.io.IOException e) {
                                    activity.runOnUiThread(() ->
                                            Toast.makeText(activity, "Marker oluşturulamadı: " + e.getMessage(),
                                                    Toast.LENGTH_LONG).show()
                                    );
                                }

                                @Override public void onResponse(@NonNull okhttp3.Call call, @NonNull okhttp3.Response response)
                                        throws java.io.IOException {
                                    final String resp = response.body() != null ? response.body().string() : "{}";
                                    if (!response.isSuccessful()) {
                                        activity.runOnUiThread(() ->
                                                Toast.makeText(activity, "HTTP " + response.code(), Toast.LENGTH_LONG).show()
                                        );
                                        return;
                                    }
                                    try {
                                        org.json.JSONObject json = new org.json.JSONObject(resp);
                                        final boolean ok = json.optBoolean("ok", json.optBoolean("success", false));
                                        final String markerId = json.optString("markerId",
                                                json.optString("id", null));
                                        activity.runOnUiThread(() -> {
                                            if (ok && markerId != null) {
                                                marker.setTag(markerId);
                                                Toast.makeText(activity, "Kaydedildi ✓", Toast.LENGTH_SHORT).show();
                                            } else {
                                                Toast.makeText(activity, "Sunucu yanıtı beklenmedik", Toast.LENGTH_LONG).show();
                                            }
                                        });
                                    } catch (org.json.JSONException ex) {
                                        activity.runOnUiThread(() ->
                                                Toast.makeText(activity, "Yanıt parse hatası", Toast.LENGTH_LONG).show()
                                        );
                                    }
                                }
                            }
                    );
                })
                .setCancelable(false)
                .show();
    }


    public Harita(FragmentActivity activity) {
        this.activity = activity;

        // 1. Harita fragment’i başlat
        SupportMapFragment mapFragment = (SupportMapFragment)
                activity.getSupportFragmentManager().findFragmentById(R.id.map);
        if (mapFragment != null) {
            mapFragment.getMapAsync(this);
        }

        // 2. Konum istemcisi
        locationClient = LocationServices.getFusedLocationProviderClient(activity);

        // 3. İzin launcher’ı
        permissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    Boolean fine = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                    Boolean coarse = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);
                    if ((fine != null && fine) || (coarse != null && coarse)) {
                        getUserLocationAndLoadInitial();
                    } else {
                        Toast.makeText(activity, "Konum izni verilmedi.", Toast.LENGTH_LONG).show();
                    }
                }
        );

        // 4. Spinner’ı bul ve adapter’ı ayarla
        initSpinner();
    }
    private boolean hasLocalGeoJson(String fileName) {
        try {
            // assets/maps klasöründeki dosyaları listeliyoruz
            String[] list = activity.getAssets().list("maps");
            if (list != null) {
                for (String asset : list) {
                    if (asset.equals(fileName)) return true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Asset listelenirken hata: " + e.getMessage());
        }
        return false;
    }
    private void loadLocalOrFallback(String iso3, String admLevel) {
        if ("OSM".equals(admLevel)) {
            loadFromOSM(currentCountryCode2);
            return;
        }
        String fileName = String.format("geoBoundaries-%s-%s.geojson", iso3, admLevel);
        if (hasLocalGeoJson(fileName)) {
            // Yerelde varsa yükle ve çiz
            try (InputStream is = activity.getAssets().open("maps/" + fileName)) {
                byte[] buf = new byte[is.available()];
                is.read(buf);
                String json = new String(buf, StandardCharsets.UTF_8);
                activity.runOnUiThread(() -> {
                    // Spinner’ı da güncelleyelim
                    int idx = levelOptions.indexOf(admLevel);
                    if (idx >= 0) spinnerLevels.setSelection(idx);
                    GeoSon.filterAndDraw(activity, mMap, json, centerPoint, radiusMeters);
                });
            } catch (IOException e) {
                Log.e(TAG, "Yerel GeoJSON okunamadı, fallback: " + e.getMessage());
                //loadFromGitHub(iso3, admLevel);
            }
        } else {
            // Yerelde yoksa GitHub’a sor
            //loadFromGitHub(iso3, admLevel);
        }
    }
    private void initSpinner() {
        spinnerLevels = activity.findViewById(R.id.spinner_level_1);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                levelOptions
        );
        spinnerLevels.setAdapter(adapter);

        spinnerLevels.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String selected = levelOptions.get(position);

                // Sadece harita ve ülke kodu hazırsa yükleme yap
                if (mapReady && countryResolved) {
                    if (!"OSM".equals(selected)) {
                        loadLocalOrFallback(currentCountryCode3, selected);
                        //loadFromGitHub(currentCountryCode3, selected);
                    } else {
                        //loadFromOSM(currentCountryCode2);
                    }
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
    }
    /**
     * Spinner’ı (dropdown) kullanıcı arayüzünden bulur, seçenekleri atar ve seçim olayını dinler.
     */
    public boolean isReady() { return ready && countryResolved; }
    private void getUserLocationAndLoadInitial() {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                double lat = location.getLatitude();
                double lon = location.getLongitude();

                centerPoint = new LatLng(lat, lon);
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(centerPoint, 12f));

                Geocoder geocoder = new Geocoder(activity, Locale.getDefault());
                try {
                    List<Address> addresses = geocoder.getFromLocation(lat, lon, 1);
                    if (!addresses.isEmpty()) {
                        currentCountryCode2 = addresses.get(0).getCountryCode(); // ISO2 (örn: "TR")
                        Locale locale = new Locale("", currentCountryCode2);
                        currentCountryCode3 = locale.getISO3Country(); // ISO3 (örn: "TUR")
                        countryResolved = true;

                        Log.d(TAG, "Ülke ISO2: " + currentCountryCode2 + ", ISO3: " + currentCountryCode3);

                        // Spinner’daki o anki seçim (default “ADM5” vb.) alınır:
                        String defaultSelection = (String) spinnerLevels.getSelectedItem();
                        if (defaultSelection != null && countryResolved && mapReady) {
                            loadLocalOrFallback(currentCountryCode3, defaultSelection);
                        }
                        fetchMarkersNearby(/*type*/ null, /*radiusM*/ 2500, /*limit*/ 150);

                        /*
                        if (defaultSelection != null) {
                            if (!"OSM".equals(defaultSelection)) {
                                //loadFromGitHub(currentCountryCode3, defaultSelection);
                            } else {
                                //loadFromOSM(currentCountryCode2);
                            }
                        }
                        */
                    }
                } catch (IOException e) {
                    Toast.makeText(activity, "Geocoder hatası.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(activity, "Konum alınamadı.", Toast.LENGTH_SHORT).show();
            }
        });
    }
    /**
     * Kullanıcının konumunu alır, ülke kodlarını çözer ve spinner’daki seçime göre
     * GeoBoundaries → OSM yüklemesini tetikler.
     */
    private void loadFromGitHub(String iso3, String admLevel) {
        String fileName = String.format("geoBoundaries-%s-%s.geojson", iso3, admLevel);
        String url = GITHUB_BASE + iso3 + "/" + admLevel + "/" + fileName;
        Log.d(TAG, "GitHub deneme URL: " + url);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                fallbackToNext(iso3, admLevel);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.code() == 404 || !response.isSuccessful() || response.body() == null) {
                    fallbackToNext(iso3, admLevel);
                } else {
                    String body = response.body().string().trim();
                    try {
                        new JSONObject(body);

                        // >>> (Aşağıdaki satırı ekleyin) Spinner’da bu admLevel’i seçili kıl:
                        activity.runOnUiThread(() -> {
                            int index = levelOptions.indexOf(admLevel);
                            if (index >= 0) {
                                spinnerLevels.setSelection(index);
                            }
                        });

                        // Geçerli JSON ise GeoSon ile filtreleyip çiz:
                        activity.runOnUiThread(() ->
                                GeoSon.filterAndDraw(activity, mMap, body, centerPoint, radiusMeters)
                        );

                    } catch (JSONException ex) {
                        fallbackToNext(iso3, admLevel);
                    }
                }
            }
        });
    }
    /**
     * GitHub raw üzerinden GeoBoundaries “admLevel” dosyasını çeker.
     * Eğer 404 veya ağ hatası/düzgün JSON dönmezse fallbackToNext ile bir sonraki adıma geçer.
     */
    private void fallbackToNext(String iso3, String currentAdm) {
        switch (currentAdm) {
            case "ADM5":
                loadFromGitHub(iso3, "ADM4");
                break;
            case "ADM4":
                loadFromGitHub(iso3, "ADM3");
                break;
            case "ADM3":
                loadFromGitHub(iso3, "ADM2");
                break;
            case "ADM2":
                loadFromGitHub(iso3, "ADM1");
                break;
            case "ADM1":
                loadFromGitHub(iso3, "ADM0");
                break;
            case "ADM0":
                loadFromOSM(currentCountryCode2);
                break;
            default:
                Toast.makeText(activity, "Sınır verisi bulunamadı.", Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * GeoBoundaries sıralaması: ADM5 → ADM4 → ADM3 → ADM2 → ADM1 → ADM0 → OSM
     */
    private void loadFromOSM(String countryIso2) {
        String url = "https://polygons.openstreetmap.fr/get_geojson.py?id="
                + countryIso2 + "&params=0";
        Log.d(TAG, "OSM deneme URL: " + url);

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder().url(url).build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                Log.e(TAG, "OSM yükleme hatası: " + e.getMessage());
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "OSM'den veri alınamadı.", Toast.LENGTH_SHORT).show()
                );
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String geoJsonStr = response.body().string().trim();
                    // OSM’den gelen veri JSON mu diye kontrol et
                    try {
                        new JSONObject(geoJsonStr);
                        activity.runOnUiThread(() ->
                                GeoSon.filterAndDraw(activity, mMap, geoJsonStr, centerPoint, radiusMeters)
                        );
                    } catch (JSONException e) {
                        Log.e(TAG, "OSM geçerli GeoJSON değil: " + e.getMessage());
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, "OSM verisi işlenemedi.", Toast.LENGTH_SHORT).show()
                        );
                    }
                } else {
                    Log.w(TAG, "OSM başarısız kod: " + response.code());
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, "OSM hatası: " + response.code(), Toast.LENGTH_SHORT).show()
                    );
                }
            }
        });
    }
    public LatLng screenPointToLatLng(Point point) {
        return mMap.getProjection().fromScreenLocation(point);
    }
    // JSON yükleme
    private JSONObject loadMarkersFromLocalJSON() {
        try {
            InputStream is = activity.openFileInput("markers.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            String json = new String(buffer, "UTF-8");
            return new JSONObject(json);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    // JSON Kaydetme
    private void saveJSONToFile(JSONObject json) {
        try {
            OutputStream os = activity.openFileOutput("markers.json", Context.MODE_PRIVATE);
            os.write(json.toString().getBytes("UTF-8"));
            os.close();
        } catch (Exception e) {
            Log.e(TAG, "saveJSONToFile error: " + e.getMessage());
        }
    }
    public void placeDraggableMarker(LatLng location) {
        if (draggableMarker != null) {
            draggableMarker.remove();
        }

        draggableMarker = mMap.addMarker(new MarkerOptions()
                .position(location)
                .title("Yeni Konum")
                .draggable(true)
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_CYAN))
        );

        mMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
            @Override
            public void onMarkerDragStart(Marker marker) {
                mMap.getUiSettings().setScrollGesturesEnabled(false);
            }

            @Override
            public void onMarkerDragEnd(Marker marker) {
                mMap.getUiSettings().setScrollGesturesEnabled(true);
            }

            @Override
            public void onMarkerDrag(Marker marker) {
            }
        });
    }
    // Yeni metodlar ekle
    public boolean isMarkerActive() {
        return draggableMarker != null;
    }

    public void cancelMarkerPlacement() {
        if (draggableMarker != null) {
            draggableMarker.remove();
            draggableMarker = null;
        }
    }

    private BitmapDescriptor getCustomIcon(String type) {
        if (iconCache.containsKey(type)) {
            return iconCache.get(type);
        }

        int fgRes;
        switch (type) {
            case "Besleme": fgRes = R.drawable.icon_besleme; break;
            case "Yuva":    fgRes = R.drawable.icon_yuva;    break;
            case "Barınak":  fgRes = R.drawable.icon_barinak; break;
            default: {
                BitmapDescriptor def = BitmapDescriptorFactory.defaultMarker();
                iconCache.put(type, def);
                return def;
            }
        }

        BitmapDescriptor bd = createCompositeDescriptor(
                R.drawable.ic_map_marker,  // VectorAsset olarak eklediğin default pin
                fgRes
        );
        iconCache.put(type, bd);
        return bd;
    }

    private BitmapDescriptor createCompositeDescriptor(@DrawableRes int bgRes,
                                                       @DrawableRes int fgRes) {
        float d = activity.getResources().getDisplayMetrics().density;

        // DP → PX
        int markerWidthPx  = (int)(MARKER_WIDTH_DP   * d + .5f);
        int iconPx         = (int)(ICON_DP           * d + .5f);
        int offsetYPx      = (int)(ICON_OFFSET_Y_DP  * d + .5f);
        int offsetXPx      = (int)(ICON_OFFSET_X_DP  * d + .5f);

        // Arka planı orijinal oranında boyutlandır
        Drawable bg = ContextCompat.getDrawable(activity, bgRes);
        int iw = bg.getIntrinsicWidth(), ih = bg.getIntrinsicHeight();
        float aspect = (float) ih / iw;
        int markerHeightPx = (int)(markerWidthPx * aspect + .5f);
        bg.setBounds(0, 0, markerWidthPx, markerHeightPx);

        // Oluşturulacak bitmap & canvas
        Bitmap bmp = Bitmap.createBitmap(markerWidthPx, markerHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // 1) Pin arka plan
        bg.draw(canvas);

        // 2) İç ikon — önceden ortalanan koordinata ek olarak X ve Y ofseti uygula
        Drawable fg = ContextCompat.getDrawable(activity, fgRes);
        int left = (markerWidthPx - iconPx) / 2 + offsetXPx;
        int top  = (markerHeightPx - iconPx) / 2 - offsetYPx;
        fg.setBounds(left, top, left + iconPx, top + iconPx);
        fg.draw(canvas);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }
    private void saveMarker(Marker marker, String type) {
        JSONObject json = loadMarkersFromLocalJSON(); // mevcut JSON

        JSONArray markersArray = json.optJSONArray("markers");
        JSONObject newMarker = null;
        try {
            if (markersArray == null) {
                markersArray = new JSONArray();
                json.put("markers", markersArray);
            }

            newMarker = new JSONObject();
            newMarker.put("type", type);
            newMarker.put("lat", marker.getPosition().latitude);
            newMarker.put("lng", marker.getPosition().longitude);
            newMarker.put("title", marker.getTitle());
        } catch (JSONException e) {
            Log.e(TAG, "Marker kaydı sırasında hata: " + e.getMessage());
            Toast.makeText(activity, "Marker kaydedilemedi.", Toast.LENGTH_SHORT).show();
            return;
        }

        markersArray.put(newMarker);

        saveJSONToFile(json);
    }

    public void initGesture(Context ctx) {
        placementDetector = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }

            @Override public void onLongPress(MotionEvent e) {
                if (!isReady()) return;                                  // hazır kontrolü
                if (!isPlacing && isPlacementMode()) {
                    Point p = new Point((int) e.getX(), (int) e.getY());
                    LatLng loc = screenPointToLatLng(p);
                    placeDraggableMarker(loc);                           // mevcut fonk. :contentReference[oaicite:0]{index=0}
                    isPlacing = true;
                }
            }

            @Override public boolean onDoubleTap(MotionEvent e) {
                if (isPlacing && isMarkerActive()) {                     // mevcut fonk. :contentReference[oaicite:1]{index=1}
                    confirmMarkerLocation();                             // mevcut fonk. :contentReference[oaicite:2]{index=2}
                    isPlacing = false;
                    return true;
                }
                return false;
            }

            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isPlacing && isMarkerActive()) {
                    cancelMarkerPlacement();                             // mevcut fonk. :contentReference[oaicite:3]{index=3}
                    isPlacing = false;
                    return true;
                }
                return false;
            }
        });
    }

    private boolean isPlacementMode() {
        return mode == MapMode.FEEDING || mode == MapMode.NEST || mode == MapMode.SHELTER || mode == MapMode.TASK;
    }

    public void setMode(MapMode newMode) {
        mode = newMode;
        if (mode == MapMode.DEFAULT && isMarkerActive()) {
            cancelMarkerPlacement();
        }
        isPlacing = false;
    }

    /** Overlay dokunuşlarını tek noktadan yönet */
    public boolean handleOverlayTouch(MotionEvent e) {
        if (!isPlacementMode()) return false;          // harita/marker tıklamaları serbest
        placementDetector.onTouchEvent(e);             // yerleştirme jestleri devrede
        return true;                                   // olayı tüket → harita sürüklenmesin
    }
    public void fetchMarkersNearbyOld(@androidx.annotation.Nullable String type, int radiusM, int limit) {
        if (!mapReady || centerPoint == null) {
            Toast.makeText(activity, "Konum hazır değil", Toast.LENGTH_SHORT).show();
            return;
        }
        HttpUrl.Builder ub = HttpUrl.parse(CF_BASE + "/markersNearby").newBuilder()
                .addQueryParameter("lat", String.valueOf(centerPoint.latitude))
                .addQueryParameter("lng", String.valueOf(centerPoint.longitude))
                .addQueryParameter("radiusM", String.valueOf(Math.max(300, radiusM)))
                .addQueryParameter("limit", String.valueOf(Math.min(300, Math.max(1, limit))));
        if (type != null) ub.addQueryParameter("type", type);

        Helpers.authorizedGetJson(activity, ub.build().toString(), /*deviceId*/ null, /*AppCheck*/ true,
                new okhttp3.Callback() {
                    @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, "CF hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                    @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                        String body = response.body()!=null? response.body().string() : "{}";
                        if (!response.isSuccessful()) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, "HTTP " + response.code(), Toast.LENGTH_LONG).show());
                            return;
                        }
                        try {
                            JSONObject json = new JSONObject(body);
                            org.json.JSONArray arr = json.optJSONArray("markers");
                            if (arr == null) arr = new org.json.JSONArray();

                            activity.runOnUiThread(() -> {
                                // Eski render’ları temizle
                                for (com.google.android.gms.maps.model.Marker m : renderedMarkers) m.remove();
                                renderedMarkers.clear();
                            });

                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject m = arr.getJSONObject(i);
                                final double lat = m.optDouble("lat");
                                final double lng = m.optDouble("lng");
                                final String mtype = m.optString("type", "Default");
                                final String id = m.optString("id", null);

                                activity.runOnUiThread(() -> {
                                    com.google.android.gms.maps.model.Marker mm =
                                            mMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                                                    .position(new com.google.android.gms.maps.model.LatLng(lat, lng))
                                                    .icon(getCustomIcon(mtype))
                                                    .title(mtype));
                                    if (mm != null) {
                                        if (id != null) mm.setTag(id);
                                        renderedMarkers.add(mm);
                                    }
                                });
                            }
                        } catch (org.json.JSONException ex) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, "Yanıt parse hatası", Toast.LENGTH_LONG).show());
                        }
                    }
                });
    }
    public void fetchMarkersNearby(@androidx.annotation.Nullable String type, int radiusM, int limit) {
        FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
        if (u == null) {
            Toast.makeText(activity, "Giriş gerekli", Toast.LENGTH_SHORT).show();
            // İstersen buradan Login'e yönlendirebilirsin:
            // activity.startActivity(new Intent(activity, com.kurmez.iyesi.Login.class));
            return;
        }

        if (!mapReady || centerPoint == null) {
            Toast.makeText(activity, "Konum hazır değil", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!mapReady || centerPoint == null) {
            Toast.makeText(activity, "Konum hazır değil", Toast.LENGTH_SHORT).show();
            return;
        }
        HttpUrl.Builder ub = HttpUrl.parse(CF_BASE + "/markersNearby").newBuilder()
                .addQueryParameter("lat", String.valueOf(centerPoint.latitude))
                .addQueryParameter("lng", String.valueOf(centerPoint.longitude))
                .addQueryParameter("radiusM", String.valueOf(Math.max(300, radiusM)))
                .addQueryParameter("limit", String.valueOf(Math.min(300, Math.max(1, limit))));
        if (type != null) ub.addQueryParameter("type", type);

        Helpers.authorizedGetJson(activity, ub.build().toString(), /*deviceId*/ null, /*AppCheck*/ true,
                new okhttp3.Callback() {
                    @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                        activity.runOnUiThread(() ->
                                Toast.makeText(activity, "CF hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                    @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                        String body = response.body()!=null? response.body().string() : "{}";
                        if (!response.isSuccessful()) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, "HTTP " + response.code(), Toast.LENGTH_LONG).show());
                            return;
                        }
                        try {
                            org.json.JSONObject json = new org.json.JSONObject(body);
                            org.json.JSONArray arr = json.optJSONArray("markers");
                            if (arr == null) arr = new org.json.JSONArray();

                            activity.runOnUiThread(() -> {
                                for (com.google.android.gms.maps.model.Marker m : renderedMarkers) m.remove();
                                renderedMarkers.clear();
                            });

                            for (int i = 0; i < arr.length(); i++) {
                                org.json.JSONObject m = arr.getJSONObject(i);
                                final double lat = m.optDouble("lat");
                                final double lng = m.optDouble("lng");
                                final String mtype = m.optString("type", "Default");
                                final String id = m.optString("id", null);

                                activity.runOnUiThread(() -> {
                                    com.google.android.gms.maps.model.Marker mm =
                                            mMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                                                    .position(new com.google.android.gms.maps.model.LatLng(lat, lng))
                                                    .icon(getCustomIcon(mtype))
                                                    .title(mtype));
                                    if (mm != null) {
                                        if (id != null) mm.setTag(id);
                                        renderedMarkers.add(mm);
                                    }
                                });
                            }
                        } catch (org.json.JSONException ex) {
                            activity.runOnUiThread(() ->
                                    Toast.makeText(activity, "Yanıt parse hatası", Toast.LENGTH_LONG).show());
                        }
                    }
                });
    }
    public void fetchMarkersByKey(String level, String key, @androidx.annotation.Nullable String type, int limit) {
        HttpUrl.Builder ub = HttpUrl.parse(CF_BASE + "/markersByKeys").newBuilder()
                .addQueryParameter("level", level)
                .addQueryParameter("key", key)
                .addQueryParameter("limit", String.valueOf(Math.min(500, Math.max(1, limit))));
        if (type != null) ub.addQueryParameter("type", type);

        Helpers.authorizedGetJson(activity, ub.build().toString(), null, true, new okhttp3.Callback() {
            @Override public void onFailure(okhttp3.Call call, java.io.IOException e) {
                activity.runOnUiThread(() ->
                        Toast.makeText(activity, "CF hata: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
            @Override public void onResponse(okhttp3.Call call, okhttp3.Response response) throws java.io.IOException {
                String body = response.body()!=null? response.body().string() : "{}";
                if (!response.isSuccessful()) {
                    activity.runOnUiThread(() ->
                            Toast.makeText(activity, "HTTP " + response.code(), Toast.LENGTH_LONG).show());
                    return;
                }
                try {
                    JSONObject json = new JSONObject(body);
                    org.json.JSONArray arr = json.optJSONArray("markers");
                    if (arr == null) arr = new org.json.JSONArray();

                    activity.runOnUiThread(() -> {
                        for (com.google.android.gms.maps.model.Marker m : renderedMarkers) m.remove();
                        renderedMarkers.clear();
                    });

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.getJSONObject(i);
                        final double lat = m.optDouble("lat");
                        final double lng = m.optDouble("lng");
                        final String mtype = m.optString("type", "Default");
                        final String id = m.optString("id", null);
                        activity.runOnUiThread(() -> {
                            com.google.android.gms.maps.model.Marker mm =
                                    mMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                                            .position(new com.google.android.gms.maps.model.LatLng(lat, lng))
                                            .icon(getCustomIcon(mtype))
                                            .title(mtype));
                            if (mm != null) {
                                if (id != null) mm.setTag(id);
                                renderedMarkers.add(mm);
                            }
                        });
                    }
                } catch (org.json.JSONException ignored) { }
            }
        });
    }

    @androidx.annotation.Nullable
    public LatLng getCurrentLocation() {
        return centerPoint; // getUserLocationAndLoadInitial() ile atanıyor
    }
}
