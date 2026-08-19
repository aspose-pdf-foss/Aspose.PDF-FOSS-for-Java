package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.aspose.pdf.Document;
import org.aspose.pdf.ExplicitDestination;
import org.aspose.pdf.GoToAction;
import org.aspose.pdf.PdfAction;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.UriAction;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.annotations.LinkAnnotation;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;

/**
 * Projects PDF {@code /Link} annotations onto the reflowed flow as real
 * hyperlinks — external {@code URI} actions and internal {@code GoTo}
 * cross-references alike — so a DOCX (or HTML) export keeps its links.
 *
 * <p>PDF links live in the annotation layer, not the content stream: a link is
 * a rectangle plus a target. This pass runs on the SHALLOW projection, where
 * every run still maps 1:1 to a geometry box, and:</p>
 * <ol>
 *   <li>tags each run whose box is covered by a link rectangle with the link
 *       target — {@code link-href} for an external URI, {@code link-anchor}
 *       for an internal destination;</li>
 *   <li>for each distinct internal destination, drops a bookmark anchor on the
 *       run nearest that destination's target position (tagged via
 *       {@code bookmark-anchors}).</li>
 * </ol>
 *
 * <p>It only <em>tags</em> runs (never restructures the inline list), so the
 * run&harr;box invariant later passes rely on is preserved; the DOCX writer
 * groups consecutive equally-tagged runs into a {@code w:hyperlink} /
 * {@code HYPERLINK \l} field and emits {@code w:bookmarkStart/End} around a
 * bookmarked run. Anchor names are assigned deterministically ({@code br1},
 * {@code br2}, … in target reading order) — not matching any particular
 * producer's numbering, but stable and self-consistent.</p>
 */
public final class LinkAnnotationEnricher {

    private static final Logger LOG = Logger.getLogger(LinkAnnotationEnricher.class.getName());

    /** Attribute: external hyperlink URI carried by a covered run. */
    public static final String ATTR_HREF = "link-href";
    /** Attribute: internal bookmark anchor a covered run links to. */
    public static final String ATTR_ANCHOR = "link-anchor";
    /** Attribute: list of bookmark anchor names a run is the destination of. */
    public static final String ATTR_BOOKMARKS = "bookmark-anchors";

    private LinkAnnotationEnricher() {
        // static entry only
    }

    /** A link rectangle on a page, plus its resolved target. */
    private static final class LinkMark {
        final int page;       // 0-based
        final double llx;
        final double lly;
        final double urx;
        final double ury;
        final String href;    // external — mutually exclusive with anchorKey
        final String anchorKey; // internal destination key — resolved to a name later

        LinkMark(int page, Rectangle r, String href, String anchorKey) {
            this.page = page;
            this.llx = r.getLLX();
            this.lly = r.getLLY();
            this.urx = r.getURX();
            this.ury = r.getURY();
            this.href = href;
            this.anchorKey = anchorKey;
        }

        boolean covers(double x, double y) {
            return x >= llx - 1 && x <= urx + 1 && y >= lly - 1 && y <= ury + 1;
        }
    }

    /** A resolved internal destination target (0-based page + top Y). */
    private static final class Target {
        final int page;
        final double top;

        Target(int page, double top) {
            this.page = page;
            this.top = top;
        }
    }

    /** A run paired with its geometry box (shallow-projection 1:1 mapping). */
    private static final class RunGeom {
        final Run run;
        final PgmBox box;

        RunGeom(Run run, PgmBox box) {
            this.run = run;
            this.box = box;
        }
    }

