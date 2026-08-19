# Resource optimization and file-size reduction

Large PDFs accumulate waste over their lifetime: objects that nothing references
anymore, byte-identical copies of the same stream, images stored at far higher
resolution than they are ever displayed at, and fully embedded fonts when only a
handful of glyphs are used. This library reduces file size through
`org.aspose.pdf.Document.optimizeResources()` and the
`org.aspose.pdf.optimization.OptimizationOptions` class.

All APIs are in the `org.aspose.pdf` root package (version 26.7, Java 11+, zero
third-party dependencies). Optimization is a two-step operation: you call
`optimizeResources()` to *stage* the passes, then `save()` to write the smaller
file. Page indices are 1-based.

## Quick start — default optimization

The parameterless `optimizeResources()` overload applies the three standard
structural passes: remove unused objects, remove unused streams, and link
duplicate streams. It does not touch images or fonts, so it is lossless.

```java
import org.aspose.pdf.Document;
import java.io.IOException;

public class BasicOptimize {
    public static void main(String[] args) throws IOException {
        try (Document doc = new Document("input.pdf")) {
            doc.optimizeResources();
            doc.save("optimized.pdf");
        }
    }
}
```

`optimizeResources()` only *stages* the work; the actual size reduction happens
during `save()`, which performs a full compact rewrite that re-serialises only
the objects still reachable from the trailer.

## Building an OptimizationOptions

`OptimizationOptions` exposes one flag (or value) per pass. The parameterless
constructor enables the three structural passes by default and leaves everything
else off:

| Option (setter) | Default | What it does |
|---|---|---|
| `setRemoveUnusedObjects(boolean)` | `true` | Drops indirect objects nothing references. |
| `setRemoveUnusedStreams(boolean)` | `true` | Drops streams nothing references. |
| `setLinkDuplicateStreams(boolean)` | `true` | Merges byte-identical streams into one shared object. |
| `setAllowReusePageContent(boolean)` | `false` | Lets identical page-content streams be shared between pages; also triggers per-page resource pruning. |
| `setCompressImages(boolean)` | `false` | Recompresses embedded images (lossy JPEG). |
| `setImageQuality(int)` | `100` | JPEG quality 1–100 used when `CompressImages` is on (clamped to that range). |
| `setResizeImages(boolean)` | `false` | Downscales oversized images (needs `MaxResolution`). |
| `setMaxResolution(int)` | `0` | Target DPI ceiling for `ResizeImages`. |
| `setSubsetFonts(boolean)` | `false` | Strips embedded fonts down to the glyphs actually used. |
| `setUnembedFonts(boolean)` | `false` | Removes embedded font programs entirely (relies on viewer substitution). |
| `setRemovePrivateInfo(boolean)` | `false` | Strips private/metadata entries. |
| `setCompressObjects(boolean)` | `false` | Packs eligible objects into object streams (ISO 32000 §7.5.7). |
| `setImageCompressionVersion(int)` | `0` | Selects the image-compression algorithm variant. |

Two factory shortcuts exist:

```java
import org.aspose.pdf.optimization.OptimizationOptions;

// Structural passes only (same as the no-arg constructor):
OptimizationOptions structural = new OptimizationOptions();

// Everything aggressive: structural + reuse page content + subset fonts + compress images:
OptimizationOptions everything = OptimizationOptions.all();
```

Apply the options through the second overload:

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.optimization.OptimizationOptions;
import java.io.IOException;

public class TunedOptimize {
    public static void main(String[] args) throws IOException {
        try (Document doc = new Document("input.pdf")) {
            OptimizationOptions opts = new OptimizationOptions();
            opts.setRemoveUnusedObjects(true);
            opts.setRemoveUnusedStreams(true);
            opts.setLinkDuplicateStreams(true);
            opts.setSubsetFonts(true);

            doc.optimizeResources(opts);
            doc.save("optimized.pdf");
        }
    }
}
```

Passing `null` to `optimizeResources(OptimizationOptions)` is safe — it applies
nothing.

## Removing unused objects and streams

These are the safest, lossless passes and they are on by default. A full rewrite
re-serialises only what the catalog still reaches, so anything orphaned — for
example, pages you deleted, or intermediate page-tree nodes discarded by
`pageNodesToBalancedTree()` — is dropped automatically.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.optimization.OptimizationOptions;
import java.io.IOException;

public class RemoveUnused {
    public static void main(String[] args) throws IOException {
        try (Document doc = new Document("with-garbage.pdf")) {
            OptimizationOptions opts = new OptimizationOptions();
            opts.setRemoveUnusedObjects(true);
            opts.setRemoveUnusedStreams(true);
            opts.setLinkDuplicateStreams(false);

            doc.optimizeResources(opts);
            doc.save("cleaned.pdf");
        }
    }
}
```

## Linking duplicate streams

Documents assembled by concatenation often carry the same logo, font, or
boilerplate content stream many times over. When `LinkDuplicateStreams` is on,
byte-identical streams collapse to a single shared object.

```java
OptimizationOptions opts = new OptimizationOptions();
opts.setLinkDuplicateStreams(true);
// AllowReusePageContent additionally lets whole identical page-content
// streams be shared between pages.
opts.setAllowReusePageContent(true);
```

## Downsampling and recompressing images

Scanned or screenshot-heavy PDFs are usually dominated by image data. Two knobs
address this:

- **Recompression** (`setCompressImages(true)` + `setImageQuality(int)`) re-encodes
  images as JPEG at the given quality (1–100). Lower quality → smaller file, more
  artifacts.
