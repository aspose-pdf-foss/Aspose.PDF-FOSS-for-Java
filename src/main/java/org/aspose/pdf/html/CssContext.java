package org.aspose.pdf.html;

import org.aspose.pdf.Color;

import java.util.logging.Logger;

/**
 * Cascading style context for HTML-to-PDF conversion.
 * <p>
 * Text properties (font family, size, color, weight, style) are inheritable
 * and propagate from parent to child elements via {@link #inherit()}.
 * Box properties (margin, background, width, height) are NOT inherited
 * and reset to defaults for each new element.
 * </p>
 */
public class CssContext {

    private static final Logger LOG = Logger.getLogger(CssContext.class.getName());

    private double fontSize = 12;
    private String fontFamily = "Helvetica";
    private boolean bold = false;
    private boolean italic = false;
    private boolean underline = false;
    private Color color = Color.BLACK;
    private Color backgroundColor = null;
    private String textAlign = "left";
    private double lineHeight = 14.4;
    private double marginTop;
    private double marginBottom;
    private double marginLeft;
    private double marginRight;
    private double width;
    private double height;
    private String display;
    private double widthPercent;
    private String cssFloat;
    private String whiteSpace;
    private double borderBottomWidth;
    private Color borderBottomColor;
    private String borderBottomStyle;

    /**
     * Creates a new child context that inherits text properties from this context.
     * <p>
     * Inherited properties: fontSize, fontFamily, bold, italic, color, lineHeight, textAlign.
     * Non-inherited properties (reset to defaults): underline, backgroundColor,
     * margins, width, height.
     * </p>
     *
     * @return a new {@code CssContext} with inherited text properties
     */
    public CssContext inherit() {
        LOG.fine("Inheriting CSS context");
        CssContext c = new CssContext();
        c.fontSize = this.fontSize;
        c.fontFamily = this.fontFamily;
        c.bold = this.bold;
        c.italic = this.italic;
        c.color = this.color;
        c.lineHeight = this.lineHeight;
        c.textAlign = this.textAlign;
        // NOTE: white-space is intentionally NOT inherited here. CSS does inherit
        // it, but our table layout cannot honour "nowrap" on every descendant of a
        // nowrap ancestor without columns overlapping; restrict nowrap to elements
        // a rule (or the HTML nowrap attribute) targets directly.
        return c;
    }

    /**
     * Maps the current font-family, bold, and italic settings to a PDF standard
     * (Type 1) font name.
     * <p>
     * Recognized families:
     * <ul>
     *   <li>Courier / monospace &rarr; Courier variants</li>
     *   <li>Times / serif (but not sans-serif) &rarr; Times-Roman variants</li>
     *   <li>Everything else (Helvetica, Arial, sans-serif, etc.) &rarr; Helvetica variants</li>
     * </ul>
     * </p>
     *
     * @return the PDF base-14 font name
     */
    public String toPdfFontName() {
        String base = fontFamily.toLowerCase();
        if (base.contains("courier") || base.contains("monospace")) {
            if (bold && italic) return "Courier-BoldOblique";
            if (bold) return "Courier-Bold";
            if (italic) return "Courier-Oblique";
            return "Courier";
        }
        if ((base.contains("times") || base.contains("serif")) && !base.contains("sans")) {
            if (bold && italic) return "Times-BoldItalic";
            if (bold) return "Times-Bold";
            if (italic) return "Times-Italic";
            return "Times-Roman";
        }
        if (bold && italic) return "Helvetica-BoldOblique";
        if (bold) return "Helvetica-Bold";
        if (italic) return "Helvetica-Oblique";
        return "Helvetica";
    }

    /**
     * Returns the font size in points.
     *
     * @return the font size
     */
    public double getFontSize() {
        return fontSize;
    }

    /**
     * Sets the font size in points.
     *
     * @param fontSize the font size
     */
    public void setFontSize(double fontSize) {
        this.fontSize = fontSize;
    }

    /**
     * Returns the CSS font-family name.
     *
     * @return the font family
     */
    public String getFontFamily() {
        return fontFamily;
    }

    /**
     * Sets the CSS font-family name.
     *
     * @param fontFamily the font family
     */
    public void setFontFamily(String fontFamily) {
        this.fontFamily = fontFamily;
    }

    /**
     * Returns whether the text is bold.
     *
     * @return {@code true} if bold
     */
    public boolean isBold() {
        return bold;
    }

    /**
     * Sets the bold flag.
     *
     * @param bold {@code true} for bold text
     */
    public void setBold(boolean bold) {
        this.bold = bold;
    }

    /**
     * Returns whether the text is italic.
     *
     * @return {@code true} if italic
     */
    public boolean isItalic() {
        return italic;
    }

    /**
     * Sets the italic flag.
     *
     * @param italic {@code true} for italic text
     */
    public void setItalic(boolean italic) {
        this.italic = italic;
    }

    /**
     * Returns whether the text is underlined.
     *
     * @return {@code true} if underlined
     */
    public boolean isUnderline() {
        return underline;
    }

    /**
     * Sets the underline flag.
     *
     * @param underline {@code true} for underlined text
     */
    public void setUnderline(boolean underline) {
        this.underline = underline;
    }

    /**
     * Returns the text (foreground) color.
     *
     * @return the color, never {@code null}
     */
    public Color getColor() {
        return color;
    }

    /**
     * Sets the text (foreground) color.
     *
     * @param color the color
     */
    public void setColor(Color color) {
        this.color = color;
    }

