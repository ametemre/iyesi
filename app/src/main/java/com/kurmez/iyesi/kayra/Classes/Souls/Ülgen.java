// UlgenProfile.java
package com.kurmez.iyesi.kayra.Classes.Souls;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;

import androidx.annotation.Keep;

/**
 * Ülgen (Admin-Veterinary) rolü için Profile alt sınıfı.
 * - Sadece kendi cihazından "İye" rolü atayabilir
 * - İlan silme/düzenleme/onaylama yetkileri vardır
 * - İdari kapsam (adminPath) ve eylem yarıçapı (coverageKm) tanımlıdır
 *
 * Not: Profile'a dokunmayız. Bu sınıf, ek davranışlar sağlar.
 */
@Keep
public class Ülgen extends Iye {

    /** Ülgen eylemlerini temsil eden izinler. */
    public enum Permission {
        ASSIGN_IYE,      // "İye" rolü atama
        APPROVE_LISTING, // İlan onaylama
        EDIT_LISTING,    // İlan düzenleme
        DELETE_LISTING   // İlan silme
    }

    /** Ülgen’in “kendi cihazından” şartını doğrulamak için kayıtlı cihaz kimliği. */
    private String ownerDeviceId;

    /** İdari kapsam kökü (örn. "TR/ADANA" veya "TR/ADANA/SEYHAN"). */
    private String adminPath;

    /** Varsayılan eylem yarıçapı (km). */
    private double actionRadiusKm = 3.0;

    /** Ülgen izinleri (varsayılan: tümü). */
    private transient EnumSet<Permission> permissions = EnumSet.of(
            Permission.ASSIGN_IYE,
            Permission.APPROVE_LISTING,
            Permission.EDIT_LISTING,
            Permission.DELETE_LISTING
    );


    // --- Yapıcılar (Profile'a dokunmadan, no-arg super() varsayımıyla) ---

    public Ülgen() {
        super();
    }

    public Ülgen(String ownerDeviceId, String adminPath, double actionRadiusKm) {
        super();
        this.ownerDeviceId = ownerDeviceId;
        this.adminPath = adminPath;
        if (actionRadiusKm > 0) this.actionRadiusKm = actionRadiusKm;
    }

    // --- Rol/kimlik yardımcıları ---

    /** Bu profil Ülgen mi? (role alanı "Ülgen"/"Ulgen"/"vet-admin" gibi varyantlara bakar) */
    public boolean isUlgen() {
        String r = getRole(); // Profile.getRole() beklenir
        return r != null && (
                "Ülgen".equalsIgnoreCase(r) ||
                        "Ulgen".equalsIgnoreCase(r) ||
                        "vet-admin".equalsIgnoreCase(r)
        );
    }


    /** Ülgen yalnızca kendi cihazından işlem yapabilir. */
    public boolean deviceIsOwner(String currentDeviceId) {
        return ownerDeviceId != null && ownerDeviceId.equals(currentDeviceId);
    }

    // --- İzinler ---

    public boolean canAssignIye()      { return permissions.contains(Permission.ASSIGN_IYE); }
    public boolean canApproveListing() { return permissions.contains(Permission.APPROVE_LISTING); }
    public boolean canEditListing()    { return permissions.contains(Permission.EDIT_LISTING); }
    public boolean canDeleteListing()  { return permissions.contains(Permission.DELETE_LISTING); }

    public EnumSet<Permission> getPermissions() {
        return EnumSet.copyOf(permissions);
    }

    public void setPermissions(EnumSet<Permission> permissions) {
        if (permissions != null && !permissions.isEmpty()) {
            this.permissions = EnumSet.copyOf(permissions);
        }
    }

    // --- Kapsam ve bildirim konuları ---

    /** Ülgen’in idari kapsam kökü (örn. "TR/ADANA"). */
    public String getAdminPath() { return adminPath; }
    public void setAdminPath(String adminPath) { this.adminPath = adminPath; }

    /** Eylem yarıçapı (km). */
    public double getActionRadiusKm() { return actionRadiusKm; }
    public void setActionRadiusKm(double km) { if (km > 0) this.actionRadiusKm = km; }

    /** Bildirim topic önerileri (örnek): rol ve adminPath bazlı. */
    public java.util.List<String> notificationTopics() {
        java.util.List<String> topics = new java.util.ArrayList<>();
        topics.add("role.ulgen");
        if (adminPath != null) {
            String t = adminPath.trim().replace('/', '.').replace(' ', '_');
            // İsteğe bağlı: sadece [A-Za-z0-9-_.~%] kalsın
            t = t.replaceAll("[^A-Za-z0-9._-]", "");
            if (!t.isEmpty()) topics.add(t); // Örn: "TR.ADANA.SEYHAN"
        }
        return topics;
    }
    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT); // Ü -> U
    }
    public boolean isAllowed(Permission p) {
        return permissions != null && permissions.contains(p) && deviceIsOwner(ownerDeviceId);
    }


    /** Kayıtlı cihaz kimliği (owner) */
    public String getOwnerDeviceId() { return ownerDeviceId; }
    public void setOwnerDeviceId(String ownerDeviceId) { this.ownerDeviceId = ownerDeviceId; }
}
