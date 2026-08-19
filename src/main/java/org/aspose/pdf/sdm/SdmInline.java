package org.aspose.pdf.sdm;

/**
 * Base of all inline-level nodes (horizontal flow inside a block, IR spec §1.4).
 */
public abstract class SdmInline extends SdmNode {

    /**
     * Creates an inline node of the given type.
     *
     * @param type the node type
     */
    protected SdmInline(SdmNodeType type) {
        super(type);
    }
}
