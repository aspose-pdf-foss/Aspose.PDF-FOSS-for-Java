# File Attachments

OpenPDF (version 26.7, Java 11+, root package `org.aspose.pdf`, zero third-party
dependencies) can embed arbitrary files inside a PDF and read them back out.
Two mechanisms exist:

- **Document-level embedded files** — files stored in the catalog
  `/Names → /EmbeddedFiles` name tree (ISO 32000-1:2008, §7.11.4). These are the
  "attachments" shown in a viewer's attachment panel. Exposed through
  `Document.getEmbeddedFiles()`, which returns an `EmbeddedFileCollection` of
  `FileSpecification` objects.
- **File-attachment annotations** — a `/FileAttachment` annotation placed on a
  page that displays an icon (pushpin, paperclip, etc.) marking the presence of
  an attached file. Exposed through `org.aspose.pdf.annotations.FileAttachmentAnnotation`.

The two are independent in this build: `FileAttachmentAnnotation` controls the
icon and rectangle only. See [Notes & limitations](#notes--limitations).

## Attach a file to a document

Add an embedded file by constructing a `FileSpecification` and calling
`add(FileSpecification)` on the collection returned by
`Document.getEmbeddedFiles()`.

The `FileSpecification(String file, String description)` constructor reads the
file from disk (if it exists and is a regular file), stores its bytes as an
embedded stream, and derives the stored file name from the path's last segment.
You can also set the MIME type via `setMIMEType(String)` (alias
`setMimeType(String)`); the MIME string is written to the embedded stream's
`/Subtype`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.FileSpecification;

public class AttachFile {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            FileSpecification fs =
                new FileSpecification("data/report.csv", "Quarterly figures");
            fs.setMIMEType("text/csv");

            doc.getEmbeddedFiles().add(fs);
            doc.save("with-attachment.pdf");
        }
    }
}
```

### Attach from an in-memory stream

When the payload is not on disk, use the
`FileSpecification(InputStream stream, String name)` constructor. It reads the
whole stream and embeds it under the given name.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.FileSpecification;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class AttachFromStream {
    public static void main(String[] args) throws Exception {
        byte[] payload = "id,name\n1,Alice\n".getBytes(StandardCharsets.UTF_8);
        try (Document doc = new Document("input.pdf")) {
            FileSpecification fs = new FileSpecification(
                new ByteArrayInputStream(payload), "people.csv");
            fs.setDescription("Generated at runtime");
            fs.setMimeType("text/csv");

            doc.getEmbeddedFiles().add(fs);
            doc.save("with-attachment.pdf");
        }
    }
}
```

## List embedded files and their metadata

`EmbeddedFileCollection` is `Iterable<FileSpecification>` and supports
`getCount()` (alias `size()`) plus **1-based** `get(int)`. Each
`FileSpecification` exposes its name (`/F`), Unicode name (`/UF`), description
(`/Desc`), MIME type, and a `FileParams` object (size, dates, checksum from the
embedded stream's `/Params`).

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.EmbeddedFileCollection;
import org.aspose.pdf.FileParams;
import org.aspose.pdf.FileSpecification;

public class ListAttachments {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("with-attachment.pdf")) {
            EmbeddedFileCollection files = doc.getEmbeddedFiles();
            System.out.println("Attachments: " + files.getCount());

            // 1-based index
            for (int i = 1; i <= files.getCount(); i++) {
                FileSpecification fs = files.get(i);
                System.out.println("Name:        " + fs.getName());
                System.out.println("Unicode name:" + fs.getUnicodeFileName());
                System.out.println("Description: " + fs.getDescription());
                System.out.println("MIME type:   " + fs.getMIMEType());

                FileParams p = fs.getParams();
                if (p != null) {
                    System.out.println("Size:        " + p.getSize());
                    System.out.println("Created:     " + p.getCreationDate());
                    System.out.println("Modified:    " + p.getModDate());
                    System.out.println("CheckSum:    " + p.getCheckSum());
                }
                System.out.println("---");
            }

            // Or iterate directly:
            for (FileSpecification fs : files) {
                System.out.println(fs.getName());
            }
        }
    }
}
```

You can also look up a specification by its file name with
`get(String name)`, which matches against `/F` and returns `null` when nothing
matches.

```java
FileSpecification fs = doc.getEmbeddedFiles().get("report.csv");
if (fs != null) {
    System.out.println(fs.getDescription());
}
```

## Extract an embedded file to disk

`FileSpecification.getData()` returns the decoded bytes of the embedded stream
(or `null` when no data is present). `getContents()` returns the same bytes
wrapped in an `InputStream`. Write the bytes wherever you need them.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.FileSpecification;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExtractAttachments {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("with-attachment.pdf")) {
            var files = doc.getEmbeddedFiles();
            for (int i = 1; i <= files.getCount(); i++) {
                FileSpecification fs = files.get(i);
                byte[] data = fs.getData();
                if (data == null) {
                    continue;
                }
                String name = fs.getName() != null ? fs.getName() : ("file" + i);
                Path out = Path.of("extracted", name);
                Files.createDirectories(out.getParent());
                Files.write(out, data);
                System.out.println("Wrote " + out + " (" + data.length + " bytes)");
            }
        }
    }
}
```

