package org.aspose.pdf.annotations;

import org.aspose.pdf.forms.RadioButtonOptionField;

/**
 * Represents the border of an annotation or form field (ISO 32000-1:2008, §12.5.4).
 */
public class Border {
    private Annotation parent;
    private RadioButtonOptionField optionParent;
    private double width = 1;
    private BorderStyle style = BorderStyle.Solid;
    private int[] dash;

    /**
     * Constructs a border for the specified annotation.
     *
     * @param parent the parent annotation (may be null)
     */
    public Border(Annotation parent) {
        this.parent = parent;
    }

    /**
     * Constructs a border bound to a {@link RadioButtonOptionField}.
     *
     * <p>Convenience overload — {@link RadioButtonOptionField} does not extend
     * {@link Annotation} in OpenPDF FOSS, so the existing
     * {@link #Border(Annotation)} ctor cannot accept it. This overload lets
     * port code that mirrors Aspose .NET's {@code new Border(option)} pattern
     * compile without an explicit {@code (Annotation) null} cast.</p>
     *
     * <p>Mutations to this border are synced back to the option via
     * {@link RadioButtonOptionField#setBorder(Border)} (matches the
     * {@link #Border(Annotation)} sync behaviour).</p>
     *
     * @param option the radio-button option this border belongs to
     *               (must not be null)
     */
    public Border(RadioButtonOptionField option) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        this.optionParent = option;
    }

    /**
     * Returns the border width in points.
     *
     * @return the border width
     */
    public double getWidth() { return width; }

    /**
     * Sets the border width in points.
     *
     * @param width the border width
     */
    public void setWidth(double width) {
        this.width = width;
        syncParent();
    }

    /**
     * Returns the border style.
     *
     * @return the border style
     */
    public BorderStyle getStyle() { return style; }

    /**
     * Sets the border style.
     *
     * @param style the border style
     */
    public void setStyle(BorderStyle style) {
        this.style = style;
        syncParent();
    }

    /**
     * Returns the dash pattern array for dashed borders.
     *
     * @return the dash pattern, or null if not set
     */
    public int[] getDash() { return dash; }

    /**
     * Sets the dash pattern array for dashed borders.
     *
     * @param dash the dash pattern array
     */
    public void setDash(int[] dash) {
        this.dash = dash;
        syncParent();
    }

    private void syncParent() {
        if (parent != null) {
            parent.setBorder(this);
        }
        if (optionParent != null) {
            optionParent.setBorder(this);
        }
    }
}
