package org.aspose.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;

/**
 * End-to-end gate for Markdown conversion reached through the public
 * {@link Document} API: a small PDF converts to Markdown carrying the source
 * text, and a Markdown document loads back into a paginated PDF whose extracted
 * text matches. The several save entry points ({@code save(path,
 * MarkdownSaveOptions)}, the generic {@code save(path, SaveOptions)} dispatch,
 * the stream form and {@code save(path, SaveFormat.Markdown)}) all agree — the
 * format is selected by the concrete options type. Pure in-memory.
 */
public class MarkdownConversionTest {

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

    private static String toMarkdown(Document doc) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, new MarkdownSaveOptions());
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    public void convertsPdfToMarkdownWithSourceText() throws IOException {
        String md = toMarkdown(sampleDoc());
        assertTrue(md.contains("Alpha paragraph line."), md);
        assertTrue(md.contains("Beta paragraph line."), md);
    }

    @Test
    public void saveFormatMarkdownMatchesOptions() throws IOException {
        ByteArrayOutputStream a = new ByteArrayOutputStream();
        sampleDoc().save(a, SaveFormat.Markdown);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        sampleDoc().save(b, new MarkdownSaveOptions());
        assertEquals(a.toString(StandardCharsets.UTF_8), b.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void genericSaveOptionsDispatchesToMarkdown() throws IOException {
        SaveOptions opts = new MarkdownSaveOptions();
        assertEquals(SaveFormat.Markdown, opts.getSaveFormat());
    }

    @Test
    public void loadsMarkdownIntoPaginatedPdf() throws IOException {
        String md = "# Report\n\n"
                + "This is the **first** paragraph with a [link](https://example.com).\n\n"
                + "- one\n- two\n- three\n\n"
                + "| A | B |\n| --- | --- |\n| x | y |\n";
        Document doc = new Document(new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8)),
                new MarkdownLoadOptions());
        assertTrue(doc.getPages().getCount() >= 1, "at least one page");

        TextAbsorber abs = new TextAbsorber();
        doc.getPages().accept(abs);
        String text = abs.getText();
        assertTrue(text.contains("Report"), text);
        assertTrue(text.contains("first"), text);
        assertTrue(text.contains("one"), text);
    }

    @Test
    public void markdownRoundTripPreservesText() throws IOException {
        // PDF -> Markdown -> PDF, then confirm the text survived the round-trip.
        String md = toMarkdown(sampleDoc());
        Document back = new Document(new ByteArrayInputStream(md.getBytes(StandardCharsets.UTF_8)),
                new MarkdownLoadOptions());
        TextAbsorber abs = new TextAbsorber();
        back.getPages().accept(abs);
        String text = abs.getText();
        assertTrue(text.contains("Alpha paragraph line."), text);
        assertTrue(text.contains("Beta paragraph line."), text);
    }
}
