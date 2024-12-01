package com.kurmez.iyesi;

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
     * Saves the companion information along with photos.
     */
    private void saveCompanion() {
        // TODO: Implement saving logic (e.g., Firebase storage and Firestore for metadata)
        Toast.makeText(this, "Companion registered successfully!", Toast.LENGTH_SHORT).show();
    }
}
