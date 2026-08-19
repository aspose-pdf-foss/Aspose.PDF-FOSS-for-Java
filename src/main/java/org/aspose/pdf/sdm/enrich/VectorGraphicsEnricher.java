package org.aspose.pdf.sdm.enrich;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.render.PdfPageRenderer;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmIds;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;

/**
 * Rasterizes vector-graphics page regions into {@code <img>} figures for the
 * STRUCTURAL HTML view — IR Stage 3 presentation fidelity.
 *
 * <p>The HTML writers emit text and raster images but no path/fill/stroke/
 * shading content, so a bar chart, a Kaplan-Meier plot, a filled shape or a 3D
 * preview renders as 0 pixels (only its text labels survive). This pass closes
 * the gap by honest rasterization: it clusters the PGM's VECTOR boxes into
 * regions, renders the page once (raster images suppressed — figures already
 * represent those), crops each region to a PNG and inserts it into the flow as
 * a {@link Figure} sized to its on-page footprint.</p>
 *
 * <p>A region whose interior is graphics-dominant (chart with sparse labels)
 * bakes its text INTO the crop and removes the duplicated text blocks from the
 * flow (their text is preserved as the figure's {@code alt}); a text-heavy
 * region (a page border frame, a form with rules around body copy) is left
 * alone — rasterizing it would swallow real prose. Regions consumed by a
 * materialized ruled {@code <table>} or already covered by a raster image are
 * skipped. Empty vector {@link Opaque} placeholders inside a rasterized region
 * are dropped from the flow (they render as invisible clutter otherwise).</p>
 *
 * <p>PRESENTATION-LAYER ONLY: this pass must run from
 * {@code StructuralHtmlPipeline.toHtml()}, never from {@code enrich()} — it
 * deletes text blocks from the flow, which would break the lossless PDF&rarr;SDM
 * projection contract the two-link oracle enforces.</p>
 *
 * <p>Kill switch: {@code -Dsdm.rasterizeVector=false}; DPI override:
 * {@code -Dsdm.vectorRasterDpi=<n>} (default 144).</p>
 */
public final class VectorGraphicsEnricher {

