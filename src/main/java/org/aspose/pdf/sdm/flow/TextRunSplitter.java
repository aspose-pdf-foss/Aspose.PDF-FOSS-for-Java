package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.Matrix;
import org.aspose.pdf.Operator;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.sdm.ContentRange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Splits a shared {@code BT…ET} text run so a subset of its boxes (a leading
 * flow block) can be pulled to another page while the rest stays put — the
 * enabling step for cross-page flow on real-world PDFs, where an entire page's
 * text is emitted inside one or two monolithic {@code BT…ET} blocks (so the
 * raw-envelope transplant of {@link CrossPageFlow} reports "entangled").
 * <p>
 * Each movable box is re-materialised as a <b>self-contained IR fragment</b>:
 * the box's own show operator (verbatim {@code Tj}/{@code TJ}; {@code '}/{@code "}
 * are lowered to explicit {@code Tj}), preceded by a fully-resolved state
 * preamble — the ambient CTM (accumulated {@code cm}), fill colour, ExtGState
 * ({@code gs}), marked-content wrapper ({@code BDC}/{@code BMC}), text state
 * ({@code Tc/Tw/Tz/TL/Tr/Ts}), font ({@code Tf}) and an <b>absolute</b>
 * {@code Tm}. Because {@code Td}/{@code TD}/{@code T*} move the text <i>line</i>
 * matrix (not the post-show text matrix), each box's absolute {@code Tm} is
 * resolved without glyph widths.
 * </p>
 * <p>
 * The moved fragments are appended to the target with an extra page-space
 * {@code 0 dy cm}; the source's affected {@code BT…ET} spans are rebuilt from
 * the resolved fragments of only the <i>kept</i> boxes, so removing a leading
 * paragraph never reflows the remainder (relative {@code Td} chains are
 * replaced by absolute {@code Tm}). Fonts / XObjects / ExtGStates / marked-
 * content properties the moved ops reference are transplanted into the target.
 * </p>
 * <p>
 * Conservative by design — returns {@code null} (caller falls back to an honest
 * skip) when the run contains anything it does not fully model: non-device
 * fill colour ({@code sc}/{@code scn}/{@code cs}) on an affected box, marked
 * content <i>inside</i> a rebuilt {@code BT…ET}, a box without a resolvable
 * font, an image box without a self-contained envelope, or a resource that
 * cannot be transplanted. The caller additionally re-verifies flow text and box
 * positions after applying the plan and rolls back on any mismatch, so a
 * modelling gap degrades to a skip, never a corrupt page.
 * </p>
 */
public final class TextRunSplitter {

    private static final Logger LOG = Logger.getLogger(TextRunSplitter.class.getName());

    private TextRunSplitter() {
    }

    /** The rewrite to apply: new source stream + ops to append to the target. */
    public static final class SplitPlan {
        private final List<Operator> newSourceOps;
        private final List<Operator> targetAppendOps;

        SplitPlan(List<Operator> newSourceOps, List<Operator> targetAppendOps) {
            this.newSourceOps = newSourceOps;
            this.targetAppendOps = targetAppendOps;
        }

        /** @return the source page's operators after removing the moved boxes */
        public List<Operator> getNewSourceOps() {
            return newSourceOps;
        }

        /** @return operators to append to the target page's stream */
        public List<Operator> getTargetAppendOps() {
            return targetAppendOps;
        }
    }

