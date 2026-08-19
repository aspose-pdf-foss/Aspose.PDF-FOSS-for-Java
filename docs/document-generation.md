# Generating PDFs from Scratch

This guide shows how to build a PDF document in memory — pages, paragraphs, text
fragments, tables, floating boxes, headers/footers, stamps, and watermarks — and
write it to disk. It targets **version 26.7** of the library, requires **Java 11+**,
uses the root package `org.aspose.pdf`, and has **zero third-party dependencies**.

Every code block below compiles against the public API. All page indices are
**1-based**. `Document` implements `java.io.Closeable`, so open it in a
try-with-resources block. Coordinates are in **points** (1/72 inch) with the PDF
origin at the **bottom-left** of the page.

## Create an empty document and add pages

`new Document()` builds an empty document with an in-memory catalog and an empty
page tree. `getPages()` returns the `PageCollection`; `add()` appends a new A4
page (`[0 0 595 842]`) and returns it. Use `page.setMediaBox(...)` to change the
page size.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;

public class NewDocument {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            // First A4 page (595 x 842 pt).
            Page page1 = doc.getPages().add();

            // A US-Letter page (612 x 792 pt): set the media box explicitly.
            Page page2 = doc.getPages().add();
            page2.setMediaBox(new Rectangle(0, 0, 612, 792));

            System.out.println("Pages: " + doc.getPages().getCount());
            doc.save("empty.pdf");
        }
    }
}
```

`getPages()` also offers `insert(int index)` (insert a blank page at a 1-based
position), `get(int index)`, `getCount()` / `size()`, and iteration
(`for (Page p : doc.getPages())`).

## Page size and margins with PageInfo

Each page carries a `PageInfo` (dimensions plus a `MarginInfo`). The page-level
`PageInfo` size is synced to the page's `/MediaBox` at save time. Note that the
default `PageInfo` margin is **90 pt** on every side.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageInfo;
import org.aspose.pdf.MarginInfo;

public class PageMargins {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            PageInfo info = page.getPageInfo();
            info.setWidth(595);
            info.setHeight(842);
            // Top-level MarginInfo constructor order is (left, bottom, right, top).
            info.setMargin(new MarginInfo(72, 72, 72, 72)); // 1 inch each side
            doc.save("margins.pdf");
        }
    }
}
```

> The top-level `org.aspose.pdf.MarginInfo` constructor is
> `MarginInfo(double left, double bottom, double right, double top)`. There is
> also `MarginInfo(double all)` for a uniform margin and a no-arg `MarginInfo()`
> (all zero). Prefer `PageInfo.setMargin(org.aspose.pdf.MarginInfo)` over the
> deprecated nested `PageInfo.MarginInfo`.

## Add text with paragraphs

`page.getParagraphs()` returns a `Paragraphs` collection. Anything that extends
`BaseParagraph` — `TextFragment`, `Heading`, `HtmlFragment`, `FloatingBox`, and
`Table` — can be added to it. Paragraphs added to a page are laid out into the
page's content stream when the document is saved. `Paragraphs.add(String)` is a
convenience overload that wraps the text in a `TextFragment`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.text.TextFragment;

public class SimpleText {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            // Quick text via the convenience overload.
            page.getParagraphs().add("Hello, PDF!");

            // A styled fragment added to the page flow.
            TextFragment fragment = new TextFragment("A second, right-aligned line.");
            fragment.getTextState().setFontSize(14);
            fragment.setHorizontalAlignment(HorizontalAlignment.Right);
            page.getParagraphs().add(fragment);

            doc.save("simple-text.pdf");
        }
    }
}
```

## Style text: font, size, color via TextState

`TextFragment.getTextState()` returns the `TextState` for the fragment's first
segment. `TextState` controls font (by name or a `Font` object), size, foreground
and background color, character/word spacing, underline, strikeout, and rendering
mode. Look up fonts with `FontRepository.findFont(String)`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Color;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextState;
import org.aspose.pdf.text.Font;
import org.aspose.pdf.text.FontRepository;

public class StyledText {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            TextFragment title = new TextFragment("Quarterly Report");
            TextState ts = title.getTextState();
            Font bold = FontRepository.findFont("Helvetica-Bold");
            ts.setFont(bold);
            ts.setFontSize(20);
            ts.setForegroundColor(Color.fromRgb(0.10, 0.20, 0.55)); // components 0..1
            page.getParagraphs().add(title);

            TextFragment note = new TextFragment("Highlighted note");
            note.getTextState().setBackgroundColor(Color.YELLOW);
            note.getTextState().setUnderline(true);
            page.getParagraphs().add(note);

            doc.save("styled-text.pdf");
        }
    }
}
```

