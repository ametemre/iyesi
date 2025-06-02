package com.kurmez.iyesi.sokak;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.maps.android.data.geojson.GeoJsonFeature;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.google.maps.android.data.geojson.GeoJsonMultiPolygon;
import com.google.maps.android.data.geojson.GeoJsonPolygon;
import com.google.maps.android.data.geojson.GeoJsonPolygonStyle;
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
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.google.maps.android.data.geojson.GeoJsonPolygonStyle;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.utilities.Progress;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

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

    private static final String TAG = "Harita";

    // “main” branch altındaki releaseData klasörü (raw GitHub URL)
    private static final String GITHUB_BASE =
            "https://github.com/wmgeolab/geoBoundaries/raw/refs/heads/main/releaseData/gbOpen/";

    private final FragmentActivity activity;
    private GoogleMap mMap;
    private boolean ready = false;
    // Harita sınıfı içinde, field olarak:
    private GeoJsonLayer layerCountry;
    private GeoJsonLayer layerProvince;
    private GeoJsonLayer layerDistrict;

    private final FusedLocationProviderClient locationClient;
    private final ActivityResultLauncher<String[]> permissionLauncher;

    // Kullanıcının bulunduğu ülkenin ISO kodları
    private String currentCountryCode2; // Örn: “TR”
    private String currentCountryCode3; // Örn: “TUR”

    // Spinner ve doldurduğu seçenekler
    private Spinner spinnerLevels;
    private final List<String> levelOptions = Arrays.asList(
            "ADM5", "ADM4", "ADM3", "ADM2", "ADM1", "ADM0", "OSM"
    );

    // Harita ve konum alınıp hazır olduğunda spinner seçimlerini işleyebilmek için:
    private boolean mapReady = false;
    private boolean countryResolved = false;

    // Kullanıcının konumu ve yarıçap (metre)
    private LatLng centerPoint;
    private final double radiusMeters = 50000; // Örneğin 50 km

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

    /**
     * Spinner’ı (dropdown) kullanıcı arayüzünden bulur, seçenekleri atar ve seçim olayını dinler.
     */
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
                        loadFromGitHub(currentCountryCode3, selected);
                    } else {
                        loadFromOSM(currentCountryCode2);
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Boş duruma gerek yok
            }
        });
    }

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap;
        ready = true;
        mapReady = true;

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
    public boolean isReady() { return ready && countryResolved; }

    /**
     * Kullanıcının konumunu alır, ülke kodlarını çözer ve spinner’daki seçime göre
     * GeoBoundaries → OSM yüklemesini tetikler.
     */
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
                        if (defaultSelection != null) {
                            if (!"OSM".equals(defaultSelection)) {
                                loadFromGitHub(currentCountryCode3, defaultSelection);
                            } else {
                                loadFromOSM(currentCountryCode2);
                            }
                        }
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
     * GitHub raw üzerinden GeoBoundaries “admLevel” dosyasını çeker.
     * Eğer 404 veya ağ hatası/düzgün JSON dönmezse fallbackToNext ile bir sonraki adıma geçer.
     */
