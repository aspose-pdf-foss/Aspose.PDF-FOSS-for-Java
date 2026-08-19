package org.aspose.pdf.engine.render;

import org.aspose.pdf.Matrix;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.engine.font.FontRepository;
import org.aspose.pdf.engine.font.PdfFont;
import org.aspose.pdf.engine.font.Type3Font;
import org.aspose.pdf.engine.parser.ContentStreamParser;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.engine.pdfobjects.PdfStream;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Renders text glyphs onto a {@link Graphics2D} context (ISO 32000-1:2008, §9.4).
 */
public class TextRenderer {

    private static final Logger LOG = Logger.getLogger(TextRenderer.class.getName());

    private final FontRepository fontRepo = new FontRepository();

    /**
     * Executes a Type 3 glyph-description content stream (ISO 32000 §9.6.5).
     * Implemented by {@link PdfPageRenderer}, which owns the operator
     * machinery; the glyph state's CTM is already set to
     * {@code FontMatrix × textState × Tm × CTM}.
     */
    public interface Type3GlyphExecutor {
        /**
         * @param g2d        target graphics
         * @param glyphState graphics state for the glyph (pre-multiplied CTM)
         * @param ops        the parsed glyph content stream
         * @param resources  resources for the glyph stream (font's own or page's)
         * @param parser     parser for resolving indirect objects (may be null)
         */
        void execute(Graphics2D g2d, GraphicsState glyphState,
                     OperatorCollection ops, Resources resources, PDFParser parser);
    }

    /** Callback into the page renderer for Type 3 glyph streams (null = Type3 skipped). */
    private Type3GlyphExecutor type3Executor;

    /** Parsed-glyph cache: CharProc stream identity → operator list. */
    private final Map<PdfStream, OperatorCollection> type3GlyphCache = new java.util.IdentityHashMap<>();

    /** Wires the Type 3 glyph-stream executor (called by the page renderer). */
    public void setType3Executor(Type3GlyphExecutor executor) {
        this.type3Executor = executor;
    }

    /** FRC for measuring JDK glyph widths (outline metrics, no hinting bias). */
    private static final FontRenderContext MEASURE_FRC =
            new FontRenderContext(null, true, true);

    /** Cache: scaled JDK fonts keyed by PDF base font name. */
    private final Map<String, Font> scaledFontCache = new HashMap<>();

    /** Cache for JDK font substitutions keyed by PDF base font name. */
    private static final Map<String, String> FONT_SUBSTITUTION = new HashMap<>();
    static {
        FONT_SUBSTITUTION.put("Helvetica", "SansSerif");
        FONT_SUBSTITUTION.put("Helvetica-Bold", "SansSerif");
        FONT_SUBSTITUTION.put("Helvetica-Oblique", "SansSerif");
        FONT_SUBSTITUTION.put("Helvetica-BoldOblique", "SansSerif");
        FONT_SUBSTITUTION.put("Arial", "Arial");
        FONT_SUBSTITUTION.put("ArialMT", "Arial");
        FONT_SUBSTITUTION.put("Arial-BoldMT", "Arial");
        FONT_SUBSTITUTION.put("Times-Roman", "Serif");
        FONT_SUBSTITUTION.put("Times-Bold", "Serif");
        FONT_SUBSTITUTION.put("Times-Italic", "Serif");
        FONT_SUBSTITUTION.put("Times-BoldItalic", "Serif");
        FONT_SUBSTITUTION.put("TimesNewRomanPSMT", "Serif");
        FONT_SUBSTITUTION.put("Courier", "Monospaced");
        FONT_SUBSTITUTION.put("Courier-Bold", "Monospaced");
        FONT_SUBSTITUTION.put("Courier-Oblique", "Monospaced");
        FONT_SUBSTITUTION.put("Courier-BoldOblique", "Monospaced");
        FONT_SUBSTITUTION.put("Symbol", "SansSerif");
        FONT_SUBSTITUTION.put("ZapfDingbats", "SansSerif");
    }

    /**
     * Renders a text string (Tj operator).
     */
    public void renderText(Graphics2D g2d, GraphicsState state,
                           byte[] rawBytes, Resources resources, PDFParser parser) {
        if (rawBytes == null || rawBytes.length == 0) return;

        PdfFont pdfFont = resolveFont(state.getFontName(), resources, parser);
        if (pdfFont instanceof Type3Font && type3Executor != null) {
            renderType3Text(g2d, state, rawBytes, (Type3Font) pdfFont, resources, parser);
            return;
        }
        String text = decodeText(pdfFont, rawBytes);
        if (text.isEmpty()) return;

        Font jdkFont = mapToJdkFont(pdfFont, state.getFontName());
        drawTextGlyphs(g2d, state, text, rawBytes, pdfFont, jdkFont);
    }

