// =============================
// File 1: app/src/main/java/com/kurmez/iyesi/kayra/IyeActivity.java
// =============================
package com.kurmez.iyesi.kayra;

import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.buildJsonFromIye;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.MainActivity;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.kayra.Classes.data.Iye;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.LoadingOverlay;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.kurmez.iyesi.kurmes.utilities.helper.ImagePick;
/**
 * IyeActivity — (Sadeleştirilmiş)
 * UI ve etkileşimler burada; Firebase / Functions / App Check gibi dış servislerle
 * olan ilişkilerin tamamı IyeProfileClient içine taşındı.
 */
public class IyeActivity extends AppCompatActivity {
    private static final String TAG = "IyeActivity";
    private static final String FUNCTIONS_REGION = "us-central1"; // profile client'a geçiyoruz
    private static final boolean USE_HTTP_FOR_UPDATE = true;       // istersen burada yönet
    public static final int REQ_PICK_PROFILE_IMAGE = 4011;

    // Activity -> hedef EditText (leak önlemek için WeakReference)
    private static final WeakHashMap<Activity, WeakReference<EditText>> pendingTargets = new WeakHashMap<>();

    // ====== UI ======
    private ImageView iyeImage, headerTitle;
    private TextView tvCompanion, tvFoundDate, tvPlace, tvWho;
    private ListView listViewIye;
    // ====== Membership Guard ======
    private static final String EXTRA_VIA_GUARD = "EXTRA_VIA_GUARD"; // runMembershipGuard(...) bunu set etmeli
    private AlertDialog membershipDialog;
    private String pendingNewPassword; // ileride CF upload/doğrulama vs. için elde tut
    // ====== Firebase ======
    private FirebaseApp app;

    // ====== State ======
    private Map<String, Object> claimCache = new HashMap<>();
    private boolean isEditing = false;
    private EditAdapter editAdapter;