    /**
     * Builds a split plan pulling {@code movedBoxes} out of {@code sourceOps}.
     *
     * @param sourcePage     the page owning {@code sourceOps} (for resources)
     * @param targetPage     the page the boxes move to (resources transplanted)
     * @param sourceOps      the source page's operator list
     * @param allSourceBoxes every box of the source page (kept + moved)
     * @param movedBoxes     the boxes to pull (a subset of allSourceBoxes)
     * @param dy             page-space vertical shift applied on the target
     * @return the plan, or {@code null} to fall back to an honest skip
     * @throws IOException if a resource cannot be read for transplant
     */
    public static SplitPlan plan(Page sourcePage, Page targetPage, List<Operator> sourceOps,
                                 List<PgmBox> allSourceBoxes, Set<PgmBox> movedBoxes, double dy)
            throws IOException {
        boolean debug = Boolean.getBoolean("ir.flow.debug");
        int n = sourceOps.size();

        // 1. Resolve per-show-op state and per-BT extents in a single walk.
        Walk walk = resolve(sourceOps);

        // 2. Map every box to its show/Do operator index.
        Map<Integer, PgmBox> boxByShow = new HashMap<>();
        for (PgmBox b : allSourceBoxes) {
            if (!(b.getSourceRef() instanceof ContentRange)) {
                continue;
            }
            int show = ((ContentRange) b.getSourceRef()).getOpEnd();
            if (show >= 0 && show < n) {
                boxByShow.put(show, b);
            }
        }
        Set<Integer> movedShow = new HashSet<>();
        for (PgmBox b : movedBoxes) {
            if (b.getSourceRef() instanceof ContentRange) {
                movedShow.add(((ContentRange) b.getSourceRef()).getOpEnd());
            }
        }

        // 3. Partition moved boxes into text (BT spans) and images (envelopes).
        Set<Integer> affectedBt = new HashSet<>();
        List<PgmBox> movedImages = new ArrayList<>();
        for (PgmBox b : movedBoxes) {
            if (b.getKind() == PgmBoxKind.TEXT) {
                Integer bt = walk.btOfShow.get(((ContentRange) b.getSourceRef()).getOpEnd());
                if (bt == null) {
                    if (debug) {
                        System.out.println("[split] text box not inside a BT..ET -> fallback");
                    }
                    return null;
                }
                affectedBt.add(bt);
            } else if (b.getKind() == PgmBoxKind.IMAGE) {
                movedImages.add(b);
            } else {
                if (debug) {
                    System.out.println("[split] moved box kind " + b.getKind() + " -> fallback");
                }
                return null; // VECTOR etc. not supported by the splitter
            }
        }

        // 4. Guard: no marked content inside any affected BT..ET (kept content's
        //    tags would be dropped by the flat rebuild and verify can't see it).
        for (int bt : affectedBt) {
            int et = walk.etOfBt.get(bt);
            for (int i = bt + 1; i < et; i++) {
                String nm = sourceOps.get(i).getName();
                if ("BDC".equals(nm) || "BMC".equals(nm) || "EMC".equals(nm)) {
                    if (debug) {
                        System.out.println("[split] marked content inside BT..ET -> fallback");
                    }
                    return null;
                }
            }
        }

        // 5. Validate every box (moved AND kept) of each affected span is fully
        //    modelled: device fill only, resolvable font.
        for (int bt : affectedBt) {
            int et = walk.etOfBt.get(bt);
            for (int i = bt + 1; i < et; i++) {
                Frag f = walk.fragByShow.get(i);
                if (f == null) {
                    continue; // not a show op
                }
                if (f.fill != null && !f.fillDevice) {
                    if (debug) {
                        System.out.println("[split] non-device fill in span -> fallback");
                    }
                    return null;
                }
                if (f.font == null) {
                    if (debug) {
                        System.out.println("[split] box without font -> fallback");
                    }
                    return null;
                }
            }
        }

        // 6. Resolve image envelopes and validate before mutating resources.
        Map<PgmBox, Frag> imageFrag = new HashMap<>();
        Set<Integer> imageRemoved = new HashSet<>();
        for (PgmBox img : movedImages) {
            int show = ((ContentRange) img.getSourceRef()).getOpEnd();
            Frag f = walk.fragByShow.get(show);
            if (f == null) {
                if (debug) {
                    System.out.println("[split] image without resolved Do -> fallback");
                }
                return null;
            }
            org.aspose.pdf.OperatorCollection oc = new org.aspose.pdf.OperatorCollection(sourceOps);
            int[] env = CrossPageFlow.envelope(oc, show, show);
            if (env == null) {
                if (debug) {
                    System.out.println("[split] image envelope null -> fallback");
                }
                return null;
            }
            for (int i = env[0]; i <= env[1]; i++) {
                imageRemoved.add(i);
            }
            imageFrag.put(img, f);
        }

        // 7. Transplant resources referenced by the MOVED boxes, capturing name
        //    remaps to rewrite operands on the target side.
        Map<String, String> fontRe = new LinkedHashMap<>();
        Map<String, String> xobjRe = new LinkedHashMap<>();
        Map<String, String> gsRe = new LinkedHashMap<>();
        Map<String, String> propRe = new LinkedHashMap<>();
        for (PgmBox b : movedBoxes) {
            int show = ((ContentRange) b.getSourceRef()).getOpEnd();
            Frag f = walk.fragByShow.get(show);
            if (f == null) {
                continue;
            }
            if (b.getKind() == PgmBoxKind.TEXT) {
                if (transplant(sourcePage, targetPage, "Font", fontName(f.font), fontRe) == null) {
                    return fail(debug, "font resource missing");
                }
            } else if (b.getKind() == PgmBoxKind.IMAGE) {
                if (transplant(sourcePage, targetPage, "XObject", doName(f.show), xobjRe) == null) {
                    return fail(debug, "xobject resource missing");
                }
            }
            if (f.gsOp != null
                    && transplant(sourcePage, targetPage, "ExtGState", opName0(f.gsOp), gsRe) == null) {
                return fail(debug, "extgstate resource missing");
            }
            for (Operator mc : f.mc) {
                String prop = markedPropName(mc);
                if (prop != null
                        && transplant(sourcePage, targetPage, "Properties", prop, propRe) == null) {
                    return fail(debug, "marked-content property missing");
                }
            }
        }

        // 8. Build the target append list (moved fragments, in op order).
        List<Integer> movedOrder = new ArrayList<>(movedShow);
        movedOrder.sort(Integer::compareTo);
        List<Operator> targetAppend = new ArrayList<>();
        for (int show : movedOrder) {
            PgmBox b = boxByShow.get(show);
            Frag f = walk.fragByShow.get(show);
            if (b == null || f == null) {
                continue;
            }
            if (b.getKind() == PgmBoxKind.IMAGE) {
                emitImageFragment(targetAppend, f, dy, xobjRe, gsRe);
            } else {
                emitTextFragment(targetAppend, f, dy, fontRe, gsRe, propRe);
            }
        }

        // 9. Build the new source stream: drop moved images' envelopes, and
        //    replace each affected BT..ET with the kept boxes rebuilt.
        Map<Integer, int[]> spanRange = new HashMap<>(); // bt -> {et}
        Map<Integer, List<Operator>> spanRebuild = new HashMap<>();
        for (int bt : affectedBt) {
            int et = walk.etOfBt.get(bt);
            List<Operator> rebuilt = new ArrayList<>();
            List<Integer> keptShows = new ArrayList<>();
            for (int i = bt + 1; i < et; i++) {
                if (walk.fragByShow.containsKey(i) && !movedShow.contains(i)) {
                    keptShows.add(i);
                }
            }
            if (!keptShows.isEmpty()) {
                rebuilt.add(new Operator("BT"));
                for (int show : keptShows) {
                    emitSourceLine(rebuilt, walk.fragByShow.get(show));
                }
                rebuilt.add(new Operator("ET"));
            }
            spanRange.put(bt, new int[]{et});
            spanRebuild.put(bt, rebuilt);
        }

        List<Operator> newSource = new ArrayList<>();
        int i = 0;
        while (i < n) {
            if (spanRange.containsKey(i)) {
                newSource.addAll(spanRebuild.get(i));
                i = spanRange.get(i)[0] + 1; // skip past ET
                continue;
            }
            if (imageRemoved.contains(i)) {
                i++;
                continue;
            }
            newSource.add(sourceOps.get(i));
            i++;
        }

        LOG.fine(() -> "split plan: moved=" + movedShow.size() + " spans=" + affectedBt.size()
                + " images=" + movedImages.size());
        return new SplitPlan(newSource, targetAppend);
    }

