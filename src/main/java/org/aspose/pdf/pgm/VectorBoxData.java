package org.aspose.pdf.pgm;

/**
 * Kind-specific payload of a VECTOR box (IR spec §2.2). LINE endpoints are
 * kept because table (rulings) and column detectors need them; for PATH only
 * the bbox (on the box itself) is retained.
 */
public final class VectorBoxData {

    /** Class of vector primitive. */
    public enum Primitive {
        /** Single straight line segment. */ LINE,
        /** Axis-aligned rectangle (re operator or 4-line closed path). */ RECT,
        /** Anything else. */ PATH
    }

    private final Primitive primitive;
    private final double x1;
    private final double y1;
    private final double x2;
    private final double y2;
    private final boolean stroked;
    private final boolean filled;
    private double[] ctm;
    private boolean consumedAsBackground;
    /** Fill colour as 0xAARRGGBB (0 = unknown/none). */
    private int fillColor;

    /**
     * Creates vector box data.
     *
     * @param primitive the primitive class
     * @param x1        line start x (LINE only; else 0)
     * @param y1        line start y
     * @param x2        line end x
     * @param y2        line end y
     * @param stroked   whether the paint op strokes
     * @param filled    whether the paint op fills
     */
    public VectorBoxData(Primitive primitive, double x1, double y1, double x2, double y2,
                         boolean stroked, boolean filled) {
        this.primitive = primitive == null ? Primitive.PATH : primitive;
        this.x1 = x1;
        this.y1 = y1;
        this.x2 = x2;
        this.y2 = y2;
        this.stroked = stroked;
        this.filled = filled;
    }

    /**
     * Returns the primitive class.
     *
     * @return the primitive
     */
    public Primitive getPrimitive() {
        return primitive;
    }

    /**
     * Returns the fill colour as 0xAARRGGBB (0 = unknown/none).
     *
     * @return the fill colour
     */
    public int getFillColor() {
        return fillColor;
    }

    /**
     * Sets the fill colour as 0xAARRGGBB.
     *
     * @param argb the fill colour
     */
    public void setFillColor(int argb) {
        this.fillColor = argb;
    }

    /**
     * Returns the line start x (LINE primitive).
     *
     * @return x1
     */
    public double getX1() {
        return x1;
    }

    /**
     * Returns the line start y.
     *
     * @return y1
     */
    public double getY1() {
        return y1;
    }

    /**
     * Returns the line end x.
     *
     * @return x2
     */
    public double getX2() {
        return x2;
    }

    /**
     * Returns the line end y.
     *
     * @return y2
     */
    public double getY2() {
        return y2;
    }

    /**
     * Returns whether the paint op strokes the path.
     *
     * @return true if stroked
     */
    public boolean isStroked() {
        return stroked;
    }

    /**
     * Returns whether the paint op fills the path.
     *
     * @return true if filled
     */
    public boolean isFilled() {
        return filled;
    }

    /**
     * Returns the CTM in effect when the path was painted (a b c d e f), or
     * null if not recorded. Positional replay needs it to translate the path
     * in device space.
     *
     * @return the CTM, or null
     */
    public double[] getCtm() {
        return ctm;
    }

    /**
     * Records the CTM in effect at paint time.
     *
     * @param ctm the six matrix values (kept by reference)
     */
    public void setCtm(double[] ctm) {
        this.ctm = ctm;
    }

    /**
     * Returns whether this fill was projected as a text-block background
     * (CSS {@code background-color}) by the fill-background enrichment.
     *
     * @return true if already represented as a block background
     */
    public boolean isConsumedAsBackground() {
        return consumedAsBackground;
    }

    /**
     * Marks this fill as represented by a text-block background, so later
     * passes (vector rasterization) do not duplicate it as pixels.
     *
     * @param consumedAsBackground true when a block background carries it
     */
    public void setConsumedAsBackground(boolean consumedAsBackground) {
        this.consumedAsBackground = consumedAsBackground;
    }
}
