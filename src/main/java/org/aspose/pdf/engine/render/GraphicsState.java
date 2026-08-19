package org.aspose.pdf.engine.render;

import org.aspose.pdf.Matrix;

import java.awt.BasicStroke;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.util.logging.Logger;

/**
 * Tracks the mutable graphics state during PDF page rendering (ISO 32000-1:2008, §8.4).
 * <p>
 * Encapsulates the current transformation matrix, color, line style, font,
 * text state, current path, and clipping path. Supports clone for the q/Q
 * (save/restore) stack.
 * </p>
 */
public class GraphicsState implements Cloneable {

    private static final Logger LOG = Logger.getLogger(GraphicsState.class.getName());

    // ---- Transformation matrix ----
    private Matrix ctm;

    // ---- Color (as java.awt.Color for direct Graphics2D use) ----
    private java.awt.Color fillColor;
    /** Name of fill Pattern (from /Resources/Pattern) when fill is set via Pattern colorspace; null otherwise. */
    private String fillPatternName;
    /** Same, for stroke. */
    private String strokePatternName;
    private java.awt.Color strokeColor;

    // ---- Line style ----
    private double lineWidth;
    private int lineCap;    // 0=butt, 1=round, 2=square
    private int lineJoin;   // 0=miter, 1=round, 2=bevel
    private double miterLimit;
    private float[] dashArray;
    private float dashPhase;

    // ---- Transparency ----
    private float strokingAlpha;
    private float nonStrokingAlpha;
    /** Blend mode from /BM (§11.3.5); shallow-copied on clone (immutable String). */
    private String blendMode = "Normal";
    /** Soft mask from /SMask in ExtGState (§11.6.5.1) — the mask dictionary,
     *  or null for /None. Shared reference on clone (read-only use). */
    private org.aspose.pdf.engine.pdfobjects.PdfDictionary softMask;
    /**
     * CTM snapshot taken when the /SMask was installed via gs. §11.6.5.2:
     * the mask group's coordinates are fixed at ExtGState-set time — a cm
     * issued between gs and the masked paint op must NOT move the mask.
     */
    private Matrix softMaskCtm;

    // ---- Font / text state ----
    private String fontName;
    private double fontSize;
    private double charSpacing;
    private double wordSpacing;
    private double horizontalScaling; // percentage, 100 = normal
    private double textLeading;
    private int textRenderingMode;
    private double textRise;

    // ---- Text matrices (ISO 32000, §9.4.2) ----
    private Matrix textMatrix;
    private Matrix textLineMatrix;

    // ---- Current path ----
    private GeneralPath currentPath;
    private double pathLastX;
    private double pathLastY;

    // ---- Clipping ----
    private GeneralPath clipPath;
    private boolean pendingClip;
    private boolean pendingClipEvenOdd;

    /**
     * Creates a new graphics state with PDF default values.
     */
    public GraphicsState() {
        ctm = Matrix.IDENTITY;
        fillColor = java.awt.Color.BLACK;
        strokeColor = java.awt.Color.BLACK;
        lineWidth = 1.0;
        lineCap = 0;
        lineJoin = 0;
        miterLimit = 10.0;
        dashArray = null;
        dashPhase = 0;
        strokingAlpha = 1.0f;
        nonStrokingAlpha = 1.0f;
        fontName = null;
        fontSize = 12;
        charSpacing = 0;
        wordSpacing = 0;
        horizontalScaling = 100;
        textLeading = 0;
        textRenderingMode = 0;
        textRise = 0;
        textMatrix = Matrix.IDENTITY;
        textLineMatrix = Matrix.IDENTITY;
        currentPath = new GeneralPath(Path2D.WIND_NON_ZERO);
        clipPath = null;
        pendingClip = false;
        pendingClipEvenOdd = false;
    }

    @Override
    public GraphicsState clone() {
        try {
            GraphicsState copy = (GraphicsState) super.clone();
            // Deep-copy mutable fields
            copy.currentPath = (GeneralPath) currentPath.clone();
            if (clipPath != null) {
                copy.clipPath = (GeneralPath) clipPath.clone();
            }
            if (dashArray != null) {
                copy.dashArray = dashArray.clone();
            }
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError(e);
        }
    }

