package org.aspose.pdf.tests.generation;

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
 * Bugs N1 and N2 — writer-byte-level correctness.
 *
 * <p><strong>N1:</strong> ISO 32000-1:2008 §7.5.4 mandates cross-reference
 * table entries to be exactly 20 bytes (SP CR or SP LF terminator — NOT
 * SP CR LF). Without that, readers that index xref by absolute byte offset
 * (Adobe Reader, Ghostscript, mupdf) land in the wrong record.</p>
 *
 * <p><strong>N2:</strong> ISO 32000-1:2008 §7.3.8.1 — "All streams shall be
 * indirect objects". Inline {@code <<…>> stream…endstream} embedded in
 * parent dictionaries are rejected by strict readers.</p>
 *
 * <p>The locale fix (Bug M) has its own dedicated test in
 * {@code TextBuilderLocaleTest}; the combined smoke test
 * {@link #freshDocumentWithFormFields_passesStrictParse} here verifies all
 * three fixes jointly produce a clean round-trip.</p>
 */
class WriterByteCorrectnessTest {

    @TempDir Path tempDir;

    // ───────────────────────── N1 — xref entry size ──────────────────────────

    /** Build a doc with a few objects so the xref table has &gt;= 4 entries. */
    private Path buildDocWithSeveralObjects(String label) throws IOException {
        Path out = tempDir.resolve(label + ".pdf");
        try (Document doc = new Document()) {
            for (int i = 0; i < 3; i++) {
                Page page = doc.getPages().add();
                // One acroform field per page so we get TextBoxField + widget streams
                page.getAnnotations().add(
                        new TextBoxField(page, new Rectangle(50, 700 - i * 30, 250, 720 - i * 30)));
            }
            doc.save(out.toString());
        }
        return out;
    }

    /** Parses {@code startxref} at the end of {@code data} and returns its offset. */
    private static int parseStartXrefOffset(byte[] data) {
        String tail = new String(data, Math.max(0, data.length - 4096), Math.min(4096, data.length),
                StandardCharsets.ISO_8859_1);
        Matcher m = Pattern.compile("startxref\\s+(\\d+)").matcher(tail);
        int last = -1;
        while (m.find()) last = Integer.parseInt(m.group(1));
        if (last < 0) throw new AssertionError("no startxref found");
        return last;
    }

    /** Parses the {@code "0 N\n"} subsection header at the xref position; returns count. */
    private static int parseXrefSubsectionCount(byte[] data, int xrefOff) {
        // Expect: "xref\n0 N\n"
        assertArrayEqualsAt(data, xrefOff, "xref\n");
        int p = xrefOff + 5;
        StringBuilder n = new StringBuilder();
        while (p < data.length && data[p] != '\n') {
            n.append((char) (data[p] & 0xFF));
            p++;
        }
        String[] parts = n.toString().split("\\s+");
        return Integer.parseInt(parts[1]);
    }

    /** Returns the byte offset where the first xref entry begins. */
    private static int xrefEntriesStart(byte[] data, int xrefOff) {
        int p = xrefOff + 5; // past "xref\n"
        while (p < data.length && data[p] != '\n') p++;
        return p + 1;
    }

    private static void assertArrayEqualsAt(byte[] data, int off, String expected) {
        byte[] exp = expected.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < exp.length; i++) {
            assertEquals(exp[i], data[off + i],
                    "byte mismatch at off " + (off + i) + " — expected '"
                            + expected + "' got: " + new String(data, off, exp.length, StandardCharsets.ISO_8859_1));
        }
    }

    @Test
    @DisplayName("N1 — every xref entry occupies exactly 20 bytes")
    void xrefEntries_areExactly20BytesEach() throws IOException {
        byte[] data = Files.readAllBytes(buildDocWithSeveralObjects("xref-20b"));
        int xrefOff = parseStartXrefOffset(data);
        int count = parseXrefSubsectionCount(data, xrefOff);
        assertTrue(count >= 4, "test premise: expected at least 4 xref entries, got " + count);
        int start = xrefEntriesStart(data, xrefOff);
        for (int i = 0; i < count; i++) {
            int entryStart = start + 20 * i;
            // Per ISO 32000-1:2008 §7.5.4 Table 18: byte 18 is the SP separator
            // between the n/f flag (byte 17) and the EOL byte (byte 19).
            assertEquals(0x20, data[entryStart + 18] & 0xFF,
                    "entry " + i + " byte 18 must be SP (entry: '"
                            + new String(data, entryStart, 20, StandardCharsets.ISO_8859_1).replace("\n", "\\n") + "')");
            // Byte 19 (last byte of the 20-byte entry) must be a single EOL.
            int last = data[entryStart + 19] & 0xFF;
            assertTrue(last == 0x0A || last == 0x0D,
                    "entry " + i + " byte 19 must be LF or CR, got 0x" + Integer.toHexString(last));
            // Byte 20 must be the start of the next entry (digit) or beyond.
            if (i + 1 < count) {
                int nextFirst = data[entryStart + 20] & 0xFF;
                assertTrue(nextFirst >= '0' && nextFirst <= '9',
                        "byte after entry " + i + " must be the first digit of the next entry, got 0x"
                                + Integer.toHexString(nextFirst));
            }
        }
    }

    @Test
    @DisplayName("N1 — xref entries terminate with SP + LF (not SP + CR + LF)")
    void xrefEntries_useSpaceLFTermination() throws IOException {
        byte[] data = Files.readAllBytes(buildDocWithSeveralObjects("xref-lf"));
        int xrefOff = parseStartXrefOffset(data);
        int count = parseXrefSubsectionCount(data, xrefOff);
        int start = xrefEntriesStart(data, xrefOff);
        for (int i = 0; i < count; i++) {
            int entryStart = start + 20 * i;
            assertEquals(0x0A, data[entryStart + 19] & 0xFF,
                    "entry " + i + " must end with LF (0x0A), got 0x"
                            + Integer.toHexString(data[entryStart + 19] & 0xFF));
            // Defensive: the byte before EOL must be SP (the 18-byte separator),
            // NOT CR (which would mean the 21-byte SP CR LF legacy form).
            assertEquals(0x20, data[entryStart + 18] & 0xFF,
                    "entry " + i + " byte 18 must be SP — anything else means a 21-byte legacy entry");
        }
    }

    @Test
    @DisplayName("N1 — every in-use xref offset points at a real \"N G obj\" header")
    void xrefByteOffsets_pointAtRealObjectStarts() throws IOException {
        byte[] data = Files.readAllBytes(buildDocWithSeveralObjects("xref-offsets"));
        int xrefOff = parseStartXrefOffset(data);
        int count = parseXrefSubsectionCount(data, xrefOff);
        int start = xrefEntriesStart(data, xrefOff);
        for (int i = 1; i < count; i++) {
            int entryStart = start + 20 * i;
            String entry = new String(data, entryStart, 20, StandardCharsets.ISO_8859_1);
            char flag = entry.charAt(17);
            if (flag != 'n') continue;  // free entry
            int offset = Integer.parseInt(entry.substring(0, 10));
            int gen = Integer.parseInt(entry.substring(11, 16));
            // Bytes at `offset` should read "{i} {gen} obj"
            String header = i + " " + gen + " obj";
            String actual = new String(data, offset,
                    Math.min(header.length(), data.length - offset), StandardCharsets.ISO_8859_1);
            assertEquals(header, actual,
                    "xref entry " + i + " offset " + offset + " must point at \""
                            + header + "\", got \"" + actual + "\"");
        }
    }

    @Test
    @DisplayName("N1 — xref subsection header line ends with a single LF")
    void xrefHeader_subsectionLineIsSingleEOL() throws IOException {
        byte[] data = Files.readAllBytes(buildDocWithSeveralObjects("xref-hdr"));
        int xrefOff = parseStartXrefOffset(data);
        // Expect: "xref\n0 N\n"
        assertArrayEqualsAt(data, xrefOff, "xref\n");
        int p = xrefOff + 5;
        while (p < data.length && data[p] != '\n') {
            // header line must not contain CR
            assertNotEquals(0x0D, data[p] & 0xFF, "subsection header contains CR at " + p);
            p++;
        }
        assertEquals(0x0A, data[p] & 0xFF, "subsection header must end with LF");
    }

    // ───────────────────────── N2 — orphan streams ───────────────────────────

    /**
     * Returns the offset of the next byte after the closest preceding
     * {@code " obj\n"} marker (or -1 if none).
     */
    private static int findPrecedingObjStart(byte[] data, int pos) {
        // Search backwards for " obj\n"
        byte[] needle = " obj\n".getBytes(StandardCharsets.US_ASCII);
        for (int i = pos - needle.length; i >= 0; i--) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) { match = false; break; }
            }
            if (match) return i + needle.length;
        }
        return -1;
    }

    private static int countOccurrences(byte[] data, int start, int end, byte[] needle) {
        int count = 0;
        outer:
        for (int i = start; i <= end - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            count++;
            i += needle.length - 1;
        }
        return count;
    }

    /**
     * Walks the file; for every {@code "stream"} keyword that is followed by
     * an EOL (i.e., starts a stream body, not an {@code endstream} substring),
     * asserts that between the start of the enclosing indirect object
     * ({@code " obj\n"}) and the stream keyword, the number of {@code "<<"}
     * matches the number of {@code ">>"} — i.e., the stream's own dictionary
     * is balanced and closed before the {@code stream} keyword. An imbalance
     * (more opens than closes) means we're inside a parent dict and the
     * stream is being serialised inline, violating ISO 32000-1:2008 §7.3.8.1.
     *
     * <p>{@code countOccurrences} of {@code "<<"} alone is NOT a valid
     * invariant: a stream dict may legitimately have nested entries (e.g.,
     * {@code /Resources << /Font << ... >> >>}) which inflates the {@code <<}
     * count above 1 even though the stream is correctly indirect.</p>
     */
    private void assertNoInlineStreams(Path pdf) throws IOException {
        byte[] data = Files.readAllBytes(pdf);
        byte[] streamKW = "stream".getBytes(StandardCharsets.US_ASCII);
        byte[] endKW = "endstream".getBytes(StandardCharsets.US_ASCII);
        byte[] openDict = "<<".getBytes(StandardCharsets.US_ASCII);
        byte[] closeDict = ">>".getBytes(StandardCharsets.US_ASCII);
        int pos = 0;
        int streamsChecked = 0;
        while (pos <= data.length - streamKW.length) {
            // Skip "endstream" matches.
            if (pos <= data.length - endKW.length && matches(data, pos, endKW)) {
                pos += endKW.length;
                continue;
            }
            if (!matches(data, pos, streamKW)) {
                pos++;
                continue;
            }
            // "stream" must be followed by EOL (LF or CR LF) to be a real
            // keyword — anything else is just part of a larger word.
            int afterKw = pos + streamKW.length;
            if (afterKw >= data.length) { pos += streamKW.length; continue; }
            byte b = data[afterKw];
            if (b != 0x0A && b != 0x0D) { pos += streamKW.length; continue; }

            int objStart = findPrecedingObjStart(data, pos);
            if (objStart < 0) { pos += streamKW.length; continue; }

            int opens  = countOccurrences(data, objStart, pos, openDict);
            int closes = countOccurrences(data, objStart, pos, closeDict);
            assertEquals(opens, closes,
                    "stream at offset " + pos + " is INLINE — between its obj header and the "
                            + "'stream' keyword, '<<' count (" + opens + ") != '>>' count (" + closes
                            + ")."
                            + " Indirect streams MUST be the value of an indirect object whose dict "
                            + "is fully closed before 'stream'.");
            streamsChecked++;
            pos += streamKW.length;
        }
        // Sanity: any document built by this test class produces at least one
        // stream (the page's /Contents or a widget's appearance), so the loop
        // must have found something.
        assertTrue(streamsChecked >= 1,
                "no streams found in the produced PDF — test premise broken (call setValue or "
                        + "appendToContentStream so a stream actually appears).");
    }

    private static boolean matches(byte[] data, int pos, byte[] needle) {
        for (int j = 0; j < needle.length; j++) {
            if (data[pos + j] != needle[j]) return false;
        }
        return true;
    }

    @Test
    @DisplayName("N2 — Form XObject streams used by widget /AP/N are indirect, not inline")
    void formXObjectStreams_areIndirectObjects() throws IOException {
        Path out = tempDir.resolve("acroform-indirect.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(50, 700, 250, 720));
            tb.setValue("appearance");  // forces regenerateAppearance() ⇒ creates an /AP/N stream
            page.getAnnotations().add(tb);
            doc.save(out.toString());
        }
        assertNoInlineStreams(out);
    }

    @Test
    @DisplayName("N2 — Reopened TextBoxField's /AP/N resolves through an indirect reference")
    void formXObjects_areReferencedAsIndirectFromAP() throws IOException {
        Path out = tempDir.resolve("ap-indirect.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(50, 700, 250, 720));
            tb.setValue("hello");
            page.getAnnotations().add(tb);
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            // The widget is the first annotation on page 1.
            Annotation widget = reopened.getPages().get(1).getAnnotations().get(1);
            COSDictionary dict = widget.getCOSDictionary();
            COSBase ap = dict.get(COSName.of("AP"));
            assertNotNull(ap, "widget must have an /AP entry");
            // Resolve /AP if it's itself an indirect reference.
            COSDictionary apDict;
            if (ap instanceof COSObjectReference) {
                apDict = (COSDictionary) ((COSObjectReference) ap).dereference();
            } else {
                apDict = (COSDictionary) ap;
            }
            COSBase n = apDict.get(COSName.of("N"));
            assertNotNull(n, "/AP must have an /N entry");
            // The fix says: /AP/N must be either an indirect reference OR (on
            // disk) the stream MUST have been promoted to an indirect object —
            // the latter case is tested at the byte level by
            // assertNoInlineStreams.
            if (n instanceof COSStream) {
                assertNotNull(((COSStream) n).getObjectKey(),
                        "/AP/N COSStream must carry an object key (= it was promoted to indirect)");
            } else {
                assertTrue(n instanceof COSObjectReference,
                        "/AP/N must be COSObjectReference or a keyed COSStream, got "
                                + n.getClass().getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("N2 — appendToContentStream-produced streams are indirect after save")
    void appendToContentStream_streamsAreIndirect() throws IOException {
        Path out = tempDir.resolve("append-content.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.appendToContentStream("q Q\n".getBytes(StandardCharsets.US_ASCII));
            doc.save(out.toString());
        }
        // Walk the bytes: every stream must be properly indirect.
        assertNoInlineStreams(out);
        // Also verify at the COS layer.
        try (Document reopened = new Document(out.toString())) {
            COSBase contents = reopened.getPages().get(1).getCOSDictionary().get(COSName.CONTENTS);
            assertNotNull(contents, "page must have /Contents");
            if (contents instanceof COSObjectReference) {
                // Fine — indirect ref to a stream
            } else if (contents instanceof COSArray) {
                COSArray arr = (COSArray) contents;
                for (int i = 0; i < arr.size(); i++) {
                    COSBase elem = arr.get(i);
                    assertTrue(elem instanceof COSObjectReference,
                            "/Contents[" + i + "] must be COSObjectReference, got "
                                    + elem.getClass().getSimpleName());
                }
            } else if (contents instanceof COSStream) {
                // OK only if the stream itself has an object key.
                assertNotNull(((COSStream) contents).getObjectKey(),
                        "/Contents COSStream must carry an object key");
            } else {
                fail("/Contents has unexpected type: " + contents.getClass().getSimpleName());
            }
        }
    }

    @Test
    @DisplayName("N2 — widget /AP survives two consecutive save+reopen cycles")
    void widgetAP_roundTripsAcrossDoubleSave() throws IOException {
        Path first = tempDir.resolve("double-1.pdf");
        Path second = tempDir.resolve("double-2.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(50, 700, 250, 720));
            tb.setValue("round-trip");
            page.getAnnotations().add(tb);
            doc.save(first.toString());
        }
        try (Document reopened = new Document(first.toString())) {
            reopened.save(second.toString());
        }
        // The second file must reopen cleanly and have a properly-formed widget /AP.
        try (Document second2 = new Document(second.toString())) {
            Annotation widget = second2.getPages().get(1).getAnnotations().get(1);
            COSBase ap = widget.getCOSDictionary().get(COSName.of("AP"));
            assertNotNull(ap, "widget /AP must survive double round-trip");
        }
        assertNoInlineStreams(second);
    }

    @Test
    @DisplayName("Combined smoke (M+N1+N2) — fresh doc with form fields parses + walks cleanly")
    void freshDocumentWithFormFields_passesStrictParse() throws IOException {
        Path out = tempDir.resolve("combined-smoke.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            // Mix of generation-time features that exercise all three fixes:
            // M (locale): TextBox setValue routes through TextBuilder
            //              fractional coords.
            // N1 (xref):  xref table is the writer's last act.
            // N2 (streams): the field's /AP/N is a freshly-created Form
            //               XObject stream with no pre-assigned object key.
            TextBoxField tb = new TextBoxField(page, new Rectangle(50.5, 700.25, 250.5, 720.25));
            tb.setValue("Smoke");
            page.getAnnotations().add(tb);
            doc.save(out.toString());
        }
        // Bytes-level invariants (catch the exact symptoms).
        byte[] data = Files.readAllBytes(out);
        String iso = new String(data, StandardCharsets.ISO_8859_1);
        assertFalse(Pattern.compile("\\b\\d+,\\d+\\b").matcher(iso).find(),
                "M: file unexpectedly contains comma-decimal numbers");
        assertNoInlineStreams(out);
        // xref-entry size — 20 bytes each.
        int xrefOff = parseStartXrefOffset(data);
        int count = parseXrefSubsectionCount(data, xrefOff);
        int start = xrefEntriesStart(data, xrefOff);
        for (int i = 0; i < count; i++) {
            assertEquals(0x0A, data[start + 20 * i + 19] & 0xFF,
                    "N1: entry " + i + " not terminated with single LF");
        }
        // And a reopen-walks-cleanly check.
        try (Document r = new Document(out.toString())) {
            Page p = r.getPages().get(1);
            assertEquals(1, p.getAnnotations().size());
            Annotation widget = p.getAnnotations().get(1);
            COSBase ap = widget.getCOSDictionary().get(COSName.of("AP"));
            assertNotNull(ap, "widget /AP must reopen non-null");
        }
    }
}
