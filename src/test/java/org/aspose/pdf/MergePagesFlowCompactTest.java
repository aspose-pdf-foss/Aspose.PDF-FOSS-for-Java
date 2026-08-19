package org.aspose.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * Merge two documents by appending their page collections, then flow-compact
 * the result — the Aspose-style API the user asked about:
 *
 * <pre>
 *   Document doc1 = new Document("src1.pdf");
 *   Document doc2 = new Document("src2.pdf");
 *   Document doc  = new Document();
 *   doc.getPages().add(doc1.getPages());   // append all of doc1's pages
 *   doc.getPages().add(doc2.getPages());   // append all of doc2's pages
 *   doc.compactFlow(new CompactionOptions());
 * </pre>
 *
 * <p>Note: the library exposes {@code compactFlow(CompactionOptions)} — there is
 * no {@code flowCompress()}. And {@code getPages().add(PageCollection)} imports
 * pages across documents, but it needs the SOURCE to be a real parsed PDF
 * (indirect content streams); a freshly built {@code new Document()} holds
 * direct content streams the page importer cannot clone. So each source is
 * round-tripped through bytes (exactly what {@code new Document("src.pdf")}
 * would give you) before appending.</p>
 */
public class MergePagesFlowCompactTest {

    // NOTE: the interactive local-path probes (merge/columns/HTML/DOCX against
    // files under D:\Aspose.PDF, writing into out\) were moved to the gitignored
    // ManualProbes class. This file keeps only the self-contained, CI-safe test.

    @Test
    public void mergePageCollectionsThenCompactFlow() throws IOException {
        // Stand in for new Document("src1.pdf") / new Document("src2.pdf"):
        // two one-page documents whose text sits at the top with a big empty
        // gap below (the vertical whitespace flow compaction collapses).
        Document doc1 = load(buildTopHeavyDoc("Document ONE",
                "First document, first line.",
                "Second line of the first doc.",
                "Third line — then a big gap below."));
        Document doc2 = load(buildTopHeavyDoc("Document TWO",
                "Second document starts here.",
                "It should flow up right after doc one",
                "instead of on a fresh, mostly empty page."));

        // Merge exactly the way the user wrote it.
        Document doc = new Document();
        doc.getPages().add(doc1.getPages());
        doc.getPages().add(doc2.getPages());
        assertEquals(2, doc.getPages().getCount(), "plain merge keeps one page per source");

        // Flow-compact: pulls doc2's content up and drops the now-empty page(s).
        CompactionResult result = doc.compactFlow(new CompactionOptions());

        System.out.println("pagesBefore=" + result.getPagesBefore()
                + " pagesAfter=" + result.getPagesAfter()
                + " gapsClosed=" + result.getGapsClosed()
                + " blocksMoved=" + result.getBlocksMoved()
                + " warnings=" + result.getWarnings());

        assertEquals(2, result.getPagesBefore(), "started from the 2 merged pages");
        assertTrue(result.getPagesAfter() <= result.getPagesBefore(),
                "flow compaction never increases the page count");
        assertTrue(doc.getPages().getCount() == result.getPagesAfter(),
                "document page count matches the reported result");

        // The compacted document still saves to a valid, non-empty PDF, and all
        // the original text survives the compaction.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        doc.save(out);
        assertTrue(out.size() > 400, "compacted PDF written");

        Document reopened = new Document(new ByteArrayInputStream(out.toByteArray()));
        org.aspose.pdf.text.TextAbsorber absorber = new org.aspose.pdf.text.TextAbsorber();
        reopened.getPages().accept(absorber);
        String text = absorber.getText();
        assertTrue(text.contains("Document ONE"), "doc one heading survives compaction");
        assertTrue(text.contains("Document TWO"), "doc two heading survives compaction");
    }

    /** One page with a heading + body lines packed into the top ~150pt of A4. */
    private static Document buildTopHeavyDoc(String heading, String... lines) throws IOException {
        Document d = new Document();
        Page page = d.getPages().add(); // default A4 (595 x 842), origin bottom-left
        TextBuilder tb = new TextBuilder(page);

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

    /** Round-trip through bytes so the result is a real parsed PDF (indirect
     *  content streams) — what {@code new Document("src.pdf")} gives you, and
     *  what {@code getPages().add(PageCollection)} needs to clone pages. */
    private static Document load(Document built) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        built.save(baos);
        return new Document(new ByteArrayInputStream(baos.toByteArray()));
    }
}
