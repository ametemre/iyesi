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
 * - generate (daire/kare modüllü QR bitmap, opsiyonel logo ve merkez boşluğu)
 * - composeSmartPayload (public redirect + uygulama-içi route + opsiyonel gizli kod + #fragment)
 * - callFunction (Cloud Functions wrapper)
 *
 * Not: Aktivite eşlemelerini uygulamaya kilitlememek için opsiyonel bir "resolver" var.
 * QR.installResolver(...) ile route → Intent dönüşünü özelleştirebilirsin.
 */
public final class QR {

    private QR() {}

    // ============================================================
    // 1) TARAMA (ZXing + özel scanner)
    // ============================================================
    public static void startScan(@NonNull Activity activity) {
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
        // 1) ZXing IntentIntegrator yolu
        IntentResult z = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (z != null && z.getContents() != null) return z.getContents().trim();

        // 2) Özel QRScannerActivity yolu
        if (requestCode == REQ_SCAN && resultCode == Activity.RESULT_OK && data != null) {
            return data.getStringExtra(EXTRA_RAW);
        }
        return null;
    }

    // ============================================================
    // 2) ROUTE NORMALİZASYONU + INTENT ÜRETİMİ
    // ============================================================
    public interface TargetResolver {
        /** iyesi://... biçimindeki route’a karşılık gidecek Intent'i döndür. (Dönmezsen null) */
        @Nullable Intent resolve(@NonNull Context ctx, @NonNull Uri normalizedRoute);
    }

    private static volatile TargetResolver sResolver;

    /** Uygulama açılışında (ör. Application.onCreate) çağırıp özel resolver’ını kur. */
    public static void installResolver(@Nullable TargetResolver resolver) {
        sResolver = resolver;
    }

    /** Dışarıdan gelen "raw" metni normalize edilmiş bir route’a çevirir. */
    @NonNull
    public static Uri normalizeRoute(@NonNull String raw) {
        try {
            // JSON gövde: {"route":"/souls/123"}
            String t = raw.trim();
            if (t.startsWith("{") && t.endsWith("}")) {
                int idx = t.indexOf("\"route\"");
                if (idx >= 0) {
                    // Basit ayıklama; production'da JSON parser kullan
                    int q1 = t.indexOf('"', idx + 7);
                    int q2 = t.indexOf('"', q1 + 1);
                    if (q1 > 0 && q2 > q1) {
                        String route = t.substring(q1 + 1, q2);
                        return Uri.parse("iyesi://" + trimLeadingSlash(route));
                    }
                }
            }

            // Parselanabilir URI ise:
            Uri uri = Uri.parse(raw);

            // Cloud Functions redirect: .../redirect?to=/souls/123&hc=BASE64
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && uri.getHost().contains("us-central1-")) {
                String to = uri.getQueryParameter("to");
                if (!TextUtils.isEmpty(to)) {
                    return Uri.parse("iyesi://" + trimLeadingSlash(to));
                }
                String slug = uri.getQueryParameter("slug");
                if (!TextUtils.isEmpty(slug)) {
                    return slugToRoute(slug);
                }
            }

            // iyesi.app/d/... → iyesi://...
            if ("https".equalsIgnoreCase(uri.getScheme())
                    && "iyesi.app".equalsIgnoreCase(uri.getHost())
                    && uri.getPath() != null
                    && uri.getPath().startsWith("/d/")) {
                String route = uri.getPath().substring(3); // "/d/" sonrası
                Uri n = Uri.parse("iyesi://" + trimLeadingSlash(route));
                if (uri.getQuery() != null) {
                    n = n.buildUpon().encodedQuery(uri.getEncodedQuery()).build();
                }
                return n;
            }

            // Zaten iyesi:// ise
            if ("iyesi".equalsIgnoreCase(uri.getScheme())) {
                return uri;
            }

            // Düz SLUG (SOUL-123 vb.)
            if (raw.matches("[A-Za-z0-9_-]+")) {
                return slugToRoute(raw);
            }

            // En sonda dokunma: https vb. kalırsa aynen döndür (web'e açarız).
            return uri;

        } catch (Throwable e) {
            Log.e("QR", "normalizeRoute error: " + e);
            return Uri.parse(raw);
        }
    }

    /** raw → Intent. Kurulu resolver varsa önce onu kullanır, yoksa varsayılan davranır. */
    @Nullable
    public static Intent buildIntentFromQr(@NonNull Context ctx, @NonNull String raw) {
        Uri route = normalizeRoute(raw);

        // Özel çözücü varsa bırak o karar versin
        TargetResolver r = sResolver;
        if (r != null && "iyesi".equalsIgnoreCase(route.getScheme())) {
            Intent custom = r.resolve(ctx, route);
            if (custom != null) return custom;
        }

        // Varsayılanlar:
        if ("https".equalsIgnoreCase(route.getScheme())
                || "http".equalsIgnoreCase(route.getScheme())
                || "market".equalsIgnoreCase(route.getScheme())) {
            return new Intent(Intent.ACTION_VIEW, route);
        }

        // iyesi:// fakat resolver kurulu değil → ana sayfaya route string’i ver
        if ("iyesi".equalsIgnoreCase(route.getScheme())) {
            Intent i = new Intent(ctx, com.kurmez.iyesi.MainActivity.class);
            i.putExtra("route", route.toString());
            return i;
        }

        // Anlaşılamadıysa web’e ver (kötü koşul)
        return new Intent(Intent.ACTION_VIEW, route);
    }

    /** raw → doğrudan startActivity */
    public static boolean route(@NonNull Context ctx, @NonNull String raw) {
        Intent i = buildIntentFromQr(ctx, raw);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(i);
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

    /**
     * "Generate QR" akışı için pop-up:
     *  - RadioGroup: Daire / Kare
     *  - SeekBar: Nokta büyüklüğü (0.30–0.48)
     *  - SeekBar: Yoğunluk/Seyreltme (0=kapalı, 1=3'te1, 2=yarı)
     *  - EditText: HTTP Link (tam URL)
     *  - EditText: #veri (fragment, '#' yazmadan)
     * Generate sonrası bu dialog kapanır, QR ikinci pop-up'ta gösterilir ve callback tetiklenir.
     */
    public static void showGenerateDialog(@NonNull Activity a,
                                          @NonNull QROptions defaults,
                                          int sizePx,
                                          @Nullable OnGenerated cb) {

        final int pad = (int) (a.getResources().getDisplayMetrics().density * 16);

        ScrollView sc = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        // --- Şekil seçimi ---
        TextView tvShape = new TextView(a);
        tvShape.setText("Nokta Şekli");
        root.addView(tvShape);

        RadioGroup rgShape = new RadioGroup(a);
        RadioButton rbCircle = new RadioButton(a);
        rbCircle.setText("Daire");
        RadioButton rbSquare = new RadioButton(a);
        rbSquare.setText("Kare");
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

        sbDot.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
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
        tvSpVal.setText(startSparse==0 ? "Tam (mod 0)" : startSparse==1 ? "Seyrek: 3'te 1 (mod 3)" : "Seyrek: 2'de 1 (mod 2)");
        root.addView(tvSpVal);

        sbSp.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                tvSpVal.setText(p==0 ? "Tam (mod 0)" : p==1 ? "Seyrek: 3'te 1 (mod 3)" : "Seyrek: 2'de 1 (mod 2)");
            }
        });
