package com.kurmez.iyesi.kayra.QR;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

/**
 * QRScannerActivity
 * ---------------------------------------------------------
 * - Yalnızca TARAMA/DEEP-LINK OKUMA yapar ve sonucu çağırana geri verir.
 * - YÖNLENDİRME YAPMAZ. (Router işini çağıran aktivite üstlenir.)
 *
 * Dönen Extras:
 *   EXTRA_QR_RAW         : String  → taranan ham metin (veya deep-link URI)
 *   EXTRA_QR_ROUTE       : String  → normalize edilmiş rota (QR.normalizeRoute ile)
 *   EXTRA_QR_IS_DEEPLINK : boolean → deep-link ile mi açıldı?
 *   EXTRA_QR_KIND        : String  → "PAIRING" | "ROUTE" | "URL" | "TEXT"
 *   EXTRA_QR_ERROR       : String  → hata varsa kısa açıklama (RESULT_CANCELED ile)
 */
public class QRScannerActivity extends AppCompatActivity {

    public static final String EXTRA_QR_RAW         = "com.kurmez.iyesi.qr.EXTRA_QR_RAW";
    public static final String EXTRA_QR_ROUTE       = "com.kurmez.iyesi.qr.EXTRA_QR_ROUTE";
    public static final String EXTRA_QR_IS_DEEPLINK = "com.kurmez.iyesi.qr.EXTRA_QR_IS_DEEPLINK";
    public static final String EXTRA_QR_KIND        = "com.kurmez.iyesi.qr.EXTRA_QR_KIND";
    public static final String EXTRA_QR_ERROR       = "com.kurmez.iyesi.qr.EXTRA_QR_ERROR";
    public static final String EXTRA_SCANNED_DATA        = "com.kurmez.iyesi.qr.EXTRA_SCANNED_DATA";

    private static final int REQ_CAMERA = 1001;
    private static final String TAG = "QRScanner";

    /** Kolay başlatma helper’ı (startActivityForResult kullananlar için) */
    public static void launchForResult(@NonNull Activity caller, int requestCode) {
        Intent i = new Intent(caller, QRScannerActivity.class);
        caller.startActivityForResult(i, requestCode);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1) Deep-link ile açılmış mı? (ACTION_VIEW + data)
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            String raw = data.toString();
            finishWithSuccess(raw, /*isDeeplink=*/true);
            return;
        }

        // 2) Kamera izni kontrolü
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }

        // 3) Taramayı başlat
        startZxingScan();
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startZxingScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("QR'ı hizalayın");
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, perms, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startZxingScan();
            } else {
                finishWithError("permission_denied");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult res = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (res != null) {
            if (res.getContents() != null) {
                QR.deliverResult(this, res.getContents(), res.getFormatName());
            } else {
                setResult(Activity.RESULT_CANCELED);
                finish();
            }
        }
    }


    private void finishWithSuccess(@NonNull String raw, boolean isDeeplink) {
        try {
            // Normalize edilmiş rota (iyi-esi://..., iyesi.app/d/... → iyesi://..., CF redirect vb.)
            Uri normalized = com.kurmez.iyesi.kayra.QR.QR.normalizeRoute(raw);

            Intent out = new Intent();
            out.putExtra(EXTRA_QR_RAW, raw);
            out.putExtra(EXTRA_QR_ROUTE, normalized != null ? normalized.toString() : raw);
            out.putExtra(EXTRA_QR_IS_DEEPLINK, isDeeplink);
            out.putExtra(EXTRA_QR_KIND, classify(raw, normalized));
            out.putExtra(EXTRA_SCANNED_DATA, classify(raw, normalized));

            setResult(RESULT_OK, out);
        } catch (Throwable t) {
            Log.e(TAG, "finishWithSuccess error", t);
            Intent out = new Intent();
            out.putExtra(EXTRA_QR_RAW, raw);
            out.putExtra(EXTRA_QR_ERROR, "normalize_failed");
            setResult(RESULT_OK, out); // ham veriyi yine de geri ver
        }
        finish();
    }

    private void finishWithError(@NonNull String error) {
        Intent out = new Intent();
        out.putExtra(EXTRA_QR_ERROR, error);
        setResult(RESULT_CANCELED, out);
        finish();
    }

    /** Kabaca sınıflandır: eşleştirme/route/url/düz metin */
    @NonNull
    private String classify(@NonNull String raw, @Nullable Uri normalized) {
        // Pairing: 16 haneli hex (ANDROID_ID gibi)
        if (raw.matches("(?i)^[0-9a-f]{16}$")) return "PAIRING";

        // Normalized route "iyesi://" olduysa ROUTE
        if (normalized != null && "iyesi".equalsIgnoreCase(normalized.getScheme())) return "ROUTE";

        // http/https/market ise URL
        try {
            Uri u = Uri.parse(raw);
            String sch = u.getScheme();
            if ("http".equalsIgnoreCase(sch) || "https".equalsIgnoreCase(sch) || "market".equalsIgnoreCase(sch)) {
                return "URL";
            }
        } catch (Exception ignore) {}

        return "TEXT";
    }

    // (İsteğe bağlı) kullanıcıya küçük bir bilgi verme
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
}
