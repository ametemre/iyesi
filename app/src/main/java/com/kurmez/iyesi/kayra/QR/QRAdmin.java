package com.kurmez.iyesi.kayra.QR;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.kurmez.iyesi.R;

public class QRAdmin extends AppCompatActivity {
    private static final String TAG = "QRAdmin";

    private @Nullable String lastRaw = null; // En güncel taranan/üretilen içerik

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(TAG, "[onCreate] in");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        // Intent ile gelen veri (varsa)
        String qrData = getIntent().getStringExtra("qr_data");
        Log.i(TAG, "[getExtra] String qrData = getIntent().getStringExtra(\"qr_data\");");

        // View referansları
        TextView qrDataTextView   = findViewById(R.id.qr_data_text_view);
        EditText qrDataEditText   = findViewById(R.id.go_to_textField);
        Button   scanQRButton     = findViewById(R.id.scan_qr_button);
        Button   generateQRButton = findViewById(R.id.generate_qr_button);
        Button   processButton    = findViewById(R.id.process_button);
        Button   go_to_button    = findViewById(R.id.go_to_button);

        if (!TextUtils.isEmpty(qrData)) {
            Log.i(TAG, "[method] in");
            lastRaw = qrData;
            qrDataTextView.setText("QR Data: " + qrData);
        } else {
            qrDataTextView.setText("QR Data will appear here");
        }

        // Tara - QRScannerActivity'yi kullan
        scanQRButton.setOnClickListener(v -> startQRScanner());

        // Üret (ayar pop-up → onay → önizleme pop-up)
        generateQRButton.setOnClickListener(v -> openGenerateDialog());

