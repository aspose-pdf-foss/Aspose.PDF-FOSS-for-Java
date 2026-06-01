package org.aspose.pdf.engine.font;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
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

    /**
     * Creates a Type0Font from a font dictionary.
     *
     * @param fontDict the Type 0 font dictionary
     * @param parser   the PDF parser
     * @throws IOException if reading font data fails
     */
    public Type0Font(COSDictionary fontDict, PDFParser parser) throws IOException {
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
        COSBase dfVal = resolve(fontDict.get("DescendantFonts"));
        if (dfVal instanceof COSArray) {
            COSArray arr = (COSArray) dfVal;
            if (arr.size() > 0) {
                COSBase firstFont = resolve(arr.get(0));
                if (firstFont instanceof COSDictionary) {
                    this.descendantFont = new CIDFont((COSDictionary) firstFont, parser);
                }
            }
        }
    }

    private void initEncodingCMap() {
        COSBase encVal = resolve(fontDict.get("Encoding"));
        if (encVal instanceof COSName) {
            this.encodingName = ((COSName) encVal).getName();
        } else {
            this.encodingName = "Identity-H";
        }
        this.isIdentity = "Identity-H".equals(encodingName) || "Identity-V".equals(encodingName);
    }
}
