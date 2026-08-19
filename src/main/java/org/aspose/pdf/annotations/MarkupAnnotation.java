package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Abstract base for markup annotations (ISO 32000-1:2008, §12.5.6.2).
 * Adds author, subject, opacity, popup, rich text, creation date, replies.
 */
public abstract class MarkupAnnotation extends Annotation {

    /**
     * Constructs a markup annotation from an existing PDF dictionary.
     *
     * @param dict the PDF dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    protected MarkupAnnotation(PdfDictionary dict, Page page) { super(dict, page); }

    /**
     * Constructs a new markup annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    protected MarkupAnnotation(Page page, Rectangle rect) { super(page, rect); }

    /**
     * Returns the author or title of the annotation (/T entry).
     *
     * @return the title string, or null if not set
     */
    public String getTitle() {
        PdfBase t = dict.get("T");
        return (t instanceof PdfString) ? ((PdfString) t).getString() : null;
    }

    /**
     * Sets the author or title of the annotation (/T entry).
     *
     * @param title the title string, or null to remove
     */
    public void setTitle(String title) {
        if (title != null) dict.set(PdfName.of("T"), new PdfString(title));
        else dict.remove(PdfName.of("T"));
    }

    /**
     * Returns the opacity of the annotation (/CA entry, 0.0-1.0, default 1.0).
     *
     * @return the opacity value
     */
    public double getOpacity() {
        PdfBase ca = dict.get("CA");
        if (ca instanceof PdfFloat) return ((PdfFloat) ca).doubleValue();
        if (ca instanceof PdfInteger) return (double) ((PdfInteger) ca).intValue();
        return 1.0;
    }

    /**
     * Sets the opacity of the annotation (/CA entry, 0.0-1.0).
     *
     * @param opacity the opacity value
     */
    public void setOpacity(double opacity) { dict.set(PdfName.of("CA"), new PdfFloat(opacity)); }

    /**
     * Returns the subject of the annotation (/Subj entry).
     *
     * @return the subject string, or null if not set
     */
    public String getSubject() {
        PdfBase s = dict.get("Subj");
        return (s instanceof PdfString) ? ((PdfString) s).getString() : null;
    }

    /**
     * Sets the subject of the annotation (/Subj entry).
     *
     * @param subject the subject string, or null to remove
     */
    public void setSubject(String subject) {
        if (subject != null) dict.set(PdfName.of("Subj"), new PdfString(subject));
        else dict.remove(PdfName.of("Subj"));
    }

    /**
     * Returns the rich text content of the annotation (/RC entry, XHTML format).
     *
     * @return the rich text string, or null if not set
     */
    public String getRichText() {
        PdfBase rc = dict.get("RC");
        return (rc instanceof PdfString) ? ((PdfString) rc).getString() : null;
    }

    /**
     * Sets the rich text content of the annotation (/RC entry, XHTML format).
     *
     * @param richText the rich text string
     */
    public void setRichText(String richText) {
        if (richText != null) dict.set(PdfName.of("RC"), new PdfString(richText));
    }

    /**
     * Returns the creation date of the annotation (/CreationDate entry).
     *
     * @return the creation date string, or null if not set
     */
    public String getCreationDate() {
        PdfBase d = dict.get("CreationDate");
        return (d instanceof PdfString) ? ((PdfString) d).getString() : null;
    }

    /**
     * Sets the creation date of the annotation (/CreationDate entry).
     *
     * @param date the creation date string in PDF date format, or null to remove
     */
    public void setCreationDate(String date) {
        if (date != null) dict.set(PdfName.of("CreationDate"), new PdfString(date));
        else dict.remove(PdfName.of("CreationDate"));
    }

    /**
     * Returns the reply type of this annotation (/RT entry).
     * Values: "R" (reply) or "Group" (grouped).
     *
     * @return the reply type name, or null if not set
     */
    public String getReplyType() {
        PdfBase rt = dict.get("RT");
        if (rt instanceof PdfName) return ((PdfName) rt).getName();
        return null;
    }

    /**
     * Sets the reply type of this annotation (/RT entry).
     *
     * @param replyType "R" for reply, "Group" for grouped, or null to remove
     */
    public void setReplyType(String replyType) {
        if (replyType != null) dict.set(PdfName.of("RT"), PdfName.of(replyType));
        else dict.remove(PdfName.of("RT"));
    }

    /**
     * Returns the associated popup annotation (/Popup entry).
     *
     * @return the popup annotation, or null if not set
     */
    public PopupAnnotation getPopup() {
        PdfBase p = resolveRef(dict.get("Popup"));
        if (p instanceof PdfDictionary) return new PopupAnnotation((PdfDictionary) p, page);
        return null;
    }

    /**
     * Sets the associated popup annotation (/Popup entry).
     *
     * @param popup the popup annotation to associate
     */
    public void setPopup(PopupAnnotation popup) {
        if (popup != null) dict.set(PdfName.of("Popup"), popup.getPdfDictionary());
    }

    /**
     * Returns the annotation this one is in reply to (/IRT entry).
     *
     * @return the referenced annotation, or null if not set
     */
    public Annotation getInReplyTo() {
        PdfBase irt = resolveRef(dict.get("IRT"));
        if (irt instanceof PdfDictionary) return Annotation.fromDictionary((PdfDictionary) irt, page);
        return null;
    }

    /**
     * Sets the annotation this one is in reply to (/IRT entry).
     *
     * @param annotation the annotation being replied to
     */
    public void setInReplyTo(Annotation annotation) {
        if (annotation != null) dict.set(PdfName.of("IRT"), annotation.getPdfDictionary());
    }

    private PdfBase resolveRef(PdfBase val) {
        if (val instanceof PdfObjectReference) {
            try { return ((PdfObjectReference) val).dereference(); }
            catch (Exception e) { return null; }
        }
        return val;
    }

    /** Cached interior color (/IC); {@code null} means "not read / not set". */
    private Color interiorColor;

    /**
     * Returns the interior (fill) color of a shape markup annotation — the
     * {@code /IC} entry (ISO 32000-1 §12.5.6.8: Square/Circle/Polygon/PolyLine).
     * Markup annotations that carry no {@code /IC} return {@code null}.
     *
     * @return the interior color, or {@code null} if none
     */
    public Color getInteriorColor() {
        if (interiorColor != null) return interiorColor;
        PdfBase ic = dict.get("IC");
        if (ic instanceof PdfArray) {
            PdfArray arr = (PdfArray) ic;
            if (arr.size() == 3) {
                return Color.fromRgb(arr.getFloat(0, 0), arr.getFloat(1, 0), arr.getFloat(2, 0));
            }
        }
        return null;
    }

    /**
     * Sets the interior (fill) color — the {@code /IC} entry. {@code null} removes it.
     *
     * @param color the interior color, or {@code null} to clear
     */
    public void setInteriorColor(Color color) {
        this.interiorColor = color;
        if (color == null) {
            dict.remove(PdfName.of("IC"));
            return;
        }
        PdfArray ic = new PdfArray();
        ic.add(new PdfFloat(color.getR()));
        ic.add(new PdfFloat(color.getG()));
        ic.add(new PdfFloat(color.getB()));
        dict.set(PdfName.of("IC"), ic);
    }
}
