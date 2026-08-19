# Page operations

Split, merge/concatenate, insert, delete, reorder, rotate, and resize pages in a
PDF. This page covers the two ways to do page-level work: the low-level
`PageCollection` API on `Document` (fine-grained control) and the
`PdfFileEditor` facade (one-call helpers for common file-to-file workflows).

- Version 26.7; Java 11+.
- Root package: `org.aspose.pdf`; the facade lives in `org.aspose.pdf.facades`.
- Zero third-party dependencies (only `java.*`, `javax.crypto`, `javax.imageio`).
- Page indices are **1-based**: `getPages().get(1)` is the first page.
- `Document` implements `AutoCloseable` — use try-with-resources.

## Model at a glance

Every `Document` exposes its pages through `Document.getPages()`, which returns a
`PageCollection`. `PageCollection` flattens the PDF page tree into a linear,
1-based list and offers `get`, `getCount`/`size`, `add`, `insert`, `delete`,
`clear`, and iteration (`Iterable<Page>`).

`PdfFileEditor` is a thin facade on top of that model. Its methods return
`boolean` (`true` on success) and **log errors instead of throwing** — check the
return value, and use `getLastException()` after a `try*` variant to inspect a
captured failure.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;

try (Document doc = new Document("input.pdf")) {
    int count = doc.getPages().getCount();      // total pages
    Page first = doc.getPages().get(1);         // 1-based
    for (Page page : doc.getPages()) {
        // iterate in document order
    }
}
```

## Merge / concatenate multiple PDFs

The simplest merge is `PdfFileEditor.concatenate`. The first input is the base
document; pages from the remaining inputs are appended in order. By default,
outlines (bookmarks) and embedded-file attachments from each source are merged
into the result (`setCopyOutlines` controls the former; attachments are always
copied).

```java
import org.aspose.pdf.facades.PdfFileEditor;

PdfFileEditor editor = new PdfFileEditor();
boolean ok = editor.concatenate(
        new String[] { "a.pdf", "b.pdf", "c.pdf" },
        "merged.pdf");
if (!ok) {
    // inspect editor.getLastException() only after a try* call;
    // concatenate itself logs and returns false
}
```

There is a two-file convenience overload and stream/document overloads:

```java
PdfFileEditor editor = new PdfFileEditor();

// Two named files
editor.concatenate("first.pdf", "second.pdf", "merged.pdf");

// Streams in, stream out
try (java.io.InputStream a = new java.io.FileInputStream("a.pdf");
     java.io.InputStream b = new java.io.FileInputStream("b.pdf");
     java.io.OutputStream out = new java.io.FileOutputStream("merged.pdf")) {
    editor.concatenate(new java.io.InputStream[] { a, b }, out);
}

// Append every page of each source document into a result document
try (Document d1 = new Document("a.pdf");
     Document d2 = new Document("b.pdf");
     Document result = new Document()) {
    editor.concatenate(new Document[] { d1, d2 }, result);
    result.save("merged.pdf");
}
```

Tuning flags (all optional):

```java
PdfFileEditor editor = new PdfFileEditor();
editor.setCopyOutlines(true);          // merge bookmarks (default true)
editor.setCopyLogicalStructure(false); // see Notes — partial support
editor.setOptimizeSize(true);          // compact rewrite of the output
```

### Merge with the low-level API

`concatenate` is built on `PageCollection.add(PageCollection)`. You can do the
same by hand when you want to control which document is the base or save with
specific options:

```java
try (Document base = new Document("a.pdf");
     Document extra = new Document("b.pdf")) {
    base.getPages().add(extra.getPages());   // append all pages of `extra`
    base.save("merged.pdf");
}
```

## Append pages from one document to another

To copy individual pages (rather than a whole collection) between documents, use
`PageCollection.add(Page)`. When the page belongs to a **different** document it
is automatically deep-copied into the destination (foreign pages are imported;
same-document pages are re-parented in place).

```java
try (Document target = new Document("target.pdf");
     Document source = new Document("source.pdf")) {
    // Append pages 2..4 of source to the end of target
    for (int i = 2; i <= 4; i++) {
        target.getPages().add(source.getPages().get(i));
    }
    target.save("appended.pdf");
}
```

The facade wraps exactly this in `append`, which appends a page range from
`portFile` to the end of `inputFile`:

```java
PdfFileEditor editor = new PdfFileEditor();
// Append pages 1..3 of extra.pdf to the end of base.pdf
editor.append("base.pdf", "extra.pdf", 1, 3, "out.pdf");
```

## Split a PDF into pages or ranges

`splitToPages` produces one PDF per page. The file-path overload writes
`page_1.pdf`, `page_2.pdf`, ... into an output directory; the single-argument
overload returns one `ByteArrayOutputStream` per page (kept in memory).

```java
PdfFileEditor editor = new PdfFileEditor();

