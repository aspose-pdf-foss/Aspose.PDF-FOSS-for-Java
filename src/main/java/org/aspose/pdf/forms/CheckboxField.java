package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Checkbox field (/FT /Btn) (ISO 32000-1:2008, §12.7.4.2.3).
 * <p>
 * A checkbox is a button field that is neither a radio button (bit 16)
 * nor a push button (bit 17). Its value is either "Off" or a custom
 * on-state name derived from the appearance dictionary.
 * </p>
 */
public class CheckboxField extends Field {

    private BoxStyle style = BoxStyle.Check;

    /**
     * Constructs a checkbox field from an existing PDF dictionary.
     *
     * @param dict     the PDF dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public CheckboxField(PdfDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new empty checkbox field.
     * The field must be added to a form via {@code Form.add(field)} or
     * {@code Form.add(field, pageNumber)}.
     *
     * <p>The {@code /AP/N} entry is created as an empty dictionary; concrete
     * appearance streams for the {@code Yes} and {@code Off} states are
     * generated on the next {@link #regenerateAppearance()} call (triggered
     * automatically by {@link #CheckboxField(Page, Rectangle)} or
     * {@link #setExportValue(String)} / {@link #setStyle(BoxStyle)}).</p>
     */
    public CheckboxField() {
        super(new PdfDictionary(), null, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Btn"));
        // Reserve /AP/N as a dictionary so getAllowedStates / state discovery
        // works even before regenerateAppearance() runs. Empty so the next
        // regenerate cleanly populates it.
        PdfDictionary apN = new PdfDictionary();
        PdfDictionary ap = new PdfDictionary();
        ap.set(PdfName.of("N"), apN);
        dict.set(PdfName.of("AP"), ap);
    }

    /**
     * Constructs a new checkbox field on the specified page with the given rectangle.
     * <p>Automatically generates Form-XObject {@code /AP/N/Yes} and {@code /AP/N/Off}
     * appearance streams via {@link FieldAppearanceBuilder} (F-10 fix).</p>
     *
     * @param page the page this checkbox belongs to
     * @param rect the rectangle defining the checkbox position and size
     */
    public CheckboxField(Page page, Rectangle rect) {
        this();
        if (page != null) {
            this.page = page;
        }
        if (rect != null) {
            setRectLenient(rect);
        }
        regenerateAppearance();
    }

    /**
     * Rebuilds the {@code /AP/N/Yes} and {@code /AP/N/Off} appearance streams
     * from the current rectangle, style and export-value name.
     *
     * <p>Idempotent: safe to call after any property change. No-op when the
     * widget has no {@code /Rect} set yet.</p>
     */
    @Override
    protected void regenerateAppearanceIfSupported() {
        regenerateAppearance();
    }

    public void regenerateAppearance() {
        Rectangle r = getRect();
        if (r == null) return;
        String onState = getOnValue();
        PdfStream onStream = FieldAppearanceBuilder.buildCheckboxAppearance(r, true, style);
        PdfStream offStream = FieldAppearanceBuilder.buildCheckboxAppearance(r, false, style);
        FieldAppearanceBuilder.installAppearance(dict, onStream, onState, offStream);
    }

    /**
     * Returns the check mark style of this checkbox.
     *
     * @return the box style
     */
    public BoxStyle getStyle() { return style; }

    /**
     * Sets the check mark style of this checkbox and regenerates the
     * {@code /AP/N} appearance streams so the new glyph is reflected.
     *
     * @param style the box style
     */
    public void setStyle(BoxStyle style) {
        this.style = style;
        regenerateAppearance();
    }

    /**
     * Sets the width of this checkbox by updating the /Rect entry.
     *
     * @param width the width in points
     */
    public void setWidth(double width) {
        Rectangle r = getRect();
        double llx = r != null ? r.getLLX() : 0;
        double lly = r != null ? r.getLLY() : 0;
        double ury = r != null ? r.getURY() : 0;
        setRectLenient(new Rectangle(llx, lly, llx + width, ury));
    }

    /**
     * Sets the height of this checkbox by updating the /Rect entry.
     *
     * @param height the height in points
     */
    public void setHeight(double height) {
        Rectangle r = getRect();
        double llx = r != null ? r.getLLX() : 0;
        double lly = r != null ? r.getLLY() : 0;
        double urx = r != null ? r.getURX() : 0;
        setRectLenient(new Rectangle(llx, lly, urx, lly + height));
    }

    /**
     * Returns whether this checkbox is checked.
     * <p>
     * Any value except "Off" or null is considered checked.
     * </p>
     *
     * @return true if checked
     */
    public boolean isChecked() {
        String v = getValue();
        return v != null && !"Off".equals(v);
    }

    /**
     * Sets the checked state of this checkbox.
     *
     * @param checked true to check, false to uncheck
     */
    public void setChecked(boolean checked) {
        String onValue = getOnValue();
        if (checked) {
            setValue(onValue);
            dict.set(PdfName.of("AS"), PdfName.of(onValue));
        } else {
            setValue("Off");
            dict.set(PdfName.of("AS"), PdfName.of("Off"));
        }
    }

    /**
     * Returns the export value (on-state name) of this checkbox.
     *
     * @return the export value
     */
    public String getExportValue() {
        return getOnValue();
    }

    /**
     * Sets the export value (on-state name) of this checkbox.
     *
     * @param exportValue the export value to use when checked
     */
    public void setExportValue(String exportValue) {
        if (exportValue == null || exportValue.isEmpty()) {
            return;
        }
        PdfDictionary ap = ensureAppearanceDictionary();
        PdfDictionary apN = ensureNormalAppearanceDictionary(ap);
        String oldOnValue = getOnValue();
        PdfBase oldState = apN.get(oldOnValue);
        apN.remove(PdfName.of(oldOnValue));
        apN.set(PdfName.of(exportValue), oldState != null ? oldState : PdfNull.INSTANCE);
        if (oldOnValue.equals(getValue())) {
            setValue(exportValue);
            dict.set(PdfName.of("AS"), PdfName.of(exportValue));
        }
        // F-10 fix: regenerate streams so the on-state name matches the rendered glyph.
        regenerateAppearance();
    }

    /**
     * Adds another allowed on-state for Aspose-compatible checkbox workflows.
     *
     * @param optionValue the additional allowed state
     */
    public void addOption(String optionValue) {
        if (optionValue == null || optionValue.isEmpty()) {
            return;
        }
        PdfDictionary ap = ensureAppearanceDictionary();
        PdfDictionary apN = ensureNormalAppearanceDictionary(ap);
        apN.set(PdfName.of(optionValue), PdfNull.INSTANCE);
    }

    /**
     * Returns all allowed states, including "Off" and any on-state names
     * present in the normal appearance dictionary.
     *
     * @return the list of allowed states
     */
    public List<String> getAllowedStates() {
        List<String> states = new ArrayList<>();
        PdfBase ap = dict.get("AP");
        if (ap instanceof PdfDictionary) {
            PdfBase n = ((PdfDictionary) ap).get("N");
            if (n instanceof PdfDictionary) {
                for (PdfName key : ((PdfDictionary) n).keySet()) {
                    states.add(key.getName());
                }
            }
        }
        if (!states.contains("Off")) {
            states.add(0, "Off");
        }
        return states;
    }

    /**
     * Determines the on-state name from the /AP/N dictionary.
     * Falls back to "Yes" if no appearance states are found.
     */
    private String getOnValue() {
        PdfBase ap = dict.get("AP");
        if (ap instanceof PdfDictionary) {
            PdfBase n = ((PdfDictionary) ap).get("N");
            if (n instanceof PdfDictionary) {
                for (PdfName key : ((PdfDictionary) n).keySet()) {
                    if (!"Off".equals(key.getName())) return key.getName();
                }
            }
        }
        return "Yes";
    }

    @Override
    public void setValue(String value) {
        if (value != null && !"Off".equals(value)) {
            dict.set(PdfName.of("V"), PdfName.of(value));
            dict.set(PdfName.of("AS"), PdfName.of(value));
        } else {
            dict.set(PdfName.of("V"), PdfName.of("Off"));
            dict.set(PdfName.of("AS"), PdfName.of("Off"));
        }
    }

    private PdfDictionary ensureAppearanceDictionary() {
        PdfBase ap = dict.get("AP");
        if (ap instanceof PdfDictionary) {
            return (PdfDictionary) ap;
        }
        PdfDictionary result = new PdfDictionary();
        dict.set(PdfName.of("AP"), result);
        return result;
    }

    private PdfDictionary ensureNormalAppearanceDictionary(PdfDictionary ap) {
        PdfBase n = ap.get("N");
        if (n instanceof PdfDictionary && !(n instanceof PdfStream)) {
            return (PdfDictionary) n;
        }
        PdfDictionary result = new PdfDictionary();
        ap.set(PdfName.of("N"), result);
        return result;
    }
}
