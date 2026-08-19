package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;

/**
 * Suppresses running headers and footers — IR Stage 3 clean-up.
 *
 * <p>When a multi-page PDF is flowed into one structural HTML stream the page
 * furniture (running title, "Manuel Utilisateur … 3/76 … 15/11/07" footer, page
 * numbers, a lone version "2.2.2") is emitted once <em>per page</em>, producing a
 * long ladder of near-identical lines that swamps the real content. A reflowed
 * HTML has no page boundaries, so that furniture is pure noise.</p>
 *
 * <p><b>Two repetition-gated passes, both HONEST DEGRADATION:</b></p>
 * <ol>
 *   <li><b>Banded furniture.</b> A short single-line text block whose box sits
 *       entirely in the top or bottom {@link #BAND_FRACTION} of its page is a
 *       candidate. Candidates are grouped by band + digit-collapsed signature (so
 *       "3/76" and "4/76" share a signature). A signature that recurs in the same
 *       band on at least half of the pages (min {@link #MIN_PAGES}) is furniture;
 *       every block carrying it is removed.</li>
 *   <li><b>Orphan numeric furniture.</b> Reading-order strays — a lone version or
 *       page number extracted as its own paragraph — often carry no page geometry,
 *       so the band test cannot see them. A block whose signature is almost all
 *       digits (≤ {@link #MAX_ORPHAN_LETTERS} letters) and repeats on at least
 *       half the pages is furniture regardless of geometry.</li>
 * </ol>
 *
 * <p>Content that merely repeats a handful of times, or is a real sentence in the
 * body, is always kept.</p>
 */
public final class RunningHeaderFooterEnricher {

    private static final Logger LOG = Logger.getLogger(RunningHeaderFooterEnricher.class.getName());

    /** Top/bottom band height as a fraction of the page — furniture lives here. */
    private static final double BAND_FRACTION = 0.12;
    /** A candidate line must be at most this tall (× page height) — one line, not a block. */
    private static final double MAX_LINE_FRACTION = 0.06;
    /** Absolute floor on the page count a signature must recur on. */
    private static final int MIN_PAGES = 3;
    /** A numeric orphan furniture block may carry at most this many letters. */
    private static final int MAX_ORPHAN_LETTERS = 2;
    /** A box-less orphan stray is furniture only when its signature is this short. */
    private static final int MAX_ORPHAN_SIG_LEN = 16;

    private RunningHeaderFooterEnricher() {
        // static entry only
    }

