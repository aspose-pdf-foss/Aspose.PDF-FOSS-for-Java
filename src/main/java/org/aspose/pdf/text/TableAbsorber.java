package org.aspose.pdf.text;

import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.parser.ContentStreamParser;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import java.util.IdentityHashMap;
import java.util.Set;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * Extracts table structures from PDF pages by analyzing text positions
 * and ruling lines to identify rows, columns, and cells.
 * <p>
 * The algorithm clusters text fragments by Y-coordinate to detect rows,
 * then by X-coordinate within each row to detect columns/cells.
 * A group of consecutive rows with a consistent column structure is
 * recognized as a table.
 * </p>
 */
public class TableAbsorber {

    private static final Logger LOG = Logger.getLogger(TableAbsorber.class.getName());

    /** Tolerance in points for same-row Y-coordinate detection. */
    private static final double ROW_TOLERANCE = 3.0;

    /** Minimum number of columns required to consider a group of rows as a table. */
    private static final int MIN_COLUMNS = 2;

    /** Minimum number of rows required to consider a group as a table. */
    private static final int MIN_ROWS = 2;

    private final List<AbsorbedTable> tables = new ArrayList<>();

    /**
     * Page rotation transform (default→rotated/visual space) for the page
     * currently being visited. On a {@code /Rotate}d page the rows a reader
     * sees run along a swapped axis, so the heuristic clusters and the cell
     * rectangles it emits must be computed in this visual space — matching
     * Aspose, whose {@code AbsorbedCell.Rectangle} is in rotated page space
     * (callers un-rotate it via {@code Page.getRotationMatrix().reverse()}).
     * Identity for unrotated pages, so their behaviour is unchanged.
     */
    private org.aspose.pdf.Matrix pageRotation = org.aspose.pdf.Matrix.IDENTITY;

    /**
     * Normalised page rotation in degrees (0/90/180/270). {@link
     * #getRotationMatrix() pageRotation} space and the reader's on-screen frame
     * differ by a both-axes flip for the quarter-turn rotations (90/270), so
     * row/column reading order is reversed for those — see {@link
     * #buildRuledTable}.
     */
    private int pageRotateDeg = 0;

    /** {@code true} when reading order runs opposite to pageRotation-space axes. */
    private boolean readingOrderFlipped() {
        return pageRotateDeg == 90 || pageRotateDeg == 270;
    }

    /** Visual (rotation-applied) coordinates of a fragment's position point. */
    private double[] visualPos(TextFragment f) {
        return pageRotation.transformPoint(
                f.getPosition().getXIndent(), f.getPosition().getYIndent());
    }

    /** Visual (rotation-applied) bounding rectangle of a fragment. */
    private org.aspose.pdf.Rectangle visualRect(TextFragment f) {
        return f.getRectangle() == null ? null : pageRotation.transform(f.getRectangle());
    }

    /**
     * A fragment's anchor in the reader's on-screen frame ({@code +x} right,
     * {@code +y} up after the page {@code /Rotate} is applied). Used only to
     * order fragments within a cell in natural reading order (top→bottom,
     * left→right), independent of the pageRotation-space geometry.
     */
    private double[] readerAnchor(TextFragment f) {
        Rectangle r = f.getRectangle();
        double x;
        double y;
        if (r != null && r.getWidth() > 0) {
            x = (r.getLLX() + r.getURX()) / 2;
            y = (r.getLLY() + r.getURY()) / 2;
        } else if (f.getPosition() != null) {
            x = f.getPosition().getXIndent();
            y = f.getPosition().getYIndent();
        } else {
            return new double[]{0, 0};
        }
        switch (pageRotateDeg) {
            case 90:  return new double[]{y, -x};
            case 180: return new double[]{-x, -y};
            case 270: return new double[]{-y, x};
            default:  return new double[]{x, y};
        }
    }

    /** Reading-order comparator: top→bottom (desc reader Y), then left→right. */
    private Comparator<TextFragment> readingOrder() {
        return Comparator
                .comparingDouble((TextFragment f) -> -readerAnchor(f)[1])
                .thenComparingDouble(f -> readerAnchor(f)[0]);
    }