    /**
     * Returns the background color, or {@code null} if none is set.
     *
     * @return the background color, or {@code null}
     */
    public Color getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Sets the background color.
     *
     * @param backgroundColor the background color, or {@code null} for transparent
     */
    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    /**
     * Returns the text alignment (left, center, right, justify).
     *
     * @return the text alignment
     */
    public String getTextAlign() {
        return textAlign;
    }

    /**
     * Sets the text alignment.
     *
     * @param textAlign one of "left", "center", "right", "justify"
     */
    public void setTextAlign(String textAlign) {
        this.textAlign = textAlign;
    }

    /**
     * Returns the line height in points.
     *
     * @return the line height
     */
    public double getLineHeight() {
        return lineHeight;
    }

    /**
     * Sets the line height in points.
     *
     * @param lineHeight the line height
     */
    public void setLineHeight(double lineHeight) {
        this.lineHeight = lineHeight;
    }

    /**
     * Returns the top margin in points.
     *
     * @return the top margin
     */
    public double getMarginTop() {
        return marginTop;
    }

    /**
     * Sets the top margin in points.
     *
     * @param marginTop the top margin
     */
    public void setMarginTop(double marginTop) {
        this.marginTop = marginTop;
    }

    /**
     * Returns the bottom margin in points.
     *
     * @return the bottom margin
     */
    public double getMarginBottom() {
        return marginBottom;
    }

    /**
     * Sets the bottom margin in points.
     *
     * @param marginBottom the bottom margin
     */
    public void setMarginBottom(double marginBottom) {
        this.marginBottom = marginBottom;
    }

    /**
     * Returns the left margin in points.
     *
     * @return the left margin
     */
    public double getMarginLeft() {
        return marginLeft;
    }

    /**
     * Sets the left margin in points.
     *
     * @param marginLeft the left margin
     */
    public void setMarginLeft(double marginLeft) {
        this.marginLeft = marginLeft;
    }

    /**
     * Returns the right margin in points.
     *
     * @return the right margin
     */
    public double getMarginRight() {
        return marginRight;
    }

    /**
     * Sets the right margin in points.
     *
     * @param marginRight the right margin
     */
    public void setMarginRight(double marginRight) {
        this.marginRight = marginRight;
    }

    /**
     * Returns the explicit width in points, or 0 if not set.
     *
     * @return the width
     */
    public double getWidth() {
        return width;
    }

    /**
     * Sets the explicit width in points.
     *
     * @param width the width
     */
    public void setWidth(double width) {
        this.width = width;
    }

    /**
     * Returns the width as a percentage of the containing block (0 = not a
     * percentage width). Box property, not inherited.
     *
     * @return the width percentage
     */
    public double getWidthPercent() {
        return widthPercent;
    }

    /**
     * Sets the width as a percentage of the containing block.
     *
     * @param pct the width percentage
     */
    public void setWidthPercent(double pct) {
        this.widthPercent = pct;
    }

    /**
     * Returns the CSS {@code float} value ({@code left}/{@code right}), or
     * {@code null}. Box property, not inherited.
     *
     * @return the float value
     */
    public String getCssFloat() {
        return cssFloat;
    }

    /**
     * Sets the CSS {@code float} value.
     *
     * @param f the float value
     */
    public void setCssFloat(String f) {
        this.cssFloat = f;
    }

    /**
     * Returns the CSS {@code white-space} value ({@code nowrap}, {@code normal},
     * …), or {@code null}. Inherited.
     *
     * @return the white-space value
     */
    public String getWhiteSpace() {
        return whiteSpace;
    }

    /**
     * Sets the CSS {@code white-space} value.
     *
     * @param ws the white-space value
     */
    public void setWhiteSpace(String ws) {
        this.whiteSpace = ws;
    }

    /**
     * Returns the explicit height in points, or 0 if not set.
     *
     * @return the height
     */
    public double getHeight() {
        return height;
    }

    /**
     * Sets the explicit height in points.
     *
     * @param height the height
     */
    public void setHeight(double height) {
        this.height = height;
    }

    /**
     * Returns the CSS {@code display} value (e.g. {@code flex}, {@code table},
     * {@code table-cell}, {@code none}), or {@code null} if not set. Not
     * inherited — reset for each element.
     *
     * @return the display value, or {@code null}
     */
    public String getDisplay() {
        return display;
    }

    /**
     * Sets the CSS {@code display} value.
     *
     * @param display the display value, or {@code null}
     */
    public void setDisplay(String display) {
        this.display = display;
    }

    /**
     * Returns the bottom-border thickness in points (0 = none). Box property,
     * not inherited.
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
     * Returns the bottom-border colour, or {@code null}.
     *
     * @return the bottom-border colour
     */
    public Color getBorderBottomColor() {
        return borderBottomColor;
    }

    /**
     * Sets the bottom-border colour.
     *
     * @param c the colour
     */
    public void setBorderBottomColor(Color c) {
        this.borderBottomColor = c;
    }

    /**
     * Returns the bottom-border style ({@code solid}/{@code dotted}/{@code dashed}),
     * or {@code null}.
     *
     * @return the border style
     */
    public String getBorderBottomStyle() {
        return borderBottomStyle;
    }

    /**
     * Sets the bottom-border style.
     *
     * @param style the style keyword
     */
    public void setBorderBottomStyle(String style) {
        this.borderBottomStyle = style;
    }
}
