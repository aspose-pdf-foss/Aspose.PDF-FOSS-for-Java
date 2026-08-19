package org.aspose.pdf.engine.font.cff;

import org.aspose.pdf.engine.font.FontDescriptor;
import org.aspose.pdf.engine.font.FontEncoding;
import org.aspose.pdf.engine.font.PdfFont;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.util.WeakIdentityHashMap;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads an AWT {@link java.awt.Font} from a PDF font that has an embedded
 * Type1C / CIDFontType0C / OpenType-CFF font program in its
 * {@code /FontDescriptor /FontFile3} stream.
 *
 * <p>Workflow:</p>
 * <ol>
 *   <li>Parse the CFF via {@link CFFParser} to learn glyph count and the
 *       glyph-id → glyph-name table</li>
 *   <li>Walk the PDF's {@code /Encoding} (charcode → glyph name) and map
 *       each charcode's Unicode codepoint to the CFF glyph id, producing the
 *       cmap input for {@link OpenTypeBuilder}</li>
 *   <li>Wrap the CFF in a synthetic OTF and hand the bytes to
 *       {@code Font.createFont(TRUETYPE_FONT, …)}</li>
 * </ol>
 */
public final class CFFFontLoader {

    private static final Logger LOG = Logger.getLogger(CFFFontLoader.class.getName());

    // These caches MUST key on object identity, not content. PdfDictionary
    // overrides equals/hashCode by content, and tools like Aspose.Words emit
    // PDFs with deterministic object numbering, so structurally identical font
    // dictionaries (e.g. the evaluation watermark subset) from two different
    // documents compare equal — a content-keyed cache then returns one
    // document's parsed font/glyph map for another's, corrupting glyphs
    // (e.g. space → 'L'). Identity keying gives each document's distinct
    // dictionary instance its own entry while intra-document reuse (the parser
    // hands back the same instance) still hits.
    //
    // The keys are held WEAKLY (WeakIdentityHashMap, not IdentityHashMap): a
    // plain IdentityHashMap pins every font dictionary it ever sees for the
    // life of the JVM, so a long-running convert/render loop over thousands of
    // documents accumulates one entry — plus a heavy AWT Font — per embedded
    // CFF font and eventually exhausts the heap. Weak keys let each closed
    // document's font dictionaries (and their cache entries) be reclaimed. The
    // values (Font / int[]) do not reference the key, so no entry is pinned by
    // its own value.

    /** Cache: font-dict instance → loaded AWT Font (or null on failure). */
    private static final WeakIdentityHashMap<Object, Font> CACHE =
            new WeakIdentityHashMap<>();

    /** Cache: font-dict instance → 256-entry charcode→CFF gid map for simple CFF fonts (or null). */
    private static final WeakIdentityHashMap<Object, int[]> CODE_TO_GID =
            new WeakIdentityHashMap<>();

    /** Cache: font-dict instance → CID→CFF-gid map for composite CFF fonts (or null). */
    private static final WeakIdentityHashMap<Object, int[]> CID_TO_GID =
            new WeakIdentityHashMap<>();

    private CFFFontLoader() {}

    /**
     * Returns the CID → glyph-id map for a composite font whose descendant is
     * a CID-keyed CFF ({@code /CIDFontType0} with {@code FontFile3}), or
     * {@code null} when the font is not composite-CFF or cannot be parsed.
     * <p>
     * In a CID-keyed CFF the charset maps gid → CID (Adobe TN#5176 §18); the
     * renderer needs the inverse to select glyphs for Identity-H CIDs. For a
     * name-keyed CFF used as a CIDFont descendant, CIDs address charstrings
     * directly (ISO 32000-1 §9.7.4.2), so the map is identity. Glyph ids index
     * the synthetic OTF built by {@link OpenTypeBuilder} (CFF gid order is
     * preserved), so they are valid for
     * {@link Font#createGlyphVector(java.awt.font.FontRenderContext, int[])}.
     */
    public static int[] compositeCffCidToGid(PdfFont pdfFont) {
        if (pdfFont == null || !pdfFont.isComposite()) return null;
        Object key = pdfFont.getFontDictionary();
        if (key == null) key = pdfFont;
        if (CID_TO_GID.containsKey(key)) return CID_TO_GID.get(key);

        int[] map = buildCompositeCffCidToGid(pdfFont);
        CID_TO_GID.put(key, map);
        return map;
    }

