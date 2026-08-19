package org.aspose.pdf.sdm;

/**
 * Discriminator for {@link SdmNode} subtypes (IR spec §1.3–§1.4).
 */
public enum SdmNodeType {
    /** Document root. */
    DOCUMENT,
    // Block nodes
    /** Heading block (level 1..6). */
    HEADING,
    /** Paragraph block. */
    PARAGRAPH,
    /** Ordered or unordered list. */
    LIST_BLOCK,
    /** One list item. */
    LIST_ITEM,
    /** Table block. */
    TABLE,
    /** Table row. */
    TABLE_ROW,
    /** Table cell. */
    TABLE_CELL,
    /** Figure (image with optional caption). */
    FIGURE,
    /** Block quotation. */
    QUOTE,
    /** Preformatted code block. */
    CODE_BLOCK,
    /** Horizontal rule / thematic break. */
    THEMATIC_BREAK,
    /** Table of contents. */
    TOC_BLOCK,
    /** Footnote body. */
    FOOTNOTE,
    /** Un-understood content preserved as-is via sourceRef. */
    OPAQUE,
    /** Grouping container (Div/Sect). */
    CONTAINER,
    /** Interactive form field (AcroForm widget projection). */
    FORM_FIELD,
    // Inline nodes
    /** Styled text run. */
    RUN,
    /** Hyperlink span. */
    LINK_INLINE,
    /** Inline image. */
    INLINE_IMAGE,
    /** Hard line break. */
    LINE_BREAK,
    /** Footnote reference marker. */
    FOOTNOTE_REF,
    /** Un-understood inline content. */
    INLINE_OPAQUE
}
