package org.aspose.pdf.engine.font.ttf;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the PDF object graph required to embed a TrueType font as a
 * {@code /Type0} composite font with {@code /Identity-H} encoding
 * (ISO 32000-1:2008, §9.7). The resulting root dictionary is suitable to
 * register under a page's {@code /Resources/Font}; content streams that
 * reference it must emit two-byte glyph IDs (big-endian) inside
 * {@code Tj}/{@code TJ}.
 *
 * <p>Mandatory PDF structure assembled here:</p>
 * <pre>
 *   Type0 root          /Type /Font /Subtype /Type0
 *      └─ /DescendantFonts [ CIDFontType2 ]
 *      └─ /Encoding /Identity-H
 *      └─ /ToUnicode &lt;CMap stream&gt;
 *   CIDFontType2        /Type /Font /Subtype /CIDFontType2
 *      └─ /FontDescriptor
 *      └─ /CIDSystemInfo (Adobe / Identity / 0)
 *      └─ /W [...] (optional, omitted — default width 1000 covers CJK)
 *   FontDescriptor      /Type /FontDescriptor
 *      └─ /FontFile2 &lt;TTF bytes&gt;
 *      └─ /Flags 4 (Symbolic) etc.
 * </pre>
 *
 * <p>For simplicity this MVP embeds the <em>entire</em> TTF without
 * subsetting and skips emitting a {@code /W} width array — viewers fall
 * back to the {@code /DW} default width (1000), which matches the CJK
 * em square the regression-test templates were rendered against. Width
 * accuracy would require glyph-usage tracking; not implemented yet.</p>
 */
public final class Type0FontBuilder {

    /**
     * Result tuple from {@link #build}: the {@code /Type0} font dict to
     * register under /Resources/Font, plus the {@link TrueTypeReader} the
     * caller will need to map Unicode → glyph IDs when encoding text.
     */
    public static final class Result {
        public final PdfDictionary type0Font;
        public final TrueTypeReader reader;
        public Result(PdfDictionary type0Font, TrueTypeReader reader) {
            this.type0Font = type0Font;
            this.reader = reader;
        }
    }

    private Type0FontBuilder() {}

    /**
     * Builds a Type0 graph for a <b>Latin / proportional</b> font: identical to {@link #build} but
     * additionally emits a real {@code /W} width array derived from the font's {@code hmtx} table and
     * marks the descriptor {@code /Nonsymbolic}. The base {@link #build} relies on {@code /DW 1000},
     * which is correct for CJK full-width glyphs but spaces proportional Latin text at one em per
     * glyph; for XFA paint fidelity (Arial/Times/…) the per-glyph advance MUST come from the font.
     *
     * @param baseFontName the {@code /BaseFont} value (PDF-friendly form)
     * @param ttfBytes     standalone TrueType bytes
     * @return the assembled Type0 dict (with {@code /W}) + parsed reader
     * @throws java.io.IOException if the TrueType bytes are malformed
     */
    public static Result buildLatin(String baseFontName, byte[] ttfBytes) throws java.io.IOException {
        Result base = build(baseFontName, ttfBytes);
        PdfArray descendants = base.type0Font.getArray("DescendantFonts");
        if (descendants != null && descendants.size() > 0) {
            PdfDictionary cidFont = descendants.getDictionary(0);
            if (cidFont != null) {
                cidFont.set(PdfName.of("W"), widthArray(base.reader));
                PdfDictionary fd = cidFont.getDictionary("FontDescriptor");
                if (fd != null) {
                    // Nonsymbolic (bit 6) — a Latin text font, not a symbol font.
                    fd.set(PdfName.of("Flags"), PdfInteger.valueOf(32));
                }
            }
        }
        return base;
    }

    /**
     * Builds the {@code /W} width array {@code [0 [w0 w1 … w(n-1)]]} (CID == GID under Identity-H)
     * with each advance scaled from font units to the PDF 1000-unit text space.
     */
    private static PdfArray widthArray(TrueTypeReader reader) {
        int upm = reader.getUnitsPerEm();
        if (upm <= 0) {
            upm = 1000;
        }
        int n = reader.getNumGlyphs();
        PdfArray run = new PdfArray();
        for (int gid = 0; gid < n; gid++) {
            int w1000 = (int) Math.round(reader.getAdvanceWidth(gid) * 1000.0 / upm);
            run.add(PdfInteger.valueOf(w1000));
        }
        PdfArray w = new PdfArray();
        w.add(PdfInteger.valueOf(0)); // starting CID
        w.add(run);
        return w;
    }

