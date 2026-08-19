package org.aspose.pdf.engine.colorspace;

/**
 * Acrobat <b>print-path</b> untagged-DeviceRGB shift, active only under
 * {@code -Drender.acrobatPrintParity=true} (the harness mode comparing our
 * raster against Adobe Acrobat "print to PDF" golds) and only for pages that
 * contain transparency.
 * <p>
 * Measured behaviour (visual-mass experiments 99994/99997, 2026-07):
 * Acrobat's silent print reproduces untagged DeviceRGB values <b>identically</b>
 * on fully opaque pages, but as soon as the page uses any transparency feature
 * (an ExtGState alpha &lt; 1 was sufficient in the experiment) the whole page
 * goes through Acrobat's transparency flattener, which treats untagged RGB as
 * the AdobeRGB&nbsp;(1998) working space and converts the flattened result to
 * the printer target (sRGB). The observed mapping is exactly
 * AdobeRGB&nbsp;&rarr;&nbsp;sRGB: decode with gamma 256/117&nbsp;=&nbsp;2.19921875,
 * AdobeRGB RGB&rarr;XYZ matrix, XYZ&rarr;sRGB matrix, sRGB encode. Validated
 * against 781 printed lattice/validation patches: mean channel error 0.35/255
 * (identity: 10.5/255). Only the standard published colorimetry constants are
 * embedded — no ICC profile data.
 * </p>
 * <p>
 * The transform preserves neutrals (both spaces are D65; the tone curves agree
 * within 1/255), so DeviceGray content is deliberately left untouched. Tagged
 * spaces (ICCBased, CalRGB, Lab) and DeviceCMYK (measured separately by
 * {@link CmykPrintLut}) are not routed through this class.
 * </p>
 * <p>
 * Activation is a thread-local flag set by the page renderer after it detects
 * transparency on the page being rendered; the public-API image extraction
 * paths never see the flag. Kill switch:
 * {@code -Drender.printParityRgbShift=false}.
 * </p>
 */
public final class RgbPrintShift {

    private RgbPrintShift() {}

