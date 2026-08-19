package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.SdmNodeType;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * IR Stage 4 PART 1 — {@link HtmlSdmReader} exact-SDM fixtures, CSS cascade
 * cases, and the HTML&rarr;SDM&rarr;HTML round-trip self-check.
 */
public class HtmlSdmReaderTest {

    private static SdmDocument read(String html) {
        return new HtmlSdmReader().read("<html><body>" + html + "</body></html>", null, null);
    }

    // ---- node-type & nesting fixtures --------------------------------------

    @Test
    public void headingsMapToHeadingWithLevel() {
        SdmDocument d = read("<h1>One</h1><h3>Three</h3>");
        assertEquals(2, d.getChildren().size());
        assertEquals(SdmNodeType.HEADING, d.getChildren().get(0).getType());
        assertEquals(1, ((Heading) d.getChildren().get(0)).getLevel());
        assertEquals("One", firstRunText(((Heading) d.getChildren().get(0)).getInline()));
        assertEquals(3, ((Heading) d.getChildren().get(1)).getLevel());
    }

    @Test
    public void paragraphWithInlineEmphasis() {
        SdmDocument d = read("<p>plain <b>bold</b> <i>ital</i> <u>und</u> end</p>");
        Paragraph p = (Paragraph) d.getChildren().get(0);
        // runs: "plain ", "bold"(b), " ", "ital"(i), " ", "und"(u), " end"
        String joined = joinRuns(p.getInline());
        assertEquals("plain bold ital und end", joined.replaceAll("\\s+", " ").trim());
        assertTrue(runWithText(p.getInline(), "bold").getStyle().isBold(), "bold run is bold");
        assertTrue(runWithText(p.getInline(), "ital").getStyle().isItalic(), "ital run is italic");
        assertTrue(runWithText(p.getInline(), "und").getStyle().isUnderline(), "und run is underlined");
        // plain run carries no style (clean round-trip)
        assertTrue(runWithText(p.getInline(), "plain ").getStyle() == null, "plain run has no style");
    }

    @Test
    public void orderedListStartAndNesting() {
        SdmDocument d = read("<ol start=\"3\"><li>a</li><li>b<ul><li>c</li></ul></li></ol>");
        ListBlock ol = (ListBlock) d.getChildren().get(0);
        assertTrue(ol.isOrdered());
        assertEquals(Integer.valueOf(3), ol.getStart());
        assertEquals(2, ol.getItems().size());
        // second item contains a nested unordered list
        List<SdmBlock> item2 = ol.getItems().get(1).getChildren();
        boolean nested = item2.stream().anyMatch(b -> b.getType() == SdmNodeType.LIST_BLOCK);
        assertTrue(nested, "second item has a nested list");
    }

    @Test
    public void tableShapeWithSpansAndHeader() {
        SdmDocument d = read(
                "<table><thead><tr><th>H1</th><th>H2</th></tr></thead>"
                + "<tbody><tr><td colspan=\"2\">wide</td></tr>"
                + "<tr><td rowspan=\"2\">tall</td><td>x</td></tr></tbody></table>");
        Table t = (Table) d.getChildren().get(0);
        assertEquals(3, t.getRows().size());
        assertEquals(TableRow.Kind.HEADER, t.getRows().get(0).getKind());
        assertEquals(TableCell.Kind.TH, t.getRows().get(0).getCells().get(0).getKind());
        assertEquals(2, t.getRows().get(1).getCells().get(0).getColSpan());
        assertEquals(2, t.getRows().get(2).getCells().get(0).getRowSpan());
    }

