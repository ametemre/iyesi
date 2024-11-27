package com.kurmez.iyesi;

import android.os.Bundle;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class Profile extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        // Bind views
        TextView username = findViewById(R.id.iye_companion);
        TextView bio = findViewById(R.id.iye_found_Date);
        TextView location = findViewById(R.id.iye_place);
        TextView followers = findViewById(R.id.who);
        ListView contentList = findViewById(R.id.list_view_iye);

        // Fetch and display user details
        username.setText("John Doe");
        bio.setText("Loving all pets!");
        location.setText("New York");
        followers.setText("Followers: 150");

        // Fetch and display user's content
        List<Content> userContent = MockData.getUserContent();
        ContentAdapter adapter = new ContentAdapter(userContent, this);
        contentList.setAdapter(adapter);
    }
}
