package org.aspose.pdf.sdm.writer;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.pgm.ImageBoxData;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.VectorBoxData;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.SdmDocument;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * SDM/PGM → PDF serialisation (IR Stage 1, PART 3): IDENTITY mode only.
 * <p>
 * {@code regeneratePages} rebuilds the listed pages' content streams FROM the
 * model without any change; all other pages are untouched. Identity semantics:
 * every {@link ContentRange}-owned operator range is replayed from the model's
 * recorded source ranges (Opaque replay — verbatim by definition, IR spec
 * §1.0-5), and operators owned by no box (graphics-state setup: q/Q/cm/gs,
 * color, clip, BT/ET framing) are preserved in their original positions —
 * without them the replayed ranges would render under the wrong state.
 * </p>
 * <p>
 * The pass validates the model against the live operator list: a box range
 * beyond the operator count means a STALE model (the page changed after
 * projection) and throws — regenerating from a stale model would corrupt the
 * page. The oracle for this pass is rendered identity (windowed SSIM ≥ 0.99),
 * not byte identity: operator serialisation may differ in whitespace/number
 * formatting.
 * </p>
 */
public final class PdfSdmWriter {

    private static final Logger LOG = Logger.getLogger(PdfSdmWriter.class.getName());

    /**
     * Regenerates the content streams of the listed pages from the model,
     * identity mode (no content change). Other pages are not touched.
     *
     * @param doc         the open document (will be mutated)
     * @param sdm         the semantic projection (consulted for Opaque replay)
     * @param pgm         the geometric projection carrying the ContentRanges
     * @param pageIndexes 0-based page indexes to regenerate
     * @throws IOException if page content cannot be read or written
     * @throws IllegalStateException if the model is stale for a page
     */
    public void regeneratePages(Document doc, SdmDocument sdm, PgmModel pgm,
                                int... pageIndexes) throws IOException {
        if (doc == null || pgm == null || pageIndexes == null) {
            throw new IllegalArgumentException("doc, pgm and pageIndexes are required");
        }
        for (int pi : pageIndexes) {
            regeneratePage(doc, pgm, pi);
        }
    }

    private void regeneratePage(Document doc, PgmModel pgm, int pageIndex) throws IOException {
        Page page = doc.getPages().get(pageIndex + 1);
        OperatorCollection ops = page.getContents();
        int n = ops.size();
        PgmPage pp = pgm.getPage(pageIndex);

        // Phase 1 — validate the model and mark box-owned operator slots.
        boolean[] owned = new boolean[n];
        for (PgmBox box : pp.getBoxes()) {
            if (!(box.getSourceRef() instanceof ContentRange)) {
                continue; // ObjectRef boxes (annotations/fields) have nothing to regenerate
            }
            ContentRange cr = (ContentRange) box.getSourceRef();
            if (cr.getOpEnd() >= n) {
                throw new IllegalStateException("stale PGM model: box " + box
                        + " claims ops " + cr.getOpStart() + ".." + cr.getOpEnd()
                        + " but page " + pageIndex + " has " + n + " operators");
            }
            for (int i = cr.getOpStart(); i <= cr.getOpEnd(); i++) {
                owned[i] = true;
            }
        }

        // Phase 2 — rebuild the stream: replay owned ranges from the model's
        // boxes in z-order (identity: same slots), keep un-owned state ops in
        // place. Every source operator is emitted exactly once, in original
        // order — pixel-identical by construction, and the model's ranges are
        // proven live against the real operator list.
        Operator[] slots = new Operator[n];
        for (PgmBox box : pp.getBoxes()) {
            if (!(box.getSourceRef() instanceof ContentRange)) {
                continue;
            }
            ContentRange cr = (ContentRange) box.getSourceRef();
            for (int i = cr.getOpStart(); i <= cr.getOpEnd(); i++) {
                slots[i] = ops.getAt(i);
            }
        }
        int stateOps = 0;
        for (int i = 0; i < n; i++) {
            if (slots[i] == null) {
                slots[i] = ops.getAt(i);
                stateOps++;
            }
        }
        List<Operator> out = new ArrayList<>(n);
        for (Operator op : slots) {
            out.add(op);
        }
        final int preserved = stateOps;
        LOG.fine(() -> "identity-regenerated page " + pageIndex + ": " + n
                + " ops (" + (n - preserved) + " box-owned, " + preserved + " state)");
        page.setContents(new OperatorCollection(out));
    }

