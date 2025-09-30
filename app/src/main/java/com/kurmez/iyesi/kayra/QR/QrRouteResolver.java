package com.kurmez.iyesi.kayra.QR;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * iyesi://... biçimindeki normalize edilmiş route'ları uygun Activity'lere çevirir.
 * QR.installResolver(new QrRouteResolver()) ile kaydedildiğinde QR.buildIntentFromQr / QR.route
 * önce buraya danışır.
 *
 * Not:
 * - Activity sınıfları reflection ile açılıyor; derleme bağımlılığı oluşturmaz.
 * - Uygun değilse null döndür; QR.java fallback olarak MainActivity'ye route="..." extra'sıyla açar.
 */
public final class QrRouteResolver implements QR.TargetResolver {
    private static final String TAG = "QrRouteResolver";


    // — İsteğe göre değiştir: Projendeki Activity sınıf isimleri —
    private static final String CLS_MAIN            = "com.kurmez.iyesi.MainActivity";
    private static final String CLS_SOUL_DETAIL     = "com.kurmez.iyesi.features.souls.SoulDetailActivity";
    private static final String CLS_MESSAGE_DETAIL  = "com.kurmez.iyesi.features.messages.MessageDetailActivity";
    private static final String CLS_ADOPTION_DETAIL = "com.kurmez.iyesi.features.adoption.AdoptionDetailActivity";
    private static final String CLS_STREET_CASE     = "com.kurmez.iyesi.features.street.StreetCaseActivity";
    private static final String CLS_EXPLORE         = "com.kurmez.iyesi.features.explore.ExploreActivity";
    private static final String CLS_QR_ADMIN        = "com.kurmez.iyesi.kayra.QR.QRAdmin";

    @Override
    public @Nullable Intent resolve(@NonNull Context ctx, @NonNull Uri route) {
        Log.i(TAG, "[resolve] in");
        try {
            if (!"iyesi".equalsIgnoreCase(route.getScheme())) return null;

            final String host = route.getHost(); // "souls", "message", "qr" vb.
            final java.util.List<String> segs = route.getPathSegments(); // host'tan sonraki parçalar

            if (host == null) return null;

            switch (host) {
                case "souls": {
                    Log.i(TAG,"switch in");
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Log.i(TAG, "[method] in");
                        Intent i = safeIntent(ctx, CLS_SOUL_DETAIL);
                        if (i != null) {
                            Log.i(TAG, "[if] in");
                            i.putExtra("soulId", id);
                            Log.i(TAG, "[putExtra] i.putExtra(\"soulId\", id);");
                            i.putExtra("route", route.toString());
                            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "message":
                case "messages": {
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Log.i(TAG, "[method] in");
                        Intent i = safeIntent(ctx, CLS_MESSAGE_DETAIL);
                        if (i != null) {
                            Log.i(TAG, "[if] in");
                            i.putExtra("messageId", id);
                            Log.i(TAG, "[putExtra] i.putExtra(\"messageId\", id);");
                            i.putExtra("route", route.toString());
                            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "sahiplendirme": {
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Log.i(TAG, "[method] in");
                        Intent i = safeIntent(ctx, CLS_ADOPTION_DETAIL);
                        if (i != null) {
                            Log.i(TAG, "[if] in");
                            i.putExtra("adoptionId", id);
                            Log.i(TAG, "[putExtra] i.putExtra(\"adoptionId\", id);");
                            i.putExtra("route", route.toString());
                            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "sokak": {
                    String caseId = route.getQueryParameter("case");
                    if (!isEmpty(caseId)) {
                        Log.i(TAG, "[method] in");
                        Intent i = safeIntent(ctx, CLS_STREET_CASE);
                        if (i != null) {
                            Log.i(TAG, "[if] in");
                            i.putExtra("caseId", caseId);
                            Log.i(TAG, "[putExtra] i.putExtra(\"caseId\", caseId);");
                            i.putExtra("route", route.toString());
                            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "qr": {
                    // iyesi://qr/admin/{id}
                    String first = firstOrNull(segs);
                    if ("admin".equalsIgnoreCase(first)) {
                        Log.i(TAG, "[method] in");
                        String adminId = segs.size() > 1 ? segs.get(1) : null;
                        Intent i = safeIntent(ctx, CLS_QR_ADMIN);
                        if (i != null) {
                            Log.i(TAG, "[if] in");
                            if (!isEmpty(adminId)) i.putExtra("qr_admin_id", adminId);
                            Log.i(TAG, "[putExtra] if (!isEmpty(adminId)) i.putExtra(\"qr_admin_id\", adminId);");
                            // redirect'li QR'dan geldiyse ham payload'ı da taşı
                            i.putExtra("route", route.toString());
                            Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "explore": {
                    String slug = firstOrNull(segs); // SOUL-123 vb. gelebilir
                    Intent i = safeIntent(ctx, CLS_EXPLORE);
                    if (i != null) {
                        Log.i(TAG, "[if] in");
                        if (!isEmpty(slug)) i.putExtra("slug", slug);
                        Log.i(TAG, "[putExtra] if (!isEmpty(slug)) i.putExtra(\"slug\", slug);");
                        i.putExtra("route", route.toString());
                        Log.i(TAG, "[putExtra] i.putExtra(\"route\", route.toString());");
                        return withFlags(i);
                    }
                    break;
                }
                default:
                    // Bilmediğin host: null döndür → QR.java MainActivity fallback’ini çalıştırır
                    break;
            }
        } catch (Throwable t) {
            Log.i(TAG, "[catch] in");
            Log.e("QrRouteResolver", "resolve failed: " + route, t);
        }
        return null;
    }

    // ————————————————————— helpers —————————————————————
    private static boolean isEmpty(@Nullable String s) { return TextUtils.isEmpty(s); }
    //Log.i(TAG, "[isEmpty] in");

    private static @Nullable String firstOrNull(java.util.List<String> segs) {
        Log.i(TAG, "[firstOrNull] in");
        return (segs != null && !segs.isEmpty()) ? segs.get(0) : null;
    }

    private static @Nullable Intent safeIntent(@NonNull Context ctx, @NonNull String clsName) {
        Log.i(TAG, "[safeIntent] in");
        try {
            Class<?> c = Class.forName(clsName);
            Intent i = new Intent(ctx, c);
            return i;
        } catch (Throwable ignore) {
            Log.i(TAG, "[catch] in");
            return null;
        }
    }

    private static Intent withFlags(Intent i) {
        Log.i(TAG, "[withFlags] in");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }
}