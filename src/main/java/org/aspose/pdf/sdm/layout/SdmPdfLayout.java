package org.aspose.pdf.sdm.layout;

import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.layout.ContentStreamBuilder;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.html.CssContext;
import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;

/**
 * IR Stage 4, PART 2 — the <b>SDM &rarr; PDF layout engine</b>. Turns an SDM tree
 * into a paginated {@link Document}: block flow (vertical stacking with
 * {@link BlockStyle}), inline flow (line breaking to the content width, REUSING
 * {@link TextLayoutHelper#wrapText}), pagination (own splitter over the block
 * list — the XFA splitter is XFA-node coupled — with heading keep-together and
 * table-header repeat), tables (column widths from ColumnSpec, span placement),
 * figures (placeholder box + caption), and std-14 fonts with metric
 * substitution recorded on the {@link LayoutReport}.
 *
 * <p>Reuses the library's own text metrics ({@link TextLayoutHelper}) and PDF
 * content emission ({@link ContentStreamBuilder}) — no wrapping/measurement is
 * duplicated. Sizes are taken FAITHFULLY from the SDM (per the PART-1 px=pt
 * convention); the engine does not rescale.</p>
 */
public final class SdmPdfLayout {

    private static final double DEFAULT_SIZE = 12.0;
    private static final String DEFAULT_FONT = "Helvetica";
    /** Heading level (1..6) → point size. */
    private static final double[] HEADING_SIZE = {24, 18, 14, 12, 11, 10};
    /** Upper bound for auto-widening the page to fit a wide table (points).
     *  ~19in — comfortably fits multi-column forms without unbounded growth. */
    private static final double MAX_AUTO_CONTENT_WIDTH = 1350.0;
    /** A wrappable (non-nowrap) cell contributes at most this to a table's
     *  preferred width — a long prose cell should wrap, not widen the page. */
    private static final double PREFERRED_WRAP_CAP = 140.0;

    /** The layout result: the produced document and the diagnostics report. */
    public static final class Result {
        private final Document document;
        private final LayoutReport report;

        Result(Document document, LayoutReport report) {
            this.document = document;
            this.report = report;
        }

        /** @return the produced PDF document. */
        public Document getDocument() { return document; }

        /** @return the layout diagnostics (font substitutions, Opaque, missing images). */
        public LayoutReport getReport() { return report; }
    }

    /**
     * Renders an SDM document to a paginated PDF.
     *
     * @param sdm   the SDM tree
     * @param setup the page geometry (null → Letter with 72pt margins)
     * @return the produced document + layout report
     */
    public Result render(SdmDocument sdm, PageSetup setup) {
        Result first = renderPass(sdm, setup, 0);
        // A NUMPAGES furniture token needs the total page count, which is only
        // known after a full layout — run a second, identical pass with it.
        if (setup != null && furnitureUsesNumPages(setup)) {
            try {
                return renderPass(sdm, setup, first.getDocument().getPages().getCount());
            } catch (java.io.IOException e) {
                return first; // cannot count pages — keep the single-pass result
            }
        }
        return first;
    }

    private static boolean furnitureUsesNumPages(PageSetup setup) {
        for (PageSetup.FurnitureLine l : setup.getHeaderLines()) {
            if (l.getText().contains(PageSetup.NUMPAGES_TOKEN)) {
                return true;
            }
        }
        for (PageSetup.FurnitureLine l : setup.getFooterLines()) {
            if (l.getText().contains(PageSetup.NUMPAGES_TOKEN)) {
                return true;
            }
        }
        return false;
    }

    private Result renderPass(SdmDocument sdm, PageSetup setup, int totalPages) {
        if (sdm == null) {
            throw new IllegalArgumentException("sdm must not be null");
        }
        if (setup == null) {
            setup = PageSetup.letter();
        }
        // Wide label/value forms (nowrap labels + many columns) need more width
        // than a portrait letter page offers; widen the page to the widest table's
        // preferred width (capped) so columns are not crushed into overlap.
        double needed = setup.isFixedSize() ? 0 : maxPreferredTableWidth(sdm.getChildren());
        double avail = setup.getContentWidth();
        if (needed > avail * 1.10) {
            double newContent = Math.min(needed, MAX_AUTO_CONTENT_WIDTH);
            // Copy — never mutate the caller's (often shared) PageSetup.
            setup = new PageSetup(setup);
            setup.setPageWidth(newContent + setup.getMarginLeft() + setup.getMarginRight());
        }
        LayoutState st = new LayoutState(setup);
        st.resources = sdm.getResources();
        st.totalPages = totalPages;
        st.newPage();
        double x = setup.getMarginLeft();
        double width = setup.getContentWidth();
        for (SdmBlock b : sdm.getChildren()) {
            layoutBlock(b, st, x, width, sdm.getChildren(), sdm.getChildren().indexOf(b));
        }
        st.finishPage();
        st.subsetUnicodeFonts();
        // A trailing page that never received ink (only spacers/page-breaks ran
        // onto it) is an artifact — drop it.
        if (!st.drewOnPage && st.pages.getCount() > 1) {
            st.pages.delete(st.pages.getCount());
        }
        st.report.setPageCount(st.pages.getCount());
        return new Result(st.doc, st.report);
    }

    // ---- block dispatch ----------------------------------------------------

    private void layoutBlock(SdmBlock b, LayoutState st, double x, double width,
                             List<SdmBlock> siblings, int index) {
        // Explicit page break (DOCX w:br type="page" / w:pageBreakBefore): the
        // block starts a fresh page unless the current one is still empty.
        if (Boolean.TRUE.equals(b.getAttributes().get("page-break-before"))
                && !st.suppressNewPage && !st.pageIsEmpty()) {
            debugBreak("explicit:" + b.getType(), st, 0);
            st.newPage();
        }
        BlockStyle bs = b.getStyle();
        double spaceBefore = bs != null ? bs.getSpaceBefore() : 0;
        double spaceAfter = bs != null ? bs.getSpaceAfter() : 0;
        double indentStart = bs != null ? bs.getIndentStart() : 0;
        double indentEnd = bs != null ? bs.getIndentEnd() : 0;
        double bx = x + indentStart;
        double bw = width - indentStart - indentEnd;
        st.cursorY -= spaceBefore;
        // Space-before is FLEXIBLE whitespace: when the gap alone (not the
        // content) would push past the page bottom, Word swallows the excess
        // instead of exiling the block to the next page — converter documents
        // position their footer line with a large before-gap and rely on this.
        // Clamps only downward drift caused by the gap; never raises the cursor
        // above where it already was.
        if (spaceBefore > 0 && !st.suppressNewPage) {
            double floor = st.setup.getContentBottom() + 12;
            if (st.cursorY < floor) {
                st.cursorY = Math.min(st.cursorY + spaceBefore, floor);
            }
        }

        switch (b.getType()) {
            case HEADING: {
                Heading h = (Heading) b;
                // A size declared on the heading's runs (DOCX HeadingN style rPr,
                // CSS font-size) beats the built-in ladder — the ladder is only
                // a default for sources that say nothing.
                double declared = declaredRunSize(h.getInline());
                double size = declared > 0
                        ? declared
                        : HEADING_SIZE[Math.max(0, Math.min(5, h.getLevel() - 1))];
                String family = declaredRunFamily(h.getInline());
                // keep-together: a heading must not be the last thing on a page.
                keepHeadingWithNext(st, size);
                double hLinkTop = st.cursorY;
                drawInlineBlock(inlineText(h.getInline()), st, bx, bw,
                        family != null ? family : DEFAULT_FONT, size, true,
                        false, textColor(h.getInline()), bs,
                        forcedLineOf(b), lineRuleOf(b));
                addLinkAnnotations(h.getInline(), st, bx, bw, hLinkTop);
                break;
            }
            case PARAGRAPH: {
                Paragraph p = (Paragraph) b;
                RunFont f = blockFont(p.getInline());
                // Positioned frame (legacy .doc sprmPDxaAbs/PDyaAbs): the
                // paragraph paints at its absolute page position and does NOT
                // consume flow — undo the generic space-before and restore the
                // cursor afterwards.
                Object frameY = b.getAttributes().get("frame-y-pt");
                if (frameY instanceof Double && !st.suppressNewPage) {
                    double savedY = st.cursorY + spaceBefore;
                    Object fxAttr = b.getAttributes().get("frame-x-pt");
                    Object fwAttr = b.getAttributes().get("frame-w-pt");
                    double fx = (fxAttr instanceof Double ? (Double) fxAttr : x) + indentStart;
                    double fw = (fwAttr instanceof Double ? (Double) fwAttr : width)
                            - indentStart - indentEnd;
                    // CONSECUTIVE paragraphs with the same frame position are
                    // ONE Word frame: they stack inside it (with their own
                    // spacing); only a new position starts at its dyaAbs.
                    String key = fxAttr + "|" + frameY + "|" + fwAttr;
                    if (!key.equals(st.frameKey)) {
                        st.frameKey = key;
                        st.frameCursorY = st.setup.getPageHeight() - (Double) frameY;
                    }
                    st.cursorY = st.frameCursorY - spaceBefore;
                    boolean prevSup = st.suppressNewPage;
                    st.suppressNewPage = true; // a frame never paginates
                    drawInlineBlock(inlineText(p.getInline()), st, fx, fw, f.family,
                            f.size, f.bold, f.italic, f.color, bs,
                            forcedLineOf(b), lineRuleOf(b));
                    st.suppressNewPage = prevSup;
                    st.frameCursorY = st.cursorY;
                    // +spaceAfter: the common tail subtracts it — a frame must
                    // leave the flow cursor exactly where it was.
                    st.cursorY = savedY + spaceAfter;
                    break;
                }
                st.frameKey = null; // non-frame paragraph ends the frame run
                double pLinkTop = st.cursorY;
                Object tabStops = b.getAttributes().get("tab-stops");
                String raw = rawInlineText(p.getInline());
                boolean tabbed = false;
                if (tabStops instanceof List && raw.indexOf('\t') >= 0
                        && !raw.trim().isEmpty()) {
                    // Explicit tab stops (DOC/DOCX): each TAB jumps to the next
                    // stop — a label row spreads across its form boxes instead
                    // of gluing at the left edge. Only a line that FITS renders
                    // this way; long tabbed prose (a numbered note "1.<TAB>…")
                    // falls back to normal wrapping.
                    @SuppressWarnings("unchecked")
                    List<double[]> stops = (List<double[]>) tabStops;
                    // Word measures tab stops from the text COLUMN's left edge
                    // (x before the paragraph indent), not from the indented
                    // line start — an indented label row must not shift its
                    // stops. The first segment starts at indent + first-line.
                    double firstX = bx + (bs != null ? bs.getIndentFirstLine() : 0);
                    tabbed = drawTabbedLine(raw, stops, st, x, firstX,
                            width - indentEnd, f,
                            forcedLineOf(b), lineRuleOf(b));
                    if (!tabbed) {
                        // Tabbed PROSE (typewriter-style: author aligns each
                        // line with a hard break and/or a literal TAB): Word
                        // wraps at the tab/break and a leading TAB on the new
                        // line jumps to the first stop.
                        tabbed = drawTabbedProse(raw, stops, st, x, firstX, bx,
                                width - indentEnd, f,
                                forcedLineOf(b), lineRuleOf(b));
                    }
                }
                if (!tabbed) {
                    drawInlineBlock(inlineText(p.getInline()), st, bx, bw, f.family, f.size,
                            f.bold, f.italic, f.color, bs, forcedLineOf(b), lineRuleOf(b));
                }
                addLinkAnnotations(p.getInline(), st, bx, bw, pLinkTop);
                break;
            }
            case CONTAINER: {
                Container cont = (Container) b;
                if (Boolean.TRUE.equals(cont.getAttributes().get("column-layout"))
                        && layoutColumns(cont, st, bx, bw)) {
                    break;
                }
                layoutChildren(cont.getChildren(), st, bx, bw);
                break;
            }
            case QUOTE:
                // indent the quote block by 24pt
                for (SdmBlock c : ((Quote) b).getChildren()) {
                    layoutBlock(c, st, bx + 24, bw - 24, ((Quote) b).getChildren(),
                            ((Quote) b).getChildren().indexOf(c));
                }
                break;
            case LIST_BLOCK:
                layoutList((ListBlock) b, st, bx, bw);
                break;
            case CODE_BLOCK: {
                CodeBlock cb = (CodeBlock) b;
                for (String line : cb.getText().split("\n", -1)) {
                    ensureSpace(st, TextLayoutHelper.getLineHeight("Courier", DEFAULT_SIZE));
                    st.drawText(line, bx, st.cursorY - DEFAULT_SIZE, "Courier", DEFAULT_SIZE, 0);
                    st.cursorY -= TextLayoutHelper.getLineHeight("Courier", DEFAULT_SIZE);
                }
                break;
            }
            case THEMATIC_BREAK: {
                int hrBg = b.getStyle() != null ? b.getStyle().getBackground() : 0;
                if ((hrBg & 0xFFFFFF) != 0 || (hrBg >>> 24) != 0) {
                    // Styled rule (e.g. hr.blue_rule) → a solid coloured bar.
                    ensureSpace(st, 10);
                    st.cursorY -= 3;
                    st.fillRect(bx, st.cursorY - 4, bw, 4, hrBg);
                    st.cursorY -= 7;
                } else {
                    ensureSpace(st, 12);
                    st.cursorY -= 6;
                    st.drawHRule(bx, st.cursorY, bw);
                    st.cursorY -= 6;
                }
                break;
            }
            case TABLE:
                layoutTable((Table) b, st, bx, bw);
                break;
            case FIGURE:
                layoutFigure((Figure) b, st, bx, bw);
                break;
            case OPAQUE: {
                Opaque o = (Opaque) b;
                double h = 24;
                ensureSpace(st, h);
                st.drawPlaceholderBox(bx, st.cursorY - h, Math.min(bw, 120), h);
                st.report.recordOpaque(o.getRenderHint() == null ? "opaque" : o.getRenderHint());
                st.cursorY -= h;
                break;
            }
            case FORM_FIELD:
                layoutFormField((org.aspose.pdf.sdm.FormField) b, st, bx, bw);
                break;
            default:
                break;
        }
        // Bottom border (CSS border-bottom): a rule at the content bottom, before
        // the margin gap — e.g. the dotted line under a "More Text" heading div.
        if (bs != null && bs.getBorderBottomWidth() > 0
                && bs.getBorderBottomStyle() != null && !st.measuring) {
            st.drawBottomBorder(bx, st.cursorY, bw, bs.getBorderBottomWidth(),
                    bs.getBorderBottomColor(), bs.getBorderBottomStyle());
        }
        st.cursorY -= spaceAfter;
    }

