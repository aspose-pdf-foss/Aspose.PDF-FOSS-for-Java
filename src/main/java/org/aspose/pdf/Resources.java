package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Wraps a PDF resource dictionary (ISO 32000-1:2008, §7.8.3).
 * <p>
 * A resource dictionary maps resource names to objects required for rendering
 * page content: fonts, XObjects, graphics states, color spaces, patterns,
 * shadings, and properties. Each getter lazily retrieves the corresponding
 * sub-dictionary and dereferences indirect object references.
 * </p>
 */
public class Resources {

    private static final Logger LOG = Logger.getLogger(Resources.class.getName());

    private static final COSName FONT = COSName.of("Font");
    private static final COSName XOBJECT = COSName.of("XObject");
    private static final COSName EXT_G_STATE = COSName.of("ExtGState");
    private static final COSName COLOR_SPACE = COSName.of("ColorSpace");
    private static final COSName PATTERN = COSName.of("Pattern");
    private static final COSName SHADING = COSName.of("Shading");
    private static final COSName PROPERTIES = COSName.of("Properties");

    private final COSDictionary dict;
    private final PDFParser parser;

    /**
     * Creates a Resources wrapper around the given COS dictionary.
     *
     * @param dict the /Resources dictionary from a page or form XObject
     * @throws IllegalArgumentException if dict is null
     */
    public Resources(COSDictionary dict) {
        this(dict, null);
    }

    /**
     * Creates a Resources wrapper with a PDF parser for resolving indirect references.
     *
     * @param dict   the /Resources dictionary
     * @param parser the PDF parser (may be null)
     * @throws IllegalArgumentException if dict is null
     */
    public Resources(COSDictionary dict, PDFParser parser) {
        if (dict == null) {
            throw new IllegalArgumentException("Resources dictionary must not be null");
        }
        this.dict = dict;
        this.parser = parser;
        LOG.fine(() -> "Resources created with " + dict.size() + " entries");
    }

    /**
     * Returns the /Font sub-dictionary, or null if absent.
     *
     * @return the font dictionary, or null
     */
    public COSDictionary getFonts() {
        return getSubDictionary(FONT);
    }

    /**
     * Returns the /XObject sub-dictionary, or null if absent.
     *
     * @return the XObject dictionary, or null
     */
    public COSDictionary getXObjects() {
        return getSubDictionary(XOBJECT);
    }

    /**
     * Returns the /ExtGState sub-dictionary, or null if absent.
     *
     * @return the extended graphics state dictionary, or null
     */
    public COSDictionary getExtGState() {
        return getSubDictionary(EXT_G_STATE);
    }

    /**
     * Returns the /ColorSpace sub-dictionary, or null if absent.
     *
     * @return the color space dictionary, or null
     */
    public COSDictionary getColorSpaces() {
        return getSubDictionary(COLOR_SPACE);
    }

    /**
     * Returns the /Pattern sub-dictionary, or null if absent.
     *
     * @return the pattern dictionary, or null
     */
    public COSDictionary getPatterns() {
        return getSubDictionary(PATTERN);
    }

    /**
     * Returns the /Shading sub-dictionary, or null if absent.
     *
     * @return the shading dictionary, or null
     */
    public COSDictionary getShadings() {
        return getSubDictionary(SHADING);
    }

    /**
     * Returns the /Properties sub-dictionary, or null if absent.
     *
     * @return the properties dictionary, or null
     */
    public COSDictionary getProperties() {
        return getSubDictionary(PROPERTIES);
    }

    /**
     * Returns the collection of image XObjects from /XObject.
     *
     * @return the image collection, or null if no /XObject dictionary
     */
    public XImageCollection getImages() {
        COSDictionary xobjects = getXObjects();
        if (xobjects == null) {
            // Lazy-create /XObject dictionary for new pages
            xobjects = new COSDictionary();
            dict.set(COSName.of("XObject"), xobjects);
        }
        return new XImageCollection(dict, xobjects, parser);
    }

    /**
     * Returns the collection of Form XObjects from /XObject (ISO 32000-1:2008, §8.10).
     * <p>The collection is a live view: entries added to the underlying /XObject
     * dictionary with {@code /Subtype /Form} appear on subsequent access. Lazily
     * creates the /XObject sub-dictionary if absent.</p>
     *
     * @return the form collection (never null; may be empty)
     */
    public XFormCollection getForms() {
        COSDictionary xobjects = getXObjects();
        if (xobjects == null) {
            xobjects = new COSDictionary();
            dict.set(XOBJECT, xobjects);
        }
        return new XFormCollection(xobjects, parser);
    }

    /**
     * Returns the underlying COS dictionary.
     *
     * @return the raw COS dictionary
     */
    public COSDictionary getCOSDictionary() {
        return dict;
    }

    /**
     * Retrieves a sub-dictionary by key, dereferencing indirect references if needed.
     *
     * @param key the dictionary key
     * @return the sub-dictionary, or null if absent or not a dictionary
     */
    private COSDictionary getSubDictionary(COSName key) {
        COSBase value = dict.get(key);
        if (value == null) {
            return null;
        }
        // Dereference indirect object references
        COSBase resolved = value;
        if (resolved instanceof COSObjectReference) {
            try {
                resolved = ((COSObjectReference) resolved).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference " + key + ": " + e.getMessage());
                return null;
            }
        }
        if (resolved instanceof COSDictionary) {
            return (COSDictionary) resolved;
        }
        COSBase finalResolved = resolved;
        LOG.fine(() -> "Value for " + key + " is not a dictionary: " + finalResolved.getClass().getSimpleName());
        return null;
    }
}