    /**
     * Renders a TJ array (interleaved strings and positioning adjustments).
     */
    public void renderTJArray(Graphics2D g2d, GraphicsState state,
                              PdfArray tjArray, Resources resources, PDFParser parser) {
        if (tjArray == null) return;

        PdfFont pdfFont = resolveFont(state.getFontName(), resources, parser);
        boolean type3 = pdfFont instanceof Type3Font && type3Executor != null;
        Font jdkFont = type3 ? null : mapToJdkFont(pdfFont, state.getFontName());

        for (int i = 0; i < tjArray.size(); i++) {
            PdfBase element = tjArray.get(i);
            if (element instanceof PdfString) {
                byte[] rawBytes = ((PdfString) element).getBytes();
                if (type3) {
                    renderType3Text(g2d, state, rawBytes, (Type3Font) pdfFont, resources, parser);
                    continue;
                }
                String text = decodeText(pdfFont, rawBytes);
                if (!text.isEmpty()) {
                    drawTextGlyphs(g2d, state, text, rawBytes, pdfFont, jdkFont);
                }
            } else if (element instanceof PdfInteger || element instanceof PdfFloat) {
                double adj = getNumber(element);
                adjustTextPosition(state, -adj / 1000.0);
            }
        }
    }

    /**
     * Renders text set in a Type 3 font (ISO 32000 §9.6.5): each character
     * code maps via /Encoding(/Differences) to a glyph-description content
     * stream in /CharProcs, which is executed with the CTM set to
     * {@code FontMatrix × textState × Tm × CTM} (§9.4.4 with the font's own
     * glyph-space matrix instead of the implicit 1/1000 scale). The advance
     * uses /Widths values, which are in glyph space and therefore also pass
     * through FontMatrix — NOT the 1/1000 convention of other font types.
     */
    private void renderType3Text(Graphics2D g2d, GraphicsState state, byte[] rawBytes,
                                 Type3Font font, Resources resources, PDFParser parser) {
        double fontSize = state.getFontSize();
        double hScale = state.getHorizontalScaling() / 100.0;
        double charSpacing = state.getCharSpacing();
        double wordSpacing = state.getWordSpacing();
        double rise = state.getTextRise();
        boolean invisible = (state.getTextRenderingMode() == 3);

        Matrix fontMatrix = font.getFontMatrix();
        // Glyph streams use the font's own /Resources; per §9.6.5 Note 2 fall
        // back to the resources of the stream the text was shown from.
        Resources glyphRes = resources;
        org.aspose.pdf.engine.pdfobjects.PdfDictionary ownRes = font.getFontResources();
        if (ownRes != null) {
            glyphRes = new Resources(ownRes, parser);
        }

        for (byte rawByte : rawBytes) {
            int charCode = rawByte & 0xFF;

            if (!invisible) {
                PdfStream charProc = font.getCharProc(charCode);
                if (charProc != null) {
                    OperatorCollection ops = type3GlyphCache.get(charProc);
                    if (ops == null) {
                        try {
                            ops = ContentStreamParser.parseToCollection(charProc);
                        } catch (IOException e) {
                            LOG.fine(() -> "Type3 CharProc parse failed for code "
                                    + charCode + ": " + e.getMessage());
                            ops = new OperatorCollection(); // negative-cache as empty
                        }
                        type3GlyphCache.put(charProc, ops);
                    }
                    if (ops.size() > 0) {
                        Matrix textState = new Matrix(fontSize * hScale, 0, 0, fontSize, 0, rise);
                        Matrix glyphCtm = fontMatrix
                                .multiply(textState)
                                .multiply(state.getTextMatrix())
                                .multiply(state.getCTM());
                        GraphicsState glyphState = state.clone();
                        glyphState.setCTM(glyphCtm);
                        type3Executor.execute(g2d, glyphState, ops, glyphRes, parser);
                    }
                }
            }

            // Advance: /Widths value is in glyph space — map through FontMatrix.
            double wGlyph = font.getWidth(charCode);
            double wText = wGlyph * fontMatrix.getA();
            double advance = (wText * fontSize + charSpacing) * hScale;
            if (charCode == 32) {
                advance += wordSpacing * hScale;
            }
            advanceTextMatrix(state, advance);
        }
    }