    /**
     * Builds the Type0 font object graph for the supplied TrueType bytes.
     *
     * @param baseFontName the {@code /BaseFont} value to use on the Type0
     *                     root and the CIDFontType2 descendant; pass the
     *                     PDF-friendly form (no spaces).
     * @param ttfBytes     standalone TrueType bytes (TTC must already be
     *                     unpacked via {@link FontDiskLookup}).
     * @return the assembled Type0 dict + parsed reader for encoding-side use
     * @throws java.io.IOException if the TrueType bytes are malformed
     */
    public static Result build(String baseFontName, byte[] ttfBytes) throws java.io.IOException {
        TrueTypeReader reader = new TrueTypeReader(ttfBytes);
        String pdfName = pdfSafeName(baseFontName);

        // /FontFile2 — embedded raw TTF as a stream.
        PdfStream fontFile = new PdfStream();
        fontFile.set(PdfName.of("Length1"), PdfInteger.valueOf(ttfBytes.length));
        fontFile.setDecodedData(ttfBytes);

        // /FontDescriptor — minimal viable: required keys + FontFile2.
        // Real ascent/descent would come from hhea/OS/2; the bbox from head;
        // we use sane CJK defaults that PDF viewers tolerate.
        PdfDictionary descriptor = new PdfDictionary();
        descriptor.set(PdfName.of("Type"), PdfName.of("FontDescriptor"));
        descriptor.set(PdfName.of("FontName"), PdfName.of(pdfName));
        descriptor.set(PdfName.of("Flags"), PdfInteger.valueOf(4));   // Symbolic
        PdfArray bbox = new PdfArray();
        bbox.add(PdfInteger.valueOf(-200));
        bbox.add(PdfInteger.valueOf(-200));
        bbox.add(PdfInteger.valueOf(1100));
        bbox.add(PdfInteger.valueOf(1100));
        descriptor.set(PdfName.of("FontBBox"), bbox);
        descriptor.set(PdfName.of("ItalicAngle"), PdfInteger.valueOf(0));
        descriptor.set(PdfName.of("Ascent"), PdfInteger.valueOf(880));
        descriptor.set(PdfName.of("Descent"), PdfInteger.valueOf(-120));
        descriptor.set(PdfName.of("CapHeight"), PdfInteger.valueOf(700));
        descriptor.set(PdfName.of("StemV"), PdfInteger.valueOf(80));
        descriptor.set(PdfName.of("FontFile2"), fontFile);

        // CIDSystemInfo — Adobe/Identity/0 is the conventional pairing for
        // Identity-H encoded composite fonts.
        PdfDictionary cidSysInfo = new PdfDictionary();
        cidSysInfo.set(PdfName.of("Registry"), new PdfString("Adobe"));
        cidSysInfo.set(PdfName.of("Ordering"), new PdfString("Identity"));
        cidSysInfo.set(PdfName.of("Supplement"), PdfInteger.valueOf(0));

        // CIDFontType2 descendant
        PdfDictionary cidFont = new PdfDictionary();
        cidFont.set(PdfName.of("Type"), PdfName.of("Font"));
        cidFont.set(PdfName.of("Subtype"), PdfName.of("CIDFontType2"));
        cidFont.set(PdfName.of("BaseFont"), PdfName.of(pdfName));
        cidFont.set(PdfName.of("CIDSystemInfo"), cidSysInfo);
        cidFont.set(PdfName.of("FontDescriptor"), descriptor);
        cidFont.set(PdfName.of("DW"), PdfInteger.valueOf(1000));
        // CIDToGIDMap defaults to /Identity for CIDFontType2 — that's what we want.

        // Type0 root
        PdfDictionary type0 = new PdfDictionary();
        type0.set(PdfName.of("Type"), PdfName.of("Font"));
        type0.set(PdfName.of("Subtype"), PdfName.of("Type0"));
        type0.set(PdfName.of("BaseFont"), PdfName.of(pdfName));
        type0.set(PdfName.of("Encoding"), PdfName.of("Identity-H"));
        PdfArray descendantArr = new PdfArray();
        descendantArr.add(cidFont);
        type0.set(PdfName.of("DescendantFonts"), descendantArr);
        type0.set(PdfName.of("ToUnicode"), buildToUnicodeCMap(reader, pdfName));

        return new Result(type0, reader);
    }

