package org.aspose.pdf.engine.filter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity tests for the 1D inverse DWT lifting routines used by
 * {@link JPXDecodeFilter}. The tests construct sub-band coefficient inputs
 * with known properties (e.g., LL only, constant) and assert that the
 * inverse lifting produces the expected reconstruction.
 */
public class JPXDecodeFilterDWTTest {

    private static final double EPS_97 = 1e-3;
    private static final double K_97   = 1.230174104914001;

    /**
     * 5/3 inverse DWT on a buffer whose low half is constant K and high half
     * is zero must reconstruct the constant K everywhere. (5/3 has no extra
     * scaling — lifting alone is exact for constant input.)
     */
    @Test
    public void dwt53_constantLow_zeroHigh_reconstructsConstant() {
        int len = 16;
        int halfLen = (len + 1) / 2;
        int[] buf = new int[len];
        for (int i = 0; i < halfLen; i++) buf[i] = 1000;       // low (LL)
        // high (HL) already zero

        JPXDecodeFilter.inverseDWT53_1D(buf, 0, len);

        for (int i = 0; i < len; i++) {
            assertEquals(1000, buf[i], "5/3 IDWT of constant-low should reproduce constant; idx " + i);
        }
    }

    /**
     * 9/7 inverse DWT: the ISO 15444-1 F.4.8.1 forward transform of a
     * constant-1 signal yields low = 1.0 (the lifting's DC gain is cancelled
     * by the final ×1/K low-band scaling) and high = 0, so the inverse fed
     * (1 | 0) must reconstruct 1.0 everywhere. Pins the STANDARD scaling
     * convention (inverse: low ×K, high ×1/K) that real encoders (OpenJPEG,
     * Kakadu) produce — verified against OpenJPEG-encoded oracle codestreams.
     * The previous K²-based scaling reconstructed ~2× contrast on every real
     * 9/7 stream.
     */
    @Test
    public void dwt97_constantLow_zeroHigh_reconstructsConstant() {
        int len = 16;
        int halfLen = (len + 1) / 2;
        double[] buf = new double[len];
        for (int i = 0; i < halfLen; i++) buf[i] = 1.0; // low

        JPXDecodeFilter.inverseDWT97_1D(buf, 0, len);

        for (int i = 0; i < len; i++) {
            assertEquals(1.0, buf[i], EPS_97,
                    "9/7 IDWT of (1, ..., 1 | 0,...,0) should reconstruct 1; idx " + i);
        }
    }

    /**
     * Pins the no-input → no-output behaviour. Zero sub-bands must reconstruct
     * zero exactly (no scaling can manufacture a signal from a zero input).
     */
    @Test
    public void dwt97_zeroLow_zeroHigh_isZero() {
        double[] buf = new double[16];
        JPXDecodeFilter.inverseDWT97_1D(buf, 0, 16);
        for (int i = 0; i < 16; i++) {
            assertEquals(0.0, buf[i], EPS_97, "zero in → zero out, idx " + i);
        }
    }

    /**
     * Linearity of the inverse: feeding low = K must reconstruct K everywhere
     * (the transform is linear, so scaling the input scales the output).
     */
    @Test
    public void dwt97_constantLow_K_check_alternate() {
        int len = 16;
        int halfLen = (len + 1) / 2;
        double[] buf = new double[len];
        for (int i = 0; i < halfLen; i++) buf[i] = K_97;       // low = K

        JPXDecodeFilter.inverseDWT97_1D(buf, 0, len);

        for (int i = 0; i < len; i++) {
            assertEquals(K_97, buf[i], EPS_97,
                    "9/7 IDWT of (K, ..., K | 0,...,0) should reconstruct K; idx " + i);
        }
    }

    /**
     * Round-trip check: a manual 9/7 forward DWT followed by our inverse DWT
     * should recover the original signal. We use a small test signal and
     * implement the forward lifting inline.
     */
    @Test
    public void dwt97_roundTrip_smallSignal() {
        double[] orig = {10, 20, 30, 40, 50, 60, 70, 80};
        double[] x = orig.clone();
        int len = x.length;

        // Forward 9/7 lifting (canonical ISO/IEC 15444-1 Annex F.4.6 order):
        // 1) d_n -= α (s_n + s_{n+1})  on odd indices
        // 2) s_n -= β (d_{n-1} + d_n)  on even
        // 3) d_n -= γ (s_n + s_{n+1})  on odd
        // 4) s_n -= δ (d_{n-1} + d_n)  on even
        // 5) scale: even *= 1/K, odd *= K
        final double A = -1.586134342, B = -0.052980118;
        final double G =  0.882911075, D =  0.443506852;

        // mirror boundary helpers
        java.util.function.IntUnaryOperator mirror = i -> i < 0 ? -i : (i >= len ? 2*(len-1)-i : i);

        for (int i = 1; i < len; i += 2) {
            int li = mirror.applyAsInt(i - 1);
            int ri = mirror.applyAsInt(i + 1);
            x[i] += A * (x[li] + x[ri]);
        }
        for (int i = 0; i < len; i += 2) {
            int li = mirror.applyAsInt(i - 1);
            int ri = mirror.applyAsInt(i + 1);
            x[i] += B * (x[li] + x[ri]);
        }
        for (int i = 1; i < len; i += 2) {
            int li = mirror.applyAsInt(i - 1);
            int ri = mirror.applyAsInt(i + 1);
            x[i] += G * (x[li] + x[ri]);
        }
        for (int i = 0; i < len; i += 2) {
            int li = mirror.applyAsInt(i - 1);
            int ri = mirror.applyAsInt(i + 1);
            x[i] += D * (x[li] + x[ri]);
        }
        // Scaling per ISO F.4.8.1: even (low) /= K, odd (high) *= K — the
        // convention real encoders write; our inverse undoes it with
        // low ×K, high ×1/K.
        double[] sc1 = x.clone();
        for (int i = 0; i < len; i += 2) sc1[i] /= K_97;
        for (int i = 1; i < len; i += 2) sc1[i] *= K_97;

        // De-interleave into the [low ... | high ...] layout our IDWT expects.
        int halfLen = (len + 1) / 2;
        double[] sub1 = new double[len];
        for (int n = 0; n < halfLen; n++) sub1[n] = sc1[2 * n];
        for (int n = 0; n < len - halfLen; n++) sub1[halfLen + n] = sc1[2 * n + 1];

        double[] rec1 = sub1.clone();
        JPXDecodeFilter.inverseDWT97_1D(rec1, 0, len);

        for (int i = 0; i < len; i++) {
            assertEquals(orig[i], rec1[i], 1e-3,
                    "9/7 forward(ISO scaling) → inverse should round-trip; idx " + i);
        }
    }
}
