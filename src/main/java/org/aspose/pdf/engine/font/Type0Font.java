package org.aspose.pdf.engine.font;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.parser.PDFParser;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Type 0 (Composite) font (ISO 32000-1:2008, §9.7).
 * <p>
 * A composite font consists of a CIDFont descendant and an encoding CMap.
 * The encoding CMap maps byte sequences to CIDs, and the ToUnicode CMap
 * maps CIDs to Unicode. Common encodings: Identity-H, Identity-V.
 * </p>
 */
public class Type0Font extends PdfFont {

    private static final Logger LOG = Logger.getLogger(Type0Font.class.getName());

    private CIDFont descendantFont;
    private String encodingName;
    private boolean isIdentity;
    /** True for a predefined {@code *-UTF16-*} CMap whose codes ARE UTF-16BE. */
    private boolean isUtf16;

    /**
     * Creates a Type0Font from a font dictionary.
     *
     * @param fontDict the Type 0 font dictionary
     * @param parser   the PDF parser
     * @throws IOException if reading font data fails
     */
    public Type0Font(PdfDictionary fontDict, PDFParser parser) throws IOException {
        super(fontDict, parser);

        // Parse /DescendantFonts — always an array with exactly one element
        initDescendantFont();

        // Parse /Encoding
        initEncodingCMap();

        LOG.fine(() -> "Type0Font created: " + baseFont + ", encoding=" + encodingName);
    }

    /**
     * Decodes raw bytes using two-level CID mapping.
     * <p>
     * 1. bytes → CIDs via encoding CMap (or Identity = pass-through)
     * 2. CIDs → Unicode via ToUnicode CMap
     * </p>
     */
    @Override
    public String decode(byte[] charCodes) throws IOException {
        StringBuilder sb = new StringBuilder();

        if (isUtf16) {
            // Codes ARE UTF-16BE code units — decode straight to Unicode.
            // Without this the non-identity path below (no ToUnicode for this
            // font) split every 2-byte code into two Latin-1 characters, so the
            // renderer drew stray glyphs (corpus 43484 KozGoPr6N-Medium /
            // UniJIS-UTF16-H came out as an unreadable smear) and extraction
            // produced raw-byte garbage.
            return new String(charCodes, java.nio.charset.StandardCharsets.UTF_16BE);
        }

        if (isIdentity) {
            // Identity-H/V: each 2 bytes = one CID
            for (int i = 0; i + 1 < charCodes.length; i += 2) {
                int cid = ((charCodes[i] & 0xFF) << 8) | (charCodes[i + 1] & 0xFF);
                appendCidDecoded(sb, cid);
            }
        } else {
            // Non-identity: try 2-byte first, then 1-byte fallback
            int i = 0;
            while (i < charCodes.length) {
                if (i + 1 < charCodes.length) {
                    int twoByteCode = ((charCodes[i] & 0xFF) << 8) | (charCodes[i + 1] & 0xFF);
                    if (toUnicode != null && toUnicode.contains(twoByteCode)) {
                        appendCidDecoded(sb, twoByteCode);
                        i += 2;
                        continue;
                    }
                }
                // Single byte fallback
                int code = charCodes[i] & 0xFF;
                appendCidDecoded(sb, code);
                i++;
            }
        }
        return sb.toString();
    }

    @Override
    public double getWidth(int charCode) {
        // charCode → CID → descendantFont.getWidth(cid)
        if (descendantFont != null) {
            return descendantFont.getWidth(charCode);
        }
        return 1000;
    }

    @Override
    public boolean isComposite() {
        // Type0 fonts always encode CIDs in 2 bytes (Identity-H/V or other
        // CMap), so the renderer iterates Tj raw bytes 2 at a time.
        return true;
    }

    /**
     * Returns the descendant CIDFont.
     *
     * @return the CIDFont, or null
     */
    public CIDFont getDescendantFont() {
        return descendantFont;
    }

    /**
     * Returns the {@link FontDescriptor} carried by the descendant CIDFont
     * (the Type0 root itself doesn't have one — PDF spec §9.7.3 places the
     * descriptor on the descendant). Falls back to the inherited base-class
     * value so callers that set the descriptor manually still get something
     * sensible.
     */
    @Override
    public FontDescriptor getFontDescriptor() {
        if (descendantFont != null && descendantFont.getFontDescriptor() != null) {
            return descendantFont.getFontDescriptor();
        }
        return super.getFontDescriptor();
    }

    /**
     * Returns the encoding name (e.g., "Identity-H").
     *
     * @return the encoding name
     */
    public String getEncodingName() {
        return encodingName;
    }

    /**
     * Returns true when the encoding CMap is Identity-H or Identity-V,
     * i.e. each 2-byte code in the content stream IS the CID.
     *
     * @return true for Identity encodings
     */
    public boolean isIdentityEncoding() {
        return isIdentity;
    }

    private void appendCidDecoded(StringBuilder sb, int cid) {
        // 1. ToUnicode CMap (highest priority when present)
        if (toUnicode != null) {
            String mapped = toUnicode.lookup(cid);
            if (mapped != null) {
                sb.append(mapped);
                return;
            }
        }
        // 2. Embedded font program recovery (CIDFontType2 without /ToUnicode):
        //    CID → GID → Unicode via the descendant's TrueType post/cmap tables.
        if (descendantFont != null) {
            int unicode = descendantFont.cidToUnicode(cid);
            if (unicode > 0) {
                sb.appendCodePoint(unicode);
                return;
            }
        }
        // 3. Encoding fallback
        if (encoding != null && cid < 256) {
            int unicode = encoding.getUnicode(cid);
            if (unicode > 0) {
                sb.append((char) unicode);
                return;
            }
        }
        // 4. Identity fallback
        sb.append((char) cid);
    }

    private void initDescendantFont() throws IOException {
        PdfBase dfVal = resolve(fontDict.get("DescendantFonts"));
        if (dfVal instanceof PdfArray) {
            PdfArray arr = (PdfArray) dfVal;
            if (arr.size() > 0) {
                PdfBase firstFont = resolve(arr.get(0));
                if (firstFont instanceof PdfDictionary) {
                    this.descendantFont = new CIDFont((PdfDictionary) firstFont, parser);
                }
            }
        }
    }

    private void initEncodingCMap() {
        PdfBase encVal = resolve(fontDict.get("Encoding"));
        if (encVal instanceof PdfName) {
            this.encodingName = ((PdfName) encVal).getName();
        } else {
            this.encodingName = "Identity-H";
        }
        this.isIdentity = "Identity-H".equals(encodingName) || "Identity-V".equals(encodingName);
        // Predefined Adobe CMaps whose name carries "UTF16" (UniJIS-UTF16-H,
        // UniGB-UTF16-H, UniKS-UTF16-H, UniCNS-UTF16-H, …) encode each character
        // as a UTF-16BE code unit — the code IS the Unicode. §9.7.5.2.
        this.isUtf16 = encodingName != null && encodingName.contains("UTF16");
    }
}