    /**
     * Draws decoded text character by character, positioning each glyph via the
     * full Text Rendering Matrix (Trm) per ISO 32000 §9.4.4.
     * <p>
     * A horizontally-scaled unit JDK font is used so that glyphs fit within the
     * PDF advance width with natural sidebearing gaps. The advance stays strictly
     * based on PDF font metrics — no accumulation error.
     * </p>
     */
    private void drawTextGlyphs(Graphics2D g2d, GraphicsState state, String text,
                                byte[] rawBytes, PdfFont pdfFont, Font jdkFont) {
        // Code-keyed subset, CID-glyph and embedded-outline fonts draw per glyph
        // id — the single-run path goes through drawString, which consults the
        // wrong cmap key, silently drops codes that collide with control
        // characters, and renders ".notdef" boxes for subset programs whose cmap
        // java.awt mishandles.
        int[] cffSimpleGid = cffSimpleCodeToGidFor(pdfFont, jdkFont);
        if (canDrawAsSingleRun(state, text, rawBytes)
                && codeKeyedReaderFor(pdfFont, jdkFont) == null
                && compositeGidFontFor(pdfFont, jdkFont) == null
                && compositeCffCidToGidFor(pdfFont, jdkFont) == null
                && embeddedTrueTypeFor(pdfFont, jdkFont) == null
                // A simple CFF font reaches the single-run drawString path only
                // when java.awt can display every decoded char; if any char is
                // undisplayable (custom /Differences encoding) we divert to the
                // per-glyph loop below to draw those by glyph id.
                && (cffSimpleGid == null || !hasUndisplayableChar(jdkFont, text))) {
            drawSingleRun(g2d, state, text, rawBytes, pdfFont, jdkFont);
            return;
        }

        double fontSize = state.getFontSize();
        double hScale = state.getHorizontalScaling() / 100.0;
        double charSpacing = state.getCharSpacing();
        double wordSpacing = state.getWordSpacing();
        double rise = state.getTextRise();
        int renderMode = state.getTextRenderingMode();
        boolean invisible = (renderMode == 3);

        Matrix ctm = state.getCTM();

        // Composite fonts (Type0 / Identity-H) encode each glyph as 2 bytes;
        // simple Type1/TrueType fonts use 1 byte. Walk rawBytes in that
        // stride so widths and advances line up with the decoded text.
        int cidLen = (pdfFont != null && pdfFont.isComposite()) ? 2 : 1;
        org.aspose.pdf.engine.font.ttf.TrueTypeReader ckReader =
                codeKeyedReaderFor(pdfFont, jdkFont);
        org.aspose.pdf.engine.font.CIDFont gidFont = compositeGidFontFor(pdfFont, jdkFont);
        org.aspose.pdf.engine.font.TrueTypeFont embeddedTt = embeddedTrueTypeFor(pdfFont, jdkFont);
        int[] cidCffGid = compositeCffCidToGidFor(pdfFont, jdkFont);

        int textIdx = 0;
        for (int i = 0; i + cidLen <= rawBytes.length; i += cidLen) {
            int charCode = (cidLen == 2)
                    ? (((rawBytes[i] & 0xFF) << 8) | (rawBytes[i + 1] & 0xFF))
                    : (rawBytes[i] & 0xFF);

            String ch = null;
            if (textIdx < text.length()) {
                int cp = text.codePointAt(textIdx);
                ch = new String(Character.toChars(cp));
                textIdx += Character.charCount(cp);
            }
            int ckGid = 0;
            java.awt.geom.GeneralPath glyphOutline = null;
            if (cidLen == 1) {
                if (embeddedTt != null) {
                    // Simple TrueType drawn with its own embedded program: select
                    // and fill the glyph outline (§9.6.6.4 lookup). java.awt.Font
                    // renders ".notdef" boxes for subset programs whose cmap it
                    // mishandles even when canDisplay reports the char available
                    // (corpus 46679 dotted-leader colon).
                    glyphOutline = embeddedTt.glyphOutlineForCode(charCode);
                } else if (ckReader != null) {
                    // Code-keyed subset cmap: select the glyph by RAW code,
                    // and draw it by glyph id — codes like 0x0D land on
                    // control characters that drawString silently skips.
                    ckGid = ckReader.getGlyphId(charCode);
                    if (ckGid == 0) ckGid = ckReader.getGlyphId(0xF000 | charCode);
                } else if (cffSimpleGid != null
                        && (ch == null || ch.isEmpty() || !jdkFont.canDisplay(ch.codePointAt(0)))) {
                    // Simple CFF (Type1C) font with a custom /Differences encoding:
                    // the decoded Unicode isn't in the synthetic OTF cmap, so
                    // drawString would emit a .notdef box. Resolve the glyph id
                    // straight from the encoding (charcode → name → CFF gid) and
                    // draw it by id below (PDFNEWNET_38043 Arabic Hacen subsets).
                    int g = (charCode >= 0 && charCode < 256) ? cffSimpleGid[charCode] : -1;
                    if (g > 0) {
                        ckGid = g;
                    } else {
                        ch = remapForSymbolicCmap(jdkFont, ch, charCode);
                    }
                } else {
                    // Symbolic TrueType fonts with a (3,0)-only cmap (§9.6.6.4)
                    ch = remapForSymbolicCmap(jdkFont, ch, charCode);
                }
            } else if (gidFont != null) {
                // Identity-H CIDFontType2 drawn with its embedded program:
                // the CID addresses a glyph directly. Draw by glyph id —
                // re-drawing the ToUnicode text loses glyph variants and
                // complex-script shaping (corpus 29111: pre-shaped Arabic
                // presentation forms came out as isolated letterforms).
                ckGid = gidFont.toGlyphId(charCode);
            } else if (cidCffGid != null) {
                // Identity-H CIDFontType0 (CID-keyed CFF): the CID selects the
                // charstring via the inverse charset. Routing the decoded
                // Unicode through drawString picks glyphs from the synthetic
                // OTF cmap built for SIMPLE fonts, which is empty/garbage for
                // composite CFFs (corpus 33408: body text as stray marks,
                // PDFKITNET-21900: "CHAPTER I" missing).
                ckGid = (charCode >= 0 && charCode < cidCffGid.length)
                        ? cidCffGid[charCode] : -1;
            }

            // Prefer the embedded glyph outline for CIDFontType2 (corpus APS/37100,
            // where java.awt silently substitutes Arial for cmap-less subsets) —
            // the outline (em-normalised, Y-up) is filled under the TRM directly.
            if (glyphOutline == null && gidFont != null && ckGid >= 0) {
                glyphOutline = gidFont.glyphOutline(ckGid);
            }
            boolean drawGv = glyphOutline == null && ckGid > 0
                    && (ckReader != null || cffSimpleGid != null || cidCffGid != null);
            boolean drawStr = glyphOutline == null && !drawGv && ch != null && !ch.isEmpty();

            if (!invisible && (glyphOutline != null || drawGv || drawStr)) {
                Matrix textState = new Matrix(fontSize * hScale, 0, 0, fontSize, 0, rise);
                Matrix trm = textState.multiply(state.getTextMatrix()).multiply(ctm);

                AffineTransform savedTransform = g2d.getTransform();
                try {
                    AffineTransform trmTransform = new AffineTransform(
                            trm.getA(), trm.getB(),
                            trm.getC(), trm.getD(),
                            trm.getE(), trm.getF());
                    g2d.transform(trmTransform);

                    g2d.setComposite(BlendComposite.fillComposite(state));
                    g2d.setColor(state.getFillColor());
                    if (glyphOutline != null) {
                        // glyf outline is already em-normalised and Y-up, matching
                        // PDF text space — no extra Y flip needed.
                        g2d.fill(glyphOutline);
                        darkenStems(g2d, glyphOutline);
                    } else {
                        g2d.scale(1, -1);
                        if (drawGv) {
                            java.awt.font.GlyphVector gv = jdkFont.createGlyphVector(
                                    g2d.getFontRenderContext(), new int[]{ckGid});
                            if (!darkenGlyphVector(g2d, gv)) {
                                g2d.drawGlyphVector(gv, 0, 0);
                            }
                        } else if (!darkenString(g2d, jdkFont, ch)) {
                            g2d.setFont(jdkFont);
                            g2d.drawString(ch, 0, 0);
                        }
                    }
                } finally {
                    g2d.setTransform(savedTransform);
                }
            }

            // Advance by PDF font metrics only (no JDK metrics — avoids accumulation error)
            double glyphWidth;
            if (pdfFont != null) {
                glyphWidth = pdfFont.getWidth(charCode) / 1000.0;
            } else {
                glyphWidth = 0.6;
            }
            double advance = (glyphWidth * fontSize + charSpacing) * hScale;
            if (charCode == 32) {
                advance += wordSpacing * hScale;
            }
            advanceTextMatrix(state, advance);
        }
    }

