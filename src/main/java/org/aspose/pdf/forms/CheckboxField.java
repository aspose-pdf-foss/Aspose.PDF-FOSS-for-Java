package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

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
     * Constructs a checkbox field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public CheckboxField(COSDictionary dict, Page page, String fullName) {
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
        super(new COSDictionary(), null, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Btn"));
        // Reserve /AP/N as a dictionary so getAllowedStates / state discovery
        // works even before regenerateAppearance() runs. Empty so the next
        // regenerate cleanly populates it.
        COSDictionary apN = new COSDictionary();
        COSDictionary ap = new COSDictionary();
        ap.set(COSName.of("N"), apN);
        dict.set(COSName.of("AP"), ap);
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
    public void regenerateAppearance() {
        Rectangle r = getRect();
        if (r == null) return;
        String onState = getOnValue();
        COSStream onStream = FieldAppearanceBuilder.buildCheckboxAppearance(r, true, style);
        COSStream offStream = FieldAppearanceBuilder.buildCheckboxAppearance(r, false, style);
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
            dict.set(COSName.of("AS"), COSName.of(onValue));
        } else {
            setValue("Off");
            dict.set(COSName.of("AS"), COSName.of("Off"));
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
        COSDictionary ap = ensureAppearanceDictionary();
        COSDictionary apN = ensureNormalAppearanceDictionary(ap);
        String oldOnValue = getOnValue();
        COSBase oldState = apN.get(oldOnValue);
        apN.remove(COSName.of(oldOnValue));
        apN.set(COSName.of(exportValue), oldState != null ? oldState : COSNull.INSTANCE);
        if (oldOnValue.equals(getValue())) {
            setValue(exportValue);
            dict.set(COSName.of("AS"), COSName.of(exportValue));
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
        COSDictionary ap = ensureAppearanceDictionary();
        COSDictionary apN = ensureNormalAppearanceDictionary(ap);
        apN.set(COSName.of(optionValue), COSNull.INSTANCE);
    }

    /**
     * Returns all allowed states, including "Off" and any on-state names
     * present in the normal appearance dictionary.
     *
     * @return the list of allowed states
     */
    public List<String> getAllowedStates() {
        List<String> states = new ArrayList<>();
        COSBase ap = dict.get("AP");
        if (ap instanceof COSDictionary) {
            COSBase n = ((COSDictionary) ap).get("N");
            if (n instanceof COSDictionary) {
                for (COSName key : ((COSDictionary) n).keySet()) {
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
        COSBase ap = dict.get("AP");
        if (ap instanceof COSDictionary) {
            COSBase n = ((COSDictionary) ap).get("N");
            if (n instanceof COSDictionary) {
                for (COSName key : ((COSDictionary) n).keySet()) {
                    if (!"Off".equals(key.getName())) return key.getName();
                }
            }
        }
        return "Yes";
    }

    @Override
    public void setValue(String value) {
        if (value != null && !"Off".equals(value)) {
            dict.set(COSName.of("V"), COSName.of(value));
            dict.set(COSName.of("AS"), COSName.of(value));
        } else {
            dict.set(COSName.of("V"), COSName.of("Off"));
            dict.set(COSName.of("AS"), COSName.of("Off"));
        }
    }

    private COSDictionary ensureAppearanceDictionary() {
        COSBase ap = dict.get("AP");
        if (ap instanceof COSDictionary) {
            return (COSDictionary) ap;
        }
        COSDictionary result = new COSDictionary();
        dict.set(COSName.of("AP"), result);
        return result;
    }

    private COSDictionary ensureNormalAppearanceDictionary(COSDictionary ap) {
        COSBase n = ap.get("N");
        if (n instanceof COSDictionary && !(n instanceof COSStream)) {
            return (COSDictionary) n;
        }
        COSDictionary result = new COSDictionary();
        ap.set(COSName.of("N"), result);
        return result;
    }
}
