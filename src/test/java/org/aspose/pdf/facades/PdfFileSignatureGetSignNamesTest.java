package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.forms.SignatureField;
import org.aspose.pdf.forms.TextBoxField;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PdfFileSignature#getSignNames()} (Sprint 47).
 * <p>
 * {@code getSignNames()} must return only <em>signed</em> signature fields
 * (those with a {@code /V} value). Blank/unsigned signature fields and
 * non-signature fields must be excluded.
 * </p>
 */
class PdfFileSignatureGetSignNamesTest {

    @Test
    void emptyDocumentReturnsEmptyList() throws IOException {
        try (Document doc = new Document()) {
            doc.getPages().add();
            try (PdfFileSignature sign = new PdfFileSignature(doc)) {
                List<String> names = sign.getSignNames();
                assertNotNull(names);
                assertTrue(names.isEmpty(), "Empty doc should have no signed names");
            }
        }
    }

    @Test
    void unsignedSignatureFieldExcluded() throws IOException {
        // A SignatureField without a /V value is NOT signed and must be excluded.
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            SignatureField sf = new SignatureField(page, new Rectangle(100, 100, 300, 200));
            sf.setPartialName("Sig1");
            doc.getForm().add(sf);

            assertFalse(sf.isSigned(), "freshly created field should be unsigned");
            try (PdfFileSignature sign = new PdfFileSignature(doc)) {
                assertEquals(0, sign.getSignNames().size(),
                        "Unsigned SignatureField must NOT appear in getSignNames");
                // ...but it should be reported as a blank signature field.
                assertTrue(sign.getBlankSignNames().contains("Sig1"),
                        "Unsigned SignatureField should appear in getBlankSignNames");
            }
        }
    }

    @Test
    void nonSignatureFieldsAlwaysExcluded() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(100, 100, 300, 150));
            tb.setPartialName("Text1");
            tb.setValue("hello");
            doc.getForm().add(tb);

            try (PdfFileSignature sign = new PdfFileSignature(doc)) {
                assertEquals(0, sign.getSignNames().size(),
                        "A non-signature field must never appear in getSignNames");
            }
        }
    }
}
