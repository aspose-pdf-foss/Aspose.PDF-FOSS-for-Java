package org.aspose.pdf;

/**
 * Options for loading an HTML document into a PDF document.
 */
public class HtmlLoadOptions extends LoadOptions {

    /**
     * {@inheritDoc}
     *
     * @return always {@link LoadFormat#HTML}
     */
    @Override
    public LoadFormat getLoadFormat() {
        return LoadFormat.HTML;
    }

    /** Page layout information for the resulting PDF. */
    private PageInfo pageInfo = new PageInfo();

    /** Base path for resolving relative image and resource paths. */
    private String basePath;

    /** Whether external http/https resources may be fetched (SDM pipeline). */
    private boolean allowNetworkResources;

    /** Whether to disable font license verifications during loading. */
    private boolean disableFontLicenseVerifications;

    /** Whether CSS overflow area clipping is applied during conversion. */
    private boolean useAreaClipping = true;

    /** CSS media type used during conversion. */
    private HtmlMediaType htmlMediaType = HtmlMediaType.Print;

    /** Page-layout adjustment used during conversion. */
    private HtmlPageLayoutOption pageLayoutOption = HtmlPageLayoutOption.None;

    /** Character encoding forced on the HTML input; {@code null} auto-detects. */
    private String inputEncoding;

    /** Whether all HTML content is laid out on a single (tall) PDF page. */
    private boolean renderToSinglePage;

    /**
     * Whether to route HTML loading through the SDM pipeline (IR Stage 4:
     * {@code HtmlSdmReader} &rarr; {@code SdmPdfLayout}) instead of the legacy
     * {@code HtmlToPdfConverter}. Default {@code true} — the SDM pipeline
     * resolves the CSS cascade and lays out real-world HTML faithfully, whereas
     * the legacy converter drops stylesheets and often yields a blank page.
     * Set {@code false} only to force the legacy DOM converter.
     */
    private boolean useSdmPipeline = true;

    /**
     * Creates a new {@code HtmlLoadOptions} with default settings.
     */
    public HtmlLoadOptions() {
    }

    /**
     * Creates a new {@code HtmlLoadOptions} with the specified base path.
     *
     * @param basePath the base path for resolving relative resources
     */
    public HtmlLoadOptions(String basePath) {
        this.basePath = basePath;
    }

    /**
     * Gets the page layout information for the resulting PDF.
     *
     * @return the page info, or {@code null} if not set
     */
    public PageInfo getPageInfo() {
        return pageInfo;
    }

