package com.kurmez.iyesi.kayra.Classes.Nodes;// ShelterMarker.java
// Barınak (Shelter) Node sınıfı - Barınak raporlarına göre tasarlandı
// package com.kurmez.iyesi.model;

/**
 * ShelterNode - Barınak bilgilerini tutan Node alt sınıfı
 * 
 * Raporlardan çıkarılan alanlar:
 * - Temel bilgiler (ad, adres, sorumlu, denetleyici, iletişim)
 * - Veteriner bilgileri (24 saat, ameliyathane, röntgen)
 * - Yerleşim bilgileri (zemin, alan, çitler, kulübeler)
 * - Bölme bilgileri (karantina, yavrulu anne, küçük/iri cins, uyuz, ameliyatlı)
 * - Beslenme bilgileri (aralık, miktar, saat)
 * - İdari birimler (bekçi, gece kalan, idari alan)
 * - Belediye iş birlikleri
 */
public class ShelterNode extends Node {
    public ShelterNode() { setTypeEnum(NodeType.SHELTER); }

    // ========= Temel Bilgiler =========
    
    /**
     * Barınak adı (örn: "Ataşehir/Kadıköy Belediyesi Hayvan Barınağı")
     */
    public String getShelterName() {
        Object v = getAttrs() != null ? getAttrs().get("shelterName") : null;
        return v == null ? null : v.toString();
    }
    public void setShelterName(String name) { safeAttrs().put("shelterName", name); }

    /**
     * Barınak adresi (örn: "Çamlık, 34774 Ümraniye/İstanbul")
     */
    public String getAddress() {
        Object v = getAttrs() != null ? getAttrs().get("address") : null;
        return v == null ? null : v.toString();
    }
    public void setAddress(String address) { safeAttrs().put("address", address); }

    /**
     * Barınak sorumlusu (örn: "Atakan Kumbasar")
     */
    public String getManager() {
        Object v = getAttrs() != null ? getAttrs().get("manager") : null;
        return v == null ? null : v.toString();
    }
    public void setManager(String manager) { safeAttrs().put("manager", manager); }

    /**
     * Denetleyici bilgileri (virgülle ayrılmış, örn: "Zeynep Beyza Çavdar" veya "Zeynep Çavdar,Begüm Ayas,Özgür Kes")
     */
    public String getSupervisors() {
        Object v = getAttrs() != null ? getAttrs().get("supervisors") : null;
        return v == null ? null : v.toString();
    }
    public void setSupervisors(String supervisors) { safeAttrs().put("supervisors", supervisors); }

    /**
     * İletişim bilgileri (telefon, örn: "0212 453 7375")
     */
    public String getContact() {
        Object v = getAttrs() != null ? getAttrs().get("contact") : null;
        return v == null ? null : v.toString();
    }
    public void setContact(String contact) { safeAttrs().put("contact", contact); }

    // ========= Veteriner Bilgileri =========

