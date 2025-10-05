package com.kurmez.iyesi.kayra.Classes.ui;

import android.content.Context;

import com.google.android.gms.maps.model.BitmapDescriptor;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.kurmez.iyesi.umay.sokak.Nodes.NodeType;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Pin ikonlarını üretip cache'leyen fabrika (drawable gerektirmez). */
public final class NodeIconFactory {

    private NodeIconFactory() {}

    // Basit cache: "KEY(type|selected)" -> BitmapDescriptor
    private static final Map<String, BitmapDescriptor> CACHE = new HashMap<>();

    // Varsayılan “teal” için HUE_AZURE makul bir karşılık
    private static final float HUE_DEFAULT = BitmapDescriptorFactory.HUE_AZURE;
    private static final float HUE_SELECTED = BitmapDescriptorFactory.HUE_RED;

    /** Genel amaçlı: Enum’a göre ikon (zoom/species/status şimdilik etkisiz, ileride zenginleştirilebilir). */
    public static BitmapDescriptor forMarker(Context ctx,
                                             NodeType type,
                                             String species,
                                             String status,
                                             int zoomBucket) {
        // Şimdilik enum’a göre varsayılan renk döndürelim
        return getDefaultIcon(ctx, mapEnumToString(type));
    }

    /** Seçili (highlight) marker için kırmızı ikon. */
    public static BitmapDescriptor getSelectedIcon(Context ctx, String type) {
        return getColoredIcon(type, /*selected=*/true);
    }

    /** Varsayılan (teal benzeri) ikon. */
    public static BitmapDescriptor getDefaultIcon(Context ctx, String type) {
        // Tür bazlı hafif renk farkı vermek istiyorsan aşağıdaki mapHueForType() kullanılır
        Float hue = mapHueForType(type);
        return getColoredIcon(type, /*selected=*/false, hue != null ? hue : HUE_DEFAULT);
    }

    // -------------------- İç yardımcılar --------------------

    private static BitmapDescriptor getColoredIcon(String type, boolean selected) {
        return getColoredIcon(type, selected, selected ? HUE_SELECTED : HUE_DEFAULT);
    }

    private static BitmapDescriptor getColoredIcon(String type, boolean selected, float hue) {
        final String key = (selected ? "SEL|" : "DEF|") + norm(type) + "|" + hue;
        BitmapDescriptor cached = CACHE.get(key);
        if (cached != null) return cached;

        BitmapDescriptor bd = BitmapDescriptorFactory.defaultMarker(hue);
        CACHE.put(key, bd);
        return bd;
    }

    // Tür adını normalize et
    private static String norm(String t) {
        return t == null ? "default" : t.trim().toLowerCase(Locale.ROOT);
    }

    // TR/EN tür adına göre küçük bir renk farkı (istersen hepsini HUE_AZURE bırakabilirsin)
    private static Float mapHueForType(String type) {
        switch (norm(type)) {
            case "besleme":
            case "feeding":
                return BitmapDescriptorFactory.HUE_GREEN;
            case "yuva":
            case "nest":
                return BitmapDescriptorFactory.HUE_AZURE;   // teal benzeri
            case "barınak":
            case "shelter":
                return BitmapDescriptorFactory.HUE_CYAN;
            case "görev":
            case "gorev":
            case "task":
                return BitmapDescriptorFactory.HUE_VIOLET;
            default:
                return HUE_DEFAULT;
        }
    }

    private static String mapEnumToString(NodeType type) {
        if (type == null) return "default";
        switch (type) {
            case FEEDING: return "besleme";
            case NEST:    return "yuva";
            case SHELTER: return "barınak";
            case TASK:    return "görev";
            default:      return "default";
        }
    }
}
