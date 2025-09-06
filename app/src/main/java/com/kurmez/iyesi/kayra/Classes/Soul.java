package com.kurmez.iyesi.kayra.Classes;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.firestore.GeoPoint;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Soul — Bir can (hayvan) kaydı.
 *
 * Tasarım hedefleri:
 * - Firestore ile doğal eşleme (no-arg ctor, public getters/setters)
 * - "PendingCompanions" mantığı için: iyeId==null → İyesi yok (pending adayı)
 * - Harita sorguları için: location (GeoPoint), geohash (String), ts (Long)
 * - Liste/Intent taşımak için: Parcelable
 *
 * NOT:
 * - "status" alanı için örnek değerler: "pending", "adoptable", "found", "assigned"...
 * - "id" Firestore docId olarak DTO seviyesinde tutulabilir (opsiyonel).
 */
@Keep
public class Soul implements Parcelable {

    // ---------- Temel alanlar ----------
    @Nullable private String id;              // Firestore docId (ops.)
    @Nullable private String name;
    @Nullable private String species;
    @Nullable private String breed;
    @Nullable private String age;
    @Nullable private String health;
    @Nullable private String foundDate;
    @Nullable private String foundLocation;
    @Nullable private String veterinary;

    // Görsel alan adı bazı yerlerde imageResId olarak geçiyor — imageUrl ile eşliyoruz.
    @Nullable private String imageUrl;
    @Nullable private String finderName;

    /** Sunucudan/CF’den gelen zaman damgası (ms). */
    private long timestamp;                   // CFHelper.parsePriorityPets ile uyumlu
    /** Veri katmanında sorgu penceresi için kullanılacak alan (epoch ms). */
    @Nullable private Long ts;

    // ---------- İlişki/Durum ----------
    /** İyesi ataması: null → İyesi yok (pending adayı). */
    @Nullable private String iyeId;
    /** Örn. "pending", "adoptable", "found", "assigned" ...  */
    @Nullable private String status;

    // ---------- Konum / Harita ----------
    @Nullable private GeoPoint location;
    @Nullable private String geohash;
    /** Opsiyonel: idari filtrelemede kullanmak istersen. */
    @Nullable private String adminPath;

    // ---------- Firestore için no-arg ctor ----------
    @Keep
    public Soul() { }

    /**
     * CFHelper.parsePriorityPets(...) ile birebir uyumlu constructor (mevcut akışını bozmamak için).
     */
    public Soul(@Nullable String name,
                @Nullable String species,
                @Nullable String breed,
                @Nullable String age,
                @Nullable String health,
                @Nullable String foundDate,
                @Nullable String foundLocation,
                @Nullable String veterinary,
                @Nullable String imageUrl,
                @Nullable String finderName,
                long timestamp) {
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.age = age;
        this.health = health;
        this.foundDate = foundDate;
        this.foundLocation = foundLocation;
        this.veterinary = veterinary;
        this.imageUrl = imageUrl;
        this.finderName = finderName;
        this.timestamp = timestamp;
    }

    /**
     * Tam alanlı kurucu (ihtiyaç olduğunda kullan).
     */
    public Soul(@Nullable String id,
                @Nullable String name,
                @Nullable String species,
                @Nullable String breed,
                @Nullable String age,
                @Nullable String health,
                @Nullable String foundDate,
                @Nullable String foundLocation,
                @Nullable String veterinary,
                @Nullable String imageUrl,
                @Nullable String finderName,
                long timestamp,
                @Nullable Long ts,
                @Nullable String iyeId,
                @Nullable String status,
                @Nullable GeoPoint location,
                @Nullable String geohash,
                @Nullable String adminPath) {
        this.id = id;
        this.name = name;
        this.species = species;
        this.breed = breed;
        this.age = age;
        this.health = health;
        this.foundDate = foundDate;
        this.foundLocation = foundLocation;
        this.veterinary = veterinary;
        this.imageUrl = imageUrl;
        this.finderName = finderName;
        this.timestamp = timestamp;
        this.ts = ts;
        this.iyeId = iyeId;
        this.status = status;
        this.location = location;
        this.geohash = geohash;
        this.adminPath = adminPath;
    }

