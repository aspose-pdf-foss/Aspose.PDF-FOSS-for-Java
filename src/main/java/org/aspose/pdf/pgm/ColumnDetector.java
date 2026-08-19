package org.aspose.pdf.pgm;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Column-structure detection for one page (IR spec §2.7, Stage 1 PART 6).
 * <p>
 * Signals: the x-coverage profile of TEXT boxes (a vertical whitespace "river"
 * of at least {@link #MIN_RIVER_WIDTH} pt splitting the text extent) plus
 * full-width spanning zones. Classification:
 * <ul>
 *   <li>{@code SINGLE_COLUMN} — no river, or not enough text for a signal;</li>
 *   <li>{@code MULTI_CLEAN(n)} — n column bands separated by rivers, no
 *       spanning zones;</li>
 *   <li>{@code MULTI_MIXED} — column bands plus at least one full-width
 *       spanning zone (title, table across columns).</li>
 * </ul>
 * Misclassifications on labeled pages are treated as FOUND defects of the
 * PGM reader (missing boxes, wrong x) or named heuristic limits — see
 * IR_STAGE1_FINDINGS.md.
 * </p>
 */
public final class ColumnDetector {

    private static final Logger LOG = Logger.getLogger(ColumnDetector.class.getName());

    /** Minimum text boxes for any multi-column signal. */
    static final int MIN_BOXES = 8;
    /** Minimum river (gutter) width in points. */
    static final double MIN_RIVER_WIDTH = 8.0;
    /** Profile strip width in points. */
    static final double STRIP = 2.0;
    /** A box wider than this fraction of the text extent is a spanning zone. */
    static final double SPAN_FRACTION = 0.75;
    /** Each column band must hold at least this fraction of the boxes. */
    static final double MIN_COLUMN_SHARE = 0.15;
    /**
     * A river crossed by at least this many VECTOR/IMAGE boxes is a table/form
     * gutter, not a column gutter (labeled-set calibration: tables, invoices
     * and Patent-Office-style forms are the dominant false-multi family, and
     * all of them draw rulings across the candidate river; genuine column
     * pages have at most one decorative rule crossing).
     */
    static final int RIVER_CROSSINGS_LIMIT = 2;

    private ColumnDetector() {
    }

    /**
     * Classifies the page and stores the result on it.
     *
     * @param page the PGM page (uses its TEXT boxes)
     * @return the classification (also set via {@link PgmPage#setColumnStructure})
     */
    public static ColumnStructure detect(PgmPage page) {
        ColumnStructure result = classify(page);
        page.setColumnStructure(result);
        return result;
    }

    /**
     * Merges thin horizontal rulings (h ≤ 2pt) that share a y (±1pt) and whose
     * x-intervals touch (gap ≤ 3pt) into maximal chains. Every thin segment is
     * represented exactly once in the result (single segments pass through).
     */
    private static List<PgmRect> mergeCollinearHorizontals(List<PgmRect> rulings) {
        List<PgmRect> thin = new ArrayList<>();
        for (PgmRect r : rulings) {
            if (r.getH() <= 2) {
                thin.add(r);
            }
        }
        thin.sort((a, b) -> a.getY() != b.getY()
                ? Double.compare(a.getY(), b.getY())
                : Double.compare(a.getX(), b.getX()));
        List<PgmRect> merged = new ArrayList<>();
        int i = 0;
        while (i < thin.size()) {
            PgmRect cur = thin.get(i);
            double y = cur.getY();
            double x0 = cur.getX();
            double x1 = cur.getRight();
            int j = i + 1;
            while (j < thin.size() && Math.abs(thin.get(j).getY() - y) <= 1
                    && thin.get(j).getX() <= x1 + 3) {
                x1 = Math.max(x1, thin.get(j).getRight());
                j++;
            }
            merged.add(new PgmRect(x0, y, x1 - x0, Math.max(cur.getH(), 0.1)));
            i = j;
        }
        return merged;
    }

    /** Length of the union of [y0,y1] spans clipped to [clipMin, clipMax]. */
    private static double unionLength(List<double[]> spans, double clipMin, double clipMax) {
        if (spans.isEmpty()) {
            return 0;
        }
        spans.sort((a, b) -> Double.compare(a[0], b[0]));
        double total = 0;
        double curStart = Double.NaN;
        double curEnd = Double.NaN;
        for (double[] s : spans) {
            double y0 = Math.max(s[0], clipMin);
            double y1 = Math.min(s[1], clipMax);
            if (y1 <= y0) {
                continue;
            }
            if (Double.isNaN(curStart)) {
                curStart = y0;
                curEnd = y1;
            } else if (y0 <= curEnd) {
                curEnd = Math.max(curEnd, y1);
            } else {
                total += curEnd - curStart;
                curStart = y0;
                curEnd = y1;
            }
        }
        if (!Double.isNaN(curStart)) {
            total += curEnd - curStart;
        }
        return total;
    }

    private static ColumnStructure classify(PgmPage page) {
        List<PgmRect> text = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getRect().getW() >= 4
                    && b.getRect().getH() > 0) {
                // Rotated marginal text (sideways edge notes) is not part of
                // the horizontal reading flow and fakes a column band.
                if (b.getData() instanceof TextBoxData
                        && ((TextBoxData) b.getData()).getRotation() != 0) {
                    continue;
                }
                text.add(b.getRect());
            }
        }
        if (text.size() < MIN_BOXES) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }

        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        for (PgmRect r : text) {
            minX = Math.min(minX, r.getX());
            maxX = Math.max(maxX, r.getRight());
        }
        double extent = maxX - minX;
        if (extent < 120) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }

        // Split spanning zones (full-width boxes) from column candidates.
        List<PgmRect> columnText = new ArrayList<>();
        int spanning = 0;
        for (PgmRect r : text) {
            if (r.getW() >= SPAN_FRACTION * extent) {
                spanning++;
            } else {
                columnText.add(r);
            }
        }
        if (columnText.size() < MIN_BOXES) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }

        // x-coverage profile over the text extent (spanning zones excluded).
        int strips = (int) Math.ceil(extent / STRIP);
        boolean[] covered = new boolean[strips];
        for (PgmRect r : columnText) {
            int from = (int) Math.max(0, Math.floor((r.getX() - minX) / STRIP));
            int to = (int) Math.min(strips - 1, Math.ceil((r.getRight() - minX) / STRIP) - 1);
            for (int s = from; s <= to; s++) {
                covered[s] = true;
            }
        }

        // Rivers: uncovered runs of >= MIN_RIVER_WIDTH strictly inside the extent.
        List<double[]> rivers = new ArrayList<>();
        int runStart = -1;
        for (int s = 0; s <= strips; s++) {
            boolean cov = s < strips && covered[s];
            if (!cov && s < strips) {
                if (runStart < 0) {
                    runStart = s;
                }
            } else if (runStart >= 0) {
                double x0 = minX + runStart * STRIP;
                double x1 = minX + s * STRIP;
                boolean interior = runStart > 0 && s < strips;
                if (interior && x1 - x0 >= MIN_RIVER_WIDTH) {
                    rivers.add(new double[]{x0, x1});
                }
                runStart = -1;
            }
        }
        if (rivers.isEmpty()) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }

        // Rulings gate: a text gutter is EMPTY; a river repeatedly crossed by
        // vector rulings or images — or walled by a vertical ruling running
        // inside it (table cell borders) — is a table/form/artwork gutter.
        // The y-window uses ALL text (spanning zones included): header rules
        // sit between the spanning title and the column body.
        double textMinY = Double.MAX_VALUE;
        double textMaxY = -Double.MAX_VALUE;
        for (PgmRect r : text) {
            textMinY = Math.min(textMinY, r.getY());
            textMaxY = Math.max(textMaxY, r.getTop());
        }
        double textHeight = Math.max(1, textMaxY - textMinY);
        List<PgmRect> rulings = new ArrayList<>();
        for (PgmBox b : page.getBoxes()) {
            if (b.getKind() == PgmBoxKind.VECTOR || b.getKind() == PgmBoxKind.IMAGE) {
                PgmRect r = b.getRect();
                if (r.getTop() >= textMinY && r.getY() <= textMaxY) {
                    rulings.add(r);
                }
            }
        }
        // Table generators draw horizontal rules as per-cell SEGMENTS that end
        // exactly at column boundaries — REPLACE thin segments by their merged
        // chains (never add: an already-full rule must not count twice).
        List<PgmRect> thick = new ArrayList<>();
        for (PgmRect r : rulings) {
            if (r.getH() > 2) {
                thick.add(r);
            }
        }
        List<PgmRect> chains = mergeCollinearHorizontals(rulings);
        rulings = new ArrayList<>(thick);
        rulings.addAll(chains);
        boolean debug = Boolean.getBoolean("ir.columns.debug");
        List<double[]> openRivers = new ArrayList<>();
        for (double[] river : rivers) {
            int crossings = 0;
            boolean walled = false;
            double riverWidth = Math.max(1, river[1] - river[0]);
            List<double[]> overlapSpans = new ArrayList<>();
            for (PgmRect r : rulings) {
                double overlap = Math.min(r.getRight(), river[1]) - Math.max(r.getX(), river[0]);
                if (r.getX() <= river[0] - 2 && r.getRight() >= river[1] + 2) {
                    crossings++;
                } else if (overlap >= 0.6 * riverWidth) {
                    // Structure (table cell rect, panel background) covering most
                    // of the river width — suppress only if such boxes also fill
                    // most of the river's HEIGHT (a stray figure edge must not
                    // kill a genuine gutter).
                    overlapSpans.add(new double[]{r.getY(), r.getTop()});
                }
                // Vertical ruling living inside the river = a table border.
                boolean inRiver = r.getX() <= river[1] + 2 && r.getRight() >= river[0] - 2;
                if (inRiver && r.getW() <= MIN_RIVER_WIDTH && r.getH() >= 0.3 * textHeight) {
                    walled = true;
                }
            }
            double overlapCoverage = unionLength(overlapSpans, textMinY, textMaxY);
            boolean overlapFilled = overlapCoverage >= 0.5 * textHeight;
            if (overlapFilled) {
                walled = true;
            }
            if (debug) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[columns-debug] p%d river [%.1f..%.1f] crossings=%d walled=%b "
                                + "(rulings=%d, textY=%.1f..%.1f)",
                        page.getIndex(), river[0], river[1], crossings, walled,
                        rulings.size(), textMinY, textMaxY));
            }
            if (crossings < RIVER_CROSSINGS_LIMIT && !walled) {
                openRivers.add(river);
            }
        }
        rivers = openRivers;
        if (rivers.isEmpty()) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }

        // Column bands between rivers; each must hold a real share of the text.
        List<double[]> bands = new ArrayList<>();
        double bandStart = minX;
        for (double[] river : rivers) {
            bands.add(new double[]{bandStart, river[0]});
            bandStart = river[1];
        }
        bands.add(new double[]{bandStart, maxX});

        int minShare = (int) Math.max(2, Math.floor(columnText.size() * MIN_COLUMN_SHARE));
        List<double[]> realBandRanges = new ArrayList<>();
        for (double[] band : bands) {
            int members = 0;
            for (PgmRect r : columnText) {
                double cx = r.getX() + r.getW() / 2;
                if (cx >= band[0] && cx <= band[1]) {
                    members++;
                }
            }
            if (members >= minShare) {
                realBandRanges.add(band);
            }
        }
        int realBands = realBandRanges.size();
        if (realBands < 2) {
            return new ColumnStructure(ColumnStructure.Type.SINGLE_COLUMN, 1);
        }
        final int bandCount = realBands;
        final int spanCount = spanning;
        LOG.fine(() -> "page " + page.getIndex() + ": " + bandCount + " columns, "
                + spanCount + " spanning zones");
        double[][] bandArray = realBandRanges.toArray(new double[0][]);
        return spanning > 0
                ? new ColumnStructure(ColumnStructure.Type.MULTI_MIXED, realBands, bandArray)
                : new ColumnStructure(ColumnStructure.Type.MULTI_CLEAN, realBands, bandArray);
    }
}
