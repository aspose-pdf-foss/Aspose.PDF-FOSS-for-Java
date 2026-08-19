package org.aspose.pdf.sdm.flow;

import org.aspose.pdf.CompactionOptions;
import org.aspose.pdf.CompactionResult;
import org.aspose.pdf.Document;
import org.aspose.pdf.pgm.ColumnStructure;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.sdm.reader.PdfSdmReader;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Orchestration of the public flow-compaction operation (IR Stage 2 PART 4):
 * optional N→1 column merge on CLEAN pages, in-page hole closing, then
 * cross-page flow with page removal (unless keepPageBreaks).
 * <p>
 * Scope note: when a page scope is set, cross-page flow is skipped (pulling
 * content across a scope boundary would touch out-of-scope pages) — recorded
 * as a warning.
 * </p>
 */
public final class FlowOperations {

    private static final Logger LOG = Logger.getLogger(FlowOperations.class.getName());

    private FlowOperations() {
    }

    /**
     * Runs flow compaction on the document per the options.
     *
     * @param doc     the open document (mutated)
     * @param options the options (null = defaults)
     * @return the result with honest skip reasons and warnings
     * @throws IOException if page content cannot be read or written
     */
    public static CompactionResult compactFlow(Document doc, CompactionOptions options)
            throws IOException {
        CompactionOptions opts = options == null ? new CompactionOptions() : options;
        CompactionResult result = new CompactionResult();
        result.setPagesBefore(doc.getPages().getCount());
        result.setPagesAfter(doc.getPages().getCount());

        PdfSdmReader.Result model = new PdfSdmReader().read(doc, null);

        // Optional N→1 merge of MULTI_CLEAN pages before compaction.
        if (opts.isMergeColumns()) {
            FlowClassifier.classify(model.getPgm());
            boolean mergedAny = false;
            for (PgmPage page : model.getPgm().getPages()) {
                if (!inScope(opts, page.getIndex())) {
                    continue;
                }
                ColumnStructure cs = org.aspose.pdf.pgm.ColumnDetector.detect(page);
                if (cs.getType() != ColumnStructure.Type.SINGLE_COLUMN) {
                    ColumnMerger.MergeResult mr = ColumnMerger.mergePage(doc, model,
                            page.getIndex(), false);
                    if (mr.isMerged()) {
                        mergedAny = true;
                        result.addBlocksMoved(mr.getBoxesMoved());
                    } else {
                        result.getPagesSkipped().put(page.getIndex(),
                                "merge: " + mr.getSkipReason());
                    }
                }
            }
            if (mergedAny) {
                model = new PdfSdmReader().read(doc, null); // merged pages are stale
            }
        }

        // In-page hole closing.
        CompactionResult inPage = FlowCompactor.compactInPage(doc, model, opts);
        result.addGapsClosed(inPage.getGapsClosed());
        result.addBlocksMoved(inPage.getBlocksMoved());
        for (Map.Entry<Integer, String> e : inPage.getPagesSkipped().entrySet()) {
            result.getPagesSkipped().putIfAbsent(e.getKey(), e.getValue());
        }
        result.getWarnings().addAll(inPage.getWarnings());

        // Cross-page flow ("like HTML") unless page breaks are kept.
        if (!opts.isKeepPageBreaks()) {
            if (opts.getPages() != null) {
                result.getWarnings().add(
                        "page scope set: cross-page flow skipped (would cross the scope)");
            } else {
                CrossPageFlow.flowAcrossPages(doc, opts, result, null);
            }
        }
        result.setPagesAfter(doc.getPages().getCount());
        LOG.fine(() -> "compactFlow: " + result);
        return result;
    }

    private static boolean inScope(CompactionOptions options, int pageIndex) {
        int[] pages = options.getPages();
        if (pages == null) {
            return true;
        }
        for (int p : pages) {
            if (p == pageIndex + 1) {
                return true;
            }
        }
        return false;
    }
}
