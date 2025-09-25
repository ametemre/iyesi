package com.kurmez.iyesi.umay;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.kurmez.iyesi.umay.Notifications;

public class IyeFcmService extends FirebaseMessagingService {
    private static final String TAG = "IyeFcmService";

    @Override public void onNewToken(@NonNull String token) {
        Log.d(TAG, "FCM token: " + token);
        // Kullanıcı giriş yapmışsa token'ı Profile dokümanına ekle
        String uid = FirebaseAuth.getInstance().getCurrentUser() != null
                ? FirebaseAuth.getInstance().getCurrentUser().getUid() : null;
        if (uid != null) {
            FirebaseFirestore.getInstance()
                    .collection("Profiles").document(uid)
                    .update("fcmTokens", FieldValue.arrayUnion(token))
                    .addOnFailureListener(e -> Log.w(TAG, "Token yazılamadı", e));
        }
    }

    @Override public void onMessageReceived(@NonNull RemoteMessage msg) {
        // Foreground’dayken karta biz karar verelim:
        String type = msg.getData().get("type");
        String id   = msg.getData().get("id");
        String deeplink = msg.getData().get("deeplink");
        String title = msg.getNotification()!=null ? msg.getNotification().getTitle() : "Bildirim";
        String body  = msg.getNotification()!=null ? msg.getNotification().getBody()  : "Yeni mesaj";

        if ("emergency".equalsIgnoreCase(type)) {
            Notifications.ensureChannels(this);
            Notifications.showEmergency(this, title, body, id, deeplink);
        }
    }
}
