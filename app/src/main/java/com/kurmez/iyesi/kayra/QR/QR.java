package com.kurmez.iyesi.kayra.QR;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import com.google.android.gms.tasks.Task;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.lang.reflect.Field;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * QR – tek merkez:
 * - startScan / parseScanResult
 * - normalizeRoute / buildIntentFromQr / route
 * - showGenerateDialog (radio + slider + Link + #veri → önizleme)
 * - generate (daire/kare modül, opsiyonel logo ve merkez boşluğu)
 * - composeSmartPayload (redirect + toRoute + hc + #fragment)
 * - callFunction (Cloud Functions wrapper)
 */
public final class QR {
    private static final String TAG = "QR";


    private QR() {}


    // ============================================================
    // 1) TARAMA (ZXing + özel scanner)
    // ============================================================
    public static void startScan(@NonNull Activity activity) {
        Log.i(TAG, "[startScan] in");
        IntentIntegrator integrator = new IntentIntegrator(activity);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt("QR'ı hizalayın");
        integrator.setBeepEnabled(true);
        integrator.setBarcodeImageEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Nullable
    public static String parseScanResult(int requestCode, int resultCode, @Nullable Intent data) {
        Log.i(TAG, "[parseScanResult] requestCode=" + requestCode + ", resultCode=" + resultCode);

        // 1. Önce ZXing sonucunu kontrol et
        IntentResult z = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (z != null && z.getContents() != null) {
            Log.i(TAG, "[parseScanResult] ZXing result: " + z.getContents());
            return z.getContents().trim();
        }

        // 2. QRScannerActivity'den gelen sonucu kontrol et
        if (requestCode == REQ_SCAN && resultCode == Activity.RESULT_OK && data != null) {
            Log.i(TAG, "[parseScanResult] Checking QRScannerActivity result");

            // Önce QRScannerActivity'nin kendi formatını dene
            String raw = data.getStringExtra("com.kurmez.iyesi.qr.EXTRA_QR_RAW");
            Log.i(TAG, "[parseScanResult] QRScannerActivity EXTRA_QR_RAW: " + raw);

            // Eski formatı da dene
            if (raw == null) {
                raw = data.getStringExtra(EXTRA_RAW);
                Log.i(TAG, "[parseScanResult] Legacy EXTRA_RAW: " + raw);
            }

            return raw;
        }

        Log.i(TAG, "[parseScanResult] No result found");
        return null;
    }

    // ============================================================
    // 2) ROUTE NORMALİZASYONU + INTENT ÜRETİMİ
    // ============================================================
    public interface TargetResolver {
        @Nullable Intent resolve(@NonNull Context ctx, @NonNull Uri normalizedRoute);
    }

    private static volatile TargetResolver sResolver;

    public static void installResolver(@Nullable TargetResolver resolver) {
        Log.i(TAG, "[installResolver] in");
        sResolver = resolver;
    }

    @NonNull
    public static Uri normalizeRoute(@NonNull String raw) {
        Log.i(TAG, "[normalizeRoute] in");
        try {
            String t = raw.trim();
            if (t.startsWith("{") && t.endsWith("}")) {
                Log.i(TAG, "[method] in");
                int idx = t.indexOf("\"route\"");
                if (idx >= 0) {
                    Log.i(TAG, "[if] in");
                    int q1 = t.indexOf('"', idx + 7);
                    int q2 = t.indexOf('"', q1 + 1);
                    if (q1 > 0 && q2 > q1) {
                        Log.i(TAG, "[if] in");
                        String route = t.substring(q1 + 1, q2);
                        Log.i(TAG, "[Uri.parse] return Uri.parse(\"iyesi://\" + trimLeadingSlash(route));");
                        return Uri.parse("iyesi://" + trimLeadingSlash(route));
                    }
                }
            }

            Uri uri = Uri.parse(raw);
            Log.i(TAG, "[Uri.parse] Uri uri = Uri.parse(raw);");

            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getHost().contains("us-central1-")) {
                Log.i(TAG, "[method] in");
                String to = uri.getQueryParameter("to");
                if (!TextUtils.isEmpty(to)) {
                    Log.i(TAG, "[method] in");
                    Log.i(TAG, "[Uri.parse] return Uri.parse(\"iyesi://\" + trimLeadingSlash(to));");
                    return Uri.parse("iyesi://" + trimLeadingSlash(to));
                }
                String slug = uri.getQueryParameter("slug");
                if (!TextUtils.isEmpty(slug)) {
                    Log.i(TAG, "[method] in");
                    return slugToRoute(slug);
                }
            }

            if ("https".equalsIgnoreCase(uri.getScheme())
                    && "iyesi.app".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/d/")) {
                Log.i(TAG, "[method] in");
                String route = uri.getPath().substring(3);
                Uri n = Uri.parse("iyesi://" + trimLeadingSlash(route));
                Log.i(TAG, "[Uri.parse] Uri n = Uri.parse(\"iyesi://\" + trimLeadingSlash(route));");
                if (uri.getQuery() != null) {
                    Log.i(TAG, "[method] in");
                    n = n.buildUpon().encodedQuery(uri.getEncodedQuery()).build();
                }
                return n;
            }

            if ("iyesi".equalsIgnoreCase(uri.getScheme())) return uri;

            if (raw.matches("[A-Za-z0-9_-]+")) return slugToRoute(raw);

            return uri;

        } catch (Throwable e) {
            Log.i(TAG, "[catch] in");
            Log.e("QR", "normalizeRoute error: " + e);
            Log.i(TAG, "[Uri.parse] return Uri.parse(raw);");
            return Uri.parse(raw);
        }
    }

    @Nullable
    public static Intent buildIntentFromQr(@NonNull Context ctx, @NonNull String raw) {
        Log.i(TAG, "[buildIntentFromQr] in");
        Uri route = normalizeRoute(raw);

        TargetResolver r = sResolver;
        if (r != null && "iyesi".equalsIgnoreCase(route.getScheme())) {
            Log.i(TAG, "[method] in");
            Intent custom = r.resolve(ctx, route);
            if (custom != null) return custom;
        }

        if ("https".equalsIgnoreCase(route.getScheme())
                || "http".equalsIgnoreCase(route.getScheme())
                || "market".equalsIgnoreCase(route.getScheme())) {
            Log.i(TAG, "[method] in");
            return new Intent(Intent.ACTION_VIEW, route);
        }

        if ("iyesi".equalsIgnoreCase(route.getScheme())) {
            Log.i(TAG, "[method] in");
            Intent i = new Intent(ctx, com.kurmez.iyesi.MainActivity.class);
            i.putExtra("route", route.toString());
            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
            return i;
        }
        return new Intent(Intent.ACTION_VIEW, route);
    }

    public static boolean route(@NonNull Context ctx, @NonNull String raw) {
        Log.i(TAG, "[route] in");
        Intent i = buildIntentFromQr(ctx, raw);
        if (i != null) {
            Log.i(TAG, "[if] in");
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
            Log.i(TAG, "[startActivity] ctx.startActivity(i);");
            return true;
        }
        return false;
    }

    // ============================================================
    // 3) POP-UP: Radio (şekil) + Sliderlar + Link + #veri → Generate → Önizleme
    // ============================================================
    public interface OnGenerated {
        void onGenerated(@NonNull Bitmap qr, @NonNull String contentUsed);
    }

    public static void showGenerateDialog(@NonNull Activity a,
                                          @NonNull QROptions defaults,
                                          int sizePx,
                                          @Nullable OnGenerated cb) {
        Log.i(TAG, "[method] in");

        final int pad = (int) (a.getResources().getDisplayMetrics().density * 16);

        ScrollView sc = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        // --- Şekil seçimi (yan yana, isimsiz) ---
        TextView tvShape = new TextView(a);
        tvShape.setText("Şekil");
        root.addView(tvShape);

        RadioGroup rgShape = new RadioGroup(a);
        rgShape.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton rbCircle = new RadioButton(a);
        RadioButton rbSquare = new RadioButton(a);
        // Çok yer kaplamasın diye sadece sembol veriyoruz
        rbCircle.setText("●");  // daire
        rbSquare.setText("■");  // kare
        rbCircle.setContentDescription("Daire");
        rbSquare.setContentDescription("Kare");
        rgShape.addView(rbCircle);
        rgShape.addView(rbSquare);
        if (defaults.dotShape == QROptions.DotShape.SQUARE) rbSquare.setChecked(true);
        else rbCircle.setChecked(true);
        root.addView(rgShape);

        // --- Nokta büyüklüğü (0.30–0.48) ---
        TextView tvDot = new TextView(a);
        tvDot.setText("Nokta Büyüklüğü");
        root.addView(tvDot);

        SeekBar sbDot = new SeekBar(a);
        sbDot.setMax(100);
        int startDot = (int) ((clamp(defaults.dotScale, 0.30f, 0.48f) - 0.30f) / (0.48f - 0.30f) * 100f);
        sbDot.setProgress(startDot);
        root.addView(sbDot);

        TextView tvDotVal = new TextView(a);
        tvDotVal.setText(String.format(java.util.Locale.US, "%.02f", clamp(defaults.dotScale, 0.30f, 0.48f)));
        root.addView(tvDotVal);

        Log.i(TAG, "[setOnSeekBarChangeListener] in");
        sbDot.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                Log.i(TAG, "[onProgressChanged] in");
                float val = map(p, 0, 100, 0.30f, 0.48f);
                tvDotVal.setText(String.format(java.util.Locale.US, "%.02f", val));
            }
        });

        // --- Yoğunluk / Seyreltme ---
        TextView tvSp = new TextView(a);
        tvSp.setText("Yoğunluk / Seyreltme");
        root.addView(tvSp);

        SeekBar sbSp = new SeekBar(a);
        sbSp.setMax(2); // 0=kapalı, 1=mod3, 2=mod2
        int startSparse = (defaults.sparseModulo == 3) ? 1 : (defaults.sparseModulo == 2 ? 2 : 0);
        sbSp.setProgress(startSparse);
        root.addView(sbSp);

        TextView tvSpVal = new TextView(a);
        tvSpVal.setText(startSparse==0 ? "Tam (mod 0)" : startSparse==1 ? "Seyrek: 3'te1 (mod3)" : "Seyrek: 2'de1 (mod2)");
        root.addView(tvSpVal);

        sbSp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                Log.i(TAG, "[onProgressChanged] in");
                tvSpVal.setText(p==0 ? "Tam (mod 0)" : p==1 ? "Seyrek: 3'te1 (mod3)" : "Seyrek: 2'de1 (mod2)");
            }
        });

        // --- Merkez boşluğu (logo) ---
        TextView tvHole = new TextView(a);
        tvHole.setText("Merkez Boşluğu (Logo)");
        root.addView(tvHole);

        final android.widget.CheckBox cbHole = new android.widget.CheckBox(a);
        cbHole.setText("Merkezde dairesel boşluk kullan");
        cbHole.setChecked(defaults.holeRatio > 0f);
        root.addView(cbHole);

        final TextView tvHoleVal = new TextView(a);
        tvHoleVal.setText(String.format(java.util.Locale.US, "Çap oranı: %.02f", clamp(defaults.holeRatio, 0f, 0.35f)));
        root.addView(tvHoleVal);

        final SeekBar sbHole = new SeekBar(a);
        sbHole.setMax(100);
        int startHole = (int) (clamp(defaults.holeRatio, 0f, 0.35f) / 0.35f * 100f);
        sbHole.setProgress(startHole);
        root.addView(sbHole);

        sbHole.setEnabled(cbHole.isChecked());
        tvHoleVal.setEnabled(cbHole.isChecked());
        cbHole.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sbHole.setEnabled(isChecked);
            tvHoleVal.setEnabled(isChecked);
        });
        sbHole.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                Log.i(TAG, "[onProgressChanged] in");
                float val = map(p, 0, 100, 0f, 0.35f);
                tvHoleVal.setText(String.format(java.util.Locale.US, "Çap oranı: %.02f", val));
            }
        });

        // --- Logo seçimi (drawable listesi) ---
        TextView tvLogo = new TextView(a);
        tvLogo.setText("Logo (opsiyonel)");
        root.addView(tvLogo);

        final int[] selectedLogoResId = {-1};
        LinearLayout logoRow = new LinearLayout(a);
        logoRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(logoRow);

        final ImageView ivLogo = new ImageView(a);
        ivLogo.setAdjustViewBounds(true);
        ivLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int logoPreviewPx = (int) (a.getResources().getDisplayMetrics().density * 48); // 48dp
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(logoPreviewPx, logoPreviewPx);
        ivLp.rightMargin = pad / 2;
        logoRow.addView(ivLogo, ivLp);

        Button btnPickLogo = new Button(a);
        btnPickLogo.setText("Seç");
        logoRow.addView(btnPickLogo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btnClearLogo = new Button(a);
        btnClearLogo.setText("Kaldır");
        logoRow.addView(btnClearLogo);

        btnClearLogo.setOnClickListener(v -> {
            selectedLogoResId[0] = -1;
            ivLogo.setImageDrawable(null);
        });

        btnPickLogo.setOnClickListener(v -> {
            List<DrawableItem> items = listAppDrawables(a);
            String[] names = new String[items.size() + 1];
            names[0] = "Yok (logo kullanma)";
            for (int i = 0; i < items.size(); i++) names[i + 1] = items.get(i).name;

            new androidx.appcompat.app.AlertDialog.Builder(a)
                    .setTitle("Logo Seç")
                    .setItems(names, (d, which) -> {
                        if (which == 0) {
                            Log.i(TAG, "[if] in");
                            selectedLogoResId[0] = -1;
                            ivLogo.setImageDrawable(null);
                        } else {
                            DrawableItem it = items.get(which - 1);
                            selectedLogoResId[0] = it.resId;
                            Bitmap preview = loadBitmapFromDrawable(a, it.resId, logoPreviewPx);
                            ivLogo.setImageBitmap(preview);
                            if (!cbHole.isChecked()) {
                                Log.i(TAG, "[method] in");
                                cbHole.setChecked(true);
                                int pHole = (int) (0.22f / 0.35f * 100f);
                                sbHole.setProgress(pHole);
                            }
                        }
                    })
                    .show();
        });

        // --- HTTP Link ---
        EditText etLink = new EditText(a);
        etLink.setHint("HTTP Link (tam URL)");
        etLink.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        etLink.setText(buildSmartContentFromOptions(defaults));
        root.addView(etLink);

        // --- #veri (fragment) ---
        EditText etFrag = new EditText(a);
        etFrag.setHint("#veri (örn: abc123) — '#' yazma");
        etFrag.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etFrag.setText(defaults.fragmentNoHash);
        root.addView(etFrag);

        // --- Generate ---
        Button btnGen = new Button(a);
        btnGen.setText("Generate");
        root.addView(btnGen, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(a)
                        .setTitle("QR Ayarları")
                        .setView(sc)
                        .setCancelable(true)
                        .create();

        btnGen.setOnClickListener(v -> {
            QROptions o = new QROptions();

            o.dotShape = (rgShape.getCheckedRadioButtonId() == rbSquare.getId())
                    ? QROptions.DotShape.SQUARE
                    : QROptions.DotShape.CIRCLE;

            o.dotScale = map(sbDot.getProgress(), 0, 100, 0.30f, 0.48f);

            int sp = sbSp.getProgress();
            o.sparseModulo = (sp==0) ? 0 : (sp==1) ? 3 : 2;

            if (cbHole.isChecked()) o.holeRatio = map(sbHole.getProgress(), 0, 100, 0f, 0.35f);
            else o.holeRatio = 0f;

            // diğerleri
            o.logoScaleInHole = defaults.logoScaleInHole;
            o.quietZone       = defaults.quietZone;
            o.transparentBg   = defaults.transparentBg;
            o.ecLevel         = defaults.ecLevel;

            // seçilen logo → bitmap
            if (selectedLogoResId[0] > 0 && o.holeRatio > 0f) {
                Log.i(TAG, "[if] in");
                int target = Math.round(sizePx * o.holeRatio);
                o.centerLogo = loadBitmapFromDrawable(a, selectedLogoResId[0], target);
            } else {
                o.centerLogo = null;
            }

            // içerik
            o.redirectBaseUrl = defaults.redirectBaseUrl;
            o.toRoute         = defaults.toRoute;
            o.hiddenCode      = defaults.hiddenCode;

            String link = etLink.getText().toString().trim();
            String frag = etFrag.getText().toString().trim();

            String content = link.isEmpty() ? buildSmartContentFromOptions(o) : link;
            if (!frag.isEmpty()) {
                Log.i(TAG, "[method] in");
                String fragClean = frag.startsWith("#") ? frag.substring(1) : frag;
                content = content + "#" + Uri.encode(fragClean);
            }

            try {
                Bitmap qr = generate(content, sizePx, o);
                dlg.dismiss();
                showPreviewDialog(a, content, qr);
                if (cb != null) cb.onGenerated(qr, content);
            } catch (WriterException e) {
                Log.i(TAG, "[catch] in");
                Log.e("QR", "Generate failed", e);
                Toast.makeText(a, "QR üretilemedi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        dlg.show();
    }

    // --- Drawable listeleme & yükleme yardımcıları ---
    private static final class DrawableItem {
        final String name; final int resId;
        DrawableItem(String n, int id) { name = n; resId = id; }
    }

    @NonNull
    private static List<DrawableItem> listAppDrawables(@NonNull Context ctx) {
        Log.i(TAG, "[listAppDrawables] in");
        List<DrawableItem> out = new ArrayList<>();
        Field[] fields = com.kurmez.iyesi.R.drawable.class.getDeclaredFields();
        for (Field f : fields) {
            Log.i(TAG, "[for] in");
            try {
                int id = f.getInt(null);
                String name = f.getName();
                if (name.startsWith("ic_launcher_background")) continue;
                out.add(new DrawableItem(name, id));
            } catch (Throwable ignore) {}
            Log.i(TAG, "[catch] in");
        }
        java.util.Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    @Nullable
    private static Bitmap loadBitmapFromDrawable(@NonNull Context ctx, int resId, int targetPx) {
        Log.i(TAG, "[loadBitmapFromDrawable] in");
        try {
            Drawable d = AppCompatResources.getDrawable(ctx, resId);
            if (d == null) return null;
            int w = d.getIntrinsicWidth()  > 0 ? d.getIntrinsicWidth()  : targetPx;
            int h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : targetPx;
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, w, h);
            d.draw(c);
            return bmp;
        } catch (Throwable t) {
            Log.i(TAG, "[catch] in");
            Log.e("QR", "loadBitmapFromDrawable failed id=" + resId, t);
            return null;
        }
    }

    private static void showPreviewDialog(@NonNull Activity a, @NonNull String content, @NonNull Bitmap bmp) {
        Log.i(TAG, "[showPreviewDialog] in");
        ImageView iv = new ImageView(a);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setImageBitmap(bmp);

        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("QR Önizleme")
                .setMessage(content)
                .setView(iv)
                .setPositiveButton("Kapat", (d, w) -> d.dismiss())
                .show();
    }

    // ------------------------------------------------------------
    // (İsteğe bağlı) Minimal diyalog – sayısal alanlarla
    // ------------------------------------------------------------
    public static void showGenerateDialogMinimal(@NonNull Activity a,
                                                 @NonNull QROptions initial,
                                                 int sizePx,
                                                 @NonNull OnGenerated cb) {
        Log.i(TAG, "[method] in");
        final int pad = (int) (a.getResources().getDisplayMetrics().density * 16);

        ScrollView sc = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        final EditText etDot = new EditText(a);
        etDot.setHint("Nokta ölçeği (0.30–0.48) örn: 0.34");
        etDot.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDot.setText(String.valueOf(initial.dotScale));
        root.addView(etDot);

        final EditText etHole = new EditText(a);
        etHole.setHint("Merkez boşluğu (çap oranı 0.18–0.30) örn: 0.22");
        etHole.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etHole.setText(String.valueOf(initial.holeRatio));
        root.addView(etHole);

        final EditText etLink = new EditText(a);
        etLink.setHint("Link (tam URL) – boş bırakırsan otomatik oluşturulur");
        etLink.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        etLink.setText(buildSmartContentFromOptions(initial));
        root.addView(etLink);

        final EditText etFrag = new EditText(a);
        etFrag.setHint("#veri (örn: abc123) — '#' yazma");
        etFrag.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etFrag.setText(initial.fragmentNoHash);
        root.addView(etFrag);

        final Button btnGenerate = new Button(a);
        btnGenerate.setText("Generate");
        root.addView(btnGenerate);

        final androidx.appcompat.app.AlertDialog dlg =
                new androidx.appcompat.app.AlertDialog.Builder(a)
                        .setView(sc)
                        .setCancelable(true)
                        .create();

        btnGenerate.setOnClickListener(v -> {
            QROptions o = new QROptions();
            try { o.dotScale = clamp(Float.parseFloat(etDot.getText().toString()), 0.25f, 0.55f); }
            catch (Exception ignore) { o.dotScale = 0.34f; }
            Log.i(TAG, "[catch] in");
            try { o.holeRatio = clamp(Float.parseFloat(etHole.getText().toString()), 0f, 0.35f); }
            catch (Exception ignore) { o.holeRatio = 0.22f; }
            Log.i(TAG, "[catch] in");

            o.centerLogo    = initial.centerLogo;
            o.transparentBg = initial.transparentBg;
            o.ecLevel       = initial.ecLevel;
            o.quietZone     = initial.quietZone;
            o.sparseModulo  = initial.sparseModulo;
            o.dotShape      = initial.dotShape;

            o.redirectBaseUrl = initial.redirectBaseUrl;
            o.toRoute         = initial.toRoute;
            o.hiddenCode      = initial.hiddenCode;

            String linkText = etLink.getText().toString().trim();
            String fragText = etFrag.getText().toString().trim();

            String content = linkText.isEmpty() ? buildSmartContentFromOptions(o) : linkText;
            if (!fragText.isEmpty()) {
                Log.i(TAG, "[method] in");
                String frag = fragText.startsWith("#") ? fragText.substring(1) : fragText;
                content = content + "#" + Uri.encode(frag);
            }

            try {
                Bitmap qr = generate(content, sizePx, o);
                dlg.dismiss();
                cb.onGenerated(qr, content);
            } catch (WriterException e) {
                Log.i(TAG, "[catch] in");
                Log.e("QR", "Generate failed", e);
            }
        });

        dlg.show();
    }

    // ============================================================
    // 4) QR GÖRSEL ÜRETİMİ (Daire/Kare modül + merkez boşluk + opsiyonel logo)
    // ============================================================
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }


    @NonNull
    public static Bitmap generate(@NonNull String content, int sizePx, @Nullable Bitmap centerLogo)
            throws WriterException {
        QROptions o = new QROptions();
        o.centerLogo = centerLogo;
        return generate(content, sizePx, o);
    }

    @NonNull
    public static Bitmap generate(@NonNull String content, int sizePx, @NonNull QROptions o)
            throws WriterException {

        Hashtable<EncodeHintType, Object> hints = new Hashtable<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, o.quietZone);

        QRCode code = Encoder.encode(content, o.ecLevel, hints);
        ByteMatrix m = code.getMatrix();
        final int modules = m.getWidth();
        final int modulesTotal = modules + o.quietZone * 2;

        final float moduleSize = (float) sizePx / modulesTotal;
        final float start = (sizePx - modulesTotal * moduleSize) / 2f;

        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(o.transparentBg ? Color.TRANSPARENT : Color.WHITE);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);

        float dotScale  = clamp(o.dotScale, 0.25f, 0.55f);
        float holeRatio = clamp(o.holeRatio, 0f, 0.35f);
        if (holeRatio >= 0.28f && dotScale < 0.36f) dotScale = 0.36f;

        final float dotHalf = moduleSize * dotScale; // circle: radius, square: half-side
        final float holeR   = (sizePx * holeRatio) / 2f;
        final float cx = sizePx / 2f, cy = sizePx / 2f;

        for (int my = 0; my < modules; my++) {
            Log.i(TAG, "[for] in");
            for (int mx = 0; mx < modules; mx++) {
                Log.i(TAG, "[for] in");
                if (m.get(mx, my) != 1) continue;

                if (o.sparseModulo > 0 && !isFunctional(mx, my, modules)
                        && ((mx + my) % o.sparseModulo != 0)) {
                    Log.i(TAG, "[method] in");
                    continue;
                }

                float centerX = start + (o.quietZone + mx + 0.5f) * moduleSize;
                float centerY = start + (o.quietZone + my + 0.5f) * moduleSize;

                float dx = centerX - cx, dy = centerY - cy;
                if (holeR > 0f && ((dx*dx + dy*dy) <= (holeR + dotHalf) * (holeR + dotHalf))) {
                    Log.i(TAG, "[method] in");
                    continue;
                }

                if (o.dotShape == QROptions.DotShape.SQUARE) {
                    Log.i(TAG, "[if] in");
                    canvas.drawRect(centerX - dotHalf, centerY - dotHalf,
                            centerX + dotHalf, centerY + dotHalf, paint);
                } else {
                    canvas.drawCircle(centerX, centerY, dotHalf, paint);
                }
            }
        }

        if (o.centerLogo != null && holeRatio > 0f) {
            Log.i(TAG, "[if] in");
            int logoSize = Math.round(sizePx * holeRatio * clamp(o.logoScaleInHole, 0.6f, 0.95f));
            Bitmap scaled = Bitmap.createScaledBitmap(o.centerLogo, logoSize, logoSize, true);
            float left = (sizePx - logoSize) / 2f;
            float top  = (sizePx - logoSize) / 2f;
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(scaled, left, top, p);
        }

        return bmp;
    }

    private static boolean isFunctional(int x, int y, int w) {
        Log.i(TAG, "[isFunctional] in");
        if ((x <= 8 && y <= 8) || (x >= w - 9 && y <= 8) || (x <= 8 && y >= w - 9)) return true;
        if (x == 6 || y == 6) return true;
        return false;
    }

    @NonNull
    public static Bitmap generateFromOptions(int sizePx, @NonNull QROptions o) throws WriterException {
        String content = buildSmartContentFromOptions(o);
        return generate(content, sizePx, o);
    }

    // ============================================================
    // 5) PAYLOAD OLUŞTURMA + FUNCTIONS
    // ============================================================
    @NonNull
    public static String composeSmartPayload(@NonNull String redirectBaseUrl,
                                             @NonNull String toRoute,
                                             @Nullable String hiddenCodeB64OrPlain) {
        Log.i(TAG, "[method] in");
        String toEnc = urlEnc(toRoute);
        String hc = hiddenCodeB64OrPlain;
        if (!TextUtils.isEmpty(hc) && !looksBase64(hc)) {
            Log.i(TAG, "[method] in");
            hc = Base64.encodeToString(hc.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(redirectBaseUrl).append("?to=").append(toEnc);
        if (!TextUtils.isEmpty(hc)) sb.append("&hc=").append(urlEnc(hc));
        return sb.toString();
    }

    @NonNull
    public static String composeSmartPayload(@NonNull String redirectBaseUrl,
                                             @NonNull String toRoute,
                                             @Nullable String hiddenCodeB64OrPlain,
                                             @Nullable String fragmentNoHash) {
        Log.i(TAG, "[method] in");
        String base = composeSmartPayload(redirectBaseUrl, toRoute, hiddenCodeB64OrPlain);
        if (!TextUtils.isEmpty(fragmentNoHash)) {
            Log.i(TAG, "[method] in");
            String frag = fragmentNoHash.startsWith("#") ? fragmentNoHash.substring(1) : fragmentNoHash;
            base += "#" + Uri.encode(frag);
        }
        return base;
    }

    @NonNull
    public static String buildSmartContentFromOptions(@NonNull QROptions o) {
        Log.i(TAG, "[buildSmartContentFromOptions] in");
        String route = o.toRoute;
        if (!TextUtils.isEmpty(route) && !route.startsWith("/")) route = "/" + route;
        return composeSmartPayload(
                o.redirectBaseUrl,
                TextUtils.isEmpty(route) ? "/" : route,
                TextUtils.isEmpty(o.hiddenCode) ? null : o.hiddenCode,
                TextUtils.isEmpty(o.fragmentNoHash) ? null : o.fragmentNoHash
        );
    }

    public static Task<HttpsCallableResult> callFunction(@NonNull String name,
                                                         @NonNull Map<String, Object> data) {
        Log.i(TAG, "[method] in");
        return FirebaseFunctions.getInstance().getHttpsCallable(name).call(data);
    }

    // ============================================================
    // 6) YARDIMCILAR + Sabitler
    // ============================================================
    @NonNull
    public static String androidId(@NonNull Context ctx) {
        Log.i(TAG, "[androidId] in");
        return Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @NonNull
    private static Uri slugToRoute(@NonNull String slug) {
        Log.i(TAG, "[slugToRoute] in");
        if (slug.startsWith("SOUL-")) return Uri.parse("iyesi://souls/" + slug.substring(5));
        Log.i(TAG, "[Uri.parse] if (slug.startsWith(\"SOUL-\")) return Uri.parse(\"iyesi://souls/\" + slug.substring(5));");
        if (slug.startsWith("MSG-"))  return Uri.parse("iyesi://message/" + slug.substring(4));
        Log.i(TAG, "[Uri.parse] if (slug.startsWith(\"MSG-\"))  return Uri.parse(\"iyesi://message/\" + slug.substring(4));");
        if (slug.startsWith("SOS-"))  return Uri.parse("iyesi://sokak?case=" + slug.substring(4));
        Log.i(TAG, "[Uri.parse] if (slug.startsWith(\"SOS-\"))  return Uri.parse(\"iyesi://sokak?case=\" + slug.substring(4));");
        if (slug.startsWith("VET-"))  return Uri.parse("iyesi://sahiplendirme/" + slug.substring(4));
        Log.i(TAG, "[Uri.parse] if (slug.startsWith(\"VET-\"))  return Uri.parse(\"iyesi://sahiplendirme/\" + slug.substring(4));");
        if (slug.startsWith("QRAD-")) return Uri.parse("iyesi://qr/admin/" + slug.substring(5));
        Log.i(TAG, "[Uri.parse] if (slug.startsWith(\"QRAD-\")) return Uri.parse(\"iyesi://qr/admin/\" + slug.substring(5));");
        Log.i(TAG, "[Uri.parse] return Uri.parse(\"iyesi://explore/\" + sl  ug);");
        return Uri.parse("iyesi://explore/" + slug);
    }

    private static String trimLeadingSlash(String s) {
        Log.i(TAG, "[trimLeadingSlash] in");
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }

    private static String urlEnc(String s) {
        Log.i(TAG, "[urlEnc] in");
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static boolean looksBase64(@NonNull String s) {
        Log.i(TAG, "[looksBase64] in");
        return s.matches("^[A-Za-z0-9+/=]+$");
    }

    // SeekBar yardımcıları
    private static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
    private static float map(int progress, int minP, int maxP, float outMin, float outMax) {
        Log.i(TAG, "[map] in");
        float t = (progress - minP) / (float) (maxP - minP);
        return outMin + t * (outMax - outMin);
    }

    // Scan result extras
    public static final String EXTRA_RAW = "qr_extra_raw";
    public static final String EXTRA_FORMAT = "qr_extra_format";
    public static final int REQ_SCAN = 0xBEEF;

    public static Intent newScanIntent(Context ctx) {
        Log.i(TAG, "[newScanIntent] in");
        return new Intent(ctx, QRScannerActivity.class);
    }

    public static void deliverResult(Activity a, String raw, @Nullable String format) {
        Log.i(TAG, "[deliverResult] in");
        Intent r = new Intent();
        r.putExtra(EXTRA_RAW, raw);
        Log.i(TAG, "[putExtra] r.putExtra(EXTRA_RAW, raw);");
        if (format != null) r.putExtra(EXTRA_FORMAT, format);
        Log.i(TAG, "[putExtra] if (format != null) r.putExtra(EXTRA_FORMAT, format);");
        a.setResult(Activity.RESULT_OK, r);
        Log.i(TAG, "[setResult] a.setResult(Activity.RESULT_OK, r);");
        a.finish();
        Log.i(TAG, "[finish] called");
    }

    // ============================================================
    // 7) QROptions
    // ============================================================
    public static final class QROptions {
        public enum DotShape { CIRCLE, SQUARE }

        public float dotScale = 0.34f;         // 0.30–0.48 güvenli
        public float holeRatio = 0.22f;        // 0–0.35 (0 = kapalı)
        public float logoScaleInHole = 0.80f;  // 0–1
        public int quietZone = 1;
        public int sparseModulo = 0;           // 0, 3, 2
        public boolean transparentBg = false;
        public ErrorCorrectionLevel ecLevel = ErrorCorrectionLevel.H;
        public QROptions.DotShape dotShape = QROptions.DotShape.CIRCLE;
        @Nullable public Bitmap centerLogo = null;

        public String redirectBaseUrl = "https://iyesi-host-pnfxz2soua-uc.a.run.app";
        public String toRoute        = "/souls/123";
        public String hiddenCode     = "";
        public String fragmentNoHash = "";
        // QR görsel ayarları

        // Payload ayarları - YENİ YAPILANDIRMA
        public String baseUrl = "https://iyesi-host-pnfxz2soua-uc.a.run.app";
        public int payloadMode = 0; // 0: iyesi.app/d/, 1: redirect?to=, 2: redirect?slug=
        public String routeOrSlug = "welcome";

        // Uygulama açma seçenekleri - YENİ
        public boolean openApp = false;
        public String openType = "intent"; // "intent" veya "scheme"
        public String packageName = "com.kurmez.iyesi";

        // Eski alanları yeni sisteme uyarla
        public String getRedirectBaseUrl() { return baseUrl; }
        public String getToRoute() { return routeOrSlug; }
    }
}

/*
KULLANIM:

QR.QROptions opts = new QR.QROptions();
QR.showGenerateDialog(this, opts, 1024, (bmp, content) -> {
    imageView.setImageBitmap(bmp);
    textView.setText(content);
});

İpuçları:
- Logo kullanıyorsan: quietZone ≥ 3, ecLevel=H, holeRatio ≈ 0.20–0.24 iyi sonuç verir.
- holeRatio büyüdükçe dotScale’i çok düşürme (kod içi heuristik var).
*/