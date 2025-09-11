package com.kurmez.iyesi.kurmes.social.content;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
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
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.adapters.ContentAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Explore extends AppCompatActivity {
    private static final String TAG = "ExploreActivity";

    private final List<String> allowedRoles =
            Arrays.asList("İye", "Körmes", "Ülgen", "Tengri", "Ağaç");

    private FirebaseAuth auth;
    private FirebaseUser user;
    private FirebaseFunctions functions;

    private RecyclerView recyclerView;
    private ContentAdapter adapter;
    private final List<Content> contentList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore);

        // 1) Auth kontrolü
        auth = FirebaseAuth.getInstance();
        user = auth.getCurrentUser();
        if (user == null) {
            Helpers.showToastSafe(this, "Lütfen önce giriş yapın.");
            finish();
            return;
        }

        // 2) UI
        recyclerView = findViewById(R.id.recycler_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContentAdapter(contentList, this);
        recyclerView.setAdapter(adapter);

        // Profil header tıklaması: Kişisel profil görünümünü aç
        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> {
            try {
                Intent viewProfile = new Intent(Intent.ACTION_VIEW, ContactsContract.Profile.CONTENT_URI);
                startActivity(viewProfile);
            } catch (Exception e) {
                // Bazı cihazlarda Contacts uygulaması olmayabilir
                Toast.makeText(this, "Profil açılamadı: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        // 3) Rolü çek → yetki kontrolü → gönderileri yükle
        Helpers.getRoleFunction()
                .addOnSuccessListener(role -> {
                    if (role == null) {
                        Helpers.showToastSafe(this, "Rol atanmadı!");
                        finish();
                        return;
                    }
                    if (!allowedRoles.contains(role)) {
                        Toast.makeText(this, "Bu sayfaya erişim yetkiniz yok: " + role, Toast.LENGTH_SHORT).show();
                        finish();
                        return;
                    }

                    // Cloud Functions (bölge: us-central1)
                    functions = FirebaseFunctions.getInstance("us-central1");
                    fetchPublicCompletedPosts();
                })
                .addOnFailureListener(e -> {
                    Toast.makeText(this, "Rol sorgusu hatası: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    finish();
                });

        // 4) Header menü aksiyonu (yenile)
        Helpers.ConversationHeaderHelper.setupHeader(this, R.menu.menu_explore_options, item -> {
            if (item.getItemId() == R.id.action_refresh) {
                fetchPublicCompletedPosts();
                return true;
            }
            return false;
        });
    }

    /* ------------------------------ Data Fetch ------------------------------ */

    private void fetchPublicCompletedPosts() {
        if (functions == null) return;

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
                    parsePosts(result != null ? result.getData() : null);
                });
    }

    @SuppressWarnings("unchecked")
    private void parsePosts(Object data) {
        contentList.clear();

        if (!(data instanceof Map)) {
            adapter.notifyDataSetChanged();
            return;
        }

        try {
            Map<String, Object> resultMap = (Map<String, Object>) data;
            Object postsObj = resultMap.get("posts");

            if (!(postsObj instanceof List)) {
                adapter.notifyDataSetChanged();
                return;
            }

            List<?> posts = (List<?>) postsObj;
            for (Object o : posts) {
                if (!(o instanceof Map)) continue;

                Map<String, Object> post = (Map<String, Object>) o;

                boolean isPublic = toBoolean(post.get("isPublic"));
                String status = toString(post.get("status"));

                if (!isPublic || !"completed".equalsIgnoreCase(status)) {
                    continue;
                }

                String mediaUrl = toString(post.get("mediaUrl"));
                String contentText = toString(post.get("content"));
                String ownerId = toString(post.get("ownerId"));

                String displayText = contentText + "\nKayıt sahibi: " + ownerId;

                Content item = new Content(mediaUrl, displayText, 0, 0, false);
                contentList.add(item);
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "parsePosts hatası", e);
        }

        adapter.notifyDataSetChanged();
    }

    /* ------------------------------ Helpers -------------------------------- */

    private static String toString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean toBoolean(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return false;
        String s = String.valueOf(o);
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }
}
