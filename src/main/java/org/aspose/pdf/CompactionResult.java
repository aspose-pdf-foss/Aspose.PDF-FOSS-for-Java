package org.aspose.pdf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link Document#compactFlow(CompactionOptions)}: what was moved,
 * closed, removed and skipped — skipped pages always carry their reason
 * (honest reporting is part of the operation's contract).
 */
public final class CompactionResult {

    private int pagesBefore;
    private int pagesAfter;
    private int gapsClosed;
    private int blocksMoved;
    private final Map<Integer, String> pagesSkipped = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * Returns the page count before compaction.
     *
     * @return pages before
     */
    public int getPagesBefore() {
        return pagesBefore;
    }

    /**
     * Sets the page count before compaction.
     *
     * @param pagesBefore pages before
     */
    public void setPagesBefore(int pagesBefore) {
        this.pagesBefore = pagesBefore;
    }

    /**
     * Returns the page count after compaction (smaller when cross-page flow
     * emptied pages).
     *
     * @return pages after
     */
    public int getPagesAfter() {
        return pagesAfter;
    }

    /**
     * Sets the page count after compaction.
     *
     * @param pagesAfter pages after
     */
    public void setPagesAfter(int pagesAfter) {
        this.pagesAfter = pagesAfter;
    }

    /**
     * Returns the number of oversized vertical gaps closed.
     *
     * @return gaps closed
     */
    public int getGapsClosed() {
        return gapsClosed;
    }

    /**
     * Adds to the closed-gap counter.
     *
     * @param n how many gaps
     */
    public void addGapsClosed(int n) {
        this.gapsClosed += n;
    }

    /**
     * Returns the number of blocks moved (shifted in page or pulled across
     * pages).
     *
     * @return blocks moved
     */
    public int getBlocksMoved() {
        return blocksMoved;
    }

    /**
     * Adds to the moved-block counter.
     *
     * @param n how many blocks
     */
    public void addBlocksMoved(int n) {
        this.blocksMoved += n;
    }

    /**
     * Returns skipped pages: 0-based page index → reason
     * (e.g. "multi-column", "relative-td-chain").
     *
     * @return the skip map
     */
    public Map<Integer, String> getPagesSkipped() {
        return pagesSkipped;
    }

    /**
     * Returns free-form warnings collected during the operation.
     *
     * @return the warnings
     */
    public List<String> getWarnings() {
        return warnings;
    }

    @Override
    public String toString() {
        return "CompactionResult{pages " + pagesBefore + "->" + pagesAfter
                + ", gapsClosed=" + gapsClosed + ", blocksMoved=" + blocksMoved
                + ", skipped=" + pagesSkipped + "}";
    }
}
