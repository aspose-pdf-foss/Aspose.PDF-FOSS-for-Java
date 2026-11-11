package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;
import java.util.*;

/**
 * Radio button group (/FT /Btn, radio flag) (ISO 32000-1:2008, §12.7.4.2.3).
 * <p>
 * A radio button field contains multiple mutually exclusive options stored
 * as /Kids entries. Each kid is a {@link RadioButtonOptionField}.
 * </p>
 */
public class RadioButtonField extends Field {

    private static final int RADIO_FLAG = 1 << 15;
    private static final int NO_TOGGLE_TO_OFF_FLAG = 1 << 14;

    /** Cached list of child radio button options. */
    private List<RadioButtonOptionField> options;

    /**
     * Constructs a radio button field from an existing PDF dictionary.
     *
     * @param dict     the PDF dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public RadioButtonField(PdfDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new radio button field on the given page.
     *
     * @param page the page
     */
    public RadioButtonField(Page page) {
        super(new PdfDictionary(), page, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Btn"));
        // Set radio flag (bit 16)
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(RADIO_FLAG));
    }

    /**
     * Constructs a new <em>detached</em> radio button field (no owning page).
     * Page wiring is deferred to {@link Form#add(Field, Page, Rectangle)}.
     */
    public RadioButtonField() {
        super(new PdfDictionary(), "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Btn"));
        // Set radio flag (bit 16)
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(RADIO_FLAG));
    }

    /**
     * Convenience overload — creates a radio-button group on {@code page} and
     * uses {@code rect} as the field-level {@code /Rect} (most viewers ignore
     * it for radio groups but Aspose-compat ports often set it).
     *
     * @param page the page
     * @param rect the optional rectangle for the field widget
     */
    public RadioButtonField(Page page, Rectangle rect) {
        this(page);
        if (rect != null) {
            setRectLenient(rect);
        }
    }

    /**
     * Attaches a {@link RadioButtonOptionField} to this radio group.
     *
     * <p>Adds the option's dictionary to {@code /Kids}, links its {@code /Parent}
     * back to this field, and — if the option's {@code /AP/N} is missing or
     * contains {@link org.aspose.pdf.engine.pdfobjects.PdfNull} placeholders — calls
     * {@link RadioButtonOptionField#regenerateAppearance()} so that the
     * option renders correctly in viewers that don't honour
     * {@code /NeedAppearances}.</p>
     *
     * @param option the option to attach (must not be null)
     */
    public void add(RadioButtonOptionField option) {
        if (option == null) {
            throw new IllegalArgumentException("option must not be null");
        }
        PdfBase kids = dict.get("Kids");
        PdfArray kidsArray;
        if (kids instanceof PdfArray) {
            kidsArray = (PdfArray) kids;
        } else {
            kidsArray = new PdfArray();
            dict.set(PdfName.of("Kids"), kidsArray);
        }
        kidsArray.add(option.getPdfDictionary());
        option.getPdfDictionary().set(PdfName.of("Parent"), dict);
        if (FieldAppearanceBuilder.isAppearanceIncomplete(option.getPdfDictionary())) {
            option.regenerateAppearance();
        }
        // Invalidate cached options view
        options = null;
    }

    /**
     * Adds a radio button option with the given value and rectangle.
     *
     * @param optionValue the export value for this option
     * @param rect        the rectangle for this option's widget
     */
    public void addOption(String optionValue, Rectangle rect) {
        PdfBase kids = dict.get("Kids");
        PdfArray kidsArray;
        if (kids instanceof PdfArray) {
            kidsArray = (PdfArray) kids;
        } else {
            kidsArray = new PdfArray();
            dict.set(PdfName.of("Kids"), kidsArray);
        }
        PdfDictionary kidDict = new PdfDictionary();
        kidDict.set(PdfName.of("Type"), PdfName.of("Annot"));
        kidDict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        if (rect != null) {
            kidDict.set(PdfName.of("Rect"), rect.toPdfArray());
        }
        kidDict.set(PdfName.of("AS"), PdfName.of("Off"));
        // Build /AP /N with real on/off appearance streams. An earlier version
        // emitted /BBox [0 0 0 0] /Length 0 placeholders here; Adobe Reader
        // rejects /Length 0 streams under AES encryption (no IV) and refuses
        // to open the document. Real appearances use the option rectangle for
        // /BBox and a Zapf-Dingbats glyph for the on-state.
        PdfStream onStream  = FieldAppearanceBuilder.buildRadioAppearance(rect, true,  BoxStyle.Circle);
        PdfStream offStream = FieldAppearanceBuilder.buildRadioAppearance(rect, false, BoxStyle.Circle);
        PdfDictionary apN = new PdfDictionary();
        apN.set(PdfName.of(optionValue), onStream);
        apN.set(PdfName.of("Off"), offStream);
        PdfDictionary ap = new PdfDictionary();
        ap.set(PdfName.of("N"), apN);
        kidDict.set(PdfName.of("AP"), ap);
        kidsArray.add(kidDict);
        // Invalidate cached options
        options = null;
    }

    /**
     * Returns the 0-based index of the currently selected option,
     * or -1 if none is selected.
     *
     * @return the selected index, or -1
     */
    public int getSelected() {
        String v = getValue();
        if (v == null || "Off".equals(v)) return -1;
        List<RadioButtonOptionField> opts = getOptions();
        for (int i = 0; i < opts.size(); i++) {
            if (v.equals(opts.get(i).getOptionValue())) return i;
        }
        return -1;
    }

    /**
     * Selects the option at the given 0-based index.
     * Updates the field value (/V) and each kid's /AS entry.
     *
     * @param index the 0-based index to select
     */
    public void setSelected(int index) {
        List<RadioButtonOptionField> opts = getOptions();
        if (index >= 0 && index < opts.size()) {
            String val = opts.get(index).getOptionValue();
            if (val == null) {
                return;
            }
            setValue(val);
            for (int i = 0; i < opts.size(); i++) {
                String state = (i == index) ? val : "Off";
                opts.get(i).getPdfDictionary().set(
                        PdfName.of("AS"),
                        PdfName.of(state));
            }
        }
    }

    /**
     * Returns whether the NoToggleToOff flag is set.
     *
     * @return true if at least one option must remain selected
     */
    public boolean isNoToggleToOff() {
        return (getFieldFlags() & NO_TOGGLE_TO_OFF_FLAG) != 0;
    }

    /**
     * Sets whether the NoToggleToOff flag is enabled.
     *
     * @param value true to prevent clearing the current selection
     */
    public void setNoToggleToOff(boolean value) {
        int flags = getFieldFlags() | RADIO_FLAG;
        flags = value ? (flags | NO_TOGGLE_TO_OFF_FLAG) : (flags & ~NO_TOGGLE_TO_OFF_FLAG);
        setFieldFlags(flags);
    }

    /**
     * Returns the value of the currently-selected option, or an empty string
     * when nothing is selected. The "value" of an option is its export name
     * (the appearance-state key on the kid widget's /AP/N dictionary, also
     * the value passed to {@link #addOption(String, Rectangle)}).
     *
     * <p>Overrides {@link Field#getValue()} so that radio-button consumers see
     * the option export value (Aspose semantics) regardless of how the field's
     * {@code /V} is encoded (PdfName per spec, or PdfString from older saves).</p>
     *
     * @return the selected option's export value, or empty string if none
     */
    @Override
    public String getValue() {
        String v = super.getValue();
        if (v == null || "Off".equals(v)) return "";
        return v;
    }

    /**
     * Selects the option whose export value matches the given string. If no
     * option matches, the selection is cleared.
     *
     * <p>Writes {@code /V} as a PdfName (per ISO 32000-1:2008 §12.7.4.2.3) and
     * updates each kid's {@code /AS} so that exactly one option shows as "on".</p>
     *
     * @param value the option value to select, or null/empty to clear
     */
    @Override
    public void setValue(String value) {
        if (value == null || value.isEmpty()) {
            dict.set(PdfName.of("V"), PdfName.of("Off"));
            List<RadioButtonOptionField> opts = getOptions();
            for (RadioButtonOptionField opt : opts) {
                opt.getPdfDictionary().set(PdfName.of("AS"), PdfName.of("Off"));
            }
            return;
        }
        List<RadioButtonOptionField> opts = getOptions();
        for (int i = 0; i < opts.size(); i++) {
            if (value.equals(opts.get(i).getOptionValue())) {
                dict.set(PdfName.of("V"), PdfName.of(value));
                for (int j = 0; j < opts.size(); j++) {
                    String state = (j == i) ? value : "Off";
                    opts.get(j).getPdfDictionary().set(PdfName.of("AS"), PdfName.of(state));
                }
                return;
            }
        }
        // No match — clear selection
        dict.set(PdfName.of("V"), PdfName.of("Off"));
        for (RadioButtonOptionField opt : opts) {
            opt.getPdfDictionary().set(PdfName.of("AS"), PdfName.of("Off"));
        }
    }

    /**
     * Returns the list of radio button options from the /Kids array.
     *
     * @return the list of options (possibly empty, never null)
     */
    public List<RadioButtonOptionField> getOptions() {
        if (options != null) return options;
        options = new ArrayList<>();
        PdfBase kids = dict.get("Kids");
        if (kids instanceof PdfObjectReference) {
            try {
                kids = ((PdfObjectReference) kids).dereference();
            } catch (Exception e) {
                kids = null;
            }
        }
        if (kids instanceof PdfArray) {
            PdfArray arr = (PdfArray) kids;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase kid = arr.get(i);
                if (kid instanceof PdfObjectReference) {
                    try {
                        kid = ((PdfObjectReference) kid).dereference();
                    } catch (Exception e) {
                        continue;
                    }
                }
                if (kid instanceof PdfDictionary) {
                    options.add(new RadioButtonOptionField((PdfDictionary) kid, page));
                }
            }
        }
        return options;
    }
}
