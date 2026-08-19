package org.aspose.pdf;

import org.aspose.pdf.tagged.AutoTaggingSettings;

/**
 * Options for saving a PDF document.
 * Controls whether linearization (web optimization) is applied.
 */
public class PdfSaveOptions extends SaveOptions {

    private boolean linearize;

    /**
     * {@inheritDoc}
     *
     * @return always {@link SaveFormat#Pdf}
     */
    @Override
    public SaveFormat getSaveFormat() {
        return SaveFormat.Pdf;
    }
    private boolean useObjectStreams;
    private boolean useXRefStream;
    private int objectsPerStream = 200;
    private ColorConversionStrategy colorConversion = ColorConversionStrategy.None;
    private AutoTaggingSettings autoTaggingSettings;

    /**
     * Returns whether linearization (web optimization) is enabled.
     *
     * @return {@code true} if the PDF should be linearized on save
     */
    public boolean isLinearize() {
        return linearize;
    }

    /**
     * Sets whether to produce a linearized (web-optimized) PDF.
     * Linearized PDFs reorder objects so the first page can be
     * displayed before the entire file is downloaded.
     *
     * @param linearize {@code true} to enable linearization
     * @return this options instance for chaining
     */
    public PdfSaveOptions setLinearize(boolean linearize) {
        this.linearize = linearize;
        return this;
    }

    /**
     * Returns whether object streams (PDF 1.5+) are enabled.
     * When enabled, small non-stream objects are packed into compressed
     * object streams (ISO 32000-1:2008 §7.5.7).
     *
     * @return {@code true} if object streams should be used on save
     */
    public boolean isUseObjectStreams() {
        return useObjectStreams;
    }

    /**
     * Sets whether to pack eligible objects into object streams (PDF 1.5+).
     * This significantly reduces file size by compressing many small objects
     * into a single Flate-encoded stream.
     *
     * @param use {@code true} to enable object streams
     * @return this options instance for chaining
     */
    public PdfSaveOptions setUseObjectStreams(boolean use) {
        this.useObjectStreams = use;
        return this;
    }

    /**
     * Returns whether xref streams (PDF 1.5+) are enabled.
     * When enabled, a cross-reference stream replaces the text xref table
     * and trailer (ISO 32000-1:2008 §7.5.8).
     *
     * @return {@code true} if xref streams should be used on save
     */
    public boolean isUseXRefStream() {
        return useXRefStream;
    }

    /**
     * Sets whether to write an xref stream instead of a text xref table (PDF 1.5+).
     *
     * @param use {@code true} to enable xref streams
     * @return this options instance for chaining
     */
    public PdfSaveOptions setUseXRefStream(boolean use) {
        this.useXRefStream = use;
        return this;
    }

    /**
     * Returns the maximum number of objects per object stream.
     *
     * @return the max objects per stream (default 200)
     */
    public int getObjectsPerStream() {
        return objectsPerStream;
    }

    /**
     * Sets the maximum number of objects to pack into a single object stream.
     *
     * @param max the maximum count (clamped to at least 1)
     * @return this options instance for chaining
     */
    public PdfSaveOptions setObjectsPerStream(int max) {
        this.objectsPerStream = Math.max(1, max);
        return this;
    }

    /**
     * Returns the color conversion strategy to apply when saving.
     *
     * @return the color conversion strategy (default {@link ColorConversionStrategy#None})
     */
    public ColorConversionStrategy getColorConversion() {
        return colorConversion;
    }

    /**
     * Sets the color conversion strategy to apply when saving.
     * When set to anything other than {@link ColorConversionStrategy#None},
     * color operators in page content streams will be converted accordingly.
     *
     * @param colorConversion the color conversion strategy
     * @return this options instance for chaining
     */
    public PdfSaveOptions setColorConversion(ColorConversionStrategy colorConversion) {
        this.colorConversion = colorConversion != null ? colorConversion : ColorConversionStrategy.None;
        return this;
    }

    /**
     * Returns the auto-tagging settings, or {@code null} if not set.
     *
     * @return the auto-tagging settings
     */
    public AutoTaggingSettings getAutoTaggingSettings() {
        return autoTaggingSettings;
    }

    /**
     * Sets the auto-tagging settings for automatic structure tagging on save.
     *
     * @param settings the auto-tagging settings, or {@code null} to disable
     */
    public void setAutoTaggingSettings(AutoTaggingSettings settings) {
        this.autoTaggingSettings = settings;
    }
}