    /**
     * Sets the page layout information for the resulting PDF.
     *
     * @param pageInfo the page info to set
     */
    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }

    /**
     * Gets the base path for resolving relative image and resource paths.
     *
     * @return the base path, or {@code null} if not set
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * Sets the base path for resolving relative image and resource paths.
     *
     * @param basePath the base path
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }

    /**
     * Gets whether external {@code http:}/{@code https:} resources may be fetched
     * over the network when loading via the SDM pipeline
     * ({@link #setUseSdmPipeline(boolean)} / {@code new Document(..., HtmlLoadOptions)}).
     *
     * @return {@code true} if network resource fetching is permitted
     */
    public boolean isAllowNetworkResources() {
        return allowNetworkResources;
    }

    /**
     * Sets whether external {@code http:}/{@code https:} resources ({@code <img>},
     * {@code <link rel=stylesheet>}) may be fetched over the network. Default
     * {@code false} — only {@code data:} URIs and local files resolved against
     * {@link #getBasePath()} are loaded. Applies to the SDM pipeline only.
     *
     * @param allowNetworkResources {@code true} to permit network fetches
     */
    public void setAllowNetworkResources(boolean allowNetworkResources) {
        this.allowNetworkResources = allowNetworkResources;
    }

    /**
     * Gets whether font license verifications are disabled during loading.
     *
     * @return {@code true} if font license verifications are disabled
     */
    public boolean isDisableFontLicenseVerifications() {
        return disableFontLicenseVerifications;
    }

    /**
     * Sets whether to disable font license verifications during loading.
     *
     * @param disable {@code true} to disable font license verifications
     */
    public void setDisableFontLicenseVerifications(boolean disable) {
        this.disableFontLicenseVerifications = disable;
    }

    /**
     * Gets whether area clipping (CSS overflow clipping of positioned blocks)
     * is applied during conversion. API-compatible with Aspose
     * {@code HtmlLoadOptions.UseAreaClipping}; the FOSS HTML loader stores the
     * flag but does not clip yet.
     *
     * @return {@code true} if area clipping is requested
     */
    public boolean isUseAreaClipping() {
        return useAreaClipping;
    }

    /**
     * Sets whether area clipping is applied during conversion. Stored for API
     * compatibility.
     *
     * @param useAreaClipping {@code true} to request area clipping
     */
    public void setUseAreaClipping(boolean useAreaClipping) {
        this.useAreaClipping = useAreaClipping;
    }

    /**
     * Gets the CSS media type used during conversion. Stored for API
     * compatibility; the FOSS loader does not evaluate {@code @media} blocks yet.
     *
     * @return the media type
     */
    public HtmlMediaType getHtmlMediaType() {
        return htmlMediaType;
    }

    /**
     * Sets the CSS media type used during conversion. Stored for API compatibility.
     *
     * @param htmlMediaType the media type; {@code null} keeps the current value
     */
    public void setHtmlMediaType(HtmlMediaType htmlMediaType) {
        if (htmlMediaType != null) {
            this.htmlMediaType = htmlMediaType;
        }
    }

    /**
     * Gets the page-layout adjustment used during conversion. Stored for API
     * compatibility.
     *
     * @return the page layout option
     */
    public HtmlPageLayoutOption getPageLayoutOption() {
        return pageLayoutOption;
    }

    /**
     * Sets the page-layout adjustment used during conversion. Stored for API
     * compatibility.
     *
     * @param pageLayoutOption the page layout option; {@code null} keeps the current value
     */
    public void setPageLayoutOption(HtmlPageLayoutOption pageLayoutOption) {
        if (pageLayoutOption != null) {
            this.pageLayoutOption = pageLayoutOption;
        }
    }

    /**
     * Gets the character encoding forced on the HTML input, or {@code null}
     * to auto-detect. Stored for API compatibility.
     *
     * @return the input encoding name, or {@code null}
     */
    public String getInputEncoding() {
        return inputEncoding;
    }

    /**
     * Sets the character encoding forced on the HTML input. Stored for API
     * compatibility.
     *
     * @param inputEncoding the encoding name, or {@code null} to auto-detect
     */
    public void setInputEncoding(String inputEncoding) {
        this.inputEncoding = inputEncoding;
    }

    /**
     * Gets whether all HTML content is laid out on one (tall) PDF page
     * (Aspose {@code HtmlLoadOptions.IsRenderToSinglePage}). Stored for API
     * compatibility; the FOSS loader paginates normally.
     *
     * @return {@code true} if single-page rendering is requested
     */
    public boolean isRenderToSinglePage() {
        return renderToSinglePage;
    }

    /**
     * Sets whether all HTML content is laid out on one (tall) PDF page.
     * Stored for API compatibility.
     *
     * @param renderToSinglePage {@code true} to request single-page rendering
     */
    public void setRenderToSinglePage(boolean renderToSinglePage) {
        this.renderToSinglePage = renderToSinglePage;
    }

    /**
     * Gets whether HTML loading is routed through the SDM pipeline
     * ({@code HtmlSdmReader} &rarr; {@code SdmPdfLayout}, IR Stage 4) rather than
     * the legacy {@code HtmlToPdfConverter}.
     *
     * @return {@code true} if the SDM pipeline is used
     */
    public boolean isUseSdmPipeline() {
        return useSdmPipeline;
    }

    /**
     * Sets whether HTML loading is routed through the SDM pipeline. When
     * {@code true} (the default), the CSS-aware SDM pipeline is used. Set
     * {@code false} to fall back to the legacy DOM-based converter.
     *
     * @param useSdmPipeline {@code true} to use the SDM pipeline
     */
    public void setUseSdmPipeline(boolean useSdmPipeline) {
        this.useSdmPipeline = useSdmPipeline;
    }
}
