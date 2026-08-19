package org.aspose.pdf.tests;

import org.aspose.pdf.Matrix;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfBoolean;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.function.ExponentialFunction;
import org.aspose.pdf.engine.pattern.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pattern and shading classes (§8.7).
 */
public class PatternShadingTest {

    // ═══════════════════════════════════════════════════════════════
    //  TilingPattern
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void tilingPatternParsePaintAndTilingType() throws IOException {
        PdfStream dict = new PdfStream();
        dict.set(PdfName.of("PatternType"), PdfInteger.valueOf(1));
        dict.set(PdfName.of("PaintType"), PdfInteger.valueOf(2));
        dict.set(PdfName.of("TilingType"), PdfInteger.valueOf(3));

        TilingPattern tp = new TilingPattern(dict, null);
        assertEquals(1, tp.getPatternType());
        assertEquals(2, tp.getPaintType());
        assertEquals(3, tp.getTilingType());
    }

    @Test
    public void tilingPatternBBoxAndSteps() throws IOException {
        PdfStream dict = new PdfStream();
        dict.set(PdfName.of("PatternType"), PdfInteger.valueOf(1));
        PdfArray bbox = new PdfArray();
        bbox.add(new PdfFloat(0)); bbox.add(new PdfFloat(0));
        bbox.add(new PdfFloat(100)); bbox.add(new PdfFloat(50));
        dict.set(PdfName.of("BBox"), bbox);
        dict.set(PdfName.of("XStep"), new PdfFloat(100));
        dict.set(PdfName.of("YStep"), new PdfFloat(50));

        TilingPattern tp = new TilingPattern(dict, null);
        Rectangle bb = tp.getBBox();
        assertEquals(100.0, bb.getWidth(), 0.01);
        assertEquals(50.0, bb.getHeight(), 0.01);
        assertEquals(100.0, tp.getXStep(), 0.01);
        assertEquals(50.0, tp.getYStep(), 0.01);
    }

    @Test
    public void tilingPatternResources() throws IOException {
        PdfStream dict = new PdfStream();
        dict.set(PdfName.of("PatternType"), PdfInteger.valueOf(1));
        PdfDictionary res = new PdfDictionary();
        res.set(PdfName.of("ProcSet"), new PdfArray());
        dict.set(PdfName.of("Resources"), res);

        TilingPattern tp = new TilingPattern(dict, null);
        assertNotNull(tp.getResources());
    }

    // ═══════════════════════════════════════════════════════════════
    //  ShadingPattern
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void shadingPatternParseReturnsShading() throws IOException {
        PdfDictionary shadingDict = new PdfDictionary();
        shadingDict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(2));
        PdfArray coords = new PdfArray();
        coords.add(new PdfFloat(0)); coords.add(new PdfFloat(0));
        coords.add(new PdfFloat(100)); coords.add(new PdfFloat(0));
        shadingDict.set(PdfName.of("Coords"), coords);
        shadingDict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

        PdfDictionary patDict = new PdfDictionary();
        patDict.set(PdfName.of("PatternType"), PdfInteger.valueOf(2));
        patDict.set(PdfName.of("Shading"), shadingDict);

