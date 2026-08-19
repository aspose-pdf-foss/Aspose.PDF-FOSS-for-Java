package org.aspose.pdf;

/**
 * Base class for all document load options. API-compatible with Aspose.PDF
 * {@code com.aspose.pdf.LoadOptions}.
 *
 * <p>The concrete subtype selects the source format: passing an
 * {@link HtmlLoadOptions} to {@link Document#Document(String, LoadOptions)}
 * loads and converts HTML. This is the uniform load entry point — the format is
 * determined by the runtime type of the options object (reported by
 * {@link #getLoadFormat()}), mirroring how {@link SaveOptions} selects the
 * output format on save.</p>
 */
public abstract class LoadOptions {

    /**
     * Returns the source format this options object loads from.
     *
     * @return the load format
     */
    public abstract LoadFormat getLoadFormat();
}
