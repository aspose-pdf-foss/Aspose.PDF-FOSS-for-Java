package org.aspose.pdf.engine.font;

import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Caches and resolves PDF fonts from resource dictionaries.
 * <p>
 * Maintains a per-instance cache to avoid re-parsing the same font dictionary
 * multiple times during text extraction.
 * </p>
 */
public final class FontRepository {

    private static final Logger LOG = Logger.getLogger(FontRepository.class.getName());

    // Keyed by the IDENTITY of the resolved font dictionary, NOT the resource
    // alias: a page and a Form XObject routinely both call their first font
    // "F0" while binding it to DIFFERENT dictionaries (page: simple ArialMT;
    // form: Type0 Identity-H). An alias-keyed cache returned whichever loaded
    // first for BOTH scopes, so the other scope's text was decoded with the
    // wrong code stride (single-byte ASCII fused into 2-byte CIDs — mojibake).
    // Identity (not content equals) keeps the lookup O(1) — PdfDictionary's
    // equals is deep and a font dict hangs a multi-KB FontFile stream off it.
    private final Map<PdfDictionary, PdfFont> cache = new IdentityHashMap<>();

    /**
     * Returns the PdfFont for the given font name from the fonts dictionary.
     * <p>
     * Caches fonts by the resolved font dictionary to avoid repeated parsing.
     * </p>
     *
     * @param fontsDict the /Font sub-dictionary from page resources
     * @param fontName  the font resource name (e.g., "F1", "TT0")
     * @param parser    the PDF parser for resolving indirect references
     * @return the resolved PdfFont
     * @throws IOException if font creation fails
     */
    public PdfFont getFont(PdfDictionary fontsDict, String fontName, PDFParser parser)
            throws IOException {
        if (fontsDict == null || fontName == null) {
            return null;
        }

        // Resolve font dictionary first — the cache key is the dictionary itself.
        PdfBase fontVal = fontsDict.get(fontName);
        if (fontVal instanceof PdfObjectReference) {
            try {
                fontVal = ((PdfObjectReference) fontVal).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference font " + fontName + ": " + e.getMessage());
                return null;
            }
        }

        if (!(fontVal instanceof PdfDictionary)) {
            LOG.warning(() -> "Font " + fontName + " is not a dictionary");
            return null;
        }
        PdfDictionary fontDict = (PdfDictionary) fontVal;

        PdfFont cached = cache.get(fontDict);
        if (cached != null) {
            return cached;
        }

        PdfFont font = PdfFont.fromDictionary(fontDict, parser);
        cache.put(fontDict, font);
        return font;
    }

    /**
     * Convenience method: resolves a font from page Resources.
     *
     * @param resources the page resources
     * @param fontName  the font resource name (e.g., "F1")
     * @param parser    the PDF parser
     * @return the resolved PdfFont, or null
     * @throws IOException if font creation fails
     */
    public static PdfFont fromResources(Resources resources, String fontName, PDFParser parser)
            throws IOException {
        if (resources == null) return null;
        PdfDictionary fonts = resources.getFonts();
        if (fonts == null) return null;
        FontRepository repo = new FontRepository();
        return repo.getFont(fonts, fontName, parser);
    }

    /**
     * Clears the font cache.
     */
    public void clear() {
        cache.clear();
    }
}
