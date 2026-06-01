package org.aspose.pdf;

import org.aspose.pdf.operators.BT;
import org.aspose.pdf.operators.ConcatenateMatrix;
import org.aspose.pdf.operators.ET;
import org.aspose.pdf.operators.GRestore;
import org.aspose.pdf.operators.GSave;
import org.aspose.pdf.operators.LineTo;
import org.aspose.pdf.operators.MoveTo;
import org.aspose.pdf.operators.Re;
import org.aspose.pdf.operators.SetTextMatrix;
import org.aspose.pdf.operators.ShowText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PageCalculateContentBBoxTest {

    @Test
    void emptyPage_returnsCropBoxFallback() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Rectangle bbox = page.calculateContentBBox();
            assertNotNull(bbox);
            // Empty content → falls back to crop box (== media box for new page)
            Rectangle crop = page.getCropBox();
            assertEquals(crop.getLLX(), bbox.getLLX(), 1e-3);
            assertEquals(crop.getLLY(), bbox.getLLY(), 1e-3);
            assertEquals(crop.getURX(), bbox.getURX(), 1e-3);
            assertEquals(crop.getURY(), bbox.getURY(), 1e-3);
        }
    }

    @Test
    void singleRectangle_returnsExactBBox() {
        Page.ContentBBoxCalculator calc = new Page.ContentBBoxCalculator();
        calc.visit(new Re(100, 200, 50, 30));
        Rectangle r = calc.toRectangle();
        assertNotNull(r);
        assertEquals(100, r.getLLX(), 1e-3);
        assertEquals(200, r.getLLY(), 1e-3);
        assertEquals(150, r.getURX(), 1e-3);
        assertEquals(230, r.getURY(), 1e-3);
    }

    @Test
    void rectangleUnderTranslateCTM_offsetsAppropriately() {
        Page.ContentBBoxCalculator calc = new Page.ContentBBoxCalculator();
        calc.visit(new GSave());
        calc.visit(new ConcatenateMatrix(1, 0, 0, 1, 10, 20)); // translate (10,20)
        calc.visit(new Re(0, 0, 5, 5));
        calc.visit(new GRestore());
        Rectangle r = calc.toRectangle();
        assertNotNull(r);
        assertEquals(10, r.getLLX(), 1e-3);
        assertEquals(20, r.getLLY(), 1e-3);
        assertEquals(15, r.getURX(), 1e-3);
        assertEquals(25, r.getURY(), 1e-3);
    }

    @Test
    void ctmIsPoppedOnGRestore() {
        Page.ContentBBoxCalculator calc = new Page.ContentBBoxCalculator();
        calc.visit(new GSave());
        calc.visit(new ConcatenateMatrix(1, 0, 0, 1, 1000, 1000)); // far away
        calc.visit(new GRestore());
        // Now CTM is back to identity — rectangle at origin should land at origin
        calc.visit(new Re(0, 0, 10, 10));
        Rectangle r = calc.toRectangle();
        assertEquals(0, r.getLLX(), 1e-3);
        assertEquals(10, r.getURX(), 1e-3);
    }

    @Test
    void pathBuiltByMoveLineTo_includesAllPoints() {
        Page.ContentBBoxCalculator calc = new Page.ContentBBoxCalculator();
        calc.visit(new MoveTo(10, 10));
        calc.visit(new LineTo(50, 20));
        calc.visit(new LineTo(15, 80));
        Rectangle r = calc.toRectangle();
        assertEquals(10, r.getLLX(), 1e-3);
        assertEquals(10, r.getLLY(), 1e-3);
        assertEquals(50, r.getURX(), 1e-3);
        assertEquals(80, r.getURY(), 1e-3);
    }

    @Test
    void textShowOrigin_isIncluded() {
        Page.ContentBBoxCalculator calc = new Page.ContentBBoxCalculator();
        calc.visit(new BT());
        calc.visit(new SetTextMatrix(new Matrix(1, 0, 0, 1, 100, 700))); // origin (100,700)
        calc.visit(new ShowText("hi"));
        calc.visit(new ET());
        Rectangle r = calc.toRectangle();
        assertNotNull(r);
        // The origin point lands in the bbox; min == max == origin
        assertEquals(100, r.getLLX(), 1e-3);
        assertEquals(700, r.getLLY(), 1e-3);
    }
}