    /**
     * 24 saat veteriner var mı? (boolean)
     */
    public Boolean has24HourVet() {
        Object v = getAttrs() != null ? getAttrs().get("has24HourVet") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHas24HourVet(boolean has) { safeAttrs().put("has24HourVet", has); }

    /**
     * Veteriner çalışma saatleri/açıklama (örn: "Haftanın 7 günü mesai bitimine kadar" veya "Her gün mesai saatleri içerisinde")
     */
    public String getVetSchedule() {
        Object v = getAttrs() != null ? getAttrs().get("vetSchedule") : null;
        return v == null ? null : v.toString();
    }
    public void setVetSchedule(String schedule) { safeAttrs().put("vetSchedule", schedule); }

    /**
     * Veteriner sıklığı/açıklama (örn: "Günde 5-6 kez barınak alanını dolaşıyorlar")
     */
    public String getVetFrequency() {
        Object v = getAttrs() != null ? getAttrs().get("vetFrequency") : null;
        return v == null ? null : v.toString();
    }
    public void setVetFrequency(String frequency) { safeAttrs().put("vetFrequency", frequency); }

    /**
     * Ameliyathane var mı? (boolean)
     */
    public Boolean hasOperatingRoom() {
        Object v = getAttrs() != null ? getAttrs().get("hasOperatingRoom") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasOperatingRoom(boolean has) { safeAttrs().put("hasOperatingRoom", has); }

    /**
     * Ameliyatlar nerede yapılıyor? (örn: "Revir bölümü" veya "Ameliyathane")
     */
    public String getSurgeryLocation() {
        Object v = getAttrs() != null ? getAttrs().get("surgeryLocation") : null;
        return v == null ? null : v.toString();
    }
    public void setSurgeryLocation(String location) { safeAttrs().put("surgeryLocation", location); }

    /**
     * Röntgen cihazı var mı? (boolean)
     */
    public Boolean hasXRay() {
        Object v = getAttrs() != null ? getAttrs().get("hasXRay") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasXRay(boolean has) { safeAttrs().put("hasXRay", has); }

    /**
     * Uzun tatillerde alınan önlemler (örn: "Gönüllülerle çalışıyorlar haftanın her günü için bir gün gönüllüsü mevcut")
     */
    public String getHolidayMeasures() {
        Object v = getAttrs() != null ? getAttrs().get("holidayMeasures") : null;
        return v == null ? null : v.toString();
    }
    public void setHolidayMeasures(String measures) { safeAttrs().put("holidayMeasures", measures); }

    // ========= Yerleşim Bilgileri =========

    /**
     * Zemin tipi (örn: "Toprak", "Taş, mermer", "Beton")
     */
    public String getFloorType() {
        Object v = getAttrs() != null ? getAttrs().get("floorType") : null;
        return v == null ? null : v.toString();
    }
    public void setFloorType(String type) { safeAttrs().put("floorType", type); }

    /**
     * Alan geniş mi? (boolean)
     */
    public Boolean isAreaWide() {
        Object v = getAttrs() != null ? getAttrs().get("isAreaWide") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setIsAreaWide(boolean wide) { safeAttrs().put("isAreaWide", wide); }

    /**
     * Alan temiz mi? (boolean)
     */
    public Boolean isAreaClean() {
        Object v = getAttrs() != null ? getAttrs().get("isAreaClean") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setIsAreaClean(boolean clean) { safeAttrs().put("isAreaClean", clean); }

    /**
     * Bahçe çitleri çelik hasır mı? (boolean)
     */
    public Boolean hasSteelMeshFence() {
        Object v = getAttrs() != null ? getAttrs().get("hasSteelMeshFence") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasSteelMeshFence(boolean has) { safeAttrs().put("hasSteelMeshFence", has); }

    /**
     * Bahçe çitleri 2m'den az mı? (boolean)
     */
    public Boolean isFenceUnder2m() {
        Object v = getAttrs() != null ? getAttrs().get("isFenceUnder2m") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setIsFenceUnder2m(boolean under) { safeAttrs().put("isFenceUnder2m", under); }

    /**
     * Kulübelerde pencere var mı? (havalandırma için) (boolean)
     */
    public Boolean hasWindowsInShelters() {
        Object v = getAttrs() != null ? getAttrs().get("hasWindowsInShelters") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasWindowsInShelters(boolean has) { safeAttrs().put("hasWindowsInShelters", has); }

    /**
     * Taban eğimli mi? (zeminde su kalmamalı) (boolean)
     */
    public Boolean hasSlopedFloor() {
        Object v = getAttrs() != null ? getAttrs().get("hasSlopedFloor") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasSlopedFloor(boolean sloped) { safeAttrs().put("hasSlopedFloor", sloped); }

    /**
     * Kulübeler yan yana, teras ve bahçeli mi? (boolean)
     */
    public Boolean hasTerracedShelters() {
        Object v = getAttrs() != null ? getAttrs().get("hasTerracedShelters") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasTerracedShelters(boolean has) { safeAttrs().put("hasTerracedShelters", has); }

    /**
     * Beton olan yerler karo döşenmiş mi? (boolean)
     */
    public Boolean hasTiledConcrete() {
        Object v = getAttrs() != null ? getAttrs().get("hasTiledConcrete") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasTiledConcrete(boolean has) { safeAttrs().put("hasTiledConcrete", has); }

    /**
     * Bahçelerde drenaj ve su giderleri var mı? (boolean)
     */
    public Boolean hasDrainage() {
        Object v = getAttrs() != null ? getAttrs().get("hasDrainage") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasDrainage(boolean has) { safeAttrs().put("hasDrainage", has); }

    /**
     * Kulübelerin teraslarında su olukları var mı? (boolean)
     */
    public Boolean hasGutters() {
        Object v = getAttrs() != null ? getAttrs().get("hasGutters") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasGutters(boolean has) { safeAttrs().put("hasGutters", has); }

    /**
     * Her gün toprağın üzerindeki pislikler alınıyor mu? (boolean)
     */
    public Boolean isDailyCleaned() {
        Object v = getAttrs() != null ? getAttrs().get("isDailyCleaned") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setIsDailyCleaned(boolean cleaned) { safeAttrs().put("isDailyCleaned", cleaned); }

    // ========= Bölme Bilgileri =========

    /**
     * Karantina bölgesi var mı? (kısırlaştırılmamış hayvanlar için, 2m² olmalı) (boolean)
     */
    public Boolean hasQuarantineArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasQuarantineArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasQuarantineArea(boolean has) { safeAttrs().put("hasQuarantineArea", has); }

    /**
     * Yavrulu anne bölmeleri var mı? (3m² olmalı) (boolean)
     */
    public Boolean hasMotherPuppyArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasMotherPuppyArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasMotherPuppyArea(boolean has) { safeAttrs().put("hasMotherPuppyArea", has); }

    /**
     * Küçük cins köpekler için ısıtmalı, minderli, uygun alanlar mevcut mu? (boolean)
     */
    public Boolean hasSmallBreedArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasSmallBreedArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasSmallBreedArea(boolean has) { safeAttrs().put("hasSmallBreedArea", has); }

    /**
     * Sokaktan toplanan iri cins köpekler için 4 ayrı bölme var mı? (küçük boy-orta boy-çok uysal-agresifler) (boolean)
     */
    public Boolean hasLargeBreedSeparateAreas() {
        Object v = getAttrs() != null ? getAttrs().get("hasLargeBreedSeparateAreas") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasLargeBreedSeparateAreas(boolean has) { safeAttrs().put("hasLargeBreedSeparateAreas", has); }

    /**
     * Gezebilecek büyük alan var mı? (boolean)
     */
    public Boolean hasLargePlayArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasLargePlayArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasLargePlayArea(boolean has) { safeAttrs().put("hasLargePlayArea", has); }

    /**
     * Gölgelik var mı? (boolean)
     */
    public Boolean hasShade() {
        Object v = getAttrs() != null ? getAttrs().get("hasShade") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasShade(boolean has) { safeAttrs().put("hasShade", has); }

    /**
     * Uyuzlar için ayrı 2 bölme var mı? (boolean)
     */
    public Boolean hasMangeArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasMangeArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasMangeArea(boolean has) { safeAttrs().put("hasMangeArea", has); }

    /**
     * Ameliyatlı sokak hayvanları için kafes bölmeleri var mı? (boolean)
     */
    public Boolean hasPostSurgeryCages() {
        Object v = getAttrs() != null ? getAttrs().get("hasPostSurgeryCages") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasPostSurgeryCages(boolean has) { safeAttrs().put("hasPostSurgeryCages", has); }

    /**
     * Hasta-yaralı kedi yeri üstü kapalı mı? (boolean)
     */
    public Boolean hasCoveredCatArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasCoveredCatArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasCoveredCatArea(boolean has) { safeAttrs().put("hasCoveredCatArea", has); }

    /**
     * Hasta-yaralı kedi yerinde koltuk minder vb var mı? (boolean)
     */
    public Boolean hasComfortItemsForCats() {
        Object v = getAttrs() != null ? getAttrs().get("hasComfortItemsForCats") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasComfortItemsForCats(boolean has) { safeAttrs().put("hasComfortItemsForCats", has); }

    // ========= Beslenme Bilgileri =========

    /**
     * Hangi aralıklarla besleme yapılıyor? (örn: "günde 1", "günde 2")
     */
    public String getFeedingFrequency() {
        Object v = getAttrs() != null ? getAttrs().get("feedingFrequency") : null;
        return v == null ? null : v.toString();
    }
    public void setFeedingFrequency(String frequency) { safeAttrs().put("feedingFrequency", frequency); }

    /**
     * Bir hayvana ortalama ne kadar mama düşüyor? (gram cinsinden, örn: 500, 600)
     */
    public Integer getFoodPerAnimal() {
        Object v = getAttrs() != null ? getAttrs().get("foodPerAnimal") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setFoodPerAnimal(int grams) { safeAttrs().put("foodPerAnimal", grams); }

    /**
     * Barınağa aylık ortalama ne kadar mama geliyor? (ton cinsinden, örn: 25)
     */
    public Double getMonthlyFoodAmount() {
        Object v = getAttrs() != null ? getAttrs().get("monthlyFoodAmount") : null;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try { return v == null ? null : Double.parseDouble(v.toString()); } catch(Exception e){ return null; }
    }
    public void setMonthlyFoodAmount(double tons) { safeAttrs().put("monthlyFoodAmount", tons); }

    /**
     * Mama saatleri (örn: "Her gün 15.00")
     */
    public String getFeedingTime() {
        Object v = getAttrs() != null ? getAttrs().get("feedingTime") : null;
        return v == null ? null : v.toString();
    }
    public void setFeedingTime(String time) { safeAttrs().put("feedingTime", time); }

    /**
     * Köpek besleme alanında tepsilerde yemek kalıyor mu? (boolean)
     */
    public Boolean hasLeftoverFood() {
        Object v = getAttrs() != null ? getAttrs().get("hasLeftoverFood") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasLeftoverFood(boolean has) { safeAttrs().put("hasLeftoverFood", has); }

    // ========= Kapasite ve Çalışan Bilgileri =========

    /**
     * Kapasite (maksimum hayvan sayısı, örn: 2200)
     */
    public Integer getCapacity() {
        Object v = getAttrs() != null ? getAttrs().get("capacity") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setCapacity(int capacity) { safeAttrs().put("capacity", capacity); }

    /**
     * Bakılan canlı sayısı (mevcut hayvan sayısı, örn: 982)
     */
    public Integer getCurrentAnimalCount() {
        Object v = getAttrs() != null ? getAttrs().get("currentAnimalCount") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setCurrentAnimalCount(int count) { safeAttrs().put("currentAnimalCount", count); }

    /**
     * Çalışan sayısı (örn: 46)
     */
    public Integer getEmployeeCount() {
        Object v = getAttrs() != null ? getAttrs().get("employeeCount") : null;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return v == null ? null : Integer.parseInt(v.toString()); } catch(Exception e){ return null; }
    }
    public void setEmployeeCount(int count) { safeAttrs().put("employeeCount", count); }

    /**
     * Çalışan görevleri/açıklama (örn: "Başhekim,veteriner,veteriner teknikeri,personel")
     */
    public String getEmployeeRoles() {
        Object v = getAttrs() != null ? getAttrs().get("employeeRoles") : null;
        return v == null ? null : v.toString();
    }
    public void setEmployeeRoles(String roles) { safeAttrs().put("employeeRoles", roles); }

    // ========= İdari Birimler =========

    /**
     * İdari alan ortada mı? (boolean)
     */
    public Boolean isAdminAreaCentral() {
        Object v = getAttrs() != null ? getAttrs().get("isAdminAreaCentral") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setIsAdminAreaCentral(boolean central) { safeAttrs().put("isAdminAreaCentral", central); }

    /**
     * Tüm alanları görebilecek konumda mı? (boolean)
     */
    public Boolean canViewAllAreas() {
        Object v = getAttrs() != null ? getAttrs().get("canViewAllAreas") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setCanViewAllAreas(boolean can) { safeAttrs().put("canViewAllAreas", can); }

    /**
     * Üst kat bekçinin kalabileceği yerde mi? (boolean)
     */
    public Boolean hasGuardRoom() {
        Object v = getAttrs() != null ? getAttrs().get("hasGuardRoom") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasGuardRoom(boolean has) { safeAttrs().put("hasGuardRoom", has); }

    /**
     * Gece kalan kişinin yeri uygun mu? (boolean)
     */
    public Boolean hasNightStayArea() {
        Object v = getAttrs() != null ? getAttrs().get("hasNightStayArea") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasNightStayArea(boolean has) { safeAttrs().put("hasNightStayArea", has); }

    /**
     * Alt katta ameliyathane tedavi odaları bulunuyor mu? (boolean)
     */
    public Boolean hasGroundFloorSurgery() {
        Object v = getAttrs() != null ? getAttrs().get("hasGroundFloorSurgery") : null;
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof String) return Boolean.parseBoolean((String) v);
        return null;
    }
    public void setHasGroundFloorSurgery(boolean has) { safeAttrs().put("hasGroundFloorSurgery", has); }

    // ========= Belediye İş Birlikleri =========

    /**
     * Belediye ile iş birlikleri/talepleri (virgülle ayrılmış, örn: "Bağcılar,Maltepe,Kartal,Beyoğlu")
     */
    public String getMunicipalityPartnerships() {
        Object v = getAttrs() != null ? getAttrs().get("municipalityPartnerships") : null;
        return v == null ? null : v.toString();
    }
    public void setMunicipalityPartnerships(String partnerships) { safeAttrs().put("municipalityPartnerships", partnerships); }
}
