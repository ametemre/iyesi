package com.kurmez.iyesi.kayra.appCheck;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

public final class PlayStoreNavigator {

    private PlayStoreNavigator() {}

    public static void openPlayServices(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=com.google.android.gms"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.gms"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public static void openPlayStoreApp(Context ctx) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=com.android.vending"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=com.android.vending"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }

    public static void openThisAppOnPlay(Context ctx, String packageName) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("market://details?id=" + packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignore) {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://play.google.com/store/apps/details?id=" + packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
    }
}