`Color` offers constants (`BLACK`, `WHITE`, `RED`, `GREEN`, `BLUE`, `YELLOW`,
`GRAY`, `LIGHT_GRAY`, `TRANSPARENT`) and factories: `fromRgb(double,double,double)`
(components in 0..1), `fromRgbBytes(int,int,int)` (0..255), `fromArgb(...)`,
`fromGray(double)`, `fromCmyk(double,double,double,double)`, and
`fromHtml(String)` (e.g. `"#1A3C88"`).

## Place text at absolute coordinates

To position text exactly, build a `TextFragment` and use a `TextBuilder`, which
draws directly into the page content stream at the fragment's `Position`
(bottom-left origin, in points).

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.Position;

public class AbsoluteText {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            TextFragment stamp = new TextFragment("Bottom-left corner, 1 inch in");
            stamp.getTextState().setFontSize(10);
            stamp.setPosition(new Position(72, 72)); // x=72pt, y=72pt from bottom-left

            new TextBuilder(page).appendText(stamp);
            doc.save("absolute-text.pdf");
        }
    }
}
```

## Headings and HTML fragments

`Heading` (level 1..6) and `HtmlFragment` are also `BaseParagraph`s. `HtmlFragment`
takes a snippet of HTML markup and renders it into the page flow.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Heading;
import org.aspose.pdf.HtmlFragment;

public class HeadingsAndHtml {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            Heading h1 = new Heading(1);
            h1.setText("1. Introduction");
            h1.getTextState().setFontSize(18);
            page.getParagraphs().add(h1);

            HtmlFragment html =
                new HtmlFragment("<p>Some <b>bold</b> and <i>italic</i> text.</p>");
            page.getParagraphs().add(html);

            doc.save("headings-html.pdf");
        }
    }
}
```

## Build a table

`Table` extends `BaseParagraph`, so add it to a page's paragraphs. Set the column
layout with `setColumnWidths(String)` (space-separated widths in points, e.g.
`"120 200 80"`). Rows come from `getRows().add()`; cells from
`row.getCells().add()` or `row.getCells().add(String)`. Cells support
`setColSpan`/`setRowSpan`, alignment, borders, background, and padding.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Table;
import org.aspose.pdf.Row;
import org.aspose.pdf.Cell;
import org.aspose.pdf.BorderInfo;
import org.aspose.pdf.BorderSide;
import org.aspose.pdf.MarginInfo;
import org.aspose.pdf.Color;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.VerticalAlignment;

public class SimpleTable {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            Table table = new Table();
            table.setColumnWidths("200 120 80");
            // Black 0.5pt border on every cell, plus a border around the table.
            table.setDefaultCellBorder(new BorderInfo(BorderSide.All, 0.5f, Color.BLACK));
            table.setBorder(new BorderInfo(BorderSide.All, 1f, Color.BLACK));
            table.setDefaultCellPadding(new MarginInfo(4, 4, 4, 4));

            // Header row.
            Row header = table.getRows().add();
            header.setBackgroundColor(Color.LIGHT_GRAY);
            header.getCells().add("Item");
            header.getCells().add("Category");
            Cell qtyHead = header.getCells().add("Qty");
            qtyHead.setAlignment(HorizontalAlignment.Right);

            // A data row with a right-aligned, vertically-centered quantity.
            Row row = table.getRows().add();
            row.getCells().add("Widget");
            row.getCells().add("Hardware");
            Cell qty = row.getCells().add("12");
            qty.setAlignment(HorizontalAlignment.Right);
            qty.setDefaultCellTextStateVerticalAlignment(VerticalAlignment.Center);

