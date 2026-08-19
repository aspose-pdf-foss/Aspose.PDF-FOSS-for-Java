package org.aspose.pdf.pgm;

/**
 * How a {@link PgmBox} participates in reflow operations (IR spec §2.1,
 * decision #13).
 */
public enum FlowClass {
    /** Moves with the content flow (ordinary body content). */
    FLOW,
    /** Moves together with its anchor target box (caption, text markup). */
    ANCHORED,
    /** Never moves (chrome: headers/footers, page numbers, watermarks). */
    FIXED,
    /** Moves only as a whole; reflow must not enter it (complex vector, Opaque). */
    ATOMIC
}
