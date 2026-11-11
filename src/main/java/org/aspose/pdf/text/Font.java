package org.aspose.pdf.text;

/**
 * Represents a font used in PDF documents.
 * <p>
 * Provides access to font name and basic font properties.
 * </p>
 */
public class Font {

    private String name;
    private boolean isEmbedded;
    private boolean isSubset;
    private byte[] fontData;
    private String fontFilePath;
    // Absorbed fragment this font was surfaced from (set by TextState.getFont for
    // editable states). Lets getFont().setEmbedded(true) embed the existing font
    // in place on save. Null for fonts the caller constructed or that back a
    // non-editable state.
    private TextFragment writeBackFragment;

    /**
     * Creates a font with default (empty) name.
     */
    public Font() {
        this.name = "";
    }

    /**
     * Creates a font with the specified name.
     *
     * @param name the font name
     */
    public Font(String name) {
        this.name = name != null ? name : "";
    }

    /**
     * Gets the font name.
     *
     * @return the font name
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the font name.
     *
     * @param name the font name
     */
    public void setName(String name) {
        this.name = name != null ? name : "";
    }

    /**
     * Returns whether this font is embedded in the PDF.
     *
     * @return {@code true} if embedded
     */
    public boolean isEmbedded() {
        return isEmbedded;
    }

    /**
     * Sets whether this font is embedded in the PDF.
     * <p>
     * Clearing the embedded flag also clears {@link #isSubset() subset}:
     * subsetting only has meaning for an embedded font program, so a
     * non-embedded font is never a subset. This mirrors Aspose.PDF, where
     * {@code getFont().setEmbedded(false)} on a replacement font reports
     * {@code isSubset() == false} immediately (FontTests.Replace_Font_NonSubset).
     * Marking a font embedded leaves the subset flag untouched.
     *
     * @param embedded {@code true} to mark as embedded
     */
    public void setEmbedded(boolean embedded) {
        boolean wasEmbedded = this.isEmbedded;
        this.isEmbedded = embedded;
        if (!embedded) {
            this.isSubset = false;
        } else if (!wasEmbedded && writeBackFragment != null) {
            // Marking an existing (originally non-embedded) document font embedded:
            // schedule the font program to be injected into its descriptor on save.
            writeBackFragment.scheduleEmbedExisting(this);
        }
    }

    /**
     * Binds this font to the absorbed fragment it was surfaced from, so that
     * {@link #setEmbedded(boolean) setEmbedded(true)} can embed the existing
     * document font in place on save. Engine-internal; called by
     * {@code TextState.getFont()} for editable states.
     *
     * @param fragment the owning absorbed fragment
     */
    void bindWriteBack(TextFragment fragment) {
        this.writeBackFragment = fragment;
    }

    /**
     * Returns whether this font is a subset.
     *
     * @return {@code true} if subset
     */
    public boolean isSubset() {
        return isSubset;
    }

    /**
     * Sets whether this font is a subset.
     *
     * @param subset {@code true} to mark as subset
     */
    public void setSubset(boolean subset) {
        this.isSubset = subset;
    }

    /**
     * Gets the raw font data bytes.
     *
     * @return the font data, or {@code null} if not set
     */
    public byte[] getFontData() {
        return fontData;
    }

    /**
     * Sets the raw font data bytes.
     *
     * @param data the font data
     */
    public void setFontData(byte[] data) {
        this.fontData = data;
        this.ttReader = null;
        this.ttTried = false;
    }

    /**
     * Gets the file path from which this font was loaded.
     *
     * @return the font file path, or {@code null} if not set
     */
    public String getFontFilePath() {
        return fontFilePath;
    }

    /**
     * Sets the file path from which this font was loaded.
     *
     * @param path the font file path
     */
    public void setFontFilePath(String path) {
        this.fontFilePath = path;
        this.ttReader = null;
        this.ttTried = false;
    }

    // --- Metrics ----------------------------------------------------------
    //
    // Font exposes glyph-level metrics on top of the existing engine readers,
    // without duplicating them:
    //   * a font carrying program bytes (getFontData() != null — e.g. loaded
    //     via FontRepository.openFont) measures through a cached
    //     org.aspose.pdf.engine.font.ttf.TrueTypeReader (real advance widths);
    //   * a name-only / Standard-14 font measures through
    //     org.aspose.pdf.engine.layout.TextLayoutHelper (WinAnsi width tables).

