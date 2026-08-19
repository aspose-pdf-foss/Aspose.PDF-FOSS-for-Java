package org.aspose.pdf.sdm.doc;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LineBreak;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Legacy binary Word ({@code .doc}, Word 97&ndash;2003, [MS-DOC]) &rarr; SDM
 * reader — the third source format of the shared SDM&rarr;PDF pipeline (after
 * HTML and DOCX). Clean-room, zero-dependency.
 *
 * <p>Coverage (V1): main-document text via the piece table (Clx/PlcPcd, both
 * ANSI-compressed and UTF-16 pieces), character formatting from CHPX FKPs
 * (bold/italic/strike/underline, size, font via SttbfFfn, colour), paragraph
 * formatting from PAPX FKPs (alignment, indents, space before/after, built-in
 * heading styles via istd 1&ndash;9), simple tables (cell/row marks 0x07 +
 * {@code sprmPFInTable}/{@code sprmPFTtp}), page size and margins from the
 * first section's SEPX, page breaks (0x0C), inline pictures (0x01 +
 * {@code sprmCPicLocation} &rarr; Data-stream PICF &rarr; OfficeArt
 * JPEG/PNG/DIB blips; EMF/WMF metafiles are skipped). Fields keep their
 * cached result; headers/footers/footnotes (outside {@code ccpText}) are
 * not read.</p>
 */
public final class DocSdmReader {

    private static final Logger LOG = Logger.getLogger(DocSdmReader.class.getName());
    private static final Charset CP1252 = Charset.forName("windows-1252");

    // ---- parsed state -------------------------------------------------------

    private byte[] wordStream;
    private byte[] tableStream;
    /** The Data stream (inline picture PICF + OfficeArt blips), or null. */
    private byte[] dataStream;
    /** Figures parsed from 0x01 picture chars of the paragraph just built. */
    private final List<SdmBlock> pendingFigures = new ArrayList<>();
    private int imageSeq;
    private char[] text;          // main document text, index == CP
    private Piece[] pieces;
    private Plc bteChpx;          // FC -> CHPX FKP page
    private Plc btePapx;          // FC -> PAPX FKP page
    private String[] fontNames;
    private SdmDocument sdm;
    /** Section start CP → starts a new page? (SEPX sprmSBkc >= 2). A 0x0C at a
     *  section boundary is a SECTION mark, not automatically a page break —
     *  continuous sections (bkc=0) flow on without breaking. */
    private final java.util.Map<Integer, Boolean> sectionPageBreak = new java.util.HashMap<>();
    /** Section start CP → column count (only sections with more than one). */
    private final java.util.Map<Integer, Integer> sectionColumns = new java.util.HashMap<>();
    /** Explicit column widths (pt) per section start CP (sprmSDxaColWidth). */
    private final java.util.Map<Integer, double[]> sectionColWidths = new java.util.HashMap<>();
    /** Inter-column gap (pt) per section start CP (sprmSDxaColumns). */
    private final java.util.Map<Integer, Double> sectionColGap = new java.util.HashMap<>();
    /** Section start CP → end CP (exclusive). */
    private final java.util.Map<Integer, Integer> sectionEnds = new java.util.HashMap<>();
    /** Office Art shape anchors: CP of the 0x08 anchor char. */
    private int[] spaCp = new int[0];
    /** Shape rectangles, page-relative points: {left, top, width, height}. */
    private double[][] spaRect = new double[0][];
    /** Shape ids parallel to {@link #spaCp} (match Escher SpContainers). */
    private int[] spaSpid = new int[0];
    /** spid → ARGB fill colour (explicit, non-white Escher fills only). */
    private final java.util.Map<Integer, Integer> shapeFill = new java.util.HashMap<>();
    /** BStore FBSEs in pib order (1-based): {btWin32, foDelay, size, fbseBody, fbseLen}. */
    private final List<long[]> blipStore = new ArrayList<>();
    /** spid → 1-based pib (FOPT 0x0104) of picture shapes. */
    private final java.util.Map<Integer, Integer> shapePib = new java.util.HashMap<>();
    /** spid → z-order (SpContainer file order, bottom to top). */
    private final java.util.Map<Integer, Integer> shapeZ = new java.util.HashMap<>();
    /** Escher walk state: the spid of the SpContainer being parsed. */
    private int escherSpid;
    /** Escher walk state: the shape TYPE (FSP header instance) of that spid. */
    private int escherShapeType;

    /** One piece-table entry: a CP range and where its characters live. */
    private static final class Piece {
        int cpStart;
        int cpEnd;
        int fcStart;      // byte offset in WordDocument stream
        boolean ansi;     // compressed (cp1252, 1 byte/char) vs UTF-16LE
    }

    /** A PLC: n+1 positions + n fixed-size data elements. */
    private static final class Plc {
        int[] pos;
        byte[][] data;
    }

    /**
     * Reads a legacy {@code .doc} into an SDM document.
     *
     * @param docBytes the whole file; must not be null
     * @return the document model
     * @throws IOException when the file is not a readable Word 97+ document
     */
    public SdmDocument read(byte[] docBytes) throws IOException {
        CompoundFile cfb = new CompoundFile(docBytes);
        wordStream = cfb.getStream("WordDocument");
        if (wordStream == null || wordStream.length < 0x200) {
            throw new IOException("not a Word document: WordDocument stream missing");
        }
        int wIdent = u16(wordStream, 0);
        if (wIdent != 0xA5EC && wIdent != 0xA5DC) {
            throw new IOException("not a Word document: bad FIB ident 0x"
                    + Integer.toHexString(wIdent));
        }
        int flags = u16(wordStream, 0x0A);
        if ((flags & 0x0100) != 0) {
            throw new IOException("encrypted .doc is not supported");
        }
        String tableName = (flags & 0x0200) != 0 ? "1Table" : "0Table";
        tableStream = cfb.getStream(tableName);
        if (tableStream == null) {
            tableStream = cfb.getStream((flags & 0x0200) != 0 ? "0Table" : "1Table");
        }
        if (tableStream == null) {
            throw new IOException("Word table stream missing (" + tableName + ")");
        }
        dataStream = cfb.getStream("Data");

        int ccpText = i32(wordStream, 0x4C);
        readPieceTable(i32(wordStream, 0x1A2), i32(wordStream, 0x1A6), ccpText);
        bteChpx = readPlc(tableStream, i32(wordStream, 0xFA), i32(wordStream, 0xFE), 4);
        btePapx = readPlc(tableStream, i32(wordStream, 0x102), i32(wordStream, 0x106), 4);
        fontNames = readFontNames(i32(wordStream, 0x112), i32(wordStream, 0x116));
        readStyleSheet(i32(wordStream, 0xA2), i32(wordStream, 0xA6));

        sdm = new SdmDocument();
        readSection(i32(wordStream, 0xCA), i32(wordStream, 0xCE));
        readShapeAnchors(i32(wordStream, 0x1DA), i32(wordStream, 0x1DE));
        readEscher(i32(wordStream, 0x22A), i32(wordStream, 0x22E));
        buildBlocks();
        return sdm;
    }

    // ---- Office Art (Escher) fills ------------------------------------------

    /**
     * Walks the OfficeArt container ({@code fcDggInfo}) collecting each
     * shape's explicit fill colour: FSP (0xF00A) carries the spid, the
     * following FOPT (0xF00B) the {@code fillColor} property. Only non-white
     * explicit fills are kept — they are the visible grey bands of forms.
     */
    private void readEscher(int fc, int lcb) {
        if (lcb <= 8 || fc < 0 || fc + lcb > tableStream.length) {
            return;
        }
        escherSpid = 0;
        walkEscher(fc, Math.min(fc + lcb, tableStream.length));
        if (System.getProperty("sdm.doc.debug") != null) {
            System.out.println("[DOCDBG] escher fills=" + shapeFill.size()
                    + " sample=" + shapeFill.entrySet().stream().limit(5)
                            .map(e -> e.getKey() + ":" + Integer.toHexString(e.getValue()))
                            .collect(java.util.stream.Collectors.joining(",")));
        }
    }

