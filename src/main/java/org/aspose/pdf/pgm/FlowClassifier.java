package org.aspose.pdf.pgm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * flowClass assignment (IR Stage 1, PART 5): FIXED chrome, ANCHORED
 * annotations/captions, ATOMIC vector clusters — everything else stays FLOW.
 * <p>
 * A false-FLOW on true chrome moves a page number into the body text during
 * compaction — worse than a missed detection — so the chrome rules prefer
 * precision: a TEXT repeat is chrome only when BOTH position AND text content
 * repeat across pages (body lines can share positions across a uniform grid,
 * but never share content); VECTOR/IMAGE repeats need position+size only.
 * Thresholds are calibrated from the Stage-1 corpus probe (see
 * IR_STAGE1_FINDINGS.md).
 * </p>
 */
public final class FlowClassifier {

    private static final Logger LOG = Logger.getLogger(FlowClassifier.class.getName());

    /** Tunable thresholds (defaults = probe-calibrated values). */
    public static final class Config {
        /** A repeat group must span at least this many distinct pages. */
        public int minRepeatPages = 3;
        /** Position/size rounding tolerance for repeat signatures, pt. */
        public double posTolerance = 2.0;
        /** Height of the top/bottom margin bands where page numbers live, pt. */
        public double marginBand = 72.0;
        /** Watermark: box must cover at least this fraction of both page dims. */
        public double watermarkCover = 0.5;
        /** Caption search: max vertical gap between image and caption text, pt. */
        public double captionGap = 15.0;
        /** Caption must horizontally overlap the image by this fraction. */
        public double captionOverlap = 0.5;
        /** A caption may be at most this many times wider than its image; a text
         *  line far wider (a body paragraph spanning a whole row of small icons)
         *  is not a caption. */
        public double captionMaxWidthFactor = 3.0;
        /** Minimum member count for an ATOMIC vector cluster. */
        public int atomicClusterSize = 5;
    }

    /** Page-number text: digits, roman numerals, "Page N", "N of M", "- N -". */
    private static final Pattern PAGE_NUMBER = Pattern.compile(
            "^\\s*[-–—]?\\s*(page\\s+)?([0-9]{1,4}|[ivxlcdm]{1,5})(\\s*(of|/)\\s*[0-9]{1,4})?\\s*[-–—]?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private FlowClassifier() {
    }

    /**
     * Classifies every box of the model with default thresholds.
     *
     * @param pgm the model (boxes are mutated in place)
     */
    public static void classify(PgmModel pgm) {
        classify(pgm, new Config());
    }

    /**
     * Classifies every box of the model.
     *
     * @param pgm    the model (boxes are mutated in place)
     * @param config the thresholds
     */
    public static void classify(PgmModel pgm, Config config) {
        markFixedRepeats(pgm, config);
        markFixedPageNumbers(pgm, config);
        markFixedWatermarks(pgm, config);
        markAnchored(pgm, config);
        markAtomic(pgm, config);
        // Everything untouched keeps the FLOW default set at construction.
    }

    // ------------------------------------------------------------- FIXED rules

