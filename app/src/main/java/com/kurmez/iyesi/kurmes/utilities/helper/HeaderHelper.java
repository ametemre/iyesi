// HeaderHelper.java
package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.content.Context;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Souls.Iye;

import java.util.Map;

public class HeaderHelper {

    private final Context context;
    private final Activity activity;

    public HeaderHelper(Activity activity) {
        this.activity = activity;
        this.context = activity.getApplicationContext();
    }

    // 👤 Header'ı herhangi bir View'a bağla
    public void attachHeaderToView(View headerView, Iye iye) {
        if (headerView == null || iye == null) {
            Log.w("HeaderHelper", "HeaderView veya Iye null, header eklenemedi");
            return;
        }

        try {
            TextView headerText = headerView.findViewById(R.id.tvUsername);
            ImageView headerImage = headerView.findViewById(R.id.ivProfile);

            String username = iye.getUsername();
            String avatarUrl = iye.getAvatarUrl();

            if (headerText != null) {
                // ÖNCE: Hardcoded "Kullanıcı" fallback
                // ŞİMDİ: String resource kullanımı
                headerText.setText(username != null && !username.isEmpty() ? username : context.getString(R.string.header_helper_label_user_fallback));
            }

            if (headerImage != null) {
                if (avatarUrl != null && !avatarUrl.isEmpty()) {
                    Glide.with(context)
                            .load(avatarUrl)
                            .placeholder(R.drawable.holder)
                            .error(R.drawable.holder)
                            .into(headerImage);
                } else {
                    headerImage.setImageResource(R.drawable.holder);
                }
            }

            Log.d("HeaderHelper", "Header başarıyla eklendi - Kullanıcı: " + username);
        } catch (Exception e) {
            Log.e("HeaderHelper", "Header eklenirken hata: " + e.getMessage());
        }
    }

    // 🔄 Header'ı güncelle (Iye objesi ile)
    public void refreshHeaderWithIye(View headerView, Iye iye) {
        attachHeaderToView(headerView, iye);
    }

    // 🔄 Header'ı güncelle (Firebase claims'ten otomatik)
    public void refreshHeaderFromFirebase(View headerView) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

        if (user == null || user.isAnonymous()) {
            Log.i("HeaderHelper", "Kullanıcı null veya anonim, header güncellenmedi");
            return;
        }

        user.getIdToken(true).addOnSuccessListener(result -> {
            Map<String, Object> claims = result.getClaims();
            Iye iye = Iye.fromClaims(claims);

            if (iye != null) {
                Log.d("HeaderHelper", "Iye yüklendi - Kullanıcı: " + iye.getUsername() + ", Avatar: " + iye.getAvatarUrl());
                attachHeaderToView(headerView, iye);
            } else {
                Log.w("HeaderHelper", "Claims'ten Iye objesi oluşturulamadı");
            }
        }).addOnFailureListener(e -> {
            Log.e("HeaderHelper", "ID token alınamadı: " + e.getMessage());
        });
    }

    // 🎯 Header View'ını oluştur ve döndür (manuel inflate için)
    public View createHeaderView(Iye iye) {
        try {
            View headerView = activity.getLayoutInflater().inflate(R.layout.item_conversation_header, null);
            attachHeaderToView(headerView, iye);
            return headerView;
        } catch (Exception e) {
            Log.e("HeaderHelper", "Header view oluşturulamadı: " + e.getMessage());
            return null;
        }
    }

    // 🔔 Header click listener'ı ekle
    public void setHeaderClickListener(View headerView, View.OnClickListener listener) {
        if (headerView != null && listener != null) {
            headerView.setOnClickListener(listener);

            // Avatar image'a da click listener ekle
            ImageView avatarImage = headerView.findViewById(R.id.ivProfile);
            if (avatarImage != null) {
                avatarImage.setOnClickListener(listener);
            }
        }
    }
}