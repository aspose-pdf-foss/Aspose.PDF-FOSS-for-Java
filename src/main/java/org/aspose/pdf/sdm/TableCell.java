package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Table cell (IR spec §1.3) with span, header/data kind, block children and an
 * optional typed {@link CellValue} (IR spec §1.7).
 */
public final class TableCell extends SdmBlock {

    /** Cell kind. */
    public enum Kind {
        /** Data cell. */ TD,
        /** Header cell. */ TH
    }

    private int rowSpan = 1;
    private int colSpan = 1;
    private Kind kind = Kind.TD;
    private CellValue cellValue;
    private final List<SdmBlock> children = new ArrayList<>();

    /**
     * Creates an empty data cell (1×1 span).
     */
    public TableCell() {
        super(SdmNodeType.TABLE_CELL);
    }

    /**
     * Returns the row span (≥1).
     *
     * @return the row span
     */
    public int getRowSpan() {
        return rowSpan;
    }

    /**
     * Sets the row span.
     *
     * @param rowSpan the span, forced to at least 1
     */
    public void setRowSpan(int rowSpan) {
        this.rowSpan = Math.max(1, rowSpan);
    }

    /**
     * Returns the column span (≥1).
     *
     * @return the column span
     */
    public int getColSpan() {
        return colSpan;
    }

    /**
     * Sets the column span.
     *
     * @param colSpan the span, forced to at least 1
     */
    public void setColSpan(int colSpan) {
        this.colSpan = Math.max(1, colSpan);
    }

    /**
     * Returns the cell kind.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Sets the cell kind.
     *
     * @param kind the kind
     */
    public void setKind(Kind kind) {
        this.kind = kind == null ? Kind.TD : kind;
    }

    /**
     * Returns the typed value.
     *
     * @return the value, or null
     */
    public CellValue getCellValue() {
        return cellValue;
    }

    /**
     * Sets the typed value.
     *
     * @param cellValue the value (may be null)
     */
    public void setCellValue(CellValue cellValue) {
        this.cellValue = cellValue;
    }

    /**
     * Returns the mutable child blocks.
     *
     * @return the children
     */
    public List<SdmBlock> getChildren() {
        return children;
    }
}