    /** Signature: kind + rounded rect (+ text content for TEXT boxes). */
    private static String signature(PgmBox b, double tol) {
        PgmRect r = b.getRect();
        String pos = String.format(Locale.ROOT, "%s|%.0f,%.0f,%.0f,%.0f", b.getKind(),
                Math.round(r.getX() / tol) * tol, Math.round(r.getY() / tol) * tol,
                Math.round(r.getW() / tol) * tol, Math.round(r.getH() / tol) * tol);
        if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData) {
            // Body lines can share a position grid across pages, but not content.
            pos += "|" + ((TextBoxData) b.getData()).getText();
        }
        return pos;
    }

    private static void markFixedRepeats(PgmModel pgm, Config config) {
        if (pgm.getPages().size() < config.minRepeatPages) {
            return;
        }
        Map<String, List<PgmBox>> groups = new HashMap<>();
        Map<String, Set<Integer>> groupPages = new HashMap<>();
        for (PgmPage page : pgm.getPages()) {
            for (PgmBox b : page.getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT || b.getKind() == PgmBoxKind.VECTOR
                        || b.getKind() == PgmBoxKind.IMAGE) {
                    String sig = signature(b, config.posTolerance);
                    groups.computeIfAbsent(sig, k -> new ArrayList<>()).add(b);
                    groupPages.computeIfAbsent(sig, k -> new HashSet<>()).add(b.getPage());
                }
            }
        }
        int fixed = 0;
        for (Map.Entry<String, List<PgmBox>> e : groups.entrySet()) {
            if (groupPages.get(e.getKey()).size() >= config.minRepeatPages) {
                for (PgmBox b : e.getValue()) {
                    b.setFlowClass(FlowClass.FIXED);
                    fixed++;
                }
            }
        }
        final int total = fixed;
        LOG.fine(() -> "repeat chrome: " + total + " boxes fixed");
    }

    private static void markFixedPageNumbers(PgmModel pgm, Config config) {
        if (pgm.getPages().size() < 2) {
            return; // a single page cannot have running page numbers
        }
        for (PgmPage page : pgm.getPages()) {
            for (PgmBox b : page.getBoxes()) {
                if (b.getKind() != PgmBoxKind.TEXT || !(b.getData() instanceof TextBoxData)) {
                    continue;
                }
                PgmRect r = b.getRect();
                boolean inBand = r.getY() >= page.getHeight() - config.marginBand
                        || r.getTop() <= config.marginBand;
                if (inBand && PAGE_NUMBER.matcher(((TextBoxData) b.getData()).getText()).matches()) {
                    b.setFlowClass(FlowClass.FIXED);
                }
            }
        }
    }

    private static void markFixedWatermarks(PgmModel pgm, Config config) {
        for (PgmPage page : pgm.getPages()) {
            for (PgmBox b : page.getBoxes()) {
                if (b.getKind() != PgmBoxKind.IMAGE && b.getKind() != PgmBoxKind.VECTOR) {
                    continue;
                }
                PgmRect r = b.getRect();
                boolean big = r.getW() >= config.watermarkCover * page.getWidth()
                        && r.getH() >= config.watermarkCover * page.getHeight();
                double cx = r.getX() + r.getW() / 2;
                double cy = r.getY() + r.getH() / 2;
                boolean centered = Math.abs(cx - page.getWidth() / 2) <= page.getWidth() * 0.2
                        && Math.abs(cy - page.getHeight() / 2) <= page.getHeight() * 0.2;
                if (big && centered) {
                    b.setFlowClass(FlowClass.FIXED);
                }
            }
        }
    }

    // ---------------------------------------------------------- ANCHORED rules

    private static void markAnchored(PgmModel pgm, Config config) {
        for (PgmPage page : pgm.getPages()) {
            // Text-markup annotations anchor to the text their QuadPoints cover.
            for (PgmBox b : page.getBoxes()) {
                if ((b.getKind() != PgmBoxKind.ANNOTATION)
                        || !(b.getData() instanceof AnnotBoxData)) {
                    continue;
                }
                AnnotBoxData d = (AnnotBoxData) b.getData();
                if (d.getQuadPoints() == null || d.getQuadPoints().length < 8) {
                    continue;
                }
                PgmRect quadRect = quadBBox(d.getQuadPoints());
                PgmBox best = null;
                double bestArea = 0;
                for (PgmBox t : page.getBoxes()) {
                    if (t.getKind() != PgmBoxKind.TEXT) {
                        continue;
                    }
                    double area = intersectionArea(quadRect, t.getRect());
                    if (area > bestArea) {
                        bestArea = area;
                        best = t;
                    }
                }
                if (best != null) {
                    b.setAnchorTargetId(best.getId());
                }
            }
            // Captions anchor to their image by proximity. A caption belongs to
            // ONE image and sits roughly over/under it, comparable in width. A
            // text line that qualifies for SEVERAL images (a body paragraph
            // spanning a whole row of GHS pictograms) — or one far wider than the
            // image — is body text, not a caption: anchoring it wrongly turns a
            // pictogram into a captioned block that breaks out of the row and
            // (under flow compaction) strands the line. So gather candidates per
            // TEXT line and anchor only when it matches exactly one image.
            // Candidate caption targets, with row-membership computed ONCE per
            // image. Recomputing imageInRow per (text, image) pair is cubic in
            // the box count — a shredded scan (thousands of image tiles, seen
            // via Form-XObject recursion) turned classification into a
            // multi-hour spin. A page with hundreds of images is such a mosaic,
            // not a set of captioned figures — skip caption anchoring outright.
            java.util.List<PgmBox> captionTargets = new java.util.ArrayList<>();
            int pageImages = 0;
            for (PgmBox img : page.getBoxes()) {
                if (img.getKind() == PgmBoxKind.IMAGE) {
                    pageImages++;
                }
            }
            if (pageImages <= 400) {
                for (PgmBox img : page.getBoxes()) {
                    if (img.getKind() != PgmBoxKind.IMAGE) {
                        continue;
                    }
                    // Skip page-spanning images (watermark/background) and chrome:
                    // body text merely abutting such a box edge must stay FLOW.
                    PgmRect ir = img.getRect();
                    if (img.getFlowClass() == FlowClass.FIXED
                            || ir.getH() >= config.watermarkCover * page.getHeight()
                            || ir.getW() >= config.watermarkCover * page.getWidth()) {
                        continue;
                    }
                    // An image that sits in a horizontal ROW with sibling images
                    // (e.g. a strip of GHS hazard pictograms) is a group member,
                    // not an individually captioned figure — the text above/below
                    // labels the whole strip. Captioning one breaks it out of the
                    // inline row. Skip such images as caption targets.
                    if (imageInRow(img, page)) {
                        continue;
                    }
                    captionTargets.add(img);
                }
            }
            for (PgmBox t : page.getBoxes()) {
                if (t.getKind() != PgmBoxKind.TEXT || t.getFlowClass() != FlowClass.FLOW
                        || t.getRect().getH() > 30) {
                    continue;
                }
                PgmRect tr = t.getRect();
                PgmBox match = null;
                int candidates = 0;
                for (PgmBox img : captionTargets) {
                    PgmRect ir = img.getRect();
                    // A caption may touch or slightly overlap the image edge
                    // (text rects include the ascent) — tolerate 2pt overlap.
                    double gapBelow = ir.getY() - tr.getTop();
                    double gapAbove = tr.getY() - ir.getTop();
                    double gap = Math.min(gapBelow >= -2 ? Math.abs(gapBelow) : Double.MAX_VALUE,
                            gapAbove >= -2 ? Math.abs(gapAbove) : Double.MAX_VALUE);
                    double overlap = Math.min(ir.getRight(), tr.getRight())
                            - Math.max(ir.getX(), tr.getX());
                    boolean overlapping = overlap
                            >= config.captionOverlap * Math.min(ir.getW(), tr.getW());
                    boolean widthOk = tr.getW() <= config.captionMaxWidthFactor * ir.getW();
                    if (gap <= config.captionGap && overlapping && widthOk) {
                        candidates++;
                        match = img;
                    }
                }
                if (candidates == 1) {
                    t.setAnchorTargetId(match.getId());
                }
            }
        }
    }

    /**
     * True when another same-size image sits on the same visual row as {@code img}
     * (tops aligned within half the image height and horizontally adjacent within
     * ~2 image widths) — i.e. {@code img} is one cell of a horizontal image strip
     * such as a GHS pictogram row, not a standalone captioned figure.
     */
    private static boolean imageInRow(PgmBox img, PgmPage page) {
        PgmRect ir = img.getRect();
        double rowTol = Math.max(3.0, 0.5 * ir.getH());
        for (PgmBox other : page.getBoxes()) {
            if (other == img || other.getKind() != PgmBoxKind.IMAGE) {
                continue;
            }
            PgmRect orr = other.getRect();
            if (Math.abs(orr.getTop() - ir.getTop()) > rowTol) {
                continue; // different row
            }
            double centreGap = Math.abs((orr.getX() + orr.getW() / 2) - (ir.getX() + ir.getW() / 2));
            if (centreGap <= 2.0 * Math.max(ir.getW(), orr.getW())) {
                return true; // an adjacent sibling on the same row
            }
        }
        return false;
    }

    private static PgmRect quadBBox(double[] q) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int i = 0; i + 1 < q.length; i += 2) {
            minX = Math.min(minX, q[i]);
            maxX = Math.max(maxX, q[i]);
            minY = Math.min(minY, q[i + 1]);
            maxY = Math.max(maxY, q[i + 1]);
        }
        return PgmRect.fromCorners(minX, minY, maxX, maxY);
    }

    private static double intersectionArea(PgmRect a, PgmRect b) {
        double w = Math.min(a.getRight(), b.getRight()) - Math.max(a.getX(), b.getX());
        double h = Math.min(a.getTop(), b.getTop()) - Math.max(a.getY(), b.getY());
        return w > 0 && h > 0 ? w * h : 0;
    }

    // ------------------------------------------------------------ ATOMIC rules

    private static void markAtomic(PgmModel pgm, Config config) {
        for (PgmPage page : pgm.getPages()) {
            // Form XObjects were not entered — only whole-box moves are safe.
            List<PgmBox> vectors = new ArrayList<>();
            for (PgmBox b : page.getBoxes()) {
                if (b.getKind() == PgmBoxKind.UNKNOWN) {
                    b.setFlowClass(FlowClass.ATOMIC);
                } else if (b.getKind() == PgmBoxKind.VECTOR
                        && b.getFlowClass() == FlowClass.FLOW) {
                    vectors.add(b);
                }
            }
            // Union-find clustering of overlapping/adjacent vector boxes: a
            // large cluster is one drawing — move only as a whole.
            // Sort-sweep by left edge instead of all-pairs: chart pages carry
            // tens of thousands of path fragments, and the naive O(n²)
            // intersection loop burned 20+ minutes on one corpus document
            // (40440). Sorted by X, a pair can only intersect while the later
            // box starts before the earlier one ends — identical clusters,
            // near-linear time on real pages.
            int n = vectors.size();
            vectors.sort(java.util.Comparator.comparingDouble(b -> b.getRect().getX()));
            PgmRect[] exp = new PgmRect[n];
            for (int i = 0; i < n; i++) {
                exp[i] = expanded(vectors.get(i).getRect());
            }
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    if (exp[j].getX() > exp[i].getRight()) {
                        break;
                    }
                    if (exp[i].intersects(exp[j])) {
                        union(parent, i, j);
                    }
                }
            }
            Map<Integer, List<PgmBox>> clusters = new HashMap<>();
            for (int i = 0; i < n; i++) {
                clusters.computeIfAbsent(find(parent, i), k -> new ArrayList<>())
                        .add(vectors.get(i));
            }
            for (List<PgmBox> cluster : clusters.values()) {
                if (cluster.size() >= config.atomicClusterSize) {
                    for (PgmBox b : cluster) {
                        b.setFlowClass(FlowClass.ATOMIC);
                    }
                }
            }
        }
    }

    private static PgmRect expanded(PgmRect r) {
        return new PgmRect(r.getX() - 2, r.getY() - 2, r.getW() + 4, r.getH() + 4);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        parent[find(parent, a)] = find(parent, b);
    }
}
