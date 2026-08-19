package org.aspose.pdf;

/**
 * Options for saving a document in HTML format.
 *
 * <p>API-compatible with Aspose.PDF {@code HtmlSaveOptions}. Options whose
 * rendering effect the FOSS HTML writer does not implement yet are accepted
 * and stored (so option-setting code ports cleanly) but may not change the
 * output; each such property documents this.</p>
 */
public class HtmlSaveOptions extends SaveOptions {

    /**
     * {@inheritDoc}
     *
     * @return always {@link SaveFormat#Html}
     */
    @Override
    public SaveFormat getSaveFormat() {
        return SaveFormat.Html;
    }

    /**
     * Font storage formats for fonts referenced from the output HTML
     * (Aspose {@code HtmlSaveOptions.FontSavingModes}).
     */
    public enum FontSavingModes {
        /** Always save fonts as WOFF. */
        AlwaysSaveAsWOFF,
        /** Always save fonts as TTF. */
        AlwaysSaveAsTTF,
        /** Always save fonts as EOT. */
        AlwaysSaveAsEOT,
        /** Save each font in all supported formats. */
        SaveInAllFormats,
        /** Do not save fonts at all. */
        DontSave
    }

    /**
     * How raster images are materialized in the output
     * (Aspose {@code HtmlSaveOptions.RasterImagesSavingModes}).
     */
    public enum RasterImagesSavingModes {
        /** Embed raster images as PNG data inside generated SVG. */
        AsPngImagesEmbeddedIntoSvg,
        /** Save raster images as external PNG files referenced via SVG. */
        AsExternalPngFilesReferencedViaSvg,
        /** Merge raster images into a single PNG page background. */
        AsEmbeddedPartsOfPngPageBackground
    }

    /**
     * Which parts of the output are embedded into the HTML file
     * (Aspose {@code HtmlSaveOptions.PartsEmbeddingModes}).
     */
    public enum PartsEmbeddingModes {
        /** Embed all resources (CSS, images, fonts) into the HTML. */
        EmbedAllIntoHtml,
        /** Embed only CSS; keep other resources external. */
        EmbedCssOnly,
        /** Keep all resources external. */
        NoEmbedding
    }

    /**
     * Overall markup shape of the generated file
     * (Aspose {@code HtmlSaveOptions.HtmlMarkupGenerationModes}).
     */
    public enum HtmlMarkupGenerationModes {
        /** Generate a complete HTML document. */
        WriteAllHtml,
        /** Generate only the body content. */
        WriteOnlyBodyContent
    }

    /**
     * Strategy for positioning letters in CSS
     * (Aspose {@code LettersPositioningMethods}).
     */
    public enum LettersPositioningMethods {
        /** Use em units and compensate rounding errors in CSS. */
        UseEmUnitsAndCompensationOfRoundingErrorsInCss,
        /** Use pixel units in CSS letter-spacing (IE compatibility). */
        UsePixelUnitsInCssLetterSpacingForIE
    }

    /**
     * Anti-aliasing post-processing mode (Aspose {@code AntialiasingProcessingType}).
     */
    public enum AntialiasingProcessingType {
        /** No additional processing. */
        NoAdditionalProcessing,
        /** Try to correct the result HTML for anti-aliasing artifacts. */
        TryCorrectResultHtml
    }

    /** The type of HTML document to generate. */
    private HtmlDocumentType documentType = HtmlDocumentType.Html5;

    /** Whether to use fixed layout rendering. */
    private boolean fixedLayout = true;

    /** Output mode for {@link Document#save(String, HtmlSaveOptions)}: structural (semantic) vs fixed-layout. */
    private HtmlOutputMode outputMode = HtmlOutputMode.FIXED_LAYOUT;

    /** Whether the structural mode may apply geometry heuristics on untagged content. */
    private boolean structuralHeuristics = true;
    private boolean suppressRunningHeadersFooters = true;

    /** Whether vector-graphics page regions (charts, shapes, fills) are
     *  rasterized into {@code <img>} content in the HTML output. */
    private boolean rasterizeVectorGraphics = true;

    /** Whether to split the document into separate HTML pages. */
    private boolean splitIntoPages = false;