// One file per page, written to ./out
editor.splitToPages("input.pdf", "out");

// One in-memory buffer per page
java.io.ByteArrayOutputStream[] perPage = editor.splitToPages("input.pdf");
for (int i = 0; i < perPage.length; i++) {
    byte[] pdfBytes = perPage[i].toByteArray();   // page i+1
}
```

Split by range or by an explicit page list with `extract`:

```java
PdfFileEditor editor = new PdfFileEditor();

// Pages 3..7 (inclusive) into a new PDF
editor.extract("input.pdf", 3, 7, "pages-3-7.pdf");

// Specific pages, in the given order
editor.extract("input.pdf", new int[] { 1, 4, 9 }, "selected.pdf");
```

Split relative to a boundary page:

```java
PdfFileEditor editor = new PdfFileEditor();

// Keep pages 1..5
editor.splitFromFirst("input.pdf", 5, "head.pdf");

// Keep pages 6..end
editor.splitToEnd("input.pdf", 6, "tail.pdf");
```

Both `splitFromFirst` and `splitToEnd` also have stream overloads:

```java
PdfFileEditor editor = new PdfFileEditor();
try (java.io.InputStream in = new java.io.FileInputStream("input.pdf");
     java.io.OutputStream out = new java.io.FileOutputStream("head.pdf")) {
    editor.splitFromFirst(in, 5, out);   // pages 1..5
}
```

## Insert, delete, and reorder pages

### Insert

`PageCollection.insert(int, Page)` inserts an existing page at a 1-based index.
`PageCollection.insert(int)` inserts a fresh blank A4 page and returns it.

```java
try (Document doc = new Document("input.pdf");
     Document donor = new Document("donor.pdf")) {
    // Insert donor page 1 as the new page 2 of doc
    doc.getPages().insert(2, donor.getPages().get(1));

    // Insert a blank page at the front
    Page blank = doc.getPages().insert(1);   // new blank A4 page, now page 1

    doc.save("out.pdf");
}
```

The facade `insert` inserts a page range from another file:

```java
PdfFileEditor editor = new PdfFileEditor();
// Insert pages 1..2 of port.pdf into input.pdf starting at position 3
editor.insert("input.pdf", 3, "port.pdf", 1, 2, "out.pdf");
```

### Delete

Delete a single page or several at once. `PageCollection.delete(int[])` and the
facade `delete` handle indices in any order, removing highest-first so earlier
removals do not shift the remaining targets; the facade additionally de-duplicates.

```java
try (Document doc = new Document("input.pdf")) {
    doc.getPages().delete(3);                       // remove page 3
    doc.getPages().delete(new int[] { 1, 5, 6 });   // remove several
    doc.getPages().clear();                         // remove all pages
    doc.save("out.pdf");
}
```

```java
PdfFileEditor editor = new PdfFileEditor();
editor.delete("input.pdf", new int[] { 2, 4 }, "out.pdf");
```

### Reorder

There is no dedicated "move page" call; reorder by adding pages to a new document
in the desired order. Because a page from another document is imported (copied),
this is a safe way to build an arbitrary permutation:

```java
try (Document src = new Document("input.pdf");
     Document out = new Document()) {
    int[] order = { 3, 1, 2 };   // new page order (1-based source indices)
    for (int i : order) {
        out.getPages().add(src.getPages().get(i));
    }
    out.save("reordered.pdf");
}
```

## Rotate a page

Page rotation is stored in the page's `/Rotate` entry (a multiple of 90). Use the
`Rotation` enum for readability, or pass degrees directly.

```java
import org.aspose.pdf.Rotation;

