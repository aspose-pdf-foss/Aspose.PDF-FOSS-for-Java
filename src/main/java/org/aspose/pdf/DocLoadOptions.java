package org.aspose.pdf;

/**
 * Options for loading (converting) an Office Open XML word-processing document
 * ({@code .docx}) into a PDF — the load-side mirror of {@link DocSaveOptions}.
 *
 * <p>Used through the uniform constructors: the concrete options type selects
 * the source format (there is no per-format factory):</p>
 *
 * <pre>
 *   Document doc = new Document("report.docx", new DocLoadOptions());
 *   doc.save("report.pdf");
 * </pre>
 *
 * <p>The pipeline is the SDM engine: {@code DocxSdmReader} (DOCX &rarr; model)
 * then {@code SdmPdfLayout} (model &rarr; paginated PDF) — the same layout the
 * HTML loader uses. When no {@link PageInfo} is set, the page size recorded in
 * the document's {@code sectPr} is honoured.</p>
 */
public class DocLoadOptions extends LoadOptions {

    private PageInfo pageInfo;

    /** Creates options with default settings (page geometry from the DOCX). */
    public DocLoadOptions() {
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link LoadFormat#DocX}
     */
    @Override
    public LoadFormat getLoadFormat() {
        return LoadFormat.DocX;
    }

    /**
     * Gets the explicit page geometry, or {@code null} to use the size from the
     * document's section properties.
     *
     * @return the page info, or {@code null}
     */
    public PageInfo getPageInfo() {
        return pageInfo;
    }

    /**
     * Sets an explicit page geometry, overriding the document's own page size.
     *
     * @param pageInfo the page info; {@code null} restores the default
     */
    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }
}