    /** Whether to embed images as base64 data URIs in the HTML. */
    private boolean embedImages = true;

    /** The scale factor applied to the output. */
    private double scale = 1.0;

    /** The folder path for storing external images when {@code embedImages} is {@code false}. */
    private String imageFolder;

    /** A prefix to prepend to CSS class names. */
    private String cssPrefix = "";

    /** Font saving mode; stored for API compatibility. */
    private FontSavingModes fontSavingMode = FontSavingModes.AlwaysSaveAsWOFF;
    /** Raster image saving mode; stored for API compatibility. */
    private RasterImagesSavingModes rasterImagesSavingMode = RasterImagesSavingModes.AsPngImagesEmbeddedIntoSvg;
    /** Parts embedding mode; stored for API compatibility. */
    private PartsEmbeddingModes partsEmbeddingMode = PartsEmbeddingModes.EmbedAllIntoHtml;
    /** Markup generation mode; stored for API compatibility. */
    private HtmlMarkupGenerationModes htmlMarkupGenerationMode = HtmlMarkupGenerationModes.WriteAllHtml;
    /** Letters positioning method; stored for API compatibility. */
    private LettersPositioningMethods lettersPositioningMethod =
            LettersPositioningMethods.UseEmUnitsAndCompensationOfRoundingErrorsInCss;
    /** Anti-aliasing processing; stored for API compatibility. */
    private AntialiasingProcessingType antialiasingProcessing = AntialiasingProcessingType.NoAdditionalProcessing;

    private boolean saveTransparentTexts;
    private boolean saveShadowedTextsAsTransparentTexts;
    private boolean compressSvgGraphicsIfAny = true;
    private boolean useZOrder;
    private boolean splitCssIntoPages;
    private int[] explicitListOfSavedPages;
    private String defaultFontName;
    private String[] excludeFontNameList;
    private boolean ignoreResourceFontErrors;
    private double ignoredTextFontSize;
    private int imageResolution = 96;
    private boolean preventGlyphsGrouping;
    private boolean renderTextAsImage;
    private String title;
    private boolean removeEmptyAreasOnTopAndBottom;
    private int batchSize;
    private boolean simpleTextboxModeGrouping;
    private double minimalLineWidth;
    private boolean flowLayoutParagraphFullWidth;
    private boolean pagesFlowTypeDependsOnViewersScreenSize;
    private boolean trySaveTextUnderliningAndStrikeoutingInCss;
    private String specialFolderForSvgImages;

    /** Creates options with default settings. */
    public HtmlSaveOptions() {
    }

    /**
     * Creates options producing the given HTML document type.
     *
     * @param documentType the document type; {@code null} keeps the default
     */
    public HtmlSaveOptions(HtmlDocumentType documentType) {
        if (documentType != null) {
            this.documentType = documentType;
        }
    }

    /**
     * Gets the HTML document type.
     *
     * @return the document type
     */
    public HtmlDocumentType getDocumentType() {
        return documentType;
    }

    /**
     * Sets the HTML document type.
     *
     * @param documentType the document type to set
     */
    public void setDocumentType(HtmlDocumentType documentType) {
        this.documentType = documentType;
    }

    /**
     * Gets whether fixed layout rendering is enabled.
     *
     * @return {@code true} if fixed layout is enabled
     */
    public boolean isFixedLayout() {
        return fixedLayout;
    }

    /**
     * Sets whether to use fixed layout rendering.
     *
     * @param fixedLayout {@code true} to enable fixed layout
     */
    public void setFixedLayout(boolean fixedLayout) {
        this.fixedLayout = fixedLayout;
    }

    /**
     * Gets the output mode used by {@link Document#save(String, HtmlSaveOptions)}.
     *
     * @return the output mode, never {@code null}
     */
    public HtmlOutputMode getOutputMode() {
        return outputMode;
    }

    /**
     * Sets the output mode used by {@link Document#save(String, HtmlSaveOptions)}:
     * {@link HtmlOutputMode#STRUCTURAL} (semantic h/p/ul/table) or
     * {@link HtmlOutputMode#FIXED_LAYOUT} (positioned visual copy, the default).
     *
     * @param outputMode the mode; {@code null} resets to {@link HtmlOutputMode#FIXED_LAYOUT}
     */
    public void setOutputMode(HtmlOutputMode outputMode) {
        this.outputMode = outputMode == null ? HtmlOutputMode.FIXED_LAYOUT : outputMode;
    }