    private static SplitPlan fail(boolean debug, String why) {
        if (debug) {
            System.out.println("[split] " + why + " -> fallback");
        }
        return null;
    }

    // ------------------------------------------------------------- emission

    /** Emits a self-contained text fragment on the target (with the dy shift). */
    private static void emitTextFragment(List<Operator> out, Frag f, double dy,
                                         Map<String, String> fontRe, Map<String, String> gsRe,
                                         Map<String, String> propRe) {
        out.add(new Operator("q"));
        out.add(cm(1, 0, 0, 1, 0, dy));
        if (!isIdentity(f.ctm)) {
            out.add(cm(f.ctm));
        }
        if (f.gsOp != null) {
            out.add(renamed(f.gsOp, gsRe));
        }
        if (f.fill != null) {
            out.add(f.fill);
        }
        for (Operator mc : f.mc) {
            out.add(renamedMarked(mc, propRe));
        }
        out.add(new Operator("BT"));
        emitTextState(out, f);
        out.add(renamed(f.font, fontRe));
        out.add(tm(f.tm));
        out.add(showOp(f.show));
        out.add(new Operator("ET"));
        for (int k = 0; k < f.mc.size(); k++) {
            out.add(new Operator("EMC"));
        }
        out.add(new Operator("Q"));
    }

