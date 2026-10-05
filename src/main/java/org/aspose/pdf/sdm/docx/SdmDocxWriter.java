package org.aspose.pdf.sdm.docx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.FootnoteRef;
import org.aspose.pdf.sdm.FormField;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.InlineImage;
import org.aspose.pdf.sdm.LineBreak;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.enrich.LinkAnnotationEnricher;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.SdmMetadata;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.TocBlock;
import org.aspose.pdf.sdm.TocEntry;

/**
 * Semantic Document Model &rarr; Office Open XML ({@code .docx}) writer.
 *
 * <p>The companion of {@link org.aspose.pdf.sdm.html.SdmHtmlWriter}: where that
 * class projects the IR onto semantic HTML5, this one projects the same model
 * onto a WordprocessingML package (ISO/IEC 29500). Nothing here depends on a
 * third-party OOXML library — the {@code .docx} ZIP and every XML part is emitted
 * by hand from {@code java.util.zip} (project constraint: zero dependencies).</p>
 *
 * <p><b>Package layout produced:</b></p>
 * <pre>
 *   [Content_Types].xml
 *   _rels/.rels
 *   docProps/core.xml           (title / author from {@link SdmMetadata})
 *   word/document.xml           (the body — flow of paragraphs and tables)
 *   word/styles.xml             (Normal, Heading1..6, Title, Quote, Hyperlink, ...)
 *   word/numbering.xml          (one bullet + one decimal abstract list, a num per list)
 *   word/_rels/document.xml.rels
 *   word/media/imageN.(png|jpeg)
 * </pre>
 *
 * <p><b>Mapping.</b> Heading&rarr;{@code pStyle="HeadingN"}, Paragraph&rarr;{@code w:p}
 * with alignment/indent/spacing/shading from {@link BlockStyle}, Run&rarr;{@code w:r}
 * with bold/italic/underline/strike/size/colour/vert-align from {@link TextStyle},
 * ListBlock&rarr;numbered/bulleted paragraphs (each list restarts via its own
 * {@code numId}), Table&rarr;{@code w:tbl} with {@code gridSpan}/{@code vMerge}
 * reconstructing colspan/rowspan, Figure/InlineImage&rarr;an inline {@code w:drawing}
 * picture sized to the PDF footprint, LinkInline&rarr;{@code w:hyperlink} with an
 * external relationship, ThematicBreak&rarr;a bottom-bordered paragraph. Content the
 * model could not classify (Opaque, footnotes, TOC, form fields) is degraded to
 * plain paragraphs rather than dropped.</p>
 *
 * <p>The writer never mutates the model and is deterministic for a given document.</p>
 */
public final class SdmDocxWriter {

    private static final Logger LOG = Logger.getLogger(SdmDocxWriter.class.getName());

    // Unit conversions. Word measures most lengths in twips (1/20 pt) and drawing
    // extents in EMU (914400 per inch = 12700 per point).
    private static final double TWIPS_PER_PT = 20.0;
    private static final long EMU_PER_PT = 12700L;
    private static final long EMU_PER_PX_96DPI = 9525L; // 914400 / 96

    private static final String NS_W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing";
    private static final String NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture";
    private static final String REL_BASE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";

    /** A relationship in {@code word/_rels/document.xml.rels}. */
    private static final class Rel {
        final String id;
        final String type;
        final String target;
        final String mode; // "External" or null
        Rel(String id, String type, String target, String mode) {
            this.id = id;
            this.type = type;
            this.target = target;
            this.mode = mode;
        }
    }

    /** A binary part under {@code word/media/}. */
    private static final class Media {
        final String name;   // e.g. "image1.png"
        final byte[] bytes;
        Media(String name, byte[] bytes) {
            this.name = name;
            this.bytes = bytes;
        }
    }

    private SdmDocument doc;
    private final StringBuilder body = new StringBuilder(8192);
    private final List<Rel> rels = new ArrayList<>();
    private final List<Media> media = new ArrayList<>();
    private final Set<String> mediaExtensions = new LinkedHashSet<>();
    /** Per-list ordered flag; the list's {@code numId} is its 1-based index. */
    private final List<Boolean> listDefs = new ArrayList<>();
    private int relCounter;
    private int docPrId;
    private boolean inHyperlink;

    /**
     * True while emitting a positioned (abs-x/framePr) paragraph on a
     * fixed-layout poster page. Such text sits OVER the page-underlay raster, so
     * near-white glyphs are visible against the source artwork and must keep
     * their colour — the {@link #emitRunText} white-text rescue is suppressed.
     */
    private boolean overUnderlay;

    /**
     * Count of fixed-layout page underlays emitted so far. Every poster page is
     * one physical page; consecutive posters carry no flow content between them,
     * so each underlay after the first forces a page break to keep pagination.
     */
    private int postersEmitted;
    /** Bookmark {@code w:id} (integer, per OOXML schema) assigned per anchor name. */
    private final java.util.Map<String, Integer> bookmarkIds = new java.util.HashMap<>();
    private int bookmarkCounter;

    /**
     * Serializes the SDM document to {@code .docx} bytes.
     *
     * @param document the SDM document; must not be null
     * @return the {@code .docx} package as a byte array
     * @throws IOException if the ZIP package cannot be assembled
     */
    public byte[] write(SdmDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
        write(document, buffer);
        return buffer.toByteArray();
    }

    /**
     * Serializes the SDM document to a {@code .docx} package on the given stream.
     * The stream is not closed.
     *
     * @param document the SDM document; must not be null
     * @param out      the destination stream
     * @throws IOException if writing fails
     */
    public void write(SdmDocument document, OutputStream out) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        this.doc = document;
        this.body.setLength(0);
        this.rels.clear();
        this.media.clear();
        this.mediaExtensions.clear();
        this.listDefs.clear();
        this.relCounter = 0;
        this.docPrId = 0;
        this.inHyperlink = false;

        // Fixed relationships come first so their ids are stable (rId1/rId2).
        String stylesRel = addRel(REL_BASE + "styles", "styles.xml", null);
        String numberingRel = addRel(REL_BASE + "numbering", "numbering.xml", null);

        for (SdmBlock block : document.getChildren()) {
            emitBlock(block);
        }

