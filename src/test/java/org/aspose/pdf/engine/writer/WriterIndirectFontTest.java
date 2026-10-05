package org.aspose.pdf.engine.writer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.junit.jupiter.api.Test;

/**
 * Guards the writer invariant that dictionaries ISO 32000 requires to be
 * indirect are serialised as indirect objects even when the caller builds them
 * INLINE. Streams are always lifted (§7.3.8); this covers the two dictionary
 * types the writer additionally promotes:
 * <ul>
 *   <li>{@code /FontDescriptor} — §9.8.1 Table 122 "shall be an indirect reference";</li>
 *   <li>font dictionaries ({@code /Type /Font}) — Adobe Acrobat only regenerates
 *       editable field appearances when the {@code /DR} font is indirect, and
 *       inline font dicts trip strict readers.</li>
 * </ul>
 * The library builds such graphs inline in several places (e.g.
 * {@code Type0FontBuilder}); the writer must lift them so the output is valid.
 * Reads no files from disk.
 */
class WriterIndirectFontTest {

    @Test
    void inlineFontAndFontDescriptorBecomeIndirectOnSave() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            // Build a fully INLINE font graph, the way font builders do:
            //   page /Resources /Font /F99 -> { /Type /Font ... /FontDescriptor { ... /FontFile2 <stream> } }
            PdfStream fontFile = new PdfStream();
            fontFile.setDecodedData(new byte[]{0, 1, 2, 3});

            PdfDictionary fd = new PdfDictionary();
            fd.set(PdfName.of("Type"), PdfName.of("FontDescriptor"));
            fd.set(PdfName.of("FontName"), PdfName.of("TestFont"));
            fd.set(PdfName.of("Flags"), PdfInteger.valueOf(4));
            fd.set(PdfName.of("FontFile2"), fontFile); // inline stream

            PdfDictionary font = new PdfDictionary();
            font.set(PdfName.of("Type"), PdfName.of("Font"));
            font.set(PdfName.of("Subtype"), PdfName.of("Type1"));
            font.set(PdfName.of("BaseFont"), PdfName.of("TestFont"));
            font.set(PdfName.of("FontDescriptor"), fd); // inline descriptor

            PdfDictionary pageDict = page.getPdfDictionary();
            PdfDictionary resources = getOrCreate(pageDict, "Resources");
            PdfDictionary fonts = getOrCreate(resources, "Font");
            fonts.set(PdfName.of("F99"), font); // inline font dict

            doc.save(bos);
        }

        try (Document re = new Document(new ByteArrayInputStream(bos.toByteArray()))) {
            PdfDictionary pageDict = re.getPages().get(1).getPdfDictionary();
            PdfDictionary res = (PdfDictionary) deref(pageDict.get("Resources"));
            PdfDictionary fonts = (PdfDictionary) deref(res.get("Font"));

            // The font dictionary must be referenced indirectly.
            assertTrue(fonts.get("F99") instanceof PdfObjectReference,
                    "/Resources /Font /F99 must serialise as an INDIRECT reference");
            PdfDictionary font = (PdfDictionary) deref(fonts.get("F99"));

            // The /FontDescriptor must be indirect (ISO 32000 §9.8.1 Table 122).
            assertTrue(font.get("FontDescriptor") instanceof PdfObjectReference,
                    "/FontDescriptor must serialise as an INDIRECT reference");
            PdfDictionary fd = (PdfDictionary) deref(font.get("FontDescriptor"));

            // The embedded font file is a stream and must also be indirect (§7.3.8).
            assertTrue(fd.get("FontFile2") instanceof PdfObjectReference,
                    "/FontFile2 stream must be an INDIRECT object");
            assertTrue(deref(fd.get("FontFile2")) instanceof PdfStream);
        }
    }

    private static PdfDictionary getOrCreate(PdfDictionary parent, String key) {
        PdfBase v = deref(parent.get(key));
        if (v instanceof PdfDictionary) {
            return (PdfDictionary) v;
        }
        PdfDictionary d = new PdfDictionary();
        parent.set(PdfName.of(key), d);
        return d;
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
