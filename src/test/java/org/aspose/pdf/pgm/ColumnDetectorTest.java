package org.aspose.pdf.pgm;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PART 6 gate (synthetic): pages generated in-test — 1-column, 2-column,
 * 3-column, and mixed (full-width heading + 2 columns) — classify exactly as
 * expected.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ColumnDetectorTest {

    @TempDir
    static Path tempDir;

    private PgmModel pgm;

    @BeforeAll
    void buildAndDetect() throws IOException {
        Document doc = new Document();

        // Page 1 — single column: 14 full-width lines.
        Page p1 = doc.getPages().add();
        for (int i = 0; i < 14; i++) {
            addLine(p1, "Single column body line " + i + " with enough words to be wide",
                    72, 720 - i * 18);
        }

        // Page 2 — clean 2 columns: left 72.., right 330.. (gutter ~40pt).
        Page p2 = doc.getPages().add();
        for (int i = 0; i < 12; i++) {
            addLine(p2, "Left column line " + i + " text", 72, 720 - i * 18);
            addLine(p2, "Right column line " + i + " text", 330, 720 - i * 18);
        }

        // Page 3 — clean 3 columns at 50 / 240 / 430.
        Page p3 = doc.getPages().add();
        for (int i = 0; i < 12; i++) {
            addLine(p3, "Col one " + i + " text", 50, 720 - i * 18);
            addLine(p3, "Col two " + i + " text", 240, 720 - i * 18);
            addLine(p3, "Col three " + i + " txt", 430, 720 - i * 18);
        }

        // Page 4 — mixed: full-width heading over 2 columns.
        Page p4 = doc.getPages().add();
        addLine(p4, "Annual Report Overview Section With A Very Long Full Width Title Line",
                72, 750);
        for (int i = 0; i < 12; i++) {
            addLine(p4, "Left column line " + i + " text", 72, 700 - i * 18);
            addLine(p4, "Right column line " + i + " text", 330, 700 - i * 18);
        }

        Path path = tempDir.resolve("columns-fixture.pdf");
        doc.save(path.toString());
        Document reopened = new Document(path.toString());
        pgm = new PdfSdmReader().read(reopened, Files.readAllBytes(path)).getPgm();
        for (PgmPage page : pgm.getPages()) {
            ColumnDetector.detect(page);
        }
    }

    private static void addLine(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    /** Page of full-width lines is SINGLE_COLUMN. */
    @Test
    public void singleColumnPage() {
        ColumnStructure cs = pgm.getPage(0).getColumnStructure();
        assertEquals(ColumnStructure.Type.SINGLE_COLUMN, cs.getType());
        assertEquals(1, cs.getColumnCount());
    }

    /** Two clean columns detect as MULTI_CLEAN(2). */
    @Test
    public void twoCleanColumns() {
        ColumnStructure cs = pgm.getPage(1).getColumnStructure();
        assertEquals(ColumnStructure.Type.MULTI_CLEAN, cs.getType());
        assertEquals(2, cs.getColumnCount());
    }

    /** Three clean columns detect as MULTI_CLEAN(3). */
    @Test
    public void threeCleanColumns() {
        ColumnStructure cs = pgm.getPage(2).getColumnStructure();
        assertEquals(ColumnStructure.Type.MULTI_CLEAN, cs.getType());
        assertEquals(3, cs.getColumnCount());
    }

    /** A full-width heading over two columns detects as MULTI_MIXED. */
    @Test
    public void headingOverColumnsIsMixed() {
        ColumnStructure cs = pgm.getPage(3).getColumnStructure();
        assertEquals(ColumnStructure.Type.MULTI_MIXED, cs.getType());
        assertEquals(2, cs.getColumnCount());
    }
}
