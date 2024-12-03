package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class QRScannerActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);

        // Start QR scanning when the activity is created
        initiateQRScan();
    }

    /**
     * Starts the QR scan using ZXing IntentIntegrator.
     */
    private void initiateQRScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Align the QR Code within the frame");
        integrator.setCameraId(0); // Default camera
        integrator.setBeepEnabled(true); // Enable beep after scanning
        integrator.setBarcodeImageEnabled(false); // Disable saving QR image
        integrator.setOrientationLocked(false); // Allow orientation changes
        integrator.initiateScan();
    }

    /**
     * Handles the result from the QR scanner.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);

        if (result != null) {
            if (result.getContents() != null) {
                // QR data successfully scanned
                String scannedData = result.getContents();
                // Pass the scanned QR data back to the calling activity
                Intent intent = new Intent();
                intent.putExtra("scanned_data", scannedData);
                setResult(RESULT_OK, intent);
                // Display a toast for feedback
                Toast.makeText(this, "QR Scanned: " + scannedData, Toast.LENGTH_SHORT).show();
                finish();
            } else {
                // No QR data scanned
                Toast.makeText(this, "No QR Code detected!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        } else {
            // Handle case where scanning didn't start or user canceled
            Toast.makeText(this, "Scan canceled or failed!", Toast.LENGTH_SHORT).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
