package org.aspose.pdf;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Represents a watermark artifact — a convenience subclass for creating
 * pagination artifacts with the Watermark subtype (ISO 32000-1:2008, §14.8.2.2).
 * <p>
 * Watermarks are typically semi-transparent text or images placed over or behind
 * page content to indicate status (e.g., "DRAFT", "CONFIDENTIAL") or ownership.
 * </p>
 */
public class WatermarkArtifact extends Artifact {

    private static final Logger LOG = Logger.getLogger(WatermarkArtifact.class.getName());

    private String fontName = "Helvetica-Bold";
    private double fontSize = 48;
    private Color color = Color.fromRgb(0.7, 0.0, 0.0);

    /**
     * Creates a new watermark artifact with type Pagination and subtype Watermark.
     */
    public WatermarkArtifact() {
        super(ArtifactType.Pagination, ArtifactSubtype.Watermark);
        LOG.fine("WatermarkArtifact created");
    }

    /**
     * Creates a new watermark artifact with the given text content.
     *
     * @param text the watermark text
     */
    public WatermarkArtifact(String text) {
        this();
        setText(text);
    }

    /**
     * Sets the base font name and size used to render the watermark text.
     *
     * @param fontName one of the Standard-14 base font names (e.g. {@code "Helvetica-Bold"})
     * @param fontSize the font size in points
     */
    public void setFont(String fontName, double fontSize) {
        if (fontName != null) this.fontName = fontName;
        if (fontSize > 0) this.fontSize = fontSize;
    }

    /** Returns the font name used by the watermark. */
    public String getFontName() {
        return fontName;
    }

    /** Returns the font size (in points) used by the watermark. */
    public double getFontSize() {
        return fontSize;
    }

    /**
     * Sets the watermark text colour.
     *
     * @param color the non-stroking colour to use for the text
     */
    public void setColor(Color color) {
        if (color != null) this.color = color;
    }

    /** Returns the watermark text colour. */
    public Color getColor() {
        return color;
    }

    /**
     * Sets the watermark text content.
     *
     * @param text the watermark text
     */
    public void setWatermarkText(String text) {
        setText(text);
    }

    /**
     * Returns the watermark text content.
     *
     * @return the watermark text
     */
    public String getWatermarkText() {
        return getText();
    }

    /**
     * Sets the watermark opacity (0.0 = fully transparent, 1.0 = fully opaque).
     *
     * @param opacity the opacity value
     * @throws IllegalArgumentException if opacity is not between 0.0 and 1.0
     */
    public void setWatermarkOpacity(double opacity) {
        if (opacity < 0.0 || opacity > 1.0) {
            throw new IllegalArgumentException("Opacity must be between 0.0 and 1.0, got: " + opacity);
        }
        setOpacity(opacity);
    }

    /**
     * Sets the watermark rotation angle in degrees.
     *
     * @param degrees the rotation angle
     */
    public void setWatermarkRotation(double degrees) {
        setRotation(degrees);
    }

    /**
     * Synthesises a {@code /Artifact <</Subtype /Watermark>> BDC ... EMC}
     * marked-content sequence for the watermark text, registering the font
     * and (if opacity &lt; 1) an {@code /ExtGState} entry on the page's
     * {@code /Resources}. Returns {@code null} when the watermark has no text.
     */
    @Override
    byte[] synthesizeContentBytes(Page page) {
        String text = getText();
        if (text == null || text.isEmpty()) return null;
        Rectangle box = page.getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 612, 792);
        double cx = (box.getLLX() + box.getURX()) / 2.0;
        double cy = (box.getLLY() + box.getURY()) / 2.0;
        double rad = Math.toRadians(getRotation());
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);

        String fontRes = ArtifactSupport.ensureStandardFont(page, fontName);
        String gsRes = (getOpacity() > 0 && getOpacity() < 1)
                ? ArtifactSupport.ensureOpacityExtGState(page, getOpacity())
                : null;

        // Approximate horizontal centring: ~half of (chars * fontSize * 0.5).
        double textHalfWidth = text.length() * fontSize * 0.5 / 2.0;

        StringBuilder cs = new StringBuilder(256);
        cs.append("/Artifact <</Type /Pagination /Subtype /Watermark>> BDC\n");
        cs.append("q\n");
        if (gsRes != null) {
            cs.append('/').append(gsRes).append(" gs\n");
        }
        appendNonStrokingColor(cs, color);
        cs.append(String.format(Locale.ROOT, "1 0 0 1 %s %s cm\n",
                fmt(cx), fmt(cy)));
        cs.append(String.format(Locale.ROOT, "%s %s %s %s 0 0 cm\n",
                fmt(cos), fmt(sin), fmt(-sin), fmt(cos)));
        cs.append("BT\n");
        cs.append('/').append(fontRes).append(' ').append(fmt(fontSize)).append(" Tf\n");
        cs.append(fmt(-textHalfWidth)).append(' ').append(fmt(-fontSize / 3.0)).append(" Td\n");
        cs.append('(').append(ArtifactSupport.escapeLiteral(text)).append(") Tj\n");
        cs.append("ET\n");
        cs.append("Q\n");
        cs.append("EMC\n");
        return cs.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void appendNonStrokingColor(StringBuilder cs, Color c) {
        double[] rgb = ArtifactSupport.toRgb(c);
        cs.append(fmt(rgb[0])).append(' ').append(fmt(rgb[1])).append(' ').append(fmt(rgb[2])).append(" rg\n");
    }

    private static String fmt(double v) {
        if (v == (long) v) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        int dot = s.indexOf('.');
        if (dot < 0) return s;
        int end = s.length();
        while (end > dot + 2 && s.charAt(end - 1) == '0') end--;
        return s.substring(0, end);
    }
}
