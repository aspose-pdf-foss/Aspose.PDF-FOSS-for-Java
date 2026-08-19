package org.aspose.pdf.engine.pdfa.fixes;

import org.aspose.pdf.ConvertErrorAction;
import org.aspose.pdf.PdfFormat;
import org.aspose.pdf.engine.pdfa.PdfAValidationResult;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfBoolean;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfString;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Logical-structure fixes for PDF/A Level A conversion
 * (ISO 19005-1:2005 §6.8 / ISO 19005-2:2011 §6.7).
 * <p>
 * Level A requires the document to be a Tagged PDF: the catalog must carry
 * {@code /MarkInfo <</Marked true>>}, a {@code /StructTreeRoot} and a natural
 * language declaration ({@code /Lang}). A converted document that lacks them
 * gets minimal conformant entries added. Full semantic auto-tagging of page
 * content is out of scope — an empty structure tree is spec-legal (the /K key
 * of the structure tree root is optional, ISO 32000-1 Table 322) and matches
 * what a non-authored scan-through conversion can honestly claim.
 * </p>
 */
public final class StructureFixes {

    private static final Logger LOG = Logger.getLogger(StructureFixes.class.getName());

    /**
     * Creates a new StructureFixes instance.
     */
    public StructureFixes() {
        // default
    }

    /**
     * Ensures the catalog satisfies the Level A tagged-PDF requirements:
     * {@code /MarkInfo/Marked = true}, {@code /StructTreeRoot} present and
     * {@code /Lang} present. No-op for level B/U targets.
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if the catalog cannot be loaded
     */
    public void ensureLevelAStructure(PDFParser parser, PdfFormat format,
                                      ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        if (!format.isLevelA()) {
            return;
        }
        PdfDictionary catalog = parser.getCatalog();

        // 6.8.2 — /MarkInfo with /Marked true
        PdfBase markInfoRef = catalog.get("MarkInfo");
        PdfDictionary markInfo = null;
        if (markInfoRef != null) {
            PdfBase resolved = parser.resolveReference(markInfoRef);
            if (resolved instanceof PdfDictionary) {
                markInfo = (PdfDictionary) resolved;
            }
        }
        if (markInfo == null) {
            markInfo = new PdfDictionary();
            catalog.set("MarkInfo", markInfo);
        }
        if (!markInfo.getBoolean("Marked", false)) {
            markInfo.set("Marked", PdfBoolean.valueOf(true));
            result.addWarning("struct.1", "Added /MarkInfo <</Marked true>> to catalog",
                    "catalog/MarkInfo", "ISO 19005-1:2005, 6.8.2");
        }

        // 6.8.3 — /StructTreeRoot (a rootless-kids tree is legal; /K is optional)
        if (catalog.get("StructTreeRoot") == null) {
            PdfDictionary structRoot = new PdfDictionary();
            structRoot.set("Type", PdfName.of("StructTreeRoot"));
            int maxObj = 0;
            for (org.aspose.pdf.engine.pdfobjects.PdfObjectKey k : parser.getAllObjectKeys()) {
                maxObj = Math.max(maxObj, k.getObjectNumber());
            }
            PdfObjectReference rootRef = new PdfObjectReference(
                    new org.aspose.pdf.engine.pdfobjects.PdfObjectKey(maxObj + 1, 0), k -> structRoot);
            catalog.set("StructTreeRoot", rootRef);
            result.addWarning("struct.2", "Added minimal /StructTreeRoot to catalog",
                    "catalog/StructTreeRoot", "ISO 19005-1:2005, 6.8.3");
        }

        // 6.8.4 — /Lang
        if (catalog.get("Lang") == null) {
            catalog.set("Lang", new PdfString("en-US"));
            result.addWarning("struct.3", "Added /Lang to catalog",
                    "catalog/Lang", "ISO 19005-1:2005, 6.8.4");
        }
        LOG.fine("Level A structure requirements ensured");
    }
}
