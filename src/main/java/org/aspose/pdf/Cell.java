package org.aspose.pdf;

import java.util.logging.Logger;

/**
 * Represents a single cell within a {@link Row} of a {@link Table}.
 * <p>
 * A cell contains a collection of {@link BaseParagraph} elements (via {@link Paragraphs})
 * and supports column/row spanning, individual border styling, background color,
 * padding (margin), alignment, and word wrapping.
 * </p>
 */
public class Cell {

    private static final Logger LOG = Logger.getLogger(Cell.class.getName());

    private Paragraphs paragraphs;
    private int colSpan = 1;
    private int rowSpan = 1;
    private BorderInfo border;
    private Color backgroundColor;
    private MarginInfo margin;
    private double width;
    private HorizontalAlignment alignment = HorizontalAlignment.None;
    private VerticalAlignment verticalAlignment = VerticalAlignment.None;
    private boolean isNoBorder;
    private boolean isWordWrapped = true;

    /**
     * Creates a new empty Cell with default settings.
     */
    public Cell() {
        // defaults applied by field initializers
    }

    /**
     * Returns the paragraphs collection for this cell, creating it lazily if needed.
     *
     * @return the paragraphs collection; never {@code null}
     */
    public Paragraphs getParagraphs() {
        if (paragraphs == null) {
            paragraphs = new Paragraphs();
        }
        return paragraphs;
    }

    /**
     * Sets the paragraphs collection for this cell.
     *
     * @param paragraphs the paragraphs collection
     */
    public void setParagraphs(Paragraphs paragraphs) {
        this.paragraphs = paragraphs;
    }

    /**
     * Convenience overload that wraps a form-field widget in a
     * {@link FormFieldParagraph} adapter and appends it to this cell's
     * paragraphs. Mirrors Aspose .NET's {@code cell.Paragraphs.Add(field)}
     * usage for fields that don't extend {@link BaseParagraph}.
     *
     * @param field the form field to embed (must not be null)
     */
    public void add(org.aspose.pdf.forms.Field field) {
        if (field == null) {
            throw new IllegalArgumentException("field must not be null");
        }
        getParagraphs().add(new FormFieldParagraph(field));
    }