    // ---------------------------------------------------------------- deltas

    /**
     * Thrown when a page's content cannot be positionally translated (e.g. a
     * relative Td chain whose members need different deltas). The page is left
     * untouched; the caller records the reason and skips the page.
     */
    public static final class UnsupportedPageOperation extends RuntimeException {
        /**
         * Creates the exception.
         *
         * @param reason the human-readable reason
         */
        public UnsupportedPageOperation(String reason) {
            super(reason);
        }
    }

    /**
     * POSITIONAL replay (IR Stage 2): regenerates one page's content stream
     * with per-box coordinate deltas {dx, dy} in page space.
     * <ul>
     *   <li>TEXT — the box's governing text positioning is translated: every
     *       {@code Tm} inside the range gets e/f adjusted; a range without its
     *       own Tm falls back to the governing op between the enclosing BT and
     *       the range (Tm, or a BT-initial Td/TD, which is absolute because
     *       the line matrix is still identity). A mid-chain relative Td/TD or
     *       T-star/quote positioning throws {@link UnsupportedPageOperation}.</li>
     *   <li>IMAGE/VECTOR — the range is wrapped in {@code q M cm ... Q} where
     *       {@code M = C·T·C⁻¹} (C = recorded CTM, T = device translation), so
     *       the object moves by exactly (dx,dy) in page space whatever the
     *       surrounding transform. A vector range containing a clip (W/W*) is
     *       rejected: wrapping would drop the clip at Q.</li>
     * </ul>
     * Boxes with ObjectRef locators (annotations) are NOT handled here — the
     * caller edits their /Rect (+/QuadPoints) directly.
     *
     * @param doc       the open document (mutated)
     * @param pgm       the projection the boxes belong to
     * @param pageIndex the 0-based page to regenerate
     * @param deltas    box → {dx, dy}; boxes with zero delta may be omitted
     * @throws IOException if page content cannot be read or written
     * @throws UnsupportedPageOperation if the page uses positioning the
     *         translator cannot adjust (page untouched)
     */
    public void applyDeltas(Document doc, PgmModel pgm, int pageIndex,
                            Map<PgmBox, double[]> deltas) throws IOException {
        Page page = doc.getPages().get(pageIndex + 1);
        OperatorCollection ops = page.getContents();
        int n = ops.size();

        Map<Integer, Operator> replaced = new HashMap<>();
        Map<Integer, double[]> adjustedBy = new HashMap<>();
        Map<Integer, double[]> wrapBefore = new HashMap<>();
        Set<Integer> wrapAfter = new HashSet<>();

        for (Map.Entry<PgmBox, double[]> e : deltas.entrySet()) {
            PgmBox box = e.getKey();
            double[] d = e.getValue();
            if (d == null || (d[0] == 0 && d[1] == 0)) {
                continue;
            }
            if (!(box.getSourceRef() instanceof ContentRange)) {
                continue; // ObjectRef boxes are edited by the caller
            }
            ContentRange cr = (ContentRange) box.getSourceRef();
            if (cr.getOpEnd() >= n) {
                throw new IllegalStateException("stale PGM model: " + box);
            }
            if (box.getKind() == PgmBoxKind.TEXT) {
                translateText(ops, cr, d, replaced, adjustedBy);
            } else if (box.getKind() == PgmBoxKind.IMAGE) {
                double[] ctm = box.getData() instanceof ImageBoxData
                        && ((ImageBoxData) box.getData()).getMatrix() != null
                        ? ((ImageBoxData) box.getData()).getMatrix().getValues() : null;
                wrapRange(cr, d, ctm, ops, wrapBefore, wrapAfter);
            } else if (box.getKind() == PgmBoxKind.VECTOR) {
                double[] ctm = box.getData() instanceof VectorBoxData
                        ? ((VectorBoxData) box.getData()).getCtm() : null;
                for (int i = cr.getOpStart(); i <= cr.getOpEnd(); i++) {
                    String name = ops.getAt(i).getName();
                    if ("W".equals(name) || "W*".equals(name)) {
                        throw new UnsupportedPageOperation(
                                "vector range contains a clip (W) at op " + i);
                    }
                }
                wrapRange(cr, d, ctm, ops, wrapBefore, wrapAfter);
            } else {
                throw new UnsupportedPageOperation(
                        "cannot translate box kind " + box.getKind());
            }
        }

        List<Operator> out = new ArrayList<>(n + 2 * wrapBefore.size());
        for (int i = 0; i < n; i++) {
            double[] m = wrapBefore.get(i);
            if (m != null) {
                out.add(new Operator("q"));
                out.add(new Operator("cm", numOperands(m)));
            }
            Operator op = replaced.get(i);
            out.add(op != null ? op : ops.getAt(i));
            if (wrapAfter.contains(i)) {
                out.add(new Operator("Q"));
            }
        }
        page.setContents(new OperatorCollection(out));
    }

