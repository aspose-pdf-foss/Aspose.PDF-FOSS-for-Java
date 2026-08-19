package org.aspose.pdf.tests;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.operators.ShowText;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextFragmentAbsorber;
import org.aspose.pdf.text.TextFragmentCollection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the per-Page {@link OperatorCollection} cache introduced to fix
 * sub-bug (e): operator mutations (e.g. {@code TextFragment.setText}) must be
 * flushed back into {@code /Contents} on save.
 */
public class OperatorCollectionCacheTest {

    /** Builds a standalone Page with a simple content stream and F1=Helvetica. */
    private static Page createPageWithText(String contentStreamText) {
        PdfDictionary fontDict = new PdfDictionary();
        fontDict.set(PdfName.TYPE, PdfName.of("Font"));
        fontDict.set(PdfName.of("Subtype"), PdfName.of("Type1"));
        fontDict.set(PdfName.of("BaseFont"), PdfName.of("Helvetica"));

        PdfDictionary fontsDict = new PdfDictionary();
        fontsDict.set(PdfName.of("F1"), fontDict);

        PdfDictionary resourcesDict = new PdfDictionary();
        resourcesDict.set(PdfName.of("Font"), fontsDict);

        byte[] streamBytes = contentStreamText.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        PdfStream contentStream = new PdfStream(streamBytes);

        PdfDictionary pageDict = new PdfDictionary();
        pageDict.set(PdfName.TYPE, PdfName.PAGE);
        pageDict.set(PdfName.MEDIABOX, new Rectangle(0, 0, 595, 842).toPdfArray());
        pageDict.set(PdfName.RESOURCES, resourcesDict);
        pageDict.set(PdfName.CONTENTS, contentStream);

        return new Page(pageDict, null);
    }

    /**
     * Builds a Document containing a single page whose content stream shows
     * {@code body} once via {@code Tj}. Round-trips via byte array so callers
     * can reopen and verify persistence.
     */
    private static byte[] buildDocBytesWithText(String body) throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        PdfDictionary pd = page.getPdfDictionary();

        PdfDictionary fontDict = new PdfDictionary();
        fontDict.set(PdfName.TYPE, PdfName.of("Font"));
        fontDict.set(PdfName.of("Subtype"), PdfName.of("Type1"));
        fontDict.set(PdfName.of("BaseFont"), PdfName.of("Helvetica"));
        PdfDictionary fontsDict = new PdfDictionary();
        fontsDict.set(PdfName.of("F1"), fontDict);
        PdfDictionary res = new PdfDictionary();
        res.set(PdfName.of("Font"), fontsDict);
        pd.set(PdfName.RESOURCES, res);

        String cs = "BT /F1 12 Tf 100 700 Td (" + body + ") Tj ET";
        PdfStream contents = new PdfStream(cs.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        pd.set(PdfName.CONTENTS, contents);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    /**
     * Builds a Document whose single page has {@code count} {@code Tj} operators
     * each rendering the same {@code marker} text.
     */
    private static byte[] buildDocBytesWithRepeatedText(String marker, int count) throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        PdfDictionary pd = page.getPdfDictionary();

        PdfDictionary fontDict = new PdfDictionary();
        fontDict.set(PdfName.TYPE, PdfName.of("Font"));
        fontDict.set(PdfName.of("Subtype"), PdfName.of("Type1"));
        fontDict.set(PdfName.of("BaseFont"), PdfName.of("Helvetica"));
        PdfDictionary fontsDict = new PdfDictionary();
        fontsDict.set(PdfName.of("F1"), fontDict);
        PdfDictionary res = new PdfDictionary();
        res.set(PdfName.of("Font"), fontsDict);
        pd.set(PdfName.RESOURCES, res);

        StringBuilder sb = new StringBuilder("BT /F1 12 Tf ");
        for (int i = 0; i < count; i++) {
            int y = 700 - i * 15;
            sb.append("1 0 0 1 100 ").append(y).append(" Tm (").append(marker).append(") Tj ");
        }
        sb.append("ET");
        PdfStream contents = new PdfStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        pd.set(PdfName.CONTENTS, contents);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    @Test
    public void test1_BasicPersistence() throws IOException {
        byte[] pdf = buildDocBytesWithText("oldtext");

        ByteArrayOutputStream modified = new ByteArrayOutputStream();
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            TextFragmentAbsorber absorber = new TextFragmentAbsorber("oldtext");
            Page page = doc.getPages().get(1);
            page.accept(absorber);
            TextFragmentCollection fragments = absorber.getTextFragments();
            assertTrue(fragments.getCount() >= 1, "absorber should locate 'oldtext'");
            fragments.get(1).setText("newtext");
            doc.save(modified);
        }

        try (Document doc2 = new Document(new ByteArrayInputStream(modified.toByteArray()))) {
            TextFragmentAbsorber after = new TextFragmentAbsorber("newtext");
            doc2.getPages().get(1).accept(after);
            assertEquals(1, after.getTextFragments().getCount(),
                    "mutation via setText must persist through save/reload");
        }
    }

