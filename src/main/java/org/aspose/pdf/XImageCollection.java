package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Collection of image XObjects from a page's /XObject resource dictionary.
 * <p>
 * Wraps the /XObject sub-dictionary, filtering to entries with
 * {@code /Subtype /Image}. Provides 1-based indexed access (Aspose convention).
 * </p>
 */
public class XImageCollection implements Iterable<XImage> {

    private static final Logger LOG = Logger.getLogger(XImageCollection.class.getName());

    private static final PdfName XOBJECT = PdfName.of("XObject");
    private static final PdfName PATTERN = PdfName.of("Pattern");
    private static final PdfName RESOURCES = PdfName.of("Resources");

    private final PdfDictionary resourcesDict;
    private final PdfDictionary xobjectDict;
    private final PDFParser parser;
    private List<XImage> images;

    /**
     * Creates an XImageCollection from an /XObject dictionary.
     *
     * @param xobjectDict the /XObject sub-dictionary
     * @param parser      the PDF parser (may be null)
     */
    public XImageCollection(PdfDictionary resourcesDict, PdfDictionary xobjectDict, PDFParser parser) {
        this.resourcesDict = resourcesDict;
        this.xobjectDict = xobjectDict;
        this.parser = parser;
    }

    /**
     * Creates an XImageCollection from an /XObject dictionary.
     *
     * @param xobjectDict the /XObject sub-dictionary
     * @param parser the PDF parser (may be null)
     */
    public XImageCollection(PdfDictionary xobjectDict, PDFParser parser) {
        this(synthesizeResources(xobjectDict), xobjectDict, parser);
    }

    private static PdfDictionary synthesizeResources(PdfDictionary xobjectDict) {
        PdfDictionary resources = new PdfDictionary();
        if (xobjectDict != null) {
            resources.set(XOBJECT, xobjectDict);
        }
        return resources;
    }

    /**
     * Returns the image at the given 1-based index.
     *
     * @param index the 1-based index
     * @return the XImage
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public XImage get(int index) {
        ensureLoaded();
        if (index < 1 || index > images.size()) {
            throw new IndexOutOfBoundsException(
                    "Image index " + index + " out of range [1, " + images.size() + "]");
        }
        return images.get(index - 1);
    }

    /**
     * Returns the image by resource name (e.g., "Im1").
     *
     * @param name the resource name
     * @return the XImage, or null if not found
     */
    public XImage get(String name) {
        ensureLoaded();
        for (XImage img : images) {
            if (name.equals(img.getName())) {
                return img;
            }
        }
        return null;
    }

    /**
     * Returns the number of images.
     *
     * @return the image count
     */
    public int getCount() {
        ensureLoaded();
        return images.size();
    }

    /**
     * Returns the number of images (alias for {@link #getCount()}).
     *
     * @return the image count
     */
    public int size() {
        return getCount();
    }

    /**
     * Returns an array of all image resource names in this collection.
     *
     * @return array of names
     */
    public String[] getNames() {
        ensureLoaded();
        java.util.List<String> names = new java.util.ArrayList<>();
        for (XImage img : images) {
            if (img != null && img.getName() != null) {
                names.add(img.getName());
            }
        }
        return names.toArray(new String[0]);
    }

    @Override
    public Iterator<XImage> iterator() {
        ensureLoaded();
        return images.iterator();
    }

    /**
     * Adds an image from an input stream to this collection. The bytes may be
     * JPEG (stored verbatim with {@code /Filter /DCTDecode}) or any format
     * decodable by {@link javax.imageio.ImageIO} — PNG, BMP, GIF — in which
     * case the image is decoded to RGB/grayscale and re-emitted as
     * {@code /FlateDecode}-compressed pixels. The resulting Image XObject
     * carries every entry required by ISO 32000-1:2008 §8.9.5 Table 89
     * ({@code /Type /XObject}, {@code /Subtype /Image}, {@code /Width},
     * {@code /Height}, {@code /ColorSpace}, {@code /BitsPerComponent},
     * {@code /Filter}). Animation in GIF inputs is lost — only the first
     * frame is embedded.
     *
     * @param imageStream the image data (JPEG, PNG, BMP, GIF)
     * @throws IOException if reading the stream fails or the bytes are not a
     *                     recognised image format
     */
    public void add(InputStream imageStream) throws IOException {
        byte[] data = readAll(imageStream);
        String name = "Im" + (getCount() + 1);
        PdfStream imgStream = XImage.createImageStream(data);
        xobjectDict.set(PdfName.of(name), imgStream);
        invalidateCache();
    }

