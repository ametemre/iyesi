package com.kurmez.iyesi;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.kurmes.utilities.Helpers;

public class Login extends AppCompatActivity {

    private static final String TAG = "Login";
    private FirebaseAuth mAuth;

    private EditText usernameField, passwordField;
    private ImageView submitButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // (Provider Application’da kuruldu; burada sadece instance erişimi)

        mAuth = FirebaseAuth.getInstance();
        usernameField = findViewById(R.id.username_login);
        passwordField = findViewById(R.id.password_login);
        submitButton = findViewById(R.id.img_login);

        submitButton.setOnClickListener(v -> loginUser());
    }

    private void loginUser() {
        String email = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();

        // ÖNCE: Hardcoded "Valid email is required" ve "Password is required"
        // ŞİMDİ: String resource kullanımı
        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            usernameField.setError(getString(R.string.login_error_valid_email_required));
            usernameField.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            passwordField.setError(getString(R.string.login_error_password_required));
            passwordField.requestFocus();
            return;
        }

        // UI spam engeli
        submitButton.setEnabled(false);

        // 1) App Check token’ını (yenilemeden) ılıkça iste
        // değişiklik: getToken(false) + bir kez fallback getToken(true)
        FirebaseAppCheck.getInstance().getToken(false)
                .addOnSuccessListener(t -> {
                    Log.d(TAG, "AppCheck token len=" + (t != null ? t.getToken().length() : 0));
                    doSignIn(email, password);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "getToken(false) FAILED: " + e.getClass().getName() + " / " + e.getMessage(), e);
                    // bir kez daha deneriz (force refresh)
                    FirebaseAppCheck.getInstance().getToken(true)
                            .addOnSuccessListener(t -> { Log.d(TAG, "AppCheck token (force) OK"); doSignIn(email, password); })
                            .addOnFailureListener(e2 -> {
                                Log.e(TAG, "getToken(true) FAILED: " + e2.getMessage(), e2);
                                // ÖNCE: Hardcoded "App integrity doğrulaması başarısız. Tekrar deneyin."
                                // ŞİMDİ: String resource kullanımı
                                Helpers.showToastSafe(Login.this, getString(R.string.login_toast_app_integrity_failed));
// Integrity erişilemedi → kullanıcıyı yönlendir
// Bir tık olayı içinde, görünür Activity bağlamında:
                                Uri uri = Uri.parse("market://details?id=" + getPackageName());
                                Intent i = new Intent(Intent.ACTION_VIEW, uri)
                                        .setPackage("com.android.vending");
                                startActivity(i); // BAL yemez: kullanıcı tıkladı ve app foreground

                                submitButton.setEnabled(true);
                            });
                });

    }

    private void doSignIn(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    submitButton.setEnabled(true);
                    if (task.isSuccessful()) {
                        // ÖNCE: Hardcoded "Login successful"
                        // ŞİMDİ: String resource kullanımı
                        Helpers.showToastSafe(Login.this, getString(R.string.login_toast_successful));
                        startActivity(new Intent(Login.this, MainActivity.class));
                        finish();
                    } else {
                        // ÖNCE: Hardcoded "Unknown error" ve "Login failed: " + msg
                        // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                        String msg = task.getException() != null
                                ? task.getException().getMessage()
                                : getString(R.string.login_error_unknown);
                        Helpers.showToastSafe(Login.this, getString(R.string.login_toast_failed, msg));
                    }
                });
    }
}
