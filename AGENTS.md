# AGENTS.md

Instructions for AI coding agents (Claude Code, Cursor, Aider, etc.) working on Aspose.PDF FOSS for Java.

## Project at a glance

- **Language**: Java 11
- **Build**: Maven 3
- **Module layout**: single-module on GitHub (root `pom.xml` builds the library)
- **Java package root**: `org.aspose.pdf`
- **Maven coordinates**: `org.aspose:aspose-pdf-foss`
- **License**: MIT
- **Test framework**: JUnit 5 (Jupiter)
- **Third-party dependencies**: **none**, by design

## Build & test commands

```bash
# Compile only
mvn clean compile

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=DocumentTests

# Run a single test method
mvn test -Dtest=DocumentTests#openExistingPdf_returnsCorrectPageCount

# Full build incl. test jar and javadoc
mvn clean install

# Generate JavaDoc only
mvn javadoc:javadoc
```

Tests should always be green on `main`. If a test is intentionally not yet passing, it must be `@Disabled` with a specific reason.

## Project structure

```
.
├── pom.xml
├── LICENSE
├── README.md
├── AGENTS.md                                    ← this file
├── .github/workflows/build.yml                  ← CI
└── src/
    ├── main/java/org/aspose/pdf/
    │   ├── Document.java                        ← top-level entry point
    │   ├── Page.java
    │   ├── annotations/                         ← annotation types
    │   ├── facades/                             ← high-level convenience APIs
    │   │                                          (PdfFileEditor, PdfFileStamp, …)
    │   ├── forms/                               ← AcroForm fields
    │   ├── text/                                ← TextFragment, TextAbsorber, …
    │   ├── drawing/                             ← Color, graphics
    │   ├── devices/                             ← page rasterization (PNG, JPEG, …)
    │   ├── engine/                              ← low-level PDF-object layer
    │   │   ├── pdfobjects/                      ← PdfDictionary, PdfArray, …
    │   │   ├── parser/                          ← XRefParser, PDFParser
    │   │   ├── writer/                          ← PDFWriter
    │   │   ├── filter/                          ← FlateDecode, JBIG2, JPXDecode, …
    │   │   └── font/                            ← font loading
    │   ├── operators/                           ← content stream operators
    │   ├── logicalstructure/                    ← tagged PDF
    │   └── printing/                            ← PDF printing
    └── test/java/org/aspose/pdf/                ← unit tests, mirroring main/ layout
```

When adding a new class, put it in the package that mirrors its conceptual area. A new annotation type goes in `annotations/`. A new content-stream operator goes in `operators/`. A new field type goes in `forms/`.

## Core invariants — do not violate

These rules are **hard constraints**. An agent that breaks them is producing incorrect changes.

### 1. Zero third-party runtime dependencies

The library uses only the standard Java platform:

- `java.*`
- `javax.crypto`
- `javax.imageio`
- `javax.xml.*`

**Do not** add Maven dependencies on Apache Commons, Guava, BouncyCastle, SLF4J, Log4j, Jackson, or anything else. If functionality is missing from the JDK, implement it in-tree.

Test dependencies (JUnit) are fine, but should remain `<scope>test</scope>`.

### 2. PDF objects are the single source of truth

Every public API class (e.g. `Document`, `Page`, `Annotation`, `Field`) wraps a `PdfDictionary` or `PdfArray` (package `engine.pdfobjects`). Mutations go through the underlying PDF object — they are not stored in separate Java fields that need to be synced.

When implementing a new property, write through to the PDF dictionary:

```java
// Good
public void setRotate(int rotate) {
    dict.set(PdfName.of("Rotate"), PdfInteger.valueOf(rotate));
}

// Bad — Java field will go out of sync with serialized PDF
private int rotate;
public void setRotate(int r) { this.rotate = r; }
```

### 3. ISO 32000-1:2008 compliance

