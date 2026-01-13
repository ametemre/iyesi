package com.kurmez.iyesi.kurmes.utilities;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kurmez.iyesi.R;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoadingOverlay — UI-thread safe, ref-counted overlay helper.
 *
 * Davranış:
 *  - show(...) her çağrıldığında internal sayaç artar.
 *  - hide() çağrıldığında sayaç azalır; sayaç 0 ise overlay kaldırılır.
 *  - forceHide() overlay'i hemen kaldırır ve sayacı sıfırlar.
 *
 * Not: Activity.destroy/finish sırasında sızıntı olmaması için mümkünse
 * Activity.onDestroy() içinde LoadingOverlay.forceHide(this) çağır.
 */
public final class LoadingOverlay {
    // Tag key projenizde tanımlı R.id.loading_overlay_root kullanılmaya devam ediyor.
    private static final int OVERLAY_TAG_KEY = R.id.loading_overlay_root;

    private LoadingOverlay() {}

    public static void show(Activity activity, String msg) {
        if (activity == null) return;

        // Eğer activity kapanıyor/kapanmışsa göstermeye çalışma
        if (activity.isFinishing()) return;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) return;

        activity.runOnUiThread(() -> {
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) return;

            // Holder: overlay view + ref count (zayıf referans kullanıyoruz)
            Object tag = root.getTag(OVERLAY_TAG_KEY);
            OverlayHolder holder = tag instanceof OverlayHolder ? (OverlayHolder) tag : null;

            View overlayView = holder != null ? holder.getOverlay() : null;

            try {
                if (overlayView == null) {
                    // Inflate et ve ekle
                    overlayView = LayoutInflater.from(activity).inflate(R.layout.view_loading_overlay, root, false);
                    root.addView(overlayView);
                    // yeni holder oluştur
                    holder = new OverlayHolder(overlayView);
                    root.setTag(OVERLAY_TAG_KEY, holder);
                }

                // Mesajı güncelle
                TextView tv = overlayView.findViewById(R.id.loading_message);
                if (tv != null) {
                    if (msg == null || msg.trim().isEmpty()) {
                        // ÖNCE: Hardcoded "Yükleniyor..."
                        // ŞİMDİ: String resource kullanımı
                        tv.setText(activity.getString(R.string.loading_overlay_default_text));
                    } else {
                        tv.setText(msg);
                    }
                }

                // Göster ve sayaç arttır
                overlayView.setVisibility(View.VISIBLE);
                holder.increment();

            } catch (Exception e) {
                // İnfla/ekleme sırasında hata olursa loglamak veya sessizce geçmek tercih edilebilir.
                // Burada sessizce yutuyoruz (UI thread çalışıyor).
                e.printStackTrace();
            }
        });
    }

    public static void hide(Activity activity) {
        if (activity == null) return;

        // activity is finishing/destroyed kontrolü gereksiz; hide güvenli şekilde çalışmalı
        activity.runOnUiThread(() -> {
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            Object tag = root.getTag(OVERLAY_TAG_KEY);
            if (!(tag instanceof OverlayHolder)) return;
            OverlayHolder holder = (OverlayHolder) tag;
            View overlay = holder.getOverlay();
            if (overlay == null) {
                // temizle
                root.setTag(OVERLAY_TAG_KEY, null);
                return;
            }

            // Sayaçı azalt; 0 ise kaldır
            int remain = holder.decrement();
            if (remain <= 0) {
                try {
                    // Kaldırmadan önce görünürlüğü gizle (kısa flicker için)
                    overlay.setVisibility(View.GONE);
                    root.removeView(overlay);
                } catch (Exception e) {
                    // removeView hata verirse sadece görünürlüğü gizle
                    overlay.setVisibility(View.GONE);
                } finally {
                    root.setTag(OVERLAY_TAG_KEY, null);
                }
            } else {
                // Hala diğer çağrılar mevcut; overlay görünür bırak
            }
        });
    }

    /**
     * Acil durum: overlay'i hemen kaldırır ve sayacı sıfırlar.
     * Örn Activity.onDestroy() içinde çağırmak için faydalıdır.
     */
    public static void forceHide(Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            Object tag = root.getTag(OVERLAY_TAG_KEY);
            if (!(tag instanceof OverlayHolder)) return;
            OverlayHolder holder = (OverlayHolder) tag;
            View overlay = holder.getOverlay();
            if (overlay != null) {
                try {
                    overlay.setVisibility(View.GONE);
                    root.removeView(overlay);
                } catch (Exception e) {
                    overlay.setVisibility(View.GONE);
                }
            }
            root.setTag(OVERLAY_TAG_KEY, null);
        });
    }

    // ----- Helper -----
    private static final class OverlayHolder {
        private final WeakReference<View> overlayRef;
        private final AtomicInteger refCount = new AtomicInteger(0);

        OverlayHolder(View overlay) {
            this.overlayRef = new WeakReference<>(overlay);
        }

        View getOverlay() {
            return overlayRef.get();
        }

        void increment() {
            refCount.incrementAndGet();
        }

        /**
         * Azaltır ve kalan sayıyı döner.
         */
        int decrement() {
            // negatif olmasını engelle
            int prev;
            do {
                prev = refCount.get();
                if (prev <= 0) {
                    // zaten sıfır veya negatif: set to 0 ve döndür
                    refCount.set(0);
                    return 0;
                }
            } while (!refCount.compareAndSet(prev, prev - 1));
            return prev - 1;
        }
    }
}
