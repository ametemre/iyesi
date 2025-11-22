// Baksi.java
package com.kurmez.iyesi.kayra.Classes.Souls;

import java.io.Serializable;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

import androidx.annotation.Keep;

/**
 * Baksi (kadim Türkçe: şifa/iyileştirme ile ilişkilenen figür)
 * - Modern uygulamada: sahadaki veteriner / sağlık görevlisi rolü.
 * - Kısıtlı idari haklar (ilan onaylama, düzenleme) ile gelir.
 * - adminPath ve eylem yarıçapı (km) tanımlıdır.
 *
 * Not: Profile / Iye sınıfına dokunmadan ek davranışlar sağlar.
 */
@Keep
public class Baksi extends Iye implements Serializable {

    private static final long serialVersionUID = 1L;

    // Veteriner / klinik bilgileri
    private String licenseId;        // Veteriner ruhsat numarası
    private String chamber;          // Üye olduğu veteriner odası
    private boolean duty;            // Nöbetçi mi?
    private boolean homeVisit;       // Evde bakım hizmeti var mı?
    private boolean hasClinic;       // Fiziksel klinik var mı?
    private String clinicName;       // Klinik adı
    private String clinicAddress;    // Klinik adresi
    private String workingHours;     // Çalışma saatleri (serbest format)
    private String[] services;       // Sunulan hizmetler (aşı, cerrahi, acil vb.)
    private String[] certifications; // Sertifikalar, belgeler

    // Doğrulama / onay
    private boolean verified;        // Ülgen veya Tengri onayı
    private String verificationNote; // Onay notu / moderatör açıklaması
    private long verifiedAt;         // Onay zamanı (epoch millis)

    // Görsel / harita
    private String imageUrl;
    private int markerColor;         // Haritada görsel ayrım (opsiyonel renk kodu)

    // Yetki & sahiplik
    public enum Permission {
        APPROVE_LISTING, // İlan onaylama
        EDIT_LISTING     // İlan düzenleme
        // Silme/rol atama yetkileri verilmez
    }

    /** Sahip cihaz id (opsiyonel). */
    private String ownerDeviceId;

    /** İdari kapsam kökü (örn. "TR/ADANA"). */
    private String adminPath;

    /** Varsayılan eylem yarıçapı (km). */
    private double actionRadiusKm = 1.5;

    /** İzinler (varsayılan: onay ve düzenleme). transient — serileştirilmez. */
    private transient EnumSet<Permission> permissions;

    // --- Yapıcılar ----------------------------------------------------------------

    public Baksi() {
        super();
        ensurePermissionsInit();
    }

    public Baksi(String ownerDeviceId, String adminPath, double actionRadiusKm) {
        super();
        this.ownerDeviceId = ownerDeviceId;
        this.adminPath = adminPath;
        if (actionRadiusKm > 0) this.actionRadiusKm = actionRadiusKm;
        ensurePermissionsInit();
    }

    // Tam parametreli yardımcı constructor (opsiyonel kullanım)
// Baksi.java'daki constructor'ı GÜNCELLE:
    public Baksi(String uid,
                 String licenseId,
                 String chamber,
                 boolean duty,
                 boolean homeVisit,
                 boolean hasClinic,
                 String clinicName,
                 String clinicAddress,
                 String workingHours,
                 String[] services,
                 String[] certifications,
                 boolean verified,
                 String verificationNote,
                 long verifiedAt,
                 String imageUrl,
                 int markerColor,
                 String ownerDeviceId,
                 String adminPath,
                 double actionRadiusKm) {
        super(uid, null, null, null, null, null, null); // Gerekli tüm parametreleri ver
        this.licenseId = licenseId;
        this.chamber = chamber;
        this.duty = duty;
        this.homeVisit = homeVisit;
        this.hasClinic = hasClinic;
        this.clinicName = clinicName;
        this.clinicAddress = clinicAddress;
        this.workingHours = workingHours;
        this.services = services;
        this.certifications = certifications;
        this.verified = verified;
        this.verificationNote = verificationNote;
        this.verifiedAt = verifiedAt;
        this.imageUrl = imageUrl;
        this.markerColor = markerColor;
        this.ownerDeviceId = ownerDeviceId;
        this.adminPath = adminPath;
        if (actionRadiusKm > 0) this.actionRadiusKm = actionRadiusKm;
        ensurePermissionsInit();
    }

    // --- Initialization helpers ----------------------------------------------------

    private void ensurePermissionsInit() {
        if (this.permissions == null) {
            this.permissions = EnumSet.of(
                    Permission.APPROVE_LISTING,
                    Permission.EDIT_LISTING
            );
        }
    }

    // --- Rol yardımcıları --------------------------------------------------------

    /**
     * Bu profil Baksi mi?
     * role alanında "Baksı", "Baksi", "vet" gibi varyantlara bakar.
     */
    public boolean isBaksi() {
        String r = getRole(); // Iye sınıfından gelmeli
        if (r == null) return false;
        String f = fold(r);
        return f.equals("baksi") || f.equals("baksı") || f.equals("vet") || f.equals("veteriner");
    }