    private static final Logger LOG = Logger.getLogger(VectorGraphicsEnricher.class.getName());
    private static final UUID NS = UUID.nameUUIDFromBytes("sdm-vecras".getBytes());
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("sdm.rasterizeVector", "true"));

    /** Boxes closer than this (pt) merge into one region. */
    private static final double CLUSTER_GAP = 10.0;
    /** A lone shape must have both dimensions at least this (pt)... */
    private static final double MIN_DIM = 6.0;
    /** ...and at least this area (pt&sup2;) to be worth a figure. */
    private static final double MIN_AREA = 300.0;
    /** A cluster of at least this many fragments is kept regardless of size. */
    private static final int MIN_COUNT = 6;
    /** Text covering more than this fraction of the region area = text-heavy. */
    private static final double TEXT_HEAVY_FRACTION = 0.25;
    /** A text block at least this fraction inside the region is baked/removed. */
    private static final double TEXT_INSIDE_FRACTION = 0.85;
    /** A vector opaque at least this fraction inside the region is dropped. */
    private static final double VECTOR_INSIDE_FRACTION = 0.60;
    /** A region with both dimensions at least this fraction of the page is "full page". */
    private static final double FULL_PAGE_FRACTION = 0.80;
    /**
     * Flow-target veto: a full-page region whose text cover exceeds this holds
     * real prose (page furniture, narrative under a page-wide grid XObject), not
     * chart labels. Rasterizing it would bake the document's text into pixels —
     * fatal for an editable flow target (DOCX), where the raster is dropped or
     * useless. Genuine full-page charts stay under this (sparse axis labels).
     */
    private static final double FULL_PAGE_TEXT_VETO = 0.05;
    /**
     * Flow-target veto, absolute form: a region — of ANY size — covering at
     * least this many characters holds prose, not chart labels (a 70%-page
     * form region with a thousand characters of body copy swallowed whole
     * documents: 31869 lost 97% of its text). Rasterizing it deletes the
     * covered text from an editable flow target. Chart tick/axis labels stay
     * well under this; such regions keep today's raster+bake behaviour.
     */
    private static final int FLOW_REGION_TEXT_CHARS_VETO = 250;
    /**
     * Flow-target mid-band: a kept region covering at least this many
     * characters (a scan's stamp line, a drawing's caption) keeps the covered
     * text IN THE FLOW instead of baking-and-deleting it — mild duplication
     * beats losing real words. Below this, labels are chart furniture and stay
     * baked into the raster only.
     */
    private static final int FLOW_REGION_TEXT_KEEP = 100;
    /** Crop padding (pt) so anti-aliased edges are not clipped. */
    private static final double PAD = 2.0;
    /** Cap on the rendered page raster's larger side (px). */
    private static final int MAX_RENDER_PX = 6000;

    private VectorGraphicsEnricher() {
        // static entry only
    }

    /**
     * Rasterizes the vector-graphics regions of every page into figures.
     *
     * @param doc the open source document (for rendering)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with (figure boxes are added)
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm) {
        enrich(doc, sdm, pgm, false);
    }

    /**
     * Rasterizes the vector-graphics regions of every page into figures.
     *
     * @param doc the open source document (for rendering)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with (figure boxes are added)
     * @param vetoTextyFullPage flow-target mode (DOCX): skip full-page regions
     *                          that hold real prose instead of baking it into
     *                          pixels and deleting the text blocks; the HTML
     *                          targets keep the raster for visual fidelity
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm, boolean vetoTextyFullPage) {
        enrich(doc, sdm, pgm, vetoTextyFullPage, java.util.Collections.emptySet());
    }

    /**
     * Rasterizes the vector-graphics regions of every page into figures.
     *
     * @param doc the open source document (for rendering)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with (figure boxes are added)
     * @param vetoTextyFullPage flow-target mode (DOCX): skip full-page regions
     *                          that hold real prose
     * @param skipPages 0-based pages already handled elsewhere (fixed-layout
     *                  underlay pages) — no regions are built for them
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm,
                              boolean vetoTextyFullPage, java.util.Set<Integer> skipPages) {
        if (!ENABLED || doc == null || sdm == null || pgm == null) {
            return;
        }
        int dpi = Integer.getInteger("sdm.vectorRasterDpi", 144);
        // Cost guard: every rasterized page is a full 144-dpi render, so a
        // thousands-page corpus dump would take hours. Cap the number of pages
        // rendered and figures produced; later pages keep today's behaviour.
        int maxPages = Integer.getInteger("sdm.vectorRasterMaxPages", 300);
        int maxFigures = Integer.getInteger("sdm.vectorRasterMaxFigures", 1000);
        int renderedPages = 0;
        long seq = 0;
        int figures = 0;
        for (int page0 = 0; page0 < pgm.getPages().size(); page0++) {
            if (renderedPages >= maxPages || figures >= maxFigures) {
                LOG.warning("VectorGraphicsEnricher: cap reached (" + renderedPages
                        + " pages, " + figures + " figures) — remaining pages keep placeholders");
                break;
            }
            PgmPage pp = pgm.getPage(page0);
            if (pp == null || skipPages.contains(page0)) {
                continue;
            }
            // /Rotate is applied by the renderer (visually upright, swapped dims);
            // cropPng maps content-space regions into that rotated pixel space.
            int rotation = ((pp.getRotation() % 360) + 360) % 360;
            List<PgmBox> vector = new ArrayList<>();
            List<PgmBox> text = new ArrayList<>();
            List<PgmRect> images = new ArrayList<>();
            java.util.Set<String> formXObjectIds = collectFormXObjectIds(sdm);
            for (PgmBox b : pp.getBoxes()) {
                if (b.getKind() == PgmBoxKind.VECTOR) {
                    vector.add(b);
                } else if (b.getKind() == PgmBoxKind.TEXT) {
                    text.add(b);
                } else if (b.getKind() == PgmBoxKind.IMAGE) {
                    images.add(b.getRect());
                } else if (b.getId() != null && formXObjectIds.contains(b.getId())
                        && b.getRect().getW() > 0 && b.getRect().getH() > 0) {
                    // A Form XObject is opaque to the shallow projection — its
                    // paths never became VECTOR boxes, so a drawing living
                    // entirely inside one (full-page fills, EPS conversions)
                    // rendered as nothing. Treat the form's footprint as a
                    // vector candidate; the text-cover and image-cover guards
                    // below still veto prose-heavy or raster-backed forms.
                    vector.add(b);
                }
            }
            if (vector.isEmpty()) {
                continue;
            }
            List<PgmRect> tableRects = collectTableRects(sdm, pgm, page0);
            List<PgmBox> candidates = new ArrayList<>();
            for (PgmBox v : vector) {
                if (v.getData() instanceof org.aspose.pdf.pgm.VectorBoxData
                        && ((org.aspose.pdf.pgm.VectorBoxData) v.getData()).isConsumedAsBackground()) {
                    continue; // already projected as a text-block background-color
                }
                if (insideFraction(v.getRect(), tableRects) >= VECTOR_INSIDE_FRACTION) {
                    continue; // the ruled <table> already draws these rules
                }
                if (insideFraction(v.getRect(), images) >= 0.9) {
                    continue; // painted under/over a raster the figure shows
                }
                candidates.add(v);
            }
            List<List<PgmBox>> clusters = cluster(candidates);
            BufferedImage pageRender = null; // lazy, one render per page
            for (List<PgmBox> cl : clusters) {
                PgmRect bbox = unionOf(cl);
                double area = bbox.getW() * bbox.getH();
                boolean bigEnough = cl.size() >= MIN_COUNT
                        || (Math.min(bbox.getW(), bbox.getH()) >= MIN_DIM && area >= MIN_AREA);
                if (!bigEnough) {
                    continue;
                }
                double textCover = coverFraction(bbox, text);
                if (System.getProperty("sdm.vecras.debug") != null) {
                    System.out.println(String.format(java.util.Locale.ROOT,
                            "[VECRAS] p%d cluster n=%d bbox=%.0fx%.0f cover=%.3f chars=%d",
                            page0 + 1, cl.size(), bbox.getW(), bbox.getH(), textCover,
                            coveredTextChars(bbox, text)));
                }
                if (textCover > TEXT_HEAVY_FRACTION) {
                    continue; // body copy inside — rasterizing would swallow prose
                }
                long coveredChars = vetoTextyFullPage ? coveredTextChars(bbox, text) : 0;
                if (vetoTextyFullPage
                        && (coveredChars >= FLOW_REGION_TEXT_CHARS_VETO
                                || (bbox.getW() >= FULL_PAGE_FRACTION * pp.getWidth()
                                        && bbox.getH() >= FULL_PAGE_FRACTION * pp.getHeight()
                                        && textCover > FULL_PAGE_TEXT_VETO))) {
                    // Flow target: a region carrying real prose (or a page-wide
                    // grid/letterhead with dense text cover) — keep the text as
                    // text; images inside the region stay as their own figures.
                    LOG.fine("vector raster: texty region skipped p" + (page0 + 1));
                    continue;
                }
                // Flow target, mid-band (a scan with a stamp/footer line, a
                // drawing with a caption): keep the raster AND keep the text in
                // the flow — mild duplication beats deleting real words from an
                // editable target. Only tiny label sets (charts) stay baked-only.
                boolean keepCoveredText = vetoTextyFullPage
                        && coveredChars >= FLOW_REGION_TEXT_KEEP;
                try {
                    if (pageRender == null) {
                        pageRender = renderPage(doc, page0 + 1, dpi);
                        renderedPages++;
                    }
                } catch (Exception e) {
                    LOG.fine("vector raster: page render failed p" + (page0 + 1) + ": " + e);
                    break; // no point trying other clusters of this page
                }
                if (pageRender == null) {
                    break;
                }
                Page page;
                try {
                    page = doc.getPages().get(page0 + 1);
                } catch (Exception e) {
                    break;
                }
                PgmRect padded = pad(bbox, pp);
                byte[] png = cropPng(pageRender, page, padded, rotation);
                if (png == null) {
                    continue;
                }
                ResourceRef ref = sdm.getResources().put(
                        "vecras:" + page0 + ":" + seq,
                        new Resource(Resource.Kind.IMAGE, png, "image/png"));
                Figure fig = new Figure(ref);
                String figId = SdmIds.sessionNodeId(NS, "vecras", seq++);
                fig.setId(figId);
                // A 90/270 render swaps the visual aspect: the crop's width is the
                // region's content-space HEIGHT, so swap the display dimensions too.
                double dispW = padded.getW();
                double dispH = padded.getH();
                if (rotation == 90 || rotation == 270) {
                    dispW = padded.getH();
                    dispH = padded.getW();
                }
                fig.getAttributes().put("display-width", dispW);
                fig.getAttributes().put("display-height", dispH);
                fig.getAttributes().put("vector-region", Boolean.TRUE);

                // The crop baked the region's text labels in — remove the now
                // duplicated text blocks from the flow, preserving their words
                // as the figure's alt text (unless the flow target keeps them).
                StringBuilder alt = new StringBuilder();
                removeCoveredBlocks(sdm, pgm, page0, padded, alt, keepCoveredText);
                if (alt.length() > 0) {
                    fig.setAlt(alt.toString().trim());
                }

                // Register geometry so ReadingOrderNormalizer places the figure
                // exactly where the graphic sat on the page.
                PgmBox figBox = new PgmBox(figId, page0, padded, 0, PgmBoxKind.IMAGE, null);
                pp.getBoxes().add(figBox);
                pgm.indexBox(figBox);

                insertByPosition(sdm, pgm, fig, page0, padded.getTop());
                figures++;
            }
        }
        final int f = figures;
        LOG.fine(() -> "VectorGraphicsEnricher: rasterized " + f + " region(s)");
    }

    // ------------------------------------------------------------ clustering

    /** Above this many fragments the page is one big drawing — skip O(n&sup2;) pairing. */
    private static final int SINGLE_CLUSTER_THRESHOLD = 1500;

    /** Single-link clustering: boxes whose rects sit within CLUSTER_GAP merge. */
    private static List<List<PgmBox>> cluster(List<PgmBox> boxes) {
        int n = boxes.size();
        if (n > SINGLE_CLUSTER_THRESHOLD) {
            // A vector-fragment explosion (a chart plotted mark-by-mark) IS one
            // drawing; pairing every fragment would be quadratic for nothing.
            List<List<PgmBox>> one = new ArrayList<>();
            one.add(new ArrayList<>(boxes));
            return one;
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            PgmRect a = boxes.get(i).getRect();
            for (int j = i + 1; j < n; j++) {
                PgmRect b = boxes.get(j).getRect();
                if (gapBetween(a, b) <= CLUSTER_GAP) {
                    union(parent, i, j);
                }
            }
        }
        java.util.Map<Integer, List<PgmBox>> byRoot = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            byRoot.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(boxes.get(i));
        }
        return new ArrayList<>(byRoot.values());
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

    /** The axis-aligned gap between two rects (0 when they touch or overlap). */
    private static double gapBetween(PgmRect a, PgmRect b) {
        double dx = Math.max(0, Math.max(b.getX() - (a.getX() + a.getW()),
                                         a.getX() - (b.getX() + b.getW())));
        double dy = Math.max(0, Math.max(b.getY() - a.getTop(), a.getY() - b.getTop()));
        return Math.max(dx, dy);
    }

    private static PgmRect unionOf(List<PgmBox> boxes) {
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (PgmBox b : boxes) {
            PgmRect r = b.getRect();
            x0 = Math.min(x0, r.getX());
            y0 = Math.min(y0, r.getY());
            x1 = Math.max(x1, r.getX() + r.getW());
            y1 = Math.max(y1, r.getTop());
        }
        return PgmRect.fromCorners(x0, y0, x1, y1);
    }

    private static PgmRect pad(PgmRect r, PgmPage pp) {
        double x0 = Math.max(0, r.getX() - PAD);
        double y0 = Math.max(0, r.getY() - PAD);
        double x1 = Math.min(pp.getWidth(), r.getX() + r.getW() + PAD);
        double y1 = Math.min(pp.getHeight(), r.getTop() + PAD);
        if (x1 <= x0 || y1 <= y0) {
            return r;
        }
        return PgmRect.fromCorners(x0, y0, x1, y1);
    }

    // ------------------------------------------------------------ geometry

    /**
     * A degenerate rect (a stroked line has zero height/width) inflated to a
     * minimal extent so area-based coverage math stays meaningful.
     */
    private static PgmRect effRect(PgmRect r) {
        if (r.getW() >= 0.5 && r.getH() >= 0.5) {
            return r;
        }
        double w = Math.max(r.getW(), 0.5);
        double h = Math.max(r.getH(), 0.5);
        return PgmRect.fromCorners(r.getX(), r.getY(), r.getX() + w, r.getY() + h);
    }

    /** Fraction of {@code r}'s area inside the union of {@code others} (approx: max single overlap). */
    private static double insideFraction(PgmRect r, List<PgmRect> others) {
        PgmRect e = effRect(r);
        double area = e.getW() * e.getH();
        double best = 0;
        for (PgmRect o : others) {
            best = Math.max(best, overlapArea(e, o) / area);
        }
        return best;
    }

    /** Fraction of {@code region}'s area covered by the given boxes (summed, capped). */
    private static double coverFraction(PgmRect region, List<PgmBox> boxes) {
        double area = region.getW() * region.getH();
        if (area <= 0) {
            return 1.0;
        }
        double sum = 0;
        for (PgmBox b : boxes) {
            sum += overlapArea(region, b.getRect());
        }
        return Math.min(1.0, sum / area);
    }

    private static double overlapArea(PgmRect a, PgmRect b) {
        double ox = Math.min(a.getX() + a.getW(), b.getX() + b.getW()) - Math.max(a.getX(), b.getX());
        double oy = Math.min(a.getTop(), b.getTop()) - Math.max(a.getY(), b.getY());
        return ox > 0 && oy > 0 ? ox * oy : 0;
    }

    /** Bounding boxes (this page) of materialized Table blocks, from their cells' geometry. */
    /** Package hook (shared with {@link HorizontalRuleEnricher}). */
    static List<PgmRect> collectTableRects(SdmDocument sdm, PgmModel pgm, int page0) {
        List<PgmRect> out = new ArrayList<>();
        collectTableRects(sdm.getChildren(), pgm, page0, out);
        return out;
    }

    private static void collectTableRects(List<SdmBlock> blocks, PgmModel pgm, int page0,
                                          List<PgmRect> out) {
        for (SdmBlock b : blocks) {
            if (b instanceof Table) {
                double[] acc = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                accumulateTableBounds((Table) b, pgm, page0, acc);
                if (acc[2] > acc[0] && acc[3] > acc[1]) {
                    out.add(PgmRect.fromCorners(acc[0], acc[1], acc[2], acc[3]));
                }
            } else if (b instanceof Container) {
                collectTableRects(((Container) b).getChildren(), pgm, page0, out);
            } else if (b instanceof Quote) {
                collectTableRects(((Quote) b).getChildren(), pgm, page0, out);
            } else if (b instanceof Footnote) {
                collectTableRects(((Footnote) b).getChildren(), pgm, page0, out);
            }
        }
    }

    private static void accumulateTableBounds(Table t, PgmModel pgm, int page0, double[] acc) {
        for (TableRow row : t.getRows()) {
            for (TableCell cell : row.getCells()) {
                for (SdmBlock b : cell.getChildren()) {
                    if (b.getId() == null) {
                        continue;
                    }
                    for (PgmBox box : pgm.byId(b.getId())) {
                        if (box.getPage() != page0) {
                            continue;
                        }
                        PgmRect r = box.getRect();
                        acc[0] = Math.min(acc[0], r.getX());
                        acc[1] = Math.min(acc[1], r.getY());
                        acc[2] = Math.max(acc[2], r.getX() + r.getW());
                        acc[3] = Math.max(acc[3], r.getTop());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ rendering

    /** Renders the full page (raster images suppressed) at the given DPI, capped. */
    private static BufferedImage renderPage(Document doc, int pageNum, int dpi) throws Exception {
        Page page = doc.getPages().get(pageNum);
        Rectangle box = PdfPageRenderer.effectiveRenderBox(page);
        double maxSidePt = Math.max(Math.abs(box.getWidth()), Math.abs(box.getHeight()));
        double effDpi = dpi;
        if (maxSidePt * dpi / 72.0 > MAX_RENDER_PX) {
            effDpi = MAX_RENDER_PX * 72.0 / maxSidePt;
        }
        PdfPageRenderer renderer = new PdfPageRenderer();
        renderer.setSuppressRasterImages(true);
        return renderer.renderPage(page, effDpi, effDpi);
    }

    /**
     * Crops the region (content-space PDF points) out of the page render and
     * encodes PNG. The render is visually upright even for a rotated page, so the
     * region's content-space corners are mapped through the SAME rotation the
     * renderer applied ({@code PdfPageRenderer.applyRotation}): for content point
     * {@code (u,v)} (u right of llx, v up from lly), display pixel {@code (px,py)}
     * with {@code s} the true render scale and {@code imgH} the render height is —
     * <pre>
     *   0:   px=u·s              py=imgH-v·s
     *   90:  px=v·s              py=u·s
     *   180: px=(Wc-u)·s         py=v·s
     *   270: px=(Hc-v)·s         py=(Wc-u)·s
     * </pre>
     */
    private static byte[] cropPng(BufferedImage pageRender, Page page, PgmRect region, int rotation) {
        try {
            Rectangle box = PdfPageRenderer.effectiveRenderBox(page);
            double wc = Math.abs(box.getWidth());
            double hc = Math.abs(box.getHeight());
            if (wc <= 0 || hc <= 0) {
                return null;
            }
            int rot = ((rotation % 360) + 360) % 360;
            // The render may have been DPI-capped — recover the true scale from the
            // display width (which is the content HEIGHT for a 90/270 rotation).
            double scale = (rot == 90 || rot == 270)
                    ? pageRender.getWidth() / hc
                    : pageRender.getWidth() / wc;
            double llx = Math.min(box.getLLX(), box.getURX());
            double lly = Math.min(box.getLLY(), box.getURY());
            int imgW = pageRender.getWidth();
            int imgH = pageRender.getHeight();
            double u0 = region.getX() - llx;
            double u1 = region.getX() + region.getW() - llx;
            double v0 = region.getY() - lly;
            double v1 = region.getTop() - lly;
            double[][] uv = {{u0, v0}, {u1, v0}, {u0, v1}, {u1, v1}};
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (double[] c : uv) {
                double u = c[0], v = c[1];
                double xD, yD;
                switch (rot) {
                    case 90:  xD = v * scale;              yD = u * scale;              break;
                    case 180: xD = (wc - u) * scale;       yD = v * scale;              break;
                    case 270: xD = (hc - v) * scale;       yD = (wc - u) * scale;       break;
                    default:  xD = u * scale;              yD = imgH - v * scale;       break;
                }
                minX = Math.min(minX, xD); maxX = Math.max(maxX, xD);
                minY = Math.min(minY, yD); maxY = Math.max(maxY, yD);
            }
            int px = (int) Math.floor(minX);
            int py = (int) Math.floor(minY);
            int pw = (int) Math.ceil(maxX - minX);
            int ph = (int) Math.ceil(maxY - minY);
            px = Math.max(0, Math.min(imgW - 1, px));
            py = Math.max(0, Math.min(imgH - 1, py));
            pw = Math.max(1, Math.min(imgW - px, pw));
            ph = Math.max(1, Math.min(imgH - py, ph));
            BufferedImage crop = pageRender.getSubimage(px, py, pw, ph);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(crop, "png", baos)) {
                return null;
            }
            return baos.toByteArray();
        } catch (Exception e) {
            LOG.fine("vector raster: crop failed: " + e);
            return null;
        }
    }

    // ------------------------------------------------------------ flow edits

    /**
     * Removes text blocks fully inside the rasterized region (their labels are
     * baked into the crop; the words are appended to {@code alt}) and vector
     * Opaque placeholders the crop replaces. Table content is never touched.
     */
    private static void removeCoveredBlocks(SdmDocument sdm, PgmModel pgm, int page0,
                                            PgmRect region, StringBuilder alt) {
        removeCoveredBlocks(sdm.getChildren(), pgm, page0, region, alt, false);
    }

    private static void removeCoveredBlocks(SdmDocument sdm, PgmModel pgm, int page0,
                                            PgmRect region, StringBuilder alt, boolean keepText) {
        removeCoveredBlocks(sdm.getChildren(), pgm, page0, region, alt, keepText);
    }

    private static void removeCoveredBlocks(List<SdmBlock> blocks, PgmModel pgm, int page0,
                                            PgmRect region, StringBuilder alt, boolean keepText) {
        for (java.util.Iterator<SdmBlock> it = blocks.iterator(); it.hasNext(); ) {
            SdmBlock b = it.next();
            if (b instanceof Container) {
                removeCoveredBlocks(((Container) b).getChildren(), pgm, page0, region, alt, keepText);
                continue;
            }
            if (b instanceof Quote) {
                removeCoveredBlocks(((Quote) b).getChildren(), pgm, page0, region, alt, keepText);
                continue;
            }
            if (b instanceof Footnote) {
                removeCoveredBlocks(((Footnote) b).getChildren(), pgm, page0, region, alt, keepText);
                continue;
            }
            if (b instanceof Opaque && ("vector".equals(((Opaque) b).getRenderHint())
                    || "form-xobject".equals(((Opaque) b).getRenderHint()))) {
                if (fractionInside(b, pgm, page0, region) >= VECTOR_INSIDE_FRACTION) {
                    it.remove();
                }
                continue;
            }
            if (b instanceof Figure
                    && !Boolean.TRUE.equals(((Figure) b).getAttributes().get("vector-region"))) {
                // An image figure inside the region is already painted into the
                // crop pixels — keeping the node would show the photo TWICE.
                // (Earlier rasters are never swallowed: vector-region figures skip.)
                if (fractionInside(b, pgm, page0, region) >= TEXT_INSIDE_FRACTION) {
                    it.remove();
                }
                continue;
            }
            if (!keepText && (b instanceof Paragraph || b instanceof Heading)) {
                if (fractionInside(b, pgm, page0, region) >= TEXT_INSIDE_FRACTION) {
                    String txt = b instanceof Paragraph
                            ? ((Paragraph) b).getText() : textOfHeading((Heading) b);
                    if (txt != null && !txt.isEmpty()) {
                        if (alt.length() > 0) {
                            alt.append(' ');
                        }
                        alt.append(txt);
                    }
                    it.remove();
                }
            }
        }
    }

    /** Total characters of TEXT boxes at least half inside the region. */
    private static long coveredTextChars(PgmRect region, List<PgmBox> text) {
        long chars = 0;
        for (PgmBox t : text) {
            if (insideFraction(t.getRect(), java.util.Collections.singletonList(region)) >= 0.5
                    && t.getData() instanceof org.aspose.pdf.pgm.TextBoxData) {
                String s = ((org.aspose.pdf.pgm.TextBoxData) t.getData()).getText();
                if (s != null) {
                    chars += s.trim().length();
                }
            }
        }
        return chars;
    }

    /** Ids of top-level Opaque nodes projecting Form XObjects ({@code Do}). */
    private static java.util.Set<String> collectFormXObjectIds(SdmDocument sdm) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        collectFormXObjectIds(sdm.getChildren(), ids);
        return ids;
    }

    private static void collectFormXObjectIds(List<SdmBlock> blocks, java.util.Set<String> ids) {
        for (SdmBlock b : blocks) {
            if (b instanceof Container) {
                collectFormXObjectIds(((Container) b).getChildren(), ids);
            } else if (b instanceof Opaque
                    && "form-xobject".equals(((Opaque) b).getRenderHint())
                    && b.getId() != null) {
                ids.add(b.getId());
            }
        }
    }

    private static String textOfHeading(Heading h) {
        StringBuilder sb = new StringBuilder();
        for (org.aspose.pdf.sdm.SdmInline in : h.getInline()) {
            if (in instanceof org.aspose.pdf.sdm.Run) {
                sb.append(((org.aspose.pdf.sdm.Run) in).getText());
            }
        }
        return sb.toString();
    }

    /**
     * Fraction of the block's box area (all pages) inside the region on the
     * given page — a block that also has geometry elsewhere scores low and is
     * kept.
     */
    private static double fractionInside(SdmBlock b, PgmModel pgm, int page0, PgmRect region) {
        if (b.getId() == null) {
            return 0;
        }
        double total = 0;
        double inside = 0;
        for (PgmBox box : pgm.byId(b.getId())) {
            PgmRect r = effRect(box.getRect());
            total += r.getW() * r.getH();
            if (box.getPage() == page0) {
                inside += overlapArea(region, r);
            }
        }
        return total > 0 ? inside / total : 0;
    }

    /**
     * Inserts {@code fig} into the flow list holding this page's content, just
     * before the first block whose top edge sits below the figure's top —
     * ReadingOrderNormalizer then finalizes the ordering from the registered
     * geometry.
     */
    /** Package hook (shared with {@link HorizontalRuleEnricher}). */
    static void insertByPosition(SdmDocument sdm, PgmModel pgm, SdmBlock fig,
                                 int page0, double figTop) {
        List<SdmBlock> best = null;
        int bestCount = -1;
        // The flow list that owns most of this page's positioned blocks.
        List<List<SdmBlock>> flows = new ArrayList<>();
        collectFlows(sdm.getChildren(), flows);
        for (List<SdmBlock> flow : flows) {
            int count = 0;
            for (SdmBlock b : flow) {
                if (pageOf(b, pgm) == page0) {
                    count++;
                }
            }
            if (count > bestCount) {
                bestCount = count;
                best = flow;
            }
        }
        if (best == null) {
            sdm.getChildren().add(fig);
            return;
        }
        int insertAt = -1;
        int lastSamePage = -1;
        for (int i = 0; i < best.size(); i++) {
            SdmBlock b = best.get(i);
            if (pageOf(b, pgm) != page0) {
                continue;
            }
            lastSamePage = i;
            double top = topOf(b, pgm, page0);
            if (!Double.isNaN(top) && top < figTop) {
                insertAt = i;
                break;
            }
        }
        if (insertAt >= 0) {
            best.add(insertAt, fig);
        } else if (lastSamePage >= 0) {
            best.add(lastSamePage + 1, fig);
        } else {
            best.add(fig);
        }
    }

    private static void collectFlows(List<SdmBlock> blocks, List<List<SdmBlock>> out) {
        out.add(blocks);
        for (SdmBlock b : blocks) {
            if (b instanceof Container) {
                collectFlows(((Container) b).getChildren(), out);
            } else if (b instanceof Quote) {
                collectFlows(((Quote) b).getChildren(), out);
            } else if (b instanceof Footnote) {
                collectFlows(((Footnote) b).getChildren(), out);
            }
        }
    }

    private static int pageOf(SdmBlock b, PgmModel pgm) {
        if (b.getId() == null) {
            return -1;
        }
        for (PgmBox box : pgm.byId(b.getId())) {
            return box.getPage();
        }
        return -1;
    }

    private static double topOf(SdmBlock b, PgmModel pgm, int page0) {
        if (b.getId() == null) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (PgmBox box : pgm.byId(b.getId())) {
            if (box.getPage() != page0) {
                continue;
            }
            double t = box.getRect().getTop();
            if (Double.isNaN(top) || t > top) {
                top = t;
            }
        }
        return top;
    }
}
