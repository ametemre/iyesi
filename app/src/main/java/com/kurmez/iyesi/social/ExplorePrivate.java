// ExplorePrivate.java
package com.kurmez.iyesi.social;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sahiplendirme.Soul;
import com.kurmez.iyesi.social.Content;
import com.kurmez.iyesi.utilities.adapters.ContentAdapter;

import java.util.ArrayList;
import java.util.List;

public class ExplorePrivate extends AppCompatActivity {
    private static final String TAG = "ExplorePrivate";

    private RecyclerView recyclerView;
    private ContentAdapter adapter;
    private final List<Content> contentList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_private);

        // 1) RecyclerView + Adapter kurulumu
        recyclerView = findViewById(R.id.recycler_private_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(contentList, this);
        recyclerView.setAdapter(adapter);

        // 2) Firebase DB referansı: "Pending/Companion"
        DatabaseReference pendingRef = FirebaseDatabase
                .getInstance()
                .getReference()
                .child("Pending")
                .child("Companion");

        // 3) Değişiklikleri dinle
        pendingRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "onDataChange çağrıldı");
                Log.d(TAG, "snapshot.exists(): " + snapshot.exists());
                Log.d(TAG, "child count: " + snapshot.getChildrenCount());
                contentList.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    Soul soul = child.getValue(Soul.class);
                    Log.d(TAG, "Okunan Soul: " + soul);
                    if (soul != null) {
                        Content item = new Content(
                                soul.getImageResId(),
                                "Tür: " + soul.getSpecies(),
                                0, 0, true
                        );
                        contentList.add(item);
                    }
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Veri okunamadı", error.toException());
            }
        });

        // 4) Profil header tıklaması
        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> {
            startActivity(new Intent(ExplorePrivate.this, Profile.class));
        });
    }
}
