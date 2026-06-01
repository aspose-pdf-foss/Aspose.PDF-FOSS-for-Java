package org.aspose.pdf.tests;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.*;
import org.aspose.pdf.engine.cos.*;
import org.aspose.pdf.forms.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for annotation and form flattening.
 */
public class FlattenTest {

    /**
     * Creates a minimal page dictionary with a MediaBox.
     */
    private Page createPage() {
        COSDictionary pageDict = new COSDictionary();
        pageDict.set(COSName.TYPE, COSName.PAGE);
        pageDict.set(COSName.MEDIABOX, new Rectangle(0, 0, 595, 842).toCOSArray());
        return new Page(pageDict, null);
    }

    /**
     * Creates an annotation dictionary with a normal appearance stream (/AP /N).
     *
     * @param rect       the annotation rectangle
     * @param apContent  the appearance stream content bytes
     * @param bbox       the appearance BBox
     * @return the annotation COS dictionary
     */
    private COSDictionary createAnnotWithAppearance(Rectangle rect, String apContent, Rectangle bbox) {
        COSDictionary annotDict = new COSDictionary();
        annotDict.set(COSName.of("Type"), COSName.of("Annot"));
        annotDict.set(COSName.of("Subtype"), COSName.of("Stamp"));
        annotDict.set(COSName.of("Rect"), rect.toCOSArray());

        COSStream apStream = new COSStream();
        apStream.setDecodedData(apContent.getBytes(StandardCharsets.US_ASCII));
        apStream.set(COSName.BBOX, bbox.toCOSArray());

        COSDictionary apDict = new COSDictionary();
        apDict.set(COSName.of("N"), apStream);
        annotDict.set(COSName.of("AP"), apDict);

        return annotDict;
    }

    @Test
    public void testFlattenAnnotationAppendsCTM() throws IOException {
        Page page = createPage();
        COSDictionary pageDict = page.getCOSDictionary();

        // Create annotation at (100,200)-(200,300) with a 100x100 BBox appearance
        Rectangle annotRect = new Rectangle(100, 200, 200, 300);
        Rectangle bbox = new Rectangle(0, 0, 100, 100);
        String apContent = "1 0 0 1 0 0 cm";

        COSDictionary annotDict = createAnnotWithAppearance(annotRect, apContent, bbox);

        // Add annotation to page /Annots array
        COSArray annots = new COSArray();
        annots.add(annotDict);
        pageDict.set(COSName.ANNOTS, annots);

        // Flatten
        page.flattenAnnotations();

        // Verify /Annots is removed
        assertNull(pageDict.get(COSName.ANNOTS),
                "Annots should be removed after flattening");

        // Verify content stream was created with CTM operators
        COSBase contents = pageDict.get(COSName.CONTENTS);
        assertNotNull(contents, "Content stream should exist after flattening");

        // Read the flattened content
        String contentStr = getContentString(contents);
        assertTrue(contentStr.contains("q"), "Flattened content should contain 'q' (save state)");
        assertTrue(contentStr.contains("cm"), "Flattened content should contain 'cm' (CTM)");
        assertTrue(contentStr.contains("Q"), "Flattened content should contain 'Q' (restore state)");
        // BUG-F4 fix (Sprint 21): the appearance is placed as a Form XObject
        // invocation (`/FmFlat... Do`) rather than inlined, so its bytes live in
        // the XObject stream — not the page content stream. This keeps the
        // appearance's own internal operators out of the page content (ISO
        // 32000-1:2008 §12.5.5).
        assertTrue(contentStr.contains("Do"),
                "Flattened content should invoke the appearance Form XObject via 'Do'");
        COSDictionary xobjs = page.getResources().getXObjects();
        assertNotNull(xobjs, "Flattening should register the appearance as an XObject");
        boolean apFound = false;
        for (COSName key : xobjs.keySet()) {
            COSBase v = xobjs.get(key);
            if (v instanceof COSStream) {
                String xc = new String(((COSStream) v).getDecodedData(), StandardCharsets.US_ASCII);
                if (xc.contains(apContent)) { apFound = true; break; }
            }
        }
        assertTrue(apFound, "Original appearance content should live in the placed XObject stream");
    }

