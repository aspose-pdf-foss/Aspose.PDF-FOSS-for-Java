package org.aspose.pdf.engine.render;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.logging.Logger;

/**
 * Separable blend-mode composite for PDF /BM (ISO 32000-1:2008, §11.3.5).
 * <p>
 * Java2D ships only Porter-Duff composites; PDF content (notably Highlight
 * annotation appearance streams, corpus 30894) relies on the Multiply blend
 * mode so the markup darkens the page instead of covering it. Only Multiply
 * is implemented — other separable modes fall back to normal SRC_OVER at the
 * {@link #fillComposite} level.
 * </p>
 */
public final class BlendComposite implements Composite {

    private static final Logger LOG = Logger.getLogger(BlendComposite.class.getName());

    /** Constant alpha (the PDF /ca value) applied on top of the blend. */
    private final float alpha;
    /** Separable blend mode name (§11.3.5.2). */
    private final String mode;

    private BlendComposite(String mode, float alpha) {
        this.mode = mode;
        this.alpha = Math.max(0f, Math.min(1f, alpha));
    }

    /**
     * Returns the composite for a non-stroking paint op under the given
     * graphics state: a Multiply blender when /BM is Multiply (or the
     * equivalent Darken on white-ish backdrops would differ — only Multiply
     * is special-cased), otherwise plain SRC_OVER with the /ca alpha.
     *
     * @param state the current graphics state
     * @return the composite to install on the Graphics2D
     */
    public static Composite fillComposite(GraphicsState state) {
        float ca = state.getNonStrokingAlpha();
        return groupComposite(state.getBlendMode(), ca);
    }

