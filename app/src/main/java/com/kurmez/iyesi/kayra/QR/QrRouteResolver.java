package com.kurmez.iyesi.kayra.QR;

import static androidx.core.content.ContextCompat.startActivity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.kurmez.iyesi.kurmes.utilities.Helpers;

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
    private static final String CLS_SOUL_DETAIL     = "com.kurmez.iyesi.kurmes.ui.SoulsManagerActivity";
    private static final String CLS_MESSAGE_DETAIL  = "com.kurmez.iyesi.kurmes.social.message.Messaging";
    private static final String CLS_ADOPTION_DETAIL = "com.kurmez.iyesi.umay.sahiplendirme.Sahiplendirme";
    private static final String CLS_STREET_CASE     = "com.kurmez.iyesi.umay.SokakActivity";
    private static final String CLS_EXPLORE         = "com.kurmez.iyesi.kurmes.social.content.Explore";
    private static final String CLS_QR_ADMIN        = "com.kurmez.iyesi.kayra.QR.QRAdmin";
    private static final String CLS_WELCOME         = "com.kurmez.iyesi.umay.Welcome"; // Yeni ekle
    private static final String CLS_FOUNDED         = "com.kurmez.iyesi.umay.sahiplendirme.Founded";

    public static void resolveTarget(String target, Context ctx) {
        Intent i = null;

        switch (target) {
            case "𐱅𐰭𐰼𐰃": {
                i = safeIntent(ctx, CLS_SOUL_DETAIL);
                if (i != null) {
                    i.putExtra("route", target);
                    // SoulsManagerActivity'de soulId yerine farklı bir extra kullanılıyor olabilir
                    i.putExtra("soulId", target); // Bu satırı kontrol et
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "souls");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Souls feature not available");
                }
                break;
            }
            case "message":
            case "𐱅𐰇𐰼𐰜": {
                i = safeIntent(ctx, CLS_MESSAGE_DETAIL);
                if (i != null) {
                    i.putExtra("route", target);
                    // Messaging activity'si messageId bekliyor olabilir
                    i.putExtra("messageId", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "messages");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Messages feature not available");
                }
                break;
            }
            case "𐰉𐰆𐰑𐰣𐰃": {
                i = safeIntent(ctx, CLS_ADOPTION_DETAIL); // Sahiplendirme activity'si
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "sahiplendirme");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Sahiplendirme feature not available");
                }
                break;
            }
            case "𐰋𐰏𐰠𐰼𐰃": {
                i = safeIntent(ctx, CLS_STREET_CASE); // SokakActivity
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "sokak");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Street case feature not available");
                }
                break;
            }
            case "𐰚𐰼𐰚𐰇𐰠𐰏": {
                i = safeIntent(ctx, CLS_EXPLORE); // Explore activity'si
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "explore");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Explore feature not available");
                }
                break;
            }
            case "𐰓𐰔": {
                i = safeIntent(ctx, CLS_WELCOME); // Welcome activity'si
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "welcome");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Welcome feature not available");
                }
                break;
            }
            case "𐰘𐰃": {
                i = safeIntent(ctx, CLS_QR_ADMIN);
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "qr");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "QR feature not available");
                }
                break;
            }
            case "𐰆𐰍𐰔": {
                i = safeIntent(ctx, CLS_FOUNDED);
                if (i != null) {
                    i.putExtra("route", target);
                } else {
                    i = safeIntent(ctx, CLS_MAIN);
                    i.putExtra("route", "founded");
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    Helpers.showToastSafe(ctx, "Founded feature not available");
                }
                break;
            }
            default: {
                Log.w(TAG, "Bilinmeyen target: " + target);
                i = safeIntent(ctx, CLS_MAIN);
                if (i != null) {
                    i.putExtra("route", target);
                    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                }
                break;
            }
        }

        // Activity'yi başlat
        if (i != null) {
            try {
                ctx.startActivity(i);
                // QRAdmin'den MainActivity'ye geçiyorsak QRAdmin'i kapat
                if (ctx instanceof Activity && i.getComponent() != null &&
                        CLS_MAIN.equals(i.getComponent().getClassName())) {
                    ((Activity) ctx).finish();
                }
            } catch (Exception e) {
                Log.e(TAG, "Activity başlatılamadı: " + e.getMessage());
                // Fallback: MainActivity'yi aç
                Intent fallback = safeIntent(ctx, CLS_MAIN);
                if (fallback != null) {
                    fallback.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(fallback);
                    if (ctx instanceof Activity) {
                        ((Activity) ctx).finish();
                    }
                }
            }
        } else {
            Log.e(TAG, "Intent oluşturulamadı target için: " + target);
            Helpers.showToastSafe(ctx, "Hedef bulunamadı: " + target);
        }
    }


    @Override
    public @Nullable Intent resolve(@NonNull Context ctx, @NonNull Uri route) {
        Log.i(TAG, "[resolve] in");
        try {
            if (!"iyesi".equalsIgnoreCase(route.getScheme())) return null;

            final String host = route.getHost(); // "souls", "message", "qr" vb.
            final java.util.List<String> segs = route.getPathSegments(); // host'tan sonraki parçalar

            if (host == null) return null;

            switch (host) {
                case "node": {
                    // iyesi://node/{COUNTRY}/{CITY}/{NODE_ID}
                    // Target: SokakActivity + Harita + NodeDetailsBottomSheet
                    if (segs.size() >= 3) {
                        String country = segs.get(0);
                        String city = segs.get(1);
                        String nodeId = segs.get(2);
                        if (!isEmpty(country) && !isEmpty(city) && !isEmpty(nodeId)) {
                            Intent i = safeIntent(ctx, CLS_STREET_CASE);
                            if (i != null) {
                                i.putExtra("qr_kind", "node");
                                i.putExtra("qr_country", country.toUpperCase(Locale.ROOT));
                                i.putExtra("qr_city", city.toUpperCase(Locale.ROOT));
                                i.putExtra("qr_node_id", nodeId);
                                i.putExtra("route", route.toString());
                                return withFlags(i);
                            }
                        }
                    }
                    break;
                }
                case "baksi": {
                    // iyesi://baksi/{COUNTRY}/{CITY}/{BAKSI_ID}
                    // Target: Welcome.java (veteriner özelleri aktif)
                    if (segs.size() >= 3) {
                        String country = segs.get(0);
                        String city = segs.get(1);
                        String baksiId = segs.get(2);
                        if (!isEmpty(country) && !isEmpty(city) && !isEmpty(baksiId)) {
                            Intent i = safeIntent(ctx, CLS_WELCOME);
                            if (i != null) {
                                i.putExtra("qr_kind", "baksi");
                                i.putExtra("vetMode", true);
                                i.putExtra("qr_country", country.toUpperCase(Locale.ROOT));
                                i.putExtra("qr_city", city.toUpperCase(Locale.ROOT));
                                i.putExtra("qr_baksi_id", baksiId);
                                i.putExtra("route", route.toString());
                                return withFlags(i);
                            }
                        }
                    }
                    break;
                }
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
                    // Bilmediğin host: null döndür → QR.java MainActivity fallback'ini çalıştırır
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

    private static @Nullable String firstOrNull(java.util.List<String> segs) {
        Log.i(TAG, "[firstOrNull] in");
        return (segs != null && !segs.isEmpty()) ? segs.get(0) : null;
    }

    private static @Nullable Intent safeIntent(@NonNull Context ctx, @NonNull String clsName) {
        Log.i(TAG, "[safeIntent] attempting: " + clsName);
        try {
            Class<?> c = Class.forName(clsName);
            Log.i(TAG, "[safeIntent] SUCCESS: " + clsName);
            Intent i = new Intent(ctx, c);
            return i;
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "[safeIntent] CLASS NOT FOUND: " + clsName, e);
            return null;
        } catch (Throwable t) {
            Log.e(TAG, "[safeIntent] ERROR for: " + clsName, t);
            return null;
        }
    }

    private static Intent withFlags(Intent i) {
        Log.i(TAG, "[withFlags] in");
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return i;
    }
}