// Harita.java içinde, loadFromGitHub(...) metodu:

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
     * GeoBoundaries sıralaması: ADM5 → ADM4 → ADM3 → ADM2 → ADM1 → ADM0 → OSM
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
     * OSM fallback: Ülke ISO2 koduna göre polygon verisini alır.
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
// Harita.java içinde

    public void loadLayer(int levelIndex, String admLevel) {
        SokakActivity act = (SokakActivity) activity;
        act.runOnUiThread(() -> {
            ProgressBar pb = act.findViewById(R.id.progress_bar);
            pb.setVisibility(View.VISIBLE);
            pb.setProgress(0);
        });

        String url;
        if (!"OSM".equals(admLevel)) {
            String iso3 = currentCountryCode3;
            String file = String.format("geoBoundaries-%s-%s.geojson", iso3, admLevel);
            url = GITHUB_BASE + iso3 + "/" + admLevel + "/" + file;
        } else {
            url = "https://polygons.openstreetmap.fr/get_geojson.py?id="
                    + currentCountryCode2 + "&params=0";
        }
        fetchGeoJson(levelIndex, admLevel, url);
    }

    private void fetchGeoJson(int levelIndex, String admLevel, String url) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addNetworkInterceptor(chain -> {
                    okhttp3.Response original = chain.proceed(chain.request());
                    ResponseBody body = original.body();
                    if (body == null) return original;
                    Progress.ProgressResponseBody prb = new Progress.ProgressResponseBody(body, fraction -> {
                        SokakActivity act = (SokakActivity) activity;
                        act.runOnUiThread(() -> {
                            ProgressBar pb = act.findViewById(R.id.progress_bar);
                            pb.setProgress((int)(fraction * 100));
                        });
                    });
                    return original.newBuilder().body(prb).build();
                })
                .build();

        Request req = new Request.Builder().url(url).build();
        client.newCall(req).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                SokakActivity act = (SokakActivity) activity;
                act.runOnUiThread(() -> {
                    Toast.makeText(activity, admLevel + " yükleme başarısız", Toast.LENGTH_SHORT).show();
                    act.findViewById(R.id.progress_bar).setVisibility(View.GONE);
                });
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String json = response.body().string();

                // 1) JSON'u parse et
                final JSONObject obj;
                try {
                    obj = new JSONObject(json);
                } catch (org.json.JSONException ex) {
                    onFailure(call, new IOException("Geçersiz JSON", ex));
                    return;
                }

                SokakActivity act = (SokakActivity) activity;
                act.runOnUiThread(() -> {
                    GeoJsonLayer layer;
                    try {
                        layer = new GeoJsonLayer(mMap, obj);
                    } catch (Exception ex) {
                        Toast.makeText(activity, "Katman oluşturma hatası", Toast.LENGTH_SHORT).show();
                        ProgressBar pbErr = act.findViewById(R.id.progress_bar);
                        pbErr.setVisibility(View.GONE);
                        return;
                    }

                    // 2) O anki lokasyonunuzu alın (örneğin Harita içinde tutuluyorsa):
                    //    Harita sınıfınızda bir getter varsa:
                    //    LatLng currentLocation = harita.getCurrentLocation();
                    //    Eğer yoksa, kameranın ortasını da geçici olarak alabilirsiniz:
                    LatLng currentLocation = mMap.getCameraPosition().target;

                    // 3) GeoJSON içindeki her feature'ı kontrol et
                    for (GeoJsonFeature feature : layer.getFeatures()) {
                        boolean containsPoint = false;

                        // Eğer feature bir Polygon ise:
                        if (feature.getGeometry() instanceof GeoJsonPolygon) {
                            GeoJsonPolygon polygon = (GeoJsonPolygon) feature.getGeometry();
                            // Dış halkasını al:
                            List<LatLng> outerBoundary = polygon.getCoordinates().get(0);
                            if (com.google.maps.android.PolyUtil.containsLocation(currentLocation, outerBoundary, false)) {
                                containsPoint = true;
                            }
                        }
                        // Eğer feature bir MultiPolygon ise:
                        else if (feature.getGeometry() instanceof GeoJsonMultiPolygon) {
                            GeoJsonMultiPolygon multi = (GeoJsonMultiPolygon) feature.getGeometry();
                            for (GeoJsonPolygon polygon : multi.getPolygons()) {
                                List<LatLng> outer = polygon.getCoordinates().get(0);
                                if (com.google.maps.android.PolyUtil.containsLocation(currentLocation, outer, false)) {
                                    containsPoint = true;
                                    break;
                                }
                            }
                        }

                        // 4) Eğer içinde ise belirgin renkle, değilse neredeyse şeffaf yap
                        GeoJsonPolygonStyle style = new GeoJsonPolygonStyle();
                        if (containsPoint) {
                            // İçindeyseniz: mavi %20 alfa, kalın kenarlık
                            style.setFillColor(Color.argb(51, 0, 0, 255));
                            style.setStrokeColor(Color.BLUE);
                            style.setStrokeWidth(2f);
                        } else {
                            // İçinde değilse: mavi çok düşük alfa (ör. alfa=10), ince kenarlık
                            style.setFillColor(Color.argb(10, 0, 0, 255));
                            style.setStrokeColor(Color.argb(10, 0, 0, 255));
                            style.setStrokeWidth(1f);
                        }
                        feature.setPolygonStyle(style);
                    }

                    // 5) Filtrelenmiş katmanı haritaya ekle
                    layer.addLayerToMap();

                    // 6) Referansı sakla (toggle/clear için ileride kullanabilirsiniz)
                    if (levelIndex == 0) layerCountry = layer;
                    else if (levelIndex == 1) layerProvince = layer;
                    else layerDistrict = layer;

                    // 7) ProgressBar'ı %100 yap ve gizle
                    ProgressBar pb = act.findViewById(R.id.progress_bar);
                    pb.setProgress(100);
                    pb.setVisibility(View.GONE);
                });
            }

        });
    }

    /**
     * Haritaya tıklayınca besleme noktası eklemek için kullanılan metot.
     */
    public void enableBeslemeMode() {
        if (mMap != null) {
            mMap.setOnMapClickListener(latLng -> {
                mMap.addMarker(new com.google.android.gms.maps.model.MarkerOptions()
                        .position(latLng)
                        .title("Beslenme Noktası"));
                mMap.setOnMapClickListener(null);
            });
        }
    }
}
