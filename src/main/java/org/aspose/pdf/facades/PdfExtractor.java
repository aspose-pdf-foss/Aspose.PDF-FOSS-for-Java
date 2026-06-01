package org.aspose.pdf.facades;

import org.aspose.pdf.*;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.FileAttachmentAnnotation;
import org.aspose.pdf.devices.Resolution;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.operators.Do;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextExtractionOptions;
import org.aspose.pdf.text.TextSearchOptions;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Facade for extracting text and images from PDF documents.
 * <p>
 * Usage pattern:
 * <pre>
 * PdfExtractor extractor = new PdfExtractor();
 * extractor.bindPdf("input.pdf");
 * extractor.extractText();
 * extractor.getText("output.txt");
 * </pre>
 * </p>
 */
public class PdfExtractor implements Closeable {

    static {
        // Bootstrap library logging (silent by default) for facade-only entry
        // paths that may not construct a Document first (Sprint 24 Part B).
        org.aspose.pdf.AsposePdfLogging.configureFromSystemProperty();
    }

    private static final Logger LOG = Logger.getLogger(PdfExtractor.class.getName());

    private Document document;
    private String extractedText;
    private final List<byte[]> extractedImages = new ArrayList<>();
    private final List<String> extractedPageTexts = new ArrayList<>();
    private final List<FileSpecification> extractedAttachments = new ArrayList<>();
    private int imageIndex = -1;
    private int pageTextIndex = -1;
    private int attachmentIndex = -1;
    private int startPage = 1;
    private int endPage = Integer.MAX_VALUE;
    private int extractTextMode = -1;
    // BUG-ENC-001 (Sprint 22): default to UTF-16LE without BOM, matching the
    // Aspose.PDF for .NET wire format that ported tests decode with. Callers can
    // override via setOutputEncoding(...) / extractText(Charset).
    private Charset outputEncoding = StandardCharsets.UTF_16LE;
    private boolean bidi;
    private TextSearchOptions textSearchOptions;
    private Resolution resolution = new Resolution(72);
    private ExtractImageMode extractImageMode = ExtractImageMode.ResourcesDefined;
    /** Password used to open encrypted PDFs in subsequent {@code bindPdf} calls. */
    private String password;

    /**
     * Creates a new PdfExtractor instance.
     */
    public PdfExtractor() {
    }

    /**
     * Creates a new PdfExtractor bound to an existing document.
     *
     * @param document the document to bind
     */
    public PdfExtractor(Document document) {
        this.document = document;
    }

    /**
     * Creates a new PdfExtractor bound to a PDF stream.
     *
     * @param stream the input stream containing PDF data
     * @throws IOException if the stream cannot be read
     */
    public PdfExtractor(InputStream stream) throws IOException {
        this.document = new Document(stream);
    }

    /**
     * Binds a PDF file to this extractor.
     *
     * @param inputFile path to the PDF file
     * @throws IOException if the file cannot be opened
     */
    public void bindPdf(String inputFile) throws IOException {
        this.document = password != null
                ? new Document(inputFile, password)
                : new Document(inputFile);
        resetDocumentBoundState();
    }

    /**
     * Binds a PDF from an input stream.
     *
     * @param stream the input stream containing PDF data
     * @throws IOException if the stream cannot be read
     */
    public void bindPdf(InputStream stream) throws IOException {
        this.document = password != null
                ? new Document(stream, password)
                : new Document(stream);
        resetDocumentBoundState();
    }

    /**
     * Returns the password applied when opening encrypted PDFs in subsequent
     * {@link #bindPdf(String)} or {@link #bindPdf(InputStream)} calls.
     *
     * @return the password, or {@code null} for none
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the password used by subsequent {@code bindPdf} calls to open
     * encrypted PDFs. Mirrors C# {@code PdfExtractor.Password}.
     *
     * @param password the open password, or {@code null} for none
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Binds an existing Document to this extractor.
     *
     * @param document the document
     */
    public void bindPdf(Document document) {
        this.document = document;
        resetDocumentBoundState();
    }

