package org.aspose.pdf.sdm.layout;

/**
 * Page geometry for {@link SdmPdfLayout} (IR Stage 4, PART 2): media size and
 * margins, in PDF points. Defaults to US Letter (612&times;792) with 72pt margins.
 */
public final class PageSetup {

    private double pageWidth = 612.0;
    private double pageHeight = 792.0;
    private double marginTop = 72.0;
    private double marginBottom = 72.0;
    private double marginLeft = 72.0;
    private double marginRight = 72.0;

    /** Furniture-text token replaced with the CURRENT page number at draw time
     *  (a DOCX {@code PAGE} field). */
    public static final String PAGE_TOKEN = "${PAGE}";
    /** Furniture-text token replaced with the TOTAL page count at draw time
     *  (a DOCX {@code NUMPAGES} field; triggers a second layout pass). */
    public static final String NUMPAGES_TOKEN = "${NUMPAGES}";

    /**
     * One line of page furniture (a running header/footer) painted in the
     * margin band of EVERY page — e.g. a DOCX {@code header1.xml} paragraph.
     */
    public static final class FurnitureLine {
        private final String text;
        private final double size;
        private final boolean bold;
        private final boolean centered;
        /** Explicit tab stops ({@code {positionPt, align}}, align 0=left 1=center
         *  2=right), from the paragraph's {@code w:tabs}; empty = edge/centre/edge. */
        private final java.util.List<double[]> tabStops = new java.util.ArrayList<>();

        /**
         * Creates a furniture line.
         *
         * @param text     the line text
         * @param size     font size in points (0 = default)
         * @param bold     bold flag
         * @param centered centred within the content width (else left-aligned)
         */
        public FurnitureLine(String text, double size, boolean bold, boolean centered) {
            this.text = text == null ? "" : text;
            this.size = size;
            this.bold = bold;
            this.centered = centered;
        }

        /** @return explicit tab stops ({@code {positionPt, align}}; mutable) */
        public java.util.List<double[]> getTabStops() { return tabStops; }

        /** @return the line text */
        public String getText() { return text; }

        /** @return font size in points (0 = default) */
        public double getSize() { return size; }

        /** @return bold flag */
        public boolean isBold() { return bold; }

        /** @return centred flag */
        public boolean isCentered() { return centered; }
    }

    /** When set, the page size is an EXPLICIT source fact (DOC/DOCX section,
     *  PageInfo, HTML meta) — the layout must not auto-widen it for wide tables. */
    private boolean fixedSize;

    /** @return whether the page size is explicit and must not be auto-widened */
    public boolean isFixedSize() { return fixedSize; }

    /** Marks the page size as explicit (disables table-driven auto-widening).
     *  @param v the flag */
    public void setFixedSize(boolean v) { this.fixedSize = v; }

    private final java.util.List<FurnitureLine> headerLines = new java.util.ArrayList<>();
    private final java.util.List<FurnitureLine> footerLines = new java.util.ArrayList<>();
    /** Distance from the page top edge to the header band (DOCX pgMar w:header). */
    private double headerDistance = 36.0;
    /** Distance from the page bottom edge to the footer band (DOCX pgMar w:footer). */
    private double footerDistance = 36.0;

    /** Creates a default (Letter) setup. */
    public PageSetup() {
    }

    /** Creates a deep copy of {@code other} (used to widen a page without mutating
     *  the caller's shared setup). */
    public PageSetup(PageSetup other) {
        this.pageWidth = other.pageWidth;
        this.pageHeight = other.pageHeight;
        this.marginTop = other.marginTop;
        this.marginBottom = other.marginBottom;
        this.marginLeft = other.marginLeft;
        this.marginRight = other.marginRight;
        this.headerLines.addAll(other.headerLines);
        this.footerLines.addAll(other.footerLines);
        this.headerDistance = other.headerDistance;
        this.fixedSize = other.fixedSize;
        this.footerDistance = other.footerDistance;
    }

    /** @return the running-header lines painted on every page (mutable) */
    public java.util.List<FurnitureLine> getHeaderLines() { return headerLines; }

    /** @return the running-footer lines painted on every page (mutable) */
    public java.util.List<FurnitureLine> getFooterLines() { return footerLines; }

    /** @return distance from the page top edge to the header band (points) */
    public double getHeaderDistance() { return headerDistance; }

    /** Sets the header band distance from the page top edge.
     *  @param v distance in points */
    public void setHeaderDistance(double v) { this.headerDistance = v; }

    /** @return distance from the page bottom edge to the footer band (points) */
    public double getFooterDistance() { return footerDistance; }

    /** Sets the footer band distance from the page bottom edge.
     *  @param v distance in points */
    public void setFooterDistance(double v) { this.footerDistance = v; }

    /** @return a Letter page setup with 72pt margins. */
    public static PageSetup letter() {
        return new PageSetup();
    }

    /** @return an A4 (595&times;842) page setup with 72pt margins. */
    public static PageSetup a4() {
        PageSetup s = new PageSetup();
        s.pageWidth = 595.0;
        s.pageHeight = 842.0;
        return s;
    }

    public double getPageWidth() { return pageWidth; }

    public void setPageWidth(double pageWidth) { this.pageWidth = pageWidth; }

    public double getPageHeight() { return pageHeight; }

    public void setPageHeight(double pageHeight) { this.pageHeight = pageHeight; }

    public double getMarginTop() { return marginTop; }

    public void setMarginTop(double marginTop) { this.marginTop = marginTop; }

    public double getMarginBottom() { return marginBottom; }

    public void setMarginBottom(double marginBottom) { this.marginBottom = marginBottom; }

    public double getMarginLeft() { return marginLeft; }

    public void setMarginLeft(double marginLeft) { this.marginLeft = marginLeft; }

    public double getMarginRight() { return marginRight; }

    public void setMarginRight(double marginRight) { this.marginRight = marginRight; }

    /** @return the usable content width (page width minus left+right margins). */
    public double getContentWidth() {
        return pageWidth - marginLeft - marginRight;
    }

    /** @return the usable content height (page height minus top+bottom margins). */
    public double getContentHeight() {
        return pageHeight - marginTop - marginBottom;
    }

    /** @return the y coordinate (PDF, bottom-left origin) of the content-area top. */
    public double getContentTop() {
        return pageHeight - marginTop;
    }

    /** @return the y coordinate of the content-area bottom. */
    public double getContentBottom() {
        return marginBottom;
    }
}
