package com.kurmez.iyesi.kayra.Classes.data;

import java.util.Map;

/**
 * Profile model class representing a user via CustomClaims.
 */
public class Iye {
    private String uid;        // DevID’den türetilip backend tarafından claims’e yazılır
    private String username;
    private String email;
    private Location location; // ⭐ DEĞİŞTİ: String -> Location object
    private String phone;
    private String role;
    private String avatarUrl;  // opsiyonel
    // Location inner class
    public static class Location {
        private String address;
        private Double lat;
        private Double lng;

        public Location() {}

        public Location(String address, Double lat, Double lng) {
            this.address = address;
            this.lat = lat;
            this.lng = lng;
        }

        // getters & setters
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public Double getLat() { return lat; }
        public void setLat(Double lat) { this.lat = lat; }
        public Double getLng() { return lng; }
        public void setLng(Double lng) { this.lng = lng; }
    }
    public Iye() { }

    // Iye.java'da constructor'ı güncelle
    public Iye(String uid, String username, String email,
               Iye.Location location, String phone, String role, String avatarUrl) {
        this.uid = uid;
        this.username = username;
        this.email = email;
        this.location = location;  // ⭐ DEĞİŞTİ: String -> Iye.Location
        this.phone = phone;
        this.role = role;
        this.avatarUrl = avatarUrl;
    }

    // Factory: CustomClaims’ten Profile üret
// Iye.java'daki fromClaims metodunu da güncelle
    public static Iye fromClaims(Map<String,Object> claims) {
        if (claims == null) return null;

        String uid = (String) claims.get("uid");
        String username = (String) claims.get("username");
        String email = (String) claims.get("email");
        String phone = (String) claims.get("phone");
        String role = (String) claims.get("role");
        String avatarUrl = (String) claims.get("avatarUrl");

        // ⭐ YENİ: Location parsing
        Object locationObj = claims.get("location");
        Iye.Location location = parseLocationFromClaims(locationObj);

        return new Iye(uid, username, email, location, phone, role, avatarUrl);
    }

    private static Iye.Location parseLocationFromClaims(Object locationObj) {
        if (locationObj == null) {
            return new Iye.Location("", null, null);
        }

        if (locationObj instanceof String) {
            return new Iye.Location((String) locationObj, null, null);
        }

        if (locationObj instanceof Map) {
            Map<?, ?> locMap = (Map<?, ?>) locationObj;
            String address = (String) locMap.get("address");
            Double lat = null;
            Double lng = null;

            Object latObj = locMap.get("lat");
            Object lngObj = locMap.get("lng");

            if (latObj instanceof Number) lat = ((Number) latObj).doubleValue();
            else if (latObj instanceof String) {
                try { lat = Double.parseDouble((String) latObj); } catch (NumberFormatException ignored) {}
            }

            if (lngObj instanceof Number) lng = ((Number) lngObj).doubleValue();
            else if (lngObj instanceof String) {
                try { lng = Double.parseDouble((String) lngObj); } catch (NumberFormatException ignored) {}
            }

            return new Iye.Location(address != null ? address : "", lat, lng);
        }

        return new Iye.Location("", null, null);
    }

    // getters & setters
    public void setLocation(String address) {
        this.location = new Location(address, null, null);
    }
    // Getter & Setter
    public String getUid() { return uid; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public Location getLocation() { return location; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
    public String getAvatarUrl() { return avatarUrl; }

    public void setUsername(String username) { this.username = username; }
    public void setEmail(String email) { this.email = email; }
    public void setLocation(Location location) { this.location = location; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public final class ClaimsKeys {
        public static final String UID        = "uid";
        public static final String ROLE       = "role";
        public static final String USERNAME   = "username";
        public static final String EMAIL      = "email";
        public static final String PHONE      = "phone";
        public static final String LOCATION   = "location";

        // avatar URL yerine, CF’in yazdığı kısa anahtar(lar):
        public static final String AVATAR_KEY = "avatarKey";
        public static final String AVATAR_REV = "avatarRev"; // opsiyonel cache-bust

    }
}

