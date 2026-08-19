package org.aspose.pdf.engine.parser;

import org.aspose.pdf.Document;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the catalog-recovery path in {@link PDFParser} when the trailer
 * {@code /Root} reference is wrong (off by object number) and the xref offsets
 * are stale, so the regular catalog lookup fails and the parser must fall
 * back to scanning the raw body for an indirect object whose dictionary is
 * tagged {@code /Type /Catalog}. Mirrors the real-world {@code 34129.pdf}
 * shape.
 */
public class ParserRecoveryTest {

    @Test
    public void recoversCatalogByDirectScan_whenTrailerRootPointsAtWrongObject() throws Exception {
        // Build a minimal PDF where:
        //   - The trailer says /Root 99 0 R (no such object exists).
        //   - The xref lists obj 99 at an offset that hits an integer literal.
        //   - The real catalog is at object 3, properly tagged /Type /Catalog.
        // The parser must locate the catalog by scanning indirect objects for
        // /Type /Catalog rather than trusting the trailer reference.
        StringBuilder body = new StringBuilder();
        body.append("%PDF-1.4\n%âãÏÓ\n");
        int obj99Pos = body.length();
        body.append("99 0 obj\n42\nendobj\n");           // bogus object the trailer points at
        int pagesPos = body.length();
        body.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n");
        int catalogPos = body.length();
        body.append("3 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int xrefPos = body.length();
        body.append("xref\n0 100\n");
        body.append("0000000000 65535 f \n");
        // pad with free entries up to 99 so /Size 100 stays consistent; only 2,3,99 in-use
        for (int i = 1; i <= 99; i++) {
            String slot;
            if (i == 2) slot = pad10(pagesPos);
            else if (i == 3) slot = pad10(catalogPos);
            else if (i == 99) slot = pad10(obj99Pos);   // points at the integer-42 object
            else slot = "0000000000";
            body.append(slot).append(" 00000 ").append(i == 2 || i == 3 || i == 99 ? "n" : "f").append(" \n");
        }
        body.append("trailer << /Size 100 /Root 99 0 R >>\nstartxref\n")
            .append(xrefPos).append("\n%%EOF\n");
        byte[] pdf = body.toString().getBytes(StandardCharsets.ISO_8859_1);

        // The trailer /Root 99 0 R points at an integer, not a Catalog. The
        // parser must scan for /Type /Catalog and pick obj 3 instead, without
        // raising IOException("Cannot find /Root catalog dictionary in trailer").
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            assertNotNull(doc.getPages(), "pages collection must be available after catalog recovery");
            assertEquals(0, doc.getPages().getCount(), "empty pages tree round-trips");
        }
    }

    /**
     * Pins the actual user-visible behaviour from PDFNEWNET_34129: the parser
     * must NOT throw {@code Cannot find /Root catalog dictionary in trailer}
     * on a PDF whose trailer {@code /Root} points at the wrong object.
     */
    @Test
    public void noCatalogTrailerError_onTrailerRootMismatch() throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("%PDF-1.4\n");
        int obj99Pos = body.length();
        body.append("99 0 obj\n7\nendobj\n");
        int pagesPos = body.length();
        body.append("2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n");
        int catalogPos = body.length();
        body.append("3 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        int xrefPos = body.length();
        body.append("xref\n0 100\n0000000000 65535 f \n");
        for (int i = 1; i <= 99; i++) {
            String slot;
            if (i == 2) slot = pad10(pagesPos);
            else if (i == 3) slot = pad10(catalogPos);
            else if (i == 99) slot = pad10(obj99Pos);
            else slot = "0000000000";
            body.append(slot).append(" 00000 ").append(i == 2 || i == 3 || i == 99 ? "n" : "f").append(" \n");
        }
        body.append("trailer << /Size 100 /Root 99 0 R >>\nstartxref\n")
            .append(xrefPos).append("\n%%EOF\n");
        byte[] pdf = body.toString().getBytes(StandardCharsets.ISO_8859_1);
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            // Just opening + dereferencing pages is enough: pre-fix this
            // chain throws IOException from PDFParser.getCatalog.
            assertEquals(0, doc.getPages().getCount());
        }
    }

    /**
     * Some producers omit {@code /Type /Catalog} on the root object entirely
     * (corpus PdfForm5.pdf: {@code 1 0 obj <</Pages 3 0 R /AcroForm ...>>}).
     * Acrobat accepts such roots; the parser must too when the dictionary has
     * a /Pages entry and no conflicting /Type.
     */
    @Test
    public void acceptsCatalogWithoutTypeEntry() throws Exception {
        StringBuilder body = new StringBuilder();
        body.append("%PDF-1.4\n");
        int catalogPos = body.length();
        body.append("1 0 obj\n<< /Pages 2 0 R >>\nendobj\n");
        int pagesPos = body.length();
        body.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
        int pagePos = body.length();
        body.append("3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n");
        int xrefPos = body.length();
        body.append("xref\n0 4\n0000000000 65535 f \n")
            .append(pad10(catalogPos)).append(" 00000 n \n")
            .append(pad10(pagesPos)).append(" 00000 n \n")
            .append(pad10(pagePos)).append(" 00000 n \n")
            .append("trailer << /Size 4 /Root 1 0 R >>\nstartxref\n")
            .append(xrefPos).append("\n%%EOF\n");
        byte[] pdf = body.toString().getBytes(StandardCharsets.ISO_8859_1);
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            assertEquals(1, doc.getPages().getCount(),
                    "typeless catalog with /Pages must open without recovery");
        }
    }

    private static String pad10(int n) {
        StringBuilder s = new StringBuilder(Integer.toString(n));
        while (s.length() < 10) s.insert(0, '0');
        return s.toString();
    }
}