    /**
     * True when the sfnt bytes are a CFF-based OpenType font (signature
     * {@code 'OTTO'}), which must be embedded as {@code /FontFile3} in a
     * {@code CIDFontType0} rather than {@code /FontFile2}/{@code CIDFontType2}.
     * TrueType-outline fonts ({@code 0x00010000} or {@code 'true'}) return false.
     *
     * @param bytes the font program bytes
     * @return true for a CFF OpenType ({@code 'OTTO'}) program
     */
    public static boolean isOpenTypeCff(byte[] bytes) {
        return bytes != null && bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x4F   // 'O'
                && (bytes[1] & 0xFF) == 0x54   // 'T'
                && (bytes[2] & 0xFF) == 0x54   // 'T'
                && (bytes[3] & 0xFF) == 0x4F;  // 'O'
    }

    /**
     * Builds the Type0 object graph for a <b>CFF-based OpenType</b> font
     * (ISO 32000-1:2008 §9.7.4.2): a {@code /CIDFontType0} descendant whose
     * {@code /FontDescriptor} carries the whole OpenType program in a
     * {@code /FontFile3} stream tagged {@code /Subtype /OpenType}. Encoding is
     * {@code /Identity-H} and, for a non-CID-keyed CFF (the common case for a
     * desktop {@code .otf}), CIDs are taken as glyph indices — matching the
     * {@code cmap}-driven glyph IDs the {@link TrueTypeReader} returns. A real
     * {@code /W} width array (from {@code hmtx}) and a {@code /ToUnicode} CMap are
     * emitted exactly as for the TrueType path.
     *
     * @param baseFontName the {@code /BaseFont} value (PDF-friendly form)
     * @param otfBytes     standalone OpenType (CFF) bytes
     * @return the assembled Type0 dict + parsed reader for encoding-side use
     * @throws java.io.IOException if the OpenType bytes are malformed
     */
    public static Result buildOpenType(String baseFontName, byte[] otfBytes) throws java.io.IOException {
        TrueTypeReader reader = new TrueTypeReader(otfBytes);
        String pdfName = pdfSafeName(baseFontName);

        // /FontFile3 — embedded OpenType program, Subtype /OpenType.
        PdfStream fontFile = new PdfStream();
        fontFile.set(PdfName.of("Subtype"), PdfName.of("OpenType"));
        fontFile.setDecodedData(otfBytes);

        PdfDictionary descriptor = new PdfDictionary();
        descriptor.set(PdfName.of("Type"), PdfName.of("FontDescriptor"));
        descriptor.set(PdfName.of("FontName"), PdfName.of(pdfName));
        descriptor.set(PdfName.of("Flags"), PdfInteger.valueOf(32)); // Nonsymbolic
        PdfArray bbox = new PdfArray();
        bbox.add(PdfInteger.valueOf(-200));
        bbox.add(PdfInteger.valueOf(-200));
        bbox.add(PdfInteger.valueOf(1100));
        bbox.add(PdfInteger.valueOf(1100));
        descriptor.set(PdfName.of("FontBBox"), bbox);
        descriptor.set(PdfName.of("ItalicAngle"), PdfInteger.valueOf(0));
        descriptor.set(PdfName.of("Ascent"), PdfInteger.valueOf(880));
        descriptor.set(PdfName.of("Descent"), PdfInteger.valueOf(-120));
        descriptor.set(PdfName.of("CapHeight"), PdfInteger.valueOf(700));
        descriptor.set(PdfName.of("StemV"), PdfInteger.valueOf(80));
        descriptor.set(PdfName.of("FontFile3"), fontFile);

        PdfDictionary cidSysInfo = new PdfDictionary();
        cidSysInfo.set(PdfName.of("Registry"), new PdfString("Adobe"));
        cidSysInfo.set(PdfName.of("Ordering"), new PdfString("Identity"));
        cidSysInfo.set(PdfName.of("Supplement"), PdfInteger.valueOf(0));

        // CIDFontType0 descendant (CFF outlines). No /CIDToGIDMap — that key is
        // only meaningful for CIDFontType2.
        PdfDictionary cidFont = new PdfDictionary();
        cidFont.set(PdfName.of("Type"), PdfName.of("Font"));
        cidFont.set(PdfName.of("Subtype"), PdfName.of("CIDFontType0"));
        cidFont.set(PdfName.of("BaseFont"), PdfName.of(pdfName));
        cidFont.set(PdfName.of("CIDSystemInfo"), cidSysInfo);
        cidFont.set(PdfName.of("FontDescriptor"), descriptor);
        cidFont.set(PdfName.of("DW"), PdfInteger.valueOf(1000));
        cidFont.set(PdfName.of("W"), widthArray(reader));

        PdfDictionary type0 = new PdfDictionary();
        type0.set(PdfName.of("Type"), PdfName.of("Font"));
        type0.set(PdfName.of("Subtype"), PdfName.of("Type0"));
        type0.set(PdfName.of("BaseFont"), PdfName.of(pdfName));
        type0.set(PdfName.of("Encoding"), PdfName.of("Identity-H"));
        PdfArray descendantArr = new PdfArray();
        descendantArr.add(cidFont);
        type0.set(PdfName.of("DescendantFonts"), descendantArr);
        type0.set(PdfName.of("ToUnicode"), buildToUnicodeCMap(reader, pdfName));

        return new Result(type0, reader);
    }

