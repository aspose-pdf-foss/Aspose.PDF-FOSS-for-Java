package org.aspose.pdf.sdm.html;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.testutil.HtmlText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * IR Stage 3 PART 1 gate: both HTML output modes ship under ONE public entry —
 * {@code Document.save(..., HtmlSaveOptions)} routes {@link HtmlOutputMode#STRUCTURAL}
 * through the SDM pipeline and {@link HtmlOutputMode#FIXED_LAYOUT} (default) through
 * the pre-existing {@code PdfToHtmlConverter} (consolidation, no behaviour change).
 */
public class SaveHtmlModesTest {

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

    private static String bodyText(String html) throws IOException {
        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        return dom.getElementsByTagName("body").item(0).getTextContent()
                .replaceAll("\\s+", " ").trim();
    }

    /** STRUCTURAL mode produces semantic paragraphs whose text equals the extractor text. */
    @Test
    public void structuralModeProducesSemanticParagraphs() throws IOException {
        Document doc = sampleDoc();
        HtmlSaveOptions options = new HtmlSaveOptions();
        options.setOutputMode(HtmlOutputMode.STRUCTURAL);
        String html = HtmlText.of(doc, options);

        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        NodeList ps = dom.getElementsByTagName("p");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < ps.getLength(); i++) {
            texts.add(ps.item(i).getTextContent().replaceAll("\\s+", " ").trim());
        }
        assertTrue(texts.contains("Alpha paragraph line."), "structural <p> for first line: " + texts);
        assertTrue(texts.contains("Beta paragraph line."), "structural <p> for second line: " + texts);

        TextAbsorber ta = new TextAbsorber();
        ta.visit(doc.getPages().get(1));
        String pdfText = ta.getText().replaceAll("\\s+", " ").trim();
        assertEquals(pdfText, bodyText(html), "[TXT] structural HTML text == PDF text");
    }

    /** Default (FIXED_LAYOUT) keeps the classic converter behaviour; both entries agree. */
    @Test
    public void fixedLayoutModeUnchanged() throws IOException {
        Document doc = sampleDoc();
        String fixedDefault = HtmlText.of(doc, new HtmlSaveOptions());
        HtmlSaveOptions explicit = new HtmlSaveOptions();
        explicit.setOutputMode(HtmlOutputMode.FIXED_LAYOUT);
        String fixedExplicit = HtmlText.of(doc, explicit);
        assertEquals(fixedDefault, fixedExplicit, "default mode IS fixed layout");
        assertTrue(fixedDefault.contains("Alpha paragraph line."), "fixed layout keeps the text");

        HtmlSaveOptions structural = new HtmlSaveOptions();
        structural.setOutputMode(HtmlOutputMode.STRUCTURAL);
        assertNotEquals(fixedDefault, HtmlText.of(doc, structural), "two genuinely different outputs");
    }

    /** The file and stream forms of save(..., HtmlSaveOptions) write the same routed output. */
    @Test
    public void fileEntryPointsRouteIdentically() throws IOException {
        Document doc = sampleDoc();
        HtmlSaveOptions options = new HtmlSaveOptions();
        options.setOutputMode(HtmlOutputMode.STRUCTURAL);

        Path viaSave = tempDir.resolve("via-save.html");
        doc.save(viaSave.toString(), options);
        String a = Files.readString(viaSave);

        // Stream form (what HtmlText.of wraps) agrees with the file form, and the
        // generic SaveOptions-typed entry routes to the same output.
        assertEquals(HtmlText.of(doc, options), a, "stream and file forms agree");
        Path viaGeneric = tempDir.resolve("via-generic.html");
        doc.save(viaGeneric.toString(), (org.aspose.pdf.SaveOptions) options);
        assertEquals(a, Files.readString(viaGeneric), "generic SaveOptions entry routes identically");
    }
}
