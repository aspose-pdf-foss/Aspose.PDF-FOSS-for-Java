package org.aspose.pdf.annotations;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreeTextEndingStyleTest {

    @Test
    void newFreeText_hasNoneByDefault() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            FreeTextAnnotation ft = new FreeTextAnnotation(p, new Rectangle(0, 0, 100, 50));
            assertEquals(LineEnding.None, ft.getEndingStyle());
        }
    }

    @Test
    void setEndingStyle_roundTrips() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            FreeTextAnnotation ft = new FreeTextAnnotation(p, new Rectangle(0, 0, 100, 50));
            ft.setEndingStyle(LineEnding.OpenArrow);
            assertEquals(LineEnding.OpenArrow, ft.getEndingStyle());

            ft.setEndingStyle(LineEnding.Diamond);
            assertEquals(LineEnding.Diamond, ft.getEndingStyle());
        }
    }

    @Test
    void setEndingStyle_nullOrNone_removesEntry() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            FreeTextAnnotation ft = new FreeTextAnnotation(p, new Rectangle(0, 0, 100, 50));
            ft.setEndingStyle(LineEnding.Square);
            assertNotNull(ft.getCOSDictionary().get("LE"));

            ft.setEndingStyle(null);
            assertNull(ft.getCOSDictionary().get("LE"));

            ft.setEndingStyle(LineEnding.Butt);
            assertNotNull(ft.getCOSDictionary().get("LE"));
            ft.setEndingStyle(LineEnding.None);
            assertNull(ft.getCOSDictionary().get("LE"));
        }
    }
}
