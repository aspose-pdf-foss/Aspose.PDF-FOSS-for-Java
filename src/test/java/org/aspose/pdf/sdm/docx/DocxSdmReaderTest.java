package org.aspose.pdf.sdm.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;

/**
 * {@link DocxSdmReader}: SDM &rarr; {@code .docx} (via {@link SdmDocxWriter})
 * &rarr; SDM must round-trip the structure — headings keep their level, runs
 * keep bold/colour, lists keep order and items, tables keep the cell grid and
 * spans, pictures keep their bytes and display size.
 */
public class DocxSdmReaderTest {

    private static SdmDocument roundTrip(SdmDocument in) throws Exception {
        byte[] docx = new SdmDocxWriter().write(in);
        return new DocxSdmReader().read(docx);
    }

    private static String textOf(SdmBlock block) {
        StringBuilder sb = new StringBuilder();
        List<SdmInline> inline = block instanceof Paragraph ? ((Paragraph) block).getInline()
                : block instanceof Heading ? ((Heading) block).getInline() : List.of();
        for (SdmInline in : inline) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            } else if (in instanceof LinkInline) {
                for (SdmInline k : ((LinkInline) in).getChildren()) {
                    if (k instanceof Run) {
                        sb.append(((Run) k).getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    @Test
    public void headingsAndRunsRoundTrip() throws Exception {
        SdmDocument in = new SdmDocument();
        Heading h = new Heading(2);
        h.getInline().add(new Run("Chapter Two", null));
        in.getChildren().add(h);
        Paragraph p = new Paragraph();
        TextStyle bold = new TextStyle();
        bold.setBold(true);
        bold.setColor(0xFF3366CC);
        p.getInline().add(new Run("Plain then ", null));
        p.getInline().add(new Run("bold blue", bold));
        in.getChildren().add(p);

        SdmDocument out = roundTrip(in);
        Heading oh = (Heading) out.getChildren().get(0);
        assertEquals(2, oh.getLevel(), "heading level");
        assertEquals("Chapter Two", textOf(oh));
        Paragraph op = (Paragraph) out.getChildren().get(1);
        assertEquals("Plain then bold blue", textOf(op));
        Run boldRun = (Run) op.getInline().get(1);
        assertNotNull(boldRun.getStyle());
        assertTrue(boldRun.getStyle().isBold(), "bold survives");
        assertEquals(0xFF3366CC, boldRun.getStyle().getColor(), "colour survives");
    }

    @Test
    public void listRoundTrips() throws Exception {
        SdmDocument in = new SdmDocument();
        ListBlock list = new ListBlock(true, null);
        for (String s : new String[]{"first", "second", "third"}) {
            org.aspose.pdf.sdm.ListItem item = new org.aspose.pdf.sdm.ListItem();
            Paragraph p = new Paragraph();
            p.getInline().add(new Run(s, null));
            item.getChildren().add(p);
            list.getItems().add(item);
        }
        in.getChildren().add(list);

        SdmDocument out = roundTrip(in);
        ListBlock ol = (ListBlock) out.getChildren().get(0);
        assertTrue(ol.isOrdered(), "ordered list stays ordered");
        assertEquals(3, ol.getItems().size(), "three items");
        assertEquals("second", textOf(ol.getItems().get(1).getChildren().get(0)));
    }

    @Test
    public void nestedListRoundTrips() throws Exception {
        // outer item "top" carries a nested bulleted list ("sub-a", "sub-b"),
        // then a second outer item "tail": the writer emits ilvl 0/1 paragraphs,
        // the reader must rebuild the same nesting (not flatten to one list).
        SdmDocument in = new SdmDocument();
        ListBlock outer = new ListBlock(true, null);
        org.aspose.pdf.sdm.ListItem top = new org.aspose.pdf.sdm.ListItem();
        Paragraph tp = new Paragraph();
        tp.getInline().add(new Run("top", null));
        top.getChildren().add(tp);
        ListBlock inner = new ListBlock(false, null);
        for (String s : new String[]{"sub-a", "sub-b"}) {
            org.aspose.pdf.sdm.ListItem li = new org.aspose.pdf.sdm.ListItem();
            Paragraph p = new Paragraph();
            p.getInline().add(new Run(s, null));
            li.getChildren().add(p);
            inner.getItems().add(li);
        }
        top.getChildren().add(inner);
        outer.getItems().add(top);
        org.aspose.pdf.sdm.ListItem tail = new org.aspose.pdf.sdm.ListItem();
        Paragraph lp = new Paragraph();
        lp.getInline().add(new Run("tail", null));
        tail.getChildren().add(lp);
        outer.getItems().add(tail);
        in.getChildren().add(outer);

        SdmDocument out = roundTrip(in);
        ListBlock oOuter = (ListBlock) out.getChildren().get(0);
        assertTrue(oOuter.isOrdered(), "outer stays ordered");
        assertEquals(2, oOuter.getItems().size(), "two outer items");
        assertEquals("top", textOf(oOuter.getItems().get(0).getChildren().get(0)));
        assertEquals("tail", textOf(oOuter.getItems().get(1).getChildren().get(0)));
        ListBlock oInner = null;
        for (SdmBlock b : oOuter.getItems().get(0).getChildren()) {
            if (b instanceof ListBlock) {
                oInner = (ListBlock) b;
            }
        }
        assertNotNull(oInner, "nested list survives under its item");
        assertTrue(!oInner.isOrdered(), "nested list stays bulleted");
        assertEquals(2, oInner.getItems().size(), "two nested items");
        assertEquals("sub-a", textOf(oInner.getItems().get(0).getChildren().get(0)));
    }

    @Test
    public void tableWithSpansRoundTrips() throws Exception {
        SdmDocument in = new SdmDocument();
        Table t = new Table();
        TableRow r1 = new TableRow(TableRow.Kind.BODY);
        TableCell wide = cell("span2");
        wide.setColSpan(2);
        r1.getCells().add(wide);
        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("a"));
        r2.getCells().add(cell("b"));
        t.getRows().add(r1);
        t.getRows().add(r2);
        in.getChildren().add(t);

        SdmDocument out = roundTrip(in);
        Table ot = (Table) out.getChildren().get(0);
        assertEquals(2, ot.getRows().size(), "two rows");
        assertEquals(1, ot.getRows().get(0).getCells().size(), "merged first row");
        assertEquals(2, ot.getRows().get(0).getCells().get(0).getColSpan(), "gridSpan survives");
        assertEquals(2, ot.getRows().get(1).getCells().size(), "two cells in second row");
        assertEquals("b", textOf(ot.getRows().get(1).getCells().get(1).getChildren().get(0)));
    }

    @Test
    public void pictureRoundTrips() throws Exception {
        SdmDocument in = new SdmDocument();
        byte[] png = tinyPng();
        ResourceRef ref = in.getResources().put("img:1", new Resource(Resource.Kind.IMAGE, png, "image/png"));
        Figure fig = new Figure(ref);
        fig.getAttributes().put("display-width", 120.0);
        fig.getAttributes().put("display-height", 80.0);
        in.getChildren().add(fig);

        SdmDocument out = roundTrip(in);
        Figure of = (Figure) out.getChildren().get(0);
        Resource res = out.getResources().get(of.getImage());
        assertNotNull(res, "image resource present");
        assertTrue(res.getBytes().length > 0, "image bytes survive");
        assertEquals(120.0, ((Number) of.getAttributes().get("display-width")).doubleValue(), 0.5,
                "display width survives (EMU round-trip)");
    }

    private static TableCell cell(String text) {
        TableCell c = new TableCell();
        Paragraph p = new Paragraph();
        p.getInline().add(new Run(text, null));
        c.getChildren().add(p);
        return c;
    }

    private static byte[] tinyPng() throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }
}