    @Test
    public void blockquoteCodeHrContainerAndOpaque() {
        SdmDocument d = read("<blockquote><p>q</p></blockquote>"
                + "<pre><code class=\"language-java\">x=1;</code></pre>"
                + "<hr/><div><p>in div</p></div><marquee>weird</marquee>");
        assertEquals(SdmNodeType.QUOTE, d.getChildren().get(0).getType());
        CodeBlock cb = (CodeBlock) d.getChildren().get(1);
        assertEquals("java", cb.getLanguage());
        assertTrue(cb.getText().contains("x=1;"));
        assertEquals(SdmNodeType.THEMATIC_BREAK, d.getChildren().get(2).getType());
        assertEquals(SdmNodeType.CONTAINER, d.getChildren().get(3).getType());
        assertEquals(SdmNodeType.OPAQUE, d.getChildren().get(4).getType());
    }

    @Test
    public void backgroundImgBecomesBackdropFigureWithDisplaySize() {
        // A bare, absolutely-positioned <img class="bg"> is the writer's watermark
        // backdrop; the reader must flag it background + carry its display size so
        // the layout paints it out of flow instead of consuming a whole page.
        String png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1"
                + "HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        SdmDocument d = read("<img class=\"bg\" src=\"" + png
                + "\" style=\"position:absolute;left:50%;z-index:-1;opacity:0.18;"
                + "width:534pt;height:630pt\"/>");
        org.aspose.pdf.sdm.Figure fig = (org.aspose.pdf.sdm.Figure) d.getChildren().get(0);
        assertEquals(Boolean.TRUE, fig.getAttributes().get("background"),
                "bg img flagged as backdrop");
        assertEquals(534.0, ((Number) fig.getAttributes().get("display-width")).doubleValue(), 0.01);
        assertEquals(630.0, ((Number) fig.getAttributes().get("display-height")).doubleValue(), 0.01);
    }

    // ---- CSS cascade cases -------------------------------------------------

    @Test
    public void inheritedFontSizeFlowsToChild() {
        SdmDocument d = read("<div style=\"font-size:20px\"><p>child</p></div>");
        Container div = (Container) d.getChildren().get(0);
        Paragraph p = (Paragraph) div.getChildren().get(0);
        TextStyle st = runWithText(p.getInline(), "child").getStyle();
        assertNotNull(st, "inherited font-size produces explicit style");
        // The existing CssStyleParser treats px as pt (1:1), so 20px -> 20pt;
        // the point of this fixture is that the size INHERITS div -> child p.
        assertEquals(20.0, st.getFontSize(), 0.01);
    }

    @Test
    public void specificityWinnerAppliesOverTagRule() {
        String html = "<html><head><style>"
                + "p { color: #ff0000; } .hi { color: #00ff00; }"
                + "</style></head><body><p class=\"hi\">t</p></body></html>";
        SdmDocument d = new HtmlSdmReader().read(html, null, null);
        Paragraph p = (Paragraph) d.getChildren().get(0);
        TextStyle st = runWithText(p.getInline(), "t").getStyle();
        // class (.hi, spec 10) beats tag (p, spec 1) -> green wins
        assertEquals(0xFF00FF00, st.getColor());
    }

    @Test
    public void inlineStyleOverridesStylesheet() {
        String html = "<html><head><style>p{color:#ff0000}</style></head>"
                + "<body><p style=\"color:#0000ff\">t</p></body></html>";
        SdmDocument d = new HtmlSdmReader().read(html, null, null);
        Paragraph p = (Paragraph) d.getChildren().get(0);
        assertEquals(0xFF0000FF, runWithText(p.getInline(), "t").getStyle().getColor());
    }

    @Test
    public void marginShorthandExpandsToBlockStyle() {
        SdmDocument d = read("<p style=\"margin: 10pt 20pt\">t</p>");
        Paragraph p = (Paragraph) d.getChildren().get(0);
        assertNotNull(p.getStyle());
        assertEquals(10.0, p.getStyle().getSpaceBefore(), 0.01);
        assertEquals(10.0, p.getStyle().getSpaceAfter(), 0.01);
        assertEquals(20.0, p.getStyle().getIndentStart(), 0.01);
        assertEquals(20.0, p.getStyle().getIndentEnd(), 0.01);
    }

    @Test
    public void textAlignBecomesBlockAlign() {
        SdmDocument d = read("<p style=\"text-align:center\">t</p>");
        Paragraph p = (Paragraph) d.getChildren().get(0);
        assertEquals(org.aspose.pdf.sdm.BlockStyle.Align.CENTER, p.getStyle().getAlign());
    }

    // ---- round-trip HTML -> SDM -> HTML -------------------------------------

    @Test
    public void roundTripStructurallyEquivalent() throws Exception {
        String html = "<html><body>"
                + "<h1>Title</h1>"
                + "<p>Body <b>bold</b> and <i>italic</i> text.</p>"
                + "<ul><li>one</li><li>two</li></ul>"
                + "<blockquote><p>quoted</p></blockquote>"
                + "</body></html>";
        SdmDocument sdm = new HtmlSdmReader().read(html, null, null);
        String out = new SdmHtmlWriter().write(sdm);
        Element body = bodyOf(out);

        // structural tags present in order
        List<String> blockTags = childElementTags(body);
        assertEquals(List.of("h1", "p", "ul", "blockquote"), blockTags);
        // text preserved
        assertEquals("Title", text(childByTag(body, "h1")));
        assertEquals("Body bold and italic text.", text(childByTag(body, "p")).replaceAll("\\s+", " "));
        assertNotNull(descendant(childByTag(body, "p"), "b"), "bold survives round-trip");
        assertNotNull(descendant(childByTag(body, "p"), "i"), "italic survives round-trip");
        Element ul = childByTag(body, "ul");
        assertEquals(2, ul.getElementsByTagName("li").getLength());
    }

    @Test
    public void roundTripNoLoss_report() {
        SdmDocument sdm1 = new HtmlSdmReader().read(
                "<html><body><p>x</p><customtag>y</customtag></body></html>", null, null);
        // unknown element recorded, not dropped: an Opaque node exists
        boolean opaque = sdm1.getChildren().stream()
                .anyMatch(b -> b.getType() == SdmNodeType.OPAQUE);
        assertTrue(opaque, "unknown element degraded to Opaque, not dropped");
    }

    // ---- helpers -----------------------------------------------------------

    private static String firstRunText(List<SdmInline> inlines) {
        for (SdmInline in : inlines) {
            if (in instanceof Run) {
                return ((Run) in).getText();
            }
        }
        return null;
    }

    private static String joinRuns(List<SdmInline> inlines) {
        StringBuilder sb = new StringBuilder();
        collectRunText(inlines, sb);
        return sb.toString();
    }

    private static void collectRunText(List<SdmInline> inlines, StringBuilder sb) {
        for (SdmInline in : inlines) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            } else if (in instanceof org.aspose.pdf.sdm.LinkInline) {
                collectRunText(((org.aspose.pdf.sdm.LinkInline) in).getChildren(), sb);
            }
        }
    }

    private static Run runWithText(List<SdmInline> inlines, String text) {
        for (SdmInline in : inlines) {
            if (in instanceof Run && ((Run) in).getText().equals(text)) {
                return (Run) in;
            }
        }
        throw new AssertionError("no run with text '" + text + "' in " + inlines);
    }

    private static Element bodyOf(String html) throws Exception {
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        return (Element) dom.getElementsByTagName("body").item(0);
    }

    private static List<String> childElementTags(Element parent) {
        List<String> tags = new java.util.ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) {
                tags.add(((Element) kids.item(i)).getTagName().toLowerCase());
            }
        }
        return tags;
    }

    private static Element childByTag(Element parent, String tag) {
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE
                    && ((Element) kids.item(i)).getTagName().equalsIgnoreCase(tag)) {
                return (Element) kids.item(i);
            }
        }
        return null;
    }

    private static Element descendant(Element parent, String tag) {
        NodeList all = parent.getElementsByTagName(tag);
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    private static String text(Element el) {
        return el.getTextContent() == null ? "" : el.getTextContent().trim();
    }
}