try (Document doc = new Document("input.pdf")) {
    Page page = doc.getPages().get(1);

    page.setRotate(Rotation.on90);   // 90 clockwise
    // equivalently: page.setRotation(90);

    int current = page.getRotate();  // 0, 90, 180, or 270

    doc.save("rotated.pdf");
}
```

`Rotation` values are `None` (0), `on90` (90), `on180` (180), and `on270` (270).
`setRotation(int)` accepts only 0, 90, 180, or 270 and throws
`IllegalArgumentException` otherwise. Rotation is an inheritable property, so a
page with no explicit `/Rotate` reports the value inherited from its page-tree
parent (default 0).

`getRotate()` returns raw degrees; `getPageRect(true)` returns the page rectangle
with rotation applied (width/height swapped for 90/270).

## Change page size / media box

A page carries several boxes. The two you usually set are the **media box** (the
physical page) and the **crop box** (the visible region). `getRect()` returns the
crop box (falling back to the media box). Rectangles use PDF user-space units
(points) with the origin at the lower-left corner.

```java
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.PageSize;

try (Document doc = new Document("input.pdf")) {
    Page page = doc.getPages().get(1);

    // Set an explicit media box (llx, lly, urx, ury)
    page.setMediaBox(new Rectangle(0, 0, 595, 842));   // A4

    // Or size the page from origin using width/height (updates the media box)
    page.setPageSize(PageSize.A4.getWidth(), PageSize.A4.getHeight());

    // Crop box can be set independently
    page.setCropBox(new Rectangle(0, 0, 595, 842));

    Rectangle media = page.getMediaBox();
    Rectangle visible = page.getRect();   // crop box (or media box)

    doc.save("resized.pdf");
}
```

`PageSize` provides constants such as `PageSize.A3`, `PageSize.A4`,
`PageSize.LETTER`, and `PageSize.LEGAL`, each exposing `getWidth()` /
`getHeight()` in points.

> Note: `setMediaBox`/`setPageSize` change the page **box**, not the content.
> Existing content keeps its coordinates, so it is not scaled to fit the new box.
> To scale the content along with the box, use `resizeContents` (below).

### Grow the page and add margins

`PdfFileEditor.addMargins` enlarges the media box and crop box by the given
margins (in points) without scaling content; the content shifts by `left` and
`bottom`. Pass `null` (or an empty array) for the page list to affect all pages.

```java
PdfFileEditor editor = new PdfFileEditor();
// Add 36pt on every side of every page
editor.addMargins("input.pdf", "out.pdf", null, 36, 36, 36, 36);
```

## Resize / scale page content

`PdfFileEditor.resizeContents` scales the content of a page into a target content
area and re-margins it, wrapping the content stream in a `q ... cm ... Q` matrix
and resizing the page box to `leftMargin + contentWidth + rightMargin` by
`topMargin + contentHeight + bottomMargin`. Annotations are transformed along with
the content. Values are given as `ContentsResizeValue` — either absolute points
(`units`) or a percentage of the page dimension (`percents`).

The `ContentsResizeParameters` constructor takes, in order:
`leftMargin, contentsWidth, rightMargin, topMargin, contentsHeight, bottomMargin`.

```java
import org.aspose.pdf.facades.PdfFileEditor;
import org.aspose.pdf.facades.PdfFileEditor.ContentsResizeParameters;
import org.aspose.pdf.facades.PdfFileEditor.ContentsResizeValue;

PdfFileEditor editor = new PdfFileEditor();

