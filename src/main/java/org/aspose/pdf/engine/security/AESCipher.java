package org.aspose.pdf.engine.security;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES cipher for PDF encryption and decryption — CBC mode.
 * <p>
 * AES-128 (V=4, AESV2): 16-byte key, IV = first 16 bytes of data.
 * AES-256 (V=5, R=6, AESV3): 32-byte key, IV = first 16 bytes of data.
 * </p>
 * <p>
 * Per ISO 32000-1:2008 §7.6.2, the initialization vector is a 16-byte random
 * string stored as the first 16 bytes of the encrypted stream or string.
 * </p>
 */
public final class AESCipher {

    private static final SecureRandom RANDOM = new SecureRandom();

    private AESCipher() {}

    /**
     * Encrypts data using AES-CBC with PKCS5 padding and a random IV.
     * The result is {@code IV(16) + ciphertext} — the format expected by
     * {@link #decrypt(byte[], byte[])}.
     *
     * @param key  16 or 32 byte AES key
     * @param data plaintext data (may be empty)
     * @return IV-prefixed ciphertext (32 bytes minimum: 16-byte IV + at least
     *         one 16-byte PKCS#7-padded block, even for empty input — Adobe
     *         Reader rejects /Length 0 encrypted streams)
     */
    public static byte[] encrypt(byte[] key, byte[] data) {
        if (data == null) data = new byte[0];
        try {
            byte[] iv = new byte[16];
            RANDOM.nextBytes(iv);

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] cipherText = cipher.doFinal(data);

            // Prepend IV
            byte[] result = new byte[16 + cipherText.length];
            System.arraycopy(iv, 0, result, 0, 16);
            System.arraycopy(cipherText, 0, result, 16, cipherText.length);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encrypts data using AES-CBC with an explicit IV and no padding.
     * Used for R=6 key wrapping (OE, UE) where data is already block-aligned.
     *
     * @param key        16 or 32 byte AES key
     * @param iv         16-byte initialization vector
     * @param plaintext  data to encrypt (must be a multiple of 16 bytes)
     * @return ciphertext (same length as plaintext, no IV prepended)
     */
    public static byte[] encryptWithIV(byte[] key, byte[] iv, byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) return new byte[0];
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption with IV failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts AES-CBC encrypted data.
     *
     * @param key  16 or 32 byte key
     * @param data encrypted data (first 16 bytes = IV)
     * @return decrypted data
     */
    public static byte[] decrypt(byte[] key, byte[] data) {
        if (data == null || data.length < 16) return new byte[0];
        try {
            byte[] iv = Arrays.copyOfRange(data, 0, 16);
            byte[] cipherText = Arrays.copyOfRange(data, 16, data.length);
            if (cipherText.length == 0) return new byte[0];

            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);

            // Try with PKCS5Padding first
            try {
                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
                return cipher.doFinal(cipherText);
            } catch (Exception e) {
                // Fallback: NoPadding + manual unpad
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
                byte[] result = cipher.doFinal(cipherText);
                return removePadding(result);
            }
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * Decrypts with explicit IV (for R=6 key recovery where IV is all zeros).
     */
    public static byte[] decryptWithIV(byte[] key, byte[] iv, byte[] cipherText) {
        if (cipherText == null || cipherText.length == 0) return new byte[0];
        try {
            SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption with IV failed: " + e.getMessage(), e);
        }
    }

    private static byte[] removePadding(byte[] data) {
        if (data.length == 0) return data;
        int pad = data[data.length - 1] & 0xFF;
        if (pad > 0 && pad <= 16 && pad <= data.length) {
            // Verify all padding bytes are the same
            boolean valid = true;
            for (int i = data.length - pad; i < data.length; i++) {
                if ((data[i] & 0xFF) != pad) { valid = false; break; }
            }
            if (valid) {
                return Arrays.copyOfRange(data, 0, data.length - pad);
            }
        }
        return data;
    }
}