    private static int[] buildCompositeCffCidToGid(PdfFont pdfFont) {
        FontDescriptor fd = pdfFont.getFontDescriptor();
        if (fd == null || fd.getFontFile2() != null) return null;
        PdfStream fontFile3 = fd.getFontFile3();
        if (fontFile3 == null) return null;
        CFFParser cff;
        try {
            byte[] cffBytes = fontFile3.getDecodedData();
            if (cffBytes == null || cffBytes.length < 4) return null;
            cff = new CFFParser(cffBytes);
        } catch (IOException e) {
            LOG.fine(() -> "compositeCffCidToGid: CFF parse failed for "
                    + pdfFont.getBaseFont() + ": " + e);
            return null;
        }
        if (!cff.cidKeyed) {
            int[] identity = new int[cff.numGlyphs];
            for (int gid = 0; gid < cff.numGlyphs; gid++) identity[gid] = gid;
            return identity;
        }
        int maxCid = 0;
        for (int gid = 0; gid < cff.numGlyphs; gid++) {
            if (cff.charsetSids[gid] > maxCid) maxCid = cff.charsetSids[gid];
        }
        int[] map = new int[maxCid + 1];
        java.util.Arrays.fill(map, -1);
        for (int gid = 0; gid < cff.numGlyphs; gid++) {
            int cid = cff.charsetSids[gid];
            if (cid >= 0 && map[cid] < 0) map[cid] = gid;
        }
        return map;
    }

    /**
     * Returns a 256-entry array mapping each 1-byte character code to its glyph
     * id in the embedded CFF program, resolved through the PDF {@code /Encoding}
     * (charcode → glyph name → CFF charset gid). Entry is {@code -1} when the
     * code maps to no glyph.
     * <p>
     * Used by the renderer to draw a <em>simple</em> (non-composite) Type1/Type1C
     * font with an embedded {@code FontFile3} CFF directly by glyph id, instead
     * of routing the decoded Unicode through {@code java.awt.Font#drawString}.
     * The latter renders {@code .notdef} boxes when the PDF uses a custom
     * {@code /Differences} encoding whose glyph names carry Unicode values that
     * the synthetic OpenType cmap does not cover (e.g. Arabic Hacen subsets in
     * PDFNEWNET_38043). The CFF gid order is preserved by {@link OpenTypeBuilder},
     * so these ids index the synthetic OTF handed to {@code Font.createFont}.
     *
     * @param pdfFont the font; must be non-composite with an embedded CFF
     * @return the charcode→gid map, or {@code null} if not a simple CFF font or
     *         the CFF cannot be parsed
     */
    public static int[] simpleCffCodeToGid(PdfFont pdfFont) {
        if (pdfFont == null || pdfFont.isComposite()) return null;
        Object key = pdfFont.getFontDictionary();
        if (key == null) key = pdfFont;
        if (CODE_TO_GID.containsKey(key)) return CODE_TO_GID.get(key);

        int[] map = buildSimpleCffCodeToGid(pdfFont);
        CODE_TO_GID.put(key, map);
        return map;
    }

