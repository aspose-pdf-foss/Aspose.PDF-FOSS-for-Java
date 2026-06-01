package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * Square annotation (ISO 32000-1:2008, Section 12.5.6.8, /Subtype /Square).
 * <p>
 * A square annotation displays a rectangle on the page. Despite its name,
 * the annotation need not be square; the rectangle is defined by the
 * annotation's bounding rectangle.
 * </p>
 */
public class SquareAnnotation extends MarkupAnnotation {

    /**
     * Constructs a square annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public SquareAnnotation(COSDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new square annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    public SquareAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        // A square is meaningless without a positive-area bounding box.
        requirePositiveArea(rect);
        dict.set(COSName.of("Subtype"), COSName.of("Square"));
    }
}
