package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.HighlightAnnotation;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextFragmentAbsorber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR Stage 2 PART 1 gate: in-page vertical compaction. Synthetic fixtures with
 * exact expectations: hole closed to natural spacing (coordinates epsilon),
 * chrome fixed and untouched, annotation Rect+QuadPoints follow their text,
 * captions follow images, [TXT] invariant, untouched pages byte-identical,
 * below-threshold gaps untouched.
 */
public class FlowCompactorTest {

    private static final double EPS = 1.0;

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------- helpers

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    /** Three paragraphs; a 122pt hole between para1 and para2; 12pt natural gap. */
    private Path buildHoleDoc() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        line(p, "Para one line one", 72, 700);
        line(p, "Para one line two", 72, 688);
        line(p, "Para one line three", 72, 676);
        // hole (deleted content): next paragraph far below
        line(p, "Para two line one", 72, 540);
        line(p, "Para two line two", 72, 528);
        line(p, "Para two line three", 72, 516);
        // natural gap to para three
        line(p, "Para three line one", 72, 490);
        line(p, "Para three line two", 72, 478);
        // page 2: untouched
        Page p2 = doc.getPages().add();
        line(p2, "Second page static text", 72, 700);
        Path path = tempDir.resolve("hole.pdf");
        doc.save(path.toString());
        return path;
    }

    private static java.util.Map<String, Double> baselines(Document doc, int pageNumber)
            throws IOException {
        TextFragmentAbsorber tfa = new TextFragmentAbsorber();
        tfa.visit(doc.getPages().get(pageNumber));
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (org.aspose.pdf.text.TextFragment f : tfa.getTextFragments()) {
            if (f.getPosition() != null) {
                out.put(f.getText(), f.getPosition().getYIndent());
            }
        }
        return out;
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

    private static CompactionResult compact(Document doc, Path source,
                                            CompactionOptions opts) throws IOException {
        PdfSdmReader.Result model = new PdfSdmReader().read(doc, Files.readAllBytes(source));
        return FlowCompactor.compactInPage(doc, model, opts);
    }

    // --------------------------------------------------------------- tests

    /** (a) The 122pt hole closes to natural spacing; order and coords exact. */
    @Test
    public void holeClosedToNaturalSpacing() throws IOException {
        Path src = buildHoleDoc();
        Document doc = new Document(src.toString());
        java.util.Map<String, Double> before = baselines(doc, 1);

        CompactionResult result = compact(doc, src, new CompactionOptions());
        assertEquals(1, result.getGapsClosed(), "exactly one hole");
        assertTrue(result.getPagesSkipped().isEmpty(), "no skips: " + result.getPagesSkipped());

        Path out = tempDir.resolve("hole-compacted.pdf");
        doc.save(out.toString());
        Document reopened = new Document(out.toString());
        java.util.Map<String, Double> after = baselines(reopened, 1);

        // Para 1 (above the hole) does not move.
        assertEquals(before.get("Para one line one"), after.get("Para one line one"), EPS);
        assertEquals(before.get("Para one line three"), after.get("Para one line three"), EPS);
        // Paras 2 and 3 move UP by the same delta; the delta equals
        // hole − naturalSpacing, where the natural gap (para2→para3) is
        // band-geometry-derived; assert the RESULTING geometry instead:
        double d2 = after.get("Para two line one") - before.get("Para two line one");
        double d3 = after.get("Para three line one") - before.get("Para three line one");
        assertTrue(d2 > 0, "para two moved up, delta=" + d2);
        assertEquals(d2, d3, EPS, "paras below the hole share one delta");
        // Hole after compaction equals the natural para2→para3 gap: baseline
        // distance para1.last → para2.first equals para2.last → para3.first.
        double naturalBaselineGap = after.get("Para two line three") - after.get("Para three line one");
        double closedBaselineGap = after.get("Para one line three") - after.get("Para two line one");
        assertEquals(naturalBaselineGap, closedBaselineGap, EPS,
                "hole closed to the page's own natural spacing");
        // Reading order preserved.
        assertEquals(extractAll(new Document(src.toString())), extractAll(reopened),
                "[TXT] invariant");
    }

    /** (b) Chrome (repeated header + page numbers) does not move; body compacts. */
    @Test
    public void chromeStaysBodyCompacts() throws IOException {
        Document doc = new Document();
        for (int i = 1; i <= 3; i++) {
            Page p = doc.getPages().add();
            line(p, "ACME CONFIDENTIAL", 72, 770);
            line(p, String.valueOf(i), 300, 30);
            line(p, "Body top of page " + i, 72, 700);
            line(p, "Body bottom of page " + i, 72, 540); // 100+pt hole
        }
        Path src = tempDir.resolve("chrome.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        CompactionResult result = compact(work, src, new CompactionOptions());
        assertEquals(3, result.getGapsClosed(), "one hole per page");
        Path out = tempDir.resolve("chrome-compacted.pdf");
        work.save(out.toString());

        Document reopened = new Document(out.toString());
        for (int i = 1; i <= 3; i++) {
            java.util.Map<String, Double> after = baselines(reopened, i);
            assertEquals(770, after.get("ACME CONFIDENTIAL"), EPS, "header fixed p" + i);
            assertEquals(30, after.get(String.valueOf(i)), EPS, "page number fixed p" + i);
            assertEquals(700, after.get("Body top of page " + i), EPS, "first band stays");
            double moved = after.get("Body bottom of page " + i);
            assertTrue(moved > 540 + 50, "body compacted up on p" + i + ": " + moved);
            assertTrue(moved < 700, "body stays below the band above it");
        }
    }

    /** (c) Highlight Rect AND QuadPoints shift by exactly the text's delta. */
    @Test
    public void highlightFollowsItsParagraph() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        line(p, "Top paragraph", 72, 700);
        line(p, "Highlighted paragraph", 72, 540); // below a 100+pt hole
        HighlightAnnotation hl = new HighlightAnnotation(p,
                new Rectangle(70, 535, 220, 552));
        p.getAnnotations().add(hl);
        Path src = tempDir.resolve("annot.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        double[] rectBefore = rectOf(work, 1);
        double[] quadsBefore = quadsOf(work, 1);
        java.util.Map<String, Double> before = baselines(work, 1);

        CompactionResult result = compact(work, src, new CompactionOptions());
        assertEquals(1, result.getGapsClosed());
        Path out = tempDir.resolve("annot-compacted.pdf");
        work.save(out.toString());

        Document reopened = new Document(out.toString());
        double delta = baselines(reopened, 1).get("Highlighted paragraph")
                - before.get("Highlighted paragraph");
        assertTrue(delta > 50, "paragraph moved up by " + delta);

        double[] rectAfter = rectOf(reopened, 1);
        assertEquals(rectBefore[0], rectAfter[0], EPS, "rect llx unchanged");
        assertEquals(rectBefore[1] + delta, rectAfter[1], EPS, "rect lly shifted by delta");
        assertEquals(rectBefore[3] + delta, rectAfter[3], EPS, "rect ury shifted by delta");
        double[] quadsAfter = quadsOf(reopened, 1);
        assertEquals(quadsBefore.length, quadsAfter.length);
        for (int k = 0; k < quadsBefore.length; k++) {
            double expected = quadsBefore[k] + (k % 2 == 0 ? 0 : delta);
            assertEquals(expected, quadsAfter[k], EPS, "quad[" + k + "] shifted");
        }
    }

    /** (d) A caption under a shifted image moves with it. */
    @Test
    public void captionFollowsImage() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        line(p, "Heading paragraph", 72, 720);
        // hole, then image with caption
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", jpeg);
        ImageStamp stamp = new ImageStamp("ignored.jpg");
        stamp.setImageStream(new ByteArrayInputStream(jpeg.toByteArray()));
        stamp.setXIndent(200);
        stamp.setYIndent(450);
        stamp.setWidth(60);
        stamp.setHeight(50);
        p.addStamp(stamp); // image occupies y 450..500
        line(p, "Figure 1: caption", 200, 440);
        Path src = tempDir.resolve("caption.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        java.util.Map<String, Double> before = baselines(work, 1);
        CompactionResult result = compact(work, src, new CompactionOptions());
        assertEquals(1, result.getGapsClosed());
        Path out = tempDir.resolve("caption-compacted.pdf");
        work.save(out.toString());

        Document reopened = new Document(out.toString());
        java.util.Map<String, Double> after = baselines(reopened, 1);
        double capDelta = after.get("Figure 1: caption") - before.get("Figure 1: caption");
        assertTrue(capDelta > 50, "caption moved with its image, delta=" + capDelta);
        // Image placement moved by the same delta.
        org.aspose.pdf.ImagePlacementAbsorber ipa = new org.aspose.pdf.ImagePlacementAbsorber();
        reopened.getPages().get(1).accept(ipa);
        assertEquals(1, ipa.getImagePlacements().size());
        double imgY = ipa.getImagePlacements().get(0).getRectangle().getLLY();
        assertEquals(450 + capDelta, imgY, EPS, "image shifted by the same delta");
    }

    /** (e+f) [TXT] invariant everywhere; untouched pages byte-identical. */
    @Test
    public void untouchedPageBytesAndText() throws IOException {
        Path src = buildHoleDoc();
        Document work = new Document(src.toString());
        compact(work, src, new CompactionOptions());
        Path out = tempDir.resolve("untouched-check.pdf");
        work.save(out.toString());

        byte[] p2Before = decodedContent(new Document(src.toString()), 2);
        byte[] p2After = decodedContent(new Document(out.toString()), 2);
        org.junit.jupiter.api.Assertions.assertArrayEquals(p2Before, p2After,
                "page 2 had no oversized gaps and must be byte-identical");
        assertEquals(extractAll(new Document(src.toString())),
                extractAll(new Document(out.toString())), "[TXT] invariant");
    }

    /** (g) Below-threshold gaps are NOT closed. */
    @Test
    public void naturalGapsUntouched() throws IOException {
        Document doc = new Document();
        Page p = doc.getPages().add();
        line(p, "Alpha", 72, 700);
        line(p, "Beta", 72, 676);  // 24pt gap < 30 threshold
        line(p, "Gamma", 72, 652);
        Path src = tempDir.resolve("natural.pdf");
        doc.save(src.toString());

        Document work = new Document(src.toString());
        CompactionResult result = compact(work, src, new CompactionOptions());
        assertEquals(0, result.getGapsClosed(), "no hole to close");
        Path out = tempDir.resolve("natural-out.pdf");
        work.save(out.toString());
        java.util.Map<String, Double> after = baselines(new Document(out.toString()), 1);
        assertEquals(700, after.get("Alpha"), EPS);
        assertEquals(676, after.get("Beta"), EPS);
        assertEquals(652, after.get("Gamma"), EPS);
    }

    // ------------------------------------------------------------ plumbing

    private static double[] rectOf(Document doc, int pageNumber) throws IOException {
        Annotation a = doc.getPages().get(pageNumber).getAnnotations().get(1);
        Rectangle r = a.getRect();
        return new double[]{r.getLLX(), r.getLLY(), r.getURX(), r.getURY()};
    }

    private static double[] quadsOf(Document doc, int pageNumber) throws IOException {
        Annotation a = doc.getPages().get(pageNumber).getAnnotations().get(1);
        PdfBase qp = a.getPdfDictionary().get("QuadPoints");
        assertNotNull(qp);
        PdfArray arr = (PdfArray) qp;
        double[] out = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            PdfBase v = arr.get(i);
            out[i] = v instanceof PdfInteger ? ((PdfInteger) v).longValue()
                    : ((PdfFloat) v).doubleValue();
        }
        return out;
    }

    private static byte[] decodedContent(Document doc, int pageNumber) throws IOException {
        Page page = doc.getPages().get(pageNumber);
        PdfBase contents = page.getPdfDictionary()
                .get(org.aspose.pdf.engine.pdfobjects.PdfName.of("Contents"));
        if (contents instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            contents = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) contents)
                    .dereference();
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        if (contents instanceof org.aspose.pdf.engine.pdfobjects.PdfStream) {
            all.write(((org.aspose.pdf.engine.pdfobjects.PdfStream) contents).getDecodedData());
        } else if (contents instanceof PdfArray) {
            PdfArray arr = (PdfArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase e = arr.get(i);
                if (e instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
                    e = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) e).dereference();
                }
                if (e instanceof org.aspose.pdf.engine.pdfobjects.PdfStream) {
                    all.write(((org.aspose.pdf.engine.pdfobjects.PdfStream) e).getDecodedData());
                }
            }
        }
        return all.toByteArray();
    }
}