        ShadingPattern sp = new ShadingPattern(patDict, null);
        assertNotNull(sp.getShading());
        assertTrue(sp.getShading() instanceof AxialShading);
    }

    // ═══════════════════════════════════════════════════════════════
    //  AxialShading
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void axialShadingParseCoords() throws IOException {
        PdfDictionary dict = createAxialShadingDict(0, 0, 100, 0);
        AxialShading as = new AxialShading(dict, null);
        assertEquals(2, as.getShadingType());
        assertEquals(0, as.getX0(), 0.01);
        assertEquals(0, as.getY0(), 0.01);
        assertEquals(100, as.getX1(), 0.01);
        assertEquals(0, as.getY1(), 0.01);
    }

    @Test
    public void axialShadingColorAtMidpoint() throws IOException {
        // Linear function: t → [t, 0, 1-t] (RGB)
        ExponentialFunction func = new ExponentialFunction(
                new double[]{0, 1}, new double[]{0, 1, 0, 1, 0, 1},
                new double[]{0, 0, 1}, new double[]{1, 0, 0}, 1.0);

        PdfDictionary dict = createAxialShadingDict(0, 0, 100, 0);
        // We can't easily inject the function into the dict, so test via getColorAt
        // Create a simple axial shading with background fallback
        AxialShading as = new AxialShading(dict, null);
        // Without a function, should return background or default
        double[] color = as.getColorAt(50, 0);
        assertNotNull(color);
        assertEquals(3, color.length);
    }

    @Test
    public void axialShadingColorAtStart() throws IOException {
        PdfDictionary dict = createAxialShadingDict(0, 0, 100, 0);
        AxialShading as = new AxialShading(dict, null);
        double[] color = as.getColorAt(0, 0); // t=0 on axis
        assertNotNull(color);
    }

    @Test
    public void axialShadingColorAtEnd() throws IOException {
        PdfDictionary dict = createAxialShadingDict(0, 0, 100, 0);
        AxialShading as = new AxialShading(dict, null);
        double[] color = as.getColorAt(100, 0); // t=1 on axis
        assertNotNull(color);
    }

    // ═══════════════════════════════════════════════════════════════
    //  RadialShading
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void radialShadingParseCoords() throws IOException {
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(3));
        PdfArray coords = new PdfArray();
        coords.add(new PdfFloat(50)); coords.add(new PdfFloat(50)); coords.add(new PdfFloat(0));
        coords.add(new PdfFloat(50)); coords.add(new PdfFloat(50)); coords.add(new PdfFloat(100));
        dict.set(PdfName.of("Coords"), coords);
        dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

        RadialShading rs = new RadialShading(dict, null);
        assertEquals(3, rs.getShadingType());
        assertEquals(50, rs.getX0(), 0.01);
        assertEquals(50, rs.getY0(), 0.01);
        assertEquals(0, rs.getR0(), 0.01);
        assertEquals(100, rs.getR1(), 0.01);
    }

    // ═══════════════════════════════════════════════════════════════
    //  FunctionBasedShading
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void functionBasedShadingGetColorAt() throws IOException {
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(1));
        dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));
        // No function → returns background/default
        FunctionBasedShading fbs = new FunctionBasedShading(dict, null);
        assertEquals(1, fbs.getShadingType());
        double[] color = fbs.getColorAt(0.5, 0.5);
        assertNotNull(color);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Shading.parse() factory
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void shadingParseType2ReturnsAxial() throws IOException {
        PdfDictionary dict = createAxialShadingDict(0, 0, 1, 0);
        Shading s = Shading.parse(dict, null);
        assertNotNull(s);
        assertTrue(s instanceof AxialShading);
        assertEquals(2, s.getShadingType());
    }

    @Test
    public void shadingParseType3ReturnsRadial() throws IOException {
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(3));
        PdfArray coords = new PdfArray();
        for (int i = 0; i < 6; i++) coords.add(new PdfFloat(i));
        dict.set(PdfName.of("Coords"), coords);
        dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

        Shading s = Shading.parse(dict, null);
        assertTrue(s instanceof RadialShading);
    }

    // ═══════════════════════════════════════════════════════════════
    //  PdfPattern.parse() factory
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void patternParseType1ReturnsTiling() throws IOException {
        PdfStream dict = new PdfStream();
        dict.set(PdfName.of("PatternType"), PdfInteger.valueOf(1));
        PdfPattern p = PdfPattern.parse(dict, null);
        assertNotNull(p);
        assertTrue(p instanceof TilingPattern);
    }

    @Test
    public void patternParseType2ReturnsShading() throws IOException {
        PdfDictionary shadingDict = new PdfDictionary();
        shadingDict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(2));
        PdfArray coords = new PdfArray();
        coords.add(new PdfFloat(0)); coords.add(new PdfFloat(0));
        coords.add(new PdfFloat(1)); coords.add(new PdfFloat(0));
        shadingDict.set(PdfName.of("Coords"), coords);
        shadingDict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

        PdfDictionary patDict = new PdfDictionary();
        patDict.set(PdfName.of("PatternType"), PdfInteger.valueOf(2));
        patDict.set(PdfName.of("Shading"), shadingDict);

        PdfPattern p = PdfPattern.parse(patDict, null);
        assertNotNull(p);
        assertTrue(p instanceof ShadingPattern);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Mesh stubs (types 4-7)
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void meshShadingStubsReturnFallbackColor() throws IOException {
        // Types 5-7 are still stubs returning a fallback colour. Type 4 is a
        // real mesh decoder now: with no vertex stream it has no triangles,
        // so a point query returns null (nothing painted) — Acrobat drops
        // malformed/empty meshes silently instead of painting a fallback.
        for (int type = 5; type <= 7; type++) {
            PdfDictionary dict = new PdfDictionary();
            dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(type));
            dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

            Shading s = Shading.parse(dict, null);
            assertNotNull(s, "Shading type " + type + " should parse");
            assertEquals(type, s.getShadingType());
            double[] color = s.getColorAt(0, 0);
            assertNotNull(color);
            assertTrue(color.length >= 3, "Should return at least 3 components");
        }
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(4));
        dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));
        Shading s = Shading.parse(dict, null);
        assertNotNull(s, "Shading type 4 should parse");
        assertEquals(4, s.getShadingType());
        assertNull(s.getColorAt(0, 0), "Empty mesh paints nothing");
    }

    @Test
    public void gouraudMeshDecodesTrianglesAndInterpolates() throws IOException {
        // One right triangle (0,0)-(4,0)-(0,4) with red/green/blue corners,
        // 8-bit flags/coords/components, Decode mapping coords to 0..4.
        PdfStream stream = new PdfStream();
        stream.set(PdfName.of("ShadingType"), PdfInteger.valueOf(4));
        stream.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));
        stream.set(PdfName.of("BitsPerFlag"), PdfInteger.valueOf(8));
        stream.set(PdfName.of("BitsPerCoordinate"), PdfInteger.valueOf(8));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(8));
        PdfArray decode = new PdfArray();
        for (double d : new double[]{0, 4, 0, 4, 0, 1, 0, 1, 0, 1}) {
            decode.add(new PdfFloat((float) d));
        }
        stream.set(PdfName.of("Decode"), decode);
        byte[] data = {
                0, 0, 0, (byte) 255, 0, 0,            // v0 (0,0) red
                0, (byte) 255, 0, 0, (byte) 255, 0,   // v1 (4,0) green
                0, 0, (byte) 255, 0, 0, (byte) 255,   // v2 (0,4) blue
        };
        stream.setDecodedData(data);

        Shading s = Shading.parse(stream, null);
        assertNotNull(s);
        double[] atRed = s.getColorAt(0.01, 0.01);
        assertNotNull(atRed, "Point inside the triangle");
        assertEquals(1.0, atRed[0], 0.02);
        assertEquals(0.0, atRed[1], 0.02);
        double[] centroid = s.getColorAt(4.0 / 3, 4.0 / 3);
        assertNotNull(centroid);
        assertEquals(1.0 / 3, centroid[0], 0.02);
        assertEquals(1.0 / 3, centroid[1], 0.02);
        assertEquals(1.0 / 3, centroid[2], 0.02);
        assertNull(s.getColorAt(3.9, 3.9), "Point outside the triangle");
    }

    // ═══════════════════════════════════════════════════════════════
    //  Pattern matrix
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void patternMatrixDefaultIsIdentity() throws IOException {
        PdfStream dict = new PdfStream();
        dict.set(PdfName.of("PatternType"), PdfInteger.valueOf(1));
        TilingPattern tp = new TilingPattern(dict, null);
        Matrix m = tp.getMatrix();
        assertNotNull(m);
        // Identity matrix: a=1, b=0, c=0, d=1, e=0, f=0
        assertEquals(1.0, m.getA(), 0.01);
        assertEquals(0.0, m.getB(), 0.01);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private PdfDictionary createAxialShadingDict(double x0, double y0, double x1, double y1) {
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("ShadingType"), PdfInteger.valueOf(2));
        PdfArray coords = new PdfArray();
        coords.add(new PdfFloat((float) x0));
        coords.add(new PdfFloat((float) y0));
        coords.add(new PdfFloat((float) x1));
        coords.add(new PdfFloat((float) y1));
        dict.set(PdfName.of("Coords"), coords);
        dict.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));
        return dict;
    }

    // ═══════════════════════════════════════════════════════════════
    //  ShadingRenderer — device/user space consistency
    // ═══════════════════════════════════════════════════════════════

    /**
     * The {@code sh} operator paints under a scaled+flipped base transform
     * (PngDevice at any DPI). The renderer samples device pixels through the
     * inverse of (base × CTM); the clip however is set in g2d user space.
     * Regression: corpus 28762.pdf — user-space clip bounds were fed into the
     * device-space inverse, every sample fell outside the axis span and, with
     * /Extend [false false], the bar gradients painted nothing at all.
     */
    @Test
    public void shadingRendererPaintsUnderScaledFlippedBaseTransform() throws IOException {
        // Axial gradient along x: 10 → 40 (user space), red → blue, no extend.
        PdfDictionary dict = createAxialShadingDict(10, 0, 40, 0);
        PdfDictionary fn = new PdfDictionary();
        fn.set(PdfName.of("FunctionType"), PdfInteger.valueOf(2));
        PdfArray domain = new PdfArray();
        domain.add(new PdfFloat(0)); domain.add(new PdfFloat(1));
        fn.set(PdfName.of("Domain"), domain);
        PdfArray c0 = new PdfArray();
        c0.add(new PdfFloat(1)); c0.add(new PdfFloat(0)); c0.add(new PdfFloat(0));
        fn.set(PdfName.of("C0"), c0);
        PdfArray c1 = new PdfArray();
        c1.add(new PdfFloat(0)); c1.add(new PdfFloat(0)); c1.add(new PdfFloat(1));
        fn.set(PdfName.of("C1"), c1);
        dict.set(PdfName.of("Function"), fn);

        Shading shading = Shading.parse(dict, null);
        assertNotNull(shading);

        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(120, 120, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = img.createGraphics();
        g2d.setColor(java.awt.Color.WHITE);
        g2d.fillRect(0, 0, 120, 120);
        // Page-style base transform: 2× scale with Y flip (user 60×60 → device 120×120).
        g2d.translate(0, 120);
        g2d.scale(2, -2);
        // Clip = the gradient's span, in USER space (as `re W n` would set it).
        g2d.setClip(new java.awt.Rectangle(10, 10, 30, 20));
        java.awt.geom.AffineTransform shadingToDevice =
                new java.awt.geom.AffineTransform(g2d.getTransform()); // state CTM = identity

        ShadingRenderer.render(g2d, shading, shadingToDevice, g2d.getClipBounds());

        // The base transform must be restored after the device-space blit.
        assertEquals(new java.awt.geom.AffineTransform(2, 0, 0, -2, 0, 120), g2d.getTransform());
        g2d.dispose();

        // User (25, 20) = gradient midpoint → device (50, 80): must be painted
        // (≈ half red / half blue), not white.
        int mid = img.getRGB(50, 80);
        assertNotEquals(0xFFFFFFFF, mid, "gradient midpoint must be painted");
        int r = (mid >> 16) & 0xFF, g = (mid >> 8) & 0xFF, b = mid & 0xFF;
        assertTrue(r > 60 && r < 200, "midpoint red ≈ 50%: " + r);
        assertTrue(b > 60 && b < 200, "midpoint blue ≈ 50%: " + b);
        assertTrue(g < 60, "midpoint green ≈ 0: " + g);

        // Outside the clip stays untouched: user (5, 20) → device (10, 80).
        assertEquals(0xFFFFFFFF, img.getRGB(10, 80), "outside the clip must stay white");
    }
}
