package org.aspose.pdf.tests;

import org.aspose.pdf.Document;
import org.aspose.pdf.DocumentInfo;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageCollection;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectKey;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSString;
import org.aspose.pdf.engine.writer.PDFWriter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Document}.
 */
public class DocumentTest {

    /**
     * Creates a minimal valid PDF as a byte array using PDFWriter.
     * Structure: Catalog -> Pages -> Page (with MediaBox)
     */
    private static byte[] createMinimalPdf() throws IOException {
        return createMinimalPdf(null);
    }

    /**
     * Creates a minimal valid PDF with optional /Info dictionary.
     */
    private static byte[] createMinimalPdf(COSDictionary infoDict) throws IOException {
        // Build COS objects
        // Object 1: Catalog
        COSDictionary catalog = new COSDictionary();
        catalog.set("Type", COSName.of("Catalog"));
        catalog.set("Pages", new COSObjectReference(new COSObjectKey(2, 0)));

        // Object 2: Pages
        COSDictionary pages = new COSDictionary();
        pages.set("Type", COSName.PAGES);
        COSArray kids = new COSArray(1);
        kids.add(new COSObjectReference(new COSObjectKey(3, 0)));
        pages.set("Kids", kids);
        pages.set("Count", COSInteger.valueOf(1));

        // Object 3: Page
        COSDictionary page = new COSDictionary();
        page.set("Type", COSName.PAGE);
        page.set("Parent", new COSObjectReference(new COSObjectKey(2, 0)));
        COSArray mediaBox = new COSArray(4);
        mediaBox.add(new COSFloat(0));
        mediaBox.add(new COSFloat(0));
        mediaBox.add(new COSFloat(612));
        mediaBox.add(new COSFloat(792));
        page.set("MediaBox", mediaBox);

        Map<COSObjectKey, COSBase> objects = new LinkedHashMap<>();
        objects.put(new COSObjectKey(1, 0), catalog);
        objects.put(new COSObjectKey(2, 0), pages);
        objects.put(new COSObjectKey(3, 0), page);

        // Trailer
        COSDictionary trailer = new COSDictionary();
        trailer.set("Root", new COSObjectReference(new COSObjectKey(1, 0)));
        trailer.set("Size", COSInteger.valueOf(4));

        if (infoDict != null) {
            objects.put(new COSObjectKey(4, 0), infoDict);
            trailer.set("Info", new COSObjectReference(new COSObjectKey(4, 0)));
            trailer.set("Size", COSInteger.valueOf(5));
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PDFWriter writer = new PDFWriter(baos, 1.4f);
        writer.write(trailer, objects);
        return baos.toByteArray();
    }

    @Test
    public void constructorFromStreamParsesMinimalPdf() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            assertNotNull(doc);
            assertNotNull(doc.getTrailer());
            assertNotNull(doc.getCatalog());
        }
    }

    @Test
    public void getVersionReturnsCorrectVersion() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            String version = doc.getVersion();
            assertTrue(version.startsWith("1.4"), "Expected version starting with 1.4, got: " + version);
        }
    }

    @Test
    public void getPagesReturnsOnePage() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            PageCollection pages = doc.getPages();
            assertNotNull(pages);
            assertEquals(1, pages.size());

            Page page = pages.get(1);
            assertNotNull(page);
            assertEquals(1, page.getNumber());

            Rectangle mb = page.getMediaBox();
            assertNotNull(mb);
            assertEquals(612, mb.getURX(), 1e-10);
            assertEquals(792, mb.getURY(), 1e-10);
        }
    }

    @Test
    public void getInfoReturnsDocumentInfo() throws IOException {
        COSDictionary infoDict = new COSDictionary();
        infoDict.set("Title", new COSString("Test Document"));
        infoDict.set("Author", new COSString("Test Author"));

        byte[] pdfBytes = createMinimalPdf(infoDict);
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            DocumentInfo info = doc.getInfo();
            assertNotNull(info);
            assertEquals("Test Document", info.getTitle());
            assertEquals("Test Author", info.getAuthor());
        }
    }

    @Test
    public void getInfoAutoCreatesWhenAbsent() throws IOException {
        // As of Stage 12 / Bug K, getInfo() auto-creates an empty writable
        // DocumentInfo on documents whose trailer has no /Info entry, so
        // callers don't have to differentiate between "fresh doc" and
        // "loaded but no metadata".
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            DocumentInfo info = doc.getInfo();
            assertNotNull(info, "getInfo() must auto-create when trailer lacks /Info");
            assertNull(info.getTitle(), "the auto-created info is empty");
        }
    }

    @Test
    public void getCatalogReturnsValidCatalog() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            COSDictionary catalog = doc.getCatalog();
            assertNotNull(catalog);
            assertEquals("Catalog", catalog.getType());
        }
    }

    @Test
    public void getTrailerContainsRoot() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            COSDictionary trailer = doc.getTrailer();
            assertNotNull(trailer);
            assertNotNull(trailer.get("Root"));
        }
    }

    @Test
    public void constructorFromNullStreamThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Document((InputStream) null));
    }

    @Test
    public void constructorFromNullFilePathThrows() {
        assertThrows(IllegalArgumentException.class, () -> new Document((String) null));
    }

    @Test
    public void closeIsIdempotent() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        Document doc = new Document(new ByteArrayInputStream(pdfBytes));
        doc.close();
        // Second close should not throw
        doc.close();
    }

    @Test
    public void iteratePagesViaForEach() throws IOException {
        byte[] pdfBytes = createMinimalPdf();
        try (Document doc = new Document(new ByteArrayInputStream(pdfBytes))) {
            int count = 0;
            for (Page page : doc.getPages()) {
                count++;
                assertNotNull(page.getMediaBox());
            }
            assertEquals(1, count);
        }
    }
}
