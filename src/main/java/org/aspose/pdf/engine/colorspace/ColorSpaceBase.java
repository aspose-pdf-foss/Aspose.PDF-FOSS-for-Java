package org.aspose.pdf.engine.colorspace;

import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Abstract base for all PDF color spaces (ISO 32000-1:2008, §8.6).
 * <p>
 * PDF defines three families of color spaces:
 * <ul>
 *   <li>Device: DeviceRGB, DeviceCMYK, DeviceGray</li>
 *   <li>CIE-based: CalRGB, CalGray, Lab, ICCBased</li>
 *   <li>Special: Indexed, Separation, DeviceN, Pattern</li>
 * </ul>
 * The {@link #resolve} factory method parses a color space specification
 * from a PdfName or PdfArray into the appropriate subclass.
 * </p>
 */
public abstract class ColorSpaceBase {

    private static final Logger LOG = Logger.getLogger(ColorSpaceBase.class.getName());

    /**
     * Returns the color space name (e.g., "DeviceRGB", "ICCBased", "Indexed").
     *
     * @return the color space name
     */
    public abstract String getName();

    /**
     * Returns the number of color components in this color space.
     *
     * @return 1 for gray, 3 for RGB, 4 for CMYK, etc.
     */
    public abstract int getNumberOfComponents();

    /**
     * Converts component values in THIS color space (each typically 0..1)
     * to a packed ARGB int (alpha=0xFF).
     * <p>
     * The base implementation maps by component count — 1 = gray,
     * 3 = RGB, 4 = CMYK — which is correct for the Device* families and a
     * sane fallback for everything else. Subclasses with richer semantics
     * (Separation/DeviceN tint transforms, Indexed palette lookup, Lab,
     * CalRGB/CalGray, ICCBased alternate) override this. Shading functions
     * and image samples MUST go through this method rather than assuming
     * RGB — a DeviceCMYK shading read as RGB turns blue into orange
     * (corpus 29077/10734).
     * </p>
     *
     * @param comps the component values in this color space
     * @return packed ARGB int (alpha=0xFF); black when comps is null/empty
     */
    public int toRGBInt(double[] comps) {
        if (comps == null || comps.length == 0) return 0xFF000000;
        switch (comps.length) {
            case 1:
                return DeviceGray.INSTANCE.toRGBInt(comps[0]);
            case 4:
                // Rendering pipeline → press-characterized display conversion
                // (the algebraic DeviceCMYK.toRGBInt(c,m,y,k) stays reserved
                // for the public API contract).
                return CmykDisplay.toRGBInt(comps);
            case 3:
            default:
                // Only plain DeviceRGB reaches this dispatch (tagged spaces —
                // CalRGB/Lab/ICC — override toRGBInt(double[]) and merely
                // delegate their final packing to DeviceRGB.toRGBInt), so the
                // untagged print-parity shift belongs here, not inside
                // DeviceRGB itself, or tagged colors would be double-shifted.
                int rgb = DeviceRGB.INSTANCE.toRGBInt(comps[0], comps[1],
                        comps.length > 2 ? comps[2] : 0);
                return RgbPrintShift.active() ? RgbPrintShift.shift(rgb) : rgb;
        }
    }

    /**
     * Resolves a color space from a PDF object.
     * <p>
     * Handles PdfName (e.g., /DeviceRGB) and PdfArray (e.g., [/ICCBased stream]).
     * </p>
     *
     * @param csObj     the color space object (PdfName or PdfArray)
     * @param resources the page resources for named color space lookup (may be null)
     * @param parser    the PDF parser for resolving indirect refs (may be null)
     * @return the resolved color space, or DeviceRGB as default
     * @throws IOException if resolution fails
     */
    /**
     * Identity-keyed cache of resolved composite color spaces. Content streams
     * re-select spaces constantly ("/CS1 cs … /CS0 cs …" per text run); without
     * a cache every {@code cs} operator re-parses the ICC profile and creates a
     * fresh native LCMS transform — observed as render workers burning 15+ CPU
     * minutes inside {@code LCMS.createNativeTransform} on a single page. The
     * resources dictionary hands back the SAME PdfArray instance for a given
     * name, so identity keys hit reliably ({@code PdfArray.equals} is deep
     * value equality — unusable here both for cost and for cross-document
     * collisions).
     *
     * <p>The keys are held weakly: a resolved value such as
     * {@link ICCBasedColorSpace} keeps a back-reference to its {@code PDFParser},
     * so a plain strong-keyed static cache pins a whole document graph per
     * cached colour space — 256 of those exhaust the heap in a long
     * convert/render loop. With weak keys, once a document is closed and dropped
     * its colour-space arrays become unreachable and the entries evaporate.
     * ({@link org.aspose.pdf.engine.parser.PDFParser#close()} additionally drops
     * the object cache and trailer, so the retained value's parser back-reference
     * is lightweight.)</p>
     */
    private static final org.aspose.pdf.engine.util.WeakIdentityHashMap<PdfBase, ColorSpaceBase>
            RESOLVE_CACHE = new org.aspose.pdf.engine.util.WeakIdentityHashMap<>();

    public static ColorSpaceBase resolve(PdfBase csObj, Resources resources,
                                          PDFParser parser) throws IOException {
        if (csObj == null) {
            return DeviceRGB.INSTANCE;
        }
        csObj = resolveRef(csObj);

        if (csObj instanceof PdfName) {
            return resolveByName(((PdfName) csObj).getName(), resources, parser);
        }

        if (csObj instanceof PdfArray) {
            ColorSpaceBase cached = RESOLVE_CACHE.get(csObj);
            if (cached != null) {
                return cached;
            }
            ColorSpaceBase resolved = resolveFromArray((PdfArray) csObj, resources, parser);
            RESOLVE_CACHE.put(csObj, resolved);
            return resolved;
        }

        return DeviceRGB.INSTANCE;
    }

    private static ColorSpaceBase resolveByName(String name, Resources resources,
                                                 PDFParser parser) throws IOException {
        switch (name) {
            case "DeviceRGB":
            case "RGB":
                return DeviceRGB.INSTANCE;
            case "DeviceCMYK":
            case "CMYK":
                return DeviceCMYK.INSTANCE;
            case "DeviceGray":
            case "G":
                return DeviceGray.INSTANCE;
            case "Pattern":
                return PatternColorSpace.INSTANCE;
            default:
                // Look up in resources /ColorSpace dictionary
                if (resources != null) {
                    PdfDictionary csDict = resources.getColorSpaces();
                    if (csDict != null) {
                        PdfBase resolved = csDict.get(name);
                        if (resolved != null) {
                            resolved = resolveRef(resolved);
                            return resolve(resolved, null, parser);
                        }
                    }
                }
                LOG.fine(() -> "Unknown color space name: " + name + ", defaulting to DeviceRGB");
                return DeviceRGB.INSTANCE;
        }
    }

    private static ColorSpaceBase resolveFromArray(PdfArray arr, Resources resources,
                                                    PDFParser parser) throws IOException {
        if (arr.size() == 0) return DeviceRGB.INSTANCE;

        PdfBase firstElem = arr.get(0);
        if (!(firstElem instanceof PdfName)) return DeviceRGB.INSTANCE;

        String csType = ((PdfName) firstElem).getName();

        switch (csType) {
            case "ICCBased":
                if (arr.size() >= 2) {
                    PdfBase streamObj = resolveRef(arr.get(1));
                    if (streamObj instanceof PdfStream) {
                        return new ICCBasedColorSpace((PdfStream) streamObj, parser);
                    }
                }
                return DeviceRGB.INSTANCE;

            case "Indexed":
                return IndexedColorSpace.fromArray(arr, resources, parser);

            case "Separation":
                return SeparationColorSpace.fromArray(arr, resources, parser);

            case "DeviceN":
                return DeviceNColorSpace.fromArray(arr, resources, parser);

            case "CalRGB":
                if (arr.size() >= 2) {
                    PdfBase params = resolveRef(arr.get(1));
                    if (params instanceof PdfDictionary) {
                        return new CalRGBColorSpace((PdfDictionary) params);
                    }
                }
                return DeviceRGB.INSTANCE;

            case "CalGray":
                if (arr.size() >= 2) {
                    PdfBase params = resolveRef(arr.get(1));
                    if (params instanceof PdfDictionary) {
                        return new CalGrayColorSpace((PdfDictionary) params);
                    }
                }
                return DeviceGray.INSTANCE;

            case "Lab":
                if (arr.size() >= 2) {
                    PdfBase params = resolveRef(arr.get(1));
                    if (params instanceof PdfDictionary) {
                        return new LabColorSpace((PdfDictionary) params);
                    }
                }
                return DeviceRGB.INSTANCE;

            case "Pattern":
                return PatternColorSpace.INSTANCE;

            default:
                LOG.fine(() -> "Unknown color space array type: " + csType);
                return DeviceRGB.INSTANCE;
        }
    }

    /**
     * Resolves an indirect object reference.
     */
    protected static PdfBase resolveRef(PdfBase obj) throws IOException {
        if (obj instanceof PdfObjectReference) {
            return ((PdfObjectReference) obj).dereference();
        }
        return obj;
    }

    // ═══════════════════════════════════════════════════════════════
    //  CIE color conversion utilities
    // ═══════════════════════════════════════════════════════════════

    /**
     * Converts CIE XYZ (D65 illuminant) to sRGB (0..1 per component).
     * Uses the standard sRGB matrix and gamma transfer function.
     *
     * @param x the X tristimulus value
     * @param y the Y tristimulus value
     * @param z the Z tristimulus value
     * @return array of [r, g, b] each in 0..1
     */
    protected static double[] xyzToSRGB(double x, double y, double z) {
        // XYZ → linear RGB (sRGB matrix, D65 illuminant)
        double rl =  3.2406 * x - 1.5372 * y - 0.4986 * z;
        double gl = -0.9689 * x + 1.8758 * y + 0.0415 * z;
        double bl =  0.0557 * x - 0.2040 * y + 1.0570 * z;
        // Apply sRGB gamma
        return new double[]{srgbGamma(rl), srgbGamma(gl), srgbGamma(bl)};
    }

    /**
     * Applies the sRGB gamma transfer function to a linear component.
     */
    private static double srgbGamma(double linear) {
        linear = Math.max(0, Math.min(1, linear));
        if (linear <= 0.0031308) return 12.92 * linear;
        return 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
    }

    /**
     * Clamps a value to [0, 1].
     *
     * @param v the value
     * @return the clamped value
     */
    protected static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * Extracts a triple (3-element double array) from a dictionary key.
     * Returns the default if not present or wrong size.
     *
     * @param dict       the dictionary
     * @param key        the key
     * @param defaultVal the default triple
     * @return the extracted triple
     */
    protected static double[] getTriple(PdfDictionary dict, String key, double[] defaultVal) {
        double[] arr = getNumberArray(dict, key);
        return (arr != null && arr.length >= 3) ? arr : defaultVal;
    }

    /**
     * Extracts a numeric array from a dictionary entry.
     *
     * @param dict the dictionary
     * @param key  the key
     * @return array of doubles, or {@code null} if not present
     */
    protected static double[] getNumberArray(PdfDictionary dict, String key) {
        PdfBase val = dict.get(key);
        if (val instanceof PdfArray) {
            PdfArray arr = (PdfArray) val;
            double[] result = new double[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                result[i] = arr.getFloat(i, 0f);
            }
            return result;
        }
        return null;
    }
}
