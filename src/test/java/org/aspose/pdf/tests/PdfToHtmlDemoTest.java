package org.aspose.pdf.tests;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlDocumentType;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.TextFragment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrates converting a PDF document to HTML.
 * <p>
 * OpenPDF exposes two conversion philosophies through the single
 * {@code Document.save(..., HtmlSaveOptions)} entry point (see {@link HtmlOutputMode}):
 * <ul>
 *   <li>{@link HtmlOutputMode#STRUCTURAL} — semantic HTML
 *       ({@code h1..h6/p/ul/ol/table}) built from the Semantic Document Model;</li>
 *   <li>{@link HtmlOutputMode#FIXED_LAYOUT} — a visual copy with absolutely
 *       positioned spans.</li>
 * </ul>
 * {@code save(OutputStream, HtmlSaveOptions)} streams the HTML;
 * {@code save(path, HtmlSaveOptions)} writes it to a file.
 */
public class PdfToHtmlDemoTest {

    @TempDir
    Path tempDir;

    /**
     * Builds a small PDF with a couple of text lines. The paragraphs are added
     * to the page and then flushed to real content by a save+reopen round trip,
     * so a subsequent PDF -> HTML conversion sees genuine page content (the same
     * situation as loading an existing PDF from disk).
     */
    private Document buildSampleDoc() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        page.getParagraphs().add(new TextFragment("Quarterly Report"));
        page.getParagraphs().add(new TextFragment("Revenue grew by 12% this quarter."));
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        doc.save(bos);
        return new Document(new java.io.ByteArrayInputStream(bos.toByteArray()));
    }

    /** PDF -> HTML as a String (structural mode), asserting the text survives. */
    @Test
    public void convertsPdfToHtmlString() throws IOException {
        try (Document doc = buildSampleDoc()) {
            HtmlSaveOptions options = new HtmlSaveOptions();
            options.setDocumentType(HtmlDocumentType.Html5);
            options.setOutputMode(HtmlOutputMode.STRUCTURAL);

            String html = org.aspose.pdf.testutil.HtmlText.of(doc, options);

            assertTrue(html.startsWith("<!DOCTYPE html>"), "should be HTML5");
            assertTrue(html.contains("Quarterly Report"), "heading text should be present");
            assertTrue(html.contains("Revenue grew by 12% this quarter."), "body text should be present");
            System.out.println("---- STRUCTURAL HTML ----\n" + html);
        }
    }

    /** PDF -> HTML written to a file (fixed-layout mode). */
    @Test
    public void convertsPdfToHtmlFile() throws IOException {
        Path out = tempDir.resolve("report.html");
        try (Document doc = buildSampleDoc()) {
            HtmlSaveOptions options = new HtmlSaveOptions();
            options.setOutputMode(HtmlOutputMode.FIXED_LAYOUT);
            options.setEmbedImages(true);

            doc.save(out.toString(), options);
        }
        assertTrue(Files.exists(out), "HTML file should be written");
        String html = new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
        assertTrue(html.contains("Quarterly Report"), "file should contain the document text");
        assertTrue(html.toLowerCase().contains("<html"), "file should be an HTML document");
    }
}
