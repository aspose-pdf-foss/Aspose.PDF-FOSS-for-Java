# Working with Forms

This guide covers AcroForm fields: reading values, filling fields, iterating, and creating new fields.

## Reading values from an existing form

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.forms.Field;

try (Document doc = new Document("form.pdf")) {
    Form form = doc.getForm();
    System.out.println("Field count: " + form.getCount());

    for (Field field : form) {
        System.out.printf("  %s = %s%n",
            field.getPartialName(),
            field.getValue());
    }
}
```

`Form` implements `Iterable<Field>`, so a for-each loop iterates over all fields, including those nested inside groups.

## Looking up a field by name

```java
Field nameField = form.get("billing.name");
if (nameField != null) {
    nameField.setValue("Jane Doe");
}
```

Field names follow the same convention as PDF spec field hierarchy: a dot-separated path through nested field nodes. `form.get("billing.name")` finds the leaf field `name` inside the parent `billing`.

For case-insensitive lookup:

```java
if (form.hasField("Billing.Name", true)) {  // true = ignoreCase
    // ...
}
```

## Field types

The library provides typed subclasses for common AcroForm field types. Cast the generic `Field` to the specific subclass when you need type-specific behaviour:

| Subclass | PDF field type | Notes |
|---|---|---|
| `TextBoxField` | Tx | Single-line, multi-line, password, comb |
| `CheckboxField` | Btn (checkbox) | Boolean check / uncheck |
| `RadioButtonField` | Btn (radio) | Group of mutually-exclusive options |
| `RadioButtonOptionField` | Btn (radio kid) | One option within a radio group |
| `ComboBoxField` | Ch (combo) | Dropdown, optionally editable |
| `ListBoxField` | Ch (list) | Multi-row selection list |
| `ButtonField` | Btn (push button) | Action triggers |
| `SignatureField` | Sig | Digital signature placeholder |

## Filling a form

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.forms.TextBoxField;
import org.aspose.pdf.forms.CheckboxField;
import org.aspose.pdf.forms.ComboBoxField;

try (Document doc = new Document("form.pdf")) {
    Form form = doc.getForm();

    ((TextBoxField) form.get("name")).setValue("Jane Doe");
    ((TextBoxField) form.get("email")).setValue("jane@example.com");
    ((CheckboxField) form.get("subscribe")).setChecked(true);
    ((ComboBoxField) form.get("country")).setValue("Netherlands");

    doc.save("form-filled.pdf");
}
```

`setValue(String)` works on all field types and accepts the string representation appropriate for the field; for example, on a `CheckboxField`, `"Yes"` and `"Off"` are the typical values, but using the typed `setChecked(boolean)` API is clearer.

## Multi-line text fields

```java
TextBoxField comments = (TextBoxField) form.get("comments");
comments.setMultiline(true);
comments.setValue("Line 1\nLine 2\nLine 3");
```

## Creating a new text field on a page

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.forms.TextBoxField;

try (Document doc = new Document()) {
    Page page = doc.getPages().add();

    // Field at position (100, 700) with size 200 x 24 points
    Rectangle rect = new Rectangle(100, 700, 300, 724);
    TextBoxField field = new TextBoxField(page, rect);
    field.setPartialName("name");
    field.setValue("Default value");

    doc.getForm().add(field);  // depending on API revision, this may be implicit
    doc.save("new-form.pdf");
}
```

`Rectangle` coordinates follow the PDF convention: origin at the lower-left corner of the page, units in points (1/72 inch).

## Flattening a form

Flattening converts interactive fields into static page content, freezing the current values:

```java
try (Document doc = new Document("form-filled.pdf")) {
    Form form = doc.getForm();
    // Flatten depending on availability — check the Form API
    // for the current method signature in your version.
    // ... flatten ...
    doc.save("form-flat.pdf");
}
```

Flattening is one-way; the resulting PDF is no longer editable as a form.

## Reading XFA (XML Forms Architecture) data

Modern AcroForms may carry an XFA layer. The XFA can be retrieved as XML:

```java
import org.aspose.pdf.forms.xfa.XfaForm;

XfaForm xfa = form.getXFA();
if (xfa != null) {
    // ... operate on the XFA representation ...
}
```

Note that **full XFA form rendering and editing is not in scope** for the FOSS edition — only the data layer is exposed. See [docs/limitations.md](limitations.md).

## Common patterns

### Dump form to a CSV-like report

```java
try (Document doc = new Document("form.pdf")) {
    System.out.println("name,value");
    for (Field field : doc.getForm()) {
        System.out.printf("\"%s\",\"%s\"%n",
            field.getPartialName(),
            field.getValue() != null ? field.getValue() : "");
    }
}
```

### Bulk-fill from a Map

```java
import java.util.Map;

void fillFrom(Document doc, Map<String, String> values) throws IOException {
    Form form = doc.getForm();
    for (Map.Entry<String, String> entry : values.entrySet()) {
        Field field = form.get(entry.getKey());
        if (field != null) {
            field.setValue(entry.getValue());
        }
    }
}
```

### Check whether a PDF has an editable form

```java
try (Document doc = new Document("file.pdf")) {
    Form form = doc.getForm();
    if (form != null && form.getCount() > 0) {
        System.out.println("Has " + form.getCount() + " form fields");
    } else {
        System.out.println("No form (or empty form)");
    }
}
```

## Limitations

- **XFA forms** are covered on their own page — reading/filling data, conversion to AcroForm, flattening, and rendering. See [xfa.md](xfa.md).
- **Field appearances**: the library generates standard appearances on save; complex custom appearances from the original document may be regenerated.
- **AcroForm JavaScript actions**: embedded form JavaScript is not executed for AcroForm fields (intentional, for security). XFA scripting is handled separately during XFA conversion/rendering — see [xfa.md](xfa.md).
- **Digital signatures on form fields**: see [signatures.md](signatures.md).
