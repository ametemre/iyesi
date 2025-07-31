package com.kurmez.iyesi.social;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.sahiplendirme.Soul;
import com.kurmez.iyesi.utilities.Helpers;
import com.kurmez.iyesi.utilities.adapters.ContentAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExplorePrivate extends AppCompatActivity {
    private static final String TAG = "ExplorePrivate";
    List<String> allowedRoles = Arrays.asList("İye", "Körmes", "Ülgen", "Tengri");

    private RecyclerView recyclerView;
    private ContentAdapter adapter;
    private final List<Content> contentList = new ArrayList<>();

    private FirebaseAuth auth;
    private FirebaseFirestore firestore;
    private String userRole;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore_private);

        // 1) Auth & Firestore init
        auth = FirebaseAuth.getInstance();
        firestore = FirebaseFirestore.getInstance();

        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Bu sayfayı görüntülemek için giriş yapmalısınız.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 2) RecyclerView + Adapter kurulumu
        recyclerView = findViewById(R.id.recycler_private_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(contentList, this);
        recyclerView.setAdapter(adapter);

        // 3) Profil header tıklama
        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> {
            startActivity(new Intent(ExplorePrivate.this, Profile.class));
        });
            // 4) Kullanıcı rolünü çek ve gönderileri yükle
            Helpers.getRoleFunction()
                    .addOnSuccessListener(role -> {
                        if (role == null) {
                            // Hata veya rol atanmadı, uyarı göster
                            Toast.makeText(this, "Rol atanmadı!", Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        // Role kontrolü:
                        if (!allowedRoles.contains(role)) {
                            Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_SHORT).show();
                            finish();
                            return;
                        }
                        // 5) Cloud Functions init ve veri çek
                        attachPendingListener(userRole);
                    })
                    .addOnFailureListener(e -> {
                        Toast.makeText(this, "Rol sorgusu hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        finish();
                    });
    }

    private void attachPendingListener(String role) {
        // Realtime DB'deki "Pending/Companion" yolunu kullan
        DatabaseReference pendingRef = FirebaseDatabase
                .getInstance()
                .getReference()
                .child("Pending")
                .child("Companion");

        // Sadece currentRole == userRole kayıtlarını sorgula
        Query query = pendingRef.orderByChild("currentRole").equalTo(role);

        query.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "onDataChange çağrıldı, kayıt sayısı: " + snapshot.getChildrenCount());
                contentList.clear();
                for (DataSnapshot child : snapshot.getChildren()) {
                    // Status filtresi: tamamlanmamış işler
                    String status = child.child("status").getValue(String.class);
                    if ("completed".equalsIgnoreCase(status)) {
                        continue;
                    }

                    Soul soul = child.getValue(Soul.class);
                    if (soul == null) continue;

                    // Content metninde tür ve sahibi göster
                    String text = "Tür: " + soul.getSpecies()
                            + "\nKayıt sahibi: " + soul.getFinderName();

                    Content item = new Content(
                            soul.getImageResId(),
                            text,
                            0,
                            0,
                            true
                    );
                    contentList.add(item);
                }
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Veri okunamadı", error.toException());
                Toast.makeText(ExplorePrivate.this,
                        "Veri okunurken hata oluştu.",
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
