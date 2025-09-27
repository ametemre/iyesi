// =============================
// File 1: app/src/main/java/com/kurmez/iyesi/kayra/IyeActivity.java
// =============================
package com.kurmez.iyesi.kayra;

import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.buildJsonFromIye;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kayra.Classes.data.Iye;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.LoadingOverlay;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * IyeActivity — (Sadeleştirilmiş)
 * UI ve etkileşimler burada; Firebase / Functions / App Check gibi dış servislerle
 * olan ilişkilerin tamamı IyeProfileClient içine taşındı.
 */
public class IyeActivity extends AppCompatActivity {
    private static final String TAG = "IyeActivity";
    private static final String FUNCTIONS_REGION = "us-central1"; // profile client'a geçiyoruz
    private static final boolean USE_HTTP_FOR_UPDATE = true;       // istersen burada yönet

    // ====== UI ======
    private ImageView iyeImage, headerTitle;
    private TextView tvCompanion, tvFoundDate, tvPlace, tvWho;
    private ListView listViewIye;

    // ====== Firebase ======
    private FirebaseApp app;

    // ====== State ======
    private Map<String, Object> claimCache = new HashMap<>();
    private boolean isEditing = false;
    private EditAdapter editAdapter;

    // ====== External relations holder ======
    private IyeProfileClient profileClient;

    // ====== Claim keys ======
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

