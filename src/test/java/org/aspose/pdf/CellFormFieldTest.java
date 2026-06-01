package org.aspose.pdf;

import org.aspose.pdf.forms.CheckboxField;
import org.aspose.pdf.forms.RadioButtonOptionField;
import org.aspose.pdf.forms.TextBoxField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 19 T1 — verifies that {@link Cell#add(BaseParagraph)} and the
 * form-field overloads correctly wrap and append to a cell's paragraphs.
 *
 * <p>Closes the structural blocker surfaced in Sprint 18 Part B where
 * {@code cell.getParagraphs().add(option)} failed to compile because
 * {@link RadioButtonOptionField} does not extend {@link BaseParagraph}.</p>
 */
class CellFormFieldTest {

    @Test
    void cellAdd_radioOption_wrapsInFormFieldParagraph() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Cell cell = new Cell();

            RadioButtonOptionField opt = new RadioButtonOptionField(page,
                    new Rectangle(50, 700, 70, 720));
            opt.setOptionName("Red");
            cell.add(opt);

            assertEquals(1, cell.getParagraphs().size());
            BaseParagraph p = cell.getParagraphs().get(0);
            assertTrue(p instanceof FormFieldParagraph,
                    "Expected FormFieldParagraph wrapper, got " + p.getClass().getSimpleName());
            FormFieldParagraph ffp = (FormFieldParagraph) p;
            assertSame(opt, ffp.getField());
            assertSame(opt, ffp.asOption());
            assertNull(ffp.asField(), "RBOF is not a Field subclass");
        }
    }

    @Test
    void cellAdd_checkbox_wrapsInFormFieldParagraph() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Cell cell = new Cell();

            CheckboxField cb = new CheckboxField(page, new Rectangle(50, 700, 70, 720));
            cell.add(cb);

            BaseParagraph p = cell.getParagraphs().get(0);
            assertTrue(p instanceof FormFieldParagraph);
            assertSame(cb, ((FormFieldParagraph) p).asField());
            assertNull(((FormFieldParagraph) p).asOption());
        }
    }

    @Test
    void cellAdd_textbox_wrapsInFormFieldParagraph() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Cell cell = new Cell();

            TextBoxField tf = new TextBoxField(page, new Rectangle(50, 700, 250, 720));
            cell.add(tf);

            BaseParagraph p = cell.getParagraphs().get(0);
            assertTrue(p instanceof FormFieldParagraph);
            assertSame(tf, ((FormFieldParagraph) p).asField());
        }
    }

    @Test
    void cellAdd_multipleFields_appendsInOrder() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Cell cell = new Cell();

            RadioButtonOptionField red = new RadioButtonOptionField(page,
                    new Rectangle(0, 0, 10, 10));
            red.setOptionName("Red");
            RadioButtonOptionField green = new RadioButtonOptionField(page,
                    new Rectangle(0, 0, 10, 10));
            green.setOptionName("Green");

            cell.add(red);
            cell.add(green);

            assertEquals(2, cell.getParagraphs().size());
            assertSame(red, ((FormFieldParagraph) cell.getParagraphs().get(0)).getField());
            assertSame(green, ((FormFieldParagraph) cell.getParagraphs().get(1)).getField());
        }
    }

    @Test
    void cellAdd_alongsideRegularParagraph_mixed() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Cell cell = new Cell();

            cell.add(new org.aspose.pdf.text.TextFragment("Choose:"));

            RadioButtonOptionField red = new RadioButtonOptionField(page,
                    new Rectangle(0, 0, 10, 10));
            red.setOptionName("Red");
            cell.add(red);

            assertEquals(2, cell.getParagraphs().size());
            assertTrue(cell.getParagraphs().get(0) instanceof org.aspose.pdf.text.TextFragment);
            assertTrue(cell.getParagraphs().get(1) instanceof FormFieldParagraph);
        }
    }

    @Test
    void cellAdd_nullArgs_throwIAE() {
        Cell cell = new Cell();
        assertThrows(IllegalArgumentException.class,
                () -> cell.add((RadioButtonOptionField) null));
        assertThrows(IllegalArgumentException.class,
                () -> cell.add((org.aspose.pdf.forms.Field) null));
        assertThrows(IllegalArgumentException.class,
                () -> cell.add((BaseParagraph) null));
    }

    @Test
    void formFieldParagraph_ctorNull_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> new FormFieldParagraph((org.aspose.pdf.forms.Field) null));
        assertThrows(IllegalArgumentException.class,
                () -> new FormFieldParagraph((RadioButtonOptionField) null));
    }
}
