package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.forms.ComboBoxField;
import org.aspose.pdf.forms.Field;
import org.aspose.pdf.forms.ListBoxField;
import org.aspose.pdf.forms.TextBoxField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug D — variable-text form fields must carry a {@code /DA} string with at
 * least a {@code Tf} operator, and the AcroForm root must carry a {@code /DR}
 * resource dictionary providing the font(s) referenced by {@code /DA}.
 */
class AcroFormDefaultAppearanceTest {

    @TempDir Path tempDir;

    private static COSDictionary resolveDict(COSBase v) throws IOException {
        if (v instanceof COSObjectReference) v = ((COSObjectReference) v).dereference();
        return v instanceof COSDictionary ? (COSDictionary) v : null;
    }

    @Test
    @DisplayName("Fresh TextBoxField has a non-null /DA out of the box")
    void freshTextBox_hasDefaultAppearance() throws IOException {
        Path out = tempDir.resolve("tb.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(50, 50, 250, 70));
            tb.setPartialName("tb");
            doc.getForm().add(tb);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            TextBoxField tb = (TextBoxField) r.getForm().get("tb");
            String da = tb.getDefaultAppearance();
            assertNotNull(da, "/DA must be present");
            assertTrue(da.contains("Tf"), "/DA must contain a Tf operator (got: " + da + ")");
            assertTrue(da.contains("/Helv"), "/DA must reference /Helv font name (got: " + da + ")");
        }
    }

    @Test
    @DisplayName("setDefaultAppearance round-trips through save+reopen")
    void setDefaultAppearance_roundTrips() throws IOException {
        Path out = tempDir.resolve("da-round.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            tb.setPartialName("tb");
            tb.setDefaultAppearance("/Helv 14 Tf 0.2 0.2 0.8 rg");
            doc.getForm().add(tb);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            TextBoxField tb = (TextBoxField) r.getForm().get("tb");
            assertEquals("/Helv 14 Tf 0.2 0.2 0.8 rg", tb.getDefaultAppearance());
        }
    }

    @Test
    @DisplayName("AcroForm /DR /Font /Helv is created when first text field is added")
    void formWithOneTextBox_acroFormDRFontHelvIsPresent() throws IOException {
        Path out = tempDir.resolve("dr-helv.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            tb.setPartialName("tb");
            doc.getForm().add(tb);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary acroForm = r.getForm().getCOSDictionary();
            COSDictionary dr = resolveDict(acroForm.get("DR"));
            assertNotNull(dr, "/DR must be present on AcroForm root");
            COSDictionary fonts = resolveDict(dr.get("Font"));
            assertNotNull(fonts, "/DR /Font must be present");
            COSDictionary helv = resolveDict(fonts.get("Helv"));
            assertNotNull(helv, "/DR /Font /Helv must be present");
            assertEquals("Helvetica", helv.getNameAsString("BaseFont"));
            assertEquals("WinAnsiEncoding", helv.getNameAsString("Encoding"));
        }
    }

    @Test
    @DisplayName("AcroForm /DR /Font /ZaDb is created (ZapfDingbats, no /Encoding)")
    void formWithOneTextBox_acroFormDRFontZaDbIsPresent() throws IOException {
        Path out = tempDir.resolve("dr-zadb.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            tb.setPartialName("tb");
            doc.getForm().add(tb);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary acroForm = r.getForm().getCOSDictionary();
            COSDictionary dr = resolveDict(acroForm.get("DR"));
            COSDictionary fonts = resolveDict(dr.get("Font"));
            COSDictionary zadb = resolveDict(fonts.get("ZaDb"));
            assertNotNull(zadb, "/DR /Font /ZaDb must be present");
            assertEquals("ZapfDingbats", zadb.getNameAsString("BaseFont"));
        }
    }

    @Test
    @DisplayName("setDefaultAppearance(null) clears the /DA entry")
    void setDefaultAppearanceNull_clearsEntry() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField tb = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            tb.setDefaultAppearance("/Helv 12 Tf 0 g");
            assertNotNull(tb.getDefaultAppearance());
            tb.setDefaultAppearance(null);
            assertNull(tb.getDefaultAppearance());
        }
    }

    @Test
    @DisplayName("ComboBoxField has a non-null /DA out of the box")
    void comboBoxField_hasDefaultAppearance() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            ComboBoxField cb = new ComboBoxField(page, new Rectangle(0, 0, 100, 20));
            assertNotNull(cb.getDefaultAppearance());
            assertTrue(cb.getDefaultAppearance().contains("Tf"));
        }
    }

    @Test
    @DisplayName("ListBoxField has a non-null /DA out of the box")
    void listBoxField_hasDefaultAppearance() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            ListBoxField lb = new ListBoxField(page, new Rectangle(0, 0, 100, 20));
            assertNotNull(lb.getDefaultAppearance());
            assertTrue(lb.getDefaultAppearance().contains("Tf"));
        }
    }

    @Test
    @DisplayName("ensureDefaultResources is idempotent (second field doesn't duplicate)")
    void formWithTwoFields_drPopulatedOnceOnly() throws IOException {
        Path out = tempDir.resolve("dr-twice.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextBoxField a = new TextBoxField(page, new Rectangle(0, 0, 100, 20));
            a.setPartialName("a");
            doc.getForm().add(a);
            TextBoxField b = new TextBoxField(page, new Rectangle(0, 30, 100, 50));
            b.setPartialName("b");
            doc.getForm().add(b);
            doc.save(out.toString());
        }
        try (Document r = new Document(out.toString())) {
            COSDictionary fonts = resolveDict(resolveDict(
                    r.getForm().getCOSDictionary().get("DR")).get("Font"));
            // Only the two standard entries should exist.
            assertEquals(2, fonts.keySet().size(),
                    "DR/Font must contain exactly /Helv and /ZaDb");
        }
    }
}