        String documentXml = buildDocumentXml();

        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(6);
        putEntry(zip, "[Content_Types].xml", buildContentTypes());
        putEntry(zip, "_rels/.rels", buildRootRels());
        putEntry(zip, "docProps/core.xml", buildCoreProps());
        putEntry(zip, "word/document.xml", documentXml);
        putEntry(zip, "word/styles.xml", buildStyles());
        putEntry(zip, "word/numbering.xml", buildNumbering());
        putEntry(zip, "word/_rels/document.xml.rels", buildDocumentRels());
        for (Media m : media) {
            putEntryBytes(zip, "word/media/" + m.name, m.bytes);
        }
        zip.finish();
        // stylesRel/numberingRel are referenced from document.xml.rels via the
        // fixed ordering above; keep the locals for readability of intent.
        LOG.fine(() -> "SdmDocxWriter: emitted docx (" + media.size() + " image parts, "
                + listDefs.size() + " lists, styles=" + stylesRel + " numbering=" + numberingRel + ")");
    }

    // ------------------------------------------------------------------
    // Block-level emission (into the shared body buffer)
    // ------------------------------------------------------------------

    private void emitBlock(SdmBlock block) {
        if (block == null) {
            return;
        }
        switch (block.getType()) {
            case HEADING: {
                Heading h = (Heading) block;
                int level = Math.max(1, Math.min(6, h.getLevel()));
                emitParagraph(h.getInline(), block.getStyle(), "Heading" + level, 0, 0,
                        framePropsOf(block));
                break;
            }
            case PARAGRAPH: {
                Paragraph p = (Paragraph) block;
                emitParagraph(p.getInline(), block.getStyle(), null, 0, 0, framePropsOf(block));
                break;
            }
            case LIST_BLOCK:
                emitList((ListBlock) block, 0);
                break;
            case TABLE:
                emitTable((Table) block);
                break;
            case FIGURE:
                emitFigure((Figure) block);
                break;
            case QUOTE: {
                Quote q = (Quote) block;
                for (SdmBlock child : q.getChildren()) {
                    // Re-tag direct paragraphs with the Quote style; nested
                    // structure (lists/tables) is emitted as-is.
                    if (child instanceof Paragraph) {
                        emitParagraph(((Paragraph) child).getInline(), child.getStyle(), "Quote", 0, 0);
                    } else {
                        emitBlock(child);
                    }
                }
                break;
            }
            case CODE_BLOCK:
                emitCodeBlock((CodeBlock) block);
                break;
            case THEMATIC_BREAK:
                emitThematicBreak();
                break;
            case CONTAINER: {
                Container c = (Container) block;
                for (SdmBlock child : c.getChildren()) {
                    emitBlock(child);
                }
                break;
            }
            case TOC_BLOCK:
                emitToc((TocBlock) block);
                break;
            case FOOTNOTE:
                emitFootnote((Footnote) block);
                break;
            case FORM_FIELD:
                emitFormField((FormField) block);
                break;
            case OPAQUE:
                // Un-understood content (vector paths, form XObjects,
                // annotations) has no renderable flow payload. An
                // empty-paragraph spacer per opaque is pure noise: a page
                // grid explodes into dozens of them, stacking up to a page
                // of fake whitespace. Emit nothing.
                LOG.fine(() -> "SdmDocxWriter: skipped opaque ("
                        + ((Opaque) block).getRenderHint() + ")");
                break;
            default:
                LOG.fine(() -> "SdmDocxWriter: unmapped block type " + block.getType());
        }
    }

    /**
     * Emits a {@code w:p} for the given inline content.
     *
     * @param inlines    the inline children (may be null/empty)
     * @param style      the block style (may be null)
     * @param pStyle     a paragraph style id (e.g. {@code Heading1}), or null
     * @param numId      list numbering id, or 0 for none
     * @param ilvl       list level (0-based) when {@code numId>0}
     */
    private void emitParagraph(List<SdmInline> inlines, BlockStyle style, String pStyle, int numId, int ilvl) {
        emitParagraph(inlines, style, pStyle, numId, ilvl, null);
    }

    private void emitParagraph(List<SdmInline> inlines, BlockStyle style, String pStyle, int numId,
            int ilvl, String framePr) {
        body.append("<w:p>");
        emitParagraphProps(style, pStyle, numId, ilvl, false, framePr);
        boolean prevOverUnderlay = overUnderlay;
        // A framed paragraph is a poster-page label painted over the underlay
        // raster: its colour is meaningful against the artwork, so don't rescue
        // white text there (unlike flow paragraphs, which sit on white paper).
        overUnderlay = prevOverUnderlay || framePr != null;
        emitInlines(inlines);
        overUnderlay = prevOverUnderlay;
        body.append("</w:p>");
    }

    /**
     * A {@code w:framePr} for an absolutely positioned block (fixed-layout
     * poster pages), or null for ordinary flow blocks. The frame is anchored to
     * the PAGE so the label sits exactly where the source page painted it.
     */
    private static String framePropsOf(SdmBlock block) {
        Object x = block.getAttributes().get("abs-x");
        Object y = block.getAttributes().get("abs-y");
        if (!(x instanceof Number) || !(y instanceof Number)) {
            return null;
        }
        // No w:w: the frame auto-sizes to its (single source line of) text, so a
        // substituted font with wider metrics cannot force a wrap whose second
        // line would smear over the label positioned below.
        StringBuilder sb = new StringBuilder("<w:framePr");
        sb.append(" w:wrap=\"around\" w:vAnchor=\"page\" w:hAnchor=\"page\"")
          .append(" w:x=\"").append(twips(((Number) x).doubleValue())).append('"')
          .append(" w:y=\"").append(Math.max(1, twips(((Number) y).doubleValue()))).append('"')
          .append("/>");
        return sb.toString();
    }

    private void emitParagraphProps(BlockStyle style, String pStyle, int numId, int ilvl, boolean thematicBreak) {
        emitParagraphProps(style, pStyle, numId, ilvl, thematicBreak, null);
    }

    private void emitParagraphProps(BlockStyle style, String pStyle, int numId, int ilvl,
            boolean thematicBreak, String framePr) {
        StringBuilder pr = new StringBuilder();
        // Children of CT_PPr must appear in the schema-defined sequence order
        // (pStyle, framePr, numPr, pBdr, shd, bidi, spacing, ind, jc, ...) or Word
        // offers to "repair" the file; emit strictly in that order.
        if (pStyle != null) {
            pr.append("<w:pStyle w:val=\"").append(pStyle).append("\"/>");
        }
        if (framePr != null) {
            pr.append(framePr);
        }
        if (numId > 0) {
            pr.append("<w:numPr><w:ilvl w:val=\"").append(ilvl).append("\"/>")
              .append("<w:numId w:val=\"").append(numId).append("\"/></w:numPr>");
        }
        if (thematicBreak) {
            pr.append("<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"auto\"/></w:pBdr>");
        }
        if (style != null) {
            if (style.getBackground() != 0) {
                pr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"")
                  .append(hex(style.getBackground())).append("\"/>");
            }
            if (style.getDirection() == BlockStyle.Direction.RTL) {
                pr.append("<w:bidi/>");
            }
            emitSpacingAndIndent(pr, style);
            String jc = alignValue(style.getAlign());
            if (jc != null) {
                pr.append("<w:jc w:val=\"").append(jc).append("\"/>");
            }
        }
        if (pr.length() > 0) {
            body.append("<w:pPr>").append(pr).append("</w:pPr>");
        }
    }

    private void emitSpacingAndIndent(StringBuilder pr, BlockStyle style) {
        double before = style.getSpaceBefore();
        double after = style.getSpaceAfter();
        double line = style.getLineHeight();
        if (before > 0 || after > 0 || line > 0) {
            pr.append("<w:spacing");
            if (before > 0) {
                pr.append(" w:before=\"").append(twips(before)).append('"');
            }
            if (after > 0) {
                pr.append(" w:after=\"").append(twips(after)).append('"');
            }
            if (line > 0) {
                // lineHeight is a multiplier; Word "auto" line rule counts in 240ths.
                pr.append(" w:line=\"").append((long) Math.round(line * 240)).append("\" w:lineRule=\"auto\"");
            }
            pr.append("/>");
        }
        double left = style.getIndentStart();
        double right = style.getIndentEnd();
        double first = style.getIndentFirstLine();
        if (left != 0 || right != 0 || first != 0) {
            pr.append("<w:ind");
            if (left != 0) {
                pr.append(" w:left=\"").append(twips(left)).append('"');
            }
            if (right != 0) {
                pr.append(" w:right=\"").append(twips(right)).append('"');
            }
            if (first > 0) {
                pr.append(" w:firstLine=\"").append(twips(first)).append('"');
            } else if (first < 0) {
                pr.append(" w:hanging=\"").append(twips(-first)).append('"');
            }
            pr.append("/>");
        }
    }

    private void emitList(ListBlock list, int depth) {
        listDefs.add(list.isOrdered());
        int numId = listDefs.size();
        for (ListItem item : list.getItems()) {
            boolean itemHadParagraph = false;
            for (SdmBlock child : item.getChildren()) {
                if (child instanceof Paragraph) {
                    emitParagraph(((Paragraph) child).getInline(), child.getStyle(),
                            "ListParagraph", numId, depth);
                    itemHadParagraph = true;
                } else if (child instanceof ListBlock) {
                    emitList((ListBlock) child, depth + 1);
                } else {
                    emitBlock(child);
                }
            }
            if (!itemHadParagraph && item.getChildren().isEmpty()) {
                // Keep the list marker even for an empty item.
                emitParagraph(null, null, "ListParagraph", numId, depth);
            }
        }
    }

    private void emitCodeBlock(CodeBlock code) {
        String text = code.getText() == null ? "" : code.getText();
        body.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr>");
        String[] lines = text.split("\n", -1);
        body.append("<w:r><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/></w:rPr>");
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                body.append("<w:br/>");
            }
            body.append("<w:t xml:space=\"preserve\">").append(xml(lines[i])).append("</w:t>");
        }
        body.append("</w:r></w:p>");
    }

    private void emitThematicBreak() {
        body.append("<w:p>");
        emitParagraphProps(null, null, 0, 0, true);
        body.append("</w:p>");
    }

    private void emitToc(TocBlock toc) {
        for (TocEntry entry : toc.getEntries()) {
            int level = Math.max(1, entry.getLevel());
            body.append("<w:p><w:pPr><w:pStyle w:val=\"TOC\"/>");
            int indent = (level - 1) * 360;
            if (indent > 0) {
                body.append("<w:ind w:left=\"").append(indent).append("\"/>");
            }
            body.append("</w:pPr>");
            emitRunText(entry.getText() == null ? "" : entry.getText(), null);
            body.append("</w:p>");
        }
    }

    private void emitFootnote(Footnote fn) {
        // No real footnote part — degrade to a paragraph at flow position.
        String refId = fn.getRefId() == null ? "" : fn.getRefId();
        body.append("<w:p>");
        if (!refId.isEmpty()) {
            emitRunText("[" + refId + "] ", null);
        }
        for (SdmBlock child : fn.getChildren()) {
            if (child instanceof Paragraph) {
                emitInlines(((Paragraph) child).getInline());
            }
        }
        body.append("</w:p>");
    }

    private void emitFormField(FormField f) {
        String label = f.getValue();
        if (label == null || label.isEmpty()) {
            label = f.getName();
        }
        body.append("<w:p>");
        emitRunText(label == null ? "" : label, null);
        body.append("</w:p>");
    }

    // ------------------------------------------------------------------
    // Figures / images
    // ------------------------------------------------------------------

    private void emitFigure(Figure fig) {
        // Fixed-layout page underlay: the page's artwork rendered text-free,
        // anchored BEHIND the positioned text frames at the page origin.
        if (Boolean.TRUE.equals(fig.getAttributes().get("page-underlay"))) {
            String uRel = resolveImageRel(fig.getImage());
            if (uRel != null) {
                double w = numAttrOr(fig, "display-width", 612);
                double h = numAttrOr(fig, "display-height", 792);
                // Each poster page is one physical page. Its text became
                // out-of-flow page-anchored frames, so consecutive poster pages
                // carry no flow content to paginate them and would all pile onto
                // one sheet. Force a page break before every underlay after the
                // first so each lands on its own page (and the following frames
                // anchor to that page).
                body.append("<w:p>");
                if (postersEmitted > 0) {
                    body.append("<w:pPr><w:pageBreakBefore/></w:pPr>");
                }
                emitAnchoredDrawing(uRel, Math.round(w * EMU_PER_PT), Math.round(h * EMU_PER_PT),
                        fig.getAlt() == null ? "" : fig.getAlt());
                body.append("</w:p>");
                postersEmitted++;
            }
            return;
        }
        // A near-full-page watermark / page-frame image renders in HTML as an
        // out-of-flow backdrop behind the text. Flow WordprocessingML has no
        // behind-text inline: emitted in the flow, the wallpaper claims a page
        // of its own and pushes the real content down. Skip it.
        if (Boolean.TRUE.equals(fig.getAttributes().get("background"))) {
            LOG.fine(() -> "SdmDocxWriter: skipped background figure " + fig.getId());
            return;
        }
        // Degenerate decoration slivers (a 2pt-wide edge strip) carry no content.
        Object dw = fig.getAttributes().get("display-width");
        Object dh = fig.getAttributes().get("display-height");
        if (dw instanceof Number && dh instanceof Number
                && (((Number) dw).doubleValue() < 4 || ((Number) dh).doubleValue() < 4)) {
            LOG.fine(() -> "SdmDocxWriter: skipped degenerate figure " + fig.getId());
            return;
        }
        String rId = resolveImageRel(fig.getImage());
        if (rId == null) {
            // Nothing to embed — keep any caption so the reference isn't lost.
            if (fig.getCaption() != null && !fig.getCaption().isEmpty()) {
                for (SdmBlock c : fig.getCaption()) {
                    emitBlock(c);
                }
            }
            return;
        }
        long[] emu = figureEmu(fig);
        String alt = fig.getAlt() == null ? "" : fig.getAlt();
        body.append("<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>");
        emitDrawing(rId, emu[0], emu[1], alt);
        body.append("</w:p>");
        if (fig.getCaption() != null && !fig.getCaption().isEmpty()) {
            for (SdmBlock c : fig.getCaption()) {
                if (c instanceof Paragraph) {
                    emitParagraph(((Paragraph) c).getInline(), c.getStyle(), "Caption", 0, 0);
                } else {
                    emitBlock(c);
                }
            }
        }
    }

    /**
     * The picture extent in EMU: PDF footprint when known, else intrinsic px
     * @96dpi, clamped to the section content box — Word does not shrink an
     * oversized inline picture, it overflows the margins and forces page breaks.
     */
    private long[] figureEmu(Figure fig) {
        double wPt = 0;
        double hPt = 0;
        Object w = fig.getAttributes().get("display-width");
        Object h = fig.getAttributes().get("display-height");
        if (w instanceof Number && h instanceof Number
                && ((Number) w).doubleValue() > 0 && ((Number) h).doubleValue() > 0) {
            wPt = ((Number) w).doubleValue();
            hPt = ((Number) h).doubleValue();
        } else {
            // Fall back to the raster's intrinsic pixel dimensions (px @96dpi = 0.75pt).
            Resource res = doc.getResources().get(fig.getImage());
            if (res != null && res.getBytes() != null) {
                try {
                    java.awt.image.BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.getBytes()));
                    if (img != null && img.getWidth() > 0 && img.getHeight() > 0) {
                        wPt = img.getWidth() * 72.0 / 96.0;
                        hPt = img.getHeight() * 72.0 / 96.0;
                    }
                } catch (IOException | RuntimeException e) {
                    LOG.fine(() -> "SdmDocxWriter: image size probe failed: " + e);
                }
            }
        }
        if (wPt <= 0 || hPt <= 0) {
            // Last resort: a modest default so the picture is visible.
            wPt = 200;
            hPt = 150;
        }
        double[] g = pageGeometryPt();
        double maxW = Math.max(72, g[0] - g[2] - g[3]);
        double maxH = Math.max(72, g[1] - g[4] - g[5]);
        double scale = Math.min(1.0, Math.min(maxW / wPt, maxH / hPt));
        return new long[]{Math.round(wPt * scale * EMU_PER_PT),
                Math.round(hPt * scale * EMU_PER_PT)};
    }

    /** Numeric block attribute with a default. */
    private static double numAttrOr(Figure fig, String key, double def) {
        Object v = fig.getAttributes().get(key);
        return v instanceof Number && ((Number) v).doubleValue() > 0
                ? ((Number) v).doubleValue() : def;
    }

    /**
     * A page-anchored, behind-text drawing at the page origin — the fixed-layout
     * underlay. Child order inside {@code wp:anchor} is schema-fixed:
     * simplePos, positionH, positionV, extent, effectExtent, wrapNone, docPr,
     * cNvGraphicFramePr, graphic.
     */
    private void emitAnchoredDrawing(String rId, long cx, long cy, String alt) {
        int id = ++docPrId;
        body.append("<w:r><w:drawing>")
            .append("<wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\"")
            .append(" relativeHeight=\"0\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\"")
            .append(" allowOverlap=\"1\">")
            .append("<wp:simplePos x=\"0\" y=\"0\"/>")
            .append("<wp:positionH relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionH>")
            .append("<wp:positionV relativeFrom=\"page\"><wp:posOffset>0</wp:posOffset></wp:positionV>")
            .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
            .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            .append("<wp:wrapNone/>")
            .append("<wp:docPr id=\"").append(id).append("\" name=\"Underlay ").append(id)
            .append("\" descr=\"").append(xmlAttr(alt)).append("\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic><a:graphicData uri=\"").append(NS_PIC).append("\">")
            .append("<pic:pic>")
            .append("<pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"Underlay ").append(id)
            .append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"").append(rId).append("\"/>")
            .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
            .append("<a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            .append("</pic:pic></a:graphicData></a:graphic>")
            .append("</wp:anchor></w:drawing></w:r>");
    }

    private void emitDrawing(String rId, long cx, long cy, String alt) {
        int id = ++docPrId;
        body.append("<w:r><w:drawing>")
            .append("<wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
            .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
            .append("<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/>")
            .append("<wp:docPr id=\"").append(id).append("\" name=\"Picture ").append(id)
            .append("\" descr=\"").append(xmlAttr(alt)).append("\"/>")
            .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
            .append("<a:graphic><a:graphicData uri=\"").append(NS_PIC).append("\">")
            .append("<pic:pic>")
            .append("<pic:nvPicPr><pic:cNvPr id=\"").append(id).append("\" name=\"Picture ").append(id)
            .append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
            .append("<pic:blipFill><a:blip r:embed=\"").append(rId).append("\"/>")
            .append("<a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
            .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/>")
            .append("<a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
            .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr>")
            .append("</pic:pic>")
            .append("</a:graphicData></a:graphic>")
            .append("</wp:inline></w:drawing></w:r>");
    }

    /** Registers the image bytes as a media part and returns its relationship id, or null. */
    private String resolveImageRel(ResourceRef ref) {
        if (ref == null || doc == null) {
            return null;
        }
        Resource res = doc.getResources().get(ref);
        if (res == null || res.getBytes() == null || res.getBytes().length == 0) {
            return null;
        }
        String mime = res.getMime() == null ? "" : res.getMime();
        String ext = extensionFor(mime);
        mediaExtensions.add(ext);
        String name = "image" + (media.size() + 1) + "." + ext;
        media.add(new Media(name, res.getBytes()));
        return addRel(REL_BASE + "image", "media/" + name, null);
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/jpeg":
            case "image/jpg":
                return "jpeg";
            case "image/gif":
                return "gif";
            case "image/bmp":
                return "bmp";
            case "image/tiff":
                return "tiff";
            default:
                return "png";
        }
    }

    // ------------------------------------------------------------------
    // Tables
    // ------------------------------------------------------------------

    private void emitTable(Table table) {
        boolean ruled = "ruled".equals(table.getAttributes().get("border"));
        String ruleColor = "auto";
        Object bc = table.getAttributes().get("border-color");
        if (bc instanceof String && ((String) bc).startsWith("#")) {
            ruleColor = ((String) bc).substring(1);
        }
        List<TableRow> rows = table.getRows();
        int cols = columnCount(table);
        if (cols <= 0) {
            return;
        }
        // Producer-junk grids: auto-tagged charts arrive from the structure tree
        // as "tables" with hundreds of columns and near-empty cells (35654: 45
        // charts x 357 columns = 23k empty <w:p/>). Word renders such a grid as
        // pages of hairline cells; flatten the little real content to paragraphs
        // instead. (The HTML writer keeps its own rendering — this is a
        // WordprocessingML-only degradation.)
        int cellCount = 0;
        long textChars = 0;
        for (TableRow r : rows) {
            for (TableCell c : r.getCells()) {
                cellCount++;
                for (SdmBlock child : c.getChildren()) {
                    textChars += blockTextChars(child);
                }
            }
        }
        if (cols > 64 || (cellCount >= 24 && textChars < Math.max(8, cellCount / 4))) {
            final int fc = cellCount;
            final long ft = textChars;
            LOG.fine(() -> "SdmDocxWriter: flattened junk grid (" + cols + " cols, "
                    + fc + " cells, " + ft + " chars)");
            for (TableRow r : rows) {
                for (TableCell c : r.getCells()) {
                    for (SdmBlock child : c.getChildren()) {
                        emitBlock(child);
                    }
                }
            }
            return;
        }
        // Fixed layout at the source column widths, but ONLY for WIDE tables:
        // under Word's default AUTO layout a wide table whose cells hold short
        // text collapses to a narrow column (Word shrinks each column to its
        // content), squishing landscape tables. Honouring explicit widths keeps
        // the source geometry. Narrow in-prose tables keep AUTO (untouched) —
        // there squish is not a problem and a mis-estimated fixed width would
        // clip text.
        long[] colw = computeColumnTwips(table, cols);
        boolean fixed = isWideTable(table, cols);
        long tblwSum = 0;
        for (long w : colw) {
            tblwSum += w;
        }
        body.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/>");
        if (fixed) {
            body.append("<w:tblW w:w=\"").append(tblwSum).append("\" w:type=\"dxa\"/>")
                .append("<w:tblLayout w:type=\"fixed\"/>");
        } else {
            body.append("<w:tblW w:w=\"0\" w:type=\"auto\"/>");
        }
        if (ruled) {
            String b = "<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"" + ruleColor + "\"/>";
            body.append("<w:tblBorders>")
                .append(b)
                .append(b.replace("top", "left"))
                .append(b.replace("top", "bottom"))
                .append(b.replace("top", "right"))
                .append(b.replace("top", "insideH"))
                .append(b.replace("top", "insideV"))
                .append("</w:tblBorders>");
        }
        body.append("</w:tblPr>");
        emitTableGrid(colw);

        int[] carryRows = new int[cols];   // remaining continuation rows per column
        int[] carrySpan = new int[cols];   // gridSpan of the cell owning the merge
        for (TableRow row : rows) {
            body.append("<w:tr>");
            List<TableCell> cells = row.getCells();
            int c = 0;
            int ci = 0;
            while (c < cols) {
                if (carryRows[c] > 0) {
                    int span = Math.max(1, carrySpan[c]);
                    emitContinuationCell(span, fixed ? spanTwips(colw, c, span) : 0);
                    carryRows[c]--;
                    c += span;
                } else if (ci < cells.size()) {
                    TableCell cell = cells.get(ci++);
                    int span = Math.max(1, Math.min(cell.getColSpan(), cols - c));
                    emitCell(cell, span, fixed ? spanTwips(colw, c, span) : 0);
                    if (cell.getRowSpan() > 1) {
                        carryRows[c] = cell.getRowSpan() - 1;
                        carrySpan[c] = span;
                    }
                    c += span;
                } else {
                    // Ragged row — pad with a real (non-merged) empty cell so the
                    // grid stays rectangular.
                    if (fixed) {
                        body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(colw[c])
                            .append("\" w:type=\"dxa\"/></w:tcPr><w:p/></w:tc>");
                    } else {
                        body.append("<w:tc><w:tcPr><w:tcW w:w=\"0\" w:type=\"auto\"/></w:tcPr><w:p/></w:tc>");
                    }
                    c += 1;
                }
            }
            body.append("</w:tr>");
        }
        body.append("</w:tbl>");
        // A table may not be the final block of the body/cell — Word requires a
        // trailing paragraph after a table. Append one defensively.
        body.append("<w:p/>");
    }

    /** Total visible text characters under a block (runs of paragraphs/headings, recursive). */
    private static long blockTextChars(SdmBlock block) {
        long n = 0;
        List<SdmInline> inline = block instanceof Paragraph ? ((Paragraph) block).getInline()
                : block instanceof Heading ? ((Heading) block).getInline() : null;
        if (inline != null) {
            for (SdmInline in : inline) {
                if (in instanceof Run && ((Run) in).getText() != null) {
                    n += ((Run) in).getText().trim().length();
                }
            }
        } else if (block instanceof Container) {
            for (SdmBlock child : ((Container) block).getChildren()) {
                n += blockTextChars(child);
            }
        } else if (block instanceof Table) {
            for (TableRow r : ((Table) block).getRows()) {
                for (TableCell c : r.getCells()) {
                    for (SdmBlock child : c.getChildren()) {
                        n += blockTextChars(child);
                    }
                }
            }
        }
        return n;
    }

    /** Minimum column width (twips) so a scaled/unknown column never vanishes. */
    private static final long MIN_COL_TWIPS = 180;

    /**
     * Column widths in twips for a fixed-layout table: source POINTS widths
     * where known, an even share of the leftover content width for unknown
     * columns, all scaled down proportionally if the total overflows the page
     * content box (Word does not shrink a fixed table — it overruns the margin).
     */
    private long[] computeColumnTwips(Table table, int cols) {
        double[] g = pageGeometryPt();
        long content = Math.max(720, twips(g[0] - g[2] - g[3]));
        long[] w = new long[cols];
        List<ColumnSpec> specs = table.getColumns();
        long known = 0;
        int unknown = 0;
        for (int i = 0; i < cols; i++) {
            long cw = 0;
            if (specs != null && i < specs.size()
                    && specs.get(i).getWidthType() == ColumnSpec.WidthType.POINTS
                    && specs.get(i).getWidth() > 0) {
                cw = twips(specs.get(i).getWidth());
            }
            if (cw > 0) {
                w[i] = cw;
                known += cw;
            } else {
                w[i] = -1;
                unknown++;
            }
        }
        if (unknown > 0) {
            long remain = content - known;
            long each = remain > 0 ? Math.max(MIN_COL_TWIPS, remain / unknown) : MIN_COL_TWIPS;
            for (int i = 0; i < cols; i++) {
                if (w[i] < 0) {
                    w[i] = each;
                }
            }
        }
        long sum = 0;
        for (long x : w) {
            sum += x;
        }
        if (sum <= 0) {
            long each = Math.max(MIN_COL_TWIPS, content / Math.max(1, cols));
            for (int i = 0; i < cols; i++) {
                w[i] = each;
            }
            return w;
        }
        if (sum > content) {
            double s = (double) content / sum;
            for (int i = 0; i < cols; i++) {
                w[i] = Math.max(1, Math.round(w[i] * s));
            }
        }
        return w;
    }

    /**
     * A table is "wide" when its known source column widths span at least 60% of
     * the page content width — the case where AUTO layout squishes it to a
     * narrow column. Narrow in-prose tables (mostly unknown or small widths)
     * return false and keep the safe AUTO behaviour.
     */
    private boolean isWideTable(Table table, int cols) {
        List<ColumnSpec> specs = table.getColumns();
        if (specs == null) {
            return false;
        }
        long known = 0;
        for (int i = 0; i < cols && i < specs.size(); i++) {
            if (specs.get(i).getWidthType() == ColumnSpec.WidthType.POINTS
                    && specs.get(i).getWidth() > 0) {
                known += twips(specs.get(i).getWidth());
            }
        }
        double[] g = pageGeometryPt();
        long content = Math.max(720, twips(g[0] - g[2] - g[3]));
        return known >= 0.60 * content;
    }

    /** Sum of the fixed column widths spanned by a cell. */
    private static long spanTwips(long[] colw, int start, int span) {
        long s = 0;
        for (int i = start; i < start + span && i < colw.length; i++) {
            s += colw[i];
        }
        return Math.max(MIN_COL_TWIPS, s);
    }

    private void emitTableGrid(long[] colw) {
        body.append("<w:tblGrid>");
        for (long w : colw) {
            body.append("<w:gridCol w:w=\"").append(Math.max(1, w)).append("\"/>");
        }
        body.append("</w:tblGrid>");
    }

    private void emitCell(TableCell cell, int gridSpan, long cellTwips) {
        body.append("<w:tc><w:tcPr>");
        if (cellTwips > 0) {
            body.append("<w:tcW w:w=\"").append(cellTwips).append("\" w:type=\"dxa\"/>");
        } else {
            body.append("<w:tcW w:w=\"0\" w:type=\"auto\"/>");
        }
        if (gridSpan > 1) {
            body.append("<w:gridSpan w:val=\"").append(gridSpan).append("\"/>");
        }
        if (cell.getRowSpan() > 1) {
            body.append("<w:vMerge w:val=\"restart\"/>");
        }
        // Cell background shading (CT_TcPr: shd follows vMerge, precedes tcMar).
        if (cell.getStyle() != null && cell.getStyle().getBackground() != 0) {
            body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"")
                .append(hex(cell.getStyle().getBackground())).append("\"/>");
        }
        body.append("</w:tcPr>");
        int before = body.length();
        for (SdmBlock child : cell.getChildren()) {
            if (child instanceof Paragraph && cell.getKind() == TableCell.Kind.TH) {
                // Emphasize header cells so the table reads correctly in Word.
                emitParagraph(((Paragraph) child).getInline(), child.getStyle(), "TableHeader", 0, 0);
            } else {
                emitBlock(child);
            }
        }
        if (body.length() == before) {
            body.append("<w:p/>"); // a table cell must contain at least one paragraph
        }
        body.append("</w:tc>");
    }

    private void emitContinuationCell(int gridSpan, long cellTwips) {
        body.append("<w:tc><w:tcPr>");
        if (cellTwips > 0) {
            body.append("<w:tcW w:w=\"").append(cellTwips).append("\" w:type=\"dxa\"/>");
        } else {
            body.append("<w:tcW w:w=\"0\" w:type=\"auto\"/>");
        }
        if (gridSpan > 1) {
            body.append("<w:gridSpan w:val=\"").append(gridSpan).append("\"/>");
        }
        body.append("<w:vMerge w:val=\"continue\"/></w:tcPr><w:p/></w:tc>");
    }

    private int columnCount(Table table) {
        int cols = table.getColumns() == null ? 0 : table.getColumns().size();
        for (TableRow row : table.getRows()) {
            int sum = 0;
            for (TableCell cell : row.getCells()) {
                sum += Math.max(1, cell.getColSpan());
            }
            cols = Math.max(cols, sum);
        }
        return cols;
    }

    // ------------------------------------------------------------------
    // Inline-level emission
    // ------------------------------------------------------------------

    private void emitInlines(List<SdmInline> inlines) {
        if (inlines == null) {
            return;
        }
        int size = inlines.size();
        int i = 0;
        while (i < size) {
            SdmInline inline = inlines.get(i);
            String key = runLinkKey(inline);
            if (key != null) {
                // Consecutive runs tagged (by LinkAnnotationEnricher) with the
                // SAME link target become ONE hyperlink / cross-reference field.
                int j = i + 1;
                while (j < size && key.equals(runLinkKey(inlines.get(j)))) {
                    j++;
                }
                emitTaggedLinkGroup(inlines.subList(i, j), (Run) inline);
                i = j;
            } else {
                emitInline(inline);
                i++;
            }
        }
    }

    /**
     * Link grouping key for a run tagged by {@link LinkAnnotationEnricher}, or
     * {@code null} if the inline is not a link-tagged run. External and internal
     * targets share no key space ({@code H:} vs {@code A:} prefix).
     */
    private static String runLinkKey(SdmInline inline) {
        if (!(inline instanceof Run)) {
            return null;
        }
        Object href = ((Run) inline).getAttributes().get(LinkAnnotationEnricher.ATTR_HREF);
        if (href instanceof String && !((String) href).isEmpty()) {
            return "H:" + href;
        }
        Object anchor = ((Run) inline).getAttributes().get(LinkAnnotationEnricher.ATTR_ANCHOR);
        if (anchor instanceof String && !((String) anchor).isEmpty()) {
            return "A:" + anchor;
        }
        return null;
    }

    /** Wraps a run of link-tagged runs in the matching Word hyperlink/field. */
    private void emitTaggedLinkGroup(List<SdmInline> group, Run first) {
        Object href = first.getAttributes().get(LinkAnnotationEnricher.ATTR_HREF);
        Object anchor = first.getAttributes().get(LinkAnnotationEnricher.ATTR_ANCHOR);
        boolean prev = inHyperlink;
        if (href instanceof String && !((String) href).isEmpty()) {
            String rId = addRel(REL_BASE + "hyperlink", (String) href, "External");
            body.append("<w:hyperlink r:id=\"").append(rId).append("\">");
            inHyperlink = true;
            for (SdmInline in : group) {
                emitInline(in);
            }
            inHyperlink = prev;
            body.append("</w:hyperlink>");
        } else if (anchor instanceof String && !((String) anchor).isEmpty()) {
            body.append("<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>");
            body.append("<w:r><w:instrText xml:space=\"preserve\"> HYPERLINK \\l \"")
                .append(xml((String) anchor)).append("\" </w:instrText></w:r>");
            body.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>");
            inHyperlink = true;
            for (SdmInline in : group) {
                emitInline(in);
            }
            inHyperlink = prev;
            body.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
        } else {
            for (SdmInline in : group) {
                emitInline(in);
            }
        }
    }

    private void emitInline(SdmInline inline) {
        if (inline == null) {
            return;
        }
        switch (inline.getType()) {
            case RUN:
                emitRun((Run) inline);
                break;
            case LINK_INLINE: {
                LinkInline link = (LinkInline) inline;
                String href = link.getHref();
                String anchor = link.getInternalAnchor();
                if (href != null && !href.isEmpty()) {
                    String rId = addRel(REL_BASE + "hyperlink", href, "External");
                    body.append("<w:hyperlink r:id=\"").append(rId).append("\">");
                    boolean prev = inHyperlink;
                    inHyperlink = true;
                    emitInlines(link.getChildren());
                    inHyperlink = prev;
                    body.append("</w:hyperlink>");
                } else if (anchor != null && !anchor.isEmpty()) {
                    // Internal cross-reference: Word models it as a HYPERLINK
                    // field with the \l switch (link to a bookmark), NOT a
                    // relationship-backed <w:hyperlink>. The display text sits
                    // between the "separate" and "end" field-char runs.
                    body.append("<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>");
                    body.append("<w:r><w:instrText xml:space=\"preserve\"> HYPERLINK \\l \"")
                        .append(xml(anchor)).append("\" </w:instrText></w:r>");
                    body.append("<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>");
                    boolean prev = inHyperlink;
                    inHyperlink = true;
                    emitInlines(link.getChildren());
                    inHyperlink = prev;
                    body.append("<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>");
                } else {
                    emitInlines(link.getChildren());
                }
                break;
            }
            case INLINE_IMAGE: {
                InlineImage img = (InlineImage) inline;
                String rId = resolveImageRel(img.getImage());
                if (rId != null) {
                    long[] emu = inlineImageEmu(img);
                    emitDrawing(rId, emu[0], emu[1], img.getAlt() == null ? "" : img.getAlt());
                }
                break;
            }
            case LINE_BREAK:
                body.append("<w:r><w:br/></w:r>");
                break;
            case FOOTNOTE_REF: {
                FootnoteRef ref = (FootnoteRef) inline;
                String refId = ref.getRefId() == null ? "" : ref.getRefId();
                body.append("<w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>")
                    .append("<w:t xml:space=\"preserve\">").append(xml(refId)).append("</w:t></w:r>");
                break;
            }
            case INLINE_OPAQUE:
                break; // un-understood inline — drop silently (no visible content)
            default:
                LOG.fine(() -> "SdmDocxWriter: unmapped inline type " + inline.getType());
        }
    }

    private long[] inlineImageEmu(InlineImage img) {
        Resource res = doc.getResources().get(img.getImage());
        if (res != null && res.getBytes() != null) {
            try {
                java.awt.image.BufferedImage bi = ImageIO.read(new ByteArrayInputStream(res.getBytes()));
                if (bi != null && bi.getWidth() > 0 && bi.getHeight() > 0) {
                    return new long[]{(long) bi.getWidth() * EMU_PER_PX_96DPI,
                            (long) bi.getHeight() * EMU_PER_PX_96DPI};
                }
            } catch (IOException | RuntimeException e) {
                LOG.fine(() -> "SdmDocxWriter: inline image size probe failed: " + e);
            }
        }
        return new long[]{(long) (12 * EMU_PER_PT), (long) (12 * EMU_PER_PT)};
    }

    private void emitRun(Run run) {
        java.util.List<String> anchors = bookmarkAnchorsOf(run);
        for (String a : anchors) {
            openBookmark(a);
        }
        emitRunText(run.getText() == null ? "" : run.getText(), run.getStyle());
        for (int i = anchors.size() - 1; i >= 0; i--) {
            closeBookmark(anchors.get(i));
        }
    }

    /**
     * Returns the bookmark anchor names a run has been tagged with (a run may be
     * the destination of several links resolving to the same location), or an
     * empty list. Populated by {@code LinkAnnotationEnricher}.
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> bookmarkAnchorsOf(Run run) {
        Object v = run.getAttributes().get("bookmark-anchors");
        if (v instanceof java.util.List) {
            return (java.util.List<String>) v;
        }
        return java.util.Collections.emptyList();
    }

    /** Emits a {@code w:bookmarkStart} for the anchor, minting a numeric id. */
    private void openBookmark(String name) {
        int id = bookmarkIds.computeIfAbsent(name, k -> bookmarkCounter++);
        body.append("<w:bookmarkStart w:id=\"").append(id)
            .append("\" w:name=\"").append(xmlAttr(name)).append("\"/>");
    }

    /** Emits the matching {@code w:bookmarkEnd} for the anchor. */
    private void closeBookmark(String name) {
        Integer id = bookmarkIds.get(name);
        if (id != null) {
            body.append("<w:bookmarkEnd w:id=\"").append(id).append("\"/>");
        }
    }

    /** Emits a single {@code w:r} carrying the given text and (optional) style. */
    private void emitRunText(String text, TextStyle st) {
        body.append("<w:r>");
        StringBuilder rpr = new StringBuilder();
        if (inHyperlink) {
            rpr.append("<w:rStyle w:val=\"Hyperlink\"/>");
        }
        if (st != null) {
            if (st.getFontFamily() != null && !st.getFontFamily().isEmpty()) {
                String fam = xmlAttr(st.getFontFamily());
                rpr.append("<w:rFonts w:ascii=\"").append(fam).append("\" w:hAnsi=\"").append(fam)
                   .append("\" w:cs=\"").append(fam).append("\"/>");
            }
            // CT_RPr children must follow the schema sequence: b, i, strike,
            // color, sz, szCs, u, shd, vertAlign (rStyle/rFonts already emitted).
            if (st.isBold()) {
                rpr.append("<w:b/>");
            }
            if (st.isItalic()) {
                rpr.append("<w:i/>");
            }
            if (st.isStrikethrough()) {
                rpr.append("<w:strike/>");
            }
            if (st.getColor() != 0) {
                // White-text rescue: in the reflow, a near-white run painted on
                // a coloured fill in the source (a section-header bar, a badge)
                // loses that fill — the writer never shades flow cells/paragraphs
                // — so it would print invisible white-on-white. When the run has
                // no background of its own and is NOT a poster-page frame over
                // the underlay raster, drop the colour so the text inherits the
                // default dark and stays legible.
                if (isNearWhite(st.getColor()) && st.getBackground() == 0 && !overUnderlay) {
                    // inherit default (auto/black): emit no w:color
                } else {
                    rpr.append("<w:color w:val=\"").append(hex(st.getColor())).append("\"/>");
                }
            }
            if (st.getFontSize() > 0) {
                long half = Math.round(st.getFontSize() * 2);
                rpr.append("<w:sz w:val=\"").append(half).append("\"/>")
                   .append("<w:szCs w:val=\"").append(half).append("\"/>");
            }
            if (st.isUnderline()) {
                rpr.append("<w:u w:val=\"single\"/>");
            }
            if (st.getBackground() != 0) {
                rpr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"")
                   .append(hex(st.getBackground())).append("\"/>");
            }
            if (st.getVertAlign() == TextStyle.VertAlign.SUPER) {
                rpr.append("<w:vertAlign w:val=\"superscript\"/>");
            } else if (st.getVertAlign() == TextStyle.VertAlign.SUB) {
                rpr.append("<w:vertAlign w:val=\"subscript\"/>");
            }
        }
        if (rpr.length() > 0) {
            body.append("<w:rPr>").append(rpr).append("</w:rPr>");
        }
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                body.append("<w:br/>");
            }
            body.append("<w:t xml:space=\"preserve\">").append(xml(lines[i])).append("</w:t>");
        }
        body.append("</w:r>");
    }

    // ------------------------------------------------------------------
    // Part assembly
    // ------------------------------------------------------------------

    private String buildDocumentXml() {
        StringBuilder sb = new StringBuilder(body.length() + 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:document")
          .append(" xmlns:w=\"").append(NS_W).append('"')
          .append(" xmlns:r=\"").append(NS_R).append('"')
          .append(" xmlns:wp=\"").append(NS_WP).append('"')
          .append(" xmlns:a=\"").append(NS_A).append('"')
          .append(" xmlns:pic=\"").append(NS_PIC).append("\">");
        sb.append("<w:body>");
        sb.append(body);
        sb.append(buildSectPr());
        sb.append("</w:body></w:document>");
        return sb.toString();
    }

    private String buildSectPr() {
        double[] g = pageGeometryPt();
        return "<w:sectPr><w:pgSz w:w=\"" + twips(g[0]) + "\" w:h=\"" + twips(g[1]) + "\"/>"
                + "<w:pgMar w:top=\"" + twips(g[4]) + "\" w:right=\"" + twips(g[3])
                + "\" w:bottom=\"" + twips(g[5]) + "\" w:left=\"" + twips(g[2])
                + "\" w:header=\"720\" w:footer=\"720\" w:gutter=\"0\"/>"
                + "</w:sectPr>";
    }

    /**
     * Page geometry in points from the SDM metadata (source page size and the
     * measured body margins), with US-Letter/1in defaults.
     *
     * @return {@code {pageW, pageH, marginLeft, marginRight, marginTop, marginBottom}}
     */
    private double[] pageGeometryPt() {
        double pw = 612;  // US Letter (8.5in)
        double ph = 792;  // 11in
        double ml = 72;
        double mr = 72;
        double mt = 72;
        double mb = 72;
        SdmMetadata meta = doc == null ? null : doc.getMetadata();
        if (meta != null) {
            Double w = parse(meta.getCustom().get("page-width"));
            Double h = parse(meta.getCustom().get("page-height"));
            if (w != null && w > 0) {
                pw = w;
            }
            if (h != null && h > 0) {
                ph = h;
            }
            Double left = parse(meta.getCustom().get("margin-left"));
            Double right = parse(meta.getCustom().get("margin-right"));
            Double top = parse(meta.getCustom().get("margin-top"));
            if (left != null && left > 0) {
                ml = left;
            }
            if (right != null && right > 0) {
                mr = right;
            }
            if (top != null && top > 0) {
                mt = top;
                mb = top;
            }
        }
        return new double[]{pw, ph, ml, mr, mt, mb};
    }

    private String buildContentTypes() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        for (String ext : mediaExtensions) {
            sb.append("<Default Extension=\"").append(ext).append("\" ContentType=\"")
              .append(imageContentType(ext)).append("\"/>");
        }
        sb.append("<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>");
        sb.append("<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>");
        sb.append("<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>");
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private static String imageContentType(String ext) {
        switch (ext) {
            case "jpeg": return "image/jpeg";
            case "gif":  return "image/gif";
            case "bmp":  return "image/bmp";
            case "tiff": return "image/tiff";
            default:     return "image/png";
        }
    }

    private String buildRootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>";
    }

    private String buildDocumentRels() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (Rel r : rels) {
            sb.append("<Relationship Id=\"").append(r.id).append("\" Type=\"").append(r.type)
              .append("\" Target=\"").append(xmlAttr(r.target)).append('"');
            if (r.mode != null) {
                sb.append(" TargetMode=\"").append(r.mode).append('"');
            }
            sb.append("/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private String buildCoreProps() {
        SdmMetadata meta = doc.getMetadata();
        String title = meta == null || meta.getTitle() == null ? "" : meta.getTitle();
        String author = meta == null || meta.getAuthor() == null ? "" : meta.getAuthor();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\"")
          .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
          .append(" xmlns:dcterms=\"http://purl.org/dc/terms/\"")
          .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">");
        sb.append("<dc:title>").append(xml(title)).append("</dc:title>");
        sb.append("<dc:creator>").append(xml(author)).append("</dc:creator>");
        sb.append("</cp:coreProperties>");
        return sb.toString();
    }

    private String buildStyles() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:styles xmlns:w=\"").append(NS_W).append("\">");
        // Document defaults.
        sb.append("<w:docDefaults><w:rPrDefault><w:rPr>")
          .append("<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:cs=\"Calibri\"/>")
          .append("<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:rPrDefault>")
          // Compact defaults for source fidelity: single line spacing and no
          // after-gap. Word's stock 8pt-after/1.08-line adds ~30% vertical air
          // that the source PDF does not have, spilling one PDF page onto two.
          .append("<w:pPrDefault><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>")
          .append("</w:docDefaults>");
        sb.append(paragraphStyle("Normal", "Normal", null, false, 0, null, true));
        sb.append(paragraphStyle("Title", "Title", "Normal", true, 56, "2E74B5", false));
        int[] hSizes = {32, 28, 26, 24, 22, 20};
        for (int i = 1; i <= 6; i++) {
            sb.append(paragraphStyle("Heading" + i, "heading " + i, "Normal", true, hSizes[i - 1], "2E74B5", false));
        }
        sb.append(paragraphStyle("ListParagraph", "List Paragraph", "Normal", false, 0, null, false));
        sb.append(paragraphStyle("Caption", "Caption", "Normal", false, 18, "44546A", false));
        sb.append(paragraphStyle("TOC", "TOC", "Normal", false, 0, null, false));
        sb.append(paragraphStyle("TableHeader", "Table Header", "Normal", true, 0, null, false));
        // Quote: italic + indented.
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/>")
          .append("<w:basedOn w:val=\"Normal\"/><w:pPr><w:ind w:left=\"720\" w:right=\"720\"/></w:pPr>")
          .append("<w:rPr><w:i/><w:iCs/><w:color w:val=\"404040\"/></w:rPr></w:style>");
        // Code: monospace, shaded.
        sb.append("<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/>")
          .append("<w:basedOn w:val=\"Normal\"/><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>")
          .append("<w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr>")
          .append("<w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\"/></w:rPr></w:style>");
        // Hyperlink character style.
        sb.append("<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/>")
          .append("<w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>");
        // A minimal bordered table style.
        sb.append("<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/>")
          .append("<w:tblPr><w:tblBorders>")
          .append("<w:top w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("<w:left w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("<w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("<w:right w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("<w:insideH w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("<w:insideV w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>")
          .append("</w:tblBorders></w:tblPr></w:style>");
        sb.append("</w:styles>");
        return sb.toString();
    }

    private String paragraphStyle(String id, String name, String basedOn, boolean bold, int sz,
                                  String color, boolean isDefault) {
        StringBuilder sb = new StringBuilder();
        sb.append("<w:style w:type=\"paragraph\"");
        if (isDefault) {
            sb.append(" w:default=\"1\"");
        }
        sb.append(" w:styleId=\"").append(id).append("\"><w:name w:val=\"").append(name).append("\"/>");
        if (basedOn != null) {
            sb.append("<w:basedOn w:val=\"").append(basedOn).append("\"/>");
        }
        if (id.startsWith("Heading") || id.equals("Title")) {
            // Compact heading spacing (6pt/3pt): the source PDF's vertical rhythm
            // is dense; Word's stock 12pt/6pt inflates one PDF page onto two.
            sb.append("<w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\"120\" w:after=\"60\"/></w:pPr>");
        }
        StringBuilder rpr = new StringBuilder();
        if (bold) {
            rpr.append("<w:b/><w:bCs/>");
        }
        if (sz > 0) {
            rpr.append("<w:sz w:val=\"").append(sz).append("\"/><w:szCs w:val=\"").append(sz).append("\"/>");
        }
        if (color != null) {
            rpr.append("<w:color w:val=\"").append(color).append("\"/>");
        }
        if (rpr.length() > 0) {
            sb.append("<w:rPr>").append(rpr).append("</w:rPr>");
        }
        sb.append("</w:style>");
        return sb.toString();
    }

    private String buildNumbering() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<w:numbering xmlns:w=\"").append(NS_W).append("\">");
        // abstractNum 0 = bullet, 1 = decimal, 9 levels each.
        sb.append("<w:abstractNum w:abstractNumId=\"0\">");
        for (int lvl = 0; lvl < 9; lvl++) {
            sb.append("<w:lvl w:ilvl=\"").append(lvl).append("\">")
              .append("<w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/>")
              .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append((lvl + 1) * 720)
              .append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        sb.append("</w:abstractNum>");
        sb.append("<w:abstractNum w:abstractNumId=\"1\">");
        for (int lvl = 0; lvl < 9; lvl++) {
            sb.append("<w:lvl w:ilvl=\"").append(lvl).append("\">")
              .append("<w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%")
              .append(lvl + 1).append(".\"/>")
              .append("<w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"").append((lvl + 1) * 720)
              .append("\" w:hanging=\"360\"/></w:pPr></w:lvl>");
        }
        sb.append("</w:abstractNum>");
        for (int i = 0; i < listDefs.size(); i++) {
            int numId = i + 1;
            int abstractId = listDefs.get(i) ? 1 : 0;
            sb.append("<w:num w:numId=\"").append(numId).append("\"><w:abstractNumId w:val=\"")
              .append(abstractId).append("\"/></w:num>");
        }
        sb.append("</w:numbering>");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String addRel(String type, String target, String mode) {
        String id = "rId" + (++relCounter);
        rels.add(new Rel(id, type, target, mode));
        return id;
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        putEntryBytes(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Fixed ZIP timestamp (2000-01-01 UTC): a default entry time is "now" with
     *  2-second DOS granularity, making byte-identical inputs produce different
     *  packages across a second boundary — the writer is deterministic. */
    private static final long ZIP_ENTRY_TIME = 946684800000L;

    private static void putEntryBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ZIP_ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static long twips(double pt) {
        return Math.round(pt * TWIPS_PER_PT);
    }

    private static String hex(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }

    /** True when every RGB channel is near the top of the range (>= 0xE6) — a
     *  glyph colour that is invisible on white paper. */
    private static boolean isNearWhite(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 0xE6 && g >= 0xE6 && b >= 0xE6;
    }

    private static Double parse(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String alignValue(BlockStyle.Align align) {
        if (align == null) {
            return null;
        }
        switch (align) {
            case CENTER:  return "center";
            case RIGHT:   return "right";
            case JUSTIFY: return "both";
            default:      return null; // LEFT is the default; emit nothing
        }
    }

    /** Escapes text content ({@code & < >}); illegal XML control chars become spaces. */
    static String xml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            if (c == '&') {
                rep = "&amp;";
            } else if (c == '<') {
                rep = "&lt;";
            } else if (c == '>') {
                rep = "&gt;";
            } else if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF) {
                rep = " ";
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16).append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** Escapes attribute values: text escaping plus {@code "}. */
    static String xmlAttr(String s) {
        return xml(s).replace("\"", "&quot;");
    }
}
