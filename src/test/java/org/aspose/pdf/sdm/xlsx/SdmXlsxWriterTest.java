package org.aspose.pdf.sdm.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.aspose.pdf.ExcelSaveOptions;
import org.aspose.pdf.sdm.CellValue;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.junit.jupiter.api.Test;

/**
 * In-memory tests for {@link SdmXlsxWriter} and {@link CellValueTyper}. No disk
 * access: the {@code .xlsx} package is built into a byte array and unzipped in
 * memory.
 */
public class SdmXlsxWriterTest {

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

    private static Map<String, String> unzip(byte[] xlsx) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            byte[] buf = new byte[4096];
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int r;
                while ((r = zis.read(buf)) > 0) {
                    bos.write(buf, 0, r);
                }
                parts.put(e.getName(), new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    private static int count(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    // ------------------------------------------------------------------
    // CellValueTyper
    // ------------------------------------------------------------------

    @Test
    public void typesNumbersDatesBooleans() {
        assertEquals(CellValue.Kind.NUMBER, CellValueTyper.type("1234").kind);
        assertEquals(CellValue.Kind.NUMBER, CellValueTyper.type("1,234.56").kind);
        assertEquals(1234.56, CellValueTyper.type("1,234.56").number, 0.0001);
        assertEquals(CellValue.Kind.NUMBER, CellValueTyper.type("1 234,56").kind);
        assertEquals(1234.56, CellValueTyper.type("1 234,56").number, 0.0001);
        assertEquals(CellValue.Kind.NUMBER, CellValueTyper.type("$4.50").kind);
        assertEquals(4.50, CellValueTyper.type("$4.50").number, 0.0001);
        // Percent stored as a fraction.
        assertEquals(0.125, CellValueTyper.type("12.5%").number, 0.0001);
        // Accounting negative.
        assertEquals(-100.0, CellValueTyper.type("(100)").number, 0.0001);

        assertEquals(CellValue.Kind.DATE, CellValueTyper.type("2026-01-15").kind);
        assertTrue(CellValueTyper.type("2026-01-15").number > 40000); // sane serial

        assertEquals(CellValue.Kind.BOOL, CellValueTyper.type("true").kind);
        assertEquals(CellValue.Kind.BOOL, CellValueTyper.type("FALSE").kind);

        assertEquals(CellValue.Kind.TEXT, CellValueTyper.type("Widget").kind);
        assertEquals(CellValue.Kind.TEXT, CellValueTyper.type("").kind);
        // A label that merely contains digits must not be mis-typed.
        assertEquals(CellValue.Kind.TEXT, CellValueTyper.type("Room 12A").kind);
    }

    // ------------------------------------------------------------------
    // Writer
    // ------------------------------------------------------------------

    @Test
    public void writesTypedGridWithHeaderAndValues() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table t = new Table();
        t.getAttributes().put("border", "ruled");
        t.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 100, ColumnSpec.Align.LEFT));
        t.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 80, ColumnSpec.Align.RIGHT));

        TableRow head = new TableRow(TableRow.Kind.HEADER);
        TableCell h1 = new TableCell();
        h1.setKind(TableCell.Kind.TH);
        h1.getChildren().add(para("Product"));
        TableCell h2 = new TableCell();
        h2.setKind(TableCell.Kind.TH);
        h2.getChildren().add(para("Price"));
        head.getCells().add(h1);
        head.getCells().add(h2);
        t.getRows().add(head);

        TableRow r1 = new TableRow(TableRow.Kind.BODY);
        r1.getCells().add(cell("Widget"));
        r1.getCells().add(cell("$4.50"));
        t.getRows().add(r1);

        doc.getChildren().add(t);

        byte[] xlsx = new SdmXlsxWriter().write(doc);
        Map<String, String> parts = unzip(xlsx);

        assertTrue(parts.containsKey("xl/workbook.xml"), "workbook present");
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"), "sheet present");
        assertTrue(parts.containsKey("xl/styles.xml"), "styles present");
        assertTrue(parts.containsKey("xl/sharedStrings.xml"), "sharedStrings present");
        assertEquals(1, count(parts.get("xl/workbook.xml"), "<sheet "));

        String sheet = parts.get("xl/worksheets/sheet1.xml");
        // Header text goes to shared strings (t="s"); the price is a native number.
        assertTrue(parts.get("xl/sharedStrings.xml").contains("Product"), "header string pooled");
        assertTrue(sheet.contains("<v>4.5</v>"), "price emitted as native number: " + sheet);
        // A header band → a frozen top row.
        assertTrue(sheet.contains("state=\"frozen\""), "header row frozen");
        // The typed value is recorded back on the model.
        CellValue cv = r1.getCells().get(1).getCellValue();
        assertNotNull(cv, "cell value typed on the model");
        assertEquals(CellValue.Kind.NUMBER, cv.getKind());
    }

    @Test
    public void mergesColSpanAndRowSpan() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table t = new Table();

        TableRow r0 = new TableRow(TableRow.Kind.BODY);
        TableCell span = new TableCell();
        span.setColSpan(3);
        span.getChildren().add(para("Title across three"));
        r0.getCells().add(span);
        t.getRows().add(r0);

        TableRow r1 = new TableRow(TableRow.Kind.BODY);
        TableCell tall = new TableCell();
        tall.setRowSpan(2);
        tall.getChildren().add(para("Tall"));
        r1.getCells().add(tall);
        r1.getCells().add(cell("b"));
        r1.getCells().add(cell("c"));
        t.getRows().add(r1);

        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("e"));
        r2.getCells().add(cell("f"));
        t.getRows().add(r2);

        doc.getChildren().add(t);

        byte[] xlsx = new SdmXlsxWriter().write(doc);
        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<mergeCells"), "mergeCells present: " + sheet);
        assertTrue(sheet.contains("A1:C1"), "colspan merge A1:C1: " + sheet);
        assertTrue(sheet.contains("A2:A3"), "rowspan merge A2:A3: " + sheet);
    }

    @Test
    public void oneWorksheetPerTableByDefault() throws IOException {
        SdmDocument doc = new SdmDocument();
        for (int i = 0; i < 2; i++) {
            Table t = new Table();
            TableRow r = new TableRow(TableRow.Kind.BODY);
            r.getCells().add(cell("x" + i));
            r.getCells().add(cell("y" + i));
            t.getRows().add(r);
            TableRow r2 = new TableRow(TableRow.Kind.BODY);
            r2.getCells().add(cell("a" + i));
            r2.getCells().add(cell("b" + i));
            t.getRows().add(r2);
            doc.getChildren().add(t);
        }
        Map<String, String> parts = unzip(new SdmXlsxWriter().write(doc));
        assertEquals(2, count(parts.get("xl/workbook.xml"), "<sheet "));
        assertTrue(parts.containsKey("xl/worksheets/sheet2.xml"), "second sheet present");

        // minimizeTheNumberOfWorksheets collapses to a single sheet.
        ExcelSaveOptions min = new ExcelSaveOptions();
        min.setMinimizeTheNumberOfWorksheets(true);
        Map<String, String> one = unzip(new SdmXlsxWriter(min).write(doc));
        assertEquals(1, count(one.get("xl/workbook.xml"), "<sheet "));
    }

    @Test
    public void carriesFontColourAndFillStyling() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table t = new Table();
        TableRow r = new TableRow(TableRow.Kind.BODY);

        // A cell with a coloured Times run on a yellow fill.
        TableCell styled = new TableCell();
        org.aspose.pdf.sdm.TextStyle ts = new org.aspose.pdf.sdm.TextStyle();
        ts.setFontFamily("Times-Roman");
        ts.setFontSize(14);
        ts.setColor(0xFFC00000); // dark red
        Paragraph p = new Paragraph();
        p.getInline().add(new Run("Alert", ts));
        styled.getChildren().add(p);
        org.aspose.pdf.sdm.BlockStyle bg = new org.aspose.pdf.sdm.BlockStyle();
        bg.setBackground(0xFFFFEB9C); // light yellow
        styled.setStyle(bg);
        r.getCells().add(styled);
        r.getCells().add(cell("plain"));
        t.getRows().add(r);
        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("a"));
        r2.getCells().add(cell("b"));
        t.getRows().add(r2);
        doc.getChildren().add(t);

        Map<String, String> parts = unzip(new SdmXlsxWriter().write(doc));
        String styles = parts.get("xl/styles.xml");
        assertTrue(styles.contains("Times-Roman"), "font family carried: " + styles);
        assertTrue(styles.contains("val=\"14\""), "font size carried");
        assertTrue(styles.contains("FFC00000"), "font colour carried");
        assertTrue(styles.contains("FFFFEB9C"), "cell fill carried");
        assertTrue(styles.contains("<border>") && styles.contains("thin"), "borders present");
    }

    @Test
    public void embedsCellImage() throws IOException {
        SdmDocument doc = new SdmDocument();
        // A tiny 2x2 PNG.
        java.awt.image.BufferedImage bi =
                new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
        bi.setRGB(0, 0, 0xFF0000);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(bi, "png", png);
        org.aspose.pdf.sdm.ResourceRef ref = doc.getResources().put("img1",
                new org.aspose.pdf.sdm.Resource(org.aspose.pdf.sdm.Resource.Kind.IMAGE,
                        png.toByteArray(), "image/png"));

        Table t = new Table();
        TableRow r = new TableRow(TableRow.Kind.BODY);
        TableCell iconCell = new TableCell();
        org.aspose.pdf.sdm.Figure fig = new org.aspose.pdf.sdm.Figure(ref);
        fig.getAttributes().put("display-width", 20.0);
        fig.getAttributes().put("display-height", 20.0);
        iconCell.getChildren().add(fig);
        r.getCells().add(iconCell);
        r.getCells().add(cell("Ruby"));
        t.getRows().add(r);
        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        r2.getCells().add(cell("x"));
        r2.getCells().add(cell("Emerald"));
        t.getRows().add(r2);
        doc.getChildren().add(t);

        Map<String, String> parts = unzip(new SdmXlsxWriter().write(doc));
        assertTrue(parts.containsKey("xl/drawings/drawing1.xml"), "drawing part present");
        assertTrue(parts.containsKey("xl/media/image1.png"), "media image present");
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("<drawing "), "sheet references drawing");
        assertTrue(parts.get("xl/drawings/drawing1.xml").contains("oneCellAnchor"), "image anchored to cell");
        assertTrue(parts.get("[Content_Types].xml").contains("image/png"), "png content type declared");
    }

    @Test
    public void proseFallbackWhenNoTable() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getChildren().add(para("Just a line of prose."));
        Map<String, String> parts = unzip(new SdmXlsxWriter().write(doc));
        assertEquals(1, count(parts.get("xl/workbook.xml"), "<sheet "));
        assertTrue(parts.get("xl/sharedStrings.xml").contains("Just a line of prose."),
                "prose captured on a sheet");
    }
}
