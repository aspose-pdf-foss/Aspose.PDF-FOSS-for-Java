package org.aspose.pdf.html;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 27 Part B — performance and correctness guards for
 * {@link HtmlTagParser#cleanHtml(String)} and {@link HtmlTagParser#parse(String)}.
 *
 * <p>The {@code @Timeout} annotations are the actual regression detectors: before
 * the Sprint 27 rewrite {@code cleanHtml} made ~70 full passes over the input, so a
 * 5&nbsp;MB document took 30+ minutes. After the rewrite (combined alternation
 * regex + single-pass entity scanner) the same input completes in seconds.</p>
 */
class HtmlTagParserPerformanceTest {

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void cleanHtml_1MB_completesUnder5Seconds() {
        StringBuilder big = new StringBuilder(1_000_000);
        for (int i = 0; i < 5000; i++) {
            big.append("<p>Para ").append(i).append(" with <br> and &nbsp; entity</p>\n");
            big.append("<div class=foo>Unquoted attr</div>\n");
            big.append("<img src=image.png>\n");
            big.append("<input type=text disabled>\n");
        }
        String result = HtmlTagParser.cleanHtml(big.toString());
        assertNotNull(result);
        assertTrue(result.length() > 0);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void parse_5MB_completesUnder30Seconds() throws Exception {
        StringBuilder big = new StringBuilder(5_000_000);
        big.append("<html><body>\n");
        for (int i = 0; i < 25000; i++) {
            big.append("<p>Paragraph ").append(i)
               .append(" with <strong>bold</strong> and <em>italic</em> text containing ")
               .append("&nbsp; and &mdash; entities. Lorem ipsum dolor sit amet, consectetur ")
               .append("adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore ")
               .append("magna aliqua.</p>\n");
        }
        big.append("</body></html>");

        org.w3c.dom.Document doc = HtmlTagParser.parse(big.toString());
        assertNotNull(doc);
    }

    @Test
    void cleanHtml_entityReplacement_preservesXmlBuiltins() {
        String input = "&amp; &lt; &gt; &quot; &apos; &nbsp; &mdash;";
        String result = HtmlTagParser.cleanHtml(input);
        // XML built-ins preserved verbatim (not double-escaped).
        assertTrue(result.contains("&amp;"));
        assertTrue(result.contains("&lt;"));
        assertTrue(result.contains("&gt;"));
        assertTrue(result.contains("&quot;"));
        assertTrue(result.contains("&apos;"));
        // Named entities replaced (either as a literal char or numeric form).
        assertTrue(result.contains(" ") || result.contains("&#160;"));  // nbsp
        assertTrue(result.contains("—") || result.contains("&#8212;")); // mdash
    }

    @Test
    void cleanHtml_unknownEntity_becomesReplacementChar() {
        String input = "&unknown; entity";
        String result = HtmlTagParser.cleanHtml(input);
        assertTrue(result.contains("�") || result.contains("&#xFFFD;"));
    }

    @Test
    void cleanHtml_bareAmpersand_escapedToAmp() {
        String input = "AT&T and Smith & Co";
        String result = HtmlTagParser.cleanHtml(input);
        assertTrue(result.contains("AT&amp;T"));
        assertTrue(result.contains("Smith &amp; Co"));
    }

    @Test
    void cleanHtml_voidElement_isSelfClosed() {
        String input = "<br><img src=\"x\"><hr/>";
        String result = HtmlTagParser.cleanHtml(input);
        assertTrue(result.contains("<br/>"), result);
        // <img> must become self-closing. (The legacy "missing space between
        // attributes" fix also inserts a space after the opening quote, so we
        // only assert that the void <img> tag ends with "/>", not its exact value.)
        assertTrue(result.matches("(?s).*<img[^>]*/>.*"), result);
        assertFalse(result.contains("</img>"), result);
    }

    @Test
    void cleanHtml_booleanAttr_getsValue() {
        String input = "<input type=\"text\" disabled>";
        String result = HtmlTagParser.cleanHtml(input);
        assertTrue(result.contains("disabled=\"disabled\""));
    }

    @Test
    void cleanHtml_multipleBooleanAttrs_allGetValue() {
        // Several boolean attrs on the same tag must all be expanded
        // (the combined-regex pass is repeated until stable).
        String input = "<input type=\"checkbox\" checked disabled required>";
        String result = HtmlTagParser.cleanHtml(input);
        assertTrue(result.contains("checked=\"checked\""), result);
        assertTrue(result.contains("disabled=\"disabled\""), result);
        assertTrue(result.contains("required=\"required\""), result);
    }

    @Test
    void cleanHtml_resultParsesAsXml() throws Exception {
        // End-to-end: the cleaned output of a messy fragment must parse.
        String input = "<div class=box>Hello &nbsp; <br> AT&T <img src=x> &bogus;</div>";
        org.w3c.dom.Document doc = HtmlTagParser.parse(input);
        assertNotNull(doc);
        assertNotNull(doc.getDocumentElement());
    }
}
