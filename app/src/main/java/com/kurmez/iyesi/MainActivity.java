package com.kurmez.iyesi;

import static com.kurmez.iyesi.kayra.AppCheckTokenProvider.runMembershipGuard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
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

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
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
import com.google.firebase.appcheck.AppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import com.kurmez.iyesi.kayra.QR.QR;
import com.kurmez.iyesi.kayra.QR.QRAdmin;
import com.kurmez.iyesi.kayra.QR.QRScannerActivity;
import com.kurmez.iyesi.kurmes.Kurmes;
import com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.PrivateCom;
import com.kurmez.iyesi.kurmes.utilities.handler.NonceUtils;
import com.kurmez.iyesi.kurmes.utilities.helper.PermissionHelper;
import com.kurmez.iyesi.umay.SokakActivity;
import com.kurmez.iyesi.umay.Welcome;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

@RequiresApi(api = Build.VERSION_CODES.N)
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int MAX_CLICKS = 10;
    private static final int SCAN_QR_REQUEST_CODE = 1001;

    /** Play Integrity Cloud Project Number (GCP Project Number) */
    private static final long CLOUD_PROJECT_NUMBER = 238523750447L;

    /** Debug geliştirirken Play dışı kurulumlara izin ver (App Check Debug ile). */
    private static final boolean DEV_ALLOW_NON_PLAY_INSTALLS = BuildConfig.DEBUG;

    private ProgressBar progress;

    private FirebaseFunctions functions;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseUser user;

    private String idToken;
    private String generatedQRCode;
    private boolean isRegistered = false;

    private final Handler handler = new Handler();
    private Runnable startCameraRunnable;
    private int clickCounter = 0;

    private PermissionHelper permissionHelper;
    private PrivateCom privateCom;
    private String response = null;
    private String role;

    private volatile boolean hasAppCheckToken = false;
    private volatile boolean hasAuthIdToken  = false;
    private static volatile boolean appCheckProviderInstalled = false;
    boolean isUlgen = false;

    private enum PreflightStatus {
        RETRIABLE_INPUT_ERROR,
        ENV_MISSING_OR_OUTDATED,
        TRANSIENT_ERROR,
        UNKNOWN_ERROR
    }
    @RequiresPermission(allOf = {Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT})
    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }

        progress = findViewById(R.id.progress);

        try { FirebaseApp.initializeApp(this); } catch (Throwable ignore) { }

        ensureAppCheckProviderInstalled();

        permissionHelper = new PermissionHelper();
        permissionHelper.setActivity(this);
        permissionHelper.setCallback(new PermissionHelper.Callback() {
            @Override public void onGranted() { Log.d(TAG, "Permissions granted"); }
            @Override public void onDenied()  { Log.w(TAG, "Some permissions denied"); }
        });
        permissionHelper.initialize();

        preflightIntegrityOrPrompt();
        runMembershipGuard(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }


    private void requestIntegrityWithRetry(Consumer<String> onOk, Consumer<Exception> onFail) {
        // Tek kullanımlık ve bağlamsal nonce (Android ID bağlamı)
        final String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        final String nonce = NonceUtils.newContextBoundNonceB64Url(androidId == null ? "" : androidId);

        Log.d(TAG, "Integrity nonce(b64url).len=" + (nonce != null ? nonce.length() : -1));

        IntegrityManager im = IntegrityManagerFactory.create(getApplicationContext());
        IntegrityTokenRequest req = IntegrityTokenRequest.builder()
                .setNonce(nonce)
                .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                .build();

        im.requestIntegrityToken(req)
                .addOnSuccessListener(token -> onOk.accept(token.token()))
                .addOnFailureListener(e -> {
                    if (e instanceof IntegrityServiceException) {
                        int code = ((IntegrityServiceException) e).getErrorCode();
                        Log.w(TAG, "Integrity failed code=" + code + ", retry policy may apply.", e);
                        if (code == IntegrityErrorCode.NONCE_TOO_SHORT) {
                            // Retry: daha uzun nonce ile dene (48 byte)
                            IntegrityTokenRequest retryReq = IntegrityTokenRequest.builder()
                                    .setNonce(NonceUtils.newNonceB64Url(48))
                                    .setCloudProjectNumber(CLOUD_PROJECT_NUMBER)
                                    .build();
                            IntegrityManager im2 = IntegrityManagerFactory.create(getApplicationContext());
                            im2.requestIntegrityToken(retryReq)
                                    .addOnSuccessListener(t -> onOk.accept(t.token()))
                                    .addOnFailureListener(onFail::accept);
                            return;
                        }
                    }
                    onFail.accept(e);
                });
    }

    private PreflightStatus mapIntegrityFailure(Exception e) {
        if (e instanceof IntegrityServiceException) {
            int code = ((IntegrityServiceException) e).getErrorCode();
            if (code == IntegrityErrorCode.NONCE_TOO_SHORT) {
                return PreflightStatus.RETRIABLE_INPUT_ERROR;
            }
            if (code == IntegrityErrorCode.API_NOT_AVAILABLE
                    || code == IntegrityErrorCode.PLAY_STORE_NOT_FOUND
                    || code == IntegrityErrorCode.PLAY_STORE_VERSION_OUTDATED
                    || code == IntegrityErrorCode.GOOGLE_SERVER_UNAVAILABLE) {
                return PreflightStatus.ENV_MISSING_OR_OUTDATED;
            }
            return PreflightStatus.TRANSIENT_ERROR;
        }
        return PreflightStatus.UNKNOWN_ERROR;
    }


    private void preflightIntegrityOrPrompt() {
        setLoading(true);

        Log.i(TAG, "GMS=" + pkgVer(this, "com.google.android.gms") +
                " PlayStore=" + pkgVer(this, "com.android.vending"));

        int gms = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this);
        if (gms != ConnectionResult.SUCCESS) {
            Log.w(TAG, "GMS not available, code=" + gms + " → opening update flow");
            ensureGmsUpToDateOrPrompt();
            return;
        }

        final boolean playOk = isPlayStoreOk(this);
        final boolean installedFromPlay = isInstalledFromPlay(this);

        if (!playOk || !installedFromPlay) {
            if (DEV_ALLOW_NON_PLAY_INSTALLS) {
                Log.w(TAG, "Play ortamı eksik/installer=non-Play ama DEV_ALLOW_NON_PLAY_INSTALLS=true → Integrity atlanıyor.");
                warmUpAppCheckThenInitUiAndAuth();
                return;
            } else {
                Log.w(TAG, "Integrity API erişilemedi veya engellendi (Play ortamı yok/uyumsuz).");
                showPlayEnvAdvice(!playOk ? "Google Play Store kurulu değil / devre dışı" : "Uygulama resmi Play Store’dan yüklenmemiş.");
                setLoading(false);
                return;
            }
        }

        requestIntegrityWithRetry(
                token -> {
                    Log.i(TAG, "Integrity token alındı (preflight OK).");
                    warmUpAppCheckThenInitUiAndAuth();
                },
                err -> {
                    PreflightStatus s = mapIntegrityFailure(err);
                    Log.e(TAG, "Integrity preflight failed: " + s, err);

                    if (DEV_ALLOW_NON_PLAY_INSTALLS &&
                            (s == PreflightStatus.ENV_MISSING_OR_OUTDATED || s == PreflightStatus.TRANSIENT_ERROR)) {
                        Log.w(TAG, "Dev modda Integrity hatası bypass → AppCheck+Auth’a devam.");
                        warmUpAppCheckThenInitUiAndAuth();
                        return;
                    }

                    if (s == PreflightStatus.ENV_MISSING_OR_OUTDATED) {
                        Log.w(TAG, "Integrity API erişilemedi veya engellendi (ENV_MISSING_OR_OUTDATED).");
                        showPlayEnvAdvice("Play ortamı eksik/eski. (Integrity env)");
                    } else if (s == PreflightStatus.TRANSIENT_ERROR) {
                        Log.w(TAG, "Integrity API geçici hata.");
                        showPlayEnvAdvice("Geçici hata: Lütfen tekrar deneyin.");
                    } else if (s == PreflightStatus.RETRIABLE_INPUT_ERROR) {
                        showPlayEnvAdvice("Nonce girdisi hatası tekrarlandı.");
                    } else {
                        showPlayEnvAdvice("Bilinmeyen Integrity hatası.");
                    }
                    setLoading(false);
                }
        );
    }


    private void ensureGmsUpToDateOrPrompt() {
        GoogleApiAvailability.getInstance()
                .makeGooglePlayServicesAvailable(this)
                .addOnCompleteListener(t -> {
                    Log.i(TAG, "GMS update flow result: " + (t.isSuccessful() ? "OK" : "FAIL"));
                    handler.post(this::preflightIntegrityOrPrompt);
                });
    }

    private void setLoading(boolean state) {
        runOnUiThread(() -> {
            if (progress == null) return;
            progress.setVisibility(state ? View.VISIBLE : View.GONE);
        });
    }

    private static boolean isPlayStoreOk(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo("com.android.vending", 0);
            boolean enabled = ai != null && ai.enabled;
            PackageInfo pi = pm.getPackageInfo("com.android.vending", 0);
            return enabled && pi != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String pkgVer(Context c, String pkg) {
        try { return c.getPackageManager().getPackageInfo(pkg, 0).versionName; }
        catch (Exception e) { return "NA"; }
    }

    private static boolean isInstalledFromPlay(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = ctx.getPackageManager().getInstallSourceInfo(ctx.getPackageName());
                String installer = info != null ? info.getInstallingPackageName() : null;
                if (installer == null) installer = info != null ? info.getInitiatingPackageName() : null;
                return "com.android.vending".equals(installer);
            } else {
                String installer = ctx.getPackageManager().getInstallerPackageName(ctx.getPackageName());
                return "com.android.vending".equals(installer);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void maybeStartHealthCheck() {
        Log.d(TAG, "maybeStartHealthCheck hasAppCheckToken=" + hasAppCheckToken + " hasAuthIdToken=" + hasAuthIdToken);
        if (!hasAppCheckToken || !hasAuthIdToken) return;
        sendStartupHealthCheck();
    }

    private void sendStartupHealthCheck() {
        if (functions == null) functions = FirebaseFunctions.getInstance();

        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", "health_check");
        payload.put("ts", System.currentTimeMillis());
        payload.put("note", "startup_warmup");
        Log.i(TAG,"idToken :" + idToken);
        Log.d(TAG, "Calling healthCheck with hasAppCheckToken=" + hasAppCheckToken + ", hasAuthIdToken=" + hasAuthIdToken + ", idTokenNull=" + (idToken == null));

        functions.getHttpsCallable("healthCheck")
                .call(payload)
                .addOnSuccessListener((HttpsCallableResult r) -> Log.i(TAG, "health_check callable OK: " + r.getData()))
                .addOnFailureListener(e -> {
                    String msg = e.getMessage() == null ? "" : e.getMessage();
                    if (msg.contains("NOT_FOUND")) {
                        Log.w(TAG, "health_check callable NOT_FOUND (deploy edilmemiş olabilir) — akış devam.");
                    } else {
                        Log.w(TAG, "health_check callable FAIL: " + msg);
                    }
                });
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private void warmUpAppCheckThenInitUiAndAuth() {
        warmUpAppCheck()
                .addOnSuccessListener(appCheckToken -> {
                    hasAppCheckToken = (appCheckToken != null && appCheckToken.getToken() != null);
                    Log.d(TAG, "AppCheck warm-up OK? " + hasAppCheckToken +
                            " exp=" + (appCheckToken != null ? appCheckToken.getExpireTimeMillis() : -1));

                    mAuth = FirebaseAuth.getInstance();
                    user = mAuth.getCurrentUser();

                    if (user != null) {
                        user.reload().addOnSuccessListener(__ ->
                                        user.getIdToken(true)
                                                .addOnSuccessListener(tokenResult -> {
                                                    idToken = tokenResult.getToken();
                                                    hasAuthIdToken = (idToken != null && !idToken.isEmpty());
                                                    Log.d(TAG, "Auth ID token ready? " + hasAuthIdToken);

                                                    // --- NEW: customClaims -> FCM topic abonelikleri
                                                    try {
                                                        Map<String, Object> claims = tokenResult.getClaims();
                                                        Log.d(TAG,"Role : " + role);
                                                        role = (claims != null && claims.get("role") != null) ? String.valueOf(claims.get("role")) : null;
                                                        String adminPath = (claims != null && claims.get("adminPath") != null) ? String.valueOf(claims.get("adminPath")) : null;
                                                        Log.d(TAG,"Role : " + role);
                                                        // aksan kırp + lower
                                                        Function<String, String> fold = s -> {
                                                            if (s == null) return "";
                                                            String n = Normalizer.normalize(s, Normalizer.Form.NFD);
                                                            return n.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
                                                        };


                                                        String fr = fold.apply(role);
                                                        if (!fr.isEmpty()) {
                                                            isUlgen = fr.equals("ulgen")
                                                                    || fr.equals("vet-admin")
                                                                    || fr.equals("ulgen-vet")
                                                                    || fr.equals("admin-veterinary");
                                                        }

                                                        if (isUlgen) {
                                                            FirebaseMessaging.getInstance()
                                                                    .subscribeToTopic("role.ulgen")
                                                                    .addOnCompleteListener(t ->
                                                                            Log.d(TAG, "Subscribed topic: role.ulgen (ok=" + t.isSuccessful() + ")"));
                                                        }

                                                        if (adminPath != null && !adminPath.trim().isEmpty()) {
                                                            String regionTopic = adminPath.trim()
                                                                    .replace('/', '.')         // TR/ADANA -> TR.ADANA
                                                                    .replace(' ', '_')         // boşluk -> _
                                                                    .replaceAll("[^A-Za-z0-9._-]", ""); // izinli karakterler
                                                            if (!regionTopic.isEmpty()) {
                                                                FirebaseMessaging.getInstance()
                                                                        .subscribeToTopic(regionTopic)
                                                                        .addOnCompleteListener(t ->
                                                                                Log.d(TAG, "Subscribed topic: " + regionTopic + " (ok=" + t.isSuccessful() + ")"));
                                                            }
                                                        }
                                                    } catch (Exception e) {
                                                        Log.w(TAG, "Topic subscribe from claims failed", e);
                                                    }
                                                    // --- /NEW

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

                    functions = FirebaseFunctions.getInstance();
                    db = FirebaseFirestore.getInstance();
                    privateCom = new PrivateCom();

                    ImageButton patiEnterButton = findViewById(R.id.pati_enter);
                    if (patiEnterButton != null) {
                        patiEnterButton.setOnClickListener(v -> {
                            clickCounter++;
                            if (startCameraRunnable != null) handler.removeCallbacks(startCameraRunnable);
                            Log.d(TAG, "pati_enter clicked → " + clickCounter);
                            if (user != null) {
                                if (clickCounter >= MAX_CLICKS) {
                                    clickCounter = 0;
                                    try {
                                        startActivity(new Intent(this, QRAdmin.class));
                                        //permissionHelper.requestBluetooth();
                                        //PrivateCom.connectToBluetoothDevice(this, this::openQRScannerForRegistration);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Bluetooth connect failed", e);
                                    }
                                } else {
                                    QRScannerActivity.launchForResult(this, 2001);
                                }
                            } else {
                                startActivity(new Intent(this, Kurmes.class));
                                //startCameraRunnable = this::openCameraWithDelay;
                                //handler.postDelayed(startCameraRunnable, 500);
                            }
                        });
                        patiEnterButton.setOnLongClickListener(v -> {
                            if (user.isAnonymous()) {
                                startActivity(new Intent(this, Welcome.class));
                            } else {
                                startActivity(new Intent(this, SokakActivity.class));
                            }

                            //openQRScannerForRegistration();
                            //handleLongClickForQRCode();
                            return true;
                        });
                    }

                    Log.d("AUTH", user == null || user.isAnonymous() ? "Kullanıcı yok" : ("Kullanıcı var: " + user.getUid()));
                    setLoading(false);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "AppCheck warm-up FAILED", e);
                    showPlayEnvAdvice("AppCheck warm-up başarısız: " + (e.getMessage() == null ? "unknown" : e.getMessage()));
                    setLoading(false);
                });
    }

    // In MainActivity.java
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // Important: update the current intent
//        handleRouteIntent(intent);
    }


    public static Task<AppCheckToken> warmUpAppCheck() {
        FirebaseAppCheck ac = FirebaseAppCheck.getInstance();
        return ac.getAppCheckToken(false)
                .continueWithTask(t -> t.isSuccessful() ? Tasks.forResult(t.getResult())
                        : ac.getAppCheckToken(true));
    }

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
    private void handleLongClickForQRCode() {
        generatedQRCode = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);

        if (mAuth != null && mAuth.getCurrentUser() != null) {
            navigateToWelcome();
            return;
        }

        try { permissionHelper.requestBluetooth(); } catch (Throwable t) { Log.w(TAG, "requestBluetooth warn", t); }
        try { privateCom.enableBluetoothAndMakeDiscoverable(this, 60); } catch (Throwable t) { Log.w(TAG, "enableBluetoothAndMakeDiscoverable warn", t); }

        showQRCodePopup(generatedQRCode);

        new Thread(() -> {
            try {
                response = PrivateCom.receiveDataBlocking();
                Log.d(TAG, "BT received: " + response);
                runOnUiThread(() -> {
                    if (response != null && response.equals("success")) {
                        navigateToWelcome();
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "Bluetooth receive failed", e);
            }
        }).start();
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

    private void navigateToWelcome() {
        startActivity(new Intent(this, Welcome.class));
        finish();
    }
    public void openQRScannerForRegistration(@Nullable int SCAN_QR_REQUEST_CODE) {
        Intent intent = new Intent(this, QRScannerActivity.class);
        startActivityForResult(intent, SCAN_QR_REQUEST_CODE);
        finish();
    }

    private void navigateToKurmes() {
        Intent intent = isRegistered
                ? new Intent(this, SoulsManagerActivity.class)
                : new Intent(this, Kurmes.class);
        startActivity(intent);
        finish();
    }

    private static final SecureRandom RAND = new SecureRandom();

    private static String newIntegrityNonce(int numBytes) {
        if (numBytes < 16) numBytes = 32;
        byte[] buf = new byte[numBytes];
        RAND.nextBytes(buf);
        return Base64.encodeToString(buf, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String buildBoundNonce() {
        try {
            String payload = "app=" + BuildConfig.APPLICATION_ID +
                    "&uid=" + (FirebaseAuth.getInstance().getUid() == null ? "anon" : FirebaseAuth.getInstance().getUid()) +
                    "&ts=" + System.currentTimeMillis() +
                    "&rand=" + RAND.nextLong();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            Log.w(TAG, "buildBoundNonce fallback to random", e);
            return newIntegrityNonce(32);
        }
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

    private void ensureAppCheckProviderInstalled() {
        if (appCheckProviderInstalled) return;
        try {
            FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
            if (BuildConfig.DEBUG) {
                try {
                    Class<?> cls = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory");
                    Object factory = cls.getMethod("getInstance").invoke(null);
                    appCheck.installAppCheckProviderFactory((AppCheckProviderFactory) factory);
                    Log.i(TAG, "AppCheck provider = Debug (reflection)");
                } catch (Throwable t) {
                    Log.w(TAG, "DebugAppCheckProviderFactory yok; PlayIntegrity'ye düşülüyor.", t);
                    appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
                    Log.i(TAG, "AppCheck provider = PlayIntegrity (fallback in debug)");
                }
            } else {
                appCheck.installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance());
                Log.i(TAG, "AppCheck provider = PlayIntegrity");
            }
            appCheckProviderInstalled = true;
        } catch (Throwable t) {
            Log.w(TAG, "AppCheck provider install failed (will rely on default)", t);
        }
    }
}
