package org.aspose.pdf.pgm;

/**
 * Kind-specific payload of an ANNOTATION or FIELD box (IR spec §2.2).
 */
public final class AnnotBoxData {

    private final String subtype;
    private final String fieldName;
    private final double[] quadPoints;

    /**
     * Creates annotation box data.
     *
     * @param subtype    the /Subtype name (e.g. "Highlight", "Widget")
     * @param fieldName  the fully-qualified field name (FIELD boxes), or null
     * @param quadPoints the /QuadPoints array for text-markup annotations
     *                   (moves together with /Rect), or null
     */
    public AnnotBoxData(String subtype, String fieldName, double[] quadPoints) {
        this.subtype = subtype;
        this.fieldName = fieldName;
        this.quadPoints = quadPoints;
    }

    /**
     * Returns the annotation /Subtype.
     *
     * @return the subtype, or null
     */
    public String getSubtype() {
        return subtype;
    }

    /**
     * Returns the fully-qualified form field name.
     *
     * @return the field name, or null
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Returns the /QuadPoints array.
     *
     * @return the quad points, or null
     */
    public double[] getQuadPoints() {
        return quadPoints;
    }
}
