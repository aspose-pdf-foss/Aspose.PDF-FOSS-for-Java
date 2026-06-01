package org.aspose.pdf.engine.font;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSStream;
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
    public CIDFont(COSDictionary fontDict, PDFParser parser) throws IOException {
        super(fontDict, parser);

        // /DW default width
        COSBase dwVal = fontDict.get("DW");
        if (dwVal != null) {
            defaultWidth = getNumber(dwVal);
        }

        // /W width array
        parseWidthArray();

        // Embedded font program + CIDToGIDMap, for Unicode recovery during
        // text extraction when no /ToUnicode CMap is present.
        initCidToGidMap();
        initEmbeddedFontProgram();

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
        if (ttReader == null) {
            return 0;
        }
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

    private void initCidToGidMap() {
        COSBase mapVal = resolve(fontDict.get("CIDToGIDMap"));
        if (mapVal instanceof COSStream) {
            try {
                this.cidToGidMap = ((COSStream) mapVal).getDecodedData();
            } catch (IOException e) {
                LOG.fine(() -> "Failed to read /CIDToGIDMap stream: " + e.getMessage());
            }
        }
        // COSName "Identity" (or absent) → leave cidToGidMap null (CID == GID).
    }

    private void initEmbeddedFontProgram() {
        if (fontDescriptor == null) {
            return;
        }
        // CIDFontType2 embeds a TrueType program in /FontFile2. CIDFontType0
        // (CFF) uses /FontFile3 and is not handled here (returns 0 → caller falls
        // back to its previous behaviour).
        COSStream fontFile = fontDescriptor.getFontFile2();
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
        COSBase wVal = resolve(fontDict.get("W"));
        if (!(wVal instanceof COSArray)) return;
        COSArray wArray = (COSArray) wVal;

        int i = 0;
        while (i < wArray.size()) {
            COSBase first = wArray.get(i);
            if (!(first instanceof COSInteger)) { i++; continue; }
            int cidFirst = ((COSInteger) first).intValue();

            if (i + 1 >= wArray.size()) break;
            COSBase second = wArray.get(i + 1);

            if (second instanceof COSArray) {
                // Format: cidFirst [w1 w2 w3 ...]
                COSArray widths = (COSArray) second;
                for (int j = 0; j < widths.size(); j++) {
                    cidWidths.put(cidFirst + j, getNumber(widths.get(j)));
                }
                i += 2;
            } else if (second instanceof COSInteger) {
                // Format: cidFirst cidLast w
                if (i + 2 >= wArray.size()) break;
                int cidLast = ((COSInteger) second).intValue();
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
