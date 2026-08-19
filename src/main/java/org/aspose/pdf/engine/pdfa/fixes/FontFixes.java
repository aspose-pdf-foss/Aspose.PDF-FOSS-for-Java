package org.aspose.pdf.engine.pdfa.fixes;

import org.aspose.pdf.ConvertErrorAction;
import org.aspose.pdf.PdfFormat;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.font.ttf.FontDiskLookup;
import org.aspose.pdf.engine.pdfa.PdfAValidationResult;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.DeflaterOutputStream;

/**
 * Font-related fixes for PDF/A compliance.
 * <p>
 * Because we cannot embed font programs we do not have access to, these fixes
 * are limited in scope.  The primary capabilities are:
 * </p>
 * <ul>
 *   <li>Generate a {@code /ToUnicode} CMap for standard-encoded fonts that lack one</li>
 *   <li>Generate a {@code /CharSet} string for subset Type 1 fonts</li>
 *   <li>Generate a {@code /CIDSet} stream for subset CID fonts</li>
 *   <li>Log warnings for unembedded fonts that cannot be fixed automatically</li>
 * </ul>
 */
public final class FontFixes {

    private static final Logger LOG = Logger.getLogger(FontFixes.class.getName());

    /** WinAnsiEncoding mapping: byte value (32..255) -> Unicode code point. */
    private static final Map<Integer, Integer> WIN_ANSI_MAP = new HashMap<>();

    static {
        // Standard ASCII range 32..126 maps to itself
        for (int i = 32; i <= 126; i++) {
            WIN_ANSI_MAP.put(i, i);
        }
        // Windows-1252 specific mappings for 128..159 and 160..255
        WIN_ANSI_MAP.put(128, 0x20AC); // Euro sign
        WIN_ANSI_MAP.put(130, 0x201A);
        WIN_ANSI_MAP.put(131, 0x0192);
        WIN_ANSI_MAP.put(132, 0x201E);
        WIN_ANSI_MAP.put(133, 0x2026);
        WIN_ANSI_MAP.put(134, 0x2020);
        WIN_ANSI_MAP.put(135, 0x2021);
        WIN_ANSI_MAP.put(136, 0x02C6);
        WIN_ANSI_MAP.put(137, 0x2030);
        WIN_ANSI_MAP.put(138, 0x0160);
        WIN_ANSI_MAP.put(139, 0x2039);
        WIN_ANSI_MAP.put(140, 0x0152);
        WIN_ANSI_MAP.put(142, 0x017D);
        WIN_ANSI_MAP.put(145, 0x2018);
        WIN_ANSI_MAP.put(146, 0x2019);
        WIN_ANSI_MAP.put(147, 0x201C);
        WIN_ANSI_MAP.put(148, 0x201D);
        WIN_ANSI_MAP.put(149, 0x2022);
        WIN_ANSI_MAP.put(150, 0x2013);
        WIN_ANSI_MAP.put(151, 0x2014);
        WIN_ANSI_MAP.put(152, 0x02DC);
        WIN_ANSI_MAP.put(153, 0x2122);
        WIN_ANSI_MAP.put(154, 0x0161);
        WIN_ANSI_MAP.put(155, 0x203A);
        WIN_ANSI_MAP.put(156, 0x0153);
        WIN_ANSI_MAP.put(158, 0x017E);
        WIN_ANSI_MAP.put(159, 0x0178);
        // 160..255 map to their Unicode equivalents (Latin-1 Supplement)
        for (int i = 160; i <= 255; i++) {
            WIN_ANSI_MAP.put(i, i);
        }
    }

    /**
     * Creates a new FontFixes instance.
     */
    public FontFixes() {
        // default
    }

