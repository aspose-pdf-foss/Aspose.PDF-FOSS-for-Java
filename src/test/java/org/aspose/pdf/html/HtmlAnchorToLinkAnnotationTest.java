package org.aspose.pdf.html;

import org.aspose.pdf.Document;
import org.aspose.pdf.GoToURIAction;
import org.aspose.pdf.HtmlLoadOptions;
import org.aspose.pdf.PdfAction;
import org.aspose.pdf.UriAction;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.annotations.LinkAnnotation;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BUG-059 — HTML {@code <a href="…">} elements must produce {@link LinkAnnotation}
 * objects on the rendered page with a {@link GoToURIAction} carrying the href.
 */
class HtmlAnchorToLinkAnnotationTest {

    @Test
    void anchor_creates_LinkAnnotation_with_GoToURIAction() throws Exception {
        String html = "<html><body><a href=\"http://aspose.com\">Click here</a></body></html>";
        try (Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                new HtmlLoadOptions(""))) {
            assertTrue(doc.getPages().getCount() >= 1);
            LinkAnnotation link = findFirstLink(doc);
            assertNotNull(link, "anchor <a href> must produce a LinkAnnotation");
            PdfAction action = link.getAction();
            assertNotNull(action);
            // Action is a URI / GoToURIAction (both implementations share /S=URI).
            assertEquals("URI", action.getType());
            assertTrue(action instanceof UriAction);
            assertEquals("http://aspose.com", ((UriAction) action).getUri());
        }
    }

    @Test
    void anchorWithoutHref_doesNotCreateAnnotation() throws Exception {
        String html = "<html><body><a>no href</a></body></html>";
        try (Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                new HtmlLoadOptions(""))) {
            LinkAnnotation link = findFirstLink(doc);
            assertNull(link, "anchor without href must not create a LinkAnnotation");
        }
    }

    @Test
    void multipleAnchors_eachProducesAnnotation() throws Exception {
        String html = "<html><body>"
                + "<p><a href=\"http://one.example.com\">one</a></p>"
                + "<p><a href=\"http://two.example.com\">two</a></p>"
                + "</body></html>";
        try (Document doc = new Document(
                new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)),
                new HtmlLoadOptions(""))) {
            int linkCount = 0;
            for (int p = 1; p <= doc.getPages().getCount(); p++) {
                AnnotationCollection ann = doc.getPages().get(p).getAnnotations();
                for (int i = 1; i <= ann.size(); i++) {
                    if (ann.get(i) instanceof LinkAnnotation) linkCount++;
                }
            }
            assertEquals(2, linkCount, "two <a href> blocks → two LinkAnnotations");
        }
    }

    private static LinkAnnotation findFirstLink(Document doc) throws Exception {
        for (int p = 1; p <= doc.getPages().getCount(); p++) {
            AnnotationCollection ann = doc.getPages().get(p).getAnnotations();
            for (int i = 1; i <= ann.size(); i++) {
                Annotation a = ann.get(i);
                if (a instanceof LinkAnnotation) return (LinkAnnotation) a;
            }
        }
        return null;
    }
}
