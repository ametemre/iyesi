package com.kurmez.iyesi.umay.sokak.Managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.*;
// Firestore imports kaldırıldı - direkt erişim artık desteklenmiyor
// import com.google.firebase.firestore.DocumentSnapshot;
// import com.google.firebase.firestore.FirebaseFirestore;
// import com.google.firebase.firestore.QuerySnapshot;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Baksi;
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeIconFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NodeManager
 *
 * - Thread-safe marker registry (ConcurrentHashMap)
 * - UI-thread güvenli marker ekleme/fırlatma helper'ları
 * - icon cache ConcurrentHashMap ile güvenli erişim
 * - debug/test marker, clearAllMarkers, find/remove, highlight/clear vs. metodlar eklendi
 *
 * - Revizyon: Baksi (Veteriner) ve genel Node fetch (Firestore) desteği eklendi.
 *
 * Not: NodeManager doğrudan GoogleMap referansı ile çalışır; GoogleMap null ise metotlar no-op/log yapar.
 */
public class NodeManager {

    private static final String TAG = "NodeManager";

    private GoogleMap mMap;
    private final Context context;

    // id -> Marker
    private final Map<String, Marker> markerById = new ConcurrentHashMap<>();
    // Marker -> type
    private final Map<Marker, String> markerTypeMap = new ConcurrentHashMap<>();
    private volatile Marker highlightedMarker;

    private static final float MARKER_WIDTH_DP = 48f;
    private static final float ICON_DP = 20f;
    private static final float ICON_OFFSET_Y_DP = 13f;
    private static final float ICON_OFFSET_X_DP = 1f;

    // thread-safe cache
    private static final Map<String, BitmapDescriptor> iconCache = new ConcurrentHashMap<>();

    // Main thread handler for UI operations
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Firestore instance - artık kullanılmıyor (güvenlik kuralları nedeniyle)
    // @Deprecated - Tüm veri erişimi Cloud Function üzerinden yapılmalı
    // private final FirebaseFirestore firestore;

    public NodeManager(GoogleMap map, Context context) {
        this.mMap = map;
        this.context = context;
        // Firestore instance kaldırıldı - direkt erişim artık desteklenmiyor
        // this.firestore = FirebaseFirestore.getInstance();
        Log.d(TAG, "NodeManager constructed. googleMap != null? " + (map != null));
    }

    private boolean mapAvailable() {
        if (mMap == null) {
            Log.w(TAG, "GoogleMap null - işlem atlandı");
            return false;
        }
        return true;
    }

    /**
     * Genel purpose addMarker: verilen options ile main thread'te marker ekler, id ile kaydeder.
     * Eğer çağrılan thread main değilse, iş UI thread'e post edilir.
     */
    public Marker addMarker(final MarkerOptions options, final String type, final String id) {
        if (!mapAvailable()) return null;
        if (options == null) {
            Log.w(TAG, "addMarker: MarkerOptions null");
            return null;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return internalAddMarker(options, type, id);
        } else {
            final Object lock = new Object();
            final Marker[] out = new Marker[1];
            mainHandler.post(() -> {
                try {
                    out[0] = internalAddMarker(options, type, id);
                } catch (Throwable t) {
                    Log.e(TAG, "addMarker (posted) hata", t);
                } finally {
                    synchronized (lock) {
                        lock.notifyAll();
                    }
                }
            });
            try {
                synchronized (lock) {
                    lock.wait(250);
                }
            } catch (InterruptedException ignored) { }
            return out[0];
        }
    }

    private Marker internalAddMarker(MarkerOptions options, String type, String id) {
        try {
            Marker marker = mMap.addMarker(options);
            if (marker != null && id != null) {
                registerMarker(id, marker, type);
            } else if (marker == null) {
                Log.w(TAG, "internalAddMarker: mMap.addMarker döndü null");
            }
            return marker;
        } catch (Exception e) {
            Log.e(TAG, "internalAddMarker hata: ", e);
            return null;
        }
    }

    public void registerMarker(String markerId, Marker marker, String type) {
        if (markerId == null || marker == null) {
            Log.w(TAG, "registerMarker: markerId veya marker null");
            return;
        }
        // Aynı markerId için daha önce eklenmiş bir marker varsa onu map'ten kaldır.
        // Aksi halde kamera hareketlerinde/fetch tekrarlarında "ghost duplicate" marker'lar kalıp
        // toggle ile gizlenemez hale geliyor.
        Marker prev = markerById.get(markerId);
        if (prev != null && prev != marker) {
            try {
                prev.remove();
            } catch (Throwable ignored) { }
            try {
                markerTypeMap.remove(prev);
            } catch (Throwable ignored) { }
            if (highlightedMarker == prev) {
                highlightedMarker = null;
            }
            Log.d(TAG, "registerMarker: replaced existing marker id=" + markerId);
        }
        marker.setTag(markerId);
        markerById.put(markerId, marker);
        markerTypeMap.put(marker, type != null ? type : "default");
        Log.d(TAG, "registerMarker: id=" + markerId + " type=" + type);
    }