    /**
     * Returns a composite for the given /BM name and constant alpha. The
     * separable modes Multiply, Screen, Overlay, Darken, Lighten and
     * Difference are blended per §11.3.5.2; any other mode (incl. Normal)
     * falls back to plain SRC_OVER with the constant alpha. Used both for
     * direct paint ops and for compositing transparency-group results.
     *
     * @param mode  the blend-mode name from /BM
     * @param alpha the constant alpha (/ca), 0..1
     * @return the composite to install on the Graphics2D
     */
    public static Composite groupComposite(String mode, float alpha) {
        if (isSeparable(mode)) {
            return new BlendComposite(mode, alpha);
        }
        if (alpha < 1.0f) {
            // Acrobat print-parity: flatten Normal-mode transparency in ink
            // (CMYK) space like Acrobat's print pipeline, not in RGB — see
            // CmykPrintLut.inkBlendActive(). Behind the parity kill switches.
            if (org.aspose.pdf.engine.colorspace.CmykPrintLut.inkBlendActive()) {
                return new BlendComposite(INK_NORMAL, alpha);
            }
            return AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha);
        }
        return AlphaComposite.SrcOver;
    }

    /** Marker mode: Normal-mode alpha compositing performed in ink space. */
    private static final String INK_NORMAL = "\0InkNormal";

    private static boolean isSeparable(String mode) {
        if (mode == null) return false;
        switch (mode) {
            case "Multiply":
            case "Screen":
            case "Overlay":
            case "Darken":
            case "Lighten":
            case "Difference":
                return true;
            default:
                return false;
        }
    }

    @Override
    public CompositeContext createContext(ColorModel srcColorModel,
                                          ColorModel dstColorModel,
                                          RenderingHints hints) {
        if (INK_NORMAL.equals(mode)) {
            return new InkNormalContext(alpha);
        }
        return new SeparableContext(mode, alpha);
    }

    /**
     * Normal-mode alpha compositing in ink (CMYK) space for Acrobat
     * print-parity. Both source and backdrop RGB are mapped back to ink values
     * through the measured print lattice ({@link
     * org.aspose.pdf.engine.colorspace.CmykPrintLut#inverse(int)}), the inks
     * are alpha-mixed, and the mix is converted forward again. This reproduces
     * Acrobat's flattener, which composites in the CMYK blending space before
     * the print color conversion: an ink-heavy overlay at /ca 0.35 must darken
     * far more than linear RGB mixing suggests (corpus 46878 sidebar).
     * Partially covered destination pixels (offscreen group canvases) treat
     * missing coverage as unprinted paper (no ink), consistent with the
     * lattice's white point.
     */
    private static final class InkNormalContext implements CompositeContext {
        private final float alpha;

        InkNormalContext(float alpha) {
            this.alpha = alpha;
        }

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int w = Math.min(src.getWidth(), dstIn.getWidth());
            int h = Math.min(src.getHeight(), dstIn.getHeight());
            int sBands = src.getNumBands();
            int dBands = dstIn.getNumBands();
            int[] sp = new int[w * sBands];
            int[] dp = new int[w * dBands];

            for (int y = 0; y < h; y++) {
                src.getPixels(src.getMinX(), src.getMinY() + y, w, 1, sp);
                dstIn.getPixels(dstIn.getMinX(), dstIn.getMinY() + y, w, 1, dp);

                for (int x = 0; x < w; x++) {
                    int si = x * sBands;
                    int di = x * dBands;
                    int srcAlpha = sBands > 3 ? sp[si + 3] : 255;
                    double as = (srcAlpha / 255.0) * alpha;
                    if (as <= 0) continue;
                    double ad = dBands > 3 ? dp[di + 3] / 255.0 : 1.0;
                    double ao = as + ad * (1 - as);

                    double[] srcInk = org.aspose.pdf.engine.colorspace.CmykPrintLut.inverse(
                            (sp[si] << 16) | (sp[si + 1] << 8) | sp[si + 2]);
                    double[] dstInk = org.aspose.pdf.engine.colorspace.CmykPrintLut.inverse(
                            (dp[di] << 16) | (dp[di + 1] << 8) | dp[di + 2]);
                    // premultiplied ink mix; uncovered backdrop = no ink
                    double c = (srcInk[0] * as + dstInk[0] * ad * (1 - as)) / ao;
                    double m = (srcInk[1] * as + dstInk[1] * ad * (1 - as)) / ao;
                    double yk = (srcInk[2] * as + dstInk[2] * ad * (1 - as)) / ao;
                    double k = (srcInk[3] * as + dstInk[3] * ad * (1 - as)) / ao;
                    double[] rgb = org.aspose.pdf.engine.colorspace.CmykPrintLut.toRGB(c, m, yk, k);
                    dp[di] = clamp((int) Math.round(rgb[0]));
                    dp[di + 1] = clamp((int) Math.round(rgb[1]));
                    dp[di + 2] = clamp((int) Math.round(rgb[2]));
                    if (dBands > 3) {
                        dp[di + 3] = clamp((int) Math.round(ao * 255));
                    }
                }
                dstOut.setPixels(dstOut.getMinX(), dstOut.getMinY() + y, w, 1, dp);
            }
        }

        private static int clamp(int v) {
            return v < 0 ? 0 : Math.min(v, 255);
        }

        @Override
        public void dispose() {
            // no resources held
        }
    }

    /**
     * Separable blends (§11.3.5.2): per channel C = B(Cb, Cs), then standard
     * source-over with the source alpha × constant alpha.
     */
    private static final class SeparableContext implements CompositeContext {
        private final float alpha;
        private final String mode;

        SeparableContext(String mode, float alpha) {
            this.mode = mode;
            this.alpha = alpha;
        }

        /** B(Cb, Cs) per §11.3.5.2, in 0..255 integer space. */
        private int blend(int cb, int cs) {
            switch (mode) {
                case "Multiply":
                    return (cs * cb) / 255;
                case "Screen":
                    return 255 - ((255 - cs) * (255 - cb)) / 255;
                case "Overlay":
                    // HardLight(Cs, Cb): Multiply(2×Cb) below 0.5, Screen above.
                    return cb <= 127
                            ? (2 * cb * cs) / 255
                            : 255 - (2 * (255 - cb) * (255 - cs)) / 255;
                case "Darken":
                    return Math.min(cb, cs);
                case "Lighten":
                    return Math.max(cb, cs);
                case "Difference":
                    return Math.abs(cb - cs);
                default:
                    return cs;
            }
        }

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int w = Math.min(src.getWidth(), dstIn.getWidth());
            int h = Math.min(src.getHeight(), dstIn.getHeight());
            int sBands = src.getNumBands();
            int dBands = dstIn.getNumBands();
            int[] sp = new int[w * sBands];
            int[] dp = new int[w * dBands];
            // Acrobat print-parity working-space blend: on transparency pages
            // Acrobat's flattener blends untagged RGB in the AdobeRGB working
            // space and converts to the print target once at the END. Our
            // pixels are already print-target values (shifted at paint time),
            // so map both operands back, blend, and re-convert — Multiply of
            // two pre-converted colors visibly overshoots otherwise (corpus
            // 15764 overlapping yellow circles).
            boolean workingSpace =
                    org.aspose.pdf.engine.colorspace.RgbPrintShift.active()
                            && sBands >= 3 && dBands >= 3;

            for (int y = 0; y < h; y++) {
                src.getPixels(src.getMinX(), src.getMinY() + y, w, 1, sp);
                dstIn.getPixels(dstIn.getMinX(), dstIn.getMinY() + y, w, 1, dp);

                for (int x = 0; x < w; x++) {
                    int si = x * sBands;
                    int di = x * dBands;
                    int srcAlpha = sBands > 3 ? sp[si + 3] : 255;
                    float as = (srcAlpha / 255f) * alpha;
                    if (as <= 0f) continue;
                    // §11.3.3: the blend function applies only to the extent
                    // the backdrop is opaque. On a (partially) transparent
                    // backdrop the source colour passes through unblended —
                    // the stored dst colour of an unpainted ARGB pixel is
                    // meaningless black and must not feed B(Cb,Cs). Full
                    // un-premultiplied composite (§11.4.5):
                    //   αo·Co = αs(1−αb)Cs + αb(1−αs)Cb + αs·αb·B(Cb,Cs)
                    float ad = dBands > 3 ? dp[di + 3] / 255f : 1f;
                    float ao = as + ad * (1 - as);

                    if (workingSpace) {
                        int su = org.aspose.pdf.engine.colorspace.RgbPrintShift.unshift(
                                (sp[si] << 16) | (sp[si + 1] << 8) | sp[si + 2]);
                        int du = org.aspose.pdf.engine.colorspace.RgbPrintShift.unshift(
                                (dp[di] << 16) | (dp[di + 1] << 8) | dp[di + 2]);
                        int mixed = 0;
                        for (int c = 0; c < 3; c++) {
                            int sh = 16 - 8 * c;
                            int cs = (su >> sh) & 0xFF;
                            int cb = (du >> sh) & 0xFF;
                            int blended = blend(cb, cs);
                            float co = (as * (1 - ad) * cs + ad * (1 - as) * cb
                                    + as * ad * blended) / ao;
                            mixed |= clamp(Math.round(co)) << sh;
                        }
                        int out = org.aspose.pdf.engine.colorspace.RgbPrintShift.shift(mixed);
                        dp[di] = (out >> 16) & 0xFF;
                        dp[di + 1] = (out >> 8) & 0xFF;
                        dp[di + 2] = out & 0xFF;
                    } else {
                        for (int c = 0; c < 3 && c < sBands && c < dBands; c++) {
                            int cs = sp[si + c];
                            int cb = dp[di + c];
                            int blended = blend(cb, cs);
                            dp[di + c] = clamp(Math.round(
                                    (as * (1 - ad) * cs + ad * (1 - as) * cb
                                            + as * ad * blended) / ao));
                        }
                    }
                    if (dBands > 3) {
                        dp[di + 3] = clamp(Math.round(ao * 255));
                    }
                }
                dstOut.setPixels(dstOut.getMinX(), dstOut.getMinY() + y, w, 1, dp);
            }
        }

        private static int clamp(int v) {
            return v < 0 ? 0 : Math.min(v, 255);
        }

        @Override
        public void dispose() {
            // no resources held
        }
    }
}
