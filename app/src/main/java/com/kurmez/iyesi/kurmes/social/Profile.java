package com.kurmez.iyesi.kurmes.social;

/**
 * Profile model class representing a user in Firestore.
 */
public class Profile {
    private String uid;         // Composite UID: FirebaseUID_deviceID
    private String username;
    private String email;
    private String location;    // İl/İlçe/Mahalle
    private String phone;
    private String role;

    /**
     * Public no-args constructor required for Firestore deserialization
     */
    public Profile() {
    }

    /**
     * Full constructor for creating a Profile instance.
     */
    public Profile(String uid, String username, String email,
                   String location, String phone, String role) {
        this.uid = uid;
        this.username = username;
        this.email = email;
        this.location = location;
        this.phone = phone;
        this.role = role;
    }

    // Getter & Setter methods
    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
