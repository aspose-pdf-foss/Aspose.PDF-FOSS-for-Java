package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.SignatureField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug C — {@code SignatureField} now exposes a public
 * {@code (Page, Rectangle)} constructor so callers can place a signature
 * placeholder on a fresh document without going through the COSDictionary
 * back door.
 */
class SignatureFieldConstructorTest {

    @TempDir Path tempDir;

    @Test
    @DisplayName("(Page, Rectangle) ctor creates an unsigned signature field")
    void pageRectangleConstructor_createsUnsignedField() throws IOException {
        Path out = tempDir.resolve("sig.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            SignatureField sig = new SignatureField(page, new Rectangle(10, 10, 200, 50));
            sig.setPartialName("signature1");
            doc.getForm().add(sig);
            assertFalse(sig.isSigned(), "freshly-built field has no /V → not signed");
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            SignatureField sig = (SignatureField) r.getForm().get("signature1");
            assertNotNull(sig, "field must round-trip through save/reopen");
            assertFalse(sig.isSigned());
            Rectangle rect = sig.getRect();
            assertEquals(10, rect.getLLX(), 0.001);
            assertEquals(10, rect.getLLY(), 0.001);
            assertEquals(200, rect.getURX(), 0.001);
            assertEquals(50, rect.getURY(), 0.001);
        }
    }

    @Test
    @DisplayName("Widget annotation appears in page /Annots after save+reopen")
    void pageRectangleConstructor_attachesWidgetToPage() throws IOException {
        Path out = tempDir.resolve("sig-annot.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            SignatureField sig = new SignatureField(page, new Rectangle(20, 20, 220, 60));
            sig.setPartialName("sig2");
            doc.getForm().add(sig);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            Page page = r.getPages().get(1);
            boolean found = false;
            for (Annotation ann : page.getAnnotations()) {
                if ("Widget".equals(ann.getSubtype())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "widget subtype annotation must be present on page");
        }
    }

    @Test
    @DisplayName("AcroForm /Fields contains the new signature field")
    void pageRectangleConstructor_acroFormRegistersField() throws IOException {
        Path out = tempDir.resolve("sig-acro.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            SignatureField sig = new SignatureField(page, new Rectangle(0, 0, 100, 50));
            sig.setPartialName("sig3");
            doc.getForm().add(sig);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            boolean found = false;
            for (Field f : r.getForm()) {
                if ("sig3".equals(f.getPartialName())) {
                    assertTrue(f instanceof SignatureField);
                    found = true;
                    break;
                }
            }
            assertTrue(found, "AcroForm /Fields must reference the signature field");
        }
    }

    @Test
    @DisplayName("Null page throws IllegalArgumentException")
    void pageRectangleConstructor_nullPage_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignatureField(null, new Rectangle(0, 0, 100, 50)));
    }

    @Test
    @DisplayName("Null rect throws IllegalArgumentException")
    void pageRectangleConstructor_nullRect_throwsIllegalArgument() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            assertThrows(IllegalArgumentException.class,
                    () -> new SignatureField(page, null));
        }
    }
}