    @Test
    public void testFlattenSkipsHiddenAnnotation() throws IOException {
        Page page = createPage();
        COSDictionary pageDict = page.getCOSDictionary();

        // Create hidden annotation (flag bit 2 = 0x02)
        Rectangle annotRect = new Rectangle(10, 10, 50, 50);
        Rectangle bbox = new Rectangle(0, 0, 40, 40);
        COSDictionary annotDict = createAnnotWithAppearance(annotRect, "0 0 m", bbox);
        annotDict.set(COSName.of("F"), COSInteger.valueOf(0x02)); // Hidden

        COSArray annots = new COSArray();
        annots.add(annotDict);
        pageDict.set(COSName.ANNOTS, annots);

        page.flattenAnnotations();

        // /Annots should still be removed (array is cleared)
        assertNull(pageDict.get(COSName.ANNOTS));

        // No content should have been appended (hidden annotation was skipped)
        COSBase contents = pageDict.get(COSName.CONTENTS);
        assertNull(contents, "No content should be added for hidden annotations");
    }

    @Test
    public void testFlattenAnnotationWithNoAppearance() throws IOException {
        Page page = createPage();
        COSDictionary pageDict = page.getCOSDictionary();

        // Create annotation without /AP
        COSDictionary annotDict = new COSDictionary();
        annotDict.set(COSName.of("Type"), COSName.of("Annot"));
        annotDict.set(COSName.of("Subtype"), COSName.of("Text"));
        annotDict.set(COSName.of("Rect"), new Rectangle(0, 0, 50, 50).toCOSArray());

        COSArray annots = new COSArray();
        annots.add(annotDict);
        pageDict.set(COSName.ANNOTS, annots);

        page.flattenAnnotations();

        assertNull(pageDict.get(COSName.ANNOTS));
        assertNull(pageDict.get(COSName.CONTENTS),
                "No content should be added when annotation has no appearance");
    }

    @Test
    public void testFlattenFormField() throws IOException {
        // Build a minimal document structure
        Document doc = new Document();
        PageCollection pages = doc.getPages();
        Page page = pages.add();
        COSDictionary pageDict = page.getCOSDictionary();

        // Create a text field with appearance
        COSDictionary fieldDict = new COSDictionary();
        fieldDict.set(COSName.of("Type"), COSName.of("Annot"));
        fieldDict.set(COSName.of("Subtype"), COSName.of("Widget"));
        fieldDict.set(COSName.of("FT"), COSName.of("Tx"));
        fieldDict.set(COSName.of("T"), new COSString("field1".getBytes(StandardCharsets.UTF_8)));
        Rectangle fieldRect = new Rectangle(50, 700, 200, 720);
        fieldDict.set(COSName.of("Rect"), fieldRect.toCOSArray());

        // Create appearance stream for the field
        COSStream apStream = new COSStream();
        apStream.setDecodedData("BT /F1 12 Tf (Hello) Tj ET".getBytes(StandardCharsets.US_ASCII));
        apStream.set(COSName.BBOX, new Rectangle(0, 0, 150, 20).toCOSArray());

        COSDictionary apDict = new COSDictionary();
        apDict.set(COSName.of("N"), apStream);
        fieldDict.set(COSName.of("AP"), apDict);

        // Add widget annotation to the page's /Annots
        COSArray annots = new COSArray();
        annots.add(fieldDict);
        pageDict.set(COSName.ANNOTS, annots);

        // Flatten annotations on the page directly
        page.flattenAnnotations();

        // Verify annotations are removed
        assertNull(pageDict.get(COSName.ANNOTS),
                "Annots should be removed after field flattening");

        // Verify content was appended
        COSBase contents = pageDict.get(COSName.CONTENTS);
        assertNotNull(contents, "Content stream should exist after field flattening");
    }

