package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

import java.io.IOException;

/**
 * Push button field (/FT /Btn, push flag) (ISO 32000-1:2008, §12.7.4.2.2).
 * <p>
 * A push button does not retain a permanent value; instead it activates
 * an action (/A entry) when pressed.
 * </p>
 */
public class ButtonField extends Field {

    /**
     * Constructs a push button field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public ButtonField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new push button field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    public ButtonField(Page page, Rectangle rect) {
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Btn"));
        // Set push button flag (bit 17)
        dict.set(COSName.of("Ff"), COSInteger.valueOf(1 << 16));
        setRectLenient(rect);
    }

    /**
     * Sets the normal caption (/CA in the /MK dictionary).
     *
     * @param caption the caption string
     */
    public void setNormalCaption(String caption) {
        COSBase mk = dict.get("MK");
        COSDictionary mkDict;
        if (mk instanceof COSDictionary) {
            mkDict = (COSDictionary) mk;
        } else {
            mkDict = new COSDictionary();
            dict.set(COSName.of("MK"), mkDict);
        }
        mkDict.set(COSName.of("CA"), new COSString(caption.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Returns the normal caption (/CA in the /MK dictionary).
     *
     * @return the caption string, or null
     */
    public String getNormalCaption() {
        COSBase mk = dict.get("MK");
        if (mk instanceof COSDictionary) {
            COSBase ca = ((COSDictionary) mk).get("CA");
            if (ca instanceof COSString) return ((COSString) ca).getString();
        }
        return null;
    }

    /**
     * Returns the action (/A entry) associated with this push button.
     *
     * @return the action, or null if none
     * @throws IOException if parsing fails
     */
    public PdfAction getAction() throws IOException {
        COSBase a = dict.get("A");
        if (a instanceof COSObjectReference) {
            try {
                a = ((COSObjectReference) a).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return (a instanceof COSDictionary) ? PdfAction.fromDictionary((COSDictionary) a, null) : null;
    }
}
