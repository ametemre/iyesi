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
        try {
            if (!"iyesi".equalsIgnoreCase(route.getScheme())) return null;

            final String host = route.getHost(); // "souls", "message", "qr" vb.
            final java.util.List<String> segs = route.getPathSegments(); // host'tan sonraki parçalar

            if (host == null) return null;

            switch (host) {
                case "souls": {
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Intent i = safeIntent(ctx, CLS_SOUL_DETAIL);
                        if (i != null) {
                            i.putExtra("soulId", id);
                            i.putExtra("route", route.toString());
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "message":
                case "messages": {
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Intent i = safeIntent(ctx, CLS_MESSAGE_DETAIL);
                        if (i != null) {
                            i.putExtra("messageId", id);
                            i.putExtra("route", route.toString());
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "sahiplendirme": {
                    String id = firstOrNull(segs);
                    if (!isEmpty(id)) {
                        Intent i = safeIntent(ctx, CLS_ADOPTION_DETAIL);
                        if (i != null) {
                            i.putExtra("adoptionId", id);
                            i.putExtra("route", route.toString());
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "sokak": {
                    String caseId = route.getQueryParameter("case");
                    if (!isEmpty(caseId)) {
                        Intent i = safeIntent(ctx, CLS_STREET_CASE);
                        if (i != null) {
                            i.putExtra("caseId", caseId);
                            i.putExtra("route", route.toString());
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "qr": {
                    // iyesi://qr/admin/{id}
                    String first = firstOrNull(segs);
                    if ("admin".equalsIgnoreCase(first)) {
                        String adminId = segs.size() > 1 ? segs.get(1) : null;
                        Intent i = safeIntent(ctx, CLS_QR_ADMIN);
                        if (i != null) {
                            if (!isEmpty(adminId)) i.putExtra("qr_admin_id", adminId);
                            // redirect'li QR'dan geldiyse ham payload'ı da taşı
                            i.putExtra("route", route.toString());
                            return withFlags(i);
                        }
                    }
                    break;
                }
                case "explore": {
                    String slug = firstOrNull(segs); // SOUL-123 vb. gelebilir
                    Intent i = safeIntent(ctx, CLS_EXPLORE);
                    if (i != null) {
                        if (!isEmpty(slug)) i.putExtra("slug", slug);
                        i.putExtra("route", route.toString());
                        return withFlags(i);
                    }
                    break;
                }
                default:
                    // Bilmediğin host: null döndür → QR.java MainActivity fallback’ini çalıştırır
                    break;
            }
        } catch (Throwable t) {
            Log.e("QrRouteResolver", "resolve failed: " + route, t);
        }
        return null;
    }

    // ————————————————————— helpers —————————————————————
    private static boolean isEmpty(@Nullable String s) { return TextUtils.isEmpty(s); }

    private static @Nullable String firstOrNull(java.util.List<String> segs) {
        return (segs != null && !segs.isEmpty()) ? segs.get(0) : null;
    }

    private static @Nullable Intent safeIntent(@NonNull Context ctx, @NonNull String clsName) {
        try {
            Class<?> c = Class.forName(clsName);
            Intent i = new Intent(ctx, c);
            return i;
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Intent withFlags(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }
}
