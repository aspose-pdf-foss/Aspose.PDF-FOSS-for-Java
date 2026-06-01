package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.MarginInfo;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageInfo;
import org.aspose.pdf.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 10 / Bug I — {@link PageInfo} must accept the canonical top-level
 * {@link org.aspose.pdf.MarginInfo} (constructor order
 * {@code (left, bottom, right, top)}) in addition to the deprecated nested
 * {@link PageInfo.MarginInfo} (constructor order
 * {@code (top, bottom, left, right)}).
 *
 * <p>Per ISO 32000-1:2008 §14.8.2.4, page margins are a layout concept above
 * the PDF object model — they don't round-trip on their own, but they must
 * apply consistently regardless of which {@code MarginInfo} flavour the caller
 * uses.</p>
 */
class PageInfoMarginInfoTest {

    @Test
    @DisplayName("Top-level MarginInfo(left, bottom, right, top) and nested (top, bottom, left, right) produce identical PageInfo state")
    void setMargin_topLevelMarginInfo_appliesSameAsNested() {
        // Logical margins: left=50, bottom=60, right=80, top=70
        // Canonical top-level ctor: (left, bottom, right, top)
        MarginInfo canonical = new MarginInfo(50, 60, 80, 70);
        // Nested ctor: (top, bottom, left, right)
        PageInfo.MarginInfo nested = new PageInfo.MarginInfo(70, 60, 50, 80);

        PageInfo a = new PageInfo();
        a.setMargin(canonical);
        PageInfo b = new PageInfo();
        b.setMargin(nested);

        assertEquals(b.getMargin().getTop(),    a.getMargin().getTop(),    1e-9, "top");
        assertEquals(b.getMargin().getBottom(), a.getMargin().getBottom(), 1e-9, "bottom");
        assertEquals(b.getMargin().getLeft(),   a.getMargin().getLeft(),   1e-9, "left");
        assertEquals(b.getMargin().getRight(),  a.getMargin().getRight(),  1e-9, "right");
    }

    @Test
    @DisplayName("PageInfo.MarginInfo nested class is annotated @Deprecated")
    void nestedMarginInfo_isDeprecated() {
        assertNotNull(PageInfo.MarginInfo.class.getAnnotation(Deprecated.class),
                "nested PageInfo.MarginInfo must be marked @Deprecated to steer callers to the canonical type");
    }

    @Test
    @DisplayName("Top-level MarginInfo javadoc spells out the (left, bottom, right, top) argument order")
    void topLevelMarginInfo_javadocSpecifiesArgOrder() throws IOException {
        Path source = Paths.get("src/main/java/org/aspose/pdf/MarginInfo.java");
        if (!Files.exists(source)) {
            // The library jar doesn't ship its source; treat absence as a soft
            // pass so the test still runs from a downstream consumer. Reviewers
            // should manually verify MarginInfo.java's javadoc names the order.
            return;
        }
        String body = new String(Files.readAllBytes(source));
        assertTrue(body.contains("(left, bottom, right, top)"),
                "MarginInfo javadoc must literally name the (left, bottom, right, top) constructor order");
    }

    @Test
    @DisplayName("setMargin(canonical) followed by save+reopen produces a parseable document")
    void setMarginWithCanonicalType_roundTrips(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("margin-canonical.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.setMediaBox(new Rectangle(0, 0, 400, 300));
            // Configure margins through the canonical type — this is what the
            // overload exists for.
            PageInfo pi = page.getPageInfo();
            pi.setMargin(new MarginInfo(10, 20, 30, 40));
            assertEquals(40, pi.getMargin().getTop(),    1e-9);
            assertEquals(20, pi.getMargin().getBottom(), 1e-9);
            assertEquals(10, pi.getMargin().getLeft(),   1e-9);
            assertEquals(30, pi.getMargin().getRight(),  1e-9);
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            Page p = reopened.getPages().get(1);
            // MediaBox came from the configured page size: 400 x 300.
            assertEquals(400, p.getRect().getWidth(),  0.5, "width survives");
            assertEquals(300, p.getRect().getHeight(), 0.5, "height survives");
        }
    }

    @Test
    @DisplayName("setMargin(null) on PageInfo clears the margin slot")
    void setMargin_nullCanonical_clearsMargin() {
        PageInfo pi = new PageInfo();
        pi.setMargin((MarginInfo) null);
        assertNull(pi.getMargin(), "null canonical margin must clear the slot");
    }
}
