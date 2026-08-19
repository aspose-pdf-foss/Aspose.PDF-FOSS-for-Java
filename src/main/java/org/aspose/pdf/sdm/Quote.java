package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Block quotation (IR spec §1.3).
 */
public final class Quote extends SdmBlock {

    private final List<SdmBlock> children = new ArrayList<>();

    /**
     * Creates an empty quote.
     */
    public Quote() {
        super(SdmNodeType.QUOTE);
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
