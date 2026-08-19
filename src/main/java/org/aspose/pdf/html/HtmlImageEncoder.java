package org.aspose.pdf.html;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.logging.Logger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Shared raster-to-HTML encoding for the HTML writers: caps the embedded
 * resolution at the on-page display footprint (browsers cannot show more) and
 * prefers JPEG for opaque images — a full-resolution PNG scan per page across
 * hundreds of pages otherwise runs the output (and the heap) into the ground.
 */
public final class HtmlImageEncoder {

    private static final Logger LOG = Logger.getLogger(HtmlImageEncoder.class.getName());

    /** Embedded rasters keep up to this multiple of their CSS pixel size (zoom budget). */
    private static final double RESOLUTION_BUDGET = 2.0;

    /** Bytes below this are embedded verbatim — decoding would cost more than it saves. */
    private static final int SMALL_BYTES = 64 * 1024;

    /** Opaque PNGs above this are repacked as JPEG even at an in-budget size:
     *  a photographic scan stored as PNG is ~10x the JPEG bytes, and corpus
     *  docs carry a hundred such full-page figures. */
    private static final int JPEG_REPACK_BYTES = 512 * 1024;

    private HtmlImageEncoder() {
        // static utility
    }

    /**
     * Encodes an already-decoded raster as a {@code data:} URI, downscaled to
     * the display budget; JPEG (q=0.8) when opaque, PNG when alpha must survive.
     *
     * @param img  the raster; must not be null
     * @param cssW the on-page CSS pixel width (0 = unknown, no downscale)
     * @param cssH the on-page CSS pixel height (0 = unknown, no downscale)
     * @return the data URI
     * @throws IOException on encoding failure
     */
    public static String dataUri(BufferedImage img, double cssW, double cssH) throws IOException {
        BufferedImage scaled = downscaleForDisplay(img, cssW, cssH);
        boolean opaque = !scaled.getColorModel().hasAlpha();
        String mime = opaque ? "image/jpeg" : "image/png";
        byte[] bytes = opaque ? encodeJpeg(scaled) : encodePng(scaled);
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Encodes stored image bytes as a {@code data:} URI, re-encoding (downscale
     * + JPEG for opaque) only when the decoded raster exceeds the display
     * budget; small or right-sized images are embedded verbatim.
     *
     * @param bytes the encoded image bytes; must not be null
     * @param mime  the source MIME type (null → image/png)
     * @param cssW  the on-page CSS pixel width (0 = unknown, embed verbatim)
     * @param cssH  the on-page CSS pixel height (0 = unknown, embed verbatim)
     * @return the data URI
     */
    public static String dataUri(byte[] bytes, String mime, double cssW, double cssH) {
        String m = mime == null || mime.isEmpty() ? "image/png" : mime;
        boolean maybeOversized = bytes.length > SMALL_BYTES && cssW > 0 && cssH > 0;
        boolean maybeRepack = bytes.length > JPEG_REPACK_BYTES && !"image/jpeg".equals(m);
        if (maybeOversized || maybeRepack) {
            try {
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img != null) {
                    boolean oversized = maybeOversized
                            && (img.getWidth() > cssW * RESOLUTION_BUDGET
                                || img.getHeight() > cssH * RESOLUTION_BUDGET);
                    if (oversized || (maybeRepack && !img.getColorModel().hasAlpha())) {
                        return dataUri(img, cssW, cssH);
                    }
                }
            } catch (IOException | RuntimeException e) {
                LOG.fine(() -> "embed: keeping original bytes (re-encode failed: " + e + ")");
            }
        }
        return "data:" + m + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Downscales a raster whose intrinsic resolution exceeds the display
     * budget; returns the input unchanged otherwise.
     *
     * @param img  the raster
     * @param cssW the CSS pixel width (0 = unknown)
     * @param cssH the CSS pixel height (0 = unknown)
     * @return the (possibly) scaled raster
     */
    public static BufferedImage downscaleForDisplay(BufferedImage img, double cssW, double cssH) {
        if (cssW <= 0 || cssH <= 0) {
            return img;
        }
        int maxW = (int) Math.ceil(cssW * RESOLUTION_BUDGET);
        int maxH = (int) Math.ceil(cssH * RESOLUTION_BUDGET);
        if (img.getWidth() <= maxW && img.getHeight() <= maxH) {
            return img;
        }
        double s = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
        int tw = Math.max(1, (int) Math.round(img.getWidth() * s));
        int th = Math.max(1, (int) Math.round(img.getHeight() * s));
        // ARGB preserves transparency of stencil/SMask images.
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, tw, th, null);
        g.dispose();
        return out;
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage img) throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB
                && img.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
        try {
            writer.setOutput(ios);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.8f);
            writer.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
            ios.close();
        }
        return baos.toByteArray();
    }
}
