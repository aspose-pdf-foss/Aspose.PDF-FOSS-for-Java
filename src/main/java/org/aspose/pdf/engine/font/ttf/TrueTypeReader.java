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

    /** {@code head.indexToLocFormat}: 0 = short (uint16×2) offsets, 1 = long (uint32). */
    private int indexToLocFormat;
    /** Byte offset of each glyph into the {@code glyf} table; length numGlyphs+1. Null if no outlines. */
    private int[] loca;
    /** Absolute byte offset of the {@code glyf} table, or -1 when absent. */
    private int glyfOffset = -1;

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
     * Returns true if the font ships at least one true-Unicode cmap subtable
     * (platform 3 encoding 1/10, or platform 0). Subset fonts produced by
     * office suites often carry only a code-keyed (1,0)/(3,0) subtable whose
     * keys are the PDF character codes, not Unicode — glyph selection for
     * those must go through the raw code (ISO 32000-1:2008, §9.6.6.4).
     *
     * @return true if a Unicode cmap subtable was parsed
     */
    public boolean hasUnicodeCmap() {
        return unicodeReverseCmap != null && !unicodeReverseCmap.isEmpty();
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
        // Outline tables: glyf + loca let us draw glyphs by id directly, without
        // java.awt.Font — essential for subset CIDFontType2 programs that ship no
        // cmap (the JDK rejects those for layout and silently substitutes Arial).
        if (tables.containsKey("glyf") && tables.containsKey("loca")) {
            try {
                glyfOffset = tables.get("glyf")[0];
                parseLoca(tables.get("loca")[0]);
            } catch (Exception e) {
                glyfOffset = -1;
                loca = null;
                LOG.fine(() -> "Failed to parse glyf/loca: " + e.getMessage());
            }
        }

        LOG.fine(() -> "TrueType parsed: unitsPerEm=" + unitsPerEm + ", numGlyphs=" + numGlyphs
                + ", cmap entries=" + (cmapTable != null ? cmapTable.size() : 0));
    }

    private void parseHead(int offset) {
        if (offset + 54 <= data.length) {
            unitsPerEm = readUInt16(offset + 18);
            if (unitsPerEm == 0) unitsPerEm = 1000;
            // indexToLocFormat lives at head + 50 (UInt16): 0 short, 1 long.
            indexToLocFormat = readUInt16(offset + 50);
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

    private void parseLoca(int offset) {
        if (numGlyphs <= 0) return;
        int[] arr = new int[numGlyphs + 1];
        if (indexToLocFormat == 0) {
            // Short format: UInt16 offsets, stored as half the real byte offset.
            for (int i = 0; i <= numGlyphs; i++) {
                arr[i] = readUInt16(offset + i * 2) * 2;
            }
        } else {
            // Long format: UInt32 byte offsets.
            for (int i = 0; i <= numGlyphs; i++) {
                arr[i] = readInt32(offset + i * 4);
            }
        }
        this.loca = arr;
    }

    /**
     * Returns the outline of a glyph as a {@link java.awt.geom.GeneralPath} in
     * em-normalised coordinates (font units divided by {@code unitsPerEm}), with
     * the TrueType Y-up orientation preserved. Empty path for blank glyphs (e.g.
     * space); {@code null} when the font has no {@code glyf}/{@code loca} tables,
     * the glyph id is out of range, or the data is malformed.
     * <p>
     * Drawing by outline bypasses {@code java.awt.Font}, which silently
     * substitutes the default physical font when an embedded subset program
     * lacks a usable {@code cmap} (ISO 32000-1:2008 §9.7.4 CIDFontType2 programs
     * are addressed purely by glyph id and frequently ship no cmap).
     *
     * @param gid the glyph id
     * @return the em-normalised, Y-up outline, or {@code null}
     */
    /**
     * Returns true when the font program carries parsable outline tables
     * ({@code glyf} + {@code loca}), i.e. {@link #getGlyphPath(int)} can serve
     * glyph outlines.
     *
     * @return true when glyph outlines are available
     */
    public boolean hasGlyphOutlines() {
        return loca != null && glyfOffset >= 0;
    }

    public java.awt.geom.GeneralPath getGlyphPath(int gid) {
        if (loca == null || glyfOffset < 0 || gid < 0 || gid + 1 >= loca.length) {
            return null;
        }
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath();
        try {
            if (!appendGlyph(gid, path, new java.awt.geom.AffineTransform(), 0)) {
                return null;
            }
        } catch (RuntimeException e) {
            LOG.fine(() -> "glyf parse failed for gid " + gid + ": " + e);
            return null;
        }
        double s = 1.0 / unitsPerEm;
        path.transform(java.awt.geom.AffineTransform.getScaleInstance(s, s));
        return path;
    }

    /**
     * Appends one glyph's contours (in raw font units, through {@code xf}) to
     * {@code path}. Recurses for composite glyphs. Returns false on malformed
     * data so the caller can fall back.
     */
    private boolean appendGlyph(int gid, java.awt.geom.GeneralPath path,
                                java.awt.geom.AffineTransform xf, int depth) {
        if (depth > 8 || gid < 0 || gid + 1 >= loca.length) return false;
        int start = glyfOffset + loca[gid];
        int end = glyfOffset + loca[gid + 1];
        if (loca[gid + 1] <= loca[gid]) return true; // empty glyph (e.g. space)
        if (start + 10 > data.length || end > data.length) return false;

        int numberOfContours = readInt16(start);
        if (numberOfContours >= 0) {
            return appendSimpleGlyph(start, numberOfContours, path, xf);
        }
        return appendCompositeGlyph(start + 10, end, path, xf, depth);
    }

    private boolean appendSimpleGlyph(int p, int numberOfContours,
                                      java.awt.geom.GeneralPath path,
                                      java.awt.geom.AffineTransform xf) {
        int o = p + 10; // skip numberOfContours(2) + bbox(8)
        int[] endPts = new int[numberOfContours];
        for (int i = 0; i < numberOfContours; i++) {
            endPts[i] = readUInt16(o);
            o += 2;
        }
        int numPoints = numberOfContours > 0 ? endPts[numberOfContours - 1] + 1 : 0;
        if (numPoints <= 0) return true;

        int instrLen = readUInt16(o);
        o += 2 + instrLen;

        // Flags (with repeat compression).
        byte[] flags = new byte[numPoints];
        for (int i = 0; i < numPoints && o < data.length; ) {
            byte flag = data[o++];
            flags[i++] = flag;
            if ((flag & 0x08) != 0 && o < data.length) { // REPEAT_FLAG
                int repeat = data[o++] & 0xFF;
                while (repeat-- > 0 && i < numPoints) flags[i++] = flag;
            }
        }

        // X coordinates (delta-encoded).
        int[] xs = new int[numPoints];
        int x = 0;
        for (int i = 0; i < numPoints; i++) {
            int flag = flags[i];
            if ((flag & 0x02) != 0) {            // X_SHORT_VECTOR
                int dx = data[o++] & 0xFF;
                x += ((flag & 0x10) != 0) ? dx : -dx; // X_IS_SAME_OR_POSITIVE_X_SHORT
            } else if ((flag & 0x10) == 0) {     // not same → 16-bit delta
                x += readInt16(o);
                o += 2;
            }
            xs[i] = x;
        }
        // Y coordinates (delta-encoded).
        int[] ys = new int[numPoints];
        int y = 0;
        for (int i = 0; i < numPoints; i++) {
            int flag = flags[i];
            if ((flag & 0x04) != 0) {            // Y_SHORT_VECTOR
                int dy = data[o++] & 0xFF;
                y += ((flag & 0x20) != 0) ? dy : -dy; // Y_IS_SAME_OR_POSITIVE_Y_SHORT
            } else if ((flag & 0x20) == 0) {
                y += readInt16(o);
                o += 2;
            }
            ys[i] = y;
        }

        int contourStart = 0;
        for (int c = 0; c < numberOfContours; c++) {
            int contourEnd = endPts[c];
            buildContour(path, xf, xs, ys, flags, contourStart, contourEnd);
            contourStart = contourEnd + 1;
        }
        return true;
    }

    /**
     * Emits one closed contour, converting TrueType quadratic on/off-curve
     * points (with implicit midpoints between consecutive off-curve points)
     * into {@code quadTo} segments.
     */
    private void buildContour(java.awt.geom.GeneralPath path,
                              java.awt.geom.AffineTransform xf,
                              int[] xs, int[] ys, byte[] flags, int s, int e) {
        int n = e - s + 1;
        if (n <= 0) return;

        // Locate a starting on-curve point; synthesise one if the contour is all
        // off-curve (start = midpoint of first and last off-curve points).
        int startIdx = -1;
        for (int i = s; i <= e; i++) {
            if ((flags[i] & 0x01) != 0) { startIdx = i; break; }
        }
        double sx, sy;
        if (startIdx >= 0) {
            sx = xs[startIdx]; sy = ys[startIdx];
        } else {
            startIdx = s;
            sx = (xs[s] + xs[e]) / 2.0;
            sy = (ys[s] + ys[e]) / 2.0;
        }
        double[] pt = transform(xf, sx, sy);
        path.moveTo(pt[0], pt[1]);

        double cx = 0, cy = 0;
        boolean haveCtrl = false;
        for (int k = 1; k <= n; k++) {
            int i = s + ((startIdx - s) + k) % n;
            boolean onCurve = (flags[i] & 0x01) != 0;
            double px = xs[i], py = ys[i];
            if (onCurve) {
                if (haveCtrl) {
                    double[] c = transform(xf, cx, cy);
                    double[] q = transform(xf, px, py);
                    path.quadTo(c[0], c[1], q[0], q[1]);
                    haveCtrl = false;
                } else {
                    double[] q = transform(xf, px, py);
                    path.lineTo(q[0], q[1]);
                }
            } else {
                if (haveCtrl) {
                    // Two consecutive off-curve points: implicit on-curve midpoint.
                    double mx = (cx + px) / 2.0, my = (cy + py) / 2.0;
                    double[] c = transform(xf, cx, cy);
                    double[] m = transform(xf, mx, my);
                    path.quadTo(c[0], c[1], m[0], m[1]);
                }
                cx = px; cy = py; haveCtrl = true;
            }
        }
        if (haveCtrl) {
            double[] c = transform(xf, cx, cy);
            path.quadTo(c[0], c[1], pt[0], pt[1]);
        }
        path.closePath();
    }

    private boolean appendCompositeGlyph(int o, int end, java.awt.geom.GeneralPath path,
                                         java.awt.geom.AffineTransform xf, int depth) {
        boolean more = true;
        while (more && o + 4 <= data.length && o < end) {
            int flags = readUInt16(o);
            int componentGid = readUInt16(o + 2);
            o += 4;

            double arg1, arg2;
            if ((flags & 0x0001) != 0) { // ARG_1_AND_2_ARE_WORDS
                arg1 = readInt16(o); arg2 = readInt16(o + 2); o += 4;
            } else {
                arg1 = (byte) data[o]; arg2 = (byte) data[o + 1]; o += 2;
            }

            double a = 1, b = 0, c = 0, d = 1;
            if ((flags & 0x0008) != 0) {            // WE_HAVE_A_SCALE
                a = d = f2dot14(o); o += 2;
            } else if ((flags & 0x0040) != 0) {     // WE_HAVE_AN_X_AND_Y_SCALE
                a = f2dot14(o); d = f2dot14(o + 2); o += 4;
            } else if ((flags & 0x0080) != 0) {     // WE_HAVE_A_TWO_BY_TWO
                a = f2dot14(o); b = f2dot14(o + 2); c = f2dot14(o + 4); d = f2dot14(o + 6); o += 8;
            }

            // ARGS_ARE_XY_VALUES: args are a translation in font units. (Point
            // matching — the alternative — is extremely rare in subsets; treat
            // args as 0 offset in that case.)
            double dx = 0, dy = 0;
            if ((flags & 0x0002) != 0) { dx = arg1; dy = arg2; }

            java.awt.geom.AffineTransform comp = new java.awt.geom.AffineTransform(a, b, c, d, dx, dy);
            java.awt.geom.AffineTransform combined = new java.awt.geom.AffineTransform(xf);
            combined.concatenate(comp);
            appendGlyph(componentGid, path, combined, depth + 1);

            more = (flags & 0x0020) != 0; // MORE_COMPONENTS
        }
        return true;
    }

    /** Reads an F2Dot14 fixed-point value (signed 2.14) as a double. */
    private double f2dot14(int offset) {
        return readInt16(offset) / 16384.0;
    }

    private static double[] transform(java.awt.geom.AffineTransform xf, double x, double y) {
        double[] p = {x, y};
        xf.transform(p, 0, p, 0, 1);
        return p;
    }

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
