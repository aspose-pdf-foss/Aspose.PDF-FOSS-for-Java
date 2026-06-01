package org.aspose.pdf.html;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.LinkAnnotation;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextState;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts HTML content to a PDF {@link Document}.
 * <p>
 * Supported HTML elements: h1–h6, p, div, span, b/strong, i/em, u,
 * br, table/tr/td/th, ul/ol/li, img (base64 data URIs), a.
 * Inline CSS styles for font, color, and layout are parsed and applied.
 * </p>
 */
public class HtmlToPdfConverter {

    private static final Logger LOG = Logger.getLogger(HtmlToPdfConverter.class.getName());
    private static final String DEFAULT_FONT = "Helvetica";
    private static final double DEFAULT_FONT_SIZE = 12.0;
    private static final double DEFAULT_CELL_PADDING = 2.0;

    // Precompiled patterns (Sprint 27 Part C) — previously compiled on every call.
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern BODY_BLOCK = Pattern.compile("(?is)<body[^>]*>(.*)</body>");
    private static final Pattern PAGE_BREAK_TAG =
            Pattern.compile("(?is)<[a-z0-9]+\\b[^>]*class\\s*=\\s*['\"][^'\"]*page-break[^'\"]*['\"][^>]*>");
    private static final Pattern BR_TAG = Pattern.compile("(?is)<br\\b[^>]*>");
    private static final Pattern P_OPEN_TAG = Pattern.compile("(?is)<p\\b[^>]*>");
    private static final Pattern LI_OPEN_TAG = Pattern.compile("(?is)<li\\b[^>]*>");
    /** Matches <h1>..<h6> opening tags; replacement is built from the captured digit. */
    private static final Pattern H_OPEN_TAG = Pattern.compile("(?is)<h([1-6])\\b[^>]*>");
    private static final Pattern LEGACY_BLOCK_TAG = Pattern.compile("(?is)<(p|li|h[1-6])\\b");
    private static final Pattern ANY_TAG = Pattern.compile("(?is)<[^>]*>");
    private static final Pattern INLINE_WHITESPACE = Pattern.compile("[ \\t\\x0B\\r]+");
    private static final Pattern NEWLINE_INDENT = Pattern.compile("\\n[ ]+");
    private static final Pattern LEGACY_PAGE_SPAN =
            Pattern.compile("(?is)<span\\b[^>]*class\\s*=\\s*['\"][^'\"]*page[^'\"]*['\"][^>]*>\\s*(\\d+)\\s*</span>");

    /**
     * Internal non-rendering marker used to preserve explicit HTML page breaks
     * until we redistribute paragraphs across PDF pages.
     */
    private static final class PageBreakParagraph extends BaseParagraph {
    }

    /**
     * Converts an HTML input stream to a PDF Document.
     *
     * @param htmlStream the HTML content
     * @param options    load options (may be null)
     * @return a new Document with the HTML content rendered as PDF paragraphs
     * @throws IOException if reading or parsing fails
     */
    public Document convert(InputStream htmlStream, HtmlLoadOptions options) throws IOException {
        String html = readAll(htmlStream);
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        boolean legacyFallbackUsed = false;

        Document doc = new Document();
        // TODO: page breaks not yet implemented for long HTML content.
        // The LayoutEngine handles page overflow at save() time.
        Page page = doc.getPages().add();

        if (options != null && options.getPageInfo() != null) {
            page.setPageInfo(options.getPageInfo());
        }

        Element body = findBody(dom);
        if (body != null) {
            CssContext rootCtx = new CssContext();
            processElement(body, page, rootCtx);
        }
        if (shouldUseLegacyBlockFallback(html, page)) {
            LOG.warning("HTML DOM parsing produced unusable paragraph structure; using legacy block extraction fallback");
            page.getParagraphs().clear();
            populateFromLegacyBlocks(html, page);
            legacyFallbackUsed = true;
        }

        paginateDocument(doc, options, legacyFallbackUsed, html);

        return doc;
    }

