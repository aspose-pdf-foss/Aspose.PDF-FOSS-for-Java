package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JPXDecode filter — JPEG 2000 decompression (§7.4.9, ISO 32000-1:2008).
 *
 * <p>From-scratch baseline JPEG 2000 Part-1 (ISO/IEC 15444-1) decoder. Targets the
 * common photographic / scanned-page profile used inside PDF: a single tile,
 * 1–4 components 8-bit unsigned, LRCP progression with one quality layer,
 * default precincts (precinct = whole sub-band), MQ-coded code-blocks (no
 * selective bypass / termination / vertical-causal / segmentation modes), and
 * either 5/3 reversible or 9/7 irreversible wavelet with optional MCT.</p>
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Optional JP2 file-format unwrapping ({@link #findCodestream}) to locate
 *       the {@code jp2c} box.</li>
 *   <li>Codestream main-header parse: SIZ / COD / QCD (others skipped).</li>
 *   <li>Per-component sub-band &amp; code-block grid construction
 *       ({@link #buildPerCompBands}).</li>
 *   <li>Tier-2: {@link BitReader}-driven LRCP packet walker
 *       ({@link #runLRCPPackets}). Each packet header is decoded with
 *       bit-stuffed reads (§B.10.1), inclusion / zero-bit-plane via tag-tree
 *       (§B.10.2), per-codeblock new-pass-count VLI (§B.10.7) and Lblock-based
 *       segment length. The packet body bytes that follow are split among the
 *       included code-blocks per the lengths announced in the header.</li>
 *   <li>Tier-1: {@link #decodeTier1} runs the MQ arithmetic decoder over the
 *       per-codeblock bytes, alternating Cleanup / SigProp / MagRef passes
 *       (Annex D).</li>
 *   <li>Inverse DWT (5/3 or 9/7) per component, applied in resolution order.</li>
 *   <li>Inverse multiple-component transform (RCT for 5/3 / ICT for 9/7) when
 *       MCT is enabled and ≥3 components are present.</li>
 *   <li>Component interleave to packed-pixel byte output.</li>
 * </ol>
 *
 * <h2>What this decoder does NOT do</h2>
 * <ul>
 *   <li>Multi-tile codestreams (logs a warning, falls back to first tile).</li>
 *   <li>Selective arithmetic coding bypass (cbStyle bit 0).</li>
 *   <li>Per-pass MQ reset (cbStyle bit 1).</li>
 *   <li>Termination on each pass (cbStyle bit 2).</li>
 *   <li>Vertical-causal context (cbStyle bit 3).</li>
 *   <li>Predictable termination / segmentation symbols (cbStyle bits 4–5).</li>
 *   <li>Explicit precinct subdivision smaller than the sub-band.</li>
 *   <li>SOP / EPH packet-boundary markers (treated as missing).</li>
 *   <li>Multi-layer rate-distortion progressions are read but layer 0 results
 *       are essentially equivalent to single-layer for the supported profile.</li>
 * </ul>
 */
public final class JPXDecodeFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(JPXDecodeFilter.class.getName());

    // ═══════════════════════════════════════════════════════════════
    //  Bit reader for packet headers (Annex B.10.1 — bit-stuffing)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Reads bits MSB-first from a byte array, applying the JPEG 2000 packet
     * header bit-stuffing rule: whenever a byte equal to {@code 0xFF} has been
     * fully consumed, the next byte's most-significant bit is a stuff bit and
     * is discarded (only its remaining 7 bits carry payload).
     *
     * <p>Public mutable {@code pos} / {@code bitsLeft} / {@code lastWasFF} let
     * callers reset state when crossing the bit/byte-aligned boundary between
     * a packet header and the raw packet body.</p>
     */
    static final class BitReader {
        final byte[] data;
        final int endPos;
        int pos;
        int curByte;
        int bitsLeft;
        boolean lastWasFF;

        BitReader(byte[] data, int pos, int endPos) {
            this.data = data;
            this.pos = pos;
            this.endPos = endPos;
            this.bitsLeft = 0;
            this.lastWasFF = false;
        }

        int readBit() {
            if (bitsLeft == 0) {
                if (pos >= endPos) {
                    // Pad with zeros at end-of-stream.
                    return 0;
                }
                curByte = data[pos++] & 0xFF;
                bitsLeft = lastWasFF ? 7 : 8;
                lastWasFF = (curByte == 0xFF);
            }
            int bit = (curByte >> (bitsLeft - 1)) & 1;
            bitsLeft--;
            return bit;
        }

        int readBits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | readBit();
            return v;
        }

        /**
         * Aligns the reader to the next byte boundary (called between a packet
         * header and its body). If the most recently loaded source byte was
         * {@code 0xFF}, the spec-mandated "extra" stuff byte that follows is
         * also consumed.
         *
         * @return the resulting byte position
         */
        int alignToByte() {
            boolean shouldSkipStuff = lastWasFF;
            bitsLeft = 0;
            if (shouldSkipStuff && pos < endPos) {
                pos++;
            }
            lastWasFF = false;
            return pos;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tag tree (Annex B.10.2) — bit-coded
    // ═══════════════════════════════════════════════════════════════

    /**
     * Tag-tree as used inside packet headers for code-block inclusion and
     * zero-bit-plane signalling. Each leaf carries a non-negative integer; the
     * decoder asks "is the value at leaf (x, y) below threshold T?" and reads
     * just enough bits from the underlying {@link BitReader} to answer.
     *
     * <p>Persistent state — the per-node ({@code currentValue}, {@code final})
     * pair — is retained across calls so that subsequent layers can refine the
     * answer rather than re-decode from scratch.</p>
     */
    static final class TagTree {
        private final int[][] vals;
        private final boolean[][] finals;
        private final int levels;
        private final int[] widths, heights;

        TagTree(int w, int h) {
            if (w < 1) w = 1;
            if (h < 1) h = 1;
            int nlev = 1;
            int tw = w, th = h;
            while (tw > 1 || th > 1) {
                tw = (tw + 1) >> 1;
                th = (th + 1) >> 1;
                nlev++;
            }
            this.levels = nlev;
            this.widths = new int[levels];
            this.heights = new int[levels];
            this.vals = new int[levels][];
            this.finals = new boolean[levels][];
            tw = w; th = h;
            for (int l = 0; l < levels; l++) {
                widths[l] = tw;
                heights[l] = th;
                vals[l] = new int[tw * th];
                finals[l] = new boolean[tw * th];
                tw = (tw + 1) >> 1;
                th = (th + 1) >> 1;
            }
        }

        /**
         * Returns the leaf's value if it is now known to be {@code < threshold};
         * returns {@code -1} otherwise (meaning "value is at least threshold").
         */
        int decode(BitReader br, int x, int y, int threshold) {
            int[] xs = new int[levels];
            int[] ys = new int[levels];
            xs[0] = x; ys[0] = y;
            for (int l = 1; l < levels; l++) {
                xs[l] = xs[l - 1] >> 1;
                ys[l] = ys[l - 1] >> 1;
            }
            int parentVal = 0;
            for (int l = levels - 1; l >= 0; l--) {
                int idx = ys[l] * widths[l] + xs[l];
                if (vals[l][idx] < parentVal) vals[l][idx] = parentVal;
                while (vals[l][idx] < threshold && !finals[l][idx]) {
                    int bit = br.readBit();
                    if (bit == 1) {
                        finals[l][idx] = true;
                        break;
                    } else {
                        vals[l][idx]++;
                    }
                }
                parentVal = vals[l][idx];
            }
            int leafIdx = y * widths[0] + x;
            return finals[0][leafIdx] ? vals[0][leafIdx] : -1;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Codestream Markers
    // ═══════════════════════════════════════════════════════════════

    private static final int SOC = 0xFF4F;
    private static final int SIZ = 0xFF51;
    private static final int COD = 0xFF52;
    private static final int QCD = 0xFF5C;
    private static final int SOT = 0xFF90;
    private static final int SOD = 0xFF93;
    private static final int EOC = 0xFFD9;

    // Sub-band types for context model
    private static final int BAND_LL = 0;
    private static final int BAND_HL = 1;
    private static final int BAND_LH = 2;
    private static final int BAND_HH = 3;

    // ═══════════════════════════════════════════════════════════════
    //  Codestream reader
    // ═══════════════════════════════════════════════════════════════

    private static final class CSReader {
        final byte[] data;
        int pos;

        CSReader(byte[] data, int pos) {
            this.data = data;
            this.pos = pos;
        }

        int u8() { return data[pos++] & 0xFF; }
        int u16() { return (u8() << 8) | u8(); }
        int u32() { return (u16() << 16) | u16(); }
        int marker() { return u16(); }
        void skip(int n) { pos += n; }
    }

    // ─── Parsed marker data ──────────────────────────────────────

    private static final class SIZData {
        int width, height;       // image size
        int tileW, tileH;       // tile size
        int xOff, yOff;         // image offset
        int txOff, tyOff;       // tile offset
        int numComp;
        int[] bitDepths;        // per component (sign in bit 7)
        int[] xSub, ySub;       // sub-sampling
    }

    private static final class CODData {
        int progressionOrder;   // 0=LRCP,1=RLCP,2=RPCL,3=PCRL,4=CPRL
        int numLayers;
        int mct;                // multiple component transform
        int numDecomp;          // decomposition levels
        int cbW, cbH;           // code-block size
        int cbStyle;
        int wavelet;            // 0=9/7 irreversible, 1=5/3 reversible
        boolean useSop;         // Scod bit 1: SOP marker before each packet
        boolean useEph;         // Scod bit 2: EPH marker after each packet header
    }

    private static final class QCDData {
        int style;              // 0=no quant, 1=derived, 2=expounded
        int guardBits;
        int[] exponents;        // per sub-band
        int[] mantissas;        // per sub-band (for style 1,2)
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tier-2 working state — sub-band & code-block grids
    // ═══════════════════════════════════════════════════════════════

    /**
     * Per code-block accumulator carried across packets. For multi-layer
     * streams each new packet appends bytes to {@code data} and adds passes
     * to {@code totalPasses}; Tier-1 is run once at the end on the totals.
     */
    static final class TileCodeBlock {
        int x, y;                 // grid position within the sub-band
        int width, height;        // pixel dimensions (≤ cbW × cbH)
        boolean included;         // included in any packet so far?
        boolean zbpDecoded;       // zero-bit-plane known?
        int zeroBitplanes;        // K (Annex B.10.5)
        int Lblock = 3;           // current Lblock value (Annex B.10.7)
        int totalPasses;          // accumulated coding passes across layers
        // Per-packet scratch — reset for every packet header decode
        int pendingBytes;
        int pendingPasses;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
    }

    /**
     * One sub-band of one component, with its code-block grid and the two
     * persistent tag trees that drive packet inclusion / zero-bit-plane
     * signalling.
     */
    static final class SubBandInfo {
        int width, height;       // pixel size
        int bandType;            // BAND_LL / BAND_HL / BAND_LH / BAND_HH
        int decompLevel;         // 1..numDecomp (LL is at numDecomp)
        int globalIdx;           // index into per-component sub-band array
        int cbW, cbH;            // nominal code-block size from COD
        int cbCols, cbRows;
        TileCodeBlock[][] cbs;
        TagTree inclTree;
        TagTree zbpTree;

        SubBandInfo(int w, int h, int type, int level, int gIdx, int cbW, int cbH) {
            this.width = w; this.height = h;
            this.bandType = type;
            this.decompLevel = level;
            this.globalIdx = gIdx;
            this.cbW = cbW; this.cbH = cbH;
            if (w == 0 || h == 0) {
                this.cbCols = 0; this.cbRows = 0;
                this.cbs = new TileCodeBlock[0][0];
                return;
            }
            this.cbCols = (w + cbW - 1) / cbW;
            this.cbRows = (h + cbH - 1) / cbH;
            this.cbs = new TileCodeBlock[cbRows][cbCols];
            for (int r = 0; r < cbRows; r++) {
                for (int c = 0; c < cbCols; c++) {
                    TileCodeBlock cb = new TileCodeBlock();
                    cb.x = c; cb.y = r;
                    cb.width = Math.min(cbW, w - c * cbW);
                    cb.height = Math.min(cbH, h - r * cbH);
                    cbs[r][c] = cb;
                }
            }
            this.inclTree = new TagTree(cbCols, cbRows);
            this.zbpTree = new TagTree(cbCols, cbRows);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  EBCOT Tier-1 Decoder (Annex D)
    // ═══════════════════════════════════════════════════════════════

    // Context labels
    private static final int CX_UNI = 17;  // uniform
    private static final int CX_RL  = 18;  // run-length
    private static final int NUM_CX = 19;

    /**
     * Decodes a single code-block from its concatenated coding-pass bytes.
     *
     * @param cbData    concatenated coding-pass data for this block
     * @param cbW       code-block width
     * @param cbH       code-block height
     * @param numPasses total number of coding passes accumulated for this block
     * @param Mb        the sub-band's nominal number of magnitude bit-planes
     *                  (G + ε_b − 1 per ISO 15444-1 Annex E.1.1)
     * @param zeroBP    number of leading insignificant bit-planes (K), read
     *                  from the packet header tag-tree
     * @param bandType  BAND_LL / BAND_HL / BAND_LH / BAND_HH
     * @return coefficient magnitudes with sign in MSB (bit 31 set ⇒ negative)
     */
    static int[] decodeTier1(byte[] cbData, int cbW, int cbH,
                             int numPasses, int Mb, int zeroBP, int bandType) {
        return decodeTier1(cbData, cbW, cbH, numPasses, Mb, zeroBP, bandType, false);
    }

    /**
     * @param segSym cbStyle bit 5 — the encoder appended a four-symbol
     *               segmentation marker (1010, UNIFORM context) after every
     *               Cleanup pass; it must be consumed or the MQ stream desyncs
     *               on the next pass (ISO 15444-1 §D.5).
     */
    static int[] decodeTier1(byte[] cbData, int cbW, int cbH,
                             int numPasses, int Mb, int zeroBP, int bandType,
                             boolean segSym) {
        int[] coeffs = new int[cbW * cbH];
        int[] sigma = new int[cbW * cbH];
        int[] eta = new int[cbW * cbH];

        if (numPasses == 0 || cbData.length == 0) return coeffs;

        // The MQ coder of ISO 15444-1 Annex C is the identical algorithm to
        // ITU-T T.88 Annex E — reuse the corpus-proven JBIG2 decoder rather
        // than maintaining a second transcription of the same state machine.
        ArithmeticDecoder mq = new ArithmeticDecoder(cbData, 0, NUM_CX);
        // Initial context states per ISO 15444-1 Table D.7: all contexts start
        // at (state 0, MPS 0) EXCEPT the all-zero-neighbourhood ZC context 0
        // (state 4), the run-length context (state 3) and the uniform context
        // (state 46). Missing the ZC-0 preset desynchronised every code-block
        // a few decisions in (first wrong bit as early as the second stripe
        // column) — the root cause of the long-standing "JPX decodes to
        // noise" family.
        mq.setContext(0, 4, 0);
        mq.setContext(CX_UNI, 46, 0);
        mq.setContext(CX_RL, 3, 0);

        int passIdx = 0;
        // First coded bit-plane = M_b − 1 − K  (Annex D.1)
        int bitPlane = Mb - 1 - zeroBP;

        while (passIdx < numPasses && bitPlane >= 0) {
            int passType;
            if (passIdx == 0) {
                passType = 2; // first pass on each new bit-plane is a Cleanup
            } else {
                passType = (passIdx - 1) % 3; // 0=SigProp, 1=MagRef, 2=Cleanup
            }

            switch (passType) {
                case 0:
                    sigPropPass(mq, coeffs, sigma, eta, cbW, cbH, bitPlane, bandType);
                    break;
                case 1:
                    magRefPass(mq, coeffs, sigma, eta, cbW, cbH, bitPlane);
                    break;
                case 2:
                    cleanupPass(mq, coeffs, sigma, eta, cbW, cbH, bitPlane, bandType);
                    if (segSym) {
                        // Consume the 1010 segmentation symbol (§D.5).
                        mq.decode(CX_UNI);
                        mq.decode(CX_UNI);
                        mq.decode(CX_UNI);
                        mq.decode(CX_UNI);
                    }
                    break;
            }

            passIdx++;
            if (passType == 2) bitPlane--;
        }

        return coeffs;
    }

    private static void sigPropPass(ArithmeticDecoder mq, int[] c, int[] sig, int[] eta,
                                    int w, int h, int bp, int band) {
        for (int j = 0; j < h; j += 4) {
            for (int i = 0; i < w; i++) {
                int jEnd = Math.min(j + 4, h);
                for (int jj = j; jj < jEnd; jj++) {
                    int idx = jj * w + i;
                    if (sig[idx] != 0) continue;
                    if (getNeighborSigCount(sig, i, jj, w, h) == 0) continue;
                    int cx = zeroCodingContext(sig, i, jj, w, h, band);
                    int bit = mq.decode(cx);
                    if (bit != 0) {
                        int sign = decodeSign(mq, sig, c, i, jj, w, h);
                        c[idx] = ((1 << bp) | (sign << 31));
                        sig[idx] = 1;
                    }
                    eta[idx] = 1; // marked as visited in this bit-plane (D.3.1)
                }
            }
        }
    }

    private static void magRefPass(ArithmeticDecoder mq, int[] c, int[] sig, int[] eta,
                                   int w, int h, int bp) {
        for (int j = 0; j < h; j += 4) {
            for (int i = 0; i < w; i++) {
                int jEnd = Math.min(j + 4, h);
                for (int jj = j; jj < jEnd; jj++) {
                    int idx = jj * w + i;
                    if (sig[idx] == 0) continue;
                    if (eta[idx] != 0) continue; // already coded in this bit-plane
                    // Context selection (Table D.4), interop-verified against
                    // OpenJPEG-encoded streams via pdf.js: first refinement of
                    // this coefficient → 14 if ANY significant neighbour,
                    // 15 if none; subsequent refinements → 16. (The earlier
                    // 14/15 assignment was inverted, desynchronising the MQ
                    // stream at the first magnitude-refinement pass.)
                    int cx;
                    if (sig[idx] == 1) {
                        cx = (getNeighborSigCount(sig, i, jj, w, h) > 0) ? 14 : 15;
                    } else {
                        cx = 16;
                    }
                    int bit = mq.decode(cx);
                    if (bit != 0) {
                        c[idx] = (c[idx] & 0x80000000) | ((c[idx] & 0x7FFFFFFF) | (1 << bp));
                    }
                    sig[idx] = 2; // promote: subsequent refinements use cx 16
                    eta[idx] = 1;
                }
            }
        }
    }

    private static void cleanupPass(ArithmeticDecoder mq, int[] c, int[] sig, int[] eta,
                                    int w, int h, int bp, int band) {
        for (int j = 0; j < h; j += 4) {
            for (int i = 0; i < w; i++) {
                int jEnd = Math.min(j + 4, h);
                int jj = j;
                while (jj < jEnd) {
                    int idx = jj * w + i;
                    if (sig[idx] != 0 || eta[idx] != 0) {
                        eta[idx] = 0; // clear visit marker for next bit-plane
                        jj++;
                        continue;
                    }
                    // Run-length mode: only when the entire 4-row stripe column is
                    // insignificant AND none of the four positions has a significant
                    // neighbor.
                    if ((jj == j) && (jj + 3 < jEnd)) {
                        boolean allClean = true;
                        for (int k = 0; k < 4 && allClean; k++) {
                            int ki = (jj + k) * w + i;
                            if (sig[ki] != 0 || eta[ki] != 0
                                    || getNeighborSigCount(sig, i, jj + k, w, h) > 0) {
                                allClean = false;
                            }
                        }
                        if (allClean) {
                            int rl = mq.decode(CX_RL);
                            if (rl == 0) {
                                // All four stay insignificant; clear visit markers.
                                for (int k = 0; k < 4; k++) eta[(jj + k) * w + i] = 0;
                                jj += 4;
                                continue;
                            }
                            int first = (mq.decode(CX_UNI) << 1) | mq.decode(CX_UNI);
                            jj += first;
                            int idx2 = jj * w + i;
                            int sign = decodeSign(mq, sig, c, i, jj, w, h);
                            c[idx2] = ((1 << bp) | (sign << 31));
                            sig[idx2] = 1;
                            eta[idx2] = 0;
                            jj++;
                            continue;
                        }
                    }
                    int cx = zeroCodingContext(sig, i, jj, w, h, band);
                    int bit = mq.decode(cx);
                    if (bit != 0) {
                        int sign = decodeSign(mq, sig, c, i, jj, w, h);
                        c[idx] = ((1 << bp) | (sign << 31));
                        sig[idx] = 1;
                    }
                    eta[idx] = 0;
                    jj++;
                }
            }
        }
    }

    // ─── Context model (Tables D.1, D.2, D.3) ─────────────────────

    private static int getNeighborSigCount(int[] sig, int x, int y, int w, int h) {
        int count = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) continue;
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                    if (sig[ny * w + nx] != 0) count++;
                }
            }
        }
        return count;
    }

    private static int zeroCodingContext(int[] sig, int x, int y, int w, int h, int band) {
        int hc = sigPresent(sig, x - 1, y, w, h) + sigPresent(sig, x + 1, y, w, h);
        int vc = sigPresent(sig, x, y - 1, w, h) + sigPresent(sig, x, y + 1, w, h);
        int dc = sigPresent(sig, x - 1, y - 1, w, h) + sigPresent(sig, x + 1, y - 1, w, h)
               + sigPresent(sig, x - 1, y + 1, w, h) + sigPresent(sig, x + 1, y + 1, w, h);
        switch (band) {
            case BAND_HL:
                // Table D.1: the HL sub-band (horizontally high-pass) swaps the
                // horizontal/vertical contributions; LL and LH share the
                // unswapped mapping. (Was inverted — HL unswapped and LH
                // swapped — corrupting every non-LL band.)
                return ctxFromHVD(vc, hc, dc);
            case BAND_LH:
                return ctxFromHVD(hc, vc, dc);
            case BAND_HH: {
                // Table D.2 — ZC context for HH sub-band: 9 contexts (0..8)
                // arranged on the (h+v, d) grid:
                //   (0,0) → 0    (0,1) → 1    (0,≥2) → 2
                //   (1,0) → 3    (1,1) → 4    (1,≥2) → 5
                //   (2,0) → 6    (2,≥1) → 7
                //   (≥3, x) → 8
                int hv = hc + vc;
                if (dc >= 3) return 8;
                if (dc == 2) return (hv >= 1) ? 7 : 6;
                if (dc == 1) return (hv >= 2) ? 5 : (hv == 1 ? 4 : 3);
                // dc == 0
                if (hv >= 2) return 2;
                if (hv == 1) return 1;
                return 0;
            }
            default: { // BAND_LL — same as HL per Table D.1
                return ctxFromHVD(hc, vc, dc);
            }
        }
    }

    /** Table D.1 row mapping for HL band (and LL, by extension). */
    private static int ctxFromHVD(int hc, int vc, int dc) {
        if (hc == 2) return 8;
        if (hc == 1) {
            if (vc >= 1) return 7;
            if (dc >= 1) return 6;
            return 5;
        }
        // hc == 0
        if (vc == 2) return 4;
        if (vc == 1) return 3;
        if (dc >= 2) return 2;
        if (dc == 1) return 1;
        return 0;
    }

    private static int sigPresent(int[] sig, int x, int y, int w, int h) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        return sig[y * w + x] != 0 ? 1 : 0;
    }

    /**
     * Decodes the sign of a newly-significant coefficient (Annex D.3.2,
     * Table D.3). The horizontal / vertical neighbour contributions are
     * clamped to {-1, 0, +1} (via {@link Integer#signum} of the two-neighbour
     * sum) and used to select one of five contexts (9..13). The decoded
     * MQ bit is XOR'ed with a predicted sign bit to recover the actual sign.
     *
     * <p>Mapping (h, v) → (context, xor_bit):
     * <pre>
     *   ( 0,  0) → ( 9, 0)   (X5)
     *   ( 0, +1) → (10, 0)   (X4 positive)
     *   ( 0, -1) → (10, 1)   (X4 negative)
     *   (+1, -1) → (11, 0)   (X3 positive)
     *   (-1, +1) → (11, 1)   (X3 negative)
     *   (+1,  0) → (12, 0)   (X2 positive)
     *   (-1,  0) → (12, 1)   (X2 negative)
     *   (+1, +1) → (13, 0)   (X1 positive)
     *   (-1, -1) → (13, 1)   (X1 negative)
     * </pre>
     */
    private static int decodeSign(ArithmeticDecoder mq, int[] sig, int[] c,
                                  int x, int y, int w, int h) {
        int hContrib = signContrib(sig, c, x - 1, y, w, h) + signContrib(sig, c, x + 1, y, w, h);
        int vContrib = signContrib(sig, c, x, y - 1, w, h) + signContrib(sig, c, x, y + 1, w, h);
        int hSign = Integer.signum(hContrib);
        int vSign = Integer.signum(vContrib);

        int ctxIdx;
        int xorBit;
        if (hSign == 0 && vSign == 0) {
            ctxIdx = 9;  xorBit = 0;                       // X5
        } else if (hSign == 0) {
            ctxIdx = 10; xorBit = (vSign < 0) ? 1 : 0;     // X4
        } else if (vSign == 0) {
            ctxIdx = 12; xorBit = (hSign < 0) ? 1 : 0;     // X2
        } else if (hSign != vSign) {
            ctxIdx = 11; xorBit = (hSign < 0) ? 1 : 0;     // X3 (opposite)
        } else {
            ctxIdx = 13; xorBit = (hSign < 0) ? 1 : 0;     // X1 (same)
        }
        int bit = mq.decode(ctxIdx);
        return bit ^ xorBit;
    }

    /** +1 if neighbor is significant and positive, -1 if significant and negative, else 0. */
    private static int signContrib(int[] sig, int[] c, int x, int y, int w, int h) {
        if (x < 0 || x >= w || y < 0 || y >= h) return 0;
        int idx = y * w + x;
        if (sig[idx] == 0) return 0;
        return (c[idx] & 0x80000000) != 0 ? -1 : 1;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Inverse Discrete Wavelet Transform (Annex F)
    // ═══════════════════════════════════════════════════════════════

    /** 1D inverse 5/3 lifting (reversible Le Gall). */
    static void inverseDWT53_1D(int[] x, int lo, int len) {
        if (len <= 1) return;
        int halfLen = (len + 1) / 2;
        int[] temp = new int[len];
        System.arraycopy(x, lo, temp, 0, len);
        for (int i = 0; i < halfLen; i++) x[lo + 2 * i] = temp[i];
        for (int i = 0; i < len - halfLen; i++) x[lo + 2 * i + 1] = temp[halfLen + i];
        for (int i = 0; i < len; i += 2) {
            int dm1 = (i > 0) ? x[lo + i - 1] : x[lo + 1];
            int dp0 = (i + 1 < len) ? x[lo + i + 1] : x[lo + i - 1];
            x[lo + i] -= (dm1 + dp0 + 2) >> 2;
        }
        for (int i = 1; i < len; i += 2) {
            int sn = x[lo + i - 1];
            int sn1 = (i + 1 < len) ? x[lo + i + 1] : sn;
            x[lo + i] += (sn + sn1) >> 1;
        }
    }

    /** 1D inverse 9/7 lifting (irreversible Daubechies). */
    static void inverseDWT97_1D(double[] x, int lo, int len) {
        if (len <= 1) return;
        int halfLen = (len + 1) / 2;
        double[] temp = new double[len];
        System.arraycopy(x, lo, temp, 0, len);
        for (int i = 0; i < halfLen; i++) x[lo + 2 * i] = temp[i];
        for (int i = 0; i < len - halfLen; i++) x[lo + 2 * i + 1] = temp[halfLen + i];

        final double K = 1.230174104914001;
        final double D = 0.443506852043971;
        final double G = 0.882911075530934;
        final double B = -0.052980118572961;
        final double A = -1.586134342059924;

        // Scaling step of the inverse lifting (ISO 15444-1 F.4.6, STEP1/2):
        // low band × K, high band × 1/K — the standard analysis gains, matching
        // what real encoders (OpenJPEG, Kakadu) write. The previous K²/1/K²
        // scaling was calibrated against this codebase's own forward transform
        // and produced ~2× contrast error on every real 9/7 codestream.
        for (int i = 0; i < len; i += 2) x[lo + i] *= K;
        for (int i = 1; i < len; i += 2) x[lo + i] /= K;

        for (int i = 0; i < len; i += 2) {
            double left  = (i > 0) ? x[lo + i - 1] : x[lo + 1];
            double right = (i + 1 < len) ? x[lo + i + 1] : x[lo + i - 1];
            x[lo + i] -= D * (left + right);
        }
        for (int i = 1; i < len; i += 2) {
            double left  = x[lo + i - 1];
            double right = (i + 1 < len) ? x[lo + i + 1] : left;
            x[lo + i] -= G * (left + right);
        }
        for (int i = 0; i < len; i += 2) {
            double left  = (i > 0) ? x[lo + i - 1] : x[lo + 1];
            double right = (i + 1 < len) ? x[lo + i + 1] : x[lo + i - 1];
            x[lo + i] -= B * (left + right);
        }
        for (int i = 1; i < len; i += 2) {
            double left  = x[lo + i - 1];
            double right = (i + 1 < len) ? x[lo + i + 1] : left;
            x[lo + i] -= A * (left + right);
        }
    }

    /** 2D inverse DWT for one decomposition level using 5/3 wavelet. */
    static void inverseDWT53_2D(int[] data, int width, int height, int stride) {
        checkCancelled(); // see inverseDWT97_2D
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(data, y * stride, row, 0, width);
            inverseDWT53_1D(row, 0, width);
            System.arraycopy(row, 0, data, y * stride, width);
        }
        checkCancelled();
        int[] col = new int[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) col[y] = data[y * stride + x];
            inverseDWT53_1D(col, 0, height);
            for (int y = 0; y < height; y++) data[y * stride + x] = col[y];
        }
    }

    /** 2D inverse DWT for one decomposition level using 9/7 wavelet. */
    static void inverseDWT97_2D(double[] data, int width, int height, int stride) {
        // Large JPEG2000 tiles run the wavelet for minutes — honour
        // cancellation so a timed-out mass-testing worker unwinds instead of
        // spinning as a zombie thread (observed via jstack on corpus giants).
        checkCancelled();
        double[] row = new double[width];
        for (int y = 0; y < height; y++) {
            System.arraycopy(data, y * stride, row, 0, width);
            inverseDWT97_1D(row, 0, width);
            System.arraycopy(row, 0, data, y * stride, width);
        }
        checkCancelled();
        double[] col = new double[height];
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) col[y] = data[y * stride + x];
            inverseDWT97_1D(col, 0, height);
            for (int y = 0; y < height; y++) data[y * stride + x] = col[y];
        }
    }

    /** Throws an unchecked cancellation marker when the worker is interrupted. */
    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("JPXDecode: interrupted");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //  Main Decoder
    // ═══════════════════════════════════════════════════════════════

    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (encoded == null || encoded.length == 0) return encoded;
        try {
            return decodeJP2(encoded);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "JPXDecode failed: " + e.getMessage() + ", returning raw data", e);
            return encoded;
        }
    }

    private byte[] decodeJP2(byte[] data) throws IOException {
        int off = 0;
        // JP2 file format wrapper detection (signature box: 0x0000000C "jP  \r\n").
        if (data.length > 12 && data[0] == 0 && data[1] == 0 && data[2] == 0
                && data[3] == 0x0C && data[4] == 0x6A && data[5] == 0x50) {
            off = findCodestream(data);
            if (off < 0) throw new IOException("Cannot find codestream in JP2 file");
        }

        CSReader r = new CSReader(data, off);

        int m = r.marker();
        if (m != SOC) throw new IOException("Expected SOC marker, got " + Integer.toHexString(m));

        // Parse main-header markers up to first SOT.
        SIZData siz = null;
        CODData cod = null;
        QCDData qcd = null;

        while (r.pos < data.length - 1) {
            m = r.marker();
            if (m == SOT) break;
            int len = r.u16();
            int endPos = r.pos + len - 2;

            switch (m) {
                case SIZ: siz = parseSIZ(r); break;
                case COD: cod = parseCOD(r); break;
                case QCD: qcd = parseQCD(r, len - 2); break;
                default:  /* skip unknown marker segment */ break;
            }
            r.pos = endPos;
        }

        if (siz == null) throw new IOException("Missing SIZ marker");
        if (cod == null) throw new IOException("Missing COD marker");
        if (qcd == null) throw new IOException("Missing QCD marker");

        final int imgW = siz.width - siz.xOff;
        final int imgH = siz.height - siz.yOff;
        boolean diag = System.getProperty("jpx.diag") != null;

        // ── Collect tile-part bitstreams, keyed by tile index (§A.4.2) ──
        // The reader sits just past the FF90 of the first SOT. Each tile-part:
        // Lsot(2) Isot(2) Psot(4) TPsot(1) TNsot(1), optional markers, SOD,
        // then Psot−(SOD end − SOT start) bytes of packet data. A tile split
        // into several tile-parts concatenates in TPsot order, which is also
        // codestream order.
        java.util.Map<Integer, java.io.ByteArrayOutputStream> tileStreams = new java.util.TreeMap<>();
        while (true) {
            int sotStart = r.pos - 2;
            r.u16(); // Lsot
            int tileIdx = r.u16();
            long psot = r.u32() & 0xFFFFFFFFL;
            int tpIdx = r.u8();
            int numTP = r.u8();
            if (diag) {
                System.out.println("[jpx.diag] SOT tileIdx=" + tileIdx + " psot=" + psot
                        + " tpIdx=" + tpIdx + " numTP=" + numTP);
            }
            // Skip POC/PPT/COM/etc. between SOT and SOD.
            while (r.pos < data.length - 1) {
                m = r.marker();
                if (m == SOD) break;
                int len = r.u16();
                r.skip(len - 2);
            }
            int bodyStart = r.pos;
            int bodyEnd;
            if (psot > 0) {
                bodyEnd = (int) Math.min(data.length, sotStart + psot);
            } else {
                // Psot=0 (last tile-part): runs to the next SOT or EOC.
                bodyEnd = data.length;
                for (int i = bodyStart; i < data.length - 1; i++) {
                    int b0 = data[i] & 0xFF, b1 = data[i + 1] & 0xFF;
                    if (b0 == 0xFF && (b1 == 0x90 || b1 == 0xD9)) {
                        bodyEnd = i;
                        break;
                    }
                }
            }
            if (bodyEnd <= bodyStart) break;
            tileStreams.computeIfAbsent(tileIdx, k1 -> new java.io.ByteArrayOutputStream())
                       .write(data, bodyStart, bodyEnd - bodyStart);
            r.pos = bodyEnd;
            if (r.pos >= data.length - 1) break;
            m = r.marker();
            if (m != SOT) break; // EOC or truncated stream
        }

        // ── Tile grid (§B.3) ──
        int numXT = Math.max(1, ceilDiv(siz.width - siz.txOff, siz.tileW));

        // Full-image component planes; tiles composite into them.
        int numComp = siz.numComp;
        byte[][] planes = new byte[numComp][];
        int[] compW = new int[numComp];
        int[] compH = new int[numComp];
        for (int c = 0; c < numComp; c++) {
            compW[c] = (imgW + siz.xSub[c] - 1) / siz.xSub[c];
            compH[c] = (imgH + siz.ySub[c] - 1) / siz.ySub[c];
            planes[c] = new byte[compW[c] * compH[c]];
        }

        for (java.util.Map.Entry<Integer, java.io.ByteArrayOutputStream> e : tileStreams.entrySet()) {
            int t = e.getKey();
            int p = t % numXT, q = t / numXT;
            int tx0 = Math.max(siz.txOff + p * siz.tileW, siz.xOff);
            int ty0 = Math.max(siz.tyOff + q * siz.tileH, siz.yOff);
            int tx1 = Math.min(siz.txOff + (p + 1) * siz.tileW, siz.width);
            int ty1 = Math.min(siz.tyOff + (q + 1) * siz.tileH, siz.height);
            if (tx1 <= tx0 || ty1 <= ty0) continue;
            if (diag) {
                System.out.println("[jpx.diag] tile " + t + " grid[" + p + "," + q + "] = ("
                        + tx0 + "," + ty0 + ")..(" + tx1 + "," + ty1 + ")");
            }
            decodeTileInto(e.getValue().toByteArray(), siz, cod, qcd,
                    tx0, ty0, tx1, ty1, planes, compW, compH);
        }

        return interleaveComponents(planes, imgW, imgH, numComp);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Locates the {@code jp2c} codestream box inside a JP2-wrapped file. */
    private int findCodestream(byte[] data) {
        int pos = 0;
        while (pos < data.length - 8) {
            int boxLen = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                       | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            int boxType = ((data[pos + 4] & 0xFF) << 24) | ((data[pos + 5] & 0xFF) << 16)
                        | ((data[pos + 6] & 0xFF) << 8) | (data[pos + 7] & 0xFF);
            if (boxType == 0x6A703263) { // "jp2c"
                return pos + 8;
            }
            if (boxLen <= 0) break;
            pos += boxLen;
        }
        return -1;
    }

    // ─── Marker parsers ──────────────────────────────────────────

    private SIZData parseSIZ(CSReader r) {
        SIZData s = new SIZData();
        r.u16(); // Rsiz
        s.width = r.u32();
        s.height = r.u32();
        s.xOff = r.u32();
        s.yOff = r.u32();
        s.tileW = r.u32();
        s.tileH = r.u32();
        s.txOff = r.u32();
        s.tyOff = r.u32();
        s.numComp = r.u16();
        s.bitDepths = new int[s.numComp];
        s.xSub = new int[s.numComp];
        s.ySub = new int[s.numComp];
        for (int i = 0; i < s.numComp; i++) {
            s.bitDepths[i] = r.u8();
            s.xSub[i] = r.u8();
            s.ySub[i] = r.u8();
        }
        return s;
    }

    private CODData parseCOD(CSReader r) {
        if (System.getProperty("jpx.diag") != null) {
            StringBuilder hex = new StringBuilder();
            for (int b = r.pos; b < Math.min(r.pos + 16, r.data.length); b++) {
                hex.append(String.format("%02X ", r.data[b] & 0xFF));
            }
            System.out.println("[jpx.diag] COD bytes from " + r.pos + ": " + hex);
        }
        CODData c = new CODData();
        int scod = r.u8();
        c.useSop = (scod & 2) != 0;
        c.useEph = (scod & 4) != 0;
        c.progressionOrder = r.u8();
        c.numLayers = r.u16();
        c.mct = r.u8();
        c.numDecomp = r.u8();
        c.cbW = 1 << (r.u8() + 2);
        c.cbH = 1 << (r.u8() + 2);
        c.cbStyle = r.u8();
        c.wavelet = r.u8();
        if (System.getProperty("jpx.diag") != null) {
            System.out.println("[jpx.diag] COD scod=0x" + Integer.toHexString(scod)
                    + " (explicitPrecincts=" + ((scod & 1) != 0)
                    + ", SOPmarkers=" + ((scod & 2) != 0)
                    + ", EPHmarkers=" + ((scod & 4) != 0) + ")");
        }
        // Explicit precincts (scod & 1) — read but otherwise ignored; this
        // decoder always uses precinct = whole sub-band (PPx=PPy=15).
        if ((scod & 1) != 0) {
            StringBuilder ps = new StringBuilder();
            for (int i = 0; i <= c.numDecomp; i++) {
                int pp = r.u8();
                int ppx = pp & 0x0F, ppy = (pp >> 4) & 0x0F;
                ps.append(" r").append(i).append(":PPx=").append(ppx).append(",PPy=").append(ppy);
            }
            if (System.getProperty("jpx.diag") != null) {
                System.out.println("[jpx.diag] COD precincts:" + ps);
            }
        }
        return c;
    }

    private QCDData parseQCD(CSReader r, int len) {
        QCDData q = new QCDData();
        int sqcd = r.u8();
        q.style = sqcd & 0x1F;
        q.guardBits = (sqcd >> 5) & 7;
        int remaining = len - 1;
        if (q.style == 0) {
            q.exponents = new int[remaining];
            q.mantissas = new int[remaining];
            for (int i = 0; i < remaining; i++) {
                int val = r.u8();
                q.exponents[i] = val >> 3;
                q.mantissas[i] = 0;
            }
        } else {
            int count = remaining / 2;
            q.exponents = new int[count];
            q.mantissas = new int[count];
            for (int i = 0; i < count; i++) {
                int val = r.u16();
                q.exponents[i] = val >> 11;
                q.mantissas[i] = val & 0x7FF;
            }
        }
        return q;
    }

    // ─── Tile decoding ───────────────────────────────────────────

    /**
     * Decodes one tile's bitstream and composites the result into the
     * full-image component {@code planes}.
     * <p>
     * NOTE: the sub-band size/code-block partition arithmetic below halves the
     * tile dimensions directly, which matches the coordinate-based formulas of
     * §B.5 only when the tile origin is aligned to {@code 2^numDecomp} (true
     * for the ubiquitous XTOsiz=0 + power-of-two tile sizes) — the same
     * assumption the single-tile path has always made for the image origin.
     * </p>
     *
     * @param tx0 tile bounds on the reference grid (§B.3)
     */
    private void decodeTileInto(byte[] data, SIZData siz, CODData cod, QCDData qcd,
                                int tx0, int ty0, int tx1, int ty1,
                                byte[][] planes, int[] compW, int[] compH) throws IOException {
        int numComp = siz.numComp;
        int numDecomp = cod.numDecomp;
        boolean reversible = (cod.wavelet == 1);
        int bsStart = 0, bsEnd = data.length;
        int imgW = tx1 - tx0, imgH = ty1 - ty0; // this tile's grid dimensions

        boolean diag = System.getProperty("jpx.diag") != null;
        if (diag) {
            System.out.println("[jpx.diag] tile imgW=" + imgW + " imgH=" + imgH
                    + " numComp=" + numComp + " numDecomp=" + numDecomp
                    + " wavelet=" + (reversible ? "5/3" : "9/7")
                    + " mct=" + cod.mct
                    + " progOrder=" + cod.progressionOrder
                    + " numLayers=" + cod.numLayers
                    + " cbW=" + cod.cbW + " cbH=" + cod.cbH
                    + " cbStyle=0x" + Integer.toHexString(cod.cbStyle));
            System.out.println("[jpx.diag] QCD style=" + qcd.style + " G=" + qcd.guardBits
                    + " entries=" + qcd.exponents.length);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < qcd.exponents.length; i++) {
                if (i > 0) sb.append(' ');
                sb.append("[").append(i).append("]ε=").append(qcd.exponents[i])
                  .append(",μ=").append(qcd.mantissas[i]);
            }
            System.out.println("[jpx.diag] QCD: " + sb);
        }

        // Per-component sub-band/code-block grids.
        SubBandInfo[][] perComp = new SubBandInfo[numComp][];
        int[] cWArr = new int[numComp];
        int[] cHArr = new int[numComp];
        for (int c = 0; c < numComp; c++) {
            cWArr[c] = (imgW + siz.xSub[c] - 1) / siz.xSub[c];
            cHArr[c] = (imgH + siz.ySub[c] - 1) / siz.ySub[c];
            perComp[c] = buildPerCompBands(cWArr[c], cHArr[c], numDecomp, cod.cbW, cod.cbH);
        }

        // Tier-2: walk packets in progression order, accumulating per-codeblock
        // bytes and pass counts.
        runLRCPPackets(data, bsStart, bsEnd, cod, numComp, perComp);

        // Tier-1 + IDWT + dequantize — per component.
        byte[][] compData = new byte[numComp][];
        int[][] intData = new int[numComp][];
        double[][] dblData = new double[numComp][];
        for (int c = 0; c < numComp; c++) {
            int[][] subBands = assembleSubBands(perComp[c], qcd, numDecomp,
                    (cod.cbStyle & 0x20) != 0);
            int bitDepth = (siz.bitDepths[c] & 0x7F) + 1;
            boolean isSigned = (siz.bitDepths[c] & 0x80) != 0;
            if (diag) {
                diagPrintSubBandStats(c, perComp[c], subBands, qcd, numDecomp);
                diagDumpLLThumbnail(c, perComp[c], subBands, qcd, numDecomp, bitDepth);
                diagDumpLLValues(c, perComp[c], subBands);
            }
            // DEBUG: zero out all HF bands to test if LL+IDWT gives blurry-but-correct image
            if (System.getProperty("jpx.zerohf") != null) {
                for (int i = 0; i < subBands.length - 1; i++) {
                    int[] sb = subBands[i];
                    for (int j = 0; j < sb.length; j++) sb[j] = 0;
                }
            }
            // DEBUG: force all signs positive in LL to see if signs are flipped.
            if (System.getProperty("jpx.posll") != null) {
                int[] ll = subBands[subBands.length - 1];
                for (int j = 0; j < ll.length; j++) ll[j] = ll[j] & 0x7FFFFFFF;
            }
            // DEBUG: force ALL signs positive in every band.
            if (System.getProperty("jpx.posall") != null) {
                for (int[] sb : subBands) {
                    for (int j = 0; j < sb.length; j++) sb[j] = sb[j] & 0x7FFFFFFF;
                }
            }
            // DEBUG: flip every sign in LL.
            if (System.getProperty("jpx.flipll") != null) {
                int[] ll = subBands[subBands.length - 1];
                for (int j = 0; j < ll.length; j++) ll[j] = ll[j] ^ 0x80000000;
            }
            // DEBUG: replace LL with a constant DC value to test IDWT correctness.
            // Expected: a uniformly-gray spatial image.
            String constLL = System.getProperty("jpx.constll");
            if (constLL != null) {
                int dcMag = Integer.parseInt(constLL);
                int[] ll = subBands[subBands.length - 1];
                for (int j = 0; j < ll.length; j++) ll[j] = dcMag;  // positive DC
                for (int i = 0; i < subBands.length - 1; i++) {
                    int[] sb = subBands[i];
                    for (int j = 0; j < sb.length; j++) sb[j] = 0;
                }
            }
            if (reversible) {
                intData[c] = inverseDWT53_Full(subBands, cWArr[c], cHArr[c], numDecomp);
                if (diag) diagPrintSpatial("DWT53.out", c, intData[c], cWArr[c], cHArr[c]);
            } else {
                dblData[c] = inverseDWT97_Full(subBands, cWArr[c], cHArr[c],
                        numDecomp, qcd, bitDepth);
                if (diag) diagPrintSpatial97("DWT97.out", c, dblData[c], cWArr[c], cHArr[c]);
            }
        }

        // Inverse multiple-component transform — per tile, BEFORE the DC level
        // shift and byte clamping (§G.2): RCT chroma spans ~9 bits signed, so
        // clamping components to bytes first destroyed saturated colours.
        if (cod.mct == 1 && numComp >= 3
                && cWArr[0] == cWArr[1] && cWArr[0] == cWArr[2]
                && cHArr[0] == cHArr[1] && cHArr[0] == cHArr[2]) {
            int n = cWArr[0] * cHArr[0];
            if (reversible) {
                int[] y0 = intData[0], u0 = intData[1], v0 = intData[2];
                for (int i = 0; i < n; i++) {
                    int y = y0[i], u = u0[i], v = v0[i];
                    int gg = y - ((u + v) >> 2);
                    y0[i] = v + gg; // R
                    u0[i] = gg;     // G
                    v0[i] = u + gg; // B
                }
            } else {
                double[] y0 = dblData[0], u0 = dblData[1], v0 = dblData[2];
                for (int i = 0; i < n; i++) {
                    double y = y0[i], u = u0[i], v = v0[i];
                    y0[i] = y + 1.402 * v;
                    u0[i] = y - 0.34413 * u - 0.71414 * v;
                    v0[i] = y + 1.772 * u;
                }
            }
        }

        for (int c = 0; c < numComp; c++) {
            int bitDepth = (siz.bitDepths[c] & 0x7F) + 1;
            boolean isSigned = (siz.bitDepths[c] & 0x80) != 0;
            compData[c] = reversible
                    ? coeffsToBytes(intData[c], cWArr[c], cHArr[c], bitDepth, isSigned)
                    : doubleCoeffsToBytes(dblData[c], cWArr[c], cHArr[c], bitDepth, isSigned);
        }

        if (diag && numComp >= 3) {
            // Dump per-component planes (pre-MCT) for visual inspection.
            try {
                java.nio.file.Path out = java.nio.file.Paths.get(System.getProperty("jpx.diag.dir",
                        System.getProperty("user.dir")));
                String[] names = {"Y", "Cb", "Cr"};
                for (int cc = 0; cc < 3 && cc < numComp; cc++) {
                    java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                            imgW, imgH, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
                    for (int yy = 0; yy < imgH; yy++) {
                        for (int xx = 0; xx < imgW; xx++) {
                            int v = compData[cc][yy * imgW + xx] & 0xFF;
                            img.getRaster().setSample(xx, yy, 0, v);
                        }
                    }
                    javax.imageio.ImageIO.write(img, "PNG",
                            out.resolve("jpxdiag_postmct_" + names[cc] + ".png").toFile());
                }
                System.out.println("[jpx.diag] wrote post-MCT components to " + out);
            } catch (Exception e) {
                System.out.println("[jpx.diag] component dump failed: " + e.getMessage());
            }
        }

        // Composite the tile into the full-image component planes.
        for (int c = 0; c < numComp; c++) {
            int dstX = ceilDiv(tx0, siz.xSub[c]) - ceilDiv(siz.xOff, siz.xSub[c]);
            int dstY = ceilDiv(ty0, siz.ySub[c]) - ceilDiv(siz.yOff, siz.ySub[c]);
            int rowLen = Math.min(cWArr[c], compW[c] - dstX);
            for (int y = 0; y < cHArr[c]; y++) {
                int dy = dstY + y;
                if (dy < 0 || dy >= compH[c] || rowLen <= 0) continue;
                System.arraycopy(compData[c], y * cWArr[c],
                        planes[c], dy * compW[c] + dstX, rowLen);
            }
        }
    }

    /**
     * Builds the sub-band metadata grid for one component. Layout matches
     * what {@link #inverseDWT53_Full} / {@link #inverseDWT97_Full} expect:
     * indices 0..3·numDecomp−1 hold (HL, LH, HH) triplets ordered from the
     * deepest decomposition level down to level 1; the final index holds
     * LL_numDecomp.
     */
    private SubBandInfo[] buildPerCompBands(int cW, int cH, int numDecomp, int cbW, int cbH) {
        int numBands = 1 + 3 * numDecomp;
        SubBandInfo[] bands = new SubBandInfo[numBands];
        int w = cW, h = cH;
        for (int d = 1; d <= numDecomp; d++) {
            int llW = (w + 1) / 2, llH = (h + 1) / 2;
            int hlW = w - llW, hlH = llH;
            int lhW = llW, lhH = h - llH;
            int hhW = w - llW, hhH = h - llH;
            int base = (numDecomp - d) * 3;
            bands[base + 0] = new SubBandInfo(hlW, hlH, BAND_HL, d, base + 0, cbW, cbH);
            bands[base + 1] = new SubBandInfo(lhW, lhH, BAND_LH, d, base + 1, cbW, cbH);
            bands[base + 2] = new SubBandInfo(hhW, hhH, BAND_HH, d, base + 2, cbW, cbH);
            w = llW;
            h = llH;
        }
        bands[numBands - 1] = new SubBandInfo(w, h, BAND_LL, numDecomp, numBands - 1, cbW, cbH);
        return bands;
    }

    /** Returns the sub-bands present at resolution level {@code r} (0 = LL only). */
    private List<SubBandInfo> bandsAtResolution(SubBandInfo[] perComp, int r, int numDecomp) {
        List<SubBandInfo> out = new ArrayList<>();
        if (r == 0) {
            out.add(perComp[perComp.length - 1]);
        } else {
            int base = (numDecomp - (numDecomp - r + 1)) * 3; // = (r - 1) * 3
            out.add(perComp[base + 0]);
            out.add(perComp[base + 1]);
            out.add(perComp[base + 2]);
        }
        return out;
    }

    // ═══════════════════════════════════════════════════════════════
    //  Tier-2: LRCP packet walker (Annex B.10)
    // ═══════════════════════════════════════════════════════════════

    private void runLRCPPackets(byte[] data, int bsStart, int bsEnd,
                                CODData cod, int numComp, SubBandInfo[][] perComp) {
        BitReader br = new BitReader(data, bsStart, bsEnd);
        int totalRes = cod.numDecomp + 1;
        boolean diag = System.getProperty("jpx.diag") != null;
        int finalBodyPos = bsStart;
        if (diag) {
            System.out.println("[jpx.diag] LRCP start bsStart=" + bsStart + " bsEnd=" + bsEnd
                    + " layers=" + cod.numLayers + " totalRes=" + totalRes);
        }
        int pktIdx = 0;
        // §B.12: packet visit order. With the default single precinct per
        // resolution (no explicit precincts, PPx=15 covers the tile) the
        // position loop is trivial, so all five progression orders reduce to
        // permutations of the (layer, resolution, component) loops.
        for (int[] pk : packetSequence(cod.progressionOrder, cod.numLayers, totalRes, numComp)) {
            int layer = pk[0];
            int r = pk[1];
            int c = pk[2];
            // Scod bit 1: each packet is preceded by a 6-byte SOP marker
            // segment (FF91 Lsop=0004 Nsop) — skip it or the header bit
            // reader desyncs into noise (corpus 53308 invoice scans).
            if (cod.useSop && br.pos + 1 < bsEnd
                    && (data[br.pos] & 0xFF) == 0xFF && (data[br.pos + 1] & 0xFF) == 0x91) {
                br.pos += 6;
                br.bitsLeft = 0;
                br.lastWasFF = false;
            }
            int hdrPos = br.pos;
            int hdrBitsLeft = br.bitsLeft;
            if (diag) {
                StringBuilder hex = new StringBuilder();
                for (int b = hdrPos; b < Math.min(hdrPos + 8, bsEnd); b++) {
                    hex.append(String.format("%02X ", data[b] & 0xFF));
                }
                System.out.println(String.format("[jpx.diag]   hdr@%d bitsLeft=%d lastFF=%s bytes: %s",
                        hdrPos, hdrBitsLeft, br.lastWasFF, hex));
            }
            List<SubBandInfo> bands = bandsAtResolution(perComp[c], r, cod.numDecomp);
            decodePacketHeader(br, bands, layer);
            int bodyPos = br.alignToByte();
            // Scod bit 2: a 2-byte EPH marker (FF92) terminates the packet
            // header, before the code-block bodies.
            if (cod.useEph && bodyPos + 1 < bsEnd
                    && (data[bodyPos] & 0xFF) == 0xFF && (data[bodyPos + 1] & 0xFF) == 0x92) {
                bodyPos += 2;
            }
            int sumBytes = 0, sumPasses = 0, includedCb = 0, totalCb = 0;
            if (diag) {
                for (SubBandInfo band : bands) {
                    for (int cby = 0; cby < band.cbRows; cby++) {
                        for (int cbx = 0; cbx < band.cbCols; cbx++) {
                            TileCodeBlock cb = band.cbs[cby][cbx];
                            totalCb++;
                            if (cb.pendingBytes > 0) { includedCb++; sumBytes += cb.pendingBytes; sumPasses += cb.pendingPasses; }
                        }
                    }
                }
                System.out.println(String.format(
                        "[jpx.diag] pkt#%d L=%d r=%d c=%d hdrPos=%d→bodyPos=%d hdrLen=%d cbInc=%d/%d bytes=%d passes=%d",
                        pktIdx++, layer, r, c, hdrPos, bodyPos, bodyPos - hdrPos, includedCb, totalCb, sumBytes, sumPasses));
            }
            bodyPos = readPacketBody(data, bodyPos, bsEnd, bands);
            // Resync the bit reader to the new byte position.
            br.pos = bodyPos;
            br.bitsLeft = 0;
            br.lastWasFF = false;
            finalBodyPos = bodyPos;
            if (bodyPos >= bsEnd) {
                if (diag) System.out.println("[jpx.diag] LRCP done (bsEnd reached) at " + bodyPos);
                return;
            }
        }
        if (diag) {
            System.out.println("[jpx.diag] LRCP done. finalBodyPos=" + finalBodyPos + " bsEnd=" + bsEnd
                    + " unused=" + (bsEnd - finalBodyPos) + " bytes");
            if (finalBodyPos < bsEnd) {
                StringBuilder hex = new StringBuilder();
                for (int b = finalBodyPos; b < Math.min(finalBodyPos + 32, bsEnd); b++) {
                    hex.append(String.format("%02X ", data[b] & 0xFF));
                }
                System.out.println("[jpx.diag] first unread bytes: " + hex);
            }
        }
    }

    /**
     * Builds the packet visit sequence of {layer, resolution, component}
     * triples for the given progression order (§B.12, Table A.16). Assumes a
     * single precinct per resolution, which collapses the position loop:
     * LRCP = L→R→C, RLCP = R→L→C, RPCL = R→C→L, PCRL/CPRL = C→R→L.
     *
     * @param order   COD progression order (0..4)
     * @param layers  number of quality layers
     * @param totalRes number of resolution levels (numDecomp + 1)
     * @param numComp number of components
     * @return packet coordinates in transmission order
     */
    static int[][] packetSequence(int order, int layers, int totalRes, int numComp) {
        int[][] seq = new int[layers * totalRes * numComp][];
        int i = 0;
        switch (order) {
            case 1: // RLCP: resolution, layer, component
                for (int r = 0; r < totalRes; r++)
                    for (int l = 0; l < layers; l++)
                        for (int c = 0; c < numComp; c++)
                            seq[i++] = new int[]{l, r, c};
                break;
            case 2: // RPCL: resolution, position, component, layer
                for (int r = 0; r < totalRes; r++)
                    for (int c = 0; c < numComp; c++)
                        for (int l = 0; l < layers; l++)
                            seq[i++] = new int[]{l, r, c};
                break;
            case 3: // PCRL: position, component, resolution, layer
            case 4: // CPRL: component, position, resolution, layer
                for (int c = 0; c < numComp; c++)
                    for (int r = 0; r < totalRes; r++)
                        for (int l = 0; l < layers; l++)
                            seq[i++] = new int[]{l, r, c};
                break;
            default: // 0 = LRCP: layer, resolution, component
                for (int l = 0; l < layers; l++)
                    for (int r = 0; r < totalRes; r++)
                        for (int c = 0; c < numComp; c++)
                            seq[i++] = new int[]{l, r, c};
        }
        return seq;
    }

    /** Decodes one packet header into the per-codeblock {@code pendingBytes/pendingPasses}. */
    private void decodePacketHeader(BitReader br, List<SubBandInfo> bands, int layer) {
        boolean diag = System.getProperty("jpx.diag") != null;
        // Reset per-packet scratch on every code-block we'll inspect.
        for (SubBandInfo band : bands) {
            for (int cby = 0; cby < band.cbRows; cby++) {
                for (int cbx = 0; cbx < band.cbCols; cbx++) {
                    TileCodeBlock cb = band.cbs[cby][cbx];
                    cb.pendingBytes = 0;
                    cb.pendingPasses = 0;
                }
            }
        }

        int present = br.readBit();
        if (present == 0) {
            return; // empty packet
        }

        for (SubBandInfo band : bands) {
            for (int cby = 0; cby < band.cbRows; cby++) {
                for (int cbx = 0; cbx < band.cbCols; cbx++) {
                    TileCodeBlock cb = band.cbs[cby][cbx];
                    boolean includedNow;
                    boolean wasIncluded = cb.included;
                    if (!cb.included) {
                        int v = band.inclTree.decode(br, cbx, cby, layer + 1);
                        includedNow = (v >= 0 && v <= layer);
                        if (includedNow) cb.included = true;
                    } else {
                        includedNow = (br.readBit() == 1);
                    }
                    if (!includedNow) continue;

                    if (!cb.zbpDecoded) {
                        int K = -1;
                        int t = 1;
                        // Iterate threshold until tag-tree resolves to a final value.
                        while (K < 0) {
                            K = band.zbpTree.decode(br, cbx, cby, t);
                            if (K < 0) t++;
                            if (t > 64) break; // safety
                        }
                        cb.zeroBitplanes = Math.max(0, K);
                        cb.zbpDecoded = true;
                    }

                    int P = readNumPasses(br);
                    int incr = 0;
                    while (br.readBit() == 1) {
                        incr++;
                        if (incr > 64) break; // safety
                    }
                    cb.Lblock += incr;

                    int log2P = 31 - Integer.numberOfLeadingZeros(Math.max(1, P));
                    int lenBits = cb.Lblock + log2P;
                    int segLen = br.readBits(lenBits);

                    cb.pendingBytes = segLen;
                    cb.pendingPasses = P;
                    if (diag) {
                        String bandName = (band.bandType==0?"LL":band.bandType==1?"HL":band.bandType==2?"LH":"HH");
                        System.out.println(String.format(
                                "[jpx.diag]   CB[%s_%d %d,%d] %s K=%d Lblock=%d (+%d) P=%d segLen=%d",
                                bandName, band.decompLevel, cbx, cby,
                                wasIncluded ? "RE-INCL" : "NEW",
                                cb.zeroBitplanes, cb.Lblock, incr, P, segLen));
                    }
                }
            }
        }
    }

    /**
     * Reads the variable-length new-pass count (Annex B.10.7, Table B.4).
     *
     * <p>The encoding is a nested escape-coding:
     * <pre>
     *   "0"                                → P = 1
     *   "10"                               → P = 2
     *   "11" + v(2 bits, v < 3)            → P = v + 3            (P = 3, 4, 5)
     *   "11" + "11" + v(5 bits, v < 31)    → P = v + 6            (P = 6..36)
     *   "11" + "11" + "11111" + v(7 bits)  → P = v + 37           (P = 37..164)
     * </pre>
     * Note that {@code v = 3} in the 2-bit field, and {@code v = 31} in the
     * 5-bit field, are escape codes that signal "read more bits". Previous
     * versions of this method incorrectly used {@code two == 0b10} (=2) as
     * the escape, which is actually a valid value (P=5), throwing off P and
     * causing wildly wrong segment lengths.
     */
    private int readNumPasses(BitReader br) {
        if (br.readBit() == 0) return 1;
        if (br.readBit() == 0) return 2;
        int v2 = br.readBits(2);
        if (v2 != 3) return v2 + 3;      // P = 3..5
        int v5 = br.readBits(5);
        if (v5 != 31) return v5 + 6;     // P = 6..36
        return br.readBits(7) + 37;      // P = 37..164
    }

    /** Reads packet body bytes and distributes them among included code-blocks. */
    private int readPacketBody(byte[] data, int pos, int endPos, List<SubBandInfo> bands) {
        for (SubBandInfo band : bands) {
            for (int cby = 0; cby < band.cbRows; cby++) {
                for (int cbx = 0; cbx < band.cbCols; cbx++) {
                    TileCodeBlock cb = band.cbs[cby][cbx];
                    if (cb.pendingBytes <= 0) continue;
                    int avail = Math.min(cb.pendingBytes, endPos - pos);
                    if (avail <= 0) {
                        cb.pendingBytes = 0;
                        cb.pendingPasses = 0;
                        continue;
                    }
                    cb.data.write(data, pos, avail);
                    pos += avail;
                    cb.totalPasses += cb.pendingPasses;
                    cb.pendingBytes = 0;
                    cb.pendingPasses = 0;
                }
            }
        }
        return pos;
    }

    /** Runs Tier-1 on every code-block of every sub-band, returning per-sub-band coefficient grids. */
    private int[][] assembleSubBands(SubBandInfo[] perComp, QCDData qcd, int numDecomp,
                                     boolean segSym) {
        int[][] out = new int[perComp.length][];
        for (SubBandInfo band : perComp) {
            int w = band.width, h = band.height;
            if (w == 0 || h == 0) {
                out[band.globalIdx] = new int[0];
                continue;
            }
            int Mb = computeMb(band, qcd, numDecomp);
            int[] full = new int[w * h];
            for (int cby = 0; cby < band.cbRows; cby++) {
                for (int cbx = 0; cbx < band.cbCols; cbx++) {
                    TileCodeBlock cb = band.cbs[cby][cbx];
                    if (cb.totalPasses <= 0 || cb.data.size() == 0) continue;
                    int[] coeffs = decodeTier1(cb.data.toByteArray(),
                            cb.width, cb.height, cb.totalPasses,
                            Mb, cb.zeroBitplanes, band.bandType, segSym);
                    // Tier-1 emits sign-magnitude (sign in bit 31); the IDWT
                    // and dequantizer work on ordinary two's-complement ints.
                    // (This conversion was missing entirely on the reversible
                    // 5/3 path — every negative coefficient reached the IDWT
                    // as a huge positive value.)
                    for (int k = 0; k < coeffs.length; k++) {
                        int v = coeffs[k];
                        if ((v & 0x80000000) != 0) coeffs[k] = -(v & 0x7FFFFFFF);
                    }
                    int dx = cbx * band.cbW;
                    int dy = cby * band.cbH;
                    for (int yy = 0; yy < cb.height; yy++) {
                        int srcOff = yy * cb.width;
                        int dstOff = (dy + yy) * w + dx;
                        System.arraycopy(coeffs, srcOff, full, dstOff, cb.width);
                    }
                }
            }
            out[band.globalIdx] = full;
        }
        return out;
    }

    /** Computes M_b — the nominal magnitude bit-plane count for sub-band b. */
    private int computeMb(SubBandInfo band, QCDData qcd, int numDecomp) {
        int qcdIdx;
        if (qcd.style == 1) {
            qcdIdx = 0;
        } else {
            qcdIdx = (band.globalIdx == 3 * numDecomp) ? 0 : (band.globalIdx + 1);
        }
        if (qcdIdx >= qcd.exponents.length) qcdIdx = qcd.exponents.length - 1;
        int eps = qcd.exponents[qcdIdx];
        if (qcd.style == 1) {
            eps = eps - (numDecomp - band.decompLevel);
        }
        return qcd.guardBits + Math.max(0, eps) - 1;
    }

    // ─── Full inverse DWT ────────────────────────────────────────

    private int[] inverseDWT53_Full(int[][] subBands, int cW, int cH, int numDecomp) {
        int[] result = new int[cW * cH];
        if (numDecomp == 0) {
            int[] ll = subBands[subBands.length - 1];
            System.arraycopy(ll, 0, result, 0, Math.min(ll.length, result.length));
            return result;
        }
        // Build LL_numDecomp into a working buffer; iteratively combine with
        // HL/LH/HH at the next finer level and IDWT the combined block.
        int[] ll = subBands[subBands.length - 1];
        int llW = (cW + (1 << numDecomp) - 1) >> numDecomp;
        int llH = (cH + (1 << numDecomp) - 1) >> numDecomp;
        int[] cur = new int[llW * llH];
        System.arraycopy(ll, 0, cur, 0, Math.min(ll.length, cur.length));
        int curW = llW, curH = llH;

        for (int d = numDecomp; d >= 1; d--) {
            int idx = (numDecomp - d) * 3;
            int newW = (cW + ((1 << (d - 1)) - 1)) >> (d - 1);
            int newH = (cH + ((1 << (d - 1)) - 1)) >> (d - 1);
            int halfW = (newW + 1) >> 1;
            int halfH = (newH + 1) >> 1;
            int[] combined = new int[newW * newH];
            // Place LL (interleaved as low coeffs at even positions in 1D
            // sense; the IDWT 1D routine de-interleaves them).
            for (int y = 0; y < halfH; y++) {
                for (int x = 0; x < halfW; x++) {
                    if (y * curW + x < cur.length) combined[y * newW + x] = cur[y * curW + x];
                }
            }
            int[] hl = subBands[idx], lh = subBands[idx + 1], hh = subBands[idx + 2];
            int hlW = newW - halfW, lhH = newH - halfH;
            for (int y = 0; y < halfH; y++)
                for (int x = 0; x < hlW; x++)
                    if (y * hlW + x < hl.length)
                        combined[y * newW + halfW + x] = hl[y * hlW + x];
            for (int y = 0; y < lhH; y++)
                for (int x = 0; x < halfW; x++)
                    if (y * halfW + x < lh.length)
                        combined[(halfH + y) * newW + x] = lh[y * halfW + x];
            for (int y = 0; y < lhH; y++)
                for (int x = 0; x < hlW; x++)
                    if (y * hlW + x < hh.length)
                        combined[(halfH + y) * newW + halfW + x] = hh[y * hlW + x];

            inverseDWT53_2D(combined, newW, newH, newW);
            cur = combined;
            curW = newW;
            curH = newH;
        }

        // cur is now full cW×cH; copy out.
        for (int y = 0; y < cH; y++) {
            System.arraycopy(cur, y * curW, result, y * cW, cW);
        }
        return result;
    }

    private double[] inverseDWT97_Full(int[][] subBands, int cW, int cH,
                                       int numDecomp, QCDData qcd, int bitDepth) {
        if (numDecomp == 0) {
            double[] result = new double[cW * cH];
            int[] ll = subBands[subBands.length - 1];
            int llIdx = subBands.length - 1;
            for (int i = 0; i < ll.length && i < result.length; i++) {
                result[i] = dequantize(ll[i], qcd, llIdx, numDecomp, BAND_LL, numDecomp, bitDepth);
            }
            return result;
        }

        // Start with LL_numDecomp dequantized.
        int[] ll = subBands[subBands.length - 1];
        int llW = (cW + (1 << numDecomp) - 1) >> numDecomp;
        int llH = (cH + (1 << numDecomp) - 1) >> numDecomp;
        double[] cur = new double[llW * llH];
        for (int i = 0; i < ll.length && i < cur.length; i++) {
            cur[i] = dequantize(ll[i], qcd, subBands.length - 1, numDecomp,
                    BAND_LL, numDecomp, bitDepth);
        }
        int curW = llW;

        for (int d = numDecomp; d >= 1; d--) {
            int idx = (numDecomp - d) * 3;
            int newW = (cW + ((1 << (d - 1)) - 1)) >> (d - 1);
            int newH = (cH + ((1 << (d - 1)) - 1)) >> (d - 1);
            int halfW = (newW + 1) >> 1;
            int halfH = (newH + 1) >> 1;
            double[] combined = new double[newW * newH];
            for (int y = 0; y < halfH; y++) {
                for (int x = 0; x < halfW; x++) {
                    if (y * curW + x < cur.length) combined[y * newW + x] = cur[y * curW + x];
                }
            }
            int[] hl = subBands[idx], lh = subBands[idx + 1], hh = subBands[idx + 2];
            int hlW = newW - halfW, lhH = newH - halfH;
            for (int y = 0; y < halfH; y++)
                for (int x = 0; x < hlW; x++)
                    if (y * hlW + x < hl.length)
                        combined[y * newW + halfW + x] =
                                dequantize(hl[y * hlW + x], qcd, idx, numDecomp,
                                        BAND_HL, d, bitDepth);
            for (int y = 0; y < lhH; y++)
                for (int x = 0; x < halfW; x++)
                    if (y * halfW + x < lh.length)
                        combined[(halfH + y) * newW + x] =
                                dequantize(lh[y * halfW + x], qcd, idx + 1, numDecomp,
                                        BAND_LH, d, bitDepth);
            for (int y = 0; y < lhH; y++)
                for (int x = 0; x < hlW; x++)
                    if (y * hlW + x < hh.length)
                        combined[(halfH + y) * newW + halfW + x] =
                                dequantize(hh[y * hlW + x], qcd, idx + 2, numDecomp,
                                        BAND_HH, d, bitDepth);
            inverseDWT97_2D(combined, newW, newH, newW);
            cur = combined;
            curW = newW;
        }

        double[] result = new double[cW * cH];
        for (int y = 0; y < cH; y++) {
            System.arraycopy(cur, y * curW, result, y * cW, cW);
        }
        return result;
    }

    /**
     * Dequantizes one Tier-1 magnitude into a double sub-band sample (Annex E).
     *
     * <p>The QCD "expounded" layout stores values in band-walk order
     * (LL_max, then HL/LH/HH from deepest to shallowest decomp level); our
     * internal sub-band index maps to that order via
     * {@code internalIdx == 3·numDecomp ⇒ QCD[0]} for LL, and
     * {@code internalIdx ⇒ QCD[internalIdx + 1]} for HL/LH/HH.</p>
     *
     * @param coeff      Tier-1 coefficient (sign in MSB)
     * @param qcd        quantization data from QCD marker
     * @param internalIdx sub-band index in our internal layout
     * @param numDecomp  total decomposition levels
     * @param bandType   BAND_LL/HL/LH/HH
     * @param decompLevel 1..numDecomp for HL/LH/HH; numDecomp for LL
     * @param bitDepth   per-component bit depth (e.g., 8)
     */
    private double dequantize(int coeff, QCDData qcd, int internalIdx,
                              int numDecomp, int bandType, int decompLevel, int bitDepth) {
        // Coefficients are two's-complement after assembleSubBands.
        int sign = coeff < 0 ? -1 : 1;
        int mag = Math.abs(coeff);
        if (mag == 0) return 0;
        if (qcd.style == 0) return sign * mag;

        int qcdIdx;
        if (qcd.style == 2) {
            // Expounded: per-band mapping.
            qcdIdx = (internalIdx == 3 * numDecomp) ? 0 : (internalIdx + 1);
        } else {
            // Derived: only entry [0] for LL_max; all others share via 2^d shift.
            qcdIdx = 0;
        }
        if (qcdIdx >= qcd.exponents.length) qcdIdx = qcd.exponents.length - 1;

        int eps = qcd.exponents[qcdIdx];
        int mu = qcd.mantissas[qcdIdx];
        // R_b = bitDepth + sub-band gain (LL=0, HL/LH=1, HH=2 — ISO 15444-1 Annex E).
        int gain = (bandType == BAND_LL) ? 0 : (bandType == BAND_HH ? 2 : 1);
        int rb = bitDepth + gain;
        if (qcd.style == 1) {
            // Derived: shift exponent by (numDecomp - decompLevel) — see E.1.1.
            eps = eps - (numDecomp - decompLevel);
        }
        double step = (1.0 + mu / 2048.0) * Math.pow(2.0, rb - eps);
        // Midpoint reconstruction (Annex E.1.1.1.2): the dequantized value
        // is the centre of the quantization interval, i.e., (mag + 0.5) * Δ_b
        // rather than the lower bound mag * Δ_b. This nudges every nonzero
        // coefficient up by half a step and improves the average error.
        return sign * (mag + 0.5) * step;
    }

    // ─── Diagnostics (gated by -Djpx.diag) ──────────────────────

    private void diagPrintSubBandStats(int comp, SubBandInfo[] bands, int[][] coeffs,
                                       QCDData qcd, int numDecomp) {
        String[] bandNames = {"LL", "HL", "LH", "HH"};
        for (SubBandInfo band : bands) {
            int[] cf = coeffs[band.globalIdx];
            if (cf == null || cf.length == 0) {
                System.out.println("[jpx.diag] c=" + comp + " band " + band.globalIdx
                        + " " + bandNames[band.bandType] + "_" + band.decompLevel
                        + " EMPTY (" + band.width + "x" + band.height + ")");
                continue;
            }
            long sumMag = 0;
            int maxMag = 0;
            int nonZero = 0;
            int neg = 0;
            for (int v : cf) {
                int mag = v & 0x7FFFFFFF;
                if (mag != 0) {
                    nonZero++;
                    sumMag += mag;
                    if (mag > maxMag) maxMag = mag;
                    if ((v & 0x80000000) != 0) neg++;
                }
            }
            int Mb = computeMb(band, qcd, numDecomp);
            double meanMag = nonZero > 0 ? sumMag / (double) nonZero : 0;
            System.out.println(String.format(
                    "[jpx.diag] c=%d band=%2d %s_%d  %dx%d  Mb=%d  nonZero=%d/%d (%.1f%%)  meanMag=%.1f  maxMag=%d  neg=%d",
                    comp, band.globalIdx, bandNames[band.bandType], band.decompLevel,
                    band.width, band.height, Mb,
                    nonZero, cf.length, 100.0 * nonZero / cf.length,
                    meanMag, maxMag, neg));
        }
    }

    private void diagPrintSpatial(String label, int comp, int[] data, int w, int h) {
        long sum = 0;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int v : data) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        System.out.println(String.format("[jpx.diag] %s c=%d %dx%d  min=%d max=%d mean=%.1f",
                label, comp, w, h, min, max, sum / (double) data.length));
    }

    private void diagDumpLLValues(int comp, SubBandInfo[] bands, int[][] coeffs) {
        SubBandInfo ll = bands[bands.length - 1];
        int[] cf = coeffs[ll.globalIdx];
        StringBuilder sb = new StringBuilder("[jpx.diag] LL c=" + comp + " values: ");
        for (int i = 0; i < Math.min(30, cf.length); i++) {
            int v = cf[i];
            int sign = (v & 0x80000000) != 0 ? -1 : 1;
            int mag = v & 0x7FFFFFFF;
            sb.append(String.format("%+d ", sign * mag));
        }
        System.out.println(sb.toString());
    }

    private void diagDumpLLThumbnail(int comp, SubBandInfo[] bands, int[][] coeffs,
                                     QCDData qcd, int numDecomp, int bitDepth) {
        try {
            SubBandInfo ll = bands[bands.length - 1];
            int[] cf = coeffs[ll.globalIdx];
            int w = ll.width, h = ll.height;
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                    w, h, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
            // Dequantize raw and render as 0..255 (clamping for visibility).
            double[] dq = new double[w * h];
            double dqMin = Double.POSITIVE_INFINITY, dqMax = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < w * h && i < cf.length; i++) {
                dq[i] = dequantize(cf[i], qcd, ll.globalIdx, numDecomp,
                        BAND_LL, numDecomp, bitDepth);
                if (dq[i] < dqMin) dqMin = dq[i];
                if (dq[i] > dqMax) dqMax = dq[i];
            }
            // Map [dqMin..dqMax] to [0..255] for visualization.
            for (int i = 0; i < w * h; i++) {
                int v = (int) Math.round(255.0 * (dq[i] - dqMin) / Math.max(1e-9, dqMax - dqMin));
                v = Math.max(0, Math.min(255, v));
                img.getRaster().setSample(i % w, i / w, 0, v);
            }
            java.nio.file.Path out = java.nio.file.Paths.get(System.getProperty("jpx.diag.dir",
                    System.getProperty("user.dir")));
            String[] names = {"Y", "Cb", "Cr"};
            String name = comp < names.length ? names[comp] : "c" + comp;
            javax.imageio.ImageIO.write(img, "PNG",
                    out.resolve("jpxdiag_LL_" + name + ".png").toFile());
            System.out.println(String.format("[jpx.diag] LL %s dequant: min=%.1f max=%.1f → wrote thumbnail",
                    name, dqMin, dqMax));
        } catch (Exception e) {
            System.out.println("[jpx.diag] LL dump failed: " + e.getMessage());
        }
    }

    private void diagPrintSpatial97(String label, int comp, double[] data, int w, int h) {
        double sum = 0;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double v : data) {
            sum += v;
            if (v < min) min = v;
            if (v > max) max = v;
        }
        System.out.println(String.format("[jpx.diag] %s c=%d %dx%d  min=%.1f max=%.1f mean=%.1f",
                label, comp, w, h, min, max, sum / data.length));
    }

    // ─── Coefficient → byte conversion ──────────────────────────

    private byte[] coeffsToBytes(int[] coeffs, int w, int h, int bitDepth, boolean signed) {
        byte[] result = new byte[w * h];
        int maxVal = (1 << bitDepth) - 1;
        int offset = signed ? 0 : (1 << (bitDepth - 1));
        for (int i = 0; i < w * h; i++) {
            int val = coeffs[i] + offset;
            result[i] = (byte) Math.max(0, Math.min(maxVal, val));
        }
        return result;
    }

    private byte[] doubleCoeffsToBytes(double[] coeffs, int w, int h,
                                       int bitDepth, boolean signed) {
        byte[] result = new byte[w * h];
        int maxVal = (1 << bitDepth) - 1;
        int offset = signed ? 0 : (1 << (bitDepth - 1));
        for (int i = 0; i < w * h; i++) {
            int val = (int) Math.round(coeffs[i]) + offset;
            result[i] = (byte) Math.max(0, Math.min(maxVal, val));
        }
        return result;
    }

    /** Interleaves component planes into packed pixels: R0,G0,B0,R1,G1,B1,… */
    private byte[] interleaveComponents(byte[][] comp, int w, int h, int numComp) {
        byte[] result = new byte[w * h * numComp];
        int idx = 0;
        for (int i = 0; i < w * h; i++) {
            for (int c = 0; c < numComp; c++) {
                result[idx++] = (i < comp[c].length) ? comp[c][i] : 0;
            }
        }
        return result;
    }

    // ─── PdfFilter interface ─────────────────────────────────────

    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        throw new IOException("JPXDecode encoding not implemented");
    }

    @Override
    public PdfName getName() {
        return PdfName.of("JPXDecode");
    }
}
