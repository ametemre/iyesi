package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.umay.sahiplendirme.Welcome;

import java.util.HashMap;
import java.util.Map;

public class Register extends AppCompatActivity {

    // Firebase
    private FirebaseAuth mAuth;
    private FirebaseFunctions functions;

    // UI
    private EditText usernameField;
    private EditText emailField;
    private EditText passwordField;
    private EditText locationField;
    private EditText phoneField;
    private ImageView  registerButton;

    // Cihaz ID’si (MainActivity’den intent ile gelmeli)
    private String deviceId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Intent extras’tan alınır
        deviceId = getIntent().getStringExtra("deviceId");
        if (deviceId == null) {
            Helpers.showToastSafe(this, "Eksik parameter: deviceId");
            finish();
            return;
        }

        // Firebase init
        mAuth      = FirebaseAuth.getInstance();
        functions  = FirebaseFunctions.getInstance();

        // UI init
        usernameField   = findViewById(R.id.username_register);
        emailField      = findViewById(R.id.e_mail_register);
        passwordField   = findViewById(R.id.password_register);
        locationField   = findViewById(R.id.adress_register);
        phoneField      = findViewById(R.id.phone_register);
        registerButton  = findViewById(R.id.img_register);

        registerButton.setOnClickListener(v -> attemptRegistration());
    }

    private void attemptRegistration() {
        // 1) Alanları al ve doğrula
        String username = usernameField.getText().toString().trim();
        String email    = emailField.getText().toString().trim();
        String password = passwordField.getText().toString();
        String location = locationField.getText().toString().trim();
        String phone    = phoneField.getText().toString().trim();

        if (TextUtils.isEmpty(username)) {
            usernameField.setError("Kullanıcı adı gerekli");
            usernameField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.setError("Geçerli e‑posta gerekli");
            emailField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password) || password.length() < 6) {
            passwordField.setError("Şifre en az 6 karakter olmalı");
            passwordField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(location)) {
            locationField.setError("Konum gerekli");
            locationField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(phone) || phone.length() < 10) {
            phoneField.setError("Geçerli telefon numarası gerekli");
            phoneField.requestFocus();
            return;
        }

        // 2) Firebase Auth ile kullanıcı oluştur
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Toast.makeText(this,
                                "Kayıt başarısız: " + task.getException().getMessage(),
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    FirebaseUser user = mAuth.getCurrentUser();
                    if (user == null) {
                        Toast.makeText(this,
                                "Beklenmedik hata: kullanıcı alınamadı",
                                Toast.LENGTH_LONG).show();
                        return;
                    }

                    // 3) Cloud Function: completeRegistration çağır
                    Map<String,Object> profile = new HashMap<>();
                    profile.put("username", username);
                    profile.put("location", location);
                    profile.put("phone", phone);

                    Map<String,Object> payload = new HashMap<>();
                    payload.put("deviceId", deviceId);
                    payload.put("profile", profile);

                    functions
                            .getHttpsCallable("completeRegistration")
                            .call(payload)
                            .addOnSuccessListener((HttpsCallableResult result) -> {
                                // 4) Token’ı yenileyip Welcome ekranına geç
                                user.getIdToken(true)
                                        .addOnSuccessListener(t -> {
                                            startActivity(new Intent(this, Welcome.class));
                                            finish();
                                        });
                            })
                            .addOnFailureListener(e -> {
                                Toast.makeText(this,
                                        "Sunucu hatası: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            });
                });
    }
}
