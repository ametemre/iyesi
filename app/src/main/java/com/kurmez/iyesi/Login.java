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

public class Login extends AppCompatActivity {

    // Firebase Authentication instance
    private FirebaseAuth mAuth;

    // Input fields
    private EditText usernameField, passwordField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        // Initialize Firebase Authentication
        mAuth = FirebaseAuth.getInstance();

        // Find input fields
        usernameField = findViewById(R.id.username_login);
        passwordField = findViewById(R.id.password_login);

        // Find the submit ImageView
        ImageView submitButton = findViewById(R.id.img_login);

        // Set click listener for login
        submitButton.setOnClickListener(v -> loginUser());
    }

    /**
     * Logs in the user using Firebase Authentication.
     */
    private void loginUser() {
        // Get input values
        String email = usernameField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();

        // Validate inputs
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

        // Log in user with Firebase Authentication
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // Navigate to Welcome activity
                        Intent intent = new Intent(Login.this, Welcome.class);
                        startActivity(intent);
                        finish();

                        Toast.makeText(Login.this, "Login successful", Toast.LENGTH_SHORT).show();
                    } else {
                        // Show error message
                        Toast.makeText(Login.this, "Login failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }
}
