package org.aspose.pdf.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Regression coverage for the malformed-legacy-HTML repairs that let large forms
 * (e.g. Loan Closing exports) parse structurally instead of collapsing to the
 * plain-text fallback.
 */
public class HtmlTagParserRobustnessTest {

    private static int count(Document dom, String tag) {
        return dom.getDocumentElement().getElementsByTagName(tag).getLength();
    }

    /** Markup that precedes the <html> element must not become a competing root. */
    @Test
    public void markupBeforeHtmlElementIsNotASecondRoot() throws Exception {
        String html = "<table><tr><td>Header</td></tr></table><br>"
                + "<html><head></head><body><p>Body</p></body></html>";
        Document dom = HtmlTagParser.parse(html);
        assertTrue(count(dom, "table") >= 1, "leading table should survive");
        assertTrue(count(dom, "p") >= 1, "body content should survive");
    }

    /** A stray closing void tag (</br>) must not abort the parse. */
    @Test
    public void strayVoidCloseTagIsTolerated() throws Exception {
        String html = "<html><body><table><tr><td>A</td></tr></table></br>"
                + "<p>After</p></body></html>";
        Document dom = HtmlTagParser.parse(html);
        assertEquals(1, count(dom, "table"));
        assertTrue(count(dom, "p") >= 1);
    }

    /** An inline event handler carrying '<' in its JS must not break attribute parsing. */
    @Test
    public void onclickWithAngleBracketIsStripped() throws Exception {
        String html = "<html><body><div onclick=\"w.write('<b>x</b>')\">Hi</div></body></html>";
        Document dom = HtmlTagParser.parse(html);
        assertTrue(count(dom, "div") >= 1);
        Element div = (Element) dom.getDocumentElement().getElementsByTagName("div").item(0);
        assertEquals("", div.getAttribute("onclick"), "event handler should be gone");
    }

    /** Many unmatched </textarea> closes (a legacy data-binding artefact) must not
     *  collapse the document; the textarea's text is kept. */
    @Test
    public void strayTextareaClosesFallBackToStructuredParse() throws Exception {
        StringBuilder sb = new StringBuilder("<html><body><table>");
        for (int i = 0; i < 5; i++) {
            sb.append("<tr><td>value ").append(i).append("</textarea></td></tr>");
        }
        sb.append("</table></body></html>");
        Document dom = HtmlTagParser.parse(sb.toString());
        assertEquals(1, count(dom, "table"), "should parse as a real table, not plain text");
        assertEquals(5, count(dom, "td"));
    }

    /** A well-formed <textarea> is NOT unwrapped (stays a real element/form field). */
    @Test
    public void wellFormedTextareaSurvives() throws Exception {
        String html = "<html><body><textarea>hello</textarea></body></html>";
        Document dom = HtmlTagParser.parse(html);
        assertEquals(1, count(dom, "textarea"), "valid textarea must remain an element");
    }
}
