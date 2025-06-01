// Explore.java
package com.kurmez.iyesi.social;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.tasks.Task;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.utilities.ContentAdapter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Firebase Functions'daki getPosts fonksiyonunu çağırarak
 * public içerikleri alır ve RecyclerView'a geçirir.
 */
public class Explore extends AppCompatActivity {
    private static final String TAG = "ExploreActivity";
    private FirebaseFunctions functions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_explore);
        functions = FirebaseFunctions.getInstance();

        RecyclerView recyclerView = findViewById(R.id.recycler_explore);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        fetchPublicPosts(recyclerView);

        View profileHeader = findViewById(R.id.profile_header);
        profileHeader.setOnClickListener(v -> {
            Intent intent = new Intent(Explore.this, Profile.class);
            startActivity(intent);
        });

        TextView username = findViewById(R.id.profile_username);
        TextView bio = findViewById(R.id.profile_bio);
        TextView followers = findViewById(R.id.profile_followers);
        // Mock profile (kendinize göre güncelleyin)
        username.setText("John Doe");
        bio.setText("Exploring the world with pets!");
        followers.setText("Followers: 200");
    }

    private void fetchPublicPosts(RecyclerView recyclerView) {
        // Boş payload ile getPosts çağırılıyor
        Map<String, Object> payload = new HashMap<>();
        functions
                .getHttpsCallable("getPosts")
                .call(payload)
                .continueWith(task -> {
                    if (!task.isSuccessful()) {
                        throw task.getException();
                    }
                    return task.getResult();
                })
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        HttpsCallableResult result = task.getResult();
                        Object data = result.getData();
                        List<Content> list = parsePosts(data);
                        ContentAdapter adapter = new ContentAdapter(list, this);
                        recyclerView.setAdapter(adapter);
                    } else {
                        Log.e(TAG, "getPosts failed", task.getException());
                    }
                });
    }

    private List<Content> parsePosts(Object data) {
        List<Content> contents = new ArrayList<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> resultMap = (Map<String, Object>) data;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> postsList = (List<Map<String, Object>>) resultMap.get("posts");

            if (postsList != null) {
                for (Map<String, Object> postMap : postsList) {
                    // 1) mediaUrl: eğer Firestore dokümanında mediaUrl alanı yoksa boş string ata
                    String mediaUrl = "";
                    if (postMap.containsKey("mediaUrl")) {
                        mediaUrl = (String) postMap.get("mediaUrl");
                    }

                    // 2) text alanı (Firestore'daki "content")
                    String text = "";
                    if (postMap.containsKey("content")) {
                        text = (String) postMap.get("content");
                    }

                    // 3) likes sayısı: Firestore'daki "likes" listesi varsa boyutunu al
                    int likesCount = 0;
                    if (postMap.containsKey("likes")) {
                        @SuppressWarnings("unchecked")
                        List<String> likesList = (List<String>) postMap.get("likes");
                        likesCount = (likesList == null ? 0 : likesList.size());
                    }

                    // 4) comments sayısı: Firestore'daki "comments" listesi varsa boyutunu al
                    int commentsCount = 0;
                    if (postMap.containsKey("comments")) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> commentsList = (List<Map<String, Object>>) postMap.get("comments");
                        commentsCount = (commentsList == null ? 0 : commentsList.size());
                    }

                    // 5) isPrivate alanı: Firestore'da "isPublic" boolean olarak geliyor
                    boolean isPrivate = true;
                    if (postMap.containsKey("isPublic")) {
                        Boolean isPublic = (Boolean) postMap.get("isPublic");
                        isPrivate = (isPublic == null ? true : !isPublic);
                    }

                    // Son olarak Content nesnesini beş parametreli constructor ile oluştur
                    Content contentItem = new Content(
                            mediaUrl,
                            text,
                            likesCount,
                            commentsCount,
                            isPrivate
                    );
                    contents.add(contentItem);
                }
            }
        } catch (ClassCastException e) {
            Log.e(TAG, "parsePosts: casting hatası", e);
        }
        return contents;
    }

}
