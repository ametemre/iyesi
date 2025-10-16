package com.kurmez.iyesi.kayra.Classes.Nodes.ui;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.Nodes.NodeType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Ikon tıklanınca açılan detay paneli. */
public class NodeDetailsBottomSheet extends BottomSheetDialogFragment {
    public static final String ARG_ID   = "markerId";
    public static final String ARG_TYPE = "markerType";
    // Harita.openMarkerDetails(...) içinde isteğe bağlı eklenen payload anahtarları:
    public static final String ARG_MARKER_JSON = "marker_json";
    public static final String ARG_SOULS_JSON  = "souls_json";
    public interface Host {
        void onRequestMarkerReposition(@androidx.annotation.NonNull String markerId);
    }
    private Host host;

    @Override public void onAttach(Context ctx) {
        super.onAttach(ctx);
        if (ctx instanceof Host) host = (Host) ctx;
    }
    public static NodeDetailsBottomSheet newInstance(String markerId, NodeType type) {
        NodeDetailsBottomSheet f = new NodeDetailsBottomSheet();
        Bundle b = new Bundle();
        b.putString(ARG_ID, markerId);
        b.putString(ARG_TYPE, type != null ? type.name() : NodeType.TASK.name());
        f.setArguments(b);
        return f;
    }

    @Override
    public View onCreateView(LayoutInflater i, ViewGroup c, Bundle s) {
        return i.inflate(R.layout.bottomsheet_marker_details, c, false);
    }

    @Override
    public void onViewCreated(View root, Bundle savedInstanceState) {
        super.onViewCreated(root, savedInstanceState);

        Bundle args = getArguments() != null ? getArguments() : new Bundle();
        final String markerId   = args.getString(ARG_ID, "");
        final String typeEnum   = args.getString(ARG_TYPE, NodeType.TASK.name());
        final String markerJson = args.getString(ARG_MARKER_JSON, null);
        final String soulsJson  = args.getString(ARG_SOULS_JSON,  "[]");

        // 1) JSON’ları parse et
        JSONObject marker = null;
        JSONArray souls = new JSONArray();
        try { if (!TextUtils.isEmpty(markerJson)) marker = new JSONObject(markerJson); } catch (JSONException ignore) {}
        try { souls = new JSONArray(soulsJson); } catch (JSONException ignore) {}

        // 2) Üst bilgiler
        String serverType = optType(marker);
        String prettyType = prettifyType(typeEnum, serverType);
        String coords     = extractLatLng(marker);
        int soulsCount    = souls.length();

        TextView tvTitle   = root.findViewById(R.id.txt_title);
        TextView tvMeta    = root.findViewById(R.id.txt_meta);
        TextView tvSouls   = root.findViewById(R.id.txt_souls);
        TextView tvRawJson = root.findViewById(R.id.txt_raw_json);

        String title = prettyType + " • " + shortId(markerId);
        String meta  = joinNonEmpty("\n",
                "ID: " + markerId,
                coords != null ? ("Konum: " + coords) : null,
                serverType != null ? ("Tür (sunucu): " + serverType) : null
        );
        String soulsLine = buildSoulsLine(soulsCount, souls);

        boolean usedXml = false;
        if (tvTitle != null || tvMeta != null || tvSouls != null || tvRawJson != null) {
            usedXml = true;
            if (tvTitle != null) tvTitle.setText(title);
            if (tvMeta  != null) tvMeta.setText(meta);
            if (tvSouls != null) tvSouls.setText(soulsLine);
            if (tvRawJson != null) {
                tvRawJson.setText(marker != null ? marker.toString() : "{}");
                tvRawJson.setVisibility(View.GONE);
            }
        }

        // 3) XML yoksa dinamik içerik kur
        if (!usedXml) {
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                int pad = dp(16);
                LinearLayout container = new LinearLayout(requireContext());
                container.setOrientation(LinearLayout.VERTICAL);
                container.setPadding(pad, pad, pad, pad);

                TextView t1 = makeTextView(18, true, title);
                TextView t2 = makeTextView(14, false, meta);
                TextView t3 = makeTextView(14, false, soulsLine);

                container.addView(t1);
                container.addView(spacer(dp(8)));
                container.addView(t2);
                container.addView(spacer(dp(8)));
                container.addView(t3);

                vg.addView(container,
                        new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                android.widget.Toast.makeText(requireContext(), title, android.widget.Toast.LENGTH_SHORT).show();
            }
        }

        // 4) Tür-bazlı görünürlük ve chip’ler (dinamik; R.id.* yok)
        boolean isFeeding  = isTypeFeeding(typeEnum, serverType);
        boolean isNest     = isTypeNest(typeEnum, serverType);
        boolean isShelter  = isTypeShelter(typeEnum, serverType);

        ChipGroup cgActions = root.findViewById(R.id.chip_group_actions);
        if (cgActions != null) {
            cgActions.removeAllViews();
            int added = 0;

            if (isFeeding) {
                long   lastFedMs = optEpochMs(marker, "lastFedAt");
                String fedBy     = optDeepString(marker, "lastFedBy", "lastFedByName", "fedBy", "fedder");
                Double fedAmt    = optDeepDouble(marker, "lastFedAmount", "lastFedAmountGrams", "fedAmount");
                String fedUnit   = optDeepString(marker, "lastFedUnit", "fedUnit", "unit");

                if (lastFedMs > 0) { addChip(cgActions, "Son besleme: " + formatRelative(lastFedMs)); added++; }
                if (!TextUtils.isEmpty(fedBy)) { addChip(cgActions, "Besleyen: " + fedBy); added++; }
                String amtTxt = humanAmount(fedAmt, fedUnit);
                if (!TextUtils.isEmpty(amtTxt)) { addChip(cgActions, "Miktar: " + amtTxt); added++; }
            }

            if (isNest) {
                long lastCleanedMs = optEpochMs(marker, "lastCleanedAt");
                long lastMaintMs   = optEpochMs(marker, "lastMaintainedAt");
                long lastReviewMs  = Math.max(lastCleanedMs, lastMaintMs);
                if (lastReviewMs > 0) { addChip(cgActions, "Son kontrol: " + formatRelative(lastReviewMs)); added++; }
            }

            if (isShelter) {
                long lastVisitedMs = optEpochMs(marker, "lastVisitedAt");
                if (lastVisitedMs > 0) { addChip(cgActions, "Son ziyaret: " + formatRelative(lastVisitedMs)); added++; }
            }

            cgActions.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
        }
        View chipType = root.findViewById(R.id.chip_type);/*
        if (chipType != null) {
            chipType.setOnLongClickListener(v -> {
                // 1) Rol kontrolü
                Task<String> t = Helpers.getRoleFunction();
                t.addOnSuccessListener(role -> {
                    String r = role == null ? "" : role.trim().toLowerCase();
                    boolean allowed = r.equals("ülgen") || r.equals("ulgen") || r.equals("admin");
                    if (!allowed) {
                        Helpers.showToastSafe(requireContext(), "Bu işlem için yetkin yok");
                        return;
                    }
                    // 2) Host’a bildir ve kapat
                    if (host != null) host.onRequestMarkerReposition(markerId);
                    dismissAllowingStateLoss();
                }).addOnFailureListener(e ->
                        Helpers.showToastSafe(requireContext(), "Rol doğrulanamadı: " + e.getMessage())
                );
                return true; // uzun basma tüketildi
            });
        }*/
    }

