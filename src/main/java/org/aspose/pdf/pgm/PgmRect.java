package org.aspose.pdf.pgm;

/**
 * Immutable axis-aligned rectangle in PDF user space (points), stored as
 * origin + extent per IR spec §2.1: (x, y) is the LOWER-LEFT corner.
 */
public final class PgmRect {

    private final double x;
    private final double y;
    private final double w;
    private final double h;

    /**
     * Creates a rectangle.
     *
     * @param x lower-left x
     * @param y lower-left y
     * @param w width (≥0)
     * @param h height (≥0)
     */
    public PgmRect(double x, double y, double w, double h) {
        this.x = x;
        this.y = y;
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
    }

    /**
     * Creates a rectangle from two corners in any order.
     *
     * @param x1 first corner x
     * @param y1 first corner y
     * @param x2 second corner x
     * @param y2 second corner y
     * @return the normalized rectangle
     */
    public static PgmRect fromCorners(double x1, double y1, double x2, double y2) {
        double lx = Math.min(x1, x2);
        double ly = Math.min(y1, y2);
        return new PgmRect(lx, ly, Math.abs(x2 - x1), Math.abs(y2 - y1));
    }

    /**
     * Returns the lower-left x.
     *
     * @return x
     */
    public double getX() {
        return x;
    }

    /**
     * Returns the lower-left y.
     *
     * @return y
     */
    public double getY() {
        return y;
    }

    /**
     * Returns the width.
     *
     * @return the width
     */
    public double getW() {
        return w;
    }

    /**
     * Returns the height.
     *
     * @return the height
     */
    public double getH() {
        return h;
    }

    /**
     * Returns the right edge (x + w).
     *
     * @return the right edge
     */
    public double getRight() {
        return x + w;
    }

    /**
     * Returns the top edge (y + h).
     *
     * @return the top edge
     */
    public double getTop() {
        return y + h;
    }

    /**
     * Tests intersection with another rectangle (touching edges count).
     *
     * @param o the other rectangle
     * @return true if the rectangles intersect
     */
    public boolean intersects(PgmRect o) {
        return o != null && x <= o.getRight() && o.x <= getRight()
                && y <= o.getTop() && o.y <= getTop();
    }

    /**
     * Returns the union of this rectangle with another.
     *
     * @param o the other rectangle (null returns this)
     * @return the bounding rectangle of both
     */
    public PgmRect union(PgmRect o) {
        if (o == null) {
            return this;
        }
        double lx = Math.min(x, o.x);
        double ly = Math.min(y, o.y);
        double rx = Math.max(getRight(), o.getRight());
        double ty = Math.max(getTop(), o.getTop());
        return new PgmRect(lx, ly, rx - lx, ty - ly);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "[%.2f,%.2f %.2fx%.2f]", x, y, w, h);
    }
}
