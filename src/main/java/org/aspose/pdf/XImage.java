package org.aspose.pdf;

import org.aspose.pdf.engine.colorspace.*;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
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
 * Wraps a PdfStream with {@code /Subtype /Image}. Provides access to image
 * properties (width, height, bits per component, color space) and decoded
 * pixel data. Supports saving to output streams and conversion to
 * {@link BufferedImage}.
 * </p>
 */
public class XImage {

    private static final Logger LOG = Logger.getLogger(XImage.class.getName());

    private final PdfStream stream;
    private String name;
    private final PDFParser parser;
    private PdfDictionary xobjectDict; // parent /XObject dictionary for renaming

    /**
     * Creates an XImage from an image XObject stream.
     *
     * @param stream the image XObject PdfStream
     * @param name   the resource name (e.g., "Im1")
     * @param parser the PDF parser for resolving indirect refs (may be null)
     */
    public XImage(PdfStream stream, String name, PDFParser parser) {
        this.stream = stream != null ? stream : new PdfStream();
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
            PdfBase val = xobjectDict.get(oldName);
            if (val != null) {
                xobjectDict.remove(PdfName.of(oldName));
                xobjectDict.set(PdfName.of(newName), val);
            }
        }
    }

    /**
     * Sets the parent /XObject dictionary reference (called by XImageCollection).
     */
    void setXObjectDictionary(PdfDictionary dict) {
        this.xobjectDict = dict;
    }

    /**
     * Returns the color space of this image.
     *
     * @return the color space
     * @throws IOException if resolution fails
     */
    public ColorSpaceBase getColorSpace() throws IOException {
        PdfBase cs = stream.get("ColorSpace");
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
        if ("DCTDecode".equals(filter) && stream.get("SMask") == null) {
            output.write(stream.getEncodedData());
        } else {
            BufferedImage img = toBufferedImage();
            // A soft-masked image (§11.6.5.2) carries its real appearance in
            // the /SMask alpha: e.g. a flat-black base whose line art lives
            // entirely in the mask. Compose the mask into PNG alpha so the
            // exported image looks like the rendered one (PDFNEWNET-34433 —
            // saving/replacing the base alone produced solid black charts).
            BufferedImage masked = composeSMaskAlpha(img);
            javax.imageio.ImageIO.write(masked != null ? masked : img, "PNG", output);
        }
    }

    /**
     * Returns an ARGB copy of {@code img} with the /SMask luminance as alpha,
     * or {@code null} when the image has no (readable) soft mask.
     */
    private BufferedImage composeSMaskAlpha(BufferedImage img) {
        try {
            PdfBase smaskVal = stream.get("SMask");
            if (smaskVal instanceof PdfObjectReference) {
                smaskVal = ((PdfObjectReference) smaskVal).dereference();
            }
            if (!(smaskVal instanceof PdfStream)) {
                return null;
            }
            XImage smask = new XImage((PdfStream) smaskVal, name + "_smask", parser);
            BufferedImage maskImg = smask.toBufferedImage();
            if (maskImg == null) {
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            double sx = (double) maskImg.getWidth() / w;
            double sy = (double) maskImg.getHeight() / h;
            for (int y = 0; y < h; y++) {
                int my = Math.min(maskImg.getHeight() - 1, (int) (y * sy));
                for (int x = 0; x < w; x++) {
                    int mx = Math.min(maskImg.getWidth() - 1, (int) (x * sx));
                    int alpha = maskImg.getRGB(mx, my) & 0xFF; // gray → alpha
                    out.setRGB(x, y, (alpha << 24) | (img.getRGB(x, y) & 0xFFFFFF));
                }
            }
            return out;
        } catch (Exception e) {
            LOG.fine(() -> "Could not compose /SMask alpha for save: " + e.getMessage());
            return null;
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
            image = createSeparationImage(maybeInvertAdobeInkJpeg(data), w, h, bpc,
                    (SeparationColorSpace) cs);
        } else if (cs instanceof DeviceNColorSpace) {
            image = createDeviceNImage(maybeInvertAdobeInkJpeg(data), w, h, bpc,
                    (DeviceNColorSpace) cs);
        } else {
            // Default: DeviceRGB or ICCBased with 3 components
            image = createRGBImage(data, w, h, bpc);
        }
        // Acrobat print-parity: on transparency pages Acrobat's flattener
        // treats UNTAGGED DeviceRGB image samples as AdobeRGB (1998) — see
        // RgbPrintShift. Applies only to an explicit /ColorSpace /DeviceRGB
        // (or Indexed over it): a missing /ColorSpace means JPX, whose
        // embedded codestream space Acrobat honours, and ICC/Cal spaces are
        // tagged. The thread-local flag is only ever set by the renderer, so
        // public-API image extraction is untouched.
        if (org.aspose.pdf.engine.colorspace.RgbPrintShift.active()
                && stream.get("ColorSpace") != null && isUntaggedRgb(cs)) {
            org.aspose.pdf.engine.colorspace.RgbPrintShift.shiftImage(image);
        }
        return applyStencilMaskIfPresent(applySoftMaskIfPresent(image));
    }

    /**
     * Inverts ink-plate JPEG samples that follow the Adobe convention.
     * Photoshop writes Separation/DeviceN plates through DCTDecode with the
     * APP14 "Adobe" marker and stores INK COVERAGE inverted (stored 255 = no
     * ink), exactly like its 4-band CMYK JPEGs (see DCTDecodeFilter). Reading
     * the samples as direct tint painted corpus 33408's dark PANTONE 378
     * illustrations as bright green negatives. Non-Adobe JPEGs and non-DCT
     * filters are returned unchanged.
     */
    private byte[] maybeInvertAdobeInkJpeg(byte[] data) {
        try {
            PdfBase filter = resolveRef(stream.get("Filter"));
            boolean dct = false;
            if (filter instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
                dct = "DCTDecode".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) filter).getName());
            } else if (filter instanceof PdfArray) {
                PdfArray fa = (PdfArray) filter;
                for (int i = 0; i < fa.size(); i++) {
                    PdfBase f = resolveRef(fa.get(i));
                    if (f instanceof org.aspose.pdf.engine.pdfobjects.PdfName
                            && "DCTDecode".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) f).getName())) {
                        dct = true;
                    }
                }
            }
            if (!dct || !hasAdobeApp14(getEncodedData())) return data;
            byte[] inverted = new byte[data.length];
            for (int i = 0; i < data.length; i++) {
                inverted[i] = (byte) (255 - (data[i] & 0xFF));
            }
            return inverted;
        } catch (Exception e) {
            return data;
        }
    }

    /** Scans a JPEG bitstream's marker segments for the Adobe APP14 marker. */
    private static boolean hasAdobeApp14(byte[] jpeg) {
        if (jpeg == null) return false;
        int i = 2; // skip SOI
        while (i + 4 < jpeg.length && (jpeg[i] & 0xFF) == 0xFF) {
            int marker = jpeg[i + 1] & 0xFF;
            if (marker == 0xDA) break; // start of scan — no more headers
            int len = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
            if (marker == 0xEE && len >= 7
                    && jpeg[i + 4] == 'A' && jpeg[i + 5] == 'd' && jpeg[i + 6] == 'o'
                    && jpeg[i + 7] == 'b' && jpeg[i + 8] == 'e') {
                return true;
            }
            i += 2 + len;
        }
        return false;
    }

    /** True for plain DeviceRGB or Indexed with a plain DeviceRGB base. */
    private static boolean isUntaggedRgb(ColorSpaceBase cs) {
        if (cs instanceof DeviceRGB) return true;
        return cs instanceof IndexedColorSpace
                && ((IndexedColorSpace) cs).getBase() instanceof DeviceRGB;
    }

    /**
     * Applies an explicit stencil mask (/Mask referencing an /ImageMask true
     * image, ISO 32000-1 §8.9.6.4): mask sample 1 hides the base pixel,
     * 0 leaves it painted; /Decode [1 0] flips the polarity. MRC-compressed
     * scans rely on this — the near-black text "foreground" plate is meant to
     * show only through its glyph stencil; painting it unmasked blacks out
     * the whole page (corpus 39728). Color-key /Mask arrays (§8.9.6.3) are
     * not handled here.
     */
    private BufferedImage applyStencilMaskIfPresent(BufferedImage baseImage) throws IOException {
        if (baseImage == null) return null;
        PdfBase maskObj = resolveRef(stream.get("Mask"));
        if (!(maskObj instanceof PdfStream)) {
            return baseImage;
        }
        PdfStream maskStream = (PdfStream) maskObj;
        if (!maskStream.getBoolean("ImageMask", false)) {
            return baseImage;
        }
        int mw = maskStream.getInt("Width", 0);
        int mh = maskStream.getInt("Height", 0);
        if (mw <= 0 || mh <= 0) return baseImage;
        byte[] bits;
        try {
            bits = maskStream.getDecodedData();
        } catch (IOException e) {
            LOG.fine(() -> "Stencil /Mask decode failed for " + name + ": " + e.getMessage());
            return baseImage;
        }
        if (bits == null) return baseImage;
        boolean invert = false;
        PdfBase decodeObj = resolveRef(maskStream.get("Decode"));
        if (decodeObj instanceof PdfArray) {
            PdfArray dec = (PdfArray) decodeObj;
            if (dec.size() >= 2 && numAsDouble(dec.get(0), 0) > numAsDouble(dec.get(1), 1)) {
                invert = true;
            }
        }
        int rowBytes = (mw + 7) / 8;
        int bw = baseImage.getWidth();
        int bh = baseImage.getHeight();
        BufferedImage out = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bh; y++) {
            int my = (int) ((long) y * mh / bh);
            for (int x = 0; x < bw; x++) {
                int mx = (int) ((long) x * mw / bw);
                int byteIndex = my * rowBytes + (mx >> 3);
                int bit = byteIndex < bits.length ? (bits[byteIndex] >> (7 - (mx & 7))) & 1 : 0;
                if (invert) bit ^= 1;
                if (bit == 0) {
                    out.setRGB(x, y, baseImage.getRGB(x, y)); // keeps base alpha
                }
                // bit 1 → masked out: leave transparent
            }
        }
        return out;
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
            xobjectDict.remove(PdfName.of(name));
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
        PdfStream newStream = createImageStream(data);
        xobjectDict.set(PdfName.of(name), newStream);
    }

    /**
     * Returns the underlying PDF stream.
     *
     * @return the image stream
     */
    public PdfStream getPdfStream() {
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
        } else if (bpc == 2) {
            // 2-bit DeviceGray (§8.9.4, Table 89 allows 1/2/4/8/16). Falling
            // through left the raster all-zero — a solid BLACK page for
            // full-page scans (corpus 60410).
            int rowBytes = (w * 2 + 7) / 8;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int byteIndex = y * rowBytes + (x >> 2);
                    if (byteIndex >= data.length) break;
                    int v = (data[byteIndex] >> (6 - 2 * (x & 3))) & 3;
                    img.getRaster().setSample(x, y, 0, v * 255 / 3);
                }
            }
        } else if (bpc == 16) {
            // 16-bit samples: use the high byte.
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int byteIndex = (y * w + x) * 2;
                    if (byteIndex >= data.length) break;
                    img.getRaster().setSample(x, y, 0, data[byteIndex] & 0xFF);
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
        org.aspose.pdf.engine.pdfobjects.PdfBase decode = stream.get("Decode");
        if (!(decode instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return false;
        org.aspose.pdf.engine.pdfobjects.PdfArray a = (org.aspose.pdf.engine.pdfobjects.PdfArray) decode;
        if (a.size() < 1) return false;
        Object first = a.get(0);
        if (first instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) first).intValue() == 1;
        }
        if (first instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
            return Math.round(((org.aspose.pdf.engine.pdfobjects.PdfFloat) first).floatValue()) == 1;
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
                    img.setRGB(x, y, org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(c, m, yc, k));
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
                if (base instanceof DeviceGray) {
                    rgb = DeviceGray.INSTANCE.toRGBInt(components[0]);
                } else if (base instanceof DeviceCMYK
                        || (base instanceof ICCBasedColorSpace && nc == 4)) {
                    rgb = org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(
                            components[0], components[1], components[2], components[3]);
                } else if (base instanceof DeviceRGB
                        || (base instanceof ICCBasedColorSpace && nc == 3)) {
                    rgb = DeviceRGB.INSTANCE.toRGBInt(
                            nc > 0 ? components[0] : 0,
                            nc > 1 ? components[1] : 0,
                            nc > 2 ? components[2] : 0);
                } else {
                    // Separation/DeviceN/Lab/CalRGB base: palette entries are
                    // BASE-space components (e.g. duotone ink tints) and must
                    // go through the base's own conversion. Feeding a 2-ink
                    // DeviceN duotone's tints into DeviceRGB as (R,G,0)
                    // painted corpus 33408's PANTONE 378+2746 plates neon
                    // green instead of the mixed neutral dark.
                    rgb = base.toRGBInt(components);
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
        PdfBase smaskObj = resolveRef(stream.get("SMask"));
        if (!(smaskObj instanceof PdfStream)) {
            return baseImage;
        }
        PdfStream smaskStream = (PdfStream) smaskObj;
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
        // 1-bpc masks already had their /Decode applied while building the
        // gray image (createGrayImage/isDecodeReversed1Bit) — inverting here
        // again would cancel it out: the MRC text masks of PDFNEWNET-32411 /
        // PdfUaCompliance part-29 (JBIG2, /Decode [1 0]) came out with the
        // page-sized black ink plate fully OPAQUE (whole page black).
        if (smaskStream.getInt("BitsPerComponent", 8) != 1) {
            PdfBase decodeObj = resolveRef(smaskStream.get("Decode"));
            if (decodeObj instanceof PdfArray) {
                PdfArray dec = (PdfArray) decodeObj;
                if (dec.size() >= 2) {
                    double d0 = numAsDouble(dec.get(0), 0);
                    double d1 = numAsDouble(dec.get(1), 1);
                    if (d0 > d1) invert = true;
                }
            }
        }
        // /Matte (§11.6.5.3): the base image samples are PREMULTIPLIED
        // against this backdrop color — un-premultiply while merging, or a
        // light texture stored against a black matte at α≈0.35 paints ~50
        // units too dark (corpus PDFNEWNET-30063-2 washi background: ours
        // 193 vs Acrobat 243). Gray (1) mattes are replicated to RGB; CMYK
        // mattes are left unhandled (rare, needs the source-space transform).
        int[] matte = null;
        PdfBase matteObj = resolveRef(smaskStream.get("Matte"));
        if (matteObj instanceof PdfArray) {
            PdfArray ma = (PdfArray) matteObj;
            if (ma.size() == 1 || ma.size() == 3) {
                matte = new int[3];
                for (int i = 0; i < 3; i++) {
                    double v = numAsDouble(ma.get(Math.min(i, ma.size() - 1)), 0);
                    matte[i] = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
                }
            }
        }
        return mergeSoftMask(baseImage, maskImage, invert, matte);
    }

    private static double numAsDouble(PdfBase b, double def) {
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger)
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) b).intValue();
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat)
            return ((org.aspose.pdf.engine.pdfobjects.PdfFloat) b).doubleValue();
        return def;
    }

    private BufferedImage mergeSoftMask(BufferedImage baseImage, BufferedImage maskImage) {
        return mergeSoftMask(baseImage, maskImage, false, null);
    }

    private BufferedImage mergeSoftMask(BufferedImage baseImage, BufferedImage maskImage,
                                         boolean invertMask, int[] matte) {
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
        // Read mask samples from the raster, NOT via getRGB(): a decoded
        // DeviceGray SMask is TYPE_BYTE_GRAY, whose color space is LINEAR
        // gray — getRGB() color-converts it to sRGB, i.e. applies a gamma
        // that inflates every alpha (mask sample 6 became alpha 42; the
        // WikiLeaks corpus watermark printed ~7x too strong).
        java.awt.image.Raster maskRaster =
                maskImage.getRaster().getNumBands() == 1 ? maskImage.getRaster() : null;
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
                int alpha = maskRaster != null
                        ? maskRaster.getSample(mx, my, 0)
                        : maskImage.getRGB(mx, my) & 0xFF;
                if (invertMask) alpha = 255 - alpha;
                if (matte != null && alpha > 0 && alpha < 255) {
                    // Un-premultiply: c' = matte + (c - matte)/α (§11.6.5.3).
                    int r = matte[0] + (((rgb >> 16 & 0xFF) - matte[0]) * 255) / alpha;
                    int g = matte[1] + (((rgb >> 8 & 0xFF) - matte[1]) * 255) / alpha;
                    int b = matte[2] + (((rgb & 0xFF) - matte[2]) * 255) / alpha;
                    rgb = (Math.max(0, Math.min(255, r)) << 16)
                            | (Math.max(0, Math.min(255, g)) << 8)
                            | Math.max(0, Math.min(255, b));
                }
                result.setRGB(x, y, (alpha << 24) | (rgb & 0x00FFFFFF));
            }
        }
        return result;
    }

    private BufferedImage createCalGrayImage(byte[] data, int w, int h, int bpc,
                                                CalGrayColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int maxVal = (1 << bpc) - 1;
        // Sub-byte samples are bit-packed with byte-aligned rows (§8.9.3):
        // the old byte-per-pixel read ran off the data after the first rows
        // and painted a 1-bit CalGray scan solid black (corpus UserGuide
        // p379-384). Same LUT approach as createSeparationImage.
        int[] lut = new int[maxVal + 1];
        for (int v = 0; v <= maxVal; v++) {
            lut[v] = toARGB(cs.toRGB(v / (double) maxVal));
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, lut[getPixelIndex(data, w, x, y, bpc) & maxVal]);
            }
        }
        return img;
    }

    private BufferedImage createCalRGBImage(byte[] data, int w, int h, int bpc,
                                             CalRGBColorSpace cs) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        double maxVal = (1 << bpc) - 1;
        int rowBytes = (w * 3 * bpc + 7) / 8; // byte-aligned rows (§8.9.3)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double r, g, b;
                if (bpc == 8) {
                    int offset = (y * w + x) * 3;
                    if (offset + 2 >= data.length) continue;
                    r = (data[offset] & 0xFF) / maxVal;
                    g = (data[offset + 1] & 0xFF) / maxVal;
                    b = (data[offset + 2] & 0xFF) / maxVal;
                } else {
                    r = readSample(data, y * rowBytes, (x * 3) * bpc, bpc) / maxVal;
                    g = readSample(data, y * rowBytes, (x * 3 + 1) * bpc, bpc) / maxVal;
                    b = readSample(data, y * rowBytes, (x * 3 + 2) * bpc, bpc) / maxVal;
                }
                double[] rgb = cs.toRGB(r, g, b);
                img.setRGB(x, y, toARGB(rgb));
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
        int maxVal = (1 << bpc) - 1;
        ColorSpaceBase altCS = cs.getAlternateCS();
        // Sub-byte samples are bit-packed with byte-aligned rows (§8.9.3) —
        // reading a byte per pixel painted a 1-bit /Separation /Black plate
        // white past the first rows (corpus 35751_2 hanger panel). Samples
        // take only 2^bpc distinct values, so precompute the tint→ARGB map
        // instead of running the tint transform per pixel.
        int[] lut = new int[maxVal + 1];
        for (int v = 0; v <= maxVal; v++) {
            lut[v] = altComponentsToARGB(cs.tintToAlternate(v / (double) maxVal), altCS);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, lut[getPixelIndex(data, w, x, y, bpc) & maxVal]);
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
        // Rows are byte-aligned; samples are packed component-major within
        // the row for any bpc (§8.9.3) — the old byte-per-sample read broke
        // every sub-byte DeviceN image the same way as Separation.
        int rowBytes = (w * nc * bpc + 7) / 8;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double[] tints = new double[nc];
                for (int c = 0; c < nc; c++) {
                    tints[c] = readSample(data, y * rowBytes, (x * nc + c) * bpc, bpc) / maxVal;
                }
                double[] altComponents = cs.tintsToAlternate(tints);
                img.setRGB(x, y, altComponentsToARGB(altComponents, altCS));
            }
        }
        return img;
    }

    /** Reads one bpc-bit sample at the given bit offset within a row. */
    private static int readSample(byte[] data, int rowByteStart, int bitOffset, int bpc) {
        int v = 0;
        int byteIndex = rowByteStart + (bitOffset >> 3);
        int bit = bitOffset & 7;
        for (int i = 0; i < bpc; i++) {
            if (byteIndex >= data.length) return v << (bpc - i);
            v = (v << 1) | ((data[byteIndex] >> (7 - bit)) & 1);
            if (++bit == 8) { bit = 0; byteIndex++; }
        }
        return v;
    }

    /**
     * Converts alternate color space components to ARGB packed int.
     */
    private int altComponentsToARGB(double[] components, ColorSpaceBase altCS) {
        int nc = altCS.getNumberOfComponents();
        if (nc == 1 && components.length >= 1) {
            return DeviceGray.INSTANCE.toRGBInt(components[0]);
        } else if (nc == 4 && components.length >= 4) {
            return org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(
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
        PdfBase filter = stream.get("Filter");
        if (filter instanceof PdfName) return ((PdfName) filter).getName();
        if (filter instanceof PdfArray) {
            PdfArray arr = (PdfArray) filter;
            if (arr.size() > 0) {
                PdfBase last = arr.get(arr.size() - 1);
                if (last instanceof PdfName) return ((PdfName) last).getName();
            }
        }
        return null;
    }

    private PdfBase resolveRef(PdfBase val) throws IOException {
        if (val instanceof PdfObjectReference) {
            return ((PdfObjectReference) val).dereference();
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
     * Build a spec-compliant {@code /XObject /Image} {@link PdfStream} from raw
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
     * @return a fully-specified Image XObject {@link PdfStream}
     * @throws IOException if the bytes cannot be decoded as a recognised
     *         image format. The exception message contains "unsupported".
     */
    public static PdfStream createImageStream(byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("unsupported (empty) image data");
        }
        PdfStream newStream = new PdfStream();
        newStream.set(PdfName.TYPE, PdfName.of("XObject"));
        newStream.set(PdfName.SUBTYPE, PdfName.of("Image"));

        if (isJpeg(data)) {
            BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) {
                throw new IOException("unsupported or unrecognised JPEG image data");
            }
            populateImageMetadata(newStream, image);
            newStream.setFilter(PdfName.of("DCTDecode"));
            newStream.setEncodedData(data);
            return newStream;
        }

        BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(data));
        if (image == null) {
            throw new IOException("unsupported or unrecognised image format "
                    + "(first bytes: " + hexHeader(data) + ")");
        }
        populateImageMetadata(newStream, image);
        newStream.setFilter(PdfName.of("FlateDecode"));
        newStream.setDecodedData(extractPixelBytes(image, isGray(image)));
        // A PNG with an alpha channel round-trips through a /SMask (§11.6.5.2)
        // so transparency survives the replace (PDFNEWNET-34433: charts whose
        // whole line art lives in the soft mask).
        if (image.getColorModel() != null && image.getColorModel().hasAlpha()) {
            PdfStream smask = new PdfStream();
            smask.set(PdfName.TYPE, PdfName.of("XObject"));
            smask.set(PdfName.SUBTYPE, PdfName.of("Image"));
            smask.set(PdfName.of("Width"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(image.getWidth()));
            smask.set(PdfName.of("Height"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(image.getHeight()));
            smask.set(PdfName.of("BitsPerComponent"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(8));
            smask.set(PdfName.of("ColorSpace"), PdfName.of("DeviceGray"));
            smask.setFilter(PdfName.of("FlateDecode"));
            smask.setDecodedData(extractAlphaBytes(image));
            newStream.set(PdfName.of("SMask"), smask);
        }
        return newStream;
    }

    /** One 8-bit alpha sample per pixel, row-major. */
    private static byte[] extractAlphaBytes(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] alpha = new byte[width * height];
        int p = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                alpha[p++] = (byte) ((image.getRGB(x, y) >>> 24) & 0xFF);
            }
        }
        return alpha;
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

    private static void populateImageMetadata(PdfStream stream, BufferedImage image) {
        boolean gray = isGray(image);
        stream.set(PdfName.of("Width"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(image.getWidth()));
        stream.set(PdfName.of("Height"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(image.getHeight()));
        stream.set(PdfName.of("BitsPerComponent"), org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(8));
        stream.set(PdfName.of("ColorSpace"), PdfName.of(gray ? "DeviceGray" : "DeviceRGB"));
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
