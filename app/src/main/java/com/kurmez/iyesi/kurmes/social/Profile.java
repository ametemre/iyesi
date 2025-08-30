package com.kurmez.iyesi.kurmes.social;

import java.util.Map;

/**
 * Profile model class representing a user via CustomClaims.
 */
public class Profile {
    private String uid;        // DevID’den türetilip backend tarafından claims’e yazılır
    private String username;
    private String email;
    private String location;
    private String phone;
    private String role;
    private String avatarUrl;  // opsiyonel

    public Profile() { }

    public Profile(String uid, String username, String email,
                   String location, String phone, String role, String avatarUrl) {
        this.uid = uid;
        this.username = username;
        this.email = email;
        this.location = location;
        this.phone = phone;
        this.role = role;
        this.avatarUrl = avatarUrl;
    }

    // Factory: CustomClaims’ten Profile üret
    public static Profile fromClaims(Map<String,Object> claims) {
        if (claims == null) return null;
        return new Profile(
                (String) claims.get("uid"),
                (String) claims.get("username"),
                (String) claims.get("email"),
                (String) claims.get("location"),
                (String) claims.get("phone"),
                (String) claims.get("role"),
                (String) claims.get("avatarUrl")
        );
    }

    // Getter & Setter
    public String getUid() { return uid; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getLocation() { return location; }
    public String getPhone() { return phone; }
    public String getRole() { return role; }
    public String getAvatarUrl() { return avatarUrl; }

    public void setUsername(String username) { this.username = username; }
    public void setEmail(String email) { this.email = email; }
    public void setLocation(String location) { this.location = location; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
}
final class ClaimsKeys {
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
