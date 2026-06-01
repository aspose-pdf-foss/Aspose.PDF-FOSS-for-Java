package org.aspose.pdf.tests.generation;

import org.aspose.pdf.CryptoAlgorithm;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.forms.TextBoxField;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug O2 — {@code PDFWriter.collectOrphanStreams} must follow
 * {@link COSObjectReference} nodes to recover and re-register orphan
 * {@code COSStream} targets on the second save of the same {@code Document}.
 *
 * <p>On the first save, every appearance Form XObject and content stream is
 * promoted to indirect form, replacing the inline slot with a
 * {@code COSObjectReference} bound to the just-built objects map. The
 * previous orphan walker only descended into {@code COSDictionary} and
 * {@code COSArray} nodes, so on the second save those references were never
 * followed — and the underlying streams were never copied into the new
 * objects map. The output PDF then contained {@code N G R} references to
 * objects that the writer never emitted.</p>
 *
 * <p>Especially catastrophic in the {@code save → encrypt → save} demo flow:
 * 128 dangling references in every encrypted output.</p>
 */
class SaveTwiceStreamRetentionTest {

    @TempDir Path tempDir;

    private void writeDocWithFormField(Document doc) throws IOException {
        Page page = doc.getPages().add();
        TextFragment tf = new TextFragment("Hello world");
        tf.setPosition(new Position(100, 700));
        new TextBuilder(page).appendText(tf);
        TextBoxField tb = new TextBoxField(page, new Rectangle(100, 600, 300, 620));
        tb.setValue("FieldValue");
        page.getAnnotations().add(tb);
    }

