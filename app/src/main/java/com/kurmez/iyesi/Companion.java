package com.kurmez.iyesi;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;

public class Companion extends AppCompatActivity {
    private String species;
    private String foundDate;
    private String foundPlace;
    private String photoUrl;
    private String profileId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_companion);

        // Retrieve data from the Intent
        species = getIntent().getStringExtra("species");
        foundDate = getIntent().getStringExtra("foundDate");
        foundPlace = getIntent().getStringExtra("foundPlace");
        photoUrl = getIntent().getStringExtra("photoUrl");
        profileId = getIntent().getStringExtra("profileId");

        // Update the UI elements
        populateUI();
    }

    private void populateUI() {
        // Get references to the UI elements
        ImageView companionImage = findViewById(R.id.companion_image);
        TextView soulCompanion = findViewById(R.id.soul_companion);
        TextView foundDateView = findViewById(R.id.found_Date);
        TextView foundPlaceView = findViewById(R.id.found_place);
        TextView who = findViewById(R.id.who);

        // Set the data to the UI elements
        soulCompanion.setText(species);
        foundDateView.setText(foundDate);
        foundPlaceView.setText(foundPlace);
        who.setText(profileId);

        // Load the companion's image using Glide
        Glide.with(this)
                .load(photoUrl)
                .placeholder(R.drawable.holder) // Placeholder image while loading
                .error(R.drawable.star) // Error image if loading fails
                .into(companionImage);
    }
}
