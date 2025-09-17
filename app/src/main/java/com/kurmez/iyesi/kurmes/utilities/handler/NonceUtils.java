package com.kurmez.iyesi.kurmes.utilities.handler;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Play Integrity için güvenli nonce üretimi yardımcıları.
 * - Varsayılan: 32 byte (256 bit) rastgele → Base64 URL-safe, NO_WRAP
 * - Minimum: 16 byte (Base64 ÖNCESİ)
 * - Maksimum: 500 byte (Play Integrity pratik üst sınırına yakın tut)
 */
public final class NonceUtils {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int DEFAULT_LEN = 32; // 256-bit tavsiye
    private static final int MIN_LEN = 16;
    private static final int MAX_LEN = 500;

    private NonceUtils() { /* no-op */ }

    /** 32-byte rastgele nonce (B64 URL-safe, NO_WRAP). */
    public static String newNonceB64Url() {
        return newNonceB64Url(DEFAULT_LEN);
    }

    /**
     * İstenen uzunlukta rastgele nonce (B64 URL-safe, NO_WRAP).
     * Uzunluk, [16, 500] aralığına sıkıştırılır.
     */
    public static String newNonceB64Url(int bytes) {
        int n = clamp(bytes, MIN_LEN, MAX_LEN);
        byte[] buf = new byte[n];
        RNG.nextBytes(buf);
        return bytesToB64Url(buf);
    }

    /**
     * Bağlamsal (context-bound) nonce.
     *  - 16 byte rastgele + 16 byte context karması (SHA-256 ilk 16) = 32 byte.
     *  - Tek kullanımlık olması için sisteme milis eklenir.
     *  - Çıkış: B64 URL-safe, NO_WRAP.
     */
    public static String newContextBoundNonceB64Url(String contextHint) {
        byte[] rnd = new byte[16];
        RNG.nextBytes(rnd);

        byte[] ctx = sha256((contextHint == null ? "" : contextHint)
                + "|" + System.currentTimeMillis());

        byte[] out = new byte[32];
        System.arraycopy(rnd, 0, out, 0, 16);
        System.arraycopy(ctx, 0, out, 16, 16); // SHA-256'nın ilk 16 baytı

        return bytesToB64Url(out);
    }

    /** Ham baytlardan Base64 URL-safe (NO_WRAP) üretir. */
    public static String bytesToB64Url(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    /** Base64 URL-safe (NO_WRAP) string'i ham baytlara çevirir. */
    public static byte[] b64UrlToBytes(String b64url) {
        return Base64.decode(b64url, Base64.URL_SAFE | Base64.NO_WRAP);
    }

    // ---- private helpers ----

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static byte[] sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Çok nadir: SHA-256 mevcut değilse rastgele 32 byte dön.
            Log.w("SHA-256 not present falling back to 32b random...",s);
            byte[] fallback = new byte[32];
            RNG.nextBytes(fallback);
            return fallback;
        }
    }
}
