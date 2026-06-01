package org.aspose.pdf.tests;

import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.security.PDFDecryptor;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.aspose.pdf.engine.security.PDFEncryptor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PDFEncryptor: encrypt → PDFDecryptor.decrypt round-trip,
 * per-object key isolation, edge cases.
 */
public class PDFEncryptorTest {

    // ── RC4 round-trip tests ──

    @Test
    public void rc4_roundTrip_shortString() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x42);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] plaintext = "Hello PDF".getBytes();
        byte[] encrypted = encryptor.encrypt(plaintext, 1, 0);
        byte[] decrypted = decryptor.decrypt(encrypted, 1, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void rc4_roundTrip_emptyData() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] encrypted = encryptor.encrypt(new byte[0], 1, 0);
        assertEquals(0, encrypted.length);
        byte[] decrypted = decryptor.decrypt(encrypted, 1, 0);
        assertEquals(0, decrypted.length);
    }

    @Test
    public void rc4_roundTrip_nullData() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        assertNull(encryptor.encrypt(null, 1, 0));
    }

    @Test
    public void rc4_roundTrip_longData() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i + 1);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] plaintext = new byte[1500];
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) (i % 256);

        byte[] encrypted = encryptor.encrypt(plaintext, 5, 0);
        byte[] decrypted = decryptor.decrypt(encrypted, 5, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void rc4_roundTrip_blockAlignedData() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xAA);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        // 16 bytes — block-aligned (doesn't matter for RC4, but consistent test)
        byte[] plaintext = new byte[16];
        for (int i = 0; i < 16; i++) plaintext[i] = (byte) i;

        byte[] encrypted = encryptor.encrypt(plaintext, 3, 0);
        byte[] decrypted = decryptor.decrypt(encrypted, 3, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    // ── AES-128 round-trip tests ──

    @Test
    public void aes128_roundTrip_shortString() {
        PDFEncryptionDict encDict = buildAES128Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x55);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] plaintext = "Hello PDF".getBytes();
        byte[] encrypted = encryptor.encrypt(plaintext, 1, 0);

        // AES adds IV(16) + padding → encrypted should be larger
        assertTrue(encrypted.length > plaintext.length,
                "AES encrypted should be larger (IV + padding)");

        byte[] decrypted = decryptor.decrypt(encrypted, 1, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void aes128_roundTrip_emptyData() {
        // Empty plaintext under AES-128 still produces 32 bytes (IV + padding
        // block). /Length 0 encrypted streams are rejected by Adobe Reader,
        // so the writer must always emit a real IV + ciphertext block.
        PDFEncryptionDict encDict = buildAES128Dict();
        byte[] key = new byte[16];

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] encrypted = encryptor.encrypt(new byte[0], 1, 0);
        assertEquals(32, encrypted.length, "Empty input must produce IV(16) + padding block(16)");
        byte[] decrypted = decryptor.decrypt(encrypted, 1, 0);
        assertEquals(0, decrypted.length);
    }

    @Test
    public void aes128_roundTrip_longData() {
        PDFEncryptionDict encDict = buildAES128Dict();
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) key[i] = (byte) (i * 3);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        byte[] plaintext = new byte[1200];
        for (int i = 0; i < plaintext.length; i++) plaintext[i] = (byte) (i % 256);

        byte[] encrypted = encryptor.encrypt(plaintext, 7, 0);
        byte[] decrypted = decryptor.decrypt(encrypted, 7, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    @Test
    public void aes128_roundTrip_blockAlignedData() {
        PDFEncryptionDict encDict = buildAES128Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0xCC);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);

        // 32 bytes = exactly 2 blocks (PKCS5 adds full pad block)
        byte[] plaintext = new byte[32];
        for (int i = 0; i < 32; i++) plaintext[i] = (byte) i;

        byte[] encrypted = encryptor.encrypt(plaintext, 2, 0);
        // IV(16) + 3 blocks(48) = 64 bytes
        assertEquals(64, encrypted.length, "IV(16) + 2 data blocks + 1 pad block");

        byte[] decrypted = decryptor.decrypt(encrypted, 2, 0);
        assertArrayEquals(plaintext, decrypted);
    }

    // ── Object key isolation ──

    @Test
    public void rc4_differentObjectsDifferentCiphertext() {
        PDFEncryptionDict encDict = buildRC4Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x77);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);

        byte[] plaintext = "Same data for both objects".getBytes();
        byte[] enc1 = encryptor.encrypt(plaintext, 1, 0);
        byte[] enc2 = encryptor.encrypt(plaintext, 2, 0);

        assertFalse(Arrays.equals(enc1, enc2),
                "Different object numbers should produce different ciphertext (per-object key)");

        // But both decrypt correctly with their own object numbers
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);
        assertArrayEquals(plaintext, decryptor.decrypt(enc1, 1, 0));
        assertArrayEquals(plaintext, decryptor.decrypt(enc2, 2, 0));
    }

    @Test
    public void aes128_differentObjectsDifferentCiphertext() {
        PDFEncryptionDict encDict = buildAES128Dict();
        byte[] key = new byte[16];
        Arrays.fill(key, (byte) 0x88);

        PDFEncryptor encryptor = new PDFEncryptor(key, encDict);

        byte[] plaintext = "Same data for both objects".getBytes();
        byte[] enc1 = encryptor.encrypt(plaintext, 1, 0);
        byte[] enc2 = encryptor.encrypt(plaintext, 2, 0);

        // For AES, even same object would differ (random IV), but different objects
        // use different keys too. Just verify both decrypt correctly.
        PDFDecryptor decryptor = new PDFDecryptor(key, encDict);
        assertArrayEquals(plaintext, decryptor.decrypt(enc1, 1, 0));
        assertArrayEquals(plaintext, decryptor.decrypt(enc2, 2, 0));

        // Cross-decryption should fail (wrong object key)
        byte[] crossDecrypt = decryptor.decrypt(enc1, 2, 0);
        assertFalse(Arrays.equals(plaintext, crossDecrypt),
                "Decrypting obj1's data with obj2's key should produce wrong result");
    }

    // ── isActive ──

    @Test
    public void isActive_withKey() {
        PDFEncryptionDict encDict = buildRC4Dict();
        PDFEncryptor encryptor = new PDFEncryptor(new byte[16], encDict);
        assertTrue(encryptor.isActive());
    }

    @Test
    public void isActive_nullKey() {
        PDFEncryptionDict encDict = buildRC4Dict();
        PDFEncryptor encryptor = new PDFEncryptor(null, encDict);
        assertFalse(encryptor.isActive());
    }

    // ── Helpers ──

    /** V=2, R=3 → RC4-128 */
    private PDFEncryptionDict buildRC4Dict() {
        COSDictionary dict = new COSDictionary();
        dict.set(COSName.of("V"), COSInteger.valueOf(2));
        dict.set(COSName.of("R"), COSInteger.valueOf(3));
        dict.set(COSName.of("Length"), COSInteger.valueOf(128));
        return new PDFEncryptionDict(dict);
    }

    /** V=4, R=4 → AES-128 with StdCF/AESV2 */
    private PDFEncryptionDict buildAES128Dict() {
        COSDictionary dict = new COSDictionary();
        dict.set(COSName.of("V"), COSInteger.valueOf(4));
        dict.set(COSName.of("R"), COSInteger.valueOf(4));
        dict.set(COSName.of("Length"), COSInteger.valueOf(128));
        dict.set(COSName.of("StmF"), COSName.of("StdCF"));
        dict.set(COSName.of("StrF"), COSName.of("StdCF"));
        COSDictionary stdCF = new COSDictionary();
        stdCF.set(COSName.of("CFM"), COSName.of("AESV2"));
        COSDictionary cf = new COSDictionary();
        cf.set(COSName.of("StdCF"), stdCF);
        dict.set(COSName.of("CF"), cf);
        return new PDFEncryptionDict(dict);
    }
}
