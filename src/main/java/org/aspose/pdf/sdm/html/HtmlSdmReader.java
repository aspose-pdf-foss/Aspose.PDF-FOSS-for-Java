package org.aspose.pdf.sdm.html;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.aspose.pdf.Color;
import org.aspose.pdf.html.CssContext;
import org.aspose.pdf.html.CssStyleParser;
import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.InlineImage;
import org.aspose.pdf.sdm.LineBreak;
import org.aspose.pdf.sdm.LinkInline;
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
import org.aspose.pdf.sdm.SdmIds;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.SourceRef;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * IR Stage 4, PART 1 — the <b>HTML &rarr; SDM reader</b>: the inverse of the
 * Stage-3 {@link SdmHtmlWriter}. It parses HTML with the zero-dep
 * {@link HtmlTagParser}, resolves the CSS cascade HERE (per IR spec §10.3) via
 * {@link CssStyleParser}/{@link CssContext} — user-agent emphasis, {@code <style>}
 * rules by specificity, inline {@code style}, and inheritance — and emits SDM
 * nodes carrying the FINAL computed {@link TextStyle}/{@link BlockStyle} (the SDM
 * carries no cascade; writers read final values).
 *
 * <p>The supported CSS is a documented subset (font family/size/weight/style,
 * text-decoration, color/background, text-align, margins, text-indent,
 * line-height); anything else is recorded on the {@link HtmlReadReport} rather
 * than silently ignored. Unknown elements degrade to {@link Opaque}.</p>
 */
public final class HtmlSdmReader {

    /** Base-14 default the CssContext seeds; used to detect explicit overrides. */
    private static final String DEFAULT_FAMILY = "Helvetica";
    private static final double DEFAULT_SIZE = 12.0;

    private java.util.UUID nsDoc;
    private long seq;
    private HtmlReadReport report;
    private final List<CssRule> styleRules = new ArrayList<>();
    /** Base for resolving relative {@code <img>}/{@code <link>} references (may be null). */
    private String baseUri;
    /** Whether {@code http:}/{@code https:} resources may be fetched (default false). */
    private boolean allowNetwork;

    /**
     * Reads HTML into an SDM document.
     *
     * @param html    the HTML source
     * @param baseUri the base URI (may be null)
     * @param options read options (may be null)
     * @return the SDM document (never null)
     */
    public SdmDocument read(String html, String baseUri, HtmlReadOptions options) {
        if (html == null) {
            throw new IllegalArgumentException("html must not be null");
        }
        this.nsDoc = SdmIds.uuidV5(SdmIds.NS_PRODUCT, "html:" + (baseUri == null ? "" : baseUri));
        this.seq = 0;
        this.report = new HtmlReadReport();
        this.styleRules.clear();
        this.baseUri = baseUri;
        this.allowNetwork = options != null && options.isAllowNetwork();

        org.w3c.dom.Document dom;
        try {
            dom = HtmlTagParser.parse(html);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("HTML parse failed: " + e.getMessage(), e);
        }
        SdmDocument sdm = new SdmDocument();
        sdm.setNsDoc(nsDoc);

        Element root = dom.getDocumentElement();
        Element head = firstChild(root, "head");
        Element body = firstChild(root, "body");
        if (body == null) {
            body = root; // tolerate fragment
        }
        // External stylesheets come first (lowest precedence), then inline <style>.
        collectLinkedStyles(root);
        if (head != null) {
            collectStyles(head);
            Element title = firstChild(head, "title");
            if (title != null) {
                sdm.getMetadata().setTitle(textContent(title).trim());
            }
            // Round-trip the source page geometry (<meta name="page-size" content="W H">).
            NodeList metas = head.getElementsByTagName("meta");
            for (int i = 0; i < metas.getLength(); i++) {
                Element m = (Element) metas.item(i);
                if ("page-size".equalsIgnoreCase(m.getAttribute("name"))) {
                    String[] wh = m.getAttribute("content").trim().split("\\s+");
                    if (wh.length == 2) {
                        sdm.getMetadata().getCustom().put("page-width", wh[0]);
                        sdm.getMetadata().getCustom().put("page-height", wh[1]);
                    }
                }
            }
        }
        collectStyles(body);
        String lang = root.getAttribute("lang");
        if (lang != null && !lang.isEmpty()) {
            sdm.getMetadata().setLang(lang);
        }

        CssContext rootCtx = new CssContext();
        walkBlocks(body, rootCtx, sdm.getChildren(), sdm);
        return sdm;
    }

    /** @return the report from the most recent {@link #read} (fonts/CSS/opaque). */
    public HtmlReadReport getReport() {
        return report;
    }

    // ---- CSS ---------------------------------------------------------------

    private void collectStyles(Element scope) {
        NodeList styles = scope.getElementsByTagName("style");
        for (int i = 0; i < styles.getLength(); i++) {
            parseStylesheet(textContent((Element) styles.item(i)));
        }
    }

    /** Loads external {@code <link rel="stylesheet" href="...">} sheets, resolving
     *  {@code href} against {@link #baseUri}. Unresolved sheets are recorded on the
     *  report rather than aborting the read. */
    private void collectLinkedStyles(Element scope) {
        NodeList links = scope.getElementsByTagName("link");
        for (int i = 0; i < links.getLength(); i++) {
            Element link = (Element) links.item(i);
            String rel = link.getAttribute("rel");
            if (rel == null
                    || !rel.toLowerCase(Locale.ROOT).contains("stylesheet")) {
                continue;
            }
            String href = link.getAttribute("href");
            if (href == null || href.isEmpty()) {
                continue;
            }
            byte[] css = fetchResource(href);
            if (css != null && css.length > 0) {
                parseStylesheet(new String(css, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                report.addExternalStylesheet(href);
            }
        }
    }

    /** Stylesheet parser: {@code selector[,selector] { decls }}, brace-aware so
     *  it unwraps {@code @media} blocks (screen-only skipped, print/all applied)
     *  and skips other at-rules ({@code @font-face}, {@code @page}, …). Comments
     *  stripped; each selector kept with its specificity. */
    private void parseStylesheet(String css) {
        if (css == null) {
            return;
        }
        css = css.replaceAll("/\\*.*?\\*/", " ");
        parseRules(css);
    }

    /** Parses a run of rules, recursing into {@code @media}/{@code @supports} bodies. */
    private void parseRules(String css) {
        int i = 0;
        int n = css.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(css.charAt(i))) {
                i++;
            }
            int brace = css.indexOf('{', i);
            if (brace < 0) {
                break;
            }
            int close = matchBrace(css, brace);
            if (close < 0) {
                break;
            }
            String head = css.substring(i, brace).trim();
            String body = css.substring(brace + 1, close);
            if (head.startsWith("@")) {
                String at = head.toLowerCase(Locale.ROOT);
                // A PDF is print media: apply @media print/all, skip screen-only.
                if (at.startsWith("@media")) {
                    boolean screenOnly = at.contains("screen")
                            && !at.contains("print") && !at.contains("all");
                    if (!screenOnly) {
                        parseRules(body);
                    }
                } else if (at.startsWith("@supports")) {
                    parseRules(body);
                }
                // @font-face/@page/@keyframes/@import/... are not selector rules — skip.
            } else {
                String decls = body.trim();
                for (String sel : splitSelectors(head)) {
                    sel = sel.trim();
                    if (!sel.isEmpty()) {
                        styleRules.add(new CssRule(sel, decls, specificity(sel)));
                    }
                }
            }
            i = close + 1;
        }
    }

