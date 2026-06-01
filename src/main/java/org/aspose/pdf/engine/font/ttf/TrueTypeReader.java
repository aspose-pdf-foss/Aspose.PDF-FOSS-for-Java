package org.aspose.pdf.engine.font.ttf;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads TrueType/OpenType font files (sfnt format).
 * <p>
 * Parses the following tables:
 * <ul>
 *   <li>{@code head} - global font metrics (unitsPerEm)</li>
 *   <li>{@code cmap} - character to glyph mapping (formats 4, 6, 12)</li>
 *   <li>{@code hmtx} - horizontal metrics (advance widths per glyph)</li>
 *   <li>{@code maxp} - maximum profile (numGlyphs)</li>
 *   <li>{@code name} - name records (optional, for debugging)</li>
 *   <li>{@code post} - PostScript names (optional)</li>
 *   <li>{@code OS/2} - additional metrics (optional)</li>
 * </ul>
 * </p>
 *
 * @see <a href="https://docs.microsoft.com/en-us/typography/opentype/spec/">OpenType spec</a>
 */
public class TrueTypeReader {

    private static final Logger LOG = Logger.getLogger(TrueTypeReader.class.getName());

    private final byte[] data;
    private int unitsPerEm = 1000;
    private int numGlyphs;
    private int numHMetrics;
    private int[] advanceWidths;     // indexed by glyph ID
    private Map<Integer, Integer> cmapTable;
    private Map<Integer, Integer> reverseCmapTable;
    /**
     * GID → Unicode reverse map populated ONLY from true-Unicode cmap subtables
     * (platform 3 / encoding 1 or 10, and platform 0). Mac (platform 1) subtables
     * carry platform-specific code points (e.g. Mac Roman 0xA4 == section sign)
     * that must NOT be treated as Unicode, so they are excluded here. Used by the
     * CID-font extraction path which needs an unambiguous Unicode value.
     */
    private Map<Integer, Integer> unicodeReverseCmap;
    /** Set per-subtable during {@link #parseCmap}; true for true-Unicode platforms. */
    private boolean currentSubtableIsUnicode;
    private String fontName;
    /** Glyph names indexed by glyph ID (from /post table). null until parsePost runs. */
    private String[] glyphNames;

    /**
     * Creates a TrueTypeReader from raw font data.
     *
     * @param data the raw sfnt/TrueType font bytes
     * @throws IOException if the font data is invalid
     */
    public TrueTypeReader(byte[] data) throws IOException {
        if (data == null || data.length < 12) {
            throw new IOException("Invalid TrueType font data: too short");
        }
        this.data = data;
        parse();
    }

    /**
     * Returns the font's unitsPerEm value from the head table.
     *
     * @return unitsPerEm (typically 1000 or 2048)
     */
    public int getUnitsPerEm() {
        return unitsPerEm;
    }

    /**
     * Returns the number of glyphs in the font.
     *
     * @return numGlyphs
     */
    public int getNumGlyphs() {
        return numGlyphs;
    }

    /**
     * Maps a character code to a glyph ID using the cmap table.
     *
     * @param charCode the character code (Unicode)
     * @return the glyph ID, or 0 (.notdef) if not found
     */
    public int getGlyphId(int charCode) {
        if (cmapTable == null) return 0;
        Integer gid = cmapTable.get(charCode);
        return gid != null ? gid : 0;
    }

    /**
     * Returns the first Unicode code point mapped to the given glyph ID.
     *
     * @param glyphId the glyph ID
     * @return the Unicode code point, or 0 if unknown
     */
    public int getUnicodeForGlyphId(int glyphId) {
        if (reverseCmapTable == null) return 0;
        Integer unicode = reverseCmapTable.get(glyphId);
        return unicode != null ? unicode : 0;
    }

