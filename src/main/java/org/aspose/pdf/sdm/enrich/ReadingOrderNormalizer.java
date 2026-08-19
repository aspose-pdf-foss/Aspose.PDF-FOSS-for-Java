package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;

/**
 * Restores geometric reading order of the top-level block flow — IR Stage 3
 * presentation clean-up for the reflowed HTML view.
 *
 * <p>The shallow reader emits blocks in correct top-to-bottom, page-by-page
 * reading order, but downstream enrichment can disturb it. On <em>tagged</em>
 * PDFs the {@code TaggedSdmEnricher} rebuilds the flow in structure-tree order
 * (which, for imperfectly tagged files, groups every figure ahead of the text),
 * and {@code ImagePlacementEnricher} then re-inserts recovered images against a
 * flow that is no longer geometric — so a page-less HTML stream renders "all
 * images, then all text". A reflow has no page furniture to honour structure
 * order for, so the natural order is the visual one.</p>
 *
 * <p>This pass STABLE-sorts the top-level blocks by (page ascending, top edge
 * descending — PDF Y grows upward, so higher-on-page comes first). A block's
 * position is taken from the geometry of its whole subtree; a block with no
 * geometry at all (a synthesized wrapper) inherits the position of its nearest
 * positioned neighbour, so it travels with the content it decorates rather than
 * sinking to an end. Ties keep their original relative order.</p>
 */
public final class ReadingOrderNormalizer {

    private static final Logger LOG = Logger.getLogger(ReadingOrderNormalizer.class.getName());

    private ReadingOrderNormalizer() {
        // static entry only
    }

    /**
     * Reorders the document's top-level blocks into geometric reading order.
     *
     * @param sdm the structural model (mutated)
     * @param pgm the page geometry model
     * @return the number of blocks whose position changed
     */
    public static int normalize(SdmDocument sdm, PgmModel pgm) {
        if (sdm == null || pgm == null) {
            return 0;
        }
        return sortFlow(sdm.getChildren(), pgm);
    }

    /**
     * Sorts one block-flow list into geometric reading order, then recurses into
     * its Container children — a tagged tree wraps the whole page flow in one
     * Sect/Div, so top-level sorting alone would be a no-op and leave a footer page
     * number stranded where the tag order put it.
     */
    private static int sortFlow(List<SdmBlock> kids, PgmModel pgm) {
        int n = kids.size();
        if (n < 2) {
            int deep = 0;
            for (SdmBlock b : kids) {
                if (b instanceof Container) {
                    deep += sortFlow(((Container) b).getChildren(), pgm);
                }
            }
            return deep;
        }

        // Geometric key per block: {page, top}, or null when the subtree carries
        // no boxes at all.
        double[][] pos = new double[n][];
        for (int i = 0; i < n; i++) {
            pos[i] = positionOf(kids.get(i), pgm);
        }
        // Anchor box-less blocks to the nearest positioned neighbour so they stay
        // with their content. Forward-fill first, then back-fill any leading gap.
        double[] carry = null;
        for (int i = 0; i < n; i++) {
            if (pos[i] != null) {
                carry = pos[i];
            } else if (carry != null) {
                pos[i] = carry;
            }
        }
        carry = null;
        for (int i = n - 1; i >= 0; i--) {
            if (pos[i] != null) {
                carry = pos[i];
            } else if (carry != null) {
                pos[i] = carry;
            }
        }

        // Decorate with original index for a stable sort, then reorder.
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        final double[][] fpos = pos;
        java.util.Arrays.sort(order, (a, b) -> {
            double[] pa = fpos[a];
            double[] pb = fpos[b];
            if (pa == null && pb == null) {
                return Integer.compare(a, b);
            }
            if (pa == null) {
                return -1; // untethered: keep before (rare — whole doc box-less)
            }
            if (pb == null) {
                return 1;
            }
            if (pa[0] != pb[0]) {
                return Double.compare(pa[0], pb[0]); // page ascending
            }
            if (pa[1] != pb[1]) {
                return Double.compare(pb[1], pa[1]); // top descending (higher first)
            }
            return Integer.compare(a, b); // stable tie-break
        });

        int moved = 0;
        List<SdmBlock> reordered = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            reordered.add(kids.get(order[i]));
            if (order[i] != i) {
                moved++;
            }
        }
        if (moved > 0) {
            kids.clear();
            kids.addAll(reordered);
            LOG.fine("ReadingOrderNormalizer: reordered " + moved + " of " + n + " blocks");
        }
        // Recurse into containers so a nested page flow is ordered too.
        for (SdmBlock b : kids) {
            if (b instanceof Container) {
                moved += sortFlow(((Container) b).getChildren(), pgm);
            }
        }
        return moved;
    }

    /**
     * Reading position of a block: the smallest page any box in its subtree sits
     * on, and the highest top edge on that page. Null when the subtree has no
     * geometry.
     */
    private static double[] positionOf(SdmBlock block, PgmModel pgm) {
        double[] acc = {Double.NaN, Double.NaN}; // page, top
        accumulate(block, pgm, acc);
        return Double.isNaN(acc[0]) ? null : acc;
    }

    private static void accumulate(SdmBlock block, PgmModel pgm, double[] acc) {
        if (block == null) {
            return;
        }
        if (block.getId() != null) {
            for (PgmBox box : pgm.byId(block.getId())) {
                double page = box.getPage();
                double top = box.getRect().getTop();
                if (Double.isNaN(acc[0]) || page < acc[0]
                        || (page == acc[0] && top > acc[1])) {
                    acc[0] = page;
                    acc[1] = top;
                }
            }
        }
        // Recurse into the block containers that hold their own geometry-bearing
        // children (paragraph/heading text lives on the block's own id).
        if (block instanceof Container) {
            for (SdmBlock c : ((Container) block).getChildren()) {
                accumulate(c, pgm, acc);
            }
        } else if (block instanceof Quote) {
            for (SdmBlock c : ((Quote) block).getChildren()) {
                accumulate(c, pgm, acc);
            }
        } else if (block instanceof Footnote) {
            for (SdmBlock c : ((Footnote) block).getChildren()) {
                accumulate(c, pgm, acc);
            }
        } else if (block instanceof ListBlock) {
            for (ListItem it : ((ListBlock) block).getItems()) {
                for (SdmBlock c : it.getChildren()) {
                    accumulate(c, pgm, acc);
                }
            }
        } else if (block instanceof Table) {
            for (TableRow row : ((Table) block).getRows()) {
                for (TableCell cell : row.getCells()) {
                    for (SdmBlock c : cell.getChildren()) {
                        accumulate(c, pgm, acc);
                    }
                }
            }
        } else if (block instanceof Figure) {
            for (SdmBlock c : ((Figure) block).getCaption()) {
                accumulate(c, pgm, acc);
            }
        }
    }
}
