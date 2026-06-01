package org.aspose.pdf.engine.layout;

import org.aspose.pdf.BaseParagraph;
import org.aspose.pdf.BorderInfo;
import org.aspose.pdf.Cell;
import org.aspose.pdf.Color;
import org.aspose.pdf.FloatingBox;
import org.aspose.pdf.HeaderFooter;
import org.aspose.pdf.Heading;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.HtmlFragment;
import org.aspose.pdf.Image;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.MarginInfo;
import org.aspose.pdf.Note;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageInfo;
import org.aspose.pdf.PageNumberStamp;
import org.aspose.pdf.Paragraphs;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Row;
import org.aspose.pdf.Table;
import org.aspose.pdf.TextStamp;
import org.aspose.pdf.TocInfo;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextSegment;
import org.aspose.pdf.text.TextState;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * The main layout engine that converts high-level paragraph objects into PDF content
 * stream bytes during {@code Document.save()}.
 * <p>
 * For each page that has paragraphs, the engine:
 * <ol>
 *   <li>Determines page dimensions and margins</li>
 *   <li>Creates a {@link LayoutContext} to track cursor position</li>
 *   <li>Creates a {@link ContentStreamBuilder} and {@link ResourceBuilder}</li>
 *   <li>Lays out header paragraphs (if present)</li>
 *   <li>Lays out each paragraph (TextFragment, Table, FloatingBox, HtmlFragment, Image)</li>
 *   <li>Lays out footer paragraphs at the bottom (if present)</li>
 *   <li>Applies stamp overlays (TextStamp, ImageStamp, PageNumberStamp)</li>
 *   <li>Sets the resulting content stream and resources on the page dictionary</li>
 * </ol>
 * </p>
 * <p>
 * Text positioning uses PDF coordinates where the origin is at the bottom-left
 * and Y increases upward. Layout flows top-to-bottom: the cursor starts at
 * {@code pageHeight - marginTop} and decreases as content is placed.
 * </p>
 */
public class LayoutEngine {

    private static final Logger LOG = Logger.getLogger(LayoutEngine.class.getName());

    // Precompiled tag-stripping patterns for stripHtmlTags (Sprint 27 Part C).
    private static final java.util.regex.Pattern BR_TAG =
            java.util.regex.Pattern.compile("(?i)<br\\s*/?>");
    private static final java.util.regex.Pattern P_OPEN_TAG =
            java.util.regex.Pattern.compile("(?i)<p[^>]*>");
    private static final java.util.regex.Pattern P_CLOSE_TAG =
            java.util.regex.Pattern.compile("(?i)</p>");
    private static final java.util.regex.Pattern ANY_TAG =
            java.util.regex.Pattern.compile("<[^>]+>");

    /** Default font when none is specified in the TextState. */
    private static final String DEFAULT_FONT = "Helvetica";

    /** Default font size when none is specified (or zero). */
    private static final double DEFAULT_FONT_SIZE = 12.0;

    /** Default cell padding when none is specified. */
    private static final double DEFAULT_CELL_PADDING = 2.0;

    /** Fallback per-column width used when a table has no explicit ColumnWidths
     *  and the available content width could not be determined. */
    private static final double DEFAULT_AUTO_COLUMN_WIDTH = 100.0;

    /** Current page number (1-based) for $p substitution. */
    private int currentPageNumber = 1;

    /** Total page count for $P substitution. */
    private int totalPageCount = 1;

    /** Auto-numbering counter for footnotes; resets per document save. */
    private int nextFootnoteNumber = 1;

    /** Auto-numbering counter for endnotes; resets per document save. */
    private int nextEndnoteNumber = 1;

    /** Maximum heading depth that the auto-numbering counter tracks. */
    private static final int MAX_HEADING_LEVELS = 9;

    /**
     * Carried between paragraphs: baseline Y of the last rendered text line,
     * X of its right edge, and the line height used. Consumed by the next
     * paragraph if it has {@link BaseParagraph#isInLineParagraph()} set so
     * the new content continues on the same baseline. Reset whenever a
     * non-inline paragraph rebuilds the state, and on page boundaries.
     */
    private double lastLineBaselineY = Double.NaN;
    private double lastLineRightX = Double.NaN;
    private double lastLineHeight = Double.NaN;

    /**
     * When non-NaN, the next text fragment's FIRST line is rendered at this
     * X (instead of the content-left margin). Set by {@link #layoutParagraph}
     * before dispatching an inline paragraph; cleared by
     * {@link #layoutTextFragment} after it consumes it for the first line.
     */
    private double inlineFirstLineX = Double.NaN;

    /**
     * Hierarchical heading counters per level (1-based level → 0-based index).
     * On encountering a {@code Heading} with {@code isAutoSequence} true at
     * level {@code L}, the counter at {@code L-1} is incremented and all
     * deeper levels are reset, then the marker is built from the non-zero
     * counters 1..L joined with '.'.
     */
    private final int[] headingCounters = new int[MAX_HEADING_LEVELS];

    /**
     * Notes pending render at the bottom of the current page. Each entry
     * holds the resolved marker text and the body text (already joined).
     * Cleared after every page's footnote block is rendered.
     */
    private final List<PendingNote> pendingFootnotes = new ArrayList<>();

    /**
     * Notes pending render at the end of the document. Accumulated across
     * pages and flushed on the last page only.
     */
    private final List<PendingNote> pendingEndnotes = new ArrayList<>();

    /** Footnote/endnote line height factor — smaller than body text. */
    private static final double NOTE_FONT_RATIO = 0.75;

    /** Superscript marker font ratio relative to the body fragment. */
    private static final double SUPERSCRIPT_FONT_RATIO = 0.65;

    /** Vertical raise of the superscript marker above the baseline. */
    private static final double SUPERSCRIPT_RAISE_RATIO = 0.33;

    /** Width of the horizontal separator above a footnote/endnote block (pt). */
    private static final double NOTE_SEPARATOR_WIDTH = 70.0;

    /** Held data for a queued footnote or endnote awaiting render. */
    private static final class PendingNote {
        final String marker;
        final String body;
        PendingNote(String marker, String body) {
            this.marker = marker;
            this.body = body;
        }
    }

    /**
     * Creates a new LayoutEngine.
     */
    public LayoutEngine() {
        LOG.fine("LayoutEngine created");
    }

    /**
     * Sets the page numbering context for $p / $P variable substitution.
     *
     * @param pageNumber 1-based current page number
     * @param totalPages total number of pages in the document
     */
    public void setPageNumbering(int pageNumber, int totalPages) {
        this.currentPageNumber = pageNumber;
        this.totalPageCount = totalPages;
    }

    /**
     * Performs layout on a page, converting its paragraphs into a PDF content stream.
     * <p>
     * After this method completes, the page's COS dictionary will have its /Contents
     * entry set to a new COSStream containing the rendered content, and its /Resources
     * entry set to the built resources dictionary.
     * </p>
     *
     * @param page the page to lay out
     */
    public void layout(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }

        Paragraphs paragraphs = page.getParagraphs();
        if (paragraphs == null || paragraphs.size() == 0) {
            LOG.fine("Page has no paragraphs; skipping layout");
            return;
        }

        // 1. Get page dimensions and margins
        double pageWidth;
        double pageHeight;
        MarginInfo margins;

        // Aspose's Page.PageInfo.Margin defaults to 90pt on every side
        // (~1.25 inch). When the user did not set PageInfo at all — the
        // common case in tests that just call `doc.getPages().add()` —
        // fall back to the same 90pt box so positions line up with both
        // Aspose templates and absorber-based assertions.
        PageInfo pageInfo = page.getPageInfo();
        if (pageInfo != null) {
            pageWidth = pageInfo.getWidth();
            pageHeight = pageInfo.getHeight();
            margins = pageInfo.getMargin() != null
                    ? toMarginInfo(pageInfo.getMargin())
                    : new MarginInfo();
        } else {
            Rectangle mediaBox = page.getMediaBox();
            if (mediaBox != null) {
                pageWidth = mediaBox.getWidth();
                pageHeight = mediaBox.getHeight();
            } else {
                pageWidth = 595; // A4 default
                pageHeight = 842;
            }
            margins = new MarginInfo(90, 90, 90, 90);
        }

        // Inline-paragraph tracking is page-local: a paragraph cannot trail
        // off the previous page and resume on this one.
        lastLineBaselineY = Double.NaN;
        lastLineRightX = Double.NaN;
        lastLineHeight = Double.NaN;
        inlineFirstLineX = Double.NaN;

        // 2. Create LayoutContext
        LayoutContext ctx = new LayoutContext(pageWidth, pageHeight, margins);

        // 3. Create builders
        ContentStreamBuilder builder = new ContentStreamBuilder();
        ResourceBuilder resources = new ResourceBuilder();

        // 4. Lay out TOC title if this is a TOC page
        TocInfo tocInfo = page.getTocInfo();
        if (tocInfo != null && tocInfo.getTitle() != null) {
            layoutTextFragment(tocInfo.getTitle(), builder, resources, ctx);
            // Add some spacing after the title
            ctx.advanceCursor(10);
        }

        // 4b. Lay out header
        HeaderFooter header = page.getHeader();
        if (header != null && header.getParagraphs() != null) {
            for (BaseParagraph para : header.getParagraphs()) {
                layoutParagraph(para, builder, resources, ctx);
            }
        }

        // 5. Lay out each paragraph
        for (BaseParagraph para : paragraphs) {
            layoutParagraph(para, builder, resources, ctx);
        }

        // 5b. Per-page footnote block + (on last page) endnote block. Both
        // anchor to the bottom of the content area; the space they consume
        // was already reserved by Document.paginateNewDocumentPagesIfNeeded
        // via estimateNoteReserve.
        layoutFootnoteBlockIfAny(builder, resources, ctx);
        layoutEndnoteBlockIfLastPage(builder, resources, ctx);

        // 6. Lay out footer at bottom
        HeaderFooter footer = page.getFooter();
        if (footer != null && footer.getParagraphs() != null) {
            // Position cursor at bottom margin area for footer
            double footerY = ctx.getContentBottom();
            // Calculate footer height first
            double savedY = ctx.getCursorY();
            ctx.setCursorY(footerY);
            for (BaseParagraph para : footer.getParagraphs()) {
                layoutParagraph(para, builder, resources, ctx);
            }
            // Restore cursor (footer doesn't consume main content area)
            ctx.setCursorY(savedY);
        }

