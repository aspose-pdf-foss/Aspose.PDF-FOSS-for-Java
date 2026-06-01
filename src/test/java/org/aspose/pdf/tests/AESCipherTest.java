package org.aspose.pdf.tests;

import org.aspose.pdf.engine.security.AESCipher;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AES encrypt/decrypt round-trip in AESCipher.
 * Covers AES-128 and AES-256, various data sizes, padding edge cases,
 * and encryptWithIV/decryptWithIV for R=6 key wrapping.
 */
public class AESCipherTest {

    // ── encrypt() → decrypt() round-trip ──

    @Test
    public void encryptDecryptRoundTrip_emptyData() {
        // Empty plaintext under AES-CBC still produces 16-byte IV + 16-byte
        // PKCS#7 padding block (Adobe Reader rejects /Length 0 encrypted
        // streams). Decryption round-trips back to empty.
        byte[] key = new byte[16];
        byte[] result = AESCipher.encrypt(key, new byte[0]);
        assertEquals(32, result.length, "Empty input must produce IV(16) + padding block(16)");
        byte[] decrypted = AESCipher.decrypt(key, result);
        assertEquals(0, decrypted.length, "Round-trip of empty plaintext must decrypt to empty");
    }

    @Test
    public void encryptDecryptRoundTrip_nullData() {
        // Null is treated as empty: produces a 32-byte IV+padding stream.
        byte[] key = new byte[16];
        byte[] result = AESCipher.encrypt(key, null);
        assertEquals(32, result.length, "Null input must produce IV(16) + padding block(16)");
    }

    @Test
    public void encryptDecryptRoundTrip_15bytes() {
        // 15 bytes = not block-aligned, exercises PKCS5 padding (1 byte pad)
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x42);
        byte[] plaintext = new byte[15];
        for (int i = 0; i < 15; i++) plaintext[i] = (byte) i;

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        assertNotNull(encrypted);
        // encrypted = IV(16) + ciphertext(16) = 32 bytes (15 data + 1 pad → 1 block)
        assertEquals(32, encrypted.length, "IV(16) + 1 padded block(16)");

        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptRoundTrip_16bytes() {
        // 16 bytes = exactly one block, padding adds a full extra block
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xAA);
        byte[] plaintext = new byte[16];
        for (int i = 0; i < 16; i++) plaintext[i] = (byte) (i * 3);

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        // encrypted = IV(16) + ciphertext(32) = 48 bytes (16 data + 16 pad → 2 blocks)
        assertEquals(48, encrypted.length, "IV(16) + 2 blocks(32)");

        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptRoundTrip_31bytes() {
        // 31 bytes: 1 block + 15 bytes → padding adds 1 byte → 2 blocks ciphertext
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xBB);
        byte[] plaintext = new byte[31];
        for (int i = 0; i < 31; i++) plaintext[i] = (byte) (i ^ 0x55);

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        assertEquals(48, encrypted.length, "IV(16) + 2 blocks(32)");

        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptRoundTrip_32bytes() {
        // 32 bytes = 2 blocks, padding adds extra block → 3 blocks ciphertext
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xCC);
        byte[] plaintext = new byte[32];
        for (int i = 0; i < 32; i++) plaintext[i] = (byte) i;

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        assertEquals(64, encrypted.length, "IV(16) + 3 blocks(48)");

        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptDecryptRoundTrip_1000bytes() {
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xDD);
        byte[] plaintext = new byte[1000];
        for (int i = 0; i < 1000; i++) plaintext[i] = (byte) (i % 256);

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        assertTrue(encrypted.length > 1000, "Encrypted should be larger (IV + padding)");

        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    // ── AES-256 round-trip ──

    @Test
    public void encryptDecryptRoundTrip_AES256() {
        byte[] key = new byte[32]; // 256-bit key
        for (int i = 0; i < 32; i++) key[i] = (byte) (i + 1);
        byte[] plaintext = "AES-256 round-trip test data!!!!".getBytes(); // 32 bytes

        byte[] encrypted = AESCipher.encrypt(key, plaintext);
        byte[] decrypted = AESCipher.decrypt(key, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    // ── Random IV: two encrypt calls produce different results ──

    @Test
    public void encryptProducesDifferentIVsEachCall() {
        byte[] key = new byte[16];
        byte[] plaintext = "Same data, different IV".getBytes();

        byte[] encrypted1 = AESCipher.encrypt(key, plaintext);
        byte[] encrypted2 = AESCipher.encrypt(key, plaintext);

        // IVs (first 16 bytes) should differ
        byte[] iv1 = Arrays.copyOfRange(encrypted1, 0, 16);
        byte[] iv2 = Arrays.copyOfRange(encrypted2, 0, 16);
        assertFalse(Arrays.equals(iv1, iv2),
                "Two encrypt calls should produce different random IVs");

        // But both should decrypt to the same plaintext
        assertArrayEquals(plaintext, AESCipher.decrypt(key, encrypted1));
        assertArrayEquals(plaintext, AESCipher.decrypt(key, encrypted2));
    }

    // ── encryptWithIV / decryptWithIV round-trip ──

    @Test
    public void encryptWithIV_decryptWithIV_roundTrip() {
        byte[] key = new byte[32]; // AES-256
        for (int i = 0; i < 32; i++) key[i] = (byte) (i * 7);
        byte[] iv = new byte[16]; // all zeros (used for R=6 OE/UE)
        byte[] plaintext = new byte[32]; // must be block-aligned for NoPadding
        for (int i = 0; i < 32; i++) plaintext[i] = (byte) (i + 100);

        byte[] encrypted = AESCipher.encryptWithIV(key, iv, plaintext);
        assertEquals(32, encrypted.length, "NoPadding: same size as input");

        byte[] decrypted = AESCipher.decryptWithIV(key, iv, encrypted);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void encryptWithIV_knownIV() {
        // Verify that the IV actually affects the ciphertext
        byte[] key = new byte[16];
        byte[] plaintext = new byte[16]; // one block

        byte[] iv1 = new byte[16];
        byte[] iv2 = new byte[16];
        iv2[0] = 1; // different IV

        byte[] enc1 = AESCipher.encryptWithIV(key, iv1, plaintext);
        byte[] enc2 = AESCipher.encryptWithIV(key, iv2, plaintext);

        assertFalse(Arrays.equals(enc1, enc2),
                "Different IVs should produce different ciphertext");

        // Both should still round-trip correctly
        assertArrayEquals(plaintext, AESCipher.decryptWithIV(key, iv1, enc1));
        assertArrayEquals(plaintext, AESCipher.decryptWithIV(key, iv2, enc2));
    }

    @Test
    public void encryptWithIV_emptyData() {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        byte[] result = AESCipher.encryptWithIV(key, iv, new byte[0]);
        assertEquals(0, result.length);
    }

    @Test
    public void encryptWithIV_nullData() {
        byte[] key = new byte[16];
        byte[] iv = new byte[16];
        byte[] result = AESCipher.encryptWithIV(key, iv, null);
        assertEquals(0, result.length);
    }
}
