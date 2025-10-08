// =============================
// File 1: app/src/main/java/com/kurmez/iyesi/kayra/IyeActivity.java
// =============================
package com.kurmez.iyesi.kayra;

import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.buildJsonFromIye;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.location.Location;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.kurmez.iyesi.Login;
import com.kurmez.iyesi.MainActivity;
import com.kurmez.iyesi.R;
import com.kurmez.iyesi.kurmes.utilities.helper.FireBaseHelper;
import com.kurmez.iyesi.umay.sokak.Harita;
import com.kurmez.iyesi.kayra.Classes.data.Iye;
import com.kurmez.iyesi.kurmes.utilities.Helpers;
import com.kurmez.iyesi.kurmes.utilities.LoadingOverlay;
import com.kurmez.iyesi.kurmes.utilities.helper.net.CFClient;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.kurmez.iyesi.kurmes.utilities.helper.ImagePick;
import android.location.Address;
import android.location.Geocoder;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * IyeActivity — (Sadeleştirilmiş)
 * UI ve etkileşimler burada; Firebase / Functions / App Check gibi dış servislerle
 * olan ilişkilerin tamamı IyeProfileClient içine taşındı.
 */
public class IyeActivity extends AppCompatActivity {
    private static final String TAG = "IyeActivity";
    private static final String BaseURL = "https://us-central1-iyesi-e8d4f.cloudfunctions.net";
    private static final String FUNCTIONS_REGION = "us-central1"; // profile client'a geçiyoruz
    private static final boolean USE_HTTP_FOR_UPDATE = true;       // istersen burada yönet
    public static final int REQ_PICK_PROFILE_IMAGE = 4011;
    // sınıf başında
    private FusedLocationProviderClient fusedLocationClient;
    private final com.google.android.gms.tasks.CancellationTokenSource placeCts = new com.google.android.gms.tasks.CancellationTokenSource();
    private static final int REQ_LOC_FOR_PLACE = 2013;
    @Nullable private Runnable pendingAfterLocation = null;
    @Nullable private Double lastLat = Double.NaN, lastLng = Double.NaN;

    // Activity -> hedef EditText (leak önlemek için WeakReference)
    private static final WeakHashMap<Activity, WeakReference<EditText>> pendingTargets = new WeakHashMap<>();
    private Iye currentIye;
    // ====== UI ======
    private ImageView iyeImage, headerTitle;
    private TextView tvCompanion, tvFoundDate, tvPlace, tvWho;
    private ListView listViewIye;
    // ====== Membership Guard ======
    private static final String EXTRA_VIA_GUARD = "EXTRA_VIA_GUARD"; // runMembershipGuard(...) bunu set etmeli
    private AlertDialog membershipDialog;
    private String pendingNewPassword; // ileride CF upload/doğrulama vs. için elde tut
    private Context ctx;
    // ====== Firebase ======
    private FirebaseApp app;
    private FirebaseUser u;
    // ====== State ======
    private Map<String, Object> claimCache = new HashMap<>();
    private boolean isEditing = false;
    private EditAdapter editAdapter;
    private FireBaseHelper firebaseHelper = new FireBaseHelper();
    // ====== External relations holder ======
    private IyeClient profileClient;
    // Alanlar
    private volatile boolean uploadInProgress = false;
    private volatile boolean saveQueued = false;
    private volatile String uploadedObjectPath = null;
    private String role;
    private String username;
    private String emailLike;
    private String loc;
    private String phone;
    private String url;
private Boolean failSafe = false;
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

        fusedLocationClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this);
        // Gestures
        iyeImage.setOnLongClickListener(v -> { if (!isEditing) enterEditMode(); return true; });
        iyeImage.setOnClickListener(this::onClickSaveProfile);
        // İlk yükleme
        u = FirebaseAuth.getInstance(app).getCurrentUser();
        Log.d(TAG, "[onCreate] currentUser=" + (u==null? "null" : u.getUid()) + " anon=" + (u!=null && u.isAnonymous()));
        // Eğer intent'te edit flag'i yoksa normal modda başla
        boolean startInEditMode = getIntent().getBooleanExtra("edit", false);
        refreshClaimsAndRender(u);
        if (startInEditMode) {
            // Sadece edit intent'i ile gelindiyse düzenleme modunda başla
            enterEditMode();
        }
        // Sadece guard ile gelindiyse aç
        if (getIntent().getBooleanExtra(EXTRA_VIA_GUARD, false)) {showMembershipDialog();}
    }
    // ====== Membership Dialog ======
    private void showMembershipDialog() {

        if (membershipDialog != null && membershipDialog.isShowing()) return;
        // EditText’i programatik oluşturuyoruz (şifre gibi davranır)
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        et.setHint("Yeni Şifre Belirle...");
        et.setPadding(dp(20), dp(12), dp(20), dp(12));
        et.setSingleLine(true);
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);

        membershipDialog = new AlertDialog.Builder(this)
                .setTitle("Üyelik Doğrulama")
                .setView(et)
                .setCancelable(false)               // geri tuşu ile kapanmasın
                .setPositiveButton("Devam", (d,w)-> {
                    FireBaseHelper.changePassword(u.getEmail(), et.getText().toString(), new FireBaseHelper.PasswordChangeCallback() {
                        @Override
                        public void onSuccess() {
                            Toast.makeText(getApplicationContext(), "Şifre başarıyla değiştirildi.", Toast.LENGTH_SHORT).show();
                            iyeImage.performClick();

                        }

                        @Override
                        public void onFailure(Exception e) {
                            Toast.makeText(getApplicationContext(), "Şifre değiştirilemedi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            finish();
                        }

                    });
                    //iyeImage.performClick();
                    failSafe = true;
                })
                .setNegativeButton("İptal", (d, w) -> {
                    // Guard başarısız/iptal → aktiviteden çık
                    //finish();
                    d.dismiss();
                    iyeImage.performClick();
                })
                .create();

        membershipDialog.setCanceledOnTouchOutside(false); // dışarı dokununca kapanmasın
        membershipDialog.setOnShowListener(d -> {
            // IME’yi aç
            membershipDialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

            final Button positive = membershipDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Button negative = membershipDialog.getButton(AlertDialog.BUTTON_NEGATIVE);

            // "Devam" tıklanınca doğrula, sonra kapat
            positive.setOnClickListener(v -> {
                final String text = String.valueOf(et.getText()).trim();
                if (TextUtils.isEmpty(text)) {
                    et.setError("Boş olamaz");
                    et.requestFocus();
                    return; // dialog açık kalsın
                }

                // Eski şifre ilk üyelikte e-mail olduğundan, burada eski parola yerine u.getEmail() kullanılıyor
                final String oldPassword = (u != null && u.getEmail() != null) ? u.getEmail() : "";
                if (TextUtils.isEmpty(oldPassword)) {
                    et.setError("Kullanıcı e-posta bilgisi bulunamadı.");
                    et.requestFocus();
                    return;
                }

                // UI kilitleme
                positive.setEnabled(false);
                negative.setEnabled(false);
                positive.setText("Doğrulanıyor...");

                // Orijinal koda sadık kalarak firebaseHelper'in changePassword metodunu çağırıyoruz.
                // Varsayım: firebaseHelper.changePassword(String oldPassword, String newPassword, Callback)
                firebaseHelper.changePassword(oldPassword, text, new FireBaseHelper.PasswordChangeCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            pendingNewPassword = text;
                            Toast.makeText(IyeActivity.this, "Şifre başarıyla değiştirildi.", Toast.LENGTH_SHORT).show();
                            membershipDialog.dismiss();
                            iyeImage.performClick();
                        });
                    }

                    @Override
                    public void onFailure(Exception e) {
                        runOnUiThread(() -> {
                            String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "Şifre değiştirme başarısız.";
                            et.setError(msg);
                            et.requestFocus();

                            positive.setEnabled(true);
                            negative.setEnabled(true);
                            positive.setText("Devam");
                        });
                    }
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
        });


        //if (username != null && url != null ){membershipDialog.dismiss();}
        if (username == null || url == null ){membershipDialog.show();}
        if (failSafe){membershipDialog.dismiss();}
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
        role      = getStringClaim(claims, ClaimsKeys.ROLE);
        username  = getStringClaim(claims, ClaimsKeys.USERNAME);
        emailLike = getStringClaim(claims, ClaimsKeys.EMAIL);
        loc       = getStringClaim(claims, ClaimsKeys.LOCATION);
        phone     = getStringClaim(claims, ClaimsKeys.PHONE);
        url       = getStringClaim(claims, ClaimsKeys.AVATAR_URL);
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
// renderUIFromClaims içinde, url değişkenini aldıktan sonra:
        if (!TextUtils.isEmpty(url)) {
            try {
                // Basit ve güvenli: Glide otomatik olarak asenkron indirir ve cache'ler
                Glide.with(this)
                        .load(url)
                        .placeholder(R.drawable.ic_iye)    // isteğe bağlı
                        .error(R.drawable.ic_follow)          // isteğe bağlı
                        .into(iyeImage);
            } catch (Exception e) {
                Log.w(TAG, "Glide yükleme hatası: " + e.getMessage(), e);
                iyeImage.setImageResource(R.drawable.ic_delete);
            }
        } else {
            iyeImage.setImageResource(R.drawable.holder);
        }

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
        JSONObject payload = new JSONObject();
        currentIye = Iye.fromClaims(claimCache);
        if (currentIye == null) currentIye = new Iye();
        if (filtered.containsKey(ClaimsKeys.USERNAME))  currentIye.setUsername(String.valueOf(filtered.get(ClaimsKeys.USERNAME)));
        if (filtered.containsKey(ClaimsKeys.EMAIL))     currentIye.setEmail(String.valueOf(filtered.get(ClaimsKeys.EMAIL)));
        if (filtered.containsKey(ClaimsKeys.LOCATION))  currentIye.setLocation(String.valueOf(filtered.get(ClaimsKeys.LOCATION)));
        if (filtered.containsKey(ClaimsKeys.PHONE))     currentIye.setPhone(String.valueOf(filtered.get(ClaimsKeys.PHONE)));
        Object au = filtered.get(ClaimsKeys.AVATAR_URL);
        if (au != null) currentIye.setAvatarUrl(String.valueOf(au));

        try {
            // JSON'u doğru şekilde oluştur
            JSONObject iyeJson = new JSONObject();

            // Temel alanlar
            iyeJson.put("username", currentIye.getUsername() != null ? currentIye.getUsername() : "");
            iyeJson.put("email", currentIye.getEmail() != null ? currentIye.getEmail() : "");
            iyeJson.put("phone", currentIye.getPhone() != null ? currentIye.getPhone() : "");
            iyeJson.put("avatarUrl", currentIye.getAvatarUrl() != null ? currentIye.getAvatarUrl() : "");

            // ⭐ YENİ: Location object olarak
            if (currentIye.getLocation() != null) {
                JSONObject locationJson = new JSONObject();
                Iye.Location location = currentIye.getLocation();
                locationJson.put("address", location.getAddress() != null ? location.getAddress() : "");
                if (location.getLat() != null) locationJson.put("lat", location.getLat());
                if (location.getLng() != null) locationJson.put("lng", location.getLng());
                iyeJson.put("location", locationJson);
            } else {
                iyeJson.put("location", new JSONObject());
            }

            // ID Token ekle - ⭐ ZORUNLU
            FirebaseUser user = FirebaseAuth.getInstance(app).getCurrentUser();
            if (user == null) {
                setUiBusy(false, null);
                Toast.makeText(this, "Kullanıcı bulunamadı.", Toast.LENGTH_LONG).show();
                return;
            }

            user.getIdToken(false).addOnSuccessListener(tokenResult -> {
                String idToken = tokenResult.getToken();


                try {
                    payload.put("idToken", idToken); // ZORUNLU
                    payload.put("json", new JSONObject(buildJsonFromIye(currentIye, claimCache))); // ⭐ GÜNCEL
                    payload.put("alsoWriteToFirestore", true);

                    Log.d(TAG, "Gönderilen JSON: " + payload.toString(2));
                } catch (JSONException e) {
                    setUiBusy(false, null);
                    Helpers.showToastSafe(this, "Hata: JSON oluşturulamadı: " + e.getMessage());
                }
            });

        } catch (JSONException e) {
            setUiBusy(false, null);
            Helpers.showToastSafe(this, "Hata: JSON oluşturulamadı: " + e.getMessage());
            return;
        }

        CFClient cfClient = new CFClient(BaseURL);
        setUiBusy(true, "Kaydediliyor...");

        // ESKİ: cfClient.postJsonAsync("updateProfile", payload, new CFClient.JsonCallback() {
        // YENİ:
        String url = BaseURL.endsWith("/") ? BaseURL + "updateIye" : BaseURL + "/updateIye";
        // çağırmadan önce
        if (!isNetworkAvailable()) {
            setUiBusy(false, null);
            Toast.makeText(this, "İnternet bağlantısı yok.", Toast.LENGTH_LONG).show();
            return;
        }

        cfClient.postJsonAsync(url, payload, new CFClient.JsonCallback() {

            @Override
            public void onSuccess(@NonNull JSONObject obj) {
                Log.d(TAG, "updateIye başarılı: " + obj.toString());  // updateProfile -> updateIye
                listViewIye.setVisibility(View.GONE);
                // Token'ı refresh et
                FirebaseAuth.getInstance(app).getCurrentUser().getIdToken(true);
                setUiBusy(false, null);
                Helpers.showToastSafe(IyeActivity.this, "Profil güncellendi.");
                // SADECE düzenleme modundan çık, yönlendirme YAPMA
                isEditing = false;
                listViewIye.setAdapter(null);
                listViewIye.setVisibility(View.GONE);
                // Claims'i yenile ve UI'ı güncelle
                refreshClaimsAndRender(FirebaseAuth.getInstance(app).getCurrentUser());
                // Yönlendirme YAPMIYORUZ - kullanıcı burada kalıyor
                startActivity(new Intent(IyeActivity.this, MainActivity.class));
                finish();
            }

            @Override public void onError(@NonNull Throwable t) {
                Log.e(TAG, "updateIye hatası: " + t.getMessage(), t);
                setUiBusy(false, null);
                String msg = t.getMessage() != null && t.getMessage().contains("Unable to resolve host")
                        ? "Sunucu adresine ulaşılamıyor. İnternet bağlantınızı veya base URL'inizi kontrol edin."
                        : "Güncelleme başarısız: " + t.getMessage();
                Helpers.showToastSafe(IyeActivity.this, msg);
            }

        });
    }
    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
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

    /**
     * Görsel yükler ve URL'yi String olarak döndürür (blocking)
     *
     * @param uri       Görsel URI'sı
     * @param endpoint  Cloud Functions endpoint
     * @param path      Storage path (örn: "images/iye/avatar")
     * @return          Yüklenen görselin URL'si
     * @throws Exception Yükleme hatası durumunda
     */
    /**
     * Görsel yükler ve URL'yi String olarak döndürür (blocking)
     *
     * @param uri       Görsel URI'sı
     * @param endpoint  Cloud Functions endpoint
     * @param path      Storage path (örn: "images/iye/avatar")
     * @return          Yüklenen görselin URL'si
     * @throws Exception Yükleme hatası durumunda
     */
    private String handlePickedImage(Uri uri, String endpoint, String path) throws Exception {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null");
        }

        // 1) URI'dan görsel verisini oku
        byte[] imageBytes;
        try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) {
                throw new IllegalStateException("Dosya açılamadı: " + uri);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buf)) != -1) {
                baos.write(buf, 0, bytesRead);
            }
            imageBytes = baos.toByteArray();
        }

        // 2) Base64 data URI'ya dönüştür
        String mimeType = getContentResolver().getType(uri);
        if (mimeType == null) mimeType = "image/jpeg";

        String base64 = android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP);
        String dataUri = "data:" + mimeType + ";base64," + base64;

        // 3) Görseli yükle ve URL'yi döndür (blocking) - PARAMETRE SIRASI DÜZELTİLDİ
        return CFClient.uploadImageAndGetUrlBlocking(this, endpoint, dataUri, path);
        // .toString() KALDIRILDI - zaten String dönüyor
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

            // 2) Hedef EditText'i al
            EditText target = deref(this);

            // 3) handlePickedImage kullanarak görsel yükle (arka planda)
            // BEFORE starting new Thread:
            runOnUiThread(() -> setUiBusy(true, "Görsel yükleniyor…"));

            new Thread(() -> {
                try {
                    String endpoint = BaseURL + "/saveBase64Image";
                    String path = "images/iye/avatar/" + System.currentTimeMillis();
                    String imageUrl = handlePickedImage(uri, endpoint, path);

                    runOnUiThread(() -> {
                        // başarılıysa
                        if (target != null) {
                            target.setText(imageUrl);
                            target.setTag(uri);
                        }
                        iyeImage.setImageURI(uri);
                        uploadedObjectPath = imageUrl;
                        Helpers.showToastSafe(IyeActivity.this, "Görsel yüklendi");
                        setUiBusy(false, null); // ÖNEMLİ: overlay'i kapat
                    });

                } catch (final Exception e) {
                    runOnUiThread(() -> {
                        if (target != null) {
                            target.setError("Yükleme hatası: " + e.getMessage());
                        }
                        Helpers.showToastSafe(IyeActivity.this, "Yükleme başarısız: " + e.getMessage());
                        setUiBusy(false, null); // Hata durumunda da kapat
                    });
                }
            }).start();
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
            ctx = parent.getContext();

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
// EditAdapter içindeki karmaşık konum kodunu basitleştir
            if (ClaimsKeys.LOCATION.equals(key)) {
                et.setFocusable(false);
                et.setFocusableInTouchMode(false);
                et.setCursorVisible(false);
                et.setHint("Konum seçmek için dokun");

                final long[] lastClick = {0L};
                View.OnClickListener openMapWithPermission = v -> {
                    long now = System.currentTimeMillis();
                    if (now - lastClick[0] < 1000) return; // 1 saniye debounce
                    lastClick[0] = now;

                    // Basit konum alma akışı
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                        // Android 6.0 altı için doğrudan harita aç
                        Harita.askAndFill(IyeActivity.this, et);
                        return;
                    }

                    // İzin kontrolü
                    boolean hasFineLocation = ContextCompat.checkSelfPermission(ctx,
                            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                    boolean hasCoarseLocation = ContextCompat.checkSelfPermission(ctx,
                            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;

                    if (hasFineLocation || hasCoarseLocation) {
                        // İzin varsa hızlı konum alma
                        requestLocationWithTimeout(et, 15000); // 15 saniye timeout
                    } else {
                        // İzin yoksa hedefi sakla ve izin iste
                        pendingTargets.put(IyeActivity.this, new WeakReference<>(et));
                        ActivityCompat.requestPermissions(IyeActivity.this,
                                new String[]{
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                },
                                REQ_LOC_FOR_PLACE);
                    }
                };

                et.setOnClickListener(openMapWithPermission);

                // Uzun basınca harita açma seçeneği
                et.setOnLongClickListener(v -> {
                    Harita.askAndFill(IyeActivity.this, et);
                    return true;
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
    // IyeActivity.java - Konum alma metodunu güncelle
    private void requestLocationWithTimeout(@NonNull EditText target, long timeoutMs) {
        if (fusedLocationClient == null) {
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        }

        target.post(() -> target.setText("Konum aranıyor..."));

        // Timeout kontrolü
        final Handler timeoutHandler = new Handler();
        final Runnable timeoutRunnable = () -> {
            Log.w(TAG, "Konum alma timeout oldu");
            target.post(() -> {
                target.setText("");
                Toast.makeText(IyeActivity.this, "Konum alınamadı - zaman aşımı", Toast.LENGTH_SHORT).show();
            });
        };

        // İzin kontrolü
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {

            target.post(() -> {
                target.setText("");
                Toast.makeText(IyeActivity.this, "Konum izni gerekli", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        // Öncelikle son bilinen konumu hızlıca al
        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(location -> {
                    if (location != null) {
                        timeoutHandler.removeCallbacks(timeoutRunnable);
                        lastLat = location.getLatitude();
                        lastLng = location.getLongitude();
                        getAddressAndFill(target, lastLat, lastLng);
                        return;
                    }

                    // Son konum yoksa, yeni konum iste
                    LocationRequest locationRequest = LocationRequest.create();
                    locationRequest.setPriority(LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY);
                    locationRequest.setInterval(10000);
                    locationRequest.setFastestInterval(5000);
                    locationRequest.setNumUpdates(1);
                    locationRequest.setMaxWaitTime(15000);

                    LocationCallback locationCallback = new LocationCallback() {
                        @Override
                        public void onLocationResult(LocationResult locationResult) {
                            timeoutHandler.removeCallbacks(timeoutRunnable);
                            if (locationResult == null) {
                                target.post(() -> {
                                    target.setText("");
                                    Toast.makeText(IyeActivity.this, "Konum alınamadı", Toast.LENGTH_SHORT).show();
                                });
                                return;
                            }
                            Location location = locationResult.getLastLocation();
                            lastLat = location.getLatitude();
                            lastLng = location.getLongitude();
                            getAddressAndFill(target, lastLat, lastLng);

                            // Callback'i kaldır
                            fusedLocationClient.removeLocationUpdates(this);
                        }

                        @Override
                        public void onLocationAvailability(LocationAvailability locationAvailability) {
                            if (!locationAvailability.isLocationAvailable()) {
                                timeoutHandler.removeCallbacks(timeoutRunnable);
                                target.post(() -> {
                                    target.setText("");
                                    Toast.makeText(IyeActivity.this, "Konum servisi kullanılamıyor", Toast.LENGTH_SHORT).show();
                                });
                                fusedLocationClient.removeLocationUpdates(this);
                            }
                        }
                    };

                    // Timeout'u başlat
                    timeoutHandler.postDelayed(timeoutRunnable, timeoutMs);

                    // Konum güncellemelerini iste
                    fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, null);

                })
                .addOnFailureListener(e -> {
                    timeoutHandler.removeCallbacks(timeoutRunnable);
                    Log.e(TAG, "getLastLocation failed: " + e.getMessage());
                    target.post(() -> {
                        target.setText("");
                        Toast.makeText(IyeActivity.this, "Konum alınamadı: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    });
                });
    }
    private void runPendingAfterLocation() {
        Log.d(TAG, "[LOC] runPendingAfterLocation()");
        Runnable r = pendingAfterLocation;
        pendingAfterLocation = null;
        if (r != null) {
            r.run();
        }
    }

    private void ensureLocationThen(@NonNull Runnable next) {
        Log.d(TAG, "[LOC] ensureLocationThen → GİRİŞ");
        pendingAfterLocation = next;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "[LOC] requesting ACCESS_FINE_LOCATION");
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_LOC_FOR_PLACE);
            return;
        }

        // İzin varsa aktif olarak bir konum iste (getCurrentLocation daha güvenilir)
        fusedLocationClient.getCurrentLocation(
                com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                placeCts.getToken()
        ).addOnSuccessListener(loc -> {
            if (loc != null) {
                lastLat = loc.getLatitude(); lastLng = loc.getLongitude();
                // gerekli iş: örn placeView.setText(formatLatLng(...)) veya reverse-geocode
                if (pendingAfterLocation != null) {
                    Runnable r = pendingAfterLocation;
                    pendingAfterLocation = null;
                    r.run();
                }
            } else {
                Log.w(TAG, "[LOC] getCurrentLocation returned null -> running pending as fallback");
                runPendingAfterLocation();
            }
        }).addOnFailureListener(e -> {
            Log.w(TAG, "[LOC] getCurrentLocation fail: " + e.getMessage());
            runPendingAfterLocation();
        });
    }
// Gerekli importlar dosyanın başına ekli değilse ekle:


    private void getAddressAndFill(@NonNull EditText target, double lat, double lng) {
        // Güvenli kontrol: NaN/Infinite ve aralık kontrolü
        if (Double.isNaN(lat) || Double.isNaN(lng) || Double.isInfinite(lat) || Double.isInfinite(lng)
                || lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) {
            Log.w(TAG, String.format(Locale.US, "getAddressAndFill called with invalid coords: lat=%s lng=%s", lat, lng));
            target.post(() -> {
                target.setText("");
                Toast.makeText(this, "Konum bilgisi geçersiz.", Toast.LENGTH_SHORT).show();
            });
            return;
        }

        final Locale tr = new Locale("tr", "TR");
        final Geocoder geocoder = new Geocoder(this, tr);

        final String coordText = String.format(Locale.US, "%.6f,%.6f", lat, lng);
        target.post(() -> {
            target.setText("Konum alınıyor...");
            target.setTag(coordText); // koordinatları tag'e koyduk
        });

        if (Build.VERSION.SDK_INT >= 33) {
            geocoder.getFromLocation(lat, lng, 1, new Geocoder.GeocodeListener() {
                @Override
                public void onGeocode(@NonNull List<Address> results) {
                    String formatted = formatAddressFromResults(results, lat, lng);
                    target.post(() -> {
                        target.setText(formatted);
                        target.setTag(coordText);
                    });
                }
                @Override
                public void onError(@NonNull String errorMessage) {
                    target.post(() -> target.setText("Konum bulunamadı"));
                }
            });
        } else {
            new Thread(() -> {
                try {
                    List<Address> res = geocoder.getFromLocation(lat, lng, 1);
                    final String formatted = formatAddressFromResults(res, lat, lng);
                    runOnUiThread(() -> {
                        if (formatted != null) {
                            target.setText(formatted);
                            target.setTag(coordText);
                        } else {
                            target.setText("Konum bilgisi bulunamadı");
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "Geocoder error: " + e.getMessage(), e);
                    runOnUiThread(() -> target.setText("Konum alınamadı"));
                }
            }).start();
        }
    }


    /** Address listesi -> "Ülke, Şehir, Mahalle" formatı döndürür; yoksa koordinatı geri döner */
    @Nullable
    private String formatAddressFromResults(@Nullable List<Address> res, double lat, double lng) {
        if (res == null || res.isEmpty()) {
            // fallback: sadece koordinat
            return String.format(Locale.US, "LangLat: %.6f,%.6f  —  AvatarLoc:%.6f,%.6f", lat, lng, lat, lng);
        }
        Address a = res.get(0);

        // Ülke (country) — getCountryName() veya getCountryCode()
        String country = a.getCountryName();  // örn: Türkiye
        if (country == null) country = a.getCountryCode(); // TR

        // İl / bölge
        String admin = a.getAdminArea();       // örn: Ankara (bazen null)
        // Şehir (locality) daha spesifik olabilir
        String city = a.getLocality();
        if (city == null) city = admin;

        // Mahalle / alt-lokalite
        String neighborhood = a.getSubLocality(); // semt/mahalle
        if (neighborhood == null) neighborhood = a.getThoroughfare(); // sokak/cadde fallback

        // Güvenli null handling
        if (country == null) country = "—";
        if (city == null) city = "—";
        if (neighborhood == null) neighborhood = "—";

        // Sonucu Türkçe etiketlerle döndür
        return String.format(Locale.forLanguageTag("tr-TR"),
                "%s / %s / %s", country, city, neighborhood);
    }

    // İzin sonucunu LocationAssist'e forward et
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        //Harita.onRequestPermissionsResult(this, requestCode, grantResults);
        // Harita.onRequestPermissionsResult(this, requestCode, grantResults); // bunu bırak

        // mevcut satırdan sonra ekle:
        if (requestCode == REQ_LOC_FOR_PLACE) {
            boolean granted = false;
            if (grantResults != null && grantResults.length > 0) {
                for (int r : grantResults) {
                    if (r == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
                }
            }
            if (granted) {
                EditText target = deref(this);
                if (target != null) {
                    // doğrudan Harita'ya gönder
                    runOnUiThread(() -> Harita.askAndFill(IyeActivity.this, target));
                } else {
                    // hedef yoksa isteğe bağlı: kısa bildirim
                    Toast.makeText(this, "Konum için izin verildi.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Konum izni gereklidir.", Toast.LENGTH_SHORT).show();
            }
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