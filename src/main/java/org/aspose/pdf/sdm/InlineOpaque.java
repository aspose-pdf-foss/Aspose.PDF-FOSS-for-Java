package org.aspose.pdf.sdm;

/**
 * Un-understood inline content preserved "as is" via provenance (IR spec §1.4).
 */
public final class InlineOpaque extends SdmInline {

    /**
     * Creates an inline opaque node.
     *
     * @param sourceRef the provenance (required — it IS the content)
     */
    public InlineOpaque(SourceRef sourceRef) {
        super(SdmNodeType.INLINE_OPAQUE);
        if (sourceRef == null) {
            throw new IllegalArgumentException("InlineOpaque requires a sourceRef");
        }
        setSourceRef(sourceRef);
    }
}
