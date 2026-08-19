package org.aspose.pdf.pgm;

/**
 * Column-structure classification of one page (IR spec §2.7).
 */
public final class ColumnStructure {

    /** Page column type. */
    public enum Type {
        /** Single text column. */ SINGLE_COLUMN,
        /** Clean N-column layout. */ MULTI_CLEAN,
        /** Columns interrupted by full-width spanning zones. */ MULTI_MIXED
    }

    private final Type type;
    private final int columnCount;
    private final double[][] bands;

    /**
     * Creates a classification without band geometry.
     *
     * @param type        the page type
     * @param columnCount the detected column count (1 for SINGLE_COLUMN)
     */
    public ColumnStructure(Type type, int columnCount) {
        this(type, columnCount, null);
    }

    /**
     * Creates a classification with column band geometry.
     *
     * @param type        the page type
     * @param columnCount the detected column count
     * @param bands       the x-ranges {x0, x1} of the column bands (may be null)
     */
    public ColumnStructure(Type type, int columnCount, double[][] bands) {
        this.type = type == null ? Type.SINGLE_COLUMN : type;
        this.columnCount = Math.max(1, columnCount);
        this.bands = bands;
    }

    /**
     * Returns the x-ranges {x0, x1} of the detected column bands (left to
     * right), or null when not recorded (single-column pages).
     *
     * @return the bands, or null
     */
    public double[][] getBands() {
        return bands;
    }

    /**
     * Returns the page column type.
     *
     * @return the type
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the detected column count.
     *
     * @return the count (≥1)
     */
    public int getColumnCount() {
        return columnCount;
    }

    @Override
    public String toString() {
        return type + (type == Type.SINGLE_COLUMN ? "" : "(" + columnCount + ")");
    }
}
