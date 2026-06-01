package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;
import org.aspose.pdf.text.RichTextFontStyles;
import org.aspose.pdf.text.TextState;

import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Free text annotation (ISO 32000-1:2008, Section 12.5.6.6, /Subtype /FreeText).
 * <p>
 * A free text annotation displays text directly on the page without requiring
 * a pop-up window. The text is always visible and is rendered using a default
 * appearance string.
 * </p>
 */
public class FreeTextAnnotation extends MarkupAnnotation {

    private static final Logger LOG = Logger.getLogger(FreeTextAnnotation.class.getName());

    private DefaultAppearance defaultAppearanceObj;
    private TextState textStyle;

    /**
     * Constructs a free text annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public FreeTextAnnotation(COSDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new free text annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    public FreeTextAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        dict.set(COSName.of("Subtype"), COSName.of("FreeText"));
    }

    /**
     * Constructs a new free text annotation with the given rectangle and default appearance.
     * <p>
     * The default appearance defines the font, font size, and text color used
     * when rendering the annotation text.
     * </p>
     *
     * @param page              the page this annotation belongs to
     * @param rect              the annotation rectangle
     * @param defaultAppearance the default appearance for the annotation text
     */
    public FreeTextAnnotation(Page page, Rectangle rect, DefaultAppearance defaultAppearance) {
        super(page, rect);
        dict.set(COSName.of("Subtype"), COSName.of("FreeText"));
        setDefaultAppearance(defaultAppearance);
    }

    /**
     * Returns the default appearance string (/DA entry).
     *
     * @return the default appearance string, or null if not set
     */
    public String getDefaultAppearance() {
        if (defaultAppearanceObj != null) {
            return defaultAppearanceObj.getText();
        }
        COSBase da = dict.get("DA");
        return (da instanceof COSString) ? ((COSString) da).getString() : null;
    }

    /**
     * Returns the default appearance string (/DA entry).
     * <p>
     * Alias for {@link #getDefaultAppearance()} to maintain compatibility
     * with Aspose.PDF naming conventions.
     * </p>
     *
     * @return the default appearance string, or null if not set
     */
    public String getDefaultAppearanceString() {
        return getDefaultAppearance();
    }

