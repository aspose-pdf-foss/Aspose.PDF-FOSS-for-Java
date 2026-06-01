package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Abstract base for all PDF annotations (ISO 32000-1:2008, §12.5).
 */
public abstract class Annotation extends org.aspose.pdf.BaseParagraph {
    private static final Logger LOG = Logger.getLogger(Annotation.class.getName());
    protected COSDictionary dict;
    protected Page page;
    private Border annotBorder;

    /**
     * Constructs an annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation; if null, a new empty dictionary is created
     * @param page the page this annotation belongs to
     */
    protected Annotation(COSDictionary dict, Page page) {
        this.dict = dict != null ? dict : new COSDictionary();
        this.page = page;
    }

    /**
     * Constructs a new annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    protected Annotation(Page page, Rectangle rect) {
        this.dict = new COSDictionary();
        this.page = page;
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        // F-10 sibling fix (Sprint 21): the base constructor stores the rect
        // leniently. Many annotation types are naturally degenerate — a
        // horizontal LineAnnotation has zero height, a TextAnnotation note icon
        // has a point-like /Rect — and Aspose.PDF accepts these. Subtypes that
        // genuinely require a positive-area box (Square, Caret) re-validate via
        // {@link #requirePositiveArea(Rectangle)} in their own constructors.
        setRectLenient(rect);
    }

    /**
     * Returns the annotation subtype (e.g. "Text", "Link", "Highlight").
     *
     * @return the subtype name, or null if not set
     */
    public String getSubtype() { return dict.getNameAsString("Subtype"); }

    /**
     * Returns the annotation rectangle defining its location on the page.
     *
     * @return the rectangle, or null if not set or malformed
     */
    public Rectangle getRect() {
        COSBase r = dict.get("Rect");
        if (r instanceof COSArray && ((COSArray) r).size() == 4) return Rectangle.fromCOSArray((COSArray) r);
        return null;
    }

    /**
     * Sets the annotation rectangle (ISO 32000-1:2008 §12.5.2, Table 164,
     * {@code /Rect} entry). The rectangle must have <strong>positive
     * area</strong> — both width and height must be strictly greater than
     * zero. Strict spec-compliant viewers (Poppler, MuPDF) reject annotations
     * whose {@code /Rect} collapses to a line or point with a
     * {@code "Bad bounding box for annotation"} error.
     *
     * <p>For naturally-point-like annotation types (e.g. {@link CaretAnnotation}),
     * use a dedicated helper that expands the point into a sensible bounding
     * box — see {@link CaretAnnotation#atPoint(Page, Point)} for the canonical
     * pattern.</p>
     *
     * @param rect the rectangle to set; ignored if null
     * @throws IllegalArgumentException if {@code rect} has zero or negative
     *         width or height
     */
    public void setRect(Rectangle rect) {
        if (rect == null) return;
        requirePositiveArea(rect);
        dict.set(COSName.of("Rect"), rect.toCOSArray());
    }

    /**
     * Stores the {@code /Rect} entry without enforcing positive area. Used by
     * the internal annotation/form-field machinery (constructors, incremental
     * {@code setWidth}/{@code setHeight} builders) where a transiently or
     * genuinely degenerate rectangle is valid — Aspose.PDF stores such
     * rectangles rather than rejecting them (F-10 sibling fix, Sprint 21).
     *
     * @param rect the rectangle to store; ignored if null
     */
    protected void setRectLenient(Rectangle rect) {
        if (rect == null) return;
        if (rect.getWidth() <= 0 || rect.getHeight() <= 0) {
            LOG.fine(() -> "/Rect has non-positive area (width=" + rect.getWidth()
                    + ", height=" + rect.getHeight() + "); storing anyway (Aspose-compat)");
        }
        dict.set(COSName.of("Rect"), rect.toCOSArray());
    }

    /**
     * Throws {@link IllegalArgumentException} if the given rectangle has zero
     * or negative width or height. Used by the public {@link #setRect} setter
     * and by shape annotation types (e.g. {@link SquareAnnotation}) whose
     * geometry is meaningless without a positive-area bounding box.
     *
     * @param rect the rectangle to validate; null is treated as valid (no-op)
     * @throws IllegalArgumentException if {@code rect} has non-positive area
     */
    protected static void requirePositiveArea(Rectangle rect) {
        if (rect == null) return;
        if (rect.getWidth() <= 0 || rect.getHeight() <= 0) {
            throw new IllegalArgumentException(
                    "/Rect must have positive area (got width=" + rect.getWidth()
                            + ", height=" + rect.getHeight() + ")");
        }
    }

