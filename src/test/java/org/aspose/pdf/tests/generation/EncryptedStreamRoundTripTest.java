package org.aspose.pdf.tests.generation;

import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.forms.RadioButtonField;
import org.aspose.pdf.forms.TextBoxField;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug Q (defensive guard) + Bug R — every stream in an encrypted output must
 * decrypt cleanly under the file key. Empty streams ({@code /Length 0}) have
 * no IV under AES and are rejected by Adobe Reader; the writer must either
 * skip them or emit a proper IV+padding-block (32 bytes).
 *
 * <p>These tests open the saved file as raw bytes, derive the file key from
 * the password using {@link org.aspose.pdf.engine.security.PDFKeyDerivation},
 * and walk every {@code N G obj <<...>> stream ... endstream} block under
 * AES-256-CBC. Any stream that fails PKCS#7 validation or has {@code /Length 0}
 * triggers a test failure.</p>
 */
class EncryptedStreamRoundTripTest {

    @TempDir Path tempDir;

    private static byte[] deriveFileKey(byte[] pdfBytes, String password) throws Exception {
        org.aspose.pdf.engine.parser.PDFParser parser =
                new org.aspose.pdf.engine.parser.PDFParser(
                        org.aspose.pdf.engine.io.RandomAccessReader.fromBytes(pdfBytes));
        parser.parse();
        COSDictionary trailer = parser.getTrailer();
        COSBase encRef = trailer.get(COSName.of("Encrypt"));
        COSBase enc = parser.resolveReference(encRef);
        org.aspose.pdf.engine.security.PDFEncryptionDict encDict =
                new org.aspose.pdf.engine.security.PDFEncryptionDict((COSDictionary) enc);
        return org.aspose.pdf.engine.security.PDFKeyDerivation
                .computeEncryptionKeyR6User(password.getBytes(StandardCharsets.UTF_8), encDict);
    }

    /** Walks every {@code obj << ... >> stream ... endstream} block and verifies AES-256-CBC
     *  decryption produces valid PKCS#7 padding. Returns a list of (obj#, reason) failures
     *  and a count of empty streams. */
    private static class StreamAudit {
        final List<int[]> badPkcs7 = new ArrayList<>();
        final List<Integer> empty = new ArrayList<>();
        int okCount = 0;
    }

    private static final Pattern OBJ_RE = Pattern.compile(
            "(\\d+) (\\d+) obj\\s*(.*?)\\s*endobj", Pattern.DOTALL);
    private static final Pattern LEN_RE = Pattern.compile("/Length\\s+(\\d+)");

