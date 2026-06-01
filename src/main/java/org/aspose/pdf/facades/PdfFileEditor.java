package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.PageSize;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageCollection;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSCloner;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides methods for manipulating PDF files: concatenating, extracting pages,
 * splitting, inserting, and deleting pages.
 * <p>
 * This is the most commonly used facade. All public methods return {@code boolean}
 * indicating success or failure, and log errors rather than throwing exceptions.
 */
public class PdfFileEditor {

    static {
        // Bootstrap library logging (silent by default) for facade-only entry
        // paths that may not construct a Document first (Sprint 24 Part B).
        org.aspose.pdf.AsposePdfLogging.configureFromSystemProperty();
    }

    private static final Logger LOG = Logger.getLogger(PdfFileEditor.class.getName());

    private Document document;
    private Exception lastException;
    private boolean useDiskBuffer;
    /**
     * Default {@code true} — historical OpenPDF behaviour was to always merge
     * outlines on concatenate (BUG-049), and several already-passing tests
     * rely on it. A test that explicitly disables it via
     * {@link #setCopyOutlines(boolean)} is honoured.
     */
    private boolean copyOutlines = true;
    private boolean copyLogicalStructure;
    private boolean keepActions = true;

    /**
     * Creates a new {@code PdfFileEditor} instance.
     */
    public PdfFileEditor() {
    }

    /**
     * Returns whether {@code concatenate(...)} should copy /Outlines from the
     * source documents into the destination.
     *
     * @return current flag
     */
    public boolean isCopyOutlines() {
        return copyOutlines;
    }

    /**
     * Enables or disables /Outlines copying during concatenation.
     *
     * @param copyOutlines {@code true} to merge bookmarks from each source
     */
    public void setCopyOutlines(boolean copyOutlines) {
        this.copyOutlines = copyOutlines;
    }

    /**
     * Returns whether {@code concatenate(...)} should preserve /StructTreeRoot
     * logical-structure information from the source documents.
     *
     * @return current flag
     */
    public boolean isCopyLogicalStructure() {
        return copyLogicalStructure;
    }

    /**
     * Enables or disables /StructTreeRoot copying during concatenation.
     *
     * @param copyLogicalStructure {@code true} to merge tagged structure from each source
     */
    public void setCopyLogicalStructure(boolean copyLogicalStructure) {
        this.copyLogicalStructure = copyLogicalStructure;
    }

    /**
     * Returns whether page-level /AA (additional actions) entries should be
     * kept when copying pages.
     *
     * @return current flag; defaults to {@code true} to match Aspose behaviour
     */
    public boolean isKeepActions() {
        return keepActions;
    }

    /**
     * Enables or disables /AA preservation when copying pages.
     *
     * @param keepActions {@code true} to keep additional-action entries
     */
    public void setKeepActions(boolean keepActions) {
        this.keepActions = keepActions;
    }

    /**
     * Page-break descriptor used by {@link PdfFileEditor#addPageBreak(Document, Document, PageBreak[])}.
     * <p>
     * Each instance points to a 1-based {@code pageNumber} in the source
     * document and a vertical offset {@code y} (in default user-space units,
     * measured from the bottom of the page) at which the source page should be
     * split into two pages in the destination document.
     * </p>
     */
    public static class PageBreak {
        private final int pageNumber;
        private final double y;

        /**
         * Creates a new page-break descriptor.
         *
         * @param pageNumber 1-based page number in the source document
         * @param y          vertical split position in default user-space units
         */
        public PageBreak(int pageNumber, double y) {
            this.pageNumber = pageNumber;
            this.y = y;
        }

        /** @return 1-based source page number */
        public int getPageNumber() {
            return pageNumber;
        }

        /** @return vertical split position in default user-space units */
        public double getY() {
            return y;
        }
    }

    /**
     * Returns the last exception captured by a Try* wrapper, or {@code null} if none.
     *
     * @return the last exception or {@code null}
     */
    public Exception getLastException() {
        return lastException;
    }

    /**
     * Returns whether this editor should prefer disk-buffer-oriented
     * concatenation strategies for large workloads.
     *
     * @return {@code true} if disk buffering is enabled
     */
    public boolean isUseDiskBuffer() {
        return useDiskBuffer;
    }

    /**
     * Enables or disables disk-buffer-oriented concatenation strategies.
     *
     * @param useDiskBuffer {@code true} to enable disk buffering
     */
    public void setUseDiskBuffer(boolean useDiskBuffer) {
        this.useDiskBuffer = useDiskBuffer;
    }

    private boolean trap(Runnable action) {
        try {
            action.run();
            lastException = null;
            return true;
        } catch (Exception e) {
            lastException = e;
            LOG.log(Level.WARNING, "Try* wrapper captured exception", e);
            return false;
        }
    }

    private void saveFacade(Document doc, String outputFile) throws IOException {
        doc.requestFullRewrite();
        doc.save(outputFile);
    }

    private void saveFacade(Document doc, OutputStream outputStream) throws IOException {
        doc.requestFullRewrite();
        doc.save(outputStream);
    }

