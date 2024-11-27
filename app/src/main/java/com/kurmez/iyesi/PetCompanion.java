package com.kurmez.iyesi;

import java.io.Serializable;

public class PetCompanion implements Serializable {
    private String imageResId; // URL for the photo
    private String breed;      // Species or breed
    private String foundDate;
    private String foundLocation;
    private String finderName; // User ID of the finder

    // No-argument constructor for Firestore (important for Firebase)
    public PetCompanion() {}

    // Constructor for creating an instance
    public PetCompanion(String imageResId, String breed, String foundDate, String foundLocation, String finderName) {
        this.imageResId = imageResId;
        this.breed = breed;
        this.foundDate = foundDate;
        this.foundLocation = foundLocation;
        this.finderName = finderName;
    }

    // Getters for Firestore serialization and app use
    public String getImageResId() {
        return imageResId;
    }

    public String getBreed() {
        return breed;
    }

    public String getFoundDate() {
        return foundDate;
    }

    public String getFoundLocation() {
        return foundLocation;
    }

    public String getFinderName() {
        return finderName;
    }
}