    // ====== Lifecycle ======
    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_iye);

        // Firebase
        app = FirebaseApp.getInstance();
        profileClient = new IyeProfileClient(app, FUNCTIONS_REGION);
        Log.i(TAG, "[onCreate] app=" + app.getName() +
                " projectId=" + app.getOptions().getProjectId() +
                " region=" + FUNCTIONS_REGION);

        // Views
        iyeImage     = findViewById(R.id.iye_image);
        headerTitle  = findViewById(R.id.header_title);
        tvCompanion  = findViewById(R.id.iye_companion);
        tvFoundDate  = findViewById(R.id.iye_found_Date);
        tvPlace      = findViewById(R.id.iye_place);
        tvWho        = findViewById(R.id.who);
        listViewIye  = findViewById(R.id.list_view_iye);

        // Gestures
        iyeImage.setOnLongClickListener(v -> { if (!isEditing) enterEditMode(); return true; });
        iyeImage.setOnClickListener(this::onClickSaveProfile);

        // İlk yükleme
        FirebaseUser uNow = FirebaseAuth.getInstance(app).getCurrentUser();
        Log.d(TAG, "[onCreate] currentUser=" + (uNow==null? "null" : uNow.getUid()) +
                " anon=" + (uNow!=null && uNow.isAnonymous()));
        refreshClaimsAndRender(uNow);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) { return true; }
    @Override public boolean onOptionsItemSelected(MenuItem item) { return super.onOptionsItemSelected(item); }

    // ====== Claims load & UI ======
    private void refreshClaimsAndRender(@Nullable FirebaseUser user) {
        Log.d(TAG, "[refreshClaimsAndRender] user=" + (user==null? "null" : user.getUid()));
        if (user == null || user.isAnonymous()) {
            Toast.makeText(this, "Oturum bulunamadı.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setUiBusy(true, "Profil yükleniyor...");
        profileClient.refreshClaims(user, new IyeProfileClient.ClaimsCallback() {
            @Override public void onSuccess(Map<String, Object> claims) {
                claimCache = claims != null ? claims : new HashMap<>();
                Log.d(TAG, "[claims] keys=" + claimCache.keySet());
                renderUIFromClaims(claimCache);
                profileClient.logAuthAndAppCheck();
                setUiBusy(false, null);
            }
            @Override public void onFailure(String error) {
                setUiBusy(false, null);
                Log.e(TAG, "[claims] FAIL: " + error);
                Toast.makeText(IyeActivity.this, "Token/Claims alınamadı: " + error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renderUIFromClaims(Map<String, Object> claims) {
        String role      = getStringClaim(claims, ClaimsKeys.ROLE);
        String username  = getStringClaim(claims, ClaimsKeys.USERNAME);
        String emailLike = getStringClaim(claims, ClaimsKeys.EMAIL);
        String loc       = getStringClaim(claims, ClaimsKeys.LOCATION);
        String phone     = getStringClaim(claims, ClaimsKeys.PHONE);

        //headerTitle.setImageResource(R.drawable.ic_user);
        tvCompanion.setText(nonEmptyOrDash(username));
        tvCompanion.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvFoundDate.setText(nonEmptyOrDash(emailLike));
        tvPlace.setText(nonEmptyOrDash(loc));
        tvWho.setText(nonEmptyOrDash(phone));

        List<Map.Entry<String, RowMeta>> rows = new ArrayList<>();
        rows.add(Map.entry(ClaimsKeys.USERNAME,  new RowMeta("Kullanıcı Adı", username,  InputType.TYPE_CLASS_TEXT)));
        rows.add(Map.entry(ClaimsKeys.EMAIL,     new RowMeta("E‑posta",       emailLike, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)));
        rows.add(Map.entry(ClaimsKeys.LOCATION,  new RowMeta("Konum",         loc,       InputType.TYPE_CLASS_TEXT)));
        rows.add(Map.entry(ClaimsKeys.PHONE,     new RowMeta("Telefon",       phone,     InputType.TYPE_CLASS_PHONE)));
        rows.add(Map.entry(ClaimsKeys.AVATAR_URL,new RowMeta("Avatar URL",    getStringClaim(claims, ClaimsKeys.AVATAR_URL), InputType.TYPE_TEXT_VARIATION_URI)));

        editAdapter = new EditAdapter(rows);
        listViewIye.setAdapter(editAdapter);
        listViewIye.setVisibility(View.GONE);
        isEditing = false;
    }

    private void enterEditMode() {
        isEditing = true;
        listViewIye.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Düzenleme modu", Toast.LENGTH_SHORT).show();
    }

    private void exitEditModeWithoutSaving() {
        isEditing = false;
        listViewIye.setAdapter(null);
        listViewIye.setVisibility(View.GONE);
    }

    private void onClickSaveProfile(View v) {
        Log.d(TAG, "[onClickSaveProfile] isEditing=" + isEditing);
        if (!isEditing) { enterEditMode(); return; }

        setUiBusy(true, "Kaydediliyor...");
        FirebaseAppCheck.getInstance().getAppCheckToken(true) // sadece erken hatayı görmek için
                .addOnFailureListener(e -> Log.e("APPCHECK","getAppCheckToken FAIL: "+e.getMessage()));

        FirebaseUser uNow = FirebaseAuth.getInstance(app).getCurrentUser();
        if (uNow == null || uNow.isAnonymous()) {
            setUiBusy(false, null);
            Toast.makeText(this, "Giriş yapmalısın.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, Login.class));
            finish();
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            setUiBusy(false, null);
            Toast.makeText(this, "Android 7.0+ gerekli.", Toast.LENGTH_LONG).show();
            return;
        }

        saveAndExitEditMode();
    }

    // ====== Save (delegates to IyeProfileClient) ======
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void saveAndExitEditMode() {
        if (editAdapter == null) {
            exitEditModeWithoutSaving();
            return;
        }
        Map<String, Object> updates = editAdapter.collectEdits();
        updates.remove(ClaimsKeys.UID);
        updates.remove(ClaimsKeys.ROLE);

        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            String key = e.getKey();
            String oldVal = getStringClaim(claimCache, key);
            String newVal = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (!equalsNullable(oldVal, newVal)) filtered.put(key, newVal);
        }

        if (filtered.isEmpty()) {
            exitEditModeWithoutSaving();
            Toast.makeText(this, "Değişiklik yok.", Toast.LENGTH_SHORT).show();
            return;
        }

        Iye currentIye = Iye.fromClaims(claimCache);
        if (currentIye == null) currentIye = new Iye();
        if (filtered.containsKey(ClaimsKeys.USERNAME))  currentIye.setUsername(String.valueOf(filtered.get(ClaimsKeys.USERNAME)));
        if (filtered.containsKey(ClaimsKeys.EMAIL))     currentIye.setEmail(String.valueOf(filtered.get(ClaimsKeys.EMAIL)));
        if (filtered.containsKey(ClaimsKeys.LOCATION))  currentIye.setLocation(String.valueOf(filtered.get(ClaimsKeys.LOCATION)));
        if (filtered.containsKey(ClaimsKeys.PHONE))     currentIye.setPhone(String.valueOf(filtered.get(ClaimsKeys.PHONE)));
        Object au = filtered.get(ClaimsKeys.AVATAR_URL);
        if (au != null) currentIye.setAvatarUrl(String.valueOf(au));

        Map<String, Object> payload = new HashMap<>();
        payload.put("profilDuzenleme", true);
        payload.put("json", buildJsonFromIye(currentIye, claimCache));
        payload.put("alsoWriteToFirestore", true);

        profileClient.updateProfile(payload, USE_HTTP_FOR_UPDATE, new IyeProfileClient.UpdateCallback() {
            @Override public void onSuccess(boolean viaSdk) {
                FirebaseAuth.getInstance(app).getCurrentUser().getIdToken(true);
                setUiBusy(false, null);
                Helpers.showToastSafe(IyeActivity.this, "Profil güncellendi.");
                isEditing = false;
                listViewIye.setAdapter(null);
                listViewIye.setVisibility(View.GONE);
                finish();
            }
            @Override public void onFailure(String error) {
                setUiBusy(false, null);
                Helpers.showToastSafe(IyeActivity.this, "Güncelleme başarısız: " + error);
            }
        });
    }

    // ====== Loading ======
    private void setUiBusy(boolean busy, @Nullable String message) {
        Log.d(TAG, "[setUiBusy] busy=" + busy + " message=" + message);
        if (busy) {
            LoadingOverlay.show(this, message != null ? message : "Yükleniyor...");
            iyeImage.setAlpha(0.5f);
            iyeImage.setEnabled(false);
        } else {
            LoadingOverlay.hide(this);
            iyeImage.setAlpha(1.0f);
            iyeImage.setEnabled(true);
        }
    }

    // ====== Utils ======
    private static boolean equalsNullable(String a, String b) {
        if (TextUtils.isEmpty(a) && TextUtils.isEmpty(b)) return true;
        if (a == null) return false;
        return a.equals(b);
    }
    private static String getStringClaim(Map<String, Object> claims, String key) {
        Object val = claims.get(key);
        return val == null ? "" : String.valueOf(val);
    }
    private static String nonEmptyOrDash(String s) { return TextUtils.isEmpty(s) ? "—" : s; }
    private int dp(int v) { float d = getResources().getDisplayMetrics().density; return Math.round(v * d); }

    // ====== Inline edit row meta & adapter ======
    private static class RowMeta {
        final String label; final String initial; final int inputType;
        RowMeta(String label, String initial, int inputType) {
            this.label = label; this.initial = initial; this.inputType = inputType;
        }
    }

    private class EditAdapter extends BaseAdapter {
        private final List<Map.Entry<String, RowMeta>> data;
        private final Map<String, EditText> editors = new HashMap<>();
        EditAdapter(List<Map.Entry<String, RowMeta>> data) { this.data = data; }
        @Override public int getCount() { return data.size(); }
        @Override public Object getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            Context ctx = parent.getContext();
            TextView label = new TextView(ctx);
            label.setText(data.get(position).getValue().label);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setPadding(dp(16), dp(8), dp(16), dp(4));

            EditText et = new EditText(ctx);
            et.setInputType(data.get(position).getValue().inputType);
            et.setText(data.get(position).getValue().initial);
            et.setPadding(dp(16), dp(4), dp(16), dp(8));

            editors.put(data.get(position).getKey(), et);

            ViewGroup layout = new android.widget.LinearLayout(ctx);
            ((android.widget.LinearLayout) layout).setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.addView(label);
            layout.addView(et);
            return layout;
        }
        Map<String, Object> collectEdits() {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, EditText> e : editors.entrySet()) {
                out.put(e.getKey(), String.valueOf(e.getValue().getText()));
            }
            return out;
        }
    }

    // ====== Entry helpers ======
    public static void launchProfile(Context ctx) {
        Intent it = new Intent(ctx, IyeActivity.class);
        ctx.startActivity(it);
    }
    public static void launchForEdit(Context ctx) {
        Intent it = new Intent(ctx, IyeActivity.class);
        it.putExtra("edit", true);
        ctx.startActivity(it);
    }
}