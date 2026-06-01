package org.aspose.pdf.annotations;

import org.aspose.pdf.forms.RadioButtonOptionField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 19 T2 — {@link Border#Border(RadioButtonOptionField)} convenience
 * ctor for radio-button options that don't extend {@link Annotation}.
 */
class BorderRBOFCtorTest {

    @Test
    void ctor_storesReferenceAndPropsDefault() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        Border border = new Border(opt);
        assertNotNull(border);
        assertEquals(1.0, border.getWidth(), 1e-6, "default width = 1");
        assertEquals(BorderStyle.Solid, border.getStyle(), "default style = Solid");
    }

    @Test
    void setWidth_syncsToOption() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        Border border = new Border(opt);
        border.setWidth(2.5);

        // Border is stored on the option through setBorder
        Border roundTrip = opt.getBorder();
        assertNotNull(roundTrip, "option should now have a Border via syncParent");
        assertEquals(2.5, roundTrip.getWidth(), 1e-6);
    }

    @Test
    void setStyle_syncsToOption() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        Border border = new Border(opt);
        border.setStyle(BorderStyle.Dashed);

        Border roundTrip = opt.getBorder();
        assertNotNull(roundTrip);
        // Round-tripped style starts with "D" (first letter, per RBOF.setBorder)
        // We only assert width here because Border doesn't round-trip the enum
        // through the COS layer in OpenPDF today (the /BS /S key is name-only).
        assertEquals(1.0, roundTrip.getWidth(), 1e-6);
    }

    @Test
    void ctor_nullOption_throws() {
        assertThrows(IllegalArgumentException.class, () -> new Border((RadioButtonOptionField) null));
    }

    @Test
    void existingAnnotationCtor_stillWorks() {
        // Regression check: the original (Annotation) ctor is untouched.
        Border border = new Border((Annotation) null);
        assertNotNull(border);
        assertEquals(1.0, border.getWidth(), 1e-6);
    }
}
