package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug P — the encryption dictionary's top-level {@code /Length} entry is
 * meaningful only for V ∈ {1, 2, 4} per ISO 32000-1:2008 §7.6.3 Table 20.
 * For V = 5 (AESV3, R = 6) the key size is fixed at 256 bits and the
 * size information lives at {@code /CF /StdCF /Length} (in bytes).
 * Emitting a top-level {@code /Length} for V = 5 produces
 * {@code "Invalid /Length supplied in Encryption dictionary"} warnings
 * in strict readers (Ghostscript, mupdf).
 */
class EncryptDictComplianceTest {

    @TempDir Path tempDir;

    /** Build a tiny 1-page doc, encrypt with the given algorithm, save, reopen,
     *  and return the resolved encryption dictionary from the trailer. */
    private COSDictionary saveAndGetEncryptDict(CryptoAlgorithm algorithm, String label) throws IOException {
        Path out = tempDir.resolve(label + ".pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("encrypt-test");
            tf.setPosition(new Position(100, 700));
            new TextBuilder(page).appendText(tf);
            doc.encrypt("pw", "pw", -1, algorithm);
            doc.save(out.toString());
        }
        Document r = new Document(out.toString(), "pw");
        COSDictionary trailer = r.getTrailer();
        COSBase encRef = trailer.get(COSName.of("Encrypt"));
        assertNotNull(encRef, "trailer must contain an /Encrypt entry for an encrypted PDF");
        // /Encrypt is typically an indirect reference. References parsed
        // from disk don't carry their own resolver; the parser does.
        COSBase enc = r.getParser().resolveReference(encRef);
        assertTrue(enc instanceof COSDictionary,
                "/Encrypt must resolve to a dictionary, got " + enc.getClass().getSimpleName());
        return (COSDictionary) enc;
    }

    @Test
    @DisplayName("AES-256: top-level encrypt dict has /Length 256 (Adobe-required)")
    void aes256Encrypt_topLevelDictHasLength256() throws IOException {
        // Adobe Acrobat requires /Length 256 on the V=5/R=6 encrypt dict
        // per the Adobe Supplement to ISO 32000 (PDF 1.7 Extension Level 3).
        // Without it Acrobat refuses to open with "the document cannot be
        // decrypted", even though every stream is correctly AES-encrypted.
        // ISO 32000-2 Annex K's example dict for AESV3 also includes
        // /Length 256.
        COSDictionary enc = saveAndGetEncryptDict(CryptoAlgorithm.AESx256, "aes256-len");
        assertEquals(256, enc.getInt("Length", -1),
                "AES-256 (V=5) encrypt dict must carry /Length 256 (bits) for Adobe Reader compatibility");
    }

    @Test
    @DisplayName("AES-256: /CF/StdCF/Length is 32 (bytes)")
    void aes256Encrypt_cfStdCFLengthIs32() throws IOException {
        // Reopen so we have access to the parser for resolving references.
        Path out = tempDir.resolve("aes256-cflen.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("encrypt-test");
            tf.setPosition(new Position(100, 700));
            new TextBuilder(page).appendText(tf);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString(), "pw")) {
            COSBase enc = r.getParser().resolveReference(
                    r.getTrailer().get(COSName.of("Encrypt")));
            assertTrue(enc instanceof COSDictionary);
            COSBase cf = r.getParser().resolveReference(((COSDictionary) enc).get(COSName.of("CF")));
            assertTrue(cf instanceof COSDictionary, "/CF must be a dict");
            COSBase stdCf = r.getParser().resolveReference(
                    ((COSDictionary) cf).get(COSName.of("StdCF")));
            assertTrue(stdCf instanceof COSDictionary, "/CF/StdCF must be a dict");
            assertEquals(32, ((COSDictionary) stdCf).getInt("Length", -1),
                    "/CF/StdCF/Length must be 32 (bytes) for AES-256");
        }
    }

    @Test
    @DisplayName("AES-128: top-level encrypt dict has /Length 128")
    void aes128Encrypt_topLevelDictHasLength128() throws IOException {
        COSDictionary enc = saveAndGetEncryptDict(CryptoAlgorithm.AESx128, "aes128-len");
        assertEquals(128, enc.getInt("Length", -1),
                "AES-128 (V=4) encrypt dict must carry /Length 128 (bits) per ISO Table 20");
    }

    @Test
    @DisplayName("RC4-128: top-level encrypt dict has /Length 128")
    void rc4_128Encrypt_topLevelDictHasLength128() throws IOException {
        COSDictionary enc = saveAndGetEncryptDict(CryptoAlgorithm.RC4x128, "rc4_128-len");
        assertEquals(128, enc.getInt("Length", -1),
                "RC4-128 (V=2) encrypt dict must carry /Length 128 (bits)");
    }

    @Test
    @DisplayName("RC4-40: top-level encrypt dict has /Length 40")
    void rc4_40Encrypt_topLevelDictHasLength40() throws IOException {
        COSDictionary enc = saveAndGetEncryptDict(CryptoAlgorithm.RC4x40, "rc4_40-len");
        assertEquals(40, enc.getInt("Length", -1),
                "RC4-40 (V=1) encrypt dict carries /Length 40 (bits) — matches the spec default");
    }
}
