package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Table-of-contents block (IR spec §1.3, rich model per decision §9.1);
 * writers without native TOC support degrade it (e.g. to a plain list).
 */
public final class TocBlock extends SdmBlock {

    private final List<TocEntry> entries = new ArrayList<>();

    /**
     * Creates an empty TOC block.
     */
    public TocBlock() {
        super(SdmNodeType.TOC_BLOCK);
    }

    /**
     * Returns the mutable entry list.
     *
     * @return the entries
     */
    public List<TocEntry> getEntries() {
        return entries;
    }
}
