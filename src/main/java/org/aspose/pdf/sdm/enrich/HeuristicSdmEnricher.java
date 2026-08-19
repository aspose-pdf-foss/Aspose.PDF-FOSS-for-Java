package org.aspose.pdf.sdm.enrich;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.text.AbsorbedCell;
import org.aspose.pdf.text.AbsorbedRow;
import org.aspose.pdf.text.AbsorbedTable;
import org.aspose.pdf.text.TableAbsorber;

/**
 * Heuristic structural enricher for UNTAGGED documents — IR Stage 3 PART 3
 * (the main case: most PDFs carry no structure tree). Reconstructs semantics
 * from geometry with HONEST DEGRADATION: when a signal is ambiguous the
 * content stays a Paragraph — the heuristics never guess up.
 *
 * <p><b>Thresholds — calibrated by the PART 3a corpus probe
 * (IR_S3_HEURISTIC_PROBE.csv, 100 untagged docs):</b></p>
 * <ul>
 *   <li><b>Heading size factor F = 1.15.</b> Short standalone lines by
 *       size-ratio-to-body: 3134 at &lt;1.05, 205 in the 1.05–1.15 band,
 *       188+79+226 at ≥1.15. The 1.05–1.15 band has the same short-line density
 *       as the page at large (205/250 vs 3134/4902) — it is emphasis/labels, not
 *       headings; ≥1.15 is where candidates separate.</li>
 *   <li><b>Standalone-line rule:</b> single visual line, ≤90 chars, line width
 *       &lt; 0.75× the page's p95 line width (probe: 76–83% of oversized lines
 *       already satisfy it — headings are short standalone lines).</li>
 *   <li><b>Bold at body size does NOT promote.</b> The probe's spacing signal is
 *       weak (gap-above/median-gap p25 = 1.00), so bold-only candidates cannot be
 *       separated from form labels and emphasized text with ≥90% precision —
 *       a deliberate recall sacrifice (documented degradation).</li>
 *   <li><b>Trailing period demotes.</b> A large-type LEAD SENTENCE is the classic
 *       false positive; headings almost never end with '.'.</li>
 *   <li><b>List markers</b> (probe: decimal=359, bullet=149, alpha=146 lines):
 *       bullet/decimal/alpha/roman at line start, ≥2 consecutive items, nested by
 *       ≥12pt indent steps, continuation lines by ≥8pt hanging indent.</li>
 *   <li><b>Tables</b> come from the proven ruled-grid {@link TableAbsorber}
 *       (Stage G5); a grid must claim ≥2 paragraphs and have ≥2 rows × ≥2
 *       columns to materialize — vector-only grids degrade to nothing.</li>
 *   <li><b>Figure captions</b> reuse the Stage-1 ANCHORED linking
 *       ({@link FlowClassifier}): text anchored to an image box becomes the
 *       figure's caption. FIXED (chrome) text is excluded from heading/list
 *       candidates — page headers are exactly the short big lines that would
 *       otherwise false-positive.</li>
 * </ul>
 *
 * <p>Retype/regroup only: a promoted node keeps its GUID, sourceRef, style and
 * inline list (same invariant as the tagged enricher).</p>
 */
public final class HeuristicSdmEnricher {

    private static final Logger LOG = Logger.getLogger(HeuristicSdmEnricher.class.getName());

    /** Probe-calibrated: minimum size ratio to the body baseline for a heading. */
    public static final double HEADING_SIZE_FACTOR = 1.15;
    /** Probe-calibrated: max characters of a standalone heading line. */
    public static final int HEADING_MAX_CHARS = 90;
    /** Probe-calibrated: heading line width < this fraction of the page p95 width. */
    public static final double HEADING_MAX_WIDTH_FRAC = 0.75;
    /** Hanging-indent threshold for list continuation lines (pt). */
    public static final double HANG_INDENT = 8;
    /** Indent step that opens a nested list level (pt). */
    public static final double NEST_INDENT = 12;
    /** Sparse-page guard: pages with less context text than this get no headings. */
    public static final int SPARSE_PAGE_CHARS = 40;
    /** Watermark-zone guard: this ratio in the middle third of the page is a watermark
     *  (labeled sample: the "Aspose.Pdf" text watermark measures 30pt over a 14pt body = 2.14). */
    public static final double WATERMARK_RATIO = 2.0;
    /** Top-right corner band (pt from the page top) where oversized short lines are
     *  document codes/stamps, not headings (labeled sample: ASME code at 114pt). */
    public static final double CODE_BAND = 130;

    private static final Pattern BULLET = Pattern.compile("^[•●◦▪·∙*–—-]\\s?.*");
    private static final Pattern DECIMAL = Pattern.compile("^\\(?(\\d{1,3})[.)].*");
    private static final Pattern ALPHA = Pattern.compile("^\\(?[a-zA-Z][.)]\\s.*");
    private static final Pattern ROMAN = Pattern.compile("^\\(?[ivxIVX]{1,5}[.)]\\s.*");

    /** Geometry digest of one top-level block. */
    private static final class Info {
        int pageIdx = -1;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double topBaseline = -Double.MAX_VALUE;
        double maxSize;
        int lineCount;
        boolean anyFixed;
        String text = "";
    }

    private final Map<SdmBlock, Info> infos = new IdentityHashMap<>();
    private PgmModel pgmRef;
    private double[] pageAcw = new double[0];
    private java.util.UUID nsDoc;

    /**
     * Applies the heuristic layers (tables &rarr; lists &rarr; headings &rarr;
     * figure captions) to an untagged shallow projection, in place.
     *
     * @param doc the open document (needed for the ruled-grid table absorber)
     * @param sdm the shallow SDM (mutated)
     * @param pgm the PGM geometry
     * @return true when any node was retyped or regrouped
     * @throws IOException if page content cannot be read
     */
    public boolean enrich(Document doc, SdmDocument sdm, PgmModel pgm) throws IOException {
        if (doc == null || sdm == null || pgm == null) {
            return false;
        }
        // Chrome exclusion + caption anchors come from the Stage-1 classifier.
        try {
            FlowClassifier.classify(pgm);
        } catch (RuntimeException e) {
            LOG.fine(() -> "flow classification failed, heuristics run without chrome info: " + e);
        }
        this.pgmRef = pgm;
        this.pageAcw = SpacingRule.pageAvgCharWidth(pgm);
        this.nsDoc = sdm.getNsDoc();
        buildInfos(sdm, pgm);

        int changes = 0;
        changes += applyTables(doc, sdm, pgm);
        changes += applyLists(sdm);
        changes += applyColumnTables(sdm);
        changes += applyHeadings(sdm, pgm);
        changes += applyFigureCaptions(sdm, pgm);
        final int total = changes;
        LOG.fine(() -> "heuristic enrichment: " + total + " upgrades");
        return changes > 0;
    }

