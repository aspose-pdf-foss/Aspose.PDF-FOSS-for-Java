package org.aspose.pdf.tests;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextParagraph;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TextBuilder} and {@link TextParagraph}.
 */
public class TextBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    public void testAppendTextFragment() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment fragment = new TextFragment("Hello World");
        fragment.getTextState().setFontName("Helvetica");
        fragment.getTextState().setFontSize(12);
        fragment.setPosition(new Position(100, 700));

        TextBuilder builder = new TextBuilder(page);
        builder.appendText(fragment);

        // Save and reload
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        byte[] pdfBytes = baos.toByteArray();
        assertTrue(pdfBytes.length > 0, "PDF output should not be empty");

        // Reload and verify text is present in the content stream
        Document reloaded = new Document(new ByteArrayInputStream(pdfBytes));
        Page reloadedPage = reloaded.getPages().get(1);
        assertNotNull(reloadedPage);

        // Verify content stream contains the text
        String content = new String(getPageContentBytes(reloadedPage), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(content.contains("Hello World"), "Content stream should contain the text");
        assertTrue(content.contains("BT"), "Content stream should contain BT operator");
        assertTrue(content.contains("ET"), "Content stream should contain ET operator");
        assertTrue(content.contains("Tf"), "Content stream should contain Tf operator");

        reloaded.close();
    }

    @Test
    public void testAppendParagraph() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextParagraph paragraph = new TextParagraph();
        paragraph.setPosition(new Position(72, 750));

        TextFragment line1 = new TextFragment("First line");
        line1.getTextState().setFontName("Helvetica");
        line1.getTextState().setFontSize(14);
        paragraph.appendLine(line1);

        TextFragment line2 = new TextFragment("Second line");
        line2.getTextState().setFontName("Helvetica");
        line2.getTextState().setFontSize(14);
        paragraph.appendLine(line2);

        TextFragment line3 = new TextFragment("Third line");
        line3.getTextState().setFontName("Helvetica");
        line3.getTextState().setFontSize(14);
        paragraph.appendLine(line3);

        TextBuilder builder = new TextBuilder(page);
        builder.appendParagraph(paragraph);

        // Save and reload
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        byte[] pdfBytes = baos.toByteArray();
        assertTrue(pdfBytes.length > 0, "PDF output should not be empty");

        Document reloaded = new Document(new ByteArrayInputStream(pdfBytes));
        Page reloadedPage = reloaded.getPages().get(1);
        String content = new String(getPageContentBytes(reloadedPage), java.nio.charset.StandardCharsets.US_ASCII);

        assertTrue(content.contains("First line"), "Content should contain first line");
        assertTrue(content.contains("Second line"), "Content should contain second line");
        assertTrue(content.contains("Third line"), "Content should contain third line");
        // Should have only one BT/ET pair for the paragraph
        assertTrue(content.contains("BT"), "Content should contain BT");
        assertTrue(content.contains("ET"), "Content should contain ET");

        reloaded.close();
    }

    @Test
    public void testFontRegistration() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment fragment = new TextFragment("Test font registration");
        fragment.getTextState().setFontName("Times-Roman");
        fragment.getTextState().setFontSize(10);
        fragment.setPosition(new Position(50, 600));

        TextBuilder builder = new TextBuilder(page);
        builder.appendText(fragment);

        // Verify font is in page resources
        Resources resources = page.getResources();
        assertNotNull(resources, "Page should have resources after appending text");

        COSDictionary fonts = resources.getFonts();
        assertNotNull(fonts, "Page resources should have a /Font dictionary");
        assertFalse(fonts.isEmpty(), "Font dictionary should not be empty");

        // Find the font entry for Times-Roman
        boolean foundTimesRoman = false;
        for (COSName key : fonts.keySet()) {
            org.aspose.pdf.engine.cos.COSBase val = fonts.get(key);
            if (val instanceof COSDictionary) {
                COSDictionary fontDict = (COSDictionary) val;
                String baseFontName = fontDict.getNameAsString("BaseFont");
                if ("Times-Roman".equals(baseFontName)) {
                    foundTimesRoman = true;
                    assertEquals("Font", fontDict.getNameAsString("Type"));
                    assertEquals("Type1", fontDict.getNameAsString("Subtype"));
                    assertEquals("WinAnsiEncoding", fontDict.getNameAsString("Encoding"));
                }
            }
        }
        assertTrue(foundTimesRoman, "Font dictionary should contain Times-Roman");

        doc.close();
    }

    @Test
    public void testMultipleFontsRegistration() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextBuilder builder = new TextBuilder(page);

        TextFragment f1 = new TextFragment("Helvetica text");
        f1.getTextState().setFontName("Helvetica");
        f1.getTextState().setFontSize(12);
        f1.setPosition(new Position(50, 700));
        builder.appendText(f1);

        TextFragment f2 = new TextFragment("Courier text");
        f2.getTextState().setFontName("Courier");
        f2.getTextState().setFontSize(12);
        f2.setPosition(new Position(50, 680));
        builder.appendText(f2);

        // Same font again should reuse
        TextFragment f3 = new TextFragment("More Helvetica");
        f3.getTextState().setFontName("Helvetica");
        f3.getTextState().setFontSize(14);
        f3.setPosition(new Position(50, 660));
        builder.appendText(f3);

        COSDictionary fonts = page.getResources().getFonts();
        assertNotNull(fonts);
        // Should have exactly 2 font entries (Helvetica and Courier), not 3
        assertEquals(2, fonts.size(), "Should have 2 distinct fonts registered");

        doc.close();
    }

    @Test
    public void testSpecialCharacterEscaping() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment fragment = new TextFragment("Hello (world) \\ test");
        fragment.getTextState().setFontName("Helvetica");
        fragment.getTextState().setFontSize(12);
        fragment.setPosition(new Position(50, 700));

        TextBuilder builder = new TextBuilder(page);
        builder.appendText(fragment);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();

        // Verify the escaped content in the raw PDF bytes
        String pdfContent = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(pdfContent.contains("\\(world\\)"), "Parentheses should be escaped");
        assertTrue(pdfContent.contains("\\\\"), "Backslash should be escaped");
    }

    @Test
    public void testAppendTextFragmentPreservesTextStateOperators() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment fragment = new TextFragment("H2");
        fragment.getTextState().setFontName("Helvetica");
        fragment.getTextState().setFontSize(12);
        fragment.getTextState().setCharacterSpacing(1.5);
        fragment.getTextState().setWordSpacing(3.0);
        fragment.getTextState().setHorizontalScaling(80.0);
        fragment.getTextState().setRenderingMode(2);
        fragment.getTextState().setTextRise(6.0);
        fragment.setPosition(new Position(72, 700));

        TextBuilder builder = new TextBuilder(page);
        builder.appendText(fragment);

        String content = new String(getPageContentBytes(page), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(content.contains("1.5 Tc"), "Content stream should preserve character spacing");
        assertTrue(content.contains("3 Tw"), "Content stream should preserve word spacing");
        assertTrue(content.contains("80 Tz"), "Content stream should preserve horizontal scaling");
        assertTrue(content.contains("2 Tr"), "Content stream should preserve rendering mode");
        assertTrue(content.contains("6 Ts"), "Content stream should preserve text rise");

        doc.close();
    }

    @Test
    public void testParagraphLineSpacing() {
        TextParagraph paragraph = new TextParagraph();
        assertEquals(1.2, paragraph.getLineSpacing(), 0.001, "Default line spacing should be 1.2");

        paragraph.setLineSpacing(1.5);
        assertEquals(1.5, paragraph.getLineSpacing(), 0.001);

        assertThrows(IllegalArgumentException.class, () -> paragraph.setLineSpacing(0));
        assertThrows(IllegalArgumentException.class, () -> paragraph.setLineSpacing(-1));
    }

    @Test
    public void testNullArguments() {
        Document doc = new Document();
        assertThrows(IllegalArgumentException.class, () -> new TextBuilder(null));

        Page page;
        try {
            page = doc.getPages().add();
        } catch (IOException e) {
            fail("Should not throw");
            return;
        }
        TextBuilder builder = new TextBuilder(page);
        assertThrows(IllegalArgumentException.class, () -> builder.appendText((TextFragment) null));
        assertThrows(IllegalArgumentException.class, () -> builder.appendParagraph(null));

        TextParagraph paragraph = new TextParagraph();
        assertThrows(IllegalArgumentException.class, () -> paragraph.appendLine((TextFragment) null));
    }

    /**
     * Helper to extract raw content bytes from a page.
     */
    private byte[] getPageContentBytes(Page page) throws IOException {
        org.aspose.pdf.engine.cos.COSBase raw = page.getRawContents();
        if (raw instanceof org.aspose.pdf.engine.cos.COSStream) {
            return ((org.aspose.pdf.engine.cos.COSStream) raw).getDecodedData();
        }
        if (raw instanceof org.aspose.pdf.engine.cos.COSArray) {
            org.aspose.pdf.engine.cos.COSArray arr = (org.aspose.pdf.engine.cos.COSArray) raw;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int i = 0; i < arr.size(); i++) {
                org.aspose.pdf.engine.cos.COSBase item = arr.get(i);
                if (item instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
                    item = ((org.aspose.pdf.engine.cos.COSObjectReference) item).dereference();
                }
                if (item instanceof org.aspose.pdf.engine.cos.COSStream) {
                    baos.write(((org.aspose.pdf.engine.cos.COSStream) item).getDecodedData());
                    baos.write('\n');
                }
            }
            return baos.toByteArray();
        }
        return new byte[0];
    }
}