    /** Baksi yalnızca kendi cihazından belirli işlemleri yapabilir (opsiyonel). */
    public boolean deviceIsOwner(String currentDeviceId) {
        return ownerDeviceId != null && ownerDeviceId.equals(currentDeviceId);
    }
    // Baksi sınıfına location getter ekleyin
    public Iye.Location getLocation() {
        // Baksi'nin konum bilgisini döndür
        // Eğer haritada gösterilecek konum farklıysa, bu metodu güncelleyin
        return super.getLocation(); // Iye sınıfından miras alıyor
    }
    // --- İzin yardımcıları -------------------------------------------------------

    public boolean canApproveListing() {
        ensurePermissionsInit();
        return permissions.contains(Permission.APPROVE_LISTING);
    }

    public boolean canEditListing() {
        ensurePermissionsInit();
        return permissions.contains(Permission.EDIT_LISTING);
    }

    public EnumSet<Permission> getPermissions() {
        ensurePermissionsInit();
        return EnumSet.copyOf(permissions);
    }

    public void setPermissions(EnumSet<Permission> permissions) {
        if (permissions != null && !permissions.isEmpty()) {
            this.permissions = EnumSet.copyOf(permissions);
        } else {
            ensurePermissionsInit();
        }
    }

    public boolean isAllowed(Permission p) {
        ensurePermissionsInit();
        if (permissions == null || !permissions.contains(p)) return false;
        // Eğer cihaz sahibi şartı istenirse buraya eklenebilir:
        // return deviceIsOwner(currentDeviceId) && permissions.contains(p);
        return true;
    }

    // --- Admin & action radius ---------------------------------------------------

    public String getAdminPath() { return adminPath; }
    public void setAdminPath(String adminPath) { this.adminPath = adminPath; }

    public double getActionRadiusKm() { return actionRadiusKm; }
    public void setActionRadiusKm(double km) { if (km > 0) this.actionRadiusKm = km; }

    // --- Notification topics -----------------------------------------------------

    /**
     * Bildirim topic önerileri: rol ve adminPath bazlı.
     * Örnek: "role.baksi", "TR.ADANA"
     */
    public List<String> notificationTopics() {
        List<String> topics = new ArrayList<>();
        topics.add("role.baksi");
        if (adminPath != null && !adminPath.trim().isEmpty()) {
            String t = adminPath.trim().replace('/', '.').replace(' ', '_');
            t = t.replaceAll("[^A-Za-z0-9._-]", "");
            if (!t.isEmpty()) topics.add(t);
        }
        return topics;
    }

    // --- Utility: fold -----------------------------------------------------------

    /** Basit fold: aksanları kaldır, küçük harfe çevir. */
    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // --- Getters / Setters ------------------------------------------------------

    public String getLicenseId() { return licenseId; }
    public void setLicenseId(String licenseId) { this.licenseId = licenseId; }

    public String getChamber() { return chamber; }
    public void setChamber(String chamber) { this.chamber = chamber; }

    public boolean isDuty() { return duty; }
    public void setDuty(boolean duty) { this.duty = duty; }

    public boolean isHomeVisit() { return homeVisit; }
    public void setHomeVisit(boolean homeVisit) { this.homeVisit = homeVisit; }

    public boolean isHasClinic() { return hasClinic; }
    public void setHasClinic(boolean hasClinic) { this.hasClinic = hasClinic; }

    public String getClinicName() { return clinicName; }
    public void setClinicName(String clinicName) { this.clinicName = clinicName; }

    public String getClinicAddress() { return clinicAddress; }
    public void setClinicAddress(String clinicAddress) { this.clinicAddress = clinicAddress; }

    public String getWorkingHours() { return workingHours; }
    public void setWorkingHours(String workingHours) { this.workingHours = workingHours; }

    public String[] getServices() { return services; }
    public void setServices(String[] services) { this.services = services; }

    public String[] getCertifications() { return certifications; }
    public void setCertifications(String[] certifications) { this.certifications = certifications; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public String getVerificationNote() { return verificationNote; }
    public void setVerificationNote(String verificationNote) { this.verificationNote = verificationNote; }

    public long getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(long verifiedAt) { this.verifiedAt = verifiedAt; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public int getMarkerColor() { return markerColor; }
    public void setMarkerColor(int markerColor) { this.markerColor = markerColor; }

    public String getOwnerDeviceId() { return ownerDeviceId; }
    public void setOwnerDeviceId(String ownerDeviceId) { this.ownerDeviceId = ownerDeviceId; }

    public double getActionRadius() { return actionRadiusKm; }

    // --- toString ---------------------------------------------------------------

    @Override
    public String toString() {
        return "Baksi{" +
                "uid=" + getUid() +
                ", clinicName='" + clinicName + '\'' +
                ", licenseId='" + licenseId + '\'' +
                ", chamber='" + chamber + '\'' +
                ", duty=" + duty +
                ", homeVisit=" + homeVisit +
                ", hasClinic=" + hasClinic +
                ", verified=" + verified +
                ", adminPath='" + adminPath + '\'' +
                ", actionRadiusKm=" + actionRadiusKm +
                '}';
    }
}
