package com.kurmez.iyesi.kurmes.seed;

import android.util.Base64;
import org.json.JSONObject;
import java.util.*;
import java.text.SimpleDateFormat;

public class SeedUtils {

    public static final List<String> TURKEY_CITIES = Arrays.asList(
        "Adana","Adıyaman","Afyonkarahisar","Ağrı","Amasya","Ankara","Antalya","Artvin","Aydın","Balıkesir",
        "Bilecik","Bingöl","Bitlis","Bolu","Burdur","Bursa","Çanakkale","Çankırı","Çorum","Denizli",
        "Diyarbakır","Edirne","Elazığ","Erzincan","Erzurum","Eskişehir","Gaziantep","Giresun","Gümüşhane","Hakkari",
        "Hatay","Isparta","Mersin","İstanbul","İzmir","Kars","Kastamonu","Kayseri","Kırklareli","Kırşehir",
        "Kocaeli","Konya","Kütahya","Malatya","Manisa","Kahramanmaraş","Mardin","Muğla","Muş","Nevşehir",
        "Niğde","Ordu","Rize","Sakarya","Samsun","Siirt","Sinop","Sivas","Tekirdağ","Tokat",
        "Trabzon","Tunceli","Şanlıurfa","Uşak","Van","Yozgat","Zonguldak","Aksaray","Bayburt","Karaman",
        "Kırıkkale","Batman","Şırnak","Bartın","Ardahan","Iğdır","Yalova","Karabük","Kilis","Osmaniye","Düzce"
    );

    private static final double[] TR_BBOX = new double[]{25.0, 35.0, 45.0, 42.5};

    private static final String[] SPECIES = {"Cat","Dog","Bird","Hedgehog","Tortoise","Crow","Donkey","Fox","Goat","Sheep"};
    private static final String[] BREEDS  = {"Mix","Anatolian","Van","Kangal","Sarman","Tekir","Akbaş","Siyah","Yerli"};
    private static final String[] HEALTHS = {"Critical","Needs Attention","Fair","Good","Unknown"};
    private static final String[] STATUSES= {"pending","adoptable","found","assigned","observed"};

    private static final byte[] PNG_1PX = new byte[]{
            (byte)0x89,0x50,0x4E,0x47,0x0D,0x0A,0x1A,0x0A,0x00,0x00,0x00,0x0D,0x49,0x48,0x44,0x52,
            0x00,0x00,0x00,0x01,0x00,0x00,0x00,0x01,0x08,0x02,0x00,0x00,0x00,(byte)0x90,0x77,0x53,
            (byte)0xDE,0x00,0x00,0x00,0x0A,0x49,0x44,0x41,0x54,0x78,(byte)0xDA,0x63,(byte)0xF8,0x0F,0x00,0x01,
            0x01,0x01,0x00,0x18,(byte)0xDD,(byte)0x8D,(byte)0xB1,0x00,0x00,0x00,0x00,0x49,0x45,0x4E,
            0x44,(byte)0xAE,0x42,0x60,(byte)0x82
    };

    public static double[] randomPointInTurkey(int citySeed) {
        Random rnd = new Random(citySeed);
        double lonMin = TR_BBOX[0], latMin = TR_BBOX[1], lonMax = TR_BBOX[2], latMax = TR_BBOX[3];
        double cityLon = lonMin + 0.5 + rnd.nextDouble() * (lonMax - lonMin - 1.0);
        double cityLat = latMin + 0.5 + rnd.nextDouble() * (latMax - latMin - 1.0);
        return new double[]{cityLat, cityLon};
    }

