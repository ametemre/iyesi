package com.kurmez.iyesi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.umay.Welcome;

import java.io.IOException;
import java.util.HashMap;
import com.google.firebase.functions.FirebaseFunctions;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;
import com.kurmez.iyesi.kurmes.utilities.helper.PermissionHelper;

import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final int MAX_CLICKS = 10; // Number of clicks for QR scanner access
    private FirebaseFunctions functions;         // CF çağrıları için
    private int clickCounter = 0; // Counter for detecting 20 clicks
    private boolean isRegistered = false; // Replace with actual logic to check user registration
    private Handler handler = new Handler(); // To manage the delayed camera start
    private Runnable startCameraRunnable; // Camera-starting task
    private static final int SCAN_QR_REQUEST_CODE = 1001; // Unique request code for QR Scanner
    private FirebaseFirestore db; // Firestore instance
    private FirebaseAuth mAuth = FirebaseAuth.getInstance(); // FirebaseAuth instance
    private String generatedQRCode; // QR code generated for the device
    private PrivateCom privateCom;
    private String response = null;
    private PermissionHelper permissionHelper;
    // callback’i dışarıda tanımladık:
    private final PermissionHelper.Callback permissionCallback = new PermissionHelper.Callback() {
        @Override public void onGranted() {
            // tüm izinler verildi
        }
        @Override public void onDenied() {
            // izinlerden en az biri reddedildi
        }
    };
    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null) {
            try {
                org.json.JSONObject payload = new org.json.JSONObject()
                        .put("kind", "health_check")
                        .put("ts", System.currentTimeMillis())
                        .put("note", "startup_warmup");  // opsiyonel
                user.reload() // profil/claim meta güncellensin
                        .addOnSuccessListener(__ ->
                                user.getIdToken(true) // force refresh
                                        .addOnSuccessListener(tokenResult -> {
                                            String idToken = tokenResult.getToken();
                                            Log.d("Role:", idToken);
                                        }));
                // App.app() Context döndürüyor; App'e cast edip çağırıyoruz
                ((App) App.app()).sendRequestWithAppCheckAndAuth(payload);
            } catch (Exception ignore) {}
        }

        Log.d("AUTH", user == null ? "Kullanıcı yok" : "Kullanıcı var: " + user.getUid());

        // 1) helper’ı oluştur, 2) activity ve callback ata,
        permissionHelper = new PermissionHelper();
        permissionHelper.setActivity(this);
        permissionHelper.setCallback(permissionCallback);
        // 3) parametresiz initialize:
        permissionHelper.initialize();

        // artık dilediğiniz yerde:
        //permissionHelper.requestAllPermissions();
        privateCom = new PrivateCom();
        // Initialize Firebase instances
        functions = FirebaseFunctions.getInstance();   // ← burayı ekleyin
        // Find the ImageButton
        ImageButton patiEnterButton = findViewById(R.id.pati_enter);
// Kullanıcı login ise küçük bir "health_check" at


        // Set click listener for the button
        patiEnterButton.setOnClickListener(v -> {
            clickCounter++;
            // Reset and cancel any existing camera-starting task
            if (startCameraRunnable != null) {
                handler.removeCallbacks(startCameraRunnable);
            }
            Log.d("Clicked" , String.valueOf(clickCounter));
            if (clickCounter == MAX_CLICKS) {
                clickCounter = 0; // Reset the counter
                try {
                    permissionHelper.requestBluetooth();
                    PrivateCom.connectToBluetoothDevice(this, () -> {
                        openQRScannerForRegistration(); // Bluetooth bağlantısı tamamlandıktan sonra çalışacak
                    });
                } catch (Exception e) {
                    Log.e("BluetoothFailed", e.toString());
                }
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
        // Sadece QR Scanner’ı başlatıyoruz
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCAN_QR_REQUEST_CODE && resultCode == RESULT_OK) {
            // Taranan davet eden cihaz ID’si
            String inviterDeviceId = data.getStringExtra(QRScannerActivity.EXTRA_SCANNED_DATA);
            // Kayıt işlemini başlat
            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceId", inviterDeviceId);
            // profile objesini uygun şekilde oluşturun (örneğin kullanıcı adı vs.)
            Map<String,Object> profile = new HashMap<>();
            EditText usernameInput = findViewById(R.id.username_register);
            String username = usernameInput.getText().toString().trim();
            profile.put("username", username);
            payload.put("profile", profile);
                    functions.getHttpsCallable("completeRegistration")
                    .call(payload)
                    .addOnSuccessListener(result -> {
                        privateCom.sendResponseToBluetoothDevice(this, String.valueOf(resultCode));
                        Helpers.showToastSafe(this, "Registration completed on-chain");
                        navigateToWelcome();
                    })
                    .addOnFailureListener(e -> {
                        Helpers.showToastSafe(this, "Registration failed: " + e.getMessage());
                    });
        }
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
     * Handles the long click event to generate and display a device-specific QR code.
     * Also listens for database changes and navigates accordingly.
     */
    @SuppressLint("HardwareIds")
    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT})
    private void handleLongClickForQRCode() {
        generatedQRCode = Settings.Secure.getString(this.getContentResolver(), Settings.Secure.ANDROID_ID); // Save the generated QR code
        // Check user authentication status
        if (mAuth.getCurrentUser() != null) {
            navigateToWelcome();
        } else {
            permissionHelper.requestBluetooth();
            privateCom.enableBluetoothAndMakeDiscoverable(this,60);
            showQRCodePopup(generatedQRCode);

            if (response == null){
                try {
                    response = PrivateCom.receiveDataBlocking();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                // ToDo:WaitingResponse
                // ToDo:onResponse(startActivity);
            }
        }
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
     * Navigates to the Welcome activity for registered users.
     */
    private void navigateToWelcome() {
        Intent intent = new Intent(this, Welcome.class);
        startActivity(intent);
        finish();
    }
    /**
     * Opens the Kurmes activity for real-time image recognition if the user is unregistered.
     */
    private void navigateToKurmes() {
        Intent intent = null; // Navigate to Kurmes activity
        if (isRegistered) {
            intent = new Intent(this, SoulsManagerActivity.class);
        }else{
            intent = new Intent(this, Kurmes.class);
        }
        startActivity(intent);
        finish();
    }
    /**
     * Navigates to the Register activity.
     */
    private void navigateToRegister() {
        Intent intent = new Intent(this, Register.class);
        startActivity(intent);
        finish();
    }
    /**
     * Navigates to the Login activity.
     */
    private void navigateToLogin() {
        Intent intent = new Intent(this, Login.class);
        startActivity(intent);
        finish();
    }

    public void Quit(){
        FirebaseAuth.getInstance().signOut();
        System.out.println("User logged out successfully.");
    }
}
