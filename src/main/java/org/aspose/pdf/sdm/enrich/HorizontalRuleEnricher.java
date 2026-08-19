package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmIds;
import org.aspose.pdf.sdm.ThematicBreak;
import org.aspose.pdf.text.TableAbsorber;

/**
 * Standalone horizontal rules &rarr; {@link ThematicBreak} for flow targets.
 *
 * <p>Page-wide separator lines (a letterhead underline below the running
 * header, a section divider) are vector strokes, often drawn inside a
 * full-page Form XObject. A flow serializer (DOCX) emits no path content and
 * the flow-target pipeline deliberately skips rasterizing prose-covered
 * full-page regions ({@link VectorGraphicsEnricher}), so without this pass the
 * separators simply vanish. This enricher re-detects them semantically: it
 * collects the page's painted axis-aligned segments (recursing into Form
 * XObjects via {@link TableAbsorber#collectPageRuleSegments}), keeps the
 * near-page-wide horizontals that do not belong to a table grid, merges
 * near-coincident edges (a filled hairline rectangle contributes two), and
 * inserts a {@code ThematicBreak} into the flow at the rule's reading
 * position.</p>
 */
public final class HorizontalRuleEnricher {

    private static final Logger LOG = Logger.getLogger(HorizontalRuleEnricher.class.getName());

    private static final UUID NS = UUID.nameUUIDFromBytes("sdm-hrule".getBytes());

    /** A (joined) rule must span at least this fraction of the page width. */
    private static final double MIN_WIDTH_FRACTION = 0.50;
    /** Segments are horizontal when the endpoints' y differ by no more than this (pt). */
    private static final double MAX_SKEW = 0.75;
    /** Collinear segments joining into one rule may be this far apart in x (pt). */
    private static final double JOIN_GAP_X = 3.0;
    /** Segments belong to the same rule when their y differ by no more than this (pt). */
    private static final double JOIN_GAP_Y = 1.2;
    /** Rules within this vertical distance merge into one separator (pt). */
    private static final double MERGE_GAP = 2.5;
    /** Table grids are excluded with this safety margin around the table rect (pt). */
    private static final double TABLE_PAD = 6.0;
    /** Cap per page — a page with more separators than this is a grid, not prose. */
    private static final int MAX_RULES_PER_PAGE = 8;

    private HorizontalRuleEnricher() {
        // static entry only
    }

