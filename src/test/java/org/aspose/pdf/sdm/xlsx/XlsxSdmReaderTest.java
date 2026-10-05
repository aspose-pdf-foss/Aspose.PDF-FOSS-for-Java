package org.aspose.pdf.sdm.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.junit.jupiter.api.Test;

/**
 * Round-trips an SDM table through {@link SdmXlsxWriter} and back through
 * {@link XlsxSdmReader}, asserting values, merges and fills survive.
 */
public class XlsxSdmReaderTest {

    private static Paragraph para(String text) {
        Paragraph p = new Paragraph();
        p.getInline().add(new Run(text, null));
        return p;
    }

    private static TableCell cell(String text) {
        TableCell c = new TableCell();
        c.getChildren().add(para(text));
        return c;
    }

    private static String cellText(TableCell c) {
        StringBuilder sb = new StringBuilder();
        for (SdmBlock ch : c.getChildren()) {
            if (ch instanceof Paragraph) {
                for (SdmInline in : ((Paragraph) ch).getInline()) {
                    if (in instanceof Run && ((Run) in).getText() != null) {
                        sb.append(((Run) in).getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static Table firstTable(SdmDocument doc) {
        for (SdmBlock b : doc.getChildren()) {
            if (b instanceof Table) {
                return (Table) b;
            }
        }
        return null;
    }

    @Test
    public void roundTripsValuesMergesAndFills() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table t = new Table();
        t.getColumns().add(new ColumnSpec());
        t.getColumns().add(new ColumnSpec());
        t.getColumns().add(new ColumnSpec());

        // Row 0: a header spanning 3 columns, on a fill.
        TableRow r0 = new TableRow(TableRow.Kind.BODY);
        TableCell span = new TableCell();
        span.setColSpan(3);
        span.getChildren().add(para("Report 2024"));
        BlockStyle bg = new BlockStyle();
        bg.setBackground(0xFFDCE6F1);
        span.setStyle(bg);
        r0.getCells().add(span);
        t.getRows().add(r0);

        // Row 1..2: a rowspan category + numeric data.
        TableRow r1 = new TableRow(TableRow.Kind.BODY);
        TableCell cat = new TableCell();
        cat.setRowSpan(2);
        cat.getChildren().add(para("North"));
        r1.getCells().add(cat);
        r1.getCells().add(cell("100"));
        r1.getCells().add(cell("$1,250.00"));
        t.getRows().add(r1);

        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("110"));
        r2.getCells().add(cell("$980.00"));
        t.getRows().add(r2);

        doc.getChildren().add(t);

        byte[] xlsx = new SdmXlsxWriter().write(doc);
        SdmDocument back = new XlsxSdmReader().read(xlsx);
        Table rt = firstTable(back);
        assertTrue(rt != null, "table recovered from xlsx");
        assertEquals(3, rt.getColumns().size(), "3 columns");
        assertEquals(3, rt.getRows().size(), "3 rows");

        // Row 0: single spanning cell.
        List<TableCell> row0 = rt.getRows().get(0).getCells();
        assertEquals(1, row0.size(), "header row has one (merged) cell");
        assertEquals(3, row0.get(0).getColSpan(), "header spans 3 columns");
        assertEquals("Report 2024", cellText(row0.get(0)));
        assertTrue(row0.get(0).getStyle() != null
                && row0.get(0).getStyle().getBackground() != 0, "header fill recovered");

        // Row 1: rowspan category + two data cells; value formats preserved.
        List<TableCell> row1 = rt.getRows().get(1).getCells();
        assertEquals(3, row1.size());
        assertEquals(2, row1.get(0).getRowSpan(), "category spans 2 rows");
        assertEquals("North", cellText(row1.get(0)));
        assertEquals("100", cellText(row1.get(1)));
        assertEquals("$1,250.00", cellText(row1.get(2)), "currency format preserved");

        // Row 2: the rowspan column is covered, so only two anchored cells remain.
        List<TableCell> row2 = rt.getRows().get(2).getCells();
        assertEquals(2, row2.size(), "row under the rowspan has 2 cells");
        assertEquals("110", cellText(row2.get(0)));
    }
}
