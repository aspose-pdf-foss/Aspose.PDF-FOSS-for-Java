package org.aspose.pdf.sdm;

import java.util.ArrayList;
import java.util.List;

/**
 * Interactive form field block (IR spec §1.3 extension): a first-class
 * projection of an AcroForm widget so every converter built on the IR can see
 * that a location is an input, not just an opaque annotation.
 * <p>
 * Geometry (page, rect) lives in the PGM {@code FIELD} box sharing this node's
 * id; the on-page footprint is mirrored in the {@code display-width} /
 * {@code display-height} attributes (points) like {@link Figure} does, so
 * writers without PGM access can still size the control.
 * </p>
 */
public final class FormField extends SdmBlock {

    /** The interaction kind, derived from /FT + /Ff (ISO 32000-1 §12.7.4). */
    public enum Kind {
        /** Single- or multi-line text input (/FT Tx). */
        TEXT,
        /** Checkbox (/FT Btn, neither pushbutton nor radio). */
        CHECKBOX,
        /** Radio button (/FT Btn, radio flag). */
        RADIO,
        /** Drop-down choice (/FT Ch, combo flag). */
        COMBOBOX,
        /** Scrollable list choice (/FT Ch, no combo flag). */
        LISTBOX,
        /** Pushbutton (/FT Btn, pushbutton flag). */
        BUTTON,
        /** Digital signature field (/FT Sig). */
        SIGNATURE
    }

    private final Kind kind;
    private String name;
    private String value;
    private boolean checked;
    private String exportValue;
    private final List<String> options = new ArrayList<>();
    private boolean multiline;
    private boolean readOnly;
    private Integer maxLen;

    /**
     * Creates a form-field node.
     *
     * @param kind      the interaction kind; must not be null
     * @param sourceRef provenance of the widget annotation (may be null for
     *                  session-created fields)
     */
    public FormField(Kind kind, SourceRef sourceRef) {
        super(SdmNodeType.FORM_FIELD);
        if (kind == null) {
            throw new IllegalArgumentException("FormField requires a kind");
        }
        this.kind = kind;
        setSourceRef(sourceRef);
    }

    /**
     * Returns the interaction kind.
     *
     * @return the kind, never null
     */
    public Kind getKind() {
        return kind;
    }

    /**
     * Returns the fully-qualified field name (parent chain joined with dots).
     *
     * @return the name, or null
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the fully-qualified field name.
     *
     * @param name the name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Returns the current value: the text for TEXT fields, the selected export
     * value for choice fields, the caption for BUTTON fields.
     *
     * @return the value, or null
     */
    public String getValue() {
        return value;
    }

    /**
     * Sets the current value.
     *
     * @param value the value
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Returns whether a CHECKBOX/RADIO field is in its on state.
     *
     * @return true when checked
     */
    public boolean isChecked() {
        return checked;
    }

    /**
     * Sets the checked state.
     *
     * @param checked the state
     */
    public void setChecked(boolean checked) {
        this.checked = checked;
    }

    /**
     * Returns the on-state export value of a CHECKBOX/RADIO (the non-Off
     * appearance-state name).
     *
     * @return the export value, or null
     */
    public String getExportValue() {
        return exportValue;
    }

    /**
     * Sets the on-state export value.
     *
     * @param exportValue the export value
     */
    public void setExportValue(String exportValue) {
        this.exportValue = exportValue;
    }

    /**
     * Returns the mutable choice options (COMBOBOX/LISTBOX display values).
     *
     * @return the options list
     */
    public List<String> getOptions() {
        return options;
    }

    /**
     * Returns whether a TEXT field is multi-line.
     *
     * @return true when multi-line
     */
    public boolean isMultiline() {
        return multiline;
    }

    /**
     * Sets the multi-line flag.
     *
     * @param multiline the flag
     */
    public void setMultiline(boolean multiline) {
        this.multiline = multiline;
    }

    /**
     * Returns whether the field is read-only (/Ff bit 1).
     *
     * @return true when read-only
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Sets the read-only flag.
     *
     * @param readOnly the flag
     */
    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * Returns the maximum text length of a TEXT field (/MaxLen).
     *
     * @return the limit, or null when unlimited
     */
    public Integer getMaxLen() {
        return maxLen;
    }

    /**
     * Sets the maximum text length.
     *
     * @param maxLen the limit, or null
     */
    public void setMaxLen(Integer maxLen) {
        this.maxLen = maxLen;
    }
}
