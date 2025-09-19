package com.kurmez.iyesi.kayra.appCheck;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.kurmez.iyesi.kayra.appCheck.GmsIntegrityPreflight.Result;
import com.kurmez.iyesi.kurmes.utilities.Helpers;

/**
 * PlayIntegrityPrereq — Play ortamını denetle & (gerekirse) kullanıcıya düzeltme akışı sun.
 *
 * Bakım Noktaları:
 *  - Düğme metinleri / yerelleştirme.
 *  - fix dialog görünürlüğü (Activity state guard’ları).
 *  - Retry stratejisi (hemen vs. gecikmeli).
 *  - (Opsiyonel) telemetri kancaları (onShow, onClick, result).
 */
public final class PlayIntegrityPrereq {
    private PlayIntegrityPrereq() {}
    private static final String TAG = "PlayIntegrityPrereq";

    public interface Gate { @MainThread void onDone(boolean ok); }

    /** Giriş noktası: önce preflight; başarısız ise (showUi=true) düzeltme diyaloğu. */
    public static void checkAndFix(@NonNull Activity a, boolean showUi, @NonNull Gate cb) {
        long t0 = enter("checkAndFix", "showUi=" + showUi);

        if (shouldSkipPreflightWhenDebug()) {
            long _t = System.currentTimeMillis();
            leave("checkAndFix", _t, "skip=debug-appcheck");
            runUi(a, () -> cb.onDone(true));
            return;
        }
        GmsIntegrityPreflight.run(a.getApplicationContext(), r -> {
            d("preflight=" + r);
            if (r.ok || !showUi || a.isFinishing()) {
                leave("checkAndFix", t0, "ok=" + r.ok);
                runUi(a, () -> cb.onDone(r.ok));
                return;
            }
            runUi(a, () -> showFixDialog(a, r, cb, t0));
        });
    }

    // --- UI (fix dialog) ---
    private static void showFixDialog(@NonNull Activity a, @NonNull Result r, @NonNull Gate cb, long parentT0) {
        if (a.isFinishing()) { runUi(a, () -> cb.onDone(false)); return; }
        AlertDialog.Builder b = new AlertDialog.Builder(a);
        b.setTitle("Ortam Gerekleri");
        b.setMessage(buildMessage(r));
        b.setCancelable(true);

        b.setPositiveButton("Tekrar Dene", (dlg, w) -> retry(a, cb, parentT0));
        b.setNegativeButton("Kapat", (dlg, w) -> cb.onDone(false));

        try {
            b.setNeutralButton("Tanılama", (dlg, w) -> a.startActivity(new Intent(a, TopActivity.class)));
        } catch (Exception e) {
            Helpers.showToastSafe(a.getApplicationContext(), "Tanılama ekranı bulunamadı (Manifest?)");
        }

        b.setOnDismissListener(d -> d("fixDialog dismissed"));

        // Ek eylemler (alt düğmeler):
        b.setOnCancelListener(d -> d("fixDialog canceled"));
        AlertDialog dialog = b.create();
        dialog.show();

        // Ek seçenekler menüsü (isteğe bağlı hızlı erişim):
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnLongClickListener(v -> {
            tryOpenPlayHelpers(a); return true;
        });
    }

    private static void retry(@NonNull Activity a, @NonNull Gate cb, long parentT0) {
        long t = enter("retry", null);
        checkAndFix(a, /*showUi=*/false, ok -> {
            leave("retry", t, "ok=" + ok);
            if (!ok) showFixDialog(a, new Result(false,false,false,true,true,false,0,0,"Tekrar deneyiniz"), cb, parentT0);
            else cb.onDone(true);
        });
    }

    // Hızlı kısayollar: Play hizmetleri / mağaza
    private static void tryOpenPlayHelpers(@NonNull Activity a) {
        d("open helpers");
        PlayStoreNavigator.openPlayServices(a);
        PlayStoreNavigator.openPlayStore(a);
        PlayStoreNavigator.openThisAppOnPlay(a, a.getPackageName());
    }

    // --- Metin oluşturma ---
    private static String buildMessage(@NonNull Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.hint).append("\n\n")
                .append("Ağ: ").append(r.netOk).append("\n")
                .append("GMS Uygun: ").append(r.gmsOk).append(" (ver=").append(r.gmsVer).append(")\n")
                .append("PlayStore Sürüm OK: ").append(r.playOk).append(" (ver=").append(r.playVer).append(")\n")
                .append("TLS Sağlayıcı: ").append(r.secProvOk).append("\n")
                .append("Integrity: ").append(r.integrityOk);
        return sb.toString();
    }

    // --- Utils ---
    private static void runUi(@NonNull Activity a, @NonNull Runnable r) {
        if (a.isFinishing()) return;
        if ("main".equals(Thread.currentThread().getName())) { r.run(); }
        else a.runOnUiThread(r);
    }

    private static long enter(String fn, @Nullable String detail) {
        long t = System.currentTimeMillis();
        Log.d(TAG, "→ " + fn + (detail == null ? "" : " | " + detail));
        return t;
    }
    private static void leave(String fn, long start, @Nullable String detail) {
        long dur = System.currentTimeMillis() - start;
        Log.d(TAG, "← " + fn + " | " + (detail == null ? "" : detail) + " (" + dur + "ms)");
    }
    private static void d(String m){ Log.d(TAG, m); }

    // Java 17: pattern matching for instanceof used below.
    private static boolean shouldSkipPreflightWhenDebug() {
        try {
            Class<?> cls = Class.forName("com.kurmez.iyesi.kayra.appCheck.AppCheckTP");
            var m = cls.getDeclaredMethod("isDebugProviderActive");
            Object ret = m.invoke(null);
            if (ret instanceof Boolean b && b) {
                d("Debug AppCheck provider active -> skipping Play Integrity preflight.");
                return true;
            }
        } catch (Throwable ignored) {
            // Class/method may not exist; fall through.
        }
        return false;
    }

}