package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR Stage 2 PART 2 gate: N→1 merge on synthetic MULTI_CLEAN pages. Merged
 * reading order is EXACT (string-equal: left column then right), stack
 * geometry asserted, rewrap packs lines to full width without exceeding it,
 * MULTI_MIXED is skipped.
 */
public class ColumnMergerTest {

    private static final double EPS = 1.5;
    private static final int LINES = 12;

    @TempDir
    Path tempDir;

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    private Path buildTwoColumn() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        for (int i = 0; i < LINES; i++) {
            line(p, "Left line " + i + " text", 72, 720 - i * 18);
            line(p, "Right line " + i + " text", 330, 720 - i * 18);
        }
        Path path = tempDir.resolve("twocol.pdf");
        doc.save(path.toString());
        return path;
    }

    private static String extract(Document doc) throws IOException {
        TextAbsorber ta = new TextAbsorber();
        ta.visit(doc.getPages().get(1));
        return ta.getText().replaceAll("\\s+", " ").trim();
    }

    private static PdfSdmReader.Result readModel(Document doc, Path src) throws IOException {
        PdfSdmReader.Result model = new PdfSdmReader().read(doc, Files.readAllBytes(src));
        FlowClassifier.classify(model.getPgm());
        return model;
    }

    /** STACK: merged order exact — the whole left column, then the right. */
    @Test
    public void stackTwoColumnsOrderExact() throws IOException {
        Path src = buildTwoColumn();
        Document doc = new Document(src.toString());
        ColumnMerger.MergeResult r = ColumnMerger.mergePage(doc, readModel(doc, src), 0, false);
        assertNull(r.getSkipReason());
        assertTrue(r.isMerged());
        Path out = tempDir.resolve("twocol-stacked.pdf");
        doc.save(out.toString());

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            expected.append("Left line ").append(i).append(" text ");
        }
        for (int i = 0; i < LINES; i++) {
            expected.append("Right line ").append(i).append(" text ");
        }
        assertEquals(expected.toString().trim(), extract(new Document(out.toString())),
                "merged reading order must be column-sequential");
    }

    /** STACK geometry: right column starts one leading below the left column,
     *  left edges aligned. */
    @Test
    public void stackGeometry() throws IOException {
        Path src = buildTwoColumn();
        Document doc = new Document(src.toString());
        ColumnMerger.MergeResult r = ColumnMerger.mergePage(doc, readModel(doc, src), 0, false);
        assertTrue(r.isMerged());
        Path out = tempDir.resolve("twocol-geom.pdf");
        doc.save(out.toString());

        Map<String, double[]> pos = positions(new Document(out.toString()));
        // Left column untouched.
        assertEquals(720, pos.get("Left line 0 text")[1], EPS);
        assertEquals(72, pos.get("Left line 0 text")[0], EPS);
        // Right column: x aligned to left band, first line one leading (18pt)
        // below the last left line.
        double lastLeftY = pos.get("Left line " + (LINES - 1) + " text")[1];
        double[] r0 = pos.get("Right line 0 text");
        assertEquals(72, r0[0], 3.0, "right column aligned to the left band");
        assertEquals(lastLeftY - 18, r0[1], 2.0, "stacked one leading below");
        // Right column keeps its internal pitch.
        assertEquals(r0[1] - 18, pos.get("Right line 1 text")[1], 2.0);
    }

    /** Three columns merge in band order. */
    @Test
    public void stackThreeColumns() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        for (int i = 0; i < 10; i++) {
            line(p, "Aa " + i, 50, 720 - i * 18);
            line(p, "Bb " + i, 240, 720 - i * 18);
            line(p, "Cc " + i, 430, 720 - i * 18);
        }
        Path src = tempDir.resolve("threecol.pdf");
        doc.save(src.toString());
        Document work = new Document(src.toString());
        ColumnMerger.MergeResult r = ColumnMerger.mergePage(work, readModel(work, src), 0, false);
        assertTrue(r.isMerged(), "skip=" + r.getSkipReason());
        Path out = tempDir.resolve("threecol-stacked.pdf");
        work.save(out.toString());
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            expected.append("Aa ").append(i).append(' ');
        }
        for (int i = 0; i < 10; i++) {
            expected.append("Bb ").append(i).append(' ');
        }
        for (int i = 0; i < 10; i++) {
            expected.append("Cc ").append(i).append(' ');
        }
        assertEquals(expected.toString().trim(), extract(new Document(out.toString())));
    }

    /** REWRAP: [TXT] order invariant, no line exceeds the content width, and
     *  lines actually merge (fewer lines than stacked). */
    @Test
    public void rewrapPacksFullWidth() throws IOException {
        Path src = buildTwoColumn();
        Document doc = new Document(src.toString());
        ColumnMerger.MergeResult r = ColumnMerger.mergePage(doc, readModel(doc, src), 0, true);
        assertNull(r.getSkipReason());
        Path out = tempDir.resolve("twocol-rewrap.pdf");
        doc.save(out.toString());
        Document reopened = new Document(out.toString());

        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < LINES; i++) {
            expected.append("Left line ").append(i).append(" text ");
        }
        for (int i = 0; i < LINES; i++) {
            expected.append("Right line ").append(i).append(" text ");
        }
        assertEquals(expected.toString().trim(), extract(reopened), "[TXT] order invariant");

        // Group fragments into visual lines; verify width bound and merging.
        Map<Long, List<double[]>> byLine = new LinkedHashMap<>();
        TextFragmentAbsorber tfa = new TextFragmentAbsorber();
        tfa.visit(reopened.getPages().get(1));
        double contentLeft = Double.MAX_VALUE;
        double contentRight = -Double.MAX_VALUE;
        int fragments = 0;
        for (TextFragment f : tfa.getTextFragments()) {
            double y = f.getPosition().getYIndent();
            byLine.computeIfAbsent(Math.round(y), k -> new ArrayList<>())
                    .add(new double[]{f.getRectangle().getLLX(), f.getRectangle().getURX()});
            contentLeft = Math.min(contentLeft, f.getRectangle().getLLX());
            contentRight = Math.max(contentRight, f.getRectangle().getURX());
            fragments++;
        }
        double width = contentRight - contentLeft;
        for (Map.Entry<Long, List<double[]>> e : byLine.entrySet()) {
            double left = Double.MAX_VALUE;
            double right = -Double.MAX_VALUE;
            for (double[] fr : e.getValue()) {
                left = Math.min(left, fr[0]);
                right = Math.max(right, fr[1]);
            }
            assertTrue(right - left <= width + 1, "line at y=" + e.getKey()
                    + " exceeds content width: " + (right - left) + " > " + width);
        }
        assertTrue(byLine.size() < fragments,
                "rewrap must pack several fragments per line: " + byLine.size()
                        + " lines for " + fragments + " fragments");
        assertTrue(byLine.size() < 2 * LINES, "fewer lines than the stacked layout");
    }

    /** MULTI_MIXED pages are skipped with an explicit reason. */
    @Test
    public void mixedPageSkipped() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        line(p, "Full Width Heading Spanning Both Columns Of This Mixed Test Page",
                72, 750);
        for (int i = 0; i < LINES; i++) {
            line(p, "Left line " + i + " text", 72, 700 - i * 18);
            line(p, "Right line " + i + " text", 330, 700 - i * 18);
        }
        Path src = tempDir.resolve("mixed.pdf");
        doc.save(src.toString());
        Document work = new Document(src.toString());
        ColumnMerger.MergeResult r = ColumnMerger.mergePage(work, readModel(work, src), 0, false);
        assertTrue(!r.isMerged());
        assertTrue(r.getSkipReason().contains("multi-mixed"), r.getSkipReason());
    }

    private static Map<String, double[]> positions(Document doc) throws IOException {
        TextFragmentAbsorber tfa = new TextFragmentAbsorber();
        tfa.visit(doc.getPages().get(1));
        Map<String, double[]> out = new LinkedHashMap<>();
        for (TextFragment f : tfa.getTextFragments()) {
            out.put(f.getText(), new double[]{f.getPosition().getXIndent(),
                    f.getPosition().getYIndent()});
        }
        return out;
    }
}
