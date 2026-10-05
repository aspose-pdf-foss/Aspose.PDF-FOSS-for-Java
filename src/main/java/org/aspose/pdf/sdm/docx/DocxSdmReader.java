package org.aspose.pdf.sdm.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LineBreak;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * DOCX &rarr; SDM reader: parses a WordprocessingML package into the Semantic
 * Document Model — the DOCX mirror of {@code HtmlSdmReader}, and the missing
 * half of DOCX&rarr;PDF (the model is then laid out by {@code SdmPdfLayout},
 * the same engine the HTML path uses).
 *
 * <p>Zero-dependency: {@code java.util.zip} for the package, the JDK DOM for
 * the parts. Coverage (V1): paragraphs and Heading1-6 styles, run formatting
 * (bold/italic/strike/underline/colour/size/highlight/font), hyperlinks,
 * numbered/bulleted lists ({@code numbering.xml} formats), tables with
 * {@code gridSpan}/{@code vMerge}, inline and anchored pictures (media parts),
 * page size from {@code sectPr} (recorded as {@code page-width/height}
 * metadata for the layout).</p>
 */
public final class DocxSdmReader {

    private static final Logger LOG = Logger.getLogger(DocxSdmReader.class.getName());

    private static final double PT_PER_EMU = 1.0 / 12700.0;
    private static final double PT_PER_TWIP = 1.0 / 20.0;

    /** rId → relationship target (media path or external URL). */
    private final Map<String, String> relTargets = new HashMap<>();
    /** rId → TargetMode=External. */
    private final Map<String, Boolean> relExternal = new HashMap<>();
    /** media path ("word/media/image1.png") → bytes. */
    private final Map<String, byte[]> media = new HashMap<>();
    /** numId → per-ilvl ordered flags (from numbering.xml numFmt per w:lvl). */
    private final Map<String, List<Boolean>> numOrdered = new HashMap<>();
    /** styleId → heading level (styles.xml). */
    private final Map<String, Integer> headingStyles = new HashMap<>();
    /** styleId → run formatting declared by the style (styles.xml rPr + basedOn). */
    private final Map<String, StyleProps> styleProps = new HashMap<>();
    /** Document default run size in points (docDefaults / default paragraph style). */
    private double defaultRunSize;

    /** Run formatting carried by a paragraph/character style (null = unset). */
    private static final class StyleProps {
        Boolean bold;
        Boolean italic;
        double size;    // points, 0 = unset
        Integer color;  // ARGB, null = unset
        String font;
        String basedOn;
    }

    /** Style of the CURRENT paragraph's pStyle chain — runs without an explicit
     *  rPr property inherit from it (Word's style cascade). */
    private StyleProps paraInherit;

    // Complex-field state (w:fldChar begin/separate/end + w:instrText), tracked
    // while reading a HEADER/FOOTER part so PAGE / NUMPAGES fields become
    // substitutable tokens instead of the stale cached value ("1/2" on every
    // page). Body text keeps the cached value as before.
    private boolean furnitureMode;
    private int fldDepth;
    private final StringBuilder fldInstr = new StringBuilder();
    private boolean fldSuppressCached;
    private boolean fldEmitted;

    private SdmDocument sdm;
    private int imageSeq;
    /** Package parts (kept for header/footer resolution during read). */
    private Map<String, byte[]> parts;
    /** Running header/footer lines (text, sizePt, bold, centered) from sectPr refs. */
    private final List<Object[]> headerLines = new ArrayList<>();
    private final List<Object[]> footerLines = new ArrayList<>();
    /** pgMar w:header / w:footer distances (points), 0 = unset. */
    private double headerDistance;
    private double footerDistance;

    /**
     * Reads a {@code .docx} package into an SDM document.
     *
     * @param docx the package bytes; must not be null
     * @return the document model
     * @throws IOException when the package or a required part cannot be parsed
     */
    public SdmDocument read(byte[] docx) throws IOException {
        if (docx == null) {
            throw new IllegalArgumentException("docx bytes must not be null");
        }
        parts = unzip(docx);
        byte[] documentXml = parts.get("word/document.xml");
        if (documentXml == null) {
            throw new IOException("not a .docx package: word/document.xml missing");
        }
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            if (e.getKey().startsWith("word/media/")) {
                media.put(e.getKey(), e.getValue());
            }
        }
        parseRels(parts.get("word/_rels/document.xml.rels"));
        parseNumbering(parts.get("word/numbering.xml"));
        parseStyles(parts.get("word/styles.xml"));

