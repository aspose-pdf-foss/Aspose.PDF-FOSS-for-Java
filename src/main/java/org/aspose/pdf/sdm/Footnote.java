package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Footnote body (IR spec §1.3, rich model per decision §9.1); referenced from
 * text by {@link FootnoteRef} with the same refId.
 */
public final class Footnote extends SdmBlock {

    private final String refId;
    private final List<SdmBlock> children = new ArrayList<>();

    /**
     * Creates a footnote body.
     *
     * @param refId the reference id linking {@link FootnoteRef} markers here
     */
    public Footnote(String refId) {
        super(SdmNodeType.FOOTNOTE);
        this.refId = refId;
    }

    /**
     * Returns the reference id.
     *
     * @return the refId
     */
    public String getRefId() {
        return refId;
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
