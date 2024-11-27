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
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

public class Register extends AppCompatActivity {

    // Firebase instances
    private FirebaseAuth mAuth;
    private FirebaseFirestore db;

    // Input fields
    private EditText usernameField, emailField, passwordField, addressField, phoneField;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Initialize Firebase
        mAuth = FirebaseAuth.getInstance();
        db = FirebaseFirestore.getInstance();

        // Find input fields
        usernameField = findViewById(R.id.username_register);
        emailField = findViewById(R.id.e_mail_register);
        passwordField = findViewById(R.id.password_register);
        addressField = findViewById(R.id.adress_register);
        phoneField = findViewById(R.id.phone_number);

        // Find the ImageView (used as a button)
        ImageView registerButton = findViewById(R.id.img_register);

        // Handle single press: Navigate to Login activity
        registerButton.setOnClickListener(v -> {
            Intent intent = new Intent(Register.this, Login.class);
            startActivity(intent);
        });

        // Handle long press: Submit the registration form
        registerButton.setOnLongClickListener(v -> {
            registerUser(); // Call the registration method
            Toast.makeText(Register.this, "Submitting registration form...", Toast.LENGTH_SHORT).show();
            return true; // Consume the long press event
        });
    }

    /**
     * Registers a new user with Firebase Authentication and saves their details to Firestore.
     */
    private void registerUser() {
        // Get input values
        String username = usernameField.getText().toString().trim();
        String email = emailField.getText().toString().trim();
        String password = passwordField.getText().toString().trim();
        String address = addressField.getText().toString().trim();
        String phone = phoneField.getText().toString().trim();

        // Validate inputs
        if (TextUtils.isEmpty(username)) {
            usernameField.setError("Username is required");
            usernameField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(email) || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailField.setError("Valid email is required");
            emailField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(password) || password.length() < 6) {
            passwordField.setError("Password must be at least 6 characters");
            passwordField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(address)) {
            addressField.setError("Address is required");
            addressField.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(phone) || phone.length() < 10) {
            phoneField.setError("Valid phone number is required");
            phoneField.requestFocus();
            return;
        }

        // Register user in Firebase Authentication
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        // Get the registered user
                        FirebaseUser user = mAuth.getCurrentUser();

                        // Save user details to Firestore
                        saveUserToFirestore(user.getUid(), username, email, address, phone);

                        // Navigate to Welcome activity
                        Intent intent = new Intent(Register.this, Welcome.class);
                        startActivity(intent);
                        finish();

                        Toast.makeText(Register.this, "Registration successful", Toast.LENGTH_SHORT).show();
                    } else {
                        // Show error message
                        Toast.makeText(Register.this, "Registration failed: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Saves user details to Firestore.
     *
     * @param userId   The user ID from Firebase Authentication.
     * @param username The username entered by the user.
     * @param email    The email entered by the user.
     * @param address  The address entered by the user.
     * @param phone    The phone number entered by the user.
     */
    private void saveUserToFirestore(String userId, String username, String email, String address, String phone) {
        Map<String, Object> userMap = new HashMap<>();
        userMap.put("username", username);
        userMap.put("email", email);
        userMap.put("address", address);
        userMap.put("phone", phone);

        db.collection("users")
                .document(userId)
                .set(userMap)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(Register.this, "User details saved successfully", Toast.LENGTH_SHORT).show();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(Register.this, "Failed to save user details: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }
}
