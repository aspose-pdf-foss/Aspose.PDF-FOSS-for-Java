package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;

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
     * Constructs a push button field from an existing PDF dictionary.
     *
     * @param dict     the PDF dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public ButtonField(PdfDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new push button field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    /**
     * Constructs a new <em>detached</em> push button field (no owning page).
     * Page wiring is deferred to {@link Form#add(Field, Page, Rectangle)}.
     */
    public ButtonField() {
        super(new PdfDictionary(), "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Btn"));
        // Set push button flag (bit 17)
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(1 << 16));
    }

    public ButtonField(Page page, Rectangle rect) {
        super(new PdfDictionary(), page, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Btn"));
        // Set push button flag (bit 17)
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(1 << 16));
        setRectLenient(rect);
    }

    /**
     * Sets the normal caption (/CA in the /MK dictionary).
     *
     * @param caption the caption string
     */
    public void setNormalCaption(String caption) {
        PdfBase mk = dict.get("MK");
        PdfDictionary mkDict;
        if (mk instanceof PdfDictionary) {
            mkDict = (PdfDictionary) mk;
        } else {
            mkDict = new PdfDictionary();
            dict.set(PdfName.of("MK"), mkDict);
        }
        mkDict.set(PdfName.of("CA"), new PdfString(caption.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Returns the normal caption (/CA in the /MK dictionary).
     *
     * @return the caption string, or null
     */
    public String getNormalCaption() {
        PdfBase mk = dict.get("MK");
        if (mk instanceof PdfDictionary) {
            PdfBase ca = ((PdfDictionary) mk).get("CA");
            if (ca instanceof PdfString) return ((PdfString) ca).getString();
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
        PdfBase a = dict.get("A");
        if (a instanceof PdfObjectReference) {
            try {
                a = ((PdfObjectReference) a).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return (a instanceof PdfDictionary) ? PdfAction.fromDictionary((PdfDictionary) a, null) : null;
    }

    /**
     * Sets this push button's activation action ({@code /A}, fired on mouse-up) to a JavaScript action
     * running {@code script} (ISO 32000-1:2008 §12.6.4.16). Used by the XFA→AcroForm converter to wire
     * a {@code +}/{@code -} control's original XFA click script onto the converted button.
     *
     * @param script the JavaScript source (ignored when {@code null}/blank)
     */
    public void setOnClickJavaScript(String script) {
        if (script == null || script.trim().isEmpty()) {
            return;
        }
        JavaScriptAction action = new JavaScriptAction(script);
        dict.set(PdfName.of("A"), action.getPdfDictionary());
    }
}
