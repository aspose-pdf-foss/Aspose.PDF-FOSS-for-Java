package org.aspose.pdf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;
import org.aspose.pdf.LoadFormat;
import org.aspose.pdf.LoadOptions;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;

/**
 * The uniform {@link Document#Document(java.io.InputStream, LoadOptions)} /
 * {@link Document#Document(String, LoadOptions)} entry point selects the source
 * format from the runtime type of the {@link LoadOptions} argument (Aspose.PDF
 * pattern), mirroring {@link org.aspose.pdf.SaveOptions} on save.
 */
public class LoadOptionsDispatchTest {

    private static String allText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            TextAbsorber ta = new TextAbsorber();
            ta.visit(doc.getPages().get(i));
            if (ta.getText() != null) sb.append(ta.getText()).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    @Test
    public void loadFormatReportedByOptionType() {
        assertEquals(LoadFormat.HTML, new HtmlLoadOptions().getLoadFormat());
        LoadOptions asBase = new HtmlLoadOptions();
        assertEquals(LoadFormat.HTML, asBase.getLoadFormat());
    }

    /** A stream + an HtmlLoadOptions typed as the base LoadOptions reads HTML. */
    @Test
    public void streamDispatchByRuntimeType() throws Exception {
        String html = "<html><body><h1>Via LoadOptions</h1><p>Body text here.</p></body></html>";
        LoadOptions opts = new HtmlLoadOptions();
        try (Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)), opts)) {
            String text = allText(doc);
            assertTrue(text.contains("Via LoadOptions"), "heading present: " + text);
            assertTrue(text.contains("Body text here."), "body present: " + text);
        }
    }

    /** An unsupported (or null) LoadOptions type fails with a clear message, not silently. */
    @Test
    public void unsupportedLoadOptionsRejected() {
        LoadOptions nullOpts = null;
        assertThrows(IllegalArgumentException.class,
                () -> new Document(new ByteArrayInputStream(new byte[0]), nullOpts));
    }
}
