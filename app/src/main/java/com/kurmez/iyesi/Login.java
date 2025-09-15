package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.kurmez.iyesi.kayra.PlayStoreFixer;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.umay.Welcome;

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

        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            usernameField.setError("Valid email is required");
            usernameField.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(password)) {
            passwordField.setError("Password is required");
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
                                Helpers.showToastSafe(Login.this, "App integrity doğrulaması başarısız. Tekrar deneyin.");
// Integrity erişilemedi → kullanıcıyı yönlendir
                                PlayStoreFixer.openPlayStoreForPackage(this, "com.android.vending"); // Play Store sayfası

                                submitButton.setEnabled(true);
                            });
                });

    }

    private void doSignIn(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    submitButton.setEnabled(true);
                    if (task.isSuccessful()) {
                        Helpers.showToastSafe(Login.this, "Login successful");
                        startActivity(new Intent(Login.this, Welcome.class));
                        finish();
                    } else {
                        String msg = task.getException() != null
                                ? task.getException().getMessage()
                                : "Unknown error";
                        Helpers.showToastSafe(Login.this, "Login failed: " + msg);
                    }
                });
    }
}