    private void walkEscher(int p, int end) {
        while (p + 8 <= end) {
            int verInst = u16(tableStream, p);
            int type = u16(tableStream, p + 2);
            // OfficeArtContent interleaves containers with 1-byte drawing
            // labels (dgglbl) — a non-Escher record type means we are off by
            // a byte; resync instead of aborting the walk.
            if (type < 0xF000) {
                p++;
                continue;
            }
            long len = u32(tableStream, p + 4);
            int body = p + 8;
            int bodyEnd = (int) Math.min(end, body + Math.max(0, len));
            if ((verInst & 0x0F) == 0x0F) {
                walkEscher(body, bodyEnd); // container — recurse
            } else if (type == 0xF00A && body + 4 <= end) { // OfficeArtFSP
                escherSpid = i32(tableStream, body);
                escherShapeType = verInst >> 4;
                shapeZ.put(escherSpid, shapeZ.size());
                if (System.getProperty("sdm.doc.debug3") != null) {
                    System.out.println("[FSP] spid=" + escherSpid + " type=" + escherShapeType);
                }
            } else if (type == 0xF00B) { // OfficeArtFOPT
                parseFopt(body, verInst >> 4, bodyEnd);
            } else if (type == 0xF007 && body + 32 <= end) { // OfficeArtFBSE
                // btWin32(1) btMac(1) rgbUid(16) tag(2) size(4) cRef(4) foDelay(4)
                blipStore.add(new long[]{tableStream[body] & 0xFF,
                        u32(tableStream, body + 28), u32(tableStream, body + 20),
                        body, len});
            }
            if (bodyEnd <= p) {
                break; // malformed length — bail out
            }
            p = bodyEnd;
        }
    }

    private void parseFopt(int p, int count, int end) {
        Integer color = null;
        boolean explicitlyUnfilled = false;
        int q = p;
        for (int i = 0; i < count && q + 6 <= end; i++, q += 6) {
            int pid = u16(tableStream, q) & 0x3FFF;
            long val = u32(tableStream, q + 2);
            if (System.getProperty("sdm.doc.debug2") != null) {
                System.out.println(String.format("[FOPT] spid=%d pid=0x%04X val=0x%08X",
                        escherSpid, pid, val));
            }
            if (pid == 0x0104 && escherSpid != 0) { // pib: BStore blip index
                shapePib.put(escherSpid, (int) val);
            } else if (pid == 0x0181) { // fillColor (COLORREF: 0x00BBGGRR)
                if ((val & 0xFF000000L) == 0) {
                    int r = (int) (val & 0xFF);
                    int g = (int) ((val >> 8) & 0xFF);
                    int b = (int) ((val >> 16) & 0xFF);
                    color = (r << 16) | (g << 8) | b;
                }
            } else if (pid == 0x01BF) { // fill-group booleans (debug hook below)
                boolean useFilled = (val & 0x00100000L) != 0;
                boolean filled = (val & 0x00000010L) != 0;
                if (useFilled && !filled) {
                    explicitlyUnfilled = true;
                }
            }
        }
        // A LINE/connector has no fillable area — its fillColor is noise
        // (black separator lines would otherwise flood their bounding box).
        boolean lineShape = escherShapeType == 20
                || (escherShapeType >= 32 && escherShapeType <= 40);
        if (escherSpid != 0 && color != null && !explicitlyUnfilled && !lineShape
                && (color & 0xFFFFFF) != 0xFFFFFF) {
            shapeFill.put(escherSpid, 0xFF000000 | color);
        }
    }

    /**
     * Office Art shape anchors ({@code plcSpaMom}, FIB pair 40): each 0x08
     * anchor character maps to an SPA carrying the shape's page-relative
     * rectangle in twips. Forms draw their fill-in grids as such shapes — the
     * outlines are re-emitted at their absolute positions so the form keeps
     * its boxes (shape CONTENT/geometry beyond the bounding box is skipped).
     */
    private void readShapeAnchors(int fc, int lcb) {
        if (lcb <= 4 || fc < 0 || fc + lcb > tableStream.length) {
            return;
        }
        int n = (lcb - 4) / (4 + 26);
        if (n <= 0) {
            return;
        }
        spaCp = new int[n];
        spaRect = new double[n][];
        spaSpid = new int[n];
        int rects = fc + (n + 1) * 4;
        for (int i = 0; i < n; i++) {
            spaCp[i] = i32(tableStream, fc + i * 4);
            spaSpid[i] = i32(tableStream, rects + i * 26);
            double left = i32(tableStream, rects + i * 26 + 4) / 20.0;
            double top = i32(tableStream, rects + i * 26 + 8) / 20.0;
            double right = i32(tableStream, rects + i * 26 + 12) / 20.0;
            double bottom = i32(tableStream, rects + i * 26 + 16) / 20.0;
            spaRect[i] = new double[]{left, top, Math.max(0, right - left),
                    Math.max(0, bottom - top)};
        }
    }

    /** Diagnostic hook (tests): the WHOLE piece-table text — main document plus
     *  footnotes, headers, annotations and TEXTBOX stories that follow it. */
    static char[] fullTextForDiagnostics(byte[] docBytes) throws IOException {
        DocSdmReader r = new DocSdmReader();
        CompoundFile cfb = new CompoundFile(docBytes);
        r.wordStream = cfb.getStream("WordDocument");
        if (r.wordStream == null) {
            return new char[0];
        }
        int flags = u16(r.wordStream, 0x0A);
        r.tableStream = cfb.getStream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        if (r.tableStream == null) {
            return new char[0];
        }
        r.readPieceTable(i32(r.wordStream, 0x1A2), i32(r.wordStream, 0x1A6), Integer.MAX_VALUE);
        return r.text;
    }

    // ---- piece table --------------------------------------------------------

    private void readPieceTable(int fcClx, int lcbClx, int ccpText) throws IOException {
        List<Piece> out = new ArrayList<>();
        if (lcbClx > 0 && fcClx >= 0 && fcClx + lcbClx <= tableStream.length) {
            int p = fcClx;
            int end = fcClx + lcbClx;
            // Skip Prc (grpprl) blocks preceding the Pcdt.
            while (p < end && (tableStream[p] & 0xFF) == 0x01) {
                int cb = u16(tableStream, p + 1);
                p += 3 + cb;
            }
            if (p < end && (tableStream[p] & 0xFF) == 0x02) {
                int lcb = i32(tableStream, p + 1);
                int plc = p + 5;
                int n = (lcb - 4) / 12;
                for (int i = 0; i < n; i++) {
                    int cpS = i32(tableStream, plc + i * 4);
                    int cpE = i32(tableStream, plc + (i + 1) * 4);
                    int pcd = plc + (n + 1) * 4 + i * 8;
                    long fc = u32(tableStream, pcd + 2);
                    Piece piece = new Piece();
                    piece.cpStart = cpS;
                    piece.cpEnd = cpE;
                    piece.ansi = (fc & 0x40000000L) != 0;
                    long raw = fc & 0xBFFFFFFFL & ~0x40000000L;
                    piece.fcStart = (int) (piece.ansi ? raw / 2 : raw);
                    out.add(piece);
                }
            }
        }
        if (out.isEmpty()) {
            // No piece table (very old / simple save): text at fcMin..fcMac.
            int fcMin = i32(wordStream, 0x18);
            int fcMac = i32(wordStream, 0x1C);
            Piece piece = new Piece();
            piece.cpStart = 0;
            piece.cpEnd = Math.max(0, Math.min(ccpText, fcMac - fcMin));
            piece.fcStart = fcMin;
            piece.ansi = true;
            out.add(piece);
        }
        pieces = out.toArray(new Piece[0]);

        int total = 0;
        for (Piece piece : pieces) {
            total = Math.max(total, piece.cpEnd);
        }
        total = Math.min(total, ccpText > 0 ? ccpText : total);
        text = new char[total];
        for (Piece piece : pieces) {
            for (int cp = piece.cpStart; cp < piece.cpEnd && cp < total; cp++) {
                int rel = cp - piece.cpStart;
                if (piece.ansi) {
                    int off = piece.fcStart + rel;
                    text[cp] = off >= 0 && off < wordStream.length
                            ? new String(wordStream, off, 1, CP1252).charAt(0) : ' ';
                } else {
                    int off = piece.fcStart + rel * 2;
                    text[cp] = off >= 0 && off + 2 <= wordStream.length
                            ? (char) u16(wordStream, off) : ' ';
                }
            }
        }
    }

    /** The WordDocument-stream byte offset of a CP, or -1. */
    private int fcOf(int cp) {
        for (Piece p : pieces) {
            if (cp >= p.cpStart && cp < p.cpEnd) {
                return p.ansi ? p.fcStart + (cp - p.cpStart)
                        : p.fcStart + (cp - p.cpStart) * 2;
            }
        }
        return -1;
    }

    /** The last CP (exclusive) still inside the same piece as {@code cp}. */
    private int pieceEnd(int cp) {
        for (Piece p : pieces) {
            if (cp >= p.cpStart && cp < p.cpEnd) {
                return p.cpEnd;
            }
        }
        return cp + 1;
    }

    // ---- formatting lookups -------------------------------------------------

    private static Plc readPlc(byte[] src, int fc, int lcb, int cbData) {
        Plc plc = new Plc();
        if (lcb <= 4 || fc < 0 || fc + lcb > src.length) {
            plc.pos = new int[]{0};
            plc.data = new byte[0][];
            return plc;
        }
        int n = (lcb - 4) / (4 + cbData);
        plc.pos = new int[n + 1];
        for (int i = 0; i <= n; i++) {
            plc.pos[i] = i32(src, fc + i * 4);
        }
        plc.data = new byte[n][];
        int base = fc + (n + 1) * 4;
        for (int i = 0; i < n; i++) {
            plc.data[i] = new byte[cbData];
            System.arraycopy(src, base + i * cbData, plc.data[i], 0, cbData);
        }
        return plc;
    }

