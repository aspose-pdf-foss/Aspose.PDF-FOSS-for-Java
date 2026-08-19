package org.aspose.pdf.html;

import org.aspose.pdf.Color;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Parses inline CSS style strings and applies them to a {@link CssContext}.
 * <p>
 * Supports a practical subset of CSS properties relevant to PDF generation:
 * font-size, font-family, font-weight, font-style, color, background-color,
 * background, text-align, text-decoration, line-height, margin (shorthand
 * and individual sides), width, and height.
 * </p>
 * <p>
 * No third-party dependencies are used. Color parsing handles #hex (3/6 digit),
 * rgb() functional notation, and common named colors.
 * </p>
 */
public final class CssStyleParser {

    private static final Logger LOG = Logger.getLogger(CssStyleParser.class.getName());

    /** Named CSS colors mapped to their RGB hex values. */
    private static final Map<String, int[]> NAMED_COLORS = new HashMap<>();

    static {
        NAMED_COLORS.put("black",       new int[]{0, 0, 0});
        NAMED_COLORS.put("white",       new int[]{255, 255, 255});
        NAMED_COLORS.put("red",         new int[]{255, 0, 0});
        NAMED_COLORS.put("green",       new int[]{0, 128, 0});
        NAMED_COLORS.put("blue",        new int[]{0, 0, 255});
        NAMED_COLORS.put("gray",        new int[]{128, 128, 128});
        NAMED_COLORS.put("grey",        new int[]{128, 128, 128});
        NAMED_COLORS.put("yellow",      new int[]{255, 255, 0});
        NAMED_COLORS.put("orange",      new int[]{255, 165, 0});
        NAMED_COLORS.put("purple",      new int[]{128, 0, 128});
        NAMED_COLORS.put("navy",        new int[]{0, 0, 128});
        NAMED_COLORS.put("silver",      new int[]{192, 192, 192});
        NAMED_COLORS.put("transparent", null);
    }

    private CssStyleParser() {
        // utility class
    }

    /**
     * Parses an inline CSS style string and applies recognized properties
     * to the given context.
     * <p>
     * Example: {@code "font-size:14px; color:#ff0000; margin:10px 5px"}
     * </p>
     *
     * @param ctx      the context to update
     * @param styleStr the CSS style attribute value; may be {@code null} or empty
     */
    public static void applyInlineStyle(CssContext ctx, String styleStr) {
        if (styleStr == null || styleStr.isEmpty()) {
            return;
        }
        LOG.fine(() -> "Applying inline style: " + styleStr);

        String[] declarations = styleStr.split(";");
        for (String rawDecl : declarations) {
            String decl = rawDecl.trim();
            if (decl.isEmpty()) {
                continue;
            }
            int colon = decl.indexOf(':');
            if (colon < 0) {
                LOG.fine(() -> "Skipping malformed declaration (no colon): " + decl);
                continue;
            }
            String property = decl.substring(0, colon).trim().toLowerCase();
            String value = decl.substring(colon + 1).trim();
            applyProperty(ctx, property, value);
        }
    }

