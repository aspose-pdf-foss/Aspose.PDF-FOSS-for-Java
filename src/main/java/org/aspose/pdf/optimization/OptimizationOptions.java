package org.aspose.pdf.optimization;

/**
 * Options controlling {@link org.aspose.pdf.Document#optimizeResources(OptimizationOptions)}.
 *
 * <p>API-compatible with Aspose.PDF's {@code Aspose.Pdf.Optimization.OptimizationOptions}
 * (also surfaced in the original API as the nested {@code Document.OptimizationOptions}).
 * Each flag toggles one optimisation pass. The structural passes
 * ({@link #setRemoveUnusedObjects}, {@link #setRemoveUnusedStreams},
 * {@link #setLinkDuplicateStreams}, {@link #setAllowReusePageContent}) are
 * implemented; the image- and font-rewriting passes are accepted for API
 * compatibility and applied on a best-effort basis.</p>
 */
public class OptimizationOptions {

    private boolean removeUnusedObjects = false;
    private boolean removeUnusedStreams = false;
    private boolean linkDuplicateStreams = false;
    private boolean allowReusePageContent = false;
    private boolean compressImages = false;
    private int imageQuality = 100;
    private boolean unembedFonts = false;
    private boolean subsetFonts = false;
    private int imageCompressionVersion = 0;
    private boolean resizeImages = false;
    private boolean removePrivateInfo = false;
    private int maxResolution = 0;

    /** Creates options with every pass disabled. */
    public OptimizationOptions() {
    }

    /**
     * Returns an options instance with the standard size-reduction passes
     * enabled (remove unused objects and streams, link duplicate streams,
     * subset fonts and compress images), mirroring Aspose's
     * {@code OptimizationOptions.All()}.
     *
     * @return a fully-enabled options instance
     */
    public static OptimizationOptions all() {
        OptimizationOptions o = new OptimizationOptions();
        o.removeUnusedObjects = true;
        o.removeUnusedStreams = true;
        o.linkDuplicateStreams = true;
        o.allowReusePageContent = true;
        o.subsetFonts = true;
        o.compressImages = true;
        return o;
    }

    /** @return whether unreferenced indirect objects are dropped on save */
    public boolean isRemoveUnusedObjects() {
        return removeUnusedObjects;
    }

    /**
     * @param value enable dropping unreferenced indirect objects
     */
    public void setRemoveUnusedObjects(boolean value) {
        this.removeUnusedObjects = value;
    }

    /** @return whether unreferenced streams are dropped on save */
    public boolean isRemoveUnusedStreams() {
        return removeUnusedStreams;
    }

    /**
     * @param value enable dropping unreferenced streams
     */
    public void setRemoveUnusedStreams(boolean value) {
        this.removeUnusedStreams = value;
    }

    /** @return whether byte-identical streams are merged into a single object */
    public boolean isLinkDuplicateStreams() {
        return linkDuplicateStreams;
    }

    /**
     * @param value enable merging byte-identical streams
     */
    public void setLinkDuplicateStreams(boolean value) {
        this.linkDuplicateStreams = value;
    }

    /** @return whether identical page-content streams may be shared between pages */
    public boolean isAllowReusePageContent() {
        return allowReusePageContent;
    }

    /**
     * @param value enable sharing identical page-content streams
     */
    public void setAllowReusePageContent(boolean value) {
        this.allowReusePageContent = value;
    }

    /** @return whether embedded images are recompressed */
    public boolean isCompressImages() {
        return compressImages;
    }

    /**
     * @param value enable recompressing embedded images
     */
    public void setCompressImages(boolean value) {
        this.compressImages = value;
    }

    /** @return the JPEG quality (1-100) used when {@link #isCompressImages()} is set */
    public int getImageQuality() {
        return imageQuality;
    }

    /**
     * @param value the JPEG quality, clamped to 1-100
     */
    public void setImageQuality(int value) {
        this.imageQuality = Math.max(1, Math.min(100, value));
    }

    /** @return whether embedded fonts are unembedded */
    public boolean isUnembedFonts() {
        return unembedFonts;
    }

    /**
     * @param value enable unembedding fonts
     */
    public void setUnembedFonts(boolean value) {
        this.unembedFonts = value;
    }

    /** @return whether embedded fonts are subset to the glyphs actually used */
    public boolean isSubsetFonts() {
        return subsetFonts;
    }

    /**
     * @param value enable font subsetting
     */
    public void setSubsetFonts(boolean value) {
        this.subsetFonts = value;
    }

    /** @return the image-compression algorithm version selector */
    public int getImageCompressionVersion() {
        return imageCompressionVersion;
    }

    /**
     * @param value the image-compression algorithm version selector
     */
    public void setImageCompressionVersion(int value) {
        this.imageCompressionVersion = value;
    }

    /** @return whether oversized images are downscaled */
    public boolean isResizeImages() {
        return resizeImages;
    }

    /**
     * @param value enable downscaling oversized images
     */
    public void setResizeImages(boolean value) {
        this.resizeImages = value;
    }

    /** @return whether private/metadata information is stripped */
    public boolean isRemovePrivateInfo() {
        return removePrivateInfo;
    }

    /**
     * @param value enable stripping private/metadata information
     */
    public void setRemovePrivateInfo(boolean value) {
        this.removePrivateInfo = value;
    }

    /** @return the maximum image resolution (DPI) used when {@link #isResizeImages()} is set */
    public int getMaxResolution() {
        return maxResolution;
    }

    /**
     * @param value the maximum image resolution in DPI
     */
    public void setMaxResolution(int value) {
        this.maxResolution = value;
    }
}
