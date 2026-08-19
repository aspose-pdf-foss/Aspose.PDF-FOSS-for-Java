package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.pgm.ColumnDetector;
import org.aspose.pdf.pgm.ColumnStructure;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.sdm.ObjectRef;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.sdm.writer.PdfSdmWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Flow compaction (IR Stage 2, PART 1): closes vertical holes on a page by
 * shifting subsequent FLOW content up, preserving natural inter-block spacing.
 * <ul>
 *   <li>gaps ≤ {@link CompactionOptions#getVerticalGapThreshold()} are natural
 *       spacing and stay; larger gaps are closed down to the page's own natural
 *       spacing (median of its below-threshold gaps);</li>
 *   <li>FIXED (chrome) and ATOMIC boxes never move; the topmost content band
 *       never moves either (content is pulled toward the block ABOVE the hole,
 *       so it cannot enter top chrome);</li>
 *   <li>ANCHORED boxes travel with their anchor target: annotation /Rect AND
 *       /QuadPoints are shifted by the same delta;</li>
 *   <li>multi-column pages are skipped with a reason (PART 2 merges CLEAN
 *       pages when {@link CompactionOptions#isMergeColumns()}).</li>
 * </ul>
 */
public final class FlowCompactor {

    private static final Logger LOG = Logger.getLogger(FlowCompactor.class.getName());

    /** Fallback natural spacing when a page has no below-threshold gaps, pt. */
    public static final double DEFAULT_NATURAL_SPACING = 14.0;

    private FlowCompactor() {
    }

    /** Plan of one page's compaction: per-box deltas plus bookkeeping. */
    public static final class PagePlan {
        private final Map<PgmBox, double[]> deltas = new LinkedHashMap<>();
        private int gapsClosed;
        private double naturalSpacing = DEFAULT_NATURAL_SPACING;
        private String skipReason;

        /**
         * Returns the box → {dx, dy} shift map (dy &gt; 0 moves up).
         *
         * @return the deltas
         */
        public Map<PgmBox, double[]> getDeltas() {
            return deltas;
        }

        /**
         * Returns how many oversized gaps this plan closes.
         *
         * @return the count
         */
        public int getGapsClosed() {
            return gapsClosed;
        }

        /**
         * Returns the natural spacing the holes are closed down to.
         *
         * @return the spacing in points
         */
        public double getNaturalSpacing() {
            return naturalSpacing;
        }

        /**
         * Returns why the page cannot be compacted, or null when it can.
         *
         * @return the skip reason, or null
         */
        public String getSkipReason() {
            return skipReason;
        }
    }

    /**
     * Computes the compaction plan of one page (pure geometry, no mutation).
     *
     * @param page         the PGM page (flowClass must be assigned)
     * @param gapThreshold the hole threshold in points
     * @return the plan (skipReason set when the page must be skipped)
     */
    public static PagePlan planPage(PgmPage page, double gapThreshold) {
        PagePlan plan = new PagePlan();
        ColumnStructure cs = page.getColumnStructure();
        if (cs == null) {
            cs = ColumnDetector.detect(page);
        }
        if (cs.getType() != ColumnStructure.Type.SINGLE_COLUMN) {
            plan.skipReason = "multi-column (" + cs + ")";
            return plan;
        }

        // Band the FLOW content by vertical overlap (top-down).
        List<PgmBox> flow = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getFlowClass() == FlowClass.FLOW
                    && (b.getKind() == PgmBoxKind.TEXT || b.getKind() == PgmBoxKind.IMAGE
                    || b.getKind() == PgmBoxKind.VECTOR)) {
                flow.add(b);
            }
        }
        if (flow.size() < 2) {
            return plan;
        }
        flow.sort((a, b) -> Double.compare(b.getRect().getTop(), a.getRect().getTop()));
        List<List<PgmBox>> bands = new ArrayList<>();
        double bandBottom = 0;
        for (PgmBox b : flow) {
            if (!bands.isEmpty() && b.getRect().getTop() >= bandBottom - 1) {
                bands.get(bands.size() - 1).add(b);
                bandBottom = Math.min(bandBottom, b.getRect().getY());
            } else {
                List<PgmBox> band = new ArrayList<>();
                band.add(b);
                bands.add(band);
                bandBottom = b.getRect().getY();
            }
        }

        // Gaps between consecutive bands; natural spacing = the page's own
        // median below-threshold gap.
        double[] gaps = new double[bands.size() - 1];
        List<Double> natural = new ArrayList<>();
        for (int i = 0; i + 1 < bands.size(); i++) {
            gaps[i] = bottomOf(bands.get(i)) - topOf(bands.get(i + 1));
            if (gaps[i] > 0 && gaps[i] <= gapThreshold) {
                natural.add(gaps[i]);
            }
        }
        if (!natural.isEmpty()) {
            natural.sort(Double::compare);
            plan.naturalSpacing = natural.get(natural.size() / 2);
        }

        // Bands that share a visual line with FIXED text are PINNED: shifting
        // them would tear the line apart (half moves, the page number stays) —
        // the dominant corpus corruption class. Everything below a pinned band
        // is pinned too (order past it must not change).
        List<double[]> fixedTextSpans = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getFlowClass() == FlowClass.FIXED && b.getKind() == PgmBoxKind.TEXT) {
                fixedTextSpans.add(new double[]{b.getRect().getY(), b.getRect().getTop()});
            }
        }
        int pinnedFrom = bands.size();
        for (int i = 0; i < bands.size(); i++) {
            double top = topOf(bands.get(i));
            double bottom = bottomOf(bands.get(i));
            for (double[] span : fixedTextSpans) {
                if (bottom <= span[1] - 1 && top >= span[0] + 1) {
                    pinnedFrom = i;
                    break;
                }
            }
            if (pinnedFrom == i) {
                break;
            }
        }

        // Cumulative upward shift for every band below each oversized gap.
        // Deltas are rounded to WHOLE points: fractional shifts change the
        // fractional part of every y and can push same-line fragments across
        // the text extractor's line-bucketing boundaries (measured on corpus:
        // 38578-2 footer reordered with a 51.2pt delta, stable with 51pt).
        double shift = 0;
        for (int i = 0; i + 1 < bands.size() && i + 1 < pinnedFrom; i++) {
            if (gaps[i] > gapThreshold) {
                shift += gaps[i] - plan.naturalSpacing;
                plan.gapsClosed++;
            }
            if (shift > 0) {
                double rounded = Math.rint(shift);
                for (PgmBox b : bands.get(i + 1)) {
                    plan.deltas.put(b, new double[]{0, rounded});
                }
            }
        }

        // ANCHORED boxes move with their target's delta.
        if (!plan.deltas.isEmpty()) {
            Map<String, double[]> deltaById = new LinkedHashMap<>();
            for (Map.Entry<PgmBox, double[]> e : plan.deltas.entrySet()) {
                deltaById.put(e.getKey().getId(), e.getValue());
            }
            for (PgmBox b : page.getBoxes()) {
                if (b.getFlowClass() == FlowClass.ANCHORED && b.getAnchorTargetId() != null) {
                    double[] d = deltaById.get(b.getAnchorTargetId());
                    if (d != null) {
                        plan.deltas.put(b, d);
                    }
                }
            }
        }

        // Order-preservation guard: no two text boxes may swap (or collide in)
        // vertical order because of differing deltas — that reorders extraction
        // and tears lines. A violating plan is dropped and the page skipped
        // (honest), never silently corrupted.
        if (!plan.deltas.isEmpty()) {
            List<PgmBox> textBoxes = new ArrayList<>();
            for (PgmBox b : page.getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT) {
                    textBoxes.add(b);
                }
            }
            for (int i = 0; i < textBoxes.size(); i++) {
                for (int j = i + 1; j < textBoxes.size(); j++) {
                    PgmBox a = textBoxes.get(i);
                    PgmBox b = textBoxes.get(j);
                    double da = deltaOf(plan, a);
                    double db = deltaOf(plan, b);
                    if (Math.abs(da - db) <= 0.01) {
                        continue; // moving together never reorders
                    }
                    double before = a.getRect().getTop() - b.getRect().getTop();
                    double after = before + da - db;
                    // The extractor groups lines with a tolerance of a few
                    // points — a pair closer than 6pt on either side of the
                    // shift can flip its line grouping (measured: 16222
                    // figure captions), so treat near-ties as violations too.
                    boolean sameLine = Math.abs(before) <= 6;
                    boolean flips = before * after < 0 || Math.abs(after) < 6;
                    if (sameLine || flips) {
                        plan.deltas.clear();
                        plan.gapsClosed = 0;
                        plan.skipReason = String.format(java.util.Locale.ROOT,
                                "order-flip: deltas %.1f vs %.1f would reorder boxes "
                                        + "(dy %.1f -> %.1f)", da, db, before, after);
                        return plan;
                    }
                }
            }
        }
        return plan;
    }

    private static double deltaOf(PagePlan plan, PgmBox b) {
        double[] d = plan.deltas.get(b);
        return d == null ? 0 : d[1];
    }

    /** FLOW text of a page in reading order (chrome/anchored excluded). */
    static String flowText(PgmPage page) {
        List<PgmBox> boxes = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getFlowClass() == FlowClass.FLOW
                    && b.getData() instanceof org.aspose.pdf.pgm.TextBoxData) {
                boxes.add(b);
            }
        }
        boxes.sort((a, b) -> {
            if (a.getReadingIndex() >= 0 && b.getReadingIndex() >= 0) {
                return Integer.compare(a.getReadingIndex(), b.getReadingIndex());
            }
            return Double.compare(b.getRect().getTop(), a.getRect().getTop());
        });
        StringBuilder sb = new StringBuilder();
        for (PgmBox b : boxes) {
            sb.append(((org.aspose.pdf.pgm.TextBoxData) b.getData()).getText()).append(' ');
        }
        return sb.toString();
    }

    /**
     * Compacts the document's pages IN PAGE (keepPageBreaks semantics): plans
     * each page, applies positional replay for stream content, shifts anchored
     * annotations' /Rect and /QuadPoints. Pages the translator cannot handle
     * are skipped with a reason and left untouched.
     *
     * @param doc     the open document (mutated)
     * @param model   the current SDM/PGM projection of {@code doc}
     * @param options the options (threshold, scope)
     * @return the accumulated result
     * @throws IOException if page content cannot be read or written
     */
    public static CompactionResult compactInPage(Document doc, PdfSdmReader.Result model,
                                                 CompactionOptions options) throws IOException {
        CompactionResult result = new CompactionResult();
        PgmModel pgm = model.getPgm();
        result.setPagesBefore(pgm.getPages().size());
        result.setPagesAfter(pgm.getPages().size());
        FlowClassifier.classify(pgm);
        PdfSdmWriter writer = new PdfSdmWriter();

        for (PgmPage page : pgm.getPages()) {
            if (!inScope(options, page.getIndex())) {
                continue;
            }
            PagePlan plan = planPage(page, options.getVerticalGapThreshold());
            if (plan.getSkipReason() != null) {
                result.getPagesSkipped().put(page.getIndex(), plan.getSkipReason());
                continue;
            }
            if (plan.getDeltas().isEmpty()) {
                continue;
            }
            List<org.aspose.pdf.Operator> beforeOps = new ArrayList<>(
                    doc.getPages().get(page.getIndex() + 1).getContents().getAll());
            try {
                writer.applyDeltas(doc, pgm, page.getIndex(), plan.getDeltas());
            } catch (PdfSdmWriter.UnsupportedPageOperation e) {
                result.getPagesSkipped().put(page.getIndex(), e.getMessage());
                continue;
            }
            // Invariant BY CONSTRUCTION: the page's flow text (chrome excluded,
            // reading order) must be unchanged by the shift. A violation rolls
            // the page back — an honest skip, never a silent reorder.
            String beforeFlow = flowText(page);
            org.aspose.pdf.sdm.reader.PdfSdmReader.Result verifyModel =
                    new org.aspose.pdf.sdm.reader.PdfSdmReader().read(doc, null);
            FlowClassifier.classify(verifyModel.getPgm());
            String afterFlow = flowText(verifyModel.getPgm().getPage(page.getIndex()));
            if (!beforeFlow.replaceAll("\\s+", " ").trim()
                    .equals(afterFlow.replaceAll("\\s+", " ").trim())) {
                doc.getPages().get(page.getIndex() + 1).setContents(
                        new org.aspose.pdf.OperatorCollection(beforeOps));
                result.getPagesSkipped().put(page.getIndex(),
                        "compact-verify-failed (flow text would change)");
                continue;
            }
            shiftAnnotations(doc, page.getIndex(), plan.getDeltas(), result);
            result.addGapsClosed(plan.getGapsClosed());
            result.addBlocksMoved(plan.getDeltas().size());
            LOG.fine(() -> "page " + page.getIndex() + ": closed " + plan.getGapsClosed()
                    + " gaps, moved " + plan.getDeltas().size() + " boxes");
        }
        return result;
    }

    /**
     * Applies deltas of ObjectRef (annotation) boxes without warning capture
     * (used by {@link ColumnMerger}).
     *
     * @param doc       the open document
     * @param pageIndex the 0-based page
     * @param deltas    the box deltas
     * @throws IOException if the annotations cannot be read
     */
    static void shiftAnnotationBoxes(Document doc, int pageIndex,
                                     Map<PgmBox, double[]> deltas) throws IOException {
        shiftAnnotations(doc, pageIndex, deltas, new CompactionResult());
    }

    /** Applies deltas of ObjectRef (annotation) boxes: /Rect and /QuadPoints. */
    private static void shiftAnnotations(Document doc, int pageIndex,
                                         Map<PgmBox, double[]> deltas,
                                         CompactionResult result) throws IOException {
        AnnotationCollection annots = doc.getPages().get(pageIndex + 1).getAnnotations();
        if (annots == null) {
            return;
        }
        for (Map.Entry<PgmBox, double[]> e : deltas.entrySet()) {
            PgmBox box = e.getKey();
            double[] d = e.getValue();
            if (!(box.getSourceRef() instanceof ObjectRef) || (d[0] == 0 && d[1] == 0)) {
                continue;
            }
            ObjectRef ref = (ObjectRef) box.getSourceRef();
            Annotation target = null;
            for (int i = 1; i <= annots.getCount(); i++) {
                Annotation a = annots.get(i);
                PdfDictionary dict = a != null ? a.getPdfDictionary() : null;
                PdfObjectKey key = dict != null ? dict.getObjectKey() : null;
                if (key != null && key.getObjectNumber() == ref.getObjNum()
                        && key.getGenerationNumber() == ref.getGen()) {
                    target = a;
                    break;
                }
            }
            if (target == null) {
                result.getWarnings().add("annotation " + ref.canonical()
                        + " not found on page " + pageIndex);
                continue;
            }
            Rectangle r = target.getRect();
            if (r != null) {
                target.setRect(new Rectangle(r.getLLX() + d[0], r.getLLY() + d[1],
                        r.getURX() + d[0], r.getURY() + d[1]));
            }
            PdfDictionary dict = target.getPdfDictionary();
            PdfBase qp = dict.get("QuadPoints");
            if (qp instanceof PdfArray) {
                PdfArray old = (PdfArray) qp;
                PdfArray shifted = new PdfArray(old.size());
                for (int k = 0; k < old.size(); k++) {
                    double v = num(old.get(k));
                    shifted.add(new PdfFloat(v + (k % 2 == 0 ? d[0] : d[1])));
                }
                dict.set(PdfName.of("QuadPoints"), shifted);
            }
        }
    }

    private static boolean inScope(CompactionOptions options, int pageIndex) {
        int[] pages = options.getPages();
        if (pages == null) {
            return true;
        }
        for (int p : pages) {
            if (p == pageIndex + 1) {
                return true;
            }
        }
        return false;
    }

    private static double topOf(List<PgmBox> band) {
        double top = -Double.MAX_VALUE;
        for (PgmBox b : band) {
            top = Math.max(top, b.getRect().getTop());
        }
        return top;
    }

    private static double bottomOf(List<PgmBox> band) {
        double bottom = Double.MAX_VALUE;
        for (PgmBox b : band) {
            bottom = Math.min(bottom, b.getRect().getY());
        }
        return bottom;
    }

    private static double num(PdfBase v) {
        if (v instanceof PdfInteger) {
            return ((PdfInteger) v).longValue();
        }
        if (v instanceof PdfFloat) {
            return ((PdfFloat) v).doubleValue();
        }
        return 0;
    }
}
