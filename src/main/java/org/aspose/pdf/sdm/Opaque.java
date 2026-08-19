package org.aspose.pdf.sdm;

/**
 * Un-understood block content preserved "as is" (IR spec §1.0-5): carries only
 * its provenance; serialisation replays the source range verbatim.
 */
public final class Opaque extends SdmBlock {

    private String renderHint;

    /**
     * Creates an opaque block over a source location.
     *
     * @param sourceRef the provenance (required — it IS the content)
     */
    public Opaque(SourceRef sourceRef) {
        super(SdmNodeType.OPAQUE);
        if (sourceRef == null) {
            throw new IllegalArgumentException("Opaque requires a sourceRef");
        }
        setSourceRef(sourceRef);
    }

    /**
     * Returns the render hint (e.g. "vector", "shading").
     *
     * @return the hint, or null
     */
    public String getRenderHint() {
        return renderHint;
    }

    /**
     * Sets the render hint.
     *
     * @param renderHint the hint
     */
    public void setRenderHint(String renderHint) {
        this.renderHint = renderHint;
    }
}
