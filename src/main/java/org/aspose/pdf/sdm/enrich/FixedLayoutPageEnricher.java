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
import org.aspose.pdf.sdm.Container;
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
    /**
     * Total text area must be under this fraction of the page area. Forms carry
     * more label text than a sparse map/poster yet are just as fixed-layout — a
     * flow reflow fragments them into hundreds of stacked vector rasters and
     * explodes the page count. The small-median-width and high-ink guards keep
     * ordinary prose (wide lines, little non-text ink) out.
     */
    private static final double MAX_TEXT_AREA_FRACTION = 0.32;
    /**
     * A median text-box width below this fraction of the page width is such a
     * strong "scattered labels" signal (thin form fields / worksheet cells, e.g.
     * 0.05 of the page) that the ink guard is waived: many forms carry only
     * hairline rules, far under {@link #MIN_INK_AREA_FRACTION}, yet reflow
     * shreds their labels into hundreds of stacked rasters and explodes the page
     * count. Ordinary prose (median ~0.7) and multi-column text (~0.4) stay well
     * above this, so they remain flow.
     */
    private static final double STRONG_LABEL_WIDTH_FRACTION = 0.15;
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
        return enrich(doc, sdm, pgm, false);
    }

    /**
     * Converts scattered-layout pages to underlay + positioned text, or — when
     * {@code forceAll} is set — EVERY page.
     *
     * <p>{@code forceAll} is the {@link org.aspose.pdf.DocSaveOptions.RecognitionMode#Textbox}
     * ("each element in a textbox") mode: a fully fixed-layout DOCX, the exact
     * analogue of the FIXED_LAYOUT HTML export. Every page becomes a
     * text-suppressed full-page raster underlay plus one positioned frame per
     * text line, so pages a flow reflow mangles — multi-column newsletters,
     * non-extractable symbol/Type&nbsp;3 fonts (their glyphs ride the underlay
     * instead of extracting as garbage), vector art and annotation shapes — are
     * reproduced pixel-faithfully instead of exploding, collapsing or printing
     * mojibake. The default {@link org.aspose.pdf.DocSaveOptions.RecognitionMode#Flow}
     * keeps {@code forceAll} false and only fixes genuine poster/form pages.</p>
     *
     * @param doc      the open source document (for rendering)
     * @param sdm      the SDM document (mutated in place)
     * @param pgm      the geometry the SDM was read with
     * @param forceAll true = fix every renderable page (Textbox mode)
     * @return the 0-based indexes of pages converted to fixed layout
     */
    public static Set<Integer> enrich(Document doc, SdmDocument sdm, PgmModel pgm,
            boolean forceAll) {
        Set<Integer> fixedPages = new HashSet<>();
        if (doc == null || sdm == null || pgm == null) {
            return fixedPages;
        }
        long seq = 0;
        // A document whose pages are ALL uniformly rotated the same way (a
        // landscape sheet authored sideways — e.g. a wide data table under
        // /Rotate 90) can be re-emitted as a fixed-layout LANDSCAPE page: the
        // text mapped upright is anchored in positioned frames (preserving the
        // 2-D table), instead of being linearised into a single flow column
        // that explodes the page count. Mixed-orientation documents keep the
        // old flow path, because one Word section carries a single orientation.
        int docRot = documentUniformRotation(pgm);
        double[] landscapeSize = null; // {width,height} to stamp once, if applied
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
            if (!forceAll && rot == 0 && !isScatteredLayout(pp)) {
                continue;
            }
            // A page whose content is a genuine DATA table (a dense grid of
            // text-filled cells covering most of the page's text) trips the
            // scattered-labels heuristic when its columns are narrow, but it is
            // not a poster/form: posterizing it would flatten the recognized
            // table to positioned text and lose the grid for the flow/spreadsheet
            // targets. Forms are excluded because their cells are mostly empty
            // (unfilled fields). Keep such pages in the flow with their table.
            if (!forceAll && rot == 0 && tableDominatesPage(sdm, pgm, page0, pp)) {
                continue;
            }
            // Type 3 / symbol fonts with no /ToUnicode extract as mojibake (their
            // codes map only to drawing procedures). Like the FIXED_LAYOUT HTML
            // export, keep those glyphs painted in the underlay (as graphics) and
            // skip them from the positioned frames, so the page shows real marks
            // instead of scattered garbage on top of a text-suppressed underlay
            // (43255: body font "R9").
            java.util.Set<String> type3NoUni;
            try {
                type3NoUni = org.aspose.pdf.html.PdfToHtmlConverter
                        .type3NoUnicodeFontNames(doc.getPages().get(page0 + 1));
            } catch (Exception e) {
                type3NoUni = java.util.Collections.emptySet();
            }
            byte[] png;
            try {
                // Rotated pages carry their text as FLOW paragraphs, so the
                // underlay must not keep the rotated glyph pixels (no doubling).
                png = renderUnderlay(doc, page0 + 1, rot == 0, !type3NoUni.isEmpty());
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
            // Upright (display) page dimensions. A ±90° page in a uniformly
            // rotated document shows as landscape, so the art-only underlay
            // raster (rendered with /Rotate applied) and the frame coordinate
            // space swap width/height. Upright pages are unchanged.
            boolean landscape = docRot != 0 && (rot == 90 || rot == 270);
            double dispW = landscape ? pageH : pageW;
            double dispH = landscape ? pageW : pageH;
            if (landscape && landscapeSize == null) {
                landscapeSize = new double[]{dispW, dispH};
            }

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
            underlay.getAttributes().put("display-width", dispW);
            underlay.getAttributes().put("display-height", dispH);
            underlay.getAttributes().put("page-underlay", Boolean.TRUE);
            underlay.getAttributes().put("abs-page", page0);
            PgmBox ubox = new PgmBox(uid, page0, new PgmRect(0, 0, dispW, dispH), 0,
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
                        && !((TextBoxData) b.getData()).getText().trim().isEmpty()
                        // Non-extractable font: its mojibake stays in the underlay
                        // as painted glyphs, never a garbage frame.
                        && !type3NoUni.contains(((TextBoxData) b.getData()).getFontName())) {
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
                // Positioned frame when the page is upright (rot==0) OR the
                // whole document is uniformly rotated and this page's text has
                // been mapped into its upright landscape space (docRot!=0):
                // either way `union` is in the display coordinate system, so
                // anchor the frame there (dispH==pageH when not landscape).
                if (rot == 0 || docRot != 0) {
                    p.getAttributes().put("abs-x", union.getX());
                    p.getAttributes().put("abs-y", dispH - union.getTop()); // from page TOP
                    p.getAttributes().put("abs-w", union.getW());
                    p.getAttributes().put("abs-page", page0);
                }
                // Rotated pages in a MIXED-orientation document: plain FLOW
                // paragraphs in upright reading order (a single Word section
                // cannot mix orientations, so no landscape frame here).
                replacement.add(p);
            }
            sdm.getChildren().addAll(Math.min(insertAt, sdm.getChildren().size()), replacement);
            final int n = text.size();
            final int pnum = page0 + 1;
            LOG.fine(() -> "fixed-layout page p" + pnum + ": underlay + " + n + " positioned labels");
        }
        // A uniformly rotated document is emitted as landscape frames above, so
        // the DOCX/HTML page geometry (taken from the source MediaBox, which
        // ignores /Rotate) must be swapped to the display orientation — else the
        // wide frames would overflow a portrait page.
        if (landscapeSize != null && sdm.getMetadata() != null) {
            sdm.getMetadata().getCustom().put("page-width",
                    String.format(java.util.Locale.ROOT, "%.2f", landscapeSize[0]));
            sdm.getMetadata().getCustom().put("page-height",
                    String.format(java.util.Locale.ROOT, "%.2f", landscapeSize[1]));
        }
        return fixedPages;
    }

    /**
     * The common uniform text rotation shared by EVERY page of the document, or
     * 0 unless all pages carry enough text and agree on the same ±90/180°
     * rotation. Gates the landscape fixed-layout path: only a wholly-rotated
     * document can become a single-orientation Word section without crushing
     * some pages.
     */
    private static int documentUniformRotation(PgmModel pgm) {
        int common = -1;
        for (int i = 0; i < pgm.getPages().size(); i++) {
            PgmPage pp = pgm.getPage(i);
            if (pp == null) {
                continue;
            }
            int r = uniformTextRotation(pp);
            if (r == 0) {
                return 0;
            }
            if (common == -1) {
                common = r;
            } else if (common != r) {
                return 0;
            }
        }
        return common == -1 ? 0 : common;
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
        widths.sort(Double::compare);
        double medianW = widths.isEmpty() ? 0 : widths.get(widths.size() / 2);
        // Base classification: enough narrow labels covering little of the page.
        boolean scatteredLabels = widths.size() >= MIN_TEXT_BOXES
                && medianW < MAX_MEDIAN_WIDTH_FRACTION * pp.getWidth()
                && textArea < MAX_TEXT_AREA_FRACTION * pageArea;
        // The ink guard rejects sparse prose, but a VERY narrow median is already
        // an unambiguous form/worksheet signal — such pages carry only hairline
        // rules (ink well under the guard) yet must be fixed-layout, or a reflow
        // stacks their fields into a phantom page explosion. So waive the ink
        // requirement for strong-label pages; keep it for the borderline ones.
        boolean strongLabels = medianW < STRONG_LABEL_WIDTH_FRACTION * pp.getWidth();
        return scatteredLabels
                && (strongLabels || inkArea >= MIN_INK_AREA_FRACTION * pageArea);
    }

    /**
     * Removes the page's top-level blocks; returns the index of the first
     * removed.
     *
     * <p>A poster page replaces its ENTIRE flow with an underlay raster plus
     * PGM-derived text frames, so nothing of the page may stay flowing — a
     * leftover paragraph both duplicates a frame's text and adds real flow
     * height that spills the single poster page onto a second Word page. The
     * heuristic enricher, however, merges/splits source runs into fresh
     * paragraphs whose ids are absent from the PGM index ({@link #pageOf}
     * returns -1). Since {@code PdfSdmReader} emits blocks in page order, an
     * unmapped block is attributed to the page of the nearest PRECEDING mapped
     * block (carry-forward) so it is removed with its page.</p>
     */
    private static int removePageBlocks(SdmDocument sdm, PgmModel pgm, int page0,
            List<SdmBlock> removed) {
        int first = sdm.getChildren().size();
        int i = 0;
        int lastMappedPage = -1;
        for (Iterator<SdmBlock> it = sdm.getChildren().iterator(); it.hasNext(); i++) {
            SdmBlock b = it.next();
            // EVERY top-level block on a poster page must go: a leftover table or
            // list (forms are recognized as ruled tables) both duplicates the
            // PGM-derived text frames and adds real flow height that spills the
            // single poster page onto extra Word pages.
            int pg = pageOf(b, pgm);
            if (pg >= 0) {
                lastMappedPage = pg;
            }
            // Unmapped block (heuristic-merged/synthesized, no PGM id): belongs
            // to the page of its nearest preceding mapped sibling in the stream.
            int effectivePage = pg >= 0 ? pg : lastMappedPage;
            if (effectivePage == page0) {
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

    /**
     * The page that owns most of the block's geometry, or -1 when unknown.
     * Recurses into container/table/list children: a synthesized table has no
     * PGM id of its own, but its cell paragraphs' runs kept the ids of the
     * source boxes they were built from.
     */
    private static int pageOf(SdmBlock b, PgmModel pgm) {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        tallyPages(b, pgm, counts);
        int best = -1;
        int bestCount = 0;
        for (java.util.Map.Entry<Integer, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                bestCount = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * True when page {@code page0} is dominated by a genuine, densely-filled data
     * table &mdash; a real grid the poster heuristic would otherwise flatten.
     *
     * <p>The discriminators against a poster/form (which is also recognized as a
     * ruled table): the table must be a real matrix ({@code >=2} rows and columns),
     * its cells must be mostly non-empty ({@code >=60%} carry text &mdash; unfilled
     * form fields fail this), and it must account for at least half the page's text
     * boxes (a stray in-poster table fails this).</p>
     */
    private static boolean tableDominatesPage(SdmDocument sdm, PgmModel pgm, int page0, PgmPage pp) {
        int pageTextBoxes = 0;
        for (PgmBox b : pp.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT) {
                pageTextBoxes++;
            }
        }
        if (pageTextBoxes < MIN_TEXT_BOXES) {
            return false;
        }
        // Attribute each top-level block to a page the same way removePageBlocks
        // does: pageOf where the PGM ids resolve, else the nearest preceding
        // mapped sibling (a synthesized table's cell runs often carry no resolvable
        // id, so pageOf(table) == -1).
        int lastMappedPage = -1;
        boolean anyMatrix = false;
        int denseNonEmpty = 0; // non-empty cells across all dense tables on this page
        for (SdmBlock block : sdm.getChildren()) {
            int pg = pageOf(block, pgm);
            if (pg >= 0) {
                lastMappedPage = pg;
            }
            int effectivePage = pg >= 0 ? pg : lastMappedPage;
            if (!(block instanceof org.aspose.pdf.sdm.Table) || effectivePage != page0) {
                continue;
            }
            org.aspose.pdf.sdm.Table t = (org.aspose.pdf.sdm.Table) block;
            int rows = t.getRows().size();
            int maxCols = 0;
            int totalCells = 0;
            int nonEmptyCells = 0;
            for (org.aspose.pdf.sdm.TableRow r : t.getRows()) {
                int cols = 0;
                for (org.aspose.pdf.sdm.TableCell cell : r.getCells()) {
                    cols += Math.max(1, cell.getColSpan());
                    totalCells++;
                    if (cellHasText(cell)) {
                        nonEmptyCells++;
                    }
                }
                maxCols = Math.max(maxCols, cols);
            }
            if (rows < 2 || maxCols < 2 || totalCells < 4) {
                continue;
            }
            if (nonEmptyCells < 0.6 * totalCells) {
                continue; // a form (mostly empty fields) — leave it posterized
            }
            anyMatrix = true;
            denseNonEmpty += nonEmptyCells;
        }
        // The recognized grid(s) must account for most of the page's text: a stray
        // in-poster table has far fewer cells than the page's scattered labels.
        return anyMatrix && denseNonEmpty >= 0.5 * pageTextBoxes;
    }

    /** True when any of a cell's block children carry visible run text. */
    private static boolean cellHasText(org.aspose.pdf.sdm.TableCell cell) {
        for (SdmBlock child : cell.getChildren()) {
            if (blockHasText(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean blockHasText(SdmBlock block) {
        java.util.List<org.aspose.pdf.sdm.SdmInline> inline = block instanceof Paragraph
                ? ((Paragraph) block).getInline()
                : block instanceof Heading ? ((Heading) block).getInline() : null;
        if (inline != null) {
            for (org.aspose.pdf.sdm.SdmInline in : inline) {
                if (in instanceof Run && ((Run) in).getText() != null
                        && !((Run) in).getText().trim().isEmpty()) {
                    return true;
                }
            }
        } else if (block instanceof Container) {
            for (SdmBlock c : ((Container) block).getChildren()) {
                if (blockHasText(c)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Accumulates, per page, the count of PGM boxes owned by the block and all
     *  of its descendant blocks. */
    private static void tallyPages(SdmBlock b, PgmModel pgm,
            java.util.Map<Integer, Integer> counts) {
        if (b == null) {
            return;
        }
        if (b.getId() != null) {
            for (PgmBox box : pgm.byId(b.getId())) {
                counts.merge(box.getPage(), 1, Integer::sum);
            }
        }
        if (b instanceof Container) {
            for (SdmBlock c : ((Container) b).getChildren()) {
                tallyPages(c, pgm, counts);
            }
        } else if (b instanceof org.aspose.pdf.sdm.Table) {
            for (org.aspose.pdf.sdm.TableRow r : ((org.aspose.pdf.sdm.Table) b).getRows()) {
                for (org.aspose.pdf.sdm.TableCell cell : r.getCells()) {
                    for (SdmBlock c : cell.getChildren()) {
                        tallyPages(c, pgm, counts);
                    }
                }
            }
        } else if (b instanceof org.aspose.pdf.sdm.ListBlock) {
            for (org.aspose.pdf.sdm.ListItem it : ((org.aspose.pdf.sdm.ListBlock) b).getItems()) {
                for (SdmBlock c : it.getChildren()) {
                    tallyPages(c, pgm, counts);
                }
            }
        } else if (b instanceof Figure) {
            for (SdmBlock c : ((Figure) b).getCaption()) {
                tallyPages(c, pgm, counts);
            }
        }
    }

    private static byte[] renderUnderlay(Document doc, int pageNum, boolean keepRotatedGlyphs,
            boolean keepType3) throws Exception {
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
        // Keep non-extractable Type 3 / symbol glyphs painted (their frames are
        // skipped by the caller) so the page shows real marks, not mojibake.
        renderer.setSuppressTextKeepType3(keepType3);
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
