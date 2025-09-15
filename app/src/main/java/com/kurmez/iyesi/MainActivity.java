package com.kurmez.iyesi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
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

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import com.google.firebase.firestore.FirebaseFirestore;

import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import com.kurmez.iyesi.kayra.appCheck.GmsIntegrityPreflight;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;
import com.kurmez.iyesi.kurmes.utilities.helper.PermissionHelper;
import com.kurmez.iyesi.umay.Welcome;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * MainActivity
 * - GMS/Installer preflight (yalnızca log; UI uyarısı AppCheck başarısızsa)
 * - Firebase App Check warm-up (token zorunlu)
 * - Auth (mevcut kullanıcı → refresh; yoksa anon giriş)
 * - QR/BT akışı (10 tık ile tarayıcı; uzun basınca cihaz QR)
 * - Kayıt/rol durumuna göre yönlendirme
 * - health_check callable opsiyonel (deploy edilmemişse akışı bozmaz)
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int MAX_CLICKS = 10;
    private static final int SCAN_QR_REQUEST_CODE = 1001;

    private FirebaseFunctions functions;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseUser user;

    private String idToken;
    private String generatedQRCode;
    private boolean isRegistered = false; // TODO: Gerçek kayıt kontrolüne bağla

    private final Handler handler = new Handler();
    private Runnable startCameraRunnable;
    private int clickCounter = 0;

    private PermissionHelper permissionHelper;
    private PrivateCom privateCom;
    private String response = null;

    private final PermissionHelper.Callback permissionCallback = new PermissionHelper.Callback() {
        @Override public void onGranted() { Log.d(TAG, "Permissions granted"); }
        @Override public void onDenied()  { Log.w(TAG, "Some permissions denied"); }
    };

    @SuppressLint("MissingPermission")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Firebase init (idempotent)
        try { FirebaseApp.initializeApp(this); } catch (Throwable ignore) { }

        // --- PermissionHelper: registerForActivityResult STARTED olmadan önce ---
        permissionHelper = new PermissionHelper();
        permissionHelper.setActivity(this);
        permissionHelper.setCallback(permissionCallback);
        permissionHelper.initialize();

        // --- Preflight: SADECE LOG (UI uyarısı göstermiyoruz) ---
        GmsIntegrityPreflight.Result pf = GmsIntegrityPreflight.run(this);
        Log.i("AppCheckPF",
                "installer=" + safeInstaller(getPackageName()) +
                        " gms=" + pf.ok + " reason=" + pf.reason +
                        " uid=" + android.os.Process.myUid());

        // --- App Check warm-up (başarısızsa uyar ve çık) ---
        warmUpAppCheck()
                .addOnSuccessListener(appCheckToken -> {
                    Log.d(TAG, "AppCheck warm-up OK. exp=" + appCheckToken.getExpireTimeMillis());

                    // Auth
                    mAuth = FirebaseAuth.getInstance();
                    user = mAuth.getCurrentUser();

                    if (user != null) {
                        user.reload()
                                .addOnSuccessListener(__ ->
                                        user.getIdToken(true)
                                                .addOnSuccessListener(tokenResult -> {
                                                    idToken = tokenResult.getToken();
                                                    Log.d(TAG, "ID Token (refresh) var mı? " + (idToken != null));
                                                    sendStartupHealthCheck(); // opsiyonel
                                                })
                                                .addOnFailureListener(e ->
                                                        Log.e(TAG, "getIdToken(refresh) failed: " + e)))
                                .addOnFailureListener(e ->
                                        Log.e(TAG, "user.reload failed: " + e));
                    } else {
                        mAuth.signInAnonymously()
                                .addOnSuccessListener(res -> {
                                    FirebaseUser u = mAuth.getCurrentUser();
                                    if (u == null) {
                                        Log.e(TAG, "Anon sign-in success but user == null");
                                        fatalNoTokenAndExit("Anon sign-in user null");
                                        return;
                                    }
                                    u.getIdToken(true)
                                            .addOnSuccessListener(token -> {
                                                idToken = token.getToken();
                                                Log.d(TAG, "ID Token (anon) var mı? " + (idToken != null));
                                                sendStartupHealthCheck(); // opsiyonel
                                            })
                                            .addOnFailureListener(e -> {
                                                Log.e(TAG, "Anon getIdToken failed: " + e);
                                                fatalNoTokenAndExit("ID token alınamadı (anon).");
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Anon sign-in fail: " + e);
                                    fatalNoTokenAndExit("Anon giriş başarısız.");
                                });
                    }

                    // Hizmetler
                    functions = FirebaseFunctions.getInstance();
                    db = FirebaseFirestore.getInstance();
                    privateCom = new PrivateCom();

                    // UI
                    ImageButton patiEnterButton = findViewById(R.id.pati_enter);

                    patiEnterButton.setOnClickListener(v -> {
                        clickCounter++;
                        if (startCameraRunnable != null) handler.removeCallbacks(startCameraRunnable);
                        Log.d(TAG, "pati_enter clicked → " + clickCounter);

                        if (clickCounter >= MAX_CLICKS) {
                            clickCounter = 0;
                            try {
                                permissionHelper.requestBluetooth();
                                PrivateCom.connectToBluetoothDevice(this, this::openQRScannerForRegistration);
                            } catch (Exception e) {
                                Log.e(TAG, "Bluetooth connect failed", e);
                            }
                        } else {
                            startCameraRunnable = this::openCameraWithDelay;
                            handler.postDelayed(startCameraRunnable, 500);
                        }
                    });

                    patiEnterButton.setOnLongClickListener(v -> {
                        handleLongClickForQRCode();
                        return true;
                    });

                    Log.d("AUTH", user == null ? "Kullanıcı yok" : ("Kullanıcı var: " + user.getUid()));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "AppCheck warm-up FAILED: " + e);
                    // YALNIZCA AppCheck başarısızsa kullanıcıyı yönlendir
                    showPlayEnvAdvice("AppCheck warm-up başarısız: " + (e.getMessage() == null ? "unknown" : e.getMessage()));
                    fatalNoTokenAndExit("AppCheck token alınamadı.");
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    // -------- Helpers --------

    private String safeInstaller(String pkg) {
        try { return getPackageManager().getInstallerPackageName(pkg); }
        catch (Throwable t) { return "unknown"; }
    }

    private void showPlayEnvAdvice(String reason) {
        new AlertDialog.Builder(this)
                .setTitle("Güncelleme Önerisi")
                .setMessage(
                        "Google Play ortamında eksik/uyumsuzluk algılandı.\nNeden: " + reason +
                                "\n\nLütfen Google Play Hizmetleri ve Play Store’u güncelleyin."
                )
                .setPositiveButton("Play Hizmetleri", (d, w) -> openPlayServices(this))
                .setNegativeButton("Play Store", (d, w) -> openPlayStore(this))
                .setNeutralButton("Bu Uygulama (Store)", (d, w) -> openThisAppInPlayStore(this))
                .show();
    }

    public static Task<AppCheckToken> warmUpAppCheck() {
        FirebaseAppCheck ac = FirebaseAppCheck.getInstance();
        return ac.getAppCheckToken(false)
                .continueWithTask(t -> t.isSuccessful() ? Tasks.forResult(t.getResult())
                        : ac.getAppCheckToken(true));
    }

    /**
     * Opsiyonel health_check. CF’de "healthCheck" callable henüz yoksa akışı bozmaz.
     */
    private void sendStartupHealthCheck() {
        if (functions == null) functions = FirebaseFunctions.getInstance();

        if (idToken == null || idToken.isEmpty()) {
            // Uygulama kuralın: token yoksa çalışmasın.
            fatalNoTokenAndExit("ID token yok (health_check atlanıyor).");
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "health_check");
        payload.put("ts", System.currentTimeMillis());
        payload.put("note", "startup_warmup");

        functions.getHttpsCallable("healthCheck")
                .call(payload)
                .addOnSuccessListener((HttpsCallableResult r) ->
                        Log.i(TAG, "health_check callable OK: " + r.getData()))
                .addOnFailureListener(e -> {
                    // NOT_FOUND: callable deploy edilmemiş → sadece bilgilendir
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("NOT_FOUND")) {
                        Log.w(TAG, "health_check callable NOT_FOUND (deploy edilmemiş olabilir) — akış devam.");
                    } else {
                        Log.w(TAG, "health_check callable FAIL: " + msg);
                    }
                });
    }

    private void fatalNoTokenAndExit(String msg) {
        Log.e(TAG, "FATAL: " + msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        openThisAppInPlayStore(this); // kullanıcıya seçenek sun
        finish();
    }

    public static void openPlayServices(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=com.google.android.gms"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.gms"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public static void openPlayStore(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=com.android.vending"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=com.android.vending"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public static void openThisAppInPlayStore(Context ctx) {
        String pkg = ctx.getPackageName();
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=" + pkg))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    // -------- QR / Registration / Navigation --------

    private void openQRScannerForRegistration() {
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SCAN_QR_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            String inviterDeviceId = data.getStringExtra(QRScannerActivity.EXTRA_SCANNED_DATA);

            Map<String, Object> payload = new HashMap<>();
            payload.put("deviceId", inviterDeviceId);

            Map<String, Object> profile = new HashMap<>();
            EditText usernameInput = findViewById(R.id.username_register);
            String username = usernameInput != null ? usernameInput.getText().toString().trim() : "";
            profile.put("username", username);
            payload.put("profile", profile);

            if (functions == null) functions = FirebaseFunctions.getInstance();

            functions.getHttpsCallable("completeRegistration")
                    .call(payload)
                    .addOnSuccessListener(result -> {
                        try {
                            privateCom.sendResponseToBluetoothDevice(this, String.valueOf(resultCode));
                        } catch (Exception e) {
                            Log.w(TAG, "BT response send fail", e);
                        }
                        Helpers.showToastSafe(this, "Registration completed on-chain");
                        isRegistered = true;
                        navigateToWelcome();
                    })
                    .addOnFailureListener(e -> {
                        Helpers.showToastSafe(this, "Registration failed: " + e.getMessage());
                        Log.e(TAG, "completeRegistration fail", e);
                    });
        }
    }

    private void openCameraWithDelay() {
        if (isRegistered) {
            navigateToWelcome();
        } else {
            navigateToKurmes();
        }
    }

    @SuppressLint("HardwareIds")
    @RequiresPermission(allOf = {
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
    })
    private void handleLongClickForQRCode() {
        generatedQRCode = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        if (mAuth != null && mAuth.getCurrentUser() != null) {
            navigateToWelcome();
            return;
        }

        try {
            permissionHelper.requestBluetooth();
        } catch (Throwable t) {
            Log.w(TAG, "requestBluetooth warn", t);
        }

        try {
            privateCom.enableBluetoothAndMakeDiscoverable(this, 60);
        } catch (Throwable t) {
            Log.w(TAG, "enableBluetoothAndMakeDiscoverable warn", t);
        }

        showQRCodePopup(generatedQRCode);

        if (response == null) {
            try {
                response = PrivateCom.receiveDataBlocking(); // gerekirse background thread'e al
                Log.d(TAG, "BT received: " + response);
                // TODO: response işlenip uygun ekrana yönlendirme
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth receive failed", e);
            }
        }
    }

    private void showQRCodePopup(String deviceId) {
        Bitmap qrBitmap;
        try {
            BarcodeEncoder barcodeEncoder = new BarcodeEncoder();
            qrBitmap = barcodeEncoder.encodeBitmap(deviceId, BarcodeFormat.QR_CODE, 400, 400);
        } catch (WriterException e) {
            Toast.makeText(this, "Failed to generate QR code", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Device QR Code");

        ImageButton qrImageButton = new ImageButton(this);
        qrImageButton.setImageBitmap(qrBitmap);
        qrImageButton.setBackgroundColor(getResources().getColor(android.R.color.transparent));

        builder.setView(qrImageButton);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // -------- Navigation helpers --------

    private void navigateToWelcome() {
        startActivity(new Intent(this, Welcome.class));
        finish();
    }

    private void navigateToKurmes() {
        Intent intent = isRegistered
                ? new Intent(this, SoulsManagerActivity.class)
                : new Intent(this, Kurmes.class);
        startActivity(intent);
        finish();
    }

    private void navigateToRegister() {
        startActivity(new Intent(this, Register.class));
        finish();
    }

    private void navigateToLogin() {
        startActivity(new Intent(this, Login.class));
        finish();
    }

    public void Quit() {
        try {
            FirebaseAuth.getInstance().signOut();
            Log.i(TAG, "User logged out successfully.");
        } catch (Throwable t) {
            Log.w(TAG, "SignOut warn", t);
        }
    }
}
