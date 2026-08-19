package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageNumberStamp;
import org.aspose.pdf.PdfPageStamp;
import org.aspose.pdf.TextStamp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides methods for adding stamps (text or image) to PDF pages.
 * <p>
 * The current implementation stores stamps but does not render them into the
 * content stream, as that requires the layout engine. Stamps are logged for
 * diagnostic purposes.
 */
public class PdfFileStamp implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(PdfFileStamp.class.getName());

    private static final class PendingStamp {
        private final org.aspose.pdf.Stamp stamp;
        private final int pageNumber;

        private PendingStamp(org.aspose.pdf.Stamp stamp, int pageNumber) {
            this.stamp = stamp;
            this.pageNumber = pageNumber;
        }
    }

    private Document document;
    private final List<PendingStamp> stamps = new ArrayList<>();
    private final List<Document> auxiliaryDocuments = new ArrayList<>();
    private int stampId;
    /** Output stream supplied via {@link #PdfFileStamp(InputStream, OutputStream)};
     *  {@link #close()} writes the finished PDF here when set. */
    private OutputStream pendingOutputStream;

    /** Output file supplied via {@link #PdfFileStamp(String, String)};
     *  {@link #close()} writes the finished PDF here when set. */
    private String pendingOutputFile;

    /**
     * Creates a new {@code PdfFileStamp} instance.
     */
    public PdfFileStamp() {
    }

    /**
     * Creates a {@code PdfFileStamp} bound to the input stream and configured to
     * write the stamped result to the supplied output stream when
     * {@link #close()} is called. Mirrors the C# {@code PdfFileStamp(Stream, Stream)}
     * constructor used by the legacy facade pattern
     * ({@code new PdfFileStamp(in, out); add…; close();}).
     *
     * @param inputStream  the source PDF stream
     * @param outputStream the destination stream (the stamped PDF is written here on {@link #close()})
     */
    public PdfFileStamp(InputStream inputStream, OutputStream outputStream) {
        bindPdf(inputStream);
        this.pendingOutputStream = outputStream;
    }

    /**
     * Creates a {@code PdfFileStamp} bound to {@code inputFile} and configured
     * to write the stamped result to {@code outputFile} when {@link #close()}
     * is called. Mirrors the C# {@code PdfFileStamp(string, string)}
     * constructor used by the legacy facade pattern
     * ({@code new PdfFileStamp(inFile, outFile); addStamp(...); close();}).
     */
    public PdfFileStamp(String inputFile, String outputFile) {
        bindPdf(inputFile);
        this.pendingOutputFile = outputFile;
    }

    /** Creates a {@code PdfFileStamp} bound to {@code document}. */
    public PdfFileStamp(Document document) {
        bindPdf(document);
    }

    /** Returns the bound document, or {@code null}. Mirrors C# {@code PdfFileStamp.Document}. */
    public Document getDocument() {
        return document;
    }

    /**
     * Sets the input file path. The file is loaded into the bound {@link Document}.
     * Mirrors C# {@code PdfFileStamp.InputFile} setter.
     */
    public void setInputFile(String inputFile) {
        bindPdf(inputFile);
    }

    /**
     * Binds a PDF file to this stamp editor.
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
     * Binds an existing {@link Document} to this stamp editor.
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
     * Adds a text stamp to the list of stamps to apply.
     * <p>
     * <strong>Note:</strong> Stamp rendering into the content stream requires
     * layout engine support and is not yet fully implemented. The stamp is stored
     * for future application.
     *
     * @param stamp the text stamp to add
     */
    public void addStamp(TextStamp stamp) {
        if (stamp == null) {
            LOG.warning("Cannot add null stamp");
            return;
        }
        stamps.add(new PendingStamp(stamp, 0));
        totalStampCount++;
        LOG.fine("Added text stamp: '" + stamp.getValue() + "'. "
                + "Stamp application requires layout engine support.");
    }

    /**
     * Adds a facades {@link Stamp} to the list of stamps to apply.
     * <p>
     * If the stamp has a bound {@link FormattedText} (via {@link Stamp#bindLogo(FormattedText)}),
     * it is converted to a {@link TextStamp} with the formatted text properties applied.
     * </p>
     *
     * @param stamp the facades stamp to add
     */
    public void addStamp(Stamp stamp) {
        if (stamp == null) {
            LOG.warning("Cannot add null stamp");
            return;
        }
        org.aspose.pdf.Stamp coreStamp = toCoreStamp(stamp);
        if (coreStamp == null) {
            LOG.fine("Facades stamp has no bound content; skipping.");
            return;
        }
        totalStampCount++;
        // Apply eagerly when a document is bound so consumers that read
        // back via getDocument() (e.g. PdfExtractor) see the result without
        // waiting for save(). Falls back to the pending queue for the rare
        // case where stamps are added before any document is bound.
        if (document != null) {
            int pageNumber = stamp.getPageNumber() > 0 ? stamp.getPageNumber() : 0;
            int[] pages = stamp.getPages();
            try {
                if (pages != null && pages.length > 0) {
                    // Explicit Stamp.Pages set: apply only to those 1-based pages.
                    for (int p : pages) {
                        if (p >= 1 && p <= document.getPages().getCount()) {
                            applyOneStamp(p, coreStamp);
                        }
                    }
                } else if (pageNumber > 0 && pageNumber <= document.getPages().getCount()) {
                    applyOneStamp(pageNumber, coreStamp);
                } else {
                    for (int i = 1; i <= document.getPages().getCount(); i++) {
                        applyOneStamp(i, coreStamp);
                    }
                }
                LOG.fine("Applied facades stamp eagerly, stampId=" + stamp.getStampId());
                return;
            } catch (java.io.IOException e) {
                LOG.log(Level.WARNING, "Eager stamp application failed; deferring", e);
            }
        }
        stamps.add(new PendingStamp(coreStamp, stamp.getPageNumber()));
        LOG.fine("Queued facades stamp (no doc bound), stampId=" + stamp.getStampId());
    }

    /**
     * Adds a text footer to all pages.
     *
     * @param footerText the footer content
     * @param bottomMargin the bottom margin in points
     */
    public void addFooter(FormattedText footerText, float bottomMargin) {
        addFooter(footerText, bottomMargin, 0f, 0f);
    }

    /**
     * Adds a text footer to all pages with explicit left/right indents
     * (mirrors the C# {@code AddFooter(FormattedText, float, float, float)}
     * 4-arg overload used by PDFNEWNET-28949 and similar tests).
     *
     * @param footerText  the footer content
     * @param bottomMargin the bottom margin in points
     * @param leftIndent  left edge offset in points
     * @param rightIndent right edge offset in points (currently used to
     *                    width-clip the stamp; takes effect once the layout
     *                    engine applies the right indent)
     */
    public void addFooter(FormattedText footerText, float bottomMargin,
                           float leftIndent, float rightIndent) {
        Stamp stamp = new Stamp();
        stamp.bindLogo(footerText);
        stamp.setOrigin(leftIndent, bottomMargin);
        stamp.setHorizontalAlignment(org.aspose.pdf.HorizontalAlignment.Center);
        stamp.setPageNumber(0);
        stamp.setStampId(stampId);
        addStamp(stamp);
    }

    /**
     * Adds an image footer to all pages.
     *
     * @param imageFile the image path
     * @param bottomMargin the bottom margin in points
     */
    public void addFooter(String imageFile, float bottomMargin) {
        Stamp stamp = new Stamp();
        stamp.bindImage(imageFile);
        stamp.setOrigin(0, bottomMargin);
        stamp.setPageNumber(0);
        stamp.setStampId(stampId);
        addStamp(stamp);
    }

    /**
     * Adds a text header to all pages.
     *
     * @param headerText the header content
     * @param topMargin the top margin in points
     */
    public void addHeader(FormattedText headerText, float topMargin) {
        ensureDocumentBound();
        if (document == null) {
            return;
        }
        Stamp stamp = new Stamp();
        stamp.bindLogo(headerText);
        stamp.setOrigin(0, resolveHeaderY(topMargin));
        stamp.setHorizontalAlignment(org.aspose.pdf.HorizontalAlignment.Center);
        stamp.setPageNumber(0);
        stamp.setStampId(stampId);
        addStamp(stamp);
    }

    /**
     * Adds an image header to all pages.
     *
     * @param imageFile the image path
     * @param topMargin the top margin in points
     */
    public void addHeader(String imageFile, float topMargin) {
        ensureDocumentBound();
        if (document == null) {
            return;
        }
        Stamp stamp = new Stamp();
        stamp.bindImage(imageFile);
        stamp.setOrigin(0, resolveHeaderY(topMargin));
        stamp.setHorizontalAlignment(org.aspose.pdf.HorizontalAlignment.Center);
        stamp.setPageNumber(0);
        stamp.setStampId(stampId);
        addStamp(stamp);
    }

    /**
     * Adds a page number stamp to all pages using the supplied formatted text
     * as the source for text value and text state.
     *
     * @param formattedText the formatted page-number text
     */
    public void addPageNumber(FormattedText formattedText) {
        if (formattedText == null) {
            LOG.warning("Cannot add null page number text");
            return;
        }
        PageNumberStamp stamp = new PageNumberStamp(formattedText.getText());
        TextStamp template = new TextStamp(formattedText);
        stamp.setTextState(template.getTextState());
        stamp.setStampId(stampId);
        // Page numbers are centered along the bottom of the page by default.
        stamp.setHorizontalAlignment(org.aspose.pdf.HorizontalAlignment.Center);
        stamp.setVerticalAlignment(org.aspose.pdf.VerticalAlignment.Bottom);
        stamps.add(new PendingStamp(stamp, 0));
    }

    /**
     * Returns the stamp id used by convenience stamp helpers.
     *
     * @return stamp identifier
     */
    public int getStampId() {
        return stampId;
    }

    /**
     * Sets the stamp id used by subsequent convenience stamp helpers.
     *
     * @param stampId stamp identifier
     */
    public void setStampId(int stampId) {
        this.stampId = stampId;
    }

    /**
     * Returns the number of stamps that have been added.
     *
     * @return the stamp count
     */
    public int getStampCount() {
        // Total number of addStamp / addHeader / addFooter / addPageNumber calls
        // since this PdfFileStamp was created. Independent of the pending-queue
        // size — eager application drains the queue without lowering this.
        return totalStampCount;
    }

    private int totalStampCount;

    /**
     * Saves the bound document to a file.
     *
     * @param outputFile path to the output file
     * @return {@code true} on success
     */
    public boolean save(String outputFile) {
        try {
            applyPendingStamps();
            document.requestFullRewrite();
            document.save(outputFile);
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
            applyPendingStamps();
            document.requestFullRewrite();
            document.save(outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PDF to stream", e);
            return false;
        }
    }

    /**
     * Closes the stamp editor and releases the bound document. If the editor
     * was created via {@link #PdfFileStamp(InputStream, OutputStream)}, the
     * stamped document is saved to the bound output stream first
     * (matching the C# {@code PdfFileStamp.Close()} contract).
     */
    public void close() {
        if (pendingOutputStream != null && document != null) {
            try {
                save(pendingOutputStream);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Auto-save on close() failed", e);
            }
            pendingOutputStream = null;
        }
        if (pendingOutputFile != null && document != null) {
            try {
                save(pendingOutputFile);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Auto-save on close() to file failed", e);
            }
            pendingOutputFile = null;
        }
        stamps.clear();
        for (Document auxiliary : auxiliaryDocuments) {
            try {
                auxiliary.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing auxiliary stamp document", e);
            }
        }
        auxiliaryDocuments.clear();
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing document", e);
            }
            document = null;
        }
    }

    private void applyPendingStamps() throws IOException {
        if (document == null || stamps.isEmpty()) {
            return;
        }
        for (PendingStamp pending : stamps) {
            if (pending.pageNumber > 0) {
                if (pending.pageNumber <= document.getPages().getCount()) {
                    applyOneStamp(pending.pageNumber, pending.stamp);
                }
                continue;
            }
            for (int i = 1; i <= document.getPages().getCount(); i++) {
                applyOneStamp(i, pending.stamp);
            }
        }
        stamps.clear();
        for (Document auxiliary : auxiliaryDocuments) {
            try {
                auxiliary.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing auxiliary stamp document", e);
            }
        }
        auxiliaryDocuments.clear();
    }

    /**
     * Routes a single pending stamp onto {@code page}. ImageStamp goes
     * through {@link PdfFileMend#addImage(String, int, double, double, double, double)}
     * which actually registers a new {@code /XObject} in page resources —
     * unlike {@code Page.addStamp(ImageStamp)} which only emits the
     * {@code Do} operator without the underlying XObject. See PDFNEWNET-31502.
     */
    private void applyOneStamp(int pageNumber, org.aspose.pdf.Stamp stamp) throws IOException {
        Page page = document.getPages().get(pageNumber);
        if (stamp instanceof org.aspose.pdf.ImageStamp) {
            org.aspose.pdf.ImageStamp imgStamp = (org.aspose.pdf.ImageStamp) stamp;
            String file = imgStamp.getFile();
            byte[] bytes = null;
            if (file != null) {
                try {
                    bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file));
                } catch (java.io.IOException ignored) {}
            }
            if (bytes == null && imgStamp.getImageStream() != null) {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = imgStamp.getImageStream().read(buf)) >= 0) baos.write(buf, 0, n);
                bytes = baos.toByteArray();
            }
            if (bytes != null && bytes.length > 0) {
                double w = imgStamp.getWidth() > 0 ? imgStamp.getWidth() : 100;
                double h = imgStamp.getHeight() > 0 ? imgStamp.getHeight() : 100;
                double x = imgStamp.getXIndent();
                double y = imgStamp.getYIndent();
                PdfFileMend tmp = new PdfFileMend(document);
                tmp.addImage(new java.io.ByteArrayInputStream(bytes),
                        pageNumber, x, y, x + w, y + h);
                return;
            }
        }
        // Page-number stamps: substitute '#'/'$P' with this page's number and the
        // total, per page. The form XObject is cached per stamp instance, so a
        // fresh TextStamp is rendered for each page (mutating the shared stamp's
        // value would reuse the first page's cached form).
        if (stamp instanceof PageNumberStamp) {
            PageNumberStamp pns = (PageNumberStamp) stamp;
            int total = document.getPages().getCount();
            org.aspose.pdf.TextStamp perPage =
                    new org.aspose.pdf.TextStamp(pns.formatPageNumber(pageNumber - 1, total));
            perPage.setTextState(pns.getTextState());
            perPage.setStampId(pns.getStampId());
            perPage.setOpacity(pns.getOpacity());
            perPage.setZoom(pns.getZoom());
            perPage.setRotate(pns.getRotate());
            perPage.setHorizontalAlignment(pns.getHorizontalAlignment());
            perPage.setVerticalAlignment(pns.getVerticalAlignment());
            perPage.setLeftMargin(pns.getLeftMargin());
            perPage.setTopMargin(pns.getTopMargin());
            perPage.setBottomMargin(pns.getBottomMargin());
            page.addStamp(perPage);
            return;
        }
        // Fallback: text/page stamps go through the existing pathway.
        page.addStamp(stamp);
    }

    private void ensureDocumentBound() {
        if (document == null) {
            LOG.warning("No document bound");
        }
    }

    private double resolveHeaderY(float topMargin) {
        if (document == null) {
            return Math.max(0, topMargin);
        }
        try {
            if (document.getPages().size() == 0) {
                return Math.max(0, topMargin);
            }
            Page firstPage = document.getPages().get(1);
            return Math.max(0, firstPage.getRect().getURY() - topMargin - 20.0);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Falling back to simple header position", e);
            return Math.max(0, topMargin);
        }
    }

    private org.aspose.pdf.Stamp toCoreStamp(Stamp stamp) {
        FormattedText ft = stamp.getFormattedText();
        if (ft != null) {
            TextStamp textStamp = new TextStamp(ft);
            copyCommonProperties(stamp, textStamp);
            return textStamp;
        }
        if (stamp.getImageFile() != null || stamp.getImageStream() != null) {
            ImageStamp imageStamp = stamp.getImageFile() != null
                    ? new ImageStamp(stamp.getImageFile())
                    : new ImageStamp("stream-image");
            if (stamp.getImageStream() != null) {
                imageStamp.setImageStream(stamp.getImageStream());
            }
            copyCommonProperties(stamp, imageStamp);
            return imageStamp;
        }
        if (stamp.getPdfFile() != null || stamp.getPdfDocument() != null) {
            Document sourceDocument = stamp.getPdfDocument();
            try {
                if (sourceDocument == null && stamp.getPdfFile() != null) {
                    sourceDocument = new Document(stamp.getPdfFile());
                    auxiliaryDocuments.add(sourceDocument);
                }
                if (sourceDocument == null || sourceDocument.getPages().size() == 0) {
                    return null;
                }
                int pageNumber = Math.min(Math.max(1, stamp.getPdfPageNumber()), sourceDocument.getPages().getCount());
                PdfPageStamp pageStamp = new PdfPageStamp(sourceDocument.getPages().get(pageNumber));
                copyCommonProperties(stamp, pageStamp);
                return pageStamp;
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to bind PDF page stamp", e);
                return null;
            }
        }
        return null;
    }

    private void copyCommonProperties(Stamp facadeStamp, org.aspose.pdf.Stamp coreStamp) {
        coreStamp.setBackground(facadeStamp.isBackground());
        coreStamp.setRotateAngle(facadeStamp.getRotation());
        coreStamp.setXIndent(facadeStamp.getOriginX());
        coreStamp.setYIndent(facadeStamp.getOriginY());
        coreStamp.setStampId(facadeStamp.getStampId());
        coreStamp.setHorizontalAlignment(facadeStamp.getHorizontalAlignment());
        coreStamp.setOpacity(facadeStamp.getOpacity());
    }
}
