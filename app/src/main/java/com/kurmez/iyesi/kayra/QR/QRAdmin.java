package com.kurmez.iyesi.kayra.QR;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.kurmez.iyesi.R;

public class QRAdmin extends AppCompatActivity {

    private @Nullable String lastRaw = null; // En güncel taranan/üretilen içerik

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        // Intent ile gelen veri (varsa)
        String qrData = getIntent().getStringExtra("qr_data");

        // View referansları
        TextView qrDataTextView   = findViewById(R.id.qr_data_text_view);
        Button   scanQRButton     = findViewById(R.id.scan_qr_button);
        Button   generateQRButton = findViewById(R.id.generate_qr_button);
        Button   processButton    = findViewById(R.id.process_button);

        if (!TextUtils.isEmpty(qrData)) {
            lastRaw = qrData;
            qrDataTextView.setText("QR Data: " + qrData);
        } else {
            qrDataTextView.setText("QR Data will appear here");
        }

        // Tara
        scanQRButton.setOnClickListener(v -> startQRScanner());

        // Üret (ayar pop-up → onay → önizleme pop-up)
        generateQRButton.setOnClickListener(v -> openGenerateDialog());

        // İşle
        processButton.setOnClickListener(v -> {
            if (!TextUtils.isEmpty(lastRaw)) {
                Toast.makeText(QRAdmin.this, "Processing: " + lastRaw, Toast.LENGTH_SHORT).show();
                if (lastRaw.startsWith("http")) {
                    Toast.makeText(QRAdmin.this, "This looks like a URL!", Toast.LENGTH_SHORT).show();

                } else {
                    Toast.makeText(QRAdmin.this, "Data: " + lastRaw, Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(QRAdmin.this, "No QR Data to process", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void openGenerateDialog() {
        QR.QROptions def = new QR.QROptions();
        def.dotShape       = QR.QROptions.DotShape.SQUARE; // kare daha kolay okunur
        def.dotScale       = 0.48f;
        def.sparseModulo   = 0;
        def.holeRatio      = 0f;        // şimdilik kapalı
        def.quietZone      = 4;         // 1 yerine 4
        def.ecLevel        = com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q;
        def.transparentBg  = false;
// içerik
        def.redirectBaseUrl = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/redirect";
        def.toRoute         = "/souls/06kKAcLDhBnFOH9Er98Y";
        def.hiddenCode      = "AB7QK9D";
        def.fragmentNoHash  = "";        // fragmanı şimdilik boş bırak (uzunluk ↓)

        QR.showGenerateDialog(this, def, 1024, (bmp, content) -> {
            ImageView preview = findViewById(R.id.qr_image_preview);
            if (preview != null) preview.setImageBitmap(bmp);
        });

        // Pop-up: Şekil (Daire/Kare), Nokta büyüklüğü (0.30–0.48), Yoğunluk (0/3/2), Link, #veri
        QR.showGenerateDialog(
                this,
                def,
                /* sizePx */ 1024,
                (qrBmp, contentUsed) -> {
                    // İkinci pop-up zaten önizlemeyi gösteriyor; ekranda da tutmak istersen:
                    ImageView preview = findViewById(R.id.qr_image_preview); // layout’ta yoksa null döner
                    if (preview != null) preview.setImageBitmap(qrBmp);

                    // Process için güncel veriyi sakla ve ekrana yaz
                    lastRaw = contentUsed;
                    TextView tv = findViewById(R.id.qr_data_text_view);
                    if (tv != null) tv.setText("QR Data: " + contentUsed);
                }
        );
    }

    private void startQRScanner() {
        startActivityForResult(QR.newScanIntent(this), QR.REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        String raw = QR.parseScanResult(requestCode, resultCode, data);
        Log.d("QR", "onActivityResult req=" + requestCode + " res=" + resultCode +
                " data=" + (data != null ? data.getExtras() : "null"));

        TextView qrDataTextView = findViewById(R.id.qr_data_text_view);

        if (raw != null) {
            lastRaw = raw; // en güncel veri
            qrDataTextView.setText("QR Data: " + raw);
            QR.route(this, raw);
            finish();
            Toast.makeText(this, "Scanned: " + raw, Toast.LENGTH_SHORT).show();
        } else if (requestCode == QR.REQ_SCAN) {
            Toast.makeText(this, "Scan canceled or empty", Toast.LENGTH_SHORT).show();
        }
    }
}
