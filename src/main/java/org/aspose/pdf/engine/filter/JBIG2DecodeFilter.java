package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JBIG2Decode filter — decodes JBIG2-encoded monochrome images.
 * ISO 32000-1:2008 §7.4.7, encoding standard: ISO/IEC 11544 (ITU T.88).
 *
 * <p>Supports:</p>
 * <ul>
 *   <li>Segment header parsing for all segment types</li>
 *   <li>Page Information segments (type 48) — page buffer allocation</li>
 *   <li>Generic Region segments with MMR coding — reuses Group 4 decoder from
 *       {@link CCITTFaxDecodeFilter}</li>
 *   <li>Immediate lossless generic regions (types 36, 38, 39, 40, 42, 43)</li>
 *   <li>End of Page (49) and End of File (51)</li>
 * </ul>
 *
 * <p>Unsupported segment types (symbol dictionaries, text regions, halftone regions,
 * pattern dictionaries, etc.) are logged and skipped. Full symbol-based decoding
 * is planned for Stage 3.</p>
 *
 * <p>Parameters (Table 12):</p>
 * <ul>
 *   <li>/JBIG2Globals: optional stream containing global segments (symbol dictionaries)</li>
 * </ul>
 */
public final class JBIG2DecodeFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(JBIG2DecodeFilter.class.getName());
    private static final int MAX_JBIG2_DIMENSION = 100_000;
    private static final int MAX_JBIG2_BITMAP_PIXELS = 100_000_000;
    private static final int MAX_JBIG2_SYMBOLS = 65_536;
    private static final int MAX_JBIG2_CONTEXTS = 1_000_000;

    // ─── Segment types (ISO/IEC 11544, Table 2) ─────────────────

    private static final int SEG_SYMBOL_DICT          = 0;
    private static final int SEG_TEXT_REGION_INT        = 4;  // Intermediate text region
    private static final int SEG_TEXT_REGION            = 6;
    private static final int SEG_TEXT_REGION_IMM        = 7;
    private static final int SEG_PATTERN_DICT          = 16;
    private static final int SEG_HALFTONE_REGION_INT    = 20; // Intermediate halftone region
    private static final int SEG_HALFTONE_REGION       = 22;
    private static final int SEG_HALFTONE_REGION_IMM   = 23;
    private static final int SEG_GENERIC_REGION        = 36;
    private static final int SEG_GENERIC_REGION_IMM    = 38;
    private static final int SEG_GENERIC_REGION_IMM_L  = 39;
    private static final int SEG_GENERIC_REFINE        = 40;
    private static final int SEG_GENERIC_REFINE_IMM    = 42;
    private static final int SEG_GENERIC_REFINE_IMM_L  = 43;
    private static final int SEG_PAGE_INFO             = 48;
    private static final int SEG_END_OF_PAGE           = 49;
    private static final int SEG_END_OF_STRIPE         = 50;
    private static final int SEG_END_OF_FILE           = 51;
    private static final int SEG_PROFILES              = 52;
    private static final int SEG_TABLES                = 53;
    private static final int SEG_EXTENSION             = 62;

    // ─── Parsed segment ──────────────────────────────────────────

    private static final class Segment {
        int number;
        int type;
        boolean pageAssocLong;
        boolean deferredNonRetain;
        int referredToCount;
        int[] referredTo;
        int pageAssoc;
        int dataLength; // -1 = unknown
        int dataOffset;
        byte[] sourceData; // which byte array this segment's data lives in
    }

    // ─── Page state ──────────────────────────────────────────────

    private static final class PageInfo {
        int width;
        int height;
        boolean defaultPixel; // default fill: false=white, true=black
        int combinationOp;    // 0=OR, 1=AND, 2=XOR, 3=XNOR, 4=REPLACE
    }

    /**
     * Decoded symbol dictionary: stores bitmaps of individual symbols.
     * Each bitmap is a flat boolean array of size width*height.
     */
    static final class SymbolDictionary {
        boolean[][] bitmaps;  // [symbolIndex] → flat bitmap
        int[] widths;         // width of each symbol
        int[] heights;        // height of each symbol
        int numSymbols;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Segment header parser
    // ═══════════════════════════════════════════════════════════════

    private static List<Segment> parseSegments(byte[] data, int offset, int end) throws IOException {
        List<Segment> segments = new ArrayList<>();
        int pos = offset;

        while (pos < end - 6) { // minimum header = 7 bytes
            Segment seg = new Segment();
            seg.number = readU32(data, pos); pos += 4;

            int flags = data[pos++] & 0xFF;
            seg.type = flags & 0x3F;
            seg.pageAssocLong = (flags & 0x40) != 0;
            seg.deferredNonRetain = (flags & 0x80) != 0;

            // Referred-to segment count
            int refByte = data[pos++] & 0xFF;
            int refCountHigh = (refByte >> 5) & 7;
            if (refCountHigh < 5) {
                seg.referredToCount = refCountHigh;
            } else if (refCountHigh == 5 || refCountHigh == 6) {
                // §7.2.4: values 5 and 6 are invalid in the 3-bit subfield
                LOG.warning("JBIG2: Invalid referred-to count indicator: " + refCountHigh);
                break; // skip this segment
            } else {
                // Long form (7): next 4 bytes = count, followed by retain flags
                if (pos + 3 >= end) break;
                seg.referredToCount = readU32(data, pos - 1) & 0x1FFFFFFF;
                pos += 3;
                // Skip retain flags: ceil((referredToCount + 1) / 8) bytes
                pos += (seg.referredToCount + 8) / 8;
            }

            // Referred-to segment numbers
            seg.referredTo = new int[seg.referredToCount];
            int refSize = (seg.number <= 256) ? 1 : (seg.number <= 65536) ? 2 : 4;
            for (int i = 0; i < seg.referredToCount; i++) {
                if (pos + refSize > end) break;
                switch (refSize) {
                    case 1: seg.referredTo[i] = data[pos++] & 0xFF; break;
                    case 2: seg.referredTo[i] = readU16(data, pos); pos += 2; break;
                    default: seg.referredTo[i] = readU32(data, pos); pos += 4; break;
                }
            }

            // Page association
            if (seg.pageAssocLong) {
                if (pos + 4 > end) break;
                seg.pageAssoc = readU32(data, pos); pos += 4;
            } else {
                if (pos >= end) break;
                seg.pageAssoc = data[pos++] & 0xFF;
            }

            // Data length
            if (pos + 4 > end) break;
            seg.dataLength = readU32(data, pos); pos += 4;
            seg.dataOffset = pos;

            if (seg.dataLength >= 0 && seg.dataLength != 0xFFFFFFFF) {
                pos += seg.dataLength;
            } else {
                // Unknown length — scan for end marker
                // For simplicity, set remaining data
                seg.dataLength = end - pos;
                pos = end;
            }

            segments.add(seg);

            if (seg.type == SEG_END_OF_FILE) break;
        }

        return segments;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Segment processors
    // ═══════════════════════════════════════════════════════════════

    private static PageInfo processPageInfo(byte[] data, Segment seg) {
        int off = seg.dataOffset;
        PageInfo pi = new PageInfo();
        pi.width = readU32(data, off);
        pi.height = readU32(data, off + 4);
        // bytes 8-11: X resolution, 12-15: Y resolution (ignored)
        if (seg.dataLength >= 19) {
            int flags = data[off + 16] & 0xFF;
            pi.defaultPixel = (flags & 0x04) != 0;
            pi.combinationOp = (flags >> 3) & 3;
        }
        return pi;
    }

    /**
     * Decodes a Generic Region segment using MMR (Modified Modified READ = Group 4).
     * JBIG2 generic regions with MMR use the same coding as CCITT Group 4.
     */
    private static boolean[] decodeGenericRegionMMR(byte[] data, int dataOffset, int dataLength,
                                                    int regionW, int regionH) throws IOException {
        // Reuse CCITTFaxDecodeFilter's Group 4 decoder via BitReader
        CCITTFaxDecodeFilter.BitReader br = new CCITTFaxDecodeFilter.BitReader(
                Arrays.copyOfRange(data, dataOffset, dataOffset + dataLength));

        boolean[] bitmap = new boolean[regionW * regionH];
        boolean[] refLine = new boolean[regionW + 2];
        int rowBytes = (regionW + 7) / 8;

        for (int row = 0; row < regionH; row++) {
            boolean[] codingLine = new boolean[regionW + 2];
            int a0 = 0;
            boolean curColor = false; // starts white

            while (a0 < regionW) {
                int mode = CCITTFaxDecodeFilter.readMode(br);
                if (mode == 9 /* EOFB */ || mode == -1 /* ERROR */) break;

                switch (mode) {
                    case 0: { // PASS
                        int b1 = CCITTFaxDecodeFilter.findB1(refLine, a0, curColor, regionW);
                        int b2 = CCITTFaxDecodeFilter.findNextChange(refLine, b1, regionW);
                        a0 = b2;
                        break;
                    }
                    case 1: { // HORIZONTAL
                        CCITTFaxDecodeFilter.HNode tree1 = curColor
                                ? CCITTFaxDecodeFilter.BLACK_TREE : CCITTFaxDecodeFilter.WHITE_TREE;
                        int run1 = CCITTFaxDecodeFilter.readRun(br, tree1);
                        if (run1 < 0) run1 = 0;
                        run1 = Math.min(run1, regionW - a0);
                        if (curColor) {
                            for (int i = a0; i < a0 + run1; i++) codingLine[i] = true;
                        }
                        a0 += run1;

                        CCITTFaxDecodeFilter.HNode tree2 = curColor
                                ? CCITTFaxDecodeFilter.WHITE_TREE : CCITTFaxDecodeFilter.BLACK_TREE;
                        int run2 = CCITTFaxDecodeFilter.readRun(br, tree2);
                        if (run2 < 0) run2 = 0;
                        run2 = Math.min(run2, regionW - a0);
                        if (!curColor) {
                            for (int i = a0; i < a0 + run2; i++) codingLine[i] = true;
                        }
                        a0 += run2;
                        break;
                    }
                    default: { // VERTICAL modes: V0=2, VR1=3, VL1=4, VR2=5, VL2=6, VR3=7, VL3=8
                        int offset;
                        switch (mode) {
                            case 2: offset = 0; break;
                            case 3: offset = 1; break;
                            case 4: offset = -1; break;
                            case 5: offset = 2; break;
                            case 6: offset = -2; break;
                            case 7: offset = 3; break;
                            case 8: offset = -3; break;
                            default: offset = 0; break;
                        }
                        int b1 = CCITTFaxDecodeFilter.findB1(refLine, a0, curColor, regionW);
                        int a1 = Math.max(a0, Math.min(b1 + offset, regionW));
                        if (curColor) {
                            for (int i = a0; i < a1; i++) codingLine[i] = true;
                        }
                        a0 = a1;
                        curColor = !curColor;
                        break;
                    }
                }
            }

            // Copy coding line to bitmap
            System.arraycopy(codingLine, 0, bitmap, row * regionW, regionW);
            // Current line becomes reference
            System.arraycopy(codingLine, 0, refLine, 0, regionW);
        }

        return bitmap;
    }

    /**
     * Decodes a generic region using arithmetic coding.
     * ISO/IEC 11544 §6.2.5: uses context from template pixels to drive arithmetic decoder.
     *
     * @param data       the segment data array
     * @param dataOffset start of arithmetic-coded data
     * @param dataLength length of arithmetic-coded data
     * @param regionW    region width in pixels
     * @param regionH    region height in pixels
     * @param gbTemplate template number (0–3)
     * @param tpgdOn     whether typical prediction is enabled
     * @param gbatX      adaptive template pixel X offsets
     * @param gbatY      adaptive template pixel Y offsets
     * @return decoded bitmap as flat boolean array
     */
    static boolean[] decodeGenericRegionArith(byte[] data, int dataOffset, int dataLength,
            int regionW, int regionH, int gbTemplate, boolean tpgdOn,
            int[] gbatX, int[] gbatY) {

        // Number of context bits depends on template (§6.2.5.3, Figures 3–6)
        int numContextBits;
        switch (gbTemplate) {
            case 0:  numContextBits = 16; break;
            case 1:  numContextBits = 13; break;
            case 2:  numContextBits = 10; break;
            case 3:  numContextBits = 10; break;
            default: numContextBits = 16; break;
        }
        int numContexts = 1 << numContextBits;

        ArithmeticDecoder arith = new ArithmeticDecoder(data, dataOffset, numContexts);
        boolean[] bitmap = new boolean[regionW * regionH];
        boolean ltp = false; // line is typical prediction
        int[][] tplOffsets = templateOffsets(gbTemplate, gbatX, gbatY);

        for (int row = 0; row < regionH; row++) {
            // Typical prediction (§6.2.5.5)
            if (tpgdOn) {
                // TPGD context is a special context (index depends on template)
                int tpgdCx;
                switch (gbTemplate) {
                    case 0:  tpgdCx = 0x9B25; break;
                    case 1:  tpgdCx = 0x0795; break;
                    case 2:  tpgdCx = 0x00E5; break;
                    case 3:  tpgdCx = 0x0195; break;
                    default: tpgdCx = 0x9B25; break;
                }
                int tpgdVal = arith.decode(tpgdCx);
                ltp = ltp ^ (tpgdVal == 1);
                if (ltp) {
                    // Copy previous row
                    if (row > 0) {
                        System.arraycopy(bitmap, (row - 1) * regionW, bitmap, row * regionW, regionW);
                    }
                    continue;
                }
            }

            for (int col = 0; col < regionW; col++) {
                int context = contextAt(bitmap, regionW, regionH, row, col, tplOffsets);
                int bit = arith.decode(context);
                if (bit == 1) {
                    bitmap[row * regionW + col] = true;
                }
            }
        }
        return bitmap;
    }

    /**
     * Builds the sorted template-pixel offset list for a generic-region
     * template with its adaptive (AT) pixels (T.88 §6.2.5.3, Figures 4–7).
     * The context value is formed MSB-first over the pixels ordered by
     * (y, x) — the same ordering the TPGDON pseudo-pixel contexts assume.
     *
     * @return array of {dx, dy} offsets, context bit i = offset[bits-1-i]
     */
    private static int[][] templateOffsets(int template, int[] atX, int[] atY) {
        int[][] fixed;
        switch (template) {
            case 0:
                fixed = new int[][]{{-1,-2},{0,-2},{1,-2},{-2,-1},{-1,-1},{0,-1},{1,-1},{2,-1},{-4,0},{-3,0},{-2,0},{-1,0}};
                break;
            case 1:
                fixed = new int[][]{{-1,-2},{0,-2},{1,-2},{2,-2},{-2,-1},{-1,-1},{0,-1},{1,-1},{2,-1},{-3,0},{-2,0},{-1,0}};
                break;
            case 2:
                fixed = new int[][]{{-1,-2},{0,-2},{1,-2},{-2,-1},{-1,-1},{0,-1},{1,-1},{-2,0},{-1,0}};
                break;
            default:
                fixed = new int[][]{{-3,-1},{-2,-1},{-1,-1},{0,-1},{1,-1},{-4,0},{-3,0},{-2,0},{-1,0}};
                break;
        }
        int atCount = (template == 0) ? 4 : 1;
        int[][] all = new int[fixed.length + atCount][];
        System.arraycopy(fixed, 0, all, 0, fixed.length);
        // Nominal AT positions (used when the segment supplies none).
        int[][] nominalAt = (template == 0)
                ? new int[][]{{3,-1},{-3,-1},{2,-2},{-2,-2}}
                : new int[][]{{template == 1 ? 3 : 2, -1}};
        for (int i = 0; i < atCount; i++) {
            int ax = (atX != null && i < atX.length) ? atX[i] : nominalAt[i][0];
            int ay = (atY != null && i < atY.length) ? atY[i] : nominalAt[i][1];
            all[fixed.length + i] = new int[]{ax, ay};
        }
        java.util.Arrays.sort(all, (a, b) -> a[1] != b[1] ? a[1] - b[1] : a[0] - b[0]);
        return all;
    }

    /** Computes the arithmetic context for (row, col) over sorted offsets. */
    private static int contextAt(boolean[] bitmap, int w, int h, int row, int col,
                                 int[][] offsets) {
        int cx = 0;
        for (int[] o : offsets) {
            cx = (cx << 1) | px(bitmap, w, h, row + o[1], col + o[0]);
        }
        return cx;
    }

    /**
     * Convenience wrapper: builds the context for one pixel, resolving the
     * template each call. Hot loops should precompute {@link #templateOffsets}
     * and use {@link #contextAt} directly.
     */
    private static int buildContext(boolean[] bitmap, int w, int h,
                                     int row, int col, int template,
                                     int[] atX, int[] atY) {
        return contextAt(bitmap, w, h, row, col, templateOffsets(template, atX, atY));
    }

    /**
     * Gets pixel value at (row, col); returns 0 for out-of-bounds coordinates.
     */
    static int px(boolean[] bm, int w, int h, int row, int col) {
        if (row < 0 || row >= h || col < 0 || col >= w) return 0;
        return bm[row * w + col] ? 1 : 0;
    }

    /**
     * Parses a Generic Region segment header to extract region parameters.
     * Returns {regionW, regionH, xOffset, yOffset, combinationOp, mmrFlag, headerSize,
     *          template, typicalPred} or null on error.
     */
    private static int[] parseGenericRegionHeader(byte[] data, Segment seg) {
        int off = seg.dataOffset;
        if (seg.dataLength < 18) return null;
        int regionW = readU32(data, off);
        int regionH = readU32(data, off + 4);
        int xOff = readU32(data, off + 8);
        int yOff = readU32(data, off + 12);
        int flags = data[off + 16] & 0xFF;
        int combOp = flags & 0x07;
        int regFlags = data[off + 17] & 0xFF;
        int mmr = regFlags & 0x01;
        int template = (regFlags >> 1) & 0x03;
        int typicalPred = (regFlags >> 3) & 0x01;
        // Data starts after header (18 bytes) + optional AT pixels
        int headerSize = 18;
        if (mmr == 0) {
            // Arithmetic coding — template-dependent AT pixel offsets
            headerSize += (template == 0) ? 8 : 2; // 4 or 1 AT pairs × 2 bytes
        }
        return new int[]{regionW, regionH, xOff, yOff, combOp, mmr, headerSize, template, typicalPred};
    }

    // ═══════════════════════════════════════════════════════════════
    //  Symbol dictionary decoding (§6.5, §7.4.2)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Decodes a symbol dictionary segment.
     * ISO/IEC 11544 §6.5: each symbol is decoded using the generic region procedure
     * (or refinement), and stored for later reference by text regions.
     *
     * @param segData         the byte array containing segment data
     * @param seg             the segment header
     * @param segmentResults  map of already-decoded symbol dictionaries
     * @param allSegments     all segments for referred-to lookup
     * @return the decoded symbol dictionary, or null on error
     */
    private static SymbolDictionary decodeSymbolDictionary(byte[] segData, Segment seg,
            java.util.Map<Integer, SymbolDictionary> segmentResults,
            java.util.Map<Integer, Segment> allSegments) {

        int off = seg.dataOffset;
        if (seg.dataLength < 10) {
            LOG.warning("JBIG2: symbol dictionary segment too short");
            return null;
        }

        // §7.4.2.1.1: Symbol dictionary flags (2 bytes)
        int sdFlags = readU16(segData, off);
        boolean sdHuff = (sdFlags & 0x01) != 0;
        boolean sdRefAgg = (sdFlags & 0x02) != 0;
        // §7.4.3.1.1 (T.88): bits 2–9 are the Huffman table selectors;
        // SDTEMPLATE lives in bits 10–11 and SDRTEMPLATE in bit 12. Reading
        // them from bits 2–4 mis-sized the AT-pixel block for any dictionary
        // with a non-zero template and derailed the whole decode (33314).
        int sdTemplate = (sdFlags >> 10) & 0x03;
        int sdrTemplate = (sdFlags >> 12) & 0x01;
        off += 2;

        // §7.4.2.1.2: AT pixels (only if arithmetic coding)
        int[] sdatX = null, sdatY = null;
        if (!sdHuff) {
            if (sdTemplate == 0) {
                if (off + 7 >= seg.dataOffset + seg.dataLength || off + 7 >= segData.length) {
                    LOG.warning("JBIG2: symbol dictionary segment truncated in AT pixels");
                    return null;
                }
                sdatX = new int[]{(byte) segData[off], (byte) segData[off + 2],
                                  (byte) segData[off + 4], (byte) segData[off + 6]};
                sdatY = new int[]{(byte) segData[off + 1], (byte) segData[off + 3],
                                  (byte) segData[off + 5], (byte) segData[off + 7]};
                off += 8;
            } else {
                if (off + 1 >= seg.dataOffset + seg.dataLength || off + 1 >= segData.length) {
                    LOG.warning("JBIG2: symbol dictionary segment truncated in AT pixels");
                    return null;
                }
                sdatX = new int[]{(byte) segData[off]};
                sdatY = new int[]{(byte) segData[off + 1]};
                off += 2;
            }
        }

        // Refinement AT pixels (if SDREFAGG and not Huffman)
        if (sdRefAgg && !sdHuff) {
            // 2 AT pixels for refinement template
            off += (sdrTemplate == 0) ? 4 : 2;
        }

        // §7.4.2.1.4: SDNUMEXSYMS and SDNUMNEWSYMS (4 bytes each)
        int sdNumExSyms = readU32(segData, off); off += 4;
        int sdNumNewSyms = readU32(segData, off); off += 4;
        if (!isReasonableSymbolCount(sdNumExSyms) || !isReasonableSymbolCount(sdNumNewSyms)) {
            LOG.warning("JBIG2: unreasonable symbol dictionary counts in segment " + seg.number
                    + ": export=" + sdNumExSyms + ", new=" + sdNumNewSyms + "; skipping segment");
            return null;
        }

        // Collect input symbols from referred-to segments
        java.util.List<boolean[]> inputSymbols = new java.util.ArrayList<>();
        java.util.List<Integer> inputWidths = new java.util.ArrayList<>();
        java.util.List<Integer> inputHeights = new java.util.ArrayList<>();
        for (int refNum : seg.referredTo) {
            SymbolDictionary refDict = segmentResults.get(refNum);
            if (refDict != null) {
                for (int i = 0; i < refDict.numSymbols; i++) {
                    inputSymbols.add(refDict.bitmaps[i]);
                    inputWidths.add(refDict.widths[i]);
                    inputHeights.add(refDict.heights[i]);
                }
            }
        }
        int numInputSyms = inputSymbols.size();

        // Decode new symbols
        boolean[][] newBitmaps = new boolean[sdNumNewSyms][];
        int[] newWidths = new int[sdNumNewSyms];
        int[] newHeights = new int[sdNumNewSyms];
        boolean[] exportFlags = null;

        if (sdHuff) {
            // Huffman-coded symbol dictionaries are rare in PDF JBIG2.
            // For now, create empty placeholder symbols.
            LOG.warning("JBIG2: Huffman-coded symbol dictionary not fully supported (segment "
                    + seg.number + "), creating placeholder symbols");
            for (int i = 0; i < sdNumNewSyms; i++) {
                newBitmaps[i] = new boolean[0];
                newWidths[i] = 0;
                newHeights[i] = 0;
            }
        } else {
            // Arithmetic-coded symbol dictionary (§6.5.5)
            int dataOffset = off;
            int dataLen = seg.dataLength - (off - seg.dataOffset);

            // Context sizes for integer decoders
            // We need separate arithmetic contexts for each integer type
            int numContextBits;
            switch (sdTemplate) {
                case 0:  numContextBits = 16; break;
                case 1:  numContextBits = 13; break;
                case 2:  numContextBits = 10; break;
                case 3:  numContextBits = 10; break;
                default: numContextBits = 16; break;
            }

            // Total contexts needed:
            // - Generic region: 1 << numContextBits
            // - Integer decoders (IA): 512 each, we need IADH, IADW, IAEX, IAAI = 4 × 512
            // - IAID: 1 << SBSYMCODELEN
            int genContexts = 1 << numContextBits;
            int iaContexts = 512;
            int totalSymbols = safeNonNegativeSum(numInputSyms, sdNumNewSyms);
            int symCodeLen = Math.max(1, ceilLog2(totalSymbols));
            if (symCodeLen > 30) {
                LOG.warning("JBIG2: unreasonable symbol code length " + symCodeLen
                        + " in segment " + seg.number + "; skipping segment");
                return null;
            }
            // §A.3: the IAID context tree holds 2^(SBSYMCODELEN+1) nodes.
            int iaidContexts = 1 << (symCodeLen + 1);
            int totalContexts = safeContextCount(genContexts, iaContexts, iaidContexts);
            if (totalContexts <= 0) {
                LOG.warning("JBIG2: unreasonable arithmetic context count in segment "
                        + seg.number + "; skipping segment");
                return null;
            }

            ArithmeticDecoder arith = new ArithmeticDecoder(segData, dataOffset, totalContexts);
            int[][] sdTplOffsets = templateOffsets(sdTemplate, sdatX, sdatY);

            // Context base offsets
            int cxGB = 0;                          // generic bitmap contexts
            int cxIADH = genContexts;              // delta height
            int cxIADW = cxIADH + iaContexts;     // delta width
            int cxIAAI = cxIADW + iaContexts;      // aggregation instances
            int cxIAEX = cxIAAI + iaContexts;      // export flag

            int heightClassHeight = 0;
            int symbolIndex = 0;

            while (symbolIndex < sdNumNewSyms) {
                // Decode delta height (HCDH)
                int deltaHeight = arith.decodeInteger(cxIADH);
                if (deltaHeight == Integer.MIN_VALUE) break; // OOB
                long nextHeightClassHeight = (long) heightClassHeight + deltaHeight;
                if (nextHeightClassHeight <= 0 || nextHeightClassHeight > MAX_JBIG2_DIMENSION) {
                    LOG.warning("JBIG2: unreasonable symbol height class " + nextHeightClassHeight
                            + " in segment " + seg.number + "; stopping symbol decode");
                    break;
                }
                heightClassHeight = (int) nextHeightClassHeight;

                long totalWidth = 0;

                // Decode symbols in this height class
                while (symbolIndex < sdNumNewSyms) {
                    // Decode delta width
                    int deltaWidth = arith.decodeInteger(cxIADW);
                    if (deltaWidth == Integer.MIN_VALUE) break; // OOB = end of height class

                    totalWidth += deltaWidth;
                    if (totalWidth <= 0 || totalWidth > MAX_JBIG2_DIMENSION) {
                        LOG.warning("JBIG2: unreasonable symbol width " + totalWidth
                                + " in segment " + seg.number + "; ending current height class");
                        break;
                    }
                    int symW = (int) totalWidth;
                    int symH = heightClassHeight;

                    if (symW <= 0 || symH <= 0) {
                        newBitmaps[symbolIndex] = new boolean[0];
                        newWidths[symbolIndex] = Math.max(0, symW);
                        newHeights[symbolIndex] = Math.max(0, symH);
                        symbolIndex++;
                        continue;
                    }

                    if (!sdRefAgg) {
                        // Direct-coded: decode using generic region procedure
                        // We decode pixel-by-pixel using the arithmetic decoder
                        int symPixels = safePixelCount(symW, symH);
                        if (symPixels < 0) {
                            LOG.warning("JBIG2: unreasonable symbol bitmap size " + symW + "x" + symH
                                    + " in segment " + seg.number + "; using placeholder");
                            newBitmaps[symbolIndex] = new boolean[0];
                            newWidths[symbolIndex] = symW;
                            newHeights[symbolIndex] = symH;
                            symbolIndex++;
                            continue;
                        }
                        boolean[] symBitmap = new boolean[symPixels];
                        for (int row = 0; row < symH; row++) {
                            for (int col = 0; col < symW; col++) {
                                int cx = contextAt(symBitmap, symW, symH, row, col, sdTplOffsets);
                                int bit = arith.decode(cxGB + cx);
                                if (bit == 1) {
                                    symBitmap[row * symW + col] = true;
                                }
                            }
                        }
                        newBitmaps[symbolIndex] = symBitmap;
                    } else {
                        // Aggregation instance — decode number of instances
                        int numInstances = arith.decodeInteger(cxIAAI);
                        if (numInstances == Integer.MIN_VALUE) numInstances = 1;

                        if (numInstances == 1) {
                            // Single-instance refinement — simplified: just decode direct
                            int symPixels = safePixelCount(symW, symH);
                            if (symPixels < 0) {
                                LOG.warning("JBIG2: unreasonable refined symbol bitmap size " + symW + "x" + symH
                                        + " in segment " + seg.number + "; using placeholder");
                                newBitmaps[symbolIndex] = new boolean[0];
                                newWidths[symbolIndex] = symW;
                                newHeights[symbolIndex] = symH;
                                symbolIndex++;
                                continue;
                            }
                            boolean[] symBitmap = new boolean[symPixels];
                            for (int row = 0; row < symH; row++) {
                                for (int col = 0; col < symW; col++) {
                                    int cx = buildContext(symBitmap, symW, symH, row, col,
                                                           sdTemplate, sdatX, sdatY);
                                    int bit = arith.decode(cxGB + cx);
                                    if (bit == 1) {
                                        symBitmap[row * symW + col] = true;
                                    }
                                }
                            }
                            newBitmaps[symbolIndex] = symBitmap;
                        } else {
                            // Multi-instance: decode as blank for now
                            int symPixels = safePixelCount(symW, symH);
                            newBitmaps[symbolIndex] = symPixels >= 0 ? new boolean[symPixels] : new boolean[0];
                        }
                    }
                    newWidths[symbolIndex] = symW;
                    newHeights[symbolIndex] = symH;
                    symbolIndex++;
                }
            }

            // Fill any remaining symbols with empty bitmaps
            for (int i = symbolIndex; i < sdNumNewSyms; i++) {
                newBitmaps[i] = new boolean[0];
                newWidths[i] = 0;
                newHeights[i] = 0;
            }

            // §6.5.10: export flags are run-length coded with IAEX over the
            // concatenated input+new symbol list, alternating skip/export runs
            // starting with skip. A dictionary may export a SUBSET — indexing
            // by position without this shifts every symbol ID in text regions.
            int totalSyms = numInputSyms + sdNumNewSyms;
            exportFlags = new boolean[totalSyms];
            int exIdx = 0;
            boolean exCur = false;
            while (exIdx < totalSyms) {
                int runLen = arith.decodeInteger(cxIAEX);
                if (runLen == Integer.MIN_VALUE || runLen < 0) { exportFlags = null; break; }
                if (exCur) {
                    for (int i = 0; i < runLen && exIdx < totalSyms; i++) exportFlags[exIdx++] = true;
                } else {
                    exIdx += runLen;
                }
                exCur = !exCur;
            }
            // §6.5.10: the run-length export flags MUST select exactly
            // SDNUMEXSYMS symbols. When they don't (a truncated/short final run
            // in some encoders — corpus 59930 marked 2 of 5 as exported, so a
            // text region could not reference the 3 dropped glyphs and the tile
            // rendered blank), fall back to the positional export below, which
            // takes the first SDNUMEXSYMS symbols (= all new symbols when there
            // are no input symbols — the usual case for these per-glyph dicts).
            if (exportFlags != null) {
                int exCount = 0;
                for (boolean fl : exportFlags) {
                    if (fl) {
                        exCount++;
                    }
                }
                if (exCount != sdNumExSyms) {
                    exportFlags = null;
                }
            }
        }

        // Build the export symbol list (§6.5.10): the flagged symbols, or —
        // when flags were unavailable — the first SDNUMEXSYMS positionally.
        java.util.List<boolean[]> allBitmaps = new java.util.ArrayList<>(inputSymbols);
        java.util.List<Integer> allWidths = new java.util.ArrayList<>(inputWidths);
        java.util.List<Integer> allHeights = new java.util.ArrayList<>(inputHeights);
        for (int i = 0; i < sdNumNewSyms; i++) {
            allBitmaps.add(newBitmaps[i]);
            allWidths.add(newWidths[i]);
            allHeights.add(newHeights[i]);
        }
        java.util.List<Integer> exported = new java.util.ArrayList<>();
        if (exportFlags != null) {
            for (int i = 0; i < exportFlags.length; i++) {
                if (exportFlags[i]) exported.add(i);
            }
        }
        if (exported.isEmpty()) {
            int cap = Math.min(sdNumExSyms, allBitmaps.size());
            for (int i = 0; i < cap; i++) exported.add(i);
        }
        SymbolDictionary dict = new SymbolDictionary();
        int totalExported = exported.size();
        dict.numSymbols = totalExported;
        dict.bitmaps = new boolean[totalExported][];
        dict.widths = new int[totalExported];
        dict.heights = new int[totalExported];
        for (int i = 0; i < totalExported; i++) {
            int src = exported.get(i);
            dict.bitmaps[i] = allBitmaps.get(src);
            dict.widths[i] = allWidths.get(src);
            dict.heights[i] = allHeights.get(src);
        }

        LOG.fine(() -> "JBIG2: decoded symbol dictionary segment " + seg.number
                + " with " + totalExported + " symbols");
        return dict;
    }

    /**
     * Returns ceil(log2(n)), minimum 0.
     */
    private static int ceilLog2(int n) {
        if (n <= 1) return 0;
        return 32 - Integer.numberOfLeadingZeros(n - 1);
    }

    private static boolean isReasonableDimension(int value) {
        return value > 0 && value <= MAX_JBIG2_DIMENSION;
    }

    private static boolean isReasonableSymbolCount(int value) {
        return value >= 0 && value <= MAX_JBIG2_SYMBOLS;
    }

    private static int safeNonNegativeSum(int left, int right) {
        long sum = (long) Math.max(0, left) + Math.max(0, right);
        return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
    }

    private static int safeContextCount(int genContexts, int iaContexts, int iaidContexts) {
        long total = (long) genContexts + 4L * iaContexts + iaidContexts;
        return total > 0 && total <= MAX_JBIG2_CONTEXTS ? (int) total : -1;
    }

    private static int safePixelCount(int width, int height) {
        if (!isReasonableDimension(width) || !isReasonableDimension(height)) {
            return -1;
        }
        long pixels = (long) width * height;
        return pixels > 0 && pixels <= MAX_JBIG2_BITMAP_PIXELS ? (int) pixels : -1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Text region decoding (§6.4, §7.4.3)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Decodes a text region segment.
     * ISO/IEC 11544 §6.4: places symbol instances from dictionaries onto a bitmap.
     *
     * @param segData        the byte array containing segment data
     * @param seg            the segment header
     * @param segmentResults map of already-decoded symbol dictionaries
     * @param allSegments    all segments for referred-to lookup
     * @param pageBitmap     the page bitmap to compose onto
     * @param pageInfo       the page information
     */
    private static void decodeTextRegion(byte[] segData, Segment seg,
            java.util.Map<Integer, SymbolDictionary> segmentResults,
            java.util.Map<Integer, Segment> allSegments,
            boolean[] pageBitmap, PageInfo pageInfo) {

        int off = seg.dataOffset;
        if (seg.dataLength < 17) {
            LOG.warning("JBIG2: text region segment too short");
            return;
        }

        // Region segment info field (§7.4.1): 17 bytes
        int regionW = readU32(segData, off);
        int regionH = readU32(segData, off + 4);
        int xOff = readU32(segData, off + 8);
        int yOff = readU32(segData, off + 12);
        int combFlags = segData[off + 16] & 0xFF;
        int combOp = combFlags & 0x07;
        if (!isReasonableDimension(regionW) || !isReasonableDimension(regionH)) {
            LOG.warning("JBIG2: unreasonable text region size " + regionW + "x" + regionH
                    + " in segment " + seg.number + "; skipping region");
            return;
        }
        off += 17;

        // §7.4.3.1: Text region segment flags (2 bytes)
        if (seg.dataLength < 19) return;
        int trFlags = readU16(segData, off); off += 2;
        boolean sbHuff = (trFlags & 0x01) != 0;
        boolean sbRefine = (trFlags & 0x02) != 0;
        int logSBStrips = (trFlags >> 2) & 0x03;
        int sbStrips = 1 << logSBStrips;
        int refCorner = (trFlags >> 4) & 0x03;
        boolean transposed = ((trFlags >> 6) & 0x01) != 0;
        int sbCombOp = (trFlags >> 7) & 0x03;
        boolean sbDefPixel = ((trFlags >> 9) & 0x01) != 0;
        // bits 10-14: DS offset, Huffman table flags
        int sbdsOffset = (trFlags >> 10) & 0x1F;
        if (sbdsOffset > 15) sbdsOffset -= 32; // sign extend 5 bits
        int sbrTemplate = (trFlags >> 15) & 0x01;

        // §7.4.3.1.2: Huffman flags (if Huffman coded) — skip
        if (sbHuff) {
            off += 2; // Huffman flags
            // May have additional table selection bytes
        }

        // §7.4.3.1.3: Refinement AT pixels (if SBREFINE and arithmetic)
        if (sbRefine && !sbHuff) {
            off += (sbrTemplate == 0) ? 4 : 2;
        }

        // §7.4.3.1.4: Number of symbol instances
        if (off + 4 > seg.dataOffset + seg.dataLength) return;
        int sbNumInstances = readU32(segData, off); off += 4;
        if (!isReasonableSymbolCount(sbNumInstances)) {
            LOG.warning("JBIG2: unreasonable symbol instance count " + sbNumInstances
                    + " in segment " + seg.number + "; skipping region");
            return;
        }

        // Collect all referred-to symbols
        java.util.List<boolean[]> symbols = new java.util.ArrayList<>();
        java.util.List<Integer> symWidths = new java.util.ArrayList<>();
        java.util.List<Integer> symHeights = new java.util.ArrayList<>();
        for (int refNum : seg.referredTo) {
            SymbolDictionary refDict = segmentResults.get(refNum);
            if (refDict != null) {
                for (int i = 0; i < refDict.numSymbols; i++) {
                    symbols.add(refDict.bitmaps[i]);
                    symWidths.add(refDict.widths[i]);
                    symHeights.add(refDict.heights[i]);
                }
            }
        }
        int numSyms = symbols.size();
        if (numSyms == 0) {
            LOG.warning("JBIG2: text region has no symbols to reference");
            return;
        }

        int symCodeLen = Math.max(1, ceilLog2(numSyms));

        // Create region bitmap
        int regionPixels = safePixelCount(regionW, regionH);
        if (regionPixels < 0) {
            LOG.warning("JBIG2: unreasonable text region bitmap size " + regionW + "x" + regionH
                    + " in segment " + seg.number + "; skipping region");
            return;
        }
        boolean[] regionBitmap = new boolean[regionPixels];
        if (sbDefPixel) {
            java.util.Arrays.fill(regionBitmap, true);
        }

        if (sbHuff) {
            // Huffman-coded text regions — not fully supported
            LOG.warning("JBIG2: Huffman-coded text region not fully supported (segment "
                    + seg.number + ")");
            // Compose empty region
            if (pageBitmap != null && pageInfo != null) {
                composeBitmap(pageBitmap, pageInfo.width, pageInfo.height,
                        regionBitmap, regionW, regionH, xOff, yOff, combOp);
            }
            return;
        }

        // Arithmetic-coded text region (§6.4.5)
        int dataOffset = off;
        int dataLen = seg.dataLength - (off - seg.dataOffset);

        // Context allocation:
        // IADT, IAFS, IADS, IAIT: 512 each (strip/first-S/delta-S/instance-T)
        // IAID: 1 << symCodeLen
        // IARI (refinement I): 512
        // IARDW, IARDH, IARDX, IARDY: 512 each (refinement dims)
        int iaContexts = 512;
        // §A.3: the IAID context tree holds 2^(SBSYMCODELEN+1) nodes.
        int iaidContexts = 1 << (symCodeLen + 1);
        int totalContexts = safeContextCount(5 * iaContexts, iaContexts, iaidContexts);
        if (totalContexts <= 0) {
            LOG.warning("JBIG2: unreasonable text-region context count in segment "
                    + seg.number + "; skipping region");
            return;
        }

        ArithmeticDecoder arith = new ArithmeticDecoder(segData, dataOffset, totalContexts);

        int cxIADT = 0;
        int cxIAFS = iaContexts;
        int cxIADS = 2 * iaContexts;
        int cxIAIT = 3 * iaContexts;
        int cxIAID = 4 * iaContexts;
        int cxIARI = 4 * iaContexts + iaidContexts;
        int cxIARDW = cxIARI + iaContexts;
        int cxIARDH = cxIARDW + iaContexts;
        int cxIARDX = cxIARDH + iaContexts;
        int cxIARDY = cxIARDX + iaContexts;

        // Decode symbol instances (§6.4.5)
        int instancesDecoded = 0;
        int firstS = 0;

        // §6.4.5 step 1: STRIPT = −(IADT decode) × SBSTRIPS.
        int stripT = 0;
        int initDT = arith.decodeInteger(cxIADT);
        if (initDT != Integer.MIN_VALUE) {
            stripT = -initDT * sbStrips;
        }

        while (instancesDecoded < sbNumInstances) {
            // §6.4.5 step 3b: every strip (including the first) begins with a
            // delta-T decode: STRIPT += IADT × SBSTRIPS.
            int strDT = arith.decodeInteger(cxIADT);
            if (strDT == Integer.MIN_VALUE) break;
            stripT += strDT * sbStrips;

            // Decode first S in strip (FIRSTS)
            int deltaFS = arith.decodeInteger(cxIAFS);
            if (deltaFS == Integer.MIN_VALUE) break; // OOB
            firstS += deltaFS;
            int curS = firstS;

            // Decode symbol instances in this strip
            while (true) {
                // Decode current T offset within strip
                int currentT = stripT;
                if (sbStrips > 1) {
                    int instanceDT = arith.decodeInteger(cxIAIT);
                    if (instanceDT != Integer.MIN_VALUE) {
                        currentT += instanceDT;
                    }
                }

                // Decode symbol ID
                int symbolID = arith.decodeIAID(cxIAID, symCodeLen);
                if (symbolID < 0 || symbolID >= numSyms) {
                    final int badId = symbolID;
                    LOG.fine(() -> "JBIG2: invalid symbol ID " + badId + " (of " + numSyms
                            + ", codeLen " + symCodeLen + ") in text region");
                    break;
                }

                // Get the symbol bitmap
                boolean[] symBitmap = symbols.get(symbolID);
                int symW = symWidths.get(symbolID);
                int symH = symHeights.get(symbolID);

                // Handle refinement if applicable
                if (sbRefine) {
                    int ri = arith.decodeInteger(cxIARI);
                    if (ri != Integer.MIN_VALUE && ri != 0) {
                        // Refinement: skip for now (use base symbol as-is)
                        // Would decode RDWI, RDHI, RDXI, RDYI and refine the symbol
                    }
                }

                // §6.4.5 steps 3c-vi..xi. T.88 Table 34: REFCORNER 0=BOTTOMLEFT,
                // 1=TOPLEFT, 2=BOTTOMRIGHT, 3=TOPRIGHT. Whichever corner is
                // referenced, the glyph occupies [CURS, CURS+len−1] along S —
                // only the T coordinate shifts for bottom/right corners.
                int placeX;
                int placeY;
                if (!transposed) {
                    placeX = curS;
                    placeY = (refCorner == 0 || refCorner == 2)
                            ? currentT - symH + 1 : currentT;   // bottom corners
                } else {
                    placeY = curS;
                    placeX = (refCorner == 2 || refCorner == 3)
                            ? currentT - symW + 1 : currentT;   // right corners
                }

                // Compose symbol onto region
                if (symBitmap != null && symBitmap.length > 0 && symW > 0 && symH > 0) {
                    composeBitmap(regionBitmap, regionW, regionH,
                            symBitmap, symW, symH, placeX, placeY, sbCombOp);
                }
                curS += transposed ? symH - 1 : symW - 1;

                instancesDecoded++;
                if (instancesDecoded >= sbNumInstances) break;

                // Decode delta S for next instance (IDS)
                int deltaS = arith.decodeInteger(cxIADS);
                if (deltaS == Integer.MIN_VALUE) break; // OOB = end of strip
                curS += deltaS + sbdsOffset;
            }
        }

        // Compose region onto page
        if (pageBitmap != null && pageInfo != null) {
            composeBitmap(pageBitmap, pageInfo.width, pageInfo.height,
                    regionBitmap, regionW, regionH, xOff, yOff, combOp);
        }

        final int decodedCount = instancesDecoded;
        LOG.fine(() -> "JBIG2: decoded text region segment " + seg.number
                + " (" + decodedCount + " instances)");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main decode
    // ═══════════════════════════════════════════════════════════════

    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (encoded == null || encoded.length == 0) return new byte[0];

        try {
            return decodeJBIG2(encoded, params);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JBIG2Decode failed: " + e.getMessage() + ", returning raw data", e);
            return encoded;
        }
    }

    private byte[] decodeJBIG2(byte[] encoded, PdfDictionary params) throws IOException {
        // Collect all segments: globals first, then page data
        List<Segment> allSegments = new ArrayList<>();

        // Parse global segments from /JBIG2Globals if present
        byte[] globalsData = null;
        if (params != null) {
            PdfBase globalsObj = params.get("JBIG2Globals");
            // /JBIG2Globals is routinely an indirect reference — dereference it,
            // otherwise the symbol dictionaries it carries are silently dropped
            // and text regions decode to a blank page (jbig2-1).
            if (globalsObj instanceof PdfObjectReference) {
                try {
                    globalsObj = ((PdfObjectReference) globalsObj).dereference();
                } catch (Exception e) {
                    LOG.fine(() -> "JBIG2: failed to dereference /JBIG2Globals: " + e.getMessage());
                }
            }
            if (globalsObj instanceof PdfStream) {
                globalsData = ((PdfStream) globalsObj).getDecodedData();
                if (globalsData != null && globalsData.length > 0) {
                    List<Segment> globalSegs = parseSegments(globalsData, 0, globalsData.length);
                    for (Segment gs : globalSegs) {
                        gs.sourceData = globalsData;
                    }
                    allSegments.addAll(globalSegs);
                }
            }
        }

        // Check for file header (0x97 0x4A 0x42 0x32 0x0D 0x0A 0x1A 0x0A)
        int dataStart = 0;
        if (encoded.length >= 8
                && (encoded[0] & 0xFF) == 0x97
                && (encoded[1] & 0xFF) == 0x4A
                && (encoded[2] & 0xFF) == 0x42
                && (encoded[3] & 0xFF) == 0x32) {
            // Skip file header: 8 bytes magic + 1 byte flags
            int headerFlags = encoded[8] & 0xFF;
            dataStart = 9;
            if ((headerFlags & 0x02) == 0) {
                dataStart += 4; // number of pages field present
            }
        }

        // Parse page segments
        List<Segment> pageSegments = parseSegments(encoded, dataStart, encoded.length);
        for (Segment ps : pageSegments) {
            ps.sourceData = encoded;
        }
        allSegments.addAll(pageSegments);

        // Process segments
        PageInfo pageInfo = null;
        boolean[] pageBitmap = null;
        java.util.Map<Integer, SymbolDictionary> segmentResults = new java.util.HashMap<>();
        // Build segment lookup by number for referred-to resolution
        java.util.Map<Integer, Segment> segmentByNumber = new java.util.HashMap<>();
        for (Segment s : allSegments) {
            segmentByNumber.put(s.number, s);
        }

        for (Segment seg : allSegments) {
            byte[] segData = seg.sourceData != null ? seg.sourceData : encoded;

            switch (seg.type) {
                case SEG_PAGE_INFO:
                    pageInfo = processPageInfo(segData, seg);
                    if (pageInfo.width > 0 && pageInfo.height > 0) {
                        pageBitmap = new boolean[pageInfo.width * pageInfo.height];
                        if (pageInfo.defaultPixel) {
                            Arrays.fill(pageBitmap, true);
                        }
                    }
                    final PageInfo pi2 = pageInfo;
                    LOG.fine(() -> "JBIG2 page: " + pi2.width + "x" + pi2.height);
                    break;

                case SEG_GENERIC_REGION:
                case SEG_GENERIC_REGION_IMM:
                case SEG_GENERIC_REGION_IMM_L: {
                    int[] hdr = parseGenericRegionHeader(segData, seg);
                    if (hdr == null || pageInfo == null || pageBitmap == null) {
                        LOG.warning("JBIG2: generic region without page info, skipping");
                        break;
                    }
                    int regionW = hdr[0], regionH = hdr[1];
                    int xOff = hdr[2], yOff = hdr[3];
                    int combOp = hdr[4], mmr = hdr[5], headerSize = hdr[6];

                    if (mmr == 1) {
                        // MMR coding — decode using Group 4
                        int regionDataOff = seg.dataOffset + headerSize;
                        int regionDataLen = seg.dataLength - headerSize;
                        if (regionDataLen > 0) {
                            boolean[] regionBitmap = decodeGenericRegionMMR(
                                    segData, regionDataOff, regionDataLen, regionW, regionH);
                            composeBitmap(pageBitmap, pageInfo.width, pageInfo.height,
                                    regionBitmap, regionW, regionH, xOff, yOff, combOp);
                        }
                    } else {
                        // Arithmetic coding — parse AT pixels from header
                        int template = hdr[7];
                        int typicalPred = hdr[8];
                        int atOff = seg.dataOffset + 18; // AT pixels start after 18-byte header
                        int[] gbatX, gbatY;
                        if (template == 0) {
                            gbatX = new int[]{
                                (byte) segData[atOff], (byte) segData[atOff + 2],
                                (byte) segData[atOff + 4], (byte) segData[atOff + 6]};
                            gbatY = new int[]{
                                (byte) segData[atOff + 1], (byte) segData[atOff + 3],
                                (byte) segData[atOff + 5], (byte) segData[atOff + 7]};
                        } else {
                            gbatX = new int[]{(byte) segData[atOff]};
                            gbatY = new int[]{(byte) segData[atOff + 1]};
                        }
                        boolean tpgdOn = typicalPred == 1;
                        int regionDataOff2 = seg.dataOffset + headerSize;
                        int regionDataLen2 = seg.dataLength - headerSize;
                        if (regionDataLen2 > 0) {
                            boolean[] regionBitmap = decodeGenericRegionArith(
                                    segData, regionDataOff2, regionDataLen2,
                                    regionW, regionH, template, tpgdOn, gbatX, gbatY);
                            composeBitmap(pageBitmap, pageInfo.width, pageInfo.height,
                                    regionBitmap, regionW, regionH, xOff, yOff, combOp);
                        }
                    }
                    break;
                }

                case SEG_SYMBOL_DICT: {
                    SymbolDictionary dict = decodeSymbolDictionary(
                            segData, seg, segmentResults, segmentByNumber);
                    if (dict != null) {
                        segmentResults.put(seg.number, dict);
                    }
                    break;
                }
                case SEG_TEXT_REGION_INT:
                case SEG_TEXT_REGION:
                case SEG_TEXT_REGION_IMM: {
                    decodeTextRegion(segData, seg, segmentResults, segmentByNumber,
                            pageBitmap, pageInfo);
                    break;
                }
                case SEG_HALFTONE_REGION_INT:
                case SEG_HALFTONE_REGION:
                case SEG_HALFTONE_REGION_IMM:
                    LOG.fine(() -> "JBIG2: halftone region segment " + seg.number + " (not decoded)");
                    break;
                case SEG_PATTERN_DICT:
                    LOG.fine(() -> "JBIG2: pattern dictionary segment " + seg.number + " (not decoded)");
                    break;
                case SEG_GENERIC_REFINE:
                case SEG_GENERIC_REFINE_IMM:
                case SEG_GENERIC_REFINE_IMM_L:
                    LOG.fine(() -> "JBIG2: generic refinement segment " + seg.number + " (not decoded)");
                    break;
                case SEG_END_OF_PAGE:
                    LOG.fine("JBIG2: end of page");
                    break;
                case SEG_END_OF_FILE:
                    LOG.fine("JBIG2: end of file");
                    break;
                case SEG_END_OF_STRIPE:
                case SEG_PROFILES:
                case SEG_TABLES:
                case SEG_EXTENSION:
                    break; // silently skip
                default:
                    LOG.fine(() -> "JBIG2: unknown segment type " + seg.type + " (segment " + seg.number + ")");
                    break;
            }
        }

        // Pack page bitmap into bytes
        if (pageBitmap != null && pageInfo != null) {
            return packBitmap(pageBitmap, pageInfo.width, pageInfo.height);
        }

        LOG.warning("JBIG2: no page produced, returning raw data");
        return encoded;
    }

    // ─── Bitmap composition ──────────────────────────────────────

    /**
     * Composes a region bitmap onto the page bitmap using the specified combination operator.
     */
    private static void composeBitmap(boolean[] page, int pageW, int pageH,
                                      boolean[] region, int regionW, int regionH,
                                      int xOff, int yOff, int combOp) {
        for (int ry = 0; ry < regionH; ry++) {
            int py = yOff + ry;
            if (py < 0 || py >= pageH) continue;
            for (int rx = 0; rx < regionW; rx++) {
                int px = xOff + rx;
                if (px < 0 || px >= pageW) continue;
                int pi = py * pageW + px;
                int ri = ry * regionW + rx;
                boolean rBit = region[ri];
                switch (combOp) {
                    case 0: page[pi] |= rBit; break;             // OR
                    case 1: page[pi] &= rBit; break;             // AND
                    case 2: page[pi] ^= rBit; break;             // XOR
                    case 3: page[pi] = !(page[pi] ^ rBit); break; // XNOR
                    case 4: page[pi] = rBit; break;               // REPLACE
                    default: page[pi] = rBit; break;
                }
            }
        }
    }

    /**
     * Packs a boolean bitmap into bytes (true=1=black, MSB first, row-aligned).
     */
    private static byte[] packBitmap(boolean[] bitmap, int width, int height) {
        int rowBytes = (width + 7) / 8;
        byte[] result = new byte[rowBytes * height];
        // JBIG2 uses 1 = black, but a 1-bpc DeviceGray PDF image sample of 0
        // is black — the filter must deliver INVERTED bits (white = 1), or
        // every decoded page comes out negative (blank scans rendered as
        // solid black, corpus 33314).
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (!bitmap[y * width + x]) {
                    result[y * rowBytes + (x >> 3)] |= (byte) (0x80 >> (x & 7));
                }
            }
        }
        return result;
    }

    // ─── Byte reading helpers ────────────────────────────────────

    private static int readU16(byte[] data, int off) {
        return ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
    }

    private static int readU32(byte[] data, int off) {
        return ((data[off] & 0xFF) << 24) | ((data[off + 1] & 0xFF) << 16)
             | ((data[off + 2] & 0xFF) << 8) | (data[off + 3] & 0xFF);
    }

    // ─── PdfFilter interface ─────────────────────────────────────

    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        throw new IOException("JBIG2Decode encoding not implemented");
    }

    @Override
    public PdfName getName() {
        return PdfName.of("JBIG2Decode");
    }
}
