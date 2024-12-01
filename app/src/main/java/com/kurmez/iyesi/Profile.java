package com.kurmez.iyesi;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class Profile extends AppCompatActivity {

    private static final int REQUEST_IMAGE_PICK = 1;
    private static final int REQUEST_IMAGE_CAPTURE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        // Profile components
        ImageView profileImage = findViewById(R.id.profile_image);
        TextView username = findViewById(R.id.profile_username);
        TextView bio = findViewById(R.id.profile_bio);

        // Click to open edit profile popup
        findViewById(R.id.icon_notification).setOnClickListener(v -> openEditProfilePopup());

        // Profile image actions
        profileImage.setOnClickListener(v -> openLocalStorage());
        profileImage.setOnLongClickListener(v -> {
            openCamera();
            return true;
        });
    }

    private void openEditProfilePopup() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.layout_edit_profile);

        // Initialize popup components
        ImageView editProfileImage = dialog.findViewById(R.id.edit_profile_image);
        EditText editUsername = dialog.findViewById(R.id.edit_profile_username);
        EditText editBio = dialog.findViewById(R.id.edit_profile_bio);
        Button saveButton = dialog.findViewById(R.id.save_button);

        // Pre-fill fields with current profile info
        editUsername.setText("John Doe"); // Replace with actual user data
        editBio.setText("Bio goes here"); // Replace with actual user data

        // Save button action
        saveButton.setOnClickListener(v -> {
            // Save changes
            String newUsername = editUsername.getText().toString().trim();
            String newBio = editBio.getText().toString().trim();

            // Update profile details (save to database or backend)
            // Example: Update UI with new data
            TextView username = findViewById(R.id.profile_username);
            TextView bio = findViewById(R.id.profile_bio);
            username.setText(newUsername);
            bio.setText(newBio);

            dialog.dismiss();
        });

        // Profile image actions in popup
        editProfileImage.setOnClickListener(v -> openLocalStorage());
        editProfileImage.setOnLongClickListener(v -> {
            openCamera();
            return true;
        });

        dialog.show();
    }

    private void openLocalStorage() {
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, REQUEST_IMAGE_PICK);
    }

    private void openCamera() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_IMAGE_CAPTURE);
        }
    }
}
