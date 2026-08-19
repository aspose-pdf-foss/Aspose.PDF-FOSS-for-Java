package org.aspose.pdf.sdm;

/**
 * Inline image (IR spec §1.4). Bytes live in the {@link ResourceTable}.
 */
public final class InlineImage extends SdmInline {

    private final ResourceRef image;
    private String alt;

    /**
     * Creates an inline image.
     *
     * @param image the image resource reference
     */
    public InlineImage(ResourceRef image) {
        super(SdmNodeType.INLINE_IMAGE);
        this.image = image;
    }

    /**
     * Returns the image resource reference.
     *
     * @return the reference
     */
    public ResourceRef getImage() {
        return image;
    }

    /**
     * Returns the alternative text.
     *
     * @return the alt text, or null
     */
    public String getAlt() {
        return alt;
    }

    /**
     * Sets the alternative text.
     *
     * @param alt the alt text
     */
    public void setAlt(String alt) {
        this.alt = alt;
    }
}
