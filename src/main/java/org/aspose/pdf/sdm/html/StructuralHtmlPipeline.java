package org.aspose.pdf.sdm.html;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.logging.Logger;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.reader.PdfSdmReader;

/**
 * PDF &rarr; structural HTML pipeline — IR Stage 3.
 *
 * <p>Chains the proven Stage-1 shallow projection ({@link PdfSdmReader}) with the
 * Stage-3 enrichment passes and the {@link SdmHtmlWriter}. Enrichment ENRICHES the
 * shallow SDM in place (Paragraph&rarr;Heading retypes, list/table grouping) — it never
 * re-creates nodes, so GUIDs survive:</p>
 * <ul>
 *   <li>tagged PDF (has a StructTreeRoot): structure comes from the author's tags
 *       (PART 2, {@code TaggedSdmEnricher});</li>
 *   <li>untagged PDF: geometry heuristics with honest degradation
 *       (PART 3, {@code HeuristicSdmEnricher}) — unless disabled via
 *       {@link HtmlSaveOptions#isStructuralHeuristics()}.</li>
 * </ul>
 */
public final class StructuralHtmlPipeline {

    private static final Logger LOG = Logger.getLogger(StructuralHtmlPipeline.class.getName());

    private StructuralHtmlPipeline() {
        // static entry only
    }

    /**
     * Converts an open document to structural HTML.
     *
     * @param doc     the open document; must not be null
     * @param options HTML save options (image embedding, heuristics toggle); null = defaults
     * @return the HTML text
     * @throws IOException if page content cannot be read or external images cannot be written
     */
    public static String toHtml(Document doc, HtmlSaveOptions options) throws IOException {
        if (options == null) {
            options = new HtmlSaveOptions();
        }
        SdmDocument sdm = buildStructuralSdm(doc, options);
        HtmlWriterOptions writerOptions = new HtmlWriterOptions();
        if (!options.isEmbedImages()) {
            writerOptions.setImageMode(HtmlWriterOptions.ImageMode.EXTERNAL);
            if (options.getImageFolder() != null) {
                writerOptions.setExternalImagesDir(Paths.get(options.getImageFolder()));
                writerOptions.setExternalImagesPrefix(options.getImageFolder().replace('\\', '/') + "/");
            }
        }
        return new SdmHtmlWriter(writerOptions).write(sdm);
    }

    /**
     * Builds the enriched, reflow-ready {@link SdmDocument} for a document — the
     * whole PDF&rarr;SDM half of the structural pipeline, shared by every
     * downstream serializer (HTML, DOCX, &hellip;). Runs the shallow projection,
     * the tagged/heuristic enrichers, then the presentation-layer passes
     * (redaction/highlight tint, running header/footer suppression, vector-region
     * rasterization, reading-order normalization) and records the page margins on
     * the metadata. The document model is never mutated.
     *
     * @param doc     the open document; must not be null
     * @param options save options controlling heuristics, header/footer
     *                suppression and vector rasterization; null = defaults
     * @return the enriched SDM document
     * @throws IOException if page content cannot be read
     */
    public static SdmDocument buildStructuralSdm(Document doc, HtmlSaveOptions options) throws IOException {
        return buildStructuralSdm(doc, options, false);
    }

    /**
     * {@link #buildStructuralSdm(Document, HtmlSaveOptions)} with a flow-target
     * switch: an editable flow serializer (DOCX) must keep prose as text, so
     * full-page vector regions carrying real text are not rasterized (the HTML
     * targets keep them for visual fidelity — the raster sits behind the text).
     *
     * @param doc        the open document; must not be null
     * @param options    save options; null = defaults
     * @param flowTarget true when the consumer is an editable flow target (DOCX)
     * @return the enriched SDM document
     * @throws IOException if page content cannot be read
     */
    public static SdmDocument buildStructuralSdm(Document doc, HtmlSaveOptions options,
            boolean flowTarget) throws IOException {
        return buildStructuralSdm(doc, options, flowTarget, false);
    }

