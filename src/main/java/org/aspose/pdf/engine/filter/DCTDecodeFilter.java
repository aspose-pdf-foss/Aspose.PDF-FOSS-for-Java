package org.aspose.pdf.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfName;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * DCTDecode filter: JPEG to raw pixel samples.
 * ISO 32000-1:2008 §7.4.8.
 *
 * <p>Decodes JPEG (baseline and progressive) image data into raw pixel samples
 * using the standard {@link javax.imageio.ImageIO} JPEG reader.</p>
 *
 * <p>Output format: interleaved component bytes in scan-line order.
 * For a 3-component RGB image of width W and height H, the output is
 * W*H*3 bytes: R,G,B,R,G,B,... row by row.</p>
 */
public final class DCTDecodeFilter implements PdfFilter {

    private static final Logger LOG = Logger.getLogger(DCTDecodeFilter.class.getName());

    @Override
    public byte[] decode(byte[] encoded, PdfDictionary params) throws IOException {
        if (encoded == null || encoded.length == 0) return new byte[0];

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(encoded));
        if (image == null) {
            throw new IOException("DCTDecode: failed to decode JPEG (" + encoded.length + " bytes)");
        }

        WritableRaster raster = image.getRaster();
        int w = raster.getWidth(), h = raster.getHeight(), bands = raster.getNumBands();

