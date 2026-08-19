package org.aspose.pdf.sdm.docx;

import java.io.IOException;
import java.io.OutputStream;

import org.aspose.pdf.DocSaveOptions;
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.html.StructuralHtmlPipeline;

/**
 * PDF &rarr; {@code .docx} pipeline — the DOCX sibling of
 * {@link StructuralHtmlPipeline}.
 *
 * <p>Reuses the exact PDF&rarr;SDM enrichment half of the structural HTML
 * pipeline ({@link StructuralHtmlPipeline#buildStructuralSdm}) so tagged-tree
 * and geometry-heuristic recognition behave identically for both targets, then
 * serializes the enriched model with {@link SdmDocxWriter}.</p>
 */
public final class StructuralDocxPipeline {

    private StructuralDocxPipeline() {
        // static entry only
    }

    /**
     * Converts an open document to a {@code .docx} package written to the stream.
     *
     * @param doc     the open document; must not be null
     * @param options DOCX save options; null = defaults
     * @param out     the destination stream (not closed)
     * @throws IOException if page content cannot be read or the package cannot be written
     */
    public static void toDocx(Document doc, DocSaveOptions options, OutputStream out) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (options == null) {
            options = new DocSaveOptions();
        }
        SdmDocument sdm = StructuralHtmlPipeline.buildStructuralSdm(doc, toHtmlOptions(options), true);
        new SdmDocxWriter().write(sdm, out);
    }

    /**
     * Converts an open document to a {@code .docx} package returned as bytes.
     *
     * @param doc     the open document; must not be null
     * @param options DOCX save options; null = defaults
     * @return the {@code .docx} bytes
     * @throws IOException if conversion fails
     */
    public static byte[] toDocx(Document doc, DocSaveOptions options) throws IOException {
        if (options == null) {
            options = new DocSaveOptions();
        }
        SdmDocument sdm = StructuralHtmlPipeline.buildStructuralSdm(doc, toHtmlOptions(options), true);
        return new SdmDocxWriter().write(sdm);
    }

    /** Maps the DOCX options onto the shared enrichment flags carried by HtmlSaveOptions. */
    private static HtmlSaveOptions toHtmlOptions(DocSaveOptions options) {
        HtmlSaveOptions h = new HtmlSaveOptions();
        h.setStructuralHeuristics(options.isStructuralHeuristics());
        h.setSuppressRunningHeadersFooters(options.isSuppressRunningHeadersFooters());
        h.setRasterizeVectorGraphics(options.isRasterizeVectorGraphics());
        return h;
    }
}
