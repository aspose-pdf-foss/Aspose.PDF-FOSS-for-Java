package org.aspose.pdf.engine.font.cff;

import java.io.IOException;

/**
 * Minimal parser for the Compact Font Format (CFF) — Adobe Technical Note #5176.
 *
 * <p>Used only to extract two things from a CFF font program embedded in a PDF:
 * (1) the total glyph count, (2) the glyph-id → glyph-name mapping (the
 * <em>charset</em>). Glyph outlines themselves are NOT decoded here — the
 * caller wraps the original CFF bytes in a synthetic OpenType container and
 * lets Java's font engine rasterise via its native CFF support.</p>
 *
 * <p>What we parse:</p>
 * <ul>
 *   <li>Header (major/minor version, header size, offset size)</li>
 *   <li>Name INDEX — first entry is the PostScript font name</li>
 *   <li>Top DICT INDEX — first entry, extract operators for CharStrings,
 *       Charset, String, etc.</li>
 *   <li>String INDEX — custom strings (SID ≥ 391)</li>
 *   <li>CharStrings INDEX — count only (no decode of glyph programs)</li>
 *   <li>Charset (format 0, 1, or 2) — produces glyph-id → glyph-name array</li>
 * </ul>
 */
public final class CFFParser {

    private final byte[] data;
    private int pos;

    /** Original CFF bytes (verbatim — handy for the OTF wrapper). */
    public final byte[] cffBytes;
    /** PostScript font name from the Name INDEX (first entry). */
    public final String fontName;
    /** Number of glyphs (size of CharStrings INDEX). */
    public final int numGlyphs;
    /** Glyph-id → glyph-name table (length == numGlyphs; index 0 is always ".notdef"). */
    public final String[] glyphNames;
    /**
     * Glyph-id → raw charset SID (length == numGlyphs; entry 0 is 0). In a
     * CID-keyed font these values are CIDs, not string ids (Adobe TN#5176 §18).
     */
    public final int[] charsetSids;
    /** True when the Top DICT carries a /ROS operator — a CID-keyed font. */
    public final boolean cidKeyed;

