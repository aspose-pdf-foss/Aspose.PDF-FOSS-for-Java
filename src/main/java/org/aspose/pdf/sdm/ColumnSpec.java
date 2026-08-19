package org.aspose.pdf.sdm;

/**
 * First-class column description of a table (IR spec §1.8).
 */
public final class ColumnSpec {

    /** How the column width is expressed. */
    public enum WidthType {
        /** Width decided by layout. */ AUTO,
        /** Absolute width in points. */ POINTS,
        /** Percentage of table width. */ PERCENT
    }

    /** Default horizontal alignment of cells in this column. */
    public enum Align {
        /** Left. */ LEFT,
        /** Right. */ RIGHT,
        /** Center. */ CENTER
    }

    private final WidthType widthType;
    private final double width;
    private final Align defaultAlign;

    /**
     * Creates a column spec.
     *
     * @param widthType    how the width is expressed
     * @param width        the width value (ignored for AUTO)
     * @param defaultAlign the default cell alignment
     */
    public ColumnSpec(WidthType widthType, double width, Align defaultAlign) {
        this.widthType = widthType == null ? WidthType.AUTO : widthType;
        this.width = width;
        this.defaultAlign = defaultAlign == null ? Align.LEFT : defaultAlign;
    }

    /**
     * Creates an AUTO-width, left-aligned column spec.
     */
    public ColumnSpec() {
        this(WidthType.AUTO, 0, Align.LEFT);
    }

    /**
     * Returns how the width is expressed.
     *
     * @return the width type
     */
    public WidthType getWidthType() {
        return widthType;
    }

    /**
     * Returns the width value (points or percent, per {@link #getWidthType()}).
     *
     * @return the width
     */
    public double getWidth() {
        return width;
    }

    /**
     * Returns the default cell alignment.
     *
     * @return the alignment
     */
    public Align getDefaultAlign() {
        return defaultAlign;
    }
}
