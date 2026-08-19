package org.aspose.pdf.text;

import org.aspose.pdf.Color;

/**
 * Represents the graphical state of text (ISO 32000-1:2008, §9.3).
 * <p>
 * Holds font name, size, colors, spacing, and rendering mode.
 * </p>
 */
public class TextState {

    private String fontName;
    /**
     * Full {@link Font} object as set via {@link #setFont(Font)}; carries the
     * embedded flag, raw bytes and disk path. Layout consults this — not just
     * the legacy {@link #fontName} string — when deciding whether to inline a
     * TrueType FontFile2 stream into the saved PDF.
     */
    private Font font;
    private double fontSize;
    private Color foregroundColor;
    private Color backgroundColor;
    private double horizontalScaling = 100;
    private double characterSpacing;
    private double wordSpacing;
    private double textLeading;
    private int renderingMode;
    private double textRise;
    private boolean invisible;
    private TextFormattingOptions formattingOptions;
    private int fontStyle = 0; // FontStyles bitmask
    private float lineSpacing = 0;
    private boolean underline;
    // Hooks invoked when underline transitions true -> false (source-underline removal on edit).
    // transient: not part of the visual state; never copied when a TextState is cloned.
    private transient java.util.List<Runnable> underlineRemovalHooks;
    private boolean strikeOut;

    /**
     * Creates a TextState with default values.
     */
    public TextState() {
        this.foregroundColor = Color.BLACK;
    }

    /**
     * Creates a TextState with the given font size.
     * Mirrors the Aspose.PDF {@code new TextState(double size)} constructor.
     *
     * @param fontSize the font size in points
     */
    public TextState(double fontSize) {
        this();
        this.fontSize = fontSize;
    }

    /**
     * Creates a TextState with the given font and font size.
     *
     * @param font     the font
     * @param fontSize the font size in points
     */
    public TextState(Font font, double fontSize) {
        this();
        setFont(font);
        this.fontSize = fontSize;
    }

    /**
     * Creates a fully-specified TextState matching the Aspose.PDF
     * {@code new TextState(foreground, background, fontStyle, font, size)}
     * constructor used in many regression tests.
     *
     * @param foreground the foreground (text) color (null leaves the default)
     * @param background the background color (null = transparent)
     * @param fontStyle  the font style bitmask (see {@link FontStyles})
     * @param font       the font (null leaves font name unset)
     * @param fontSize   the font size in points
     */
    public TextState(Color foreground, Color background, int fontStyle,
                     Font font, double fontSize) {
        this();
        if (foreground != null) {
            this.foregroundColor = foreground;
        }
        this.backgroundColor = background;
        this.fontStyle = fontStyle;
        if (font != null) {
            setFont(font);
        }
        this.fontSize = fontSize;
    }

    /**
     * Returns the font name.
     *
     * @return the font name, or null
     */
    public String getFontName() {
        return fontName;
    }

    /**
     * Sets the font name.
     *
     * @param fontName the font name
     */
    public void setFontName(String fontName) {
        this.fontName = fontName;
    }

    /**
     * Returns the font as a {@link Font} object.
     * The font object wraps the current font name.
     *
     * @return the font, or null if no font name is set
     */
    public Font getFont() {
        if (font != null) return font;
        if (fontName == null) return null;
        Font f = new Font(fontName);
        f.setEmbedded(fontEmbedded);
        f.setSubset(fontSubset);
        return f;
    }

    /** Embedding/subset status of the resolved font, surfaced through {@link #getFont()}. */
    private boolean fontEmbedded;
    private boolean fontSubset;

    /**
     * Records the embedding/subset status of the resolved font so a name-only
     * {@link #getFont()} reports {@code isEmbedded()}/{@code isSubset()}
     * correctly. Engine-internal; set by the extractor, does not affect the
     * font object a caller may have assigned via {@link #setFont(Font)}.
     *
     * @param embedded whether the font program is embedded
     * @param subset   whether the font is a subset (/BaseFont has a "+"-tag)
     */
    public void setFontEmbeddingInfo(boolean embedded, boolean subset) {
        this.fontEmbedded = embedded;
        this.fontSubset = subset;
    }

