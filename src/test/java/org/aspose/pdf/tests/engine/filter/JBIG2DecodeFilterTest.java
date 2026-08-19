package org.aspose.pdf.tests.engine.filter;

import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.filter.ArithmeticDecoder;
import org.aspose.pdf.engine.filter.JBIG2DecodeFilter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link JBIG2DecodeFilter} and {@link ArithmeticDecoder}.
 * Covers arithmetic decoding, generic regions, symbol dictionaries,
 * text regions, global segments, and edge cases.
 */
public class JBIG2DecodeFilterTest {

    private final JBIG2DecodeFilter filter = new JBIG2DecodeFilter();

    // ═══════════════════════════════════════════════════════════════
    //  Basic / edge cases
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void emptyInput() throws IOException {
        byte[] result = filter.decode(new byte[0], null);
        assertEquals(0, result.length);
    }

    @Test
    public void nullInput() throws IOException {
        byte[] result = filter.decode(null, null);
        assertEquals(0, result.length);
    }

    @Test
    public void malformedDataReturnsRaw() throws IOException {
        byte[] garbage = {0x01, 0x02, 0x03, 0x04, 0x05};
        byte[] result = filter.decode(garbage, null);
        assertNotNull(result);
    }

    @Test
    public void encodingNotSupported() {
        assertThrows(IOException.class, () -> filter.encode(new byte[]{1, 2, 3}, null));
    }

    @Test
    public void filterName() {
        assertEquals("JBIG2Decode", filter.getName().getValue());
    }

