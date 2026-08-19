package org.aspose.pdf.sdm.enrich;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
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
 * Restores the extractor's inter-run whitespace inside SDM paragraphs before
 * HTML serialization (IR Stage 3).
 *
 * <p>The Stage-1 reader stores one Run per text fragment with NO separator;
 * the text extractor decides glue-vs-space from geometry ({@link SpacingRule}).
 * An HTML paragraph concatenates its runs, so without this pass same-line
 * fragments render glued ({@code Total42}). This normalizer walks every
 * Paragraph/Heading whose PGM boxes still align 1:1 with its runs and inserts
 * literal space Runs where the extractor would emit whitespace; it also
 * mirrors the extractor's NBSP&rarr;space normalization.</p>
 *
 * <p>MUST run LAST in the enrichment chain: inserted space Runs deliberately
 * break the run&harr;box part alignment (they have no boxes).</p>
 */
public final class RunSpacingNormalizer {

    private static final Logger LOG = Logger.getLogger(RunSpacingNormalizer.class.getName());

    private RunSpacingNormalizer() {
    }

    /**
     * Normalizes inter-run spacing across the whole document.
     *
     * @param sdm the SDM document (mutated in place)
     * @param pgm the PGM geometry the document was read with
     */
    public static void normalize(SdmDocument sdm, PgmModel pgm) {
        if (sdm == null || pgm == null) {
            return;
        }
        walk(sdm.getChildren(), pgm, SpacingRule.pageAvgCharWidth(pgm));
    }

    private static void walk(List<? extends SdmBlock> blocks, PgmModel pgm, double[] pageAcw) {
        for (SdmBlock b : blocks) {
            if (b instanceof Paragraph) {
                normalizeInline(((Paragraph) b).getInline(), b.getId(), pgm, pageAcw);
            } else if (b instanceof Heading) {
                normalizeInline(((Heading) b).getInline(), b.getId(), pgm, pageAcw);
            } else if (b instanceof Container) {
                walk(((Container) b).getChildren(), pgm, pageAcw);
            } else if (b instanceof Quote) {
                walk(((Quote) b).getChildren(), pgm, pageAcw);
            } else if (b instanceof Footnote) {
                walk(((Footnote) b).getChildren(), pgm, pageAcw);
            } else if (b instanceof ListBlock) {
                for (ListItem li : ((ListBlock) b).getItems()) {
                    walk(li.getChildren(), pgm, pageAcw);
                }
            } else if (b instanceof Table) {
                for (TableRow r : ((Table) b).getRows()) {
                    for (TableCell c : r.getCells()) {
                        walk(c.getChildren(), pgm, pageAcw);
                    }
                }
            } else if (b instanceof Figure) {
                walk(((Figure) b).getCaption(), pgm, pageAcw);
            }
        }
    }

    private static void normalizeInline(List<SdmInline> inline, String id, PgmModel pgm,
                                        double[] pageAcw) {
        if (id == null || inline == null || inline.size() < 2) {
            replaceNbsp(inline);
            return;
        }
        List<Run> runs = new ArrayList<>();
        for (SdmInline in : inline) {
            if (in instanceof Run) {
                runs.add((Run) in);
            }
        }
        List<PgmBox> boxes = new ArrayList<>(pgm.byId(id));
        if (runs.size() < 2 || boxes.size() != runs.size()) {
            replaceNbsp(inline);
            return; // already normalized / split residue — geometry no longer 1:1
        }
        boxes.sort((a, b) -> Integer.compare(a.getPartIndex(), b.getPartIndex()));

        int pageIdx = boxes.get(0).getPage();
        double acw = pageIdx >= 0 && pageIdx < pageAcw.length ? pageAcw[pageIdx] : 5.0;

        for (int i = runs.size() - 1; i >= 1; i--) {
            PgmBox prev = boxes.get(i - 1);
            PgmBox cur = boxes.get(i);
            double prevBase = baseline(prev);
            double curBase = baseline(cur);
            boolean space = SpacingRule.shouldSpace(
                    runs.get(i - 1).getText(),
                    prev.getRect().getX() + prev.getRect().getW(), prevBase,
                    runs.get(i).getText(), cur.getRect().getX(), curBase, acw);
            if (space) {
                int pos = inline.indexOf(runs.get(i));
                if (pos > 0) {
                    inline.add(pos, new Run(" ", null));
                }
            }
        }
        replaceNbsp(inline);
    }

    private static double baseline(PgmBox box) {
        Object data = box.getData();
        if (data instanceof TextBoxData) {
            return ((TextBoxData) data).getBaselineY();
        }
        return box.getRect().getY();
    }

    /** Mirrors the extractor's BUG-EXT-SPACE normalization (NBSP → space). */
    private static void replaceNbsp(List<SdmInline> inline) {
        if (inline == null) {
            return;
        }
        for (SdmInline in : inline) {
            if (in instanceof Run) {
                Run r = (Run) in;
                if (r.getText() != null && r.getText().indexOf(' ') >= 0) {
                    r.setText(r.getText().replace(' ', ' '));
                }
            }
        }
    }
}
