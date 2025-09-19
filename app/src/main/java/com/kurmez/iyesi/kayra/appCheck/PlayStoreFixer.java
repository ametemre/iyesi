package com.kurmez.iyesi.kayra.appCheck;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.kurmez.iyesi.kayra.appCheck.GmsIntegrityPreflight.Result;

/**
 * PlayStoreFixer — GMS / Play Store ortamını kullanıcıya düzelttiren yardımcı UI.
 *
 * Bakım Noktaları:
 *  - Metin/yerelleştirme (BUT_UPDATE, BUT_RETRY, BUT_CLOSE, BUT_MORE, SHEET_ITEMS).
 *  - Hangi eylemler ilk diyalogda, hangileri “Diğer…” sayfasında gösterilecek.
 *  - GmsIntegrityPreflight eşikleri (ayrı dosyada).
 *  - Telemetri kancaları (TODO etiketleri).
 */
public final class PlayStoreFixer {
    private PlayStoreFixer() {}
    private static final String TAG = "PlayStoreFixer";

    // ---- UI metinleri (yerelleştirilebilir) ----
    private static final String TTL_FIX   = "Ortam Gerekleri";
    private static final String MSG_GENERIC= "Uygulama için Google Play Hizmetleri / Play Store ve TLS sağlayıcısı güncel/erişilebilir olmalı.";
    private static final String BUT_UPDATE= "Güncelle/Düzelt";
    private static final String BUT_RETRY = "Tekrar Dene";
    private static final String BUT_MORE  = "Diğer…";
    private static final String BUT_CLOSE = "Kapat";

    private static final String SHEET_PSERV = "Play Services sayfası";
    private static final String SHEET_PSTORE= "Play Store ana sayfa";
    private static final String SHEET_THIS  = "Bu uygulama (Play)";
    private static final String SHEET_INFO_PSERV = "Play Services (Uyg. Bilgileri)";
    private static final String SHEET_INFO_PSTORE= "Play Store (Uyg. Bilgileri)";
    private static final String SHEET_NET   = "Ağ/Ayarlar";
    private static final String SHEET_DIAG  = "Tanılama";

    // ---- Giriş noktası: preflight + dialog ----
    public static void showResolveDialog(@NonNull Activity a) {
        long t0 = enter("showResolveDialog", "preflight");
        GmsIntegrityPreflight.run(a.getApplicationContext(), r -> {
            leave("showResolveDialog", t0, "preflight=" + r.ok + " hint=" + r.hint);
            runUi(a, () -> buildAndShow(a, r));
        });
    }

    /** Preflight sonucu hazırsa doğrudan çağır. */
    public static void showResolveDialog(@NonNull Activity a, @NonNull Result r) {
        runUi(a, () -> buildAndShow(a, r));
    }

    // ---- Dialog inşası ----
    private static void buildAndShow(@NonNull Activity a, @NonNull Result r) {
        if (a.isFinishing()) return;
        String message = buildMessage(r);
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(TTL_FIX)
                .setMessage(message)
                .setCancelable(true)
                .setPositiveButton(BUT_UPDATE, (d, w) -> doUpdateActions(a))
                .setNegativeButton(BUT_CLOSE, (d, w) -> {/*TODO: telemetry close*/})
                .setNeutralButton(BUT_RETRY, (d, w) -> retry(a));
        AlertDialog dialog = b.create();
        dialog.setOnShowListener(di -> {
            // “Diğer…” için uzun basma kısayolu
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnLongClickListener(v -> { showActionSheet(a); return true; });
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnLongClickListener(v -> { showActionSheet(a); return true; });
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL ).setText(BUT_MORE);
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL ).setOnClickListener(v -> showActionSheet(a));
        });
        dialog.show();
    }

    // ---- Eylemler ----
    private static void doUpdateActions(@NonNull Activity a) {
        // Kullanıcıyı en yaygın düzeltmelere götür.
        PlayStoreNavigator.openPlayServices(a);
        PlayStoreNavigator.openPlayStore(a);
        PlayStoreNavigator.openThisAppOnPlay(a, a.getPackageName());
        // TODO: telemetry "update_clicked"
    }

    private static void retry(@NonNull Activity a) {
        long t = enter("retry", null);
        GmsIntegrityPreflight.run(a.getApplicationContext(), r2 -> {
            leave("retry", t, "ok=" + r2.ok);
            if (!r2.ok) showResolveDialog(a, r2);
            // ok ise sessizce kapanır; Main tarafı yeniden deneyebilir.
        });
    }

    private static void showActionSheet(@NonNull Activity a) {
        String[] items = new String[]{SHEET_PSERV, SHEET_PSTORE, SHEET_THIS, SHEET_INFO_PSERV, SHEET_INFO_PSTORE, SHEET_NET, SHEET_DIAG};
        new AlertDialog.Builder(a)
                .setTitle(BUT_MORE)
                .setItems(items, (d, idx) -> {
                    switch (idx) {
                        case 0: PlayStoreNavigator.openPlayServices(a); break;
                        case 1: PlayStoreNavigator.openPlayStore(a); break;
                        case 2: PlayStoreNavigator.openThisAppOnPlay(a, a.getPackageName()); break;
                        case 3: PlayStoreNavigator.openAppSystemPage(a, PlayStoreNavigator.PLAY_SERVICES_PKG); break;
                        case 4: PlayStoreNavigator.openAppSystemPage(a, PlayStoreNavigator.PLAY_STORE_PKG); break;
                        case 5: a.startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); break;
                        case 6: a.startActivity(new Intent(a, TopActivity.class)); break;
                    }
                })
                .setNegativeButton(BUT_CLOSE, null)
                .show();
    }

    // ---- Metin üretimi ----
    private static String buildMessage(@NonNull Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append(MSG_GENERIC).append("\n\n")
                .append("Durum: ").append(r.hint).append("\n")
                .append("Ağ: ").append(r.netOk).append("\n")
                .append("GMS uygun: ").append(r.gmsOk).append(" (ver=").append(r.gmsVer).append(")\n")
                .append("PlayStore sürüm OK: ").append(r.playOk).append(" (ver=").append(r.playVer).append(")\n")
                .append("TLS Sağlayıcı: ").append(r.secProvOk).append("\n")
                .append("Integrity: ").append(r.integrityOk).append("\n\n")
                .append("• “").append(BUT_UPDATE).append("” ile güncelleme sayfalarına gidin.\n")
                .append("• “").append(BUT_MORE).append("” ile ileri seçeneklere bakın.\n")
                .append("• Düzeltme sonrası “").append(BUT_RETRY).append("” ile tekrar deneyin.");
        return sb.toString();
    }

    // ---- Thread/Log yardımcıları ----
    private static void runUi(@NonNull Activity a, @NonNull Runnable r) {
        if (a.isFinishing()) return;
        if ("main".equals(Thread.currentThread().getName())) r.run();
        else a.runOnUiThread(r);
    }
    private static long enter(String fn, @Nullable String detail){
        long t = System.currentTimeMillis();
        Log.d(TAG, "→ " + fn + (detail==null?"":" | "+detail));
        return t;
    }
    private static void leave(String fn, long startMs, @Nullable String detail){
        long dur = System.currentTimeMillis() - startMs;
        Log.d(TAG, "← " + fn + " | " + (detail==null?"":detail) + " ("+dur+"ms)");
    }
}
