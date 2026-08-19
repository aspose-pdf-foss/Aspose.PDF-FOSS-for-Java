package org.aspose.pdf;

/**
 * Options for saving a document as Office Open XML word-processing
 * ({@code .docx}). API-shaped after Aspose.PDF {@code DocSaveOptions}.
 *
 * <p>The FOSS converter reuses the structural IR (SDM) pipeline: the PDF is
 * projected to the Semantic Document Model (with tagged-structure or geometry
 * heuristics) and that model is serialized to WordprocessingML. The flags here
 * steer the shared enrichment the same way {@link HtmlSaveOptions} does for the
 * HTML writer.</p>
 */
public class DocSaveOptions extends SaveOptions {

    /**
     * Recognition granularity for the produced Word document
     * (Aspose {@code DocSaveOptions.RecognitionMode}).
     */
    public enum RecognitionMode {
        /**
         * Fastest, layout-faithful mode. In the FOSS pipeline this maps to a
         * shallow structural projection (paragraphs and detected tables) without
         * the full geometry heuristics.
         */
        Textbox,
        /**
         * Slower, flow-oriented mode that recognizes headings, lists and tables
         * so the result reflows and edits naturally. This is the default.
         */
        Flow
    }

    /**
     * Target Word format (Aspose {@code DocSaveOptions.DocFormat}). The FOSS
     * writer emits OOXML only: {@link #Doc} is accepted for API compatibility
     * and produces a {@code .docx}-structured package (binary Word 97 output
     * is not supported).
     */
    public enum DocFormat {
        /** Word 97-2003 request — served as OOXML (no binary writer). */
        Doc,
        /** Office Open XML (default). */
        DocX
    }

    private RecognitionMode recognitionMode = RecognitionMode.Flow;
    private DocFormat format = DocFormat.DocX;
    private boolean recognizeBullets;
    private boolean addReturnToLineEnd = true;
    private float relativeHorizontalProximity = 1.0f;
    private float maxDistanceBetweenTextLines;
    private int imageResolutionX = 300;
    private int imageResolutionY = 300;
    private boolean extractOcrSublayerOnly;
    private boolean tryMergeAdjacentSameBackgroundImages;
    private boolean convertType3Fonts;
    private boolean structuralHeuristics = true;
    private boolean suppressRunningHeadersFooters = true;
    private boolean rasterizeVectorGraphics = true;
    private int resolution = 150;