    /**
     * Applies a single CSS property/value pair to the context.
     */
    private static void applyProperty(CssContext ctx, String property, String value) {
        switch (property) {
            case "font-size":
                ctx.setFontSize(parseDimension(value, ctx.getFontSize()));
                break;
            case "font-family":
                ctx.setFontFamily(parseFont(value));
                break;
            case "font-weight":
                ctx.setBold(isBold(value));
                break;
            case "font-style":
                ctx.setItalic("italic".equalsIgnoreCase(value) || "oblique".equalsIgnoreCase(value));
                break;
            case "color":
                Color fg = parseColor(value);
                if (fg != null) {
                    ctx.setColor(fg);
                }
                break;
            case "background-color":
            case "background":
                ctx.setBackgroundColor(parseColor(value));
                break;
            case "text-align":
                ctx.setTextAlign(value.toLowerCase());
                break;
            case "text-decoration":
                ctx.setUnderline(value.toLowerCase().contains("underline"));
                break;
            case "line-height":
                ctx.setLineHeight(parseDimension(value, ctx.getFontSize()));
                break;
            case "margin":
                parseMarginShorthand(ctx, value);
                break;
            case "margin-top":
                ctx.setMarginTop(parseDimension(value, 0));
                break;
            case "margin-bottom":
                ctx.setMarginBottom(parseDimension(value, 0));
                break;
            case "margin-left":
                ctx.setMarginLeft(parseDimension(value, 0));
                break;
            case "margin-right":
                ctx.setMarginRight(parseDimension(value, 0));
                break;
            case "width":
                ctx.setWidth(parseDimension(value, 0));
                if (value != null && value.trim().endsWith("%")) {
                    try {
                        ctx.setWidthPercent(Double.parseDouble(
                                value.trim().substring(0, value.trim().length() - 1).trim()));
                    } catch (NumberFormatException ignored) { }
                }
                break;
            case "float":
                ctx.setCssFloat(value.trim().toLowerCase());
                break;
            case "white-space":
                ctx.setWhiteSpace(value.trim().toLowerCase());
                break;
            case "height":
                ctx.setHeight(parseDimension(value, 0));
                break;
            case "display":
                ctx.setDisplay(value.trim().toLowerCase());
                break;
            case "border-bottom":
                // Shorthand: <width> <style> <color> in any order (e.g.
                // "1px dotted #6C8CD9", "0.5pt solid rgba(0,0,0,1)").
                parseBorderBottomShorthand(ctx, value);
                break;
            case "border-bottom-width":
                ctx.setBorderBottomWidth(parseDimension(value, 0));
                break;
            case "border-bottom-style":
                ctx.setBorderBottomStyle(value.trim().toLowerCase());
                break;
            case "border-bottom-color":
                Color bbc = parseColor(value);
                if (bbc != null) {
                    ctx.setBorderBottomColor(bbc);
                }
                break;
            default:
                LOG.fine(() -> "Ignoring unsupported CSS property: " + property);
                break;
        }
    }

    /** CSS border-style keywords. */
    private static final java.util.Set<String> BORDER_STYLES = new java.util.HashSet<>(
            java.util.Arrays.asList("none", "hidden", "solid", "dotted", "dashed",
                    "double", "groove", "ridge", "inset", "outset"));

    /**
     * Parses a {@code border-bottom} shorthand ({@code <width> <style> <color>}
     * in any order) into the context's bottom-border fields. Tokens are split on
     * whitespace but parentheses are respected so {@code rgba(0, 0, 0, 1)} stays
     * one token.
     */
    private static void parseBorderBottomShorthand(CssContext ctx, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        for (String tok : splitRespectingParens(value.trim())) {
            String low = tok.toLowerCase(java.util.Locale.ROOT);
            if (BORDER_STYLES.contains(low)) {
                ctx.setBorderBottomStyle(low);
                continue;
            }
            Color c = parseColor(tok);
            if (c != null) {
                ctx.setBorderBottomColor(c);
                continue;
            }
            // Anything else is treated as the width dimension.
            ctx.setBorderBottomWidth(parseDimension(tok, 0));
        }
    }

    /** Splits on whitespace while keeping parenthesised groups (e.g. rgba(...)) intact. */
    private static java.util.List<String> splitRespectingParens(String s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth = Math.max(0, depth - 1);
            }
            if (Character.isWhitespace(ch) && depth == 0) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * Parses a CSS dimension value and converts it to PDF points (1pt = 1/72 in).
     * <p>
     * Supported units:
     * <ul>
     *   <li>{@code px} — pixels, treated as 0.75pt (96 dpi screen assumption)</li>
     *   <li>{@code pt} — points (1:1)</li>
     *   <li>{@code em} — relative to {@code base} font size</li>
     *   <li>{@code %} — percentage of {@code base}</li>
     *   <li>{@code cm} — centimeters (1cm = 28.3465pt)</li>
     *   <li>{@code mm} — millimeters (1mm = 2.83465pt)</li>
     *   <li>{@code in} — inches (1in = 72pt)</li>
     * </ul>
     * A bare number (no unit) is treated as points.
     * </p>
     *
     * @param value the CSS dimension string, e.g. "14px", "1.5em", "50%"
     * @param base  the base value for relative units (em, %)
     * @return the value in points
     */
    public static double parseDimension(String value, double base) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        String v = value.trim().toLowerCase();

