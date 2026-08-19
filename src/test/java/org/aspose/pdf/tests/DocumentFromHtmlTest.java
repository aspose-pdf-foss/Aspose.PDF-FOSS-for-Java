package org.aspose.pdf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;
import org.aspose.pdf.PageInfo;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;

/**
 * IR Stage 4 PART 3 — HTML markup loading through the uniform
 * {@code new Document(InputStream, HtmlLoadOptions)} constructor (the source
 * format is selected by the concrete LoadOptions type — no per-format factory)
 * and the {@code useSdmPipeline} routing on the HTML constructors. Verifies the
 * SDM path produces a real, text-bearing PDF and honours page geometry, while
 * the legacy path stays reachable.
 */
public class DocumentFromHtmlTest {

    /** Markup-string shorthand: the constructor form of the removed fromHtml factory. */
    private static Document fromHtml(String html) throws Exception {
        return fromHtml(html, null);
    }

    private static Document fromHtml(String html, HtmlLoadOptions opt) throws Exception {
        return new Document(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), opt);
    }

    private static String allText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            TextAbsorber ta = new TextAbsorber();
            ta.visit(doc.getPages().get(i));
            if (ta.getText() != null) sb.append(ta.getText()).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    @Test
    public void fromHtml_basicTextPreserved() throws Exception {
        String html = "<html><body><h1>Report Title</h1>"
                + "<p>First paragraph body.</p><p>Second paragraph here.</p></body></html>";
        Document doc = fromHtml(html);
        assertNotNull(doc);
        assertTrue(doc.getPages().getCount() >= 1, "at least one page");
        String text = allText(doc);
        assertTrue(text.contains("Report Title"), "heading text present: " + text);
        assertTrue(text.contains("First paragraph body."), "para 1 present: " + text);
        assertTrue(text.contains("Second paragraph here."), "para 2 present: " + text);
        doc.close();
    }

    @Test
    public void fromHtml_listAndTableText() throws Exception {
        String html = "<html><body>"
                + "<ul><li>Alpha</li><li>Beta</li></ul>"
                + "<table><tr><td>Cell11</td><td>Cell12</td></tr>"
                + "<tr><td>Cell21</td><td>Cell22</td></tr></table>"
                + "</body></html>";
        Document doc = fromHtml(html);
        String text = allText(doc);
        for (String w : new String[]{"Alpha", "Beta", "Cell11", "Cell12", "Cell21", "Cell22"}) {
            assertTrue(text.contains(w), "missing '" + w + "' in: " + text);
        }
        doc.close();
    }

    @Test
    public void fromHtml_honoursPageInfoGeometry() throws Exception {
        HtmlLoadOptions opt = new HtmlLoadOptions();
        PageInfo pi = new PageInfo(612, 792); // Letter
        opt.setPageInfo(pi);
        Document doc = fromHtml("<p>geometry check</p>", opt);
        Page p1 = doc.getPages().get(1);
        assertEquals(612.0, p1.getRect().getWidth(), 1.0, "page width honoured");
        assertEquals(792.0, p1.getRect().getHeight(), 1.0, "page height honoured");
        doc.close();
    }

    /** Saves to bytes and reopens — the legacy converter only emits content at save(). */
    private static Document saveAndReopen(Document doc) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos);
        doc.close();
        return new Document(new ByteArrayInputStream(bos.toByteArray()));
    }

    @Test
    public void constructor_useSdmPipelineRoutesThroughSdm() throws Exception {
        HtmlLoadOptions opt = new HtmlLoadOptions();
        opt.setUseSdmPipeline(true);
        String html = "<html><body><h2>Heading Two</h2><p>Body via SDM path.</p></body></html>";
        Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), opt);
        Document reopened = saveAndReopen(doc);
        String text = allText(reopened);
        assertTrue(text.contains("Heading Two"), "heading via SDM: " + text);
        assertTrue(text.contains("Body via SDM path."), "body via SDM: " + text);
        reopened.close();
    }

    @Test
    public void constructor_legacyPathIsReachable() throws Exception {
        // The legacy DOM converter is still reachable via an explicit
        // setUseSdmPipeline(false) and must produce text.
        HtmlLoadOptions opt = new HtmlLoadOptions();
        opt.setUseSdmPipeline(false);
        String html = "<html><body><p>Legacy default path.</p></body></html>";
        Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), opt);
        Document reopened = saveAndReopen(doc);
        String text = allText(reopened);
        assertTrue(text.contains("Legacy default path."), "legacy text: " + text);
        reopened.close();
    }
}
