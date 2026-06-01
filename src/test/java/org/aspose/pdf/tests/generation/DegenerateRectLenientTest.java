package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.FreeTextAnnotation;
import org.aspose.pdf.annotations.LineAnnotation;
import org.aspose.pdf.annotations.TextAnnotation;
import org.aspose.pdf.forms.CheckboxField;
import org.aspose.pdf.forms.TextBoxField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 21 / A2 — F-10 sibling cluster. The strict positive-area check on
 * {@link org.aspose.pdf.annotations.Annotation#setRect(Rectangle)} must not
 * leak into the internal construction / incremental-sizing paths used by form
 * fields and naturally-degenerate annotation types. Aspose.PDF stores such
 * rectangles rather than rejecting them, so these constructors must not throw.
 *
 * @see AnnotationRectValidationTest for the public-API strictness contract that
 *      is intentionally preserved.
 */
class DegenerateRectLenientTest {

    @Test
    @DisplayName("CheckboxField.setWidth before setHeight (transient 20x0 rect) does not throw")
    void checkbox_setWidthThenHeight_doesNotThrow() {
        CheckboxField chk = new CheckboxField();
        chk.setPartialName("cb");
        // setWidth runs while height is still 0 -> 20x0 intermediate rect.
        assertDoesNotThrow(chk::regenerateAppearance, "fresh checkbox should be safe");
        assertDoesNotThrow(() -> chk.setWidth(20));
        assertDoesNotThrow(() -> chk.setHeight(20));
        Rectangle r = chk.getRect();
        assertEquals(20, r.getWidth(), 1e-6);
        assertEquals(20, r.getHeight(), 1e-6);
    }

    @Test
    @DisplayName("Zero-width FreeTextAnnotation constructor stores the rect instead of throwing")
    void freeText_zeroWidth_doesNotThrow() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            FreeTextAnnotation ft = assertDoesNotThrow(() ->
                    new FreeTextAnnotation(page, new Rectangle(100, 100, 100, 120)));
            assertEquals(0, ft.getRect().getWidth(), 1e-6);
        }
    }

    @Test
    @DisplayName("Zero-height LineAnnotation (horizontal line) constructor does not throw")
    void line_zeroHeight_doesNotThrow() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            LineAnnotation ln = assertDoesNotThrow(() ->
                    new LineAnnotation(page, new Rectangle(50, 200, 226, 200)));
            assertEquals(0, ln.getRect().getHeight(), 1e-6);
        }
    }

    @Test
    @DisplayName("Point-like TextAnnotation (0x0 rect) constructor does not throw")
    void text_pointRect_doesNotThrow() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            assertDoesNotThrow(() ->
                    new TextAnnotation(page, new Rectangle(120, 600, 120, 600)));
        }
    }

    @Test
    @DisplayName("Negative-area TextBoxField (LLY > URY) constructor does not throw")
    void textBox_negativeArea_doesNotThrow() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            assertDoesNotThrow(() ->
                    new TextBoxField(page, new Rectangle(100, 100, 200, 80)));
        }
    }
}