    // ---------- Yardımcılar ----------

    private String extractLatLng(JSONObject marker) {
        if (marker == null) return null;
        double lat = Double.NaN, lng = Double.NaN;
        try {
            if (marker.has("lat")) lat = marker.optDouble("lat", Double.NaN);
            if (marker.has("lng")) lng = marker.optDouble("lng", Double.NaN);
            JSONObject data = marker.optJSONObject("data");
            if (Double.isNaN(lat) && data != null) lat = data.optDouble("lat", Double.NaN);
            if (Double.isNaN(lng) && data != null) lng = data.optDouble("lng", Double.NaN);
        } catch (Throwable ignore) {}
        if (Double.isNaN(lat) || Double.isNaN(lng)) return null;
        return String.format(Locale.getDefault(), "%.5f, %.5f", lat, lng);
    }

    private String optType(JSONObject marker) {
        if (marker == null) return null;
        String t = marker.optString("type", null);
        if (t == null && marker.has("data")) {
            JSONObject d = marker.optJSONObject("data");
            if (d != null) t = d.optString("type", null);
        }
        return t;
    }

    private String prettifyType(String enumName, String serverType) {
        String fromEnum;
        try {
            NodeType mt = NodeType.valueOf(enumName);
            switch (mt) {
                case FEEDING: fromEnum = "Besleme"; break;
                case NEST:    fromEnum = "Yuva";    break;
                case SHELTER: fromEnum = "Barınak"; break;
                case TASK:    fromEnum = "Görev";   break;
                default:      fromEnum = "Nokta";
            }
        } catch (Throwable ignore) {
            fromEnum = "Nokta";
        }
        if (!TextUtils.isEmpty(serverType)) return fromEnum + " (" + serverType + ")";
        return fromEnum;
    }

    private String buildSoulsLine(int count, JSONArray souls) {
        StringBuilder sb = new StringBuilder();
        sb.append("Canlar: ").append(count);
        int listed = 0;
        for (int i = 0; i < souls.length() && listed < 5; i++) {
            try {
                JSONObject s = souls.getJSONObject(i);
                String name = s.optString("name", null);
                if (TextUtils.isEmpty(name)) name = s.optString("nickname", null);
                if (!TextUtils.isEmpty(name)) {
                    if (listed == 0) sb.append(" • ");
                    else sb.append(", ");
                    sb.append(name);
                    listed++;
                }
            } catch (JSONException ignore) {}
        }
        return sb.toString();
    }

