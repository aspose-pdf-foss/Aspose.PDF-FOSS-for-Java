package org.aspose.pdf.text;

import org.aspose.pdf.Color;
import org.aspose.pdf.Page;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.font.ttf.FontDiskLookup;
import org.aspose.pdf.engine.font.ttf.TrueTypeReader;
import org.aspose.pdf.engine.font.ttf.Type0FontBuilder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds and appends text content to a PDF page by generating content stream operators.
 * <p>
 * Registers fonts in the page resources and produces proper PDF content stream syntax
 * (BT/ET blocks with Tf, Td, Tj operators) as specified in ISO 32000-1:2008, §9.
 * </p>
 */
public class TextBuilder {

    /** ISO 32000-1 §D.2 WinAnsiEncoding ≡ CP1252. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /**
     * Encodes content-stream bytes using WinAnsiEncoding. Built-in Type1
     * font dictionaries created by this class declare {@code /Encoding
     * /WinAnsiEncoding}; the text-show payload therefore must be the byte
     * sequence the font maps from WinAnsi codepoints. Characters outside
     * CP1252 are replaced with {@code '?'} per the spec recommendation for
     * unrepresentable glyphs in single-byte fonts.
     */
    private static byte[] winAnsi(String s) {
        CharsetEncoder enc = WIN_ANSI.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .replaceWith(new byte[]{(byte) '?'});
        try {
            java.nio.ByteBuffer bb = enc.encode(java.nio.CharBuffer.wrap(s));
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalStateException("WinAnsi encoding failure", e);
        }
    }

    private static final Logger LOG = Logger.getLogger(TextBuilder.class.getName());

    private final Page page;

    /**
     * Sprint 37: per-builder cache of Type0/Identity-H font registrations.
     * {@code type0FontResources} maps base font name (e.g. {@code "Arial"})
     * to its resource alias (e.g. {@code "F2"}); {@code type0Readers} maps
     * the resource alias to the {@link TrueTypeReader} we need for
     * Unicode → glyph-ID translation when emitting text.
     */
    private final Map<String, String> type0FontResources = new HashMap<>();
    private final Map<String, TrueTypeReader> type0Readers = new HashMap<>();

    /**
     * Creates a TextBuilder that appends text to the given page.
     *
     * @param page the target page
     * @throws IllegalArgumentException if page is null
     */
    public TextBuilder(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        this.page = page;
    }

