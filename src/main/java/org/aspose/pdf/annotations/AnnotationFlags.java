package org.aspose.pdf.annotations;

import java.util.EnumSet;

/**
 * Annotation flag bits per ISO 32000-1:2008 §12.5.3, Table 165.
 *
 * <p>Each enum constant carries the actual bit value (1 &lt;&lt; (n − 1)) for the
 * spec's 1-indexed bit position, so {@link #getBit()} can be used directly
 * for bitwise composition.</p>
 *
 * <p>Use {@link #fromBits(int)} to decode a raw {@code /F} entry into a typed
 * {@link EnumSet} and {@link #toBits(EnumSet)} to re-encode.</p>
 */
public enum AnnotationFlags {
    /** Do not display or print, regardless of /AP. */
    Invisible      (1 << 0),  // bit 1
    /** Do not display, do not print, do not allow interaction. */
    Hidden         (1 << 1),  // bit 2
    /** Print the annotation if printing is permitted. */
    Print          (1 << 2),  // bit 3
    /** Do not scale the appearance to match page zoom. */
    NoZoom         (1 << 3),  // bit 4
    /** Do not rotate the appearance with the page. */
    NoRotate       (1 << 4),  // bit 5
    /** Do not display, but allow printing. */
    NoView         (1 << 5),  // bit 6
    /** Do not allow the user to interact with the annotation. */
    ReadOnly       (1 << 6),  // bit 7
    /** Do not allow the annotation to be deleted or its properties modified. */
    Locked         (1 << 7),  // bit 8
    /** Invert the NoView flag for the matching mouse event (PDF 1.5+). */
    ToggleNoView   (1 << 8),  // bit 9
    /** Do not allow the annotation contents to be modified (PDF 1.7+). */
    LockedContents (1 << 9);  // bit 10

    private final int bit;

    AnnotationFlags(int bit) {
        this.bit = bit;
    }

    /**
     * Returns the bit value for this flag (already shifted into position).
     *
     * @return the bit mask for this flag
     */
    public int getBit() {
        return bit;
    }

    /**
     * Decodes a raw {@code /F} bitfield into a typed {@link EnumSet}.
     *
     * @param bits the raw flag bits
     * @return a set with every flag whose bit is set in {@code bits}
     */
    public static EnumSet<AnnotationFlags> fromBits(int bits) {
        EnumSet<AnnotationFlags> result = EnumSet.noneOf(AnnotationFlags.class);
        for (AnnotationFlags flag : values()) {
            if ((bits & flag.bit) != 0) result.add(flag);
        }
        return result;
    }

    /**
     * Encodes an {@link EnumSet} of flags into a raw bitfield suitable for
     * {@code /F}.
     *
     * @param flags the set of flags (must not be null)
     * @return the OR of every member's {@link #getBit()}
     */
    public static int toBits(EnumSet<AnnotationFlags> flags) {
        int result = 0;
        for (AnnotationFlags flag : flags) result |= flag.bit;
        return result;
    }
}