    /** Set for the duration of a print-parity render of a transparency page. */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Hard thread-local override for rasterising GOLD (already-printed) PDFs:
     * their content already carries Acrobat's output colors, and MS Print to
     * PDF re-encodes stencilled scans as /SMask images — the transparency
     * detector would fire on the GOLD and double-convert it (corpus 48471:
     * the gold panel showed a "shift" the print never had). Comparators set
     * this around gold rendering.
     */
    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * Suppresses (or re-enables) the shift for the current thread regardless
     * of per-page activation — see {@link #SUPPRESSED}.
     *
     * @param on true to suppress
     */
    public static void setSuppressed(boolean on) {
        SUPPRESSED.set(on);
    }

    /** AdobeRGB (1998) decoding gamma: 2 + 51/256. */
    private static final double ARGB_GAMMA = 563.0 / 256.0;

    /**
     * Combined linear AdobeRGB &rarr; linear sRGB matrix:
     * (XYZ&rarr;sRGB)&nbsp;&middot;&nbsp;(AdobeRGB&rarr;XYZ), both D65.
     */
    private static final double[][] K;

    /** Decode table: v/255 through the AdobeRGB gamma. */
    private static final double[] DECODE = new double[256];

    /** sRGB encode table over linear [0,1], 4096 steps + guard, lerp'd. */
    private static final int ENC_N = 4096;
    private static final double[] ENCODE = new double[ENC_N + 2];

    /** Inverse of {@link #K}: linear sRGB &rarr; linear AdobeRGB. */
    private static final double[][] KINV;

    /** Decode table: v/255 through the sRGB curve (for {@link #unshift}). */
    private static final double[] SRGB_DECODE = new double[256];

    /** AdobeRGB encode table over linear [0,1] (gamma 1/2.19921875), lerp'd. */
    private static final double[] ARGB_ENCODE = new double[ENC_N + 2];

    static {
        double[][] argbToXyz = {
                {0.57667, 0.18556, 0.18823},
                {0.29734, 0.62736, 0.07529},
                {0.02703, 0.07069, 0.99134}};
        double[][] xyzToSrgb = {
                {3.2406, -1.5372, -0.4986},
                {-0.9689, 1.8758, 0.0415},
                {0.0557, -0.2040, 1.0570}};
        K = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                double s = 0;
                for (int m = 0; m < 3; m++) s += xyzToSrgb[i][m] * argbToXyz[m][j];
                K[i][j] = s;
            }
        }
        for (int v = 0; v < 256; v++) {
            DECODE[v] = Math.pow(v / 255.0, ARGB_GAMMA);
        }
        for (int i = 0; i <= ENC_N + 1; i++) {
            double l = Math.min(1.0, i / (double) ENC_N);
            ENCODE[i] = l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
            ARGB_ENCODE[i] = Math.pow(l, 1 / ARGB_GAMMA);
        }
        // 3x3 inverse of K (for the working-space blend round trip).
        double det = K[0][0] * (K[1][1] * K[2][2] - K[1][2] * K[2][1])
                - K[0][1] * (K[1][0] * K[2][2] - K[1][2] * K[2][0])
                + K[0][2] * (K[1][0] * K[2][1] - K[1][1] * K[2][0]);
        KINV = new double[3][3];
        KINV[0][0] = (K[1][1] * K[2][2] - K[1][2] * K[2][1]) / det;
        KINV[0][1] = (K[0][2] * K[2][1] - K[0][1] * K[2][2]) / det;
        KINV[0][2] = (K[0][1] * K[1][2] - K[0][2] * K[1][1]) / det;
        KINV[1][0] = (K[1][2] * K[2][0] - K[1][0] * K[2][2]) / det;
        KINV[1][1] = (K[0][0] * K[2][2] - K[0][2] * K[2][0]) / det;
        KINV[1][2] = (K[0][2] * K[1][0] - K[0][0] * K[1][2]) / det;
        KINV[2][0] = (K[1][0] * K[2][1] - K[1][1] * K[2][0]) / det;
        KINV[2][1] = (K[0][1] * K[2][0] - K[0][0] * K[2][1]) / det;
        KINV[2][2] = (K[0][0] * K[1][1] - K[0][1] * K[1][0]) / det;
        for (int v = 0; v < 256; v++) {
            double s = v / 255.0;
            SRGB_DECODE[v] = s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
        }
    }

    /**
     * Marks the current thread's render as needing (or not) the print shift.
     * Only the page renderer calls this, and only in print-parity mode.
     *
     * @param on true when the page being rendered contains transparency
     */
    public static void setActive(boolean on) {
        ACTIVE.set(on && enabled());
    }

    /** Clears the thread-local flag (render finished). */
    public static void clear() {
        ACTIVE.remove();
    }

    /**
     * Whether untagged DeviceRGB values must be shifted right now.
     *
     * @return true when the current thread renders a transparency page in
     *         print-parity mode and the shift has not been disabled via
     *         {@code render.printParityRgbShift=false}
     */
    public static boolean active() {
        return ACTIVE.get() && !SUPPRESSED.get();
    }

    /** Whether the feature is enabled by system properties at all. */
    public static boolean enabled() {
        return Boolean.getBoolean("render.acrobatPrintParity")
                && !"false".equals(System.getProperty("render.printParityRgbShift"));
    }

    /**
     * Applies AdobeRGB&nbsp;&rarr;&nbsp;sRGB to a packed RGB value,
     * preserving the alpha byte. Out-of-gamut results are clipped.
     *
     * @param argb packed ARGB (or RGB) pixel
     * @return the shifted pixel with the original alpha byte
     */
    public static int shift(int argb) {
        double r = DECODE[(argb >> 16) & 0xFF];
        double g = DECODE[(argb >> 8) & 0xFF];
        double b = DECODE[argb & 0xFF];
        double lr = K[0][0] * r + K[0][1] * g + K[0][2] * b;
        double lg = K[1][0] * r + K[1][1] * g + K[1][2] * b;
        double lb = K[2][0] * r + K[2][1] * g + K[2][2] * b;
        return (argb & 0xFF000000) | (encode(lr) << 16) | (encode(lg) << 8) | encode(lb);
    }

    /**
     * Inverse of {@link #shift}: maps an sRGB pixel back to the AdobeRGB
     * working-space value it came from. Used by the blend compositor to
     * reproduce Acrobat's flattener, which blends in the working space and
     * converts to the print target only ONCE at the end — Multiply of two
     * already-converted colors visibly overshoots (corpus 15764 yellow
     * circle overlaps: gold b=30, converted-operand multiply b=56).
     *
     * @param argb packed ARGB (or RGB) pixel in print-target (sRGB) space
     * @return the working-space pixel with the original alpha byte
     */
    public static int unshift(int argb) {
        double r = SRGB_DECODE[(argb >> 16) & 0xFF];
        double g = SRGB_DECODE[(argb >> 8) & 0xFF];
        double b = SRGB_DECODE[argb & 0xFF];
        double lr = KINV[0][0] * r + KINV[0][1] * g + KINV[0][2] * b;
        double lg = KINV[1][0] * r + KINV[1][1] * g + KINV[1][2] * b;
        double lb = KINV[2][0] * r + KINV[2][1] * g + KINV[2][2] * b;
        return (argb & 0xFF000000)
                | (encodeTable(ARGB_ENCODE, lr) << 16)
                | (encodeTable(ARGB_ENCODE, lg) << 8)
                | encodeTable(ARGB_ENCODE, lb);
    }

    /** sRGB-encodes a linear value via the interpolated table, to 0..255. */
    private static int encode(double lin) {
        return encodeTable(ENCODE, lin);
    }

    /** Encodes a linear value through an interpolated gamma table, to 0..255. */
    private static int encodeTable(double[] table, double lin) {
        if (lin <= 0) return 0;
        if (lin >= 1) return 255;
        double p = lin * ENC_N;
        int i = (int) p;
        double f = p - i;
        double s = table[i] + (table[i + 1] - table[i]) * f;
        return (int) (s * 255 + 0.5);
    }

    /**
     * Shifts every pixel of a decoded image in place (used for untagged
     * DeviceRGB image XObjects on transparency pages).
     *
     * @param img the image to transform
     */
    public static void shiftImage(java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                row[x] = shift(row[x]);
            }
            img.setRGB(0, y, w, 1, row, 0, w);
        }
    }
}
