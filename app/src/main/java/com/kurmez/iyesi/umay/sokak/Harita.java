package com.kurmez.iyesi.umay.sokak;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresPermission;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;
import android.util.Log;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.kurmez.iyesi.umay.sokak.Managers.HaritaManager;
import com.kurmez.iyesi.umay.sokak.Managers.LocationManager;
import com.kurmez.iyesi.umay.sokak.Managers.NodeManager;
import com.kurmez.iyesi.umay.sokak.Cache.NodeCache;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import com.kurmez.iyesi.kurmes.utilities.helper.CFHelper;
import com.kurmez.iyesi.kayra.Classes.Nodes.Node;
import com.kurmez.iyesi.kayra.Classes.Nodes.NodeType;
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeDetailsBottomSheet;
import com.kurmez.iyesi.R;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

/**
 * Harita - map wrapper / coordinator
 * - onMapReady içinde detaylı loglar eklendi
 * - focusOnMyLocation() eklendi (lastLocation + fallback getCurrentLocation)
 * - addDebugTestMarker() eklendi (hızlı test için)
 * - fetchNodesNearby() için yer tutucu + log
 *
 * NOT: HaritaManager/MarkerManager sınıflarının sağladığı methodlara göre
 * marker ekleme/temizleme çağrılarını MarkerManager üzerinden yapman gerekiyor.
 * Buradaki metotlar log ve çağrı noktası sağlar; veri kaynağını (Firestore vb.)
 * fetchNodesNearby içinde entegre et.
 */
public class Harita implements OnMapReadyCallback {
    private static final String TAG = "Harita";

    private HaritaManager haritaManager;
    private LocationManager locationManager;
    private NodeManager nodeManager;
    private FragmentActivity activity;
    private CFHelper cfHelper; // Cloud Functions helper
    private NodeCache nodeCache; // Node cache - marker detayları için

    private boolean hasCenteredOnce = false; // ilk lokasyonda kamerayı taşıma kontrolü
    
    // Role-based task visibility policy (SokakActivity tarafından set edilir)
    private volatile boolean canSeeTasks = false;        // "Görev"
    private volatile boolean canSeeUrgentTasks = false;  // "Acil"
    // Nodes (non-vet) visibility/fetch policy
    private volatile boolean canFetchNodes = true;       // fetchNodesNearby çağrıları çalışsın mı?
    private volatile boolean autoFetchNodesEnabled = true; // onCameraIdle otomatik fetch yapsın mı?
    
    // Country ve city bilgisi (markersNearby için gerekli)
    @Nullable
    private String currentCountry = null;
    @Nullable
    private String currentCity = null;

    // Listener interfaces
    public interface LockModeListener {
        void onLockModeChanged(boolean locked);
    }

    public interface NodeCreationListener {
        void onNodeCreated(String nodeId, String nodeType);
        void onNodeCreationFailed(String error);
    }

    /**
     * Haritaya marker eklendikten/yenilendikten sonra UI tarafında (örn. layer görünürlüğü) güncellemek için.
     * SokakActivity, toggle durumlarına göre marker görünürlüğünü burada yeniden uygulatabilir.
     */
    public interface MarkersUpdatedListener {
        void onMarkersUpdated();
    }

    // Map modes enum
    public enum MapMode {
        DEFAULT, FEEDING, NEST, SHELTER, TASK
    }

    private LockModeListener lockModeListener;
    private NodeCreationListener nodeCreationListener;
    private MarkersUpdatedListener markersUpdatedListener;
    private MapMode currentMode = MapMode.DEFAULT;
    private boolean isMarkerPlacementMode = false;
    private boolean isRepositionMode = false;
    private String repositionMarkerId;

    public Harita(FragmentActivity activity) {
        this.activity = activity;
        Log.d(TAG, "Harita ctor - başlatılıyor");
        this.haritaManager = new HaritaManager(activity);
        this.locationManager = new LocationManager(activity);
        // MarkerManager oluşturulacak onMapReady içinde (GoogleMap referansı gerekli)
        this.haritaManager.getMapAsync(this);
    }

    // Kamera hareket takibi için
    private LatLng lastFetchedCenter = null;
    private static final double MIN_FETCH_DISTANCE_M = 500.0; // 500m'den fazla hareket edilirse yeniden fetch
    private Handler fetchDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingFetch = null;

    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        Log.d(TAG, "onMapReady called. googleMap != null ? " + (googleMap != null));
        try {
            this.nodeManager = new NodeManager(googleMap, activity);
            Log.d(TAG, "MarkerManager oluşturuldu.");
        } catch (Exception e) {
            Log.e(TAG, "MarkerManager oluşturulurken hata: ", e);
        }

        // HaritaManager içindeki googleMap referansını doğrula/logla (varsa)
        try {
            GoogleMap gm = haritaManager.getGoogleMap();
            Log.d(TAG, "haritaManager.getGoogleMap() sonuc: " + (gm != null));
        } catch (Exception e) {
            Log.w(TAG, "haritaManager.getGoogleMap() çağrılırken hata (opsiyonel): " + e.getMessage());
        }

        // Marker click listener ekle - marker tıklandığında detay göster
        if (googleMap != null) {
            googleMap.setOnMarkerClickListener(marker -> {
                String markerId = (String) marker.getTag();
                if (markerId != null && nodeCache != null) {
                    // Cache'ten Node'u al
                    Node node = nodeCache.getNode(markerId);
                    if (node != null) {
                        // Node'u JSON'a çevir ve BottomSheet göster
                        openMarkerDetails(markerId, node);
                        return true; // Event consumed
                    } else {
                        Log.w(TAG, "onMarkerClick: Node cache'de bulunamadı - id=" + markerId);
                    }
                }
                return false; // Event not consumed, default behavior
            });
            Log.d(TAG, "onMapReady: Marker click listener eklendi");
        }

        // Kamera hareket listener'ı ekle - harita hareket ettiğinde Nodes yükle
        if (googleMap != null) {
            googleMap.setOnCameraIdleListener(() -> {
                LatLng center = googleMap.getCameraPosition().target;
                if (center != null) {
                    // Role policy: bazı roller (örn. AGAC/ANON) node verisine erişmemeli → otomatik fetch kapalı
                    if (!autoFetchNodesEnabled) {
                        return;
                    }
                    // Debounce: 500ms bekle, sonra fetch et
                    if (pendingFetch != null) {
                        fetchDebounceHandler.removeCallbacks(pendingFetch);
                    }
                    pendingFetch = () -> {
                        // Eğer yeterince hareket edildiyse veya ilk fetch ise
                        if (lastFetchedCenter == null || 
                            distanceBetween(lastFetchedCenter, center) > MIN_FETCH_DISTANCE_M) {
                            // Country ve city bilgisi varsa fetch et
                            if (currentCountry != null && currentCity != null) {
                                Log.d(TAG, "onCameraIdle: Yeni konum için Nodes yükleniyor: " + center);
                                fetchNodesNearby(center, 5000.0, 200, currentCountry, currentCity);
                                lastFetchedCenter = center;
                            } else {
                                Log.w(TAG, "onCameraIdle: Country/city bilgisi yok, Nodes yüklenemiyor");
                            }
                        }
                    };
                    fetchDebounceHandler.postDelayed(pendingFetch, 500); // 500ms debounce
                }
            });
            Log.d(TAG, "onMapReady: Kamera idle listener eklendi");
        }