    /** The grpprl bytes governing the character at {@code fc}, or empty. */
    private byte[] chpxAt(int fc) {
        int pn = fkpPageFor(bteChpx, fc);
        if (pn < 0) {
            return new byte[0];
        }
        int page = pn * 512;
        if (page + 512 > wordStream.length) {
            return new byte[0];
        }
        int crun = wordStream[page + 511] & 0xFF;
        for (int i = 0; i < crun; i++) {
            int fcA = i32(wordStream, page + i * 4);
            int fcB = i32(wordStream, page + (i + 1) * 4);
            if (fc >= fcA && fc < fcB) {
                int rgb = wordStream[page + (crun + 1) * 4 + i] & 0xFF;
                if (rgb == 0) {
                    return new byte[0];
                }
                int off = page + rgb * 2;
                int cb = wordStream[off] & 0xFF;
                if (off + 1 + cb > page + 512) {
                    return new byte[0];
                }
                byte[] grpprl = new byte[cb];
                System.arraycopy(wordStream, off + 1, grpprl, 0, cb);
                return grpprl;
            }
        }
        return new byte[0];
    }

    /** The FC (exclusive) where the CHPX run containing {@code fc} ends. */
    private int chpxRunEnd(int fc) {
        int pn = fkpPageFor(bteChpx, fc);
        if (pn >= 0) {
            int page = pn * 512;
            if (page + 512 <= wordStream.length) {
                int crun = wordStream[page + 511] & 0xFF;
                for (int i = 0; i < crun; i++) {
                    int fcA = i32(wordStream, page + i * 4);
                    int fcB = i32(wordStream, page + (i + 1) * 4);
                    if (fc >= fcA && fc < fcB) {
                        return fcB;
                    }
                }
            }
        }
        return fc + 1;
    }

    /** PAPX grpprl (istd stripped, returned via the 1-element array), or empty. */
    private byte[] papxAt(int fc, int[] istdOut) {
        int pn = fkpPageFor(btePapx, fc);
        if (pn < 0) {
            return new byte[0];
        }
        int page = pn * 512;
        if (page + 512 > wordStream.length) {
            return new byte[0];
        }
        int crun = wordStream[page + 511] & 0xFF;
        for (int i = 0; i < crun; i++) {
            int fcA = i32(wordStream, page + i * 4);
            int fcB = i32(wordStream, page + (i + 1) * 4);
            if (fc >= fcA && fc < fcB) {
                int bx = wordStream[page + (crun + 1) * 4 + i * 13] & 0xFF;
                if (bx == 0) {
                    return new byte[0];
                }
                int off = page + bx * 2;
                int cw = wordStream[off] & 0xFF;
                int grpprlOff;
                int len;
                if (cw == 0) {
                    cw = wordStream[off + 1] & 0xFF;
                    grpprlOff = off + 2;
                    len = 2 * cw;
                } else {
                    grpprlOff = off + 1;
                    len = 2 * cw - 1;
                }
                if (len < 2 || grpprlOff + len > page + 512) {
                    return new byte[0];
                }
                istdOut[0] = u16(wordStream, grpprlOff);
                byte[] grpprl = new byte[len - 2];
                System.arraycopy(wordStream, grpprlOff + 2, grpprl, 0, len - 2);
                return grpprl;
            }
        }
        return new byte[0];
    }

    /** The FKP page number whose FC interval contains {@code fc}, or -1. */
    private static int fkpPageFor(Plc bte, int fc) {
        for (int i = 0; i + 1 < bte.pos.length; i++) {
            if (fc >= bte.pos[i] && fc < bte.pos[i + 1]) {
                return (int) u32(bte.data[i], 0) & 0x3FFFFF;
            }
        }
        return bte.data.length > 0 ? (int) u32(bte.data[bte.data.length - 1], 0) & 0x3FFFFF : -1;
    }

    // ---- sprm interpretation ------------------------------------------------

    /** Character style of a run: the paragraph style's resolved CHP overlaid
     *  with the run's direct CHPX. Never null (may be an all-default style). */
    private TextStyle runChp(byte[] grpprl, int istd) {
        TextStyle s = cloneStyle(styleChp(istd));
        applyChpxInto(grpprl, s);
        return s;
    }

    private static TextStyle cloneStyle(TextStyle src) {
        TextStyle s = new TextStyle();
        if (src != null) {
            s.setBold(src.isBold());
            s.setItalic(src.isItalic());
            s.setUnderline(src.isUnderline());
            s.setStrikethrough(src.isStrikethrough());
            s.setFontSize(src.getFontSize());
            s.setColor(src.getColor());
            s.setFontFamily(src.getFontFamily());
        }
        return s;
    }

    /** Applies CHP sprms to {@code s} in place (toggles resolve against the
     *  current value: 0x80 = as-style, 0x81 = invert style). */
    private void applyChpxInto(byte[] grpprl, TextStyle s) {
        int p = 0;
        while (p + 2 <= grpprl.length) {
            int sprm = u16(grpprl, p);
            p += 2;
            int size = operandSize(sprm, grpprl, p);
            if (size < 0 || p + size > grpprl.length) {
                break;
            }
            switch (sprm) {
                case 0x0835: // sprmCFBold
                    s.setBold(toggle(grpprl[p], s.isBold()));
                    break;
                case 0x0836: // sprmCFItalic
                    s.setItalic(toggle(grpprl[p], s.isItalic()));
                    break;
                case 0x0837: // sprmCFStrike
                    s.setStrikethrough(toggle(grpprl[p], s.isStrikethrough()));
                    break;
                case 0x2A3E: // sprmCKul (underline kind)
                    s.setUnderline((grpprl[p] & 0xFF) != 0);
                    break;
                case 0x4A43: // sprmCHps (half-points)
                    s.setFontSize(u16(grpprl, p) / 2.0);
                    break;
                case 0x4A4F: // sprmCRgFtc0 (font index)
                case 0x4A51: { // sprmCRgFtc2
                    int idx = u16(grpprl, p);
                    if (fontNames != null && idx >= 0 && idx < fontNames.length
                            && fontNames[idx] != null) {
                        s.setFontFamily(fontNames[idx]);
                    }
                    break;
                }
                case 0x2A42: { // sprmCIco (classic 16-colour index)
                    int rgb = icoToRgb(grpprl[p] & 0xFF);
                    if (rgb >= 0) {
                        s.setColor(0xFF000000 | rgb);
                    }
                    break;
                }
                case 0x6870: { // sprmCCv — COLORREF 0x00BBGGRR, little-endian
                    // in the file, so the FIRST byte is RED (then green, blue).
                    int r = grpprl[p] & 0xFF;
                    int g = grpprl[p + 1] & 0xFF;
                    int b = grpprl[p + 2] & 0xFF;
                    s.setColor(0xFF000000 | (r << 16) | (g << 8) | b);
                    break;
                }
                default:
                    break;
            }
            p += size;
        }
    }

    /** Word toggle operand: 0/1 absolute, 0x80 = as current, 0x81 = inverted. */
    private static boolean toggle(byte operand, boolean current) {
        int v = operand & 0xFF;
        if (v == 0x80) {
            return current;
        }
        if (v == 0x81) {
            return !current;
        }
        return v != 0;
    }

    /** Paragraph-level facts pulled from a PAPX grpprl. */
    private static final class ParaProps {
        int jc = -1;
        double leftPt;
        double rightPt;
        double firstPt;
        double beforePt;
        double afterPt;
        boolean inTable;
        boolean rowEnd;
        /** Exact line height in points (LSPD dyaLine < 0), or 0. */
        double lineExactPt;
        /** Minimum line height in points (LSPD dyaLine > 0, fMult=0), or 0. */
        double lineAtLeastPt;
        /** Tab stops: {positionPt, align} with align 0=left 1=center 2=right. */
        final List<double[]> tabs = new ArrayList<>();
        /** Positioned text frame (converter idiom: one absolutely placed
         *  paragraph per printed line): x/y from the page corner, NaN = none. */
        double frameXPt = Double.NaN;
        double frameYPt = Double.NaN;
        double frameWPt = Double.NaN;
    }

