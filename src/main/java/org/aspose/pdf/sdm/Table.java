package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Table block (IR spec §1.3) with first-class {@link ColumnSpec} list
 * (IR spec §1.8).
 */
public final class Table extends SdmBlock {

    private String caption;
    private final List<ColumnSpec> columns = new ArrayList<>();
    private final List<TableRow> rows = new ArrayList<>();

    /**
     * Creates an empty table.
     */
    public Table() {
        super(SdmNodeType.TABLE);
    }

    /**
     * Returns the table caption.
     *
     * @return the caption, or null
     */
    public String getCaption() {
        return caption;
    }

    /**
     * Sets the table caption.
     *
     * @param caption the caption
     */
    public void setCaption(String caption) {
        this.caption = caption;
    }

    /**
     * Returns the mutable column specs.
     *
     * @return the columns
     */
    public List<ColumnSpec> getColumns() {
        return columns;
    }

    /**
     * Returns the mutable row list.
     *
     * @return the rows
     */
    public List<TableRow> getRows() {
        return rows;
    }
}
