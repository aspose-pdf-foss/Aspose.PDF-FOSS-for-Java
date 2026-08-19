package org.aspose.pdf.sdm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base of every SDM node (IR spec §1.1): identity, type discriminator, open
 * attribute map, and optional provenance.
 * <p>
 * Attributes are the extension point: writers read the keys they know and MUST
 * preserve unknown keys on round-trip (never delete them).
 * </p>
 */
public abstract class SdmNode {

    private String id;
    private final SdmNodeType type;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private SourceRef sourceRef;

    /**
     * Creates a node of the given type.
     *
     * @param type the node type discriminator
     */
    protected SdmNode(SdmNodeType type) {
        this.type = type;
    }

    /**
     * Returns the node GUID (see {@link SdmIds}); shared with the PGM boxes
     * projecting this node.
     *
     * @return the id, or null if not yet assigned
     */
    public String getId() {
        return id;
    }

    /**
     * Assigns the node GUID. Assigned once at first construction; edits never
     * recompute it.
     *
     * @param id the GUID string
     */
    public void setId(String id) {
        this.id = id;
    }

    /**
     * Returns the node type discriminator.
     *
     * @return the type
     */
    public SdmNodeType getType() {
        return type;
    }

    /**
     * Returns the mutable open attribute map (unknown keys are preserved on
     * round-trip).
     *
     * @return the attributes
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * Returns the node provenance, or null for session-created nodes.
     *
     * @return the source reference
     */
    public SourceRef getSourceRef() {
        return sourceRef;
    }

    /**
     * Sets the node provenance.
     *
     * @param sourceRef the source reference (may be null)
     */
    public void setSourceRef(SourceRef sourceRef) {
        this.sourceRef = sourceRef;
    }
}
