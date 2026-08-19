package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.Document;
import org.aspose.pdf.pgm.ColumnDetector;
import org.aspose.pdf.pgm.ColumnStructure;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.sdm.writer.PdfSdmWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * N→1 column merge on MULTI_CLEAN pages (IR Stage 2, PART 2) — the early
 * stress-test of the shifting machinery.
 * <ul>
 *   <li><b>STACK mode</b> (mechanism proof): column contents are stacked
 *       sequentially at their ORIGINAL line widths — column 1 stays, column k
 *       moves below the accumulated content, left edges aligned. Reading order
 *       becomes the full left column, then the next.</li>
 *   <li><b>REWRAP mode</b> (quality step): the merged line stream is re-packed
 *       to the full content width at FRAGMENT granularity — original glyph
 *       runs are never re-encoded (corpus fonts are subsets; synthesising new
 *       strings through them is not generally possible), fragments are
 *       repositioned greedily onto full-width lines. A fragment wider than the
 *       content width keeps its own line. Word-level re-breaking is a tracked
 *       non-goal.</li>
 * </ul>
 * MULTI_MIXED pages are skipped (Stage 9 scope). Stacked content that would
 * overflow the bottom limit is skipped with "stack-overflow" (cross-page flow
 * is PART 3's job).
 */
public final class ColumnMerger {

    private static final Logger LOG = Logger.getLogger(ColumnMerger.class.getName());

    /** Bottom margin content may not cross when stacking, pt. */
    public static final double BOTTOM_LIMIT = 36.0;
    /** Inter-fragment gap when packing lines in REWRAP mode, pt. */
    static final double PACK_GAP = 4.0;

    private ColumnMerger() {
    }

    /** Outcome of one page merge. */
    public static final class MergeResult {
        private final boolean merged;
        private final String skipReason;
        private final int boxesMoved;

        MergeResult(boolean merged, String skipReason, int boxesMoved) {
            this.merged = merged;
            this.skipReason = skipReason;
            this.boxesMoved = boxesMoved;
        }

        /**
         * Returns whether the page was merged.
         *
         * @return true when merged
         */
        public boolean isMerged() {
            return merged;
        }

        /**
         * Returns why the page was skipped, or null when merged.
         *
         * @return the reason, or null
         */
        public String getSkipReason() {
            return skipReason;
        }

        /**
         * Returns how many boxes were repositioned.
         *
         * @return the count
         */
        public int getBoxesMoved() {
            return boxesMoved;
        }
    }

    /**
     * Merges a MULTI_CLEAN page's columns into one flow.
     *
     * @param doc       the open document (mutated)
     * @param model     the projection of {@code doc}
     * @param pageIndex the 0-based page
     * @param rewrap    true for REWRAP mode, false for STACK mode
     * @return the outcome (never null; skip reasons are explicit)
     * @throws IOException if page content cannot be read or written
     */
    public static MergeResult mergePage(Document doc, PdfSdmReader.Result model,
                                        int pageIndex, boolean rewrap) throws IOException {
        PgmPage page = model.getPgm().getPage(pageIndex);
        ColumnStructure cs = page.getColumnStructure();
        if (cs == null) {
            cs = ColumnDetector.detect(page);
        }
        if (cs.getType() == ColumnStructure.Type.SINGLE_COLUMN) {
            return new MergeResult(false, "single-column", 0);
        }
        if (cs.getType() == ColumnStructure.Type.MULTI_MIXED) {
            return new MergeResult(false, "multi-mixed (Stage 9 scope)", 0);
        }
        double[][] bands = cs.getBands();
        if (bands == null || bands.length < 2) {
            return new MergeResult(false, "no band geometry", 0);
        }

        // Assign FLOW boxes to columns by center x.
        List<List<PgmBox>> columns = new ArrayList<>();
        for (int i = 0; i < bands.length; i++) {
            columns.add(new ArrayList<>());
        }
        for (PgmBox b : page.getBoxes()) {
            if (b.getFlowClass() != FlowClass.FLOW
                    || (b.getKind() != PgmBoxKind.TEXT && b.getKind() != PgmBoxKind.IMAGE
                    && b.getKind() != PgmBoxKind.VECTOR)) {
                continue;
            }
            double cx = b.getRect().getX() + b.getRect().getW() / 2;
            int col = -1;
            for (int i = 0; i < bands.length; i++) {
                if (cx >= bands[i][0] - 2 && cx <= bands[i][1] + 2) {
                    col = i;
                    break;
                }
            }
            if (col < 0) {
                if (b.getKind() == PgmBoxKind.TEXT && b.getRect().getW() > 40) {
                    return new MergeResult(false, "text box outside all column bands: "
                            + b.getRect(), 0);
                }
                if (b.getKind() == PgmBoxKind.TEXT) {
                    // Small gutter fragment (hyphen, a detached diacritic glyph
                    // at a column edge) — assign to the band whose INTERVAL is
                    // nearest. Center distance picked the wrong column for edge
                    // diacritics and detached umlaut dots from their letters.
                    double best = Double.MAX_VALUE;
                    for (int i = 0; i < bands.length; i++) {
                        double dist = Math.max(0,
                                Math.max(bands[i][0] - cx, cx - bands[i][1]));
                        if (dist < best) {
                            best = dist;
                            col = i;
                        }
                    }
                } else {
                    continue; // small decoration between columns — leave in place
                }
            }
            columns.get(col).add(b);
        }
        for (List<PgmBox> col : columns) {
            if (col.isEmpty()) {
                return new MergeResult(false, "empty column band", 0);
            }
            col.sort((a, b) -> Double.compare(b.getRect().getTop(), a.getRect().getTop()));
        }

        Map<PgmBox, double[]> deltas = rewrap
                ? planRewrap(columns, bands)
                : planStack(columns, bands);
        if (deltas == null) {
            return new MergeResult(false, "stack-overflow (needs cross-page flow)", 0);
        }

        // ANCHORED boxes follow their targets.
        Map<String, double[]> deltaById = new LinkedHashMap<>();
        for (Map.Entry<PgmBox, double[]> e : deltas.entrySet()) {
            deltaById.put(e.getKey().getId(), e.getValue());
        }
        for (PgmBox b : page.getBoxes()) {
            if (b.getFlowClass() == FlowClass.ANCHORED && b.getAnchorTargetId() != null) {
                double[] d = deltaById.get(b.getAnchorTargetId());
                if (d != null) {
                    deltas.put(b, d);
                }
            }
        }

        try {
            new PdfSdmWriter().applyDeltas(doc, model.getPgm(), pageIndex, deltas);
        } catch (PdfSdmWriter.UnsupportedPageOperation e) {
            return new MergeResult(false, e.getMessage(), 0);
        }
        FlowCompactor.shiftAnnotationBoxes(doc, pageIndex, deltas);
        LOG.fine(() -> "merged page " + pageIndex + " (" + (rewrap ? "rewrap" : "stack")
                + "): " + deltas.size() + " boxes");
        return new MergeResult(true, null, deltas.size());
    }

    /**
     * STACK mode: columns k&gt;0 move below the accumulated content. The first
     * line of column k continues the line rhythm: its TOP lands one leading
     * below the TOP of the accumulated content's last line.
     */
    private static Map<PgmBox, double[]> planStack(List<List<PgmBox>> columns,
                                                   double[][] bands) {
        Map<PgmBox, double[]> deltas = new LinkedHashMap<>();
        double spacing = columnLeading(columns.get(0));
        double lastLineTop = lastLineTopOf(columns.get(0));
        for (int k = 1; k < columns.size(); k++) {
            List<PgmBox> col = columns.get(k);
            double colTop = topOf(col);
            double dx = Math.rint(bands[0][0] - bands[k][0]);
            double dy = Math.rint((lastLineTop - spacing) - colTop);
            for (PgmBox b : col) {
                deltas.put(b, new double[]{dx, dy});
            }
            lastLineTop = lastLineTopOf(col) + dy;
            if (bottomOf(col) + dy < BOTTOM_LIMIT) {
                return null; // would overflow the page bottom
            }
        }
        return deltas;
    }

    /** TOP edge of the LAST (lowest) line of a column. */
    private static double lastLineTopOf(List<PgmBox> col) {
        double top = Double.MAX_VALUE;
        for (PgmBox b : col) {
            top = Math.min(top, b.getRect().getTop());
        }
        return top;
    }

    /**
     * REWRAP mode: fragments of the merged column stream are packed greedily
     * onto full-content-width lines (fragment granularity — glyph runs are
     * repositioned, never re-encoded).
     */
    private static Map<PgmBox, double[]> planRewrap(List<List<PgmBox>> columns,
                                                    double[][] bands) {
        double leftX = bands[0][0];
        double rightX = bands[bands.length - 1][1];
        double width = rightX - leftX;
        double leading = columnLeading(columns.get(0));
        double startTop = topOf(columns.get(0));

        // Merged reading order: column by column, its lines top-down, boxes
        // left-to-right within a line.
        List<List<PgmBox>> lines = new ArrayList<>();
        for (List<PgmBox> col : columns) {
            List<PgmBox> current = null;
            double bandBottom = 0;
            for (PgmBox b : col) {
                if (current != null && b.getRect().getTop() >= bandBottom - 1) {
                    current.add(b);
                    bandBottom = Math.min(bandBottom, b.getRect().getY());
                } else {
                    current = new ArrayList<>();
                    current.add(b);
                    lines.add(current);
                    bandBottom = b.getRect().getY();
                }
            }
        }
        for (List<PgmBox> line : lines) {
            line.sort((a, b) -> Double.compare(a.getRect().getX(), b.getRect().getX()));
        }

        Map<PgmBox, double[]> deltas = new LinkedHashMap<>();
        double baselineTop = startTop;
        double curX = leftX;
        boolean firstInLine = true;
        for (List<PgmBox> line : lines) {
            double lineWidth = 0;
            for (PgmBox b : line) {
                lineWidth += b.getRect().getW();
            }
            lineWidth += PACK_GAP * Math.max(0, line.size() - 1);
            if (!firstInLine && curX + lineWidth > leftX + width + 0.5) {
                baselineTop -= leading;
                curX = leftX;
                firstInLine = true;
            }
            for (PgmBox b : line) {
                double dx = Math.rint(curX - b.getRect().getX());
                double dy = Math.rint(baselineTop - b.getRect().getTop());
                deltas.put(b, new double[]{dx, dy});
                curX += b.getRect().getW() + PACK_GAP;
            }
            firstInLine = false;
            if (baselineTop - leading < BOTTOM_LIMIT
                    && lines.indexOf(line) < lines.size() - 1) {
                return null; // rewrap must not overflow the page either
            }
        }
        return deltas;
    }

    /** Median vertical pitch between consecutive lines of a column. */
    private static double columnLeading(List<PgmBox> col) {
        List<Double> tops = new ArrayList<>();
        for (PgmBox b : col) {
            tops.add(b.getRect().getTop());
        }
        tops.sort((a, b) -> Double.compare(b, a));
        List<Double> pitches = new ArrayList<>();
        for (int i = 0; i + 1 < tops.size(); i++) {
            double p = tops.get(i) - tops.get(i + 1);
            if (p > 1) {
                pitches.add(p);
            }
        }
        if (pitches.isEmpty()) {
            return 14;
        }
        pitches.sort(Double::compare);
        return pitches.get(pitches.size() / 2);
    }

    private static double topOf(List<PgmBox> col) {
        double top = -Double.MAX_VALUE;
        for (PgmBox b : col) {
            top = Math.max(top, b.getRect().getTop());
        }
        return top;
    }

    private static double bottomOf(List<PgmBox> col) {
        double bottom = Double.MAX_VALUE;
        for (PgmBox b : col) {
            bottom = Math.min(bottom, b.getRect().getY());
        }
        return bottom;
    }
}