    /**
     * As {@link #buildStructuralSdm(Document, HtmlSaveOptions, boolean)} but with
     * an explicit {@code forceFixedPages} flag: when true (DOCX
     * {@link org.aspose.pdf.DocSaveOptions.RecognitionMode#Textbox} mode) EVERY
     * page is converted to a fixed-layout underlay + positioned text frames, the
     * DOCX analogue of the FIXED_LAYOUT HTML export. Only meaningful for a flow
     * target.
     *
     * @param doc             the open source document
     * @param options         shared enrichment flags; null = defaults
     * @param flowTarget      true when the consumer is an editable flow target (DOCX)
     * @param forceFixedPages true = fix every page (Textbox mode)
     * @return the enriched SDM document
     * @throws IOException if page content cannot be read
     */
    public static SdmDocument buildStructuralSdm(Document doc, HtmlSaveOptions options,
            boolean flowTarget, boolean forceFixedPages) throws IOException {
        if (doc == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (options == null) {
            options = new HtmlSaveOptions();
        }
        PdfSdmReader.Result projection = new PdfSdmReader().read(doc);
        SdmDocument sdm = projection.getSdm();
        // Tint runs marked by a redaction/highlight annotation BEFORE enrichment,
        // while each run still maps 1:1 to a geometry box; the tint then rides the
        // run objects into table cells. Presentation-only (kept out of enrich()).
        org.aspose.pdf.sdm.enrich.RedactionHighlightEnricher.enrich(doc, sdm, projection.getPgm());
        // Project PDF /Link annotations (external URIs + internal GoTo cross-refs)
        // onto the flow as run tags, while runs still map 1:1 to geometry boxes.
        // Flow targets (DOCX) render them as w:hyperlink / HYPERLINK fields with
        // bookmarks; the HTML path leaves the tags for a future <a> pass.
        if (flowTarget) {
            try {
                org.aspose.pdf.sdm.enrich.LinkAnnotationEnricher.enrich(
                        doc, sdm, projection.getPgm());
            } catch (RuntimeException e) {
                LOG.warning("link-annotation projection failed — continuing without: " + e);
            }
        }
        enrich(doc, projection, options);
        // Presentation-layer cleanup for the reflowed view only — kept OUT of
        // enrich() so the core PDF→SDM projection stays lossless (the two-link
        // oracle guards that contract). A page-less reflow stream has no place for
        // per-page running headers/footers, so drop them here.
        if (options.isSuppressRunningHeadersFooters()) {
            org.aspose.pdf.sdm.enrich.RunningHeaderFooterEnricher.enrich(
                    sdm, projection.getPgm());
        }
        // Rasterize vector-graphics regions (charts, shapes, fills) into <img>
        // figures — the writers emit no path/fill/stroke content, so without
        // this a chart renders as its text labels over nothing. Presentation
        // layer (may delete baked-label text blocks), so NOT part of enrich().
        // A poster/map page (scattered labels over page-filling artwork) has no
        // meaningful reading flow: for flow targets it becomes a text-suppressed
        // underlay render + absolutely positioned text frames (the DOCX analogue
        // of FIXED_LAYOUT HTML). The raster/rule passes must skip those pages.
        java.util.Set<Integer> fixedPages = java.util.Collections.emptySet();
        if (flowTarget) {
            try {
                fixedPages = org.aspose.pdf.sdm.enrich.FixedLayoutPageEnricher.enrich(
                        doc, sdm, projection.getPgm(), forceFixedPages);
            } catch (RuntimeException e) {
                LOG.warning("fixed-layout page handling failed — continuing without: " + e);
            }
        }
        if (options.isRasterizeVectorGraphics()) {
            try {
                org.aspose.pdf.sdm.enrich.VectorGraphicsEnricher.enrich(
                        doc, sdm, projection.getPgm(), flowTarget, fixedPages);
            } catch (RuntimeException e) {
                LOG.warning("vector-region rasterization failed — continuing without: " + e);
            }
        }
        // Flow targets emit no path content, so page-wide separator rules
        // (letterhead underlines) would vanish — re-detect them semantically.
        if (flowTarget) {
            try {
                org.aspose.pdf.sdm.enrich.HorizontalRuleEnricher.enrich(
                        doc, sdm, projection.getPgm(), fixedPages);
            } catch (RuntimeException e) {
                LOG.warning("horizontal-rule detection failed — continuing without: " + e);
            }
        }
        // Restore geometric reading order for the reflow: tagged-structure order
        // and geometry-blind image recovery can leave the flow as "all images then
        // all text". A page-less stream reads best top-to-bottom by position.
        org.aspose.pdf.sdm.enrich.ReadingOrderNormalizer.normalize(sdm, projection.getPgm());
        // Reinstate the page's text margins so the reflowed body is inset from the
        // edges the way the source is, instead of jammed flush against the left.
        recordPageMargins(sdm, projection.getPgm());
        return sdm;
    }

    /**
     * Measures the page text margins (left, top, right in points) from the geometry
     * across ALL pages and stores them on the document metadata, so the writer can
     * inset {@code <body>} the way the source insets its text. Uses ROBUST
     * percentiles, not extremes: the left margin is the 10th-percentile left edge
     * (a few stray far-left boxes — a marginal note, a mis-measured rule — do not
     * shrink the inset), the right margin the 90th-percentile right reach, the top
     * the 95th-percentile highest line. Every page shares the same margins, so the
     * percentiles are dominated by the true, repeated text-column edges.
     */
    private static void recordPageMargins(SdmDocument sdm, org.aspose.pdf.pgm.PgmModel pgm) {
        if (pgm.getPages().isEmpty()) {
            return;
        }
        double pageW = pgm.getPages().get(0).getWidth();
        double pageH = pgm.getPages().get(0).getHeight();
        java.util.List<Double> lefts = new java.util.ArrayList<>();
        java.util.List<Double> rights = new java.util.ArrayList<>();
        java.util.List<Double> tops = new java.util.ArrayList<>();
        for (org.aspose.pdf.pgm.PgmPage p : pgm.getPages()) {
            for (org.aspose.pdf.pgm.PgmBox b : p.getBoxes()) {
                if (b.getKind() != org.aspose.pdf.pgm.PgmBoxKind.TEXT) {
                    continue;
                }
                lefts.add(b.getRect().getX());
                rights.add(b.getRect().getX() + b.getRect().getW());
                tops.add(b.getRect().getTop());
            }
        }
        if (lefts.size() < 4 || pageW <= 0 || pageH <= 0) {
            return;
        }
        double left = Math.max(0, percentile(lefts, 10));
        double right = Math.max(0, pageW - percentile(rights, 90));
        // Top uses the highest line (the header is legitimate top-of-page content),
        // clamped by a near-max percentile so a lone stray-high box cannot zero it.
        double top = Math.max(0, pageH - percentile(tops, 99));
        // Only bother when there is a meaningful inset (avoid sub-point noise).
        if (left >= 2 || top >= 2 || right >= 2) {
            java.util.Map<String, String> custom = sdm.getMetadata().getCustom();
            custom.put("margin-left", String.format(java.util.Locale.ROOT, "%.2f", left));
            custom.put("margin-right", String.format(java.util.Locale.ROOT, "%.2f", right));
            custom.put("margin-top", String.format(java.util.Locale.ROOT, "%.2f", top));
        }
    }

    /** The p-th percentile (0..100) of the values, by nearest-rank on a sorted copy. */
    private static double percentile(java.util.List<Double> values, double p) {
        java.util.List<Double> sorted = new java.util.ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.round(p / 100.0 * (sorted.size() - 1));
        idx = Math.max(0, Math.min(sorted.size() - 1, idx));
        return sorted.get(idx);
    }

    /**
     * Applies the Stage-3 enrichment passes to the shallow projection.
     * PART 1 ships the shallow pass-through; PART 2 adds the tagged enricher,
     * PART 3 the heuristic one. Kept package-visible for pipeline tests.
     *
     * @param doc        the source document
     * @param projection the shallow SDM+PGM projection (mutated in place)
     * @param options    save options controlling the heuristics toggle
     */
    static void enrich(Document doc, PdfSdmReader.Result projection, HtmlSaveOptions options) {
        try {
            if (org.aspose.pdf.sdm.enrich.TaggedSdmEnricher.isTagged(doc)) {
                boolean applied = new org.aspose.pdf.sdm.enrich.TaggedSdmEnricher()
                        .enrich(doc, projection.getSdm(), projection.getPgm());
                LOG.fine(() -> "structural enrichment: tagged tree "
                        + (applied ? "applied" : "present but not applied"));
                // Many tagged trees are shallow and never mark their visual tables
                // (no /Table /TR /TD), leaving a ruled table flattened to prose.
                // Recover those geometrically — the heading/list heuristics stay
                // off (the author's tags own those).
                if (options.isStructuralHeuristics()) {
                    new org.aspose.pdf.sdm.enrich.HeuristicSdmEnricher()
                            .enrichRuledTablesOnly(doc, projection.getSdm(), projection.getPgm());
                }
            } else if (options.isStructuralHeuristics()) {
                boolean applied = new org.aspose.pdf.sdm.enrich.HeuristicSdmEnricher()
                        .enrich(doc, projection.getSdm(), projection.getPgm());
                LOG.fine(() -> "structural enrichment: heuristic "
                        + (applied ? "applied" : "no upgrades"));
            } else {
                LOG.fine(() -> "structural enrichment: shallow (heuristics disabled)");
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning("tagged enrichment failed — falling back to shallow structural: " + e);
        }
        // Recover raster images the shallow reader missed (form-nested / inline)
        // so a cover collage or form-wrapped photo is not lost from the flow.
        org.aspose.pdf.sdm.enrich.ImagePlacementEnricher.enrich(
                doc, projection.getSdm(), projection.getPgm());
        // Coloured fill rectangles → block background-colour (reflow-safe).
        org.aspose.pdf.sdm.enrich.FillBackgroundEnricher.enrich(
                projection.getSdm(), projection.getPgm());
        // LAST: restore extractor-parity whitespace between runs (space runs
        // have no boxes, so nothing that needs run↔box alignment may run later).
        org.aspose.pdf.sdm.enrich.RunSpacingNormalizer.normalize(
                projection.getSdm(), projection.getPgm());
    }
}
