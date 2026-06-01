package org.aspose.pdf.tests.generation;

import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.aspose.pdf.engine.security.PDFKeyDerivation;
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
 * "Every single stream must decrypt" — strict regression guard against
 * any future change that produces ciphertext Adobe Reader (or any
 * spec-compliant reader) would reject.
 *
 * <p>Walks the saved file's raw bytes, finds every {@code N G obj ...
 * stream ... endstream} block, derives the file key from the password
 * using the library's own {@link PDFKeyDerivation}, and runs AES-256-CBC
 * (or AES-128-CBC for V=4) over the IV-prefixed ciphertext. Any stream
 * that fails PKCS#7 validation, has {@code /Length 0}, or has misaligned
 * length fails the test.</p>
 *
 * <p>This test predates and would have caught Bug R (empty appearance
 * streams), the previous double-encryption regressions, and the
 * Adobe-rejects-due-to-wrong-PDF-version bug — the latter does not
 * affect stream decryption but is caught by
 * {@link #aes256EncryptedFile_headerIsAtLeastPdf17AndDeclaresAdbeExt}.</p>
 */
class EveryStreamDecryptsTest {

    @TempDir Path tempDir;

    // Strict EOL handling per ISO 32000-1 §7.3.8.1: 'stream' is followed by
    // exactly one EOL marker — \r\n or \n alone, NEVER \r alone, and we must
    // not greedily consume further EOL bytes (which may be legitimate IV bytes
    // that happen to be 0x0a/0x0d).
    private static int streamDataStart(byte[] data, int objHeaderOffset) {
        int p = objHeaderOffset;
        // find 'stream'
        while (p < data.length - 6) {
            if (data[p] == 's' && data[p+1] == 't' && data[p+2] == 'r'
                    && data[p+3] == 'e' && data[p+4] == 'a' && data[p+5] == 'm') {
                p += 6;
                // exactly one EOL marker
                if (p < data.length && data[p] == '\r') {
                    p++;
                    if (p < data.length && data[p] == '\n') p++;
                } else if (p < data.length && data[p] == '\n') {
                    p++;
                } // else 'stream' immediately followed by data (lenient)
                return p;
            }
            p++;
        }
        return -1;
    }

    // Scope each match to ONE object — group(3) is the body between 'obj' and 'endobj'.
    private static final Pattern OBJ_RE = Pattern.compile(
            "(\\d+) (\\d+) obj\\s*(.*?)\\s*endobj", Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern LEN_RE = Pattern.compile("/Length\\s+(\\d+)");

    /** Header-only parse to recover the encryption dict, then derive the file key. */
    private static byte[] deriveFileKey(byte[] pdfBytes, String password) throws Exception {
        org.aspose.pdf.engine.parser.PDFParser parser =
                new org.aspose.pdf.engine.parser.PDFParser(
                        org.aspose.pdf.engine.io.RandomAccessReader.fromBytes(pdfBytes));
        parser.parse();
        COSDictionary trailer = parser.getTrailer();
        COSBase encRef = trailer.get(COSName.of("Encrypt"));
        COSBase enc = parser.resolveReference(encRef);
        PDFEncryptionDict encDict = new PDFEncryptionDict((COSDictionary) enc);
        byte[] pwBytes = password.getBytes(StandardCharsets.UTF_8);
        int R = encDict.getR();
        byte[] documentId = null;
        COSBase id = trailer.get(COSName.of("ID"));
        if (id instanceof org.aspose.pdf.engine.cos.COSArray
                && ((org.aspose.pdf.engine.cos.COSArray) id).size() > 0) {
            COSBase first = ((org.aspose.pdf.engine.cos.COSArray) id).get(0);
            if (first instanceof org.aspose.pdf.engine.cos.COSString) {
                documentId = ((org.aspose.pdf.engine.cos.COSString) first).getBytes();
            }
        }
        if (R >= 5) {
            return PDFKeyDerivation.computeEncryptionKeyR6User(pwBytes, encDict);
        }
        return PDFKeyDerivation.computeEncryptionKeyR2R4(pwBytes, encDict, documentId);
    }

    /** Per ISO 32000-1 §7.6.2 Algorithm 1: per-object key = MD5(fileKey || objNum_LE3 || gen_LE2 ||
     *  [salt for AES])[:Math.min(16, n+5)]. */
    private static byte[] perObjectKey(byte[] fileKey, int objNum, int gen, boolean aes) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        md.update(fileKey);
        md.update(new byte[]{
                (byte) (objNum & 0xFF), (byte) ((objNum >> 8) & 0xFF), (byte) ((objNum >> 16) & 0xFF),
                (byte) (gen & 0xFF), (byte) ((gen >> 8) & 0xFF)});
        if (aes) md.update(new byte[]{'s', 'A', 'l', 'T'});
        byte[] h = md.digest();
        int n = Math.min(16, fileKey.length + 5);
        byte[] k = new byte[n];
        System.arraycopy(h, 0, k, 0, n);
        return k;
    }

    private static class Audit {
        int total, ok, empty, broken;
        final List<String> failures = new ArrayList<>();
    }

    private static Audit auditAllStreams(byte[] pdf, String password) throws Exception {
        org.aspose.pdf.engine.parser.PDFParser parser =
                new org.aspose.pdf.engine.parser.PDFParser(
                        org.aspose.pdf.engine.io.RandomAccessReader.fromBytes(pdf));
        parser.parse();
        COSDictionary trailer = parser.getTrailer();
        COSBase encRef = trailer.get(COSName.of("Encrypt"));
        COSBase enc = parser.resolveReference(encRef);
        PDFEncryptionDict encDict = new PDFEncryptionDict((COSDictionary) enc);
        int R = encDict.getR();
        boolean aes = (R == 4 || R == 6);
        byte[] fileKey = deriveFileKey(pdf, password);

        String body = new String(pdf, StandardCharsets.ISO_8859_1);
        Matcher om = OBJ_RE.matcher(body);
        Audit a = new Audit();
        while (om.find()) {
            int objNum = Integer.parseInt(om.group(1));
            int gen = Integer.parseInt(om.group(2));
            String objBody = om.group(3);
            int objStart = om.start(3);
            // Must contain a 'stream' keyword to be a stream object — and within THIS object.
            int relStream = objBody.indexOf("stream");
            if (relStream < 0) continue;
            // Skip ones where 'stream' appears as part of '/StreamFilter' etc. by requiring it
            // immediately after the dict's closing '>>' (with whitespace).
            int relDictEnd = objBody.lastIndexOf(">>", relStream);
            if (relDictEnd < 0) continue;
            // Find /Length inside this object's dict
            String dictPart = objBody.substring(0, relStream);
            Matcher lm = LEN_RE.matcher(dictPart);
            if (!lm.find()) continue;
            int length = Integer.parseInt(lm.group(1));
            int dataStart = streamDataStart(pdf, objStart + relStream);
            a.total++;
            if (length == 0) {
                a.empty++;
                a.failures.add("obj " + objNum + ": EMPTY (Adobe rejects /Length 0)");
                continue;
            }
            if (length < 16 || (length - 16) % 16 != 0) {
                a.broken++;
                a.failures.add("obj " + objNum + ": misaligned len=" + length);
                continue;
            }
            byte[] iv = new byte[16];
            byte[] ct = new byte[length - 16];
            System.arraycopy(pdf, dataStart, iv, 0, 16);
            System.arraycopy(pdf, dataStart + 16, ct, 0, ct.length);
            byte[] key = (R >= 5) ? fileKey : perObjectKey(fileKey, objNum, gen, aes);
            Cipher c = Cipher.getInstance(aes ? "AES/CBC/NoPadding" : "AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            byte[] pt = c.doFinal(ct);
            int pad = pt[pt.length - 1] & 0xFF;
            boolean valid = pad >= 1 && pad <= 16 && pad <= pt.length;
            if (valid) {
                for (int i = pt.length - pad; i < pt.length; i++) {
                    if ((pt[i] & 0xFF) != pad) { valid = false; break; }
                }
            }
            if (!valid) {
                a.broken++;
                a.failures.add("obj " + objNum + ": bad PKCS#7 pad=" + pad + " len=" + length);
            } else {
                a.ok++;
            }
        }
        return a;
    }

    private static void assertEveryStreamDecrypts(byte[] pdf, String password) throws Exception {
        Audit a = auditAllStreams(pdf, password);
        if (!a.failures.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append(a.failures.size()).append(" of ").append(a.total).append(" streams failed:\n");
            for (String f : a.failures) sb.append("  ").append(f).append('\n');
            fail(sb.toString());
        }
        assertTrue(a.total > 0, "no streams found — wrong PDF?");
    }

    /** Build a doc with many appendToContentStream calls + form widgets to
     *  exercise as many encryption-bearing object types as the demo does. */
    private Document buildBusyDoc() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        for (int i = 0; i < 30; i++) {
            String payload = String.format("q 1 0 0 1 50 %d cm Q\n", 100 + i * 5);
            p.appendToContentStream(payload.getBytes(StandardCharsets.ISO_8859_1));
        }
        TextFragment tf = new TextFragment("Hello encrypted world");
        tf.setPosition(new Position(100, 700));
        new TextBuilder(p).appendText(tf);
        TextBoxField tb = new TextBoxField(p, new Rectangle(100, 600, 300, 620));
        tb.setPartialName("tb"); tb.setValue("text");
        doc.getForm().add(tb, 1);
        RadioButtonField rb = new RadioButtonField(p);
        rb.setPartialName("plan");
        rb.addOption("A", new Rectangle(100, 500, 114, 514));
        rb.addOption("B", new Rectangle(180, 500, 194, 514));
        doc.getForm().add(rb, 1);
        return doc;
    }

    @Test
    @DisplayName("save-plain → encrypt(AES-256) → save: every stream decrypts cleanly")
    void twoSaves_plainThenAES256_everyStreamDecryptsCleanly() throws Exception {
        Path plain = tempDir.resolve("plain.pdf");
        Path enc = tempDir.resolve("enc.pdf");
        try (Document doc = buildBusyDoc()) {
            doc.save(plain.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(enc), "pw");
    }

    @Test
    @DisplayName("single-save AES-256 (no prior plain save): every stream decrypts cleanly")
    void singleSaveAES256_everyStreamDecryptsCleanly() throws Exception {
        Path out = tempDir.resolve("single.pdf");
        try (Document doc = buildBusyDoc()) {
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("save-plain → encrypt(AES-128) → save: every stream decrypts cleanly (V=4 path)")
    void twoSavesEncryptedAES128_everyStreamDecryptsCleanly() throws Exception {
        Path plain = tempDir.resolve("plain.pdf");
        Path enc = tempDir.resolve("aes128.pdf");
        try (Document doc = buildBusyDoc()) {
            doc.save(plain.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx128);
            doc.save(enc.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(enc), "pw");
    }

    @Test
    @DisplayName("encrypt(pw1) → save → encrypt(pw2) → save: every stream decrypts under pw2")
    void encryptTwiceWithDifferentPasswords_secondEncryptionWorks() throws Exception {
        Path first = tempDir.resolve("first.pdf");
        Path second = tempDir.resolve("second.pdf");
        try (Document doc = buildBusyDoc()) {
            doc.encrypt("pw1", "pw1", -1, CryptoAlgorithm.AESx256);
            doc.save(first.toString());
            doc.encrypt("pw2", "pw2", -1, CryptoAlgorithm.AESx256);
            doc.save(second.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(second), "pw2");
    }

    @Test
    @DisplayName("AES-256 file declares PDF 1.7 + ADBE ExtensionLevel 3 (Adobe Reader compatibility)")
    void aes256EncryptedFile_headerIsAtLeastPdf17AndDeclaresAdbeExt() throws Exception {
        Path out = tempDir.resolve("aes256.pdf");
        try (Document doc = new Document()) {
            doc.getPages().add();
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        byte[] bytes = Files.readAllBytes(out);
        String header = new String(bytes, 0, Math.min(16, bytes.length), StandardCharsets.ISO_8859_1);
        assertTrue(header.startsWith("%PDF-1.7") || header.startsWith("%PDF-2."),
                "AES-256 requires PDF 1.7+ or PDF 2.0 header, got: " + header.split("\n")[0]);

        org.aspose.pdf.engine.parser.PDFParser parser =
                new org.aspose.pdf.engine.parser.PDFParser(
                        org.aspose.pdf.engine.io.RandomAccessReader.fromBytes(bytes));
        parser.parse();
        COSDictionary trailer = parser.getTrailer();
        COSBase catalog = parser.resolveReference(trailer.get(COSName.ROOT));
        assertTrue(catalog instanceof COSDictionary);
        COSBase ext = ((COSDictionary) catalog).get(COSName.of("Extensions"));
        assertTrue(ext instanceof COSDictionary, "AES-256 catalog must contain /Extensions");
        COSBase adbe = ((COSDictionary) ext).get(COSName.of("ADBE"));
        assertTrue(adbe instanceof COSDictionary, "AES-256 /Extensions must contain /ADBE entry");
        COSBase bv = ((COSDictionary) adbe).get(COSName.of("BaseVersion"));
        COSBase el = ((COSDictionary) adbe).get(COSName.of("ExtensionLevel"));
        assertEquals("1.7", bv instanceof COSName ? ((COSName) bv).getName() : String.valueOf(bv));
        assertEquals(3, el instanceof org.aspose.pdf.engine.cos.COSInteger
                ? ((org.aspose.pdf.engine.cos.COSInteger) el).intValue() : -1);
    }

    private static byte[] tinyJpeg() throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(12, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "JPEG", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("AES-256 with ImageStamp: every stream decrypts (incl. the image XObject)")
    void aes256_withImageStamp_everyStreamDecrypts() throws Exception {
        Path out = tempDir.resolve("stamp.pdf");
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            org.aspose.pdf.ImageStamp stamp =
                    new org.aspose.pdf.ImageStamp(new java.io.ByteArrayInputStream(tinyJpeg()));
            stamp.setXIndent(100); stamp.setYIndent(100);
            stamp.setWidth(200); stamp.setHeight(120);
            p.addStamp(stamp);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("AES-256 with BackgroundArtifact: every stream decrypts")
    void aes256_withBackgroundArtifact_everyStreamDecrypts() throws Exception {
        Path out = tempDir.resolve("bg.pdf");
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            p.getArtifacts().add(new org.aspose.pdf.BackgroundArtifact(
                    org.aspose.pdf.Color.fromRgb(0.5, 0.5, 0.5)));
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("AES-256 with WatermarkArtifact: every stream decrypts")
    void aes256_withWatermarkArtifact_everyStreamDecrypts() throws Exception {
        Path out = tempDir.resolve("wm.pdf");
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            org.aspose.pdf.WatermarkArtifact wm = new org.aspose.pdf.WatermarkArtifact("DRAFT");
            wm.setFont("Helvetica-Bold", 48);
            wm.setRotation(45);
            wm.setOpacity(0.25);
            p.getArtifacts().add(wm);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("AES-256 with TextBoxField appearance stream: every stream decrypts")
    void aes256_withTextBoxField_everyStreamDecrypts() throws Exception {
        Path out = tempDir.resolve("tbf.pdf");
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            TextBoxField tb = new TextBoxField(p, new Rectangle(100, 600, 300, 620));
            tb.setPartialName("tb"); tb.setValue("Hello");
            doc.getForm().add(tb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }

    @Test
    @DisplayName("AES-256 full-demo-equivalent (stamp + 2 artifacts + 3 field types): every stream decrypts")
    void aes256_fullDemoEquivalent_everyStreamDecrypts() throws Exception {
        Path out = tempDir.resolve("full.pdf");
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            for (int i = 0; i < 10; i++) {
                p.appendToContentStream(("q 1 0 0 1 50 " + (100 + i * 5) + " cm Q\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
            }
            TextFragment tf = new TextFragment("Full demo equivalent");
            tf.setPosition(new Position(60, 760));
            new TextBuilder(p).appendText(tf);
            p.getArtifacts().add(new org.aspose.pdf.BackgroundArtifact(
                    org.aspose.pdf.Color.fromRgb(0.96, 0.97, 0.85)));
            org.aspose.pdf.WatermarkArtifact wm = new org.aspose.pdf.WatermarkArtifact("CONFIDENTIAL");
            wm.setFont("Helvetica-Bold", 64); wm.setRotation(45); wm.setOpacity(0.18);
            p.getArtifacts().add(wm);
            org.aspose.pdf.ImageStamp stamp =
                    new org.aspose.pdf.ImageStamp(new java.io.ByteArrayInputStream(tinyJpeg()));
            stamp.setXIndent(380); stamp.setYIndent(560); stamp.setWidth(180); stamp.setHeight(120);
            p.addStamp(stamp);
            TextBoxField tb = new TextBoxField(p, new Rectangle(100, 400, 300, 420));
            tb.setPartialName("name"); tb.setValue("Jane Doe");
            doc.getForm().add(tb, 1);
            org.aspose.pdf.forms.ComboBoxField combo =
                    new org.aspose.pdf.forms.ComboBoxField(p, new Rectangle(100, 360, 300, 380));
            combo.setPartialName("country"); combo.addOption("Germany"); combo.setSelected("Germany");
            doc.getForm().add(combo, 1);
            RadioButtonField rb = new RadioButtonField(p);
            rb.setPartialName("plan");
            rb.addOption("Free", new Rectangle(100, 300, 114, 314));
            rb.addOption("Pro", new Rectangle(180, 300, 194, 314));
            rb.addOption("Enterprise", new Rectangle(260, 300, 274, 314));
            doc.getForm().add(rb, 1);
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(out.toString());
        }
        assertEveryStreamDecrypts(Files.readAllBytes(out), "pw");
    }
}
