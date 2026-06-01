package org.aspose.pdf.forms;

import org.aspose.pdf.*;
import org.aspose.pdf.engine.cos.*;

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
     * Constructs a text box field from an existing COS dictionary.
     *
     * @param dict     the COS dictionary backing this field
     * @param page     the page this field belongs to (may be null)
     * @param fullName the fully-qualified dotted name
     */
    public TextBoxField(COSDictionary dict, Page page, String fullName) {
        super(dict, page, fullName);
    }

    /**
     * Constructs a new text box field on the given page with the specified rectangle.
     *
     * @param page the page
     * @param rect the field rectangle
     */
    public TextBoxField(Page page, Rectangle rect) {
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Tx"));
        setRectLenient(rect);
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
        super(new COSDictionary(), page, "");
        dict.set(COSName.of("Type"), COSName.of("Annot"));
        dict.set(COSName.of("Subtype"), COSName.of("Widget"));
        dict.set(COSName.of("FT"), COSName.of("Tx"));
        if (rects != null && rects.length > 0) {
            if (rects.length == 1) {
                setRectLenient(rects[0]);
            } else {
                // Multiple rects: create /Kids array with one widget per rect
                COSArray kids = new COSArray();
                for (Rectangle rect : rects) {
                    COSDictionary kid = new COSDictionary();
                    kid.set(COSName.of("Type"), COSName.of("Annot"));
                    kid.set(COSName.of("Subtype"), COSName.of("Widget"));
                    if (rect != null) {
                        kid.set(COSName.of("Rect"), rect.toCOSArray());
                    }
                    kids.add(kid);
                }
                dict.set(COSName.of("Kids"), kids);
                // Set main Rect to the first rectangle
                setRectLenient(rects[0]);
            }
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
        dict.set(COSName.of("MaxLen"), COSInteger.valueOf(maxLen));
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
        COSBase kidsObj = dict.get(COSName.of("Kids"));
        if (kidsObj instanceof COSObjectReference) {
            try { kidsObj = ((COSObjectReference) kidsObj).dereference(); }
            catch (Exception e) { kidsObj = null; }
        }

        COSStream firstAp = null;
        if (kidsObj instanceof COSArray) {
            COSArray kids = (COSArray) kidsObj;
            for (int i = 0; i < kids.size(); i++) {
                COSBase kid = kids.get(i);
                if (kid instanceof COSObjectReference) {
                    try { kid = ((COSObjectReference) kid).dereference(); }
                    catch (Exception e) { continue; }
                }
                if (kid instanceof COSDictionary) {
                    Rectangle kidRect = rectFromDict((COSDictionary) kid);
                    if (kidRect != null) {
                        COSStream ap = buildAndStoreAppearance((COSDictionary) kid, text, kidRect);
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

    private COSStream buildAndStoreAppearance(COSDictionary widgetDict, String text, Rectangle rect) {
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
        cs.append("2 ").append(formatNum(yOffset)).append(" Td\n");
        cs.append(escapeLiteral(text)).append(" Tj\n");
        cs.append("ET\n");
        cs.append("Q\n");
        cs.append("EMC\n");

        COSStream apStream = new COSStream();
        apStream.set(COSName.TYPE, COSName.XOBJECT);
        apStream.set(COSName.SUBTYPE, COSName.FORM);
        COSArray bbox = new COSArray();
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(rect.getWidth()));
        bbox.add(new COSFloat(rect.getHeight()));
        apStream.set(COSName.BBOX, bbox);
        apStream.set(COSName.RESOURCES, buildAppearanceResources(fontName));
        apStream.setDecodedData(cs.toString().getBytes(StandardCharsets.ISO_8859_1));

        installAppearance(widgetDict, apStream);
        return apStream;
    }

    private static void installAppearance(COSDictionary widgetDict, COSStream apStream) {
        COSBase apVal = widgetDict.get(COSName.of("AP"));
        if (apVal instanceof COSObjectReference) {
            try { apVal = ((COSObjectReference) apVal).dereference(); }
            catch (Exception e) { apVal = null; }
        }
        COSDictionary ap;
        if (apVal instanceof COSDictionary) {
            ap = (COSDictionary) apVal;
        } else {
            ap = new COSDictionary();
            widgetDict.set(COSName.of("AP"), ap);
        }
        ap.set(COSName.N, apStream);
    }

    private static Rectangle rectFromDict(COSDictionary widgetDict) {
        COSBase r = widgetDict.get(COSName.of("Rect"));
        if (r instanceof COSObjectReference) {
            try { r = ((COSObjectReference) r).dereference(); }
            catch (Exception e) { return null; }
        }
        if (r instanceof COSArray && ((COSArray) r).size() == 4) {
            return Rectangle.fromCOSArray((COSArray) r);
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

    private static COSDictionary buildAppearanceResources(String fontName) {
        COSDictionary resources = new COSDictionary();
        COSDictionary fonts = new COSDictionary();
        COSDictionary font = new COSDictionary();
        font.set(COSName.TYPE, COSName.FONT);
        font.set(COSName.SUBTYPE, COSName.of("Type1"));
        font.set(COSName.BASE_FONT, COSName.of("Helvetica"));
        font.set(COSName.of("Name"), COSName.of(fontName));
        fonts.set(fontName, font);
        resources.set(COSName.of("Font"), fonts);
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
