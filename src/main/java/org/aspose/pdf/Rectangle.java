package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSFloat;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents a rectangle defined by lower-left and upper-right corners (ISO 32000-1:2008, §7.9.5).
 * <p>
 * PDF rectangles are specified as arrays of four numbers {@code [llx lly urx ury]}
 * giving the coordinates of two diagonally opposite corners. This class provides
 * convenience accessors and conversion to/from COS arrays.
 * </p>
 */
public class Rectangle {

    private static final Logger LOG = Logger.getLogger(Rectangle.class.getName());

    private final double llx;
    private final double lly;
    private final double urx;
    private final double ury;

    /**
     * Creates a rectangle from its four corner coordinates.
     *
     * @param llx lower-left x coordinate
     * @param lly lower-left y coordinate
     * @param urx upper-right x coordinate
     * @param ury upper-right y coordinate
     */
    public Rectangle(double llx, double lly, double urx, double ury) {
        this.llx = llx;
        this.lly = lly;
        this.urx = urx;
        this.ury = ury;
        LOG.finer(() -> "Rectangle created: [" + llx + ", " + lly + ", " + urx + ", " + ury + "]"); // per-object trace: debug level (Sprint 32 A)
    }

    /**
     * Returns the lower-left x coordinate.
     *
     * @return the lower-left x coordinate
     */
    public double getLLX() {
        return llx;
    }

    /**
     * Returns the lower-left y coordinate.
     *
     * @return the lower-left y coordinate
     */
    public double getLLY() {
        return lly;
    }

    /**
     * Returns the upper-right x coordinate.
     *
     * @return the upper-right x coordinate
     */
    public double getURX() {
        return urx;
    }

    /**
     * Returns the upper-right y coordinate.
     *
     * @return the upper-right y coordinate
     */
    public double getURY() {
        return ury;
    }

    /**
     * Returns the width of this rectangle (urx - llx).
     *
     * @return the width
     */
    public double getWidth() {
        return urx - llx;
    }

    /**
     * Returns the height of this rectangle (ury - lly).
     *
     * @return the height
     */
    public double getHeight() {
        return ury - lly;
    }

    /**
     * Creates a Rectangle from a COS array of four numbers.
     *
     * @param array the COS array containing [llx, lly, urx, ury]
     * @return a new Rectangle
     * @throws IllegalArgumentException if the array is null or does not have exactly 4 elements
     */
    public static Rectangle fromCOSArray(COSArray array) {
        if (array == null) {
            throw new IllegalArgumentException("COSArray must not be null");
        }
        if (array.size() != 4) {
            throw new IllegalArgumentException("Rectangle COSArray must have exactly 4 elements, got " + array.size());
        }
        return new Rectangle(
                array.getFloat(0, 0f),
                array.getFloat(1, 0f),
                array.getFloat(2, 0f),
                array.getFloat(3, 0f)
        );
    }

    /**
     * Converts this rectangle to a COS array of four numbers.
     *
     * @return a COSArray containing [llx, lly, urx, ury]
     */
    public COSArray toCOSArray() {
        COSArray array = new COSArray(4);
        array.add(new COSFloat(llx));
        array.add(new COSFloat(lly));
        array.add(new COSFloat(urx));
        array.add(new COSFloat(ury));
        return array;
    }

    /**
     * Determines whether this rectangle intersects with the specified rectangle.
     *
     * @param other the other rectangle to test
     * @return {@code true} if the rectangles overlap, {@code false} otherwise
     */
    public boolean isIntersect(Rectangle other) {
        if (other == null) {
            return false;
        }
        // Two rectangles do NOT intersect if one is entirely to the left, right,
        // above, or below the other.
        return !(this.urx <= other.llx || other.urx <= this.llx
                || this.ury <= other.lly || other.ury <= this.lly);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Rectangle)) return false;
        Rectangle r = (Rectangle) o;
        return Double.compare(llx, r.llx) == 0
                && Double.compare(lly, r.lly) == 0
                && Double.compare(urx, r.urx) == 0
                && Double.compare(ury, r.ury) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(llx, lly, urx, ury);
    }

    @Override
    public String toString() {
        return "Rectangle[" + llx + ", " + lly + ", " + urx + ", " + ury + "]";
    }
}
