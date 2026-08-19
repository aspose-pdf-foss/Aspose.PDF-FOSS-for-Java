package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;

/**
 * Gate for {@link RunningHeaderFooterEnricher}: a synthetic 6-page document with
 * a running header, a running footer whose only change is the page number, an
 * orphan version number with no geometry, and one genuine body paragraph per
 * page. The furniture must vanish; the body must survive.
 */
class RunningHeaderFooterEnricherTest {

    private static final double PAGE_W = 595;
    private static final double PAGE_H = 842;

    @Test
    void dropsRunningHeadersFootersAndOrphanNumbersKeepsBody() {
        int pages = 6;
        SdmDocument sdm = new SdmDocument();
        PgmModel pgm = new PgmModel();
        for (int p = 0; p < pages; p++) {
            pgm.addPage(new PgmPage(p, PAGE_W, PAGE_H, 0));
        }

        int guid = 0;
        for (int p = 0; p < pages; p++) {
            // Header near the very top (PDF Y grows upward → high Y).
            guid = addBlock(sdm, pgm, guid, p, "ITESOFT.FreeMind Manuel", 800, 812, true);
            // Body paragraph in the middle — must be kept.
            guid = addBlock(sdm, pgm, guid, p, "Corps du texte page " + (p + 1)
                    + " avec un contenu unique et suffisamment long.", 400, 412, true);
            // Footer near the bottom, page number is the only per-page change.
            guid = addBlock(sdm, pgm, guid, p, "Manuel Utilisateur 2.2.2.2 " + (p + 1)
                    + "/6 15/11/07", 20, 32, true);
            // Orphan version number: a stray with NO page geometry.
            guid = addBlock(sdm, pgm, guid, p, "2.2.2", 0, 0, false);
        }

        int before = sdm.getChildren().size();
        assertEquals(pages * 4, before);

        int removed = RunningHeaderFooterEnricher.enrich(sdm, pgm);
        // header (6) + footer (6) + orphan version (6) = 18 furniture blocks.
        assertEquals(18, removed);

        List<String> kept = new ArrayList<>();
        for (SdmBlock b : sdm.getChildren()) {
            kept.add(((Paragraph) b).getText().trim());
        }
        assertEquals(pages, kept.size());
        for (String t : kept) {
            assertTrue(t.startsWith("Corps du texte"), "kept unexpected block: " + t);
        }
        assertFalse(kept.contains("2.2.2"));
    }

    @Test
    void keepsContentWhenNoRepetition() {
        // A 4-page doc where the top-band lines are all DIFFERENT — not furniture.
        SdmDocument sdm = new SdmDocument();
        PgmModel pgm = new PgmModel();
        for (int p = 0; p < 4; p++) {
            pgm.addPage(new PgmPage(p, PAGE_W, PAGE_H, 0));
        }
        String[] titles = {
            "Introduction generale au produit",
            "Architecture logicielle du moteur",
            "Guide pratique de deploiement",
            "Annexe glossaire des termes",
        };
        int guid = 0;
        for (int p = 0; p < 4; p++) {
            guid = addBlock(sdm, pgm, guid, p, titles[p], 800, 812, true);
        }
        int removed = RunningHeaderFooterEnricher.enrich(sdm, pgm);
        assertEquals(0, removed);
        assertEquals(4, sdm.getChildren().size());
    }

    private static int addBlock(SdmDocument sdm, PgmModel pgm, int guid, int page,
                                String text, double y, double top, boolean withBox) {
        String id = String.format("00000000-0000-0000-0000-%012d", guid);
        Paragraph para = new Paragraph();
        para.setId(id);
        para.getInline().add(new Run(text, new TextStyle()));
        sdm.getChildren().add(para);
        if (withBox) {
            PgmRect rect = new PgmRect(72, y, 200, top - y);
            PgmBox box = new PgmBox(id, page, rect, 0, PgmBoxKind.TEXT, null);
            pgm.getPage(page).getBoxes().add(box);
            pgm.indexBox(box);
        }
        return guid + 1;
    }
}