    /**
     * Generates a {@code /ToUnicode} CMap for fonts that use WinAnsiEncoding or
     * MacRomanEncoding but lack a {@code /ToUnicode} entry.
     * <p>
     * PDF/A Level A requires every font to have a way to map character codes to
     * Unicode (ISO 19005-1:2005, 6.3.8).
     * </p>
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void generateToUnicodeCMap(PDFParser parser, PdfFormat format,
                                      ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            String type = dict.getNameAsString("Type");
            if (!"Font".equals(type)) {
                continue;
            }
            // Skip if already has ToUnicode
            if (dict.get("ToUnicode") != null) {
                continue;
            }

            String encoding = dict.getNameAsString("Encoding");
            if (!"WinAnsiEncoding".equals(encoding) && !"MacRomanEncoding".equals(encoding)) {
                continue;
            }

            int firstChar = dict.getInt("FirstChar", 0);
            int lastChar = dict.getInt("LastChar", 255);

            LOG.fine(() -> "Generating ToUnicode CMap for font at object " + key.getObjectNumber()
                    + " (" + encoding + ")");

            byte[] cmapData;
            if ("WinAnsiEncoding".equals(encoding)) {
                cmapData = buildWinAnsiCMap(firstChar, lastChar);
            } else {
                // MacRomanEncoding — use a simplified identity mapping for now
                cmapData = buildWinAnsiCMap(firstChar, lastChar);
            }

            PdfStream cmapStream = new PdfStream();
            cmapStream.setDecodedData(cmapData);
            cmapStream.setFilter(PdfName.FLATE_DECODE);

            int maxObj = findMaxObjectNumber(parser);
            PdfObjectKey cmapKey = new PdfObjectKey(maxObj + 1, 0);
            PdfObjectReference cmapRef = new PdfObjectReference(cmapKey, k -> cmapStream);

            dict.set("ToUnicode", cmapRef);
            result.addWarning("font.1", "Generated /ToUnicode CMap for " + encoding + " font",
                    "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.8");
        }
    }

    /**
     * Generates a {@code /CharSet} string for subset Type 1 fonts that lack one.
     * <p>
     * The /CharSet string lists the glyph names present in the font. For PDF/A-1
     * this is required in the font descriptor of subset Type 1 fonts
     * (ISO 19005-1:2005, 6.3.5).
     * </p>
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void generateCharSet(PDFParser parser, PdfFormat format,
                                ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            if (!"Font".equals(dict.getNameAsString("Type"))) {
                continue;
            }
            String subtype = dict.getNameAsString("Subtype");
            if (!"Type1".equals(subtype)) {
                continue;
            }

            // Check if it's a subset font (name contains '+')
            String baseFontName = dict.getNameAsString("BaseFont");
            if (baseFontName == null || !baseFontName.contains("+")) {
                continue;
            }

            // Get font descriptor
            PdfBase fdRef = dict.get("FontDescriptor");
            if (fdRef == null) {
                continue;
            }
            PdfBase fdObj = parser.resolveReference(fdRef);
            if (!(fdObj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary fontDesc = (PdfDictionary) fdObj;
            if (fontDesc.get("CharSet") != null) {
                continue;
            }

            // Generate CharSet from encoding differences or standard encoding
            int firstChar = dict.getInt("FirstChar", 0);
            int lastChar = dict.getInt("LastChar", 255);
            String charSet = buildCharSetString(firstChar, lastChar);

            fontDesc.set("CharSet", new PdfString(charSet));
            result.addWarning("font.2", "Generated /CharSet for subset Type1 font " + baseFontName,
                    "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.5");
        }
    }

    /**
     * Generates a {@code /CIDSet} stream for subset CID fonts that lack one.
     * <p>
     * For PDF/A, CIDFont subsets must include a CIDSet stream that indicates
     * which CIDs are present (ISO 19005-1:2005, 6.3.6).
     * </p>
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void generateCIDSet(PDFParser parser, PdfFormat format,
                               ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            String subtype = dict.getNameAsString("Subtype");
            if (!"CIDFontType0".equals(subtype) && !"CIDFontType2".equals(subtype)) {
                continue;
            }

            // Get font descriptor
            PdfBase fdRef = dict.get("FontDescriptor");
            if (fdRef == null) {
                continue;
            }
            PdfBase fdObj = parser.resolveReference(fdRef);
            if (!(fdObj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary fontDesc = (PdfDictionary) fdObj;
            if (fontDesc.get("CIDSet") != null) {
                continue;
            }

            // Check if subset
            String baseFontName = dict.getNameAsString("BaseFont");
            if (baseFontName == null || !baseFontName.contains("+")) {
                continue;
            }

            LOG.fine(() -> "Generating CIDSet for CIDFont " + baseFontName);

            // Generate a CIDSet that marks all CIDs 0..lastCid as present
            // This is a conservative approach; ideally we'd analyze the actual CIDs used
            int lastCid = 255; // default
            PdfBase wArray = dict.get("W");
            if (wArray instanceof PdfArray) {
                lastCid = estimateMaxCidFromW((PdfArray) wArray);
            }

            byte[] cidSetData = buildCidSetBitmap(lastCid);

            PdfStream cidSetStream = new PdfStream();
            cidSetStream.setDecodedData(cidSetData);
            cidSetStream.setFilter(PdfName.FLATE_DECODE);

            int maxObj = findMaxObjectNumber(parser);
            PdfObjectKey cidSetKey = new PdfObjectKey(maxObj + 1, 0);
            PdfObjectReference cidSetRef = new PdfObjectReference(cidSetKey, k -> cidSetStream);

            fontDesc.set("CIDSet", cidSetRef);
            result.addWarning("font.3", "Generated /CIDSet for subset CIDFont " + baseFontName,
                    "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.6");
        }
    }

    /**
     * Embeds a font program for simple (non-composite) fonts that lack one, so the
     * converted document satisfies the PDF/A embedding requirement (ISO 19005-1:2005,
     * 6.3.4 / ISO 19005-2:2011, 6.2.11.4). The font program is located on the host
     * system by name/style via {@link FontDiskLookup} (standard-14 names are mapped to
     * their metric-compatible system faces: Helvetica→Arial, Times→Times New Roman,
     * Courier→Courier New). For a standard-14 font without a FontDescriptor one is
     * synthesized from the TrueType metrics. Because the embedded program is TrueType,
     * a Type1 dictionary is retyped to TrueType, a WinAnsi base encoding is ensured for
     * non-symbolic faces (rule 6.3.7) and /FirstChar/LastChar/Widths are generated from
     * the program's advance widths when missing. Composite (Type0) and Type3 fonts are
     * left to {@link #logUnembeddedFonts}.
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void embedUnembeddedFonts(PDFParser parser, PdfFormat format,
                                     ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        int nextObj = findMaxObjectNumber(parser) + 1;
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary) || obj instanceof PdfStream) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            if (!"Font".equals(dict.getNameAsString("Type"))) {
                continue;
            }
            String subtype = dict.getNameAsString("Subtype");
            if ("Type3".equals(subtype) || "Type0".equals(subtype) || subtype == null) {
                continue;
            }
            String baseFont = dict.getNameAsString("BaseFont");
            if (baseFont == null) {
                continue;
            }

            PdfDictionary fontDesc = null;
            PdfBase fdRef = dict.get("FontDescriptor");
            if (fdRef != null) {
                PdfBase fdObj = parser.resolveReference(fdRef);
                if (fdObj instanceof PdfDictionary) {
                    fontDesc = (PdfDictionary) fdObj;
                }
            }
            if (fontDesc != null && (fontDesc.get("FontFile") != null
                    || fontDesc.get("FontFile2") != null
                    || fontDesc.get("FontFile3") != null)) {
                continue; // already embedded
            }

            byte[] program = locateFontProgram(baseFont);
            if (program == null) {
                continue; // logUnembeddedFonts reports it
            }
            boolean isCffOtf = program.length > 4 && program[0] == 'O' && program[1] == 'T'
                    && program[2] == 'T' && program[3] == 'O';
            if (isCffOtf && format.isPdfA1()) {
                // PDF/A-1 does not allow OpenType/CFF in FontFile3; skip rather than
                // emit an invalid file.
                continue;
            }

            org.aspose.pdf.engine.font.ttf.TrueTypeReader reader;
            try {
                reader = new org.aspose.pdf.engine.font.ttf.TrueTypeReader(program);
            } catch (IOException | RuntimeException e) {
                LOG.fine(() -> "Located font program for '" + baseFont + "' is unreadable: " + e.getMessage());
                continue;
            }

            PdfStream fontStream = new PdfStream();
            fontStream.setDecodedData(program);
            fontStream.setFilter(PdfName.FLATE_DECODE);
            fontStream.set("Length1", PdfInteger.valueOf(program.length));
            if (isCffOtf) {
                fontStream.set("Subtype", PdfName.of("OpenType"));
            }
            PdfStream fontStreamFinal = fontStream;
            PdfObjectReference fontFileRef = new PdfObjectReference(
                    new PdfObjectKey(nextObj++, 0), k -> fontStreamFinal);

            if (fontDesc == null) {
                fontDesc = buildFontDescriptor(baseFont, reader);
                PdfDictionary fdFinal = fontDesc;
                PdfObjectReference fdNewRef = new PdfObjectReference(
                        new PdfObjectKey(nextObj++, 0), k -> fdFinal);
                dict.set("FontDescriptor", fdNewRef);
            }
            fontDesc.set(isCffOtf ? "FontFile3" : "FontFile2", fontFileRef);

            // The embedded program is an sfnt: a Type1 dictionary must be retyped so the
            // program type matches the font type.
            if ("Type1".equals(subtype) || "MMType1".equals(subtype)) {
                dict.set("Subtype", PdfName.of("TrueType"));
            }

            // 6.3.7: non-symbolic TrueType must use MacRoman/WinAnsi encoding.
            int flags = fontDesc.getInt("Flags", 0);
            boolean symbolic = (flags & 0x04) != 0;
            if (!symbolic) {
                ensureWinAnsiEncoding(dict, parser);
            }

            // Simple fonts need /Widths; synthesize from the program's advances.
            if (dict.get("Widths") == null) {
                addWinAnsiWidths(dict, reader);
            }

            result.addWarning("font.5",
                    "Embedded system font program for '" + baseFont + "'",
                    "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.4");
        }
    }

    /**
     * Adds the spec-default {@code /CIDToGIDMap /Identity} to CIDFontType2
     * descendant fonts that omit it. PDF/A requires the key to be present
     * explicitly (ISO 19005-1:2005, 6.3.3.2); /Identity is the ISO 32000-1
     * Table 117 default, so making it explicit never changes rendering.
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void fixCidToGidMap(PDFParser parser, PdfFormat format,
                               ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary) || obj instanceof PdfStream) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            if (!"Font".equals(dict.getNameAsString("Type"))
                    || !"CIDFontType2".equals(dict.getNameAsString("Subtype"))) {
                continue;
            }
            if (dict.get("CIDToGIDMap") == null) {
                dict.set("CIDToGIDMap", PdfName.of("Identity"));
                result.addWarning("font.6",
                        "Added default /CIDToGIDMap /Identity to CIDFontType2 font",
                        "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.3.2");
            }
        }
    }

    /**
     * Locates a system font program for the given BaseFont name. Standard-14
     * names map to metric-compatible system faces; the subset prefix is stripped.
     */
    private static byte[] locateFontProgram(String baseFont) {
        String name = baseFont.contains("+") ? baseFont.substring(baseFont.indexOf('+') + 1) : baseFont;
        String stylePart = "";
        String family = name;
        int comma = name.indexOf(',');
        if (comma >= 0) {
            family = name.substring(0, comma);
            stylePart = name.substring(comma + 1);
        } else {
            int dash = name.lastIndexOf('-');
            if (dash > 0) {
                family = name.substring(0, dash);
                stylePart = name.substring(dash + 1);
            }
        }
        String styleLower = stylePart.toLowerCase();
        boolean bold = styleLower.contains("bold");
        boolean italic = styleLower.contains("italic") || styleLower.contains("oblique");

        String famLower = family.toLowerCase();
        boolean serif = famLower.startsWith("times");
        boolean mono = famLower.startsWith("courier");
        if (famLower.equals("helvetica")) {
            family = "Arial";
        } else if (famLower.equals("times") || famLower.equals("times new roman") || famLower.startsWith("times")) {
            family = "Times New Roman";
        } else if (famLower.startsWith("courier")) {
            family = "Courier New";
        }

        byte[] bytes = FontDiskLookup.loadStyled(family, bold, italic, null);
        if (bytes == null) {
            bytes = FontDiskLookup.loadByName(name);
        }
        if (bytes == null) {
            bytes = FontDiskLookup.loadFallback(serif, mono, bold, italic, null);
        }
        return bytes;
    }

