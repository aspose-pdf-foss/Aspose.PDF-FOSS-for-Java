package org.aspose.pdf.engine.pdfa;

import org.aspose.pdf.ConvertErrorAction;
import org.aspose.pdf.PdfFormat;
import org.aspose.pdf.PdfFormatConversionOptions;
import org.aspose.pdf.engine.pdfa.fixes.ActionFixes;
import org.aspose.pdf.engine.pdfa.fixes.AnnotationFixes;
import org.aspose.pdf.engine.pdfa.fixes.FileStructureFixes;
import org.aspose.pdf.engine.pdfa.fixes.FontFixes;
import org.aspose.pdf.engine.pdfa.fixes.FormFixes;
import org.aspose.pdf.engine.pdfa.fixes.GraphicsFixes;
import org.aspose.pdf.engine.pdfa.fixes.MetadataFixes;
import org.aspose.pdf.engine.pdfa.fixes.PdfXFixes;
import org.aspose.pdf.engine.pdfa.fixes.StructureFixes;
import org.aspose.pdf.engine.pdfa.fixes.TransparencyFixes;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orchestrator for PDF/A and PDF/X conversion.
 * <p>
 * Applies a series of fix passes to the object-level objects of a parsed PDF document
 * so that it becomes compliant with the requested profile (PDF/A-1a, PDF/A-2b, PDF/X-1a, etc.).
 * After all fixes have been applied the document is re-validated and a
 * {@link PdfAValidationResult} is returned.
 * </p>
 *
 * <p>Fix order (metadata first, then structure, then content):</p>
 * <ol>
 *   <li>Metadata fixes (XMP, DocInfo synchronisation)</li>
 *   <li>File-structure fixes (encryption, LZW, external refs, trailer ID)</li>
 *   <li>Graphics / output-intent fixes</li>
 *   <li>Font fixes</li>
 *   <li>Transparency fixes (PDF/A-1 only)</li>
 *   <li>Annotation fixes</li>
 *   <li>Action fixes</li>
 *   <li>Form fixes</li>
 *   <li>PDF/X fixes (when target is PDF/X)</li>
 * </ol>
 */
public final class PdfAConverter {

    private static final Logger LOG = Logger.getLogger(PdfAConverter.class.getName());

    /**
     * Creates a new converter instance.
     */
    public PdfAConverter() {
        // default
    }

    /**
     * Converts the parsed PDF so that it conforms to the standard described by
     * {@code options.getFormat()}.
     * <p>
     * Each fix class is invoked in the correct order.  After all fixes have been
     * applied the document is re-validated and a {@link PdfAValidationResult} is
     * returned.  The caller should inspect
     * {@link PdfAValidationResult#isCompliant()} to determine whether the
     * conversion was fully successful.
     * </p>
     *
     * @param parser  the parsed PDF document (must already have been parsed)
     * @param options conversion options (target format, error action, etc.)
     * @return the validation result after conversion
     * @throws IOException              if an I/O error occurs while accessing PDF objects
     * @throws IllegalArgumentException if parser or options is {@code null}
     */
    public PdfAValidationResult convert(PDFParser parser, PdfFormatConversionOptions options) throws IOException {
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }

        PdfFormat format = options.getFormat();
        ConvertErrorAction errorAction = options.getErrorAction();
        PdfAValidationResult result = new PdfAValidationResult(format);

        LOG.info(() -> "Starting PDF/A conversion to " + format.name());

        // 1. Metadata fixes (must run first so XMP is in place for subsequent steps)
        applyFix("MetadataFixes", () -> {
            MetadataFixes mf = new MetadataFixes();
            mf.ensureXmpMetadata(parser, format, errorAction, result);
            mf.removeMetadataFilter(parser, format, errorAction, result);
            mf.ensurePdfAId(parser, format, errorAction, result);
            mf.syncDocInfoWithXmp(parser, format, errorAction, result);
        });

        // 2. File-structure fixes
        applyFix("FileStructureFixes", () -> {
            FileStructureFixes fs = new FileStructureFixes();
            fs.removeEncryption(parser, format, errorAction, result);
            fs.replaceLzwWithFlate(parser, format, errorAction, result);
            fs.removeExternalStreamRefs(parser, format, errorAction, result);
            if (format.isPdfA1()) {
                fs.removeEmbeddedFiles(parser, format, errorAction, result);
                fs.removeOCProperties(parser, format, errorAction, result);
            }
            fs.ensureTrailerId(parser, format, errorAction, result);
        });

