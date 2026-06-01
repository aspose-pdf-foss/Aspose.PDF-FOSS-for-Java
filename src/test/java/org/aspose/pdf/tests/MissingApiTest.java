package org.aspose.pdf.tests;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.AnnotationType;
import org.aspose.pdf.engine.layout.ContentStreamBuilder;
import org.aspose.pdf.facades.*;
import org.aspose.pdf.printing.*;
import org.aspose.pdf.text.*;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 7 missing API features.
 */
public class MissingApiTest {

    // ==================== Feature 1: Page.addStamp ====================

    @Test
    public void testAddTextStampCenter() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextStamp stamp = new TextStamp("Test stamp");
        stamp.setHorizontalAlignment(HorizontalAlignment.Center);
        stamp.setVerticalAlignment(VerticalAlignment.Center);
        stamp.getTextState().setFontSize(14);
        stamp.setWidth(100);
        stamp.setHeight(20);

        page.addStamp(stamp);

        // Verify content stream was added
        assertNotNull(page.getRawContents(), "Content stream should exist after stamp");

        doc.close();
    }

    @Test
    public void testAddTextStampWithRotation() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextStamp stamp = new TextStamp("Rotated");
        stamp.setRotateAngle(45);
        stamp.setXIndent(100);
        stamp.setYIndent(200);
        stamp.getTextState().setFontSize(12);

        page.addStamp(stamp);

        assertNotNull(page.getRawContents());
        doc.close();
    }

    @Test
    public void testAddTextStampBackground() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        // Add some existing content first
        page.appendToContentStream("BT /F1 12 Tf (Existing text) Tj ET\n".getBytes());

        TextStamp stamp = new TextStamp("Background");
        stamp.setBackground(true);
        stamp.getTextState().setFontSize(10);
        page.addStamp(stamp);

        // Content should exist (prepended)
        assertNotNull(page.getRawContents());
        doc.close();
    }

    @Test
    public void testAddImageStamp() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        // addStamp now materialises and registers the image bytes
        // (closes Bug B). An in-memory JPEG is enough for the round-trip
        // check; the old test passed a non-existent "dummy.jpg" because
        // the buggy impl never actually opened the file.
        byte[] jpegBytes = buildTinyJpeg();
        ImageStamp stamp = new ImageStamp("ignored.jpg");
        stamp.setImageStream(new java.io.ByteArrayInputStream(jpegBytes));
        stamp.setXIndent(100);
        stamp.setYIndent(200);
        stamp.setWidth(200);
        stamp.setHeight(100);

        page.addStamp(stamp);

        assertNotNull(page.getRawContents(), "Content stream should exist after image stamp");
        doc.close();
    }

    private static byte[] buildTinyJpeg() throws IOException {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "JPEG", out);
        return out.toByteArray();
    }

    @Test
    public void testAddPdfPageStamp() throws IOException {
        Document doc = new Document();
        Page sourcePage = doc.getPages().add();
        sourcePage.appendToContentStream("BT /F1 12 Tf (Source content) Tj ET\n".getBytes());

        Page targetPage = doc.getPages().add();

        PdfPageStamp stamp = new PdfPageStamp(sourcePage);
        stamp.setBackground(true);
        targetPage.addStamp(stamp);

        assertNotNull(targetPage.getRawContents(), "Target should have content after page stamp");
        doc.close();
    }

    // ==================== Feature 2: TextReplaceOptions ====================

    @Test
    public void testTextReplaceOptionsDefaults() {
        TextReplaceOptions opts = new TextReplaceOptions();
        assertEquals(TextReplaceOptions.ReplaceAdjustment.None, opts.getReplaceAdjustmentAction());
        assertEquals(TextReplaceOptions.Scope.REPLACE_ALL, opts.getReplaceScope());
    }

    @Test
    public void testTextReplaceOptionsConstructors() {
        TextReplaceOptions opts1 = new TextReplaceOptions(TextReplaceOptions.ReplaceAdjustment.ShiftRestOfLine);
        assertEquals(TextReplaceOptions.ReplaceAdjustment.ShiftRestOfLine, opts1.getReplaceAdjustmentAction());

        TextReplaceOptions opts2 = new TextReplaceOptions(TextReplaceOptions.Scope.REPLACE_FIRST);
        assertEquals(TextReplaceOptions.Scope.REPLACE_FIRST, opts2.getReplaceScope());
    }

    @Test
    public void testTextReplaceOptionsOnAbsorber() {
        TextFragmentAbsorber absorber = new TextFragmentAbsorber("test");
        assertNotNull(absorber.getTextReplaceOptions());
        assertEquals(TextReplaceOptions.Scope.REPLACE_ALL, absorber.getTextReplaceOptions().getReplaceScope());

        TextReplaceOptions opts = new TextReplaceOptions(TextReplaceOptions.Scope.REPLACE_FIRST);
        absorber.setTextReplaceOptions(opts);
        assertSame(opts, absorber.getTextReplaceOptions());
    }

    @Test
    public void testTextReplaceOptionsOnPdfContentEditor() {
        PdfContentEditor editor = new PdfContentEditor();
        assertNull(editor.getTextReplaceOptions());

        TextReplaceOptions opts = new TextReplaceOptions(TextReplaceOptions.ReplaceAdjustment.AdjustSpaceWidth);
        editor.setTextReplaceOptions(opts);
        assertSame(opts, editor.getTextReplaceOptions());
    }

    // ==================== Feature 3: ParagraphAdd ====================

    @Test
    public void testParagraphAddToNewDocument() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment fragment = new TextFragment("Hello World");
        fragment.getTextState().setFontSize(14);
        page.getParagraphs().add(fragment);

        // Save to stream - this should trigger layout
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);

        assertTrue(baos.size() > 0, "Saved PDF should have content");
        doc.close();
    }

    // ==================== Feature 4: PdfExtractor ====================

    @Test
    public void testPdfExtractorTextFromNewDoc() throws IOException {
        // Create a document with text via paragraphs
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextFragment tf = new TextFragment("Extractable text");
        tf.getTextState().setFontSize(12);
        page.getParagraphs().add(tf);

        // Save and reload
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        // Use PdfExtractor
        PdfExtractor extractor = new PdfExtractor();
        extractor.bindPdf(new java.io.ByteArrayInputStream(baos.toByteArray()));
        extractor.extractText();

        String text = extractor.getTextAsString();
        assertNotNull(text, "Extracted text should not be null");
        extractor.close();
    }

    @Test
    public void testPdfExtractorPageRange() throws IOException {
        PdfExtractor extractor = new PdfExtractor();
        extractor.setStartPage(1);
        extractor.setEndPage(1);
        assertEquals(1, extractor.getStartPage());
        assertEquals(1, extractor.getEndPage());
    }

    @Test
    public void testPdfExtractorImageApi() throws IOException {
        // Just test the API contract works
        Document doc = new Document();
        doc.getPages().add();

        PdfExtractor extractor = new PdfExtractor();
        extractor.bindPdf(doc);
        extractor.extractImage();

        assertFalse(extractor.hasNextImage(), "New doc should have no images");
        assertEquals(0, extractor.getImageCount());

        extractor.close();
    }

    // ==================== Feature 5: PdfPageStamp ====================

    @Test
    public void testPdfPageStampProperties() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        PdfPageStamp stamp = new PdfPageStamp(page);
        assertSame(page, stamp.getSourcePage());
        assertFalse(stamp.isBackground());

        stamp.setBackground(true);
        assertTrue(stamp.isBackground());

        stamp.setXIndent(10);
        stamp.setYIndent(20);
        stamp.setWidth(100);
        stamp.setHeight(200);

        assertEquals(10, stamp.getXIndent(), 0.01);
        assertEquals(20, stamp.getYIndent(), 0.01);
        assertEquals(100, stamp.getWidth(), 0.01);
        assertEquals(200, stamp.getHeight(), 0.01);
    }

    // ==================== Feature 6: TextFormattingOptions ====================

    @Test
    public void testTextFormattingOptionsDefaults() {
        TextFormattingOptions opts = new TextFormattingOptions();
        assertEquals(0, opts.getSubsequentLinesIndent(), 0.01);
        assertEquals(0, opts.getFirstLineIndent(), 0.01);
        assertEquals(TextFormattingOptions.LineSpacingMode.FontSize, opts.getLineSpacing());
        assertEquals(TextFormattingOptions.WordWrapMode.ByWords, opts.getWrapMode());
    }

    @Test
    public void testTextFormattingOptionsSetters() {
        TextFormattingOptions opts = new TextFormattingOptions();
        opts.setSubsequentLinesIndent(20);
        opts.setFirstLineIndent(10);
        opts.setLineSpacing(TextFormattingOptions.LineSpacingMode.Proportional);
        opts.setWrapMode(TextFormattingOptions.WordWrapMode.ByCharacter);

        assertEquals(20, opts.getSubsequentLinesIndent(), 0.01);
        assertEquals(10, opts.getFirstLineIndent(), 0.01);
        assertEquals(TextFormattingOptions.LineSpacingMode.Proportional, opts.getLineSpacing());
        assertEquals(TextFormattingOptions.WordWrapMode.ByCharacter, opts.getWrapMode());
    }

    @Test
    public void testTextFormattingOptionsOnTextState() {
        TextState state = new TextState();
        assertNull(state.getFormattingOptions());

        TextFormattingOptions opts = new TextFormattingOptions();
        opts.setSubsequentLinesIndent(20);
        state.setFormattingOptions(opts);

        assertSame(opts, state.getFormattingOptions());
        assertEquals(20, state.getFormattingOptions().getSubsequentLinesIndent(), 0.01);
    }

    @Test
    public void testTextFormattingOptionsConstructor() {
        TextFormattingOptions opts = new TextFormattingOptions(TextFormattingOptions.WordWrapMode.ByCharacter);
        assertEquals(TextFormattingOptions.WordWrapMode.ByCharacter, opts.getWrapMode());
    }

    // ==================== Feature 7: Printing Package ====================

    @Test
    public void testPdfPrinterSettings() {
        PdfPrinterSettings settings = new PdfPrinterSettings();
        assertNull(settings.getPrinterName());
        assertFalse(settings.isPrintToFile());
        assertEquals(1, settings.getCopies());
        assertEquals(0, settings.getFromPage());
        assertEquals(0, settings.getToPage());
        assertEquals(PdfPrintRange.AllPages, settings.getPrintRange());
        assertNotNull(settings.getDefaultPageSettings());

        settings.setPrinterName("Test Printer");
        settings.setPrintToFile(true);
        settings.setPrintFileName("output.ps");
        settings.setCopies((short) 3);
        settings.setFromPage(1);
        settings.setToPage(5);
        settings.setPrintRange(PdfPrintRange.SomePages);
        settings.setDuplex(DuplexKind.Horizontal);

        assertEquals("Test Printer", settings.getPrinterName());
        assertTrue(settings.isPrintToFile());
        assertEquals("output.ps", settings.getPrintFileName());
        assertEquals(3, settings.getCopies());
        assertEquals(1, settings.getFromPage());
        assertEquals(5, settings.getToPage());
        assertEquals(PdfPrintRange.SomePages, settings.getPrintRange());
        assertEquals(DuplexKind.Horizontal, settings.getDuplex());
    }

    @Test
    public void testPrintPageSettings() {
        PrintPageSettings ps = new PrintPageSettings();
        assertNotNull(ps.getPaperSize());
        assertNotNull(ps.getMargins());
        assertFalse(ps.isLandscape());
        assertTrue(ps.isColor());

        ps.setPaperSize(PrintPaperSizes.Letter);
        ps.setLandscape(true);
        ps.setColor(false);
        ps.setMargins(new PrinterMargins(50, 50, 50, 50));

        assertEquals("Letter", ps.getPaperSize().getName());
        assertEquals(850, ps.getPaperSize().getWidth());
        assertTrue(ps.isLandscape());
        assertFalse(ps.isColor());
        assertEquals(50, ps.getMargins().getLeft());
    }

    @Test
    public void testPrintPaperSize() {
        PrintPaperSize size = new PrintPaperSize("A4", 827, 1169);
        assertEquals("A4", size.getName());
        assertEquals(827, size.getWidth());
        assertEquals(1169, size.getHeight());
        assertEquals(PrinterPaperKind.Custom, size.getKind());
    }

    @Test
    public void testPrintPaperSizesConstants() {
        assertNotNull(PrintPaperSizes.A4);
        assertEquals(827, PrintPaperSizes.A4.getWidth());
        assertEquals(1169, PrintPaperSizes.A4.getHeight());

        assertNotNull(PrintPaperSizes.Letter);
        assertEquals(850, PrintPaperSizes.Letter.getWidth());
        assertEquals(1100, PrintPaperSizes.Letter.getHeight());

        assertNotNull(PrintPaperSizes.Legal);
        assertNotNull(PrintPaperSizes.A3);
        assertNotNull(PrintPaperSizes.A5);
        assertNotNull(PrintPaperSizes.Tabloid);
    }

    @Test
    public void testPrinterMargins() {
        PrinterMargins m = new PrinterMargins(10, 20, 30, 40);
        assertEquals(10, m.getLeft());
        assertEquals(20, m.getRight());
        assertEquals(30, m.getTop());
        assertEquals(40, m.getBottom());
    }

    @Test
    public void testPdfPrinterResolution() {
        PdfPrinterResolution res = new PdfPrinterResolution();
        assertEquals(150, res.getX());
        assertEquals(150, res.getY());
        assertEquals(PdfPrinterResolutionKind.Custom, res.getKind());

        res.setX(300);
        res.setY(300);
        res.setKind(PdfPrinterResolutionKind.High);
        assertEquals(300, res.getX());
        assertEquals(PdfPrinterResolutionKind.High, res.getKind());
    }

    @Test
    public void testPdfViewerProperties() throws IOException {
        PdfViewer viewer = new PdfViewer();
        assertTrue(viewer.isAutoResize());
        assertTrue(viewer.isAutoRotate());
        assertTrue(viewer.isPrintPageDialog());
        assertFalse(viewer.isPrintAsGrayscale());
        assertEquals(150, viewer.getResolution());

        viewer.setAutoResize(false);
        viewer.setAutoRotate(false);
        viewer.setPrintPageDialog(false);
        viewer.setPrintAsGrayscale(true);
        viewer.setResolution(300);

        assertFalse(viewer.isAutoResize());
        assertFalse(viewer.isAutoRotate());
        assertFalse(viewer.isPrintPageDialog());
        assertTrue(viewer.isPrintAsGrayscale());
        assertEquals(300, viewer.getResolution());

        viewer.close();
    }

    @Test
    public void testPdfViewerBindPdf() throws IOException {
        Document doc = new Document();
        doc.getPages().add();

        PdfViewer viewer = new PdfViewer();
        viewer.bindPdf(doc);

        // Should not throw
        viewer.close();
    }

    @Test
    public void testInstalledPrinters() {
        // Just verify it doesn't throw
        java.util.List<String> printers = PdfPrinterSettings.getInstalledPrinters();
        assertNotNull(printers);
    }

    // ==================== AnnotationType enum ====================

    @Test
    public void testAnnotationType() {
        assertEquals("Text", AnnotationType.Text.getSubtype());
        assertEquals("Highlight", AnnotationType.Highlight.getSubtype());
        assertEquals("Redact", AnnotationType.Redact.getSubtype());

        assertEquals(AnnotationType.Text, AnnotationType.fromSubtype("Text"));
        assertEquals(AnnotationType.Highlight, AnnotationType.fromSubtype("Highlight"));
        assertNull(AnnotationType.fromSubtype("UnknownType"));
    }
}
