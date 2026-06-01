package org.aspose.pdf.facades;

import org.aspose.pdf.Document;

import java.io.InputStream;
import java.util.logging.Logger;

/**
 * Represents a stamp in the facades API that can be applied via {@link PdfFileStamp}.
 * <p>
 * A facade stamp wraps either a {@link FormattedText} (for text stamps) or an
 * image, along with positioning and rotation properties. Use {@link #bindLogo(FormattedText)}
 * to attach text content, and {@link #setOrigin(double, double)} to set the position.
 * </p>
 */
public class Stamp {

    private static final Logger LOG = Logger.getLogger(Stamp.class.getName());

    private FormattedText formattedText;
    private double originX;
    private double originY;
    private float rotation;
    private boolean isBackground;
    private int pageNumber;
    private int stampId;
    private String imageFile;
    private InputStream imageStream;
    private String pdfFile;
    private Document pdfDocument;
    private int pdfPageNumber = 1;

    /**
     * Creates a new empty {@code Stamp} instance.
     */
    public Stamp() {
    }

    /**
     * Binds a {@link FormattedText} as the text content (logo) of this stamp.
     *
     * @param formattedText the formatted text to use as the stamp content
     */
    public void bindLogo(FormattedText formattedText) {
        this.formattedText = formattedText;
        this.imageFile = null;
        this.imageStream = null;
        this.pdfFile = null;
        this.pdfDocument = null;
        LOG.fine(() -> "Bound formatted text logo: " + (formattedText != null ? formattedText.getText() : "null"));
    }

    /**
     * Convenience overload: binds an image file as the logo. Equivalent to
     * {@link #bindImage(String)} — mirrors the Aspose .NET overload that
     * accepts a file path under the same {@code BindLogo} name.
     *
     * @param logoFile the image file path
     */
    public void bindLogo(String logoFile) {
        bindImage(logoFile);
    }

    /**
     * Binds an image file as the stamp source.
     *
     * @param imageFile the image file path
     */
    public void bindImage(String imageFile) {
        this.formattedText = null;
        this.imageStream = null;
        this.pdfFile = null;
        this.pdfDocument = null;
        this.imageFile = imageFile;
        LOG.fine(() -> "Bound image stamp file: " + imageFile);
    }

    /**
     * Binds an image stream as the stamp source.
     *
     * @param imageStream the image data stream
     */
    public void bindImage(InputStream imageStream) {
        this.formattedText = null;
        this.imageFile = null;
        this.pdfFile = null;
        this.pdfDocument = null;
        this.imageStream = imageStream;
        LOG.fine("Bound image stamp stream");
    }

    /**
     * Convenience overload: binds the first page of {@code pdfFile} as the
     * stamp source.
     *
     * @param pdfFile the source PDF file path
     */
    public void bindPdf(String pdfFile) {
        bindPdf(pdfFile, 1);
    }

    /**
     * Binds the first page of a PDF stream as the stamp source. The stream is
     * eagerly buffered into a {@link Document} so the caller can close it
     * immediately after this call returns.
     *
     * @param pdfStream the source PDF input stream (must not be null)
     */
    public void bindPdf(InputStream pdfStream) {
        if (pdfStream == null) {
            throw new IllegalArgumentException("pdfStream must not be null");
        }
        try {
            Document doc = new Document(pdfStream);
            bindPdf(doc, 1);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to bind PDF stream: " + e.getMessage(), e);
        }
    }

    /**
     * Binds a PDF page as the stamp source.
     *
     * @param pdfFile the source PDF file path
     * @param pageNumber the 1-based source page number
     */
    public void bindPdf(String pdfFile, int pageNumber) {
        this.formattedText = null;
        this.imageFile = null;
        this.imageStream = null;
        this.pdfDocument = null;
        this.pdfFile = pdfFile;
        this.pdfPageNumber = Math.max(1, pageNumber);
        LOG.fine(() -> "Bound PDF stamp file: " + pdfFile + ", page " + this.pdfPageNumber);
    }

    /**
     * Binds a page from an existing document as the stamp source.
     *
     * @param document the source document
     * @param pageNumber the 1-based source page number
     */
    public void bindPdf(Document document, int pageNumber) {
        this.formattedText = null;
        this.imageFile = null;
        this.imageStream = null;
        this.pdfFile = null;
        this.pdfDocument = document;
        this.pdfPageNumber = Math.max(1, pageNumber);
        LOG.fine(() -> "Bound PDF stamp document, page " + this.pdfPageNumber);
    }

    /**
     * Sets the origin (position) of the stamp on the page.
     *
     * @param x the horizontal position in points from the left edge
     * @param y the vertical position in points from the bottom edge
     */
    public void setOrigin(double x, double y) {
        this.originX = x;
        this.originY = y;
    }

    /**
     * Returns the horizontal position of the stamp origin.
     *
     * @return the X coordinate in points
     */
    public double getOriginX() {
        return originX;
    }

    /**
     * Returns the vertical position of the stamp origin.
     *
     * @return the Y coordinate in points
     */
    public double getOriginY() {
        return originY;
    }

    /**
     * Returns the rotation angle in degrees.
     *
     * @return the rotation angle
     */
    public float getRotation() {
        return rotation;
    }

    /**
     * Sets the rotation angle in degrees.
     *
     * @param rotation the rotation angle
     */
    public void setRotation(float rotation) {
        this.rotation = rotation;
    }

    /**
     * Returns whether this stamp is rendered behind the page content.
     *
     * @return {@code true} if the stamp is a background element
     */
    public boolean isBackground() {
        return isBackground;
    }

    /**
     * Sets whether this stamp is rendered behind the page content.
     *
     * @param background {@code true} to render the stamp behind content
     */
    public void setBackground(boolean background) {
        this.isBackground = background;
    }

    /**
     * Returns the page number this stamp should be applied to.
     * A value of 0 means all pages.
     *
     * @return the page number
     */
    public int getPageNumber() {
        return pageNumber;
    }

    /**
     * Sets the page number this stamp should be applied to.
     * Use 0 for all pages.
     *
     * @param pageNumber the page number
     */
    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    /**
     * Returns the stamp identifier.
     *
     * @return the stamp ID
     */
    public int getStampId() {
        return stampId;
    }

    /**
     * Sets the stamp identifier.
     *
     * @param stampId the stamp ID
     */
    public void setStampId(int stampId) {
        this.stampId = stampId;
    }

    /**
     * Returns the bound {@link FormattedText}, or {@code null} if none has been set.
     *
     * @return the formatted text content
     */
    public FormattedText getFormattedText() {
        return formattedText;
    }

    /**
     * Returns the bound image file path, or {@code null}.
     *
     * @return the image file path
     */
    public String getImageFile() {
        return imageFile;
    }

    /**
     * Returns the bound image stream, or {@code null}.
     *
     * @return the image stream
     */
    public InputStream getImageStream() {
        return imageStream;
    }

    /**
     * Returns the bound PDF file path, or {@code null}.
     *
     * @return the PDF file path
     */
    public String getPdfFile() {
        return pdfFile;
    }

    /**
     * Returns the bound PDF document, or {@code null}.
     *
     * @return the source document
     */
    public Document getPdfDocument() {
        return pdfDocument;
    }

    /**
     * Returns the 1-based source PDF page number.
     *
     * @return the source page number
     */
    public int getPdfPageNumber() {
        return pdfPageNumber;
    }
}
