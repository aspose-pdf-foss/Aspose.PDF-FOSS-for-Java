package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;

/**
 * Gate for {@link ReadingOrderNormalizer}: a flow scrambled into "all figures,
 * then all text" (the tagged-enrichment failure mode) must be restored to
 * geometric page-then-top-to-bottom order, and a box-less decoration must travel
 * with the neighbour it followed.
 */
class ReadingOrderNormalizerTest {

    private static final double PAGE_H = 842;

    @Test
    void restoresPageThenTopToBottomOrder() {
        SdmDocument sdm = new SdmDocument();
        PgmModel pgm = new PgmModel();
        pgm.addPage(new PgmPage(0, 595, PAGE_H, 0));
        pgm.addPage(new PgmPage(1, 595, PAGE_H, 0));

        // Intentionally scrambled: both figures first, then the text — but the
        // geometry says image(top p0), title(mid p0), image(top p1), body(mid p1).
        Figure imgP0 = figure("f0", pgm, 0, 700);
        Figure imgP1 = figure("f1", pgm, 1, 700);
        Paragraph titleP0 = para("p0", pgm, 0, 500, "Titre page une");
        Paragraph bodyP1 = para("p1", pgm, 1, 500, "Corps page deux");
        sdm.getChildren().add(imgP0);
        sdm.getChildren().add(imgP1);
        sdm.getChildren().add(titleP0);
        sdm.getChildren().add(bodyP1);

        ReadingOrderNormalizer.normalize(sdm, pgm);

        List<String> order = describe(sdm);
        assertEquals(List.of("IMG@0", "Titre page une", "IMG@1", "Corps page deux"), order);
    }

    @Test
    void boxlessBlockTravelsWithPrecedingNeighbour() {
        SdmDocument sdm = new SdmDocument();
        PgmModel pgm = new PgmModel();
        pgm.addPage(new PgmPage(0, 595, PAGE_H, 0));
        pgm.addPage(new PgmPage(1, 595, PAGE_H, 0));

        Paragraph bodyP1 = para("b1", pgm, 1, 500, "Corps page deux");
        Paragraph titleP0 = para("t0", pgm, 0, 600, "Titre page une");
        Paragraph decoP0 = para(null, pgm, -1, 0, "deco"); // box-less, follows title
        // scrambled input: page-1 body, then page-0 title, then its box-less deco
        sdm.getChildren().add(bodyP1);
        sdm.getChildren().add(titleP0);
        sdm.getChildren().add(decoP0);

        ReadingOrderNormalizer.normalize(sdm, pgm);

        List<String> order = describe(sdm);
        // title (p0) sorts before body (p1); the box-less deco stays right after
        // the title it trailed, not marooned on page 1.
        assertEquals(List.of("Titre page une", "deco", "Corps page deux"), order);
    }

    private static List<String> describe(SdmDocument sdm) {
        List<String> out = new ArrayList<>();
        for (SdmBlock b : sdm.getChildren()) {
            if (b instanceof Figure) {
                out.add("IMG@" + b.getAttributes().get("pg"));
            } else {
                out.add(((Paragraph) b).getText().trim());
            }
        }
        return out;
    }

    private static Figure figure(String id, PgmModel pgm, int page, double top) {
        Figure f = new Figure(null);
        f.setId(id);
        f.getAttributes().put("pg", page);
        box(pgm, id, page, top);
        return f;
    }

    private static Paragraph para(String id, PgmModel pgm, int page, double top, String text) {
        Paragraph p = new Paragraph();
        if (id != null) {
            p.setId(id);
            box(pgm, id, page, top);
        }
        p.getInline().add(new Run(text, new TextStyle()));
        return p;
    }

    private static void box(PgmModel pgm, String id, int page, double top) {
        PgmRect rect = new PgmRect(72, top - 12, 200, 12);
        PgmBox b = new PgmBox(id, page, rect, 0, PgmBoxKind.TEXT, null);
        pgm.getPage(page).getBoxes().add(b);
        pgm.indexBox(b);
    }
}