    private static StreamAudit auditStreams(byte[] pdfBytes, byte[] fileKey) throws Exception {
        StreamAudit res = new StreamAudit();
        String body = new String(pdfBytes, StandardCharsets.ISO_8859_1);
        Matcher om = OBJ_RE.matcher(body);
        while (om.find()) {
            int objNum = Integer.parseInt(om.group(1));
            String objBody = om.group(3);
            int streamIdx = objBody.indexOf("stream\r\n");
            int prefixLen = 8;
            if (streamIdx < 0) {
                streamIdx = objBody.indexOf("stream\n");
                prefixLen = 7;
            }
            if (streamIdx < 0) continue;
            String dictPart = objBody.substring(0, streamIdx);
            Matcher lm = LEN_RE.matcher(dictPart);
            if (!lm.find()) continue;
            int length = Integer.parseInt(lm.group(1));
            int dataStart = om.start(3) + streamIdx + prefixLen;
            if (length == 0) {
                res.empty.add(objNum);
                continue;
            }
            // Misaligned: AES-CBC ciphertext = IV(16) + ciphertext multiple of 16
            if (length < 16 || (length - 16) % 16 != 0) {
                res.badPkcs7.add(new int[]{objNum, length, -1});
                continue;
            }
            byte[] sb = new byte[length];
            System.arraycopy(pdfBytes, dataStart, sb, 0, length);
            byte[] iv = new byte[16];
            byte[] ct = new byte[length - 16];
            System.arraycopy(sb, 0, iv, 0, 16);
            System.arraycopy(sb, 16, ct, 0, ct.length);
            SecretKeySpec ks = new SecretKeySpec(fileKey, "AES");
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, ks, new IvParameterSpec(iv));
            byte[] pt = c.doFinal(ct);
            int pad = pt[pt.length - 1] & 0xFF;
            boolean valid = pad >= 1 && pad <= 16 && pad <= pt.length;
            if (valid) {
                for (int i = pt.length - pad; i < pt.length; i++) {
                    if ((pt[i] & 0xFF) != pad) { valid = false; break; }
                }
            }
            if (!valid) {
                res.badPkcs7.add(new int[]{objNum, length, pad});
            } else {
                res.okCount++;
            }
        }
        return res;
    }

    private static void assertCleanAudit(byte[] pdfBytes, String password) throws Exception {
        byte[] key = deriveFileKey(pdfBytes, password);
        StreamAudit a = auditStreams(pdfBytes, key);
        StringBuilder msg = new StringBuilder();
        if (!a.empty.isEmpty()) {
            msg.append("empty streams (Adobe rejects /Length 0 under encryption): ");
            msg.append(a.empty).append('\n');
        }
        if (!a.badPkcs7.isEmpty()) {
            msg.append("streams that fail AES-CBC + PKCS#7 decryption:\n");
            for (int[] b : a.badPkcs7) {
                msg.append("  obj ").append(b[0]).append(": len=").append(b[1]).append(" pad=").append(b[2]).append('\n');
            }
        }
        assertTrue(a.empty.isEmpty() && a.badPkcs7.isEmpty(),
                () -> msg.toString() + "OK=" + a.okCount);
    }

    @Test
    @DisplayName("appendToContentStream under AES-256: every content stream decrypts cleanly")
    void appendToContentStream_underAESv3_eachStreamDecryptsCleanly() throws Exception {
        Path out = tempDir.resolve("append-aes.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            for (int i = 0; i < 30; i++) {
                String payload = String.format("q 1 0 0 1 100 %d cm Q\n", 50 + i * 5);
                page.appendToContentStream(payload.getBytes(StandardCharsets.ISO_8859_1));
            }
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertCleanAudit(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("Single-save encrypted (no prior plain save): every stream decrypts cleanly")
    void singleSaveEncrypted_eachStreamDecryptsCleanly() throws Exception {
        Path out = tempDir.resolve("single-aes.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("Hello encryption");
            tf.setPosition(new Position(100, 700));
            new TextBuilder(page).appendText(tf);
            TextBoxField tb = new TextBoxField(page, new Rectangle(100, 600, 300, 620));
            tb.setPartialName("tb");
            tb.setValue("text");
            doc.getForm().add(tb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertCleanAudit(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("Save-plain → encrypt → save: every stream in the encrypted file decrypts cleanly")
    void twoSaves_plainThenEncrypted_allStreamsDecryptCleanly() throws Exception {
        Path plain = tempDir.resolve("plain.pdf");
        Path enc = tempDir.resolve("enc.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("Hello");
            tf.setPosition(new Position(100, 700));
            new TextBuilder(page).appendText(tf);
            doc.save(plain.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        assertCleanAudit(Files.readAllBytes(enc), "pw");
    }

    @Test
    @DisplayName("RadioButtonField.addOption under encryption: no /Length 0 appearance streams")
    void radioButtonOption_underEncryption_hasNoEmptyAppearanceStreams() throws Exception {
        Path out = tempDir.resolve("radio-aes.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            RadioButtonField rb = new RadioButtonField(page);
            rb.setPartialName("plan");
            rb.addOption("Free",       new Rectangle(100, 700, 114, 714));
            rb.addOption("Pro",        new Rectangle(180, 700, 194, 714));
            rb.addOption("Enterprise", new Rectangle(260, 700, 274, 714));
            doc.getForm().add(rb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertCleanAudit(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("Defensive: empty plaintext under AES still emits a 32-byte ciphertext stream")
    void emptyPayloadUnderAES_emitsThirtyTwoBytes() {
        // The PDFEncryptor / AESCipher must never return /Length 0 for AES paths,
        // otherwise Adobe-strict readers reject the document. 32 bytes = 16 IV +
        // 16 PKCS#7-padded all-zero-length plaintext block.
        byte[] key = new byte[32];
        for (int i = 0; i < 32; i++) key[i] = (byte) i;
        byte[] result = org.aspose.pdf.engine.security.AESCipher.encrypt(key, new byte[0]);
        assertEquals(32, result.length,
                "AES-CBC of empty plaintext must produce a 16-byte IV + 16-byte padding block");
        byte[] result2 = org.aspose.pdf.engine.security.AESCipher.encrypt(key, null);
        assertEquals(32, result2.length,
                "AES-CBC of null plaintext must also produce a 16-byte IV + 16-byte padding block");
    }

    @Test
    @DisplayName("Appended then appended-again: both streams decrypt cleanly")
    void appendThenAppend_secondStreamDecryptsCleanly() throws Exception {
        Path out = tempDir.resolve("twice.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.appendToContentStream("q 1 0 0 1 10 10 cm Q\n".getBytes(StandardCharsets.ISO_8859_1));
            page.appendToContentStream("q 1 0 0 1 20 20 cm Q\n".getBytes(StandardCharsets.ISO_8859_1));
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertCleanAudit(Files.readAllBytes(out), "pw");
    }
}
