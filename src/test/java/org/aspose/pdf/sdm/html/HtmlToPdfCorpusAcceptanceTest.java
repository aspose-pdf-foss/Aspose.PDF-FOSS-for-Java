package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.layout.PageSetup;
import org.aspose.pdf.sdm.layout.SdmPdfLayout;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;

/**
 * IR Stage 4 PART 3 — HTML&rarr;PDF corpus acceptance. For each of 30+ diverse
 * semantic HTML documents, reads it to SDM ({@link HtmlSdmReader}), lays it out
 * to PDF ({@link SdmPdfLayout}), and measures the <b>[TXT] invariant</b>: the
 * fraction of authored (SDM) word occurrences that survive into the rendered PDF
 * text. Markup decoration added by layout (list markers, etc.) does not penalise
 * this recall metric. GO gate: aggregate recall &ge; 98%.
 */
public class HtmlToPdfCorpusAcceptanceTest {

    private static final PageSetup LETTER = PageSetup.letter();

    // ---- corpus ------------------------------------------------------------

    /** 34 varied semantic HTML documents (headings, inline styling, lists,
     * nested lists, tables incl. thead/colspan, blockquote, code, containers,
     * links, mixed long docs). */
    private static List<String> corpus() {
        List<String> c = new ArrayList<>();
        c.add("<h1>Annual Report</h1><p>Revenue rose sharply this year.</p>");
        c.add("<p>Plain paragraph without any markup at all.</p>");
        c.add("<h2>Section</h2><p>Body with <b>bold</b> and <i>italic</i> words.</p>");
        c.add("<p>A sentence with an <a href='http://example.com'>anchor link</a> inside.</p>");
        c.add("<ul><li>First item</li><li>Second item</li><li>Third item</li></ul>");
        c.add("<ol start='3'><li>Charlie</li><li>Delta</li></ol>");
        c.add("<ul><li>Top<ul><li>Nested one</li><li>Nested two</li></ul></li><li>Sibling</li></ul>");
        c.add("<table><tr><th>Name</th><th>Value</th></tr>"
                + "<tr><td>Alpha</td><td>100</td></tr><tr><td>Beta</td><td>200</td></tr></table>");
        c.add("<table><thead><tr><th colspan='2'>Summary</th></tr></thead>"
                + "<tbody><tr><td>Left</td><td>Right</td></tr></tbody></table>");
        c.add("<blockquote>To be or not to be, that is the question.</blockquote>");
        c.add("<pre><code>int main() { return zero; }</code></pre>");
        c.add("<h1>Title</h1><h2>Subtitle</h2><p>Introduction paragraph follows here.</p>");
        c.add("<div><section><p>Wrapped inside containers and sections.</p></section></div>");
        c.add("<p>Text with <span style='color:red'>colored span</span> content.</p>");
        c.add("<p>Underlined <u>important</u> and struck <s>obsolete</s> words.</p>");
        c.add("<h3>List of things</h3><ul><li>apples</li><li>oranges</li><li>bananas</li></ul>");
        c.add("<p>" + repeatWords("lorem", 120) + "</p>"); // long, forces wrap
        c.add("<p>" + repeatWords("paragraph", 400) + "</p>"); // very long, forces pagination
        c.add("<h1>Chapter One</h1>" + "<p>" + repeatWords("chapter", 60) + "</p>"
                + "<h2>Part A</h2><p>" + repeatWords("section", 60) + "</p>");
        c.add("<p>Mixed <b>bold <i>and italic</i></b> nesting works fine.</p>");
        c.add("<table><tr><td>R1C1</td><td>R1C2</td><td>R1C3</td></tr>"
                + "<tr><td>R2C1</td><td>R2C2</td><td>R2C3</td></tr></table>");
        c.add("<ol><li>Step one instructions</li><li>Step two instructions</li>"
                + "<li>Step three instructions</li></ol>");
        c.add("<blockquote><p>Quoted paragraph one.</p><p>Quoted paragraph two.</p></blockquote>");
        c.add("<p>Symbols and numbers: 42 items at 3.14 each equals plenty.</p>");
        c.add("<h2>FAQ</h2><p>Question about pricing.</p><p>Answer about pricing details.</p>");
        c.add("<div><h1>Dashboard</h1><ul><li>Metric one</li><li>Metric two</li></ul>"
                + "<p>Footer note text.</p></div>");
        c.add("<p>Contact us at the office during business hours every weekday.</p>");
        c.add("<article><h1>News</h1><p>Something newsworthy happened downtown today.</p></article>");
        c.add("<table><thead><tr><th>Q</th><th>A</th></tr></thead>"
                + "<tbody><tr><td>Why</td><td>Because reasons</td></tr>"
                + "<tr><td>When</td><td>Tomorrow morning</td></tr></tbody></table>");
        c.add("<h1>Recipe</h1><ol><li>Preheat the oven</li><li>Mix the ingredients</li>"
                + "<li>Bake until golden</li></ol><p>Serve warm and enjoy.</p>");
        c.add("<p>The quick brown fox jumps over the lazy dog repeatedly.</p>");
        c.add("<section><h2>Overview</h2><p>" + repeatWords("overview", 40) + "</p>"
                + "<h2>Details</h2><p>" + repeatWords("detail", 40) + "</p></section>");
        c.add("<p>Line one.<br>Line two after a break.<br>Line three at the end.</p>");
        c.add("<h1>Glossary</h1><ul><li>Term: definition text</li>"
                + "<li>Another: more definition text</li></ul>");
        return c;
    }

