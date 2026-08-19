package org.aspose.pdf.sdm.reader;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.sdm.FormField;

/**
 * Resolves the interactive-field facts of a Widget annotation dictionary:
 * field type, fully-qualified name, value, flags, choice options. In PDF the
 * widget and the field may share one dictionary or the widget may be a /Kids
 * leaf of a parent field, with /FT, /Ff, /V, /Opt, /MaxLen inheritable through
 * the /Parent chain (ISO 32000-1 §12.7.3.1) — this helper walks that chain so
 * every consumer (SDM reader, HTML converters) sees the merged view.
 */
public final class WidgetFieldInfo {

    private static final Logger LOG = Logger.getLogger(WidgetFieldInfo.class.getName());

    /** Cap on /Parent hops — defends against reference cycles in broken files. */
    private static final int MAX_PARENT_DEPTH = 32;

    private final FormField.Kind kind;
    private final String name;
    private final String value;
    private final boolean checked;
    private final String exportValue;
    private final List<String> options;
    private final boolean multiline;
    private final boolean readOnly;
    private final Integer maxLen;

    private WidgetFieldInfo(FormField.Kind kind, String name, String value, boolean checked,
                            String exportValue, List<String> options, boolean multiline,
                            boolean readOnly, Integer maxLen) {
        this.kind = kind;
        this.name = name;
        this.value = value;
        this.checked = checked;
        this.exportValue = exportValue;
        this.options = options;
        this.multiline = multiline;
        this.readOnly = readOnly;
        this.maxLen = maxLen;
    }

    /**
     * Resolves a widget annotation dictionary into field facts.
     *
     * @param widget the annotation dictionary (subtype Widget)
     * @return the resolved info, or null when no field type can be determined
     *         (not an interactive field)
     */
    public static WidgetFieldInfo resolve(PdfDictionary widget) {
        if (widget == null) {
            return null;
        }
        String ft = inheritedName(widget, "FT");
        if (ft == null) {
            return null;
        }
        int ff = inheritedInt(widget, "Ff", 0);
        boolean readOnly = (ff & 1) != 0;
        FormField.Kind kind;
        boolean multiline = false;
        switch (ft) {
            case "Tx":
                kind = FormField.Kind.TEXT;
                multiline = (ff & (1 << 12)) != 0; // §12.7.4.3 Multiline
                break;
            case "Btn":
                if ((ff & (1 << 16)) != 0) {       // Pushbutton
                    kind = FormField.Kind.BUTTON;
                } else if ((ff & (1 << 15)) != 0) { // Radio
                    kind = FormField.Kind.RADIO;
                } else {
                    kind = FormField.Kind.CHECKBOX;
                }
                break;
            case "Ch":
                kind = (ff & (1 << 17)) != 0        // Combo
                        ? FormField.Kind.COMBOBOX : FormField.Kind.LISTBOX;
                break;
            case "Sig":
                kind = FormField.Kind.SIGNATURE;
                break;
            default:
                LOG.fine(() -> "unknown field type /" + ft + " — treating as TEXT");
                kind = FormField.Kind.TEXT;
        }

        String name = fullName(widget);
        String value = stringy(inherited(widget, "V"));
        String exportValue = null;
        boolean checked = false;
        if (kind == FormField.Kind.CHECKBOX || kind == FormField.Kind.RADIO) {
            exportValue = onStateName(widget);
            // Visual state: the widget's own /AS wins (per-widget in a radio
            // group); fall back to the inherited /V name.
            String as = widget.getNameAsString("AS");
            String v = value;
            String state = as != null ? as : v;
            checked = state != null && !"Off".equals(state);
            if (checked && exportValue == null) {
                exportValue = state;
            }
        } else if (kind == FormField.Kind.BUTTON) {
            // Caption from the appearance characteristics, not /V.
            PdfDictionary mk = widget.getDictionary("MK");
            String ca = mk != null ? mk.getString("CA") : null;
            if (ca != null) {
                value = ca;
            }
        }

        List<String> options = new ArrayList<>();
        PdfBase optBase = inherited(widget, "Opt");
        if (optBase instanceof PdfArray) {
            for (PdfBase item : (PdfArray) optBase) {
                item = deref(item);
                if (item instanceof PdfArray && ((PdfArray) item).size() >= 2) {
                    // [export display] pair — show the display value.
                    PdfBase disp = deref(((PdfArray) item).get(1));
                    options.add(stringy(disp));
                } else {
                    options.add(stringy(item));
                }
            }
        }

        Integer maxLen = null;
        int ml = inheritedInt(widget, "MaxLen", -1);
        if (ml > 0) {
            maxLen = ml;
        }
        return new WidgetFieldInfo(kind, name, value, checked, exportValue,
                options, multiline, readOnly, maxLen);
    }

