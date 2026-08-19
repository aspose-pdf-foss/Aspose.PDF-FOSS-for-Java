package org.aspose.pdf.engine.filter;

import java.util.logging.Logger;

/**
 * Adaptive binary arithmetic decoder for JBIG2 (MQ-coder).
 * Implements the arithmetic coding procedures specified in ISO/IEC 11544
 * (ITU T.88) Annex E (normative) and Annex F (software conventions).
 *
 * <p>Each coding context (CX) maintains:</p>
 * <ul>
 *   <li>I(CX): index into the Qe probability estimation table (0–46)</li>
 *   <li>MPS(CX): the more probable symbol (0 or 1)</li>
 * </ul>
 *
 * <p>The decoder maintains registers:</p>
 * <ul>
 *   <li>A — interval register (16-bit effective)</li>
 *   <li>C — code register (32-bit)</li>
 *   <li>CT — count of available bits in C</li>
 *   <li>BP — byte pointer into the compressed data</li>
 * </ul>
 *
 * @see JBIG2DecodeFilter
 */
public final class ArithmeticDecoder {

    private static final Logger LOG = Logger.getLogger(ArithmeticDecoder.class.getName());

    /**
     * Qe probability estimation table (Table E.1, 47 entries).
     * Each row: {Qe value, NMPS index, NLPS index, SWITCH flag}.
     */
    private static final int[][] QE_TABLE = {
        {0x5601, 1,  1,  1}, // 0
        {0x3401, 2,  6,  0}, // 1
        {0x1801, 3,  9,  0}, // 2
        {0x0AC1, 4,  12, 0}, // 3
        {0x0521, 5,  29, 0}, // 4
        {0x0221, 38, 33, 0}, // 5
        {0x5601, 7,  6,  1}, // 6
        {0x5401, 8,  14, 0}, // 7
        {0x4801, 9,  14, 0}, // 8
        {0x3801, 10, 14, 0}, // 9
        {0x3001, 11, 17, 0}, // 10
        {0x2401, 12, 18, 0}, // 11
        {0x1C01, 13, 20, 0}, // 12
        {0x1601, 29, 21, 0}, // 13
        {0x5601, 15, 14, 1}, // 14
        {0x5401, 16, 14, 0}, // 15
        {0x5101, 17, 15, 0}, // 16
        {0x4801, 18, 16, 0}, // 17
        {0x3801, 19, 17, 0}, // 18
        {0x3401, 20, 18, 0}, // 19
        {0x3001, 21, 19, 0}, // 20
        {0x2801, 22, 19, 0}, // 21
        {0x2401, 23, 20, 0}, // 22
        {0x2201, 24, 21, 0}, // 23
        {0x1C01, 25, 22, 0}, // 24
        {0x1801, 26, 23, 0}, // 25
        {0x1601, 27, 24, 0}, // 26
        {0x1401, 28, 25, 0}, // 27
        {0x1201, 29, 26, 0}, // 28
        {0x1101, 30, 27, 0}, // 29
        {0x0AC1, 31, 28, 0}, // 30
        {0x09C1, 32, 29, 0}, // 31
        {0x08A1, 33, 30, 0}, // 32
        {0x0521, 34, 31, 0}, // 33
        {0x0441, 35, 32, 0}, // 34
        {0x02A1, 36, 33, 0}, // 35
        {0x0221, 37, 34, 0}, // 36
        {0x0141, 38, 35, 0}, // 37
        {0x0111, 39, 36, 0}, // 38
        {0x0085, 40, 37, 0}, // 39
        {0x0049, 41, 38, 0}, // 40
        {0x0025, 42, 39, 0}, // 41
        {0x0015, 43, 40, 0}, // 42
        {0x0009, 44, 41, 0}, // 43
        {0x0005, 45, 42, 0}, // 44
        {0x0001, 45, 43, 0}, // 45
        {0x5601, 46, 46, 0}, // 46 (uniform context)
    };

    private final byte[] data;
    private int bp;       // byte pointer
    private int C;        // code register (32-bit)
    private int A;        // interval register (16-bit effective)
    private int CT;       // bit counter

    // Context state: parallel arrays for all contexts
    private final int[] contextI;   // I(CX) index into QE_TABLE
    private final int[] contextMPS; // MPS(CX) value (0 or 1)