    /**
     * Remaps characters into the U+F000–U+F0FF private-use range for symbolic
     * TrueType fonts whose only cmap subtable is (3,0) Microsoft Symbol.
     * <p>
     * ISO 32000 §9.6.6.4: glyph lookup in a symbolic TrueType font tries the
     * (3,0) cmap with the character code directly, then with {@code 0xF000 + code}.
     * Subset fonts produced by symbol-era tools map their glyphs only at
     * F000+code, so Java's {@code drawString} resolves the decoded Unicode
     * ('A' = U+0041) to .notdef. When the font cannot display the decoded
     * character but can display its PUA twin, substitute the PUA character.
     * Fonts with a working Unicode cmap pass {@code canDisplay} and are
     * returned unchanged.
     * </p>
     *
     * @param font     the JDK font the text will be drawn with
     * @param ch       the decoded character (single code point as a String)
     * @param charCode the raw character code from the content stream
     * @return the character to draw — {@code ch}, or its U+F0xx substitute
     */
    public static String remapForSymbolicCmap(Font font, String ch, int charCode) {
        if (font == null || ch == null || ch.length() != 1 || charCode > 0xFF) {
            return ch;
        }
        char c = ch.charAt(0);
        if (!font.canDisplay(c)) {
            char pua = (char) (0xF000 | charCode);
            if (font.canDisplay(pua)) {
                return String.valueOf(pua);
            }
        }
        return ch;
    }

