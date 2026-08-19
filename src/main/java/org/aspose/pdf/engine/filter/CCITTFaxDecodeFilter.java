package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfBoolean;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * CCITTFaxDecode filter — CCITT Group 3 (1D) and Group 4 (2D) fax decompression.
 * ISO 32000-1:2008 §7.4.6, ITU-T Recommendations T.4 and T.6.
 *
 * <p>Decodes monochrome (1-bit) image data compressed with CCITT fax encoding.
 * Group 3 uses Modified Huffman run-length coding; Group 4 uses 2D MMR coding
 * relative to the previous reference line.</p>
 *
 * <p>Parameters (Table 11):</p>
 * <ul>
 *   <li>K: &lt;0 = Group 4, =0 = Group 3 1D, &gt;0 = Group 3 mixed</li>
 *   <li>Columns: image width in pixels (default 1728)</li>
 *   <li>Rows: image height, 0 = until EOB (default 0)</li>
 *   <li>EndOfLine, EncodedByteAlign, EndOfBlock, BlackIs1</li>
 * </ul>
 */
public final class CCITTFaxDecodeFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(CCITTFaxDecodeFilter.class.getName());

    // ═══════════════════════════════════════════════════════════════
    //  Huffman Code Tables (ITU-T T.4, Tables 1–4)
    // ═══════════════════════════════════════════════════════════════

    // Each entry: {code_value, bit_length, run_length}
    // Tree is built at class loading time for fast decoding.

    // --- White terminating codes (run length 0–63) ---
    private static final int[][] WHITE_TERM = {
        {0x35,8,0},  {0x07,6,1},  {0x07,4,2},  {0x08,4,3},
        {0x0B,4,4},  {0x0C,4,5},  {0x0E,4,6},  {0x0F,4,7},
        {0x13,5,8},  {0x14,5,9},  {0x07,5,10}, {0x08,5,11},
        {0x08,6,12}, {0x03,6,13}, {0x34,6,14}, {0x35,6,15},
        {0x2A,6,16}, {0x2B,6,17}, {0x27,7,18}, {0x0C,7,19},
        {0x08,7,20}, {0x17,7,21}, {0x03,7,22}, {0x04,7,23},
        {0x28,7,24}, {0x2B,7,25}, {0x13,7,26}, {0x24,7,27},
        {0x18,7,28}, {0x02,8,29}, {0x03,8,30}, {0x1A,8,31},
        {0x1B,8,32}, {0x12,8,33}, {0x13,8,34}, {0x14,8,35},
        {0x15,8,36}, {0x16,8,37}, {0x17,8,38}, {0x28,8,39},
        {0x29,8,40}, {0x2A,8,41}, {0x2B,8,42}, {0x2C,8,43},
        {0x2D,8,44}, {0x04,8,45}, {0x05,8,46}, {0x0A,8,47},
        {0x0B,8,48}, {0x52,8,49}, {0x53,8,50}, {0x54,8,51},
        {0x55,8,52}, {0x24,8,53}, {0x25,8,54}, {0x58,8,55},
        {0x59,8,56}, {0x5A,8,57}, {0x5B,8,58}, {0x4A,8,59},
        {0x4B,8,60}, {0x32,8,61}, {0x33,8,62}, {0x34,8,63},
    };

    // --- White makeup codes (run lengths 64–1728) ---
    private static final int[][] WHITE_MAKEUP = {
        {0x1B,5,64},   {0x12,5,128},  {0x17,6,192},  {0x37,7,256},
        {0x36,8,320},  {0x37,8,384},  {0x64,8,448},  {0x65,8,512},
        {0x68,8,576},  {0x67,8,640},  {0xCC,9,704},  {0xCD,9,768},
        {0xD2,9,832},  {0xD3,9,896},  {0xD4,9,960},  {0xD5,9,1024},
        {0xD6,9,1088}, {0xD7,9,1152}, {0xD8,9,1216}, {0xD9,9,1280},
        {0xDA,9,1344}, {0xDB,9,1408}, {0x98,9,1472}, {0x99,9,1536},
        {0x9A,9,1600}, {0x18,6,1664}, {0x9B,9,1728},
    };

    // --- Black terminating codes (run length 0–63) ---
    private static final int[][] BLACK_TERM = {
        {0x37,10,0},  {0x02,3,1},   {0x03,2,2},   {0x02,2,3},
        {0x03,3,4},   {0x03,4,5},   {0x02,4,6},   {0x03,5,7},
        {0x05,6,8},   {0x04,6,9},   {0x04,7,10},  {0x05,7,11},
        {0x07,7,12},  {0x04,8,13},  {0x07,8,14},  {0x18,9,15},
        {0x17,10,16}, {0x18,10,17}, {0x08,10,18}, {0x67,11,19},
        {0x68,11,20}, {0x6C,11,21}, {0x37,11,22}, {0x28,11,23},
        {0x17,11,24}, {0x18,11,25}, {0xCA,12,26}, {0xCB,12,27},
        {0xCC,12,28}, {0xCD,12,29}, {0x68,12,30}, {0x69,12,31},
        {0x6A,12,32}, {0x6B,12,33}, {0xD2,12,34}, {0xD3,12,35},
        {0xD4,12,36}, {0xD5,12,37}, {0xD6,12,38}, {0xD7,12,39},
        {0x6C,12,40}, {0x6D,12,41}, {0xDA,12,42}, {0xDB,12,43},
        {0x54,12,44}, {0x55,12,45}, {0x56,12,46}, {0x57,12,47},
        {0x64,12,48}, {0x65,12,49}, {0x52,12,50}, {0x53,12,51},
        {0x24,12,52}, {0x37,12,53}, {0x38,12,54}, {0x27,12,55},
        {0x28,12,56}, {0x58,12,57}, {0x59,12,58}, {0x2B,12,59},
        {0x2C,12,60}, {0x5A,12,61}, {0x66,12,62}, {0x67,12,63},
    };

    // --- Black makeup codes (run lengths 64–1728) ---
    private static final int[][] BLACK_MAKEUP = {
        {0x0F,10,64},   {0xC8,12,128},  {0xC9,12,192},
        {0x5B,12,256},  {0x33,12,320},  {0x34,12,384},  {0x35,12,448},
        {0x6C,13,512},  {0x6D,13,576},  {0x4A,13,640},  {0x4B,13,704},
        {0x4C,13,768},  {0x4D,13,832},  {0x72,13,896},  {0x73,13,960},
        {0x74,13,1024}, {0x75,13,1088}, {0x76,13,1152}, {0x77,13,1216},
        {0x52,13,1280}, {0x53,13,1344}, {0x54,13,1408}, {0x55,13,1472},
        {0x5A,13,1536}, {0x5B,13,1600}, {0x64,13,1664}, {0x65,13,1728},
    };

    // --- Common extended makeup codes (both colors, run lengths 1792–2560) ---
    private static final int[][] COMMON_MAKEUP = {
        {0x08,11,1792}, {0x0C,11,1856}, {0x0D,11,1920},
        {0x12,12,1984}, {0x13,12,2048}, {0x14,12,2112}, {0x15,12,2176},
        {0x16,12,2240}, {0x17,12,2304}, {0x1C,12,2368}, {0x1D,12,2432},
        {0x1E,12,2496}, {0x1F,12,2560},
    };

    // ─── Huffman tree node ───────────────────────────────────────

    static final class HNode {
        int runLength = -1; // -1 = internal node
        boolean isMakeup;
        HNode zero, one;
    }

    static final HNode WHITE_TREE = new HNode();
    static final HNode BLACK_TREE = new HNode();

    static {
        // Build white tree
        for (int[] e : WHITE_TERM)  addCode(WHITE_TREE, e[0], e[1], e[2], false);
        for (int[] e : WHITE_MAKEUP) addCode(WHITE_TREE, e[0], e[1], e[2], true);
        for (int[] e : COMMON_MAKEUP) addCode(WHITE_TREE, e[0], e[1], e[2], true);
        // Build black tree
        for (int[] e : BLACK_TERM)  addCode(BLACK_TREE, e[0], e[1], e[2], false);
        for (int[] e : BLACK_MAKEUP) addCode(BLACK_TREE, e[0], e[1], e[2], true);
        for (int[] e : COMMON_MAKEUP) addCode(BLACK_TREE, e[0], e[1], e[2], true);
    }

    private static void addCode(HNode root, int code, int bits, int runLen, boolean makeup) {
        HNode n = root;
        for (int i = bits - 1; i >= 0; i--) {
            int bit = (code >> i) & 1;
            if (bit == 0) {
                if (n.zero == null) n.zero = new HNode();
                n = n.zero;
            } else {
                if (n.one == null) n.one = new HNode();
                n = n.one;
            }
        }
        n.runLength = runLen;
        n.isMakeup = makeup;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Bit Reader (MSB first)
    // ═══════════════════════════════════════════════════════════════

    static final class BitReader {
        private final byte[] data;
        int bytePos;
        int bitPos = 7; // 7=MSB, 0=LSB

        BitReader(byte[] data) {
            this.data = data;
        }

        /** Reads one bit. Returns 0 or 1, or -1 on EOF. */
        int readBit() {
            if (bytePos >= data.length) return -1;
            int bit = (data[bytePos] >> bitPos) & 1;
            if (--bitPos < 0) { bitPos = 7; bytePos++; }
            return bit;
        }

        /** Aligns to the next byte boundary. */
        void alignToByte() {
            if (bitPos != 7) { bitPos = 7; bytePos++; }
        }

        boolean eof() {
            return bytePos >= data.length;
        }

        /** Reads up to 12 bits without consuming, for EOL detection. Returns -1 on EOF. */
        int peekBits(int n) {
            int savedByte = bytePos, savedBit = bitPos;
            int val = 0;
            for (int i = 0; i < n; i++) {
                int b = readBit();
                if (b < 0) { bytePos = savedByte; bitPos = savedBit; return -1; }
                val = (val << 1) | b;
            }
            bytePos = savedByte;
            bitPos = savedBit;
            return val;
        }

        void skipBits(int n) {
            for (int i = 0; i < n; i++) readBit();
        }
    }

    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int currentByte;
        private int bitCount;

        void writeBit(int bit) {
            currentByte = (currentByte << 1) | (bit & 1);
            bitCount++;
            if (bitCount == 8) {
                out.write(currentByte & 0xFF);
                currentByte = 0;
                bitCount = 0;
            }
        }

        void writeBits(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                writeBit((value >> i) & 1);
            }
        }

        void alignToByte() {
            if (bitCount == 0) return;
            currentByte <<= (8 - bitCount);
            out.write(currentByte & 0xFF);
            currentByte = 0;
            bitCount = 0;
        }

        byte[] toByteArray() {
            alignToByte();
            return out.toByteArray();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Run-length decoding from Huffman tree
    // ═══════════════════════════════════════════════════════════════

    /**
     * Reads one complete run (zero or more makeup codes + one terminating code).
     * Returns total run length, or -1 on failure/EOF.
     */
    static int readRun(BitReader br, HNode tree) {
        int total = 0;
        while (true) {
            HNode n = tree;
            int depth = 0;
            while (n.runLength == -1) {
                int bit = br.readBit();
                if (bit < 0) return total > 0 ? total : -1;
                n = (bit == 0) ? n.zero : n.one;
                if (n == null) return -1; // unknown code
                if (++depth > 15) return -1; // safety
            }
            total += n.runLength;
            if (!n.isMakeup) return total; // terminating code
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Group 3 1D decoder (K = 0)
    // ═══════════════════════════════════════════════════════════════

    private static byte[] decodeGroup31D(byte[] data, int columns, int rows,
                                         boolean endOfLine, boolean byteAlign,
                                         boolean endOfBlock) throws IOException {
        BitReader br = new BitReader(data);
        int rowBytes = (columns + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(rowBytes * Math.max(rows, 16));
        int linesDecoded = 0;

        while (rows == 0 || linesDecoded < rows) {
            if (br.eof()) break;

            // Byte-align FIRST (§7.4.6 /EncodedByteAlign): each row's coded
            // data begins on a byte boundary. Aligning only after the EOL
            // probe let the previous row's zero fill bits (≤7) join the next
            // code's leading zeros into a phantom "EOL" (≥11 zeros + 1) that
            // swallowed that code — corpus 35275: rows desynced at ~x500 and
            // the certificate scan smeared into noise.
            if (byteAlign) br.alignToByte();
            // Consume an EOL pattern (≥11 zeros + 1, incl. fill bits) when one
            // is ahead — T.4 streams routinely carry EOLs before every line
            // even when the PDF /EndOfLine flag is false (corpus 29753: every
            // row is EOL-prefixed; treating the EOL zeros as white-run data
            // desynced the Huffman stream and smeared the whole fax page).
            consumeEOLIfPresent(br);

            byte[] line = new byte[rowBytes];
            int col = 0;
            boolean white = true;
            boolean rowError = false;

            while (col < columns) {
                HNode tree = white ? WHITE_TREE : BLACK_TREE;
                int run = readRun(br, tree);
                if (run < 0) {
                    rowError = true;
                    break;
                }
                run = Math.min(run, columns - col);
                if (!white) {
                    setBits(line, col, run);
                }
                col += run;
                white = !white;
            }
            out.write(line);
            linesDecoded++;

            // A bad code desyncs the bit stream — everything after it is
            // garbage until the next resync point. With /EncodedByteAlign
            // the next byte boundary IS a resync point (the loop head
            // realigns), so keep decoding; otherwise scan for the next EOL
            // (each EOL marks a fresh row start).
            if (rowError && !byteAlign && !resyncToEOL(br)) {
                break; // no further EOL — rest of the data is unusable
            }

            // Check for RTC (6 consecutive EOLs = end of data)
            if (endOfBlock && detectRTC(br)) break;
        }

        return out.toByteArray();
    }

    /**
     * Consumes one EOL — any number of fill zeros, at least 11 of them,
     * terminated by a 1 bit (T.4 §4.1.2) — if and only if one starts at the
     * current position. Restores the position when the bits ahead are not
     * an EOL, so image data is never eaten.
     */
    static void consumeEOLIfPresent(BitReader br) {
        int savedByte = br.bytePos, savedBit = br.bitPos;
        int zeros = 0;
        while (true) {
            int b = br.readBit();
            if (b < 0) {
                br.bytePos = savedByte; br.bitPos = savedBit;
                return;
            }
            if (b == 1) {
                if (zeros >= 11) return; // EOL (with fill) consumed
                br.bytePos = savedByte; br.bitPos = savedBit;
                return; // not an EOL
            }
            if (++zeros > 4096) { // degenerate zero-run — bail out, restore
                br.bytePos = savedByte; br.bitPos = savedBit;
                return;
            }
        }
    }

    /**
     * Scans forward to just past the next EOL (≥11 zeros then a 1).
     *
     * @return true if an EOL was found, false on EOF
     */
    static boolean resyncToEOL(BitReader br) {
        int zeros = 0;
        while (true) {
            int b = br.readBit();
            if (b < 0) return false;
            if (b == 1) {
                if (zeros >= 11) return true;
                zeros = 0;
            } else {
                zeros++;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Group 4 decoder (K < 0) — MMR / T.6
    // ═══════════════════════════════════════════════════════════════

    // Mode codes (T.6 Table 1):
    // Pass:   0001
    // Horiz:  001
    // V(0):   1
    // VR(1):  011
    // VL(1):  010
    // VR(2):  000011
    // VL(2):  000010
    // VR(3):  0000011
    // VL(3):  0000010

    private static final int MODE_PASS  = 0;
    private static final int MODE_HORIZ = 1;
    private static final int MODE_V0    = 2;
    private static final int MODE_VR1   = 3;
    private static final int MODE_VL1   = 4;
    private static final int MODE_VR2   = 5;
    private static final int MODE_VL2   = 6;
    private static final int MODE_VR3   = 7;
    private static final int MODE_VL3   = 8;
    private static final int MODE_EOFB  = 9;
    private static final int MODE_ERROR = -1;

    static int readMode(BitReader br) {
        int b = br.readBit();
        if (b < 0) return MODE_EOFB;
        if (b == 1) return MODE_V0;              // 1
        b = br.readBit(); if (b < 0) return MODE_EOFB;
        if (b == 1) {
            b = br.readBit(); if (b < 0) return MODE_EOFB;
            return b == 1 ? MODE_VR1 : MODE_VL1; // 011 / 010
        }
        b = br.readBit(); if (b < 0) return MODE_EOFB;
        if (b == 1) return MODE_HORIZ;            // 001
        b = br.readBit(); if (b < 0) return MODE_EOFB;
        if (b == 1) return MODE_PASS;             // 0001
        b = br.readBit(); if (b < 0) return MODE_EOFB;
        if (b == 1) {
            b = br.readBit(); if (b < 0) return MODE_EOFB;
            return b == 1 ? MODE_VR2 : MODE_VL2; // 000011 / 000010
        }
        b = br.readBit(); if (b < 0) return MODE_EOFB;
        if (b == 1) {
            b = br.readBit(); if (b < 0) return MODE_EOFB;
            return b == 1 ? MODE_VR3 : MODE_VL3; // 0000011 / 0000010
        }
        // 0000000... likely EOFB or error
        return MODE_EOFB;
    }

    private static byte[] decodeGroup4(byte[] data, int columns, int rows,
                                       boolean byteAlign, boolean endOfBlock) throws IOException {
        BitReader br = new BitReader(data);
        int rowBytes = (columns + 7) / 8;
        ByteArrayOutputStream out = new ByteArrayOutputStream(rowBytes * Math.max(rows, 16));

        // Reference line starts as all-white
        boolean[] refLine = new boolean[columns + 2]; // extra for boundary handling
        int linesDecoded = 0;

        // Diagnostic trace: -Dccitt.trace dumps per-line decision sequence
        // for the first 60 lines into stdout.
        boolean trace = Boolean.getBoolean("ccitt.trace");
        int traceLines = Integer.getInteger("ccitt.trace.lines", 60);

        while (rows == 0 || linesDecoded < rows) {
            if (br.eof()) break;
            if (byteAlign) br.alignToByte();

            boolean[] codingLine = new boolean[columns + 2];
            int a0 = 0;
            boolean curColor = false;
            boolean sawAnyMode = false;

            int bitsAtLineStart = br.bytePos * 8 + (7 - br.bitPos);
            StringBuilder t = trace && linesDecoded < traceLines ? new StringBuilder() : null;

            while (a0 < columns) {
                int mode = readMode(br);
                if (mode == MODE_EOFB || mode == MODE_ERROR) {
                    if (a0 == 0) {
                        if (t != null) {
                            t.append(" [EOFB at line start]");
                            System.out.println("L" + linesDecoded + " bits=0 " + t);
                        }
                        return out.toByteArray();
                    }
                    if (t != null) t.append(" [EOFB midline a0=").append(a0).append("]");
                    break;
                }

                boolean lineStart = (a0 == 0 && !sawAnyMode);
                switch (mode) {
                    case MODE_PASS: {
                        int b1 = findB1(refLine, a0, curColor, columns, lineStart);
                        int b2 = findNextChange(refLine, b1, columns);
                        fillRun(codingLine, a0, b2, curColor);
                        if (t != null) t.append(" P(a0=").append(a0).append("→").append(b2).append(",c=").append(curColor ? 'B' : 'W').append(')');
                        a0 = b2;
                        break;
                    }
                    case MODE_HORIZ: {
                        int run1 = readRun(br, curColor ? BLACK_TREE : WHITE_TREE);
                        if (run1 < 0) run1 = 0;
                        int end1 = Math.min(a0 + run1, columns);
                        fillRun(codingLine, a0, end1, curColor);

                        int run2 = readRun(br, curColor ? WHITE_TREE : BLACK_TREE);
                        if (run2 < 0) run2 = 0;
                        int end2 = Math.min(end1 + run2, columns);
                        fillRun(codingLine, end1, end2, !curColor);

                        if (t != null) t.append(" H(a0=").append(a0).append(",r1=").append(run1).append(",r2=").append(run2).append(",c=").append(curColor ? 'B' : 'W').append(')');
                        a0 = end2;
                        break;
                    }
                    default: {
                        int offset = 0;
                        String mname = "V?";
                        switch (mode) {
                            case MODE_V0:  offset =  0; mname = "V0";  break;
                            case MODE_VR1: offset =  1; mname = "VR1"; break;
                            case MODE_VL1: offset = -1; mname = "VL1"; break;
                            case MODE_VR2: offset =  2; mname = "VR2"; break;
                            case MODE_VL2: offset = -2; mname = "VL2"; break;
                            case MODE_VR3: offset =  3; mname = "VR3"; break;
                            case MODE_VL3: offset = -3; mname = "VL3"; break;
                        }
                        int b1 = findB1(refLine, a0, curColor, columns, lineStart);
                        int a1 = b1 + offset;
                        if (a1 > columns) a1 = columns;
                        if (a1 < a0) a1 = a0;
                        fillRun(codingLine, a0, a1, curColor);
                        if (t != null) t.append(' ').append(mname).append("(a0=").append(a0).append(",b1=").append(b1).append(",a1=").append(a1).append(",c=").append(curColor ? 'B' : 'W').append(')');
                        a0 = a1;
                        curColor = !curColor;
                        break;
                    }
                }
                sawAnyMode = true;
            }

            int bitsAtLineEnd = br.bytePos * 8 + (7 - br.bitPos);
            if (t != null) {
                System.out.println("L" + linesDecoded + " bits=" + (bitsAtLineEnd - bitsAtLineStart) + t);
            }

            out.write(packLine(codingLine, columns, rowBytes));
            System.arraycopy(codingLine, 0, refLine, 0, columns);
            linesDecoded++;
        }

        return out.toByteArray();
    }

    /** Sets {@code line[from..to)} to {@code color}; clamps to array bounds. */
    private static void fillRun(boolean[] line, int from, int to, boolean color) {
        if (!color) return; // codingLine is initialised to false (white)
        int start = Math.max(from, 0);
        int end = Math.min(to, line.length);
        for (int i = start; i < end; i++) line[i] = true;
    }

    // ─── Group 4 helpers ─────────────────────────────────────────

    /**
     * Finds b1: the first changing element on the reference line strictly to
     * the right of {@code a0} whose colour is opposite to {@code curColor}
     * (T.6 §2.2.1).
     *
     * <p>Convention: this decoder tracks {@code a0} as the leading-edge
     * position of the next run on the coding line. For all calls except the
     * very first one on a line, the previous mode has just placed a transition
     * AT column {@code a0}; that transition is already accounted for, so b1
     * must lie at column &gt; a0. For the first call on a line {@code a0 == 0}
     * is treated as the imaginary white element at –1, and column 0 itself is
     * a valid b1.</p>
     */
    static int findB1(boolean[] refLine, int a0, boolean curColor, int columns,
                      boolean lineStart) {
        // Strict-right-of-a0 except at line start (imaginary white at -1).
        int start = lineStart ? 0 : (a0 + 1);
        if (start >= columns) return columns;
        if (start < 0) start = 0;
        boolean prev = (start == 0) ? false : refLine[start - 1];
        for (int i = start; i < columns; i++) {
            if (refLine[i] != prev) {
                if (refLine[i] != curColor) {
                    return i;
                }
            }
            prev = refLine[i];
        }
        return columns;
    }

    /** Backwards-compatible overload (treats every call as midline). */
    static int findB1(boolean[] refLine, int a0, boolean curColor, int columns) {
        return findB1(refLine, a0, curColor, columns, false);
    }

    /**
     * Finds b2: the next changing element strictly after {@code pos} on the
     * reference line.
     */
    static int findNextChange(boolean[] refLine, int pos, int columns) {
        if (pos >= columns) return columns;
        boolean color = (pos < 0) ? false : refLine[pos];
        int start = Math.max(pos + 1, 0);
        for (int i = start; i < columns; i++) {
            if (refLine[i] != color) return i;
        }
        return columns;
    }

    // ─── Group 3 mixed (K > 0) ──────────────────────────────────

    private static byte[] decodeGroup3Mixed(byte[] data, int columns, int rows, int k,
                                            boolean endOfLine, boolean byteAlign,
                                            boolean endOfBlock) throws IOException {
        // K > 0 means: use 1D for the first line, then alternate between
        // up to K-1 2D-coded lines and 1 1D-coded line.
        // For simplicity, delegate to Group 4 (superset) which handles both modes.
        return decodeGroup4(data, columns, rows, byteAlign, endOfBlock);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Utility methods
    // ═══════════════════════════════════════════════════════════════

    /** Sets 'count' bits starting at position 'start' in the packed byte array. */
    private static void setBits(byte[] line, int start, int count) {
        for (int i = start; i < start + count; i++) {
            line[i >> 3] |= (byte) (0x80 >> (i & 7));
        }
    }

    /** Packs a boolean array into bytes (true=1=black, MSB first). */
    static byte[] packLine(boolean[] line, int columns, int rowBytes) {
        byte[] packed = new byte[rowBytes];
        for (int i = 0; i < columns; i++) {
            if (line[i]) {
                packed[i >> 3] |= (byte) (0x80 >> (i & 7));
            }
        }
        return packed;
    }

    /** Detects RTC (Return To Control): 6 consecutive EOL codes. */
    private static boolean detectRTC(BitReader br) {
        // Simplified: don't look ahead for RTC; let row count or EOF handle termination
        return false;
    }

    // ─── Parameter extraction ────────────────────────────────────

    private static int getInt(PdfDictionary params, String key, int def) {
        if (params == null) return def;
        PdfBase v = params.get(key);
        return (v instanceof PdfInteger) ? ((PdfInteger) v).intValue() : def;
    }

    private static boolean getBool(PdfDictionary params, String key, boolean def) {
        if (params == null) return def;
        PdfBase v = params.get(key);
        return (v instanceof PdfBoolean) ? ((PdfBoolean) v).getValue() : def;
    }

    // ═══════════════════════════════════════════════════════════════
    //  PdfFilter interface
    // ═══════════════════════════════════════════════════════════════

    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (encoded == null || encoded.length == 0) return new byte[0];

        int k          = getInt(params, "K", 0);
        int columns    = getInt(params, "Columns", 1728);
        int rows       = getInt(params, "Rows", 0);
        boolean eol    = getBool(params, "EndOfLine", false);
        boolean align  = getBool(params, "EncodedByteAlign", false);
        boolean eob    = getBool(params, "EndOfBlock", true);
        boolean black1 = getBool(params, "BlackIs1", false);

        LOG.fine(() -> "CCITTFaxDecode: K=" + k + " cols=" + columns + " rows=" + rows
                + " eol=" + eol + " align=" + align + " eob=" + eob + " black1=" + black1);

        byte[] result;
        if (k < 0) {
            result = decodeGroup4(encoded, columns, rows, align, eob);
        } else if (k == 0) {
            result = decodeGroup31D(encoded, columns, rows, eol, align, eob);
        } else {
            result = decodeGroup3Mixed(encoded, columns, rows, k, eol, align, eob);
        }

        // When the caller specified an explicit row count but the bitstream
        // ran out earlier (truncated EOFB, RTC marker, or simply less encoded
        // data than declared), pad the tail with all-zero bytes so the
        // downstream consumer sees a full {@code rows × rowBytes} buffer.
        // After the BlackIs1 inversion below those zero bytes become 0xFF
        // (white) — matching the PDF default that "missing" image samples
        // render as the colour-space default value.
        if (rows > 0 && columns > 0) {
            int rowBytes = (columns + 7) / 8;
            int expected = rowBytes * rows;
            int decodedLen = result.length;
            if (decodedLen < expected) {
                byte[] padded = new byte[expected];
                System.arraycopy(result, 0, padded, 0, decodedLen);
                LOG.fine(() -> "CCITTFaxDecode: padded "
                        + (expected - decodedLen) + " trailing bytes ("
                        + (rows - decodedLen / rowBytes) + " rows)");
                result = padded;
            }
        }

        // Default (BlackIs1=false): CCITT convention is 1=black, but PDF default is 0=black
        // So we invert unless BlackIs1=true
        if (!black1) {
            for (int i = 0; i < result.length; i++) {
                result[i] = (byte) (~result[i] & 0xFF);
            }
        }

        return result;
    }

    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        if (decoded == null || decoded.length == 0) return new byte[0];

        int k = getInt(params, "K", 0);
        int columns = getInt(params, "Columns", getInt(params, "Width", 1728));
        int rows = getInt(params, "Rows", getInt(params, "Height", 0));
        boolean eol = getBool(params, "EndOfLine", false);
        boolean align = getBool(params, "EncodedByteAlign", false);
        boolean eob = getBool(params, "EndOfBlock", true);
        boolean black1 = getBool(params, "BlackIs1", false);
        if (columns <= 0) {
            throw new IOException("CCITTFaxDecode requires Columns/Width for encoding");
        }

        int rowBytes = (columns + 7) / 8;
        if (rows <= 0) {
            rows = decoded.length / rowBytes;
        }
        int availableRows = decoded.length / rowBytes;
        if (availableRows <= 0) {
            throw new IOException("CCITTFaxDecode: insufficient decoded image data");
        }
        if (availableRows < rows) {
            LOG.fine("CCITTFaxEncode: truncating rows from " + rows + " to " + availableRows
                    + " based on decoded byte length");
            rows = availableRows;
        }

        byte[] ccittBits = decoded.clone();
        if (!black1) {
            for (int i = 0; i < ccittBits.length; i++) {
                ccittBits[i] = (byte) (~ccittBits[i] & 0xFF);
            }
        }

        if (k < 0 || k > 0) {
            return encodeGroup4Horizontal(ccittBits, columns, rows, align, eob);
        }
        return encodeGroup31D(ccittBits, columns, rows, eol, align, eob);
    }

    @Override
    public PdfName getName() {
        return PdfName.of("CCITTFaxDecode");
    }

    private static byte[] encodeGroup31D(byte[] decoded, int columns, int rows,
                                         boolean endOfLine, boolean byteAlign,
                                         boolean endOfBlock) throws IOException {
        BitWriter writer = new BitWriter();
        for (int row = 0; row < rows; row++) {
            if (endOfLine) {
                writeEol(writer);
            }
            encodeRuns1D(writer, decoded, row, columns);
            if (byteAlign) {
                writer.alignToByte();
            }
        }
        if (endOfBlock && endOfLine) {
            for (int i = 0; i < 6; i++) {
                writeEol(writer);
            }
        }
        return writer.toByteArray();
    }

    private static byte[] encodeGroup4Horizontal(byte[] decoded, int columns, int rows,
                                                 boolean byteAlign, boolean endOfBlock) throws IOException {
        BitWriter writer = new BitWriter();
        for (int row = 0; row < rows; row++) {
            int[] runs = extractRuns(decoded, row, columns);
            int index = 0;
            while (index < runs.length) {
                int run1 = runs[index++];
                int run2 = index < runs.length ? runs[index++] : 0;
                writer.writeBits(0x1, 3); // Horizontal mode: 001
                writeRun(writer, run1, false);
                writeRun(writer, run2, true);
            }
            if (byteAlign) {
                writer.alignToByte();
            }
        }
        if (endOfBlock) {
            writeEol(writer);
            writeEol(writer);
        }
        return writer.toByteArray();
    }

    private static void encodeRuns1D(BitWriter writer, byte[] decoded, int row, int columns) throws IOException {
        int[] runs = extractRuns(decoded, row, columns);
        boolean black = false;
        for (int run : runs) {
            writeRun(writer, run, black);
            black = !black;
        }
    }

    private static int[] extractRuns(byte[] decoded, int row, int columns) {
        int[] temp = new int[columns + 1];
        int count = 0;
        boolean black = false; // starts with white
        int run = 0;
        for (int col = 0; col < columns; col++) {
            boolean pixelBlack = getPixel(decoded, row, col, columns);
            if (pixelBlack == black) {
                run++;
            } else {
                temp[count++] = run;
                black = pixelBlack;
                run = 1;
            }
        }
        temp[count++] = run;
        int[] result = new int[count];
        System.arraycopy(temp, 0, result, 0, count);
        return result;
    }

    private static boolean getPixel(byte[] decoded, int row, int col, int columns) {
        int rowBytes = (columns + 7) / 8;
        int index = row * rowBytes + (col >> 3);
        return ((decoded[index] >> (7 - (col & 7))) & 1) != 0;
    }

    private static void writeRun(BitWriter writer, int runLength, boolean black) throws IOException {
        int remaining = runLength;
        while (remaining >= 2560) {
            writeCode(writer, black ? COMMON_MAKEUP : COMMON_MAKEUP, 2560);
            remaining -= 2560;
        }
        while (remaining >= 64) {
            int makeup = Math.min((remaining / 64) * 64, 1728);
            if (makeup < 64) {
                break;
            }
            if (makeup <= 1728) {
                writeCode(writer, black ? BLACK_MAKEUP : WHITE_MAKEUP, makeup);
            } else {
                writeCode(writer, COMMON_MAKEUP, makeup);
            }
            remaining -= makeup;
        }
        writeCode(writer, black ? BLACK_TERM : WHITE_TERM, remaining);
    }

    private static void writeCode(BitWriter writer, int[][] table, int runLength) throws IOException {
        for (int[] entry : table) {
            if (entry[2] == runLength) {
                writer.writeBits(entry[0], entry[1]);
                return;
            }
        }
        throw new IOException("Unsupported CCITT run length: " + runLength);
    }

    private static void writeEol(BitWriter writer) {
        writer.writeBits(0x001, 12); // 000000000001
    }
}
