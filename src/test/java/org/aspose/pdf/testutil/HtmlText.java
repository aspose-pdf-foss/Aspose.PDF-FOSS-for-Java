package org.aspose.pdf.testutil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;

/**
 * Test shorthand for "convert a document to HTML markup in memory".
 *
 * <p>The public API deliberately exposes only the uniform
 * {@code Document.save(..., SaveOptions)} entry points (the concrete options
 * type selects the format — no per-format methods), so tests that assert on
 * the HTML text route through the stream form via this helper.</p>
 */
public final class HtmlText {

    private HtmlText() {
        // static helper
    }

    /**
     * Converts the document to HTML and returns the markup.
     *
     * @param doc     the open document
     * @param options HTML save options; {@code null} means defaults
     * @return the HTML text
     * @throws IOException if conversion fails
     */
    public static String of(Document doc, HtmlSaveOptions options) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.save(bos, options == null ? new HtmlSaveOptions() : options);
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }
}
