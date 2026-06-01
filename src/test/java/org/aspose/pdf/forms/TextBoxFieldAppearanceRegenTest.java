package org.aspose.pdf.forms;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.XForm;
import org.aspose.pdf.operators.SelectFont;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-058 — {@code TextBoxField.setValue} must regenerate the {@code /AP /N}
 * appearance stream so that downstream readers can extract per-field font size
 * (used by auto-shrink-to-fit form widgets and by visual renderers).
 */
class TextBoxFieldAppearanceRegenTest {

    @Test
    void setValue_emits_AP_N_streamWith_Tf() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(50, 700, 250, 730));
            tf.setPartialName("name");

            assertNull(tf.getNormalAppearance(), "no AP/N expected on a fresh field");

            tf.setValue("Hello");

            XForm n = tf.getNormalAppearance();
            assertNotNull(n, "AP/N must be created by setValue");

            OperatorCollection ops = n.getContents();
            SelectFont sf = findSelectFont(ops);
            assertNotNull(sf, "SelectFont (Tf) operator must be present in AP/N");
            assertTrue(sf.getSize() > 0, "font size must be > 0; got " + sf.getSize());
        }
    }

    @Test
    void setValue_longText_autoShrinksFontSize() throws Exception {
        // Simulates PDFNET_58367 conditions: a narrow rect plus a long value;
        // auto-shrink should bring the size down close to 4.5pt.
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(50, 700, 170, 730));
            tf.setPartialName("name");
            tf.setValue("TestTestTestTestTestTestTestTestTestTestTestTestTest"); // 52 chars

            XForm n = tf.getNormalAppearance();
            assertNotNull(n);
            SelectFont sf = findSelectFont(n.getContents());
            assertNotNull(sf);
            assertTrue(sf.getSize() > 1.0 && sf.getSize() < 6.5,
                    "auto-shrunk size should be small; got " + sf.getSize());
        }
    }

    @Test
    void setValue_clearedNullValue_stillRegeneratesAppearance() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(50, 700, 150, 730));
            tf.setPartialName("name");
            tf.setValue("first");
            XForm n1 = tf.getNormalAppearance();
            assertNotNull(n1);

            tf.setValue(null);   // clears /V
            XForm n2 = tf.getNormalAppearance();
            assertNotNull(n2, "AP/N must still be present after setValue(null)");
            SelectFont sf = findSelectFont(n2.getContents());
            assertNotNull(sf);
            assertEquals(12.0, sf.getSize(), 1e-6,
                    "auto-shrink on empty text caps at 12pt");
        }
    }

    @Test
    void regenerateAppearance_overwritesPreviousAP() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tf = new TextBoxField(page, new Rectangle(50, 700, 250, 730));
            tf.setPartialName("name");
            tf.setValue("short");
            double sizeShort = findSelectFont(tf.getNormalAppearance().getContents()).getSize();

            tf.setValue("a much longer string that will not fit at the same size");
            double sizeLong = findSelectFont(tf.getNormalAppearance().getContents()).getSize();
            assertTrue(sizeLong < sizeShort,
                    "auto-shrink must pick a smaller size for a longer value; short="
                            + sizeShort + " long=" + sizeLong);
        }
    }

    private static SelectFont findSelectFont(OperatorCollection ops) {
        for (Operator op : ops) {
            if (op instanceof SelectFont) return (SelectFont) op;
        }
        return null;
    }
}
