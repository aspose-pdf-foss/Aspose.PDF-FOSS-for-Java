package org.aspose.pdf.sdm;

import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PART 1 gate: model construction round-trip (build tree → walk → same),
 * ResourceTable resolution, CellValue kinds, PGM multi-box index.
 */
public class SdmModelTest {

    /** Build a small document tree and walk it back — structure preserved exactly. */
    @Test
    public void treeBuildAndWalkRoundTrip() {
        SdmDocument doc = new SdmDocument();
        doc.getMetadata().setTitle("T");
        doc.getMetadata().setLang("cs-CZ");

        Heading h = new Heading(2);
        h.getInline().add(new Run("Title", null));
        doc.getChildren().add(h);

        Paragraph p = new Paragraph();
        TextStyle bold = new TextStyle();
        bold.setBold(true);
        bold.setFontFamily("Helvetica");
        bold.setFontSize(11);
        p.getInline().add(new Run("Hello ", null));
        p.getInline().add(new Run("world", bold));
        p.getInline().add(new LineBreak());
        p.getInline().add(new Run("line2", null));
        doc.getChildren().add(p);

        Table t = new Table();
        t.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 120,
                ColumnSpec.Align.RIGHT));
        t.getColumns().add(new ColumnSpec());
        TableRow row = new TableRow(TableRow.Kind.BODY);
        TableCell cell = new TableCell();
        cell.setColSpan(2);
        cell.setCellValue(new CellValue(CellValue.Kind.NUMBER, "1 234,56", "#,##0.00"));
        Paragraph cellPara = new Paragraph();
        cellPara.getInline().add(new Run("1 234,56", null));
        cell.getChildren().add(cellPara);
        row.getCells().add(cell);
        t.getRows().add(row);
        doc.getChildren().add(t);

        // walk back
        assertEquals(3, doc.getChildren().size());
        assertEquals(SdmNodeType.HEADING, doc.getChildren().get(0).getType());
        assertEquals(2, ((Heading) doc.getChildren().get(0)).getLevel());
        Paragraph pBack = (Paragraph) doc.getChildren().get(1);
        assertEquals("Hello world\nline2", pBack.getText());
        assertTrue(((Run) pBack.getInline().get(1)).getStyle().isBold());
        Table tBack = (Table) doc.getChildren().get(2);
        assertEquals(2, tBack.getColumns().size());
        assertEquals(ColumnSpec.Align.RIGHT, tBack.getColumns().get(0).getDefaultAlign());
        TableCell cBack = tBack.getRows().get(0).getCells().get(0);
        assertEquals(2, cBack.getColSpan());
        assertEquals(CellValue.Kind.NUMBER, cBack.getCellValue().getKind());
        assertEquals("1 234,56", cBack.getCellValue().getRaw());
        assertEquals("1 234,56", ((Paragraph) cBack.getChildren().get(0)).getText());
    }

    /** Unknown attributes are stored and preserved (round-trip contract §1.1). */
    @Test
    public void unknownAttributesPreserved() {
        Paragraph p = new Paragraph();
        p.getAttributes().put("x-custom", "keep-me");
        assertEquals("keep-me", p.getAttributes().get("x-custom"));
    }

    /** ResourceTable: bytes live in the table, nodes carry refs that resolve. */
    @Test
    public void resourceTableRefResolution() {
        SdmDocument doc = new SdmDocument();
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        ResourceRef ref = doc.getResources().put("img1",
                new Resource(Resource.Kind.IMAGE, png, "image/png"));
        Figure fig = new Figure(ref);
        doc.getChildren().add(fig);

        Resource resolved = doc.getResources().get(fig.getImage());
        assertSame(png, resolved.getBytes());
        assertEquals(Resource.Kind.IMAGE, resolved.getKind());
        assertEquals("image/png", resolved.getMime());
        assertNull(doc.getResources().get("absent"));
        assertEquals(1, doc.getResources().size());
    }

    /** All CellValue kinds construct and expose their fields. */
    @Test
    public void cellValueKinds() {
        assertEquals(CellValue.Kind.NUMBER, new CellValue(CellValue.Kind.NUMBER, "1", null).getKind());
        assertEquals(CellValue.Kind.TEXT, new CellValue(CellValue.Kind.TEXT, "a", null).getKind());
        assertEquals(CellValue.Kind.DATE, new CellValue(CellValue.Kind.DATE, "2026-07-21", "yyyy-MM-dd").getKind());
        assertEquals(CellValue.Kind.BOOL, new CellValue(CellValue.Kind.BOOL, "true", null).getKind());
        assertEquals("yyyy-MM-dd", new CellValue(CellValue.Kind.DATE, "x", "yyyy-MM-dd").getFormat());
    }

    /** PGM: multi-box nodes index under one GUID; flowClass defaults to FLOW. */
    @Test
    public void pgmMultiBoxIndexAndDefaults() {
        UUID ns = SdmIds.nsDoc("doc".getBytes(StandardCharsets.UTF_8));
        String guid = SdmIds.nodeId(ns, new ContentRange(5, 0, 9));

        PgmModel model = new PgmModel();
        PgmPage p0 = new PgmPage(0, 612, 792, 0);
        PgmPage p1 = new PgmPage(1, 612, 792, 0);
        model.addPage(p0);
        model.addPage(p1);

        PgmBox part1 = new PgmBox(guid, 0, new PgmRect(72, 100, 468, 50), 0,
                PgmBoxKind.TEXT, new ContentRange(5, 0, 9));
        part1.setPart(0, 2);
        PgmBox part2 = new PgmBox(guid, 1, new PgmRect(72, 700, 468, 30), 0,
                PgmBoxKind.TEXT, new ContentRange(5, 0, 9));
        part2.setPart(1, 2);
        p0.getBoxes().add(part1);
        p1.getBoxes().add(part2);
        model.indexBox(part1);
        model.indexBox(part2);

        assertEquals(2, model.byId(guid).size());
        assertEquals(0, model.byId(guid).get(0).getPartIndex());
        assertEquals(2, model.byId(guid).get(1).getPartCount());
        assertEquals(FlowClass.FLOW, part1.getFlowClass());
        assertEquals(2, model.totalBoxes());
        assertTrue(model.byId("no-such-id").isEmpty());

        // ANCHORED wiring
        PgmBox highlight = new PgmBox("hl-id", 0, new PgmRect(72, 100, 100, 12), 1,
                PgmBoxKind.ANNOTATION, new ObjectRef(9, 0));
        highlight.setAnchorTargetId(guid);
        assertEquals(FlowClass.ANCHORED, highlight.getFlowClass());
        assertEquals(guid, highlight.getAnchorTargetId());
    }

    /** PgmRect geometry helpers used by detectors. */
    @Test
    public void pgmRectGeometry() {
        PgmRect a = PgmRect.fromCorners(10, 20, 30, 5);
        assertEquals(10, a.getX());
        assertEquals(5, a.getY());
        assertEquals(20, a.getW());
        assertEquals(15, a.getH());
        PgmRect b = new PgmRect(25, 10, 10, 10);
        assertTrue(a.intersects(b));
        PgmRect u = a.union(b);
        assertEquals(10, u.getX());
        assertEquals(5, u.getY());
        assertEquals(25, u.getW());
        assertEquals(15, u.getH());
    }
}
