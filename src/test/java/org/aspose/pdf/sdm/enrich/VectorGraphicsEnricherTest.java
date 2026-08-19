package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * {@link VectorGraphicsEnricher}: vector-drawn page regions (charts, shapes)
 * must survive into STRUCTURAL HTML as rasterized {@code <img>} figures —
 * before this pass they rendered as 0 pixels (only text labels remained). A
 * text-heavy region (page frame around prose) must be left alone.
 */
public class VectorGraphicsEnricherTest {

    /** A bar-chart-like cluster: filled bars + a baseline axis. */
    private static void chart(Page page) {
        StringBuilder cs = new StringBuilder("q\n0.2 0.4 0.8 rg\n");
        int[] heights = {60, 90, 120, 75};
        for (int i = 0; i < heights.length; i++) {
            cs.append(100 + i * 40).append(" 400 30 ").append(heights[i]).append(" re f\n");
        }
        cs.append("0 0 0 RG\n2 w\n95 398 m 350 398 l S\n"); // x-axis
        cs.append("Q\n");
        page.appendToContentStream(cs.toString().getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void text(Page page, String s, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(s);
        tf.setPosition(new Position(x, y));
        tf.getTextState().setFontSize(12);
        new TextBuilder(page).appendText(tf);
    }

    private static String html(Document doc, HtmlOutputMode mode) throws IOException {
        HtmlSaveOptions o = new HtmlSaveOptions();
        o.setOutputMode(mode);
        return org.aspose.pdf.testutil.HtmlText.of(doc, o);
    }

    /** The chart region becomes a PNG figure; body copy far away is untouched. */
    @Test
    public void chartRegionBecomesFigure() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        chart(page);
        text(page, "Report body text outside the chart.", 100, 720);

        String html = html(doc, HtmlOutputMode.STRUCTURAL);
        assertTrue(html.contains("data:image/png"),
                "the vector chart must be rasterized into an <img>: " + head(html));
        assertTrue(html.contains("Report body text outside the chart."),
                "body text outside the region must be kept");
        assertFalse(html.contains("data-render-hint=\"vector\""),
                "the empty vector placeholders must be replaced by the raster");
    }

    /** A label inside the chart is baked into the crop: gone from the flow, kept as alt. */
    @Test
    public void chartLabelIsBakedIntoTheRaster() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        chart(page);
        text(page, "LBL42", 105, 450); // inside the plot area, like an in-chart label
        text(page, "Body prose stays.", 100, 720);

        String html = html(doc, HtmlOutputMode.STRUCTURAL);
        assertTrue(html.contains("data:image/png"), "chart figure expected");
        assertFalse(html.contains(">LBL42"),
                "the in-chart label must not remain as flow text (it is baked into the crop)");
        assertTrue(html.contains("LBL42"),
                "the label text must be preserved as the figure's alt");
        assertTrue(html.contains("Body prose stays."), "body prose must remain");
    }

    /** A stroked page frame around dense prose must NOT be rasterized (prose stays text). */
    @Test
    public void textHeavyFrameIsLeftAlone() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        page.appendToContentStream(
                "q\n1 w\n0 0 0 RG\n40 40 520 740 re S\nQ\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < 26; i++) {
            text(page, "Line " + i + " of the body copy filling the framed page area fully.",
                    60, 740 - i * 26);
        }

        String html = html(doc, HtmlOutputMode.STRUCTURAL);
        assertFalse(html.contains("data:image/png"),
                "a text-heavy framed page must not be swallowed into a raster");
        assertTrue(html.contains("Line 25 of the body copy"), "all prose must remain");
    }

    /** Fixed layout gets the vector underlay image; a pure-text page does not. */
    @Test
    public void fixedLayoutGetsVectorUnderlayOnlyWhenInked() throws IOException {
        Document withChart = new Document();
        chart(withChart.getPages().add());
        String html = html(withChart, HtmlOutputMode.FIXED_LAYOUT);
        assertTrue(html.contains("class=\"v\""),
                "fixed layout must emit the vector underlay for vector content");

        Document textOnly = new Document();
        text(textOnly.getPages().add(), "Just text.", 100, 700);
        String htmlText = html(textOnly, HtmlOutputMode.FIXED_LAYOUT);
        assertFalse(htmlText.contains("class=\"v\""),
                "a text-only page must not carry a (blank) underlay");
    }

    /**
     * A rotated page (/Rotate 90/270) must still rasterize its vector region — the
     * renderer draws it upright, and the crop maps the content-space region through
     * the same rotation. Before this the enricher skipped rotated pages, leaving
     * every rotated chart/plan BLANK in STRUCTURAL.
     */
    @Test
    public void rotatedPageStillRasterizes() throws IOException {
        for (org.aspose.pdf.Rotation rot : new org.aspose.pdf.Rotation[]{
                org.aspose.pdf.Rotation.on90, org.aspose.pdf.Rotation.on270}) {
            Document doc = new Document();
            Page page = doc.getPages().add();
            page.setRotate(rot);
            chart(page);
            String html = html(doc, HtmlOutputMode.STRUCTURAL);
            assertTrue(html.contains("data:image/png"),
                    "rotated (" + rot + ") vector page must still produce a raster figure: "
                            + head(html));
            assertFalse(html.contains("data-render-hint=\"vector\""),
                    "vector placeholders must be replaced by the raster on a rotated page");
        }
    }

    /** Kill switch: option off = old behaviour (placeholders, no raster). */
    @Test
    public void optionDisablesRasterization() throws IOException {
        Document doc = new Document();
        chart(doc.getPages().add());
        HtmlSaveOptions o = new HtmlSaveOptions();
        o.setOutputMode(HtmlOutputMode.STRUCTURAL);
        o.setRasterizeVectorGraphics(false);
        assertFalse(org.aspose.pdf.testutil.HtmlText.of(doc, o).contains("data:image/png"),
                "setRasterizeVectorGraphics(false) must disable the raster figures");
    }

    private static String head(String s) {
        return s.length() > 400 ? s.substring(0, 400) : s;
    }
}
