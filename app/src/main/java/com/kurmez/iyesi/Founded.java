package com.kurmez.iyesi;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Founded extends AppCompatActivity {

    private static final int REQUEST_IMAGE_CAPTURE = 1;

    private ImageView photoPreview;
    private EditText speciesField, foundDateField, foundPlaceField;
    private Button takePhotoButton, saveButton;

    private Uri photoUri;
    private StorageReference storageReference;
    private FirebaseFirestore firestore;
    private FirebaseAuth auth;

    private String currentPhotoPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_founded);

        // Initialize Firebase instances
        storageReference = FirebaseStorage.getInstance().getReference();
        firestore = FirebaseFirestore.getInstance();
        auth = FirebaseAuth.getInstance();

        // Find views
        photoPreview = findViewById(R.id.founded_photo);
        speciesField = findViewById(R.id.companion_species);
        foundDateField = findViewById(R.id.companion_found_date);
        foundPlaceField = findViewById(R.id.companion_found_place);
        takePhotoButton = findViewById(R.id.take_photo_button);
        saveButton = findViewById(R.id.save_companion_button);

        // Take photo button click
        takePhotoButton.setOnClickListener(v -> capturePhoto());

        // Save button click
        saveButton.setOnClickListener(v -> saveCompanion());
    }

    private void capturePhoto() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the file for the photo
            File photoFile = null;
            try {
                photoFile = createImageFile();
            } catch (IOException ex) {
                Toast.makeText(this, "Error creating photo file: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            }

            // Continue only if the file was successfully created
            if (photoFile != null) {
                photoUri = FileProvider.getUriForFile(this, "com.kurmez.iyesi.fileprovider", photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    private File createImageFile() throws IOException {
        // Create a unique image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(null);
        File image = File.createTempFile(imageFileName, ".jpg", storageDir);

        // Save the file path for later use
        currentPhotoPath = image.getAbsolutePath();
        return image;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK) {
            // Display the photo
            photoPreview.setImageURI(photoUri);
        }
    }

    private void saveCompanion() {
        String species = speciesField.getText().toString().trim();
        String foundDate = foundDateField.getText().toString().trim();
        String foundPlace = foundPlaceField.getText().toString().trim();
        String userId = auth.getCurrentUser() != null ? auth.getCurrentUser().getUid() : "Unknown User";

        // Validation checks
        if (species.isEmpty()) {
            Toast.makeText(this, "Species cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (foundDate.isEmpty()) {
            Toast.makeText(this, "Found date cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (foundPlace.isEmpty()) {
            Toast.makeText(this, "Found place cannot be empty!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (photoUri == null) {
            Toast.makeText(this, "Please take a photo!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Upload photo to Firebase Storage
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        StorageReference photoRef = storageReference.child("companion_photos/" + timestamp + ".jpg");

        photoRef.putFile(photoUri)
                .addOnSuccessListener(taskSnapshot -> photoRef.getDownloadUrl().addOnSuccessListener(uri -> {
                    String photoUrl = uri.toString();

                    // Create a PetCompanion object
                    PetCompanion companion = new PetCompanion(photoUrl, species, foundDate, foundPlace, userId);

                    // Save companion data to Firestore
                    firestore.collection("companions")
                            .add(companion)
                            .addOnSuccessListener(documentReference -> {
                                Toast.makeText(this, "Companion registered successfully!", Toast.LENGTH_SHORT).show();
                                finish(); // Close activity
                            })
                            .addOnFailureListener(e -> {
                                Toast.makeText(this, "Failed to register companion: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            });
                }))
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Failed to upload photo: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}