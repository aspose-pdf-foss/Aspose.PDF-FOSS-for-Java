package org.aspose.pdf.engine.font.cff;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Subsets a Compact Font Format (CFF) program — Adobe Technical Note #5176 — by
 * dropping the outlines of glyphs that are not used.
 *
 * <p>Strategy (safe and offset-stable): the CharStrings of unused glyphs are
 * replaced with an empty {@code endchar} program, while <em>everything else is
 * preserved</em> — the Name/String/Global-Subr INDEXes, the charset, the Private
 * DICT and its Local Subrs. Because glyph ids, the charset (gid→SID/CID) and all
 * subroutines are untouched, the result is a drop-in replacement: no glyph
 * renumbering, no subroutine tracing, no risk of removing a routine a kept glyph
 * depends on. The size win comes from discarding the unused outlines, which
 * dominate a large font's byte count.</p>
 *
 * <p>The rebuilt font places the new CharStrings INDEX last and re-encodes the
 * Top DICT with fixed 5-byte integer offsets (TN#5176 §4, operand type
 * {@code b0 == 29}), so the Top DICT size is constant and the four absolute
 * offsets it holds (charset, encoding, CharStrings, Private) can be patched after
 * the layout is known.</p>
 *
 * <p>CID-keyed fonts (Top DICT carries {@code /ROS}, or an {@code FDArray}/
 * {@code FDSelect}) are returned unchanged for now — their per-font Private DICTs
 * add another layer of offsets to patch. The caller keeps the original program as
 * a safe fallback whenever subsetting cannot be performed.</p>
 */
public final class CFFSubsetter {

    private static final Logger LOG = Logger.getLogger(CFFSubsetter.class.getName());

    private final byte[] data;

    private CFFSubsetter(byte[] data) {
        this.data = data;
    }

    /**
     * Rebuilds {@code cff} keeping only the outlines of {@code usedGids} (plus
     * {@code .notdef}, glyph id 0, which is always retained).
     *
     * @param cff      the original CFF program
     * @param usedGids the glyph ids whose outlines must survive
     * @return the subsetted program, or the original {@code cff} when it cannot be
     *         safely rebuilt (CID-keyed font, malformed input, etc.)
     */
    public static byte[] subset(byte[] cff, Set<Integer> usedGids) {
        if (cff == null) {
            return null;
        }
        try {
            byte[] out = new CFFSubsetter(cff).run(usedGids);
            return out != null ? out : cff;
        } catch (RuntimeException | IOException e) {
            LOG.fine(() -> "CFF subset aborted: " + e.getMessage());
            return cff;
        }
    }

    // ── Top DICT model (only the entries we must read) ──
    private int charsetOffset = 0;
    private int encodingOffset = 0;
    private int charStringsOffset = 0;
    private int privateSize = 0;
    private int privateOffset = 0;
    private boolean hasRos = false;
    private boolean hasFd = false;

