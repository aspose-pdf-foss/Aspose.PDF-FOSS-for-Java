# Limitations and Out-of-Scope Features

Aspose.PDF FOSS for Java (version 26.7) is an open-source implementation of the
core PDF specification (ISO 32000-1:2008) plus document conversion. It is
functional for many production workflows, but it is not a drop-in replacement
for every feature of the commercial product. This page is a high-level
orientation — the individual guides call out the precise partial cases, and
JavaDoc is the source of truth for exact API coverage.

## Out of scope (not planned for the FOSS edition)

These exist in the commercial [Aspose.PDF for Java](https://products.aspose.com/pdf/java/)
but are not targeted here:

- **OCR** — recognising text in scanned images. Use a dedicated OCR engine (e.g. Tesseract).
- **Conversion to spreadsheet/presentation/e-book formats** — XLSX, PPTX, EPUB, MOBI, Markdown, LaTeX, ZUGFeRD, and similar. (HTML, DOCX and DOC conversion *are* supported — see [conversion.md](conversion.md).)
- **Input from non-document formats** — XLSX, PPTX, XPS, PCL, PostScript, EPS, SVG as source formats.
- **3D annotations and PRC/U3D streams.**
- **PDF/X** print-production family. (PDF/A is supported — see [pdfa.md](pdfa.md).)

If you need any of these, the commercial product is the right tool.

## Supported since the early docs (previously listed as out of scope)

The following are now implemented and documented — earlier revisions of this
page marked them out of scope:

- **HTML ↔ PDF, DOCX ↔ PDF, and legacy DOC → PDF** conversion on a shared structured-document engine — see [conversion.md](conversion.md).
- **XFA forms** — reading/filling data, conversion to AcroForm, flattening, and rendering — see [xfa.md](xfa.md).

## In scope but partial (verify against your case)

- **Drawing API generation** — the `org.aspose.pdf.drawing` package (`Graph`, `Line`, `Rectangle`, `Circle`, `Arc`, `Curve`, `Ellipse`, gradients) is a geometry/styling/validation model. It is **not yet wired into the page-generation pipeline**, so a `Graph` added to a page is not emitted on `save()`. Vector graphics in *existing* PDFs render fully on the read/rasterize path. See [drawing.md](drawing.md).
- **DOCX/DOC output is OOXML only** — both `DocFormat.DocX` and `DocFormat.Doc` emit an OOXML `.docx`-shaped file; there is no binary Word 97-2003 (`.doc`) writer. Legacy `.doc` is import-only.
- **`SaveFormat.Xml`** is defined but not implemented; it currently falls through to PDF output.
- **Text replacement** — re-encoding into subset/embedded and composite fonts works; exact glyph-advance/position compensation after a length-changing replacement can differ slightly from the reference renderer. Non-Latin bidi/shaping edge cases may also differ.
- **Digital signatures** — approval signatures (PKCS#7, RSA/DSA/ECDSA) sign and verify; there is **no certification (DocMDP) API**, no trust-chain/revocation checking, and LTV/DSS is limited. Verification needs access to the signed file's bytes. See [signatures.md](signatures.md).
- **File-attachment annotations** — `FileAttachmentAnnotation` renders an icon/rect but does not link an embedded `FileSpecification`; for real attachments use the document-level `EmbeddedFileCollection`. See [attachments.md](attachments.md).
- **Facade coverage** — common operations are covered; some less-common overloads are absent, and a few methods are explicit stubs/best-effort (e.g. `PdfFileEditor.makeNUp` is a stub, `makeBooklet` is best-effort). See [facades.md](facades.md).
- **Tagged PDF / logical structure** — readable; programmatic construction of well-formed structure trees is partial.
- **PDF/UA accessibility** — baseline only; full tag-tree validation is in progress.
- **High-fidelity rasterization knobs** — many of the commercial `RenderingOptions` toggles (hinting modes, custom scale modes) are not implemented; core rendering (text, images, shadings, transparency groups, soft masks, blend modes, JPX/JBIG2) is. See [rasterization.md](rasterization.md).

## Performance considerations

- **Very large or very dense pages** may use more memory than the commercial product; the project prefers correctness first. Increase the heap (`-Xmx`) for huge inputs. Rendering cost scales with output pixel count — rasterizing a large-format page at high DPI is inherently heavy.
- **A single `Document` instance is not thread-safe.** Use one `Document` per thread; open separate instances for parallel work.
- **Reading is generally cheaper than writing.** `save()` rebuilds the cross-reference table and stream data — batch edits before saving in high-throughput pipelines.
- **Always close `Document`** (try-with-resources). Closed documents release their parser graph and caches; leaving them open leaks memory and, on Windows, keeps the source file locked.

## Compatibility expectations

- **PDFs produced here are readable** by Acrobat, Foxit, Sumatra, Apple Preview, Chrome, and other major readers; round-trip fidelity is part of the test suite.
- **PDFs produced by other tools** are generally readable. The parser tolerates minor spec violations but rejects files that cannot be processed safely.
- **Encryption** is supported up to AES-256. Public-key (certificate) encryption requires the `ICustomSecurityHandler` interface; a turn-key helper is not yet provided.

## Reporting gaps and bugs

For an "in scope but partial" case with a missing scenario, please
[open a GitHub Issue](https://github.com/aspose-pdf-foss/Aspose.PDF-FOSS-for-Java/issues) with:

1. A short description of the use case
2. A minimal reproducible example (Java + a sample file if applicable)
3. The expected output (or how the commercial Aspose.PDF for Java handles it)

For "out of scope" items, please point to the commercial product instead of filing a feature request.
