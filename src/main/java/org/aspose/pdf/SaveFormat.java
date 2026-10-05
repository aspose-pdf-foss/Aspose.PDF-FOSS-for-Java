package org.aspose.pdf;

/**
 * Specifies the format for saving a document.
 */
public enum SaveFormat {
    /** PDF format. */
    Pdf,
    /** HTML format. */
    Html,
    /** Office Open XML word-processing format ({@code .docx}). */
    DocX,
    /**
     * Word 97-2003 request. The FOSS writer has no binary {@code .doc}
     * serializer &mdash; accepted for API compatibility; produces an OOXML
     * package (same output as {@link #DocX}).
     */
    Doc,
    /** XML format. */
    Xml,
    /**
     * Office Open XML spreadsheet format ({@code .xlsx}). Tables recognised in
     * the source are exported as worksheets of typed cells (see
     * {@link ExcelSaveOptions}).
     */
    Xlsx,
    /**
     * Alias of {@link #Xlsx} kept for Aspose.PDF API compatibility
     * ({@code SaveFormat.Excel}). The FOSS writer emits an {@code .xlsx}
     * package regardless of the requested spreadsheet flavour.
     */
    Excel,
    /**
     * Markdown format ({@code .md}). The document is projected to the structural
     * model and serialized as CommonMark / GitHub-Flavored Markdown (headings,
     * paragraphs, pipe tables, lists, emphasis, links, images). See
     * {@link MarkdownSaveOptions}.
     */
    Markdown
}
