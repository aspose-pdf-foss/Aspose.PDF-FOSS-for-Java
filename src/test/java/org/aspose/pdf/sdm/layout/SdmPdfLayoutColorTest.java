package org.aspose.pdf.sdm.layout;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aspose.pdf.Operator;
import org.aspose.pdf.Page;
import org.aspose.pdf.operators.SetRGBColor;
import org.aspose.pdf.operators.ShowText;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.TextStyle;
import org.junit.jupiter.api.Test;

/**
 * Regression: a coloured run (e.g. a red heading) must NOT bleed into the black
 * text that follows. The fill colour is persistent graphics state, so every text
 * draw sets its own colour — black text explicitly sets black.
 */
public class SdmPdfLayoutColorTest {

    @Test
    public void blackTextAfterRedHeadingIsBlack() throws Exception {
        TextStyle red = new TextStyle();
        red.setColor(0xCB3100);
        Heading h = new Heading(1);
        h.getInline().add(new Run("RedHead", red));
        Paragraph p = new Paragraph();
        p.getInline().add(new Run("BlackBody", null));

        SdmDocument sdm = new SdmDocument();
        sdm.getChildren().add(h);
        sdm.getChildren().add(p);
        SdmPdfLayout.Result r = new SdmPdfLayout().render(sdm, PageSetup.letter());

        Page page = r.getDocument().getPages().get(1);
        double[] cur = {0, 0, 0};
        Boolean bodyIsBlack = null;
        for (Operator op : page.getContents()) {
            if (op instanceof SetRGBColor) {
                SetRGBColor c = (SetRGBColor) op;
                cur = new double[]{c.getR(), c.getG(), c.getB()};
            } else if (op instanceof ShowText) {
                String t = ((ShowText) op).getText();
                if (t != null && t.contains("BlackBody")) {
                    bodyIsBlack = cur[0] == 0.0 && cur[1] == 0.0 && cur[2] == 0.0;
                }
            }
        }
        assertNotNull(bodyIsBlack, "body text was drawn");
        assertTrue(bodyIsBlack, "black body text must not inherit the red heading colour");
    }
}