    private static int[] buildSimpleCffCodeToGid(PdfFont pdfFont) {
        FontDescriptor fd = pdfFont.getFontDescriptor();
        if (fd == null) return null;
        // Only simple fonts whose outlines come from an embedded CFF (FontFile3).
        // FontFile2 (TrueType) has its own draw-by-outline path; CID fonts are
        // composite and excluded above.
        if (fd.getFontFile2() != null) return null;
        PdfStream fontFile3 = fd.getFontFile3();
        if (fontFile3 == null) return null;

        CFFParser cff;
        try {
            byte[] cffBytes = fontFile3.getDecodedData();
            if (cffBytes == null || cffBytes.length < 4) return null;
            cff = new CFFParser(cffBytes);
        } catch (IOException e) {
            LOG.fine(() -> "simpleCffCodeToGid: CFF parse failed for " + pdfFont.getBaseFont() + ": " + e);
            return null;
        }

        Map<String, Integer> nameToGid = new HashMap<>(cff.numGlyphs * 2);
        for (int gid = 0; gid < cff.numGlyphs; gid++) {
            String n = cff.glyphNames[gid];
            if (n != null && !nameToGid.containsKey(n)) nameToGid.put(n, gid);
        }

        FontEncoding enc = pdfFont.getEncoding();
        int[] codeToGid = new int[256];
        java.util.Arrays.fill(codeToGid, -1);
        boolean any = false;
        for (int code = 0; code <= 255; code++) {
            String name = (enc != null) ? enc.getGlyphName(code) : null;
            if (name == null || name.isEmpty() || ".notdef".equals(name)) continue;
            Integer gid = nameToGid.get(name);
            if (gid != null && gid > 0) {
                codeToGid[code] = gid;
                any = true;
            }
        }
        return any ? codeToGid : null;
    }

    /**
     * Returns a Java {@code Font} backed by the embedded CFF, or {@code null}
     * if the font has no FontFile3 stream or parsing fails. The returned font
     * has size 1 pt; callers should derive a sized variant via
     * {@link Font#deriveFont}.
     */
    public static Font load(PdfFont pdfFont) {
        if (pdfFont == null) return null;
        Object key = pdfFont.getFontDictionary();
        if (key == null) key = pdfFont;
        if (CACHE.containsKey(key)) return CACHE.get(key);

        Font result = doLoad(pdfFont);
        CACHE.put(key, result);
        if (result != null) {
            LOG.fine(() -> "CFFFontLoader: loaded " + pdfFont.getBaseFont() + " → " + result.getFontName());
        }
        return result;
    }

    private static Font doLoad(PdfFont pdfFont) {
        FontDescriptor fd = pdfFont.getFontDescriptor();
        if (fd == null) return null;

        // Prefer FontFile2 (full TTF/OpenType program) when present — Java's
        // Font.createFont accepts it as-is, no synthetic wrapping needed.
        // PDF spec §9.9 says /FontFile2 carries the entire sfnt-housed font.
        PdfStream fontFile2 = fd.getFontFile2();
        if (fontFile2 != null) {
            try {
                byte[] ttf = fontFile2.getDecodedData();
                if (ttf != null && ttf.length > 4) {
                    return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(ttf));
                }
            } catch (FontFormatException | IOException e) {
                LOG.fine(() -> "FontFile2 load failed for " + pdfFont.getBaseFont() + ": " + e);
            }
        }

        PdfStream fontFile3 = fd.getFontFile3();
        if (fontFile3 == null) return null;

        byte[] cffBytes;
        try {
            cffBytes = fontFile3.getDecodedData();
        } catch (IOException e) {
            LOG.fine(() -> "CFFFontLoader: cannot read FontFile3 for " + pdfFont.getBaseFont() + ": " + e);
            return null;
        }
        if (cffBytes == null || cffBytes.length < 4) return null;

        CFFParser cff;
        try {
            cff = new CFFParser(cffBytes);
        } catch (IOException e) {
            LOG.fine(() -> "CFFFontLoader: parse failed for " + pdfFont.getBaseFont() + ": " + e);
            return null;
        }

