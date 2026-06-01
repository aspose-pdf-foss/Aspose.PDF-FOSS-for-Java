package org.aspose.pdf;

import org.aspose.pdf.engine.colorspace.*;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.engine.parser.PDFParser;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Logger;

/**
 * Represents an image XObject in a PDF document (ISO 32000-1:2008, §8.9, Table 89).
 * <p>
 * Wraps a COSStream with {@code /Subtype /Image}. Provides access to image
 * properties (width, height, bits per component, color space) and decoded
 * pixel data. Supports saving to output streams and conversion to
 * {@link BufferedImage}.
 * </p>
 */
public class XImage {

    private static final Logger LOG = Logger.getLogger(XImage.class.getName());

    private final COSStream stream;
    private String name;
    private final PDFParser parser;
    private COSDictionary xobjectDict; // parent /XObject dictionary for renaming

    /**
     * Creates an XImage from an image XObject stream.
     *
     * @param stream the image XObject COSStream
     * @param name   the resource name (e.g., "Im1")
     * @param parser the PDF parser for resolving indirect refs (may be null)
     */
    public XImage(COSStream stream, String name, PDFParser parser) {
        this.stream = stream != null ? stream : new COSStream();
        this.name = name;
        this.parser = parser;
    }

    /**
     * Returns the image width in pixels (/Width).
     *
     * @return the width
     */
    public int getWidth() {
        return stream.getInt("Width", 0);
    }

    /**
     * Returns the image height in pixels (/Height).
     *
     * @return the height
     */
    public int getHeight() {
        return stream.getInt("Height", 0);
    }

    /**
     * Returns the bits per component (/BitsPerComponent). Default: 8.
     *
     * @return the bits per component
     */
    public int getBitsPerComponent() {
        return stream.getInt("BitsPerComponent", 8);
    }

    /**
     * Returns the resource name of this image (e.g., "Im1").
     *
     * @return the resource name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the name of this image resource, updating both the Java field
     * and the key in the parent /XObject dictionary (if known).
     *
     * @param newName the new name
     */
    public void setName(String newName) {
        if (newName == null || newName.equals(this.name)) return;
        String oldName = this.name;
        this.name = newName;
        // Rename the key in the parent /XObject dictionary
        if (xobjectDict != null && oldName != null) {
            COSBase val = xobjectDict.get(oldName);
            if (val != null) {
                xobjectDict.remove(COSName.of(oldName));
                xobjectDict.set(COSName.of(newName), val);
            }
        }
    }

    /**
     * Sets the parent /XObject dictionary reference (called by XImageCollection).
     */
    void setXObjectDictionary(COSDictionary dict) {
        this.xobjectDict = dict;
    }

    /**
     * Returns the color space of this image.
     *
     * @return the color space
     * @throws IOException if resolution fails
     */
    public ColorSpaceBase getColorSpace() throws IOException {
        COSBase cs = stream.get("ColorSpace");
        if (cs != null) {
            cs = resolveRef(cs);
            return ColorSpaceBase.resolve(cs, null, parser);
        }
        return DeviceRGB.INSTANCE;
    }

    /**
     * Returns whether this is an image mask (1-bit stencil).
     *
     * @return true if /ImageMask is true
     */
    public boolean isImageMask() {
        return stream.getBoolean("ImageMask", false);
    }

    /**
     * Returns the decoded image data (raw pixel bytes after filter decompression).
     *
     * @return the decoded bytes
     * @throws IOException if decoding fails
     */
    public byte[] getDecodedData() throws IOException {
        return stream.getDecodedData();
    }

    /**
     * Returns the raw encoded stream data (e.g., raw JPEG bytes for DCTDecode).
     *
     * @return the encoded bytes
     */
    public byte[] getEncodedData() {
        return stream.getEncodedData();
    }