    /**
     * Tags runs covered by link annotations and drops bookmark anchors at
     * internal destinations.
     *
     * @param doc the source document (annotation + destination layer)
     * @param sdm the shallow structural model (mutated: run attributes set)
     * @param pgm the page geometry model
     * @return the number of runs tagged as link anchors
     */
    public static int enrich(Document doc, SdmDocument sdm, PgmModel pgm) {
        if (doc == null || sdm == null || pgm == null) {
            return 0;
        }
        int pages;
        try {
            pages = doc.getPages().getCount();
        } catch (Exception e) {
            return 0;
        }

        List<LinkMark> marks = new ArrayList<>();
        // Distinct internal destinations, keyed for dedup, in first-seen order.
        Map<String, Target> targets = new LinkedHashMap<>();
        for (int p = 1; p <= pages; p++) {
            Page page;
            AnnotationCollection annots;
            try {
                page = doc.getPages().get(p);
                annots = page.getAnnotations();
            } catch (Exception e) {
                continue;
            }
            if (annots == null) {
                continue;
            }
            for (int i = 1; i <= annots.getCount(); i++) {
                Annotation ann;
                try {
                    ann = annots.get(i);
                } catch (Exception e) {
                    continue;
                }
                if (ann == null || !"Link".equals(ann.getSubtype())) {
                    continue;
                }
                Rectangle r = ann.getRect();
                if (r == null) {
                    continue;
                }
                PdfDictionary dict = ann.getPdfDictionary();
                if (dict == null) {
                    continue;
                }
                LinkAnnotation link = new LinkAnnotation(dict, page);
                String href = externalUri(link);
                if (href != null) {
                    marks.add(new LinkMark(p - 1, r, href, null));
                    continue;
                }
                Target t = internalTarget(link, doc);
                if (t != null) {
                    String key = t.page + ":" + Math.round(t.top);
                    targets.putIfAbsent(key, t);
                    marks.add(new LinkMark(p - 1, r, null, key));
                }
            }
        }
        if (marks.isEmpty()) {
            return 0;
        }

        // Deterministic anchor names in target reading order (page, then top→bottom).
        List<Map.Entry<String, Target>> ordered = new ArrayList<>(targets.entrySet());
        ordered.sort((a, b) -> {
            int c = Integer.compare(a.getValue().page, b.getValue().page);
            if (c != 0) {
                return c;
            }
            return Double.compare(b.getValue().top, a.getValue().top);
        });
        Map<String, String> anchorName = new LinkedHashMap<>();
        int n = 1;
        for (Map.Entry<String, Target> e : ordered) {
            anchorName.put(e.getKey(), "br" + (n++));
        }

        List<RunGeom> runs = new ArrayList<>();
        collectRuns(sdm.getChildren(), pgm, runs);
        if (runs.isEmpty()) {
            return 0;
        }

        // Place a bookmark on the run nearest each internal destination.
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (Map.Entry<String, Target> e : ordered) {
            Target t = e.getValue();
            RunGeom best = null;
            double bestDist = Double.MAX_VALUE;
            for (RunGeom rg : runs) {
                if (rg.box.getPage() != t.page) {
                    continue;
                }
                double boxTop = rg.box.getRect().getY() + rg.box.getRect().getH();
                double d = Math.abs(boxTop - t.top);
                if (d < bestDist) {
                    bestDist = d;
                    best = rg;
                }
            }
            if (best != null) {
                String name = anchorName.get(e.getKey());
                addBookmark(best.run, name);
                placed.add(e.getKey());
            }
        }

        // Tag covered runs with their link target.
        int tagged = 0;
        for (RunGeom rg : runs) {
            double cx = rg.box.getRect().getX() + rg.box.getRect().getW() / 2;
            double cy = rg.box.getRect().getY() + rg.box.getRect().getH() / 2;
            int page = rg.box.getPage();
            for (LinkMark m : marks) {
                if (m.page != page || !m.covers(cx, cy)) {
                    continue;
                }
                if (m.href != null) {
                    rg.run.getAttributes().put(ATTR_HREF, m.href);
                    tagged++;
                    break;
                }
                if (m.anchorKey != null && placed.contains(m.anchorKey)) {
                    rg.run.getAttributes().put(ATTR_ANCHOR, anchorName.get(m.anchorKey));
                    tagged++;
                    break;
                }
            }
        }
        if (tagged > 0) {
            LOG.fine("LinkAnnotationEnricher: tagged " + tagged + " run(s), "
                    + placed.size() + " bookmark(s)");
        }
        return tagged;
    }