    /** The non-Off key of the widget's normal appearance = the on-state name. */
    private static String onStateName(PdfDictionary widget) {
        PdfDictionary ap = widget.getDictionary("AP");
        PdfDictionary n = ap != null ? ap.getDictionary("N") : null;
        if (n == null) {
            return null;
        }
        for (PdfName key : n.keySet()) {
            String s = key.getName();
            if (!"Off".equals(s)) {
                return s;
            }
        }
        return null;
    }

    /** Joins /T up the /Parent chain with dots into the fully-qualified name. */
    private static String fullName(PdfDictionary widget) {
        List<String> parts = new ArrayList<>();
        PdfDictionary d = widget;
        for (int depth = 0; d != null && depth < MAX_PARENT_DEPTH; depth++) {
            String t = d.getString("T");
            if (t != null && !t.isEmpty()) {
                parts.add(0, t);
            }
            d = d.getDictionary("Parent");
        }
        return parts.isEmpty() ? null : String.join(".", parts);
    }

    /** Finds the key on the widget or up the /Parent chain (inheritable keys). */
    private static PdfBase inherited(PdfDictionary widget, String key) {
        PdfDictionary d = widget;
        for (int depth = 0; d != null && depth < MAX_PARENT_DEPTH; depth++) {
            PdfBase v = deref(d.get(key));
            if (v != null) {
                return v;
            }
            d = d.getDictionary("Parent");
        }
        return null;
    }

    private static String inheritedName(PdfDictionary widget, String key) {
        PdfBase v = inherited(widget, key);
        return v instanceof PdfName ? ((PdfName) v).getName() : null;
    }

    private static int inheritedInt(PdfDictionary widget, String key, int def) {
        PdfBase v = inherited(widget, key);
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) v).intValue();
        }
        return def;
    }

    private static PdfBase deref(PdfBase v) {
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            try {
                return ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) v).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return v;
    }

    /** String form of a value: PdfString decoded, PdfName raw, else null. */
    private static String stringy(PdfBase v) {
        if (v instanceof PdfString) {
            return ((PdfString) v).getString();
        }
        if (v instanceof PdfName) {
            return ((PdfName) v).getName();
        }
        return null;
    }

    /** @return the interaction kind, never null */
    public FormField.Kind getKind() {
        return kind;
    }

    /** @return the fully-qualified field name, or null */
    public String getName() {
        return name;
    }

    /** @return the current value / button caption, or null */
    public String getValue() {
        return value;
    }

    /** @return true when a checkbox/radio widget is in its on state */
    public boolean isChecked() {
        return checked;
    }

    /** @return the on-state export value, or null */
    public String getExportValue() {
        return exportValue;
    }

    /** @return choice display options (never null, may be empty) */
    public List<String> getOptions() {
        return options;
    }

    /** @return true for a multi-line text field */
    public boolean isMultiline() {
        return multiline;
    }

    /** @return true for a read-only field */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** @return the text length limit, or null */
    public Integer getMaxLen() {
        return maxLen;
    }

    /**
     * Populates a {@link FormField} node with this info.
     *
     * @param f the node to fill
     */
    public void applyTo(FormField f) {
        f.setName(name);
        f.setValue(value);
        f.setChecked(checked);
        f.setExportValue(exportValue);
        f.getOptions().addAll(options);
        f.setMultiline(multiline);
        f.setReadOnly(readOnly);
        f.setMaxLen(maxLen);
    }
}