    /**
     * Saves the image to an output stream.
     * <p>
     * For DCTDecode (JPEG) images, writes raw JPEG data directly.
     * For other images, converts to PNG format via {@code javax.imageio}.
     * </p>
     *
     * @param output the output stream
     * @throws IOException if saving fails
     */
    public void save(OutputStream output) throws IOException {
        String filter = getFilterName();
        if ("DCTDecode".equals(filter)) {
            output.write(stream.getEncodedData());
        } else {
            BufferedImage img = toBufferedImage();
            javax.imageio.ImageIO.write(img, "PNG", output);
        }
    }

    /**
     * Converts this image to a {@link BufferedImage}.
     * <p>
     * Handles DeviceRGB, DeviceGray, DeviceCMYK, Indexed, and ICCBased color spaces.
     * </p>
     *
     * @return the converted BufferedImage
     * @throws IOException if conversion fails
     */
    public BufferedImage toBufferedImage() throws IOException {
        byte[] data = getDecodedData();
        int w = getWidth();
        int h = getHeight();
        int bpc = getBitsPerComponent();

        if (w <= 0 || h <= 0) {
            return new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        }

        ColorSpaceBase cs = getColorSpace();

        if (isImageMask()) {
            return createMaskImage(data, w, h);
        }

        int nc = cs.getNumberOfComponents();

        BufferedImage image;
        if (cs instanceof DeviceGray || (cs instanceof ICCBasedColorSpace && nc == 1)) {
            image = createGrayImage(data, w, h, bpc);
        } else if (cs instanceof DeviceCMYK || (cs instanceof ICCBasedColorSpace && nc == 4)) {
            // DCTDecodeFilter pre-converts CMYK JPEGs to RGB (using the JPEG's
            // embedded ICC profile, transparently handling Adobe-inverted CMYK).
            // Detect that case by payload size and route to the RGB path so we
            // don't double-convert RGB-as-CMYK and produce teal garbage.
            if (data.length == w * h * 3) {
                image = createRGBImage(data, w, h, bpc);
            } else {
                image = createCMYKImage(data, w, h);
            }
        } else if (cs instanceof IndexedColorSpace) {
            image = createIndexedImage(data, w, h, bpc, (IndexedColorSpace) cs);
        } else if (cs instanceof CalGrayColorSpace) {
            image = createCalGrayImage(data, w, h, bpc, (CalGrayColorSpace) cs);
        } else if (cs instanceof CalRGBColorSpace) {
            image = createCalRGBImage(data, w, h, bpc, (CalRGBColorSpace) cs);
        } else if (cs instanceof LabColorSpace) {
            image = createLabImage(data, w, h, bpc, (LabColorSpace) cs);
        } else if (cs instanceof SeparationColorSpace) {
            image = createSeparationImage(data, w, h, bpc, (SeparationColorSpace) cs);
        } else if (cs instanceof DeviceNColorSpace) {
            image = createDeviceNImage(data, w, h, bpc, (DeviceNColorSpace) cs);
        } else {
            // Default: DeviceRGB or ICCBased with 3 components
            image = createRGBImage(data, w, h, bpc);
        }
        return applySoftMaskIfPresent(image);
    }

    /**
     * Deletes this image from the parent XObject dictionary.
     * <p>
     * After deletion, the image resource name is removed and subsequent
     * references to it in content streams will fail to resolve.
     * </p>
     */
    public void delete() {
        if (xobjectDict != null && name != null) {
            xobjectDict.remove(COSName.of(name));
        }
    }

    /**
     * Replaces this image with data from an input stream.
     * <p>
     * Creates a new image stream from the provided data and stores it under
     * the same resource name in the parent XObject dictionary. If the data
     * begins with a JPEG SOI marker (0xFF 0xD8), the {@code /Filter} is set
     * to {@code /DCTDecode}.
     * </p>
     *
     * @param newImageStream the input stream containing the replacement image data
     * @throws IOException if reading from the stream fails
     */
    public void replace(InputStream newImageStream) throws IOException {
        if (xobjectDict == null || name == null) return;
        byte[] data = readAll(newImageStream);
        COSStream newStream = createImageStream(data);
        xobjectDict.set(COSName.of(name), newStream);
    }

