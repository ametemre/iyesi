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

    private void initiateQRScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("Scan a QR Code");
        integrator.setCameraId(0); // Default camera
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false); // Disable saving images
        integrator.setOrientationLocked(true); // Lock orientation
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);

        if (result != null) {
            if (result.getContents() != null) {
                // Scanned QR data
                String scannedData = result.getContents();

                // Pass data back to calling activity (e.g., QR.java)
                Intent intent = new Intent();
                intent.putExtra("scanned_data", scannedData);
                setResult(RESULT_OK, intent);
                finish();
            } else {
                // If no QR code was scanned
                Toast.makeText(this, "No QR Code scanned!", Toast.LENGTH_SHORT).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }
}
