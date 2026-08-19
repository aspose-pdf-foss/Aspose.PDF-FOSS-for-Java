package org.aspose.pdf.pgm;

/**
 * Kind of drawn object a {@link PgmBox} represents (IR spec §2.1) — the VIEW
 * of the object, never its semantics.
 */
public enum PgmBoxKind {
    /** Text show operations. */
    TEXT,
    /** Raster image (Image XObject or inline image). */
    IMAGE,
    /** Vector path (line/rect/path). */
    VECTOR,
    /** Annotation (non-widget). */
    ANNOTATION,
    /** Form field widget. */
    FIELD,
    /** Page chrome (reserved; chrome is normally flagged via flowClass). */
    CHROME,
    /** Box whose semantics are not projected into SDM. */
    UNKNOWN
}
