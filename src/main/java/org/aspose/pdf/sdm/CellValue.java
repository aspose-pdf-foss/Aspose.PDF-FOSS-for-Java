package org.aspose.pdf.sdm;

/**
 * Typed value of a table cell (IR spec §1.7) — the XLSX perspective: recognised
 * from text at PDF read time ("1 234,56" → NUMBER), serialised as a typed cell.
 * Formulas are not recoverable from PDF — values only.
 */
public final class CellValue {

    /** Recognised value kind. */
    public enum Kind {
        /** Numeric value. */ NUMBER,
        /** Plain text. */ TEXT,
        /** Date value. */ DATE,
        /** Boolean value. */ BOOL
    }

    private final Kind kind;
    private final String raw;
    private final String format;

    /**
     * Creates a typed cell value.
     *
     * @param kind   the recognised kind
     * @param raw    the raw text as it appeared in the source
     * @param format the number/date format pattern, or null
     */
    public CellValue(Kind kind, String raw, String format) {
        if (kind == null || raw == null) {
            throw new IllegalArgumentException("kind and raw must not be null");
        }
        this.kind = kind;
        this.raw = raw;
        this.format = format;
    }

    /**
     * Returns the recognised kind.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the raw source text.
     *
     * @return the raw text
     */
    public String getRaw() {
        return raw;
    }

    /**
     * Returns the format pattern.
     *
     * @return the format, or null
     */
    public String getFormat() {
        return format;
    }
}
