package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Table row (IR spec §1.3), typed by band kind.
 */
public final class TableRow extends SdmBlock {

    /** Band the row belongs to. */
    public enum Kind {
        /** Header band. */ HEADER,
        /** Body band. */ BODY,
        /** Footer band. */ FOOTER
    }

    private final Kind kind;
    private final List<TableCell> cells = new ArrayList<>();

    /**
     * Creates a row of the given band kind.
     *
     * @param kind the band kind
     */
    public TableRow(Kind kind) {
        super(SdmNodeType.TABLE_ROW);
        this.kind = kind == null ? Kind.BODY : kind;
    }

    /**
     * Returns the band kind.
     *
     * @return the kind
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the mutable cell list.
     *
     * @return the cells
     */
    public List<TableCell> getCells() {
        return cells;
    }
}
