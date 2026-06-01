package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * Caret annotation (ISO 32000-1:2008, Section 12.5.6.11, /Subtype /Caret).
 * <p>
 * A caret annotation indicates a proposed insertion point in the document text.
 * It has no special appearance; the caret symbol is typically shown as a blinking
 * cursor at the annotation location.
 * </p>
 */
public class CaretAnnotation extends MarkupAnnotation {

    /**
     * Constructs a caret annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public CaretAnnotation(COSDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new caret annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle (must have positive area per
     *             ISO 32000-1:2008 §12.5.2)
     */
    public CaretAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        // A caret needs a positive-area box to host its glyph; callers with a
        // single coordinate should use atPoint(Page, Point) instead.
        requirePositiveArea(rect);
        dict.set(COSName.of("Subtype"), COSName.of("Caret"));
    }

    /**
     * Creates a caret annotation centred on the given point with a default
     * 12&times;12 user-space bounding box. The bounding box is large enough
     * to host the caret glyph and satisfies the positive-area requirement
     * of {@link Annotation#setRect(Rectangle)} per ISO 32000-1:2008 §12.5.2.
     *
     * @param page the page this annotation belongs to
     * @param point the point at which the caret should appear
     * @return a new {@link CaretAnnotation} with {@code /Rect} =
     *         {@code [x-6, y-6, x+6, y+6]}
     * @throws IllegalArgumentException if {@code point} is null
     */
    public static CaretAnnotation atPoint(Page page, Point point) {
        if (point == null) {
            throw new IllegalArgumentException("point must not be null");
        }
        double x = point.getX();
        double y = point.getY();
        return new CaretAnnotation(page, new Rectangle(x - 6, y - 6, x + 6, y + 6));
    }
}