    /** Emits a self-contained image fragment on the target (with the dy shift). */
    private static void emitImageFragment(List<Operator> out, Frag f, double dy,
                                          Map<String, String> xobjRe, Map<String, String> gsRe) {
        out.add(new Operator("q"));
        out.add(cm(1, 0, 0, 1, 0, dy));
        if (!isIdentity(f.ctm)) {
            out.add(cm(f.ctm));
        }
        if (f.gsOp != null) {
            out.add(renamed(f.gsOp, gsRe));
        }
        out.add(renamedDo(f.show, xobjRe));
        out.add(new Operator("Q"));
    }

    /**
     * Emits one kept line for the in-place source rebuild. The surrounding
     * {@code q cm … Q} wrapper survives, so the ambient CTM is not replayed; the
     * fill / ExtGState / text state / font are replayed per line (idempotent)
     * and the positioning is made absolute so nothing reflows.
     */
    private static void emitSourceLine(List<Operator> out, Frag f) {
        if (f.gsOp != null) {
            out.add(f.gsOp);
        }
        if (f.fill != null) {
            out.add(f.fill);
        }
        emitTextState(out, f);
        out.add(f.font);
        out.add(tm(f.tm));
        out.add(showOp(f.show));
    }

    /**
     * Emits the text-state ({@code Tc Tw Tz Tr Ts}) that governs the following
     * show operator. These are emitted EXPLICITLY (even at their defaults) so a
     * line never inherits a stale value: on the source side the kept lines share
     * one {@code BT…ET} (no {@code q/Q} is allowed inside a text object, so state
     * leaks line-to-line); on the target side the fragment's {@code q} inherits
     * whatever text state the page's trailing content left active. {@code TL} is
     * not needed — {@code '}/{@code "} are lowered to an absolute {@code Tm} plus
     * {@code Tj}, so no {@code T*} leading motion remains.
     */
    private static void emitTextState(List<Operator> out, Frag f) {
        out.add(new Operator("Tc", one(f.tc)));
        out.add(new Operator("Tw", one(f.tw)));
        out.add(new Operator("Tz", one(f.tz)));
        out.add(new Operator("Tr", one(f.tr)));
        out.add(new Operator("Ts", one(f.ts)));
    }

    /** Lowers {@code '}/{@code "} to a plain {@code Tj}; keeps {@code Tj}/{@code TJ}. */
    private static Operator showOp(Operator op) {
        String name = op.getName();
        if ("'".equals(name)) {
            return new Operator("Tj", new ArrayList<>(op.getOperands()));
        }
        if ("\"".equals(name)) {
            List<PdfBase> ops = op.getOperands();
            List<PdfBase> str = new ArrayList<>();
            str.add(ops.get(ops.size() - 1)); // aw ac string -> string
            return new Operator("Tj", str);
        }
        return op;
    }

    // ------------------------------------------------------------- resolving

    /** Per-show-op resolved state; a single movable IR fragment. */
    private static final class Frag {
        Matrix ctm;
        Matrix tm;
        Operator fill;
        boolean fillDevice;
        Operator gsOp;
        Operator font;
        double tc, tw, tz, tl, tr, ts;
        List<Operator> mc;
        Operator show;
    }

    private static final class GState {
        Matrix ctm = Matrix.IDENTITY;
        Operator fill;
        boolean fillDevice;
        Operator gsOp;
        Operator font;
        double tc, tw, tz = 100, tl, tr, ts;

        GState copy() {
            GState g = new GState();
            g.ctm = ctm;
            g.fill = fill;
            g.fillDevice = fillDevice;
            g.gsOp = gsOp;
            g.font = font;
            g.tc = tc;
            g.tw = tw;
            g.tz = tz;
            g.tl = tl;
            g.tr = tr;
            g.ts = ts;
            return g;
        }
    }

    private static final class Walk {
        final Map<Integer, Frag> fragByShow = new HashMap<>();
        final Map<Integer, Integer> btOfShow = new HashMap<>();
        final Map<Integer, Integer> etOfBt = new HashMap<>();
    }