        // Quick debug: test marker ekleme (opsiyonel, prod'da kapat)
        // addDebugTestMarker(); // Yorum satırı: prod'da kapat

        // Eğer izinler uygunsa otomatik olarak konuma odaklanma dene
        if (hasLocationPermission(activity)) {
            focusOnMyLocation();
        } else {
            Log.w(TAG, "onMapReady: konum izni yok, focusAtılamıyor.");
        }
    }

    /**
     * Node type'ına göre layer string'ini döndür
     * @param nodeType Node tipi (örn: "Besleme", "Yuva", "Barınak", "Baksi")
     * @return Layer string (örn: "FEEDING_AREAS", "NESTING_AREAS", "SHELTERS", "VETS_NEARBY")
     */
    @NonNull
    private static String normalizeTypeKey(@Nullable String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        // TR karakterleri normalize et (ı/İ vb.)
        s = s.replace("ı", "i")
                .replace("ğ", "g")
                .replace("ü", "u")
                .replace("ş", "s")
                .replace("ö", "o")
                .replace("ç", "c");
        // whitespace -> underscore (bazı yerlerde "Besleme Alanı" vb gelebilir)
        s = s.replaceAll("\\s+", "_");
        return s;
    }

    private enum TaskKind { NONE, TASK, URGENT }

    @NonNull
    private static String firstNonEmpty(@Nullable String... xs) {
        if (xs == null) return "";
        for (String x : xs) {
            if (x != null) {
                String t = x.trim();
                if (!t.isEmpty()) return t;
            }
        }
        return "";
    }

    @NonNull
    private TaskKind classifyTaskKind(@NonNull JSONObject item, @Nullable String type) {
        String typeKey = normalizeTypeKey(type);
        if (typeKey.equals("acil") || typeKey.equals("urgent") || typeKey.equals("critical")) return TaskKind.URGENT;
        if (typeKey.equals("gorev") || typeKey.equals("task") || typeKey.equals("tasks")) return TaskKind.TASK;

        // Founded / soul_inneed gibi kayıtlar: etiketi farklı alanlarda taşıyabiliriz
        String tagRaw = firstNonEmpty(
                item.optString("tag", null),
                item.optString("label", null),
                item.optString("taskTag", null),
                item.optString("priority", null),
                item.optString("health", null),
                item.optString("status", null)
        );
        String tagKey = normalizeTypeKey(tagRaw);
        if (tagKey.contains("acil") || tagKey.contains("urgent") || tagKey.contains("critical")) return TaskKind.URGENT;
        if (tagKey.contains("gorev") || tagKey.contains("task")) return TaskKind.TASK;

        String rk = firstNonEmpty(item.optString("requestKind", null), item.optString("node", null));
        if (normalizeTypeKey(rk).equals("soul_inneed")) {
            // Etiket belirtilmemişse default: görev
            return TaskKind.TASK;
        }

        return TaskKind.NONE;
    }

    private String getLayerForNodeType(String nodeType) {
        if (nodeType == null) return null;

        // Backend/seed/UI farklı yerlerde farklı type string'leri döndürebiliyor:
        // - "Besleme"/"feeding"
        // - "Yuva"/"nest"
        // - "Barınak"/"barinak"/"shelter"
        // - "Görev"/"gorev"/"task"
        // Bu yüzden normalize edip map'liyoruz.
        final String key = normalizeTypeKey(nodeType);

        // Vets
        if (key.equals("baksi") || key.equals("saglik")) return "VETS_NEARBY";

        // Manual toggles (Tengri için istenenler)
        if (key.equals("besleme") || key.equals("feeding")) return "FEEDING_AREAS";
        if (key.equals("yuva") || key.equals("nest") || key.equals("nests")) return "NESTING_AREAS";
        if (key.equals("barinak") || key.equals("barinaklar") || key.equals("shelter") || key.equals("shelters")) return "SHELTERS";
        if (key.equals("gorev") || key.equals("gorevler") || key.equals("task") || key.equals("tasks")) return "TASKS";
        if (key.equals("acil") || key.equals("urgent") || key.equals("critical")) return "TASKS";

        // Existing layers
        if (key.equals("emergency")) return "EMERGENCY_AREAS";
        if (key.equals("responsible")) return "RESPONSIBLE_AREAS";
        if (key.equals("responsiblenew")) return "RESPONSIBLE_NEWLY_ADDED";
        if (key.equals("overdue")) return "OVERDUE_CARE_AREAS";
        if (key.equals("all5km")) return "ALL_WITHIN_5KM";

        // Unknown type -> layer yok (toggle edilemez); logla ki backend type varyasyonu yakalayalım
        Log.w(TAG, "getLayerForNodeType: unknown nodeType='" + nodeType + "' (normalized='" + key + "')");
                return null;
    }
    
    /**
     * İki LatLng arasındaki mesafeyi metre cinsinden hesaplar (Haversine)
     */
    private double distanceBetween(LatLng a, LatLng b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double R = 6371000; // Earth radius in meters
        double dLat = Math.toRadians(b.latitude - a.latitude);
        double dLng = Math.toRadians(b.longitude - a.longitude);
        double lat1 = Math.toRadians(a.latitude);
        double lat2 = Math.toRadians(b.latitude);
        double a1 = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.sin(dLng / 2) * Math.sin(dLng / 2) *
                Math.cos(lat1) * Math.cos(lat2);
        double c = 2 * Math.atan2(Math.sqrt(a1), Math.sqrt(1 - a1));
        return R * c;
    }

    // Listener setters
    public void setLockModeListener(LockModeListener listener) {
        this.lockModeListener = listener;
    }

    public void setNodeCreationListener(NodeCreationListener listener) {
        this.nodeCreationListener = listener;
    }

    public void setMarkersUpdatedListener(@Nullable MarkersUpdatedListener listener) {
        this.markersUpdatedListener = listener;
    }

    private void notifyMarkersUpdated() {
        if (markersUpdatedListener == null) return;
        try {
            markersUpdatedListener.onMarkersUpdated();
        } catch (Throwable t) {
            Log.w(TAG, "notifyMarkersUpdated: listener error", t);
        }
    }

    /**
     * CFHelper setter - Cloud Functions çağrıları için gerekli
     */
    public void setCFHelper(CFHelper cfHelper) {
        this.cfHelper = cfHelper;
        Log.d(TAG, "CFHelper set edildi: " + (cfHelper != null));
    }

    /**
     * NodeCache setter - Marker detayları için gerekli
     */
    public void setNodeCache(NodeCache nodeCache) {
        this.nodeCache = nodeCache;
        Log.d(TAG, "NodeCache set edildi: " + (nodeCache != null));
    }

    /**
     * Country ve city setter - markersNearby endpoint'i için gerekli
     */
    public void setCountryAndCity(@Nullable String country, @Nullable String city) {
        this.currentCountry = country;
        this.currentCity = city;
        Log.d(TAG, "Country ve city set edildi: country=" + country + " city=" + city);
    }

    /**
     * Haritadaki task marker görünürlüğü için rol bazlı policy.
     * - Görev: canSeeTasks=true olan roller görebilir
     * - Acil:  canSeeUrgentTasks=true olan roller görebilir
     */
    public void setTaskAccessPolicy(boolean canSeeTasks, boolean canSeeUrgentTasks) {
        this.canSeeTasks = canSeeTasks;
        this.canSeeUrgentTasks = canSeeUrgentTasks;
        Log.d(TAG, "TaskAccessPolicy set: canSeeTasks=" + canSeeTasks + " canSeeUrgentTasks=" + canSeeUrgentTasks);
    }

    /**
     * Non-vet nodes erişim politikası.
     * - canFetchNodes=false: fetchNodesNearby() çağrılarını da hard-block eder
     * - autoFetchNodesEnabled=false: kamera hareketlerinde otomatik fetch yapılmaz
     */
    public void setNodesAccessPolicy(boolean canFetchNodes, boolean autoFetchNodesEnabled) {
        this.canFetchNodes = canFetchNodes;
        this.autoFetchNodesEnabled = autoFetchNodesEnabled;
        Log.d(TAG, "NodesAccessPolicy set: canFetchNodes=" + canFetchNodes + " autoFetch=" + autoFetchNodesEnabled);
    }

    // Mode management
    public void setMode(MapMode mode) {
        this.currentMode = mode;
        Log.d(TAG, "MapMode set: " + mode);
    }

    public MapMode getCurrentMode() {
        return currentMode;
    }

    // Marker placement mode methods
    public void startMarkerPlacementMode() {
        isMarkerPlacementMode = true;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(true);
        }
        Log.d(TAG, "MarkerPlacementMode başlatıldı");
    }

    public void stopMarkerPlacementMode() {
        isMarkerPlacementMode = false;
        isRepositionMode = false;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(false);
        }
        Log.d(TAG, "MarkerPlacementMode durduruldu");
    }

    public void startRepositionMode(String markerId) {
        isRepositionMode = true;
        repositionMarkerId = markerId;
        if (lockModeListener != null) {
            lockModeListener.onLockModeChanged(true);
        }
        Log.d(TAG, "Reposition mode başlatıldı. markerId=" + markerId);
    }

    // Touch handling
    public boolean handleOverlayTouch(MotionEvent event) {
        if (isMarkerPlacementMode || isRepositionMode) {
            // TODO: burada gerçek marker placement/reposition logic çalışacak.
            Log.d(TAG, "handleOverlayTouch: marker/place event yakalandı: " + event.getAction());
            // Döndürülen true, üstteki overlay'in dokunmayı tüketmesini sağlar.
            return true;
        }
        return false;
    }

    // Node type selection
    public void showNodeTypeSelectionDialog() {
        // Implement node type selection dialog (activity reference ile)
        Log.d(TAG, "showNodeTypeSelectionDialog çağrıldı (henüz implement yok)");
    }

    /**
     * fetchNodesNearby:
     * - ÖNCE cache'den okur (cache-first yaklaşımı)
     * - Cache'de yeterli veri varsa kullanır
     * - Cache'de yoksa veya yeterli değilse Cloud Functions /markersNearby endpoint'ini çağırır
     * - Response'u parse edip Node objelerine çevirir
     * - NodeManager üzerinden marker'ları haritaya ekler
     * - Veritabanından çekilen verileri cache'e kaydeder
     * 
     * @param center Harita merkez noktası
     * @param radius Yarıçap (metre)
     * @param limit Maksimum marker sayısı
     * @param country Ülke kodu (örn: "TR", "CY") - zorunlu
     * @param city Şehir adı (örn: "ADANA", "GIRNE") - zorunlu
     */
    public void fetchNodesNearby(LatLng center, double radius, int limit, String country, String city) {
        if (!canFetchNodes) {
            Log.d(TAG, "fetchNodesNearby: blocked by role policy (canFetchNodes=false)");
            return;
        }
        Log.d(TAG, "fetchNodesNearby çağrıldı. center=" + center + " radius=" + radius + " limit=" + limit + 
                " country=" + country + " city=" + city);

        // 1) Önce markerManager mevcut mu kontrol et
        if (nodeManager == null) {
            Log.w(TAG, "fetchNodesNearby: nodeManager null - marker eklenemiyor");
            return;
        }

        // 2) Country ve city kontrolü
        if (country == null || country.trim().isEmpty() || city == null || city.trim().isEmpty()) {
            Log.e(TAG, "fetchNodesNearby: country ve city zorunlu! country=" + country + " city=" + city);
            return;
        }

        // 3) CACHE-FIRST: Önce cache'den oku
        if (nodeCache != null) {
            List<Node> cachedNodes = nodeCache.getNodesNearby(center, radius);
            
            // Cache'de yeterli veri varsa (en az limit/2 kadar) kullan
            if (cachedNodes.size() >= Math.max(1, limit / 2)) {
                Log.d(TAG, "fetchNodesNearby: Cache'den " + cachedNodes.size() + " node bulundu, haritaya ekleniyor...");
                
                // UI thread'de marker'ları ekle
                activity.runOnUiThread(() -> {
                    try {
                        int addedCount = 0;
                        for (Node node : cachedNodes) {
                            if (addedCount >= limit) break;
                            
                            if (node.getLat() == null || node.getLng() == null) continue;
                            
                            // Marker ekle
                            LatLng nodePos = new LatLng(node.getLat(), node.getLng());
                            String nodeType = node.getType() != null ? node.getType() : "default";
                            String nodeId = node.getId() != null ? node.getId() : ("node_" + System.currentTimeMillis() + "_" + addedCount);

                            // Role-based task visibility (cache)
                            TaskKind tk = classifyTaskKind(new JSONObject(), nodeType);
                            if (tk == TaskKind.URGENT && !canSeeUrgentTasks) continue;
                            if (tk == TaskKind.TASK && !canSeeTasks) continue;

                            // Task ise tek tipte göster (toggle/layer için): "Acil" veya "Görev"
                            if (tk == TaskKind.URGENT) {
                                nodeType = "Acil";
                                try { node.setType(nodeType); } catch (Throwable ignored) { }
                            } else if (tk == TaskKind.TASK) {
                                nodeType = "Görev";
                                try { node.setType(nodeType); } catch (Throwable ignored) { }
                            }
                            
                            // Marker tipine göre icon al
                            BitmapDescriptor icon = nodeManager.getCustomIcon(nodeType);
                            
                            // Duplicate üretmemek için: varsa marker'ı güncelle, yoksa ekle
                            Marker marker = nodeManager.findMarkerById(nodeId);
                            if (marker != null) {
                                marker.setPosition(nodePos);
                                marker.setTitle(nodeType);
                                marker.setSnippet(node.getNote() != null ? node.getNote() : "");
                                marker.setIcon(icon);
                                nodeManager.registerMarker(nodeId, marker, nodeType);
                            } else {
                            MarkerOptions options = new MarkerOptions()
                                    .position(nodePos)
                                    .title(nodeType)
                                    .snippet(node.getNote() != null ? node.getNote() : "")
                                    .icon(icon);
                                marker = nodeManager.addMarker(options, nodeType, nodeId);
                            }
                                
                                // Marker'ı cache'e ekle (marker referansı ve layer bilgisi ile)
                                if (marker != null && nodeCache != null) {
                                    String layer = getLayerForNodeType(nodeType);
                                    if (layer == null) {
                                        Log.w(TAG, "fetchNodesNearby(cache): layer null for type='" + nodeType + "' id=" + nodeId);
                                    }
                                    nodeCache.putMarker(nodeId, marker, nodeType, layer);
                                }
                            
                            addedCount++;
                        }
                        
                        Log.d(TAG, "fetchNodesNearby: Cache'den " + addedCount + " marker başarıyla haritaya eklendi");
                        notifyMarkersUpdated();
                        return; // Cache'den yeterli veri bulundu, veritabanına gitme
                    } catch (Exception e) {
                        Log.e(TAG, "fetchNodesNearby: Cache marker ekleme hatası", e);
                        // Hata olursa veritabanından çekmeye devam et
                    }
                });
            } else {
                Log.d(TAG, "fetchNodesNearby: Cache'de yeterli veri yok (" + cachedNodes.size() + " < " + (limit / 2) + "), veritabanından çekiliyor...");
            }
        }

        // 4) CFHelper mevcut mu kontrol et (veritabanından çekmek için)
        if (cfHelper == null) {
            Log.w(TAG, "fetchNodesNearby: CFHelper null - Cloud Functions çağrısı yapılamıyor");
            return;
        }

        // 5) Cloud Functions endpoint'ini çağır (cache'de yeterli veri yoksa)
        Map<String, String> queryParams = new HashMap<>();
        queryParams.put("lat", String.valueOf(center.latitude));
        queryParams.put("lng", String.valueOf(center.longitude));
        queryParams.put("radiusM", String.valueOf((int)radius));
        queryParams.put("limit", String.valueOf(limit));
        queryParams.put("country", country.trim().toUpperCase()); // UmayAna.js normKey ile normalize ediyor ama biz de normalize edelim
        queryParams.put("city", city.trim().toUpperCase());

        Log.d(TAG, "fetchNodesNearby: Cloud Functions çağrısı yapılıyor - /markersNearby");

        cfHelper.endpointAsync("/markersNearby", queryParams, null, false, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                try {
                    Log.d(TAG, "fetchNodesNearby: Cloud Functions başarılı - response alındı");
                    
                    // Response format: { ok: true, markers: [...], count: N, country, city }
                    // UmayAna.js markersNearbyHandler: return res.json({ok: true, country, city, count, markers: hits})
                    boolean ok = resp.optBoolean("ok", false);
                    if (!ok) {
                        Log.w(TAG, "fetchNodesNearby: Response ok=false");
                        return;
                    }

                    // UmayAna.js'de response field'ı "markers" olarak dönüyor, "items" değil
                    JSONArray markers = resp.optJSONArray("markers");
                    if (markers == null || markers.length() == 0) {
                        Log.d(TAG, "fetchNodesNearby: Hiç marker bulunamadı (markers array boş veya null)");
                        // Debug: Response'un tamamını logla
                        Log.d(TAG, "fetchNodesNearby: Response keys: " + resp.keys());
                        return;
                    }

                    Log.d(TAG, "fetchNodesNearby: " + markers.length() + " marker bulundu, haritaya ekleniyor...");

                    // UI thread'de marker'ları ekle
                    activity.runOnUiThread(() -> {
                        try {
                            int addedCount = 0;
                            for (int i = 0; i < markers.length(); i++) {
                                JSONObject item = markers.getJSONObject(i);
                                
                                // Debug: İlk marker'ın içeriğini logla
                                if (i == 0) {
                                    Log.d(TAG, "fetchNodesNearby: İlk marker JSON: " + item.toString());
                                }
                                
                                // Node objesini parse et
                                Node node = parseNodeFromJson(item);
                                if (node == null) {
                                    Log.w(TAG, "fetchNodesNearby: Node parse edilemedi, atlandı");
                                    continue;
                                }
                                
                                if (node.getLat() == null || node.getLng() == null) {
                                    Log.w(TAG, "fetchNodesNearby: Geçersiz node atlandı (lat/lng yok) - id=" + node.getId() + 
                                            " lat=" + node.getLat() + " lng=" + node.getLng());
                                    continue;
                                }

                                // Marker ekle
                                LatLng nodePos = new LatLng(node.getLat(), node.getLng());
                                String nodeType = node.getType() != null ? node.getType() : "default";
                                String nodeId = node.getId() != null ? node.getId() : ("node_" + System.currentTimeMillis() + "_" + i);

                                // Role-based task visibility (CF response)
                                TaskKind tk = classifyTaskKind(item, nodeType);
                                if (tk == TaskKind.URGENT && !canSeeUrgentTasks) continue;
                                if (tk == TaskKind.TASK && !canSeeTasks) continue;

                                // Task ise tek tipte göster (toggle/layer için): "Acil" veya "Görev"
                                if (tk == TaskKind.URGENT) {
                                    nodeType = "Acil";
                                    try { node.setType(nodeType); } catch (Throwable ignored) { }
                                } else if (tk == TaskKind.TASK) {
                                    nodeType = "Görev";
                                    try { node.setType(nodeType); } catch (Throwable ignored) { }
                                }

                                // Marker tipine göre icon al (NodeManager'ın getCustomIcon metodunu kullan)
                                BitmapDescriptor icon = nodeManager.getCustomIcon(nodeType);
                                
                                // Duplicate üretmemek için: varsa marker'ı güncelle, yoksa ekle
                                Marker marker = nodeManager.findMarkerById(nodeId);
                                if (marker != null) {
                                    marker.setPosition(nodePos);
                                    marker.setTitle(nodeType);
                                    marker.setSnippet(node.getNote() != null ? node.getNote() : "");
                                    marker.setIcon(icon);
                                    nodeManager.registerMarker(nodeId, marker, nodeType);
                                } else {
                                MarkerOptions options = new MarkerOptions()
                                        .position(nodePos)
                                        .title(nodeType)
                                        .snippet(node.getNote() != null ? node.getNote() : "")
                                        .icon(icon); // Custom icon ekle
                                    marker = nodeManager.addMarker(options, nodeType, nodeId);
                                }
                                
                                // Node'u cache'e ekle (hem memory hem persistent storage)
                                // country ve city parametreleriyle kaydet (persistent storage için)
                                if (nodeCache != null) {
                                    nodeCache.putNode(nodeId, node, country.trim().toUpperCase(), city.trim().toUpperCase(), System.currentTimeMillis());
                                    
                                    // Marker'ı da cache'e ekle (layer bilgisi ile)
                                    if (marker != null) {
                                        String layer = getLayerForNodeType(nodeType);
                                        if (layer == null) {
                                            Log.w(TAG, "fetchNodesNearby(cf): layer null for type='" + nodeType + "' id=" + nodeId);
                                        }
                                        nodeCache.putMarker(nodeId, marker, nodeType, layer);
                                    }
                                }
                                
                                addedCount++;
                            }

                            Log.d(TAG, "fetchNodesNearby: " + addedCount + " marker başarıyla haritaya eklendi");
                            notifyMarkersUpdated();
                        } catch (JSONException e) {
                            Log.e(TAG, "fetchNodesNearby: JSON parse hatası", e);
                        } catch (Exception e) {
                            Log.e(TAG, "fetchNodesNearby: Marker ekleme hatası", e);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "fetchNodesNearby: Response işleme hatası", e);
                }
            }

            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "fetchNodesNearby: Cloud Functions hatası", error);
                // Hata durumunda kullanıcıya bilgi verilebilir (Toast vb.)
            }
        });
    }

    /**
     * JSON'dan Node objesine parse eder
     * Referans: MockData_V2.py'deki Node şeması ve UmayAna.js response formatı
     * UmayAna.js nearbyNodes: {id: d.id, dist, ...data} formatında döner
     */
    private Node parseNodeFromJson(JSONObject json) {
        try {
            Node node = new Node();
            
            // Temel alanlar
            String id = json.optString("id", null);
            if (id == null || id.trim().isEmpty()) {
                Log.w(TAG, "parseNodeFromJson: id yok, null döndürülüyor");
                return null;
            }
            node.setId(id);
            node.setType(json.optString("type", null));
            
            // Lat/Lng - UmayAna.js direkt lat/lng döndürüyor
            // optDouble NaN döndürebilir, kontrol et
            double lat = json.optDouble("lat", Double.NaN);
            double lng = json.optDouble("lng", Double.NaN);
            
            if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                node.setLat(lat);
                node.setLng(lng);
            } else {
                // Fallback: location objesi içinde olabilir
                if (json.has("location")) {
                    JSONObject location = json.optJSONObject("location");
                    if (location != null) {
                        lat = location.optDouble("lat", Double.NaN);
                        lng = location.optDouble("lng", Double.NaN);
                        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
                            node.setLat(lat);
                            node.setLng(lng);
                        }
                    }
                }
                
                // Hala lat/lng yoksa null döndür
                if (node.getLat() == null || node.getLng() == null) {
                    Log.w(TAG, "parseNodeFromJson: lat/lng bulunamadı - id=" + id);
                    return null;
                }
            }

            // Diğer alanlar
            node.setGeohash(json.optString("geohash", null));
            node.setSpecies(json.optString("species", null));
            node.setCategory(json.optString("category", null));
            node.setStatus(json.optString("status", null));
            node.setNote(json.optString("note", null));
            node.setAdminPath(json.optString("adminPath", null));
            
            // Souls count
            if (json.has("soulsCount")) {
                int soulsCount = json.optInt("soulsCount", 0);
                node.setSoulsCount(soulsCount);
            }

            // last* alanları - "kim" ve "ne zaman" bilgileri
            // lastFed* (Besleme)
            if (json.has("lastFedBy")) {
                node.setLastFedBy(json.optString("lastFedBy", null));
            }
            if (json.has("lastFedAt")) {
                // Firestore Timestamp number olarak gelebilir (ms)
                Object lastFedAtObj = json.opt("lastFedAt");
                if (lastFedAtObj instanceof Number) {
                    node.setLastFedAt(((Number) lastFedAtObj).longValue());
                } else if (lastFedAtObj instanceof Long) {
                    node.setLastFedAt((Long) lastFedAtObj);
                } else if (lastFedAtObj != null) {
                    try {
                        node.setLastFedAt(Long.parseLong(String.valueOf(lastFedAtObj)));
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "parseNodeFromJson: lastFedAt parse hatası: " + lastFedAtObj);
                    }
                }
            }
            if (json.has("lastFedAmount")) {
                Object lastFedAmountObj = json.opt("lastFedAmount");
                if (lastFedAmountObj instanceof Number) {
                    node.setLastFedAmount(((Number) lastFedAmountObj).doubleValue());
                }
            }
            
            // lastCleaned* (Temizlik)
            if (json.has("lastCleanedBy")) {
                node.setLastCleanedBy(json.optString("lastCleanedBy", null));
            }
            if (json.has("lastCleanedAt")) {
                Object lastCleanedAtObj = json.opt("lastCleanedAt");
                if (lastCleanedAtObj instanceof Number) {
                    node.setLastCleanedAt(((Number) lastCleanedAtObj).longValue());
                } else if (lastCleanedAtObj instanceof Long) {
                    node.setLastCleanedAt((Long) lastCleanedAtObj);
                } else if (lastCleanedAtObj != null) {
                    try {
                        node.setLastCleanedAt(Long.parseLong(String.valueOf(lastCleanedAtObj)));
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "parseNodeFromJson: lastCleanedAt parse hatası: " + lastCleanedAtObj);
                    }
                }
            }
            
            // lastMaintained* (Bakım)
            if (json.has("lastMaintainedBy")) {
                node.setLastMaintainedBy(json.optString("lastMaintainedBy", null));
            }
            if (json.has("lastMaintainedAt")) {
                Object lastMaintainedAtObj = json.opt("lastMaintainedAt");
                if (lastMaintainedAtObj instanceof Number) {
                    node.setLastMaintainedAt(((Number) lastMaintainedAtObj).longValue());
                } else if (lastMaintainedAtObj instanceof Long) {
                    node.setLastMaintainedAt((Long) lastMaintainedAtObj);
                } else if (lastMaintainedAtObj != null) {
                    try {
                        node.setLastMaintainedAt(Long.parseLong(String.valueOf(lastMaintainedAtObj)));
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "parseNodeFromJson: lastMaintainedAt parse hatası: " + lastMaintainedAtObj);
                    }
                }
            }
            
            // lastVisited* (Ziyaret)
            if (json.has("lastVisitedBy")) {
                node.setLastVisitedBy(json.optString("lastVisitedBy", null));
            }
            if (json.has("lastVisitedAt")) {
                Object lastVisitedAtObj = json.opt("lastVisitedAt");
                if (lastVisitedAtObj instanceof Number) {
                    node.setLastVisitedAt(((Number) lastVisitedAtObj).longValue());
                } else if (lastVisitedAtObj instanceof Long) {
                    node.setLastVisitedAt((Long) lastVisitedAtObj);
                } else if (lastVisitedAtObj != null) {
                    try {
                        node.setLastVisitedAt(Long.parseLong(String.valueOf(lastVisitedAtObj)));
                    } catch (NumberFormatException e) {
                        Log.w(TAG, "parseNodeFromJson: lastVisitedAt parse hatası: " + lastVisitedAtObj);
                    }
                }
            }

            // Keys nested object - Node.Keys parse et
            if (json.has("keys")) {
                try {
                    JSONObject keysJson = json.optJSONObject("keys");
                    if (keysJson != null) {
                        Node.Keys keys = new Node.Keys();
                        keys.setCountry(keysJson.optString("country", null));
                        keys.setCity(keysJson.optString("city", null));
                        keys.setProvince(keysJson.optString("province", null));
                        keys.setDistrict(keysJson.optString("district", null));
                        keys.setNeighbourhood(keysJson.optString("neighbourhood", null));
                        keys.setStreet(keysJson.optString("street", null));
                        node.setKeys(keys);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "parseNodeFromJson: Keys parse hatası", e);
                }
            }

            // Location nested object - Node.Location parse et
            if (json.has("location")) {
                try {
                    JSONObject locationJson = json.optJSONObject("location");
                    if (locationJson != null && !locationJson.has("lat")) {
                        // location objesi sadece adres bilgisi içeriyorsa (lat/lng değilse)
                        Node.Location location = new Node.Location();
                        location.setCountry(locationJson.optString("country", null));
                        location.setCity(locationJson.optString("city", null));
                        location.setProvince(locationJson.optString("province", null));
                        location.setDistrict(locationJson.optString("district", null));
                        location.setNeighbourhood(locationJson.optString("neighbourhood", null));
                        location.setStreet(locationJson.optString("street", null));
                        node.setLocation(location);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "parseNodeFromJson: Location parse hatası", e);
                }
            }

            // Attrs map - tür-özel alanlar
            if (json.has("attrs")) {
                try {
                    JSONObject attrsJson = json.optJSONObject("attrs");
                    if (attrsJson != null) {
                        Map<String, Object> attrs = new HashMap<>();
                        Iterator<String> keys = attrsJson.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            Object value = attrsJson.get(key);
                            attrs.put(key, value);
                        }
                        node.setAttrs(attrs);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "parseNodeFromJson: Attrs parse hatası", e);
                }
            }

            // Timestamps (opsiyonel - JSON'da number olarak gelebilir)
            // Firestore Timestamp formatı için özel parse gerekebilir

            return node;
        } catch (Exception e) {
            Log.e(TAG, "parseNodeFromJson: Parse hatası", e);
            return null;
        }
    }

    /**
     * Marker tıklandığında detay bottom sheet'i açar
     * Node'u JSON'a çevirip NodeDetailsBottomSheet'e gönderir
     */
    private void openMarkerDetails(String markerId, Node node) {
        try {
            // Node'u JSONObject'e çevir
            JSONObject nodeJson = nodeToJson(node);
            
            // NodeType'ı belirle
            NodeType nodeType = node.getTypeEnum();
            if (nodeType == null) {
                nodeType = NodeType.TASK; // Default
            }
            
            // NodeDetailsBottomSheet'i oluştur ve göster
            NodeDetailsBottomSheet bottomSheet = NodeDetailsBottomSheet.newInstance(markerId, nodeType);
            
            // Marker JSON'unu bundle'a ekle
            Bundle args = bottomSheet.getArguments();
            if (args != null) {
                args.putString(NodeDetailsBottomSheet.ARG_MARKER_JSON, nodeJson.toString());
                args.putString(NodeDetailsBottomSheet.ARG_SOULS_JSON, "[]"); // Şimdilik boş
            }
            
            // FragmentActivity üzerinden göster
            bottomSheet.show(activity.getSupportFragmentManager(), "NodeDetails");
            Log.d(TAG, "openMarkerDetails: BottomSheet açıldı - id=" + markerId);
        } catch (Exception e) {
            Log.e(TAG, "openMarkerDetails: Hata", e);
        }
    }

    /**
     * QR gibi "nodeId ile direkt aç" akışları için public helper.
     *
     * Öncelik:
     * 1) NodeCache (memory/persistent) içinden aç
     * 2) (TODO) CF /markerDetails ile node fetch edip cache'e yaz, sonra aç
     *
     * Not: CF tarafı bu repoda değil; endpoint eklendiğinde TODO kısmı tamamlanacak.
     */
    public void openNodeDetailsById(@Nullable String country,
                                   @Nullable String city,
                                   @NonNull String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) return;

        // 1) Cache
        try {
            if (nodeCache != null) {
                Node n = nodeCache.getNode(nodeId);
                if (n != null) {
                    openMarkerDetails(nodeId, n);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "openNodeDetailsById: cache read failed id=" + nodeId, t);
        }

        // 2) CF /markerDetails ile çek (country/city + markerId)
        if (cfHelper == null) {
            Log.w(TAG, "openNodeDetailsById: CFHelper null, cannot fetch markerDetails. id=" + nodeId);
            return;
        }
        if (country == null || country.trim().isEmpty() || city == null || city.trim().isEmpty()) {
            Log.w(TAG, "openNodeDetailsById: country/city missing, cannot fetch markerDetails. id=" + nodeId);
            return;
        }

        final String cKey = country.trim().toUpperCase(java.util.Locale.ROOT);
        final String cityKey = city.trim().toUpperCase(java.util.Locale.ROOT);

        Map<String, String> q = new HashMap<>();
        q.put("markerId", nodeId);
        q.put("country", cKey);
        q.put("city", cityKey);

        cfHelper.endpointAsync("/markerDetails", q, null, /*post=*/false, new CFHelper.EndpointCallback() {
            @Override
            public void onSuccess(JSONObject resp) {
                try {
                    if (!resp.optBoolean("ok", false)) {
                        Log.w(TAG, "openNodeDetailsById: markerDetails ok=false id=" + nodeId);
                        return;
                    }

                    Node fetched = parseNodeFromJson(resp);
                    if (fetched == null) {
                        Log.w(TAG, "openNodeDetailsById: markerDetails parseNodeFromJson failed id=" + nodeId);
                        return;
                    }

                    if (nodeCache != null) {
                        nodeCache.putNode(nodeId, fetched, cKey, cityKey, System.currentTimeMillis());
                    }

                    openMarkerDetails(nodeId, fetched);
                    focusOnMarker(nodeId);
                } catch (Exception e) {
                    Log.e(TAG, "openNodeDetailsById: markerDetails handling error id=" + nodeId, e);
                }
            }

            @Override
            public void onError(Throwable err) {
                Log.w(TAG, "openNodeDetailsById: markerDetails error id=" + nodeId + " err=" + err, err);
            }
        });
    }

    /**
     * Node objesini JSONObject'e çevirir
     * NodeDetailsBottomSheet'in beklediği formatta
     */
    private JSONObject nodeToJson(Node node) {
        try {
            JSONObject json = new JSONObject();
            
            // Temel alanlar
            if (node.getId() != null) json.put("id", node.getId());
            if (node.getType() != null) json.put("type", node.getType());
            if (node.getLat() != null) json.put("lat", node.getLat());
            if (node.getLng() != null) json.put("lng", node.getLng());
            if (node.getGeohash() != null) json.put("geohash", node.getGeohash());
            if (node.getSpecies() != null) json.put("species", node.getSpecies());
            if (node.getCategory() != null) json.put("category", node.getCategory());
            if (node.getStatus() != null) json.put("status", node.getStatus());
            if (node.getNote() != null) json.put("note", node.getNote());
            if (node.getAdminPath() != null) json.put("adminPath", node.getAdminPath());
            if (node.getSoulsCount() != null) json.put("soulsCount", node.getSoulsCount());
            
            // last* alanları
            if (node.getLastFedBy() != null) json.put("lastFedBy", node.getLastFedBy());
            if (node.getLastFedAt() != null) json.put("lastFedAt", node.getLastFedAt());
            if (node.getLastFedAmount() != null) json.put("lastFedAmount", node.getLastFedAmount());
            if (node.getLastCleanedBy() != null) json.put("lastCleanedBy", node.getLastCleanedBy());
            if (node.getLastCleanedAt() != null) json.put("lastCleanedAt", node.getLastCleanedAt());
            if (node.getLastMaintainedBy() != null) json.put("lastMaintainedBy", node.getLastMaintainedBy());
            if (node.getLastMaintainedAt() != null) json.put("lastMaintainedAt", node.getLastMaintainedAt());
            if (node.getLastVisitedBy() != null) json.put("lastVisitedBy", node.getLastVisitedBy());
            if (node.getLastVisitedAt() != null) json.put("lastVisitedAt", node.getLastVisitedAt());
            
            // Keys nested object
            if (node.getKeys() != null) {
                JSONObject keysJson = new JSONObject();
                Node.Keys keys = node.getKeys();
                if (keys.getCountry() != null) keysJson.put("country", keys.getCountry());
                if (keys.getCity() != null) keysJson.put("city", keys.getCity());
                if (keys.getProvince() != null) keysJson.put("province", keys.getProvince());
                if (keys.getDistrict() != null) keysJson.put("district", keys.getDistrict());
                if (keys.getNeighbourhood() != null) keysJson.put("neighbourhood", keys.getNeighbourhood());
                if (keys.getStreet() != null) keysJson.put("street", keys.getStreet());
                json.put("keys", keysJson);
            }
            
            // Location nested object
            if (node.getLocation() != null) {
                JSONObject locationJson = new JSONObject();
                Node.Location location = node.getLocation();
                if (location.getCountry() != null) locationJson.put("country", location.getCountry());
                if (location.getCountryCode() != null) locationJson.put("countryCode", location.getCountryCode());
                if (location.getCity() != null) locationJson.put("city", location.getCity());
                if (location.getCityCode() != null) locationJson.put("cityCode", location.getCityCode());
                if (location.getProvince() != null) locationJson.put("province", location.getProvince());
                if (location.getDistrict() != null) locationJson.put("district", location.getDistrict());
                if (location.getNeighbourhood() != null) locationJson.put("neighbourhood", location.getNeighbourhood());
                if (location.getStreet() != null) locationJson.put("street", location.getStreet());
                json.put("location", locationJson);
            }
            
            // Attrs map
            if (node.getAttrs() != null && !node.getAttrs().isEmpty()) {
                JSONObject attrsJson = new JSONObject();
                for (Map.Entry<String, Object> entry : node.getAttrs().entrySet()) {
                    attrsJson.put(entry.getKey(), entry.getValue());
                }
                json.put("attrs", attrsJson);
            }
            
            return json;
        } catch (JSONException e) {
            Log.e(TAG, "nodeToJson: JSON oluşturma hatası", e);
            return new JSONObject(); // Boş JSON döndür
        }
    }

    @RequiresPermission(allOf = {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION})
    public void setMyLocationIconEnabled(boolean enabled) {
        if (haritaManager.getGoogleMap() != null) {
            try {
                haritaManager.getGoogleMap().setMyLocationEnabled(enabled);
                Log.d(TAG, "MyLocation icon setMyLocationEnabled(" + enabled + ")");
            } catch (SecurityException se) {
                Log.e(TAG, "setMyLocationIconEnabled - izin hatası: " + se.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "setMyLocationIconEnabled - beklenmedik hata: ", e);
            }
        } else {
            Log.w(TAG, "setMyLocationIconEnabled: googleMap null");
        }
    }

    // Utility methods
    public boolean isReady() {
        boolean ready = haritaManager != null && haritaManager.isMapReady();
        Log.d(TAG, "isReady() -> " + ready);
        return ready;
    }

    // Konum izni kontrolü için yardımcı metod
    public static boolean hasLocationPermission(Context context) {
        boolean fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        return fine || coarse;
    }

    /**
     * focusOnMyLocation
     * - İzin varsa önce getLastLocation() dener, null ise getCurrentLocation() ile fallback yapar.
     * - googleMap != null ise animateCamera ile odaklama yapar.
     * - hasCenteredOnce sayesinde sadece ilk başarılı lokasyonda kamera taşınır (isteğe bağlı).
     */
    public void focusOnMyLocation() {
        Log.d(TAG, "focusOnMyLocation çağrıldı. hasCenteredOnce=" + hasCenteredOnce);
        if (hasCenteredOnce) {
            Log.d(TAG, "focusOnMyLocation: zaten bir kere odaklandı, tekrar etmiyor.");
            return;
        }

        if (!hasLocationPermission(activity)) {
            Log.w(TAG, "focusOnMyLocation: konum izni yok");
            return;
        }

        FusedLocationProviderClient fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(activity);
        try {
            fused.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            LatLng me = new LatLng(loc.getLatitude(), loc.getLongitude());
                            animateCameraTo(me, 15f);
                            hasCenteredOnce = true;
                            Log.d(TAG, "focusOnMyLocation: lastLocation ile odaklandı -> " + me);
                        } else {
                            Log.w(TAG, "focusOnMyLocation: lastLocation null, getCurrentLocation ile denenecek.");
                            // fallback
                            requestCurrentLocationForCenter(fused);
                        }
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "focusOnMyLocation: getLastLocation hata, fallback çalışıyor", e);
                        requestCurrentLocationForCenter(fused);
                    });
        } catch (SecurityException se) {
            Log.e(TAG, "focusOnMyLocation: SecurityException (izin eksik)", se);
        }
    }

    // Animasyon kapsayıcı
    public void animateCameraTo(LatLng latLng, float zoom) {
        activity.runOnUiThread(() -> {
            try {
                GoogleMap gm = haritaManager.getGoogleMap();
                if (gm != null) {
                    gm.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, zoom));
                    Log.d(TAG, "animateCameraTo çağrıldı: " + latLng + " zoom=" + zoom);
                } else {
                    Log.w(TAG, "animateCameraTo: googleMap null, kamera taşınamadı");
                }
            } catch (Exception e) {
                Log.e(TAG, "animateCameraTo sırasında hata: ", e);
            }
        });
    }

    /**
     * Haritada belirli bir marker'a odaklan (QR açılışları için).
     * Öncelik: NodeManager marker → NodeCache koordinat.
     */
    private void focusOnMarker(@NonNull String nodeId) {
        try {
            if (nodeManager != null) {
                Marker m = nodeManager.findMarkerById(nodeId);
                if (m != null) {
                    animateCameraTo(m.getPosition(), 17f);
                    return;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "focusOnMarker: marker lookup failed id=" + nodeId, t);
        }

        try {
            if (nodeCache != null) {
                Node n = nodeCache.getNode(nodeId);
                if (n != null && n.getLat() != null && n.getLng() != null) {
                    animateCameraTo(new LatLng(n.getLat(), n.getLng()), 17f);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "focusOnMarker: nodeCache fallback failed id=" + nodeId, t);
        }
    }

    // Fallback getCurrentLocation ile tek seferlik center
    private void requestCurrentLocationForCenter(FusedLocationProviderClient fused) {
        com.google.android.gms.tasks.CancellationTokenSource cts = new com.google.android.gms.tasks.CancellationTokenSource();
        try {
            fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            LatLng me = new LatLng(loc.getLatitude(), loc.getLongitude());
                            animateCameraTo(me, 15f);
                            hasCenteredOnce = true;
                            Log.d(TAG, "requestCurrentLocationForCenter: currentLocation ile odaklandı -> " + me);
                        } else {
                            Log.w(TAG, "requestCurrentLocationForCenter: currentLocation null");
                        }
                    })
                    .addOnFailureListener(e -> Log.e(TAG, "requestCurrentLocationForCenter hata", e));
        } catch (SecurityException se) {
            Log.e(TAG, "requestCurrentLocationForCenter SecurityException", se);
        }
    }

    // Debug helper: harita hazırken kolayca bir test marker ekler
    public void addDebugTestMarker() {
        try {
            GoogleMap gm = haritaManager.getGoogleMap();
            if (gm != null) {
                LatLng testPos = new LatLng(41.008239, 28.978359); // İstanbul (örnek)
                // MarkerManager üzerinden eklemeyi tercih et, yoksa doğrudan googleMap.addMarker() çağr
                try {
                    nodeManager.addDebugMarker(testPos, "TEST"); // MarkerManager böyle bir method içerebilir
                    Log.d(TAG, "addDebugTestMarker: MarkerManager ile test marker istendi");
                } catch (Throwable t) {
                    // Fallback: doğrudan googleMap.addMarker (MarkerManager yoksa)
                    gm.addMarker(new com.google.android.gms.maps.model.MarkerOptions().position(testPos).title("TEST"));
                    gm.moveCamera(CameraUpdateFactory.newLatLngZoom(testPos, 12f));
                    Log.d(TAG, "addDebugTestMarker: googleMap.addMarker fallback ile test marker eklendi");
                }
            } else {
                Log.w(TAG, "addDebugTestMarker: googleMap null, marker eklenemiyor");
            }
        } catch (Exception e) {
            Log.e(TAG, "addDebugTestMarker hata: ", e);
        }
    }
    // Add this method to Harita.java
    public GoogleMap getGoogleMap() {
        if (haritaManager != null) {
            return haritaManager.getGoogleMap();
        }
        return null;
    }
    public void placeDraggableNode(LatLng location) {
        if (nodeManager != null) {
            Log.d(TAG, "placeDraggableMarker çağrıldı: " + location);
            try {
                nodeManager.placeDraggableMarker(location,null,null);
            } catch (Throwable t) {
                Log.w(TAG, "placeDraggableMarker: MarkerManager.placeDraggableMarker yok veya hata: " + t.getMessage());
            }
        } else {
            Log.w(TAG, "placeDraggableMarker: markerManager null");
        }
    }
    public NodeManager getNodeManager() {
        return nodeManager;
    }
    public static void askAndFill(Context context, EditText editText) {
        // Konum izni kontrolü
        if (!hasLocationPermission(context)) {
            // ÖNCE: Hardcoded "Konum izni gerekli"
            // ŞİMDİ: String resource kullanımı
            Toast.makeText(context, context.getString(R.string.harita_toast_location_permission_required), Toast.LENGTH_SHORT).show();
            return;
        }

        // Hemen kullanıcıya dönüş (UI thread) — görünür geri bildirim
        // ÖNCE: Hardcoded "Konum alınıyor..."
        // ŞİMDİ: String resource kullanımı
        editText.post(() -> editText.setText(context.getString(R.string.harita_text_location_fetching)));

        // FusedLocation client
        FusedLocationProviderClient fused = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context);

        // Önce getLastLocation ile hızlı bir sonuç dene
        try {
            fused.getLastLocation()
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            double lat = loc.getLatitude();
                            double lon = loc.getLongitude();
                            String formatted = String.format(java.util.Locale.US,
                                    "LangLat: %.6f,%.6f  —  AvatarLoc:%.6f,%.6f",
                                    lat, lon, lat, lon);
                            // UI thread güvenli güncelleme
                            editText.post(() -> {
                                editText.setText(formatted);
                                // opsiyonel: koordinatları tag'e kaydet
                                editText.setTag(lat + "," + lon);
                            });
                        } else {
                            // lastLocation null ise aktif istek yap
                            requestCurrentLocation(fused, editText);
                        }
                    })
                    .addOnFailureListener(e -> {
                        // hata olursa fallback aktif istek
                        requestCurrentLocation(fused, editText);
                    });
        } catch (SecurityException se) {
            // Güvenlik exception'ı olursa kullanıcıya bildir
            // ÖNCE: Hardcoded "Konum alınamadı (izin)."
            // ŞİMDİ: String resource kullanımı
            editText.post(() -> editText.setText(context.getString(R.string.harita_text_location_failed_permission)));
        }
    }

    // Yardımcı private method: aktif istek (getCurrentLocation) yapar
    private static void requestCurrentLocation(FusedLocationProviderClient fused, EditText editText) {
        com.google.android.gms.tasks.CancellationTokenSource cts = new com.google.android.gms.tasks.CancellationTokenSource();
        try {
            fused.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.getToken())
                    .addOnSuccessListener(loc -> {
                        if (loc != null) {
                            double lat = loc.getLatitude();
                            double lon = loc.getLongitude();
                            // ÖNCE: Hardcoded "long: %.6f  —  lat:%.6f"
                            // ŞİMDİ: String resource kullanımı - format string ile lat ve lon parametreleri
                            // Static metod olduğu için editText.getContext() kullanılıyor
                            Context ctx = editText.getContext();
                            String formatted = ctx.getString(R.string.harita_format_location_coords, lon, lat);
                            editText.post(() -> {
                                editText.setText(formatted);
                                editText.setTag(lat + "," + lon);
                            });
                        } else {
                            // ÖNCE: Hardcoded "Konum alınamadı"
                            // ŞİMDİ: String resource kullanımı
                            editText.post(() -> editText.setText(editText.getContext().getString(R.string.harita_text_location_failed)));
                        }
                    })
                    .addOnFailureListener(e -> {
                        // ÖNCE: Hardcoded "Konum alınamadı"
                        // ŞİMDİ: String resource kullanımı
                        editText.post(() -> editText.setText(editText.getContext().getString(R.string.harita_text_location_failed)));
                    });
        } catch (SecurityException se) {
            // ÖNCE: Hardcoded "Konum alınamadı (izin)."
            // ŞİMDİ: String resource kullanımı
            editText.post(() -> editText.setText(editText.getContext().getString(R.string.harita_text_location_failed_permission)));
        }
    }

    /**
     * İzin sonuçları için callback
     */
    public static void onRequestPermissionsResult(FragmentActivity activity, int requestCode, int[] grantResults) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // İzin verildi
                // ÖNCE: Hardcoded "Konum izni verildi"
                // ŞİMDİ: String resource kullanımı
                Toast.makeText(activity, activity.getString(R.string.harita_toast_location_permission_granted), Toast.LENGTH_SHORT).show();
            } else {
                // İzin reddedildi
                // ÖNCE: Hardcoded "Konum izni reddedildi"
                // ŞİMDİ: String resource kullanımı
                Toast.makeText(activity, activity.getString(R.string.harita_toast_location_permission_denied), Toast.LENGTH_LONG).show();
            }
        }
    }

    // İzin kodu için sabit
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;


    public void cleanup() {
        Log.d(TAG, "cleanup çağrıldı");
        if (haritaManager != null) {
            try {
                haritaManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "haritaManager.cleanup hata: " + t.getMessage());
            }
        }
        if (locationManager != null) {
            try {
                locationManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "locationManager.cleanup hata: " + t.getMessage());
            }
        }
        if (nodeManager != null) {
            try {
                nodeManager.cleanup();
            } catch (Throwable t) {
                Log.w(TAG, "markerManager.cleanup hata: " + t.getMessage());
            }
        }
    }
}