            // A full-width note cell spanning all three columns.
            Row noteRow = table.getRows().add();
            Cell note = noteRow.getCells().add("Prices exclude tax.");
            note.setColSpan(3);
            note.setBackgroundColor(Color.fromRgb(0.96, 0.96, 0.90));

            page.getParagraphs().add(table);
            doc.save("table.pdf");
        }
    }
}
```

`BorderInfo` has constructors `BorderInfo()`,
`BorderInfo(BorderSide, double width)`,
`BorderInfo(BorderSide, double width, Color)`, and one taking a `GraphInfo`.
`BorderSide` values: `None`, `All`, `Top`, `Bottom`, `Left`, `Right`.
`HorizontalAlignment`: `None`, `Left`, `Center`, `Right`, `Justify`.
`VerticalAlignment`: `None`, `Top`, `Center`, `Bottom`.

## Position content with a FloatingBox

A `FloatingBox` is a fixed-size container you place with `setLeft`/`setTop`
(offsets in points). Fill it via its own `getParagraphs()`; it can have a
background color, border, and padding.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.FloatingBox;
import org.aspose.pdf.BorderInfo;
import org.aspose.pdf.BorderSide;
import org.aspose.pdf.Color;
import org.aspose.pdf.MarginInfo;
import org.aspose.pdf.text.TextFragment;

public class FloatingBoxDemo {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            FloatingBox box = new FloatingBox(220, 90); // width x height in points
            box.setLeft(300);
            box.setTop(60);
            box.setBackgroundColor(Color.fromRgb(0.93, 0.96, 1.0));
            box.setBorder(new BorderInfo(BorderSide.All, 1f, Color.BLUE));
            box.setPadding(new MarginInfo(6, 6, 6, 6));

            box.getParagraphs().add(new TextFragment("Callout box content"));
            page.getParagraphs().add(box);

            doc.save("floating-box.pdf");
        }
    }
}
```

## Headers and footers

A `HeaderFooter` carries a `Paragraphs` collection and a margin. Attach one with
`page.setHeader(...)` / `page.setFooter(...)`.

For a document loaded from disk, call
`page.applyHeaderFooterOverlay(pageNumber, totalPages)` before saving to render
the header/footer as a Form XObject overlay (`$p` / `$P` substitution is applied
by the overlay renderer). For a freshly-built document, headers and footers are
rendered during `save()` as part of the page layout pass.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.HeaderFooter;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.text.TextFragment;

public class HeaderFooterDemo {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getParagraphs().add(new TextFragment("Body content."));

            HeaderFooter header = new HeaderFooter();
            TextFragment htext = new TextFragment("Confidential");
            htext.setHorizontalAlignment(HorizontalAlignment.Center);
            header.getParagraphs().add(htext);
            page.setHeader(header);

            HeaderFooter footer = new HeaderFooter();
            footer.getParagraphs().add(new TextFragment("© 2026 Example Corp."));
            page.setFooter(footer);

            doc.save("header-footer.pdf");
        }
    }
}
```

## Text, image, and page-number stamps

Stamps overlay content onto an existing page. Apply one with `page.addStamp(...)`.
All stamps share position (`setXIndent`/`setYIndent`), alignment
(`setHorizontalAlignment`/`setVerticalAlignment`), margins, `setOpacity(0..1)`,
`setRotateAngle(degrees)`, `setZoom`, and `setBackground(boolean)` (background
stamps are prepended behind existing content).

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.TextStamp;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.PageNumberStamp;
import org.aspose.pdf.Color;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.VerticalAlignment;

public class Stamps {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            // Text stamp, semi-transparent, rotated 45 degrees, centered.
            TextStamp text = new TextStamp("APPROVED");
            text.getTextState().setFontSize(36);
            text.getTextState().setForegroundColor(Color.GREEN);
            text.setOpacity(0.4);
            text.setRotateAngle(45);
            text.setHorizontalAlignment(HorizontalAlignment.Center);
            text.setVerticalAlignment(VerticalAlignment.Center);
            page.addStamp(text);

            // Image stamp at a fixed size and position.
            ImageStamp image = new ImageStamp("logo.png");
            image.setWidth(120);
            image.setHeight(48);
            image.setXIndent(40);
            image.setYIndent(720);
            page.addStamp(image);

            // Page-number stamp. addStamp draws the stamp's raw value; substitute
            // the '#'/'$P' tokens yourself for the current page.
            PageNumberStamp pn = new PageNumberStamp("Page # of $P");
            pn.setValue(pn.formatPageNumber(0, doc.getPages().getCount()));
            pn.setVerticalAlignment(VerticalAlignment.Bottom);
            pn.setHorizontalAlignment(HorizontalAlignment.Center);
            page.addStamp(pn);

            doc.save("stamps.pdf");
        }
    }
}
```