    private String shortId(String id) {
        if (TextUtils.isEmpty(id)) return "";
        return id.length() > 10 ? id.substring(0, 6) + "…" + id.substring(id.length() - 4) : id;
    }

    private TextView makeTextView(int sp, boolean bold, String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setText(text);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private View spacer(int hPx) {
        View v = new View(requireContext());
        v.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, hPx));
        return v;
    }

    /** Null/boş olanları atlayarak metinleri birleştirir. */
    private String joinNonEmpty(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        if (parts != null) {
            for (String p : parts) {
                if (p == null) continue;
                String t = p.trim();
                if (t.isEmpty()) continue;
                if (!first) sb.append(sep);
                sb.append(t);
                first = false;
            }
        }
        return sb.toString();
    }

    /** Firestore/CF gövdesinden epoch millis değeri okur (kökte ya da data altında). */
    private long optEpochMs(JSONObject obj, String key) {
        if (obj == null) return -1;
        try {
            if (obj.has(key)) {
                Object v = obj.get(key);
                if (v instanceof Number) return ((Number) v).longValue();
                if (v instanceof JSONObject) {
                    JSONObject t = (JSONObject) v;
                    if (t.has("_seconds")) {
                        long sec  = t.optLong("_seconds", 0);
                        long nsec = t.optLong("_nanoseconds", 0);
                        return sec * 1000L + (nsec / 1_000_000L);
                    }
                }
            }
        } catch (JSONException ignore) {}
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optEpochMs(data, key);
        return -1;
    }

    /** İnsan okunur "x dk/sa/gün önce" formatı. */
    private String formatRelative(long tsMs) {
        if (tsMs <= 0) return null;
        long now = System.currentTimeMillis();
        long diff = Math.max(0, now - tsMs);
        long mins = diff / 60000L;
        if (mins < 1) return "şimdi";
        if (mins < 60) return mins + " dk önce";
        long hours = mins / 60;
        if (hours < 24) return hours + " sa önce";
        long days = hours / 24;
        if (days < 7)  return days + " gün önce";
        java.text.DateFormat df = android.text.format.DateFormat.getDateFormat(requireContext());
        return df.format(new java.util.Date(tsMs));
    }

    /** Yeni chip ekler (dinamik). */
    private void addChip(ChipGroup group, String text) {
        Chip chip = new Chip(requireContext(),
                null,
                com.google.android.material.R.style.Widget_MaterialComponents_Chip_Action);
        chip.setClickable(false);
        chip.setCheckable(false);
        chip.setText(text);
        group.addView(chip);
    }

    private int dp(int v) {
        return Math.round(v * Resources.getSystem().getDisplayMetrics().density);
    }

    private boolean isTypeFeeding(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "FEEDING".equals(e) || "BESLEME".equalsIgnoreCase(enumName)
                || s.equals("feeding") || s.equals("besleme");
    }

    private boolean isTypeNest(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "NEST".equals(e) || "YUVA".equalsIgnoreCase(enumName)
                || s.equals("nest") || s.equals("yuva");
    }

    private boolean isTypeShelter(String enumName, String serverType) {
        String e = enumName == null ? "" : enumName.trim().toUpperCase(Locale.ROOT);
        String s = serverType == null ? "" : serverType.trim().toLowerCase(Locale.ROOT);
        return "SHELTER".equals(e) || "BARINAK".equalsIgnoreCase(enumName)
                || s.equals("shelter") || s.equals("barınak");
    }

    // Derin okuma: kökte yoksa data içinde ara
    private String optDeepString(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String k : keys) {
            String v = obj.optString(k, null);
            if (!TextUtils.isEmpty(v)) return v;
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optDeepString(data, keys);
        return null;
    }

    private Double optDeepDouble(JSONObject obj, String... keys) {
        if (obj == null) return null;
        for (String k : keys) {
            if (obj.has(k)) {
                try { return obj.getDouble(k); }
                catch (JSONException ignore) {}
            }
        }
        JSONObject data = obj.optJSONObject("data");
        if (data != null && data != obj) return optDeepDouble(data, keys);
        return null;
    }

    /** 12 → "12", 12.5 → "12.5", birim varsa ekler. */
    private String humanAmount(Double amt, String unit) {
        if (amt == null) return null;
        String n = (Math.abs(amt - Math.rint(amt)) < 1e-9)
                ? String.format(Locale.getDefault(), "%.0f", amt)
                : String.format(Locale.getDefault(), "%.2f", amt);
        if (!TextUtils.isEmpty(unit)) return n + " " + unit;
        return n;
    }
}
