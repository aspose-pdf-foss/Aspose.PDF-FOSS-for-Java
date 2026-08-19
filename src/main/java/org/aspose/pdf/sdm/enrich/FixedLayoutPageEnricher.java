package org.aspose.pdf.sdm.enrich;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
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
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmIds;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Fixed-layout page handling for FLOW targets (DOCX).
 *
 * <p>A poster/map/worksheet page — dozens of small scattered labels over
 * page-filling artwork — has no meaningful reading flow: emitting its text as
 * flowing paragraphs glues unrelated captions into mush, and emitting the
 * artwork inline destroys pagination. Such pages are the DOCX analogue of the
 * FIXED_LAYOUT HTML mode, and this enricher reproduces that recipe:</p>
 *
 * <ol>
 *   <li>the page is rendered once with <b>text suppressed</b> and anchored
 *       BEHIND the flow as a full-page underlay (artwork, frames, photos —
 *       no glyphs, so the positioned text is not doubled);</li>
 *   <li>every TEXT box is re-emitted as its own absolutely positioned
 *       paragraph ({@code abs-x/abs-y/abs-w} attributes &rarr; Word
 *       {@code w:framePr} frames), so the labels sit exactly where the
 *       source put them and stay editable;</li>
 *   <li>the page's flow blocks (the glued paragraphs, opaques, figures now
 *       baked into the underlay) are removed.</li>
 * </ol>
 *
 * <p>Ordinary text pages are untouched — see {@link #isScatteredLayout}.</p>
 */
public final class FixedLayoutPageEnricher {

    private static final Logger LOG = Logger.getLogger(FixedLayoutPageEnricher.class.getName());

    private static final UUID NS = UUID.nameUUIDFromBytes("sdm-fixedpage".getBytes());

    /** Minimum scattered TEXT boxes for the poster classification. */
    private static final int MIN_TEXT_BOXES = 12;
    /** Median text-box width must be under this fraction of the page width. */
    private static final double MAX_MEDIAN_WIDTH_FRACTION = 0.30;
    /** Total text area must be under this fraction of the page area. */
    private static final double MAX_TEXT_AREA_FRACTION = 0.20;
    /** Non-text ink (images/vector/forms) must cover at least this fraction. */
    private static final double MIN_INK_AREA_FRACTION = 0.30;
    /** Underlay render resolution. */
    private static final int UNDERLAY_DPI = 144;
    /** Cap on the rendered page raster's larger side (px). */
    private static final int MAX_RENDER_PX = 6000;

    private FixedLayoutPageEnricher() {
        // static entry only
    }

    /**
     * Converts every scattered-layout page to underlay + positioned text.
     *
     * @param doc the open source document (for rendering)
     * @param sdm the SDM document (mutated in place)
     * @param pgm the geometry the SDM was read with
     * @return the 0-based indexes of pages converted to fixed layout (the
     *         vector-raster and rule passes must skip these)
     */
    public static Set<Integer> enrich(Document doc, SdmDocument sdm, PgmModel pgm) {
        Set<Integer> fixedPages = new HashSet<>();
        if (doc == null || sdm == null || pgm == null) {
            return fixedPages;
        }
        long seq = 0;
        for (int page0 = 0; page0 < pgm.getPages().size(); page0++) {
            PgmPage pp = pgm.getPage(page0);
            if (pp == null) {
                continue;
            }
            // A page whose text is UNIFORMLY rotated ±90° (a landscape drawing
            // sheet authored sideways) is unreadable to the flow projection —
            // its "paragraphs" are vertical strips. Re-emit the text in the
            // rotated reading order as ordinary flow paragraphs over an
            // art-only underlay, instead of baking it into pixels.
            int rot = uniformTextRotation(pp);
            if (rot == 0 && !isScatteredLayout(pp)) {
                continue;
            }
            byte[] png;
            try {
                // Rotated pages carry their text as FLOW paragraphs, so the
                // underlay must not keep the rotated glyph pixels (no doubling).
                png = renderUnderlay(doc, page0 + 1, rot == 0);
            } catch (Exception e) {
                LOG.fine("fixed-layout underlay render failed p" + (page0 + 1) + ": " + e);
                continue;
            }
            if (png == null) {
                continue;
            }
            fixedPages.add(page0);
            double pageH = pp.getHeight();
            double pageW = pp.getWidth();

            // Remove the page's flow blocks; remember where they sat so the
            // replacement content lands in the same inter-page position, and
            // keep the removed paragraphs' run styles — the positioned frames
            // must inherit colour/highlight (e.g. an annotation tint applied by
            // RedactionHighlightEnricher), not reset to a plain style.
            List<SdmBlock> removed = new ArrayList<>();
            int insertAt = removePageBlocks(sdm, pgm, page0, removed);
            java.util.Map<String, TextStyle> styleById = new java.util.HashMap<>();
            for (SdmBlock rb : removed) {
                if (rb.getId() == null) {
                    continue;
                }
                List<org.aspose.pdf.sdm.SdmInline> inline = rb instanceof Paragraph
                        ? ((Paragraph) rb).getInline()
                        : rb instanceof Heading ? ((Heading) rb).getInline() : null;
                if (inline != null) {
                    for (org.aspose.pdf.sdm.SdmInline in : inline) {
                        if (in instanceof Run && ((Run) in).getStyle() != null) {
                            styleById.put(rb.getId(), ((Run) in).getStyle());
                            break;
                        }
                    }
                }
            }

            List<SdmBlock> replacement = new ArrayList<>();
            ResourceRef ref = sdm.getResources().put("fixedpage:" + page0,
                    new Resource(Resource.Kind.IMAGE, png, "image/png"));
            Figure underlay = new Figure(ref);
            String uid = SdmIds.sessionNodeId(NS, "fixedpage", seq++);
            underlay.setId(uid);
            underlay.getAttributes().put("display-width", pageW);
            underlay.getAttributes().put("display-height", pageH);
            underlay.getAttributes().put("page-underlay", Boolean.TRUE);
            underlay.getAttributes().put("abs-page", page0);
            PgmBox ubox = new PgmBox(uid, page0, new PgmRect(0, 0, pageW, pageH), 0,
                    PgmBoxKind.IMAGE, null);
            pp.getBoxes().add(ubox);
            pgm.indexBox(ubox);
            replacement.add(underlay);

            // One positioned paragraph per HORIZONTAL text box, top-to-bottom
            // then left-to-right. Rotated labels stay painted in the underlay
            // (Word frames cannot hold diagonal text) — no frame for them.
            // On a uniformly-rotated page ALL text is taken, mapped into the
            // upright reading space, and emitted as flow paragraphs instead.
            List<PgmBox> text = new ArrayList<>();
            for (PgmBox b : pp.getBoxes()) {
                if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData
                        && (rot != 0 || ((TextBoxData) b.getData()).getRotation() == 0)
                        && !((TextBoxData) b.getData()).getText().trim().isEmpty()) {
                    text.add(rot != 0 ? uprightCopy(b, rot, pp.getWidth(), pp.getHeight()) : b);
                }
            }
            text.sort((a, b) -> {
                int c = Double.compare(b.getRect().getTop(), a.getRect().getTop());
                return c != 0 ? c : Double.compare(a.getRect().getX(), b.getRect().getX());
            });
            // Double-printed text (a footer drawn twice coincides pixel-perfectly
            // in the PDF, but two frames drift apart in Word and smear) — one
            // frame per (text, position).
            Set<String> seen = new HashSet<>();
            List<PgmBox> unique = new ArrayList<>();
            for (PgmBox b : text) {
                TextBoxData td = (TextBoxData) b.getData();
                String key = td.getText().trim() + '@' + Math.round(b.getRect().getX() / 2)
                        + ':' + Math.round(b.getRect().getTop() / 2);
                if (seen.add(key)) {
                    unique.add(b);
                }
            }
            // PGM splits a line at kerning gaps — for tiny fine print even
            // MID-WORD ("…without author" + "ization from…"). The pieces abut
            // pixel-perfectly in the PDF, but as separate frames they drift
            // under Word's substituted font metrics and smear over each other.
            // Rejoin same-baseline neighbours whose gap is within a couple of
            // glyphs; genuinely separate columns (big gaps) stay apart.
            for (List<PgmBox> line : joinLineFragments(unique)) {
                PgmBox first = line.get(0);
                TextBoxData td = (TextBoxData) first.getData();
                StringBuilder joined = new StringBuilder();
                double prevRight = Double.NaN;
                PgmRect union = null;
                for (PgmBox piece : line) {
                    TextBoxData pd = (TextBoxData) piece.getData();
                    PgmRect r = piece.getRect();
                    if (joined.length() > 0) {
                        // A visible gap is a word break; a hairline gap is a
                        // mid-word split — join without a space.
                        double gap = r.getX() - prevRight;
                        if (gap > 0.27 * Math.max(1, pd.getFontSize())) {
                            joined.append(' ');
                        }
                    }
                    joined.append(pd.getText());
                    prevRight = r.getX() + r.getW();
                    union = union == null ? r : PgmRect.fromCorners(
                            Math.min(union.getX(), r.getX()), Math.min(union.getY(), r.getY()),
                            Math.max(union.getX() + union.getW(), r.getX() + r.getW()),
                            Math.max(union.getTop(), r.getTop()));
                }
                // Inherit the source run's style (colour, highlight, bold) when
                // the owning paragraph is known; fill font facts from the box.
                TextStyle source = first.getId() != null ? styleById.get(first.getId()) : null;
                TextStyle style = source != null ? source : new TextStyle();
                if (style.getFontFamily() == null && td.getFontName() != null
                        && !td.getFontName().isEmpty()) {
                    style.setFontFamily(stripSubsetPrefix(td.getFontName()));
                }
                if (style.getFontSize() <= 0 && td.getFontSize() > 0) {
                    style.setFontSize(td.getFontSize());
                }
                Paragraph p = new Paragraph();
                p.getInline().add(new Run(joined.toString(), style));
                String pid = SdmIds.sessionNodeId(NS, "fixedtext", seq++);
                p.setId(pid);
                if (rot == 0) {
                    p.getAttributes().put("abs-x", union.getX());
                    p.getAttributes().put("abs-y", pageH - union.getTop()); // from page TOP
                    p.getAttributes().put("abs-w", union.getW());
                    p.getAttributes().put("abs-page", page0);
                }
                // Rotated pages: plain FLOW paragraphs in upright reading order
                // (frame positions would be sideways-page coordinates — useless
                // on a portrait Word page).
                replacement.add(p);
            }
            sdm.getChildren().addAll(Math.min(insertAt, sdm.getChildren().size()), replacement);
            final int n = text.size();
            final int pnum = page0 + 1;
            LOG.fine(() -> "fixed-layout page p" + pnum + ": underlay + " + n + " positioned labels");
        }
        return fixedPages;
    }

    /**
     * Groups boxes (sorted top-to-bottom, then left-to-right) into joined line
     * fragments: same-baseline neighbours whose horizontal gap is at most about
     * two glyph widths merge into one fragment run; bigger gaps (separate
     * columns) and different baselines start a new fragment.
     */
    private static List<List<PgmBox>> joinLineFragments(List<PgmBox> boxes) {
        List<List<PgmBox>> out = new ArrayList<>();
        for (PgmBox b : boxes) {
            List<PgmBox> current = out.isEmpty() ? null : out.get(out.size() - 1);
            if (current != null) {
                PgmBox prev = current.get(current.size() - 1);
                double fs = Math.max(4, ((TextBoxData) b.getData()).getFontSize());
                boolean sameLine = Math.abs(prev.getRect().getTop() - b.getRect().getTop())
                        <= Math.max(2, 0.4 * fs);
                double gap = b.getRect().getX() - (prev.getRect().getX() + prev.getRect().getW());
                if (sameLine && gap >= -2 && gap <= 2.0 * fs) {
                    current.add(b);
                    continue;
                }
            }
            List<PgmBox> line = new ArrayList<>();
            line.add(b);
            out.add(line);
        }
        return out;
    }

    /**
     * True for a page whose text is scattered small labels over page-filling
     * artwork — a poster/map/worksheet, not a flowing text page.
     */
    static boolean isScatteredLayout(PgmPage pp) {
        double pageArea = pp.getWidth() * pp.getHeight();
        if (pageArea <= 0) {
            return false;
        }
        List<Double> widths = new ArrayList<>();
        double textArea = 0;
        double inkArea = 0;
        for (PgmBox b : pp.getBoxes()) {
            PgmRect r = b.getRect();
            double area = Math.max(0, r.getW()) * Math.max(0, r.getH());
            if (b.getKind() == PgmBoxKind.TEXT) {
                widths.add(r.getW());
                textArea += area;
            } else if (b.getKind() == PgmBoxKind.IMAGE || b.getKind() == PgmBoxKind.VECTOR
                    || b.getKind() == PgmBoxKind.UNKNOWN) {
                inkArea += Math.min(area, pageArea); // a full-page form counts once
            }
        }
        if (widths.size() < MIN_TEXT_BOXES) {
            return false;
        }
        widths.sort(Double::compare);
        double medianW = widths.get(widths.size() / 2);
        return medianW < MAX_MEDIAN_WIDTH_FRACTION * pp.getWidth()
                && textArea < MAX_TEXT_AREA_FRACTION * pageArea
                && inkArea >= MIN_INK_AREA_FRACTION * pageArea;
    }

    /** Removes the page's top-level blocks; returns the index of the first removed. */
    private static int removePageBlocks(SdmDocument sdm, PgmModel pgm, int page0,
            List<SdmBlock> removed) {
        int first = sdm.getChildren().size();
        int i = 0;
        for (Iterator<SdmBlock> it = sdm.getChildren().iterator(); it.hasNext(); i++) {
            SdmBlock b = it.next();
            if (!(b instanceof Paragraph || b instanceof Heading || b instanceof Opaque
                    || b instanceof Figure)) {
                continue; // tables/lists (rare on posters) stay as recognized
            }
            if (pageOf(b, pgm) == page0) {
                removed.add(b);
                it.remove();
                if (i < first) {
                    first = i;
                }
                i--; // account for removal
            }
        }
        return first;
    }

    /** The page that owns most of the block's geometry, or -1 when unknown. */
    private static int pageOf(SdmBlock b, PgmModel pgm) {
        if (b.getId() == null) {
            return -1;
        }
        int best = -1;
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (PgmBox box : pgm.byId(b.getId())) {
            counts.merge(box.getPage(), 1, Integer::sum);
        }
        int bestCount = 0;
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static byte[] renderUnderlay(Document doc, int pageNum, boolean keepRotatedGlyphs)
            throws Exception {
        Page page = doc.getPages().get(pageNum);
        Rectangle box = PdfPageRenderer.effectiveRenderBox(page);
        double maxSidePt = Math.max(Math.abs(box.getWidth()), Math.abs(box.getHeight()));
        double effDpi = UNDERLAY_DPI;
        if (maxSidePt * effDpi / 72.0 > MAX_RENDER_PX) {
            effDpi = MAX_RENDER_PX * 72.0 / maxSidePt;
        }
        PdfPageRenderer renderer = new PdfPageRenderer();
        renderer.setSuppressText(true); // positioned frames carry the text...
        // ...except rotated labels on a NORMAL poster page: Word frames are
        // horizontal-only, so diagonal text stays visible in the pixels. A
        // uniformly-rotated page carries ALL its text as flow paragraphs, so
        // there the glyphs must vanish from the underlay (no doubling).
        renderer.setSuppressTextKeepRotated(keepRotatedGlyphs);
        java.awt.image.BufferedImage img = renderer.renderPage(page, effDpi, effDpi);
        if (img == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        return ImageIO.write(img, "png", bos) ? bos.toByteArray() : null;
    }

    /** Share of a page's rotation among its text (char-weighted): returns 90 or
     *  270 when at least 80% of the characters are rotated that way, else 0. */
    private static int uniformTextRotation(PgmPage pp) {
        long total = 0;
        long rot90 = 0;
        long rot270 = 0;
        long rot180 = 0;
        for (PgmBox b : pp.getBoxes()) {
            if (b.getKind() != PgmBoxKind.TEXT || !(b.getData() instanceof TextBoxData)) {
                continue;
            }
            TextBoxData td = (TextBoxData) b.getData();
            String t = td.getText();
            int n = t == null ? 0 : t.trim().length();
            total += n;
            double r = ((td.getRotation() % 360) + 360) % 360;
            if (Math.abs(r - 90) < 1) {
                rot90 += n;
            } else if (Math.abs(r - 270) < 1) {
                rot270 += n;
            } else if (Math.abs(r - 180) < 1) {
                rot180 += n;
            }
        }
        if (total < 20) {
            return 0; // not enough signal
        }
        if (rot90 >= 0.8 * total) {
            return 90;
        }
        if (rot270 >= 0.8 * total) {
            return 270;
        }
        if (rot180 >= 0.8 * total) {
            return 180; // an upside-down page
        }
        return 0;
    }

    /**
     * A copy of a text box with its rectangle mapped into the UPRIGHT reading
     * space of a uniformly rotated page, so the standard top-down/left-right
     * sorting and line joining read the sideways sheet in its natural order.
     * For +90° glyphs (baseline runs up the page) the upright view is the page
     * rotated clockwise: {@code (x,y) → (y, W-x)}; for 270° the mirror image.
     */
    private static PgmBox uprightCopy(PgmBox b, int rot, double pageW, double pageH) {
        PgmRect r = b.getRect();
        PgmRect t;
        if (rot == 90) {
            t = new PgmRect(r.getY(), pageW - r.getX() - r.getW(), r.getH(), r.getW());
        } else if (rot == 270) {
            t = new PgmRect(pageH - r.getY() - r.getH(), r.getX(), r.getH(), r.getW());
        } else { // 180 — upside down: mirror both axes, dimensions unchanged
            t = new PgmRect(pageW - r.getX() - r.getW(), pageH - r.getY() - r.getH(),
                    r.getW(), r.getH());
        }
        PgmBox copy = new PgmBox(b.getId(), b.getPage(), t, b.getZ(), b.getKind(), null);
        copy.setData(b.getData());
        return copy;
    }

    private static String stripSubsetPrefix(String name) {
        return name.length() > 7 && name.charAt(6) == '+' ? name.substring(7) : name;
    }
}