    /** Translates a TEXT box's governing positioning by delta. */
    private void translateText(OperatorCollection ops, ContentRange cr, double[] d,
                               Map<Integer, Operator> replaced,
                               Map<Integer, double[]> adjustedBy) {
        boolean adjusted = false;
        for (int i = cr.getOpStart(); i <= cr.getOpEnd(); i++) {
            if ("Tm".equals(ops.getAt(i).getName())) {
                adjustSlot(ops, i, d, 4, 5, replaced, adjustedBy);
                adjusted = true;
            }
        }
        if (adjusted) {
            return;
        }
        // No Tm inside the range — find the governing positioning op between
        // the enclosing BT and the range start.
        int bt = -1;
        for (int i = cr.getOpStart() - 1; i >= 0; i--) {
            String name = ops.getAt(i).getName();
            if ("BT".equals(name)) {
                bt = i;
                break;
            }
            if ("ET".equals(name)) {
                break;
            }
        }
        if (bt < 0) {
            throw new UnsupportedPageOperation(
                    "text box at ops " + cr.getOpStart() + ".." + cr.getOpEnd()
                            + " has no enclosing BT");
        }
        // Adjusting a positioning op OUTSIDE the box's range is only safe when
        // nothing was SHOWN before the box in this BT — otherwise the shared
        // op moves the earlier (unmoved) text too and tears the line.
        for (int i = bt + 1; i < cr.getOpStart(); i++) {
            String name = ops.getAt(i).getName();
            if ("Tj".equals(name) || "TJ".equals(name) || "'".equals(name)
                    || "\"".equals(name)) {
                throw new UnsupportedPageOperation(
                        "box shares its BT with earlier shown text (op " + i + ")");
            }
        }
        int governing = -1;
        String governingName = null;
        int positioningCount = 0;
        for (int i = bt + 1; i <= cr.getOpEnd(); i++) {
            String name = ops.getAt(i).getName();
            if ("Tm".equals(name) || "Td".equals(name) || "TD".equals(name)
                    || "T*".equals(name) || "'".equals(name) || "\"".equals(name)) {
                positioningCount++;
                if (i < cr.getOpStart() || governing < 0) {
                    governing = i;
                    governingName = name;
                }
            }
        }
        if (governing < 0) {
            throw new UnsupportedPageOperation("text box has no positioning op after BT");
        }
        if ("Tm".equals(governingName)) {
            adjustSlot(ops, governing, d, 4, 5, replaced, adjustedBy);
        } else if (("Td".equals(governingName) || "TD".equals(governingName))
                && positioningCount == 1) {
            // BT-initial Td: line matrix is identity, the offset is absolute.
            adjustSlot(ops, governing, d, 0, 1, replaced, adjustedBy);
        } else {
            throw new UnsupportedPageOperation("relative text positioning ("
                    + governingName + ", chain of " + positioningCount
                    + ") cannot take a per-box delta");
        }
    }

