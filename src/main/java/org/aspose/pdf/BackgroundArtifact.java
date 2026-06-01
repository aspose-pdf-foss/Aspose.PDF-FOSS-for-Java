package org.aspose.pdf;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Represents a background artifact — a convenience subclass for creating
 * artifacts that serve as page backgrounds (ISO 32000-1:2008, §14.8.2.2).
 * <p>
 * Background artifacts can include solid colors or images placed behind
 * the primary page content.
 * </p>
 */
public class BackgroundArtifact extends Artifact {

    private static final Logger LOG = Logger.getLogger(BackgroundArtifact.class.getName());

    private Color backgroundColor;
    private InputStream backgroundImage;

    /**
     * Creates a new background artifact with type Background and subtype Background.
     */
    public BackgroundArtifact() {
        super(ArtifactType.Background, ArtifactSubtype.Background);
        setBackground(true);
        LOG.fine("BackgroundArtifact created");
    }

    /**
     * Convenience constructor: a solid-colour page background.
     *
     * @param fillColor the colour to fill the page with
     */
    public BackgroundArtifact(Color fillColor) {
        this();
        setBackgroundColor(fillColor);
    }

    /**
     * Returns the background color.
     *
     * @return the background color, or {@code null} if not set
     */
    public Color getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Sets the background color.
     *
     * @param color the background color
     */
    public void setBackgroundColor(Color color) {
        this.backgroundColor = color;
    }

    /**
     * Returns the background image input stream.
     *
     * @return the background image stream, or {@code null} if not set
     */
    public InputStream getBackgroundImage() {
        return backgroundImage;
    }

    /**
     * Sets the background image from an input stream.
     *
     * @param imageStream the image input stream
     */
    public void setBackgroundImage(InputStream imageStream) {
        this.backgroundImage = imageStream;
    }

    /**
     * Emits a {@code /Artifact BMC q <color> rg <pageRect> re f Q EMC}
     * sequence covering the page's media box. Returns {@code null} when no
     * background colour has been set (the parent {@link Page} then falls back
     * to emitting an empty BMC/EMC pair for back-compat).
     */
    @Override
    byte[] synthesizeContentBytes(Page page) {
        if (backgroundColor == null) return null;
        Rectangle box = page.getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 612, 792);
        double[] rgb = ArtifactSupport.toRgb(backgroundColor);
        StringBuilder cs = new StringBuilder(128);
        cs.append("/Artifact BMC\n");
        cs.append("q\n");
        cs.append(fmt(rgb[0])).append(' ')
          .append(fmt(rgb[1])).append(' ')
          .append(fmt(rgb[2])).append(" rg\n");
        cs.append(fmt(box.getLLX())).append(' ')
          .append(fmt(box.getLLY())).append(' ')
          .append(fmt(box.getWidth())).append(' ')
          .append(fmt(box.getHeight())).append(" re f\n");
        cs.append("Q\n");
        cs.append("EMC\n");
        return cs.toString().getBytes(StandardCharsets.ISO_8859_1);
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
