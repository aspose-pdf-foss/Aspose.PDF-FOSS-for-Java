package org.aspose.pdf.engine.render;

import org.aspose.pdf.Document;
import org.aspose.pdf.devices.PngDevice;
import org.aspose.pdf.devices.Resolution;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderer regression test for ISO 32000-1 §8.7.3.1: a Pattern's /Matrix maps
 * pattern space to the <em>default</em> (initial) coordinate system of the
 * content stream, NOT the CTM in effect when the fill operator runs.
 *
 * <p>The renderer previously composed the pattern with {@code base × CTM ×
 * patternMatrix}, so any pattern fill under a non-identity {@code cm} was shifted
 * by that CTM — corpus 34156's full-page background pattern, filled under
 * {@code 1 0 0 1 0 792 cm}, was pushed a whole page off-screen and rendered as a
 * black box. This test fills the whole page with a tiling pattern whose cell
 * paints only its bottom half red, under a +100 vertical {@code cm}. Correct
 * (base-relative) placement keeps the red in the page's bottom half; the old
 * CTM-relative bug shifts it into the top half.</p>
 */
public class PatternMatrixCtmIndependenceRenderTest {

    private static byte[] pdf() {
        // Tiling pattern, identity matrix, one tile per page (XStep=YStep=200).
        // Cell paints [0 0 200 100] red — only the bottom half of pattern space.
        String cell = "1 0 0 rg 0 0 200 100 re f";
        // Fill the whole page: translate up 100, then a rect that maps to the
        // full page device area (0 -100 200 200 re -> device 0 0 200 200).
        String content = "q 1 0 0 1 0 100 cm /Pattern cs /P1 scn 0 -100 200 200 re f Q";
        String pattern =
                "<< /Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 "
              + "/BBox [0 0 200 200] /XStep 200 /YStep 200 /Matrix [1 0 0 1 0 0] "
              + "/Resources << >> /Length " + cell.length() + " >>\nstream\n"
              + cell + "\nendstream";

        StringBuilder body = new StringBuilder("%PDF-1.4\n");
        String[] objs = {
                "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
                "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
                "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] "
                        + "/Resources << /Pattern << /P1 5 0 R >> >> /Contents 4 0 R >>\nendobj\n",
                "4 0 obj\n<< /Length " + content.length() + " >>\nstream\n"
                        + content + "\nendstream\nendobj\n",
                "5 0 obj\n" + pattern + "\nendobj\n"
        };
        int[] offsets = new int[objs.length];
        for (int i = 0; i < objs.length; i++) {
            offsets[i] = body.length();
            body.append(objs[i]);
        }
        int xrefPos = body.length();
        body.append("xref\n0 ").append(objs.length + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) body.append(String.format("%010d 00000 n \n", off));
        body.append("trailer\n<< /Size ").append(objs.length + 1)
            .append(" /Root 1 0 R >>\nstartxref\n").append(xrefPos).append("\n%%EOF");
        return body.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Mean red channel over a PDF-space box (origin bottom-left). */
    private static double meanRed(BufferedImage img, int x0, int y0pdf, int w, int h) {
        long r = 0, n = 0;
        int y0 = img.getHeight() - y0pdf - h;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x0 + w); x++) {
                r += img.getRGB(x, y) >> 16 & 0xFF;
                n++;
            }
        }
        return n == 0 ? 0 : (double) r / n;
    }

    private static boolean isRed(BufferedImage img, int x0, int y0pdf, int w, int h) {
        long r = 0, g = 0, b = 0, n = 0;
        int y0 = img.getHeight() - y0pdf - h;
        for (int y = Math.max(0, y0); y < Math.min(img.getHeight(), y0 + h); y++) {
            for (int x = Math.max(0, x0); x < Math.min(img.getWidth(), x0 + w); x++) {
                int rgb = img.getRGB(x, y);
                r += rgb >> 16 & 0xFF; g += rgb >> 8 & 0xFF; b += rgb & 0xFF; n++;
            }
        }
        if (n == 0) return false;
        return r / n > 180 && g / n < 80 && b / n < 80;
    }

    @Test
    public void patternMatrixIsCtmIndependent() throws Exception {
        try (Document doc = new Document(new ByteArrayInputStream(pdf()))) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            new PngDevice(new Resolution(72)).process(doc.getPages().get(1), png);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png.toByteArray()));

            // Bottom half (PDF y 10..90) must be red; top half (PDF y 110..190)
            // must NOT be red. The old CTM-relative bug inverted this.
            boolean bottomRed = isRed(img, 20, 20, 160, 60);
            boolean topRed = isRed(img, 20, 120, 160, 60);
            assertTrue(bottomRed && !topRed,
                    "Pattern must paint base-relative (bottom half red), not CTM-shifted "
                    + "(bottomRed=" + bottomRed + " topRed=" + topRed
                    + " bottomMeanR=" + meanRed(img, 20, 20, 160, 60)
                    + " topMeanR=" + meanRed(img, 20, 120, 160, 60) + ")");
        }
    }
}
