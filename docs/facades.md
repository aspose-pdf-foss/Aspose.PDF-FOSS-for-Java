# The Facades API

The `org.aspose.pdf.facades` package provides **task-focused helper classes** that
sit on top of the core `Document` / `Page` API. Each facade wraps one common job —
merging files, replacing text, stamping, encrypting, extracting, signing — behind a
small, uniform surface so you do not have to drive the low-level object model
yourself.

Most facades follow the same lifecycle:

1. **Construct** the facade (`new PdfFileEditor()`), optionally passing an input path
   or an already-open `Document`.
2. **Bind** a PDF with a `bindPdf(...)` overload — from a file path, an `InputStream`,
   or an existing `Document`.
3. **Do the work** — call the verb methods (`concatenate`, `replaceText`,
   `addStamp`, `encryptFile`, …).
4. **Save** the result with `save(...)` and/or `close()` the facade.

Conventions used throughout this package:

- **Page numbers are 1-based.**
- Many facades **implement `AutoCloseable`/`Closeable`**, so you can use
  try-with-resources. Facades that own the `Document` they opened will close it for
  you; facades that were bound to an external `Document` leave it open.
- The `*Editor` / `PdfFile*` facades return **`boolean`** from most operations
  (`true` = success). They log failures via `java.util.logging` rather than throwing,
  so check the return value.
- The `Try*`-prefixed variants (on `PdfFileEditor` and `PdfFileSecurity`) run the
  same operation but capture the exception instead of logging-and-returning; use
  `getLastException()` on `PdfFileEditor` to inspect it.

- Version: 26.7 · Java 11+ · root package `org.aspose.pdf` · zero third-party
  dependencies.

---

## PdfFileEditor

**Purpose:** Page-level document surgery — concatenate, extract, split, insert,
delete, resize, and add margins/page-breaks. The most-used facade.

Key methods:

- `boolean bindPdf(String | InputStream | Document)`, `boolean save(String | OutputStream)`
- `boolean concatenate(String[] inputFiles, String outputFile)`
- `boolean concatenate(String first, String second, String outputFile)`
- `boolean concatenate(InputStream[], OutputStream)` / `concatenate(Document[], Document result)`
- `boolean extract(String in, int startPage, int endPage, String out)` and
  `extract(String in, int[] pageNumbers, String out)`
- `ByteArrayOutputStream[] splitToPages(String in)` / `boolean splitToPages(String in, String outDir)`
- `boolean splitFromFirst(...)`, `boolean splitToEnd(...)`
- `boolean insert(String in, int insertPosition, String portFile, int startPage, int endPage, String out)`
- `boolean append(String in, String portFile, int startPage, int endPage, String out)`
- `boolean delete(String in, int[] pageNumbers, String out)`
- `void resizeContents(Document, ContentsResizeParameters)` / file overload
  `resizeContents(String in, String out, ContentsResizeParameters)`
- `boolean addMargins(...)`, `boolean addPageBreak(Document src, Document dst, PageBreak[] breaks)`
- Flags: `setCopyOutlines`, `setCopyLogicalStructure`, `setKeepActions`, `setOptimizeSize`
- `Try*` variants: `tryConcatenate`, `tryExtract`, `tryAppend`, `tryInsert`,
  `tryDelete`, `trySplitFromFirst`, `trySplitToEnd`, `tryResize`, plus `getLastException()`

```java
import org.aspose.pdf.facades.PdfFileEditor;

PdfFileEditor editor = new PdfFileEditor();

// Merge three PDFs (outlines/bookmarks are copied by default).
editor.concatenate(new String[]{"a.pdf", "b.pdf", "c.pdf"}, "merged.pdf");

// Extract pages 2..4 of the merged file into a new document.
editor.extract("merged.pdf", 2, 4, "pages_2_to_4.pdf");

// Remove pages 1 and 5.
editor.delete("merged.pdf", new int[]{1, 5}, "trimmed.pdf");
```

