package com.openaiapi.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class PayloadCrypto {

    public static final String PREFIX = "enc1:";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final Base64.Encoder B64 =
            Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public PayloadCrypto(String transportKey) {
        if (transportKey == null || transportKey.isBlank()) {
            throw new IllegalArgumentException("transportKey is required");
        }
        this.key = new SecretKeySpec(sha256(transportKey), "AES");
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return PREFIX + B64.encodeToString(packed);
        } catch (Exception ex) {
            throw new IllegalStateException("seal failed: " + ex.getMessage(), ex);
        }
    }

    public String unseal(String sealed) {
        if (sealed == null || !sealed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("missing " + PREFIX + " prefix");
        }
        try {
            byte[] packed = B64D.decode(sealed.substring(PREFIX.length()).trim());
            if (packed.length < IV_LEN + 16) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(packed, 0, iv, 0, IV_LEN);
            byte[] ct = new byte[packed.length - IV_LEN];
            System.arraycopy(packed, IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("unseal failed: " + ex.getMessage(), ex);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
