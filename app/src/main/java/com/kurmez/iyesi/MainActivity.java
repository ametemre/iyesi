package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends AppCompatActivity {
    private FirebaseAuth mAuth; // Firebase Authentication instance
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        // Initialize Firebase Authentication
        mAuth = FirebaseAuth.getInstance();
        // Handle window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
        // Find the button
        ImageButton button = findViewById(R.id.pati_enter);
        // Button click to check authentication
        button.setOnClickListener(view -> {
            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null) {
                // User is authenticated, navigate to Welcome activity
                startActivity(new Intent(MainActivity.this, Welcome.class));
            } else {
                // User is not authenticated, navigate to Register activity
                startActivity(new Intent(MainActivity.this, Register.class));
            }
            finish(); // Close MainActivity
        });
        // Optional: Long click logic for additional functionality (e.g., QR scanning)
        button.setOnLongClickListener(view -> {
            FirebaseUser currentUser = mAuth.getCurrentUser();
            if (currentUser != null) {
                // User is authenticated, navigate to Welcome activity
                startActivity(new Intent(MainActivity.this, QR.class));
            } else {
                // User is not authenticated, navigate to Register activity
                startActivity(new Intent(MainActivity.this, Register.class));
            }
            finish(); // Close MainActivity
            return true; // Indicate the long click is consumed
        });
    }
}