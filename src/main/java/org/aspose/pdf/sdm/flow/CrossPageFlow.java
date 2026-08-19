package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.pgm.ColumnDetector;
import org.aspose.pdf.pgm.ColumnStructure;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.sdm.writer.PdfSdmWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Cross-page flow (IR Stage 2, PART 3): after in-page compaction, content is
 * PULLED from the following page into the freed space — block-level only
 * (a block that does not fit stays; paragraph splitting is a tracked
 * non-goal). Emptied pages are removed with their chrome; chrome never
 * migrates between pages, and page-number text is NOT rewritten (documented
 * limitation). ANCHORED annotations travel with their blocks: the /Rect gets
 * the target page's coordinates and the annotation dictionary moves to the
 * target page's /Annots.
 * <p>
 * A moved block's operators are transplanted: each box's op range is expanded
 * to its self-contained envelope (the enclosing {@code q ... BT..ET ... Q} or
 * {@code BT..ET} for text, {@code q..Q} for images), the envelope's Tm /
 * BT-initial Td positioning is translated, and resources the ops reference
 * (Tf fonts, Do XObjects) are copied into the target page's resource
 * dictionary (renamed on conflict, operands rewritten). Envelopes referencing
 * resources the transplanter does not handle (gs, sh, cs/CS, scn/SCN, BDC)
 * stop the pull with an explicit reason.
 * </p>
 */
public final class CrossPageFlow {

    private static final Logger LOG = Logger.getLogger(CrossPageFlow.class.getName());

    /** Content may not land below this margin on the target page, pt. */
    public static final double BOTTOM_LIMIT = 36.0;

    private CrossPageFlow() {
    }

