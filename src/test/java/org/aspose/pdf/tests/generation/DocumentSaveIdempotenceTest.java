package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.CryptoAlgorithm;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug O — {@link Document#save(String)} must be idempotent: calling it
 * twice on the same {@code Document} must produce two files of equivalent
 * structure. The previous implementation mutated the page dict on the first
 * save (replacing {@code /Contents} and {@code /Resources/Font/*} entries
 * with {@code COSObjectReference} values), so the second save's {@code
 * instanceof COSStream} / {@code instanceof COSDictionary} guards silently
 * skipped the re-promotion and emitted dangling references.
 *
 * <p>Especially severe in the {@code save → encrypt → save} demo flow:
 * encrypted output lost ~80% of its objects and rendered blank in Adobe
 * Reader / Ghostscript / mupdf.</p>
 */
class DocumentSaveIdempotenceTest {

    @TempDir Path tempDir;

    private void writeOnePageWithText(Document doc) throws IOException {
        Page page = doc.getPages().add();
        TextFragment tf = new TextFragment("Hello world");
        tf.setPosition(new Position(100, 700));
        new TextBuilder(page).appendText(tf);
    }

    private int countMatches(byte[] data, Pattern p) {
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher m = p.matcher(iso);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    private int countObjects(byte[] data) {
        // Match "N G obj" at start of a line.
        return countMatches(data, Pattern.compile("(?m)^\\d+\\s+\\d+\\s+obj"));
    }

    private int countStreams(byte[] data) {
        // Match the "stream" keyword followed by CR or LF (excluding "endstream").
        // Use a negative lookbehind to skip the "end" prefix.
        return countMatches(data, Pattern.compile("(?<!end)stream[\\r\\n]"));
    }

    @Test
    @DisplayName("Two consecutive saves of the same doc produce files with identical obj/stream counts")
    void saveTwice_secondFileHasSameObjectCount() throws IOException {
        Path a = tempDir.resolve("a.pdf");
        Path b = tempDir.resolve("b.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(a.toString());
            doc.save(b.toString());
        }
        byte[] ba = Files.readAllBytes(a);
        byte[] bb = Files.readAllBytes(b);
        assertEquals(countObjects(ba), countObjects(bb),
                "second save must contain the same number of objects (was: "
                        + countObjects(ba) + " vs " + countObjects(bb) + ")");
        assertEquals(countStreams(ba), countStreams(bb),
                "second save must contain the same number of streams");
    }

    @Test
    @DisplayName("save → encrypt → save: encrypted file has the same stream count as the plain file")
    void saveTwiceWithEncrypt_secondFileHasSameStreamCount() throws IOException {
        Path plain = tempDir.resolve("plain.pdf");
        Path enc = tempDir.resolve("enc.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(plain.toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        byte[] plainBytes = Files.readAllBytes(plain);
        byte[] encBytes   = Files.readAllBytes(enc);
        assertEquals(countStreams(plainBytes), countStreams(encBytes),
                "encrypted second save must preserve every stream from the plain first save");
    }

    @Test
    @DisplayName("After two saves, the second file's page /Contents resolves to a non-null COSStream")
    void saveTwice_pageContentsResolveToStream() throws IOException {
        Path a = tempDir.resolve("twosave-content.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(a.toString());
            doc.save(a.toString());  // overwrite — exercises the idempotence
        }
        try (Document r = new Document(a.toString())) {
            Page page = r.getPages().get(1);
            COSBase contents = page.getCOSDictionary().get(COSName.CONTENTS);
            assertNotNull(contents, "/Contents must be present");
            COSBase resolved = contents instanceof COSObjectReference
                    ? ((COSObjectReference) contents).dereference()
                    : contents;
            // /Contents may be a stream or an array of streams; either way
            // none of the references may resolve to null/COSNull.
            if (resolved instanceof COSArray) {
                COSArray arr = (COSArray) resolved;
                assertTrue(arr.size() >= 1, "/Contents array must be non-empty");
                for (int i = 0; i < arr.size(); i++) {
                    COSBase elem = arr.get(i);
                    if (elem instanceof COSObjectReference) {
                        elem = ((COSObjectReference) elem).dereference();
                    }
                    assertTrue(elem instanceof COSStream,
                            "/Contents[" + i + "] must resolve to a COSStream, got "
                                    + (elem == null ? "null" : elem.getClass().getSimpleName()));
                }
            } else {
                assertTrue(resolved instanceof COSStream,
                        "/Contents must resolve to a COSStream, got "
                                + resolved.getClass().getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("After two saves, every /Resources/Font/* entry resolves to a COSDictionary")
    void saveTwice_fontResourcesResolveToDicts() throws IOException {
        Path a = tempDir.resolve("twosave-fonts.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(a.toString());
            doc.save(a.toString());
        }
        try (Document r = new Document(a.toString())) {
            Page page = r.getPages().get(1);
            COSBase res = page.getCOSDictionary().get(COSName.RESOURCES);
            if (res instanceof COSObjectReference) {
                res = ((COSObjectReference) res).dereference();
            }
            assertTrue(res instanceof COSDictionary, "/Resources must resolve to a dict");
            COSBase fonts = ((COSDictionary) res).get(COSName.FONT);
            if (fonts instanceof COSObjectReference) {
                fonts = ((COSObjectReference) fonts).dereference();
            }
            assertTrue(fonts instanceof COSDictionary, "/Resources/Font must resolve to a dict");
            COSDictionary fontDict = (COSDictionary) fonts;
            assertFalse(fontDict.keySet().isEmpty(), "test premise: page must have at least one font");
            for (COSName k : fontDict.keySet()) {
                COSBase v = fontDict.get(k.getName());
                if (v instanceof COSObjectReference) {
                    v = ((COSObjectReference) v).dereference();
                }
                assertTrue(v instanceof COSDictionary,
                        "/Resources/Font/" + k.getName() + " must resolve to a dict, got "
                                + (v == null ? "null" : v.getClass().getSimpleName()));
            }
        }
    }

    @Test
    @DisplayName("save → encrypt → save: every in-use xref entry points at a real \"N G obj\" header")
    void saveTwiceWithEncrypt_allXRefEntriesPointAtRealObjects() throws IOException {
        Path enc = tempDir.resolve("enc-xref.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(tempDir.resolve("plain-pre.pdf").toString());
            doc.encrypt("pw", "pw", -1, CryptoAlgorithm.AESx256);
            doc.save(enc.toString());
        }
        byte[] data = Files.readAllBytes(enc);
        // Last startxref in the file.
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        Matcher mStartXref = Pattern.compile("startxref\\s+(\\d+)").matcher(iso);
        int last = -1;
        while (mStartXref.find()) last = Integer.parseInt(mStartXref.group(1));
        assertTrue(last > 0, "startxref must be present");
        // Header "xref\n0 N\n"
        assertEquals('x', (char) data[last]);
        int p = last + 5; // past "xref\n"
        StringBuilder hdr = new StringBuilder();
        while (p < data.length && data[p] != '\n') {
            hdr.append((char) (data[p] & 0xFF));
            p++;
        }
        int count = Integer.parseInt(hdr.toString().trim().split("\\s+")[1]);
        int start = p + 1;
        for (int i = 1; i < count; i++) {  // skip the free head at i=0
            int entryStart = start + 20 * i;
            String entry = new String(data, entryStart, 20, StandardCharsets.ISO_8859_1);
            char flag = entry.charAt(17);
            if (flag != 'n') continue;
            int offset = Integer.parseInt(entry.substring(0, 10));
            int gen = Integer.parseInt(entry.substring(11, 16));
            String expectedHeader = i + " " + gen + " obj";
            String actual = new String(data, offset,
                    Math.min(expectedHeader.length(), data.length - offset), StandardCharsets.ISO_8859_1);
            assertEquals(expectedHeader, actual,
                    "xref entry " + i + " (offset " + offset + ") must point at \""
                            + expectedHeader + "\", got \"" + actual + "\"");
        }
    }

    @Test
    @DisplayName("Three successive saves all produce valid files (idempotence for N≥3)")
    void saveThriceWithMutation_eachSaveProducesCompleteFile() throws IOException {
        Path a = tempDir.resolve("save1.pdf");
        Path b = tempDir.resolve("save2.pdf");
        Path c = tempDir.resolve("save3.pdf");
        try (Document doc = new Document()) {
            writeOnePageWithText(doc);
            doc.save(a.toString());
            // Mutate: add a second page.
            TextFragment tf = new TextFragment("Second page text");
            tf.setPosition(new Position(100, 700));
            Page p2 = doc.getPages().add();
            new TextBuilder(p2).appendText(tf);
            doc.save(b.toString());
            // Mutate again: add a third page.
            TextFragment tf3 = new TextFragment("Third page text");
            tf3.setPosition(new Position(100, 700));
            Page p3 = doc.getPages().add();
            new TextBuilder(p3).appendText(tf3);
            doc.save(c.toString());
        }
        // Each file should reopen and report the expected page count.
        try (Document ra = new Document(a.toString())) { assertEquals(1, ra.getPages().size()); }
        try (Document rb = new Document(b.toString())) { assertEquals(2, rb.getPages().size()); }
        try (Document rc = new Document(c.toString())) { assertEquals(3, rc.getPages().size()); }
        // And the third file's first-page /Contents must still resolve (not
        // dangle to a no-longer-existing key).
        try (Document rc = new Document(c.toString())) {
            COSBase contents = rc.getPages().get(1).getCOSDictionary().get(COSName.CONTENTS);
            if (contents instanceof COSObjectReference) {
                contents = ((COSObjectReference) contents).dereference();
            }
            assertNotNull(contents, "/Contents must resolve");
            assertTrue(contents instanceof COSStream || contents instanceof COSArray,
                    "/Contents must resolve to stream or array, got " + contents.getClass().getSimpleName());
        }
    }
}
