package org.aspose.pdf.engine.layout;

import org.aspose.pdf.MarginInfo;

import java.util.logging.Logger;

/**
 * Tracks the cursor position and content area bounds during page layout.
 * <p>
 * PDF coordinates have their origin at the bottom-left of the page, with Y increasing
 * upward. Layout flows top-to-bottom, so the cursor starts at the top of the content
 * area (pageHeight - marginTop) and moves downward (Y decreases) as content is placed.
 * </p>
 * <p>
 * The content area is the region available for paragraph layout after subtracting
 * the page margins.
 * </p>
 */
public class LayoutContext {

    private static final Logger LOG = Logger.getLogger(LayoutContext.class.getName());

    /** Current horizontal position in PDF user-space units. */
    private double cursorX;

    /** Current vertical position in PDF user-space units (decreases as content flows down). */
    private double cursorY;

    /** Left edge of the content area. */
    private final double contentLeft;

    /** Right edge of the content area. */
    private final double contentRight;

    /** Top edge of the content area (in PDF coordinates). */
    private final double contentTop;

    /** Bottom edge of the content area (in PDF coordinates). */
    private final double contentBottom;

    /** Total page width in points. */
    private final double pageWidth;

    /** Total page height in points. */
    private final double pageHeight;

    /**
     * Transient per-block horizontal insets (a paragraph's own left/right
     * margin), folded into the effective content edges only while that block is
     * laid out. Both default to 0, so a block without explicit left/right
     * margins — every FOSS-native paragraph — sees the unchanged content area.
     */
    private double blockIndentLeft;
    private double blockIndentRight;

    /**
     * Creates a LayoutContext for a page with the given dimensions and margins.
     * <p>
     * The content area is computed as:
     * <ul>
     *   <li>contentLeft = marginLeft</li>
     *   <li>contentRight = pageWidth - marginRight</li>
     *   <li>contentTop = pageHeight - marginTop</li>
     *   <li>contentBottom = marginBottom</li>
     * </ul>
     * The cursor is initialized at (contentLeft, contentTop).
     * </p>
     *
     * @param pageWidth  the total page width in points
     * @param pageHeight the total page height in points
     * @param margins    the page margins; if null, zero margins are used
     */
    public LayoutContext(double pageWidth, double pageHeight, MarginInfo margins) {
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;

        double marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0;
        if (margins != null) {
            marginLeft = margins.getLeft();
            marginRight = margins.getRight();
            marginTop = margins.getTop();
            marginBottom = margins.getBottom();
        }

        this.contentLeft = marginLeft;
        this.contentRight = pageWidth - marginRight;
        this.contentTop = pageHeight - marginTop;
        this.contentBottom = marginBottom;

        this.cursorX = contentLeft;
        this.cursorY = contentTop;

        LOG.fine(() -> String.format("LayoutContext created: page=%.0fx%.0f, content area=[%.1f,%.1f]-[%.1f,%.1f]",
                pageWidth, pageHeight, contentLeft, contentBottom, contentRight, contentTop));
    }

    /**
     * Returns the available width of the content area.
     *
     * @return contentRight - contentLeft
     */
    public double getAvailableWidth() {
        return getContentRight() - getContentLeft();
    }

    /**
     * Applies a block's own left/right margin as a transient inset on the
     * content area for the duration of that block's layout. Negative values are
     * clamped to 0. Call {@link #clearBlockIndent()} when the block is done.
     *
     * @param left  left inset in points
     * @param right right inset in points
     */
    public void setBlockIndent(double left, double right) {
        this.blockIndentLeft = Math.max(0, left);
        this.blockIndentRight = Math.max(0, right);
    }

    /** Clears any transient block indent set by {@link #setBlockIndent}. */
    public void clearBlockIndent() {
        this.blockIndentLeft = 0;
        this.blockIndentRight = 0;
    }

    /**
     * Returns the remaining vertical space from the current cursor position
     * to the bottom of the content area.
     *
     * @return cursorY - contentBottom
     */
    public double getRemainingHeight() {
        return cursorY - contentBottom;
    }

    /**
     * Moves the cursor down by the specified height.
     * <p>
     * In PDF coordinates, moving down means decreasing Y.
     * </p>
     *
     * @param height the height to advance (positive value)
     */
    public void advanceCursor(double height) {
        this.cursorY -= height;
    }

    /**
     * Resets the horizontal cursor to the left edge of the content area.
     */
    public void resetCursorX() {
        this.cursorX = getContentLeft();
    }

    /**
     * Returns whether there is enough vertical space remaining for content
     * of the given height.
     *
     * @param height the required height
     * @return true if cursorY - height &gt;= contentBottom
     */
    public boolean hasSpace(double height) {
        return (cursorY - height) >= contentBottom;
    }

    /**
     * Returns the current horizontal cursor position.
     *
     * @return the X coordinate in PDF user-space units
     */
    public double getCursorX() {
        return cursorX;
    }

    /**
     * Sets the current horizontal cursor position.
     *
     * @param cursorX the X coordinate
     */
    public void setCursorX(double cursorX) {
        this.cursorX = cursorX;
    }

    /**
     * Returns the current vertical cursor position.
     *
     * @return the Y coordinate in PDF user-space units
     */
    public double getCursorY() {
        return cursorY;
    }

    /**
     * Sets the current vertical cursor position.
     *
     * @param cursorY the Y coordinate
     */
    public void setCursorY(double cursorY) {
        this.cursorY = cursorY;
    }

    /**
     * Returns the left edge of the content area.
     *
     * @return the content left X coordinate
     */
    public double getContentLeft() {
        return contentLeft + blockIndentLeft;
    }

    /**
     * Returns the right edge of the content area.
     *
     * @return the content right X coordinate
     */
    public double getContentRight() {
        return contentRight - blockIndentRight;
    }

    /**
     * Returns the top edge of the content area.
     *
     * @return the content top Y coordinate
     */
    public double getContentTop() {
        return contentTop;
    }

    /**
     * Returns the bottom edge of the content area.
     *
     * @return the content bottom Y coordinate
     */
    public double getContentBottom() {
        return contentBottom;
    }

    /**
     * Returns the total page width.
     *
     * @return the page width in points
     */
    public double getPageWidth() {
        return pageWidth;
    }

    /**
     * Returns the total page height.
     *
     * @return the page height in points
     */
    public double getPageHeight() {
        return pageHeight;
    }
}
