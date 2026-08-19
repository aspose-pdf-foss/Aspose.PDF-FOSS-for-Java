package org.aspose.pdf.sdm.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.operators.SetTextMatrix;
import org.aspose.pdf.operators.ShowText;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SourceRef;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;

/**
 * IR Stage 4 PART 2 — {@link SdmPdfLayout} asserted-geometry tests: positions,
 * wrapping to content width, pagination + heading keep-together, table columns +
 * THead repeat, the [TXT] invariant (PDF text == SDM text), the SDM&rarr;PDF&rarr;SDM
 * loop-back, and LayoutReport entries.
 */
public class SdmPdfLayoutTest {

    private static final PageSetup LETTER = PageSetup.letter(); // 612x792, 72 margins

    private static SdmDocument doc(org.aspose.pdf.sdm.SdmBlock... blocks) {
        SdmDocument d = new SdmDocument();
        for (org.aspose.pdf.sdm.SdmBlock b : blocks) {
            d.getChildren().add(b);
        }
        return d;
    }

    private static Paragraph para(String text) {
        Paragraph p = new Paragraph();
        p.getInline().add(new Run(text, null));
        return p;
    }

    private static Heading heading(int level, String text) {
        Heading h = new Heading(level);
        h.getInline().add(new Run(text, null));
        return h;
    }

    // ---- [TXT] invariant + loop-back --------------------------------------

    @Test
    public void txtInvariant_PdfTextEqualsSdmText() throws Exception {
        SdmDocument sdm = doc(heading(1, "The Title"), para("First paragraph body."),
                para("Second paragraph here."));
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        String pdfText = allText(r.getDocument()).replaceAll("\\s+", " ").trim();
        String sdmText = "The Title First paragraph body. Second paragraph here.";
        assertEquals(sdmText, pdfText);
    }

    @Test
    public void loopBack_Stage1ReaderRecognizesStructure() throws Exception {
        SdmDocument sdm = doc(heading(1, "H"), para("p one"), para("p two"), para("p three"));
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        org.aspose.pdf.sdm.reader.PdfSdmReader.Result back =
                new org.aspose.pdf.sdm.reader.PdfSdmReader().read(r.getDocument());
        // the shallow reader projects text blocks; at least the 4 text blocks survive
        long textBlocks = back.getSdm().getChildren().stream()
                .filter(b -> b.getType() == org.aspose.pdf.sdm.SdmNodeType.PARAGRAPH
                        || b.getType() == org.aspose.pdf.sdm.SdmNodeType.HEADING).count();
        assertTrue(textBlocks >= 3, "SDM->PDF->SDM keeps text blocks recognizable, got " + textBlocks);
    }

    // ---- positions ---------------------------------------------------------

    @Test
    public void positions_headingAboveParagraph() throws Exception {
        SdmDocument sdm = doc(heading(1, "Head"), para("body"));
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        List<Drawn> drawn = drawnText(r.getDocument().getPages().get(1));
        Drawn head = find(drawn, "Head");
        Drawn body = find(drawn, "body");
        assertTrue(head.y > body.y, "heading is above the paragraph (y " + head.y + " > " + body.y + ")");
        // heading starts near the content top (792-72=720 minus one heading ascent)
        assertTrue(head.y <= LETTER.getContentTop() && head.y > LETTER.getContentTop() - 40,
                "heading near content top, y=" + head.y);
    }

    @Test
    public void centerAlignHonored() throws Exception {
        Paragraph p = para("centered");
        org.aspose.pdf.sdm.BlockStyle bs = new org.aspose.pdf.sdm.BlockStyle();
        bs.setAlign(org.aspose.pdf.sdm.BlockStyle.Align.CENTER);
        p.setStyle(bs);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(doc(p), LETTER);
        Drawn d = find(drawnText(r.getDocument().getPages().get(1)), "centered");
        double lineWidth = TextLayoutHelper.measureTextWidth("centered", "Helvetica", 12);
        double expectedX = LETTER.getMarginLeft() + (LETTER.getContentWidth() - lineWidth) / 2;
        assertEquals(expectedX, d.x, 1.0);
    }

    // ---- wrapping ----------------------------------------------------------

