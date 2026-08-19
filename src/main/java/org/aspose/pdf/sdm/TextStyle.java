package org.aspose.pdf.sdm;

/**
 * Explicit computed style of an inline run (IR spec §1.5). Colors are packed
 * sRGB {@code 0xAARRGGBB} ints (0 = unset).
 */
public final class TextStyle {

    /** Vertical alignment of the run relative to the baseline. */
    public enum VertAlign {
        /** Normal baseline. */ NORMAL,
        /** Superscript. */ SUPER,
        /** Subscript. */ SUB
    }

    private String fontFamily;
    private double fontSize;
    private boolean bold;
    private boolean italic;
    private boolean underline;
    private boolean strikethrough;
    private int color;
    private int background;
    private VertAlign vertAlign = VertAlign.NORMAL;

    /**
     * Returns the font family name.
     *
     * @return the family, or null if unset
     */
    public String getFontFamily() {
        return fontFamily;
    }

    /**
     * Sets the font family name.
     *
     * @param fontFamily the family
     */
    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    /**
     * Returns the font size in points.
     *
     * @return the size
     */
    public double getFontSize() {
        return fontSize;
    }

    /**
     * Sets the font size in points.
     *
     * @param fontSize the size
     */
    public void setFontSize(double fontSize) {
        this.fontSize = fontSize;
    }

    /**
     * Returns whether the run is bold.
     *
     * @return true if bold
     */
    public boolean isBold() {
        return bold;
    }

    /**
     * Sets the bold flag.
     *
     * @param bold true for bold
     */
    public void setBold(boolean bold) {
        this.bold = bold;
    }

    /**
     * Returns whether the run is italic.
     *
     * @return true if italic
     */
    public boolean isItalic() {
        return italic;
    }

    /**
     * Sets the italic flag.
     *
     * @param italic true for italic
     */
    public void setItalic(boolean italic) {
        this.italic = italic;
    }

    /**
     * Returns whether the run is underlined.
     *
     * @return true if underlined
     */
    public boolean isUnderline() {
        return underline;
    }

    /**
     * Sets the underline flag.
     *
     * @param underline true for underline
     */
    public void setUnderline(boolean underline) {
        this.underline = underline;
    }

    /**
     * Returns whether the run is struck through.
     *
     * @return true if struck through
     */
    public boolean isStrikethrough() {
        return strikethrough;
    }

    /**
     * Sets the strikethrough flag.
     *
     * @param strikethrough true for strikethrough
     */
    public void setStrikethrough(boolean strikethrough) {
        this.strikethrough = strikethrough;
    }

    /**
     * Returns the foreground color as packed 0xAARRGGBB (0 = unset).
     *
     * @return the color
     */
    public int getColor() {
        return color;
    }

    /**
     * Sets the foreground color as packed 0xAARRGGBB.
     *
     * @param color the color
     */
    public void setColor(int color) {
        this.color = color;
    }

    /**
     * Returns the background color as packed 0xAARRGGBB (0 = unset).
     *
     * @return the background color
     */
    public int getBackground() {
        return background;
    }

    /**
     * Sets the background color as packed 0xAARRGGBB.
     *
     * @param background the background color
     */
    public void setBackground(int background) {
        this.background = background;
    }

    /**
     * Returns the vertical alignment.
     *
     * @return the vertical alignment
     */
    public VertAlign getVertAlign() {
        return vertAlign;
    }

    /**
     * Sets the vertical alignment.
     *
     * @param vertAlign the vertical alignment
     */
    public void setVertAlign(VertAlign vertAlign) {
        this.vertAlign = vertAlign;
    }
}