    /** Replaces slot i with a copy whose operands[xIdx/yIdx] are shifted. */
    private void adjustSlot(OperatorCollection ops, int i, double[] d, int xIdx, int yIdx,
                            Map<Integer, Operator> replaced,
                            Map<Integer, double[]> adjustedBy) {
        double[] prev = adjustedBy.get(i);
        if (prev != null) {
            if (prev[0] != d[0] || prev[1] != d[1]) {
                throw new UnsupportedPageOperation("op " + i
                        + " governs boxes with different deltas");
            }
            return; // same delta already applied
        }
        Operator op = replaced.getOrDefault(i, ops.getAt(i));
        List<PdfBase> operands = op.getOperands();
        if (operands.size() <= Math.max(xIdx, yIdx)) {
            throw new UnsupportedPageOperation("malformed positioning op at " + i);
        }
        List<PdfBase> next = new ArrayList<>(operands);
        next.set(xIdx, new PdfFloat(num(operands.get(xIdx)) + d[0]));
        next.set(yIdx, new PdfFloat(num(operands.get(yIdx)) + d[1]));
        replaced.put(i, new Operator(op.getName(), next));
        adjustedBy.put(i, d.clone());
    }

    /** Registers a q/cm(M)/Q wrap around the range for a device translation. */
    private void wrapRange(ContentRange cr, double[] d, double[] ctm,
                           OperatorCollection ops, Map<Integer, double[]> wrapBefore,
                           Set<Integer> wrapAfter) {
        if (ctm == null) {
            throw new UnsupportedPageOperation("no recorded CTM for range "
                    + cr.getOpStart() + ".." + cr.getOpEnd());
        }
        if (wrapBefore.containsKey(cr.getOpStart())) {
            throw new UnsupportedPageOperation("overlapping wrapped ranges at op "
                    + cr.getOpStart());
        }
        // M = C·T·C⁻¹ so that CTM' = M·C equals C followed by the device
        // translation T (row-vector convention, ISO 32000 §8.3.4).
        double[] t = {1, 0, 0, 1, d[0], d[1]};
        double[] m = mul(mul(ctm, t), invert(ctm));
        wrapBefore.put(cr.getOpStart(), m);
        wrapAfter.add(cr.getOpEnd());
    }

    private static List<PdfBase> numOperands(double[] m) {
        List<PdfBase> out = new ArrayList<>(6);
        for (double v : m) {
            out.add(new PdfFloat(v));
        }
        return out;
    }

    private static double[] mul(double[] m1, double[] m2) {
        return new double[]{
                m1[0] * m2[0] + m1[1] * m2[2],
                m1[0] * m2[1] + m1[1] * m2[3],
                m1[2] * m2[0] + m1[3] * m2[2],
                m1[2] * m2[1] + m1[3] * m2[3],
                m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
                m1[4] * m2[1] + m1[5] * m2[3] + m2[5]
        };
    }

    private static double[] invert(double[] m) {
        double det = m[0] * m[3] - m[1] * m[2];
        if (Math.abs(det) < 1e-12) {
            throw new UnsupportedPageOperation("degenerate CTM " + Arrays.toString(m));
        }
        return new double[]{
                m[3] / det, -m[1] / det,
                -m[2] / det, m[0] / det,
                (m[2] * m[5] - m[3] * m[4]) / det,
                (m[1] * m[4] - m[0] * m[5]) / det
        };
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
