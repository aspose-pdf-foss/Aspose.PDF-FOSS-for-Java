package org.aspose.pdf.engine.layout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds a PDF content stream as a sequence of bytes.
 * <p>
 * Provides methods corresponding to PDF operators (ISO 32000-1:2008, Section 8 and 9)
 * for text, graphics state, path construction, and XObject rendering. Also tracks
 * font and image resource registrations for building the associated /Resources dictionary.
 * </p>
 * <p>
 * Numeric values are formatted using {@link Locale#US} to ensure decimal points
 * (not commas) in the output. Coordinate values use 2 decimal places; small values
 * (such as color components) use 4 decimal places; font sizes use 1 decimal place.
 * </p>
 */
public class ContentStreamBuilder {

    private static final Logger LOG = Logger.getLogger(ContentStreamBuilder.class.getName());

    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);

    /**
     * Maps a registered base font name (e.g. "Helvetica") to its resource name (e.g. "F1").
     */
    private final Map<String, String> fontResources = new LinkedHashMap<>();

    /**
     * Maps a registered image key to its resource name (e.g. "Im1").
     */
    private final Map<String, String> imageResources = new LinkedHashMap<>();

    /**
     * Resource name (e.g. "F2") → the embedded {@code /Type0} font dictionary registered via
     * {@link #registerEmbeddedFont}. The caller's font-merge step writes these dicts onto the page's
     * {@code /Resources/Font} instead of a standard-14 {@code /Type1} dict.
     */
    private final Map<String, org.aspose.pdf.engine.pdfobjects.PdfDictionary> embeddedFontDicts
            = new LinkedHashMap<>();

    /**
     * Resource name (e.g. "Im1") → the Image XObject stream registered via
     * {@link #registerImage(String, org.aspose.pdf.engine.pdfobjects.PdfStream)}. The caller's
     * resource-merge step attaches these onto the page's {@code /Resources/XObject}.
     */
    private final Map<String, org.aspose.pdf.engine.pdfobjects.PdfStream> imageXObjectDicts
            = new LinkedHashMap<>();

    /**
     * Resource name (e.g. "GS1") → the {@code /ExtGState} dictionary registered via
     * {@link #registerAlphaGState(double)}. The caller's resource-merge step attaches
     * these onto the page's {@code /Resources/ExtGState} so soft alpha (watermark
     * backdrops) actually applies.
     */
    private final Map<String, org.aspose.pdf.engine.pdfobjects.PdfDictionary> extGStateDicts
            = new LinkedHashMap<>();
    /** Alpha value → its already-registered ExtGState resource name (dedup). */
    private final Map<Double, String> alphaGStates = new LinkedHashMap<>();

    private int fontCounter = 0;
    private int imageCounter = 0;
    private int gsCounter = 0;

    /**
     * Resource names of fonts that use Identity-H 2-byte CID encoding
     * (Type0 with a TrueType descendant). For these, {@link #showText} emits
     * each character as a big-endian {@code <gid>} hex string instead of a
     * WinAnsi byte. Mapped to the {@link org.aspose.pdf.engine.font.ttf.TrueTypeReader}
     * that provides the Unicode→glyph_id translation.
     */
    private final Map<String, org.aspose.pdf.engine.font.ttf.TrueTypeReader> type0Readers
            = new LinkedHashMap<>();

    /** Currently active font resource name from the last {@link #setFont} call. */
    private String currentFontResource;

    /**
     * WinAnsiEncoding mapping table for characters 128-159 (the range that differs
     * from ISO 8859-1). Maps Unicode code point to WinAnsi byte value.
     * Characters 0-127 and 160-255 map to themselves in WinAnsi.
     */
    private static final int[] WIN_ANSI_128_159 = {
        0x20AC, 0x0000, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, // 128-135
        0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0x0000, 0x017D, 0x0000, // 136-143
        0x0000, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, // 144-151
        0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0x0000, 0x017E, 0x0178  // 152-159
    };

    /**
     * Creates an empty ContentStreamBuilder.
     */
    public ContentStreamBuilder() {
        LOG.fine("ContentStreamBuilder created");
    }

    // ---- Text operators (ISO 32000-1:2008, Section 9) ----

    /**
     * Emits the BT (begin text object) operator.
     */
    public void beginText() {
        emit("BT\n");
    }

    /**
     * Emits the ET (end text object) operator.
     */
    public void endText() {
        emit("ET\n");
    }

    /**
     * Emits the Tf (set text font and size) operator.
     *
     * @param resourceName the font resource name (e.g. "F1")
     * @param size         the font size in points
     */
    public void setFont(String resourceName, double size) {
        emit(String.format(Locale.US, "/%s %.1f Tf\n", resourceName, size));
        this.currentFontResource = resourceName;
    }

    /**
     * Marks {@code resourceName} as a Type0/Identity-H font. Subsequent
     * {@link #showText} calls — while this font is active — will emit
     * 2-byte glyph IDs via the supplied {@code reader}'s cmap instead of
     * single-byte WinAnsi codes. Call once per page after the font has been
     * registered with {@link #registerFont}.
     *
     * @param resourceName the {@code /Fn} name on /Resources/Font
     * @param reader       the TrueType reader providing Unicode → GID
     */
    public void markFontAsType0(String resourceName,
                                org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        if (resourceName == null || reader == null) return;
        type0Readers.put(resourceName, reader);
    }

    /**
     * Emits the Td (move text position) operator.
     *
     * @param x the horizontal offset
     * @param y the vertical offset
     */
    public void moveText(double x, double y) {
        emit(String.format(Locale.US, "%.2f %.2f Td\n", x, y));
    }

    /**
     * Emits the Tj (show text) operator with proper PDF string escaping
     * and WinAnsiEncoding.
     *
     * @param text the text to show
     */
    public void showText(String text) {
        if (text == null) {
            text = "";
        }
        org.aspose.pdf.engine.font.ttf.TrueTypeReader cidReader =
                currentFontResource != null ? type0Readers.get(currentFontResource) : null;
        if (cidReader != null) {
            showTextAsCid(text, cidReader);
            return;
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        sb.append('(');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int winAnsi = unicodeToWinAnsi(ch);
            if (winAnsi < 0) {
                // Character not in WinAnsi, replace with '?'
                winAnsi = '?';
            }
            // Escape PDF special characters within literal strings
            if (winAnsi == '\\') {
                sb.append("\\\\");
            } else if (winAnsi == '(') {
                sb.append("\\(");
            } else if (winAnsi == ')') {
                sb.append("\\)");
            } else if (winAnsi >= 32 && winAnsi <= 126) {
                sb.append((char) winAnsi);
            } else if (winAnsi >= 128 && winAnsi <= 255) {
                // Emit as octal escape for non-ASCII WinAnsi bytes
                sb.append('\\');
                sb.append(String.format(Locale.US, "%03o", winAnsi));
            } else {
                sb.append('?');
            }
        }
        sb.append(") Tj\n");
        emit(sb.toString());
    }

    /**
     * Emits {@code text} as a hex string of 2-byte glyph IDs for an
     * Identity-H Type0 font. Each input <em>codepoint</em> — supplementary-
     * plane characters included — is translated through the font's cmap to
     * a single 16-bit GID; missing glyphs fall back to {@code .notdef}
     * (GID 0).
     *
     * <p>One codepoint = one 2-byte CID, even when the Java string carries
     * the codepoint as a UTF-16 surrogate pair. The decoder ({@code TextRenderer})
     * walks the resulting bytes in 2-byte strides, so this stays balanced.</p>
     */
    private void showTextAsCid(String text,
                               org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        StringBuilder sb = new StringBuilder(text.length() * 4 + 8);
        sb.append('<');
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int gid = reader.getGlyphId(cp);
            sb.append(String.format("%04X", gid & 0xFFFF));
            i += Character.charCount(cp);
        }
        sb.append("> Tj\n");
        emit(sb.toString());
    }

    /**
     * Emits the Tm (set text matrix) operator.
     *
     * @param a the a component
     * @param b the b component
     * @param c the c component
     * @param d the d component
     * @param e the e (tx) component
     * @param f the f (ty) component
     */
    public void setTextMatrix(double a, double b, double c, double d, double e, double f) {
        emit(String.format(Locale.US, "%.4f %.4f %.4f %.4f %.2f %.2f Tm\n", a, b, c, d, e, f));
    }

    // ---- Color operators (ISO 32000-1:2008, Section 8.6) ----

    /**
     * Emits the rg (set RGB fill color) operator.
     *
     * @param r red component (0.0 to 1.0)
     * @param g green component (0.0 to 1.0)
     * @param b blue component (0.0 to 1.0)
     */
    public void setRGBFillColor(double r, double g, double b) {
        emit(String.format(Locale.US, "%.4f %.4f %.4f rg\n", r, g, b));
    }

    /**
     * Emits the RG (set RGB stroke color) operator.
     *
     * @param r red component (0.0 to 1.0)
     * @param g green component (0.0 to 1.0)
     * @param b blue component (0.0 to 1.0)
     */
    public void setRGBStrokeColor(double r, double g, double b) {
        emit(String.format(Locale.US, "%.4f %.4f %.4f RG\n", r, g, b));
    }

    /**
     * Emits the g (set gray fill color) operator.
     *
     * @param gray the gray level (0.0 = black, 1.0 = white)
     */
    public void setGrayFillColor(double gray) {
        emit(String.format(Locale.US, "%.4f g\n", gray));
    }

    /**
     * Emits the G (set gray stroke color) operator.
     *
     * @param gray the gray level (0.0 = black, 1.0 = white)
     */
    public void setGrayStrokeColor(double gray) {
        emit(String.format(Locale.US, "%.4f G\n", gray));
    }

    // ---- Graphics state operators (ISO 32000-1:2008, Section 8.4) ----

    /**
     * Emits the w (set line width) operator.
     *
     * @param width the line width
     */
    public void setLineWidth(double width) {
        emit(String.format(Locale.US, "%.2f w\n", width));
    }

    /**
     * Emits the d (set dash pattern) operator. An empty {@code pattern} restores
     * a solid line ({@code [] 0 d}).
     *
     * @param pattern the dash array (on/off segment lengths); empty for solid
     * @param phase   the dash phase
     */
    public void setLineDash(double[] pattern, double phase) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pattern.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%.2f", pattern[i]));
        }
        sb.append(']');
        emit(String.format(Locale.US, "%s %.2f d\n", sb, phase));
    }

    /**
     * Emits the q (save graphics state) operator.
     */
    public void saveState() {
        emit("q\n");
    }

    /**
     * Emits the Q (restore graphics state) operator.
     */
    public void restoreState() {
        emit("Q\n");
    }

    /**
     * Emits the cm (concatenate matrix) operator.
     *
     * @param a the a component
     * @param b the b component
     * @param c the c component
     * @param d the d component
     * @param e the e (tx) component
     * @param f the f (ty) component
     */
    public void concatMatrix(double a, double b, double c, double d, double e, double f) {
        emit(String.format(Locale.US, "%.4f %.4f %.4f %.4f %.2f %.2f cm\n", a, b, c, d, e, f));
    }

    // ---- Path construction and painting (ISO 32000-1:2008, Section 8.5) ----

    /**
     * Emits the re (rectangle) operator.
     *
     * @param x the lower-left x coordinate
     * @param y the lower-left y coordinate
     * @param w the width
     * @param h the height
     */
    public void rectangle(double x, double y, double w, double h) {
        emit(String.format(Locale.US, "%.2f %.2f %.2f %.2f re\n", x, y, w, h));
    }

    /**
     * Emits the m (move-to) operator, beginning a new subpath at {@code (x,y)}.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     */
    public void moveTo(double x, double y) {
        emit(String.format(Locale.US, "%.2f %.2f m\n", x, y));
    }

    /**
     * Emits the l (line-to) operator, appending a straight segment to {@code (x,y)}.
     *
     * @param x the x coordinate
     * @param y the y coordinate
     */
    public void lineTo(double x, double y) {
        emit(String.format(Locale.US, "%.2f %.2f l\n", x, y));
    }

    /**
     * Emits the c (cubic Bézier) operator with two control points.
     *
     * @param x1 first control x
     * @param y1 first control y
     * @param x2 second control x
     * @param y2 second control y
     * @param x3 end x
     * @param y3 end y
     */
    public void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) {
        emit(String.format(Locale.US, "%.2f %.2f %.2f %.2f %.2f %.2f c\n", x1, y1, x2, y2, x3, y3));
    }

    /**
     * Emits the h (close subpath) operator.
     */
    public void closePath() {
        emit("h\n");
    }

    /**
     * Emits the f (fill path) operator.
     */
    public void fill() {
        emit("f\n");
    }

    /**
     * Emits the S (stroke path) operator.
     */
    public void stroke() {
        emit("S\n");
    }

    /**
     * Emits the B (fill then stroke path) operator.
     */
    public void fillStroke() {
        emit("B\n");
    }

    /**
     * Emits {@code W n} — intersects the clipping path with the current path and then ends the path
     * without painting. Use after defining a path (e.g. {@link #rectangle}) to clip subsequent marks
     * to that region (ISO 32000-1:2008, §8.5.4). Pair with {@link #saveState}/{@link #restoreState}
     * to scope the clip.
     */
    public void clip() {
        emit("W n\n");
    }

    // ---- XObject operator (ISO 32000-1:2008, Section 8.8) ----

    /**
     * Emits the Do (paint XObject) operator.
     *
     * @param name the XObject resource name (e.g. "Im1")
     */
    public void drawXObject(String name) {
        emit(String.format(Locale.US, "/%s Do\n", name));
    }

    /**
     * Emits the gs (set parameters from an ExtGState) operator.
     *
     * @param name the /ExtGState resource name (e.g. "GS1")
     */
    public void setExtGState(String name) {
        emit(String.format(Locale.US, "/%s gs\n", name));
    }

    // ---- Resource registration ----

    /**
     * Registers a standard font and returns its resource name.
     * If the same base font was already registered, returns the existing name.
     *
     * @param baseFont the base font name (e.g. "Helvetica")
     * @return the resource name (e.g. "F1")
     */
    public String registerFont(String baseFont) {
        String effectiveFont = baseFont != null ? baseFont : "Helvetica";
        String existing = fontResources.get(effectiveFont);
        if (existing != null) {
            return existing;
        }
        fontCounter++;
        String name = "F" + fontCounter;
        fontResources.put(effectiveFont, name);
        LOG.fine(() -> "Registered font " + effectiveFont + " as " + name);
        return name;
    }

    /**
     * Registers an embedded {@code /Type0} font and returns its resource name. The font is marked as
     * Identity-H (subsequent {@link #showText} emits 2-byte GIDs via {@code reader}), and the supplied
     * Type0 dictionary is recorded so the caller's font-merge step can attach it to the page
     * {@code /Resources/Font}. Re-registering the same {@code fontKey} returns the existing name.
     *
     * @param fontKey   a stable unique key for this font+style (e.g. {@code "arial-b"})
     * @param type0Dict the assembled {@code /Type0} font dictionary
     * @param reader    the TrueType reader providing Unicode→GID for encoding
     * @return the resource name (e.g. {@code "F2"})
     */
    public String registerEmbeddedFont(String fontKey,
                                       org.aspose.pdf.engine.pdfobjects.PdfDictionary type0Dict,
                                       org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        String key = fontKey != null ? fontKey : "EmbeddedFont";
        String existing = fontResources.get(key);
        if (existing != null) {
            return existing;
        }
        fontCounter++;
        String name = "F" + fontCounter;
        fontResources.put(key, name);
        embeddedFontDicts.put(name, type0Dict);
        markFontAsType0(name, reader);
        return name;
    }

    /**
     * @return resource name → embedded {@code /Type0} font dictionary, for the font-merge step
     *         (unmodifiable; empty when no embedded fonts were registered)
     */
    public Map<String, org.aspose.pdf.engine.pdfobjects.PdfDictionary> getEmbeddedFontDicts() {
        return Collections.unmodifiableMap(embeddedFontDicts);
    }

    /**
     * Registers an image resource and returns its resource name.
     * If the same key was already registered, returns the existing name.
     *
     * @param key a unique key for the image
     * @return the resource name (e.g. "Im1")
     */
    public String registerImage(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Image key must not be null");
        }
        String existing = imageResources.get(key);
        if (existing != null) {
            return existing;
        }
        imageCounter++;
        String name = "Im" + imageCounter;
        imageResources.put(key, name);
        LOG.fine(() -> "Registered image " + key + " as " + name);
        return name;
    }

    /**
     * Registers an Image XObject (its stream) and returns its resource name, recording the stream so
     * the caller's resource-merge step can attach it to the page {@code /Resources/XObject}. Paint the
     * image with {@code q  w 0 0 h x y cm  /name Do  Q} (the {@code cm} scales the 1×1 image space to
     * the target rectangle). Re-registering the same {@code key} returns the existing name.
     *
     * @param key  a stable unique key for this image (e.g. its content hash)
     * @param xobj the assembled Image XObject stream
     * @return the resource name (e.g. {@code "Im1"})
     */
    public String registerImage(String key, org.aspose.pdf.engine.pdfobjects.PdfStream xobj) {
        String name = registerImage(key);
        if (xobj != null) {
            imageXObjectDicts.put(name, xobj);
        }
        return name;
    }

    /**
     * @return resource name → Image XObject stream, for the resource-merge step
     *         (unmodifiable; empty when no image streams were registered)
     */
    public Map<String, org.aspose.pdf.engine.pdfobjects.PdfStream> getImageXObjectDicts() {
        return Collections.unmodifiableMap(imageXObjectDicts);
    }

    /**
     * Registers a constant-alpha {@code /ExtGState} ({@code /ca} fill alpha and
     * {@code /CA} stroke alpha) and returns its resource name. Emit it with
     * {@link #setExtGState(String)} to fade subsequent painting (e.g. a
     * watermark backdrop). Re-registering the same alpha returns the same name.
     *
     * @param alpha opacity in [0,1]
     * @return the ExtGState resource name (e.g. {@code "GS1"})
     */
    public String registerAlphaGState(double alpha) {
        double a = Math.max(0.0, Math.min(1.0, alpha));
        String existing = alphaGStates.get(a);
        if (existing != null) {
            return existing;
        }
        gsCounter++;
        String name = "GS" + gsCounter;
        org.aspose.pdf.engine.pdfobjects.PdfDictionary gs =
                new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
        gs.set("Type", org.aspose.pdf.engine.pdfobjects.PdfName.of("ExtGState"));
        gs.set("ca", new org.aspose.pdf.engine.pdfobjects.PdfFloat(a));
        gs.set("CA", new org.aspose.pdf.engine.pdfobjects.PdfFloat(a));
        extGStateDicts.put(name, gs);
        alphaGStates.put(a, name);
        return name;
    }

    /**
     * @return resource name → {@code /ExtGState} dictionary, for the resource-merge
     *         step (unmodifiable; empty when no soft-alpha state was registered)
     */
    public Map<String, org.aspose.pdf.engine.pdfobjects.PdfDictionary> getExtGStateDicts() {
        return Collections.unmodifiableMap(extGStateDicts);
    }

    /**
     * Returns an unmodifiable map from base font name to resource name.
     *
     * @return the font resources map
     */
    public Map<String, String> getFontResources() {
        return Collections.unmodifiableMap(fontResources);
    }

    /**
     * Returns an unmodifiable map from image key to resource name.
     *
     * @return the image resources map
     */
    public Map<String, String> getImageResources() {
        return Collections.unmodifiableMap(imageResources);
    }

    // ---- Output ----

    /**
     * Returns the accumulated content stream as a byte array.
     *
     * @return the content stream bytes
     */
    public byte[] toByteArray() {
        return buffer.toByteArray();
    }

    // ---- Internal helpers ----

    /**
     * Writes an ASCII string to the content stream buffer.
     *
     * @param s the string to write
     */
    private void emit(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        buffer.write(bytes, 0, bytes.length);
    }

    /**
     * Converts a Unicode character to its WinAnsiEncoding byte value.
     * <p>
     * For characters 0-127 and 160-255, the mapping is identity (same as ISO 8859-1).
     * For characters 128-159, a lookup table is used since WinAnsi uses Windows-1252
     * mappings in this range. Characters outside the WinAnsi range return -1.
     * </p>
     *
     * @param ch the Unicode character
     * @return the WinAnsi byte value (0-255), or -1 if not representable
     */
    public static int unicodeToWinAnsi(char ch) {
        // Direct mapping for ASCII and Latin-1 supplement (160-255)
        if (ch < 128) {
            return ch;
        }
        if (ch >= 160 && ch <= 255) {
            return ch;
        }
        // Search the 128-159 range (Windows-1252 specials)
        for (int i = 0; i < WIN_ANSI_128_159.length; i++) {
            if (WIN_ANSI_128_159[i] == ch) {
                return 128 + i;
            }
        }
        // Not in WinAnsi
        return -1;
    }
}
