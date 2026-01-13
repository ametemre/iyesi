package com.kurmez.iyesi.kurmes.utilities.helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * CFObligations — Founded akışındaki Cloud Functions (CF) sorumluluklarını
 * UI’den ayrıştıran, tekrar kullanılabilir bir yardımcı katman.
 *
 * Neler yapar?
 * - Pending companion kontrolü (key ile; genelde Firebase UID)
 * - "soul_inneed" isteğinin gönderimi (gerekirse tek seferlik geri-dönüşümlü retry)
 * - Hata kodu ayrıştırma (401/403/404/500…)
 *
 * Notlar:
 * - UI/Toast/Activity bağımlılığı yoktur. Sonuçları caller’a callback ile verir.
 * - CFHelper’ı içeriden kurabilir ya da dışarıdan verebilirsiniz.
 */
public class CFObligations {

    private static final String TAG = "CFObligations";
    private static final int MAX_LOG_CHARS = 4000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CFHelper cf;
    private final boolean verboseJson;

    /* -------------------------------- Constructors -------------------------------- */

    /** CFHelper dışarıdan verilecekse bu yapıcıyı kullanın. */
    public CFObligations(@NonNull CFHelper cfHelper, boolean verboseJson) {
        this.cf = cfHelper;
        this.verboseJson = verboseJson;
    }

    /** CFHelper’ı içeride oluşturmak için basit kurucu. baseUrl opsiyoneldir (null olabilir). */
    /** CFHelper’ı içeride oluşturmak için basit kurucu. region zorunlu. */
    public CFObligations(@NonNull Context context,
                         @NonNull String projectId,
                         @Nullable String region,
                         boolean verboseJson) {
        // Listener gerekmiyor → null geçilebilir
        String safeRegion = (region == null || region.isEmpty()) ? "us-central1" : region;
        this.cf = new CFHelper(context, projectId, safeRegion, null);
        this.verboseJson = verboseJson;
    }


    /* --------------------------------- Callbacks ---------------------------------- */

    public interface PendingListener {
        void onResult(@Nullable JSONObject companion);
        void onError(@NonNull Throwable error, int httpCode);
    }

    public interface SubmitListener {
        void onSuccess(@NonNull JSONObject response);
        void onError(@NonNull Throwable error, int httpCode, boolean wasRetried);
    }

    /* ------------------------------ Public API (CF) ------------------------------- */

    /**
     * Cihaza ait bekleyen "companion/pending" var mı kontrol eder.
     * UI’den bağımsızdır; sonucu callback’e döner.
     */
    public void checkPendingCompanion(@NonNull String key,
                                      @NonNull PendingListener cb) {
        checkPendingCompanion(key, null, null, cb);
    }

    public void checkPendingCompanion(@NonNull String key,
                                      @Nullable String country,
                                      @Nullable String city,
                                      @NonNull PendingListener cb) {
        Log.i(TAG, "CF checkPendingCompanion START key=" + key
                + " country=" + country + " city=" + city);
        cf.checkPendingCompanion(key, country, city, new CFHelper.PendingCallback() {
            @Override public void onResult(JSONObject companion) {
                Log.i(TAG, "CF checkPendingCompanion OK has=" + (companion != null));
                if (verboseJson && companion != null) logChunked("pending.companion", companion.toString());
                main.post(() -> cb.onResult(companion));
            }
            @Override public void onError(Throwable error) {
                final int code = inferHttpStatus(error);
                Log.w(TAG, "CF checkPendingCompanion ERROR code=" + code + " msg=" + (error == null ? "null" : error.getMessage()));
                main.post(() -> cb.onError(error != null ? error : new RuntimeException("unknown"), code));
            }
        });
    }

    /**
     * "soul_inneed" isteğini gönderir. Eğer image upload hatası gelirse
     * payload’taki "imageBase64" kaldırılıp "imageUrl":"placeholder://holder"
     * ile TEK KEZ retry yapılır (retryEnabled=true ise).
     *
     * @param payload Gönderilecek JSON. (Değiştirilebilir; retry durumunda image alanı güncellenir)
     * @param retryEnabled true ise image-upload-failed durumunda tek sefer retry yapılır
     */
    public void submitSoulInNeed(@NonNull JSONObject payload,
                                 boolean retryEnabled,
                                 @NonNull SubmitListener cb) {
        submitInternal(payload, retryEnabled, /*isRetry*/ false, cb);
    }

    /* ------------------------------- Internal impl -------------------------------- */

    private void submitInternal(@NonNull JSONObject payload,
                                boolean retryEnabled,
                                boolean isRetry,
                                @NonNull SubmitListener cb) {
        final long t0 = System.currentTimeMillis();
        Log.i(TAG, "CF submitSoulInNeed START" + (isRetry ? " (retry)" : ""));
        if (verboseJson) logSafeJson("[SUBMIT] payload", String.valueOf(payload));

        cf.submitSoulInNeed(payload, new CFHelper.EndpointCallback() {
            @Override public void onSuccess(JSONObject resp) {
                long dt = System.currentTimeMillis() - t0;
                Log.i(TAG, "CF submitSoulInNeed END OK (" + dt + " ms)");
                if (verboseJson && resp != null) logChunked("submit.response", resp.toString());
                main.post(() -> cb.onSuccess(resp != null ? resp : new JSONObject()));
            }

            @Override public void onError(Throwable error) {
                long dt = System.currentTimeMillis() - t0;
                final String msg = (error == null ? "" : String.valueOf(error.getMessage()));
                Log.w(TAG, "CF submitSoulInNeed END ERROR (" + dt + " ms): " + msg);

                // Tek seferlik otomatik retry: image upload başarısızsa
                if (!isRetry && retryEnabled && isImageUploadFailure(msg)) {
                    try {
                        payload.remove("imageBase64");
                        // Sahte URL göndermeyelim; UI local placeholder gösterebilir.
                        payload.remove("imageUrl");
                        Log.i(TAG, "Retry without imageUrl");
                    } catch (Exception ignore) { /* no-op */ }
                    submitInternal(payload, /*retryEnabled*/ false, /*isRetry*/ true, cb);
                    return;
                }

                final int code = inferHttpStatus(error);
                main.post(() -> cb.onError(error != null ? error : new RuntimeException("unknown"), code, isRetry));
            }
        });
    }

    /* --------------------------------- Utilities ---------------------------------- */

    /** Founded.java’daki ile aynı mantık: message içinden kaba HTTP kodu tahmini. */
    public static int inferHttpStatus(@Nullable Throwable error) {
        if (error == null) return -1;
        final String m = String.valueOf(error.getMessage());
        if (m.contains("401") || m.contains("UNAUTHENTICATED")) return 401;
        if (m.contains("403") || m.contains("PERMISSION_DENIED") || m.contains("AppCheck")) return 403;
        if (m.contains("404") || m.contains("NOT_FOUND")) return 404;
        if (m.contains("500")) return 500;
        return -1;
    }

    private static boolean isImageUploadFailure(@Nullable String msg) {
        if (msg == null) return false;
        return msg.contains("image-upload-failed");
    }

    private void logSafeJson(@NonNull String prefix, @Nullable String text) {
        if (!verboseJson) return;
        if (text == null) { Log.d(TAG, prefix + " <null>"); return; }
        logChunked(prefix, text);
    }

    private static void logChunked(@NonNull String prefix, @NonNull String text) {
        for (int i = 0; i < text.length(); i += MAX_LOG_CHARS) {
            Log.d(TAG, prefix + ": " + text.substring(i, Math.min(i + MAX_LOG_CHARS, text.length())));
        }
    }
}
