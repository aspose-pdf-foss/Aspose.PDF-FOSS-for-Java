# Navigation: Bookmarks, Destinations, Actions, and Links

This guide covers document navigation with the FOSS PDF library (version 26.7,
Java 11+, root package `org.aspose.pdf`, zero third-party dependencies):

- **Bookmarks (outlines)** — the collapsible tree shown in a viewer's sidebar.
- **Destinations** — a view of a page (which page, and how it is displayed).
- **Actions** — what happens when a bookmark or link is activated (go to a page,
  open a URI, hide an annotation, etc.).
- **Link annotations** — clickable regions on a page carrying an action or
  destination.

All page indices are **1-based**. Documents are `AutoCloseable`; the examples
use try-with-resources.

## The outline (bookmark) tree

`Document.getOutlines()` returns an `OutlineCollection` — the root of the
bookmark tree (the catalog's `/Outlines` dictionary). Each node is an
`OutlineItemCollection`, which is both a single bookmark and a container for its
child bookmarks. Both types are `Iterable`, and their `get(int)` /
`delete(int)` methods are 1-based.

## Read the outline tree

`OutlineCollection` and `OutlineItemCollection` both implement
`Iterable<OutlineItemCollection>`, so you can walk the tree recursively. Each
item exposes `getTitle()`, `getLevel()` (1-based nesting depth), `getDestination()`
(an `ExplicitDestination` or `null`), and `getAction()`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.OutlineCollection;
import org.aspose.pdf.OutlineItemCollection;
import org.aspose.pdf.ExplicitDestination;

public class ReadOutline {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            OutlineCollection outlines = doc.getOutlines();
            System.out.println("Top-level bookmarks: " + outlines.getCount());
            for (OutlineItemCollection item : outlines) {
                print(item);
            }
        }
    }

    private static void print(OutlineItemCollection item) throws Exception {
        StringBuilder indent = new StringBuilder();
        for (int i = 1; i < item.getLevel(); i++) indent.append("  ");

        ExplicitDestination dest = item.getDestination();
        String page = (dest != null) ? " -> page " + dest.getPageNumber() : "";
        System.out.println(indent + item.getTitle() + page);

        // Recurse into children (OutlineItemCollection is itself Iterable).
        for (OutlineItemCollection child : item) {
            print(child);
        }
    }
}
```

## Add a bookmark that points to a page

Create an `OutlineItemCollection` bound to the root `OutlineCollection`, set its
title and destination, then add it. A destination is an `ExplicitDestination`
subclass — for example `XYZExplicitDestination` (position + zoom) or
`FitExplicitDestination` (scale the whole page to fit).

`XYZExplicitDestination(Page page, double left, double top, double zoom)` accepts
`Double.NaN` for `left`/`top` ("keep current value") and `0` or `NaN` for `zoom`
("keep current zoom").

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.OutlineCollection;
import org.aspose.pdf.OutlineItemCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.XYZExplicitDestination;
import org.aspose.pdf.FitExplicitDestination;

public class AddBookmark {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            OutlineCollection outlines = doc.getOutlines();
            Page page3 = doc.getPages().get(3);

            // Bookmark 1: XYZ destination (top-left of page 3, keep zoom).
            OutlineItemCollection intro = new OutlineItemCollection(outlines);
            intro.setTitle("Introduction");
            intro.setDestination(
                    new XYZExplicitDestination(page3, Double.NaN, Double.NaN, 0));
            outlines.add(intro);

            // Bookmark 2: "Fit page" destination, styled bold.
            OutlineItemCollection summary = new OutlineItemCollection(outlines);
            summary.setTitle("Summary");
            summary.setBold(true);
            summary.setDestination(new FitExplicitDestination(doc.getPages().get(5)));
            outlines.add(summary);

            doc.save("bookmarks.pdf");
        }
    }
}
```

Each outline item also supports visual styling: `setBold(boolean)`,
`setItalic(boolean)`, `setColor(Color)`, and `setOpen(boolean)` (whether its
children are expanded by default).

## Add a nested (child) bookmark