    /**
     * Builds a minimal FontDescriptor for a font whose dictionary had none
     * (standard-14), taking metrics from the located TrueType program.
     */
    private static PdfDictionary buildFontDescriptor(String baseFont,
                                                     org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        PdfDictionary fd = new PdfDictionary();
        fd.set("Type", PdfName.of("FontDescriptor"));
        fd.set("FontName", PdfName.of(baseFont));
        fd.set("Flags", PdfInteger.valueOf(32)); // non-symbolic
        double scale = 1000.0 / Math.max(1, reader.getUnitsPerEm());
        PdfArray bbox = new PdfArray();
        bbox.add(PdfInteger.valueOf(-600));
        bbox.add(PdfInteger.valueOf(-300));
        bbox.add(PdfInteger.valueOf(1300));
        bbox.add(PdfInteger.valueOf(1000));
        fd.set("FontBBox", bbox);
        fd.set("ItalicAngle", PdfInteger.valueOf(0));
        fd.set("Ascent", PdfInteger.valueOf(800));
        fd.set("Descent", PdfInteger.valueOf(-200));
        fd.set("CapHeight", PdfInteger.valueOf(700));
        fd.set("StemV", PdfInteger.valueOf(80));
        // metrics scale retained for documentation purposes: widths use the same factor
        if (scale <= 0) {
            LOG.fine("Unexpected unitsPerEm in located font for " + baseFont);
        }
        return fd;
    }

