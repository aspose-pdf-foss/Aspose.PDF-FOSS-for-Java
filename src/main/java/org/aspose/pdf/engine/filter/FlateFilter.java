package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * FlateDecode filter (§7.4.4, ISO 32000-1:2008).
 * <p>
 * Compresses and decompresses data using the Deflate algorithm (RFC 1951).
 * This is the most commonly used filter in PDF — approximately 90% of all
 * streams use FlateDecode. Supports optional PNG/TIFF predictors via
 * {@link PredictorDecoder}.
 * </p>
 */
public final class FlateFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(FlateFilter.class.getName());

    private static final int BUFFER_SIZE = 8192;

    /**
     * Tiny buffer used only for last-resort prefix recovery. The JDK's
     * {@link Inflater} discards everything produced inside the single
     * {@code inflate(buf)} call that throws, so the buffer size is the upper
     * bound on bytes lost before a corruption point. 64 keeps that loss small
     * while staying large enough to make recovery of multi-KB prefixes cheap.
     */
    private static final int SMALL_BUFFER_SIZE = 64;

    /**
     * Creates a FlateFilter instance.
     */
    public FlateFilter() {
        // Stateless
    }

    /**
     * {@inheritDoc}
     * <p>
     * Decompresses Flate-encoded data. If the data lacks a zlib header (raw deflate),
     * a fallback with {@code nowrap=true} is attempted automatically.
     * </p>
     */
    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (encoded == null || encoded.length == 0) {
            return new byte[0];
        }

        byte[] inflated;
        try {
            inflated = inflate(encoded, false);
        } catch (DecodeSizeLimitException bomb) {
            // Decompression bomb: final — re-trying nowrap or running the
            // tail-truncation salvage would just re-inflate the bomb.
            throw bomb;
        } catch (IOException e) {
            // Fallback: some PDFs use raw deflate without zlib header
            LOG.fine("Flate decode failed with zlib header, retrying with nowrap=true");
            try {
                inflated = inflate(encoded, true);
            } catch (DecodeSizeLimitException bomb) {
                throw bomb;
            } catch (IOException e2) {
                byte[] salvaged = trySalvageInflate(encoded);
                if (salvaged != null) {
                    inflated = salvaged;
                } else {
                    // Report BOTH attempts — the zlib-header failure alone is
                    // often misleading (e.g. it hides a raw-deflate diagnostic).
                    IOException combined = new IOException(
                            "FlateDecode failed: zlib attempt: " + e.getMessage()
                            + "; raw attempt: " + e2.getMessage(), e);
                    combined.addSuppressed(e2);
                    throw combined;
                }
            }
        }

        // Apply predictor if specified
        byte[] result = applyPredictorDecode(inflated, params);

        final int inLen = encoded.length;
        final int outLen = result.length;
        LOG.fine(() -> "FlateDecode: " + inLen + " bytes -> " + outLen + " bytes");
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        if (decoded == null || decoded.length == 0) {
            return new byte[0];
        }

        // Apply predictor encoding if specified
        byte[] toCompress = applyPredictorEncode(decoded, params);

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(toCompress);
            deflater.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream(toCompress.length);
            byte[] buf = new byte[BUFFER_SIZE];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }

            LOG.fine(() -> "FlateEncode: " + decoded.length + " bytes → " + out.size() + " bytes");
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PdfName getName() {
        return PdfName.FLATE_DECODE;
    }

    // ---- Internal ----

    private static byte[] inflate(byte[] data, boolean nowrap) throws IOException {
        Inflater inflater = new Inflater(nowrap);
        long limit = DecodeLimits.maxDecodedBytes();
        try {
            inflater.setInput(data);
            // Initial capacity: data.length * 2 overflows int for raw streams
            // over 1 GB (corpus has 1.68 GB PDFs) and pre-allocates hundreds of
            // MB for big-but-legal streams before a single byte is produced.
            // Clamp — BAOS doubles on demand anyway.
            int initial = (int) Math.min(Math.max(data.length * 2L, 64), 1L << 20);
            ByteArrayOutputStream out = new ByteArrayOutputStream(initial);
            byte[] buf = new byte[BUFFER_SIZE];
            while (!inflater.finished()) {
                try {
                    int n = inflater.inflate(buf);
                    if (n == 0) {
                        if (inflater.needsInput()) {
                            break;
                        }
                        if (inflater.needsDictionary()) {
                            throw new IOException("FlateDecode: preset dictionary required but not supported");
                        }
                    }
                    // Account for the chunk BEFORE writing it: checking the
                    // size only on the next iteration lets the buffer overshoot
                    // the cap — and the OOM happens inside BAOS's growth
                    // copyOf, not at our check (observed in mass-corpus runs).
                    DecodeLimits.check((long) out.size() + n, limit, "FlateDecode");
                    out.write(buf, 0, n);
                } catch (DataFormatException e) {
                    // Every byte produced INSIDE the failing inflate() call is
                    // discarded by the JDK when it throws. With BUFFER_SIZE =
                    // 8192 that loses up to ~8 KB of good output wherever the
                    // corruption sits — including the common "bad Adler-32
                    // checksum after a fully valid deflate body" case, which
                    // silently truncated the tail (corpus 46075.pdf lost its
                    // last 7.8 KB of content ops). Retry from scratch with a
                    // tiny buffer so we surrender at most SMALL_BUFFER_SIZE
                    // bytes, and return whichever attempt recovered more.
                    byte[] prefix = inflateSmallBuf(data, nowrap);
                    if (prefix != null && prefix.length >= out.size() && prefix.length > 0) {
                        LOG.warning("FlateDecode: recovered " + prefix.length
                                + " bytes before corruption: " + e.getMessage());
                        return prefix;
                    }
                    if (out.size() > 0) {
                        LOG.warning("FlateDecode: partial decompression (" + out.size()
                                + " bytes recovered) due to: " + e.getMessage());
                        return out.toByteArray();
                    }
                    throw new IOException("FlateDecode: invalid deflate data: " + e.getMessage(), e);
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    /**
     * Mirrors {@link #inflate(byte[], boolean)} but with {@link #SMALL_BUFFER_SIZE}
     * and never throws: it returns whatever was decompressed before any
     * corruption, or {@code null} if not a single byte could be produced. Used
     * as a last resort when the fast 8 KB path lost all of its output to a
     * corruption that landed inside the first inflate() call.
     *
     * @param data   the raw (encoded) stream bytes
     * @param nowrap whether to inflate as raw deflate (no zlib header)
     * @return the recovered prefix, or {@code null} if nothing was recoverable
     */
    private static byte[] inflateSmallBuf(byte[] data, boolean nowrap) {
        Inflater inflater = new Inflater(nowrap);
        long limit = DecodeLimits.maxDecodedBytes();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
            byte[] buf = new byte[SMALL_BUFFER_SIZE];
            while (!inflater.finished()) {
                // Never-throws recovery path: stop (don't throw) at the cap.
                if (out.size() > limit) break;
                int n;
                try {
                    n = inflater.inflate(buf);
                } catch (DataFormatException stop) {
                    break;
                }
                if (n == 0) {
                    // No progress possible: out of input, or a preset dictionary
                    // is demanded (unsupported). Stop with what we have.
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                }
                out.write(buf, 0, n);
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } finally {
            inflater.end();
        }
    }

    private static byte[] trySalvageInflate(byte[] data) throws DecodeSizeLimitException {
        if (data == null || data.length < 32) {
            return null;
        }
        byte[] best = null;
        int minLength = Math.max(32, data.length - 65536);

        for (boolean nowrap : new boolean[] {false, true}) {
            for (int trim = 1; trim <= 4096 && data.length - trim >= minLength; trim *= 2) {
                byte[] candidate = tryInflatePrefix(data, data.length - trim, nowrap);
                if (candidate != null && candidate.length > 0
                        && (best == null || candidate.length > best.length)) {
                    best = candidate;
                }
            }
            if (best == null) {
                for (int len = data.length - 1; len >= minLength; len -= 256) {
                    byte[] candidate = tryInflatePrefix(data, len, nowrap);
                    if (candidate != null && candidate.length > 0
                            && (best == null || candidate.length > best.length)) {
                        best = candidate;
                    }
                }
            }
        }

        if (best != null) {
            LOG.warning("FlateDecode: salvaged " + best.length
                    + " bytes by truncating corrupt stream tail");
        }
        return best;
    }

    private static byte[] tryInflatePrefix(byte[] data, int length, boolean nowrap)
            throws DecodeSizeLimitException {
        if (length <= 0 || length > data.length) {
            return null;
        }
        byte[] prefix = new byte[length];
        System.arraycopy(data, 0, prefix, 0, length);
        try {
            return inflate(prefix, nowrap);
        } catch (IOException ignored) {
            // Includes DecodeSizeLimitException via the throws clause rethrow below.
            if (ignored instanceof DecodeSizeLimitException) {
                // Bomb prefixes are still bombs — stop the salvage search instead
                // of re-inflating up to the cap for every candidate truncation.
                throw (DecodeSizeLimitException) ignored;
            }
            return null;
        }
    }

    private static byte[] applyPredictorDecode(byte[] data, PdfDictionary params) {
        if (params == null) {
            return data;
        }
        int predictor = params.getInt(PdfName.of("Predictor"), 1);
        if (predictor <= 1) {
            return data;
        }
        int columns = params.getInt(PdfName.of("Columns"), 1);
        int colors = params.getInt(PdfName.of("Colors"), 1);
        int bpc = params.getInt(PdfName.of("BitsPerComponent"), 8);
        return PredictorDecoder.decode(data, predictor, columns, colors, bpc);
    }

    private static byte[] applyPredictorEncode(byte[] data, PdfDictionary params) {
        if (params == null) {
            return data;
        }
        int predictor = params.getInt(PdfName.of("Predictor"), 1);
        if (predictor <= 1) {
            return data;
        }
        int columns = params.getInt(PdfName.of("Columns"), 1);
        int colors = params.getInt(PdfName.of("Colors"), 1);
        int bpc = params.getInt(PdfName.of("BitsPerComponent"), 8);
        return PredictorDecoder.encode(data, predictor, columns, colors, bpc);
    }
}