    public Marker findMarkerById(String markerId) {
        if (markerId == null) return null;
        return markerById.get(markerId);
    }

    public Collection<Marker> getAllMarkers() {
        return markerById.values();
    }

    public void removeMarker(String markerId) {
        if (markerId == null) return;
        Marker marker = markerById.remove(markerId);
        if (marker != null) {
            try {
                marker.remove();
                markerTypeMap.remove(marker);
                Log.d(TAG, "removeMarker: removed id=" + markerId);
            } catch (Exception e) {
                Log.e(TAG, "removeMarker hata: ", e);
            }
        } else {
            Log.w(TAG, "removeMarker: marker bulunamadı id=" + markerId);
        }
    }
    // NodeManager sınıfına bu metodu ekleyin
    public void removeMarkersByType(final String type) {
        if (!mapAvailable()) return;

        mainHandler.post(() -> {
            try {
                List<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, Marker> entry : markerById.entrySet()) {
                    Marker marker = entry.getValue();
                    String markerType = markerTypeMap.get(marker);
                    if (type.equals(markerType)) {
                        toRemove.add(entry.getKey());
                    }
                }

                for (String id : toRemove) {
                    removeMarker(id);
                }
                Log.d(TAG, "removeMarkersByType: " + type + " - " + toRemove.size() + " marker temizlendi");
            } catch (Throwable t) {
                Log.e(TAG, "removeMarkersByType hata: ", t);
            }
        });
    }
    public void clearAllMarkers() {
        if (!mapAvailable()) return;
        mainHandler.post(() -> {
            try {
                for (Marker m : markerById.values()) {
                    try { m.remove(); } catch (Throwable t) { }
                }
                markerById.clear();
                markerTypeMap.clear();
                highlightedMarker = null;
                Log.d(TAG, "clearAllMarkers: tamamlandı");
            } catch (Throwable t) {
                Log.e(TAG, "clearAllMarkers hata: ", t);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void highlightMarker(final Marker marker) {
        if (marker == null) return;
        mainHandler.post(() -> {
            try {
                if (highlightedMarker == marker) return;
                clearMarkerHighlight();

                String uiKey = markerTypeMap.getOrDefault(marker, "default");
                marker.setIcon(NodeIconFactory.getSelectedIcon(context, uiKey));
                highlightedMarker = marker;
                Log.d(TAG, "highlightMarker: marker highlighted type=" + uiKey);
            } catch (Throwable t) {
                Log.e(TAG, "highlightMarker hata: ", t);
            }
        });
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    public void clearMarkerHighlight() {
        mainHandler.post(() -> {
            try {
                if (highlightedMarker != null) {
                    String uiKey = markerTypeMap.getOrDefault(highlightedMarker, "default");
                    highlightedMarker.setIcon(NodeIconFactory.getDefaultIcon(context, uiKey));
                    highlightedMarker = null;
                    Log.d(TAG, "clearMarkerHighlight: tamam");
                }
            } catch (Throwable t) {
                Log.e(TAG, "clearMarkerHighlight hata: ", t);
            }
        });
    }

    public BitmapDescriptor getCustomIcon(String type) {
        if (type == null) type = "default";
        if (iconCache.containsKey(type)) return iconCache.get(type);

        BitmapDescriptor bd = createIconForType(type);
        if (bd != null) {
            iconCache.put(type, bd);
        } else {
            bd = BitmapDescriptorFactory.defaultMarker();
            iconCache.put(type, bd);
        }
        return bd;
    }

    private BitmapDescriptor createIconForType(String type) {
        // Type varyasyonlarını normalize et (backend "feeding"/"shelter"/"task" gibi dönebilir)m
        String key = type;
        if (key == null) key = "default";
        key = key.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("ı", "i").replace("ğ", "g").replace("ü", "u")
                .replace("ş", "s").replace("ö", "o").replace("ç", "c");

        int fgRes;
        switch (key) {
            case "besleme":
            case "feeding":
                fgRes = R.drawable.icon_besleme;
                break;
            case "yuva":
            case "nest":
                fgRes = R.drawable.icon_yuva;
                break;
            case "barinak":
            case "shelter":
                fgRes = R.drawable.icon_barinak;
                break;
            case "saglik": // Baksi tipi için ikon öngörüldü
            case "baksi":
                fgRes = R.drawable.icon_saglik;
                break;
            default:
                return BitmapDescriptorFactory.defaultMarker();
        }

        try {
            return createCompositeDescriptor(R.drawable.ic_map_marker, fgRes);
        } catch (Throwable t) {
            Log.w(TAG, "createCompositeDescriptor hata, fallback default marker: " + t.getMessage());
            return BitmapDescriptorFactory.defaultMarker();
        }
    }

    private BitmapDescriptor createCompositeDescriptor(int bgRes, int fgRes) {
        float d = context.getResources().getDisplayMetrics().density;
        int markerWidthPx = (int) (MARKER_WIDTH_DP * d + .5f);
        int iconPx = (int) (ICON_DP * d + .5f);
        int offsetYPx = (int) (ICON_OFFSET_Y_DP * d );
        int offsetXPx = (int) (ICON_OFFSET_X_DP * d + .5f);

        Drawable bg = ContextCompat.getDrawable(context, bgRes);
        if (bg == null) {
            Log.w(TAG, "createCompositeDescriptor: bg drawable null, fallback default marker");
            return BitmapDescriptorFactory.defaultMarker();
        }

        int iw = bg.getIntrinsicWidth(), ih = bg.getIntrinsicHeight();
        float aspect = iw == 0 ? 1f : (float) ih / iw;
        int markerHeightPx = (int) (markerWidthPx * aspect + .5f);
        bg.setBounds(0, 0, markerWidthPx, markerHeightPx);

        Bitmap bmp = Bitmap.createBitmap(markerWidthPx, markerHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        bg.draw(canvas);

        Drawable fg = ContextCompat.getDrawable(context, fgRes);
        if (fg != null) {
            int left = (markerWidthPx - iconPx) / 2 + offsetXPx;
            int top = (markerHeightPx - iconPx) / 2 - offsetYPx;
            fg.setBounds(left, top, left + iconPx, top + iconPx);
            fg.draw(canvas);
        } else {
            Log.w(TAG, "createCompositeDescriptor: fg drawable null, sadece bg çizildi");
        }

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    /**
     * Firestore'dan VetNodes koleksiyonunu çek ve haritaya ekle.
     * 
     * DEPRECATED: Bu metot artık kullanılmıyor. Firestore security rules tüm kullanıcı erişimlerini engelliyor.
     * Tüm veri erişimi Cloud Function üzerinden yapılmalı (findNearbyBaksi).
     * 
     * Bu metot çağrılırsa sadece log yazar, Firestore'a erişmez.
     */
    @Deprecated
    public void loadVetNodes() {
        Log.w(TAG, "loadVetNodes: DEPRECATED - Firestore direkt erişimi artık desteklenmiyor. " +
                "Cloud Function (findNearbyBaksi) kullanılmalı.");
        // Firestore direkt erişimi kaldırıldı - güvenlik kuralları nedeniyle
        // Tüm veri erişimi Cloud Function üzerinden yapılmalı
    }


    public void addDebugMarker(final LatLng pos, final String title) {
        if (!mapAvailable()) return;
        mainHandler.post(() -> {
            try {
                MarkerOptions options = new MarkerOptions()
                        .position(pos)
                        .title(title)
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE));
                Marker m = mMap.addMarker(options);
                if (m != null) {
                    String id = "debug-" + System.currentTimeMillis();
                    registerMarker(id, m, "debug");
                    Log.d(TAG, "addDebugMarker: eklendi id=" + id + " pos=" + pos);
                }
            } catch (Throwable t) {
                Log.e(TAG, "addDebugMarker hata: ", t);
            }
        });
    }

    public void placeDraggableMarker(final LatLng location, @Nullable final String id, @Nullable final String type) {
        if (id == null) { Objects.equals("𐰚𐰃𐰼𐰇", id); }
        if (type == null) { Objects.equals("𐰼", id); }
        if (!mapAvailable()) return;
        mainHandler.post(() -> {
            try {
                MarkerOptions options = new MarkerOptions()
                        .position(location)
                        .draggable(true)
                        .title(type != null ? type : "İlan");
                Marker marker = mMap.addMarker(options);
                if (marker != null && id != null) {
                    registerMarker(id, marker, type);
                    Log.d(TAG, "placeDraggableMarker: eklendi id=" + id + " loc=" + location);
                }
            } catch (Throwable t) {
                Log.e(TAG, "placeDraggableMarker hata: ", t);
            }
        });
    }

    public void cleanup() {
        try {
            if (mMap != null) {
                for (Marker m : markerById.values()) {
                    try { m.remove(); } catch (Throwable ignored) { }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "cleanup sırasında hata: " + t.getMessage());
        } finally {
            markerById.clear();
            markerTypeMap.clear();
            iconCache.clear();
            highlightedMarker = null;
            Log.d(TAG, "cleanup tamamlandı");
        }
    }

    public void setMap(GoogleMap map) {
        this.mMap = map;
        Log.d(TAG, "setMap: yeni googleMap != null? " + (map != null));
    }

    public static void clearIconCache() {
        iconCache.clear();
        Log.d(TAG, "clearIconCache: tamam");
    }
}