    /**
     * Appends a single text fragment to the page.
     * <p>
     * Registers the font in the page's /Resources/Font dictionary if not already present,
     * then builds and appends content stream bytes: {@code q BT /Fn size Tf x y Td (text) Tj ET Q}.
     * </p>
     *
     * @param fragment the text fragment to append
     * @throws IllegalArgumentException if fragment is null
     */
    public void appendText(TextFragment fragment) {
        if (fragment == null) {
            throw new IllegalArgumentException("TextFragment must not be null");
        }
        fragment.setPage(this.page);

        TextState fragState = fragment.getTextState();
        Position fragPos = fragment.getPosition();
        double fragX = fragPos != null ? fragPos.getXIndent() : 0;
        double fragY = fragPos != null ? fragPos.getYIndent() : 0;

        // ── Build a working list of segments to render ───────────────────────
        // The TextFragment ctor seeds segments[0] with the primary text. If a
        // user adds more segments via getSegments().add(...), render each one
        // as its own Tj so absorbers reading the document back can decompose
        // the fragment into per-segment text fragments (matching the
        // Aspose.PDF semantics that each Tj-per-state becomes a fragment).
        java.util.List<TextSegment> segments = fragment.getSegments();
        if (segments == null || segments.isEmpty()) {
            // Defensive: render only the primary text via the historical path.
            String resourceName = registerFont(resolveFontName(fragState));
            page.appendToContentStream(buildTextContent(resourceName,
                    fragState, fragX, fragY, fragment.getText()));
            LOG.fine(() -> "Appended text fragment (no segments): \"" + fragment.getText() + "\"");
            return;
        }

        // Compute background rectangles up-front (one for the whole fragment if
        // any of its segments inherits its BG, plus one per segment with its own).
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try {
            write(baos, "q\n");

            // Fragment-level background rectangle (covers all segments roughly):
            // position at fragment Y baseline, width estimated from primary text.
            if (fragState.getBackgroundColor() != null) {
                double fragSize = resolveFontSize(fragState);
                double fragWidth = estimateTextWidth(fragment.getText(), fragSize);
                writeBackgroundRect(baos, fragState.getBackgroundColor(),
                        fragX, fragY, fragWidth, fragSize * 1.32);
            }

            // Per-segment background rectangles (skip primary if it inherits
            // the fragment-level one — the primary segment shares state with
            // the fragment and doesn't get a separate rectangle).
            for (int i = 0; i < segments.size(); i++) {
                TextSegment seg = segments.get(i);
                TextState segState = seg.getTextState();
                if (segState == null) continue;
                Color bg = segState.getBackgroundColor();
                if (bg == null) continue;
                if (i == 0 && bg == fragState.getBackgroundColor()) continue;
                Position sp = seg.getPosition();
                double sx = sp != null ? sp.getXIndent() : fragX;
                double sy = sp != null ? sp.getYIndent() : fragY;
                double size = resolveFontSize(segState);
                double w = estimateTextWidth(seg.getText(), size);
                writeBackgroundRect(baos, bg, sx, sy, w, size * 1.32);
            }

            // Now emit one BT…ET for the segments.
            write(baos, "BT\n");
            String currentFont = null;
            double currentSize = -1;
            Color currentFg = null;
            double currentCharSpacing = 0;
            double currentWordSpacing = 0;
            double currentHorizontalScaling = 100;
            double currentLeading = 0;
            int currentRenderingMode = 0;
            double currentTextRise = 0;
            for (int i = 0; i < segments.size(); i++) {
                TextSegment seg = segments.get(i);
                TextState segState = seg.getTextState();
                if (segState == null) {
                    segState = fragState;
                }
                String fontName = resolveFontName(segState);
                double fontSize = resolveFontSize(segState);
                String segText = seg.getText() != null ? seg.getText() : "";

                // Position: if segment has explicit position use Tm, else for the
                // first segment use fragment position via Tm, else inherit cursor.
                Position segPos = seg.getPosition();
                if (segPos != null) {
                    write(baos, "1 0 0 1 " + formatNumber(segPos.getXIndent())
                            + " " + formatNumber(segPos.getYIndent()) + " Tm\n");
                } else if (i == 0) {
                    write(baos, "1 0 0 1 " + formatNumber(fragX)
                            + " " + formatNumber(fragY) + " Tm\n");
                }

                // Sprint 37: route text containing characters outside WinAnsi
                // through a Type0 (composite, Identity-H) font so Thai/CJK/etc.
                // round-trip through save → reopen → TFA. Falls back to the
                // existing Type1/WinAnsi path when the system TTF cannot be
                // located (e.g. font name unknown to the OS).
                boolean useType0 = requiresType0(segText);
                String resourceName = null;
                if (useType0) {
                    resourceName = registerType0Font(fontName, segText);
                    if (resourceName == null) {
                        useType0 = false;
                    }
                }
                if (!useType0) {
                    resourceName = registerFont(fontName);
                }
                String fontKey = (useType0 ? "T0:" : "T1:") + fontName;
                if (!fontKey.equals(currentFont) || fontSize != currentSize) {
                    write(baos, "/" + resourceName + " " + formatNumber(fontSize) + " Tf\n");
                    currentFont = fontKey;
                    currentSize = fontSize;
                }

                double charSpacing = segState.getCharacterSpacing();
                if (Double.compare(charSpacing, currentCharSpacing) != 0) {
                    write(baos, formatNumber(charSpacing) + " Tc\n");
                    currentCharSpacing = charSpacing;
                }

                double wordSpacing = segState.getWordSpacing();
                if (Double.compare(wordSpacing, currentWordSpacing) != 0) {
                    write(baos, formatNumber(wordSpacing) + " Tw\n");
                    currentWordSpacing = wordSpacing;
                }

                double horizontalScaling = segState.getHorizontalScaling();
                if (Double.compare(horizontalScaling, currentHorizontalScaling) != 0) {
                    write(baos, formatNumber(horizontalScaling) + " Tz\n");
                    currentHorizontalScaling = horizontalScaling;
                }

                double textLeading = segState.getTextLeading();
                if (Double.compare(textLeading, currentLeading) != 0) {
                    write(baos, formatNumber(textLeading) + " TL\n");
                    currentLeading = textLeading;
                }

                int renderingMode = segState.getRenderingMode();
                if (renderingMode != currentRenderingMode) {
                    write(baos, renderingMode + " Tr\n");
                    currentRenderingMode = renderingMode;
                }

                double textRise = segState.getTextRise();
                if (Double.compare(textRise, currentTextRise) != 0) {
                    write(baos, formatNumber(textRise) + " Ts\n");
                    currentTextRise = textRise;
                }

                Color fg = segState.getForegroundColor();
                if (fg == null) fg = fragState.getForegroundColor();
                if (fg != null && fg != currentFg) {
                    writeFillColorRG(baos, fg);
                    currentFg = fg;
                }

                if (useType0) {
                    TrueTypeReader reader = type0Readers.get(resourceName);
                    write(baos, "<" + encodeAsCidHex(segText, reader) + "> Tj\n");
                } else {
                    write(baos, "(" + escapePdfString(segText) + ") Tj\n");
                }
            }
            write(baos, "ET\n");
            write(baos, "Q\n");
        } catch (IOException e) {
            throw new RuntimeException("Unexpected I/O error building content stream", e);
        }

        page.appendToContentStream(baos.toByteArray());
        LOG.fine(() -> "Appended text fragment with " + segments.size() + " segment(s)");
    }

