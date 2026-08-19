package org.aspose.pdf.sdm;

/**
 * PDF locator for objects living INSIDE a page content stream: a half-open
 * range of operator indices {@code [opStart, opEnd]} (inclusive) within the
 * page's logically concatenated content (the operator list that
 * {@code Page.getContents()} yields — /Contents arrays are already spliced).
 * <ul>
 *   <li>text — the show-op range (often within BT…ET, but not required to
 *       align with BT/ET boundaries: one BT block may hold many nodes);</li>
 *   <li>vector — path construction ops (m, l, c, re, h, ...) up to and
 *       including the paint op (f, f-star, S, s, B, B-star, b, b-star, n);</li>
 *   <li>raster — the Do operator, or the BI…EI inline-image range.</li>
 * </ul>
 * Editing a ContentRange object means regenerating the page's content stream;
 * after regeneration all ContentRanges of that page must be re-projected.
 */
public final class ContentRange extends SourceRef {

    private final int pageObjNum;
    private final int opStart;
    private final int opEnd;

    /**
     * Creates a content-stream range locator.
     *
     * @param pageObjNum the object number of the page dictionary owning the stream
     * @param opStart    first operator index of the range (0-based, inclusive)
     * @param opEnd      last operator index of the range (0-based, inclusive)
     */
    public ContentRange(int pageObjNum, int opStart, int opEnd) {
        super("pdf");
        if (opStart < 0 || opEnd < opStart) {
            throw new IllegalArgumentException(
                    "invalid operator range: " + opStart + ".." + opEnd);
        }
        this.pageObjNum = pageObjNum;
        this.opStart = opStart;
        this.opEnd = opEnd;
    }

    /**
     * Returns the object number of the owning page dictionary.
     *
     * @return the page object number
     */
    public int getPageObjNum() {
        return pageObjNum;
    }

    /**
     * Returns the first operator index (inclusive).
     *
     * @return the range start
     */
    public int getOpStart() {
        return opStart;
    }

    /**
     * Returns the last operator index (inclusive).
     *
     * @return the range end
     */
    public int getOpEnd() {
        return opEnd;
    }

    @Override
    public String canonical() {
        return "pdf:content:" + pageObjNum + ":" + opStart + "-" + opEnd;
    }
}
