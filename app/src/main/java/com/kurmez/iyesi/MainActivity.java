package com.kurmez.iyesi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.RequiresPermission;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;

import com.google.android.play.core.integrity.IntegrityManager;
import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.IntegrityServiceException;
import com.google.android.play.core.integrity.IntegrityTokenRequest;
import com.google.android.play.core.integrity.model.IntegrityErrorCode;

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

// import com.kurmez.iyesi.kayra.appCheck.GmsIntegrityPreflight;

import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;
import com.kurmez.iyesi.kurmes.utilities.helper.PermissionHelper;
import com.kurmez.iyesi.umay.Welcome;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * MainActivity
 * - Preflight (GMS + Play Store + tek atış Play Integrity). Başarısızsa kullanıcıyı yönlendir.
 * - Firebase App Check warm-up
 * - Auth (mevcut kullanıcı → refresh; yoksa anon giriş)
 * - QR/BT akışı
 * - health_check callable opsiyonel
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int MAX_CLICKS = 10;
    private static final int SCAN_QR_REQUEST_CODE = 1001;
    private static final long CLOUD_PROJECT_NUMBER = 238523750447L; // Play Integrity
    private ProgressBar progress;

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

    private volatile boolean hasAppCheckToken = false;
    private volatile boolean hasAuthIdToken  = false;

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

        // PermissionHelper
        permissionHelper = new PermissionHelper();
        permissionHelper.setActivity(this);
        permissionHelper.setCallback(permissionCallback);
        permissionHelper.initialize();

        // Preflight
        preflightIntegrityOrPrompt();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    // =============================================================================================
    // Preflight
    // =============================================================================================

    private void preflightIntegrityOrPrompt() {
        if (!isGmsOk(this)) {
            Log.e(TAG, "GMS not available. Opening Play Services page.");
            showPlayEnvAdvice("Google Play Hizmetleri uygun değil");
            return; // dialog butonları üzerinden finish
        }

        if (!isPlayStoreOk(this)) {
            Log.e(TAG, "Play Store missing/disabled. Opening Play Store page.");
            showPlayEnvAdvice("Google Play Store kurulu değil / devre dışı");
            return; // dialog butonları üzerinden finish
        }

        requestIntegrityPreflight(this, new IntegrityPreflightCallback() {
            @Override public void onOk() {
                Log.i(TAG, "Integrity preflight OK. Proceeding to App Check warm-up...");
                warmUpAppCheckThenInitUiAndAuth();
            }

            @Override public void onFail(Throwable err, Integer code) {
                String reason;
                if (err instanceof IntegrityServiceException) {
                    int c = ((IntegrityServiceException) err).getErrorCode();
                    reason = "IntegrityServiceException: " + c;
                    Log.e(TAG, "Integrity preflight failed. code=" + c, err);

                    if (c == IntegrityErrorCode.PLAY_STORE_NOT_FOUND) {
                        showPlayEnvAdvice("Play Store bulunamadı / resmi sürüm değil (kod -2)");
                    } else if (c == IntegrityErrorCode.NONCE_IS_NOT_BASE64) {
                        showPlayEnvAdvice("Nonce formatı hatalı: web-safe base64 (no wrap, no padding) kullanın.");
                    } else {
                        showPlayEnvAdvice("Play Integrity başarısız: kod=" + c);
                    }
                } else {
                    reason = err == null ? "unknown" : err.getMessage();
                    Log.e(TAG, "Integrity preflight failed: " + reason, err);
                    showPlayEnvAdvice("Play Integrity başarısız: " + reason);
                }
            }
        });
        setLoading(false);
    }
    private void setLoading(boolean state) {
        runOnUiThread(() -> {
            if (progress == null) return;
            if (state) {
                progress.setVisibility(View.VISIBLE);
            } else {
                progress.setVisibility(View.GONE);
            }
        });
    }
    private static boolean isGmsOk(Context ctx) {
        int gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx);
        return gms == ConnectionResult.SUCCESS;
    }

    private static boolean isPlayStoreOk(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo("com.android.vending", 0);
            boolean enabled = ai != null && ai.enabled;
            pm.getPackageInfo("com.android.vending", 0);
            return enabled;
        } catch (Exception e) {
            return false;
        }
    }

    /** Tek atış Play Integrity preflight (nonce: base64 web-safe, no-wrap, no-padding). */
    private void requestIntegrityPreflight(Context ctx, IntegrityPreflightCallback cb) {
        try {
            IntegrityManager mgr = IntegrityManagerFactory.create(ctx);
            String nonce = generateWebSafeBase64Nonce(32); // 32 bytes → 16–500 aralığında
            IntegrityTokenRequest req = IntegrityTokenRequest.builder()
                    .setNonce(nonce)
                    .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                    .build();
            mgr.requestIntegrityToken(req)
                    .addOnSuccessListener(token -> cb.onOk())
                    .addOnFailureListener(err -> {
                        Integer code = (err instanceof IntegrityServiceException)
                                ? ((IntegrityServiceException) err).getErrorCode()
                                : null;
                        cb.onFail(err, code);
                    });
        } catch (Throwable t) {
            cb.onFail(t, null);
        }
    }

    private interface IntegrityPreflightCallback {
        void onOk();
        void onFail(Throwable err, Integer code);
    }

    /** Web-safe Base64 (URL_SAFE | NO_WRAP | NO_PADDING). */
    private static String generateWebSafeBase64Nonce(int numBytes) {
        byte[] seed = new byte[numBytes];
        new SecureRandom().nextBytes(seed);
        // URL-safe, no wrap, no padding → Play Integrity'nin istediği format.
        return Base64.encodeToString(seed,
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    // =============================================================================================
    // App Check + Auth + UI
    // =============================================================================================

    private void warmUpAppCheckThenInitUiAndAuth() {
        warmUpAppCheck()
                .addOnSuccessListener(appCheckToken -> {
                    hasAppCheckToken = (appCheckToken != null && appCheckToken.getToken() != null);
                    Log.d(TAG, "AppCheck warm-up OK? " + hasAppCheckToken +
                            " exp=" + (appCheckToken != null ? appCheckToken.getExpireTimeMillis() : -1));

                    // Auth
                    mAuth = FirebaseAuth.getInstance();
                    user = mAuth.getCurrentUser();

                    if (user != null) {
                        user.reload()
                                .addOnSuccessListener(__ ->
                                        user.getIdToken(true)
                                                .addOnSuccessListener(tokenResult -> {
                                                    idToken = tokenResult.getToken();
                                                    hasAuthIdToken = (idToken != null && !idToken.isEmpty());
                                                    Log.d(TAG, "Auth ID token ready? " + hasAuthIdToken);
                                                    maybeStartHealthCheck();
                                                })
                                                .addOnFailureListener(e -> Log.e(TAG, "getIdToken(refresh) failed", e)))
                                .addOnFailureListener(e -> Log.e(TAG, "user.reload failed", e));
                    } else {
                        mAuth.signInAnonymously()
                                .addOnSuccessListener(res -> {
                                    FirebaseUser u = mAuth.getCurrentUser();
                                    if (u == null) {
                                        Log.e(TAG, "Anon sign-in success but user == null");
                                        showPlayEnvAdvice("Anon sign-in user null");
                                        return;
                                    }
                                    u.getIdToken(true)
                                            .addOnSuccessListener(token -> {
                                                idToken = token.getToken();
                                                hasAuthIdToken = (idToken != null && !idToken.isEmpty());
                                                Log.d(TAG, "Auth ID token (anon) ready? " + hasAuthIdToken);
                                                maybeStartHealthCheck();
                                            })
                                            .addOnFailureListener(e -> {
                                                Log.e(TAG, "Anon getIdToken failed", e);
                                                showPlayEnvAdvice("ID token alınamadı (anon).");
                                            });
                                })
                                .addOnFailureListener(e -> {
                                    Log.e(TAG, "Anon sign-in fail", e);
                                    showPlayEnvAdvice("Anon giriş başarısız.");
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
                    Log.e(TAG, "AppCheck warm-up FAILED", e);
                    showPlayEnvAdvice("AppCheck warm-up başarısız: " + (e.getMessage() == null ? "unknown" : e.getMessage()));
                });
    }

    public static Task<AppCheckToken> warmUpAppCheck() {
        FirebaseAppCheck ac = FirebaseAppCheck.getInstance();
        return ac.getAppCheckToken(false)
                .continueWithTask(t -> t.isSuccessful() ? Tasks.forResult(t.getResult())
                        : ac.getAppCheckToken(true));
    }

    private void maybeStartHealthCheck() {
        Log.d(TAG, "maybeStartHealthCheck hasAppCheckToken=" + hasAppCheckToken +
                " hasAuthIdToken=" + hasAuthIdToken);
        if (!hasAppCheckToken || !hasAuthIdToken) return;
        sendStartupHealthCheck();
    }

    private void sendStartupHealthCheck() {
        if (functions == null) functions = FirebaseFunctions.getInstance();

        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "health_check");
        payload.put("ts", System.currentTimeMillis());
        payload.put("note", "startup_warmup");

        Log.d(TAG, "Calling healthCheck with hasAppCheckToken=" + hasAppCheckToken +
                ", hasAuthIdToken=" + hasAuthIdToken + ", idTokenNull=" + (idToken == null));

        functions.getHttpsCallable("healthCheck")
                .call(payload)
                .addOnSuccessListener((HttpsCallableResult r) ->
                        Log.i(TAG, "health_check callable OK: " + r.getData()))
                .addOnFailureListener(e -> {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("NOT_FOUND")) {
                        Log.w(TAG, "health_check callable NOT_FOUND (deploy edilmemiş olabilir) — akış devam.");
                    } else {
                        Log.w(TAG, "health_check callable FAIL: " + msg);
                    }
                });
    }

    // =============================================================================================
    // Ortam & Market yardımcıları (dialog leak korumalı)
    // =============================================================================================

    private void safeFinishWithDelay() {
        handler.postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) finish();
        }, 300);
    }

    private void openPlayServicesAndFinish() {
        try { openPlayServices(this); } catch (Throwable ignore) { }
        safeFinishWithDelay();
    }

    private void openPlayStoreAndFinish() {
        try { openPlayStore(this); } catch (Throwable ignore) { }
        safeFinishWithDelay();
    }

    private void openThisAppInPlayStoreAndFinish() {
        try { openThisAppInPlayStore(this); } catch (Throwable ignore) { }
        safeFinishWithDelay();
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

    private void showPlayEnvAdvice(String reason) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this)
                .setTitle("Güncelleme / Düzeltme Gerekli")
                .setMessage(
                        "Google Play ortamında eksik/uyumsuzluk algılandı.\n" +
                                "Neden: " + reason +
                                "\n\nLütfen Google Play Hizmetleri ve Play Store’u güncelleyin veya etkinleştirin."
                )
                .setPositiveButton("Play Hizmetleri", (d, w) -> openPlayServicesAndFinish())
                .setNegativeButton("Play Store", (d, w) -> openPlayStoreAndFinish())
                .setNeutralButton("Bu Uygulama (Store)", (d, w) -> openThisAppInPlayStoreAndFinish())
                .setOnDismissListener(d -> safeFinishWithDelay())
                .show();
    }

    // =============================================================================================
    // QR / Registration / Navigation
    // =============================================================================================

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
        qrImageButton.setBackgroundColor(ContextCompat.getColor(this, android.R.color.transparent));

        builder.setView(qrImageButton);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // =============================================================================================
    // Navigation helpers
    // =============================================================================================

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