    /**
     * Sets the default appearance string (/DA entry).
     *
     * @param da the default appearance string
     */
    public void setDefaultAppearance(String da) {
        this.defaultAppearanceObj = null;
        if (da != null) {
            dict.set(COSName.of("DA"), new COSString(da.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Returns the default appearance object for this annotation.
     *
     * @return the default appearance object, or null if not set
     */
    public DefaultAppearance getDefaultAppearanceObject() {
        return defaultAppearanceObj;
    }

    /**
     * Sets the default appearance using a {@link DefaultAppearance} object.
     * This also updates the /DA string in the underlying dictionary.
     *
     * @param da the default appearance object
     */
    public void setDefaultAppearance(DefaultAppearance da) {
        this.defaultAppearanceObj = da;
        if (da != null) {
            String daStr = da.getText();
            dict.set(COSName.of("DA"), new COSString(daStr.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Returns the text style applied to this annotation's text.
     *
     * @return the text style, or null if not set
     */
    public TextState getTextStyle() {
        return textStyle;
    }

    /**
     * Sets the text style for this annotation's text.
     *
     * @param textStyle the text style
     */
    public void setTextStyle(TextState textStyle) {
        this.textStyle = textStyle;
    }

    /**
     * Applies rich text font styles to a range of characters in the annotation text.
     * <p>
     * This method modifies the rich text (/RC) entry to apply CSS-style formatting
     * (bold, italic, underline) to the specified character range. The range is
     * zero-based and refers to the plain text content of the annotation.
     * </p>
     *
     * @param startIndex the starting character index (inclusive, zero-based)
     * @param endIndex   the ending character index (inclusive, zero-based)
     * @param styles     a bitmask of {@link RichTextFontStyles} flags
     * @see RichTextFontStyles
     */
    public void setTextStyle(int startIndex, int endIndex, int styles) {
        LOG.fine(() -> "setTextStyle(" + startIndex + ", " + endIndex + ", 0x"
                + Integer.toHexString(styles) + ")");

        String contents = getContents();
        if (contents == null || contents.isEmpty()) {
            return;
        }

        // Build CSS style string from flags
        String cssStyle = buildCssStyle(styles);

        // Build rich text from content with styled range
        StringBuilder richText = new StringBuilder();
        richText.append("<?xml version=\"1.0\"?>");
        richText.append("<body xmlns=\"http://www.w3.org/1999/xhtml\">");

        int safeStart = Math.max(0, startIndex);
        int safeEnd = Math.min(contents.length() - 1, endIndex);

        if (safeStart > 0) {
            richText.append("<span>").append(escapeXml(contents.substring(0, safeStart))).append("</span>");
        }
        if (safeStart <= safeEnd && safeEnd < contents.length()) {
            richText.append("<span style=\"").append(cssStyle).append("\">")
                    .append(escapeXml(contents.substring(safeStart, safeEnd + 1)))
                    .append("</span>");
        }
        if (safeEnd + 1 < contents.length()) {
            richText.append("<span>").append(escapeXml(contents.substring(safeEnd + 1))).append("</span>");
        }

        richText.append("</body>");
        setRichText(richText.toString());
    }

    /**
     * Applies rich text font styles to the entire annotation text, optionally changing
     * font name, size, and color.
     * <p>
     * When {@link RichTextFontStyles#ClearExisting} is included in the styles bitmask,
     * all existing styles are removed before applying the new ones.
     * </p>
     *
     * @param styles   a bitmask of {@link RichTextFontStyles} flags
     * @param fontName the font name to apply (e.g. "Arial", "Helvetica")
     * @param fontSize the font size in points
     * @param color    the text color
     * @see RichTextFontStyles
     */
    public void setTextStyle(int styles, String fontName, double fontSize, Color color) {
        LOG.fine(() -> "setTextStyle(0x" + Integer.toHexString(styles) + ", "
                + fontName + ", " + fontSize + ", " + color + ")");

        // Update the default appearance
        DefaultAppearance da = new DefaultAppearance(
                fontName != null ? fontName : "Helvetica",
                fontSize,
                color != null ? color : Color.BLACK);
        setDefaultAppearance(da);

        // Update the text style
        TextState ts = new TextState();
        ts.setFontName(fontName != null ? fontName : "Helvetica");
        ts.setFontSize(fontSize);
        ts.setForegroundColor(color != null ? color : Color.BLACK);
        if ((styles & RichTextFontStyles.Bold) != 0) {
            ts.setFontStyle(ts.getFontStyle() | 1); // Bold flag
        }
        if ((styles & RichTextFontStyles.Italic) != 0) {
            ts.setFontStyle(ts.getFontStyle() | 2); // Italic flag
        }
        if ((styles & RichTextFontStyles.Underline) != 0) {
            ts.setUnderline(true);
        }
        this.textStyle = ts;

        // Build CSS style from flags (without ClearExisting flag)
        int effectiveStyles = styles & ~RichTextFontStyles.ClearExisting;
        String cssStyle = buildCssStyle(effectiveStyles);

        // Add font-family and font-size to CSS
        StringBuilder fullCss = new StringBuilder();
        if (fontName != null) {
            fullCss.append("font-family:").append(fontName).append(";");
        }
        fullCss.append("font-size:").append(String.valueOf((int) fontSize)).append("pt;");
        if (color != null) {
            fullCss.append("color:rgb(")
                    .append((int) (color.getR() * 255)).append(",")
                    .append((int) (color.getG() * 255)).append(",")
                    .append((int) (color.getB() * 255)).append(");");
        }
        if (!cssStyle.isEmpty()) {
            fullCss.append(cssStyle);
        }

        String contents = getContents();
        if (contents != null && !contents.isEmpty()) {
            StringBuilder richText = new StringBuilder();
            richText.append("<?xml version=\"1.0\"?>");
            richText.append("<body xmlns=\"http://www.w3.org/1999/xhtml\">");
            richText.append("<span style=\"").append(fullCss).append("\">")
                    .append(escapeXml(contents))
                    .append("</span>");
            richText.append("</body>");
            setRichText(richText.toString());
        }
    }

    /**
     * Returns the justification of the text (/Q entry).
     * <p>
     * 0 = left-justified, 1 = centered, 2 = right-justified.
     * </p>
     *
     * @return the justification value, default 0 (left-justified)
     */
    public int getJustification() {
        return dict.getInt("Q", 0);
    }

    /**
     * Builds a CSS style string from a {@link RichTextFontStyles} bitmask.
     *
     * @param styles the bitmask
     * @return the CSS style string
     */
    private static String buildCssStyle(int styles) {
        StringBuilder css = new StringBuilder();
        if ((styles & RichTextFontStyles.Bold) != 0) {
            css.append("font-weight:bold;");
        }
        if ((styles & RichTextFontStyles.Italic) != 0) {
            css.append("font-style:italic;");
        }
        if ((styles & RichTextFontStyles.Underline) != 0) {
            css.append("text-decoration:underline;");
        }
        return css.toString();
    }

    /**
     * Escapes special XML characters in text content.
     *
     * @param text the text to escape
     * @return the escaped text
     */
    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&apos;");
    }

    // ── /IT intent, /Rotate, /CL callout ─────────────────────────────────

    private static final COSName N_IT     = COSName.of("IT");
    private static final COSName N_ROTATE = COSName.of("Rotate");
    private static final COSName N_CL     = COSName.of("CL");

    /**
     * Returns the intent (/IT) of this annotation, or {@link FreeTextIntent#Undefined}
     * if not set. See ISO 32000-1:2008 §12.5.6.6 (FreeText annotations).
     *
     * @return the intent, never null
     */
    public FreeTextIntent getIntent() {
        COSBase it = dict.get(N_IT);
        if (it instanceof COSName) {
            return FreeTextIntent.fromPdfName(((COSName) it).getName());
        }
        return FreeTextIntent.Undefined;
    }

    /**
     * Sets the intent (/IT). Passing {@code null} or {@link FreeTextIntent#Undefined}
     * removes the entry.
     *
     * @param intent the intent, or null/Undefined to remove
     */
    public void setIntent(FreeTextIntent intent) {
        if (intent == null || intent == FreeTextIntent.Undefined) {
            dict.remove(N_IT);
            return;
        }
        String name = intent.toPdfName();
        if (name == null) {
            dict.remove(N_IT);
        } else {
            dict.set(N_IT, COSName.of(name));
        }
    }

    /**
     * Returns the rotation (/Rotate) of the annotation in degrees, default 0.
     * Should be a multiple of 90 per spec.
     *
     * @return the rotation
     */
    public int getRotate() {
        COSBase r = dict.get(N_ROTATE);
        if (r instanceof COSInteger) {
            return ((COSInteger) r).intValue();
        }
        if (r instanceof COSFloat) {
            return (int) ((COSFloat) r).doubleValue();
        }
        return 0;
    }

    /**
     * Sets the rotation (/Rotate). A warning is logged if the value is not a
     * multiple of 90, but the value is still written.
     *
     * @param rotate the rotation in degrees
     */
    public void setRotate(int rotate) {
        if (rotate % 90 != 0) {
            LOG.warning(() -> "FreeTextAnnotation Rotate should be a multiple of 90, got " + rotate);
        }
        dict.set(N_ROTATE, COSInteger.valueOf(rotate));
    }

    /**
     * Returns the callout-line points (/CL), or {@code null} if not set.
     *
     * <p>Per spec the callout line is either 2 points (4 numbers) or 3 points
     * (6 numbers). Each row of the returned array is {@code {x, y}}.</p>
     *
     * @return array of points, or null
     */
    public double[][] getCallout() {
        COSBase cl = dict.get(N_CL);
        if (!(cl instanceof COSArray)) return null;
        COSArray arr = (COSArray) cl;
        int n = arr.size();
        if (n != 4 && n != 6) return null;
        int numPoints = n / 2;
        double[][] result = new double[numPoints][2];
        for (int i = 0; i < numPoints; i++) {
            result[i][0] = arr.getFloat(i * 2, 0);
            result[i][1] = arr.getFloat(i * 2 + 1, 0);
        }
        return result;
    }

    /**
     * Sets the callout-line points (/CL). Pass {@code null} or an empty
     * array to remove the entry. Must be 2 or 3 points; a warning is logged
     * for other sizes and the call is a no-op.
     *
     * @param points array of {x, y} points (length 2 or 3), or null
     */
    public void setCallout(double[][] points) {
        if (points == null || points.length == 0) {
            dict.remove(N_CL);
            return;
        }
        if (points.length != 2 && points.length != 3) {
            LOG.warning(() -> "FreeTextAnnotation callout must be 2 or 3 points, got " + points.length);
            return;
        }
        COSArray arr = new COSArray();
        for (double[] p : points) {
            if (p == null || p.length != 2) {
                LOG.warning("FreeTextAnnotation callout point must be 2 numbers");
                return;
            }
            arr.add(new COSFloat(p[0]));
            arr.add(new COSFloat(p[1]));
        }
        dict.set(N_CL, arr);
    }

    /**
     * Returns the line-ending style at the endpoint of the callout line
     * ({@code /LE}, ISO 32000-1:2008 §12.5.6.6). Only meaningful when the
     * annotation's {@code /IT} is {@code FreeTextCallout}; otherwise the value
     * is read-only metadata that PDF viewers may ignore.
     *
     * <p>Unlike {@link LineAnnotation} (which stores {@code /LE} as a two-entry
     * array for start+end), a FreeText callout has a single endpoint, so
     * {@code /LE} is stored as a bare {@link org.aspose.pdf.engine.cos.COSName}.</p>
     *
     * @return the ending style, or {@link LineEnding#None} if not set or
     *         unrecognised
     */
    public LineEnding getEndingStyle() {
        COSBase le = dict.get("LE");
        if (le instanceof COSName) {
            try {
                return LineEnding.valueOf(((COSName) le).getName());
            } catch (IllegalArgumentException e) {
                return LineEnding.None;
            }
        }
        return LineEnding.None;
    }

    /**
     * Sets the line-ending style at the endpoint of the callout line.
     * Passing {@code null} or {@link LineEnding#None} removes the {@code /LE}
     * entry from the dictionary.
     *
     * @param style the ending style; null/None clears the entry
     */
    public void setEndingStyle(LineEnding style) {
        if (style == null || style == LineEnding.None) {
            dict.remove(COSName.of("LE"));
        } else {
            dict.set(COSName.of("LE"), COSName.of(style.name()));
        }
    }
}
