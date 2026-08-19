package org.aspose.pdf.sdm;

import org.aspose.pdf.Page;
import org.aspose.pdf.devices.PngDevice;
import org.aspose.pdf.devices.Resolution;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Test utility: page rendering + mean 8×8-window SSIM (same metric as the XFA
 * acceptance suites), used as the identity-round-trip oracle.
 */
public final class SsimTestUtil {

    private SsimTestUtil() {
    }

    /**
     * Renders a page to an image via PngDevice at the given DPI.
     *
     * @param page the page
     * @param dpi  the render resolution
     * @return the rendered image
     * @throws IOException if rendering fails
     */
    public static BufferedImage render(Page page, int dpi) throws IOException {
        PngDevice device = new PngDevice(new Resolution(dpi));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        device.process(page, out);
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * Mean 8×8-window SSIM over the overlapping region of two images.
     *
     * @param a first image
     * @param b second image
     * @return SSIM in [0..1] (1 = identical)
     */
    public static double windowedSsim(BufferedImage a, BufferedImage b) {
        int w = Math.min(a.getWidth(), b.getWidth());
        int h = Math.min(a.getHeight(), b.getHeight());
        double[] ga = new double[w * h];
        double[] gb = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ga[y * w + x] = gray(a.getRGB(x, y));
                gb[y * w + x] = gray(b.getRGB(x, y));
            }
        }
        final int win = 8;
        final double c1 = 6.5025;
        final double c2 = 58.5225;
        double sum = 0;
        long n = 0;
        for (int by = 0; by + win <= h; by += win) {
            for (int bx = 0; bx + win <= w; bx += win) {
                double muA = 0;
                double muB = 0;
                for (int y = by; y < by + win; y++) {
                    for (int x = bx; x < bx + win; x++) {
                        muA += ga[y * w + x];
                        muB += gb[y * w + x];
                    }
                }
                int cnt = win * win;
                muA /= cnt;
                muB /= cnt;
                double vA = 0;
                double vB = 0;
                double cov = 0;
                for (int y = by; y < by + win; y++) {
                    for (int x = bx; x < bx + win; x++) {
                        double da = ga[y * w + x] - muA;
                        double db = gb[y * w + x] - muB;
                        vA += da * da;
                        vB += db * db;
                        cov += da * db;
                    }
                }
                vA /= cnt;
                vB /= cnt;
                cov /= cnt;
                sum += ((2 * muA * muB + c1) * (2 * cov + c2))
                        / ((muA * muA + muB * muB + c1) * (vA + vB + c2));
                n++;
            }
        }
        return n == 0 ? 1.0 : sum / n;
    }

    private static double gray(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0.299 * r + 0.587 * g + 0.114 * b;
    }
}
