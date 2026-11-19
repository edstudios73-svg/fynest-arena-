package gh.edu.gctu.smartattend.security;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Cryptographic primitives. All randomness comes from SecureRandom. */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();

    private Crypto() {}

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    /** URL-safe random token with the given number of bytes of entropy. */
    public static String randomToken(int bytes) {
        return b64url(randomBytes(bytes));
    }

    /** Uniformly distributed six digit code (000000-999999). */
    public static String sixDigitCode() {
        return String.format("%06d", RNG.nextInt(1_000_000));
    }

    public static int randomInt(int bound) {
        return RNG.nextInt(bound);
    }

    public static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] fromB64url(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    public static byte[] sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    public record Sealed(byte[] iv, byte[] ciphertext) {}

    public static Sealed aesGcmEncrypt(byte[] key, byte[] plaintext, byte[] aad) {
        try {
            byte[] iv = randomBytes(12);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            return new Sealed(iv, c.doFinal(plaintext));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] ciphertext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to decrypt protected data", e);
        }
    }
}
