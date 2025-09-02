package com.kurmez.iyesi.kayra.Classes;

import androidx.annotation.Nullable;

import com.google.firebase.firestore.Exclude;

import org.json.JSONObject;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * PetCompanion hem sahiplendirme hem de sosyal akış verilerini taşıyan model sınıfıdır.
 * Delicate (hassas) bilgiler Firestore'a yazılırken @Exclude ile gizlenebilir.
 */
public class Soul implements Serializable {

    // -----------------------------
    // Adoption (Sahiplendirme) Fields
    // -------------------------
    /** Hayvanın kullanıcıya verilen adı veya tanımlayıcı ismi */
    private String name;

    /** Tür bilgisi (örn. "Cat", "Dog") */
    private String species;

    /** Irk veya cins bilgisi (örn. "Siamese", "Labrador") */
    private String breed;

    /** Yaş bilgisi (örn. "2 years") */
    private String age;

    /** Genel sağlık durumu (örn. "Good", "Needs Attention") */
    private String health;

    /** Bulunduğu tarih (YYYY-MM-DD formatında) */
    private String foundDate;

    /** Bulunduğu konum açıklaması */
    private String foundLocation;

    /** Veteriner bilgisi (hassas) */
    private String veterinary;

    /** Resim URL’si veya Storage referansı */
    private String imageResId;

    /** Kayıt yapan kişinin UID’si */
    private String finderName;

    /** Kayıt oluşturulma zamanı (milisaniye cinsinden) */
    private long timestamp;


    // -----------------------------
    // Social Feed (Sosyal Akış) Fields
    // -----------------------------

    /** Gönderi ID’si */
    private String postId;

    /** Gönderi içeriği */
    private String content;

    /** Gönderi sahibinin UID’si */
    private String authorUid;

    /** ISO formatında oluşturulma zamanı (örn. "2025-05-25T14:30:00Z") */
    private String createdAt;

    /** Gönderiye ait medya URL listesi */
    private List<String> mediaUrls;


    // -----------------------------
    // Additional Scenario Fields
    // -----------------------------

    /** Kayıp ilanı işaretçisi */
    private Boolean isLost;

    /** Geçici bakıcı UID’si */
    private String fosterUid;

    /** Geçici bakıma dönüş tarihi (YYYY-MM-DD) */
    private String fosterReturnDate;


    // -----------------------------
    // Constructors
    // -----------------------------

    /**
     * Boş constructor Firestore mapping için gereklidir.
     * mediaUrls listesini ve isLost bayrağını başlatır.
     */
    public Soul() {
        this.mediaUrls = new ArrayList<>();
        this.isLost    = false;
    }

    /**
     * Adoption için kullanılır.
     *
     * @param name           Hayvan adı
     * @param species        Tür bilgisi
     * @param breed          Irk/cins bilgisi
     * @param age            Yaş bilgisi
     * @param health         Sağlık durumu
     * @param foundDate      Bulunma tarihi
     * @param foundLocation  Bulunma konumu
     * @param veterinary     Veteriner bilgisi (hassas)
     * @param imageResId     Resim referansı
     * @param finderName     Kayıt yapan UID
     * @param timestamp      Oluşturulma zamanı
     */
    public Soul(
            String name,
            String species,
            String breed,
            String age,
            String health,
            String foundDate,
            String foundLocation,
            String veterinary,
            String imageResId,
            String finderName,
            long   timestamp
    ) {
        this();
        this.name          = name;
        this.species       = species;
        this.breed         = breed;
        this.age           = age;
        this.health        = health;
        this.foundDate     = foundDate;
        this.foundLocation = foundLocation;
        this.veterinary    = veterinary;
        this.imageResId    = imageResId;
        this.finderName    = finderName;
        this.timestamp     = timestamp;
    }

    /**
     * Social feed için kullanılır.
     *
     * @param postId     Gönderi ID’si
     * @param content    İçerik metni
     * @param authorUid  Yazar UID’si
     * @param createdAt  Oluşturulma zamanı
     * @param mediaUrls  Medya URL listesi
     */
    public Soul(
            String postId,
            String content,
            String authorUid,
            String createdAt,
            List<String> mediaUrls
    ) {
        this();
        this.postId    = postId;
        this.content   = content;
        this.authorUid = authorUid;
        this.createdAt = createdAt;
        this.mediaUrls = mediaUrls != null ? mediaUrls : new ArrayList<>();
    }

    @Nullable
    public static Soul fromJson(JSONObject o) {
        if (o == null) return null;

        // Bazı durumlarda { soul:{...} } şeklinde gelebilir
        JSONObject src = o.optJSONObject("soul");
        if (src == null) src = o;

        String species       = src.optString("species", "");
        String breed         = src.optString("breed", "");
        String age           = src.optString("age", "");
        String health        = src.optString("health", "");
        String foundDate     = src.optString("foundDate", "");
        String foundLocation = src.optString("foundLocation", "");
        String imageResId    = src.optString("imageResId", src.optString("imageUrl", ""));
        String finderName    = src.optString("finderName", src.optString("finder", ""));
        long   timestamp     = src.optLong("timestamp", src.optLong("createdAt", 0L));

        // Hiç anlamlı veri yoksa null döndür (ExplorePrivate fallback'ını tetikler)
        boolean empty = species.isEmpty() && breed.isEmpty() && age.isEmpty()
                && health.isEmpty() && foundDate.isEmpty() && foundLocation.isEmpty()
                && imageResId.isEmpty() && finderName.isEmpty() && timestamp == 0L;
        if (empty) return null;

        return new Soul(
                /*name*/ null,
                species, breed, age, health,
                foundDate, foundLocation,
                /*veterinary*/ null,
                imageResId, finderName, timestamp
        );
    }



    // -----------------------------
    // Getters & Setters
    // -----------------------------

    // Adoption getters
    public String getName()           { return name; }
    public String getSpecies()        { return species; }
    public String getBreed()          { return breed; }
    public String getAge()            { return age; }
    public String getHealth()         { return health; }
    public String getFoundDate()      { return foundDate; }
    public String getFoundLocation()  { return foundLocation; }

    /** Hassas veteriner bilgisini Firestore’dan gizler */
    @Exclude
    public String getVeterinary()     { return veterinary; }

    public String getImageResId()     { return imageResId; }
    public String getFinderName()     { return finderName; }
    public long   getTimestamp()      { return timestamp; }

    // Social feed getters
    public String getPostId()         { return postId; }
    public String getContent()        { return content; }
    public String getAuthorUid()      { return authorUid; }
    public String getCreatedAt()      { return createdAt; }
    public List<String> getMediaUrls(){ return mediaUrls; }

    // Scenario-specific getters & setters
    public Boolean getIsLost()                 { return isLost; }
    public void    setIsLost(Boolean isLost)   { this.isLost = isLost; }

    public String  getFosterUid()              { return fosterUid; }
    public void    setFosterUid(String fosterUid) { this.fosterUid = fosterUid; }

    public String  getFosterReturnDate()       { return fosterReturnDate; }
    public void    setFosterReturnDate(String fosterReturnDate) {
        this.fosterReturnDate = fosterReturnDate;
    }
}