`OutlineItemCollection.add(OutlineItemCollection)` attaches a child to a
bookmark, building the hierarchy. The library maintains the `/First`, `/Last`,
`/Next`, `/Prev`, `/Parent`, and `/Count` links for you.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.OutlineCollection;
import org.aspose.pdf.OutlineItemCollection;
import org.aspose.pdf.XYZExplicitDestination;

public class NestedBookmark {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            OutlineCollection outlines = doc.getOutlines();

            OutlineItemCollection chapter = new OutlineItemCollection(outlines);
            chapter.setTitle("Chapter 1");
            chapter.setDestination(new XYZExplicitDestination(
                    doc.getPages().get(1), Double.NaN, Double.NaN, 0));
            outlines.add(chapter);

            OutlineItemCollection section = new OutlineItemCollection(outlines);
            section.setTitle("1.1 Overview");
            section.setDestination(new XYZExplicitDestination(
                    doc.getPages().get(2), Double.NaN, Double.NaN, 0));
            chapter.add(section);          // nested under "Chapter 1"

            System.out.println("Chapter children: " + chapter.getCount());
            doc.save("nested.pdf");
        }
    }
}
```

## Explicit destinations

`ExplicitDestination` is the abstract base; every subclass mirrors a PDF
destination syntax (ISO 32000-1, Table 151). All are verified to exist and take
a `Page` as the first constructor argument:

| Class | Public constructor (beyond `Page`) | Meaning |
|---|---|---|
| `XYZExplicitDestination` | `(Page, left, top, zoom)` | Position upper-left corner + zoom. `NaN` = keep current. |
| `FitExplicitDestination` | `(Page)` | Fit the whole page in the window. |
| `FitHExplicitDestination` | `(Page, top)` | Fit page width; position vertically at `top`. |
| `FitVExplicitDestination` | `(Page, left)` | Fit page height; position horizontally at `left`. |
| `FitRExplicitDestination` | `(Page, left, bottom, right, top)` | Fit the given rectangle. |
| `FitBExplicitDestination` | `(Page)` | Fit the page's bounding box. |
| `FitBHExplicitDestination` | `(Page, top)` | Fit bounding-box width at `top`. |
| `FitBVExplicitDestination` | `(Page, left)` | Fit bounding-box height at `left`. |

Common accessors: `getPage()`, `getPageNumber()` (1-based). `XYZExplicitDestination`
additionally exposes `getLeft()`, `getTop()`, and `getZoom()` (returns `0.0` when
unspecified). `XYZExplicitDestination` also has a page-number constructor
`(int pageNumber, double left, double top, double zoom)` for when you do not yet
hold a `Page` object.

```java
import org.aspose.pdf.*;

Page p = doc.getPages().get(2);
ExplicitDestination fit   = new FitExplicitDestination(p);
ExplicitDestination width = new FitHExplicitDestination(p, 780);      // top y = 780
ExplicitDestination rect  = new FitRExplicitDestination(p, 50, 50, 550, 750);
ExplicitDestination xyz   = new XYZExplicitDestination(p, 0, 792, 1.5); // 150% zoom
```

## Named destinations

A named destination is a stable label (resolved through the catalog's
`/Names → /Dests` name tree) that other objects can reference instead of
embedding page coordinates. `Document.getNamedDestinations()` returns a
`NamedDestinations` accessor.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.NamedDestinations;
import org.aspose.pdf.XYZExplicitDestination;

public class NamedDests {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            NamedDestinations dests = doc.getNamedDestinations();

            // Register a named destination.
            dests.add("chapter1", new XYZExplicitDestination(
                    doc.getPages().get(1), Double.NaN, Double.NaN, 0));

            // Enumerate and resolve.
            for (String name : dests.getNames()) {
                System.out.println(name + " -> page "
                        + dests.get(name).getPageNumber());
            }
            doc.save("named.pdf");
        }
    }
}
```

`NamedDestinations` methods: `add(name, dest)` / `set(name, dest)` (both write to
the name tree; `set` replaces), `get(name)` → resolved `ExplicitDestination` or
`null`, `remove(name)` → `boolean`, `getNames()` → `List<String>`,
`getNamesArray()` → `String[]`, and `getCount()` / `size()`.