    // ================ CTM ================

    /** Returns the current transformation matrix. */
    public Matrix getCTM() { return ctm; }

    /** Sets the current transformation matrix. */
    public void setCTM(Matrix ctm) { this.ctm = ctm; }

    /** Concatenates a matrix onto the CTM: ctm = matrix × ctm. */
    public void concatMatrix(Matrix matrix) {
        this.ctm = matrix.multiply(ctm);
    }

    // ================ Color ================

    /** Returns the fill color as an AWT color. */
    public java.awt.Color getFillColor() { return fillColor; }

    /** Returns the current fill Pattern name (or null for solid colour fill). */
    public String getFillPatternName() { return fillPatternName; }
    /** Stores a Pattern resource name as the fill source. Pass {@code null} to revert to solid colour. */
    public void setFillPatternName(String n) { this.fillPatternName = n; }
    /** Returns the current stroke Pattern name (or null). */
    public String getStrokePatternName() { return strokePatternName; }
    /** Stores a Pattern resource name as the stroke source. */
    public void setStrokePatternName(String n) { this.strokePatternName = n; }

    /** Sets the fill color from PDF RGB components (0..1). */
    public void setFillColorRGB(double r, double g, double b) {
        this.fillColor = deviceRgbColor(r, g, b);
    }

    /** Packs untagged DeviceRGB components, applying the Acrobat print-parity
     *  transparency-page shift ({@link org.aspose.pdf.engine.colorspace.RgbPrintShift})
     *  when active. */
    private static java.awt.Color deviceRgbColor(double r, double g, double b) {
        java.awt.Color c = new java.awt.Color(clamp(r), clamp(g), clamp(b));
        if (org.aspose.pdf.engine.colorspace.RgbPrintShift.active()) {
            c = new java.awt.Color(
                    org.aspose.pdf.engine.colorspace.RgbPrintShift.shift(c.getRGB()), false);
        }
        return c;
    }

    /** Sets the fill color from a PDF gray value (0..1). */
    public void setFillColorGray(double gray) {
        float g = clamp(gray);
        this.fillColor = new java.awt.Color(g, g, g);
    }

    /** Sets the fill color from PDF CMYK components (0..1) via the
     *  press-characterized display conversion
     *  ({@link org.aspose.pdf.engine.colorspace.CmykDisplay}). */
    public void setFillColorCMYK(double c, double m, double y, double k) {
        // Press-characterized display conversion (CGATS LUT) - matches what
        // ICC-aware viewers show; the algebraic formula made print grays
        // greenish and crushed mid-tone mixes (corpus 10734).
        int argb = org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(c, m, y, k);
        this.fillColor = new java.awt.Color(argb, false);
    }

    /** Sets the fill color directly. */
    public void setFillColor(java.awt.Color color) { this.fillColor = color; }

    // ---- Active color spaces selected by cs/CS (ISO 32000 8.6.8) ----
    private org.aspose.pdf.engine.colorspace.ColorSpaceBase fillColorSpace;
    private org.aspose.pdf.engine.colorspace.ColorSpaceBase strokeColorSpace;

    /** Returns the color space selected by the last {@code cs}, or null. */
    public org.aspose.pdf.engine.colorspace.ColorSpaceBase getFillColorSpace() { return fillColorSpace; }
    /** Stores the color space selected by {@code cs} for subsequent sc/scn. */
    public void setFillColorSpace(org.aspose.pdf.engine.colorspace.ColorSpaceBase cs) { this.fillColorSpace = cs; }
    /** Returns the color space selected by the last {@code CS}, or null. */
    public org.aspose.pdf.engine.colorspace.ColorSpaceBase getStrokeColorSpace() { return strokeColorSpace; }
    /** Stores the color space selected by {@code CS} for subsequent SC/SCN. */
    public void setStrokeColorSpace(org.aspose.pdf.engine.colorspace.ColorSpaceBase cs) { this.strokeColorSpace = cs; }

    /** Returns the stroke color as an AWT color. */
    public java.awt.Color getStrokeColor() { return strokeColor; }