    private int countObjects(byte[] data) {
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?m)^\\d+\\s+\\d+\\s+obj").matcher(iso);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private int countStreams(byte[] data) {
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?<!end)stream[\\r\\n]").matcher(iso);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private Set<Integer> referencedObjectNumbers(byte[] data) {
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("\\b(\\d+)\\s+(\\d+)\\s+R\\b").matcher(iso);
        Set<Integer> out = new HashSet<>();
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }

    private Set<Integer> definedObjectNumbers(byte[] data) {
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("(?m)^(\\d+)\\s+\\d+\\s+obj").matcher(iso);
        Set<Integer> out = new HashSet<>();
        while (m.find()) out.add(Integer.parseInt(m.group(1)));
        return out;
    }

    @Test
    @DisplayName("Save twice without mutation: second file has same object + stream count")
    void saveTwice_plainThenPlain_secondHasSameStreamCount() throws IOException {
        Path a = tempDir.resolve("plain-plain-a.pdf");
        Path b = tempDir.resolve("plain-plain-b.pdf");
        try (Document doc = new Document()) {
            writeDocWithFormField(doc);
            doc.save(a.toString());
            doc.save(b.toString());
        }
        byte[] ba = Files.readAllBytes(a);
        byte[] bb = Files.readAllBytes(b);
        assertEquals(countObjects(ba),  countObjects(bb),  "object count must match");
        assertEquals(countStreams(ba), countStreams(bb), "stream count must match");
    }

    @Test
    @DisplayName("Save plain then encrypt+save: encrypted file has same stream count as plain")
    void saveTwice_plainThenEncrypted_secondHasSameStreamCount() throws IOException {
        Path plain = tempDir.resolve("plain.pdf");
        Path enc   = tempDir.resolve("enc.pdf");
        try (Document doc = new Document()) {
            writeDocWithFormField(doc);
            doc.save(plain.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        byte[] plainBytes = Files.readAllBytes(plain);
        byte[] encBytes   = Files.readAllBytes(enc);
        assertEquals(countStreams(plainBytes), countStreams(encBytes),
                "encrypted second-save must preserve every stream from the plain first save");
    }

    @Test
    @DisplayName("Save plain then encrypt+save: no N G R reference points at a missing object")
    void saveTwiceWithEncrypt_noReferencedObjectIsMissing() throws IOException {
        Path enc = tempDir.resolve("enc-refs.pdf");
        try (Document doc = new Document()) {
            writeDocWithFormField(doc);
            doc.save(tempDir.resolve("plain-pre.pdf").toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        byte[] data = Files.readAllBytes(enc);
        Set<Integer> referenced = referencedObjectNumbers(data);
        Set<Integer> defined    = definedObjectNumbers(data);
        Set<Integer> missing = new TreeSet<>(referenced);
        missing.removeAll(defined);
        assertTrue(missing.isEmpty(),
                "encrypted file must not contain references to undefined objects, missing: " + missing);
    }

    @Test
    @DisplayName("Save plain then encrypt+save: appearance Form XObjects remain present")
    void saveTwiceWithEncrypt_appearanceFormXObjectsArePresent() throws IOException {
        Path enc = tempDir.resolve("enc-ap.pdf");
        try (Document doc = new Document()) {
            writeDocWithFormField(doc);
            doc.save(tempDir.resolve("plain-pre-ap.pdf").toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        try (Document r = new Document(enc.toString(), "pw")) {
            Page page = r.getPages().get(1);
            // Find the widget annotation (the TextBoxField); resolve /AP/N.
            Annotation widget = page.getAnnotations().get(1);
            COSBase ap = widget.getCOSDictionary().get(COSName.of("AP"));
            COSBase apResolved = r.getParser().resolveReference(ap);
            assertTrue(apResolved instanceof COSDictionary, "/AP must resolve to a dict");
            COSBase n = ((COSDictionary) apResolved).get(COSName.of("N"));
            COSBase nResolved = r.getParser().resolveReference(n);
            assertTrue(nResolved instanceof COSStream,
                    "/AP/N must resolve to a COSStream after save→encrypt→save, got "
                            + (nResolved == null ? "null" : nResolved.getClass().getSimpleName()));
            byte[] body = ((COSStream) nResolved).getDecodedData();
            assertTrue(body.length > 0, "/AP/N stream must have non-empty body");
        }
    }

    @Test
    @DisplayName("Save plain then encrypt+save: page /Contents round-trips and contains appended ops")
    void saveTwiceWithEncrypt_contentStreamsRoundTrip() throws IOException {
        Path enc = tempDir.resolve("enc-content.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("Hi");
            tf.setPosition(new Position(100, 700));
            new TextBuilder(page).appendText(tf);
            page.appendToContentStream("q Q\n".getBytes(StandardCharsets.US_ASCII));
            doc.save(tempDir.resolve("plain-pre-content.pdf").toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        try (Document r = new Document(enc.toString(), "pw")) {
            Page page = r.getPages().get(1);
            COSBase contents = page.getCOSDictionary().get(COSName.CONTENTS);
            COSBase resolved = r.getParser().resolveReference(contents);
            // /Contents may be a stream or an array of stream refs.
            StringBuilder all = new StringBuilder();
            if (resolved instanceof COSStream) {
                all.append(new String(((COSStream) resolved).getDecodedData(),
                        StandardCharsets.US_ASCII));
            } else if (resolved instanceof COSArray) {
                COSArray arr = (COSArray) resolved;
                for (int i = 0; i < arr.size(); i++) {
                    COSBase elem = r.getParser().resolveReference(arr.get(i));
                    assertTrue(elem instanceof COSStream,
                            "/Contents[" + i + "] must resolve to a stream");
                    all.append(new String(((COSStream) elem).getDecodedData(),
                            StandardCharsets.US_ASCII));
                }
            } else {
                fail("/Contents must resolve to stream or array, got "
                        + (resolved == null ? "null" : resolved.getClass().getSimpleName()));
            }
            assertTrue(all.toString().contains("q Q"),
                    "appended 'q Q' operators must survive save→encrypt→save round-trip");
        }
    }

    @Test
    @DisplayName("Three saves with encrypt in middle: every output file is structurally complete")
    void saveThriceWithEncryptInMiddle_allSavesProduceCompleteFiles() throws IOException {
        Path s1 = tempDir.resolve("save1.pdf");
        Path s2 = tempDir.resolve("save2-enc.pdf");
        Path s3 = tempDir.resolve("save3-enc.pdf");
        try (Document doc = new Document()) {
            writeDocWithFormField(doc);
            doc.save(s1.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(s2.toString());
            doc.save(s3.toString());
        }
        // Each saved file's referenced object numbers must all be defined.
        for (Path p : new Path[]{s1, s2, s3}) {
            byte[] data = Files.readAllBytes(p);
            Set<Integer> referenced = referencedObjectNumbers(data);
            Set<Integer> defined    = definedObjectNumbers(data);
            Set<Integer> missing = new TreeSet<>(referenced);
            missing.removeAll(defined);
            assertTrue(missing.isEmpty(),
                    p.getFileName() + ": references to undefined objects: " + missing);
        }
    }
}
