package org.aspose.pdf.pgm;

import org.aspose.pdf.Document;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.HighlightAnnotation;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.text.Position;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PART 5 gate (synthetic fixture): a 4-page document with a repeated header,
 * page numbers, one highlight annotation, and an image with a caption — every
 * box gets its exact expected flowClass: header/pagenum=FIXED, highlight=
 * ANCHORED to the right text GUID, caption=ANCHORED to the image, body=FLOW.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FlowClassifierTest {

    @TempDir
    static Path tempDir;

    private PdfSdmReader.Result result;

    @BeforeAll
    void buildAndClassify() throws IOException {
        Document doc = new Document();
        for (int p = 1; p <= 4; p++) {
            Page page = doc.getPages().add();

            TextFragment header = new TextFragment("ACME Corporation Confidential");
            header.setPosition(new Position(72, 770));
            new TextBuilder(page).appendText(header);

            TextFragment pageNum = new TextFragment(String.valueOf(p));
            pageNum.setPosition(new Position(300, 30));
            new TextBuilder(page).appendText(pageNum);

            TextFragment body = new TextFragment(
                    "Body content of page " + p + " which is unique flowing text");
            body.setPosition(new Position(72, 600));
            new TextBuilder(page).appendText(body);

            if (p == 1) {
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

                TextFragment caption = new TextFragment("Figure 1: caption");
                caption.setPosition(new Position(200, 390));
                new TextBuilder(page).appendText(caption);

                page.getAnnotations().add(new HighlightAnnotation(page,
                        new Rectangle(72, 595, 272, 615)));
            }

            if (p == 2) {
                // A horizontal strip of three identical icons (a GHS-pictogram
                // row) plus a narrow "caption-shaped" label directly under the
                // middle icon. Without the row guard the label would anchor as
                // that icon's caption; the strip must instead stay an image row.
                for (int k = 0; k < 3; k++) {
                    BufferedImage ic = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
                    ByteArrayOutputStream j = new ByteArrayOutputStream();
                    ImageIO.write(ic, "JPEG", j);
                    ImageStamp s = new ImageStamp("ignored.jpg");
                    s.setImageStream(new ByteArrayInputStream(j.toByteArray()));
                    s.setXIndent(120 + k * 60);
                    s.setYIndent(400);
                    s.setWidth(40);
                    s.setHeight(40);
                    page.addStamp(s);
                }
                TextFragment label = new TextFragment("OK");
                label.setPosition(new Position(185, 392)); // narrow, under middle icon
                new TextBuilder(page).appendText(label);
            }
        }
        Path path = tempDir.resolve("flowclass-fixture.pdf");
        doc.save(path.toString());

        Document reopened = new Document(path.toString());
        result = new PdfSdmReader().read(reopened, Files.readAllBytes(path));
        FlowClassifier.classify(result.getPgm());
    }

    private List<PgmBox> textBoxes(int page, String contains) {
        List<PgmBox> out = new ArrayList<>();
        for (PgmBox b : result.getPgm().getPage(page).getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData
                    && ((TextBoxData) b.getData()).getText().contains(contains)) {
                out.add(b);
            }
        }
        return out;
    }

    /** The repeated header is FIXED on every page. */
    @Test
    public void headerIsFixedOnEveryPage() {
        for (int p = 0; p < 4; p++) {
            List<PgmBox> header = textBoxes(p, "ACME Corporation Confidential");
            assertEquals(1, header.size(), "header box on page " + p);
            assertEquals(FlowClass.FIXED, header.get(0).getFlowClass(),
                    "header on page " + p + " must be FIXED");
        }
    }

    /** Page numbers are FIXED via the margin-band numeric rule. */
    @Test
    public void pageNumbersAreFixed() {
        for (int p = 0; p < 4; p++) {
            List<PgmBox> found = new ArrayList<>();
            for (PgmBox b : result.getPgm().getPage(p).getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData
                        && ((TextBoxData) b.getData()).getText().trim()
                                .equals(String.valueOf(p + 1))) {
                    found.add(b);
                }
            }
            assertEquals(1, found.size(), "page number box on page " + p);
            assertEquals(FlowClass.FIXED, found.get(0).getFlowClass(),
                    "page number on page " + p + " must be FIXED");
        }
    }

    /** Body text is FLOW on every page (chrome rules must NOT catch it). */
    @Test
    public void bodyIsFlow() {
        for (int p = 0; p < 4; p++) {
            List<PgmBox> body = textBoxes(p, "Body content of page " + (p + 1));
            assertEquals(1, body.size(), "body box on page " + p);
            assertEquals(FlowClass.FLOW, body.get(0).getFlowClass(),
                    "body on page " + p + " must stay FLOW");
        }
    }

    /** The highlight anchors to the GUID of the body text it covers. */
    @Test
    public void highlightAnchorsToItsText() {
        PgmBox highlight = null;
        for (PgmBox b : result.getPgm().getPage(0).getBoxes()) {
            if (b.getKind() == PgmBoxKind.ANNOTATION && b.getData() instanceof AnnotBoxData
                    && "Highlight".equals(((AnnotBoxData) b.getData()).getSubtype())) {
                highlight = b;
            }
        }
        assertNotNull(highlight, "highlight box present");
        assertEquals(FlowClass.ANCHORED, highlight.getFlowClass());
        String bodyId = textBoxes(0, "Body content of page 1").get(0).getId();
        assertEquals(bodyId, highlight.getAnchorTargetId(),
                "highlight must anchor to the covered body text GUID");
    }

    /** A label under a ROW of icons is not captured as one icon's caption. */
    @Test
    public void iconRowLabelStaysFlow() {
        List<PgmBox> label = textBoxes(1, "OK");
        assertEquals(1, label.size(), "the row label box exists on page 2");
        assertEquals(FlowClass.FLOW, label.get(0).getFlowClass(),
                "a label under an icon ROW must stay FLOW, not anchor as a caption");
        assertNull(label.get(0).getAnchorTargetId(),
                "row label must not anchor to any icon");
        // And no icon in the strip received a caption anchor from any text.
        for (PgmBox b : result.getPgm().getPage(1).getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT) {
                assertNull(b.getAnchorTargetId(),
                        "no text should caption-anchor to an icon-row member");
            }
        }
    }

    /** The caption anchors to the image above it. */
    @Test
    public void captionAnchorsToImage() {
        List<PgmBox> caption = textBoxes(0, "Figure 1: caption");
        assertEquals(1, caption.size());
        assertEquals(FlowClass.ANCHORED, caption.get(0).getFlowClass(),
                "caption must be ANCHORED");
        PgmBox image = null;
        for (PgmBox b : result.getPgm().getPage(0).getBoxes()) {
            if (b.getKind() == PgmBoxKind.IMAGE) {
                image = b;
            }
        }
        assertNotNull(image);
        assertEquals(image.getId(), caption.get(0).getAnchorTargetId(),
                "caption must anchor to the image GUID");
        assertEquals(FlowClass.FLOW, image.getFlowClass(), "the image itself flows");
    }

    /** Every box got a class; nothing was left unclassified (FLOW is a class). */
    @Test
    public void everyBoxHasAClass() {
        int total = 0;
        for (PgmPage page : result.getPgm().getPages()) {
            for (PgmBox b : page.getBoxes()) {
                assertNotNull(b.getFlowClass());
                total++;
            }
        }
        assertTrue(total >= 14, "expected at least 14 boxes, saw " + total);
    }
}
