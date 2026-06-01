package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.DocumentInfo;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
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
        // Build a doc, save it, then verify behaviour on reopen.
        Path out = tmp.resolve("no-info.pdf");
        try (Document doc = new Document()) {
            doc.getPages().add();
            // Do NOT touch getInfo(): we want the saved file to have no /Info.
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            // Sanity: the saved file genuinely has no /Info entry in its
            // trailer (writer drops empty info dicts at save time).
            COSDictionary trailer = reopened.getTrailer();
            assertFalse(trailer.containsKey("Info"),
                    "test premise: writer must have dropped the empty /Info entry");

            DocumentInfo created = reopened.getInfo();
            assertNotNull(created,
                    "getInfo() on a file lacking /Info must still return a writable empty instance");
            assertNull(created.getTitle(), "freshly-created info has no Title");
            created.setTitle("PostHoc");
            assertEquals("PostHoc", reopened.getInfo().getTitle(),
                    "subsequent getInfo() sees the mutation");
        }
    }
}
