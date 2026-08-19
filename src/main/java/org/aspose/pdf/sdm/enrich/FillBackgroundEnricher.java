package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.pgm.VectorBoxData;
import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;

/**
 * Projects filled-rectangle vector graphics onto the text they enclose — IR
 * Stage 3 fidelity. A borderless PDF has no ruled table, but authors paint
 * coloured boxes behind callouts, form fields, buttons and slide titles with a
 * {@code re f} fill. Absolute PDF coordinates cannot survive a semantic reflow,
 * so instead of positioning a box we give the text block it bounds a CSS
 * {@code background-color} — the colour follows the text wherever it reflows.
 *
 * <p>HONEST DEGRADATION: only a TIGHT enclosing fill qualifies (area ≤ 4× the
 * block's own box, and never a half-page-plus frame) — a page-sized decorative
 * frame would otherwise tint every block. White / near-white fills are ignored
 * (they are "erase" rectangles, not backgrounds).</p>
 */
public final class FillBackgroundEnricher {

    /** Fills covering at least this fraction of the page are frames, not callouts. */
    private static final double MAX_PAGE_FRACTION = 0.5;
    /** A fill taller than this multiple of the block is a panel spanning other content. */
    private static final double MAX_HEIGHT_MULTIPLE = 8.0;
    /** Geometry slack (pt) when testing enclosure. */
    private static final double SLACK = 2.0;

    private FillBackgroundEnricher() {
    }

    /** One filled rectangle collected from the PGM. */
    private static final class Fill {
        final int page;
        final double x;
        final double y;
        final double w;
        final double h;
        final int color;
        final VectorBoxData source;
        Fill(int page, PgmRect r, int color, VectorBoxData source) {
            this.page = page;
            this.x = r.getX();
            this.y = r.getY();
            this.w = r.getW();
            this.h = r.getH();
            this.color = color;
            this.source = source;
        }
        double area() {
            return w * h;
        }
        boolean encloses(double bx, double by, double bw, double bh) {
            return bx >= x - SLACK && by >= y - SLACK
                    && bx + bw <= x + w + SLACK && by + bh <= y + h + SLACK;
        }
    }

    /**
     * Assigns background colours to text blocks bounded by a coloured fill.
     *
     * @param sdm the SDM document (mutated in place)
     * @param pgm the PGM geometry (fills + block boxes)
     */
    public static void enrich(SdmDocument sdm, PgmModel pgm) {
        if (sdm == null || pgm == null) {
            return;
        }
        List<Fill> fills = new ArrayList<>();
        for (PgmPage p : pgm.getPages()) {
            double pageArea = p.getWidth() * p.getHeight();
            for (PgmBox b : p.getBoxes()) {
                if (b.getKind() != PgmBoxKind.VECTOR || !(b.getData() instanceof VectorBoxData)) {
                    continue;
                }
                VectorBoxData v = (VectorBoxData) b.getData();
                // RECT and rectangle-ish filled PATHs (rounded boxes, banners drawn
                // with curved corners) qualify; a LINE is a stroke, never a fill.
                if (!v.isFilled() || v.getPrimitive() == VectorBoxData.Primitive.LINE) {
                    continue;
                }
                int c = v.getFillColor();
                if (c == 0 || isNearWhite(c)) {
                    continue;
                }
                if (pageArea > 0 && b.getRect().getW() * b.getRect().getH() >= MAX_PAGE_FRACTION * pageArea) {
                    continue; // page frame, not a callout
                }
                fills.add(new Fill(b.getPage(), b.getRect(), c, v));
            }
        }
        if (fills.isEmpty()) {
            return;
        }
        walk(sdm.getChildren(), pgm, fills);
    }

    private static void walk(List<? extends SdmBlock> blocks, PgmModel pgm, List<Fill> fills) {
        for (SdmBlock b : blocks) {
            if (b instanceof Paragraph || b instanceof Heading) {
                applyBackground(b, pgm, fills);
            }
            if (b instanceof Container) {
                walk(((Container) b).getChildren(), pgm, fills);
            } else if (b instanceof Quote) {
                walk(((Quote) b).getChildren(), pgm, fills);
            } else if (b instanceof Footnote) {
                walk(((Footnote) b).getChildren(), pgm, fills);
            } else if (b instanceof ListBlock) {
                for (ListItem li : ((ListBlock) b).getItems()) {
                    walk(li.getChildren(), pgm, fills);
                }
            } else if (b instanceof Table) {
                for (TableRow r : ((Table) b).getRows()) {
                    for (TableCell c : r.getCells()) {
                        walk(c.getChildren(), pgm, fills);
                    }
                }
            }
        }
    }

    private static void applyBackground(SdmBlock b, PgmModel pgm, List<Fill> fills) {
        if (b.getId() == null) {
            return;
        }
        List<PgmBox> boxes = pgm.byId(b.getId());
        if (boxes == null || boxes.isEmpty()) {
            return;
        }
        int page = -1;
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (PgmBox box : boxes) {
            page = box.getPage();
            minX = Math.min(minX, box.getRect().getX());
            minY = Math.min(minY, box.getRect().getY());
            maxX = Math.max(maxX, box.getRect().getX() + box.getRect().getW());
            maxY = Math.max(maxY, box.getRect().getY() + box.getRect().getH());
        }
        double bw = maxX - minX;
        double bh = maxY - minY;
        Fill best = null;
        for (Fill f : fills) {
            if (f.page != page || !f.encloses(minX, minY, bw, bh)) {
                continue;
            }
            // A banner can be far WIDER than its centred text, so width is not a
            // tightness signal — but a fill much TALLER than the block is a panel
            // spanning unrelated content, not this block's background.
            if (f.h > MAX_HEIGHT_MULTIPLE * Math.max(bh, 1.0)) {
                continue;
            }
            if (best == null || f.area() < best.area()) {
                best = f; // smallest tight enclosure wins
            }
        }
        if (best == null) {
            return;
        }
        // Contrast guard: a fill is only a text BACKGROUND when it contrasts with
        // the text (navy panel + white text = yes; black rule/bar + black text =
        // no — that would just bury the text under a solid block). Thin black
        // rules and default-black fills are the common false positives here.
        if (Math.abs(luminance(best.color) - luminance(blockTextColor(b))) < 0.3) {
            return;
        }
        BlockStyle st = b.getStyle();
        if (st == null) {
            st = new BlockStyle();
            b.setStyle(st);
        }
        st.setBackground(best.color);
        // The block background now carries this fill — tell the vector-region
        // rasterizer not to swallow the callout (text included) into pixels.
        best.source.setConsumedAsBackground(true);
    }

    /** Dominant text colour of a block (first explicit run colour; default black). */
    private static int blockTextColor(SdmBlock b) {
        List<SdmInline> inlines = b instanceof Paragraph ? ((Paragraph) b).getInline()
                : b instanceof Heading ? ((Heading) b).getInline() : null;
        if (inlines != null) {
            for (SdmInline in : inlines) {
                if (in instanceof Run) {
                    Run r = (Run) in;
                    if (r.getStyle() != null && r.getStyle().getColor() != 0) {
                        return r.getStyle().getColor() | 0xFF000000;
                    }
                }
            }
        }
        return 0xFF000000; // PDF default text colour is black
    }

    /** Perceived luminance in [0,1] of an ARGB colour. */
    private static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    private static boolean isNearWhite(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r >= 245 && g >= 245 && b >= 245;
    }
}
