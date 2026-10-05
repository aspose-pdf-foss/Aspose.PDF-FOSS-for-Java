package org.aspose.pdf.sdm.enrich;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.imageio.ImageIO;

import org.aspose.pdf.Document;
import org.aspose.pdf.ImagePlacement;
import org.aspose.pdf.ImagePlacementAbsorber;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmIds;

/**
 * Recovers RASTER images that the shallow {@link org.aspose.pdf.sdm.reader.PdfSdmReader}
 * misses — images nested inside Form XObjects and inline images whose pixels the
 * reader did not attach — so they are not lost from the STRUCTURAL HTML.
 *
 * <p>The Stage-1 reader emits a page's Form XObjects as opaque nodes without
 * entering them, and creates inline-image figures with no pixel data. The
 * FIXED_LAYOUT converter instead uses {@link ImagePlacementAbsorber}, which
 * descends into forms and decodes inline images — so a cover collage or a
 * form-wrapped photo shows in FIXED but vanishes in STRUCTURAL. This pass runs
 * the same absorber and adds a {@link Figure} (rasterized to PNG, sized to its
 * on-page footprint) for every placement not already represented by an image
 * figure, inserted into reading order by vertical position.</p>
 *
 * <p>Kill switch: {@code -Dsdm.recoverImages=false}.</p>
 */
public final class ImagePlacementEnricher {