        if ("0".equals(v)) {
            return 0;
        }

        try {
            if (v.endsWith("px")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim());
            }
            if (v.endsWith("pt")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim());
            }
            if (v.endsWith("em")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim()) * base;
            }
            if (v.endsWith("%")) {
                return Double.parseDouble(v.substring(0, v.length() - 1).trim()) / 100.0 * base;
            }
            if (v.endsWith("cm")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 28.3465;
            }
            if (v.endsWith("mm")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 2.83465;
            }
            if (v.endsWith("in")) {
                return Double.parseDouble(v.substring(0, v.length() - 2).trim()) * 72.0;
            }
            // bare number — treat as points
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            LOG.fine(() -> "Cannot parse dimension: " + v);
            return base;
        }
    }

    /**
     * Parses a CSS color value and returns a {@link Color}.
     * <p>
     * Supported formats:
     * <ul>
     *   <li>{@code #RGB} — 3-digit hex (expanded to 6-digit)</li>
     *   <li>{@code #RRGGBB} — 6-digit hex</li>
     *   <li>{@code rgb(R, G, B)} — functional notation with 0-255 integer components</li>
     *   <li>Named colors: black, white, red, green, blue, gray/grey, yellow,
     *       orange, purple, navy, silver, transparent</li>
     * </ul>
     * </p>
     *
     * @param value the CSS color string
     * @return the parsed {@link Color}, or {@code null} for "transparent" or unparseable values
     */
    public static Color parseColor(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String v = value.trim().toLowerCase();

        // Named color
        if (NAMED_COLORS.containsKey(v)) {
            int[] rgb = NAMED_COLORS.get(v);
            if (rgb == null) {
                // transparent
                return null;
            }
            return Color.fromRgb(rgb[0] / 255.0, rgb[1] / 255.0, rgb[2] / 255.0);
        }

        // Hex color
        if (v.startsWith("#")) {
            return parseHexColor(v.substring(1));
        }

        // rgb() / rgba() functional
        if (v.startsWith("rgb(") && v.endsWith(")")) {
            return parseRgbFunction(v.substring(4, v.length() - 1));
        }
        if (v.startsWith("rgba(") && v.endsWith(")")) {
            return parseRgbFunction(v.substring(5, v.length() - 1));
        }

        LOG.fine(() -> "Cannot parse color: " + v);
        return null;
    }

    /**
     * Parses a hex color string (without the leading '#').
     */
    private static Color parseHexColor(String hex) {
        try {
            if (hex.length() == 3) {
                // Expand #RGB to #RRGGBB
                int r = Integer.parseInt(hex.substring(0, 1), 16) * 17;
                int g = Integer.parseInt(hex.substring(1, 2), 16) * 17;
                int b = Integer.parseInt(hex.substring(2, 3), 16) * 17;
                return Color.fromRgb(r / 255.0, g / 255.0, b / 255.0);
            }
            if (hex.length() == 6) {
                int r = Integer.parseInt(hex.substring(0, 2), 16);
                int g = Integer.parseInt(hex.substring(2, 4), 16);
                int b = Integer.parseInt(hex.substring(4, 6), 16);
                return Color.fromRgb(r / 255.0, g / 255.0, b / 255.0);
            }
        } catch (NumberFormatException e) {
            LOG.fine(() -> "Invalid hex color: #" + hex);
        }
        return null;
    }

    /**
     * Parses the contents of an rgb() function, e.g. "255, 0, 128".
     */
    private static Color parseRgbFunction(String inner) {
        try {
            String[] parts = inner.split(",");
            // rgb() has 3 components; rgba() adds an alpha we honour only for full
            // transparency (alpha 0 → no colour), otherwise treat as opaque.
            if (parts.length != 3 && parts.length != 4) {
                return null;
            }
            if (parts.length == 4) {
                double a = Double.parseDouble(parts[3].trim());
                if (a <= 0.0) {
                    return null;
                }
            }
            int r = Integer.parseInt(parts[0].trim());
            int g = Integer.parseInt(parts[1].trim());
            int b = Integer.parseInt(parts[2].trim());
            return Color.fromRgb(
                    Math.max(0, Math.min(255, r)) / 255.0,
                    Math.max(0, Math.min(255, g)) / 255.0,
                    Math.max(0, Math.min(255, b)) / 255.0
            );
        } catch (NumberFormatException e) {
            LOG.fine(() -> "Invalid rgb() value: " + inner);
            return null;
        }
    }

    /**
     * Extracts the first font name from a CSS font-family value.
     * <p>
     * Strips quotes and returns only the first font in a comma-separated list.
     * For example, {@code "'Times New Roman', serif"} returns {@code "Times New Roman"}.
     * </p>
     *
     * @param value the CSS font-family string
     * @return the first font name, trimmed and unquoted
     */
    public static String parseFont(String value) {
        if (value == null || value.isEmpty()) {
            return "Helvetica";
        }
        // Take first font from comma-separated list
        String first = value.split(",")[0].trim();
        // Remove surrounding quotes
        if ((first.startsWith("\"") && first.endsWith("\""))
                || (first.startsWith("'") && first.endsWith("'"))) {
            first = first.substring(1, first.length() - 1).trim();
        }
        return first.isEmpty() ? "Helvetica" : first;
    }

    /**
     * Determines whether a CSS font-weight value represents bold.
     * <p>
     * Returns {@code true} for the keyword "bold" or numeric weights &ge; 700.
     * </p>
     *
     * @param value the CSS font-weight value
     * @return {@code true} if the weight is bold
     */
    public static boolean isBold(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        value = value.trim().toLowerCase();
        if ("bold".equals(value) || "bolder".equals(value)) {
            return true;
        }
        try {
            return Integer.parseInt(value) >= 700;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Parses a CSS margin shorthand value and applies it to the context.
     * <p>
     * Supports 1, 2, 3, or 4 values:
     * <ul>
     *   <li>1 value: all four margins</li>
     *   <li>2 values: vertical (top/bottom) and horizontal (left/right)</li>
     *   <li>3 values: top, horizontal (left/right), bottom</li>
     *   <li>4 values: top, right, bottom, left</li>
     * </ul>
     * </p>
     *
     * @param ctx   the context to update
     * @param value the CSS margin shorthand string
     */
    public static void parseMarginShorthand(CssContext ctx, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        String[] parts = value.trim().split("\\s+");
        switch (parts.length) {
            case 1: {
                double v = parseDimension(parts[0], 0);
                ctx.setMarginTop(v);
                ctx.setMarginRight(v);
                ctx.setMarginBottom(v);
                ctx.setMarginLeft(v);
                break;
            }
            case 2: {
                double vert = parseDimension(parts[0], 0);
                double horiz = parseDimension(parts[1], 0);
                ctx.setMarginTop(vert);
                ctx.setMarginBottom(vert);
                ctx.setMarginLeft(horiz);
                ctx.setMarginRight(horiz);
                break;
            }
            case 3: {
                double top = parseDimension(parts[0], 0);
                double horiz = parseDimension(parts[1], 0);
                double bottom = parseDimension(parts[2], 0);
                ctx.setMarginTop(top);
                ctx.setMarginRight(horiz);
                ctx.setMarginBottom(bottom);
                ctx.setMarginLeft(horiz);
                break;
            }
            default: {
                // 4 or more — use first 4
                ctx.setMarginTop(parseDimension(parts[0], 0));
                ctx.setMarginRight(parseDimension(parts[1], 0));
                ctx.setMarginBottom(parseDimension(parts[2], 0));
                ctx.setMarginLeft(parseDimension(parts[3], 0));
                break;
            }
        }
    }
}
