package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.HighlightAnnotation;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextFragmentAbsorber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR Stage 2 PART 3 gate: cross-page flow. Delete-middle pull-up with page
 * removal, merge-like continuation, annotations crossing pages, chrome dying
 * with its removed page while other pages' chrome stays.
 */
public class CrossPageFlowTest {

    private static final double EPS = 1.5;

    @TempDir
    Path tempDir;

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    private static String extractAll(Document doc) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            TextAbsorber ta = new TextAbsorber();
            ta.visit(doc.getPages().get(i));
            sb.append(ta.getText()).append('\n');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static CompactionResult flow(Document doc) throws IOException {
        CompactionResult result = new CompactionResult();
        result.setPagesBefore(doc.getPages().getCount());
        result.setPagesAfter(doc.getPages().getCount());
        CrossPageFlow.flowAcrossPages(doc, new CompactionOptions(), result, null);
        return result;
    }

    /** (a) Delete-middle: the second page's content pulls up, the page is
     *  removed, [TXT] preserved. */
    @Test
    public void pullUpAndRemoveEmptiedPage() throws IOException {
        Document doc = new Document();
        Page p1 = doc.getPages().add();
        line(p1, "Alpha paragraph on page one", 72, 700);
        Page p2 = doc.getPages().add();
        line(p2, "Beta paragraph from page two", 72, 700);
        Path src = tempDir.resolve("pull.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        String before = extractAll(work);
        CompactionResult result = flow(work);
        Path out = tempDir.resolve("pull-out.pdf");
        work.save(out.toString());

        Document reopened = new Document(out.toString());
        assertEquals(1, reopened.getPages().getCount(), "emptied page removed");
        assertEquals(1, result.getPagesAfter());
        assertTrue(result.getBlocksMoved() >= 1);
        assertEquals(before, extractAll(reopened), "[TXT] invariant");

        Map<String, Double> pos = baselines(reopened, 1);
        assertEquals(700, pos.get("Alpha paragraph on page one"), EPS);
        // Pulled block lands one natural spacing below the target content.
        double beta = pos.get("Beta paragraph from page two");
        assertTrue(beta < 700 && beta > 600,
                "pulled block right below the target content: " + beta);
    }

    /** (b) Merge-like: content continues right after the short page's content;
     *  a block that does not fit stays. */
    @Test
    public void continuationAfterShortPage() throws IOException {
        Document doc = new Document();
        Page p1 = doc.getPages().add();
        // Content ends LOW on page 1: only ~150pt of free space remain.
        line(p1, "Doc A last line", 72, 200);
        Page p2 = doc.getPages().add();
        // Two blocks: one small (fits), one tall paragraph block of many lines
        // that will NOT fit above the bottom limit after the first pull.
        line(p2, "Doc B first block", 72, 720);
        for (int i = 0; i < 30; i++) {
            line(p2, "Doc B big block line " + i, 72, 660 - i * 20);
        }
        Path src = tempDir.resolve("merge-like.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        String before = extractAll(work);
        flow(work);
        Path out = tempDir.resolve("merge-like-out.pdf");
        work.save(out.toString());
        Document reopened = new Document(out.toString());

        assertEquals(2, reopened.getPages().getCount(),
                "big block stays: no paragraph splitting in v1");
        assertEquals(before, extractAll(reopened), "[TXT] invariant");
        Map<String, Double> p1pos = baselines(reopened, 1);
        assertNotNull(p1pos.get("Doc B first block"), "small block pulled to page 1");
        assertTrue(p1pos.get("Doc B first block") < 200, "continues after A's content");
        Map<String, Double> p2pos = baselines(reopened, 2);
        assertNotNull(p2pos.get("Doc B big block line 0"), "big block still on page 2");
    }

    /** (c) An annotated paragraph crossing pages carries its annotation to the
     *  target page with translated coordinates. */
    @Test
    public void annotationTravelsWithItsBlock() throws IOException {
        Document doc = new Document();
        Page p1 = doc.getPages().add();
        line(p1, "Target page content", 72, 700);
        Page p2 = doc.getPages().add();
        line(p2, "Annotated paragraph", 72, 700);
        p2.getAnnotations().add(new HighlightAnnotation(p2,
                new Rectangle(70, 695, 200, 712)));
        Path src = tempDir.resolve("annot-cross.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        flow(work);
        Path out = tempDir.resolve("annot-cross-out.pdf");
        work.save(out.toString());
        Document reopened = new Document(out.toString());

        assertEquals(1, reopened.getPages().getCount());
        Map<String, Double> pos = baselines(reopened, 1);
        double delta = pos.get("Annotated paragraph") - 700;
        Annotation moved = reopened.getPages().get(1).getAnnotations().get(1);
        assertNotNull(moved, "annotation on the target page");
        assertEquals("Highlight", moved.getSubtype());
        assertEquals(695 + delta, moved.getRect().getLLY(), EPS,
                "rect translated with the block");
        assertEquals(70, moved.getRect().getLLX(), EPS, "x untouched");
    }

    /** (d) The removed page's chrome disappears with it; kept pages keep
     *  their own chrome. */
    @Test
    public void chromeDiesWithRemovedPage() throws IOException {
        Document doc = new Document();
        for (int p = 1; p <= 3; p++) {
            Page page = doc.getPages().add();
            line(page, "RUNNING HEADER", 72, 770);
            line(page, String.valueOf(p), 300, 30);
            if (p < 3) {
                for (int i = 0; i < 20; i++) {
                    line(page, "Page " + p + " body line " + i, 72, 700 - i * 24);
                }
            } else {
                line(page, "Last page tiny body", 72, 700);
            }
        }
        Path src = tempDir.resolve("chrome-cross.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        flow(work);
        Path out = tempDir.resolve("chrome-cross-out.pdf");
        work.save(out.toString());
        Document reopened = new Document(out.toString());

        assertEquals(2, reopened.getPages().getCount(), "page 3 drained and removed");
        String all = extractAll(reopened);
        assertTrue(all.contains("Last page tiny body"), "page-3 body pulled forward");
        assertTrue(!all.contains("RUNNING HEADER 3".replace(" 3", " 3")),
                "removed page's chrome not duplicated");
        int headers = all.split("RUNNING HEADER", -1).length - 1;
        assertEquals(2, headers, "exactly the two kept pages' headers remain");
        Map<String, Double> p1pos = baselines(reopened, 1);
        assertEquals(770, p1pos.get("RUNNING HEADER"), EPS, "kept chrome fixed");
        assertEquals(30, p1pos.get("1"), EPS);
    }

    private static Map<String, Double> baselines(Document doc, int pageNumber)
            throws IOException {
        TextFragmentAbsorber tfa = new TextFragmentAbsorber();
        tfa.visit(doc.getPages().get(pageNumber));
        Map<String, Double> out = new LinkedHashMap<>();
        for (TextFragment f : tfa.getTextFragments()) {
            if (f.getPosition() != null) {
                out.put(f.getText(), f.getPosition().getYIndent());
            }
        }
        return out;
    }
}