    // ---------- İşlevsel yardımcılar ----------
    public boolean hasIyesi() {
        return iyeId != null && !iyeId.trim().isEmpty();
    }

    public boolean isPending() {
        return !hasIyesi() && "pending".equalsIgnoreCase(safe(status));
    }

    // ---------- Getters / Setters ----------
    @Nullable public String getId() { return id; }
    public void setId(@Nullable String id) { this.id = id; }

    @Nullable public String getName() { return name; }
    public void setName(@Nullable String name) { this.name = name; }

    @Nullable public String getSpecies() { return species; }
    public void setSpecies(@Nullable String species) { this.species = species; }

    @Nullable public String getBreed() { return breed; }
    public void setBreed(@Nullable String breed) { this.breed = breed; }

    @Nullable public String getAge() { return age; }
    public void setAge(@Nullable String age) { this.age = age; }

    @Nullable public String getHealth() { return health; }
    public void setHealth(@Nullable String health) { this.health = health; }

    @Nullable public String getFoundDate() { return foundDate; }
    public void setFoundDate(@Nullable String foundDate) { this.foundDate = foundDate; }

    @Nullable public String getFoundLocation() { return foundLocation; }
    public void setFoundLocation(@Nullable String foundLocation) { this.foundLocation = foundLocation; }

    @Nullable public String getVeterinary() { return veterinary; }
    public void setVeterinary(@Nullable String veterinary) { this.veterinary = veterinary; }

    // Görsel için uyumluluk getter/setter’ları:
    @Nullable public String getImageUrl() { return imageUrl; }
    public void setImageUrl(@Nullable String imageUrl) { this.imageUrl = imageUrl; }
    /** Bazı adapter’lar imageResId bekliyor → imageUrl ile eşlenir. */
    @Nullable public String getImageResId() { return imageUrl; }
    public void setImageResId(@Nullable String v) { this.imageUrl = v; }