    /**
     * Generates a {@code /ToUnicode} CMap stream that maps every glyph ID in
     * the font's cmap back to a single Unicode codepoint. Lets PDF readers
     * extract text and copy-paste it correctly even though the content
     * stream carries raw glyph IDs (Identity-H).
     */
    private static PdfStream buildToUnicodeCMap(TrueTypeReader reader, String pdfName) {
        // Invert the cmap: glyph_id → first Unicode that maps to it.
        Map<Integer, Integer> entries = reader.getCmapEntries();
        TreeMap<Integer, Integer> gidToUnicode = new TreeMap<>();
        for (Map.Entry<Integer, Integer> e : entries.entrySet()) {
            int unicode = e.getKey();
            int gid = e.getValue();
            if (gid == 0) continue;
            gidToUnicode.putIfAbsent(gid, unicode);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/CIDInit /ProcSet findresource begin\n");
        sb.append("12 dict begin\n");
        sb.append("begincmap\n");
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n");
        sb.append("/CMapName /Adobe-Identity-UCS def\n");
        sb.append("/CMapType 2 def\n");
        sb.append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");

        // beginbfchar groups (up to 100 entries per group per spec). The CMap
        // format encodes supplementary-plane codepoints as UTF-16 surrogate
        // pairs inside the bfchar value (Adobe CMap spec, §3.9.4).
        List<int[]> chunk = new ArrayList<>(100);
        for (Map.Entry<Integer, Integer> e : gidToUnicode.entrySet()) {
            int gid = e.getKey();
            int u = e.getValue();
            // Lone surrogate halves never map to anything meaningful; skip.
            if (u >= 0xD800 && u <= 0xDFFF) continue;
            chunk.add(new int[]{gid, u});
            if (chunk.size() == 100) flushBfchar(sb, chunk);
        }
        if (!chunk.isEmpty()) flushBfchar(sb, chunk);

        sb.append("endcmap\n");
        sb.append("CMapName currentdict /CMap defineresource pop\n");
        sb.append("end\nend\n");

        byte[] body = sb.toString().getBytes(StandardCharsets.US_ASCII);
        PdfStream cmap = new PdfStream();
        cmap.setDecodedData(body);
        return cmap;
    }

    private static void flushBfchar(StringBuilder sb, List<int[]> chunk) {
        sb.append(chunk.size()).append(" beginbfchar\n");
        for (int[] pair : chunk) {
            sb.append('<').append(hex4(pair[0])).append("> <")
                    .append(hexUtf16(pair[1])).append(">\n");
        }
        sb.append("endbfchar\n");
        chunk.clear();
    }

    private static String hex4(int v) {
        return String.format("%04X", v & 0xFFFF);
    }

    /**
     * Emits {@code codePoint} as its UTF-16 hex representation: 4 hex digits
     * for BMP, 8 hex digits (surrogate pair) for supplementary planes — the
     * Adobe ToUnicode CMap value format (§3.9.4 of the CMap spec).
     */
    private static String hexUtf16(int codePoint) {
        if (codePoint <= 0xFFFF) {
            return String.format("%04X", codePoint);
        }
        int v = codePoint - 0x10000;
        int high = 0xD800 | (v >>> 10);
        int low  = 0xDC00 | (v & 0x3FF);
        return String.format("%04X%04X", high, low);
    }

    /**
     * Replaces characters that PDF doesn't allow inside a /Name with the
     * placeholder {@code _}. Spaces in particular have to go (e.g.
     * {@code "Arial Unicode MS"} → {@code "Arial_Unicode_MS"}). The result
     * is opaque to viewers — what matters is that the same name is used
     * consistently on the Type0 root and descendant.
     */
    private static String pdfSafeName(String name) {
        if (name == null || name.isEmpty()) return "Font";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == ' ' || c == '#' || c == '(' || c == ')'
                    || c == '<' || c == '>' || c == '[' || c == ']'
                    || c == '{' || c == '}' || c == '/' || c == '%') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