    /**
     * Creates and initializes a new arithmetic decoder.
     *
     * @param data        the compressed data byte array
     * @param offset      the starting byte offset in the data
     * @param numContexts the number of coding contexts to allocate
     */
    public ArithmeticDecoder(byte[] data, int offset, int numContexts) {
        this.data = data;
        this.bp = offset;
        int safeContexts = Math.max(1, numContexts);
        this.contextI = new int[safeContexts];
        this.contextMPS = new int[safeContexts];
        initDec();
    }

    /**
     * INITDEC — initializes the decoder (T.88 §E.3.5, standard conventions:
     * {@code bp} points at the CURRENT byte B).
     */
    private void initDec() {
        C = currentByte() << 16;
        byteIn();
        C <<= 7;
        CT -= 7;
        A = 0x8000;
    }

    /** The byte at the current pointer, 0xFF past the end (§E.3.4 padding). */
    private int currentByte() {
        return bp < data.length ? data[bp] & 0xFF : 0xFF;
    }

    /**
     * Decodes one binary decision using the specified context.
     *
     * @param cx the context index (0 to numContexts-1)
     * @return the decoded symbol (0 or 1)
     */
    public int decode(int cx) {
        // T.88 §E.3.2 DECODE with §E.3.3 exchange procedures. Chigh is
        // compared against Qe (standard conventions — matching INITDEC/BYTEIN
        // above; mixing in the Annex-G complement forms desynchronises the
        // decoder within a few dozen decisions).
        int icx = contextI[cx];
        int qe = QE_TABLE[icx][0];
        A -= qe;
        int d;
        if (((C >>> 16) & 0xFFFF) < qe) {
            // LPS exchange (§E.3.3.2)
            if (A < qe) {
                d = contextMPS[cx];
                contextI[cx] = QE_TABLE[icx][1]; // NMPS
            } else {
                d = 1 - contextMPS[cx];
                if (QE_TABLE[icx][3] == 1) {
                    contextMPS[cx] = 1 - contextMPS[cx];
                }
                contextI[cx] = QE_TABLE[icx][2]; // NLPS
            }
            A = qe;
            renormD();
        } else {
            C -= qe << 16;
            if ((A & 0x8000) == 0) {
                // MPS exchange (§E.3.3.1)
                if (A < qe) {
                    d = 1 - contextMPS[cx];
                    if (QE_TABLE[icx][3] == 1) {
                        contextMPS[cx] = 1 - contextMPS[cx];
                    }
                    contextI[cx] = QE_TABLE[icx][2]; // NLPS
                } else {
                    d = contextMPS[cx];
                    contextI[cx] = QE_TABLE[icx][1]; // NMPS
                }
                renormD();
            } else {
                d = contextMPS[cx];
            }
        }
        return d;
    }

    /**
     * Decodes an integer value using the IAID (Integer Arithmetic Integer Decoder)
     * procedure specified in §A.3. Uses a binary tree of contexts.
     *
     * @param cxIAID   the base context index for IAID contexts
     * @param symCodeLen number of bits in the symbol code (SBSYMCODELEN)
     * @return the decoded integer value
     */
    public int decodeIAID(int cxIAID, int symCodeLen) {
        // §A.3: decode SYMCODELEN bits, building a context tree
        int prev = 1;
        for (int i = 0; i < symCodeLen; i++) {
            int bit = decode(cxIAID + prev);
            prev = (prev << 1) | bit;
        }
        // Remove the leading 1 bit
        return prev - (1 << symCodeLen);
    }

