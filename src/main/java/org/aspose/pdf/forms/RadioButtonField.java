package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;
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
     * Constructs a radio button field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public RadioButtonField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new radio button field on the given page.
     *
     * @param page the page
     */
    public RadioButtonField(Page page) {
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Btn"));
        // Set radio flag (bit 16)
        dict.set(COSName.of("Ff"), COSInteger.valueOf(RADIO_FLAG));
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
     * contains {@link org.aspose.pdf.engine.cos.COSNull} placeholders — calls
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
        COSBase kids = dict.get("Kids");
        COSArray kidsArray;
        if (kids instanceof COSArray) {
            kidsArray = (COSArray) kids;
        } else {
            kidsArray = new COSArray();
            dict.set(COSName.of("Kids"), kidsArray);
        }
        kidsArray.add(option.getCOSDictionary());
        option.getCOSDictionary().set(COSName.of("Parent"), dict);
        if (FieldAppearanceBuilder.isAppearanceIncomplete(option.getCOSDictionary())) {
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
        COSBase kids = dict.get("Kids");
        COSArray kidsArray;
        if (kids instanceof COSArray) {
            kidsArray = (COSArray) kids;
        } else {
            kidsArray = new COSArray();
            dict.set(COSName.of("Kids"), kidsArray);
        }
        COSDictionary kidDict = new COSDictionary();
        kidDict.set(COSName.of("Type"), COSName.of("Annot"));
        kidDict.set(COSName.of("Subtype"), COSName.of("Widget"));
        if (rect != null) {
            kidDict.set(COSName.of("Rect"), rect.toCOSArray());
        }
        kidDict.set(COSName.of("AS"), COSName.of("Off"));
        // Build /AP /N with real on/off appearance streams. An earlier version
        // emitted /BBox [0 0 0 0] /Length 0 placeholders here; Adobe Reader
        // rejects /Length 0 streams under AES encryption (no IV) and refuses
        // to open the document. Real appearances use the option rectangle for
        // /BBox and a Zapf-Dingbats glyph for the on-state.
        COSStream onStream  = FieldAppearanceBuilder.buildRadioAppearance(rect, true,  BoxStyle.Circle);
        COSStream offStream = FieldAppearanceBuilder.buildRadioAppearance(rect, false, BoxStyle.Circle);
        COSDictionary apN = new COSDictionary();
        apN.set(COSName.of(optionValue), onStream);
        apN.set(COSName.of("Off"), offStream);
        COSDictionary ap = new COSDictionary();
        ap.set(COSName.of("N"), apN);
        kidDict.set(COSName.of("AP"), ap);
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
                opts.get(i).getCOSDictionary().set(
                        COSName.of("AS"),
                        COSName.of(state));
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
     * {@code /V} is encoded (COSName per spec, or COSString from older saves).</p>
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
     * <p>Writes {@code /V} as a COSName (per ISO 32000-1:2008 §12.7.4.2.3) and
     * updates each kid's {@code /AS} so that exactly one option shows as "on".</p>
     *
     * @param value the option value to select, or null/empty to clear
     */
    @Override
    public void setValue(String value) {
        if (value == null || value.isEmpty()) {
            dict.set(COSName.of("V"), COSName.of("Off"));
            List<RadioButtonOptionField> opts = getOptions();
            for (RadioButtonOptionField opt : opts) {
                opt.getCOSDictionary().set(COSName.of("AS"), COSName.of("Off"));
            }
            return;
        }
        List<RadioButtonOptionField> opts = getOptions();
        for (int i = 0; i < opts.size(); i++) {
            if (value.equals(opts.get(i).getOptionValue())) {
                dict.set(COSName.of("V"), COSName.of(value));
                for (int j = 0; j < opts.size(); j++) {
                    String state = (j == i) ? value : "Off";
                    opts.get(j).getCOSDictionary().set(COSName.of("AS"), COSName.of(state));
                }
                return;
            }
        }
        // No match — clear selection
        dict.set(COSName.of("V"), COSName.of("Off"));
        for (RadioButtonOptionField opt : opts) {
            opt.getCOSDictionary().set(COSName.of("AS"), COSName.of("Off"));
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
        COSBase kids = dict.get("Kids");
        if (kids instanceof COSObjectReference) {
            try {
                kids = ((COSObjectReference) kids).dereference();
            } catch (Exception e) {
                kids = null;
            }
        }
        if (kids instanceof COSArray) {
            COSArray arr = (COSArray) kids;
            for (int i = 0; i < arr.size(); i++) {
                COSBase kid = arr.get(i);
                if (kid instanceof COSObjectReference) {
                    try {
                        kid = ((COSObjectReference) kid).dereference();
                    } catch (Exception e) {
                        continue;
                    }
                }
                if (kid instanceof COSDictionary) {
                    options.add(new RadioButtonOptionField((COSDictionary) kid, page));
                }
            }
        }
        return options;
    }
}
