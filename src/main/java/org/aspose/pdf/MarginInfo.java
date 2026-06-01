package org.aspose.pdf;

import java.util.logging.Logger;

/**
 * Represents margin information for a content element. All margin values are
 * expressed in PDF user-space units (1/72 inch), default 0 for all sides.
 *
 * <p><strong>Constructor argument order: (left, bottom, right, top)</strong> —
 * this matches Aspose .NET's {@code MarginInfo(double, double, double, double)}.
 * Note that this is <em>not</em> the same order as the (now-deprecated) nested
 * {@code PageInfo.MarginInfo(top, bottom, left, right)}. Example:</p>
 *
 * <pre>{@code
 * // 50pt left + 80pt right margins, 60pt top/bottom:
 * MarginInfo m = new MarginInfo(50, 60, 80, 60);
 * pageInfo.setMargin(m);
 * }</pre>
 *
 * <p>If you only need uniform margins, use {@link #MarginInfo(double)}.</p>
 */
public class MarginInfo {

    private static final Logger LOG = Logger.getLogger(MarginInfo.class.getName());

    private double top;
    private double bottom;
    private double left;
    private double right;

    /**
     * Creates a MarginInfo with all margins set to 0.
     */
    public MarginInfo() {
        // all default to 0
    }

    /**
     * Creates a MarginInfo with the specified margin values for each side.
     *
     * @param left   the left margin
     * @param bottom the bottom margin
     * @param right  the right margin
     * @param top    the top margin
     */
    public MarginInfo(double left, double bottom, double right, double top) {
        this.left = left;
        this.bottom = bottom;
        this.right = right;
        this.top = top;
    }

    /**
     * Creates a MarginInfo with all four margins set to the same value.
     *
     * @param all the margin value to apply to all sides
     */
    public MarginInfo(double all) {
        this.top = all;
        this.bottom = all;
        this.left = all;
        this.right = all;
    }

    /**
     * Gets the top margin value.
     *
     * @return the top margin in user-space units
     */
    public double getTop() {
        return top;
    }

    /**
     * Sets the top margin value.
     *
     * @param top the top margin in user-space units
     */
    public void setTop(double top) {
        this.top = top;
    }

    /**
     * Gets the bottom margin value.
     *
     * @return the bottom margin in user-space units
     */
    public double getBottom() {
        return bottom;
    }

    /**
     * Sets the bottom margin value.
     *
     * @param bottom the bottom margin in user-space units
     */
    public void setBottom(double bottom) {
        this.bottom = bottom;
    }

    /**
     * Gets the left margin value.
     *
     * @return the left margin in user-space units
     */
    public double getLeft() {
        return left;
    }

    /**
     * Sets the left margin value.
     *
     * @param left the left margin in user-space units
     */
    public void setLeft(double left) {
        this.left = left;
    }

    /**
     * Gets the right margin value.
     *
     * @return the right margin in user-space units
     */
    public double getRight() {
        return right;
    }

    /**
     * Sets the right margin value.
     *
     * @param right the right margin in user-space units
     */
    public void setRight(double right) {
        this.right = right;
    }

    @Override
    public String toString() {
        return "MarginInfo{top=" + top + ", bottom=" + bottom
                + ", left=" + left + ", right=" + right + '}';
    }
}