    private static ParaProps applyPapx(byte[] grpprl) {
        ParaProps pp = new ParaProps();
        boolean sprmDebug = Boolean.getBoolean("doc.sprm.debug");
        if (sprmDebug) {
            System.err.println("[papx] ---- grpprl " + grpprl.length + "b");
        }
        int p = 0;
        while (p + 2 <= grpprl.length) {
            int sprm = u16(grpprl, p);
            p += 2;
            int size = operandSize(sprm, grpprl, p);
            if (size < 0 || p + size > grpprl.length) {
                break;
            }
            if (sprmDebug) {
                System.err.printf("[papx] sprm=%04X size=%d op16=%d%s%n", sprm, size,
                        size >= 2 ? s16(grpprl, p) : (size == 1 ? grpprl[p] & 0xFF : 0),
                        size == 4 ? (" op16b=" + s16(grpprl, p + 2)) : "");
            }
            switch (sprm) {
                case 0x2403: // sprmPJc80
                case 0x2461: // sprmPJc
                    pp.jc = grpprl[p] & 0xFF;
                    break;
                case 0x840F: // sprmPDxaLeft80
                case 0x845E: // sprmPDxaLeft
                    pp.leftPt = s16(grpprl, p) / 20.0;
                    break;
                case 0x840E: // sprmPDxaRight80
                case 0x845D: // sprmPDxaRight
                    pp.rightPt = s16(grpprl, p) / 20.0;
                    break;
                case 0x8411: // sprmPDxaLeft180
                case 0x8460: // sprmPDxaLeft1
                    pp.firstPt = s16(grpprl, p) / 20.0;
                    break;
                case 0xA413: // sprmPDyaBefore
                    pp.beforePt = u16(grpprl, p) / 20.0;
                    break;
                case 0xA414: // sprmPDyaAfter
                    pp.afterPt = u16(grpprl, p) / 20.0;
                    break;
                case 0x2416: // sprmPFInTable
                    pp.inTable = (grpprl[p] & 0xFF) != 0;
                    break;
                case 0x2417: // sprmPFTtp (row terminator)
                    pp.rowEnd = (grpprl[p] & 0xFF) != 0;
                    break;
                case 0xC60D: { // sprmPChgTabsPapx: [del cnt + pos][add cnt + pos + tbd]
                    int q = p + 1; // skip the cb byte
                    if (q < grpprl.length) {
                        int del = grpprl[q] & 0xFF;
                        q += 1 + del * 2;
                        if (q < grpprl.length) {
                            int add = grpprl[q] & 0xFF;
                            q += 1;
                            if (q + add * 3 <= grpprl.length) {
                                for (int t = 0; t < add; t++) {
                                    double posPt = u16(grpprl, q + t * 2) / 20.0;
                                    int jc = grpprl[q + add * 2 + t] & 0x07;
                                    double align = jc == 1 ? 1 : (jc == 2 || jc == 3) ? 2 : 0;
                                    pp.tabs.add(new double[]{posPt, align});
                                }
                            }
                        }
                    }
                    break;
                }
                case 0x8418: // sprmPDxaAbs — positioned-frame X (twips)
                    pp.frameXPt = s16(grpprl, p) / 20.0;
                    break;
                case 0x8419: // sprmPDyaAbs — positioned-frame Y (twips)
                    pp.frameYPt = s16(grpprl, p) / 20.0;
                    break;
                case 0x841A: // sprmPDxaWidth — positioned-frame width (twips)
                    pp.frameWPt = u16(grpprl, p) / 20.0;
                    break;
                case 0x6412: { // sprmPDyaLine (LSPD: dyaLine + fMultLinespace)
                    int dya = s16(grpprl, p);
                    int fMult = s16(grpprl, p + 2);
                    if (fMult == 0) {
                        // Twips: negative = EXACT |dya|, positive = at least.
                        if (dya < 0) {
                            pp.lineExactPt = -dya / 20.0;
                        } else if (dya > 0) {
                            pp.lineAtLeastPt = dya / 20.0;
                        }
                    }
                    break;
                }
                default:
                    break;
            }
            p += size;
        }
        return pp;
    }

    /** Operand byte count for a Word-97 sprm (spra = top 3 bits). */
    private static int operandSize(int sprm, byte[] grpprl, int p) {
        switch (sprm >>> 13) {
            case 0:
            case 1:
                return 1;
            case 2:
            case 4:
            case 5:
                return 2;
            case 3:
                return 4;
            case 7:
                return 3;
            default: // 6 — variable, first byte is the length
                if (p >= grpprl.length) {
                    return -1;
                }
                if (sprm == 0xD608) { // sprmTDefTable: 2-byte length
                    return 2 + u16(grpprl, p) - 1;
                }
                return 1 + (grpprl[p] & 0xFF);
        }
    }

    /** Classic Word 16-colour palette (ico), or -1 for auto/unknown. */
    private static int icoToRgb(int ico) {
        switch (ico) {
            case 1: return 0x000000;
            case 2: return 0x0000FF;
            case 3: return 0x00FFFF;
            case 4: return 0x00FF00;
            case 5: return 0xFF00FF;
            case 6: return 0xFF0000;
            case 7: return 0xFFFF00;
            case 8: return 0xFFFFFF;
            case 9: return 0x008080;
            case 10: return 0x008000;
            case 11: return 0x800080;
            case 12: return 0x800000;
            case 13: return 0x808000;
            case 14: return 0x808080;
            case 15: return 0xC0C0C0;
            case 16: return 0x000080;
            default: return -1;
        }
    }

    // ---- stylesheet (STSH) --------------------------------------------------

    /** Per-style raw CHPX grpprl + base-style index, from the STSH. */
    private byte[][] styleChpx = new byte[0][];
    private int[] styleBase = new int[0];
    /** Resolved per-style character defaults (lazy). */
    private TextStyle[] styleResolved = new TextStyle[0];

    /**
     * Parses the stylesheet: each paragraph/character style's CHPX upx and its
     * {@code istdBase} chain. Most documents put the character SIZE here (on
     * Normal / the style in use), not on every run — without it every run
     * falls back to the engine default and the page inflates.
     */
    private void readStyleSheet(int fc, int lcb) {
        if (lcb <= 4 || fc < 0 || fc + lcb > tableStream.length) {
            return;
        }
        int cbStshi = u16(tableStream, fc);
        int stshi = fc + 2;
        int cstd = u16(tableStream, stshi);
        int cbStdBase = u16(tableStream, stshi + 2);
        if (cstd <= 0 || cstd > 4096 || cbStdBase < 8 || cbStdBase > 32) {
            return;
        }
        styleChpx = new byte[cstd][];
        styleBase = new int[cstd];
        styleResolved = new TextStyle[cstd];
        java.util.Arrays.fill(styleBase, 0x0FFF);
        int pos = fc + 2 + cbStshi;
        int end = fc + lcb;
        for (int i = 0; i < cstd && pos + 2 <= end; i++) {
            int cbStd = u16(tableStream, pos);
            pos += 2;
            if (cbStd == 0) {
                continue;
            }
            int std = pos;
            pos += cbStd;
            if (pos > end) {
                break;
            }
            int flags2 = u16(tableStream, std + 2);
            int sgc = flags2 & 0x0F;           // 1=paragraph, 2=character
            styleBase[i] = (flags2 >> 4) & 0x0FFF;
            int nameOff = std + cbStdBase;
            int cch = u16(tableStream, nameOff);
            int gr = nameOff + 2 + cch * 2 + 2; // xstz: cch + chars + null
            if (((gr - std) & 1) != 0) {
                gr++;                           // grupx is 2-byte aligned
            }
            if (sgc == 1) {
                // paragraph style: PAPX upx first, then the CHPX upx
                int cbPapx = u16(tableStream, gr);
                gr += 2 + cbPapx;
                if (((gr - std) & 1) != 0) {
                    gr++;
                }
            }
            if (gr + 2 > std + cbStd) {
                continue;
            }
            int cbChpx = u16(tableStream, gr);
            if (cbChpx <= 0 || gr + 2 + cbChpx > std + cbStd) {
                continue;
            }
            styleChpx[i] = new byte[cbChpx];
            System.arraycopy(tableStream, gr + 2, styleChpx[i], 0, cbChpx);
        }
    }

    /** Diagnostic hook (tests): stylesheet summary. */
    static String stshDiagnostics(byte[] docBytes) throws IOException {
        DocSdmReader r = new DocSdmReader();
        CompoundFile cfb = new CompoundFile(docBytes);
        r.wordStream = cfb.getStream("WordDocument");
        int flags = u16(r.wordStream, 0x0A);
        r.tableStream = cfb.getStream((flags & 0x0200) != 0 ? "1Table" : "0Table");
        r.fontNames = r.readFontNames(i32(r.wordStream, 0x112), i32(r.wordStream, 0x116));
        r.readStyleSheet(i32(r.wordStream, 0xA2), i32(r.wordStream, 0xA6));
        StringBuilder sb = new StringBuilder("styles=" + r.styleChpx.length);
        int withChpx = 0;
        for (int i = 0; i < r.styleChpx.length; i++) {
            if (r.styleChpx[i] != null) {
                withChpx++;
            }
        }
        sb.append(" withChpx=").append(withChpx);
        for (int i = 0; i < Math.min(16, r.styleChpx.length); i++) {
            TextStyle st = r.styleChp(i);
            if (st != null && (st.getFontSize() > 0 || st.isBold() || st.getFontFamily() != null)) {
                sb.append(" | istd").append(i).append(": sz=").append(st.getFontSize())
                  .append(st.isBold() ? " B" : "").append(" f=").append(st.getFontFamily());
            }
        }
        return sb.toString();
    }

