package com.kurmez.iyesi.kayra;

import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class NonceUtil {
    private static final SecureRandom RNG = new SecureRandom();

    public static String nextNonce(@Nullable String userId) {
        byte[] rnd = new byte[48];
        RNG.nextBytes(rnd);
        long ts = System.currentTimeMillis();

        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new RuntimeException(e); }

        sha.update(rnd);
        sha.update(longToBytes(ts));
        if (userId != null) sha.update(userId.getBytes(StandardCharsets.UTF_8));

        byte[] digest = sha.digest();
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] longToBytes(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }
}

