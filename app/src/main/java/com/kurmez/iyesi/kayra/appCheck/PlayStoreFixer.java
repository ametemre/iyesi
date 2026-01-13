package com.kurmez.iyesi.kayra.appCheck;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.kurmez.iyesi.R;

/**
 * Play ortamı için yönlendirme yardımcıları:
 * - Play Store / Google Play services güncelleme sayfalarını açma
 * - GMS hata iletişim kutusu (mümkünse)
 * - Integrity uyarısı için hazır diyalog
 *
 * Kullanım (Integrity fail anında):
 *   PlayStoreFixer.showIntegrityFixDialog(activity);
 *
 * Sadece doğrudan açmak istersen:
 *   PlayStoreFixer.openPlayStoreForPackage(ctx, "com.android.vending"); // Play Store
 *   PlayStoreFixer.openPlayStoreForPackage(ctx, "com.google.android.gms"); // Google Play services
 */
public final class PlayStoreFixer {

    private static final String TAG = "PlayStoreFixer";

    private PlayStoreFixer() {}

    /**
     * market:// → com.android.vending’e sabitleyip açmayı dener,
     * olmazsa genel market://, en sonda https:// fallback.
     */
    public static boolean openPlayStoreForPackage(@NonNull Context ctx, @NonNull String pkg) {
        Intent market = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
        market.setPackage("com.android.vending");
        market.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(market);
            return true;
        } catch (ActivityNotFoundException e1) {
            Log.w(TAG, "Play Store paketli market intent açılamadı, genel market:// deneniyor", e1);
            Intent marketGeneric = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + pkg));
            marketGeneric.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(marketGeneric);
                return true;
            } catch (ActivityNotFoundException e2) {
                Log.w(TAG, "market:// açılamadı, web fallback deneniyor", e2);
                Intent web = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + pkg));
                web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    ctx.startActivity(web);
                    return true;
                } catch (ActivityNotFoundException e3) {
                    Log.e(TAG, "Hiçbir yöntemle açamadım: " + pkg, e3);
                    return false;
                }
            }
        }
    }

    /** Uygulama bilgi ekranını açar (Ayarlar → Uygulama bilgisi). */
    public static boolean openAppInfoScreen(@NonNull Context ctx, @NonNull String pkg) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + pkg));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "App info açılamadı: " + pkg, e);
            return false;
        }
    }

    /**
     * GMS uygunluk diyaloğunu göstermek için basit sarmalayıcı.
     * Not: Bazı ROM’larda çalışmayabilir.
     */
    public static void tryShowGmsErrorDialog(@NonNull Activity activity) {
        GoogleApiAvailability gaa = GoogleApiAvailability.getInstance();
        int code = gaa.isGooglePlayServicesAvailable(activity);
        if (code != ConnectionResult.SUCCESS && gaa.isUserResolvableError(code)) {
            gaa.getErrorDialog(activity, code, /*requestCode=*/1001).show();
        } else {
            Log.d(TAG, "GMS hata diyaloğu uygun değil. code=" + code);
        }
    }

    /**
     * Integrity / AppCheck uyarısı için rehber diyalog.
     * Bu diyaloğu Integrity preflight’ında FAIL aldığında çağır.
     */
    @MainThread
    public static void showIntegrityFixDialog(@NonNull Activity activity) {
        // ÖNCE: Hardcoded "Google Play doğrulaması gerekli", uzun mesaj ve buton metinleri
        // ŞİMDİ: String resource kullanımı
        new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.playstore_fixer_dialog_title_verification_required))
                .setMessage(activity.getString(R.string.playstore_fixer_dialog_message_components_outdated))
                .setPositiveButton(activity.getString(R.string.playstore_fixer_dialog_button_update_play_store), (d, w) -> {
                    boolean ok = openPlayStoreForPackage(activity, "com.android.vending");
                    if (!ok) openAppInfoScreen(activity, "com.android.vending");
                })
                .setNeutralButton(activity.getString(R.string.playstore_fixer_dialog_button_update_play_services), (d, w) -> {
                    boolean ok = openPlayStoreForPackage(activity, "com.google.android.gms");
                    if (!ok) openAppInfoScreen(activity, "com.google.android.gms");
                })
                .setNegativeButton(activity.getString(R.string.playstore_fixer_dialog_button_later), (d, w) -> d.dismiss())
                .setOnDismissListener(d -> {
                    // İstersen burada yeniden deneme tetikle:
                    // PlayEnvDiagnostics.preflight(activity, /*showUi=*/false, status -> {...});
                })
                .show();
    }
}
