package org.aspose.pdf.engine.optimization;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Logger;

/**
 * Glyph-outline stripper for TrueType font programs: rebuilds a TTF keeping
 * only the outlines of the requested glyph ids (plus their composite
 * components and glyph 0), emptying every other {@code glyf} entry.
 * <p>
 * Glyphs are NOT renumbered — {@code numGlyphs}, {@code cmap},
 * {@code hmtx} and any {@code CIDToGIDMap} stay valid, which makes the
 * transformation safe for CID-keyed fonts where CID == GID (Identity).
 * Outline data dominates font size, so the win is close to a full subset
 * at a fraction of the complexity/risk.
 * </p>
 * <p>
 * The rebuilt file preserves the original table order, recomputes table
 * checksums and {@code head.checkSumAdjustment}, and always uses the LONG
 * {@code loca} format (head.indexToLocFormat = 1). Any structural anomaly
 * aborts the rebuild ({@code null} return) so the caller keeps the
 * original program.
 * </p>
 */
public final class TtfGlyphStripper {

    private static final Logger LOG = Logger.getLogger(TtfGlyphStripper.class.getName());

    private final byte[] data;
    private final Map<String, int[]> tables = new LinkedHashMap<>();   // tag → {offset, length}
    private int numGlyphs;
    private int indexToLocFormat;
    private int[] loca;                 // numGlyphs + 1 offsets into glyf
    private int glyfOffset;
    private int glyfLength;

    private TtfGlyphStripper(byte[] data) {
        this.data = data;
    }

    /**
     * Rebuilds {@code ttf} with only the given glyph outlines retained.
     *
     * @param ttf      the original TrueType program
     * @param usedGids the glyph ids whose outlines must survive
     * @return the stripped font, or {@code null} when the font cannot be
     *         safely rebuilt (caller keeps the original)
     */
    public static byte[] strip(byte[] ttf, Set<Integer> usedGids) {
        try {
            TtfGlyphStripper stripper = new TtfGlyphStripper(ttf);
            return stripper.run(usedGids);
        } catch (RuntimeException e) {
            LOG.fine(() -> "Glyph strip aborted: " + e.getMessage());
            return null;
        }
    }

    private byte[] run(Set<Integer> usedGids) {
        parseDirectory();
        if (!tables.containsKey("glyf") || !tables.containsKey("loca")
                || !tables.containsKey("head") || !tables.containsKey("maxp")) {
            return null;
        }
        numGlyphs = readU16(tables.get("maxp")[0] + 4);
        indexToLocFormat = readU16(tables.get("head")[0] + 50);
        if (numGlyphs <= 0 || numGlyphs > 0xFFFF || (indexToLocFormat & ~1) != 0) {
            return null;
        }
        parseLoca();

        // Closure over composite glyphs: a kept composite needs its parts.
        Set<Integer> keep = new TreeSet<>();
        keep.add(0);                                    // .notdef is mandatory
        for (int gid : usedGids) {
            if (gid >= 0 && gid < numGlyphs) {
                addWithComponents(gid, keep, 0);
            }
        }

        // New glyf + loca (LONG format, 2-byte-aligned entries).
        ByteArrayOutputStream newGlyf = new ByteArrayOutputStream(glyfLength / 2);
        int[] newLoca = new int[numGlyphs + 1];
        for (int gid = 0; gid < numGlyphs; gid++) {
            newLoca[gid] = newGlyf.size();
            if (keep.contains(gid)) {
                int start = loca[gid];
                int end = loca[gid + 1];
                newGlyf.write(data, glyfOffset + start, end - start);
                if (((end - start) & 1) != 0) {
                    newGlyf.write(0);
                }
            }
        }
        newLoca[numGlyphs] = newGlyf.size();

        byte[] glyfBytes = newGlyf.toByteArray();
        byte[] locaBytes = new byte[(numGlyphs + 1) * 4];
        for (int i = 0; i <= numGlyphs; i++) {
            writeU32(locaBytes, i * 4, newLoca[i]);
        }
        byte[] headBytes = slice(tables.get("head"));
        writeU16(headBytes, 50, 1);                     // long loca format
        writeU32(headBytes, 8, 0);                      // checkSumAdjustment ← 0 for now

        return assemble(glyfBytes, locaBytes, headBytes);
    }

    /** Recursively adds a glyph and, for composites, all component glyphs. */
    private void addWithComponents(int gid, Set<Integer> keep, int depth) {
        if (depth > 8 || !keep.add(gid)) {
            return;
        }
        int start = loca[gid];
        int end = loca[gid + 1];
        if (end - start < 10) {
            return;                                     // empty or trivially simple
        }
        int off = glyfOffset + start;
        int contours = readS16(off);
        if (contours >= 0) {
            return;                                     // simple glyph
        }
        off += 10;                                      // header: contours + bbox
        while (true) {
            int flags = readU16(off);
            int component = readU16(off + 2);
            addWithComponents(component, keep, depth + 1);
            off += 4;
            off += (flags & 0x0001) != 0 ? 4 : 2;       // ARG_1_AND_2_ARE_WORDS
            if ((flags & 0x0008) != 0) off += 2;        // WE_HAVE_A_SCALE
            else if ((flags & 0x0040) != 0) off += 4;   // X_AND_Y_SCALE
            else if ((flags & 0x0080) != 0) off += 8;   // 2x2 TRANSFORM
            if ((flags & 0x0020) == 0) break;           // MORE_COMPONENTS
        }
    }

