package com.kurmez.iyesi;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
                handler.postDelayed(startCameraRunnable, 150); // Delay of 2 seconds
            }
        });

        // Long-click listener to generate and display a device-specific QR code
        patiEnterButton.setOnLongClickListener(v -> {
            showDeviceSpecificQRCode();
            return true; // Consume the long click
        });
    }

    /**
     * Opens the QR Scanner for device registration when clicked 20 times.
     */
    private void openQRScannerForRegistration() {
        Toast.makeText(this, "Accessing QR Scanner for Registration...", Toast.LENGTH_SHORT).show();
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivity(intent);
    }

    /**
     * Opens the Found activity after a delay.
     */
    private void openCameraWithDelay() {
        if (isRegistered) {
            navigateToWelcome(); // Navigate to Welcome activity for registered users
        } else {
            openCameraForFound(); // Open Found activity for unregistered users
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
     * Opens the Found activity for taking a photo of a founded soul.
     */
    private void openCameraForFound() {
        Intent intent = new Intent(this, Found.class);
        startActivity(intent);
    }

    /**
     * Displays a popup with a generated device-specific QR code.
     */
    private void showDeviceSpecificQRCode() {
        String deviceId = UUID.randomUUID().toString(); // Replace with actual device info
        Bitmap qrBitmap;
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            qrBitmap = barcodeEncoder.encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400);
        } catch (WriterException e) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
            return;
        }
        // Create and show the popup
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
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up handler to prevent memory leaks
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
    // Add this constant at the top of MainActivity
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCAN_QR_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String scannedData = data.getStringExtra("scanned_data");

            if (scannedData != null && !scannedData.isEmpty()) {
                // Display scanned data as a toast
                Toast.makeText(this, "Scanned QR Data: " + scannedData, Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "No QR Data Found!", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
