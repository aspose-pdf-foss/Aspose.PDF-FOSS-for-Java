package org.aspose.pdf.text;

import org.aspose.pdf.Rectangle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A table detected on a PDF page during absorption.
 * <p>
 * Contains the rows of the table and the overall bounding rectangle.
 * </p>
 */
public class AbsorbedTable {

    private final List<AbsorbedRow> rows = new ArrayList<>();
    private Rectangle rectangle;
    private int borderColorRgb = -1;

    /**
     * Returns the packed 0xRRGGBB colour of this table's ruling lines, or -1 when
     * the table was not detected from rulings (or the colour is unknown).
     *
     * @return the rule colour, or -1
     */
    public int getBorderColorRgb() {
        return borderColorRgb;
    }

    /**
     * Records the ruling-line colour (packed 0xRRGGBB) of this ruled table.
     *
     * @param rgb the colour, or -1 for unknown
     */
    public void setBorderColorRgb(int rgb) {
        this.borderColorRgb = rgb;
    }

    /**
     * Returns the list of rows in this table.
     *
     * @return unmodifiable list of rows
     */
    public List<AbsorbedRow> getRowList() {
        return Collections.unmodifiableList(rows);
    }

    /**
     * Adds a row to this table.
     *
     * @param row the row to add
     */
    public void addRow(AbsorbedRow row) {
        if (row != null) {
            rows.add(row);
        }
    }

    /**
     * Returns the bounding rectangle of this table.
     *
     * @return the rectangle, or null if not set
     */
    public Rectangle getRectangle() {
        return rectangle;
    }

    /**
     * Sets the bounding rectangle of this table.
     *
     * @param rectangle the bounding rectangle
     */
    public void setRectangle(Rectangle rectangle) {
        this.rectangle = rectangle;
    }
}
