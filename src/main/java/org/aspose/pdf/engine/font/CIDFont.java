package org.aspose.pdf.engine.font;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.font.ttf.TrueTypeReader;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * CID font (ISO 32000-1:2008, §9.7.4).
 * <p>
 * Handles /Subtype /CIDFontType0 (CFF-based) and /CIDFontType2 (TrueType-based).
 * CID fonts are always used as descendant fonts of Type 0 (composite) fonts.
 * Parses the /W (width) array and /DW (default width) entries.
 * </p>
 */
public class CIDFont extends PdfFont {

    private static final Logger LOG = Logger.getLogger(CIDFont.class.getName());

    private double defaultWidth = 1000;
    private final Map<Integer, Double> cidWidths = new HashMap<>();

    /** Embedded TrueType program (/FontFile2), used to recover Unicode for extraction. */
    private TrueTypeReader ttReader;
    /** {@code Registry-Ordering} of /CIDSystemInfo (e.g. "Adobe-Korea1"), or null. */
    private String registryOrdering;
    /**
     * /CIDToGIDMap when it is an explicit stream (two big-endian bytes per CID).
     * {@code null} means the default {@code Identity} mapping (CID == GID).
     */
    private byte[] cidToGidMap;

    /**
     * Creates a CIDFont from a font dictionary.
     *
     * @param fontDict the CIDFont dictionary
     * @param parser   the PDF parser (may be null)
     * @throws IOException if reading font data fails
     */
    public CIDFont(PdfDictionary fontDict, PDFParser parser) throws IOException {
        super(fontDict, parser);

        // /DW default width
        PdfBase dwVal = fontDict.get("DW");
        if (dwVal != null) {
            defaultWidth = getNumber(dwVal);
        }

        // /W width array
        parseWidthArray();

        // Embedded font program + CIDToGIDMap, for Unicode recovery during
        // text extraction when no /ToUnicode CMap is present.
        initCidToGidMap();
        initEmbeddedFontProgram();

        // /CIDSystemInfo — a STANDARD collection (Adobe-Korea1, ...) fixes the
        // character meaning of every CID; the bundled ordering tables recover
        // Unicode when the font has neither /ToUnicode nor usable glyph names
        // (typical for CIDFontType0/CFF subsets).
        PdfBase csi = resolve(fontDict.get("CIDSystemInfo"));
        if (csi instanceof PdfDictionary) {
            PdfBase reg = resolve(((PdfDictionary) csi).get("Registry"));
            PdfBase ord = resolve(((PdfDictionary) csi).get("Ordering"));
            if (reg instanceof org.aspose.pdf.engine.pdfobjects.PdfString
                    && ord instanceof org.aspose.pdf.engine.pdfobjects.PdfString) {
                registryOrdering = ((org.aspose.pdf.engine.pdfobjects.PdfString) reg).getString()
                        + "-" + ((org.aspose.pdf.engine.pdfobjects.PdfString) ord).getString();
            }
        }

        LOG.fine(() -> "CIDFont created: " + baseFont + ", " + cidWidths.size() + " width entries");
    }

    /**
     * Recovers the Unicode code point for a CID using the embedded font program,
     * for the common case of a CIDFontType2 (TrueType) descendant that ships
     * neither a {@code /ToUnicode} CMap on its parent Type0 font. Maps
     * {@code CID → GID} (via {@code /CIDToGIDMap}, default Identity), then
     * {@code GID → Unicode} through the embedded {@code post} glyph names and the
     * true-Unicode cmap subtables.
     *
     * @param cid the character identifier (for Identity-H this is the 2-byte code)
     * @return the Unicode code point, or 0 if it cannot be recovered
     */
    public int cidToUnicode(int cid) {
        if (ttReader != null) {
            int gid = cidToGid(cid);
            // 1. Glyph name (post table) → Adobe Glyph List. Unambiguous when present.
            int u = unicodeFromGlyphName(ttReader.getGlyphName(gid));
            if (isReadable(u)) {
                return u;
            }
            // 2. True-Unicode cmap subtables only (avoids Mac platform code-point
            //    collisions such as Mac Roman 0xA4 vs Unicode U+00A7 section sign).
            u = ttReader.getUnicodeForGlyphIdPreferUnicode(gid);
            if (isReadable(u)) {
                return u;
            }
            // 3. Last resort: any reverse cmap entry (may be a non-Unicode platform).
            u = ttReader.getUnicodeForGlyphId(gid);
            if (isReadable(u)) {
                return u;
            }
        }
        // 4. Standard character collection: the /CIDSystemInfo registry-ordering
        //    defines the CID's meaning independently of the font program — the
        //    only recovery for a CIDFontType0/CFF subset without /ToUnicode.
        int u = org.aspose.pdf.engine.font.cmap.CidOrderingUnicode.lookup(registryOrdering, cid);
        if (isReadable(u)) {
            return u;
        }
        return 0;
    }

    private int cidToGid(int cid) {
        if (cidToGidMap != null) {
            int idx = cid * 2;
            if (idx + 1 < cidToGidMap.length) {
                return ((cidToGidMap[idx] & 0xFF) << 8) | (cidToGidMap[idx + 1] & 0xFF);
            }
            return 0;
        }
        // Identity CIDToGIDMap: CID == GID.
        return cid;
    }

    /**
     * Maps a CID to the embedded font program's glyph id via /CIDToGIDMap
     * (explicit stream or the default Identity). Public for glyph-level
     * rendering: a CIDFontType2 content stream addresses GLYPHS, so drawing
     * by glyph id preserves exactly what the producer laid out (e.g.
     * pre-shaped Arabic presentation forms — corpus 29111), where a decode
     * to Unicode + re-draw loses the shaping.
     *
     * @param cid the character id from the content stream
     * @return the glyph id (0 = .notdef / out of range)
     */
    public int toGlyphId(int cid) {
        return cidToGid(cid);
    }