    /** The resolved character defaults of style {@code istd} (istdBase chain
     *  applied root-first), or null. */
    private TextStyle styleChp(int istd) {
        if (istd < 0 || istd >= styleChpx.length) {
            return null;
        }
        if (styleResolved[istd] != null) {
            return styleResolved[istd];
        }
        // Collect the chain root-first (guard against cycles).
        java.util.List<Integer> chain = new ArrayList<>();
        int cur = istd;
        int guard = 0;
        while (cur >= 0 && cur < styleChpx.length && guard++ < 12) {
            chain.add(0, cur);
            int base = styleBase[cur];
            if (base == 0x0FFF || base == cur) {
                break;
            }
            cur = base;
        }
        TextStyle s = new TextStyle();
        for (int id : chain) {
            if (styleChpx[id] != null) {
                applyChpxInto(styleChpx[id], s);
            }
        }
        styleResolved[istd] = s;
        return s;
    }

    // ---- fonts --------------------------------------------------------------

    private String[] readFontNames(int fc, int lcb) {
        if (lcb <= 6 || fc < 0 || fc + lcb > tableStream.length) {
            return new String[0];
        }
        List<String> names = new ArrayList<>();
        int p = fc;
        int count;
        if (u16(tableStream, p) == 0xFFFF) {
            count = u16(tableStream, p + 2);
            p += 6; // fExtend + cData + cbExtra
        } else {
            count = u16(tableStream, p);
            p += 4;
        }
        int end = fc + lcb;
        for (int i = 0; i < count && p < end; i++) {
            int cbFfnM1 = tableStream[p] & 0xFF;
            int entryEnd = Math.min(end, p + 1 + cbFfnM1);
            // FFN: name (UTF-16LE, null-terminated) starts 40 bytes into the entry.
            int nameOff = p + 40;
            StringBuilder sb = new StringBuilder();
            for (int q = nameOff; q + 2 <= entryEnd; q += 2) {
                char c = (char) u16(tableStream, q);
                if (c == 0) {
                    break;
                }
                sb.append(c);
            }
            names.add(sb.length() > 0 ? sb.toString() : null);
            p = entryEnd;
        }
        return names.toArray(new String[0]);
    }

    // ---- sections -----------------------------------------------------------

    private void readSection(int fcPlcfSed, int lcb) {
        Plc sed = readPlc(tableStream, fcPlcfSed, lcb, 12);
        if (sed.data.length == 0) {
            return;
        }
        // Break codes of EVERY section: a following section with bkc=0
        // (continuous) does not start a new page — its 0x0C boundary mark is
        // structural only. Word's default (no sprmSBkc in the SEPX) is 2.
        for (int i = 0; i < sed.data.length; i++) {
            int startCp = sed.pos[i];
            int bkc = 2;
            int cols = 1;
            double[] colWidths = new double[8];
            double[] colSpacing = new double[8];
            double colGap = -1;
            byte[] g = sepxGrpprl(u32(sed.data[i], 2));
            int q = 0;
            while (q + 2 <= g.length) {
                int sprm = u16(g, q);
                q += 2;
                int size = operandSize(sprm, g, q);
                if (size < 0 || q + size > g.length) {
                    break;
                }
                if (sprm == 0x3009) { // sprmSBkc
                    bkc = g[q] & 0xFF;
                } else if (sprm == 0x500B) { // sprmSCcolumns (count - 1)
                    cols = u16(g, q) + 1;
                } else if (sprm == 0xF203) { // sprmSDxaColWidth: [iCol][XAS]
                    int iCol = g[q] & 0xFF;
                    if (iCol < colWidths.length) {
                        colWidths[iCol] = u16(g, q + 1) / 20.0;
                    }
                } else if (sprm == 0xF204) { // sprmSDxaColSpacing: [iCol][XAS]
                    int iCol = g[q] & 0xFF;
                    if (iCol < colSpacing.length) {
                        colSpacing[iCol] = u16(g, q + 1) / 20.0;
                    }
                } else if (sprm == 0x9005) { // sprmSDxaColumns (uniform gap)
                    colGap = u16(g, q) / 20.0;
                }
                q += size;
            }
            sectionPageBreak.put(startCp, bkc >= 2);
            if (cols > 1) {
                int nCols = Math.min(cols, 8);
                sectionColumns.put(startCp, nCols);
                boolean allSet = true;
                for (int c = 0; c < nCols; c++) {
                    allSet &= colWidths[c] > 0;
                }
                if (allSet) {
                    double[] ws = new double[nCols];
                    System.arraycopy(colWidths, 0, ws, 0, nCols);
                    sectionColWidths.put(startCp, ws);
                }
                double gapFromSpacing = 0;
                for (int c = 0; c < nCols; c++) {
                    gapFromSpacing = Math.max(gapFromSpacing, colSpacing[c]);
                }
                if (gapFromSpacing > 0) {
                    sectionColGap.put(startCp, gapFromSpacing);
                } else if (colGap >= 0) {
                    sectionColGap.put(startCp, colGap);
                }
            }
            int endCp = i + 1 < sed.pos.length ? sed.pos[i + 1] : Integer.MAX_VALUE;
            sectionEnds.put(startCp, endCp);
        }

        byte[] grpprl = sepxGrpprl(u32(sed.data[0], 2));
        double pageW = 0;
        double pageH = 0;
        double left = -1;
        double right = -1;
        double top = -1;
        double bottom = -1;
        int p = 0;
        while (p + 2 <= grpprl.length) {
            int sprm = u16(grpprl, p);
            p += 2;
            int size = operandSize(sprm, grpprl, p);
            if (size < 0 || p + size > grpprl.length) {
                break;
            }
            switch (sprm) {
                case 0xB01F: pageW = u16(grpprl, p) / 20.0; break; // sprmSXaPage
                case 0xB020: pageH = u16(grpprl, p) / 20.0; break; // sprmSYaPage
                case 0xB021: left = u16(grpprl, p) / 20.0; break;  // sprmSDxaLeft
                case 0xB022: right = u16(grpprl, p) / 20.0; break; // sprmSDxaRight
                case 0x9023: top = s16(grpprl, p) / 20.0; break;   // sprmSDyaTop
                case 0x9024: bottom = s16(grpprl, p) / 20.0; break;// sprmSDyaBottom
                default: break;
            }
            p += size;
        }
        java.util.Map<String, String> meta = sdm.getMetadata().getCustom();
        if (pageW > 1 && pageH > 1) {
            meta.put("page-width", String.format(Locale.ROOT, "%.2f", pageW));
            meta.put("page-height", String.format(Locale.ROOT, "%.2f", pageH));
        }
        // -1 = sprm absent (fall back to the layout default); an EXPLICIT zero
        // is a real margin — full-width forms rely on it, and dropping it
        // narrows every line and reflows the page.
        if (left >= 0) {
            meta.put("margin-left", String.format(Locale.ROOT, "%.2f", left));
        }
        if (right >= 0) {
            meta.put("margin-right", String.format(Locale.ROOT, "%.2f", right));
        }
        if (top != -1) {
            meta.put("margin-top", String.format(Locale.ROOT, "%.2f", Math.abs(top)));
        }
        if (bottom != -1) {
            meta.put("margin-bottom", String.format(Locale.ROOT, "%.2f", Math.abs(bottom)));
        }
    }

    /** The SEPX grpprl at {@code fcSepx} in the WordDocument stream, or empty. */
    private byte[] sepxGrpprl(long fcSepx) {
        if (fcSepx == 0xFFFFFFFFL || fcSepx < 0 || fcSepx + 2 > wordStream.length) {
            return new byte[0];
        }
        int cb = u16(wordStream, (int) fcSepx);
        if (cb <= 0 || fcSepx + 2 + cb > wordStream.length) {
            return new byte[0];
        }
        byte[] grpprl = new byte[cb];
        System.arraycopy(wordStream, (int) fcSepx + 2, grpprl, 0, cb);
        return grpprl;
    }

    // ---- block building -----------------------------------------------------

