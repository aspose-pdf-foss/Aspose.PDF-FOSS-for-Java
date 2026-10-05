package org.aspose.pdf.devices;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.logging.Logger;

/**
 * Renders a PDF page to JPEG format with configurable compression quality.
 * <p>
 * JPEG does not support alpha, so the rendered image is flattened to an
 * opaque RGB image with a white background before encoding.
 * </p>
 */
public class JpegDevice extends PageDevice {

    private static final Logger LOG = Logger.getLogger(JpegDevice.class.getName());

    private final int quality;
    private RenderingOptions renderingOptions;

    /**
     * Creates a JPEG device with the default 150 DPI resolution and quality 100.
     */
    public JpegDevice() {
        this(new Resolution(150), 100);
    }

    /**
     * Creates a JPEG device with the default 150 DPI resolution and the given
     * quality.
     *
     * @param quality the JPEG compression quality (1-100, 100 = best)
     */
    public JpegDevice(int quality) {
        this(new Resolution(150), quality);
    }

    /**
     * Creates a JPEG device with explicit pixel dimensions and the default
     * 150 DPI resolution + quality 100.
     *
     * @param width  target width in pixels
     * @param height target height in pixels
     */
    public JpegDevice(int width, int height) {
        this(width, height, new Resolution(150), 100);
    }

    /**
     * Creates a JPEG device with explicit pixel dimensions, resolution, and
     * default quality 100.
     *
     * @param width      target width in pixels
     * @param height     target height in pixels
     * @param resolution the rendering resolution
     */
    public JpegDevice(int width, int height, Resolution resolution) {
        this(width, height, resolution, 100);
    }

    /**
     * Creates a JPEG device with the given resolution and default quality (100).
     *
     * @param resolution the rendering resolution
     */
    public JpegDevice(Resolution resolution) {
        this(resolution, 100);
    }

    /**
     * Creates a JPEG device with the given resolution and quality.
     *
     * @param resolution the rendering resolution
     * @param quality    the JPEG compression quality (1-100, 100 = best)
     */
    public JpegDevice(Resolution resolution, int quality) {
        super(resolution);
        this.quality = Math.max(1, Math.min(100, quality));
    }

    /**
     * Creates a JPEG device with explicit dimensions, resolution, and quality.
     *
     * @param width      target width in pixels
     * @param height     target height in pixels
     * @param resolution the rendering resolution
     * @param quality    the JPEG compression quality (1-100)
     */
    public JpegDevice(int width, int height, Resolution resolution, int quality) {
        super(width, height, resolution);
        this.quality = Math.max(1, Math.min(100, quality));
    }

    /**
     * Returns the JPEG quality setting.
     *
     * @return the quality (1-100)
     */
    public int getQuality() {
        return quality;
    }

    /**
     * Returns the rendering options.
     *
     * @return the rendering options, or null
     */
    public RenderingOptions getRenderingOptions() {
        return renderingOptions;
    }

    /**
     * Sets the rendering options.
     *
     * @param options the rendering options
     */
    public void setRenderingOptions(RenderingOptions options) {
        this.renderingOptions = options;
    }

    @Override
    protected void writeImage(BufferedImage image, OutputStream output) throws IOException {
        // Convert ARGB to RGB (JPEG doesn't support alpha)
        BufferedImage rgb = new BufferedImage(
                image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();

        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageIO writer available");
        }

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality / 100.0f);
            // Stamp the device resolution into the JFIF density fields so the
            // saved JPEG advertises the correct DPI (ISO/IEC 10918 JFIF APP0).
            // Without this the file carries no density and readers report 0/72.
            javax.imageio.metadata.IIOMetadata meta = buildJfifMetadata(writer, param, rgb);
            writer.write(null, new IIOImage(rgb, null, meta), param);
        } finally {
            writer.dispose();
        }
        LOG.fine(() -> "JPEG image written: " + rgb.getWidth() + "x" + rgb.getHeight()
                + " quality=" + quality);
    }

    /**
     * Builds JPEG image metadata carrying the device resolution as the JFIF
     * pixel density (resUnits = 1 → dots-per-inch). Returns {@code null} if the
     * writer cannot supply or accept a metadata tree, in which case the image is
     * written without density (the pixel data is unaffected either way).
     *
     * @param writer the JPEG image writer
     * @param param  the write parameters (needed to obtain default metadata)
     * @param rgb    the image being written (its type selects the metadata shape)
     * @return populated metadata, or {@code null} if density could not be set
     */
    private javax.imageio.metadata.IIOMetadata buildJfifMetadata(
            ImageWriter writer, ImageWriteParam param, BufferedImage rgb) {
        try {
            javax.imageio.ImageTypeSpecifier type =
                    javax.imageio.ImageTypeSpecifier.createFromRenderedImage(rgb);
            javax.imageio.metadata.IIOMetadata meta =
                    writer.getDefaultImageMetadata(type, param);
            if (meta == null || meta.isReadOnly()) {
                return null;
            }
            String fmt = "javax_imageio_jpeg_image_1.0";
            javax.imageio.metadata.IIOMetadataNode root =
                    (javax.imageio.metadata.IIOMetadataNode) meta.getAsTree(fmt);
            javax.imageio.metadata.IIOMetadataNode jfif = findOrCreateJfif(root);
            jfif.setAttribute("resUnits", "1"); // dots per inch
            jfif.setAttribute("Xdensity", String.valueOf(resolution.getX()));
            jfif.setAttribute("Ydensity", String.valueOf(resolution.getY()));
            meta.setFromTree(fmt, root);
            return meta;
        } catch (RuntimeException | javax.imageio.metadata.IIOInvalidTreeException e) {
            LOG.fine(() -> "Could not embed JFIF density: " + e.getMessage());
            return null;
        }
    }

    /**
     * Finds the {@code app0JFIF} node under {@code JPEGvariety}, creating the
     * intermediate nodes if the default tree omitted them.
     *
     * @param root the root metadata node for the JPEG image format
     * @return the {@code app0JFIF} element ready to receive density attributes
     */
    private static javax.imageio.metadata.IIOMetadataNode findOrCreateJfif(
            javax.imageio.metadata.IIOMetadataNode root) {
        javax.imageio.metadata.IIOMetadataNode variety =
                childByName(root, "JPEGvariety");
        if (variety == null) {
            variety = new javax.imageio.metadata.IIOMetadataNode("JPEGvariety");
            root.insertBefore(variety, root.getFirstChild());
        }
        javax.imageio.metadata.IIOMetadataNode jfif = childByName(variety, "app0JFIF");
        if (jfif == null) {
            jfif = new javax.imageio.metadata.IIOMetadataNode("app0JFIF");
            variety.appendChild(jfif);
        }
        return jfif;
    }

    /**
     * Returns the first direct child element with the given name, or {@code null}.
     *
     * @param parent the parent metadata node
     * @param name   the child tag name to match
     * @return the matching child, or {@code null} if none
     */
    private static javax.imageio.metadata.IIOMetadataNode childByName(
            javax.imageio.metadata.IIOMetadataNode parent, String name) {
        for (org.w3c.dom.Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof javax.imageio.metadata.IIOMetadataNode
                    && name.equals(n.getNodeName())) {
                return (javax.imageio.metadata.IIOMetadataNode) n;
            }
        }
        return null;
    }
}