    /**
     * Returns the Unicode code point for the given glyph ID using ONLY the
     * true-Unicode cmap subtables (see {@link #unicodeReverseCmap}). Prefer this
     * over {@link #getUnicodeForGlyphId(int)} when an unambiguous Unicode value
     * is required, because the general reverse map can be polluted by Mac
     * platform code points that collide with unrelated Unicode characters.
     *
     * @param glyphId the glyph ID
     * @return the Unicode code point, or 0 if no Unicode subtable maps it
     */
    public int getUnicodeForGlyphIdPreferUnicode(int glyphId) {
        if (unicodeReverseCmap == null) return 0;
        Integer unicode = unicodeReverseCmap.get(glyphId);
        return unicode != null ? unicode : 0;
    }

    /**
     * Returns the PostScript name for the glyph from the {@code /post} table,
     * or {@code null} if the post table was missing, used a format we don't
     * parse, or the glyph id is out of range. Used as a fall-back for subset
     * fonts that don't ship a {@code /ToUnicode} CMap and have a useless cmap
     * (charCode == glyphId): the glyph name resolves through the Adobe Glyph
     * List to a Unicode codepoint.
     *
     * @param glyphId zero-based glyph id
     * @return the glyph's PostScript name (e.g. {@code "C"}, {@code "germandbls"}),
     *         or {@code null}
     */
    public String getGlyphName(int glyphId) {
        if (glyphNames == null || glyphId < 0 || glyphId >= glyphNames.length) {
            return null;
        }
        return glyphNames[glyphId];
    }

    /**
     * Returns the advance width for the given glyph ID, in font units.
     *
     * @param glyphId the glyph ID
     * @return the advance width
     */
    public int getAdvanceWidth(int glyphId) {
        if (advanceWidths == null || advanceWidths.length == 0) return 0;
        if (glyphId < advanceWidths.length) {
            return advanceWidths[glyphId];
        }
        return advanceWidths[advanceWidths.length - 1];
    }

    /**
     * Returns the font name from the name table (if available).
     *
     * @return the font name, or null
     */
    public String getFontName() {
        return fontName;
    }

    /**
     * Returns an unmodifiable view of the Unicode-to-glyph cmap as a map.
     * Used by the writer side ({@code Type0FontBuilder}) to enumerate the
     * codepoints supported by the font when emitting a {@code /ToUnicode}
     * CMap and a {@code /W} width array.
     *
     * @return the cmap entries; empty map if the cmap table was absent
     */
    public java.util.Map<Integer, Integer> getCmapEntries() {
        if (cmapTable == null) return java.util.Collections.emptyMap();
        return java.util.Collections.unmodifiableMap(cmapTable);
    }

    private void parse() throws IOException {
        int numTables = readUInt16(4);

        Map<String, int[]> tables = new HashMap<>();
        for (int i = 0; i < numTables; i++) {
            int dirOffset = 12 + i * 16;
            if (dirOffset + 16 > data.length) break;
            String tag = readTag(dirOffset);
            int offset = readInt32(dirOffset + 8);
            int length = readInt32(dirOffset + 12);
            tables.put(tag, new int[]{offset, length});
        }

        if (tables.containsKey("head")) {
            parseHead(tables.get("head")[0]);
        }
        if (tables.containsKey("maxp")) {
            parseMaxp(tables.get("maxp")[0]);
        }
        if (tables.containsKey("hhea")) {
            parseHhea(tables.get("hhea")[0]);
        }
        if (tables.containsKey("hmtx")) {
            parseHmtx(tables.get("hmtx")[0], tables.get("hmtx")[1]);
        }
        if (tables.containsKey("cmap")) {
            parseCmap(tables.get("cmap")[0], tables.get("cmap")[1]);
        }
        if (tables.containsKey("name")) {
            try {
                parseName(tables.get("name")[0], tables.get("name")[1]);
            } catch (Exception e) {
                LOG.fine(() -> "Failed to parse name table: " + e.getMessage());
            }
        }
        if (tables.containsKey("post")) {
            try {
                parsePost(tables.get("post")[0], tables.get("post")[1]);
            } catch (Exception e) {
                LOG.fine(() -> "Failed to parse post table: " + e.getMessage());
            }
        }

        LOG.fine(() -> "TrueType parsed: unitsPerEm=" + unitsPerEm + ", numGlyphs=" + numGlyphs
                + ", cmap entries=" + (cmapTable != null ? cmapTable.size() : 0));
    }

