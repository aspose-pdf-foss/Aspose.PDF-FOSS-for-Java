package org.aspose.pdf.engine.font.cff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the CFF Standard Strings table (Adobe TN #5176 Appendix A) against
 * ordering regressions. The table previously diverged from the spec after SID
 * 149, so accented glyphs resolved to wrong names (corpus 35126: adieresis
 * SID 202 came back as "Ncommaaccent" and every "ä" vanished from the render).
 */
class CFFStandardStringsTest {

    @Test
    void tableHas391Entries() {
        assertEquals(391, CFFStandardStrings.NAMES.length);
    }

    @Test
    void anchorSidsMatchAppendixA() {
        assertEquals(".notdef", CFFStandardStrings.lookup(0));
        assertEquals("space", CFFStandardStrings.lookup(1));
        assertEquals("A", CFFStandardStrings.lookup(34));
        assertEquals("z", CFFStandardStrings.lookup(91));
        assertEquals("germandbls", CFFStandardStrings.lookup(149));
        assertEquals("onesuperior", CFFStandardStrings.lookup(150));
        assertEquals("copyright", CFFStandardStrings.lookup(170));
        assertEquals("Aacute", CFFStandardStrings.lookup(171));
        assertEquals("Adieresis", CFFStandardStrings.lookup(173));
        assertEquals("Zcaron", CFFStandardStrings.lookup(199));
        assertEquals("aacute", CFFStandardStrings.lookup(200));
        assertEquals("adieresis", CFFStandardStrings.lookup(202));
        assertEquals("ecircumflex", CFFStandardStrings.lookup(208));
        assertEquals("egrave", CFFStandardStrings.lookup(210));
        assertEquals("zcaron", CFFStandardStrings.lookup(228));
        assertEquals("exclamsmall", CFFStandardStrings.lookup(229));
        assertEquals("ff", CFFStandardStrings.lookup(266));
        assertEquals("colonmonetary", CFFStandardStrings.lookup(300));
        assertEquals("commainferior", CFFStandardStrings.lookup(346));
        assertEquals("Ydieresissmall", CFFStandardStrings.lookup(378));
        assertEquals("Semibold", CFFStandardStrings.lookup(390));
    }

    @Test
    void outOfRangeSidsFallBackToNotdef() {
        assertEquals(".notdef", CFFStandardStrings.lookup(391));
        assertEquals(".notdef", CFFStandardStrings.lookup(-1));
    }
}
