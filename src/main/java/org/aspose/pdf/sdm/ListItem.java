package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * One item of a {@link ListBlock}; children are blocks, allowing nesting
 * (IR spec §1.3).
 */
public final class ListItem extends SdmBlock {

    private final List<SdmBlock> children = new ArrayList<>();

    /**
     * Creates an empty list item.
     */
    public ListItem() {
        super(SdmNodeType.LIST_ITEM);
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
