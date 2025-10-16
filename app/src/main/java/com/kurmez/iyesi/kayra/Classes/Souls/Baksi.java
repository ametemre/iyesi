// Baksi.java
package com.kurmez.iyesi.kayra.Classes.Souls;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;

import androidx.annotation.Keep;

/**
 * Baksi (kadim Türkçe karşılığı: şifa/iyileştirme ile ilişkilenen figür)
 * - Modern uygulamada: sahadaki veteriner / sağlık görevlisi rolü.
 * - Kısıtlı idari haklar (ilan onaylama, düzenleme) ile gelir.
 * - adminPath ve eylem yarıçapı (km) tanımlıdır.
 *
 * Not: Profile sınıfına dokunmadan ek davranışlar sağlar.
 */
@Keep
public class Baksi extends Iye {

    /** Baksi eylemlerini temsil eden izinler (sınırlı). */
    public enum Permission {
        APPROVE_LISTING, // İlan onaylama
        EDIT_LISTING     // İlan düzenleme
        // Silme veya rol atama yetkisi yok
    }

    /** Sahip cihaz id (opsiyonel). */
    private String ownerDeviceId;

    /** İdari kapsam kökü (örn. "TR/ADANA"). */
    private String adminPath;

    /** Varsayılan eylem yarıçapı (km). Baksi daha yerel çalışır. */
    private double actionRadiusKm = 1.5;

    /** İzinler (varsayılan: onay ve düzenleme). */
    private transient EnumSet<Permission> permissions = EnumSet.of(
            Permission.APPROVE_LISTING,
            Permission.EDIT_LISTING
    );

    // --- Yapıcılar ---
    public Baksi() { super(); }

    public Baksi(String ownerDeviceId, String adminPath, double actionRadiusKm) {
        super();
        this.ownerDeviceId = ownerDeviceId;
        this.adminPath = adminPath;
        if (actionRadiusKm > 0) this.actionRadiusKm = actionRadiusKm;
    }

    // --- Rol yardımcıları ---

    /**
     * Bu profil Baksi mi?
     * role alanında "Baksı", "Baksi", "vet" gibi varyantlara bakar.
     */
    public boolean isBaksi() {
        String r = getRole();
        if (r == null) return false;
        String f = fold(r);
        return f.equals("baksi") || f.equals("baksı") || f.equals("vet") || f.equals("veteriner");
    }

    /** Baksi yalnızca kendi cihazından belirli işlemleri yapabilir (opsiyonel). */
    public boolean deviceIsOwner(String currentDeviceId) {
        return ownerDeviceId != null && ownerDeviceId.equals(currentDeviceId);
    }

    // --- İzin yardımcıları ---

    public boolean canApproveListing() { return permissions.contains(Permission.APPROVE_LISTING); }
    public boolean canEditListing()    { return permissions.contains(Permission.EDIT_LISTING); }

    public EnumSet<Permission> getPermissions() {
        return permissions == null ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions);
    }

    public void setPermissions(EnumSet<Permission> permissions) {
        if (permissions != null && !permissions.isEmpty()) {
            this.permissions = EnumSet.copyOf(permissions);
        }
    }

    // --- Kapsam ve bildirim konuları ---

    public String getAdminPath() { return adminPath; }
    public void setAdminPath(String adminPath) { this.adminPath = adminPath; }

    public double getActionRadiusKm() { return actionRadiusKm; }
    public void setActionRadiusKm(double km) { if (km > 0) this.actionRadiusKm = km; }

    /**
     * Bildirim topic önerileri: rol ve adminPath bazlı.
     * Örnek: "role.baksi", "TR.ADANA"
     */
    public java.util.List<String> notificationTopics() {
        java.util.List<String> topics = new java.util.ArrayList<>();
        topics.add("role.baksi");
        if (adminPath != null) {
            String t = adminPath.trim().replace('/', '.').replace(' ', '_');
            t = t.replaceAll("[^A-Za-z0-9._-]", "");
            if (!t.isEmpty()) topics.add(t);
        }
        return topics;
    }

    /** Basit fold: aksanları kaldır, küçük harfe çevir. */
    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    /**
     * Kısıtlı izin kontrolü.
     * Burada cihaz ownership gereksinimini de tercih edersen aktif edebilirsin.
     */
    public boolean isAllowed(Permission p) {
        if (permissions == null || !permissions.contains(p)) return false;
        // Örnek: cihaz sahibi olma şartını eklemek istersen:
        // return deviceIsOwner(ownerDeviceId) && permissions.contains(p);
        return true;
    }

    // --- owner device id ---
    public String getOwnerDeviceId() { return ownerDeviceId; }
    public void setOwnerDeviceId(String ownerDeviceId) { this.ownerDeviceId = ownerDeviceId; }
}