    private void parseHead(int offset) {
        if (offset + 54 <= data.length) {
            unitsPerEm = readUInt16(offset + 18);
            if (unitsPerEm == 0) unitsPerEm = 1000;
        }
    }

    private void parseMaxp(int offset) {
        if (offset + 6 <= data.length) {
            numGlyphs = readUInt16(offset + 4);
        }
    }

    private void parseHhea(int offset) {
        if (offset + 36 <= data.length) {
            numHMetrics = readUInt16(offset + 34);
        }
    }

    private void parseHmtx(int offset, int length) {
        advanceWidths = new int[numGlyphs > 0 ? numGlyphs : Math.max(numHMetrics, 1)];
        for (int i = 0; i < numHMetrics && offset + i * 4 + 2 <= data.length; i++) {
            advanceWidths[i] = readUInt16(offset + i * 4);
        }
        if (numHMetrics > 0 && numGlyphs > numHMetrics) {
            int lastWidth = advanceWidths[numHMetrics - 1];
            for (int i = numHMetrics; i < numGlyphs; i++) {
                advanceWidths[i] = lastWidth;
            }
        }
    }

    private void parseCmap(int tableOffset, int tableLength) {
        cmapTable = new HashMap<>();
        reverseCmapTable = new HashMap<>();
        unicodeReverseCmap = new HashMap<>();
        if (tableOffset + 4 > data.length) return;

        int numSubtables = readUInt16(tableOffset + 2);
        int bestOffset = -1;
        int bestPriority = -1;
        int[] subtableOffsets = new int[numSubtables];
        int[] subtableFormats = new int[numSubtables];
        boolean[] subtableUnicode = new boolean[numSubtables];
        boolean bestIsUnicode = false;

        for (int i = 0; i < numSubtables; i++) {
            int recOffset = tableOffset + 4 + i * 8;
            if (recOffset + 8 > data.length) break;
            int platformId = readUInt16(recOffset);
            int encodingId = readUInt16(recOffset + 2);
            int subtableOffset = readInt32(recOffset + 4);
            int absoluteOffset = tableOffset + subtableOffset;
            subtableOffsets[i] = absoluteOffset;
            if (absoluteOffset >= 0 && absoluteOffset + 2 <= data.length) {
                subtableFormats[i] = readUInt16(absoluteOffset);
            } else {
                subtableFormats[i] = -1;
            }

            // True-Unicode subtables: Microsoft BMP/full (3,1)/(3,10) and the
            // Unicode platform (0). Mac (1,0) and Microsoft Symbol (3,0) carry
            // non-Unicode code points and are excluded from unicodeReverseCmap.
            boolean isUnicode = (platformId == 3 && (encodingId == 1 || encodingId == 10))
                    || platformId == 0;
            subtableUnicode[i] = isUnicode;

            int priority = -1;
            if (platformId == 3 && encodingId == 10) priority = 12;
            else if (platformId == 3 && encodingId == 1) priority = 11;
            else if (platformId == 3 && encodingId == 0) priority = 10;
            else if (platformId == 0) priority = 5 + encodingId;
            else if (platformId == 1 && encodingId == 0) priority = 1;

            if (priority > bestPriority) {
                bestPriority = priority;
                bestOffset = absoluteOffset;
                bestIsUnicode = isUnicode;
            }
        }

        if (bestOffset < 0 || bestOffset >= data.length) return;

        for (int i = 0; i < numSubtables; i++) {
            currentSubtableIsUnicode = subtableUnicode[i];
            parseCmapSubtable(subtableOffsets[i], subtableFormats[i], false);
        }
        currentSubtableIsUnicode = bestIsUnicode;
        parseCmapSubtable(bestOffset, readUInt16(bestOffset), true);
    }