    /**
     * Returns true when glyph selection for this font must use the RAW
     * character code instead of the ToUnicode-decoded character.
     * <p>
     * Office-suite subset fonts (e.g. {@code EAAAAA+TimesNewRomanPSMT} in
     * corpus 30151) are symbolic TrueType fonts without /Encoding whose
     * embedded cmap is keyed by the PDF character codes (1, 2, 3, …) — not
     * by Unicode. Drawing the decoded character through such a cmap picks an
     * unrelated glyph: code 0x48 ('H' decoded) hits whatever subset glyph
     * happens to sit at 0x48, and {@code canDisplay} can't catch it because
     * the lookup "succeeds". ISO 32000-1:2008 §9.6.6.4 mandates code-based
     * lookup for symbolic TrueType fonts; apply it whenever the embedded
     * program ships no true-Unicode cmap subtable and the glyphs are drawn
     * with the embedded font itself (a substituted system font is
     * Unicode-keyed, so decoded text is correct there).
     * </p>
     */
    private static org.aspose.pdf.engine.font.ttf.TrueTypeReader codeKeyedReaderFor(
            PdfFont pdfFont, Font jdkFont) {
        if (!(pdfFont instanceof org.aspose.pdf.engine.font.TrueTypeFont) || jdkFont == null) {
            return null;
        }
        org.aspose.pdf.engine.font.TrueTypeFont ttFont =
                (org.aspose.pdf.engine.font.TrueTypeFont) pdfFont;
        if (ttFont.hasExplicitEncoding()) return null;
        org.aspose.pdf.engine.font.FontDescriptor fd = ttFont.getFontDescriptor();
        if (fd == null || !fd.isSymbolic()) return null;
        org.aspose.pdf.engine.font.ttf.TrueTypeReader reader = ttFont.getTrueTypeReader();
        if (reader == null || reader.hasUnicodeCmap() || reader.getCmapEntries().isEmpty()) {
            return null;
        }
        // Only valid when drawing with the embedded program itself — a
        // substituted system font is Unicode-keyed and the decoded text
        // already renders correctly there.
        if (org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont) != jdkFont) {
            return null;
        }
        return reader;
    }

    /**
     * Returns the descendant CIDFont when this composite font's CIDs can be
     * drawn as glyph ids with the embedded program: Identity-H/V encoding,
     * CIDFontType2 descendant (CID → GID via /CIDToGIDMap), and the glyphs
     * are actually drawn with the embedded font. Null otherwise.
     */
    private static org.aspose.pdf.engine.font.CIDFont compositeGidFontFor(
            PdfFont pdfFont, Font jdkFont) {
        if (!(pdfFont instanceof org.aspose.pdf.engine.font.Type0Font) || jdkFont == null) {
            return null;
        }
        org.aspose.pdf.engine.font.Type0Font t0 =
                (org.aspose.pdf.engine.font.Type0Font) pdfFont;
        if (!t0.isIdentityEncoding()) return null;
        org.aspose.pdf.engine.font.CIDFont descendant = t0.getDescendantFont();
        if (descendant == null || !descendant.isType2()) return null;
        Font loaded = org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont);
        if (loaded != null) {
            // Embedded program is what java.awt draws — glyph-id addressing is
            // only valid when the drawn font IS the embedded one.
            return loaded == jdkFont ? descendant : null;
        }
        // java.awt.Font rejected the embedded program (subset with no cmap/name
        // or truncated hmtx — corpus 00129: GS-generated "Helvetica" whose glyf
        // actually houses serif outlines), so jdkFont is a name-substituted
        // family with the wrong glyphs. Our own TrueTypeReader still parses
        // glyf/loca — draw the embedded outlines directly, matching Acrobat.
        return descendant.hasGlyphOutlines() ? descendant : null;
    }

    /**
     * Returns the CID→glyph-id map when this composite font's descendant is a
     * CIDFontType0 (CFF in {@code FontFile3}), the encoding is Identity-H/V,
     * and the glyphs are drawn with the embedded program — the only case where
     * glyph-id addressing into the synthetic OTF is valid. Null otherwise.
     */
    private static int[] compositeCffCidToGidFor(PdfFont pdfFont, Font jdkFont) {
        if (!(pdfFont instanceof org.aspose.pdf.engine.font.Type0Font) || jdkFont == null) {
            return null;
        }
        org.aspose.pdf.engine.font.Type0Font t0 =
                (org.aspose.pdf.engine.font.Type0Font) pdfFont;
        if (!t0.isIdentityEncoding()) return null;
        org.aspose.pdf.engine.font.CIDFont descendant = t0.getDescendantFont();
        if (descendant == null || descendant.isType2()) return null;
        if (org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont) != jdkFont) {
            return null;
        }
        return org.aspose.pdf.engine.font.cff.CFFFontLoader.compositeCffCidToGid(pdfFont);
    }

    /**
     * Returns the simple {@link org.aspose.pdf.engine.font.TrueTypeFont} when its
     * glyphs should be drawn from the embedded program by outline: a parsed
     * embedded TrueType program is present and the glyphs are actually drawn with
     * it (not a substituted system font). Null otherwise.
     * <p>
     * Outline drawing sidesteps {@code java.awt.Font}, which renders the default
     * ".notdef" box for subset programs whose {@code cmap} it mishandles even
     * when {@code canDisplay} returns true (corpus 46679). A substituted system
     * font is Unicode-keyed and reliable, so it keeps the {@code drawString} path.
     */
    private static org.aspose.pdf.engine.font.TrueTypeFont embeddedTrueTypeFor(
            PdfFont pdfFont, Font jdkFont) {
        if (!(pdfFont instanceof org.aspose.pdf.engine.font.TrueTypeFont) || jdkFont == null) {
            return null;
        }
        org.aspose.pdf.engine.font.TrueTypeFont tt =
                (org.aspose.pdf.engine.font.TrueTypeFont) pdfFont;
        if (tt.getTrueTypeReader() == null) return null;
        // Only when drawing with the embedded program itself.
        if (org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont) != jdkFont) {
            return null;
        }
        return tt;
    }

    /**
     * Returns the charcode→gid map for a simple (non-composite) font whose
     * outlines come from an embedded CFF ({@code FontFile3}) and that is being
     * drawn with that embedded program — otherwise {@code null}.
     * <p>
     * Such fonts otherwise reach {@code drawString}, which resolves the decoded
     * Unicode through the synthetic OTF cmap and renders {@code .notdef} boxes
     * for custom {@code /Differences} encodings (Arabic Hacen subsets in
     * PDFNEWNET_38043). The map lets the per-glyph loop draw by glyph id
     * instead — but only as a fallback when {@code java.awt.Font#canDisplay}
     * reports the decoded char unavailable, so Latin CFF fonts that already
     * render correctly keep their {@code drawString} path unchanged.
     */
    private static int[] cffSimpleCodeToGidFor(PdfFont pdfFont, Font jdkFont) {
        if (pdfFont == null || jdkFont == null || pdfFont.isComposite()) return null;
        if (org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont) != jdkFont) {
            return null;
        }
        return org.aspose.pdf.engine.font.cff.CFFFontLoader.simpleCffCodeToGid(pdfFont);
    }

    /**
     * Whole-string variant of {@link #remapForSymbolicCmap(Font, String, int)}
     * for the single-run path, where {@code text.length() == rawBytes.length}
     * is guaranteed by {@link #canDrawAsSingleRun}.
     */
    private static String remapForSymbolicCmap(Font font, String text, byte[] rawBytes) {
        StringBuilder sb = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char out = c;
            if (!font.canDisplay(c)) {
                char pua = (char) (0xF000 | (rawBytes[i] & 0xFF));
                if (font.canDisplay(pua)) {
                    out = pua;
                }
            }
            if (out != c && sb == null) {
                sb = new StringBuilder(text.length()).append(text, 0, i);
            }
            if (sb != null) {
                sb.append(out);
            }
        }
        return sb != null ? sb.toString() : text;
    }

    /**
     * True if {@code jdkFont} cannot display at least one code point of
     * {@code text} — the signal that a simple CFF font's custom encoding would
     * make {@code drawString} emit a {@code .notdef} box, so the per-glyph
     * draw-by-id path should be used instead.
     */
    private static boolean hasUndisplayableChar(Font jdkFont, String text) {
        if (jdkFont == null || text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!jdkFont.canDisplay(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private boolean canDrawAsSingleRun(GraphicsState state, String text, byte[] rawBytes) {
        if (text == null || text.isEmpty() || rawBytes == null) return false;
        if (rawBytes.length != text.length()) return false;
        if (Math.abs(state.getCharSpacing()) >= 0.0001) return false;
        if (Math.abs(state.getWordSpacing()) >= 0.0001) return false;
        // The single-run path walks rawBytes one byte at a time and looks up
        // widths per-byte — only correct for simple fonts. A composite (Type0)
        // font that happens to produce rawBytes.length == text.length() (e.g.
        // pure supplementary-plane text where one codepoint = 2-char surrogate
        // pair = 2 CID bytes) would otherwise slip through here and advance
        // by /DW per byte instead of per CID.
        for (int i = 0; i < text.length(); i++) {
            if (Character.isHighSurrogate(text.charAt(i))) return false;
        }
        return true;
    }

    private void drawSingleRun(Graphics2D g2d, GraphicsState state, String text,
                               byte[] rawBytes, PdfFont pdfFont, Font jdkFont) {
        double fontSize = state.getFontSize();
        double hScale = state.getHorizontalScaling() / 100.0;
        double rise = state.getTextRise();
        int renderMode = state.getTextRenderingMode();
        if (renderMode != 3) {
            Matrix ctm = state.getCTM();
            Matrix textState = new Matrix(fontSize * hScale, 0, 0, fontSize, 0, rise);
            Matrix trm = textState.multiply(state.getTextMatrix()).multiply(ctm);

            AffineTransform savedTransform = g2d.getTransform();
            try {
                AffineTransform trmTransform = new AffineTransform(
                        trm.getA(), trm.getB(),
                        trm.getC(), trm.getD(),
                        trm.getE(), trm.getF());
                g2d.transform(trmTransform);
                g2d.scale(1, -1);

                g2d.setComposite(BlendComposite.fillComposite(state));
                g2d.setColor(state.getFillColor());
                // Symbolic TrueType fonts with a (3,0)-only cmap (§9.6.6.4)
                String run = remapForSymbolicCmap(jdkFont, text, rawBytes);
                if (!darkenString(g2d, jdkFont, run)) {
                    g2d.setFont(jdkFont);
                    g2d.drawString(run, 0, 0);
                }
            } finally {
                g2d.setTransform(savedTransform);
            }
        }

        double totalAdvance = 0;
        for (byte rawByte : rawBytes) {
            int charCode = rawByte & 0xFF;
            double glyphWidth = pdfFont != null ? pdfFont.getWidth(charCode) / 1000.0 : 0.6;
            totalAdvance += glyphWidth * fontSize * hScale;
        }
        advanceTextMatrix(state, totalAdvance);
    }

    /**
     * Acrobat print-parity stem darkening ({@code -Drender.acrobatPrintParity=true},
     * harness-only; a no-op otherwise).
     * <p>
     * Acrobat (and the GDI print path that produces the visual-compare golds)
     * rasterises glyphs with hinting/stem darkening, so small text comes out
     * noticeably heavier than a plain anti-aliased outline fill — Java2D fills
     * an 8pt stem at ~1 px of pale grey where Acrobat paints ~2 px near-solid.
     * Approximate that by re-tracing the just-filled outline with a hairline
     * stroke of constant <em>device-pixel</em> width, which fattens every glyph
     * uniformly regardless of the current text-space scale.
     * </p>
     *
     * @param g2d     graphics in the glyph's user space (fill colour/composite set)
     * @param outline the outline that was just filled, in current user space
     */
    private static void darkenStems(Graphics2D g2d, Shape outline) {
        if (!Boolean.getBoolean("render.acrobatPrintParity") || outline == null) return;
        AffineTransform t = g2d.getTransform();
        double scale = Math.sqrt(Math.abs(t.getDeterminant()));
        if (scale <= 1e-9) return;
        float w = (float) (STEM_DARKEN_DEVICE_PX / scale);
        Stroke saved = g2d.getStroke();
        g2d.setStroke(new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2d.draw(outline);
        g2d.setStroke(saved);
    }

    /**
     * Print-parity variant of {@code drawGlyphVector}: fills the vector's own
     * outline and re-traces it. Filling the SAME shape that gets stroked keeps
     * the two perfectly registered — stroking on top of {@code drawString}/
     * {@code drawGlyphVector} output ghosts, because the JDK rasteriser
     * positions glyphs with its own (hinted, integer-advance) metrics that
     * differ sub-pixel from the outline returned by {@code getOutline}.
     *
     * @return true if the glyphs were painted here (parity mode on)
     */
    private static boolean darkenGlyphVector(Graphics2D g2d, java.awt.font.GlyphVector gv) {
        if (!Boolean.getBoolean("render.acrobatPrintParity")) return false;
        Shape outline = gv.getOutline(0, 0);
        g2d.fill(outline);
        darkenStems(g2d, outline);
        return true;
    }

    /** {@link #darkenGlyphVector} for text that would go through drawString. */
    private static boolean darkenString(Graphics2D g2d, Font font, String text) {
        if (!Boolean.getBoolean("render.acrobatPrintParity")
                || text == null || text.isEmpty()) return false;
        return darkenGlyphVector(g2d,
                font.createGlyphVector(g2d.getFontRenderContext(), text));
    }

    /** Total added stroke width, in device pixels, for print-parity stem darkening. */
    private static final double STEM_DARKEN_DEVICE_PX =
            Double.parseDouble(System.getProperty("render.printParityStemDarkenPx", "0.6"));

    private void adjustTextPosition(GraphicsState state, double displacement) {
        double fontSize = state.getFontSize();
        double hScale = state.getHorizontalScaling() / 100.0;
        double tx = displacement * fontSize * hScale;
        advanceTextMatrix(state, tx);
    }

    private void advanceTextMatrix(GraphicsState state, double tx) {
        Matrix advance = new Matrix(1, 0, 0, 1, tx, 0);
        state.setTextMatrixDirect(advance.multiply(state.getTextMatrix()));
    }

    private PdfFont resolveFont(String fontName, Resources resources, PDFParser parser) {
        if (fontName == null || resources == null) return null;
        PdfDictionary fontsDict = resources.getFonts();
        if (fontsDict == null) return null;
        try {
            return fontRepo.getFont(fontsDict, fontName, parser);
        } catch (IOException e) {
            LOG.fine(() -> "Failed to resolve font " + fontName + ": " + e.getMessage());
            return null;
        }
    }

    private String decodeText(PdfFont font, byte[] rawBytes) {
        if (font == null) {
            return new String(rawBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        try {
            return font.decode(rawBytes);
        } catch (IOException e) {
            LOG.fine(() -> "Font decode failed: " + e.getMessage());
            return new String(rawBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }
    }

    /**
     * Maps a PDF font to a JDK substitute font, horizontally scaled to match
     * the PDF font's glyph widths.
     * <p>
     * Computes the average width ratio (PDF / JDK) across ASCII printable chars
     * and applies it as a horizontal scale via {@link Font#deriveFont(AffineTransform)}.
     * This makes JDK glyphs fit within the PDF advance slots with natural gaps,
     * while the advance stays strictly based on PDF metrics (no accumulation error).
     * </p>
     */
    private Font mapToJdkFont(PdfFont pdfFont) {
        return mapToJdkFont(pdfFont, null);
    }

    /**
     * @param fallbackName the font name from /Tf when {@code pdfFont} is null
     *                     (e.g. an annotation appearance stream referencing a
     *                     font like /Verdana that isn't in the form's empty
     *                     /Resources). Used to look up a system font by name.
     */
    private Font mapToJdkFont(PdfFont pdfFont, String fallbackName) {
        // Cache key: font-dict identity. Two PdfFont instances can share a
        // base-font NAME (e.g. T1_0 Type1C and TT0 TrueType in 31836 both name
        // "XYCBJQ+PerpetuaTitlingMT-Light") but have different /Subtype,
        // /Encoding, and embedded font programs. Keying on name alone hands
        // the wrong loaded Font to the second resource and its glyphs come
        // out as .notdef boxes.
        String cacheKey;
        if (pdfFont == null) {
            cacheKey = fallbackName != null ? "name:" + fallbackName : "__default__";
        } else if (pdfFont.getFontDictionary() != null) {
            cacheKey = "fd@" + System.identityHashCode(pdfFont.getFontDictionary());
        } else {
            cacheKey = pdfFont.getBaseFont() != null ? pdfFont.getBaseFont() : "__default__";
        }
        Font cached = scaledFontCache.get(cacheKey);
        if (cached != null) return cached;

        // Preferred path — embedded CFF (Type1C / CIDFontType0C / OpenType-CFF)
        // wrapped in a synthetic OpenType container. Uses the PDF's own glyph
        // outlines, encoding, and widths; falls back to family substitution
        // below if the font has no FontFile3 or the CFF can't be parsed.
        Font embedded = org.aspose.pdf.engine.font.cff.CFFFontLoader.load(pdfFont);
        if (embedded != null) {
            scaledFontCache.put(cacheKey, embedded);
            return embedded;
        }

        String jdkFamily = "SansSerif";
        int style = Font.PLAIN;

        // Pick the name to resolve. Prefer the embedded PdfFont's PostScript
        // base name (more specific); otherwise use the literal /Tf name from
        // the content stream (annotation appearance streams often reference
        // /Verdana or /Cour without resolvable resources).
        String baseName = null;
        if (pdfFont != null && pdfFont.getBaseFont() != null) {
            baseName = pdfFont.getBaseFont();
        } else if (fallbackName != null && !fallbackName.isEmpty()) {
            baseName = fallbackName;
        }
        if (baseName != null) {
            if (baseName.length() > 7 && baseName.charAt(6) == '+') {
                baseName = baseName.substring(7);
            }

            String mapped = FONT_SUBSTITUTION.get(baseName);
            if (mapped != null) {
                jdkFamily = mapped;
            } else {
                // Try the system font registry by base name first — this picks
                // up Verdana/Cour/Tahoma/etc. that Windows ships natively. PDF
                // appearance streams (FreeText annotations, form fields) often
                // reference these without embedding, expecting the OS to have
                // them. Match the PostScript name OR a "/Cour" → "Courier New"
                // -style abbreviation.
                String resolved = resolveSystemFontName(baseName);
                if (resolved != null) {
                    jdkFamily = resolved;
                } else {
                    String lower = baseName.toLowerCase();
                    if (lower.contains("courier") || lower.contains("mono")) {
                        jdkFamily = "Monospaced";
                    } else if (lower.contains("times") || lower.contains("serif")) {
                        jdkFamily = "Serif";
                    }
                }
            }

            String lower = baseName.toLowerCase();
            if (lower.contains("bold") && (lower.contains("italic") || lower.contains("oblique"))) {
                style = Font.BOLD | Font.ITALIC;
            } else if (lower.contains("bold")) {
                style = Font.BOLD;
            } else if (lower.contains("italic") || lower.contains("oblique")) {
                style = Font.ITALIC;
            }
        }

        Font baseFont = new Font(jdkFamily, style, 1);

        // Compute horizontal scale: ratio of PDF average width to JDK average width.
        // This makes JDK glyphs proportionally match the PDF advance widths.
        double hScaleFactor = computeWidthScale(pdfFont, baseFont);
        Font result;
        if (hScaleFactor > 0.5 && hScaleFactor < 1.5 && Math.abs(hScaleFactor - 1.0) > 0.005) {
            result = baseFont.deriveFont(AffineTransform.getScaleInstance(hScaleFactor, 1.0));
        } else {
            result = baseFont;
        }

        scaledFontCache.put(cacheKey, result);
        return result;
    }

    /**
     * Computes the horizontal scale factor to apply to a JDK font so that its
     * average glyph width matches the PDF font's average glyph width.
     *
     * @return scale factor (PDF_avg / JDK_avg), or 1.0 if not computable
     */
    private double computeWidthScale(PdfFont pdfFont, Font jdkFont) {
        if (pdfFont == null) return 1.0;

        double pdfTotal = 0;
        double jdkTotal = 0;
        int count = 0;

        // Sample ASCII printable characters (space through tilde)
        for (int code = 33; code < 127; code++) {
            double pdfW = pdfFont.getWidth(code) / 1000.0;
            // Skip characters with default/missing width (0 or 1.0 = full em)
            if (pdfW <= 0.01 || pdfW >= 0.999) continue;

            String ch = String.valueOf((char) code);
            double jdkW = jdkFont.getStringBounds(ch, MEASURE_FRC).getWidth();
            if (jdkW <= 0.01) continue;

            pdfTotal += pdfW;
            jdkTotal += jdkW;
            count++;
        }

        if (count >= 10 && jdkTotal > 0) {
            return pdfTotal / jdkTotal;
        }
        return 1.0;
    }

    /** Cache of system font family names (lowercased) → canonical family string. */
    private static volatile java.util.Map<String, String> SYS_FONT_INDEX;

    private static java.util.Map<String, String> systemFontIndex() {
        java.util.Map<String, String> idx = SYS_FONT_INDEX;
        if (idx != null) return idx;
        idx = new java.util.HashMap<>();
        try {
            String[] families = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            for (String f : families) idx.put(f.toLowerCase(java.util.Locale.ROOT), f);
        } catch (Exception ignored) {}
        // Common short-name aliases used in PDF DA strings
        if (idx.containsKey("courier new")) idx.put("cour", idx.get("courier new"));
        if (idx.containsKey("times new roman")) idx.put("times", idx.get("times new roman"));
        if (idx.containsKey("arial")) {
            idx.put("helv", idx.get("arial"));   // common /Helv DA name
            idx.put("helvetica", idx.get("arial"));
        }
        SYS_FONT_INDEX = idx;
        return idx;
    }

    /**
     * Resolves a PDF base font name to a system font family if one matches.
     * Strips trailing weight/style suffixes ("-Bold", ",Italic" etc.) before
     * looking up.
     */
    private static String resolveSystemFontName(String baseName) {
        if (baseName == null || baseName.isEmpty()) return null;
        String stripped = baseName.replaceAll("[-,].*$", "");
        java.util.Map<String, String> idx = systemFontIndex();
        String hit = idx.get(stripped.toLowerCase(java.util.Locale.ROOT));
        if (hit != null) return hit;
        return idx.get(baseName.toLowerCase(java.util.Locale.ROOT));
    }

    private static double getNumber(PdfBase val) {
        if (val instanceof PdfInteger) return ((PdfInteger) val).intValue();
        if (val instanceof PdfFloat) return ((PdfFloat) val).doubleValue();
        return 0;
    }
}
