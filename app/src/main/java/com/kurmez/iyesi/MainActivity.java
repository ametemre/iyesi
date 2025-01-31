package com.kurmez.iyesi;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final int MAX_CLICKS = 20; // Number of clicks for QR scanner access
    private int clickCounter = 0; // Counter for detecting 20 clicks
    private boolean isRegistered = false; // Replace with actual logic to check user registration
    private Handler handler = new Handler(); // To manage the delayed camera start
    private Runnable startCameraRunnable; // Camera-starting task
    private static final int SCAN_QR_REQUEST_CODE = 1001; // Unique request code for QR Scanner
    private FirebaseFirestore db; // Firestore instance
    private FirebaseAuth mAuth; // FirebaseAuth instance
    private String generatedQRCode; // QR code generated for the device
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Firebase instances
        db = FirebaseFirestore.getInstance();
        mAuth = FirebaseAuth.getInstance();

        // Find the ImageButton
        ImageButton patiEnterButton = findViewById(R.id.pati_enter);

        // Set click listener for the button
        patiEnterButton.setOnClickListener(v -> {
            clickCounter++;

            // Reset and cancel any existing camera-starting task
            if (startCameraRunnable != null) {
                handler.removeCallbacks(startCameraRunnable);
            }

            // If clicked 20 times, open the QR Scanner
            if (clickCounter == MAX_CLICKS) {
                clickCounter = 0; // Reset the counter
                openQRScannerForRegistration(); // Open QR scanner
            } else {
                // Schedule the camera start after 2 seconds
                startCameraRunnable = this::openCameraWithDelay;
                handler.postDelayed(startCameraRunnable, 500); // Delay of 2 seconds
            }
        });

        // Long-click listener to generate and display a device-specific QR code
        patiEnterButton.setOnLongClickListener(v -> {
            handleLongClickForQRCode();
            return true; // Consume the long click
        });
    }
    /**
     * Opens the QR Scanner for device registration when clicked 20 times.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler to prevent memory leaks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
    private void openQRScannerForRegistration() {
        Toast.makeText(this, "Accessing QR Scanner for Registration...", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }
    /**
     * Opens the appropriate activity based on the registration status.
     */
    private void openCameraWithDelay() {
        if (isRegistered) {
            navigateToWelcome(); // Navigate to Welcome activity for registered users
        } else {
            navigateToKurmes(); // Open Kurmes activity for unregistered users
        }
    }
    /**
     * Navigates to the Welcome activity for registered users.
     */
    private void navigateToWelcome() {
        Intent intent = new Intent(this, Welcome.class);
        startActivity(intent);
    }
    /**
     * Opens the Kurmes activity for real-time image recognition if the user is unregistered.
     */
    private void navigateToKurmes() {
        Intent intent = new Intent(this, com.kurmez.iyesi.kurmes.Kurmes.class); // Navigate to Kurmes activity
        startActivity(intent);
    }
    /**
     * Handles the long click event to generate and display a device-specific QR code.
     * Also listens for database changes and navigates accordingly.
     */
    private void handleLongClickForQRCode() {
        String deviceId = getDeviceSpecificId();
        generatedQRCode = deviceId; // Save the generated QR code

        // Check user authentication status
        if (mAuth.getCurrentUser() != null) {
            // User is authenticated, navigate to Welcome
            navigateToWelcome();
        } else {
            // Listen for changes in the database for the generated QR code
            listenForDatabaseChanges(deviceId);

            // Show the QR code in a popup
            showQRCodePopup(deviceId);
        }
    }
    /**
     * Generates a unique identifier for the device (you can replace UUID with other device info).
     */
    private String getDeviceSpecificId() {
        // Replace this logic with actual unique device-specific logic if needed
        return UUID.randomUUID().toString();
    }
    /**
     * Listens for changes in the Firestore database for the generated QR code.
     *
     * @param deviceId The generated QR code representing the device.
     */
    private void listenForDatabaseChanges(String deviceId) {
        db.collection("devices")
                .whereEqualTo("deviceId", deviceId)
                .addSnapshotListener((querySnapshot, e) -> {
                    if (e != null) {
                        Toast.makeText(this, "Database Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (querySnapshot != null) {
                        for (DocumentChange change : querySnapshot.getDocumentChanges()) {
                            if (change.getType() == DocumentChange.Type.ADDED) {
                                // Check if the device is now registered
                                String registeredDeviceId = change.getDocument().getString("deviceId");
                                if (deviceId.equals(registeredDeviceId)) {
                                    Toast.makeText(this, "Device registered!", Toast.LENGTH_SHORT).show();

                                    // Check if user data is present
                                    if (change.getDocument().contains("userId")) {
                                        navigateToLogin(); // Navigate to Login activity
                                    } else {
                                        navigateToRegister(); // Navigate to Register activity
                                    }
                                    break;
                                }
                            }
                        }
                    }
                });
    }
    /**
     * Displays a popup with the generated QR code.
     *
     * @param deviceId The device-specific identifier to be displayed as a QR code.
     */
    private void showQRCodePopup(String deviceId) {
        Bitmap qrBitmap;
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            qrBitmap = barcodeEncoder.encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400);
        } catch (WriterException e) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
            return;
        }

        // Show the QR code in a popup
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Device QR Code");

        // Set the QR image in the dialog
        ImageButton qrImageButton = new ImageButton(this);
        qrImageButton.setImageBitmap(qrBitmap);
        qrImageButton.setBackgroundColor(getResources().getColor(android.R.color.transparent));

        builder.setView(qrImageButton);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }
    /**
     * Navigates to the Register activity.
     */
    private void navigateToRegister() {
        Intent intent = new Intent(this, Register.class);
        startActivity(intent);
    }
    /**
     * Navigates to the Login activity.
     */
    private void navigateToLogin() {
        Intent intent = new Intent(this, Login.class);
        startActivity(intent);
    }
}
