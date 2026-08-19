package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;

/**
 * Line annotation (ISO 32000-1:2008, Section 12.5.6.7, /Subtype /Line).
 * <p>
 * A line annotation displays a single straight line on the page, defined
 * by two endpoints. It may optionally include leader lines and line endings.
 * </p>
 */
public class LineAnnotation extends MarkupAnnotation {

    private Point starting;
    private Point ending;

    /**
     * Constructs a line annotation from an existing PDF dictionary.
     *
     * @param dict the PDF dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public LineAnnotation(PdfDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new line annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    public LineAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        dict.set(PdfName.of("Subtype"), PdfName.of("Line"));
    }

    /**
     * Constructs a new line annotation with explicit start and end points.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     * @param x1   the x-coordinate of the start point
     * @param y1   the y-coordinate of the start point
     * @param x2   the x-coordinate of the end point
     * @param y2   the y-coordinate of the end point
     */
    public LineAnnotation(Page page, Rectangle rect, double x1, double y1, double x2, double y2) {
        super(page, rect);
        dict.set(PdfName.of("Subtype"), PdfName.of("Line"));
        PdfArray l = new PdfArray();
        l.add(new PdfFloat(x1));
        l.add(new PdfFloat(y1));
        l.add(new PdfFloat(x2));
        l.add(new PdfFloat(y2));
        dict.set(PdfName.of("L"), l);
        this.starting = new Point(x1, y1);
        this.ending = new Point(x2, y2);
    }

    /**
     * Constructs a new line annotation with {@link Point}-based start and end points.
     *
     * @param page       the page this annotation belongs to
     * @param rect       the annotation rectangle
     * @param startPoint the start point of the line
     * @param endPoint   the end point of the line
     */
    public LineAnnotation(Page page, Rectangle rect, Point startPoint, Point endPoint) {
        this(page, rect,
             startPoint != null ? startPoint.getX() : 0,
             startPoint != null ? startPoint.getY() : 0,
             endPoint != null ? endPoint.getX() : 0,
             endPoint != null ? endPoint.getY() : 0);
    }

    /**
     * Returns the line endpoints as a four-element array [x1, y1, x2, y2].
     *
     * @return the line coordinates, or null if not set
     */
    public double[] getLine() {
        PdfBase l = dict.get("L");
        if (l instanceof PdfArray) {
            PdfArray arr = (PdfArray) l;
            if (arr.size() >= 4) {
                return new double[]{
                        arr.getFloat(0, 0), arr.getFloat(1, 0),
                        arr.getFloat(2, 0), arr.getFloat(3, 0)
                };
            }
        }
        return null;
    }

    /**
     * Returns the starting point of the line.
     *
     * @return the start point, or null if not set
     */
    public Point getStarting() {
        // The /L array in the dictionary is authoritative: it may have been
        // rewritten externally (e.g. PdfFileEditor.resizeContents transforms the
        // endpoints), so prefer it over any cached Point. Fall back to the cached
        // value only when /L is not present yet.
        double[] coords = getLine();
        if (coords != null && coords.length >= 4) {
            starting = new Point(coords[0], coords[1]);
            return starting;
        }
        return starting;
    }

    /**
     * Sets the starting point of the line.
     *
     * @param point the start point
     */
    public void setStarting(Point point) {
        this.starting = point;
        updateLineArray();
    }

    /**
     * Returns the ending point of the line.
     *
     * @return the end point, or null if not set
     */
    public Point getEnding() {
        double[] coords = getLine();
        if (coords != null && coords.length >= 4) {
            ending = new Point(coords[2], coords[3]);
            return ending;
        }
        return ending;
    }

    /**
     * Sets the ending point of the line.
     *
     * @param point the end point
     */
    public void setEnding(Point point) {
        this.ending = point;
        updateLineArray();
    }

    /**
     * Returns the intent of this line annotation.
     *
     * @return the line intent
     */
    public LineIntent getIntent() {
        String it = dict.getString("IT");
        if ("LineArrow".equals(it)) return LineIntent.LineArrow;
        if ("LineDimension".equals(it)) return LineIntent.LineDimension;
        return LineIntent.Undefined;
    }

    /**
     * Sets the intent of this line annotation.
     *
     * @param intent the line intent
     */
    public void setIntent(LineIntent intent) {
        if (intent == null || intent == LineIntent.Undefined) {
            dict.remove(PdfName.of("IT"));
        } else {
            dict.set(PdfName.of("IT"), PdfName.of(intent.name()));
        }
    }

    /**
     * Returns the line ending style at the start of the line.
     *
     * @return the starting style
     */
    public LineEnding getStartingStyle() {
        return getLineEndingFromLE(0);
    }

    /**
     * Sets the line ending style at the start of the line.
     *
     * @param style the starting style
     */
    public void setStartingStyle(LineEnding style) {
        setLineEndingInLE(0, style);
    }

    /**
     * Returns the line ending style at the end of the line.
     *
     * @return the ending style
     */
    public LineEnding getEndingStyle() {
        return getLineEndingFromLE(1);
    }

    /**
     * Sets the line ending style at the end of the line.
     *
     * @param style the ending style
     */
    public void setEndingStyle(LineEnding style) {
        setLineEndingInLE(1, style);
    }

    private LineEnding getLineEndingFromLE(int index) {
        PdfBase le = dict.get("LE");
        if (le instanceof PdfArray && ((PdfArray) le).size() > index) {
            PdfBase val = ((PdfArray) le).get(index);
            if (val instanceof PdfName) {
                String name = ((PdfName) val).getName();
                try {
                    return LineEnding.valueOf(name);
                } catch (IllegalArgumentException e) {
                    return LineEnding.None;
                }
            }
        }
        return LineEnding.None;
    }

    private void setLineEndingInLE(int index, LineEnding style) {
        PdfBase existing = dict.get("LE");
        PdfArray le;
        if (existing instanceof PdfArray && ((PdfArray) existing).size() >= 2) {
            le = (PdfArray) existing;
        } else {
            le = new PdfArray();
            le.add(PdfName.of("None"));
            le.add(PdfName.of("None"));
        }
        le.set(index, PdfName.of(style != null ? style.name() : "None"));
        dict.set(PdfName.of("LE"), le);
    }

    /**
     * Updates the /L array in the PDF dictionary from the current starting/ending points.
     */
    private void updateLineArray() {
        Point s = starting != null ? starting : new Point(0, 0);
        Point e = ending != null ? ending : new Point(0, 0);
        PdfArray l = new PdfArray();
        l.add(new PdfFloat(s.getX()));
        l.add(new PdfFloat(s.getY()));
        l.add(new PdfFloat(e.getX()));
        l.add(new PdfFloat(e.getY()));
        dict.set(PdfName.of("L"), l);
    }
}
