package org.aspose.pdf.sdm.xlsx;

import java.io.IOException;
import java.io.OutputStream;

import org.aspose.pdf.Document;
import org.aspose.pdf.ExcelSaveOptions;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.html.StructuralHtmlPipeline;

/**
 * PDF &rarr; {@code .xlsx} pipeline &mdash; the spreadsheet sibling of
 * {@link org.aspose.pdf.sdm.docx.StructuralDocxPipeline}.
 *
 * <p>Reuses the exact PDF&rarr;SDM enrichment half of the structural HTML
 * pipeline ({@link StructuralHtmlPipeline#buildStructuralSdm}) &mdash; so
 * tagged-tree and geometry-heuristic table recognition behave identically for the
 * spreadsheet target &mdash; then serializes the recognised tables with
 * {@link SdmXlsxWriter}.</p>
 *
 * <p>The SDM is built with {@code flowTarget=false}: a spreadsheet cares only
 * about the recognised {@link org.aspose.pdf.sdm.Table} blocks, so the
 * flow-target presentation passes (fixed-layout/poster page rasterization,
 * running-header suppression tweaks, horizontal-rule recovery) are skipped. In
 * particular the fixed-layout pass would reclassify a page that is mostly a
 * single narrow-column table as a "poster" and flatten the grid to positioned
 * text &mdash; exactly what a spreadsheet must not lose.</p>
 */
public final class StructuralXlsxPipeline {

    private StructuralXlsxPipeline() {
        // static entry only
    }

    /**
     * Converts an open document to an {@code .xlsx} package written to the stream.
     *
     * @param doc     the open document; must not be null
     * @param options Excel save options; null = defaults
     * @param out     the destination stream (not closed)
     * @throws IOException if page content cannot be read or the package cannot be written
     */
    public static void toXlsx(Document doc, ExcelSaveOptions options, OutputStream out) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (options == null) {
            options = new ExcelSaveOptions();
        }
        SdmDocument sdm = StructuralHtmlPipeline.buildStructuralSdm(doc, toHtmlOptions(options));
        new SdmXlsxWriter(options).write(sdm, out);
    }

    /**
     * Converts an open document to an {@code .xlsx} package returned as bytes.
     *
     * @param doc     the open document; must not be null
     * @param options Excel save options; null = defaults
     * @return the {@code .xlsx} bytes
     * @throws IOException if conversion fails
     */
    public static byte[] toXlsx(Document doc, ExcelSaveOptions options) throws IOException {
        if (options == null) {
            options = new ExcelSaveOptions();
        }
        SdmDocument sdm = StructuralHtmlPipeline.buildStructuralSdm(doc, toHtmlOptions(options));
        return new SdmXlsxWriter(options).write(sdm);
    }

    /** Maps the Excel options onto the shared enrichment flags carried by HtmlSaveOptions. */
    private static HtmlSaveOptions toHtmlOptions(ExcelSaveOptions options) {
        HtmlSaveOptions h = new HtmlSaveOptions();
        h.setStructuralHeuristics(options.isStructuralHeuristics());
        h.setSuppressRunningHeadersFooters(options.isSuppressRunningHeadersFooters());
        // Tables are text; no need to rasterize vector graphics for a spreadsheet.
        h.setRasterizeVectorGraphics(false);
        return h;
    }
}
