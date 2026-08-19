package org.aspose.pdf.pgm;

import org.aspose.pdf.Matrix;
import org.aspose.pdf.sdm.ResourceRef;

/**
 * Kind-specific payload of an IMAGE box (IR spec §2.2): the resource reference
 * and the placement matrix (cm) in effect at the Do operator.
 */
public final class ImageBoxData {

    private final ResourceRef resourceRef;
    private final Matrix matrix;

    /**
     * Creates image box data.
     *
     * @param resourceRef the image resource reference (bytes in the ResourceTable)
     * @param matrix      the placement matrix
     */
    public ImageBoxData(ResourceRef resourceRef, Matrix matrix) {
        this.resourceRef = resourceRef;
        this.matrix = matrix;
    }

    /**
     * Returns the image resource reference.
     *
     * @return the reference, or null
     */
    public ResourceRef getResourceRef() {
        return resourceRef;
    }

    /**
     * Returns the placement matrix.
     *
     * @return the matrix, or null
     */
    public Matrix getMatrix() {
        return matrix;
    }
}
