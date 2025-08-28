package com.kurmez.iyesi;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.view.LayoutInflater;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.HttpsCallableResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ProfileActivity (Inline Edit Mode)
 * - Veriyi CustomClaims'ten okur
 * - Long-press on ImageView (iye_image) => EDIT MODE açılır (ListView editable satırlar)
 * - Tek dokunuş (tap) on ImageView => Kaydeder, token yeniler, ListView boşalır (normal moda döner)
 * - uid & role düzenlenemez
 * - Backend callable: updateClaims
 */
public class ProfileActivity extends AppCompatActivity {

    // === UI ===
    private ImageView iyeImage, headerTitle;
    private TextView tvCompanion, tvFoundDate, tvPlace, tvWho;
    private ListView listViewIye; // normalde boş; edit modda satırlar gösterilir

    // === State ===
    private FirebaseFunctions functions;
    private Map<String, Object> claimCache = new HashMap<>();
    private boolean isEditing = false;

    private EditAdapter editAdapter;

    // Backend callable function adı
    private static final String FN_UPDATE_CLAIMS = "updateClaims";

    // Claim anahtarları
    public static final class ClaimsKeys {
        public static final String UID        = "uid";
        public static final String ROLE       = "role";
        public static final String USERNAME   = "username";
        public static final String EMAIL      = "email";
        public static final String PHONE      = "phone";
        public static final String LOCATION   = "location";
        public static final String AVATAR_URL = "avatarUrl";
        private ClaimsKeys() {}
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_profile);

        // View binding
        iyeImage     = findViewById(R.id.iye_image);
        headerTitle  = findViewById(R.id.header_title);
        tvCompanion  = findViewById(R.id.iye_companion);
        tvFoundDate  = findViewById(R.id.iye_found_Date);
        tvPlace      = findViewById(R.id.iye_place);
        tvWho        = findViewById(R.id.who);
        listViewIye  = findViewById(R.id.list_view_iye);

        functions = FirebaseFunctions.getInstance();

        // ImageView jestleri: long-press = edit mode ON, tap = save & edit mode OFF
        iyeImage.setOnLongClickListener(v -> {
            if (!isEditing) enterEditMode();
            return true;
        });
        iyeImage.setOnClickListener(v -> {
            if (isEditing) saveAndExitEditMode();
        });

