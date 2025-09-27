package com.kurmez.iyesi.kurmes.utilities;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kurmez.iyesi.R;

public final class LoadingOverlay {
    private static final int OVERLAY_TAG_KEY = R.id.loading_overlay_root;

    public static void show(Activity a, String msg) {
        a.runOnUiThread(() -> {
            ViewGroup root = a.findViewById(android.R.id.content);

            // Tag üzerinden mevcut overlay’i bul
            View overlay = (View) root.getTag(OVERLAY_TAG_KEY);
            if (overlay == null) {
                overlay = LayoutInflater.from(a).inflate(R.layout.view_loading_overlay, root, false);
                root.addView(overlay);
                root.setTag(OVERLAY_TAG_KEY, overlay); // <-- artık R.id tabanlı key
            }

            TextView tv = overlay.findViewById(R.id.loading_message);
            if (tv != null && msg != null) tv.setText(msg);
            overlay.setVisibility(View.VISIBLE);
        });
    }

    public static void hide(Activity a) {
        a.runOnUiThread(() -> {
            ViewGroup root = a.findViewById(android.R.id.content);
            View overlay = (View) root.getTag(OVERLAY_TAG_KEY);
            if (overlay != null) overlay.setVisibility(View.GONE);
        });
    }    private LoadingOverlay() {}


}
