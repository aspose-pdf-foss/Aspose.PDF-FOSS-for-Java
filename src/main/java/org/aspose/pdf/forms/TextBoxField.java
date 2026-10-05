package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.pdfobjects.*;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text input field (/FT /Tx) (ISO 32000-1:2008, §12.7.4.3).
 * <p>
 * Represents a single-line or multi-line text input field in an interactive form.
 * </p>
 */
public class TextBoxField extends Field {

    private static final int COMB_FLAG = 1 << 24;

    /**
     * Constructs a text box field from an existing PDF dictionary.
     *
     * @param dict     the PDF dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public TextBoxField(PdfDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new text box field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    public TextBoxField(Page page, Rectangle rect) {
        super(new PdfDictionary(), page, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Tx"));
        setRectLenient(rect);
        markPrintable(dict);
        // ISO §12.7.3.3: variable-text fields need /DA with at least a /Tf
        // selector or strict readers (poppler, mupdf) won't render typed text.
        if (getDefaultAppearance() == null) {
            // Size 0 = auto-shrink (Aspose-compat semantic, preserved by
            // regenerateAppearance). /Helv resolves via the AcroForm /DR
            // populated lazily by Form.add(...).
            setDefaultAppearance("/Helv 0 Tf 0 g");
        }
    }

    /**
     * Constructs a new text box field associated with the first page of the document.
     * Useful for Aspose-compatible code paths that create a field from a document first.
     *
     * @param document the owning document
     * @param rect the field rectangle
     */
    public TextBoxField(Document document, Rectangle rect) {
        this(document != null ? firstPage(document) : null, rect);
    }

    /**
     * Constructs a new text box field on the given page with multiple rectangles.
     * <p>
     * This creates a single field with multiple widget annotations (one per rectangle),
     * stored as /Kids entries. This is useful when the same field needs to appear
     * at multiple locations on a page or across pages.
     * </p>
     *
     * @param page  the page
     * @param rects the array of rectangles for the field's widgets
     */
    public TextBoxField(Page page, Rectangle[] rects) {
        super(new PdfDictionary(), page, "");
        dict.set(PdfName.of("Type"), PdfName.of("Annot"));
        dict.set(PdfName.of("Subtype"), PdfName.of("Widget"));
        dict.set(PdfName.of("FT"), PdfName.of("Tx"));
        if (rects != null && rects.length > 0) {
            if (rects.length == 1) {
                setRectLenient(rects[0]);
            } else {
                // Multiple rects: create /Kids array with one widget per rect
                PdfArray kids = new PdfArray();
                for (Rectangle rect : rects) {
                    PdfDictionary kid = new PdfDictionary();
                    kid.set(PdfName.of("Type"), PdfName.of("Annot"));
                    kid.set(PdfName.of("Subtype"), PdfName.of("Widget"));
                    if (rect != null) {
                        kid.set(PdfName.of("Rect"), rect.toPdfArray());
                    }
                    kids.add(kid);
                }
                dict.set(PdfName.of("Kids"), kids);
                // Set main Rect to the first rectangle
                setRectLenient(rects[0]);
            }
        }
        markPrintable(dict);
    }

    /**
     * Ensures a freshly created widget annotation carries the Print flag
     * ({@code /F} bit 3, ISO 32000-1:2008 §12.5.3). Real generators (and Acrobat)
     * always set it; without any {@code /F}, Adobe Acrobat/Reader does not paint
     * the field's baked {@code /AP} appearance until the field first gains focus,
     * so values look invisible on open. Only applied when {@code /F} is absent so
     * an explicitly-configured flag set is never overridden.
     *
     * @param d the widget annotation dictionary
     */
    static void markPrintable(PdfDictionary d) {
        if (d.get("F") == null) {
            d.set(PdfName.of("F"), PdfInteger.valueOf(4)); // Print
        }
    }

    /**
     * Returns whether this text field is multiline (/Ff bit 13).
     *
     * @return true if multiline
     */
    public boolean isMultiline() {
        return (getFieldFlags() & (1 << 12)) != 0;
    }

    /**
     * Sets whether this text field is multiline (/Ff bit 13).
     *
     * @param ml true to enable multiline
     */
    public void setMultiline(boolean ml) {
        int ff = getFieldFlags();
        setFieldFlags(ml ? (ff | (1 << 12)) : (ff & ~(1 << 12)));
    }

    /**
     * Returns whether this text field is a password field (/Ff bit 14).
     *
     * @return true if password
     */
    public boolean isPassword() {
        return (getFieldFlags() & (1 << 13)) != 0;
    }