    /** Sets the stroke color from PDF RGB components (0..1). */
    public void setStrokeColorRGB(double r, double g, double b) {
        this.strokeColor = deviceRgbColor(r, g, b);
    }

    /** Sets the stroke color from a PDF gray value (0..1). */
    public void setStrokeColorGray(double gray) {
        float g = clamp(gray);
        this.strokeColor = new java.awt.Color(g, g, g);
    }

    /** Sets the stroke color from PDF CMYK components (0..1) via the
     *  press-characterized display conversion
     *  ({@link org.aspose.pdf.engine.colorspace.CmykDisplay}). */
    public void setStrokeColorCMYK(double c, double m, double y, double k) {
        int argb = org.aspose.pdf.engine.colorspace.CmykDisplay.toRGBInt(c, m, y, k);
        this.strokeColor = new java.awt.Color(argb, false);
    }

    /** Sets the stroke color directly. */
    public void setStrokeColor(java.awt.Color color) { this.strokeColor = color; }

    // ================ Line style ================

    /** Returns the line width in user units. */
    public double getLineWidth() { return lineWidth; }
    /** Sets the line width. */
    public void setLineWidth(double w) { this.lineWidth = w; }

    /** Returns the line cap style (0=butt, 1=round, 2=square). */
    public int getLineCap() { return lineCap; }
    /** Sets the line cap style. */
    public void setLineCap(int cap) { this.lineCap = cap; }

    /** Returns the line join style (0=miter, 1=round, 2=bevel). */
    public int getLineJoin() { return lineJoin; }
    /** Sets the line join style. */
    public void setLineJoin(int join) { this.lineJoin = join; }

    /** Returns the miter limit. */
    public double getMiterLimit() { return miterLimit; }
    /** Sets the miter limit. */
    public void setMiterLimit(double limit) { this.miterLimit = limit; }

    /** Returns the dash array, or null for solid lines. */
    public float[] getDashArray() { return dashArray; }
    /** Returns the dash phase. */
    public float getDashPhase() { return dashPhase; }
    /** Sets the dash pattern. */
    public void setDash(float[] array, float phase) {
        this.dashArray = array;
        this.dashPhase = phase;
    }

    /**
     * Creates an AWT BasicStroke from the current line style.
     *
     * @return the stroke
     */
    public BasicStroke createStroke() {
        // ISO 32000 §8.4.3.2: a line width of 0 denotes the thinnest line the
        // device can render (one pixel) — NOT an invisible line. Java2D has
        // the same convention: a 0-width BasicStroke is a device-space
        // hairline, immune to CTM scaling (a 0.1 floor here would shrink to
        // nothing under a down-scaling CTM, e.g. 0.05 in corpus 29903.pdf).
        // Small positive widths keep the historical 0.1 floor.
        float w = lineWidth == 0 ? 0f : Math.max((float) lineWidth, 0.1f);
        int cap = mapLineCap(lineCap);
        int join = mapLineJoin(lineJoin);
        float ml = Math.max((float) miterLimit, 1.0f);
        if (dashArray != null && dashArray.length > 0) {
            return new BasicStroke(w, cap, join, ml, dashArray, dashPhase);
        }
        return new BasicStroke(w, cap, join, ml);
    }

    // ================ Transparency ================

    /** Returns the stroking alpha (0..1). */
    public float getStrokingAlpha() { return strokingAlpha; }
    /** Sets the stroking alpha. */
    public void setStrokingAlpha(float alpha) { this.strokingAlpha = alpha; }

    /** Returns the non-stroking (fill) alpha (0..1). */
    public float getNonStrokingAlpha() { return nonStrokingAlpha; }
    /** Sets the non-stroking alpha. */
    public void setNonStrokingAlpha(float alpha) { this.nonStrokingAlpha = alpha; }

    /** Returns the blend mode (/BM, §11.3.5). Default "Normal". */
    public String getBlendMode() { return blendMode; }
    /** Sets the blend mode; null resets to "Normal". */
    public void setBlendMode(String mode) {
        this.blendMode = (mode != null && !mode.isEmpty()) ? mode : "Normal";
    }

