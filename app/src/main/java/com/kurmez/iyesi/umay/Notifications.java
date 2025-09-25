package com.kurmez.iyesi.umay;

import android.app.*;
import android.content.*;
import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;

import com.kurmez.iyesi.umay.sahiplendirme.Founded;

public final class Notifications {
    private Notifications() {}
    public static final String CH_EMERGENCY = "emergency";

    public static void ensureChannels(Context ctx) {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_EMERGENCY, "Acil Müdahale", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Acil vaka uyarıları");
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(ch);
        }
    }

    public static void showEmergency(Context ctx, String title, String body,
                                     @Nullable String soulId, @Nullable String deeplink) {
        PendingIntent pi = makeDeepLinkPI(ctx, deeplink, soulId);

        Notification n = new NotificationCompat.Builder(ctx, CH_EMERGENCY)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        int id = (soulId != null) ? soulId.hashCode() : (int) System.currentTimeMillis();
        nm.notify(id, n);
    }

    private static PendingIntent makeDeepLinkPI(Context ctx, @Nullable String deeplink, @Nullable String soulId) {
        Intent intent;
        if (deeplink != null && deeplink.startsWith("iye://")) {
            // Manifest’teki intent-filter ile eşleşecek
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(deeplink));
        } else {
            // Alternatif: hedef Activity’e extras ver
            intent = new Intent(ctx, Founded.class);
            if (soulId != null) intent.putExtra("soulId", soulId);
        }

        // Back-stack düz olsun:
        TaskStackBuilder tsb = TaskStackBuilder.create(ctx);
        tsb.addNextIntentWithParentStack(intent);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return tsb.getPendingIntent((soulId != null ? soulId.hashCode() : 1001), flags);
    }
}