    /**
     * Sets the start page for extraction (1-based).
     *
     * @param page the start page number
     */
    public void setStartPage(int page) {
        this.startPage = page;
    }

    /**
     * Returns the start page.
     *
     * @return the start page number
     */
    public int getStartPage() {
        return startPage;
    }

    /**
     * Sets the end page for extraction (1-based).
     *
     * @param page the end page number
     */
    public void setEndPage(int page) {
        this.endPage = page;
    }

    /**
     * Returns the end page.
     *
     * @return the end page number
     */
    public int getEndPage() {
        return endPage;
    }

    /**
     * Sets the text extraction mode.
     * <p>
     * Mode {@code 0} follows the Aspose-compatible visual text path and uses
     * layout-oriented extraction. Other values currently fall back to the
     * default content-stream order extraction.
     * </p>
     *
     * @param mode extraction mode identifier
     */
    public void setExtractTextMode(int mode) {
        this.extractTextMode = mode;
    }

    /**
     * Returns the current text extraction mode.
     *
     * @return extraction mode identifier
     */
    public int getExtractTextMode() {
        return extractTextMode;
    }

    /**
     * Sets image extraction resolution for API parity.
     *
     * @param resolution extraction resolution
     */
    public void setResolution(Resolution resolution) {
        if (resolution != null) {
            this.resolution = resolution;
        }
    }

    /**
     * Returns the configured extraction resolution.
     *
     * @return extraction resolution
     */
    public Resolution getResolution() {
        return resolution;
    }

    /**
     * Sets the image extraction mode.
     *
     * @param extractImageMode extraction mode
     */
    public void setExtractImageMode(ExtractImageMode extractImageMode) {
        if (extractImageMode != null) {
            this.extractImageMode = extractImageMode;
        }
    }

    /**
     * Returns the image extraction mode.
     *
     * @return extraction mode
     */
    public ExtractImageMode getExtractImageMode() {
        return extractImageMode;
    }

    /**
     * Extracts text from the page range.
     *
     * @throws IOException if text extraction fails
     */
    public void extractText() throws IOException {
        extractText(outputEncoding);
    }

    /**
     * Extracts text from the page range using the requested output encoding.
     *
     * @param encoding the output encoding used by {@code getText(...)}
     * @throws IOException if text extraction fails
     */
    public void extractText(Charset encoding) throws IOException {
        if (document == null) throw new IllegalStateException("No document bound");
        this.outputEncoding = encoding != null ? encoding : StandardCharsets.UTF_16LE;
        extractedPageTexts.clear();
        pageTextIndex = -1;
        TextAbsorber documentAbsorber = new TextAbsorber(createTextExtractionOptions());
        documentAbsorber.setTextSearchOptions(getTextSearchOptions());
        PageCollection pages = document.getPages();
        int end = Math.min(endPage, pages.getCount());
        for (int i = startPage; i <= end; i++) {
            Page page = pages.get(i);
            page.accept(documentAbsorber);
            TextAbsorber pageAbsorber = new TextAbsorber(createTextExtractionOptions());
            pageAbsorber.setTextSearchOptions(getTextSearchOptions());
            page.accept(pageAbsorber);
            extractedPageTexts.add(normalizeExtractedText(pageAbsorber.getText()));
        }
        this.extractedText = normalizeExtractedText(documentAbsorber.getText());
        this.bidi = containsBidiText(this.extractedText);
    }

    private TextExtractionOptions createTextExtractionOptions() {
        if (extractTextMode == 1) {
            return new TextExtractionOptions(TextExtractionOptions.TextFormattingMode.Raw);
        }
        return new TextExtractionOptions(TextExtractionOptions.TextFormattingMode.Pure);
    }

    private String normalizeExtractedText(String text) {
        text = normalizeLegacyBulletPlaceholders(text);
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", System.lineSeparator());
    }

