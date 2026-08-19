package org.aspose.pdf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Loading a PDF straight from an HTML file path via {@code new Document(path, HtmlLoadOptions)}
 * (no manual stream reading), the file-based counterpart of
 * the stream-constructor markup form.
 */
public class FromHtmlFileTest {

    private static String allText(Document doc) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= doc.getPages().getCount(); i++) {
            TextAbsorber ta = new TextAbsorber();
            ta.visit(doc.getPages().get(i));
            if (ta.getText() != null) sb.append(ta.getText()).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /** A path is read and converted; a path string is NOT rendered as literal text. */
    @Test
    public void loadsFileByPath(@TempDir File dir) throws Exception {
        File html = new File(dir, "doc.html");
        Files.write(html.toPath(),
                "<html><body><h1>Report Title</h1><p>Body paragraph here.</p></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        try (Document doc = new Document(html.getAbsolutePath(), (HtmlLoadOptions) null)) {
            String text = allText(doc);
            assertTrue(text.contains("Report Title"), "heading present: " + text);
            assertTrue(text.contains("Body paragraph here."), "body present: " + text);
            // Regression: the path itself must not leak into the output as text.
            assertTrue(!text.contains(".html"), "path string must not be rendered: " + text);
        }
    }

    /** The base path defaults to the HTML file's directory, so a relative image loads. */
    @Test
    public void basePathDefaultsToFileDirectory(@TempDir File dir) throws Exception {
        File assets = new File(dir, "img");
        assets.mkdirs();
        ImageIO.write(new BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB), "png",
                new File(assets, "logo.png"));
        File html = new File(dir, "page.html");
        Files.write(html.toPath(),
                "<html><body><p>See logo</p><img src=\"img/logo.png\"></body></html>"
                        .getBytes(StandardCharsets.UTF_8));

        // No setBasePath — the HTML-file constructor must default it to the file's own folder.
        try (Document doc = new Document(html.getAbsolutePath(), (HtmlLoadOptions) null)) {
            assertTrue(doc.getPages().getCount() >= 1, "at least one page");
            // The relative image resolved and was embedded as an XObject.
            boolean hasImage = doc.getPages().get(1).getResources().getImages().getCount() > 0;
            assertTrue(hasImage, "relative <img> resolved via auto base-path and embedded");
        }
    }

    /** An explicit page geometry is honoured. */
    @Test
    public void honoursExplicitOptions(@TempDir File dir) throws Exception {
        File html = new File(dir, "g.html");
        Files.write(html.toPath(),
                "<html><body><p>geometry</p></body></html>".getBytes(StandardCharsets.UTF_8));
        HtmlLoadOptions opt = new HtmlLoadOptions();
        opt.setPageInfo(new org.aspose.pdf.PageInfo(612, 792));
        try (Document doc = new Document(html.getAbsolutePath(), opt)) {
            assertEquals(612.0, doc.getPages().get(1).getRect().getWidth(), 1.0);
            assertEquals(792.0, doc.getPages().get(1).getRect().getHeight(), 1.0);
        }
    }
}
