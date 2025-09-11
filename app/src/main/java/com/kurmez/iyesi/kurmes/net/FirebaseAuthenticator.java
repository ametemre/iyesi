package com.kurmez.iyesi.kurmes.net;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import okhttp3.Authenticator;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.Route; /**
 * CFClient — Cloud Functions HTTP istemcisi
 * - Firebase Auth ID token + (varsa) App Check token ekler
 * - Basit GET/POST/PATCH/DELETE yardımcıları
 * - Token alma için 2 farklı API:
 *    1) getTokens(TokenCallback cb, ErrorCallback onError)
 *    2) getTokens(TokensSuccess ok, TokensFailure fail)  // iki-lamdalı overload
 *
 * Ekstralar:
 * - WhereBuilder: listSoulsByFields için where dizesi kurucu
 * - listSoulsByFields: async JSON döndüren yüksek seviye çağrı
 * - withAutoAuth(): Interceptor + Authenticator ile otomatik header ekleyen client
 */

// ------------------------ Support: Authenticator ------------------------
public final class FirebaseAuthenticator implements Authenticator {
    @Override public Request authenticate(Route route, Response response) {
        // Sadece ilk 401’de dene (sonsuz döngüyü engelle)
        if (response.request().header("Authorization") == null) return null;
        if (response.priorResponse() != null) return null;

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return null;
        try {
            String fresh = Tasks.await(user.getIdToken(true)).getToken();
            if (fresh == null) return null;
            return response.request().newBuilder()
                    .header("Authorization", "Bearer " + fresh)
                    .build();
        } catch (Exception e) {
            return null;
        }
    }
}
