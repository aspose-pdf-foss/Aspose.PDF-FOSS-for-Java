package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.MergeOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR Stage 2 PART 4 gate: the public API surface. Options are respected —
 * scope limits touched pages (outside byte-identical), the threshold is
 * honored, keepPageBreaks stops cross-page flow; the merge wrapper equals
 * manual concat + compactFlow.
 */
public class FlowApiTest {

    @TempDir
    Path tempDir;

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    private static Document twoHolePages() throws IOException {
        Document doc = new Document();
        for (int p = 1; p <= 2; p++) {
            Page page = doc.getPages().add();
            line(page, "Page " + p + " top", 72, 700);
            line(page, "Page " + p + " bottom", 72, 540);
        }
        return doc;
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

    /** Scope limits compaction; out-of-scope pages stay byte-identical. */
    @Test
    public void scopeRespected() throws IOException {
        Document doc = twoHolePages();
        byte[] p2Before = decodedContent(doc, 2);
        CompactionResult result = doc.compactFlow(
                new CompactionOptions().setPages(1).setKeepPageBreaks(true));
        assertEquals(1, result.getGapsClosed(), "only page 1 compacted");
        assertArrayEquals(p2Before, decodedContent(doc, 2),
                "out-of-scope page byte-identical");
        assertTrue(result.getWarnings().isEmpty()
                        || result.getWarnings().get(0).contains("scope"),
                "scope warning only");
    }

    /** The gap threshold is honored. */
    @Test
    public void thresholdHonored() throws IOException {
        Document doc = twoHolePages();
        CompactionResult result = doc.compactFlow(new CompactionOptions()
                .setVerticalGapThreshold(500).setKeepPageBreaks(true));
        assertEquals(0, result.getGapsClosed(), "160pt holes below a 500pt threshold");
    }

    /** keepPageBreaks=true keeps the page count. */
    @Test
    public void keepPageBreaksStopsCrossPage() throws IOException {
        Document doc = twoHolePages();
        CompactionResult result = doc.compactFlow(
                new CompactionOptions().setKeepPageBreaks(true));
        assertEquals(2, result.getPagesBefore());
        assertEquals(2, result.getPagesAfter(), "no page removal with page breaks kept");
        assertEquals(2, doc.getPages().getCount());
    }

    /** Defaults flow across pages and remove the emptied page. */
    @Test
    public void defaultsFlowAcrossPages() throws IOException {
        Document doc = twoHolePages();
        String before = extractAll(doc);
        CompactionResult result = doc.compactFlow(null);
        assertEquals(2, result.getPagesBefore());
        assertEquals(1, result.getPagesAfter(), "content flowed, empty page removed");
        assertEquals(before, extractAll(doc), "[TXT] invariant");
    }

    /** merge(withFlowCompaction) == manual concat then compactFlow. */
    @Test
    public void mergeWrapperEqualsManual() throws IOException {
        Document a1 = new Document();
        line(a1.getPages().add(), "Document A content", 72, 700);
        Document b1 = new Document();
        line(b1.getPages().add(), "Document B content", 72, 700);

        Document merged = Document.merge(List.of(a1, b1),
                MergeOptions.withFlowCompaction());

        Document a2 = new Document();
        line(a2.getPages().add(), "Document A content", 72, 700);
        Document b2 = new Document();
        line(b2.getPages().add(), "Document B content", 72, 700);
        Document manual = Document.merge(List.of(a2, b2), null);
        assertEquals(2, manual.getPages().getCount(), "plain concat keeps pages");
        CompactionResult manualResult = manual.compactFlow(null);

        assertEquals(manual.getPages().getCount(), merged.getPages().getCount());
        assertEquals(extractAll(manual), extractAll(merged));
        assertEquals(1, merged.getPages().getCount(),
                "B's content continues on A's page; result=" + manualResult
                        + " warnings=" + manualResult.getWarnings());
    }

    private static byte[] decodedContent(Document doc, int pageNumber) throws IOException {
        Page page = doc.getPages().get(pageNumber);
        PdfBase contents = page.getPdfDictionary().get(PdfName.of("Contents"));
        if (contents instanceof PdfObjectReference) {
            contents = ((PdfObjectReference) contents).dereference();
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        if (contents instanceof PdfStream) {
            all.write(((PdfStream) contents).getDecodedData());
        } else if (contents instanceof PdfArray) {
            PdfArray arr = (PdfArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase e = arr.get(i);
                if (e instanceof PdfObjectReference) {
                    e = ((PdfObjectReference) e).dereference();
                }
                if (e instanceof PdfStream) {
                    all.write(((PdfStream) e).getDecodedData());
                }
            }
        }
        return all.toByteArray();
    }
}
