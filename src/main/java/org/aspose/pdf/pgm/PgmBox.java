package org.aspose.pdf.pgm;

import org.aspose.pdf.sdm.SourceRef;

/**
 * One drawn object on one page (IR spec §2.1). Shares its id with the SDM node
 * it projects; one SDM node maps to 1..N boxes (multi-box, §2.4).
 */
public final class PgmBox {

    private final String id;
    private final int page;
    private final PgmRect rect;
    private int z;
    private final PgmBoxKind kind;
    private final SourceRef sourceRef;
    private int readingIndex = -1;
    private FlowClass flowClass = FlowClass.FLOW;
    private String anchorTargetId;
    private int partIndex;
    private int partCount = 1;
    private Object data;

    /**
     * Creates a box.
     *
     * @param id        the GUID shared with the projected SDM node
     * @param page      the 0-based page index
     * @param rect      the bounding rectangle in page space
     * @param z         the drawing-order index on the page
     * @param kind      the object kind
     * @param sourceRef the provenance (ContentRange for TEXT/IMAGE/VECTOR,
     *                  ObjectRef for ANNOTATION/FIELD)
     */
    public PgmBox(String id, int page, PgmRect rect, int z, PgmBoxKind kind,
                  SourceRef sourceRef) {
        if (rect == null || kind == null) {
            throw new IllegalArgumentException("rect and kind are required");
        }
        this.id = id;
        this.page = page;
        this.rect = rect;
        this.z = z;
        this.kind = kind;
        this.sourceRef = sourceRef;
    }

    /**
     * Returns the GUID shared with the SDM node.
     *
     * @return the id
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the 0-based page index.
     *
     * @return the page index
     */
    public int getPage() {
        return page;
    }

    /**
     * Returns the bounding rectangle.
     *
     * @return the rect
     */
    public PgmRect getRect() {
        return rect;
    }

    /**
     * Returns the drawing-order index (z-order).
     *
     * @return z
     */
    public int getZ() {
        return z;
    }

    /**
     * Sets the drawing-order index. Builders assign z after sorting boxes into
     * drawing order (stable sort, IR spec §2.8-3).
     *
     * @param z the z-order
     */
    public void setZ(int z) {
        this.z = z;
    }

    /**
     * Returns the object kind.
     *
     * @return the kind
     */
    public PgmBoxKind getKind() {
        return kind;
    }

    /**
     * Returns the provenance.
     *
     * @return the source reference
     */
    public SourceRef getSourceRef() {
        return sourceRef;
    }

    /**
     * Returns the position in the page reading order, or -1 if not assigned.
     *
     * @return the reading index
     */
    public int getReadingIndex() {
        return readingIndex;
    }

    /**
     * Sets the position in the page reading order.
     *
     * @param readingIndex the reading index
     */
    public void setReadingIndex(int readingIndex) {
        this.readingIndex = readingIndex;
    }

    /**
     * Returns how this box participates in reflow.
     *
     * @return the flow class
     */
    public FlowClass getFlowClass() {
        return flowClass;
    }

    /**
     * Sets the reflow participation class.
     *
     * @param flowClass the flow class
     */
    public void setFlowClass(FlowClass flowClass) {
        this.flowClass = flowClass == null ? FlowClass.FLOW : flowClass;
    }

    /**
     * Returns the GUID of the anchor target (for {@link FlowClass#ANCHORED}).
     *
     * @return the target id, or null
     */
    public String getAnchorTargetId() {
        return anchorTargetId;
    }

    /**
     * Sets the anchor target GUID and marks the box ANCHORED.
     *
     * @param anchorTargetId the target id
     */
    public void setAnchorTargetId(String anchorTargetId) {
        this.anchorTargetId = anchorTargetId;
        if (anchorTargetId != null) {
            this.flowClass = FlowClass.ANCHORED;
        }
    }

    /**
     * Returns this box's part index within a multi-box node (0-based).
     *
     * @return the part index
     */
    public int getPartIndex() {
        return partIndex;
    }

    /**
     * Returns the total number of parts of the multi-box node.
     *
     * @return the part count (≥1)
     */
    public int getPartCount() {
        return partCount;
    }

    /**
     * Sets the multi-box part position.
     *
     * @param partIndex 0-based part index
     * @param partCount total parts (≥1)
     */
    public void setPart(int partIndex, int partCount) {
        this.partIndex = Math.max(0, partIndex);
        this.partCount = Math.max(1, partCount);
    }

    /**
     * Returns the kind-specific payload ({@link TextBoxData}, {@link ImageBoxData},
     * {@link VectorBoxData}, {@link AnnotBoxData}), or null.
     *
     * @return the payload
     */
    public Object getData() {
        return data;
    }

    /**
     * Sets the kind-specific payload.
     *
     * @param data the payload
     */
    public void setData(Object data) {
        this.data = data;
    }

    @Override
    public String toString() {
        return kind + "@p" + page + rect + " z=" + z + " " + flowClass;
    }
}
