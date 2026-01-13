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

import com.kurmez.iyesi.R;
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
        Log.i(TAG, "[launchForResult] in");
        Intent i = new Intent(caller, QRScannerActivity.class);
        caller.startActivityForResult(i, requestCode);
        Log.i(TAG, "[startActivityForResult] caller.startActivityForResult(i, requestCode);");
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Deep-link ile açılmış mı kontrolü
        Log.i(TAG, "[onCreate] in");
        super.onCreate(savedInstanceState);

        // 1) Deep-link ile açılmış mı? (ACTION_VIEW + data)
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null) {
            Log.i(TAG, "[if] in");
            String raw = data.toString();
            finishWithSuccess(raw, /*isDeeplink=*/true);
            return;
        }

        // 2) Kamera izni kontrolü
        if (!hasCameraPermission()) {
            Log.i(TAG, "[method] in");
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            return;
        }

        // 3) Taramayı başlat
        startZxingScan();
    }

    private boolean hasCameraPermission() {
        Log.i(TAG, "[hasCameraPermission] in");
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startZxingScan() {
        Log.i(TAG, "[startZxingScan] in");
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        // ÖNCE: Hardcoded "QR'ı hizalayın"
        // ŞİMDİ: String resource kullanımı
        integrator.setPrompt(getString(R.string.qr_scanner_prompt_align));
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] perms, @NonNull int[] grantResults) {
        Log.i(TAG, "[onRequestPermissionsResult] in");
        super.onRequestPermissionsResult(requestCode, perms, grantResults);
        if (requestCode == REQ_CAMERA) {
            Log.i(TAG, "[if] in");
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "[if] in");
                startZxingScan();
            } else {
                finishWithError("permission_denied");
            }
        }
    }
    /**
     * Taranan içeriği işler - BaseLink'i tamamen çıkarır
     */
    private String processScannedContent_raw(String rawContent) {
        Log.i(TAG, "[processScannedContent] in: " + rawContent);

        // Base URL'yi kontrol et
        String baseUrl = "https://iyesi-host-pnfxz2soua-uc.a.run.app";

        if (rawContent.contains(baseUrl)) {
            Log.i(TAG, "[processScannedContent] Base URL tespit edildi");

            // Base URL'yi tamamen çıkar
            String processedContent = rawContent.replace(baseUrl, "");
            Log.i(TAG, "[processScannedContent] Base URL çıkarıldı: " + processedContent);

            // Boş string kontrolü
            if (processedContent.isEmpty()) {
                Log.i(TAG, "[processScannedContent] Sadece base URL, welcome döndürülüyor");
                return "welcome";
            }

            // Baştaki ve sondaki slash'leri temizle
            processedContent = processedContent.replaceAll("^/+", "").replaceAll("/+$", "");

            // Eğer /d/ formatındaysa, /d/ kısmını da çıkar
            if (processedContent.startsWith("d/")) {
                processedContent = processedContent.substring(2);
            }

            Log.i(TAG, "[processScannedContent] İşlenmiş içerik: " + processedContent);
            return processedContent;
        }

        // Diğer durumlarda ham içeriği döndür
        return rawContent;
    }
    /**
     * Taranan içeriği işler - BaseLink'i tamamen çıkarır
     */
    private String processScannedContent(String rawContent) {
        Log.i(TAG, "[processScannedContent] in: " + rawContent);

        String baseUrl = "https://iyesi-host-pnfxz2soua-uc.a-run.app";

        if (rawContent.contains(baseUrl)) {
            Log.i(TAG, "[processScannedContent] Base URL tespit edildi");

            // Base URL'yi ve ardından gelen path/fragment'i ayır
            String remaining = rawContent.substring(baseUrl.length());

            // Boşsa welcome döndür
            if (remaining.isEmpty()) {
                return "welcome";
            }

            // Path'de /d/ varsa onu da çıkar
            if (remaining.startsWith("/d/")) {
                remaining = remaining.substring(3);
            }
            // Sadece / ile başlıyorsa onu çıkar
            else if (remaining.startsWith("/")) {
                remaining = remaining.substring(1);
            }
            // # ile başlıyorsa onu çıkar
            else if (remaining.startsWith("#")) {
                remaining = remaining.substring(1);
            }

            Log.i(TAG, "[processScannedContent] İşlenmiş: " + remaining);
            return remaining;
        }

        return rawContent;
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.i(TAG, "[onActivityResult] requestCode=" + requestCode + ", resultCode=" + resultCode);

        // Sadece ZXing sonucunu işle
        IntentResult zxingResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        String BaseLink = "https://iyesi-host-pnfxz2soua-uc.a.run.app";
        if (zxingResult != null) {
            if (zxingResult.getContents() != null) {
                // Başarılı tarama
                String rawContent = zxingResult.getContents();
                if (rawContent.contains(BaseLink)){
                    String target = processScannedContent_raw(rawContent);
                    Log.i(TAG, "Applikasyon linkli :" + target );
                    QrRouteResolver qrRouteResolver = new QrRouteResolver();
                    qrRouteResolver.resolveTarget(target,this);
                }else {
                    Log.i(TAG, "[onActivityResult] Scan successful: " + rawContent);
                    finishWithSuccess(rawContent, false);
                }
            } else {
                // Tarama iptal edildi
                Log.i(TAG, "[onActivityResult] Scan cancelled");
                finishWithError("scan_cancelled");
            }
        } else {
            Log.i(TAG, "[onActivityResult] No ZXing result");
        }
    }

    private void finishWithSuccess(@NonNull String raw, boolean isDeeplink) {
        Log.i(TAG, "[finishWithSuccess] in");
        try {
            // Normalize edilmiş rota (iyi-esi://..., iyesi.app/d/... → iyesi://..., CF redirect vb.)
            Uri normalized = com.kurmez.iyesi.kayra.QR.QR.normalizeRoute(raw);

            Intent out = new Intent();
            out.putExtra(EXTRA_QR_RAW, raw);
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_RAW, raw);");
            out.putExtra(EXTRA_QR_ROUTE, normalized != null ? normalized.toString() : raw);
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_ROUTE, normalized != null ? normalized.toString() : raw);");
            out.putExtra(EXTRA_QR_IS_DEEPLINK, isDeeplink);
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_IS_DEEPLINK, isDeeplink);");
            out.putExtra(EXTRA_QR_KIND, classify(raw, normalized));
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_KIND, classify(raw, normalized));");
            out.putExtra(EXTRA_SCANNED_DATA, classify(raw, normalized));
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_SCANNED_DATA, classify(raw, normalized));");

            setResult(RESULT_OK, out);
            Log.i(TAG, "[setResult] setResult(RESULT_OK, out);");
        } catch (Throwable t) {
            Log.i(TAG, "[catch] in");
            Log.e(TAG, "finishWithSuccess error", t);
            Intent out = new Intent();
            out.putExtra(EXTRA_QR_RAW, raw);
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_RAW, raw);");
            out.putExtra(EXTRA_QR_ERROR, "normalize_failed");
            Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_ERROR, \"normalize_failed\");");
            setResult(RESULT_OK, out); // ham veriyi yine de geri ver
            Log.i(TAG, "[setResult] setResult(RESULT_OK, out); // ham veriyi yine de geri ver");
        }
        finish();
        Log.i(TAG, "[finish] called");
    }

    private void finishWithError(@NonNull String error) {
        Log.i(TAG, "[finishWithError] in");
        Intent out = new Intent();
        out.putExtra(EXTRA_QR_ERROR, error);
        Log.i(TAG, "[putExtra] out.putExtra(EXTRA_QR_ERROR, error);");
        setResult(RESULT_CANCELED, out);
        Log.i(TAG, "[setResult] setResult(RESULT_CANCELED, out);");
        finish();
        Log.i(TAG, "[finish] called");
    }

    /** Kabaca sınıflandır: eşleştirme/route/url/düz metin */
    @NonNull
    private String classify(@NonNull String raw, @Nullable Uri normalized) {
        Log.i(TAG, "[classify] in");
        // Sadece basit sınıflandırma
        if (raw.startsWith("iyesi://")) return "INTERNAL_ROUTE";
        if (raw.startsWith("http")) return "EXTERNAL_URL";
        if (raw.matches("(?i)^[0-9a-f]{16}$")) return "PAIRING_CODE";
        if (normalized != null && "iyesi".equalsIgnoreCase(normalized.getScheme())) return "ROUTE";

        return "PLAIN_TEXT";
    }

    // (İsteğe bağlı) kullanıcıya küçük bir bilgi verme
    private void toast(String msg) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); }
    //Log.i(TAG, "[toast] in");
}