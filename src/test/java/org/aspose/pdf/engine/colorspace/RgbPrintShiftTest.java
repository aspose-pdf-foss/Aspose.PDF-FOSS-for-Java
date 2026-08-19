package org.aspose.pdf.engine.colorspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RgbPrintShift} — the Acrobat print-parity AdobeRGB (1998)
 * &rarr; sRGB shift applied to untagged DeviceRGB on transparency pages.
 * The expected values below were measured from Acrobat-printed golds of our
 * own patch charts (visual-mass experiments 99994/99997, 52765 chart family).
 */
public class RgbPrintShiftTest {

    @AfterEach
    void cleanup() {
        RgbPrintShift.clear();
        System.clearProperty("render.acrobatPrintParity");
        System.clearProperty("render.printParityRgbShift");
    }

    private static void assertShift(int r, int g, int b, int er, int eg, int eb, int tol) {
        int out = RgbPrintShift.shift(0xFF000000 | (r << 16) | (g << 8) | b);
        int or = (out >> 16) & 0xFF, og = (out >> 8) & 0xFF, ob = out & 0xFF;
        assertTrue(Math.abs(or - er) <= tol && Math.abs(og - eg) <= tol
                        && Math.abs(ob - eb) <= tol,
                String.format("(%d,%d,%d) -> (%d,%d,%d), expected (%d,%d,%d)",
                        r, g, b, or, og, ob, er, eg, eb));
    }

    /** Measured chart colors (52765 family, exact match on the printed gold). */
    @Test
    public void measuredChartColors() {
        assertShift(193, 16, 160, 226, 7, 165, 1);
        assertShift(255, 184, 28, 255, 185, 0, 1);
    }

    /** Neutrals stay neutral; the tone curves diverge in the shadows.
     *  Expected values are the measured 99994 gold: 0.125→26, 0.25→62,
     *  0.375→95, 0.5→129. */
    @Test
    public void neutralsFollowMeasuredToneCurve() {
        assertShift(255, 255, 255, 255, 255, 255, 0);
        assertShift(0, 0, 0, 0, 0, 0, 0);
        assertShift(32, 32, 32, 26, 26, 26, 1);
        assertShift(64, 64, 64, 62, 62, 62, 1);
        assertShift(96, 96, 96, 95, 95, 95, 1);
        assertShift(128, 128, 128, 129, 129, 129, 1);
    }

    /** Saturated cyan-ish colors lose their red component (gamut mapping). */
    @Test
    public void saturatedCyanCrushesRed() {
        int out = RgbPrintShift.shift(0xFF000000 | (45 << 16) | (109 << 8) | 146);
        assertEquals(0, (out >> 16) & 0xFF);
        assertTrue(Math.abs(((out >> 8) & 0xFF) - 109) <= 1);
        assertTrue(Math.abs((out & 0xFF) - 148) <= 2);
    }

    /** The alpha byte passes through untouched. */
    @Test
    public void alphaPreserved() {
        int out = RgbPrintShift.shift(0x7F000000 | (10 << 16) | (200 << 8) | 30);
        assertEquals(0x7F, out >>> 24);
    }

    /** Inactive without the print-parity property, active with it, cleared after. */
    @Test
    public void activationLifecycle() {
        assertFalse(RgbPrintShift.active());
        RgbPrintShift.setActive(true);
        assertFalse(RgbPrintShift.active(), "must stay off without acrobatPrintParity");

        System.setProperty("render.acrobatPrintParity", "true");
        RgbPrintShift.setActive(true);
        assertTrue(RgbPrintShift.active());
        RgbPrintShift.clear();
        assertFalse(RgbPrintShift.active());

        System.setProperty("render.printParityRgbShift", "false");
        RgbPrintShift.setActive(true);
        assertFalse(RgbPrintShift.active(), "kill switch must win");
    }

    /** unshift inverts shift within rounding for in-gamut colors. */
    @Test
    public void unshiftRoundTrip() {
        int[] samples = {0xFFC110A0, 0xFFFFB81C, 0xFF808080, 0xFF204060, 0xFFFFF67F};
        for (int argb : samples) {
            int rt = RgbPrintShift.shift(RgbPrintShift.unshift(argb));
            for (int sh = 0; sh <= 16; sh += 8) {
                assertTrue(Math.abs(((rt >> sh) & 0xFF) - ((argb >> sh) & 0xFF)) <= 1,
                        String.format("%08X -> %08X", argb, rt));
            }
        }
    }

    /** Working-space Multiply: gold 15764 overlap = shift(Y*Y), not
     *  shift(Y)*shift(Y) — the measured overlap is (255,238,30). */
    @Test
    public void workingSpaceMultiplyMatchesGold() {
        // Y = (1, 0.963, 0.5) -> (255, 245.6, 127.5)
        int y = 0xFFFFF680; // 255,246,128 (8-bit quantized paint value)
        int yu = RgbPrintShift.unshift(RgbPrintShift.shift(y));
        int r = ((yu >> 16) & 0xFF) * ((yu >> 16) & 0xFF) / 255;
        int g = ((yu >> 8) & 0xFF) * ((yu >> 8) & 0xFF) / 255;
        int b = (yu & 0xFF) * (yu & 0xFF) / 255;
        int out = RgbPrintShift.shift(0xFF000000 | (r << 16) | (g << 8) | b);
        assertTrue(Math.abs(((out >> 16) & 0xFF) - 255) <= 1, "R " + ((out >> 16) & 0xFF));
        assertTrue(Math.abs(((out >> 8) & 0xFF) - 238) <= 2, "G " + ((out >> 8) & 0xFF));
        assertTrue(Math.abs((out & 0xFF) - 30) <= 3, "B " + (out & 0xFF));
    }

    /** shiftImage transforms every pixel like shift(). */
    @Test
    public void shiftImageMatchesScalar() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                2, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, 0xC110A0);
        img.setRGB(1, 0, 0xFFB81C);
        RgbPrintShift.shiftImage(img);
        assertEquals(RgbPrintShift.shift(0xFFC110A0) & 0xFFFFFF, img.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(RgbPrintShift.shift(0xFFFFB81C) & 0xFFFFFF, img.getRGB(1, 0) & 0xFFFFFF);
    }
}
