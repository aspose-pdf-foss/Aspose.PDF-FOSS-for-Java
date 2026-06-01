package org.aspose.pdf.facades;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

class StampApiTest {

    @Test
    void newStamp_isEmpty() {
        Stamp s = new Stamp();
        assertNull(s.getFormattedText());
        assertNull(s.getImageFile());
        assertNull(s.getImageStream());
        assertNull(s.getPdfFile());
        assertNull(s.getPdfDocument());
        assertEquals(0, s.getOriginX(), 1e-6);
        assertEquals(0, s.getOriginY(), 1e-6);
    }

    @Test
    void bindLogo_string_aliasesBindImage() {
        Stamp s = new Stamp();
        s.bindLogo("logo.png");
        assertEquals("logo.png", s.getImageFile());
        assertNull(s.getFormattedText(), "string overload of bindLogo goes through bindImage path");
    }

    @Test
    void bindLogo_formattedText_setsFormattedText() {
        Stamp s = new Stamp();
        FormattedText ft = new FormattedText("DRAFT");
        s.bindLogo(ft);
        assertSame(ft, s.getFormattedText());
        assertNull(s.getImageFile());
    }

    @Test
    void bindPdf_singleArg_setsPageOne() {
        Stamp s = new Stamp();
        s.bindPdf("source.pdf");
        assertEquals("source.pdf", s.getPdfFile());
        assertEquals(1, s.getPdfPageNumber());
    }

    @Test
    void bindPdf_stringPage_setsBoth() {
        Stamp s = new Stamp();
        s.bindPdf("source.pdf", 5);
        assertEquals("source.pdf", s.getPdfFile());
        assertEquals(5, s.getPdfPageNumber());
    }

    @Test
    void bindPdf_nullStream_throws() {
        Stamp s = new Stamp();
        assertThrows(IllegalArgumentException.class, () -> s.bindPdf((java.io.InputStream) null));
    }

    @Test
    void bindPdf_invalidStream_throws() {
        Stamp s = new Stamp();
        // arbitrary non-PDF bytes
        ByteArrayInputStream bad = new ByteArrayInputStream(new byte[]{'x', 'x', 'x'});
        assertThrows(IllegalArgumentException.class, () -> s.bindPdf(bad));
    }

    @Test
    void setOrigin_storesXY() {
        Stamp s = new Stamp();
        s.setOrigin(100, 200);
        assertEquals(100, s.getOriginX(), 1e-6);
        assertEquals(200, s.getOriginY(), 1e-6);
    }

    @Test
    void bindMethods_clearOtherSources() {
        Stamp s = new Stamp();
        s.bindImage("a.png");
        assertEquals("a.png", s.getImageFile());
        s.bindPdf("b.pdf");
        assertNull(s.getImageFile());
        assertEquals("b.pdf", s.getPdfFile());
        s.bindLogo("c.png");
        assertNull(s.getPdfFile());
        assertEquals("c.png", s.getImageFile());
    }
}