    /**
     * Returns the text content of the annotation (/Contents entry).
     *
     * @return the contents string, or null if not set
     */
    public String getContents() {
        COSBase c = dict.get("Contents");
        return (c instanceof COSString) ? ((COSString) c).getString() : null;
    }

    /**
     * Sets the text content of the annotation (/Contents entry).
     *
     * @param contents the contents string, or null to remove
     */
    public void setContents(String contents) {
        if (contents != null) dict.set(COSName.of("Contents"), new COSString(contents.getBytes(StandardCharsets.UTF_8)));
        else dict.remove(COSName.of("Contents"));
    }

    /**
     * Returns the unique name of the annotation (/NM entry).
     *
     * @return the name string, or null if not set
     */
    public String getName() {
        COSBase nm = dict.get("NM");
        return (nm instanceof COSString) ? ((COSString) nm).getString() : null;
    }

    /**
     * Sets the unique name of the annotation (/NM entry).
     *
     * @param name the name string
     */
    public void setName(String name) {
        if (name != null) dict.set(COSName.of("NM"), new COSString(name.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Returns the date and time the annotation was last modified (/M entry).
     *
     * @return the modification date string, or null if not set
     */
    public String getModified() {
        COSBase m = dict.get("M");
        return (m instanceof COSString) ? ((COSString) m).getString() : null;
    }

    /**
     * Sets the date and time the annotation was last modified (/M entry).
     *
     * @param date the modification date string in PDF date format, or null to remove
     */
    public void setModified(String date) {
        if (date != null) dict.set(COSName.of("M"), new COSString(date.getBytes(StandardCharsets.UTF_8)));
        else dict.remove(COSName.of("M"));
    }

    /**
     * Returns the annotation flags (/F entry) as a bitmask.
     *
     * @return the flags value, or 0 if not set
     */
    public int getFlags() { return dict.getInt("F", 0); }

    /**
     * Sets the annotation flags (/F entry) as a bitmask.
     *
     * @param flags the flags bitmask
     */
    public void setFlags(int flags) { dict.set(COSName.of("F"), COSInteger.valueOf(flags)); }

    /**
     * Returns the annotation flags as a typed {@link java.util.EnumSet}.
     *
     * @return the set of flags currently set in {@code /F}
     */
    public java.util.EnumSet<AnnotationFlags> getFlagsAsEnum() {
        return AnnotationFlags.fromBits(getFlags());
    }

    /**
     * Sets the annotation flags from a typed {@link java.util.EnumSet}.
     *
     * @param flags the flags to encode into {@code /F} (must not be null)
     */
    public void setFlags(java.util.EnumSet<AnnotationFlags> flags) {
        if (flags == null) {
            throw new IllegalArgumentException("flags must not be null");
        }
        setFlags(AnnotationFlags.toBits(flags));
    }

    /**
     * Convenience: sets the annotation flags from a varargs list of enum values.
     *
     * @param flags the flags to set
     */
    public void setFlags(AnnotationFlags... flags) {
        java.util.EnumSet<AnnotationFlags> set = java.util.EnumSet.noneOf(AnnotationFlags.class);
        if (flags != null) {
            for (AnnotationFlags f : flags) {
                if (f != null) set.add(f);
            }
        }
        setFlags(set);
    }

    /**
     * Returns whether the Invisible flag (bit 1) is set.
     *
     * @return true if the annotation is invisible
     */
    public boolean isInvisible() { return (getFlags() & 0x01) != 0; }

    /**
     * Returns whether the Hidden flag (bit 2) is set.
     *
     * @return true if the annotation is hidden
     */
    public boolean isHidden() { return (getFlags() & 0x02) != 0; }

    /**
     * Returns whether the Print flag (bit 3) is set.
     *
     * @return true if the annotation should be printed
     */
    public boolean isPrint() { return (getFlags() & 0x04) != 0; }

    /**
     * Returns whether the NoZoom flag (bit 4) is set.
     *
     * @return true if the annotation should not scale with the page
     */
    public boolean isNoZoom() { return (getFlags() & 0x08) != 0; }

    /**
     * Returns whether the NoRotate flag (bit 5) is set.
     *
     * @return true if the annotation should not rotate with the page
     */
    public boolean isNoRotate() { return (getFlags() & 0x10) != 0; }

    /**
     * Returns whether the NoView flag (bit 6) is set.
     *
     * @return true if the annotation should not be displayed
     */
    public boolean isNoView() { return (getFlags() & 0x20) != 0; }

    /**
     * Returns whether the ReadOnly flag (bit 7) is set.
     *
     * @return true if the annotation is read-only
     */
    public boolean isReadOnly() { return (getFlags() & 0x40) != 0; }

    /**
     * Returns whether the Locked flag (bit 8) is set.
     *
     * @return true if the annotation is locked
     */
    public boolean isLocked() { return (getFlags() & 0x80) != 0; }

    /**
     * Returns the annotation color (/C entry).
     *
     * @return the color, or null if not set
     */
    public Color getColor() {
        COSBase c = dict.get("C");
        if (c instanceof COSArray) {
            COSArray arr = (COSArray) c;
            if (arr.size() == 3) return Color.fromRgb(arr.getFloat(0,0), arr.getFloat(1,0), arr.getFloat(2,0));
            if (arr.size() == 1) return Color.fromGray(arr.getFloat(0,0));
            if (arr.size() == 4) return Color.fromCmyk(arr.getFloat(0,0), arr.getFloat(1,0), arr.getFloat(2,0), arr.getFloat(3,0));
        }
        return null;
    }

    /**
     * Sets the annotation color (/C entry) as an RGB color array.
     *
     * @param color the color to set, or null to remove
     */
    public void setColor(Color color) {
        if (color == null) { dict.remove(COSName.of("C")); return; }
        COSArray c = new COSArray();
        c.add(new COSFloat(color.getR()));
        c.add(new COSFloat(color.getG()));
        c.add(new COSFloat(color.getB()));
        dict.set(COSName.of("C"), c);
    }

    /**
     * Returns the border of this annotation.
     *
     * @return the border, or null
     */
    public Border getBorder() {
        if (annotBorder == null) {
            annotBorder = new Border(this);
            COSBase borderBase = dict.get("Border");
            if (borderBase instanceof COSArray) {
                COSArray borderArray = (COSArray) borderBase;
                if (borderArray.size() >= 3) {
                    annotBorder.setWidth(borderArray.getFloat(2, 1f));
                }
            }
        }
        return annotBorder;
    }

    /**
     * Sets the border of this annotation.
     *
     * @param border the border to set
     */
    public void setBorder(Border border) {
        this.annotBorder = border;
        if (border != null) {
            COSArray bs = new COSArray();
            bs.add(new COSFloat(0)); // horizontal corner radius
            bs.add(new COSFloat(0)); // vertical corner radius
            bs.add(new COSFloat(border.getWidth()));
            dict.set(COSName.of("Border"), bs);
        }
    }

    /**
     * Returns the raw normal appearance stream (/AP /N) for this annotation
     * (ISO 32000-1:2008, §12.5.5).
     * <p>
     * The normal appearance is used when the annotation is not interacted with.
     * If /AP /N is a dictionary of sub-appearances (for annotations with
     * multiple states), this returns null — only direct stream values are returned.
     * </p>
     *
     * @return the normal appearance COSStream, or null if absent or not a stream
     */
    public COSStream getNormalAppearanceStream() {
        COSBase ap = resolveRef(dict.get("AP"));
        if (!(ap instanceof COSDictionary)) return null;
        COSBase n = resolveRef(((COSDictionary) ap).get("N"));
        if (n instanceof COSStream) return (COSStream) n;
        return null;
    }

    /**
     * Returns the normal appearance as an {@link XForm}, mirroring the C#
     * {@code Annotation.NormalAppearance} property. The XForm wraps the
     * underlying /AP /N COSStream so callers can iterate its content
     * operators via {@link XForm#getContents()}.
     *
     * @return the XForm wrapping /AP /N, or {@code null} if absent or not a stream
     */
    public XForm getNormalAppearance() {
        COSStream stream = getNormalAppearanceStream();
        if (stream == null) return null;
        return new XForm(stream, "N", null);
    }

    /**
     * Resolves an indirect object reference. If the value is a COSObjectReference,
     * dereferences it. Otherwise returns the value as-is.
     *
     * @param value the COS value to resolve
     * @return the resolved value, or null
     */
    private COSBase resolveRef(COSBase value) {
        if (value == null) return null;
        if (value instanceof COSObjectReference) {
            try {
                return ((COSObjectReference) value).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference: " + e.getMessage());
                return null;
            }
        }
        return value;
    }

    /**
     * Flattens this annotation into the page content stream.
     * <p>
     * Bakes the annotation's normal appearance stream (/AP /N) into the page content
     * using a CTM to map the appearance BBox to the annotation Rect, then removes
     * the annotation from the page's /Annots array.
     * If no normal appearance stream is available, the annotation is simply removed.
     * </p>
     *
     * @throws IOException if reading the appearance stream fails
     */
    public void flatten() throws IOException {
        if (page == null) return;

        COSStream apStream = getNormalAppearanceStream();
        if (apStream != null) {
            Rectangle annotRect = getRect();
            if (annotRect != null) {
                // Get appearance BBox (defaults to Rect if absent)
                COSBase bboxVal = resolveRef(apStream.get("BBox"));
                Rectangle bbox;
                if (bboxVal instanceof COSArray && ((COSArray) bboxVal).size() == 4) {
                    bbox = Rectangle.fromCOSArray((COSArray) bboxVal);
                } else {
                    bbox = annotRect;
                }

                if (bbox != null) {
                    double bboxW = bbox.getWidth();
                    double bboxH = bbox.getHeight();
                    if (bboxW != 0 && bboxH != 0) {
                        double sx = annotRect.getWidth() / bboxW;
                        double sy = annotRect.getHeight() / bboxH;
                        double tx = annotRect.getLLX() - bbox.getLLX() * sx;
                        double ty = annotRect.getLLY() - bbox.getLLY() * sy;

                        byte[] apContent = apStream.getDecodedData();
                        StringBuilder sb = new StringBuilder();
                        sb.append("\nq\n");
                        sb.append(sx).append(" 0 0 ").append(sy).append(' ')
                          .append(tx).append(' ').append(ty).append(" cm\n");
                        sb.append(new String(apContent, java.nio.charset.StandardCharsets.US_ASCII));
                        sb.append("\nQ\n");
                        page.appendToContentStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    }
                }
            }
        }

        // Remove this annotation from the page's /Annots array
        page.getAnnotations().delete(this);
    }

    /**
     * Returns the opacity of the annotation (0.0 = fully transparent, 1.0 = fully opaque).
     * <p>
     * For markup annotations, the /CA entry is used. For non-markup annotations,
     * this returns 1.0 by default.
     * </p>
     *
     * @return the opacity value (0.0 to 1.0)
     */
    public double getOpacity() {
        COSBase ca = dict.get("CA");
        if (ca instanceof COSFloat) return ((COSFloat) ca).doubleValue();
        if (ca instanceof COSInteger) return (double) ((COSInteger) ca).intValue();
        return 1.0;
    }

    /**
     * Sets the opacity of the annotation (0.0 = fully transparent, 1.0 = fully opaque).
     *
     * @param opacity the opacity value (0.0 to 1.0)
     */
    public void setOpacity(double opacity) {
        dict.set(COSName.of("CA"), new COSFloat(opacity));
    }

    /**
     * Returns the Z-index (drawing order) of this annotation.
     * Annotations with higher Z-index are drawn on top.
     *
     * @return the Z-index
     */
    public int getZIndex() {
        return dict.getInt("ZIndex", 0);
    }

    /**
     * Sets the Z-index (drawing order) of this annotation.
     * Annotations with higher Z-index are drawn on top.
     * Use negative values to place behind page content.
     *
     * @param zIndex the Z-index value
     */
    public void setZIndex(int zIndex) {
        dict.set(COSName.of("ZIndex"), COSInteger.valueOf(zIndex));
    }

    /**
     * Returns the annotation action collection (/AA entry).
     * <p>
     * The additional-actions dictionary defines actions to be performed in response
     * to various trigger events (ISO 32000-1:2008, Section 12.6.3).
     * </p>
     *
     * @return the annotation action collection; never null
     */
    public AnnotationActionCollection getActions() {
        AnnotationActionCollection collection = new AnnotationActionCollection();
        try {
            // Primary action (/A) and its chained /Next actions (§12.6.3). A
            // sequence of actions is represented as a linked list through the
            // /Next entry; Aspose flattens that chain into the Actions list, so
            // an annotation with /A → URI(google) → /Next URI(yahoo) yields two
            // entries.
            COSBase a = resolveRef(dict.get("A"));
            if (a instanceof COSDictionary) {
                PdfAction action = PdfAction.fromDictionary((COSDictionary) a, null);
                while (action != null) {
                    collection.add(action);
                    action = action.getNext();
                }
            }
            // Additional actions (/AA): one action per trigger event
            // (ISO 32000-1:2008, Table 197/198 — /E, /X, /D, /U, /Fo, /Bl, ...).
            COSBase aa = resolveRef(dict.get("AA"));
            if (aa instanceof COSDictionary) {
                COSDictionary aaDict = (COSDictionary) aa;
                for (COSName key : aaDict.keySet()) {
                    COSBase entry = resolveRef(aaDict.get(key));
                    if (entry instanceof COSDictionary) {
                        PdfAction action = PdfAction.fromDictionary((COSDictionary) entry, null);
                        if (action != null) {
                            collection.add(action);
                            // /E (enter), /X (exit), /D (mouse-down) get exposed
                            // through the typed accessors as well.
                            String k = key.getName();
                            if ("E".equals(k)) collection.setOnEnter(action);
                            else if ("X".equals(k)) collection.setOnExit(action);
                            else if ("D".equals(k)) collection.setOnPressMouseBtn(action);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.fine(() -> "Failed to read annotation actions: " + e.getMessage());
        }
        return collection;
    }

    /**
     * Returns the page this annotation belongs to.
     *
     * @return the parent page
     */
    public Page getPage() { return page; }

    /**
     * Sets the page this annotation belongs to.
     *
     * @param page the page
     */
    public void setPage(Page page) { this.page = page; }

    /**
     * Returns the underlying COS dictionary for this annotation.
     *
     * @return the COS dictionary
     */
    public COSDictionary getCOSDictionary() { return dict; }

    /**
     * Factory method: creates a typed annotation from a COS dictionary based on its /Subtype.
     *
     * @param dict the COS dictionary representing the annotation
     * @param page the page the annotation belongs to
     * @return a typed Annotation subclass instance
     */
    public static Annotation fromDictionary(COSDictionary dict, Page page) {
        String subtype = dict.getNameAsString("Subtype");
        if (subtype == null) return new GenericAnnotation(dict, page);
        switch (subtype) {
            case "Text": return new TextAnnotation(dict, page);
            case "Link": return new LinkAnnotation(dict, page);
            case "FreeText": return new FreeTextAnnotation(dict, page);
            case "Line": return new LineAnnotation(dict, page);
            case "Square": return new SquareAnnotation(dict, page);
            case "Circle": return new CircleAnnotation(dict, page);
            case "Polygon": return new PolygonAnnotation(dict, page);
            case "PolyLine": return new PolylineAnnotation(dict, page);
            case "Highlight": return new HighlightAnnotation(dict, page);
            case "Underline": return new UnderlineAnnotation(dict, page);
            case "Squiggly": return new SquigglyAnnotation(dict, page);
            case "StrikeOut": return new StrikeOutAnnotation(dict, page);
            case "Stamp": return new StampAnnotation(dict, page);
            case "Ink": return new InkAnnotation(dict, page);
            case "Popup": return new PopupAnnotation(dict, page);
            case "FileAttachment": return new FileAttachmentAnnotation(dict, page);
            case "Widget": return new WidgetAnnotation(dict, page);
            case "Redact": return new RedactionAnnotation(dict, page);
            case "Watermark": return new WatermarkAnnotation(dict, page);
            case "Screen": return new ScreenAnnotation(dict, page);
            case "Caret": return new CaretAnnotation(dict, page);
            default: return new GenericAnnotation(dict, page);
        }
    }
}