    /** Index of the {@code '}'} that closes the block opened at {@code open}. */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Splits a selector list on commas that are not inside {@code []} or {@code ()}. */
    private static List<String> splitSelectors(String s) {
        List<String> out = new ArrayList<>();
        int depthB = 0;
        int depthP = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depthB++;
            else if (c == ']') depthB = Math.max(0, depthB - 1);
            else if (c == '(') depthP++;
            else if (c == ')') depthP = Math.max(0, depthP - 1);
            else if (c == ',' && depthB == 0 && depthP == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int specificity(String sel) {
        int spec = 0;
        for (String part : sel.split("(?=[.#])")) {
            if (part.startsWith("#")) {
                spec += 100;
            } else if (part.startsWith(".")) {
                spec += 10;
            } else if (!part.isEmpty() && !part.equals("*")) {
                spec += 1;
            }
        }
        return spec;
    }

    /** Matches a full selector (with descendant combinators) against {@code el}:
     *  the rightmost compound must match {@code el}; each preceding compound must
     *  match some ancestor, in order. {@code >} is treated as descendant (approx). */
    private boolean selectorMatches(String sel, Element el) {
        List<String> comps = new ArrayList<>();
        for (String c : sel.trim().split("\\s*>\\s*|\\s+")) {
            if (!c.isEmpty()) {
                comps.add(c);
            }
        }
        if (comps.isEmpty()) {
            return false;
        }
        if (!compoundMatches(comps.get(comps.size() - 1), el)) {
            return false;
        }
        int idx = comps.size() - 2;
        Node ancestor = el.getParentNode();
        while (idx >= 0 && ancestor != null) {
            if (ancestor.getNodeType() == Node.ELEMENT_NODE
                    && compoundMatches(comps.get(idx), (Element) ancestor)) {
                idx--;
            }
            ancestor = ancestor.getParentNode();
        }
        return idx < 0;
    }

    /** Matches one compound selector ({@code tag.class#id[attr]:pseudo}) against
     *  a single element. Pseudo-classes/elements are ignored (treated as matching);
     *  attribute selectors ({@code [a]}, {@code [a=v]}, {@code [a~=v]}) are honoured. */
    private boolean compoundMatches(String comp, Element el) {
        String tag = el.getTagName().toLowerCase(Locale.ROOT);
        String id = el.getAttribute("id");
        List<String> classes = new ArrayList<>();
        String classAttr = el.getAttribute("class");
        if (classAttr != null) {
            for (String c : classAttr.trim().split("\\s+")) {
                if (!c.isEmpty()) {
                    classes.add(c);
                }
            }
        }
        int i = 0;
        int n = comp.length();
        while (i < n) {
            char c = comp.charAt(i);
            if (c == '*') {
                i++;
            } else if (c == '.') {
                int j = tokenEnd(comp, i + 1);
                if (!classes.contains(comp.substring(i + 1, j))) {
                    return false;
                }
                i = j;
            } else if (c == '#') {
                int j = tokenEnd(comp, i + 1);
                if (!comp.substring(i + 1, j).equals(id)) {
                    return false;
                }
                i = j;
            } else if (c == '[') {
                int cl = comp.indexOf(']', i);
                if (cl < 0) {
                    return false;
                }
                if (!attrMatches(comp.substring(i + 1, cl), el)) {
                    return false;
                }
                i = cl + 1;
            } else if (c == ':') {
                // pseudo-class/element — skip its name (ignored / treated as matching)
                int j = i + 1;
                while (j < n && ":.#[".indexOf(comp.charAt(j)) < 0) {
                    j++;
                }
                i = j;
            } else {
                int j = tokenEnd(comp, i);
                if (!comp.substring(i, j).toLowerCase(Locale.ROOT).equals(tag)) {
                    return false;
                }
                i = j;
            }
        }
        return true;
    }

    /** Next index at or after {@code from} that starts a new simple-selector token. */
    private static int tokenEnd(String s, int from) {
        int j = from;
        while (j < s.length() && ".#[:*".indexOf(s.charAt(j)) < 0) {
            j++;
        }
        return j;
    }

    /** Matches an attribute selector body (inside {@code []}) against {@code el}. */
    private static boolean attrMatches(String expr, Element el) {
        expr = expr.trim();
        String op = null;
        for (String o : new String[]{"~=", "|=", "^=", "$=", "*=", "="}) {
            int p = expr.indexOf(o);
            if (p >= 0) {
                String name = expr.substring(0, p).trim();
                String val = unquote(expr.substring(p + o.length()).trim());
                String actual = el.getAttribute(name);
                if (actual == null) {
                    return false;
                }
                switch (o) {
                    case "=":  return actual.equals(val);
                    case "~=": return java.util.Arrays.asList(actual.trim().split("\\s+")).contains(val);
                    case "|=": return actual.equals(val) || actual.startsWith(val + "-");
                    case "^=": return actual.startsWith(val);
                    case "$=": return actual.endsWith(val);
                    case "*=": return actual.contains(val);
                    default:   break;
                }
                op = o;
                break;
            }
        }
        // presence-only: [attr]
        return op == null && el.hasAttribute(expr);
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Resolves the effective CssContext for an element: inherit → matched
     *  {@code <style>} rules (ascending specificity) → inline style. */
    private CssContext resolveStyle(Element el, CssContext parent) {
        CssContext ctx = parent.inherit();
        List<CssRule> matched = new ArrayList<>();
        for (CssRule r : styleRules) {
            if (selectorMatches(r.selector, el)) {
                matched.add(r);
            }
        }
        matched.sort((a, b) -> Integer.compare(a.specificity, b.specificity));
        for (CssRule r : matched) {
            applyDecls(ctx, r.declarations);
        }
        String inline = el.getAttribute("style");
        if (inline != null && !inline.isEmpty()) {
            applyDecls(ctx, inline);
        }
        return ctx;
    }

    /** True when a {@code <div>} should lay its direct children out as parallel
     *  columns: an explicit flex/table {@code display}, or the well-known EDGAR/
     *  iShares {@code sideBySide} wrapper (whose {@code display:table} rule keys
     *  off an ancestor class that is absent, so it must be recognised by name). */
    private static boolean isColumnParent(Element el, String display) {
        if (display != null) {
            String d = display.trim().toLowerCase(Locale.ROOT);
            if (d.equals("flex") || d.equals("inline-flex") || d.equals("table")) {
                return true;
            }
        }
        return hasClassToken(el, "sideBySide");
    }

    /** True when {@code el}'s {@code class} attribute contains {@code token}. */
    private static boolean hasClassToken(Element el, String token) {
        String cls = el.getAttribute("class");
        if (cls == null || cls.isEmpty()) {
            return false;
        }
        for (String t : cls.trim().split("\\s+")) {
            if (t.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private void applyDecls(CssContext ctx, String decls) {
        CssStyleParser.applyInlineStyle(ctx, decls);
        if (report != null && decls != null) {
            for (String d : decls.split(";")) {
                int c = d.indexOf(':');
                if (c > 0) {
                    String prop = d.substring(0, c).trim().toLowerCase(Locale.ROOT);
                    if (!SUPPORTED_CSS.contains(prop) && !prop.isEmpty()) {
                        report.addUnsupportedCss(prop);
                    }
                }
            }
        }
    }

    private static final java.util.Set<String> SUPPORTED_CSS = new java.util.HashSet<>(java.util.Arrays.asList(
            "font-family", "font-size", "font-weight", "font-style", "font",
            "text-decoration", "color", "background-color", "background",
            "text-align", "margin", "margin-top", "margin-bottom", "margin-left",
            "margin-right", "text-indent", "line-height", "direction"));

    // ---- Block walking -----------------------------------------------------

    private void walkBlocks(Element parent, CssContext ctx, List<SdmBlock> out, SdmDocument sdm) {
        List<SdmInline> pendingInline = null; // accumulates loose inline/text into a Paragraph
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                String t = n.getNodeValue();
                if (t != null && !t.trim().isEmpty()) {
                    if (pendingInline == null) {
                        pendingInline = new ArrayList<>();
                    }
                    addRun(pendingInline, t, ctx);
                }
                continue;
            }
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) n;
            String tag = el.getTagName().toLowerCase(Locale.ROOT);
            // An inline wrapper (e.g. <span class="value">) that itself contains
            // block-level content (a <div>/<ul>) is NOT really inline — a browser
            // breaks the inline box around the block. Unwrap it: flush pending
            // inline, then recurse walkBlocks THROUGH it so the nested list/divs
            // become real blocks instead of being flattened onto one line.
            if (isInlineTag(tag) && !containsBlockLevel(el)) {
                if (pendingInline == null) {
                    pendingInline = new ArrayList<>();
                }
                collectInline(el, ctx, pendingInline);
                continue;
            }
            if (isInlineTag(tag)) {
                if (pendingInline != null && !pendingInline.isEmpty()) {
                    Paragraph p = new Paragraph();
                    p.getInline().addAll(pendingInline);
                    assignId(p);
                    out.add(p);
                }
                pendingInline = null;
                walkBlocks(el, resolveStyle(el, ctx), out, sdm);
                continue;
            }
            // a block element flushes any pending loose inline into a Paragraph first
            if (pendingInline != null && !pendingInline.isEmpty()) {
                Paragraph p = new Paragraph();
                p.getInline().addAll(pendingInline);
                assignId(p);
                out.add(p);
            }
            pendingInline = null;
            SdmBlock block = buildBlock(el, tag, ctx, sdm);
            if (block != null) {
                out.add(block);
            }
        }
        if (pendingInline != null && !pendingInline.isEmpty()) {
            Paragraph p = new Paragraph();
            p.getInline().addAll(pendingInline);
            assignId(p);
            out.add(p);
        }
    }

    private SdmBlock buildBlock(Element el, String tag, CssContext parentCtx, SdmDocument sdm) {
        CssContext ctx = resolveStyle(el, parentCtx);
        switch (tag) {
            case "h1": case "h2": case "h3": case "h4": case "h5": case "h6": {
                Heading h = new Heading(tag.charAt(1) - '0');
                collectInlineChildren(el, ctx, h.getInline());
                return applyBlockStyle(h, ctx, parentCtx);
            }
            case "p": {
                Paragraph p = new Paragraph();
                collectInlineChildren(el, ctx, p.getInline());
                return applyBlockStyle(p, ctx, parentCtx);
            }
            case "ul": case "ol": {
                boolean ordered = tag.equals("ol");
                Integer start = null;
                String s = el.getAttribute("start");
                if (ordered && s != null && !s.isEmpty()) {
                    try { start = Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { }
                }
                ListBlock lb = new ListBlock(ordered, start);
                NodeList kids = el.getChildNodes();
                for (int i = 0; i < kids.getLength(); i++) {
                    Node k = kids.item(i);
                    if (k.getNodeType() == Node.ELEMENT_NODE
                            && ((Element) k).getTagName().equalsIgnoreCase("li")) {
                        ListItem li = new ListItem();
                        walkBlocks((Element) k, resolveStyle((Element) k, ctx), li.getChildren(), sdm);
                        assignId(li);
                        lb.getItems().add(li);
                    }
                }
                assignId(lb);
                return lb;
            }
            case "table":
                return buildTable(el, ctx, sdm);
            case "blockquote": {
                Quote q = new Quote();
                walkBlocks(el, ctx, q.getChildren(), sdm);
                assignId(q);
                return q;
            }
            case "pre": case "code": {
                CodeBlock cb = new CodeBlock(textContent(el), languageOf(el));
                assignId(cb);
                return cb;
            }
            case "hr": {
                ThematicBreak tb = new ThematicBreak();
                // A styled rule (e.g. hr.blue_rule) carries a background colour —
                // paint it as a coloured bar instead of a plain grey line.
                Color hrBg = ctx.getBackgroundColor();
                if (hrBg != null) {
                    BlockStyle bs = new BlockStyle();
                    bs.setBackground(colorToInt(hrBg));
                    tb.setStyle(bs);
                }
                assignId(tb);
                return tb;
            }
            case "figure":
                return buildFigure(el, ctx, sdm);
            case "img": {
                ResourceRef ref = putImage(el, sdm);
                if (ref == null) {
                    return opaque(el);
                }
                Figure fig = new Figure(ref);
                String alt = el.getAttribute("alt");
                if (alt != null && !alt.isEmpty()) {
                    fig.setAlt(alt);
                }
                // Round-trip the on-page footprint the writer recorded inline; without
                // it the layout falls back to the raster's intrinsic pixels.
                Double dw = styleLenPt(el, "width");
                Double dh = styleLenPt(el, "height");
                if (dw != null && dw > 0) fig.getAttributes().put("display-width", dw);
                if (dh != null && dh > 0) fig.getAttributes().put("display-height", dh);
                // A watermark/page-frame backdrop is emitted as a bare, absolutely
                // positioned <img class="bg"> (z-index behind text). Flag it so the
                // layout paints it out of flow instead of consuming a whole page.
                if (isBackgroundImg(el)) {
                    fig.getAttributes().put("background", Boolean.TRUE);
                }
                assignId(fig);
                return fig;
            }
            case "div": case "section": case "article": case "header":
            case "footer": case "main": case "aside": case "nav": {
                Container c = new Container(tag.equals("section") ? "Sect" : "Div");
                String disp = ctx.getDisplay();
                if (disp != null && !disp.isEmpty()) {
                    c.getAttributes().put("display", disp);
                }
                // Side-by-side column layout: a fl/table container, or the
                // well-known "sideBySide" EDGAR/iShares wrapper, lays its direct
                // child <div>s out as parallel columns (see SdmPdfLayout).
                if (isColumnParent(el, disp)) {
                    c.getAttributes().put("column-layout", Boolean.TRUE);
                    if (hasClassToken(el, "withRules")) {
                        c.getAttributes().put("column-rules", Boolean.TRUE);
                    }
                }
                // A CSS float on a narrow (<100%) block is laid out as a column
                // with following content flowing beside it (see SdmPdfLayout).
                String flt = ctx.getCssFloat();
                if (("left".equals(flt) || "right".equals(flt))
                        && ctx.getWidthPercent() > 0 && ctx.getWidthPercent() < 100) {
                    c.getAttributes().put("float", flt);
                    c.getAttributes().put("float-width-pct", ctx.getWidthPercent());
                }
                walkBlocks(el, ctx, c.getChildren(), sdm);
                // Carry only the box style we actually render for a div: its
                // background and bottom border (the dotted "border-bottom" rule).
                // Margins are NOT transferred — without a full box model, folding
                // every nested wrapper div's margin into block spacing injects
                // large spurious vertical gaps (EDGAR wraps content many divs deep).
                applyContainerStyle(c, ctx);
                assignId(c);
                return c;
            }
            case "html": case "body": {
                // Transparent wrapper (EDGAR/legacy cells embed a nested
                // <html>…</html> fragment). Flatten its block children in place.
                Container c = new Container("Div");
                walkBlocks(el, ctx, c.getChildren(), sdm);
                assignId(c);
                return c;
            }
            case "input": case "select": case "textarea": case "button":
                return buildFormField(el, tag);
            case "style": case "script": case "head": case "title":
                return null; // non-rendered
            default:
                report.addUnsupportedElement(tag);
                return opaque(el);
        }
    }

    private SdmBlock buildTable(Element el, CssContext ctx, SdmDocument sdm) {
        Table t = new Table();
        Element caption = firstChild(el, "caption");
        if (caption != null) {
            t.setCaption(textContent(caption).trim());
        }
        Element colgroup = firstChild(el, "colgroup");
        if (colgroup != null) {
            NodeList cols = colgroup.getElementsByTagName("col");
            for (int i = 0; i < cols.getLength(); i++) {
                Element col = (Element) cols.item(i);
                String w = col.getAttribute("width");
                if (w == null || w.isEmpty()) {
                    w = styleWidth(col);
                }
                t.getColumns().add(columnSpec(w));
            }
        }
        addRows(el, "thead", TableRow.Kind.HEADER, t, ctx, sdm);
        addRows(el, "tbody", TableRow.Kind.BODY, t, ctx, sdm);
        // rows directly under <table> (no tbody)
        collectDirectRows(el, t, ctx, sdm);
        addRows(el, "tfoot", TableRow.Kind.FOOTER, t, ctx, sdm);
        assignId(t);
        return t;
    }

    private void addRows(Element table, String section, TableRow.Kind kind,
                         Table t, CssContext ctx, SdmDocument sdm) {
        Element sec = firstChild(table, section);
        if (sec == null) {
            return;
        }
        NodeList trs = sec.getChildNodes();
        for (int i = 0; i < trs.getLength(); i++) {
            Node n = trs.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && ((Element) n).getTagName().equalsIgnoreCase("tr")) {
                t.getRows().add(buildRow((Element) n, kind, ctx, sdm));
            }
        }
    }

    private void collectDirectRows(Element table, Table t, CssContext ctx, SdmDocument sdm) {
        NodeList kids = table.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && ((Element) n).getTagName().equalsIgnoreCase("tr")) {
                t.getRows().add(buildRow((Element) n, TableRow.Kind.BODY, ctx, sdm));
            }
        }
    }

    private TableRow buildRow(Element tr, TableRow.Kind kind, CssContext ctx, SdmDocument sdm) {
        TableRow row = new TableRow(kind);
        // Resolve the row's own style: a CSS background on <tr> (e.g. the zebra
        // "tr.greenbg { background:#e1e9e1 }") shows through its cells in the
        // browser. Cells inherit from the row (its text props cascade down), and
        // the row background is propagated to cells that have none of their own.
        Color rowBg = resolveStyle(tr, ctx).getBackgroundColor();
        NodeList cells = tr.getChildNodes();
        for (int i = 0; i < cells.getLength(); i++) {
            Node n = cells.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element ce = (Element) n;
            String ctag = ce.getTagName().toLowerCase(Locale.ROOT);
            if (!ctag.equals("td") && !ctag.equals("th")) {
                continue;
            }
            TableCell cell = new TableCell();
            cell.setKind(ctag.equals("th") ? TableCell.Kind.TH : TableCell.Kind.TD);
            cell.setRowSpan(spanAttr(ce, "rowspan"));
            cell.setColSpan(spanAttr(ce, "colspan"));
            CssContext cellCtx = resolveStyle(ce, ctx);
            walkBlocks(ce, cellCtx, cell.getChildren(), sdm);
            // Carry the cell's background (and alignment) so the layout can paint
            // header tints (e.g. the green iShares/EDGAR column headers).
            applyCellStyle(cell, cellCtx);
            // Inherit the row's background where the cell declares none, so a
            // zebra-striped <tr class="greenbg"> paints across the whole row.
            if (rowBg != null) {
                BlockStyle cs = cell.getStyle();
                if (cs == null) {
                    cs = new BlockStyle();
                    cell.setStyle(cs);
                }
                if (cs.getBackground() == 0) {
                    cs.setBackground(colorToInt(rowBg));
                }
            }
            // Per-cell width (HTML width="6%"/"94" attr or CSS width) drives column
            // sizing; the HTML `nowrap` attribute keeps a label on one line. Only
            // the ATTRIBUTE is honoured — CSS white-space:nowrap is often applied
            // via broad/complex selectors our engine over-matches, which would
            // force every cell nowrap and overlap the columns (seen on iShares TSR).
            captureCellWidth(cell, ce, cellCtx);
            if (ce.hasAttribute("nowrap")) {
                cell.getAttributes().put("nowrap", Boolean.TRUE);
            }
            assignId(cell);
            row.getCells().add(cell);
        }
        assignId(row);
        return row;
    }

    private SdmBlock buildFigure(Element el, CssContext ctx, SdmDocument sdm) {
        Element img = firstChild(el, "img");
        ResourceRef ref = img == null ? null : putImage(img, sdm);
        if (ref == null) {
            return opaque(el);
        }
        Figure fig = new Figure(ref);
        if (img != null) {
            String alt = img.getAttribute("alt");
            if (alt != null && !alt.isEmpty()) {
                fig.setAlt(alt);
            }
            // Round-trip the on-page footprint the writer recorded as an inline
            // style (width/height in pt). Without this the layout falls back to
            // the raster's intrinsic PIXEL dimensions, which are unrelated to the
            // document size and can explode a figure across hundreds of pages.
            Double dw = styleLenPt(img, "width");
            Double dh = styleLenPt(img, "height");
            if (dw != null && dw > 0) fig.getAttributes().put("display-width", dw);
            if (dh != null && dh > 0) fig.getAttributes().put("display-height", dh);
        }
        Element cap = firstChild(el, "figcaption");
        if (cap != null) {
            walkBlocks(cap, resolveStyle(cap, ctx), fig.getCaption(), sdm);
        }
        assignId(fig);
        return fig;
    }

    // ---- Inline walking ----------------------------------------------------

    private void collectInlineChildren(Element el, CssContext ctx, List<SdmInline> out) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                String t = n.getNodeValue();
                if (t != null && !t.isEmpty()) {
                    addRun(out, t, ctx);
                }
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                collectInline((Element) n, ctx, out);
            }
        }
    }

    private void collectInline(Element el, CssContext ctx, List<SdmInline> out) {
        String tag = el.getTagName().toLowerCase(Locale.ROOT);
        CssContext childCtx = resolveStyle(el, ctx);
        switch (tag) {
            case "b": case "strong": childCtx.setBold(true); collectInlineChildren(el, childCtx, out); return;
            case "i": case "em": childCtx.setItalic(true); collectInlineChildren(el, childCtx, out); return;
            case "u": case "ins": childCtx.setUnderline(true); collectInlineChildren(el, childCtx, out); return;
            case "span": case "font": collectInlineChildren(el, childCtx, out); return;
            case "br": out.add(new LineBreak()); return;
            case "a": {
                LinkInline link = new LinkInline(el.getAttribute("href"));
                collectInlineChildren(el, childCtx, link.getChildren());
                assignId(link);
                out.add(link);
                return;
            }
            case "img": {
                // note: needs the SdmDocument for the resource table; carried via field-less
                // path is not available here, so degrade to alt text run when no doc context.
                String alt = el.getAttribute("alt");
                if (alt != null && !alt.isEmpty()) {
                    addRun(out, alt, childCtx);
                }
                return;
            }
            case "s": case "strike": case "del": {
                // strike toggles the strikethrough text-style flag
                collectStrike(el, childCtx, out);
                return;
            }
            default:
                report.addUnsupportedElement(tag);
                collectInlineChildren(el, childCtx, out);
        }
    }

    private void collectStrike(Element el, CssContext ctx, List<SdmInline> out) {
        List<SdmInline> tmp = new ArrayList<>();
        collectInlineChildren(el, ctx, tmp);
        for (SdmInline in : tmp) {
            if (in instanceof Run) {
                TextStyle st = ((Run) in).getStyle();
                if (st == null) {
                    st = new TextStyle();
                    ((Run) in).setStyle(st);
                }
                st.setStrikethrough(true);
            }
            out.add(in);
        }
    }

    private void addRun(List<SdmInline> out, String text, CssContext ctx) {
        // Effectively-invisible text (e.g. EDGAR's 0.001pt "@[ ]@" change markers
        // wrapped in tiny-font spans) is dropped — it is not meant to be read.
        if (ctx.getFontSize() > 0 && ctx.getFontSize() < 1.0 && text.trim().length() <= 4) {
            return;
        }
        Run r = new Run(text, textStyle(ctx));
        assignId(r);
        out.add(r);
    }

    // ---- Style projection (computed CssContext -> explicit SDM style) -------

    /** Builds a TextStyle carrying only the deltas from the document default,
     *  so plain text produces an empty style (clean round-trip). */
    private TextStyle textStyle(CssContext ctx) {
        TextStyle st = new TextStyle();
        boolean any = false;
        if (ctx.isBold()) { st.setBold(true); any = true; }
        if (ctx.isItalic()) { st.setItalic(true); any = true; }
        if (ctx.isUnderline()) { st.setUnderline(true); any = true; }
        String fam = ctx.getFontFamily();
        if (fam != null && !fam.isEmpty() && !fam.equals(DEFAULT_FAMILY)) {
            st.setFontFamily(fam); any = true;
        }
        if (ctx.getFontSize() > 0 && Math.abs(ctx.getFontSize() - DEFAULT_SIZE) > 1e-6) {
            st.setFontSize(ctx.getFontSize()); any = true;
        }
        Color color = ctx.getColor();
        if (color != null && !isBlack(color)) {
            st.setColor(colorToInt(color)); any = true;
        }
        Color bg = ctx.getBackgroundColor();
        if (bg != null) {
            st.setBackground(colorToInt(bg)); any = true;
        }
        return any ? st : null;
    }

    /** Records a cell's authored width (HTML {@code width} attribute, percent or
     *  pixels, else the CSS width) as a {@code width-pct}/{@code width-pt} attribute
     *  the layout uses to size table columns. */
    private void captureCellWidth(TableCell cell, Element ce, CssContext cellCtx) {
        String wAttr = ce.getAttribute("width");
        if (wAttr != null && !wAttr.trim().isEmpty()) {
            wAttr = wAttr.trim();
            if (wAttr.endsWith("%")) {
                try {
                    double p = Double.parseDouble(wAttr.substring(0, wAttr.length() - 1).trim());
                    if (p > 0) { cell.getAttributes().put("width-pct", p); return; }
                } catch (NumberFormatException ignored) { }
            } else {
                double pt = CssStyleParser.parseDimension(wAttr, DEFAULT_SIZE);
                if (pt > 0) { cell.getAttributes().put("width-pt", pt); return; }
            }
        }
        if (cellCtx.getWidthPercent() > 0) {
            cell.getAttributes().put("width-pct", cellCtx.getWidthPercent());
        } else if (cellCtx.getWidth() > 0) {
            cell.getAttributes().put("width-pt", cellCtx.getWidth());
        }
    }

    /** Applies a table cell's background and alignment (from its resolved CSS). */
    private void applyCellStyle(TableCell cell, CssContext ctx) {
        BlockStyle bs = new BlockStyle();
        boolean any = false;
        Color bg = ctx.getBackgroundColor();
        if (bg != null) { bs.setBackground(colorToInt(bg)); any = true; }
        String align = ctx.getTextAlign();
        if (align != null) {
            switch (align.toLowerCase(Locale.ROOT)) {
                case "center": bs.setAlign(BlockStyle.Align.CENTER); any = true; break;
                case "right": bs.setAlign(BlockStyle.Align.RIGHT); any = true; break;
                default: break;
            }
        }
        if (any) {
            cell.setStyle(bs);
        }
    }

    /** Transfers only the renderable box style (background + bottom border) onto
     *  a container, WITHOUT margins — see the div case for why margins are unsafe. */
    private void applyContainerStyle(SdmBlock block, CssContext ctx) {
        BlockStyle bs = new BlockStyle();
        boolean any = false;
        Color bg = ctx.getBackgroundColor();
        if (bg != null) { bs.setBackground(colorToInt(bg)); any = true; }
        String bbStyle = ctx.getBorderBottomStyle();
        if (ctx.getBorderBottomWidth() > 0 && bbStyle != null
                && !"none".equals(bbStyle) && !"hidden".equals(bbStyle)) {
            bs.setBorderBottomWidth(ctx.getBorderBottomWidth());
            bs.setBorderBottomStyle(bbStyle);
            Color bbc = ctx.getBorderBottomColor();
            bs.setBorderBottomColor(bbc != null ? colorToInt(bbc) : 0xFF000000);
            any = true;
        }
        if (any) {
            block.setStyle(bs);
        }
    }

    private SdmBlock applyBlockStyle(SdmBlock block, CssContext ctx, CssContext parent) {
        BlockStyle bs = new BlockStyle();
        boolean any = false;
        String align = ctx.getTextAlign();
        if (align != null) {
            switch (align.toLowerCase(Locale.ROOT)) {
                case "center": bs.setAlign(BlockStyle.Align.CENTER); any = true; break;
                case "right": bs.setAlign(BlockStyle.Align.RIGHT); any = true; break;
                case "justify": bs.setAlign(BlockStyle.Align.JUSTIFY); any = true; break;
                default: break;
            }
        }
        if (ctx.getMarginTop() > 0) { bs.setSpaceBefore(ctx.getMarginTop()); any = true; }
        if (ctx.getMarginBottom() > 0) { bs.setSpaceAfter(ctx.getMarginBottom()); any = true; }
        if (ctx.getMarginLeft() > 0) { bs.setIndentStart(ctx.getMarginLeft()); any = true; }
        if (ctx.getMarginRight() > 0) { bs.setIndentEnd(ctx.getMarginRight()); any = true; }
        Color bg = ctx.getBackgroundColor();
        if (bg != null) { bs.setBackground(colorToInt(bg)); any = true; }
        // Bottom border (e.g. the dotted "border-bottom: 1px dotted #6C8CD9"
        // rule under a heading div). A width without a visible style renders
        // nothing in CSS, so require an explicit non-none/hidden style.
        String bbStyle = ctx.getBorderBottomStyle();
        if (ctx.getBorderBottomWidth() > 0 && bbStyle != null
                && !"none".equals(bbStyle) && !"hidden".equals(bbStyle)) {
            bs.setBorderBottomWidth(ctx.getBorderBottomWidth());
            bs.setBorderBottomStyle(bbStyle);
            Color bbc = ctx.getBorderBottomColor();
            bs.setBorderBottomColor(bbc != null ? colorToInt(bbc) : 0xFF000000);
            any = true;
        }
        if (any) {
            block.setStyle(bs);
        }
        assignId(block);
        return block;
    }

    /**
     * Rebuilds a {@link org.aspose.pdf.sdm.FormField} from an HTML form control
     * ({@code input}/{@code select}/{@code textarea}/{@code button}) — the
     * inverse of the writer's {@code emitFormField}.
     */
    private SdmBlock buildFormField(Element el, String tag) {
        String type = el.getAttribute("type").toLowerCase(Locale.ROOT);
        String dataKind = el.getAttribute("data-field-kind");
        org.aspose.pdf.sdm.FormField.Kind kind;
        boolean multiline = false;
        switch (tag) {
            case "textarea":
                kind = org.aspose.pdf.sdm.FormField.Kind.TEXT;
                multiline = true;
                break;
            case "select":
                kind = "listbox".equals(dataKind)
                        ? org.aspose.pdf.sdm.FormField.Kind.LISTBOX
                        : org.aspose.pdf.sdm.FormField.Kind.COMBOBOX;
                break;
            case "button":
                kind = org.aspose.pdf.sdm.FormField.Kind.BUTTON;
                break;
            default: // input
                if ("checkbox".equals(type)) {
                    kind = org.aspose.pdf.sdm.FormField.Kind.CHECKBOX;
                } else if ("radio".equals(type)) {
                    kind = org.aspose.pdf.sdm.FormField.Kind.RADIO;
                } else if ("button".equals(type) || "submit".equals(type) || "reset".equals(type)) {
                    kind = org.aspose.pdf.sdm.FormField.Kind.BUTTON;
                } else if ("signature".equals(dataKind)) {
                    kind = org.aspose.pdf.sdm.FormField.Kind.SIGNATURE;
                } else {
                    kind = org.aspose.pdf.sdm.FormField.Kind.TEXT;
                }
        }
        org.aspose.pdf.sdm.FormField f =
                new org.aspose.pdf.sdm.FormField(kind, htmlSource(el));
        f.setMultiline(multiline);
        String name = el.getAttribute("name");
        if (name != null && !name.isEmpty()) {
            f.setName(name);
        }
        switch (kind) {
            case CHECKBOX:
            case RADIO: {
                String v = el.getAttribute("value");
                if (v != null && !v.isEmpty()) {
                    f.setExportValue(v);
                }
                f.setChecked(el.hasAttribute("checked"));
                break;
            }
            case COMBOBOX:
            case LISTBOX: {
                NodeList kids = el.getChildNodes();
                for (int i = 0; i < kids.getLength(); i++) {
                    Node k = kids.item(i);
                    if (k.getNodeType() == Node.ELEMENT_NODE
                            && ((Element) k).getTagName().equalsIgnoreCase("option")) {
                        Element opt = (Element) k;
                        String text = textContent(opt);
                        f.getOptions().add(text);
                        if (opt.hasAttribute("selected")) {
                            f.setValue(text);
                        }
                    }
                }
                break;
            }
            case BUTTON: {
                String caption = "button".equals(tag) ? textContent(el) : el.getAttribute("value");
                if (caption != null && !caption.isEmpty()) {
                    f.setValue(caption);
                }
                break;
            }
            default: { // TEXT / SIGNATURE
                String v = "textarea".equals(tag) ? textContent(el) : el.getAttribute("value");
                if (v != null && !v.isEmpty()) {
                    f.setValue(v);
                }
                String ml = el.getAttribute("maxlength");
                if (ml != null && !ml.isEmpty()) {
                    try {
                        f.setMaxLen(Integer.parseInt(ml.trim()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        f.setReadOnly(el.hasAttribute("readonly")
                || "true".equals(el.getAttribute("data-readonly")));
        Double dw = styleLenPt(el, "width");
        Double dh = styleLenPt(el, "height");
        if (dw != null && dw > 0) {
            f.getAttributes().put("display-width", dw);
        }
        if (dh != null && dh > 0) {
            f.getAttributes().put("display-height", dh);
        }
        assignId(f);
        return f;
    }

    private Opaque opaque(Element el) {
        Opaque o = new Opaque(htmlSource(el));
        o.setRenderHint("html:" + el.getTagName().toLowerCase(Locale.ROOT));
        assignId(o);
        return o;
    }

    // ---- Resources ---------------------------------------------------------

    /**
     * True when an {@code <img>} is a decorative backdrop rather than flow
     * content: it carries the writer's {@code bg} class or an absolute/negative
     * z-index positioning in its inline style.
     */
    private static boolean isBackgroundImg(Element img) {
        String cls = img.getAttribute("class");
        if (cls != null) {
            for (String c : cls.trim().split("\\s+")) {
                if (c.equalsIgnoreCase("bg")) {
                    return true;
                }
            }
        }
        String style = img.getAttribute("style");
        if (style != null) {
            String s = style.toLowerCase(Locale.ROOT);
            if (s.contains("position:absolute") || s.contains("z-index:-")) {
                return true;
            }
        }
        return false;
    }

    private ResourceRef putImage(Element img, SdmDocument sdm) {
        String src = img.getAttribute("src");
        if (src == null || src.isEmpty()) {
            return null;
        }
        byte[] bytes = null;
        String mime = null;
        if (src.startsWith("data:")) {
            int comma = src.indexOf(',');
            if (comma > 0) {
                String meta = src.substring(5, comma);
                if (meta.contains(";base64")) {
                    try {
                        bytes = java.util.Base64.getDecoder().decode(src.substring(comma + 1));
                    } catch (IllegalArgumentException ignored) { }
                }
                int semi = meta.indexOf(';');
                mime = semi >= 0 ? meta.substring(0, semi) : meta;
            }
        }
        if (bytes == null) {
            // external/relative reference — resolve against baseUri (disk/file:/URL)
            bytes = fetchResource(src);
            if (bytes != null && bytes.length > 0) {
                mime = guessImageMime(src);
            } else {
                // unresolved — keep the ref so the node survives; record for the report
                report.addExternalImage(src);
                bytes = new byte[0];
                mime = "image/unknown";
            }
        }
        String id = "img-" + (sdm.getResources().size() + 1);
        Resource res = new Resource(Resource.Kind.IMAGE, bytes, mime);
        res.getMeta().put("src", src);
        return sdm.getResources().put(id, res);
    }

    /**
     * Loads the bytes of an external resource referenced by {@code src}, resolving
     * relative references against {@link #baseUri}. Handles absolute/relative
     * filesystem paths, {@code file:} URIs, and — only when {@link #allowNetwork}
     * is set — {@code http:}/{@code https:} URLs. {@code data:} URIs are decoded by
     * the caller. Returns null on any failure, so a broken reference degrades to a
     * placeholder rather than aborting the whole read.
     */
    private byte[] fetchResource(String src) {
        if (src == null || src.isEmpty() || src.startsWith("data:")) {
            return null;
        }
        try {
            String lower = src.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return allowNetwork ? readUrl(new java.net.URL(src)) : null;
            }
            if (lower.startsWith("file:")) {
                return java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get(java.net.URI.create(src)));
            }
            // Relative reference against an http(s) base.
            if (baseUri != null) {
                String bl = baseUri.toLowerCase(Locale.ROOT);
                if (bl.startsWith("http://") || bl.startsWith("https://")) {
                    return allowNetwork
                            ? readUrl(new java.net.URL(new java.net.URL(baseUri), src))
                            : null;
                }
            }
            // Filesystem path (absolute, or relative to the base directory).
            java.io.File f = resolveFile(src);
            if (f != null && f.isFile()) {
                return java.nio.file.Files.readAllBytes(f.toPath());
            }
        } catch (Exception ignored) {
            // unresolved — the caller records the reference on the report
        }
        return null;
    }

    /** Resolves {@code src} to a filesystem file: absolute as-is, otherwise relative
     *  to {@link #baseUri} (treated as a directory, or the parent of a base file). */
    private java.io.File resolveFile(String src) {
        java.io.File direct = new java.io.File(src);
        if (direct.isAbsolute() || baseUri == null || baseUri.isEmpty()) {
            return direct;
        }
        String base = baseUri;
        if (base.toLowerCase(Locale.ROOT).startsWith("file:")) {
            try {
                base = new java.io.File(java.net.URI.create(base)).getPath();
            } catch (Exception ignored) { /* keep the raw string */ }
        }
        java.io.File bf = new java.io.File(base);
        java.io.File dir = bf.isFile() ? bf.getParentFile() : bf;
        if (dir == null) {
            dir = bf;
        }
        return new java.io.File(dir, src);
    }

    private static byte[] readUrl(java.net.URL url) throws java.io.IOException {
        java.net.URLConnection c = url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (java.io.InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** Guesses an image MIME type from the reference's file extension. */
    private static String guessImageMime(String src) {
        String s = src.toLowerCase(Locale.ROOT);
        int cut = s.length();
        int q = s.indexOf('?');
        if (q >= 0) cut = Math.min(cut, q);
        int h = s.indexOf('#');
        if (h >= 0) cut = Math.min(cut, h);
        s = s.substring(0, cut);
        if (s.endsWith(".png")) return "image/png";
        if (s.endsWith(".jpg") || s.endsWith(".jpeg")) return "image/jpeg";
        if (s.endsWith(".gif")) return "image/gif";
        if (s.endsWith(".bmp")) return "image/bmp";
        if (s.endsWith(".tif") || s.endsWith(".tiff")) return "image/tiff";
        if (s.endsWith(".webp")) return "image/webp";
        if (s.endsWith(".svg")) return "image/svg+xml";
        return "image/unknown";
    }

    // ---- helpers -----------------------------------------------------------

    private void assignId(org.aspose.pdf.sdm.SdmNode node) {
        node.setId(SdmIds.sessionNodeId(nsDoc, "html", seq++));
    }

    private SourceRef htmlSource(Element el) {
        return new HtmlSourceRef(el.getTagName().toLowerCase(Locale.ROOT) + "[" + seq + "]");
    }

    /** True when {@code el} has a descendant element that is block-level (a list,
     *  div/section, table, paragraph, heading, list item). Such content nested
     *  inside an inline wrapper must be promoted to real blocks (see walkBlocks). */
    private static boolean containsBlockLevel(Element el) {
        NodeList kids = el.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element c = (Element) n;
            String tag = c.getTagName().toLowerCase(Locale.ROOT);
            if (isBlockLevelTag(tag) || containsBlockLevel(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockLevelTag(String tag) {
        switch (tag) {
            case "div": case "section": case "article": case "header":
            case "footer": case "main": case "aside": case "nav":
            case "ul": case "ol": case "li": case "table": case "tr":
            case "td": case "th": case "thead": case "tbody": case "tfoot":
            case "p": case "h1": case "h2": case "h3": case "h4": case "h5":
            case "h6": case "blockquote": case "pre": case "hr": case "figure":
                return true;
            default:
                return false;
        }
    }

    private static boolean isInlineTag(String tag) {
        switch (tag) {
            case "b": case "strong": case "i": case "em": case "u": case "ins":
            case "s": case "strike": case "del": case "span": case "font":
            case "a": case "br": case "sub": case "sup": case "small": case "code":
            // Inline text containers (HTML default display:inline). Without
            // these a <label> wrapping text (PDFNET_39120: the whole body is
            // <div><label><wild-card>text) fell to buildBlock→opaque and its
            // text was dropped → blank page. collectInline's default arm then
            // recurses through any unknown custom element (e.g. <wild-card>).
            case "label": case "abbr": case "cite": case "q": case "mark":
            case "time": case "output": case "samp": case "kbd": case "var":
            case "bdi": case "bdo": case "wbr":
                return true;
            default:
                return false;
        }
    }

    private static int spanAttr(Element el, String name) {
        String v = el.getAttribute(name);
        if (v != null && !v.isEmpty()) {
            try { return Math.max(1, Integer.parseInt(v.trim())); } catch (NumberFormatException ignored) { }
        }
        return 1;
    }

    private static String languageOf(Element el) {
        String cls = el.getAttribute("class");
        if (cls != null && cls.startsWith("language-")) {
            return cls.substring("language-".length());
        }
        Element code = firstChild(el, "code");
        if (code != null) {
            String c = code.getAttribute("class");
            if (c != null && c.startsWith("language-")) {
                return c.substring("language-".length());
            }
        }
        return null;
    }

    private org.aspose.pdf.sdm.ColumnSpec columnSpec(String w) {
        if (w == null || w.isEmpty()) {
            return new org.aspose.pdf.sdm.ColumnSpec();
        }
        w = w.trim();
        try {
            if (w.endsWith("%")) {
                double pct = Double.parseDouble(w.substring(0, w.length() - 1).trim());
                return new org.aspose.pdf.sdm.ColumnSpec(
                        org.aspose.pdf.sdm.ColumnSpec.WidthType.PERCENT, pct,
                        org.aspose.pdf.sdm.ColumnSpec.Align.LEFT);
            }
            double pts = CssStyleParser.parseDimension(w, DEFAULT_SIZE);
            return new org.aspose.pdf.sdm.ColumnSpec(
                    org.aspose.pdf.sdm.ColumnSpec.WidthType.POINTS, pts,
                    org.aspose.pdf.sdm.ColumnSpec.Align.LEFT);
        } catch (RuntimeException e) {
            return new org.aspose.pdf.sdm.ColumnSpec();
        }
    }

    /** Reads a CSS length declaration from an element's inline {@code style} and
     *  converts it to points, or {@code null} when absent/unparseable. */
    private static Double styleLenPt(Element el, String prop) {
        String style = el.getAttribute("style");
        if (style == null) {
            return null;
        }
        for (String d : style.split(";")) {
            int c = d.indexOf(':');
            if (c > 0 && d.substring(0, c).trim().equalsIgnoreCase(prop)) {
                return parseLenPt(d.substring(c + 1).trim());
            }
        }
        return null;
    }

    /** Parses a CSS length ({@code pt}/{@code px}/{@code in}/{@code cm}/{@code mm},
     *  default {@code pt}) to points. */
    private static Double parseLenPt(String v) {
        if (v == null) {
            return null;
        }
        v = v.trim().toLowerCase(Locale.ROOT);
        double mult = 1.0;
        if (v.endsWith("pt")) { v = v.substring(0, v.length() - 2); }
        else if (v.endsWith("px")) { v = v.substring(0, v.length() - 2); mult = 0.75; }
        else if (v.endsWith("in")) { v = v.substring(0, v.length() - 2); mult = 72.0; }
        else if (v.endsWith("cm")) { v = v.substring(0, v.length() - 2); mult = 28.3465; }
        else if (v.endsWith("mm")) { v = v.substring(0, v.length() - 2); mult = 2.83465; }
        try {
            return Double.parseDouble(v.trim()) * mult;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String styleWidth(Element el) {
        String style = el.getAttribute("style");
        if (style == null) {
            return null;
        }
        for (String d : style.split(";")) {
            int c = d.indexOf(':');
            if (c > 0 && d.substring(0, c).trim().equalsIgnoreCase("width")) {
                return d.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static Element firstChild(Element parent, String tag) {
        if (parent == null) {
            return null;
        }
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                    && ((Element) n).getTagName().equalsIgnoreCase(tag)) {
                return (Element) n;
            }
        }
        // fall back to descendant search (e.g. body under html)
        NodeList all = parent.getElementsByTagName(tag);
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    private static String textContent(Element el) {
        return el.getTextContent() == null ? "" : el.getTextContent();
    }

    private static boolean isBlack(Color c) {
        return c.getR() == 0 && c.getG() == 0 && c.getB() == 0;
    }

    private static int colorToInt(Color c) {
        int r = clamp255(c.getR());
        int g = clamp255(c.getG());
        int b = clamp255(c.getB());
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp255(double v) {
        int i = (int) Math.round(v * 255.0);
        return Math.max(0, Math.min(255, i));
    }

    /** A parsed CSS rule: one simple selector, its declaration block, specificity. */
    private static final class CssRule {
        final String selector;
        final String declarations;
        final int specificity;

        CssRule(String selector, String declarations, int specificity) {
            this.selector = selector;
            this.declarations = declarations;
            this.specificity = specificity;
        }
    }

    /** HTML provenance locator for {@link Opaque}/{@link org.aspose.pdf.sdm.InlineOpaque}. */
    private static final class HtmlSourceRef extends SourceRef {
        private final String locator;

        HtmlSourceRef(String locator) {
            super("html");
            this.locator = locator;
        }

        @Override
        public String canonical() {
            return "html:" + locator;
        }
    }
}
