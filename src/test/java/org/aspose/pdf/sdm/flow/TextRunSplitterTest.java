package org.aspose.pdf.sdm.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TextRunSplitter}: a leading box is pulled out of a
 * MONOLITHIC {@code BT…ET} run (three lines sharing one text object, the shape
 * the raw-envelope transplant cannot handle) and re-materialised on another
 * page, with the remaining lines kept in place and all text conserved.
 */
public class TextRunSplitterTest {

    /** Three lines in ONE BT..ET; move the first line to a fresh page. */
    @Test
    public void splitsMonolithicRunAndConservesText() throws Exception {
        Document doc = new Document();
        Page src = doc.getPages().add();
        addHelvetica(src);
        src.setContents(new OperatorCollection(Arrays.asList(
                op("BT"),
                op("Tf", PdfName.of("F1"), num(12)),
                op("Td", num(72), num(700)),
                op("Tj", new PdfString("Alpha")),
                op("Td", num(0), num(-20)),
                op("Tj", new PdfString("Bravo")),
                op("Td", num(0), num(-20)),
                op("Tj", new PdfString("Charlie")),
                op("ET"))));
        Page tgt = doc.getPages().add();

        PdfSdmReader.Result m = new PdfSdmReader().read(doc, null);
        FlowClassifier.classify(m.getPgm());
        PgmPage sp = m.getPgm().getPage(0);

        PgmBox alpha = boxWithText(sp, "Alpha");
        assertNotNull(alpha, "Alpha box resolved");
        PgmBox bravoBefore = boxWithText(sp, "Bravo");
        assertNotNull(bravoBefore, "Bravo present before split");
        long bravoY = Math.round(bravoBefore.getRect().getY());

        List<Operator> sourceOps = new ArrayList<>(src.getContents().getAll());
        Set<PgmBox> moved = new HashSet<>();
        moved.add(alpha);
        TextRunSplitter.SplitPlan plan = TextRunSplitter.plan(
                src, tgt, sourceOps, sp.getBoxes(), moved, -100.0);
        assertNotNull(plan, "split plan produced for a monolithic BT..ET");

        src.setContents(new OperatorCollection(plan.getNewSourceOps()));
        tgt.setContents(new OperatorCollection(plan.getTargetAppendOps()));

        // Round-trip and confirm: Alpha moved to page 2, Bravo/Charlie stayed.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        Document re = new Document(new ByteArrayInputStream(baos.toByteArray()));
        PdfSdmReader.Result m2 = new PdfSdmReader().read(re, null);
        FlowClassifier.classify(m2.getPgm());

        String srcAfter = allText(m2.getPgm().getPage(0));
        String tgtAfter = allText(m2.getPgm().getPage(1));
        assertTrue(tgtAfter.contains("Alpha"), "Alpha moved to target: '" + tgtAfter + "'");
        assertTrue(!srcAfter.contains("Alpha"), "Alpha removed from source: '" + srcAfter + "'");
        assertTrue(srcAfter.contains("Bravo") && srcAfter.contains("Charlie"),
                "kept lines survive on source: '" + srcAfter + "'");

        // Kept lines keep their y positions (no reflow from removing the leader).
        PgmBox bravo = boxWithText(m2.getPgm().getPage(0), "Bravo");
        assertNotNull(bravo, "Bravo present");
        assertEquals(bravoY, Math.round(bravo.getRect().getY()),
                "Bravo kept its y (no reflow after the leader was pulled)");
    }

    // ----------------------------------------------------------------- helpers

    private static void addHelvetica(Page page) {
        PdfDictionary font = new PdfDictionary();
        font.set("Type", PdfName.of("Font"));
        font.set("Subtype", PdfName.of("Type1"));
        font.set("BaseFont", PdfName.of("Helvetica"));
        font.set("Encoding", PdfName.of("WinAnsiEncoding"));
        PdfDictionary fonts = new PdfDictionary();
        fonts.set("F1", font);
        page.ensureResources().getPdfDictionary().set("Font", fonts);
    }

    private static Operator op(String name, PdfBase... operands) {
        return new Operator(name, new ArrayList<>(Arrays.asList(operands)));
    }

    private static PdfFloat num(double v) {
        return new PdfFloat(v);
    }

    private static PgmBox boxWithText(PgmPage page, String needle) {
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData
                    && ((TextBoxData) b.getData()).getText().contains(needle)
                    && b.getSourceRef() instanceof ContentRange) {
                return b;
            }
        }
        return null;
    }

    private static String allText(PgmPage page) {
        StringBuilder sb = new StringBuilder();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData) {
                sb.append(((TextBoxData) b.getData()).getText()).append(' ');
            }
        }
        return sb.toString();
    }
}