The `NamedDestination` class (distinct from `NamedDestinations`) is a *reference*
you can attach to a `GoToAction` or an outline item; it resolves lazily at use
time via `resolve()`. Construct it as `new NamedDestination(document, name)`.
Both `ExplicitDestination` and `NamedDestination` implement the `IAppointment`
marker interface, so `OutlineItemCollection.setDestination(IAppointment)` and
`GoToAction.setDestination(IAppointment)` accept either kind.

## Actions

`PdfAction` is the abstract base. Its factory `PdfAction.fromDictionary(dict, doc)`
maps the `/S` type to a concrete subclass; `getType()` returns the type name
(`"GoTo"`, `"URI"`, `"Hide"`, ...). The subclasses you can *construct* directly:

| Class | Constructor(s) | Purpose |
|---|---|---|
| `GoToAction` | `(Page)`, `(ExplicitDestination)`, `(NamedDestination)`, `()` | Jump within this document. |
| `UriAction` | `(String uri)` | Open a URI. |
| `GoToURIAction` | `(String uri)` | Aspose-named subclass of `UriAction`; `getURI()`/`setURI()`. |
| `GoToRemoteAction` | `(String file, int pageNumber)`, `(String file, ExplicitDestination)` | Jump into another PDF file. |
| `HideAction` | `(String name, boolean hide)`, `(String[] names, boolean hide)` | Show/hide named annotations. |

`GoToAction` exposes `getDestination()` (resolved `ExplicitDestination`),
`getAppointment()` (the raw `ExplicitDestination` or `NamedDestination`), and
`setDestination(IAppointment)`.

The remaining action types — `GoToEmbeddedAction`, `GenericAction`,
`NamedAction`, `LaunchAction`, `JavaScriptAction`, `SubmitFormAction`,
`ResetFormAction`, `ImportDataAction`, `SetOCGStateAction`, `RenditionAction`,
`TransitionAction` — are recognized and returned by the parser but are read
oriented: `GoToEmbeddedAction` and `GenericAction`, for instance, expose only a
`(PdfDictionary)` constructor, so they are produced when reading a document
rather than built from scratch. Check the specific class before relying on a
constructor.

### Document-level actions

`Document.getActions()` returns a `DocumentActions` view over the catalog's
`/OpenAction` and `/AA` (additional-actions) entries:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.DocumentActions;
import org.aspose.pdf.GoToAction;

try (Document doc = new Document("input.pdf")) {
    DocumentActions actions = doc.getActions();
    // Run when the document opens: jump to page 1.
    actions.setOpenAction(new GoToAction(doc.getPages().get(1)));
    doc.save("openaction.pdf");
}
```

`DocumentActions` also has `getBeforeClosing`/`setBeforeClosing`,
`getBeforeSaving`/`setBeforeSaving`, `getAfterSaving`/`setAfterSaving`,
`getBeforePrinting`/`setBeforePrinting`, and `getAfterPrinting`/`setAfterPrinting`.
Passing `null` removes the entry.

## Link annotations

A `LinkAnnotation` (in `org.aspose.pdf.annotations`) is a clickable rectangle on
a page. Construct it with `new LinkAnnotation(Page, Rectangle)`, attach an action
with `setAction(PdfAction)`, and add it to the page via
`page.getAnnotations().add(...)`. `Rectangle(llx, lly, urx, ury)` uses PDF user
space (origin at the lower-left of the page).

Note: `LinkAnnotation` has no `setDestination` setter — attach navigation with a
`GoToAction` (internal) or a URI action (external). Reading back is supported via
`getAction()` and `getDestination(Document)`.

### External link (open a URI)

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.GoToURIAction;
import org.aspose.pdf.annotations.LinkAnnotation;

public class UriLink {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            var page = doc.getPages().get(1);

            LinkAnnotation link =
                    new LinkAnnotation(page, new Rectangle(72, 700, 300, 720));
            link.setAction(new GoToURIAction("https://example.com"));
            page.getAnnotations().add(link);

            doc.save("uri-link.pdf");
        }
    }
}
```

### Internal link (GoTo another page)

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.GoToAction;
import org.aspose.pdf.XYZExplicitDestination;
import org.aspose.pdf.annotations.LinkAnnotation;

