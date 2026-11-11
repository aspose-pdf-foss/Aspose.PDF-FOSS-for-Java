package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.text.Font;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Provides utility methods for working with fonts in a PDF document.
 * <p>
 * Accessed via {@link Document#getFontUtilities()}. Allows enumerating all fonts
 * used across all pages of the document and marking fonts for subsetting.
 * </p>
 */
public class FontUtilities {

    private static final Logger LOG = Logger.getLogger(FontUtilities.class.getName());

    private final Document document;

    /**
     * Creates a FontUtilities instance for the given document.
     *
     * @param document the PDF document
     * @throws IllegalArgumentException if document is null
     */
    public FontUtilities(Document document) {
        if (document == null) {
            throw new IllegalArgumentException("Document must not be null");
        }
        this.document = document;
    }

    /**
     * Returns all fonts used across all pages of the document.
     * <p>
     * Iterates each page's /Resources /Font dictionary and collects unique fonts
     * by their base font name. Font embedding and subset status are determined
     * from the font dictionary entries (/FontDescriptor, /FontFile, etc.).
     * </p>
     *
     * @return an array of all unique fonts found in the document
     */
    public Font[] getAllFonts() {
        Map<String, Font> fontMap = new LinkedHashMap<>();
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                Page page = pages.get(i);
                Resources resources = page.getResources();
                if (resources == null) continue;
                collectFonts(resources.getFonts(), fontMap);
            }
            // AcroForm default resources (/AcroForm /DR /Font) hold the fonts used
            // by form field appearances, which never appear in a page's own
            // /Resources (ISO 32000-1 §12.7.2). Aspose includes them in the
            // document's font list — scan them after the pages so page fonts keep
            // their leading positions (PDFNET-44576).
            collectFonts(acroFormFonts(), fontMap);
        } catch (IOException e) {
            LOG.warning(() -> "Error collecting fonts: " + e.getMessage());
        }
        return fontMap.values().toArray(new Font[0]);
    }

    /**
     * Adds each entry of a {@code /Font} resource dictionary to {@code fontMap},
     * de-duplicating by the raw {@code /BaseFont} name (so two differently-subset
     * copies of the same face stay distinct) while exposing the subset-prefix-
     * stripped name through {@link Font#getName()}.
     */
    private void collectFonts(PdfDictionary fontsDict, Map<String, Font> fontMap) {
        if (fontsDict == null) return;
        for (PdfName key : fontsDict.keySet()) {
            PdfBase fontObj = resolveRef(fontsDict.get(key));
            if (!(fontObj instanceof PdfDictionary)) continue;
            PdfDictionary fontDict = (PdfDictionary) fontObj;
            String baseFontName = fontDict.getNameAsString("BaseFont");
            if (baseFontName == null) {
                baseFontName = key.getName();
            }
            if (!fontMap.containsKey(baseFontName)) {
                Font font = new Font(stripSubsetPrefix(baseFontName));
                font.setEmbedded(isFontEmbedded(fontDict));
                font.setSubset(isFontSubset(baseFontName));
                fontMap.put(baseFontName, font);
            }
        }
    }

    /**
     * Returns the {@code /AcroForm /DR /Font} dictionary, or {@code null} when the
     * document has no form or no default-resource fonts.
     */
    private PdfDictionary acroFormFonts() throws IOException {
        PdfDictionary catalog = document.getCatalog();
        if (catalog == null) return null;
        PdfBase acroForm = resolveRef(catalog.get("AcroForm"));
        if (!(acroForm instanceof PdfDictionary)) return null;
        PdfBase dr = resolveRef(((PdfDictionary) acroForm).get("DR"));
        if (!(dr instanceof PdfDictionary)) return null;
        PdfBase fonts = resolveRef(((PdfDictionary) dr).get("Font"));
        return fonts instanceof PdfDictionary ? (PdfDictionary) fonts : null;
    }

    /**
     * Strips a 6-uppercase-letter subset prefix ({@code ABCDEF+}) from a base
     * font name (ISO 32000-1 §9.6.4). Returns the input unchanged when it does
     * not match the subset convention.
     */
    private static String stripSubsetPrefix(String baseFont) {
        if (baseFont == null || baseFont.length() < 8 || baseFont.charAt(6) != '+') {
            return baseFont;
        }
        for (int i = 0; i < 6; i++) {
            char c = baseFont.charAt(i);
            if (c < 'A' || c > 'Z') return baseFont;
        }
        return baseFont.substring(7);
    }

    /**
     * Subsets the document's embedded fonts in place: strips glyph outlines
     * that no content stream uses from the embedded TrueType programs
     * (Identity-encoded CID fonts; see the resource optimizer for the safe
     * shapes handled). The change takes effect on the next save.
     */
    public void subsetFonts() {
        if (document.getParser() == null) {
            LOG.fine("subsetFonts: new document — fonts are embedded per use already");
            return;
        }
        org.aspose.pdf.optimization.OptimizationOptions options =
                new org.aspose.pdf.optimization.OptimizationOptions();
        options.setSubsetFonts(true);
        org.aspose.pdf.engine.optimization.ResourceOptimizer.optimize(
                document.getParser(), options);
    }

    /**
     * Checks whether a font dictionary indicates the font is embedded.
     *
     * @param fontDict the font dictionary
     * @return true if the font has an embedded program
     */
    private boolean isFontEmbedded(PdfDictionary fontDict) {
        PdfBase descriptor = resolveRef(fontDict.get("FontDescriptor"));
        if (descriptor instanceof PdfDictionary) {
            PdfDictionary desc = (PdfDictionary) descriptor;
            if (desc.get("FontFile") != null || desc.get("FontFile2") != null
                    || desc.get("FontFile3") != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether a font name indicates it is a subset (has a 6-letter prefix + '+').
     *
     * @param baseFontName the base font name
     * @return true if the name matches the subset naming convention
     */
    private boolean isFontSubset(String baseFontName) {
        if (baseFontName == null || baseFontName.length() < 8) return false;
        // Subset fonts have the pattern ABCDEF+FontName
        if (baseFontName.charAt(6) != '+') return false;
        for (int i = 0; i < 6; i++) {
            char c = baseFontName.charAt(i);
            if (c < 'A' || c > 'Z') return false;
        }
        return true;
    }

    /**
     * Resolves an indirect object reference.
     *
     * @param val the PDF value to resolve
     * @return the resolved value, or null
     */
    private PdfBase resolveRef(PdfBase val) {
        if (val instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) val).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return val;
    }
}