    private void parseCmapSubtable(int offset, int format, boolean forPrimaryMap) {
        if (offset < 0 || offset >= data.length) return;
        switch (format) {
            case 0:
                parseCmapFormat0(offset, forPrimaryMap);
                break;
            case 4:
                parseCmapFormat4(offset, forPrimaryMap);
                break;
            case 6:
                parseCmapFormat6(offset, forPrimaryMap);
                break;
            case 12:
                parseCmapFormat12(offset, forPrimaryMap);
                break;
            default:
                if (forPrimaryMap) {
                    LOG.fine(() -> "Unsupported cmap format: " + format);
                }
        }
    }

    private void parseCmapFormat4(int offset, boolean forPrimaryMap) {
        if (offset + 14 > data.length) return;
        int segCountX2 = readUInt16(offset + 6);
        int segCount = segCountX2 / 2;

        int endCodesOffset = offset + 14;
        int startCodesOffset = endCodesOffset + segCountX2 + 2;
        int idDeltaOffset = startCodesOffset + segCountX2;
        int idRangeOffset = idDeltaOffset + segCountX2;

        for (int seg = 0; seg < segCount; seg++) {
            int endCode = readUInt16(endCodesOffset + seg * 2);
            int startCode = readUInt16(startCodesOffset + seg * 2);
            int idDelta = readInt16(idDeltaOffset + seg * 2);
            int idRangeOffsetVal = readUInt16(idRangeOffset + seg * 2);

            if (startCode == 0xFFFF) break;

            for (int charCode = startCode; charCode <= endCode; charCode++) {
                int glyphId;
                if (idRangeOffsetVal == 0) {
                    glyphId = (charCode + idDelta) & 0xFFFF;
                } else {
                    int glyphIndexOffset = idRangeOffset + seg * 2 + idRangeOffsetVal + (charCode - startCode) * 2;
                    if (glyphIndexOffset + 2 > data.length) continue;
                    glyphId = readUInt16(glyphIndexOffset);
                    if (glyphId != 0) {
                        glyphId = (glyphId + idDelta) & 0xFFFF;
                    }
                }
                if (glyphId != 0) {
                    registerCmapMapping(charCode, glyphId, forPrimaryMap);
                }
            }
        }
    }

    private void parseCmapFormat0(int offset, boolean forPrimaryMap) {
        if (offset + 262 > data.length) return;
        for (int charCode = 0; charCode < 256; charCode++) {
            int glyphId = data[offset + 6 + charCode] & 0xFF;
            if (glyphId != 0) {
                registerCmapMapping(charCode, glyphId, forPrimaryMap);
            }
        }
    }

    private void parseCmapFormat6(int offset, boolean forPrimaryMap) {
        if (offset + 10 > data.length) return;
        int firstCode = readUInt16(offset + 6);
        int entryCount = readUInt16(offset + 8);
        for (int i = 0; i < entryCount; i++) {
            int glyphOffset = offset + 10 + i * 2;
            if (glyphOffset + 2 > data.length) break;
            int glyphId = readUInt16(glyphOffset);
            if (glyphId != 0) {
                registerCmapMapping(firstCode + i, glyphId, forPrimaryMap);
            }
        }
    }

    private void parseCmapFormat12(int offset, boolean forPrimaryMap) {
        if (offset + 16 > data.length) return;
        int numGroups = readInt32(offset + 12);
        for (int i = 0; i < numGroups; i++) {
            int groupOffset = offset + 16 + i * 12;
            if (groupOffset + 12 > data.length) break;
            int startCharCode = readInt32(groupOffset);
            int endCharCode = readInt32(groupOffset + 4);
            int startGlyphId = readInt32(groupOffset + 8);
            for (int cc = startCharCode; cc <= endCharCode; cc++) {
                int gid = startGlyphId + (cc - startCharCode);
                registerCmapMapping(cc, gid, forPrimaryMap);
            }
        }
    }