    /**
     * Removes running headers/footers from the SDM in place.
     *
     * @param sdm the structural model (mutated)
     * @param pgm the page geometry model (positions + page sizes)
     * @return the number of blocks removed
     */
    public static int enrich(SdmDocument sdm, PgmModel pgm) {
        if (sdm == null || pgm == null) {
            return 0;
        }
        int totalPages = pgm.getPages().size();
        // Repetition is the whole signal; without at least a few pages there is
        // nothing to distinguish furniture from a one-off line.
        if (totalPages < MIN_PAGES) {
            return 0;
        }
        int minPages = Math.max(MIN_PAGES, (int) Math.ceil(0.5 * totalPages));

        List<Entry> entries = new ArrayList<>();
        collect(sdm.getChildren(), pgm, entries);
        if (entries.isEmpty()) {
            return 0;
        }

        // Pass 1 — banded furniture: distinct pages per (band, signature).
        Map<String, Set<Integer>> pagesByBandSig = new HashMap<>();
        for (Entry e : entries) {
            if (e.band != null) {
                pagesByBandSig.computeIfAbsent(e.band + ' ' + e.sig, k -> new HashSet<>()).add(e.page);
            }
        }
        Set<String> runningBandKeys = new HashSet<>();
        Set<String> runningSigs = new HashSet<>();
        for (Map.Entry<String, Set<Integer>> me : pagesByBandSig.entrySet()) {
            if (me.getValue().size() >= minPages) {
                runningBandKeys.add(me.getKey());
                runningSigs.add(me.getKey().substring(2)); // strip "H "/"F "
            }
        }

        // Pass 2 — orphan furniture: a reading-order stray that lost its page box
        // (a lone version "2.2.2", a running-title word "Design" split off the
        // header). Counted by occurrence since these lack geometry to band.
        Map<String, Integer> orphanCounts = new HashMap<>();
        for (Entry e : entries) {
            if (isOrphanFurniture(e)) {
                orphanCounts.merge(e.sig, 1, Integer::sum);
            }
        }
        Set<String> runningOrphanSigs = new HashSet<>();
        for (Map.Entry<String, Integer> me : orphanCounts.entrySet()) {
            if (me.getValue() >= minPages) {
                runningOrphanSigs.add(me.getKey());
            }
        }

        if (runningBandKeys.isEmpty() && runningOrphanSigs.isEmpty()) {
            return 0;
        }

        Set<SdmBlock> toRemove = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        long totalChars = 0;
        long removeChars = 0;
        for (Entry e : entries) {
            totalChars += e.sig.length();
            boolean banded = e.band != null && runningBandKeys.contains(e.band + ' ' + e.sig);
            // A boxless copy of a confirmed banded furniture line (e.g. the
            // reading-order stray of the same running title) is furniture too.
            boolean confirmedSig = e.band == null && runningSigs.contains(e.sig);
            boolean orphan = runningOrphanSigs.contains(e.sig) && isOrphanFurniture(e);
            if (banded || confirmedSig || orphan) {
                toRemove.add(e.block);
                removeChars += e.sig.length();
            }
        }
        // SAFETY VALVE: real page furniture is a bounded share of the document
        // (headers + footers + strays — half at the very most, in short docs).
        // When the repetition gates mark MORE than that (a template form whose
        // pages are near-identical — every line "repeats on every page"), this is
        // not furniture; removing it would hollow out the document. Keep it all.
        if (totalChars > 0 && removeChars > 0.66 * totalChars) {
            LOG.fine("RunningHeaderFooterEnricher: suppression would drop "
                    + removeChars + "/" + totalChars
                    + " signature chars — repeated-template document, keeping all");
            return 0;
        }
        int removed = remove(sdm.getChildren(), toRemove);
        if (removed > 0) {
            LOG.fine("RunningHeaderFooterEnricher: removed " + removed
                    + " running header/footer block(s) across " + totalPages + " pages");
        }
        return removed;
    }

    /**
     * True when an entry is orphan furniture — a repetition candidate that has no
     * page geometry to band. Either a numeric-dominant stray (a lone version or
     * page number) or a short box-less stray (a running-title word split off the
     * header). The ≥50%-of-pages gate elsewhere confirms it is actually running.
     */
    private static boolean isOrphanFurniture(Entry e) {
        boolean numeric = e.letters <= MAX_ORPHAN_LETTERS && e.sig.indexOf('#') >= 0;
        boolean boxlessShort = e.page < 0 && e.sig.length() <= MAX_ORPHAN_SIG_LEN;
        return numeric || boxlessShort;
    }

    /** Walks the block tree, gathering short text blocks with band + signature. */
    private static void collect(List<SdmBlock> blocks, PgmModel pgm, List<Entry> out) {
        for (SdmBlock b : blocks) {
            if (b instanceof Container) {
                collect(((Container) b).getChildren(), pgm, out);
                continue;
            }
            String text;
            if (b instanceof Paragraph) {
                text = inlineText(((Paragraph) b).getInline());
            } else if (b instanceof Heading) {
                text = inlineText(((Heading) b).getInline());
            } else {
                continue;
            }
            String sig = signature(text);
            if (sig.isEmpty()) {
                continue;
            }
            int letters = 0;
            for (int i = 0; i < sig.length(); i++) {
                if (Character.isLetter(sig.charAt(i))) {
                    letters++;
                }
            }
            List<PgmBox> boxes = b.getId() == null ? null : pgm.byId(b.getId());
            String band = (boxes == null || boxes.isEmpty()) ? null : band(boxes, pgm);
            int page = (boxes == null || boxes.isEmpty()) ? -1 : boxes.get(0).getPage();
            out.add(new Entry(b, page, band, sig, letters));
        }
    }

