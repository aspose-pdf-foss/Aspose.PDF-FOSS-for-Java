package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.FileAttachmentAnnotation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 / Bug J — {@link FileAttachmentAnnotation#setIcon(String)} must
 * write through to the {@code /Name} entry per ISO 32000-1:2008 §12.5.6.15
 * Table 184 so callers no longer have to reach into {@code getCOSDictionary()}
 * directly.
 */
class FileAttachmentIconTest {

    private FileAttachmentAnnotation roundTrip(String icon, Path tmp) throws IOException {
        Path out = tmp.resolve("fa-" + (icon == null ? "null" : icon) + ".pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            FileAttachmentAnnotation fa =
                    new FileAttachmentAnnotation(page, new Rectangle(100, 100, 120, 120));
            // Pre-seed a non-null value so null-clears are observable.
            fa.setIcon("Paperclip");
            fa.setIcon(icon);
            page.getAnnotations().add(fa);
            doc.save(out.toString());
        }
        Document reopened = new Document(out.toString());
        Annotation a = reopened.getPages().get(1).getAnnotations().get(1);
        assertTrue(a instanceof FileAttachmentAnnotation,
                "annotation must be reconstructed as FileAttachmentAnnotation, got " + a.getClass().getSimpleName());
        return (FileAttachmentAnnotation) a;
    }

    @Test
    @DisplayName("setIcon(\"Graph\") round-trips through save+reopen")
    void setIcon_graph_roundTrips(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip("Graph", tmp);
        assertEquals("Graph", fa.getIcon());
    }

    @Test
    @DisplayName("setIcon(\"PushPin\") round-trips through save+reopen")
    void setIcon_pushPin_roundTrips(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip("PushPin", tmp);
        assertEquals("PushPin", fa.getIcon());
    }

    @Test
    @DisplayName("setIcon(\"Paperclip\") round-trips through save+reopen")
    void setIcon_paperclip_roundTrips(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip("Paperclip", tmp);
        assertEquals("Paperclip", fa.getIcon());
    }

    @Test
    @DisplayName("setIcon(\"Tag\") round-trips through save+reopen")
    void setIcon_tag_roundTrips(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip("Tag", tmp);
        assertEquals("Tag", fa.getIcon());
    }

    @Test
    @DisplayName("setIcon(\"CustomIcon\") is preserved verbatim on disk")
    void setIcon_customName_preservedAsIs(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip("CustomIcon", tmp);
        assertEquals("CustomIcon", fa.getIcon());
    }

    @Test
    @DisplayName("setIcon(null) clears the /Name entry from the dict")
    void setIcon_null_clearsEntry(@TempDir Path tmp) throws IOException {
        FileAttachmentAnnotation fa = roundTrip(null, tmp);
        // /Name is gone — getIcon() falls back to its default "PushPin".
        assertEquals("PushPin", fa.getIcon(),
                "missing /Name should make getIcon() return the documented default");
        assertFalse(fa.getCOSDictionary().containsKey("Name"),
                "/Name entry must be removed from the dict after setIcon(null)");
    }
}
