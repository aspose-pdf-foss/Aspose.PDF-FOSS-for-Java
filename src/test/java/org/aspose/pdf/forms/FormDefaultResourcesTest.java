package org.aspose.pdf.forms;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the "editable text field shows blank until focus in Adobe
 * Acrobat" bug (found via the live XLSX&rarr;PDF feature, 2026-09-10).
 *
 * <p><b>What went wrong.</b> Adobe Acrobat/Reader REGENERATES the appearance of
 * EDITABLE (non-read-only) variable-text fields when the document opens: it takes
 * the field value {@code /V}, the font selector in {@code /DA} (e.g.
 * {@code /Helv 0 Tf 0 g}) and resolves that font against the AcroForm default
 * resources {@code /AcroForm /DR /Font}. Crucially, Acrobat only accepts the
 * default font when it is stored as an <b>indirect object</b>. When the library
 * built the {@code /DR} from scratch it emitted the {@code /Helv} font as an
 * <b>inline</b> dictionary, so Acrobat's regeneration failed silently and every
 * editable field rendered blank until it first gained focus.</p>
 *
 * <p><b>Why it hid for so long.</b> Read-only fields are NOT regenerated (Acrobat
 * trusts the baked {@code /AP}), so they always showed — the bug looked like
 * "only read-only cells are visible". Our own {@code PngDevice} and the Windows
 * PDF engine both render the baked {@code /AP} leniently and therefore MASK the
 * problem; only real Acrobat exposes it. It also only bites forms BUILT FROM
 * SCRATCH (a loaded PDF keeps its original, already-indirect {@code /DR}).</p>
 *
 * <p>Because no image or Acrobat check can run in CI, this guards the exact
 * structural invariant instead: a from-scratch form's default {@code /DR} font
 * must be an indirect reference. In-memory only; reads no files from disk.</p>
 *
 * @see Form#ensureDefaultResources
 */
class FormDefaultResourcesTest {

    @Test
    void defaultDrFontIsIndirectSoAcrobatCanRegenerateEditableFields() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Form form = doc.getForm();
            TextBoxField tb = new TextBoxField(page, new Rectangle(100, 700, 300, 720));
            tb.setPartialName("amount");
            tb.setValue("1234"); // editable: NO read-only flag — the regenerated case
            form.add(tb);        // triggers Form.ensureDefaultResources

            // Assert the DIRECT output of ensureDefaultResources. NB: checking a
            // saved+reopened document does NOT work — the writer promotes any
            // reachable font dict to an indirect object on serialisation, so a
            // regressed (inline) /DR would still read back as indirect and the
            // guard would be vacuous. The bug lives in what ensureStandardFont
            // PUTS INTO the live /DR, so inspect that in memory.
            PdfDictionary acro = form.getPdfDictionary();
            PdfBase dr = deref(acro.get("DR"));
            assertTrue(dr instanceof PdfDictionary, "AcroForm must carry a /DR");
            PdfBase fontsBase = deref(((PdfDictionary) dr).get("Font"));
            assertTrue(fontsBase instanceof PdfDictionary, "/DR must carry a /Font dictionary");
            PdfDictionary fonts = (PdfDictionary) fontsBase;

            // THE invariant: the default variable-text font must be an INDIRECT
            // object. An inline dict here => Acrobat cannot regenerate editable
            // field appearances => fields blank until focus. Do NOT "simplify"
            // Form.ensureDefaultResources back to an inline /DR font.
            assertTrue(fonts.get("Helv") instanceof PdfObjectReference,
                    "/DR /Font /Helv must be an INDIRECT reference so Acrobat can "
                            + "regenerate editable field appearances (inline dict => blank until focus)");
            assertTrue(fonts.get("ZaDb") instanceof PdfObjectReference,
                    "/DR /Font /ZaDb must also be an indirect reference");

            // Sanity: the editable field round-trips through save with its value.
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            try (Document re = new Document(new ByteArrayInputStream(bos.toByteArray()))) {
                Field amount = re.getForm().get("amount");
                assertNotNull(amount, "field must round-trip");
                assertEquals("1234", amount.getValue());
                assertFalse(amount.isReadOnly(), "field must stay editable (the failing case)");
            }
        }
    }

    private static PdfBase deref(PdfBase b) {
        if (b instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) b).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return b;
    }
}