        // 3. Graphics / output-intent fixes
        applyFix("GraphicsFixes", () -> {
            GraphicsFixes gf = new GraphicsFixes();
            gf.addOutputIntent(parser, format, errorAction, result);
            gf.removeImageAlternates(parser, format, errorAction, result);
            gf.removeOPI(parser, format, errorAction, result);
            gf.fixInterpolate(parser, format, errorAction, result);
        });

        // 4. Font fixes (embedding first so the ToUnicode pass sees the WinAnsi
        // encoding the embed pass establishes for retyped fonts)
        applyFix("FontFixes", () -> {
            FontFixes ff = new FontFixes();
            ff.embedUnembeddedFonts(parser, format, errorAction, result);
            ff.fixCidToGidMap(parser, format, errorAction, result);
            ff.generateToUnicodeCMap(parser, format, errorAction, result);
            ff.generateCharSet(parser, format, errorAction, result);
            ff.generateCIDSet(parser, format, errorAction, result);
            ff.logUnembeddedFonts(parser, format, errorAction, result);
        });

        // 4b. Level A logical-structure requirements (MarkInfo/StructTreeRoot/Lang)
        applyFix("StructureFixes", () -> {
            StructureFixes sf = new StructureFixes();
            sf.ensureLevelAStructure(parser, format, errorAction, result);
        });

        // 5. Transparency fixes (PDF/A-1 forbids transparency; PDF/A-2+ allows it)
        if (format.isPdfA1()) {
            applyFix("TransparencyFixes", () -> {
                TransparencyFixes tf = new TransparencyFixes();
                tf.fixSmask(parser, format, errorAction, result);
                tf.fixBlendMode(parser, format, errorAction, result);
                tf.fixStrokingAlpha(parser, format, errorAction, result);
                tf.fixNonStrokingAlpha(parser, format, errorAction, result);
                tf.removeTransparencyGroups(parser, format, errorAction, result);
            });
        }

        // 6. Annotation fixes
        applyFix("AnnotationFixes", () -> {
            AnnotationFixes af = new AnnotationFixes();
            af.removeForbiddenAnnotations(parser, format, errorAction, result);
            af.fixAnnotationFlags(parser, format, errorAction, result);
        });

        // 7. Action fixes
        applyFix("ActionFixes", () -> {
            ActionFixes af = new ActionFixes();
            af.removeCatalogAA(parser, format, errorAction, result);
            af.removePageAA(parser, format, errorAction, result);
            af.removeWidgetAA(parser, format, errorAction, result);
            af.removeForbiddenActions(parser, format, errorAction, result);
        });

        // 8. Form fixes
        applyFix("FormFixes", () -> {
            FormFixes ff = new FormFixes();
            ff.fixNeedAppearances(parser, format, errorAction, result);
            ff.removeFieldAA(parser, format, errorAction, result);
            if (format.isPdfA2OrLater()) {
                ff.removeXFA(parser, format, errorAction, result);
            }
        });

        // 9. PDF/X fixes
        if (format.isPdfX()) {
            applyFix("PdfXFixes", () -> {
                PdfXFixes pxf = new PdfXFixes();
                pxf.addPdfXVersion(parser, format, errorAction, result);
                pxf.addPdfXConformance(parser, format, errorAction, result);
                pxf.addTrapped(parser, format, errorAction, result);
                pxf.addTrimBox(parser, format, errorAction, result);
                pxf.addOutputIntentPdfX(parser, format, errorAction, result);
                if (format.isPdfX1a()) {
                    pxf.removeEncryption(parser, format, errorAction, result);
                }
            });
        }

        LOG.info(() -> "PDF/A conversion completed. Compliant=" + result.isCompliant()
                + ", violations=" + result.getViolations().size());

        return result;
    }

    /**
     * Applies a named fix, catching and logging any exception.
     */
    private void applyFix(String name, FixAction action) throws IOException {
        try {
            LOG.fine(() -> "Applying " + name);
            action.run();
            LOG.fine(() -> name + " completed");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error in " + name + ": " + e.getMessage(), e);
            throw e;
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Unexpected error in " + name + ": " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Functional interface for a fix action that may throw IOException.
     */
    @FunctionalInterface
    private interface FixAction {
        /**
         * Runs the fix.
         *
         * @throws IOException if an I/O error occurs
         */
        void run() throws IOException;
    }
}