    @Nullable public String getFinderName() { return finderName; }
    public void setFinderName(@Nullable String finderName) { this.finderName = finderName; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    @Nullable public Long getTs() { return ts; }
    public void setTs(@Nullable Long ts) { this.ts = ts; }

    @Nullable public String getIyeId() { return iyeId; }
    public void setIyeId(@Nullable String iyeId) { this.iyeId = iyeId; }

    @Nullable public String getStatus() { return status; }
    public void setStatus(@Nullable String status) { this.status = status; }

    @Nullable public GeoPoint getLocation() { return location; }
    public void setLocation(@Nullable GeoPoint location) { this.location = location; }

    @Nullable public String getGeohash() { return geohash; }
    public void setGeohash(@Nullable String geohash) { this.geohash = geohash; }

    @Nullable public String getAdminPath() { return adminPath; }
    public void setAdminPath(@Nullable String adminPath) { this.adminPath = adminPath; }

    /** Hızlı erişim: konumdan lat/lng döndür (yoksa null). */
    @Nullable public Double getLat() { return location != null ? location.getLatitude() : null; }
    @Nullable public Double getLng() { return location != null ? location.getLongitude() : null; }

    // ---------- Parcelable ----------
    protected Soul(Parcel in) {
        id = readNullableString(in);
        name = readNullableString(in);
        species = readNullableString(in);
        breed = readNullableString(in);
        age = readNullableString(in);
        health = readNullableString(in);
        foundDate = readNullableString(in);
        foundLocation = readNullableString(in);
        veterinary = readNullableString(in);
        imageUrl = readNullableString(in);
        finderName = readNullableString(in);
        timestamp = in.readLong();
        if (in.readByte() == 0) {
            ts = null;
        } else {
            ts = in.readLong();
        }
        iyeId = readNullableString(in);
        status = readNullableString(in);

        double lat = in.readDouble();
        double lng = in.readDouble();
        if (!Double.isNaN(lat) && !Double.isNaN(lng)) {
            location = new GeoPoint(lat, lng);
        } else {
            location = null;
        }
        geohash = readNullableString(in);
        adminPath = readNullableString(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        writeNullableString(dest, id);
        writeNullableString(dest, name);
        writeNullableString(dest, species);
        writeNullableString(dest, breed);
        writeNullableString(dest, age);
        writeNullableString(dest, health);
        writeNullableString(dest, foundDate);
        writeNullableString(dest, foundLocation);
        writeNullableString(dest, veterinary);
        writeNullableString(dest, imageUrl);
        writeNullableString(dest, finderName);

        dest.writeLong(timestamp);
        if (ts == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeLong(ts);
        }

        writeNullableString(dest, iyeId);
        writeNullableString(dest, status);

        if (location != null) {
            dest.writeDouble(location.getLatitude());
            dest.writeDouble(location.getLongitude());
        } else {
            dest.writeDouble(Double.NaN);
            dest.writeDouble(Double.NaN);
        }

        writeNullableString(dest, geohash);
        writeNullableString(dest, adminPath);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<Soul> CREATOR = new Creator<Soul>() {
        @Override
        public Soul createFromParcel(Parcel in) { return new Soul(in); }
        @Override
        public Soul[] newArray(int size) { return new Soul[size]; }
    };

    // ---------- Builder ----------
    public static class Builder {
        private final Soul s = new Soul();
        public Builder id(@Nullable String v) { s.setId(v); return this; }
        public Builder name(@Nullable String v) { s.setName(v); return this; }
        public Builder species(@Nullable String v) { s.setSpecies(v); return this; }
        public Builder breed(@Nullable String v) { s.setBreed(v); return this; }
        public Builder age(@Nullable String v) { s.setAge(v); return this; }
        public Builder health(@Nullable String v) { s.setHealth(v); return this; }
        public Builder foundDate(@Nullable String v) { s.setFoundDate(v); return this; }
        public Builder foundLocation(@Nullable String v) { s.setFoundLocation(v); return this; }
        public Builder veterinary(@Nullable String v) { s.setVeterinary(v); return this; }
        public Builder imageUrl(@Nullable String v) { s.setImageUrl(v); return this; }
        public Builder imageResId(@Nullable String v) { s.setImageResId(v); return this; }
        public Builder finderName(@Nullable String v) { s.setFinderName(v); return this; }
        public Builder timestamp(long v) { s.setTimestamp(v); return this; }
        public Builder ts(@Nullable Long v) { s.setTs(v); return this; }
        public Builder iyeId(@Nullable String v) { s.setIyeId(v); return this; }
        public Builder status(@Nullable String v) { s.setStatus(v); return this; }
        public Builder location(@Nullable GeoPoint v) { s.setLocation(v); return this; }
        public Builder geohash(@Nullable String v) { s.setGeohash(v); return this; }
        public Builder adminPath(@Nullable String v) { s.setAdminPath(v); return this; }
        public Soul build() { return s; }
    }

    // ---------- DTO yardımcıları ----------
    /** Firestore / CF için minimal public DTO (kişisel verisiz). */
    @NonNull
    public Map<String, Object> toPublicMap() {
        Map<String, Object> m = new HashMap<>();
        if (id != null) m.put("id", id);
        if (name != null) m.put("name", name);
        if (species != null) m.put("species", species);
        if (breed != null) m.put("breed", breed);
        if (health != null) m.put("health", health);
        if (foundDate != null) m.put("foundDate", foundDate);
        if (foundLocation != null) m.put("foundLocation", foundLocation);
        if (imageUrl != null) m.put("imageUrl", imageUrl);
        if (status != null) m.put("status", status);
        if (geohash != null) m.put("geohash", geohash);
        if (ts != null) m.put("ts", ts);
        if (location != null) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("lat", location.getLatitude());
            loc.put("lng", location.getLongitude());
            m.put("location", loc);
        }
        if (adminPath != null) m.put("adminPath", adminPath);
        if (iyeId != null) m.put("iyeId", iyeId);
        return m;
    }

    // ---------- JSON → Model yardımcıları ----------
    /** Sunucu/CF JSON’unu esnek biçimde çözer. imageResId veya imageUrl anahtarlarını destekler. */
    @NonNull
    public static Soul fromJson(@Nullable JSONObject o) {
        Soul s = new Soul();
        if (o == null) return s;

        s.id = o.optString("id", null);
        s.name = o.optString("name", null);
        s.species = o.optString("species", null);
        s.breed = o.optString("breed", null);
        s.age = o.optString("age", null);
        s.health = o.optString("health", null);
        s.foundDate = o.optString("foundDate", null);
        s.foundLocation = o.optString("foundLocation", null);
        s.veterinary = o.optString("veterinary", null);
        s.finderName = o.optString("finderName", null);

        // Görsel: imageResId > imageUrl
        String img = o.optString("imageResId", null);
        if (img == null || img.isEmpty()) img = o.optString("imageUrl", null);
        s.imageUrl = img;

        // Zaman
        if (o.has("timestamp")) s.timestamp = safeLong(o, "timestamp", 0L);
        if (o.has("ts")) s.ts = o.isNull("ts") ? null : safeLong(o, "ts", 0L);

        // İlişki/durum
        s.iyeId = o.optString("iyeId", null);
        s.status = o.optString("status", null);

        // Konum: location{lat,lng} veya kökte lat/lng ya da _lat/_long
        Double lat = null, lng = null;
        if (o.has("location") && !o.isNull("location")) {
            JSONObject loc = o.optJSONObject("location");
            if (loc != null) {
                if (loc.has("lat"))  lat = safeDouble(loc, "lat");
                if (loc.has("lng"))  lng = safeDouble(loc, "lng");
                if (lat == null && loc.has("_lat"))  lat = safeDouble(loc, "_lat");
                if (lng == null && loc.has("_long")) lng = safeDouble(loc, "_long");
            }
        }
        if (lat == null && o.has("lat"))   lat = safeDouble(o, "lat");
        if (lng == null && o.has("lng"))   lng = safeDouble(o, "lng");
        if (lat == null && o.has("_lat"))  lat = safeDouble(o, "_lat");
        if (lng == null && o.has("_long")) lng = safeDouble(o, "_long");

        if (lat != null && lng != null) s.location = new GeoPoint(lat, lng);

        s.geohash = o.optString("geohash", null);
        s.adminPath = o.optString("adminPath", null);

        return s;
    }

    // ---------- equals/hashCode (id varsa ona göre) ----------
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Soul)) return false;
        Soul soul = (Soul) o;
        if (id != null && soul.id != null) {
            return id.equals(soul.id);
        }
        return Objects.equals(name, soul.name)
                && Objects.equals(species, soul.species)
                && timestamp == soul.timestamp;
    }

    @Override
    public int hashCode() {
        if (id != null) return id.hashCode();
        return Objects.hash(name, species, timestamp);
    }

    // ---------- küçük yardımcılar ----------
    @NonNull
    private static String safe(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static void writeNullableString(@NonNull Parcel dest, @Nullable String s) {
        if (s == null) {
            dest.writeByte((byte) 0);
        } else {
            dest.writeByte((byte) 1);
            dest.writeString(s);
        }
    }

    @Nullable
    private static String readNullableString(@NonNull Parcel in) {
        return (in.readByte() == 0) ? null : in.readString();
    }

    @Nullable
    private static Double safeDouble(@NonNull JSONObject o, @NonNull String key) {
        try { return o.isNull(key) ? null : o.getDouble(key); }
        catch (Exception ignore) { return null; }
    }

    private static long safeLong(@NonNull JSONObject o, @NonNull String key, long def) {
        try { return o.isNull(key) ? def : o.getLong(key); }
        catch (Exception ignore) { return def; }
    }
}
