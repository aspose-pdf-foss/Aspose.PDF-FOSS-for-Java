package org.aspose.pdf.engine.layout;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Builds the /Resources dictionary for a PDF page during layout.
 * <p>
 * Tracks registered fonts and image XObjects, assigning resource names
 * (e.g. "F1", "Im1") and creating the corresponding PDF dictionary entries.
 * Each font is registered as a Type 1 standard font with WinAnsiEncoding
 * (ISO 32000-1:2008, Section 9.6.2.2).
 * </p>
 */
public class ResourceBuilder {

    private static final Logger LOG = Logger.getLogger(ResourceBuilder.class.getName());

    /**
     * Maps resource name (e.g. "F1") to its font PDF dictionary.
     */
    private final Map<String, PdfDictionary> fonts = new LinkedHashMap<>();

    /**
     * Maps resource name (e.g. "Im1") to its image PDF stream.
     */
    private final Map<String, PdfStream> images = new LinkedHashMap<>();

    /**
     * Maps base font name to resource name for deduplication.
     */
    private final Map<String, String> fontNameToResource = new LinkedHashMap<>();

    private int fontCounter = 0;
    private int imageCounter = 0;

    /**
     * Resource-name → TrueTypeReader for fonts registered via
     * {@link #addEmbeddedFont}. Layout code retrieves the reader so the
     * content-stream builder can encode text as 2-byte CIDs that the
     * embedded Type0 dictionary understands.
     */
    private final Map<String, org.aspose.pdf.engine.font.ttf.TrueTypeReader> type0Readers
            = new LinkedHashMap<>();

    /**
     * Resource names of embedded fonts whose program should be subset (only the
     * glyphs actually drawn are kept) at the end of page layout — populated from
     * {@link org.aspose.pdf.text.Font#isSubset()}. See {@link #subsetEmbeddedFonts()}.
     */
    private final java.util.Set<String> subsetResources = new java.util.LinkedHashSet<>();

    /**
     * Creates an empty ResourceBuilder.
     */
    public ResourceBuilder() {
        LOG.fine("ResourceBuilder created");
    }

