package org.aspose.pdf;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents a color value in one of several color spaces (RGB, Grayscale, CMYK).
 * <p>
 * Color instances are immutable. Use the static factory methods {@link #fromRgb},
 * {@link #fromGray}, and {@link #fromCmyk} to create instances. Common colors
 * are available as static constants.
 * </p>
 */
public class Color {

    private static final Logger LOG = Logger.getLogger(Color.class.getName());

    /**
     * Supported PDF color spaces.
     */
    public enum ColorSpace {
        /** DeviceRGB — three components: red, green, blue. */
        RGB,
        /** DeviceGray — one component: gray level. */
        GRAY,
        /** DeviceCMYK — four components: cyan, magenta, yellow, black. */
        CMYK
    }

    /** Black (gray 0). */
    public static final Color BLACK = fromGray(0);

    /** White (gray 1). */
    public static final Color WHITE = fromGray(1);

    /** Red (RGB 1, 0, 0). */
    public static final Color RED = fromRgb(1, 0, 0);

    /** Green (RGB 0, 1, 0). */
    public static final Color GREEN = fromRgb(0, 1, 0);

    /** Blue (RGB 0, 0, 1). */
    public static final Color BLUE = fromRgb(0, 0, 1);

    /** Yellow (RGB 1, 1, 0). */
    public static final Color YELLOW = fromRgb(1, 1, 0);

    /** Gray (RGB 0.5, 0.5, 0.5). */
    public static final Color GRAY = fromRgb(0.5, 0.5, 0.5);

    /** Blue-violet (RGB 138, 43, 226). */
    public static final Color BLUE_VIOLET = fromArgb(255, 138, 43, 226);

    /** Fully transparent color (alpha = 0). */
    public static final Color TRANSPARENT = fromArgb(0, 0, 0, 0);

    /** Light gray color. */
    public static final Color LIGHT_GRAY = fromRgb(0.75, 0.75, 0.75);

    // ── Aspose-compatible static getters (C# property style) ──

    /** Returns the predefined black color. */
    public static Color getBlack() { return BLACK; }
    /** Returns the predefined white color. */
    public static Color getWhite() { return WHITE; }
    /** Returns the predefined red color. */
    public static Color getRed() { return RED; }
    /** Returns the predefined green color. */
    public static Color getGreen() { return GREEN; }
    /** Returns the predefined blue color. */
    public static Color getBlue() { return BLUE; }
    /** Returns the predefined yellow color. */
    public static Color getYellow() { return YELLOW; }
    /** Returns the predefined gray color. */
    public static Color getGray() { return GRAY; }
    /** Returns the predefined light gray color. */
    public static Color getLightGray() { return LIGHT_GRAY; }
    /** Returns the predefined transparent color. */
    public static Color getTransparent() { return TRANSPARENT; }

    /**
     * The empty color sentinel — Aspose {@code Color.Empty}. Represents "no color
     * set" and is distinct from every real color (including RGB black). Two empty
     * colors are equal to each other only.
     */
    private static final Color EMPTY = createEmpty();

    /**
     * Returns the empty color sentinel (Aspose {@code Color.Empty}). Not equal to
     * any real color; {@code getEmpty().equals(getEmpty())} is {@code true}.
     *
     * @return the shared empty color instance
     */
    public static Color getEmpty() { return EMPTY; }

    private static Color createEmpty() {
        Color c = new Color(ColorSpace.RGB, new double[0]);
        c.empty = true;
        return c;
    }

    private final ColorSpace colorSpace;
    private final double[] components;
    private double alpha = 1.0;
    /** True only for the {@link #EMPTY} sentinel (Aspose {@code Color.Empty}). */
    private boolean empty = false;

    private Color(ColorSpace colorSpace, double[] components) {
        this.colorSpace = colorSpace;
        this.components = components;
    }

    /**
     * Subclass constructor for an RGB color from 0&ndash;1 components (used by
     * {@link org.aspose.pdf.facades.FontColor}).
     *
     * @param r red (0&ndash;1)
     * @param g green (0&ndash;1)
     * @param b blue (0&ndash;1)
     */
    protected Color(double r, double g, double b) {
        this(ColorSpace.RGB, new double[]{r, g, b});
    }

    /**
     * Creates an RGB color.
     *
     * @param r red component (0.0 to 1.0)
     * @param g green component (0.0 to 1.0)
     * @param b blue component (0.0 to 1.0)
     * @return a new Color in the RGB color space
     */
    public static Color fromRgb(double r, double g, double b) {
        LOG.fine(() -> "Color.fromRgb(" + r + ", " + g + ", " + b + ")");
        return new Color(ColorSpace.RGB, new double[]{r, g, b});
    }

    /**
     * Creates an RGB color from 0&ndash;255 integer components (the form used
     * when translating a {@code java.awt.Color} / {@code System.Drawing.Color}).
     * Named distinctly from {@link #fromRgb(double, double, double)} to avoid the
     * overload trap where {@code fromRgb(1, 0, 0)} (integer literals) would bind
     * to the byte form instead of the 0&ndash;1 float form.
     *
     * @param r red component (0&ndash;255)
     * @param g green component (0&ndash;255)
     * @param b blue component (0&ndash;255)
     * @return a new Color in the RGB color space
     */
    public static Color fromRgbBytes(int r, int g, int b) {
        return new Color(ColorSpace.RGB, new double[]{r / 255.0, g / 255.0, b / 255.0});
    }

    /**
     * Returns this color's RGB components as 0&ndash;255 integers (Aspose
     * {@code Color.ToRgb()}). Non-RGB colors are first converted to RGB.
     *
     * @return an {@code int[]}{r, g, b} in 0&ndash;255
     */
    public int[] toRgb() {
        return new int[]{
                (int) Math.round(getR() * 255.0),
                (int) Math.round(getG() * 255.0),
                (int) Math.round(getB() * 255.0)};
    }

    /**
     * Creates a grayscale color.
     *
     * @param gray the gray level (0.0 = black, 1.0 = white)
     * @return a new Color in the GRAY color space
     */
    public static Color fromGray(double gray) {
        LOG.fine(() -> "Color.fromGray(" + gray + ")");
        return new Color(ColorSpace.GRAY, new double[]{gray});
    }

    /**
     * Creates a CMYK color.
     *
     * @param c cyan component (0.0 to 1.0)
     * @param m magenta component (0.0 to 1.0)
     * @param y yellow component (0.0 to 1.0)
     * @param k black (key) component (0.0 to 1.0)
     * @return a new Color in the CMYK color space
     */
    public static Color fromCmyk(double c, double m, double y, double k) {
        LOG.fine(() -> "Color.fromCmyk(" + c + ", " + m + ", " + y + ", " + k + ")");
        return new Color(ColorSpace.CMYK, new double[]{c, m, y, k});
    }

    /**
     * Creates an RGB color from integer component values in the 0-255 range.
     * <p>
     * This factory method provides .NET {@code System.Drawing.Color.FromArgb()} compatibility.
     * </p>
     *
     * @param red   red component (0 to 255)
     * @param green green component (0 to 255)
     * @param blue  blue component (0 to 255)
     * @return a new Color in the RGB color space
     */
    public static Color fromArgb(int red, int green, int blue) {
        return fromRgb(red / 255.0, green / 255.0, blue / 255.0);
    }

    /**
     * Creates an RGB color with alpha from integer component values in the 0-255 range.
     * <p>
     * This factory method provides .NET {@code System.Drawing.Color.FromArgb()} compatibility.
     * </p>
     *
     * @param alpha alpha component (0 = fully transparent, 255 = fully opaque)
     * @param red   red component (0 to 255)
     * @param green green component (0 to 255)
     * @param blue  blue component (0 to 255)
     * @return a new Color in the RGB color space with the specified alpha
     */
    public static Color fromArgb(int alpha, int red, int green, int blue) {
        Color c = fromRgb(red / 255.0, green / 255.0, blue / 255.0);
        c.alpha = alpha / 255.0;
        return c;
    }

    /**
     * Parses an HTML hex color string into a Color.
     * <p>
     * Supported formats: {@code #RRGGBB} and {@code #AARRGGBB}.
     * The leading {@code #} is optional.
     * </p>
     *
     * @param htmlColor the HTML color string (e.g., {@code "#FF88FD"} or {@code "FF88FD"})
     * @return the parsed Color
     * @throws IllegalArgumentException if the string format is invalid
     */
    public static Color fromHtml(String htmlColor) {
        if (htmlColor == null || htmlColor.isEmpty()) {
            throw new IllegalArgumentException("htmlColor must not be null or empty");
        }
        String hex = htmlColor.startsWith("#") ? htmlColor.substring(1) : htmlColor;
        if (hex.length() == 6) {
            int r = Integer.parseInt(hex.substring(0, 2), 16);
            int g = Integer.parseInt(hex.substring(2, 4), 16);
            int b = Integer.parseInt(hex.substring(4, 6), 16);
            return fromArgb(r, g, b);
        } else if (hex.length() == 8) {
            int a = Integer.parseInt(hex.substring(0, 2), 16);
            int r = Integer.parseInt(hex.substring(2, 4), 16);
            int g = Integer.parseInt(hex.substring(4, 6), 16);
            int b = Integer.parseInt(hex.substring(6, 8), 16);
            return fromArgb(a, r, g, b);
        } else {
            throw new IllegalArgumentException("Invalid HTML color format: " + htmlColor
                    + ". Expected #RRGGBB or #AARRGGBB.");
        }
    }

    /**
     * Returns the alpha (opacity) component of this color.
     * <p>
     * 0.0 is fully transparent, 1.0 is fully opaque.
     * </p>
     *
     * @return the alpha value (0.0 to 1.0)
     */
    public double getAlpha() {
        return alpha;
    }

    /**
     * Returns the color space of this color.
     *
     * @return the color space
     */
    public ColorSpace getColorSpace() {
        return colorSpace;
    }

    /**
     * Returns a copy of the component values.
     * <p>
     * The number of components depends on the color space:
     * RGB = 3, GRAY = 1, CMYK = 4.
     * </p>
     *
     * @return a cloned array of component values
     */
    public double[] getComponents() {
        return components.clone();
    }

    /**
     * Returns the red component (0..1) for RGB colors, or an approximation for others.
     *
     * @return the red component
     */
    public double getR() {
        switch (colorSpace) {
            case RGB: return components[0];
            case GRAY: return components[0];
            case CMYK: return (1 - components[0]) * (1 - components[3]);
            default: return 0;
        }
    }

    /**
     * Returns the green component (0..1) for RGB colors, or an approximation for others.
     *
     * @return the green component
     */
    public double getG() {
        switch (colorSpace) {
            case RGB: return components[1];
            case GRAY: return components[0];
            case CMYK: return (1 - components[1]) * (1 - components[3]);
            default: return 0;
        }
    }

    /**
     * Returns the blue component (0..1) for RGB colors, or an approximation for others.
     *
     * @return the blue component
     */
    public double getB() {
        switch (colorSpace) {
            case RGB: return components[2];
            case GRAY: return components[0];
            case CMYK: return (1 - components[2]) * (1 - components[3]);
            default: return 0;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color)) return false;
        Color c = (Color) o;
        // The empty sentinel (Aspose Color.Empty) equals only another empty and
        // is distinct from every real color, including RGB black.
        if (empty || c.empty) return empty == c.empty;
        return colorSpace == c.colorSpace && Arrays.equals(components, c.components);
    }

    @Override
    public int hashCode() {
        return Objects.hash(colorSpace, Arrays.hashCode(components));
    }

    @Override
    public String toString() {
        return "Color{" + colorSpace + ", " + Arrays.toString(components) + "}";
    }
}
