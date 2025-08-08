package com.kurmez.iyesi;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.DocumentSnapshot;
import com.kurmez.iyesi.kurmes.social.Profile;

import java.util.Arrays;
import java.util.List;

public class ProfileActivity extends AppCompatActivity {

    public static final String EXTRA_UID = "extra_uid";

    private ImageView iyeImage, headerTitle;
    private TextView tvCompanion, tvFoundDate, tvPlace, tvWho;
    private ListView listViewIye;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        // ➊ View’ları bağla
        iyeImage     = findViewById(R.id.iye_image);
        headerTitle  = findViewById(R.id.header_title);
        tvCompanion  = findViewById(R.id.iye_companion);
        tvFoundDate  = findViewById(R.id.iye_found_Date);
        tvPlace      = findViewById(R.id.iye_place);
        tvWho        = findViewById(R.id.who);
        listViewIye  = findViewById(R.id.list_view_iye);

        // ➋ Intent’ten UID al
        String uid = getIntent().getStringExtra(EXTRA_UID);
        if (uid != null) {
            loadProfile(uid);
        }
    }

    private void loadProfile(String uid) {
        FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get()
                .addOnSuccessListener(this::onProfileLoaded)
                .addOnFailureListener(e -> {
                    // hata yönetimi
                });
    }

    private void onProfileLoaded(DocumentSnapshot doc) {
        if (!doc.exists()) return;
        Profile p = doc.toObject(Profile.class);
        if (p == null) return;

        // ➌ Model’den UI’a veri doldurma
        tvCompanion.setText(p.getUsername());
        tvFoundDate.setText(p.getEmail());    // örnek
        tvPlace    .setText(p.getLocation());
        tvWho      .setText(p.getRole());

        // ➍ ListView doldurma (örnek olarak role’a göre sabit liste)
        List<String> items = Arrays.asList("İye: " + p.getRole(), "Tel: " + p.getPhone());
        listViewIye.setAdapter(
                new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1,
                        items
                )
        );
    }
}
