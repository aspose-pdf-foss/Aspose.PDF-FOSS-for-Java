package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.ThematicBreak;
import org.junit.jupiter.api.Test;

/**
 * Advanced CSS the EDGAR/iShares reports rely on: {@code @media} unwrapping,
 * descendant combinators, {@code rgba()} colours — all of which must reach the
 * SDM as block/cell backgrounds.
 */
public class CssAdvancedSelectorTest {

    private static SdmDocument read(String html) {
        return new HtmlSdmReader().read(html, null, null);
    }

    private static ThematicBreak findRule(java.util.List<SdmBlock> blocks) {
        for (SdmBlock b : blocks) {
            if (b instanceof ThematicBreak) return (ThematicBreak) b;
            if (b instanceof org.aspose.pdf.sdm.Container) {
                ThematicBreak r = findRule(((org.aspose.pdf.sdm.Container) b).getChildren());
                if (r != null) return r;
            }
        }
        return null;
    }

    @Test
    public void plainHrRuleNoMedia() {
        String html = "<html><head><style>"
                + "hr.blue_rule { background-color: rgba(0,169,224,1) }"
                + "</style></head><body>"
                + "<div class=\"Header_rule\"><hr class=\"blue_rule\"></div></body></html>";
        ThematicBreak hr = findRule(read(html).getChildren());
        assertNotNull(hr, "hr present");
        assertNotNull(hr.getStyle(), "hr carries a style (no @media)");
        assertEquals(0x00A9E0, hr.getStyle().getBackground() & 0xFFFFFF, "blue applied");
    }

    @Test
    public void mediaPrintRuleWithRgbaAppliesToHr() {
        String html = "<html><head><style>"
                + "@media screen { hr.blue_rule { background-color: rgba(255,0,0,1) } }"
                + "@media print { hr.blue_rule { background-color: rgba(0,169,224,1) } }"
                + "</style></head><body>"
                + "<div class=\"Header_rule\"><hr class=\"blue_rule\"></div></body></html>";
        ThematicBreak hr = findRule(read(html).getChildren());
        assertNotNull(hr, "hr present");
        assertNotNull(hr.getStyle(), "hr carries a style");
        // print media wins (0,169,224) — not the screen red.
        assertEquals(0x00A9E0, hr.getStyle().getBackground() & 0xFFFFFF,
                "blue print background applied via @media + rgba");
    }

    @Test
    public void descendantComboAppliesCellBackground() {
        String html = "<html><head><style>"
                + "thead td { background-color: rgba(118,188,33,1) }"
                + "</style></head><body>"
                + "<table><thead><tr><td>Head</td></tr></thead>"
                + "<tbody><tr><td>Body</td></tr></tbody></table></body></html>";
        Table t = firstTable(read(html).getChildren());
        assertNotNull(t, "table present");
        int headBg = t.getRows().get(0).getCells().get(0).getStyle() == null ? 0
                : t.getRows().get(0).getCells().get(0).getStyle().getBackground();
        assertEquals(0x76BC21, headBg & 0xFFFFFF, "green header via descendant combinator");
        // body cell (not under thead) must NOT get the tint.
        TableCell bodyCell = t.getRows().get(1).getCells().get(0);
        int bodyBg = bodyCell.getStyle() == null ? 0 : bodyCell.getStyle().getBackground();
        assertEquals(0, bodyBg & 0xFFFFFF, "body cell untinted");
    }

    private static Table firstTable(java.util.List<SdmBlock> blocks) {
        for (SdmBlock b : blocks) {
            if (b instanceof Table) return (Table) b;
            if (b instanceof org.aspose.pdf.sdm.Container) {
                Table t = firstTable(((org.aspose.pdf.sdm.Container) b).getChildren());
                if (t != null) return t;
            }
        }
        return null;
    }
}