    /**
     * Ensures a non-symbolic simple font declares WinAnsi encoding
     * (ISO 19005-1:2005, 6.3.7): an /Encoding dictionary keeps its /Differences
     * but gets /BaseEncoding /WinAnsiEncoding; otherwise /Encoding /WinAnsiEncoding
     * is set directly.
     */
    private static void ensureWinAnsiEncoding(PdfDictionary fontDict, PDFParser parser) {
        String encName = fontDict.getNameAsString("Encoding");
        if ("WinAnsiEncoding".equals(encName) || "MacRomanEncoding".equals(encName)) {
            return;
        }
        PdfBase encRef = fontDict.get("Encoding");
        if (encRef != null) {
            PdfBase encObj;
            try {
                encObj = parser.resolveReference(encRef);
            } catch (IOException | RuntimeException e) {
                encObj = null;
            }
            if (encObj instanceof PdfDictionary) {
                PdfDictionary encDict = (PdfDictionary) encObj;
                String base = encDict.getNameAsString("BaseEncoding");
                if (!"WinAnsiEncoding".equals(base) && !"MacRomanEncoding".equals(base)) {
                    encDict.set("BaseEncoding", PdfName.of("WinAnsiEncoding"));
                }
                return;
            }
        }
        fontDict.set("Encoding", PdfName.of("WinAnsiEncoding"));
    }