    private void registerCmapMapping(int charCode, int glyphId, boolean forPrimaryMap) {
        if (forPrimaryMap) {
            cmapTable.put(charCode, glyphId);
        }
        Integer existing = reverseCmapTable.get(glyphId);
        if (shouldReplaceReverseMapping(existing, charCode)) {
            reverseCmapTable.put(glyphId, charCode);
        }
        if (currentSubtableIsUnicode) {
            Integer existingU = unicodeReverseCmap.get(glyphId);
            if (shouldReplaceReverseMapping(existingU, charCode)) {
                unicodeReverseCmap.put(glyphId, charCode);
            }
        }
    }

    private boolean shouldReplaceReverseMapping(Integer existing, int candidate) {
        if (existing == null) return true;
        if (isReadableUnicode(candidate) && !isReadableUnicode(existing)) return true;
        if (!isPrivateUse(candidate) && isPrivateUse(existing)) return true;
        return false;
    }

    private boolean isReadableUnicode(int codePoint) {
        return codePoint >= 0x20 && !isPrivateUse(codePoint);
    }

    private boolean isPrivateUse(int codePoint) {
        return codePoint >= 0xE000 && codePoint <= 0xF8FF;
    }

    private void parseName(int tableOffset, int tableLength) {
        if (tableOffset + 6 > data.length) return;
        int count = readUInt16(tableOffset + 2);
        int storageOffset = readUInt16(tableOffset + 4);
        int stringStorageOffset = tableOffset + storageOffset;

        for (int i = 0; i < count; i++) {
            int recOffset = tableOffset + 6 + i * 12;
            if (recOffset + 12 > data.length) break;
            int platformId = readUInt16(recOffset);
            int nameId = readUInt16(recOffset + 6);
            int strLength = readUInt16(recOffset + 8);
            int strOffset = readUInt16(recOffset + 10);

            if (nameId == 6 && fontName == null) {
                int start = stringStorageOffset + strOffset;
                if (start + strLength <= data.length) {
                    if (platformId == 3 || platformId == 0) {
                        fontName = new String(data, start, strLength, java.nio.charset.StandardCharsets.UTF_16BE);
                    } else {
                        fontName = new String(data, start, strLength, java.nio.charset.StandardCharsets.US_ASCII);
                    }
                }
            }
        }
    }

    /**
     * Parses the {@code /post} table to populate {@link #glyphNames} indexed
     * by glyph id. Supports formats 1.0 (Mac standard names only), 2.0
     * (numGlyphs indices into Mac standard + Pascal-string custom names) and
     * 3.0 (no glyph names — leaves {@link #glyphNames} null).
     *
     * @see <a href="https://docs.microsoft.com/en-us/typography/opentype/spec/post">OpenType post table</a>
     */
    private void parsePost(int offset, int length) {
        if (offset + 32 > data.length) return;
        int version = readInt32(offset);
        // Format 0x00010000 — exactly the 258 Macintosh standard names.
        if (version == 0x00010000) {
            int count = Math.min(258, numGlyphs > 0 ? numGlyphs : 258);
            glyphNames = new String[count];
            System.arraycopy(MAC_STANDARD_NAMES, 0, glyphNames, 0, count);
            return;
        }
        // Format 0x00020000 — header (32) + numberOfGlyphs (UInt16) +
        // numberOfGlyphs × glyphNameIndex (UInt16) + Pascal strings.
        if (version == 0x00020000) {
            int p = offset + 32;
            if (p + 2 > data.length) return;
            int numberOfGlyphs = readUInt16(p);
            p += 2;
            if (p + numberOfGlyphs * 2 > data.length) return;
            int[] indices = new int[numberOfGlyphs];
            for (int i = 0; i < numberOfGlyphs; i++) {
                indices[i] = readUInt16(p);
                p += 2;
            }
            // Walk the Pascal-string list. Indices ≥258 reference the i-th
            // custom name in the order they appear.
            java.util.List<String> custom = new java.util.ArrayList<>();
            while (p < offset + length && p < data.length) {
                int strLen = data[p] & 0xFF;
                p++;
                if (p + strLen > data.length) break;
                custom.add(new String(data, p, strLen,
                        java.nio.charset.StandardCharsets.US_ASCII));
                p += strLen;
            }
            glyphNames = new String[numberOfGlyphs];
            for (int gid = 0; gid < numberOfGlyphs; gid++) {
                int idx = indices[gid];
                if (idx < 258) {
                    glyphNames[gid] = MAC_STANDARD_NAMES[idx];
                } else {
                    int customIdx = idx - 258;
                    if (customIdx >= 0 && customIdx < custom.size()) {
                        glyphNames[gid] = custom.get(customIdx);
                    }
                }
            }
            return;
        }
        // Format 0x00030000 — no glyph names exposed. Format 0x00025000
        // (deprecated 2.5) is rarely used and intentionally not supported.
    }

