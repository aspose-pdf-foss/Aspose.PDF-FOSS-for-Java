package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmNodeType;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * IR Stage 3 PART 3b gate: the heuristic enrichment layers, each against a
 * synthetic fixture with a KNOWN structure — exact recognition asserted, plus
 * the degradation rule (ambiguous large text STAYS a paragraph — never guess
 * up). Thresholds under test are the probe-calibrated constants of
 * {@link HeuristicSdmEnricher}.
 */
public class HeuristicSdmEnricherTest {

    private static void line(Page page, String text, double x, double y, double size)
            throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        tf.getTextState().setFontSize(size);
        new TextBuilder(page).appendText(tf);
    }

    private static PdfSdmReader.Result enrich(Document doc) throws IOException {
        PdfSdmReader.Result r = new PdfSdmReader().read(doc, null);
        new HeuristicSdmEnricher().enrich(doc, r.getSdm(), r.getPgm());
        return r;
    }

    private static String textOf(SdmBlock b) {
        if (b instanceof Paragraph) {
            return ((Paragraph) b).getText();
        }
        StringBuilder sb = new StringBuilder();
        if (b instanceof Heading) {
            ((Heading) b).getInline().forEach(in -> {
                if (in instanceof org.aspose.pdf.sdm.Run) {
                    sb.append(((org.aspose.pdf.sdm.Run) in).getText());
                }
            });
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ layer 1

    /** Size-ranked headings: 18pt→h1, 14pt→h2; body stays; GUIDs preserved. */
    @Test
    public void headingsBySizeRank() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Main Title", 72, 720, 18);
        line(page, "Intro body text that is clearly a normal paragraph of the document.",
                72, 690, 12);
        line(page, "Sub Section", 72, 650, 14);
        line(page, "More body text keeps the 12pt size as the dominant baseline size.",
                72, 620, 12);
        line(page, "Trailing body line to weight the baseline further down the page.",
                72, 590, 12);

        PdfSdmReader.Result r = new PdfSdmReader().read(doc, null);
        String mainId = r.getSdm().getChildren().get(0).getId();
        assertNotNull(mainId);
        new HeuristicSdmEnricher().enrich(doc, r.getSdm(), r.getPgm());
        List<SdmBlock> out = r.getSdm().getChildren();

        assertEquals(SdmNodeType.HEADING, out.get(0).getType());
        assertEquals(1, ((Heading) out.get(0)).getLevel(), "largest size → h1");
        assertEquals("Main Title", textOf(out.get(0)));
        assertEquals(mainId, out.get(0).getId(), "retype preserves GUID");
        assertEquals(SdmNodeType.PARAGRAPH, out.get(1).getType());
        assertEquals(SdmNodeType.HEADING, out.get(2).getType());
        assertEquals(2, ((Heading) out.get(2)).getLevel(), "second size rank → h2");
        assertEquals(SdmNodeType.PARAGRAPH, out.get(3).getType());
        assertEquals(SdmNodeType.PARAGRAPH, out.get(4).getType());
    }

    /** Degradation: a large lead sentence (ends with '.') and a wide large line stay paragraphs. */
    @Test
    public void degradationNeverGuessesUp() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        // short but a sentence — the classic false positive
        line(page, "This opening statement is large.", 72, 720, 16);
        // large but a full-width running line (not standalone)
        line(page, "This very long large-type line runs the full width of the page column "
                + "and cannot be a heading at all.", 72, 680, 16);
        for (int i = 0; i < 6; i++) {
            line(page, "Body paragraph line number " + i + " at the regular body size.",
                    72, 640 - i * 20, 12);
        }

        PdfSdmReader.Result r = enrich(doc);
        for (SdmBlock b : r.getSdm().getChildren()) {
            assertEquals(SdmNodeType.PARAGRAPH, b.getType(),
                    "ambiguous large text must STAY a paragraph: " + textOf(b));
        }
    }

    /** Guards from the labeled sample: watermark zone, top-right code, sparse page, '!'. */
    @Test
    public void precisionGuards() throws IOException {
        // W: giant text mid-page (watermark) with a real body around it
        Document w = new Document();
        Page wp = w.getPages().add();
        for (int i = 0; i < 5; i++) {
            line(wp, "Body text line that provides the dominant baseline size " + i + ".",
                    72, 720 - i * 20, 12);
        }
        line(wp, "DRAFT COPY", 200, 400, 40); // middle third of a 792pt page
        for (SdmBlock b : enrich(w).getSdm().getChildren()) {
            assertEquals(SdmNodeType.PARAGRAPH, b.getType(),
                    "watermark-zone text stays a paragraph: " + textOf(b));
        }

        // C: oversized short code in the top-right corner
        Document c = new Document();
        Page cp = c.getPages().add();
        for (int i = 0; i < 5; i++) {
            line(cp, "Body text line that provides the dominant baseline size " + i + ".",
                    72, 700 - i * 20, 12);
        }
        line(cp, "DOC-2026-0042", 430, 760, 15);
        for (SdmBlock b : enrich(c).getSdm().getChildren()) {
            assertEquals(SdmNodeType.PARAGRAPH, b.getType(),
                    "top-right code stays a paragraph: " + textOf(b));
        }

        // S: sparse page (title slide) — no body evidence, no headings at all
        Document s = new Document();
        Page sp = s.getPages().add();
        line(sp, "BIG SLIDE TITLE", 150, 500, 32);
        line(sp, "author name", 260, 300, 10);
        for (SdmBlock b : enrich(s).getSdm().getChildren()) {
            assertEquals(SdmNodeType.PARAGRAPH, b.getType(),
                    "sparse page promotes nothing: " + textOf(b));
        }

        // P: exclamation lead line
        Document p = new Document();
        Page pp = p.getPages().add();
        line(pp, "Yada, yada!", 72, 720, 15);
        for (int i = 0; i < 5; i++) {
            line(pp, "Body text line that provides the dominant baseline size " + i + ".",
                    72, 690 - i * 20, 12);
        }
        for (SdmBlock b : enrich(p).getSdm().getChildren()) {
            assertEquals(SdmNodeType.PARAGRAPH, b.getType(),
                    "sentence-punctuated line stays a paragraph: " + textOf(b));
        }
    }

    // ------------------------------------------------------------------ layer 2

    /** Bullet list: two marked lines regroup into ul; a lone marker does not. */
    @Test
    public void bulletListAndLoneMarkerDegradation() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Intro paragraph before the list starts here.", 72, 720, 12);
        line(page, "• First point of the list", 90, 690, 12);
        line(page, "• Second point of the list", 90, 670, 12);
        line(page, "After-list paragraph.", 72, 640, 12);
        line(page, "• Lone marker far below is not a list", 90, 400, 12);
        line(page, "Normal closing text.", 72, 380, 12);

        PdfSdmReader.Result r = enrich(doc);
        List<SdmBlock> out = r.getSdm().getChildren();
        assertEquals(SdmNodeType.PARAGRAPH, out.get(0).getType());
        assertEquals(SdmNodeType.LIST_BLOCK, out.get(1).getType());
        ListBlock list = (ListBlock) out.get(1);
        assertTrue(!list.isOrdered(), "bullets → unordered");
        assertEquals(2, list.getItems().size());
        // The leading marker glyph is stripped from the item text — it is
        // structural and every writer (md "-", html <li>, docx numPr) re-emits
        // its own, so keeping it would double-print the bullet.
        assertEquals("First point of the list",
                textOf(list.getItems().get(0).getChildren().get(0)));
        assertEquals(SdmNodeType.PARAGRAPH, out.get(2).getType());
        assertEquals(SdmNodeType.PARAGRAPH, out.get(3).getType(),
                "a lone marker line degrades to a paragraph");
        assertEquals(SdmNodeType.PARAGRAPH, out.get(4).getType());
    }

    /** Ordered list with start, hanging continuation and one nested level by indent. */
    @Test
    public void orderedListNestingAndContinuation() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "3. Third step of the procedure", 72, 700, 12);
        line(page, "continued explanation of step three", 100, 680, 12); // hanging indent
        line(page, "a) nested detail one", 100, 660, 12);                 // nested by indent
        line(page, "b) nested detail two", 100, 640, 12);
        line(page, "4. Fourth step of the procedure", 72, 620, 12);

        PdfSdmReader.Result r = enrich(doc);
        List<SdmBlock> out = r.getSdm().getChildren();
        assertEquals(1, out.size(), "one list block: " + out.size());
        ListBlock list = (ListBlock) out.get(0);
        assertTrue(list.isOrdered());
        assertEquals(Integer.valueOf(3), list.getStart(), "start parsed from the first label");
        assertEquals(2, list.getItems().size(), "two top-level items");
        List<SdmBlock> firstItem = list.getItems().get(0).getChildren();
        // Marker stripped (see note above); the start number was still parsed
        // from the original "3." label before stripping.
        assertEquals("Third step of the procedure", textOf(firstItem.get(0)));
        assertEquals("continued explanation of step three", textOf(firstItem.get(1)),
                "hanging-indent line joins the item");
        SdmBlock nested = firstItem.get(2);
        assertEquals(SdmNodeType.LIST_BLOCK, nested.getType(), "indent step opens a nested list");
        assertEquals(2, ((ListBlock) nested).getItems().size());
    }

    // ------------------------------------------------------------------ layer 3

    /** Ruled 2×2 grid + cell texts → exact Table node with ColumnSpec widths. */
    @Test
    public void ruledGridBecomesTable() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        // grid: x 100..300..500, y 600..650..700 (2 cols × 2 rows)
        StringBuilder g = new StringBuilder("q\n1 w\n");
        for (double y : new double[]{600, 650, 700}) {
            g.append("100 ").append((int) y).append(" m\n500 ").append((int) y).append(" l\nS\n");
        }
        for (double x : new double[]{100, 300, 500}) {
            g.append((int) x).append(" 600 m\n").append((int) x).append(" 700 l\nS\n");
        }
        g.append("Q\n");
        page.appendToContentStream(g.toString().getBytes(StandardCharsets.ISO_8859_1));
        line(page, "Name", 110, 675, 12);
        line(page, "Value", 310, 675, 12);
        line(page, "Total", 110, 625, 12);
        line(page, "42", 310, 625, 12);

        PdfSdmReader.Result r = enrich(doc);
        List<SdmBlock> out = r.getSdm().getChildren();
        Table table = null;
        for (SdmBlock b : out) {
            if (b instanceof Table) {
                table = (Table) b;
            }
        }
        assertNotNull(table, "ruled grid recognized as a table");
        assertEquals(2, table.getRows().size());
        assertEquals(2, table.getRows().get(0).getCells().size());
        assertEquals(2, table.getColumns().size(), "ColumnSpec per column");
        assertEquals("Name", textOf(table.getRows().get(0).getCells().get(0).getChildren().get(0)));
        assertEquals("42", textOf(table.getRows().get(1).getCells().get(1).getChildren().get(0)));
        for (SdmBlock b : out) {
            if (b instanceof Paragraph) {
                String t = textOf(b);
                assertTrue(!t.equals("Name") && !t.equals("42"),
                        "cell content must not stay at top level: " + t);
            }
        }
    }

    /**
     * A run that STRADDLES a column boundary (the extractor glued a Patient-ID
     * cell to the next cell as one wide fragment) must be split at the ruled
     * boundary so each word lands in its own column — not dumped whole into one.
     */
    @Test
    public void runStraddlingColumnBoundaryIsSplit() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        // 2 columns (x 100..175..250), 2 rows (y 600..650..700).
        StringBuilder g = new StringBuilder("q\n1 w\n");
        for (double y : new double[]{600, 650, 700}) {
            g.append("100 ").append((int) y).append(" m\n250 ").append((int) y).append(" l\nS\n");
        }
        for (double x : new double[]{100, 175, 250}) {
            g.append((int) x).append(" 600 m\n").append((int) x).append(" 700 l\nS\n");
        }
        g.append("Q\n");
        page.appendToContentStream(g.toString().getBytes(StandardCharsets.ISO_8859_1));
        line(page, "ID", 110, 675, 10);
        line(page, "Name", 185, 675, 10);
        // ONE wide fragment whose text spans both columns (ID gap Name).
        line(page, "12345      Cerebrovascular", 110, 625, 10);

        PdfSdmReader.Result r = enrich(doc);
        Table table = null;
        for (SdmBlock b : r.getSdm().getChildren()) {
            if (b instanceof Table) {
                table = (Table) b;
            }
        }
        assertNotNull(table, "ruled grid recognized as a table");
        String c0 = textOf(table.getRows().get(1).getCells().get(0).getChildren().get(0)).trim();
        String c1 = textOf(table.getRows().get(1).getCells().get(1).getChildren().get(0)).trim();
        assertEquals("12345", c0, "Patient-ID word stays in column 1");
        assertEquals("Cerebrovascular", c1, "the second word is split off into column 2");
    }

    /**
     * A tagged tree wraps a page's flow in a Container, and often never marks its
     * visual tables. {@code enrichRuledTablesOnly} must still recover a ruled table
     * whose paragraphs are NESTED in that container (not at the top level).
     */
    @Test
    public void ruledTableRecoveredInsideContainer() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        StringBuilder g = new StringBuilder("q\n1 w\n");
        for (double y : new double[]{600, 650, 700}) {
            g.append("100 ").append((int) y).append(" m\n500 ").append((int) y).append(" l\nS\n");
        }
        for (double x : new double[]{100, 300, 500}) {
            g.append((int) x).append(" 600 m\n").append((int) x).append(" 700 l\nS\n");
        }
        g.append("Q\n");
        page.appendToContentStream(g.toString().getBytes(StandardCharsets.ISO_8859_1));
        line(page, "Name", 110, 675, 12);
        line(page, "Value", 310, 675, 12);
        line(page, "Total", 110, 625, 12);
        line(page, "42", 310, 625, 12);

        PdfSdmReader.Result r = new PdfSdmReader().read(doc, null);
        // Simulate the tagged enricher: move every top-level block into one Container.
        org.aspose.pdf.sdm.Container sect = new org.aspose.pdf.sdm.Container("Sect");
        sect.getChildren().addAll(r.getSdm().getChildren());
        r.getSdm().getChildren().clear();
        r.getSdm().getChildren().add(sect);

        boolean applied = new HeuristicSdmEnricher()
                .enrichRuledTablesOnly(doc, r.getSdm(), r.getPgm());
        assertTrue(applied, "ruled table recovered even though flow is nested in a container");

        Table table = null;
        for (SdmBlock b : sect.getChildren()) {
            if (b instanceof Table) {
                table = (Table) b;
            }
        }
        assertNotNull(table, "table materialized inside the container");
        assertEquals(2, table.getRows().size());
        assertEquals("42", textOf(table.getRows().get(1).getCells().get(1).getChildren().get(0)));
    }

    /**
     * BORDERLESS key/value form (no ruled grid) → geometric column table:
     * five label/value rows whose runs line up into two whitespace columns
     * become a 2-column Table, cells carrying the original text.
     */
    @Test
    public void borderlessColumnsBecomeTable() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        String[][] kv = {
            {"Name", "John Smith"}, {"Company", "Acme Corp"}, {"Phone", "+1 555 1234"},
            {"Email", "john@acme.com"}, {"Country", "USA"},
        };
        double y = 700;
        for (String[] row : kv) {
            line(page, row[0], 72, y, 10);   // label column
            line(page, row[1], 220, y, 10);  // value column
            y -= 16;
        }

        List<SdmBlock> out = enrich(doc).getSdm().getChildren();
        Table table = null;
        for (SdmBlock b : out) {
            if (b instanceof Table) {
                table = (Table) b;
            }
        }
        assertNotNull(table, "borderless key/value form recognized as a table");
        assertEquals(2, table.getColumns().size(), "two whitespace columns");
        assertEquals(5, table.getRows().size(), "one row per key/value line");
        assertEquals("Name",
                textOf(table.getRows().get(0).getCells().get(0).getChildren().get(0)));
        assertEquals("John Smith",
                textOf(table.getRows().get(0).getCells().get(1).getChildren().get(0)));
        assertEquals("USA",
                textOf(table.getRows().get(4).getCells().get(1).getChildren().get(0)));
        for (SdmBlock b : out) {
            assertTrue(!(b instanceof Paragraph),
                    "the whole form is claimed by the table, no run-on paragraph remains");
        }
    }

    /** Degradation: a normal wrapped paragraph (one column per line) is NOT tabled. */
    @Test
    public void proseIsNotTabled() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "This is a perfectly ordinary paragraph of running text that", 72, 700, 11);
        line(page, "wraps across several lines and shares one left margin with no", 72, 684, 11);
        line(page, "second column anywhere, so it must never be mistaken for a", 72, 668, 11);
        line(page, "table by the whitespace-column heuristic.", 72, 652, 11);

        List<SdmBlock> out = enrich(doc).getSdm().getChildren();
        for (SdmBlock b : out) {
            assertTrue(!(b instanceof Table), "single-column prose must stay a paragraph");
        }
    }

    // ------------------------------------------------------------------ layer 5

    /** Image + short text right below it → Figure with the text as caption. */
    @Test
    public void figureAdoptsAnchoredCaption() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", jpeg);
        ImageStamp stamp = new ImageStamp("ignored.jpg");
        stamp.setImageStream(new ByteArrayInputStream(jpeg.toByteArray()));
        stamp.setXIndent(200);
        stamp.setYIndent(500);
        stamp.setWidth(120);
        stamp.setHeight(80);
        page.addStamp(stamp);
        line(page, "Figure 1. The setup", 210, 490, 10); // ≤15pt below the image, overlapping in x
        line(page, "Unrelated body paragraph far away from the image on the page.", 72, 300, 12);

        PdfSdmReader.Result r = enrich(doc);
        Figure figure = null;
        for (SdmBlock b : r.getSdm().getChildren()) {
            if (b instanceof Figure) {
                figure = (Figure) b;
            }
        }
        assertNotNull(figure, "image projected as Figure");
        assertEquals(1, figure.getCaption().size(), "caption adopted");
        assertEquals("Figure 1. The setup", textOf(figure.getCaption().get(0)));
        for (SdmBlock b : r.getSdm().getChildren()) {
            assertTrue(!(b instanceof Paragraph && "Figure 1. The setup".equals(textOf(b))),
                    "caption no longer at top level");
        }
    }

    // ------------------------------------------------------------------ e2e + toggle

    /** End-to-end untagged: save(...,HtmlSaveOptions STRUCTURAL) emits h1 + ul; toggle disables heuristics. */
    @Test
    public void endToEndHeuristicHtmlAndToggle() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Report Title", 72, 720, 18);
        line(page, "Ordinary introduction paragraph at the regular body size here.",
                72, 690, 12);
        line(page, "• alpha bullet", 90, 660, 12);
        line(page, "• beta bullet", 90, 640, 12);
        line(page, "Ordinary closing paragraph at the regular body size right here.",
                72, 610, 12);

        HtmlSaveOptions structural = new HtmlSaveOptions();
        structural.setOutputMode(HtmlOutputMode.STRUCTURAL);
        String html = org.aspose.pdf.testutil.HtmlText.of(doc, structural);
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        assertEquals(1, dom.getElementsByTagName("h1").getLength(), html);
        assertEquals("Report Title", dom.getElementsByTagName("h1").item(0).getTextContent().trim());
        assertEquals(1, dom.getElementsByTagName("ul").getLength());
        assertEquals(2, dom.getElementsByTagName("li").getLength());

        HtmlSaveOptions shallow = new HtmlSaveOptions();
        shallow.setOutputMode(HtmlOutputMode.STRUCTURAL);
        shallow.setStructuralHeuristics(false);
        org.w3c.dom.Document shallowDom = HtmlTagParser.parse(org.aspose.pdf.testutil.HtmlText.of(doc, shallow));
        assertEquals(0, shallowDom.getElementsByTagName("h1").getLength(),
                "heuristics off → shallow structural (paragraphs only)");
        assertEquals(0, shallowDom.getElementsByTagName("ul").getLength());
    }

    /** Layer 4 (runs): reader styles flow through the heuristic path into b/i markup. */
    @Test
    public void styledRunsSurviveHeuristicPath() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextFragment bold = new TextFragment("BoldLead");
        bold.setPosition(new Position(72, 700));
        bold.getTextState().setFontSize(12);
        bold.getTextState().setFont(org.aspose.pdf.text.FontRepository.findFont("Helvetica-Bold"));
        new TextBuilder(page).appendText(bold);
        line(page, "and plain continuation of the same body paragraph text here.", 130, 700, 12);

        HtmlSaveOptions structural = new HtmlSaveOptions();
        structural.setOutputMode(HtmlOutputMode.STRUCTURAL);
        String html = org.aspose.pdf.testutil.HtmlText.of(doc, structural);
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        assertEquals(1, dom.getElementsByTagName("b").getLength(),
                "bold font name → <b> run: " + html);
        assertEquals("BoldLead", dom.getElementsByTagName("b").item(0).getTextContent().trim());
    }
}
