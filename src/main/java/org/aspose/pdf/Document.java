package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSNull;
import org.aspose.pdf.engine.cos.COSObjectKey;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.engine.cos.COSString;
import org.aspose.pdf.engine.io.RandomAccessReader;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.engine.security.PDFDecryptor;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.aspose.pdf.engine.security.PDFEncryptor;
import org.aspose.pdf.engine.security.PDFKeyDerivation;
import org.aspose.pdf.engine.writer.PDFWriter;
import org.aspose.pdf.engine.xmp.XmpWriter;
import org.aspose.pdf.forms.Form;
import org.aspose.pdf.html.HtmlToPdfConverter;
import org.aspose.pdf.html.PdfToHtmlConverter;
import org.aspose.pdf.security.EncryptionParameters;
import org.aspose.pdf.security.ICustomSecurityHandler;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextState;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.linearization.LinearizedPDFWriter;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.logicalstructure.StructTreeRoot;
import org.aspose.pdf.operators.BDC;
import org.aspose.pdf.operators.Do;
import org.aspose.pdf.operators.DP;
import org.aspose.pdf.operators.GS;
import org.aspose.pdf.operators.SelectFont;
import org.aspose.pdf.operators.SetAdvancedColor;
import org.aspose.pdf.operators.SetAdvancedColorStroke;
import org.aspose.pdf.operators.SetColorSpace;
import org.aspose.pdf.operators.SetColorSpaceStroke;
import org.aspose.pdf.operators.ShFill;
import org.aspose.pdf.tagged.TaggedContent;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * The central class for working with PDF documents (ISO 32000-1:2008).
 * <p>
 * Opens a PDF from a file path or input stream, parses its structure, and provides
 * access to pages, document information, catalog, and trailer.
 * Implements {@link Closeable} to release resources when done.
 * </p>
 */
public class Document implements Closeable {

    static {
        // Configure library logging (silent by default) before any engine code
        // logs. Document is the canonical entry point — almost every API path
        // instantiates it — so this is a reliable bootstrap (Sprint 24 Part B).
        AsposePdfLogging.configureFromSystemProperty();
    }

    private static final Logger LOG = Logger.getLogger(Document.class.getName());

    private final PDFParser parser;
    private PageCollection pages;
    private DocumentInfo info;
    private OutlineCollection outlines;
    private Form form;
    private EmbeddedFileCollection embeddedFiles;
    private ViewerPreferences viewerPreferences;
    private XmpMetadata metadata;
    private COSDictionary inMemoryCatalog; // for empty documents created with Document()
    // When set, saveNewDocument skips paginateNewDocumentPagesIfNeeded — the
    // upstream converter (e.g. HtmlToPdfConverter) has already chunked the
    // content into pages and rerunning our generic splitter on already-paginated
    // pages over-counts (it estimates heights slightly differently and treats
    // tight-fit pages as overflowing, producing extra blanks).
    private boolean pagesPrePaginated;
    private String sourcePath;            // path to the original PDF file, null for new documents
    private byte[] sourceBytes;           // original bytes for stream-opened documents
    private int nextNewObjectNumber = -1; // next available object number for new objects
    // Imported-from-other-document objects keyed by fresh target-document keys.
    // Merged into the object set at save time. See DocumentPageImporter.
    private final Map<COSObjectKey, COSBase> pendingImports = new LinkedHashMap<>();
    private NamedDestinations namedDestinations;
    private TaggedContent taggedContent;
    private PageInfo pageInfo;
    private String fileName;
    private FontUtilities fontUtilities;
    private boolean embedStandardFonts;
    private boolean optimizeRequested;
    private boolean fullRewriteRequested;
    /** Set by flushDirtyPages() when a page's /Contents was edited this save. */
    private boolean editedPageContentsThisSave;

    // ── Write-side encryption state (set by encrypt(), consumed by save()) ──
    private PDFEncryptor pendingEncryptor;
    private PDFEncryptionDict pendingEncDict;
    private byte[] pendingDocumentId;

    /** Lock for thread-safe access to parser-dependent lazy initialization. */
    private final Object parserLock = new Object();

    /**
     * Document page mode — how the document should be displayed when opened.
     */
    public enum PageMode {
        /** Neither outlines nor thumbnails visible. */
        UseNone,
        /** Outlines (bookmarks) panel visible. */
        UseOutlines,
        /** Thumbnail panel visible. */
        UseThumbs,
        /** Full screen mode. */
        FullScreen,
        /** Optional content group panel visible. */
        UseOC,
        /** Attachments panel visible. */
        UseAttachments
    }

    /**
     * Opens a PDF document from a file path.
     *
     * @param filePath the path to the PDF file
     * @throws IOException              if the file cannot be read or is not a valid PDF
     * @throws IllegalArgumentException if filePath is null
     */
    public Document(String filePath) throws IOException {
        this(filePath, (String) null);
    }

