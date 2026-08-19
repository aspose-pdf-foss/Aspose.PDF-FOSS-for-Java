package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered or unordered list block (IR spec §1.3).
 */
public final class ListBlock extends SdmBlock {

    private final boolean ordered;
    private final Integer start;
    private String markerStyle;
    private final List<ListItem> items = new ArrayList<>();

    /**
     * Creates a list block.
     *
     * @param ordered true for an ordered (numbered) list
     * @param start   the start number for ordered lists, or null
     */
    public ListBlock(boolean ordered, Integer start) {
        super(SdmNodeType.LIST_BLOCK);
        this.ordered = ordered;
        this.start = start;
    }

    /**
     * Returns whether the list is ordered.
     *
     * @return true if ordered
     */
    public boolean isOrdered() {
        return ordered;
    }

    /**
     * Returns the start number of an ordered list.
     *
     * @return the start, or null
     */
    public Integer getStart() {
        return start;
    }

    /**
     * Returns the marker style hint (e.g. "disc", "decimal").
     *
     * @return the marker style, or null
     */
    public String getMarkerStyle() {
        return markerStyle;
    }

    /**
     * Sets the marker style hint.
     *
     * @param markerStyle the marker style
     */
    public void setMarkerStyle(String markerStyle) {
        this.markerStyle = markerStyle;
    }

    /**
     * Returns the mutable item list.
     *
     * @return the items
     */
    public List<ListItem> getItems() {
        return items;
    }
}