    private void buildBlocks() {
        List<SdmBlock> root = sdm.getChildren();
        List<SdmBlock> out = root;
        Table openTable = null;
        TableRow openRow = null;
        boolean pageBreakPending = false;
        // Multi-column section state: output is redirected into per-column
        // containers; a 0x0E column-break char advances to the next column;
        // the assembled wrapper reuses the layout's side-by-side machinery.
        int[] sectionStarts = sectionEnds.keySet().stream().mapToInt(Integer::intValue)
                .sorted().toArray();
        org.aspose.pdf.sdm.Container colWrap = null;
        org.aspose.pdf.sdm.Container colCur = null;
        int colSection = -1;

        int cp = 0;
        while (cp < text.length) {
            // Section transitions drive the column wrapper.
            int secStart = sectionOf(sectionStarts, cp);
            if (colWrap != null && secStart != colSection) {
                root.add(colWrap);
                colWrap = null;
                colCur = null;
                out = root;
            }
            if (colWrap == null) {
                Integer nCols = sectionColumns.get(secStart);
                if (nCols != null && nCols > 1) {
                    colWrap = new org.aspose.pdf.sdm.Container(null);
                    colWrap.getAttributes().put("column-layout", Boolean.TRUE);
                    double[] ws = sectionColWidths.get(secStart);
                    if (ws != null) {
                        colWrap.getAttributes().put("column-widths-pt", ws);
                    }
                    Double cg = sectionColGap.get(secStart);
                    if (cg != null) {
                        colWrap.getAttributes().put("column-gap-pt", cg);
                    }
                    colCur = new org.aspose.pdf.sdm.Container(null);
                    colWrap.getChildren().add(colCur);
                    out = colCur.getChildren();
                    colSection = secStart;
                }
            }
            // The paragraph runs to the next paragraph/cell/section mark
            // inclusive. A section mark (0x0C) TERMINATES its paragraph like
            // Word does — it is not always paired with a 0x0D, and gluing it
            // to the following paragraph drags that paragraph into the wrong
            // section (e.g. into a two-column wrapper it does not belong to).
            int end = cp;
            while (end < text.length && text[end] != 0x0D && text[end] != 0x07
                    && text[end] != 0x0C) {
                end++;
            }
            boolean cellMark = end < text.length && text[end] == 0x07;

            int[] istd = {0};
            int markFc = fcOf(Math.min(end, text.length - 1));
            ParaProps pp = markFc >= 0 ? applyPapx(papxAt(markFc, istd)) : new ParaProps();

            SdmBlock block = buildParagraph(cp, end, istd[0]);
            // A paragraph terminated by the SECTION mark itself is structural:
            // when empty it is not a visible blank line — no spacer.
            if (end < text.length && text[end] == 0x0C && block instanceof Paragraph
                    && !hasVisibleText((Paragraph) block)) {
                block = null;
            }
            if (block != null && pageBreakPending) {
                block.getAttributes().put("page-break-before", Boolean.TRUE);
                pageBreakPending = false;
            }
            applyParaStyle(block, pp);
            // Include the terminating mark itself: a 0x0C now ENDS a paragraph,
            // so it sits at `end`, not inside the body.
            if (hasPageBreak(cp, Math.min(end + 1, text.length))) {
                pageBreakPending = true;
            }

            if (pp.inTable || cellMark) {
                if (openTable == null) {
                    openTable = new Table();
                    openTable.getAttributes().put("border", "ruled");
                    out.add(openTable);
                    openRow = new TableRow(TableRow.Kind.BODY);
                }
                if (pp.rowEnd) {
                    if (!openRow.getCells().isEmpty()) {
                        openTable.getRows().add(openRow);
                    }
                    openRow = new TableRow(TableRow.Kind.BODY);
                } else {
                    TableCell cell = new TableCell();
                    if (block != null) {
                        cell.getChildren().add(block);
                    }
                    openRow.getCells().add(cell);
                }
            } else {
                if (openTable != null) {
                    if (openRow != null && !openRow.getCells().isEmpty()) {
                        openTable.getRows().add(openRow);
                    }
                    openTable = null;
                    openRow = null;
                }
                if (block != null) {
                    out.add(block);
                }
            }
            // Figures from this paragraph's inline pictures follow the block —
            // into the current cell when inside a table, else the flow.
            if (!pendingFigures.isEmpty()) {
                List<SdmBlock> tgt = out;
                if ((pp.inTable || cellMark) && openRow != null
                        && !openRow.getCells().isEmpty()) {
                    tgt = openRow.getCells().get(openRow.getCells().size() - 1)
                            .getChildren();
                }
                tgt.addAll(pendingFigures);
                pendingFigures.clear();
            }
            emitAnchoredShapes(cp, end + 1, out);
            // A column break inside this paragraph: following content goes to
            // the NEXT column of the wrapper.
            if (colWrap != null && containsChar(cp, end, (char) 0x0E)) {
                colCur = new org.aspose.pdf.sdm.Container(null);
                colWrap.getChildren().add(colCur);
                out = colCur.getChildren();
                // Word: the break paragraph's MARK lands on the first line of
                // the NEW column — an otherwise-empty '\x0E' paragraph (block
                // suppressed above) still costs one line there. Re-emit it as
                // a spacer at the top of the next column.
                if (block == null) {
                    Paragraph colSpacer = new Paragraph();
                    TextStyle markStyle = markFc >= 0 ? runChp(chpxAt(markFc), istd[0]) : null;
                    if (markStyle != null && markStyle.getFontSize() > 0) {
                        colSpacer.getInline().add(new Run("", markStyle));
                    }
                    applyParaStyle(colSpacer, pp);
                    out.add(colSpacer);
                }
            }
            cp = end + 1;
        }
        if (openTable != null && openRow != null && !openRow.getCells().isEmpty()) {
            openTable.getRows().add(openRow);
        }
        if (colWrap != null) {
            root.add(colWrap);
        }
    }

