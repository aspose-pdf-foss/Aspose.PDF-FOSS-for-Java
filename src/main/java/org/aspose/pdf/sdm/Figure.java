package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Figure block (IR spec §1.3): an image with optional caption blocks and alt
 * text. Image bytes live in the {@link ResourceTable}.
 */
public final class Figure extends SdmBlock {

    private final ResourceRef image;
    private final List<SdmBlock> caption = new ArrayList<>();
    private String alt;

    /**
     * Creates a figure over an image resource.
     *
     * @param image the image resource reference
     */
    public Figure(ResourceRef image) {
        super(SdmNodeType.FIGURE);
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
     * Returns the mutable caption blocks.
     *
     * @return the caption
     */
    public List<SdmBlock> getCaption() {
        return caption;
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
