# Getting Started

This guide walks you from zero to a working Aspose.PDF FOSS setup, with a few minimal programs you can copy and run.

## Prerequisites

- **JDK 11 or newer.** OpenJDK from [Adoptium](https://adoptium.net/) is recommended. Verify with `java -version` and `javac -version`.
- **Maven 3.8+** or **Gradle 7+**. Verify with `mvn -version` or `gradle --version`.
- A text editor or IDE (IntelliJ IDEA, Eclipse, VS Code with Java extensions all work).

## Build from source

While Maven Central publishing is being set up, install from source:

```bash
git clone https://github.com/aspose-pdf-foss/Aspose.PDF-FOSS-for-Java.git
cd Aspose.PDF-FOSS-for-Java
mvn clean install
```

This compiles, tests, and installs `aspose-pdf-26.8.jar` into your local Maven repository (`~/.m2/repository/org/aspose/aspose-pdf/26.8/`). From that point any local Maven project can depend on it.

If you only need the jar without running tests:

```bash
mvn clean install -DskipTests
```

## Add the dependency

Once `aspose-pdf` is in your local Maven repo (or, eventually, on Maven Central), add it to your project.

**Maven:**

```xml
<dependency>
    <groupId>org.aspose</groupId>
    <artifactId>aspose-pdf</artifactId>
    <version>26.8</version>
</dependency>
```

**Gradle (Groovy DSL):**

```groovy
implementation 'org.aspose:aspose-pdf:26.8'
```

**Gradle (Kotlin DSL):**

```kotlin
implementation("org.aspose:aspose-pdf:26.8")
```

No further dependencies are needed. The library uses only the standard Java platform (`java.*`, `javax.crypto`, `javax.imageio`, `javax.xml.*`).

## Your first PDF

Create a Java class with this content:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.TextFragment;

public class HelloPdf {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            page.getParagraphs().add(new TextFragment("Hello, PDF world!"));
            doc.save("hello.pdf");
        }
        System.out.println("Wrote hello.pdf");
    }
}
```

Compile and run. You should now have a `hello.pdf` in the working directory that opens in any PDF viewer.

## Reading an existing PDF

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.text.TextAbsorber;

public class ReadPdf {
    public static void main(String[] args) throws Exception {
        try (Document doc = new Document("hello.pdf")) {
            System.out.println("Page count: " + doc.getPages().getCount());

            TextAbsorber absorber = new TextAbsorber();
            doc.getPages().accept(absorber);
            System.out.println("Text:\n" + absorber.getText());
        }
    }
}
```

## Always use try-with-resources

`Document` implements `AutoCloseable` and holds file handles, parsed cross-reference tables, and decoded streams. **Always** create it inside a `try (Document doc = new Document(...))` block. Failing to close `Document` leaks file handles and may keep the source file locked on Windows.

If you cannot use try-with-resources (for example, in a long-lived service that keeps a `Document` open across method calls), make sure to call `doc.close()` in a `finally` block.

## Working with `InputStream`

Reading from a stream rather than a path is fully supported and recommended for web servers, JAR-bundled resources, and similar contexts:

```java
try (InputStream in = getClass().getResourceAsStream("/templates/invoice.pdf");
     Document doc = new Document(in)) {
    // ... process ...
}
```

When constructing from `InputStream`, the entire content is read into memory; the stream may be closed by the caller afterwards.

## Saving

```java
doc.save("output.pdf");                        // by path
doc.save(new FileOutputStream("output.pdf"));  // by stream
doc.save();                                    // overwrite the file the doc was loaded from
```

`doc.save()` without arguments only works for documents loaded from a path. For documents loaded from a stream, you must provide a destination.

## Running unit tests after changes

If you build from source and want to confirm everything works on your machine, run:

```bash
mvn test
```

All public tests should pass. If any test fails on your environment, [open an issue](https://github.com/aspose-pdf-foss/Aspose.PDF-FOSS-for-Java/issues/new) with the failure log, your JDK version, and your OS.

## Troubleshooting

**`Document` constructor throws `IOException` for what looks like a valid PDF.**
The library is strict about malformed PDFs. Try opening the same file in another tool. If the file is valid but rejected here, please [report a bug](https://github.com/aspose-pdf-foss/Aspose.PDF-FOSS-for-Java/issues/new) with the file attached if you can share it.

**OutOfMemoryError on large files.**
For very large documents you may need to increase the JVM heap: `java -Xmx2g …`. We are aware of opportunities to reduce memory use; please file issues if specific operations are surprisingly memory-hungry.

**Cannot save: file is locked.**
Make sure you actually called `doc.close()` (or used try-with-resources). On Windows the source file may remain locked until the `Document` is closed.

**`mvn install` fails on tests.**
Use `mvn install -DskipTests` to install regardless, then please report which tests fail so we can investigate.

## Next steps

Browse the full [documentation index](README.md), or jump to a topic:

- [Working with text](text-extraction.md) — extraction, search, replacement
- [Generating documents](document-generation.md) — pages, paragraphs, tables, stamps
- [Document conversion](conversion.md) — HTML ↔ PDF, DOCX ↔ PDF, DOC → PDF
- [Page operations](page-operations.md) — split, merge, rotate, resize
- [Drawing API](drawing.md) — shapes and gradients
- [Forms](forms.md) and [XFA forms](xfa.md)
- [Annotations](annotations.md)
- [Navigation](navigation.md) — bookmarks, destinations, links
- [Attachments](attachments.md)
- [Page rasterization](rasterization.md) — render to PNG/JPEG/TIFF
- [Optimization](optimization.md) — shrink file size
- [Security](security.md) and [Digital signatures](signatures.md)
- [PDF/A](pdfa.md) · [Metadata](metadata.md) · [Facades](facades.md)
- [Known limitations](limitations.md)