    /** True when the paragraph carries any non-blank run text. */
    private static boolean hasVisibleText(Paragraph p) {
        for (org.aspose.pdf.sdm.SdmInline in : p.getInline()) {
            if (in instanceof Run && ((Run) in).getText() != null
                    && !((Run) in).getText().trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The start CP of the section containing a paragraph starting at {@code cp}
     *  (largest section start &le; cp). */
    private static int sectionOf(int[] starts, int cp) {
        int best = 0;
        for (int s : starts) {
            if (s <= cp) {
                best = s;
            } else {
                break;
            }
        }
        return best;
    }

    private boolean containsChar(int from, int to, char c) {
        for (int i = from; i < to && i < text.length; i++) {
            if (text[i] == c) {
                return true;
            }
        }
        return false;
    }

    // ---- inline pictures (PICF + OfficeArt blip, [MS-DOC] 2.9.192) ----------

    /**
     * Resolves the inline picture at the 0x01 char {@code cp}: its CHPX's
     * {@code sprmCPicLocation} (0x6A03) points at a PICF in the Data stream;
     * the PICF payload is an OfficeArtInlineSpContainer whose blip record
     * carries the raster (JPEG/PNG/DIB). Returns a Figure sized from
     * {@code dxaGoal×mx} (the author's display size), or null when the
     * picture cannot be decoded (EMF/WMF metafiles are V1-skipped).
     */
    private SdmBlock inlinePicture(int cp) {
        if (dataStream == null) {
            return null;
        }
        int fc = fcOf(cp);
        if (fc < 0) {
            return null;
        }
        int fcPic = -1;
        byte[] chpx = chpxAt(fc);
        int p = 0;
        while (p + 2 <= chpx.length) {
            int sprm = u16(chpx, p);
            p += 2;
            int size = operandSize(sprm, chpx, p);
            if (size < 0 || p + size > chpx.length) {
                break;
            }
            if (sprm == 0x6A03) { // sprmCPicLocation
                fcPic = i32(chpx, p);
            }
            p += size;
        }
        if (fcPic < 0 || fcPic + 0x44 > dataStream.length) {
            return null;
        }
        int lcb = i32(dataStream, fcPic);
        int cbHeader = u16(dataStream, fcPic + 4);
        if (lcb <= cbHeader || cbHeader < 0x2E || fcPic + lcb > dataStream.length) {
            return null;
        }
        // picmid: display size = dxaGoal (twips) scaled by mx/my (per-mille).
        double dxaGoal = s16(dataStream, fcPic + 28) / 20.0;
        double dyaGoal = s16(dataStream, fcPic + 30) / 20.0;
        double mx = u16(dataStream, fcPic + 32) / 1000.0;
        double my = u16(dataStream, fcPic + 34) / 1000.0;
        byte[][] blip = new byte[1][];
        String[] mime = new String[1];
        findBlip(dataStream, fcPic + cbHeader, fcPic + lcb, blip, mime);
        if (blip[0] == null) {
            LOG.fine("inline picture at fcPic=" + fcPic + " has no decodable blip");
            return null;
        }
        ResourceRef ref = sdm.getResources().put("docimg:" + (imageSeq++),
                new Resource(Resource.Kind.IMAGE, blip[0], mime[0]));
        Figure fig = new Figure(ref);
        double w = dxaGoal * (mx > 0 ? mx : 1);
        double h = dyaGoal * (my > 0 ? my : 1);
        if (w > 1 && h > 1) {
            fig.getAttributes().put("display-width", w);
            fig.getAttributes().put("display-height", h);
        }
        return fig;
    }

    /** Depth-first Escher walk over [{@code p}, {@code end}) looking for the
     *  first raster blip record; fills {@code blip[0]}/{@code mime[0]}. */
    private static void findBlip(byte[] d, int p, int end, byte[][] blip, String[] mime) {
        while (p + 8 <= end && blip[0] == null) {
            int verInst = u16(d, p);
            int type = u16(d, p + 2);
            long len = u32(d, p + 4);
            if (type < 0xF000 || len < 0 || p + 8 + len > end + 8L) {
                return; // out of sync — stop rather than misread raster bytes
            }
            int payload = p + 8;
            int payloadEnd = (int) Math.min(end, payload + len);
            if ((verInst & 0x0F) == 0x0F) {
                findBlip(d, payload, payloadEnd, blip, mime); // container
            } else if (type == 0xF007 && payloadEnd - payload > 36) {
                // FBSE: header (36 bytes + name) may EMBED the blip record.
                int cbName = d[payload + 33] & 0xFF;
                findBlip(d, payload + 36 + cbName, payloadEnd, blip, mime);
            } else if (type >= 0xF018 && type <= 0xF117) {
                int inst = verInst >>> 4;
                String m = null;
                boolean metafile = false;
                switch (type) {
                    case 0xF01D:
                    case 0xF02A: m = "image/jpeg"; break;
                    case 0xF01E: m = "image/png"; break;
                    case 0xF01F: m = "image/bmp"; break;
                    case 0xF029: m = "image/tiff"; break;
                    case 0xF01A:
                    case 0xF01B:
                    case 0xF01C: metafile = true; break; // EMF/WMF/PICT: V1 skip
                    default: break;
                }
                if (m != null) {
                    // rgbUid1 (16) [+ rgbUid2 (16) when the odd instance] + tag.
                    int off = payload + 16 + ((inst & 1) != 0 ? 16 : 0) + 1;
                    if (off < payloadEnd) {
                        byte[] bytes = new byte[payloadEnd - off];
                        System.arraycopy(d, off, bytes, 0, bytes.length);
                        blip[0] = "image/bmp".equals(m) ? dibToBmp(bytes) : bytes;
                        mime[0] = m;
                    }
                } else if (metafile) {
                    LOG.fine("skipping metafile blip type 0x" + Integer.toHexString(type));
                }
            }
            p = payload + (int) len;
        }
    }

    /** Wraps a raw DIB (BITMAPINFOHEADER + bits) into a .bmp for ImageIO. */
    private static byte[] dibToBmp(byte[] dib) {
        if (dib.length < 40) {
            return null;
        }
        int biSize = i32(dib, 0);
        int bitCount = u16(dib, 14);
        int clrUsed = i32(dib, 32);
        int colors = clrUsed != 0 ? clrUsed : (bitCount <= 8 ? 1 << bitCount : 0);
        int offBits = 14 + biSize + colors * 4;
        byte[] bmp = new byte[14 + dib.length];
        bmp[0] = 'B';
        bmp[1] = 'M';
        int fileSize = bmp.length;
        bmp[2] = (byte) fileSize;
        bmp[3] = (byte) (fileSize >> 8);
        bmp[4] = (byte) (fileSize >> 16);
        bmp[5] = (byte) (fileSize >> 24);
        bmp[10] = (byte) offBits;
        bmp[11] = (byte) (offBits >> 8);
        bmp[12] = (byte) (offBits >> 16);
        bmp[13] = (byte) (offBits >> 24);
        System.arraycopy(dib, 0, bmp, 14, dib.length);
        return bmp;
    }

    /**
     * Emits outline figures for shapes anchored (0x08) in the CP range
     * [from, to): drawn by the layout at their absolute page position without
     * consuming flow. Adjacent shapes with an identical rectangle (stacked
     * duplicates in form documents) collapse to one outline.
     */
    private void emitAnchoredShapes(int from, int to, List<SdmBlock> out) {
        if (spaCp.length == 0) {
            return;
        }
        // Collect this paragraph's shapes, then emit in Escher z-order
        // (SpContainer file order) — a shadow rectangle must paint BENEATH
        // the band it shadows, not over it.
        List<Integer> batch = new ArrayList<>();
        for (int i = 0; i < spaCp.length; i++) {
            if (spaCp[i] >= from && spaCp[i] < to) {
                batch.add(i);
            }
        }
        // Paint order: filled shapes first, darker before lighter (the
        // shadow idiom: a black offset rect UNDER its grey band), outlines
        // and write-in boxes last.
        batch.sort(java.util.Comparator.comparingLong(i -> {
            Integer f = shapeFill.get(spaSpid[i]);
            if (f == null) {
                return Long.MAX_VALUE;
            }
            int r = (f >> 16) & 0xFF;
            int g = (f >> 8) & 0xFF;
            int b = f & 0xFF;
            return r * 3L + g * 6L + b; // luminance-ish, dark first
        }));
        double[] prev = null;
        for (int i : batch) {
            double[] r = spaRect[i];
            if (r[2] < 0.5 && r[3] < 0.5) {
                continue; // zero-size marker shape
            }
            if (prev != null && prev[0] == r[0] && prev[1] == r[1]
                    && prev[2] == r[2] && prev[3] == r[3]) {
                continue; // stacked duplicate
            }
            prev = r;
            // A PICTURE shape (FOPT pib → BStore blip) becomes a real image
            // figure, not an outline. The pib may hang on a CHILD of a group
            // whose SPA we anchor — when the drawing has exactly one picture,
            // associate it with this (page-covering group) anchor.
            SdmBlock picture = shapePicture(spaSpid[i], r);
            if (picture != null) {
                out.add(picture);
                continue;
            }
            org.aspose.pdf.sdm.Figure fig = new org.aspose.pdf.sdm.Figure(null);
            fig.getAttributes().put("shape-outline", Boolean.TRUE);
            fig.getAttributes().put("pos-x-pt", r[0]);
            fig.getAttributes().put("pos-y-pt", r[1]);
            fig.getAttributes().put("display-width", r[2]);
            fig.getAttributes().put("display-height", r[3]);
            Integer fill = shapeFill.get(spaSpid[i]);
            if (fill != null) {
                if (System.getProperty("sdm.doc.debug4") != null) {
                    System.out.println(String.format("[FILLRECT] spid=%d rect=%.0fx%.0f@%.0f,%.0f argb=%08X",
                            spaSpid[i], r[2], r[3], r[0], r[1], fill));
                }
                fig.getAttributes().put("fill-argb", fill);
            }
            out.add(fig);
        }
    }

    /**
     * Resolves the anchored shape {@code spid} to an image Figure when it (or,
     * for a one-picture drawing, its grouped child) is a picture shape. The
     * blip bytes live in the delay stream (WordDocument, {@code foDelay}) or
     * embedded in the FBSE itself. Returns null for non-picture shapes.
     */
    private SdmBlock shapePicture(int spid, double[] rect) {
        Integer pib = shapePib.get(spid);
        if (pib == null && shapePib.size() == 1 && spaCp.length == 1) {
            pib = shapePib.values().iterator().next();
        }
        if (pib == null || pib < 1 || pib > blipStore.size()) {
            return null;
        }
        long[] rec = blipStore.get(pib - 1);
        byte[][] blip = new byte[1][];
        String[] mime = new String[1];
        long fo = rec[1];
        long size = rec[2];
        if (fo >= 0 && size > 0 && fo + 8 <= wordStream.length) {
            // The record's own rh length is authoritative; FBSE.size may be a
            // few bytes SHORT of it (uid accounting differs), so bound by the
            // stream end — findBlip stops at the first blip anyway.
            findBlip(wordStream, (int) fo, wordStream.length, blip, mime);
        }
        if (blip[0] == null && rec[4] > 36 + 8) {
            // FBSE with the blip embedded right after its 36-byte header + name.
            int body = (int) rec[3];
            int cbName = tableStream[body + 33] & 0xFF;
            findBlip(tableStream, body + 36 + cbName,
                    (int) Math.min(tableStream.length, rec[3] + rec[4]), blip, mime);
        }
        if (blip[0] == null) {
            LOG.fine("picture shape spid=" + spid + " blip undecodable (bt=" + rec[0] + ")");
            return null;
        }
        ResourceRef ref = sdm.getResources().put("docimg:" + (imageSeq++),
                new Resource(Resource.Kind.IMAGE, blip[0], mime[0]));
        Figure fig = new Figure(ref);
        // Word paints the shape at its SPA rectangle, absolutely positioned on
        // the anchor's page, BENEATH the text (a mostly-white full-page photo
        // must not white-out the paragraphs above it).
        fig.getAttributes().put("shape-image", Boolean.TRUE);
        fig.getAttributes().put("pos-x-pt", Math.max(0, rect[0]));
        fig.getAttributes().put("pos-y-pt", Math.max(0, rect[1]));
        fig.getAttributes().put("display-width", rect[2]);
        fig.getAttributes().put("display-height", rect[3]);
        return fig;
    }

    private boolean hasPageBreak(int from, int to) {
        for (int i = from; i < to; i++) {
            if (text[i] == 0x0C) {
                // A 0x0C that ENDS a section is a section mark: the page breaks
                // only when the FOLLOWING section says so (bkc >= 2). A 0x0C
                // outside any section boundary is a manual page break.
                Boolean sectionBreaks = sectionPageBreak.get(i + 1);
                if (sectionBreaks == null || sectionBreaks) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Builds a Paragraph/Heading for CP range [from, to); null when empty. */
    private SdmBlock buildParagraph(int from, int to, int istd) {
        List<org.aspose.pdf.sdm.SdmInline> inline = new ArrayList<>();
        int cp = from;
        boolean fieldInstruction = false;
        StringBuilder buf = new StringBuilder();
        TextStyle bufStyle = null;
        while (cp < to) {
            int fc = fcOf(cp);
            int runEndFc = fc >= 0 ? chpxRunEnd(fc) : fc + 1;
            TextStyle style = fc >= 0 ? runChp(chpxAt(fc), istd) : null;
            // Convert the CHPX run's FC extent back to a CP count, clamped to
            // the current piece (runs may not span pieces coherently).
            int pieceLimit = pieceEnd(cp);
            boolean ansiPiece = isAnsi(cp);
            int cpSpan = ansiPiece ? runEndFc - fc : (runEndFc - fc) / 2;
            int runTo = Math.min(to, Math.min(pieceLimit, cp + Math.max(1, cpSpan)));

            for (int i = cp; i < runTo; i++) {
                char c = text[i];
                if (c == 0x13) {
                    fieldInstruction = true;   // field begin: skip instruction
                    continue;
                }
                if (c == 0x14) {
                    fieldInstruction = false;  // separator: cached result follows
                    continue;
                }
                if (c == 0x15) {
                    fieldInstruction = false;  // field end
                    continue;
                }
                if (fieldInstruction) {
                    continue;
                }
                if (c == 0x0B) { // hard line break
                    flushRun(buf, bufStyle, inline);
                    inline.add(new LineBreak());
                    continue;
                }
                if (c == 0x0C || c == 0x0D || c == 0x07) {
                    continue; // structural marks handled by the caller
                }
                if (c == 0x1E) {
                    c = '-';   // non-breaking hyphen
                }
                if (c == 0x01) {
                    // Inline picture: the char's CHPX carries sprmCPicLocation,
                    // an offset into the Data stream (PICF + OfficeArt blip).
                    SdmBlock fig = inlinePicture(i);
                    if (fig != null) {
                        pendingFigures.add(fig);
                    }
                    continue;
                }
                if (c == 0x1F || c == 0x08 || c == 0x05 || c < 0x20 && c != '\t') {
                    continue; // soft hyphen, shape anchors, annotations
                }
                if (!sameStyle(bufStyle, style)) {
                    flushRun(buf, bufStyle, inline);
                    bufStyle = style;
                }
                // Keep the TAB: paragraphs with explicit tab stops are rendered
                // as tabbed segments by the layout; elsewhere it is sanitized.
                buf.append(c);
            }
            cp = runTo;
        }
        flushRun(buf, bufStyle, inline);
        if (inline.isEmpty()) {
            // A paragraph holding only floating-shape ANCHORS (0x08), a
            // COLUMN-BREAK mark (0x0E) or an inline PICTURE (0x01) occupies no
            // meaningful flow space of its own — shapes and figures are
            // emitted separately, the break is structural; a spacer here
            // inflates columns and pushes phantom pages (a picture paragraph
            // would double-space: the figure block already advances the flow).
            for (int i = from; i < to && i < text.length; i++) {
                if (text[i] == 0x08 || text[i] == 0x0E || text[i] == 0x01) {
                    return null;
                }
            }
            // An empty paragraph is a real blank line in Word — a vertical
            // spacer that forms rely on to push labels down to their boxes.
            // Its height is the PARAGRAPH MARK's character size (the CHPX of
            // the 0x0D char), not the document default: form spacers are
            // typically small (8-10pt), and a 12pt default stretches the page.
            Paragraph spacer = new Paragraph();
            int markFc = fcOf(Math.max(from, Math.min(to, text.length - 1)));
            TextStyle markStyle = markFc >= 0 ? runChp(chpxAt(markFc), istd) : null;
            if (markStyle != null && markStyle.getFontSize() > 0) {
                spacer.getInline().add(new Run("", markStyle));
            }
            return spacer;
        }
        if (istd >= 1 && istd <= 6) { // built-in "heading N" styles
            Heading h = new Heading(istd);
            h.getInline().addAll(inline);
            return h;
        }
        Paragraph p = new Paragraph();
        p.getInline().addAll(inline);
        return p;
    }

    private boolean isAnsi(int cp) {
        for (Piece p : pieces) {
            if (cp >= p.cpStart && cp < p.cpEnd) {
                return p.ansi;
            }
        }
        return true;
    }

    private static void flushRun(StringBuilder buf, TextStyle style,
                                 List<org.aspose.pdf.sdm.SdmInline> out) {
        if (buf.length() > 0) {
            out.add(new Run(buf.toString(), style));
            buf.setLength(0);
        }
    }

    private static boolean sameStyle(TextStyle a, TextStyle b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.isBold() == b.isBold() && a.isItalic() == b.isItalic()
                && a.isUnderline() == b.isUnderline()
                && a.isStrikethrough() == b.isStrikethrough()
                && a.getFontSize() == b.getFontSize()
                && a.getColor() == b.getColor()
                && java.util.Objects.equals(a.getFontFamily(), b.getFontFamily());
    }

    private static void applyParaStyle(SdmBlock block, ParaProps pp) {
        if (block == null) {
            return;
        }
        BlockStyle bs = null;
        if (pp.jc >= 0) {
            bs = new BlockStyle();
            switch (pp.jc) {
                case 1: bs.setAlign(BlockStyle.Align.CENTER); break;
                case 2: bs.setAlign(BlockStyle.Align.RIGHT); break;
                case 3:
                case 4: bs.setAlign(BlockStyle.Align.JUSTIFY); break;
                default: bs.setAlign(BlockStyle.Align.LEFT); break;
            }
        }
        if (pp.leftPt != 0 || pp.rightPt != 0 || pp.firstPt != 0
                || pp.beforePt > 0 || pp.afterPt > 0) {
            bs = bs != null ? bs : new BlockStyle();
            bs.setIndentStart(pp.leftPt);
            bs.setIndentEnd(pp.rightPt);
            bs.setIndentFirstLine(pp.firstPt);
            bs.setSpaceBefore(pp.beforePt);
            bs.setSpaceAfter(pp.afterPt);
        }
        // Exact/minimum line spacing (Word LSPD) — the same attributes the DOCX
        // reader emits; forms rely on exact leading to align labels with their
        // absolutely positioned boxes.
        if (pp.lineExactPt > 0) {
            if (System.getProperty("sdm.doc.debug") != null) {
                System.out.println("[DOCDBG] exact line " + pp.lineExactPt);
            }
            block.getAttributes().put("line-height-pt", pp.lineExactPt);
            block.getAttributes().put("line-rule", "exact");
        } else if (pp.lineAtLeastPt > 0) {
            block.getAttributes().put("line-height-pt", pp.lineAtLeastPt);
            block.getAttributes().put("line-rule", "atLeast");
        }
        if (!pp.tabs.isEmpty()) {
            block.getAttributes().put("tab-stops", new ArrayList<>(pp.tabs));
        }
        // Positioned frame (sprmPDxaAbs/PDyaAbs): the paragraph paints at an
        // absolute page position without consuming flow — converter documents
        // place every printed line this way.
        if (!Double.isNaN(pp.frameYPt) && pp.frameYPt > 0) {
            block.getAttributes().put("frame-y-pt", pp.frameYPt);
            if (!Double.isNaN(pp.frameXPt)) {
                block.getAttributes().put("frame-x-pt", Math.max(0, pp.frameXPt));
            }
            if (!Double.isNaN(pp.frameWPt) && pp.frameWPt > 8) {
                block.getAttributes().put("frame-w-pt", pp.frameWPt);
            }
        }
        if (bs != null) {
            block.setStyle(bs);
        }
    }

    // ---- little-endian helpers ---------------------------------------------

    private static int u16(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) {
            return 0;
        }
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int s16(byte[] b, int off) {
        return (short) u16(b, off);
    }

    private static int i32(byte[] b, int off) {
        return (int) u32(b, off);
    }

    private static long u32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) {
            return 0;
        }
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