        // 7. Build content stream and set on page
        byte[] contentBytes = builder.toByteArray();
        COSStream contentStream = new COSStream();
        contentStream.setDecodedData(contentBytes);

        COSDictionary pageDict = page.getCOSDictionary();
        pageDict.set(COSName.CONTENTS, contentStream);

        // 8. Build resources dictionary and set on page
        // Sync font registrations from builder to resources
        for (java.util.Map.Entry<String, String> entry : builder.getFontResources().entrySet()) {
            String baseFont = entry.getKey();
            // Ensure resource builder also has this font
            if (resources.getFontResourceName(baseFont) == null) {
                resources.addFont(baseFont);
            }
        }

        COSDictionary resourcesDict = resources.buildResourcesDictionary();
        pageDict.set(COSName.RESOURCES, resourcesDict);

        LOG.fine(() -> "Layout complete: " + contentBytes.length + " bytes of content stream");
    }

    /**
     * The rendered header/footer of a page, ready to be wrapped as a Form
     * XObject overlay. Used for documents loaded from disk, where the full
     * layout pass ({@link #layout(Page)}) does not run and would otherwise
     * overwrite the page's existing content.
     */
    public static final class HeaderFooterOverlay {
        /** Content-stream bytes drawing the header/footer in page user space. */
        public final byte[] content;
        /** The {@code /Resources} dictionary (fonts, image XObjects) referenced by {@link #content}. */
        public final COSDictionary resources;

        HeaderFooterOverlay(byte[] content, COSDictionary resources) {
            this.content = content;
            this.resources = resources;
        }
    }

    /**
     * Lays out only this page's header and footer paragraphs (set via
     * {@link Page#setHeader}/{@link Page#setFooter}) into a standalone content
     * stream, WITHOUT touching the page's existing {@code /Contents} or
     * {@code /Resources}. Callers wrap the result as a Form XObject overlay.
     * <p>
     * This is the loaded-document counterpart to {@link #layout(Page)}: that
     * method rebuilds the whole page from paragraphs (replacing content), which
     * is correct only for newly authored pages. PDFNET-38279: footers applied
     * to the pages of an existing PDF must be appended, not replace the original
     * content.
     * </p>
     *
     * @param page the page whose header/footer to render
     * @return the overlay content + resources, or {@code null} if the page has
     *         neither a header nor a footer with paragraphs
     */
    public HeaderFooterOverlay buildHeaderFooterOverlay(Page page) {
        if (page == null) {
            throw new IllegalArgumentException("Page must not be null");
        }
        HeaderFooter header = page.getHeader();
        HeaderFooter footer = page.getFooter();
        boolean hasHeader = header != null && header.getParagraphs() != null
                && !header.getParagraphs().isEmpty();
        boolean hasFooter = footer != null && footer.getParagraphs() != null
                && !footer.getParagraphs().isEmpty();
        if (!hasHeader && !hasFooter) {
            return null;
        }

        // Page dimensions / margins — mirror layout()'s resolution so footer
        // positions line up with the rest of the engine.
        double pageWidth;
        double pageHeight;
        MarginInfo margins;
        PageInfo pageInfo = page.getPageInfo();
        if (pageInfo != null) {
            pageWidth = pageInfo.getWidth();
            pageHeight = pageInfo.getHeight();
            margins = pageInfo.getMargin() != null
                    ? toMarginInfo(pageInfo.getMargin())
                    : new MarginInfo();
        } else {
            Rectangle mediaBox = page.getMediaBox();
            if (mediaBox != null) {
                pageWidth = mediaBox.getWidth();
                pageHeight = mediaBox.getHeight();
            } else {
                pageWidth = 595;
                pageHeight = 842;
            }
            margins = new MarginInfo(90, 90, 90, 90);
        }

        lastLineBaselineY = Double.NaN;
        lastLineRightX = Double.NaN;
        lastLineHeight = Double.NaN;
        inlineFirstLineX = Double.NaN;

        LayoutContext ctx = new LayoutContext(pageWidth, pageHeight, margins);
        ContentStreamBuilder builder = new ContentStreamBuilder();
        ResourceBuilder resources = new ResourceBuilder();

        if (hasHeader) {
            for (BaseParagraph para : header.getParagraphs()) {
                layoutParagraph(para, builder, resources, ctx);
            }
        }

        if (hasFooter) {
            // The footer occupies the bottom-margin band. Lay it out in a
            // context whose content floor is the page bottom (bottom margin 0)
            // and start the cursor at the original bottom-margin line, so a
            // multi-paragraph footer (e.g. image + text + page number) flows
            // downward into the band instead of being clipped by the main
            // content floor after the first paragraph (PDFNET-38279).
            MarginInfo footerMargins = new MarginInfo(
                    margins.getLeft(), 0, margins.getRight(), margins.getTop());
            LayoutContext footerCtx = new LayoutContext(pageWidth, pageHeight, footerMargins);
            footerCtx.setCursorY(margins.getBottom() > 0 ? margins.getBottom() : 72);
            for (BaseParagraph para : footer.getParagraphs()) {
                layoutParagraph(para, builder, resources, footerCtx);
            }
        }

        // Sync any fonts the builder registered into the resource builder
        // (mirrors layout() step 8) so the overlay's /Resources is complete.
        for (java.util.Map.Entry<String, String> entry : builder.getFontResources().entrySet()) {
            if (resources.getFontResourceName(entry.getKey()) == null) {
                resources.addFont(entry.getKey());
            }
        }

        return new HeaderFooterOverlay(builder.toByteArray(), resources.buildResourcesDictionary());
    }

    /**
     * Dispatches a paragraph to the appropriate type-specific renderer.
     *
     * @param para      the paragraph to lay out
     * @param builder   the content stream builder
     * @param resources the resource builder
     * @param ctx       the layout context
     * @return the height consumed by the paragraph
     */
    public double layoutParagraph(BaseParagraph para, ContentStreamBuilder builder,
                                   ResourceBuilder resources, LayoutContext ctx) {
        if (para == null) {
            return 0;
        }

        MarginInfo paraMargin = para.getMargin();
        // When the caller marked this paragraph as inline, snap the cursor
        // back up to the previous paragraph's last baseline so the new
        // content shares a row, and remember the X to resume rendering at.
        // Top/bottom margins are skipped for inline paragraphs — they would
        // otherwise push the resumed line away from the previous one.
        boolean inline = para.isInLineParagraph()
                && !Double.isNaN(lastLineBaselineY)
                && !Double.isNaN(lastLineRightX);
        if (inline) {
            // Restore cursor to where the previous line's baseline started
            // (one line height above its baseline), so the first line of
            // this paragraph renders on the same baseline.
            ctx.setCursorY(lastLineBaselineY + lastLineHeight);
            inlineFirstLineX = lastLineRightX + 1.0;   // tiny gap
            paraMargin = null;                          // suppress margins
        } else if (paraMargin != null && paraMargin.getTop() > 0) {
            ctx.advanceCursor(paraMargin.getTop());
        }

        double height = 0;

        if (para instanceof Heading) {
            height = layoutHeading((Heading) para, builder, resources, ctx);
        } else if (para instanceof TextFragment) {
            height = layoutTextFragment((TextFragment) para, builder, resources, ctx);
        } else if (para instanceof Table) {
            height = layoutTable((Table) para, builder, resources, ctx);
        } else if (para instanceof HtmlFragment) {
            height = layoutHtmlFragment((HtmlFragment) para, builder, resources, ctx);
        } else if (para instanceof FloatingBox) {
            height = layoutFloatingBox((FloatingBox) para, builder, resources, ctx);
        } else if (para instanceof Image) {
            // Image layout is a placeholder for now
            height = layoutImage((Image) para, builder, resources, ctx);
        } else {
            LOG.fine(() -> "Unsupported paragraph type: " + para.getClass().getSimpleName());
        }

        // Apply paragraph margin (bottom)
        if (paraMargin != null && paraMargin.getBottom() > 0) {
            ctx.advanceCursor(paraMargin.getBottom());
        }

        return height;
    }

    /**
     * Lays out a TextFragment with word wrapping, font selection, and color.
     *
     * @param tf        the text fragment
     * @param builder   the content stream builder
     * @param resources the resource builder
     * @param ctx       the layout context
     * @return the total height consumed
     */
    public double layoutTextFragment(TextFragment tf, ContentStreamBuilder builder,
                                      ResourceBuilder resources, LayoutContext ctx) {
        String text = tf.getText();
        // When a fragment is built segment-by-segment (no setText), the
        // top-level getText() is null but the segments carry the content.
        // Concatenate so rendering covers both construction styles.
        if ((text == null || text.isEmpty()) && tf.getSegments() != null && !tf.getSegments().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TextSegment seg : tf.getSegments()) {
                if (seg.getText() != null) sb.append(seg.getText());
            }
            text = sb.toString();
        }
        if (text == null || text.isEmpty()) {
            return 0;
        }

        // Rich multi-segment fragment: when the segments carry heterogeneous
        // styles (differing font style or color), render each as its own
        // styled run on a shared baseline so per-segment bold/italic/color
        // survives the save→reload round trip (PDFNEWNET_48777). Uniform and
        // single-segment fragments fall through to the word-wrapping path.
        if (hasHeterogeneousSegments(tf)) {
            return layoutRichSegments(tf, builder, resources, ctx);
        }

        // Substitute page number variables ($p = current page, $P = total pages)
        if (text.contains("$p") || text.contains("$P")) {
            text = text.replace("$P", String.valueOf(totalPageCount));
            text = text.replace("$p", String.valueOf(currentPageNumber));
        }

        // Normalize line breaks: PDF text rendering treats CR as a regular
        // character (not in WinAnsi), so leftover \r from a \r\n sequence
        // would otherwise render as a literal '?'. Wrap splits only on \n.
        if (text.indexOf('\r') >= 0) {
            text = text.replace("\r\n", "\n").replace('\r', '\n');
        }

        // Get font info from TextState. Mirror Heading: when segments carry
        // their own font/size, use the first segment's TextState — the
        // fragment-level state is often a barebones placeholder.
        TextState ts = tf.getTextState();
        if (!tf.getSegments().isEmpty() && tf.getSegments().get(0).getTextState() != null) {
            TextState segTs = tf.getSegments().get(0).getTextState();
            if (segTs.getFontName() != null || segTs.getFontSize() > 0 || segTs.getLineSpacing() > 0) {
                ts = segTs;
            }
        }
        String fontName = (ts != null && ts.getFontName() != null) ? ts.getFontName() : DEFAULT_FONT;
        double fontSize = (ts != null && ts.getFontSize() > 0) ? ts.getFontSize() : DEFAULT_FONT_SIZE;

        // SimSun (the standard CJK face) has no Extension B glyphs. When a
        // fragment uses CJK Ext B characters (U+20000+), transparently swap
        // the embedded SimSun bytes for SimSun-ExtB (simsunb.ttf), which
        // covers exactly that block plus the ASCII range we need to keep
        // surrounding Latin words readable.
        org.aspose.pdf.text.Font fontOverride = computeExtBOverride(ts, text);

        // Register font. Prefer the Type0 embedded path when the caller has
        // marked the font as embedded AND we have raw TTF bytes — otherwise
        // fall back to the standard Type1/WinAnsi path.
        String fontResource = registerFontResource(ts, fontName, resources, builder, fontOverride);

        // Word wrap. When this is the inline-continuation paragraph, the
        // first line shares the row with the previous paragraph and has
        // only `firstLineWidth` worth of space; subsequent lines wrap as
        // usual at the content margin.
        double availWidth = ctx.getAvailableWidth();
        double inlineX = inlineFirstLineX;
        inlineFirstLineX = Double.NaN;   // consume — set only for the next paragraph
        List<String> lines;
        if (!Double.isNaN(inlineX) && inlineX < ctx.getContentRight()) {
            double firstWidth = Math.max(1, ctx.getContentRight() - inlineX);
            lines = wrapAsymmetric(text, fontName, fontSize, firstWidth, availWidth);
        } else {
            inlineX = Double.NaN;
            lines = TextLayoutHelper.wrapText(text, fontName, fontSize, availWidth);
        }
        // Aspose semantics: when LineSpacing is unset (0), use the typographic
        // default (~font × 1.2). Once explicitly set, Aspose treats the value
        // as a hint that overrides the default leading floor; empirically
        // matching the PDFNEWNET_33546 template shows the baseline-to-baseline
        // distance collapses to {@code fontSize} regardless of the small
        // value chosen (lineSpacing=2 with fontSize=10 still renders as 10pt
        // baselines). Use {@code max(lineSpacing, fontSize)} as the leading
        // so callers can also push lines APART by passing a larger value.
        double lineHeight;
        if (ts != null && ts.getLineSpacing() > 0) {
            lineHeight = Math.max(fontSize, ts.getLineSpacing());
        } else {
            lineHeight = TextLayoutHelper.getLineHeight(fontName, fontSize);
        }

        // Handle foreground color
        Color fgColor = (ts != null) ? ts.getForegroundColor() : null;

        // Handle horizontal alignment
        HorizontalAlignment align = tf.getHorizontalAlignment();

        double totalHeight = 0;
        // Track the rightmost end of the last rendered line for two reasons:
        // (1) superscript marker for an attached footnote/endnote, and
        // (2) inline-continuation of the next paragraph.
        double localLastBaselineY = Double.NaN;
        double localLastLineRightX = Double.NaN;
        int lineIndex = 0;

        for (String line : lines) {
            if (!ctx.hasSpace(lineHeight)) {
                LOG.fine("Not enough space for text line; stopping layout");
                break;
            }

            double lineX;
            double lineWidth = TextLayoutHelper.measureTextWidth(line, fontName, fontSize);
            boolean inlineFirst = lineIndex == 0 && !Double.isNaN(inlineX);

            if (inlineFirst) {
                // First line of an inline-continuation paragraph anchors to
                // the X just past where the previous paragraph ended; do not
                // apply alignment to this line (alignment refers to the full
                // content width, which doesn't apply here).
                lineX = inlineX;
            } else {
                lineX = ctx.getContentLeft();
                if (align == HorizontalAlignment.Center) {
                    lineX = ctx.getContentLeft() + (availWidth - lineWidth) / 2.0;
                } else if (align == HorizontalAlignment.Right) {
                    lineX = ctx.getContentRight() - lineWidth;
                }
            }

            double baselineY = ctx.getCursorY() - lineHeight;

            builder.beginText();
            if (fgColor != null) {
                emitColor(fgColor, builder, true);
            }
            builder.setFont(fontResource, fontSize);
            builder.moveText(lineX, baselineY);
            builder.showText(line);
            builder.endText();

            localLastBaselineY = baselineY;
            localLastLineRightX = lineX + lineWidth;

            ctx.advanceCursor(lineHeight);
            totalHeight += lineHeight;
            lineIndex++;
        }

        // Publish for the next paragraph in case it is inline.
        if (!Double.isNaN(localLastBaselineY)) {
            lastLineBaselineY = localLastBaselineY;
            lastLineRightX = localLastLineRightX;
            lastLineHeight = lineHeight;
        }

        // Append a superscript marker for an attached footnote or endnote
        // and queue the note body for later block rendering. Both are
        // possible on the same fragment in principle; Aspose treats endnote
        // as taking precedence when both are set, matching estimateNoteReserve.
        Note footNote = tf.getFootNote();
        Note endNote = tf.getEndNote();
        if (!Double.isNaN(localLastBaselineY) && (footNote != null || endNote != null)) {
            boolean isEndnote = endNote != null;
            Note attached = isEndnote ? endNote : footNote;
            String marker = resolveNoteMarker(attached, isEndnote);
            drawSuperscriptMarker(builder, fontResource, fontSize,
                    localLastLineRightX, localLastBaselineY, marker);
            String body = extractNoteBody(attached);
            (isEndnote ? pendingEndnotes : pendingFootnotes)
                    .add(new PendingNote(marker, body));
        }

        return totalHeight;
    }

    /**
     * Returns {@code true} when the fragment has at least two non-empty
     * segments whose styles differ in font style or foreground color. Such
     * fragments must be rendered run-by-run (see {@link #layoutRichSegments});
     * uniform or single-segment fragments render via the normal concatenated
     * path. The font name is deliberately NOT part of the test: many
     * fragments carry font/size only on the first segment as a placeholder,
     * and treating that as "heterogeneous" would needlessly divert them.
     */
    private boolean hasHeterogeneousSegments(TextFragment tf) {
        if (tf.getSegments() == null || tf.getSegments().size() < 2) {
            return false;
        }
        boolean first = true;
        int style0 = 0;
        Color color0 = null;
        for (TextSegment seg : tf.getSegments()) {
            if (seg.getText() == null || seg.getText().isEmpty()) {
                continue;
            }
            TextState st = seg.getTextState();
            int style = st != null ? st.getFontStyle() : 0;
            Color color = st != null ? st.getForegroundColor() : null;
            if (first) {
                style0 = style;
                color0 = color;
                first = false;
            } else {
                if (style != style0) return true;
                if (!java.util.Objects.equals(color, color0)) return true;
            }
        }
        return false;
    }

    /**
     * Renders a fragment whose segments carry heterogeneous styles as a
     * sequence of styled runs sharing one baseline. Each segment emits its
     * own fill color and font (including a synthesized bold/italic standard-14
     * variant via {@link #applyFontStyle}), so per-segment styling survives a
     * save→reload round trip. No intra-fragment word wrapping is performed —
     * styled rich runs are assumed short, matching the Aspose generator.
     */
    private double layoutRichSegments(TextFragment tf, ContentStreamBuilder builder,
                                      ResourceBuilder resources, LayoutContext ctx) {
        // Each segment is rendered with its OWN TextState. We deliberately do
        // NOT inherit from tf.getTextState(): in this API that getter aliases
        // segments.get(0), so treating it as a "fragment default" would leak
        // the first segment's style/color onto the others. Unset properties
        // fall back to the engine defaults instead.

        // First pass: size the line (largest segment font) and total width
        // (for center/right alignment).
        double maxSize = DEFAULT_FONT_SIZE;
        double totalWidth = 0;
        for (TextSegment seg : tf.getSegments()) {
            String segText = seg.getText();
            if (segText == null || segText.isEmpty()) continue;
            TextState st = seg.getTextState();
            double size = (st != null && st.getFontSize() > 0) ? st.getFontSize() : DEFAULT_FONT_SIZE;
            int style = st != null ? st.getFontStyle() : 0;
            String base = (st != null && st.getFontName() != null) ? st.getFontName() : DEFAULT_FONT;
            String styled = applyFontStyle(base, style);
            maxSize = Math.max(maxSize, size);
            totalWidth += TextLayoutHelper.measureTextWidth(segText, styled, size);
        }

        double lineHeight = TextLayoutHelper.getLineHeight(DEFAULT_FONT, maxSize);
        if (!ctx.hasSpace(lineHeight)) {
            return 0;
        }
        double baselineY = ctx.getCursorY() - lineHeight;

        double availWidth = ctx.getAvailableWidth();
        double x = ctx.getContentLeft();
        HorizontalAlignment align = tf.getHorizontalAlignment();
        if (align == HorizontalAlignment.Center) {
            x = ctx.getContentLeft() + (availWidth - totalWidth) / 2.0;
        } else if (align == HorizontalAlignment.Right) {
            x = ctx.getContentRight() - totalWidth;
        }

        // Second pass: emit one styled run per segment.
        for (TextSegment seg : tf.getSegments()) {
            String segText = seg.getText();
            if (segText == null || segText.isEmpty()) continue;
            TextState st = seg.getTextState();
            double size = (st != null && st.getFontSize() > 0) ? st.getFontSize() : DEFAULT_FONT_SIZE;
            int style = st != null ? st.getFontStyle() : 0;
            String base = (st != null && st.getFontName() != null) ? st.getFontName() : DEFAULT_FONT;
            Color color = st != null ? st.getForegroundColor() : null;
            String styled = applyFontStyle(base, style);
            String fontResource = registerFontResource(st, styled, resources, builder);

            builder.beginText();
            if (color != null) {
                emitColor(color, builder, true);
            }
            builder.setFont(fontResource, size);
            builder.moveText(x, baselineY);
            builder.showText(segText);
            builder.endText();

            x += TextLayoutHelper.measureTextWidth(segText, styled, size);
        }

        ctx.advanceCursor(lineHeight);
        // Publish baseline so a following inline-continuation paragraph aligns.
        lastLineBaselineY = baselineY;
        lastLineRightX = x;
        lastLineHeight = lineHeight;
        return lineHeight;
    }

    /**
     * Maps a base font name + {@link org.aspose.pdf.text.FontStyles} bitmask
     * to a styled BaseFont name. Standard-14 families map to their canonical
     * variant ("Helvetica" + Bold → "Helvetica-Bold"); other families get a
     * "<name>,Bold" suffix so the weight is still encoded in the name and
     * recoverable on reload even when the exact face is substituted.
     */
    private String applyFontStyle(String base, int style) {
        boolean bold = (style & org.aspose.pdf.text.FontStyles.Bold) != 0;
        boolean italic = (style & org.aspose.pdf.text.FontStyles.Italic) != 0;
        if ((!bold && !italic) || base == null) {
            return base;
        }
        String lower = base.toLowerCase();
        if (lower.contains("bold") || lower.contains("italic") || lower.contains("oblique")) {
            return base;   // already carries a style marker
        }
        String family = standard14Family(lower);
        if (family != null) {
            return styledStandard14(family, bold, italic);
        }
        String suffix = (bold && italic) ? "BoldItalic" : (bold ? "Bold" : "Italic");
        return base + "," + suffix;
    }

    /** Canonical standard-14 family for a (lower-cased) base name, or null. */
    private String standard14Family(String lowerName) {
        if (lowerName.startsWith("helvetica") || lowerName.startsWith("arial")) return "Helvetica";
        if (lowerName.startsWith("times")) return "Times";
        if (lowerName.startsWith("courier")) return "Courier";
        return null;
    }

    /** Standard-14 styled variant name for a family + bold/italic flags. */
    private String styledStandard14(String family, boolean bold, boolean italic) {
        switch (family) {
            case "Helvetica":
                if (bold && italic) return "Helvetica-BoldOblique";
                if (bold) return "Helvetica-Bold";
                return "Helvetica-Oblique";
            case "Courier":
                if (bold && italic) return "Courier-BoldOblique";
                if (bold) return "Courier-Bold";
                return "Courier-Oblique";
            case "Times":
                if (bold && italic) return "Times-BoldItalic";
                if (bold) return "Times-Bold";
                return "Times-Italic";
            default:
                return family;
        }
    }

    /**
     * Returns the marker to print for a note. A user-supplied override
     * ({@link Note#setText} with non-empty value) wins; otherwise the next
     * auto-number from the appropriate counter is used.
     */
    private String resolveNoteMarker(Note note, boolean isEndnote) {
        String override = note.getText();
        if (override != null && !override.isEmpty()) {
            return override;
        }
        return String.valueOf(isEndnote ? nextEndnoteNumber++ : nextFootnoteNumber++);
    }

    /**
     * Flattens a note's body paragraphs into a single string. Only
     * {@link TextFragment} paragraphs are supported — richer paragraphs
     * (tables, floating boxes inside a note body) would need separate
     * handling but the tests in scope don't exercise that.
     */
    private String extractNoteBody(Note note) {
        StringBuilder sb = new StringBuilder();
        if (note == null) return "";
        for (org.aspose.pdf.BaseParagraph p : note.getParagraphs()) {
            if (p instanceof TextFragment) {
                String t = ((TextFragment) p).getText();
                if (t == null || t.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * Draws {@code marker} as superscript: smaller font, baseline raised by
     * a fraction of the body font size. Position is the end of the last
     * line + a small gap so the marker does not collide with the body text.
     */
    private void drawSuperscriptMarker(ContentStreamBuilder builder,
                                       String fontResource,
                                       double bodyFontSize,
                                       double atX,
                                       double bodyBaselineY,
                                       String marker) {
        if (marker == null || marker.isEmpty()) return;
        double size = Math.max(4.0, bodyFontSize * SUPERSCRIPT_FONT_RATIO);
        double y = bodyBaselineY + bodyFontSize * SUPERSCRIPT_RAISE_RATIO;
        // Aspose puts the marker flush against the body text (no gap), so
        // a 1pt gap shifts the superscript right and accumulates pHash bit
        // flips. Match Aspose by sitting on the exact end-of-line X.
        builder.beginText();
        builder.setFont(fontResource, size);
        builder.moveText(atX, y);
        builder.showText(marker);
        builder.endText();
    }

    /**
     * Renders the per-page footnote block: a thin horizontal separator
     * followed by each queued footnote as "marker body" on its own (possibly
     * wrapped) line, anchored at the bottom of the content area. Clears
     * {@link #pendingFootnotes} afterwards.
     */
    private void layoutFootnoteBlockIfAny(ContentStreamBuilder builder,
                                          ResourceBuilder resources,
                                          LayoutContext ctx) {
        if (pendingFootnotes.isEmpty()) return;
        drawNoteBlock(pendingFootnotes, builder, resources, ctx);
        pendingFootnotes.clear();
    }

    /**
     * Renders the document-level endnote block on the last page. Drains
     * {@link #pendingEndnotes}.
     */
    private void layoutEndnoteBlockIfLastPage(ContentStreamBuilder builder,
                                              ResourceBuilder resources,
                                              LayoutContext ctx) {
        if (pendingEndnotes.isEmpty()) return;
        if (currentPageNumber < totalPageCount) return;
        drawNoteBlock(pendingEndnotes, builder, resources, ctx);
        pendingEndnotes.clear();
    }

    /**
     * Shared note-block renderer. Anchors the block at the bottom of the
     * content area: short separator above, then each pending note as a
     * superscript marker followed by the body text on its own (possibly
     * wrapped) line. Notes that overflow the available bottom space are
     * silently dropped.
     */
    private void drawNoteBlock(List<PendingNote> notes,
                               ContentStreamBuilder builder,
                               ResourceBuilder resources,
                               LayoutContext ctx) {
        double bodyFontSize = DEFAULT_FONT_SIZE;
        String fontName = DEFAULT_FONT;
        String fontResource = resources.addFont(fontName);
        builder.registerFont(fontName);
        double noteSize = bodyFontSize * NOTE_FONT_RATIO;
        double noteLineHeight = TextLayoutHelper.getLineHeight(fontName, noteSize);
        double availWidth = ctx.getAvailableWidth();

        // Pre-wrap each note's body lines. The marker sits as superscript at
        // the start of the FIRST wrapped line of each note; continuation
        // lines hang under the body text (no marker).
        List<RenderedNoteLine> lines = new ArrayList<>();
        for (PendingNote pn : notes) {
            // Width consumed by the superscript marker so the body wraps
            // around it on the first line.
            String marker = pn.marker == null ? "" : pn.marker;
            double markerWidth = TextLayoutHelper.measureTextWidth(
                    marker, fontName, noteSize * SUPERSCRIPT_FONT_RATIO);
            double firstLineWidth = Math.max(1, availWidth - markerWidth);
            List<String> wrappedFirst = TextLayoutHelper.wrapText(
                    pn.body, fontName, noteSize, firstLineWidth);
            boolean firstSeen = false;
            if (wrappedFirst.isEmpty()) {
                lines.add(new RenderedNoteLine(marker, "", markerWidth));
                continue;
            }
            for (String w : wrappedFirst) {
                if (!firstSeen) {
                    lines.add(new RenderedNoteLine(marker, w, markerWidth));
                    firstSeen = true;
                } else {
                    lines.add(new RenderedNoteLine("", w, markerWidth));
                }
            }
        }
        if (lines.isEmpty()) return;

        double separatorGap = 4.0;
        double blockHeight = separatorGap + lines.size() * noteLineHeight + 2.0;
        double blockTop = ctx.getContentBottom() + blockHeight;
        double available = ctx.getCursorY() - ctx.getContentBottom();
        if (blockHeight > available) {
            blockTop = ctx.getCursorY();
        }

        // Short hairline separator above the block (filled rectangle —
        // ContentStreamBuilder exposes no moveTo/lineTo).
        double sepY = blockTop - 1.0;
        builder.rectangle(ctx.getContentLeft(), sepY, NOTE_SEPARATOR_WIDTH, 0.5);
        builder.fill();

        double y = blockTop - separatorGap - noteLineHeight;
        double leftX = ctx.getContentLeft();
        for (RenderedNoteLine rnl : lines) {
            if (y < ctx.getContentBottom()) break;
            // Superscript marker (only on the first line of each note).
            if (!rnl.marker.isEmpty()) {
                double supSize = Math.max(4.0, noteSize * SUPERSCRIPT_FONT_RATIO);
                double supY = y + noteSize * SUPERSCRIPT_RAISE_RATIO;
                builder.beginText();
                builder.setFont(fontResource, supSize);
                builder.moveText(leftX, supY);
                builder.showText(rnl.marker);
                builder.endText();
            }
            // Body — first line shifts right by the marker width, continuation
            // lines align with the first body character.
            if (!rnl.body.isEmpty()) {
                double bodyX = leftX + rnl.indent;
                builder.beginText();
                builder.setFont(fontResource, noteSize);
                builder.moveText(bodyX, y);
                builder.showText(rnl.body);
                builder.endText();
            }
            y -= noteLineHeight;
        }
    }

    /** One rendered line of the footnote/endnote block. */
    private static final class RenderedNoteLine {
        final String marker;   // empty for continuation lines
        final String body;
        final double indent;   // left padding for the body (= marker width)
        RenderedNoteLine(String marker, String body, double indent) {
            this.marker = marker;
            this.body = body;
            this.indent = indent;
        }
    }

    /**
     * Resets per-document note counters and queues. Called from
     * {@link org.aspose.pdf.Document#save} before laying out the first
     * page so consecutive saves of the same Document start from {@code 1}.
     */
    /**
     * Picks the right font resource for the current text run. When the
     * caller's {@link TextState} carries an embedded {@link org.aspose.pdf.text.Font}
     * with raw TTF bytes, the resource is registered as a Type0 composite
     * font and the {@link ContentStreamBuilder} is told to encode subsequent
     * {@code showText} calls as 2-byte CIDs. Otherwise registration falls
     * back to the standard Type1/WinAnsi path keyed on font name.
     */
    private String registerFontResource(TextState ts, String fontName,
                                        ResourceBuilder resources,
                                        ContentStreamBuilder builder) {
        return registerFontResource(ts, fontName, resources, builder, null);
    }

    /**
     * Variant of {@link #registerFontResource(TextState, String, ResourceBuilder, ContentStreamBuilder)}
     * that takes an explicit {@code fontOverride}, used by per-fragment
     * font substitution (e.g. SimSun → SimSun-ExtB). When non-null the
     * override is registered as the embedded font; otherwise the resolver
     * falls back to {@code ts.getFont()} exactly as before.
     */
    private String registerFontResource(TextState ts, String fontName,
                                        ResourceBuilder resources,
                                        ContentStreamBuilder builder,
                                        org.aspose.pdf.text.Font fontOverride) {
        org.aspose.pdf.text.Font font = fontOverride != null
                ? fontOverride
                : (ts != null ? ts.getFont() : null);
        if (font != null && font.isEmbedded()
                && font.getFontData() != null && font.getFontData().length > 0) {
            String embeddedRes = resources.addEmbeddedFont(font);
            if (embeddedRes != null) {
                builder.registerFont(font.getName());   // for stable Tf naming
                builder.markFontAsType0(embeddedRes, resources.getType0Reader(embeddedRes));
                return embeddedRes;
            }
        }
        String fontResource = resources.addFont(fontName);
        builder.registerFont(fontName);
        return fontResource;
    }

    /**
     * SimSun-ExtB substitution. When the fragment's text contains
     * supplementary-plane characters (≥ U+10000) and the embedded font is
     * SimSun, return a fresh {@code SimSun-ExtB} Font wrapping the bytes of
     * {@code simsunb.ttf}; otherwise return {@code null} (meaning: keep the
     * caller's Font as-is). The original {@link TextState} is never mutated.
     */
    private org.aspose.pdf.text.Font computeExtBOverride(TextState ts, String text) {
        if (ts == null || text == null || text.isEmpty()) return null;
        org.aspose.pdf.text.Font origFont = ts.getFont();
        if (origFont == null || !origFont.isEmbedded()) return null;
        if (origFont.getFontData() == null || origFont.getFontData().length == 0) return null;
        String name = origFont.getName();
        if (name == null || !name.equalsIgnoreCase("SimSun")) return null;
        if (!hasSupplementaryChar(text)) return null;

        byte[] extBBytes = simsunExtBBytes();
        if (extBBytes == null) return null;

        org.aspose.pdf.text.Font extB = new org.aspose.pdf.text.Font("SimSun-ExtB");
        extB.setFontData(extBBytes);
        extB.setEmbedded(true);
        return extB;
    }

    /** {@code true} when any UTF-16 high-surrogate appears in {@code s}. */
    private static boolean hasSupplementaryChar(String s) {
        for (int i = 0, n = s.length(); i < n; i++) {
            if (Character.isHighSurrogate(s.charAt(i))) return true;
        }
        return false;
    }

    /** Lazy single-load cache for simsunb.ttf. {@code null} if not on disk. */
    private static volatile byte[] CACHED_SIMSUN_EXTB;
    private static volatile boolean SIMSUN_EXTB_PROBED;

    private static byte[] simsunExtBBytes() {
        if (SIMSUN_EXTB_PROBED) return CACHED_SIMSUN_EXTB;
        synchronized (LayoutEngine.class) {
            if (!SIMSUN_EXTB_PROBED) {
                CACHED_SIMSUN_EXTB = org.aspose.pdf.engine.font.ttf
                        .FontDiskLookup.loadByExactBasename("simsunb");
                SIMSUN_EXTB_PROBED = true;
            }
        }
        return CACHED_SIMSUN_EXTB;
    }

    public void resetNoteState() {
        nextFootnoteNumber = 1;
        nextEndnoteNumber = 1;
        pendingFootnotes.clear();
        pendingEndnotes.clear();
        java.util.Arrays.fill(headingCounters, 0);
        lastLineBaselineY = Double.NaN;
        lastLineRightX = Double.NaN;
        lastLineHeight = Double.NaN;
        inlineFirstLineX = Double.NaN;
    }

    /**
     * Wraps {@code text} so that the first output line fits within
     * {@code firstWidth} (typically the slack on the previous paragraph's
     * last line) and subsequent lines fit within {@code restWidth} (the
     * regular content width). Pure-greedy: words go on the current line
     * as long as they fit, then overflow to the next.
     */
    private static List<String> wrapAsymmetric(String text, String fontName, double fontSize,
                                               double firstWidth, double restWidth) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) return result;
        String[] words = text.split(" ", -1);
        double spaceW = TextLayoutHelper.measureTextWidth(" ", fontName, fontSize);
        StringBuilder cur = new StringBuilder();
        double curWidth = 0;
        double maxWidth = firstWidth;
        for (String word : words) {
            double w = TextLayoutHelper.measureTextWidth(word, fontName, fontSize);
            double add = cur.length() == 0 ? w : spaceW + w;
            if (cur.length() == 0 || curWidth + add <= maxWidth) {
                if (cur.length() > 0) cur.append(' ');
                cur.append(word);
                curWidth += add;
            } else {
                result.add(cur.toString());
                cur.setLength(0);
                cur.append(word);
                curWidth = w;
                maxWidth = restWidth;   // subsequent lines use the full width
            }
        }
        if (cur.length() > 0) result.add(cur.toString());
        return result;
    }

    /**
     * Bumps the auto-sequence counter for {@code level} and returns the
     * dotted marker ("1", "1.1", "1.2.1", …) joined from levels 1..L.
     * Counters at depths greater than {@code level} are reset, matching the
     * outline behaviour used in Word/Aspose where a new H2 inside H1 #2
     * starts at "2.1" not "2.6".
     */
    private String advanceHeadingMarker(int level) {
        if (level < 1) level = 1;
        if (level > MAX_HEADING_LEVELS) level = MAX_HEADING_LEVELS;
        headingCounters[level - 1]++;
        for (int i = level; i < MAX_HEADING_LEVELS; i++) headingCounters[i] = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < level; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(headingCounters[i]);
        }
        return sb.toString();
    }

    /**
     * Lays out a Heading paragraph, rendering it as a TOC entry with optional
     * dot leader line and page number.
     * <p>
     * The heading text is indented based on its level (level * 20pt).
     * When the heading has a TOC page set, a dot leader and page number
     * are rendered at the right margin.
     * </p>
     *
     * @param heading   the heading to lay out
     * @param builder   the content stream builder
     * @param resources the resource builder
     * @param ctx       the layout context
     * @return the total height consumed
     */
    public double layoutHeading(Heading heading, ContentStreamBuilder builder,
                                 ResourceBuilder resources, LayoutContext ctx) {
        // Determine the display text from segments or text property
        String displayText = buildHeadingText(heading);
        if (displayText == null || displayText.isEmpty()) {
            return 0;
        }

        // Auto-numbered headings get a hierarchical prefix ("1", "1.1", "1.1.1", …)
        // joined with three spaces — matches the Aspose render template
        // (`1   Primary Level Heading`, `1.1   Secondary…`).
        if (heading.isAutoSequence()) {
            String marker = advanceHeadingMarker(heading.getLevel());
            displayText = marker + "   " + displayText;
        }

        // Get font info from heading's TextState or segments
        TextState ts = heading.getTextState();
        String fontName = DEFAULT_FONT;
        double fontSize = DEFAULT_FONT_SIZE;
        Color fgColor = null;

        // If segments have TextState, use the first segment's style
        if (!heading.getSegments().isEmpty()) {
            TextSegment firstSeg = heading.getSegments().get(0);
            if (firstSeg.getTextState() != null) {
                ts = firstSeg.getTextState();
            }
        }

        if (ts != null) {
            if (ts.getFontName() != null) {
                fontName = ts.getFontName();
            }
            if (ts.getFontSize() > 0) {
                fontSize = ts.getFontSize();
            }
            fgColor = ts.getForegroundColor();
        }

        // Register font (Type0 embedded path when applicable, Type1 otherwise).
        String fontResource = registerFontResource(ts, fontName, resources, builder);

        double lineHeight = TextLayoutHelper.getLineHeight(fontName, fontSize);

        if (!ctx.hasSpace(lineHeight)) {
            LOG.fine("Not enough space for heading; stopping layout");
            return 0;
        }

        // Heading indent: only the TOC mode applies the per-level shift, so
        // normal headings (`tocPage == null`) line up with the body text.
        boolean isTocEntry = heading.getTocPage() != null;
        double indent = isTocEntry ? (heading.getLevel() - 1) * 20.0 : 0.0;
        double lineX = ctx.getContentLeft() + indent;
        double availWidth = ctx.getAvailableWidth() - indent;
        String pageNumberStr = "";
        double pageNumberWidth = 0;
        double dotWidth = TextLayoutHelper.measureTextWidth(".", fontName, fontSize);

        if (isTocEntry && heading.getDestinationPage() != null) {
            int destPageNum = heading.getDestinationPage().getNumber();
            pageNumberStr = String.valueOf(destPageNum);
            pageNumberWidth = TextLayoutHelper.measureTextWidth(pageNumberStr, fontName, fontSize);
        }

        // Measure text width
        double textWidth = TextLayoutHelper.measureTextWidth(displayText, fontName, fontSize);

        // Render heading text
        builder.beginText();
        if (fgColor != null) {
            emitColor(fgColor, builder, true);
        }
        builder.setFont(fontResource, fontSize);
        builder.moveText(lineX, ctx.getCursorY() - lineHeight);
        builder.showText(displayText);
        builder.endText();

        // Render dot leader and page number if in TOC mode
        if (isTocEntry && !pageNumberStr.isEmpty()) {
            double dotsStartX = lineX + textWidth + dotWidth;
            double pageNumX = ctx.getContentRight() - pageNumberWidth;
            double dotsEndX = pageNumX - dotWidth;

            // Build dot leader string
            if (dotsEndX > dotsStartX && dotWidth > 0) {
                int dotCount = (int) ((dotsEndX - dotsStartX) / dotWidth);
                StringBuilder dots = new StringBuilder();
                for (int i = 0; i < dotCount; i++) {
                    dots.append('.');
                }
                if (dots.length() > 0) {
                    builder.beginText();
                    if (fgColor != null) {
                        emitColor(fgColor, builder, true);
                    }
                    builder.setFont(fontResource, fontSize);
                    builder.moveText(dotsStartX, ctx.getCursorY() - lineHeight);
                    builder.showText(dots.toString());
                    builder.endText();
                }
            }

            // Render page number at right margin
            builder.beginText();
            if (fgColor != null) {
                emitColor(fgColor, builder, true);
            }
            builder.setFont(fontResource, fontSize);
            builder.moveText(pageNumX, ctx.getCursorY() - lineHeight);
            builder.showText(pageNumberStr);
            builder.endText();
        }

        ctx.advanceCursor(lineHeight);
        return lineHeight;
    }

    /**
     * Builds the display text for a heading from its segments or text property.
     *
     * @param heading the heading
     * @return the display text, or empty string
     */
    private String buildHeadingText(Heading heading) {
        if (!heading.getSegments().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TextSegment seg : heading.getSegments()) {
                if (seg.getText() != null) {
                    sb.append(seg.getText());
                }
            }
            return sb.toString();
        }
        return heading.getText() != null ? heading.getText() : "";
    }

    /**
     * Lays out a Table with borders, backgrounds, and cell content.
     *
     * @param table     the table
     * @param builder   the content stream builder
     * @param resources the resource builder
     * @param ctx       the layout context
     * @return the total height consumed
     */
    public double layoutTable(Table table, ContentStreamBuilder builder,
                               ResourceBuilder resources, LayoutContext ctx) {
        double tableStartY = ctx.getCursorY();
        double tableX = ctx.getContentLeft();

        // Parse column widths. If none are specified, Aspose auto-sizes the table:
        // the number of columns is derived from the widest row (counting colspans)
        // and the available content width is distributed evenly across them.
        double[] colWidths = parseColumnWidths(table.getColumnWidths());
        if (colWidths.length == 0) {
            int columnCount = countColumns(table);
            if (columnCount == 0) {
                LOG.fine("Table has no rows/cells; nothing to lay out");
                return 0;
            }
            double available = ctx.getContentRight() - tableX;
            if (available <= 0) {
                available = columnCount * DEFAULT_AUTO_COLUMN_WIDTH;
            }
            double each = available / columnCount;
            colWidths = new double[columnCount];
            for (int i = 0; i < columnCount; i++) {
                colWidths[i] = each;
            }
        }
        double totalTableWidth = 0;
        for (double w : colWidths) {
            totalTableWidth += w;
        }

        // Default cell padding
        MarginInfo defaultPadding = table.getDefaultCellPadding();
        double padTop = defaultPadding != null ? defaultPadding.getTop() : DEFAULT_CELL_PADDING;
        double padBottom = defaultPadding != null ? defaultPadding.getBottom() : DEFAULT_CELL_PADDING;
        double padLeft = defaultPadding != null ? defaultPadding.getLeft() : DEFAULT_CELL_PADDING;
        double padRight = defaultPadding != null ? defaultPadding.getRight() : DEFAULT_CELL_PADDING;

        double totalHeight = 0;

        for (Row row : table.getRows()) {
            // Calculate row height based on cell content
            double rowHeight = calculateRowHeight(row, colWidths, resources, padTop, padBottom,
                    padLeft, padRight, table);

            if (row.getFixedRowHeight() > 0) {
                rowHeight = row.getFixedRowHeight();
            }
            if (row.getMinRowHeight() > 0 && rowHeight < row.getMinRowHeight()) {
                rowHeight = row.getMinRowHeight();
            }

            if (!ctx.hasSpace(rowHeight)) {
                LOG.fine("Not enough space for table row; stopping table layout");
                break;
            }

            double cellY = ctx.getCursorY();
            double cellX = tableX;
            int cellIndex = 0;

            for (Cell cell : row.getCells()) {
                if (cellIndex >= colWidths.length) {
                    break;
                }

                // Calculate cell width (with colspan)
                double cellWidth = 0;
                for (int i = cellIndex; i < Math.min(cellIndex + cell.getColSpan(), colWidths.length); i++) {
                    cellWidth += colWidths[i];
                }

                // Draw cell background
                Color cellBg = cell.getBackgroundColor();
                if (cellBg == null) {
                    cellBg = row.getBackgroundColor();
                }
                if (cellBg == null) {
                    cellBg = table.getBackgroundColor();
                }
                if (cellBg != null) {
                    builder.saveState();
                    emitColor(cellBg, builder, true);
                    builder.rectangle(cellX, cellY - rowHeight, cellWidth, rowHeight);
                    builder.fill();
                    builder.restoreState();
                }

                // Draw cell border
                BorderInfo cellBorder = cell.getBorder();
                if (cellBorder == null) {
                    cellBorder = table.getDefaultCellBorder();
                }
                if (cellBorder != null) {
                    drawCellBorder(builder, cellBorder, cellX, cellY, cellWidth, rowHeight);
                }

                // Layout cell content
                MarginInfo cellPadding = cell.getMargin();
                double cpTop = cellPadding != null ? cellPadding.getTop() : padTop;
                double cpLeft = cellPadding != null ? cellPadding.getLeft() : padLeft;
                double cpRight = cellPadding != null ? cellPadding.getRight() : padRight;

                // Create a temporary sub-context for cell content
                double innerX = cellX + cpLeft;
                double innerY = cellY - cpTop;
                double innerWidth = cellWidth - cpLeft - cpRight;

                layoutCellContent(cell, builder, resources, innerX, innerY, innerWidth);

                cellX += cellWidth;
                cellIndex += cell.getColSpan();
            }

            ctx.advanceCursor(rowHeight);
            totalHeight += rowHeight;
        }

        // Draw table border
        BorderInfo tableBorder = table.getBorder();
        if (tableBorder != null) {
            drawCellBorder(builder, tableBorder, tableX, tableStartY,
                    totalTableWidth, totalHeight);
        }

        return totalHeight;
    }

    /**
     * Applies stamp overlays (TextStamp, ImageStamp, PageNumberStamp) to the page.
     * <p>
     * Stamps are rendered at their specified positions independent of the normal
     * layout flow. Text stamps use the specified font and color.
     * </p>
     *
     * @param page      the page
     * @param builder   the content stream builder
     * @param resources the resource builder
     */
    public void layoutStamps(Page page, ContentStreamBuilder builder, ResourceBuilder resources) {
        // Stamps would be applied via page.addStamp() mechanism
        // For now this is a placeholder that can be called externally
        LOG.fine("layoutStamps called (placeholder)");
    }

    /**
     * Lays out a TextStamp at its specified position.
     *
     * @param stamp     the text stamp
     * @param builder   the content stream builder
     * @param resources the resource builder
     * @param pageWidth the page width for alignment
     */
    public void layoutTextStamp(TextStamp stamp, ContentStreamBuilder builder,
                                 ResourceBuilder resources, double pageWidth) {
        if (stamp == null || stamp.getValue() == null || stamp.getValue().isEmpty()) {
            return;
        }

        // Substitute page number variables in stamp text
        String stampText = stamp.getValue();
        if (stampText.contains("$p") || stampText.contains("$P")) {
            stampText = stampText.replace("$P", String.valueOf(totalPageCount));
            stampText = stampText.replace("$p", String.valueOf(currentPageNumber));
        }

        TextState ts = stamp.getTextState();
        String fontName = (ts != null && ts.getFontName() != null) ? ts.getFontName() : DEFAULT_FONT;
        double fontSize = (ts != null && ts.getFontSize() > 0) ? ts.getFontSize() : DEFAULT_FONT_SIZE;

        String fontResource = resources.addFont(fontName);
        builder.registerFont(fontName);

        double x = stamp.getXIndent();
        double y = stamp.getYIndent();

        // Handle horizontal alignment
        if (stamp.getHorizontalAlignment() == HorizontalAlignment.Center) {
            double textWidth = TextLayoutHelper.measureTextWidth(stampText, fontName, fontSize);
            x = (pageWidth - textWidth) / 2.0;
        } else if (stamp.getHorizontalAlignment() == HorizontalAlignment.Right) {
            double textWidth = TextLayoutHelper.measureTextWidth(stampText, fontName, fontSize);
            x = pageWidth - textWidth - stamp.getXIndent();
        }

        builder.saveState();
        Color fgColor = (ts != null) ? ts.getForegroundColor() : null;
        if (fgColor != null) {
            emitColor(fgColor, builder, true);
        }
        builder.beginText();
        builder.setFont(fontResource, fontSize);
        builder.moveText(x, y);
        builder.showText(stampText);
        builder.endText();
        builder.restoreState();
    }

    // ---- Private helpers ----

    /**
     * Lays out an HtmlFragment by stripping HTML tags and rendering as plain text.
     */
    private double layoutHtmlFragment(HtmlFragment html, ContentStreamBuilder builder,
                                       ResourceBuilder resources, LayoutContext ctx) {
        String htmlText = html.getHtmlText();
        if (htmlText == null || htmlText.isEmpty()) {
            return 0;
        }
        // Simple tag stripping to get plain text
        String plainText = stripHtmlTags(htmlText);
        if (plainText.isEmpty()) {
            return 0;
        }
        // Create a temporary TextFragment from the plain text
        TextFragment tf = new TextFragment(plainText);
        tf.setHorizontalAlignment(html.getHorizontalAlignment());
        return layoutTextFragment(tf, builder, resources, ctx);
    }

    /**
     * Strips HTML tags from a string, converting br/p tags to newlines.
     */
    private String stripHtmlTags(String html) {
        String text = html;
        text = BR_TAG.matcher(text).replaceAll("\n");
        text = P_OPEN_TAG.matcher(text).replaceAll("\n");
        text = P_CLOSE_TAG.matcher(text).replaceAll("");
        text = ANY_TAG.matcher(text).replaceAll("");
        // Entities are literal substitutions — no regex needed.
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&nbsp;", " ");
        text = text.replace("&quot;", "\"");
        return text.trim();
    }

    /**
     * Lays out a FloatingBox by rendering its contained paragraphs at the box's position.
     */
    private double layoutFloatingBox(FloatingBox box, ContentStreamBuilder builder,
                                      ResourceBuilder resources, LayoutContext ctx) {
        double savedX = ctx.getCursorX();
        double savedY = ctx.getCursorY();

        // If the box has explicit position, use it
        if (box.getLeft() > 0 || box.getTop() > 0) {
            ctx.setCursorX(ctx.getContentLeft() + box.getLeft());
            if (box.getTop() > 0) {
                ctx.setCursorY(ctx.getContentTop() - box.getTop());
            }
        }

        // Draw background
        if (box.getBackgroundColor() != null && box.getWidth() > 0 && box.getHeight() > 0) {
            builder.saveState();
            emitColor(box.getBackgroundColor(), builder, true);
            builder.rectangle(ctx.getCursorX(), ctx.getCursorY() - box.getHeight(),
                    box.getWidth(), box.getHeight());
            builder.fill();
            builder.restoreState();
        }

        double totalHeight = 0;
        Paragraphs paras = box.getParagraphs();
        if (paras != null) {
            for (BaseParagraph para : paras) {
                totalHeight += layoutParagraph(para, builder, resources, ctx);
            }
        }

        // If box has explicit height, use that instead
        if (box.getHeight() > 0) {
            totalHeight = box.getHeight();
        }

        // Restore cursor if this was a positioned box
        if (box.getLeft() > 0 || box.getTop() > 0) {
            ctx.setCursorX(savedX);
            ctx.setCursorY(savedY);
            // Floating boxes don't consume space in the normal flow
            return 0;
        }

        return totalHeight;
    }

    /**
     * Lays out an {@link Image} paragraph by decoding the source file (or
     * stream), packaging it as an Image XObject in the page's resources, and
     * emitting a {@code cm}+{@code Do} pair in the content stream at the
     * current cursor position. Honours {@link Image#getFixWidth()} /
     * {@link Image#getFixHeight()} when set; otherwise falls back to the
     * decoded source dimensions (in points = pixels at 72 DPI).
     *
     * <p>JPEG inputs go through verbatim with {@code /Filter /DCTDecode}
     * (lossless reuse of the source bytes). Other formats (GIF, PNG, BMP)
     * are decoded into a {@link java.awt.image.BufferedImage}, repacked as
     * 8-bit DeviceRGB, and {@code /FlateDecode}-compressed.</p>
     *
     * <p>When {@link Image#isInLineParagraph()} is true, the image renders
     * to the right of the previous paragraph's last text line (sharing the
     * baseline), and {@link #lastLineRightX} is updated so a subsequent
     * inline text fragment continues on the same row.</p>
     */
    private double layoutImage(Image image, ContentStreamBuilder builder,
                                ResourceBuilder resources, LayoutContext ctx) {
        byte[] rawBytes = readImageBytes(image);
        if (rawBytes == null || rawBytes.length == 0) {
            // No file/stream — fall back to the old placeholder behaviour so
            // layouts that mention an Image but don't supply data still
            // reserve vertical space and don't crash.
            return reserveImagePlaceholder(image, ctx);
        }
        DecodedImage decoded = decodeImageBytes(rawBytes);
        if (decoded == null) {
            LOG.warning(() -> "Could not decode image; falling back to placeholder");
            return reserveImagePlaceholder(image, ctx);
        }

        // Default to a 1-pixel = 1-point mapping (matches the implicit Aspose
        // default of treating a `new Image()` as native-size at 72 DPI when no
        // FixWidth/FixHeight is supplied).
        double w = image.getFixWidth() > 0 ? image.getFixWidth() : decoded.width;
        double h = image.getFixHeight() > 0 ? image.getFixHeight() : decoded.height;

        // Determine origin. Inline images sit to the right of the prior
        // paragraph's last baseline (sharing the row); normal-flow images
        // anchor at the content margin.
        boolean inline = !Double.isNaN(inlineFirstLineX);
        double imgX;
        double imgY;
        if (inline) {
            imgX = inlineFirstLineX;
            // Use previous line's height as a rough proxy for the image
            // baseline so the box sits next to the surrounding glyphs.
            double base = !Double.isNaN(lastLineBaselineY) ? lastLineBaselineY : ctx.getCursorY() - h;
            imgY = base;
            inlineFirstLineX = Double.NaN;   // consume
        } else {
            imgX = ctx.getContentLeft();
            imgY = ctx.getCursorY() - h;
        }

        String resName = resources.addImage(image.toString() + "@" + System.identityHashCode(image),
                buildImageXObject(decoded));
        builder.saveState();
        builder.concatMatrix(w, 0, 0, h, imgX, imgY);
        builder.drawXObject(resName);
        builder.restoreState();

        if (inline) {
            // Publish updated inline state so a trailing inline text fragment
            // (e.g. PDFNEWNET_37521) continues on the same row to the right
            // of the image.
            lastLineRightX = imgX + w;
            lastLineBaselineY = imgY;
            lastLineHeight = Math.max(lastLineHeight, h);
            return 0;   // do not advance the cursor for an inline image
        }
        ctx.advanceCursor(h);
        return h;
    }

    /** Falls back to reserving placeholder space when image data is unavailable. */
    private double reserveImagePlaceholder(Image image, LayoutContext ctx) {
        double w = image.getFixWidth() > 0 ? image.getFixWidth() : 100;
        double h = image.getFixHeight() > 0 ? image.getFixHeight() : 100;
        ctx.advanceCursor(h);
        return h;
    }

    /** Reads image bytes from the configured file path or input stream. */
    private byte[] readImageBytes(Image image) {
        try {
            if (image.getFile() != null) {
                return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(image.getFile()));
            }
            if (image.getImageStream() != null) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = image.getImageStream().read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (java.io.IOException e) {
            LOG.warning(() -> "Failed to read image bytes: " + e.getMessage());
        }
        return null;
    }

    /** Decoded image data ready to be wrapped as a PDF Image XObject. */
    private static final class DecodedImage {
        final int width;
        final int height;
        final String filter;        // "DCTDecode" for verbatim JPEG, "FlateDecode" otherwise
        final String colorSpace;    // "DeviceRGB" or "DeviceGray"
        final byte[] encodedBytes;
        DecodedImage(int w, int h, String filter, String cs, byte[] bytes) {
            this.width = w; this.height = h;
            this.filter = filter; this.colorSpace = cs;
            this.encodedBytes = bytes;
        }
    }

    private DecodedImage decodeImageBytes(byte[] raw) {
        // JPEG fast path: forward the source bytes through /DCTDecode so PDF
        // viewers decode them natively (lossless reuse).
        if (raw.length >= 4 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8) {
            int[] dims = readJpegDimensions(raw);
            if (dims != null) {
                String cs = dims[2] == 1 ? "DeviceGray"
                        : dims[2] == 4 ? "DeviceCMYK" : "DeviceRGB";
                return new DecodedImage(dims[0], dims[1], "DCTDecode", cs, raw);
            }
        }
        // General path: decode through ImageIO (covers GIF/PNG/BMP/TIFF/…)
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                    new java.io.ByteArrayInputStream(raw));
            if (bi == null) return null;
            int w = bi.getWidth();
            int h = bi.getHeight();
            byte[] rgb = new byte[w * h * 3];
            int idx = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = bi.getRGB(x, y);
                    // Composite alpha against white so transparent GIF
                    // backgrounds match the surrounding page paper.
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int g = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    if (a < 255) {
                        r = (r * a + 255 * (255 - a)) / 255;
                        g = (g * a + 255 * (255 - a)) / 255;
                        b = (b * a + 255 * (255 - a)) / 255;
                    }
                    rgb[idx++] = (byte) r;
                    rgb[idx++] = (byte) g;
                    rgb[idx++] = (byte) b;
                }
            }
            byte[] compressed = flateCompress(rgb);
            return new DecodedImage(w, h, "FlateDecode", "DeviceRGB", compressed);
        } catch (java.io.IOException e) {
            LOG.warning(() -> "ImageIO failed to decode: " + e.getMessage());
            return null;
        }
    }

    /**
     * Wraps a {@link DecodedImage} into a PDF Image XObject COSStream ready
     * for registration through {@link ResourceBuilder#addImage}.
     */
    private org.aspose.pdf.engine.cos.COSStream buildImageXObject(DecodedImage di) {
        org.aspose.pdf.engine.cos.COSStream s = new org.aspose.pdf.engine.cos.COSStream();
        s.set(COSName.of("Type"), COSName.of("XObject"));
        s.set(COSName.of("Subtype"), COSName.of("Image"));
        s.set(COSName.of("Width"), org.aspose.pdf.engine.cos.COSInteger.valueOf(di.width));
        s.set(COSName.of("Height"), org.aspose.pdf.engine.cos.COSInteger.valueOf(di.height));
        s.set(COSName.of("BitsPerComponent"), org.aspose.pdf.engine.cos.COSInteger.valueOf(8));
        s.set(COSName.of("ColorSpace"), COSName.of(di.colorSpace));
        s.set(COSName.of("Filter"), COSName.of(di.filter));
        s.setEncodedData(di.encodedBytes);
        return s;
    }

    /** Deflate-compress raw bytes for embedding as a /FlateDecode stream. */
    private static byte[] flateCompress(byte[] raw) {
        java.util.zip.Deflater def = new java.util.zip.Deflater();
        def.setInput(raw);
        def.finish();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length / 2);
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            int n = def.deflate(buf);
            out.write(buf, 0, n);
        }
        def.end();
        return out.toByteArray();
    }

    /** Parses JPEG SOFn marker for width/height/components. Mirrors PdfFileMend. */
    private static int[] readJpegDimensions(byte[] data) {
        int i = 2;
        while (i + 3 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) return null;
            int marker = data[i + 1] & 0xFF;
            if (marker >= 0xD0 && marker <= 0xD9) { i += 2; continue; }
            if (marker == 0x01) { i += 2; continue; }
            int segLen = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int h = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                int w = ((data[i + 7] & 0xFF) << 8) | (data[i + 8] & 0xFF);
                int comps = data[i + 9] & 0xFF;
                if (w > 0 && h > 0) return new int[]{w, h, comps};
            }
            i += 2 + segLen;
        }
        return null;
    }

    /**
     * Calculates the height needed for a table row based on its cell content.
     */
    private double calculateRowHeight(Row row, double[] colWidths, ResourceBuilder resources,
                                       double padTop, double padBottom,
                                       double padLeft, double padRight, Table table) {
        double maxHeight = TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_FONT_SIZE)
                + padTop + padBottom;
        int cellIndex = 0;

        for (Cell cell : row.getCells()) {
            if (cellIndex >= colWidths.length) {
                break;
            }

            double cellWidth = 0;
            for (int i = cellIndex; i < Math.min(cellIndex + cell.getColSpan(), colWidths.length); i++) {
                cellWidth += colWidths[i];
            }

            MarginInfo cellPadding = cell.getMargin();
            double cpTop = cellPadding != null ? cellPadding.getTop() : padTop;
            double cpBottom = cellPadding != null ? cellPadding.getBottom() : padBottom;
            double cpLeft = cellPadding != null ? cellPadding.getLeft() : padLeft;
            double cpRight = cellPadding != null ? cellPadding.getRight() : padRight;

            double innerWidth = cellWidth - cpLeft - cpRight;
            double cellContentHeight = measureCellContent(cell, innerWidth);
            double totalCellHeight = cellContentHeight + cpTop + cpBottom;

            if (totalCellHeight > maxHeight) {
                maxHeight = totalCellHeight;
            }

            cellIndex += cell.getColSpan();
        }
        return maxHeight;
    }

    /**
     * Measures the content height of a cell (without padding).
     */
    private double measureCellContent(Cell cell, double innerWidth) {
        double totalHeight = 0;
        Paragraphs paras = cell.getParagraphs();
        if (paras == null || paras.size() == 0) {
            return 0;
        }

        for (BaseParagraph para : paras) {
            if (para instanceof TextFragment) {
                TextFragment tf = (TextFragment) para;
                TextState ts = tf.getTextState();
                String fontName = (ts != null && ts.getFontName() != null) ? ts.getFontName() : DEFAULT_FONT;
                double fontSize = (ts != null && ts.getFontSize() > 0) ? ts.getFontSize() : DEFAULT_FONT_SIZE;

                String text = tf.getText();
                if (text != null && !text.isEmpty()) {
                    List<String> lines = TextLayoutHelper.wrapText(text, fontName, fontSize, innerWidth);
                    double lineHeight = TextLayoutHelper.getLineHeight(fontName, fontSize);
                    totalHeight += lines.size() * lineHeight;
                }
            } else {
                // Default: assume one line height for unknown content
                totalHeight += TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_FONT_SIZE);
            }
        }
        return totalHeight;
    }

    /**
     * Layouts the content inside a cell at the given position.
     */
    private void layoutCellContent(Cell cell, ContentStreamBuilder builder,
                                    ResourceBuilder resources,
                                    double x, double y, double width) {
        Paragraphs paras = cell.getParagraphs();
        if (paras == null || paras.size() == 0) {
            return;
        }

        double currentY = y;

        for (BaseParagraph para : paras) {
            if (para instanceof TextFragment) {
                TextFragment tf = (TextFragment) para;

                // When a fragment is built segment-by-segment (no setText), the
                // top-level getText() is empty but the segments carry the content.
                // Mirror layoutTextFragment so both construction styles render.
                String text = tf.getText();
                if ((text == null || text.isEmpty())
                        && tf.getSegments() != null && !tf.getSegments().isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (TextSegment seg : tf.getSegments()) {
                        if (seg.getText() != null) sb.append(seg.getText());
                    }
                    text = sb.toString();
                }
                if (text == null || text.isEmpty()) {
                    continue;
                }

                // Prefer the first segment's TextState when it carries font info;
                // the fragment-level state is often a barebones placeholder.
                TextState ts = tf.getTextState();
                if (!tf.getSegments().isEmpty() && tf.getSegments().get(0).getTextState() != null) {
                    TextState segTs = tf.getSegments().get(0).getTextState();
                    if (segTs.getFontName() != null || segTs.getFontSize() > 0
                            || segTs.getLineSpacing() > 0) {
                        ts = segTs;
                    }
                }
                String fontName = (ts != null && ts.getFontName() != null) ? ts.getFontName() : DEFAULT_FONT;
                double fontSize = (ts != null && ts.getFontSize() > 0) ? ts.getFontSize() : DEFAULT_FONT_SIZE;

                String fontResource = resources.addFont(fontName);
                builder.registerFont(fontName);

                // Normalize line breaks: a leftover \r would render as '?' in WinAnsi.
                if (text.indexOf('\r') >= 0) {
                    text = text.replace("\r\n", "\n").replace('\r', '\n');
                }

                List<String> lines = TextLayoutHelper.wrapText(text, fontName, fontSize, width);
                double lineHeight = TextLayoutHelper.getLineHeight(fontName, fontSize);

                Color fgColor = (ts != null) ? ts.getForegroundColor() : null;

                for (String line : lines) {
                    double lineX = x;

                    // Handle cell alignment
                    HorizontalAlignment align = cell.getAlignment();
                    if (align == HorizontalAlignment.None) {
                        align = tf.getHorizontalAlignment();
                    }
                    if (align == HorizontalAlignment.Center) {
                        double lineWidth = TextLayoutHelper.measureTextWidth(line, fontName, fontSize);
                        lineX = x + (width - lineWidth) / 2.0;
                    } else if (align == HorizontalAlignment.Right) {
                        double lineWidth = TextLayoutHelper.measureTextWidth(line, fontName, fontSize);
                        lineX = x + width - lineWidth;
                    }

                    builder.beginText();
                    if (fgColor != null) {
                        emitColor(fgColor, builder, true);
                    }
                    builder.setFont(fontResource, fontSize);
                    builder.moveText(lineX, currentY - lineHeight);
                    builder.showText(line);
                    builder.endText();

                    currentY -= lineHeight;
                }
            }
        }
    }

    /**
     * Draws a border around a cell rectangle. Each side is drawn individually
     * based on the BorderInfo's per-side GraphInfo settings.
     */
    private void drawCellBorder(ContentStreamBuilder builder, BorderInfo border,
                                 double x, double y, double width, double height) {
        builder.saveState();

        // Draw top border
        if (border.getTop() != null) {
            BorderInfo.GraphInfo gi = border.getTop();
            if (gi.getColor() != null) {
                emitColor(gi.getColor(), builder, false);
            }
            builder.setLineWidth(gi.getLineWidth());
            builder.rectangle(x, y - gi.getLineWidth() / 2, width, 0);
            builder.stroke();
        }

        // Draw bottom border
        if (border.getBottom() != null) {
            BorderInfo.GraphInfo gi = border.getBottom();
            if (gi.getColor() != null) {
                emitColor(gi.getColor(), builder, false);
            }
            builder.setLineWidth(gi.getLineWidth());
            builder.rectangle(x, y - height + gi.getLineWidth() / 2, width, 0);
            builder.stroke();
        }

        // Draw left border
        if (border.getLeft() != null) {
            BorderInfo.GraphInfo gi = border.getLeft();
            if (gi.getColor() != null) {
                emitColor(gi.getColor(), builder, false);
            }
            builder.setLineWidth(gi.getLineWidth());
            builder.rectangle(x + gi.getLineWidth() / 2, y - height, 0, height);
            builder.stroke();
        }

        // Draw right border
        if (border.getRight() != null) {
            BorderInfo.GraphInfo gi = border.getRight();
            if (gi.getColor() != null) {
                emitColor(gi.getColor(), builder, false);
            }
            builder.setLineWidth(gi.getLineWidth());
            builder.rectangle(x + width - gi.getLineWidth() / 2, y - height, 0, height);
            builder.stroke();
        }

        builder.restoreState();
    }

    /**
     * Emits a color setting operator to the content stream.
     *
     * @param color  the color
     * @param builder the content stream builder
     * @param isFill  true for fill color, false for stroke color
     */
    private void emitColor(Color color, ContentStreamBuilder builder, boolean isFill) {
        if (color == null) {
            return;
        }
        switch (color.getColorSpace()) {
            case RGB:
                if (isFill) {
                    builder.setRGBFillColor(color.getR(), color.getG(), color.getB());
                } else {
                    builder.setRGBStrokeColor(color.getR(), color.getG(), color.getB());
                }
                break;
            case GRAY:
                double[] comp = color.getComponents();
                if (isFill) {
                    builder.setGrayFillColor(comp[0]);
                } else {
                    builder.setGrayStrokeColor(comp[0]);
                }
                break;
            case CMYK:
                // Convert CMYK to RGB for simplicity in content stream
                if (isFill) {
                    builder.setRGBFillColor(color.getR(), color.getG(), color.getB());
                } else {
                    builder.setRGBStrokeColor(color.getR(), color.getG(), color.getB());
                }
                break;
            default:
                break;
        }
    }

    /**
     * Converts a PageInfo.MarginInfo to a MarginInfo.
     */
    private MarginInfo toMarginInfo(PageInfo.MarginInfo piMargin) {
        return new MarginInfo(
                piMargin.getLeft(),
                piMargin.getBottom(),
                piMargin.getRight(),
                piMargin.getTop()
        );
    }

    /**
     * Parses a space-separated column widths string into an array of doubles.
     *
     * @param columnWidths the column widths string (e.g. "100 200 150")
     * @return the parsed widths array, or empty array if null/empty
     */
    /**
     * Determines the number of columns of a table that has no explicit
     * ColumnWidths, by taking the widest row (summing each cell's colspan).
     *
     * @param table the table
     * @return the column count, or 0 if the table has no cells
     */
    private int countColumns(Table table) {
        int max = 0;
        for (Row row : table.getRows()) {
            int count = 0;
            for (Cell cell : row.getCells()) {
                count += Math.max(1, cell.getColSpan());
            }
            if (count > max) {
                max = count;
            }
        }
        return max;
    }

    private double[] parseColumnWidths(String columnWidths) {
        if (columnWidths == null || columnWidths.trim().isEmpty()) {
            return new double[0];
        }
        String[] parts = columnWidths.trim().split("\\s+");
        double[] widths = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                widths[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                LOG.warning("Invalid column width value: " + parts[i] + "; using 50");
                widths[i] = 50;
            }
        }
        return widths;
    }
}
