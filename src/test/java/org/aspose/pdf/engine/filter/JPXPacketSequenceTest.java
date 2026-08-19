package org.aspose.pdf.engine.filter;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards {@link JPXDecodeFilter#packetSequence} — the §B.12 progression-order
 * packet walk. Regression for corpus 57776.pdf (visual-mass 00434): an RLCP
 * codestream decoded as LRCP scrambles every code-block into noise.
 */
public class JPXPacketSequenceTest {

    private static String render(int[][] seq) {
        StringBuilder sb = new StringBuilder();
        for (int[] p : seq) sb.append('L').append(p[0]).append('R').append(p[1]).append('C').append(p[2]).append(' ');
        return sb.toString().trim();
    }

    @Test
    public void lrcpIsLayerOuterComponentInner() {
        assertEquals("L0R0C0 L0R0C1 L0R1C0 L0R1C1 L1R0C0 L1R0C1 L1R1C0 L1R1C1",
                render(JPXDecodeFilter.packetSequence(0, 2, 2, 2)));
    }

    @Test
    public void rlcpIsResolutionOuterComponentInner() {
        assertEquals("L0R0C0 L0R0C1 L1R0C0 L1R0C1 L0R1C0 L0R1C1 L1R1C0 L1R1C1",
                render(JPXDecodeFilter.packetSequence(1, 2, 2, 2)));
    }

    @Test
    public void rpclIsResolutionOuterLayerInner() {
        assertEquals("L0R0C0 L1R0C0 L0R0C1 L1R0C1 L0R1C0 L1R1C0 L0R1C1 L1R1C1",
                render(JPXDecodeFilter.packetSequence(2, 2, 2, 2)));
    }

    @Test
    public void pcrlAndCprlAreComponentOuterLayerInner() {
        String expected = "L0R0C0 L1R0C0 L0R1C0 L1R1C0 L0R0C1 L1R0C1 L0R1C1 L1R1C1";
        assertEquals(expected, render(JPXDecodeFilter.packetSequence(3, 2, 2, 2)));
        assertEquals(expected, render(JPXDecodeFilter.packetSequence(4, 2, 2, 2)));
    }

    @Test
    public void everyOrderVisitsEachPacketExactlyOnce() {
        for (int order = 0; order <= 4; order++) {
            int[][] seq = JPXDecodeFilter.packetSequence(order, 6, 6, 3);
            assertEquals(6 * 6 * 3, seq.length, "order " + order);
            long distinct = Arrays.stream(seq).map(Arrays::toString).distinct().count();
            assertEquals(6 * 6 * 3, distinct, "order " + order + " must not repeat packets");
        }
    }

    @Test
    public void unknownOrderFallsBackToLrcp() {
        assertArrayEquals(JPXDecodeFilter.packetSequence(0, 3, 2, 2)[5],
                JPXDecodeFilter.packetSequence(9, 3, 2, 2)[5]);
    }
}
