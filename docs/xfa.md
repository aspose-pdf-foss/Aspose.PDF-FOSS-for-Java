# XFA Forms

XFA (XML Forms Architecture) forms store their field definitions and data as XML
packets inside a PDF's `/AcroForm` dictionary under the `/XFA` entry. This library
lets you detect an XFA document, read and fill XFA field values, convert the XFA
form into ordinary editable AcroForm fields, render/paginate the XFA layout onto
PDF pages, and flatten a form.

All types below are in `org.aspose.pdf` and its `org.aspose.pdf.forms` /
`org.aspose.pdf.forms.xfa` subpackages. Version 26.7; Java 11+; zero third-party
dependencies. XFA field paths use the dotted SOM (Scripting Object Model) syntax,
for example `form1.Page1.TextField1` or the fully-indexed
`form1[0].Page1[0].TextField1[0]` — both are accepted.

The public entry point is `Form.getXFA()`, which returns an
`org.aspose.pdf.forms.xfa.XfaForm` (or `null` when the document has no `/XFA`
entry).

## Detect an XFA document

`Form.getType()` returns a `Form.FormType`. It reports `FormType.XFA` when the
AcroForm dictionary carries an `/XFA` entry, and `FormType.Standard` otherwise.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.forms.xfa.XfaForm;

try (Document doc = new Document("xfa-form.pdf")) {
    Form form = doc.getForm();

    if (form.getType() == Form.FormType.XFA) {
        System.out.println("This document contains an XFA form.");
    }

    // getXFA() returns null when there is no /XFA entry.
    XfaForm xfa = form.getXFA();
    if (xfa != null) {
        System.out.println("XFA packets loaded.");
    }
}
```

`Form.FormType` also defines `Static` (XFA foreground over a PDF background) and
`Dynamic` (fully XFA-driven layout), but `getType()` only distinguishes `Standard`
vs `XFA` — it does not classify static vs dynamic on its own.

## Read XFA field values

`XfaForm.get(String fieldName)` returns the field's value from the XFA `datasets`
packet as a `String`, or `null` when the field has no data node. Use
`XfaForm.getFieldNames()` to enumerate the field paths declared in the XFA
`template` (dotted, without indices).

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.forms.xfa.XfaForm;

try (Document doc = new Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa == null) {
        return; // not an XFA document
    }

    for (String name : xfa.getFieldNames()) {
        String value = xfa.get(name);
        System.out.println(name + " = " + (value == null ? "<empty>" : value));
    }
}
```

You can also inspect the raw XML packets as W3C DOM documents:
`getTemplate()`, `getDatasets()`, `getForm()`, `getConfig()`, and the assembled
`getXDP()`. `getFieldTemplate(String fieldName)` returns the `<field>` (or
`<exclGroup>`) template node for a given path, and `getNamespaceManager()` returns
an `XfaNamespaceContext` for XPath queries over those documents.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.xfa.XfaForm;
import org.w3c.dom.Document; // note: distinct from org.aspose.pdf.Document

try (org.aspose.pdf.Document doc = new org.aspose.pdf.Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa != null) {
        org.w3c.dom.Document template = xfa.getTemplate();
        org.w3c.dom.Document datasets = xfa.getDatasets();
        // Traverse the DOM directly, or query it with getNamespaceManager().
    }
}
```

## Fill (set) XFA field values

`XfaForm.set(String fieldName, String value)` writes a value into the XFA
`datasets` packet, creating the leaf data node when it does not yet exist, and
writes the modified packet back into the PDF's `/XFA` entry. Save the document to
persist the change.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.xfa.XfaForm;

try (Document doc = new Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa != null) {
        xfa.set("form1.Page1.CustomerName", "Jane Doe");
        xfa.set("form1.Page1.OrderTotal", "1250.00");

        // Repeated rows are addressed with explicit instance indices:
        xfa.set("form1.Page1.body[1].item", "Widget");
    }
    doc.save("xfa-form-filled.pdf");
}
```

Image fields are filled with `setFieldImage(String fieldName, InputStream)`, which
Base64-encodes the image bytes and stores them in the datasets XML:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.xfa.XfaForm;

import java.io.FileInputStream;
import java.io.InputStream;

