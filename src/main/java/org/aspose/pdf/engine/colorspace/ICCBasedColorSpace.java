package org.aspose.pdf.engine.colorspace;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.parser.PDFParser;

import java.awt.color.ICC_ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.IOException;
import java.util.logging.Logger;

/**
 * ICCBased color space (ISO 32000-1:2008, §8.6.5.5).
 * <p>
 * Wraps an ICC profile stream. The embedded profile is parsed with the
 * JDK's own {@link java.awt.color.ICC_Profile} / {@link ICC_ColorSpace}
 * (no external dependency) and used for the actual conversion; if the
 * profile is absent or malformed we fall back to the /Alternate color
 * space (or a Device* space derived from /N).
 * </p>
 */
public class ICCBasedColorSpace extends ColorSpaceBase {

    private static final Logger LOG = Logger.getLogger(ICCBasedColorSpace.class.getName());

    private final int numComponents;
    private final ColorSpaceBase alternate;
    /** JDK color space built from the embedded profile; null = use alternate. */
    private final ICC_ColorSpace iccColorSpace;

    /**
     * Creates an ICCBasedColorSpace from a profile stream.
     *
     * @param iccStream the ICC profile stream (must have /N)
     * @param parser    the PDF parser (may be null)
     * @throws IOException if reading the stream dict fails
     */
    public ICCBasedColorSpace(PdfStream iccStream, PDFParser parser) throws IOException {
        this.numComponents = iccStream.getInt("N", 3);
        this.iccColorSpace = loadIccColorSpace(iccStream, numComponents);

        // Try /Alternate
        PdfBase alt = iccStream.get("Alternate");
        if (alt != null) {
            alt = resolveRef(alt);
            ColorSpaceBase resolved = ColorSpaceBase.resolve(alt, null, parser);
            if (resolved != null) {
                this.alternate = resolved;
                return;
            }
        }

        // Default based on N
        switch (numComponents) {
            case 1:
                this.alternate = DeviceGray.INSTANCE;
                break;
            case 4:
                this.alternate = DeviceCMYK.INSTANCE;
                break;
            default:
                this.alternate = DeviceRGB.INSTANCE;
                break;
        }
    }

    @Override
    public String getName() { return "ICCBased"; }

    @Override
    public int getNumberOfComponents() { return numComponents; }

    /**
     * Converts via the embedded ICC profile (JDK CMM) when available,
     * otherwise via the /Alternate (or N-derived Device*) color space.
     */
    @Override
    public int toRGBInt(double[] comps) {
        // Acrobat print-parity harness mode: Acrobat's print pipeline pushes
        // 4-component (CMYK) ICC content through the same press-to-monitor
        // transform as DeviceCMYK, so use the measured print lattice instead
        // of the JDK colorimetric CMM (see CmykPrintLut).
        if (numComponents == 4 && comps != null && comps.length >= 4
                && CmykPrintLut.active()) {
            return CmykPrintLut.toRGBInt(comps[0], comps[1], comps[2], comps[3]);
        }
        if (iccColorSpace != null && comps != null && comps.length >= numComponents) {
            try {
                float[] in = new float[numComponents];
                for (int i = 0; i < numComponents; i++) {
                    float min = iccColorSpace.getMinValue(i);
                    float max = iccColorSpace.getMaxValue(i);
                    // PDF components are 0..1; scale into the profile range
                    // (Lab profiles have L 0..100, a/b -128..127).
                    in[i] = (float) (min + comps[i] * (max - min));
                    if (min == 0f && max == 1f) in[i] = (float) comps[i];
                }
                float[] rgb = iccColorSpace.toRGB(in);
                return DeviceRGB.INSTANCE.toRGBInt(rgb[0], rgb[1], rgb[2]);
            } catch (Exception e) {
                LOG.fine(() -> "ICC conversion failed, using alternate: " + e.getMessage());
            }
        }
        return alternate.toRGBInt(comps);
    }

    /**
     * Parses the embedded ICC profile bytes with the JDK CMM. Returns null
     * (alternate-space fallback) when the data is missing, malformed, or the
     * component count does not match /N.
     */
    private static ICC_ColorSpace loadIccColorSpace(PdfStream iccStream, int n) {
        try {
            byte[] data = iccStream.getDecodedData();
            if (data == null || data.length < 128) return null;
            ICC_Profile profile = ICC_Profile.getInstance(data);
            ICC_ColorSpace cs = new ICC_ColorSpace(profile);
            if (cs.getNumComponents() != n) {
                LOG.fine(() -> "ICC profile components " + cs.getNumComponents()
                        + " != /N " + n + "; using alternate");
                return null;
            }
            return cs;
        } catch (Exception e) {
            LOG.fine(() -> "Embedded ICC profile unusable: " + e.getMessage());
            return null;
        }
    }

    /**
     * Returns the alternate (fallback) color space used for conversions.
     *
     * @return the alternate color space
     */
    public ColorSpaceBase getAlternate() { return alternate; }
}
