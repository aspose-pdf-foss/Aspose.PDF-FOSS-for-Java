package org.aspose.pdf;

/**
 * Base class for all document save options. API-compatible with Aspose.PDF
 * {@code com.aspose.pdf.SaveOptions}.
 *
 * <p>The concrete subtype selects the output format: passing a
 * {@link PdfSaveOptions} to {@link Document#save(String, SaveOptions)} writes a
 * PDF, an {@link HtmlSaveOptions} writes HTML, and so on. This is the single,
 * uniform save entry point — the desired format is determined by the runtime
 * type of the options object (reported by {@link #getSaveFormat()}) rather than
 * by a separate format argument or a per-format method.</p>
 */
public abstract class SaveOptions {

    /**
     * Returns the output format this options object produces.
     *
     * @return the save format
     */
    public abstract SaveFormat getSaveFormat();
}
