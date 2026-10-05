package org.aspose.pdf;

/**
 * Options for saving a document as an Office Open XML spreadsheet
 * ({@code .xlsx}). API-shaped after Aspose.PDF {@code ExcelSaveOptions}.
 *
 * <p>The FOSS converter reuses the structural IR (SDM) pipeline: the PDF is
 * projected to the Semantic Document Model (with tagged-structure or geometry
 * heuristics — the same table recognition the HTML/DOCX writers use), and every
 * recognised {@link org.aspose.pdf.sdm.Table} is serialized to a worksheet of
 * typed cells (number / date / boolean / text inferred from the cell text).
 * Formulas cannot be recovered from a PDF, so cells carry values only.</p>
 *
 * <p>The flags here steer the shared enrichment the same way
 * {@link HtmlSaveOptions} and {@link DocSaveOptions} do for their writers.</p>
 */
public class ExcelSaveOptions extends SaveOptions {

    /**
     * Target spreadsheet flavour (Aspose {@code ExcelSaveOptions.ExcelFormat}).
     * The FOSS writer emits an {@code .xlsx} OOXML package for every value;
     * non-XLSX flavours are accepted for API compatibility only.
     */
    public enum ExcelFormat {
        /** Office Open XML spreadsheet (default). */
        XLSX,
        /** SpreadsheetML 2003 request — served as XLSX. */
        XmlSpreadSheet2003,
        /** Comma-separated values request — served as XLSX. */
        CSV,
        /** OpenDocument spreadsheet request — served as XLSX. */
        ODS
    }

    private ExcelFormat format = ExcelFormat.XLSX;
    private boolean minimizeTheNumberOfWorksheets;
    private boolean insertBlankColumnAtFirst;
    private boolean uniformWorksheets;
    private boolean structuralHeuristics = true;
    private boolean suppressRunningHeadersFooters = true;

    /** Creates options with default (XLSX) settings. */
    public ExcelSaveOptions() {
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link SaveFormat#Xlsx}
     */
    @Override
    public SaveFormat getSaveFormat() {
        return SaveFormat.Xlsx;
    }

    /**
     * Gets the target spreadsheet flavour.
     *
     * @return the format, never {@code null}
     */
    public ExcelFormat getFormat() {
        return format;
    }

    /**
     * Sets the target spreadsheet flavour. See {@link ExcelFormat} for the
     * XLSX-only caveat.
     *
     * @param format the format; {@code null} resets to {@link ExcelFormat#XLSX}
     */
    public void setFormat(ExcelFormat format) {
        this.format = format == null ? ExcelFormat.XLSX : format;
    }

    /**
     * Gets whether all recognised tables are packed onto a single worksheet
     * (Aspose {@code MinimizeTheNumberOfWorksheets}).
     *
     * @return {@code true} to emit one worksheet
     */
    public boolean isMinimizeTheNumberOfWorksheets() {
        return minimizeTheNumberOfWorksheets;
    }

    /**
     * Sets whether all recognised tables are packed onto a single worksheet
     * (stacked with a blank separator row). When {@code false} (default) each
     * table becomes its own worksheet.
     *
     * @param value the flag
     */
    public void setMinimizeTheNumberOfWorksheets(boolean value) {
        this.minimizeTheNumberOfWorksheets = value;
    }

    /**
     * Gets the Aspose {@code InsertBlankColumnAtFirst} hint.
     *
     * @return the flag
     */
    public boolean isInsertBlankColumnAtFirst() {
        return insertBlankColumnAtFirst;
    }

    /**
     * Sets whether a blank leading column is inserted before the data (Aspose
     * {@code InsertBlankColumnAtFirst}).
     *
     * @param value the flag
     */
    public void setInsertBlankColumnAtFirst(boolean value) {
        this.insertBlankColumnAtFirst = value;
    }

    /**
     * Gets the Aspose {@code UniformWorksheets} hint.
     *
     * @return the flag
     */
    public boolean isUniformWorksheets() {
        return uniformWorksheets;
    }

    /**
     * Sets the Aspose {@code UniformWorksheets} hint (keep every worksheet's
     * column set uniform). Stored for API compatibility.
     *
     * @param value the flag
     */
    public void setUniformWorksheets(boolean value) {
        this.uniformWorksheets = value;
    }

    /**
     * Gets whether geometry heuristics may run on untagged content to recover
     * tables the structure tree does not mark.
     *
     * @return {@code true} if heuristics are enabled (default)
     */
    public boolean isStructuralHeuristics() {
        return structuralHeuristics;
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
}
