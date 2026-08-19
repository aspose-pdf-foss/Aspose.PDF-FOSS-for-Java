package org.aspose.pdf.sdm.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;

/**
 * Unit gate for {@link SdmDocxWriter}: a hand-built Semantic Document Model must
 * serialize to a well-formed WordprocessingML package whose parts carry the
 * expected structure (headings, styled runs, restarting lists, a table with
 * {@code gridSpan}/{@code vMerge}, and an embedded picture with a matching
 * relationship). Assertions inspect the ZIP parts directly — no OOXML library.
 */
public class SdmDocxWriterTest {

    private static Map<String, String> unzip(byte[] docx) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                parts.put(e.getName(), new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static Run run(String text, boolean bold) {
        TextStyle st = new TextStyle();
        st.setBold(bold);
        st.setFontSize(12);
        return new Run(text, st);
    }

    private static byte[] tinyPng() throws IOException {
        BufferedImage img = new BufferedImage(8, 6, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private static SdmDocument sample() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getMetadata().setTitle("Sample Title");
        doc.getMetadata().setAuthor("Tester");

        Heading h = new Heading(1);
        h.getInline().add(run("Report Heading", true));
        doc.getChildren().add(h);

        Paragraph p = new Paragraph();
        p.getInline().add(run("Plain intro text and ", false));
        p.getInline().add(run("bold emphasis", true));
        p.getInline().add(run(".", false));
        BlockStyle ps = new BlockStyle();
        ps.setAlign(BlockStyle.Align.CENTER);
        p.setStyle(ps);
        doc.getChildren().add(p);

        ListBlock ul = new ListBlock(false, null);
        for (String s : new String[]{"First bullet", "Second bullet"}) {
            ListItem li = new ListItem();
            Paragraph lp = new Paragraph();
            lp.getInline().add(run(s, false));
            li.getChildren().add(lp);
            ul.getItems().add(li);
        }
        doc.getChildren().add(ul);

        ListBlock ol = new ListBlock(true, 1);
        ListItem oli = new ListItem();
        Paragraph olp = new Paragraph();
        olp.getInline().add(run("Numbered item", false));
        oli.getChildren().add(olp);
        ol.getItems().add(oli);
        doc.getChildren().add(ol);

        Table t = new Table();
        t.getAttributes().put("border", "ruled");
        t.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 100, ColumnSpec.Align.LEFT));
        t.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 100, ColumnSpec.Align.LEFT));
        TableRow head = new TableRow(TableRow.Kind.HEADER);
        TableCell spanCell = new TableCell();
        spanCell.setColSpan(2);
        Paragraph hp = new Paragraph();
        hp.getInline().add(run("Header spanning two", true));
        spanCell.getChildren().add(hp);
        head.getCells().add(spanCell);
        t.getRows().add(head);
        TableRow body = new TableRow(TableRow.Kind.BODY);
        for (String s : new String[]{"A1", "B1"}) {
            TableCell c = new TableCell();
            Paragraph cp = new Paragraph();
            cp.getInline().add(run(s, false));
            c.getChildren().add(cp);
            body.getCells().add(c);
        }
        t.getRows().add(body);
        doc.getChildren().add(t);

        ResourceRef ref = doc.getResources().put("img1",
                new Resource(Resource.Kind.IMAGE, tinyPng(), "image/png"));
        Figure fig = new Figure(ref);
        fig.getAttributes().put("display-width", 80.0);
        fig.getAttributes().put("display-height", 60.0);
        fig.setAlt("a tiny image");
        doc.getChildren().add(fig);

        return doc;
    }

    @Test
    public void producesValidPackageWithAllParts() throws IOException {
        byte[] docx = new SdmDocxWriter().write(sample());
        Map<String, String> parts = unzip(docx);

        assertTrue(parts.containsKey("[Content_Types].xml"), "content types present");
        assertTrue(parts.containsKey("_rels/.rels"), "root rels present");
        assertTrue(parts.containsKey("word/document.xml"), "document part present");
        assertTrue(parts.containsKey("word/styles.xml"), "styles part present");
        assertTrue(parts.containsKey("word/numbering.xml"), "numbering part present");
        assertTrue(parts.containsKey("word/_rels/document.xml.rels"), "document rels present");
        assertTrue(parts.containsKey("word/media/image1.png"), "image media part present");
        assertTrue(parts.get("[Content_Types].xml").contains("Extension=\"png\""),
                "png default content type declared");
    }

    @Test
    public void documentXmlCarriesStructure() throws IOException {
        Map<String, String> parts = unzip(new SdmDocxWriter().write(sample()));
        String doc = parts.get("word/document.xml");

        assertTrue(doc.contains("w:val=\"Heading1\""), "heading style applied");
        assertTrue(doc.contains("Report Heading"), "heading text present");
        assertTrue(doc.contains("<w:b/>"), "bold run property emitted");
        assertTrue(doc.contains("<w:jc w:val=\"center\"/>"), "paragraph alignment emitted");
        assertTrue(doc.contains("<w:numPr>"), "list numbering applied");
        assertTrue(doc.contains("<w:tbl>"), "table emitted");
        assertTrue(doc.contains("<w:gridSpan w:val=\"2\"/>"), "colspan -> gridSpan");
        assertTrue(doc.contains("<w:tblBorders>"), "ruled table borders");
        assertTrue(doc.contains("<w:drawing>") && doc.contains("r:embed="), "picture drawing with embed");
        assertTrue(doc.contains("<w:sectPr>"), "section properties (page size) present");
    }

    @Test
    public void listsRestartWithDistinctNumIds() throws IOException {
        Map<String, String> parts = unzip(new SdmDocxWriter().write(sample()));
        String numbering = parts.get("word/numbering.xml");
        // Two lists -> two <w:num> definitions (bullet numId=1, ordered numId=2),
        // each referencing the correct abstract (0 bullet, 1 decimal).
        assertTrue(numbering.contains("w:numId=\"1\""), "first list numId");
        assertTrue(numbering.contains("w:numId=\"2\""), "second list numId (restart)");
        assertTrue(numbering.contains("w:numFmt w:val=\"bullet\""), "bullet abstract");
        assertTrue(numbering.contains("w:numFmt w:val=\"decimal\""), "decimal abstract");

        String rels = parts.get("word/_rels/document.xml.rels");
        assertTrue(rels.contains("/image\""), "image relationship declared");
        assertNotNull(rels);
    }

    @Test
    public void allTextRoundTripsIntoDocument() throws IOException {
        Map<String, String> parts = unzip(new SdmDocxWriter().write(sample()));
        String doc = parts.get("word/document.xml");
        for (String expected : new String[]{
                "Report Heading", "Plain intro text and", "bold emphasis",
                "First bullet", "Second bullet", "Numbered item",
                "Header spanning two", "A1", "B1"}) {
            assertTrue(doc.contains(expected), "text present in document.xml: " + expected);
        }
    }
}
