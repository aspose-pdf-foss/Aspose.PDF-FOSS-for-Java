package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSName;

/**
 * Package-private helpers used by {@link BackgroundArtifact} and
 * {@link WatermarkArtifact} when synthesising raw content-stream bytes:
 * register Standard-14 fonts and opacity {@code /ExtGState} entries on the
 * page's {@code /Resources}, escape PDF literal strings, and extract RGB
 * components from a {@link Color}.
 */
final class ArtifactSupport {

    private ArtifactSupport() { }

    /**
     * Ensures a Type1 Standard-14 font entry exists on the page's
     * {@code /Resources/Font} and returns its resource name. Reuses an
     * existing entry if {@code /BaseFont} matches; otherwise registers a
     * new {@code F<sanitised-name>} entry.
     */
    static String ensureStandardFont(Page page, String baseFont) {
        Resources res = page.ensureResources();
        COSDictionary fonts = res.getFonts();
        if (fonts == null) {
            fonts = new COSDictionary();
            res.getCOSDictionary().set(COSName.of("Font"), fonts);
        }
        // Re-use any existing /BaseFont match.
        for (COSName key : fonts.keySet()) {
            org.aspose.pdf.engine.cos.COSBase val = fonts.get(key);
            if (val instanceof COSDictionary) {
                String existingBase = ((COSDictionary) val).getNameAsString("BaseFont");
                if (baseFont.equals(existingBase)) return key.getName();
            }
        }
        String name = "F" + baseFont.replace("-", "");
        if (fonts.get(name) != null) {
            int n = 2;
            while (fonts.get(name + n) != null) n++;
            name = name + n;
        }
        COSDictionary f = new COSDictionary();
        f.set(COSName.of("Type"), COSName.of("Font"));
        f.set(COSName.of("Subtype"), COSName.of("Type1"));
        f.set(COSName.of("BaseFont"), COSName.of(baseFont));
        if (!"ZapfDingbats".equals(baseFont) && !"Symbol".equals(baseFont)) {
            f.set(COSName.of("Encoding"), COSName.of("WinAnsiEncoding"));
        }
        fonts.set(COSName.of(name), f);
        return name;
    }

    /**
     * Ensures an {@code /ExtGState} entry providing the given non-stroking
     * and stroking alpha exists on the page's {@code /Resources} and returns
     * its resource name.
     */
    static String ensureOpacityExtGState(Page page, double alpha) {
        Resources res = page.ensureResources();
        COSDictionary extGs = res.getExtGState();
        if (extGs == null) {
            extGs = new COSDictionary();
            res.getCOSDictionary().set(COSName.of("ExtGState"), extGs);
        }
        // Re-use any existing entry whose /ca and /CA match.
        for (COSName key : extGs.keySet()) {
            org.aspose.pdf.engine.cos.COSBase val = extGs.get(key);
            if (val instanceof COSDictionary) {
                COSDictionary gs = (COSDictionary) val;
                double caVal = gs.getFloat("ca", -1f);
                double upperCa = gs.getFloat("CA", -1f);
                if (Math.abs(caVal - alpha) < 1e-6 && Math.abs(upperCa - alpha) < 1e-6) {
                    return key.getName();
                }
            }
        }
        // Build a stable name like "GS18" for alpha=0.18.
        String name = "GS" + String.format(java.util.Locale.ROOT, "%02d",
                (int) Math.round(alpha * 100));
        int suffix = 2;
        while (extGs.get(name) != null) {
            name = "GS" + String.format(java.util.Locale.ROOT, "%02d",
                    (int) Math.round(alpha * 100)) + "_" + suffix++;
        }
        COSDictionary gs = new COSDictionary();
        gs.set(COSName.of("Type"), COSName.of("ExtGState"));
        gs.set(COSName.of("ca"), new COSFloat(alpha));
        gs.set(COSName.of("CA"), new COSFloat(alpha));
        extGs.set(COSName.of(name), gs);
        return name;
    }

    /** Returns RGB components in [0,1] for any {@link Color}, falling back to black. */
    static double[] toRgb(Color color) {
        if (color == null) return new double[]{0, 0, 0};
        return new double[]{color.getR(), color.getG(), color.getB()};
    }

    /** Escapes a string for a PDF literal {@code (...)} payload. */
    static String escapeLiteral(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 4);
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
        return out.toString();
    }
}
