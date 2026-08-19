package org.aspose.pdf.tests;

import org.aspose.pdf.XImage;
import org.aspose.pdf.engine.colorspace.*;
import org.aspose.pdf.engine.pdfobjects.*;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link XImage}.
 */
public class XImageTest {

    @Test
    public void testBasicProperties() {
        PdfStream stream = new PdfStream();
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(10));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(20));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(8));

        XImage img = new XImage(stream, "Im1", null);
        assertEquals(10, img.getWidth());
        assertEquals(20, img.getHeight());
        assertEquals(8, img.getBitsPerComponent());
        assertEquals("Im1", img.getName());
    }

    @Test
    public void testDefaultColorSpace() throws IOException {
        PdfStream stream = new PdfStream();
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(1));

        XImage img = new XImage(stream, "Im1", null);
        ColorSpaceBase cs = img.getColorSpace();
        assertSame(DeviceRGB.INSTANCE, cs);
    }

    @Test
    public void testExplicitColorSpace() throws IOException {
        PdfStream stream = new PdfStream();
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("ColorSpace"), PdfName.of("DeviceGray"));

        XImage img = new XImage(stream, "Im1", null);
        ColorSpaceBase cs = img.getColorSpace();
        assertSame(DeviceGray.INSTANCE, cs);
    }

    @Test
    public void testIsImageMask() {
        PdfStream stream = new PdfStream();
        stream.set(PdfName.of("ImageMask"), PdfBoolean.TRUE);
        XImage img = new XImage(stream, "Im1", null);
        assertTrue(img.isImageMask());
    }

    @Test
    public void testIsNotImageMask() {
        PdfStream stream = new PdfStream();
        XImage img = new XImage(stream, "Im1", null);
        assertFalse(img.isImageMask());
    }

    @Test
    public void testToBufferedImageRGB() throws IOException {
        int w = 2, h = 2;
        // 2x2 RGB image: red, green, blue, white
        byte[] pixels = new byte[]{
            (byte) 255, 0, 0,           // red
            0, (byte) 255, 0,           // green
            0, 0, (byte) 255,           // blue
            (byte) 255, (byte) 255, (byte) 255  // white
        };
        PdfStream stream = new PdfStream(pixels);
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(w));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(h));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(8));
        stream.set(PdfName.of("ColorSpace"), PdfName.of("DeviceRGB"));

        XImage img = new XImage(stream, "Im1", null);
        BufferedImage bi = img.toBufferedImage();
        assertNotNull(bi);
        assertEquals(w, bi.getWidth());
        assertEquals(h, bi.getHeight());
        // Check top-left pixel is red
        int rgb = bi.getRGB(0, 0);
        assertEquals(0xFFFF0000, rgb);
    }

    @Test
    public void testToBufferedImageGray() throws IOException {
        int w = 2, h = 2;
        byte[] pixels = new byte[]{0, (byte) 128, (byte) 255, 64};
        PdfStream stream = new PdfStream(pixels);
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(w));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(h));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(8));
        stream.set(PdfName.of("ColorSpace"), PdfName.of("DeviceGray"));

        XImage img = new XImage(stream, "Im1", null);
        BufferedImage bi = img.toBufferedImage();
        assertEquals(BufferedImage.TYPE_BYTE_GRAY, bi.getType());
        assertEquals(w, bi.getWidth());
        assertEquals(h, bi.getHeight());
    }

    @Test
    public void testToBufferedImageSeparation1Bit() throws IOException {
        // 10x3 1-bit /Separation /Black image, rows byte-aligned (§8.9.3):
        // row0 all ink (black), row1 no ink (white), row2 alternating.
        // The old byte-per-pixel read painted everything past the first
        // rows white (corpus 35751_2 hanger panel).
        int w = 10, h = 3;
        byte[] pixels = new byte[]{
            (byte) 0xFF, (byte) 0xC0,
            0x00, 0x00,
            (byte) 0xAA, (byte) 0x80
        };
        PdfDictionary fn = new PdfDictionary();
        fn.set(PdfName.of("FunctionType"), PdfInteger.valueOf(2));
        PdfArray domain = new PdfArray();
        domain.add(PdfInteger.valueOf(0));
        domain.add(PdfInteger.valueOf(1));
        fn.set(PdfName.of("Domain"), domain);
        PdfArray c0 = new PdfArray();
        PdfArray c1 = new PdfArray();
        for (int i = 0; i < 4; i++) {
            c0.add(PdfInteger.valueOf(0));
            c1.add(PdfInteger.valueOf(i == 3 ? 1 : 0));
        }
        fn.set(PdfName.of("C0"), c0);
        fn.set(PdfName.of("C1"), c1);
        fn.set(PdfName.of("N"), PdfInteger.valueOf(1));

        PdfArray csArr = new PdfArray();
        csArr.add(PdfName.of("Separation"));
        csArr.add(PdfName.of("Black"));
        csArr.add(PdfName.of("DeviceCMYK"));
        csArr.add(fn);

        PdfStream stream = new PdfStream(pixels);
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(w));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(h));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("ColorSpace"), csArr);

        XImage img = new XImage(stream, "Im1", null);
        BufferedImage bi = img.toBufferedImage();
        assertEquals(w, bi.getWidth());
        assertEquals(h, bi.getHeight());
        // Row 0: full black ink everywhere (dark), including x beyond byte 0.
        assertTrue((bi.getRGB(9, 0) & 0xFF) < 80, "row0 must be inked");
        // Row 1: no ink -> white.
        assertEquals(0xFFFFFFFF, bi.getRGB(9, 1));
        // Row 2: alternating, starts inked.
        assertTrue((bi.getRGB(0, 2) & 0xFF) < 80, "row2 x0 inked");
        assertEquals(0xFFFFFFFF, bi.getRGB(1, 2));
        assertTrue((bi.getRGB(8, 2) & 0xFF) < 80, "row2 x8 inked");
        assertEquals(0xFFFFFFFF, bi.getRGB(9, 2));
    }

    @Test
    public void testToBufferedImageCalGray1Bit() throws IOException {
        // 10x2 1-bit /CalGray scan: row0 all 1 (white), row1 alternating.
        // The old byte-per-pixel read ran off the data and painted the
        // page black (corpus UserGuide p379).
        PdfDictionary params = new PdfDictionary();
        PdfArray wp = new PdfArray();
        wp.add(new PdfFloat(0.951f));
        wp.add(PdfInteger.valueOf(1));
        wp.add(new PdfFloat(1.089f));
        params.set(PdfName.of("WhitePoint"), wp);
        params.set(PdfName.of("Gamma"), new PdfFloat(1.8f));
        PdfArray csArr = new PdfArray();
        csArr.add(PdfName.of("CalGray"));
        csArr.add(params);

        byte[] pixels = new byte[]{(byte) 0xFF, (byte) 0xC0, (byte) 0xAA, (byte) 0x80};
        PdfStream stream = new PdfStream(pixels);
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(10));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(2));
        stream.set(PdfName.of("BitsPerComponent"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("ColorSpace"), csArr);

        BufferedImage bi = new XImage(stream, "Im1", null).toBufferedImage();
        // Row 0: all white, including pixels past the first byte.
        assertTrue((bi.getRGB(9, 0) & 0xFF) > 200, "row0 must be white");
        // Row 1: alternating black/white.
        assertTrue((bi.getRGB(0, 1) & 0xFF) > 200, "row1 x0 white");
        assertTrue((bi.getRGB(1, 1) & 0xFF) < 60, "row1 x1 black");
    }

    @Test
    public void testSaveAsJPEG() throws IOException {
        // Create a stream with DCTDecode filter
        byte[] fakeJpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        PdfStream stream = new PdfStream(fakeJpeg);
        stream.set(PdfName.of("Subtype"), PdfName.of("Image"));
        stream.set(PdfName.of("Width"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("Height"), PdfInteger.valueOf(1));
        stream.set(PdfName.of("Filter"), PdfName.of("DCTDecode"));

        XImage img = new XImage(stream, "Im1", null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        img.save(baos);
        byte[] saved = baos.toByteArray();
        // Should be the raw JPEG bytes
        assertEquals(fakeJpeg.length, saved.length);
        assertEquals((byte) 0xFF, saved[0]);
        assertEquals((byte) 0xD8, saved[1]);
    }

    @Test
    public void testGetPdfStream() {
        PdfStream stream = new PdfStream();
        XImage img = new XImage(stream, "test", null);
        assertSame(stream, img.getPdfStream());
    }
}