    /**
     * Deletes the image at the given 1-based index from the parent /XObject dictionary.
     *
     * @param index the 1-based index
     * @throws IndexOutOfBoundsException if index is out of range
     */
    public void delete(int index) {
        XImage image = get(index);
        image.delete();
        invalidateCache();
    }

    /**
     * Deletes ALL images from the parent {@code /XObject} dictionary
     * (Aspose.PDF {@code XImageCollection.Clear()}).
     */
    public void clear() {
        for (int i = getCount(); i >= 1; i--) {
            delete(i);
        }
    }

    /**
     * Deletes the image with the given resource name.
     *
     * @param name the image resource name
     */
    public void delete(String name) {
        XImage image = get(name);
        if (image != null) {
            image.delete();
            invalidateCache();
        }
    }

    /**
     * Replaces the image at the given 1-based index with a new image.
     *
     * @param index          the 1-based index
     * @param newImageStream the replacement image stream
     * @throws IOException if reading or replacing image data fails
     */
    public void replace(int index, InputStream newImageStream) throws IOException {
        XImage image = get(index);
        image.replace(newImageStream);
        invalidateCache();
    }

    /**
     * Replaces the image with the given resource name with a new image.
     *
     * @param name           the image resource name
     * @param newImageStream the replacement image stream
     * @throws IOException if reading or replacing image data fails
     */
    public void replace(String name, InputStream newImageStream) throws IOException {
        XImage image = get(name);
        if (image == null) {
            throw new IOException("Image not found: " + name);
        }
        image.replace(newImageStream);
        invalidateCache();
    }

    private void ensureLoaded() {
        if (images != null) return;
        images = new ArrayList<>();
        collectImagesFromResources(resourcesDict, new java.util.IdentityHashMap<>());
        LOG.fine(() -> "XImageCollection loaded: " + images.size() + " images");
    }

    private void collectImagesFromResources(PdfDictionary resources,
                                            java.util.IdentityHashMap<PdfDictionary, Boolean> visited) {
        if (resources == null || visited.put(resources, Boolean.TRUE) != null) {
            return;
        }
        PdfBase xObjectsBase = resources.get(XOBJECT);
        PdfBase resolvedXObjects = resolveRef(xObjectsBase);
        if (resolvedXObjects instanceof PdfDictionary) {
            collectImagesFromXObjectDictionary((PdfDictionary) resolvedXObjects, visited);
        }
        PdfBase patternsBase = resources.get(PATTERN);
        PdfBase resolvedPatterns = resolveRef(patternsBase);
        if (resolvedPatterns instanceof PdfDictionary) {
            collectImagesFromPatternDictionary((PdfDictionary) resolvedPatterns, visited);
        }
    }

    private void collectImagesFromXObjectDictionary(PdfDictionary dictionary,
                                                    java.util.IdentityHashMap<PdfDictionary, Boolean> visited) {
        for (PdfName key : dictionary.keySet()) {
            PdfBase val = resolveRef(dictionary.get(key));
            if (!(val instanceof PdfStream)) {
                continue;
            }
            PdfStream stream = (PdfStream) val;
            String subtype = stream.getNameAsString("Subtype");
            if ("Image".equals(subtype)) {
                XImage img = new XImage(stream, key.getName(), parser);
                img.setXObjectDictionary(dictionary);
                images.add(img);
            } else if ("Form".equals(subtype)) {
                PdfBase nestedResources = resolveRef(stream.get(RESOURCES));
                if (nestedResources instanceof PdfDictionary) {
                    collectImagesFromResources((PdfDictionary) nestedResources, visited);
                }
            }
        }
    }

    private void collectImagesFromPatternDictionary(PdfDictionary patternDict,
                                                    java.util.IdentityHashMap<PdfDictionary, Boolean> visited) {
        for (PdfName key : patternDict.keySet()) {
            PdfBase val = resolveRef(patternDict.get(key));
            if (!(val instanceof PdfStream)) {
                continue;
            }
            PdfStream patternStream = (PdfStream) val;
            PdfBase nestedResources = resolveRef(patternStream.get(RESOURCES));
            if (nestedResources instanceof PdfDictionary) {
                collectImagesFromResources((PdfDictionary) nestedResources, visited);
            }
        }
    }

    private void invalidateCache() {
        images = null;
    }

    private PdfBase resolveRef(PdfBase val) {
        if (val instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) val).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference XObject: " + e.getMessage());
                return null;
            }
        }
        return val;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