    /**
     * Classifies a block as living wholly in the top ("H") or bottom ("F") band
     * of a single page, and being a single short line; else null (body content).
     */
    private static String band(List<PgmBox> boxes, PgmModel pgm) {
        int page = boxes.get(0).getPage();
        if (page < 0 || page >= pgm.getPages().size()) {
            return null;
        }
        PgmPage p = pgm.getPage(page);
        double pageH = p.getHeight();
        if (pageH <= 0) {
            return null;
        }
        double minY = Double.MAX_VALUE;
        double maxTop = -Double.MAX_VALUE;
        for (PgmBox box : boxes) {
            if (box.getPage() != page) {
                return null; // spans pages — not furniture
            }
            PgmRect r = box.getRect();
            minY = Math.min(minY, r.getY());
            maxTop = Math.max(maxTop, r.getTop());
        }
        double height = maxTop - minY;
        if (height > pageH * MAX_LINE_FRACTION) {
            return null; // a real block, not a one-line runner
        }
        double bandH = pageH * BAND_FRACTION;
        if (minY >= pageH - bandH) {
            return "H"; // header: near the top (PDF Y grows upward)
        }
        if (maxTop <= bandH) {
            return "F"; // footer: near the bottom
        }
        return null;
    }

    /** Removes marked blocks from the tree in place; returns the count removed. */
    private static int remove(List<SdmBlock> blocks, Set<SdmBlock> toRemove) {
        int removed = 0;
        for (java.util.Iterator<SdmBlock> it = blocks.iterator(); it.hasNext();) {
            SdmBlock b = it.next();
            if (toRemove.contains(b)) {
                it.remove();
                removed++;
            } else if (b instanceof Container) {
                removed += remove(((Container) b).getChildren(), toRemove);
            }
        }
        return removed;
    }

    private static String inlineText(List<SdmInline> inlines) {
        StringBuilder sb = new StringBuilder();
        for (SdmInline in : inlines) {
            if (in instanceof Run) {
                sb.append(((Run) in).getText());
            }
        }
        return sb.toString();
    }

    /**
     * Structural signature: letters lower-cased and kept, each run of digits
     * collapsed to a single '#', a few structural separators (. / : -) kept, and
     * everything else (spaces, other punctuation) dropped. This makes the varying
     * parts of furniture stable across pages — the footer "Manuel Utilisateur
     * 2.2.2.2 3/76 15/11/07" and "… 4/76 … 16/11/07" both map to
     * "manuelutilisateur#.#.#.#/#/#", and a bare version/page number "2.2.2" or
     * "3/76" maps to "#.#.#" / "#/#" — so a furniture line whose only per-page
     * change is a number still groups, while distinct titles stay distinct.
     */
    private static String signature(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        boolean prevDigit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = Character.toLowerCase(text.charAt(i));
            if (Character.isDigit(c)) {
                if (!prevDigit) {
                    sb.append('#');
                }
                prevDigit = true;
            } else {
                prevDigit = false;
                if (Character.isLetter(c)) {
                    sb.append(c);
                } else if (c == '.' || c == '/' || c == ':' || c == '-') {
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** One collected text block plus its grouping keys. */
    private static final class Entry {
        final SdmBlock block;
        final int page;     // -1 if the block has no page geometry
        final String band;  // "H", "F", or null (body / geometry-less)
        final String sig;
        final int letters;

        Entry(SdmBlock block, int page, String band, String sig, int letters) {
            this.block = block;
            this.page = page;
            this.band = band;
            this.sig = sig;
            this.letters = letters;
        }
    }
}