    /**
     * Decodes an integer using the Integer Arithmetic Decoding procedure (§A.2).
     * Returns the decoded integer, or Integer.MIN_VALUE if OOB (out of band).
     *
     * @param cxIA base context index (needs 512 contexts starting at cxIA)
     * @return decoded integer value, or Integer.MIN_VALUE for OOB
     */
    public int decodeInteger(int cxIA) {
        // §A.2: Integer arithmetic decoding procedure
        int prev = 1;

        // Decode S (sign) bit
        int s = decodeBitIA(cxIA, prev);
        prev = (prev << 1) | s;

        // Decode using the prefix/range structure from Table A.1
        int bit = decodeBitIA(cxIA, prev);
        prev = (prev << 1) | bit;

        int v;
        if (bit == 0) {
            // Range: 0..3 (2 bits)
            v = decodeBitsIA(cxIA, prev, 2);
        } else {
            bit = decodeBitIA(cxIA, prev);
            prev = (prev << 1) | bit;
            if (bit == 0) {
                // Range: 4..19 (4 bits) + 4
                v = decodeBitsIA(cxIA, prev, 4) + 4;
            } else {
                bit = decodeBitIA(cxIA, prev);
                prev = (prev << 1) | bit;
                if (bit == 0) {
                    // Range: 20..83 (6 bits) + 20
                    v = decodeBitsIA(cxIA, prev, 6) + 20;
                } else {
                    bit = decodeBitIA(cxIA, prev);
                    prev = (prev << 1) | bit;
                    if (bit == 0) {
                        // Range: 84..339 (8 bits) + 84
                        v = decodeBitsIA(cxIA, prev, 8) + 84;
                    } else {
                        bit = decodeBitIA(cxIA, prev);
                        prev = (prev << 1) | bit;
                        if (bit == 0) {
                            // Range: 340..4435 (12 bits) + 340
                            v = decodeBitsIA(cxIA, prev, 12) + 340;
                        } else {
                            // Range: 4436..(2^32-1) (32 bits) + 4436
                            v = decodeBitsIA(cxIA, prev, 32) + 4436;
                        }
                    }
                }
            }
        }

        if (s == 0) {
            return v;
        } else if (v == 0) {
            // OOB: S=1, V=0
            return Integer.MIN_VALUE;
        } else {
            return -v;
        }
    }

    /**
     * Decodes one bit for the integer arithmetic procedure using context tree.
     */
    private int decodeBitIA(int cxBase, int prev) {
        return decode(cxBase + prev);
    }

    /** §A.2 PREV update: once PREV reaches 256, keep the low 8 bits + bit 8. */
    private static int nextPrev(int prev, int bit) {
        int next = (prev << 1) | bit;
        return prev < 256 ? next : (next & 511) | 256;
    }

    /**
     * Decodes multiple bits for the integer arithmetic procedure,
     * threading PREV per §A.2.
     */
    private int decodeBitsIA(int cxBase, int prev, int numBits) {
        int value = 0;
        for (int i = 0; i < numBits; i++) {
            int bit = decodeBitIA(cxBase, prev);
            prev = nextPrev(prev, bit);
            value = (value << 1) | bit;
        }
        return value;
    }

    /**
     * RENORMD — renormalization of the decoder.
     * Annex E §E.3.3: shift A and C left until A >= 0x8000.
     */
    private void renormD() {
        do {
            if (CT == 0) byteIn();
            A <<= 1;
            C <<= 1;
            CT--;
        } while ((A & 0x8000) == 0);
    }

    /**
     * BYTEIN — reads one byte into the C register.
     * Annex E §E.3.4: handles 0xFF byte stuffing for marker detection.
     */
    private void byteIn() {
        // §E.3.4: the stuffing test applies to the CURRENT byte; the byte
        // consumed is the one after it.
        if (currentByte() == 0xFF) {
            int b1 = (bp + 1 < data.length) ? (data[bp + 1] & 0xFF) : 0xFF;
            if (b1 > 0x8F) {
                // Marker (or end of data): stay put, feed 1-bits.
                C += 0xFF00;
                CT = 8;
            } else {
                bp++;
                C += b1 << 9;
                CT = 7;
            }
        } else {
            bp++;
            C += currentByte() << 8;
            CT = 8;
        }
    }

    /**
     * Presets a context's adaptation state. JPEG 2000 Tier-1 (ISO 15444-1
     * Annex D — same MQ coder as T.88) starts the UNIFORM context at state 46
     * and the run-length context at state 3 instead of the all-zeros default.
     *
     * @param cx       the context index
     * @param stateIdx initial index into the Qe state table
     * @param mpsVal   initial MPS symbol (0 or 1)
     */
    public void setContext(int cx, int stateIdx, int mpsVal) {
        contextI[cx] = stateIdx;
        contextMPS[cx] = mpsVal;
    }

    /**
     * Resets a context to its default state (I=0, MPS=0).
     *
     * @param cx the context index to reset
     */
    public void resetContext(int cx) {
        contextI[cx] = 0;
        contextMPS[cx] = 0;
    }

    /**
     * Resets all contexts to their default state.
     */
    public void resetAllContexts() {
        java.util.Arrays.fill(contextI, 0);
        java.util.Arrays.fill(contextMPS, 0);
    }

    /**
     * Returns the current byte position in the data stream.
     *
     * @return the byte pointer offset
     */
    public int getBytePointer() {
        return bp;
    }
}
