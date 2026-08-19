package org.aspose.pdf.sdm.reader;

import org.aspose.pdf.Document;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.SquareAnnotation;
import org.aspose.pdf.pgm.ImageBoxData;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.pgm.VectorBoxData;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.ObjectRef;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmNodeType;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PART 2 gate: on a synthetic PDF with known content (two paragraphs at known
 * positions, one image, one drawn line, one annotation) the reader yields
 * exactly the expected node/box counts, kinds, bbox coordinates (within
 * epsilon), sourceRef types, and deterministic GUIDs across two reads.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PdfSdmReaderTest {

    private static final double EPS = 1.0;

    @TempDir
    static Path tempDir;

    private Path pdfPath;
    private byte[] pdfBytes;

    @BeforeAll
    void buildFixture() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();

        TextFragment tf1 = new TextFragment("First paragraph text");
        tf1.setPosition(new Position(100, 700));
        new TextBuilder(page).appendText(tf1);
        TextFragment tf2 = new TextFragment("Second paragraph text");
        tf2.setPosition(new Position(100, 600));
        new TextBuilder(page).appendText(tf2);

        // One straight stroked line at known coordinates.
        page.appendToContentStream(
                "q\n100 100 m\n300 100 l\nS\nQ\n".getBytes(StandardCharsets.ISO_8859_1));

        // One image at a known placement (cm = [50 0 0 40 200 400]).
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(img, "JPEG", jpeg);
        ImageStamp stamp = new ImageStamp("ignored.jpg");
        stamp.setImageStream(new ByteArrayInputStream(jpeg.toByteArray()));
        stamp.setXIndent(200);
        stamp.setYIndent(400);
        stamp.setWidth(50);
        stamp.setHeight(40);
        page.addStamp(stamp);

        // One annotation.
        SquareAnnotation square = new SquareAnnotation(page,
                new Rectangle(400, 500, 480, 560));
        page.getAnnotations().add(square);

        pdfPath = tempDir.resolve("sdm-fixture.pdf");
        doc.save(pdfPath.toString());
        pdfBytes = Files.readAllBytes(pdfPath);
    }

    private PdfSdmReader.Result readFixture() throws IOException {
        Document doc = new Document(pdfPath.toString());
        return new PdfSdmReader().read(doc, pdfBytes);
    }

    private static List<PgmBox> boxesOf(PgmModel pgm, PgmBoxKind kind) {
        List<PgmBox> out = new ArrayList<>();
        for (PgmBox b : pgm.getPage(0).getBoxes()) {
            if (b.getKind() == kind) {
                out.add(b);
            }
        }
        return out;
    }

    /** Exact node/box counts and kinds. */
    @Test
    public void exactCountsAndKinds() throws IOException {
        PdfSdmReader.Result r = readFixture();
        PgmModel pgm = r.getPgm();
        assertEquals(1, pgm.getPages().size());

        assertEquals(2, boxesOf(pgm, PgmBoxKind.TEXT).size(), "two text fragments");
        assertEquals(1, boxesOf(pgm, PgmBoxKind.IMAGE).size(), "one image");
        assertEquals(1, boxesOf(pgm, PgmBoxKind.VECTOR).size(), "one drawn line");
        assertEquals(1, boxesOf(pgm, PgmBoxKind.ANNOTATION).size(), "one annotation");

        SdmDocument sdm = r.getSdm();
        int paragraphs = 0;
        int figures = 0;
        int opaques = 0;
        for (SdmBlock b : sdm.getChildren()) {
            if (b.getType() == SdmNodeType.PARAGRAPH) {
                paragraphs++;
            } else if (b.getType() == SdmNodeType.FIGURE) {
                figures++;
            } else if (b.getType() == SdmNodeType.OPAQUE) {
                opaques++;
            }
        }
        assertEquals(2, paragraphs, "two SDM paragraphs");
        assertEquals(1, figures, "one SDM figure");
        assertEquals(2, opaques, "vector opaque + annotation opaque");
    }

    /** Page geometry matches the PDF page. */
    @Test
    public void pageGeometry() throws IOException {
        PdfSdmReader.Result r = readFixture();
        Document doc = new Document(pdfPath.toString());
        Rectangle rect = doc.getPages().get(1).getRect();
        PgmPage p = r.getPgm().getPage(0);
        assertEquals(rect.getWidth(), p.getWidth(), 0.01);
        assertEquals(rect.getHeight(), p.getHeight(), 0.01);
    }

    /** Text boxes carry the authored positions and texts. */
    @Test
    public void textBoxCoordinatesAndContent() throws IOException {
        PdfSdmReader.Result r = readFixture();
        List<PgmBox> text = boxesOf(r.getPgm(), PgmBoxKind.TEXT);
        // Reading order: top line (y=700) first.
        PgmBox first = text.get(0).getReadingIndex() < text.get(1).getReadingIndex()
                ? text.get(0) : text.get(1);
        PgmBox second = first == text.get(0) ? text.get(1) : text.get(0);

        assertEquals(100, first.getRect().getX(), EPS, "first line left edge");
        TextBoxData d1 = (TextBoxData) first.getData();
        assertEquals(700, d1.getBaselineY(), 2.0, "first line baseline");
        assertEquals("First paragraph text", d1.getText());

        assertEquals(100, second.getRect().getX(), EPS);
        TextBoxData d2 = (TextBoxData) second.getData();
        assertEquals(600, d2.getBaselineY(), 2.0);
        assertEquals("Second paragraph text", d2.getText());

        // SDM paragraphs carry the same texts in reading order.
        List<String> paraTexts = new ArrayList<>();
        for (SdmBlock b : r.getSdm().getChildren()) {
            if (b instanceof Paragraph) {
                paraTexts.add(((Paragraph) b).getText());
            }
        }
        assertEquals(List.of("First paragraph text", "Second paragraph text"), paraTexts);
    }

    /** The vector line box: exact endpoints; bbox = path expanded by the stroke
     *  (default line width 1 → half a point on each side, §8.4.3.2), so a
     *  horizontal line projects a 1pt-tall VISUAL box, not a 0-height one. */
    @Test
    public void vectorLineBox() throws IOException {
        PdfSdmReader.Result r = readFixture();
        PgmBox v = boxesOf(r.getPgm(), PgmBoxKind.VECTOR).get(0);
        assertEquals(99.5, v.getRect().getX(), 0.01);
        assertEquals(99.5, v.getRect().getY(), 0.01);
        assertEquals(201, v.getRect().getW(), 0.01);
        assertEquals(1, v.getRect().getH(), 0.01);
        VectorBoxData d = (VectorBoxData) v.getData();
        assertEquals(VectorBoxData.Primitive.LINE, d.getPrimitive());
        assertEquals(100, d.getX1(), 0.01);
        assertEquals(100, d.getY1(), 0.01);
        assertEquals(300, d.getX2(), 0.01);
        assertEquals(100, d.getY2(), 0.01);
        assertTrue(d.isStroked());
        assertTrue(!d.isFilled());
    }

    /** The image box sits exactly at the stamp placement (cm 50 0 0 40 200 400). */
    @Test
    public void imageBoxPlacement() throws IOException {
        PdfSdmReader.Result r = readFixture();
        PgmBox img = boxesOf(r.getPgm(), PgmBoxKind.IMAGE).get(0);
        assertEquals(200, img.getRect().getX(), 0.01);
        assertEquals(400, img.getRect().getY(), 0.01);
        assertEquals(50, img.getRect().getW(), 0.01);
        assertEquals(40, img.getRect().getH(), 0.01);
        ImageBoxData d = (ImageBoxData) img.getData();
        assertNotNull(d.getResourceRef(), "image bytes referenced in ResourceTable");
        // The figure resolves to a stored resource with bytes.
        Figure fig = null;
        for (SdmBlock b : r.getSdm().getChildren()) {
            if (b instanceof Figure) {
                fig = (Figure) b;
            }
        }
        assertNotNull(fig);
        assertNotNull(r.getSdm().getResources().get(fig.getImage()));
        assertTrue(r.getSdm().getResources().get(fig.getImage()).getBytes().length > 0);
    }

    /** sourceRef typing: stream content → ContentRange with plausible operator
     *  indices; annotations → ObjectRef with a real object number. */
    @Test
    public void sourceRefTyping() throws IOException {
        PdfSdmReader.Result r = readFixture();
        Document doc = new Document(pdfPath.toString());
        int opCount = doc.getPages().get(1).getContents().size();

        for (PgmBox b : r.getPgm().getPage(0).getBoxes()) {
            switch (b.getKind()) {
                case TEXT:
                case IMAGE:
                case VECTOR:
                    assertTrue(b.getSourceRef() instanceof ContentRange,
                            b.getKind() + " must use ContentRange");
                    ContentRange cr = (ContentRange) b.getSourceRef();
                    assertTrue(cr.getOpStart() >= 0 && cr.getOpEnd() < opCount
                                    && cr.getOpStart() <= cr.getOpEnd(),
                            "plausible op range " + cr.canonical() + " of " + opCount);
                    break;
                case ANNOTATION:
                case FIELD:
                    assertTrue(b.getSourceRef() instanceof ObjectRef,
                            "annotation must use ObjectRef");
                    assertTrue(((ObjectRef) b.getSourceRef()).getObjNum() > 0);
                    break;
                default:
                    break;
            }
        }
    }

    /** z-order equals drawing order: strictly increasing, content sorted by
     *  operator start, annotations last. */
    @Test
    public void zOrderIsDrawingOrder() throws IOException {
        PdfSdmReader.Result r = readFixture();
        List<PgmBox> boxes = r.getPgm().getPage(0).getBoxes();
        int prevZ = -1;
        int prevOpStart = -1;
        boolean seenAnnot = false;
        for (PgmBox b : boxes) {
            assertTrue(b.getZ() > prevZ, "z strictly increasing");
            prevZ = b.getZ();
            if (b.getSourceRef() instanceof ContentRange) {
                assertTrue(!seenAnnot, "content boxes precede annotation boxes");
                int s = ((ContentRange) b.getSourceRef()).getOpStart();
                assertTrue(s >= prevOpStart, "content sorted by opStart");
                prevOpStart = s;
            } else {
                seenAnnot = true;
            }
        }
    }

    /** GUID determinism: two independent reads of the same file produce the
     *  same ids for the same boxes; ids link SDM nodes and PGM boxes. */
    @Test
    public void guidDeterminismAcrossReads() throws IOException {
        PdfSdmReader.Result r1 = readFixture();
        PdfSdmReader.Result r2 = readFixture();
        List<PgmBox> b1 = r1.getPgm().getPage(0).getBoxes();
        List<PgmBox> b2 = r2.getPgm().getPage(0).getBoxes();
        assertEquals(b1.size(), b2.size());
        for (int i = 0; i < b1.size(); i++) {
            assertEquals(b1.get(i).getId(), b2.get(i).getId(), "box " + i + " id stable");
            assertNotNull(b1.get(i).getId());
        }
        // Every box id resolves back through the model index.
        for (PgmBox b : b1) {
            assertTrue(!r1.getPgm().byId(b.getId()).isEmpty());
        }
        // Paragraph ids are shared with their TEXT boxes.
        for (SdmBlock blk : r1.getSdm().getChildren()) {
            if (blk instanceof Paragraph) {
                assertTrue(!r1.getPgm().byId(blk.getId()).isEmpty(),
                        "paragraph id has PGM boxes");
            }
        }
    }

    /** §2.8-4: the text captured by SDM equals the absorber text (whitespace-
     *  normalized: the absorber inserts line separators between fragments). */
    @Test
    public void sdmTextEqualsAbsorberText() throws IOException {
        PdfSdmReader.Result r = readFixture();
        Document doc = new Document(pdfPath.toString());
        TextAbsorber ta = new TextAbsorber();
        ta.visit(doc.getPages().get(1));
        String absorber = ta.getText().replaceAll("\\s+", " ").trim();

        StringBuilder sdmText = new StringBuilder();
        for (SdmBlock b : r.getSdm().getChildren()) {
            if (b instanceof Paragraph) {
                sdmText.append(((Paragraph) b).getText()).append(' ');
            }
        }
        assertEquals(absorber, sdmText.toString().replaceAll("\\s+", " ").trim());
    }
}
