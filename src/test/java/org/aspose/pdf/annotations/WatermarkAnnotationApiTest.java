package org.aspose.pdf.annotations;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkAnnotationApiTest {

    @Test
    void setText_andGet_roundTrip() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            WatermarkAnnotation w = new WatermarkAnnotation(p, new Rectangle(0, 0, 100, 100));
            w.setText("DRAFT");
            assertEquals("DRAFT", w.getText());
            assertEquals("DRAFT", w.getContents(), "setText delegates to /Contents");
        }
    }

    @Test
    void getText_defaultsToNull() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            WatermarkAnnotation w = new WatermarkAnnotation(p, new Rectangle(0, 0, 100, 100));
            assertNull(w.getText());
        }
    }

    @Test
    void setOpacity_andGet_roundTrip() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            WatermarkAnnotation w = new WatermarkAnnotation(p, new Rectangle(0, 0, 100, 100));
            assertEquals(1.0, w.getOpacity(), 1e-6, "default = 1.0");
            w.setOpacity(0.4);
            assertEquals(0.4, w.getOpacity(), 1e-6);
        }
    }

    @Test
    void setAngle_andGet_roundTrip() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            WatermarkAnnotation w = new WatermarkAnnotation(p, new Rectangle(0, 0, 100, 100));
            assertEquals(0, w.getAngle(), 1e-6, "default = 0");
            w.setAngle(45);
            assertEquals(45, w.getAngle(), 1e-6);
        }
    }

    @Test
    void subtype_isWatermark() throws Exception {
        try (Document doc = new Document()) {
            Page p = doc.getPages().add();
            WatermarkAnnotation w = new WatermarkAnnotation(p, new Rectangle(0, 0, 100, 100));
            // Verify the /Subtype entry was set
            assertEquals("Watermark",
                    ((org.aspose.pdf.engine.cos.COSName) w.getCOSDictionary().get("Subtype")).getName());
        }
    }
}