// --- Merkez boşluğu (logo) ---
        TextView tvHole = new TextView(a);
        tvHole.setText("Merkez Boşluğu (Logo)");
        root.addView(tvHole);

// Aç/Kapat
        final android.widget.CheckBox cbHole = new android.widget.CheckBox(a);
        cbHole.setText("Merkezde dairesel boşluk kullan");
        cbHole.setChecked(defaults.holeRatio > 0f);
        root.addView(cbHole);

// Slider (0.00 – 0.35 arası; öneri 0.18–0.30)
        final TextView tvHoleVal = new TextView(a);
        tvHoleVal.setText(String.format(java.util.Locale.US,
                "Çap oranı: %.02f", clamp(defaults.holeRatio, 0f, 0.35f)));
        root.addView(tvHoleVal);

        final SeekBar sbHole = new SeekBar(a);
        sbHole.setMax(100);
        int startHole = (int) (clamp(defaults.holeRatio, 0f, 0.35f) / 0.35f * 100f);
        sbHole.setProgress(startHole);
        root.addView(sbHole);

// Enable/disable
        sbHole.setEnabled(cbHole.isChecked());
        tvHoleVal.setEnabled(cbHole.isChecked());
        cbHole.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sbHole.setEnabled(isChecked);
            tvHoleVal.setEnabled(isChecked);
        });

        sbHole.setOnSeekBarChangeListener(new SimpleSeek() {
            @Override public void onProgressChanged(SeekBar seekBar, int p, boolean fromUser) {
                float val = map(p, 0, 100, 0f, 0.35f);
                tvHoleVal.setText(String.format(java.util.Locale.US, "Çap oranı: %.02f", val));
            }
        });

        // --- HTTP Link ---
        EditText etLink = new EditText(a);
        etLink.setHint("HTTP Link (tam URL)");
        etLink.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        // Varsayılanı, smart payload ile oluştur:
        etLink.setText(buildSmartContentFromOptions(defaults));
        root.addView(etLink);

        // --- #veri (fragment) ---
        EditText etFrag = new EditText(a);
        etFrag.setHint("#veri (örn: abc123) — '#' yazma");
        etFrag.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etFrag.setText(defaults.fragmentNoHash);
        root.addView(etFrag);

        // --- Generate düğmesi ---
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

            // Şekil
            o.dotShape = (rgShape.getCheckedRadioButtonId() == rbSquare.getId())
                    ? QROptions.DotShape.SQUARE
                    : QROptions.DotShape.CIRCLE;

            // Nokta büyüklüğü
            o.dotScale = map(sbDot.getProgress(), 0, 100, 0.30f, 0.48f);

            // Yoğunluk
            int sparsePos = sbSp.getProgress();
            o.sparseModulo = (sparsePos==0) ? 0 : (sparsePos==1) ? 3 : 2;

            // >>> Merkez boşluğu (slider’dan)
            if (cbHole.isChecked()) {
                o.holeRatio = map(sbHole.getProgress(), 0, 100, 0f, 0.35f);
            } else {
                o.holeRatio = 0f;
            }

            // Diğer parametreleri devral
            o.logoScaleInHole = defaults.logoScaleInHole;
            o.quietZone       = defaults.quietZone;
            o.transparentBg   = defaults.transparentBg;
            o.ecLevel         = defaults.ecLevel;
            o.centerLogo      = defaults.centerLogo;

            // İçerik
            o.redirectBaseUrl = defaults.redirectBaseUrl;
            o.toRoute         = defaults.toRoute;
            o.hiddenCode      = defaults.hiddenCode;

            String link = etLink.getText().toString().trim();
            String frag = etFrag.getText().toString().trim();

            String content = link.isEmpty() ? buildSmartContentFromOptions(o) : link;
            if (!frag.isEmpty()) {
                String fragClean = frag.startsWith("#") ? frag.substring(1) : frag;
                content = content + "#" + Uri.encode(fragClean);
            }

            try {
                Bitmap qr = generate(content, sizePx, o);
                dlg.dismiss();
                showPreviewDialog(a, content, qr);
                if (cb != null) cb.onGenerated(qr, content);
            } catch (WriterException e) {
                Log.e("QR", "Generate failed", e);
                Toast.makeText(a, "QR üretilemedi: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        dlg.show();
    }
    // Uygulama drawable'larını (R.drawable.*) listelemek için basit model
    private static final class DrawableItem {
        final String name; final int resId;
        DrawableItem(String n, int id) { name = n; resId = id; }
    }

    /** Uygulamadaki tüm R.drawable ögelerini ad+id olarak döndürür. */
    @NonNull
    private static List<DrawableItem> listAppDrawables(@NonNull Context ctx) {
        List<DrawableItem> out = new ArrayList<>();
        Field[] fields = com.kurmez.iyesi.R.drawable.class.getDeclaredFields();
        for (Field f : fields) {
            try {
                int id = f.getInt(null);
                String name = f.getName();
                // İstersen gürültü azalt: launcher arka planları vb. elemeler
                if (name.startsWith("ic_launcher_background")) continue;
                out.add(new DrawableItem(name, id));
            } catch (Throwable ignore) { /* no-op */ }
        }
        // Basitçe isme göre sırala
        java.util.Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Her türlü Drawable'ı (vektör dahil) Bitmap'e çevirir. */
    @Nullable
    private static Bitmap loadBitmapFromDrawable(@NonNull Context ctx, int resId, int targetPx) {
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
            Log.e("QR", "loadBitmapFromDrawable failed id=" + resId, t);
            return null;
        }
    }

    private static void showPreviewDialog(@NonNull Activity a, @NonNull String content, @NonNull Bitmap bmp) {
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
        final int pad = (int) (a.getResources().getDisplayMetrics().density * 16);

        ScrollView sc = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        // --- Nokta ölçeği ---
        final EditText etDot = new EditText(a);
        etDot.setHint("Nokta ölçeği (0.30–0.48) örn: 0.34");
        etDot.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDot.setText(String.valueOf(initial.dotScale));
        root.addView(etDot);

        // --- Merkez boşluğu (ÇAP oranı) ---
        final EditText etHole = new EditText(a);
        etHole.setHint("Merkez boşluğu (çap oranı 0.18–0.30) örn: 0.22");
        etHole.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etHole.setText(String.valueOf(initial.holeRatio));
        root.addView(etHole);

        // --- Link (tam URL) ---
        final EditText etLink = new EditText(a);
        etLink.setHint("Link (tam URL) – boş bırakırsan otomatik oluşturulur");
        etLink.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        etLink.setText(buildSmartContentFromOptions(initial));
        root.addView(etLink);

        // --- #veri (fragment) ---
        final EditText etFrag = new EditText(a);
        etFrag.setHint("#veri (örn: abc123) — '#' yazma");
        etFrag.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etFrag.setText(initial.fragmentNoHash);
        root.addView(etFrag);

        // --- Generate düğmesi ---
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
            try { o.holeRatio = clamp(Float.parseFloat(etHole.getText().toString()), 0f, 0.35f); }
            catch (Exception ignore) { o.holeRatio = 0.22f; }

            o.centerLogo    = initial.centerLogo;
            o.transparentBg = initial.transparentBg;
            o.ecLevel       = initial.ecLevel;
            o.quietZone     = initial.quietZone;
            o.sparseModulo  = initial.sparseModulo;
            o.dotShape      = initial.dotShape;

            // İçerik
            o.redirectBaseUrl = initial.redirectBaseUrl;
            o.toRoute         = initial.toRoute;
            o.hiddenCode      = initial.hiddenCode;

            String linkText = etLink.getText().toString().trim();
            String fragText = etFrag.getText().toString().trim();

            String content = linkText.isEmpty() ? buildSmartContentFromOptions(o) : linkText;
            if (!fragText.isEmpty()) {
                String frag = fragText.startsWith("#") ? fragText.substring(1) : fragText;
                content = content + "#" + Uri.encode(frag);
            }

            try {
                Bitmap qr = generate(content, sizePx, o);
                dlg.dismiss();
                cb.onGenerated(qr, content);
            } catch (WriterException e) {
                Log.e("QR", "Generate failed", e);
            }
        });

        dlg.show();
    }

    // ============================================================
    // 4) QR GÖRSEL ÜRETİMİ (Daire/Kare modül + merkez boşluk + opsiyonel logo)
    // ============================================================
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Basit overload: yalnızca logo geçerek üret. */
    @NonNull
    public static Bitmap generate(@NonNull String content, int sizePx, @Nullable Bitmap centerLogo)
            throws WriterException {
        QROptions o = new QROptions();
        o.centerLogo = centerLogo;
        return generate(content, sizePx, o);
    }

    /** Parametrik üretim: daire/kare modüller + merkez dairesel boşluk (holeRatio>0 ise). */
    @NonNull
    public static Bitmap generate(@NonNull String content, int sizePx, @NonNull QROptions o)
            throws WriterException {

        // ZXing matrisi
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
        if (holeRatio >= 0.28f && dotScale < 0.36f) dotScale = 0.36f; // heuristik

        final float dotHalf = moduleSize * dotScale; // circle: radius, square: half-side
        final float holeR   = (sizePx * holeRatio) / 2f;
        final float cx = sizePx / 2f, cy = sizePx / 2f;

        // Modül modül çiz
        for (int my = 0; my < modules; my++) {
            for (int mx = 0; mx < modules; mx++) {
                if (m.get(mx, my) != 1) continue;

                // Seyreltme – işlevsel kalıpları koru
                if (o.sparseModulo > 0 && !isFunctional(mx, my, modules)
                        && ((mx + my) % o.sparseModulo != 0)) {
                    continue;
                }

                float centerX = start + (o.quietZone + mx + 0.5f) * moduleSize;
                float centerY = start + (o.quietZone + my + 0.5f) * moduleSize;

                float dx = centerX - cx, dy = centerY - cy;
                if (holeR > 0f && ((dx*dx + dy*dy) <= (holeR + dotHalf) * (holeR + dotHalf))) {
                    continue; // merkez boşluk
                }

                if (o.dotShape == QROptions.DotShape.SQUARE) {
                    canvas.drawRect(centerX - dotHalf, centerY - dotHalf,
                            centerX + dotHalf, centerY + dotHalf, paint);
                } else {
                    canvas.drawCircle(centerX, centerY, dotHalf, paint);
                }
            }
        }

        // Opsiyonel logo
        if (o.centerLogo != null && holeRatio > 0f) {
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
        // 3 köşedeki finder + separator alanı (≈9x9 güvenli tampon)
        if ((x <= 8 && y <= 8) || (x >= w - 9 && y <= 8) || (x <= 8 && y >= w - 9)) return true;
        // timing pattern (satır ve sütun)
        if (x == 6 || y == 6) return true;
        return false;
    }

    /** QR üretimini doğrudan QROptions içinden içerik oluşturarak yapan yardımcı. */
    @NonNull
    public static Bitmap generateFromOptions(int sizePx, @NonNull QROptions o) throws WriterException {
        String content = buildSmartContentFromOptions(o);
        return generate(content, sizePx, o);
    }

    // ============================================================
    // 3.b) QROptions + (ikincil) Dialog
    // ============================================================
    public interface OnOptionsConfirmed { void onConfirmed(@NonNull QROptions opts); }

    public static void showOptionsDialog(@NonNull Activity a,
                                         @NonNull QROptions initial,
                                         @NonNull OnOptionsConfirmed cb) {

        final int pad = (int) (a.getResources().getDisplayMetrics().density * 16);

        ScrollView sc = new ScrollView(a);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        // --- Nokta ölçeği ---
        EditText etDot = new EditText(a);
        etDot.setHint("Nokta ölçeği (0.30–0.48) örn: 0.34");
        etDot.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etDot.setText(String.valueOf(initial.dotScale));
        root.addView(etDot);

        // --- Logo boşluğu (ÇAP oranı) ---
        EditText etHole = new EditText(a);
        etHole.setHint("Logo boşluğu ÇAP oranı (0.18–0.30) örn: 0.22");
        etHole.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etHole.setText(String.valueOf(initial.holeRatio));
        root.addView(etHole);

        // --- Redirect base URL ---
        EditText etUrl = new EditText(a);
        etUrl.setHint("Redirect Base URL (örn: https://us-central1-.../redirect)");
        etUrl.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        etUrl.setText(initial.redirectBaseUrl);
        root.addView(etUrl);

        // --- toRoute ---
        EditText etTo = new EditText(a);
        etTo.setHint("Uygulama içi rota (toRoute) örn: /souls/123");
        etTo.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etTo.setText(initial.toRoute);
        root.addView(etTo);

        // --- hiddenCode (opsiyonel) ---
        EditText etHC = new EditText(a);
        etHC.setHint("Gizli Kod (opsiyonel) — plain veya Base64");
        etHC.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etHC.setText(initial.hiddenCode);
        root.addView(etHC);

        // --- #fragment ---
        EditText etFrag = new EditText(a);
        etFrag.setHint("#fragment (örn: veri123) — '#' KULLANMA");
        etFrag.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        etFrag.setText(initial.fragmentNoHash);
        root.addView(etFrag);

        // --- Şeffaf arka plan seçeneği (opsiyonel) ---
        android.widget.CheckBox cbTransparent = new android.widget.CheckBox(a);
        cbTransparent.setText("Şeffaf arka plan (PNG alfa)");
        cbTransparent.setChecked(initial.transparentBg);
        root.addView(cbTransparent);

        // --- Sparse modulo (ileri seviye, 0 kapalı) ---
        EditText etSparse = new EditText(a);
        etSparse.setHint("Seyreltme (0=kapalı, 3=3'te 1, 2=yarı)");
        etSparse.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etSparse.setText(String.valueOf(initial.sparseModulo));
        root.addView(etSparse);

        new androidx.appcompat.app.AlertDialog.Builder(a)
                .setTitle("QR Ayarları")
                .setView(sc)
                .setNegativeButton("İptal", null)
                .setNeutralButton("Varsayılanlar", (d, w) -> {
                    etDot.setText("0.34");
                    etHole.setText("0.22");
                    etUrl.setText("https://us-central1-iyesi-e8d4f.cloudfunctions.net/redirect");
                    etTo.setText("/souls/123");
                    etHC.setText("");
                    etFrag.setText("");
                    cbTransparent.setChecked(false);
                    etSparse.setText("0");
                })
                .setPositiveButton("Tamam", (d, w) -> {
                    QROptions o = new QROptions();
                    try { o.dotScale = clamp(Float.parseFloat(etDot.getText().toString()), 0.25f, 0.55f); }
                    catch (Exception ignore) { o.dotScale = 0.34f; }

                    try { o.holeRatio = clamp(Float.parseFloat(etHole.getText().toString()), 0f, 0.35f); }
                    catch (Exception ignore) { o.holeRatio = 0.22f; }

                    o.redirectBaseUrl = etUrl.getText().toString().trim();
                    o.toRoute         = etTo.getText().toString().trim();
                    o.hiddenCode      = etHC.getText().toString().trim();
                    o.fragmentNoHash  = etFrag.getText().toString().trim();
                    o.transparentBg   = cbTransparent.isChecked();

                    try { o.sparseModulo = Integer.parseInt(etSparse.getText().toString().trim()); }
                    catch (Exception ignore) { o.sparseModulo = 0; }

                    cb.onConfirmed(o);
                })
                .show();
    }

    // ============================================================
    // 5) PAYLOAD OLUŞTURMA + FUNCTIONS
    // ============================================================
    /**
     * Örn:
     * composeSmartPayload("https://.../redirect", "/souls/123", "AB7QK9D")
     * → https://.../redirect?to=%2Fsouls%2F123&hc=QUI3UUs5RA==
     */
    @NonNull
    public static String composeSmartPayload(@NonNull String redirectBaseUrl,
                                             @NonNull String toRoute,
                                             @Nullable String hiddenCodeB64OrPlain) {
        String toEnc = urlEnc(toRoute);
        String hc = hiddenCodeB64OrPlain;
        if (!TextUtils.isEmpty(hc) && !looksBase64(hc)) {
            hc = Base64.encodeToString(hc.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(redirectBaseUrl).append("?to=").append(toEnc);
        if (!TextUtils.isEmpty(hc)) sb.append("&hc=").append(urlEnc(hc));
        return sb.toString();
    }

    /** Link sonuna #fragment ekleyen overload. */
    @NonNull
    public static String composeSmartPayload(@NonNull String redirectBaseUrl,
                                             @NonNull String toRoute,
                                             @Nullable String hiddenCodeB64OrPlain,
                                             @Nullable String fragmentNoHash) {
        String base = composeSmartPayload(redirectBaseUrl, toRoute, hiddenCodeB64OrPlain);
        if (!TextUtils.isEmpty(fragmentNoHash)) {
            String frag = fragmentNoHash.startsWith("#") ? fragmentNoHash.substring(1) : fragmentNoHash;
            base += "#" + Uri.encode(frag);
        }
        return base;
    }

    /** QROptions içinden smart content üretir. */
    @NonNull
    public static String buildSmartContentFromOptions(@NonNull QROptions o) {
        String route = o.toRoute;
        if (!TextUtils.isEmpty(route) && !route.startsWith("/")) {
            route = "/" + route; // güvence
        }
        return composeSmartPayload(
                o.redirectBaseUrl,
                TextUtils.isEmpty(route) ? "/" : route,
                TextUtils.isEmpty(o.hiddenCode) ? null : o.hiddenCode,
                TextUtils.isEmpty(o.fragmentNoHash) ? null : o.fragmentNoHash
        );
    }

    /** Basit Functions wrapper (ör. "verifyQr", "redeemCode" vb.) */
    public static Task<HttpsCallableResult> callFunction(@NonNull String name,
                                                         @NonNull Map<String, Object> data) {
        return FirebaseFunctions.getInstance().getHttpsCallable(name).call(data);
    }

    // ============================================================
    // 6) YARDIMCILAR + Sabitler
    // ============================================================
    @NonNull
    public static String androidId(@NonNull Context ctx) {
        return Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    @NonNull
    private static Uri slugToRoute(@NonNull String slug) {
        // Basit eşleme: SOUL-123 → /souls/123, MSG-45 → /message/45, VET-9 → /sahiplendirme/9 ...
        if (slug.startsWith("SOUL-")) return Uri.parse("iyesi://souls/" + slug.substring(5));
        if (slug.startsWith("MSG-"))  return Uri.parse("iyesi://message/" + slug.substring(4));
        if (slug.startsWith("SOS-"))  return Uri.parse("iyesi://sokak?case=" + slug.substring(4));
        if (slug.startsWith("VET-"))  return Uri.parse("iyesi://sahiplendirme/" + slug.substring(4));
        if (slug.startsWith("QRAD-")) return Uri.parse("iyesi://qr/admin/" + slug.substring(5));
        return Uri.parse("iyesi://explore/" + slug);
    }

    private static String trimLeadingSlash(String s) {
        return (s != null && s.startsWith("/")) ? s.substring(1) : s;
    }

    private static String urlEnc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static boolean looksBase64(@NonNull String s) {
        return s.matches("^[A-Za-z0-9+/=]+$");
    }

    // === SeekBar yardımcıları ===
    private static abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
    private static float map(int progress, int minP, int maxP, float outMin, float outMax) {
        float t = (progress - minP) / (float) (maxP - minP);
        return outMin + t * (outMax - outMin);
    }

    // === Scan result extras ===
    public static final String EXTRA_RAW = "qr_extra_raw";
    public static final String EXTRA_FORMAT = "qr_extra_format";
    public static final int REQ_SCAN = 0xBEEF; // tek referans

    public static Intent newScanIntent(Context ctx) {
        return new Intent(ctx, QRScannerActivity.class);
    }

    public static void deliverResult(Activity a, String raw, @Nullable String format) {
        Intent r = new Intent();
        r.putExtra(EXTRA_RAW, raw);
        if (format != null) r.putExtra(EXTRA_FORMAT, format);
        a.setResult(Activity.RESULT_OK, r);
        a.finish();
    }

    // ============================================================
    // 7) QROptions (PopUp ile ayarlanır)
    // ============================================================
    public static final class QROptions {
        public enum DotShape { CIRCLE, SQUARE }

        /** modül başına yarı-ölçek (dairede yarıçap, karede yarı-kenar): 0.30–0.48 güvenli */
        public float dotScale = 0.34f;

        /** merkez boşluğunun ÇAP oranı (QR kısa kenarına göre): 0.18–0.30 önerilir (0 = kapalı) */
        public float holeRatio = 0.22f;

        /** logo, boşluğun yüzde kaçı kadar çizilsin (0–1) */
        public float logoScaleInHole = 0.80f;

        /** QR’nin kenarındaki quiet zone modül sayısı */
        public int quietZone = 1;

        /** seyreltme: 0=kapalı, 3=3’te 1, 2=yarı */
        public int sparseModulo = 0;

        /** arka plan şeffaf ise PNG’de alfa korunur */
        public boolean transparentBg = false;

        /** hata düzeltme seviyesi (dot + boşluk için H önerilir) */
        public ErrorCorrectionLevel ecLevel = ErrorCorrectionLevel.H;

        /** modül şekli */
        public DotShape dotShape = DotShape.CIRCLE;

        /** opsiyonel: merkez logo (generate çağrısına set edebilirsin) */
        @Nullable public Bitmap centerLogo = null;

        /** Smart payload alanları */
        public String redirectBaseUrl = "https://us-central1-iyesi-e8d4f.cloudfunctions.net/redirect";
        public String toRoute        = "/souls/123";
        public String hiddenCode     = ""; // plain veya Base64
        public String fragmentNoHash = ""; // '#'sız girilecek
    }
}

/*
KULLANIM ÖRNEĞİ (Activity içinde):

QR.QROptions opts = new QR.QROptions();
QR.showGenerateDialog(this, opts, 1024, (bmp, content) -> {
    myImageView.setImageBitmap(bmp);
    myTextView.setText(content);
});

Notlar:
- Yoğunluğu azaltmak için dotScale'i 0.30–0.36 aralığına çek veya sparsity (mod 3) aç.
- holeRatio büyürse (≥0.28) dotScale’i ≥0.36 tut (heuristik uygulanıyor).
- Uzun URL’lerde sparsity’i 0 tutmak genellikle daha güvenli.
- Şeffaf PNG için transparentBg=true.
*/