        // 4-band JPEGs (CMYK/YCCK) come out of the Sun JPEG plugin as raw
        // component bytes, but Adobe-Photoshop CMYK JPEGs use INVERTED CMYK
        // (stored 255 = no ink, stored 0 = full ink) per the Adobe APP14 marker
        // convention. Java's BufferedImage.getRGB() applies a STANDARD CMYK→RGB
        // conversion via the image's ICC profile and does NOT undo the inversion
        // — so for a near-white photo region (stored 255,255,255,255 in the
        // bytes), getRGB returns near-BLACK, which is what we saw probing the
        // 31836 page (~0x0A2D2E for the photo center).
        //
        // Fix: detect Adobe-inverted CMYK via the APP14 marker and apply the
        // inverted-CMYK → RGB formula directly. With stored values (c,m,y,k):
        //   actual ink = 255 - stored
        //   r = 255 * (1 - actual_c/255) * (1 - actual_k/255) = stored_c * stored_k / 255
        // Emitting 3 bytes/pixel RGB keeps the format consistent with 3-band
        // JPEGs; XImage routes to the RGB path by payload size when it sees a
        // CMYK-declared image with RGB-sized payload.
        if (bands == 4) {
            boolean inverted = isAdobeInvertedCmyk(encoded);
            // Image-level /Decode [1 0 1 0 1 0 1 0] (§8.9.5.2, forwarded via
            // PdfStream.getEffectiveDecodeParams) inverts each CMYK component
            // AFTER filter decode — fold it into the APP14 decision (XOR):
            // an Adobe-inverted JPEG whose /Decode un-inverts is net normal.
            if (isFullInversionDecode(params, 4)) {
                inverted = !inverted;
            }
            byte[] decoded = new byte[w * h * 3];
            int[] pixel = new int[4];
            int off = 0;
            for (int y = 0; y < h; y++) {
                // Per-pixel CMYK conversion of a large scan runs for minutes;
                // honour cancellation per row so a timed-out worker unwinds
                // instead of spinning as a zombie thread.
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("DCTDecode: interrupted");
                }
                for (int x = 0; x < w; x++) {
                    raster.getPixel(x, y, pixel);
                    int c = pixel[0] & 0xFF, m = pixel[1] & 0xFF;
                    int yv = pixel[2] & 0xFF, k = pixel[3] & 0xFF;
                    // Actual ink coverage (Adobe APP14 CMYK is stored inverted),
                    // then the press-characterized display conversion - keeps
                    // JPEG photos consistent with vector CMYK fills.
                    double ic, im, iy, ik;
                    if (inverted) {
                        ic = (255 - c) / 255.0; im = (255 - m) / 255.0;
                        iy = (255 - yv) / 255.0; ik = (255 - k) / 255.0;
                    } else {
                        ic = c / 255.0; im = m / 255.0;
                        iy = yv / 255.0; ik = k / 255.0;
                    }
                    int rgb = org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(ic, im, iy, ik);
                    decoded[off++] = (byte) ((rgb >> 16) & 0xFF);
                    decoded[off++] = (byte) ((rgb >> 8) & 0xFF);
                    decoded[off++] = (byte) (rgb & 0xFF);
                }
            }
            final boolean fInv = inverted;
            LOG.fine(() -> "DCTDecode: " + encoded.length + " -> " + w + "x" + h
                    + "x4(CMYK" + (fInv ? "/inv" : "") + ")→3(RGB) = " + decoded.length);
            return decoded;
        }

        byte[] decoded = new byte[w * h * bands];
        int[] pixel = new int[bands];
        int off = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                raster.getPixel(x, y, pixel);
                for (int b = 0; b < bands; b++) {
                    decoded[off++] = (byte) (pixel[b] & 0xFF);
                }
            }
        }

        LOG.fine(() -> "DCTDecode: " + encoded.length + " -> " + w + "x" + h + "x" + bands + " = " + decoded.length);
        return decoded;
    }

    @Override
    public byte[] encode(byte[] decoded, PdfDictionary params) throws IOException {
        if (decoded == null || decoded.length == 0) return new byte[0];

        int width = getInt(params, "Width", 0);
        int height = getInt(params, "Height", 0);
        int bitsPerComponent = getInt(params, "BitsPerComponent", 8);
        if (width <= 0 || height <= 0) {
            throw new IOException("DCTDecode requires Width and Height for encoding");
        }
        if (bitsPerComponent != 8) {
            throw new IOException("DCTDecode only supports 8-bit samples for encoding");
        }

        int components = getComponentCount(params);
        BufferedImage image = createImage(decoded, width, height, components);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "jpg", out)) {
            throw new IOException("DCTDecode: no JPEG writer available");
        }
        byte[] encoded = out.toByteArray();
        LOG.fine(() -> "DCTEncode: " + decoded.length + " -> " + encoded.length
                + " (" + width + "x" + height + "x" + components + ")");
        return encoded;
    }

    @Override
    public PdfName getName() {
        return PdfName.of("DCTDecode");
    }

    private static int getInt(PdfDictionary params, String key, int defaultValue) {
        return params != null ? params.getInt(key, defaultValue) : defaultValue;
    }

    private static int getComponentCount(PdfDictionary params) {
        if (params == null) {
            return 3;
        }
        String colorSpace = params.getNameAsString("ColorSpace");
        if ("DeviceGray".equals(colorSpace)) return 1;
        if ("DeviceRGB".equals(colorSpace)) return 3;
        if ("DeviceCMYK".equals(colorSpace)) return 4;

        if (params.get("ColorSpace") instanceof PdfArray) {
            PdfArray array = (PdfArray) params.get("ColorSpace");
            String family = array.getName(0);
            if ("DeviceGray".equals(family)) return 1;
            if ("DeviceRGB".equals(family)) return 3;
            if ("DeviceCMYK".equals(family)) return 4;
            if ("ICCBased".equals(family) && array.size() > 1 && array.get(1) instanceof PdfDictionary) {
                return ((PdfDictionary) array.get(1)).getInt("N", 3);
            }
        }
        return Math.max(1, getInt(params, "Colors", 3));
    }

    private static BufferedImage createImage(byte[] decoded, int width, int height, int components)
            throws IOException {
        if (components == 1) {
            int expected = width * height;
            if (decoded.length < expected) {
                throw new IOException("DCTDecode grayscale encode: insufficient data");
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster raster = image.getRaster();
            int offset = 0;
            int[] pixel = new int[1];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    pixel[0] = decoded[offset++] & 0xFF;
                    raster.setPixel(x, y, pixel);
                }
            }
            return image;
        }

        int expected = width * height * components;
        if (decoded.length < expected) {
            throw new IOException("DCTDecode color encode: insufficient data");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb;
                if (components >= 4) {
                    int c = decoded[offset++] & 0xFF;
                    int m = decoded[offset++] & 0xFF;
                    int yv = decoded[offset++] & 0xFF;
                    int k = decoded[offset++] & 0xFF;
                    rgb = cmykToRgb(c, m, yv, k);
                } else {
                    int r = decoded[offset++] & 0xFF;
                    int g = decoded[offset++] & 0xFF;
                    int b = decoded[offset++] & 0xFF;
                    rgb = (r << 16) | (g << 8) | b;
                }
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private static int cmykToRgb(int c, int m, int y, int k) {
        int r = 255 - Math.min(255, c + k);
        int g = 255 - Math.min(255, m + k);
        int b = 255 - Math.min(255, y + k);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Scans the JPEG bitstream for the Adobe APP14 marker (FF EE) and decides
     * whether the data is Adobe-inverted CMYK / YCCK.
     *
     * <p>The APP14 segment carries a ColorTransform byte: 0 means
     * unknown/CMYK/RGBA, 1 = YCbCr, 2 = YCCK. For 4-component JPEGs both 0
     * and 2 imply Adobe's inverted-CMYK storage convention.</p>
     *
     * <p>When no APP14 marker is present we default to {@code true} —
     * effectively all real-world CMYK JPEGs found in PDFs come from Photoshop
     * and follow this convention; the rare non-inverted variant would have an
     * APP14 with a different transform code.</p>
     */
    /**
     * True when the forwarded image-level {@code /Decode} array requests a
     * full per-component inversion ({@code [1 0]} repeated for every
     * component). Partial or non-inverting arrays return false — only the
     * all-inverted form composes with the APP14 inversion as a simple XOR.
     *
     * @param params filter params (augmented with /Decode by PdfStream)
     * @param comps  expected component count
     * @return true for a full [1 0 ×comps] inversion
     */
    private static boolean isFullInversionDecode(PdfDictionary params, int comps) {
        if (params == null) return false;
        org.aspose.pdf.engine.pdfobjects.PdfBase d = params.get("Decode");
        if (!(d instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return false;
        org.aspose.pdf.engine.pdfobjects.PdfArray a =
                (org.aspose.pdf.engine.pdfobjects.PdfArray) d;
        if (a.size() != comps * 2) return false;
        for (int i = 0; i < comps; i++) {
            if (a.getFloat(2 * i, -1f) != 1f || a.getFloat(2 * i + 1, -1f) != 0f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAdobeInvertedCmyk(byte[] encoded) {
        if (encoded == null || encoded.length < 14) return true;
        // Walk past SOI (FF D8) and scan marker segments until SOS or EOI.
        int i = 2;
        while (i + 4 < encoded.length) {
            int b0 = encoded[i] & 0xFF;
            int b1 = encoded[i + 1] & 0xFF;
            if (b0 != 0xFF) { i++; continue; }
            // Skip fill bytes (0xFF padding).
            while (b1 == 0xFF && i + 2 < encoded.length) {
                i++;
                b1 = encoded[i + 1] & 0xFF;
            }
            if (b1 == 0xD8 || b1 == 0xD9) { i += 2; continue; }       // SOI / EOI
            if (b1 == 0xDA) return true;                                // SOS — past metadata
            if (b1 == 0xEE) {                                          // APP14
                int segLen = ((encoded[i + 2] & 0xFF) << 8) | (encoded[i + 3] & 0xFF);
                if (segLen >= 12 && i + 13 < encoded.length
                        && encoded[i + 4] == 'A' && encoded[i + 5] == 'd'
                        && encoded[i + 6] == 'o' && encoded[i + 7] == 'b'
                        && encoded[i + 8] == 'e') {
                    int ct = encoded[i + 13] & 0xFF;
                    return ct == 0 || ct == 2;                         // CMYK / YCCK → inverted
                }
                i += 2 + Math.max(2, segLen);
                continue;
            }
            // Standalone single-byte markers (RST0..7 = D0..D7, TEM = 01) have no length.
            if (b1 >= 0xD0 && b1 <= 0xD7) { i += 2; continue; }
            // Length-prefixed segment.
            int segLen = ((encoded[i + 2] & 0xFF) << 8) | (encoded[i + 3] & 0xFF);
            i += 2 + Math.max(2, segLen);
        }
        return true;
    }
}