    public static double[] jitterNear(double lat, double lon, double meters) {
        double degLat = meters / 111320.0;
        double degLon = meters / (111320.0 * Math.max(0.2, Math.abs(Math.cos(lat * Math.PI / 180.0))));
        double jLat = lat + (Math.random() * 2 - 1) * degLat;
        double jLon = lon + (Math.random() * 2 - 1) * degLon;
        return new double[]{jLat, jLon};
    }

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    public static String geohashEncode(double lat, double lon, int precision) {
        double[] latI = new double[]{-90.0, 90.0};
        double[] lonI = new double[]{-180.0, 180.0};
        boolean even = true;
        int bitsTotal = precision * 5;
        int[] bits = new int[bitsTotal];
        int bi = 0;
        while (bi < bitsTotal) {
            if (even) {
                double mid = (lonI[0] + lonI[1]) / 2.0;
                if (lon > mid) { lonI[0] = mid; bits[bi++] = 1; } else { lonI[1] = mid; bits[bi++] = 0; }
            } else {
                double mid = (latI[0] + latI[1]) / 2.0;
                if (lat > mid) { latI[0] = mid; bits[bi++] = 1; } else { latI[1] = mid; bits[bi++] = 0; }
            }
            even = !even;
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < bits.length; i += 5) {
            int idx = 0;
            for (int j = i; j < i + 5; j++) idx = (idx << 1) | bits[j];
            out.append(BASE32.charAt(idx));
        }
        return out.toString();
    }

    public static String makeDataUrlPng() {
        return "data:image/png;base64," + Base64.encodeToString(PNG_1PX, Base64.NO_WRAP);
    }

    public static JSONObject pickIyeForSoul(String markerId) {
        double r = Math.random();
        try {
            JSONObject iye = new JSONObject();
            if (r < 0.25) {
                iye.put("kind", "none");  iye.put("value", JSONObject.NULL);
            } else if (r < 0.50) {
                iye.put("kind", "role");  iye.put("value", "Körmes");
            } else if (r < 0.75) {
                String uid = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                iye.put("kind", "uid");   iye.put("value", uid);
            } else {
                String mid = markerId != null ? markerId : "m_" + UUID.randomUUID().toString().substring(0,6);
                iye.put("kind", "marker"); iye.put("value", mid);
            }
            return iye;
        } catch (Exception e) { return null; }
    }

    public static List<JSONObject> generateSoulsForCity(String city, double centerLat, double centerLon,
                                                        int soulsPerCity, boolean withImages, long nowMillis) {
        List<JSONObject> out = new ArrayList<>();
        String cityKey = city.toLowerCase(Locale.US).replace(" ", "_");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (int i = 0; i < soulsPerCity; i++) {
            try {
                double[] jl = jitterNear(centerLat, centerLon, 500 + (i % 300) * 5);
                double lat = jl[0], lon = jl[1];
                String gh = geohashEncode(lat, lon, 7);
                String sid = cityKey + "_s" + String.format(Locale.US, "%04d", i);

                String species = SPECIES[(int)(Math.random() * SPECIES.length)];
                String breed   = BREEDS[(int)(Math.random() * BREEDS.length)];
                String health  = HEALTHS[(int)(Math.random() * HEALTHS.length)];
                String status  = STATUSES[(int)(Math.random() * STATUSES.length)];
                JSONObject iye = pickIyeForSoul(null);

                JSONObject doc = new JSONObject();
                doc.put("name", sid.replace("_"," "));
                doc.put("species", species);
                doc.put("breed", breed);
                doc.put("health", health);
                doc.put("foundDate", sdf.format(new Date()));
                doc.put("foundLocation", city);
                if (withImages) doc.put("imageUrl", makeDataUrlPng());
                doc.put("timestamp", nowMillis);
                doc.put("ts", nowMillis);
                JSONObject loc = new JSONObject();
                loc.put("_lat", lat); loc.put("_long", lon);
                doc.put("location", loc);
                doc.put("lat", lat);
                doc.put("lng", lon);
                doc.put("geohash", gh);
                doc.put("status", status);
                doc.put("iye", iye);
                doc.put("adminPath", "TR/" + city.toUpperCase(Locale.US));
                doc.put("city", city.toUpperCase(Locale.US));
                if (iye != null && "uid".equals(iye.optString("kind")) && !iye.isNull("value")) {
                    doc.put("ownerUid", iye.optString("value"));
                }
                out.add(doc);
            } catch (Exception ignore) {}
        }
        return out;
    }

    public static double[] citySeedPoint(String city) {
        int seed = Math.abs(city.hashCode()) % 1_000_000;
        return randomPointInTurkey(seed);
    }
}