public class InternalLink {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf")) {
            var page1 = doc.getPages().get(1);
            var page3 = doc.getPages().get(3);

            LinkAnnotation link =
                    new LinkAnnotation(page1, new Rectangle(72, 660, 300, 680));
            // GoToAction with an explicit XYZ destination on page 3.
            link.setAction(new GoToAction(
                    new XYZExplicitDestination(page3, Double.NaN, Double.NaN, 0)));
            page1.getAnnotations().add(link);

            doc.save("internal-link.pdf");
        }
    }
}
```

## Facade: PdfBookmarkEditor

`org.aspose.pdf.facades.PdfBookmarkEditor` offers convenience methods for common
bookmark tasks. It is `AutoCloseable`; bind a document (`bindPdf`), operate, then
`save`.

Verified methods include:

- `createBookmarkOfPage(String title, int pageNumber)` — add one bookmark to a
  page (backed by a `GoToAction`).
- `createBookmarks()` / `createBookmarks(Color, boolean bold, boolean italic)` —
  add a "Page N" bookmark for every page, optionally styled.
- `extractBookmarks()` / `extractBookmarks(String title)` — read the tree into a
  `Bookmarks` collection.
- `deleteBookmarks()` / `deleteBookmarks(String title)` — remove all, or by title.
- `exportBookmarksToXML(String xmlFile)` / `importBookmarksWithXML(String xmlFile)`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.facades.PdfBookmarkEditor;

public class BookmarkFacade {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("input.pdf");
             PdfBookmarkEditor editor = new PdfBookmarkEditor()) {
            editor.bindPdf(doc);
            editor.createBookmarkOfPage("Cover", 1);
            editor.createBookmarkOfPage("Appendix", doc.getPages().getCount());
            editor.save("with-bookmarks.pdf");
        }
    }
}
```

Note: `PdfBookmarkEditor.close()` closes the bound document. When you also wrap
the `Document` in try-with-resources (as above), the second `close()` is a
harmless no-op; if you prefer, bind by file path (`editor.bindPdf("input.pdf")`)
and let the editor own the document lifecycle.

## Notes & limitations

- **1-based indices.** `outlines.get(1)` is the first bookmark; page numbers from
  `getPageNumber()` and `doc.getPages().get(n)` are 1-based.
- **Destination vs. action are mutually exclusive on an outline item.**
  `OutlineItemCollection.setDestination(...)` clears any `/A` action, and
  `setAction(...)` clears any `/Dest`. Set one or the other.
- **`LinkAnnotation` has no destination setter.** Use `setAction(new GoToAction(...))`
  for internal jumps; the getters `getAction()` and `getDestination(Document)`
  read either form.
- **Some action types are read-only.** The parser recognizes all standard action
  types, but several (e.g. `GoToEmbeddedAction`, `GenericAction`) only expose a
  dictionary constructor and are intended for reading existing documents, not for
  construction. Verify the constructor on the specific class before using it.
- **Named-destination storage.** `NamedDestinations.add`/`set` write to the
  modern `/Names → /Dests` name tree. Lookups (`get`, `getNames`) also read the
  legacy catalog `/Dests` dictionary for compatibility.
- **Save to persist.** Bookmark, destination, action, and annotation changes live
  in memory until `doc.save(...)`.

## See also

- `org.aspose.pdf.Document` — `getOutlines()`, `getNamedDestinations()`, `getActions()`.
- `org.aspose.pdf.OutlineCollection`, `org.aspose.pdf.OutlineItemCollection`.
- `org.aspose.pdf.ExplicitDestination` and its `Fit*`/`XYZ` subclasses.
- `org.aspose.pdf.NamedDestinations`, `org.aspose.pdf.NamedDestination`.
- `org.aspose.pdf.PdfAction` and subclasses (`GoToAction`, `UriAction`,
  `GoToURIAction`, `GoToRemoteAction`, `HideAction`, ...).
- `org.aspose.pdf.DocumentActions`.
- `org.aspose.pdf.annotations.LinkAnnotation`.
- `org.aspose.pdf.facades.PdfBookmarkEditor`.