> **Note:** `makeNUp(...)` is a stub and returns `false`. `makeBooklet(...)` produces
> a best-effort landscape imposition. See [page operations below](#notes--limitations).

See also [page-operations.md](page-operations.md) for merge/split/rotate/resize walkthroughs and the `PageCollection` API.

---

## PdfContentEditor

**Purpose:** Edit page content — primarily **text replacement**, plus adding a Text
(sticky-note) annotation and reading/removing stamp metadata. Implements
`AutoCloseable`.

Key methods:

- `boolean bindPdf(...)`, `boolean save(...)`, `void close()`
- `boolean replaceText(String searchText, String replaceText)` (whole document)
- `boolean replaceText(String searchText, int pageNumber, String replaceText)`
- `ReplaceTextStrategy getReplaceTextStrategy()` / `setTextReplaceOptions(TextReplaceOptions)`
  — control regex use and first-vs-all scope
- `boolean createText(Rectangle rect, String title, String contents, boolean open, String icon, int pageNumber)`
- `StampInfo[] getStamps(int pageNumber)`, `boolean deleteStampById(int stampId)` / `(int pageNumber, int stampId)`

```java
import org.aspose.pdf.facades.PdfContentEditor;

try (PdfContentEditor ed = new PdfContentEditor()) {
    ed.bindPdf("invoice.pdf");
    ed.replaceText("{{CUSTOMER}}", "Acme Corp");   // replaces every occurrence
    ed.save("invoice_filled.pdf");
}
```

---

## PdfExtractor

**Purpose:** Pull **text, images, and attachments** out of a PDF. Implements
`Closeable`. Uses an "extract then iterate" pattern.

Key methods:

- `void bindPdf(String | InputStream | Document)`, `void close()`
- `void setStartPage(int)` / `setEndPage(int)` — 1-based extraction window
- Text: `void extractText()` / `extractText(Charset)`, then
  `void getText(String path | OutputStream)` or `String getTextAsString()`;
  per-page: `boolean hasNextPageText()` + `getNextPageText(...)`
- Images: `void extractImage()`, then `boolean hasNextImage()` +
  `getNextImage(String | OutputStream[, ImageFormat])`, `int getImageCount()`;
  `setExtractImageMode(ExtractImageMode)`, `setResolution(Resolution)`
- Attachments: `void extractAttachment()` / `extractAttachment(String name)`,
  `List<String> getAttachNames()`, `void getAttachment(String pathOrDir)`
- `void setPassword(String)` for encrypted input

```java
import org.aspose.pdf.facades.PdfExtractor;

try (PdfExtractor ex = new PdfExtractor()) {
    ex.bindPdf("report.pdf");
    ex.extractText();
    System.out.println(ex.getTextAsString());

    ex.extractImage();
    int i = 0;
    while (ex.hasNextImage()) {
        ex.getNextImage("img_" + (i++) + ".png");
    }
}
```

See also [text-extraction.md](text-extraction.md).

---

## PdfBookmarkEditor

**Purpose:** Create, extract, style, and delete **bookmarks (outlines)**, and
import/export the bookmark tree as XML. Implements `AutoCloseable`.

Key methods:

- `boolean bindPdf(...)`, `boolean save(...)`, `void close()`
- `void createBookmarks()` / `createBookmarks(Color, boolean bold, boolean italic)`
  — one "Page N" bookmark per page
- `boolean createBookmarkOfPage(String title, int pageNumber)`
- `Bookmarks extractBookmarks()` / `extractBookmarks(String title)`
- `boolean deleteBookmarks()` / `deleteBookmarks(String title)`
- `void exportBookmarksToXML(String xmlFile)` / `importBookmarksWithXML(String xmlFile)`

```java
import org.aspose.pdf.facades.PdfBookmarkEditor;

try (PdfBookmarkEditor bm = new PdfBookmarkEditor()) {
    bm.bindPdf("book.pdf");
    bm.createBookmarkOfPage("Chapter 1", 1);
    bm.createBookmarkOfPage("Chapter 2", 12);
    bm.save("book_bookmarked.pdf");
}
```

See also [navigation.md](navigation.md) for the lower-level outline/destination API.

---

## PdfFileSignature

**Purpose:** Enumerate, verify, add, and remove **digital signatures**. Implements
`AutoCloseable`.

Key methods:

- `void bindPdf(String | InputStream | Document)`, `void save(String | OutputStream)`, `void close()`
- Inspect: `List<String> getSignNames()` (signed only), `getBlankSignNames()`,
  `List<SignatureName> getSignatureNames(boolean onlyActive)`,
  `boolean containsSignature()`, `boolean isSigned(String)`
- Metadata: `getReason`, `getLocation`, `getSignerName`, `getDateTime`,
  `getContactInfo` (each by field name or `SignatureName`)
- Verify: `boolean verifySignature(String signName)` (and `SignatureName` /
  `ValidationOptions` / `ValidationResult[]` overloads),
  `boolean isCoversWholeDocument(String)`, `int getTotalRevision()`, `int getRevision(String)`
- Sign: `void sign(int pageNumber, String reason, String contact, String location, boolean visible, Rectangle rect, Signature signature)`
  and `void sign(String fieldName, Signature signature)`
- Remove: `void removeSignature(String signName[, boolean removeField])`;
  usage rights: `containsUsageRights()`, `removeUsageRights()`

```java
import org.aspose.pdf.facades.PdfFileSignature;

try (PdfFileSignature sig = new PdfFileSignature("signed.pdf")) {
    for (String name : sig.getSignNames()) {
        System.out.println(name + " valid=" + sig.verifySignature(name)
                + " by " + sig.getSignerName(name));
    }
}
```

See also [signatures.md](signatures.md) for signing with a PKCS#12 keystore and verification details.

---

## PdfFileSecurity

**Purpose:** **Encrypt, decrypt, change passwords, and set permissions**. Implements
`Closeable`. Can be driven either by binding a document or by setting input/output up
front (the constructor overloads).

Key methods:

- Constructors: `PdfFileSecurity()`, `(String in, String out)`, `(InputStream, OutputStream)`, `(Document, OutputStream)`
- `boolean bindPdf(...)`, `boolean save()` / `save(String)` / `save(OutputStream)`, `void close()`
- `boolean encryptFile(String userPwd, String ownerPwd, DocumentPrivilege, KeySize, Algorithm)` (and int-permission overload)
- `boolean decryptFile(String password)`, `boolean isEncrypted()`
- `boolean setPrivilege(DocumentPrivilege)` / `setPrivilege(String userPwd, String ownerPwd, DocumentPrivilege)` / int-permission overload
- `boolean changePassword(String oldPwd, String newUserPwd, String newOwnerPwd[, KeySize, Algorithm])`
- `Try*` variants: `tryEncryptFile`, `tryDecryptFile`, `trySetPrivilege`,
  `tryChangePassword`; `setAllowExceptions(boolean)`

```java
import org.aspose.pdf.facades.PdfFileSecurity;
import org.aspose.pdf.facades.DocumentPrivilege;
import org.aspose.pdf.facades.KeySize;
import org.aspose.pdf.facades.Algorithm;

try (PdfFileSecurity sec = new PdfFileSecurity("in.pdf", "out.pdf")) {
    sec.bindPdf("in.pdf");
    sec.encryptFile("userpwd", "ownerpwd",
            DocumentPrivilege.getForbidAll(), KeySize.x256, Algorithm.AES);
    sec.save();
}
```

See also [security.md](security.md).

---

## PdfConverter

**Purpose:** Rasterize PDF pages to **images** (page-by-page) or to a multi-page
**TIFF**. Implements `AutoCloseable`.

Key methods:

- `boolean bindPdf(...)`, `void close()`, `void doConvert()`
- `void setStartPage(int)` / `setEndPage(int)`, `void setResolution(Resolution)`, `int getPageCount()`
- Iterate: `boolean hasNextImage()`, then
  `boolean getNextImage(String outFile | OutputStream[, ImageFormat | String format][, width, height][, quality])`
- TIFF: `boolean saveAsTIFF(String outFile[, TiffSettings | CompressionType | width,height][, Resolution])`,
  `boolean saveAsTIFF(OutputStream, ...)`, `boolean saveAsTIFFClassF(String[, width, height])`

```java
import org.aspose.pdf.facades.PdfConverter;
import org.aspose.pdf.facades.ImageFormat;

try (PdfConverter conv = new PdfConverter()) {
    conv.bindPdf("doc.pdf");
    conv.doConvert();
    int page = 1;
    while (conv.hasNextImage()) {
        conv.getNextImage("page_" + (page++) + ".png", ImageFormat.Png);
    }
    // Or all pages into one multi-page TIFF:
    // conv.saveAsTIFF("doc.tiff");
}
```

See also [rasterization.md](rasterization.md) and [conversion.md](conversion.md).

---

## PdfPageEditor

**Purpose:** Adjust **page geometry** — size, rotation, zoom, and content position.
Implements `Closeable`.

Key methods:

- `boolean bindPdf(...)`, `boolean save(...)`, `void close()`, `int getPageCount()`
- `float[] getPageSize(int pageNumber)` / `void setPageSize(int pageNumber, PageSize)`
- `int getPageRotation(int)` / `void setPageRotation(int pageNumber, int rotation)`;
  bulk `setPageRotations(Map<Integer,Integer>)` / `getPageRotations()`
- `void setProcessPages(int[])` / `int[] getProcessPages()` — restrict subsequent edits
- `float getZoom()` / `void setZoom(float)`, `void movePosition(int dx, int dy)`

```java
import org.aspose.pdf.facades.PdfPageEditor;

try (PdfPageEditor pe = new PdfPageEditor()) {
    pe.bindPdf("scan.pdf");
    pe.setPageRotation(1, 90);      // rotate page 1 by 90 degrees
    pe.save("scan_rotated.pdf");
}
```

---

## PdfAnnotationEditor

**Purpose:** Bulk **annotation** operations — flatten, delete, count, re-author, and
import/export XFDF.

Key methods:

- `boolean bindPdf(...)`, `boolean save(...)`, `void close()`, `Document getDocument()`
- `boolean flattenAnnotations()`
- `boolean deleteAnnotations()` / `deleteAnnotations(String annotationType)`
- `int getAnnotationCount(int pageNumber)`
- `void modifyAnnotationsAuthor(int startPage, int endPage, String oldAuthor, String newAuthor)`
- `void exportAnnotationsXfdf(OutputStream, int startPage, int endPage, ...)`
- `void importAnnotationsFromXfdf(String filePath | InputStream[, AnnotationType[]])`

```java
import org.aspose.pdf.facades.PdfAnnotationEditor;

try (PdfAnnotationEditor ae = new PdfAnnotationEditor()) {
    ae.bindPdf("comments.pdf");
    ae.flattenAnnotations();      // bake annotations into page content
    ae.save("flattened.pdf");
}
```

See also [annotations.md](annotations.md).

---

## PdfFileStamp

**Purpose:** Add **stamps, headers, footers, and page numbers**. Implements
`AutoCloseable`.

Key methods:

- Constructors: `PdfFileStamp()`, `(String in, String out)`, `(InputStream, OutputStream)`, `(Document)`
- `boolean bindPdf(...)`, `boolean save(...)`, `void close()`
- `void addStamp(TextStamp)` / `addStamp(Stamp)`
- `void addHeader(FormattedText, float topMargin)` / `addHeader(String imageFile, float topMargin)`
- `void addFooter(FormattedText, float bottomMargin[, ...])` / `addFooter(String imageFile, float bottomMargin)`
- `void addPageNumber(FormattedText)`
- `int getStampId()` / `setStampId(int)`, `int getStampCount()`

```java
import org.aspose.pdf.facades.PdfFileStamp;
import org.aspose.pdf.facades.FormattedText;

try (PdfFileStamp stamp = new PdfFileStamp("in.pdf", "out.pdf")) {
    stamp.bindPdf("in.pdf");
    stamp.addFooter(new FormattedText("Confidential"), 20f);
    stamp.addPageNumber(new FormattedText("Page $p of $P"));
    stamp.save("out.pdf");
}
```

The `Stamp` class (a plain stamp descriptor consumed by `addStamp`) lets you
`bindLogo`, `bindImage`, or `bindPdf` a source and set opacity, origin, rotation,
alignment, background flag, and target pages.

---

## Form

**Purpose:** Fill, read, flatten, and import/export **AcroForm** field values.
Implements `AutoCloseable`.

Key methods:

- Constructors accept file paths, streams, or a `Document` (with optional output).
- `String[] getFieldNames()`, `String getField(String name)`, `String getFieldType(String)`,
  `FieldType getFieldTypeAsEnum(String)`
- `boolean fillField(String name, String value)` / `(name, boolean)` / `(name, int)`
- `boolean flattenAllFields()` / `flattenField(String name)`
- `boolean hasXfa()`, `boolean isSignaturesExist()`
- `void importFdf(InputStream)`, `void importXml(InputStream)`, `void exportXml(OutputStream)`
- `boolean save()` / `save(String)` / `save(OutputStream)`

```java
import org.aspose.pdf.facades.Form;

try (Form form = new Form("application.pdf")) {
    form.fillField("Name", "Jane Doe");
    form.fillField("Subscribe", true);
    form.flattenAllFields();          // make values non-editable
    form.save("application_filled.pdf");
}
```

See also [forms.md](forms.md).

---

## FormEditor

**Purpose:** **Add and modify** form fields (not just fill them) — create text
boxes, buttons, list items, set field limits, copy fields between files.

Key methods:

- Constructors accept file paths, streams, or a `Document` (with optional output).
- `boolean addField(FieldType type, String fieldName, int pageNumber, float llx, float lly, float urx, float ury)`
  and value/style overloads
- `boolean addSubmitBtn(String fieldName, int pageNumber, String caption, ...)`,
  `boolean addListItem(String fieldName, String item)`
- `boolean removeField(String)`, `boolean setFieldLimit(String, int)` / `int getFieldLimit(String)`
- `boolean copyOuterField(String sourceFile, String fieldName, int pageNumber)`
- `FormFieldFacade getFacade()` / `setFacade(FormFieldFacade)` — appearance defaults
- Also mirrors `Form`'s `fillField`, `flattenAllFields`, `getFieldNames`, `save`, `close`

```java
import org.aspose.pdf.facades.FormEditor;
import org.aspose.pdf.facades.FieldType;

try (FormEditor fe = new FormEditor("blank.pdf")) {
    fe.addField(FieldType.Text, "email", 1, 100, 700, 300, 720);
    fe.save("with_field.pdf");
}
```

See also [forms.md](forms.md).

---

## PdfFileInfo

**Purpose:** Read and write **document metadata** and inspect basic document
properties (page count/size, encryption state, permissions). Implements `Closeable`.

Key methods:

- Constructors accept a file path (optionally with password), stream, or `Document`.
- `boolean bindPdf(...)` (password overloads available)
- Read: `getTitle`, `getAuthor`, `getSubject`, `getKeywords`, `getCreator`,
  `getProducer`, `getCreationDate`, `getModDate`
- `boolean isPdfFile()`, `boolean isEncrypted()`, `boolean hasOpenPassword()`,
  `boolean hasEditPassword()`, `PasswordType getPasswordType()`
- `int getNumberOfPages()`, `double getPageWidth(int)`, `double getPageHeight(int)`,
  `DocumentPrivilege getDocumentPrivilege()`
- Write: `void setMetaInfo(String name, String value)`, `void clearInfo()`,
  `boolean saveNewInfo(String outFile | OutputStream)`

```java
import org.aspose.pdf.facades.PdfFileInfo;

try (PdfFileInfo info = new PdfFileInfo("doc.pdf")) {
    System.out.println("Pages: " + info.getNumberOfPages());
    System.out.println("Author: " + info.getAuthor());
    info.setMetaInfo("Author", "New Author");
    info.saveNewInfo("doc_updated.pdf");
}
```

See also [metadata.md](metadata.md).

---

## PdfXmpMetadata

**Purpose:** Read and edit **XMP metadata** as key/value pairs. Implements
`Closeable`.

Key methods:

- `boolean bindPdf(...)`, `boolean save(String)`, `void close()`
- `XmpMetadata getXmpMetadata()`
- `boolean contains(String key)`, `XmpValue get(String key)`
- `void add(String key, String|XmpValue value)`, `void set(String key, String value)`,
  `void remove(String key)`

```java
import org.aspose.pdf.facades.PdfXmpMetadata;

try (PdfXmpMetadata xmp = new PdfXmpMetadata("doc.pdf")) {
    xmp.set("dc:title", "Quarterly Report");
    xmp.save("doc_xmp.pdf");
}
```

See also [metadata.md](metadata.md).

---

## PdfFileMend

**Purpose:** Stamp **raw images and text** onto existing pages at explicit
coordinates (a lower-level companion to `PdfFileStamp`). Implements `Closeable`.

Key methods:

- Constructors accept file paths, streams, or a `Document`.
- `boolean addImage(String imageFile | InputStream, int pageNumber, double llx, double lly, double urx, double ury)`
- `boolean addText(FormattedText, int pageNumber, double x, double y)` (float overload too)
- `boolean save()` / `save(String)` / `save(OutputStream)`, `void close()`

```java
import org.aspose.pdf.facades.PdfFileMend;
import org.aspose.pdf.facades.FormattedText;

try (PdfFileMend mend = new PdfFileMend("in.pdf")) {
    mend.addImage("logo.png", 1, 50, 750, 150, 800);
    mend.addText(new FormattedText("DRAFT"), 1, 250, 400);
    mend.save("out.pdf");
}
```

---

## PdfViewer

**Purpose:** **Render and print** PDF pages via `java.awt` (implements
`java.awt.print.Printable`). Useful for on-screen preview and printing rather than
file output. Implements `AutoCloseable`.

Key methods:

- `void bindPdf(String | InputStream | Document)`, `void openPdfFile(String)`, `void close()`
- `BufferedImage decodePage(int pageNum)`, `BufferedImage[] decodeAllPages()`
- `void printDocument()`, `void printDocumentWithSettings(...)`, `void printLargePdf(String)`
- Options: `setResolution(int)`, `setAutoResize`, `setAutoRotate`, `setPrintAsGrayscale`,
  `setPrintPageDialog`

```java
import java.awt.image.BufferedImage;
import org.aspose.pdf.facades.PdfViewer;

try (PdfViewer viewer = new PdfViewer()) {
    viewer.bindPdf("doc.pdf");
    viewer.setResolution(150);
    BufferedImage firstPage = viewer.decodePage(1);   // 1-based
    // ... display or save firstPage ...
}
```

See also [rasterization.md](rasterization.md).

---

## Notes & limitations

- **Not implemented / partial on `PdfFileEditor`:** `makeNUp(...)` is a stub and
  returns `false`. `makeBooklet(...)` performs a best-effort landscape imposition
  rather than a full print-shop booklet layout. `setCopyLogicalStructure(true)`
  currently only propagates the `/MarkInfo` "Marked=true" hint — a full
  cross-document `/StructTreeRoot` merge is not performed.
- **Error handling style varies.** `PdfFileEditor`, `PdfContentEditor`,
  `PdfBookmarkEditor`, `PdfConverter`, `Form`, `FormEditor`, `PdfFileStamp`,
  `PdfFileMend`, `PdfPageEditor`, `PdfAnnotationEditor`, and `PdfFileSecurity`
  return `boolean` and log rather than throw. `PdfExtractor` and `PdfViewer` throw
  `IOException`. `PdfFileSignature.save(...)` throws `IOException`. Always check the
  return value where one is provided.
- **Document ownership.** A facade that opens a file/stream owns that `Document` and
  closes it on `close()`; a facade bound to an external `Document` does not. Prefer
  try-with-resources for facades implementing `AutoCloseable`/`Closeable`.
- **Signature verification** requires the original document bytes on disk (via the
  document's source path). Documents loaded purely from a stream or byte array may
  report a signature as unverifiable because the byte-range digest cannot be
  recomputed.
- **`PdfConverter` / `PdfViewer`** rendering depends on `javax.imageio` and
  `java.awt`; some advanced PDF features may not render pixel-perfectly. See
  [limitations.md](limitations.md).
- The package also contains supporting **value/enum types** used by the facades
  above — for example `Bookmark`, `Bookmarks`, `Stamp`, `StampInfo`, `SignatureName`,
  `FormattedText`, `FormFieldFacade`, `DocumentPrivilege`, `FieldType`, `FontColor`,
  `FontStyle`, `Algorithm`, `KeySize`, `EncodingType`, `PasswordType`, `ImageFormat`,
  `ExtractImageMode`, and `ReplaceTextStrategy`. These are not standalone facades but
  parameters/results of the methods documented here.

## See also

- [page-operations.md](page-operations.md) — merge/split/rotate/resize behind `PdfFileEditor` / `PdfPageEditor`
- [navigation.md](navigation.md) — outlines/destinations behind `PdfBookmarkEditor`
- [signatures.md](signatures.md) — signing/verification behind `PdfFileSignature`
- [security.md](security.md) — encryption and permissions behind `PdfFileSecurity`
- [forms.md](forms.md) — AcroForm field model behind `Form` / `FormEditor`
- [annotations.md](annotations.md) — annotation types used by `PdfAnnotationEditor`
- [text-extraction.md](text-extraction.md) — the absorber API behind `PdfExtractor`
- [conversion.md](conversion.md) and [rasterization.md](rasterization.md) — image/TIFF
  output behind `PdfConverter` / `PdfViewer`
- [metadata.md](metadata.md) — document info and XMP behind `PdfFileInfo` / `PdfXmpMetadata`
- [getting-started.md](getting-started.md) — the core `Document` / `Page` API these
  facades wrap
