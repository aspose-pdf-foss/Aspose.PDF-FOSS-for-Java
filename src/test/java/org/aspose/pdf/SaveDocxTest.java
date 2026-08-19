package org.aspose.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end gate for the PDF&rarr;{@code .docx} converter reached through the
 * public {@link Document} API: a small PDF converts to a WordprocessingML
 * package whose {@code document.xml} carries the source text, and the several
 * entry points ({@code save(path, DocSaveOptions)}, the generic
 * {@code save(path, SaveOptions)} dispatch, the stream form, and
 * {@code save(path, SaveFormat.DocX)}) all agree — the format is selected by
 * the concrete options type, there is no per-format method.
 */
public class SaveDocxTest {

    @TempDir
    Path tempDir;

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    private static Document sampleDoc() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Alpha paragraph line.", 72, 700);
        line(page, "Beta paragraph line.", 72, 660);
        return doc;
    }

    /** Extracts the concatenated {@code <w:t>} text of word/document.xml from a .docx byte[]. */
    private static String documentText(byte[] docx) throws IOException {
        String xml = null;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(docx))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.getName().equals("word/document.xml")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    xml = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                    break;
                }
            }
        }
        if (xml == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = Pattern.compile("<w:t[^>]*>(.*?)</w:t>", Pattern.DOTALL).matcher(xml);
        while (m.find()) {
            sb.append(m.group(1)).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static boolean isZip(byte[] b) {
        return b.length > 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
    }

    @Test
    public void convertsPdfToDocxWithSourceText() throws IOException {
        Document doc = sampleDoc();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, new DocSaveOptions());
        byte[] docx = bos.toByteArray();

        assertTrue(isZip(docx), "output is a ZIP/OOXML package");
        String text = documentText(docx);
        assertTrue(text.contains("Alpha paragraph line."), "first line present: " + text);
        assertTrue(text.contains("Beta paragraph line."), "second line present: " + text);
    }

    @Test
    public void textMatchesExtractorText() throws IOException {
        Document doc = sampleDoc();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, new DocSaveOptions());

        TextAbsorber ta = new TextAbsorber();
        ta.visit(doc.getPages().get(1));
        String pdfText = ta.getText().replaceAll("\\s+", " ").trim();
        assertTrue(documentText(bos.toByteArray()).contains(pdfText.split(" ")[0]),
                "docx text carries the PDF text");
    }

    @Test
    public void fileAndStreamEntryPointsAgree() throws IOException {
        Document doc = sampleDoc();

        Path viaSave = tempDir.resolve("via-save.docx");
        Path viaGeneric = tempDir.resolve("via-generic.docx");
        Path viaFormat = tempDir.resolve("via-format.docx");
        doc.save(viaSave.toString(), new DocSaveOptions());
        // The generic SaveOptions-typed entry dispatches on the runtime type.
        doc.save(viaGeneric.toString(), (SaveOptions) new DocSaveOptions());
        doc.save(viaFormat.toString(), SaveFormat.DocX);

        byte[] a = Files.readAllBytes(viaSave);
        byte[] b = Files.readAllBytes(viaGeneric);
        byte[] c = Files.readAllBytes(viaFormat);
        assertTrue(isZip(a) && isZip(b) && isZip(c), "all forms wrote ZIP packages");
        // The writer is deterministic, so the three routes produce identical bytes.
        assertArrayEquals(a, b, "save(path,opts) == save(path,(SaveOptions)opts)");
        assertArrayEquals(a, c, "save(path,opts) == save(path, SaveFormat.DocX)");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, new DocSaveOptions());
        assertArrayEquals(a, bos.toByteArray(), "file and stream forms agree");
    }
}
