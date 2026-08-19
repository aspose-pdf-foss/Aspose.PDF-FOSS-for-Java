package org.aspose.pdf.pgm;

/**
 * Kind-specific payload of a TEXT box (IR spec §2.2): baseline and font info
 * for layout recomputation, plus the shown text.
 */
public final class TextBoxData {

    private final double baselineY;
    private final String fontName;
    private final double fontSize;
    private final String text;
    private final int rotation;

    /**
     * Creates text box data for horizontal text.
     *
     * @param baselineY the baseline y in page space
     * @param fontName  the font family/base name
     * @param fontSize  the font size in points
     * @param text      the shown text
     */
    public TextBoxData(double baselineY, String fontName, double fontSize, String text) {
        this(baselineY, fontName, fontSize, text, 0);
    }

    /**
     * Creates text box data.
     *
     * @param baselineY the baseline y in page space
     * @param fontName  the font family/base name
     * @param fontSize  the font size in points
     * @param text      the shown text
     * @param rotation  the text rotation in degrees (0 = horizontal); rotated
     *                  marginal text must not feed column detection
     */
    public TextBoxData(double baselineY, String fontName, double fontSize, String text,
                       int rotation) {
        this.baselineY = baselineY;
        this.fontName = fontName;
        this.fontSize = fontSize;
        this.text = text == null ? "" : text;
        this.rotation = rotation;
    }

    /**
     * Returns the text rotation in degrees (0 = horizontal).
     *
     * @return the rotation
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Returns the baseline y in page space.
     *
     * @return the baseline
     */
    public double getBaselineY() {
        return baselineY;
    }

    /**
     * Returns the font name.
     *
     * @return the font name, or null
     */
    public String getFontName() {
        return fontName;
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
     * Returns the shown text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }
}