        sdm = new SdmDocument();
        Element body = firstChildElement(parse(documentXml).getDocumentElement(), "body");
        if (body == null) {
            throw new IOException("word/document.xml has no w:body");
        }
        readBlocks(body, sdm.getChildren());
        readSectPr(body);
        return sdm;
    }

    /**
     * Stream convenience form of {@link #read(byte[])}.
     *
     * @param in the package stream (fully read; not closed)
     * @return the document model
     * @throws IOException when the package cannot be parsed
     */
    public SdmDocument read(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return read(bos.toByteArray());
    }

    // ------------------------------------------------------------- block level

    private void readBlocks(Element parent, List<SdmBlock> out) {
        // Consecutive numbered paragraphs fold into ListBlocks; w:ilvl nests
        // them (a level-N paragraph becomes an item of a list hanging off the
        // last level-(N-1) item). The stacks track the open list per level and
        // the numId that opened it — our own writer gives each nesting level
        // its own numId, Word reuses one numId across levels; both shapes land
        // in the same nested-ListBlock tree.
        List<ListBlock> listStack = new ArrayList<>();
        List<String> listNumIds = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element el = (Element) n;
            String name = local(el);
            boolean breakBefore = pendingPageBreak;
            if ("p".equals(name)) {
                String numId = numIdOf(el);
                if (numId != null) {
                    int lvl = ilvlOf(el);
                    if (listStack.isEmpty()) {
                        ListBlock root = new ListBlock(orderedAt(numId, 0), null);
                        out.add(root);
                        listStack.add(root);
                        listNumIds.add(numId);
                    }
                    while (listStack.size() - 1 > lvl) {
                        listStack.remove(listStack.size() - 1);
                        listNumIds.remove(listNumIds.size() - 1);
                    }
                    // A different numId at the SAME level starts a sibling list.
                    if (listStack.size() - 1 == lvl && !numId.equals(listNumIds.get(lvl))) {
                        if (lvl == 0) {
                            listStack.clear();
                            listNumIds.clear();
                            ListBlock root = new ListBlock(orderedAt(numId, 0), null);
                            out.add(root);
                            listStack.add(root);
                            listNumIds.add(numId);
                        } else {
                            listStack.remove(listStack.size() - 1);
                            listNumIds.remove(listNumIds.size() - 1);
                        }
                    }
                    while (listStack.size() - 1 < lvl) {
                        ListBlock outer = listStack.get(listStack.size() - 1);
                        if (outer.getItems().isEmpty()) {
                            outer.getItems().add(new ListItem());
                        }
                        ListBlock nested = new ListBlock(orderedAt(numId, listStack.size()), null);
                        outer.getItems().get(outer.getItems().size() - 1)
                                .getChildren().add(nested);
                        listStack.add(nested);
                        listNumIds.add(numId);
                    }
                    ListItem item = new ListItem();
                    SdmBlock para = readParagraph(el, true);
                    if (para != null) {
                        item.getChildren().add(para);
                    }
                    drainPendingFigures(item.getChildren());
                    listStack.get(listStack.size() - 1).getItems().add(item);
                    continue;
                }
                listStack.clear();
                listNumIds.clear();
                SdmBlock block = readParagraph(el, false);
                if (block != null) {
                    if (breakBefore) {
                        block.getAttributes().put("page-break-before", Boolean.TRUE);
                        pendingPageBreak = false;
                    }
                    out.add(block);
                }
                drainPendingFigures(out);
            } else if ("tbl".equals(name)) {
                listStack.clear();
                listNumIds.clear();
                Table tbl = readTable(el);
                if (breakBefore) {
                    tbl.getAttributes().put("page-break-before", Boolean.TRUE);
                    pendingPageBreak = false;
                }
                out.add(tbl);
            } else if ("sectPr".equals(name)) {
                // handled by readSectPr
                listStack.clear();
                listNumIds.clear();
            }
        }
    }

    /** One w:p → Heading/Paragraph/ThematicBreak/Figure (drawing-only), or null when empty. */
    private SdmBlock readParagraph(Element p, boolean inList) {
        Element pPr = firstChildElement(p, "pPr");
        String pStyle = pPr != null ? valAttr(firstChildElement(pPr, "pStyle")) : null;

        // Our own writer's thematic break: an empty paragraph with a bottom pBdr.
        if (pPr != null && firstChildElement(pPr, "pBdr") != null && textOf(p).trim().isEmpty()) {
            return new ThematicBreak();
        }

        List<SdmInline> inline = new ArrayList<>();
        List<SdmBlock> drawings = new ArrayList<>();
        // Word's style cascade: runs inherit the pStyle chain's rPr (bold
        // labels, coloured headings) unless the run declares its own value.
        StyleProps savedInherit = paraInherit;
        paraInherit = pStyle != null ? resolveStyle(pStyle) : null;
        try {
            readInlines(p, inline, drawings);
        } finally {
            paraInherit = savedInherit;
        }

        // A paragraph whose only inline content is line breaks (w:cr / w:br —
        // converter page-break paragraphs look like this) has no text: treating
        // the breaks as content would render phantom empty lines at the default
        // size where Word shows a 1pt paragraph mark.
        boolean onlyBreaks = !inline.isEmpty();
        for (SdmInline in : inline) {
            if (in.getType() != org.aspose.pdf.sdm.SdmNodeType.LINE_BREAK) {
                onlyBreaks = false;
                break;
            }
        }
        if (onlyBreaks) {
            inline.clear();
        }
        if (inline.isEmpty() && drawings.size() == 1) {
            SdmBlock only = drawings.get(0);
            // A page-anchored background that opens a new page (fixed-layout
            // export: each page = a backdrop drawing + a group of framePr labels)
            // carries the page break on its own paragraph. The figure never marks
            // the page as drawn, so the break must ride along or every page's
            // frames collapse onto the first.
            if (pPr != null && toggleOn(firstChildElement(pPr, "pageBreakBefore"))) {
                // A full-page backdrop is out-of-flow: it never marks the page
                // "drawn", so a plain page-break-before (which SdmPdfLayout gates
                // on !pageIsEmpty) is swallowed and every page's underlay piles
                // onto the first — the multi-page fixed-layout DOCX collapses to
                // one page. Promote it to an UNCONDITIONAL force-new-page (the
                // same mechanism the HTML fixed-layout round-trip uses), carrying
                // the page size so heterogeneous pages keep their dimensions.
                boolean pageBackdrop =
                        Boolean.TRUE.equals(only.getAttributes().get("background"))
                        || Boolean.TRUE.equals(only.getAttributes().get("pos-page-anchored"));
                if (pageBackdrop) {
                    only.getAttributes().put("force-new-page", Boolean.TRUE);
                    Object dw = only.getAttributes().get("display-width");
                    Object dh = only.getAttributes().get("display-height");
                    if (dw instanceof Number && dh instanceof Number) {
                        only.getAttributes().put("page-w-pt", ((Number) dw).doubleValue());
                        only.getAttributes().put("page-h-pt", ((Number) dh).doubleValue());
                    }
                } else {
                    only.getAttributes().put("page-break-before", Boolean.TRUE);
                }
            }
            return only; // picture paragraph → block figure
        }
        if (inline.isEmpty() && drawings.isEmpty() && !inList) {
            // An empty paragraph is a real blank line in Word: it advances one
            // line height (plus any explicit w:spacing). Paragraphs that were
            // ONLY line breaks (cleared above) stay dropped — they are
            // converter page-break carriers, not content.
            if (onlyBreaks) {
                return null;
            }
            Paragraph spacer = new Paragraph();
            applyParagraphStyle(pPr, spacer);
            return spacer;
        }

        Integer level = pStyle != null ? headingStyles.get(pStyle) : null;
        SdmBlock block;
        if (level != null) {
            Heading h = new Heading(level);
            h.getInline().addAll(inline);
            block = h;
        } else {
            Paragraph para = new Paragraph();
            para.getInline().addAll(inline);
            block = para;
        }
        applyParagraphStyle(pPr, block);
        if (pPr != null && toggleOn(firstChildElement(pPr, "pageBreakBefore"))) {
            block.getAttributes().put("page-break-before", Boolean.TRUE);
        }
        // Absolutely-positioned frame (w:framePr with a page anchor): the
        // paragraph paints at its (x, y) on the current page and does NOT consume
        // flow — the same contract as the legacy .doc positioned-frame path that
        // SdmPdfLayout already honors via frame-*-pt. Our PDF->DOCX fixed-layout
        // export places every form label this way over a page-anchored backdrop;
        // without honoring it the labels reflow as prose and explode the page
        // count (Word positions them; our reader used to stack them). frame-y-pt
        // is the trigger the layout keys on; x/w are optional.
        if (pPr != null) {
            Element framePr = firstChildElement(pPr, "framePr");
            if (framePr != null) {
                String fy = attrNS(framePr, "y");
                if (fy != null) {
                    block.getAttributes().put("frame-y-pt", parseLong(fy) * PT_PER_TWIP);
                    String fx = attrNS(framePr, "x");
                    if (fx != null) {
                        block.getAttributes().put("frame-x-pt", parseLong(fx) * PT_PER_TWIP);
                    }
                    String fw = attrNS(framePr, "w");
                    if (fw != null) {
                        block.getAttributes().put("frame-w-pt", parseLong(fw) * PT_PER_TWIP);
                    }
                }
            }
        }
        // A drawing sharing a text paragraph degrades to a following figure.
        if (!drawings.isEmpty()) {
            // caller adds the paragraph; figures ride along via a container-free
            // convention: we return the paragraph and queue figures after it.
            pendingFigures.addAll(drawings);
        }
        return block;
    }

    /** Figures produced by drawings living inside a TEXT paragraph. */
    private final List<SdmBlock> pendingFigures = new ArrayList<>();
    /** Set by an explicit {@code w:br type="page"}; the NEXT block starts a page. */
    private boolean pendingPageBreak;

    private void applyParagraphStyle(Element pPr, SdmBlock block) {
        if (pPr == null) {
            return;
        }
        Element jc = firstChildElement(pPr, "jc");
        String v = valAttr(jc);
        BlockStyle style = null;
        if (v != null) {
            style = new BlockStyle();
            switch (v) {
                case "center":
                    style.setAlign(BlockStyle.Align.CENTER);
                    break;
                case "right":
                case "end":
                    style.setAlign(BlockStyle.Align.RIGHT);
                    break;
                case "both":
                case "distribute":
                    style.setAlign(BlockStyle.Align.JUSTIFY);
                    break;
                default:
                    style.setAlign(BlockStyle.Align.LEFT);
                    break;
            }
        }
        // w:spacing drives the vertical rhythm. PDF→Word converter output leans
        // on it completely: every source text line is a paragraph with an EXACT
        // line height plus a "before" gap — ignoring it stacks lines onto each
        // other. exact/atLeast go to attributes (points, independent of the font
        // size); an auto w:line is a multiplier in 240ths.
        Element spacing = firstChildElement(pPr, "spacing");
        if (spacing != null) {
            String beforeAttr = attrNS(spacing, "before");
            String afterAttr = attrNS(spacing, "after");
            double before = parseLong(beforeAttr) * PT_PER_TWIP;
            double after = parseLong(afterAttr) * PT_PER_TWIP;
            if (before > 0) {
                style = style != null ? style : new BlockStyle();
                style.setSpaceBefore(before);
            }
            if (after > 0) {
                style = style != null ? style : new BlockStyle();
                style.setSpaceAfter(after);
            }
            String rule = attrNS(spacing, "lineRule");
            String lineAttr = attrNS(spacing, "line");
            long line = parseLong(lineAttr);
            if ("exact".equals(rule) && lineAttr != null) {
                // line=0 is a legitimate zero-height spacer (carries an
                // absolutely positioned shape without consuming flow space).
                block.getAttributes().put("line-height-pt", line * PT_PER_TWIP);
                block.getAttributes().put("line-rule", "exact");
            } else if ("atLeast".equals(rule) && line > 0) {
                block.getAttributes().put("line-height-pt", line * PT_PER_TWIP);
                block.getAttributes().put("line-rule", "atLeast");
            } else if (line > 0) {
                style = style != null ? style : new BlockStyle();
                style.setLineHeight(line / 240.0);
            }
        }
        // w:ind positions the paragraph horizontally (converter output places
        // each line's x this way).
        Element ind = firstChildElement(pPr, "ind");
        if (ind != null) {
            double left = parseLong(attrNS(ind, "left")) * PT_PER_TWIP;
            double right = parseLong(attrNS(ind, "right")) * PT_PER_TWIP;
            double first = parseLong(attrNS(ind, "firstLine")) * PT_PER_TWIP;
            double hanging = parseLong(attrNS(ind, "hanging")) * PT_PER_TWIP;
            if (left != 0 || right != 0 || first > 0 || hanging > 0) {
                style = style != null ? style : new BlockStyle();
                style.setIndentStart(left);
                style.setIndentEnd(right);
                if (hanging > 0) {
                    style.setIndentFirstLine(-hanging);
                } else if (first > 0) {
                    style.setIndentFirstLine(first);
                }
            }
        }
        if (style != null) {
            block.setStyle(style);
        }
    }

    // ------------------------------------------------------------ inline level

    private void readInlines(Element parent, List<SdmInline> out, List<SdmBlock> drawings) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element el = (Element) n;
            switch (local(el)) {
                case "r":
                    readRun(el, out, drawings);
                    break;
                case "hyperlink": {
                    String rid = attrNS(el, "id");
                    String href = rid != null && Boolean.TRUE.equals(relExternal.get(rid))
                            ? relTargets.get(rid) : null;
                    List<SdmInline> kids = new ArrayList<>();
                    readInlines(el, kids, drawings);
                    if (href != null) {
                        LinkInline link = new LinkInline(href);
                        link.getChildren().addAll(kids);
                        out.add(link);
                    } else {
                        out.addAll(kids); // internal anchor — keep the text
                    }
                    break;
                }
                case "pPr":
                    break; // paragraph properties, not content
                case "fldSimple": {
                    // A simple field. In a header/footer part a PAGE/NUMPAGES
                    // field becomes a substitutable token (the cached value is
                    // stale — "1" on every page); elsewhere keep the cached text.
                    String tok = furnitureMode ? fieldToken(attrNS(el, "instr")) : null;
                    if (tok != null) {
                        Element r0 = firstChildElement(el, "r");
                        out.add(new Run(tok,
                                runStyle(r0 != null ? firstChildElement(r0, "rPr") : null)));
                    } else {
                        readInlines(el, out, drawings);
                    }
                    break;
                }
                default:
                    // smartTag / ins / sdt wrappers — descend for their runs
                    readInlines(el, out, drawings);
                    break;
            }
        }
    }

    private void readRun(Element r, List<SdmInline> out, List<SdmBlock> drawings) {
        TextStyle style = runStyle(firstChildElement(r, "rPr"));
        StringBuilder text = new StringBuilder();
        for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element el = (Element) n;
            switch (local(el)) {
                case "t":
                    if (!fldSuppressCached) {
                        text.append(el.getTextContent());
                    }
                    break;
                case "instrText":
                    if (furnitureMode && fldDepth > 0) {
                        fldInstr.append(el.getTextContent());
                    }
                    break;
                case "fldChar": {
                    // Complex field (begin / instrText / separate / cached
                    // value / end). Only PAGE/NUMPAGES in furniture parts get
                    // special handling; body fields keep their cached value.
                    if (!furnitureMode) {
                        break;
                    }
                    String type = attrNS(el, "fldCharType");
                    if ("begin".equals(type)) {
                        fldDepth++;
                        if (fldDepth == 1) {
                            fldInstr.setLength(0);
                            fldEmitted = false;
                        }
                    } else if ("separate".equals(type) && fldDepth > 0) {
                        String tok = fieldToken(fldInstr.toString());
                        if (tok != null) {
                            flushRun(text, style, out);
                            out.add(new Run(tok, style));
                            fldEmitted = true;
                            fldSuppressCached = true; // drop the stale cached value
                        }
                    } else if ("end".equals(type)) {
                        if (fldDepth > 0) {
                            fldDepth--;
                        }
                        if (fldDepth == 0) {
                            if (!fldEmitted) {
                                String tok = fieldToken(fldInstr.toString());
                                if (tok != null) { // field with no separate/cached part
                                    flushRun(text, style, out);
                                    out.add(new Run(tok, style));
                                }
                            }
                            fldSuppressCached = false;
                            fldEmitted = false;
                        }
                    }
                    break;
                }
                case "tab":
                    // Keep the TAB: a header line "left\tright" positions its
                    // parts at tab stops — the layout aligns them (the body
                    // painter substitutes a gap; std-14 has no tab glyph).
                    text.append('\t');
                    break;
                case "br":
                    if ("page".equals(attrNS(el, "type"))) {
                        // Explicit page break: content after it starts a new page.
                        flushRun(text, style, out);
                        pendingPageBreak = true;
                        break;
                    }
                    // fall through — an ordinary line break
                case "cr":
                    flushRun(text, style, out);
                    out.add(new LineBreak());
                    break;
                case "drawing": {
                    flushRun(text, style, out);
                    Figure fig = readDrawing(el);
                    if (fig != null) {
                        drawings.add(fig);
                    }
                    break;
                }
                case "pict":
                case "object": {
                    // Legacy VML picture: <w:pict><v:shape style="width:..pt;
                    // height:..pt"><v:imagedata r:id=".."/></v:shape></w:pict>
                    flushRun(text, style, out);
                    Figure fig = readVmlPicture(el);
                    if (fig != null) {
                        drawings.add(fig);
                    }
                    break;
                }
                default:
                    break;
            }
        }
        flushRun(text, style, out);
    }

    private static void flushRun(StringBuilder text, TextStyle style, List<SdmInline> out) {
        if (text.length() > 0) {
            out.add(new Run(text.toString(), style));
            text.setLength(0);
        }
    }

    private TextStyle runStyle(Element rPr) {
        // Cascade: direct rPr > run's character style (rStyle) > the paragraph
        // style chain (paraInherit) > docDefaults size.
        StyleProps inherit = paraInherit;
        if (rPr != null) {
            String rs = valAttr(firstChildElement(rPr, "rStyle"));
            if (rs != null) {
                inherit = mergeStyles(resolveStyle(rs), inherit);
            }
        }
        boolean anyInherit = inherit != null && (inherit.bold != null || inherit.italic != null
                || inherit.size > 0 || inherit.color != null || inherit.font != null);
        if (rPr == null && !anyInherit) {
            if (defaultRunSize > 0) {
                TextStyle s = new TextStyle();
                s.setFontSize(defaultRunSize);
                return s;
            }
            return null;
        }
        TextStyle s = new TextStyle();
        Element b = rPr != null ? firstChildElement(rPr, "b") : null;
        if (b != null ? toggleOn(b) : inherit != null && Boolean.TRUE.equals(inherit.bold)) {
            s.setBold(true);
        }
        Element i = rPr != null ? firstChildElement(rPr, "i") : null;
        if (i != null ? toggleOn(i) : inherit != null && Boolean.TRUE.equals(inherit.italic)) {
            s.setItalic(true);
        }
        if (rPr != null && toggleOn(firstChildElement(rPr, "strike"))) {
            s.setStrikethrough(true);
        }
        Element u = rPr != null ? firstChildElement(rPr, "u") : null;
        if (u != null && !"none".equals(valAttr(u))) {
            s.setUnderline(true);
        }
        Element color = rPr != null ? firstChildElement(rPr, "color") : null;
        String cv = valAttr(color);
        if (cv != null && cv.matches("(?i)[0-9a-f]{6}")) {
            s.setColor(0xFF000000 | Integer.parseInt(cv, 16));
        } else if (inherit != null && inherit.color != null) {
            s.setColor(inherit.color);
        }
        Element sz = rPr != null ? firstChildElement(rPr, "sz") : null;
        String sv = valAttr(sz);
        if (sv != null) {
            try {
                s.setFontSize(Double.parseDouble(sv) / 2.0); // half-points → points
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        if (s.getFontSize() <= 0 && inherit != null && inherit.size > 0) {
            s.setFontSize(inherit.size); // paragraph/character style size
        }
        if (s.getFontSize() <= 0 && defaultRunSize > 0) {
            s.setFontSize(defaultRunSize); // document default (docDefaults/Normal)
        }
        String fill = rPr != null ? attrNS(firstChildElement(rPr, "shd"), "fill") : null;
        if (fill != null && fill.matches("(?i)[0-9a-f]{6}")) {
            s.setBackground(0xFF000000 | Integer.parseInt(fill, 16));
        }
        String fam = rPr != null ? attrNS(firstChildElement(rPr, "rFonts"), "ascii") : null;
        if (fam == null || fam.isEmpty()) {
            fam = inherit != null ? inherit.font : null;
        }
        if (fam != null && !fam.isEmpty()) {
            // PDF→Word converters carry the PDF font name verbatim —
            // "TLGCFD+Arial-BoldMT" — instead of setting w:b/w:i. Strip the
            // subset prefix, recover the style bits, keep the base family.
            String name = fam.replaceFirst("^[A-Z]{6}\\+", "");
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.contains("bold")) {
                s.setBold(true);
            }
            if (lower.contains("italic") || lower.contains("oblique")) {
                s.setItalic(true);
            }
            String base = name.split("[-,]")[0];
            if (base.endsWith("MT")) {
                base = base.substring(0, base.length() - 2);
            }
            if (base.endsWith("PS")) {
                base = base.substring(0, base.length() - 2);
            }
            s.setFontFamily(base.isEmpty() ? name : base);
        }
        return s;
    }

    // ---------------------------------------------------------------- pictures

    private Figure readDrawing(Element drawing) {
        // inline or anchor → a:graphic → pic:pic → pic:blipFill → a:blip r:embed
        Element blip = firstDescendant(drawing, "blip");
        if (blip == null) {
            return null;
        }
        String rid = attrNS(blip, "embed");
        String target = rid != null ? relTargets.get(rid) : null;
        if (target == null) {
            return null;
        }
        String path = target.startsWith("word/") ? target
                : "word/" + (target.startsWith("/") ? target.substring(1) : target);
        byte[] bytes = media.get(path);
        if (bytes == null) {
            return null;
        }
        String resId = "docximg:" + (imageSeq++);
        ResourceRef ref = sdm.getResources().put(resId,
                new Resource(Resource.Kind.IMAGE, bytes, mimeOf(path)));
        Figure fig = new Figure(ref);
        Element extent = firstDescendant(drawing, "extent");
        if (extent != null) {
            double w = parseLong(extent.getAttribute("cx")) * PT_PER_EMU;
            double h = parseLong(extent.getAttribute("cy")) * PT_PER_EMU;
            if (w > 0 && h > 0) {
                fig.getAttributes().put("display-width", w);
                fig.getAttributes().put("display-height", h);
            }
        }
        // A wp:anchor (vs wp:inline) positioned relativeFrom="page" is a
        // page-anchored backdrop — our fixed-layout PDF->DOCX export emits the
        // whole-page vector underlay this way (behindDoc, positionH/V
        // relativeFrom="page", posOffset in EMU from the page corner). Mark it as
        // a full-page background pinned to the page corner, so the layout paints
        // it at its true page position instead of flowing it inside the (often
        // large) text margins, which shrank the form's box artwork into a small
        // inset rectangle and dropped the field grid (corpus 39156).
        Element anchor = firstDescendant(drawing, "anchor");
        if (anchor != null) {
            Element posH = firstDescendant(anchor, "positionH");
            Element posV = firstDescendant(anchor, "positionV");
            boolean pageH = posH != null && "page".equals(posH.getAttribute("relativeFrom"));
            boolean pageV = posV != null && "page".equals(posV.getAttribute("relativeFrom"));
            boolean behind = "1".equals(anchor.getAttribute("behindDoc"))
                    || "true".equalsIgnoreCase(anchor.getAttribute("behindDoc"));
            if (behind || (pageH && pageV)) {
                fig.getAttributes().put("background", Boolean.TRUE);
                fig.getAttributes().put("background-opacity", 1.0);
                if (pageH && pageV) {
                    fig.getAttributes().put("pos-x-pt", anchorOffsetPt(posH));
                    fig.getAttributes().put("pos-y-pt", anchorOffsetPt(posV));
                    fig.getAttributes().put("pos-page-anchored", Boolean.TRUE);
                    fig.getAttributes().put("pos-from-page-corner", Boolean.TRUE);
                }
            }
        }
        return fig;
    }

    /** The {@code wp:posOffset} (EMU, measured from the page corner) of a
     *  DrawingML {@code wp:positionH}/{@code wp:positionV} element, in points. */
    private double anchorOffsetPt(Element pos) {
        Element off = firstDescendant(pos, "posOffset");
        if (off == null) {
            return 0;
        }
        try {
            return Long.parseLong(off.getTextContent().trim()) * PT_PER_EMU;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** VML picture ({@code v:imagedata r:id}); display size from the shape's CSS style. */
    private Figure readVmlPicture(Element pict) {
        Element imagedata = firstDescendant(pict, "imagedata");
        if (imagedata == null) {
            return null;
        }
        String rid = attrNS(imagedata, "id");
        String target = rid != null ? relTargets.get(rid) : null;
        if (target == null) {
            return null;
        }
        String path = target.startsWith("word/") ? target
                : "word/" + (target.startsWith("/") ? target.substring(1) : target);
        byte[] bytes = media.get(path);
        if (bytes == null) {
            return null;
        }
        String resId = "docximg:" + (imageSeq++);
        ResourceRef ref = sdm.getResources().put(resId,
                new Resource(Resource.Kind.IMAGE, bytes, mimeOf(path)));
        Figure fig = new Figure(ref);
        Element shape = firstDescendant(pict, "shape");
        String css = shape != null ? shape.getAttribute("style") : null;
        double w = cssPt(css, "width");
        double h = cssPt(css, "height");
        if (w > 0 && h > 0) {
            fig.getAttributes().put("display-width", w);
            fig.getAttributes().put("display-height", h);
        }
        // An absolutely positioned shape behind the text (z-index < 0) is the
        // page's BACKGROUND layer — a scanned page under an OCR text layer, a
        // letterhead. In flow it would push the real content down a page; the
        // layout paints it as a full-opacity page backdrop instead.
        if (css != null && css.contains("position:absolute")) {
            fig.getAttributes().put("background", Boolean.TRUE);
            fig.getAttributes().put("background-opacity", 1.0);
            // Page-anchored shapes carry their EXACT position (margin-left/top
            // measured from the page's top-left corner, may be negative). The
            // layout must paint the backdrop there — centring it shifts the
            // scanned line art (boxes, rules) off the text laid over it.
            if (css.contains("mso-position-horizontal-relative:page")
                    && css.contains("mso-position-vertical-relative:page")) {
                fig.getAttributes().put("pos-x-pt", cssPt(css, "margin-left"));
                fig.getAttributes().put("pos-y-pt", cssPt(css, "margin-top"));
                fig.getAttributes().put("pos-page-anchored", Boolean.TRUE);
            }
        }
        return fig;
    }

    /** A "name:123.45pt" value from a VML style string (negatives allowed), or 0. */
    private static double cssPt(String style, String name) {
        if (style == null) {
            return 0;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(name + "\\s*:\\s*(-?[0-9.]+)pt").matcher(style);
        return m.find() ? Double.parseDouble(m.group(1)) : 0;
    }

    // ------------------------------------------------------------------ tables

    private Table readTable(Element tbl) {
        Table table = new Table();
        // Grid borders: explicit w:tblBorders, or a border-drawing table style
        // (our own writer emits TableGrid). Recorded as the same "ruled"
        // attribute the PDF-side enrichers use, so SdmPdfLayout draws the grid.
        Element tblPr = firstChildElement(tbl, "tblPr");
        if (tblPr != null) {
            Element borders = firstChildElement(tblPr, "tblBorders");
            String styleId = valAttr(firstChildElement(tblPr, "tblStyle"));
            boolean ruled = false;
            if (borders != null) {
                Element top = firstChildElement(borders, "top");
                String bv = valAttr(top);
                ruled = bv != null && !"none".equals(bv) && !"nil".equals(bv);
                String bc = attrNS(top, "color");
                if (ruled && bc != null && bc.matches("(?i)[0-9a-f]{6}")) {
                    table.getAttributes().put("border-color", "#" + bc.toLowerCase(Locale.ROOT));
                }
            }
            if (!ruled && styleId != null && styleId.toLowerCase(Locale.ROOT).contains("grid")) {
                ruled = true;
            }
            if (ruled) {
                table.getAttributes().put("border", "ruled");
            }
            // Cell margins: converter grids set w:tblCellMar to 0/nil and pack
            // text to the exact cell width — the layout's default 3pt inset
            // would wrap their lines early and inflate row heights.
            Element cellMar = firstChildElement(tblPr, "tblCellMar");
            if (cellMar != null) {
                Element left = firstChildElement(cellMar, "left");
                String type = attrNS(left, "type");
                double pad = "nil".equals(type) ? 0
                        : parseLong(attrNS(left, "w")) * PT_PER_TWIP;
                table.getAttributes().put("cell-pad-pt", Math.max(0, pad));
            }
        }
        Element grid = firstChildElement(tbl, "tblGrid");
        if (grid != null) {
            for (Element col : childElements(grid, "gridCol")) {
                double w = parseLong(attrNS(col, "w")) * PT_PER_TWIP;
                table.getColumns().add(w > 0
                        ? new ColumnSpec(ColumnSpec.WidthType.POINTS, w, ColumnSpec.Align.LEFT)
                        : new ColumnSpec());
            }
        }
        // vMerge bookkeeping: cells with val=restart open a span; continue-cells
        // increment it and are dropped (SDM spans, like HTML rowspan).
        Map<Integer, TableCell> openVMerge = new HashMap<>();
        for (Element tr : childElements(tbl, "tr")) {
            TableRow row = new TableRow(TableRow.Kind.BODY);
            // Explicit row height (w:trHeight, atLeast/exact): the layout must
            // not size the row tighter than the source's grid.
            Element trPr = firstChildElement(tr, "trPr");
            Element trHeight = trPr != null ? firstChildElement(trPr, "trHeight") : null;
            if (trHeight != null) {
                double th = parseLong(valAttr(trHeight)) * PT_PER_TWIP;
                if (th > 0) {
                    row.getAttributes().put("min-height-pt", th);
                }
            }
            int colIndex = 0;
            for (Element tc : childElements(tr, "tc")) {
                Element tcPr = firstChildElement(tc, "tcPr");
                int span = 1;
                String vMerge = null;
                if (tcPr != null) {
                    String gs = valAttr(firstChildElement(tcPr, "gridSpan"));
                    if (gs != null) {
                        try {
                            span = Math.max(1, Integer.parseInt(gs));
                        } catch (NumberFormatException ignored) {
                            span = 1;
                        }
                    }
                    Element vm = firstChildElement(tcPr, "vMerge");
                    if (vm != null) {
                        vMerge = valAttr(vm);
                        if (vMerge == null) {
                            vMerge = "continue";
                        }
                    }
                }
                if ("continue".equals(vMerge)) {
                    TableCell open = openVMerge.get(colIndex);
                    if (open != null) {
                        open.setRowSpan(open.getRowSpan() + 1);
                    }
                    colIndex += span;
                    continue;
                }
                TableCell cell = new TableCell();
                if (span > 1) {
                    cell.setColSpan(span);
                }
                readBlocks(tc, cell.getChildren());
                stripTrailingEmptyParagraph(cell.getChildren());
                if ("restart".equals(vMerge)) {
                    openVMerge.put(colIndex, cell);
                } else {
                    openVMerge.remove(colIndex);
                }
                row.getCells().add(cell);
                colIndex += span;
            }
            table.getRows().add(row);
        }
        return table;
    }

    private static void stripTrailingEmptyParagraph(List<SdmBlock> blocks) {
        // OOXML mandates a trailing w:p in every cell; drop it when empty.
        if (!blocks.isEmpty()) {
            SdmBlock last = blocks.get(blocks.size() - 1);
            if (last instanceof Paragraph && ((Paragraph) last).getInline().isEmpty()) {
                blocks.remove(blocks.size() - 1);
            }
        }
    }

    // ------------------------------------------------------------------- parts

    private void readSectPr(Element body) {
        Element sectPr = firstChildElement(body, "sectPr");
        if (sectPr == null) {
            return;
        }
        Element pgSz = firstChildElement(sectPr, "pgSz");
        if (pgSz != null) {
            double w = parseLong(attrNS(pgSz, "w")) * PT_PER_TWIP;
            double h = parseLong(attrNS(pgSz, "h")) * PT_PER_TWIP;
            if (w > 1 && h > 1) {
                sdm.getMetadata().getCustom().put("page-width",
                        String.format(Locale.ROOT, "%.2f", w));
                sdm.getMetadata().getCustom().put("page-height",
                        String.format(Locale.ROOT, "%.2f", h));
            }
        }
        // The section margins are the document's real geometry (a PDF→DOCX→PDF
        // round trip recorded the source margins here) — the layout must reuse
        // them, not guess, or content that filled one source page spills over.
        Element pgMar = firstChildElement(sectPr, "pgMar");
        if (pgMar != null) {
            putMarginMeta("margin-left", attrNS(pgMar, "left"));
            putMarginMeta("margin-right", attrNS(pgMar, "right"));
            putMarginMeta("margin-top", attrNS(pgMar, "top"));
            putMarginMeta("margin-bottom", attrNS(pgMar, "bottom"));
            headerDistance = parseLong(attrNS(pgMar, "header")) * PT_PER_TWIP;
            footerDistance = parseLong(attrNS(pgMar, "footer")) * PT_PER_TWIP;
        }
        // Running header/footer parts: the section references them by rId; their
        // paragraphs become per-page furniture lines (text, size, bold, centred).
        readFurniture(firstChildElement(sectPr, "headerReference"), headerLines);
        readFurniture(firstChildElement(sectPr, "footerReference"), footerLines);
    }

    private void readFurniture(Element ref, List<Object[]> out) {
        if (ref == null) {
            return;
        }
        String rid = attrNS(ref, "id");
        String target = rid != null ? relTargets.get(rid) : null;
        if (target == null) {
            return;
        }
        String path = target.startsWith("word/") ? target
                : "word/" + (target.startsWith("/") ? target.substring(1) : target);
        byte[] xml = parts.get(path);
        if (xml == null) {
            return;
        }
        try {
            Element root = parse(xml).getDocumentElement();
            furnitureMode = true;
            fldDepth = 0;
            fldInstr.setLength(0);
            fldSuppressCached = false;
            fldEmitted = false;
            collectFurnitureLines(root, out);
        } catch (IOException e) {
            LOG.fine("header/footer part unreadable: " + path + ": " + e);
        } finally {
            furnitureMode = false;
            fldDepth = 0;
            fldSuppressCached = false;
        }
    }

    /** Maps a field instruction to its furniture token, or null when it is not
     *  a PAGE/NUMPAGES field (PAGEREF/SECTIONPAGES etc. stay cached text). */
    private static String fieldToken(String instr) {
        if (instr == null) {
            return null;
        }
        if (instr.matches("(?s).*\\bNUMPAGES\\b.*")) {
            return org.aspose.pdf.sdm.layout.PageSetup.NUMPAGES_TOKEN;
        }
        if (instr.matches("(?s).*\\bPAGE\\b.*")) {
            return org.aspose.pdf.sdm.layout.PageSetup.PAGE_TOKEN;
        }
        return null;
    }

    /** Flattens the part's paragraphs (recursing into tables) to furniture lines. */
    private void collectFurnitureLines(Element parent, List<Object[]> out) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) {
                continue;
            }
            Element el = (Element) n;
            String name = local(el);
            if ("p".equals(name)) {
                List<SdmInline> inline = new ArrayList<>();
                readInlines(el, inline, new ArrayList<>());
                StringBuilder sb = new StringBuilder();
                double size = 0;
                boolean bold = false;
                for (SdmInline in : inline) {
                    if (in instanceof Run) {
                        Run r = (Run) in;
                        sb.append(r.getText());
                        if (r.getStyle() != null) {
                            size = Math.max(size, r.getStyle().getFontSize());
                            bold |= r.getStyle().isBold();
                        }
                    }
                }
                String text = sb.toString().trim();
                if (!text.isEmpty()) {
                    Element pPr = firstChildElement(el, "pPr");
                    boolean centered = pPr != null
                            && "center".equals(valAttr(firstChildElement(pPr, "jc")));
                    out.add(new Object[]{text, size, bold, centered, tabStopsOf(pPr)});
                }
            } else {
                collectFurnitureLines(el, out); // tables/sdt wrappers
            }
        }
    }

    /** Explicit tab stops from {@code w:pPr/w:tabs}: {@code {positionPt, align}}
     *  with align 0=left, 1=center, 2=right (decimal counts as right; bar
     *  and cleared stops are skipped). Empty when the paragraph declares none. */
    private static List<double[]> tabStopsOf(Element pPr) {
        List<double[]> out = new ArrayList<>();
        Element tabs = pPr != null ? firstChildElement(pPr, "tabs") : null;
        if (tabs == null) {
            return out;
        }
        for (Element tab : childElements(tabs, "tab")) {
            String kind = valAttr(tab);
            if ("bar".equals(kind) || "clear".equals(kind)) {
                continue;
            }
            double pos = parseLong(attrNS(tab, "pos")) * PT_PER_TWIP;
            if (pos <= 0) {
                continue;
            }
            double align = "center".equals(kind) ? 1
                    : ("right".equals(kind) || "decimal".equals(kind)) ? 2 : 0;
            out.add(new double[]{pos, align});
        }
        return out;
    }

    /** @return running-header lines: {String text, double sizePt, Boolean bold, Boolean centered} */
    public List<Object[]> getHeaderLines() {
        return headerLines;
    }

    /** @return running-footer lines (same shape as {@link #getHeaderLines()}) */
    public List<Object[]> getFooterLines() {
        return footerLines;
    }

    /** @return pgMar header distance in points (0 = unset) */
    public double getHeaderDistance() {
        return headerDistance;
    }

    /** @return pgMar footer distance in points (0 = unset) */
    public double getFooterDistance() {
        return footerDistance;
    }

    private void putMarginMeta(String key, String twips) {
        if (twips == null || twips.isEmpty()) {
            return; // attribute absent — let the layout heuristic decide
        }
        double v = parseLong(twips) * PT_PER_TWIP;
        if (v >= 0) {
            // An EXPLICIT zero is a real margin (converter output runs the body
            // to the page edge); only a missing attribute falls back.
            sdm.getMetadata().getCustom().put(key, String.format(Locale.ROOT, "%.2f", v));
        }
    }

    private void parseRels(byte[] rels) throws IOException {
        if (rels == null) {
            return;
        }
        Element root = parse(rels).getDocumentElement();
        for (Element rel : childElements(root, "Relationship")) {
            String id = rel.getAttribute("Id");
            String target = rel.getAttribute("Target");
            if (!id.isEmpty() && !target.isEmpty()) {
                relTargets.put(id, target);
                relExternal.put(id, "External".equals(rel.getAttribute("TargetMode")));
            }
        }
    }

    private void parseNumbering(byte[] numbering) throws IOException {
        if (numbering == null) {
            return;
        }
        Element root = parse(numbering).getDocumentElement();
        // abstractNumId → per-ilvl ordered flags (numFmt != bullet), so a
        // multilevel list can mix "1. 2. 3." with nested "• • •".
        Map<String, List<Boolean>> abstractOrdered = new HashMap<>();
        for (Element an : childElements(root, "abstractNum")) {
            String id = attrNS(an, "abstractNumId");
            List<Boolean> levels = new ArrayList<>();
            for (Element lvl : childElements(an, "lvl")) {
                int ilvl = (int) parseLong(attrNS(lvl, "ilvl"));
                String fmt = valAttr(firstChildElement(lvl, "numFmt"));
                while (levels.size() <= ilvl) {
                    levels.add(Boolean.FALSE);
                }
                levels.set(ilvl, fmt != null && !"bullet".equals(fmt));
            }
            abstractOrdered.put(id, levels);
        }
        for (Element num : childElements(root, "num")) {
            String numId = attrNS(num, "numId");
            String abs = valAttr(firstChildElement(num, "abstractNumId"));
            if (numId != null && abs != null && abstractOrdered.containsKey(abs)) {
                numOrdered.put(numId, abstractOrdered.get(abs));
            }
        }
    }

    /** Whether numbering {@code numId} is ordered at nesting level {@code lvl}
     *  (falls back to the deepest declared level, then to bullet). */
    private boolean orderedAt(String numId, int lvl) {
        List<Boolean> levels = numOrdered.get(numId);
        if (levels == null || levels.isEmpty()) {
            return false;
        }
        return levels.get(Math.min(lvl, levels.size() - 1));
    }

    /** The paragraph's list level (w:pPr/w:numPr/w:ilvl val), clamped to 0..8. */
    private static int ilvlOf(Element p) {
        Element pPr = firstChildElement(p, "pPr");
        Element numPr = pPr != null ? firstChildElement(pPr, "numPr") : null;
        long v = numPr != null ? parseLong(valAttr(firstChildElement(numPr, "ilvl"))) : 0;
        return (int) Math.max(0, Math.min(8, v));
    }

    private void parseStyles(byte[] styles) throws IOException {
        if (styles == null) {
            return;
        }
        Element root = parse(styles).getDocumentElement();
        // Document default run size: docDefaults/rPrDefault, else the default
        // paragraph style's rPr. Runs without an explicit w:sz inherit it —
        // falling back to the engine's 12pt default instead renders a 9-11pt
        // document ~20% larger, so every wrap and position drifts.
        Element dd = firstChildElement(root, "docDefaults");
        Element rprDef = dd != null ? firstChildElement(dd, "rPrDefault") : null;
        Element ddRpr = rprDef != null ? firstChildElement(rprDef, "rPr") : null;
        String ddSz = ddRpr != null ? valAttr(firstChildElement(ddRpr, "sz")) : null;
        if (ddSz != null) {
            defaultRunSize = parseLong(ddSz) / 2.0;
        }
        for (Element st : childElements(root, "style")) {
            String def = attrNS(st, "default");
            if (defaultRunSize <= 0 && ("1".equals(def) || "on".equals(def) || "true".equals(def))
                    && "paragraph".equals(attrNS(st, "type"))) {
                Element rpr = firstChildElement(st, "rPr");
                String sz = rpr != null ? valAttr(firstChildElement(rpr, "sz")) : null;
                if (sz != null) {
                    defaultRunSize = parseLong(sz) / 2.0;
                }
            }
        }
        for (Element st : childElements(root, "style")) {
            String id = attrNS(st, "styleId");
            if (id == null) {
                continue;
            }
            styleProps.put(id, stylePropsOf(st));
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?i)^heading\\s*([1-6])$").matcher(id);
            if (m.matches()) {
                headingStyles.put(id, Integer.parseInt(m.group(1)));
                continue;
            }
            Element name = firstChildElement(st, "name");
            String nv = valAttr(name);
            if (nv != null) {
                java.util.regex.Matcher n = java.util.regex.Pattern
                        .compile("(?i)^heading\\s*([1-6])$").matcher(nv.trim());
                if (n.matches()) {
                    headingStyles.put(id, Integer.parseInt(n.group(1)));
                }
            }
        }
    }

    /** Extracts the run formatting a styles.xml {@code w:style} declares. */
    private static StyleProps stylePropsOf(Element st) {
        StyleProps sp = new StyleProps();
        sp.basedOn = valAttr(firstChildElement(st, "basedOn"));
        Element rPr = firstChildElement(st, "rPr");
        if (rPr == null) {
            return sp;
        }
        Element b = firstChildElement(rPr, "b");
        if (b != null) {
            sp.bold = toggleOn(b);
        }
        Element i = firstChildElement(rPr, "i");
        if (i != null) {
            sp.italic = toggleOn(i);
        }
        String sz = valAttr(firstChildElement(rPr, "sz"));
        if (sz != null) {
            sp.size = parseLong(sz) / 2.0;
        }
        String cv = valAttr(firstChildElement(rPr, "color"));
        if (cv != null && cv.matches("(?i)[0-9a-f]{6}")) {
            sp.color = 0xFF000000 | Integer.parseInt(cv, 16);
        }
        String fam = attrNS(firstChildElement(rPr, "rFonts"), "ascii");
        if (fam != null && !fam.isEmpty()) {
            sp.font = fam;
        }
        return sp;
    }

    /** Resolves a style id through its {@code basedOn} chain (nearest wins). */
    private StyleProps resolveStyle(String id) {
        StyleProps out = new StyleProps();
        int guard = 0;
        for (String cur = id; cur != null && guard++ < 8; ) {
            StyleProps sp = styleProps.get(cur);
            if (sp == null) {
                break;
            }
            if (out.bold == null) {
                out.bold = sp.bold;
            }
            if (out.italic == null) {
                out.italic = sp.italic;
            }
            if (out.size <= 0) {
                out.size = sp.size;
            }
            if (out.color == null) {
                out.color = sp.color;
            }
            if (out.font == null) {
                out.font = sp.font;
            }
            cur = sp.basedOn;
        }
        return out;
    }

    /** Overlays {@code hi} on {@code lo} (hi wins where set); either may be null. */
    private static StyleProps mergeStyles(StyleProps hi, StyleProps lo) {
        if (hi == null) {
            return lo;
        }
        if (lo == null) {
            return hi;
        }
        StyleProps out = new StyleProps();
        out.bold = hi.bold != null ? hi.bold : lo.bold;
        out.italic = hi.italic != null ? hi.italic : lo.italic;
        out.size = hi.size > 0 ? hi.size : lo.size;
        out.color = hi.color != null ? hi.color : lo.color;
        out.font = hi.font != null ? hi.font : lo.font;
        return out;
    }

    // ----------------------------------------------------------------- helpers

    /** Drains queued in-paragraph figures into the output after their paragraph. */
    void drainPendingFigures(List<SdmBlock> out) {
        out.addAll(pendingFigures);
        pendingFigures.clear();
    }

    private static Map<String, byte[]> unzip(byte[] docx) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                parts.put(e.getName(), bos.toByteArray());
            }
        }
        return parts;
    }

    private static org.w3c.dom.Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("malformed OOXML part: " + e.getMessage(), e);
        }
    }

    private static String local(Element el) {
        String n = el.getLocalName();
        return n != null ? n : el.getTagName();
    }

    private static Element firstChildElement(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(local((Element) n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(local((Element) n))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstDescendant(Element parent, String localName) {
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    /** The w:val attribute (namespace-tolerant), or null. */
    private static String valAttr(Element el) {
        return attrNS(el, "val");
    }

    /** A namespaced attribute by local name (any prefix), or null. */
    private static String attrNS(Element el, String localName) {
        if (el == null) {
            return null;
        }
        org.w3c.dom.NamedNodeMap atts = el.getAttributes();
        for (int i = 0; i < atts.getLength(); i++) {
            Node a = atts.item(i);
            String ln = a.getLocalName() != null ? a.getLocalName() : a.getNodeName();
            if (localName.equals(ln)) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    /** Toggle property (w:b / w:i): present with no val, or val != false/0. */
    private static boolean toggleOn(Element el) {
        if (el == null) {
            return false;
        }
        String v = valAttr(el);
        return v == null || !("false".equals(v) || "0".equals(v) || "none".equals(v));
    }

    private static long parseLong(String v) {
        if (v == null || v.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String textOf(Element el) {
        return el.getTextContent() == null ? "" : el.getTextContent();
    }

    /** The paragraph's numbering id (w:pPr/w:numPr/w:numId val), or null. */
    private static String numIdOf(Element p) {
        Element pPr = firstChildElement(p, "pPr");
        Element numPr = pPr != null ? firstChildElement(pPr, "numPr") : null;
        return numPr != null ? valAttr(firstChildElement(numPr, "numId")) : null;
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".gif")) {
            return "image/gif";
        }
        if (p.endsWith(".bmp")) {
            return "image/bmp";
        }
        return "image/png";
    }
}