    /**
     * Runs cross-page flow over the whole document until fixpoint: pull blocks
     * forward, then remove drained pages.
     *
     * @param doc     the open document (mutated)
     * @param options the compaction options
     * @param result  the result accumulator (blocksMoved, pagesAfter, warnings)
     * @param nsBytes the file bytes for id minting (may be null)
     * @throws IOException if page content cannot be read or written
     */
    public static void flowAcrossPages(Document doc, CompactionOptions options,
                                       CompactionResult result, byte[] nsBytes)
            throws IOException {
        Set<Integer> drained = new HashSet<>();
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 500) {
            changed = false;
            PdfSdmReader.Result model = new PdfSdmReader().read(doc, nsBytes);
            FlowClassifier.classify(model.getPgm());
            int pages = model.getPgm().getPages().size();
            for (int t = 0; t + 1 < pages; t++) {
                String outcome = pullOnce(doc, model, t, t + 1, options, result);
                if ("moved".equals(outcome)) {
                    changed = true;
                    break; // model is stale — re-project and continue
                }
                if ("drained".equals(outcome)) {
                    drained.add(pageKey(doc, t + 1));
                    changed = true;
                    break;
                }
                if (outcome != null) {
                    result.getWarnings().add("pull p" + (t + 1) + "->p" + t + ": " + outcome);
                }
            }
        }
        removeDrainedPages(doc, drained, result, nsBytes);
    }

    /** Identity of a page across index shifts: its dictionary object hash. */
    private static int pageKey(Document doc, int pageIndex) {
        try {
            return System.identityHashCode(doc.getPages().get(pageIndex + 1).getPdfDictionary());
        } catch (Exception e) {
            return -1;
        }
    }

    private static void removeDrainedPages(Document doc, Set<Integer> drained,
                                           CompactionResult result, byte[] nsBytes)
            throws IOException {
        if (drained.isEmpty()) {
            return;
        }
        // Verify drained pages truly hold no flow content anymore, then delete
        // (their chrome goes with them; other pages' chrome is untouched).
        PdfSdmReader.Result model = new PdfSdmReader().read(doc, nsBytes);
        FlowClassifier.classify(model.getPgm());
        List<Integer> toRemove = new ArrayList<>();
        for (PgmPage page : model.getPgm().getPages()) {
            if (!drained.contains(pageKey(doc, page.getIndex()))) {
                continue;
            }
            boolean hasContent = false;
            for (PgmBox b : page.getBoxes()) {
                if (b.getFlowClass() == FlowClass.FLOW || b.getFlowClass() == FlowClass.ATOMIC
                        || b.getKind() == PgmBoxKind.ANNOTATION
                        || b.getKind() == PgmBoxKind.FIELD) {
                    hasContent = true;
                    break;
                }
            }
            if (!hasContent) {
                toRemove.add(page.getIndex());
            }
        }
        for (int i = toRemove.size() - 1; i >= 0; i--) {
            doc.getPages().delete(toRemove.get(i) + 1);
        }
        result.setPagesAfter(doc.getPages().getCount());
    }

    /**
     * Pulls as many leading blocks of page {@code s} into page {@code t} as
     * fit. Returns "moved" when something moved, "drained" when the source
     * lost its last flow block, null when nothing fits, or a reason string.
     */
    private static String pullOnce(Document doc, PdfSdmReader.Result model, int t, int s,
                                   CompactionOptions options, CompactionResult result)
            throws IOException {
        boolean debug = Boolean.getBoolean("ir.flow.debug");
        PgmPage target = model.getPgm().getPage(t);
        PgmPage source = model.getPgm().getPage(s);
        if (!singleColumn(target) || !singleColumn(source)) {
            if (debug) {
                System.out.println("[flow-debug] p" + s + "->p" + t + ": multi-column");
            }
            return null; // multi-column pages neither give nor take blocks
        }

        // Free space on the target page.
        double targetBottomLimit = bottomLimit(target, options);
        double contentBottom = Double.MAX_VALUE;
        boolean targetHasContent = false;
        for (PgmBox b : target.getBoxes()) {
            if (b.getFlowClass() == FlowClass.FLOW || b.getFlowClass() == FlowClass.ATOMIC) {
                contentBottom = Math.min(contentBottom, b.getRect().getY());
                targetHasContent = true;
            }
        }
        if (!targetHasContent) {
            contentBottom = target.getHeight() - 72;
        }

        // Leading BLOCK of the source page: line bands grouped by natural
        // spacing — a paragraph moves as a whole, never line by line (that
        // would silently split paragraphs across pages).
        List<List<PgmBox>> lineBands = flowBands(source);
        List<List<PgmBox>> bands = blocksOf(lineBands);
        if (debug) {
            System.out.println("[flow-debug] p" + s + "->p" + t + ": blocks="
                    + bands.size() + " lines=" + lineBands.size()
                    + " targetContentBottom=" + contentBottom
                    + " bottomLimit=" + targetBottomLimit);
        }
        if (bands.isEmpty()) {
            return null;
        }
        double spacing = FlowCompactor.DEFAULT_NATURAL_SPACING;
        double targetTop = contentBottom - spacing;

        // Default unit is the leading BLOCK (a whole paragraph, never split).
        List<PgmBox> band = bands.get(0);
        boolean partial = false;
        if (targetTop - (topOfBand(band) - bottomOfBand(band)) < targetBottomLimit) {
            // The whole leading block does not fit the target's free space.
            if (!options.isSplitParagraphs()) {
                if (debug) {
                    System.out.println("[flow-debug] block h="
                            + (topOfBand(band) - bottomOfBand(band))
                            + " does not fit: targetTop=" + targetTop);
                }
                return null; // block stays whole (paragraph splitting is off)
            }
            // Fallback (opt-in): pull the maximal PREFIX of leading LINES that
            // fits, splitting the paragraph across the page boundary. Lines are
            // atomic (never split horizontally); the prefix is < the leading
            // block (which itself did not fit), so it can only shrink it.
            band = leadingLinesThatFit(lineBands, targetTop, targetBottomLimit);
            if (band == null) {
                if (debug) {
                    System.out.println("[flow-debug] not even one leading line fits:"
                            + " targetTop=" + targetTop);
                }
                return null; // not even a single line fits — nothing to pull
            }
            partial = true;
            if (debug) {
                System.out.println("[flow-debug] paragraph split: pulling "
                        + band.size() + " leading-line boxes (h="
                        + (topOfBand(band) - bottomOfBand(band)) + ")");
            }
        }
        for (PgmBox b : band) {
            if (b.getKind() != PgmBoxKind.TEXT && b.getKind() != PgmBoxKind.IMAGE) {
                return "block contains " + b.getKind() + " (v1 moves TEXT/IMAGE blocks)";
            }
        }
        double bandTop = topOfBand(band);
        double dy = Math.rint(targetTop - bandTop);
        if (debug && partial) {
            System.out.println("[flow-debug]   bandTop=" + bandTop + " targetTop=" + targetTop
                    + " dy=" + dy + " predictedBottom="
                    + (bottomOfBand(band) + dy) + " bottomLimit=" + targetBottomLimit);
        }
        // A partial (line-level) pull always leaves a remainder on the source, so
        // it can never DRAIN the page and its remainder must be lifted — force a
        // block count > 1 so commitPull reports "moved" and runs the leading lift.
        int effectiveBlocks = partial ? Math.max(2, bands.size()) : bands.size();

        // Envelopes of the band's boxes in the source stream.
        Page sourcePage = doc.getPages().get(s + 1);
        Page targetPage = doc.getPages().get(t + 1);
        OperatorCollection sourceOps = sourcePage.getContents();

        // PATH 1: raw self-contained envelope transplant (the proven path — used
        // whenever every band box sits in its own q..BT..ET..Q / q..Q). It is
        // SKIPPED for a partial (line-level) pull: after a prior leading lift the
        // remainder boxes are wrapped as q..cm(0,dy)..BT..Tm..ET..Q envelopes, and
        // translateEnvelope would shift BOTH the leading cm and the Tm — a double
        // shift that drifts the lines down into the footer. The splitter (PATH 2)
        // re-materialises each box at an absolute Tm and composes the ambient CTM
        // once, so partial pulls always route through it.
        EnvelopeMove env = partial
                ? EnvelopeMove.skip("partial pull → splitter")
                : buildEnvelopeMove(source, band, sourceOps, dy, sourcePage, targetPage);
        if (env.reason == null) {
            List<Operator> sourceOut = new ArrayList<>();
            for (int i = 0; i < sourceOps.size(); i++) {
                if (!env.owned.contains(i)) {
                    sourceOut.add(sourceOps.getAt(i));
                }
            }
            String oc = commitPull(doc, model, s, t, band, effectiveBlocks, dy, bandTop,
                    sourcePage, targetPage, sourceOut, env.moved, result, true);
            if (debug) {
                System.out.println("[flow-debug] p" + s + "->p" + t
                        + ": envelope path outcome=" + oc + " (blocks=" + bands.size()
                        + " partial=" + partial + ")");
            }
            return oc;
        }

        // PATH 2: the band shares a monolithic BT..ET with boxes that stay (the
        // "entangled" case) — split the run into self-contained IR fragments.
        // Kill switch: -Dir.flow.splitRuns=false restores the honest skip.
        if (!"false".equals(System.getProperty("ir.flow.splitRuns"))) {
            try {
                TextRunSplitter.SplitPlan plan = TextRunSplitter.plan(sourcePage, targetPage,
                        new ArrayList<>(sourceOps.getAll()), source.getBoxes(),
                        new HashSet<>(band), dy);
                if (plan != null) {
                    if (debug) {
                        System.out.println("[flow-debug] p" + s + "->p" + t
                                + ": run-splitter engaged (" + band.size() + " boxes)");
                    }
                    String oc = commitPull(doc, model, s, t, band, effectiveBlocks, dy, bandTop,
                            sourcePage, targetPage, plan.getNewSourceOps(),
                            plan.getTargetAppendOps(), result, false);
                    if (debug) {
                        System.out.println("[flow-debug] p" + s + "->p" + t
                                + ": splitter path outcome=" + oc + " (bands=" + bands.size() + ")");
                    }
                    return oc;
                }
            } catch (RuntimeException ex) {
                // The splitter must never break a pull; fall through to the skip.
                LOG.fine(() -> "run-splitter threw, falling back: " + ex);
            }
        }
        return env.reason;
    }

    /** Result of trying the raw-envelope transplant: reason==null means success. */
    private static final class EnvelopeMove {
        final String reason;
        final Set<Integer> owned;
        final List<Operator> moved;

        EnvelopeMove(String reason, Set<Integer> owned, List<Operator> moved) {
            this.reason = reason;
            this.owned = owned;
            this.moved = moved;
        }

        static EnvelopeMove skip(String reason) {
            return new EnvelopeMove(reason, null, null);
        }
    }

    /**
     * Builds the raw-envelope move (owned op indices + translated, resource-
     * transplanted moved ops), or an {@link EnvelopeMove} carrying a skip reason
     * when the band is not made of self-contained, non-entangled envelopes.
     */
    private static EnvelopeMove buildEnvelopeMove(PgmPage source, List<PgmBox> band,
                                                  OperatorCollection sourceOps, double dy,
                                                  Page sourcePage, Page targetPage)
            throws IOException {
        List<int[]> envelopes = new ArrayList<>();
        Set<Integer> owned = new HashSet<>();
        for (PgmBox b : band) {
            if (!(b.getSourceRef() instanceof ContentRange)) {
                return EnvelopeMove.skip("band box without ContentRange");
            }
            ContentRange cr = (ContentRange) b.getSourceRef();
            int[] envRange = envelope(sourceOps, cr.getOpStart(), cr.getOpEnd());
            if (envRange == null) {
                return EnvelopeMove.skip("box ops " + cr.getOpStart() + ".." + cr.getOpEnd()
                        + " have no self-contained envelope");
            }
            envelopes.add(envRange);
            for (int i = envRange[0]; i <= envRange[1]; i++) {
                owned.add(i);
            }
        }
        for (PgmBox b : source.getBoxes()) {
            if (band.contains(b) || !(b.getSourceRef() instanceof ContentRange)) {
                continue;
            }
            ContentRange cr = (ContentRange) b.getSourceRef();
            for (int i = cr.getOpStart(); i <= cr.getOpEnd(); i++) {
                if (owned.contains(i)) {
                    return EnvelopeMove.skip("envelope entangled with box outside the block");
                }
            }
        }
        List<Operator> moved = new ArrayList<>();
        envelopes.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] envRange : envelopes) {
            List<Operator> chunk = new ArrayList<>();
            for (int i = envRange[0]; i <= envRange[1]; i++) {
                chunk.add(sourceOps.getAt(i));
            }
            String tErr = translateEnvelope(chunk, dy);
            if (tErr != null) {
                return EnvelopeMove.skip(tErr);
            }
            moved.addAll(chunk);
        }
        String rErr = transplantResources(moved, sourcePage, targetPage);
        if (rErr != null) {
            return EnvelopeMove.skip(rErr);
        }
        return new EnvelopeMove(null, owned, moved);
    }

    /**
     * Applies a pull (new source stream + ops appended to the target), then
     * enforces the by-construction invariants and rolls back on any violation:
     * (1) the concatenated FLOW text of both pages is unchanged (no reorder /
     * loss), and (2) the KEPT source boxes do not move (no reflow). On success
     * the band's anchored annotations travel to the target.
     *
     * @return "moved", "drained", or a skip reason (already rolled back)
     */
    private static String commitPull(Document doc, PdfSdmReader.Result model, int s, int t,
                                     List<PgmBox> band, int bandCount, double dy, double bandTop,
                                     Page sourcePage, Page targetPage, List<Operator> sourceOut,
                                     List<Operator> movedAppend, CompactionResult result,
                                     boolean strictOrder)
            throws IOException {
        List<Operator> sourceBefore = new ArrayList<>(sourcePage.getContents().getAll());
        List<Operator> targetBefore = new ArrayList<>(targetPage.getContents().getAll());
        List<Operator> targetOut = new ArrayList<>(targetBefore);
        targetOut.addAll(movedAppend);
        sourcePage.setContents(new OperatorCollection(sourceOut));
        targetPage.setContents(new OperatorCollection(targetOut));

        PdfSdmReader.Result verifyModel = new PdfSdmReader().read(doc, null);
        FlowClassifier.classify(verifyModel.getPgm());

        boolean debug = Boolean.getBoolean("ir.flow.debug");
        boolean ok;
        if (strictOrder) {
            // Envelope path: FLOW text of the two pages (chrome excluded, reading
            // order) is exactly the concatenation it was before the pull.
            String beforeFlow = flowText(model.getPgm().getPage(t))
                    + flowText(model.getPgm().getPage(s));
            String afterFlow = flowText(verifyModel.getPgm().getPage(t))
                    + flowText(verifyModel.getPgm().getPage(s));
            ok = normalizeText(beforeFlow).equals(normalizeText(afterFlow));
        } else {
            // Split path: the moved fragments carry absolute positions (no reorder
            // by construction), but text piled onto a target that already holds an
            // image can be re-CLASSIFIED FLOW->ANCHORED. So verify content, not
            // reading class: the multiset of ALL text words across the two pages is
            // conserved (FIXED chrome is identical on both sides and cancels).
            List<String> before = allTextWords(model.getPgm().getPage(t), model.getPgm().getPage(s));
            List<String> after = allTextWords(verifyModel.getPgm().getPage(t),
                    verifyModel.getPgm().getPage(s));
            ok = before.equals(after);
        }
        if (debug && !ok) {
            System.out.println("[flow-debug] pull p" + s + "->p" + t + " FAIL: text not conserved");
        }

        // Shared invariant: the KEPT source boxes must not REFLOW — every kept
        // box's rounded top survives the pull. This is a SUBSET (not equality)
        // test: rebuilding the source stream can re-segment identical text into a
        // slightly different box grouping (a line splits into two boxes at the
        // same y), adding tops without moving any real content. Word conservation
        // above already rules out loss/duplication, so extra tops are harmless;
        // requiring every ORIGINAL kept top to remain still catches a genuine
        // reflow (a kept box whose top shifts drops its old value from `after`).
        if (ok) {
            Set<Long> movedTops = new HashSet<>();
            for (PgmBox b : band) {
                movedTops.add(Math.round(b.getRect().getTop()));
            }
            List<Long> beforeKept = keptFlowTops(model.getPgm().getPage(s), movedTops);
            List<Long> afterKept = keptFlowTops(verifyModel.getPgm().getPage(s), null);
            ok = afterKept.containsAll(beforeKept);
            if (debug && !ok) {
                System.out.println("[flow-debug] pull p" + s + "->p" + t
                        + " FAIL: kept source boxes reflowed (before=" + beforeKept
                        + " after=" + afterKept + ")");
            }
        }

        // Shared invariant: the pull must not collide the moved content with the
        // target's existing content or chrome — a faithful pull lands the band in
        // free space, so any NEW overlapping text pair on the target is a fit
        // miscalc (e.g. a split prefix packed one line into the footer band).
        if (ok && !"false".equals(System.getProperty("ir.flow.targetOverlapGuard"))) {
            int addedTargetOverlaps = textOverlapCount(verifyModel.getPgm().getPage(t))
                    - textOverlapCount(model.getPgm().getPage(t));
            if (Boolean.getBoolean("ir.flow.debug") && addedTargetOverlaps > 0) {
                System.out.println("[flow-debug] pull p" + s + "->p" + t
                        + " adds " + addedTargetOverlaps + " target overlaps");
            }
            ok = addedTargetOverlaps <= 0;
        }

        if (!ok) {
            sourcePage.setContents(new OperatorCollection(sourceBefore));
            targetPage.setContents(new OperatorCollection(targetBefore));
            return "pull-verify-failed p" + s + "->p" + t;
        }

        moveAnchoredAnnotations(doc, s, t, band, dy, model);

        // Close the LEADING gap the pull opened on the source: its remaining FLOW
        // content is lifted up to the top slot the moved band vacated (bandTop).
        // Without this the remainder stays stranded at the page bottom with a big
        // empty top — and, because free space is measured below the lowest
        // content, the cascade stalls (the next page can never flow into it).
        // In-page compaction deliberately never moves the topmost band (chrome
        // safety), so this leading lift is done here, verified and rolled back on
        // any flow-text change. Kill switch: -Dir.flow.liftRemainder=false.
        if (bandCount > 1) {
            liftSourceRemainder(doc, s, bandTop);
        }

        result.addBlocksMoved(1);
        LOG.fine(() -> "pulled block of " + band.size() + " boxes p" + s + "->p" + t
                + " dy=" + dy);
        return bandCount == 1 ? "drained" : "moved";
    }

    /**
     * Lifts the source page's remaining FLOW content (and its anchored
     * annotations) up by a uniform delta so its topmost band's top aligns with
     * {@code ceilingTop} — the top the just-moved band vacated. A uniform shift
     * preserves reading order by construction; the flow text is re-verified and
     * the page rolled back on any change (honest no-op, never a corruption).
     */
    private static void liftSourceRemainder(Document doc, int s, double ceilingTop)
            throws IOException {
        if ("false".equals(System.getProperty("ir.flow.liftRemainder"))) {
            return;
        }
        PdfSdmReader.Result post = new PdfSdmReader().read(doc, null);
        FlowClassifier.classify(post.getPgm());
        PgmPage src = post.getPgm().getPage(s);

        double topmost = -Double.MAX_VALUE;
        List<PgmBox> flow = new ArrayList<>();
        for (PgmBox b : src.getBoxes()) {
            if (b.getFlowClass() == FlowClass.FLOW
                    && (b.getKind() == PgmBoxKind.TEXT || b.getKind() == PgmBoxKind.IMAGE
                    || b.getKind() == PgmBoxKind.VECTOR)) {
                flow.add(b);
                topmost = Math.max(topmost, b.getRect().getTop());
            }
        }
        if (flow.isEmpty()) {
            return; // nothing left to lift (drained)
        }
        double dy = Math.rint(ceilingTop - topmost);
        boolean debug = Boolean.getBoolean("ir.flow.debug");
        if (debug) {
            System.out.println("[flow-debug] lift p" + s + ": flow=" + flow.size()
                    + " topmost=" + topmost + " ceiling=" + ceilingTop + " dy=" + dy);
        }
        if (dy <= 1) {
            return; // no meaningful leading gap
        }

        Map<PgmBox, double[]> deltas = new LinkedHashMap<>();
        Map<String, double[]> byId = new LinkedHashMap<>();
        for (PgmBox b : flow) {
            double[] d = new double[]{0, dy};
            deltas.put(b, d);
            byId.put(b.getId(), d);
        }
        for (PgmBox b : src.getBoxes()) {
            if (b.getFlowClass() == FlowClass.ANCHORED && b.getAnchorTargetId() != null) {
                double[] d = byId.get(b.getAnchorTargetId());
                if (d != null) {
                    deltas.put(b, d);
                }
            }
        }

        Page srcPage = doc.getPages().get(s + 1);
        List<Operator> before = new ArrayList<>(srcPage.getContents().getAll());
        List<String> beforeWords = new ArrayList<>();
        collectWords(src, beforeWords);
        beforeWords.sort(null);
        int beforeOverlaps = textOverlapCount(src);

        // PATH A: simple Tm adjustment — works when every remaining flow box has
        // its own governing positioning (e.g. the source was already rebuilt by
        // the splitter on the pull that just happened).
        boolean applied = false;
        try {
            new PdfSdmWriter().applyDeltas(doc, post.getPgm(), s, deltas);
            applied = true;
        } catch (RuntimeException e) {
            srcPage.setContents(new OperatorCollection(before));
            if (debug) {
                System.out.println("[flow-debug] lift p" + s + " applyDeltas failed: " + e
                        + " -> trying splitter");
            }
        }

        // PATH B: the remainder is still inside a monolithic BT..ET the simple
        // path cannot shift — re-materialise every remaining flow box at its
        // absolute position lifted by dy (target == source), the same way the
        // pull splits an entangled run.
        if (!applied) {
            try {
                Set<PgmBox> lift = new HashSet<>();
                for (PgmBox b : flow) {
                    if (b.getKind() == PgmBoxKind.TEXT || b.getKind() == PgmBoxKind.IMAGE) {
                        lift.add(b);
                    }
                }
                TextRunSplitter.SplitPlan plan = TextRunSplitter.plan(srcPage, srcPage,
                        new ArrayList<>(before), src.getBoxes(), lift, dy);
                if (plan == null) {
                    if (debug) {
                        System.out.println("[flow-debug] lift p" + s + " splitter returned null");
                    }
                    return;
                }
                List<Operator> combined = new ArrayList<>(plan.getNewSourceOps());
                combined.addAll(plan.getTargetAppendOps());
                srcPage.setContents(new OperatorCollection(combined));
            } catch (RuntimeException e) {
                srcPage.setContents(new OperatorCollection(before));
                if (debug) {
                    System.out.println("[flow-debug] lift p" + s + " splitter threw: " + e);
                }
                LOG.fine(() -> "lift-remainder splitter threw p" + s + ": " + e);
                return;
            }
        }

        // Verify: the source's flow text (reading order) is unchanged — a uniform
        // shift can never reorder it, so any mismatch is a modelling gap; roll back.
        PdfSdmReader.Result verify = new PdfSdmReader().read(doc, null);
        FlowClassifier.classify(verify.getPgm());
        List<String> afterWords = new ArrayList<>();
        collectWords(verify.getPgm().getPage(s), afterWords);
        afterWords.sort(null);
        // A uniform vertical lift conserves the page's text and cannot reorder the
        // reading order (the lifted body stays between the static header/footer
        // chrome). Verify the word MULTISET of the WHOLE page is unchanged — robust
        // to box re-segmentation and to FLOW<->FIXED re-classification, both of
        // which a geometry- or flow-ordered compare would spuriously flag. Any
        // real content loss/duplication from a modelling gap still rolls back.
        if (!beforeWords.equals(afterWords)) {
            srcPage.setContents(new OperatorCollection(before));
            if (debug) {
                System.out.println("[flow-debug] lift p" + s + " rolled back (flow text changed)");
            }
            LOG.fine(() -> "lift-remainder rolled back p" + s + " (flow text changed)");
            return;
        }

        // Overlap guard: a faithful uniform lift never makes two text boxes
        // collide; if it does (a re-materialisation gap collapsed line spacing),
        // roll back rather than leave overlapping glyphs on the page.
        int addedOverlaps = textOverlapCount(verify.getPgm().getPage(s)) - beforeOverlaps;
        if (addedOverlaps > 0) {
            srcPage.setContents(new OperatorCollection(before));
            if (debug) {
                System.out.println("[flow-debug] lift p" + s + " rolled back (+"
                        + addedOverlaps + " overlaps)");
            }
            LOG.fine(() -> "lift-remainder rolled back p" + s + " (overlaps)");
            return;
        }
        if (debug) {
            System.out.println("[flow-debug] lift p" + s + " applied dy=" + dy
                    + " via " + (applied ? "deltas" : "splitter"));
        }
        FlowCompactor.shiftAnnotationBoxes(doc, s, deltas);
    }

    /** Count of text-box pairs that overlap (area &gt; 1pt²) on a page. */
    private static int textOverlapCount(PgmPage page) {
        List<PgmBox> boxes = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT
                    && b.getData() instanceof org.aspose.pdf.pgm.TextBoxData) {
                boxes.add(b);
            }
        }
        boxes.sort((a, b) -> Double.compare(b.getRect().getTop(), a.getRect().getTop()));
        int count = 0;
        for (int i = 0; i < boxes.size(); i++) {
            org.aspose.pdf.pgm.PgmRect a = boxes.get(i).getRect();
            for (int j = i + 1; j < boxes.size(); j++) {
                org.aspose.pdf.pgm.PgmRect b = boxes.get(j).getRect();
                if (b.getTop() < a.getY() - 0.5) {
                    break;
                }
                double ix = Math.min(a.getRight(), b.getRight()) - Math.max(a.getX(), b.getX());
                double iy = Math.min(a.getTop(), b.getTop()) - Math.max(a.getY(), b.getY());
                if (ix > 1.0 && iy > 1.0) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Sorted words of every TEXT box on the two pages (content-preservation set). */
    private static List<String> allTextWords(PgmPage a, PgmPage b) {
        List<String> words = new ArrayList<>();
        collectWords(a, words);
        collectWords(b, words);
        words.sort(null);
        return words;
    }

    private static void collectWords(PgmPage page, List<String> out) {
        for (PgmBox box : page.getBoxes()) {
            if (box.getKind() == PgmBoxKind.TEXT
                    && box.getData() instanceof org.aspose.pdf.pgm.TextBoxData) {
                String text = ((org.aspose.pdf.pgm.TextBoxData) box.getData()).getText();
                for (String w : normalizeText(text).split(" ")) {
                    if (!w.isEmpty()) {
                        out.add(w);
                    }
                }
            }
        }
    }

    /** Sorted rounded tops of FLOW text boxes, excluding any top in {@code exclude}. */
    private static List<Long> keptFlowTops(PgmPage page, Set<Long> exclude) {
        List<Long> tops = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() != PgmBoxKind.TEXT || b.getFlowClass() != FlowClass.FLOW) {
                continue;
            }
            long top = Math.round(b.getRect().getTop());
            if (exclude != null && exclude.contains(top)) {
                continue;
            }
            tops.add(top);
        }
        tops.sort(Long::compareTo);
        return tops;
    }

    /** FLOW text of a page in reading order (chrome and anchored excluded). */
    private static String flowText(PgmPage page) {
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

    private static String normalizeText(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ parts

    private static boolean singleColumn(PgmPage page) {
        ColumnStructure cs = page.getColumnStructure();
        if (cs == null) {
            cs = ColumnDetector.detect(page);
        }
        return cs.getType() == ColumnStructure.Type.SINGLE_COLUMN;
    }

    private static double bottomLimit(PgmPage page, CompactionOptions options) {
        double limit = BOTTOM_LIMIT;
        if (options.isPreserveChrome()) {
            for (PgmBox b : page.getBoxes()) {
                if (b.getFlowClass() == FlowClass.FIXED
                        && b.getRect().getTop() <= page.getHeight() / 3) {
                    limit = Math.max(limit, b.getRect().getTop() + 6);
                }
            }
        }
        return limit;
    }

    /** FLOW bands of a page, top-down (same banding as the compactor). */
    private static List<List<PgmBox>> flowBands(PgmPage page) {
        List<PgmBox> flow = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getFlowClass() == FlowClass.FLOW
                    && (b.getKind() == PgmBoxKind.TEXT || b.getKind() == PgmBoxKind.IMAGE
                    || b.getKind() == PgmBoxKind.VECTOR)) {
                flow.add(b);
            }
        }
        flow.sort((a, b) -> Double.compare(b.getRect().getTop(), a.getRect().getTop()));
        List<List<PgmBox>> bands = new ArrayList<>();
        double bandBottom = 0;
        for (PgmBox b : flow) {
            if (!bands.isEmpty() && b.getRect().getTop() >= bandBottom - 1) {
                bands.get(bands.size() - 1).add(b);
                bandBottom = Math.min(bandBottom, b.getRect().getY());
            } else {
                List<PgmBox> bandList = new ArrayList<>();
                bandList.add(b);
                bands.add(bandList);
                bandBottom = b.getRect().getY();
            }
        }
        return bands;
    }

    /**
     * Groups line bands into paragraph BLOCKS: consecutive bands whose gap is
     * within the page's own natural line spacing belong to one block.
     */
    private static List<List<PgmBox>> blocksOf(List<List<PgmBox>> bands) {
        if (bands.size() <= 1) {
            return bands;
        }
        List<Double> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < bands.size(); i++) {
            double gap = bottomOfBand(bands.get(i)) - topOfBand(bands.get(i + 1));
            if (gap > 0 && gap <= 30) {
                gaps.add(gap);
            }
        }
        double natural = FlowCompactor.DEFAULT_NATURAL_SPACING;
        if (!gaps.isEmpty()) {
            gaps.sort(Double::compare);
            natural = gaps.get(gaps.size() / 2);
        }
        double joinLimit = Math.max(FlowCompactor.DEFAULT_NATURAL_SPACING, natural * 1.5);
        List<List<PgmBox>> blocks = new ArrayList<>();
        List<PgmBox> current = new ArrayList<>(bands.get(0));
        for (int i = 1; i < bands.size(); i++) {
            double gap = bottomOfBand(blocks.isEmpty() ? current : current)
                    - topOfBand(bands.get(i));
            if (gap <= joinLimit) {
                current.addAll(bands.get(i));
            } else {
                blocks.add(current);
                current = new ArrayList<>(bands.get(i));
            }
        }
        blocks.add(current);
        return blocks;
    }

    private static double topOfBand(List<PgmBox> band) {
        double top = -Double.MAX_VALUE;
        for (PgmBox b : band) {
            top = Math.max(top, b.getRect().getTop());
        }
        return top;
    }

    private static double bottomOfBand(List<PgmBox> band) {
        double bottom = Double.MAX_VALUE;
        for (PgmBox b : band) {
            bottom = Math.min(bottom, b.getRect().getY());
        }
        return bottom;
    }

    /**
     * Returns the boxes of the maximal PREFIX of leading line-bands whose
     * stacked height (top of the first band to the bottom of the k-th) still
     * lands above {@code bottomLimit} when the prefix's top is placed at
     * {@code targetTop}; or null when not even the first line fits. Bands are
     * top-down and the returned boxes move as one unit under a single uniform
     * shift, so their relative layout is preserved. Used only on the opt-in
     * paragraph-split fallback, after the whole leading block did not fit — so
     * the prefix is always a strict subset of that block.
     */
    private static List<PgmBox> leadingLinesThatFit(List<List<PgmBox>> lineBands,
                                                    double targetTop, double bottomLimit) {
        if (lineBands.isEmpty()) {
            return null;
        }
        double top = topOfBand(lineBands.get(0));
        int fit = 0;
        for (int k = 0; k < lineBands.size(); k++) {
            double bottom = bottomOfBand(lineBands.get(k));
            if (targetTop - (top - bottom) < bottomLimit) {
                break; // adding band k would overflow the free space
            }
            fit = k + 1;
        }
        if (fit == 0) {
            return null;
        }
        List<PgmBox> boxes = new ArrayList<>();
        for (int k = 0; k < fit; k++) {
            boxes.addAll(lineBands.get(k));
        }
        return boxes;
    }

    /**
     * Expands an op range to its self-contained envelope:
     * {@code q [state] BT..ET Q}, bare {@code BT..ET}, or {@code q..Q}.
     * Returns null when the surroundings do not match a movable pattern.
     */
    static int[] envelope(OperatorCollection ops, int start, int end) {
        int n = ops.size();
        // Find enclosing BT (if the range is text).
        int bt = -1;
        for (int i = start; i >= 0; i--) {
            String name = ops.getAt(i).getName();
            if ("BT".equals(name)) {
                bt = i;
                break;
            }
            if ("ET".equals(name)) {
                break;
            }
        }
        if (bt >= 0) {
            int et = -1;
            for (int i = end; i < n; i++) {
                String name = ops.getAt(i).getName();
                if ("ET".equals(name)) {
                    et = i;
                    break;
                }
                if ("BT".equals(name) && i > end) {
                    break;
                }
            }
            if (et < 0) {
                return null;
            }
            // Optional enclosing q ... Q directly around the BT..ET.
            int from = bt;
            int to = et;
            int qAt = -1;
            for (int i = bt - 1; i >= 0; i--) {
                String name = ops.getAt(i).getName();
                if ("q".equals(name)) {
                    qAt = i;
                    break;
                }
                if (!isSimpleStateOp(name)) {
                    break;
                }
            }
            if (qAt >= 0 && et + 1 < n && "Q".equals(ops.getAt(et + 1).getName())) {
                from = qAt;
                to = et + 1;
            }
            return new int[]{from, to};
        }
        // Non-text: enclosing q..Q with only state ops around the range.
        int qAt = -1;
        for (int i = start - 1; i >= 0; i--) {
            String name = ops.getAt(i).getName();
            if ("q".equals(name)) {
                qAt = i;
                break;
            }
            if (!isSimpleStateOp(name)) {
                return null;
            }
        }
        if (qAt < 0) {
            return null;
        }
        for (int i = end + 1; i < n; i++) {
            String name = ops.getAt(i).getName();
            if ("Q".equals(name)) {
                return new int[]{qAt, i};
            }
            if (!isSimpleStateOp(name)) {
                return null;
            }
        }
        return null;
    }

    /** State ops that may sit inside a movable envelope without side effects. */
    private static boolean isSimpleStateOp(String name) {
        switch (name) {
            case "cm":
            case "w":
            case "J":
            case "j":
            case "M":
            case "d":
            case "ri":
            case "i":
            case "rg":
            case "RG":
            case "g":
            case "G":
            case "k":
            case "K":
            case "Tf":
            case "Tc":
            case "Tw":
            case "Tz":
            case "TL":
            case "Ts":
            case "Tr":
                return true;
            default:
                return false;
        }
    }

    /** Translates the envelope's positioning by dy; null on success. */
    private static String translateEnvelope(List<Operator> chunk, double dy) {
        boolean adjusted = false;
        boolean sawPositioning = false;
        for (int i = 0; i < chunk.size(); i++) {
            Operator op = chunk.get(i);
            String name = op.getName();
            if ("Tm".equals(name)) {
                chunk.set(i, shifted(op, 4, 5, 0, dy));
                adjusted = true;
                sawPositioning = true;
            } else if ("Td".equals(name) || "TD".equals(name)) {
                if (!sawPositioning) {
                    chunk.set(i, shifted(op, 0, 1, 0, dy));
                    adjusted = true;
                }
                sawPositioning = true;
            } else if ("T*".equals(name) || "'".equals(name) || "\"".equals(name)) {
                sawPositioning = true;
            } else if ("cm".equals(name) && !insideText(chunk, i)) {
                // Image placement: translate the FIRST cm of the envelope.
                if (!adjusted) {
                    chunk.set(i, shifted(op, 4, 5, 0, dy));
                    adjusted = true;
                }
            }
        }
        return adjusted ? null : "envelope has no translatable positioning";
    }

    private static boolean insideText(List<Operator> chunk, int index) {
        for (int i = index; i >= 0; i--) {
            String name = chunk.get(i).getName();
            if ("BT".equals(name)) {
                return true;
            }
            if ("ET".equals(name)) {
                return false;
            }
        }
        return false;
    }

    private static Operator shifted(Operator op, int xIdx, int yIdx, double dx, double dy) {
        List<PdfBase> operands = new ArrayList<>(op.getOperands());
        operands.set(xIdx, new PdfFloat(num(operands.get(xIdx)) + dx));
        operands.set(yIdx, new PdfFloat(num(operands.get(yIdx)) + dy));
        return new Operator(op.getName(), operands);
    }

    /**
     * Copies the resources the moved ops reference (Tf fonts, Do XObjects)
     * into the target page, renaming on conflict and rewriting operands.
     * Returns null on success or a reason when an unsupported resource kind
     * is referenced.
     */
    private static String transplantResources(List<Operator> moved, Page sourcePage,
                                              Page targetPage) throws IOException {
        Map<String, String> fontRenames = new LinkedHashMap<>();
        Map<String, String> xobjRenames = new LinkedHashMap<>();
        for (int i = 0; i < moved.size(); i++) {
            Operator op = moved.get(i);
            String name = op.getName();
            if ("gs".equals(name) || "sh".equals(name) || "cs".equals(name)
                    || "CS".equals(name) || "scn".equals(name) || "SCN".equals(name)
                    || "BDC".equals(name) || "DP".equals(name)) {
                return "moved ops reference unsupported resource kind: " + name;
            }
            if ("Tf".equals(name) && !op.getOperands().isEmpty()
                    && op.getOperands().get(0) instanceof PdfName) {
                String res = ((PdfName) op.getOperands().get(0)).getName();
                String renamed = ensureResource(sourcePage, targetPage, "Font", res,
                        fontRenames);
                if (renamed == null) {
                    return "font resource /" + res + " missing on source page";
                }
                if (!renamed.equals(res)) {
                    List<PdfBase> operands = new ArrayList<>(op.getOperands());
                    operands.set(0, PdfName.of(renamed));
                    moved.set(i, new Operator("Tf", operands));
                }
            } else if ("Do".equals(name) && !op.getOperands().isEmpty()
                    && op.getOperands().get(0) instanceof PdfName) {
                String res = ((PdfName) op.getOperands().get(0)).getName();
                String renamed = ensureResource(sourcePage, targetPage, "XObject", res,
                        xobjRenames);
                if (renamed == null) {
                    return "xobject resource /" + res + " missing on source page";
                }
                if (!renamed.equals(res)) {
                    List<PdfBase> operands = new ArrayList<>(op.getOperands());
                    operands.set(0, PdfName.of(renamed));
                    moved.set(i, new Operator("Do", operands));
                }
            }
        }
        return null;
    }

    /**
     * Ensures {@code category}/{@code resName} of the source page exists on the
     * target page; returns the (possibly renamed) target name, or null when the
     * source has no such resource.
     */
    static String ensureResource(Page sourcePage, Page targetPage,
                                         String category, String resName,
                                         Map<String, String> renames) {
        if (renames.containsKey(resName)) {
            return renames.get(resName);
        }
        PdfDictionary srcCat = categoryDict(sourcePage, category, false);
        PdfBase value = srcCat != null ? srcCat.get(resName) : null;
        if (value == null) {
            return null;
        }
        PdfDictionary dstCat = categoryDict(targetPage, category, true);
        PdfBase existing = dstCat.get(resName);
        String finalName = resName;
        if (existing == null) {
            dstCat.set(PdfName.of(resName), value);
        } else if (!sameObject(existing, value)) {
            int suffix = 1;
            while (dstCat.get(resName + "x" + suffix) != null) {
                suffix++;
            }
            finalName = resName + "x" + suffix;
            dstCat.set(PdfName.of(finalName), value);
        }
        renames.put(resName, finalName);
        return finalName;
    }

    private static boolean sameObject(PdfBase a, PdfBase b) {
        if (a == b) {
            return true;
        }
        org.aspose.pdf.engine.pdfobjects.PdfObjectKey ka = keyOf(a);
        org.aspose.pdf.engine.pdfobjects.PdfObjectKey kb = keyOf(b);
        return ka != null && ka.equals(kb);
    }

    private static org.aspose.pdf.engine.pdfobjects.PdfObjectKey keyOf(PdfBase v) {
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) v).getKey();
        }
        return v != null ? v.getObjectKey() : null;
    }

    private static PdfDictionary categoryDict(Page page, String category, boolean create) {
        org.aspose.pdf.Resources resources = create
                ? page.ensureResources() : page.getResources();
        if (resources == null) {
            return null;
        }
        PdfDictionary resDict = resources.getPdfDictionary();
        PdfBase cat = resDict.get(category);
        if (cat instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            try {
                cat = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) cat).dereference();
            } catch (IOException e) {
                cat = null;
            }
        }
        if (!(cat instanceof PdfDictionary)) {
            if (!create) {
                return null;
            }
            PdfDictionary fresh = new PdfDictionary();
            resDict.set(PdfName.of(category), fresh);
            return fresh;
        }
        return (PdfDictionary) cat;
    }

    /** Moves annotations anchored to the band's boxes onto the target page. */
    private static void moveAnchoredAnnotations(Document doc, int s, int t,
                                                List<PgmBox> band, double dy,
                                                PdfSdmReader.Result model)
            throws IOException {
        Set<String> movedIds = new HashSet<>();
        for (PgmBox b : band) {
            movedIds.add(b.getId());
        }
        PgmPage source = model.getPgm().getPage(s);
        List<PgmBox> anchored = new ArrayList<>();
        for (PgmBox b : source.getBoxes()) {
            if ((b.getKind() == PgmBoxKind.ANNOTATION || b.getKind() == PgmBoxKind.FIELD)
                    && b.getFlowClass() == FlowClass.ANCHORED
                    && b.getAnchorTargetId() != null
                    && movedIds.contains(b.getAnchorTargetId())) {
                anchored.add(b);
            }
        }
        if (anchored.isEmpty()) {
            return;
        }
        Page sourcePage = doc.getPages().get(s + 1);
        Page targetPage = doc.getPages().get(t + 1);
        AnnotationCollection sourceAnnots = sourcePage.getAnnotations();
        for (PgmBox box : anchored) {
            org.aspose.pdf.sdm.ObjectRef ref = (org.aspose.pdf.sdm.ObjectRef) box.getSourceRef();
            for (int i = 1; i <= sourceAnnots.getCount(); i++) {
                Annotation a = sourceAnnots.get(i);
                org.aspose.pdf.engine.pdfobjects.PdfObjectKey key =
                        a != null && a.getPdfDictionary() != null
                                ? a.getPdfDictionary().getObjectKey() : null;
                if (key == null || key.getObjectNumber() != ref.getObjNum()
                        || key.getGenerationNumber() != ref.getGen()) {
                    continue;
                }
                Rectangle r = a.getRect();
                if (r != null) {
                    a.setRect(new Rectangle(r.getLLX(), r.getLLY() + dy,
                            r.getURX(), r.getURY() + dy));
                }
                PdfBase qp = a.getPdfDictionary().get("QuadPoints");
                if (qp instanceof org.aspose.pdf.engine.pdfobjects.PdfArray) {
                    org.aspose.pdf.engine.pdfobjects.PdfArray old =
                            (org.aspose.pdf.engine.pdfobjects.PdfArray) qp;
                    org.aspose.pdf.engine.pdfobjects.PdfArray shifted =
                            new org.aspose.pdf.engine.pdfobjects.PdfArray(old.size());
                    for (int k = 0; k < old.size(); k++) {
                        double v = num(old.get(k));
                        shifted.add(new PdfFloat(v + (k % 2 == 0 ? 0 : dy)));
                    }
                    a.getPdfDictionary().set(PdfName.of("QuadPoints"), shifted);
                }
                sourceAnnots.delete(a);
                targetPage.getAnnotations().add(a);
                break;
            }
        }
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
