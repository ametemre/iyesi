package com.kurmez.iyesi;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.UUID;

public class QR extends AppCompatActivity {

    private static final int SCAN_QR_REQUEST_CODE = 1001; // Request code for QR scanning

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        // Retrieve the QR data passed from the QRScannerActivity
        String qrData = getIntent().getStringExtra("qr_data");

        // Find views in the layout
        TextView qrDataTextView = findViewById(R.id.qr_data_text_view);
        Button scanQRButton = findViewById(R.id.scan_qr_button);
        Button generateQRButton = findViewById(R.id.generate_qr_button);
        Button processButton = findViewById(R.id.process_button);

        // Display the QR data or a fallback message
        if (qrData != null && !qrData.isEmpty()) {
            qrDataTextView.setText("QR Data: " + qrData);
        } else {
            qrDataTextView.setText("QR Data will appear here");
        }

        // Handle "Scan QR" button click
        scanQRButton.setOnClickListener(v -> startQRScanner());

        // Handle "Generate QR" button click
        generateQRButton.setOnClickListener(v -> {
            String referralCode = generateUniqueReferralCode();
            showQRPopup(referralCode);
        });

        // Handle "Process QR" button click
        processButton.setOnClickListener(v -> {
            if (qrData != null && !qrData.isEmpty()) {
                // Process the QR data
                Toast.makeText(QR.this, "Processing: " + qrData, Toast.LENGTH_SHORT).show();

                // Example: Perform action based on QR data
                if (qrData.startsWith("http")) {
                    Toast.makeText(QR.this, "This looks like a URL!", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(QR.this, "Data: " + qrData, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(QR.this, "No QR Data to process", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Starts the QR Scanner activity.
     */
    private void startQRScanner() {
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }

    /**
     * Generates a QR Code and displays it in a popup dialog.
     */
    private void showQRPopup(String data) {
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            Bitmap qrBitmap = barcodeEncoder.encodeBitmap(data, BarcodeFormat.QR_CODE, 400, 400);

            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Your Referral QR Code");
            builder.setMessage("Share this QR code:");
            builder.setPositiveButton("Close", (dialog, which) -> dialog.dismiss());

            ImageView qrImageView = new ImageView(this);
            qrImageView.setImageBitmap(qrBitmap);
            builder.setView(qrImageView);

            builder.create().show();
        } catch (WriterException e) {
            Toast.makeText(this, "Failed to generate QR code: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String generateUniqueReferralCode() {
        String uniqueID = UUID.randomUUID().toString();
        return "https://example.com/app?referral=" + uniqueID;
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SCAN_QR_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String scannedData = data.getStringExtra("scanned_data");

            // Display the scanned QR data
            TextView qrDataTextView = findViewById(R.id.qr_data_text_view);
            qrDataTextView.setText("QR Data: " + scannedData);

            // Process the scanned data
            Toast.makeText(this, "Scanned: " + scannedData, Toast.LENGTH_SHORT).show();
        }
    }
}