> `ImageStamp` accepts a file path (`new ImageStamp(String)`) or an
> `InputStream` of encoded JPEG/PNG/BMP/GIF bytes; the bytes are cached so one
> stamp can be applied to several pages. `PageNumberStamp` extends `TextStamp`;
> `formatPageNumber(pageIndex, totalPages)` replaces `#` with
> `pageIndex + startingNumber` and `$P` with `totalPages`. `page.addStamp(TextStamp)`
> renders the stamp's raw `getValue()` string, so call `setValue(...)` per page
> to get a live page number (see the limitation note below).

## Watermarks and page backgrounds (artifacts)

`WatermarkArtifact` and `BackgroundArtifact` are added through
`page.getArtifacts().add(...)`. A watermark renders centered, rotated,
semi-transparent text; a background fills the page's media box with a solid color.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.WatermarkArtifact;
import org.aspose.pdf.BackgroundArtifact;
import org.aspose.pdf.Color;
import org.aspose.pdf.text.TextFragment;

public class Watermark {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getParagraphs().add(new TextFragment("Draft document body."));

            // Solid page background behind all content.
            page.getArtifacts().add(new BackgroundArtifact(Color.fromRgb(0.98, 0.98, 0.94)));

            // Centered, rotated, faded "DRAFT" text watermark.
            WatermarkArtifact wm = new WatermarkArtifact("DRAFT");
            wm.setFont("Helvetica-Bold", 72);
            wm.setColor(Color.fromRgb(0.7, 0.0, 0.0));
            wm.setWatermarkOpacity(0.20);
            wm.setWatermarkRotation(45);
            page.getArtifacts().add(wm);

