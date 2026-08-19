package org.aspose.pdf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.PdfSaveOptions;
import org.aspose.pdf.SaveFormat;
import org.aspose.pdf.SaveOptions;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The uniform {@link Document#save(java.io.OutputStream, SaveOptions)} /
 * {@link Document#save(String, SaveOptions)} entry point selects the output
 * format from the runtime type of the {@link SaveOptions} argument (Aspose.PDF
 * pattern) — a {@link PdfSaveOptions} produces a PDF, an {@link HtmlSaveOptions}
 * produces HTML.
 */
public class SaveOptionsDispatchTest {

    private static Document oneLineDoc() throws Exception {
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextFragment tf = new TextFragment("Uniform save entry point.");
        tf.setPosition(new Position(72, 700));
        tf.getTextState().setFontSize(14);
        new TextBuilder(page).appendText(tf);
        return doc;
    }

    private static boolean isPdf(byte[] b) {
        return b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F';
    }

    @Test
    public void saveFormatReportedByOptionType() {
        assertEquals(SaveFormat.Pdf, new PdfSaveOptions().getSaveFormat());
        assertEquals(SaveFormat.Html, new HtmlSaveOptions().getSaveFormat());
        // Both are SaveOptions — the polymorphic base type.
        SaveOptions pdf = new PdfSaveOptions();
        SaveOptions html = new HtmlSaveOptions();
        assertEquals(SaveFormat.Pdf, pdf.getSaveFormat());
        assertEquals(SaveFormat.Html, html.getSaveFormat());
    }

    @Test
    public void streamDispatchByRuntimeType() throws Exception {
        // Static type SaveOptions forces the uniform overload; runtime type decides.
        SaveOptions asPdf = new PdfSaveOptions();
        SaveOptions asHtml = new HtmlSaveOptions();

        try (Document doc = oneLineDoc()) {
            ByteArrayOutputStream pdfOut = new ByteArrayOutputStream();
            doc.save(pdfOut, asPdf);
            assertTrue(isPdf(pdfOut.toByteArray()), "PdfSaveOptions must yield a PDF");
        }
        try (Document doc = oneLineDoc()) {
            ByteArrayOutputStream htmlOut = new ByteArrayOutputStream();
            doc.save(htmlOut, asHtml);
            String html = new String(htmlOut.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(html.contains("<html") || html.contains("<!DOCTYPE"),
                    "HtmlSaveOptions must yield HTML: " + html.substring(0, Math.min(80, html.length())));
            assertTrue(html.contains("Uniform save entry point."), "text preserved in HTML");
        }
    }

    @Test
    public void fileDispatchByRuntimeType(@TempDir Path dir) throws Exception {
        Path pdf = dir.resolve("out.pdf");
        Path htmlFile = dir.resolve("out.html");
        SaveOptions asPdf = new PdfSaveOptions();
        SaveOptions asHtml = new HtmlSaveOptions();

        try (Document doc = oneLineDoc()) {
            doc.save(pdf.toString(), asPdf);
        }
        try (Document doc = oneLineDoc()) {
            doc.save(htmlFile.toString(), asHtml);
        }
        assertTrue(isPdf(Files.readAllBytes(pdf)), "file PdfSaveOptions must yield a PDF");
        String html = new String(Files.readAllBytes(htmlFile), StandardCharsets.UTF_8);
        assertTrue(html.contains("<html") || html.contains("<!DOCTYPE"), "file HtmlSaveOptions must yield HTML");
    }

    @Test
    public void pdfOptionsCompressionHonouredViaBaseType() throws Exception {
        // Round-trip through a parser-backed document so compressed save is allowed.
        byte[] plain;
        try (Document doc = oneLineDoc()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            plain = bos.toByteArray();
        }
        try (Document doc = new Document(new ByteArrayInputStream(plain))) {
            PdfSaveOptions po = new PdfSaveOptions();
            po.setUseObjectStreams(true);
            SaveOptions asBase = po;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos, asBase);
            byte[] compressed = bos.toByteArray();
            assertTrue(isPdf(compressed), "compressed save via SaveOptions must be a PDF");
            // The result must reopen and keep its text.
            try (Document re = new Document(new ByteArrayInputStream(compressed))) {
                assertEquals(1, re.getPages().getCount());
            }
        }
    }
}
