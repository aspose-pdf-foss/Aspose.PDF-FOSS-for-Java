package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug H — {@link TextBuilder} must encode the text-show payload using
 * WinAnsiEncoding (CP1252) and ensure the referenced font dict carries
 * {@code /Encoding /WinAnsiEncoding}. Before the fix the payload was
 * encoded as US-ASCII, mapping every non-ASCII character (em-dash, bullet,
 * middle dot, …) to a literal {@code '?'} even when the font supported it.
 */
class TextBuilderWinAnsiEncodingTest {

    @TempDir Path tempDir;

    private static byte[] contentStreamBytes(Page page) throws IOException {
        COSBase contents = page.getCOSDictionary().get(COSName.of("Contents"));
        if (contents instanceof COSObjectReference) {
            contents = ((COSObjectReference) contents).dereference();
        }
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        if (contents instanceof COSStream) {
            all.write(((COSStream) contents).getDecodedData());
        } else if (contents instanceof COSArray) {
            COSArray arr = (COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                COSBase e = arr.get(i);
                if (e instanceof COSObjectReference) e = ((COSObjectReference) e).dereference();
                if (e instanceof COSStream) all.write(((COSStream) e).getDecodedData());
            }
        }
        return all.toByteArray();
    }

    private static boolean containsByte(byte[] data, int target) {
        for (byte b : data) if ((b & 0xFF) == target) return true;
        return false;
    }

    private static Page buildPageWith(Document doc, String text) throws IOException {
        Page page = doc.getPages().add();
        TextFragment f = new TextFragment(text);
        f.getTextState().setFontName("Helvetica");
        f.getTextState().setFontSize(12);
        f.getTextState().setForegroundColor(Color.BLACK);
        f.setPosition(new Position(50, 700));
        new TextBuilder(page).appendText(f);
        return page;
    }

    @Test
    @DisplayName("ASCII-only text is encoded byte-for-byte (no behaviour change)")
    void appendText_asciiOnly_unchanged() throws IOException {
        try (Document doc = new Document()) {
            Page page = buildPageWith(doc, "Hello world");
            byte[] cs = contentStreamBytes(page);
            String text = new String(cs, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("(Hello world) Tj"),
                    "ASCII text must serialise verbatim");
            // No bytes outside ASCII range.
            for (byte b : cs) {
                assertTrue((b & 0xFF) < 0x80,
                        "ASCII-only input must produce ASCII-only bytes");
            }
        }
    }

    @Test
    @DisplayName("Em-dash U+2014 → CP1252 byte 0x97")
    void appendText_emDash_encodesAsWinAnsi() throws IOException {
        try (Document doc = new Document()) {
            Page page = buildPageWith(doc, "a—b");
            byte[] cs = contentStreamBytes(page);
            assertTrue(containsByte(cs, 0x97),
                    "em-dash must be encoded as CP1252 byte 0x97");
        }
    }

    @Test
    @DisplayName("Bullet U+2022 → CP1252 byte 0x95")
    void appendText_bullet_encodesAsWinAnsi() throws IOException {
        try (Document doc = new Document()) {
            Page page = buildPageWith(doc, "x•y");
            byte[] cs = contentStreamBytes(page);
            assertTrue(containsByte(cs, 0x95),
                    "bullet must be encoded as CP1252 byte 0x95");
        }
    }

    @Test
    @DisplayName("Right arrow U+2192 (not in CP1252) → graceful '?' fallback")
    void appendText_rightArrow_encodesAsWinAnsi() throws IOException {
        try (Document doc = new Document()) {
            Page page = buildPageWith(doc, "go→there");
            byte[] cs = contentStreamBytes(page);
            // '?' = 0x3F. Should be present (the arrow becomes ?).
            assertTrue(containsByte(cs, 0x3F),
                    "unmappable character must fall back to '?' rather than crash");
        }
    }

    @Test
    @DisplayName("Middle dot U+00B7 → CP1252 byte 0xB7")
    void appendText_middleDot_encodesAsWinAnsi() throws IOException {
        try (Document doc = new Document()) {
            Page page = buildPageWith(doc, "a·b");
            byte[] cs = contentStreamBytes(page);
            assertTrue(containsByte(cs, 0xB7),
                    "middle dot must be encoded as CP1252 byte 0xB7");
        }
    }

    @Test
    @DisplayName("Newly-created font dict has /Encoding /WinAnsiEncoding")
    void appendText_createsFontWithWinAnsiEncoding() throws IOException {
        Path out = tempDir.resolve("font-enc.pdf");
        try (Document doc = new Document()) {
            buildPageWith(doc, "Hello");
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary fonts = r.getPages().get(1).ensureResources().getFonts();
            assertNotNull(fonts);
            boolean found = false;
            for (COSName k : fonts.keySet()) {
                COSBase v = fonts.get(k);
                if (v instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
                    v = ((org.aspose.pdf.engine.cos.COSObjectReference) v).dereference();
                }
                if (v instanceof COSDictionary) {
                    COSDictionary f = (COSDictionary) v;
                    if ("WinAnsiEncoding".equals(f.getNameAsString("Encoding"))) {
                        found = true;
                        break;
                    }
                }
            }
            assertTrue(found,
                    "at least one /Encoding /WinAnsiEncoding font dict must be registered");
        }
    }

    @Test
    @DisplayName("Existing font dict without /Encoding gets /WinAnsiEncoding added")
    void appendText_existingFontMissingEncoding_getsWinAnsiAdded() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            // Pre-attach a Helvetica font dict WITHOUT /Encoding.
            COSDictionary fonts = new COSDictionary();
            COSDictionary helv = new COSDictionary();
            helv.set(COSName.of("Type"), COSName.of("Font"));
            helv.set(COSName.of("Subtype"), COSName.of("Type1"));
            helv.set(COSName.of("BaseFont"), COSName.of("Helvetica"));
            fonts.set("F1", helv);
            page.ensureResources().getCOSDictionary().set(COSName.of("Font"), fonts);

            // Now ask TextBuilder to draw using "Helvetica" — it'll reuse F1
            // and supply the missing /Encoding.
            TextFragment f = new TextFragment("Hi");
            f.getTextState().setFontName("Helvetica");
            f.getTextState().setFontSize(12);
            f.setPosition(new Position(50, 700));
            new TextBuilder(page).appendText(f);

            assertEquals("WinAnsiEncoding", helv.getNameAsString("Encoding"),
                    "TextBuilder must add /Encoding to existing font dict that lacks one");
        }
    }

    @Test
    @DisplayName("Existing font with non-WinAnsi /Encoding is preserved")
    void appendText_existingFontWithDifferentEncoding_preserved() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            COSDictionary fonts = new COSDictionary();
            COSDictionary helv = new COSDictionary();
            helv.set(COSName.of("Type"), COSName.of("Font"));
            helv.set(COSName.of("Subtype"), COSName.of("Type1"));
            helv.set(COSName.of("BaseFont"), COSName.of("Helvetica"));
            helv.set(COSName.of("Encoding"), COSName.of("MacRomanEncoding"));
            fonts.set("F1", helv);
            page.ensureResources().getCOSDictionary().set(COSName.of("Font"), fonts);

            TextFragment f = new TextFragment("Hi");
            f.getTextState().setFontName("Helvetica");
            f.getTextState().setFontSize(12);
            f.setPosition(new Position(50, 700));
            new TextBuilder(page).appendText(f);

            assertEquals("MacRomanEncoding", helv.getNameAsString("Encoding"),
                    "TextBuilder must NOT overwrite a pre-existing /Encoding");
        }
    }
}
