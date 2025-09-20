package com.kurmez.iyesi.kayra.appCheck;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * NonceUtil
 * - Üretim: en az 32 bayt, URL-safe Base64, padding yok.
 * - İsteğe bağlı kullanıcı/cihaz bağlamını karmalayabilir; yoksa yalnız rastgele + zaman.
 */
public final class NonceUtil {
    private static final SecureRandom RNG = new SecureRandom();

    /** En az 32 baytlık nonce üretir; URL-safe Base64 (padding yok) döner. */
    public static String nextNonce(@Nullable String userId) {
        try {
            byte[] rnd = new byte[32]; // >=16 şartı için güvenli
            RNG.nextBytes(rnd);

            long ts = System.currentTimeMillis();

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(rnd);
            sha.update(longToBytes(ts));
            if (userId != null && !userId.isEmpty()) {
                sha.update(userId.getBytes(StandardCharsets.UTF_8));
            }
            byte[] digest = sha.digest(); // 32 byte

            // URL-safe, no-wrap, no-padding:
            return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Throwable t) {
            // Çok istisnai: doğrudan rastgele 32 baytı Base64'le
            byte[] fallback = new byte[32];
            RNG.nextBytes(fallback);
            return Base64.encodeToString(fallback, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }
    }

    /** İstenen bayt uzunluğunda rastgele nonce üretir; min 16 bayt önerilir. */
    public static String randomNonceBase64Url(int numBytes) {
        int n = Math.max(16, numBytes);
        byte[] rnd = new byte[n];
        RNG.nextBytes(rnd);
        return Base64.encodeToString(rnd, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] longToBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    private NonceUtil() {}
}