    @Test
    public void wrapping_noLineExceedsContentWidth() throws Exception {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            longText.append("word").append(i).append(' ');
        }
        SdmPdfLayout.Result r = new SdmPdfLayout().render(doc(para(longText.toString().trim())), LETTER);
        List<Drawn> drawn = drawnText(r.getDocument().getPages().get(1));
        assertTrue(drawn.size() > 1, "long paragraph wrapped to multiple lines");
        for (Drawn d : drawn) {
            double w = TextLayoutHelper.measureTextWidth(d.text, "Helvetica", 12);
            assertTrue(w <= LETTER.getContentWidth() + 0.5,
                    "line '" + d.text + "' width " + w + " <= content " + LETTER.getContentWidth());
        }
    }

    // ---- wide-form auto page widening -------------------------------------

    @Test
    public void wideNowrapTable_widensPage() throws Exception {
        Table t = new Table();
        TableRow row = new TableRow(TableRow.Kind.BODY);
        for (int i = 0; i < 8; i++) {
            TableCell c = new TableCell();
            Paragraph p = new Paragraph();
            p.getInline().add(new Run("Long Label Text " + i + ":", null));
            c.getChildren().add(p);
            c.getAttributes().put("nowrap", Boolean.TRUE);
            row.getCells().add(c);
        }
        t.getRows().add(row);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(doc(t), PageSetup.letter());
        double w = r.getDocument().getPages().get(1).getRect().getWidth();
        assertTrue(w > 612.5, "8 nowrap columns must widen the page beyond letter, got " + w);
    }

    @Test
    public void narrowTable_keepsLetterWidth() throws Exception {
        Table t = new Table();
        TableRow row = new TableRow(TableRow.Kind.BODY);
        for (int i = 0; i < 2; i++) {
            TableCell c = new TableCell();
            Paragraph p = new Paragraph();
            p.getInline().add(new Run("x", null));
            c.getChildren().add(p);
            row.getCells().add(c);
        }
        t.getRows().add(row);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(doc(t), PageSetup.letter());
        double w = r.getDocument().getPages().get(1).getRect().getWidth();
        assertEquals(612.0, w, 0.5, "a narrow table keeps the letter page width");
    }

    // ---- pagination --------------------------------------------------------

    @Test
    public void pagination_overflowGoesToPageTwo() throws Exception {
        List<org.aspose.pdf.sdm.SdmBlock> blocks = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            blocks.add(para("Paragraph number " + i + " with some body text to take vertical space."));
        }
        blocks.add(para("LAST_MARKER_BLOCK"));
        SdmDocument sdm = new SdmDocument();
        sdm.getChildren().addAll(blocks);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        assertTrue(r.getDocument().getPages().getCount() >= 2, "content overflowed to a 2nd page");
        // the last marker block lands on the last page, not the first
        String page1 = pageText(r.getDocument().getPages().get(1));
        assertFalse(page1.contains("LAST_MARKER_BLOCK"), "last block is not on page 1");
        String lastPage = pageText(r.getDocument().getPages().get(r.getDocument().getPages().getCount()));
        assertTrue(lastPage.contains("LAST_MARKER_BLOCK"), "last block on the last page");
    }

    @Test
    public void headingKeepTogether_notStrandedAtPageBottom() throws Exception {
        // Fill most of a page, then a heading + paragraph that won't both fit at the bottom.
        List<org.aspose.pdf.sdm.SdmBlock> blocks = new ArrayList<>();
        int fill = fillCountForNearBottom();
        for (int i = 0; i < fill; i++) {
            blocks.add(para("filler line " + i));
        }
        blocks.add(heading(2, "KEEP_HEADING"));
        blocks.add(para("its following body paragraph"));
        SdmDocument sdm = new SdmDocument();
        sdm.getChildren().addAll(blocks);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        assertTrue(r.getDocument().getPages().getCount() >= 2);
        // the heading and its body must be on the SAME page (both on page 2)
        String p2 = pageText(r.getDocument().getPages().get(2));
        assertTrue(p2.contains("KEEP_HEADING"), "heading moved to page 2");
        assertTrue(p2.contains("its following body"), "body with the heading on page 2");
    }

    /** Number of single-line fillers that leaves ~one line of room at page bottom. */
    private static int fillCountForNearBottom() {
        double lh = TextLayoutHelper.getLineHeight("Helvetica", 12);
        double usable = LETTER.getContentHeight();
        return (int) Math.floor(usable / lh) - 1;
    }

    // ---- tables ------------------------------------------------------------

    @Test
    public void table_columnBoundariesAndHeaderRepeat() throws Exception {
        Table t = new Table();
        t.getColumns().add(new org.aspose.pdf.sdm.ColumnSpec(
                org.aspose.pdf.sdm.ColumnSpec.WidthType.POINTS, 100, org.aspose.pdf.sdm.ColumnSpec.Align.LEFT));
        t.getColumns().add(new org.aspose.pdf.sdm.ColumnSpec(
                org.aspose.pdf.sdm.ColumnSpec.WidthType.POINTS, 200, org.aspose.pdf.sdm.ColumnSpec.Align.LEFT));
        TableRow header = new TableRow(TableRow.Kind.HEADER);
        header.getCells().add(cell("COLA", TableCell.Kind.TH));
        header.getCells().add(cell("COLB", TableCell.Kind.TH));
        t.getRows().add(header);
        for (int i = 0; i < 60; i++) {
            TableRow row = new TableRow(TableRow.Kind.BODY);
            row.getCells().add(cell("a" + i, TableCell.Kind.TD));
            row.getCells().add(cell("b" + i, TableCell.Kind.TD));
            t.getRows().add(row);
        }
        SdmPdfLayout.Result r = new SdmPdfLayout().render(doc(t), LETTER);
        assertTrue(r.getDocument().getPages().getCount() >= 2, "table spans 2+ pages");
        // THead repeats: COLA present on both page 1 and page 2
        assertTrue(pageText(r.getDocument().getPages().get(1)).contains("COLA"), "header on page 1");
        assertTrue(pageText(r.getDocument().getPages().get(2)).contains("COLA"), "header repeats on page 2");
        // column-A cell text starts at the left margin; column-B at margin+100
        List<Drawn> d = drawnText(r.getDocument().getPages().get(1));
        Drawn a0 = find(d, "a0");
        Drawn b0 = find(d, "b0");
        assertEquals(LETTER.getMarginLeft() + 3, a0.x, 1.5);
        assertEquals(LETTER.getMarginLeft() + 100 + 3, b0.x, 1.5);
    }

    @Test
    public void table_colspanSpansTwoColumns() throws Exception {
        Table t = new Table();
        TableRow r1 = new TableRow(TableRow.Kind.BODY);
        TableCell wide = cell("WIDE", TableCell.Kind.TD);
        wide.setColSpan(2);
        r1.getCells().add(wide);
        t.getRows().add(r1);
        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("L", TableCell.Kind.TD));
        r2.getCells().add(cell("R", TableCell.Kind.TD));
        t.getRows().add(r2);
        SdmPdfLayout.Result res = new SdmPdfLayout().render(doc(t), LETTER);
        List<Drawn> d = drawnText(res.getDocument().getPages().get(1));
        // WIDE starts at the left column; R starts at the second column (> half width)
        Drawn wideD = find(d, "WIDE");
        Drawn rD = find(d, "R");
        assertEquals(LETTER.getMarginLeft() + 3, wideD.x, 1.5);
        assertTrue(rD.x > LETTER.getMarginLeft() + LETTER.getContentWidth() / 2 - 20,
                "second cell in the right column, x=" + rD.x);
    }

    // ---- figures -----------------------------------------------------------

    @Test
    public void figure_embedsRealImageScaledToContentWidth() throws Exception {
        byte[] png = pngBytes(1000, 100);   // intrinsic 1000pt wide > content width
        SdmDocument sdm = new SdmDocument();
        org.aspose.pdf.sdm.ResourceRef ref = sdm.getResources().put("img1",
                new org.aspose.pdf.sdm.Resource(org.aspose.pdf.sdm.Resource.Kind.IMAGE, png, "image/png"));
        sdm.getChildren().add(new org.aspose.pdf.sdm.Figure(ref));

        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        Page p1 = r.getDocument().getPages().get(1);

        boolean painted = false;
        double drawnWidth = -1;
        double lastCmW = -1;
        for (Operator op : p1.getContents()) {
            if (op instanceof org.aspose.pdf.operators.ConcatenateMatrix) {
                lastCmW = ((org.aspose.pdf.operators.ConcatenateMatrix) op).getMatrix().getA();
            } else if (op instanceof org.aspose.pdf.operators.Do) {
                painted = true;
                drawnWidth = lastCmW;
            }
        }
        assertTrue(painted, "block figure paints an image XObject (Do)");
        // present resource must NOT be reported missing (it was embedded, not placeholdered)
        assertTrue(r.getReport().getMissingResources().isEmpty(),
                "present image embedded, not treated as missing: " + r.getReport().getMissingResources());
        // 1000pt-wide image scaled down to the content width
        assertTrue(drawnWidth > 0 && drawnWidth <= LETTER.getContentWidth() + 0.5,
                "image scaled to content width, drawn=" + drawnWidth + " content=" + LETTER.getContentWidth());
        // /Resources/XObject carries the image on the page
        org.aspose.pdf.engine.pdfobjects.PdfDictionary res =
                (org.aspose.pdf.engine.pdfobjects.PdfDictionary) p1.getPdfDictionary().get("Resources");
        assertTrue(res != null && res.get("XObject") != null, "page /Resources/XObject present");
    }

    @Test
    public void backgroundFigure_paintedOutOfFlow_noExtraPageNoCursorAdvance() throws Exception {
        // A near-full-page background image (a watermark) plus one short paragraph.
        // In flow the image would consume a whole page (paragraph pushed to page 2);
        // as a backdrop it must stay on page 1, faded, and leave the paragraph in place.
        byte[] png = pngBytes(500, 700);
        SdmDocument sdm = new SdmDocument();
        org.aspose.pdf.sdm.ResourceRef ref = sdm.getResources().put("wm",
                new org.aspose.pdf.sdm.Resource(org.aspose.pdf.sdm.Resource.Kind.IMAGE, png, "image/png"));
        org.aspose.pdf.sdm.Figure bg = new org.aspose.pdf.sdm.Figure(ref);
        bg.getAttributes().put("background", Boolean.TRUE);
        bg.getAttributes().put("display-width", 500.0);
        bg.getAttributes().put("display-height", 700.0);
        sdm.getChildren().add(bg);
        sdm.getChildren().add(para("Only one short line of body text."));

        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        assertEquals(1, r.getDocument().getPages().getCount(),
                "backdrop must not consume flow space / add a page");
        Page p1 = r.getDocument().getPages().get(1);
        // Backdrop painted with a soft-alpha ExtGState and the body text present.
        org.aspose.pdf.engine.pdfobjects.PdfDictionary res =
                (org.aspose.pdf.engine.pdfobjects.PdfDictionary) p1.getPdfDictionary().get("Resources");
        assertTrue(res != null && res.get("ExtGState") != null,
                "page /Resources/ExtGState present for the faded backdrop");
        assertTrue(res.get("XObject") != null, "backdrop image XObject present");
        TextAbsorber ta = new TextAbsorber();
        ta.visit(p1);
        assertTrue(ta.getText().contains("one short line"), "body text stays on page 1");
    }

    @Test
    public void figure_missingResourceFallsBackToPlaceholder() throws Exception {
        SdmDocument sdm = new SdmDocument();
        // ResourceRef with no matching resource in the table.
        sdm.getChildren().add(new org.aspose.pdf.sdm.Figure(new org.aspose.pdf.sdm.ResourceRef("nope")));
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        assertFalse(r.getReport().getMissingResources().isEmpty(), "missing image reported");
    }

    private static byte[] pngBytes(int w, int h) throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(java.awt.Color.LIGHT_GRAY);
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", b);
        return b.toByteArray();
    }

    // ---- report ------------------------------------------------------------

    @Test
    public void report_fontSubstitutionAndOpaque() throws Exception {
        Paragraph p = new Paragraph();
        TextStyle ts = new TextStyle();
        ts.setFontFamily("Comic Sans MS"); // unavailable -> Helvetica
        p.getInline().add(new Run("styled", ts));
        SdmDocument sdm = doc(p, new Opaque(new HtmlRef()));
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
        assertTrue(r.getReport().getFontSubstitutions().containsKey("Comic Sans MS"),
                "font substitution recorded: " + r.getReport().getFontSubstitutions());
        assertEquals("Helvetica", r.getReport().getFontSubstitutions().get("Comic Sans MS"));
        assertFalse(r.getReport().getOpaquePlaceholders().isEmpty(), "opaque placeholder recorded");
    }

    // ---- helpers -----------------------------------------------------------

    private static TableCell cell(String text, TableCell.Kind kind) {
        TableCell c = new TableCell();
        c.setKind(kind);
        c.getChildren().add(para(text));
        return c;
    }

    private static String allText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            sb.append(pageText(doc.getPages().get(i))).append(' ');
        }
        return sb.toString();
    }

    private static String pageText(Page page) throws Exception {
        TextAbsorber ta = new TextAbsorber();
        ta.visit(page);
        return ta.getText() == null ? "" : ta.getText();
    }

    private static final class Drawn {
        final double x;
        final double y;
        final String text;
        Drawn(double x, double y, String text) { this.x = x; this.y = y; this.text = text; }
    }

    /** Walks a page's content operators collecting each drawn text piece with its Tm position. */
    private static List<Drawn> drawnText(Page page) throws Exception {
        List<Drawn> out = new ArrayList<>();
        double curX = 0;
        double curY = 0;
        for (Operator op : page.getContents()) {
            if (op instanceof SetTextMatrix) {
                org.aspose.pdf.Matrix m = ((SetTextMatrix) op).getMatrix();
                curX = m.getE();
                curY = m.getF();
            } else if (op instanceof ShowText) {
                out.add(new Drawn(curX, curY, ((ShowText) op).getText()));
            }
        }
        return out;
    }

    private static Drawn find(List<Drawn> drawn, String text) {
        for (Drawn d : drawn) {
            if (d.text != null && d.text.contains(text)) {
                return d;
            }
        }
        throw new AssertionError("no drawn text containing '" + text + "' in " + drawn.size() + " pieces");
    }

    private static final class HtmlRef extends SourceRef {
        HtmlRef() { super("html"); }
        @Override public String canonical() { return "html:test"; }
    }
}
