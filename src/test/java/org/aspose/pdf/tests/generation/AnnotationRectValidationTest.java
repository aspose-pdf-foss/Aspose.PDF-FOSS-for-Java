package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Point;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.CaretAnnotation;
import org.aspose.pdf.annotations.SquareAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7 / Bug F — {@link org.aspose.pdf.annotations.Annotation#setRect(Rectangle)}
 * must reject degenerate (zero-area or negative-area) rectangles per ISO
 * 32000-1:2008 §12.5.2. {@link CaretAnnotation} also gains an
 * {@code atPoint(Page, Point)} helper for the common case of placing a caret
 * at a single coordinate.
 */
class AnnotationRectValidationTest {

    @Test
    @DisplayName("setRect with zero width is rejected with an IAE mentioning \"positive area\"")
    void setRect_zeroWidth_throws() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> new SquareAnnotation(page, new Rectangle(100, 100, 100, 120)));
            assertTrue(ex.getMessage().toLowerCase().contains("positive area"),
                    "IAE message should mention 'positive area', got: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("setRect with zero height is rejected")
    void setRect_zeroHeight_throws() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            assertThrows(IllegalArgumentException.class,
                    () -> new SquareAnnotation(page, new Rectangle(100, 100, 120, 100)));
        }
    }

    @Test
    @DisplayName("setRect with negative area (URX < LLX) is rejected")
    void setRect_negativeArea_throws() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            assertThrows(IllegalArgumentException.class,
                    () -> new SquareAnnotation(page, new Rectangle(200, 100, 100, 120)));
        }
    }

    @Test
    @DisplayName("setRect with a normal rect succeeds and round-trips")
    void setRect_validRect_succeeds(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("valid-rect.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            SquareAnnotation sq = new SquareAnnotation(page, new Rectangle(50, 60, 250, 200));
            page.getAnnotations().add(sq);
            doc.save(out.toString());
        }
        try (Document reopened = new Document(out.toString())) {
            Rectangle r = reopened.getPages().get(1).getAnnotations().get(1).getRect();
            assertNotNull(r);
            assertEquals(50,  r.getLLX(), 1e-6);
            assertEquals(60,  r.getLLY(), 1e-6);
            assertEquals(250, r.getURX(), 1e-6);
            assertEquals(200, r.getURY(), 1e-6);
        }
    }

    @Test
    @DisplayName("CaretAnnotation ctor with a degenerate rect is rejected, but atPoint(...) succeeds")
    void newCaret_defaultRect_isNonDegenerate() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            // Caret with a degenerate rect (single point treated as rect): rejected.
            assertThrows(IllegalArgumentException.class,
                    () -> new CaretAnnotation(page, new Rectangle(120, 600, 120, 600)));
            // The atPoint helper expands to a 12x12 box automatically.
            CaretAnnotation caret = CaretAnnotation.atPoint(page, new Point(120, 600));
            assertNotNull(caret);
            assertTrue(caret.getRect().getWidth()  > 0, "atPoint must produce positive width");
            assertTrue(caret.getRect().getHeight() > 0, "atPoint must produce positive height");
        }
    }

    @Test
    @DisplayName("CaretAnnotation.atPoint(page, (x, y)) produces /Rect [x-6, y-6, x+6, y+6]")
    void caretAtPoint_renderRectIs12x12() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            CaretAnnotation caret = CaretAnnotation.atPoint(page, new Point(100, 200));
            Rectangle r = caret.getRect();
            assertEquals( 94, r.getLLX(), 1e-6);
            assertEquals(194, r.getLLY(), 1e-6);
            assertEquals(106, r.getURX(), 1e-6);
            assertEquals(206, r.getURY(), 1e-6);
            assertEquals(12, r.getWidth(),  1e-6);
            assertEquals(12, r.getHeight(), 1e-6);
            // Null point is rejected as a regression guard.
            assertThrows(IllegalArgumentException.class,
                    () -> CaretAnnotation.atPoint(page, null));
        }
    }
}
