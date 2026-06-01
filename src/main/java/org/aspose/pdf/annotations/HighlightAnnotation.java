package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * Highlight annotation (ISO 32000-1:2008, Section 12.5.6.10, /Subtype /Highlight).
 * <p>
 * A highlight annotation appears as a highlight behind the text. The region
 * to be highlighted is specified by the /QuadPoints array, which defines
 * quadrilaterals covering the text.
 * </p>
 */
public class HighlightAnnotation extends MarkupAnnotation {

    /**
     * Constructs a highlight annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public HighlightAnnotation(COSDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new highlight annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    public HighlightAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        dict.set(COSName.of("Subtype"), COSName.of("Highlight"));
        // Auto-derive QuadPoints from rect (matches C# Aspose behavior).
        // Order: top-left, top-right, bottom-left, bottom-right
        // (counter-clockwise from top-left, per ISO 32000-1:2008 §12.5.6.10).
        setQuadPoints(new double[] {
                rect.getLLX(), rect.getURY(),
                rect.getURX(), rect.getURY(),
                rect.getLLX(), rect.getLLY(),
                rect.getURX(), rect.getLLY()
        });
    }

    /**
     * Returns the quadrilateral points defining the highlighted regions.
     * <p>
     * Each set of 8 values defines a quadrilateral: four (x, y) pairs
     * specifying the corners in counterclockwise order.
     * </p>
     *
     * @return the quad points array, or null if not set
     */
    public double[] getQuadPoints() {
        COSBase qp = dict.get("QuadPoints");
        if (qp instanceof COSArray) {
            COSArray arr = (COSArray) qp;
            double[] result = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.getFloat(i, 0);
            }
            return result;
        }
        return null;
    }

    /**
     * Sets the quadrilateral points defining the highlighted regions.
     *
     * @param points the quad points array (multiples of 8 values)
     */
    public void setQuadPoints(double[] points) {
        if (points == null) return;
        COSArray arr = new COSArray();
        for (double p : points) {
            arr.add(new COSFloat(p));
        }
        dict.set(COSName.of("QuadPoints"), arr);
    }

    /**
     * Sets multiple quadrilaterals at once. Each {@code double[8]} sub-array
     * is one quad in {@code [TLx TLy TRx TRy BLx BLy BRx BRy]} order
     * (ISO 32000-1:2008 §12.5.6.10 Table 179). The sub-arrays are
     * concatenated into the underlying flat representation.
     *
     * @param quads array of {@code double[8]} quadrilaterals;
     *              passing {@code null} removes the {@code /QuadPoints} entry.
     * @throws IllegalArgumentException if any sub-array is null
     */
    public void setQuadPoints(double[][] quads) {
        if (quads == null) {
            dict.set(COSName.of("QuadPoints"), (COSBase) null);
            return;
        }
        int total = 0;
        for (double[] q : quads) {
            if (q == null) throw new IllegalArgumentException("quad sub-array must not be null");
            total += q.length;
        }
        double[] flat = new double[total];
        int p = 0;
        for (double[] q : quads) {
            System.arraycopy(q, 0, flat, p, q.length);
            p += q.length;
        }
        setQuadPoints(flat);
    }
}
