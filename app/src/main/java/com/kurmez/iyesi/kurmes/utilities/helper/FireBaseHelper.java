package com.kurmez.iyesi.kurmes.utilities.helper;

import static com.kurmez.iyesi.kurmes.utilities.helper.JsonHelper.toAsciiRole;

import android.app.Activity;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.appcheck.AppCheckToken;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GetTokenResult;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

import okhttp3.Headers;

public class FireBaseHelper {
    private static String deviceId;
    private String TAG = "FirebaseHelper";
    @RequiresApi(api = Build.VERSION_CODES.N)

    public CFHelper cf;
    public static void fetchUserClaims(Activity activity, @NonNull Consumer<Map<String, Object>> callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || Build.VERSION_CODES.N >= Build.VERSION.SDK_INT || user.isAnonymous()) return;

        user.getIdToken(true)
                .addOnSuccessListener(result -> {
                    Map<String, Object> claims = result.getClaims();
                    callback.accept(claims);
                })
                .addOnFailureListener(e -> Log.e("CustomClaims", "Token alınamadı", e));
    }
    public static Object customClaims(FirebaseUser user){
        if (user != null) {
            // true => token’ı yenile, claim güncellemeleri hemen gelsin
            user.getIdToken(true).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    GetTokenResult tok = task.getResult();
                    Map<String, Object> claims = tok.getClaims();
                    Object role = claims.get("role");   // örn. "Iye", "Ulgen" vb.
                    // ... kullan
                } else {
                    Exception e = task.getException();
                    // hata ele al
                }
            });
        }
        return null;
    }
    static class Tokens {
        final String idToken;
        @Nullable
        final String appCheckToken;
        Tokens(String idToken, @Nullable String appCheckToken) {
            this.idToken = idToken; this.appCheckToken = appCheckToken;
        }
    }//------------------------------------------------------------- Kimlik / Token
    private static volatile @Nullable String userRole;//--------------------------------------------------- İstemci tarafında saklanan rol bilgisi (sunucudan getRole ile çekilir).

    static Tokens refreshTokensBlocking() throws Exception {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        FirebaseUser user = auth.getCurrentUser();
        FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
        if (user == null) throw new IllegalStateException("Not authenticated");

        // Force refresh for every call
        String idTok = Tasks.await(user.getIdToken(true)).getToken();
        if (idTok == null || idTok.isEmpty()) throw new IllegalStateException("Empty ID token");

        String appCheckTok = null;
        try {
            AppCheckToken t = Tasks.await(appCheck.getAppCheckToken(false));
            if (t != null && t.getToken() != null && !t.getToken().isEmpty()) {
                appCheckTok = t.getToken();
            }
        } catch (Exception ignore) {
            // App Check zorunlu değilse sessiz geç
        }
        return new Tokens(idTok, appCheckTok);
    }//-------------------------- Her çağrıda taze ID token ve (varsa) App Check token al.
    @RequiresApi(api = Build.VERSION_CODES.N)
    public static Headers buildAuthHeaders(@NonNull Tokens t) {
        Headers.Builder hb = new Headers.Builder()
                // Sunucu çoğunlukla Authorization: Bearer <ID_TOKEN> bekler
                .add("Authorization", "Bearer " + t.idToken)
                // Bazı yardımcılar X-Firebase-Authorization da kabul ediyor
                .add("X-Firebase-Authorization", "Bearer " + t.idToken);

        if (t.appCheckToken != null) {
            hb.add("X-Firebase-AppCheck", t.appCheckToken);
        }

        // İstemci tarafı gözlem için rol header’ı (ASCII zorunluluğu!)
        if (userRole != null && !userRole.isEmpty()) {
            String asciiRole = toAsciiRole(userRole);
            if (asciiRole != null) hb.add("X-User-Role", asciiRole);
        }

        if (deviceId != null && !deviceId.isEmpty()) {
            hb.add("X-Device-Id", deviceId);
        }

        return hb.build();
    }


    private static String getCustomClaims(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length >= 2) {
                String payload = parts[1];
                byte[] decoded = Base64.decode(payload, Base64.URL_SAFE);
                return new String(decoded, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
    public interface PasswordChangeCallback {
        void onSuccess();
        void onFailure(Exception e);
    }


    /**
     * Mevcut oturum açmış kullanıcı için önce yeniden kimlik doğrulama yapar, sonra şifreyi günceller.
     * @param oldPassword mevcut (eski) şifre
     * @param newPassword yeni şifre
     * @param callback sonuç bildirimi için çağrılacak geri çağırma
     */
    public static void changePassword(@NonNull final String oldPassword,
                                      @NonNull final String newPassword,
                                      @NonNull final PasswordChangeCallback callback) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            callback.onFailure(new IllegalStateException("Kullanıcı oturumu yok."));
            return;
        }


        String email = user.getEmail();
        if (email == null || email.isEmpty()) {
            callback.onFailure(new IllegalStateException("Kullanıcı e-posta bilgisi bulunamadı."));
            return;
        }


        AuthCredential credential = EmailAuthProvider.getCredential(email, oldPassword);
        user.reauthenticate(credential).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull Task<Void> task) {
                if (task.isSuccessful()) {
                    user.updatePassword(newPassword).addOnCompleteListener(new OnCompleteListener<Void>() {
                        @Override
                        public void onComplete(@NonNull Task<Void> task2) {
                            if (task2.isSuccessful()) {
                                callback.onSuccess();
                            } else {
                                callback.onFailure(task2.getException());
                            }
                        }
                    });
                } else {
// Yeniden kimlik doğrulama başarısız
                    callback.onFailure(task.getException());
                }
            }
        });
    }
    // ----------------------------- TOKEN/HEADER UTILS -----------------------------
    // Eğer interceptor'ların zaten bunu yapıyorsa, aşağıdakiler ek işlem gerektirmez.
    // Bu örnekte interceptor'lar üzerinden ilerlenir (FirebaseHeadersInterceptor).
    public static void getTokens(@NonNull TokensCallback cb, @NonNull ErrorCallback onError) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            onError.onError(new IllegalStateException("No FirebaseUser"));
            return;
        }
        user.getIdToken(true).addOnSuccessListener(tokenResult -> {
            final String idToken = tokenResult.getToken();
            FirebaseAppCheck.getInstance().getAppCheckToken(true)
                    .addOnSuccessListener(appCheck -> {
                        String appToken = (appCheck != null) ? appCheck.getToken() : null;
                        cb.onReady(idToken, appToken);
                    })
                    .addOnFailureListener(e -> cb.onReady(idToken, null));
        }).addOnFailureListener(onError::onError);
    }//Firebase ID token + App Check token'ı callback ile verir.
    public static interface TokensCallback {
        void onReady(@NonNull String idToken, @Nullable String appCheckToken);
    }
    public static interface ErrorCallback {
        void onError(@NonNull Throwable t);
    }

}