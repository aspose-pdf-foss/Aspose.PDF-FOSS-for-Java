package org.aspose.pdf.drawing;

import java.util.logging.Logger;

/**
 * Represents an arc drawing shape.
 * <p>
 * The arc is defined by its center ({@code posX}, {@code posY}), {@code radius},
 * and the angular range from {@code startAngle} to {@code endAngle} (in degrees).
 * The bounding box is computed from the actual arc extent to support accurate
 * bounds checking.
 * </p>
 */
public class Arc extends Shape {

    private static final Logger LOG = Logger.getLogger(Arc.class.getName());

    private float posX;
    private float posY;
    private float radius;
    private float startAngle;
    private float endAngle;

    /**
     * Creates a new arc with the specified center, radius, and angular range.
     *
     * @param posX       the x-coordinate of the center
     * @param posY       the y-coordinate of the center
     * @param radius     the radius of the arc
     * @param startAngle the start angle in degrees
     * @param endAngle   the end angle in degrees
     */
    public Arc(float posX, float posY, float radius, float startAngle, float endAngle) {
        this.posX = posX;
        this.posY = posY;
        this.radius = radius;
        this.startAngle = startAngle;
        this.endAngle = endAngle;
    }

    /**
     * Gets the x-coordinate of the center.
     *
     * @return the center x-coordinate
     */
    public float getPosX() {
        return posX;
    }

    /**
     * Sets the x-coordinate of the center.
     *
     * @param posX the center x-coordinate
     */
    public void setPosX(float posX) {
        this.posX = posX;
    }

    /**
     * Gets the y-coordinate of the center.
     *
     * @return the center y-coordinate
     */
    public float getPosY() {
        return posY;
    }

    /**
     * Sets the y-coordinate of the center.
     *
     * @param posY the center y-coordinate
     */
    public void setPosY(float posY) {
        this.posY = posY;
    }

    /**
     * Gets the radius.
     *
     * @return the radius
     */
    public float getRadius() {
        return radius;
    }

    /**
     * Sets the radius.
     *
     * @param radius the radius
     */
    public void setRadius(float radius) {
        this.radius = radius;
    }

    /**
     * Gets the start angle in degrees.
     *
     * @return the start angle
     */
    public float getStartAngle() {
        return startAngle;
    }

    /**
     * Sets the start angle in degrees.
     *
     * @param startAngle the start angle
     */
    public void setStartAngle(float startAngle) {
        this.startAngle = startAngle;
    }

    /**
     * Gets the end angle in degrees.
     *
     * @return the end angle
     */
    public float getEndAngle() {
        return endAngle;
    }

    /**
     * Sets the end angle in degrees.
     *
     * @param endAngle the end angle
     */
    public void setEndAngle(float endAngle) {
        this.endAngle = endAngle;
    }

    /**
     * {@inheritDoc}
     * <p>
     * Computes the actual bounding box of the arc by evaluating the endpoints
     * and any axis-aligned extrema that fall within the angular range.
     * </p>
     */
    @Override
    public void checkBounds(double width, double height) {
        // The arc is drawn starting at startAngle and sweeping by
        // (endAngle - startAngle) degrees. The sign of that difference selects
        // the direction (positive = counter-clockwise, negative = clockwise),
        // and only the actually-swept angular range contributes to the bounding
        // box — NOT the full circle.
        //
        // The swept amount is reduced modulo 360 so that e.g. Arc(...,-90,360)
        // (raw span 450°) is the same 90° arc as Arc(...,270,360), rather than a
        // full circle. A raw difference that is a non-zero multiple of 360 means
        // a complete circle. Absolute angles are used when sampling cos/sin, so
        // negative or >360 values are fine (trigonometric functions are periodic).
        double rawSweep = endAngle - startAngle;
        double sweep = rawSweep % 360.0;
        boolean fullCircle = rawSweep != 0.0 && sweep == 0.0;

        double angBegin;
        double angEnd;
        if (fullCircle) {
            angBegin = startAngle;
            angEnd = startAngle + 360.0;
        } else {
            angBegin = Math.min(startAngle, startAngle + sweep);
            angEnd = Math.max(startAngle, startAngle + sweep);
        }

        // Evaluate endpoints
        double begRad = Math.toRadians(angBegin);
        double endRad = Math.toRadians(angEnd);

        double minX = Math.min(Math.cos(begRad), Math.cos(endRad));
        double maxX = Math.max(Math.cos(begRad), Math.cos(endRad));
        double minY = Math.min(Math.sin(begRad), Math.sin(endRad));
        double maxY = Math.max(Math.sin(begRad), Math.sin(endRad));

        // Check axis-aligned extrema (multiples of 90°) strictly inside the range
        double first90 = Math.floor(angBegin / 90.0 + 1.0) * 90.0;
        for (double a = first90; a < angEnd - 1e-9; a += 90.0) {
            double rad = Math.toRadians(a);
            minX = Math.min(minX, Math.cos(rad));
            maxX = Math.max(maxX, Math.cos(rad));
            minY = Math.min(minY, Math.sin(rad));
            maxY = Math.max(maxY, Math.sin(rad));
        }

        // Scale by radius and translate to center
        double actualMinX = posX + minX * radius;
        double actualMaxX = posX + maxX * radius;
        double actualMinY = posY + minY * radius;
        double actualMaxY = posY + maxY * radius;

        double eps = 1e-9;
        if (actualMinX < -eps) {
            throw new BoundsOutOfRangeException(
                    "Arc left edge (" + actualMinX + ") is negative");
        }
        if (actualMinY < -eps) {
            throw new BoundsOutOfRangeException(
                    "Arc bottom edge (" + actualMinY + ") is negative");
        }
        if (actualMaxX > width + eps) {
            throw new BoundsOutOfRangeException(
                    "Arc right edge (" + actualMaxX +
                    ") exceeds container width (" + width + ")");
        }
        if (actualMaxY > height + eps) {
            throw new BoundsOutOfRangeException(
                    "Arc top edge (" + actualMaxY +
                    ") exceeds container height (" + height + ")");
        }
    }
}
