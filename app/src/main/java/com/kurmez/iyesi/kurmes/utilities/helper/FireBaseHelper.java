package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

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

}