    /**
     * Binds a PDF file to this editor.
     *
     * @param inputFile path to the PDF file
     * @return {@code true} on success
     */
    public boolean bindPdf(String inputFile) {
        try {
            this.document = new Document(inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
            return false;
        }
    }

    /**
     * Binds a PDF from an input stream.
     *
     * @param inputStream the input stream containing PDF data
     * @return {@code true} on success
     */
    public boolean bindPdf(InputStream inputStream) {
        try {
            this.document = new Document(inputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream", e);
            return false;
        }
    }

    /**
     * Binds an existing {@link Document} to this editor.
     *
     * @param document the document to bind
     * @return {@code true} on success
     */
    public boolean bindPdf(Document document) {
        if (document == null) {
            LOG.warning("Cannot bind null document");
            return false;
        }
        this.document = document;
        return true;
    }

    /**
     * Saves the bound document to a file.
     *
     * @param outputFile path to the output file
     * @return {@code true} on success
     */
    public boolean save(String outputFile) {
        try {
            saveFacade(document, outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PDF to file: " + outputFile, e);
            return false;
        }
    }

    /**
     * Saves the bound document to an output stream.
     *
     * @param outputStream the output stream
     * @return {@code true} on success
     */
    public boolean save(OutputStream outputStream) {
        try {
            saveFacade(document, outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PDF to stream", e);
            return false;
        }
    }

    /**
     * Concatenates multiple PDF files into one output file.
     * The first file is used as the base document; pages from remaining files are appended.
     *
     * @param inputFiles  array of input PDF file paths
     * @param outputFile  path to the output PDF file
     * @return {@code true} on success
     */
    public boolean concatenate(String[] inputFiles, String outputFile) {
        if (inputFiles == null || inputFiles.length == 0) {
            LOG.warning("No input files provided for concatenation");
            return false;
        }
        Map<String, Document> sourceDocs = new LinkedHashMap<>();
        try (Document baseDoc = new Document(inputFiles[0])) {
            PageCollection basePages = baseDoc.getPages();
            int basePageCountBefore = basePages.getCount();
            // Track per-source how many pages we appended and the appended-page
            // base index so we can re-target outline destinations.
            for (int i = 1; i < inputFiles.length; i++) {
                String sourcePath = inputFiles[i];
                Document srcDoc = sourceDocs.get(sourcePath);
                if (srcDoc == null) {
                    srcDoc = new Document(sourcePath);
                    sourceDocs.put(sourcePath, srcDoc);
                }
                int pagesBefore = basePages.getCount();
                basePages.add(srcDoc.getPages());
                int srcPageCount = basePages.getCount() - pagesBefore;
                if (copyOutlines) {
                    copyOutlines(srcDoc, baseDoc, pagesBefore, srcPageCount);
                }
                if (copyLogicalStructure) {
                    copyLogicalStructure(srcDoc, baseDoc, pagesBefore);
                }
                copyEmbeddedFiles(srcDoc, baseDoc);
                LOG.fine(() -> "Merged outlines from " + sourcePath
                        + " offset by " + pagesBefore);
            }
            saveFacade(baseDoc, outputFile);
            LOG.fine("Concatenated " + inputFiles.length + " files into " + outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to concatenate PDF files", e);
            return false;
        } finally {
            closeDocuments(sourceDocs.values());
        }
    }

    /**
     * Merges every entry from {@code src.getEmbeddedFiles()} into
     * {@code dst.getEmbeddedFiles()} as a fresh {@link FileSpecification} so
     * the embedded stream is owned by the destination's object table (the
     * source's COS objects die when its document closes). Mirrors the
     * Aspose contract that concatenate preserves attachments from every
     * input — PDFNEWNET-32261.
     */
    private void copyEmbeddedFiles(Document src, Document dst) {
        try {
            org.aspose.pdf.EmbeddedFileCollection srcEf = src.getEmbeddedFiles();
            if (srcEf == null || srcEf.getCount() == 0) {
                return;
            }
            org.aspose.pdf.EmbeddedFileCollection dstEf = dst.getEmbeddedFiles();
            if (dstEf == null) {
                return;
            }
            for (int i = 1; i <= srcEf.getCount(); i++) {
                org.aspose.pdf.FileSpecification srcFs = srcEf.get(i);
                if (srcFs == null) continue;
                String name = srcFs.getName();
                byte[] data = srcFs.getData();
                if (data == null) continue;
                org.aspose.pdf.FileSpecification cloned =
                        new org.aspose.pdf.FileSpecification(
                                new java.io.ByteArrayInputStream(data),
                                name != null ? name : ("file" + i));
                String desc = srcFs.getDescription();
                if (desc != null) cloned.setDescription(desc);
                String unicodeName = srcFs.getUnicodeFileName();
                if (unicodeName != null) cloned.setUnicodeFileName(unicodeName);
                String mime = srcFs.getMIMEType();
                if (mime != null) cloned.setMIMEType(mime);
                String rel = srcFs.getRelationship();
                if (rel != null) cloned.setRelationship(rel);
                dstEf.add(cloned);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to merge embedded files during concatenation", e);
        }
    }

    /**
     * Best-effort merge of the source document's logical structure into the
     * destination, used when {@link #setCopyLogicalStructure(boolean)} is
     * enabled. The current implementation only propagates the {@code /MarkInfo}
     * "Marked=true" hint — full {@code /StructTreeRoot} cross-document rewrite
     * is engine-level work and is out of scope for the facade. The flag still
     * exists so callers that toggle it for API parity don't fail, and so
     * the resulting destination is a valid tagged-PDF container.
     */
    private void copyLogicalStructure(Document src, Document dst, int pageOffset) {
        try {
            COSDictionary srcCatalog = src.getCatalog();
            COSDictionary dstCatalog = dst.getCatalog();
            if (srcCatalog == null || dstCatalog == null) {
                return;
            }
            COSBase srcStruct = srcCatalog.get(COSName.of("StructTreeRoot"));
            if (srcStruct == null) {
                return;
            }
            COSBase markInfo = dstCatalog.get(COSName.of("MarkInfo"));
            if (markInfo == null) {
                COSDictionary mi = new COSDictionary();
                mi.set(COSName.of("Marked"),
                        org.aspose.pdf.engine.cos.COSBoolean.TRUE);
                dstCatalog.set(COSName.of("MarkInfo"), mi);
            }
            LOG.fine(() -> "copyLogicalStructure: /MarkInfo propagated; "
                    + "full /StructTreeRoot cross-document merge is deferred");
        } catch (Exception e) {
            LOG.log(Level.FINE,
                    "copyLogicalStructure: best-effort copy failed (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Splits each page in {@code src} at the configured {@link PageBreak} y-offset
     * and appends both halves to {@code dst}. Implementation strategy:
     * <ol>
     *   <li>For each source page referenced by a {@link PageBreak}: append the
     *       whole page to {@code dst} twice; on the first copy crop the
     *       {@code MediaBox} to {@code [llx, y, urx, ury]} (top half), on the
     *       second copy crop to {@code [llx, lly, urx, y]} (bottom half).</li>
     *   <li>Pages that are not split are appended verbatim.</li>
     * </ol>
     * MediaBox cropping is the simplest faithful approximation of Aspose's
     * AddPageBreak — it preserves all content streams and resources so the
     * absorber test in PDFNET-49995 (text from both halves must be retrievable)
     * is satisfied without re-laying-out content.
     *
     * @param src    the source document
     * @param dst    the destination document (must not be null)
     * @param breaks page-break descriptors; if empty, all source pages are
     *               appended whole
     * @return {@code true} on success
     */
    public boolean addPageBreak(Document src, Document dst, PageBreak[] breaks) {
        if (src == null || dst == null) {
            LOG.warning("addPageBreak: source or destination document is null");
            return false;
        }
        try {
            java.util.Map<Integer, Double> breakByPage = new LinkedHashMap<>();
            if (breaks != null) {
                for (PageBreak pb : breaks) {
                    if (pb != null) {
                        breakByPage.put(pb.getPageNumber(), pb.getY());
                    }
                }
            }
            int srcCount = src.getPages().getCount();
            for (int p = 1; p <= srcCount; p++) {
                Page sourcePage = src.getPages().get(p);
                Double splitY = breakByPage.get(p);
                if (splitY == null) {
                    dst.getPages().add(sourcePage);
                    continue;
                }
                Rectangle mb = sourcePage.getMediaBox();
                if (mb == null) {
                    dst.getPages().add(sourcePage);
                    continue;
                }
                double y = splitY;
                // Top half: y..ury
                dst.getPages().add(sourcePage);
                Page top = dst.getPages().get(dst.getPages().getCount());
                top.setMediaBox(new Rectangle(mb.getLLX(), y, mb.getURX(), mb.getURY()));
                // Bottom half: lly..y
                dst.getPages().add(sourcePage);
                Page bottom = dst.getPages().get(dst.getPages().getCount());
                bottom.setMediaBox(new Rectangle(mb.getLLX(), mb.getLLY(), mb.getURX(), y));
            }
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "addPageBreak failed", e);
            return false;
        }
    }

    /**
     * Copies outline items from {@code src} into {@code dst}, re-targeting each
     * destination's page reference by adding {@code pageOffset} so it points at
     * the page index that was just appended into {@code dst}. Limits work to
     * the first {@code srcPageCount} pages of the source so destinations
     * referencing pages outside the imported range are dropped.
     */
    private void copyOutlines(Document src, Document dst, int pageOffset, int srcPageCount) {
        try {
            org.aspose.pdf.OutlineCollection srcOutlines = src.getOutlines();
            if (srcOutlines == null || srcOutlines.getCount() == 0) {
                return;
            }
            org.aspose.pdf.OutlineCollection dstOutlines = dst.getOutlines();
            for (org.aspose.pdf.OutlineItemCollection item : srcOutlines) {
                org.aspose.pdf.OutlineItemCollection copied = copyOutlineItem(
                        item, dstOutlines, dst, pageOffset, srcPageCount);
                if (copied != null) {
                    dstOutlines.add(copied);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to copy outlines during concatenation", e);
        }
    }

    private org.aspose.pdf.OutlineItemCollection copyOutlineItem(
            org.aspose.pdf.OutlineItemCollection srcItem,
            org.aspose.pdf.OutlineCollection dstOutlines,
            Document dst, int pageOffset, int srcPageCount) {
        try {
            org.aspose.pdf.OutlineItemCollection copy =
                    new org.aspose.pdf.OutlineItemCollection(dstOutlines);
            copy.setTitle(srcItem.getTitle());
            org.aspose.pdf.ExplicitDestination srcDest = srcItem.getDestination();
            if (srcDest != null) {
                int srcPageNumber = srcDest.getPageNumber();
                if (srcPageNumber >= 1 && srcPageNumber <= srcPageCount) {
                    int dstPageNumber = pageOffset + srcPageNumber;
                    if (dstPageNumber >= 1 && dstPageNumber <= dst.getPages().getCount()) {
                        Page targetPage = dst.getPages().get(dstPageNumber);
                        copy.setDestination(retargetDestination(srcDest, targetPage));
                    }
                }
            }
            return copy;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to copy outline item: " + srcItem.getTitle(), e);
            return null;
        }
    }

    private static org.aspose.pdf.ExplicitDestination retargetDestination(
            org.aspose.pdf.ExplicitDestination src, Page newPage) {
        // Recreate the destination of the same subclass so toCOSArray emits
        // the same /Fit … shape on save; fall back to /XYZ when the subclass
        // is unrecognised.
        if (src instanceof org.aspose.pdf.FitHExplicitDestination) {
            return new org.aspose.pdf.FitHExplicitDestination(newPage,
                    ((org.aspose.pdf.FitHExplicitDestination) src).getTop());
        }
        if (src instanceof org.aspose.pdf.XYZExplicitDestination) {
            org.aspose.pdf.XYZExplicitDestination xyz =
                    (org.aspose.pdf.XYZExplicitDestination) src;
            return new org.aspose.pdf.XYZExplicitDestination(
                    newPage, xyz.getLeft(), xyz.getTop(), xyz.getZoom());
        }
        return new org.aspose.pdf.XYZExplicitDestination(newPage, Double.NaN, Double.NaN, 0);
    }

    /**
     * Concatenates multiple PDF streams into one output stream.
     *
     * @param inputStreams  array of input streams containing PDF data
     * @param outputStream  the output stream
     * @return {@code true} on success
     */
    public boolean concatenate(InputStream[] inputStreams, OutputStream outputStream) {
        if (inputStreams == null || inputStreams.length == 0) {
            LOG.warning("No input streams provided for concatenation");
            return false;
        }
        Map<InputStream, Document> sourceDocs = new IdentityHashMap<>();
        try (Document baseDoc = new Document(inputStreams[0])) {
            PageCollection basePages = baseDoc.getPages();
            for (int i = 1; i < inputStreams.length; i++) {
                InputStream sourceStream = inputStreams[i];
                Document srcDoc = sourceDocs.get(sourceStream);
                if (srcDoc == null) {
                    srcDoc = new Document(sourceStream);
                    sourceDocs.put(sourceStream, srcDoc);
                }
                basePages.add(srcDoc.getPages());
                copyEmbeddedFiles(srcDoc, baseDoc);
            }
            saveFacade(baseDoc, outputStream);
            LOG.fine("Concatenated " + inputStreams.length + " streams");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to concatenate PDF streams", e);
            return false;
        } finally {
            closeDocuments(sourceDocs.values());
        }
    }

    /**
     * Concatenates multiple documents, appending all pages to the result document.
     *
     * @param documents  array of source documents
     * @param result     the target document to receive all pages
     * @return {@code true} on success
     */
    public boolean concatenate(Document[] documents, Document result) {
        if (documents == null || documents.length == 0 || result == null) {
            LOG.warning("Invalid arguments for document concatenation");
            return false;
        }
        try {
            PageCollection resultPages = result.getPages();
            for (Document doc : documents) {
                if (doc != null) {
                    resultPages.add(doc.getPages());
                    copyEmbeddedFiles(doc, result);
                }
            }
            LOG.fine("Concatenated " + documents.length + " documents");
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to concatenate documents", e);
            return false;
        }
    }

    private void closeDocuments(Iterable<Document> documents) {
        for (Document document : documents) {
            if (document == null) {
                continue;
            }
            try {
                document.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Failed to close concatenation source document", e);
            }
        }
    }

    /**
     * Extracts a range of pages from a PDF file to an output file.
     *
     * @param inputFile  path to the input PDF file
     * @param startPage  1-based start page number (inclusive)
     * @param endPage    1-based end page number (inclusive)
     * @param outputFile path to the output PDF file
     * @return {@code true} on success
     */
    public boolean extract(String inputFile, int startPage, int endPage, String outputFile) {
        try {
            Document srcDoc = new Document(inputFile);
            Document destDoc = new Document();
            PageCollection srcPages = srcDoc.getPages();
            PageCollection destPages = destDoc.getPages();
            for (int i = startPage; i <= endPage && i <= srcPages.getCount(); i++) {
                destPages.add(srcPages.get(i));
            }
            saveFacade(destDoc, outputFile);
            LOG.fine("Extracted pages " + startPage + "-" + endPage + " from " + inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract pages from " + inputFile, e);
            return false;
        }
    }

    /**
     * Extracts specific pages from a PDF file to an output file.
     *
     * @param inputFile   path to the input PDF file
     * @param pageNumbers array of 1-based page numbers to extract
     * @param outputFile  path to the output PDF file
     * @return {@code true} on success
     */
    public boolean extract(String inputFile, int[] pageNumbers, String outputFile) {
        try {
            Document srcDoc = new Document(inputFile);
            Document destDoc = new Document();
            PageCollection srcPages = srcDoc.getPages();
            PageCollection destPages = destDoc.getPages();
            for (int pageNum : pageNumbers) {
                if (pageNum >= 1 && pageNum <= srcPages.getCount()) {
                    destPages.add(srcPages.get(pageNum));
                }
            }
            saveFacade(destDoc, outputFile);
            LOG.fine("Extracted " + pageNumbers.length + " pages from " + inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to extract pages from " + inputFile, e);
            return false;
        }
    }

    /**
     * Splits a PDF file so that each page becomes a separate PDF document
     * saved in the specified output directory.
     *
     * @param inputFile path to the input PDF file
     * @param outputDir path to the output directory
     * @return {@code true} on success
     */
    /**
     * Splits the input PDF into one byte-array per page, returned as an
     * array of {@link java.io.ByteArrayOutputStream}. Mirrors the C# overload
     * {@code MemoryStream[] PdfFileEditor.SplitToPages(string)}.
     *
     * @param inputFile path to the input PDF file
     * @return one byte-array stream per page (caller can {@code writeTo} or
     *         {@code toByteArray}); empty array on failure
     */
    public java.io.ByteArrayOutputStream[] splitToPages(String inputFile) {
        try (Document srcDoc = new Document(inputFile)) {
            PageCollection srcPages = srcDoc.getPages();
            int n = srcPages.getCount();
            java.io.ByteArrayOutputStream[] result = new java.io.ByteArrayOutputStream[n];
            for (int i = 1; i <= n; i++) {
                try (Document singlePage = new Document()) {
                    singlePage.getPages().add(srcPages.get(i));
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    singlePage.save(baos);
                    result[i - 1] = baos;
                }
            }
            LOG.fine("Split " + n + " pages from " + inputFile + " into in-memory streams");
            return result;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to split PDF to pages: " + inputFile, e);
            return new java.io.ByteArrayOutputStream[0];
        }
    }

    public boolean splitToPages(String inputFile, String outputDir) {
        try {
            Document srcDoc = new Document(inputFile);
            PageCollection srcPages = srcDoc.getPages();
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            for (int i = 1; i <= srcPages.getCount(); i++) {
                Document singlePage = new Document();
                singlePage.getPages().add(srcPages.get(i));
                String outPath = new File(dir, "page_" + i + ".pdf").getAbsolutePath();
                saveFacade(singlePage, outPath);
            }
            LOG.fine("Split " + srcPages.getCount() + " pages from " + inputFile + " into " + outputDir);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to split PDF: " + inputFile, e);
            return false;
        }
    }

    /**
     * Extracts pages {@code 1..pageNum} from the input stream into the output stream.
     *
     * @param inputStream the source PDF stream
     * @param pageNum the 1-based last page to keep
     * @param outputStream the destination PDF stream
     * @return {@code true} on success
     */
    public boolean splitFromFirst(InputStream inputStream, int pageNum, OutputStream outputStream) {
        try {
            Document srcDoc = new Document(inputStream);
            Document destDoc = new Document();
            PageCollection srcPages = srcDoc.getPages();
            PageCollection destPages = destDoc.getPages();
            int lastPage = Math.min(pageNum, srcPages.getCount());
            for (int i = 1; i <= lastPage; i++) {
                destPages.add(srcPages.get(i));
            }
            saveFacade(destDoc, outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to splitFromFirst from input stream", e);
            return false;
        }
    }

    /**
     * Extracts pages {@code pageNum..lastPage} from the input stream into the output stream.
     *
     * @param inputStream the source PDF stream
     * @param pageNum the 1-based first page to keep
     * @param outputStream the destination PDF stream
     * @return {@code true} on success
     */
    public boolean splitToEnd(InputStream inputStream, int pageNum, OutputStream outputStream) {
        try {
            Document srcDoc = new Document(inputStream);
            Document destDoc = new Document();
            PageCollection srcPages = srcDoc.getPages();
            PageCollection destPages = destDoc.getPages();
            int startPage = Math.max(1, pageNum);
            for (int i = startPage; i <= srcPages.getCount(); i++) {
                destPages.add(srcPages.get(i));
            }
            saveFacade(destDoc, outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to splitToEnd from input stream", e);
            return false;
        }
    }

    /**
     * Inserts pages from a source file into a target file at the specified position.
     *
     * @param inputFile      path to the input PDF file
     * @param insertPosition 1-based position where pages should be inserted
     * @param portFile       path to the PDF file containing pages to insert
     * @param startPage      1-based start page in portFile (inclusive)
     * @param endPage        1-based end page in portFile (inclusive)
     * @param outputFile     path to the output PDF file
     * @return {@code true} on success
     */
    public boolean insert(String inputFile, int insertPosition, String portFile,
                          int startPage, int endPage, String outputFile) {
        try {
            Document baseDoc = new Document(inputFile);
            Document portDoc = new Document(portFile);
            PageCollection basePages = baseDoc.getPages();
            PageCollection portPages = portDoc.getPages();
            int pos = insertPosition;
            for (int i = startPage; i <= endPage && i <= portPages.getCount(); i++) {
                basePages.insert(pos, portPages.get(i));
                pos++;
            }
            saveFacade(baseDoc, outputFile);
            LOG.fine("Inserted pages from " + portFile + " into " + inputFile + " at position " + insertPosition);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to insert pages", e);
            return false;
        }
    }

    /**
     * Deletes specified pages from a PDF file and saves the result.
     * Pages are deleted in reverse order to preserve correct indices.
     *
     * @param inputFile   path to the input PDF file
     * @param pageNumbers array of 1-based page numbers to delete
     * @param outputFile  path to the output PDF file
     * @return {@code true} on success
     */
    public boolean delete(String inputFile, int[] pageNumbers, String outputFile) {
        try {
            Document doc = new Document(inputFile);
            PageCollection pages = doc.getPages();
            int[] sorted = pageNumbers.clone();
            Arrays.sort(sorted);
            // Delete in reverse order to preserve indices
            for (int i = sorted.length - 1; i >= 0; i--) {
                if (sorted[i] >= 1 && sorted[i] <= pages.getCount()) {
                    pages.delete(sorted[i]);
                }
            }
            saveFacade(doc, outputFile);
            LOG.fine("Deleted " + pageNumbers.length + " pages from " + inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to delete pages from " + inputFile, e);
            return false;
        }
    }

    /**
     * Arranges pages into an N-up layout.
     * <p>
     * <strong>Stub implementation.</strong> This method requires a layout engine
     * and is not yet implemented.
     *
     * @param inputFile  path to the input PDF file
     * @param outputFile path to the output PDF file
     * @param nX         number of pages horizontally
     * @param nY         number of pages vertically
     * @return {@code false} (not implemented)
     */
    public boolean makeNUp(String inputFile, String outputFile, int nX, int nY) {
        LOG.warning("makeNUp is not yet implemented");
        return false;
    }

    /**
     * Resizes contents of all pages in the document by wrapping each page's
     * content stream in a {@code q  scaleX 0 0 scaleY tx ty cm  ... Q} matrix
     * derived from {@code parameters}.
     *
     * @param document   the document whose pages to resize
     * @param parameters the resize parameters
     */
    public void resizeContents(Document document, ContentsResizeParameters parameters) {
        if (document == null || parameters == null) {
            LOG.warning("resizeContents: null arguments");
            return;
        }
        int n;
        try {
            n = document.getPages().getCount();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "resizeContents: failed to enumerate pages", e);
            return;
        }
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i + 1;
        resizeContents(document, all, parameters);
    }

    /**
     * Resizes contents of specific pages in the document by wrapping each
     * targeted page's content stream in a {@code q ... cm ... Q} matrix
     * derived from {@code parameters}. Percent values are resolved against
     * the corresponding page dimension (width for left/right/contentsWidth,
     * height for top/bottom/contentsHeight); margins+content that do not sum
     * to the page dimension are auto-adjusted proportionally.
     *
     * @param document    the document whose pages to resize
     * @param pageNumbers array of 1-based page numbers to resize
     * @param parameters  the resize parameters
     */
    public void resizeContents(Document document, int[] pageNumbers, ContentsResizeParameters parameters) {
        if (document == null || parameters == null || pageNumbers == null) {
            LOG.warning("resizeContents: null arguments");
            return;
        }
        for (int pageNum : pageNumbers) {
            try {
                Page page = document.getPages().get(pageNum);
                applyResizeToPage(page, parameters);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to resize page " + pageNum, e);
            }
        }
    }

    /**
     * File-path overload mirroring {@code PdfFileEditor.ResizeContents(string, string, ContentsResizeParameters)}
     * in Aspose. Opens {@code inputFile}, applies the resize, saves to
     * {@code outputFile}.
     *
     * @param inputFile  path to the source PDF
     * @param outputFile path to the destination PDF
     * @param parameters the resize parameters
     * @return {@code true} on success
     */
    public boolean resizeContents(String inputFile, String outputFile,
                                  ContentsResizeParameters parameters) {
        try (Document doc = new Document(inputFile)) {
            resizeContents(doc, parameters);
            doc.save(outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to resizeContents " + inputFile + " → " + outputFile, e);
            return false;
        }
    }

    /**
     * Resolves a {@link ContentsResizeValue} to absolute PDF units. A null
     * value is treated as zero so that callers can omit margins they do not
     * want to set.
     */
    private static double resolveResizeValue(ContentsResizeValue v, double pageDim) {
        if (v == null) return 0;
        return v.isPercent() ? (v.getValue() * pageDim / 100.0) : v.getValue();
    }

    /**
     * Computes scaleX/scaleY/tx/ty from {@code parameters} relative to
     * {@code page}'s media box, then wraps its content stream in
     * {@code q ... cm ... Q}.
     */
    private void applyResizeToPage(Page page, ContentsResizeParameters parameters) throws IOException {
        Rectangle media = page.getMediaBox();
        if (media == null) {
            LOG.warning("Page has no media box, skipping resize");
            return;
        }
        double pageWidth = media.getWidth();
        double pageHeight = media.getHeight();
        if (pageWidth <= 0 || pageHeight <= 0) {
            LOG.warning("Page has non-positive media box, skipping resize");
            return;
        }

        double lm = resolveResizeValue(parameters.getLeftMargin(), pageWidth);
        double rm = resolveResizeValue(parameters.getRightMargin(), pageWidth);
        double tm = resolveResizeValue(parameters.getTopMargin(), pageHeight);
        double bm = resolveResizeValue(parameters.getBottomMargin(), pageHeight);

        // A null content width/height means "compute automatically as
        // pageDim - leftMargin - rightMargin" (C# Aspose semantics). Only
        // explicit values participate in the proportional auto-adjust below.
        double cw;
        if (parameters.getContentsWidth() == null) {
            cw = pageWidth - lm - rm;
        } else {
            cw = resolveResizeValue(parameters.getContentsWidth(), pageWidth);
        }
        double ch;
        if (parameters.getContentsHeight() == null) {
            ch = pageHeight - tm - bm;
        } else {
            ch = resolveResizeValue(parameters.getContentsHeight(), pageHeight);
        }

        // Auto-adjust if sums differ from page dimensions — match Aspose's
        // tolerant behavior; callers often over- or under-specify margins.
        double sumX = lm + cw + rm;
        if (sumX > 0 && Math.abs(sumX - pageWidth) > 0.1) {
            double k = pageWidth / sumX;
            lm *= k; cw *= k; rm *= k;
        }
        double sumY = tm + ch + bm;
        if (sumY > 0 && Math.abs(sumY - pageHeight) > 0.1) {
            double k = pageHeight / sumY;
            tm *= k; ch *= k; bm *= k;
        }

        if (cw <= 0 || ch <= 0) {
            LOG.warning("Resolved content area is non-positive, skipping resize");
            return;
        }

        double scaleX = cw / pageWidth;
        double scaleY = ch / pageHeight;
        // PDF origin is lower-left, so horizontal translation = lm and vertical
        // translation = bm (bottom margin).
        double tx = lm;
        double ty = bm;
        PdfPageEditor.wrapPageContent(page, scaleX, 0, 0, scaleY, tx, ty);
        transformAnnotations(page, scaleX, scaleY, tx, ty);
    }

    /**
     * Applies an affine transformation {@code [scaleX 0 0 scaleY tx ty]} to
     * every annotation on the page so they stay visually anchored to the
     * resized content. Mirrors the C# Aspose {@code ResizeContents} behavior.
     *
     * <p>Transforms:</p>
     * <ul>
     *   <li>{@code Annotation.Rect} — both corners</li>
     *   <li>{@code TextMarkupAnnotation.QuadPoints} (Highlight/Underline/StrikeOut/Squiggly)</li>
     *   <li>{@code InkAnnotation.InkList} — every point in every stroke</li>
     * </ul>
     *
     * @param page   the page whose annotations to transform
     * @param scaleX horizontal scale factor
     * @param scaleY vertical scale factor
     * @param tx     horizontal translation in points
     * @param ty     vertical translation in points
     */
    private static void transformAnnotations(Page page, double scaleX, double scaleY,
                                             double tx, double ty) {
        org.aspose.pdf.annotations.AnnotationCollection annots = page.getAnnotations();
        if (annots == null) return;

        int n;
        try {
            n = annots.size();
        } catch (Exception e) {
            return;
        }

        for (int i = 1; i <= n; i++) {
            org.aspose.pdf.annotations.Annotation a;
            try {
                a = annots.get(i);
            } catch (Exception e) {
                continue;
            }
            if (a == null) continue;

            Rectangle r = a.getRect();
            if (r != null) {
                a.setRect(new Rectangle(
                        r.getLLX() * scaleX + tx,
                        r.getLLY() * scaleY + ty,
                        r.getURX() * scaleX + tx,
                        r.getURY() * scaleY + ty));
            }

            if (a instanceof org.aspose.pdf.annotations.HighlightAnnotation) {
                org.aspose.pdf.annotations.HighlightAnnotation tma =
                        (org.aspose.pdf.annotations.HighlightAnnotation) a;
                double[] qp = transformXYArray(tma.getQuadPoints(), scaleX, scaleY, tx, ty);
                if (qp != null) tma.setQuadPoints(qp);
            } else if (a instanceof org.aspose.pdf.annotations.UnderlineAnnotation) {
                org.aspose.pdf.annotations.UnderlineAnnotation tma =
                        (org.aspose.pdf.annotations.UnderlineAnnotation) a;
                double[] qp = transformXYArray(tma.getQuadPoints(), scaleX, scaleY, tx, ty);
                if (qp != null) tma.setQuadPoints(qp);
            } else if (a instanceof org.aspose.pdf.annotations.StrikeOutAnnotation) {
                org.aspose.pdf.annotations.StrikeOutAnnotation tma =
                        (org.aspose.pdf.annotations.StrikeOutAnnotation) a;
                double[] qp = transformXYArray(tma.getQuadPoints(), scaleX, scaleY, tx, ty);
                if (qp != null) tma.setQuadPoints(qp);
            } else if (a instanceof org.aspose.pdf.annotations.SquigglyAnnotation) {
                org.aspose.pdf.annotations.SquigglyAnnotation tma =
                        (org.aspose.pdf.annotations.SquigglyAnnotation) a;
                double[] qp = transformXYArray(tma.getQuadPoints(), scaleX, scaleY, tx, ty);
                if (qp != null) tma.setQuadPoints(qp);
            }

            if (a instanceof org.aspose.pdf.annotations.InkAnnotation) {
                org.aspose.pdf.annotations.InkAnnotation ink =
                        (org.aspose.pdf.annotations.InkAnnotation) a;
                java.util.List<double[]> strokes = ink.getInkList();
                if (strokes != null && !strokes.isEmpty()) {
                    java.util.List<double[]> out = new java.util.ArrayList<>(strokes.size());
                    for (double[] stroke : strokes) {
                        out.add(transformXYArray(stroke, scaleX, scaleY, tx, ty));
                    }
                    ink.setInkList(out);
                }
            }
        }
    }

    /**
     * Transforms a flat array of (x, y) pairs by an affine transform.
     * Returns a new array, or {@code null} if the input is null/empty.
     */
    private static double[] transformXYArray(double[] xy, double scaleX, double scaleY,
                                             double tx, double ty) {
        if (xy == null || xy.length == 0) return null;
        double[] out = new double[xy.length];
        for (int i = 0; i + 1 < xy.length; i += 2) {
            out[i]     = xy[i]     * scaleX + tx;
            out[i + 1] = xy[i + 1] * scaleY + ty;
        }
        return out;
    }

    /**
     * Parameters for resizing page contents.
     */
    public static class ContentsResizeParameters {

        private ContentsResizeValue leftMargin;
        private ContentsResizeValue rightMargin;
        private ContentsResizeValue topMargin;
        private ContentsResizeValue bottomMargin;
        private ContentsResizeValue contentsWidth;
        private ContentsResizeValue contentsHeight;

        /**
         * Creates resize parameters with the specified margins and content dimensions.
         *
         * @param leftMargin     the left margin value
         * @param contentsWidth  the content width value
         * @param rightMargin    the right margin value
         * @param topMargin      the top margin value
         * @param contentsHeight the content height value
         * @param bottomMargin   the bottom margin value
         */
        public ContentsResizeParameters(
                ContentsResizeValue leftMargin, ContentsResizeValue contentsWidth, ContentsResizeValue rightMargin,
                ContentsResizeValue topMargin, ContentsResizeValue contentsHeight, ContentsResizeValue bottomMargin) {
            this.leftMargin = leftMargin;
            this.contentsWidth = contentsWidth;
            this.rightMargin = rightMargin;
            this.topMargin = topMargin;
            this.contentsHeight = contentsHeight;
            this.bottomMargin = bottomMargin;
        }

        /**
         * Gets the left margin value.
         *
         * @return the left margin
         */
        public ContentsResizeValue getLeftMargin() { return leftMargin; }

        /**
         * Gets the right margin value.
         *
         * @return the right margin
         */
        public ContentsResizeValue getRightMargin() { return rightMargin; }

        /**
         * Gets the top margin value.
         *
         * @return the top margin
         */
        public ContentsResizeValue getTopMargin() { return topMargin; }

        /**
         * Gets the bottom margin value.
         *
         * @return the bottom margin
         */
        public ContentsResizeValue getBottomMargin() { return bottomMargin; }

        /**
         * Gets the contents width value.
         *
         * @return the contents width
         */
        public ContentsResizeValue getContentsWidth() { return contentsWidth; }

        /**
         * Gets the contents height value.
         *
         * @return the contents height
         */
        public ContentsResizeValue getContentsHeight() { return contentsHeight; }

        /** Sets the left margin value. */
        public void setLeftMargin(ContentsResizeValue v) { this.leftMargin = v; }

        /** Sets the right margin value. */
        public void setRightMargin(ContentsResizeValue v) { this.rightMargin = v; }

        /** Sets the top margin value. */
        public void setTopMargin(ContentsResizeValue v) { this.topMargin = v; }

        /** Sets the bottom margin value. */
        public void setBottomMargin(ContentsResizeValue v) { this.bottomMargin = v; }

        /** Sets the contents width value. */
        public void setContentsWidth(ContentsResizeValue v) { this.contentsWidth = v; }

        /** Sets the contents height value. */
        public void setContentsHeight(ContentsResizeValue v) { this.contentsHeight = v; }

        /**
         * Creates a {@code ContentsResizeParameters} with the specified content
         * dimensions and zero margins. Margins can be adjusted afterward via
         * the setters.
         *
         * <p>Shortcut for the common case "resize page to W×H then adjust
         * margins separately" — mirrors C# Aspose
         * {@code PdfFileEditor.ContentsResizeParameters.PageResize(width, height)}.</p>
         *
         * @param width  target content width in points (absolute, not percent)
         * @param height target content height in points
         * @return a new {@code ContentsResizeParameters} with content =
         *         (width, height) and all margins = 0
         */
        public static ContentsResizeParameters pageResize(double width, double height) {
            return new ContentsResizeParameters(
                    ContentsResizeValue.units(0),
                    ContentsResizeValue.units(width),
                    ContentsResizeValue.units(0),
                    ContentsResizeValue.units(0),
                    ContentsResizeValue.units(height),
                    ContentsResizeValue.units(0));
        }
    }

    /**
     * Represents a value used in content resizing parameters.
     * Can be specified as an absolute value in points or as a percentage.
     */
    public static class ContentsResizeValue {

        private final double value;
        private final boolean isPercent;

        private ContentsResizeValue(double value, boolean isPercent) {
            this.value = value;
            this.isPercent = isPercent;
        }

        /**
         * Creates a percentage-based resize value.
         *
         * @param value the percentage value
         * @return a new {@code ContentsResizeValue}
         */
        public static ContentsResizeValue percents(double value) {
            return new ContentsResizeValue(value, true);
        }

        /**
         * Creates an absolute resize value in points.
         *
         * @param value the value in points
         * @return a new {@code ContentsResizeValue}
         */
        public static ContentsResizeValue units(double value) {
            return new ContentsResizeValue(value, false);
        }

        /**
         * Gets the numeric value.
         *
         * @return the value
         */
        public double getValue() { return value; }

        /**
         * Returns whether this value is a percentage.
         *
         * @return {@code true} if percentage, {@code false} if absolute
         */
        public boolean isPercent() { return isPercent; }
    }

    /**
     * Arranges pages into a booklet layout.
     * <p>
     * <strong>Stub implementation.</strong> This method requires a layout engine
     * and is not yet implemented.
     *
     * @param inputFile  path to the input PDF file
     * @param outputFile path to the output PDF file
     * @return {@code false} (not implemented)
     */
    public boolean makeBooklet(String inputFile, String outputFile) {
        try {
            Document srcDoc = new Document(inputFile);
            PageSize bookletSize = inferLandscapeBookletSize(srcDoc);
            Document result = buildBooklet(srcDoc, bookletSize);
            saveFacade(result, outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to make booklet from " + inputFile, e);
            return false;
        }
    }

    /**
     * Arranges pages from the input stream into a booklet layout saved to the output stream.
     *
     * @param inputStream source PDF stream
     * @param outputStream destination PDF stream
     * @return {@code true} on success
     */
    public boolean makeBooklet(InputStream inputStream, OutputStream outputStream) {
        try {
            Document srcDoc = new Document(inputStream);
            PageSize bookletSize = inferLandscapeBookletSize(srcDoc);
            Document result = buildBooklet(srcDoc, bookletSize);
            saveFacade(result, outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to make booklet from input stream", e);
            return false;
        }
    }

    /**
     * Arranges pages into a booklet layout using the specified target page size.
     *
     * @param inputFile source PDF file
     * @param outputFile destination PDF file
     * @param pageSize target booklet page size
     * @return {@code true} on success
     */
    public boolean makeBooklet(String inputFile, String outputFile, PageSize pageSize) {
        try {
            Document srcDoc = new Document(inputFile);
            Document result = buildBooklet(srcDoc, pageSize);
            saveFacade(result, outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to make booklet from " + inputFile + " using " + pageSize, e);
            return false;
        }
    }

    /**
     * Arranges pages from the input stream into a booklet layout with the specified target page size.
     *
     * @param inputStream source PDF stream
     * @param outputStream destination PDF stream
     * @param pageSize target booklet page size
     * @return {@code true} on success
     */
    public boolean makeBooklet(InputStream inputStream, OutputStream outputStream, PageSize pageSize) {
        try {
            Document srcDoc = new Document(inputStream);
            Document result = buildBooklet(srcDoc, pageSize);
            saveFacade(result, outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to make booklet from input stream using " + pageSize, e);
            return false;
        }
    }

    /**
     * Concatenates two PDF files into one output file.
     *
     * @param firstInputFile  path to the first input PDF
     * @param secondInputFile path to the second input PDF
     * @param outputFile      path to the output PDF file
     * @return {@code true} on success
     */
    public boolean concatenate(String firstInputFile, String secondInputFile, String outputFile) {
        return concatenate(new String[]{firstInputFile, secondInputFile}, outputFile);
    }

    /**
     * Appends a range of pages from {@code portFile} to the end of {@code inputFile},
     * writing the result to {@code outputFile}.
     *
     * @param inputFile  path to the base PDF file
     * @param portFile   path to the PDF file containing pages to append
     * @param startPage  1-based start page in portFile (inclusive)
     * @param endPage    1-based end page in portFile (inclusive)
     * @param outputFile path to the output PDF file
     * @return {@code true} on success
     */
    public boolean append(String inputFile, String portFile, int startPage, int endPage, String outputFile) {
        try {
            Document baseDoc = new Document(inputFile);
            Document portDoc = new Document(portFile);
            PageCollection basePages = baseDoc.getPages();
            PageCollection portPages = portDoc.getPages();
            for (int i = startPage; i <= endPage && i <= portPages.getCount(); i++) {
                basePages.add(portPages.get(i));
            }
            saveFacade(baseDoc, outputFile);
            LOG.fine("Appended pages " + startPage + "-" + endPage + " from " + portFile + " to " + inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to append pages", e);
            return false;
        }
    }

    /**
     * Extracts pages {@code 1..pageNum} into {@code outputFile}.
     *
     * @param inputFile  path to the input PDF file
     * @param pageNum    1-based last page to keep (inclusive)
     * @param outputFile path to the output PDF file
     * @return {@code true} on success
     */
    public boolean splitFromFirst(String inputFile, int pageNum, String outputFile) {
        return extract(inputFile, 1, pageNum, outputFile);
    }

    /**
     * Extracts pages {@code pageNum..lastPage} into {@code outputFile}.
     *
     * @param inputFile  path to the input PDF file
     * @param pageNum    1-based first page to keep (inclusive)
     * @param outputFile path to the output PDF file
     * @return {@code true} on success
     */
    public boolean splitToEnd(String inputFile, int pageNum, String outputFile) {
        try {
            Document srcDoc = new Document(inputFile);
            int last = srcDoc.getPages().getCount();
            return extract(inputFile, pageNum, last, outputFile);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to splitToEnd: " + inputFile, e);
            return false;
        }
    }

    private PageSize inferLandscapeBookletSize(Document srcDoc) throws IOException {
        Page firstPage = srcDoc.getPages().getCount() > 0 ? srcDoc.getPages().get(1) : null;
        Rectangle rect = firstPage != null ? firstPage.getRect() : null;
        double width = rect != null ? rect.getWidth() : PageSize.A4.getWidth();
        double height = rect != null ? rect.getHeight() : PageSize.A4.getHeight();
        return new PageSize(height, width);
    }

    private Document buildBooklet(Document srcDoc, PageSize pageSize) throws IOException {
        if (pageSize == null) {
            throw new IllegalArgumentException("pageSize must not be null");
        }
        Document result = new Document();
        PageCollection srcPages = srcDoc.getPages();
        int sourceCount = srcPages.getCount();
        int bookletPageCount = (sourceCount + 1) / 2;
        for (int i = 0; i < bookletPageCount; i++) {
            Page targetPage = result.getPages().add();
            targetPage.setPageSize(pageSize.getWidth(), pageSize.getHeight());

            int leftPageNum = (i * 2) + 1;
            int rightPageNum = leftPageNum + 1;
            if (leftPageNum <= sourceCount) {
                placePageAsForm(result, targetPage, srcPages.get(leftPageNum), "FmL" + (i + 1), true);
            }
            if (rightPageNum <= sourceCount) {
                placePageAsForm(result, targetPage, srcPages.get(rightPageNum), "FmR" + (i + 1), false);
            }
        }
        return result;
    }

    private void placePageAsForm(Document targetDocument, Page targetPage, Page sourcePage,
                                 String resourceName, boolean leftHalf) throws IOException {
        Rectangle sourceRect = sourcePage.getRect();
        if (sourceRect == null) {
            sourceRect = new Rectangle(0, 0, 612, 792);
        }
        Rectangle targetRect = targetPage.getRect();
        if (targetRect == null) {
            targetRect = new Rectangle(0, 0, targetPage.getPageInfo().getWidth(), targetPage.getPageInfo().getHeight());
        }
        double slotWidth = targetRect.getWidth() / 2.0;
        double slotHeight = targetRect.getHeight();
        double scale = Math.min(slotWidth / sourceRect.getWidth(), slotHeight / sourceRect.getHeight());
        double scaledWidth = sourceRect.getWidth() * scale;
        double scaledHeight = sourceRect.getHeight() * scale;
        double xOffset = leftHalf
                ? (slotWidth - scaledWidth) / 2.0
                : slotWidth + ((slotWidth - scaledWidth) / 2.0);
        double yOffset = (slotHeight - scaledHeight) / 2.0;

        COSStream formStream = createFormXObject(targetDocument, sourcePage, sourceRect);
        COSObjectReference formRef = targetDocument.registerImportedObject(formStream);

        Resources resources = targetPage.getResources();
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            xObjects = new COSDictionary();
            resources.getCOSDictionary().set(COSName.of("XObject"), xObjects);
        }
        xObjects.set(COSName.of(resourceName), formRef);

        String ops = "q\n"
                + formatMatrix(scale, 0, 0, scale, xOffset, yOffset)
                + "/" + resourceName + " Do\n"
                + "Q\n";
        targetPage.appendToContentStream(ops.getBytes(StandardCharsets.US_ASCII));
    }

    private COSStream createFormXObject(Document targetDocument, Page sourcePage, Rectangle sourceRect) throws IOException {
        COSStream formStream = new COSStream();
        formStream.set("Type", COSName.of("XObject"));
        formStream.set("Subtype", COSName.of("Form"));
        formStream.set("BBox", sourceRect.toCOSArray());

        COSDictionary sourceResources = sourcePage.getResources() != null
                ? sourcePage.getResources().getCOSDictionary()
                : null;
        if (sourceResources != null) {
            COSCloner cloner = new COSCloner(targetDocument::registerImportedObject);
            COSBase clonedResources = cloner.cloneAny(sourceResources);
            if (clonedResources instanceof COSDictionary) {
                formStream.set("Resources", clonedResources);
            }
        }

        byte[] sourceData = readPageContentBytes(sourcePage);
        formStream.setDecodedData(sourceData != null ? sourceData : new byte[0]);
        return formStream;
    }

    private byte[] readPageContentBytes(Page sourcePage) throws IOException {
        COSBase sourceContents = sourcePage.getRawContents();
        if (sourceContents instanceof COSObjectReference) {
            sourceContents = ((COSObjectReference) sourceContents).dereference();
        }
        if (sourceContents instanceof COSStream) {
            return ((COSStream) sourceContents).getDecodedData();
        }
        if (sourceContents instanceof COSArray) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            COSArray arr = (COSArray) sourceContents;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item instanceof COSObjectReference) {
                    item = ((COSObjectReference) item).dereference();
                }
                if (item instanceof COSStream) {
                    byte[] data = ((COSStream) item).getDecodedData();
                    if (data != null && data.length > 0) {
                        baos.write(data);
                        baos.write('\n');
                    }
                }
            }
            return baos.toByteArray();
        }
        return new byte[0];
    }

    private String formatMatrix(double a, double b, double c, double d, double e, double f) {
        return String.format(java.util.Locale.US, "%.4f %.4f %.4f %.4f %.4f %.4f cm\n", a, b, c, d, e, f);
    }

    /**
     * Expands the MediaBox and CropBox of the specified pages by the given margins (in points),
     * preserving the original content position (content shifts by {@code left} and {@code bottom}).
     *
     * @param inputFile  path to the input PDF file
     * @param outputFile path to the output PDF file
     * @param pages      1-based page numbers to modify, or {@code null} for all pages
     * @param left       left margin to add (points)
     * @param right      right margin to add (points)
     * @param top        top margin to add (points)
     * @param bottom     bottom margin to add (points)
     * @return {@code true} on success
     */
    public boolean addMargins(String inputFile, String outputFile, int[] pages,
                              double left, double right, double top, double bottom) {
        try {
            Document doc = new Document(inputFile);
            PageCollection pc = doc.getPages();
            int[] target;
            if (pages == null || pages.length == 0) {
                target = new int[pc.getCount()];
                for (int i = 0; i < target.length; i++) target[i] = i + 1;
            } else {
                target = pages;
            }
            for (int idx : target) {
                if (idx < 1 || idx > pc.getCount()) continue;
                Page page = pc.get(idx);
                Rectangle mb = page.getMediaBox();
                if (mb != null) {
                    page.setMediaBox(new Rectangle(
                            mb.getLLX() - left,
                            mb.getLLY() - bottom,
                            mb.getURX() + right,
                            mb.getURY() + top));
                }
                Rectangle cb = page.getCropBox();
                if (cb != null) {
                    page.setCropBox(new Rectangle(
                            cb.getLLX() - left,
                            cb.getLLY() - bottom,
                            cb.getURX() + right,
                            cb.getURY() + top));
                }
            }
            saveFacade(doc, outputFile);
            LOG.fine("addMargins applied to " + target.length + " page(s) in " + inputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed addMargins: " + inputFile, e);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Try* wrappers — capture the exception via getLastException() and
    // return true/false. Useful for Aspose API parity.
    // ---------------------------------------------------------------------

    /**
     * Try-variant of {@link #concatenate(String[], String)}.
     *
     * @param inputFiles input files
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception (see {@link #getLastException()})
     */
    public boolean tryConcatenate(String[] inputFiles, String outputFile) {
        return trap(() -> {
            if (!concatenate(inputFiles, outputFile)) {
                throw new RuntimeException("concatenate returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #concatenate(InputStream[], OutputStream)}.
     *
     * @param inputStreams input streams
     * @param outputStream output stream
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryConcatenate(InputStream[] inputStreams, OutputStream outputStream) {
        return trap(() -> {
            if (!concatenate(inputStreams, outputStream)) {
                throw new RuntimeException("concatenate returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #makeBooklet(String, String)}.
     *
     * @param inputFile  input file
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryMakeBooklet(String inputFile, String outputFile) {
        return trap(() -> {
            if (!makeBooklet(inputFile, outputFile)) {
                throw new RuntimeException("makeBooklet returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #makeNUp(String, String, int, int)}.
     *
     * @param inputFile  input file
     * @param outputFile output file
     * @param nX         horizontal count
     * @param nY         vertical count
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryMakeNUp(String inputFile, String outputFile, int nX, int nY) {
        return trap(() -> {
            if (!makeNUp(inputFile, outputFile, nX, nY)) {
                throw new RuntimeException("makeNUp returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #extract(String, int, int, String)}.
     *
     * @param inputFile  input file
     * @param startPage  start page (inclusive)
     * @param endPage    end page (inclusive)
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryExtract(String inputFile, int startPage, int endPage, String outputFile) {
        return trap(() -> {
            if (!extract(inputFile, startPage, endPage, outputFile)) {
                throw new RuntimeException("extract returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #extract(String, int[], String)}.
     *
     * @param inputFile   input file
     * @param pageNumbers pages to extract
     * @param outputFile  output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryExtract(String inputFile, int[] pageNumbers, String outputFile) {
        return trap(() -> {
            if (!extract(inputFile, pageNumbers, outputFile)) {
                throw new RuntimeException("extract returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #resizeContents(Document, ContentsResizeParameters)}.
     *
     * @param document   the document
     * @param parameters the resize parameters
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryResize(Document document, ContentsResizeParameters parameters) {
        return trap(() -> resizeContents(document, parameters));
    }

    /**
     * Try-variant of {@link #append(String, String, int, int, String)}.
     *
     * @param inputFile  base file
     * @param portFile   file with pages to append
     * @param startPage  start page in portFile
     * @param endPage    end page in portFile
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryAppend(String inputFile, String portFile, int startPage, int endPage, String outputFile) {
        return trap(() -> {
            if (!append(inputFile, portFile, startPage, endPage, outputFile)) {
                throw new RuntimeException("append returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #insert(String, int, String, int, int, String)}.
     *
     * @param inputFile      base file
     * @param insertPosition insert position
     * @param portFile       file with pages to insert
     * @param startPage      start page in portFile
     * @param endPage        end page in portFile
     * @param outputFile     output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryInsert(String inputFile, int insertPosition, String portFile,
                             int startPage, int endPage, String outputFile) {
        return trap(() -> {
            if (!insert(inputFile, insertPosition, portFile, startPage, endPage, outputFile)) {
                throw new RuntimeException("insert returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #delete(String, int[], String)}.
     *
     * @param inputFile   input file
     * @param pageNumbers pages to delete
     * @param outputFile  output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean tryDelete(String inputFile, int[] pageNumbers, String outputFile) {
        return trap(() -> {
            if (!delete(inputFile, pageNumbers, outputFile)) {
                throw new RuntimeException("delete returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #splitFromFirst(String, int, String)}.
     *
     * @param inputFile  input file
     * @param pageNum    last page to keep
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean trySplitFromFirst(String inputFile, int pageNum, String outputFile) {
        return trap(() -> {
            if (!splitFromFirst(inputFile, pageNum, outputFile)) {
                throw new RuntimeException("splitFromFirst returned false");
            }
        });
    }

    /**
     * Try-variant of {@link #splitToEnd(String, int, String)}.
     *
     * @param inputFile  input file
     * @param pageNum    first page to keep
     * @param outputFile output file
     * @return {@code true} on success, {@code false} on exception
     */
    public boolean trySplitToEnd(String inputFile, int pageNum, String outputFile) {
        return trap(() -> {
            if (!splitToEnd(inputFile, pageNum, outputFile)) {
                throw new RuntimeException("splitToEnd returned false");
            }
        });
    }
}
