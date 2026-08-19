package org.aspose.pdf.sdm;

/**
 * Explicit computed style of a block node (IR spec §1.6). Styles are EXPLICIT
 * per node: any cascade is resolved by the reader at read time; the model has
 * no cascade machinery.
 */
public final class BlockStyle {

    /** Horizontal alignment of block content. */
    public enum Align {
        /** Left-aligned. */ LEFT,
        /** Right-aligned. */ RIGHT,
        /** Centered. */ CENTER,
        /** Justified. */ JUSTIFY
    }

    /** Text direction. */
    public enum Direction {
        /** Left-to-right. */ LTR,
        /** Right-to-left. */ RTL
    }

    private Align align = Align.LEFT;
    private double indentStart;
    private double indentEnd;
    private double indentFirstLine;
    private double spaceBefore;
    private double spaceAfter;
    private double lineHeight;
    private Direction direction = Direction.LTR;
    /** Block background colour as 0xAARRGGBB (0 = none). */
    private int background;
    private double borderBottomWidth;
    private int borderBottomColor;
    private String borderBottomStyle;

    /**
     * Returns the horizontal alignment.
     *
     * @return the alignment
     */
    public Align getAlign() {
        return align;
    }

    /**
     * Sets the horizontal alignment.
     *
     * @param align the alignment
     */
    public void setAlign(Align align) {
        this.align = align;
    }

    /**
     * Returns the start-side indent in points.
     *
     * @return the indent
     */
    public double getIndentStart() {
        return indentStart;
    }

    /**
     * Sets the start-side indent in points.
     *
     * @param v the indent
     */
    public void setIndentStart(double v) {
        this.indentStart = v;
    }

    /**
     * Returns the end-side indent in points.
     *
     * @return the indent
     */
    public double getIndentEnd() {
        return indentEnd;
    }

    /**
     * Sets the end-side indent in points.
     *
     * @param v the indent
     */
    public void setIndentEnd(double v) {
        this.indentEnd = v;
    }

    /**
     * Returns the first-line indent in points.
     *
     * @return the indent
     */
    public double getIndentFirstLine() {
        return indentFirstLine;
    }

    /**
     * Sets the first-line indent in points.
     *
     * @param v the indent
     */
    public void setIndentFirstLine(double v) {
        this.indentFirstLine = v;
    }

    /**
     * Returns the space before the block in points.
     *
     * @return the space
     */
    public double getSpaceBefore() {
        return spaceBefore;
    }

    /**
     * Sets the space before the block in points.
     *
     * @param v the space
     */
    public void setSpaceBefore(double v) {
        this.spaceBefore = v;
    }

    /**
     * Returns the space after the block in points.
     *
     * @return the space
     */
    public double getSpaceAfter() {
        return spaceAfter;
    }

    /**
     * Sets the space after the block in points.
     *
     * @param v the space
     */
    public void setSpaceAfter(double v) {
        this.spaceAfter = v;
    }

    /**
     * Returns the line height multiplier (0 = unset/normal).
     *
     * @return the line height
     */
    public double getLineHeight() {
        return lineHeight;
    }

    /**
     * Sets the line height multiplier.
     *
     * @param v the line height
     */
    public void setLineHeight(double v) {
        this.lineHeight = v;
    }

    /**
     * Returns the text direction.
     *
     * @return the direction
     */
    public Direction getDirection() {
        return direction;
    }

    /**
     * Sets the text direction.
     *
     * @param direction the direction
     */
    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    /**
     * Returns the block background colour as 0xAARRGGBB (0 = none).
     *
     * @return the background colour
     */
    public int getBackground() {
        return background;
    }

    /**
     * Sets the block background colour as 0xAARRGGBB (0 = none).
     *
     * @param argb the background colour
     */
    public void setBackground(int argb) {
        this.background = argb;
    }

    /**
     * Returns the bottom-border thickness in points (0 = no bottom border).
     *
     * @return the bottom-border thickness
     */
    public double getBorderBottomWidth() {
        return borderBottomWidth;
    }

    /**
     * Sets the bottom-border thickness in points.
     *
     * @param w the thickness
     */
    public void setBorderBottomWidth(double w) {
        this.borderBottomWidth = w;
    }

    /**
     * Returns the bottom-border colour as 0xAARRGGBB.
     *
     * @return the bottom-border colour
     */
    public int getBorderBottomColor() {
        return borderBottomColor;
    }

    /**
     * Sets the bottom-border colour as 0xAARRGGBB.
     *
     * @param argb the colour
     */
    public void setBorderBottomColor(int argb) {
        this.borderBottomColor = argb;
    }

    /**
     * Returns the bottom-border style ({@code solid}/{@code dotted}/{@code dashed}).
     *
     * @return the border style, or {@code null}
     */
    public String getBorderBottomStyle() {
        return borderBottomStyle;
    }

    /**
     * Sets the bottom-border style.
     *
     * @param style the border style
     */
    public void setBorderBottomStyle(String style) {
        this.borderBottomStyle = style;
    }
}
