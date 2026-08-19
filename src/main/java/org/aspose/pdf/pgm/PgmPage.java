package org.aspose.pdf.pgm;

import java.util.ArrayList;
import java.util.List;

/**
 * One page of the geometry model (IR spec §2.3): size, rotation, and boxes in
 * z-order.
 */
public final class PgmPage {

    private final int index;
    private final double width;
    private final double height;
    private final int rotation;
    private final List<PgmBox> boxes = new ArrayList<>();
    private ColumnStructure columnStructure;

    /**
     * Creates a page.
     *
     * @param index    the 0-based page index
     * @param width    the page width in points
     * @param height   the page height in points
     * @param rotation the /Rotate value in degrees
     */
    public PgmPage(int index, double width, double height, int rotation) {
        this.index = index;
        this.width = width;
        this.height = height;
        this.rotation = rotation;
    }

    /**
     * Returns the 0-based page index.
     *
     * @return the index
     */
    public int getIndex() {
        return index;
    }

    /**
     * Returns the page width in points.
     *
     * @return the width
     */
    public double getWidth() {
        return width;
    }

    /**
     * Returns the page height in points.
     *
     * @return the height
     */
    public double getHeight() {
        return height;
    }

    /**
     * Returns the page /Rotate in degrees.
     *
     * @return the rotation
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Returns the mutable box list in z-order (drawing order).
     *
     * @return the boxes
     */
    public List<PgmBox> getBoxes() {
        return boxes;
    }

    /**
     * Returns the column-structure classification (PART 6), or null if not
     * classified.
     *
     * @return the classification
     */
    public ColumnStructure getColumnStructure() {
        return columnStructure;
    }

    /**
     * Sets the column-structure classification.
     *
     * @param columnStructure the classification
     */
    public void setColumnStructure(ColumnStructure columnStructure) {
        this.columnStructure = columnStructure;
    }
}