    /** Returns the soft-mask dictionary (/SMask, §11.6.5.1), or null for /None. */
    public org.aspose.pdf.engine.pdfobjects.PdfDictionary getSoftMask() { return softMask; }
    /** Sets the soft-mask dictionary; null means /None. */
    public void setSoftMask(org.aspose.pdf.engine.pdfobjects.PdfDictionary mask) {
        this.softMask = mask;
        if (mask == null) this.softMaskCtm = null;
    }

    /** Returns the CTM captured when the /SMask was installed, or null. */
    public Matrix getSoftMaskCtm() { return softMaskCtm; }
    /** Records the CTM in effect at ExtGState-set time (§11.6.5.2). */
    public void setSoftMaskCtm(Matrix m) { this.softMaskCtm = m; }

    // ================ Font / Text state ================

    /** Returns the current font resource name (e.g., "F1"). */
    public String getFontName() { return fontName; }
    /** Sets the current font. */
    public void setFont(String name, double size) {
        this.fontName = name;
        this.fontSize = size;
    }

    /** Returns the current font size. */
    public double getFontSize() { return fontSize; }

    /** Returns the character spacing in text-space units. */
    public double getCharSpacing() { return charSpacing; }
    /** Sets the character spacing. */
    public void setCharSpacing(double cs) { this.charSpacing = cs; }

    /** Returns the word spacing in text-space units. */
    public double getWordSpacing() { return wordSpacing; }
    /** Sets the word spacing. */
    public void setWordSpacing(double ws) { this.wordSpacing = ws; }

    /** Returns the horizontal text scaling (percentage, 100 = normal). */
    public double getHorizontalScaling() { return horizontalScaling; }
    /** Sets the horizontal text scaling. */
    public void setHorizontalScaling(double hs) { this.horizontalScaling = hs; }

    /** Returns the text leading. */
    public double getTextLeading() { return textLeading; }
    /** Sets the text leading. */
    public void setTextLeading(double tl) { this.textLeading = tl; }

    /** Returns the text rendering mode (0-7). */
    public int getTextRenderingMode() { return textRenderingMode; }
    /** Sets the text rendering mode. */
    public void setTextRenderingMode(int mode) { this.textRenderingMode = mode; }

    /** Returns the text rise. */
    public double getTextRise() { return textRise; }
    /** Sets the text rise. */
    public void setTextRise(double rise) { this.textRise = rise; }

    // ================ Text matrices ================

    /** Returns the text matrix (Tm). */
    public Matrix getTextMatrix() { return textMatrix; }
    /** Sets the text matrix. */
    public void setTextMatrix(Matrix tm) {
        this.textMatrix = tm;
        this.textLineMatrix = tm;
    }

    /** Sets the text matrix without changing the text line matrix (for glyph advance). */
    public void setTextMatrixDirect(Matrix tm) {
        this.textMatrix = tm;
    }

    /** Returns the text line matrix (Tlm). */
    public Matrix getTextLineMatrix() { return textLineMatrix; }

    /**
     * Moves the text position by (tx, ty) — implements Td operator.
     * <p>
     * Sets Tlm = T(tx,ty) × Tlm, and sets Tm = Tlm.
     * </p>
     */
    public void moveTextPosition(double tx, double ty) {
        Matrix translation = new Matrix(1, 0, 0, 1, tx, ty);
        this.textLineMatrix = translation.multiply(textLineMatrix);
        this.textMatrix = textLineMatrix;
    }

    /**
     * Moves to the next line — implements T* operator.
     * Equivalent to: 0, -textLeading Td.
     */
    public void nextLine() {
        moveTextPosition(0, -textLeading);
    }

    /**
     * Begins a text object (BT) — resets text matrix and text line matrix to identity.
     */
    public void beginText() {
        this.textMatrix = Matrix.IDENTITY;
        this.textLineMatrix = Matrix.IDENTITY;
    }

    // ================ Path operations ================

