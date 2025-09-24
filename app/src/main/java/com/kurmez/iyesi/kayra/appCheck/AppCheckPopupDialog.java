package com.kurmez.iyesi.kayra.appCheck;

// AppCheckPopupDialog.java  (yeni, küçük DialogFragment)

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

public class AppCheckPopupDialog extends DialogFragment {
    private static final String ARG_IS_DEBUG = "is_debug";
    private static final String ARG_SECRET   = "secret";
    private static final String ARG_REASON   = "reason";

    public static AppCheckPopupDialog newInstance(boolean isDebug, @Nullable String secret, @NonNull String reason) {
        Bundle b = new Bundle();
        b.putBoolean(ARG_IS_DEBUG, isDebug);
        b.putString(ARG_SECRET, secret);
        b.putString(ARG_REASON, reason);
        AppCheckPopupDialog d = new AppCheckPopupDialog();
        d.setArguments(b);
        return d;
    }

    @NonNull @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        boolean isDebug = getArguments().getBoolean(ARG_IS_DEBUG);
        String secret   = getArguments().getString(ARG_SECRET);
        String reason   = getArguments().getString(ARG_REASON, "TOKEN_INVALID");

        AlertDialog.Builder b = new AlertDialog.Builder(ctx)
                .setCancelable(true);

        if (isDebug) {
            b.setTitle("App Check (Debug)")
                    .setMessage(secret != null && !secret.isEmpty()
                            ? "Debug Secret:\n" + secret + "\n\nFirebase Console > App Check > Debug devices ekranında 'Allow'."
                            : "Debug secret Logcat’e yazıldı.\nLogcat’teki 'Enter this debug secret…' satırını kopyalayıp Console’da 'Allow'.")
                    .setPositiveButton("Kopyala", (d,w) -> copyToClipboard(ctx, secret))
                    .setNegativeButton("Kapat", null)
                    .setNeutralButton("Console’a Git", (d,w) -> openFirebaseConsole());
        } else {
            b.setTitle("App Check doğrulaması başarısız")
                    .setMessage(buildProdHelp(reason))
                    .setPositiveButton("Yeniden Dene", (d,w) -> requestRetry())
                    .setNegativeButton("Kapat", null)
                    .setNeutralButton("Yardım", (d,w) -> openDocs());
        }
        return b.create();
    }

    private void copyToClipboard(Context c, @Nullable String secret) {
        if (secret == null || secret.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("Debug Secret", secret));
        Toast.makeText(c, "Kopyalandı", Toast.LENGTH_SHORT).show();
    }

    private void openFirebaseConsole() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://console.firebase.google.com/")));
    }
    private void openDocs() {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://firebase.google.com/docs/app-check")));
    }

    private CharSequence buildProdHelp(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append("Token doğrulanamadı.\n\n")
                .append("• Uygulama Play Store’dan kurulu mu?\n")
                .append("• SHA-256 fingerprint(ler) Console’da kayıtlı mı? (Release / App Signing)\n")
                .append("• App Check Enforcement prod’da açık mı (gerekiyorsa)?\n")
                .append("• Google Play Hizmetleri/Play Store güncel mi?\n\n");
        if ("TOKEN_INVALID".equals(reason)) {
            sb.append("Hata: TOKEN_INVALID\n")
                    .append("- İmza (SHA-256) ⇄ App Check kayıtları eşleşsin.\n")
                    .append("- Yeni sürüm sonrası ilk açılışta bir kez daha deneyin.\n");
        }
        return sb.toString();
    }

    private void requestRetry() {
        // Kuyruklu çağrıları readiness sağlanınca çalıştır
        // Örn: Harita.get().drainVetQueueIfReady();
    }
}

