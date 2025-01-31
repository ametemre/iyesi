package com.kurmez.iyesi;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

public class Founded extends AppCompatActivity {
    private List<Bitmap> photoList;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        // Retrieve the photos passed from Found activity
        photoList = getIntent().getParcelableArrayListExtra("photos");

        if (photoList == null || photoList.isEmpty()) {
            Toast.makeText(this, "No photos found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Set up the image slider
        ViewPager2 photoSlider = findViewById(R.id.founded_photos_slider);
        ImageSliderAdapter sliderAdapter = new ImageSliderAdapter(photoList, this);
        photoSlider.setAdapter(sliderAdapter);

        findViewById(R.id.take_anotherphoto_button).setOnClickListener(v -> finish()); // Return to capture screen

        findViewById(R.id.save_companion_button).setOnClickListener(v -> saveCompanion());
    }
    /**
     * Saves the companion information along with photos and navigates to the Companion activity.
     */
    private void saveCompanion() {
        String species = "Sarman Cat"; // Replace with actual user input
        String foundDate = "01/01/2025";     // Replace with actual user input
        String foundPlace = "Bishkek";     // Replace with actual user input
        String profileId = "Veterinarian : Evren Hoca"; // Replace with actual data

        if (photoList != null && !photoList.isEmpty()) {
            Bitmap firstPhoto = photoList.get(0);
            String photoUrl = uploadPhotoAndGetUrl(firstPhoto);

            if (photoUrl != null) {
                Intent intent = new Intent(Founded.this, Companion.class);
                intent.putExtra("species", species);
                intent.putExtra("foundDate", foundDate);
                intent.putExtra("foundPlace", foundPlace);
                intent.putExtra("photoUrl", photoUrl);
                intent.putExtra("profileId", profileId);
                startActivity(intent);
            } else {
                Toast.makeText(this, "Failed to upload photo", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "No photo available to register companion", Toast.LENGTH_SHORT).show();
        }
    }
    /**
     * Simulates uploading the photo and returning a URL (replace with actual implementation).
     */
    private String uploadPhotoAndGetUrl(Bitmap photo) {
        // TODO: Replace this with actual upload logic (e.g., Firebase Storage)
        return "https://example.com/photo.jpg"; // Replace with the real uploaded URL
    }
}