        // İlk yükleme
        refreshClaimsAndRender();
    }

    // Menü (isteğe bağlı, burada edit’i menüden kaldırdık; sadece image jestleriyle kontrol ediliyor)
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // getMenuInflater().inflate(R.menu.profile_menu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) { return super.onOptionsItemSelected(item); }

    // === Claims Yükleme / UI doldurma ===
    private void refreshClaimsAndRender() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            Toast.makeText(this, "Oturum bulunamadı.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        user.getIdToken(true)
                .addOnSuccessListener(this::onTokenReady)
                .addOnFailureListener(e ->
                        Toast.makeText(this, "Token alınamadı: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
    }

    private void onTokenReady(GetTokenResult tokenResult) {
        Map<String, Object> claims = tokenResult.getClaims();
        if (claims == null || claims.isEmpty()) {
            Toast.makeText(this, "CustomClaims boş görünüyor.", Toast.LENGTH_LONG).show();
            return;
        }
        claimCache = claims;
        renderUIFromClaims(claims);
    }

    private void renderUIFromClaims(Map<String, Object> claims) {
        String role      = getStringClaim(claims, ClaimsKeys.ROLE);
        String username  = getStringClaim(claims, ClaimsKeys.USERNAME);
        String emailLike = getStringClaim(claims, ClaimsKeys.EMAIL);
        String location  = getStringClaim(claims, ClaimsKeys.LOCATION);
        String phone     = getStringClaim(claims, ClaimsKeys.PHONE);
        String avatarUrl = getStringClaim(claims, ClaimsKeys.AVATAR_URL);

        tvCompanion.setText(nonEmptyOrDash(username));
        tvFoundDate.setText(nonEmptyOrDash(emailLike));
        tvPlace.setText(nonEmptyOrDash(location));
        tvWho.setText(nonEmptyOrDash(role));
        headerTitle.setContentDescription("Profil Başlığı");

        // Normal modda ListView boş/gizli
        if (!isEditing) {
            listViewIye.setAdapter(null);
            listViewIye.setVisibility(View.GONE);
        }
        // Avatar göstermek istersen Glide ile burada yükleyebilirsin
    }

    // === EDIT MODE ===
    private void enterEditMode() {
        if (claimCache == null || claimCache.isEmpty()) {
            Toast.makeText(this, "Düzenlenecek veri yok.", Toast.LENGTH_SHORT).show();
            return;
        }
        isEditing = true;

        // Editlenecek alanları sıralı şekilde hazırlıyoruz (uid/role yok)
        LinkedHashMap<String, RowMeta> rows = new LinkedHashMap<>();
        rows.put(ClaimsKeys.USERNAME,   new RowMeta("Kullanıcı adı", getStringClaim(claimCache, ClaimsKeys.USERNAME), InputType.TYPE_CLASS_TEXT));
        rows.put(ClaimsKeys.EMAIL,      new RowMeta("E-posta",       getStringClaim(claimCache, ClaimsKeys.EMAIL),    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS));
        rows.put(ClaimsKeys.PHONE,      new RowMeta("Telefon",       getStringClaim(claimCache, ClaimsKeys.PHONE),    InputType.TYPE_CLASS_PHONE));
        rows.put(ClaimsKeys.LOCATION,   new RowMeta("Konum",         getStringClaim(claimCache, ClaimsKeys.LOCATION), InputType.TYPE_CLASS_TEXT));
        rows.put(ClaimsKeys.AVATAR_URL, new RowMeta("Avatar URL",    getStringClaim(claimCache, ClaimsKeys.AVATAR_URL), InputType.TYPE_TEXT_VARIATION_URI));

        editAdapter = new EditAdapter(new ArrayList<>(rows.entrySet()));
        listViewIye.setAdapter(editAdapter);
        listViewIye.setVisibility(View.VISIBLE);

        Toast.makeText(this, "Düzenleme modu: değişiklikleri kaydetmek için resme dokun.", Toast.LENGTH_SHORT).show();
    }

    private void saveAndExitEditMode() {
        if (editAdapter == null) {
            isEditing = false;
            listViewIye.setAdapter(null);
            listViewIye.setVisibility(View.GONE);
            return;
        }
        Map<String, Object> updates = editAdapter.collectEdits();

        // uid/role asla gönderme
        updates.remove(ClaimsKeys.UID);
        updates.remove(ClaimsKeys.ROLE);

        // Sadece değişen alanları gönder
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String key = e.getKey();
            String oldVal = getStringClaim(claimCache, key);
            String newVal = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (!equalsNullable(oldVal, newVal)) {
                filtered.put(key, newVal);
            }
        }

        if (filtered.isEmpty()) {
            exitEditModeWithoutSaving();
            Toast.makeText(this, "Değişiklik yok.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Backend’e gönder
        Map<String, Object> payload = new HashMap<>();
        payload.put("updates", filtered);

        setUiBusy(true);
        functions.getHttpsCallable(FN_UPDATE_CLAIMS)
                .call(payload)
                .addOnSuccessListener(this::onUpdateClaimsSuccess)
                .addOnFailureListener(e -> {
                    setUiBusy(false);
                    Toast.makeText(this, "Güncelleme başarısız: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
    }

    private void onUpdateClaimsSuccess(HttpsCallableResult result) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            setUiBusy(false);
            Toast.makeText(this, "Kullanıcı oturumu yok.", Toast.LENGTH_LONG).show();
            return;
        }
        Task<GetTokenResult> t = user.getIdToken(true);
        t.addOnSuccessListener(tr -> {
            setUiBusy(false);
            isEditing = false;
            listViewIye.setAdapter(null);
            listViewIye.setVisibility(View.GONE);
            onTokenReady(tr); // claimCache & UI tazelenir
            Toast.makeText(this, "Profil güncellendi.", Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e -> {
            setUiBusy(false);
            Toast.makeText(this, "Token yenileme hatası: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void exitEditModeWithoutSaving() {
        isEditing = false;
        listViewIye.setAdapter(null);
        listViewIye.setVisibility(View.GONE);
        renderUIFromClaims(claimCache);
    }

    // === Helpers ===
    private void setUiBusy(boolean busy) {
        // İstersen ProgressBar ekleyebilirsin
        iyeImage.setAlpha(busy ? 0.5f : 1.0f);
        iyeImage.setEnabled(!busy);
    }

    private static boolean equalsNullable(String a, String b) {
        if (TextUtils.isEmpty(a) && TextUtils.isEmpty(b)) return true;
        if (a == null) return false;
        return a.equals(b);
    }

    private static String getStringClaim(Map<String, Object> claims, String key) {
        Object val = claims.get(key);
        return val == null ? "" : String.valueOf(val);
    }

    private static String nonEmptyOrDash(String s) {
        return TextUtils.isEmpty(s) ? "—" : s;
    }

    // === Inline edit için basit satır meta & adapter ===

    private static class RowMeta {
        final String label;
        final String initial;
        final int inputType;
        RowMeta(String label, String initial, int inputType) {
            this.label = label;
            this.initial = initial;
            this.inputType = inputType;
        }
    }

    private class EditAdapter extends BaseAdapter {
        private final List<Map.Entry<String, RowMeta>> data;
        private final Map<String, EditText> editors = new HashMap<>();

        EditAdapter(List<Map.Entry<String, RowMeta>> data) { this.data = data; }

        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                // Basit yatay layout: Label | EditText
                LinearLayout layout = new LinearLayout(ProfileActivity.this);
                layout.setOrientation(LinearLayout.VERTICAL);
                layout.setPadding(dp(12), dp(10), dp(12), dp(10));

                TextView tv = new TextView(ProfileActivity.this);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
                tv.setTextSize(14);
                tv.setPadding(0, 0, 0, dp(6));

                EditText et = new EditText(ProfileActivity.this);
                et.setSingleLine(true);
                et.setPadding(dp(10), dp(8), dp(10), dp(8));
                et.setBackground(null); // sade görünüm
                et.setTextSize(15);

                layout.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                layout.addView(et, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

                row = layout;
                row.setTag(new Holder(tv, et));
            }

            Holder h = (Holder) row.getTag();
            Map.Entry<String, RowMeta> entry = data.get(position);
            String key = entry.getKey();
            RowMeta meta = entry.getValue();

            h.label.setText(meta.label);
            h.input.setInputType(meta.inputType);
            h.input.setText(meta.initial == null ? "" : meta.initial);
            h.input.setHint(meta.label);

            editors.put(key, h.input);
            return row;
        }

        Map<String, Object> collectEdits() {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<String, RowMeta> e : data) {
                String key = e.getKey();
                EditText et = editors.get(key);
                String val = et == null ? "" : et.getText().toString().trim();
                out.put(key, val);
            }
            return out;
        }

        class Holder {
            final TextView label;
            final EditText input;
            Holder(TextView l, EditText i) { this.label = l; this.input = i; }
        }

        private int dp(int v) {
            float d = getResources().getDisplayMetrics().density;
            return Math.round(v * d);
        }
    }

    private int dp(int v) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }
    public void launchProfile(Context context) {
        Intent intent = new Intent(context, ProfileActivity.class);
        context.startActivity(intent);
        finish();
    }
    public void launchForEdit(Context context) {
        Intent intent = new Intent(context, ProfileActivity.class);
        // İstersen ileride "editMode" flag’ı koyabilirsin:
        intent.putExtra("editMode", true);
        context.startActivity(intent);
        finish();
    }
}