    // ================= assembly =================

    /** Rewrites the font with replacement glyf/loca/head tables. */
    private byte[] assemble(byte[] glyfBytes, byte[] locaBytes, byte[] headBytes) {
        int numTables = tables.size();
        int headerSize = 12 + 16 * numTables;
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> entry : tables.entrySet()) {
            switch (entry.getKey()) {
                case "glyf": content.put("glyf", glyfBytes); break;
                case "loca": content.put("loca", locaBytes); break;
                case "head": content.put("head", headBytes); break;
                default: content.put(entry.getKey(), slice(entry.getValue()));
            }
        }

        int total = headerSize;
        for (byte[] table : content.values()) {
            total += (table.length + 3) & ~3;
        }
        byte[] out = new byte[total];
        System.arraycopy(data, 0, out, 0, 12);          // sfnt header incl. searchRange etc.
        writeU16(out, 4, numTables);
        recomputeSearchFields(out, numTables);

        int dirOff = 12;
        int dataOff = headerSize;
        int headOffset = -1;
        for (Map.Entry<String, byte[]> entry : content.entrySet()) {
            byte[] table = entry.getValue();
            byte[] tag = entry.getKey().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            System.arraycopy(tag, 0, out, dirOff, 4);
            System.arraycopy(table, 0, out, dataOff, table.length);
            writeU32(out, dirOff + 4, (int) tableChecksum(out, dataOff, table.length));
            writeU32(out, dirOff + 8, dataOff);
            writeU32(out, dirOff + 12, table.length);
            if ("head".equals(entry.getKey())) {
                headOffset = dataOff;
            }
            dirOff += 16;
            dataOff += (table.length + 3) & ~3;
        }

        // head.checkSumAdjustment = 0xB1B0AFBA − checksum(entire file).
        long fileSum = tableChecksum(out, 0, out.length);
        if (headOffset >= 0) {
            writeU32(out, headOffset + 8, (int) (0xB1B0AFBAL - fileSum));
        }
        return out;
    }

    private void recomputeSearchFields(byte[] out, int numTables) {
        int entrySelector = 31 - Integer.numberOfLeadingZeros(Math.max(1, numTables));
        int searchRange = (1 << entrySelector) * 16;
        writeU16(out, 6, searchRange);
        writeU16(out, 8, entrySelector);
        writeU16(out, 10, numTables * 16 - searchRange);
    }

    private static long tableChecksum(byte[] buf, int offset, int length) {
        long sum = 0;
        int end = offset + ((length + 3) & ~3);
        for (int i = offset; i < end; i += 4) {
            long word = 0;
            for (int b = 0; b < 4; b++) {
                word = (word << 8) | (i + b < buf.length ? buf[i + b] & 0xFF : 0);
            }
            sum = (sum + word) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // ================= parsing =================

    private void parseDirectory() {
        int numTables = readU16(4);
        if (numTables <= 0 || numTables > 64 || 12 + 16 * numTables > data.length) {
            throw new IllegalStateException("bad table directory");
        }
        for (int i = 0; i < numTables; i++) {
            int rec = 12 + 16 * i;
            String tag = new String(data, rec, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int offset = readU32(rec + 8);
            int length = readU32(rec + 12);
            if (offset < 0 || length < 0 || offset + length > data.length) {
                throw new IllegalStateException("table out of bounds: " + tag);
            }
            tables.put(tag, new int[]{offset, length});
        }
    }

    private void parseLoca() {
        int[] locaTable = tables.get("loca");
        int[] glyfTable = tables.get("glyf");
        glyfOffset = glyfTable[0];
        glyfLength = glyfTable[1];
        loca = new int[numGlyphs + 1];
        if (indexToLocFormat == 0) {
            if (locaTable[1] < (numGlyphs + 1) * 2) {
                throw new IllegalStateException("short loca too small");
            }
            for (int i = 0; i <= numGlyphs; i++) {
                loca[i] = readU16(locaTable[0] + i * 2) * 2;
            }
        } else {
            if (locaTable[1] < (numGlyphs + 1) * 4) {
                throw new IllegalStateException("long loca too small");
            }
            for (int i = 0; i <= numGlyphs; i++) {
                loca[i] = readU32(locaTable[0] + i * 4);
            }
        }
        for (int i = 0; i < numGlyphs; i++) {
            if (loca[i] > loca[i + 1] || loca[i + 1] > glyfLength) {
                throw new IllegalStateException("non-monotonic loca");
            }
        }
    }

    private byte[] slice(int[] table) {
        byte[] copy = new byte[table[1]];
        System.arraycopy(data, table[0], copy, 0, table[1]);
        return copy;
    }

    private int readU16(int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private int readS16(int offset) {
        return (short) readU16(offset);
    }

    private int readU32(int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static void writeU16(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 8);
        buf[offset + 1] = (byte) value;
    }

    private static void writeU32(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value >>> 24);
        buf[offset + 1] = (byte) (value >>> 16);
        buf[offset + 2] = (byte) (value >>> 8);
        buf[offset + 3] = (byte) value;
    }
}
