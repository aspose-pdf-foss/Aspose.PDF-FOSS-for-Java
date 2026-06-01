package org.aspose.pdf.forms;

import org.aspose.pdf.Color;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSNull;
import org.aspose.pdf.engine.cos.COSStream;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds Form-XObject {@code /AP/N} appearance streams for form fields
 * (checkbox and radio-button options).
 *
 * <p>Per ISO 32000-1:2008 §12.7.4.3, form-field widget annotations must have
 * an {@code /AP/N} entry that is either a stream (single-state fields like
 * text/button) or a dictionary keyed by state name (multi-state like checkbox
 * {@code /Yes /Off} and radio {@code /<option> /Off}). Without proper
 * {@code /AP/N}, viewers only render the field if {@code /NeedAppearances}
 * is true in the AcroForm dictionary, which is non-portable.</p>
 *
 * <p>This builder uses the standard Zapf Dingbats font (a Type-1 standard
 * 14 font, requires no embedding) for box-style glyphs and a simple
 * black-on-white square for the off state.</p>
 *
 * <p>Closes F-10: previously {@link CheckboxField} and {@link RadioButtonField}
 * stored {@link COSNull#INSTANCE} placeholders or zero-BBox empty streams in
 * {@code /AP/N}, causing {@link Field#getAppearance()} to return null states.</p>
 */
public final class FieldAppearanceBuilder {

    private FieldAppearanceBuilder() {}

    /**
     * Builds a checkbox appearance Form-XObject for the given state and glyph.
     *
     * @param rect the widget rectangle (used for /BBox)
     * @param checked {@code true} for "on" state (renders the glyph),
     *                {@code false} for "Off" (empty stream)
     * @param style the box style; null defaults to {@link BoxStyle#Check}
     * @return a {@link COSStream} wrapped as Form-XObject
     */
    public static COSStream buildCheckboxAppearance(Rectangle rect, boolean checked, BoxStyle style) {
        return buildBoxAppearance(rect, checked, style != null ? style : BoxStyle.Check);
    }

    /**
     * Builds a radio-button appearance Form-XObject for the given state.
     * Defaults to a circle glyph when style is null (radio convention).
     *
     * @param rect the widget rectangle
     * @param selected whether the option is selected
     * @param style the box style; null defaults to {@link BoxStyle#Circle}
     * @return a {@link COSStream} wrapped as Form-XObject
     */
    public static COSStream buildRadioAppearance(Rectangle rect, boolean selected, BoxStyle style) {
        return buildBoxAppearance(rect, selected, style != null ? style : BoxStyle.Circle);
    }

    private static COSStream buildBoxAppearance(Rectangle rect, boolean on, BoxStyle style) {
        double w = rect != null ? rect.getWidth() : 12;
        double h = rect != null ? rect.getHeight() : 12;
        if (w <= 0) w = 12;
        if (h <= 0) h = 12;

        StringBuilder cs = new StringBuilder(64);
        cs.append("q\n");
        if (on) {
            String glyph = boxStyleToGlyph(style);
            double fontSize = Math.min(w, h) * 0.8;
            // Centre the glyph horizontally and seat it slightly above the baseline.
            double tx = w * 0.15;
            double ty = h * 0.2;
            cs.append("BT\n");
            cs.append("/ZaDb ").append(fmt(fontSize)).append(" Tf\n");
            cs.append(fmt(tx)).append(' ').append(fmt(ty)).append(" Td\n");
            cs.append('(').append(glyph).append(") Tj\n");
            cs.append("ET\n");
        }
        cs.append("Q\n");

        COSStream stream = new COSStream();
        stream.setDecodedData(cs.toString().getBytes(StandardCharsets.ISO_8859_1));

        stream.set(COSName.TYPE, COSName.of("XObject"));
        stream.set(COSName.SUBTYPE, COSName.of("Form"));
        stream.set(COSName.of("FormType"), COSInteger.valueOf(1));

        COSArray bbox = new COSArray();
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(0));
        bbox.add(new COSFloat(w));
        bbox.add(new COSFloat(h));
        stream.set(COSName.BBOX, bbox);
        stream.set(COSName.RESOURCES, buildResources());

        return stream;
    }

    /**
     * Installs the per-state appearance streams on a widget dictionary. Replaces
     * any existing {@code /AP/N/<state>} entries (including {@link COSNull}
     * placeholders) and sets {@code /AS} to {@code "Off"} if not already set.
     *
     * @param widgetDict the widget annotation dictionary
     * @param onStateStream the stream for the "on" state
     * @param onStateName the name of the on state (e.g. {@code "Yes"})
     * @param offStateStream the stream for {@code "Off"}
     */
    public static void installAppearance(COSDictionary widgetDict,
                                         COSStream onStateStream,
                                         String onStateName,
                                         COSStream offStateStream) {
        if (widgetDict == null || onStateName == null) return;

        COSBase apVal = widgetDict.get(COSName.of("AP"));
        COSDictionary ap;
        if (apVal instanceof COSDictionary) {
            ap = (COSDictionary) apVal;
        } else {
            ap = new COSDictionary();
            widgetDict.set(COSName.of("AP"), ap);
        }

        COSBase nVal = ap.get(COSName.N);
        COSDictionary apN;
        if (nVal instanceof COSDictionary && !(nVal instanceof COSStream)) {
            apN = (COSDictionary) nVal;
        } else {
            apN = new COSDictionary();
            ap.set(COSName.N, apN);
        }

        if (onStateStream != null) apN.set(COSName.of(onStateName), onStateStream);
        if (offStateStream != null) apN.set(COSName.of("Off"), offStateStream);

        if (widgetDict.get(COSName.of("AS")) == null) {
            widgetDict.set(COSName.of("AS"), COSName.of("Off"));
        }
    }

    /**
     * Returns {@code true} if {@code /AP/N} on the given widget contains any
     * {@link COSNull} placeholder or is missing entirely. Used by attach paths
     * to detect when to (re)generate appearances.
     */
    public static boolean isAppearanceIncomplete(COSDictionary widgetDict) {
        COSBase apVal = widgetDict.get(COSName.of("AP"));
        if (!(apVal instanceof COSDictionary)) return true;
        COSBase nVal = ((COSDictionary) apVal).get(COSName.N);
        if (nVal == null) return true;
        if (nVal instanceof COSStream) return isStreamIncomplete((COSStream) nVal);
        if (!(nVal instanceof COSDictionary)) return true;
        COSDictionary apN = (COSDictionary) nVal;
        if (apN.size() == 0) return true;
        for (COSName key : apN.keySet()) {
            COSBase entry = apN.get(key);
            if (entry == null || entry instanceof COSNull) return true;
            if (entry instanceof COSStream) {
                if (isStreamIncomplete((COSStream) entry)) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    private static boolean isStreamIncomplete(COSStream s) {
        // Missing or zero-area BBox = placeholder. Also empty data + no BBox = placeholder.
        COSBase bbox = s.get(COSName.BBOX);
        if (bbox instanceof COSArray && ((COSArray) bbox).size() == 4) {
            COSArray bb = (COSArray) bbox;
            double width = bb.getFloat(2, 0) - bb.getFloat(0, 0);
            double height = bb.getFloat(3, 0) - bb.getFloat(1, 0);
            return width <= 0 || height <= 0;
        }
        // BBox missing entirely — only complete if data is non-empty (legacy spec-lax forms)
        try {
            byte[] data = s.getDecodedData();
            return data == null || data.length == 0;
        } catch (Exception e) {
            return true;
        }
    }

    private static COSDictionary buildResources() {
        COSDictionary resources = new COSDictionary();
        COSDictionary fonts = new COSDictionary();
        COSDictionary zadb = new COSDictionary();
        zadb.set(COSName.TYPE, COSName.FONT);
        zadb.set(COSName.SUBTYPE, COSName.of("Type1"));
        zadb.set(COSName.BASE_FONT, COSName.of("ZapfDingbats"));
        zadb.set(COSName.of("Name"), COSName.of("ZaDb"));
        fonts.set("ZaDb", zadb);
        resources.set(COSName.of("Font"), fonts);
        return resources;
    }

    /**
     * Returns the single-character Zapf Dingbats string for the given box style.
     * Codes are the standard PDF check-glyph mapping (Adobe Acrobat convention).
     */
    private static String boxStyleToGlyph(BoxStyle style) {
        if (style == null) return "4";
        switch (style) {
            case Check:   return "4";   // ✓
            case Circle:  return "l";   // ●
            case Cross:   return "8";   // ✕
            case Diamond: return "u";   // ◆
            case Square:  return "n";   // ■
            case Star:    return "H";   // ★
            default:      return "4";
        }
    }

    private static String fmt(double v) {
        if (v == (long) v) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        int end = s.length();
        while (end > s.indexOf('.') + 2 && s.charAt(end - 1) == '0') end--;
        return s.substring(0, end);
    }
}
