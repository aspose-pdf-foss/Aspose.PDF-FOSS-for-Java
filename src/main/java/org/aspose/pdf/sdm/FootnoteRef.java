package org.aspose.pdf.sdm;

/**
 * Footnote reference marker (IR spec §1.4); resolves to the {@link Footnote}
 * with the same refId.
 */
public final class FootnoteRef extends SdmInline {

    private final String refId;

    /**
     * Creates a footnote reference.
     *
     * @param refId the id of the referenced footnote
     */
    public FootnoteRef(String refId) {
        super(SdmNodeType.FOOTNOTE_REF);
        this.refId = refId;
    }

    /**
     * Returns the referenced footnote id.
     *
     * @return the refId
     */
    public String getRefId() {
        return refId;
    }
}