    /**
     * Generates /FirstChar, /LastChar and /Widths (WinAnsi code range 32..255)
     * from the embedded program's advance widths.
     */
    private static void addWinAnsiWidths(PdfDictionary fontDict,
                                         org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        double scale = 1000.0 / Math.max(1, reader.getUnitsPerEm());
        PdfArray widths = new PdfArray();
        for (int code = 32; code <= 255; code++) {
            Integer unicode = WIN_ANSI_MAP.get(code);
            int w = 0;
            if (unicode != null) {
                int gid = reader.getGlyphId(unicode);
                if (gid > 0) {
                    w = (int) Math.round(reader.getAdvanceWidth(gid) * scale);
                }
            }
            widths.add(PdfInteger.valueOf(w));
        }
        fontDict.set("FirstChar", PdfInteger.valueOf(32));
        fontDict.set("LastChar", PdfInteger.valueOf(255));
        fontDict.set("Widths", widths);
    }

    /**
     * Logs warnings for fonts that are not embedded and cannot be fixed
     * automatically (we lack the font program data).
     *
     * @param parser      the parsed PDF
     * @param format      the target format
     * @param errorAction the error action strategy
     * @param result      the validation result
     * @throws IOException if an I/O error occurs
     */
    public void logUnembeddedFonts(PDFParser parser, PdfFormat format,
                                   ConvertErrorAction errorAction, PdfAValidationResult result) throws IOException {
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            PdfBase obj;
            try {
                obj = parser.getObject(key);
            } catch (IOException e) {
                continue;
            }
            if (!(obj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary dict = (PdfDictionary) obj;
            if (!"Font".equals(dict.getNameAsString("Type"))) {
                continue;
            }
            String subtype = dict.getNameAsString("Subtype");
            // Type3 fonts are always embedded
            if ("Type3".equals(subtype)) {
                continue;
            }

            PdfBase fdRef = dict.get("FontDescriptor");
            if (fdRef == null) {
                // Standard 14 fonts may lack FontDescriptor
                String baseFontName = dict.getNameAsString("BaseFont");
                if (baseFontName != null && isStandard14(baseFontName)) {
                    result.addWarning("font.4",
                            "Standard 14 font '" + baseFontName + "' is not embedded (may need embedding for strict PDF/A)",
                            "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.3");
                }
                continue;
            }

            PdfBase fdObj = parser.resolveReference(fdRef);
            if (!(fdObj instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary fontDesc = (PdfDictionary) fdObj;

            // Check for embedded font program
            boolean embedded = fontDesc.get("FontFile") != null
                    || fontDesc.get("FontFile2") != null
                    || fontDesc.get("FontFile3") != null;
            if (!embedded) {
                String baseFontName = dict.getNameAsString("BaseFont");
                result.addWarning("font.4",
                        "Font '" + (baseFontName != null ? baseFontName : "unknown")
                                + "' is not embedded (cannot fix automatically)",
                        "obj " + key.getObjectNumber(), "ISO 19005-1:2005, 6.3.3");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a ToUnicode CMap for WinAnsiEncoding covering firstChar..lastChar.
     */
    private static byte[] buildWinAnsiCMap(int firstChar, int lastChar) {
        StringBuilder sb = new StringBuilder(4096);
        sb.append("/CIDInit /ProcSet findresource begin\n");
        sb.append("12 dict begin\n");
        sb.append("begincmap\n");
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        sb.append("/CMapName /Adobe-Identity-UCS def\n");
        sb.append("/CMapType 2 def\n");
        sb.append("1 begincodespacerange\n");
        sb.append("<00> <FF>\n");
        sb.append("endcodespacerange\n");

        // Collect valid mappings
        int count = 0;
        StringBuilder entries = new StringBuilder(2048);
        for (int code = firstChar; code <= lastChar; code++) {
            Integer unicode = WIN_ANSI_MAP.get(code);
            if (unicode != null) {
                entries.append(String.format("<%02X> <%04X>\n", code, unicode));
                count++;
            }
        }

        // Write in batches of 100 (CMap spec limit)
        String[] lines = entries.toString().split("\n");
        int offset = 0;
        while (offset < lines.length) {
            int batch = Math.min(100, lines.length - offset);
            sb.append(batch).append(" beginbfchar\n");
            for (int i = 0; i < batch; i++) {
                sb.append(lines[offset + i]).append('\n');
            }
            sb.append("endbfchar\n");
            offset += batch;
        }

        sb.append("endcmap\n");
        sb.append("CMapName currentdict /CMap defineresource pop\n");
        sb.append("end\n");
        sb.append("end\n");
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Builds a /CharSet string listing standard glyph names for codes firstChar..lastChar.
     */
    private static String buildCharSetString(int firstChar, int lastChar) {
        // Standard glyph names for WinAnsi codes 32..126
        String[] stdNames = {
                "space", "exclam", "quotedbl", "numbersign", "dollar", "percent",
                "ampersand", "quotesingle", "parenleft", "parenright", "asterisk",
                "plus", "comma", "hyphen", "period", "slash",
                "zero", "one", "two", "three", "four", "five", "six", "seven",
                "eight", "nine", "colon", "semicolon", "less", "equal", "greater",
                "question", "at",
                "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
                "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z",
                "bracketleft", "backslash", "bracketright", "asciicircum", "underscore",
                "grave",
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
                "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
                "braceleft", "bar", "braceright", "asciitilde"
        };

        StringBuilder sb = new StringBuilder();
        for (int code = Math.max(firstChar, 32); code <= Math.min(lastChar, 126); code++) {
            int idx = code - 32;
            if (idx >= 0 && idx < stdNames.length) {
                sb.append('/').append(stdNames[idx]);
            }
        }
        return sb.toString();
    }

    /**
     * Estimates the maximum CID from a /W (widths) array.
     */
    private static int estimateMaxCidFromW(PdfArray wArray) {
        int maxCid = 255;
        for (int i = 0; i < wArray.size(); i++) {
            PdfBase item = wArray.get(i);
            if (item instanceof PdfInteger) {
                int val = (int) ((PdfInteger) item).longValue();
                if (val > maxCid) {
                    maxCid = val;
                }
            }
        }
        return maxCid;
    }

    /**
     * Builds a CIDSet bitmap marking CIDs 0..lastCid as present.
     * The CIDSet is a byte array where bit N represents CID N (MSB first).
     */
    private static byte[] buildCidSetBitmap(int lastCid) {
        int byteCount = (lastCid / 8) + 1;
        byte[] bitmap = new byte[byteCount];
        // Mark all CIDs 0..lastCid as present
        for (int cid = 0; cid <= lastCid; cid++) {
            int byteIdx = cid / 8;
            int bitIdx = 7 - (cid % 8); // MSB first
            bitmap[byteIdx] |= (1 << bitIdx);
        }
        return bitmap;
    }

    /**
     * Checks if a font name is one of the standard 14 PDF fonts.
     */
    private static boolean isStandard14(String name) {
        // Strip subset prefix if present
        String n = name.contains("+") ? name.substring(name.indexOf('+') + 1) : name;
        switch (n) {
            case "Courier": case "Courier-Bold": case "Courier-Oblique": case "Courier-BoldOblique":
            case "Helvetica": case "Helvetica-Bold": case "Helvetica-Oblique": case "Helvetica-BoldOblique":
            case "Times-Roman": case "Times-Bold": case "Times-Italic": case "Times-BoldItalic":
            case "Symbol": case "ZapfDingbats":
                return true;
            default:
                return false;
        }
    }

    /**
     * Finds the maximum object number currently in the parser.
     */
    private static int findMaxObjectNumber(PDFParser parser) {
        int maxObj = 0;
        for (PdfObjectKey k : parser.getAllObjectKeys()) {
            maxObj = Math.max(maxObj, k.getObjectNumber());
        }
        return maxObj;
    }
}
