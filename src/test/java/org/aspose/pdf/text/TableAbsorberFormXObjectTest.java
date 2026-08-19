package org.aspose.pdf.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.aspose.pdf.Document;
import org.junit.jupiter.api.Test;

/**
 * A ruled table whose grid + text are drawn inside a Form XObject (the whole page
 * is one {@code Do}, as many tagged clinical PDFs are authored) must still be
 * detected — {@link TableAbsorber} recurses into the form to collect its rules.
 */
class TableAbsorberFormXObjectTest {

    /** 2x2 grid (x 100/150/200, y 100/150/200) + one word per cell, all in a form. */
    private static final String FORM =
            "1 w\n"
            + "100 100 m 200 100 l S\n100 150 m 200 150 l S\n100 200 m 200 200 l S\n"
            + "100 100 m 100 200 l S\n150 100 m 150 200 l S\n200 100 m 200 200 l S\n"
            + "BT /F0 8 Tf 108 172 Td (Name) Tj ET\n"
            + "BT /F0 8 Tf 158 172 Td (Value) Tj ET\n"
            + "BT /F0 8 Tf 108 122 Td (Total) Tj ET\n"
            + "BT /F0 8 Tf 158 122 Td (42) Tj ET\n";

    private static byte[] pdfWithFormGrid() {
        String pageContent = "q /F1 Do Q\n";
        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        String[] objs = {
                "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
                "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 300] "
                        + "/Resources << /XObject << /F1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n",
                "4 0 obj\n<< /Length " + pageContent.length() + " >>\nstream\n"
                        + pageContent + "\nendstream\nendobj\n",
                "5 0 obj\n<< /Type /XObject /Subtype /Form /BBox [0 0 300 300] "
                        + "/Resources << /Font << /F0 6 0 R >> >> /Length " + FORM.length()
                        + " >>\nstream\n" + FORM + "\nendstream\nendobj\n",
                "6 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
        };
        int[] offsets = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            offsets[i] = body.length();
            body.append(objs[i]);
        }
        int xrefPos = body.length();
        body.append("xref\n0 ").append(objs.length + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) {
            body.append(String.format("%010d 00000 n \n", off));
        }
        body.append("trailer\n<< /Size ").append(objs.length + 1)
            .append(" /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF");
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void ruledGridInsideFormXObjectIsDetected() throws Exception {
        try (Document doc = new Document(new ByteArrayInputStream(pdfWithFormGrid()))) {
            TableAbsorber ab = new TableAbsorber();
            ab.visit(doc.getPages().get(1));
            List<AbsorbedTable> tables = ab.getTableList();
            assertEquals(1, tables.size(), "the form's ruled grid is found");
            AbsorbedTable t = tables.get(0);
            assertEquals(2, t.getRowList().size(), "2 rows");
            assertEquals(2, t.getRowList().get(0).getCellList().size(), "2 columns");
            String c00 = t.getRowList().get(0).getCellList().get(0).getText().trim();
            String c11 = t.getRowList().get(1).getCellList().get(1).getText().trim();
            assertTrue(c00.contains("Name"), "top-left cell text: " + c00);
            assertTrue(c11.contains("42"), "bottom-right cell text: " + c11);
        }
    }
}