The PDF format is defined by [ISO 32000-1:2008](https://www.iso.org/standard/51502.html). When in doubt about encoding, behavior, or defaults, the spec wins.

### 4. Public API stability

Once a class or method is published in a release, do not change its signature without an explicit deprecation cycle. New methods can be added freely; existing ones cannot be renamed or removed without prior `@Deprecated` annotation.

### 5. No unnecessary refactoring

When fixing a bug or adding a feature, change only what is needed. Drive-by reformatting, renames, or "cleanup" of unrelated code makes review harder and history noisier.

## Code style

- **Indent**: 4 spaces
- **Line length**: soft 100, hard 120
- **Braces**: same-line opening (`if (cond) {`)
- **Imports**: explicit (no wildcard `import x.y.*;`)
- **Naming**:
  - Classes: `PascalCase` (`TextFragmentAbsorber`)
  - Methods, fields, locals: `camelCase` (`getPageCount`)
  - Constants: `UPPER_SNAKE_CASE` (`DEFAULT_PAGE_SIZE`)
  - Packages: lowercase, no underscores (`org.aspose.pdf.annotations`)
- **Logging**: use `java.util.logging.Logger`, named after the enclosing class. Do not add SLF4J or Log4j.
- **JavaDoc**: required on all public classes and public methods.
- **`@Override`**: always present when overriding.
- **`final`**: prefer `final` for fields and method parameters that are not reassigned, but do not retrofit existing code.

## Test conventions

- **Framework**: JUnit 5 (`org.junit.jupiter.api.*`)
- **Assertions**: vanilla JUnit (`Assertions.assertEquals`, `assertThrows`, etc.). No AssertJ, no Hamcrest.
- **Naming**: `methodName_stateUnderTest_expectedBehavior` is the preferred pattern, e.g. `setValue_nullArgument_throwsNPE`.
- **Self-contained**: tests must build any required PDF in-memory or via `@TempDir`. **No external test files.** The published GitHub repository contains no sample PDF corpus; tests cannot rely on one.
- **Temp files**: use `@TempDir Path tempDir` and resolve paths through it:

```java
@TempDir
Path tempDir;

@Test
void saveAndReload_roundtripsCorrectly() throws Exception {
    Path out = tempDir.resolve("test.pdf");
    try (Document doc = new Document()) {
        doc.getPages().add();
        doc.save(out.toString());
    }
    try (Document reloaded = new Document(out.toString())) {
        assertEquals(1, reloaded.getPages().size());
    }
}
```

- **Disabled tests**: if a test can't pass yet, mark it with `@Disabled("specific reason explaining why")`. Generic "TODO" messages are not acceptable.

## Commit messages

Free-form is fine, but keep the subject line under 72 characters and use the imperative mood:

```
Fix infinite recursion in PdfDictionary.writeTo for indirect references

The writeTo() method was not checking isIndirect() before recursing,
which caused stack overflow when a dictionary contained a reference
back to itself via the page tree.
```

Group related changes into a single commit. Avoid commits that mix functional changes with formatting fixes.

## Before opening a pull request

1. `mvn clean test` passes locally
2. No new third-party dependencies introduced
3. JavaDoc added on any new public API
4. Test coverage exists for the new behavior
5. Public API signatures are unchanged (or, if changed, an explicit deprecation cycle is in place)
6. Commit messages explain the *why*, not just the *what*

## What is out of scope for AI agents

- **Do not** add third-party runtime dependencies under any circumstances
- **Do not** change Java package names or Maven coordinates
- **Do not** modify `LICENSE` or copyright notices
- **Do not** introduce build system changes (Gradle, Bazel, etc.) — Maven only
- **Do not** add binary files (PDFs, images, fonts) to the repository as test fixtures; tests must be self-contained
- **Do not** refactor unrelated code while fixing a bug
- **Do not** rename existing public APIs without an explicit instruction and deprecation plan

## When in doubt

If a requested change conflicts with these rules, stop and ask. It is always better to surface a conflict than to produce code that has to be reverted.
