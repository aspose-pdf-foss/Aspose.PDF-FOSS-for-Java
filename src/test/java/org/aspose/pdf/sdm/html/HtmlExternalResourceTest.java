package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import javax.imageio.ImageIO;

import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * External-resource resolution for {@link HtmlSdmReader}: relative {@code <img>}
 * references and {@code <link rel="stylesheet">} are loaded from disk relative to
 * the base URI, and undecorated (unresolved) references degrade gracefully.
 */
public class HtmlExternalResourceTest {

    /** A relative {@code <img src>} is loaded from the base directory and embedded. */
    @Test
    public void relativeImageLoadedFromBaseDir(@TempDir File dir) throws Exception {
        File png = new File(dir, "pic.png");
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", png);

        // Block-level <img> is the resource-bearing image path (inline <img> inside
        // a <p> degrades to alt text — a separate, pre-existing limitation).
        String html = "<html><body><img src=\"pic.png\"></body></html>";
        HtmlSdmReader reader = new HtmlSdmReader();
        HtmlReadOptions ro = new HtmlReadOptions();
        SdmDocument sdm = reader.read(html, dir.getAbsolutePath(), ro);

        Resource img = sdm.getResources().get("img-1");
        assertNotNull(img, "image resource created");
        assertEquals(Resource.Kind.IMAGE, img.getKind());
        assertTrue(img.getBytes().length > 0, "image bytes loaded from disk");
        assertEquals("image/png", img.getMime());
        assertTrue(reader.getReport().getExternalImages().isEmpty(),
                "resolved image is not recorded as unresolved");
    }

    /** An external {@code <link rel=stylesheet>} is fetched and its rules cascade. */
    @Test
    public void linkedStylesheetLoadedAndApplied(@TempDir File dir) throws Exception {
        Files.write(new File(dir, "style.css").toPath(),
                ".hi{color:#ff0000}".getBytes(StandardCharsets.UTF_8));

        String html = "<html><head>"
                + "<link rel=\"stylesheet\" href=\"style.css\">"
                + "</head><body><p class=\"hi\">t</p></body></html>";
        HtmlSdmReader reader = new HtmlSdmReader();
        SdmDocument sdm = reader.read(html, dir.getAbsolutePath(), new HtmlReadOptions());

        Paragraph p = (Paragraph) sdm.getChildren().get(0);
        assertEquals(0xFFFF0000, runWithText(p.getInline(), "t").getStyle().getColor(),
                "colour from the linked sheet applied");
        assertTrue(reader.getReport().getExternalStylesheets().isEmpty(),
                "stylesheet resolved, not recorded as unresolved");
    }

    /** A missing relative image degrades to an empty placeholder and is reported. */
    @Test
    public void missingImageDegradesGracefully(@TempDir File dir) {
        String html = "<html><body><img src=\"nope.png\"></body></html>";
        HtmlSdmReader reader = new HtmlSdmReader();
        SdmDocument sdm = reader.read(html, dir.getAbsolutePath(), new HtmlReadOptions());

        Resource img = sdm.getResources().get("img-1");
        assertNotNull(img, "node preserved even when the source is missing");
        assertEquals(0, img.getBytes().length, "no bytes for a missing source");
        assertTrue(reader.getReport().getExternalImages().contains("nope.png"),
                "unresolved reference recorded on the report");
    }

    /** Network references stay unresolved unless allowNetwork is set (offline default). */
    @Test
    public void networkImageBlockedByDefault() {
        String html = "<html><body><img src=\"http://example.invalid/x.png\"></body></html>";
        HtmlSdmReader reader = new HtmlSdmReader();
        SdmDocument sdm = reader.read(html, null, new HtmlReadOptions());

        assertEquals(0, sdm.getResources().get("img-1").getBytes().length,
                "no network fetch by default");
        assertTrue(reader.getReport().getExternalImages()
                .contains("http://example.invalid/x.png"));
    }

    private static Run runWithText(List<SdmInline> inlines, String text) {
        for (SdmInline in : inlines) {
            if (in instanceof Run && ((Run) in).getText().equals(text)) {
                return (Run) in;
            }
        }
        throw new AssertionError("no run with text '" + text + "' in " + inlines);
    }
}