    /**
     * Runs ONLY the ruled-grid table detection — the pass a tagged document still
     * needs. Many tagged PDFs carry a shallow structure tree that never marks
     * their visual tables (no {@code /Table}/{@code /TR}/{@code /TD}), so the
     * {@code TaggedSdmEnricher} leaves a ruled table flattened into paragraphs.
     * This recovers those tables geometrically without re-running the heading and
     * list heuristics the author's tags already own.
     *
     * @param doc the source document
     * @param sdm the structural model (mutated in place)
     * @param pgm the page geometry model
     * @return true when at least one table was materialized
     * @throws IOException if a page's content cannot be read
     */
    public boolean enrichRuledTablesOnly(Document doc, SdmDocument sdm, PgmModel pgm) throws IOException {
        if (doc == null || sdm == null || pgm == null) {
            return false;
        }
        try {
            FlowClassifier.classify(pgm);
        } catch (RuntimeException e) {
            LOG.fine(() -> "flow classification failed, table pass runs without chrome info: " + e);
        }
        this.pgmRef = pgm;
        this.pageAcw = SpacingRule.pageAvgCharWidth(pgm);
        this.nsDoc = sdm.getNsDoc();
        buildInfos(sdm, pgm);
        int changes = applyTables(doc, sdm, pgm);
        LOG.fine(() -> "tagged-doc table recovery: " + changes + " table(s)");
        return changes > 0;
    }

    // ------------------------------------------------------------------ infos

    private void buildInfos(SdmDocument sdm, PgmModel pgm) {
        buildInfos(sdm.getChildren(), pgm);
    }

    /** Builds geometry Info for every block in the flow, recursing containers so
     *  a tagged tree's nested paragraphs are visible to the table pass. */
    private void buildInfos(List<SdmBlock> blocks, PgmModel pgm) {
        for (SdmBlock b : blocks) {
            Info info = new Info();
            List<PgmBox> boxes = b.getId() == null ? java.util.Collections.emptyList()
                    : pgm.byId(b.getId());
            Set<Long> bands = new java.util.HashSet<>();
            for (PgmBox box : boxes) {
                info.pageIdx = box.getPage();
                info.minX = Math.min(info.minX, box.getRect().getX());
                info.maxX = Math.max(info.maxX, box.getRect().getX() + box.getRect().getW());
                info.anyFixed |= box.getFlowClass() == FlowClass.FIXED;
                if (box.getData() instanceof TextBoxData) {
                    TextBoxData d = (TextBoxData) box.getData();
                    info.maxSize = Math.max(info.maxSize, d.getFontSize());
                    info.topBaseline = Math.max(info.topBaseline, d.getBaselineY());
                    bands.add(Math.round(d.getBaselineY() / 2.0));
                }
            }
            info.lineCount = bands.size();
            if (b instanceof Paragraph) {
                info.text = ((Paragraph) b).getText() == null ? "" : ((Paragraph) b).getText().trim();
            }
            infos.put(b, info);
            if (b instanceof org.aspose.pdf.sdm.Container) {
                buildInfos(((org.aspose.pdf.sdm.Container) b).getChildren(), pgm);
            } else if (b instanceof org.aspose.pdf.sdm.Quote) {
                buildInfos(((org.aspose.pdf.sdm.Quote) b).getChildren(), pgm);
            }
        }
    }

    private Info infoOf(SdmBlock b) {
        return infos.getOrDefault(b, new Info());
    }

    // ------------------------------------------------------------------ headings

