package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.DocumentInfo;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 12 / Bug K — {@link Document#getInfo()} must auto-create an empty
 * {@code /Info} dictionary on writable documents so that callers can do
 * {@code doc.getInfo().setTitle(...)} without an NPE.
 *
 * <p>References ISO 32000-1:2008 §14.3.3 (Document Information Dictionary).</p>
 */
class DocumentInfoAutoCreateTest {

    @Test
    @DisplayName("Fresh Document.getInfo() returns a non-null DocumentInfo")
    void freshDocument_getInfoReturnsNonNull() throws IOException {
        try (Document doc = new Document()) {
            assertNotNull(doc.getInfo(),
                    "getInfo() must auto-create on a fresh document");
        }
    }

    @Test
    @DisplayName("setTitle on fresh document round-trips after save+reopen")
    void setTitle_onFreshDocument_writesThrough(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("info-roundtrip.pdf");
        try (Document doc = new Document()) {
            doc.getPages().add();
            doc.getInfo().setTitle("Sprint20 Title");
            doc.getInfo().setAuthor("Andrey");
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            DocumentInfo reinfo = reopened.getInfo();
            assertNotNull(reinfo, "reopened doc must expose /Info");
            assertEquals("Sprint20 Title", reinfo.getTitle());
            assertEquals("Andrey", reinfo.getAuthor());
        }
    }

    @Test
    @DisplayName("getInfo() returns the same instance across calls")
    void getInfo_calledTwice_returnsSameInstance() throws IOException {
        try (Document doc = new Document()) {
            DocumentInfo a = doc.getInfo();
            DocumentInfo b = doc.getInfo();
            assertSame(a, b, "consecutive getInfo() calls must return cached instance");
        }
    }

    @Test
    @DisplayName("getOrCreateInfo() is still callable but marked @Deprecated")
    void getOrCreateInfo_isStillAvailable_butDeprecated() throws Exception {
        Method m = Document.class.getMethod("getOrCreateInfo");
        assertNotNull(m.getAnnotation(Deprecated.class),
                "getOrCreateInfo() must carry @Deprecated for backwards compat signal");
        // Behavioural check: still returns a DocumentInfo, same as getInfo().
        try (Document doc = new Document()) {
            DocumentInfo viaGet = doc.getInfo();
            DocumentInfo viaOld = doc.getOrCreateInfo();
            assertSame(viaGet, viaOld, "deprecated alias must return the same instance as getInfo()");
        }
    }

    @Test
    @DisplayName("Reopened PDF whose trailer has no /Info still gets a non-null DocumentInfo")
    void reopenDocumentWithoutInfo_getInfoCreatesEmpty(@TempDir Path tmp) throws IOException {
        // The FOSS writer always stamps a Producer/Creator /Info on save
        // (PDFNET_49363_1), so a round-tripped file can no longer lack /Info.
        // Feed a hand-built minimal PDF whose trailer genuinely has no /Info.
        Path out = tmp.resolve("no-info.pdf");
        java.nio.file.Files.write(out, minimalPdfWithoutInfo());

        try (Document reopened = new Document(out.toString())) {
            // Sanity: the input genuinely has no /Info entry in its trailer.
            PdfDictionary trailer = reopened.getTrailer();
            assertFalse(trailer.containsKey("Info"),
                    "test premise: hand-built input must have no /Info entry");

            DocumentInfo created = reopened.getInfo();
            assertNotNull(created,
                    "getInfo() on a file lacking /Info must still return a writable empty instance");
            assertNull(created.getTitle(), "freshly-created info has no Title");
            created.setTitle("PostHoc");
            assertEquals("PostHoc", reopened.getInfo().getTitle(),
                    "subsequent getInfo() sees the mutation");
        }
    }

    /**
     * Builds a minimal, valid single-page PDF 1.7 whose trailer contains only
     * /Size and /Root — deliberately no /Info — with a correct cross-reference
     * table (byte offsets computed as the body is assembled).
     */
    private static byte[] minimalPdfWithoutInfo() throws IOException {
        String[] objects = {
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
        };
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        int[] offsets = new int[objects.length + 1]; // index 0 unused (free object)
        for (int i = 0; i < objects.length; i++) {
            offsets[i + 1] = body.size();
            body.write(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n")
                    .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        java.io.ByteArrayOutputStream pdf = new java.io.ByteArrayOutputStream();
        pdf.write("%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        int bodyStart = pdf.size();
        pdf.write(body.toByteArray());

        int xrefStart = pdf.size();
        StringBuilder xref = new StringBuilder();
        xref.append("xref\n0 ").append(objects.length + 1).append('\n');
        xref.append("0000000000 65535 f \n");
        for (int i = 1; i <= objects.length; i++) {
            xref.append(String.format("%010d 00000 n \n", bodyStart + offsets[i]));
        }
        xref.append("trailer\n<< /Size ").append(objects.length + 1)
                .append(" /Root 1 0 R >>\n");
        xref.append("startxref\n").append(xrefStart).append("\n%%EOF\n");
        pdf.write(xref.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return pdf.toByteArray();
    }
}