    /**
     * Gets whether structural mode may apply geometry heuristics
     * (heading/list/table recognition) to untagged content.
     *
     * @return {@code true} if heuristics are enabled (default)
     */
    public boolean isStructuralHeuristics() {
        return structuralHeuristics;
    }

    /**
     * Sets whether structural mode may apply geometry heuristics to untagged
     * content. When disabled, untagged documents produce shallow structural
     * HTML (paragraphs only).
     *
     * @param structuralHeuristics {@code true} to enable heuristics
     */
    public void setStructuralHeuristics(boolean structuralHeuristics) {
        this.structuralHeuristics = structuralHeuristics;
    }

    /**
     * Gets whether structural mode drops running page headers and footers.
     *
     * <p>A reflowed structural HTML stream has no page boundaries, so a per-page
     * running title, footer or page number becomes a long ladder of near
     * identical lines that swamps the real content. When enabled (default),
     * structural mode detects such repeated top/bottom-of-page furniture across
     * the document and removes it. Disable to keep every page's furniture inline.</p>
     *
     * @return {@code true} if running headers/footers are suppressed (default)
     */
    public boolean isSuppressRunningHeadersFooters() {
        return suppressRunningHeadersFooters;
    }

    /**
     * Sets whether structural mode drops running page headers and footers.
     *
     * @param suppressRunningHeadersFooters {@code true} to remove repeated
     *        top/bottom-of-page furniture from the reflowed stream
     */
    public void setSuppressRunningHeadersFooters(boolean suppressRunningHeadersFooters) {
        this.suppressRunningHeadersFooters = suppressRunningHeadersFooters;
    }

    /**
     * Gets whether vector-graphics page regions are rasterized into the HTML.
     *
     * <p>HTML output carries text and raster images but no PDF path/fill/stroke
     * content — without rasterization a bar chart, plot, filled shape or 3D
     * preview renders as nothing (only its text labels survive). When enabled
     * (default), vector-graphics regions are rendered to PNG and emitted as
     * {@code <img>}: in structural mode as figures in the flow, in fixed-layout
     * mode as a per-page vector underlay behind the positioned text.</p>
     *
     * @return {@code true} if vector graphics are rasterized (default)
     */
    public boolean isRasterizeVectorGraphics() {
        return rasterizeVectorGraphics;
    }

    /**
     * Sets whether vector-graphics page regions are rasterized into the HTML.
     *
     * @param rasterizeVectorGraphics {@code true} to render vector regions to
     *        PNG {@code <img>} content
     */
    public void setRasterizeVectorGraphics(boolean rasterizeVectorGraphics) {
        this.rasterizeVectorGraphics = rasterizeVectorGraphics;
    }

    /**
     * Gets whether the document is split into separate HTML pages.
     *
     * @return {@code true} if splitting is enabled
     */
    public boolean isSplitIntoPages() {
        return splitIntoPages;
    }

    /**
     * Sets whether to split the document into separate HTML pages.
     *
     * @param splitIntoPages {@code true} to split into pages
     */
    public void setSplitIntoPages(boolean splitIntoPages) {
        this.splitIntoPages = splitIntoPages;
    }

    /**
     * Gets whether images are embedded as base64 data URIs.
     *
     * @return {@code true} if images are embedded
     */
    public boolean isEmbedImages() {
        return embedImages;
    }

    /**
     * Sets whether to embed images as base64 data URIs.
     *
     * @param embedImages {@code true} to embed images
     */
    public void setEmbedImages(boolean embedImages) {
        this.embedImages = embedImages;
    }

    /**
     * Gets the scale factor applied to the output.
     *
     * @return the scale factor
     */
    public double getScale() {
        return scale;
    }

    /**
     * Sets the scale factor applied to the output.
     *
     * @param scale the scale factor
     */
    public void setScale(double scale) {
        this.scale = scale;
    }