    /**
     * The 258 Macintosh standard PostScript glyph names referenced by
     * {@code post} table format 1.0 and as a base by format 2.0.
     */
    private static final String[] MAC_STANDARD_NAMES = {
            ".notdef", ".null", "nonmarkingreturn", "space", "exclam", "quotedbl",
            "numbersign", "dollar", "percent", "ampersand", "quotesingle", "parenleft",
            "parenright", "asterisk", "plus", "comma", "hyphen", "period", "slash",
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
            "nine", "colon", "semicolon", "less", "equal", "greater", "question", "at",
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O",
            "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "bracketleft",
            "backslash", "bracketright", "asciicircum", "underscore", "grave",
            "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o",
            "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z", "braceleft", "bar",
            "braceright", "asciitilde", "Adieresis", "Aring", "Ccedilla", "Eacute",
            "Ntilde", "Odieresis", "Udieresis", "aacute", "agrave", "acircumflex",
            "adieresis", "atilde", "aring", "ccedilla", "eacute", "egrave",
            "ecircumflex", "edieresis", "iacute", "igrave", "icircumflex", "idieresis",
            "ntilde", "oacute", "ograve", "ocircumflex", "odieresis", "otilde", "uacute",
            "ugrave", "ucircumflex", "udieresis", "dagger", "degree", "cent", "sterling",
            "section", "bullet", "paragraph", "germandbls", "registered", "copyright",
            "trademark", "acute", "dieresis", "notequal", "AE", "Oslash", "infinity",
            "plusminus", "lessequal", "greaterequal", "yen", "mu", "partialdiff",
            "summation", "product", "pi", "integral", "ordfeminine", "ordmasculine",
            "Omega", "ae", "oslash", "questiondown", "exclamdown", "logicalnot",
            "radical", "florin", "approxequal", "Delta", "guillemotleft",
            "guillemotright", "ellipsis", "nonbreakingspace", "Agrave", "Atilde",
            "Otilde", "OE", "oe", "endash", "emdash", "quotedblleft", "quotedblright",
            "quoteleft", "quoteright", "divide", "lozenge", "ydieresis", "Ydieresis",
            "fraction", "currency", "guilsinglleft", "guilsinglright", "fi", "fl",
            "daggerdbl", "periodcentered", "quotesinglbase", "quotedblbase",
            "perthousand", "Acircumflex", "Ecircumflex", "Aacute", "Edieresis", "Egrave",
            "Iacute", "Icircumflex", "Idieresis", "Eth", "eth", "Yacute", "yacute",
            "Thorn", "thorn", "minus", "multiply", "onesuperior", "twosuperior",
            "threesuperior", "onehalf", "onequarter", "threequarters", "franc", "Gbreve",
            "gbreve", "Idotaccent", "Scedilla", "scedilla", "Cacute", "cacute", "Ccaron",
            "ccaron", "dcroat"
    };

    private int readUInt16(int offset) {
        if (offset + 2 > data.length) return 0;
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private int readInt16(int offset) {
        int val = readUInt16(offset);
        return val > 0x7FFF ? val - 0x10000 : val;
    }

    private int readInt32(int offset) {
        if (offset + 4 > data.length) return 0;
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private String readTag(int offset) {
        if (offset + 4 > data.length) return "";
        return new String(data, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