    private static String externalUri(LinkAnnotation link) {
        try {
            PdfAction a = link.getAction();
            if (a instanceof UriAction) {
                String uri = ((UriAction) a).getUri();
                return uri != null && !uri.isEmpty() ? uri : null;
            }
        } catch (Exception e) {
            LOG.fine(() -> "link action read failed: " + e);
        }
        return null;
    }

    private static Target internalTarget(LinkAnnotation link, Document doc) {
        // Prefer the /Dest path (resolves named destinations through the real
        // document, incl. this test's binary-string dest names); fall back to a
        // /A GoTo action's destination.
        ExplicitDestination dest = null;
        try {
            dest = link.getDestination(doc);
        } catch (Exception e) {
            LOG.fine(() -> "link /Dest read failed: " + e);
        }
        if (dest == null) {
            try {
                PdfAction a = link.getAction();
                if (a instanceof GoToAction) {
                    dest = ((GoToAction) a).getDestination();
                }
            } catch (Exception e) {
                LOG.fine(() -> "GoTo action read failed: " + e);
            }
        }
        if (dest == null) {
            return null;
        }
        int pageNum = dest.getPageNumber();
        if (pageNum < 1) {
            return null;
        }
        return new Target(pageNum - 1, destTop(dest, doc, pageNum));
    }

    /** Destination top Y in PDF user space; falls back to the page top. */
    private static double destTop(ExplicitDestination dest, Document doc, int pageNum) {
        try {
            java.lang.reflect.Method m = dest.getClass().getMethod("getTop");
            Object v = m.invoke(dest);
            if (v instanceof Number) {
                double top = ((Number) v).doubleValue();
                if (top > 0) {
                    return top;
                }
            }
        } catch (ReflectiveOperationException ignore) {
            // Fit / FitV / FitB destinations carry no top — use the page top.
        }
        try {
            Rectangle r = doc.getPages().get(pageNum).getRect();
            if (r != null) {
                return r.getURY();
            }
        } catch (Exception ignore) {
            // fall through
        }
        return Double.MAX_VALUE; // unknown top → treat as page-top-most
    }

    @SuppressWarnings("unchecked")
    private static void addBookmark(Run run, String name) {
        Object v = run.getAttributes().get(ATTR_BOOKMARKS);
        List<String> list;
        if (v instanceof List) {
            list = (List<String>) v;
        } else {
            list = new ArrayList<>();
            run.getAttributes().put(ATTR_BOOKMARKS, list);
        }
        if (!list.contains(name)) {
            list.add(name);
        }
    }

    private static void collectRuns(List<SdmBlock> blocks, PgmModel pgm, List<RunGeom> out) {
        for (SdmBlock b : blocks) {
            List<SdmInline> inlines = null;
            if (b instanceof Paragraph) {
                inlines = ((Paragraph) b).getInline();
            } else if (b instanceof Heading) {
                inlines = ((Heading) b).getInline();
            } else if (b instanceof Container) {
                collectRuns(((Container) b).getChildren(), pgm, out);
                continue;
            }
            if (inlines == null || b.getId() == null) {
                continue;
            }
            List<Run> runs = new ArrayList<>();
            for (SdmInline in : inlines) {
                if (in instanceof Run) {
                    runs.add((Run) in);
                }
            }
            List<PgmBox> boxes = new ArrayList<>(pgm.byId(b.getId()));
            if (runs.isEmpty() || boxes.size() != runs.size()) {
                continue;
            }
            boxes.sort((x, y) -> Integer.compare(x.getPartIndex(), y.getPartIndex()));
            for (int i = 0; i < runs.size(); i++) {
                out.add(new RunGeom(runs.get(i), boxes.get(i)));
            }
        }
    }
}