    /**
     * Gets the folder path for storing external images.
     * Used when {@code embedImages} is {@code false}.
     *
     * @return the image folder path, or {@code null} if not set
     */
    public String getImageFolder() {
        return imageFolder;
    }

    /**
     * Sets the folder path for storing external images.
     * Used when {@code embedImages} is {@code false}.
     *
     * @param imageFolder the image folder path
     */
    public void setImageFolder(String imageFolder) {
        this.imageFolder = imageFolder;
    }

    /**
     * Gets the prefix prepended to CSS class names.
     *
     * @return the CSS prefix
     */
    public String getCssPrefix() {
        return cssPrefix;
    }

    /**
     * Sets the prefix to prepend to CSS class names.
     *
     * @param cssPrefix the CSS prefix
     */
    public void setCssPrefix(String cssPrefix) {
        this.cssPrefix = cssPrefix;
    }

    /**
     * Gets the prefix prepended to CSS class names
     * (Aspose name for {@link #getCssPrefix()}).
     *
     * @return the CSS class-names prefix
     */
    public String getCssClassNamesPrefix() {
        return cssPrefix;
    }

    /**
     * Sets the prefix prepended to CSS class names
     * (Aspose name for {@link #setCssPrefix(String)}).
     *
     * @param prefix the CSS class-names prefix
     */
    public void setCssClassNamesPrefix(String prefix) {
        this.cssPrefix = prefix;
    }

    /**
     * Gets the font saving mode.
     * <p>The FOSS writer does not save standalone font files yet; the value is
     * stored for API compatibility.</p>
     *
     * @return the font saving mode
     */
    public FontSavingModes getFontSavingMode() {
        return fontSavingMode;
    }

    /**
     * Sets the font saving mode. Stored for API compatibility; the FOSS
     * writer does not save standalone font files yet.
     *
     * @param mode the font saving mode
     */
    public void setFontSavingMode(FontSavingModes mode) {
        this.fontSavingMode = mode;
    }

    /**
     * Gets the raster-images saving mode. Stored for API compatibility.
     *
     * @return the raster-images saving mode
     */
    public RasterImagesSavingModes getRasterImagesSavingMode() {
        return rasterImagesSavingMode;
    }

    /**
     * Sets the raster-images saving mode. Stored for API compatibility.
     *
     * @param mode the raster-images saving mode
     */
    public void setRasterImagesSavingMode(RasterImagesSavingModes mode) {
        this.rasterImagesSavingMode = mode;
    }

    /**
     * Gets the parts embedding mode. {@link PartsEmbeddingModes#EmbedAllIntoHtml}
     * corresponds to {@code embedImages=true}; other modes keep resources external.
     *
     * @return the parts embedding mode
     */
    public PartsEmbeddingModes getPartsEmbeddingMode() {
        return partsEmbeddingMode;
    }

    /**
     * Sets the parts embedding mode. Also toggles {@link #isEmbedImages()}
     * so the writer's single-file/external-resources behavior follows.
     *
     * @param mode the parts embedding mode
     */
    public void setPartsEmbeddingMode(PartsEmbeddingModes mode) {
        this.partsEmbeddingMode = mode;
        if (mode != null) {
            this.embedImages = (mode == PartsEmbeddingModes.EmbedAllIntoHtml);
        }
    }

    /**
     * Gets the markup generation mode. Stored for API compatibility.
     *
     * @return the markup generation mode
     */
    public HtmlMarkupGenerationModes getHtmlMarkupGenerationMode() {
        return htmlMarkupGenerationMode;
    }

    /**
     * Sets the markup generation mode. Stored for API compatibility.
     *
     * @param mode the markup generation mode
     */
    public void setHtmlMarkupGenerationMode(HtmlMarkupGenerationModes mode) {
        this.htmlMarkupGenerationMode = mode;
    }

    /**
     * Gets the letters positioning method. Stored for API compatibility.
     *
     * @return the letters positioning method
     */
    public LettersPositioningMethods getLettersPositioningMethod() {
        return lettersPositioningMethod;
    }

