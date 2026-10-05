package org.aspose.pdf.sdm.markdown;

import java.io.IOException;
import java.nio.file.Path;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.MarkdownSaveOptions;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.html.StructuralHtmlPipeline;

/**
 * PDF &rarr; Markdown pipeline — the Markdown sibling of
 * {@code StructuralHtmlPipeline} / {@code StructuralDocxPipeline}.
 *
 * <p>Reuses the exact PDF&rarr;SDM enrichment half of the structural HTML
 * pipeline ({@link StructuralHtmlPipeline#buildStructuralSdm}) so tagged-tree
 * and geometry-heuristic recognition behave identically for every target, then
 * serializes the enriched model with {@link SdmMarkdownWriter}. Markdown is an
 * editable flow target (like DOCX), so {@code flowTarget=true}.</p>
 */
public final class StructuralMarkdownPipeline {

    private StructuralMarkdownPipeline() {
        // static entry only
    }

    /**
     * Converts an open document to Markdown text, writing external images (when
     * requested) relative to {@code baseDir}.
     *
     * @param doc     the open document; must not be null
     * @param options Markdown save options; null = defaults
     * @param baseDir the directory the {@code .md} will be written into (used to
     *                place external image files); {@code null} forces inline
     *                {@code data:} URIs (stream output)
     * @return the Markdown text
     * @throws IOException if page content cannot be read or images cannot be written
     */
    public static String toMarkdown(Document doc, MarkdownSaveOptions options, Path baseDir)
            throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (options == null) {
            options = new MarkdownSaveOptions();
        }
        SdmDocument sdm = StructuralHtmlPipeline.buildStructuralSdm(doc, toHtmlOptions(options), true);
        boolean embed = options.isEmbedImagesAsDataUri() || baseDir == null;
        return new SdmMarkdownWriter(baseDir, options.getResourcesDirectoryName(), embed).write(sdm);
    }

    /**
     * Converts an open document to Markdown with all images embedded as
     * {@code data:} URIs (no external files).
     *
     * @param doc     the open document; must not be null
     * @param options Markdown save options; null = defaults
     * @return the Markdown text
     * @throws IOException if page content cannot be read
     */
    public static String toMarkdown(Document doc, MarkdownSaveOptions options) throws IOException {
        return toMarkdown(doc, options, null);
    }

    /** Maps the Markdown options onto the shared enrichment flags carried by HtmlSaveOptions. */
    private static HtmlSaveOptions toHtmlOptions(MarkdownSaveOptions options) {
        HtmlSaveOptions h = new HtmlSaveOptions();
        h.setStructuralHeuristics(options.isStructuralHeuristics());
        h.setSuppressRunningHeadersFooters(options.isSuppressRunningHeadersFooters());
        h.setRasterizeVectorGraphics(options.isRasterizeVectorGraphics());
        return h;
    }
}