    private String normalizeLegacyBulletPlaceholders(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.replaceAll("(?m)^([ \\t]*)(?:[?\\uFFFD]|\\p{Co})\\s{2,}", "$1.  ");
    }

    /**
     * Writes extracted text to a file.
     *
     * @param outputPath the output file path
     * @throws IOException if writing fails
     */
    public void getText(String outputPath) throws IOException {
        Files.write(Paths.get(outputPath), getTextBytes(extractedText));
    }

    /**
     * Writes extracted text to an output stream.
     *
     * @param stream the output stream
     * @throws IOException if writing fails
     */
    public void getText(OutputStream stream) throws IOException {
        stream.write(getTextBytes(extractedText));
    }

    /**
     * Returns the extracted text as a string.
     *
     * @return the extracted text, or empty string if not yet extracted
     */
    public String getTextAsString() {
        return extractedText != null ? extractedText : "";
    }

    /**
     * Returns text search options associated with the extractor.
     *
     * @return mutable text search options
     */
    public TextSearchOptions getTextSearchOptions() {
        if (textSearchOptions == null) {
            textSearchOptions = new TextSearchOptions();
        }
        return textSearchOptions;
    }

    /**
     * Sets text search options used by subsequent extraction calls.
     *
     * @param textSearchOptions options to use, or {@code null}
     */
    public void setTextSearchOptions(TextSearchOptions textSearchOptions) {
        this.textSearchOptions = textSearchOptions;
    }

    /**
     * Returns whether page-by-page extracted text remains available.
     *
     * @return true if another page text chunk can be retrieved
     */
    public boolean hasNextPageText() {
        return pageTextIndex + 1 < extractedPageTexts.size();
    }

    /**
     * Writes the next page text to a file.
     *
     * @param outputPath target file path
     * @throws IOException if writing fails
     */
    public void getNextPageText(String outputPath) throws IOException {
        Files.write(Paths.get(outputPath), getNextPageTextBytes());
    }

    /**
     * Writes the next page text to a stream.
     *
     * @param stream target stream
     * @throws IOException if writing fails
     */
    public void getNextPageText(OutputStream stream) throws IOException {
        stream.write(getNextPageTextBytes());
    }

    /**
     * Returns whether the current extraction contains bidi text.
     *
     * @return true if extracted text contains RTL scripts
     */
    public boolean isBidi() {
        return bidi;
    }

    /**
     * Prepares attachment extraction for all embedded files.
     */
    public void extractAttachment() {
        ensureAttachmentsLoaded();
        attachmentIndex = -1;
    }

    /**
     * Prepares extraction for a specific attachment key or file name.
     *
     * @param name attachment key or file name
     */
    public void extractAttachment(String name) {
        ensureAttachmentsLoaded();
        attachmentIndex = -1;
        if (name == null || name.isEmpty()) {
            return;
        }
        String normalized = normalizeAttachmentSelector(name);
        extractedAttachments.removeIf(fs ->
                !name.equals(fs.getName())
                        && !normalized.equals(fs.getName())
                        && !name.equals(buildAttachmentKey(fs)));
    }

    /**
     * Returns the currently prepared attachment names.
     *
     * @return attachment names
     */
    public List<String> getAttachNames() {
        ensureAttachmentsLoaded();
        List<String> names = new ArrayList<>(extractedAttachments.size());
        for (FileSpecification spec : extractedAttachments) {
            names.add(spec.getName());
        }
        return Collections.unmodifiableList(names);
    }

