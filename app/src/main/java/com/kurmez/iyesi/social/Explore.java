package com.kurmez.iyesi.social;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.utilities.adapters.ContentAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Explore extends AppCompatActivity {
    private static final String TAG = "ExploreActivity";

    private FirebaseAuth auth;
    private FirebaseUser user;
    private FirebaseFunctions functions;
    private FirebaseFirestore firestore;
    private String userRole;

    private RecyclerView recyclerView;
    private ContentAdapter adapter;
    private List<Content> contentList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore);

        // 1) Auth kontrolü
        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Lütfen önce giriş yapın.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 2) Firestore init (profil için)
        firestore = FirebaseFirestore.getInstance();

        // 3) UI setup
        recyclerView = findViewById(R.id.recycler_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(contentList, this);
        recyclerView.setAdapter(adapter);

        // Profil header tıklaması
        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> startActivity(new Intent(this, Profile.class)));

        // 4) Kullanıcı rolünü çek ve gönderileri yükle
        String uid = user.getUid();
        firestore.collection("profiles")
                .document(uid)
                .get()
                .addOnSuccessListener(this::onProfileLoaded)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Profil yüklenemedi", e);
                    Toast.makeText(this, "Profil bilgisi alınamadı.", Toast.LENGTH_SHORT).show();
                    finish();
                });
    }

    private void onProfileLoaded(DocumentSnapshot doc) {
        userRole = doc.getString("role");
        if (userRole == null || userRole.isEmpty()) {
            Toast.makeText(this, "Rol bilgisi tanımsız.", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // 5) Cloud Functions init ve veri çek
        functions = FirebaseFunctions.getInstance();
        fetchPublicCompletedPosts();
    }

    private void fetchPublicCompletedPosts() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("onlyCompleted", true);
        payload.put("onlyPublic", true);

        functions.getHttpsCallable("getPosts")
                .call(payload)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "getPosts failed", task.getException());
                        Toast.makeText(this, "Gönderiler yüklenemedi.", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    HttpsCallableResult result = task.getResult();
                    parsePosts(result.getData());
                });
    }

    @SuppressWarnings("unchecked")
    private void parsePosts(Object data) {
        contentList.clear();
        try {
            Map<String, Object> resultMap = (Map<String, Object>) data;
            List<Map<String, Object>> posts = (List<Map<String, Object>>) resultMap.get("posts");
            if (posts != null) {
                for (Map<String, Object> post : posts) {
                    Boolean isPublic = (Boolean) post.get("isPublic");
                    String status = (String) post.get("status");
                    if (!Boolean.TRUE.equals(isPublic) || !"completed".equalsIgnoreCase(status)) {
                        continue;
                    }
                    String mediaUrl = post.get("mediaUrl") != null ? (String) post.get("mediaUrl") : "";
                    String contentText = post.get("content") != null ? (String) post.get("content") : "";
                    String ownerId = post.get("ownerId") != null ? (String) post.get("ownerId") : "";
                    String displayText = contentText + "\nKayıt sahibi: " + ownerId;

                    Content item = new Content(mediaUrl, displayText, 0, 0, false);
                    contentList.add(item);
                }
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "parsePosts hatası", e);
        }
        adapter.notifyDataSetChanged();
    }
}