    private byte[] run(Set<Integer> usedGids) throws IOException {
        int pos = 0;
        // -- Header --
        int major = u8(pos);
        int hdrSize = u8(pos + 2);
        if (major != 1 || hdrSize < 4) {
            return null;
        }
        pos = hdrSize;

        int[] nameIndex = indexSpan(pos);          // [start,end)
        pos = nameIndex[1];
        int[] topDictIndex = indexSpan(pos);
        pos = topDictIndex[1];
        int[] stringIndex = indexSpan(pos);
        pos = stringIndex[1];
        int[] globalSubrIndex = indexSpan(pos);
        pos = globalSubrIndex[1];

        // Top DICT (single entry) — parse the operators we care about.
        int[] tdEntry = indexEntrySpan(topDictIndex, 0);
        parseTopDict(tdEntry[0], tdEntry[1]);

        if (hasRos || hasFd) {
            return null; // CID-keyed: not handled in this version
        }
        if (charStringsOffset <= 0 || privateOffset <= 0) {
            return null;
        }
        if (encodingOffset > 1) {
            // Custom CFF encoding — rare in embedded PDF fonts; skip to stay safe.
            return null;
        }

        // -- CharStrings INDEX --
        int[] csIndex = indexSpan(charStringsOffset);
        int numGlyphs = u16(charStringsOffset);
        if (numGlyphs <= 0 || numGlyphs > 0xFFFF) {
            return null;
        }

        // -- charset byte span (only custom charsets, offset > 2, carry bytes) --
        int charsetLen = (charsetOffset > 2) ? charsetByteLength(charsetOffset, numGlyphs) : 0;

        // -- Private DICT (+ Local Subrs) contiguous span --
        int subrsRel = privateSubrsRelOffset(privateOffset, privateSize);
        int privateSpanEnd;
        if (subrsRel > 0) {
            int localSubrsStart = privateOffset + subrsRel;
            int localSubrsEnd = indexSpan(localSubrsStart)[1];
            privateSpanEnd = Math.max(privateOffset + privateSize, localSubrsEnd);
        } else {
            privateSpanEnd = privateOffset + privateSize;
        }

        // -- Build the new CharStrings INDEX (unused glyphs → bare endchar) --
        byte[][] entries = new byte[numGlyphs][];
        byte[] endchar = new byte[]{0x0E};
        for (int gid = 0; gid < numGlyphs; gid++) {
            int[] span = indexEntrySpan(csIndex, gid);
            boolean keep = gid == 0 || usedGids.contains(gid);
            entries[gid] = keep ? copy(span[0], span[1]) : endchar;
        }
        byte[] newCharStrings = encodeIndex(entries);

        // -- Re-encode the Top DICT with fixed 5-byte offset placeholders --
        TopDictRewrite td = rewriteTopDict(tdEntry[0], tdEntry[1], charsetLen > 0);
        byte[] topDictIndexBytes = encodeIndex(new byte[][]{td.bytes});
        int topDictHeaderSize = indexHeaderSize(td.bytes.length);

        // -- Lay out the output, recording section starts --
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        write(out, 0, hdrSize);                                  // header
        writeSpan(out, nameIndex);                               // Name INDEX
        int topDictIndexStart = out.size();
        out.write(topDictIndexBytes, 0, topDictIndexBytes.length); // Top DICT INDEX
        writeSpan(out, stringIndex);                             // String INDEX
        writeSpan(out, globalSubrIndex);                         // Global Subr INDEX

        int charsetStart = out.size();
        if (charsetLen > 0) {
            out.write(data, charsetOffset, charsetLen);          // charset (verbatim)
        }
        int privateStart = out.size();
        out.write(data, privateOffset, privateSpanEnd - privateOffset); // Private DICT (+ Local Subrs)
        int charStringsStart = out.size();
        out.write(newCharStrings, 0, newCharStrings.length);     // CharStrings INDEX (last)

        byte[] result = out.toByteArray();

        // -- Patch the Top DICT absolute offsets (fixed 5-byte ints) --
        int tdDataStart = topDictIndexStart + topDictHeaderSize;
        patch5(result, tdDataStart + td.charStringsPatch, charStringsStart);
        patch5(result, tdDataStart + td.privatePatch, privateStart);
        if (charsetLen > 0 && td.charsetPatch >= 0) {
            patch5(result, tdDataStart + td.charsetPatch, charsetStart);
        }
        return result;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Top DICT parsing / re-encoding
    // ────────────────────────────────────────────────────────────────────────

    private void parseTopDict(int start, int end) {
        int p = start;
        long[] operands = new long[48];
        int nOperands = 0;
        while (p < end) {
            int b0 = u8(p);
            if (b0 <= 21) {
                int op = b0;
                if (b0 == 12) {
                    op = 1200 + u8(p + 1);
                    p += 2;
                } else {
                    p++;
                }
                switch (op) {
                    case 15: if (nOperands > 0) charsetOffset = (int) operands[0]; break;
                    case 16: if (nOperands > 0) encodingOffset = (int) operands[0]; break;
                    case 17: if (nOperands > 0) charStringsOffset = (int) operands[0]; break;
                    case 18:
                        if (nOperands >= 2) {
                            privateSize = (int) operands[0];
                            privateOffset = (int) operands[1];
                        }
                        break;
                    case 1230: hasRos = true; break;        // /ROS → CID
                    case 1236: hasFd = true; break;         // FDArray
                    case 1237: hasFd = true; break;         // FDSelect
                    default: break;
                }
                nOperands = 0;
            } else {
                int[] span = dictOperandSpan(p);
                operands[nOperands++] = span[1]; // value
                p += span[0];                    // length
            }
        }
    }

    private static final class TopDictRewrite {
        byte[] bytes;
        int charsetPatch = -1;
        int encodingPatch = -1;
        int charStringsPatch = -1;
        int privatePatch = -1;
    }

    /**
     * Re-emits the Top DICT, copying every operator verbatim except charset(15),
     * CharStrings(17) and the Private(18) offset, which become fixed 5-byte integer
     * placeholders (patched after layout). Encoding(16) is passed through untouched
     * (we only reach here for predefined encodings, offset ≤ 1).
     */
    private TopDictRewrite rewriteTopDict(int start, int end, boolean haveCharset) {
        TopDictRewrite r = new TopDictRewrite();
        ByteArrayOutputStream td = new ByteArrayOutputStream();
        int p = start;
        int operandRunStart = p;
        while (p < end) {
            int b0 = u8(p);
            if (b0 <= 21) {
                int op = b0;
                int opLen = 1;
                if (b0 == 12) {
                    op = 1200 + u8(p + 1);
                    opLen = 2;
                }
                if (op == 15 && haveCharset) {
                    r.charsetPatch = td.size();
                    write5(td, 0);
                    writeBytes(td, p, opLen);
                } else if (op == 17) {
                    r.charStringsPatch = td.size();
                    write5(td, 0);
                    writeBytes(td, p, opLen);
                } else if (op == 18) {
                    // Operands were "size offset"; keep size, replace offset.
                    int[] sizeSpan = dictOperandSpan(operandRunStart);
                    writeBytes(td, operandRunStart, sizeSpan[0]); // size operand verbatim
                    r.privatePatch = td.size();
                    write5(td, 0);                                // new offset placeholder
                    writeBytes(td, p, opLen);                     // operator 18
                } else {
                    // Copy the whole operand run + operator verbatim.
                    writeBytes(td, operandRunStart, p - operandRunStart);
                    writeBytes(td, p, opLen);
                }
                p += opLen;
                operandRunStart = p;
            } else {
                p += dictOperandSpan(p)[0];
            }
        }
        r.bytes = td.toByteArray();
        return r;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  INDEX helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Returns {start, endExclusive} of the INDEX beginning at {@code pos}. */
    private int[] indexSpan(int pos) throws IOException {
        int count = u16(pos);
        if (count == 0) {
            return new int[]{pos, pos + 2};
        }
        int offSize = u8(pos + 2);
        if (offSize < 1 || offSize > 4) {
            throw new IOException("CFF: bad offSize " + offSize);
        }
        int offArrStart = pos + 3;
        int lastOff = off(offArrStart + count * offSize, offSize);
        int dataStart = offArrStart + (count + 1) * offSize;
        int endPos = dataStart + lastOff - 1;
        return new int[]{pos, endPos};
    }

    /** Returns {entryStart, entryEndExclusive} of entry {@code i} in an INDEX span. */
    private int[] indexEntrySpan(int[] indexSpan, int i) throws IOException {
        int pos = indexSpan[0];
        int count = u16(pos);
        int offSize = u8(pos + 2);
        int offArrStart = pos + 3;
        int dataStart = offArrStart + (count + 1) * offSize;
        int o0 = off(offArrStart + i * offSize, offSize);
        int o1 = off(offArrStart + (i + 1) * offSize, offSize);
        return new int[]{dataStart + o0 - 1, dataStart + o1 - 1};
    }

    private static int indexHeaderSize(int singleEntryLen) {
        int offSize = offSizeFor(singleEntryLen + 1);
        return 2 + 1 + (2) * offSize; // count(2) + offSize(1) + 2 offsets
    }

    /** Encodes a CFF INDEX from the given entries. */
    private static byte[] encodeIndex(byte[][] entries) {
        int count = entries.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU16(out, count);
        if (count == 0) {
            return out.toByteArray();
        }
        int total = 1;
        for (byte[] e : entries) {
            total += e.length;
        }
        int offSize = offSizeFor(total);
        out.write(offSize);
        int acc = 1;
        writeOff(out, acc, offSize);
        for (byte[] e : entries) {
            acc += e.length;
            writeOff(out, acc, offSize);
        }
        for (byte[] e : entries) {
            out.write(e, 0, e.length);
        }
        return out.toByteArray();
    }

    private static int offSizeFor(int maxOffset) {
        if (maxOffset <= 0xFF) return 1;
        if (maxOffset <= 0xFFFF) return 2;
        if (maxOffset <= 0xFFFFFF) return 3;
        return 4;
    }

    // ────────────────────────────────────────────────────────────────────────
    //  charset / Private DICT measurement
    // ────────────────────────────────────────────────────────────────────────

    private int charsetByteLength(int offset, int numGlyphs) throws IOException {
        int p = offset;
        int format = u8(p++);
        if (format == 0) {
            return 1 + 2 * (numGlyphs - 1);
        } else if (format == 1 || format == 2) {
            int gid = 1;
            while (gid < numGlyphs) {
                p += 2; // first SID
                int nLeft = (format == 1) ? u8(p) : u16(p);
                p += (format == 1) ? 1 : 2;
                gid += nLeft + 1;
            }
            return p - offset;
        }
        throw new IOException("CFF: unknown charset format " + format);
    }

    /** Returns the Subrs(19) operand (relative offset) in a Private DICT, or 0 if absent. */
    private int privateSubrsRelOffset(int offset, int size) {
        int p = offset;
        int end = offset + size;
        long[] operands = new long[48];
        int n = 0;
        while (p < end) {
            int b0 = u8(p);
            if (b0 <= 21) {
                int op = b0;
                if (b0 == 12) {
                    op = 1200 + u8(p + 1);
                    p += 2;
                } else {
                    p++;
                }
                if (op == 19 && n > 0) {
                    return (int) operands[0];
                }
                n = 0;
            } else {
                int[] span = dictOperandSpan(p);
                operands[n++] = span[1];
                p += span[0];
            }
        }
        return 0;
    }

    /** Parses one DICT operand at {@code p}. Returns {byteLength, value}. */
    private int[] dictOperandSpan(int p) {
        int b0 = u8(p);
        if (b0 >= 32 && b0 <= 246) {
            return new int[]{1, b0 - 139};
        } else if (b0 >= 247 && b0 <= 250) {
            return new int[]{2, (b0 - 247) * 256 + u8(p + 1) + 108};
        } else if (b0 >= 251 && b0 <= 254) {
            return new int[]{2, -(b0 - 251) * 256 - u8(p + 1) - 108};
        } else if (b0 == 28) {
            int v = (u8(p + 1) << 8) | u8(p + 2);
            if ((v & 0x8000) != 0) v |= 0xFFFF0000;
            return new int[]{3, v};
        } else if (b0 == 29) {
            int v = (u8(p + 1) << 24) | (u8(p + 2) << 16) | (u8(p + 3) << 8) | u8(p + 4);
            return new int[]{5, v};
        } else if (b0 == 30) {
            int q = p + 1;
            while (q < data.length) {
                int b = u8(q++);
                if ((b & 0x0F) == 0x0F || (b & 0xF0) == 0xF0) break;
            }
            return new int[]{q - p, 0};
        }
        return new int[]{1, 0};
    }

    // ────────────────────────────────────────────────────────────────────────
    //  Low-level byte I/O
    // ────────────────────────────────────────────────────────────────────────

    private int u8(int p) {
        return data[p] & 0xFF;
    }

    private int u16(int p) {
        return ((data[p] & 0xFF) << 8) | (data[p + 1] & 0xFF);
    }

    private int off(int p, int size) {
        int v = 0;
        for (int i = 0; i < size; i++) {
            v = (v << 8) | (data[p + i] & 0xFF);
        }
        return v;
    }

    private byte[] copy(int start, int end) {
        byte[] b = new byte[Math.max(0, end - start)];
        System.arraycopy(data, start, b, 0, b.length);
        return b;
    }

    private void write(ByteArrayOutputStream out, int start, int len) {
        out.write(data, start, len);
    }

    private void writeSpan(ByteArrayOutputStream out, int[] span) {
        out.write(data, span[0], span[1] - span[0]);
    }

    private void writeBytes(ByteArrayOutputStream out, int start, int len) {
        out.write(data, start, len);
    }

    private static void writeU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeOff(ByteArrayOutputStream out, int v, int size) {
        for (int i = size - 1; i >= 0; i--) {
            out.write((v >> (8 * i)) & 0xFF);
        }
    }

    /** Writes a fixed 5-byte DICT integer operand (b0 == 29). */
    private static void write5(ByteArrayOutputStream out, int v) {
        out.write(29);
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    /** Patches a fixed 5-byte DICT integer operand in-place (skips the 0x1D tag). */
    private static void patch5(byte[] buf, int pos, int v) {
        buf[pos + 1] = (byte) ((v >> 24) & 0xFF);
        buf[pos + 2] = (byte) ((v >> 16) & 0xFF);
        buf[pos + 3] = (byte) ((v >> 8) & 0xFF);
        buf[pos + 4] = (byte) (v & 0xFF);
    }
}