            doc.save("watermark.pdf");
        }
    }
}
```

You can also set a solid page background directly with `page.setBackground(Color)`
(or `doc.setBackground(Color)` for every page) — passing `Color.WHITE` or `null`
removes it.

## A complete invoice-like example

This ties the pieces together: a header, a title, a line-item table, a totals
`FloatingBox`, a footer, and a page-number stamp.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.HeaderFooter;
import org.aspose.pdf.Table;
import org.aspose.pdf.Row;
import org.aspose.pdf.Cell;
import org.aspose.pdf.FloatingBox;
import org.aspose.pdf.BorderInfo;
import org.aspose.pdf.BorderSide;
import org.aspose.pdf.MarginInfo;
import org.aspose.pdf.Color;
import org.aspose.pdf.HorizontalAlignment;
import org.aspose.pdf.PageNumberStamp;
import org.aspose.pdf.VerticalAlignment;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.FontRepository;

public class Invoice {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();

            // Header.
            HeaderFooter header = new HeaderFooter();
            TextFragment company = new TextFragment("ACME Corporation");
            company.getTextState().setFont(FontRepository.findFont("Helvetica-Bold"));
            company.getTextState().setFontSize(16);
            header.getParagraphs().add(company);
            page.setHeader(header);

            // Title.
            TextFragment title = new TextFragment("INVOICE #2026-0042");
            title.getTextState().setFontSize(22);
            title.getTextState().setForegroundColor(Color.fromRgb(0.10, 0.20, 0.55));
            page.getParagraphs().add(title);

            // Line-item table.
            Table table = new Table();
            table.setColumnWidths("240 90 90 90");
            table.setDefaultCellBorder(new BorderInfo(BorderSide.All, 0.5f, Color.GRAY));
            table.setDefaultCellPadding(new MarginInfo(5, 5, 5, 5));

            Row head = table.getRows().add();
            head.setBackgroundColor(Color.LIGHT_GRAY);
            head.getCells().add("Description");
            for (String h : new String[]{"Unit", "Qty", "Amount"}) {
                Cell c = head.getCells().add(h);
                c.setAlignment(HorizontalAlignment.Right);
            }

            String[][] items = {
                {"Consulting services", "150.00", "10", "1500.00"},
                {"Software license",    "300.00", "2",  "600.00"},
                {"Support (annual)",    "200.00", "1",  "200.00"},
            };
            for (String[] item : items) {
                Row r = table.getRows().add();
                r.getCells().add(item[0]);
                for (int i = 1; i < item.length; i++) {
                    Cell c = r.getCells().add(item[i]);
                    c.setAlignment(HorizontalAlignment.Right);
                }
            }
            page.getParagraphs().add(table);

            // Totals box positioned to the right.
            FloatingBox totals = new FloatingBox(200, 40);
            totals.setLeft(320);
            totals.setTop(360);
            totals.setBorder(new BorderInfo(BorderSide.All, 1f, Color.BLACK));
            totals.setPadding(new MarginInfo(6, 6, 6, 6));
            TextFragment total = new TextFragment("TOTAL DUE: $2300.00");
            total.getTextState().setFont(FontRepository.findFont("Helvetica-Bold"));
            total.getTextState().setFontSize(13);
            totals.getParagraphs().add(total);
            page.getParagraphs().add(totals);

            // Footer.
            HeaderFooter footer = new HeaderFooter();
            footer.getParagraphs().add(
                new TextFragment("Thank you for your business."));
            page.setFooter(footer);

            // Page-number stamp.
            PageNumberStamp pn = new PageNumberStamp("Page # of $P");
            pn.setValue(pn.formatPageNumber(0, doc.getPages().getCount()));
            pn.setHorizontalAlignment(HorizontalAlignment.Right);
            pn.setVerticalAlignment(VerticalAlignment.Bottom);
            page.addStamp(pn);

            doc.save("invoice.pdf");
        }
    }
}
```

## Notes & limitations

- **Coordinates and units.** All positions and sizes are in points (1/72 inch).
  The PDF coordinate origin is the bottom-left corner of the page.
- **Page indices are 1-based.** `getPages().get(1)` is the first page;
  `insert(index)` uses a 1-based index.
- **Paragraph layout runs at save time.** Paragraphs added via
  `page.getParagraphs().add(...)` are flowed into the content stream during
  `save()`. `doc.processParagraphs()` exists for Aspose API compatibility but is a
  no-op — the layout still happens on save.
- **Default `PageInfo` margin is 90 pt** on every side; set `PageInfo.setMargin`
  (with the top-level `MarginInfo`) if you need different margins.
- **`PageNumberStamp` does not auto-number pages.** `page.addStamp(TextStamp)`
  draws the stamp's raw `getValue()` text; the `#`/`$P` tokens are only expanded
  by `formatPageNumber(pageIndex, totalPages)`, which you must call and feed back
  via `setValue(...)` on a per-page basis. There is no automatic per-page token
  substitution in the stamp path.
- **`WatermarkArtifact` centering is approximate.** The synthesized watermark
  horizontally centers text using an estimate of the string width rather than
  exact glyph metrics, so very long watermark strings may be slightly off-center.
- **Header/footer on loaded vs. new documents.** New documents render
  headers/footers during `save()`. For documents opened from an existing file,
  call `page.applyHeaderFooterOverlay(pageNumber, totalPages)` before saving —
  it is idempotent across repeated saves.
- **Fonts.** Use `FontRepository.findFont(String)` for the Standard-14 base fonts
  (e.g. `"Helvetica"`, `"Helvetica-Bold"`, `"Times-Roman"`, `"Courier"`). To embed
  a font program, load it with `FontRepository.openFont(...)` and assign it via
  `TextState.setFont(Font)`; set `doc.setEmbedStandardFonts(true)` to also embed
  the standard fonts.
- **Always close the document** (try-with-resources) to release parser resources.

## See also

- [Extracting and searching text](./text-extraction.md)
- [Working with tables](./tables.md)
- [Annotations and stamps](./annotations.md)
- [Saving and export formats](./saving.md)
