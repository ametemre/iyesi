package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.kurmez.iyesi.kurmes.social.Iyesi;

import java.util.Map;
import java.util.function.Consumer;

public class FireBaseHelper {
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static void fetchUserClaims(Activity activity, @NonNull Consumer<Map<String, Object>> callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || Build.VERSION_CODES.N >= Build.VERSION.SDK_INT || user.isAnonymous()) return;

        user.getIdToken(true)
                .addOnSuccessListener(result -> {
                    Map<String, Object> claims = result.getClaims();
                    callback.accept(claims);
                })
                .addOnFailureListener(e -> Log.e("CustomClaims", "Token alınamadı", e));
    }
    public static Object customClaims(FirebaseUser user){
        if (user != null) {
            // true => token’ı yenile, claim güncellemeleri hemen gelsin
            user.getIdToken(true).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    GetTokenResult tok = task.getResult();
                    Map<String, Object> claims = tok.getClaims();
                    Object role = claims.get("role");   // örn. "Iye", "Ulgen" vb.
                    // ... kullan
                } else {
                    Exception e = task.getException();
                    // hata ele al
                }
            });
        }
        return null;
    }
}
