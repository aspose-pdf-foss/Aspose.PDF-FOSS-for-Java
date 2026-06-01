package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * List box field (/FT /Ch, no combo flag) (ISO 32000-1:2008, §12.7.4.4).
 * <p>
 * A list box displays a scrollable list of options from which one (or more,
 * if multi-select is enabled) can be selected.
 * </p>
 */
public class ListBoxField extends Field {

    /**
     * Constructs a list box field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public ListBoxField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new list box field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    public ListBoxField(Page page, Rectangle rect) {
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Ch"));
        setRectLenient(rect);
        if (getDefaultAppearance() == null) {
            setDefaultAppearance("/Helv 12 Tf 0 g");
        }
    }

    /**
     * Returns the options (/Opt array) for this list box.
     *
     * @return the option collection (never null)
     */
    public OptionCollection getOptions() {
        COSBase opt = dict.get("Opt");
        return new OptionCollection(opt instanceof COSArray ? (COSArray) opt : new COSArray());
    }

    /**
     * Returns whether multi-select is enabled (/Ff bit 22).
     *
     * @return true if multiple options can be selected
     */
    public boolean isMultiSelect() {
        return (getFieldFlags() & (1 << 21)) != 0;
    }

    /**
     * Returns the currently selected value.
     *
     * @return the selected value, or null
     */
    public String getSelected() {
        return getValue();
    }

    /**
     * Sets the selected value by string.
     *
     * @param value the value to select
     */
    public void setSelected(String value) {
        setValue(value);
    }

    /**
     * Sets the selected option by 1-based index.
     *
     * @param index 1-based index of the option to select
     */
    public void setSelected(int index) {
        OptionCollection opts = getOptions();
        if (index >= 1 && index <= opts.size()) {
            setValue(opts.get(index - 1).getValue());
        }
    }

    /**
     * Adds an option to this list box (/Opt array).
     *
     * @param value the option value to add
     */
    public void addOption(String value) {
        COSBase opt = dict.get("Opt");
        COSArray arr;
        if (opt instanceof COSArray) {
            arr = (COSArray) opt;
        } else {
            arr = new COSArray();
            dict.set(COSName.of("Opt"), arr);
        }
        arr.add(new COSString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /**
     * Returns the top index (/TI entry) — the index of the first visible option.
     *
     * @return the top index, or 0 if not set
     */
    public int getTopIndex() {
        return dict.getInt("TI", 0);
    }
}
