package com.kurmez.iyesi.kayra;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.kurmez.iyesi.kayra.TopActivity;

/**
 * Play Integrity (App Check) için önkoşul denetleri:
 * - Play Store kurulu / etkin mi?
 * - Versiyon asgari eşiğin üstünde mi? (düşükse Play Store güncelleme sayfasına yönlendirir)
 * - Google Play services güncel/uygun mu? (gerekirse GMS sayfasına yönlendirir)
 *
 * NOT: Android 11+ için manifest'e package visibility eklemen gerekebilir (aşağıya bak).
 */
public final class PlayIntegrityPrereq {

    private PlayIntegrityPrereq() {}

    public interface ResultCallback {
        void onResult(boolean ok);
    }

    private static final String TAG = "PlayIntegrityPrereq";
    private static final String PKG_PLAY_STORE = "com.android.vending";
    private static final String PKG_GMS        = "com.google.android.gms";

    // Play Integrity’nin sorunsuz çalıştığı Play Store sürümleri genellikle çok yeni olur.
    // Burada geniş tutulmuş güvenli bir alt sınır veriyoruz. İstersen gerekirse artır.
    private static final long MIN_PLAY_STORE_VER_CODE = 380_000_000L;

    /**
     * Önkoşulları kontrol eder. Uygun değilse (ve showUi=true ise) ilgili
     * mağaza sayfasını otomatik açar. Sonucu callback ile döner.
     */
    public static void checkAndFix(@NonNull Context ctx,
                                   boolean showUi,
                                   @Nullable ResultCallback cb) {
        boolean hasPs   = isPackageInstalled(ctx, PKG_PLAY_STORE);
        boolean enabled = isPackageEnabled(ctx, PKG_PLAY_STORE);
        long ver        = getVersionCode(ctx, PKG_PLAY_STORE);
        boolean gmsOk   = isPlayServicesOk(ctx);

        boolean ok = hasPs && enabled && ver >= MIN_PLAY_STORE_VER_CODE && gmsOk;

        if (!ok && showUi && TopActivity.isForeground()) {
            if (!hasPs || !enabled || ver < MIN_PLAY_STORE_VER_CODE) {
                openDetails(ctx, PKG_PLAY_STORE);
            } else if (!gmsOk) {
                openDetails(ctx, PKG_GMS);
            }
        }

        if (cb != null) cb.onResult(ok);
    }

    /* -------------------- helpers -------------------- */

    private static boolean isPlayServicesOk(Context ctx) {
        int status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx);
        return status == ConnectionResult.SUCCESS;
    }

    private static boolean isPackageInstalled(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            } else {
                //noinspection deprecation
                pm.getPackageInfo(pkg, 0);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isPackageEnabled(Context ctx, String pkg) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.enabled;
        } catch (Exception e) {
            return false;
        }
    }

    private static long getVersionCode(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pi = pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0));
            } else {
                //noinspection deprecation
                pi = pm.getPackageInfo(pkg, 0);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return pi.getLongVersionCode();
            } else {
                //noinspection deprecation
                return pi.versionCode;
            }
        } catch (Exception e) {
            return 0L;
        }
    }

    private static void openDetails(Context ctx, String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                Intent web = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(web);
            } catch (Exception ex) {
                Log.w(TAG, "Mağaza sayfası açılamadı: " + pkg, ex);
            }
        }
    }
}
