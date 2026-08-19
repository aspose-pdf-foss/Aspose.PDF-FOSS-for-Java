package org.aspose.pdf;

/**
 * Options for {@link Document#compactFlow(CompactionOptions)} — the flow
 * compaction operation (IR Stage 2): closing vertical holes left by deletion
 * or merge, like an HTML page reflows.
 * <p>
 * Defaults come from the Stage-1 corpus calibration, not guesses: the gap
 * threshold is 30pt (corpus line-gap p50=5.5 / p90=16.9; ≥30pt is the 5%
 * anomalous tail).
 * </p>
 */
public final class CompactionOptions {

    private int[] pages;
    private double verticalGapThreshold = 30.0;
    private boolean keepPageBreaks = false;
    private boolean preserveChrome = true;
    private boolean mergeColumns = false;
    private boolean splitParagraphs = false;

    /**
     * Returns the 1-based page numbers to compact, or null for the whole
     * document.
     *
     * @return the scope, or null
     */
    public int[] getPages() {
        return pages;
    }

    /**
     * Limits compaction to the given 1-based page numbers; pages outside the
     * scope are byte-untouched.
     *
     * @param pages the page numbers, or null for all
     * @return this
     */
    public CompactionOptions setPages(int... pages) {
        this.pages = pages;
        return this;
    }

    /**
     * Returns the vertical gap threshold in points: gaps larger than this are
     * holes to close; smaller gaps are natural spacing and stay.
     *
     * @return the threshold (default 30)
     */
    public double getVerticalGapThreshold() {
        return verticalGapThreshold;
    }

    /**
     * Sets the vertical gap threshold in points.
     *
     * @param threshold the threshold (must be positive)
     * @return this
     */
    public CompactionOptions setVerticalGapThreshold(double threshold) {
        if (threshold <= 0) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.verticalGapThreshold = threshold;
        return this;
    }

    /**
     * Returns whether page breaks are kept. Default FALSE — content flows
     * across pages like HTML and emptied pages are removed; set true for
     * in-page compaction only.
     *
     * @return true to keep page breaks
     */
    public boolean isKeepPageBreaks() {
        return keepPageBreaks;
    }

    /**
     * Sets whether page breaks are kept (true = in-page compaction only).
     *
     * @param keep true to keep page breaks
     * @return this
     */
    public CompactionOptions setKeepPageBreaks(boolean keep) {
        this.keepPageBreaks = keep;
        return this;
    }

    /**
     * Returns whether chrome (headers/footers/page numbers/watermarks) is
     * preserved in place (default true).
     *
     * @return true when chrome stays
     */
    public boolean isPreserveChrome() {
        return preserveChrome;
    }

    /**
     * Sets whether chrome is preserved in place.
     *
     * @param preserve true to keep chrome fixed
     * @return this
     */
    public CompactionOptions setPreserveChrome(boolean preserve) {
        this.preserveChrome = preserve;
        return this;
    }

    /**
     * Returns whether multi-column CLEAN pages are merged N→1 before
     * compaction (default false: multi-column pages are skipped).
     *
     * @return true to merge columns
     */
    public boolean isMergeColumns() {
        return mergeColumns;
    }

    /**
     * Sets whether multi-column CLEAN pages are merged N→1.
     *
     * @param merge true to merge
     * @return this
     */
    public CompactionOptions setMergeColumns(boolean merge) {
        this.mergeColumns = merge;
        return this;
    }

    /**
     * Returns whether cross-page flow may SPLIT a paragraph block across the
     * page boundary (default false).
     * <p>
     * With this off, a leading block that does not fit the target's free space
     * stays put as a whole (paragraphs never break). With it on, when the whole
     * block does not fit, cross-page flow pulls the maximal <i>prefix of leading
     * LINES</i> that does fit — filling a near-empty boundary page instead of
     * leaving it blank. Lines are the atomic unit: a line is never split
     * horizontally. This is a denser but more aggressive reflow (it can separate
     * a paragraph's opening lines from its remainder), so it is opt-in.
     * </p>
     *
     * @return true when paragraph blocks may be split line-by-line across pages
     */
    public boolean isSplitParagraphs() {
        return splitParagraphs;
    }

    /**
     * Sets whether cross-page flow may split a paragraph block across the page
     * boundary line-by-line when the whole block does not fit.
     *
     * @param split true to allow line-level paragraph splitting across pages
     * @return this
     */
    public CompactionOptions setSplitParagraphs(boolean split) {
        this.splitParagraphs = split;
        return this;
    }
}
