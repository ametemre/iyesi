package com.kurmez.iyesi;

public class MissingPet {
    private String petName;
    private String species;
    private String color;
    private String age;
    private String lastLocation;
    private String missingDate;
    private String photoUrl;
    private String ownerProfileId;

    public MissingPet() {
        // Default constructor for Firestore
    }

    public MissingPet(String petName, String species, String color, String age, String lastLocation, String missingDate, String photoUrl, String ownerProfileId) {
        this.petName = petName;
        this.species = species;
        this.color = color;
        this.age = age;
        this.lastLocation = lastLocation;
        this.missingDate = missingDate;
        this.photoUrl = photoUrl;
        this.ownerProfileId = ownerProfileId;
    }

    // Getters and Setters
    public String getPetName() {
        return petName;
    }

    public void setPetName(String petName) {
        this.petName = petName;
    }

    public String getSpecies() {
        return species;
    }

    public void setSpecies(String species) {
        this.species = species;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public String getAge() {
        return age;
    }

    public void setAge(String age) {
        this.age = age;
    }

    public String getLastLocation() {
        return lastLocation;
    }

    public void setLastLocation(String lastLocation) {
        this.lastLocation = lastLocation;
    }

    public String getMissingDate() {
        return missingDate;
    }

    public void setMissingDate(String missingDate) {
        this.missingDate = missingDate;
    }

    public String getPhotoUrl() {
        return photoUrl;
    }

    public void setPhotoUrl(String photoUrl) {
        this.photoUrl = photoUrl;
    }

    public String getOwnerProfileId() {
        return ownerProfileId;
    }

    public void setOwnerProfileId(String ownerProfileId) {
        this.ownerProfileId = ownerProfileId;
    }
}