    /** Cached TrueType reader for an embedded/file font; null until first use or when no bytes. */
    private transient org.aspose.pdf.engine.font.ttf.TrueTypeReader ttReader;
    private transient boolean ttTried;

    /**
     * Returns the cached {@link org.aspose.pdf.engine.font.ttf.TrueTypeReader}
     * for this font's program bytes, or {@code null} when the font has no
     * embedded/file bytes (a Standard-14 / name-only font) or the bytes are not
     * a parseable sfnt. Built once, lazily.
     */
    private org.aspose.pdf.engine.font.ttf.TrueTypeReader ttReader() {
        if (!ttTried) {
            ttTried = true;
            if (fontData != null && fontData.length > 0) {
                try {
                    ttReader = new org.aspose.pdf.engine.font.ttf.TrueTypeReader(fontData);
                } catch (Exception e) {
                    ttReader = null; // not an sfnt (e.g. Type1) — fall back to the by-name path
                }
            }
        }
        return ttReader;
    }

    /**
     * Measures the advance width of the given text at the given font size.
     * <p>
     * For a font that carries program bytes (loaded through
     * {@link FontRepository#openFont(String)} and the like) the width is summed
     * from the font's real glyph advance widths. For a Standard-14 / name-only
     * font the width comes from the standard WinAnsi width tables. The result is
     * in user-space points, matching Aspose.PDF semantics
     * (e.g. Helvetica {@code "A"} at 14&nbsp;pt ≈ 9.338).
     * </p>
     *
     * @param text     the text to measure (an empty or {@code null} string measures 0)
     * @param fontSize the font size in points
     * @return the text advance width in user-space points
     */
    public double measureString(String text, double fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        org.aspose.pdf.engine.font.ttf.TrueTypeReader reader = ttReader();
        if (reader != null) {
            int unitsPerEm = reader.getUnitsPerEm();
            if (unitsPerEm <= 0) {
                unitsPerEm = 1000;
            }
            double total = 0;
            for (int i = 0; i < text.length(); i++) {
                int gid = reader.getGlyphId(text.charAt(i));
                total += (double) reader.getAdvanceWidth(gid) / unitsPerEm * fontSize;
            }
            return total;
        }
        return org.aspose.pdf.engine.layout.TextLayoutHelper.measureTextWidth(text, name, fontSize);
    }

    /**
     * Returns the font ascender — the maximum height above the baseline — in
     * units of 1/1000&nbsp;em (PDF glyph space).
     * <p>
     * For a font with program bytes this is read from the font's {@code hhea}
     * table; for a Standard-14 / name-only font a representative default (800)
     * is returned.
     * </p>
     *
     * @return the ascender in 1/1000&nbsp;em
     */
    public double getAscender() {
        org.aspose.pdf.engine.font.ttf.TrueTypeReader reader = ttReader();
        if (reader != null) {
            int unitsPerEm = reader.getUnitsPerEm();
            if (unitsPerEm <= 0) {
                unitsPerEm = 1000;
            }
            return reader.getAscender() * 1000.0 / unitsPerEm;
        }
        return 800.0;
    }

    /**
     * Returns the font descender — the maximum depth below the baseline, normally
     * negative — in units of 1/1000&nbsp;em (PDF glyph space).
     * <p>
     * For a font with program bytes this is read from the font's {@code hhea}
     * table; for a Standard-14 / name-only font a representative default (-200)
     * is returned.
     * </p>
     *
     * @return the descender in 1/1000&nbsp;em
     */
    public double getDescender() {
        org.aspose.pdf.engine.font.ttf.TrueTypeReader reader = ttReader();
        if (reader != null) {
            int unitsPerEm = reader.getUnitsPerEm();
            if (unitsPerEm <= 0) {
                unitsPerEm = 1000;
            }
            return reader.getDescender() * 1000.0 / unitsPerEm;
        }
        return -200.0;
    }

    @Override
    public String toString() {
        return name;
    }
}
