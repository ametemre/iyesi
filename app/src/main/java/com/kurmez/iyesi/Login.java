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
        FirebaseAppCheck.getInstance();

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
        FirebaseAppCheck.getInstance().getAppCheckToken(false)
                .addOnSuccessListener(token -> {
                    Log.d(TAG, "AppCheck token hazır (len=" + (token != null ? token.getToken().length() : 0) + ")");
                    // 2) Token hazır → Auth girişine başla
                    doSignIn(email, password);
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "AppCheck token alınamadı", e);
                    // Enforce açıksa giriş başarısız olacaktır; kullanıcıya net mesaj ver
                    Helpers.showToastSafe(Login.this,
                            "App integrity doğrulaması başarısız. Lütfen tekrar deneyin.");
                    submitButton.setEnabled(true);
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