    private static final Logger LOG = Logger.getLogger(ImagePlacementEnricher.class.getName());
    private static final UUID NS = UUID.nameUUIDFromBytes("sdm-imgabs".getBytes());
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("sdm.recoverImages", "true"));

    private ImagePlacementEnricher() {
    }

    /**
     * @param doc the open source document (for the image absorber)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with
     */
    public static void enrich(Document doc, SdmDocument sdm, PgmModel pgm) {
        if (!ENABLED || doc == null || sdm == null || pgm == null) {
            return;
        }
        int pageCount;
        try {
            pageCount = doc.getPages().getCount();
        } catch (Exception e) {
            return;
        }
        long seq = 0;
        int added = 0;
        for (int pi = 1; pi <= pageCount; pi++) {
            List<PgmRect> existing = existingImageRects(sdm, pgm, pi - 1);
            List<ImagePlacement> placements;
            try {
                Page page = doc.getPages().get(pi);
                ImagePlacementAbsorber abs = new ImagePlacementAbsorber();
                page.accept(abs);
                placements = abs.getImagePlacements();
            } catch (Exception e) {
                LOG.fine("image absorb failed on page " + pi + ": " + e);
                continue;
            }
            for (ImagePlacement ip : placements) {
                Rectangle r = ip.getRectangle();
                if (r == null || r.getWidth() < 3 || r.getHeight() < 3) {
                    continue;
                }
                if (coveredBy(existing, r)) {
                    continue; // already represented by an image figure the reader found
                }
                byte[] png = rasterize(ip);
                if (png == null) {
                    continue;
                }
                ResourceRef ref = sdm.getResources().put(
                        "imgabs:" + pi + ":" + seq, new Resource(Resource.Kind.IMAGE, png, "image/png"));
                Figure fig = new Figure(ref);
                fig.setId(SdmIds.sessionNodeId(NS, "imgabs", seq++));
                fig.getAttributes().put("display-width", r.getWidth());
                fig.getAttributes().put("display-height", r.getHeight());
                // If the placement sits inside a recognised table cell, put the
                // image INTO that cell (a product-catalogue icon column) instead of
                // dropping it as a stray figure after the table.
                if (!placeInTableCell(sdm, fig, pi - 1, r)) {
                    insertByPosition(sdm, pgm, fig, pi - 1, r.getURY());
                }
                existing.add(PgmRect.fromCorners(r.getLLX(), r.getLLY(), r.getURX(), r.getURY()));
                added++;
            }
        }
        final int fAdded = added;
        LOG.fine(() -> "ImagePlacementEnricher: recovered " + fAdded + " image(s)");

        // Sweep pre-existing top-level image figures (emitted by the shallow reader)
        // into any table cell whose recorded bounds contain them, so an icon column
        // shows the icon IN the cell rather than as a stray figure after the table.
        for (int i = 0; i < sdm.getChildren().size(); i++) {
            SdmBlock b = sdm.getChildren().get(i);
            if (!(b instanceof Figure)) {
                continue;
            }
            Figure f = (Figure) b;
            if (f.getImage() == null || f.getId() == null) {
                continue;
            }
            PgmRect box = null;
            int page0 = -1;
            for (PgmBox bx : pgm.byId(f.getId())) {
                if (bx.getKind() == PgmBoxKind.IMAGE) {
                    box = bx.getRect();
                    page0 = bx.getPage();
                    break;
                }
            }
            if (box == null) {
                continue;
            }
            Rectangle r = new Rectangle(box.getX(), box.getY(),
                    box.getX() + box.getW(), box.getY() + box.getH());
            if (placeInTableCell(sdm, f, page0, r)) {
                sdm.getChildren().remove(i);
                i--;
            }
        }
    }

    /**
     * If the image placement's centre falls inside a recognised table cell on this
     * page (matched via the cell's recorded {@code cell-bounds}), inserts the image
     * figure as the first child of that cell and returns true.
     */
    private static boolean placeInTableCell(SdmDocument sdm, Figure fig, int page0, Rectangle r) {
        double cx = (r.getLLX() + r.getURX()) / 2;
        double cy = (r.getLLY() + r.getURY()) / 2;
        for (SdmBlock b : sdm.getChildren()) {
            if (!(b instanceof Table)) {
                continue;
            }
            Table t = (Table) b;
            Object pg = t.getAttributes().get("table-page");
            if (!(pg instanceof Integer) || (Integer) pg != page0) {
                continue;
            }
            for (TableRow row : t.getRows()) {
                for (TableCell cell : row.getCells()) {
                    Object cb = cell.getAttributes().get("cell-bounds");
                    if (!(cb instanceof double[])) {
                        continue;
                    }
                    double[] bb = (double[]) cb;
                    double lx = Math.min(bb[0], bb[2]);
                    double rx = Math.max(bb[0], bb[2]);
                    double by = Math.min(bb[1], bb[3]);
                    double ty = Math.max(bb[1], bb[3]);
                    if (cx >= lx && cx <= rx && cy >= by && cy <= ty) {
                        cell.getChildren().add(0, fig);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Rects (PDF points) of images the reader already captured on the given page. */
    private static List<PgmRect> existingImageRects(SdmDocument sdm, PgmModel pgm, int page0) {
        List<PgmRect> out = new ArrayList<>();
        for (SdmBlock b : sdm.getChildren()) {
            if (b.getId() == null) {
                continue;
            }
            for (PgmBox box : pgm.byId(b.getId())) {
                if (box.getKind() == PgmBoxKind.IMAGE && box.getPage() == page0) {
                    out.add(box.getRect());
                }
            }
        }
        return out;
    }

    /** True when the placement overlaps an existing image rect by &gt;50% of the smaller area. */
    private static boolean coveredBy(List<PgmRect> existing, Rectangle r) {
        double ra = r.getWidth() * r.getHeight();
        for (PgmRect e : existing) {
            double ox = Math.min(e.getX() + e.getW(), r.getURX()) - Math.max(e.getX(), r.getLLX());
            double oy = Math.min(e.getY() + e.getH(), r.getURY()) - Math.max(e.getY(), r.getLLY());
            if (ox > 0 && oy > 0) {
                double inter = ox * oy;
                double ea = e.getW() * e.getH();
                if (inter >= 0.5 * Math.min(ra, ea)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] rasterize(ImagePlacement ip) {
        try {
            BufferedImage bi = ip.getImage().toBufferedImage();
            if (bi == null) {
                return null;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            if (!ImageIO.write(bi, "png", baos)) {
                return null;
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Inserts {@code fig} into the flat block list just before the first block on
     * the same page whose top edge sits below the figure's top edge (reading
     * order top-to-bottom); if there is none, after the last block of that page.
     */
    private static void insertByPosition(SdmDocument sdm, PgmModel pgm, Figure fig,
                                         int page0, double figTop) {
        List<SdmBlock> children = sdm.getChildren();
        int insertAt = -1;
        int lastSamePage = -1;
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            int bp = pageOf(b, pgm);
            if (bp != page0) {
                continue;
            }
            lastSamePage = i;
            double bTop = topYOf(b, pgm);
            if (!Double.isNaN(bTop) && bTop < figTop) {
                insertAt = i;
                break;
            }
        }
        if (insertAt >= 0) {
            children.add(insertAt, fig);
        } else if (lastSamePage >= 0) {
            children.add(lastSamePage + 1, fig);
        } else {
            children.add(fig);
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

    private static double topYOf(SdmBlock b, PgmModel pgm) {
        if (b.getId() == null) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (PgmBox box : pgm.byId(b.getId())) {
            double t = box.getRect().getTop();
            if (Double.isNaN(top) || t > top) {
                top = t;
            }
        }
        return top;
    }
}
