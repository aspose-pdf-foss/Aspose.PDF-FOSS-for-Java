package org.aspose.pdf.engine.colorspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the print-parity CMYK lattice and its RGB→ink inverse used for
 * ink-space transparency flattening (corpus 46878: a rich-black sidebar at
 * /ca 0.35 must composite far darker than linear RGB mixing).
 */
class CmykPrintLutTest {

    @Test
    void latticeCornersMatchMeasurement() {
        // white paper and solid magenta, measured from the printed chart
        assertEquals(0xFFFFFFFF, CmykPrintLut.toRGBInt(0, 0, 0, 0));
        int magenta = CmykPrintLut.toRGBInt(0, 1, 0, 0);
        assertEquals(246, (magenta >> 16) & 0xFF);
        assertEquals(86, (magenta >> 8) & 0xFF);
        assertEquals(160, magenta & 0xFF);
    }

    @Test
    void inverseRoundTripsThroughTheLattice() {
        double[][] probes = {
                {0, 0, 0, 0.6}, {0.977, 0.745, 0.025, 0}, {0.5, 0, 0, 0},
                {0.742, 0.676, 0.668, 0.895}, {0, 0, 0, 0}};
        for (double[] p : probes) {
            int rgb = CmykPrintLut.toRGBInt(p[0], p[1], p[2], p[3]);
            double[] ink = CmykPrintLut.inverse(rgb);
            double[] back = CmykPrintLut.toRGB(ink[0], ink[1], ink[2], ink[3]);
            for (int ch = 0; ch < 3; ch++) {
                double want = (rgb >> (16 - 8 * ch)) & 0xFF;
                assertTrue(Math.abs(back[ch] - want) <= 4,
                        "channel " + ch + " off by " + Math.abs(back[ch] - want)
                                + " for cmyk " + java.util.Arrays.toString(p));
            }
        }
    }

    @Test
    void inactiveWithoutParityFlag() {
        // the suite runs without -Drender.acrobatPrintParity — both switches off
        assertTrue(!CmykPrintLut.active() || Boolean.getBoolean("render.acrobatPrintParity"));
    }
}