        // İşle
        processButton.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastRaw)) {
                Log.i(TAG, "[method] in");
                // QR'daki ham veriyi işle, dış linklere gitme
                processQRContent(lastRaw);
            } else {
                Toast.makeText(QRAdmin.this, "No QR Data to process", Toast.LENGTH_SHORT).show();
            }
        });
        go_to_button.setOnClickListener(v -> {
            String target = String.valueOf(qrDataEditText.getText());
            Log.i(TAG + "goTo",target);
            QrRouteResolver.resolveTarget(target,this);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(TAG, "[onResume] QRAdmin aktif");
        // TextView içeriğini güncelle (tarama sonrası kaybolmaması için)
        updateQRDataView();
    }

    private void updateQRDataView() {
        TextView qrDataTextView = findViewById(R.id.qr_data_text_view);
        if (!TextUtils.isEmpty(lastRaw)) {
            qrDataTextView.setText("QR Data: " + lastRaw);
        }
    }

    private void processQRContent(String rawContent) {
        Log.i(TAG, "[processQRContent] in: " + rawContent);

        // Özel işleme: "welcome" route'u için özel davranış
        if ("welcome".equals(rawContent)) {
            Log.i(TAG, "[processQRContent] Welcome route detected");
            handleWelcomeRoute();
            return;
        }

        // Normalize et (iyesi:// formatına çevir)
        Uri normalized = QR.normalizeRoute(rawContent);

        if ("iyesi".equalsIgnoreCase(normalized.getScheme())) {
            Log.i(TAG, "[method] in");
            // Uygulama içi route - doğrudan işle
            handleInternalRoute(normalized);
        } else if (rawContent.startsWith("http")) {
            Log.i(TAG, "[method] in");
            // HTTP linki - içindeki veriyi çıkar ve işle
            extractAndProcessFromUrl(rawContent);
        } else {
            // Düz metin - olduğu gibi kullan
            handlePlainText(rawContent);
        }
    }

    private void handleWelcomeRoute() {
        Log.i(TAG, "[handleWelcomeRoute] in");
        // Welcome sayfası için özel işlem
        Toast.makeText(this, "Welcome sayfası açılıyor...", Toast.LENGTH_LONG).show();

        // Burada veteriner kayıtlı sokak hayvanları listesini göster
        // Örnek: Intent ile WelcomeActivity'yi aç
        // Intent i = new Intent(this, WelcomeActivity.class);
        // startActivity(i);

        // Şimdilik sadece mesaj göster
        TextView qrDataTextView = findViewById(R.id.qr_data_text_view);
        qrDataTextView.setText("Welcome - Veteriner Kayıtlı Sokak Hayvanları Listesi");
    }

    private void handleInternalRoute(Uri route) {
        Log.i(TAG, "[handleInternalRoute] in");
        // iyesi://... formatındaki route'u işle
        String host = route.getHost();
        String path = route.getPath();

        Toast.makeText(this, "Internal Route: " + host + path, Toast.LENGTH_LONG).show();

        // Burada route'a göre uygulama içi işlemler yap
        if ("souls".equals(host)) {
            Log.i(TAG, "[method] in");
            String soulId = route.getLastPathSegment();
            // Soul detayını aç...
        }
        // ... diğer route'lar
    }

    private void extractAndProcessFromUrl(String url) {
        Log.i(TAG, "[extractAndProcessFromUrl] in");
        try {
            Uri uri = Uri.parse(url);
            Log.i(TAG, "[Uri.parse] Uri uri = Uri.parse(url);");

            // Cloud Functions redirect URL'sinden veriyi çıkar
            if (uri.getHost() != null && uri.getHost().contains("us-central1")) {
                Log.i(TAG, "[method] in");
                String toParam = uri.getQueryParameter("to");
                if (!TextUtils.isEmpty(toParam)) {
                    Log.i(TAG, "[method] in");
                    // to parametresinden internal route'u al
                    processQRContent("iyesi://" + toParam);
                    return;
                }

                String slugParam = uri.getQueryParameter("slug");
                if (!TextUtils.isEmpty(slugParam)) {
                    Log.i(TAG, "[method] in");
                    // slug'dan internal route oluştur
                    processQRContent(slugParam);
                    return;
                }
            }

            // iyesi.app/d/... formatı
            if ("iyesi.app".equals(uri.getHost()) && uri.getPath() != null && uri.getPath().startsWith("/d/")) {
                Log.i(TAG, "[method] in");
                String route = uri.getPath().substring(3); // /d/ kısmını atla
                processQRContent("iyesi://" + route);
                return;
            }

            // Diğer HTTP linkleri için ham veriyi göster
            Toast.makeText(this, "URL Content: " + url, Toast.LENGTH_LONG).show();

        } catch (Exception e) {
            Log.i(TAG, "[catch] in");
            Toast.makeText(this, "URL parse error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void handlePlainText(String text) {
        Log.i(TAG, "[handlePlainText] in");
        // Düz metin QR içeriği
        if (text.matches("[A-Za-z0-9_-]+")) {
            Log.i(TAG, "[method] in");
            // Slug formatında - internal route'a çevir
            Uri slugRoute = QR.normalizeRoute(text);
            processQRContent(slugRoute.toString());
        } else {
            // Gerçek düz metin
            Toast.makeText(this, "Text Content: " + text, Toast.LENGTH_LONG).show();
        }
    }

    private void openGenerateDialog() {
        Log.i(TAG, "[openGenerateDialog] in");
        QR.QROptions def = new QR.QROptions();
        def.dotShape       = QR.QROptions.DotShape.SQUARE; // kare daha kolay okunur
        def.dotScale       = 0.48f;
        def.sparseModulo   = 0;
        def.holeRatio      = 0f;        // şimdilik kapalı
        def.quietZone      = 4;         // 1 yerine 4
        def.ecLevel        = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q;
        def.transparentBg  = false;
        // içerik
        def.redirectBaseUrl = "https://iyesi-host-pnfxz2soua-uc.a.run.app";
        def.toRoute         = "/d/welcome"; // Welcome route için
        def.hiddenCode      = "AB7QK9D";
        def.fragmentNoHash  = "";

        // SADECE BİR DİALOG ÇAĞRISI
        QR.showGenerateDialog(this, def, 1024, (qrBmp, contentUsed) -> {
            ImageView preview = findViewById(R.id.qr_image_preview);
            if (preview != null) preview.setImageBitmap(qrBmp);

            lastRaw = contentUsed;
            updateQRDataView();
        });
    }

    private void startQRScanner() {
        Log.d("QRAdmin", "Starting QR scanner...");
        // QRScannerActivity'yi başlat
        QRScannerActivity.launchForResult(this, QR.REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        Log.d("QRAdmin", "onActivityResult req=" + requestCode + " res=" + resultCode);

        if (requestCode == QR.REQ_SCAN) {
            // QRScannerActivity sonucu
            if (resultCode == Activity.RESULT_OK && data != null) {
                String raw = data.getStringExtra(QRScannerActivity.EXTRA_QR_RAW);
                if (!TextUtils.isEmpty(raw)) {
                    lastRaw = raw;
                    updateQRDataView();
                    Toast.makeText(this, "Scanned: " + raw, Toast.LENGTH_SHORT).show();

                    // Otomatik işleme
                    processQRContent(raw);
                }
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == IntentIntegrator.REQUEST_CODE) {
            // ZXing scanner sonucu (fallback)
            IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
            if (result != null) {
                if (result.getContents() != null) {
                    String raw = result.getContents().trim();
                    lastRaw = raw;
                    updateQRDataView();
                    Toast.makeText(this, "Scanned: " + raw, Toast.LENGTH_SHORT).show();
                    processQRContent(raw);
                } else {
                    Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (lastRaw != null) {
            outState.putString("lastRaw", lastRaw);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        lastRaw = savedInstanceState.getString("lastRaw");
        updateQRDataView();
    }
}