package org.aspose.pdf;

/**
 * Options for saving a document as Markdown ({@code .md}). API-shaped after
 * Aspose.PDF {@code MarkdownSaveOptions}.
 *
 * <p>The FOSS converter reuses the structural IR (SDM) pipeline: the PDF is
 * projected to the Semantic Document Model (with tagged-structure or geometry
 * heuristics) and that model is serialized to CommonMark / GitHub-Flavored
 * Markdown. Headings become {@code #}&hellip;{@code ######}, paragraphs plain
 * text, recognised tables GFM pipe tables, lists {@code -}/{@code 1.} items,
 * bold/italic runs {@code **}/{@code *}, links {@code [text](href)} and images
 * {@code ![alt](src)}. The flags here steer the shared enrichment the same way
 * {@link HtmlSaveOptions} and {@link DocSaveOptions} do.</p>
 *
 * <p><b>Images.</b> When the document is saved to a file, referenced images are
 * written as external files into a sub-directory next to the {@code .md}
 * (default name {@code "resources"}, see {@link #setResourcesDirectoryName}) and
 * referenced by relative path. When saved to a stream (no folder is available)
 * images are embedded as {@code data:} URIs so nothing is lost.</p>
 */
public class MarkdownSaveOptions extends SaveOptions {

    private String resourcesDirectoryName = "resources";
    private boolean structuralHeuristics = true;
    private boolean suppressRunningHeadersFooters = true;
    private boolean rasterizeVectorGraphics;
    private boolean embedImagesAsDataUri;

    /** Creates options with default settings. */
    public MarkdownSaveOptions() {
    }

    /**
     * {@inheritDoc}
     *
     * @return always {@link SaveFormat#Markdown}
     */
    @Override
    public SaveFormat getSaveFormat() {
        return SaveFormat.Markdown;
    }

    /**
     * Gets the name of the sub-directory that receives extracted images when the
     * document is saved to a file.
     *
     * @return the resources directory name (default {@code "resources"})
     */
    public String getResourcesDirectoryName() {
        return resourcesDirectoryName;
    }

    /**
     * Sets the name of the sub-directory that receives extracted images when the
     * document is saved to a file (Aspose {@code MarkdownSaveOptions
     * .ResourcesDirectoryName}). Created next to the output {@code .md} file.
     *
     * @param name the sub-directory name; {@code null}/empty restores the default
     */
    public void setResourcesDirectoryName(String name) {
        this.resourcesDirectoryName = (name == null || name.isEmpty()) ? "resources" : name;
    }

    /**
     * Gets whether geometry heuristics (heading / list / table recognition) may
     * run on untagged content.
     *
     * @return {@code true} if heuristics are enabled (default)
     */
    public boolean isStructuralHeuristics() {
        return structuralHeuristics;
    }

    /**
     * Sets whether geometry heuristics may run on untagged content. When
     * disabled, untagged documents produce shallow Markdown (paragraphs only).
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
     * Sets whether repeated per-page running headers/footers are dropped. A
     * page-less Markdown stream has no place for per-page furniture, so it is
     * removed by default.
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
     * <p>Markdown is a text-first format, so vector rasterization is
     * <b>off</b> by default (a chart is dropped with an HTML-comment placeholder
     * rather than turned into a raster). Enable it to keep charts/diagrams as
     * images at the cost of a larger, less editable document.</p>
     *
     * @return {@code true} if vector graphics are rasterized (default {@code false})
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
     * Gets whether images are embedded as {@code data:} URIs instead of written
     * as external files.
     *
     * @return {@code true} to embed images inline
     */
    public boolean isEmbedImagesAsDataUri() {
        return embedImagesAsDataUri;
    }

    /**
     * Sets whether images are embedded as {@code data:} URIs even when saving to
     * a file. When {@code false} (default) file targets write external images
     * into {@link #getResourcesDirectoryName()}; stream targets always embed.
     *
     * @param value {@code true} to embed images inline
     */
    public void setEmbedImagesAsDataUri(boolean value) {
        this.embedImagesAsDataUri = value;
    }
}