    /** Creates options with default (Flow) settings. */
    public DocSaveOptions() {
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link SaveFormat#DocX}
     */
    @Override
    public SaveFormat getSaveFormat() {
        return SaveFormat.DocX;
    }

    /**
     * Gets the recognition mode.
     *
     * @return the recognition mode, never {@code null}
     */
    public RecognitionMode getRecognitionMode() {
        return recognitionMode;
    }

    /**
     * Sets the recognition mode. {@link RecognitionMode#Flow} enables the
     * geometry heuristics (heading/list/table recognition); {@link
     * RecognitionMode#Textbox} keeps a shallow structural projection.
     *
     * @param mode the recognition mode; {@code null} resets to {@link RecognitionMode#Flow}
     */
    public void setRecognitionMode(RecognitionMode mode) {
        this.recognitionMode = mode == null ? RecognitionMode.Flow : mode;
    }

    /**
     * Gets the recognition mode (Aspose property name {@code Mode}).
     *
     * @return the recognition mode, never {@code null}
     */
    public RecognitionMode getMode() {
        return getRecognitionMode();
    }

    /**
     * Sets the recognition mode (Aspose property name {@code Mode}).
     *
     * @param mode the recognition mode; {@code null} resets to {@link RecognitionMode#Flow}
     */
    public void setMode(RecognitionMode mode) {
        setRecognitionMode(mode);
    }

    /**
     * Gets the target Word format.
     *
     * @return the format, never {@code null}
     */
    public DocFormat getFormat() {
        return format;
    }

    /**
     * Sets the target Word format. See {@link DocFormat#Doc} for the
     * binary-format caveat.
     *
     * @param format the format; {@code null} resets to {@link DocFormat#DocX}
     */
    public void setFormat(DocFormat format) {
        this.format = format == null ? DocFormat.DocX : format;
    }

    /**
     * Gets whether bullet/list recognition is requested.
     *
     * @return {@code true} when list recognition is on
     */
    public boolean isRecognizeBullets() {
        return recognizeBullets;
    }

    /**
     * Sets bullet/list recognition (Aspose {@code RecognizeBullets}). The FOSS
     * Flow pipeline always recognizes lists structurally; the flag is stored
     * for API compatibility and future tuning.
     *
     * @param value the flag
     */
    public void setRecognizeBullets(boolean value) {
        this.recognizeBullets = value;
    }

    /**
     * Gets whether an explicit return is added at each source line end.
     *
     * @return the flag (default {@code true})
     */
    public boolean isAddReturnToLineEnd() {
        return addReturnToLineEnd;
    }

    /**
     * Sets the Aspose {@code AddReturnToLineEnd} recognition hint. Stored for
     * API compatibility; the FOSS recognizer merges lines by geometry.
     *
     * @param value the flag
     */
    public void setAddReturnToLineEnd(boolean value) {
        this.addReturnToLineEnd = value;
    }

    /**
     * Gets the Aspose {@code RelativeHorizontalProximity} tuning value.
     *
     * @return the value (default {@code 1.0f})
     */
    public float getRelativeHorizontalProximity() {
        return relativeHorizontalProximity;
    }

    /**
     * Sets the Aspose {@code RelativeHorizontalProximity} tuning hint. Stored
     * for API compatibility; the FOSS recognizer uses its own gap metrics.
     *
     * @param value the value
     */
    public void setRelativeHorizontalProximity(float value) {
        this.relativeHorizontalProximity = value;
    }

    /**
     * Gets the Aspose {@code MaxDistanceBetweenTextLines} tuning value.
     *
     * @return the value (default {@code 0} = automatic)
     */
    public float getMaxDistanceBetweenTextLines() {
        return maxDistanceBetweenTextLines;
    }

    /**
     * Sets the Aspose {@code MaxDistanceBetweenTextLines} tuning hint. Stored
     * for API compatibility; the FOSS recognizer uses its own gap metrics.
     *
     * @param value the value
     */
    public void setMaxDistanceBetweenTextLines(float value) {
        this.maxDistanceBetweenTextLines = value;
    }

    /**
     * Gets the horizontal DPI for rasterized page regions.
     *
     * @return the DPI (default 300)
     */
    public int getImageResolutionX() {
        return imageResolutionX;
    }

    /**
     * Sets the horizontal DPI for rasterized page regions (vector underlays,
     * poster pages). Also drives {@link #setResolution(int)}.
     *
     * @param dpi the DPI
     */
    public void setImageResolutionX(int dpi) {
        this.imageResolutionX = dpi;
        setResolution(dpi);
    }

    /**
     * Gets the vertical DPI for rasterized page regions.
     *
     * @return the DPI (default 300)
     */
    public int getImageResolutionY() {
        return imageResolutionY;
    }

    /**
     * Sets the vertical DPI for rasterized page regions. The FOSS rasterizer
     * is isotropic: the effective DPI is driven by {@link #setResolution(int)}.
     *
     * @param dpi the DPI
     */
    public void setImageResolutionY(int dpi) {
        this.imageResolutionY = dpi;
        setResolution(dpi);
    }

    /**
     * Gets the OCR-sublayer extraction flag.
     *
     * @return the flag
     */
    public boolean isExtractOcrSublayerOnly() {
        return extractOcrSublayerOnly;
    }

    /**
     * Sets the Aspose {@code ExtractOcrSublayerOnly} hint (use the invisible
     * OCR text layer instead of the scan raster). Stored for API
     * compatibility; the FOSS extractor reads all text layers.
     *
     * @param value the flag
     */
    public void setExtractOcrSublayerOnly(boolean value) {
        this.extractOcrSublayerOnly = value;
    }

    /**
     * Gets the adjacent-background-image merge flag.
     *
     * @return the flag
     */
    public boolean isTryMergeAdjacentSameBackgroundImages() {
        return tryMergeAdjacentSameBackgroundImages;
    }

    /**
     * Sets the Aspose {@code TryMergeAdjacentSameBackgroundImages} hint.
     * Stored for API compatibility; the FOSS pipeline rasterizes vector
     * regions as single underlays already.
     *
     * @param value the flag
     */
    public void setTryMergeAdjacentSameBackgroundImages(boolean value) {
        this.tryMergeAdjacentSameBackgroundImages = value;
    }

    /**
     * Gets the Type3-font conversion flag.
     *
     * @return the flag
     */
    public boolean isConvertType3Fonts() {
        return convertType3Fonts;
    }

    /**
     * Sets the Aspose {@code ConvertType3Fonts} hint. Stored for API
     * compatibility; Type3 glyph text is extracted through the normal
     * decoder.
     *
     * @param value the flag
     */
    public void setConvertType3Fonts(boolean value) {
        this.convertType3Fonts = value;
    }

    /**
     * Gets whether geometry heuristics may run on untagged content.
     *
     * @return {@code true} if heuristics are enabled (default)
     */
    public boolean isStructuralHeuristics() {
        return structuralHeuristics && recognitionMode == RecognitionMode.Flow;
    }

    /**
     * Sets whether geometry heuristics may run on untagged content.
     *
     * @param value {@code true} to enable heuristics
     */
    public void setStructuralHeuristics(boolean value) {
        this.structuralHeuristics = value;
    }

    /**
     * Gets whether repeated per-page running headers/footers are dropped.
     *
     * @return {@code true} if suppressed (default)
     */
    public boolean isSuppressRunningHeadersFooters() {
        return suppressRunningHeadersFooters;
    }

    /**
     * Sets whether repeated per-page running headers/footers are dropped.
     *
     * @param value {@code true} to remove page furniture
     */
    public void setSuppressRunningHeadersFooters(boolean value) {
        this.suppressRunningHeadersFooters = value;
    }

    /**
     * Gets whether vector-graphics regions (charts, shapes, fills) are
     * rasterized into embedded images.
     *
     * @return {@code true} if vector graphics are rasterized (default)
     */
    public boolean isRasterizeVectorGraphics() {
        return rasterizeVectorGraphics;
    }

    /**
     * Sets whether vector-graphics regions are rasterized into embedded images.
     *
     * @param value {@code true} to rasterize vector regions
     */
    public void setRasterizeVectorGraphics(boolean value) {
        this.rasterizeVectorGraphics = value;
    }

    /**
     * Gets the resolution (DPI) used when rasterizing regions/images. Stored for
     * API compatibility.
     *
     * @return the resolution in DPI
     */
    public int getResolution() {
        return resolution;
    }

    /**
     * Sets the resolution (DPI) used when rasterizing regions/images. Stored for
     * API compatibility.
     *
     * @param dpi the resolution in DPI
     */
    public void setResolution(int dpi) {
        this.resolution = dpi;
    }
}