try (Document doc = new Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa != null) {
        try (InputStream img = new FileInputStream("signature.png")) {
            xfa.setFieldImage("form1.Page1.Signature", img);
        }
    }
    doc.save("xfa-form-with-image.pdf");
}
```

Because a `set(...)` mutates a stream inside the `/XFA` entry, the save path
detects the dirty XFA packet (`XfaForm.hasDirtyPackets()`) and forces a full
rewrite of the file so the change reloads reliably — this is handled
automatically by `Document.save`, you do not call it yourself.

## Scripting (JavaScript & FormCalc)

XFA calculations and initialization written in JavaScript or FormCalc are executed
**implicitly** during conversion and rendering, not through a public scripting
call. When you convert or paginate an XFA form (see below), the library runs the
load-time scripts (`initialize` / `calculate` / `ready`), resolves script-driven
subform occurrences, and applies script-computed presence and values so the output
matches how Adobe would render it. There is **no public API to trigger or evaluate
an arbitrary XFA script directly**; the JavaScript interpreter and FormCalc engine
live in internal `org.aspose.pdf.engine.*` packages.

Script execution during the paint/convert pipeline is on by default and can be
disabled with the JVM system property `-Dxfa.runScripts=false`:

```
java -Dxfa.runScripts=false -cp aspose-pdf.jar MyApp
```

Related opt-out switches used by the same pipeline (all default on): `-Dxfa.formPacket=false`
(do not backfill values from the saved `<form>` packet), `-Dxfa.barcodeFallback=false`
(do not seed empty 2D-barcode fields), and `-Dxfa.convertRenderDom=false` (fall
back to a plain template+data merge with no scripts for conversion).

## Convert XFA to AcroForm

Most non-Adobe PDF viewers cannot render XFA. Converting the XFA form into ordinary
**editable** AcroForm fields makes the form and its data display in any viewer. The
public way to do this is `Form.setType(Form.FormType.Standard)`: it runs the data
binding, maps each XFA field to the matching AcroForm field type at its laid-out
position, paints static content, paginates dynamic forms, and removes the `/XFA`
entry.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;

try (Document doc = new Document("xfa-form.pdf")) {
    Form form = doc.getForm();
    if (form.getType() == Form.FormType.XFA) {
        form.setType(Form.FormType.Standard);   // XFA -> editable AcroForm, /XFA dropped
    }
    doc.save("acroform.pdf");
}
```

The produced fields remain **editable** form fields — this is a conversion, not a
flatten. If binding cannot run (for example the document has no template packet),
`setType` still removes the `/XFA` entry so the form degrades to a plain AcroForm
rather than staying an unrenderable XFA.

If you need the conversion result details (how many fields were added, values
carried, geometry resolved vs placed at a fallback position, and any unmapped
nodes), call `XfaForm.convertToAcroForm(Document)` directly. It returns an
`XfaFlattener.Result` with public counters such as `fieldsAdded`,
`boundValuesCarried`, `geometryResolved`, `geometryFallback`, the `byType` map, and
the `unmapped` list.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.xfa.XfaForm;
import org.aspose.pdf.engine.xfa.flatten.XfaFlattener;

try (Document doc = new Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa != null) {
        XfaFlattener.Result result = xfa.convertToAcroForm(doc); // DROP policy: /XFA removed
        System.out.println("Fields added:   " + result.fieldsAdded);
        System.out.println("Values carried: " + result.boundValuesCarried);
        System.out.println("Unmapped nodes: " + result.unmapped.size());
    }
    doc.save("acroform.pdf");
}
```

A two-argument overload `convertToAcroForm(Document, XfaFlattener.XfaPolicy)` lets
you choose `XfaPolicy.DROP` (pure AcroForm, `/XFA` removed — the default) or
`XfaPolicy.KEEP` (hybrid document that retains the `/XFA` entry).

## Render / paginate the XFA layout onto PDF pages

`XfaForm.paintPaginatedContent(Document)` lays the XFA form out across as many
pages as it needs and paints the static content and field values onto the
document's pages. A flowed (dynamic) form is split into pages; a positioned form
authored as page-sized subforms emits one page per subform. It returns an
`XfaPaginator.Result` describing the page count and mode.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.xfa.XfaForm;

try (Document doc = new Document("xfa-form.pdf")) {
    XfaForm xfa = doc.getForm().getXFA();
    if (xfa != null) {
        xfa.paintPaginatedContent(doc); // paginate + paint the XFA layout
    }
    doc.save("xfa-rendered.pdf");
}
```

