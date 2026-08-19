package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SDM root (IR spec §1.2): metadata, block children, resource table, and the
 * document namespace used for id minting.
 */
public final class SdmDocument extends SdmNode {

    private final SdmMetadata metadata = new SdmMetadata();
    private final List<SdmBlock> children = new ArrayList<>();
    private final ResourceTable resources = new ResourceTable();
    private UUID nsDoc;

    /**
     * Creates an empty document.
     */
    public SdmDocument() {
        super(SdmNodeType.DOCUMENT);
    }

    /**
     * Returns the document metadata.
     *
     * @return the metadata
     */
    public SdmMetadata getMetadata() {
        return metadata;
    }

    /**
     * Returns the mutable top-level blocks.
     *
     * @return the children
     */
    public List<SdmBlock> getChildren() {
        return children;
    }

    /**
     * Returns the resource table.
     *
     * @return the resources
     */
    public ResourceTable getResources() {
        return resources;
    }

    /**
     * Returns the document namespace UUID used for id minting (see
     * {@link SdmIds#nsDoc}).
     *
     * @return the namespace, or null if not set
     */
    public UUID getNsDoc() {
        return nsDoc;
    }

    /**
     * Sets the document namespace UUID.
     *
     * @param nsDoc the namespace
     */
    public void setNsDoc(UUID nsDoc) {
        this.nsDoc = nsDoc;
    }
}
