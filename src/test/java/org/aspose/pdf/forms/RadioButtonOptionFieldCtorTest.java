package org.aspose.pdf.forms;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RadioButtonOptionFieldCtorTest {

    @Test
    void noArgCtor_createsValidWidget() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        COSDictionary d = opt.getCOSDictionary();
        assertEquals("Annot",  ((COSName) d.get("Type")).getName());
        assertEquals("Widget", ((COSName) d.get("Subtype")).getName());
        assertEquals("Btn",    ((COSName) d.get("FT")).getName());
        assertNull(opt.getRect());
    }

    @Test
    void pageRectCtor_setsPageAndRect() throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            Rectangle r = new Rectangle(10, 20, 30, 40);
            RadioButtonOptionField opt = new RadioButtonOptionField(page, r);

            Rectangle got = opt.getRect();
            assertNotNull(got);
            assertEquals(10, got.getLLX(), 1e-6);
            assertEquals(20, got.getLLY(), 1e-6);
            assertEquals(30, got.getURX(), 1e-6);
            assertEquals(40, got.getURY(), 1e-6);

            // /P entry points to the page dict
            assertSame(page.getCOSDictionary(), opt.getCOSDictionary().get("P"));
        }
    }

    @Test
    void pageRectCtor_nullPage_nullRect_doesNotThrow() {
        RadioButtonOptionField opt = new RadioButtonOptionField((Page) null, (Rectangle) null);
        assertNotNull(opt.getCOSDictionary());
        assertNull(opt.getRect());
    }

    @Test
    void setOptionName_storesInAS_andAPN() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        opt.setOptionName("Yes");

        // /AS active state
        assertEquals("Yes", ((COSName) opt.getCOSDictionary().get("AS")).getName());

        // /AP/N contains both "Yes" and "Off"
        COSDictionary ap = (COSDictionary) opt.getCOSDictionary().get("AP");
        COSDictionary n = (COSDictionary) ap.get("N");
        assertNotNull(n.get("Yes"));
        assertNotNull(n.get("Off"));

        // getOptionName mirrors getOptionValue (non-"Off" key)
        assertEquals("Yes", opt.getOptionName());
        assertEquals("Yes", opt.getOptionValue());
    }

    @Test
    void setOptionName_replacesPreviousName() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        opt.setOptionName("Yes");
        opt.setOptionName("Maybe");

        assertEquals("Maybe", opt.getOptionName());
        COSDictionary n = (COSDictionary) ((COSDictionary) opt.getCOSDictionary().get("AP")).get("N");
        // "Yes" should be gone, "Maybe" and "Off" remain
        assertNull(n.get("Yes"));
        assertNotNull(n.get("Maybe"));
        assertNotNull(n.get("Off"));
    }

    @Test
    void setOptionName_null_clearsAS_keepsOff() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        opt.setOptionName("Yes");
        opt.setOptionName(null);

        assertNull(opt.getCOSDictionary().get("AS"));
        COSDictionary n = (COSDictionary) ((COSDictionary) opt.getCOSDictionary().get("AP")).get("N");
        assertNotNull(n.get("Off"));
    }

    @Test
    void setWidth_resizesRect() {
        RadioButtonOptionField opt = new RadioButtonOptionField((Page) null, new Rectangle(10, 20, 30, 40));
        opt.setWidth(50);
        assertEquals(60, opt.getRect().getURX(), 1e-6);
        assertEquals(40, opt.getRect().getURY(), 1e-6);
    }

    @Test
    void setHeight_resizesRect() {
        RadioButtonOptionField opt = new RadioButtonOptionField((Page) null, new Rectangle(10, 20, 30, 40));
        opt.setHeight(15);
        assertEquals(30, opt.getRect().getURX(), 1e-6);
        assertEquals(35, opt.getRect().getURY(), 1e-6);  // 20 + 15
    }

    @Test
    void setWidth_withoutRect_createsRect() {
        RadioButtonOptionField opt = new RadioButtonOptionField();
        opt.setWidth(50);
        Rectangle r = opt.getRect();
        assertNotNull(r);
        assertEquals(50, r.getURX() - r.getLLX(), 1e-6);
    }
}