    /**
     * Sets the letters positioning method. Stored for API compatibility.
     *
     * @param method the letters positioning method
     */
    public void setLettersPositioningMethod(LettersPositioningMethods method) {
        this.lettersPositioningMethod = method;
    }

    /**
     * Gets the anti-aliasing processing type. Stored for API compatibility.
     *
     * @return the anti-aliasing processing type
     */
    public AntialiasingProcessingType getAntialiasingProcessing() {
        return antialiasingProcessing;
    }

    /**
     * Sets the anti-aliasing processing type. Stored for API compatibility.
     *
     * @param type the anti-aliasing processing type
     */
    public void setAntialiasingProcessing(AntialiasingProcessingType type) {
        this.antialiasingProcessing = type;
    }

    /**
     * Gets whether transparent texts are written into the HTML.
     * Stored for API compatibility.
     *
     * @return {@code true} if transparent texts are saved
     */
    public boolean isSaveTransparentTexts() {
        return saveTransparentTexts;
    }

    /**
     * Sets whether transparent texts are written into the HTML.
     * Stored for API compatibility.
     *
     * @param value {@code true} to save transparent texts
     */
    public void setSaveTransparentTexts(boolean value) {
        this.saveTransparentTexts = value;
    }

    /**
     * Gets whether shadowed texts are saved as transparent selectable texts.
     * Stored for API compatibility.
     *
     * @return {@code true} if shadowed texts are saved transparently
     */
    public boolean isSaveShadowedTextsAsTransparentTexts() {
        return saveShadowedTextsAsTransparentTexts;
    }

    /**
     * Sets whether shadowed texts are saved as transparent selectable texts.
     * Stored for API compatibility.
     *
     * @param value {@code true} to save shadowed texts transparently
     */
    public void setSaveShadowedTextsAsTransparentTexts(boolean value) {
        this.saveShadowedTextsAsTransparentTexts = value;
    }

    /**
     * Gets whether SVG graphics are compressed into SVGZ. Stored for API
     * compatibility; the FOSS writer emits uncompressed SVG.
     *
     * @return {@code true} if SVG compression is requested
     */
    public boolean isCompressSvgGraphicsIfAny() {
        return compressSvgGraphicsIfAny;
    }

    /**
     * Sets whether SVG graphics are compressed into SVGZ. Stored for API
     * compatibility.
     *
     * @param value {@code true} to request SVG compression
     */
    public void setCompressSvgGraphicsIfAny(boolean value) {
        this.compressSvgGraphicsIfAny = value;
    }

    /**
     * Gets whether content is emitted in z-order. Stored for API compatibility.
     *
     * @return {@code true} if z-order emission is requested
     */
    public boolean isUseZOrder() {
        return useZOrder;
    }

    /**
     * Sets whether content is emitted in z-order. Stored for API compatibility.
     *
     * @param value {@code true} to emit in z-order
     */
    public void setUseZOrder(boolean value) {
        this.useZOrder = value;
    }

    /**
     * Gets whether CSS is split into per-page files. Stored for API compatibility.
     *
     * @return {@code true} if per-page CSS splitting is requested
     */
    public boolean isSplitCssIntoPages() {
        return splitCssIntoPages;
    }

    /**
     * Sets whether CSS is split into per-page files. Stored for API compatibility.
     *
     * @param value {@code true} to split CSS per page
     */
    public void setSplitCssIntoPages(boolean value) {
        this.splitCssIntoPages = value;
    }

    /**
     * Gets the explicit list of 1-based page numbers to convert;
     * {@code null} converts all pages.
     *
     * @return the page list, or {@code null}
     */
    public int[] getExplicitListOfSavedPages() {
        return explicitListOfSavedPages;
    }

    /**
     * Sets the explicit list of 1-based page numbers to convert.
     *
     * @param pages the page list, or {@code null} for all pages
     */
    public void setExplicitListOfSavedPages(int[] pages) {
        this.explicitListOfSavedPages = pages;
    }

    /**
     * Gets the substitution font used when an embedded font cannot be
     * resolved. Stored for API compatibility.
     *
     * @return the default font name, or {@code null}
     */
    public String getDefaultFontName() {
        return defaultFontName;
    }