    private int applyHeadings(SdmDocument sdm, PgmModel pgm) {
        double baseSize = bodyBaselineSize(pgm);
        if (baseSize <= 0) {
            return 0;
        }
        Map<Integer, Double> pageWide = pageP95Width(pgm);

        // Per-page context volume for the sparse-page guard.
        Map<Integer, Integer> pageChars = new HashMap<>();
        for (org.aspose.pdf.pgm.PgmPage p : pgm.getPages()) {
            int chars = 0;
            for (PgmBox box : p.getBoxes()) {
                if (box.getKind() == PgmBoxKind.TEXT && box.getData() instanceof TextBoxData) {
                    String t = ((TextBoxData) box.getData()).getText();
                    chars += t == null ? 0 : t.trim().length();
                }
            }
            pageChars.put(p.getIndex(), chars);
        }

        // Pass 1: collect candidates and their size buckets.
        // Precision guards (each diagnosed on the labeled sample):
        //  P — sentence punctuation (./!/?) = lead sentence, not a heading;
        //  S — sparse page (<40 context chars): the body-size mode is meaningless,
        //      title slides false-promote incidental small text;
        //  C — short oversized line in the TOP-RIGHT corner = document code/stamp;
        //  W — ratio ≥2.2 in the middle third of the page = text watermark.
        List<SdmBlock> children = sdm.getChildren();
        Map<SdmBlock, Long> candidateSize = new IdentityHashMap<>();
        TreeMap<Long, Integer> levelBySize = new TreeMap<>(java.util.Collections.reverseOrder());
        for (SdmBlock b : children) {
            if (!(b instanceof Paragraph)) {
                continue;
            }
            Info info = infoOf(b);
            if (info.anyFixed || info.lineCount != 1 || info.text.isEmpty()) {
                continue;
            }
            double ratio = info.maxSize / baseSize;
            double wide = pageWide.getOrDefault(info.pageIdx, 0.0);
            boolean standalone = info.text.length() <= HEADING_MAX_CHARS
                    && (wide <= 0 || (info.maxX - info.minX) < HEADING_MAX_WIDTH_FRAC * wide);
            boolean leadSentence = info.text.endsWith(".") || info.text.endsWith("!")
                    || info.text.endsWith("?");
            if (ratio < HEADING_SIZE_FACTOR || !standalone || leadSentence) {
                continue;
            }
            int context = pageChars.getOrDefault(info.pageIdx, 0) - info.text.length();
            if (context < SPARSE_PAGE_CHARS) {
                continue; // S
            }
            double pageW = 0;
            double pageH = 0;
            if (info.pageIdx >= 0 && info.pageIdx < pgm.getPages().size()) {
                pageW = pgm.getPage(info.pageIdx).getWidth();
                pageH = pgm.getPage(info.pageIdx).getHeight();
            }
            if (pageH > 0 && info.topBaseline >= pageH - CODE_BAND && pageW > 0
                    && info.minX > 0.6 * pageW) {
                continue; // C
            }
            if (pageH > 0 && ratio >= WATERMARK_RATIO
                    && info.topBaseline > pageH / 3 && info.topBaseline < 2 * pageH / 3) {
                continue; // W
            }
            long bucket = Math.round(info.maxSize * 2);
            candidateSize.put(b, bucket);
            levelBySize.put(bucket, 0);
        }
        if (candidateSize.isEmpty()) {
            return 0;
        }
        int level = 1;
        for (Map.Entry<Long, Integer> e : levelBySize.entrySet()) {
            e.setValue(Math.min(6, level++));
        }
        // Pass 2: retype in place (GUID preserved).
        int changes = 0;
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            Long bucket = candidateSize.get(b);
            if (bucket != null) {
                children.set(i, toHeading((Paragraph) b, levelBySize.get(bucket)));
                changes++;
            }
        }
        return changes;
    }

    /** Length-weighted mode of text-box font sizes (0.5pt buckets) — the body size. */
    private static double bodyBaselineSize(PgmModel pgm) {
        Map<Long, Integer> weights = new HashMap<>();
        for (org.aspose.pdf.pgm.PgmPage p : pgm.getPages()) {
            for (PgmBox b : p.getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData) {
                    TextBoxData d = (TextBoxData) b.getData();
                    int w = d.getText() == null ? 0 : d.getText().trim().length();
                    if (w > 0 && d.getFontSize() > 0) {
                        weights.merge(Math.round(d.getFontSize() * 2), w, Integer::sum);
                    }
                }
            }
        }
        return weights.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() / 2.0).orElse(0.0);
    }

    /** p95 of visual-line widths per page (the "full line" width reference). */
    private static Map<Integer, Double> pageP95Width(PgmModel pgm) {
        Map<Integer, Double> out = new HashMap<>();
        for (org.aspose.pdf.pgm.PgmPage p : pgm.getPages()) {
            Map<Long, double[]> lines = new TreeMap<>(); // band -> [minX, maxX]
            for (PgmBox b : p.getBoxes()) {
                if (b.getKind() != PgmBoxKind.TEXT || !(b.getData() instanceof TextBoxData)) {
                    continue;
                }
                long band = Math.round(((TextBoxData) b.getData()).getBaselineY() / 2.0);
                double[] mm = lines.computeIfAbsent(band,
                        k -> new double[]{Double.MAX_VALUE, -Double.MAX_VALUE});
                mm[0] = Math.min(mm[0], b.getRect().getX());
                mm[1] = Math.max(mm[1], b.getRect().getX() + b.getRect().getW());
            }
            List<Double> widths = new ArrayList<>();
            for (double[] mm : lines.values()) {
                if (mm[1] > mm[0]) {
                    widths.add(mm[1] - mm[0]);
                }
            }
            widths.sort(null);
            if (!widths.isEmpty()) {
                out.put(p.getIndex(), widths.get((int) Math.min(widths.size() - 1,
                        widths.size() * 0.95)));
            }
        }
        return out;
    }

    /** Retype preserving GUID/sourceRef/style/attributes/inline (same invariant as PART 2). */
    private static Heading toHeading(Paragraph p, int level) {
        Heading h = new Heading(level);
        h.setId(p.getId());
        h.setSourceRef(p.getSourceRef());
        h.getAttributes().putAll(p.getAttributes());
        h.getInline().addAll(p.getInline());
        h.setStyle(p.getStyle());
        return h;
    }

    // ------------------------------------------------------------------ lists

    private enum MarkerKind { BULLET, DECIMAL, ALPHA, ROMAN, NONE }

    private static MarkerKind markerOf(String text) {
        if (text == null || text.isEmpty()) {
            return MarkerKind.NONE;
        }
        if (BULLET.matcher(text).matches()) {
            return MarkerKind.BULLET;
        }
        if (DECIMAL.matcher(text).matches()) {
            return MarkerKind.DECIMAL;
        }
        if (ALPHA.matcher(text).matches()) {
            return MarkerKind.ALPHA;
        }
        if (ROMAN.matcher(text).matches()) {
            return MarkerKind.ROMAN;
        }
        return MarkerKind.NONE;
    }

    private int applyLists(SdmDocument sdm) {
        List<SdmBlock> children = sdm.getChildren();
        List<SdmBlock> result = new ArrayList<>(children.size());
        int changes = 0;
        int i = 0;
        while (i < children.size()) {
            SdmBlock b = children.get(i);
            Info info = infoOf(b);
            MarkerKind kind = b instanceof Paragraph && !info.anyFixed
                    ? markerOf(info.text) : MarkerKind.NONE;
            if (kind == MarkerKind.NONE) {
                result.add(b);
                i++;
                continue;
            }
            // Collect the run of same-page marker items of ANY marker kind
            // (kind may change at a nesting level) + hanging continuations.
            List<List<SdmBlock>> items = new ArrayList<>();
            List<Double> itemX = new ArrayList<>();
            List<MarkerKind> itemKind = new ArrayList<>();
            int j = i;
            while (j < children.size()) {
                SdmBlock cand = children.get(j);
                Info ci = infoOf(cand);
                if (!(cand instanceof Paragraph) || ci.anyFixed || ci.pageIdx != info.pageIdx) {
                    break;
                }
                MarkerKind mk = markerOf(ci.text);
                if (mk != MarkerKind.NONE) {
                    items.add(new ArrayList<>(java.util.Collections.singletonList(cand)));
                    itemX.add(ci.minX);
                    itemKind.add(mk);
                    j++;
                } else if (!items.isEmpty()
                        && ci.minX > itemX.get(itemX.size() - 1) + HANG_INDENT) {
                    items.get(items.size() - 1).add(cand); // hanging continuation
                    j++;
                } else {
                    break;
                }
            }
            if (items.size() < 2) {
                // degradation: a lone marker line is NOT a list
                result.add(b);
                i++;
                continue;
            }
            result.add(buildNestedList(items, itemX, itemKind));
            changes++;
            i = j;
        }
        if (changes > 0) {
            children.clear();
            children.addAll(result);
        }
        return changes;
    }

    /**
     * Builds a (possibly two-level) list from collected marker items: items
     * indented ≥{@link #NEST_INDENT} beyond the base column nest under the
     * preceding base item (deeper levels clamp to one nesting level — v1).
     */
    private static ListBlock buildNestedList(List<List<SdmBlock>> items, List<Double> itemX,
                                             List<MarkerKind> itemKind) {
        double base = Double.MAX_VALUE;
        for (double x : itemX) {
            base = Math.min(base, x);
        }
        ListBlock root = newList(itemKind.get(0), infoTextOf(items.get(0)));
        ListBlock nested = null;
        ListItem lastRootItem = null;
        for (int k = 0; k < items.size(); k++) {
            boolean deep = itemX.get(k) > base + NEST_INDENT && lastRootItem != null;
            ListItem li = new ListItem();
            li.getChildren().addAll(items.get(k));
            if (deep) {
                if (nested == null) {
                    nested = newList(itemKind.get(k), infoTextOf(items.get(k)));
                    lastRootItem.getChildren().add(nested);
                }
                nested.getItems().add(li);
            } else {
                nested = null;
                root.getItems().add(li);
                lastRootItem = li;
            }
        }
        return root;
    }

    private static String infoTextOf(List<SdmBlock> item) {
        return item.isEmpty() ? "" : textOf(item.get(0)).trim();
    }

    private static ListBlock newList(MarkerKind kind, String firstText) {
        boolean ordered = kind != MarkerKind.BULLET;
        Integer start = null;
        if (kind == MarkerKind.DECIMAL) {
            java.util.regex.Matcher m = DECIMAL.matcher(firstText);
            if (m.matches()) {
                try {
                    start = Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {
                    // non-numeric label — leave start unset
                }
            }
        }
        ListBlock list = new ListBlock(ordered, start);
        list.setMarkerStyle(kind.name().toLowerCase(Locale.ROOT));
        return list;
    }

    // ------------------------------------------------------------------ tables

    private int applyTables(Document doc, SdmDocument sdm, PgmModel pgm) throws IOException {
        int changes = 0;
        int pages = doc.getPages().getCount();
        for (int p = 1; p <= Math.min(pages, pgm.getPages().size()); p++) {
            Page page = doc.getPages().get(p);
            List<AbsorbedTable> tables;
            try {
                TableAbsorber absorber = new TableAbsorber();
                absorber.visit(page);
                tables = absorber.getTableList();
            } catch (RuntimeException e) {
                LOG.fine(() -> "table absorber failed on a page: " + e);
                continue;
            }
            for (AbsorbedTable at : tables) {
                // The claimable paragraphs may sit at the top level OR nested in a
                // container (a tagged tree wraps a page's flow in a Sect/Div). Try
                // each flow list; a table's rows live in exactly one of them.
                List<List<SdmBlock>> flows = new ArrayList<>();
                collectFlows(sdm.getChildren(), flows);
                for (List<SdmBlock> flow : flows) {
                    if (materializeTable(flow, at, p - 1) > 0) {
                        changes++;
                        break;
                    }
                }
            }
        }
        return changes;
    }

    /** Gathers every mutable block-flow list in the tree (top level + containers). */
    private void collectFlows(List<SdmBlock> blocks, List<List<SdmBlock>> out) {
        out.add(blocks);
        for (SdmBlock b : blocks) {
            if (b instanceof org.aspose.pdf.sdm.Container) {
                collectFlows(((org.aspose.pdf.sdm.Container) b).getChildren(), out);
            } else if (b instanceof org.aspose.pdf.sdm.Quote) {
                collectFlows(((org.aspose.pdf.sdm.Quote) b).getChildren(), out);
            }
        }
    }

    private int materializeTable(List<SdmBlock> children, AbsorbedTable at, int pageIdx) {
        List<AbsorbedRow> rows = at.getRowList();
        if (rows == null || rows.size() < 2) {
            return 0;
        }
        int maxCols = 0;
        Map<Integer, Integer> colCountHist = new HashMap<>();
        for (AbsorbedRow r : rows) {
            int c = r.getCellList().size();
            maxCols = Math.max(maxCols, c);
            colCountHist.merge(c, 1, Integer::sum);
        }
        if (maxCols < 2) {
            return 0;
        }
        // RAGGEDNESS: a genuine grid has a uniform column count per row; a
        // key/value FORM (or the fragment-grouping fallback when no ruling lines
        // were found) yields wildly ragged rows — 2 cells here, 8 there — and
        // tabling that as-is shreds "13024-10001-0001" into "13024 | - | 10001 |
        // - | 0001". When the ragged rows still have a SMALL dominant shape (a
        // 2/3-column label:value form), COLLAPSE every row onto that shape's column
        // boundaries so the value stays whole; otherwise degrade to prose.
        int dominantFreq = 0;
        for (int v : colCountHist.values()) {
            dominantFreq = Math.max(dominantFreq, v);
        }
        if (maxCols >= 4 && dominantFreq < 0.6 * rows.size()) {
            return 0; // ragged merged-cell form / prose mis-grouped — keep prose
        }
        // Claim content per RUN: the extractor merges same-line cells into one
        // visual paragraph, so a cell claims the runs whose box centres sit in
        // its rect; a paragraph split across cells yields per-cell sub-paragraphs
        // (same Run objects, deterministic sub-range ids — the PART 2 invariant).
        Map<AbsorbedCell, List<SdmBlock>> byCell = new LinkedHashMap<>();
        Map<SdmBlock, Boolean> claimed = new IdentityHashMap<>();
        int firstIdx = Integer.MAX_VALUE;
        List<AbsorbedCell> allCells = new ArrayList<>();
        for (AbsorbedRow r : rows) {
            allCells.addAll(r.getCellList());
        }
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            if (!(b instanceof Paragraph)) {
                continue;
            }
            Info info = infoOf(b);
            if (info.pageIdx != pageIdx || info.anyFixed) {
                continue;
            }
            Map<AbsorbedCell, List<SdmBlock>> pieces = splitAcrossCells((Paragraph) b, allCells);
            if (pieces == null || pieces.isEmpty()) {
                continue;
            }
            for (Map.Entry<AbsorbedCell, List<SdmBlock>> e : pieces.entrySet()) {
                byCell.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
            }
            claimed.put(b, Boolean.TRUE);
            firstIdx = Math.min(firstIdx, i);
        }
        if (claimed.size() < 2) {
            return 0; // vector-only or near-empty grid — degrade to nothing
        }
        // DOUBLE-PRINT: some producers paint a line twice (bold-by-overprint);
        // the copies coincide pixel-perfectly in the PDF but arrive as TWO
        // identical paragraphs claimed into the same cell ("Sponsor's
        // assessment" x2). Keep one of adjacent identical-text pieces.
        for (List<SdmBlock> content : byCell.values()) {
            for (int k = content.size() - 1; k > 0; k--) {
                SdmBlock cur = content.get(k);
                SdmBlock prev = content.get(k - 1);
                if (cur instanceof Paragraph && prev instanceof Paragraph) {
                    String a = ((Paragraph) cur).getText();
                    String bTxt = ((Paragraph) prev).getText();
                    if (a != null && !a.trim().isEmpty() && a.trim().equals(
                            bTxt == null ? null : bTxt.trim())) {
                        content.remove(k);
                    }
                }
            }
        }
        // PER-GLYPH GUARD: a spurious grid (ruled rules that happen to fall
        // between individual glyphs of monospace/positioned prose) puts ~one
        // character in every cell, so the "table" has as many wide columns as
        // the line has glyphs. Tabling it shreds prose into a per-letter grid
        // that then round-trips glued ("Wehavecreated"). A genuine table's
        // cells hold words/numbers — reject when a wide grid averages under two
        // characters per filled cell.
        int nonEmptyCells = 0;
        long cellChars = 0;
        for (List<SdmBlock> content : byCell.values()) {
            long len = blockTextLength(content);
            if (len > 0) {
                nonEmptyCells++;
                cellChars += len;
            }
        }
        if (maxCols >= 6 && nonEmptyCells >= 8
                && (double) cellChars / nonEmptyCells < 2.0) {
            return 0; // per-glyph mis-detection — keep the prose paragraph(s)
        }
        // SPARSE GUARD: a poster/worksheet page framed with decorative boxes
        // yields a huge ruled "grid" whose cells are mostly EMPTY — the ink is
        // pictures and scattered captions, not tabular data (59751: 19x17 grid,
        // 43 of 323 cells filled). A genuine data table is densely populated
        // even when some value cells are blank. Emitting the sparse grid drags
        // pages of empty cells into the flow and imprisons captions in random
        // cells — reject big, mostly-hollow grids and keep the prose.
        int totalCells = allCells.size();
        if (totalCells >= 24 && nonEmptyCells < 0.35 * totalCells) {
            return 0;
        }
        Table table = new Table();
        // Ruled grid → visible cell borders in the source; record it so the HTML
        // writer draws borders (a borderless geometric column table does not set
        // this and stays borderless). Carry the source rule COLOUR too so a blue
        // clinical-form grid renders blue, not a generic black.
        table.getAttributes().put("border", "ruled");
        int rc = at.getBorderColorRgb();
        if (rc >= 0) {
            table.getAttributes().put("border-color",
                    String.format("#%06x", rc & 0xFFFFFF));
        }
        AbsorbedRow first = rows.get(0);
        for (AbsorbedCell c : first.getCellList()) {
            Rectangle rect = c.getRectangle();
            table.getColumns().add(rect == null ? new ColumnSpec()
                    : new ColumnSpec(ColumnSpec.WidthType.POINTS,
                            Math.max(1, rect.getURX() - rect.getLLX()), ColumnSpec.Align.LEFT));
        }
        for (AbsorbedRow r : rows) {
            TableRow row = new TableRow(TableRow.Kind.BODY);
            for (AbsorbedCell c : r.getCellList()) {
                TableCell cell = new TableCell();
                List<SdmBlock> content = byCell.get(c);
                if (content != null) {
                    cell.getChildren().addAll(content);
                }
                row.getCells().add(cell);
            }
            table.getRows().add(row);
        }
        // Replace the first claimed block with the table; drop the rest.
        List<SdmBlock> rebuilt = new ArrayList<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            if (i == firstIdx) {
                rebuilt.add(table);
            }
            if (!claimed.containsKey(b)) {
                rebuilt.add(b);
            }
        }
        children.clear();
        children.addAll(rebuilt);
        return 1;
    }

    /**
     * Partitions a paragraph's runs among absorbed cells. A paragraph entirely
     * inside ONE cell is passed through whole (GUID intact). Otherwise per-cell
     * sub-paragraphs are built with extractor-parity spacing.
     *
     * <p><b>A run may STRADDLE a column boundary</b> — the extractor emits a whole
     * visual line ("10721001 Cerebrovascular", the Patient-ID cell glued to the
     * next cell) as one run whose box spans two cells. Assigning it whole by its
     * centre drops the Patient ID into the wrong column. So a run that spans more
     * than one cell on its row is SPLIT at the ruled column boundaries by
     * proportional character position, and each fragment joins its own cell.</p>
     *
     * <p>Returns null when the paragraph does not intersect the grid at all, or
     * when some run lands outside every cell (straddles the table edge — degrade,
     * do not claim).</p>
     */
    private Map<AbsorbedCell, List<SdmBlock>> splitAcrossCells(Paragraph p,
                                                               List<AbsorbedCell> cells) {
        if (p.getId() == null || pgmRef == null) {
            return null;
        }
        List<Run> runs = new ArrayList<>();
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run) {
                runs.add((Run) in);
            }
        }
        List<PgmBox> boxes = new ArrayList<>(pgmRef.byId(p.getId()));
        if (runs.isEmpty() || boxes.size() != runs.size()) {
            return null;
        }
        boxes.sort((a, b) -> Integer.compare(a.getPartIndex(), b.getPartIndex()));

        // Split each run across the cells of its row; accumulate ordered pieces
        // per cell. A run that lands in no cell means the paragraph straddles the
        // table edge — degrade (return null) rather than claim it partially.
        Map<AbsorbedCell, List<Piece>> byCellPieces = new LinkedHashMap<>();
        for (int i = 0; i < runs.size(); i++) {
            PgmBox box = boxes.get(i);
            List<Piece> pieces = splitRunByCells(box, runs.get(i), cells);
            if (pieces.isEmpty()) {
                return null;
            }
            for (Piece piece : pieces) {
                byCellPieces.computeIfAbsent(piece.cell, k -> new ArrayList<>()).add(piece);
            }
        }
        if (byCellPieces.isEmpty()) {
            return null;
        }

        Map<AbsorbedCell, List<SdmBlock>> out = new LinkedHashMap<>();
        // Whole paragraph in a single cell, un-split: pass it through intact.
        if (byCellPieces.size() == 1) {
            Piece only = byCellPieces.values().iterator().next().get(0);
            if (!only.split) {
                out.computeIfAbsent(only.cell, k -> new ArrayList<>()).add(p);
                return out;
            }
        }
        double acw = pageAcw.length > boxes.get(0).getPage() ? pageAcw[boxes.get(0).getPage()] : 5.0;
        for (Map.Entry<AbsorbedCell, List<Piece>> e : byCellPieces.entrySet()) {
            List<Piece> pieces = e.getValue();
            pieces.sort((a, b) -> Double.compare(a.startX, b.startX));
            Paragraph sub = new Paragraph();
            sub.setStyle(p.getStyle());
            int min = Integer.MAX_VALUE;
            int max = -1;
            int pageObjNum = -1;
            Piece prev = null;
            for (Piece piece : pieces) {
                if (prev != null && !endsWithWs(prev.text) && !startsWithWs(piece.text)
                        && SpacingRule.shouldSpace(prev.text, prev.endX, prev.baseline,
                                piece.text, piece.startX, piece.baseline, acw)) {
                    sub.getInline().add(new Run(" ", null));
                }
                sub.getInline().add(piece.split
                        ? new Run(piece.text, piece.run.getStyle()) : piece.run);
                if (piece.box.getSourceRef() instanceof org.aspose.pdf.sdm.ContentRange) {
                    org.aspose.pdf.sdm.ContentRange cr =
                            (org.aspose.pdf.sdm.ContentRange) piece.box.getSourceRef();
                    pageObjNum = cr.getPageObjNum();
                    min = Math.min(min, cr.getOpStart());
                    max = Math.max(max, cr.getOpEnd());
                }
                prev = piece;
            }
            if (max >= 0 && pageObjNum >= 0) {
                org.aspose.pdf.sdm.ContentRange subRef =
                        new org.aspose.pdf.sdm.ContentRange(pageObjNum, min, max);
                sub.setSourceRef(subRef);
                if (nsDoc != null) {
                    sub.setId(org.aspose.pdf.sdm.SdmIds.nodeId(nsDoc, subRef));
                }
            }
            out.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(sub);
        }
        return out;
    }

    /** One fragment of a run assigned to a single cell (whole run when not split). */
    private static final class Piece {
        final AbsorbedCell cell;
        final String text;
        final Run run;      // originating run (its style is reused for split text)
        final PgmBox box;   // originating box (provenance for the id/spacing)
        final double startX;
        final double endX;
        final double baseline;
        final boolean split;

        Piece(AbsorbedCell cell, String text, Run run, PgmBox box,
              double startX, double endX, boolean split) {
            this.cell = cell;
            this.text = text;
            this.run = run;
            this.box = box;
            this.startX = startX;
            this.endX = endX;
            this.baseline = box.getData() instanceof TextBoxData
                    ? ((TextBoxData) box.getData()).getBaselineY() : box.getRect().getY();
            this.split = split;
        }
    }

    /**
     * Splits one run's text across the cells of its row at the ruled column
     * boundaries. Returns a single whole-run Piece when the run stays inside one
     * cell; an empty list when the run's row has no cells (outside the grid).
     * Character x is estimated proportionally across the run box (uniform width) —
     * good enough to route each word to its column even in a proportional font.
     */
    private List<Piece> splitRunByCells(PgmBox box, Run run, List<AbsorbedCell> cells) {
        double x0 = box.getRect().getX();
        double x1 = x0 + box.getRect().getW();
        double cy = box.getRect().getY() + box.getRect().getH() / 2;
        List<AbsorbedCell> rowCells = new ArrayList<>();
        for (AbsorbedCell c : cells) {
            Rectangle r = c.getRectangle();
            if (r != null && cy >= r.getLLY() - 2 && cy <= r.getURY() + 2) {
                rowCells.add(c);
            }
        }
        rowCells.sort((a, b) -> Double.compare(a.getRectangle().getLLX(), b.getRectangle().getLLX()));
        String t = run.getText();
        List<Piece> pieces = new ArrayList<>();
        if (rowCells.isEmpty() || t.isEmpty()) {
            // No row cells: the run may still be a zero-width marker inside a cell.
            AbsorbedCell c = cellAt((x0 + x1) / 2, cy, cells);
            if (c != null) {
                pieces.add(new Piece(c, t, run, box, x0, x1, false));
            }
            return pieces;
        }
        AbsorbedCell cAll = cellByX((x0 + x1) / 2, rowCells);
        AbsorbedCell cLeft = cellByX(x0, rowCells);
        AbsorbedCell cRight = cellByX(x1, rowCells);
        if (cAll != null && cAll == cLeft && cAll == cRight) {
            pieces.add(new Piece(cAll, t, run, box, x0, x1, false));
            return pieces;
        }
        int n = t.length();
        double w = x1 - x0;
        if (n <= 1) {
            // A single glyph cannot be split — route it whole to the cell under
            // its centre (or nearest), so it does not vanish at a boundary.
            AbsorbedCell c = cellByX((x0 + x1) / 2, rowCells);
            if (c == null) {
                c = nearestCellByX((x0 + x1) / 2, rowCells);
            }
            if (c != null) {
                pieces.add(new Piece(c, t, run, box, x0, x1, true));
            }
            return pieces;
        }
        // Preferred path: cut at each ruled column boundary the run crosses. A
        // column gutter is a WIDE inter-word space, so snap each proportional cut
        // to the nearest INTERIOR space — that lands it in the gutter, not mid-word
        // (uniform char width drifts a few glyphs because the gutter eats x the
        // char model ignores). Each segment then joins the column its centre sits
        // in, which is robust even when a boundary snap is skipped.
        int li = cellIndexByX(x0, rowCells);
        int ri = cellIndexByX(x1, rowCells);
        if (li >= 0 && ri > li && w > 0) {
            List<Integer> cuts = new ArrayList<>();
            for (int ci = li; ci < ri; ci++) {
                double xb = rowCells.get(ci).getRectangle().getURX();
                int approx = (int) Math.round((xb - x0) / w * n);
                int cut = snapToSpace(t, approx);
                if (cut > 0 && (cuts.isEmpty() || cut > cuts.get(cuts.size() - 1))) {
                    cuts.add(cut);
                }
            }
            if (!cuts.isEmpty()) {
                int start = 0;
                for (int j = 0; j <= cuts.size(); j++) {
                    int end = j < cuts.size() ? cuts.get(j) : n;
                    if (end > start) {
                        double sx = x0 + w * start / n;
                        double ex = x0 + w * end / n;
                        double cx = (sx + ex) / 2;
                        AbsorbedCell c = cellByX(cx, rowCells);
                        if (c == null) {
                            c = nearestCellByX(cx, rowCells);
                        }
                        pieces.add(new Piece(c, t.substring(start, end), run, box, sx, ex, true));
                    }
                    start = end;
                }
                return pieces;
            }
        }
        // No interior space to snap to — a single word that merely overshoots a
        // ruled boundary (its box runs a little wide). Splitting it mid-glyph would
        // shed a stray letter into the next column, so keep the word whole under
        // the cell its centre sits in.
        AbsorbedCell c = cellByX((x0 + x1) / 2, rowCells);
        if (c == null) {
            c = nearestCellByX((x0 + x1) / 2, rowCells);
        }
        if (c != null) {
            pieces.add(new Piece(c, t, run, box, x0, x1, true));
        }
        return pieces;
    }

    /**
     * Snaps a character index to the nearest whitespace in {@code (0, n)} — the
     * word gap a column gutter falls in. Returns {@code approx} unchanged when no
     * interior space is within a small window (then the caller cuts mid-word).
     */
    /**
     * Nearest INTERIOR space to {@code approx}, returned as a cut index in
     * {@code (0, n)} (the char after the space, so the space trails the left
     * piece). Returns -1 when no interior space is within the search window — a
     * trailing/leading space is not a usable column cut.
     */
    private static int snapToSpace(String t, int approx) {
        int n = t.length();
        if (n < 2) {
            return -1;
        }
        approx = Math.max(1, Math.min(n - 1, approx));
        int window = Math.max(6, n / 2);
        for (int d = 0; d <= window; d++) {
            int right = approx + d;
            if (right < n && Character.isWhitespace(t.charAt(right)) && right + 1 < n) {
                return right + 1; // cut AFTER the space: it trails the left piece
            }
            int left = approx - d;
            if (left > 0 && left < n && Character.isWhitespace(t.charAt(left)) && left + 1 < n) {
                return left + 1;
            }
        }
        return -1;
    }

    /** Index into {@code rowCells} of the cell under x (by containment, else nearest). */
    private static int cellIndexByX(double x, List<AbsorbedCell> rowCells) {
        AbsorbedCell c = cellByX(x, rowCells);
        if (c == null) {
            c = nearestCellByX(x, rowCells);
        }
        return c == null ? -1 : rowCells.indexOf(c);
    }

    /** First row cell whose x-range contains x (1pt slack), else null. */
    private static AbsorbedCell cellByX(double x, List<AbsorbedCell> rowCells) {
        for (AbsorbedCell c : rowCells) {
            Rectangle r = c.getRectangle();
            if (r != null && x >= r.getLLX() - 1 && x <= r.getURX() + 1) {
                return c;
            }
        }
        return null;
    }

    /** Row cell whose x-range is nearest to x (used when x falls in a gutter). */
    private static AbsorbedCell nearestCellByX(double x, List<AbsorbedCell> rowCells) {
        AbsorbedCell best = null;
        double bestD = Double.MAX_VALUE;
        for (AbsorbedCell c : rowCells) {
            Rectangle r = c.getRectangle();
            if (r == null) {
                continue;
            }
            double d = x < r.getLLX() ? r.getLLX() - x : (x > r.getURX() ? x - r.getURX() : 0);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    /** Any cell containing (x,y) with slack, else null. */
    private static AbsorbedCell cellAt(double x, double y, List<AbsorbedCell> cells) {
        for (AbsorbedCell c : cells) {
            Rectangle r = c.getRectangle();
            if (r != null && x >= r.getLLX() - 1 && x <= r.getURX() + 1
                    && y >= r.getLLY() - 2 && y <= r.getURY() + 2) {
                return c;
            }
        }
        return null;
    }

    private static boolean endsWithWs(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1));
    }

    private static boolean startsWithWs(String s) {
        return !s.isEmpty() && Character.isWhitespace(s.charAt(0));
    }

    /** Total non-whitespace character count of a cell's block content (runs only). */
    private static long blockTextLength(List<SdmBlock> blocks) {
        long n = 0;
        for (SdmBlock b : blocks) {
            List<SdmInline> inline = null;
            if (b instanceof Paragraph) {
                inline = ((Paragraph) b).getInline();
            } else if (b instanceof Heading) {
                inline = ((Heading) b).getInline();
            }
            if (inline == null) {
                continue;
            }
            for (SdmInline in : inline) {
                if (in instanceof Run) {
                    String t = ((Run) in).getText();
                    if (t != null) {
                        for (int i = 0; i < t.length(); i++) {
                            if (!Character.isWhitespace(t.charAt(i))) {
                                n++;
                            }
                        }
                    }
                }
            }
        }
        return n;
    }

    // ------------------------------------------------------------ column tables

    /** Kill switch: {@code -Dsdm.columnTables=false} disables geometric column detection. */
    private static final boolean COLUMN_TABLES =
            !"false".equalsIgnoreCase(System.getProperty("sdm.columnTables", "true"));
    /** X-tolerance (pt) for treating two run left-edges as the same column. */
    private static final double COL_TOL = 8.0;

    /**
     * Recovers BORDERLESS tabular regions (key/value forms, multi-column
     * lists) that the ruled-grid {@link TableAbsorber} cannot see. The shallow
     * reader merges such a region into ONE multi-line Paragraph whose runs
     * concatenate across columns ("Registration number - Synonyms None. ...");
     * here a Paragraph whose runs line up into ≥2 whitespace-separated columns
     * over ≥2 rows becomes a real {@link Table}, so the HTML keeps the source's
     * two-column shape instead of a run-on line.
     *
     * <p>HONEST DEGRADATION: prose (one column per line) and ragged text never
     * qualify — a secondary column must be supported by at least half the rows,
     * which justified/wrapped body text cannot fake. The same Run objects are
     * reused (GUID-safe, no text added or lost).</p>
     */
    private int applyColumnTables(SdmDocument sdm) {
        if (!COLUMN_TABLES || pgmRef == null) {
            return 0;
        }
        List<SdmBlock> children = sdm.getChildren();
        List<SdmBlock> rebuilt = new ArrayList<>(children.size());
        int changes = 0;
        for (SdmBlock b : children) {
            Table t = tryColumnTable(b);
            if (t != null) {
                rebuilt.add(t);
                changes++;
            } else {
                rebuilt.add(b);
            }
        }
        if (changes > 0) {
            children.clear();
            children.addAll(rebuilt);
        }
        return changes;
    }

    /** Attempts to read a Paragraph as a whitespace-column grid; null if it is not one. */
    private Table tryColumnTable(SdmBlock b) {
        if (!(b instanceof Paragraph)) {
            return null;
        }
        Info info = infoOf(b);
        if (info.anyFixed) {
            return null;
        }
        Paragraph p = (Paragraph) b;
        if (p.getId() == null) {
            return null;
        }
        List<Run> runs = new ArrayList<>();
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run) {
                runs.add((Run) in);
            }
        }
        List<PgmBox> boxes = new ArrayList<>(pgmRef.byId(p.getId()));
        if (runs.size() < 4 || boxes.size() != runs.size()) {
            return null; // need enough content and a clean run↔box mapping
        }
        boxes.sort((x, y) -> Integer.compare(x.getPartIndex(), y.getPartIndex()));
        for (PgmBox box : boxes) {
            if (!(box.getData() instanceof TextBoxData)) {
                return null;
            }
        }

        // Group boxes into visual rows by baseline (top-to-bottom).
        List<List<Integer>> rows = groupRows(boxes);
        if (rows.size() < 2) {
            return null;
        }

        // Column starts: the left edge of the first box on each row, plus every
        // box that opens after a gutter (a gap wider than ~2 font-heights).
        List<Double> starts = new ArrayList<>();
        for (List<Integer> row : rows) {
            double prevRight = Double.NEGATIVE_INFINITY;
            double rowFont = 0;
            for (int idx : row) {
                rowFont = Math.max(rowFont, ((TextBoxData) boxes.get(idx).getData()).getFontSize());
            }
            double gutter = Math.max(9.0, 2.0 * rowFont);
            for (int idx : row) {
                double left = boxes.get(idx).getRect().getX();
                double right = left + boxes.get(idx).getRect().getW();
                if (prevRight == Double.NEGATIVE_INFINITY || left - prevRight > gutter) {
                    starts.add(left);
                }
                prevRight = Math.max(prevRight, right);
            }
        }

        // Cluster start-X into columns and keep those aligned across ≥ half the rows.
        double[] cols = clusterColumns(starts, rows.size());
        if (cols.length < 2) {
            return null;
        }
        // COLUMN-COUNT CAP: a genuine key/value or column table has a handful of
        // columns. Hundreds of "aligned" starts are a CHART — axis tick labels
        // scattered over a couple of baselines cluster into one pseudo-column
        // each (35654: 357 columns × 45 charts = 23k empty cells). Keep prose.
        if (cols.length > 12) {
            return null;
        }

        // DENSITY GUARD: a real key/value grid has most rows spanning ≥2 columns
        // (label + value). Scattered form text (a heading here, an address there)
        // fills one column per row and must NOT be tabled — that only reorders
        // prose. Require ≥60% of rows (min 2) to occupy at least two columns.
        int paired = 0;
        for (List<Integer> row : rows) {
            boolean[] occ = new boolean[cols.length];
            for (int idx : row) {
                occ[columnOf(boxes.get(idx).getRect().getX(), cols)] = true;
            }
            int filled = 0;
            for (boolean o : occ) {
                if (o) {
                    filled++;
                }
            }
            if (filled >= 2) {
                paired++;
            }
        }
        if (paired < Math.max(2, (int) Math.ceil(rows.size() * 0.6))) {
            return null;
        }

        // Assign each box to a column (greatest start ≤ box.left + tolerance).
        return buildColumnTable(p, boxes, rows, cols);
    }

    /** Column index a run at the given left-X belongs to (greatest start ≤ x + tol). */
    private static int columnOf(double left, double[] cols) {
        int col = 0;
        for (int c = 0; c < cols.length; c++) {
            if (left + COL_TOL >= cols[c]) {
                col = c;
            }
        }
        return col;
    }

    /** Greedily groups box indices into baseline rows (tolerance ≈ half the font height). */
    private List<List<Integer>> groupRows(List<PgmBox> boxes) {
        // Order by descending baseline (page top first), stable on partIndex.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            order.add(i);
        }
        order.sort((a, c) -> {
            double ba = ((TextBoxData) boxes.get(a).getData()).getBaselineY();
            double bc = ((TextBoxData) boxes.get(c).getData()).getBaselineY();
            int cmp = Double.compare(bc, ba);
            return cmp != 0 ? cmp : Integer.compare(boxes.get(a).getPartIndex(),
                    boxes.get(c).getPartIndex());
        });
        List<List<Integer>> rows = new ArrayList<>();
        List<Integer> cur = null;
        double curBase = Double.NaN;
        double curFont = 0;
        for (int idx : order) {
            TextBoxData d = (TextBoxData) boxes.get(idx).getData();
            double base = d.getBaselineY();
            double tol = Math.max(3.0, 0.5 * Math.max(curFont, d.getFontSize()));
            if (cur == null || Math.abs(base - curBase) > tol) {
                cur = new ArrayList<>();
                rows.add(cur);
                curBase = base;
                curFont = d.getFontSize();
            }
            cur.add(idx);
        }
        // Within each row keep left-to-right order.
        for (List<Integer> row : rows) {
            row.sort((a, c) -> Double.compare(boxes.get(a).getRect().getX(),
                    boxes.get(c).getRect().getX()));
        }
        return rows;
    }

    /**
     * Clusters candidate column-start X values (tolerance {@link #COL_TOL}) and
     * returns the sorted starts of columns supported by at least half the rows
     * (the leftmost column is always kept). Empty/degenerate → &lt;2 entries.
     */
    private double[] clusterColumns(List<Double> starts, int rowCount) {
        if (starts.isEmpty()) {
            return new double[0];
        }
        List<Double> sorted = new ArrayList<>(starts);
        java.util.Collections.sort(sorted);
        List<Double> centres = new ArrayList<>();
        List<Integer> support = new ArrayList<>();
        double sum = 0;
        int count = 0;
        double clusterStart = sorted.get(0);
        for (double s : sorted) {
            if (s - clusterStart > COL_TOL) {
                centres.add(sum / count);
                support.add(count);
                sum = 0;
                count = 0;
                clusterStart = s;
            }
            sum += s;
            count++;
        }
        centres.add(sum / count);
        support.add(count);

        int minSupport = Math.max(2, (int) Math.ceil(rowCount * 0.5));
        List<Double> kept = new ArrayList<>();
        for (int i = 0; i < centres.size(); i++) {
            // Always keep the leftmost column; keep the rest only when aligned
            // across enough rows (this is what rejects ragged prose).
            if (i == 0 || support.get(i) >= minSupport) {
                kept.add(centres.get(i));
            }
        }
        double[] out = new double[kept.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kept.get(i);
        }
        return out;
    }

    /** Materializes the detected grid: one column per start, one row per baseline group. */
    private Table buildColumnTable(Paragraph p, List<PgmBox> boxes,
                                   List<List<Integer>> rows, double[] cols) {
        Table table = new Table();
        double blockMaxX = 0;
        for (PgmBox box : boxes) {
            blockMaxX = Math.max(blockMaxX, box.getRect().getX() + box.getRect().getW());
        }
        for (int c = 0; c < cols.length; c++) {
            double width = (c + 1 < cols.length ? cols[c + 1] : blockMaxX) - cols[c];
            table.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS,
                    Math.max(1, width), ColumnSpec.Align.LEFT));
        }
        for (List<Integer> row : rows) {
            TableRow tr = new TableRow(TableRow.Kind.BODY);
            List<List<Integer>> cells = new ArrayList<>();
            for (int c = 0; c < cols.length; c++) {
                cells.add(new ArrayList<>());
            }
            for (int idx : row) {
                cells.get(columnOf(boxes.get(idx).getRect().getX(), cols)).add(idx);
            }
            for (List<Integer> cellBoxes : cells) {
                TableCell cell = new TableCell();
                if (!cellBoxes.isEmpty()) {
                    cell.getChildren().add(buildCellParagraph(p, boxes, cellBoxes));
                }
                tr.getCells().add(cell);
            }
            table.getRows().add(tr);
        }
        table.setId(p.getId());
        table.setSourceRef(p.getSourceRef());
        return table;
    }

    /**
     * Builds one cell's Paragraph from the given box indices (already in reading
     * order), reusing the original Run objects and inserting extractor-parity
     * spaces between them. Mirrors {@link #splitAcrossCells}'s sub-paragraph rule
     * so ids/sourceRefs stay deterministic.
     */
    private Paragraph buildCellParagraph(Paragraph p, List<PgmBox> boxes, List<Integer> idxs) {
        List<Run> runs = new ArrayList<>();
        for (SdmInline in : p.getInline()) {
            if (in instanceof Run) {
                runs.add((Run) in);
            }
        }
        Paragraph sub = new Paragraph();
        sub.setStyle(p.getStyle());
        int min = Integer.MAX_VALUE;
        int max = -1;
        int pageObjNum = -1;
        PgmBox prev = null;
        int prevIdx = -1;
        for (int idx : idxs) {
            PgmBox box = boxes.get(idx);
            if (prev != null && prev.getData() instanceof TextBoxData
                    && box.getData() instanceof TextBoxData) {
                TextBoxData pd = (TextBoxData) prev.getData();
                TextBoxData cd = (TextBoxData) box.getData();
                double acw = pageAcw.length > box.getPage() ? pageAcw[box.getPage()] : 5.0;
                if (SpacingRule.shouldSpace(runs.get(prevIdx).getText(),
                        prev.getRect().getX() + prev.getRect().getW(), pd.getBaselineY(),
                        runs.get(idx).getText(), box.getRect().getX(), cd.getBaselineY(), acw)) {
                    sub.getInline().add(new Run(" ", null));
                }
            }
            sub.getInline().add(runs.get(idx));
            if (box.getSourceRef() instanceof org.aspose.pdf.sdm.ContentRange) {
                org.aspose.pdf.sdm.ContentRange cr = (org.aspose.pdf.sdm.ContentRange) box.getSourceRef();
                pageObjNum = cr.getPageObjNum();
                min = Math.min(min, cr.getOpStart());
                max = Math.max(max, cr.getOpEnd());
            }
            prev = box;
            prevIdx = idx;
        }
        if (max >= 0 && pageObjNum >= 0) {
            org.aspose.pdf.sdm.ContentRange subRef =
                    new org.aspose.pdf.sdm.ContentRange(pageObjNum, min, max);
            sub.setSourceRef(subRef);
            if (nsDoc != null) {
                sub.setId(org.aspose.pdf.sdm.SdmIds.nodeId(nsDoc, subRef));
            }
        }
        return sub;
    }

    // ------------------------------------------------------------------ figures

    private int applyFigureCaptions(SdmDocument sdm, PgmModel pgm) {
        // ANCHORED text → image: box.anchorTargetId points at the image box id.
        Map<String, String> captionToFigure = new HashMap<>();
        for (org.aspose.pdf.pgm.PgmPage p : pgm.getPages()) {
            for (PgmBox b : p.getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT && b.getAnchorTargetId() != null) {
                    captionToFigure.put(b.getId(), b.getAnchorTargetId());
                }
            }
        }
        if (captionToFigure.isEmpty()) {
            return 0;
        }
        Map<String, Figure> figuresById = new HashMap<>();
        for (SdmBlock b : sdm.getChildren()) {
            if (b instanceof Figure && b.getId() != null) {
                figuresById.put(b.getId(), (Figure) b);
            }
        }
        int changes = 0;
        java.util.Iterator<SdmBlock> it = sdm.getChildren().iterator();
        while (it.hasNext()) {
            SdmBlock b = it.next();
            if (!(b instanceof Paragraph) || b.getId() == null) {
                continue;
            }
            String figureId = captionToFigure.get(b.getId());
            Figure figure = figureId == null ? null : figuresById.get(figureId);
            if (figure != null) {
                figure.getCaption().add(b);
                it.remove();
                changes++;
            }
        }
        return changes;
    }

    // ------------------------------------------------------------------ misc

    /** Concatenated Run text of a paragraph (diagnostics). */
    static String textOf(SdmBlock b) {
        if (!(b instanceof Paragraph)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SdmInline in : ((Paragraph) b).getInline()) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            }
        }
        return sb.toString();
    }
}