    /**
     * Returns the em-normalised, Y-up outline of the given glyph id from the
     * embedded TrueType program, or {@code null} when no embedded program is
     * available or the glyph cannot be parsed. Drawing this outline directly
     * avoids {@code java.awt.Font}, which substitutes the default physical font
     * for cmap-less subset programs (corpus APS/37100: every glyph rendered as
     * the wrong Arial shape).
     *
     * @param gid the glyph id (see {@link #toGlyphId(int)})
     * @return the glyph outline, or {@code null}
     */
    public java.awt.geom.GeneralPath glyphOutline(int gid) {
        return ttReader != null ? ttReader.getGlyphPath(gid) : null;
    }

    /**
     * Returns true when the embedded TrueType program is present and carries
     * parsable outline tables, i.e. {@link #glyphOutline(int)} can serve glyphs.
     *
     * @return true when embedded glyph outlines are available
     */
    public boolean hasGlyphOutlines() {
        return ttReader != null && ttReader.hasGlyphOutlines();
    }

    /**
     * Returns true if the descendant subtype is CIDFontType2 (TrueType-housed,
     * glyph ids resolvable via /CIDToGIDMap).
     *
     * @return true for CIDFontType2
     */
    public boolean isType2() {
        return "CIDFontType2".equals(fontDict.getNameAsString("Subtype"));
    }

    private void initCidToGidMap() {
        PdfBase mapVal = resolve(fontDict.get("CIDToGIDMap"));
        if (mapVal instanceof PdfStream) {
            try {
                this.cidToGidMap = ((PdfStream) mapVal).getDecodedData();
            } catch (IOException e) {
                LOG.fine(() -> "Failed to read /CIDToGIDMap stream: " + e.getMessage());
            }
        }
        // PdfName "Identity" (or absent) → leave cidToGidMap null (CID == GID).
    }

    private void initEmbeddedFontProgram() {
        if (fontDescriptor == null) {
            return;
        }
        // CIDFontType2 embeds a TrueType program in /FontFile2. CIDFontType0
        // (CFF) uses /FontFile3 and is not handled here (returns 0 → caller falls
        // back to its previous behaviour).
        PdfStream fontFile = fontDescriptor.getFontFile2();
        if (fontFile == null) {
            return;
        }
        try {
            byte[] ttfData = fontFile.getDecodedData();
            if (ttfData != null && ttfData.length > 0) {
                this.ttReader = new TrueTypeReader(ttfData);
            }
        } catch (IOException e) {
            LOG.fine(() -> "Failed to parse embedded CIDFontType2 program: " + e.getMessage());
        }
    }

    /**
     * Resolves a PostScript glyph name to a Unicode code point via the Adobe
     * Glyph List, with the standard {@code uniXXXX} / {@code uXXXXX} fallbacks.
     * Mirrors the logic in {@code TrueTypeFont}; returns 0 if unrecognised.
     */
    private static int unicodeFromGlyphName(String name) {
        if (name == null || name.isEmpty() || ".notdef".equals(name)) {
            return 0;
        }
        int dot = name.indexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        int u = AdobeGlyphList.getUnicode(base);
        if (u > 0) {
            return u;
        }
        if (base.startsWith("uni") && base.length() == 7) {
            try { return Integer.parseInt(base.substring(3), 16); }
            catch (NumberFormatException ignored) {}
        }
        if (base.startsWith("u") && base.length() >= 5 && base.length() <= 7) {
            try { return Integer.parseInt(base.substring(1), 16); }
            catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    /** Filters out non-positive values and C0/C1 control codes. */
    private static boolean isReadable(int unicode) {
        if (unicode <= 0) {
            return false;
        }
        if (Character.isWhitespace(unicode)) {
            return true;
        }
        return !Character.isISOControl(unicode);
    }

    @Override
    public double getWidth(int cid) {
        Double w = cidWidths.get(cid);
        return w != null ? w : defaultWidth;
    }

    /**
     * Returns the default width (/DW).
     *
     * @return the default width
     */
    public double getDefaultWidth() {
        return defaultWidth;
    }

    /**
     * Parses the /W (widths) array.
     * <p>
     * Format: [cidFirst [w1 w2 ...]] or [cidFirst cidLast w]
     * </p>
     */
    private void parseWidthArray() {
        PdfBase wVal = resolve(fontDict.get("W"));
        if (!(wVal instanceof PdfArray)) return;
        PdfArray wArray = (PdfArray) wVal;

        int i = 0;
        while (i < wArray.size()) {
            PdfBase first = wArray.get(i);
            if (!(first instanceof PdfInteger)) { i++; continue; }
            int cidFirst = ((PdfInteger) first).intValue();

            if (i + 1 >= wArray.size()) break;
            PdfBase second = wArray.get(i + 1);

            if (second instanceof PdfArray) {
                // Format: cidFirst [w1 w2 w3 ...]
                PdfArray widths = (PdfArray) second;
                for (int j = 0; j < widths.size(); j++) {
                    cidWidths.put(cidFirst + j, getNumber(widths.get(j)));
                }
                i += 2;
            } else if (second instanceof PdfInteger) {
                // Format: cidFirst cidLast w
                if (i + 2 >= wArray.size()) break;
                int cidLast = ((PdfInteger) second).intValue();
                double width = getNumber(wArray.get(i + 2));
                for (int cid = cidFirst; cid <= cidLast; cid++) {
                    cidWidths.put(cid, width);
                }
                i += 3;
            } else {
                i++;
            }
        }
    }
}
