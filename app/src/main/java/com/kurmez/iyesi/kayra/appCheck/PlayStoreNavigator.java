package com.kurmez.iyesi.kayra.appCheck;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Centralized Play Store navigation & fallbacks. */
public final class PlayStoreNavigator {
    private PlayStoreNavigator() {}
    public static final String PLAY_STORE_PKG = "com.android.vending";
    public static final String PLAY_SERVICES_PKG = "com.google.android.gms";
    private static final String PLAY_WEB_APP = "https://play.google.com/store/apps/details?id=";
    private static final String PLAY_WEB_HOME = "https://play.google.com/store";

    /** Open Google Play Services detail page. */
    public static void openPlayServices(@NonNull Context c) {
        openAppOnPlay(c, PLAY_SERVICES_PKG);
    }

    /** Open Play Store (home). */
    public static void openPlayStore(@NonNull Context c) {
        // Try launching the Play app
        Intent i = c.getPackageManager().getLaunchIntentForPackage(PLAY_STORE_PKG);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tryStart(c, i, null);
            return;
        }
        // Fallback to web
        openUrl(c, PLAY_WEB_HOME);
    }

    /** Open this app’s page on Play. */
    public static void openThisAppOnPlay(@NonNull Context c, @NonNull String myPkg) {
        openAppOnPlay(c, myPkg);
    }

    /** Open any package’s page on Play (app → web fallback). */
    public static void openAppOnPlay(@NonNull Context c, @NonNull String pkg) {
        Intent market = marketDetails(pkg);
        if (!tryStart(c, market, () -> openUrl(c, PLAY_WEB_APP + pkg))) {
            openUrl(c, PLAY_WEB_APP + pkg);
        }
    }

    /** Open system App info (useful when Play missing). */
    public static void openAppSystemPage(@NonNull Context c, @NonNull String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(c, i, null);
    }

    /** Best-effort hint: was pkg installed from Play Store? */
    public static boolean isInstalledFromPlay(@NonNull Context c, @NonNull String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                InstallSourceInfo info = c.getPackageManager().getInstallSourceInfo(pkg);
                return PLAY_STORE_PKG.equals(info.getInstallingPackageName())
                        || PLAY_STORE_PKG.equals(info.getInitiatingPackageName())
                        || PLAY_STORE_PKG.equals(info.getOriginatingPackageName());
            } else {
                String installer = c.getPackageManager().getInstallerPackageName(pkg);
                return PLAY_STORE_PKG.equals(installer);
            }
        } catch (Exception ignored) { return false; }
    }

    // ---- helpers ----
    private static Intent marketDetails(String pkg) {
        return new Intent(Intent.ACTION_VIEW)
                .setData(Uri.parse("market://details?id=" + pkg))
                .setPackage(PLAY_STORE_PKG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static void openUrl(@NonNull Context c, @NonNull String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        tryStart(c, i, null);
    }

    private static boolean tryStart(@NonNull Context c, @NonNull Intent i, @Nullable Runnable fallback) {
        try {
            c.startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (fallback != null) fallback.run();
            return false;
        }
    }
}