Related painters are available for finer control: `paintPositionedContent(Document)`
paints only the positioned (non-flowed) content onto page 1, and
`convertToAcroForm(Document)` (above) both paints the layout and adds interactive
widgets.

### Rasterize an XFA form to images

To produce raster images (PNG/JPEG/TIFF/…), first turn the XFA layout into normal
PDF pages — either by converting to AcroForm (`Form.setType(FormType.Standard)`) or
by `paintPaginatedContent` — then rasterize those pages with the device classes:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.devices.PngDevice;
import org.aspose.pdf.devices.Resolution;

import java.io.FileOutputStream;

try (Document doc = new Document("xfa-form.pdf")) {
    doc.getForm().setType(Form.FormType.Standard); // materialize XFA into real pages

    PngDevice device = new PngDevice(new Resolution(150));
    for (int i = 1; i <= doc.getPages().getCount(); i++) {
        Page page = doc.getPages().get(i);
        try (FileOutputStream out = new FileOutputStream("xfa-page-" + i + ".png")) {
            device.process(page, out);
        }
    }
}
```

See [rasterization.md](rasterization.md) for the full device catalogue, resolution,
and fixed-size options.

## Flatten an XFA form

Flattening burns the form's appearance into the page content and removes the
interactive fields, producing a static, non-editable document. Because a true
flatten operates on AcroForm widgets, flatten an XFA form in two steps: convert it
to an AcroForm first, then call `Form.flatten()`.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;

try (Document doc = new Document("xfa-form.pdf")) {
    Form form = doc.getForm();

    if (form.getType() == Form.FormType.XFA) {
        form.setType(Form.FormType.Standard); // XFA -> editable AcroForm
    }
    form.flatten();                           // burn appearances, remove fields

    doc.save("flattened.pdf");
}
```

`Document.flatten()` is a shortcut for `getForm().flatten()`. A
`flatten(FlattenSettings)` overload exists for AcroForm flattening options
(update-appearances, hide-buttons, etc.); note that in this version those settings
are accepted but the flatten currently follows the default behaviour.

To keep the fields visible but non-editable instead of flattening, convert to
AcroForm and set the field read-only flag rather than calling `flatten()`.

## Notes & limitations

- **Detection** distinguishes only `Standard` vs `XFA` via `Form.getType()`. The
  `Static` / `Dynamic` enum constants exist but `getType()` does not classify
  static vs dynamic.
- **`getXFA()` returns `null`** when the document has no `/XFA` entry — always
  null-check it.
- **Reading is sparse-aware.** `get`/`set` navigate the XFA `datasets` packet,
  which omits layout-only containers; the library follows `<bind ref>` and
  descendant search to reach the real data node. Repeated rows must be addressed
  with explicit instance indices (`body[1]`).
- **Scripting is not directly callable.** JavaScript and FormCalc run only
  implicitly inside the convert/paginate pipeline (toggle with
  `-Dxfa.runScripts=...`). There is no public API to evaluate an arbitrary XFA
  script.
- **Conversion is structure + values, not full XFA render fidelity.** Fields with
  flowed (non-positional) geometry are still created and carry their value, but may
  be placed at a fallback position (`XfaFlattener.Result.geometryFallback`).
- **Flattening XFA requires converting to AcroForm first** — `Form.flatten()`
  operates on AcroForm widgets, not the raw XFA packets.
- **`flatten(FlattenSettings)`** accepts settings but currently delegates to the
  default flatten behaviour.
- `XfaFlattener.Result`, `XfaFlattener.XfaPolicy`, `XfaPaginator.Result`, and
  `XfaPainter.Result` live in `org.aspose.pdf.engine.xfa.*`. They are returned by
  the public `XfaForm` methods and are safe to read, but the engine package is
  otherwise internal.

## See also

- [forms.md](forms.md) — AcroForm fields (text boxes, checkboxes, radios, combo/list boxes, signatures)
- [rasterization.md](rasterization.md) — rendering pages to PNG/JPEG/TIFF/BMP/GIF
- [conversion.md](conversion.md) — document format conversion
- [getting-started.md](getting-started.md) — opening, saving, and basic document operations
