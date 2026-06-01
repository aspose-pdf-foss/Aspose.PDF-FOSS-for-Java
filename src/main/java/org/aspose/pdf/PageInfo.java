package org.aspose.pdf;

import java.util.logging.Logger;

/**
 * Holds page layout information including dimensions and margins.
 * <p>
 * Used when constructing new pages to specify their size and margin settings.
 * </p>
 */
public class PageInfo {

    private static final Logger LOG = Logger.getLogger(PageInfo.class.getName());

    private double width;
    private double height;
    private MarginInfo margin;
    private boolean isLandscape;

    /**
     * Creates a PageInfo with default A4 dimensions (595 x 842 points) and zero margins.
     */
    public PageInfo() {
        this(595, 842);
    }

    /**
     * Creates a PageInfo with the specified dimensions and zero margins.
     *
     * @param width  the page width in points
     * @param height the page height in points
     */
    public PageInfo(double width, double height) {
        this.width = width;
        this.height = height;
        this.isLandscape = width > height;
        // Aspose's Page.PageInfo.Margin default is 90pt on every side
        // (~1.25 inch). Match that so visual templates produced against the
        // Aspose default line up with our rendered output when callers do
        // not override, and so absorber-based tests that assert absolute
        // X-positions (e.g. 90 + firstLineIndent) match.
        this.margin = new MarginInfo(90, 90, 90, 90);
        LOG.fine(() -> "PageInfo created: " + width + " x " + height);
    }

    /**
     * Returns the page width in points.
     *
     * @return the width
     */
    public double getWidth() {
        return width;
    }

    /**
     * Sets the page width in points.
     *
     * @param width the width
     */
    public void setWidth(double width) {
        this.width = width;
    }

    /**
     * Returns the page height in points.
     *
     * @return the height
     */
    public double getHeight() {
        return height;
    }

    /**
     * Returns the usable height (page height minus top and bottom margins).
     *
     * @return the pure content height in points
     */
    public double getPureHeight() {
        double h = height;
        if (margin != null) {
            h -= margin.getTop() + margin.getBottom();
        }
        return h;
    }

    /**
     * Sets the page height in points.
     *
     * @param height the height
     */
    public void setHeight(double height) {
        this.height = height;
    }

    /**
     * Returns the margin information.
     *
     * @return the margin info
     */
    public MarginInfo getMargin() {
        return margin;
    }

    /**
     * Sets the margin information.
     *
     * @param margin the margin info
     */
    public void setMargin(MarginInfo margin) {
        this.margin = margin;
    }

    /**
     * Sets the margin information from the canonical top-level
     * {@link org.aspose.pdf.MarginInfo} type. The values are translated into
     * the (now-deprecated) nested representation for internal storage.
     *
     * <p>Prefer this overload over {@link #setMargin(MarginInfo)} in new code —
     * the nested {@code PageInfo.MarginInfo} has a different constructor
     * argument order and is scheduled for removal.</p>
     *
     * @param canonical margin info expressed via {@link org.aspose.pdf.MarginInfo}
     */
    public void setMargin(org.aspose.pdf.MarginInfo canonical) {
        if (canonical == null) {
            this.margin = null;
            return;
        }
        this.margin = new MarginInfo(
                canonical.getTop(),
                canonical.getBottom(),
                canonical.getLeft(),
                canonical.getRight());
    }

    /**
     * Returns whether this page is in landscape orientation.
     * <p>
     * If explicitly set via {@link #setIsLandscape(boolean)}, returns that value.
     * Otherwise returns {@code true} if width is greater than height.
     * </p>
     *
     * @return {@code true} if landscape
     */
    public boolean isLandscape() {
        return isLandscape;
    }

    /**
     * Sets whether this page should be in landscape orientation.
     *
     * @param landscape {@code true} for landscape, {@code false} for portrait
     */
    public void setIsLandscape(boolean landscape) {
        this.isLandscape = landscape;
        if (landscape && width < height) {
            double tmp = width;
            width = height;
            height = tmp;
        } else if (!landscape && width > height) {
            double tmp = width;
            width = height;
            height = tmp;
        }
    }

    /**
     * Creates a deep copy of this PageInfo, including a copy of the margin.
     *
     * @return a new PageInfo with the same dimensions and margins
     */
    public PageInfo deepClone() {
        PageInfo clone = new PageInfo(this.width, this.height);
        clone.isLandscape = this.isLandscape;
        if (this.margin != null) {
            MarginInfo m = new MarginInfo();
            m.setTop(this.margin.getTop());
            m.setBottom(this.margin.getBottom());
            m.setLeft(this.margin.getLeft());
            m.setRight(this.margin.getRight());
            clone.margin = m;
        }
        return clone;
    }

    /**
     * Holds margin values for the four sides of a page.
     *
     * @deprecated Use the canonical top-level {@link org.aspose.pdf.MarginInfo}
     *     instead — it has a different (left, bottom, right, top) constructor
     *     order that matches Aspose .NET. This nested class will be removed in
     *     a future major version. Use the
     *     {@link PageInfo#setMargin(org.aspose.pdf.MarginInfo)} overload to
     *     migrate without per-call conversion.
     */
    @Deprecated
    public static class MarginInfo {

        private double top;
        private double bottom;
        private double left;
        private double right;

        /**
         * Creates a MarginInfo with all margins set to zero.
         */
        public MarginInfo() {
            this(0, 0, 0, 0);
        }

        /**
         * Creates a MarginInfo with the specified values.
         *
         * @param top    the top margin in points
         * @param bottom the bottom margin in points
         * @param left   the left margin in points
         * @param right  the right margin in points
         */
        public MarginInfo(double top, double bottom, double left, double right) {
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }

        /**
         * Returns the top margin.
         *
         * @return the top margin in points
         */
        public double getTop() {
            return top;
        }

        /**
         * Sets the top margin.
         *
         * @param top the top margin in points
         */
        public void setTop(double top) {
            this.top = top;
        }

        /**
         * Returns the bottom margin.
         *
         * @return the bottom margin in points
         */
        public double getBottom() {
            return bottom;
        }

        /**
         * Sets the bottom margin.
         *
         * @param bottom the bottom margin in points
         */
        public void setBottom(double bottom) {
            this.bottom = bottom;
        }

        /**
         * Returns the left margin.
         *
         * @return the left margin in points
         */
        public double getLeft() {
            return left;
        }

        /**
         * Sets the left margin.
         *
         * @param left the left margin in points
         */
        public void setLeft(double left) {
            this.left = left;
        }

        /**
         * Returns the right margin.
         *
         * @return the right margin in points
         */
        public double getRight() {
            return right;
        }

        /**
         * Sets the right margin.
         *
         * @param right the right margin in points
         */
        public void setRight(double right) {
            this.right = right;
        }

        @Override
        public String toString() {
            return "MarginInfo[top=" + top + ", bottom=" + bottom + ", left=" + left + ", right=" + right + "]";
        }
    }

    @Override
    public String toString() {
        return "PageInfo[" + width + " x " + height + ", " + margin + "]";
    }
}