If you prefer the stream form:

```java
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

try (InputStream in = fs.getContents()) {
    if (in != null) {
        Files.copy(in, Path.of("extracted", fs.getName()));
    }
}
```

## Delete an attachment

`EmbeddedFileCollection` offers three removal operations. All keep the backing
name tree consistent; save the document to persist the change.

- `delete(int index)` — remove by **1-based** index.
- `delete(String name)` — remove the first file matching `/F`.
- `delete()` — remove **all** embedded files (drops the `/EmbeddedFiles` entry).

```java
import org.aspose.pdf.Document;

public class DeleteAttachments {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("with-attachment.pdf")) {
            var files = doc.getEmbeddedFiles();

            // Remove one by name:
            files.delete("report.csv");

            // Or remove the first by index (1-based):
            // files.delete(1);

            // Or remove everything:
            // files.delete();

            doc.save("cleaned.pdf");
        }
    }
}
```

## Attachments survive merges

`PdfFileEditor.concatenate(...)` preserves embedded files from every input
document. Internally each source `FileSpecification` is re-embedded into the
result as a fresh copy (name, bytes, and description are carried over), so the
merged output carries the union of all inputs' attachments.

```java
import org.aspose.pdf.facades.PdfFileEditor;

public class MergeKeepsAttachments {
    public static void main(String[] args) throws Exception {
        PdfFileEditor editor = new PdfFileEditor();
        editor.concatenate(
            new String[] { "a.pdf", "b.pdf" },  // both may carry attachments
            "merged.pdf");
        // merged.pdf contains the embedded files from both a.pdf and b.pdf
    }
}
```

## Page-level file-attachment annotation

`FileAttachmentAnnotation` places an attachment icon on a page. Construct it
with a page and a rectangle, optionally set the icon name, then add it to the
page's annotation collection.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.FileAttachmentAnnotation;

public class PageAttachmentIcon {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            Page page = doc.getPages().get_Item(1);   // 1-based

            FileAttachmentAnnotation ann =
                new FileAttachmentAnnotation(page, new Rectangle(50, 750, 70, 770));
            ann.setIcon("Paperclip");                 // Graph | PushPin | Paperclip | Tag

            page.getAnnotations().add(ann);
            doc.save("with-icon.pdf");
        }
    }
}
```

`getIcon()` returns the current icon name, defaulting to `"PushPin"` when the
annotation has no `/Name` entry. Passing `null` to `setIcon(null)` removes the
entry so the viewer uses its default.

## Notes & limitations

- **Only `getEmbeddedFiles()`** exposes the document-level collection. There is
  no `getEmbeddedFilesCollection()` method in this build.
- **`FileSpecification` metadata is set-once at construction for the payload.**
  The disk/stream constructors embed the bytes and populate `/Params → /Size`.
  There is no public setter to replace the embedded bytes after construction.
- **`FileParams` is read-only.** It exposes `getSize()`, `getCreationDate()`,
  `getModDate()`, and `getCheckSum()`. `getCreationDate()`/`getModDate()` return
  the raw PDF date string (e.g. `D:20240131120000Z`); there is no automatic
  population of dates or checksum on embed — expect these to be `null` for files
  you create unless the source PDF supplied them.
- **MIME type** is stored on the embedded stream's `/Subtype`; reading it back
  requires the embedded stream to exist. `getMIMEType()`/`getMimeType()` return
  `null` when there is no embedded stream.
- **`FileAttachmentAnnotation` does not link to a `FileSpecification` in this
  build.** It only manages the icon (`/Name`) and rectangle. To make an attached
  file available to viewers, add it to `Document.getEmbeddedFiles()`; the
  annotation icon is presentational. There is no `getFile()`/`setFile()` on the
  annotation.
- **1-based indexing** applies to both `EmbeddedFileCollection.get(int)` /
  `delete(int)` and to page access (`getPages().get_Item(1)`).
- **`get(String name)` / `delete(String name)`** match against the `/F` entry
  only. If a portfolio carries several attachments under the same name,
  `delete(String)` removes just the first match per call.
- Always call `doc.save(...)` after adding or deleting to persist changes, and
  use try-with-resources so the `Document` is closed.

## See also

- `org.aspose.pdf.Document` — `getEmbeddedFiles()`
- `org.aspose.pdf.EmbeddedFileCollection` — add / enumerate / delete
- `org.aspose.pdf.FileSpecification` — name, description, MIME, data
- `org.aspose.pdf.FileParams` — size, dates, checksum
- `org.aspose.pdf.annotations.FileAttachmentAnnotation` — page-level icon
- `org.aspose.pdf.facades.PdfFileEditor` — `concatenate(...)` preserves attachments
- ISO 32000-1:2008 §7.11.3–§7.11.4 (file specifications, embedded files) and
  §12.5.6.15 (file attachment annotations)
