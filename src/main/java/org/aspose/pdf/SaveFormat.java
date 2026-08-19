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
    Xml
}
