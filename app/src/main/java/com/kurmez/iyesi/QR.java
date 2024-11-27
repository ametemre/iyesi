package com.kurmez.iyesi;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class QR extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        // Retrieve the QR data passed from the QRScannerActivity
        String qrData = getIntent().getStringExtra("qr_data");

        // Find views in the layout
        TextView qrTextView = findViewById(R.id.qr_data_text_view);
        Button processButton = findViewById(R.id.process_button);

        // Display the QR data or a fallback message
        if (qrData != null && !qrData.isEmpty()) {
            qrTextView.setText("QR Data: " + qrData);
        } else {
            qrTextView.setText("No QR Data Found");
        }

        // Set up button to process QR data (example action)
        processButton.setOnClickListener(v -> {
            if (qrData != null && !qrData.isEmpty()) {
                // Handle the QR data as needed (e.g., navigate or process)
                Toast.makeText(QR.this, "Processing: " + qrData, Toast.LENGTH_SHORT).show();

                // Example: Perform action based on QR data
                if (qrData.startsWith("http")) {
                    // Could launch a browser or navigate to another activity
                    Toast.makeText(QR.this, "This looks like a URL!", Toast.LENGTH_SHORT).show();
                } else {
                    // Handle non-URL QR data
                    Toast.makeText(QR.this, "Data: " + qrData, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(QR.this, "Nothing to process", Toast.LENGTH_SHORT).show();
            }
        });
    }
}