    /**
     * Sets the font from a {@link Font} object. The full object is retained
     * — embedded flag, raw bytes, and file path included — so that the
     * layout engine can embed the font's binary in the output PDF when
     * {@link Font#isEmbedded()} is true.
     *
     * @param font the font to set
     */
    public void setFont(Font font) {
        this.font = font;
        this.fontName = font != null ? font.getName() : null;
        // Aspose semantics: assigning a new (embeddable) font to an absorbed
        // fragment re-draws that fragment's glyphs with the new font on save.
        // Mirror the setFontSize write-back: only fires for a state bound to an
        // extracted fragment and only for a font carrying real program bytes
        // (Standard-14 replacements have no bytes and take the by-name path).
        if (font != null && sourceWriteBackFragment != null
                && font.getFontData() != null && font.getFontData().length > 0) {
            sourceWriteBackFragment.applyFontToSource(font);
        }
    }

    /**
     * Returns the font size in points.
     *
     * @return the font size
     */
    public double getFontSize() {
        return fontSize;
    }

    /**
     * Sets the font size.
     *
     * @param fontSize the font size in points
     */
    public void setFontSize(double fontSize) {
        this.fontSize = fontSize;
        if (sourceWriteBackFragment != null) {
            sourceWriteBackFragment.applyFontSizeToSource(fontSize);
        }
    }

    /** Fragment whose source content stream mirrors font-size changes, or null. */
    private TextFragment sourceWriteBackFragment;

    /**
     * Binds this state to an extracted fragment so that
     * {@link #setFontSize(double)} writes the new size back into the
     * fragment's source content stream (Aspose semantics: mutating an
     * absorbed fragment's state edits the document — PDFNEWNET-30639).
     * Engine-internal; called by the extractor/absorber, not by clients.
     *
     * @param fragment the owning extracted fragment
     */
    public void bindSourceFragment(TextFragment fragment) {
        this.sourceWriteBackFragment = fragment;
    }

    /**
     * Returns the foreground (text) color.
     *
     * @return the foreground color
     */
    public Color getForegroundColor() {
        return foregroundColor;
    }

    /**
     * Sets the foreground color.
     *
     * @param color the foreground color
     */
    public void setForegroundColor(Color color) {
        this.foregroundColor = color;
    }

    /**
     * Returns the background color (null = transparent).
     *
     * @return the background color, or null
     */
    public Color getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Sets the background color.
     *
     * @param color the background color
     */
    public void setBackgroundColor(Color color) {
        this.backgroundColor = color;
        // Aspose semantics: setting a background colour on an absorbed fragment's
        // state paints a filled rectangle behind each of the fragment's segments
        // in the source content stream, surviving save/reload (mirrors the
        // setFont/setFontSize write-backs). Only fires for a bound state.
        if (color != null && sourceWriteBackFragment != null) {
            sourceWriteBackFragment.applyBackgroundToSource(color);
        }
    }

    /**
     * Returns the horizontal scaling percentage (default 100).
     *
     * @return the horizontal scaling
     */
    public double getHorizontalScaling() {
        return horizontalScaling;
    }

    /**
     * Sets the horizontal scaling percentage.
     *
     * @param value the scaling percentage (100 = normal)
     */
    public void setHorizontalScaling(double value) {
        this.horizontalScaling = value;
    }

    /**
     * Returns the character spacing in text units.
     *
     * @return the character spacing
     */
    public double getCharacterSpacing() {
        return characterSpacing;
    }

    /**
     * Sets the character spacing.
     *
     * @param value the character spacing
     */
    public void setCharacterSpacing(double value) {
        this.characterSpacing = value;
    }

    /**
     * Returns the word spacing (extra space added after space characters).
     *
     * @return the word spacing
     */
    public double getWordSpacing() {
        return wordSpacing;
    }

    /**
     * Sets the word spacing.
     *
     * @param value the word spacing
     */
    public void setWordSpacing(double value) {
        this.wordSpacing = value;
    }

    /**
     * Returns the text leading (vertical distance between lines for T* and ' operators).
     *
     * @return the text leading
     */
    public double getTextLeading() {
        return textLeading;
    }

    /**
     * Sets the text leading.
     *
     * @param value the text leading
     */
    public void setTextLeading(double value) {
        this.textLeading = value;
    }