    /**
     * Visits a page and extracts table structures.
     *
     * @param page the page to analyze
     * @throws IOException if text extraction fails
     */
    public void visit(Page page) throws IOException {
        tables.clear();
        pageRotation = page.getRotationMatrix();
        pageRotateDeg = ((page.getRotate() % 360) + 360) % 360;

        // 1. Extract all text fragments using TextFragmentAbsorber
        TextFragmentAbsorber tfa = new TextFragmentAbsorber();
        tfa.visit(page);
        TextFragmentCollection fragments = tfa.getTextFragments();
        if (fragments == null || fragments.size() == 0) {
            return;
        }

        // 2. Collect fragments with valid positions into a sortable list
        List<TextFragment> positioned = new ArrayList<>();
        for (TextFragment f : fragments) {
            if (f.getPosition() != null) {
                positioned.add(f);
            }
        }
        if (positioned.isEmpty()) {
            return;
        }

        // Sort by visual Y descending (top to bottom), then visual X ascending
        // (left to right). On a rotated page these axes are swapped relative to
        // the raw content coordinates — visualPos() applies the page rotation.
        positioned.sort(Comparator
                .comparingDouble((TextFragment f) -> -visualPos(f)[1])
                .thenComparingDouble(f -> visualPos(f)[0]));

        // 3. Primary detection: ruling-line grids (PDFNEWNET-39178). Tables
        // drawn with explicit border/grid strokes are segmented exactly from
        // the geometry — the count and per-cell content then match Aspose.
        List<AbsorbedTable> ruled = detectRuledTables(page, positioned);
        if (!ruled.isEmpty()) {
            tables.addAll(ruled);
            LOG.fine(() -> "TableAbsorber found " + tables.size() + " ruled table(s)");
            return;
        }

        // 4. Fallback: text-position heuristic for tables without rulings.
        List<List<TextFragment>> rows = groupByY(positioned);
        List<List<List<TextFragment>>> tableGroups = detectTableGroups(rows);
        for (List<List<TextFragment>> group : tableGroups) {
            AbsorbedTable table = buildTable(group);
            if (table != null) {
                tables.add(table);
            }
        }

        LOG.fine(() -> "TableAbsorber found " + tables.size() + " table(s)");
    }

    // ================= Ruled-grid detection =================

    /** Segment endpoint / level-merge tolerance in points. */
    private static final double RULE_TOLERANCE = 3.0;

    /** Two grid levels closer than this merge into one rule (points). */
    private static final double LEVEL_MERGE = 2.0;

    /** Axis-alignment tolerance: max cross-axis drift for a rule (points). */
    private static final double AXIS_DRIFT = 0.7;

    /** Safety cap — pages with more rule segments fall back to the heuristic. */
    private static final int MAX_RULE_SEGMENTS = 5000;

    /**
     * Detects tables from ruling lines: collects the stroked/filled
     * axis-aligned path segments of the page (CTM applied), clusters
     * touching segments into connected grid regions, and slices each region
     * into rows/columns at the distinct horizontal/vertical rule levels.
     * Text fragments are assigned to cells by their anchor point.
     *
     * @return the ruled tables in top-to-bottom page order; empty when the
     *         page has no usable rulings
     */
    private List<AbsorbedTable> detectRuledTables(Page page, List<TextFragment> fragments) {
        List<double[]> segments;
        try {
            segments = collectRuleSegments(page);
        } catch (IOException e) {
            LOG.fine(() -> "Rule collection failed, falling back to heuristic: " + e.getMessage());
            return Collections.emptyList();
        }
        if (segments.isEmpty() || segments.size() > MAX_RULE_SEGMENTS) {
            return Collections.emptyList();
        }

        List<List<double[]>> clusters = clusterSegments(segments);
        List<AbsorbedTable> result = new ArrayList<>();
        for (List<double[]> cluster : clusters) {
            AbsorbedTable table = buildRuledTable(cluster, fragments);
            if (table != null) {
                table.setBorderColorRgb(pageRuleColorRgb);
                result.add(table);
            }
        }
        // Top-to-bottom page order, matching visual reading order.
        result.sort(Comparator.comparingDouble(
                (AbsorbedTable t) -> -(t.getRectangle() != null ? t.getRectangle().getURY() : 0)));
        return result;
    }

    /**
     * Walks the page content stream tracking q/Q/cm and collects the
     * axis-aligned segments of every painted path ({@code m/l} polylines and
     * {@code re} rectangle edges). Curves and clipping-only paths are
     * ignored. Each segment is {x1, y1, x2, y2} in device space.
     */
    /** Rule-colour histogram (packed 0xRRGGBB -&gt; painted segment count) for the page. */
    private java.util.Map<Integer, Integer> ruleColorCounts;
    /** Dominant ruling-line colour of the last page, or -1 when unknown. */
    private int pageRuleColorRgb = -1;

