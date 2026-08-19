package org.aspose.pdf.sdm.html;

import java.nio.file.Path;

/**
 * Options for {@link SdmHtmlWriter} — the structural (semantic) SDM &rarr; HTML5 serializer.
 *
 * <p>Defaults: images are embedded as {@code data:} URIs, the generated markup uses one
 * small CSS class set collected into a single {@code <style>} element (no inline style
 * spam), and blocks are separated by newlines (whitespace-insignificant for the DOM).</p>
 */
public final class HtmlWriterOptions {

    /** How image resources referenced by Figure/InlineImage nodes are materialized. */
    public enum ImageMode {
        /** Embed image bytes as {@code data:<mime>;base64,...} URIs (self-contained HTML). */
        DATA_URI,
        /** Write image bytes as external files into {@link #getExternalImagesDir()}. */
        EXTERNAL
    }

    private ImageMode imageMode = ImageMode.DATA_URI;
    private Path externalImagesDir;
    private String externalImagesPrefix = "";

    /**
     * Returns how images are materialized (default {@link ImageMode#DATA_URI}).
     *
     * @return the image mode, never null
     */
    public ImageMode getImageMode() {
        return imageMode;
    }

    /**
     * Sets how images are materialized.
     *
     * @param imageMode the mode; null resets to {@link ImageMode#DATA_URI}
     * @return this options object (fluent)
     */
    public HtmlWriterOptions setImageMode(ImageMode imageMode) {
        this.imageMode = imageMode == null ? ImageMode.DATA_URI : imageMode;
        return this;
    }

    /**
     * Returns the directory external image files are written to
     * (used only in {@link ImageMode#EXTERNAL}).
     *
     * @return the directory, or null if not set
     */
    public Path getExternalImagesDir() {
        return externalImagesDir;
    }

    /**
     * Sets the directory external image files are written to.
     *
     * @param dir target directory (created on demand); required for {@link ImageMode#EXTERNAL}
     * @return this options object (fluent)
     */
    public HtmlWriterOptions setExternalImagesDir(Path dir) {
        this.externalImagesDir = dir;
        return this;
    }

    /**
     * Returns the prefix prepended to external image file names in {@code src} attributes
     * (e.g. {@code "images/"}). Empty by default.
     *
     * @return the prefix, never null
     */
    public String getExternalImagesPrefix() {
        return externalImagesPrefix;
    }

    /**
     * Sets the {@code src} prefix for external image files.
     *
     * @param prefix the prefix; null is treated as empty
     * @return this options object (fluent)
     */
    public HtmlWriterOptions setExternalImagesPrefix(String prefix) {
        this.externalImagesPrefix = prefix == null ? "" : prefix;
        return this;
    }
}