    // ====== External relations holder ======
    private IyeClient profileClient;
    // Alanlar
    private volatile boolean uploadInProgress = false;
    private volatile boolean saveQueued = false;
    private volatile String uploadedObjectPath = null;
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
        profileClient = new IyeClient(app, FUNCTIONS_REGION);
        Log.i(TAG, "[onCreate] app=" + app.getName() + " projectId=" + app.getOptions().getProjectId() + " region=" + FUNCTIONS_REGION);

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
        Log.d(TAG, "[onCreate] currentUser=" + (uNow==null? "null" : uNow.getUid()) + " anon=" + (uNow!=null && uNow.isAnonymous()));
        refreshClaimsAndRender(uNow);
        // Sadece guard ile gelindiyse aç
        if (getIntent().getBooleanExtra(EXTRA_VIA_GUARD, false)) {showMembershipDialog();}
    }
    // ====== Membership Dialog ======
    private void showMembershipDialog() {
        if (membershipDialog != null && membershipDialog.isShowing()) return;
        // EditText’i programatik oluşturuyoruz (şifre gibi davranır)
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint("Üyelik anahtarı / şifre");
        et.setPadding(dp(20), dp(12), dp(20), dp(12));
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
                membershipDialog = new AlertDialog.Builder(this)
                .setTitle("Üyelik Doğrulama")
                .setView(et)
                .setCancelable(false)               // geri tuşu ile kapanmasın
                .setPositiveButton("Devam", null)   // auto-dismiss'i override edeceğiz
                .setNegativeButton("İptal", (d, w) -> {
                    // Guard başarısız/iptal → aktiviteden çık
                    finish();
                })
                .create();
                membershipDialog.setCanceledOnTouchOutside(false); // dışarı dokununca kapanmasın
        membershipDialog.setOnShowListener(d -> {
            // IME’yi aç
                    membershipDialog.getWindow().setSoftInputMode(
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
                        // "Devam" tıklanınca doğrula, sonra kapat
                            membershipDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                                final String text = String.valueOf(et.getText()).trim();
                                if (TextUtils.isEmpty(text)) {
                                    et.setError("Boş olamaz");
                                    et.requestFocus();
                                    return; // dialog açık kalsın
                                }
                                // Burada sadece hafızaya alıyoruz; gerçek işlemi sonra bağlayacaksın
                                        pendingNewPassword = text;
                                membershipDialog.dismiss();
                            });
        });
                // IME "Done" → Pozitif butonu tetikle (dialog kapanmasın; bizim click handler karar verir)
                        et.setOnEditorActionListener((v, actionId, event) -> {
                            if (actionId == EditorInfo.IME_ACTION_DONE) {
                                membershipDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                                return true;
                            }
                            return false;
                        });
                membershipDialog.show();
    }
    // Dilersen guard’tan başlatmak için bu yardımcıyı kullan:
    public static Intent intentFromGuard(Context ctx) {
        Intent it = new Intent(ctx, IyeActivity.class);
        it.putExtra(EXTRA_VIA_GUARD, true);
        return it;
    }
    private void showKeyboard(View v) {
        v.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
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
        profileClient.refreshClaims(user, new IyeClient.ClaimsCallback() {
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
        String url       = getStringClaim(claims, ClaimsKeys.AVATAR_URL);
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
        if (uploadInProgress) { saveQueued = true; setUiBusy(true,"Görsel yükleniyor…"); return; }
        if (uploadedObjectPath == null) { Helpers.showToastSafe(this,"Önce görseli yükleyin"); return; }
        saveAndExitEditMode(); // DB’ye avatarPath = uploadedObjectPath
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

        profileClient.updateProfile(payload, USE_HTTP_FOR_UPDATE, new IyeClient.UpdateCallback() {
            @Override public void onSuccess(boolean viaSdk) {
                FirebaseAuth.getInstance(app).getCurrentUser().getIdToken(true);
                setUiBusy(false, null);
                Helpers.showToastSafe(IyeActivity.this, "Profil güncellendi.");
                isEditing = false;
                listViewIye.setAdapter(null);
                listViewIye.setVisibility(View.GONE);
                startActivity(new Intent(IyeActivity.this, MainActivity.class));
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

    /** Avatar/URL alanına tıklamada çağır: sistem picker'ı aç. */
    public static void askAndPick(Activity act, EditText target) {
        pendingTargets.put(act, new WeakReference<>(target));
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("image/*");
        it.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        // Dönen URI'yi kalıcı kılmak için persist flag talebi:
        it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        it.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        act.startActivityForResult(it, REQ_PICK_PROFILE_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_PROFILE_IMAGE && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) return;

            // 1) Persist izinlerini al
            final int flags = (data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignore) { /* bazı cihazlar izin tekrarı atabilir */ }
            onActivityResult(this, requestCode, resultCode, data);

            // 2) Güvenli decode (ölçekli) → Bitmap (ARGB_8888)
            Bitmap bmp = ImagePick.decodeScaledBitmapFromUri(this, uri, /*maxDim*/ 1600);
            if (bmp == null) {
                // kullanıcıya hata bildir
                return;
            }
            if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
                bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
            }

            // 3) PNG → data URI
            String dataUri = ImagePick.bitmapToPngDataUri(bmp);

            // 4) Upload (background thread)
            EditText target = ImagePick.findPendingTargetFor(this); // senin pendingTargets haritandan çeker
            String path = "images/iye/avatar"; // senaryona göre: "images/soul/avatar" vs.
            String endpoint = "https://us-central1-<project-id>.cloudfunctions.net/saveBase64Image";

            new Thread(() -> {
                try {
                    String url = CFClient.uploadImageAndGetUrlBlocking(this, endpoint, dataUri, path);
                    runOnUiThread(() -> {
                        if (target != null) target.setText(url);
                        // burada UI'da göster / kaydet / avatar önizlemesi yap
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        if (target != null) target.setError("Yükleme hatası: " + e.getMessage());
                    });
                }
            }).start();
        }
    }

    /** Activity.onActivityResult'tan forward et. */
    public static void onActivityResult(Activity act, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQ_PICK_PROFILE_IMAGE) return;
        EditText target = deref(act);

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            Toast.makeText(act, "Resim seçilmedi.", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uri = data.getData();

        // Kullanıcı verdiği okuma iznini kalıcı hale getir (uygulama yeniden açıldığında da erişilsin)
        try {
            final int takeFlags = data.getFlags()
                    & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            act.getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (Exception ignore) { /* Bazı cihazlarda write flag gelmeyebilir, sorun değil. */ }

        if (target != null) {
            // Görsel referansını text'e basitçe yazalım (ileride upload tamamlanınca gerçek URL ile değiştirirsin)
            target.setText(uri.toString());

            // (İsteğe bağlı) Kullanıcıya dosya adı gibi bir bilgi göster
            String name = tryGetDisplayName(act, uri);
            if (name != null) {
                Toast.makeText(act, "Seçildi: " + name, Toast.LENGTH_SHORT).show();
            }
            // İleride upload için URI'yi saklamak istersen:
            target.setTag(uri);
        }
    }

    private static EditText deref(Activity act) {
        WeakReference<EditText> ref = pendingTargets.get(act);
        return ref != null ? ref.get() : null;
    }

    private static String tryGetDisplayName(Activity act, Uri uri) {
        try (android.database.Cursor c = act.getContentResolver()
                .query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignore) {}
        return null;
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

            final String key = data.get(position).getKey();

            EditText et = new EditText(ctx);
            et.setInputType(data.get(position).getValue().inputType);
            et.setText(data.get(position).getValue().initial);
            et.setPadding(dp(16), dp(4), dp(16), dp(8));

            editors.put(data.get(position).getKey(), et);

            ViewGroup layout = new android.widget.LinearLayout(ctx);
            ((android.widget.LinearLayout) layout).setOrientation(android.widget.LinearLayout.VERTICAL);
            layout.addView(label);
            layout.addView(et);
            if (ClaimsKeys.LOCATION.equals(key)) {
                View.OnClickListener askThenFill =
                        v -> Harita.askAndFill(IyeActivity.this, et);
                et.setOnClickListener(askThenFill);
                et.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) askThenFill.onClick(v);
                });
            }
            // "Avatar URL" alanına dokunulduğunda: galeri aç
            if (ClaimsKeys.AVATAR_URL.equals(key)) {
                View.OnClickListener pick = v -> askAndPick(IyeActivity.this, et);
                et.setOnClickListener(pick);
                et.setOnFocusChangeListener((v, hasFocus) -> { if (hasFocus) pick.onClick(v); });
            }
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
    // İzin sonucunu LocationAssist'e forward et
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Harita.onRequestPermissionsResult(this, requestCode, grantResults);
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