package org.aspose.pdf;

/**
 * Options for loading (converting) an Office Open XML spreadsheet
 * ({@code .xlsx}) into a PDF &mdash; the inverse of {@link ExcelSaveOptions} and
 * the spreadsheet sibling of {@link DocLoadOptions}.
 *
 * <p>The FOSS loader parses each worksheet into the Semantic Document Model (a
 * table of typed, styled cells) with {@code XlsxSdmReader}, then paginates it to
 * PDF with the shared {@code SdmPdfLayout}. An explicit {@link #getPageInfo()}
 * fixes the page geometry; otherwise a default portrait page is used and wide
 * tables wrap into it.</p>
 */
public class ExcelLoadOptions extends LoadOptions {

    private PageInfo pageInfo;
    private boolean interactiveForms;

    /** Creates options with default settings. */
    public ExcelLoadOptions() {
    }

    /**
     * Returns whether the spreadsheet is converted to a "live" interactive PDF:
     * cell values become AcroForm fields and cell formulas become PDF JavaScript
     * calculate actions (recalculated by Adobe Acrobat/Reader and Foxit). When
     * {@code false} (the default) the loader produces a static, print-faithful
     * table with plain text cells.
     *
     * @return true if interactive form conversion is enabled
     */
    public boolean isInteractiveForms() {
        return interactiveForms;
    }

    /**
     * Enables or disables "live" interactive form conversion. See
     * {@link #isInteractiveForms()}.
     *
     * <p><b>Viewer note:</b> formula recalculation runs only in viewers that
     * execute AcroForm JavaScript (Adobe Acrobat/Reader, Foxit). Other viewers
     * (browser built-ins, most mobile) show fillable fields carrying the last
     * computed values without live recalculation.</p>
     *
     * @param interactiveForms true to emit form fields and formula JavaScript
     */
    public void setInteractiveForms(boolean interactiveForms) {
        this.interactiveForms = interactiveForms;
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link LoadFormat#Xlsx}
     */
    @Override
    public LoadFormat getLoadFormat() {
        return LoadFormat.Xlsx;
    }

    /**
     * Gets the explicit page geometry to lay the spreadsheet into, or {@code null}
     * to use a default page.
     *
     * @return the page info, or {@code null}
     */
    public PageInfo getPageInfo() {
        return pageInfo;
    }

    /**
     * Sets an explicit page geometry (size and margins) for the produced PDF.
     *
     * @param pageInfo the page info, or {@code null} for the default
     */
    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }
}
