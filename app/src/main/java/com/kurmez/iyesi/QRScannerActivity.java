package com.kurmez.iyesi;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.kurmez.iyesi.kurmes.utilities.Helpers;

import java.util.Map;

public class QRScannerActivity extends AppCompatActivity {
    public static String EXTRA_SCANNED_DATA;
    protected String HostID;
    protected String GuestID;
    @SuppressLint("HardwareIds")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_scanner);
        HostID = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID);
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
                GuestID = result.getContents();
                Intent intent = new Intent();
                intent.putExtra("scanned_data", GuestID);
                FirebaseFunctions.getInstance()
                        .getHttpsCallable("createBlock")
                        .call(Map.of(
                                "inviterDeviceId", HostID,
                                "deviceId",        GuestID
                        ))
                        .addOnSuccessListener(r -> {

                            setResult(RESULT_OK, intent);
                            // Display a toast for feedback
                            Helpers.showToastSafe(this, "QR Scanned: " + GuestID);
                        })
                        .addOnFailureListener(e ->{
                            Helpers.showToastSafe(this, "Registration failed: " + e.getMessage());
                        });
                finish();
            } else {
                // No QR data scanned
                Helpers.showToastSafe(this, "No QR Code detected!");
                setResult(RESULT_CANCELED);
                finish();
            }
        } else {
            // Handle case where scanning didn't start or user canceled
            Helpers.showToastSafe(this, "Scan canceled or failed!");
            setResult(RESULT_CANCELED);
            finish();
        }
    }
}