        // Build name → gid reverse lookup
        Map<String, Integer> nameToGid = new HashMap<>(cff.numGlyphs * 2);
        for (int gid = 0; gid < cff.numGlyphs; gid++) {
            String n = cff.glyphNames[gid];
            if (n != null && !nameToGid.containsKey(n)) nameToGid.put(n, gid);
        }

        // Build Unicode → gid via PDF /Encoding
        Map<Integer, Integer> unicodeToGid = new HashMap<>();
        FontEncoding enc = pdfFont.getEncoding();
        for (int code = 0; code <= 255; code++) {
            String name;
            int unicode;
            if (enc != null) {
                name = enc.getGlyphName(code);
                unicode = enc.getUnicode(code);
            } else {
                name = null;
                unicode = code;
            }
            if (name == null || name.isEmpty() || ".notdef".equals(name)) continue;
            Integer gid = nameToGid.get(name);
            if (gid == null) {
                // Fall back to the Adobe Glyph List for names not in this font's charset
                continue;
            }
            if (unicode <= 0) {
                // Some encodings return 0 for unmapped codes. Use the charcode
                // itself as a placeholder Unicode value so drawString can route
                // through the cmap.
                unicode = code;
            }
            if (unicode >= 0xE000 && unicode <= 0xF8FF) {
                // private-use — fine
            }
            if (unicode > 0xFFFD) continue;
            unicodeToGid.putIfAbsent(unicode, gid);
        }

        // Always map space if available
        Integer spaceGid = nameToGid.get("space");
        if (spaceGid != null) unicodeToGid.putIfAbsent(0x20, spaceGid);

        // Build advance-width array (units 1/1000 em). First code wins, matching
        // the unicodeToGid cmap above: several codes can share one glyph name
        // (WinAnsi maps both 0x20 and 0xA0 nbsp to "space"), and a sharer that
        // falls outside the PDF /Widths range reports the 1000-unit default —
        // letting it overwrite turned every space into a full em (corpus 35126).
        int[] widths = new int[cff.numGlyphs];
        for (int code = 0; code <= 255; code++) {
            String name = (enc != null) ? enc.getGlyphName(code) : null;
            if (name == null) continue;
            Integer gid = nameToGid.get(name);
            if (gid == null || widths[gid] > 0) continue;
            double w = pdfFont.getWidth(code);
            if (w > 0) widths[gid] = (int) Math.round(w);
        }
        // Fill in a sensible default for glyphs that the PDF /Widths array doesn't cover
        int avg = 500;
        int filled = 0;
        long sum = 0;
        for (int w : widths) if (w > 0) { sum += w; filled++; }
        if (filled > 0) avg = (int) (sum / filled);
        for (int i = 0; i < widths.length; i++) if (widths[i] == 0) widths[i] = avg;

        OpenTypeBuilder.Inputs in = new OpenTypeBuilder.Inputs();
        in.cff = cff;
        in.nameToGid = nameToGid;
        in.unicodeToGid = unicodeToGid;
        in.advanceWidths = widths;
        in.displayName = stripSubsetPrefix(pdfFont.getBaseFont() != null ? pdfFont.getBaseFont() : cff.fontName);
        in.psName = in.displayName;

        byte[] otf;
        try {
            otf = OpenTypeBuilder.build(in);
        } catch (IOException e) {
            LOG.fine(() -> "CFFFontLoader: OTF build failed: " + e);
            return null;
        }
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(otf));
        } catch (FontFormatException | IOException e) {
            LOG.fine(() -> "CFFFontLoader: createFont rejected synthetic OTF for "
                    + pdfFont.getBaseFont() + ": " + e);
            return null;
        }
    }

    /** Removes the "XXXXXX+" subset prefix from a base font name, if present. */
    private static String stripSubsetPrefix(String name) {
        if (name == null) return null;
        if (name.length() > 7 && name.charAt(6) == '+') return name.substring(7);
        return name;
    }
}
