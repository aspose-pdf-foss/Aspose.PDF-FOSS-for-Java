package org.aspose.pdf.facades;

import org.aspose.pdf.Color;

/**
 * An RGB color specified with 0&ndash;255 integer components, used by the
 * {@link FormattedText} facade constructors (Aspose {@code FontColor}). It is a
 * {@link Color} so it is accepted anywhere a {@code Color} foreground/background
 * is expected.
 */
public class FontColor extends Color {

    /**
     * Creates a color from 0&ndash;255 red/green/blue components.
     *
     * @param r red component (0&ndash;255)
     * @param g green component (0&ndash;255)
     * @param b blue component (0&ndash;255)
     */
    public FontColor(int r, int g, int b) {
        super(r / 255.0, g / 255.0, b / 255.0);
    }
}
