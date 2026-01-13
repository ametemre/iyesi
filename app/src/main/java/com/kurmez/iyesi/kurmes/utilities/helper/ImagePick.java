package com.kurmez.iyesi.kurmes.utilities.helper;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.kurmez.iyesi.kurmes.utilities.clients.CFClient;
import com.kurmez.iyesi.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

public final class ImagePick {

    private ImagePick() {}

    // startActivityForResult sabiti
    public static final int REQ_PICK_PROFILE_IMAGE = 9911;

    private static final String TAG = "ImagePick";
    private static final WeakHashMap<Activity, WeakReference<EditText>> pendingTargets = new WeakHashMap<>();

    /** Avatar/URL alanına tıklamada çağır: sistem picker'ı aç. */
    public static void askAndPick(Activity act, EditText target) {
        if (act == null) return;
        pendingTargets.put(act, new WeakReference<>(target));

        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        act.startActivityForResult(it, REQ_PICK_PROFILE_IMAGE);
    }

    /** onDestroy vb. durumlarda temizlik için. */
    public static void clearPendingTarget(Activity act) {
        if (act != null) pendingTargets.remove(act);
    }

    /** Bu Activity için saklanan hedef EditText'i getirir (varsa). */
    @Nullable
    public static EditText findPendingTargetFor(Activity act) {
        WeakReference<EditText> ref = pendingTargets.get(act);
        return ref != null ? ref.get() : null;
    }

    /**
     * onActivityResult'ta çağır: seçilen görseli yükleyip URL'yi EditText'e yazar.
     *
     * @return handled ise true (olay tüketildi), değilse false
     */
    public static boolean handleActivityResultAndUpload(
            Activity act,
            int requestCode,
            int resultCode,
            @Nullable Intent data,
            String endpointUrl,
            String allowedPath,     // "images/iye/avatar" veya "images/soul/avatar" vb.
            int maxDim              // örn. 1600
    ) {
        if (requestCode != REQ_PICK_PROFILE_IMAGE) return false;
        if (resultCode != Activity.RESULT_OK || data == null) return true;

        try {
            Uri uri = data.getData();
            if (uri == null) return true;

            // Kalıcı okuma iznini almaya çalış (belgelerde öneriliyor)
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                act.getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Throwable ignore) { /* bazı cihazlarda tekrar denemede istisna atabilir */ }

            // Güvenli decode → ölçekli Bitmap (ARGB_8888)
            Bitmap bmp = decodeScaledBitmapFromUri(act, uri, maxDim);
            if (bmp == null) {
                EditText t = findPendingTargetFor(act);
                if (t != null) {
                    // ÖNCE: Hardcoded "Görsel açılamadı."
                    // ŞİMDİ: String resource kullanımı
                    t.setError(act.getString(R.string.imagepick_error_image_cannot_open));
                }
                return true;
            }
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
            }

            // PNG data URI
            final String dataUri = bitmapToPngDataUri(bmp);

            // Hedef EditText
            final EditText target = findPendingTargetFor(act);

            // Upload: main thread'i bloklamamak için arka planda
            new Thread(() -> {
                try {
                    String url = CFClient.uploadImageAndGetUrlBlocking(act, endpointUrl, dataUri, allowedPath);
                    act.runOnUiThread(() -> {
                        if (target != null) {
                            target.setText(url);
                            target.setError(null);
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "upload failed", e);
                    act.runOnUiThread(() -> {
                        if (target != null) {
                            // ÖNCE: Hardcoded "Yükleme hatası: " + e.getMessage()
                            // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                            String errorMsg = e.getMessage() == null ? "-" : e.getMessage();
                            target.setError(act.getString(R.string.imagepick_error_upload_failed, errorMsg));
                        }
                    });
                }
            }).start();

        } catch (Throwable t) {
            Log.e(TAG, "handleActivityResult error", t);
            EditText target = findPendingTargetFor(act);
            if (target != null) {
                // ÖNCE: Hardcoded "İşleme hatası: " + t.getMessage()
                // ŞİMDİ: String resource kullanımı - format string ile error mesajı
                String errorMsg = t.getMessage() == null ? "-" : t.getMessage();
                target.setError(act.getString(R.string.imagepick_error_processing_failed, errorMsg));
            }
        }
        return true;
    }

    // ─────────────────────────────
    // Decode Helpers
    // ─────────────────────────────

    /** Uri → ölçekli Bitmap (ARGB_8888), büyük görsellerde OOM riskini azaltır. */
    @Nullable
    public static Bitmap decodeScaledBitmapFromUri(Context ctx, Uri uri, int maxDim) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
                Bitmap bmp = ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE); // HW yerine SW
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    int sample = calcSampleSize(w, h, maxDim);
                    decoder.setTargetSampleSize(Math.max(1, sample));
                });
                // ImageDecoder çoğu formatta EXIF'i uygular; ek dönüş gerekmez
                return bmp;
            } else {
                // 1) Boyutları öğren
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    BitmapFactory.decodeStream(in, null, bounds);
                }

                // 2) Örnekleme faktörü
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                opts.inSampleSize = calcSampleSize(bounds.outWidth, bounds.outHeight, maxDim);

                // 3) Decode
                Bitmap bmp;
                try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                    bmp = BitmapFactory.decodeStream(in, null, opts);
                }

                if (bmp == null) return null;

                // 4) EXIF dönüklüğü düzelt (özellikle JPEG)
                try (InputStream exifIn = ctx.getContentResolver().openInputStream(uri)) {
                    ExifInterface exif = new ExifInterface(exifIn);
                    int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
                    bmp = rotateByExifIfNeeded(bmp, orientation);
                } catch (Throwable ignore) {
                    // bazı formatlarda EXIF olmayabilir
                }

                return bmp;
            }
        } catch (Throwable t) {
            Log.e(TAG, "decodeScaledBitmapFromUri error", t);
            return null;
        }
    }

    /** PNG data URI üretir: "data:image/png;base64,..." */
    public static String bitmapToPngDataUri(Bitmap bmp) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos); // PNG'de kalite parametresi etkisizdir
        String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        return "data:image/png;base64," + b64;
    }

    /** Hedef en-büyük boyuta göre inSampleSize hesaplar (2'nin kuvvetleri). */
    public static int calcSampleSize(int w, int h, int maxDim) {
        int maxSide = Math.max(Math.max(w, 1), Math.max(h, 1));
        int sample = 1;
        while (maxSide / (sample * 2) > maxDim) sample *= 2;
        return Math.max(1, sample);
    }

    /** EXIF'e göre döndürme (API < 28 yolunda kullanılır). */
    @Nullable
    private static Bitmap rotateByExifIfNeeded(Bitmap src, int exifOrientation) {
        try {
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (exifOrientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    m.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    m.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    m.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    m.preScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    m.preScale(1f, -1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    m.setRotate(90);
                    m.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    m.setRotate(270);
                    m.postScale(-1f, 1f);
                    break;
                case ExifInterface.ORIENTATION_NORMAL:
                case ExifInterface.ORIENTATION_UNDEFINED:
                default:
                    return src;
            }
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if (out != src) src.recycle();
            return out;
        } catch (Throwable t) {
            Log.w(TAG, "rotateByExifIfNeeded failed, returning original", t);
            return src;
        }
    }
}
