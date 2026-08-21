# Changelog

All notable changes to `org.aspose:aspose-pdf-foss` are documented in this file.

Entries are generated from a verified diff against the previous published version's
knowledge model (`/knowledge-diff pdf java`), not from raw commit history — every
Added/Changed/Removed line traces to a real API symbol or claim. GitHub's own
auto-generated release notes on each [Release](https://github.com/aspose-pdf-foss/Aspose.PDF-FOSS-for-Java/releases)
are a convenience summary only; this file is the authoritative record.

## 26.8.0 — 2026-08-21

### Added

A new Structured Document Model (SDM) subsystem for PDF-to-HTML and PDF-to-DOCX
round-trip conversion. 122 new public classes, organized around:

- **Core document model**: `SdmDocument`, `SdmNode`, `SdmBlock`, `SdmInline`,
  `SdmMetadata`, `SdmNodeType`, `SdmIds`
- **HTML conversion**: `HtmlSdmReader`, `SdmHtmlWriter`, `HtmlReadOptions`,
  `HtmlWriterOptions`, `HtmlReadReport`, `HtmlOutputMode`, `HtmlPageLayoutOption`,
  `HtmlMarkupGenerationModes`, `HtmlMediaType`, `HtmlImageEncoder`,
  `StructuralHtmlPipeline`
- **DOCX conversion**: `DocxSdmReader`, `SdmDocxWriter`, `DocFormat`,
  `DocLoadOptions`, `DocSaveOptions`, `StructuralDocxPipeline`
- **PDF-side SDM**: `PdfSdmReader`, `PdfSdmWriter`, `SdmPdfLayout`
- **Flow and layout engine**: `FlowCompactor`, `FlowClass`, `FlowClassifier`,
  `FlowOperations`, `ColumnDetector`, `ColumnMerger`, `ColumnReflow`,
  `ColumnSpec`, `ColumnStructure`, `CrossPageFlow`, `TextRunSplitter`,
  `ReadingOrderNormalizer`, `LayoutReport`, `PagePlan`, `PageSetup`
- **Content enrichment (post-processing)**: `FillBackgroundEnricher`,
  `FixedLayoutPageEnricher`, `HeuristicSdmEnricher`, `HorizontalRuleEnricher`,
  `ImagePlacementEnricher`, `LinkAnnotationEnricher`, `RedactionHighlightEnricher`,
  `RunSpacingNormalizer`, `RunningHeaderFooterEnricher`, `TaggedSdmEnricher`,
  `VectorGraphicsEnricher`
- **Document structure primitives**: `Paragraph`, `Run`, `LineBreak`, `Container`,
  `Figure`, `Footnote`, `FootnoteRef`, `Quote`, `CodeBlock`, `ListBlock`,
  `ListItem`, `TableCell`, `TableRow`, `TocBlock`, `TocEntry`, `ThematicBreak`,
  `InlineImage`, `InlineOpaque`, `LinkInline`, `BlockStyle`, `FontColor`,
  `FurnitureLine`, `WidgetFieldInfo`
- **Supporting types**: `Align`, `Direction`, `VertAlign`, `WidthType`,
  `RecognitionMode`, `LoadFormat`, `LoadOptions`, `SaveOptions`, `MergeOptions`,
  `MergeResult`, `CompactionOptions`, `CompactionResult`, `Options`,
  `Resource`, `ResourceRef`, `ResourceTable`, `ObjectRef`, `SourceRef`,
  `ContentRange`, `Primitive`, `Opaque`, and additional PGM/image/font-related
  types (`PgmBox`, `PgmBoxKind`, `PgmModel`, `PgmPage`, `PgmRect`,
  `ImageBoxData`, `ImageMode`, `VectorBoxData`, `TextBoxData`, `AnnotBoxData`,
  `CellValue`, `CidOrderingUnicode`, `CmykPrintLut`, `RgbPrintShift`,
  `AntialiasingProcessingType`, `FontSavingModes`, `PartsEmbeddingModes`,
  `RasterImagesSavingModes`, `LettersPositioningMethods`,
  `StructureFixes`, `TtfGlyphStripper`, `UnsupportedPageOperation`,
  `WeakIdentityHashMap`, `CryptFilter`, `DocFormat`)

Approximately 1,645 additional fine-grained capability claims accompany these
classes (parsing edge cases, format-specific behaviors, round-trip fidelity
guarantees). Full detail is grounded in `knowledge/pdf/java/merged/` at
[aspose.org](https://github.com/Aspose/aspose.org) commit `3324fc12`, not
reproduced here as individual lines.

### Changed

65 existing public classes were modified as part of this release. The
evidence-grounding tooling used to produce this changelog does not yet
distinguish purely additive modifications (e.g. a new method) from
signature-breaking ones within this category — no class was reclassified as
`API_REMOVED`, `API_SIGNATURE_CHANGED`, or `API_RENAMED` (semantic_diff.py's
dedicated breaking-change types), which is evidence against a breaking change,
but the 65 modified classes below have not been individually, manually
verified for binary/source compatibility. If you depend on any of them,
review your usage before upgrading:

`AESCipher`, `ASCII85Filter`, `AbsorbedTable`, `ArithmeticDecoder`,
`BlendComposite`, `CFFFontLoader`, `CFFParser`, `CIDFont`, `CaretAnnotation`,
`CircleAnnotation`, `CircularValue`, `Config`, `ContentStreamBuilder`,
`CssContext`, `Document`, `FileAttachmentAnnotation`, `FontFixes`,
`FontRepository`, `FormField`, `FreeTextAnnotation`, `GraphicsState`,
`Heading`, `HighlightAnnotation`, `HtmlLoadOptions`, `HtmlSaveOptions`,
`InkAnnotation`, `LineAnnotation`, `MarkupAnnotation`, `Page`,
`PageLabels`, `PageNumberStamp`, `PdfFormatConversionOptions`,
`PdfPageEditor`, `PdfPageRenderer`, `PdfSaveOptions`, `PolylineAnnotation`,
`RedactionAnnotation`, `Result`, `SignatureCustomAppearance`,
`SignatureField`, `SplitPlan`, `SquareAnnotation`, `SquigglyAnnotation`,
`Stamp`, `StampAnnotation`, `StrikeOutAnnotation`, `Table`, `TableAbsorber`,
`TextAnnotation`, `TextBuilder`, `TextFragment`, `TextLayoutHelper`,
`TextStamp`, `TextState`, `TransparencyRules`, `Traverse`, `TrueTypeFont`,
`TrueTypeReader`, `Type0FontBuilder`, `UnderlineAnnotation`,
`WatermarkAnnotation`, `XImage`, `XImageCollection`, `XRefParser`,
`XfaMeasurement`

Notably, `HtmlLoadOptions`/`HtmlSaveOptions` were modified in this batch —
likely integration points for the new SDM-based HTML conversion pipeline
above, though this is an inference from the class names, not independently
confirmed against the actual diffed methods. Full itemized list with
evidence paths: `reports/refresh_review/pdf/java/semantic_changes.json` in
the [aspose.org](https://github.com/Aspose/aspose.org) repository.

### Removed

Two internal capability claims were removed (not API classes — no public
class, method, or field was removed in this release):
- `CLM-pdf-ebddd631`
- `CLM-pdf-db5bc8`

### Compatibility

No API removals or signature changes were detected by the available tooling.
See the **Changed** section above for the scope and limits of that
determination.

### Maven Coordinates

```xml
<dependency>
    <groupId>org.aspose</groupId>
    <artifactId>aspose-pdf-foss</artifactId>
    <version>26.8.0</version>
</dependency>
```