    private static String repeatWords(String w, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(w).append(i).append(' ');
        }
        return sb.toString().trim();
    }

    // ---- test --------------------------------------------------------------

    @Test
    public void corpus_txtRecallAtLeast98Percent() throws Exception {
        List<String> docs = corpus();
        assertTrue(docs.size() >= 30, "corpus has 30+ docs, got " + docs.size());

        long totalAuthored = 0;
        long totalPreserved = 0;
        int perfect = 0;
        List<String> worst = new ArrayList<>();

        for (int i = 0; i < docs.size(); i++) {
            String html = "<html><body>" + docs.get(i) + "</body></html>";
            SdmDocument sdm = new HtmlSdmReader().read(html, null, new HtmlReadOptions());
            Map<String, Integer> authored = new HashMap<>();
            for (SdmBlock b : sdm.getChildren()) {
                collectBlock(b, authored);
            }

            SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, LETTER);
            Map<String, Integer> rendered = tokenize(pdfText(r.getDocument()));
            r.getDocument().close();

            long authoredCount = sum(authored);
            long preserved = intersectionSize(authored, rendered);
            totalAuthored += authoredCount;
            totalPreserved += preserved;

            double recall = authoredCount == 0 ? 1.0 : (double) preserved / authoredCount;
            if (recall >= 0.999) perfect++;
            if (recall < 0.98) {
                worst.add(String.format(Locale.ROOT, "doc[%d] recall=%.3f (%d/%d): %s",
                        i, recall, preserved, authoredCount, snippet(docs.get(i))));
            }
        }

        double aggregate = totalAuthored == 0 ? 1.0 : (double) totalPreserved / totalAuthored;
        System.out.println("[TXT] corpus: docs=" + docs.size() + " perfect=" + perfect
                + " aggregateRecall=" + String.format(Locale.ROOT, "%.4f", aggregate)
                + " (" + totalPreserved + "/" + totalAuthored + ")");
        if (!worst.isEmpty()) {
            System.out.println("[TXT] docs below 0.98:");
            worst.forEach(w -> System.out.println("  " + w));
        }
        assertTrue(aggregate >= 0.98,
                "aggregate [TXT] recall must be >= 0.98, was " + aggregate + "; worst=" + worst);
    }

    // ---- SDM text collection ----------------------------------------------

    private static void collectBlock(SdmBlock b, Map<String, Integer> acc) {
        switch (b.getType()) {
            case HEADING:
                collectInline(((Heading) b).getInline(), acc);
                break;
            case PARAGRAPH:
                collectInline(((Paragraph) b).getInline(), acc);
                break;
            case LIST_BLOCK:
                for (ListItem it : ((ListBlock) b).getItems()) {
                    for (SdmBlock cb : it.getChildren()) collectBlock(cb, acc);
                }
                break;
            case TABLE:
                for (TableRow row : ((Table) b).getRows()) {
                    for (TableCell cell : row.getCells()) {
                        for (SdmBlock cb : cell.getChildren()) collectBlock(cb, acc);
                    }
                }
                break;
            case QUOTE:
                for (SdmBlock cb : ((Quote) b).getChildren()) collectBlock(cb, acc);
                break;
            case CONTAINER:
                for (SdmBlock cb : ((Container) b).getChildren()) collectBlock(cb, acc);
                break;
            case CODE_BLOCK:
                addTokens(((CodeBlock) b).getText(), acc);
                break;
            case FIGURE:
                for (SdmBlock cb : ((Figure) b).getCaption()) collectBlock(cb, acc);
                break;
            default:
                // THEMATIC_BREAK, OPAQUE, etc. carry no authored flowing text.
                break;
        }
    }

    private static void collectInline(List<SdmInline> inlines, Map<String, Integer> acc) {
        if (inlines == null) return;
        for (SdmInline in : inlines) {
            if (in instanceof Run) {
                addTokens(((Run) in).getText(), acc);
            } else if (in instanceof LinkInline) {
                collectInline(((LinkInline) in).getChildren(), acc);
            }
            // LineBreak / InlineImage carry no flowing text (alt is optional).
        }
    }

    // ---- tokenization / metrics -------------------------------------------

    private static void addTokens(String text, Map<String, Integer> acc) {
        if (text == null) return;
        for (String tok : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!tok.isEmpty()) acc.merge(tok, 1, Integer::sum);
        }
    }

    private static Map<String, Integer> tokenize(String text) {
        Map<String, Integer> m = new HashMap<>();
        addTokens(text, m);
        return m;
    }

    private static long sum(Map<String, Integer> m) {
        long s = 0;
        for (int v : m.values()) s += v;
        return s;
    }

    /** Sum over authored tokens of min(authoredCount, renderedCount) — preserved occurrences. */
    private static long intersectionSize(Map<String, Integer> authored, Map<String, Integer> rendered) {
        long s = 0;
        for (Map.Entry<String, Integer> e : authored.entrySet()) {
            int have = rendered.getOrDefault(e.getKey(), 0);
            s += Math.min(e.getValue(), have);
        }
        return s;
    }

    private static String pdfText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            Page p = doc.getPages().get(i);
            TextAbsorber ta = new TextAbsorber();
            ta.visit(p);
            if (ta.getText() != null) sb.append(ta.getText()).append(' ');
        }
        return sb.toString();
    }

    private static String snippet(String html) {
        String s = html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) + "..." : s;
    }
}