    @Test
    public void testDocumentFlatten() throws IOException {
        // Build a document with an annotation on a page
        Document doc = new Document();
        PageCollection pages = doc.getPages();
        Page page = pages.add();
        COSDictionary pageDict = page.getCOSDictionary();

        // Add a stamp annotation with appearance
        Rectangle annotRect = new Rectangle(100, 100, 200, 200);
        Rectangle bbox = new Rectangle(0, 0, 100, 100);
        COSDictionary annotDict = createAnnotWithAppearance(annotRect, "0 1 0 rg 0 0 100 100 re f", bbox);

        COSArray annots = new COSArray();
        annots.add(annotDict);
        pageDict.set(COSName.ANNOTS, annots);

        // Flatten the whole document
        doc.flatten();

        // Verify annotations removed
        assertNull(pageDict.get(COSName.ANNOTS),
                "Annots should be removed after document.flatten()");

        // Verify content was generated
        COSBase contents = pageDict.get(COSName.CONTENTS);
        assertNotNull(contents, "Content stream should exist after document.flatten()");
    }

    @Test
    public void testAppendToContentStreamSingleStream() {
        Page page = createPage();
        COSDictionary pageDict = page.getCOSDictionary();

        // Set initial content
        COSStream initial = new COSStream();
        initial.setDecodedData("BT (Hello) Tj ET".getBytes(StandardCharsets.US_ASCII));
        pageDict.set(COSName.CONTENTS, initial);

        // Append new content
        page.appendToContentStream("q 1 0 0 1 0 0 cm Q".getBytes(StandardCharsets.US_ASCII));

        // Should now be a COSArray with 2 entries
        COSBase contents = pageDict.get(COSName.CONTENTS);
        assertTrue(contents instanceof COSArray, "Contents should be an array after append");
        assertEquals(2, ((COSArray) contents).size());
    }

    @Test
    public void testAppendToContentStreamExistingArray() {
        Page page = createPage();
        COSDictionary pageDict = page.getCOSDictionary();

        // Set initial content as array
        COSArray arr = new COSArray();
        COSStream s1 = new COSStream();
        s1.setDecodedData("BT ET".getBytes(StandardCharsets.US_ASCII));
        arr.add(s1);
        pageDict.set(COSName.CONTENTS, arr);

        // Append
        page.appendToContentStream("q Q".getBytes(StandardCharsets.US_ASCII));

        assertEquals(2, arr.size(), "Array should grow by one after append");
    }

    @Test
    public void testGetNormalAppearance() {
        COSDictionary annotDict = new COSDictionary();
        annotDict.set(COSName.of("Subtype"), COSName.of("Stamp"));

        // No AP -> null
        Annotation annot = Annotation.fromDictionary(annotDict, null);
        assertNull(annot.getNormalAppearanceStream());

        // Add AP/N stream
        COSStream apStream = new COSStream();
        apStream.setDecodedData("test".getBytes(StandardCharsets.US_ASCII));
        COSDictionary apDict = new COSDictionary();
        apDict.set(COSName.of("N"), apStream);
        annotDict.set(COSName.of("AP"), apDict);

        annot = Annotation.fromDictionary(annotDict, null);
        assertNotNull(annot.getNormalAppearanceStream());
        assertSame(apStream, annot.getNormalAppearanceStream());
    }

    /**
     * Helper: extracts text from a content stream COS object (COSStream or COSArray).
     */
    private String getContentString(COSBase contents) throws IOException {
        if (contents instanceof COSStream) {
            return new String(((COSStream) contents).getDecodedData(), StandardCharsets.US_ASCII);
        }
        if (contents instanceof COSArray) {
            StringBuilder sb = new StringBuilder();
            COSArray arr = (COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item instanceof COSStream) {
                    sb.append(new String(((COSStream) item).getDecodedData(), StandardCharsets.US_ASCII));
                }
            }
            return sb.toString();
        }
        return "";
    }
}
