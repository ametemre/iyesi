// MarkerManager.java
package com.kurmez.iyesi.umay.sokak.Managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import androidx.core.content.ContextCompat;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.*;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Nodes.ui.NodeIconFactory;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MarkerManager {
    private GoogleMap mMap;
    private Context context;
    private final Map<String, Marker> markerById = new ConcurrentHashMap<>();
    private final Map<Marker, String> markerTypeMap = new HashMap<>();
    private Marker highlightedMarker;

    private static final float MARKER_WIDTH_DP = 48f;
    private static final float ICON_DP = 20f;
    private static final float ICON_OFFSET_Y_DP = 13f;
    private static final float ICON_OFFSET_X_DP = 1f;
    private static final Map<String, BitmapDescriptor> iconCache = new HashMap<>();

    public MarkerManager(GoogleMap map, Context context) {
        this.mMap = map;
        this.context = context;
    }

    public Marker addMarker(MarkerOptions options, String type, String id) {
        Marker marker = mMap.addMarker(options);
        if (marker != null && id != null) {
            registerMarker(id, marker, type);
        }
        return marker;
    }

    public void registerMarker(String markerId, Marker marker, String type) {
        marker.setTag(markerId);
        markerById.put(markerId, marker);
        markerTypeMap.put(marker, type);
    }

    public Marker findMarkerById(String markerId) {
        return markerById.get(markerId);
    }

    public void removeMarker(String markerId) {
        Marker marker = markerById.get(markerId);
        if (marker != null) {
            marker.remove();
            markerById.remove(markerId);
            markerTypeMap.remove(marker);
        }
    }

    public void highlightMarker(Marker marker) {
        if (highlightedMarker == marker) return;
        clearMarkerHighlight();

        String uiKey = markerTypeMap.getOrDefault(marker, "default");
        marker.setIcon(NodeIconFactory.getSelectedIcon(context, uiKey));
        highlightedMarker = marker;
    }

    public void clearMarkerHighlight() {
        if (highlightedMarker != null) {
            String uiKey = markerTypeMap.getOrDefault(highlightedMarker, "default");
            highlightedMarker.setIcon(NodeIconFactory.getDefaultIcon(context, uiKey));
            highlightedMarker = null;
        }
    }

    public BitmapDescriptor getCustomIcon(String type) {
        if (iconCache.containsKey(type)) {
            return iconCache.get(type);
        }

        int fgRes;
        switch (type) {
            case "Besleme":
                fgRes = R.drawable.icon_besleme;
                break;
            case "Yuva":

                fgRes = R.drawable.icon_yuva;
                break;
            case "Barınak":
                fgRes = R.drawable.icon_barinak;
                break;
            default:
                BitmapDescriptor def = BitmapDescriptorFactory.defaultMarker();
                iconCache.put(type, def);
                return def;
        }

        BitmapDescriptor bd = createCompositeDescriptor(
                R.drawable.ic_map_marker,
                fgRes
        );
        iconCache.put(type, bd);
        return bd;
    }

    private BitmapDescriptor createCompositeDescriptor(int bgRes, int fgRes) {
        float d = context.getResources().getDisplayMetrics().density;

        int markerWidthPx = (int) (MARKER_WIDTH_DP * d + .5f);
        int iconPx = (int) (ICON_DP * d + .5f);
        int offsetYPx = (int) (ICON_OFFSET_Y_DP * d + .5f);
        int offsetXPx = (int) (ICON_OFFSET_X_DP * d + .5f);

        Drawable bg = ContextCompat.getDrawable(context, bgRes);
        int iw = bg.getIntrinsicWidth(), ih = bg.getIntrinsicHeight();
        float aspect = (float) ih / iw;
        int markerHeightPx = (int) (markerWidthPx * aspect + .5f);
        bg.setBounds(0, 0, markerWidthPx, markerHeightPx);

        Bitmap bmp = Bitmap.createBitmap(markerWidthPx, markerHeightPx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        bg.draw(canvas);

        Drawable fg = ContextCompat.getDrawable(context, fgRes);
        int left = (markerWidthPx - iconPx) / 2 + offsetXPx;
        int top = (markerHeightPx - iconPx) / 2 - offsetYPx;
        fg.setBounds(left, top, left + iconPx, top + iconPx);
        fg.draw(canvas);

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    public void cleanup() {
        for (Marker marker : markerById.values()) {
            marker.remove();
        }
        markerById.clear();
        markerTypeMap.clear();
        iconCache.clear();
        highlightedMarker = null;
    }
}