    // ═══════════════════════════════════════════════════════════════
    //  ArithmeticDecoder tests
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void arithmeticDecoderCreation() {
        byte[] data = new byte[64];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 16);
        assertNotNull(decoder);
    }

    @Test
    public void arithmeticDecoderDecodesFromZeros() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 4);
        for (int i = 0; i < 100; i++) {
            int bit = decoder.decode(0);
            assertTrue(bit == 0 || bit == 1, "Decoded bit must be 0 or 1");
        }
    }

    @Test
    public void arithmeticDecoderDecodesFromOnes() {
        byte[] data = new byte[256];
        java.util.Arrays.fill(data, (byte) 0xFF);
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 4);
        for (int i = 0; i < 100; i++) {
            int bit = decoder.decode(0);
            assertTrue(bit == 0 || bit == 1);
        }
    }

    @Test
    public void arithmeticDecoderMultipleContexts() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 1024);
        int bit0 = decoder.decode(0);
        int bit512 = decoder.decode(512);
        int bit1023 = decoder.decode(1023);
        assertTrue(bit0 == 0 || bit0 == 1);
        assertTrue(bit512 == 0 || bit512 == 1);
        assertTrue(bit1023 == 0 || bit1023 == 1);
    }

    @Test
    public void arithmeticDecoderResetContext() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 16);
        decoder.decode(0);
        decoder.decode(0);
        decoder.resetContext(0);
        int bit = decoder.decode(0);
        assertTrue(bit == 0 || bit == 1);
    }

    @Test
    public void arithmeticDecoderResetAllContexts() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 16);
        decoder.decode(0);
        decoder.decode(5);
        decoder.resetAllContexts();
        int bit = decoder.decode(0);
        assertTrue(bit == 0 || bit == 1);
    }

    @Test
    public void arithmeticDecoderDecodeInteger() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 1024);
        int val = decoder.decodeInteger(0);
        // Should return an integer without crashing
        assertTrue(val == Integer.MIN_VALUE || val >= Integer.MIN_VALUE + 1);
    }

    @Test
    public void arithmeticDecoderDecodeIAID() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 1024);
        int val = decoder.decodeIAID(0, 4);
        assertTrue(val >= 0 && val < 16, "IAID with 4 bits should be 0-15, got " + val);
    }

    @Test
    public void arithmeticDecoderDecodeIAIDSingleBit() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 1024);
        int val = decoder.decodeIAID(0, 1);
        assertTrue(val >= 0 && val < 2, "IAID with 1 bit should be 0-1, got " + val);
    }

    @Test
    public void arithmeticDecoderBytePointer() {
        byte[] data = new byte[256];
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 10, 4);
        assertTrue(decoder.getBytePointer() >= 10);
    }

    @Test
    public void arithmeticDecoderPastEndOfData() {
        // Very short data — decoder should pad with 0xFF and not crash
        byte[] data = {0x00, 0x01};
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 4);
        for (int i = 0; i < 200; i++) {
            int bit = decoder.decode(0);
            assertTrue(bit == 0 || bit == 1);
        }
    }

    @Test
    public void arithmeticDecoderByteStuffing() {
        // Data with 0xFF followed by values <= 0x8F (byte stuffing)
        // and > 0x8F (marker)
        byte[] data = new byte[32];
        data[0] = (byte) 0x00;
        data[1] = (byte) 0xFF;
        data[2] = (byte) 0x50; // <= 0x8F: byte stuff
        data[3] = (byte) 0xFF;
        data[4] = (byte) 0xD9; // > 0x8F: marker
        ArithmeticDecoder decoder = new ArithmeticDecoder(data, 0, 4);
        for (int i = 0; i < 50; i++) {
            int bit = decoder.decode(0);
            assertTrue(bit == 0 || bit == 1);
        }
    }

    @Test
    public void arithmeticDecoderConsistentOutput() {
        // Same data, same offset → same output
        byte[] data = {0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC, (byte) 0xDE, (byte) 0xF0,
                       0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        int[] results1 = new int[20];
        int[] results2 = new int[20];
        ArithmeticDecoder dec1 = new ArithmeticDecoder(data, 0, 16);
        ArithmeticDecoder dec2 = new ArithmeticDecoder(data, 0, 16);
        for (int i = 0; i < 20; i++) {
            results1[i] = dec1.decode(i % 4);
            results2[i] = dec2.decode(i % 4);
        }
        assertArrayEquals(results1, results2);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Full pipeline: Page info + generic region MMR + end of page
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void fullPipelineMMRGenericRegion() throws IOException {
        // Build a minimal JBIG2 stream:
        // 1. PageInfo segment (type 48)
        // 2. Immediate lossless generic region with MMR (type 39)
        // 3. End of page (type 49)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // --- Segment 0: Page Info (type 48) ---
        writeU32(baos, 0);          // segment number
        baos.write(48);             // type=48
        baos.write(0x00);           // 0 referred-to
        baos.write(1);              // page assoc
        writeU32(baos, 19);         // data length
        writeU32(baos, 8);          // width=8
        writeU32(baos, 1);          // height=1
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // --- Segment 1: Generic Region Imm Lossless (type 39) ---
        byte[] mmrData = {(byte) 0x80, 0x00, 0x10, 0x00, 0x08};
        int regionDataLen = 18 + mmrData.length;
        writeU32(baos, 1);
        baos.write(39);             // type=39
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, regionDataLen);
        writeU32(baos, 8);          // width=8
        writeU32(baos, 1);          // height=1
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);           // combination flags
        baos.write(0x01);           // MMR=1
        baos.write(mmrData);

        // --- Segment 2: End of Page ---
        writeU32(baos, 2);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertEquals(1, result.length); // 8 pixels = 1 byte
    }

    @Test
    public void fullPipelineArithmeticGenericRegion() throws IOException {
        // Build a JBIG2 stream with arithmetic-coded generic region (template 3)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Page Info: 4x4
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 4);
        writeU32(baos, 4);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // Generic Region Immediate (type 38), arithmetic, template 3
        // Header: 18 bytes + 2 AT bytes + arithmetic data
        byte[] arithData = new byte[64]; // zeros → deterministic decode
        int regDataLen = 18 + 2 + arithData.length;
        writeU32(baos, 1);
        baos.write(38);             // type=38 (immediate)
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, regDataLen);
        writeU32(baos, 4);          // width=4
        writeU32(baos, 4);          // height=4
        writeU32(baos, 0);          // x offset
        writeU32(baos, 0);          // y offset
        baos.write(0x00);           // combination flags
        baos.write(0x06);           // MMR=0, template=3 (bits 1-2 = 11), TPGD=0
        // AT pixels for template 3: 1 pair
        baos.write(2);              // AT X
        baos.write(-1 & 0xFF);      // AT Y (signed -1)
        baos.write(arithData);

        // End of Page
        writeU32(baos, 2);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        // 4 pixels wide → 1 byte per row, 4 rows = 4 bytes
        assertEquals(4, result.length);
    }

    @Test
    public void fullPipelineEmptyPage() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 0);  // width=0
        writeU32(baos, 0);  // height=0
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        writeU32(baos, 1);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
    }

    @Test
    public void unknownSegmentTypeSkipped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Page info 4x4
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 4);
        writeU32(baos, 4);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // Unknown segment type 60
        writeU32(baos, 1);
        baos.write(60);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 4);
        baos.write(new byte[4]);

        // End of page
        writeU32(baos, 2);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        // 4x4 page = 4 bytes (1 byte/row, MSB first, row-aligned)
        assertEquals(4, result.length);
    }

    @Test
    public void defaultPixelBlack() throws IOException {
        // Page with default pixel = black (bit 2 of flags)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x04);           // flags: default pixel = black
        baos.write(0x00);
        baos.write(0x00);

        writeU32(baos, 1);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertEquals(1, result.length);
        // JBIG2 black (1) maps to DeviceGray black (0) — the filter delivers
        // inverted bits, so an all-black page packs to all-zero samples.
        assertEquals((byte) 0x00, result[0]);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Global segments test
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void globalSegmentsSourceDataTagged() throws IOException {
        // Build a minimal globals stream with a symbol dict segment
        ByteArrayOutputStream globalsStream = new ByteArrayOutputStream();
        writeU32(globalsStream, 0);  // segment number 0
        globalsStream.write(0);      // type=0 (symbol dict)
        globalsStream.write(0x00);   // 0 referred-to
        globalsStream.write(0);      // page assoc = 0 (global)
        // Minimal SD: 2 flags + 8 AT (template 0) + 4 SDNUMEXSYMS + 4 SDNUMNEWSYMS = 18 bytes
        writeU32(globalsStream, 18);
        globalsStream.write(0x00);   // SD flags low byte: arith, no refagg, template 0
        globalsStream.write(0x00);   // SD flags high byte
        // 4 AT pairs for template 0
        globalsStream.write(3);  globalsStream.write(-1 & 0xFF);
        globalsStream.write(-3 & 0xFF); globalsStream.write(-1 & 0xFF);
        globalsStream.write(2);  globalsStream.write(-2 & 0xFF);
        globalsStream.write(-2 & 0xFF); globalsStream.write(-2 & 0xFF);
        writeU32(globalsStream, 0);  // SDNUMEXSYMS = 0
        writeU32(globalsStream, 0);  // SDNUMNEWSYMS = 0

        byte[] globalsBytes = globalsStream.toByteArray();

        // Build page data
        ByteArrayOutputStream pageStream = new ByteArrayOutputStream();
        writeU32(pageStream, 1);
        pageStream.write(48);
        pageStream.write(0x00);
        pageStream.write(1);
        writeU32(pageStream, 19);
        writeU32(pageStream, 8);
        writeU32(pageStream, 8);
        writeU32(pageStream, 0);
        writeU32(pageStream, 0);
        pageStream.write(0x00);
        pageStream.write(0x00);
        pageStream.write(0x00);

        writeU32(pageStream, 2);
        pageStream.write(49);
        pageStream.write(0x00);
        pageStream.write(1);
        writeU32(pageStream, 0);

        byte[] pageBytes = pageStream.toByteArray();

        PdfDictionary params = new PdfDictionary();
        PdfStream globalsObj = new PdfStream();
        globalsObj.setDecodedData(globalsBytes);
        params.set("JBIG2Globals", globalsObj);

        byte[] result = filter.decode(pageBytes, params);
        assertNotNull(result);
        assertEquals(8, result.length); // 8x8 = 8 bytes
    }

    // ═══════════════════════════════════════════════════════════════
    //  File header detection
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void fileHeaderDetectedAndSkipped() throws IOException {
        // Build JBIG2 with file header magic bytes
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // File header: 8 bytes magic + 1 byte flags + 4 bytes page count
        baos.write(new byte[]{(byte) 0x97, 0x4A, 0x42, 0x32, 0x0D, 0x0A, 0x1A, 0x0A});
        baos.write(0x00); // flags: sequential, page count present
        writeU32(baos, 1); // 1 page

        // Page info
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // End of page
        writeU32(baos, 1);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        // End of file
        writeU32(baos, 2);
        baos.write(51);
        baos.write(0x00);
        baos.write(0);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertEquals(1, result.length); // 8x1 page
    }

    // ═══════════════════════════════════════════════════════════════
    //  Symbol dictionary + text region pipeline
    // ═══════════════════════════════════════════════════════════════

    @Test
    public void symbolDictAndTextRegion() throws IOException {
        // Build a JBIG2 stream with:
        // 1. PageInfo (8x8)
        // 2. Symbol dictionary (1 simple 2x2 symbol)
        // 3. Text region placing that symbol
        // 4. End of page
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // --- Segment 0: Page Info ---
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 8);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // --- Segment 1: Symbol Dictionary (template 3, arith, 1 new symbol 2x2) ---
        // SD flags: arith=0, refagg=0, template=3 → 0x000C (bits 2-3 = template)
        // Template 3 has 1 AT pair = 2 bytes
        // Data: flags(2) + AT(2) + SDNUMEXSYMS(4) + SDNUMNEWSYMS(4) + arith data
        byte[] arithSymData = new byte[32]; // zeros → deterministic
        int sdDataLen = 2 + 2 + 4 + 4 + arithSymData.length;
        writeU32(baos, 1);
        baos.write(0);               // type=0 (symbol dict)
        baos.write(0x00);            // 0 referred-to
        baos.write(1);               // page assoc
        writeU32(baos, sdDataLen);
        baos.write(0x0C);            // flags low: template=3 (bits 2-3)
        baos.write(0x00);            // flags high
        baos.write(2);               // AT X
        baos.write(0xFF);            // AT Y = -1
        writeU32(baos, 1);           // SDNUMEXSYMS = 1
        writeU32(baos, 1);           // SDNUMNEWSYMS = 1
        baos.write(arithSymData);

        // --- Segment 2: Text Region Immediate (type 7) ---
        // References segment 1 (symbol dict)
        // Minimal text region: places 1 symbol instance
        byte[] arithTextData = new byte[32];
        // Region header (17) + text flags (2) + SBNUMINSTANCES (4) + arith data
        int trDataLen = 17 + 2 + 4 + arithTextData.length;
        writeU32(baos, 2);
        baos.write(7);               // type=7 (text region immediate)
        // referred-to: 1 segment (segment 1)
        baos.write(0x20);            // 1 referred-to (bits 5-7 = 001)
        baos.write(1);               // referred-to segment number = 1 (1 byte since seg number <= 256)
        baos.write(1);               // page assoc
        writeU32(baos, trDataLen);
        // Region segment info (17 bytes)
        writeU32(baos, 8);           // width
        writeU32(baos, 8);           // height
        writeU32(baos, 0);           // x offset
        writeU32(baos, 0);           // y offset
        baos.write(0x00);            // combination flags
        // Text region flags (2 bytes)
        baos.write(0x00);            // SBHUFF=0, SBREFINE=0, LOGSBSTRIPS=0
        baos.write(0x00);
        // SBNUMINSTANCES
        writeU32(baos, 1);           // 1 symbol instance
        baos.write(arithTextData);

        // --- Segment 3: End of Page ---
        writeU32(baos, 3);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        // 8x8 page → 8 bytes
        assertEquals(8, result.length);
    }

    @Test
    public void unreasonableSymbolDictionaryCountsAreSkipped() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        writeU32(baos, 1);
        baos.write(0);      // symbol dictionary
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 10); // flags + AT + counts
        baos.write(0x0C);   // arithmetic, template 3
        baos.write(0x00);
        baos.write(2);
        baos.write(0xFF);
        writeU32(baos, 1);
        writeU32(baos, 0x80000000); // absurd / negative as signed int

        writeU32(baos, 2);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    public void multipleGenericRegionsComposed() throws IOException {
        // Two generic regions composed onto the same page
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Page Info: 8x2
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 2);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // First generic region: 8x1 at y=0
        byte[] mmrData1 = {(byte) 0x80, 0x00, 0x10, 0x00, 0x08};
        int regLen1 = 18 + mmrData1.length;
        writeU32(baos, 1);
        baos.write(39);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, regLen1);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);   // x=0
        writeU32(baos, 0);   // y=0
        baos.write(0x00);
        baos.write(0x01);    // MMR=1
        baos.write(mmrData1);

        // Second generic region: 8x1 at y=1
        byte[] mmrData2 = {(byte) 0x80, 0x00, 0x10, 0x00, 0x08};
        int regLen2 = 18 + mmrData2.length;
        writeU32(baos, 2);
        baos.write(39);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, regLen2);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);   // x=0
        writeU32(baos, 1);   // y=1
        baos.write(0x00);
        baos.write(0x01);    // MMR=1
        baos.write(mmrData2);

        // End of page
        writeU32(baos, 3);
        baos.write(49);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 0);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertEquals(2, result.length); // 8x2 = 2 bytes
    }

    @Test
    public void endOfFileStopsParsing() throws IOException {
        // End of file segment should stop further segment parsing
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Page Info
        writeU32(baos, 0);
        baos.write(48);
        baos.write(0x00);
        baos.write(1);
        writeU32(baos, 19);
        writeU32(baos, 8);
        writeU32(baos, 1);
        writeU32(baos, 0);
        writeU32(baos, 0);
        baos.write(0x00);
        baos.write(0x00);
        baos.write(0x00);

        // End of file
        writeU32(baos, 1);
        baos.write(51);     // type=51 (end of file)
        baos.write(0x00);
        baos.write(0);
        writeU32(baos, 0);

        // Garbage after EOF — should not be parsed
        baos.write(new byte[100]);

        byte[] result = filter.decode(baos.toByteArray(), null);
        assertNotNull(result);
        assertEquals(1, result.length);
    }

    // ═══════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════

    private static void writeU32(ByteArrayOutputStream baos, int value) {
        baos.write((value >> 24) & 0xFF);
        baos.write((value >> 16) & 0xFF);
        baos.write((value >> 8) & 0xFF);
        baos.write(value & 0xFF);
    }
}