    /**
     * Returns the underlying COS stream.
     *
     * @return the image stream
     */
    public COSStream getCOSStream() {
        return stream;
    }

    // ---- Private image creation helpers ----

    private BufferedImage createRGBImage(byte[] data, int w, int h, int bpc) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int bytesPerPixel = 3;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * bytesPerPixel;
                if (offset + 2 < data.length) {
                    int r = data[offset] & 0xFF;
                    int g = data[offset + 1] & 0xFF;
                    int b = data[offset + 2] & 0xFF;
                    img.setRGB(x, y, (0xFF << 24) | (r << 16) | (g << 8) | b);
                }
            }
        }
        return img;
    }

    private BufferedImage createGrayImage(byte[] data, int w, int h, int bpc) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        if (bpc == 8 && data.length >= w * h) {
            byte[] rasterData = new byte[w * h];
            System.arraycopy(data, 0, rasterData, 0, Math.min(data.length, w * h));
            img.getRaster().setDataElements(0, 0, w, h, rasterData);
        } else if (bpc == 1) {
            // 1-bit DeviceGray: ISO 32000-1:2008 §8.9.5.2 Table 90.
            // Default /Decode is [0 1]: raw bit 0 → color 0 (black, gray=0),
            // raw bit 1 → color 1 (white, gray=255).  /Decode [1 0] inverts.
            // (NOTE: the inverse /Decode interpretation only applies to non-mask
            // images — image masks are routed to createMaskImage above.)
            //
            // Pre-fill the raster to white before unpacking: lossy decoders
            // such as CCITTFaxDecode may legitimately produce fewer than
            // {@code rowBytes * h} bytes (e.g. when an EOFB marker truncates
            // a long all-white tail).  The PDF spec treats missing image
            // samples as the colour-space's default value — for DeviceGray
            // that is 0 in raw, mapped to gray 255 by the default Decode.
            boolean invertDecode = isDecodeReversed1Bit();
            int defaultGray = invertDecode ? 0 : 255;
            byte fill = (byte) defaultGray;
            byte[] base = new byte[w * h];
            if (defaultGray != 0) java.util.Arrays.fill(base, fill);
            img.getRaster().setDataElements(0, 0, w, h, base);

            int rowBytes = (w + 7) / 8;
            int rowsAvail = data.length / rowBytes;
            int hh = Math.min(h, rowsAvail);
            for (int y = 0; y < hh; y++) {
                for (int x = 0; x < w; x++) {
                    int bit = (data[y * rowBytes + (x >> 3)] >> (7 - (x & 7))) & 1;
                    if (invertDecode) bit ^= 1;
                    int gray = bit == 1 ? 255 : 0;
                    img.getRaster().setSample(x, y, 0, gray);
                }
            }
        } else if (bpc == 4) {
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int byteIndex = y * ((w + 1) / 2) + x / 2;
                    if (byteIndex < data.length) {
                        int nibble = (x % 2 == 0)
                                ? (data[byteIndex] >> 4) & 0x0F
                                : data[byteIndex] & 0x0F;
                        int gray = nibble * 255 / 15;
                        img.getRaster().setSample(x, y, 0, gray);
                    }
                }
            }
        }
        return img;
    }

    /**
     * Reads {@code /Decode} for a 1-bit image and tells whether it inverts
     * the default {@code [0 1]} mapping.  Returns {@code true} only when
     * the explicit array starts with {@code 1} (i.e. {@code /Decode [1 0]}).
     */
    private boolean isDecodeReversed1Bit() {
        org.aspose.pdf.engine.cos.COSBase decode = stream.get("Decode");
        if (!(decode instanceof org.aspose.pdf.engine.cos.COSArray)) return false;
        org.aspose.pdf.engine.cos.COSArray a = (org.aspose.pdf.engine.cos.COSArray) decode;
        if (a.size() < 1) return false;
        Object first = a.get(0);
        if (first instanceof org.aspose.pdf.engine.cos.COSInteger) {
            return ((org.aspose.pdf.engine.cos.COSInteger) first).intValue() == 1;
        }
        if (first instanceof org.aspose.pdf.engine.cos.COSFloat) {
            return Math.round(((org.aspose.pdf.engine.cos.COSFloat) first).floatValue()) == 1;
        }
        return false;
    }

    private BufferedImage createCMYKImage(byte[] data, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * 4;
                if (offset + 3 < data.length) {
                    double c = (data[offset] & 0xFF) / 255.0;
                    double m = (data[offset + 1] & 0xFF) / 255.0;
                    double yc = (data[offset + 2] & 0xFF) / 255.0;
                    double k = (data[offset + 3] & 0xFF) / 255.0;
                    img.setRGB(x, y, DeviceCMYK.INSTANCE.toRGBInt(c, m, yc, k));
                }
            }
        }
        return img;
    }

    private BufferedImage createIndexedImage(byte[] data, int w, int h, int bpc,
                                              IndexedColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ColorSpaceBase base = cs.getBase();
        int nc = base.getNumberOfComponents();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int index = getPixelIndex(data, w, x, y, bpc);
                double[] components = cs.lookupColor(index);
                int rgb;
                if (nc == 1) {
                    rgb = DeviceGray.INSTANCE.toRGBInt(components[0]);
                } else if (nc == 4) {
                    rgb = DeviceCMYK.INSTANCE.toRGBInt(
                            components[0], components[1], components[2], components[3]);
                } else {
                    rgb = DeviceRGB.INSTANCE.toRGBInt(
                            nc > 0 ? components[0] : 0,
                            nc > 1 ? components[1] : 0,
                            nc > 2 ? components[2] : 0);
                }
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private BufferedImage createMaskImage(byte[] data, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        int rowBytes = (w + 7) / 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int byteIndex = y * rowBytes + x / 8;
                if (byteIndex < data.length) {
                    int bit = (data[byteIndex] >> (7 - (x % 8))) & 1;
                    img.getRaster().setSample(x, y, 0, bit == 0 ? 255 : 0);
                }
            }
        }
        return img;
    }

    private BufferedImage applySoftMaskIfPresent(BufferedImage baseImage) throws IOException {
        COSBase smaskObj = resolveRef(stream.get("SMask"));
        if (!(smaskObj instanceof COSStream)) {
            return baseImage;
        }
        COSStream smaskStream = (COSStream) smaskObj;
        XImage softMask = new XImage(smaskStream, name + "_SMask", parser);
        BufferedImage maskImage = softMask.toBufferedImage();
        if (maskImage == null) {
            return baseImage;
        }
        // Apply the SMask's /Decode array (PDF §8.9.5.10). Default for
        // grayscale is [0 1] — pixel value linearly mapped to alpha 0..1
        // (0 = transparent, 1 = opaque). [1 0] inverts the polarity, which is
        // common for masks produced from a "darkness" plate. Without this we
        // misread bright (all-1) masks as fully opaque and end up overlaying
        // a 1×1 black "ink plate" on the entire page (PDFNEWNET_32411).
        boolean invert = false;
        COSBase decodeObj = resolveRef(smaskStream.get("Decode"));
        if (decodeObj instanceof COSArray) {
            COSArray dec = (COSArray) decodeObj;
            if (dec.size() >= 2) {
                double d0 = numAsDouble(dec.get(0), 0);
                double d1 = numAsDouble(dec.get(1), 1);
                if (d0 > d1) invert = true;
            }
        }
        return mergeSoftMask(baseImage, maskImage, invert);
    }

    private static double numAsDouble(COSBase b, double def) {
        if (b instanceof org.aspose.pdf.engine.cos.COSInteger)
            return ((org.aspose.pdf.engine.cos.COSInteger) b).intValue();
        if (b instanceof org.aspose.pdf.engine.cos.COSFloat)
            return ((org.aspose.pdf.engine.cos.COSFloat) b).doubleValue();
        return def;
    }

    private BufferedImage mergeSoftMask(BufferedImage baseImage, BufferedImage maskImage) {
        return mergeSoftMask(baseImage, maskImage, false);
    }

    private BufferedImage mergeSoftMask(BufferedImage baseImage, BufferedImage maskImage,
                                         boolean invertMask) {
        // The output should preserve the resolution of WHICHEVER input has more
        // detail. The previous implementation locked the result to the base
        // image's dimensions, which silently discarded a high-res mask paired
        // with a small (often 1×1 placeholder) base. PDFNEWNET_32411 paints a
        // 1×1 black image with a 2502×3228 1-bit text mask — sampling the mask
        // down to a single pixel collapsed the result to one fully-opaque
        // black pixel that then got stretched across the whole page, hiding
        // every layer drawn beneath it. Sampling the result at MAX(base, mask)
        // dimensions preserves the mask's text shapes.
        int width = Math.max(baseImage.getWidth(), maskImage.getWidth());
        int height = Math.max(baseImage.getHeight(), maskImage.getHeight());
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int baseW = baseImage.getWidth();
        int baseH = baseImage.getHeight();
        int maskW = maskImage.getWidth();
        int maskH = maskImage.getHeight();
        for (int y = 0; y < height; y++) {
            int by = (int) ((long) y * baseH / height);
            int my = (int) ((long) y * maskH / height);
            if (by >= baseH) by = baseH - 1;
            if (my >= maskH) my = maskH - 1;
            for (int x = 0; x < width; x++) {
                int bx = (int) ((long) x * baseW / width);
                int mx = (int) ((long) x * maskW / width);
                if (bx >= baseW) bx = baseW - 1;
                if (mx >= maskW) mx = maskW - 1;
                int rgb = baseImage.getRGB(bx, by);
                int maskRgb = maskImage.getRGB(mx, my);
                int alpha = maskRgb & 0xFF;
                if (invertMask) alpha = 255 - alpha;
                result.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }
        return result;
    }

    private BufferedImage createCalGrayImage(byte[] data, int w, int h, int bpc,
                                                CalGrayColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double maxVal = (1 << bpc) - 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                double gray = (idx < data.length) ? (data[idx] & 0xFF) / maxVal : 0;
                double[] rgb = cs.toRGB(gray);
                img.setRGB(x, y, toARGB(rgb));
            }
        }
        return img;
    }

    private BufferedImage createCalRGBImage(byte[] data, int w, int h, int bpc,
                                             CalRGBColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double maxVal = (1 << bpc) - 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * 3;
                if (offset + 2 < data.length) {
                    double r = (data[offset] & 0xFF) / maxVal;
                    double g = (data[offset + 1] & 0xFF) / maxVal;
                    double b = (data[offset + 2] & 0xFF) / maxVal;
                    double[] rgb = cs.toRGB(r, g, b);
                    img.setRGB(x, y, toARGB(rgb));
                }
            }
        }
        return img;
    }

    private BufferedImage createLabImage(byte[] data, int w, int h, int bpc,
                                          LabColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double[] range = cs.getRange();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * 3;
                if (offset + 2 < data.length) {
                    // L* is encoded as 0..100 mapped to 0..255
                    double lStar = (data[offset] & 0xFF) * 100.0 / 255.0;
                    // a* and b* are encoded as (value - min) / (max - min) * 255
                    double aStar = range[0] + (data[offset + 1] & 0xFF) * (range[1] - range[0]) / 255.0;
                    double bStar = range[2] + (data[offset + 2] & 0xFF) * (range[3] - range[2]) / 255.0;
                    double[] rgb = cs.toRGB(lStar, aStar, bStar);
                    img.setRGB(x, y, toARGB(rgb));
                }
            }
        }
        return img;
    }

    private BufferedImage createSeparationImage(byte[] data, int w, int h, int bpc,
                                                  SeparationColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double maxVal = (1 << bpc) - 1;
        ColorSpaceBase altCS = cs.getAlternateCS();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                double tint = (idx < data.length) ? (data[idx] & 0xFF) / maxVal : 0;
                double[] altComponents = cs.tintToAlternate(tint);
                img.setRGB(x, y, altComponentsToARGB(altComponents, altCS));
            }
        }
        return img;
    }

    private BufferedImage createDeviceNImage(byte[] data, int w, int h, int bpc,
                                               DeviceNColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int nc = cs.getNumberOfComponents();
        double maxVal = (1 << bpc) - 1;
        ColorSpaceBase altCS = cs.getAlternateCS();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int offset = (y * w + x) * nc;
                double[] tints = new double[nc];
                for (int c = 0; c < nc && offset + c < data.length; c++) {
                    tints[c] = (data[offset + c] & 0xFF) / maxVal;
                }
                double[] altComponents = cs.tintsToAlternate(tints);
                img.setRGB(x, y, altComponentsToARGB(altComponents, altCS));
            }
        }
        return img;
    }

    /**
     * Converts alternate color space components to ARGB packed int.
     */
    private int altComponentsToARGB(double[] components, ColorSpaceBase altCS) {
        int nc = altCS.getNumberOfComponents();
        if (nc == 1 && components.length >= 1) {
            return DeviceGray.INSTANCE.toRGBInt(components[0]);
        } else if (nc == 4 && components.length >= 4) {
            return DeviceCMYK.INSTANCE.toRGBInt(
                    components[0], components[1], components[2], components[3]);
        } else if (components.length >= 3) {
            return DeviceRGB.INSTANCE.toRGBInt(components[0], components[1], components[2]);
        } else if (components.length >= 1) {
            return DeviceGray.INSTANCE.toRGBInt(components[0]);
        }
        return 0xFF000000;
    }

    /**
     * Converts double[3] RGB (0..1) to packed ARGB int.
     */
    private static int toARGB(double[] rgb) {
        int r = (int) Math.round(Math.max(0, Math.min(1, rgb[0])) * 255);
        int g = (int) Math.round(Math.max(0, Math.min(1, rgb[1])) * 255);
        int b = (int) Math.round(Math.max(0, Math.min(1, rgb[2])) * 255);
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }

    private int getPixelIndex(byte[] data, int w, int x, int y, int bpc) {
        if (bpc == 8) {
            int idx = y * w + x;
            return idx < data.length ? (data[idx] & 0xFF) : 0;
        } else if (bpc == 4) {
            int byteIndex = y * ((w + 1) / 2) + x / 2;
            if (byteIndex >= data.length) return 0;
            return (x % 2 == 0) ? (data[byteIndex] >> 4) & 0x0F : data[byteIndex] & 0x0F;
        } else if (bpc == 1) {
            int rowBytes = (w + 7) / 8;
            int byteIndex = y * rowBytes + x / 8;
            if (byteIndex >= data.length) return 0;
            return (data[byteIndex] >> (7 - (x % 8))) & 1;
        } else if (bpc == 2) {
            int pixelsPerByte = 4;
            int byteIndex = y * ((w + pixelsPerByte - 1) / pixelsPerByte) + x / pixelsPerByte;
            if (byteIndex >= data.length) return 0;
            int shift = 6 - (x % pixelsPerByte) * 2;
            return (data[byteIndex] >> shift) & 0x03;
        }
        return 0;
    }

    private String getFilterName() {
        COSBase filter = stream.get("Filter");
        if (filter instanceof COSName) return ((COSName) filter).getName();
        if (filter instanceof COSArray) {
            COSArray arr = (COSArray) filter;
            if (arr.size() > 0) {
                COSBase last = arr.get(arr.size() - 1);
                if (last instanceof COSName) return ((COSName) last).getName();
            }
        }
        return null;
    }

    private COSBase resolveRef(COSBase val) throws IOException {
        if (val instanceof COSObjectReference) {
            return ((COSObjectReference) val).dereference();
        }
        return val;
    }

    /**
     * Reads all bytes from an input stream.
     */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /**
     * Build a spec-compliant {@code /XObject /Image} {@link COSStream} from raw
     * bytes in any of the formats supported by {@link javax.imageio.ImageIO}.
     * JPEG bytes (SOI {@code FF D8}) are stored verbatim with
     * {@code /Filter /DCTDecode}; everything else (PNG, BMP, GIF, …) is decoded
     * to RGB or grayscale via {@code ImageIO} and re-emitted as
     * {@code /FlateDecode}-compressed pixel data. The resulting stream carries
     * all of the entries the spec requires (ISO 32000-1:2008 §8.9.5 Table 89):
     * {@code /Type /XObject}, {@code /Subtype /Image}, {@code /Width},
     * {@code /Height}, {@code /ColorSpace}, {@code /BitsPerComponent},
     * {@code /Filter}.
     *
     * <p>Package-private so {@link XImageCollection#add(InputStream)} and
     * {@link Page#addStamp(ImageStamp)} (Stage 2) can share the same code
     * path.</p>
     *
     * @param data raw image bytes; must not be null or empty
     * @return a fully-specified Image XObject {@link COSStream}
     * @throws IOException if the bytes cannot be decoded as a recognised
     *         image format. The exception message contains "unsupported".
     */
    static COSStream createImageStream(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("unsupported (empty) image data");
        }
        COSStream newStream = new COSStream();
        newStream.set(COSName.TYPE, COSName.of("XObject"));
        newStream.set(COSName.SUBTYPE, COSName.of("Image"));

        if (isJpeg(data)) {
            BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) {
                throw new IOException("unsupported or unrecognised JPEG image data");
            }
            populateImageMetadata(newStream, image);
            newStream.setFilter(COSName.of("DCTDecode"));
            newStream.setEncodedData(data);
            return newStream;
        }

        BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("unsupported or unrecognised image format "
                    + "(first bytes: " + hexHeader(data) + ")");
        }
        populateImageMetadata(newStream, image);
        newStream.setFilter(COSName.of("FlateDecode"));
        newStream.setDecodedData(extractPixelBytes(image, isGray(image)));
        return newStream;
    }

    private static String hexHeader(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(8, data.length);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static boolean isJpeg(byte[] data) {
        return data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8;
    }

    private static void populateImageMetadata(COSStream stream, BufferedImage image) {
        boolean gray = isGray(image);
        stream.set(COSName.of("Width"), org.aspose.pdf.engine.cos.COSInteger.valueOf(image.getWidth()));
        stream.set(COSName.of("Height"), org.aspose.pdf.engine.cos.COSInteger.valueOf(image.getHeight()));
        stream.set(COSName.of("BitsPerComponent"), org.aspose.pdf.engine.cos.COSInteger.valueOf(8));
        stream.set(COSName.of("ColorSpace"), COSName.of(gray ? "DeviceGray" : "DeviceRGB"));
    }

    private static boolean isGray(BufferedImage image) {
        ColorModel colorModel = image.getColorModel();
        return colorModel != null && colorModel.getNumColorComponents() == 1;
    }

    private static byte[] extractPixelBytes(BufferedImage image, boolean gray) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] pixels = new byte[width * height * (gray ? 1 : 3)];
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (gray) {
                    pixels[p++] = (byte) ((r + g + b) / 3);
                } else {
                    pixels[p++] = (byte) r;
                    pixels[p++] = (byte) g;
                    pixels[p++] = (byte) b;
                }
            }
        }
        return pixels;
    }
}
