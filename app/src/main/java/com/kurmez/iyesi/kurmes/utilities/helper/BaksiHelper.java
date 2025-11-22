package com.kurmez.iyesi.kurmes.utilities.helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.util.Log;

import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.libraries.places.api.Places;
import com.google.android.libraries.places.api.model.Place;
import com.google.android.libraries.places.api.model.PlaceLikelihood;
import com.google.android.libraries.places.api.net.FindCurrentPlaceRequest;
import com.google.android.libraries.places.api.net.PlacesClient;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.kurmez.iyesi.kayra.Classes.Souls.Baksi;
import com.kurmez.iyesi.umay.sokak.Managers.NodeManager;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BaksiHelper {
    private static final String TAG = "BaksiHelper";
    private static final String PREFS_NAME = "VetCachePrefs";
    private static final String KEY_VET_JSON = "vet_json";
    private static final String KEY_LAST_UPDATE = "last_update";
    private static final long CACHE_EXPIRY_MS = 3600000; // 1 saat

    // Kademeli arama yarıçapları (metre cinsinden)
    private static final double[] SEARCH_RADIUS = {150, 1500, 15000};
    private static final String[] RADIUS_LABELS = {"150m", "1.5km", "15km"};

    private Context context;
    private NodeManager nodeManager;
    private BaksiHelperListener listener;
    private int currentRadiusIndex = 0;

    public interface BaksiHelperListener {
        void onVetsLoaded(int count, double radius);
        void onVetsLoadFailed(String error);
        void onVetMarkerAdded(Marker marker);
        void onSearchRadiusSuggested(double nextRadius, String radiusLabel);
    }

    public BaksiHelper(Context context, NodeManager nodeManager, BaksiHelperListener listener) {
        this.context = context;
        this.nodeManager = nodeManager;
        this.listener = listener;
    }

    /**
     * Place.Type listesini String listesine dönüştür
     */
    private List<String> convertPlaceTypesToStringList(List<Place.Type> placeTypes) {
        List<String> stringTypes = new ArrayList<>();
        if (placeTypes != null) {
            for (Place.Type type : placeTypes) {
                stringTypes.add(type.toString());
            }
        }
        return stringTypes;
    }

    /**
     * Kademeli veteriner arama başlat
     */
    public void startProgressiveVetSearch(LatLng userLocation) {
        Log.d(TAG, "Kademeli veteriner arama başlatılıyor");
        currentRadiusIndex = 0;
        searchVetsInRadius(userLocation, SEARCH_RADIUS[currentRadiusIndex]);
    }

    /**
     * Belirli yarıçapta veteriner arama
     */
    public void searchVetsInRadius(LatLng userLocation, double radiusMeters) {
        Log.d(TAG, radiusMeters + " metre çapında veteriner aranıyor: " + userLocation);

        // Önce cache kontrolü (sadece ilk arama için)
        if (radiusMeters == SEARCH_RADIUS[0]) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
            String cachedJson = prefs.getString(KEY_VET_JSON, null);
            long currentTime = System.currentTimeMillis();

            if (cachedJson != null && (currentTime - lastUpdate < CACHE_EXPIRY_MS)) {
                Log.d(TAG, "Cache'ten veterinerler yükleniyor");
                displayVetsFromCache(cachedJson, userLocation);
                return;
            }
        }

        // Google Places API'den ara
        searchVetsWithGooglePlaces(userLocation, radiusMeters);
    }

    /**
     * Bir sonraki arama yarıçapına geç
     */
    public void searchWithNextRadius(LatLng userLocation) {
        currentRadiusIndex++;
        if (currentRadiusIndex < SEARCH_RADIUS.length) {
            double nextRadius = SEARCH_RADIUS[currentRadiusIndex];
            String nextRadiusLabel = RADIUS_LABELS[currentRadiusIndex];

            Log.d(TAG, "Bir sonraki yarıçap: " + nextRadius + " metre");

            if (listener != null) {
                listener.onSearchRadiusSuggested(nextRadius, nextRadiusLabel);
            }

            searchVetsInRadius(userLocation, nextRadius);
        } else {
            Log.d(TAG, "Tüm arama yarıçapları denendi, veteriner bulunamadı");
            if (listener != null) {
                listener.onVetsLoaded(0, 0);
            }
        }
    }

    /**
     * Google Places API ile veteriner arama
     */
    private void searchVetsWithGooglePlaces(LatLng userLocation, double radiusMeters) {
        Log.d(TAG, "Google Places API ile " + radiusMeters + " metre çapında veteriner aranıyor");

        if (!Places.isInitialized()) {
            Log.e(TAG, "Places API başlatılmamış");
            if (listener != null) {
                listener.onVetsLoadFailed("Places API başlatılmamış");
            }
            // Firebase'den yükle
            fetchNearbyVetsFromFirebase(userLocation, radiusMeters);
            return;
        }

        PlacesClient placesClient = Places.createClient(context);

        List<Place.Field> fields = Arrays.asList(
                Place.Field.ID,
                Place.Field.NAME,
                Place.Field.LAT_LNG,
                Place.Field.ADDRESS,
                Place.Field.TYPES
        );

        FindCurrentPlaceRequest request = FindCurrentPlaceRequest.newInstance(fields);

        placesClient.findCurrentPlace(request)
                .addOnSuccessListener(response -> {
                    Log.d(TAG, "FindCurrentPlace başarılı, toplam sonuç: " + response.getPlaceLikelihoods().size());

                    List<Place> vetPlaces = new ArrayList<>();
                    int totalProcessed = 0;
                    int withinRadius = 0;

                    for (PlaceLikelihood likelihood : response.getPlaceLikelihoods()) {
                        Place place = likelihood.getPlace();
                        totalProcessed++;

                        // MESAFE KONTROLÜ
                        if (place.getLatLng() != null) {
                            double distance = calculateDistanceInMeters(userLocation, place.getLatLng());

                            if (distance <= radiusMeters) {
                                withinRadius++;

                                // TÜRLERİ STRING'E ÇEVİR VE LOGLA
                                List<String> stringTypes = convertPlaceTypesToStringList(place.getTypes());
                                Log.d(TAG, "Yer: " + place.getName() + " - Türler: " + stringTypes + " - Mesafe: " + distance + "m");

                                // VETERİNER KONTROLÜ
                                if (isRealVeterinary(place, stringTypes)) {
                                    Log.d(TAG, "Gerçek veteriner bulundu: " + place.getName() + " - " + distance + " metre");
                                    vetPlaces.add(place);
                                }
                            }
                        }
                    }

                    Log.d(TAG, "İşlenen: " + totalProcessed + ", yarıçap içinde: " + withinRadius + ", veteriner: " + vetPlaces.size());

                    if (vetPlaces.isEmpty()) {
                        Log.w(TAG, radiusMeters + " metre çapında veteriner bulunamadı");
                        if (listener != null) {
                            listener.onVetsLoaded(0, radiusMeters);
                        }
                    } else {
                        displayGooglePlacesVets(vetPlaces);
                        if (listener != null) {
                            listener.onVetsLoaded(vetPlaces.size(), radiusMeters);
                        }
                    }

                    // Firebase'den de yükle (başarılı olsa da olmasa da)
                    fetchNearbyVetsFromFirebase(userLocation, radiusMeters);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "FindCurrentPlace hatası: ", e);
                    if (listener != null) {
                        listener.onVetsLoadFailed("Google Places hatası: " + e.getMessage());
                    }
                    fetchNearbyVetsFromFirebase(userLocation, radiusMeters);
                });
    }

    /**
     * GERÇEK VETERİNER KONTROLÜ - GELİŞTİRİLMİŞ FİLTRE
     */
    /**
     * GERÇEK VETERİNER KONTROLÜ - GELİŞTİRİLMİŞ FİLTRE
     */
    private boolean isRealVeterinary(Place place, List<String> stringTypes) {
        if (place == null) return false;

        String placeName = (place.getName() != null ? place.getName() : "").toLowerCase();
        String placeAddress = (place.getAddress() != null ? place.getAddress() : "").toLowerCase();

        // VETERİNER ANAHTAR KELİMELERİ - GELİŞTİRİLMİŞ
        String[] vetKeywords = {
                "veteriner", "vet", "hayvan", "animal", "klinik", "clinic", "pet",
                "veterinerlik", "veterinary", "hayvan hastanesi", "animal hospital",
                "pet clinic", "veteriner hekim", "vet hekim"
        };

        // YANLIŞ POZİTİFLERİ ELEMEK İÇİN - GELİŞTİRİLMİŞ
        String[] excludeKeywords = {
                "hastane", "hospital", "su arıtma", "water", "medical", "medikal",
                "eczane", "pharmacy", "market", "restaurant", "cafe", "okul",
                "school", "üniversite", "university", "acıbadem", "laboratuvar", "lab",
                "park", "bahçe", "garden", "turist", "tourist", "attraction", "otel", "hotel",
                "dokuzoluk", // Özel olarak log'ta görünen yanlış pozitif
                "avm", "mall", "alışveriş", "shopping", "spor", "sport", "fitness"
        };

        // İsimde veteriner anahtar kelimeleri ara
        boolean hasVetKeyword = false;
        for (String keyword : vetKeywords) {
            if (placeName.contains(keyword) || placeAddress.contains(keyword)) {
                hasVetKeyword = true;
                break;
            }
        }

        if (!hasVetKeyword) {
            Log.d(TAG, "Veteriner anahtar kelimesi yok: " + placeName);
            return false;
        }

        // Yanlış pozitifleri ele - DAHA KATI FİLTRE
        for (String exclude : excludeKeywords) {
            if (placeName.contains(exclude) || placeAddress.contains(exclude)) {
                Log.d(TAG, "Yanlış pozitif eleme: " + placeName + " - " + exclude);
                return false;
            }
        }

        // Tür kontrolü (Google Places türleri) - ÖNCELİKLİ
        if (stringTypes != null && !stringTypes.isEmpty()) {
            boolean hasVetType = false;
            for (String type : stringTypes) {
                if (type.contains("veterinary_care") ||
                        type.contains("pet_store") ||
                        type.contains("animal_shelter") ||
                        type.contains("pet_groomer") ||
                        type.contains("pet_training") ||
                        type.contains("pet_sitter") ||
                        type.contains("veterinarian")) {
                    hasVetType = true;
                    break;
                }
            }

            // Eğer veteriner türü varsa, kesinlikle veterinerdir
            if (hasVetType) {
                Log.d(TAG, "Veteriner türü bulundu: " + stringTypes + " - " + placeName);
                return true;
            }

            // Veteriner olmayan türleri ele
            for (String type : stringTypes) {
                if (type.contains("tourist_attraction") ||
                        type.contains("park") ||
                        type.contains("point_of_interest") ||
                        type.contains("establishment") ||
                        type.contains("restaurant") ||
                        type.contains("cafe") ||
                        type.contains("shopping_mall")) {
                    Log.d(TAG, "Veteriner olmayan tür eleme: " + type + " - " + placeName);
                    return false;
                }
            }
        }

        // Eğer tür yoksa ama isimde veteriner geçiyorsa ve yanlış pozitif değilse kabul et
        Log.d(TAG, "Tür yok ama isimde veteriner geçiyor (kabul edildi): " + placeName);
        return true;
    }

    /**
     * FIREBASE'DEN YAKIN VETERİNERLERİ GETİR
     */
    private void fetchNearbyVetsFromFirebase(LatLng userLocation, double maxDistanceMeters) {
        Log.d(TAG, "Firebase'den " + maxDistanceMeters + " metre içindeki veterinerler yükleniyor");
        FirebaseFirestore db = FirebaseFirestore.getInstance();

        db.collection("VetNodes").get()
                .addOnSuccessListener(snapshot -> {
                    Log.d(TAG, "Firebase'den VetNodes alındı, toplam: " + snapshot.size());
                    List<Baksi> nearbyVets = new ArrayList<>();

                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        try {
                            Baksi baksi = doc.toObject(Baksi.class);
                            if (baksi != null && baksi.getLocation() != null) {
                                Double lat = baksi.getLocation().getLat();
                                Double lng = baksi.getLocation().getLng();

                                if (lat != null && lng != null) {
                                    LatLng vetPos = new LatLng(lat, lng);
                                    double distance = calculateDistanceInMeters(userLocation, vetPos);

                                    if (distance <= maxDistanceMeters) {
                                        nearbyVets.add(baksi);
                                        Log.d(TAG, "Yakın veteriner: " + baksi.getClinicName() + " - " + distance + " metre");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Vet parse hatası: " + doc.getId(), e);
                        }
                    }

                    Log.d(TAG, maxDistanceMeters + " metre içindeki veteriner sayısı: " + nearbyVets.size());

                    if (!nearbyVets.isEmpty()) {
                        displayVetsOnMap(nearbyVets);
                        cacheVets(nearbyVets);

                        if (listener != null) {
                            listener.onVetsLoaded(nearbyVets.size(), maxDistanceMeters);
                        }
                    } else {
                        Log.w(TAG, "Firebase'de " + maxDistanceMeters + " metre içinde veteriner bulunamadı");
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firebase hatası: ", e);
                    // Firebase hatasını sadece logla, kullanıcıya gösterme (Google Places öncelikli)
                    if (e.getMessage().contains("PERMISSION_DENIED")) {
                        Log.w(TAG, "Firebase erişim izni yok. Google Places sonuçları kullanılıyor.");
                    }
                });
    }

    /**
     * GOOGLE PLACES VETERİNERLERİNİ GÖSTER
     */
    private void displayGooglePlacesVets(List<Place> places) {
        Log.d(TAG, "Google Places veterinerleri haritada gösteriliyor: " + places.size());

        if (nodeManager == null) {
            Log.e(TAG, "NodeManager null!");
            return;
        }

        int addedCount = 0;

        for (Place place : places) {
            if (place.getLatLng() == null) continue;

            LatLng pos = place.getLatLng();
            String title = place.getName() != null ? place.getName() : "Veteriner";
            String snippet = place.getAddress() != null ? place.getAddress() : "";

            MarkerOptions options = new MarkerOptions()
                    .position(pos)
                    .title(title)
                    .snippet(snippet)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN));

            String markerId = "google_vet_" + (place.getId() != null ? place.getId() : System.nanoTime());
            Marker marker = nodeManager.addMarker(options, "Baksi", markerId);

            if (marker != null) {
                addedCount++;
                Log.d(TAG, "Google Places veteriner eklendi: " + title);

                if (listener != null) {
                    listener.onVetMarkerAdded(marker);
                }
            }
        }

        Log.d(TAG, "Google Places'tan eklenen veteriner: " + addedCount);
    }

    /**
     * FIREBASE VETERİNERLERİNİ HARİTADA GÖSTER
     */
    private void displayVetsOnMap(List<Baksi> vets) {
        Log.d(TAG, "Firebase veterinerleri haritada gösteriliyor: " + vets.size());

        if (nodeManager == null) {
            Log.e(TAG, "NodeManager null!");
            return;
        }

        int addedCount = 0;
        for (Baksi vet : vets) {
            if (vet.getLocation() == null) {
                Log.w(TAG, "Veteriner location null: " + vet.getClinicName());
                continue;
            }

            if (vet.getLocation().getLat() == null || vet.getLocation().getLng() == null) {
                Log.w(TAG, "Veteriner lat/lng null: " + vet.getClinicName());
                continue;
            }

            LatLng pos = new LatLng(vet.getLocation().getLat(), vet.getLocation().getLng());
            String title = vet.getClinicName() != null ? vet.getClinicName() : "Veteriner";
            String snippet = vet.getClinicAddress() != null ? vet.getClinicAddress() : "";

            MarkerOptions options = new MarkerOptions()
                    .position(pos)
                    .title(title)
                    .snippet(snippet)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_BLUE)); // Firebase marker'ları mavi

            String markerId = vet.getUid() != null ? vet.getUid() : "vet_" + System.nanoTime();
            Marker marker = nodeManager.addMarker(options, "Baksi", markerId);

            if (marker != null) {
                addedCount++;
                Log.d(TAG, "Firebase veteriner eklendi: " + markerId);

                if (listener != null) {
                    listener.onVetMarkerAdded(marker);
                }
            } else {
                Log.e(TAG, "Marker eklenemedi: " + markerId);
            }
        }

        Log.d(TAG, "Firebase'ten eklenen veteriner: " + addedCount);
    }

    /**
     * CACHE'DEN VETERİNER YÜKLE
     */
    private void displayVetsFromCache(String json, LatLng userLocation) {
        try {
            Log.d(TAG, "JSON'dan veterinerler yükleniyor");
            Gson gson = new Gson();
            Type listType = new TypeToken<List<Baksi>>(){}.getType();
            List<Baksi> vets = gson.fromJson(json, listType);
            Log.d(TAG, "JSON'dan yüklenen veteriner sayısı: " + (vets != null ? vets.size() : 0));

            if (vets != null && !vets.isEmpty()) {
                displayVetsOnMap(vets);
                if (listener != null) {
                    listener.onVetsLoaded(vets.size(), SEARCH_RADIUS[0]);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "JSON parse hatası", e);
        }

        // Cache'den yüklense bile güncel veri için Google Places'tan ara
        searchVetsWithGooglePlaces(userLocation, SEARCH_RADIUS[0]);
    }

    /**
     * VETERİNERLERİ CACHE'LE
     */
    private void cacheVets(List<Baksi> vets) {
        try {
            Log.d(TAG, "Veterinerler cache'leniyor: " + vets.size() + " adet");
            Gson gson = new Gson();
            String json = gson.toJson(vets);
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_VET_JSON, json)
                    .putLong(KEY_LAST_UPDATE, System.currentTimeMillis())
                    .apply();
            Log.d(TAG, "Veterinerler başarıyla cache'lendi");
        } catch (Exception e) {
            Log.e(TAG, "Cache yazma hatası", e);
        }
    }

    /**
     * METRE CİNSİNDEN MESAFE HESAPLAMA
     */
    private double calculateDistanceInMeters(LatLng loc1, LatLng loc2) {
        Location l1 = new Location("");
        l1.setLatitude(loc1.latitude);
        l1.setLongitude(loc1.longitude);
        Location l2 = new Location("");
        l2.setLatitude(loc2.latitude);
        l2.setLongitude(loc2.longitude);
        return l1.distanceTo(l2); // metre cinsinden
    }

    /**
     * CACHE'İ TEMİZLE
     */
    public void clearCache() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .remove(KEY_VET_JSON)
                .remove(KEY_LAST_UPDATE)
                .apply();
        Log.d(TAG, "Veteriner cache temizlendi");
    }

    /**
     * CACHE DURUMUNU KONTROL ET
     */
    public boolean isCacheValid() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long lastUpdate = prefs.getLong(KEY_LAST_UPDATE, 0);
        String cachedJson = prefs.getString(KEY_VET_JSON, null);
        long currentTime = System.currentTimeMillis();

        return cachedJson != null && (currentTime - lastUpdate < CACHE_EXPIRY_MS);
    }
}