package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.HighlightAnnotation;
import org.aspose.pdf.annotations.SquigglyAnnotation;
import org.aspose.pdf.annotations.StrikeOutAnnotation;
import org.aspose.pdf.annotations.UnderlineAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 6 / Bug E — text-markup annotations must auto-derive {@code /QuadPoints}
 * in {@code [TLx TLy TRx TRy BLx BLy BRx BRy]} order per
 * ISO 32000-1:2008 §12.5.6.10 Table 179. Without that order, strict viewers
 * (Poppler/MuPDF) emit {@code "Bad Annot Text Markup QuadPoints"} and the
 * decoration disappears.
 */
class MarkupAnnotationQuadPointsTest {

    private static final double EPS = 1e-6;

    /** Rect(100, 700, 200, 720) ⇒ expected [TLx TLy TRx TRy BLx BLy BRx BRy]. */
    private static final double[] EXPECTED = {
            100, 720,  // top-left  (llx, ury)
            200, 720,  // top-right (urx, ury)
            100, 700,  // bottom-left  (llx, lly)
            200, 700,  // bottom-right (urx, lly)
    };

    private double[] roundTrip(BiFunction<Page, Rectangle, Annotation> ctor, Path tmp,
                               String label) throws IOException {
        Path out = tmp.resolve(label + ".pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getAnnotations().add(ctor.apply(page, new Rectangle(100, 700, 200, 720)));
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            Annotation a = reopened.getPages().get(1).getAnnotations().get(1);
            if (a instanceof HighlightAnnotation)   return ((HighlightAnnotation) a).getQuadPoints();
            if (a instanceof UnderlineAnnotation)   return ((UnderlineAnnotation) a).getQuadPoints();
            if (a instanceof StrikeOutAnnotation)   return ((StrikeOutAnnotation) a).getQuadPoints();
            if (a instanceof SquigglyAnnotation)    return ((SquigglyAnnotation) a).getQuadPoints();
            throw new AssertionError("unexpected annotation type: " + a.getClass());
        }
    }

    private void assertQuadPointsMatch(double[] qp) {
        assertNotNull(qp, "/QuadPoints must be present");
        assertEquals(8, qp.length, "single-rect markup must emit exactly 8 values");
        for (int i = 0; i < 8; i++) {
            assertEquals(EXPECTED[i], qp[i], EPS,
                    "/QuadPoints[" + i + "] mismatch: expected " + EXPECTED[i] + " got " + qp[i]);
        }
    }

    @Test
    @DisplayName("Highlight auto-derives QuadPoints in TL/TR/BL/BR order")
    void highlight_autoDerivedQuadPoints_areInTLTRBLBROrder(@TempDir Path tmp) throws IOException {
        assertQuadPointsMatch(roundTrip(HighlightAnnotation::new, tmp, "highlight"));
    }

    @Test
    @DisplayName("Underline auto-derives QuadPoints in TL/TR/BL/BR order")
    void underline_autoDerivedQuadPoints_areInTLTRBLBROrder(@TempDir Path tmp) throws IOException {
        assertQuadPointsMatch(roundTrip(UnderlineAnnotation::new, tmp, "underline"));
    }

    @Test
    @DisplayName("StrikeOut auto-derives QuadPoints in TL/TR/BL/BR order")
    void strikeOut_autoDerivedQuadPoints_areInTLTRBLBROrder(@TempDir Path tmp) throws IOException {
        assertQuadPointsMatch(roundTrip(StrikeOutAnnotation::new, tmp, "strikeout"));
    }

    @Test
    @DisplayName("Squiggly auto-derives QuadPoints in TL/TR/BL/BR order")
    void squiggly_autoDerivedQuadPoints_areInTLTRBLBROrder(@TempDir Path tmp) throws IOException {
        assertQuadPointsMatch(roundTrip(SquigglyAnnotation::new, tmp, "squiggly"));
    }

    @Test
    @DisplayName("All quad-point coords lie within the annotation's /Rect")
    void quadPoints_allValuesLieInsideRect(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("all-markups.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getAnnotations().add(new HighlightAnnotation(page, new Rectangle(100, 700, 200, 720)));
            page.getAnnotations().add(new UnderlineAnnotation(page, new Rectangle(100, 680, 200, 700)));
            page.getAnnotations().add(new StrikeOutAnnotation(page, new Rectangle(100, 660, 200, 680)));
            page.getAnnotations().add(new SquigglyAnnotation(page, new Rectangle(100, 640, 200, 660)));
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            Page page = reopened.getPages().get(1);
            for (int i = 1; i <= 4; i++) {
                Annotation a = page.getAnnotations().get(i);
                double[] qp;
                if      (a instanceof HighlightAnnotation) qp = ((HighlightAnnotation) a).getQuadPoints();
                else if (a instanceof UnderlineAnnotation) qp = ((UnderlineAnnotation) a).getQuadPoints();
                else if (a instanceof StrikeOutAnnotation) qp = ((StrikeOutAnnotation) a).getQuadPoints();
                else                                       qp = ((SquigglyAnnotation) a).getQuadPoints();
                Rectangle rect = a.getRect();
                for (int j = 0; j < qp.length; j += 2) {
                    double x = qp[j], y = qp[j + 1];
                    assertTrue(x >= rect.getLLX() - EPS && x <= rect.getURX() + EPS,
                            a.getClass().getSimpleName() + ": x=" + x + " out of [LLX="
                                    + rect.getLLX() + ", URX=" + rect.getURX() + "]");
                    assertTrue(y >= rect.getLLY() - EPS && y <= rect.getURY() + EPS,
                            a.getClass().getSimpleName() + ": y=" + y + " out of [LLY="
                                    + rect.getLLY() + ", URY=" + rect.getURY() + "]");
                }
            }
        }
    }

    @Test
    @DisplayName("setQuadPoints(double[][]) writes a concatenated flat array")
    void setQuadPoints_multiQuad_overload_writesConcatenated(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("multi-quad.pdf");
        double[] quad1 = {100, 720, 200, 720, 100, 700, 200, 700};
        double[] quad2 = {100, 700, 200, 700, 100, 680, 200, 680};
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            // Outer /Rect must cover both quads to keep "all values inside Rect" invariant.
            HighlightAnnotation h = new HighlightAnnotation(page, new Rectangle(100, 680, 200, 720));
            h.setQuadPoints(new double[][] {quad1, quad2});
            page.getAnnotations().add(h);
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            HighlightAnnotation h = (HighlightAnnotation)
                    reopened.getPages().get(1).getAnnotations().get(1);
            double[] qp = h.getQuadPoints();
            assertEquals(16, qp.length, "multi-quad overload must produce 16 values for two quads");
            for (int i = 0; i < 8; i++) {
                assertEquals(quad1[i], qp[i], EPS, "quad1[" + i + "]");
                assertEquals(quad2[i], qp[i + 8], EPS, "quad2[" + i + "]");
            }
        }
    }
}
