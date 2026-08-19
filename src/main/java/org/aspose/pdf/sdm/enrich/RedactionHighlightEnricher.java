package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Reflects region-marking annotations (Redact / Highlight / Square) as a text
 * highlight in the reflowed HTML — IR Stage 3 presentation clean-up.
 *
 * <p>Clinical and legal PDFs mark sensitive values with coloured annotation boxes
 * (a light-blue redaction frame, a yellow highlight) that live in the annotation
 * layer, not the content stream. Those marks carry meaning, so a faithful reflow
 * should keep them. This pass runs on the SHALLOW projection — where every run
 * still maps 1:1 to a geometry box — and tints each run whose box centre falls
 * inside an annotation rectangle with that annotation's colour. Because later
 * enrichment reuses the run objects (and copies their style when splitting), the
 * tint survives into table cells.</p>
 */
public final class RedactionHighlightEnricher {

    private static final Logger LOG = Logger.getLogger(RedactionHighlightEnricher.class.getName());

    private RedactionHighlightEnricher() {
        // static entry only
    }

    /** One annotation rectangle plus its packed 0xRRGGBB colour, on a page. */
    private static final class Mark {
        final double llx;
        final double lly;
        final double urx;
        final double ury;
        final int rgb;

        Mark(Rectangle r, int rgb) {
            this.llx = r.getLLX();
            this.lly = r.getLLY();
            this.urx = r.getURX();
            this.ury = r.getURY();
            this.rgb = rgb;
        }

        boolean contains(double x, double y) {
            return x >= llx - 1 && x <= urx + 1 && y >= lly - 1 && y <= ury + 1;
        }
    }

    /**
     * Tints runs covered by a region-marking annotation with the annotation colour.
     *
     * @param doc the source document (annotation layer)
     * @param sdm the shallow structural model (mutated: run backgrounds set)
     * @param pgm the page geometry model
     * @return the number of runs highlighted
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
        List<List<Mark>> perPage = new ArrayList<>();
        boolean any = false;
        for (int p = 1; p <= pages; p++) {
            List<Mark> marks = new ArrayList<>();
            try {
                AnnotationCollection annots = doc.getPages().get(p).getAnnotations();
                for (int i = 1; i <= annots.getCount(); i++) {
                    Annotation a = annots.get(i);
                    String sub = a.getSubtype();
                    if (!("Redact".equals(sub) || "Highlight".equals(sub) || "Square".equals(sub))) {
                        continue;
                    }
                    Rectangle r = a.getRect();
                    Color c = a.getColor();
                    if (r == null || c == null) {
                        continue;
                    }
                    int[] rgb = c.toRgb();
                    if (rgb == null || rgb.length < 3) {
                        continue;
                    }
                    marks.add(new Mark(r, (rgb[0] << 16) | (rgb[1] << 8) | rgb[2]));
                    any = true;
                }
            } catch (Exception e) {
                LOG.fine(() -> "annotation read failed on a page: " + e);
            }
            perPage.add(marks);
        }
        if (!any) {
            return 0;
        }
        int[] count = {0};
        highlight(sdm.getChildren(), pgm, perPage, count);
        if (count[0] > 0) {
            LOG.fine("RedactionHighlightEnricher: highlighted " + count[0] + " run(s)");
        }
        return count[0];
    }

    private static void highlight(List<SdmBlock> blocks, PgmModel pgm,
                                  List<List<Mark>> perPage, int[] count) {
        for (SdmBlock b : blocks) {
            List<SdmInline> inlines = null;
            if (b instanceof Paragraph) {
                inlines = ((Paragraph) b).getInline();
            } else if (b instanceof Heading) {
                inlines = ((Heading) b).getInline();
            } else if (b instanceof Container) {
                highlight(((Container) b).getChildren(), pgm, perPage, count);
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
                PgmBox box = boxes.get(i);
                int page = box.getPage();
                if (page < 0 || page >= perPage.size()) {
                    continue;
                }
                double cx = box.getRect().getX() + box.getRect().getW() / 2;
                double cy = box.getRect().getY() + box.getRect().getH() / 2;
                for (Mark m : perPage.get(page)) {
                    if (m.contains(cx, cy)) {
                        Run run = runs.get(i);
                        TextStyle st = run.getStyle();
                        if (st == null) {
                            st = new TextStyle();
                            run.setStyle(st);
                        }
                        if (st.getBackground() == 0) {
                            st.setBackground(0xFF000000 | m.rgb);
                            count[0]++;
                        }
                        break;
                    }
                }
            }
        }
    }
}
