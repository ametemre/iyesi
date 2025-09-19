package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
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

import com.kurmez.iyesi.kayra.appCheck.PlayEnvDiagnostics;
import com.kurmez.iyesi.kayra.appCheck.TopActivity;
import com.kurmez.iyesi.umay.Welcome;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MainActivity — Merkezi başlangıç orkestratörü
 *
 * NOT:
 * - App Check / Play Integrity SAĞLAYICI kurulumu burada yapılmaz.
 *   Application sınıfında (AppCheckTokenProvider) halledilir.
 * - Bu Activity sadece:
 *     1) AppCheck token warm-up,
 *     2) Firebase Auth (mevcut/anon),
 *     3) (opsiyonel) health_check callable,
 *     4) UI enable + navigasyon
 *   yönetir.
 *
 * TODO(beta): QR/Bluetooth kayıt akışını burada uzun basma ile açacağız (şimdilik pasif).
 * TODO(nav): Kurmes / SoulsManager koşulunu belirleyip Welcome dışı akışları bağlayacağız.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // --- UI ---
    private ProgressBar progressBar;
    private ImageButton enterButton;

    // --- Firebase / Services ---
    private FirebaseFunctions functions;
    private FirebaseFirestore db;
    private FirebaseAuth mAuth;
    private FirebaseUser user;

    // --- State ---
    private volatile boolean hasAppCheckToken = false;
    private volatile boolean hasAuthIdToken   = false;
    private @Nullable String idToken = null;

    // Tekrarlı çağrıyı engelle
    private final AtomicBoolean healthSent = new AtomicBoolean(false);

    // Dev mod bilgisi
    private final boolean isDevelopmentMode = BuildConfig.DEBUG;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        progressBar = findViewById(R.id.progress);
        enterButton = findViewById(R.id.pati_enter);

        setLoading(true);
        setEnterEnabled(false);

        // Firebase init (idempotent)
        try { FirebaseApp.initializeApp(this); } catch (Throwable ignore) {}

        // Servisler
        functions = FirebaseFunctions.getInstance();
        db        = FirebaseFirestore.getInstance();

        // Ortam preflight — sadece LOG amaçlı; akışı bloke etmez
        try {
            PlayEnvDiagnostics.preflight(this, report -> Log.d("PlayEnvDiag", "preflight: " + report));
        } catch (Throwable t) {
            Log.w(TAG, "preflight threw", t);
        }

        // Tıklama → giriş
        if (enterButton != null) {
            enterButton.setOnClickListener(v -> onEnterButtonClick());
            // TODO(beta): Uzun tık ile QR/Bluetooth kayıt akışını aç (şimdilik pasif)
            // enterButton.setOnLongClickListener(v -> { openQrBluetoothRegistration(); return true; });
            // Uzun tık tanılama kısayolu:
            enterButton.setOnLongClickListener(v -> {
                openDiagnostics();
                return true;
            });
        }

        // AppCheck warm-up → Auth → (opsiyonel) Health → UI enable
        warmUpAppCheck()
                .addOnSuccessListener(token -> {
                    hasAppCheckToken = token != null && token.getToken() != null;
                    Log.d(TAG, "AppCheck warm-up OK? " + hasAppCheckToken +
                            " exp=" + (token != null ? token.getExpireTimeMillis() : -1));
                    initAuth();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "AppCheck warm-up FAILED", e);
                    // Dış katman (AppCheckTokenProvider/PlayEnvDiagnostics) zaten gerekli uyarıları yönetir.
                    // Kullanıcıyı kilitlememek adına yine Auth akışına geçiyoruz:
                    initAuth();
                });
    }

    // =============================================================================================
    // AppCheck
    // =============================================================================================
    public static Task<AppCheckToken> warmUpAppCheck() {
        FirebaseAppCheck ac = FirebaseAppCheck.getInstance();
        return ac.getAppCheckToken(false)
                .continueWithTask(t -> t.isSuccessful()
                        ? Tasks.forResult(t.getResult())
                        : ac.getAppCheckToken(true));
    }

    // =============================================================================================
    // Auth
    // =============================================================================================
    private void initAuth() {
        mAuth = FirebaseAuth.getInstance();
        user  = mAuth.getCurrentUser();

        if (user != null) {
            user.reload()
                    .addOnSuccessListener(__ -> user.getIdToken(true)
                            .addOnSuccessListener(res -> {
                                idToken = res.getToken();
                                hasAuthIdToken = idToken != null && !idToken.isEmpty();
                                Log.d(TAG, "Auth ID token ready? " + hasAuthIdToken);
                                maybeAllReady();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "getIdToken(refresh) failed", e);
                                // Anon fallback’e düş:
                                attemptAnonymousAuth();
                            }))
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "user.reload failed", e);
                        attemptAnonymousAuth();
                    });
        } else {
            attemptAnonymousAuth();
        }
    }

    private void attemptAnonymousAuth() {
        mAuth.signInAnonymously()
                .addOnSuccessListener(r -> {
                    FirebaseUser u = mAuth.getCurrentUser();
                    if (u == null) {
                        hasAuthIdToken = false;
                        maybeAllReady();
                        return;
                    }
                    u.getIdToken(true)
                            .addOnSuccessListener(res -> {
                                idToken = res.getToken();
                                hasAuthIdToken = idToken != null && !idToken.isEmpty();
                                Log.d(TAG, "Auth ID token (anon) ready? " + hasAuthIdToken);
                                maybeAllReady();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Anon getIdToken failed", e);
                                // Dev moddaysak Auth hatasını şiddetle bloklamayalım
                                if (isDevelopmentMode) {
                                    hasAuthIdToken = true;
                                } else {
                                    hasAuthIdToken = false;
                                }
                                maybeAllReady();
                            });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Anon sign-in fail", e);
                    if (isDevelopmentMode) {
                        hasAuthIdToken = true;
                    } else {
                        hasAuthIdToken = false;
                    }
                    maybeAllReady();
                });
    }

    // =============================================================================================
    // Sağlık ping + UI
    // =============================================================================================
    private void maybeAllReady() {
        Log.d(TAG, "maybeAllReady hasAppCheckToken=" + hasAppCheckToken +
                " hasAuthIdToken=" + hasAuthIdToken);

        if (hasAppCheckToken && hasAuthIdToken && healthSent.compareAndSet(false, true)) {
            sendStartupHealthCheck();
        }

        setEnterEnabled(true);
        setLoading(false);
    }

    private void sendStartupHealthCheck() {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("kind", "health_check");
            payload.put("ts", System.currentTimeMillis());
            payload.put("note", "startup_warmup");

            functions.getHttpsCallable("healthCheck")
                    .call(payload)
                    .addOnSuccessListener((HttpsCallableResult r) ->
                            Log.i(TAG, "health_check callable OK: " + r.getData()))
                    .addOnFailureListener(e -> {
                        String msg = e.getMessage() == null ? "" : e.getMessage();
                        if (msg.contains("NOT_FOUND")) {
                            Log.w(TAG, "health_check callable NOT_FOUND — devam.");
                        } else {
                            Log.w(TAG, "health_check callable FAIL: " + msg);
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "health_check skipped", t);
        }
    }

    // =============================================================================================
    // UI helpers
    // =============================================================================================
    private void setLoading(boolean state) {
        runOnUiThread(() -> {
            if (progressBar != null) {
                progressBar.setVisibility(state ? View.VISIBLE : View.GONE);
            }
            if (enterButton != null && state) setEnterEnabled(false);
        });
    }

    private void setEnterEnabled(boolean enabled) {
        runOnUiThread(() -> {
            if (enterButton != null) {
                enterButton.setEnabled(enabled);
                enterButton.setAlpha(enabled ? 1f : .5f);
            }
        });
    }

    private void toast(String m) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
    }

    // =============================================================================================
    // Navigasyon
    // =============================================================================================
    private void onEnterButtonClick() {
        // TODO(nav): İleride koşula göre Kurmes / SoulsManager hedeflerini de bağlayacağız.
        navigateToWelcome();
    }

    private void navigateToWelcome() {
        startActivity(new Intent(this, Welcome.class));
        finish();
    }

    private void openDiagnostics() {
        try {
            startActivity(new Intent(this, TopActivity.class));
        } catch (Throwable t) {
            toast("Tanılama ekranı açılamadı.");
        }
    }

    // =============================================================================================
    // Oturum
    // =============================================================================================
    public void Quit() {
        try {
            FirebaseAuth.getInstance().signOut();
            Log.i(TAG, "User logged out successfully.");
        } catch (Throwable t) {
            Log.w(TAG, "SignOut warn", t);
        }
    }

    // =============================================================================================
    // TODO(beta): QR/Bluetooth kayıt akışı (şimdilik pasif)
    // - Permission/BT helper’lar, ZXing/QR oluşturma ve karşı cihazla eşleşme burada
    //   yeniden etkinleştirilecek.
    // =============================================================================================

    // private void openQrBluetoothRegistration() { /* TODO(beta) */ }
}