    /**
     * Acrobat-compatibility coordinate limit (ISO 32000 Annex C.2): Acrobat
     * silently drops path segments whose CTM-mapped coordinates exceed
     * ±32767. Real content stays far below this (max media box is 14400
     * units); coordinates beyond it come from corrupt or hostile streams
     * (corpus 46075.pdf carries a damaged Flate region whose fused number
     * tokens yield coordinates in the millions — Acrobat renders the page
     * clean, while keeping the segments painted page-covering "lens" blobs
     * and drove Java2D/Marlin stroking into a multi-minute subdivision).
     * Disable with {@code -Drender.coordLimit=false}.
     */
    private static final double COORD_LIMIT = 32767;
    private static final boolean COORD_LIMIT_ON =
            !"false".equalsIgnoreCase(System.getProperty("render.coordLimit"));

    /**
     * True while the current subpath has no valid anchor point (its moveTo
     * was dropped by the coordinate limit); segments are skipped until the
     * next in-range point re-anchors the subpath.
     */
    private boolean pathPoisoned;

    /** Returns false when the coordinate limit rejects this user-space point. */
    private boolean pathPointOk(double x, double y) {
        if (!COORD_LIMIT_ON) return true;
        if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
        double[] p = ctm.transformPoint(x, y);
        return Math.abs(p[0]) <= COORD_LIMIT && Math.abs(p[1]) <= COORD_LIMIT;
    }

    /**
     * Whether the point survives the coordinate limit after SATURATING
     * clamping (Acrobat keeps well-formed out-of-range geometry by clamping
     * it to the Annex-C range rather than dropping it: corpus 58919 clips
     * with an even-odd donut whose outer rectangle spans ±2.7M units — the
     * logo inside survives in Acrobat's print). Only non-finite points are
     * rejected; {@code out} receives the clamped USER-space point.
     */
    private boolean pathPointClamped(double x, double y, double[] out) {
        out[0] = x;
        out[1] = y;
        if (!COORD_LIMIT_ON) return true;
        if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
        double[] p = ctm.transformPoint(x, y);
        if (Math.abs(p[0]) <= COORD_LIMIT && Math.abs(p[1]) <= COORD_LIMIT) return true;
        // Saturate in DEVICE space, then map back to user space so the
        // clamped point lands exactly on the Annex-C boundary.
        double cx = Math.max(-COORD_LIMIT, Math.min(COORD_LIMIT, p[0]));
        double cy = Math.max(-COORD_LIMIT, Math.min(COORD_LIMIT, p[1]));
        try {
            java.awt.geom.AffineTransform inv = new java.awt.geom.AffineTransform(
                    ctm.getA(), ctm.getB(), ctm.getC(), ctm.getD(),
                    ctm.getE(), ctm.getF()).createInverse();
            java.awt.geom.Point2D up = inv.transform(new java.awt.geom.Point2D.Double(cx, cy), null);
            out[0] = up.getX();
            out[1] = up.getY();
            return true;
        } catch (java.awt.geom.NoninvertibleTransformException e) {
            return false; // degenerate CTM — treat as dropped (old behavior)
        }
    }

    /** Returns the current path. */
    public GeneralPath getCurrentPath() { return currentPath; }

    /** Begins a new subpath at (x, y). */
    public void moveTo(double x, double y) {
        if (!pathPointOk(x, y)) {
            pathPoisoned = true;
            return;
        }
        pathPoisoned = false;
        currentPath.moveTo((float) x, (float) y);
        pathLastX = x;
        pathLastY = y;
    }

    /** Appends a line from the current point to (x, y). */
    public void lineTo(double x, double y) {
        if (!pathPointOk(x, y)) return;
        if (pathPoisoned) { // re-anchor a subpath whose moveTo was dropped
            moveTo(x, y);
            return;
        }
        currentPath.lineTo((float) x, (float) y);
        pathLastX = x;
        pathLastY = y;
    }

