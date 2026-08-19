package org.aspose.pdf.sdm;

/**
 * Styled text run (IR spec §1.4): the atom of inline formatting.
 */
public final class Run extends SdmInline {

    private String text;
    private TextStyle style;

    /**
     * Creates a run.
     *
     * @param text  the run text
     * @param style the explicit computed style (may be null)
     */
    public Run(String text, TextStyle style) {
        super(SdmNodeType.RUN);
        this.text = text == null ? "" : text;
        this.style = style;
    }

    /**
     * Returns the run text.
     *
     * @return the text
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the run text.
     *
     * @param text the text
     */
    public void setText(String text) {
        this.text = text == null ? "" : text;
    }

    /**
     * Returns the explicit style.
     *
     * @return the style, or null
     */
    public TextStyle getStyle() {
        return style;
    }

    /**
     * Sets the explicit style.
     *
     * @param style the style
     */
    public void setStyle(TextStyle style) {
        this.style = style;
    }
}
