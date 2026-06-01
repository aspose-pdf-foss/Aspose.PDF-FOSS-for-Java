package org.aspose.pdf.annotations;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * Watermark annotation (ISO 32000-1:2008, Section 12.5.6.22, /Subtype /Watermark).
 * <p>
 * A watermark annotation is used to represent graphics that are expected to be
 * printed at a fixed size and position on a page, regardless of the dimensions
 * of the printed page. It is typically used for paper-independent graphics
 * such as company logos or letterhead.
 * </p>
 */
public class WatermarkAnnotation extends MarkupAnnotation {

    /**
     * Constructs a watermark annotation from an existing COS dictionary.
     *
     * @param dict the COS dictionary backing this annotation
     * @param page the page this annotation belongs to
     */
    public WatermarkAnnotation(COSDictionary dict, Page page) {
        super(dict, page);
    }

    /**
     * Constructs a new watermark annotation with the given rectangle on the specified page.
     *
     * @param page the page this annotation belongs to
     * @param rect the annotation rectangle
     */
    public WatermarkAnnotation(Page page, Rectangle rect) {
        super(page, rect);
        dict.set(COSName.of("Subtype"), COSName.of("Watermark"));
    }

    /**
     * Returns the watermark text (alias for {@link #getContents()}).
     * Watermark text content is stored in the standard {@code /Contents} entry.
     *
     * @return the watermark text, or null
     */
    public String getText() {
        return getContents();
    }

    /**
     * Sets the watermark text (alias for {@link #setContents(String)}).
     * Stored in the standard {@code /Contents} entry.
     *
     * @param text the watermark text, or null to clear
     */
    public void setText(String text) {
        setContents(text);
    }

    /**
     * Returns the watermark opacity ({@code /CA}, 0.0 = fully transparent,
     * 1.0 = opaque). Defaults to 1.0 when the entry is absent.
     *
     * @return the constant-alpha value in [0, 1]
     */
    public double getOpacity() {
        COSBase v = dict.get("CA");
        if (v instanceof COSFloat) return ((COSFloat) v).doubleValue();
        if (v instanceof COSInteger) return ((COSInteger) v).longValue();
        return 1.0;
    }

    /**
     * Sets the watermark opacity ({@code /CA}). Values outside [0, 1] are not
     * validated — the writer will pass them through as written.
     *
     * @param opacity constant-alpha value (0.0–1.0 typical)
     */
    public void setOpacity(double opacity) {
        dict.set(COSName.of("CA"), new COSFloat(opacity));
    }

    /**
     * Returns the watermark rotation angle in degrees ({@code /Rotate}).
     * Defaults to 0 when the entry is absent.
     *
     * @return the rotation in degrees
     */
    public double getAngle() {
        COSBase v = dict.get("Rotate");
        if (v instanceof COSFloat) return ((COSFloat) v).doubleValue();
        if (v instanceof COSInteger) return ((COSInteger) v).longValue();
        return 0;
    }

    /**
     * Sets the watermark rotation angle in degrees.
     *
     * @param angle rotation in degrees
     */
    public void setAngle(double angle) {
        dict.set(COSName.of("Rotate"), new COSFloat(angle));
    }
}
