package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.XImage;
import org.aspose.pdf.XImageCollection;
import org.aspose.pdf.engine.cos.COSStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 1 / Bug A — {@link XImageCollection#add(java.io.InputStream)} must
 * produce a fully-specified Image XObject (ISO 32000-1:2008 §8.9.5 Table 89):
 * the resulting {@code COSStream} must carry {@code /Type /XObject},
 * {@code /Subtype /Image}, {@code /Filter}, {@code /Width}, {@code /Height},
 * {@code /ColorSpace}, {@code /BitsPerComponent}. Without these entries,
 * strict viewers (Poppler) skip the image with
 * {@code "Syntax Error: Bad image parameters"}.
 */
class XImageCollectionAddTest {

    private static final int W = 32;
    private static final int H = 24;

    /** Returns a {@code WxH} solid-coloured image encoded as the given format ("JPG", "PNG", "BMP", "GIF"). */
    private static byte[] makeImage(String format) throws IOException {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, W, H);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, format, baos)) {
            throw new IOException("ImageIO has no writer for " + format);
        }
        return baos.toByteArray();
    }

    /** Saves a single-page doc that embeds {@code data} via {@code XImageCollection.add}, reopens, returns the first XImage's underlying COSStream. */
    private COSStream embedAndReopen(byte[] data, Path tmp, String label) throws IOException {
        Path out = tmp.resolve(label + ".pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            XImageCollection images = page.getResources().getImages();
            images.add(new ByteArrayInputStream(data));
            doc.save(out.toString());
        }
        Document reopened = new Document(out.toString());
        XImageCollection images = reopened.getPages().get(1).getResources().getImages();
        assertEquals(1, images.getCount(), "exactly one image should round-trip");
        XImage xi = images.get(1);
        return xi.getCOSStream();
    }

    private void assertSpecRequiredEntries(COSStream s, String expectedFilter) {
        assertEquals("XObject", s.getNameAsString("Type"),
                "Type must be /XObject per ISO §8.9.5 Table 89");
        assertEquals("Image",   s.getNameAsString("Subtype"),
                "Subtype must be /Image");
        assertEquals(W, s.getInt("Width", -1),  "/Width must reflect source pixel width");
        assertEquals(H, s.getInt("Height", -1), "/Height must reflect source pixel height");
        assertEquals(8, s.getInt("BitsPerComponent", -1),
                "/BitsPerComponent default 8 for ImageIO-decoded sources");
        String cs = s.getNameAsString("ColorSpace");
        assertTrue("DeviceRGB".equals(cs) || "DeviceGray".equals(cs),
                "/ColorSpace must be /DeviceRGB or /DeviceGray, got " + cs);
        if (expectedFilter != null) {
            String filter = s.getNameAsString("Filter");
            assertEquals(expectedFilter, filter,
                    "/Filter must be " + expectedFilter + ", got " + filter);
        } else {
            String filter = s.getNameAsString("Filter");
            assertTrue("DCTDecode".equals(filter) || "FlateDecode".equals(filter),
                    "/Filter must be a recognised image filter, got " + filter);
        }
    }

    @Test
    @DisplayName("add(jpegStream) sets /Type /Subtype /Filter /Width /Height /ColorSpace /BitsPerComponent")
    void addJpeg_setsRequiredImageDictEntries(@TempDir Path tmp) throws IOException {
        COSStream s = embedAndReopen(makeImage("JPG"), tmp, "jpeg");
        assertSpecRequiredEntries(s, "DCTDecode");
    }

    @Test
    @DisplayName("add(pngStream) transcodes to a valid Image XObject")
    void addPng_transcodesToValidXObject(@TempDir Path tmp) throws IOException {
        COSStream s = embedAndReopen(makeImage("PNG"), tmp, "png");
        // PNG path goes through ImageIO decode → FlateDecode re-encoding.
        // (A future variant might re-encode as JPEG; either is spec-valid.)
        assertSpecRequiredEntries(s, null);
    }

    @Test
    @DisplayName("add(bmpStream) transcodes to a valid Image XObject")
    void addBmp_transcodesToValidXObject(@TempDir Path tmp) throws IOException {
        COSStream s = embedAndReopen(makeImage("BMP"), tmp, "bmp");
        assertSpecRequiredEntries(s, null);
    }

    @Test
    @DisplayName("add(gifStream) transcodes to a valid Image XObject")
    void addGif_transcodesToValidXObject(@TempDir Path tmp) throws IOException {
        COSStream s = embedAndReopen(makeImage("GIF"), tmp, "gif");
        assertSpecRequiredEntries(s, null);
    }

    @Test
    @DisplayName("add(unknownFormat) throws IOException whose message mentions 'unsupported' or 'unrecognised'")
    void addUnknownFormat_throwsIOException() throws IOException {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            XImageCollection images = page.getResources().getImages();
            IOException ex = assertThrows(IOException.class,
                    () -> images.add(new ByteArrayInputStream(new byte[] {0, 1, 2, 3})));
            String msg = ex.getMessage().toLowerCase();
            assertTrue(msg.contains("unsupported") || msg.contains("unrecognised") || msg.contains("unrecognized"),
                    "expected 'unsupported'/'unrecognised' in message, got: " + ex.getMessage());
        }
    }
}
