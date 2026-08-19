package org.aspose.pdf;

/**
 * Output mode for PDF &rarr; HTML conversion ({@link Document#save(String, HtmlSaveOptions)}).
 *
 * <p>IR Stage 3: one public entry serves both conversion philosophies —
 * a semantic reflowable document versus a pixel-faithful visual copy.</p>
 */
public enum HtmlOutputMode {

    /**
     * Structural (semantic) HTML: headings, paragraphs, lists, tables as
     * {@code h1..h6/p/ul/ol/table} produced from the Semantic Document Model.
     * Tagged PDFs use the author's structure tree; untagged PDFs use
     * geometry heuristics with honest degradation (unsure &rarr; paragraph).
     */
    STRUCTURAL,

    /**
     * Fixed-layout HTML: a visual copy with absolutely positioned spans
     * (the classic {@code PdfToHtmlConverter} behaviour).
     */
    FIXED_LAYOUT
}
