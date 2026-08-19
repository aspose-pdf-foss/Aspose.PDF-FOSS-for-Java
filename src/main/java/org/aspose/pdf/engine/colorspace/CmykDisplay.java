package org.aspose.pdf.engine.colorspace;

/**
 * Display-oriented DeviceCMYK → sRGB conversion (ISO 32000-1:2008, §8.6.4.4).
 * <p>
 * Real viewers (Adobe Acrobat, Chrome, print drivers) render DeviceCMYK so that
 * it looks like ink on paper, which differs visibly from the naive algebraic
 * {@code (1-C)(1-K)} formula: print-neutral grays (C &gt; M ≈ Y) come out neutral
 * instead of greenish, and solid inks are muted (pure process cyan ≈
 * {@code rgb(0,174,239)}, rich black ≈ {@code rgb(35,31,32)}).
 * </p>
 * <p>
 * This class reproduces that look with a small <b>analytical ink-mixing
 * formula</b> — <b>no external data, no binary resource, no third-party
 * profile</b>. Each ink is given its standard published <i>process-color</i>
 * solid appearance in sRGB, and inks combine by a per-channel multiplicative
 * (subtractive, Beer–Lambert-style) transmittance:
 * </p>
 * <pre>
 *   channel = 255 · ∏<sub>ink∈{C,M,Y,K}</sub> ( 1 − amount<sub>ink</sub> · a<sub>ink,channel</sub> )
 *   where a<sub>ink,channel</sub> = 1 − solid<sub>ink,channel</sub> / 255
 * </pre>
 * <p>
 * The solid appearances are the well-known process primaries (the same values
 * cited in general references for the CMYK process colors), so no ink amount of
 * zero changes a channel and white maps to white exactly. By construction the
 * four solid primaries, paper white and registration black are reproduced
 * exactly; mixtures are a physically-motivated approximation.
 * </p>
 * <p>
 * NOTE: this is the <b>rendering</b> conversion. The public-API conversion
 * {@link DeviceCMYK#toRGBInt(double, double, double, double)} keeps the
 * algebraic formula — Aspose.PDF compatibility tests pin pure C/M/Y/K to exact
 * RGB primaries there.
 * </p>
 */
public final class CmykDisplay {

    // Standard process-color solid appearances in sRGB (R,G,B). These are the
    // commonly-published process primaries, not data sampled from any profile:
    //   process cyan (0,174,239), process magenta (236,0,140),
    //   process yellow (255,242,0), process/rich black ~(35,31,32).
    private static final double[] CYAN_SOLID    = {0,   174, 239};
    private static final double[] MAGENTA_SOLID = {236, 0,   140};
    private static final double[] YELLOW_SOLID  = {255, 242, 0};
    private static final double[] BLACK_SOLID   = {35,  31,  32};

    // Per-channel absorption of each ink at full strength: a = 1 - solid/255.
    private static final double[] A_C = absorption(CYAN_SOLID);
    private static final double[] A_M = absorption(MAGENTA_SOLID);
    private static final double[] A_Y = absorption(YELLOW_SOLID);
    private static final double[] A_K = absorption(BLACK_SOLID);

    private CmykDisplay() {}

    private static double[] absorption(double[] solid) {
        return new double[]{1 - solid[0] / 255.0, 1 - solid[1] / 255.0, 1 - solid[2] / 255.0};
    }

    /**
     * Converts CMYK components (each 0..1) to a packed ARGB int using the
     * analytical process-ink mixing model described in the class doc.
     *
     * @param c cyan (0..1)
     * @param m magenta (0..1)
     * @param y yellow (0..1)
     * @param k black (0..1)
     * @return packed ARGB int (alpha=0xFF)
     */
    public static int toRGBInt(double c, double m, double y, double k) {
        // Acrobat print-parity harness mode: use the measured print-path
        // lattice instead of the display model (see CmykPrintLut).
        if (CmykPrintLut.active()) return CmykPrintLut.toRGBInt(c, m, y, k);
        double cc = clamp01(c), mm = clamp01(m), yy = clamp01(y), kk = clamp01(k);
        int rgb = 0xFF000000;
        for (int ch = 0; ch < 3; ch++) {
            double t = (1 - cc * A_C[ch]) * (1 - mm * A_M[ch]) * (1 - yy * A_Y[ch]) * (1 - kk * A_K[ch]);
            int v = (int) Math.round(255 * t);
            if (v < 0) v = 0;
            else if (v > 255) v = 255;
            rgb |= v << (8 * (2 - ch));
        }
        return rgb;
    }

    /** Array variant for the {@link ColorSpaceBase#toRGBInt(double[])} pipeline. */
    public static int toRGBInt(double[] comps) {
        if (comps == null || comps.length < 4) return 0xFF000000;
        return toRGBInt(comps[0], comps[1], comps[2], comps[3]);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }
}