    /**
     * Parses a CFF stream.
     *
     * @param data CFF bytes — typically the contents of a {@code /FontFile3}
     *             stream with {@code /Subtype /Type1C} or {@code /CIDFontType0C}
     * @throws IOException on malformed input
     */
    public CFFParser(byte[] data) throws IOException {
        if (data == null || data.length < 4) {
            throw new IOException("CFF: too short");
        }
        this.cffBytes = data;
        this.data = data;
        this.pos = 0;

        // -- Header --
        int major = readU8();
        readU8(); // minor
        int hdrSize = readU8();
        int offSize = readU8();
        if (major != 1) {
            throw new IOException("CFF: unsupported major version " + major);
        }
        if (hdrSize < 4 || offSize < 1 || offSize > 4) {
            throw new IOException("CFF: bad header (hdrSize=" + hdrSize + ", offSize=" + offSize + ")");
        }
        pos = hdrSize;

        // -- Name INDEX --
        IndexData nameIndex = readIndex();
        this.fontName = nameIndex.count > 0
                ? new String(nameIndex.entry(0), java.nio.charset.StandardCharsets.US_ASCII)
                : "";

        // -- Top DICT INDEX --
        IndexData topDictIndex = readIndex();
        if (topDictIndex.count == 0) {
            throw new IOException("CFF: empty Top DICT INDEX");
        }
        byte[] topDictBytes = topDictIndex.entry(0);
        TopDict topDict = parseTopDict(topDictBytes);

        // -- String INDEX --
        IndexData stringIndex = readIndex();
        String[] customStrings = new String[stringIndex.count];
        for (int i = 0; i < stringIndex.count; i++) {
            customStrings[i] = new String(stringIndex.entry(i), java.nio.charset.StandardCharsets.US_ASCII);
        }

        // -- Global Subr INDEX -- (skip)
        readIndex();

        // -- CharStrings INDEX --
        if (topDict.charStringsOffset <= 0) {
            throw new IOException("CFF: missing CharStrings operator (17) in Top DICT");
        }
        pos = topDict.charStringsOffset;
        IndexData charStrings = readIndex();
        this.numGlyphs = charStrings.count;

        // -- Charset --
        this.cidKeyed = topDict.hasRos;
        this.glyphNames = new String[numGlyphs];
        this.charsetSids = new int[numGlyphs];
        glyphNames[0] = ".notdef";
        if (numGlyphs > 1) {
            readCharset(topDict.charsetOffset, customStrings);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Header / Top DICT
    // ────────────────────────────────────────────────────────────────────────

    private static final class TopDict {
        int charsetOffset = 0;       // op 15: 0=ISOAdobe, 1=Expert, 2=ExpertSubset, else offset
        int encodingOffset = 0;       // op 16: 0=Standard, 1=Expert, else offset
        int charStringsOffset = 0;    // op 17: REQUIRED
        int charStringType = 2;       // op 12-6: default 2 (Type 2)
        int privateDictSize = 0;      // op 18 second operand
        int privateDictOffset = 0;    // op 18 first operand
        boolean hasRos = false;       // op 12-30: Registry-Ordering-Supplement (CID-keyed)
    }

    private static TopDict parseTopDict(byte[] dict) {
        TopDict t = new TopDict();
        int p = 0;
        long[] operands = new long[48];
        boolean[] isReal = new boolean[48];
        int nOperands = 0;
        while (p < dict.length) {
            int b0 = dict[p] & 0xFF;
            if (b0 <= 21) {
                // operator
                int op = b0;
                if (b0 == 12 && p + 1 < dict.length) {
                    op = 1200 + (dict[p + 1] & 0xFF);
                    p += 2;
                } else {
                    p++;
                }
                applyTopDictOp(t, op, operands, nOperands);
                nOperands = 0;
            } else {
                // operand
                long[] parsed = parseDictOperand(dict, p);
                if (parsed == null) break;
                operands[nOperands++] = parsed[0];
                isReal[nOperands - 1] = parsed[2] != 0;
                p += (int) parsed[1];
            }
        }
        return t;
    }

    /** Parses one DICT operand. Returns [value, byteLength, isReal(0/1)] or null on error. */
    private static long[] parseDictOperand(byte[] dict, int p) {
        int b0 = dict[p] & 0xFF;
        if (b0 >= 32 && b0 <= 246) {
            return new long[]{b0 - 139, 1, 0};
        } else if (b0 >= 247 && b0 <= 250) {
            int b1 = dict[p + 1] & 0xFF;
            return new long[]{((b0 - 247) * 256L) + b1 + 108, 2, 0};
        } else if (b0 >= 251 && b0 <= 254) {
            int b1 = dict[p + 1] & 0xFF;
            return new long[]{-((b0 - 251) * 256L) - b1 - 108, 2, 0};
        } else if (b0 == 28) {
            int v = ((dict[p + 1] & 0xFF) << 8) | (dict[p + 2] & 0xFF);
            if ((v & 0x8000) != 0) v |= 0xFFFF0000;
            return new long[]{v, 3, 0};
        } else if (b0 == 29) {
            int v = ((dict[p + 1] & 0xFF) << 24) | ((dict[p + 2] & 0xFF) << 16)
                  | ((dict[p + 3] & 0xFF) << 8) | (dict[p + 4] & 0xFF);
            return new long[]{v, 5, 0};
        } else if (b0 == 30) {
            // BCD-coded real number — skip until trailing 0xf
            int q = p + 1;
            while (q < dict.length) {
                int b = dict[q++] & 0xFF;
                if ((b & 0x0F) == 0x0F || (b & 0xF0) == 0xF0) break;
            }
            return new long[]{0, q - p, 1};
        }
        return null;
    }

    private static void applyTopDictOp(TopDict t, int op, long[] operands, int n) {
        switch (op) {
            case 15: if (n > 0) t.charsetOffset = (int) operands[0]; break;
            case 16: if (n > 0) t.encodingOffset = (int) operands[0]; break;
            case 17: if (n > 0) t.charStringsOffset = (int) operands[0]; break;
            case 18:
                if (n >= 2) {
                    t.privateDictSize = (int) operands[0];
                    t.privateDictOffset = (int) operands[1];
                }
                break;
            case 1206: if (n > 0) t.charStringType = (int) operands[0]; break;
            case 1230: t.hasRos = true; break;
            default: /* ignore */ break;
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    //  INDEX structure
    // ────────────────────────────────────────────────────────────────────────

    private final class IndexData {
        final int count;
        final int offSize;
        final int[] offsets;  // count+1 entries (1-based, relative to data block)
        final int dataStart;  // absolute position of data block

        IndexData(int count, int offSize, int[] offsets, int dataStart) {
            this.count = count;
            this.offSize = offSize;
            this.offsets = offsets;
            this.dataStart = dataStart;
        }

        byte[] entry(int i) {
            int start = dataStart + offsets[i] - 1;
            int end = dataStart + offsets[i + 1] - 1;
            int len = Math.max(0, end - start);
            byte[] buf = new byte[len];
            System.arraycopy(data, start, buf, 0, len);
            return buf;
        }
    }

    private IndexData readIndex() throws IOException {
        if (pos + 2 > data.length) throw new IOException("CFF: truncated INDEX header at " + pos);
        int count = readU16();
        if (count == 0) {
            return new IndexData(0, 0, new int[]{1, 1}, pos);
        }
        int offSize = readU8();
        if (offSize < 1 || offSize > 4) throw new IOException("CFF: bad INDEX offSize " + offSize);
        int[] offsets = new int[count + 1];
        for (int i = 0; i <= count; i++) {
            offsets[i] = readOff(offSize);
        }
        int dataStart = pos;
        int lastOff = offsets[count];
        pos = dataStart + lastOff - 1;
        return new IndexData(count, offSize, offsets, dataStart);
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Charset
    // ────────────────────────────────────────────────────────────────────────

    private void readCharset(int offset, String[] customStrings) throws IOException {
        if (offset == 0) {
            // Predefined charset: ISOAdobe for name-keyed fonts; for a
            // CID-keyed font the default charset is the identity CID mapping.
            for (int gid = 1; gid < numGlyphs && gid < CFFStandardStrings.ISO_ADOBE_LEN; gid++) {
                glyphNames[gid] = CFFStandardStrings.lookup(gid);
            }
            for (int gid = CFFStandardStrings.ISO_ADOBE_LEN; gid < numGlyphs; gid++) {
                glyphNames[gid] = "glyph" + gid;
            }
            for (int gid = 1; gid < numGlyphs; gid++) charsetSids[gid] = gid;
            return;
        }
        if (offset == 1 || offset == 2) {
            // Predefined Expert / ExpertSubset — rarely seen. Fall back to numeric names.
            for (int gid = 1; gid < numGlyphs; gid++) {
                glyphNames[gid] = "glyph" + gid;
                charsetSids[gid] = gid;
            }
            return;
        }
        pos = offset;
        int format = readU8();
        if (format == 0) {
            for (int gid = 1; gid < numGlyphs; gid++) {
                int sid = readU16();
                glyphNames[gid] = sidToName(sid, customStrings);
                charsetSids[gid] = sid;
            }
        } else if (format == 1 || format == 2) {
            int gid = 1;
            while (gid < numGlyphs) {
                int first = readU16();
                int nLeft = (format == 1) ? readU8() : readU16();
                for (int i = 0; i <= nLeft && gid < numGlyphs; i++) {
                    glyphNames[gid] = sidToName(first + i, customStrings);
                    charsetSids[gid++] = first + i;
                }
            }
        } else {
            throw new IOException("CFF: unknown Charset format " + format);
        }
    }

    private static String sidToName(int sid, String[] customStrings) {
        if (sid < 391) return CFFStandardStrings.lookup(sid);
        int idx = sid - 391;
        if (idx < customStrings.length) return customStrings[idx];
        return "sid" + sid;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Low-level reads
    // ────────────────────────────────────────────────────────────────────────

    private int readU8() throws IOException {
        if (pos >= data.length) throw new IOException("CFF: EOF at " + pos);
        return data[pos++] & 0xFF;
    }

    private int readU16() throws IOException {
        int b0 = readU8();
        int b1 = readU8();
        return (b0 << 8) | b1;
    }

    private int readOff(int size) throws IOException {
        int v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | readU8();
        return v;
    }
}
