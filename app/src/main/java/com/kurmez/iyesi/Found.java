package com.kurmez.iyesi;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class Found extends AppCompatActivity {

    private static final int REQUEST_IMAGE_CAPTURE = 1; // Request code for capturing a photo
    private List<Bitmap> photoList = new ArrayList<>(); // List to store captured images

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_found);

        findViewById(R.id.capture_button).setOnClickListener(v -> openCamera());

        // Automatically open the camera for the first photo
        openCamera();
    }

    /**
     * Launch the camera to capture a photo.
     */
    private void openCamera() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        } else {
            Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK && data != null) {
            // Retrieve the captured image
            Bitmap imageBitmap = (Bitmap) data.getExtras().get("data");

            if (imageBitmap != null) {
                photoList.add(imageBitmap); // Add photo to the list
                navigateToFoundedActivity(); // Navigate to the next screen
            }
        } else if (requestCode == REQUEST_IMAGE_CAPTURE) {
            Toast.makeText(this, "Photo capture cancelled", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Navigates to the next screen (activity_founded) with the captured photos.
     */
    private void navigateToFoundedActivity() {
        Intent intent = new Intent(this, Founded.class);
        intent.putParcelableArrayListExtra("photos", new ArrayList<>(photoList)); // Pass the photos
        startActivity(intent);
    }
}