    /**
     * Sets the substitution font used when an embedded font cannot be
     * resolved. Stored for API compatibility.
     *
     * @param name the default font name
     */
    public void setDefaultFontName(String name) {
        this.defaultFontName = name;
    }

    /**
     * Gets the list of font names excluded from saving. Stored for API compatibility.
     *
     * @return the excluded font names, or {@code null}
     */
    public String[] getExcludeFontNameList() {
        return excludeFontNameList;
    }

    /**
     * Sets the list of font names excluded from saving. Stored for API compatibility.
     *
     * @param names the excluded font names
     */
    public void setExcludeFontNameList(String[] names) {
        this.excludeFontNameList = names;
    }

    /**
     * Gets whether resource font errors are ignored. Stored for API compatibility.
     *
     * @return {@code true} if font errors are ignored
     */
    public boolean isIgnoreResourceFontErrors() {
        return ignoreResourceFontErrors;
    }

    /**
     * Sets whether resource font errors are ignored. Stored for API compatibility.
     *
     * @param value {@code true} to ignore font errors
     */
    public void setIgnoreResourceFontErrors(boolean value) {
        this.ignoreResourceFontErrors = value;
    }

    /**
     * Gets the font size below which text is ignored. Stored for API compatibility.
     *
     * @return the ignored text font size
     */
    public double getIgnoredTextFontSize() {
        return ignoredTextFontSize;
    }

    /**
     * Sets the font size below which text is ignored. Stored for API compatibility.
     *
     * @param size the ignored text font size
     */
    public void setIgnoredTextFontSize(double size) {
        this.ignoredTextFontSize = size;
    }

    /**
     * Gets the resolution (DPI) used when rasterizing images. Stored for API
     * compatibility.
     *
     * @return the image resolution in DPI
     */
    public int getImageResolution() {
        return imageResolution;
    }

    /**
     * Sets the resolution (DPI) used when rasterizing images. Stored for API
     * compatibility.
     *
     * @param dpi the image resolution in DPI
     */
    public void setImageResolution(int dpi) {
        this.imageResolution = dpi;
    }

    /**
     * Gets whether glyph grouping is prevented. Stored for API compatibility.
     *
     * @return {@code true} if glyph grouping is prevented
     */
    public boolean isPreventGlyphsGrouping() {
        return preventGlyphsGrouping;
    }

    /**
     * Sets whether glyph grouping is prevented. Stored for API compatibility.
     *
     * @param value {@code true} to prevent glyph grouping
     */
    public void setPreventGlyphsGrouping(boolean value) {
        this.preventGlyphsGrouping = value;
    }

    /**
     * Gets whether text is rendered as image. Stored for API compatibility.
     *
     * @return {@code true} if text is rendered as image
     */
    public boolean isRenderTextAsImage() {
        return renderTextAsImage;
    }

    /**
     * Sets whether text is rendered as image. Stored for API compatibility.
     *
     * @param value {@code true} to render text as image
     */
    public void setRenderTextAsImage(boolean value) {
        this.renderTextAsImage = value;
    }

    /**
     * Gets the HTML document title; {@code null} derives it from metadata.
     *
     * @return the title, or {@code null}
     */
    public String getTitle() {
        return title;
    }

    /**
     * Sets the HTML document title.
     *
     * @param title the title
     */
    public void setTitle(String title) {
        this.title = title;
    }

    /**
     * Gets whether empty areas on top and bottom are removed. Stored for API
     * compatibility.
     *
     * @return {@code true} if empty areas are removed
     */
    public boolean isRemoveEmptyAreasOnTopAndBottom() {
        return removeEmptyAreasOnTopAndBottom;
    }

    /**
     * Sets whether empty areas on top and bottom are removed. Stored for API
     * compatibility.
     *
     * @param value {@code true} to remove empty areas
     */
    public void setRemoveEmptyAreasOnTopAndBottom(boolean value) {
        this.removeEmptyAreasOnTopAndBottom = value;
    }

    /**
     * Gets the conversion batch size (pages per batch); 0 means unbatched.
     * Stored for API compatibility.
     *
     * @return the batch size
     */
    public int getBatchSize() {
        return batchSize;
    }

