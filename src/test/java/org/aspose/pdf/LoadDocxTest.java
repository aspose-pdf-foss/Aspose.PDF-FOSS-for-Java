package org.aspose.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end DOCX &rarr; PDF through the uniform constructor
 * ({@code new Document(..., DocLoadOptions)} — the concrete LoadOptions type
 * selects the source format): a document saved as {@code .docx} loads back into
 * a paginated PDF whose extracted text carries the content, and the page size
 * recorded in {@code sectPr} is honoured.
 */
public class LoadDocxTest {

    @TempDir
    Path tempDir;

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    private static byte[] sampleDocx() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Alpha docx line.", 72, 700);
        line(page, "Beta docx line.", 72, 660);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, new DocSaveOptions());
        return bos.toByteArray();
    }

    private static String allText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            TextAbsorber ta = new TextAbsorber();
            ta.visit(doc.getPages().get(i));
            if (ta.getText() != null) {
                sb.append(ta.getText()).append(' ');
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    @Test
    public void docxLoadsIntoTextBearingPdf() throws Exception {
        byte[] docx = sampleDocx();
        try (Document pdf = new Document(new ByteArrayInputStream(docx), new DocLoadOptions())) {
            assertTrue(pdf.getPages().getCount() >= 1, "at least one page");
            String text = allText(pdf);
            assertTrue(text.contains("Alpha docx line."), "first line: " + text);
            assertTrue(text.contains("Beta docx line."), "second line: " + text);
        }
    }

    @Test
    public void fileAndStreamFormsAgree() throws Exception {
        byte[] docx = sampleDocx();
        Path file = tempDir.resolve("sample.docx");
        Files.write(file, docx);

        String viaFile;
        try (Document pdf = new Document(file.toString(), new DocLoadOptions())) {
            viaFile = allText(pdf);
        }
        String viaStream;
        try (Document pdf = new Document(new ByteArrayInputStream(docx), new DocLoadOptions())) {
            viaStream = allText(pdf);
        }
        assertEquals(viaFile, viaStream, "file and stream forms agree");
    }

    @Test
    public void sectPrPageSizeHonoured() throws Exception {
        // Our writer records the source page size (A4 595x842) in sectPr; the
        // loader must reproduce it instead of defaulting to Letter.
        byte[] docx = sampleDocx();
        try (Document pdf = new Document(new ByteArrayInputStream(docx), new DocLoadOptions())) {
            Rectangle rect = pdf.getPages().get(1).getRect();
            assertEquals(595, rect.getWidth(), 3, "A4 width from sectPr");
            assertEquals(842, rect.getHeight(), 3, "A4 height from sectPr");
        }
    }

    @Test
    public void explicitPageInfoOverridesSectPr() throws Exception {
        byte[] docx = sampleDocx();
        DocLoadOptions opt = new DocLoadOptions();
        opt.setPageInfo(new PageInfo(612, 792)); // Letter
        try (Document pdf = new Document(new ByteArrayInputStream(docx), opt)) {
            Rectangle rect = pdf.getPages().get(1).getRect();
            assertEquals(612, rect.getWidth(), 1, "explicit width wins");
            assertEquals(792, rect.getHeight(), 1, "explicit height wins");
        }
    }

    @Test
    public void roundTripSurvivesSaveAndReopen() throws Exception {
        byte[] docx = sampleDocx();
        ByteArrayOutputStream pdfBytes = new ByteArrayOutputStream();
        try (Document pdf = new Document(new ByteArrayInputStream(docx), new DocLoadOptions())) {
            pdf.save(pdfBytes);
        }
        try (Document reopened = new Document(new ByteArrayInputStream(pdfBytes.toByteArray()))) {
            String text = allText(reopened);
            assertTrue(text.contains("Alpha docx line."), "text survives save/reopen: " + text);
        }
    }
}