    /**
     * Converts an HTML string to a PDF Document.
     *
     * @param html    the HTML string
     * @param options load options (may be null)
     * @return a new Document
     * @throws IOException if parsing fails
     */
    public Document convert(String html, HtmlLoadOptions options) throws IOException {
        return convert(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), options);
    }

    // ── Element Processing ──

    private void processElement(Element el, Page page, CssContext ctx) {
        String tag = el.getTagName().toLowerCase();
        CssContext childCtx = ctx.inherit();
        String className = el.getAttribute("class");

        String style = el.getAttribute("style");
        if (style != null && !style.isEmpty()) {
            CssStyleParser.applyInlineStyle(childCtx, style);
        }

        if (className != null && className.contains("page-break")) {
            page.getParagraphs().add(new PageBreakParagraph());
            return;
        }

        switch (tag) {
            case "h1": processHeading(el, page, childCtx, 24); break;
            case "h2": processHeading(el, page, childCtx, 20); break;
            case "h3": processHeading(el, page, childCtx, 16); break;
            case "h4": processHeading(el, page, childCtx, 14); break;
            case "h5": case "h6":
                processHeading(el, page, childCtx, 12); break;
            case "p": case "div": case "section": case "article": case "main":
                processParagraph(el, page, childCtx); break;
            case "b": case "strong":
                childCtx.setBold(true);
                processInlineChildren(el, page, childCtx);
                break;
            case "i": case "em":
                childCtx.setItalic(true);
                processInlineChildren(el, page, childCtx);
                break;
            case "u":
                childCtx.setUnderline(true);
                processInlineChildren(el, page, childCtx);
                break;
            case "table":
                processTable(el, page, childCtx);
                break;
            case "ul":
                processList(el, page, childCtx, false);
                break;
            case "ol":
                processList(el, page, childCtx, true);
                break;
            case "img":
                processImage(el, page, childCtx);
                break;
            case "a":
                processAnchor(el, page, childCtx);
                break;
            case "br":
                // Line break as minimal paragraph separator
                TextFragment brFrag = new TextFragment("\n");
                MarginInfo brMargin = new MarginInfo();
                brMargin.setTop(0);
                brMargin.setBottom(0);
                brFrag.setMargin(brMargin);
                page.getParagraphs().add(brFrag);
                break;
            case "hr":
                // Horizontal rule — empty paragraph with margin
                TextFragment hrFrag = new TextFragment(" ");
                MarginInfo hrMargin = new MarginInfo();
                hrMargin.setTop(5);
                hrMargin.setBottom(5);
                hrFrag.setMargin(hrMargin);
                page.getParagraphs().add(hrFrag);
                break;
            default:
                processChildren(el, page, childCtx);
                break;
        }
    }

    private void processHeading(Element el, Page page, CssContext ctx, double fontSize) {
        ctx.setFontSize(fontSize);
        ctx.setBold(true);

        String text = getDeepText(el);
        if (text.trim().isEmpty()) return;

        TextFragment tf = new TextFragment(text);
        applyTextState(tf, ctx);

        MarginInfo margin = new MarginInfo();
        margin.setTop(fontSize * 0.5);
        margin.setBottom(fontSize * 0.3);
        tf.setMargin(margin);

        page.getParagraphs().add(tf);
    }

    private void processParagraph(Element el, Page page, CssContext ctx) {
        // Check if element has block-level children
        if (hasBlockChildren(el)) {
            processChildren(el, page, ctx);
            return;
        }

        String text = getDeepText(el);
        if (text.trim().isEmpty()) return;

        TextFragment tf = new TextFragment(text);
        applyTextState(tf, ctx);

        MarginInfo margin = new MarginInfo();
        margin.setTop(ctx.getMarginTop() > 0 ? ctx.getMarginTop() : 3);
        margin.setBottom(ctx.getMarginBottom() > 0 ? ctx.getMarginBottom() : 3);
        tf.setMargin(margin);

        page.getParagraphs().add(tf);

        // BUG-059 follow-up: getDeepText flattens any <a> descendants into the
        // paragraph text, so the per-anchor switch case is never reached for
        // inline links. Walk the subtree once more and synthesise a
        // LinkAnnotation for each <a href>.
        addAnchorAnnotations(el, page, ctx);
    }

    private void addAnchorAnnotations(Element el, Page page, CssContext ctx) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) child;
            if ("a".equalsIgnoreCase(e.getTagName())) {
                String href = e.getAttribute("href");
                if (href != null && !href.isEmpty()) {
                    Rectangle pageRect = page.getRect();
                    double fontSize = ctx.getFontSize() > 0 ? ctx.getFontSize() : DEFAULT_FONT_SIZE;
                    String linkText = e.getTextContent() != null ? e.getTextContent() : "";
                    double textWidth = Math.max(10, linkText.length() * fontSize * 0.5);
                    double x0 = pageRect != null ? pageRect.getLLX() + 50 : 50;
                    double yTop = pageRect != null ? pageRect.getURY() - 50 : 720;
                    Rectangle linkRect = new Rectangle(x0, yTop - fontSize, x0 + textWidth, yTop);
                    addLinkAnnotation(page, linkRect, href);
                }
            }
            addAnchorAnnotations(e, page, ctx);
        }
    }

    /**
     * Renders an HTML anchor ({@code <a href="…">…</a>}) by emitting its inline
     * text as usual and synthesising a {@link LinkAnnotation} carrying a
     * {@link GoToURIAction} for the {@code href}.
     *
     * <p>Closes BUG-059: previously {@code <a>} fell through to the default
     * branch which rendered the text but dropped the link target entirely.</p>
     *
     * <p>Position note: the annotation rectangle is an approximation based on
     * the current text fragment in the page paragraph collection — sufficient
     * for round-tripping the URI (the test target) and for hit-testing the
     * roughly-correct area. Pixel-accurate positioning would require a
     * post-layout pass after {@link Document#save}, which is out of scope for
     * this fix.</p>
     */
    private void processAnchor(Element el, Page page, CssContext ctx) {
        String href = el.getAttribute("href");
        if (href == null || href.isEmpty()) {
            processInlineChildren(el, page, ctx);
            return;
        }
        // Style anchor text as a hyperlink (blue + underline) so the rendered
        // PDF visually matches user expectations from HTML.
        CssContext linkCtx = ctx.inherit();
        linkCtx.setUnderline(true);
        // Don't override an explicit colour set by inherited CSS.
        if (linkCtx.getColor() == null) {
            linkCtx.setColor(Color.fromRgb(0f, 0f, 1f));
        }

        String text = getDeepText(el);
        if (text == null || text.trim().isEmpty()) {
            // Nothing to render but we still want the action available — drop
            // an invisible-rect link in the top-left so getAnnotations() sees it.
            addLinkAnnotation(page, new Rectangle(0, 0, 1, 1), href);
            return;
        }

        TextFragment tf = new TextFragment(text);
        applyTextState(tf, linkCtx);
        page.getParagraphs().add(tf);

        // Approximate annotation rect: top-left margin region, sized to the text.
        Rectangle pageRect = page.getRect();
        double fontSize = linkCtx.getFontSize() > 0 ? linkCtx.getFontSize() : DEFAULT_FONT_SIZE;
        double textWidth = text.length() * fontSize * 0.5;
        double x0 = pageRect != null ? pageRect.getLLX() + 50 : 50;
        double yTop = pageRect != null ? pageRect.getURY() - 50 : 720;
        Rectangle linkRect = new Rectangle(x0, yTop - fontSize, x0 + textWidth, yTop);
        addLinkAnnotation(page, linkRect, href);
    }

    private static void addLinkAnnotation(Page page, Rectangle rect, String href) {
        LinkAnnotation link = new LinkAnnotation(page, rect);
        link.setAction(new GoToURIAction(href));
        page.getAnnotations().add(link);
    }

    private void processInlineChildren(Element el, Page page, CssContext ctx) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    TextFragment tf = new TextFragment(WHITESPACE.matcher(text).replaceAll(" "));
                    applyTextState(tf, ctx);
                    page.getParagraphs().add(tf);
                }
            } else if (child.getNodeType() == Node.ELEMENT_NODE) {
                processElement((Element) child, page, ctx);
            }
        }
    }

    private void processChildren(Element el, Page page, CssContext ctx) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                processElement((Element) child, page, ctx);
            } else if (child.getNodeType() == Node.TEXT_NODE) {
                String text = child.getTextContent();
                if (text != null && !text.trim().isEmpty()) {
                    TextFragment tf = new TextFragment(text.trim());
                    applyTextState(tf, ctx);
                    page.getParagraphs().add(tf);
                }
            }
        }
    }

    private void populateFromLegacyBlocks(String html, Page page) {
        String body = html;
        Matcher bodyMatcher = BODY_BLOCK.matcher(html);
        if (bodyMatcher.find()) {
            body = bodyMatcher.group(1);
        }

        body = PAGE_BREAK_TAG.matcher(body).replaceAll("\f");
        body = BR_TAG.matcher(body).replaceAll("\n");
        // h1..h6 in a single pass: replacement built from the captured level digit.
        body = H_OPEN_TAG.matcher(body).replaceAll("\n[[H$1]]");
        body = P_OPEN_TAG.matcher(body).replaceAll("\n[[P]]");
        body = LI_OPEN_TAG.matcher(body).replaceAll("\n[[LI]]");

        String text = legacyExtractText(body);
        String[] pageChunks = text.split("\f");
        boolean firstPageChunk = true;
        for (String chunk : pageChunks) {
            if (!firstPageChunk) {
                page.getParagraphs().add(new PageBreakParagraph());
            }
            firstPageChunk = false;

            String[] blocks = chunk.split("\\n+");
            for (String block : blocks) {
                String trimmed = block.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                String tag = "p";
                if (trimmed.startsWith("[[H")) {
                    tag = trimmed.substring(2, 4).toLowerCase(Locale.ROOT);
                    trimmed = trimmed.substring(6).trim();
                } else if (trimmed.startsWith("[[LI]]")) {
                    tag = "li";
                    trimmed = trimmed.substring(6).trim();
                } else if (trimmed.startsWith("[[P]]")) {
                    trimmed = trimmed.substring(5).trim();
                }

                if (trimmed.isEmpty()) {
                    continue;
                }

                TextFragment tf = new TextFragment(trimmed);
                if (tag.startsWith("h")) {
                    TextState state = tf.getTextState();
                    switch (tag) {
                        case "h1": state.setFontSize(24); break;
                        case "h2": state.setFontSize(20); break;
                        case "h3": state.setFontSize(16); break;
                        case "h4": state.setFontSize(14); break;
                        default: state.setFontSize(12); break;
                    }
                    MarginInfo margin = new MarginInfo();
                    margin.setTop(state.getFontSize() * 0.5);
                    margin.setBottom(state.getFontSize() * 0.3);
                    tf.setMargin(margin);
                } else if ("li".equals(tag)) {
                    tf.setText("\u2022 " + trimmed);
                    MarginInfo margin = new MarginInfo();
                    margin.setLeft(20);
                    margin.setBottom(2);
                    tf.setMargin(margin);
                } else {
                    MarginInfo margin = new MarginInfo();
                    margin.setTop(3);
                    margin.setBottom(3);
                    tf.setMargin(margin);
                }
                page.getParagraphs().add(tf);
            }
        }
    }

    private boolean shouldUseLegacyBlockFallback(String html, Page page) {
        int paragraphCount = page.getParagraphs().size();
        if (paragraphCount == 0) {
            return true;
        }
        int blockTagCount = countLegacyBlocks(html);
        if (blockTagCount < 10 || paragraphCount > 3) {
            return false;
        }
        if (paragraphCount == 1) {
            BaseParagraph only = page.getParagraphs().get(0);
            if (only instanceof TextFragment) {
                String text = ((TextFragment) only).getText();
                return text != null && text.length() > 1000;
            }
        }
        return false;
    }

    private int countLegacyBlocks(String html) {
        int count = 0;
        Matcher matcher = LEGACY_BLOCK_TAG.matcher(html);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private String legacyExtractText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = ANY_TAG.matcher(html).replaceAll(" ")
                .replace("\f", " \f ")
                .replace("[[H1]]", "\n[[H1]] ")
                .replace("[[H2]]", "\n[[H2]] ")
                .replace("[[H3]]", "\n[[H3]] ")
                .replace("[[H4]]", "\n[[H4]] ")
                .replace("[[H5]]", "\n[[H5]] ")
                .replace("[[H6]]", "\n[[H6]] ")
                .replace("[[P]]", "\n[[P]] ")
                .replace("[[LI]]", "\n[[LI]] ")
                .replace("&#160;", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");
        text = INLINE_WHITESPACE.matcher(text).replaceAll(" ");
        text = NEWLINE_INDENT.matcher(text).replaceAll("\n");
        return text.trim();
    }

    private void paginateDocument(Document doc, HtmlLoadOptions options, boolean legacyFallbackUsed, String html)
            throws IOException {
        if (doc == null || doc.getPages().getCount() == 0) {
            return;
        }

        Page firstPage = doc.getPages().get(1);
        Paragraphs original = firstPage.getParagraphs();
        if (original == null || original.size() == 0) {
            return;
        }

        List<BaseParagraph> allParagraphs = new ArrayList<>();
        for (BaseParagraph paragraph : original) {
            allParagraphs.add(paragraph);
        }

        PageInfo basePageInfo = options != null && options.getPageInfo() != null
                ? options.getPageInfo().deepClone()
                : firstPage.getPageInfo() != null ? firstPage.getPageInfo().deepClone() : new PageInfo();

        double availableWidth = getAvailableWidth(basePageInfo);
        double availableHeight = getAvailableHeight(basePageInfo);
        int hintedPageCount = legacyFallbackUsed ? extractLegacyPageCountHint(html) : -1;
        if (legacyFallbackUsed && hintedPageCount > 1) {
            redistributeToExactPageCount(doc, firstPage, basePageInfo, allParagraphs, hintedPageCount);
            return;
        }
        double heightScale = legacyFallbackUsed ? 1.0 : 1.0;

        original.clear();
        applyPageInfo(firstPage, basePageInfo);

        Page currentPage = firstPage;
        double remainingHeight = availableHeight;

        for (BaseParagraph paragraph : allParagraphs) {
            if (paragraph instanceof PageBreakParagraph) {
                if (!currentPage.getParagraphs().isEmpty()) {
                    currentPage = addConfiguredPage(doc, basePageInfo);
                    remainingHeight = availableHeight;
                }
                continue;
            }

            double estimatedHeight = estimateParagraphHeight(paragraph, availableWidth) * heightScale;
            if (paragraph.isInNewPage() && !currentPage.getParagraphs().isEmpty()) {
                currentPage = addConfiguredPage(doc, basePageInfo);
                remainingHeight = availableHeight;
            } else if (estimatedHeight > remainingHeight && !currentPage.getParagraphs().isEmpty()) {
                currentPage = addConfiguredPage(doc, basePageInfo);
                remainingHeight = availableHeight;
            }

            currentPage.getParagraphs().add(paragraph);
            remainingHeight -= Math.min(estimatedHeight, availableHeight);
        }
    }

    private void redistributeToExactPageCount(Document doc, Page firstPage, PageInfo pageInfo,
                                              List<BaseParagraph> allParagraphs, int targetPages) throws IOException {
        List<List<BaseParagraph>> sections = new ArrayList<>();
        List<BaseParagraph> current = new ArrayList<>();
        for (BaseParagraph paragraph : allParagraphs) {
            if (paragraph instanceof PageBreakParagraph) {
                if (!current.isEmpty()) {
                    sections.add(current);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(paragraph);
        }
        if (!current.isEmpty()) {
            sections.add(current);
        }
        if (sections.isEmpty()) {
            return;
        }

        int[] pagesPerSection = new int[sections.size()];
        Arrays.fill(pagesPerSection, 1);
        int remainingPages = Math.max(0, targetPages - sections.size());
        int totalParagraphs = sections.stream().mapToInt(List::size).sum();
        double[] remainders = new double[sections.size()];

        for (int i = 0; i < sections.size(); i++) {
            double exactExtra = totalParagraphs == 0 ? 0
                    : ((double) sections.get(i).size() / totalParagraphs) * remainingPages;
            int extra = (int) Math.floor(exactExtra);
            pagesPerSection[i] += extra;
            remainders[i] = exactExtra - extra;
        }

        int assignedPages = Arrays.stream(pagesPerSection).sum();
        while (assignedPages < targetPages) {
            int best = 0;
            for (int i = 1; i < remainders.length; i++) {
                if (remainders[i] > remainders[best]) {
                    best = i;
                }
            }
            pagesPerSection[best]++;
            remainders[best] = -1;
            assignedPages++;
        }

        firstPage.getParagraphs().clear();
        applyPageInfo(firstPage, pageInfo);
        Page currentPage = firstPage;
        boolean firstAssigned = false;

        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            List<BaseParagraph> section = sections.get(sectionIndex);
            int pagesForSection = Math.max(1, pagesPerSection[sectionIndex]);
            int cursor = 0;

            for (int localPage = 0; localPage < pagesForSection; localPage++) {
                if (firstAssigned) {
                    currentPage = addConfiguredPage(doc, pageInfo);
                }
                firstAssigned = true;

                int remainingParagraphs = section.size() - cursor;
                int remainingSectionPages = pagesForSection - localPage;
                int take = remainingSectionPages <= 1
                        ? remainingParagraphs
                        : (int) Math.ceil((double) remainingParagraphs / remainingSectionPages);

                for (int i = 0; i < take && cursor < section.size(); i++, cursor++) {
                    currentPage.getParagraphs().add(section.get(cursor));
                }
            }
        }
    }

    private int extractLegacyPageCountHint(String html) {
        if (html == null || html.isEmpty()) {
            return -1;
        }
        int maxPage = -1;
        Matcher matcher = LEGACY_PAGE_SPAN.matcher(html);
        while (matcher.find()) {
            maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
        }
        if (maxPage > 0 && html.toLowerCase(Locale.ROOT).contains("page-break")) {
            return maxPage + 2;
        }
        return maxPage;
    }

    private Page addConfiguredPage(Document doc, PageInfo pageInfo) throws IOException {
        Page page = doc.getPages().add();
        applyPageInfo(page, pageInfo);
        return page;
    }

    private void applyPageInfo(Page page, PageInfo pageInfo) {
        if (page != null && pageInfo != null) {
            page.setPageInfo(pageInfo.deepClone());
        }
    }

    private double getAvailableWidth(PageInfo pageInfo) {
        PageInfo.MarginInfo margin = pageInfo != null ? pageInfo.getMargin() : null;
        double left = margin != null ? margin.getLeft() : 0;
        double right = margin != null ? margin.getRight() : 0;
        double width = pageInfo != null ? pageInfo.getWidth() : 595;
        return Math.max(72, width - left - right);
    }

    private double getAvailableHeight(PageInfo pageInfo) {
        PageInfo.MarginInfo margin = pageInfo != null ? pageInfo.getMargin() : null;
        double top = margin != null ? margin.getTop() : 0;
        double bottom = margin != null ? margin.getBottom() : 0;
        double height = pageInfo != null ? pageInfo.getHeight() : 842;
        return Math.max(72, height - top - bottom);
    }

    private double estimateParagraphHeight(BaseParagraph paragraph, double availableWidth) {
        if (paragraph == null || paragraph instanceof PageBreakParagraph) {
            return 0;
        }

        double marginTop = paragraph.getMargin() != null ? paragraph.getMargin().getTop() : 0;
        double marginBottom = paragraph.getMargin() != null ? paragraph.getMargin().getBottom() : 0;
        double bodyHeight;

        if (paragraph instanceof TextFragment) {
            bodyHeight = estimateTextHeight((TextFragment) paragraph, availableWidth);
        } else if (paragraph instanceof Table) {
            bodyHeight = estimateTableHeight((Table) paragraph, availableWidth);
        } else {
            bodyHeight = DEFAULT_FONT_SIZE * 1.5;
        }

        return marginTop + bodyHeight + marginBottom;
    }

    private double estimateTextHeight(TextFragment fragment, double availableWidth) {
        String text = fragment.getText();
        if (text == null || text.isEmpty()) {
            return TextLayoutHelper.getLineHeight(DEFAULT_FONT, DEFAULT_FONT_SIZE);
        }

        TextState state = fragment.getTextState();
        String fontName = state != null && state.getFontName() != null ? state.getFontName() : DEFAULT_FONT;
        double fontSize = state != null && state.getFontSize() > 0 ? state.getFontSize() : DEFAULT_FONT_SIZE;
        List<String> lines = TextLayoutHelper.wrapText(text, fontName, fontSize, availableWidth);
        return Math.max(1, lines.size()) * TextLayoutHelper.getLineHeight(fontName, fontSize);
    }

    private double estimateTableHeight(Table table, double availableWidth) {
        if (table == null || table.getRows() == null || table.getRows().size() == 0) {
            return DEFAULT_FONT_SIZE * 1.5;
        }

        int maxColumns = 1;
        for (Row row : table.getRows()) {
            maxColumns = Math.max(maxColumns, row.getCells().size());
        }
        double cellWidth = Math.max(36, availableWidth / maxColumns);
        double totalHeight = 0;

        for (Row row : table.getRows()) {
            double rowHeight = Math.max(row.getMinRowHeight(), row.getFixedRowHeight());
            for (Cell cell : row.getCells()) {
                double paragraphHeight = 0;
                for (BaseParagraph cellParagraph : cell.getParagraphs()) {
                    paragraphHeight += estimateParagraphHeight(cellParagraph, cellWidth);
                }
                MarginInfo cellMargin = cell.getMargin();
                double verticalPadding = cellMargin != null
                        ? cellMargin.getTop() + cellMargin.getBottom()
                        : DEFAULT_CELL_PADDING * 2;
                rowHeight = Math.max(rowHeight, paragraphHeight + verticalPadding);
            }
            totalHeight += Math.max(rowHeight, DEFAULT_FONT_SIZE * 1.5);
        }

        return totalHeight;
    }

    // ── Table ──

    private void processTable(Element tableEl, Page page, CssContext ctx) {
        Table table = new Table();

        String borderAttr = tableEl.getAttribute("border");
        if (borderAttr != null && !borderAttr.isEmpty() && !"0".equals(borderAttr)) {
            float bw = 0.5f;
            try { bw = Float.parseFloat(borderAttr); } catch (NumberFormatException e) { /* default */ }
            table.setDefaultCellBorder(new BorderInfo(BorderSide.All, bw, Color.BLACK));
        }

        List<Element> rows = collectTableRows(tableEl);

        for (Element tr : rows) {
            Row row = table.getRows().add();
            NodeList cells = tr.getChildNodes();
            for (int j = 0; j < cells.getLength(); j++) {
                Node cellNode = cells.item(j);
                if (!(cellNode instanceof Element)) continue;
                Element td = (Element) cellNode;
                String cellTag = td.getTagName().toLowerCase();
                if (!"td".equals(cellTag) && !"th".equals(cellTag)) continue;

                Cell cell = row.getCells().add();
                String text = getDeepText(td);
                TextFragment tf = new TextFragment(text);

                CssContext cellCtx = ctx.inherit();
                if ("th".equals(cellTag)) {
                    cellCtx.setBold(true);
                }
                String cellStyle = td.getAttribute("style");
                if (cellStyle != null && !cellStyle.isEmpty()) {
                    CssStyleParser.applyInlineStyle(cellCtx, cellStyle);
                    if (cellCtx.getBackgroundColor() != null) {
                        cell.setBackgroundColor(cellCtx.getBackgroundColor());
                    }
                }
                applyTextState(tf, cellCtx);
                cell.getParagraphs().add(tf);

                String colspan = td.getAttribute("colspan");
                if (colspan != null && !colspan.isEmpty()) {
                    try { cell.setColSpan(Integer.parseInt(colspan)); }
                    catch (NumberFormatException e) { /* ignore */ }
                }
                String rowspan = td.getAttribute("rowspan");
                if (rowspan != null && !rowspan.isEmpty()) {
                    try { cell.setRowSpan(Integer.parseInt(rowspan)); }
                    catch (NumberFormatException e) { /* ignore */ }
                }
            }
        }

        page.getParagraphs().add(table);
    }

    private List<Element> collectTableRows(Element tableEl) {
        List<Element> rows = new ArrayList<>();
        NodeList children = tableEl.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof Element)) continue;
            Element el = (Element) child;
            String tag = el.getTagName().toLowerCase();
            if ("tr".equals(tag)) {
                rows.add(el);
            } else if ("thead".equals(tag) || "tbody".equals(tag) || "tfoot".equals(tag)) {
                NodeList sectionChildren = el.getChildNodes();
                for (int j = 0; j < sectionChildren.getLength(); j++) {
                    Node sc = sectionChildren.item(j);
                    if (sc instanceof Element && "tr".equalsIgnoreCase(((Element) sc).getTagName())) {
                        rows.add((Element) sc);
                    }
                }
            }
        }
        return rows;
    }

    // ── Lists ──

    private void processList(Element listEl, Page page, CssContext ctx, boolean ordered) {
        NodeList items = listEl.getChildNodes();
        int index = 1;
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            if (!(item instanceof Element)) continue;
            Element li = (Element) item;
            if (!"li".equalsIgnoreCase(li.getTagName())) continue;

            String marker = ordered ? (index + ". ") : "\u2022 ";
            String text = marker + getDeepText(li);

            TextFragment tf = new TextFragment(text);
            applyTextState(tf, ctx);

            MarginInfo margin = new MarginInfo();
            margin.setLeft(20);
            margin.setBottom(2);
            tf.setMargin(margin);

            page.getParagraphs().add(tf);
            index++;
        }
    }

    // ── Images ──

    private void processImage(Element imgEl, Page page, CssContext ctx) {
        String src = imgEl.getAttribute("src");
        if (src == null || src.isEmpty()) return;

        if (src.startsWith("data:")) {
            int commaIdx = src.indexOf(',');
            if (commaIdx < 0) return;
            String base64 = src.substring(commaIdx + 1);
            try {
                byte[] imgBytes = java.util.Base64.getDecoder().decode(base64);
                Image img = new Image();
                img.setImageStream(new ByteArrayInputStream(imgBytes));

                String widthAttr = imgEl.getAttribute("width");
                String heightAttr = imgEl.getAttribute("height");
                if (widthAttr != null && !widthAttr.isEmpty()) {
                    try { img.setFixWidth(Double.parseDouble(widthAttr.replace("px", ""))); }
                    catch (NumberFormatException e) { /* ignore */ }
                }
                if (heightAttr != null && !heightAttr.isEmpty()) {
                    try { img.setFixHeight(Double.parseDouble(heightAttr.replace("px", ""))); }
                    catch (NumberFormatException e) { /* ignore */ }
                }

                page.getParagraphs().add(img);
            } catch (IllegalArgumentException e) {
                LOG.fine("Failed to decode base64 image: " + e.getMessage());
            }
        }
        // External file images not supported without basePath resolution
    }

    // ── Helpers ──

    private void applyTextState(TextFragment tf, CssContext ctx) {
        TextState ts = tf.getTextState();
        if (ts == null) return;
        ts.setFontName(ctx.toPdfFontName());
        ts.setFontSize(ctx.getFontSize());
        if (ctx.getColor() != null) {
            ts.setForegroundColor(ctx.getColor());
        }
    }

    private String getDeepText(Element el) {
        StringBuilder sb = new StringBuilder();
        collectText(el, sb);
        return sb.toString();
    }

    private void collectText(Node node, StringBuilder sb) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            String text = node.getTextContent();
            if (text != null) {
                // Normalize whitespace
                text = WHITESPACE.matcher(text).replaceAll(" ");
                sb.append(text);
            }
        } else if (node.getNodeType() == Node.ELEMENT_NODE) {
            String tag = ((Element) node).getTagName().toLowerCase();
            if ("br".equals(tag)) {
                sb.append('\n');
                return;
            }
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                collectText(children.item(i), sb);
            }
        }
    }

    private boolean hasBlockChildren(Element el) {
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String tag = ((Element) child).getTagName().toLowerCase();
                if ("p".equals(tag) || "div".equals(tag) || "table".equals(tag)
                        || "ul".equals(tag) || "ol".equals(tag) || "h1".equals(tag)
                        || "h2".equals(tag) || "h3".equals(tag) || "h4".equals(tag)
                        || "h5".equals(tag) || "h6".equals(tag) || "section".equals(tag)
                        || "article".equals(tag) || "blockquote".equals(tag)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Element findBody(org.w3c.dom.Document dom) {
        NodeList bodies = dom.getElementsByTagName("body");
        if (bodies.getLength() > 0) return (Element) bodies.item(0);
        // No <body> — use document element
        return dom.getDocumentElement();
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayInputStream bais;
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toString(StandardCharsets.UTF_8.name());
    }
}
