package org.aspose.pdf;

/**
 * Specifies the source format a document is loaded (and converted) from.
 * API-compatible with the Aspose.PDF {@code LoadFormat} concept.
 */
public enum LoadFormat {
    /** Native PDF — opened directly, no conversion. */
    PDF,
    /** HTML — converted to PDF on load. */
    HTML,
    /** Office Open XML word-processing ({@code .docx}) — converted to PDF on load. */
    DocX,
    /** Office Open XML spreadsheet ({@code .xlsx}) — converted to PDF on load. */
    Xlsx,
    /** Markdown ({@code .md}) — converted to PDF on load. */
    Markdown
}
