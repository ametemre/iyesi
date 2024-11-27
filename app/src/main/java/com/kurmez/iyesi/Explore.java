package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class Explore extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore);

        // Bind RecyclerView
        RecyclerView recyclerView = findViewById(R.id.recycler_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Fetch public content
        List<Content> publicContent = MockData.getPublicContent();
        ContentAdapter adapter = new ContentAdapter(publicContent, this);
        recyclerView.setAdapter(adapter);

        // Profile Header Click
        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> {
            Intent intent = new Intent(Explore.this, Profile.class);
            startActivity(intent);
        });

        // Update Profile Info
        TextView username = findViewById(R.id.profile_username);
        TextView bio = findViewById(R.id.profile_bio);
        TextView followers = findViewById(R.id.profile_followers);

        // Mock data (replace with real user data)
        username.setText("John Doe");
        bio.setText("Exploring the world with pets!");
        followers.setText("Followers: 200");
    }
}