    /**
     * Sets the conversion batch size (pages per batch). Stored for API
     * compatibility.
     *
     * @param batchSize the batch size
     */
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    /**
     * Gets whether simple textbox mode grouping is used. Stored for API
     * compatibility.
     *
     * @return {@code true} if simple textbox grouping is enabled
     */
    public boolean isSimpleTextboxModeGrouping() {
        return simpleTextboxModeGrouping;
    }

    /**
     * Sets whether simple textbox mode grouping is used. Stored for API
     * compatibility.
     *
     * @param value {@code true} to enable simple textbox grouping
     */
    public void setSimpleTextboxModeGrouping(boolean value) {
        this.simpleTextboxModeGrouping = value;
    }

    /**
     * Gets the minimal emitted line width in points. Stored for API compatibility.
     *
     * @return the minimal line width
     */
    public double getMinimalLineWidth() {
        return minimalLineWidth;
    }

    /**
     * Sets the minimal emitted line width in points. Stored for API compatibility.
     *
     * @param width the minimal line width
     */
    public void setMinimalLineWidth(double width) {
        this.minimalLineWidth = width;
    }

    /**
     * Gets whether flow-layout paragraphs take the full width. Stored for API
     * compatibility.
     *
     * @return {@code true} if paragraphs take the full width
     */
    public boolean isFlowLayoutParagraphFullWidth() {
        return flowLayoutParagraphFullWidth;
    }

    /**
     * Sets whether flow-layout paragraphs take the full width. Stored for API
     * compatibility.
     *
     * @param value {@code true} for full-width paragraphs
     */
    public void setFlowLayoutParagraphFullWidth(boolean value) {
        this.flowLayoutParagraphFullWidth = value;
    }

    /**
     * Gets whether pages' flow type depends on the viewer's screen size.
     * Stored for API compatibility.
     *
     * @return {@code true} if flow type is viewer-dependent
     */
    public boolean isPagesFlowTypeDependsOnViewersScreenSize() {
        return pagesFlowTypeDependsOnViewersScreenSize;
    }

    /**
     * Sets whether pages' flow type depends on the viewer's screen size.
     * Stored for API compatibility.
     *
     * @param value {@code true} for viewer-dependent flow
     */
    public void setPagesFlowTypeDependsOnViewersScreenSize(boolean value) {
        this.pagesFlowTypeDependsOnViewersScreenSize = value;
    }

    /**
     * Gets whether text underlining/strikeout is expressed in CSS. Stored for
     * API compatibility.
     *
     * @return {@code true} if underline/strikeout goes to CSS
     */
    public boolean isTrySaveTextUnderliningAndStrikeoutingInCss() {
        return trySaveTextUnderliningAndStrikeoutingInCss;
    }

    /**
     * Sets whether text underlining/strikeout is expressed in CSS. Stored for
     * API compatibility.
     *
     * @param value {@code true} to express underline/strikeout in CSS
     */
    public void setTrySaveTextUnderliningAndStrikeoutingInCss(boolean value) {
        this.trySaveTextUnderliningAndStrikeoutingInCss = value;
    }

    /**
     * Gets the folder that receives all extracted images
     * (Aspose {@code SpecialFolderForAllImages}); same storage as
     * {@link #getImageFolder()}.
     *
     * @return the image folder, or {@code null}
     */
    public String getSpecialFolderForAllImages() {
        return imageFolder;
    }

    /**
     * Sets the folder that receives all extracted images
     * (Aspose {@code SpecialFolderForAllImages}); same storage as
     * {@link #setImageFolder(String)}.
     *
     * @param folder the image folder
     */
    public void setSpecialFolderForAllImages(String folder) {
        this.imageFolder = folder;
    }

    /**
     * Gets the folder that receives extracted SVG images. Stored for API
     * compatibility.
     *
     * @return the SVG image folder, or {@code null}
     */
    public String getSpecialFolderForSvgImages() {
        return specialFolderForSvgImages;
    }

    /**
     * Sets the folder that receives extracted SVG images. Stored for API
     * compatibility.
     *
     * @param folder the SVG image folder
     */
    public void setSpecialFolderForSvgImages(String folder) {
        this.specialFolderForSvgImages = folder;
    }
}