    /**
     * Sprint 37: true if any character in {@code text} cannot be encoded in
     * WinAnsiEncoding (ISO 32000-1 §D.2). Used to route text through the
     * Type0/Identity-H path so Thai/CJK/Arabic/etc. survive save → reopen.
     */
    private static boolean requiresType0(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (org.aspose.pdf.engine.layout.ContentStreamBuilder
                    .unicodeToWinAnsi(text.charAt(i)) < 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Fallback font list for Type0 registration when the requested font on
     * disk lacks glyphs for the supplied text (e.g. Windows Arial has no
     * Thai). Ordered by glyph coverage — Tahoma is bundled with every recent
     * Windows release and ships with Thai, Cyrillic, Arabic, Hebrew, Greek
     * and a wide CJK subset, which is enough for the cases the regression
     * suite cares about; CJK-heavy text falls through to SimSun.
     */
    private static final String[] TYPE0_FALLBACK_FONTS = {
            "Tahoma",
            "Arial Unicode MS",
            "Microsoft Sans Serif",
            "SimSun",
            "Noto Sans",
    };

    /**
     * Sprint 37: lazily register a Type0/Identity-H font for {@code fontName}.
     * Loads the TTF from disk and verifies its cmap covers every codepoint in
     * {@code text}; if not, walks {@link #TYPE0_FALLBACK_FONTS} until a font
     * with full coverage is found. The actual face used may therefore differ
     * from {@code fontName} — that's the Aspose-style behaviour where text in
     * "Arial" automatically renders through Tahoma when the original font
     * has no Thai glyphs.
     *
     * @return the resource alias (e.g. {@code "F2"}) on the page's
     *         {@code /Resources/Font}, or {@code null} when no candidate
     *         font could be located on disk
     */
    private String registerType0Font(String fontName, String text) {
        if (fontName == null || fontName.isEmpty()) {
            return null;
        }
        // Pick the best font for this text — prefer the requested face when
        // its glyph coverage is sufficient, otherwise fall back. If the
        // requested font is unknown to the OS we bail out so the caller can
        // use the historical Type1/WinAnsi path: substituting a different
        // family for an unresolvable name would surprise callers that pick
        // standard-14 fonts ("Helvetica", "Times-Roman") relying on the
        // WinAnsi '?'-fallback behaviour for unrepresentable characters.
        Type0FontBuilder.Result effectiveResult = tryLoadType0(fontName);
        if (effectiveResult == null) {
            return null;
        }
        String effectiveName = fontName;
        if (!coversAllCodepoints(effectiveResult.reader, text)) {
            for (String fb : TYPE0_FALLBACK_FONTS) {
                if (fb.equalsIgnoreCase(fontName)) continue;
                Type0FontBuilder.Result r = tryLoadType0(fb);
                if (r != null && coversAllCodepoints(r.reader, text)) {
                    effectiveName = fb;
                    effectiveResult = r;
                    LOG.fine(() -> "Type0 fallback: requested " + fontName
                            + " lacks glyphs; using " + fb);
                    break;
                }
            }
        }

        String cached = type0FontResources.get(effectiveName);
        if (cached != null) {
            return cached;
        }

        Resources resources = page.ensureResources();
        COSDictionary fontsDict = resources.getFonts();
        if (fontsDict == null) {
            fontsDict = new COSDictionary();
            resources.getCOSDictionary().set(COSName.FONT, fontsDict);
        }
        int index = 1;
        String candidateName;
        do {
            candidateName = "F" + index;
            index++;
        } while (fontsDict.containsKey(candidateName));

        fontsDict.set(COSName.of(candidateName), effectiveResult.type0Font);
        type0FontResources.put(effectiveName, candidateName);
        type0Readers.put(candidateName, effectiveResult.reader);
        final String finalName = candidateName;
        final String fEffective = effectiveName;
        LOG.fine(() -> "Registered Type0 font " + fEffective + " as /" + finalName);
        return candidateName;
    }

    /**
     * Loads {@code fontName}'s TTF from disk and assembles its Type0 graph.
     * Returns {@code null} when the file is missing or the parser rejects it.
     */
    private static Type0FontBuilder.Result tryLoadType0(String fontName) {
        byte[] ttf = FontDiskLookup.loadByName(fontName);
        if (ttf == null) {
            return null;
        }
        try {
            return Type0FontBuilder.build(fontName, ttf);
        } catch (IOException e) {
            LOG.warning("Failed to build Type0 font for " + fontName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns {@code true} when every codepoint in {@code text} resolves to a
     * non-{@code .notdef} glyph through {@code reader}'s cmap. Surrogate
     * pairs collapse to the single supplementary-plane codepoint that
     * {@link TrueTypeReader#getGlyphId(int)} expects.
     */
    private static boolean coversAllCodepoints(TrueTypeReader reader, String text) {
        if (reader == null || text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (reader.getGlyphId(cp) == 0) return false;
            i += Character.charCount(cp);
        }
        return true;
    }

    /**
     * Sprint 37: encode {@code text} as a stream of big-endian 2-byte glyph
     * IDs (hex), one CID per Unicode codepoint. Missing glyphs fall back to
     * {@code .notdef} (GID 0); UTF-16 surrogate pairs collapse to the single
     * supplementary-plane CID that {@link TrueTypeReader#getGlyphId(int)}
     * returns. Result is the body of a {@code <…> Tj} hex string operand.
     */
    private static String encodeAsCidHex(String text, TrueTypeReader reader) {
        if (text == null || text.isEmpty() || reader == null) return "";
        StringBuilder sb = new StringBuilder(text.length() * 4);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int gid = reader.getGlyphId(cp);
            sb.append(String.format("%04X", gid & 0xFFFF));
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String resolveFontName(TextState s) {
        return s != null && s.getFontName() != null ? s.getFontName() : "Helvetica";
    }

    private static double resolveFontSize(TextState s) {
        return s != null && s.getFontSize() > 0 ? s.getFontSize() : 12;
    }

    /**
     * Estimates text width by averaging the typical character advance for the
     * Helvetica/CourierNew sans-serif family. This is intentionally a rough
     * heuristic — it powers background rectangles for which a coarse
     * tolerance suffices; pixel-perfect width would require a real
     * font-metric table (out of scope).
     */
    private static double estimateTextWidth(String text, double fontSize) {
        if (text == null || text.isEmpty()) return 0;
        // Average advance ≈ 0.55 em across mixed-width sans-serif text.
        return text.length() * fontSize * 0.55;
    }

    private static void writeBackgroundRect(ByteArrayOutputStream baos, Color color,
                                            double x, double y, double w, double h) throws IOException {
        write(baos, "q\n");
        writeFillColorRG(baos, color);
        write(baos, formatNumber(x) + " " + formatNumber(y) + " "
                + formatNumber(w) + " " + formatNumber(h) + " re\n");
        write(baos, "f\n");
        write(baos, "Q\n");
    }

    private static void writeFillColorRG(ByteArrayOutputStream baos, Color color) throws IOException {
        double[] c = color.getComponents();
        double r = c.length > 0 ? c[0] : 0;
        double g = c.length > 1 ? c[1] : r;
        double b = c.length > 2 ? c[2] : r;
        write(baos, formatNumber(r) + " " + formatNumber(g) + " " + formatNumber(b) + " rg\n");
    }

    /**
     * Appends multiple text fragments to the page.
     * <p>
     * Each fragment is appended individually by calling {@link #appendText(TextFragment)}.
     * </p>
     *
     * @param fragments the list of text fragments to append
     * @throws IllegalArgumentException if fragments is null
     */
    public void appendText(List<TextFragment> fragments) {
        if (fragments == null) {
            throw new IllegalArgumentException("Fragments list must not be null");
        }
        for (TextFragment fragment : fragments) {
            appendText(fragment);
        }
    }

    /**
     * Appends a paragraph (multiple lines) to the page.
     * <p>
     * Each line is positioned vertically below the previous one using the paragraph's
     * line spacing multiplied by the font size.
     * </p>
     *
     * @param paragraph the text paragraph to append
     * @throws IllegalArgumentException if paragraph is null
     */
    public void appendParagraph(TextParagraph paragraph) {
        if (paragraph == null) {
            throw new IllegalArgumentException("TextParagraph must not be null");
        }

        List<TextFragment> lines = paragraph.getLinesList();
        if (lines.isEmpty()) {
            return;
        }

        Position paraPos = paragraph.getPosition();
        double startX = paraPos != null ? paraPos.getXIndent() : 0;
        double startY = paraPos != null ? paraPos.getYIndent() : 0;

        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try {
            write(baos, "q\n");
            write(baos, "BT\n");

            for (int i = 0; i < lines.size(); i++) {
                TextFragment line = lines.get(i);
                line.setPage(this.page);
                TextState state = line.getTextState();
                String fontName = state.getFontName() != null ? state.getFontName() : "Helvetica";
                double fontSize = state.getFontSize() > 0 ? state.getFontSize() : 12;

                String resourceName = registerFont(fontName);

                write(baos, "/" + resourceName + " " + formatNumber(fontSize) + " Tf\n");

                if (i == 0) {
                    write(baos, formatNumber(startX) + " " + formatNumber(startY) + " Td\n");
                } else {
                    // Move down by line spacing * font size
                    double leading = -(paragraph.getLineSpacing() * fontSize);
                    write(baos, "0 " + formatNumber(leading) + " Td\n");
                }

                write(baos, "(" + escapePdfString(line.getText()) + ") Tj\n");
            }

            write(baos, "ET\n");
            write(baos, "Q\n");
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw IOException
            throw new RuntimeException("Unexpected I/O error building content stream", e);
        }

        page.appendToContentStream(baos.toByteArray());
        LOG.fine(() -> "Appended paragraph with " + lines.size() + " line(s)");
    }

    /**
     * Registers a font in the page's /Resources/Font dictionary if not already present.
     * Creates a simple Type1 font dictionary with /Type /Font, /Subtype /Type1,
     * /BaseFont /&lt;fontName&gt;, /Encoding /WinAnsiEncoding.
     *
     * @param fontName the base font name (e.g., "Helvetica", "Times-Roman")
     * @return the resource name (e.g., "F1", "F2") used to reference this font in the content stream
     */
    private String registerFont(String fontName) {
        Resources resources = page.ensureResources();
        COSDictionary resDict = resources.getCOSDictionary();

        // Get or create the /Font sub-dictionary
        COSDictionary fontsDict = resources.getFonts();
        if (fontsDict == null) {
            fontsDict = new COSDictionary();
            resDict.set(COSName.FONT, fontsDict);
        }

        // Check if this base font is already registered
        for (COSName key : fontsDict.keySet()) {
            org.aspose.pdf.engine.cos.COSBase val = fontsDict.get(key);
            if (val instanceof COSDictionary) {
                COSDictionary fontDict = (COSDictionary) val;
                String existingBase = fontDict.getNameAsString("BaseFont");
                if (fontName.equals(existingBase)) {
                    // If this dict was attached by another code path that
                    // forgot /Encoding, supply WinAnsi so the bytes emitted
                    // by buildTextContent map to the right glyphs. Do not
                    // overwrite an existing non-WinAnsi encoding.
                    if (fontDict.get("Encoding") == null
                            && !"ZapfDingbats".equals(existingBase)
                            && !"Symbol".equals(existingBase)) {
                        fontDict.set(COSName.ENCODING, COSName.of("WinAnsiEncoding"));
                    }
                    return key.getName();
                }
            }
        }

        // Generate a new name /F1, /F2, ...
        int index = 1;
        String candidateName;
        do {
            candidateName = "F" + index;
            index++;
        } while (fontsDict.containsKey(candidateName));

        // Create font dictionary: /Type /Font /Subtype /Type1 /BaseFont /<fontName> /Encoding /WinAnsiEncoding
        COSDictionary fontDict = new COSDictionary();
        fontDict.set(COSName.TYPE, COSName.FONT);
        fontDict.set(COSName.of("Subtype"), COSName.of("Type1"));
        fontDict.set(COSName.BASE_FONT, COSName.of(fontName));
        fontDict.set(COSName.ENCODING, COSName.of("WinAnsiEncoding"));

        String resourceName = candidateName;
        fontsDict.set(COSName.of(resourceName), fontDict);

        LOG.fine(() -> "Registered font " + fontName + " as /" + resourceName);
        return resourceName;
    }

    /**
     * Builds content stream bytes for a single text fragment.
     */
    private byte[] buildTextContent(String fontResourceName, TextState state,
                                    double x, double y, String text) {
        double fontSize = resolveFontSize(state);
        StringBuilder sb = new StringBuilder(128);
        sb.append("q\n");
        sb.append("BT\n");
        sb.append("/").append(fontResourceName).append(" ").append(formatNumber(fontSize)).append(" Tf\n");
        appendTextStateOperators(sb, state);
        sb.append("1 0 0 1 ").append(formatNumber(x)).append(" ").append(formatNumber(y)).append(" Tm\n");
        sb.append("(").append(escapePdfString(text)).append(") Tj\n");
        sb.append("ET\n");
        sb.append("Q\n");
        return winAnsi(sb.toString());
    }

    private static void appendTextStateOperators(StringBuilder sb, TextState state) {
        if (state == null) {
            return;
        }
        if (Double.compare(state.getCharacterSpacing(), 0) != 0) {
            sb.append(formatNumber(state.getCharacterSpacing())).append(" Tc\n");
        }
        if (Double.compare(state.getWordSpacing(), 0) != 0) {
            sb.append(formatNumber(state.getWordSpacing())).append(" Tw\n");
        }
        if (Double.compare(state.getHorizontalScaling(), 100) != 0) {
            sb.append(formatNumber(state.getHorizontalScaling())).append(" Tz\n");
        }
        if (Double.compare(state.getTextLeading(), 0) != 0) {
            sb.append(formatNumber(state.getTextLeading())).append(" TL\n");
        }
        if (state.getRenderingMode() != 0) {
            sb.append(state.getRenderingMode()).append(" Tr\n");
        }
        if (Double.compare(state.getTextRise(), 0) != 0) {
            sb.append(formatNumber(state.getTextRise())).append(" Ts\n");
        }
    }

    /**
     * Escapes a string for use in a PDF literal string: backslash, parentheses, and control chars.
     *
     * @param text the input string
     * @return the escaped string (without enclosing parentheses)
     */
    static String escapePdfString(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '(':
                    sb.append("\\(");
                    break;
                case ')':
                    sb.append("\\)");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    /**
     * Formats a number for PDF content stream, avoiding unnecessary decimals for integers.
     *
     * <p><strong>Locale-independent</strong> by design: PDF syntax recognises
     * only {@code '.'} as the decimal separator (ISO 32000-1:2008 §7.3.3), so
     * the formatter must always pin {@link java.util.Locale#ROOT}. Without
     * the explicit locale, default-locale comma-as-decimal (ru/de/fr/…)
     * would emit text-show coordinates like {@code 87,2000} that every PDF
     * viewer treats as separate tokens ({@code Unknown operator ',2000'}).</p>
     */
    private static String formatNumber(double value) {
        if (value == (long) value) {
            return Long.toString((long) value);
        }
        // Use up to 4 decimal places, strip trailing zeros
        String s = String.format(java.util.Locale.ROOT, "%.4f", value);
        // Remove trailing zeros after decimal
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "");
            s = s.replaceAll("\\.$", "");
        }
        return s;
    }

    private static void write(ByteArrayOutputStream baos, String s) throws IOException {
        baos.write(winAnsi(s));
    }
}