    private static Walk resolve(List<Operator> ops) {
        Walk w = new Walk();
        GState gs = new GState();
        List<GState> gstack = new ArrayList<>();
        List<Operator> mc = new ArrayList<>();
        Matrix tm = null;
        Matrix tlm = null;
        int curBt = -1;
        int n = ops.size();
        for (int i = 0; i < n; i++) {
            Operator op = ops.get(i);
            String name = op.getName();
            switch (name) {
                case "q":
                    gstack.add(gs.copy());
                    break;
                case "Q":
                    if (!gstack.isEmpty()) {
                        gs = gstack.remove(gstack.size() - 1);
                    }
                    break;
                case "cm":
                    gs.ctm = matrixOf(op).multiply(gs.ctm);
                    break;
                case "rg":
                case "g":
                case "k":
                    gs.fill = op;
                    gs.fillDevice = true;
                    break;
                case "cs":
                case "sc":
                case "scn":
                    gs.fill = op;
                    gs.fillDevice = false;
                    break;
                case "gs":
                    gs.gsOp = op;
                    break;
                case "BDC":
                case "BMC":
                    mc.add(op);
                    break;
                case "EMC":
                    if (!mc.isEmpty()) {
                        mc.remove(mc.size() - 1);
                    }
                    break;
                case "BT":
                    tm = Matrix.IDENTITY;
                    tlm = Matrix.IDENTITY;
                    curBt = i;
                    break;
                case "ET":
                    if (curBt >= 0) {
                        w.etOfBt.put(curBt, i);
                    }
                    tm = null;
                    tlm = null;
                    curBt = -1;
                    break;
                case "Tm":
                    tlm = matrixOf(op);
                    tm = tlm;
                    break;
                case "Td":
                    tlm = translate(num(op, 0), num(op, 1)).multiply(tlm);
                    tm = tlm;
                    break;
                case "TD":
                    gs.tl = -num(op, 1);
                    tlm = translate(num(op, 0), num(op, 1)).multiply(tlm);
                    tm = tlm;
                    break;
                case "T*":
                    tlm = translate(0, -gs.tl).multiply(tlm == null ? Matrix.IDENTITY : tlm);
                    tm = tlm;
                    break;
                case "Tc":
                    gs.tc = num(op, 0);
                    break;
                case "Tw":
                    gs.tw = num(op, 0);
                    break;
                case "Tz":
                    gs.tz = num(op, 0);
                    break;
                case "TL":
                    gs.tl = num(op, 0);
                    break;
                case "Tr":
                    gs.tr = num(op, 0);
                    break;
                case "Ts":
                    gs.ts = num(op, 0);
                    break;
                case "Tf":
                    gs.font = op;
                    break;
                case "'":
                    tlm = translate(0, -gs.tl).multiply(tlm == null ? Matrix.IDENTITY : tlm);
                    tm = tlm;
                    break;
                case "\"":
                    gs.tw = num(op, 0);
                    gs.tc = num(op, 1);
                    tlm = translate(0, -gs.tl).multiply(tlm == null ? Matrix.IDENTITY : tlm);
                    tm = tlm;
                    break;
                default:
                    break;
            }
            if (isShow(name)) {
                Frag f = new Frag();
                f.ctm = gs.ctm;
                f.tm = tm == null ? Matrix.IDENTITY : tm;
                f.fill = gs.fill;
                f.fillDevice = gs.fillDevice;
                f.gsOp = gs.gsOp;
                f.font = gs.font;
                f.tc = gs.tc;
                f.tw = gs.tw;
                f.tz = gs.tz;
                f.tl = gs.tl;
                f.tr = gs.tr;
                f.ts = gs.ts;
                f.mc = new ArrayList<>(mc);
                f.show = op;
                w.fragByShow.put(i, f);
                if (curBt >= 0) {
                    w.btOfShow.put(i, curBt);
                }
            } else if ("Do".equals(name)) {
                Frag f = new Frag();
                f.ctm = gs.ctm;
                f.gsOp = gs.gsOp;
                f.mc = new ArrayList<>(mc);
                f.show = op;
                w.fragByShow.put(i, f);
            }
        }
        return w;
    }

    private static boolean isShow(String name) {
        return "Tj".equals(name) || "TJ".equals(name) || "'".equals(name) || "\"".equals(name);
    }

    // ------------------------------------------------------------- helpers

