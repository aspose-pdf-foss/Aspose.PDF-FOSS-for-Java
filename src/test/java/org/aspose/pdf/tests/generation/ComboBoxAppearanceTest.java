package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.forms.ComboBoxField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 22 Part 3 — F-10 sibling for {@link ComboBoxField}. A combo box must
 * generate a real {@code /AP/N} appearance stream (not a {@code COSNull}
 * placeholder, not a missing entry) so strict viewers render the selected text.
 */
class ComboBoxAppearanceTest {

    private static COSStream normalAppearance(ComboBoxField cb) {
        COSBase ap = cb.getCOSDictionary().get(COSName.of("AP"));
        assertTrue(ap instanceof COSDictionary, "/AP should be a dictionary");
        COSBase n = ((COSDictionary) ap).get(COSName.N);
        assertTrue(n instanceof COSStream, "/AP/N should be a real Form XObject stream");
        return (COSStream) n;
    }

    @Test
    @DisplayName("ComboBox ctor builds a real /AP/N Form XObject (no COSNull)")
    void ctor_buildsAppearanceStream() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            ComboBoxField cb = new ComboBoxField(page, new Rectangle(50, 100, 250, 130));
            COSStream n = normalAppearance(cb);
            assertEquals(COSName.of("Form"), n.get(COSName.SUBTYPE));
            assertNotNull(n.get(COSName.BBOX));
        }
    }

    @Test
    @DisplayName("setSelected refreshes the appearance to show the chosen value")
    void setSelected_rendersValue() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            ComboBoxField cb = new ComboBoxField(page, new Rectangle(50, 100, 250, 130));
            cb.setPartialName("country");
            cb.addOption("USA");
            cb.addOption("UK");
            cb.setSelected("UK");
            String content = new String(normalAppearance(cb).getDecodedData(), StandardCharsets.ISO_8859_1);
            assertTrue(content.contains("(UK)"), "appearance should paint the selected value, got: " + content);
            assertTrue(content.contains("Tf"), "appearance should set a font");
        }
    }

    @Test
    @DisplayName("Zero-area combo box silently skips appearance generation (F-10)")
    void zeroAreaRect_skipsAppearance() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            // Degenerate rect must not throw and must not crash regeneration.
            ComboBoxField cb = assertDoesNotThrow(() ->
                    new ComboBoxField(page, new Rectangle(50, 100, 50, 130)));
            COSBase ap = cb.getCOSDictionary().get(COSName.of("AP"));
            if (ap instanceof COSDictionary) {
                assertFalse(((COSDictionary) ap).get(COSName.N) instanceof COSStream,
                        "degenerate rect should not produce an /AP/N stream");
            }
        }
    }
}
