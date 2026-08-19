# Document Conversion

Convert between PDF and other document formats: **PDF ↔ HTML**. All conversions run entirely in-process with zero third-party dependencies (only `java.*`/`javax.*`), so no external tools or services are required.

## The structured-document (IR) engine

Every format conversion here flows through a shared intermediate representation called the **Semantic Document Model (SDM / IR)**. Rather than a direct byte-to-byte transform, each input is *read* into the model and each output is *written* from it:

- **PDF → SDM**: extract text, images, tables, and reading order (using the PDF's structure tree when it is tagged, or geometry heuristics when it is not).
- **HTML/DOCX/DOC → SDM**: parse the source markup or binary and resolve its style cascade.
- **SDM → PDF**: paginate and lay out the model with `SdmPdfLayout`.
- **SDM → HTML/DOCX**: serialize the model to the target markup.

**What fidelity to expect.** These conversions reproduce *content and structure* — text, headings, paragraphs, lists, tables, images, hyperlinks, and page geometry — faithfully. They are not pixel-perfect renderers: a converted document is a re-flowed structured copy, not a photograph. Vector graphics (charts, plots, filled shapes) have no equivalent in HTML/DOCX markup and are rasterized to embedded images when the relevant option is enabled (on by default). For a pixel-exact image of a page, rasterize it instead (see [rasterization.md](rasterization.md)).

The entry points are uniform: **save** is selected by the runtime type of a `SaveOptions` subclass (or a `SaveFormat` enum value), and **load** is selected by the runtime type of a `LoadOptions` subclass passed to the `Document` constructor.

---

## PDF → HTML

`Document` produces HTML in one of two modes, chosen with `HtmlSaveOptions.setOutputMode(HtmlOutputMode)`:

- `HtmlOutputMode.FIXED_LAYOUT` (the default) — a visual copy with absolutely positioned spans, preserving the on-page look.
- `HtmlOutputMode.STRUCTURAL` — semantic, reflowable HTML (`h1..h6`, `p`, `ul`/`ol`, `table`) built from the SDM. Tagged PDFs use the author's structure tree; untagged PDFs fall back to geometry heuristics.

### Quick conversion with a SaveFormat

The simplest call uses the format enum and applies default (fixed-layout) options:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.SaveFormat;

try (Document doc = new Document("input.pdf")) {
    doc.save("output.html", SaveFormat.Html);
}
```

### Structural (semantic) HTML

For reflowable, semantically tagged output, pass an `HtmlSaveOptions` with `STRUCTURAL` mode:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.HtmlOutputMode;

try (Document doc = new Document("report.pdf")) {
    HtmlSaveOptions options = new HtmlSaveOptions();
    options.setOutputMode(HtmlOutputMode.STRUCTURAL);
    // On untagged PDFs, geometry heuristics recover headings/lists/tables.
    // Set false to emit shallow paragraph-only HTML instead.
    options.setStructuralHeuristics(true);
    // Repeated per-page running headers/footers become noise when reflowed;
    // they are dropped by default. Set false to keep them inline.
    options.setSuppressRunningHeadersFooters(true);
    doc.save("report.html", options);
}
```

### Controlling images and vector graphics

By default images are embedded as base64 data URIs (single-file output), and vector-graphics regions are rasterized so charts and shapes are not lost:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;

try (Document doc = new Document("charts.pdf")) {
    HtmlSaveOptions options = new HtmlSaveOptions();
    options.setEmbedImages(true);            // base64 data URIs, one self-contained file
    options.setRasterizeVectorGraphics(true); // render charts/shapes to <img>
    doc.save("charts.html", options);
}
```

To keep images as external files instead of embedding them, set an image folder and disable embedding:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;

try (Document doc = new Document("input.pdf")) {
    HtmlSaveOptions options = new HtmlSaveOptions();
    options.setEmbedImages(false);
    options.setImageFolder("out/images");
    doc.save("out/input.html", options);
}
```

### Writing HTML to a stream

A stream target has no folder for external parts, so resources are always embedded:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;

import java.io.FileOutputStream;

try (Document doc = new Document("input.pdf");
     FileOutputStream out = new FileOutputStream("output.html")) {
    doc.save(out, new HtmlSaveOptions()); // stream is not closed by save()
}
```

---

## HTML → PDF

Load an HTML file (or stream) through a `Document` constructor that takes `HtmlLoadOptions`. The document is laid out immediately, so you can `save` it as a PDF right away:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;

try (Document doc = new Document("page.html", new HtmlLoadOptions())) {
    doc.save("page.pdf");
}
```

### Base path and relative resources

Relative `<img>` and `<link rel="stylesheet">` references are resolved against a base path. When you load from a file, the base path defaults to the file's own directory, so local resources resolve automatically. Set it explicitly when loading from a stream or when resources live elsewhere:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;

HtmlLoadOptions options = new HtmlLoadOptions();
options.setBasePath("D:/site/assets");   // resolve relative URLs against this folder
options.setInputEncoding("UTF-8");        // force an encoding (null = auto-detect)

try (Document doc = new Document("D:/site/index.html", options)) {
    doc.save("index.pdf");
}
```

`HtmlLoadOptions(String basePath)` is a convenience constructor for the same thing.

### External (network) resources

Only `data:` URIs and local files (resolved against the base path) are loaded by default. To permit fetching `http:`/`https:` resources referenced from the HTML, opt in explicitly:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;

HtmlLoadOptions options = new HtmlLoadOptions();
options.setAllowNetworkResources(true);  // fetch remote <img>/<link> over the network

try (Document doc = new Document("page.html", options)) {
    doc.save("page.pdf");
}
```

### Page geometry

By default the SDM pipeline honours the source page size embedded in the HTML (via a `<meta>` size, or the source size preserved on a PDF→HTML→PDF round-trip). Provide an explicit `PageInfo` to fix the output page geometry:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlLoadOptions;
import org.aspose.pdf.PageInfo;

HtmlLoadOptions options = new HtmlLoadOptions();
PageInfo pageInfo = new PageInfo();
pageInfo.setWidth(595.0);   // A4 width in points
pageInfo.setHeight(842.0);  // A4 height in points
options.setPageInfo(pageInfo);

try (Document doc = new Document("page.html", options)) {
    doc.save("a4.pdf");
}
```

The CSS-aware SDM pipeline is used by default. `HtmlLoadOptions.setUseSdmPipeline(false)` forces the older DOM-based converter, which does not resolve full stylesheets — leave the default (`true`) unless you have a specific reason.

---


### Tuning the projection with DocSaveOptions

`DocSaveOptions` steers recognition granularity. `RecognitionMode.Flow` (the default) recognizes headings, lists, and tables so the result reflows and edits naturally; `RecognitionMode.Textbox` keeps a shallower, layout-oriented projection:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.DocSaveOptions;

try (Document doc = new Document("report.pdf")) {
    DocSaveOptions options = new DocSaveOptions();
    options.setRecognitionMode(DocSaveOptions.RecognitionMode.Flow);
    options.setSuppressRunningHeadersFooters(true); // drop repeated page furniture
    options.setRasterizeVectorGraphics(true);        // charts/shapes → embedded images
    doc.save("report.docx", options);
}
```

### The DocFormat setting

`DocSaveOptions.setFormat(DocFormat)` accepts both `DocFormat.DocX` (default) and `DocFormat.Doc`. Note the FOSS writer emits **OOXML only**: requesting `DocFormat.Doc` (or `SaveFormat.Doc`) is accepted for API compatibility but produces the same `.docx`-structured package — there is no binary Word 97 serializer.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.DocSaveOptions;

try (Document doc = new Document("input.pdf")) {
    DocSaveOptions options = new DocSaveOptions();
    options.setFormat(DocSaveOptions.DocFormat.Doc); // served as OOXML
    doc.save("input.doc", options);
}
```

You can also write to a stream with `doc.save(OutputStream, DocSaveOptions)`.

---

To override the page geometry, set a `PageInfo` on the load options:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.DocLoadOptions;
import org.aspose.pdf.PageInfo;

DocLoadOptions options = new DocLoadOptions();
PageInfo pageInfo = new PageInfo();
pageInfo.setWidth(612.0);   // US Letter width in points
pageInfo.setHeight(792.0);  // US Letter height in points
options.setPageInfo(pageInfo);

try (Document doc = new Document("report.docx", options)) {
    doc.save("report.pdf");
}
```

Running headers and footers (`headerReference`/`footerReference` parts) become per-page furniture, and the body margins are adjusted so text clears them — the same way Word lays out the document.


---

## Round-tripping and uniform entry points

Because save is dispatched by the runtime type of the options object, you can pass any `SaveOptions` subclass to the uniform `save(String, SaveOptions)` / `save(OutputStream, SaveOptions)` overloads and get the corresponding format:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.SaveOptions;
import org.aspose.pdf.HtmlSaveOptions;

SaveOptions options = new HtmlSaveOptions(); // an HtmlSaveOptions → HTML output
try (Document doc = new Document("input.pdf")) {
    doc.save("output.html", options);
}
```

Likewise, `new Document(String, LoadOptions)` dispatches on the load-options type (`HtmlLoadOptions` → HTML, `DocLoadOptions` → DOCX/DOC).

## Notes & limitations

- **Fidelity is structural, not pixel-exact.** Conversions preserve content, structure, images, and page geometry — not the exact rendered appearance. For an exact page image, rasterize instead (see [rasterization.md](rasterization.md)).
- **Vector graphics** (charts, plots, filled shapes, 3D previews) have no HTML/DOCX equivalent. They are rendered to PNG and emitted as `<img>` / embedded images when `setRasterizeVectorGraphics(true)` is set (the default). With it off, only the text labels of such regions survive.
- **No binary `.doc` output.** `SaveFormat.Doc` and `DocSaveOptions.DocFormat.Doc` are accepted for API compatibility but always produce an OOXML (`.docx`) package. Legacy `.doc` is supported for *input* only.
- **`SaveFormat.Xml` is not implemented.** The enum value exists for API compatibility, but there is no XML serializer; `save(path, SaveFormat.Xml)` falls through to writing a plain PDF. Do not rely on it for XML export.
- **Many `HtmlSaveOptions` / `DocSaveOptions` knobs are stored but inert.** Properties such as font-saving modes, letters-positioning methods, anti-aliasing processing, and several DPI/proximity tuning values are accepted so option-setting code ports cleanly, but do not currently change the output. Each such property documents this in its Javadoc.
- **HTML loading uses the SDM pipeline by default** (`HtmlLoadOptions.setUseSdmPipeline(true)`), which resolves the CSS cascade. The legacy DOM converter (`setUseSdmPipeline(false)`) drops stylesheets and is not recommended.
- **Network fetching is opt-in.** External `http:`/`https:` resources are only loaded when `HtmlLoadOptions.setAllowNetworkResources(true)` is set.
- `Document` implements `AutoCloseable`; always use try-with-resources. Page indices are 1-based (`doc.getPages().get(1)`).

## See also

- [getting-started.md](getting-started.md) — opening, creating, and saving documents
- [rasterization.md](rasterization.md) — rendering pages to PNG/JPEG/TIFF images
- [text-extraction.md](text-extraction.md) — extracting text and structure from a PDF
- [metadata.md](metadata.md) — document information and XMP metadata