    private static String transplant(Page src, Page dst, String cat, String name,
                                     Map<String, String> renames) {
        if (name == null) {
            return null;
        }
        return CrossPageFlow.ensureResource(src, dst, cat, name, renames);
    }

    private static Operator renamed(Operator op, Map<String, String> renames) {
        if (op == null || op.getOperands().isEmpty()
                || !(op.getOperands().get(0) instanceof PdfName)) {
            return op;
        }
        String orig = ((PdfName) op.getOperands().get(0)).getName();
        String to = renames.get(orig);
        if (to == null || to.equals(orig)) {
            return op;
        }
        List<PdfBase> ops = new ArrayList<>(op.getOperands());
        ops.set(0, PdfName.of(to));
        return new Operator(op.getName(), ops);
    }

    private static Operator renamedDo(Operator op, Map<String, String> renames) {
        return renamed(op, renames);
    }

    private static Operator renamedMarked(Operator op, Map<String, String> renames) {
        // BDC /Tag /PropName  — rename the second operand when it is a name that
        // was transplanted into /Properties.
        List<PdfBase> ops = op.getOperands();
        if (ops.size() < 2 || !(ops.get(1) instanceof PdfName)) {
            return op;
        }
        String orig = ((PdfName) ops.get(1)).getName();
        String to = renames.get(orig);
        if (to == null || to.equals(orig)) {
            return op;
        }
        List<PdfBase> copy = new ArrayList<>(ops);
        copy.set(1, PdfName.of(to));
        return new Operator(op.getName(), copy);
    }

    private static String fontName(Operator tf) {
        return opName0(tf);
    }

    private static String doName(Operator doOp) {
        return opName0(doOp);
    }

    private static String opName0(Operator op) {
        if (op == null || op.getOperands().isEmpty()
                || !(op.getOperands().get(0) instanceof PdfName)) {
            return null;
        }
        return ((PdfName) op.getOperands().get(0)).getName();
    }

    private static String markedPropName(Operator mc) {
        List<PdfBase> ops = mc.getOperands();
        if (ops.size() >= 2 && ops.get(1) instanceof PdfName) {
            return ((PdfName) ops.get(1)).getName();
        }
        return null;
    }

    private static Operator cm(double a, double b, double c, double d, double e, double f) {
        List<PdfBase> ops = new ArrayList<>(6);
        ops.add(new PdfFloat(a));
        ops.add(new PdfFloat(b));
        ops.add(new PdfFloat(c));
        ops.add(new PdfFloat(d));
        ops.add(new PdfFloat(e));
        ops.add(new PdfFloat(f));
        return new Operator("cm", ops);
    }

    private static Operator cm(Matrix m) {
        return cm(m.getA(), m.getB(), m.getC(), m.getD(), m.getE(), m.getF());
    }

    private static Operator tm(Matrix m) {
        List<PdfBase> ops = new ArrayList<>(6);
        ops.add(new PdfFloat(m.getA()));
        ops.add(new PdfFloat(m.getB()));
        ops.add(new PdfFloat(m.getC()));
        ops.add(new PdfFloat(m.getD()));
        ops.add(new PdfFloat(m.getE()));
        ops.add(new PdfFloat(m.getF()));
        return new Operator("Tm", ops);
    }

    private static List<PdfBase> one(double v) {
        List<PdfBase> ops = new ArrayList<>(1);
        ops.add(new PdfFloat(v));
        return ops;
    }

    private static boolean isIdentity(Matrix m) {
        return m.getA() == 1 && m.getB() == 0 && m.getC() == 0 && m.getD() == 1
                && m.getE() == 0 && m.getF() == 0;
    }

    private static Matrix translate(double tx, double ty) {
        return new Matrix(1, 0, 0, 1, tx, ty);
    }

    private static Matrix matrixOf(Operator op) {
        List<PdfBase> ops = op.getOperands();
        if (ops.size() < 6) {
            return Matrix.IDENTITY;
        }
        return new Matrix(num(op, 0), num(op, 1), num(op, 2), num(op, 3), num(op, 4), num(op, 5));
    }

    private static double num(Operator op, int idx) {
        List<PdfBase> ops = op.getOperands();
        if (idx >= ops.size()) {
            return 0;
        }
        PdfBase v = ops.get(idx);
        if (v instanceof PdfInteger) {
            return ((PdfInteger) v).longValue();
        }
        if (v instanceof PdfFloat) {
            return ((PdfFloat) v).doubleValue();
        }
        return 0;
    }
}