    /** Ensures the heading and at least one following line fit; else new page. */
    private void keepHeadingWithNext(LayoutState st, double headingSize) {
        double need = TextLayoutHelper.getLineHeight(DEFAULT_FONT, headingSize)
                + TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_SIZE);
        if (!st.suppressNewPage
                && st.cursorY - need < st.setup.getContentBottom() && !st.pageIsEmpty()) {
            debugBreak("heading-keep", st, need);
            st.newPage();
        }
    }

    // ---- inline / paragraph ------------------------------------------------

    /**
     * Synthesises a {@code LinkAnnotation} + {@code GoToURIAction} for every
     * anchor inline of a just-drawn text block (BUG-059 on the SDM path). The
     * rectangle covers the block's drawn band on the CURRENT page — an
     * approximation (the flattened inline text does not track per-run x), but
     * it round-trips the URI and hit-tests the right area.
     */
    private void addLinkAnnotations(List<SdmInline> inlines, LayoutState st,
                                    double x, double width, double topY) {
        if (st.measuring || st.page == null) {
            return;
        }
        for (SdmInline in : inlines) {
            if (!(in instanceof LinkInline)) {
                continue;
            }
            String href = ((LinkInline) in).getHref();
            if (href == null || href.isEmpty()) {
                continue;
            }
            // The block may have paginated mid-way; clamp the band to this page.
            double top = Math.min(topY, st.setup.getContentTop());
            double bottom = Math.min(st.cursorY, top);
            try {
                org.aspose.pdf.Rectangle rect =
                        new org.aspose.pdf.Rectangle(x, bottom, x + width, top);
                org.aspose.pdf.annotations.LinkAnnotation link =
                        new org.aspose.pdf.annotations.LinkAnnotation(st.page, rect);
                link.setAction(new org.aspose.pdf.GoToURIAction(href));
                st.page.getAnnotations().add(link);
            } catch (Exception e) {
                st.report.recordOpaque("link:" + href + " (" + e.getMessage() + ")");
            }
        }
    }

    /** The block's forced line height in points ({@code line-height-pt}), or -1. */
    private static double forcedLineOf(SdmBlock b) {
        Object v = b.getAttributes().get("line-height-pt");
        return v instanceof Number ? ((Number) v).doubleValue() : -1;
    }

    /** The block's line rule ({@code exact}/{@code atLeast}), or null. */
    private static String lineRuleOf(SdmBlock b) {
        Object v = b.getAttributes().get("line-rule");
        return v instanceof String ? (String) v : null;
    }

    private void drawInlineBlock(String text, LayoutState st, double x, double width,
                                 String family, double size, boolean bold, boolean italic,
                                 int color, BlockStyle bs) {
        drawInlineBlock(text, st, x, width, family, size, bold, italic, color, bs, -1, null);
    }

    /** Wraps text to the content width (REUSE TextLayoutHelper.wrapText) and draws
     *  each line with alignment/indent, paginating line-by-line. A DOCX
     *  {@code lineRule="exact"} height REPLACES the natural line height (0 is a
     *  legal zero-advance overlay); {@code atLeast} only raises it. */
    private void drawInlineBlock(String text, LayoutState st, double x, double width,
                                 String family, double size, boolean bold, boolean italic,
                                 int color, BlockStyle bs, double forcedLine, String lineRule) {
        String font = st.resolveFont(family, bold, italic);
        double lineHeight = TextLayoutHelper.getLineHeight(font, size);
        double baselineDrop = size;
        if ("exact".equals(lineRule) && forcedLine >= 0) {
            lineHeight = forcedLine;
            // Word sets the baseline ~one descent above the exact box bottom;
            // matching it keeps converter-produced lines on their source y.
            baselineDrop = Math.min(size, Math.max(1, lineHeight - 0.20 * size));
        } else if ("atLeast".equals(lineRule) && forcedLine > lineHeight) {
            lineHeight = forcedLine;
        }
        double firstIndent = bs != null ? bs.getIndentFirstLine() : 0;
        BlockStyle.Align align = bs != null && bs.getAlign() != null ? bs.getAlign() : BlockStyle.Align.LEFT;
        // Only the FIRST line is narrowed by a first-line indent; continuation
        // lines get the full width. Wrapping everything at (width-firstIndent)
        // squeezed whole paragraphs into a sliver when a converter uses a huge
        // firstLine indent to park the first line far to the right.
        List<String> lines = null;
        // Converter fidelity: an exact-line paragraph IS one source line (the
        // converter emits a paragraph per printed line). If the whole text fits
        // within a small metric tolerance, keep it on one line — wrapping over
        // a 2% font-width difference inflates the page and cascades breaks.
        if ("exact".equals(lineRule) && forcedLine >= 0 && text != null
                && !text.isEmpty() && text.indexOf('\n') < 0) {
            double effWidth = width - (firstIndent > 0 ? firstIndent : 0);
            double tw = TextLayoutHelper.measureTextWidth(text, font, size);
            if (tw > effWidth && tw <= effWidth * 1.08) {
                lines = new ArrayList<>();
                lines.add(text);
            }
        }
        if (lines != null) {
            // one-line fidelity case resolved above
        } else if (firstIndent > 1 && text != null && !text.isEmpty()) {
            List<String> narrow = TextLayoutHelper.wrapText(text, font, size,
                    Math.max(20, width - firstIndent));
            if (narrow.size() > 1 && text.startsWith(narrow.get(0))) {
                String head = narrow.get(0);
                String rest = text.substring(head.length());
                if (rest.startsWith(" ")) {
                    rest = rest.substring(1);
                }
                lines = new ArrayList<>();
                lines.add(head);
                lines.addAll(TextLayoutHelper.wrapText(rest, font, size, width));
            } else {
                lines = narrow;
            }
        } else {
            lines = TextLayoutHelper.wrapText(text, font, size, width - firstIndent);
        }
        if (lines.isEmpty()) {
            lines = new ArrayList<>();
            lines.add("");
        }
        // Paint the block background (e.g. the green "Portfolio composition"
        // header bar) behind the text when it fits on the current page.
        int blockBg = bs != null ? bs.getBackground() : 0;
        if (((blockBg & 0xFFFFFF) != 0 || (blockBg >>> 24) != 0) && !lines.isEmpty()) {
            double h = lines.size() * lineHeight;
            if (st.cursorY - h >= st.setup.getContentBottom()) {
                st.fillRect(x, st.cursorY - h, width, h, blockBg);
            }
        }
        boolean first = true;
        for (String line : lines) {
            ensureSpace(st, lineHeight);
            double lineWidth = TextLayoutHelper.measureTextWidth(line, font, size);
            if (lineWidth > width) {
                st.report.recordOverflowToken(line.length() > 24 ? line.substring(0, 24) + "…" : line);
            }
            double lx = x + (first ? firstIndent : 0);
            switch (align) {
                case CENTER: lx = x + Math.max(0, (width - lineWidth) / 2); break;
                case RIGHT: lx = x + Math.max(0, width - lineWidth); break;
                default: break;
            }
            st.drawText(line, lx, st.cursorY - baselineDrop, font, size, color);
            st.cursorY -= lineHeight;
            first = false;
        }
    }

    private void layoutList(ListBlock lb, LayoutState st, double x, double width) {
        int n = lb.getStart() != null ? lb.getStart() : 1;
        for (ListItem li : lb.getItems()) {
            String marker = lb.isOrdered() ? (n + ".") : "•";
            double markerW = 18;
            ensureSpace(st, TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_SIZE));
            st.drawText(marker, x, st.cursorY - DEFAULT_SIZE, st.resolveFont(DEFAULT_FONT, false, false),
                    DEFAULT_SIZE, 0);
            double savedY = st.cursorY;
            for (SdmBlock c : li.getChildren()) {
                layoutBlock(c, st, x + markerW, width - markerW, li.getChildren(),
                        li.getChildren().indexOf(c));
            }
            if (st.cursorY == savedY) {
                st.cursorY -= TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_SIZE);
            }
            n++;
        }
    }

    // ---- tables ------------------------------------------------------------

    private void layoutTable(Table t, LayoutState st, double x, double width) {
        int colCount = 0;
        for (TableRow r : t.getRows()) {
            int c = 0;
            for (TableCell cell : r.getCells()) {
                c += cell.getColSpan();
            }
            colCount = Math.max(colCount, c);
        }
        if (colCount == 0) {
            return;
        }
        double[] colX = computeColumnBoundaries(t, colCount, x, width);
        // A "ruled" table (the source drew a visible grid: DOCX tblBorders /
        // TableGrid style, PDF ruled-grid detection) strokes its cell boxes;
        // everything else stays borderless like the HTML writer's default.
        boolean ruled = "ruled".equals(t.getAttributes().get("border"));
        int ruleColor = 0xFF9A9A9A;
        Object bc = t.getAttributes().get("border-color");
        if (bc instanceof String && ((String) bc).matches("#[0-9a-fA-F]{6}")) {
            ruleColor = 0xFF000000 | Integer.parseInt(((String) bc).substring(1), 16);
        }
        // Cell inset: DOCX w:tblCellMar (converter grids use 0 and expect the
        // full cell width); 3pt default matches the HTML path's historic look.
        double cellPad = 3;
        Object cp = t.getAttributes().get("cell-pad-pt");
        if (cp instanceof Number) {
            cellPad = ((Number) cp).doubleValue();
        }
        // header rows (repeated on each page the table spans)
        List<TableRow> headers = new ArrayList<>();
        for (TableRow r : t.getRows()) {
            if (r.getKind() == TableRow.Kind.HEADER) {
                headers.add(r);
            }
        }
        for (TableRow r : t.getRows()) {
            double rowH = effectiveRowHeight(r, colX, st, cellPad);
            if (!st.suppressNewPage
                    && st.cursorY - rowH < st.setup.getContentBottom() - BREAK_EPSILON
                    && !st.pageIsEmpty()) {
                debugBreak("table-row", st, rowH);
                st.newPage();
                for (TableRow h : headers) {
                    drawRow(h, st, colX, effectiveRowHeight(h, colX, st, cellPad),
                            ruled, ruleColor, cellPad);
                }
            }
            drawRow(r, st, colX, rowH, ruled, ruleColor, cellPad);
        }
    }

    /** Content-measured row height raised to the row's explicit minimum
     *  (DOCX {@code w:trHeight} — the source grid must not be compressed). */
    private double effectiveRowHeight(TableRow r, double[] colX, LayoutState st, double cellPad) {
        double h = rowHeight(r, colX, st, cellPad);
        double min = displayPt(r, "min-height-pt");
        return min > h ? min : h;
    }

    /** Maximum preferred width over every table anywhere in the block tree. */
    private double maxPreferredTableWidth(List<SdmBlock> blocks) {
        double max = 0;
        for (SdmBlock b : blocks) {
            if (b instanceof Table) {
                max = Math.max(max, preferredTableWidth((Table) b));
            }
            if (b instanceof Container) {
                max = Math.max(max, maxPreferredTableWidth(((Container) b).getChildren()));
            } else if (b instanceof Quote) {
                max = Math.max(max, maxPreferredTableWidth(((Quote) b).getChildren()));
            } else if (b instanceof ListBlock) {
                for (ListItem li : ((ListBlock) b).getItems()) {
                    max = Math.max(max, maxPreferredTableWidth(li.getChildren()));
                }
            } else if (b instanceof Table) {
                for (TableRow r : ((Table) b).getRows()) {
                    for (TableCell cell : r.getCells()) {
                        max = Math.max(max, maxPreferredTableWidth(cell.getChildren()));
                    }
                }
            }
        }
        return max;
    }

    /** A table's preferred (min-content-ish) width: each column at least fits its
     *  widest single-line cell; nowrap cells demand their full text width, other
     *  cells are capped (they can wrap). */
    private double preferredTableWidth(Table t) {
        int colCount = 0;
        for (TableRow r : t.getRows()) {
            int c = 0;
            for (TableCell cell : r.getCells()) {
                c += cell.getColSpan();
            }
            colCount = Math.max(colCount, c);
        }
        if (colCount == 0) {
            return 0;
        }
        double[] pref = new double[colCount];
        for (TableRow r : t.getRows()) {
            int col = 0;
            for (TableCell cell : r.getCells()) {
                if (cell.getColSpan() == 1 && col < colCount) {
                    RunFont f = blockFont(cellInlines(cell));
                    String font = f.family != null ? f.family : DEFAULT_FONT;
                    double size = f.size > 0 ? f.size : DEFAULT_SIZE;
                    String text = cellText(cell).replace('\n', ' ');
                    double tw = TextLayoutHelper.measureTextWidth(text, font, size) + 10;
                    if (!isNowrap(cell)) {
                        tw = Math.min(tw, PREFERRED_WRAP_CAP);
                    }
                    if (tw > pref[col]) {
                        pref[col] = tw;
                    }
                }
                col += cell.getColSpan();
            }
        }
        double sum = 0;
        for (double p : pref) {
            sum += p;
        }
        return sum;
    }

    private double[] computeColumnBoundaries(Table t, int colCount, double x, double width) {
        double[] w = new double[colCount];
        double assigned = 0;
        int autoCount = 0;
        List<org.aspose.pdf.sdm.ColumnSpec> cols = t.getColumns();
        for (int i = 0; i < colCount; i++) {
            if (i < cols.size() && cols.get(i).getWidthType() == org.aspose.pdf.sdm.ColumnSpec.WidthType.POINTS) {
                w[i] = cols.get(i).getWidth();
                assigned += w[i];
            } else if (i < cols.size() && cols.get(i).getWidthType() == org.aspose.pdf.sdm.ColumnSpec.WidthType.PERCENT) {
                w[i] = width * cols.get(i).getWidth() / 100.0;
                assigned += w[i];
            } else {
                w[i] = -1;
                autoCount++;
            }
        }
        // Per-cell authored widths (HTML width="6%"/CSS width) fill columns the
        // colgroup left unspecified — legacy label/value forms size every column
        // this way, not via <colgroup>.
        double[] cellW = perColumnCellWidths(t, colCount, width);
        for (int i = 0; i < colCount; i++) {
            if (w[i] < 0 && cellW[i] > 0) {
                w[i] = cellW[i];
                assigned += w[i];
                autoCount--;
            }
        }
        double remaining = Math.max(0, width - assigned);
        // Auto columns take their natural (fit-content) share of the remaining
        // width — proportional to the widest cell text in each — so a label
        // column is not squeezed to the same width as a two-digit number column
        // (CSS `width: fit-content`). Falls back to an equal split when content
        // cannot be measured.
        double[] natural = autoCount > 0 ? naturalColumnWidths(t, colCount, width) : null;
        double naturalSum = 0;
        if (natural != null) {
            for (int i = 0; i < colCount; i++) {
                if (w[i] < 0) naturalSum += natural[i];
            }
        }
        double each = autoCount > 0 ? remaining / autoCount : 0;
        double[] cw = new double[colCount];
        double total = 0;
        for (int i = 0; i < colCount; i++) {
            if (w[i] >= 0) {
                cw[i] = w[i];
            } else if (naturalSum > 1) {
                cw[i] = remaining * natural[i] / naturalSum;
            } else {
                cw[i] = each;
            }
            total += cw[i];
        }
        // A nowrap column must be at least as wide as its single-line text, else a
        // narrow authored width (e.g. a 6% label) would force the "no-wrap" label
        // to wrap anyway. Bump such columns up before the fit-to-band scaling.
        double[] nwMin = nowrapColumnMinWidths(t, colCount);
        for (int i = 0; i < colCount; i++) {
            if (nwMin[i] > cw[i]) {
                total += nwMin[i] - cw[i];
                cw[i] = nwMin[i];
            }
        }
        // Authored widths can sum past the band (percentages that total >100%, or
        // mixed %/px); scale everything to fit so columns don't run off the edge.
        if (total > width + 0.5 && total > 0) {
            double s = width / total;
            for (int i = 0; i < colCount; i++) {
                cw[i] *= s;
            }
        }
        double[] boundaries = new double[colCount + 1];
        boundaries[0] = x;
        for (int i = 0; i < colCount; i++) {
            boundaries[i + 1] = boundaries[i] + cw[i];
        }
        return boundaries;
    }

    /** Minimum width (points) each column needs so its nowrap cells stay on one
     *  line; 0 for columns with no nowrap cell. */
    private double[] nowrapColumnMinWidths(Table t, int colCount) {
        double[] out = new double[colCount];
        for (TableRow r : t.getRows()) {
            int col = 0;
            for (TableCell cell : r.getCells()) {
                if (cell.getColSpan() == 1 && col < colCount && isNowrap(cell)) {
                    RunFont f = blockFont(cellInlines(cell));
                    String font = f.family != null ? f.family : DEFAULT_FONT;
                    double size = f.size > 0 ? f.size : DEFAULT_SIZE;
                    // Bold text is wider than the (non-bold) width table used here;
                    // pad ~8% so a bold "no-wrap" label is not clipped/wrapped.
                    double tw = TextLayoutHelper.measureTextWidth(cellText(cell), font, size);
                    if (f.bold) {
                        tw *= 1.08;
                    }
                    tw += 8;
                    if (tw > out[col]) {
                        out[col] = tw;
                    }
                }
                col += cell.getColSpan();
            }
        }
        return out;
    }

    private static boolean isNowrap(TableCell cell) {
        return Boolean.TRUE.equals(cell.getAttributes().get("nowrap"));
    }

    /** Per-column authored width in points from single-column cells' {@code width-pct}/
     *  {@code width-pt} attributes (max across rows), or -1 where unspecified. */
    private double[] perColumnCellWidths(Table t, int colCount, double width) {
        double[] out = new double[colCount];
        java.util.Arrays.fill(out, -1);
        for (TableRow r : t.getRows()) {
            int col = 0;
            for (TableCell cell : r.getCells()) {
                if (cell.getColSpan() == 1 && col < colCount) {
                    double cwv = -1;
                    Object pct = cell.getAttributes().get("width-pct");
                    Object pt = cell.getAttributes().get("width-pt");
                    if (pct instanceof Number) {
                        cwv = width * ((Number) pct).doubleValue() / 100.0;
                    } else if (pt instanceof Number) {
                        cwv = ((Number) pt).doubleValue();
                    }
                    if (cwv > 0 && cwv > out[col]) {
                        out[col] = cwv;
                    }
                }
                col += cell.getColSpan();
            }
        }
        return out;
    }

    /** Widest single-column cell text per column (points), used to size auto
     *  columns proportionally to their content (fit-content). Cells spanning
     *  multiple columns are excluded so a full-width header does not dominate. */
    private double[] naturalColumnWidths(Table t, int colCount, double width) {
        double[] nat = new double[colCount];
        // Cap any single cell's contribution to half the band — a long label
        // meant to wrap ("Forward foreign currency exchange contracts, …") must
        // not claim its full single-line width and starve the numeric columns.
        double cap = width * 0.5;
        for (TableRow r : t.getRows()) {
            int col = 0;
            for (TableCell cell : r.getCells()) {
                if (cell.getColSpan() == 1 && col < colCount) {
                    RunFont f = blockFont(cellInlines(cell));
                    String font = f.family != null ? f.family : DEFAULT_FONT;
                    double size = f.size > 0 ? f.size : DEFAULT_SIZE;
                    double tw = Math.min(cap,
                            TextLayoutHelper.measureTextWidth(cellText(cell), font, size) + 6);
                    if (tw > nat[col]) nat[col] = tw;
                }
                col += cell.getColSpan();
            }
        }
        // A wholly empty auto column still needs a sliver of width.
        for (int i = 0; i < colCount; i++) {
            if (nat[i] <= 0) nat[i] = 12;
        }
        return nat;
    }

    private double rowHeight(TableRow r, double[] colX, LayoutState st, double cellPad) {
        // A row with an explicit source height (w:trHeight) must not collect
        // our invented paddings on top of its measured content — the explicit
        // minimum already encodes the source grid, and per-row padding drift
        // accumulates into spurious page breaks on exact-geometry documents.
        boolean explicitH = r.getAttributes().containsKey("min-height-pt");
        double flatPad = explicitH ? 0 : 2;
        double blockPad = explicitH ? 0 : 6;
        double max = explicitH ? 4
                : TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_SIZE) + 2;
        int col = 0;
        for (TableCell cell : r.getCells()) {
            double cw = colX[Math.min(colX.length - 1, col + cell.getColSpan())] - colX[Math.min(colX.length - 1, col)];
            // A cell carrying block-level structure (a list, nested divs, a
            // sub-table) is laid out with the full block machinery, so its height
            // is MEASURED by running that layout into a throwaway builder — the
            // flatten-to-one-string path below cannot express a bulleted list.
            if (cellHasBlocks(cell)) {
                max = Math.max(max, measureCellBlocks(cell, cw, st, cellPad) + blockPad);
                col += cell.getColSpan();
                continue;
            }
            RunFont f = blockFont(cellInlines(cell));
            // Resolve the font EXACTLY as drawRow does (bold for a TH or a
            // CSS-bold cell, plus italic). rowHeight and drawRow must measure the
            // wrapped line count with the same font metrics, otherwise a bold
            // header that wraps to two lines is sized for one (its background
            // rectangle then falls short and collides with the row below).
            boolean th = cell.getKind() == TableCell.Kind.TH;
            String font = st.resolveFont(f.family, f.bold || th, f.italic);
            double size = f.size > 0 ? f.size : DEFAULT_SIZE;
            int lines = isNowrap(cell)
                    ? Math.max(1, cellText(cell).split("\n", -1).length)
                    : Math.max(1, TextLayoutHelper.wrapText(cellText(cell), font, size, cw - 6).size());
            max = Math.max(max, lines * TextLayoutHelper.getLineHeight(font, size) + flatPad);
            col += cell.getColSpan();
        }
        return max;
    }

    /** All inline content of a cell (across its paragraph/heading children). */
    private static List<SdmInline> cellInlines(TableCell cell) {
        List<SdmInline> out = new ArrayList<>();
        for (SdmBlock b : cell.getChildren()) {
            if (b instanceof Paragraph) {
                out.addAll(((Paragraph) b).getInline());
            } else if (b instanceof Heading) {
                out.addAll(((Heading) b).getInline());
            }
        }
        return out;
    }

    private void drawRow(TableRow r, LayoutState st, double[] colX, double rowH,
                         boolean ruled, int ruleColor, double cellPad) {
        double top = st.cursorY;
        int col = 0;
        for (TableCell cell : r.getCells()) {
            int startCol = Math.min(colX.length - 1, col);
            int endCol = Math.min(colX.length - 1, col + cell.getColSpan());
            double cx = colX[startCol];
            double cw = colX[endCol] - cx;
            double ch = rowH * cell.getRowSpan();
            // Paint the cell background (e.g. green column headers) before the
            // border and text so the tint sits behind the content.
            int cellBg = cell.getStyle() != null ? cell.getStyle().getBackground() : 0;
            if ((cellBg & 0xFFFFFF) != 0 || (cellBg >>> 24) != 0) {
                st.fillRect(cx, top - ch, cw, ch, cellBg);
            }
            if (ruled) {
                st.strokeRect(cx, top - ch, cw, ch, ruleColor);
            }
            // A cell with block-level content (a list, nested divs) is laid out
            // with the block machinery inside its box so a bulleted list renders
            // as stacked bullets — not flattened onto one line. Page breaks are
            // suppressed: the cell is a single unit sized by rowHeight().
            if (cellHasBlocks(cell)) {
                boolean savedSuppress = st.suppressNewPage;
                double savedCursor = st.cursorY;
                st.suppressNewPage = true;
                // Explicit-height rows keep the source geometry: no invented inset.
                st.cursorY = top - (r.getAttributes().containsKey("min-height-pt") ? 0 : 3);
                layoutChildren(cell.getChildren(), st, cx + cellPad, cw - 2 * cellPad);
                st.suppressNewPage = savedSuppress;
                st.cursorY = savedCursor;
                col += cell.getColSpan();
                continue;
            }
            // HTML tables are borderless unless CSS asks otherwise; a full grid on
            // every cell reads as a spreadsheet and looks nothing like the source.
            // Honour the CSS the reader recognised on the cell content: colour,
            // weight, style, family, size (a TH is bold unless CSS overrode it),
            // plus the cell's text-align.
            boolean th = cell.getKind() == TableCell.Kind.TH;
            RunFont f = blockFont(cellInlines(cell));
            String font = st.resolveFont(f.family, f.bold || th, f.italic);
            double size = f.size > 0 ? f.size : DEFAULT_SIZE;
            int color = f.color;
            BlockStyle.Align align = cell.getStyle() != null && cell.getStyle().getAlign() != null
                    ? cell.getStyle().getAlign() : BlockStyle.Align.LEFT;
            List<String> lines = isNowrap(cell)
                    ? java.util.Arrays.asList(cellText(cell).split("\n", -1))
                    : TextLayoutHelper.wrapText(cellText(cell), font, size, cw - 6);
            double ty = top - size - 2;
            for (String line : lines) {
                double lineWidth = TextLayoutHelper.measureTextWidth(line, font, size);
                double lx;
                switch (align) {
                    case RIGHT: lx = cx + Math.max(3, cw - 3 - lineWidth); break;
                    case CENTER: lx = cx + Math.max(3, (cw - lineWidth) / 2); break;
                    default: lx = cx + 3; break;
                }
                st.drawText(line, lx, ty, font, size, color);
                ty -= TextLayoutHelper.getLineHeight(font, size);
            }
            col += cell.getColSpan();
        }
        st.cursorY = top - rowH;
    }

    // ---- figures -----------------------------------------------------------

    private void layoutFigure(Figure fig, LayoutState st, double x, double width) {
        // A PICTURE shape (legacy .doc Office Art anchor with a blip): painted
        // at its absolute SPA rectangle on the anchor's page, on the BACKGROUND
        // layer (beneath text), without consuming flow space — Word semantics
        // for floating pictures.
        if (Boolean.TRUE.equals(fig.getAttributes().get("shape-image"))) {
            if (!st.measuring && st.page != null) {
                org.aspose.pdf.sdm.Resource sres = fig.getImage() != null && st.resources != null
                        ? st.resources.get(fig.getImage()) : null;
                if (sres != null && sres.getBytes() != null && sres.getBytes().length > 0) {
                    try {
                        PdfStream xobj = org.aspose.pdf.XImage.createImageStream(sres.getBytes());
                        double w = displayPt(fig, "display-width");
                        double h = displayPt(fig, "display-height");
                        double px = displayPt(fig, "pos-x-pt");
                        double py = displayPt(fig, "pos-y-pt");
                        double y = st.setup.getPageHeight() - py - h;
                        // Register the XObject on the MAIN builder (the merged
                        // page /Resources come from it), but emit the paint ops
                        // into the background stream so text stays on top.
                        String name = st.builder.registerImage(
                                "shapeimg:" + System.identityHashCode(fig), xobj);
                        ContentStreamBuilder tgt = st.background != null ? st.background : st.builder;
                        tgt.saveState();
                        tgt.concatMatrix(w, 0, 0, h, px, y);
                        tgt.drawXObject(name);
                        tgt.restoreState();
                        st.drewOnPage = true;
                    } catch (Exception e) {
                        st.report.recordMissingResource(fig.getImage()
                                + " (" + e.getMessage() + ")");
                    }
                }
            }
            return;
        }
        // A shape OUTLINE (legacy .doc Office Art anchor: a form's fill-in box,
        // a separator line) paints at its absolute page position without
        // consuming flow space — the anchor decides which page it lands on.
        if (Boolean.TRUE.equals(fig.getAttributes().get("shape-outline"))) {
            if (!st.measuring && st.page != null) {
                double w = displayPt(fig, "display-width");
                double h = displayPt(fig, "display-height");
                double px = displayPt(fig, "pos-x-pt");
                double py = displayPt(fig, "pos-y-pt");
                double y = st.setup.getPageHeight() - py - h;
                Object fill = fig.getAttributes().get("fill-argb");
                // Shapes paint on the page's BACKGROUND layer: their anchors
                // trail the text, but visually they sit beneath it (a grey
                // band must not cover its labels).
                ContentStreamBuilder saved = st.builder;
                if (st.background != null) {
                    st.builder = st.background;
                }
                if (fill instanceof Number) {
                    st.fillRect(px, y, w, h, ((Number) fill).intValue());
                }
                st.strokeRect(px, y, w, h, 0xFF7A7A7A);
                st.builder = saved;
                // A plain small unfilled box on a legacy-form page is a
                // write-in area — promote it to a real fillable field.
                if (fill == null && w >= 8 && h >= 7 && h <= 40) {
                    st.addBoxField(px, y, w, h);
                }
            }
            return;
        }
        // Resolve the block image from the SDM ResourceTable; embed it at its
        // intrinsic pixel size (px = pt, per the PART-1 convention), scaled down
        // to the content width when wider. A missing/unreadable resource falls
        // back to a placeholder box and is recorded (never silently dropped).
        org.aspose.pdf.sdm.Resource res = fig.getImage() != null && st.resources != null
                ? st.resources.get(fig.getImage()) : null;
        // A watermark / page-frame backdrop is painted OUT OF FLOW: centred on the
        // current page, faded, and — crucially — WITHOUT advancing the cursor. In
        // flow it would consume a whole page of vertical space, so every source
        // page split into a blank watermark page + a text page (28 → 56 pages).
        if (Boolean.TRUE.equals(fig.getAttributes().get("background"))) {
            layoutBackdrop(fig, res, st);
            return;
        }
        // Prefer the recorded on-page footprint (display-width/height in points)
        // over the raster's intrinsic pixels: the pixel size is the scan
        // resolution, unrelated to the document size, and using it explodes
        // pagination when the two diverge.
        double targetW = displayPt(fig, "display-width");
        double targetH = displayPt(fig, "display-height");
        boolean placed = false;
        if (res != null && res.getKind() == org.aspose.pdf.sdm.Resource.Kind.IMAGE
                && res.getBytes() != null && res.getBytes().length > 0) {
            try {
                PdfStream xobj = org.aspose.pdf.XImage.createImageStream(res.getBytes());
                double iw = xobj.getInt("Width", 0);
                double ih = xobj.getInt("Height", 0);
                double dw = targetW > 0 ? targetW : iw;
                double dh = targetH > 0 ? targetH : ih;
                if (dw > 0 && dh > 0) {
                    if (dw > width) {              // fit to content width, keep aspect
                        double s = width / dw;
                        dw *= s;
                        dh *= s;
                    }
                    double maxH = st.setup.getContentHeight();
                    if (dh > maxH) {              // never taller than one page
                        double s = maxH / dh;
                        dw *= s;
                        dh *= s;
                    }
                    ensureSpace(st, dh);
                    st.drawImage(fig.getImage().getId(), xobj, x, st.cursorY - dh, dw, dh);
                    st.cursorY -= dh;
                    placed = true;
                }
            } catch (Exception e) {
                st.report.recordMissingResource(fig.getImage().getId()
                        + " (undecodable image: " + e.getMessage() + ")");
            }
        }
        if (!placed) {
            double boxW = Math.min(width, 200);
            double boxH = 120;
            ensureSpace(st, boxH);
            st.drawPlaceholderBox(x, st.cursorY - boxH, boxW, boxH);
            String id = fig.getImage() != null ? fig.getImage().getId()
                    : (fig.getAlt() != null ? fig.getAlt() : "figure");
            st.report.recordMissingResource(id + " (image placeholder)");
            st.cursorY -= boxH;
        }
        List<SdmBlock> cap = fig.getCaption();
        for (SdmBlock c : cap) {
            layoutBlock(c, st, x, width, cap, cap.indexOf(c));
        }
    }

    /**
     * Places a {@link org.aspose.pdf.sdm.FormField} as a REAL AcroForm field on
     * the current page (not a placeholder box), so an HTML form survives
     * HTML&rarr;SDM&rarr;PDF as a fillable PDF form. Sizes come from the recorded
     * on-page footprint (display-width/height), clamped to the content width.
     */
    private void layoutFormField(org.aspose.pdf.sdm.FormField f, LayoutState st,
                                 double x, double width) {
        boolean checkable = f.getKind() == org.aspose.pdf.sdm.FormField.Kind.CHECKBOX
                || f.getKind() == org.aspose.pdf.sdm.FormField.Kind.RADIO;
        double defW = checkable ? 12 : 120;
        double defH = checkable ? 12 : (f.isMultiline() ? 48 : 16);
        double w = displayPt(f, "display-width");
        double h = displayPt(f, "display-height");
        if (w <= 0) {
            w = defW;
        }
        if (h <= 0) {
            h = defH;
        }
        w = Math.min(w, width);
        h = Math.min(h, st.setup.getContentHeight());
        ensureSpace(st, h);
        // Height-measurement pass: only advance the cursor, never create a real
        // AcroForm field (that would leak phantom widgets onto the page).
        if (st.measuring) {
            st.cursorY -= h;
            return;
        }
        org.aspose.pdf.Rectangle rect = new org.aspose.pdf.Rectangle(
                x, st.cursorY - h, x + w, st.cursorY);
        try {
            org.aspose.pdf.forms.Field field;
            switch (f.getKind()) {
                case CHECKBOX:
                case RADIO: {
                    // A lone HTML radio has no group context here; a checkbox
                    // widget with the same export value is the closest fillable
                    // equivalent.
                    org.aspose.pdf.forms.CheckboxField cb =
                            new org.aspose.pdf.forms.CheckboxField(st.page, rect);
                    if (f.isChecked()) {
                        cb.setChecked(true);
                    }
                    field = cb;
                    break;
                }
                case COMBOBOX: {
                    org.aspose.pdf.forms.ComboBoxField combo =
                            new org.aspose.pdf.forms.ComboBoxField(st.page, rect);
                    for (String opt : f.getOptions()) {
                        combo.addOption(opt == null ? "" : opt);
                    }
                    if (f.getValue() != null) {
                        combo.setSelected(f.getValue());
                    }
                    field = combo;
                    break;
                }
                case LISTBOX: {
                    org.aspose.pdf.forms.ListBoxField list =
                            new org.aspose.pdf.forms.ListBoxField(st.page, rect);
                    for (String opt : f.getOptions()) {
                        list.addOption(opt == null ? "" : opt);
                    }
                    if (f.getValue() != null) {
                        list.setSelected(f.getValue());
                    }
                    field = list;
                    break;
                }
                case BUTTON: {
                    field = new org.aspose.pdf.forms.ButtonField(st.page, rect);
                    if (f.getValue() != null) {
                        field.setValue(f.getValue());
                    }
                    break;
                }
                default: { // TEXT / SIGNATURE — a signature renders as a text box
                    org.aspose.pdf.forms.TextBoxField tb =
                            new org.aspose.pdf.forms.TextBoxField(st.page, rect);
                    tb.setMultiline(f.isMultiline());
                    if (f.getMaxLen() != null) {
                        tb.setMaxLen(f.getMaxLen());
                    }
                    if (f.getValue() != null) {
                        tb.setValue(f.getValue());
                    }
                    field = tb;
                }
            }
            if (f.getName() != null && !f.getName().isEmpty()) {
                field.setPartialName(f.getName().replace('.', '_'));
            }
            st.doc.getForm().add(field);
        } catch (Exception e) {
            // Field creation must never sink the layout — degrade to the box.
            st.drawPlaceholderBox(x, st.cursorY - h, w, h);
            st.report.recordOpaque("form-field:" + f.getKind() + " (" + e.getMessage() + ")");
        }
        st.cursorY -= h;
    }

    /** Opacity applied to a watermark/backdrop figure ({@code -Dsdm.backdropAlpha} overrides). */
    private static final double BACKDROP_ALPHA = backdropAlpha();

    private static double backdropAlpha() {
        try {
            double a = Double.parseDouble(System.getProperty("sdm.backdropAlpha", "0.5"));
            return Math.max(0.0, Math.min(1.0, a));
        } catch (NumberFormatException e) {
            return 0.5;
        }
    }

    /**
     * Paints a {@code background} figure as a faded, centred backdrop on the
     * current page without consuming vertical flow space. If the current page is
     * still empty a page is started first so the backdrop lands under the text
     * that follows rather than on a leftover previous page.
     */
    private void layoutBackdrop(Figure fig, org.aspose.pdf.sdm.Resource res, LayoutState st) {
        if (res == null || res.getKind() != org.aspose.pdf.sdm.Resource.Kind.IMAGE
                || res.getBytes() == null || res.getBytes().length == 0) {
            return; // unresolved backdrop — silently skip (decorative only)
        }
        if (st.page == null) {
            st.newPage();
        }
        try {
            PdfStream xobj = org.aspose.pdf.XImage.createImageStream(res.getBytes());
            double iw = xobj.getInt("Width", 0);
            double ih = xobj.getInt("Height", 0);
            double dw = displayPt(fig, "display-width");
            double dh = displayPt(fig, "display-height");
            if (dw <= 0) dw = iw;
            if (dh <= 0) dh = ih;
            if (dw <= 0 || dh <= 0) {
                return;
            }
            double pageW = st.setup.getPageWidth();
            double pageH = st.setup.getPageHeight();
            double bx;
            double by;
            if (Boolean.TRUE.equals(fig.getAttributes().get("pos-page-anchored"))) {
                // Page-anchored VML shape: paint at its recorded offset. Word
                // measures the offset from the text-margin origin (empirically —
                // a "-5.75pt / -7.7pt" backdrop lands just outside the margins,
                // not off the page edge), so the margins are added back here.
                bx = st.setup.getMarginLeft() + displayPt(fig, "pos-x-pt");
                by = pageH - st.setup.getMarginTop() - displayPt(fig, "pos-y-pt") - dh;
            } else {
                // Clamp to the page while keeping aspect, then centre.
                double s = Math.min(1.0, Math.min(pageW / dw, pageH / dh));
                dw *= s;
                dh *= s;
                bx = (pageW - dw) / 2.0;
                by = (pageH - dh) / 2.0;
            }
            // A page-background SCAN (absolutely positioned behind the text
            // layer, e.g. a VML z-index<0 shape) must paint at full opacity —
            // the watermark fade is only for decorative overlays.
            Object op = fig.getAttributes().get("background-opacity");
            double alpha = op instanceof Number ? ((Number) op).doubleValue() : BACKDROP_ALPHA;
            st.drawBackdropImage(fig.getImage().getId(), xobj, bx, by, dw, dh, alpha);
        } catch (Exception e) {
            st.report.recordMissingResource(fig.getImage().getId()
                    + " (undecodable backdrop: " + e.getMessage() + ")");
        }
    }

    /** Reads a numeric figure attribute (on-page footprint in points), or 0. */
    private static double displayPt(org.aspose.pdf.sdm.SdmNode node, String key) {
        Object v = node.getAttributes().get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0.0;
    }

    /**
     * Lays out a list of block children, transparently unwrapping pass-through
     * wrapper containers and honouring a CSS {@code float} on a narrow child: the
     * floated block becomes a column and the following siblings flow beside it in
     * the remaining width (the common "float:left box + wrapping text" idiom).
     */
    private void layoutChildren(List<SdmBlock> children, LayoutState st, double x, double width) {
        List<SdmBlock> eff = flattenTransparent(children);
        for (int i = 0; i < eff.size(); i++) {
            SdmBlock b = eff.get(i);
            String flt = floatOf(b);
            Double wpct = floatWidthPct(b);
            if (flt != null && wpct != null && wpct > 0 && wpct < 100 && i + 1 < eff.size()) {
                double gap = 8;
                double floatW = width * wpct / 100.0;
                double restW = width - floatW - gap;
                if (restW >= 40) {
                    boolean left = "left".equalsIgnoreCase(flt);
                    double floatX = left ? x : x + restW + gap;
                    double restX = left ? x + floatW + gap : x;
                    double top = st.cursorY;
                    boolean savedSuppress = st.suppressNewPage;
                    st.suppressNewPage = true;
                    st.cursorY = top;
                    layoutBlock(b, st, floatX, floatW, eff, i);
                    double floatBottom = st.cursorY;
                    st.cursorY = top;
                    for (int j = i + 1; j < eff.size(); j++) {
                        layoutBlock(eff.get(j), st, restX, restW, eff, j);
                    }
                    double restBottom = st.cursorY;
                    st.suppressNewPage = savedSuppress;
                    st.cursorY = Math.min(floatBottom, restBottom);
                    return; // the band consumed this float and all following siblings
                }
            }
            layoutBlock(b, st, x, width, eff, i);
        }
    }

    /** Splices pass-through wrapper containers (no style, no attributes — e.g. a
     *  nested {@code <html>}/{@code <div width:100%>}) so a floated grandchild
     *  becomes a direct sibling of the content that should flow beside it. */
    private static List<SdmBlock> flattenTransparent(List<SdmBlock> children) {
        List<SdmBlock> out = new ArrayList<>();
        for (SdmBlock b : children) {
            if (b instanceof Container && b.getStyle() == null
                    && ((Container) b).getAttributes().isEmpty()) {
                out.addAll(flattenTransparent(((Container) b).getChildren()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static String floatOf(SdmBlock b) {
        if (b instanceof Container) {
            Object f = ((Container) b).getAttributes().get("float");
            return f instanceof String ? (String) f : null;
        }
        return null;
    }

    private static Double floatWidthPct(SdmBlock b) {
        if (b instanceof Container) {
            Object w = ((Container) b).getAttributes().get("float-width-pct");
            if (w instanceof Number) {
                return ((Number) w).doubleValue();
            }
        }
        return null;
    }

    // ---- helpers -----------------------------------------------------------

    /** Sub-point slack before breaking: a source page whose exact line heights
     *  sum to precisely the content height must not spill a one-line tail page
     *  over float noise. */
    private static final double BREAK_EPSILON = 0.75;

    /** Diagnostic page-break tracing ({@code -Dsdm.layout.debug=1}). */
    private static final boolean DEBUG_BREAKS = System.getProperty("sdm.layout.debug") != null;

    private static void debugBreak(String why, LayoutState st, double need) {
        if (DEBUG_BREAKS) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[SDMBRK] page=%d why=%s cursorY=%.1f need=%.1f bottom=%.1f",
                    st.pages.getCount(), why, st.cursorY, need,
                    st.setup.getContentBottom()));
        }
    }

    private void ensureSpace(LayoutState st, double height) {
        if (!st.suppressNewPage
                && st.cursorY - height < st.setup.getContentBottom() - BREAK_EPSILON
                && !st.pageIsEmpty()) {
            debugBreak("ensure-space", st, height);
            st.newPage();
        }
    }

    /**
     * Lays a flex/table container's direct children out as parallel columns of
     * equal width, then advances the cursor past the tallest column. Returns
     * {@code false} (caller falls back to normal vertical stacking) when there
     * are fewer than two columns or the columns would be too narrow. Page breaks
     * are suppressed inside the columns — the side-by-side band is treated as a
     * single-page unit (these wrappers hold short allocation tables).
     */
    private boolean layoutColumns(Container cont, LayoutState st, double x, double width) {
        List<SdmBlock> cols = cont.getChildren();
        if (cols.size() < 2) {
            return false;
        }
        int n = cols.size();
        double gap = 12;
        double colW = (width - gap * (n - 1)) / n;
        // Explicit column geometry (DOC sprmSDxaColWidth/-Spacing): Word forms
        // use UNEQUAL columns — an even split lands the second column ~90pt
        // off. Trust the authored widths when they fit the content box.
        double[] colXs = new double[n];
        double[] colWs = new double[n];
        Object wsAttr = cont.getAttributes().get("column-widths-pt");
        Object gapAttr = cont.getAttributes().get("column-gap-pt");
        boolean explicit = false;
        if (wsAttr instanceof double[] && ((double[]) wsAttr).length >= n) {
            double[] ws = (double[]) wsAttr;
            double g = gapAttr instanceof Double ? (Double) gapAttr : gap;
            double cx = x;
            double sum = 0;
            explicit = true;
            for (int i = 0; i < n; i++) {
                if (ws[i] < 30) {
                    explicit = false;
                    break;
                }
                colXs[i] = cx;
                colWs[i] = ws[i];
                cx += ws[i] + g;
                sum += ws[i] + (i < n - 1 ? g : 0);
            }
            if (explicit && sum > width * 1.05 + 4) {
                explicit = false; // authored widths overflow the box — fall back
            }
        }
        if (!explicit) {
            if (colW < 60) {
                return false; // too narrow to be a meaningful multi-column split
            }
            for (int i = 0; i < n; i++) {
                colXs[i] = x + i * (colW + gap);
                colWs[i] = colW;
            }
        }
        double topY = st.cursorY;
        double minEndY = topY;
        boolean prevSuppress = st.suppressNewPage;
        st.suppressNewPage = true;
        for (int i = 0; i < n; i++) {
            st.cursorY = topY;
            layoutBlock(cols.get(i), st, colXs[i], colWs[i], cols, i);
            minEndY = Math.min(minEndY, st.cursorY);
        }
        // Optional vertical rules between columns (div.sideBySide.withRules).
        if (Boolean.TRUE.equals(cont.getAttributes().get("column-rules"))) {
            for (int i = 0; i < n - 1; i++) {
                double rx = colXs[i] + colWs[i] + gap / 2;
                st.drawVRule(rx, topY, minEndY);
            }
        }
        st.suppressNewPage = prevSuppress;
        st.cursorY = minEndY;
        return true;
    }

    /**
     * Draws ONE line whose TAB characters jump to explicit tab stops (positions
     * in points from {@code originX} — the text column's left edge, Word's tab
     * origin; align 0=left, 1=center, 2=right). The first segment starts at
     * {@code firstX} (paragraph indent + first-line indent). Word semantics:
     * each tab advances to the first stop beyond the cursor.
     */
    private boolean drawTabbedLine(String text, List<double[]> stops, LayoutState st,
                                   double originX, double firstX, double width, RunFont f,
                                   double forcedLine, String lineRule) {
        if (text.indexOf('\n') >= 0) {
            return false; // multi-line content — let the wrapping path handle it
        }
        String font = st.resolveFont(f.family, f.bold, f.italic);
        double size = f.size > 0 ? f.size : DEFAULT_SIZE;
        // DRY RUN: compute each segment's x; when the line would overflow the
        // block width this is tabbed PROSE, not a label row — report false so
        // the caller wraps it normally.
        String[] segs = text.split("\t", -1);
        double[] sxs = new double[segs.length];
        double cx = firstX;
        for (int i = 0; i < segs.length; i++) {
            double w = TextLayoutHelper.measureTextWidth(segs[i], font, size);
            double sx = i == 0 ? firstX : cx + 6;
            if (i > 0) {
                for (double[] stop : stops) {
                    double stopX = originX + stop[0];
                    if (stopX > cx + 1) {
                        sx = stop[1] == 2 ? stopX - w
                                : stop[1] == 1 ? stopX - w / 2 : stopX;
                        break;
                    }
                }
                if (sx < cx + 2) {
                    sx = cx + 2;
                }
            }
            sxs[i] = sx;
            cx = Math.max(cx, sx + w);
        }
        // A small metric overflow (substituted font slightly wider than the
        // authored one) must not collapse a label row into wrapped prose —
        // tolerate ~6% overrun; only genuinely long content falls back.
        if (cx > originX + width * 1.06 + 2) {
            return false;
        }
        double lineHeight = TextLayoutHelper.getLineHeight(font, size);
        double baselineDrop = size;
        if ("exact".equals(lineRule) && forcedLine >= 0) {
            lineHeight = forcedLine;
            baselineDrop = Math.min(size, Math.max(1, lineHeight - 0.20 * size));
        } else if ("atLeast".equals(lineRule) && forcedLine > lineHeight) {
            lineHeight = forcedLine;
        }
        ensureSpace(st, lineHeight);
        for (int i = 0; i < segs.length; i++) {
            if (!segs[i].isEmpty()) {
                st.drawText(segs[i], sxs[i], st.cursorY - baselineDrop, font, size, f.color);
            }
        }
        st.cursorY -= lineHeight;
        return true;
    }

    /**
     * Multi-line tabbed prose (Word tab-wrap semantics). Segments flow left to
     * right, each TAB jumping to the next stop; a segment that does not fit on
     * the current line wraps, and the pending TAB then jumps from the line
     * start ({@code leftX}) to the first stop — typewriter-aligned paragraphs
     * ("1.<TAB>line<TAB>line…") reproduce Word's per-line indenting. A segment
     * longer than a line wraps internally with continuation at {@code leftX}.
     */
    private boolean drawTabbedProse(String text, List<double[]> stops, LayoutState st,
                                    double originX, double firstX, double leftX,
                                    double width, RunFont f,
                                    double forcedLine, String lineRule) {
        String font = st.resolveFont(f.family, f.bold, f.italic);
        double size = f.size > 0 ? f.size : DEFAULT_SIZE;
        double lineHeight = TextLayoutHelper.getLineHeight(font, size);
        double baselineDrop = size;
        if ("exact".equals(lineRule) && forcedLine >= 0) {
            lineHeight = forcedLine;
            baselineDrop = Math.min(size, Math.max(1, lineHeight - 0.20 * size));
        } else if ("atLeast".equals(lineRule) && forcedLine > lineHeight) {
            lineHeight = forcedLine;
        }
        double lineEnd = originX + width * 1.06 + 2;
        double cx = firstX;
        boolean lineOpen = false;
        String[] hardLines = text.split("\n", -1);
        for (int h = 0; h < hardLines.length; h++) {
        if (h > 0) {
            // hard break (0x0B): close the current line, restart at the indent
            st.cursorY -= lineHeight;
            lineOpen = false;
            cx = leftX;
        }
        String[] segs = hardLines[h].split("\t", -1);
        for (int i = 0; i < segs.length; i++) {
            String seg = segs[i];
            double sx = cx;
            if (i > 0) {
                sx = nextStop(stops, originX, cx);
                double w = TextLayoutHelper.measureTextWidth(seg, font, size);
                if (sx + w > lineEnd && lineOpen) {
                    // wrap: the pending TAB jumps from the fresh line's start
                    st.cursorY -= lineHeight;
                    lineOpen = false;
                    sx = nextStop(stops, originX, leftX);
                }
            }
            if (seg.isEmpty()) {
                cx = Math.max(cx, sx);
                continue;
            }
            // The segment itself may exceed a full line: draw the fitting head
            // at the tab position, then wrap the rest at the paragraph's left
            // indent (same head/rest split drawInlineBlock uses).
            List<String> headWrap = TextLayoutHelper.wrapText(seg, font, size,
                    Math.max(20, originX + width - sx));
            String head = headWrap.isEmpty() ? seg : headWrap.get(0);
            if (!lineOpen) {
                ensureSpace(st, lineHeight);
                lineOpen = true;
            }
            st.drawText(head, sx, st.cursorY - baselineDrop, font, size, f.color);
            cx = sx + TextLayoutHelper.measureTextWidth(head, font, size);
            if (headWrap.size() > 1 && seg.startsWith(head)) {
                String rest = seg.substring(head.length());
                if (rest.startsWith(" ")) {
                    rest = rest.substring(1);
                }
                for (String ln : TextLayoutHelper.wrapText(rest, font, size,
                        Math.max(20, originX + width - leftX))) {
                    st.cursorY -= lineHeight;
                    ensureSpace(st, lineHeight);
                    st.drawText(ln, leftX, st.cursorY - baselineDrop, font, size, f.color);
                    cx = leftX + TextLayoutHelper.measureTextWidth(ln, font, size);
                }
            }
        }
        }
        st.cursorY -= lineHeight;
        return true;
    }

    /** First stop beyond {@code cx} (honouring right/center alignment is the
     *  caller's concern for label rows; prose stops are left stops), or a
     *  small fixed gap when the stops are exhausted. */
    private static double nextStop(List<double[]> stops, double originX, double cx) {
        for (double[] stop : stops) {
            double stopX = originX + stop[0];
            if (stopX > cx + 1) {
                return stopX;
            }
        }
        return cx + 6;
    }

    /** Inline text with TABs preserved (for the tabbed-line path). */
    private static String rawInlineText(List<SdmInline> inlines) {
        StringBuilder sb = new StringBuilder();
        collectInlineText(inlines, sb);
        return sb.toString();
    }

    private static String inlineText(List<SdmInline> inlines) {
        StringBuilder sb = new StringBuilder();
        collectInlineText(inlines, sb);
        // TAB has no glyph in the std-14 set (paints '?'); body text renders it
        // as a wide gap. (Furniture lines keep the TAB — it drives alignment.)
        return sb.toString().replace("\t", "   ");
    }

    private static void collectInlineText(List<SdmInline> inlines, StringBuilder sb) {
        for (SdmInline in : inlines) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            } else if (in instanceof LinkInline) {
                collectInlineText(((LinkInline) in).getChildren(), sb);
            } else if (in.getType() == org.aspose.pdf.sdm.SdmNodeType.LINE_BREAK) {
                sb.append('\n');
            }
        }
    }

    /** True when the cell carries block-level structure (a list, nested divs, a
     *  sub-table, a figure) that the flatten-to-one-string path cannot render —
     *  or paragraphs with explicit DOCX geometry (exact line heights, spacing,
     *  indents), which only the real block layout reproduces. */
    private static boolean cellHasBlocks(TableCell cell) {
        for (SdmBlock b : cell.getChildren()) {
            switch (b.getType()) {
                case LIST_BLOCK:
                case CONTAINER:
                case TABLE:
                case FIGURE:
                case QUOTE:
                case CODE_BLOCK:
                    return true;
                default:
                    break;
            }
            if (b.getAttributes().containsKey("line-height-pt")) {
                return true;
            }
            // A blank-line paragraph inside a cell is invisible to the
            // flatten-to-one-string path (it contributes no text) but must
            // still advance a line — only the block path renders it.
            if (b instanceof Paragraph && ((Paragraph) b).getInline().isEmpty()) {
                return true;
            }
            BlockStyle bs = b.getStyle();
            if (bs != null && (bs.getSpaceBefore() > 0 || bs.getSpaceAfter() > 0
                    || bs.getIndentStart() != 0)) {
                return true;
            }
        }
        return false;
    }

    /** Measures the height a cell's block children consume at width {@code cw} by
     *  running the real block layout into a throwaway builder. Used by rowHeight
     *  so the row is tall enough for the bulleted list drawn in drawRow. */
    private double measureCellBlocks(TableCell cell, double cw, LayoutState st, double cellPad) {
        ContentStreamBuilder savedBuilder = st.builder;
        double savedCursor = st.cursorY;
        boolean savedSuppress = st.suppressNewPage;
        boolean savedMeasuring = st.measuring;
        boolean savedDrew = st.drewOnPage;
        st.builder = new ContentStreamBuilder();
        st.suppressNewPage = true;
        st.measuring = true;
        double top = 100000;
        st.cursorY = top;
        layoutChildren(cell.getChildren(), st, 0, cw - 2 * cellPad);
        double used = top - st.cursorY;
        st.builder = savedBuilder;
        st.cursorY = savedCursor;
        st.suppressNewPage = savedSuppress;
        st.measuring = savedMeasuring;
        st.drewOnPage = savedDrew;
        return Math.max(used, TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_SIZE));
    }

    private static String cellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (SdmBlock b : cell.getChildren()) {
            if (b instanceof Paragraph) {
                sb.append(inlineText(((Paragraph) b).getInline())).append(' ');
            } else if (b instanceof Heading) {
                sb.append(inlineText(((Heading) b).getInline())).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private RunFont blockFont(List<SdmInline> inlines) {
        RunFont f = new RunFont();
        f.family = DEFAULT_FONT;
        f.size = DEFAULT_SIZE;
        boolean first = true;
        // Bold/italic by MAJORITY of text volume, not the first run: a
        // definition paragraph opening with a short bold term must not be
        // measured (and wrapped) entirely in the wider bold face.
        long boldChars = 0;
        long italicChars = 0;
        long totalChars = 0;
        for (SdmInline in : inlines) {
            if (!(in instanceof Run) || ((Run) in).getStyle() == null) {
                continue;
            }
            Run r = (Run) in;
            TextStyle st = r.getStyle();
            if (first) {
                if (st.getFontFamily() != null) f.family = st.getFontFamily();
                if (st.getFontSize() > 0) f.size = st.getFontSize();
                f.color = st.getColor();
                first = false;
            }
            int len = r.getText() == null ? 0 : r.getText().length();
            totalChars += len;
            if (st.isBold()) {
                boldChars += len;
            }
            if (st.isItalic()) {
                italicChars += len;
            }
        }
        f.bold = totalChars > 0 && boldChars * 2 >= totalChars;
        f.italic = totalChars > 0 && italicChars * 2 >= totalChars;
        return f;
    }

    /** The first EXPLICIT run font size (points), or 0 when none declares one. */
    private static double declaredRunSize(List<SdmInline> inlines) {
        for (SdmInline in : inlines) {
            if (in instanceof Run && ((Run) in).getStyle() != null
                    && ((Run) in).getStyle().getFontSize() > 0) {
                return ((Run) in).getStyle().getFontSize();
            }
        }
        return 0;
    }

    /** The first EXPLICIT run font family, or null when none declares one. */
    private static String declaredRunFamily(List<SdmInline> inlines) {
        for (SdmInline in : inlines) {
            if (in instanceof Run && ((Run) in).getStyle() != null
                    && ((Run) in).getStyle().getFontFamily() != null) {
                return ((Run) in).getStyle().getFontFamily();
            }
        }
        return null;
    }

    private static int textColor(List<SdmInline> inlines) {
        for (SdmInline in : inlines) {
            if (in instanceof Run && ((Run) in).getStyle() != null
                    && ((Run) in).getStyle().getColor() != 0) {
                return ((Run) in).getStyle().getColor();
            }
        }
        return 0;
    }

    private static final class RunFont {
        String family;
        double size;
        boolean bold;
        boolean italic;
        int color;
    }

    /** Per-render state: current document, page, content builder, cursor. */
    private static final class LayoutState {
        final Document doc = new Document();
        final org.aspose.pdf.PageCollection pages;
        final PageSetup setup;
        final LayoutReport report = new LayoutReport();
        org.aspose.pdf.sdm.ResourceTable resources;
        Page page;
        ContentStreamBuilder builder;
        /** Background layer of the page: painted BENEATH the flow content
         *  (legacy-form shape fills/outlines anchored after their text). */
        ContentStreamBuilder background;
        double cursorY;
        boolean drewOnPage;
        /** When set, page breaks are suppressed (used inside a side-by-side
         *  column band, which is laid out as a single-page unit). */
        boolean suppressNewPage;
        /** When set, block layout is running only to MEASURE consumed height
         *  (into a throwaway builder) — side effects that create real page
         *  objects (AcroForm fields) must be skipped. */
        boolean measuring;
        /** Total page count for the NUMPAGES furniture token (0 on the first
         *  pass; the second render pass supplies the real figure). */
        int totalPages;

        LayoutState(PageSetup setup) {
            this.setup = setup;
            try {
                this.pages = doc.getPages();
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot access pages of a new document", e);
            }
        }

        boolean pageIsEmpty() {
            return !drewOnPage;
        }

        void newPage() {
            frameKey = null; // frame positions are per-page
            finishPage();
            if (pages.getCount() == 0 || page != null) {
                page = pages.add();
            } else {
                page = pages.get(1);
            }
            page.setPageSize(setup.getPageWidth(), setup.getPageHeight());
            builder = new ContentStreamBuilder();
            background = new ContentStreamBuilder();
            cursorY = setup.getContentTop();
            drewOnPage = false;
            drawPageFurniture();
        }

        /** Paints the running header/footer lines in the margin bands. */
        private void drawPageFurniture() {
            double y = setup.getPageHeight() - setup.getHeaderDistance();
            for (PageSetup.FurnitureLine line : setup.getHeaderLines()) {
                double size = line.getSize() > 0 ? line.getSize() : 10;
                y -= size;
                drawFurnitureLine(line, size, y);
                y -= size * 0.25;
            }
            // Footer stacks upward from its band so the LAST line sits lowest.
            java.util.List<PageSetup.FurnitureLine> footer = setup.getFooterLines();
            double fy = setup.getFooterDistance();
            for (int i = footer.size() - 1; i >= 0; i--) {
                PageSetup.FurnitureLine line = footer.get(i);
                double size = line.getSize() > 0 ? line.getSize() : 10;
                drawFurnitureLine(line, size, fy);
                fy += size * 1.25;
            }
            // Furniture alone must not force an empty content page to be kept.
            drewOnPage = false;
        }

        /**
         * One furniture line. TABs carry Word's classic header positioning —
         * {@code left\tright} or {@code left\tcenter\tright} — so the parts are
         * spread to the left edge / centre / right edge of the content band.
         */
        private void drawFurnitureLine(PageSetup.FurnitureLine line, double size, double y) {
            double x = setup.getMarginLeft();
            double width = setup.getContentWidth();
            String font = resolveFont(DEFAULT_FONT, line.isBold(), false);
            // PAGE/NUMPAGES field tokens get the real numbers: the current page
            // is the one just added (pages.getCount()); the total comes from the
            // second render pass (falls back to the current page before that).
            String resolved = line.getText();
            if (resolved.contains(PageSetup.PAGE_TOKEN)
                    || resolved.contains(PageSetup.NUMPAGES_TOKEN)) {
                int pageNo = pages.getCount();
                resolved = resolved
                        .replace(PageSetup.PAGE_TOKEN, String.valueOf(pageNo))
                        .replace(PageSetup.NUMPAGES_TOKEN,
                                String.valueOf(totalPages > 0 ? totalPages : pageNo));
            }
            String[] parts = resolved.split("\t");
            // Explicit w:tabs stops position each TAB-separated part exactly
            // (Word measures stops from the left text edge); the edge/centre/edge
            // spread below stays the fallback for paragraphs without w:tabs.
            java.util.List<double[]> stops = line.getTabStops();
            if (parts.length >= 2 && stops.size() >= parts.length - 1) {
                if (!parts[0].trim().isEmpty()) {
                    drawText(parts[0].trim(), x, y, font, size, 0xFF000000);
                }
                for (int i = 1; i < parts.length; i++) {
                    String t = parts[i].trim();
                    if (t.isEmpty()) {
                        continue;
                    }
                    double[] stop = stops.get(i - 1);
                    double pos = x + stop[0];
                    double tw = TextLayoutHelper.measureTextWidth(t, font, size);
                    double lx = stop[1] == 1 ? pos - tw / 2 : stop[1] == 2 ? pos - tw : pos;
                    lx = Math.max(x, Math.min(lx, x + width - tw));
                    drawText(t, lx, y, font, size, 0xFF000000);
                }
                return;
            }
            if (parts.length >= 2) {
                String left = parts[0].trim();
                String right = parts[parts.length - 1].trim();
                if (!left.isEmpty()) {
                    drawText(left, x, y, font, size, 0xFF000000);
                }
                if (!right.isEmpty()) {
                    double rw = TextLayoutHelper.measureTextWidth(right, font, size);
                    drawText(right, x + Math.max(0, width - rw), y, font, size, 0xFF000000);
                }
                if (parts.length >= 3) {
                    StringBuilder mid = new StringBuilder();
                    for (int i = 1; i < parts.length - 1; i++) {
                        if (mid.length() > 0) {
                            mid.append(' ');
                        }
                        mid.append(parts[i].trim());
                    }
                    if (mid.length() > 0) {
                        double mw = TextLayoutHelper.measureTextWidth(mid.toString(), font, size);
                        drawText(mid.toString(), x + Math.max(0, (width - mw) / 2), y,
                                font, size, 0xFF000000);
                    }
                }
                return;
            }
            String text = resolved.trim();
            double lx = x;
            if (line.isCentered()) {
                double lw = TextLayoutHelper.measureTextWidth(text, font, size);
                lx = x + Math.max(0, (width - lw) / 2);
            }
            drawText(text, lx, y, font, size, 0xFF000000);
        }

        void finishPage() {
            if (page == null || builder == null) {
                return;
            }
            byte[] bg = background != null ? background.toByteArray() : new byte[0];
            byte[] main = builder.toByteArray();
            byte[] bytes = new byte[bg.length + 1 + main.length];
            System.arraycopy(bg, 0, bytes, 0, bg.length);
            bytes[bg.length] = (byte) 0x0A;
            System.arraycopy(main, 0, bytes, bg.length + 1, main.length);
            PdfStream contentStream = new PdfStream();
            contentStream.setDecodedData(bytes);
            PdfDictionary pageDict = page.getPdfDictionary();
            pageDict.set(PdfName.CONTENTS, contentStream);
            PdfDictionary fonts = new PdfDictionary();
            for (java.util.Map.Entry<String, String> e : builder.getFontResources().entrySet()) {
                PdfDictionary f = new PdfDictionary();
                f.set("Type", PdfName.of("Font"));
                f.set("Subtype", PdfName.of("Type1"));
                f.set("BaseFont", PdfName.of(e.getKey()));
                f.set("Encoding", PdfName.of("WinAnsiEncoding"));
                fonts.set(PdfName.of(e.getValue()), f);
            }
            // Embedded Identity-H fonts (CJK/Cyrillic lines) REPLACE the
            // placeholder Type1 dict registered under the same alias — the
            // 2-byte CID strings are unreadable under WinAnsi.
            for (java.util.Map.Entry<String, PdfDictionary> e
                    : builder.getEmbeddedFontDicts().entrySet()) {
                fonts.set(PdfName.of(e.getKey()), e.getValue());
            }
            PdfDictionary resourcesDict = new PdfDictionary();
            resourcesDict.set("Font", fonts);
            // Image XObjects registered while laying out block figures.
            java.util.Map<String, PdfStream> images = builder.getImageXObjectDicts();
            if (!images.isEmpty()) {
                PdfDictionary xobjects = new PdfDictionary();
                for (java.util.Map.Entry<String, PdfStream> e : images.entrySet()) {
                    xobjects.set(PdfName.of(e.getKey()), e.getValue());
                }
                resourcesDict.set("XObject", xobjects);
            }
            // Soft-alpha states registered for watermark/backdrop figures.
            java.util.Map<String, PdfDictionary> gstates = builder.getExtGStateDicts();
            if (!gstates.isEmpty()) {
                PdfDictionary extg = new PdfDictionary();
                for (java.util.Map.Entry<String, PdfDictionary> e : gstates.entrySet()) {
                    extg.set(PdfName.of(e.getKey()), e.getValue());
                }
                resourcesDict.set("ExtGState", extg);
            }
            pageDict.set(PdfName.RESOURCES, resourcesDict);
            builder = null;
            background = null;
        }

        /** Resolves an SDM family + style to a Base-14 name, recording substitutions. */
        String resolveFont(String family, boolean bold, boolean italic) {
            CssContext c = new CssContext();
            if (family != null && !family.isEmpty()) {
                c.setFontFamily(family);
            }
            c.setBold(bold);
            c.setItalic(italic);
            String base14 = c.toPdfFontName();
            if (family != null && !family.isEmpty()) {
                String canon = base14.split("-")[0];
                if (!family.equalsIgnoreCase(canon) && !family.equalsIgnoreCase(base14)) {
                    report.recordFontSubstitution(family, base14);
                }
            }
            return base14;
        }

        /** Unicode fallback faces tried, in order, for text WinAnsi can't encode. */
        private static final String[] UNICODE_FALLBACK_FONTS = {
                "Microsoft YaHei", "SimSun", "Arial Unicode MS", "Tahoma",
        };
        /** family → built Type0 graph (null value = tried, not on this OS). */
        private final java.util.Map<String, org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result>
                unicodeFonts = new java.util.HashMap<>();
        /** family → original TTF bytes (kept for the final glyph-strip pass). */
        private final java.util.Map<String, byte[]> unicodeTtf = new java.util.HashMap<>();
        /** family → glyph ids actually shown (drives the subset). */
        private final java.util.Map<String, java.util.Set<Integer>> unicodeGids
                = new java.util.HashMap<>();
        /** family → gid → unicode codepoint (rebuilds a lean ToUnicode). */
        private final java.util.Map<String, java.util.Map<Integer, Integer>> unicodeGidCp
                = new java.util.HashMap<>();

        void drawText(String text, double x, double baselineY, String font, double size, int color) {
            if (text == null || text.isEmpty()) {
                return;
            }
            String res = needsUnicodeFont(text) ? registerUnicodeFont(text) : null;
            if (res == null) {
                res = builder.registerFont(font);
            }
            builder.beginText();
            // ALWAYS set the fill colour (black when color==0). The fill colour is
            // graphics state that persists across BT/ET, so skipping it for black
            // text let a preceding coloured run (e.g. a red heading) bleed into
            // every following black label/value.
            builder.setRGBFillColor(((color >> 16) & 0xFF) / 255.0,
                    ((color >> 8) & 0xFF) / 255.0, (color & 0xFF) / 255.0);
            builder.setFont(res, size);
            builder.setTextMatrix(1, 0, 0, 1, x, baselineY);
            builder.showText(text);
            builder.endText();
            drewOnPage = true;
        }

        /** True when the line contains codepoints WinAnsi cannot encode (CJK,
         *  Cyrillic, …) — the std-14 path would paint them as '?'. */
        private static boolean needsUnicodeFont(String text) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c > 0xFF && !isWinAnsiExtra(c)) {
                    return true;
                }
            }
            return false;
        }

        /** The handful of >0xFF codepoints WinAnsi DOES map (quotes, dashes,
         *  €, ™, Œ, Š, …) — these stay on the std-14 path. */
        private static boolean isWinAnsiExtra(char c) {
            switch (c) {
                case 0x0152: case 0x0153: case 0x0160: case 0x0161:
                case 0x0178: case 0x017D: case 0x017E: case 0x0192:
                case 0x02C6: case 0x02DC: case 0x2013: case 0x2014:
                case 0x2018: case 0x2019: case 0x201A: case 0x201C:
                case 0x201D: case 0x201E: case 0x2020: case 0x2021:
                case 0x2022: case 0x2026: case 0x2030: case 0x2039:
                case 0x203A: case 0x20AC: case 0x2122:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Registers (once per layout) an embedded Identity-H Type0 font able
         * to show {@code text}: walks the system-font fallback chain, prefers
         * the first face covering every codepoint, else the first that loads
         * (notdef boxes still beat rows of '?'). Returns the page resource
         * alias, or null when no candidate font exists on this OS — the
         * caller then falls back to the historical std-14 path.
         */
        private String registerUnicodeFont(String text) {
            String covering = null;
            String loaded = null;
            for (String cand : UNICODE_FALLBACK_FONTS) {
                if (!unicodeFonts.containsKey(cand)) {
                    org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result r = null;
                    byte[] ttf = org.aspose.pdf.engine.font.ttf.FontDiskLookup.loadByName(cand);
                    if (ttf != null) {
                        try {
                            r = org.aspose.pdf.engine.font.ttf.Type0FontBuilder
                                    .buildLatin(cand.replace(" ", ""), ttf);
                            unicodeTtf.put(cand, ttf);
                        } catch (java.io.IOException e) {
                            // malformed system font — treat as absent
                        }
                    }
                    unicodeFonts.put(cand, r);
                }
                org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result r = unicodeFonts.get(cand);
                if (r == null) {
                    continue;
                }
                if (loaded == null) {
                    loaded = cand;
                }
                if (covering == null && covers(r.reader, text)) {
                    covering = cand;
                    break;
                }
            }
            String pick = covering != null ? covering : loaded;
            if (pick == null) {
                return null;
            }
            org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result r = unicodeFonts.get(pick);
            // Record the glyphs this line shows — the post-layout glyph-strip
            // keeps only these outlines (a full CJK face is ~20MB embedded).
            java.util.Set<Integer> gids = unicodeGids
                    .computeIfAbsent(pick, k -> new java.util.HashSet<>());
            java.util.Map<Integer, Integer> gidCp = unicodeGidCp
                    .computeIfAbsent(pick, k -> new java.util.HashMap<>());
            gids.add(0);
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                int gid = r.reader.getGlyphId(cp);
                gids.add(gid);
                gidCp.putIfAbsent(gid, cp);
                i += Character.charCount(cp);
            }
            return builder.registerEmbeddedFont("uni:" + pick, r.type0Font, r.reader);
        }

        /**
         * Post-layout: strips every embedded unicode font down to the glyph
         * outlines actually shown. The Type0 dict instance is shared by every
         * page's resources, so replacing its FontFile2 bytes once fixes the
         * whole document. GIDs are not renumbered — the Identity-H CIDs
         * already written to the content streams stay valid.
         */
        void subsetUnicodeFonts() {
            for (java.util.Map.Entry<String, org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result> e
                    : unicodeFonts.entrySet()) {
                org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result r = e.getValue();
                byte[] ttf = unicodeTtf.get(e.getKey());
                java.util.Set<Integer> gids = unicodeGids.get(e.getKey());
                if (r == null || ttf == null || gids == null || gids.isEmpty()) {
                    continue;
                }
                byte[] stripped = org.aspose.pdf.engine.optimization.TtfGlyphStripper
                        .strip(ttf, gids);
                if (stripped == null) {
                    continue;
                }
                org.aspose.pdf.engine.pdfobjects.PdfArray desc =
                        r.type0Font.getArray("DescendantFonts");
                PdfDictionary cid = desc != null && desc.size() > 0 ? desc.getDictionary(0) : null;
                PdfDictionary fd = cid != null ? cid.getDictionary("FontDescriptor") : null;
                Object ff = fd != null ? fd.get("FontFile2") : null;
                if (ff instanceof PdfStream) {
                    ((PdfStream) ff).setDecodedData(stripped);
                    ((PdfStream) ff).set(PdfName.of("Length1"),
                            org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(stripped.length));
                }
                // The builder's /W and /ToUnicode cover the FULL face (tens of
                // thousands of glyphs, ~1.5MB of text) — rebuild both for just
                // the glyphs shown.
                java.util.List<Integer> sorted = new java.util.ArrayList<>(gids);
                java.util.Collections.sort(sorted);
                if (cid != null) {
                    org.aspose.pdf.engine.pdfobjects.PdfArray w =
                            new org.aspose.pdf.engine.pdfobjects.PdfArray();
                    int upm = Math.max(1, r.reader.getUnitsPerEm());
                    for (int gid : sorted) {
                        w.add(org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(gid));
                        org.aspose.pdf.engine.pdfobjects.PdfArray one =
                                new org.aspose.pdf.engine.pdfobjects.PdfArray();
                        one.add(org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(
                                (int) Math.round(r.reader.getAdvanceWidth(gid) * 1000.0 / upm)));
                        w.add(one);
                    }
                    cid.set(PdfName.of("W"), w);
                }
                java.util.Map<Integer, Integer> gidCp = unicodeGidCp.get(e.getKey());
                if (gidCp != null && !gidCp.isEmpty()) {
                    r.type0Font.set(PdfName.of("ToUnicode"), leanToUnicode(gidCp));
                }
            }
        }

        /** Minimal ToUnicode CMap: one bfchar per shown glyph. */
        private static PdfStream leanToUnicode(java.util.Map<Integer, Integer> gidCp) {
            StringBuilder sb = new StringBuilder();
            sb.append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
              .append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
              .append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
              .append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n");
            java.util.List<Integer> gs = new java.util.ArrayList<>(gidCp.keySet());
            java.util.Collections.sort(gs);
            for (int i = 0; i < gs.size(); i += 100) {
                int n = Math.min(100, gs.size() - i);
                sb.append(n).append(" beginbfchar\n");
                for (int j = i; j < i + n; j++) {
                    int gid = gs.get(j);
                    int cp = gidCp.get(gid);
                    sb.append(String.format("<%04X> <", gid & 0xFFFF));
                    for (char c : Character.toChars(cp)) {
                        sb.append(String.format("%04X", (int) c));
                    }
                    sb.append(">\n");
                }
                sb.append("endbfchar\n");
            }
            sb.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            PdfStream s = new PdfStream();
            s.setDecodedData(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return s;
        }

        /** Every non-WinAnsi codepoint maps to a real glyph in {@code reader}. */
        private static boolean covers(org.aspose.pdf.engine.font.ttf.TrueTypeReader reader,
                                      String text) {
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                if (cp > 0x20 && reader.getGlyphId(cp) == 0) {
                    return false;
                }
                i += Character.charCount(cp);
            }
            return true;
        }

        /** Embeds an image XObject and paints it at (x,y) scaled to (w,h). */
        void drawImage(String key, PdfStream xobj, double x, double y, double w, double h) {
            String name = builder.registerImage(key, xobj);
            builder.saveState();
            builder.concatMatrix(w, 0, 0, h, x, y);
            builder.drawXObject(name);
            builder.restoreState();
            drewOnPage = true;
        }

        /**
         * Paints an image faintly at (x,y) scaled to (w,h) with constant fill
         * alpha — a watermark backdrop. Does NOT set {@code drewOnPage}: a page
         * carrying only a backdrop is still "empty" for flow purposes, so the
         * out-of-flow watermark never forces a page break of its own.
         */
        void drawBackdropImage(String key, PdfStream xobj, double x, double y,
                               double w, double h, double alpha) {
            String name = builder.registerImage(key, xobj);
            String gs = builder.registerAlphaGState(alpha);
            builder.saveState();
            builder.setExtGState(gs);
            builder.concatMatrix(w, 0, 0, h, x, y);
            builder.drawXObject(name);
            builder.restoreState();
        }

        /** Sequence for generated write-in box field names. */
        int fieldSeq;

        /** Current positioned-frame identity (x|y|w) and its internal cursor —
         *  consecutive same-position frame paragraphs stack into one frame. */
        String frameKey;
        double frameCursorY;

        /**
         * Adds a fillable AcroForm field over a legacy-form write-in box: a
         * checkbox for checkbox-sized boxes, else a text box. Failures are
         * swallowed — a broken widget must not sink the page.
         */
        void addBoxField(double x, double y, double w, double h) {
            try {
                org.aspose.pdf.Rectangle r = new org.aspose.pdf.Rectangle(x, y, x + w, y + h);
                org.aspose.pdf.forms.Field f = (w <= 15 && h <= 15)
                        ? new org.aspose.pdf.forms.CheckboxField(page, r)
                        : new org.aspose.pdf.forms.TextBoxField(page, r);
                f.setPartialName("box" + (++fieldSeq));
                doc.getForm().add(f);
            } catch (Exception e) {
                // decorative degradation only
            }
        }

        void drawRect(double x, double y, double w, double h) {
            builder.saveState();
            builder.setRGBStrokeColor(0.6, 0.6, 0.6);
            builder.rectangle(x, y, w, h);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }

        /** Strokes a rectangle outline in a solid {@code 0xAARRGGBB} colour. */
        void strokeRect(double x, double y, double w, double h, int argb) {
            builder.saveState();
            builder.setLineWidth(0.5);
            builder.setRGBStrokeColor(((argb >> 16) & 0xFF) / 255.0,
                    ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0);
            builder.rectangle(x, y, w, h);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }

        /** Fills a rectangle with a solid {@code 0xAARRGGBB} colour (alpha ignored). */
        void fillRect(double x, double y, double w, double h, int argb) {
            builder.saveState();
            builder.setRGBFillColor(((argb >> 16) & 0xFF) / 255.0,
                    ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0);
            builder.rectangle(x, y, w, h);
            builder.fill();
            builder.restoreState();
            drewOnPage = true;
        }

        void drawVRule(double x, double yTop, double yBottom) {
            builder.saveState();
            builder.setRGBStrokeColor(0.5, 0.5, 0.5);
            builder.moveTo(x, yTop);
            builder.lineTo(x, yBottom);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }

        void drawHRule(double x, double y, double w) {
            builder.saveState();
            builder.setRGBStrokeColor(0.5, 0.5, 0.5);
            builder.moveTo(x, y);
            builder.lineTo(x + w, y);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }

        /** Draws a block's bottom border: a horizontal line at {@code y} across
         *  {@code w}, honouring the CSS thickness, {@code 0xAARRGGBB} colour and
         *  style ({@code dotted}/{@code dashed} → a dash pattern, else solid). */
        void drawBottomBorder(double x, double y, double w, double thickness,
                              int argb, String style) {
            builder.saveState();
            builder.setRGBStrokeColor(((argb >> 16) & 0xFF) / 255.0,
                    ((argb >> 8) & 0xFF) / 255.0, (argb & 0xFF) / 255.0);
            double lw = thickness > 0 ? thickness : 0.5;
            builder.setLineWidth(lw);
            if ("dotted".equalsIgnoreCase(style)) {
                builder.setLineDash(new double[]{lw, lw * 2}, 0);
            } else if ("dashed".equalsIgnoreCase(style)) {
                builder.setLineDash(new double[]{lw * 3, lw * 2}, 0);
            }
            builder.moveTo(x, y);
            builder.lineTo(x + w, y);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }

        void drawPlaceholderBox(double x, double y, double w, double h) {
            builder.saveState();
            builder.setRGBStrokeColor(0.7, 0.7, 0.7);
            builder.rectangle(x, y, w, h);
            builder.stroke();
            builder.moveTo(x, y);
            builder.lineTo(x + w, y + h);
            builder.moveTo(x, y + h);
            builder.lineTo(x + w, y);
            builder.stroke();
            builder.restoreState();
            drewOnPage = true;
        }
    }
}
