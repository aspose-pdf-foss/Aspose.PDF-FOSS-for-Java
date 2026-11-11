package org.aspose.pdf.devices;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.render.PdfPageRenderer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.WritableRaster;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Renders PDF pages to multi-page TIFF format.
 * <p>
 * Supports rendering a single page, a range of pages, or an entire document.
 * Uses the JDK TIFF ImageIO writer (available in Java 9+; Java 11 is required
 * by this project).
 * </p>
 */
public class TiffDevice {

    private static final Logger LOG = Logger.getLogger(TiffDevice.class.getName());

    private final Resolution resolution;
    private final TiffSettings settings;
    private final int explicitWidth;
    private final int explicitHeight;

    /** Default 150 DPI resolution + default settings. */
    public TiffDevice() {
        this(new Resolution(150), null, 0, 0);
    }

    /** Explicit pixel dimensions + 150 DPI + default settings. */
    public TiffDevice(int width, int height) {
        this(new Resolution(150), null, width, height);
    }

    /** Explicit dimensions + resolution. */
    public TiffDevice(int width, int height, Resolution resolution) {
        this(resolution, null, width, height);
    }

    /** Explicit dimensions + resolution + settings. */
    public TiffDevice(int width, int height, Resolution resolution, TiffSettings settings) {
        this(resolution, settings, width, height);
    }

    /** Explicit dimensions + settings (default 150 DPI). */
    public TiffDevice(int width, int height, TiffSettings settings) {
        this(new Resolution(150), settings, width, height);
    }

    /** Settings only — default 150 DPI, no explicit dimensions. */
    public TiffDevice(TiffSettings settings) {
        this(new Resolution(150), settings, 0, 0);
    }

    /** PageSize + settings — width/height from page-size in points. */
    public TiffDevice(org.aspose.pdf.PageSize pageSize, TiffSettings settings) {
        this(new Resolution(150), settings,
                pageSize == null ? 0 : (int) Math.round(pageSize.getWidth()),
                pageSize == null ? 0 : (int) Math.round(pageSize.getHeight()));
    }

    /**
     * Creates a TIFF device with the given resolution.
     *
     * @param resolution the rendering resolution
     * @throws IllegalArgumentException if resolution is null
     */
    public TiffDevice(Resolution resolution) {
        this(resolution, null, 0, 0);
    }

    /**
     * Creates a TIFF device with the given resolution and settings.
     *
     * @param resolution   the rendering resolution
     * @param tiffSettings the TIFF output settings, or {@code null} for defaults
     * @throws IllegalArgumentException if resolution is null
     */
    public TiffDevice(Resolution resolution, TiffSettings tiffSettings) {
        this(resolution, tiffSettings, 0, 0);
    }

    private TiffDevice(Resolution resolution, TiffSettings tiffSettings,
                       int explicitWidth, int explicitHeight) {
        if (resolution == null) {
            throw new IllegalArgumentException("Resolution must not be null");
        }
        this.resolution = resolution;
        this.settings = tiffSettings;
        this.explicitWidth = explicitWidth;
        this.explicitHeight = explicitHeight;
    }

    /** Returns the explicit width set via a (width, height, ...) constructor, or 0 if not set. */
    public int getWidth() {
        return explicitWidth;
    }

    /** Returns the explicit height set via a (width, height, ...) constructor, or 0 if not set. */
    public int getHeight() {
        return explicitHeight;
    }

    /**
     * Returns the TIFF settings used by this device.
     *
     * @return the settings, or {@code null} if using defaults
     */
    public TiffSettings getSettings() {
        return settings;
    }

