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

                        Log.d(TAG, "Ülke ISO2: " + currentCountryCode2 +
                                ", ISO3: " + currentCountryCode3);

                        // Spinner’daki o anki seçim (default “ADM5” vb.) alınır:
                        String defaultSelection = (String) spinnerLevels.getSelectedItem();
                        if (defaultSelection != null && countryResolved && mapReady) {
                            loadLocalOrFallback(currentCountryCode3, defaultSelection);
                        }
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

    public void confirmMarkerLocation() {
        if (draggableMarker != null) {
            draggableMarker.setDraggable(false);
            openMarkerTypeSelectionDialog(draggableMarker);
            draggableMarker = null;
        }
    }
    private void openMarkerTypeSelectionDialog(Marker marker) {
        String[] types = {"Besleme", "Yuva", "Barınak" , "Default"};
        new AlertDialog.Builder(activity)
                .setTitle("Konum Türü Seçiniz")
                .setItems(types, (dialog, which) -> {
                    String selectedType = types[which];
                    marker.setTitle(selectedType + " Noktası");
                    marker.setIcon(getCustomIcon(selectedType));


                    // backend kaydı (şimdilik yerel veya JSON)
                    saveMarker(marker, selectedType);
                })
                .setCancelable(false)
                .show();
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
}