    /**
     * Returns the text rendering mode (0=fill, 1=stroke, 2=fill+stroke, 3=invisible).
     *
     * @return the rendering mode
     */
    public int getRenderingMode() {
        return renderingMode;
    }

    /**
     * Sets the text rendering mode.
     *
     * @param mode the rendering mode
     */
    public void setRenderingMode(int mode) {
        this.renderingMode = mode;
    }

    /**
     * Returns the text rise (baseline shift).
     *
     * @return the text rise
     */
    public double getTextRise() {
        return textRise;
    }

    /**
     * Sets the text rise.
     *
     * @param value the text rise
     */
    public void setTextRise(double value) {
        this.textRise = value;
    }

    /**
     * Returns whether the text is invisible (rendering mode 3).
     *
     * @return true if invisible
     */
    public boolean isInvisible() {
        return invisible || renderingMode == 3;
    }

    /**
     * Sets whether the text is invisible.
     *
     * @param invisible true to mark as invisible
     */
    public void setInvisible(boolean invisible) {
        this.invisible = invisible;
    }

    /**
     * Returns the text formatting options.
     *
     * @return the formatting options, or null
     */
    public TextFormattingOptions getFormattingOptions() {
        return formattingOptions;
    }

    /**
     * Sets the text formatting options.
     *
     * @param options the formatting options
     */
    public void setFormattingOptions(TextFormattingOptions options) {
        this.formattingOptions = options;
    }

    /**
     * Returns the font style bitmask (see {@link FontStyles}).
     *
     * @return the font style flags
     */
    public int getFontStyle() {
        return fontStyle;
    }

    /**
     * Sets the font style bitmask (see {@link FontStyles}).
     *
     * @param fontStyle the font style flags
     */
    public void setFontStyle(int fontStyle) {
        this.fontStyle = fontStyle;
    }

    /**
     * Returns the line spacing value.
     *
     * @return the line spacing
     */
    public float getLineSpacing() {
        return lineSpacing;
    }

    /**
     * Sets the line spacing value.
     *
     * @param lineSpacing the line spacing
     */
    public void setLineSpacing(float lineSpacing) {
        this.lineSpacing = lineSpacing;
    }

    /**
     * Returns whether the text is underlined.
     *
     * @return true if underline is enabled
     */
    public boolean isUnderline() {
        return underline;
    }

    /**
     * Sets whether the text is underlined.
     * <p>
     * When the underline state transitions from {@code true} to {@code false} on a
     * fragment whose underline was drawn as explicit path operators in the source
     * content (detected via {@code TextEditOptions.ToAttemptGetUnderlineFromSource}),
     * any registered {@linkplain #addUnderlineRemovalHook removal hooks} are invoked
     * so the underline operators are removed from the content stream on save.
     * </p>
     *
     * @param underline true to enable underline
     */
    public void setUnderline(boolean underline) {
        boolean was = this.underline;
        this.underline = underline;
        if (was && !underline && underlineRemovalHooks != null) {
            // Run-and-clear: removal is idempotent but we avoid re-firing on a
            // subsequent toggle that no longer has source operators to remove.
            java.util.List<Runnable> hooks = underlineRemovalHooks;
            underlineRemovalHooks = null;
            for (Runnable hook : hooks) {
                hook.run();
            }
        }
    }

    /**
     * Registers a hook that removes source-drawn underline operators when this
     * state's underline is turned off. Used by the text-extraction engine to
     * associate detected underline path operators with the fragment so a later
     * {@code setUnderline(false)} edit can strip them on save.
     *
     * @param hook the removal action (ignored if {@code null})
     */
    public void addUnderlineRemovalHook(Runnable hook) {
        if (hook == null) {
            return;
        }
        if (underlineRemovalHooks == null) {
            underlineRemovalHooks = new java.util.ArrayList<>(1);
        }
        underlineRemovalHooks.add(hook);
    }

    /**
     * Returns whether the text has a strikeout (strikethrough) line.
     *
     * @return true if strikeout is enabled
     */
    public boolean isStrikeOut() {
        return strikeOut;
    }

    /**
     * Sets whether the text has a strikeout (strikethrough) line.
     *
     * @param strikeOut true to enable strikeout
     */
    public void setStrikeOut(boolean strikeOut) {
        this.strikeOut = strikeOut;
    }
}