    /**
     * Renders all pages of a document to a multi-page TIFF.
     *
     * @param document the PDF document
     * @param output   the output stream
     * @throws IOException if rendering or writing fails
     */
    public void process(Document document, OutputStream output) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        process(document, 1, document.getPages().getCount(), output);
    }

    /**
     * Renders a range of pages to a multi-page TIFF.
     *
     * @param document  the PDF document
     * @param fromPage  the first page (1-based)
     * @param toPage    the last page (1-based, inclusive)
     * @param output    the output stream
     * @throws IOException if rendering or writing fails
     */
    public void process(Document document, int fromPage, int toPage, OutputStream output)
            throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("OutputStream must not be null");
        }
        int pageCount = document.getPages().getCount();
        if (fromPage < 1) fromPage = 1;
        if (toPage > pageCount) toPage = pageCount;
        if (fromPage > toPage) {
            throw new IllegalArgumentException("fromPage (" + fromPage + ") > toPage (" + toPage + ")");
        }

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("TIFF");
        if (!writers.hasNext()) {
            // Fallback: write pages as individual PNGs is not possible in TIFF
            throw new IOException("No TIFF ImageIO writer available. "
                    + "Ensure Java 11+ is used (javax.imageio TIFF plugin).");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            applyCompression(param);

            boolean first = true;

            boolean binary = isBiLevelCompression();
            PdfPageRenderer renderer = new PdfPageRenderer();
            for (int i = fromPage; i <= toPage; i++) {
                Page page = document.getPages().get(i);
                BufferedImage image = renderer.renderPage(page, resolution.getX(), resolution.getY());
                if (explicitWidth > 0 && explicitHeight > 0) {
                    image = resizeTo(image, explicitWidth, explicitHeight);
                }

                // CCITT T.4/T.6 require 1-bit (bi-level) images; everything else uses RGB.
                BufferedImage out = binary ? toBinary(image) : toRGB(image);
                IIOMetadata md = buildMetadata(writer, param, out, resolution);

                if (first) {
                    writer.write(null, new IIOImage(out, null, md), param);
                    first = false;
                } else {
                    writer.writeInsert(-1, new IIOImage(out, null, md), param);
                }
            }
        } finally {
            writer.dispose();
        }

        int logFrom = fromPage, logTo = toPage;
        LOG.fine(() -> "TIFF written: pages " + logFrom + "-" + logTo);
    }

    /**
     * Renders a single page to TIFF.
     *
     * @param page   the PDF page
     * @param output the output stream
     * @throws IOException if rendering or writing fails
     */
    public void process(Page page, OutputStream output) throws IOException {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        if (output == null) {
            throw new IllegalArgumentException("OutputStream must not be null");
        }

        PdfPageRenderer renderer = new PdfPageRenderer();
        BufferedImage image = renderer.renderPage(page, resolution.getX(), resolution.getY());
        if (explicitWidth > 0 && explicitHeight > 0) {
            image = resizeTo(image, explicitWidth, explicitHeight);
        }
        BufferedImage rgb = toRGB(image);

        if (!ImageIO.write(rgb, "TIFF", output)) {
            throw new IOException("No TIFF ImageIO writer available");
        }
    }

    /**
     * Returns the resolution used by this device.
     *
     * @return the resolution
     */
    public Resolution getResolution() {
        return resolution;
    }

    /**
     * Builds metadata that records the device's nominal DPI as TIFF
     * XResolution / YResolution / ResolutionUnit (so viewers and the gold
     * baselines agree on physical size). Falls back to the writer default if
     * the platform's TIFF metadata format is not the JDK 9+ standard one.
     */
    private static IIOMetadata buildMetadata(ImageWriter writer, ImageWriteParam param,
                                             BufferedImage image, Resolution resolution) {
        try {
            IIOMetadata md = writer.getDefaultImageMetadata(
                    new ImageTypeSpecifier(image), param);
            // The JDK 9+ standard format key is "javax_imageio_1.0".
            String stdFmt = "javax_imageio_1.0";
            IIOMetadataNode root = (IIOMetadataNode) md.getAsTree(stdFmt);
            IIOMetadataNode dim = getOrAdd(root, "Dimension");
            // Standard metadata uses "PixelAspectRatio" + "HorizontalPixelSize" /
            // "VerticalPixelSize" (in millimetres). The TIFF writer maps these to
            // XResolution / YResolution at write time.
            float dpiX = (float) resolution.getX();
            float dpiY = (float) resolution.getY();
            if (dpiX <= 0) dpiX = 96f;
            if (dpiY <= 0) dpiY = dpiX;
            float pixelSizeMmX = 25.4f / dpiX;
            float pixelSizeMmY = 25.4f / dpiY;
            setSize(dim, "HorizontalPixelSize", pixelSizeMmX);
            setSize(dim, "VerticalPixelSize", pixelSizeMmY);
            md.mergeTree(stdFmt, root);
            return md;
        } catch (Exception e) {
            LOG.fine(() -> "TIFF DPI metadata write failed: " + e.getMessage());
            return null;
        }
    }

    private static IIOMetadataNode getOrAdd(IIOMetadataNode parent, String name) {
        org.w3c.dom.NodeList nodes = parent.getElementsByTagName(name);
        if (nodes.getLength() > 0) return (IIOMetadataNode) nodes.item(0);
        IIOMetadataNode node = new IIOMetadataNode(name);
        parent.appendChild(node);
        return node;
    }

    private static void setSize(IIOMetadataNode parent, String tag, float mm) {
        IIOMetadataNode node = getOrAdd(parent, tag);
        node.setAttribute("value", Float.toString(mm));
    }

    private void applyCompression(ImageWriteParam param) {
        if (settings == null || settings.getCompression() == null) {
            return;
        }
        String name = mapCompression(settings.getCompression());
        if (name == null) {
            param.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            return;
        }
        try {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            String[] available = param.getCompressionTypes();
            if (available != null) {
                for (String t : available) {
                    if (t.equalsIgnoreCase(name)) {
                        param.setCompressionType(t);
                        return;
                    }
                }
            }
            LOG.fine(() -> "TIFF compression '" + name + "' not available; using writer default");
        } catch (UnsupportedOperationException e) {
            LOG.fine(() -> "TIFF writer does not support compression selection");
        }
    }

    private static String mapCompression(CompressionType c) {
        switch (c) {
            case LZW:    return "LZW";
            case CCITT3: return "CCITT T.4";
            case CCITT4: return "CCITT T.6";
            case RLE:    return "PackBits";
            case None:   return null;
            default:     return null;
        }
    }

    /**
     * Scales the rendered page bitmap to {@code targetW × targetH}, used when a
     * {@code TiffDevice(width, height, …)} constructor is in play (PDFNET-44785
     * style explicit output sizing).
     */
    private static BufferedImage resizeTo(BufferedImage src, int targetW, int targetH) {
        if (src == null || (src.getWidth() == targetW && src.getHeight() == targetH)) {
            return src;
        }
        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetW, targetH, null);
        g.dispose();
        return scaled;
    }

    private BufferedImage toRGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return rgb;
    }

    private BufferedImage toBinary(BufferedImage src) {
        BufferedImage rgb = toRGB(src);
        int w = rgb.getWidth();
        int h = rgb.getHeight();
        BufferedImage bin = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_BINARY);
        WritableRaster raster = bin.getRaster();
        float brightness = settings != null ? settings.getBrightness() : 0.5f;
        int threshold = Math.max(0, Math.min(255, Math.round(brightness * 255f)));
        // Row-wise bulk pixel access: a single getRGB/setSamples call per scanline is
        // dramatically faster than per-pixel getRGB(x,y)/setSample(x,y), which dominated
        // large multi-page TIFF exports (e.g. a 1100-page document at 300 DPI, PDFNEWNET-33324).
        int[] row = new int[w];
        int[] bits = new int[w];
        for (int y = 0; y < h; y++) {
            rgb.getRGB(0, y, w, 1, row, 0, w);
            for (int x = 0; x < w; x++) {
                int argb = row[x];
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int luminance = (299 * r + 587 * g + 114 * b) / 1000;
                bits[x] = luminance >= threshold ? 1 : 0;
            }
            raster.setSamples(0, y, w, 1, 0, bits);
        }
        return bin;
    }

    private boolean isBiLevelCompression() {
        if (settings == null || settings.getCompression() == null) return false;
        CompressionType c = settings.getCompression();
        return c == CompressionType.CCITT3 || c == CompressionType.CCITT4;
    }
}