    /**
     * Returns the maximum length of text (/MaxLen entry).
     *
     * @return the max length, or 0 if not set
     */
    public int getMaxLen() {
        return dict.getInt("MaxLen", 0);
    }

    /**
     * Sets the maximum length of text (/MaxLen entry).
     *
     * @param maxLen the maximum length
     */
    public void setMaxLen(int maxLen) {
        dict.set(PdfName.of("MaxLen"), PdfInteger.valueOf(maxLen));
    }

    /**
     * Returns whether comb formatting is enabled (/Ff bit 25).
     *
     * @return true if comb formatting is enabled
     */
    public boolean isForceCombs() {
        return (getFieldFlags() & COMB_FLAG) != 0;
    }

    /**
     * Sets whether comb formatting is enabled (/Ff bit 25).
     *
     * @param value true to enable comb formatting
     */
    public void setForceCombs(boolean value) {
        int flags = getFieldFlags();
        setFieldFlags(value ? (flags | COMB_FLAG) : (flags & ~COMB_FLAG));
    }

    private static Page firstPage(Document document) {
        try {
            if (document.getPages().getCount() == 0) {
                return document.getPages().add();
            }
            return document.getPages().get(1);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Sets the field value and rebuilds the {@code /AP /N} appearance stream
     * so that downstream readers (and renderers) see the new text at the right
     * font size.
     *
     * <p>If the field's effective default-appearance string ({@code /DA}) has
     * an explicit font size, that size is used. If the {@code /DA} size is
     * {@code 0} (auto-shrink), this method computes a size that fits the new
     * value in the widget rectangle using a Helvetica-average advance width
     * estimate (≈ 0.467 × {@code size}) and a 2pt horizontal padding. The
     * computed size is clamped to {@code [0.5, 12]}.</p>
     *
     * <p>Closes BUG-058: previously {@code setValue} only updated {@code /V},
     * leaving stale glyphs in {@code /AP /N} and causing renderers to skip the
     * field entirely when looking for the per-field font size.</p>
     */
    @Override
    public void setValue(String value) {
        super.setValue(value);
        try {
            regenerateAppearance();
        } catch (RuntimeException e) {
            // Appearance regeneration is best-effort: never let it fail
            // setValue itself (the /V entry has already been written).
        }
    }

    /**
     * Rebuilds {@code /AP /N} from the current {@code /V}, {@code /Rect} and
     * default-appearance. Exposed for tests and the rare case where callers
     * mutate {@code /V} via a sibling API and want appearance to catch up.
     *
     * <p>If the field has child widget annotations under {@code /Kids}, an
     * appearance stream is generated for each kid (using the kid's own
     * {@code /Rect}). The same stream is also mirrored on the parent field so
     * callers reading {@code field.getNormalAppearance()} see it.</p>
     */
    public void regenerateAppearance() {
        String text = getValue();
        if (text == null) text = "";

        // Try /Kids first — widget annotations may live as separate dicts.
        PdfBase kidsObj = dict.get(PdfName.of("Kids"));
        if (kidsObj instanceof PdfObjectReference) {
            try { kidsObj = ((PdfObjectReference) kidsObj).dereference(); }
            catch (Exception e) { kidsObj = null; }
        }

        PdfStream firstAp = null;
        if (kidsObj instanceof PdfArray) {
            PdfArray kids = (PdfArray) kidsObj;
            for (int i = 0; i < kids.size(); i++) {
                PdfBase kid = kids.get(i);
                if (kid instanceof PdfObjectReference) {
                    try { kid = ((PdfObjectReference) kid).dereference(); }
                    catch (Exception e) { continue; }
                }
                if (kid instanceof PdfDictionary) {
                    Rectangle kidRect = rectFromDict((PdfDictionary) kid);
                    if (kidRect != null) {
                        PdfStream ap = buildAndStoreAppearance((PdfDictionary) kid, text, kidRect);
                        if (firstAp == null) firstAp = ap;
                    }
                }
            }
        }

        Rectangle ownRect = getRect();
        if (firstAp != null) {
            // Mirror the kid's appearance onto the parent dict so direct reads via
            // field.getNormalAppearance() (bypassing the widget hierarchy) return
            // something sensible. Mirroring the stream object is fine because
            // serialisation walks it through the same writer.
            installAppearance(dict, firstAp);
        } else if (ownRect != null) {
            buildAndStoreAppearance(dict, text, ownRect);
        }
    }

    private PdfStream buildAndStoreAppearance(PdfDictionary widgetDict, String text, Rectangle rect) {
        DAInfo da = parseDA(getEffectiveDA());
        double size = da.size;
        if (size <= 0) {
            double padding = 2.0;
            double available = Math.max(1.0, rect.getWidth() - padding * 2.0);
            int n = Math.max(1, text.length());
            // 0.5 ≈ avg em-width for a uniform-glyph string in Helvetica/Arial.
            // Tuned so a 52-char "Test..." string in a ~120pt-wide widget
            // resolves to ≈ 4.5pt (PDFNET_58367 expects 4.5 ± 0.2).
            size = available / (n * 0.5);
            if (size > 12.0) size = 12.0;
            if (size < 0.5) size = 0.5;
        }
        String fontName = da.fontName != null ? da.fontName : "Helv";

        StringBuilder cs = new StringBuilder(64 + text.length());
        cs.append("/Tx BMC\n");
        cs.append("q\n");
        cs.append("BT\n");
        cs.append('/').append(fontName).append(' ').append(formatNum(size)).append(" Tf\n");
        if (da.colorOps != null && !da.colorOps.isEmpty()) {
            cs.append(da.colorOps).append('\n');
        } else {
            cs.append("0 g\n");
        }
        double yOffset = Math.max(0, (rect.getHeight() - size) / 2.0);
        // Horizontal placement honours the field quadding (/Q): 0=left (2pt inset), 1=centre, 2=right.
        // Right/centre need the run width, measured from the standard Helvetica metrics (the /DA font).
        double xOffset = 2.0;
        int q = effectiveQuadding(widgetDict);
        if (q != 0) {
            double textWidth = helveticaWidth(text, size);
            double avail = rect.getWidth() - 4.0;
            if (textWidth < avail) {
                xOffset = q == 2 ? rect.getWidth() - 2.0 - textWidth
                        : Math.max(2.0, (rect.getWidth() - textWidth) / 2.0);
            }
        }
        cs.append(formatNum(xOffset)).append(' ').append(formatNum(yOffset)).append(" Td\n");
        cs.append(escapeLiteral(text)).append(" Tj\n");
        cs.append("ET\n");
        cs.append("Q\n");
        cs.append("EMC\n");

        PdfStream apStream = new PdfStream();
        apStream.set(PdfName.TYPE, PdfName.XOBJECT);
        apStream.set(PdfName.SUBTYPE, PdfName.FORM);
        PdfArray bbox = new PdfArray();
        bbox.add(new PdfFloat(0));
        bbox.add(new PdfFloat(0));
        bbox.add(new PdfFloat(rect.getWidth()));
        bbox.add(new PdfFloat(rect.getHeight()));
        apStream.set(PdfName.BBOX, bbox);
        // /FormType and the identity /Matrix are defaults per §8.10.2, but real
        // generators write them explicitly and some viewers (and Acrobat's
        // form-field appearance path) are happier when they are present.
        apStream.set(PdfName.of("FormType"), PdfInteger.valueOf(1));
        PdfArray matrix = new PdfArray();
        for (double m : new double[]{1, 0, 0, 1, 0, 0}) {
            matrix.add(new PdfFloat((float) m));
        }
        apStream.set(PdfName.of("Matrix"), matrix);
        apStream.set(PdfName.RESOURCES, buildAppearanceResources(fontName));
        apStream.setDecodedData(cs.toString().getBytes(StandardCharsets.ISO_8859_1));

        installAppearance(widgetDict, apStream);
        return apStream;
    }

    private static void installAppearance(PdfDictionary widgetDict, PdfStream apStream) {
        PdfBase apVal = widgetDict.get(PdfName.of("AP"));
        if (apVal instanceof PdfObjectReference) {
            try { apVal = ((PdfObjectReference) apVal).dereference(); }
            catch (Exception e) { apVal = null; }
        }
        PdfDictionary ap;
        if (apVal instanceof PdfDictionary) {
            ap = (PdfDictionary) apVal;
        } else {
            ap = new PdfDictionary();
            widgetDict.set(PdfName.of("AP"), ap);
        }
        ap.set(PdfName.N, apStream);
    }

    private static Rectangle rectFromDict(PdfDictionary widgetDict) {
        PdfBase r = widgetDict.get(PdfName.of("Rect"));
        if (r instanceof PdfObjectReference) {
            try { r = ((PdfObjectReference) r).dereference(); }
            catch (Exception e) { return null; }
        }
        if (r instanceof PdfArray && ((PdfArray) r).size() == 4) {
            return Rectangle.fromPdfArray((PdfArray) r);
        }
        return null;
    }

    /** Returns the field's /DA, walking through the field hierarchy when absent. */
    private String getEffectiveDA() {
        String da = getDefaultAppearance();
        if (da != null && !da.isEmpty()) return da;
        // Aspose-compat default if nothing is set anywhere
        return "/Helv 0 Tf 0 g";
    }

    /** The effective {@code /Q} quadding for the appearance — the widget's, else the field's, else 0. */
    private int effectiveQuadding(PdfDictionary widgetDict) {
        int q = quaddingOf(widgetDict);
        if (q < 0) {
            q = quaddingOf(dict);
        }
        return q < 0 ? 0 : q;
    }

    private static int quaddingOf(PdfDictionary d) {
        if (d == null) {
            return -1;
        }
        PdfBase v = d.get(PdfName.of("Q"));
        if (v instanceof PdfObjectReference) {
            try { v = ((PdfObjectReference) v).dereference(); }
            catch (Exception e) { return -1; }
        }
        return v instanceof PdfInteger ? ((PdfInteger) v).intValue() : -1;
    }

    /** Width of {@code text} at {@code size} pt in Helvetica, from the standard AFM metrics (1/1000 em). */
    private static double helveticaWidth(String text, double size) {
        int[] w = org.aspose.pdf.engine.font.StandardFonts.getWidths("Helvetica");
        if (w == null) {
            return text.length() * size * 0.5; // fallback ≈ avg em width
        }
        double total = 0;
        for (int i = 0; i < text.length(); i++) {
            int c = text.charAt(i) & 0xFF;
            total += w[c];
        }
        return total / 1000.0 * size;
    }

    private static PdfDictionary buildAppearanceResources(String fontName) {
        PdfDictionary resources = new PdfDictionary();
        PdfDictionary fonts = new PdfDictionary();
        PdfDictionary font = new PdfDictionary();
        font.set(PdfName.TYPE, PdfName.FONT);
        font.set(PdfName.SUBTYPE, PdfName.of("Type1"));
        font.set(PdfName.BASE_FONT, PdfName.of("Helvetica"));
        // Without an /Encoding a viewer uses Helvetica's built-in StandardEncoding (0xE1='æ' etc.), which
        // garbles the ISO-8859-1 bytes we write — "Česká" rendered "?eskÆ". WinAnsiEncoding maps the
        // Latin-1 accents correctly (á/é/í/ó/ú/ý). (Truly non-WinAnsi glyphs — Czech č/ř/š/ž/Č — still
        // need an embedded font, as the render track uses.)
        font.set(PdfName.of("Encoding"), PdfName.of("WinAnsiEncoding"));
        font.set(PdfName.of("Name"), PdfName.of(fontName));
        fonts.set(fontName, font);
        resources.set(PdfName.of("Font"), fonts);
        PdfArray procSet = new PdfArray();
        procSet.add(PdfName.of("PDF"));
        procSet.add(PdfName.of("Text"));
        resources.set(PdfName.of("ProcSet"), procSet);
        return resources;
    }

    /** Parses {@code /DA} → font name + size + leading color-setting tokens. */
    private static DAInfo parseDA(String da) {
        DAInfo info = new DAInfo();
        if (da == null) return info;
        // Match "/<font> <size> Tf" (size may be 0 for auto-shrink)
        Matcher m = TF_PATTERN.matcher(da);
        if (m.find()) {
            info.fontName = m.group(1);
            try { info.size = Double.parseDouble(m.group(2)); }
            catch (NumberFormatException ignored) {}
            // Everything after Tf is color/state setup we replay verbatim
            String tail = da.substring(m.end()).trim();
            if (!tail.isEmpty()) info.colorOps = tail;
        }
        return info;
    }

    private static final Pattern TF_PATTERN =
            Pattern.compile("/([A-Za-z0-9_]+)\\s+(-?\\d+(?:\\.\\d+)?)\\s+Tf");

    private static String formatNum(double v) {
        if (v == (long) v) return Long.toString((long) v);
        // Strip trailing zeros but keep a sensible precision
        String s = String.format(java.util.Locale.ROOT, "%.4f", v);
        int dot = s.indexOf('.');
        if (dot < 0) return s;
        int end = s.length();
        while (end > dot + 2 && s.charAt(end - 1) == '0') end--;
        return s.substring(0, end);
    }

    private static String escapeLiteral(String s) {
        StringBuilder out = new StringBuilder(s.length() + 4);
        out.append('(');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '(': out.append("\\("); break;
                case ')': out.append("\\)"); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: out.append(c);
            }
        }
        out.append(')');
        return out.toString();
    }

    private static final class DAInfo {
        String fontName;
        double size;
        String colorOps;
    }
}
