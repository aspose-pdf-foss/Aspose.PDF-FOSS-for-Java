package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.FootnoteRef;
import org.aspose.pdf.sdm.Heading;
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
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;
import org.aspose.pdf.sdm.TocBlock;
import org.aspose.pdf.sdm.TocEntry;
import org.aspose.pdf.sdm.ContentRange;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * IR Stage 3 PART 1 gate: the structural SDM &rarr; HTML5 writer.
 *
 * <p>Every fixture is built in-test from SDM nodes; the writer's output is parsed
 * back with the zero-dep {@link HtmlTagParser} and the DOM tree (tags, nesting,
 * attributes) is asserted exactly — no whitespace-fragile string comparison.
 * The [TXT] invariant asserts the HTML text content equals the SDM text.</p>
 */
public class SdmHtmlWriterTest {

    // ------------------------------------------------------------------
    // DOM helpers
    // ------------------------------------------------------------------

    private static Element bodyOf(String html) throws IOException {
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        NodeList list = dom.getElementsByTagName("body");
        assertEquals(1, list.getLength(), "exactly one <body>");
        return (Element) list.item(0);
    }

    private static List<Element> kids(Element e) {
        List<Element> out = new ArrayList<>();
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static String textOf(Node e) {
        return e.getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static Element only(Element parent, String tag) {
        List<Element> match = new ArrayList<>();
        for (Element k : kids(parent)) {
            if (k.getNodeName().equalsIgnoreCase(tag)) {
                match.add(k);
            }
        }
        assertEquals(1, match.size(), "exactly one <" + tag + "> under <" + parent.getNodeName() + ">");
        return match.get(0);
    }

    private static String styleTextOf(String html) throws IOException {
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        NodeList styles = dom.getElementsByTagName("style");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < styles.getLength(); i++) {
            sb.append(styles.item(i).getTextContent());
        }
        return sb.toString();
    }

    private static Paragraph para(String text) {
        Paragraph p = new Paragraph();
        p.getInline().add(new Run(text, null));
        return p;
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Headings h1..h6 map to their exact tags; paragraph text survives. */
    @Test
    public void headingsAndParagraphs() throws IOException {
        SdmDocument doc = new SdmDocument();
        Heading h1 = new Heading(1);
        h1.getInline().add(new Run("Title", null));
        Heading h3 = new Heading(3);
        h3.getInline().add(new Run("Section", null));
        doc.getChildren().add(h1);
        doc.getChildren().add(para("Body text one."));
        doc.getChildren().add(h3);
        doc.getChildren().add(para("Body text two."));

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        List<Element> top = kids(body);
        assertEquals(4, top.size());
        assertEquals("h1", top.get(0).getNodeName().toLowerCase());
        assertEquals("Title", textOf(top.get(0)));
        assertEquals("p", top.get(1).getNodeName().toLowerCase());
        assertEquals("Body text one.", textOf(top.get(1)));
        assertEquals("h3", top.get(2).getNodeName().toLowerCase());
        assertEquals("Section", textOf(top.get(2)));
        assertEquals("p", top.get(3).getNodeName().toLowerCase());
    }

    /** Nested list: ul > li[1] contains an ol with start=3; li text is inlined (no p wrapper). */
    @Test
    public void nestedListWithStart() throws IOException {
        SdmDocument doc = new SdmDocument();
        ListBlock ul = new ListBlock(false, null);
        ListItem li1 = new ListItem();
        li1.getChildren().add(para("first"));
        ListItem li2 = new ListItem();
        li2.getChildren().add(para("second"));
        ListBlock ol = new ListBlock(true, 3);
        ListItem oli = new ListItem();
        oli.getChildren().add(para("third-nested"));
        ol.getItems().add(oli);
        li2.getChildren().add(ol);
        ul.getItems().add(li1);
        ul.getItems().add(li2);
        doc.getChildren().add(ul);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element ulEl = only(body, "ul");
        List<Element> items = kids(ulEl);
        assertEquals(2, items.size());
        assertEquals("li", items.get(0).getNodeName().toLowerCase());
        assertEquals("first", textOf(items.get(0)));
        Element li2El = items.get(1);
        Element olEl = only(li2El, "ol");
        assertEquals("3", olEl.getAttribute("start"));
        List<Element> oItems = kids(olEl);
        assertEquals(1, oItems.size());
        assertEquals("third-nested", textOf(oItems.get(0)));
        // li2's own paragraph text is present before the nested list
        assertTrue(textOf(li2El).startsWith("second"));
    }

    /** Table: caption, colgroup widths, thead th cells, tbody with colspan/rowspan attributes. */
    @Test
    public void tableWithSpans() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table table = new Table();
        table.setCaption("Quarterly");
        table.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS, 120, ColumnSpec.Align.LEFT));
        table.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.PERCENT, 40, ColumnSpec.Align.RIGHT));

        TableRow header = new TableRow(TableRow.Kind.HEADER);
        TableCell th1 = new TableCell();
        th1.setKind(TableCell.Kind.TH);
        th1.getChildren().add(para("Name"));
        TableCell th2 = new TableCell();
        th2.setKind(TableCell.Kind.TH);
        th2.getChildren().add(para("Value"));
        header.getCells().add(th1);
        header.getCells().add(th2);
        table.getRows().add(header);

        TableRow row1 = new TableRow(TableRow.Kind.BODY);
        TableCell spanned = new TableCell();
        spanned.setColSpan(2);
        spanned.getChildren().add(para("Merged"));
        row1.getCells().add(spanned);
        table.getRows().add(row1);

        TableRow row2 = new TableRow(TableRow.Kind.BODY);
        TableCell tall = new TableCell();
        tall.setRowSpan(2);
        tall.getChildren().add(para("Tall"));
        TableCell plain = new TableCell();
        plain.getChildren().add(para("Plain"));
        row2.getCells().add(tall);
        row2.getCells().add(plain);
        table.getRows().add(row2);

        doc.getChildren().add(table);
        String html = new SdmHtmlWriter().write(doc);
        Element body = bodyOf(html);
        Element tableEl = only(body, "table");
        assertEquals("Quarterly", textOf(only(tableEl, "caption")));

        Element colgroup = only(tableEl, "colgroup");
        assertEquals(2, kids(colgroup).size());
        String styles = styleTextOf(html);
        assertTrue(styles.contains("width:120pt"), "colgroup pt width in style: " + styles);
        assertTrue(styles.contains("width:40%"), "colgroup % width in style: " + styles);

        Element thead = only(tableEl, "thead");
        List<Element> headCells = kids(kids(thead).get(0));
        assertEquals(2, headCells.size());
        assertEquals("th", headCells.get(0).getNodeName().toLowerCase());
        assertEquals("Name", textOf(headCells.get(0)));

        Element tbody = only(tableEl, "tbody");
        List<Element> bodyRows = kids(tbody);
        assertEquals(2, bodyRows.size());
        Element mergedTd = kids(bodyRows.get(0)).get(0);
        assertEquals("td", mergedTd.getNodeName().toLowerCase());
        assertEquals("2", mergedTd.getAttribute("colspan"));
        Element tallTd = kids(bodyRows.get(1)).get(0);
        assertEquals("2", tallTd.getAttribute("rowspan"));
        assertEquals("Tall", textOf(tallTd));
    }

    /** A ruled table (border=ruled) emits class="ruled" and border CSS; a plain one does not. */
    @Test
    public void ruledTableGetsBorderCss() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table table = new Table();
        table.getAttributes().put("border", "ruled");
        TableRow row = new TableRow(TableRow.Kind.BODY);
        TableCell cell = new TableCell();
        cell.getChildren().add(para("A"));
        row.getCells().add(cell);
        table.getRows().add(row);
        doc.getChildren().add(table);

        String html = new SdmHtmlWriter().write(doc);
        Element tableEl = only(bodyOf(html), "table");
        assertTrue(tableEl.getAttribute("class").contains("ruled"), "ruled table tagged");
        String styles = styleTextOf(html);
        assertTrue(styles.contains("table.ruled") && styles.contains("border:1px solid"),
                "border CSS present: " + styles);

        // A plain table stays borderless — no ruled class, no border rule.
        SdmDocument doc2 = new SdmDocument();
        Table plain = new Table();
        TableRow r2 = new TableRow(TableRow.Kind.BODY);
        TableCell c2 = new TableCell();
        c2.getChildren().add(para("B"));
        r2.getCells().add(c2);
        plain.getRows().add(r2);
        doc2.getChildren().add(plain);
        String html2 = new SdmHtmlWriter().write(doc2);
        assertFalse(only(bodyOf(html2), "table").getAttribute("class").contains("ruled"),
                "plain table not tagged ruled");
        assertFalse(styleTextOf(html2).contains("table.ruled"), "no border CSS for plain table");
    }

    /** Page-margin metadata insets the {@code <body>}; absent, the body has no style. */
    @Test
    public void bodyGetsPageMarginFromMetadata() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getMetadata().getCustom().put("margin-left", "72.00");
        doc.getMetadata().getCustom().put("margin-right", "60.00");
        doc.getMetadata().getCustom().put("margin-top", "48.00");
        doc.getChildren().add(para("Body text"));
        String html = new SdmHtmlWriter().write(doc);
        String styles = styleTextOf(html);
        assertTrue(styles.contains("body{margin:0;padding:48.00pt 60.00pt 48.00pt 72.00pt !important}"),
                "body inset by page padding (top right bottom left): " + styles);
        // Also inline on <body> so a simple viewer that ignores <style> still insets.
        assertTrue(bodyOf(html).getAttribute("style").contains("padding:48.00pt"),
                "body carries inline padding too");

        // No margin metadata → no body padding rule.
        SdmDocument bare = new SdmDocument();
        bare.getChildren().add(para("Plain"));
        assertFalse(styleTextOf(new SdmHtmlWriter().write(bare)).contains("padding:"),
                "no body padding without page-margin metadata");
    }

    /** Figure: img with data-URI src + alt, figcaption text. */
    @Test
    public void figureWithCaption() throws IOException {
        SdmDocument doc = new SdmDocument();
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ResourceRef ref = doc.getResources().put("img1", new Resource(Resource.Kind.IMAGE, bytes, "image/png"));
        Figure fig = new Figure(ref);
        fig.setAlt("logo");
        fig.getCaption().add(para("Figure 1. The logo"));
        doc.getChildren().add(fig);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element figEl = only(body, "figure");
        Element img = only(figEl, "img");
        assertTrue(img.getAttribute("src").startsWith("data:image/png;base64,"), "data URI src");
        assertEquals("logo", img.getAttribute("alt"));
        assertEquals(java.util.Base64.getEncoder().encodeToString(bytes),
                img.getAttribute("src").substring("data:image/png;base64,".length()),
                "exact base64 payload");
        assertEquals("Figure 1. The logo", textOf(only(figEl, "figcaption")));
    }

    /** A background watermark emits a bare bg img, faded enough to be visible behind text. */
    @Test
    public void backgroundWatermarkVisibleBehindText() throws IOException {
        SdmDocument doc = new SdmDocument();
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ResourceRef ref = doc.getResources().put("wm", new Resource(Resource.Kind.IMAGE, bytes, "image/png"));
        Figure fig = new Figure(ref);
        fig.getAttributes().put("background", Boolean.TRUE);
        fig.getAttributes().put("display-width", 534.0);
        fig.getAttributes().put("display-height", 630.0);
        doc.getChildren().add(fig);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element img = only(body, "img");
        assertEquals("bg", img.getAttribute("class"));
        String style = img.getAttribute("style");
        assertTrue(style.contains("z-index:-1"), "behind the text: " + style);
        assertTrue(style.contains("opacity:0.45"),
                "opacity high enough to show against white (0.18 washed out): " + style);
    }

    /** A caption-less image figure is inline-block so pictogram rows flow horizontally. */
    @Test
    public void imageOnlyFigureIsInlineBlock() throws IOException {
        SdmDocument doc = new SdmDocument();
        byte[] bytes = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ResourceRef ref = doc.getResources().put("pic", new Resource(Resource.Kind.IMAGE, bytes, "image/png"));
        doc.getChildren().add(new Figure(ref));

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element figEl = only(body, "figure");
        assertTrue(figEl.getAttribute("style").contains("display:inline-block"),
                "image-only figure flows inline: " + figEl.getAttribute("style"));
    }

    /** Quote and CodeBlock: blockquote>p; pre>code with language class and verbatim escaped text. */
    @Test
    public void quoteAndCode() throws IOException {
        SdmDocument doc = new SdmDocument();
        Quote q = new Quote();
        q.getChildren().add(para("Quoted words."));
        doc.getChildren().add(q);
        doc.getChildren().add(new CodeBlock("if (a < b) { return a & b; }", "java"));
        doc.getChildren().add(new ThematicBreak());

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element bq = only(body, "blockquote");
        assertEquals("Quoted words.", textOf(only(bq, "p")));
        Element pre = only(body, "pre");
        Element code = only(pre, "code");
        assertEquals("language-java", code.getAttribute("class"));
        assertEquals("if (a < b) { return a & b; }", code.getTextContent(), "code verbatim (entities round-trip)");
        only(body, "hr");
    }

    /** Footnote pattern: sup+id links in both directions. */
    @Test
    public void footnotePattern() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = new Paragraph();
        p.getInline().add(new Run("Claim", null));
        p.getInline().add(new FootnoteRef("1"));
        doc.getChildren().add(p);
        Footnote fn = new Footnote("1");
        fn.getChildren().add(para("Source of the claim."));
        doc.getChildren().add(fn);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element pEl = only(body, "p");
        Element sup = only(pEl, "sup");
        assertEquals("fnref-1", sup.getAttribute("id"));
        Element supLink = only(sup, "a");
        assertEquals("#fn-1", supLink.getAttribute("href"));
        assertEquals("1", textOf(supLink));

        Element fnDiv = only(body, "div");
        assertEquals("fn-1", fnDiv.getAttribute("id"));
        assertEquals("footnote", fnDiv.getAttribute("class"));
        Element backSup = only(fnDiv, "sup");
        assertEquals("#fnref-1", only(backSup, "a").getAttribute("href"));
        assertTrue(textOf(fnDiv).contains("Source of the claim."));
    }

    /** Styled runs: b/i/u/s/sup as tags; font/size/colour as ONE shared span class in <style>. */
    @Test
    public void styledRunsMinimalMarkup() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = new Paragraph();
        TextStyle bold = new TextStyle();
        bold.setBold(true);
        p.getInline().add(new Run("fat", bold));
        TextStyle iu = new TextStyle();
        iu.setItalic(true);
        iu.setUnderline(true);
        p.getInline().add(new Run("slant", iu));
        TextStyle styled = new TextStyle();
        styled.setFontFamily("Courier");
        styled.setFontSize(14);
        styled.setColor(0xFFFF0000);
        p.getInline().add(new Run("red", styled));
        TextStyle supSt = new TextStyle();
        supSt.setVertAlign(TextStyle.VertAlign.SUPER);
        p.getInline().add(new Run("2", supSt));
        doc.getChildren().add(p);

        String html = new SdmHtmlWriter().write(doc);
        Element pEl = only(bodyOf(html), "p");

        Element b = only(pEl, "b");
        assertEquals("fat", textOf(b));
        assertEquals(0, kids(b).size(), "bold via <b> only, no span spam");

        Element i = only(pEl, "i");
        Element u = only(i, "u");
        assertEquals("slant", textOf(u));

        Element span = only(pEl, "span");
        assertEquals("red", textOf(span));
        String cls = span.getAttribute("class");
        assertFalse(cls.isEmpty(), "styled run gets a class");
        String styles = styleTextOf(html);
        assertTrue(styles.contains("." + cls + "{"), "class defined in <style>");
        assertTrue(styles.contains("font-family:'Courier'"), styles);
        assertTrue(styles.contains("font-size:14pt"), styles);
        assertTrue(styles.contains("color:#ff0000"), styles);

        Element sup = only(pEl, "sup");
        assertEquals("2", textOf(sup));
    }

    /** Identical TextStyles share one CSS class (collection, not inline spam). */
    @Test
    public void styleClassesShared() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = new Paragraph();
        TextStyle s1 = new TextStyle();
        s1.setFontSize(14);
        TextStyle s2 = new TextStyle();
        s2.setFontSize(14);
        p.getInline().add(new Run("one", s1));
        p.getInline().add(new Run("two", s2));
        doc.getChildren().add(p);

        String html = new SdmHtmlWriter().write(doc);
        Element pEl = only(bodyOf(html), "p");
        List<Element> spans = kids(pEl);
        assertEquals(2, spans.size());
        assertEquals(spans.get(0).getAttribute("class"), spans.get(1).getAttribute("class"),
                "same declarations → same class");
        String styles = styleTextOf(html);
        int first = styles.indexOf("font-size:14pt");
        assertTrue(first >= 0);
        assertEquals(first, styles.lastIndexOf("font-size:14pt"), "declaration emitted once");
    }

    /** BlockStyle: align/margins/indent → class on the block element. */
    @Test
    public void blockStyleMapping() throws IOException {
        SdmDocument doc = new SdmDocument();
        Paragraph p = para("Centered text");
        BlockStyle st = new BlockStyle();
        st.setAlign(BlockStyle.Align.CENTER);
        st.setSpaceBefore(12);
        st.setIndentFirstLine(18.5);
        p.setStyle(st);
        doc.getChildren().add(p);

        String html = new SdmHtmlWriter().write(doc);
        Element pEl = only(bodyOf(html), "p");
        String cls = pEl.getAttribute("class");
        assertFalse(cls.isEmpty());
        String styles = styleTextOf(html);
        assertTrue(styles.contains("." + cls + "{"));
        assertTrue(styles.contains("text-align:center"), styles);
        assertTrue(styles.contains("margin-top:12pt"), styles);
        assertTrue(styles.contains("text-indent:18.5pt"), styles);
    }

    /** TOC: nav with nested ul, links to node ids; target heading carries the id attribute. */
    @Test
    public void tocNavAndAnchors() throws IOException {
        SdmDocument doc = new SdmDocument();
        Heading target = new Heading(1);
        target.setId("guid-h1");
        target.getInline().add(new Run("Chapter One", null));
        TocBlock toc = new TocBlock();
        toc.getEntries().add(new TocEntry(1, "Chapter One", "guid-h1"));
        toc.getEntries().add(new TocEntry(2, "Sub A", null));
        toc.getEntries().add(new TocEntry(2, "Sub B", null));
        toc.getEntries().add(new TocEntry(1, "Chapter Two", null));
        doc.getChildren().add(toc);
        doc.getChildren().add(target);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element nav = only(body, "nav");
        assertEquals("toc", nav.getAttribute("class"));
        Element ul = only(nav, "ul");
        List<Element> lis = kids(ul);
        assertEquals(2, lis.size(), "two level-1 entries");
        Element li1 = lis.get(0);
        Element link = only(li1, "a");
        assertEquals("#guid-h1", link.getAttribute("href"));
        assertEquals("Chapter One", textOf(link));
        Element nested = only(li1, "ul");
        assertEquals(2, kids(nested).size(), "two level-2 entries nested under the first");
        assertEquals("Sub A", textOf(kids(nested).get(0)));
        assertEquals("Chapter Two", textOf(lis.get(1)));

        Element h1 = only(body, "h1");
        assertEquals("guid-h1", h1.getAttribute("id"), "link target carries its id");
    }

    /** Opaque degrades to a placeholder div with data-source provenance — never silently dropped. */
    @Test
    public void opaquePlaceholder() throws IOException {
        SdmDocument doc = new SdmDocument();
        Opaque op = new Opaque(new ContentRange(7, 3, 9));
        op.setRenderHint("vector");
        doc.getChildren().add(op);

        Element body = bodyOf(new SdmHtmlWriter().write(doc));
        Element div = only(body, "div");
        assertEquals("opaque", div.getAttribute("class"));
        assertEquals("pdf:content:7:3-9", div.getAttribute("data-source"));
        assertEquals("vector", div.getAttribute("data-render-hint"));
        assertEquals("", textOf(div));
    }

    /** Links and metadata: a[href]; head carries title/author/lang. */
    @Test
    public void linksAndMetadata() throws IOException {
        SdmDocument doc = new SdmDocument();
        doc.getMetadata().setTitle("Ti & Tle");
        doc.getMetadata().setAuthor("A. Author");
        doc.getMetadata().setLang("en-US");
        Paragraph p = new Paragraph();
        LinkInline link = new LinkInline("https://example.com/a?b=1&c=2");
        link.getChildren().add(new Run("visit", null));
        p.getInline().add(link);
        doc.getChildren().add(p);

        String html = new SdmHtmlWriter().write(doc);
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        Element htmlEl = dom.getDocumentElement();
        assertEquals("en-US", htmlEl.getAttribute("lang"));
        NodeList titles = dom.getElementsByTagName("title");
        assertEquals(1, titles.getLength());
        assertEquals("Ti & Tle", textOf(titles.item(0)));
        NodeList metas = dom.getElementsByTagName("meta");
        boolean author = false;
        for (int i = 0; i < metas.getLength(); i++) {
            Element m = (Element) metas.item(i);
            if ("author".equals(m.getAttribute("name"))) {
                assertEquals("A. Author", m.getAttribute("content"));
                author = true;
            }
        }
        assertTrue(author, "author meta present");

        Element a = only(only(bodyOf(html), "p"), "a");
        assertEquals("https://example.com/a?b=1&c=2", a.getAttribute("href"));
        assertEquals("visit", textOf(a));
    }

    /**
     * Regression for the Link-2 (SDM&rarr;HTML) writer fidelity bug that the
     * two-link oracle revealed (see {@code TwoLinkOracleTest}): a single-paragraph
     * table cell was inlined without its {@code <p>} wrapper and cells were emitted
     * {@code </td><td>} with no separating whitespace, so text extraction of the
     * HTML glued adjacent cell text into one token (e.g. "TORINODossier" from the
     * 28353.pdf memo form). Cells MUST be whitespace-separated in the text stream.
     */
    @Test
    public void adjacentTableCellsAreWhitespaceSeparatedInText() throws IOException {
        SdmDocument doc = new SdmDocument();
        Table table = new Table();
        TableRow row = new TableRow(TableRow.Kind.BODY);
        TableCell c1 = new TableCell();
        c1.getChildren().add(para("TORINO"));
        TableCell c2 = new TableCell();
        c2.getChildren().add(para("Dossier"));
        row.getCells().add(c1);
        row.getCells().add(c2);
        table.getRows().add(row);
        doc.getChildren().add(table);

        String html = new SdmHtmlWriter().write(doc);
        String bodyText = textOf(bodyOf(html));
        assertTrue(bodyText.contains("TORINO Dossier"),
                "adjacent cells must be whitespace-separated in extracted text, got: " + bodyText);
        assertFalse(bodyText.contains("TORINODossier"),
                "adjacent cell text must not be glued together");
    }

    // ------------------------------------------------------------------
    // [TXT] invariant on the everything-fixture
    // ------------------------------------------------------------------

    private static String sdmText(SdmDocument doc) {
        StringBuilder sb = new StringBuilder();
        collectBlocks(sb, doc.getChildren());
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static void collectBlocks(StringBuilder sb, List<? extends SdmBlock> blocks) {
        for (SdmBlock b : blocks) {
            switch (b.getType()) {
                case HEADING: collectInlines(sb, ((Heading) b).getInline()); break;
                case PARAGRAPH: collectInlines(sb, ((Paragraph) b).getInline()); break;
                case LIST_BLOCK:
                    for (ListItem li : ((ListBlock) b).getItems()) {
                        collectBlocks(sb, li.getChildren());
                    }
                    break;
                case TABLE: {
                    Table t = (Table) b;
                    if (t.getCaption() != null) {
                        sb.append(t.getCaption()).append(' ');
                    }
                    for (TableRow r : t.getRows()) {
                        for (TableCell c : r.getCells()) {
                            collectBlocks(sb, c.getChildren());
                        }
                    }
                    break;
                }
                case FIGURE: collectBlocks(sb, ((Figure) b).getCaption()); break;
                case QUOTE: collectBlocks(sb, ((Quote) b).getChildren()); break;
                case CODE_BLOCK: sb.append(((CodeBlock) b).getText()).append(' '); break;
                case TOC_BLOCK:
                    for (TocEntry e : ((TocBlock) b).getEntries()) {
                        sb.append(e.getText()).append(' ');
                    }
                    break;
                case FOOTNOTE:
                    sb.append(((Footnote) b).getRefId()).append(' ');
                    collectBlocks(sb, ((Footnote) b).getChildren());
                    break;
                default:
                    break;
            }
            sb.append(' ');
        }
    }

    private static void collectInlines(StringBuilder sb, List<SdmInline> inlines) {
        for (SdmInline in : inlines) {
            switch (in.getType()) {
                case RUN: sb.append(((Run) in).getText()); break;
                case LINK_INLINE: collectInlines(sb, ((LinkInline) in).getChildren()); break;
                case FOOTNOTE_REF: sb.append(((FootnoteRef) in).getRefId()); break;
                case LINE_BREAK: sb.append(' '); break;
                default: break;
            }
        }
    }

    /** [TXT]: the HTML text content equals the SDM text on a fixture using every node type. */
    @Test
    public void txtInvariantEverythingFixture() throws IOException {
        SdmDocument doc = new SdmDocument();
        Heading h = new Heading(2);
        h.getInline().add(new Run("Everything", null));
        doc.getChildren().add(h);
        Paragraph p = new Paragraph();
        TextStyle bold = new TextStyle();
        bold.setBold(true);
        p.getInline().add(new Run("Styled ", bold));
        LinkInline link = new LinkInline("https://x.y");
        link.getChildren().add(new Run("link", null));
        p.getInline().add(link);
        p.getInline().add(new FootnoteRef("7"));
        doc.getChildren().add(p);
        ListBlock list = new ListBlock(true, 2);
        ListItem li = new ListItem();
        li.getChildren().add(para("item text"));
        list.getItems().add(li);
        doc.getChildren().add(list);
        Table t = new Table();
        t.setCaption("Cap");
        TableRow tr = new TableRow(TableRow.Kind.BODY);
        TableCell tc = new TableCell();
        tc.getChildren().add(para("cell"));
        tr.getCells().add(tc);
        t.getRows().add(tr);
        doc.getChildren().add(t);
        Quote q = new Quote();
        q.getChildren().add(para("quoted"));
        doc.getChildren().add(q);
        doc.getChildren().add(new CodeBlock("x = 1", null));
        Footnote fn = new Footnote("7");
        fn.getChildren().add(para("note body"));
        doc.getChildren().add(fn);

        String html = new SdmHtmlWriter().write(doc);
        assertEquals(sdmText(doc), textOf(bodyOf(html)), "[TXT] HTML text == SDM text");
    }
}