    /**
     * Convenience overload that wraps a {@link org.aspose.pdf.forms.RadioButtonOptionField}
     * in a {@link FormFieldParagraph} adapter and appends it to this cell's
     * paragraphs. Mirrors Aspose .NET's {@code cell.Paragraphs.Add(option)}
     * usage that previously could not be ported because RBOF does not extend
     * {@link BaseParagraph}.
     *
     * @param option the radio button option to embed (must not be null)
     */
    public void add(org.aspose.pdf.forms.RadioButtonOptionField option) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        getParagraphs().add(new FormFieldParagraph(option));
    }

    /**
     * Appends a {@link BaseParagraph} (text, image, table, etc.) directly.
     * Equivalent to {@code getParagraphs().add(paragraph)} but provided so
     * callers don't have to switch between {@code add(...)} and
     * {@code getParagraphs().add(...)} depending on the argument type.
     *
     * @param paragraph the paragraph to append (must not be null)
     */
    public void add(BaseParagraph paragraph) {
        if (paragraph == null) {
            throw new IllegalArgumentException("paragraph must not be null");
        }
        getParagraphs().add(paragraph);
    }

    /**
     * Returns the number of columns this cell spans.
     *
     * @return the column span; defaults to 1
     */
    public int getColSpan() {
        return colSpan;
    }

    /**
     * Sets the number of columns this cell spans.
     *
     * @param colSpan the column span; must be at least 1
     * @throws IllegalArgumentException if colSpan is less than 1
     */
    public void setColSpan(int colSpan) {
        if (colSpan < 1) {
            throw new IllegalArgumentException("colSpan must be >= 1, got: " + colSpan);
        }
        this.colSpan = colSpan;
    }

    /**
     * Returns the number of rows this cell spans.
     *
     * @return the row span; defaults to 1
     */
    public int getRowSpan() {
        return rowSpan;
    }

    /**
     * Sets the number of rows this cell spans.
     *
     * @param rowSpan the row span; must be at least 1
     * @throws IllegalArgumentException if rowSpan is less than 1
     */
    public void setRowSpan(int rowSpan) {
        if (rowSpan < 1) {
            throw new IllegalArgumentException("rowSpan must be >= 1, got: " + rowSpan);
        }
        this.rowSpan = rowSpan;
    }

    /**
     * Returns the border styling for this cell.
     *
     * @return the border info, or {@code null} if not set
     */
    public BorderInfo getBorder() {
        return border;
    }

    /**
     * Sets the border styling for this cell.
     *
     * @param border the border info
     */
    public void setBorder(BorderInfo border) {
        this.border = border;
    }

    /**
     * Returns the background color of this cell.
     *
     * @return the background color, or {@code null} if not set
     */
    public Color getBackgroundColor() {
        return backgroundColor;
    }

    /**
     * Sets the background color of this cell.
     *
     * @param backgroundColor the background color
     */
    public void setBackgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    /**
     * Returns the cell padding (margin) information.
     *
     * @return the margin info representing cell padding, or {@code null}
     */
    public MarginInfo getMargin() {
        return margin;
    }

    /**
     * Sets the cell padding (margin) information.
     *
     * @param margin the margin info representing cell padding
     */
    public void setMargin(MarginInfo margin) {
        this.margin = margin;
    }

    /**
     * Returns the explicit width of this cell in points.
     *
     * @return the width; 0 means auto-calculated from column widths
     */
    public double getWidth() {
        return width;
    }

    /**
     * Sets the explicit width of this cell in points.
     *
     * @param width the width in points
     */
    public void setWidth(double width) {
        this.width = width;
    }

    /**
     * Returns the horizontal alignment for content within this cell.
     *
     * @return the horizontal alignment
     */
    public HorizontalAlignment getAlignment() {
        return alignment;
    }

    /**
     * Sets the horizontal alignment for content within this cell.
     *
     * @param alignment the horizontal alignment
     */
    public void setAlignment(HorizontalAlignment alignment) {
        this.alignment = alignment;
    }

    /**
     * Returns the vertical alignment for content within this cell.
     *
     * @return the vertical alignment
     */
    public VerticalAlignment getDefaultCellTextStateVerticalAlignment() {
        return verticalAlignment;
    }

    /**
     * Sets the vertical alignment for content within this cell.
     *
     * @param verticalAlignment the vertical alignment
     */
    public void setDefaultCellTextStateVerticalAlignment(VerticalAlignment verticalAlignment) {
        this.verticalAlignment = verticalAlignment;
    }

    /**
     * Returns whether this cell should have no border drawn.
     *
     * @return {@code true} if no border should be drawn
     */
    public boolean isNoBorder() {
        return isNoBorder;
    }

    /**
     * Sets whether this cell should have no border drawn.
     *
     * @param noBorder {@code true} to suppress border drawing
     */
    public void setNoBorder(boolean noBorder) {
        this.isNoBorder = noBorder;
    }

    /**
     * Returns whether text content in this cell should be word-wrapped.
     *
     * @return {@code true} if word wrapping is enabled; defaults to {@code true}
     */
    public boolean isWordWrapped() {
        return isWordWrapped;
    }

    /**
     * Sets whether text content in this cell should be word-wrapped.
     *
     * @param wordWrapped {@code true} to enable word wrapping
     */
    public void setWordWrapped(boolean wordWrapped) {
        this.isWordWrapped = wordWrapped;
    }

    /**
     * Creates a deep copy of this cell, including its paragraphs, border, margin,
     * background color, alignment, and span settings.
     *
     * @return a new {@link Cell} with the same properties
     */
    @Override
    public Cell clone() {
        Cell copy = new Cell();
        // Deep-copy paragraphs
        if (this.paragraphs != null) {
            Paragraphs copyParagraphs = new Paragraphs();
            for (BaseParagraph p : this.paragraphs) {
                copyParagraphs.add(p); // paragraphs are shared references
            }
            copy.paragraphs = copyParagraphs;
        }
        copy.colSpan = this.colSpan;
        copy.rowSpan = this.rowSpan;
        copy.border = this.border;
        copy.backgroundColor = this.backgroundColor;
        copy.margin = this.margin;
        copy.width = this.width;
        copy.alignment = this.alignment;
        copy.verticalAlignment = this.verticalAlignment;
        copy.isNoBorder = this.isNoBorder;
        copy.isWordWrapped = this.isWordWrapped;
        return copy;
    }
}