    /**
     * Collects the page's painted axis-aligned rule segments (recursing into
     * Form XObjects on {@code Do}), each as {@code {x1, y1, x2, y2}} in page
     * user space. Internal engine hook for the SDM enrichment pipeline
     * (standalone-rule detection); not part of the public Aspose surface.
     *
     * @param page the page to scan; must not be null
     * @return the painted segments; empty when the page has none
     * @throws IOException if the content stream cannot be parsed
     */
    public List<double[]> collectPageRuleSegments(Page page) throws IOException {
        return collectRuleSegments(page);
    }

    private List<double[]> collectRuleSegments(Page page) throws IOException {
        List<double[]> segments = new ArrayList<>();
        ruleColorCounts = new java.util.HashMap<>();
        Set<PdfStream> activeForms = Collections.newSetFromMap(new IdentityHashMap<>());
        collectFromOps(page.getContents(), new double[]{1, 0, 0, 1, 0, 0},
                page.getResources(), segments, activeForms, 0, 0x000000, 0x000000);
        pageRuleColorRgb = -1;
        int best = -1;
        for (java.util.Map.Entry<Integer, Integer> e : ruleColorCounts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                pageRuleColorRgb = e.getKey();
            }
        }
        return segments;
    }

    /** Max Form XObject nesting for rule collection (guards pathological files). */
    private static final int MAX_FORM_DEPTH = 12;

    /**
     * Collects axis-aligned path segments from one content stream, recursing into
     * Form XObjects on {@code Do} so tables drawn inside a form (common in tagged
     * PDFs where the whole page is one XObject) are seen. {@code baseCtm} is the CTM
     * in effect where this stream is invoked.
     */
    private void collectFromOps(OperatorCollection ops, double[] baseCtm, Resources resources,
                                List<double[]> segments, Set<PdfStream> activeForms, int depth,
                                int baseStroke, int baseFill) {
        if (ops == null || segments.size() > MAX_RULE_SEGMENTS) {
            return;
        }
        List<double[]> path = new ArrayList<>();
        double[] ctm = baseCtm.clone();
        Deque<double[]> gsStack = new ArrayDeque<>();
        double curX = 0;
        double curY = 0;
        double startX = 0;
        double startY = 0;
        int strokeColor = baseStroke;   // inherit the colour at the Do point
        int fillColor = baseFill;

        for (int i = 0; i < ops.size(); i++) {
            if (segments.size() > MAX_RULE_SEGMENTS) {
                return;
            }
            Operator op = ops.getAt(i);
            String name = op.getName();
            List<PdfBase> operands = op.getOperands();
            if ("Do".equals(name) && depth < MAX_FORM_DEPTH && operands != null && !operands.isEmpty()) {
                collectFromForm(operands.get(0), ctm, resources, segments, activeForms, depth,
                        strokeColor, fillColor);
                continue;
            }
            switch (name) {
                case "q":
                    gsStack.push(ctm.clone());
                    break;
                case "Q":
                    if (!gsStack.isEmpty()) {
                        ctm = gsStack.pop();
                    }
                    break;
                case "cm": {
                    if (operands == null || operands.size() < 6) break;
                    double[] m = new double[6];
                    for (int k = 0; k < 6; k++) {
                        m[k] = toDouble(operands.get(k));
                    }
                    ctm = multiply(m, ctm);
                    break;
                }
                case "m":
                    if (operands == null || operands.size() < 2) break;
                    curX = toDouble(operands.get(0));
                    curY = toDouble(operands.get(1));
                    startX = curX;
                    startY = curY;
                    break;
                case "l": {
                    if (operands == null || operands.size() < 2) break;
                    double nx = toDouble(operands.get(0));
                    double ny = toDouble(operands.get(1));
                    addSegment(path, ctm, curX, curY, nx, ny);
                    curX = nx;
                    curY = ny;
                    break;
                }
                case "h":
                    addSegment(path, ctm, curX, curY, startX, startY);
                    curX = startX;
                    curY = startY;
                    break;
                case "re": {
                    if (operands == null || operands.size() < 4) break;
                    double x = toDouble(operands.get(0));
                    double y = toDouble(operands.get(1));
                    double w = toDouble(operands.get(2));
                    double h = toDouble(operands.get(3));
                    addSegment(path, ctm, x, y, x + w, y);
                    addSegment(path, ctm, x, y + h, x + w, y + h);
                    addSegment(path, ctm, x, y, x, y + h);
                    addSegment(path, ctm, x + w, y, x + w, y + h);
                    curX = x;
                    curY = y;
                    startX = x;
                    startY = y;
                    break;
                }
                case "RG":
                    strokeColor = rgbOp(operands);
                    break;
                case "rg":
                    fillColor = rgbOp(operands);
                    break;
                case "G":
                    strokeColor = grayOp(operands);
                    break;
                case "g":
                    fillColor = grayOp(operands);
                    break;
                case "K":
                    strokeColor = cmykOp(operands);
                    break;
                case "k":
                    fillColor = cmykOp(operands);
                    break;
                case "SC": case "SCN": {           // colour in current space (stroke)
                    int c = scnColor(operands);
                    if (c >= 0) {
                        strokeColor = c;
                    }
                    break;
                }
                case "sc": case "scn": {           // colour in current space (fill)
                    int c = scnColor(operands);
                    if (c >= 0) {
                        fillColor = c;
                    }
                    break;
                }
                case "S": case "s":                       // stroked → stroke colour
                    recordRuleColor(strokeColor, path.size());
                    segments.addAll(path);
                    path.clear();
                    break;
                case "f": case "F": case "f*":            // filled rule → fill colour
                    recordRuleColor(fillColor, path.size());
                    segments.addAll(path);
                    path.clear();
                    break;
                case "B": case "B*": case "b": case "b*": // stroke wins for a border
                    recordRuleColor(strokeColor, path.size());
                    segments.addAll(path);
                    path.clear();
                    break;
                case "n":
                    // Clipping-only path — not painted, not a rule.
                    path.clear();
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Resolves a Form XObject by name from {@code resources}, applies its
     * {@code /Matrix} to {@code ctmAtDo}, and recurses into its content stream.
     * Cycles and non-Form XObjects are skipped.
     */
    private void collectFromForm(PdfBase nameOperand, double[] ctmAtDo, Resources resources,
                                 List<double[]> segments, Set<PdfStream> activeForms, int depth,
                                 int strokeAtDo, int fillAtDo) {
        try {
            if (resources == null) {
                return;
            }
            String xobjName = nameOperand.toString();
            if (xobjName.startsWith("/")) {
                xobjName = xobjName.substring(1);
            }
            PdfDictionary resDict = resources.getPdfDictionary();
            if (resDict == null) {
                return;
            }
            PdfBase xobjBase = deref(resDict.get("XObject"));
            if (!(xobjBase instanceof PdfDictionary)) {
                return;
            }
            PdfBase formBase = deref(((PdfDictionary) xobjBase).get(xobjName));
            if (!(formBase instanceof PdfStream)) {
                return;
            }
            PdfStream form = (PdfStream) formBase;
            if (!"Form".equals(form.getNameAsString("Subtype")) || !activeForms.add(form)) {
                return;
            }
            try {
                double[] ctm = ctmAtDo;
                PdfBase matrixBase = deref(form.get("Matrix"));
                if (matrixBase instanceof org.aspose.pdf.engine.pdfobjects.PdfArray) {
                    org.aspose.pdf.engine.pdfobjects.PdfArray ma =
                            (org.aspose.pdf.engine.pdfobjects.PdfArray) matrixBase;
                    if (ma.size() >= 6) {
                        double[] m = new double[6];
                        for (int k = 0; k < 6; k++) {
                            m[k] = toDouble(deref(ma.get(k)));
                        }
                        ctm = multiply(m, ctmAtDo);
                    }
                }
                Resources formRes = resources;
                PdfBase frb = deref(form.get("Resources"));
                if (frb instanceof PdfDictionary) {
                    formRes = new Resources((PdfDictionary) frb);
                }
                byte[] data = form.getDecodedData();
                if (data == null || data.length == 0) {
                    return;
                }
                OperatorCollection formOps = ContentStreamParser.parseToCollection(data);
                collectFromOps(formOps, ctm, formRes, segments, activeForms, depth + 1,
                        strokeAtDo, fillAtDo);
            } finally {
                activeForms.remove(form);
            }
        } catch (Exception e) {
            LOG.fine(() -> "rule collection skipped a Form XObject: " + e.getMessage());
        }
    }

    private static PdfBase deref(PdfBase b) throws IOException {
        return b instanceof PdfObjectReference ? ((PdfObjectReference) b).dereference() : b;
    }

    /** Adds the (transformed) segment to {@code path} if it is axis-aligned. */
    private static void addSegment(List<double[]> path, double[] ctm,
                                   double x1, double y1, double x2, double y2) {
        double dx1 = ctm[0] * x1 + ctm[2] * y1 + ctm[4];
        double dy1 = ctm[1] * x1 + ctm[3] * y1 + ctm[5];
        double dx2 = ctm[0] * x2 + ctm[2] * y2 + ctm[4];
        double dy2 = ctm[1] * x2 + ctm[3] * y2 + ctm[5];
        boolean horizontal = Math.abs(dy1 - dy2) <= AXIS_DRIFT;
        boolean vertical = Math.abs(dx1 - dx2) <= AXIS_DRIFT;
        if (!horizontal && !vertical) {
            return;
        }
        if (horizontal && vertical) {
            return;     // degenerate point
        }
        path.add(new double[]{Math.min(dx1, dx2), Math.min(dy1, dy2),
                Math.max(dx1, dx2), Math.max(dy1, dy2)});
    }

    /** 3x2 PDF matrix multiplication: result = m × ctm. */
    private static double[] multiply(double[] m, double[] ctm) {
        return new double[]{
                m[0] * ctm[0] + m[1] * ctm[2],
                m[0] * ctm[1] + m[1] * ctm[3],
                m[2] * ctm[0] + m[3] * ctm[2],
                m[2] * ctm[1] + m[3] * ctm[3],
                m[4] * ctm[0] + m[5] * ctm[2] + ctm[4],
                m[4] * ctm[1] + m[5] * ctm[3] + ctm[5]
        };
    }

    /** Accumulates {@code n} painted rule segments under the given colour. */
    private void recordRuleColor(int rgb, int n) {
        if (n > 0 && ruleColorCounts != null) {
            ruleColorCounts.merge(rgb, n, Integer::sum);
        }
    }

    private static int clamp255(double v) {
        int i = (int) Math.round(v * 255);
        return Math.max(0, Math.min(255, i));
    }

    private static int rgbOp(List<PdfBase> ops) {
        if (ops == null || ops.size() < 3) {
            return 0x000000;
        }
        return (clamp255(toDouble(ops.get(0))) << 16)
                | (clamp255(toDouble(ops.get(1))) << 8) | clamp255(toDouble(ops.get(2)));
    }

    private static int grayOp(List<PdfBase> ops) {
        if (ops == null || ops.isEmpty()) {
            return 0x000000;
        }
        int g = clamp255(toDouble(ops.get(0)));
        return (g << 16) | (g << 8) | g;
    }

    /**
     * Colour from an {@code sc/scn} operator by arity: 1 numeric = gray, 3 = RGB,
     * 4 = CMYK. Returns -1 when the operands are not all numeric (e.g. a trailing
     * pattern name) — the colour is then left unchanged.
     */
    private static int scnColor(List<PdfBase> ops) {
        if (ops == null || ops.isEmpty()) {
            return -1;
        }
        for (PdfBase o : ops) {
            if (!(o instanceof PdfInteger) && !(o instanceof PdfFloat)) {
                return -1;
            }
        }
        switch (ops.size()) {
            case 1: return grayOp(ops);
            case 3: return rgbOp(ops);
            case 4: return cmykOp(ops);
            default: return -1;
        }
    }

    private static int cmykOp(List<PdfBase> ops) {
        if (ops == null || ops.size() < 4) {
            return 0x000000;
        }
        double c = toDouble(ops.get(0));
        double m = toDouble(ops.get(1));
        double y = toDouble(ops.get(2));
        double k = toDouble(ops.get(3));
        return (clamp255((1 - c) * (1 - k)) << 16)
                | (clamp255((1 - m) * (1 - k)) << 8) | clamp255((1 - y) * (1 - k));
    }

    private static double toDouble(PdfBase value) {
        if (value instanceof PdfInteger) {
            return ((PdfInteger) value).intValue();
        }
        if (value instanceof PdfFloat) {
            return ((PdfFloat) value).doubleValue();
        }
        return 0;
    }

    /** Groups segments whose bounding boxes touch (within tolerance) into clusters. */
    private static List<List<double[]>> clusterSegments(List<double[]> segments) {
        int n = segments.size();
        int[] component = new int[n];
        java.util.Arrays.fill(component, -1);
        int clusterCount = 0;
        for (int i = 0; i < n; i++) {
            if (component[i] >= 0) {
                continue;
            }
            Deque<Integer> queue = new ArrayDeque<>();
            queue.add(i);
            component[i] = clusterCount;
            while (!queue.isEmpty()) {
                int u = queue.poll();
                double[] a = segments.get(u);
                for (int v = 0; v < n; v++) {
                    if (component[v] >= 0) {
                        continue;
                    }
                    double[] b = segments.get(v);
                    if (a[0] - RULE_TOLERANCE <= b[2] && a[2] + RULE_TOLERANCE >= b[0]
                            && a[1] - RULE_TOLERANCE <= b[3] && a[3] + RULE_TOLERANCE >= b[1]) {
                        component[v] = clusterCount;
                        queue.add(v);
                    }
                }
            }
            clusterCount++;
        }
        List<List<double[]>> clusters = new ArrayList<>(clusterCount);
        for (int c = 0; c < clusterCount; c++) {
            clusters.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            clusters.get(component[i]).add(segments.get(i));
        }
        return clusters;
    }

    /**
     * Slices one rule cluster into a row/column grid and fills the cells
     * with the text fragments whose anchor falls inside. Returns {@code null}
     * when the cluster has fewer than two horizontal or vertical rule levels
     * (a lone underline is not a table).
     */
    private AbsorbedTable buildRuledTable(List<double[]> cluster, List<TextFragment> rawFragments) {
        // Fill cells in reading order so each cell's TextFragments[1] is the
        // first line the reader sees (matches Aspose's cell fragment order).
        List<TextFragment> fragments = new ArrayList<>(rawFragments);
        fragments.sort(readingOrder());
        List<Double> hLevels = new ArrayList<>();
        List<Double> vLevels = new ArrayList<>();
        for (double[] raw : cluster) {
            // Work in visual (rotated) page space: on a /Rotate 90|270 page a
            // content-horizontal rule is visually vertical (and vice versa), so
            // the row/column axes must be assigned after rotation — otherwise
            // the grid comes out transposed (PDFNEWNET_36802_1: 3x27 vs 27x3).
            double[] p0 = pageRotation.transformPoint(raw[0], raw[1]);
            double[] p1 = pageRotation.transformPoint(raw[2], raw[3]);
            double[] s = {p0[0], p0[1], p1[0], p1[1]};
            if (Math.abs(s[1] - s[3]) <= AXIS_DRIFT) {
                addLevel(hLevels, (s[1] + s[3]) / 2);
            } else if (Math.abs(s[0] - s[2]) <= AXIS_DRIFT) {
                addLevel(vLevels, (s[0] + s[2]) / 2);
            }
        }
        if (hLevels.size() < 2 || vLevels.size() < 2) {
            return null;
        }
        // Reader-order row/column iteration. In pageRotation space rows run
        // top→bottom (descending Y) and columns left→right (ascending X); for a
        // 90/270 quarter-turn the reader's frame is flipped in both axes, so
        // both orderings reverse to keep row 0 / column 0 at the reader's
        // top-left (PDFNEWNET_36802_1).
        if (readingOrderFlipped()) {
            hLevels.sort(Comparator.naturalOrder());   // reader top → bottom
            vLevels.sort(Comparator.reverseOrder());   // reader left → right
        } else {
            hLevels.sort(Comparator.reverseOrder());   // top → bottom
            vLevels.sort(Comparator.naturalOrder());   // left → right
        }

        // Classify the actual painted rule segments (in pageRotation space) into
        // vertical and horizontal, so a MERGED cell — a grid rectangle whose
        // internal dividing rule was never drawn — can be detected and emitted as
        // one spanning cell instead of being split into empty sub-cells.
        List<double[]> vSegs = new ArrayList<>(); // {x, yLo, yHi}
        List<double[]> hSegs = new ArrayList<>(); // {y, xLo, xHi}
        for (double[] raw : cluster) {
            double[] p0 = pageRotation.transformPoint(raw[0], raw[1]);
            double[] p1 = pageRotation.transformPoint(raw[2], raw[3]);
            if (Math.abs(p0[1] - p1[1]) <= AXIS_DRIFT) {
                hSegs.add(new double[]{(p0[1] + p1[1]) / 2, Math.min(p0[0], p1[0]), Math.max(p0[0], p1[0])});
            } else if (Math.abs(p0[0] - p1[0]) <= AXIS_DRIFT) {
                vSegs.add(new double[]{(p0[0] + p1[0]) / 2, Math.min(p0[1], p1[1]), Math.max(p0[1], p1[1])});
            }
        }

        int nR = hLevels.size() - 1;
        int nC = vLevels.size() - 1;
        boolean[][] occupied = new boolean[nR][nC];
        AbsorbedTable table = new AbsorbedTable();
        for (int r = 0; r < nR; r++) {
            AbsorbedRow row = new AbsorbedRow();
            for (int c = 0; c < nC; c++) {
                if (occupied[r][c]) {
                    continue; // part of a merged cell anchored above/left
                }
                double cellTop = Math.max(hLevels.get(r), hLevels.get(r + 1));
                double cellBottom = Math.min(hLevels.get(r), hLevels.get(r + 1));
                double cellLeft = Math.min(vLevels.get(c), vLevels.get(c + 1));
                double cellRight = Math.max(vLevels.get(c), vLevels.get(c + 1));
                // colSpan: extend right while the internal vertical boundary was
                // NOT drawn across this row band (a merged header cell).
                int colSpan = 1;
                while (c + colSpan < nC
                        && !hasRule(vSegs, vLevels.get(c + colSpan), cellBottom, cellTop)) {
                    colSpan++;
                }
                // rowSpan: extend down while the internal horizontal boundary was
                // NOT drawn across this column band (a merged category cell).
                int rowSpan = 1;
                while (r + rowSpan < nR
                        && !hasRule(hSegs, hLevels.get(r + rowSpan), cellLeft, cellRight)) {
                    rowSpan++;
                }
                double left = Math.min(vLevels.get(c), vLevels.get(c + colSpan));
                double right = Math.max(vLevels.get(c), vLevels.get(c + colSpan));
                double top = Math.max(hLevels.get(r), hLevels.get(r + rowSpan));
                double bottom = Math.min(hLevels.get(r), hLevels.get(r + rowSpan));
                AbsorbedCell cell = new AbsorbedCell();
                cell.setRectangle(new Rectangle(left, bottom, right, top));
                for (TextFragment f : fragments) {
                    double[] anchor = anchorOf(f);
                    if (anchor != null
                            && anchor[0] >= left - 1 && anchor[0] <= right + 1
                            && anchor[1] >= bottom - 1 && anchor[1] <= top + 1) {
                        cell.addTextFragment(f);
                    }
                }
                row.addCell(cell);
                for (int rr = r; rr < r + rowSpan; rr++) {
                    for (int cc = c; cc < c + colSpan; cc++) {
                        occupied[rr][cc] = true;
                    }
                }
            }
            table.addRow(row);
        }
        double tblMinX = Math.min(vLevels.get(0), vLevels.get(vLevels.size() - 1));
        double tblMaxX = Math.max(vLevels.get(0), vLevels.get(vLevels.size() - 1));
        double tblMinY = Math.min(hLevels.get(0), hLevels.get(hLevels.size() - 1));
        double tblMaxY = Math.max(hLevels.get(0), hLevels.get(hLevels.size() - 1));
        table.setRectangle(new Rectangle(tblMinX, tblMinY, tblMaxX, tblMaxY));
        return table;
    }

    /**
     * True when a painted rule segment lies at coordinate {@code level} (within
     * {@link #RULE_TOLERANCE}) and covers at least half of the band
     * {@code [lo, hi]}. Each segment is {@code {level, bandLo, bandHi}}. Used to
     * tell a real cell boundary from a merged cell's missing internal rule.
     */
    private static boolean hasRule(List<double[]> segs, double level, double lo, double hi) {
        double band = hi - lo;
        if (band <= 0) {
            return true; // degenerate band — treat as bounded
        }
        for (double[] s : segs) {
            if (Math.abs(s[0] - level) > RULE_TOLERANCE) {
                continue;
            }
            double overlap = Math.min(s[2], hi) - Math.max(s[1], lo);
            if (overlap >= 0.5 * band) {
                return true;
            }
        }
        return false;
    }

    /** Merges {@code value} into the level list within {@link #LEVEL_MERGE}. */
    private static void addLevel(List<Double> levels, double value) {
        for (int i = 0; i < levels.size(); i++) {
            if (Math.abs(levels.get(i) - value) <= LEVEL_MERGE) {
                return;
            }
        }
        levels.add(value);
    }

    /**
     * The point used to place a fragment into a cell: rect centre, else
     * position — expressed in visual (rotated) page space so it matches the
     * rotated grid levels built by {@link #buildRuledTable}.
     */
    private double[] anchorOf(TextFragment f) {
        Rectangle r = f.getRectangle();
        if (r != null && r.getWidth() > 0) {
            return pageRotation.transformPoint(
                    (r.getLLX() + r.getURX()) / 2, (r.getLLY() + r.getURY()) / 2);
        }
        Position p = f.getPosition();
        return p != null ? pageRotation.transformPoint(p.getXIndent(), p.getYIndent()) : null;
    }

    /**
     * Returns the list of tables detected on the last visited page.
     *
     * @return unmodifiable list of absorbed tables
     */
    public List<AbsorbedTable> getTableList() {
        return Collections.unmodifiableList(tables);
    }

    /**
     * Groups text fragments into rows based on Y-coordinate proximity.
     */
    private List<List<TextFragment>> groupByY(List<TextFragment> fragments) {
        List<List<TextFragment>> rows = new ArrayList<>();
        List<TextFragment> currentRow = new ArrayList<>();
        double currentY = Double.NaN;

        for (TextFragment f : fragments) {
            double y = visualPos(f)[1];
            if (Double.isNaN(currentY) || Math.abs(y - currentY) <= ROW_TOLERANCE) {
                currentRow.add(f);
                if (Double.isNaN(currentY)) {
                    currentY = y;
                }
            } else {
                if (!currentRow.isEmpty()) {
                    // Sort row by visual X coordinate
                    currentRow.sort(Comparator.comparingDouble(fr -> visualPos(fr)[0]));
                    rows.add(currentRow);
                }
                currentRow = new ArrayList<>();
                currentRow.add(f);
                currentY = y;
            }
        }
        if (!currentRow.isEmpty()) {
            currentRow.sort(Comparator.comparingDouble(fr -> visualPos(fr)[0]));
            rows.add(currentRow);
        }
        return rows;
    }

    /**
     * Detects groups of consecutive rows that form tables.
     * A table is a sequence of rows where each row has at least MIN_COLUMNS fragments.
     */
    private List<List<List<TextFragment>>> detectTableGroups(List<List<TextFragment>> rows) {
        List<List<List<TextFragment>>> groups = new ArrayList<>();
        List<List<TextFragment>> currentGroup = new ArrayList<>();

        for (List<TextFragment> row : rows) {
            if (row.size() >= MIN_COLUMNS) {
                currentGroup.add(row);
            } else {
                if (currentGroup.size() >= MIN_ROWS) {
                    groups.add(currentGroup);
                }
                currentGroup = new ArrayList<>();
            }
        }
        if (currentGroup.size() >= MIN_ROWS) {
            groups.add(currentGroup);
        }
        return groups;
    }

    /**
     * Builds an AbsorbedTable from a group of rows.
     */
    private AbsorbedTable buildTable(List<List<TextFragment>> rowFragments) {
        AbsorbedTable table = new AbsorbedTable();
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;

        for (List<TextFragment> rowFrags : rowFragments) {
            AbsorbedRow row = new AbsorbedRow();
            for (TextFragment f : rowFrags) {
                AbsorbedCell cell = new AbsorbedCell();
                cell.addTextFragment(f);
                // Emit the cell rectangle in visual (rotated) page space — Aspose
                // callers un-rotate it via Page.getRotationMatrix().reverse().
                Rectangle r = visualRect(f);
                if (r != null) {
                    cell.setRectangle(r);
                    minX = Math.min(minX, r.getLLX());
                    minY = Math.min(minY, r.getLLY());
                    maxX = Math.max(maxX, r.getURX());
                    maxY = Math.max(maxY, r.getURY());
                }
                row.addCell(cell);
            }
            table.addRow(row);
        }

        if (minX != Double.MAX_VALUE) {
            table.setRectangle(new Rectangle(minX, minY, maxX, maxY));
        }
        return table;
    }
}
