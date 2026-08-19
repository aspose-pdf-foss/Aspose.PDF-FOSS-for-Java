package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * IR Stage 3 fidelity: a filled rectangle that encloses a text block becomes
 * that block's CSS background-colour (reflow-safe), but only when it contrasts
 * with the text — a black bar over black text must NOT bury it.
 */
public class FillBackgroundEnricherTest {

    private static void filledRect(Page page, String rgbFill, int x, int y, int w, int h) {
        String cs = "q\n" + rgbFill + " rg\n" + x + " " + y + " " + w + " " + h + " re f\nQ\n";
        page.appendToContentStream(cs.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void coloredText(Page page, String text, double x, double y, Color color)
            throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        tf.getTextState().setFontSize(14);
        tf.getTextState().setForegroundColor(color);
        new TextBuilder(page).appendText(tf);
    }

    private static String structuralHtml(Document doc) throws IOException {
        HtmlSaveOptions o = new HtmlSaveOptions();
        o.setOutputMode(HtmlOutputMode.STRUCTURAL);
        return org.aspose.pdf.testutil.HtmlText.of(doc, o);
    }

    /** Navy fill behind white text → the block carries background-color:#000045. */
    @Test
    public void colouredBoxBecomesBlockBackground() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        filledRect(page, "0 0 0.27", 60, 690, 300, 40); // navy
        coloredText(page, "WHITE ON NAVY", 70, 700, Color.getWhite());

        String html = structuralHtml(doc);
        assertTrue(html.contains("background-color:#000045"),
                "navy fill enclosing white text becomes the block background: " + html);
    }

    /** Black fill behind black text → NO background (contrast guard). */
    @Test
    public void blackBarOverBlackTextIsNotBackground() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        filledRect(page, "0 0 0", 60, 690, 300, 40); // black
        coloredText(page, "BLACK ON BLACK", 70, 700, Color.getBlack());

        assertFalse(structuralHtml(doc).contains("background-color"),
                "a black fill over black text must not become a background");
    }
}
