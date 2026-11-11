package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;

/**
 * Combo box / dropdown field (/FT /Ch, combo flag) (ISO 32000-1:2008, §12.7.4.4).
 * <p>
 * A combo box allows the user to select a single value from a list of options,
 * and optionally type a custom value if the editable flag is set.
 * </p>
 */
public class ComboBoxField extends Field {

    /**
     * Constructs a combo box field from an existing PDF dictionary.
     *
     * @param dict     the PDF dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public ComboBoxField(PdfDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new empty combo box field.
     */
    public ComboBoxField() {
        super(new PdfDictionary(), null, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Ch"));
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(1 << 17));
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
        super(new PdfDictionary(), page, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Ch"));
        // Set combo flag (bit 18)
        dict.set(PdfName.of("Ff"), PdfInteger.valueOf(1 << 17));
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
    @Override
    protected void regenerateAppearanceIfSupported() {
        regenerateAppearance();
    }

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

        PdfStream apStream = new PdfStream();
        apStream.set(PdfName.TYPE, PdfName.of("XObject"));
        apStream.set(PdfName.SUBTYPE, PdfName.of("Form"));
        apStream.set(PdfName.of("FormType"), PdfInteger.valueOf(1));
        PdfArray bbox = new PdfArray();
        bbox.add(new PdfFloat(0));
        bbox.add(new PdfFloat(0));
        bbox.add(new PdfFloat(w));
        bbox.add(new PdfFloat(h));
        apStream.set(PdfName.BBOX, bbox);
        apStream.set(PdfName.RESOURCES, buildAppearanceResources(fontName));
        apStream.setDecodedData(cs.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        PdfBase apVal = dict.get(PdfName.of("AP"));
        if (apVal instanceof PdfObjectReference) {
            try { apVal = ((PdfObjectReference) apVal).dereference(); }
            catch (Exception e) { apVal = null; }
        }
        PdfDictionary ap;
        if (apVal instanceof PdfDictionary) {
            ap = (PdfDictionary) apVal;
        } else {
            ap = new PdfDictionary();
            dict.set(PdfName.of("AP"), ap);
        }
        ap.set(PdfName.N, apStream);
    }

    private static PdfDictionary buildAppearanceResources(String fontName) {
        PdfDictionary font = new PdfDictionary();
        font.set(PdfName.TYPE, PdfName.of("Font"));
        font.set(PdfName.SUBTYPE, PdfName.of("Type1"));
        font.set(PdfName.of("BaseFont"), PdfName.of("Helvetica"));
        // WinAnsiEncoding so the ISO-8859-1 bytes we write render their Latin-1 accents (else the
        // built-in StandardEncoding garbles them: 0xE1 'á' → 'æ').
        font.set(PdfName.of("Encoding"), PdfName.of("WinAnsiEncoding"));
        PdfDictionary fonts = new PdfDictionary();
        fonts.set(PdfName.of(fontName), font);
        PdfDictionary res = new PdfDictionary();
        res.set(PdfName.of("Font"), fonts);
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
        PdfBase opt = dict.get("Opt");
        if (opt instanceof PdfObjectReference) {
            try {
                opt = ((PdfObjectReference) opt).dereference();
            } catch (Exception ignored) {
                opt = null;
            }
        }
        return new OptionCollection(opt instanceof PdfArray ? (PdfArray) opt : new PdfArray());
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
        PdfBase opt = dict.get("Opt");
        PdfArray arr;
        if (opt instanceof PdfArray) {
            arr = (PdfArray) opt;
        } else {
            arr = new PdfArray();
            dict.set(PdfName.of("Opt"), arr);
        }
        arr.add(new PdfString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
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