    /**
     * Writes the prepared attachment(s) to the given file or directory.
     *
     * @param outputPath file path or directory path
     * @throws IOException if writing fails
     */
    public void getAttachment(String outputPath) throws IOException {
        ensureAttachmentsLoaded();
        if (extractedAttachments.isEmpty()) {
            return;
        }
        String safePath = outputPath != null ? outputPath : "";
        boolean asDirectory = safePath.isEmpty()
                || safePath.endsWith("/")
                || safePath.endsWith("\\")
                || Files.isDirectory(Paths.get(safePath));
        if (asDirectory) {
            java.nio.file.Path dir = safePath.isEmpty() ? Paths.get(".") : Paths.get(safePath);
            Files.createDirectories(dir);
            for (FileSpecification spec : extractedAttachments) {
                Files.write(dir.resolve(spec.getName()), safeAttachmentData(spec));
            }
            return;
        }
        FileSpecification selected = extractedAttachments.get(Math.min(Math.max(attachmentIndex, 0), extractedAttachments.size() - 1));
        Files.write(Paths.get(safePath), safeAttachmentData(selected));
    }

    /**
     * Extracts images from the page range.
     *
     * @throws IOException if image extraction fails
     */
    public void extractImage() throws IOException {
        if (document == null) throw new IllegalStateException("No document bound");
        extractedImages.clear();
        imageIndex = -1;
        PageCollection pages = document.getPages();
        int end = Math.min(endPage, pages.getCount());
        for (int i = startPage; i <= end; i++) {
            Page page = pages.get(i);
            Resources res = page.getResources();
            if (res == null) continue;
            XImageCollection images = res.getImages();
            if (images == null) continue;
            Set<String> actuallyUsed = extractActuallyUsedImageNames(page);
            for (int j = 1; j <= images.getCount(); j++) {
                try {
                    XImage img = images.get(j);
                    if (extractImageMode == ExtractImageMode.ActuallyUsed) {
                        String name = img.getName();
                        if (name == null || !actuallyUsed.contains(name)) {
                            continue;
                        }
                    }
                    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
                    img.save(imageBytes);
                    byte[] data = imageBytes.toByteArray();
                    if (data != null && data.length > 0) {
                        extractedImages.add(data);
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Failed to extract image " + j, e);
                }
            }
        }
    }

    /**
     * Returns whether there are more extracted images to retrieve.
     *
     * @return true if more images are available
     */
    public boolean hasNextImage() {
        return imageIndex + 1 < extractedImages.size();
    }

    /**
     * Saves the next extracted image to a file.
     *
     * @param outputPath the output file path
     * @throws IOException if writing fails
     */
    public void getNextImage(String outputPath) throws IOException {
        imageIndex++;
        if (imageIndex >= extractedImages.size()) {
            throw new IOException("No more images");
        }
        Files.write(Paths.get(outputPath), extractedImages.get(imageIndex));
    }

    /**
     * Saves the next extracted image to a file using the requested output format.
     * Current implementation preserves the extracted image payload and accepts the
     * format for source compatibility with Aspose facade tests.
     *
     * @param outputPath the output file path
     * @param format the requested image format
     * @throws IOException if writing fails
     */
    public void getNextImage(String outputPath, ImageFormat format) throws IOException {
        getNextImage(outputPath);
    }

    /**
     * Saves the next extracted image to an output stream.
     *
     * @param stream the output stream
     * @throws IOException if writing fails
     */
    public void getNextImage(OutputStream stream) throws IOException {
        imageIndex++;
        if (imageIndex >= extractedImages.size()) {
            throw new IOException("No more images");
        }
        stream.write(extractedImages.get(imageIndex));
    }

    /**
     * Saves the next extracted image to a stream using the requested output format.
     * Current implementation preserves the extracted image payload and accepts the
     * format for source compatibility with Aspose facade tests.
     *
     * @param stream the output stream
     * @param format the requested image format
     * @throws IOException if writing fails
     */
    public void getNextImage(OutputStream stream, ImageFormat format) throws IOException {
        getNextImage(stream);
    }

    /**
     * Returns the number of extracted images.
     *
     * @return the image count
     */
    public int getImageCount() {
        return extractedImages.size();
    }

    /**
     * Closes this extractor and releases the bound document.
     *
     * @throws IOException if closing fails
     */
    public void close() throws IOException {
        if (document != null) {
            document.close();
            document = null;
        }
    }

    private void resetDocumentBoundState() {
        extractedText = null;
        extractedImages.clear();
        extractedPageTexts.clear();
        extractedAttachments.clear();
        imageIndex = -1;
        pageTextIndex = -1;
        attachmentIndex = -1;
        bidi = false;
        startPage = 1;
        endPage = Integer.MAX_VALUE;
        if (document != null) {
            try {
                PageCollection pages = document.getPages();
                if (pages != null) {
                    endPage = pages.getCount();
                }
            } catch (IOException e) {
                LOG.log(Level.FINE, "Failed to initialize page range from bound document", e);
            }
        }
    }

    private byte[] getTextBytes(String text) {
        return (text != null ? text : "").getBytes(outputEncoding != null ? outputEncoding : StandardCharsets.UTF_16LE);
    }

    private byte[] getNextPageTextBytes() {
        pageTextIndex++;
        if (pageTextIndex < 0 || pageTextIndex >= extractedPageTexts.size()) {
            return new byte[0];
        }
        return getTextBytes(extractedPageTexts.get(pageTextIndex));
    }

    private boolean containsBidiText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(text.charAt(i));
            if (block == Character.UnicodeBlock.HEBREW
                    || block == Character.UnicodeBlock.ARABIC
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
                    || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B) {
                return true;
            }
        }
        return false;
    }

    private void ensureAttachmentsLoaded() {
        extractedAttachments.clear();
        if (document == null) {
            return;
        }
        Map<String, FileSpecification> attachments = new LinkedHashMap<>();
        EmbeddedFileCollection files = document.getEmbeddedFiles();
        for (int i = 1; i <= files.getCount(); i++) {
            FileSpecification file = files.get(i);
            attachments.putIfAbsent(file.getName(), file);
        }
        try {
            PageCollection pages = document.getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                for (Annotation annotation : pages.get(i).getAnnotations()) {
                    if (annotation instanceof FileAttachmentAnnotation) {
                        COSBase fs = resolveRef(annotation.getCOSDictionary().get("FS"));
                        if (fs instanceof COSDictionary) {
                            FileSpecification spec = new FileSpecification((COSDictionary) fs);
                            attachments.putIfAbsent(spec.getName(), spec);
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to scan file attachment annotations", e);
        }
        extractedAttachments.addAll(attachments.values());
    }

    private COSBase resolveRef(COSBase value) {
        if (value instanceof COSObjectReference) {
            try {
                return ((COSObjectReference) value).dereference();
            } catch (Exception e) {
                return null;
            }
        }
        return value;
    }

    private String normalizeAttachmentSelector(String name) {
        return name.replaceFirst("^<\\d+>", "");
    }

    private String buildAttachmentKey(FileSpecification spec) {
        return "<4>" + spec.getName();
    }

    private byte[] safeAttachmentData(FileSpecification spec) throws IOException {
        byte[] data = spec.getData();
        return data != null ? data : new byte[0];
    }

    private Set<String> extractActuallyUsedImageNames(Page page) {
        Set<String> names = new LinkedHashSet<>();
        try {
            OperatorCollection contents = page.getContents();
            Resources resources = page.getResources();
            COSDictionary xObjects = resources != null ? resources.getXObjects() : null;
            for (Operator operator : contents) {
                if (operator instanceof Do) {
                    String name = ((Do) operator).getXObjectName();
                    if (name != null && !name.isEmpty() && isImageXObject(xObjects, name)) {
                        names.add(name);
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to inspect page content for ActuallyUsed images", e);
        }
        return names;
    }

    private boolean isImageXObject(COSDictionary xObjects, String name) {
        if (xObjects == null) {
            return false;
        }
        COSBase candidate = xObjects.get(name);
        candidate = resolveRef(candidate);
        if (!(candidate instanceof COSDictionary)) {
            return false;
        }
        COSDictionary dict = (COSDictionary) candidate;
        return "XObject".equals(dict.getNameAsString("Type"))
                && "Image".equals(dict.getNameAsString("Subtype"));
    }
}
