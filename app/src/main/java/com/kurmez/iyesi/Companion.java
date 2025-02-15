package com.kurmez.iyesi;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
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

        // Populate the UI elements with the retrieved data
        populateUI();

        // Show a waiting popup for Ülgen's response
        showWaitingPopup();
    }

    private void populateUI() {
        // Get references to the UI elements
        ImageView companionImage = findViewById(R.id.companion_image);
        TextView soulCompanion = findViewById(R.id.soul_companion);
        TextView foundDateView = findViewById(R.id.found_Date);
        TextView foundPlaceView = findViewById(R.id.found_place);
        TextView ulgenView = findViewById(R.id.veterineary_ulgen);

        // Set the data to the UI elements
        soulCompanion.setText(species != null ? species : "N/A");
        foundDateView.setText(foundDate != null ? foundDate : "N/A");
        foundPlaceView.setText(foundPlace != null ? foundPlace : "N/A");
        ulgenView.setText(profileId != null ? profileId : "N/A");

        // Load the companion's image using Glide
        if (photoUrl != null && !photoUrl.isEmpty()) {
            Glide.with(this)
                    .load(photoUrl)
                    .placeholder(R.drawable.holder) // Placeholder image while loading
                    .error(R.drawable.star) // Error image if loading fails
                    .into(companionImage);
        } else {
            companionImage.setImageResource(R.drawable.holder); // Fallback image
        }
    }

    /**
     * Displays a popup dialog indicating waiting for Ülgen's response.
     */
    private void showWaitingPopup() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Waiting For Ülgen's Approval");
        builder.setMessage(
                "Veterinarians in our community are checking your application and will respond to you ASAP with the necessary medical attention.\n\n" +
                        "Please do not touch, move, or pet the soul you have found. It may not look harmful, but street animals might carry diseases that could harm you, or you may harm the soul.\n\n" +
                        "You will receive the necessary professional help ASAP.");
        builder.setCancelable(false); // Prevent dismissal
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
}