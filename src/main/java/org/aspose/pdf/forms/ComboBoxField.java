package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

/**
 * Combo box / dropdown field (/FT /Ch, combo flag) (ISO 32000-1:2008, §12.7.4.4).
 * <p>
 * A combo box allows the user to select a single value from a list of options,
 * and optionally type a custom value if the editable flag is set.
 * </p>
 */
public class ComboBoxField extends Field {

    /**
     * Constructs a combo box field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public ComboBoxField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new empty combo box field.
     */
    public ComboBoxField() {
        super(new COSDictionary(), null, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Ch"));
        dict.set(COSName.of("Ff"), COSInteger.valueOf(1 << 17));
    }

    /**
     * Constructs a new combo box field associated with the given document.
     *
     * @param doc the document this field belongs to
     */
    public ComboBoxField(Document doc) {
        this();
    }

    /**
     * Constructs a new combo box field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    public ComboBoxField(Page page, Rectangle rect) {
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Ch"));
        // Set combo flag (bit 18)
        dict.set(COSName.of("Ff"), COSInteger.valueOf(1 << 17));
        setRectLenient(rect);
        if (getDefaultAppearance() == null) {
            setDefaultAppearance("/Helv 12 Tf 0 g");
        }
        regenerateAppearance();
    }

    /**
     * Rebuilds the {@code /AP/N} normal appearance stream from the current
     * rectangle, default appearance ({@code /DA}) and selected value, so the
     * dropdown's chosen text is visible in strict viewers (poppler, mupdf) that
     * render only the appearance stream (F-10 sibling, Sprint 22 Part 3).
     *
     * <p>Idempotent. No-op when the widget has no (positive-area) {@code /Rect}
     * yet — a degenerate or missing rectangle is stored rather than rejected,
     * matching {@link Field}'s lenient construction semantics.</p>
     */
    public void regenerateAppearance() {
        Rectangle rect = getRect();
        if (rect == null) return;
        double w = rect.getWidth();
        double h = rect.getHeight();
        if (w <= 0 || h <= 0) return; // F-10: silently skip degenerate rects

        String selected = getSelected();
        if (selected == null) selected = "";

        // Minimal /DA parse: "/Font size Tf <colorOps>"
        String fontName = "Helv";
        double size = 12.0;
        String colorOps = "0 g";
        String da = getDefaultAppearance();
        if (da != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("/(\\S+)\\s+([0-9.]+)\\s+Tf").matcher(da);
            if (m.find()) {
                fontName = m.group(1);
                try { size = Double.parseDouble(m.group(2)); } catch (NumberFormatException ignored) {}
            }
            int tf = da.indexOf("Tf");
            if (tf >= 0 && tf + 2 < da.length()) {
                String tail = da.substring(tf + 2).trim();
                if (!tail.isEmpty()) colorOps = tail;
            }
        }
        if (size <= 0) size = 12.0;

        double yOffset = Math.max(0, (h - size) / 2.0);
        StringBuilder cs = new StringBuilder(64 + selected.length());
        cs.append("/Tx BMC\n");
        cs.append("q\n");
        cs.append("BT\n");
        cs.append('/').append(fontName).append(' ').append(formatNum(size)).append(" Tf\n");
        cs.append(colorOps).append('\n');
        cs.append("2 ").append(formatNum(yOffset)).append(" Td\n");
        cs.append(escapeLiteral(selected)).append(" Tj\n");
        cs.append("ET\n");
        cs.append("Q\n");
        cs.append("EMC\n");

        COSStream apStream = new COSStream();
        apStream.set(COSName.TYPE, COSName.of("XObject"));
        apStream.set(COSName.SUBTYPE, COSName.of("Form"));
        apStream.set(COSName.of("FormType"), COSInteger.valueOf(1));
        COSArray bbox = new COSArray();
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(w));
        bbox.add(new COSFloat(h));
        apStream.set(COSName.BBOX, bbox);
        apStream.set(COSName.RESOURCES, buildAppearanceResources(fontName));
        apStream.setDecodedData(cs.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        COSBase apVal = dict.get(COSName.of("AP"));
        if (apVal instanceof COSObjectReference) {
            try { apVal = ((COSObjectReference) apVal).dereference(); }
            catch (Exception e) { apVal = null; }
        }
        COSDictionary ap;
        if (apVal instanceof COSDictionary) {
            ap = (COSDictionary) apVal;
        } else {
            ap = new COSDictionary();
            dict.set(COSName.of("AP"), ap);
        }
        ap.set(COSName.N, apStream);
    }

    private static COSDictionary buildAppearanceResources(String fontName) {
        COSDictionary font = new COSDictionary();
        font.set(COSName.TYPE, COSName.of("Font"));
        font.set(COSName.SUBTYPE, COSName.of("Type1"));
        font.set(COSName.of("BaseFont"), COSName.of("Helvetica"));
        COSDictionary fonts = new COSDictionary();
        fonts.set(COSName.of(fontName), font);
        COSDictionary res = new COSDictionary();
        res.set(COSName.of("Font"), fonts);
        return res;
    }

    private static String formatNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return String.valueOf(Math.round(v * 1000.0) / 1000.0);
    }

    private static String escapeLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('(');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == ')' || c == '\\') sb.append('\\');
            sb.append(c);
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Returns the options (/Opt array) for this combo box.
     *
     * @return the option collection (never null)
     */
    public OptionCollection getOptions() {
        COSBase opt = dict.get("Opt");
        if (opt instanceof COSObjectReference) {
            try {
                opt = ((COSObjectReference) opt).dereference();
            } catch (Exception ignored) {
                opt = null;
            }
        }
        return new OptionCollection(opt instanceof COSArray ? (COSArray) opt : new COSArray());
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
        regenerateAppearance();
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
            regenerateAppearance();
        }
    }

    /**
     * Adds an option to this combo box (/Opt array).
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
     * Returns whether this combo box is editable (/Ff bit 19).
     *
     * @return true if the user can type a custom value
     */
    public boolean isEditable() {
        return (getFieldFlags() & (1 << 18)) != 0;
    }
}
