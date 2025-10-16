package com.kurmez.iyesi.kayra.Classes.Nodes;// MarkerBase.java
// Firestore POJO, MockData.py şemasına uyumlu.
// package com.kurmez.iyesi.model;  // <-- paket adını istersen ayarla

import java.util.Map;
import java.util.HashMap;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.IgnoreExtraProperties;

@IgnoreExtraProperties
public class Node {

    @DocumentId
    private String id;               // Firestore doc id (otomatik)

    private String type;             // "Besleme" | "Yuva" | "Barınak" | "Görev"
    private Double lat;
    private Double lng;
    private String geohash;

    // Genel alanlar
    private String species;          // (Besleme/Yuva/Barınak için ops.)
    private String category;         // örn "Mama" (Besleme)
    private String status;           // "active" vb.
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String note;

    // Denormalize alanlar
    private Integer soulsCount;      // create_souls sonrası update edilir

    // last* denormalize alanları
    private String lastFedBy;
    private Long   lastFedAt;        // ms
    private Double lastFedAmount;

    private String lastCleanedBy;
    private Long   lastCleanedAt;    // ms

    private String lastMaintainedBy;
    private Long   lastMaintainedAt; // ms

    private String lastVisitedBy;
    private Long   lastVisitedAt;    // ms

    // Anahtarlar ve konum
    private Keys keys;               // denormalize key alanları
    private Location location;       // görsel/insani okunur adres parçalaması
    private String adminPath;        // "TR/İstanbul/Kadıköy/Moda/Bahariye Cd."

    // Tür-özel alanlar için esnek "attrs" haritası
    private Map<String, Object> attrs = new HashMap<>();

    public Node() {}

    // ========= Yardımcılar =========
    public NodeType getTypeEnum() { return NodeType.from(type); }
    public void setTypeEnum(NodeType t) { this.type = (t == null ? null : t.wire()); }

    protected Map<String, Object> safeAttrs() {
        if (attrs == null) attrs = new HashMap<>();
        return attrs;
    }

    // ========= Getters / Setters =========
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public String getGeohash() { return geohash; }
    public void setGeohash(String geohash) { this.geohash = geohash; }

    public String getSpecies() { return species; }
    public void setSpecies(String species) { this.species = species; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Integer getSoulsCount() { return soulsCount; }
    public void setSoulsCount(Integer soulsCount) { this.soulsCount = soulsCount; }

    public String getLastFedBy() { return lastFedBy; }
    public void setLastFedBy(String lastFedBy) { this.lastFedBy = lastFedBy; }
    public Long getLastFedAt() { return lastFedAt; }
    public void setLastFedAt(Long lastFedAt) { this.lastFedAt = lastFedAt; }
    public Double getLastFedAmount() { return lastFedAmount; }
    public void setLastFedAmount(Double lastFedAmount) { this.lastFedAmount = lastFedAmount; }

    public String getLastCleanedBy() { return lastCleanedBy; }
    public void setLastCleanedBy(String lastCleanedBy) { this.lastCleanedBy = lastCleanedBy; }
    public Long getLastCleanedAt() { return lastCleanedAt; }
    public void setLastCleanedAt(Long lastCleanedAt) { this.lastCleanedAt = lastCleanedAt; }

    public String getLastMaintainedBy() { return lastMaintainedBy; }
    public void setLastMaintainedBy(String lastMaintainedBy) { this.lastMaintainedBy = lastMaintainedBy; }
    public Long getLastMaintainedAt() { return lastMaintainedAt; }
    public void setLastMaintainedAt(Long lastMaintainedAt) { this.lastMaintainedAt = lastMaintainedAt; }

    public String getLastVisitedBy() { return lastVisitedBy; }
    public void setLastVisitedBy(String lastVisitedBy) { this.lastVisitedBy = lastVisitedBy; }
    public Long getLastVisitedAt() { return lastVisitedAt; }
    public void setLastVisitedAt(Long lastVisitedAt) { this.lastVisitedAt = lastVisitedAt; }

    public Keys getKeys() { return keys; }
    public void setKeys(Keys keys) { this.keys = keys; }

    public Location getLocation() { return location; }
    public void setLocation(Location location) { this.location = location; }

    public String getAdminPath() { return adminPath; }
    public void setAdminPath(String adminPath) { this.adminPath = adminPath; }

    public Map<String, Object> getAttrs() { return attrs; }
    public void setAttrs(Map<String, Object> attrs) { this.attrs = attrs; }

    // ===== Nested Types =====
    @IgnoreExtraProperties
    public static class Keys {
        private String country;
        private String city;
        private String province;
        private String district;
        private String neighbourhood;
        private String street;

        public Keys() {}

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getProvince() { return province; }
        public void setProvince(String province) { this.province = province; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getNeighbourhood() { return neighbourhood; }
        public void setNeighbourhood(String neighbourhood) { this.neighbourhood = neighbourhood; }
        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
    }

    @IgnoreExtraProperties
    public static class Location {
        private String country;
        private String countryCode;
        private String city;
        private String province;
        private String cityCode;
        private String district;
        private String neighbourhood;
        private String street;

        public Location() {}

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getProvince() { return province; }
        public void setProvince(String province) { this.province = province; }
        public String getCityCode() { return cityCode; }
        public void setCityCode(String cityCode) { this.cityCode = cityCode; }
        public String getDistrict() { return district; }
        public void setDistrict(String district) { this.district = district; }
        public String getNeighbourhood() { return neighbourhood; }
        public void setNeighbourhood(String neighbourhood) { this.neighbourhood = neighbourhood; }
        public String getStreet() { return street; }
        public void setStreet(String street) { this.street = street; }
    }
}