- **Downsampling** (`setResizeImages(true)` + `setMaxResolution(int)`) scales images
  whose effective resolution exceeds the DPI ceiling. Effective resolution is
  computed from each image's largest on-page display footprint, so an image shown
  small is downsampled harder than one shown full-page.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.optimization.OptimizationOptions;
import java.io.IOException;

public class ShrinkImages {
    public static void main(String[] args) throws IOException {
        try (Document doc = new Document("scanned.pdf")) {
            OptimizationOptions opts = new OptimizationOptions();
            opts.setCompressImages(true);
            opts.setImageQuality(60);        // trade quality for size
            opts.setResizeImages(true);
            opts.setMaxResolution(150);      // cap at 150 DPI

            doc.optimizeResources(opts);
            doc.save("scanned-small.pdf");
        }
    }
}
```

Both passes rewrite stream bytes in place. The library forces a full compact
rewrite on the next `save()` whenever a content-mutating pass actually changed
something, so the superseded (larger) original bytes are dropped rather than left
behind alongside the new ones.

## Subsetting and unembedding fonts

- **Subsetting** (`setSubsetFonts(true)`) keeps the embedded font program but strips
  it to only the glyphs the document actually uses. This is lossless for the
  rendered text and typically the biggest safe win on text-heavy documents.
- **Unembedding** (`setUnembedFonts(true)`) removes the embedded font program
  entirely, relying on the viewer to substitute a system font. This is smaller
  still but changes appearance if the reader lacks the exact font — use it only
  for common fonts.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.optimization.OptimizationOptions;
import java.io.IOException;

public class FontOptimize {
    public static void main(String[] args) throws IOException {
        try (Document doc = new Document("report.pdf")) {
            OptimizationOptions opts = new OptimizationOptions();
            opts.setSubsetFonts(true);   // safe: keeps embedded look
            // opts.setUnembedFonts(true); // aggressive: relies on substitution

            doc.optimizeResources(opts);
            doc.save("report-subset.pdf");
        }
    }
}
```

## Before / after: measuring the win

Optimization is only real once written to disk, so measure the file on disk, not
the in-memory document.

```java
import org.aspose.pdf.Document;
import org.aspose.pdf.optimization.OptimizationOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MeasureOptimize {
    public static void main(String[] args) throws IOException {
        Path in = Path.of("input.pdf");
        Path out = Path.of("optimized.pdf");

        long before = Files.size(in);
        try (Document doc = new Document(in.toString())) {
            OptimizationOptions opts = OptimizationOptions.all();
            opts.setImageQuality(70);
            opts.setResizeImages(true);
            opts.setMaxResolution(150);
            doc.optimizeResources(opts);
            doc.save(out.toString());
        }
        long after = Files.size(out);

        System.out.printf("Before: %,d bytes%n", before);
        System.out.printf("After:  %,d bytes%n", after);
        System.out.printf("Saved:  %.1f%%%n", 100.0 * (before - after) / before);
    }
}
```

## Related document-level helpers

`optimizeResources()` stages resource-level passes. A few `Document` methods
influence how the file is *serialised* and pair well with it:

```java
try (Document doc = new Document("input.pdf")) {
    // Favour a compact full rewrite (object streams, no orphans) on next save:
    doc.setOptimizeSize(true);

    // Request a full rewrite instead of an incremental append:
    doc.optimize();

    // Rebalance a flat page tree so per-page lookup is O(log n):
    doc.pageNodesToBalancedTree();      // default fan-out 10
    // doc.pageNodesToBalancedTree((byte) 8);

    doc.save("output.pdf");
}
```

`setCompressObjects(true)` on the options and `setOptimizeSize(true)` on the
document both request the same compact object-stream rewrite.

## Notes & limitations

- **Optimization needs a save.** `optimizeResources()` only stages the passes and
  (for the structural passes) requests a full compact rewrite. Nothing shrinks
  until you call `save()`. Reusing the same `Document` for read-only work after
  `optimizeResources()` without saving gains you nothing.
- **Structural passes are implemented and lossless.** `RemoveUnusedObjects`,
  `RemoveUnusedStreams`, `LinkDuplicateStreams`, and `AllowReusePageContent` are
  fully implemented. They are enabled by the default constructor
  (`AllowReusePageContent` excepted, which defaults to `false`).
- **Image and font passes are best-effort.** `CompressImages`, `ResizeImages`,
  `SubsetFonts`, `UnembedFonts`, and `RemovePrivateInfo` are honoured where the
  engine supports the operation and are otherwise a no-op — the document still
  saves correctly, just without that particular reduction. Do not assume every
  image or font in an arbitrary document will be rewritten.
- **`ResizeImages` requires `MaxResolution`.** With `MaxResolution` left at its
  default `0`, the resize pass does nothing; set a positive DPI ceiling.
- **`ImageQuality` is clamped.** Values below 1 or above 100 are silently clamped
  into the 1–100 range.
- **Lossy vs. lossless.** `CompressImages` (JPEG re-encode), `ResizeImages`
  (downscale), and `UnembedFonts` (font substitution) change appearance.
  `SubsetFonts` and all structural passes do not. Choose accordingly.
- **`optimize()` vs. `optimizeResources()`.** `optimize()` (and
  `setOptimizeSize(true)`) only request a compact rewrite on the next save; they
  do not run the resource passes. Use `optimizeResources(...)` to actually prune
  and recompress resources.
- **Always close the document.** Use try-with-resources so the underlying parser
  and file handles are released.

## See also

- `org.aspose.pdf.Document` — `optimizeResources()`, `optimizeResources(OptimizationOptions)`, `optimize()`, `setOptimizeSize(boolean)`, `pageNodesToBalancedTree()`, `save(String)`.
- `org.aspose.pdf.optimization.OptimizationOptions` — all option setters plus the `all()` factory.