    /**
     * Registers a standard PDF font and returns its resource name.
     * <p>
     * Creates a font dictionary with:
     * <ul>
     *   <li>/Type /Font</li>
     *   <li>/Subtype /Type1</li>
     *   <li>/BaseFont /fontName</li>
     *   <li>/Encoding /WinAnsiEncoding</li>
     * </ul>
     * If the same base font is registered again, the existing resource name is returned.
     * </p>
     *
     * @param baseFont the standard font name (e.g. "Helvetica", "Courier-Bold")
     * @return the resource name (e.g. "F1")
     */
    /**
     * Registers a TrueType font as a {@code /Type0}/{@code /Identity-H}
     * embedded font and returns its resource name. The font's raw bytes
     * (from {@link org.aspose.pdf.text.Font#getFontData()}) are
     * embedded into a {@code /FontFile2} stream and wired through a
     * {@code CIDFontType2} descendant plus a {@code /ToUnicode} CMap.
     *
     * <p>Returns {@code null} (and registers nothing) when {@code font} has
     * no font data — callers should fall back to {@link #addFont(String)}
     * in that case.</p>
     *
     * @param font the {@link org.aspose.pdf.text.Font} carrying TTF
     *             bytes; the {@link org.aspose.pdf.text.Font#isEmbedded()}
     *             flag is assumed already checked by the caller
     * @return the resource name (e.g. {@code "F2"}), or {@code null}
     */
    public String addEmbeddedFont(org.aspose.pdf.text.Font font) {
        if (font == null || font.getFontData() == null || font.getFontData().length == 0) {
            return null;
        }
        String key = "EMBED:" + (font.getName() != null ? font.getName() : "anon")
                + "@" + System.identityHashCode(font);
        String existing = fontNameToResource.get(key);
        if (existing != null) return existing;
        try {
            byte[] ttf = font.getFontData();
            // If for some reason a TTC slipped through (the FontRepository
            // path normally unpacks it), pull the first face out now.
            if (org.aspose.pdf.engine.font.ttf.FontDiskLookup.isTTC(ttf)) {
                byte[] unpacked = org.aspose.pdf.engine.font.ttf.FontDiskLookup
                        .extractFirstFaceFromTTC(ttf);
                if (unpacked != null) ttf = unpacked;
            }
            String fontName = font.getName() != null ? font.getName() : "EmbeddedFont";
            // CFF-based OpenType (.otf, 'OTTO') must embed as /FontFile3 in a
            // CIDFontType0; TrueType-outline fonts keep the /FontFile2 path.
            org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result built =
                    org.aspose.pdf.engine.font.ttf.Type0FontBuilder.isOpenTypeCff(ttf)
                            ? org.aspose.pdf.engine.font.ttf.Type0FontBuilder.buildOpenType(fontName, ttf)
                            : org.aspose.pdf.engine.font.ttf.Type0FontBuilder.build(fontName, ttf);
            fontCounter++;
            String resourceName = "F" + fontCounter;
            fonts.put(resourceName, built.type0Font);
            fontNameToResource.put(key, resourceName);
            type0Readers.put(resourceName, built.reader);
            if (font.isSubset()) {
                subsetResources.add(resourceName);
            }
            LOG.fine(() -> "Added embedded TrueType font " + font.getName()
                    + " as " + resourceName);
            return resourceName;
        } catch (Exception e) {
            LOG.warning(() -> "Failed to build Type0 dict for " + font.getName()
                    + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the {@link org.aspose.pdf.engine.font.ttf.TrueTypeReader}
     * paired with an embedded font resource, or {@code null} if the
     * resource is a standard Type1 font (or not registered).
     *
     * @param resourceName the {@code /Fn} name returned by {@link #addEmbeddedFont}
     * @return the reader, or {@code null}
     */
    public org.aspose.pdf.engine.font.ttf.TrueTypeReader getType0Reader(String resourceName) {
        return type0Readers.get(resourceName);
    }

    public String addFont(String baseFont) {
        String effectiveFont = baseFont != null ? baseFont : "Helvetica";

        // Check if already registered
        String existing = fontNameToResource.get(effectiveFont);
        if (existing != null) {
            return existing;
        }

        fontCounter++;
        String resourceName = "F" + fontCounter;

        PdfDictionary fontDict = new PdfDictionary();
        fontDict.set(PdfName.TYPE, PdfName.FONT);
        fontDict.set(PdfName.SUBTYPE, PdfName.of("Type1"));
        fontDict.set(PdfName.BASE_FONT, PdfName.of(effectiveFont));
        fontDict.set(PdfName.ENCODING, PdfName.of("WinAnsiEncoding"));

        fonts.put(resourceName, fontDict);
        fontNameToResource.put(effectiveFont, resourceName);

        LOG.fine(() -> "Added font " + effectiveFont + " as " + resourceName);
        return resourceName;
    }

    /**
     * Registers an image XObject and returns its resource name.
     * <p>
     * The caller provides the image PdfStream which must already contain
     * the appropriate /Subtype /Image, /Width, /Height, etc. entries.
     * </p>
     *
     * @param key         a unique key for deduplication
     * @param imageStream the image XObject stream
     * @return the resource name (e.g. "Im1")
     */
    public String addImage(String key, PdfStream imageStream) {
        if (key == null) {
            throw new IllegalArgumentException("Image key must not be null");
        }
        if (imageStream == null) {
            throw new IllegalArgumentException("Image stream must not be null");
        }

        // Check if already registered
        for (Map.Entry<String, PdfStream> entry : images.entrySet()) {
            if (entry.getValue() == imageStream) {
                return entry.getKey();
            }
        }

        imageCounter++;
        String resourceName = "Im" + imageCounter;
        images.put(resourceName, imageStream);

        LOG.fine(() -> "Added image " + key + " as " + resourceName);
        return resourceName;
    }

    /**
     * Builds and returns the complete /Resources PDF dictionary.
     * <p>
     * The dictionary contains /Font and /XObject sub-dictionaries mapping
     * resource names to their objects.
     * </p>
     *
     * @return the /Resources PdfDictionary
     */
    /**
     * Subsets the embedded TrueType programs of fonts marked for subsetting
     * (see {@link org.aspose.pdf.text.Font#setSubset(boolean)}), keeping only the
     * glyphs actually drawn on this page. Must be called AFTER all content has
     * been emitted (so every used glyph has been recorded via
     * {@link org.aspose.pdf.engine.font.ttf.TrueTypeReader#markGlyphUsed(int)})
     * and BEFORE {@link #buildResourcesDictionary()}.
     * <p>
     * The subset is a drop-in replacement of the {@code /FontFile2} bytes:
     * {@link org.aspose.pdf.engine.optimization.TtfGlyphStripper} does not
     * renumber glyphs, so the Identity-H {@code CIDToGIDMap} and {@code /W}
     * widths stay valid. Any failure (strip returns {@code null}, a larger
     * result, or a malformed graph) leaves the full program embedded — subsetting
     * is best-effort and never drops a used glyph.
     * </p>
     */
    public void subsetEmbeddedFonts() {
        for (String res : subsetResources) {
            org.aspose.pdf.engine.font.ttf.TrueTypeReader reader = type0Readers.get(res);
            PdfDictionary type0 = fonts.get(res);
            if (reader == null || type0 == null) {
                continue;
            }
            java.util.Set<Integer> used = reader.getUsedGids();
            if (used == null || used.isEmpty()) {
                continue;
            }
            // The glyph stripper rebuilds glyf/loca; CFF (OpenType /FontFile3)
            // programs have no such tables and are embedded whole.
            if (!reader.hasGlyphOutlines()) {
                continue;
            }
            try {
                org.aspose.pdf.engine.pdfobjects.PdfArray descendants = type0.getArray("DescendantFonts");
                if (descendants == null || descendants.size() == 0) {
                    continue;
                }
                PdfDictionary cidFont = descendants.getDictionary(0);
                if (cidFont == null) {
                    continue;
                }
                PdfDictionary descriptor = cidFont.getDictionary("FontDescriptor");
                if (descriptor == null) {
                    continue;
                }
                org.aspose.pdf.engine.pdfobjects.PdfBase ffBase = descriptor.get("FontFile2");
                if (!(ffBase instanceof PdfStream)) {
                    continue;
                }
                PdfStream fontFile2 = (PdfStream) ffBase;
                byte[] original = fontFile2.getDecodedData();
                if (original == null || original.length == 0) {
                    continue;
                }
                java.util.Set<Integer> gids = new java.util.TreeSet<>(used);
                gids.add(0); // always keep .notdef
                byte[] stripped = org.aspose.pdf.engine.optimization.TtfGlyphStripper.strip(original, gids);
                if (stripped != null && stripped.length > 0 && stripped.length < original.length) {
                    fontFile2.setDecodedData(stripped);
                    fontFile2.set("Length1", org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(stripped.length));
                    final int before = original.length;
                    final int after = stripped.length;
                    LOG.fine(() -> "Subset embedded font " + res + ": " + before + " -> " + after + " bytes");
                }
            } catch (Exception e) {
                LOG.fine(() -> "Font subset skipped for " + res + ": " + e.getMessage());
            }
        }
    }

    public PdfDictionary buildResourcesDictionary() {
        PdfDictionary resources = new PdfDictionary();

        // Build /Font sub-dictionary
        if (!fonts.isEmpty()) {
            PdfDictionary fontDict = new PdfDictionary();
            for (Map.Entry<String, PdfDictionary> entry : fonts.entrySet()) {
                fontDict.set(PdfName.of(entry.getKey()), entry.getValue());
            }
            resources.set(PdfName.FONT, fontDict);
        }

        // Build /XObject sub-dictionary
        if (!images.isEmpty()) {
            PdfDictionary xobjectDict = new PdfDictionary();
            for (Map.Entry<String, PdfStream> entry : images.entrySet()) {
                xobjectDict.set(PdfName.of(entry.getKey()), entry.getValue());
            }
            resources.set(PdfName.XOBJECT, xobjectDict);
        }

        LOG.fine(() -> "Built resources dictionary with " + fonts.size()
                + " font(s) and " + images.size() + " image(s)");
        return resources;
    }

    /**
     * Returns the resource name for a previously registered font, or null.
     *
     * @param baseFont the base font name
     * @return the resource name, or null if not registered
     */
    public String getFontResourceName(String baseFont) {
        return fontNameToResource.get(baseFont);
    }
}