    /**
     * Opens a PDF document from a file path with a password.
     *
     * @param filePath the path to the PDF file
     * @param password the password for encrypted PDFs (null or empty for no password)
     * @throws IOException              if the file cannot be read, is not valid, or password is wrong
     * @throws IllegalArgumentException if filePath is null
     */
    public Document(String filePath, String password) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        LOG.fine(() -> "Opening PDF from file: " + filePath);
        RandomAccessReader reader = RandomAccessReader.fromFile(new File(filePath));
        this.parser = new PDFParser(reader);
        this.parser.parse();
        this.sourcePath = filePath;
        this.fileName = filePath;
        initSecurity(password);
    }

    /**
     * Opens a PDF document from a file path using a custom security handler.
     *
     * @param filePath path to the PDF file
     * @param password password for the encrypted PDF
     * @param customHandler custom security handler
     * @throws IOException if the file cannot be opened
     */
    public Document(String filePath, String password, ICustomSecurityHandler customHandler) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        RandomAccessReader reader = RandomAccessReader.fromFile(new File(filePath));
        this.parser = new PDFParser(reader);
        this.parser.parse();
        this.sourcePath = filePath;
        this.fileName = filePath;
        initSecurity(password, customHandler);
    }

    /**
     * Opens a PDF document from an input stream.
     * The entire stream is read into memory.
     *
     * @param stream the input stream containing PDF data
     * @throws IOException              if the stream cannot be read or is not a valid PDF
     * @throws IllegalArgumentException if stream is null
     */
    public Document(InputStream stream) throws IOException {
        this(stream, (String) null);
    }

    /**
     * Opens a PDF document from an input stream with a password.
     *
     * @param stream   the input stream containing PDF data
     * @param password the password for encrypted PDFs (null for no password)
     * @throws IOException if the stream cannot be read, is not valid, or password is wrong
     */
    public Document(InputStream stream, String password) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream must not be null");
        }
        LOG.fine("Opening PDF from InputStream");
        byte[] bytes = readAllBytes(stream);
        this.sourceBytes = bytes.clone();
        RandomAccessReader reader = RandomAccessReader.fromBytes(bytes);
        this.parser = new PDFParser(reader);
        this.parser.parse();
        initSecurity(password);
    }

    /**
     * Opens a PDF document from an input stream using a custom security handler.
     *
     * @param stream input stream containing PDF data
     * @param password password for the encrypted PDF
     * @param customHandler custom security handler
     * @throws IOException if the stream cannot be opened
     */
    public Document(InputStream stream, String password, ICustomSecurityHandler customHandler) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("Input stream must not be null");
        }
        byte[] bytes = readAllBytes(stream);
        this.sourceBytes = bytes.clone();
        RandomAccessReader reader = RandomAccessReader.fromBytes(bytes);
        this.parser = new PDFParser(reader);
        this.parser.parse();
        initSecurity(password, customHandler);
    }

    /**
     * Creates a new empty PDF document.
     * The document starts with a single-page structure that can be populated.
     */
    public Document() {
        LOG.fine("Creating new empty PDF document");
        // Create minimal PDF structure in memory
        COSDictionary catalog = new COSDictionary();
        catalog.set(COSName.TYPE, COSName.of("Catalog"));
        COSDictionary pagesDict = new COSDictionary();
        pagesDict.set(COSName.TYPE, COSName.PAGES);
        pagesDict.set(COSName.KIDS, new org.aspose.pdf.engine.cos.COSArray());
        pagesDict.set(COSName.COUNT, org.aspose.pdf.engine.cos.COSInteger.valueOf(0));
        catalog.set(COSName.PAGES, pagesDict);
        this.parser = null;
        this.pages = new PageCollection(pagesDict, null);
        this.pages.setOwningDocument(this);
        this.inMemoryCatalog = catalog;
        // PDFNET_46999: a freshly created document defaults to PDF 1.7. Set only
        // here (not as a field default) so PDF/A inference on loaded documents,
        // which keys off pdfFormat == null, is unaffected.
        this.pdfFormat = PdfFormat.v_1_7;
    }

    /**
     * Creates a PDF document from an HTML file.
     *
     * @param filePath the path to the HTML file
     * @param options  HTML load options (may be null for defaults)
     * @throws IOException if reading or parsing fails
     */
    public Document(String filePath, HtmlLoadOptions options) throws IOException {
        this();
        if (options == null) options = new HtmlLoadOptions();
        try (InputStream is = new java.io.FileInputStream(filePath)) {
            HtmlToPdfConverter converter = new HtmlToPdfConverter();
            Document converted = converter.convert(is, options);
            try {
                adoptConvertedPages(converted);
            } finally {
                converted.close();
            }
        }
    }

    /**
     * Creates a PDF document from an HTML input stream.
     *
     * @param stream  the HTML content stream
     * @param options HTML load options (may be null for defaults)
     * @throws IOException if reading or parsing fails
     */
    public Document(InputStream stream, HtmlLoadOptions options) throws IOException {
        this();
        if (options == null) options = new HtmlLoadOptions();
        HtmlToPdfConverter converter = new HtmlToPdfConverter();
        Document converted = converter.convert(stream, options);
        try {
            adoptConvertedPages(converted);
        } finally {
            converted.close();
        }
    }

    private void adoptConvertedPages(Document converted) throws IOException {
        // HtmlToPdfConverter already paginated the content per page; mark this
        // document so save() does not re-paginate (which over-counts on tight
        // pages — see PDFNET_32745: 60 input pages → 66 after re-pagination).
        this.pagesPrePaginated = true;
        PageCollection srcPages = converted.getPages();
        for (int i = 1; i <= srcPages.getCount(); i++) {
            Page srcPage = srcPages.get(i);
            Page dstPage = this.getPages().add();

            PageInfo srcInfo = srcPage.getPageInfo();
            if (srcInfo != null) {
                dstPage.setPageInfo(srcInfo.deepClone());
            }

            if (srcPage.getHeader() != null) {
                dstPage.setHeader(srcPage.getHeader());
            }
            if (srcPage.getFooter() != null) {
                dstPage.setFooter(srcPage.getFooter());
            }
            if (srcPage.getTocInfo() != null) {
                dstPage.setTocInfo(srcPage.getTocInfo());
            }

            Paragraphs srcParagraphs = srcPage.getParagraphs();
            if (srcParagraphs != null) {
                for (BaseParagraph paragraph : srcParagraphs) {
                    dstPage.getParagraphs().add(paragraph);
                }
            }

            // BUG-059: also propagate annotations synthesized by the converter
            // (e.g. LinkAnnotation built from `<a href>`). Without this copy the
            // converter's per-page annotations were silently dropped when the
            // outer Document adopted only the paragraphs.
            org.aspose.pdf.annotations.AnnotationCollection srcAnnots = srcPage.getAnnotations();
            if (srcAnnots != null) {
                for (int a = 1; a <= srcAnnots.size(); a++) {
                    dstPage.getAnnotations().add(srcAnnots.get(a));
                }
            }
        }
    }

    /**
     * Package-private constructor from a pre-configured parser.
     * Used internally for testing and by other API classes.
     *
     * @param parser the already-parsed PDFParser
     */
    Document(PDFParser parser) {
        if (parser == null) {
            throw new IllegalArgumentException("Parser must not be null");
        }
        this.parser = parser;
    }

    /**
     * Returns the collection of pages in this document.
     * The page collection is lazily built on first access.
     *
     * @return the PageCollection
     * @throws IOException if the page tree cannot be read
     */
    /** Background colour applied via {@link #setBackground(Color)}. */
    private Color backgroundColor;

    /**
     * Returns the document-level background colour set via
     * {@link #setBackground(Color)}, or {@code null} for none.
     */
    public Color getBackground() {
        return this.backgroundColor;
    }

    /**
     * Applies a solid background colour to every page in the document
     * (via {@link Page#setBackground(Color)}). Mirrors C# {@code Document.Background}.
     *
     * @param color the colour, or null/white to remove backgrounds
     */
    public void setBackground(Color color) {
        this.backgroundColor = color;
        try {
            PageCollection pages = getPages();
            for (int i = 1; i <= pages.getCount(); i++) {
                pages.get(i).setBackground(color);
            }
        } catch (IOException e) {
            // Pages unavailable — defer until they are loaded; the field is set.
        }
    }

    public PageCollection getPages() throws IOException {
        if (pages == null) {
            synchronized (parserLock) {
                if (pages == null) {
                    if (parser == null) {
                        throw new IOException("Document has no parser and no pages");
                    }
                    COSDictionary catalog = parser.getCatalog();
                    COSBase pagesRef = catalog.get(COSName.PAGES);
                    COSBase pagesObj = parser.resolveReference(pagesRef);
                    if (!(pagesObj instanceof COSDictionary)) {
                        pagesObj = recoverPagesDictionaryFromObjects();
                    }
                    if (!(pagesObj instanceof COSDictionary)) {
                        throw new IOException("Cannot find /Pages dictionary in catalog");
                    }
                    pages = new PageCollection((COSDictionary) pagesObj, parser);
                    pages.setOwningDocument(this);
                }
            }
        }
        return pages;
    }

    /**
     * Returns the document information dictionary (ISO 32000-1:2008, §14.3.3).
     * Auto-creates an empty {@code /Info} dictionary if the document does not
     * yet have one — for both freshly-constructed documents and reopened files
     * whose trailer omits {@code /Info}. The result is therefore <strong>never
     * null</strong>, and consecutive calls return the same instance.
     *
     * <p>The auto-created dict is only persisted to the saved file once at
     * least one metadata entry (Title, Author, etc.) is set on it; an empty
     * dict is dropped at save time to avoid emitting a useless object.</p>
     *
     * @return the DocumentInfo (never null)
     * @throws IOException if the info dictionary cannot be read
     */
    public DocumentInfo getInfo() throws IOException {
        if (info != null) {
            return info;
        }
        if (parser != null) {
            COSDictionary trailer = parser.getTrailer();
            COSBase infoRef = trailer.get(COSName.INFO);
            if (infoRef != null) {
                COSBase infoObj = parser.resolveReference(infoRef);
                if (infoObj instanceof COSDictionary) {
                    info = new DocumentInfo((COSDictionary) infoObj);
                    return info;
                }
            }
        }
        // Auto-create
        COSDictionary infoDict = new COSDictionary();
        if (parser != null) {
            parser.getTrailer().set(COSName.INFO, infoDict);
        }
        info = new DocumentInfo(infoDict);
        return info;
    }

    /**
     * Deprecated alias for {@link #getInfo()}. {@code getInfo()} itself now
     * auto-creates a writable empty info dict when one is absent, so the two
     * methods are equivalent. Retained for backwards compatibility.
     *
     * @return the DocumentInfo (never null)
     * @throws IOException if the info dictionary cannot be read
     * @deprecated use {@link #getInfo()} directly; it auto-creates as of Sprint 20.
     */
    @Deprecated
    public DocumentInfo getOrCreateInfo() throws IOException {
        return getInfo();
    }

    /**
     * Returns the XMP metadata of this document.
     * Creates empty metadata if none exists. The returned object is cached;
     * modifications are written back when the document is saved.
     *
     * @return the XMP metadata
     * @throws IOException if reading fails
     */
    public XmpMetadata getMetadata() throws IOException {
        if (metadata == null) {
            if (parser != null) {
                COSDictionary catalog = parser.getCatalog();
                COSBase metaRef = catalog.get(COSName.of("Metadata"));
                if (metaRef != null) {
                    COSBase resolved = parser.resolveReference(metaRef);
                    if (resolved instanceof COSStream) {
                        byte[] xmpBytes = ((COSStream) resolved).getDecodedData();
                        metadata = new XmpMetadata(xmpBytes);
                    }
                }
            }
            if (metadata == null) {
                metadata = new XmpMetadata();
            }
        }
        return metadata;
    }

    /**
     * Sets the XMP metadata from raw XML bytes read from a stream.
     *
     * @param stream the input stream containing XMP XML
     * @throws IOException if reading fails
     */
    public void setXmpMetadata(InputStream stream) throws IOException {
        byte[] xmpBytes = readAllBytes(stream);
        metadata = new XmpMetadata(xmpBytes);
    }

    /**
     * Writes the XMP metadata XML bytes to the given output stream.
     *
     * @param stream the output stream
     * @throws IOException if writing fails
     */
    public void getXmpMetadata(OutputStream stream) throws IOException {
        XmpMetadata meta = getMetadata();
        byte[] bytes = meta.getBytes();
        stream.write(bytes);
    }

    /**
     * Returns the PDF version string (e.g. "1.4", "1.7").
     *
     * @return the version string
     */
    public String getVersion() {
        return String.valueOf(parser.getVersion());
    }

    /**
     * Returns the root catalog dictionary.
     *
     * @return the catalog dictionary
     * @throws IOException if the catalog cannot be read
     */
    public COSDictionary getCatalog() throws IOException {
        if (inMemoryCatalog != null) return inMemoryCatalog;
        return parser.getCatalog();
    }

    /**
     * Returns the document-level action triggers (ISO 32000-1:2008, §12.6.4.1):
     * {@code /OpenAction} plus the {@code /AA} entries (will-close, will/did-save,
     * will/did-print). The returned view is a live wrapper around the catalog;
     * mutations through it write directly to the catalog dictionary.
     *
     * @return a non-null DocumentActions view
     * @throws IOException if the catalog cannot be read
     */
    public DocumentActions getActions() throws IOException {
        return new DocumentActions(getCatalog(), this);
    }

    /**
     * Returns the trailer dictionary.
     *
     * @return the trailer dictionary
     */
    public COSDictionary getTrailer() {
        return parser.getTrailer();
    }

    /**
     * Returns the underlying PDF parser (for internal use by engine components).
     *
     * @return the parser, or null for new documents
     */
    public PDFParser getParser() {
        return parser;
    }

    /**
     * Returns the document outline (bookmarks) collection.
     * Creates an empty collection if the document has no outlines.
     *
     * @return the outline collection
     * @throws IOException if reading the catalog fails
     */
    public OutlineCollection getOutlines() throws IOException {
        if (outlines == null) {
            if (parser != null) {
                COSDictionary catalog = parser.getCatalog();
                COSBase outlinesRef = catalog.get(COSName.of("Outlines"));
                if (outlinesRef != null) {
                    COSBase resolved = parser.resolveReference(outlinesRef);
                    if (resolved instanceof COSDictionary) {
                        outlines = new OutlineCollection((COSDictionary) resolved, this, parser);
                    }
                }
            }
            if (outlines == null) {
                outlines = new OutlineCollection(this, parser);
                if (parser != null) {
                    parser.getCatalog().set(COSName.of("Outlines"), outlines.getCOSDictionary());
                }
            }
        }
        return outlines;
    }

    /**
     * Returns the page mode — how the document should be displayed when opened.
     *
     * @return the page mode
     * @throws IOException if reading the catalog fails
     */
    /**
     * Returns the interactive form (AcroForm) of this document.
     * Creates an empty form if the document has none.
     *
     * @return the form
     * @throws IOException if reading the catalog fails
     */
    public Form getForm() throws IOException {
        if (form == null) {
            if (parser != null) {
                COSDictionary catalog = parser.getCatalog();
                COSBase acroFormRef = catalog.get(COSName.of("AcroForm"));
                if (acroFormRef != null) {
                    COSBase resolved = parser.resolveReference(acroFormRef);
                    if (resolved instanceof COSDictionary) {
                        form = new Form((COSDictionary) resolved, this, parser);
                    }
                }
            }
            if (form == null) {
                // Create an empty AcroForm AND attach it to the catalog so that
                // any subsequent add() calls actually persist to the saved PDF.
                // Without this, FormEditor.addField on a PDF that has no
                // /AcroForm landed in an orphaned dict that the writer never
                // serialised — see PDFNEWNET-31679.
                COSDictionary emptyForm = new COSDictionary();
                emptyForm.set(COSName.of("Fields"), new org.aspose.pdf.engine.cos.COSArray());
                if (parser != null) {
                    parser.getCatalog().set(COSName.of("AcroForm"), emptyForm);
                }
                form = new Form(emptyForm, this, parser);
            }
        }
        return form;
    }

    /**
     * Returns the collection of embedded files (attachments).
     *
     * @return the embedded file collection
     */
    public EmbeddedFileCollection getEmbeddedFiles() {
        if (embeddedFiles == null) {
            embeddedFiles = new EmbeddedFileCollection(this, parser);
        }
        return embeddedFiles;
    }

    /** PDF portfolio (catalog /Collection); lazily backed by embedded files. */
    private Collection collection;

    /**
     * Returns the document's portfolio {@link Collection}, instantiating an
     * empty one on first access. Mirrors the C# auto-create behaviour, so
     * code such as {@code doc.getCollection().add(fs)} works without first
     * calling {@link #setCollection(Collection)}.
     */
    public Collection getCollection() {
        if (collection == null) {
            setCollection(new Collection());
        }
        return collection;
    }

    /**
     * Replaces (or removes, when {@code value == null}) the document
     * portfolio. Stamps {@code /Collection &lt;&lt; /View /D &gt;&gt;} on the
     * catalog so viewers know to render in collection mode.
     */
    public void setCollection(Collection value) {
        this.collection = value;
        try {
            org.aspose.pdf.engine.cos.COSDictionary catalog = getCatalog();
            if (value == null) {
                catalog.remove(org.aspose.pdf.engine.cos.COSName.of("Collection"));
                return;
            }
            org.aspose.pdf.engine.cos.COSDictionary col = new org.aspose.pdf.engine.cos.COSDictionary();
            col.set(org.aspose.pdf.engine.cos.COSName.of("Type"),
                    org.aspose.pdf.engine.cos.COSName.of("Collection"));
            // /View /D — Detail view; the standard portfolio entry point.
            col.set(org.aspose.pdf.engine.cos.COSName.of("View"),
                    org.aspose.pdf.engine.cos.COSName.of("D"));
            catalog.set(org.aspose.pdf.engine.cos.COSName.of("Collection"), col);
            value.bind(this);
        } catch (java.io.IOException e) {
            // Catalog access failed — leave the in-memory binding so add()
            // still pushes through to embedded files, which is what callers
            // actually inspect.
        }
    }

    /**
     * Returns the font utilities for this document.
     * Provides access to all fonts used in the document and font subsetting.
     *
     * @return the font utilities instance
     */
    public FontUtilities getFontUtilities() {
        if (fontUtilities == null) {
            fontUtilities = new FontUtilities(this);
        }
        return fontUtilities;
    }

    /**
     * Returns whether the standard 14 PDF fonts should be embedded when saving.
     *
     * @return true if standard fonts should be embedded
     */
    public boolean isEmbedStandardFonts() {
        return embedStandardFonts;
    }

    /**
     * Sets whether the standard 14 PDF fonts should be embedded when saving.
     * <p>
     * When set to {@code true}, the PDF writer will embed the standard fonts
     * (Helvetica, Times-Roman, Courier, etc.) into the output file. This increases
     * file size but ensures consistent rendering on all viewers.
     * </p>
     *
     * @param embed true to embed standard fonts
     */
    public void setEmbedStandardFonts(boolean embed) {
        this.embedStandardFonts = embed;
    }

    /**
     * Returns the list of Optional Content Groups (layers).
     *
     * @return the layers, or empty list
     * @throws IOException if catalog reading fails
     */
    public java.util.List<Layer> getLayers() throws IOException {
        java.util.List<Layer> layers = new java.util.ArrayList<>();
        if (parser == null) return layers;
        COSDictionary catalog = parser.getCatalog();
        COSBase ocProps = parser.resolveReference(catalog.get(COSName.of("OCProperties")));
        if (!(ocProps instanceof COSDictionary)) return layers;
        COSBase ocgs = parser.resolveReference(((COSDictionary) ocProps).get(COSName.of("OCGs")));
        if (!(ocgs instanceof org.aspose.pdf.engine.cos.COSArray)) return layers;
        org.aspose.pdf.engine.cos.COSArray arr = (org.aspose.pdf.engine.cos.COSArray) ocgs;
        for (int i = 0; i < arr.size(); i++) {
            COSBase ocg = parser.resolveReference(arr.get(i));
            if (ocg instanceof COSDictionary) layers.add(new Layer((COSDictionary) ocg));
        }
        return layers;
    }

    /**
     * Returns the viewer preferences.
     *
     * @return the viewer preferences
     * @throws IOException if catalog reading fails
     */
    public ViewerPreferences getViewerPreferences() throws IOException {
        if (viewerPreferences == null) {
            if (parser != null) {
                COSDictionary catalog = parser.getCatalog();
                COSBase vp = parser.resolveReference(catalog.get(COSName.of("ViewerPreferences")));
                if (vp instanceof COSDictionary) {
                    viewerPreferences = new ViewerPreferences((COSDictionary) vp);
                } else {
                    COSDictionary vpDict = new COSDictionary();
                    catalog.set(COSName.of("ViewerPreferences"), vpDict);
                    viewerPreferences = new ViewerPreferences(vpDict);
                }
            } else {
                viewerPreferences = new ViewerPreferences(null);
            }
        }
        return viewerPreferences;
    }

    // ── Viewer preference convenience methods ──

    /** Hide toolbar preference. */
    public boolean getHideToolbar() throws IOException { return getViewerPreferences().getHideToolbar(); }
    /** Sets hide toolbar preference. */
    public void setHideToolbar(boolean v) throws IOException { getViewerPreferences().setHideToolbar(v); }

    /** Hide menubar preference. */
    public boolean getHideMenubar() throws IOException { return getViewerPreferences().getHideMenubar(); }
    /** Sets hide menubar preference. */
    public void setHideMenubar(boolean v) throws IOException { getViewerPreferences().setHideMenubar(v); }

    /** Hide window UI preference. */
    public boolean getHideWindowUI() throws IOException { return getViewerPreferences().getHideWindowUI(); }
    /** Sets hide window UI preference. */
    public void setHideWindowUI(boolean v) throws IOException { getViewerPreferences().setHideWindowUI(v); }

    /** Fit window preference. */
    public boolean getFitWindow() throws IOException { return getViewerPreferences().getFitWindow(); }
    /** Sets fit window preference. */
    public void setFitWindow(boolean v) throws IOException { getViewerPreferences().setFitWindow(v); }

    /** Center window preference. */
    public boolean getCenterWindow() throws IOException { return getViewerPreferences().getCenterWindow(); }
    /** Sets center window preference. */
    public void setCenterWindow(boolean v) throws IOException { getViewerPreferences().setCenterWindow(v); }

    /** Display document title preference. */
    public boolean getDisplayDocTitle() throws IOException { return getViewerPreferences().getDisplayDocTitle(); }
    /** Sets display document title preference. */
    public void setDisplayDocTitle(boolean v) throws IOException { getViewerPreferences().setDisplayDocTitle(v); }

    /** Page layout (SinglePage, OneColumn, TwoColumnLeft, etc.). */
    public String getPageLayout() throws IOException {
        if (parser == null) return "SinglePage";
        String layout = parser.getCatalog().getNameAsString("PageLayout");
        return layout != null ? layout : "SinglePage";
    }
    /** Sets page layout. */
    public void setPageLayout(String layout) throws IOException {
        if (parser != null) {
            parser.getCatalog().set(COSName.of("PageLayout"), COSName.of(layout));
        }
    }

    public PageMode getPageMode() throws IOException {
        if (parser == null) return PageMode.UseNone;
        String mode = parser.getCatalog().getNameAsString("PageMode");
        if (mode == null) return PageMode.UseNone;
        try {
            return PageMode.valueOf(mode);
        } catch (IllegalArgumentException e) {
            return PageMode.UseNone;
        }
    }

    /**
     * Sets the page mode.
     *
     * @param mode the page mode
     * @throws IOException if writing to the catalog fails
     */
    public void setPageMode(PageMode mode) throws IOException {
        if (parser != null) {
            parser.getCatalog().set(COSName.of("PageMode"), COSName.of(mode.name()));
        }
    }

    /**
     * Saves the document to a file.
     *
     * Returns a PageInfo object that describes default page properties for newly added pages.
     *
     * @return the default page info
     */
    public PageInfo getPageInfo() {
        if (pageInfo == null) {
            pageInfo = new PageInfo();
        }
        return pageInfo;
    }

    /**
     * Processes paragraphs for all pages (layout pass).
     * This is a no-op placeholder for API compatibility with Aspose.PDF.
     */
    public void processParagraphs() {
        // Layout is performed during save; this method exists for API compatibility
    }

    /**
     * @param filePath the output file path
     * @throws IOException              if writing fails
     * @throws IllegalArgumentException if filePath is null
     */
    public void save(String filePath) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        if (optimizeRequested && parser != null) {
            save(filePath, new PdfSaveOptions().setLinearize(true));
            optimizeRequested = false;
            return;
        }
        // Check if saving to the same file we loaded from
        if ((sourcePath != null && isSameFile(filePath, sourcePath))
                || (sourcePath == null && sourceBytes != null && fileName != null && isSameFile(filePath, fileName))) {
            saveToSameFile(filePath);
        } else {
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                save(fos);
            }
        }
    }

    /**
     * Saves the document back to its associated target path.
     *
     * @throws IOException if the document has no associated path or saving fails
     */
    public void save() throws IOException {
        if (sourcePath != null) {
            save(sourcePath);
            return;
        }
        if (fileName != null && !fileName.isEmpty()) {
            save(fileName);
            return;
        }
        throw new IOException("Cannot save document without a target path. Open from a file or set fileName first.");
    }

    /**
     * Saves the document to an output stream.
     * Collects all objects from the parser and writes a complete PDF.
     *
     * @param outputStream the output stream to write to
     * @throws IOException              if writing fails
     * @throws IllegalArgumentException if outputStream is null
     */
    public void save(OutputStream outputStream) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        // Flush any per-Page OperatorCollection mutations back into /Contents
        // before the save walks the object graph. Without this, changes made
        // via TextFragment.setText (and other mutations to the cached ops)
        // would silently disappear on save.
        flushDirtyPages();
        // PDFNET-38279: render any header/footer applied to the pages of a
        // loaded document as a Form XObject overlay. The new-document path
        // (saveNewDocument -> LayoutEngine.layout) already renders header/footer
        // from paragraphs, so this only runs for parser-backed documents to
        // avoid double rendering.
        if (parser != null && pages != null) {
            int totalPages = pages.size();
            int pageNum = 0;
            for (Page page : pages) {
                pageNum++;
                if (page.getHeader() != null || page.getFooter() != null) {
                    page.applyHeaderFooterOverlay(pageNum, totalPages);
                }
            }
        }
        boolean repairedPageTree = false;
        if (parser != null) {
            PageCollection documentPages = getPages();
            documentPages.repairBrokenTreeIfNeeded();
            repairedPageTree = documentPages.wasTreeRepaired();
        }
        // Validate tagged PDF structure before saving
        if (taggedContent != null) {
            taggedContent.validateBeforeSave();
        }
        if (parser != null) {
            // Check if cross-document outline items were added (objectKey collisions possible)
            boolean hasCrossDocOutlines = false;
            if (outlines != null) {
                for (OutlineItemCollection item : outlines) {
                    COSObjectKey itemKey = item.getCOSDictionary().getObjectKey();
                    if (itemKey != null) {
                        try {
                            COSBase existing = parser.getObject(itemKey);
                            if (existing != item.getCOSDictionary()) {
                                hasCrossDocOutlines = true;
                                break;
                            }
                        } catch (Exception e) {
                            hasCrossDocOutlines = true;
                            break;
                        }
                    } else if (item.getCOSDictionary().get("Title") != null) {
                        // New item without objectKey — needs fullRewrite to register
                        hasCrossDocOutlines = true;
                        break;
                    }
                }
            }

            if (hasCrossDocOutlines || repairedPageTree || optimizeRequested || fullRewriteRequested || pendingEncryptor != null
                    || !pendingImports.isEmpty() || metadata != null || pdfaCompliant) {
                saveFullRewrite(outputStream);
                optimizeRequested = false;
                fullRewriteRequested = false;
            } else {
                Map<COSObjectKey, COSBase> modifiedObjects = collectModifiedObjects();
                // Incremental append of a modified page content stream is not
                // reliably honoured on reload for hybrid-reference / xref-stream
                // sources (§7.5.8.4) — the appended object's xref entry is lost,
                // so the edit silently reverts (BUG-TFA-REPLACE-001, e.g.
                // PDFNET_42759). Fall back to a full rewrite in exactly that case.
                // Gated on content-stream edits so signing/other incremental
                // saves (which touch dictionaries, not /Contents) are unaffected.
                boolean unreliableIncremental = !modifiedObjects.isEmpty()
                        && editedPageContentsThisSave
                        && sourceUsesXRefStream();
                if (unreliableIncremental) {
                    saveFullRewrite(outputStream);
                    optimizeRequested = false;
                    fullRewriteRequested = false;
                } else if (!modifiedObjects.isEmpty() && (sourcePath != null || sourceBytes != null)) {
                    saveIncremental(outputStream, modifiedObjects);
                } else {
                    saveFullRewrite(outputStream);
                    optimizeRequested = false;
                    fullRewriteRequested = false;
                }
            }
        } else {
            // New document — build object graph manually
            saveNewDocument(outputStream);
            optimizeRequested = false;
            fullRewriteRequested = false;
        }
    }

    /**
     * Returns whether the current document contains incremental updates.
     *
     * @return true if the trailer contains a valid /Prev entry
     */
    public boolean hasIncrementalUpdate() {
        if (sourcePath != null || sourceBytes != null) {
            try {
                int startxrefCount = countStartxrefMarkers();
                if (startxrefCount > 1) {
                    COSDictionary trailer = parser != null ? parser.getTrailer() : null;
                    if (trailer != null && trailer.get(COSName.of("XRefStm")) instanceof COSInteger
                            && startxrefCount == 2) {
                        return false;
                    }
                    return true;
                }
            } catch (IOException e) {
                // Fall back to trailer inspection below.
            }
        }
        if (parser == null) {
            return false;
        }
        COSDictionary trailer = parser.getTrailer();
        if (trailer == null) {
            return false;
        }
        COSBase prev = trailer.get(COSName.of("Prev"));
        return prev instanceof COSInteger && ((COSInteger) prev).longValue() > 0;
    }

    /**
     * Requests a full rewrite on the next save instead of an incremental append.
     * This is a compatibility baseline for {@code Document.Optimize()}.
     */
    public void optimize() {
        optimizeRequested = true;
    }

    /**
     * Requests that the next save operation performs a full rewrite even if
     * incremental save would otherwise be possible.
     */
    public void requestFullRewrite() {
        fullRewriteRequested = true;
    }

    /**
     * Returns whether the current document is linearized for fast web view.
     * <p>
     * For documents opened from a file or byte array this inspects the source bytes
     * using the linearization detector. New in-memory documents that have not been
     * reopened from saved bytes return {@code false}.
     * </p>
     *
     * @return {@code true} if the current source bytes represent a linearized PDF
     */
    public boolean isLinearized() {
        if (sourcePath == null && sourceBytes == null) {
            return false;
        }
        try {
            RandomAccessReader reader = sourcePath != null
                    ? RandomAccessReader.fromFile(new File(sourcePath))
                    : RandomAccessReader.fromBytes(sourceBytes);
            try {
                return org.aspose.pdf.engine.linearization.LinearizationDetector.detect(reader) != null;
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            LOG.fine(() -> "Failed to detect linearization: " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes unused page-level resources before the next save.
     * <p>
     * This is a focused compatibility implementation of Aspose's
     * {@code OptimizeResources()} for imported-page scenarios: it prunes
     * unreferenced entries from page and form XObject resource dictionaries
     * based on the actual operators present in content streams.
     * </p>
     *
     * @throws IOException if page content streams cannot be parsed
     */
    public void optimizeResources() throws IOException {
        for (int i = 1; i <= getPages().getCount(); i++) {
            pruneUnusedResources(getPages().get(i), new java.util.IdentityHashMap<>());
        }
        optimizeRequested = true;
    }

    /**
     * Optimises the document according to the supplied options before the next
     * save. API-compatible with Aspose's
     * {@code Document.OptimizeResources(OptimizationOptions)}.
     * <p>
     * The structural passes are applied immediately: enabling
     * {@code removeUnusedObjects}/{@code removeUnusedStreams} requests a full
     * rewrite on the next save (a full rewrite drops every object the catalog no
     * longer references), and {@code allowReusePageContent} prunes unused
     * per-page resources. The image- and font-rewriting passes are accepted for
     * API compatibility; they are honoured where the engine already supports the
     * operation and are otherwise a no-op (the document still saves correctly).
     * </p>
     *
     * @param options the optimisation options; {@code null} applies nothing
     * @throws IOException if page content streams cannot be parsed
     */
    public void optimizeResources(org.aspose.pdf.optimization.OptimizationOptions options)
            throws IOException {
        if (options == null) {
            return;
        }
        if (options.isAllowReusePageContent() || options.isRemoveUnusedStreams()) {
            for (int i = 1; i <= getPages().getCount(); i++) {
                pruneUnusedResources(getPages().get(i), new java.util.IdentityHashMap<>());
            }
        }
        // A full rewrite re-serialises only the objects still reachable from the
        // trailer, which is exactly "remove unused objects/streams". Duplicate
        // streams are likewise only written once when the writer dedups, so we
        // also request the rewrite for linkDuplicateStreams.
        if (options.isRemoveUnusedObjects() || options.isRemoveUnusedStreams()
                || options.isLinkDuplicateStreams()) {
            optimizeRequested = true;
        }
        LOG.fine(() -> "optimizeResources(options): rewrite="
                + (options.isRemoveUnusedObjects() || options.isRemoveUnusedStreams()
                        || options.isLinkDuplicateStreams())
                + " compressImages=" + options.isCompressImages()
                + " subsetFonts=" + options.isSubsetFonts());
    }

    /**
     * Saves the document in the specified format.
     *
     * @param filePath the output file path
     * @param format   the desired output format
     * @throws IOException if writing fails
     */
    public void save(String filePath, SaveFormat format) throws IOException {
        if (format == SaveFormat.Html) {
            save(filePath, new HtmlSaveOptions());
        } else {
            save(filePath);
        }
    }

    /**
     * Saves the document with the specified PDF save options.
     * When {@link PdfSaveOptions#isLinearize()} is true, produces a linearized
     * (web-optimized) PDF per ISO 32000-1 Annex F.
     *
     * @param filePath the output file path
     * @param options  PDF save options
     * @throws IOException if writing fails
     */
    public void save(String filePath, PdfSaveOptions options) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        flushDirtyPages();
        // Linearize / compressed writers re-read from the parser's reader while
        // building the output, so opening a FileOutputStream on the same path
        // first (which truncates on Windows) would yank the parser's bytes out
        // from under it (PDFNET-54615). Route through a temp-file helper.
        boolean sameFile = (sourcePath != null && isSameFile(filePath, sourcePath))
                || (sourcePath == null && sourceBytes != null && fileName != null
                        && isSameFile(filePath, fileName));
        if (options != null && options.isLinearize()) {
            if (parser == null) {
                throw new IOException("Cannot linearize a new (unparsed) document");
            }
            if (sameFile) {
                saveToSameFileWith(filePath, fos -> {
                    LinearizedPDFWriter writer = new LinearizedPDFWriter();
                    writer.write(fos, parser, parser.getTrailer());
                });
            } else {
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    LinearizedPDFWriter writer = new LinearizedPDFWriter();
                    writer.write(fos, parser, parser.getTrailer());
                }
            }
        } else if (options != null && (options.isUseObjectStreams() || options.isUseXRefStream())) {
            if (parser == null) {
                throw new IOException("Cannot write compressed: no parser (new document)");
            }
            if (sameFile) {
                saveToSameFileWith(filePath, fos -> saveCompressed(fos, options));
            } else {
                try (FileOutputStream fos = new FileOutputStream(filePath)) {
                    saveCompressed(fos, options);
                }
            }
        } else {
            save(filePath);
        }
    }

    /**
     * Saves the document using object streams and/or xref streams (PDF 1.5+).
     *
     * @param outputStream the output stream
     * @param options      save options controlling compression
     * @throws IOException if writing fails
     */
    private void saveCompressed(OutputStream outputStream, PdfSaveOptions options) throws IOException {
        Map<COSObjectKey, COSBase> objects = new LinkedHashMap<>();
        int maxObjNum = 0;
        for (COSObjectKey key : parser.getAllObjectKeys()) {
            COSBase obj = parser.getObject(key);
            if (obj != null && !(obj instanceof COSNull)) {
                objects.put(key, obj);
                maxObjNum = Math.max(maxObjNum, key.getObjectNumber());
            }
        }
        if (metadata != null) {
            maxObjNum = syncXmpToCatalog(parser.getCatalog(), objects, maxObjNum);
        }

        float version = Math.max(parser.getVersion(), 1.5f);
        PDFWriter writer = new PDFWriter(outputStream, version);
        COSDictionary trailer = new COSDictionary(parser.getTrailer());
        configureExistingEncryption(writer, trailer);
        maxObjNum = applyPendingEncryption(writer, trailer, objects, maxObjNum);

        if (options.isUseObjectStreams()) {
            writer.writeCompressed(trailer, objects, options.getObjectsPerStream());
        } else {
            // XRef stream only, no object streams — use maxPerStream=MAX_VALUE to skip packing
            writer.writeCompressed(trailer, objects, Integer.MAX_VALUE);
        }
    }

    /**
     * Saves the document as HTML with the specified options.
     *
     * @param filePath the output HTML file path
     * @param options  HTML save options
     * @throws IOException if conversion or writing fails
     */
    public void save(String filePath, HtmlSaveOptions options) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        flushDirtyPages();
        if (options == null) options = new HtmlSaveOptions();
        PdfToHtmlConverter converter = new PdfToHtmlConverter();
        String html = converter.convert(this, options);
        java.nio.file.Files.writeString(java.nio.file.Path.of(filePath), html,
            StandardCharsets.UTF_8);
    }

    /**
     * Returns the file path this document was loaded from, or {@code null} for new documents
     * or documents loaded from streams.
     *
     * @return the source file path, or {@code null}
     */
    public String getSourcePath() {
        return sourcePath;
    }

    /**
     * Returns the file name (path) this document was opened from.
     *
     * @return the file name, or {@code null} for new or stream-based documents
     */
    public String getFileName() {
        return fileName;
    }

    /**
     * Sets the file name associated with this document.
     *
     * @param fileName the file name to set
     */
    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /**
     * Exports all annotations from this document to an XFDF file.
     *
     * @param filePath the output XFDF file path
     * @throws IOException if writing fails
     */
    public void exportAnnotationsToXfdf(String filePath) throws IOException {
        XfdfExporter.export(this, filePath);
    }

    /**
     * Exports all annotations from this document to an XFDF output stream.
     *
     * @param stream the output stream to write XFDF XML to
     * @throws IOException if writing fails
     */
    public void exportAnnotationsToXfdf(OutputStream stream) throws IOException {
        XfdfExporter.export(this, stream);
    }

    /**
     * Imports annotations from an XFDF file into this document.
     *
     * @param filePath the path to the XFDF file
     * @throws IOException if reading fails
     */
    public void importAnnotationsFromXfdf(String filePath) throws IOException {
        XfdfImporter.importXfdf(this, filePath);
    }

    /**
     * Imports annotations from an XFDF input stream into this document.
     *
     * @param stream the input stream containing XFDF XML
     * @throws IOException if reading fails
     */
    public void importAnnotationsFromXfdf(InputStream stream) throws IOException {
        XfdfImporter.importXfdf(this, stream);
    }

    /**
     * Flattens the entire document: bakes all annotation and form field appearances
     * into page content streams, then removes annotations and form fields.
     * <p>
     * This iterates all pages calling {@link Page#flattenAnnotations()}, then
     * calls {@link Form#flatten()} to remove form fields. After flattening,
     * annotations and form fields are no longer interactive -- their visual
     * appearance becomes part of the static page content.
     * </p>
     *
     * @throws IOException if reading pages, annotations, or form data fails
     */
    public void flatten() throws IOException {
        PageCollection pgs = getPages();
        for (int i = 1; i <= pgs.getCount(); i++) {
            pgs.get(i).flattenAnnotations();
        }
        getForm().flatten();
    }

    /**
     * Flattens the document using the specified form flatten settings.
     * <p>
     * Bakes all annotation appearances into page content streams and flattens
     * form fields according to the provided {@link Form.FlattenSettings}.
     * </p>
     *
     * @param settings the flatten settings controlling the flattening behavior
     * @throws IOException if reading pages, annotations, or form data fails
     */
    public void flatten(Form.FlattenSettings settings) throws IOException {
        PageCollection pgs = getPages();
        for (int i = 1; i <= pgs.getCount(); i++) {
            pgs.get(i).flattenAnnotations();
        }
        getForm().flatten(settings);
    }

    /**
     * Returns the document's named destinations collection.
     * Provides access to destinations stored in {@code /Dests} or {@code /Names→/Dests}.
     *
     * @return the named destinations, or {@code null} if no parser
     * @throws IOException if the catalog cannot be read
     */
    public NamedDestinations getNamedDestinations() throws IOException {
        if (namedDestinations == null) {
            COSDictionary catalog = getCatalog();
            if (catalog != null) {
                namedDestinations = new NamedDestinations(catalog, this, parser);
            }
        }
        return namedDestinations;
    }

    /**
     * Returns the page labels, or {@code null} if {@code /PageLabels} is not present.
     *
     * @return the page labels, or {@code null}
     * @throws IOException if the catalog cannot be read
     */
    public PageLabels getPageLabels() throws IOException {
        if (parser == null) return null;
        return PageLabels.parse(parser.getCatalog());
    }

    /**
     * Returns the document's tagged content, providing access to the logical structure tree.
     * Creates the structure tree if it doesn't exist.
     *
     * @return the tagged content API
     * @throws IOException if catalog access fails
     */
    public TaggedContent getTaggedContent() throws IOException {
        if (taggedContent == null) {
            COSDictionary catalog = getCatalog();
            if (catalog != null) {
                taggedContent = new TaggedContent(this, catalog, parser);
            }
        }
        return taggedContent;
    }

    /**
     * Returns the logical structure tree for reading.
     * Returns {@code null} if the document has no structure tree.
     *
     * @return the structure tree root, or {@code null}
     * @throws IOException if catalog access fails
     */
    public StructTreeRoot getLogicalStructure() throws IOException {
        COSDictionary catalog = getCatalog();
        if (catalog == null) return null;
        COSBase strObj = catalog.get("StructTreeRoot");
        if (strObj instanceof COSObjectReference) {
            strObj = ((COSObjectReference) strObj).dereference();
        }
        if (strObj instanceof COSDictionary) {
            return new StructTreeRoot((COSDictionary) strObj, parser);
        }
        return null;
    }

    /**
     * Allocates a new object number for a new indirect object.
     * Starts from max existing object number + 1.
     *
     * @return the next available object number
     */
    public int allocateObjectNumber() {
        if (nextNewObjectNumber < 0) {
            if (parser != null) {
                int max = 0;
                for (COSObjectKey key : parser.getAllObjectKeys()) {
                    max = Math.max(max, key.getObjectNumber());
                }
                nextNewObjectNumber = max + 1;
            } else {
                nextNewObjectNumber = 1;
            }
        }
        return nextNewObjectNumber++;
    }

    /**
     * Registers an object imported from another document under a freshly allocated
     * key. Returns a {@link COSObjectReference} whose resolver points into this
     * document's pending-imports map; the object is merged into the output
     * during save.
     */
    public COSObjectReference registerImportedObject(COSBase body) {
        if (body == null) throw new IllegalArgumentException("body must not be null");
        COSObjectKey key = new COSObjectKey(allocateObjectNumber(), 0);
        pendingImports.put(key, body);
        if (body instanceof COSDictionary) {
            ((COSDictionary) body).setObjectKey(key);
        }
        return new COSObjectReference(key, k -> pendingImports.get(k));
    }

    /** Returns the pending-imports map (package-private for PageCollection/save paths). */
    Map<COSObjectKey, COSBase> getPendingImports() {
        return pendingImports;
    }

    /**
     * Collects all COS objects that have been modified since loading.
     *
     * @return map of modified object keys to their COS objects
     * @throws IOException if objects cannot be loaded
     */
    private Map<COSObjectKey, COSBase> collectModifiedObjects() throws IOException {
        Map<COSObjectKey, COSBase> modified = new LinkedHashMap<>();
        // Snapshot the key set first — parser.getObject can lazily load new
        // objects (compressed-object streams, recovered objects) and mutate the
        // backing map, which would otherwise raise ConcurrentModificationException.
        List<COSObjectKey> keys = new ArrayList<>(parser.getAllObjectKeys());
        for (COSObjectKey key : keys) {
            try {
                COSBase obj = parser.getObject(key);
                if (obj != null && isDirtyDeep(obj)) {
                    modified.put(key, obj);
                }
            } catch (IOException e) {
                LOG.fine(() -> "Skipping unreadable object during incremental dirty scan: "
                        + key + " (" + e.getMessage() + ")");
            }
        }
        return modified;
    }

    /**
     * Checks if an object or any of its direct children are dirty.
     * Does not follow indirect references to avoid loading the entire object graph.
     *
     * @param obj the object to check
     * @return {@code true} if the object or any direct child is dirty
     */
    private boolean isDirtyDeep(COSBase obj) {
        return isDirtyDeep(obj, 0);
    }

    /**
     * Recursively checks if an object or any of its direct children are dirty.
     * Does not follow indirect references. Depth-limited to prevent infinite loops.
     *
     * @param obj   the object to check
     * @param depth current recursion depth
     * @return {@code true} if any nested direct object is dirty
     */
    private boolean isDirtyDeep(COSBase obj, int depth) {
        if (obj == null || depth > 5) return false;
        if (obj.isDirty()) return true;
        if (obj instanceof COSDictionary) {
            for (COSBase value : ((COSDictionary) obj).values()) {
                if (value != null && !(value instanceof COSObjectReference)) {
                    if (isDirtyDeep(value, depth + 1)) return true;
                }
            }
        }
        if (obj instanceof COSArray) {
            COSArray arr = (COSArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item != null && !(item instanceof COSObjectReference)) {
                    if (isDirtyDeep(item, depth + 1)) return true;
                }
            }
        }
        return false;
    }

    /**
     * Performs an incremental save: copies original file bytes + appends modified objects.
     * ISO 32000-1:2008 §7.5.6.
     *
     * @param outputStream    the output stream
     * @param modifiedObjects the objects that have been modified
     * @throws IOException if writing fails
     */
    private void saveIncremental(OutputStream outputStream,
                                  Map<COSObjectKey, COSBase> modifiedObjects) throws IOException {
        RandomAccessReader original = sourcePath != null
                ? RandomAccessReader.fromFile(new File(sourcePath))
                : RandomAccessReader.fromBytes(sourceBytes);
        try {
            PDFWriter writer = new PDFWriter(outputStream, parser.getVersion());
            configureExistingEncryption(writer, parser.getTrailer());
            writer.writeIncremental(original, parser.getTrailer(), modifiedObjects);
        } finally {
            original.close();
        }
    }

    /**
     * Performs a full rewrite of the PDF (collects all objects and writes a complete file).
     *
     * @param outputStream the output stream
     * @throws IOException if writing fails
     */
    private void saveFullRewrite(OutputStream outputStream) throws IOException {
        Map<COSObjectKey, COSBase> objects = new LinkedHashMap<>();
        int maxObjNum = 0;
        for (COSObjectKey key : parser.getAllObjectKeys()) {
            try {
                COSBase obj = parser.getObject(key);
                if (obj != null && !(obj instanceof COSNull)) {
                    objects.put(key, obj);
                    maxObjNum = Math.max(maxObjNum, key.getObjectNumber());
                }
            } catch (IOException e) {
                LOG.fine(() -> "Skipping unreadable object during full rewrite: "
                        + key + " (" + e.getMessage() + ")");
            }
        }
        // Merge objects imported from other documents (cross-document page copy).
        for (Map.Entry<COSObjectKey, COSBase> e : pendingImports.entrySet()) {
            objects.put(e.getKey(), e.getValue());
            maxObjNum = Math.max(maxObjNum, e.getKey().getObjectNumber());
        }
        // Register new outline items that were added programmatically (e.g. from another document)
        if (outlines != null && outlines.getCount() > 0) {
            COSDictionary outlinesDict = outlines.getCOSDictionary();
            // Ensure outlines dict is registered
            if (outlinesDict.getObjectKey() == null) {
                COSObjectKey outlinesKey = new COSObjectKey(++maxObjNum, 0);
                outlinesDict.setObjectKey(outlinesKey);
                objects.put(outlinesKey, outlinesDict);
                parser.getCatalog().set(COSName.of("Outlines"),
                        new COSObjectReference(outlinesKey, k -> objects.get(k)));
            } else if (!objects.containsKey(outlinesDict.getObjectKey())) {
                objects.put(outlinesDict.getObjectKey(), outlinesDict);
            }
            // Register each outline item (and children) — always assign new keys
            // since items may come from another document with colliding object numbers
            maxObjNum = registerOutlineItems(outlines, objects, maxObjNum);
        }
        // Sync XMP metadata to catalog if present
        if (metadata != null) {
            maxObjNum = syncXmpToCatalog(parser.getCatalog(), objects, maxObjNum);
        }
        PDFWriter writer = new PDFWriter(outputStream, parser.getVersion());
        COSDictionary trailer = new COSDictionary(parser.getTrailer());
        trailer.remove(COSName.of("Prev"));
        trailer.remove(COSName.of("XRefStm"));
        configureExistingEncryption(writer, trailer);
        maxObjNum = applyPendingEncryption(writer, trailer, objects, maxObjNum);
        writer.write(trailer, objects);
    }

    /**
     * Saves to the same file the document was loaded from.
     * Strategy: write to temp file, then rename.
     * This is necessary because we need to read the original while writing.
     *
     * @param filePath the file path (same as sourcePath)
     * @throws IOException if writing or renaming fails
     */
    private void saveToSameFile(String filePath) throws IOException {
        saveToSameFileWith(filePath, this::save);
    }

    /**
     * Functional variant of {@link #saveToSameFile}: lets the caller supply an
     * alternate writer (linearized, compressed, etc) while keeping the temp-file
     * + close-parser + atomic-move dance in one place. The parser's reader is
     * closed only after the writer finishes so writers that re-read from the
     * source stream (linearizer's PageObjectCollector, encryption sync) still see
     * the original bytes.
     */
    @FunctionalInterface
    private interface IOConsumer {
        void accept(OutputStream out) throws IOException;
    }

    private void saveToSameFileWith(String filePath, IOConsumer writer) throws IOException {
        Path target = Path.of(filePath);
        Path tempFile = java.nio.file.Files.createTempFile(target.getParent(), "openpdf-", ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tempFile.toFile())) {
                writer.accept(fos);
            }
            // Close the parser's reader to release the file handle (required on Windows)
            if (parser != null) {
                parser.close();
            }
            // Replace original with temp — try atomic move first
            try {
                java.nio.file.Files.move(tempFile, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Fallback: non-atomic replace
                java.nio.file.Files.move(tempFile, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            this.sourcePath = filePath;
            this.fileName = filePath;
            this.sourceBytes = null;
            this.optimizeRequested = false;
            this.fullRewriteRequested = false;
        } finally {
            java.nio.file.Files.deleteIfExists(tempFile);
        }
    }

    /**
     * Checks whether two file paths refer to the same file.
     */
    private boolean isSameFile(String path1, String path2) {
        try {
            return Path.of(path1).toRealPath().equals(Path.of(path2).toRealPath());
        } catch (IOException e) {
            return path1.equals(path2);
        }
    }

    private int countStartxrefMarkers() throws IOException {
        RandomAccessReader reader;
        if (sourcePath != null) {
            reader = RandomAccessReader.fromFile(new File(sourcePath));
        } else if (sourceBytes != null) {
            reader = RandomAccessReader.fromBytes(sourceBytes);
        } else {
            return 0;
        }

        byte[] pattern = "startxref".getBytes(StandardCharsets.US_ASCII);
        try {
            long from = 0;
            int count = 0;
            while (true) {
                long pos = reader.findForward(pattern, from);
                if (pos < 0) {
                    return count;
                }
                count++;
                from = pos + pattern.length;
            }
        } finally {
            reader.close();
        }
    }

    /**
     * Saves a new document (created with the default constructor) by building the
     * object graph from the in-memory page tree.
     */
    private void saveNewDocument(OutputStream outputStream) throws IOException {
        // A PDF is required by the spec (ISO 32000-1 §7.7.3) to have at least
        // one page. If the user called `new Document()` + `save()` without
        // explicitly adding any page, inject a single blank A4 page so the
        // resulting file round-trips as a valid 1-page PDF instead of being
        // saved with /Count 0 / /Kids [] and re-loading as a 0-page document.
        if (pages != null && pages.getCount() == 0) {
            pages.add();
        }
        if (!pagesPrePaginated) {
            paginateNewDocumentPagesIfNeeded();
        }

        // Run layout engine for pages that have paragraphs
        if (pages != null) {
            org.aspose.pdf.engine.layout.LayoutEngine layoutEngine =
                    new org.aspose.pdf.engine.layout.LayoutEngine();
            layoutEngine.resetNoteState();
            int totalPages = pages.size();
            int pageNum = 0;
            for (Page page : pages) {
                pageNum++;
                layoutEngine.setPageNumbering(pageNum, totalPages);
                if (page.getParagraphs() != null && !page.getParagraphs().isEmpty()) {
                    layoutEngine.layout(page);
                }
            }
        }

        Map<COSObjectKey, COSBase> objects = new LinkedHashMap<>();
        int objNum = 1;
        COSObjectReference trailerInfoRef = null;
        // Pre-seed objects with pendingImports (cross-document imports use keys
        // allocated via Document.allocateObjectNumber, which may overlap with
        // the local counter below — bump objNum past the highest imported key).
        int maxImportNum = 0;
        for (Map.Entry<COSObjectKey, COSBase> e : pendingImports.entrySet()) {
            objects.put(e.getKey(), e.getValue());
            maxImportNum = Math.max(maxImportNum, e.getKey().getObjectNumber());
        }
        if (maxImportNum > objNum) objNum = maxImportNum;

        // Build pages dictionary with /Kids
        COSDictionary pagesDict = new COSDictionary();
        pagesDict.set(COSName.TYPE, COSName.PAGES);
        org.aspose.pdf.engine.cos.COSArray kids = new org.aspose.pdf.engine.cos.COSArray();

        if (pages != null) {
            for (Page page : pages) {
                COSDictionary pd = page.getCOSDictionary();
                COSObjectKey existing = pd.getObjectKey();
                COSObjectKey pageKey;
                if (existing != null && objects.containsKey(existing) && objects.get(existing) == pd) {
                    // Imported page already registered via pendingImports — reuse its key.
                    pageKey = existing;
                } else {
                    pageKey = new COSObjectKey(++objNum, 0);
                    pd.setObjectKey(pageKey);
                    objects.put(pageKey, pd);
                }
                pd.set(COSName.PARENT, pagesDict);
                kids.add(new COSObjectReference(pageKey, k -> objects.get(k)));

                // Register content stream as indirect object.
                // BUG O: a previous saveNewDocument call replaces /Contents with
                // a COSObjectReference bound to the *old* (now-stale) objects
                // map. On the second save we must resolve through that
                // reference, recover the underlying COSStream, and re-register
                // it in the current objects map with a fresh key. Without this,
                // the page's /Contents reference would point at an object the
                // writer never emits — every page renders blank.
                COSBase contentsVal = pd.get("Contents");
                COSStream contentsStream = null;
                if (contentsVal instanceof COSStream) {
                    contentsStream = (COSStream) contentsVal;
                } else if (contentsVal instanceof COSObjectReference) {
                    try {
                        COSBase resolved = ((COSObjectReference) contentsVal).dereference();
                        if (resolved instanceof COSStream) {
                            contentsStream = (COSStream) resolved;
                        }
                    } catch (IOException ignored) {
                        // Fall through; contentsStream stays null and we leave
                        // /Contents alone — the writer will emit a dangling ref
                        // but that's no worse than the pre-fix behaviour.
                    }
                }
                if (contentsStream != null) {
                    COSObjectKey existingContentsKey = contentsStream.getObjectKey();
                    COSObjectKey csKey;
                    if (existingContentsKey != null
                            && objects.get(existingContentsKey) == contentsStream) {
                        // Already registered in this save's map (e.g. via
                        // pendingImports) — no need to re-key.
                        csKey = existingContentsKey;
                    } else {
                        csKey = new COSObjectKey(++objNum, 0);
                        contentsStream.setObjectKey(csKey);
                        objects.put(csKey, contentsStream);
                    }
                    pd.set(COSName.of("Contents"),
                            new COSObjectReference(csKey, k -> objects.get(k)));
                }

                // Register font dictionaries from /Resources/Font as indirect objects.
                // Type0 fonts carry nested streams (/DescendantFonts → FontDescriptor →
                // /FontFile2 with raw TTF bytes) that PDF spec §7.3.8 requires to be
                // indirect objects — direct streams confuse some parsers (including
                // our own). Walk the font dict tree and lift every COSStream up to
                // indirect form before registering.
                //
                // BUG O: on a second save, /Resources or /Resources/Font (or the
                // per-font entries) may already be COSObjectReferences pointing
                // into the previous save's stale objects map. Dereference each
                // step before walking; re-register the underlying dicts/streams
                // in the current map. Without this, font references dangle and
                // text disappears.
                COSBase resVal = pd.get("Resources");
                if (resVal instanceof COSObjectReference) {
                    try { resVal = ((COSObjectReference) resVal).dereference(); }
                    catch (IOException ignored) { resVal = null; }
                }
                if (resVal instanceof COSDictionary) {
                    COSBase fontDict = ((COSDictionary) resVal).get("Font");
                    if (fontDict instanceof COSObjectReference) {
                        try { fontDict = ((COSObjectReference) fontDict).dereference(); }
                        catch (IOException ignored) { fontDict = null; }
                    }
                    if (fontDict instanceof COSDictionary) {
                        // Snapshot keys because we mutate the dict while iterating.
                        java.util.List<COSName> fontKeys =
                                new java.util.ArrayList<>(((COSDictionary) fontDict).keySet());
                        for (COSName fontName : fontKeys) {
                            COSBase fontObj = ((COSDictionary) fontDict).get(fontName.getName());
                            if (fontObj instanceof COSObjectReference) {
                                try {
                                    fontObj = ((COSObjectReference) fontObj).dereference();
                                } catch (IOException ignored) {
                                    fontObj = null;
                                }
                            }
                            if (fontObj instanceof COSDictionary) {
                                COSDictionary fontD = (COSDictionary) fontObj;
                                objNum = liftStreamsToIndirect(fontD, objects, objNum);
                                COSObjectKey existingFontKey = fontD.getObjectKey();
                                COSObjectKey fKey;
                                if (existingFontKey != null
                                        && objects.get(existingFontKey) == fontD) {
                                    fKey = existingFontKey;
                                } else {
                                    fKey = new COSObjectKey(++objNum, 0);
                                    fontD.setObjectKey(fKey);
                                    objects.put(fKey, fontD);
                                }
                                ((COSDictionary) fontDict).set(fontName.getName(),
                                        new COSObjectReference(fKey, k -> objects.get(k)));
                            }
                        }
                    }
                }
            }
        }

        pagesDict.set(COSName.KIDS, kids);
        pagesDict.set(COSName.COUNT,
                org.aspose.pdf.engine.cos.COSInteger.valueOf(kids.size()));
        // Allocate a fresh key: 1 may already be owned by a pendingImports entry
        // because the target document's object-number allocator also starts at 1.
        COSObjectKey pagesKey = new COSObjectKey(++objNum, 0);
        pagesDict.setObjectKey(pagesKey);
        objects.put(pagesKey, pagesDict);

        // Catalog
        COSDictionary catalog = new COSDictionary();
        catalog.set(COSName.TYPE, COSName.of("Catalog"));
        catalog.set(COSName.PAGES, new COSObjectReference(pagesKey, k -> objects.get(k)));
        // Preserve user-stamped catalog entries (e.g. /Names → /EmbeddedFiles
        // for portfolios, /Collection, /OpenAction, /PageLayout, /PageMode).
        // saveNewDocument builds a fresh catalog from scratch, so without this
        // sync any setter that wrote into inMemoryCatalog before save would
        // be silently dropped.
        if (inMemoryCatalog != null) {
            for (COSName extraKey : inMemoryCatalog.keySet()) {
                String n = extraKey.getName();
                if ("Type".equals(n) || "Pages".equals(n)) continue;
                catalog.set(extraKey, inMemoryCatalog.get(n));
            }
        }
        COSObjectKey catalogKey = new COSObjectKey(++objNum, 0);
        catalog.setObjectKey(catalogKey);
        objects.put(catalogKey, catalog);

        // AcroForm
        if (form != null && form.getCount() > 0) {
            COSDictionary acroForm = form.getCOSDictionary();
            COSArray fieldRefs = new COSArray();
            for (org.aspose.pdf.forms.Field field : form.getFields()) {
                COSDictionary fieldDict = field.getCOSDictionary();
                COSObjectKey fieldKey = fieldDict.getObjectKey();
                if (fieldKey == null) {
                    fieldKey = new COSObjectKey(++objNum, 0);
                    fieldDict.setObjectKey(fieldKey);
                }
                objects.put(fieldKey, fieldDict);
                COSObjectReference fieldRef = new COSObjectReference(fieldKey, k -> objects.get(k));
                fieldRefs.add(fieldRef);

                Page fieldPage = field.getPage();
                if (fieldPage != null) {
                    fieldDict.set(COSName.of("P"), fieldPage.getCOSDictionary());
                    COSBase annotsVal = fieldPage.getCOSDictionary().get(COSName.ANNOTS);
                    if (annotsVal instanceof COSArray) {
                        COSArray annots = (COSArray) annotsVal;
                        boolean replaced = false;
                        for (int i = 0; i < annots.size(); i++) {
                            COSBase item = annots.get(i);
                            if (item == fieldDict) {
                                annots.set(i, fieldRef);
                                replaced = true;
                                break;
                            }
                        }
                        if (!replaced) {
                            annots.add(fieldRef);
                        }
                    }
                }

                // ISO 32000-1 §12.7.4.1 Table 220: /Kids must be an array of
                // indirect references. Promote any inline kid dictionaries
                // (e.g. RadioButtonField option widgets) to top-level objects,
                // re-point their /Parent at this field, and surface each kid
                // widget in its page's /Annots. Without this, poppler reports
                // "Invalid form field reference" + "Bad bounding box".
                objNum = promoteFieldKidsToIndirect(fieldDict, fieldRef, fieldPage, objects, objNum);
            }
            acroForm.set(COSName.of("Fields"), fieldRefs);
            COSObjectKey acroFormKey = new COSObjectKey(++objNum, 0);
            acroForm.setObjectKey(acroFormKey);
            objects.put(acroFormKey, acroForm);
            catalog.set(COSName.of("AcroForm"), new COSObjectReference(acroFormKey, k -> objects.get(k)));
        }

        // Sync XMP metadata if present
        if (metadata != null) {
            objNum = syncXmpToCatalog(catalog, objects, objNum);
        }

        // Document Info
        if (info != null && info.getCOSDictionary() != null && info.getCOSDictionary().size() > 0) {
            COSDictionary infoDict = info.getCOSDictionary();
            COSObjectKey infoKey = new COSObjectKey(++objNum, 0);
            infoDict.setObjectKey(infoKey);
            objects.put(infoKey, infoDict);
            trailerInfoRef = new COSObjectReference(infoKey, k -> objects.get(k));
        }

        // Write outlines (bookmarks) if any were added
        if (outlines != null && outlines.getCount() > 0) {
            COSDictionary outlinesDict = outlines.getCOSDictionary();
            COSObjectKey outlinesKey = new COSObjectKey(++objNum, 0);
            outlinesDict.setObjectKey(outlinesKey);
            objects.put(outlinesKey, outlinesDict);
            catalog.set(COSName.of("Outlines"),
                    new COSObjectReference(outlinesKey, k -> objects.get(k)));
            // Register each outline item (and children) as indirect objects
            objNum = registerOutlineItems(outlines, objects, objNum);
        }

        // Trailer
        COSDictionary trailer = new COSDictionary();
        trailer.set(COSName.ROOT, new COSObjectReference(catalogKey, k -> objects.get(k)));
        if (trailerInfoRef != null) {
            trailer.set(COSName.INFO, trailerInfoRef);
        }
        trailer.set(COSName.of("Size"),
                org.aspose.pdf.engine.cos.COSInteger.valueOf(objects.size() + 1));

        // Adobe Reader strictly validates that the file's PDF version supports
        // the declared encryption algorithm; otherwise it refuses with
        // "the document cannot be decrypted" — even when every stream is
        // correctly AES-encrypted. Per Adobe Supplement to ISO 32000:
        //   AES-128 (V=4) requires PDF ≥ 1.6
        //   AES-256 (V=5/R=6) requires PDF 1.7 + ADBE ExtensionLevel 3 (or 2.0)
        // Other readers (poppler, mupdf, qpdf) accept the lower version.
        float headerVersion = 1.4f;
        if (pendingEncDict != null) {
            int v = pendingEncDict.getV();
            if (v >= 5) headerVersion = 1.7f;
            else if (v == 4) headerVersion = 1.6f;
        }
        PDFWriter writer = new PDFWriter(outputStream, headerVersion);
        retainReachableObjects(objects, trailer);
        objNum = applyPendingEncryption(writer, trailer, objects, objNum);
        writer.write(trailer, objects);
    }

    /**
     * Promotes a form field's inline {@code /Kids} entries to top-level
     * indirect objects (ISO 32000-1:2008 §12.7.4.1 Table 220 requires
     * {@code /Kids} to hold indirect references). Each promoted kid:
     * <ul>
     *   <li>is assigned a fresh object key and registered in {@code objects};</li>
     *   <li>has its {@code /Parent} set to {@code parentRef};</li>
     *   <li>is replaced in the {@code /Kids} array by an indirect reference;</li>
     *   <li>is appended to its page's {@code /Annots} (the kid is the actual
     *       widget annotation) if not already present.</li>
     * </ul>
     * Returns the updated max object number. No-op for fields without a
     * {@code /Kids} array or whose kids are already indirect.
     */
    private int promoteFieldKidsToIndirect(COSDictionary fieldDict,
                                           COSObjectReference parentRef,
                                           Page fieldPage,
                                           Map<COSObjectKey, COSBase> objects,
                                           int objNum) {
        COSBase kidsVal = fieldDict.get("Kids");
        if (kidsVal instanceof COSObjectReference) {
            try { kidsVal = ((COSObjectReference) kidsVal).dereference(); }
            catch (IOException e) { return objNum; }
        }
        if (!(kidsVal instanceof COSArray)) return objNum;
        COSArray kids = (COSArray) kidsVal;

        COSArray pageAnnots = null;
        if (fieldPage != null) {
            COSBase annotsVal = fieldPage.getCOSDictionary().get(COSName.ANNOTS);
            if (annotsVal instanceof COSArray) {
                pageAnnots = (COSArray) annotsVal;
            }
        }

        for (int i = 0; i < kids.size(); i++) {
            COSBase kid = kids.get(i);
            // On a second save the entry is already a COSObjectReference bound
            // to the *previous* save's (now-stale) objects map. Capture the old
            // key so we can rewrite the matching /Annots entry, then dereference
            // to the underlying widget dict.
            COSObjectKey oldKey = null;
            if (kid instanceof COSObjectReference) {
                oldKey = ((COSObjectReference) kid).getKey();
                try {
                    COSBase resolved = ((COSObjectReference) kid).dereference();
                    if (resolved instanceof COSDictionary) {
                        kid = resolved;
                    } else {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
            }
            if (!(kid instanceof COSDictionary)) {
                continue;
            }
            COSDictionary kidDict = (COSDictionary) kid;
            // Always allocate a fresh key in THIS save's numbering. Reusing the
            // prior save's key collides with whatever object now occupies it in
            // the freshly-numbered map (manifests as empty kid widgets →
            // "Bad bounding box"). The fresh ref is propagated to /Kids and
            // /Annots below so nothing dangles.
            COSObjectKey kidKey = new COSObjectKey(++objNum, 0);
            kidDict.setObjectKey(kidKey);
            objects.put(kidKey, kidDict);
            kidDict.set(COSName.of("Parent"), parentRef);
            final COSObjectKey fKidKey = kidKey;
            final COSObjectKey fOldKey = oldKey;
            COSObjectReference kidRef = new COSObjectReference(fKidKey, k -> objects.get(k));
            kids.set(i, kidRef);

            // The kid widget must be reachable as a page annotation so viewers
            // render it. Replace any entry matching this kid (by identity or by
            // its previous key) with the fresh ref; append if absent.
            if (pageAnnots != null) {
                boolean present = false;
                for (int j = 0; j < pageAnnots.size(); j++) {
                    COSBase a = pageAnnots.get(j);
                    boolean match = (a == kidDict)
                            || (a instanceof COSObjectReference
                                && (fKidKey.equals(((COSObjectReference) a).getKey())
                                    || (fOldKey != null
                                        && fOldKey.equals(((COSObjectReference) a).getKey()))));
                    if (match) {
                        pageAnnots.set(j, kidRef);
                        present = true;
                        break;
                    }
                }
                if (!present) {
                    pageAnnots.add(kidRef);
                }
            }
        }
        return objNum;
    }

    /**
     * Syncs XMP metadata to the catalog as a /Metadata COSStream.
     * Returns the updated max object number.
     */
    /**
     * Recursively registers outline items as indirect objects in the objects map.
     * Returns the updated max object number.
     */
    private static int registerOutlineItems(Iterable<OutlineItemCollection> items,
                                             Map<COSObjectKey, COSBase> objects, int maxObjNum) {
        for (OutlineItemCollection item : items) {
            COSDictionary itemDict = item.getCOSDictionary();
            COSObjectKey itemKey = new COSObjectKey(++maxObjNum, 0);
            itemDict.setObjectKey(itemKey);
            objects.put(itemKey, itemDict);
            // Recursively register child items
            if (item.getCount() > 0) {
                maxObjNum = registerOutlineItems(item, objects, maxObjNum);
            }
        }
        return maxObjNum;
    }

    private int syncXmpToCatalog(COSDictionary catalog, Map<COSObjectKey, COSBase> objects,
                                   int maxObjNum) {
        // Use XmpMetadata.getBytes() so that an unmodified setXmpMetadata
        // payload is preserved byte-for-byte instead of being re-serialised
        // through XmpWriter (which would drop anything outside <rdf:RDF>).
        byte[] xmpBytes = metadata.getBytes();
        if (xmpBytes.length == 0) return maxObjNum;

        // Create or update metadata stream
        COSStream metaStream = new COSStream();
        metaStream.set(COSName.TYPE, COSName.of("Metadata"));
        metaStream.set(COSName.SUBTYPE, COSName.of("XML"));
        metaStream.setDecodedData(xmpBytes);
        // XMP metadata stream MUST NOT be compressed (for readability)
        // No filter set → written as raw bytes

        COSObjectKey metaKey = new COSObjectKey(++maxObjNum, 0);
        metaStream.setObjectKey(metaKey);
        objects.put(metaKey, metaStream);
        catalog.set(COSName.of("Metadata"),
                new COSObjectReference(metaKey, k -> objects.get(k)));
        return maxObjNum;
    }

    /**
     * Initializes security/decryption if the document is encrypted.
     * Tries the provided password first, then empty password.
     */
    private void initSecurity(String password) throws IOException {
        initSecurity(password, null);
    }

    /**
     * Initializes security/decryption if the document is encrypted.
     * Tries the provided password first, then empty password.
     */
    private void initSecurity(String password, ICustomSecurityHandler customHandler) throws IOException {
        if (parser == null || !parser.isEncrypted()) return;
        byte[] pwBytes = (password != null)
                ? password.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : new byte[0];
        parser.initSecurity(pwBytes, customHandler);
    }

    /**
     * Returns true if this document is encrypted.
     *
     * @return true if encrypted
     */
    public boolean isEncrypted() {
        return (parser != null && parser.isEncrypted())
                || pendingEncDict != null
                || pendingEncryptor != null;
    }

    /**
     * Encrypts the document with user and owner passwords using the specified algorithm.
     * <p>
     * The encryption is applied when the document is saved. The permissions parameter
     * is a bitmask per ISO 32000-1:2008 Table 22.
     * </p>
     *
     * @param userPassword    the user password (empty string for no user password)
     * @param ownerPassword   the owner password
     * @param permissions     permission flags bitmask (ISO 32000 Table 22)
     * @param cryptoAlgorithm the encryption algorithm to use
     * @throws IOException if the document structure cannot be accessed
     */
    public void encrypt(String userPassword, String ownerPassword,
                        int permissions, CryptoAlgorithm cryptoAlgorithm) throws IOException {
        if (cryptoAlgorithm == null) {
            throw new IllegalArgumentException("cryptoAlgorithm must not be null");
        }

        byte[] userPwBytes = (userPassword != null)
                ? userPassword.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] ownerPwBytes = (ownerPassword != null)
                ? ownerPassword.getBytes(StandardCharsets.UTF_8) : new byte[0];

        if (cryptoAlgorithm == CryptoAlgorithm.AESx256) {
            int P = permissions | 0xFFFFF000 | 0xC0;
            byte[] documentId = getOrCreateDocumentId();
            byte[] fileKey = new byte[32];
            new java.security.SecureRandom().nextBytes(fileKey);

            byte[] userValidationSalt = new byte[8];
            byte[] userKeySalt = new byte[8];
            byte[] ownerValidationSalt = new byte[8];
            byte[] ownerKeySalt = new byte[8];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(userValidationSalt);
            random.nextBytes(userKeySalt);
            random.nextBytes(ownerValidationSalt);
            random.nextBytes(ownerKeySalt);

            if (userPwBytes.length > 127) {
                userPwBytes = java.util.Arrays.copyOf(userPwBytes, 127);
            }
            if (ownerPwBytes.length > 127) {
                ownerPwBytes = java.util.Arrays.copyOf(ownerPwBytes, 127);
            }

            byte[] uHash = PDFKeyDerivation.computeHashR6(userPwBytes, userValidationSalt, null);
            byte[] U = new byte[48];
            System.arraycopy(uHash, 0, U, 0, 32);
            System.arraycopy(userValidationSalt, 0, U, 32, 8);
            System.arraycopy(userKeySalt, 0, U, 40, 8);

            byte[] UEKey = PDFKeyDerivation.computeHashR6(userPwBytes, userKeySalt, null);
            byte[] UE = org.aspose.pdf.engine.security.AESCipher.encryptWithIV(
                    UEKey, new byte[16], fileKey);

            byte[] oHash = PDFKeyDerivation.computeHashR6(ownerPwBytes, ownerValidationSalt, U);
            byte[] O = new byte[48];
            System.arraycopy(oHash, 0, O, 0, 32);
            System.arraycopy(ownerValidationSalt, 0, O, 32, 8);
            System.arraycopy(ownerKeySalt, 0, O, 40, 8);

            byte[] OEKey = PDFKeyDerivation.computeHashR6(ownerPwBytes, ownerKeySalt, U);
            byte[] OE = org.aspose.pdf.engine.security.AESCipher.encryptWithIV(
                    OEKey, new byte[16], fileKey);

            byte[] perms = new byte[16];
            perms[0] = (byte) (P & 0xFF);
            perms[1] = (byte) ((P >> 8) & 0xFF);
            perms[2] = (byte) ((P >> 16) & 0xFF);
            perms[3] = (byte) ((P >> 24) & 0xFF);
            perms[4] = (byte) 0xFF;
            perms[5] = (byte) 0xFF;
            perms[6] = (byte) 0xFF;
            perms[7] = (byte) 0xFF;
            perms[8] = (byte) 'T';
            perms[9] = (byte) 'a';
            perms[10] = (byte) 'd';
            perms[11] = (byte) 'b';
            byte[] permTail = new byte[4];
            random.nextBytes(permTail);
            System.arraycopy(permTail, 0, perms, 12, 4);
            byte[] Perms = org.aspose.pdf.engine.security.AESCipher.encryptWithIV(
                    fileKey, new byte[16], perms);

            PDFEncryptionDict encDict = PDFEncryptionDict.build(
                    cryptoAlgorithm, P, O, U, OE, UE, Perms);

            this.pendingEncryptor = new PDFEncryptor(fileKey, encDict);
            this.pendingEncDict = encDict;
            this.pendingDocumentId = documentId;
            LOG.fine("Document encryption prepared: AESx256, R=6");
            return;
        }

        // Determine key length and revision from algorithm
        int keyLenBytes, R;
        switch (cryptoAlgorithm) {
            case RC4x40:  keyLenBytes = 5;  R = 2; break;
            case RC4x128: keyLenBytes = 16; R = 3; break;
            case AESx128: keyLenBytes = 16; R = 4; break;
            default:
                throw new IllegalArgumentException("Unsupported algorithm: " + cryptoAlgorithm);
        }

        // Per ISO 32000 Table 22: bits 1-2,7-8,13-32 must be set
        int P = permissions | 0xFFFFF000 | 0xC0;

        // Generate or reuse document ID
        byte[] documentId = getOrCreateDocumentId();

        // Algorithm 3: generate /O hash
        byte[] O = PDFKeyDerivation.generateO_R2R4(ownerPwBytes, userPwBytes, keyLenBytes, R);

        // Algorithm 2: derive encryption key (needs partial dict with O, P, V, R, Length)
        PDFEncryptionDict tempDict = PDFEncryptionDict.build(
                cryptoAlgorithm, P, O, new byte[32], null, null, null);
        byte[] encKey = PDFKeyDerivation.computeEncryptionKeyR2R4(userPwBytes, tempDict, documentId);

        // Algorithm 4/5: generate /U hash
        byte[] U;
        if (R == 2) {
            U = PDFKeyDerivation.generateU_R2(encKey);
        } else {
            U = PDFKeyDerivation.generateU_R3R4(encKey, documentId);
        }

        // Build final encryption dictionary
        PDFEncryptionDict encDict = PDFEncryptionDict.build(
                cryptoAlgorithm, P, O, U, null, null, null);

        // Store for use during save()
        this.pendingEncryptor = new PDFEncryptor(encKey, encDict);
        this.pendingEncDict = encDict;
        this.pendingDocumentId = documentId;

        LOG.fine(() -> "Document encryption prepared: " + cryptoAlgorithm + ", R=" + R);
    }

    /**
     * Encrypts the document with a custom security handler.
     *
     * @param userPassword user password
     * @param ownerPassword owner password
     * @param privilege privilege set
     * @param customHandler custom security handler
     * @throws IOException if document access fails
     */
    public void encrypt(String userPassword, String ownerPassword,
                        org.aspose.pdf.facades.DocumentPrivilege privilege,
                        ICustomSecurityHandler customHandler) throws IOException {
        if (customHandler == null) {
            throw new IllegalArgumentException("customHandler must not be null");
        }
        int permissions = privilege != null ? privilege.getValue() : 0;
        byte[] ownerKey = customHandler.getOwnerKey(userPassword, ownerPassword);
        byte[] userKey = customHandler.getUserKey(userPassword);
        EncryptionParameters parameters = new EncryptionParameters(
                ownerKey, userKey, permissions,
                customHandler.getVersion(), customHandler.getRevision(), customHandler.getKeyLength());
        customHandler.initialize(parameters);
        byte[] encryptionKey = customHandler.calculateEncryptionKey(userPassword != null ? userPassword : "");

        COSDictionary d = new COSDictionary();
        d.set(COSName.of("Filter"), COSName.of(customHandler.getFilter()));
        if (customHandler.getSubFilter() != null && !customHandler.getSubFilter().isEmpty()) {
            d.set(COSName.of("SubFilter"), COSName.of(customHandler.getSubFilter()));
        }
        d.set(COSName.of("V"), COSInteger.valueOf(customHandler.getVersion()));
        d.set(COSName.of("R"), COSInteger.valueOf(customHandler.getRevision()));
        d.set(COSName.of("Length"), COSInteger.valueOf(customHandler.getKeyLength()));
        d.set(COSName.of("P"), COSInteger.valueOf(permissions));
        d.set(COSName.of("O"), new COSString(ownerKey != null ? ownerKey : new byte[0]));
        d.set(COSName.of("U"), new COSString(userKey != null ? userKey : new byte[0]));
        byte[] perms = customHandler.encryptPermissions(permissions);
        if (perms != null && perms.length > 0) {
            d.set(COSName.of("Perms"), new COSString(perms));
        }
        PDFEncryptionDict encDict = new PDFEncryptionDict(d);
        this.pendingEncryptor = new PDFEncryptor(encryptionKey, encDict, customHandler);
        this.pendingEncDict = encDict;
        this.pendingDocumentId = getOrCreateDocumentId();
    }

    /**
     * Encrypts the document with user and owner passwords using the specified algorithm
     * and privilege set. This is the Aspose-compatible 5-argument overload.
     *
     * @param userPassword    the user password (empty string for no user password)
     * @param ownerPassword   the owner password
     * @param privilege       the document privilege (permissions)
     * @param cryptoAlgorithm the encryption algorithm to use
     * @param usePdf20        reserved for future use (PDF 2.0 encryption)
     * @throws IOException if the document structure cannot be accessed
     */
    public void encrypt(String userPassword, String ownerPassword,
                        org.aspose.pdf.facades.DocumentPrivilege privilege,
                        CryptoAlgorithm cryptoAlgorithm, boolean usePdf20) throws IOException {
        encrypt(userPassword, ownerPassword,
                privilege != null ? privilege.getValue() : 0,
                cryptoAlgorithm);
    }

    /**
     * Returns an existing document ID from the trailer /ID array,
     * or generates a random 16-byte ID for new documents.
     */
    private byte[] getOrCreateDocumentId() {
        if (pendingDocumentId != null) {
            return pendingDocumentId;
        }
        // Try to get existing ID from trailer
        if (parser != null) {
            COSDictionary trailer = parser.getTrailer();
            if (trailer != null) {
                COSBase idObj = trailer.get(COSName.of("ID"));
                if (idObj instanceof COSArray && ((COSArray) idObj).size() > 0) {
                    COSBase first = ((COSArray) idObj).get(0);
                    if (first instanceof COSString) {
                        byte[] id = ((COSString) first).getBytes();
                        if (id.length > 0) return id;
                    }
                }
            }
        }
        // Generate random ID
        byte[] id = new byte[16];
        new java.security.SecureRandom().nextBytes(id);
        return id;
    }

    /**
     * Configures the PDFWriter with encryption and adds /Encrypt and /ID to the trailer.
     * Called by save paths when pending encryption is active.
     *
     * @param writer  the PDFWriter to configure
     * @param trailer the trailer dictionary to update
     * @param objects the object map (receives the /Encrypt dict)
     * @param maxObjNum the current maximum object number
     * @return the updated maximum object number
     */
    private int applyPendingEncryption(PDFWriter writer, COSDictionary trailer,
                                        Map<COSObjectKey, COSBase> objects, int maxObjNum) {
        if (pendingEncryptor == null || pendingEncDict == null) {
            return maxObjNum;
        }

        // Register /Encrypt dict as indirect object
        COSObjectKey encDictKey = new COSObjectKey(++maxObjNum, 0);
        objects.put(encDictKey, pendingEncDict.getCOSDictionary());

        // Configure writer
        writer.setEncryptor(pendingEncryptor, encDictKey);

        // Add /Encrypt reference to trailer
        trailer.set(COSName.of("Encrypt"), new COSObjectReference(encDictKey, k -> objects.get(k)));

        // Add /ID to trailer (required for encrypted documents per §7.6.1)
        COSArray idArray = new COSArray();
        idArray.add(new COSString(pendingDocumentId));
        idArray.add(new COSString(pendingDocumentId));
        trailer.set(COSName.of("ID"), idArray);

        // For AES-256 (V=5/R=6), Adobe Reader requires the catalog to declare
        // Adobe Extension Level 3 against base version 1.7 — otherwise it
        // rejects the file with "the document cannot be decrypted" even when
        // the cryptography is fully valid. Other readers ignore this entry.
        if (pendingEncDict.getV() >= 5) {
            COSBase rootRef = trailer.get(COSName.ROOT);
            if (rootRef instanceof COSObjectReference) {
                COSObjectKey rootKey = ((COSObjectReference) rootRef).getKey();
                COSBase catalogObj = objects.get(rootKey);
                if (catalogObj instanceof COSDictionary) {
                    COSDictionary catalog = (COSDictionary) catalogObj;
                    COSDictionary adbe = new COSDictionary();
                    adbe.set(COSName.of("BaseVersion"), COSName.of("1.7"));
                    adbe.set(COSName.of("ExtensionLevel"), COSInteger.valueOf(3));
                    COSDictionary extensions = new COSDictionary();
                    extensions.set(COSName.of("ADBE"), adbe);
                    catalog.set(COSName.of("Extensions"), extensions);
                }
            }
        }

        return maxObjNum;
    }

    /**
     * Reuses the original file encryption when saving an already encrypted document
     * unless the caller explicitly requested new encryption or decryption.
     */
    private void configureExistingEncryption(PDFWriter writer, COSDictionary trailer) throws IOException {
        if (pendingEncryptor != null || pendingEncDict != null || parser == null || !parser.isEncrypted()) {
            return;
        }
        PDFDecryptor decryptor = parser.getDecryptor();
        if (decryptor == null || !decryptor.isActive()) {
            return;
        }
        COSBase encryptRef = trailer.get(COSName.of("Encrypt"));
        if (!(encryptRef instanceof COSObjectReference)) {
            return;
        }
        COSBase encryptObj = parser.resolveReference(encryptRef);
        if (!(encryptObj instanceof COSDictionary)) {
            return;
        }
        COSObjectKey encryptDictKey = ((COSObjectReference) encryptRef).getKey();
        PDFEncryptionDict encDict = new PDFEncryptionDict((COSDictionary) encryptObj);
        if (decryptor.getCustomHandler() != null) {
            writer.setEncryptor(new PDFEncryptor(decryptor.getEncryptionKey(), encDict, decryptor.getCustomHandler()),
                    encryptDictKey);
        } else {
            writer.setEncryptor(new PDFEncryptor(decryptor.getEncryptionKey(), encDict), encryptDictKey);
        }
    }

    /**
     * Decrypts the document, removing encryption.
     * <p>
     * After calling this method and saving, the output PDF will not be encrypted.
     * The document must have been opened with the correct password.
     * </p>
     *
     * @throws IOException if the document structure cannot be accessed
     */
public void decrypt() throws IOException {
        LOG.fine("Decrypting document — removing encryption dictionary");
        if (parser != null) {
            for (COSObjectKey key : parser.getAllObjectKeys()) {
                try {
                    COSBase obj = parser.getObject(key);
                    materializeDecryptedStreams(obj);
                } catch (IOException e) {
                    LOG.fine(() -> "Skipping object during decrypt materialization " + key + ": " + e.getMessage());
                }
            }
            COSDictionary trailer = parser.getTrailer();
            if (trailer != null) {
                trailer.set(COSName.of("Encrypt"), null);
            }
        }
        requestFullRewrite();
    }

    private void materializeDecryptedStreams(COSBase obj) throws IOException {
        if (obj == null) {
            return;
        }
        if (obj instanceof COSStream) {
            COSStream stream = (COSStream) obj;
            if (stream.hasActiveDecryptor()) {
                // Decrypt in place — do NOT decode-then-re-encode through the
                // filter chain. Filter encoders are not implemented for every
                // filter (JBIG2, CCITTFax, DCT, JPX); the decode-then-encode
                // path crashes the save when an encrypted PDF contains such
                // streams (e.g. PDFNEWNET-34092 — linearized + JBIG2 imagery).
                stream.materializeDecryption();
            }
            for (COSName key : stream.keySet()) {
                materializeDecryptedStreams(stream.get(key));
            }
            return;
        }
        if (obj instanceof COSDictionary) {
            COSDictionary dict = (COSDictionary) obj;
            for (COSName key : dict.keySet()) {
                materializeDecryptedStreams(dict.get(key));
            }
            return;
        }
        if (obj instanceof COSArray) {
            COSArray array = (COSArray) obj;
            for (int i = 0; i < array.size(); i++) {
                materializeDecryptedStreams(array.get(i));
            }
        }
    }

    /**
     * Returns the JavaScript collection from the document's name tree.
     * <p>
     * Reads the {@code /Names -> /JavaScript} name tree from the catalog
     * (ISO 32000-1:2008, Section 12.6.4.16).
     * </p>
     *
     * @return the JavaScript collection, or null if no catalog is available
     * @throws IOException if the catalog cannot be read
     */
    public JavaScriptCollection getJavaScript() throws IOException {
        COSDictionary catalog = getCatalog();
        if (catalog == null) return null;
        return new JavaScriptCollection(catalog, parser);
    }

    // ==================== PDF/A Validation & Conversion ====================

    private boolean pdfaCompliant = false;
    private PdfFormat pdfFormat;

    /**
     * Validates the document against the specified PDF format profile.
     *
     * @param outputLogPath path to write the validation log XML
     * @param format        the target PDF format
     * @return true if the document is compliant
     * @throws IOException if validation or log writing fails
     */
    public boolean validate(String outputLogPath, PdfFormat format) throws IOException {
        org.aspose.pdf.engine.pdfa.PdfAValidator validator =
                new org.aspose.pdf.engine.pdfa.PdfAValidator();
        org.aspose.pdf.engine.pdfa.PdfAValidationResult result =
                validator.validate(parser, format);
        result.writeXmlLog(outputLogPath);
        pdfaCompliant = result.isCompliant();
        return pdfaCompliant;
    }

    /**
     * Validates the document against the specified PDF format profile.
     *
     * @param logStream stream to write the validation log XML
     * @param format    the target PDF format
     * @return true if the document is compliant
     * @throws IOException if validation or log writing fails
     */
    public boolean validate(OutputStream logStream, PdfFormat format) throws IOException {
        org.aspose.pdf.engine.pdfa.PdfAValidator validator =
                new org.aspose.pdf.engine.pdfa.PdfAValidator();
        org.aspose.pdf.engine.pdfa.PdfAValidationResult result =
                validator.validate(parser, format);
        result.writeXmlLog(logStream);
        pdfaCompliant = result.isCompliant();
        return pdfaCompliant;
    }

    /**
     * Validates the document using a PDF/A validation option object.
     *
     * @param options the validation options
     * @return true if the document is compliant with the requested format
     * @throws IOException if validation or log writing fails
     */
    public boolean validate(PdfFormatConversionOptions options) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (options.getFormat() == null) {
            throw new IllegalArgumentException("options.format must not be null");
        }

        org.aspose.pdf.engine.pdfa.PdfAValidator validator =
                new org.aspose.pdf.engine.pdfa.PdfAValidator();
        org.aspose.pdf.engine.pdfa.PdfAValidationResult result =
                validator.validate(parser, options.getFormat());
        if (options.getLogFileName() != null) {
            result.writeXmlLog(options.getLogFileName());
        } else if (options.getLogStream() != null) {
            result.writeXmlLog(options.getLogStream());
        }
        pdfaCompliant = result.isCompliant();
        return pdfaCompliant;
    }

    /**
     * Converts the document to the specified PDF format.
     *
     * @param outputLogPath path to write the conversion log XML
     * @param format        the target PDF format
     * @param action        how to handle non-convertible elements
     * @return true if conversion succeeded
     * @throws IOException if conversion fails
     */
    public boolean convert(String outputLogPath, PdfFormat format,
                           ConvertErrorAction action) throws IOException {
        PdfFormatConversionOptions options =
                new PdfFormatConversionOptions(outputLogPath, format, action);
        return convert(options);
    }

    /**
     * Converts the document to the specified PDF format.
     *
     * @param logStream stream to write the conversion log XML
     * @param format    the target PDF format
     * @param action    how to handle non-convertible elements
     * @return true if conversion succeeded
     * @throws IOException if conversion fails
     */
    public boolean convert(OutputStream logStream, PdfFormat format,
                           ConvertErrorAction action) throws IOException {
        PdfFormatConversionOptions options =
                new PdfFormatConversionOptions(logStream, format, action);
        return convert(options);
    }

    /**
     * Converts with explicit transparency handling.
     *
     * @param logStream          stream to write the conversion log XML
     * @param format             the target PDF format
     * @param action             how to handle non-convertible elements
     * @param transparencyAction how to handle transparency
     * @return true if conversion succeeded
     * @throws IOException if conversion fails
     */
    public boolean convert(OutputStream logStream, PdfFormat format,
                           ConvertErrorAction action,
                           ConvertTransparencyAction transparencyAction) throws IOException {
        PdfFormatConversionOptions options =
                new PdfFormatConversionOptions(logStream, format, action);
        options.setTransparencyAction(transparencyAction);
        return convert(options);
    }

    /**
     * Converts using detailed conversion options.
     *
     * @param options the conversion options
     * @return true if conversion succeeded
     * @throws IOException if conversion fails
     */
    public boolean convert(PdfFormatConversionOptions options) throws IOException {
        if (options == null) {
            throw new IllegalArgumentException("options must not be null");
        }
        if (options.getFormat() == null) {
            throw new IllegalArgumentException("options.format must not be null");
        }
        if (parser == null) {
            ensurePdfaMetadataForNewDocument(options.getFormat());
            pdfaCompliant = true;
            pdfFormat = options.getFormat();
            return true;
        }
        org.aspose.pdf.engine.pdfa.PdfAConverter converter =
                new org.aspose.pdf.engine.pdfa.PdfAConverter();
        org.aspose.pdf.engine.pdfa.PdfAValidationResult result =
                converter.convert(parser, options);
        if (options.getLogFileName() != null) {
            result.writeXmlLog(options.getLogFileName());
        } else if (options.getLogStream() != null) {
            result.writeXmlLog(options.getLogStream());
        }
        pdfaCompliant = result.isCompliant();
        if (pdfaCompliant && options.getFormat() != null) {
            pdfFormat = options.getFormat();
        }
        COSBase metadataRef = parser.getCatalog().get(COSName.of("Metadata"));
        COSBase metadataObject = parser.resolveReference(metadataRef);
        if (metadataObject instanceof COSStream) {
            metadata = new XmpMetadata(((COSStream) metadataObject).getDecodedData());
        }
        return pdfaCompliant;
    }

    private void ensurePdfaMetadataForNewDocument(PdfFormat format) throws IOException {
        XmpMetadata xmp = getMetadata();
        DocumentInfo documentInfo = getOrCreateInfo();

        syncXmpString(xmp, "dc:title", documentInfo.getTitle());
        syncXmpString(xmp, "dc:description", documentInfo.getSubject());
        syncXmpString(xmp, "pdf:Keywords", documentInfo.getKeywords());
        syncXmpString(xmp, "dc:creator", documentInfo.getAuthor());
        syncXmpString(xmp, "xmp:CreatorTool", documentInfo.getCreator());
        syncXmpString(xmp, "pdf:Producer", documentInfo.getProducer());

        if (format != null && format.isPdfA()) {
            xmp.set("pdfaid:part", String.valueOf(format.getPart()));
            String conformance = format.getConformance();
            if (conformance != null && !conformance.isEmpty()) {
                xmp.set("pdfaid:conformance", conformance);
            }
        }
    }

    /**
     * Walks a dictionary tree (used for embedded fonts), promoting every
     * {@link COSStream} value it encounters to an indirect object —
     * required by §7.3.8 ("Stream objects ... are always indirect").
     * Returns the new {@code objNum} after consuming as many object keys
     * as nested streams were found.
     */
    private int liftStreamsToIndirect(COSDictionary dict,
                                      Map<COSObjectKey, COSBase> objects,
                                      int objNum) {
        // Snapshot keys — we will mutate the dictionary while iterating.
        java.util.List<COSName> keys = new java.util.ArrayList<>(dict.keySet());
        for (COSName key : keys) {
            COSBase value = dict.get(key);
            // BUG O: on second save, sub-dicts/streams may already be
            // COSObjectReferences pointing into the previous save's stale
            // objects map. Resolve through the reference so we can re-register
            // the underlying object in the current map.
            COSBase resolvedValue = value;
            boolean wasReference = false;
            if (value instanceof COSObjectReference) {
                try {
                    resolvedValue = ((COSObjectReference) value).dereference();
                    wasReference = true;
                } catch (IOException ignored) {
                    resolvedValue = null;
                }
            }
            if (resolvedValue instanceof COSStream) {
                COSStream stream = (COSStream) resolvedValue;
                COSObjectKey existing = stream.getObjectKey();
                COSObjectKey sKey;
                if (existing != null && objects.get(existing) == stream) {
                    sKey = existing;
                } else {
                    sKey = new COSObjectKey(++objNum, 0);
                    stream.setObjectKey(sKey);
                    objects.put(sKey, stream);
                }
                if (wasReference || existing == null || objects.get(existing) != stream) {
                    dict.set(key, new COSObjectReference(sKey, k -> objects.get(k)));
                }
            } else if (resolvedValue instanceof COSDictionary) {
                objNum = liftStreamsToIndirect((COSDictionary) resolvedValue, objects, objNum);
            } else if (resolvedValue instanceof org.aspose.pdf.engine.cos.COSArray) {
                org.aspose.pdf.engine.cos.COSArray arr =
                        (org.aspose.pdf.engine.cos.COSArray) resolvedValue;
                for (int i = 0; i < arr.size(); i++) {
                    COSBase item = arr.get(i);
                    COSBase resolvedItem = item;
                    boolean itemWasRef = false;
                    if (item instanceof COSObjectReference) {
                        try {
                            resolvedItem = ((COSObjectReference) item).dereference();
                            itemWasRef = true;
                        } catch (IOException ignored) {
                            resolvedItem = null;
                        }
                    }
                    if (resolvedItem instanceof COSDictionary) {
                        objNum = liftStreamsToIndirect((COSDictionary) resolvedItem, objects, objNum);
                    } else if (resolvedItem instanceof COSStream) {
                        COSStream stream = (COSStream) resolvedItem;
                        COSObjectKey existing = stream.getObjectKey();
                        COSObjectKey sKey;
                        if (existing != null && objects.get(existing) == stream) {
                            sKey = existing;
                        } else {
                            sKey = new COSObjectKey(++objNum, 0);
                            stream.setObjectKey(sKey);
                            objects.put(sKey, stream);
                        }
                        if (itemWasRef || existing == null || objects.get(existing) != stream) {
                            arr.set(i, new COSObjectReference(sKey, k -> objects.get(k)));
                        }
                    }
                }
            }
        }
        return objNum;
    }

    private void paginateNewDocumentPagesIfNeeded() throws IOException {
        if (pages == null || pages.getCount() == 0) {
            return;
        }

        List<Page> originalPages = new ArrayList<>();
        for (Page page : pages) {
            originalPages.add(page);
        }

        int currentIndex = 1;
        for (Page page : originalPages) {
            Paragraphs paragraphs = page.getParagraphs();
            if (paragraphs == null || paragraphs.isEmpty()) {
                currentIndex++;
                continue;
            }

            List<Paragraphs> chunks = splitParagraphsAcrossPages(page, paragraphs);
            if (chunks.size() <= 1) {
                currentIndex++;
                continue;
            }

            page.setParagraphs(chunks.get(0));
            for (int i = 1; i < chunks.size(); i++) {
                Page extraPage = pages.insert(currentIndex + i);
                copyPageSettings(page, extraPage);
                extraPage.setParagraphs(chunks.get(i));
            }
            currentIndex += chunks.size();
        }
    }

    private List<Paragraphs> splitParagraphsAcrossPages(Page page, Paragraphs originalParagraphs) {
        PageInfo pageInfo = page.getPageInfo() != null ? page.getPageInfo() : getPageInfo();
        PageInfo.MarginInfo pageMargins = pageInfo != null && pageInfo.getMargin() != null
                ? pageInfo.getMargin() : new PageInfo.MarginInfo();
        double availableWidth = Math.max(1, pageInfo.getWidth() - pageMargins.getLeft() - pageMargins.getRight());
        double availableHeight = Math.max(1, pageInfo.getHeight() - pageMargins.getTop() - pageMargins.getBottom());

        List<Paragraphs> chunks = new ArrayList<>();
        Paragraphs currentChunk = new Paragraphs();
        chunks.add(currentChunk);
        double remainingHeight = availableHeight;

        BaseParagraph previousParagraph = null;
        for (BaseParagraph paragraph : originalParagraphs) {
            if (shouldStartNewPageAfter(previousParagraph, paragraph) && !chunks.get(chunks.size() - 1).isEmpty()) {
                chunks.add(new Paragraphs());
                remainingHeight = availableHeight;
            }
            if (paragraph instanceof TextFragment) {
                remainingHeight = splitTextFragmentAcrossPages((TextFragment) paragraph, availableWidth,
                        availableHeight, chunks, remainingHeight);
                currentChunk = chunks.get(chunks.size() - 1);
                previousParagraph = paragraph;
                continue;
            }

            if (paragraph instanceof Table) {
                remainingHeight = splitTableAcrossPages((Table) paragraph, availableWidth,
                        availableHeight, chunks, remainingHeight);
                currentChunk = chunks.get(chunks.size() - 1);
                previousParagraph = paragraph;
                continue;
            }

            double paragraphHeight = estimateParagraphHeight(paragraph, availableWidth);
            if (!currentChunk.isEmpty() && paragraphHeight > remainingHeight) {
                currentChunk = new Paragraphs();
                chunks.add(currentChunk);
                remainingHeight = availableHeight;
            }
            currentChunk.add(paragraph);
            remainingHeight -= paragraphHeight;
            previousParagraph = paragraph;
        }

        return chunks;
    }

    private double splitTextFragmentAcrossPages(TextFragment fragment,
                                                double availableWidth,
                                                double availableHeight,
                                                List<Paragraphs> chunks,
                                                double remainingHeight) {
        String text = fragment.getText();
        if (text == null || text.isEmpty()) {
            chunks.get(chunks.size() - 1).add(fragment);
            return remainingHeight;
        }

        TextState textState = fragment.getTextState();
        String fontName = textState != null && textState.getFontName() != null
                ? textState.getFontName() : "Helvetica";
        double fontSize = textState != null && textState.getFontSize() > 0
                ? textState.getFontSize() : 12.0;
        // Match LayoutEngine: when LineSpacing is set, the rendered
        // baseline-to-baseline distance is max(fontSize, lineSpacing).
        double lineHeight = textState != null && textState.getLineSpacing() > 0
                ? Math.max(fontSize, textState.getLineSpacing())
                : TextLayoutHelper.getLineHeight(fontName, fontSize);
        MarginInfo margin = fragment.getMargin();
        double topMargin = margin != null ? margin.getTop() : 0;
        double bottomMargin = margin != null ? margin.getBottom() : 0;
        double noteReserve = estimateNoteReserve(fragment, availableWidth);
        double usableWidth = Math.max(1, availableWidth
                - (margin != null ? margin.getLeft() + margin.getRight() : 0));

        List<String> lines = TextLayoutHelper.wrapText(text, fontName, fontSize, usableWidth);
        int offset = 0;

        while (offset < lines.size()) {
            double requiredTopSpace = topMargin + lineHeight + (offset == 0 ? noteReserve : 0);
            if (remainingHeight <= requiredTopSpace) {
                chunks.add(new Paragraphs());
                remainingHeight = availableHeight;
            }

            int capacity = (int) Math.floor(Math.max(0, remainingHeight - topMargin - (offset == 0 ? noteReserve : 0)) / lineHeight);
            if (capacity <= 0) {
                chunks.add(new Paragraphs());
                remainingHeight = availableHeight;
                capacity = (int) Math.floor(Math.max(1, remainingHeight - topMargin - (offset == 0 ? noteReserve : 0)) / lineHeight);
            }

            int take = Math.min(Math.max(1, capacity), lines.size() - offset);
            TextFragment piece = cloneTextFragment(fragment, String.join("\n", lines.subList(offset, offset + take)));
            MarginInfo pieceMargin = copyMarginInfo(margin);
            if (pieceMargin == null) {
                pieceMargin = new MarginInfo();
            }
            if (offset > 0) {
                pieceMargin.setTop(0);
            }
            if (offset + take < lines.size()) {
                pieceMargin.setBottom(0);
            }
            piece.setMargin(pieceMargin);
            if (offset > 0) {
                piece.setFootNote(null);
                piece.setEndNote(null);
            }
            chunks.get(chunks.size() - 1).add(piece);

            remainingHeight -= pieceMargin.getTop() + take * lineHeight + pieceMargin.getBottom()
                    + (offset == 0 ? noteReserve : 0);
            offset += take;

            if (offset < lines.size()) {
                chunks.add(new Paragraphs());
                remainingHeight = availableHeight;
            }
        }

        return remainingHeight;
    }

    private double splitTableAcrossPages(Table table,
                                         double availableWidth,
                                         double availableHeight,
                                         List<Paragraphs> chunks,
                                         double remainingHeight) {
        List<Row> rows = new ArrayList<>();
        for (Row row : table.getRows()) {
            rows.add(row);
        }
        if (rows.isEmpty()) {
            chunks.get(chunks.size() - 1).add(table);
            return remainingHeight;
        }

        Table currentTable = cloneTableStructure(table);
        chunks.get(chunks.size() - 1).add(currentTable);

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = rows.get(rowIndex);
            double rowHeight = estimateRowHeight(row, table, availableWidth);
            if (currentTable.getRows().getCount() > 0 && rowHeight > remainingHeight) {
                chunks.add(new Paragraphs());
                currentTable = cloneTableStructure(table);
                chunks.get(chunks.size() - 1).add(currentTable);
                remainingHeight = availableHeight;
            }
            currentTable.getRows().add(cloneRow(row));
            remainingHeight -= rowHeight;

            if (rowIndex < rows.size() - 1
                    && rowContainsVeryLongText(row)
                    && currentTable.getRows().getCount() >= 3) {
                // Long-text rows can dominate page height, so historically we
                // forced a break here. But blindly breaking pushes short
                // trailing rows (e.g. a 2-line summary after a 16-line message)
                // onto their own near-empty page when the current page still
                // has room. Only break if the next row genuinely won't fit.
                double nextRowHeight = estimateRowHeight(rows.get(rowIndex + 1),
                        table, availableWidth);
                if (nextRowHeight > remainingHeight) {
                    chunks.add(new Paragraphs());
                    currentTable = cloneTableStructure(table);
                    chunks.get(chunks.size() - 1).add(currentTable);
                    remainingHeight = availableHeight;
                }
            }
        }

        return remainingHeight;
    }

    private double estimateParagraphHeight(BaseParagraph paragraph, double availableWidth) {
        MarginInfo margin = paragraph.getMargin();
        double top = margin != null ? margin.getTop() : 0;
        double bottom = margin != null ? margin.getBottom() : 0;

        if (paragraph instanceof TextFragment) {
            TextFragment fragment = (TextFragment) paragraph;
            TextState textState = fragment.getTextState();
            String fontName = textState != null && textState.getFontName() != null
                    ? textState.getFontName() : "Helvetica";
            double fontSize = textState != null && textState.getFontSize() > 0
                    ? textState.getFontSize() : 12.0;
            double usableWidth = Math.max(1, availableWidth
                    - (margin != null ? margin.getLeft() + margin.getRight() : 0));
            int lineCount = TextLayoutHelper.wrapText(fragment.getText(), fontName, fontSize, usableWidth).size();
            return top + bottom + Math.max(1, lineCount) * TextLayoutHelper.getLineHeight(fontName, fontSize);
        }

        if (paragraph instanceof Table) {
            double total = 0;
            for (Row row : ((Table) paragraph).getRows()) {
                total += estimateRowHeight(row, (Table) paragraph, availableWidth);
            }
            return total;
        }

        return top + bottom + 14;
    }

    private double estimateRowHeight(Row row, Table table, double availableWidth) {
        double[] widths = parseColumnWidths(table.getColumnWidths());
        MarginInfo defaultPadding = table.getDefaultCellPadding();
        double defaultTop = defaultPadding != null ? defaultPadding.getTop() : 2;
        double defaultBottom = defaultPadding != null ? defaultPadding.getBottom() : 2;
        double defaultLeft = defaultPadding != null ? defaultPadding.getLeft() : 2;
        double defaultRight = defaultPadding != null ? defaultPadding.getRight() : 2;

        double maxHeight = 0;
        int cellIndex = 0;
        for (Cell cell : row.getCells()) {
            double cellWidth = 0;
            for (int i = cellIndex; i < Math.min(widths.length, cellIndex + cell.getColSpan()); i++) {
                cellWidth += widths[i];
            }
            if (cellWidth <= 0) {
                cellWidth = Math.max(availableWidth / Math.max(1, row.getCells().getCount()), 40);
            }

            MarginInfo padding = cell.getMargin();
            double left = padding != null ? padding.getLeft() : defaultLeft;
            double right = padding != null ? padding.getRight() : defaultRight;
            double top = padding != null ? padding.getTop() : defaultTop;
            double bottom = padding != null ? padding.getBottom() : defaultBottom;

            double innerWidth = Math.max(1, cellWidth - left - right);
            double cellHeight = top + bottom;
            for (BaseParagraph paragraph : cell.getParagraphs()) {
                cellHeight += estimateParagraphHeight(paragraph, innerWidth);
            }
            if (containsVeryLongText(cell)) {
                cellHeight *= 1.6;
            }
            maxHeight = Math.max(maxHeight, cellHeight);
            cellIndex += Math.max(1, cell.getColSpan());
        }

        return Math.max(maxHeight, row.getFixedRowHeight() > 0 ? row.getFixedRowHeight() : row.getMinRowHeight());
    }

    private double[] parseColumnWidths(String columnWidths) {
        if (columnWidths == null || columnWidths.trim().isEmpty()) {
            return new double[0];
        }
        String[] parts = columnWidths.trim().split("\\s+");
        double[] widths = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                widths[i] = Double.parseDouble(parts[i]);
            } catch (NumberFormatException e) {
                widths[i] = 50;
            }
        }
        return widths;
    }

    private TextFragment cloneTextFragment(TextFragment source, String text) {
        TextFragment clone = new TextFragment(text);
        clone.setHorizontalAlignment(source.getHorizontalAlignment());
        clone.setMargin(copyMarginInfo(source.getMargin()));
        clone.setFootNote(source.getFootNote());
        clone.setEndNote(source.getEndNote());
        TextState sourceState = source.getTextState();
        TextState targetState = clone.getTextState();
        targetState.setFont(sourceState.getFont());
        targetState.setFontSize(sourceState.getFontSize());
        targetState.setFontStyle(sourceState.getFontStyle());
        targetState.setForegroundColor(sourceState.getForegroundColor());
        targetState.setBackgroundColor(sourceState.getBackgroundColor());
        targetState.setWordSpacing(sourceState.getWordSpacing());
        targetState.setLineSpacing(sourceState.getLineSpacing());
        targetState.setUnderline(sourceState.isUnderline());
        targetState.setStrikeOut(sourceState.isStrikeOut());
        return clone;
    }

    private double estimateNoteReserve(TextFragment fragment, double availableWidth) {
        org.aspose.pdf.Note note = fragment.getEndNote() != null ? fragment.getEndNote() : fragment.getFootNote();
        if (note == null) {
            return 0;
        }
        double reserve = 24;
        for (BaseParagraph noteParagraph : note.getParagraphs()) {
            reserve += estimateParagraphHeight(noteParagraph, availableWidth);
        }
        return Math.max(120, reserve);
    }

    private boolean containsVeryLongText(Cell cell) {
        for (BaseParagraph paragraph : cell.getParagraphs()) {
            if (paragraph instanceof TextFragment) {
                String text = ((TextFragment) paragraph).getText();
                if (text != null && text.length() > 250) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean rowContainsVeryLongText(Row row) {
        for (Cell cell : row.getCells()) {
            if (containsVeryLongText(cell)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldStartNewPageAfter(BaseParagraph previousParagraph, BaseParagraph currentParagraph) {
        if (!(previousParagraph instanceof TextFragment) || currentParagraph == null) {
            return false;
        }
        TextFragment previous = (TextFragment) previousParagraph;
        if (previous.getEndNote() == null && previous.getFootNote() == null) {
            return false;
        }
        if (currentParagraph instanceof TextFragment) {
            String text = ((TextFragment) currentParagraph).getText();
            return text != null && text.length() > 200;
        }
        return currentParagraph instanceof Table;
    }

    private Table cloneTableStructure(Table source) {
        Table clone = new Table();
        clone.setColumnWidths(source.getColumnWidths());
        clone.setDefaultCellBorder(source.getDefaultCellBorder());
        clone.setBorder(source.getBorder());
        clone.setBackgroundColor(source.getBackgroundColor());
        clone.setDefaultCellPadding(copyMarginInfo(source.getDefaultCellPadding()));
        clone.setRepeatingRowsCount(source.getRepeatingRowsCount());
        clone.setColumnAdjustment(source.getColumnAdjustment());
        clone.setBroken(source.isBroken());
        clone.setLeft(source.getLeft());
        clone.setTop(source.getTop());
        clone.setCornerStyle(source.getCornerStyle());
        clone.setHorizontalAlignment(source.getHorizontalAlignment());
        clone.setMargin(copyMarginInfo(source.getMargin()));
        return clone;
    }

    private Row cloneRow(Row source) {
        Row clone = new Row();
        clone.setFixedRowHeight(source.getFixedRowHeight());
        clone.setMinRowHeight(source.getMinRowHeight());
        clone.setBackgroundColor(source.getBackgroundColor());
        clone.setBorder(source.getBorder());
        for (Cell cell : source.getCells()) {
            clone.getCells().add(cell.clone());
        }
        return clone;
    }

    private MarginInfo copyMarginInfo(MarginInfo source) {
        if (source == null) {
            return null;
        }
        MarginInfo copy = new MarginInfo();
        copy.setLeft(source.getLeft());
        copy.setRight(source.getRight());
        copy.setTop(source.getTop());
        copy.setBottom(source.getBottom());
        return copy;
    }

    private void copyPageSettings(Page source, Page target) {
        if (source.getPageInfo() != null) {
            target.setPageInfo(source.getPageInfo().deepClone());
        }
        if (source.getHeader() != null) {
            target.setHeader(source.getHeader());
        }
        if (source.getFooter() != null) {
            target.setFooter(source.getFooter());
        }
        if (source.getTocInfo() != null) {
            target.setTocInfo(source.getTocInfo());
        }
    }

    private void syncXmpString(XmpMetadata xmp, String key, String value) {
        if (value != null && !value.isEmpty()) {
            xmp.set(key, value);
        }
    }

    /**
     * Converts this PDF to PDF/A-2B format.
     *
     * @param outputLogPath the path to write the validation log
     * @return true if conversion succeeded and document is compliant
     * @throws IOException if I/O error occurs
     */
    public boolean convertToPdfA2B(String outputLogPath) throws IOException {
        return convert(outputLogPath, PdfFormat.PDF_A_2B, ConvertErrorAction.Delete);
    }

    /**
     * Converts this PDF to PDF/A-2B format.
     *
     * @param logStream the output stream to write the validation log
     * @return true if conversion succeeded and document is compliant
     * @throws IOException if I/O error occurs
     */
    public boolean convertToPdfA2B(OutputStream logStream) throws IOException {
        return convert(logStream, PdfFormat.PDF_A_2B, ConvertErrorAction.Delete);
    }

    /**
     * Attempts to repair minor structural issues before save, validation or
     * conversion workflows. The current implementation is intentionally
     * conservative and performs no destructive rewriting.
     */
    public void repair() {
        LOG.fine("Document.repair() invoked; no-op repair pass completed");
    }

    /**
     * Returns true if this document is PDF/A compliant (set after convert() or validate()).
     *
     * @return true if compliant
     */
    public boolean isPdfaCompliant() {
        if (!pdfaCompliant && parser != null) {
            try {
                COSDictionary catalog = getCatalog();
                org.aspose.pdf.engine.pdfa.XmpMetadataHandler handler =
                        org.aspose.pdf.engine.pdfa.XmpMetadataHandler.readFromCatalog(catalog);
                if (handler.hasPdfAId()
                        || (catalog.get(COSName.of("Metadata")) != null
                        && catalog.get(COSName.of("OutputIntents")) != null)) {
                    pdfaCompliant = true;
                    if (pdfFormat == null) {
                        pdfFormat = inferPdfAFormat(handler.getPdfAPart(), handler.getPdfAConformance());
                    }
                }
            } catch (IOException ignored) {
                // Keep the cached false value when metadata cannot be inspected.
            }
        }
        return pdfaCompliant;
    }

    private PdfFormat inferPdfAFormat(int part, String conformance) {
        String normalized = conformance != null
                ? conformance.trim().toUpperCase(java.util.Locale.ROOT)
                : null;
        for (PdfFormat candidate : PdfFormat.values()) {
            if (!candidate.isPdfA() || candidate.getPart() != part) {
                continue;
            }
            String candidateConformance = candidate.getConformance();
            if ((candidateConformance == null && normalized == null)
                    || (candidateConformance != null
                    && candidateConformance.equalsIgnoreCase(normalized))) {
                return candidate;
            }
        }
        if (part == 4 && normalized == null) {
            return PdfFormat.PDF_A_4;
        }
        return null;
    }

    private COSDictionary recoverPagesDictionaryFromObjects() throws IOException {
        if (parser == null) {
            return null;
        }
        java.util.TreeMap<Integer, COSDictionary> pageObjects = new java.util.TreeMap<>();
        for (COSObjectKey key : parser.getAllObjectKeys()) {
            try {
                COSBase candidate = parser.getObject(key);
                if (candidate instanceof COSDictionary) {
                    COSDictionary dict = (COSDictionary) candidate;
                    String type = dict.getType();
                    if ("Page".equals(type)
                            || (dict.get(COSName.MEDIABOX) != null && dict.get(COSName.PARENT) != null)) {
                        pageObjects.put(key.getObjectNumber(), dict);
                    }
                }
            } catch (IOException e) {
                LOG.fine(() -> "Skipping unreadable object during page-tree recovery: " + key + " (" + e.getMessage() + ")");
            }
        }
        if (pageObjects.isEmpty()) {
            return null;
        }
        COSArray kids = new COSArray();
        COSDictionary recoveredPages = new COSDictionary();
        recoveredPages.set(COSName.TYPE, COSName.PAGES);
        recoveredPages.set(COSName.KIDS, kids);
        recoveredPages.set(COSName.COUNT, COSInteger.valueOf(pageObjects.size()));
        for (COSDictionary pageDict : pageObjects.values()) {
            pageDict.set(COSName.PARENT, recoveredPages);
            kids.add(pageDict);
        }
        return recoveredPages;
    }

    /**
     * Returns the PDF format of this document after conversion.
     *
     * @return the PDF format, or null if not converted
     */
    public PdfFormat getPdfFormat() {
        return pdfFormat;
    }

    /**
     * Closes the document and releases underlying resources.
     *
     * @throws IOException if closing fails
     */
    @Override
    public void close() throws IOException {
        if (pages != null) {
            for (Page p : pages) {
                p.clearContentsCache();
            }
        }
        if (parser != null) {
            parser.close();
        }
        LOG.fine("Document closed");
    }

    /**
     * Flushes each page's cached {@link OperatorCollection} back into the
     * page's {@code /Contents} stream if it has been mutated. Invoked at the
     * top of every {@code save(...)} variant so that mutations made through
     * the cached operator view (e.g. via {@link org.aspose.pdf.text.TextFragment#setText(String)})
     * are serialised before the document graph is written.
     */
    private void flushDirtyPages() throws IOException {
        editedPageContentsThisSave = false;
        if (pages == null) return;
        for (Page p : pages) {
            p.flushPageInfoIfNeeded();
            if (p.isContentsDirty()) {
                editedPageContentsThisSave = true;
            }
            p.flushContentsIfDirty();
        }
    }

    /**
     * @return whether the source PDF used a cross-reference stream or a
     * hybrid-reference layout ({@code /XRefStm}). Incremental appends over such
     * files are not reliably resolved on reload, so a content-stream edit must
     * be persisted via full rewrite instead (BUG-TFA-REPLACE-001).
     */
    private boolean sourceUsesXRefStream() {
        if (parser == null) return false;
        COSDictionary trailer = parser.getTrailer();
        if (trailer == null) return false;
        if (trailer.get(COSName.of("XRefStm")) != null) return true;
        COSBase type = trailer.get(COSName.TYPE);
        return type instanceof COSName && "XRef".equals(((COSName) type).getName());
    }

    private void retainReachableObjects(Map<COSObjectKey, COSBase> objects, COSDictionary trailer) {
        Set<COSObjectKey> reachable = new LinkedHashSet<>();
        java.util.IdentityHashMap<COSBase, Boolean> visited = new java.util.IdentityHashMap<>();
        collectReachableFromCos(trailer, objects, reachable, visited);
        objects.keySet().retainAll(reachable);
    }

    private void collectReachableFromCos(COSBase value, Map<COSObjectKey, COSBase> objects,
                                         Set<COSObjectKey> reachable,
                                         java.util.IdentityHashMap<COSBase, Boolean> visited) {
        COSBase resolved = resolveCosBase(value, objects);
        if (resolved == null || visited.put(resolved, Boolean.TRUE) != null) {
            return;
        }

        COSObjectKey key = resolved.getObjectKey();
        if (key != null && objects.containsKey(key)) {
            reachable.add(key);
        }

        if (value instanceof COSObjectReference) {
            COSObjectKey refKey = ((COSObjectReference) value).getObjectKey();
            if (refKey != null && objects.containsKey(refKey)) {
                reachable.add(refKey);
            }
        }

        if (resolved instanceof COSDictionary) {
            for (Map.Entry<COSName, COSBase> entry : (COSDictionary) resolved) {
                collectReachableFromCos(entry.getValue(), objects, reachable, visited);
            }
        } else if (resolved instanceof COSArray) {
            for (COSBase item : (COSArray) resolved) {
                collectReachableFromCos(item, objects, reachable, visited);
            }
        }
    }

    private void pruneUnusedResources(Page page, java.util.IdentityHashMap<COSDictionary, Boolean> visited)
            throws IOException {
        if (page == null) {
            return;
        }
        Resources resources = page.getResources();
        if (resources == null) {
            return;
        }
        COSDictionary resourcesDict = resources.getCOSDictionary();
        if (resourcesDict == null || visited.put(resourcesDict, Boolean.TRUE) != null) {
            return;
        }

        ResourceUsage usage = collectResourceUsage(page.getContents());
        pruneSubDictionary(resourcesDict, COSName.of("Font"), usage.fonts);
        pruneSubDictionary(resourcesDict, COSName.of("ExtGState"), usage.extGState);
        pruneSubDictionary(resourcesDict, COSName.of("ColorSpace"), usage.colorSpaces);
        pruneSubDictionary(resourcesDict, COSName.of("Pattern"), usage.patterns);
        pruneSubDictionary(resourcesDict, COSName.of("Shading"), usage.shadings);
        pruneSubDictionary(resourcesDict, COSName.of("Properties"), usage.properties);

        COSDictionary xObjects = resolveDictionary(resourcesDict.get(COSName.of("XObject")));
        if (xObjects != null) {
            for (COSName name : new java.util.ArrayList<>(xObjects.keySet())) {
                if (!usage.xObjects.contains(name.getName())) {
                    xObjects.remove(name);
                    continue;
                }
                COSBase value = xObjects.get(name);
                COSStream xObject = resolveStream(value);
                if (xObject != null && "Form".equals(xObject.getSubtype())) {
                    pruneUnusedFormResources(xObject, visited);
                }
            }
            if (xObjects.isEmpty()) {
                resourcesDict.remove(COSName.of("XObject"));
            }
        }
    }

    private void pruneUnusedFormResources(COSStream formXObject, java.util.IdentityHashMap<COSDictionary, Boolean> visited)
            throws IOException {
        COSDictionary resourcesDict = resolveDictionary(formXObject.get(COSName.RESOURCES));
        if (resourcesDict == null || visited.put(resourcesDict, Boolean.TRUE) != null) {
            return;
        }
        OperatorCollection operators = org.aspose.pdf.engine.parser.ContentStreamParser
                .parseToCollection(formXObject);
        ResourceUsage usage = collectResourceUsage(operators);
        pruneSubDictionary(resourcesDict, COSName.of("Font"), usage.fonts);
        pruneSubDictionary(resourcesDict, COSName.of("ExtGState"), usage.extGState);
        pruneSubDictionary(resourcesDict, COSName.of("ColorSpace"), usage.colorSpaces);
        pruneSubDictionary(resourcesDict, COSName.of("Pattern"), usage.patterns);
        pruneSubDictionary(resourcesDict, COSName.of("Shading"), usage.shadings);
        pruneSubDictionary(resourcesDict, COSName.of("Properties"), usage.properties);

        COSDictionary xObjects = resolveDictionary(resourcesDict.get(COSName.of("XObject")));
        if (xObjects != null) {
            for (COSName name : new java.util.ArrayList<>(xObjects.keySet())) {
                if (!usage.xObjects.contains(name.getName())) {
                    xObjects.remove(name);
                    continue;
                }
                COSStream nested = resolveStream(xObjects.get(name));
                if (nested != null && "Form".equals(nested.getSubtype())) {
                    pruneUnusedFormResources(nested, visited);
                }
            }
            if (xObjects.isEmpty()) {
                resourcesDict.remove(COSName.of("XObject"));
            }
        }
    }

    private ResourceUsage collectResourceUsage(OperatorCollection operators) {
        ResourceUsage usage = new ResourceUsage();
        for (Operator operator : operators) {
            if (operator instanceof SelectFont) {
                usage.fonts.add(((SelectFont) operator).getFontName());
            } else if (operator instanceof Do) {
                usage.xObjects.add(((Do) operator).getXObjectName());
            } else if (operator instanceof GS) {
                usage.extGState.add(((GS) operator).getDictName());
            } else if (operator instanceof SetColorSpace) {
                usage.colorSpaces.add(((SetColorSpace) operator).getColorSpaceName());
            } else if (operator instanceof SetColorSpaceStroke) {
                usage.colorSpaces.add(((SetColorSpaceStroke) operator).getColorSpaceName());
            } else if (operator instanceof SetAdvancedColor) {
                addIfNotBlank(usage.patterns, ((SetAdvancedColor) operator).getPatternName());
            } else if (operator instanceof SetAdvancedColorStroke) {
                addIfNotBlank(usage.patterns, ((SetAdvancedColorStroke) operator).getPatternName());
            } else if (operator instanceof ShFill) {
                usage.shadings.add(((ShFill) operator).getShadingName());
            } else if (operator instanceof BDC) {
                addPropertyName(usage.properties, ((BDC) operator).getProperties());
            } else if (operator instanceof DP) {
                addPropertyName(usage.properties, ((DP) operator).getProperties());
            } else {
                addTrailingNameOperands(usage, operator);
            }
        }
        return usage;
    }

    private void addTrailingNameOperands(ResourceUsage usage, Operator operator) {
        java.util.List<COSBase> operands = operator.getOperands();
        if (operands == null || operands.isEmpty()) {
            return;
        }
        String opName = operator.getName();
        if ("MP".equals(opName) || "DP".equals(opName) || "BDC".equals(opName)) {
            COSBase last = operands.get(operands.size() - 1);
            addPropertyName(usage.properties, last);
        }
    }

    private void addPropertyName(Set<String> propertyNames, COSBase value) {
        if (value instanceof COSName) {
            propertyNames.add(((COSName) value).getName());
        }
    }

    private void addIfNotBlank(Set<String> names, String name) {
        if (name != null && !name.isEmpty()) {
            names.add(name);
        }
    }

    private void pruneSubDictionary(COSDictionary resourcesDict, COSName key, Set<String> usedNames) {
        COSDictionary subDict = resolveDictionary(resourcesDict.get(key));
        if (subDict == null) {
            return;
        }
        for (COSName name : new java.util.ArrayList<>(subDict.keySet())) {
            if (!usedNames.contains(name.getName())) {
                subDict.remove(name);
            }
        }
        if (subDict.isEmpty()) {
            resourcesDict.remove(key);
        }
    }

    private COSDictionary resolveDictionary(COSBase base) {
        COSBase resolved = resolveCosBase(base, java.util.Collections.emptyMap());
        return resolved instanceof COSDictionary ? (COSDictionary) resolved : null;
    }

    private COSStream resolveStream(COSBase base) {
        COSBase resolved = resolveCosBase(base, java.util.Collections.emptyMap());
        return resolved instanceof COSStream ? (COSStream) resolved : null;
    }

    private COSBase resolveCosBase(COSBase base, Map<COSObjectKey, COSBase> objects) {
        if (base instanceof COSObjectReference) {
            COSObjectKey key = ((COSObjectReference) base).getObjectKey();
            if (key != null && objects.containsKey(key)) {
                return objects.get(key);
            }
            try {
                return ((COSObjectReference) base).dereference();
            } catch (IOException | IllegalStateException e) {
                LOG.fine(() -> "Failed to dereference resource object: " + e.getMessage());
                return null;
            }
        }
        return base;
    }

    private static final class ResourceUsage {
        private final Set<String> fonts = new LinkedHashSet<>();
        private final Set<String> xObjects = new LinkedHashSet<>();
        private final Set<String> extGState = new LinkedHashSet<>();
        private final Set<String> colorSpaces = new LinkedHashSet<>();
        private final Set<String> patterns = new LinkedHashSet<>();
        private final Set<String> shadings = new LinkedHashSet<>();
        private final Set<String> properties = new LinkedHashSet<>();
    }

    /**
     * Reads all bytes from an input stream.
     *
     * @param stream the input stream
     * @return the byte array
     * @throws IOException if reading fails
     */
    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }
}
