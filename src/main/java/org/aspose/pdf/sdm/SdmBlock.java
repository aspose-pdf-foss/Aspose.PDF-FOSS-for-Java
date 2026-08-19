package org.aspose.pdf.sdm;

/**
 * Base of all block-level nodes (vertical flow, IR spec §1.3). Every block
 * carries an optional explicit {@link BlockStyle}.
 */
public abstract class SdmBlock extends SdmNode {

    private BlockStyle style;

    /**
     * Creates a block node of the given type.
     *
     * @param type the node type
     */
    protected SdmBlock(SdmNodeType type) {
        super(type);
    }

    /**
     * Returns the explicit block style, or null if unset.
     *
     * @return the style
     */
    public BlockStyle getStyle() {
        return style;
    }

    /**
     * Sets the explicit block style.
     *
     * @param style the style (may be null)
     */
    public void setStyle(BlockStyle style) {
        this.style = style;
    }
}
