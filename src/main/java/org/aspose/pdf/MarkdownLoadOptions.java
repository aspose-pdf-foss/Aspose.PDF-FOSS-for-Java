package org.aspose.pdf;

/**
 * Options for loading (converting) a Markdown document ({@code .md}) into a PDF
 * — the load-side mirror of {@link MarkdownSaveOptions}.
 *
 * <p>Used through the uniform constructors: the concrete options type selects
 * the source format (there is no per-format factory):</p>
 *
 * <pre>
 *   Document doc = new Document("notes.md", new MarkdownLoadOptions());
 *   doc.save("notes.pdf");
 * </pre>
 *
 * <p>The pipeline is the SDM engine: {@code MarkdownSdmReader} (Markdown &rarr;
 * model) then {@code SdmPdfLayout} (model &rarr; paginated PDF) — the same second
 * half the HTML, DOCX and XLSX loaders use. When no {@link PageInfo} is set an
 * A4 page with default margins is used and content reflows into it.</p>
 */
public class MarkdownLoadOptions extends LoadOptions {

    private PageInfo pageInfo;

    /** Creates options with default settings. */
    public MarkdownLoadOptions() {
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link LoadFormat#Markdown}
     */
    @Override
    public LoadFormat getLoadFormat() {
        return LoadFormat.Markdown;
    }

    /**
     * Gets the explicit page geometry, or {@code null} to use the default page.
     *
     * @return the page info, or {@code null}
     */
    public PageInfo getPageInfo() {
        return pageInfo;
    }

    /**
     * Sets an explicit page geometry (size and margins) for the produced PDF.
     *
     * @param pageInfo the page info; {@code null} restores the default
     */
    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }
}
