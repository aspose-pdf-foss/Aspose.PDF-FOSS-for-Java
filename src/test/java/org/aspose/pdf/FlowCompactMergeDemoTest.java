package org.aspose.pdf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * MANUAL DEMO — merging documents with flow compaction, side by side.
 *
 * <p>Run it and open the two produced PDFs to see the difference:</p>
 * <pre>
 *   cd pdf
 *   mvn -o test -Dtest=FlowCompactMergeDemoTest -DtrimStackTrace=false
 * </pre>
 *
 * <p>It builds three one-page documents, each with a short block of text at the
 * TOP of the page and a large empty gap below. Then it merges them two ways:</p>
 * <ul>
 *   <li><b>merge-plain.pdf</b> — plain concatenation: each document keeps its own
 *       page, so the merged file has one page per source (big gaps preserved);</li>
 *   <li><b>merge-flowcompact.pdf</b> — {@code MergeOptions.withFlowCompaction()}:
 *       the flow compactor closes the vertical gaps and pulls the following
 *       documents' text up so it flows continuously, removing now-empty pages.</li>
 * </ul>
 *
 * <p>The console prints the output paths and the {@link CompactionResult}
 * (pages before/after, gaps closed, blocks moved) so you can see what happened.
 * Output goes to <code>&lt;repo-root&gt;/flow-compact-demo/</code>.</p>
 */
public class FlowCompactMergeDemoTest {

    /** One source document: a page with {@code lines} of text near the top. */
    private static Document textDoc(String heading, String... lines) throws IOException {
        Document d = new Document();
        Page page = d.getPages().add(); // default A4 (595 x 842), origin bottom-left
        TextBuilder tb = new TextBuilder(page);

        // Heading near the very top, then body lines under it — all in the top
        // ~150 pt, leaving ~650 pt of empty space below (the gap to be compacted).
        double y = 800;
        TextFragment head = new TextFragment(heading);
        head.setPosition(new Position(72, y));
        tb.appendText(head);
        y -= 24;
        for (String line : lines) {
            TextFragment tf = new TextFragment(line);
            tf.setPosition(new Position(72, y));
            tb.appendText(tf);
            y -= 16;
        }
        return d;
    }

    @Test
    public void mergeWithAndWithoutFlowCompaction() throws IOException {
        // 1. Build three small documents, each one page with text only at the top.
        List<Document> sources = new ArrayList<>();
        sources.add(textDoc("Document ONE",
                "This is the first document.",
                "It has a few lines of text",
                "near the top of the page."));
        sources.add(textDoc("Document TWO",
                "The second document follows.",
                "Its content should flow up",
                "right after document one."));
        sources.add(textDoc("Document THREE",
                "Third and final document.",
                "After flow compaction this",
                "sits below the previous text."));

        Path outDir = Paths.get("..", "flow-compact-demo");
        Files.createDirectories(outDir);

        // 2. PLAIN merge (no flow compaction): one page per source document.
        Document plain = Document.merge(copies(sources), null);
        Path plainPath = outDir.resolve("merge-plain.pdf");
        plain.save(plainPath.toString());
        int plainPages = plain.getPages().getCount();

        // 3. FLOW-COMPACT merge: content flows continuously, empty pages removed.
        MergeOptions options = MergeOptions.withFlowCompaction();
        Document compact = Document.merge(copies(sources), options);
        Path compactPath = outDir.resolve("merge-flowcompact.pdf");
        compact.save(compactPath.toString());
        int compactPages = compact.getPages().getCount();

        // 4. Also show the direct compactFlow result object on the plain merge, so
        //    you can read the pages-before/after and gaps-closed numbers.
        Document toReport = Document.merge(copies(sources), null);
        CompactionResult result = toReport.compactFlow(new CompactionOptions());

        System.out.println("========================================================");
        System.out.println(" FLOW-COMPACT MERGE DEMO");
        System.out.println("========================================================");
        System.out.println(" plain merge         : " + plainPages + " pages -> "
                + plainPath.toAbsolutePath().normalize());
        System.out.println(" flow-compact merge  : " + compactPages + " pages -> "
                + compactPath.toAbsolutePath().normalize());
        System.out.println(" CompactionResult    : " + result);
        System.out.println("   pagesBefore=" + result.getPagesBefore()
                + " pagesAfter=" + result.getPagesAfter()
                + " gapsClosed=" + result.getGapsClosed()
                + " blocksMoved=" + result.getBlocksMoved());
        if (!result.getWarnings().isEmpty()) {
            System.out.println("   warnings=" + result.getWarnings());
        }
        System.out.println(" Open both PDFs to compare: plain keeps one page per");
        System.out.println(" document; flow-compact pulls the text together.");
        System.out.println("========================================================");

        // Sanity assertions (the demo still self-checks):
        assertTrue(Files.exists(plainPath) && Files.size(plainPath) > 400, "plain PDF written");
        assertTrue(Files.exists(compactPath) && Files.size(compactPath) > 400, "flow-compact PDF written");
        assertTrue(plainPages == 3, "plain merge keeps one page per source, got " + plainPages);
        assertTrue(compactPages <= plainPages,
                "flow compaction does not increase page count (" + compactPages + " <= " + plainPages + ")");
        assertTrue(result.getPagesAfter() <= result.getPagesBefore(),
                "compactFlow pagesAfter <= pagesBefore");
    }

    /** Fresh reloads of the sources — merge() consumes/normalizes them, so each
     *  merge run gets its own copies to stay independent. */
    private static List<Document> copies(List<Document> sources) throws IOException {
        List<Document> out = new ArrayList<>();
        for (Document d : sources) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            d.save(baos);
            out.add(new Document(new java.io.ByteArrayInputStream(baos.toByteArray())));
        }
        return out;
    }
}
