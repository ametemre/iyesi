package com.kurmez.iyesi.umay.sokak;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.maps.android.data.geojson.GeoJsonFeature;
import com.google.maps.android.data.geojson.GeoJsonLayer;
import com.google.maps.android.data.geojson.GeoJsonPolygonStyle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * GeoSon: Gelen GeoJSON’u, yalnızca “ilgi alanı” içindeki Feature’ları alarak
 * yeni bir GeoJsonLayer oluşturup haritaya ekler.
 */
public class GeoSon {

    private static final String TAG = "GeoSon";

    /**
     * @param map           Çizimi yapacağınız GoogleMap nesnesi
     * @param rawGeoJson    Ham GeoJSON metni
     * @param center        “İlgi alanı”nın merkezi konumu (genellikle kullanıcı lokasyonu)
     * @param radiusMeters  Kaç metre yarıçapındaki Feature’ları dahil edeceğiz
     */
// GeoSon.filterAndDraw metodunu şöyle güncelleyin:
    public static void filterAndDraw(Context context,
                                     GoogleMap map,
                                     String rawGeoJson,
                                     LatLng center,
                                     double radiusMeters) {
        try {
            JSONObject full = new JSONObject(rawGeoJson);
            JSONArray features = full.getJSONArray("features");

            JSONObject filtered = new JSONObject();
            filtered.put("type", "FeatureCollection");
            filtered.put("features", new JSONArray());

            for (int i = 0; i < features.length(); i++) {
                JSONObject feat = features.getJSONObject(i);
                if (featureIntersectsCircle(feat, center, radiusMeters)) {
                    filtered.getJSONArray("features").put(feat);
                }
            }

            GeoJsonLayer layer = new GeoJsonLayer(map, filtered);

            // Stil ayarları...
            GeoJsonPolygonStyle defaultStyle = layer.getDefaultPolygonStyle();
            defaultStyle.setFillColor(Color.argb(70, 255, 0, 0));
            defaultStyle.setStrokeColor(Color.RED);
            defaultStyle.setStrokeWidth(3f);

            int colorIndex = 0;
            int[] colors = {
                    Color.argb(70, 255, 0, 0),
                    Color.argb(70, 0, 255, 0),
                    Color.argb(70, 0, 0, 255)
            };
            for (GeoJsonFeature gFeat : layer.getFeatures()) {
                if (gFeat.hasGeometry()) {
                    GeoJsonPolygonStyle style = new GeoJsonPolygonStyle();
                    boolean containsUser = false;

                    // Eğer geometry Polygon ise
                    if (gFeat.getGeometry() instanceof com.google.maps.android.data.geojson.GeoJsonPolygon) {
                        java.util.List<com.google.android.gms.maps.model.LatLng> outer =
                                ((com.google.maps.android.data.geojson.GeoJsonPolygon) gFeat.getGeometry()).getCoordinates().get(0);
                        if (com.google.maps.android.PolyUtil.containsLocation(
                                center.latitude, center.longitude, outer, false)) {
                            containsUser = true;
                        }
                    }
                    // MultiPolygon için de benzer şekilde loop ekle (isteğe bağlı)

                    if (containsUser) {
                        style.setFillColor(Color.argb(51, 0, 0, 255)); // Belirgin mavi, %20 şeffaflık
                        style.setStrokeColor(Color.BLUE);
                        style.setStrokeWidth(2f);
                    } else {
                        style.setFillColor(Color.argb(10, 0, 0, 255)); // Neredeyse şeffaf
                        style.setStrokeColor(Color.argb(10, 0, 0, 255));
                        style.setStrokeWidth(1f);
                    }
                    gFeat.setPolygonStyle(style);
                }
            }


            layer.addLayerToMap();
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(39, 35), 5));
            Toast.makeText(context, "Sınırlar başarıyla yüklendi.", Toast.LENGTH_SHORT).show();

        } catch (JSONException e) {
            Log.e(TAG, "GeoJSON parse hatası (filterAndDraw): " + e.getMessage());
            Toast.makeText(context, "GeoJSON işleme hatası.", Toast.LENGTH_SHORT).show();
        }
    }




    /**
     * Verilen Feature’ın geometri koordinatlarından birinin “center” noktasına
     * distance ≤ radiusMeters olup olmadığını kontrol eder.
     * Basitleştirilmiş metot: sadece Polygon’ın herhangi bir vertex’inin içine girip
     * girmediğine bakılır. Daha kesin kesişme için bounding box veya point-in-polygon
     * testleri ekleyebilirsiniz.
     *
     * @param feature       Sorgulanacak Feature
     * @param center        Merkez Konum
     * @param radiusMeters  Yarıçap (metre)
     * @return true eğer Feature merkez noktasından ya da bounding box içinden kesişiyorsa
     */
    private static boolean featureIntersectsCircle(JSONObject feature,
                                                   LatLng center,
                                                   double radiusMeters) {
        try {
            JSONObject geom = feature.getJSONObject("geometry");
            JSONArray coordsArray = geom.getJSONArray("coordinates");
            String type = geom.getString("type");

            if ("Polygon".equals(type)) {
                JSONArray rings = coordsArray.getJSONArray(0);
                for (int i = 0; i < rings.length(); i++) {
                    JSONArray pt = rings.getJSONArray(i);
                    double lon = pt.getDouble(0), lat = pt.getDouble(1);
                    if (distanceMeters(center.latitude, center.longitude, lat, lon) <= radiusMeters) {
                        return true;
                    }
                }
            } else if ("MultiPolygon".equals(type)) {
                for (int r = 0; r < coordsArray.length(); r++) {
                    JSONArray rings = coordsArray.getJSONArray(r).getJSONArray(0);
                    for (int i = 0; i < rings.length(); i++) {
                        JSONArray pt = rings.getJSONArray(i);
                        double lon = pt.getDouble(0), lat = pt.getDouble(1);
                        if (distanceMeters(center.latitude, center.longitude, lat, lon) <= radiusMeters) {
                            return true;
                        }
                    }
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "featureIntersectsCircle hatası: " + e.getMessage());
        }
        return false;
    }

    /**
     * İki enlem-boylam noktasının metre cinsinden yaklaşık mesafesini döner.
     * Haversine formülü kullanılmıştır.
     */
    private static double distanceMeters(double lat1, double lon1,
                                         double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);

        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.sin(dLon / 2) * Math.sin(dLon / 2) * Math.cos(rLat1) * Math.cos(rLat2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double R = 6371000; // Dünya yarıçapı metre
        return R * c;
    }
}