    /**
     * Detects standalone page-wide horizontal rules on every page and inserts
     * {@link ThematicBreak} blocks into the flow at their reading positions.
     *
     * @param doc the open source document (for content-stream scanning)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with (rule boxes are added)
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm) {
        enrich(doc, sdm, pgm, java.util.Collections.emptySet());
    }

    /**
     * Detects standalone page-wide horizontal rules on every page and inserts
     * {@link ThematicBreak} blocks into the flow at their reading positions.
     *
     * @param doc the open source document (for content-stream scanning)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with (rule boxes are added)
     * @param skipPages 0-based pages already handled as fixed-layout underlays
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm,
                              java.util.Set<Integer> skipPages) {
        if (doc == null || sdm == null || pgm == null) {
            return;
        }
        TableAbsorber absorber = new TableAbsorber();
        long seq = 0;
        for (int page0 = 0; page0 < pgm.getPages().size(); page0++) {
            PgmPage pp = pgm.getPage(page0);
            if (pp == null || skipPages.contains(page0)) {
                continue;
            }
            List<double[]> segments;
            try {
                Page page = doc.getPages().get(page0 + 1);
                segments = absorber.collectPageRuleSegments(page);
            } catch (Exception e) {
                LOG.fine("hrule: segment collection failed p" + (page0 + 1) + ": " + e);
                continue;
            }
            if (segments.isEmpty()) {
                continue;
            }
            double pageW = pp.getWidth();
            List<PgmRect> tableRects = VectorGraphicsEnricher.collectTableRects(sdm, pgm, page0);

            // A separator is often drawn as SEVERAL abutting strokes (one per
            // header cell, or a producer artifact), each too short on its own.
            // Join collinear horizontals first, THEN judge the joined width.
            List<double[]> horizontals = new ArrayList<>(); // {y, x1, x2}
            List<double[]> verticals = new ArrayList<>();   // {x, y1, y2}
            for (double[] s : segments) {
                if (Math.abs(s[3] - s[1]) <= MAX_SKEW) {
                    horizontals.add(new double[]{(s[1] + s[3]) / 2,
                            Math.min(s[0], s[2]), Math.max(s[0], s[2])});
                } else if (Math.abs(s[2] - s[0]) <= MAX_SKEW) {
                    verticals.add(new double[]{(s[0] + s[2]) / 2,
                            Math.min(s[1], s[3]), Math.max(s[1], s[3])});
                }
            }
            List<double[]> rules = joinAndFilter(horizontals, verticals, pageW, tableRects);
            if (rules.isEmpty()) {
                continue;
            }
            // Merge near-coincident edges (filled hairline re = top+bottom edge).
            rules.sort((a, b) -> Double.compare(b[0], a[0]));
            List<double[]> merged = new ArrayList<>();
            for (double[] r : rules) {
                if (!merged.isEmpty() && merged.get(merged.size() - 1)[0] - r[0] <= MERGE_GAP) {
                    continue;
                }
                merged.add(r);
            }
            if (merged.size() > MAX_RULES_PER_PAGE) {
                LOG.fine("hrule: " + merged.size() + " page-wide rules on p" + (page0 + 1)
                        + " — looks like a grid, skipping the page");
                continue;
            }
            for (double[] r : merged) {
                ThematicBreak tb = new ThematicBreak();
                String id = SdmIds.sessionNodeId(NS, "hrule", seq++);
                tb.setId(id);
                // Register geometry so reading-order passes keep the rule where
                // the separator sat on the page.
                PgmBox box = new PgmBox(id, page0, new PgmRect(r[1], r[0] - 0.5, r[2], 1), 0,
                        PgmBoxKind.VECTOR, null);
                pp.getBoxes().add(box);
                pgm.indexBox(box);
                VectorGraphicsEnricher.insertByPosition(sdm, pgm, tb, page0, r[0]);
            }
            final int n = merged.size();
            final int p = page0 + 1;
            LOG.fine(() -> "hrule: inserted " + n + " thematic break(s) on p" + p);
        }
    }

    /**
     * Groups horizontals into y-bands, joins x-runs whose gaps are at most
     * {@link #JOIN_GAP_X}, and keeps joined rules that are near-page-wide,
     * outside every table grid, and not crossed by vertical rules (a
     * horizontal crossed by verticals is a grid line even when the table
     * failed to materialize in the SDM — the structural test is
     * producer-independent, unlike the table-rect lookup).
     *
     * @param horizontals {@code {y, x1, x2}} segments
     * @param verticals   {@code {x, y1, y2}} segments
     * @return {@code {y, x, width}} rules
     */
    private static List<double[]> joinAndFilter(List<double[]> horizontals, List<double[]> verticals,
            double pageW, List<PgmRect> tableRects) {
        // Sort by y (desc), then x — bands come out contiguous.
        horizontals.sort((a, b) -> a[0] != b[0] ? Double.compare(b[0], a[0])
                : Double.compare(a[1], b[1]));
        List<double[]> rules = new ArrayList<>(); // {y, x, width}
        int i = 0;
        while (i < horizontals.size()) {
            double bandY = horizontals.get(i)[0];
            int j = i;
            while (j < horizontals.size() && bandY - horizontals.get(j)[0] <= JOIN_GAP_Y) {
                j++;
            }
            List<double[]> band = new ArrayList<>(horizontals.subList(i, j));
            band.sort((a, b) -> Double.compare(a[1], b[1]));
            double runX1 = band.get(0)[1];
            double runX2 = band.get(0)[2];
            for (int k = 1; k <= band.size(); k++) {
                if (k < band.size() && band.get(k)[1] - runX2 <= JOIN_GAP_X) {
                    runX2 = Math.max(runX2, band.get(k)[2]);
                    continue;
                }
                double w = runX2 - runX1;
                if (w >= MIN_WIDTH_FRACTION * pageW
                        && !insideTable(runX1 + w / 2, bandY, tableRects)
                        && !crossedByVerticals(bandY, runX1, runX2, verticals)) {
                    rules.add(new double[]{bandY, runX1, w});
                }
                if (k < band.size()) {
                    runX1 = band.get(k)[1];
                    runX2 = band.get(k)[2];
                }
            }
            i = j;
        }
        return rules;
    }

    /** True when at least two vertical rules touch the horizontal's band — a grid line. */
    private static boolean crossedByVerticals(double y, double x1, double x2,
            List<double[]> verticals) {
        int crossings = 0;
        for (double[] v : verticals) {
            if (v[0] >= x1 - 1 && v[0] <= x2 + 1 && y >= v[1] - 1 && y <= v[2] + 1) {
                if (++crossings >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean insideTable(double x, double y, List<PgmRect> tableRects) {
        for (PgmRect t : tableRects) {
            if (x >= t.getX() - TABLE_PAD && x <= t.getX() + t.getW() + TABLE_PAD
                    && y >= t.getY() - TABLE_PAD && y <= t.getTop() + TABLE_PAD) {
                return true;
            }
        }
        return false;
    }
}