// Resize content into a 500x700pt area with 36pt margins on all sides
ContentsResizeParameters params = new ContentsResizeParameters(
        ContentsResizeValue.units(36),    // left margin
        ContentsResizeValue.units(500),   // content width
        ContentsResizeValue.units(36),    // right margin
        ContentsResizeValue.units(36),    // top margin
        ContentsResizeValue.units(700),   // content height
        ContentsResizeValue.units(36));   // bottom margin

editor.resizeContents("input.pdf", "out.pdf", params);
```

Operate on an open document or on selected pages:

```java
PdfFileEditor editor = new PdfFileEditor();
try (Document doc = new Document("input.pdf")) {
    // All pages:
    editor.resizeContents(doc, params);
    // Only pages 1 and 3:
    editor.resizeContents(doc, new int[] { 1, 3 }, params);
    doc.save("out.pdf");
}
```

Shortcut for "scale to W x H with zero margins":

```java
ContentsResizeParameters p = ContentsResizeParameters.pageResize(595, 842); // A4
```

A `null` content width or height means "compute automatically as
`pageDimension - margins`", which yields a pure re-margin (no scaling) when the
margins sum to the page size. Percentage values resolve against the page's width
(left/right/content-width) or height (top/bottom/content-height).

## Notes & limitations

- **Indices are 1-based.** `get`, `insert`, `delete`, `extract`, and page-range
  arguments all count from 1. Out-of-range indices throw
  `IndexOutOfBoundsException` in the `PageCollection` API; the facade methods
  clamp or skip out-of-range values and log instead.
- **Facade methods return `boolean`, not exceptions.** They log failures and
  return `false`. The `try*` variants (`tryConcatenate`, `tryExtract`,
  `tryDelete`, `tryAppend`, `tryInsert`, `trySplitFromFirst`, `trySplitToEnd`,
  `tryResize`, `tryMakeBooklet`, `tryMakeNUp`) capture the exception, which you
  can then read via `getLastException()`.
- **Cross-document pages are copied, not shared.** `add(Page)` / `insert(int,
  Page)` import a foreign page (deep copy into the destination's object table),
  so the source document can be closed safely afterward. Pages from the same
  document are re-parented in place.
- **`setMediaBox` / `setPageSize` do not scale content.** Use `resizeContents`
  (or `addMargins`) when the geometry must follow the box.
- **`setCopyLogicalStructure` is partial.** When enabled during `concatenate`, it
  only propagates the `/MarkInfo` "Marked=true" hint; a full cross-document
  `/StructTreeRoot` merge is not performed.
- **`makeBooklet` is implemented; `makeNUp` is not.** `makeNUp` currently logs a
  warning and returns `false`. `addPageBreak` splits a page into two halves by
  cropping the media box (both halves reference the same content), which is a
  faithful approximation rather than a content re-layout.
- **Concatenation merges attachments and (optionally) outlines.** Embedded files
  from every source are copied into the result; bookmarks are merged when
  `isCopyOutlines()` is `true` (the default) with destinations re-targeted to the
  appended page indices.

## See also

- `org.aspose.pdf.Document` — open, `getPages()`, `save(...)`, `close()`.
- `org.aspose.pdf.PageCollection` — `add`, `insert`, `delete`, `clear`, `get`,
  `getCount`/`size`, iteration.
- `org.aspose.pdf.Page` — `getMediaBox`/`setMediaBox`, `getCropBox`/`setCropBox`,
  `getRect`, `getPageRect`, `setPageSize`, `getRotate`/`setRotate`/`setRotation`.
- `org.aspose.pdf.Rotation` — `None`, `on90`, `on180`, `on270`.
- `org.aspose.pdf.PageSize` — `A1`..`A4`, `LETTER`, `LEGAL`.
- `org.aspose.pdf.Rectangle` — page-box geometry in points (lower-left origin).
- `org.aspose.pdf.facades.PdfFileEditor` — `concatenate`, `append`, `extract`,
  `splitToPages`, `splitFromFirst`, `splitToEnd`, `insert`, `delete`,
  `addMargins`, `resizeContents`, `makeBooklet`.
