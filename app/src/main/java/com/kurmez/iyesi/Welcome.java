package com.kurmez.iyesi;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class Welcome extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome_v2);

        ImageView imgWelcome = findViewById(R.id.img_welcome);
        ListView listView = findViewById(R.id.list_view);

        // Set click listener
        imgWelcome.setOnClickListener(v -> {
            // Action for short click
            startActivity(new Intent(Welcome.this, ExplorePrivate.class));
            Toast.makeText(Welcome.this, "Image Clicked!", Toast.LENGTH_SHORT).show();
        });

        // Set long click listener
        imgWelcome.setOnLongClickListener(v -> {
            // Action for long click
            startActivity(new Intent(Welcome.this, Explore.class));
            Toast.makeText(Welcome.this, "Image Long-Clicked!", Toast.LENGTH_SHORT).show();
            return true; // Returning true indicates the long click was handled
        });

        // Sample data for the ListView
        List<PetCompanion> companions = new ArrayList<>();
        companions.add(new PetCompanion("https://example.com/image1.jpg", "Golden Retriever", "01/01/2023", "Istanbul", "Ahmet"));
        companions.add(new PetCompanion("https://example.com/image2.jpg", "Siamese Cat", "15/02/2023", "Ankara", "Mehmet"));
        companions.add(new PetCompanion("https://example.com/image3.jpg", "German Shepherd", "10/03/2023", "Izmir", "Fatma"));
        companions.add(new PetCompanion("https://example.com/image4.jpg", "Persian Cat", "20/04/2023", "Bursa", "Ece"));
        companions.add(new PetCompanion("https://example.com/image5.jpg", "Labrador Retriever", "05/05/2023", "Antalya", "Hasan"));
        companions.add(new PetCompanion("https://example.com/image6.jpg", "Ragdoll", "12/06/2023", "Adana", "Kemal"));
        companions.add(new PetCompanion("https://example.com/image7.jpg", "Bulldog", "18/07/2023", "Gaziantep", "Leyla"));
        companions.add(new PetCompanion("https://example.com/image8.jpg", "Beagle", "25/08/2023", "Konya", "Zeynep"));
        companions.add(new PetCompanion("https://example.com/image9.jpg", "Maine Coon", "30/09/2023", "Samsun", "Yusuf"));
        companions.add(new PetCompanion("https://example.com/image10.jpg", "Shih Tzu", "10/10/2023", "Diyarbakır", "Seda"));
        companions.add(new PetCompanion("https://example.com/image11.jpg", "Chow Chow", "20/11/2023", "Trabzon", "Cem"));
        companions.add(new PetCompanion("https://example.com/image12.jpg", "Siberian Husky", "01/12/2023", "Van", "Ali"));

        // Create and set a custom adapter
        CompanionAdapter adapter = new CompanionAdapter(this, companions);
        listView.setAdapter(adapter);

        // Handle item click events
        listView.setOnItemClickListener((parent, view, position, id) -> {
            // Get the selected companion
            PetCompanion selectedCompanion = companions.get(position);
            Toast.makeText(this, "Selected: " + selectedCompanion.getBreed(), Toast.LENGTH_SHORT).show();

            // Navigate to Companion activity with the selected item's data
            Intent intent = new Intent(Welcome.this, Companion.class);
            intent.putExtra("species", selectedCompanion.getBreed());
            intent.putExtra("foundDate", selectedCompanion.getFoundDate());
            intent.putExtra("foundPlace", selectedCompanion.getFoundLocation());
            intent.putExtra("photoUrl", selectedCompanion.getImageResId());
            intent.putExtra("profileId", selectedCompanion.getFinderName());
            startActivity(intent);
        });
    }
}