    /** Appends a cubic Bézier curve (c operator). */
    public void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) {
        if (!pathPointOk(x1, y1) || !pathPointOk(x2, y2) || !pathPointOk(x3, y3)) return;
        if (pathPoisoned) {
            moveTo(x3, y3);
            return;
        }
        currentPath.curveTo((float) x1, (float) y1, (float) x2, (float) y2, (float) x3, (float) y3);
        pathLastX = x3;
        pathLastY = y3;
    }

    /** Appends a cubic Bézier curve with first control point = current point (v operator). */
    public void curveToV(double x2, double y2, double x3, double y3) {
        if (!pathPointOk(x2, y2) || !pathPointOk(x3, y3)) return;
        if (pathPoisoned) {
            moveTo(x3, y3);
            return;
        }
        currentPath.curveTo((float) pathLastX, (float) pathLastY,
                (float) x2, (float) y2, (float) x3, (float) y3);
        pathLastX = x3;
        pathLastY = y3;
    }

    /** Appends a cubic Bézier curve with final control point = end point (y operator). */
    public void curveToY(double x1, double y1, double x3, double y3) {
        if (!pathPointOk(x1, y1) || !pathPointOk(x3, y3)) return;
        if (pathPoisoned) {
            moveTo(x3, y3);
            return;
        }
        currentPath.curveTo((float) x1, (float) y1, (float) x3, (float) y3, (float) x3, (float) y3);
        pathLastX = x3;
        pathLastY = y3;
    }

    /** Appends a rectangle (re operator). */
    public void rect(double x, double y, double w, double h) {
        // Out-of-range rectangles are CLAMPED, not dropped: a rectangle stays
        // a rectangle after saturating both corners, and Acrobat keeps such
        // well-formed geometry (corpus 58919's even-odd clip pairs a small
        // hole with a ±2.7M-unit outer rect — dropping the outer rect turned
        // the "everything except the hole" clip into "only the hole" and cut
        // the page's logo away). Damaged-stream m/l/c segments (corpus 46075)
        // keep the drop behavior in moveTo/lineTo/curveTo.
        double[] p0 = new double[2];
        double[] p1 = new double[2];
        if (!pathPointClamped(x, y, p0) || !pathPointClamped(x + w, y + h, p1)) return;
        pathPoisoned = false;
        currentPath.moveTo((float) p0[0], (float) p0[1]);
        currentPath.lineTo((float) p1[0], (float) p0[1]);
        currentPath.lineTo((float) p1[0], (float) p1[1]);
        currentPath.lineTo((float) p0[0], (float) p1[1]);
        currentPath.closePath();
        pathLastX = p0[0];
        pathLastY = p0[1];
    }

    /** Closes the current subpath (h operator). */
    public void closePath() {
        if (pathPoisoned) return;
        currentPath.closePath();
    }

    /** Clears the current path after painting or no-op. */
    public void clearPath() {
        currentPath.reset();
        pathPoisoned = false;
    }

    // ================ Clipping ================

    /** Returns the current clipping path, or null if not set. */
    public GeneralPath getClipPath() { return clipPath; }

    /** Sets the clipping path directly. */
    public void setClipPath(GeneralPath path) { this.clipPath = path; }

    /** Marks a pending non-zero winding clip (W operator). */
    public void setPendingClip() {
        this.pendingClip = true;
        this.pendingClipEvenOdd = false;
    }

    /** Marks a pending even-odd clip (W* operator). */
    public void setPendingClipEvenOdd() {
        this.pendingClip = true;
        this.pendingClipEvenOdd = true;
    }

    /** Returns true if there is a pending clip to apply. */
    public boolean hasPendingClip() { return pendingClip; }

    /** Returns true if the pending clip uses even-odd rule. */
    public boolean isPendingClipEvenOdd() { return pendingClipEvenOdd; }

    /** Clears the pending clip flag (after it has been applied). */
    public void clearPendingClip() {
        this.pendingClip = false;
        this.pendingClipEvenOdd = false;
    }

    // ================ Helpers ================

    private static float clamp(double v) {
        return (float) Math.max(0.0, Math.min(1.0, v));
    }

    private static int mapLineCap(int pdfCap) {
        switch (pdfCap) {
            case 0: return BasicStroke.CAP_BUTT;
            case 1: return BasicStroke.CAP_ROUND;
            case 2: return BasicStroke.CAP_SQUARE;
            default: return BasicStroke.CAP_BUTT;
        }
    }

    private static int mapLineJoin(int pdfJoin) {
        switch (pdfJoin) {
            case 0: return BasicStroke.JOIN_MITER;
            case 1: return BasicStroke.JOIN_ROUND;
            case 2: return BasicStroke.JOIN_BEVEL;
            default: return BasicStroke.JOIN_MITER;
        }
    }
}