    @Test
    public void test2_MultipleMutationsOnSamePage() throws IOException {
        final int N = 13;
        byte[] pdf = buildDocBytesWithRepeatedText("findme", N);

        ByteArrayOutputStream modified = new ByteArrayOutputStream();
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            TextFragmentAbsorber absorber = new TextFragmentAbsorber("findme");
            Page page = doc.getPages().get(1);
            page.accept(absorber);
            TextFragmentCollection fragments = absorber.getTextFragments();
            assertEquals(N, fragments.getCount(), "should find all " + N + " instances");
            for (int i = 1; i <= fragments.getCount(); i++) {
                fragments.get(i).setText("REPLACED");
            }
            doc.save(modified);
        }

        try (Document doc2 = new Document(new ByteArrayInputStream(modified.toByteArray()))) {
            TextFragmentAbsorber after = new TextFragmentAbsorber("REPLACED");
            doc2.getPages().get(1).accept(after);
            assertEquals(N, after.getTextFragments().getCount(),
                    "all mutations on the same page must flush together");
            TextFragmentAbsorber stale = new TextFragmentAbsorber("findme");
            doc2.getPages().get(1).accept(stale);
            assertEquals(0, stale.getTextFragments().getCount(),
                    "no 'findme' instances should remain");
        }
    }

    @Test
    public void test3_CacheReturnsSameInstance() throws IOException {
        Page page = createPageWithText("BT /F1 12 Tf 100 700 Td (Hello) Tj ET");
        OperatorCollection ops1 = page.getContents();
        OperatorCollection ops2 = page.getContents();
        assertSame(ops1, ops2, "getContents() must return the cached instance on repeat calls");
    }

    @Test
    public void test4_SetContentsInvalidatesCache() throws IOException {
        Page page = createPageWithText("BT /F1 12 Tf 100 700 Td (Hello) Tj ET");
        OperatorCollection ops1 = page.getContents();
        assertNotNull(ops1);

        OperatorCollection replacement = new OperatorCollection();
        replacement.add(new ShowText("fresh"));
        page.setContents(replacement);

        OperatorCollection ops2 = page.getContents();
        // setContents() invalidates the cache and writes authoritative bytes; the
        // next getContents() RE-PARSES them into typed, render-ready operators
        // (ConcatenateMatrix/GSave/… rather than generic Operator instances) — so
        // it returns a fresh collection, not the caller-supplied one.
        assertNotSame(ops1, ops2,
                "previous cache must be evicted after setContents()");
        assertNotSame(replacement, ops2,
                "setContents() invalidates the cache; getContents() re-parses the written bytes");
        // …but the re-parsed content reflects the replacement (the 'fresh' text).
        StringBuilder rendered = new StringBuilder();
        for (Operator op : ops2) {
            rendered.append(op.toString()).append(' ');
        }
        assertTrue(rendered.toString().contains("fresh"),
                "re-parsed content must contain the replacement text, was: " + rendered);
    }

    @Test
    public void test5_NonMutatingSaveUnchanged() throws IOException {
        byte[] pdf = buildDocBytesWithText("keeptext");

        ByteArrayOutputStream resaved = new ByteArrayOutputStream();
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            // Parse contents but don't mutate anything.
            OperatorCollection ops = doc.getPages().get(1).getContents();
            assertNotNull(ops);
            doc.save(resaved);
        }

        try (Document doc2 = new Document(new ByteArrayInputStream(resaved.toByteArray()))) {
            TextFragmentAbsorber absorber = new TextFragmentAbsorber("keeptext");
            doc2.getPages().get(1).accept(absorber);
            assertEquals(1, absorber.getTextFragments().getCount(),
                    "non-mutating read+save must preserve original content");
        }
    }
}
