package org.aspose.pdf;

import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfNull;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
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

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
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
    private PdfDictionary inMemoryCatalog; // for empty documents created with Document()
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
    private final Map<PdfObjectKey, PdfBase> pendingImports = new LinkedHashMap<>();
    private NamedDestinations namedDestinations;
    private TaggedContent taggedContent;
    private PageInfo pageInfo;
    private String fileName;
    private FontUtilities fontUtilities;
    private boolean embedStandardFonts;
    private boolean optimizeRequested;
    /**
     * Whether the pending optimize rewrite may pack objects into compressed
     * object streams (/ObjStm). Set by {@link #optimizeResources} only when
     * {@code OptimizationOptions.CompressObjects} is requested; Aspose keeps
     * every object as a plain indirect object otherwise, which regressions rely
     * on (PDFNET-39575 greps the output for "/Separation" and "N 0 obj").
     */
    private boolean optimizeUseObjectStreams;
    private boolean optimizeSize;
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
        PdfDictionary catalog = new PdfDictionary();
        catalog.set(PdfName.TYPE, PdfName.of("Catalog"));
        PdfDictionary pagesDict = new PdfDictionary();
        pagesDict.set(PdfName.TYPE, PdfName.PAGES);
        pagesDict.set(PdfName.KIDS, new org.aspose.pdf.engine.pdfobjects.PdfArray());
        pagesDict.set(PdfName.COUNT, org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(0));
        catalog.set(PdfName.PAGES, pagesDict);
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
        initFromHtmlFile(filePath, options, false);
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
        initFromHtmlStream(stream, options, false);
    }

    /**
     * Loads a document from a file, converting it to PDF according to the runtime
     * type of {@code options} — the uniform load entry point (Aspose.PDF
     * {@code Document(String, LoadOptions)}). Passing an {@link HtmlLoadOptions}
     * reads and converts the HTML file; the concrete options type selects the
     * source format, mirroring how {@link SaveOptions} selects the format on save.
     *
     * @param filePath the input file path
     * @param options  the load options; their concrete type selects the source
     *                 format
     * @throws IOException if reading or parsing fails
     */
    public Document(String filePath, LoadOptions options) throws IOException {
        this();
        if (options instanceof HtmlLoadOptions) {
            // The uniform entry point uses the SDM pipeline so the document is
            // laid out immediately (Aspose-like), rather than the legacy
            // converter that defers content until save().
            initFromHtmlFile(filePath, (HtmlLoadOptions) options, true);
        } else if (options instanceof DocLoadOptions) {
            initFromDocx(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    (DocLoadOptions) options);
        } else if (options instanceof ExcelLoadOptions) {
            initFromXlsx(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    (ExcelLoadOptions) options);
        } else if (options instanceof MarkdownLoadOptions) {
            initFromMarkdown(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    (MarkdownLoadOptions) options);
        } else {
            throw new IllegalArgumentException("Unsupported LoadOptions type: "
                    + (options == null ? "null" : options.getClass().getName())
                    + ". To open a PDF use new Document(String).");
        }
    }

    /**
     * Loads a document from a stream, converting it to PDF according to the
     * runtime type of {@code options} — the stream counterpart of
     * {@link #Document(String, LoadOptions)} (Aspose.PDF
     * {@code Document(InputStream, LoadOptions)}).
     *
     * @param stream  the input stream
     * @param options the load options; their concrete type selects the source
     *                format
     * @throws IOException if reading or parsing fails
     */
    public Document(InputStream stream, LoadOptions options) throws IOException {
        this();
        if (options instanceof HtmlLoadOptions) {
            initFromHtmlStream(stream, (HtmlLoadOptions) options, true);
        } else if (options instanceof DocLoadOptions) {
            initFromDocx(readAllBytes(stream), (DocLoadOptions) options);
        } else if (options instanceof ExcelLoadOptions) {
            initFromXlsx(readAllBytes(stream), (ExcelLoadOptions) options);
        } else if (options instanceof MarkdownLoadOptions) {
            initFromMarkdown(readAllBytes(stream), (MarkdownLoadOptions) options);
        } else {
            throw new IllegalArgumentException("Unsupported LoadOptions type: "
                    + (options == null ? "null" : options.getClass().getName())
                    + ". To open a PDF use new Document(InputStream).");
        }
    }

    /**
     * Loads (converts) a {@code .docx} package: DOCX &rarr; SDM via
     * {@code DocxSdmReader}, then the shared {@code SdmPdfLayout} paginates the
     * model — the same second half the HTML loader uses. An explicit
     * {@link DocLoadOptions#getPageInfo()} fixes the page geometry; otherwise
     * the document's own {@code sectPr} page size is honoured.
     */
    private void initFromDocx(byte[] docx, DocLoadOptions options) throws IOException {
        // Format sniffing: a legacy binary .doc is an OLE2 compound file
        // (D0 CF 11 E0), a .docx is a ZIP (PK). Both load through the same
        // DocLoadOptions — the bytes decide the parser.
        if (docx != null && docx.length > 4 && (docx[0] & 0xFF) == 0xD0
                && (docx[1] & 0xFF) == 0xCF && (docx[2] & 0xFF) == 0x11
                && (docx[3] & 0xFF) == 0xE0) {
            initFromLegacyDoc(docx, options);
            return;
        }
        org.aspose.pdf.sdm.docx.DocxSdmReader reader = new org.aspose.pdf.sdm.docx.DocxSdmReader();
        org.aspose.pdf.sdm.SdmDocument sdm = reader.read(docx);
        PageInfo pi = options != null ? options.getPageInfo() : null;
        org.aspose.pdf.sdm.layout.PageSetup setup = pageSetupFrom(pi);
        if (pi == null) {
            applySdmPageSize(setup, sdm);
        }
        // Word documents state their page size explicitly (sectPr/PageInfo) —
        // wide tables must wrap into it, not stretch the page.
        setup.setFixedSize(true);
        // Running header/footer parts become per-page furniture; the content
        // margins must clear the furniture bands (Word pushes body text below
        // the header the same way).
        double headerH = furnitureInto(reader.getHeaderLines(), setup.getHeaderLines());
        double footerH = furnitureInto(reader.getFooterLines(), setup.getFooterLines());
        if (reader.getHeaderDistance() > 0) {
            setup.setHeaderDistance(reader.getHeaderDistance());
        }
        setup.setFooterDistance(Math.max(14, reader.getFooterDistance()));
        if (headerH > 0) {
            setup.setMarginTop(Math.max(setup.getMarginTop(),
                    setup.getHeaderDistance() + headerH + 6));
        }
        if (footerH > 0) {
            setup.setMarginBottom(Math.max(setup.getMarginBottom(),
                    setup.getFooterDistance() + footerH + 6));
        }
        org.aspose.pdf.sdm.layout.SdmPdfLayout.Result result =
                new org.aspose.pdf.sdm.layout.SdmPdfLayout().render(sdm, setup);
        adoptSdmPages(result.getDocument());
    }

    /**
     * Loads (converts) an {@code .xlsx} spreadsheet: XLSX &rarr; SDM via
     * {@code XlsxSdmReader} (one table per worksheet), then the shared
     * {@code SdmPdfLayout} paginates the model &mdash; the same second half the
     * HTML and DOCX loaders use. An explicit {@link ExcelLoadOptions#getPageInfo()}
     * fixes the page geometry; otherwise a default page is used and wide tables
     * wrap into it.
     */
    private void initFromXlsx(byte[] xlsx, ExcelLoadOptions options) throws IOException {
        boolean interactive = options != null && options.isInteractiveForms();
        org.aspose.pdf.sdm.xlsx.XlsxSdmReader reader = new org.aspose.pdf.sdm.xlsx.XlsxSdmReader();
        reader.setInteractiveForms(interactive);
        org.aspose.pdf.sdm.SdmDocument sdm = reader.read(xlsx);
        PageInfo pi = options != null ? options.getPageInfo() : null;
        org.aspose.pdf.sdm.layout.PageSetup setup = pageSetupFrom(pi);
        // A spreadsheet has its own column widths; keep the page fixed so a wide
        // table wraps/scales into it rather than stretching the sheet.
        setup.setFixedSize(true);
        org.aspose.pdf.sdm.layout.SdmPdfLayout layout = new org.aspose.pdf.sdm.layout.SdmPdfLayout();
        org.aspose.pdf.sdm.layout.SdmPdfLayout.Result result = layout.render(sdm, setup);
        adoptSdmPages(result.getDocument());
        if (interactive) {
            // Create the form fields ONCE on this (adopting) document from the
            // placements recorded during layout — NOT in the layout document.
            // Adopting a document that already has fields imports each widget twice
            // (page import + form re-registration), leaving /AcroForm/Fields pointing
            // at different objects than the /Annots widgets, which makes Acrobat hide
            // values until a field gains focus. One clean creation pass avoids that.
            createLiveFormFields(layout.getXlsxPlacements());
            installLiveFormRuntime(
                    (String) sdm.getAttributes().get("xlsx-doc-js"),
                    asStringList(sdm.getAttributes().get("xlsx-calc-order")));
        }
    }

    /**
     * Loads (converts) a Markdown document: Markdown &rarr; SDM via
     * {@code MarkdownSdmReader}, then the shared {@code SdmPdfLayout} paginates
     * the model &mdash; the same second half the HTML, DOCX and XLSX loaders use.
     * An explicit {@link MarkdownLoadOptions#getPageInfo()} fixes the page
     * geometry; otherwise a default page is used and content reflows into it.
     */
    private void initFromMarkdown(byte[] md, MarkdownLoadOptions options) throws IOException {
        org.aspose.pdf.sdm.markdown.MarkdownSdmReader reader =
                new org.aspose.pdf.sdm.markdown.MarkdownSdmReader();
        org.aspose.pdf.sdm.SdmDocument sdm = reader.read(md);
        PageInfo pi = options != null ? options.getPageInfo() : null;
        org.aspose.pdf.sdm.layout.PageSetup setup = pageSetupFrom(pi);
        // Markdown carries no page geometry; keep the page fixed so wide tables
        // wrap into it rather than stretching the page.
        setup.setFixedSize(true);
        org.aspose.pdf.sdm.layout.SdmPdfLayout.Result result =
                new org.aspose.pdf.sdm.layout.SdmPdfLayout().render(sdm, setup);
        adoptSdmPages(result.getDocument());
    }

    /**
     * Creates one AcroForm text field per recorded placement on this document's
     * already-paginated pages. Each field is a single merged field+widget object
     * referenced from both {@code /AcroForm/Fields} and its page {@code /Annots}.
     */
    private void createLiveFormFields(java.util.List<org.aspose.pdf.sdm.layout.SdmPdfLayout.XlsxFieldPlacement> placements)
            throws IOException {
        if (placements == null || placements.isEmpty()) {
            return;
        }
        Form form = getForm();
        PageCollection pages = getPages();
        int pageCount = pages.getCount();
        for (org.aspose.pdf.sdm.layout.SdmPdfLayout.XlsxFieldPlacement p : placements) {
            if (p.pageNumber < 1 || p.pageNumber > pageCount) {
                continue;
            }
            Page page = pages.get(p.pageNumber);
            Rectangle rect = new Rectangle(p.llx, p.lly, p.urx, p.ury);
            org.aspose.pdf.forms.TextBoxField tb = new org.aspose.pdf.forms.TextBoxField(page, rect);
            tb.setPartialName(p.name);
            tb.setQuadding(p.quadding);
            if (p.value != null) {
                tb.setValue(p.value);
            }
            if (p.readOnly) {
                tb.setFieldFlags(tb.getFieldFlags() | 1); // /Ff bit 1 = ReadOnly
            }
            PdfDictionary aa = new PdfDictionary();
            if (p.formatJs != null) {
                aa.set(PdfName.of("F"), jsActionDict(p.formatJs));
            }
            if (p.calcJs != null) {
                // Wrap so a runtime error leaves the cached value + baked /AP intact
                // instead of blanking the field.
                aa.set(PdfName.of("C"), jsActionDict("try{event.value = " + p.calcJs + ";}catch(_e){}"));
            }
            if (aa.size() > 0) {
                tb.getPdfDictionary().set(PdfName.of("AA"), aa);
            }
            form.add(tb);
        }
    }

    /** Builds a {@code /S /JavaScript} action dictionary with the given script. */
    private static PdfDictionary jsActionDict(String js) {
        PdfDictionary d = new PdfDictionary();
        d.set(PdfName.of("S"), PdfName.of("JavaScript"));
        d.set(PdfName.of("JS"), new org.aspose.pdf.engine.pdfobjects.PdfString(js));
        return d;
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<String> asStringList(Object o) {
        return o instanceof java.util.List ? (java.util.List<String>) o : java.util.Collections.emptyList();
    }

    /**
     * Wires the "live spreadsheet" pieces onto this document after pagination:
     * the {@code AXL} JavaScript runtime as document-level JS
     * ({@code /Names /JavaScript}), and the AcroForm calculation order
     * ({@code /CO}) so Acrobat recomputes formula fields in dependency order.
     * The per-field calculate/format actions were baked into the widgets during
     * layout and carried over by the page import.
     */
    private void installLiveFormRuntime(String docJs, java.util.List<String> calcOrder)
            throws IOException {
        PdfDictionary catalog = getCatalog();
        Form form = getForm();
        PdfDictionary acro = form.getPdfDictionary();
        // Ensure the AcroForm is reachable from the catalog even on a parser-less
        // (freshly built) document, so the writer serialises it. When the catalog
        // already links it (parser-backed doc), leave the existing reference alone.
        if (catalog.get(PdfName.of("AcroForm")) == null) {
            catalog.set(PdfName.of("AcroForm"), acro);
        }

        if (docJs != null && !docJs.isEmpty()) {
            PdfDictionary jsAction = new PdfDictionary();
            jsAction.set(PdfName.of("S"), PdfName.of("JavaScript"));
            jsAction.set(PdfName.of("JS"), new org.aspose.pdf.engine.pdfobjects.PdfString(docJs));
            org.aspose.pdf.engine.pdfobjects.PdfArray namesArr =
                    new org.aspose.pdf.engine.pdfobjects.PdfArray();
            namesArr.add(new org.aspose.pdf.engine.pdfobjects.PdfString("AXLRuntime"));
            namesArr.add(jsAction);
            PdfDictionary jsTree = new PdfDictionary();
            jsTree.set(PdfName.of("Names"), namesArr);
            PdfBase namesVal = catalog.get(PdfName.of("Names"));
            PdfDictionary names = namesVal instanceof PdfDictionary
                    ? (PdfDictionary) namesVal : new PdfDictionary();
            names.set(PdfName.of("JavaScript"), jsTree);
            catalog.set(PdfName.of("Names"), names);
        }

        if (calcOrder != null && !calcOrder.isEmpty()) {
            org.aspose.pdf.engine.pdfobjects.PdfArray co =
                    new org.aspose.pdf.engine.pdfobjects.PdfArray();
            for (String name : calcOrder) {
                org.aspose.pdf.forms.Field f = form.get(name);
                if (f != null) {
                    co.add(f.getPdfDictionary());
                }
            }
            if (co.size() > 0) {
                acro.set(PdfName.of("CO"), co);
            }
        }
    }

    /**
     * Loads a legacy binary Word document ({@code .doc}, Word 97&ndash;2003):
     * {@code DocSdmReader} parses the OLE2 container, piece table and FKP
     * formatting into the SDM; the shared {@code SdmPdfLayout} paginates it —
     * the same second half the HTML and DOCX loaders use.
     */
    private void initFromLegacyDoc(byte[] doc, DocLoadOptions options) throws IOException {
        org.aspose.pdf.sdm.doc.DocSdmReader reader = new org.aspose.pdf.sdm.doc.DocSdmReader();
        org.aspose.pdf.sdm.SdmDocument sdm = reader.read(doc);
        PageInfo pi = options != null ? options.getPageInfo() : null;
        org.aspose.pdf.sdm.layout.PageSetup setup = pageSetupFrom(pi);
        if (pi == null) {
            applySdmPageSize(setup, sdm);
        } else {
            // An explicit PageInfo overrides the page SIZE, but the document's
            // own margins stay authoritative: a full-width form (0pt margins)
            // must not inherit default 72pt margins, or every tab stop and
            // anchored box slides off its text.
            java.util.Map<String, String> c = sdm.getMetadata().getCustom();
            setup.setMarginLeft(metaPt(c, "margin-left", setup.getMarginLeft()));
            setup.setMarginRight(metaPt(c, "margin-right", setup.getMarginRight()));
            setup.setMarginTop(metaPt(c, "margin-top", setup.getMarginTop()));
            setup.setMarginBottom(metaPt(c, "margin-bottom", setup.getMarginBottom()));
        }
        // Word documents state their page size explicitly — a wide TOC/table
        // must wrap into it, not stretch the page.
        setup.setFixedSize(true);
        org.aspose.pdf.sdm.layout.SdmPdfLayout.Result result =
                new org.aspose.pdf.sdm.layout.SdmPdfLayout().render(sdm, setup);
        adoptSdmPages(result.getDocument());
    }

    /** Copies reader furniture tuples into the setup; returns the band height (pt). */
    private static double furnitureInto(java.util.List<Object[]> lines,
            java.util.List<org.aspose.pdf.sdm.layout.PageSetup.FurnitureLine> out) {
        double h = 0;
        for (Object[] l : lines) {
            double size = ((Number) l[1]).doubleValue();
            org.aspose.pdf.sdm.layout.PageSetup.FurnitureLine line =
                    new org.aspose.pdf.sdm.layout.PageSetup.FurnitureLine(
                            (String) l[0], size, Boolean.TRUE.equals(l[2]),
                            Boolean.TRUE.equals(l[3]));
            // Explicit w:tabs positions (5th tuple slot) refine the default
            // edge/centre/edge spread of TAB-separated furniture parts.
            if (l.length > 4 && l[4] instanceof java.util.List) {
                for (Object stop : (java.util.List<?>) l[4]) {
                    if (stop instanceof double[]) {
                        line.getTabStops().add((double[]) stop);
                    }
                }
            }
            out.add(line);
            h += (size > 0 ? size : 10) * 1.25;
        }
        return h;
    }

    /** Shared HTML-file loader for the {@code HtmlLoadOptions}/{@code LoadOptions} constructors. */
    private void initFromHtmlFile(String filePath, HtmlLoadOptions options, boolean forceSdm)
            throws IOException {
        // A null options means the caller did not fix a page geometry, so a
        // PDF->HTML->PDF round-trip should reproduce the source page size
        // embedded in the HTML rather than the default. Explicit options win.
        boolean callerGeometry = options != null;
        if (options == null) options = new HtmlLoadOptions();
        if (forceSdm || options.isUseSdmPipeline()) {
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            byte[] bytes = java.nio.file.Files.readAllBytes(path);
            // Default the base path to the file's own directory so relative
            // <img>/<link> references resolve without an explicit setBasePath.
            if (options.getBasePath() == null || options.getBasePath().isEmpty()) {
                java.nio.file.Path parent = path.toAbsolutePath().getParent();
                if (parent != null) {
                    options.setBasePath(parent.toString());
                }
            }
            adoptSdmPages(renderHtmlViaSdm(decodeHtml(bytes, options), options, callerGeometry));
            return;
        }
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

    /** Shared HTML-stream loader for the {@code HtmlLoadOptions}/{@code LoadOptions} constructors. */
    private void initFromHtmlStream(InputStream stream, HtmlLoadOptions options, boolean forceSdm)
            throws IOException {
        // Same geometry contract as the file form: null options = reproduce the
        // page size embedded in the HTML; explicit options fix the geometry.
        boolean callerGeometry = options != null;
        if (options == null) options = new HtmlLoadOptions();
        if (forceSdm || options.isUseSdmPipeline()) {
            byte[] bytes = readAllBytes(stream);
            adoptSdmPages(renderHtmlViaSdm(decodeHtml(bytes, options), options, callerGeometry));
            return;
        }
        HtmlToPdfConverter converter = new HtmlToPdfConverter();
        Document converted = converter.convert(stream, options);
        try {
            adoptConvertedPages(converted);
        } finally {
            converted.close();
        }
    }

    /**
     * Runs the SDM HTML pipeline: HTML &rarr; {@code SdmDocument} (via
     * {@code HtmlSdmReader}, CSS cascade resolved in the reader) &rarr; paginated
     * {@link Document} (via {@code SdmPdfLayout}).
     */
    private static Document renderHtmlViaSdm(String html, HtmlLoadOptions options,
                                             boolean callerGeometry) {
        String baseUri = options != null ? options.getBasePath() : null;
        org.aspose.pdf.sdm.html.HtmlReadOptions ro = new org.aspose.pdf.sdm.html.HtmlReadOptions();
        ro.setBaseUri(baseUri);
        ro.setAllowNetwork(options != null && options.isAllowNetworkResources());
        org.aspose.pdf.sdm.SdmDocument sdm =
                new org.aspose.pdf.sdm.html.HtmlSdmReader().read(html, baseUri, ro);
        org.aspose.pdf.sdm.layout.PageSetup setup = pageSetupFrom(options);
        // When the caller did not fix a page geometry, honour the source page size
        // the writer recorded (PDF->HTML->PDF), so a wide/landscape original is not
        // reflowed into portrait (which explodes page counts and clips text).
        if (!callerGeometry) {
            applySdmPageSize(setup, sdm);
        }
        // A fixed-layout export (PDF->HTML->PDF) carries absolutely positioned
        // content sized to its own page; never auto-widen it for "wide tables".
        // Its embedded page size is a pixel-exact reproduction of the source, so
        // it is AUTHORITATIVE even when the caller passed options: an empty
        // HtmlLoadOptions eagerly seeds a default A4 PageInfo, so the
        // callerGeometry guard above would otherwise clamp every landscape /
        // odd-size fixed page to A4 and crush it. Only OUR own exporter sets this
        // flag, so honouring the SDM size here cannot hijack a genuine caller size.
        if ("1".equals(sdm.getMetadata().getCustom().get("fixed-layout"))) {
            setup.setFixedSize(true);
            applySdmPageSize(setup, sdm);
        }
        org.aspose.pdf.sdm.layout.SdmPdfLayout.Result result =
                new org.aspose.pdf.sdm.layout.SdmPdfLayout().render(sdm, setup);
        return result.getDocument();
    }

    /** Overrides the page setup with the source page size recorded in the SDM
     *  metadata ({@code page-width}/{@code page-height} in points), when present. */
    private static void applySdmPageSize(org.aspose.pdf.sdm.layout.PageSetup setup,
                                         org.aspose.pdf.sdm.SdmDocument sdm) {
        try {
            java.util.Map<String, String> c = sdm.getMetadata().getCustom();
            String pw = c.get("page-width");
            String ph = c.get("page-height");
            if (pw != null && ph != null) {
                double w = Double.parseDouble(pw);
                double h = Double.parseDouble(ph);
                if (w > 1 && h > 1) {
                    setup.setPageWidth(w);
                    setup.setPageHeight(h);
                    // Scale margins to the page: the fixed 72pt default swallows a
                    // small page (e.g. a 258x204 label) and explodes it across many
                    // pages. A ~4% border (clamped) keeps content proportionate.
                    double mx = Math.min(54, Math.max(6, w * 0.04));
                    double my = Math.min(54, Math.max(6, h * 0.04));
                    // Explicit source margins (DOCX sectPr pgMar, HTML meta) beat
                    // the heuristic: content that filled one source page must not
                    // spill onto a second because of an invented wider border.
                    setup.setMarginLeft(metaPt(c, "margin-left", mx));
                    setup.setMarginRight(metaPt(c, "margin-right", mx));
                    setup.setMarginTop(metaPt(c, "margin-top", my));
                    setup.setMarginBottom(metaPt(c, "margin-bottom", my));
                }
            }
        } catch (RuntimeException ignore) {
            // malformed metadata — keep the default setup
        }
    }

    /** A points value from SDM metadata, or the fallback when absent/invalid. */
    private static double metaPt(java.util.Map<String, String> meta, String key, double def) {
        String v = meta.get(key);
        if (v != null) {
            try {
                double d = Double.parseDouble(v);
                if (d >= 0 && d < 300) {
                    return d;
                }
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return def;
    }

    /** Maps the {@link HtmlLoadOptions} page geometry to an SDM {@code PageSetup}. */
    private static org.aspose.pdf.sdm.layout.PageSetup pageSetupFrom(HtmlLoadOptions options) {
        return pageSetupFrom(options != null ? options.getPageInfo() : null);
    }

    /** Maps an explicit {@link PageInfo} to an SDM {@code PageSetup}. */
    private static org.aspose.pdf.sdm.layout.PageSetup pageSetupFrom(PageInfo pi) {
        org.aspose.pdf.sdm.layout.PageSetup setup = new org.aspose.pdf.sdm.layout.PageSetup();
        if (pi != null) {
            if (pi.getWidth() > 0) setup.setPageWidth(pi.getWidth());
            if (pi.getHeight() > 0) setup.setPageHeight(pi.getHeight());
            PageInfo.MarginInfo m = pi.getMargin();
            if (m != null) {
                setup.setMarginTop(m.getTop());
                setup.setMarginBottom(m.getBottom());
                setup.setMarginLeft(m.getLeft());
                setup.setMarginRight(m.getRight());
            }
        }
        return setup;
    }

    /** Decodes HTML bytes using {@code options.getInputEncoding()} (UTF-8 default). */
    private static String decodeHtml(byte[] bytes, HtmlLoadOptions options) {
        java.nio.charset.Charset cs = StandardCharsets.UTF_8;
        String enc = options != null ? options.getInputEncoding() : null;
        if (enc != null && !enc.isEmpty()) {
            try {
                cs = java.nio.charset.Charset.forName(enc);
            } catch (RuntimeException ignore) {
                // Unknown encoding name → fall back to UTF-8.
            }
        }
        return new String(bytes, cs);
    }

    /**
     * Deep-copies every page of an SDM-produced document into this one and marks
     * the result pre-paginated (the SDM layout engine already split pages, so
     * {@code save()} must not re-flow them).
     *
     * <p>The layout engine attaches page content as <em>direct, in-memory</em>
     * {@link PdfStream}s (see {@code SdmPdfLayout.finishPage}). The page importer
     * deep-copies through the source's serialized object graph, so we first
     * round-trip {@code produced} through {@code save()}/reopen — this both
     * materializes the streams as regular indirect objects and reuses the
     * well-tested concatenate import path.</p>
     */
    private void adoptSdmPages(Document produced) throws IOException {
        this.pagesPrePaginated = true;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            produced.save(bos);
        } finally {
            try {
                produced.close();
            } catch (IOException e) {
                LOG.fine(() -> "Failed to close SDM-produced document: " + e.getMessage());
            }
        }
        Document parsed = new Document(new java.io.ByteArrayInputStream(bos.toByteArray()));
        try {
            this.getPages().add(parsed.getPages());
        } finally {
            try {
                parsed.close();
            } catch (IOException e) {
                LOG.fine(() -> "Failed to close reopened SDM document: " + e.getMessage());
            }
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
                    PdfDictionary catalog = parser.getCatalog();
                    PdfBase pagesRef = catalog.get(PdfName.PAGES);
                    PdfBase pagesObj = parser.resolveReference(pagesRef);
                    if (!(pagesObj instanceof PdfDictionary)) {
                        pagesObj = recoverPagesDictionaryFromObjects();
                    }
                    if (!(pagesObj instanceof PdfDictionary)) {
                        throw new IOException("Cannot find /Pages dictionary in catalog");
                    }
                    pagesObj = normalizePagesRoot(catalog, (PdfDictionary) pagesObj);
                    pages = new PageCollection((PdfDictionary) pagesObj, parser);
                    pages.setOwningDocument(this);
                }
            }
        }
        return pages;
    }

    /**
     * Normalizes the page-tree root the catalog points at.
     *
     * <p>Some malformed PDFs set the catalog's {@code /Pages} to a leaf page (or
     * an intermediate node) rather than the page-tree root — e.g. the catalog
     * references a {@code /Type /Page} whose {@code /Parent} is the real
     * {@code /Type /Pages} root. Readers tolerate this on read (the single page
     * still resolves), but page-tree mutations ({@code add}/{@code insert})
     * then operate on the wrong node and are silently lost on save. This climbs
     * {@code /Parent} to the topmost {@code /Type /Pages} ancestor and, when it
     * differs from what the catalog references, repoints {@code /Pages} at the
     * true root so reads, edits, and the saved file all agree. ISO 32000-1
     * §7.7.3 (the page tree is rooted at the catalog's {@code /Pages}).</p>
     *
     * @param catalog  the document catalog (repointed in place when needed)
     * @param pagesObj the dictionary the catalog's {@code /Pages} currently resolves to
     * @return the effective page-tree root to wrap in a {@link PageCollection}
     */
    private PdfDictionary normalizePagesRoot(PdfDictionary catalog, PdfDictionary pagesObj) {
        PdfDictionary node = pagesObj;
        java.util.Set<PdfDictionary> seen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seen.add(node);
        while (true) {
            PdfBase parentRef = node.get(PdfName.PARENT);
            if (parentRef == null) {
                break;
            }
            PdfBase parent;
            try {
                parent = parser.resolveReference(parentRef);
            } catch (IOException e) {
                break; // unresolvable parent — keep what we have
            }
            if (!(parent instanceof PdfDictionary) || !seen.add((PdfDictionary) parent)) {
                break; // missing parent or a /Parent cycle
            }
            node = (PdfDictionary) parent;
        }
        if (node != pagesObj && "Pages".equals(node.getNameAsString("Type"))) {
            LOG.warning(() -> "Catalog /Pages pointed at a non-root node; "
                    + "repointing to the true page-tree root so page edits persist");
            catalog.set(PdfName.PAGES, node);
            return node;
        }
        return pagesObj;
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
            PdfDictionary trailer = parser.getTrailer();
            PdfBase infoRef = trailer.get(PdfName.INFO);
            if (infoRef != null) {
                PdfBase infoObj = parser.resolveReference(infoRef);
                if (infoObj instanceof PdfDictionary) {
                    info = new DocumentInfo((PdfDictionary) infoObj);
                    return info;
                }
            }
        }
        // Auto-create
        PdfDictionary infoDict = new PdfDictionary();
        if (parser != null) {
            parser.getTrailer().set(PdfName.INFO, infoDict);
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
                PdfDictionary catalog = parser.getCatalog();
                PdfBase metaRef = catalog.get(PdfName.of("Metadata"));
                if (metaRef != null) {
                    PdfBase resolved = parser.resolveReference(metaRef);
                    if (resolved instanceof PdfStream) {
                        byte[] xmpBytes = ((PdfStream) resolved).getDecodedData();
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
        float headerV = parser != null ? parser.getVersion() : 1.7f;
        String header = String.valueOf(headerV);
        // ISO 32000 §7.5.5: a /Version name in the document catalog overrides the
        // header version when it is later (e.g. a 1.7 file upgraded to 2.0 via an
        // incremental update writes /Version /2.0 into the catalog).
        try {
            PdfDictionary cat = getCatalog();
            if (cat != null) {
                String cv = cat.getNameAsString("Version");
                if (cv != null && !cv.isEmpty()) {
                    try {
                        if (Float.parseFloat(cv) >= headerV) {
                            return cv;
                        }
                    } catch (NumberFormatException ignore) {
                        // malformed /Version name — fall back to the header.
                    }
                }
            }
        } catch (IOException ignore) {
            // catalog unreadable — fall back to the header version.
        }
        return header;
    }

    /**
     * Returns the root catalog dictionary.
     *
     * @return the catalog dictionary
     * @throws IOException if the catalog cannot be read
     */
    public PdfDictionary getCatalog() throws IOException {
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
    public PdfDictionary getTrailer() {
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
                PdfDictionary catalog = parser.getCatalog();
                PdfBase outlinesRef = catalog.get(PdfName.of("Outlines"));
                if (outlinesRef != null) {
                    PdfBase resolved = parser.resolveReference(outlinesRef);
                    if (resolved instanceof PdfDictionary) {
                        outlines = new OutlineCollection((PdfDictionary) resolved, this, parser);
                    }
                }
            }
            if (outlines == null) {
                outlines = new OutlineCollection(this, parser);
                if (parser != null) {
                    parser.getCatalog().set(PdfName.of("Outlines"), outlines.getPdfDictionary());
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
                PdfDictionary catalog = parser.getCatalog();
                PdfBase acroFormRef = catalog.get(PdfName.of("AcroForm"));
                if (acroFormRef != null) {
                    PdfBase resolved = parser.resolveReference(acroFormRef);
                    if (resolved instanceof PdfDictionary) {
                        form = new Form((PdfDictionary) resolved, this, parser);
                    }
                }
            }
            if (form == null) {
                // Create an empty AcroForm AND attach it to the catalog so that
                // any subsequent add() calls actually persist to the saved PDF.
                // Without this, FormEditor.addField on a PDF that has no
                // /AcroForm landed in an orphaned dict that the writer never
                // serialised — see PDFNEWNET-31679.
                PdfDictionary emptyForm = new PdfDictionary();
                emptyForm.set(PdfName.of("Fields"), new org.aspose.pdf.engine.pdfobjects.PdfArray());
                if (parser != null) {
                    parser.getCatalog().set(PdfName.of("AcroForm"), emptyForm);
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
            org.aspose.pdf.engine.pdfobjects.PdfDictionary catalog = getCatalog();
            if (value == null) {
                catalog.remove(org.aspose.pdf.engine.pdfobjects.PdfName.of("Collection"));
                return;
            }
            org.aspose.pdf.engine.pdfobjects.PdfDictionary col = new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
            col.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Type"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("Collection"));
            // /View /D — Detail view; the standard portfolio entry point.
            col.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("View"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("D"));
            catalog.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Collection"), col);
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
        PdfDictionary catalog = parser.getCatalog();
        PdfBase ocProps = parser.resolveReference(catalog.get(PdfName.of("OCProperties")));
        if (!(ocProps instanceof PdfDictionary)) return layers;
        PdfBase ocgs = parser.resolveReference(((PdfDictionary) ocProps).get(PdfName.of("OCGs")));
        if (!(ocgs instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return layers;
        org.aspose.pdf.engine.pdfobjects.PdfArray arr = (org.aspose.pdf.engine.pdfobjects.PdfArray) ocgs;
        for (int i = 0; i < arr.size(); i++) {
            PdfBase ocg = parser.resolveReference(arr.get(i));
            if (ocg instanceof PdfDictionary) layers.add(new Layer((PdfDictionary) ocg));
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
                PdfDictionary catalog = parser.getCatalog();
                PdfBase vp = parser.resolveReference(catalog.get(PdfName.of("ViewerPreferences")));
                if (vp instanceof PdfDictionary) {
                    viewerPreferences = new ViewerPreferences((PdfDictionary) vp);
                } else {
                    PdfDictionary vpDict = new PdfDictionary();
                    catalog.set(PdfName.of("ViewerPreferences"), vpDict);
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
            parser.getCatalog().set(PdfName.of("PageLayout"), PdfName.of(layout));
        }
    }

    public PageMode getPageMode() throws IOException {
        // Use the effective catalog: for documents created with new Document()
        // the catalog lives in inMemoryCatalog (parser is null), and setPageMode
        // writes there too — reading parser.getCatalog() would miss it.
        PdfDictionary catalog = getCatalog();
        if (catalog == null) return PageMode.UseNone;
        String mode = catalog.getNameAsString("PageMode");
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
        // Write to the effective catalog so it works for both parsed documents
        // and ones created with new Document() (whose catalog is inMemoryCatalog;
        // save() syncs those keys into the written catalog).
        PdfDictionary catalog = getCatalog();
        if (catalog != null) {
            catalog.set(PdfName.of("PageMode"), PdfName.of(mode.name()));
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
            // Optimization aims for a SMALLER file. Linearizing adds overhead (linearization
            // dict + hint stream + duplicated first-page objects) that can outweigh the
            // resource pruning on small documents (PDFNEWNET-30310). Emit a compact rewrite
            // with cross-reference/object streams instead, which packs the surviving objects.
            //
            // Optimization must never make the file BIGGER: when the rewrite
            // exceeds the untouched source (format overhead can dominate on
            // tiny or already-tightly-packed files) and the document carries
            // no other tracked modifications, the original bytes win.
            long sourceLen = sourceByteLength();
            boolean fallback = sourceLen > 0 && !hasTrackedModifications();
            java.nio.file.Path sourceBackup = null;
            if (fallback && sourcePath != null && isSameFile(filePath, sourcePath)) {
                // Saving over the source: the inner save destroys the bytes we
                // may need to restore, so stash them in a sibling temp file.
                sourceBackup = java.nio.file.Files.createTempFile("aspose-opt-src", ".pdf");
                java.nio.file.Files.copy(java.nio.file.Paths.get(sourcePath), sourceBackup,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                // Pack into object streams when CompressObjects was asked for,
                // or when the source itself already used cross-reference/object
                // streams (shape-preserving: unpacking such files bloats them).
                // Classic/flat sources stay flat so the compact rewrite still
                // shrinks the file but downstream text greps see the object
                // bodies (Aspose parity — PDFNET-39575).
                boolean useObjStreams = optimizeUseObjectStreams || sourceUsesXRefStream();
                save(filePath, new PdfSaveOptions().setUseObjectStreams(useObjStreams).setUseXRefStream(true));
                optimizeRequested = false;
                optimizeUseObjectStreams = false;
                if (fallback && new File(filePath).length() > sourceLen) {
                    LOG.fine(() -> "Optimized output is larger than the source ("
                            + new File(filePath).length() + " > " + sourceLen
                            + "); keeping the original bytes");
                    if (sourceBackup != null) {
                        java.nio.file.Files.copy(sourceBackup, java.nio.file.Paths.get(filePath),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        try (FileOutputStream fos = new FileOutputStream(filePath)) {
                            writeSourceTo(fos);
                        }
                    }
                }
            } finally {
                if (sourceBackup != null) {
                    try {
                        java.nio.file.Files.deleteIfExists(sourceBackup);
                    } catch (IOException ignore) {
                        // best-effort temp cleanup
                    }
                }
            }
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
     * Byte length of the untouched source this document was opened from, or
     * {@code -1} for programmatically-created documents.
     */
    private long sourceByteLength() {
        if (sourceBytes != null) {
            return sourceBytes.length;
        }
        if (sourcePath != null) {
            File f = new File(sourcePath);
            return f.isFile() ? f.length() : -1;
        }
        return -1;
    }

    /** Streams the untouched source bytes to {@code out}. */
    private void writeSourceTo(OutputStream out) throws IOException {
        if (sourceBytes != null) {
            out.write(sourceBytes);
            return;
        }
        if (sourcePath != null) {
            java.nio.file.Files.copy(java.nio.file.Paths.get(sourcePath), out);
        }
    }

    /**
     * Whether the document carries user modifications this API can track
     * (edited page contents, imported pages, new outlines, XMP changes,
     * pending encryption). Used to decide when an optimizeResources() save
     * may fall back to the original bytes; in-place low-level COS edits are
     * not observable here, so callers combining such edits with
     * optimizeResources() always get the rewritten file (the fallback only
     * replaces output that came out LARGER than the source).
     */
    private boolean hasTrackedModifications() {
        if (!pendingImports.isEmpty() || pendingEncryptor != null || pendingEncDict != null
                || metadata != null) {
            return true;
        }
        if (outlines != null && outlines.getCount() > 0) {
            return true;
        }
        if (pages != null) {
            for (Page p : pages) {
                if (p.isContentsDirty()) {
                    return true;
                }
            }
        }
        return false;
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
        renderPendingPageOverlays();
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
                    PdfObjectKey itemKey = item.getPdfDictionary().getObjectKey();
                    if (itemKey != null) {
                        try {
                            PdfBase existing = parser.getObject(itemKey);
                            if (existing != item.getPdfDictionary()) {
                                hasCrossDocOutlines = true;
                                break;
                            }
                        } catch (Exception e) {
                            hasCrossDocOutlines = true;
                            break;
                        }
                    } else if (item.getPdfDictionary().get("Title") != null) {
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
                Map<PdfObjectKey, PdfBase> modifiedObjects = collectModifiedObjects();
                // Incremental append of a modified page content stream is not
                // reliably honoured on reload for hybrid-reference / xref-stream
                // sources (§7.5.8.4) — the appended object's xref entry is lost,
                // so the edit silently reverts (BUG-TFA-REPLACE-001, e.g.
                // PDFNET_42759). Fall back to a full rewrite in exactly that case.
                // Gated on content-stream edits so signing/other incremental
                // saves (which touch dictionaries, not /Contents) are unaffected.
                // XFA datasets edits (Form.getXFA().set(...)) modify an EXISTING packet stream, so
                // they hit the same hybrid/xref-stream unreliability as content edits — the appended
                // stream's xref entry is lost on reload, leaving Acrobat unable to open the filled
                // form. Treat a dirty XFA packet like a content edit and force a full rewrite.
                boolean xfaEdited = false;
                if (form != null) {
                    try {
                        org.aspose.pdf.forms.xfa.XfaForm xf = form.getXFA();
                        xfaEdited = xf != null && xf.hasDirtyPackets();
                    } catch (Throwable ignore) {
                        // no XFA / not resolvable — leave xfaEdited false
                    }
                }
                boolean unreliableIncremental = !modifiedObjects.isEmpty()
                        && (editedPageContentsThisSave || xfaEdited)
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
                    PdfDictionary trailer = parser != null ? parser.getTrailer() : null;
                    if (trailer != null && trailer.get(PdfName.of("XRefStm")) instanceof PdfInteger
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
        PdfDictionary trailer = parser.getTrailer();
        if (trailer == null) {
            return false;
        }
        PdfBase prev = trailer.get(PdfName.of("Prev"));
        return prev instanceof PdfInteger && ((PdfInteger) prev).longValue() > 0;
    }

    /**
     * Requests a full rewrite on the next save instead of an incremental append.
     * This is a compatibility baseline for {@code Document.Optimize()}.
     */
    public void optimize() {
        optimizeRequested = true;
    }

    /**
     * Rebuilds the page tree as a balanced tree with at most 10 kids per node.
     * API-compatible with Aspose's {@code Document.PageNodesToBalancedTree()}.
     *
     * @throws IOException if the page tree cannot be read
     */
    public void pageNodesToBalancedTree() throws IOException {
        pageNodesToBalancedTree((byte) 10);
    }

    /**
     * Rebuilds the page tree as a balanced tree (ISO 32000-1:2008, §7.7.3.2)
     * with at most {@code nodeKidsCount} kids per intermediate node. A flat
     * tree with tens of thousands of pages makes per-page lookup O(n) in
     * consumers; a balanced tree makes it O(log n).
     *
     * <p>Inheritable attributes (/Resources, /MediaBox, /CropBox, /Rotate)
     * of intermediate nodes are pushed down onto the leaf pages before the
     * old intermediate nodes are discarded, so pages keep their effective
     * values.</p>
     *
     * @param nodeKidsCount maximum kids per node (must be at least 2)
     * @throws IOException if the page tree cannot be read
     */
    public void pageNodesToBalancedTree(byte nodeKidsCount) throws IOException {
        int fanout = nodeKidsCount;
        if (fanout < 2) {
            throw new IllegalArgumentException("nodeKidsCount must be >= 2, got " + fanout);
        }
        PdfDictionary catalog = getCatalog();
        PdfBase rootBase = catalog.get(PdfName.PAGES);
        if (rootBase instanceof PdfObjectReference) {
            rootBase = ((PdfObjectReference) rootBase).dereference();
        }
        if (!(rootBase instanceof PdfDictionary)) {
            return;
        }
        PdfDictionary root = (PdfDictionary) rootBase;

        // Collect leaf page entries (preserving original Kids entry objects, i.e.
        // indirect references stay references) in document order.
        java.util.List<PdfBase> entries = new java.util.ArrayList<>();
        java.util.List<PdfDictionary> dicts = new java.util.ArrayList<>();
        collectPageLeaves(root, null, null, null, null, entries, dicts,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        int total = dicts.size();
        if (total == 0) {
            return;
        }

        // Build the tree bottom-up: group runs of `fanout` siblings under new
        // intermediate /Pages nodes until one level fits under the root.
        java.util.List<PdfBase> levelEntries = entries;
        java.util.List<PdfDictionary> levelDicts = dicts;
        java.util.List<Integer> levelCounts = new java.util.ArrayList<>();
        for (int i = 0; i < total; i++) levelCounts.add(1);

        while (levelEntries.size() > fanout) {
            java.util.List<PdfBase> nextEntries = new java.util.ArrayList<>();
            java.util.List<PdfDictionary> nextDicts = new java.util.ArrayList<>();
            java.util.List<Integer> nextCounts = new java.util.ArrayList<>();
            for (int start = 0; start < levelEntries.size(); start += fanout) {
                int end = Math.min(start + fanout, levelEntries.size());
                PdfDictionary node = new PdfDictionary();
                node.set(PdfName.TYPE, PdfName.PAGES);
                PdfArray kids = new PdfArray();
                int count = 0;
                for (int i = start; i < end; i++) {
                    kids.add(levelEntries.get(i));
                    levelDicts.get(i).set(PdfName.PARENT, node);
                    count += levelCounts.get(i);
                }
                node.set(PdfName.KIDS, kids);
                node.set(PdfName.COUNT, PdfInteger.valueOf(count));
                nextEntries.add(node);
                nextDicts.add(node);
                nextCounts.add(count);
            }
            levelEntries = nextEntries;
            levelDicts = nextDicts;
            levelCounts = nextCounts;
        }

        PdfArray rootKids = new PdfArray();
        for (int i = 0; i < levelEntries.size(); i++) {
            rootKids.add(levelEntries.get(i));
            levelDicts.get(i).set(PdfName.PARENT, root);
        }
        root.set(PdfName.KIDS, rootKids);
        root.set(PdfName.COUNT, PdfInteger.valueOf(total));
        // Old intermediate nodes are now unreachable and get dropped by the
        // writer's reachability walk on the next full save.
    }

    /**
     * Depth-first collection of leaf page entries for {@link #pageNodesToBalancedTree(byte)}.
     * Carries inheritable attributes down and materializes them on leaves that
     * lack their own values (§7.7.3.4), because the old intermediate nodes that
     * held them are discarded.
     */
    private void collectPageLeaves(PdfDictionary node, PdfBase resources, PdfBase mediaBox,
                                   PdfBase cropBox, PdfBase rotate,
                                   java.util.List<PdfBase> outEntries,
                                   java.util.List<PdfDictionary> outDicts,
                                   java.util.Set<PdfDictionary> visited) throws IOException {
        if (!visited.add(node)) {
            return; // cycle guard
        }
        PdfBase r = node.get(PdfName.RESOURCES);
        if (r != null) resources = r;
        PdfBase mb = node.get(PdfName.MEDIABOX);
        if (mb != null) mediaBox = mb;
        PdfBase cb = node.get(PdfName.CROPBOX);
        if (cb != null) cropBox = cb;
        PdfBase rot = node.get(PdfName.ROTATE);
        if (rot != null) rotate = rot;

        PdfArray kids = node.getArray("Kids");
        if (kids == null) {
            return;
        }
        for (int i = 0; i < kids.size(); i++) {
            PdfBase entry = kids.get(i);
            PdfBase resolved = entry instanceof PdfObjectReference
                    ? ((PdfObjectReference) entry).dereference() : entry;
            if (!(resolved instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary kid = (PdfDictionary) resolved;
            if ("Pages".equals(kid.getType()) || (kid.containsKey("Kids") && !kid.containsKey("Contents"))) {
                collectPageLeaves(kid, resources, mediaBox, cropBox, rotate, outEntries, outDicts, visited);
            } else {
                if (resources != null && !kid.containsKey("Resources")) kid.set(PdfName.RESOURCES, resources);
                if (mediaBox != null && !kid.containsKey("MediaBox")) kid.set(PdfName.MEDIABOX, mediaBox);
                if (cropBox != null && !kid.containsKey("CropBox")) kid.set(PdfName.CROPBOX, cropBox);
                if (rotate != null && !kid.containsKey("Rotate")) kid.set(PdfName.ROTATE, rotate);
                outEntries.add(entry);
                outDicts.add(kid);
            }
        }
    }

    /**
     * Gets whether the next save should favour a compact full rewrite
     * (object streams, no orphaned objects) over an incremental append.
     * API-compatible with Aspose's {@code Document.OptimizeSize}.
     *
     * @return {@code true} if compact-size saving has been requested
     */
    public boolean isOptimizeSize() {
        return optimizeSize;
    }

    /**
     * Sets whether the next save should favour a compact full rewrite.
     * API-compatible with Aspose's {@code Document.OptimizeSize}: enabling it
     * requests the same compact rewrite as {@link #optimize()}.
     *
     * @param value {@code true} to request compact-size saving
     */
    public void setOptimizeSize(boolean value) {
        optimizeSize = value;
        if (value) {
            optimizeRequested = true;
        }
    }

    /**
     * Requests that the next save operation performs a full rewrite even if
     * incremental save would otherwise be possible.
     */
    public void requestFullRewrite() {
        fullRewriteRequested = true;
    }

    /**
     * Removes the given objects from the underlying parser so they are not
     * written on the next full rewrite. Used to drop object subtrees that became
     * orphaned by an in-memory edit (e.g. embedded font programs left unreferenced
     * by {@code FontReplace.RemoveUnusedFonts}). Safe to call with keys that are
     * unknown or already removed.
     *
     * @param keys the object keys to drop
     */
    public void removeParserObjects(java.util.Collection<PdfObjectKey> keys) {
        if (parser == null || keys == null) {
            return;
        }
        for (PdfObjectKey key : keys) {
            parser.removeObject(key);
        }
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
        // Parameterless behaviour mirrors Aspose's default OptimizationOptions:
        // unused objects/streams removal plus duplicate-stream linking.
        org.aspose.pdf.optimization.OptimizationOptions defaults =
                new org.aspose.pdf.optimization.OptimizationOptions();
        defaults.setRemoveUnusedObjects(true);
        defaults.setRemoveUnusedStreams(true);
        defaults.setLinkDuplicateStreams(true);
        optimizeResources(defaults);
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
        if (options.isAllowReusePageContent() || options.isRemoveUnusedStreams()
                || options.isRemoveUnusedObjects()) {
            pruneUnusedResourcesAcrossPages();
        }
        // Graph-level passes (image recompression, stream recompression,
        // duplicate-stream linking). Objects orphaned here are dropped by
        // the compact rewrite requested below.
        org.aspose.pdf.engine.optimization.ResourceOptimizer.Stats optStats = null;
        if (parser != null) {
            Map<PdfStream, double[]> imageDisplay = null;
            if (options.isResizeImages() && options.getMaxResolution() > 0) {
                imageDisplay = collectImageDisplaySizes();
            }
            optStats = org.aspose.pdf.engine.optimization.ResourceOptimizer.optimize(
                    parser, options, imageDisplay);
        }
        // A full rewrite re-serialises only the objects still reachable from the
        // trailer, which is exactly "remove unused objects/streams". Duplicate
        // streams are likewise only written once when the writer dedups, so we
        // also request the rewrite for linkDuplicateStreams.
        if (options.isRemoveUnusedObjects() || options.isRemoveUnusedStreams()
                || options.isLinkDuplicateStreams() || options.isCompressObjects()) {
            optimizeRequested = true;
        }
        // The image/recompress/subset/private-info passes REWRITE stream bytes in
        // place. Without a full rewrite the next save appends those modified
        // objects incrementally, leaving the ORIGINAL (larger) bytes in the file
        // AND the new ones — the output nearly doubles (BUG-OPT-22: 35473.pdf
        // 26.3MB -> 48.9MB with recompress on, dedup off). Force the compact
        // rewrite whenever a content-mutating pass actually changed something, so
        // the superseded bytes are dropped.
        if (optStats != null
                && (optStats.streamsRecompressed > 0 || optStats.imagesRecompressed > 0
                        || optStats.fontsSubset > 0 || optStats.privateEntriesRemoved > 0
                        || optStats.duplicatesLinked > 0)) {
            optimizeRequested = true;
        }
        if (options.isCompressObjects()) {
            optimizeUseObjectStreams = true;
        }
        LOG.fine(() -> "optimizeResources(options): rewrite="
                + (options.isRemoveUnusedObjects() || options.isRemoveUnusedStreams()
                        || options.isLinkDuplicateStreams())
                + " compressImages=" + options.isCompressImages()
                + " subsetFonts=" + options.isSubsetFonts());
    }

    /**
     * Collects, per image XObject stream, the largest display footprint (in
     * points) across all its placements in the document. Used by the
     * optimizer to compute effective resolution for downsampling.
     */
    private Map<PdfStream, double[]> collectImageDisplaySizes() throws IOException {
        Map<PdfStream, double[]> sizes = new java.util.IdentityHashMap<>();
        for (int i = 1; i <= getPages().getCount(); i++) {
            ImagePlacementAbsorber absorber = new ImagePlacementAbsorber();
            try {
                absorber.visit(getPages().get(i));
            } catch (IOException e) {
                LOG.fine(() -> "Image placement scan failed: " + e.getMessage());
                continue;
            }
            for (ImagePlacement placement : absorber.getImagePlacements()) {
                if (placement.getImage() == null || placement.getRectangle() == null) {
                    continue;
                }
                PdfStream stream = placement.getImage().getPdfStream();
                Rectangle rect = placement.getRectangle();
                double[] current = sizes.computeIfAbsent(stream, s -> new double[]{0, 0});
                current[0] = Math.max(current[0], rect.getWidth());
                current[1] = Math.max(current[1], rect.getHeight());
            }
        }
        return sizes;
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
        } else if (format == SaveFormat.DocX || format == SaveFormat.Doc) {
            save(filePath, new DocSaveOptions());
        } else if (format == SaveFormat.Xlsx || format == SaveFormat.Excel) {
            save(filePath, new ExcelSaveOptions());
        } else if (format == SaveFormat.Markdown) {
            save(filePath, new MarkdownSaveOptions());
        } else {
            save(filePath);
        }
    }

    /**
     * Saves the document to a stream in the given format (Aspose.PDF
     * {@code Document.Save(Stream, SaveFormat)}). {@link SaveFormat#Doc} and
     * {@link SaveFormat#DocX} both produce an OOXML package (there is no
     * binary Word 97 serializer).
     *
     * @param outputStream the output stream (not closed by this method)
     * @param format       the desired output format
     * @throws IOException if writing fails
     */
    public void save(OutputStream outputStream, SaveFormat format) throws IOException {
        if (format == SaveFormat.Html) {
            save(outputStream, new HtmlSaveOptions());
        } else if (format == SaveFormat.DocX || format == SaveFormat.Doc) {
            save(outputStream, new DocSaveOptions());
        } else if (format == SaveFormat.Xlsx || format == SaveFormat.Excel) {
            save(outputStream, new ExcelSaveOptions());
        } else if (format == SaveFormat.Markdown) {
            save(outputStream, new MarkdownSaveOptions());
        } else {
            save(outputStream);
        }
    }

    /**
     * Saves the document to a file in the format determined by the runtime type
     * of {@code options} — the single, uniform save entry point (Aspose.PDF
     * {@code Document.save(String, SaveOptions)}). A {@link PdfSaveOptions} writes
     * a PDF, an {@link HtmlSaveOptions} writes HTML.
     *
     * @param filePath the output file path
     * @param options  the save options; their concrete type selects the format
     *                 ({@code null} saves a plain PDF)
     * @throws IOException if writing fails
     */
    public void save(String filePath, SaveOptions options) throws IOException {
        if (options == null) {
            save(filePath);
        } else if (options instanceof HtmlSaveOptions) {
            save(filePath, (HtmlSaveOptions) options);
        } else if (options instanceof DocSaveOptions) {
            save(filePath, (DocSaveOptions) options);
        } else if (options instanceof ExcelSaveOptions) {
            save(filePath, (ExcelSaveOptions) options);
        } else if (options instanceof MarkdownSaveOptions) {
            save(filePath, (MarkdownSaveOptions) options);
        } else if (options instanceof PdfSaveOptions) {
            save(filePath, (PdfSaveOptions) options);
        } else if (options.getSaveFormat() == SaveFormat.Html) {
            save(filePath, new HtmlSaveOptions());
        } else if (options.getSaveFormat() == SaveFormat.DocX) {
            save(filePath, new DocSaveOptions());
        } else if (options.getSaveFormat() == SaveFormat.Xlsx
                || options.getSaveFormat() == SaveFormat.Excel) {
            save(filePath, new ExcelSaveOptions());
        } else if (options.getSaveFormat() == SaveFormat.Markdown) {
            save(filePath, new MarkdownSaveOptions());
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
        // Materialize paragraphs / header-footer added to the pages of a loaded
        // document. The linearized and compressed writers below serialise straight
        // from the parser and never route through save(OutputStream), so without
        // this the authored text of a newly appended page would be dropped.
        renderPendingPageOverlays();
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
            // The overlay above registers its Form XObject in pendingImports, which
            // the linearized writer (reading purely from the parser) cannot see.
            // Bake pending edits into a concrete object graph and linearize that.
            Document baked = bakedForStructuralWrite();
            final PDFParser writeParser = baked != null ? baked.getParser() : parser;
            try {
                if (sameFile) {
                    saveToSameFileWith(filePath, fos -> {
                        LinearizedPDFWriter writer = new LinearizedPDFWriter();
                        writer.write(fos, writeParser, writeParser.getTrailer());
                    });
                } else {
                    try (FileOutputStream fos = new FileOutputStream(filePath)) {
                        LinearizedPDFWriter writer = new LinearizedPDFWriter();
                        writer.write(fos, writeParser, writeParser.getTrailer());
                    }
                }
            } finally {
                if (baked != null) baked.close();
            }
        } else if (options != null && (options.isUseObjectStreams() || options.isUseXRefStream())) {
            if (parser == null) {
                throw new IOException("Cannot write compressed: no parser (new document)");
            }
            // Same pendingImports concern as the linearize path: bake first when
            // there are in-memory overlays, then write from the reparsed graph.
            Document baked = bakedForStructuralWrite();
            final Document writeDoc = baked != null ? baked : this;
            try {
                if (sameFile) {
                    saveToSameFileWith(filePath, fos -> writeDoc.saveCompressed(fos, options));
                } else {
                    try (FileOutputStream fos = new FileOutputStream(filePath)) {
                        writeDoc.saveCompressed(fos, options);
                    }
                }
            } finally {
                if (baked != null) baked.close();
            }
        } else {
            save(filePath);
        }
    }

    /**
     * Bakes any in-memory edits held in {@link #pendingImports} (paragraph and
     * header/footer overlays applied to the pages of a loaded document) into a
     * concrete object graph, by performing an in-memory full rewrite and
     * reparsing the result. Returned so the linearized / compressed writers —
     * which serialise straight from a {@link PDFParser} and cannot see
     * pendingImports — can write from a self-contained parser.
     * <p>
     * Callers must {@link #close()} the returned document. Returns {@code null}
     * when there is nothing to bake, in which case the writer should use this
     * document's own parser directly.
     * </p>
     *
     * @return a reparsed document carrying the baked edits, or {@code null}
     * @throws IOException if the intermediate rewrite or reparse fails
     */
    private Document bakedForStructuralWrite() throws IOException {
        if (pendingImports.isEmpty()) {
            return null;
        }
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        saveFullRewrite(buf);
        return new Document(new java.io.ByteArrayInputStream(buf.toByteArray()));
    }

    /**
     * Saves the document using object streams and/or xref streams (PDF 1.5+).
     *
     * @param outputStream the output stream
     * @param options      save options controlling compression
     * @throws IOException if writing fails
     */
    private void saveCompressed(OutputStream outputStream, PdfSaveOptions options) throws IOException {
        Map<PdfObjectKey, PdfBase> objects = new LinkedHashMap<>();
        int maxObjNum = 0;
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            try {
                PdfBase obj = parser.getObject(key);
                if (obj != null && !(obj instanceof PdfNull)) {
                    objects.put(key, obj);
                    maxObjNum = Math.max(maxObjNum, key.getObjectNumber());
                }
            } catch (IOException e) {
                // Same lenient policy as saveFullRewrite: a corrupt object the
                // xref points at (e.g. an /ObjStm container that is not a
                // stream) must not abort the save of everything else.
                LOG.fine(() -> "Skipping unreadable object during compressed save: "
                        + key + " (" + e.getMessage() + ")");
            }
        }
        if (metadata != null) {
            maxObjNum = syncXmpToCatalog(parser.getCatalog(), objects, maxObjNum);
        }
        maxObjNum = registerLiveCatalog(objects, maxObjNum);
        maxObjNum = registerLivePageTreeRoot(objects, maxObjNum);

        float version = Math.max(parser.getVersion(), 1.5f);
        PDFWriter writer = new PDFWriter(outputStream, version);
        PdfDictionary trailer = new PdfDictionary(parser.getTrailer());
        // optimizeResources(): drop everything the trailer no longer reaches —
        // this is what actually removes unused objects and the duplicates
        // orphaned by ResourceOptimizer's stream linking.
        if (optimizeRequested) {
            retainReachableObjects(objects, trailer);
        }
        configureExistingEncryption(writer, trailer);
        maxObjNum = applyPendingEncryption(writer, trailer, objects, maxObjNum);

        if (options.isUseObjectStreams()) {
            writer.writeCompressed(trailer, objects, options.getObjectsPerStream());
        } else {
            // XRef stream only, no object streams — maxPerStream<=0 keeps every
            // object as a plain "N G obj" body (MAX_VALUE would instead pack them
            // all into one giant /ObjStm).
            writer.writeCompressed(trailer, objects, 0);
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
        java.nio.file.Files.writeString(java.nio.file.Path.of(filePath), htmlText(options),
                StandardCharsets.UTF_8);
    }

    /**
     * Converts the document to HTML markup (IR Stage 3).
     *
     * <p>Routing is controlled by {@link HtmlSaveOptions#getOutputMode()}:
     * {@link HtmlOutputMode#FIXED_LAYOUT} (default) produces the classic
     * absolutely-positioned visual copy; {@link HtmlOutputMode#STRUCTURAL}
     * produces semantic HTML (h1..h6/p/ul/ol/table) from the Semantic Document
     * Model — tagged PDFs use the author's structure tree, untagged PDFs use
     * geometry heuristics unless {@link HtmlSaveOptions#isStructuralHeuristics()}
     * is off.</p>
     */
    private String htmlText(HtmlSaveOptions options) throws IOException {
        flushDirtyPages();
        if (options == null) {
            options = new HtmlSaveOptions();
        }
        Document renderDoc = materializeDynamicXfaForHtml();
        try {
            if (options.getOutputMode() == HtmlOutputMode.STRUCTURAL) {
                return org.aspose.pdf.sdm.html.StructuralHtmlPipeline.toHtml(renderDoc, options);
            }
            return new PdfToHtmlConverter().convert(renderDoc, options);
        } finally {
            if (renderDoc != this) {
                try {
                    renderDoc.close();
                } catch (IOException ignore) {
                    // best-effort cleanup of the transient painted document
                }
            }
        }
    }

    /**
     * Returns the document whose pages the HTML export should convert.
     *
     * <p>A <em>dynamic</em> XFA form (ISO 32000-1:2008 §12.7.8) ships only a
     * static "Please wait…" placeholder page in the PDF body — the real form
     * lives in the XFA XML and is laid out by the viewer at open time. The
     * catalog's {@code /NeedsRendering} flag marks such documents. Converting
     * the placeholder to HTML would lose the entire form, so here we paint the
     * paginated XFA (binding + load scripts) into a fresh document and convert
     * THAT instead, giving the user the actual form. Any failure — no template,
     * paint produced nothing, or an engine error — falls back to converting the
     * original page(s) unchanged, so non-XFA and hybrid/static-XFA documents are
     * never disturbed.</p>
     *
     * @return a transient painted document (caller must close it) when the XFA
     *         was materialized, otherwise {@code this}
     */
    private Document materializeDynamicXfaForHtml() {
        try {
            if (!getCatalog().getBoolean("NeedsRendering", false)) {
                return this;
            }
            Form form = getForm();
            org.aspose.pdf.forms.xfa.XfaForm xfa = form != null ? form.getXFA() : null;
            if (xfa == null || xfa.getTemplate() == null) {
                return this;
            }
            Document painted = new Document();
            org.aspose.pdf.engine.xfa.flatten.layout.XfaPaginator.Result r =
                    xfa.paintPaginatedContent(painted);
            if (r == null || r.pages <= 0 || painted.getPages().getCount() == 0) {
                painted.close();
                return this;
            }
            LOG.fine(() -> "Materialized dynamic XFA into " + r.pages + " page(s) for HTML export");
            return painted;
        } catch (Throwable t) {
            LOG.fine(() -> "Dynamic-XFA materialization for HTML skipped: " + t);
            return this;
        }
    }

    /**
     * Saves the document as Office Open XML ({@code .docx}) with the specified
     * options (IR structural pipeline).
     *
     * @param filePath the output {@code .docx} file path
     * @param options  DOCX save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(String filePath, DocSaveOptions options) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        flushDirtyPages();
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            org.aspose.pdf.sdm.docx.StructuralDocxPipeline.toDocx(this, options, fos);
        }
    }

    /**
     * Converts the document to {@code .docx} and writes it to a stream.
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      DOCX save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(OutputStream outputStream, DocSaveOptions options) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        flushDirtyPages();
        org.aspose.pdf.sdm.docx.StructuralDocxPipeline.toDocx(this, options, outputStream);
        outputStream.flush();
    }

    /**
     * Saves the document as an Office Open XML spreadsheet ({@code .xlsx}) with
     * the specified options (IR structural pipeline). Recognised tables become
     * worksheets of typed cells (number / date / boolean / text).
     *
     * @param filePath the output {@code .xlsx} file path
     * @param options  Excel save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(String filePath, ExcelSaveOptions options) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        flushDirtyPages();
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            org.aspose.pdf.sdm.xlsx.StructuralXlsxPipeline.toXlsx(this, options, fos);
        }
    }

    /**
     * Converts the document to {@code .xlsx} and writes it to a stream.
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      Excel save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(OutputStream outputStream, ExcelSaveOptions options) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        flushDirtyPages();
        org.aspose.pdf.sdm.xlsx.StructuralXlsxPipeline.toXlsx(this, options, outputStream);
        outputStream.flush();
    }

    /**
     * Saves the document as Markdown ({@code .md}) with the specified options
     * (IR structural pipeline). Headings, paragraphs, recognised tables, lists,
     * emphasis, links and images are serialized as CommonMark / GitHub-Flavored
     * Markdown. Referenced images are written into a sub-directory next to the
     * output file (see {@link MarkdownSaveOptions#getResourcesDirectoryName()})
     * unless inline embedding is requested.
     *
     * @param filePath the output {@code .md} file path
     * @param options  Markdown save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(String filePath, MarkdownSaveOptions options) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("File path must not be null");
        }
        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        java.nio.file.Path dir = path.toAbsolutePath().getParent();
        java.nio.file.Files.writeString(path, markdownText(options, dir), StandardCharsets.UTF_8);
    }

    /**
     * Converts the document to Markdown and writes it to a stream. A stream
     * target has no folder for external image files, so images are embedded as
     * {@code data:} URIs.
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      Markdown save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(OutputStream outputStream, MarkdownSaveOptions options) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        outputStream.write(markdownText(options, null).getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Converts the document to Markdown markup, embedding every image as a
     * {@code data:} URI (Aspose.PDF has no {@code MarkdownText} accessor; this is
     * the FOSS convenience mirror of {@link #htmlText(HtmlSaveOptions)}).
     *
     * @param options Markdown save options; {@code null} means defaults
     * @return the Markdown text
     * @throws IOException if conversion fails
     */
    public String markdownText(MarkdownSaveOptions options) throws IOException {
        return markdownText(options, null);
    }

    private String markdownText(MarkdownSaveOptions options, java.nio.file.Path baseDir)
            throws IOException {
        flushDirtyPages();
        if (options == null) {
            options = new MarkdownSaveOptions();
        }
        Document renderDoc = materializeDynamicXfaForHtml();
        try {
            return org.aspose.pdf.sdm.markdown.StructuralMarkdownPipeline.toMarkdown(
                    renderDoc, options, baseDir);
        } finally {
            if (renderDoc != this) {
                try {
                    renderDoc.close();
                } catch (IOException ignore) {
                    // best-effort cleanup of the transient painted document
                }
            }
        }
    }

    /**
     * Saves the document as HTML into a stream. Resources are embedded into
     * the markup (a stream target has no folder for external parts).
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      HTML save options; {@code null} means defaults
     * @throws IOException if conversion or writing fails
     */
    public void save(OutputStream outputStream, HtmlSaveOptions options) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        outputStream.write(htmlText(options).getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    /**
     * Saves the document to a stream with the specified PDF save options,
     * honouring linearization and object/xref-stream compression (the stream
     * counterpart of {@link #save(String, PdfSaveOptions)}).
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      PDF save options; {@code null} writes a plain PDF
     * @throws IOException if writing fails
     */
    public void save(OutputStream outputStream, PdfSaveOptions options) throws IOException {
        if (outputStream == null) {
            throw new IllegalArgumentException("Output stream must not be null");
        }
        flushDirtyPages();
        if (options != null && options.isLinearize()) {
            if (parser == null) {
                throw new IOException("Cannot linearize a new (unparsed) document");
            }
            LinearizedPDFWriter writer = new LinearizedPDFWriter();
            writer.write(outputStream, parser, parser.getTrailer());
        } else if (options != null && (options.isUseObjectStreams() || options.isUseXRefStream())) {
            if (parser == null) {
                throw new IOException("Cannot write compressed: no parser (new document)");
            }
            saveCompressed(outputStream, options);
        } else {
            save(outputStream);
        }
    }

    /**
     * Saves the document to a stream in the format determined by the runtime type
     * of {@code options} — the stream counterpart of
     * {@link #save(String, SaveOptions)} (Aspose.PDF
     * {@code Document.save(OutputStream, SaveOptions)}).
     *
     * @param outputStream the output stream (not closed by this method)
     * @param options      the save options; their concrete type selects the format
     *                     ({@code null} saves a plain PDF)
     * @throws IOException if writing fails
     */
    public void save(OutputStream outputStream, SaveOptions options) throws IOException {
        if (options == null) {
            save(outputStream);
        } else if (options instanceof HtmlSaveOptions) {
            save(outputStream, (HtmlSaveOptions) options);
        } else if (options instanceof DocSaveOptions) {
            save(outputStream, (DocSaveOptions) options);
        } else if (options instanceof ExcelSaveOptions) {
            save(outputStream, (ExcelSaveOptions) options);
        } else if (options instanceof PdfSaveOptions) {
            save(outputStream, (PdfSaveOptions) options);
        } else if (options.getSaveFormat() == SaveFormat.Html) {
            save(outputStream, new HtmlSaveOptions());
        } else if (options.getSaveFormat() == SaveFormat.DocX) {
            save(outputStream, new DocSaveOptions());
        } else if (options.getSaveFormat() == SaveFormat.Xlsx
                || options.getSaveFormat() == SaveFormat.Excel) {
            save(outputStream, new ExcelSaveOptions());
        } else {
            save(outputStream);
        }
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
            PdfDictionary catalog = getCatalog();
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
            PdfDictionary catalog = getCatalog();
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
        PdfDictionary catalog = getCatalog();
        if (catalog == null) return null;
        PdfBase strObj = catalog.get("StructTreeRoot");
        if (strObj instanceof PdfObjectReference) {
            strObj = ((PdfObjectReference) strObj).dereference();
        }
        if (strObj instanceof PdfDictionary) {
            return new StructTreeRoot((PdfDictionary) strObj, parser);
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
                for (PdfObjectKey key : parser.getAllObjectKeys()) {
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
     * key. Returns a {@link PdfObjectReference} whose resolver points into this
     * document's pending-imports map; the object is merged into the output
     * during save.
     */
    public PdfObjectReference registerImportedObject(PdfBase body) {
        if (body == null) throw new IllegalArgumentException("body must not be null");
        PdfObjectKey key = new PdfObjectKey(allocateObjectNumber(), 0);
        pendingImports.put(key, body);
        if (body instanceof PdfDictionary) {
            ((PdfDictionary) body).setObjectKey(key);
        }
        return new PdfObjectReference(key, k -> pendingImports.get(k));
    }

    /** Returns the pending-imports map (package-private for PageCollection/save paths). */
    Map<PdfObjectKey, PdfBase> getPendingImports() {
        return pendingImports;
    }

    /**
     * Collects all PDF objects that have been modified since loading.
     *
     * @return map of modified object keys to their PDF objects
     * @throws IOException if objects cannot be loaded
     */
    private Map<PdfObjectKey, PdfBase> collectModifiedObjects() throws IOException {
        Map<PdfObjectKey, PdfBase> modified = new LinkedHashMap<>();
        // Snapshot the key set first — parser.getObject can lazily load new
        // objects (compressed-object streams, recovered objects) and mutate the
        // backing map, which would otherwise raise ConcurrentModificationException.
        List<PdfObjectKey> keys = new ArrayList<>(parser.getAllObjectKeys());
        for (PdfObjectKey key : keys) {
            try {
                PdfBase obj = parser.getObject(key);
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
    private boolean isDirtyDeep(PdfBase obj) {
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
    private boolean isDirtyDeep(PdfBase obj, int depth) {
        if (obj == null || depth > 5) return false;
        if (obj.isDirty()) return true;
        if (obj instanceof PdfDictionary) {
            for (PdfBase value : ((PdfDictionary) obj).values()) {
                if (value != null && !(value instanceof PdfObjectReference)) {
                    if (isDirtyDeep(value, depth + 1)) return true;
                }
            }
        }
        if (obj instanceof PdfArray) {
            PdfArray arr = (PdfArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase item = arr.get(i);
                if (item != null && !(item instanceof PdfObjectReference)) {
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
                                  Map<PdfObjectKey, PdfBase> modifiedObjects) throws IOException {
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
    /**
     * Whether an object is stale file-structure metadata that a full rewrite regenerates and must
     * NOT emit as a body object: a cross-reference stream ({@code /Type /XRef}) or an object stream
     * ({@code /Type /ObjStm}). The parser exposes these as ordinary objects (and decompresses ObjStm
     * members into their own keys), so writing the containers too would leave an orphaned XRef-stream
     * object — carrying the source's {@code /Prev} offset — which strict viewers (Acrobat/Chrome)
     * report as "damaged, but can be repaired".
     */
    private static boolean isStaleFileStructure(PdfBase obj) {
        if (obj instanceof PdfDictionary) {
            String type = ((PdfDictionary) obj).getNameAsString("Type");
            return "XRef".equals(type) || "ObjStm".equals(type);
        }
        return false;
    }

    private void saveFullRewrite(OutputStream outputStream) throws IOException {
        Map<PdfObjectKey, PdfBase> objects = new LinkedHashMap<>();
        int maxObjNum = 0;
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            try {
                PdfBase obj = parser.getObject(key);
                if (obj != null && !(obj instanceof PdfNull) && !isStaleFileStructure(obj)) {
                    objects.put(key, obj);
                    maxObjNum = Math.max(maxObjNum, key.getObjectNumber());
                }
            } catch (IOException e) {
                LOG.fine(() -> "Skipping unreadable object during full rewrite: "
                        + key + " (" + e.getMessage() + ")");
            }
        }
        // Merge objects imported from other documents (cross-document page copy).
        for (Map.Entry<PdfObjectKey, PdfBase> e : pendingImports.entrySet()) {
            objects.put(e.getKey(), e.getValue());
            maxObjNum = Math.max(maxObjNum, e.getKey().getObjectNumber());
        }
        maxObjNum = registerLiveCatalog(objects, maxObjNum);
        maxObjNum = registerLivePageTreeRoot(objects, maxObjNum);
        // Register new outline items that were added programmatically (e.g. from another document)
        if (outlines != null && outlines.getCount() > 0) {
            PdfDictionary outlinesDict = outlines.getPdfDictionary();
            // Ensure outlines dict is registered
            if (outlinesDict.getObjectKey() == null) {
                PdfObjectKey outlinesKey = new PdfObjectKey(++maxObjNum, 0);
                outlinesDict.setObjectKey(outlinesKey);
                objects.put(outlinesKey, outlinesDict);
                parser.getCatalog().set(PdfName.of("Outlines"),
                        new PdfObjectReference(outlinesKey, k -> objects.get(k)));
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
        PdfDictionary trailer = new PdfDictionary(parser.getTrailer());
        // Hybrid-reference source (§7.5.8.4): preserve the classic-table +
        // /XRefStm shape on full rewrite, like Aspose (PDFNET-45599).
        writer.setPreserveHybridXRef(trailer.get(PdfName.of("XRefStm")) != null);
        trailer.remove(PdfName.of("Prev"));
        trailer.remove(PdfName.of("XRefStm"));
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

        Map<PdfObjectKey, PdfBase> objects = new LinkedHashMap<>();
        int objNum = 1;
        PdfObjectReference trailerInfoRef = null;
        // Pre-seed objects with pendingImports (cross-document imports use keys
        // allocated via Document.allocateObjectNumber, which may overlap with
        // the local counter below — bump objNum past the highest imported key).
        int maxImportNum = 0;
        for (Map.Entry<PdfObjectKey, PdfBase> e : pendingImports.entrySet()) {
            objects.put(e.getKey(), e.getValue());
            maxImportNum = Math.max(maxImportNum, e.getKey().getObjectNumber());
        }
        if (maxImportNum > objNum) objNum = maxImportNum;

        // Build pages dictionary with /Kids
        PdfDictionary pagesDict = new PdfDictionary();
        pagesDict.set(PdfName.TYPE, PdfName.PAGES);
        org.aspose.pdf.engine.pdfobjects.PdfArray kids = new org.aspose.pdf.engine.pdfobjects.PdfArray();

        if (pages != null) {
            for (Page page : pages) {
                PdfDictionary pd = page.getPdfDictionary();
                PdfObjectKey existing = pd.getObjectKey();
                PdfObjectKey pageKey;
                if (existing != null && objects.containsKey(existing) && objects.get(existing) == pd) {
                    // Imported page already registered via pendingImports — reuse its key.
                    pageKey = existing;
                } else {
                    pageKey = new PdfObjectKey(++objNum, 0);
                    pd.setObjectKey(pageKey);
                    objects.put(pageKey, pd);
                }
                pd.set(PdfName.PARENT, pagesDict);
                kids.add(new PdfObjectReference(pageKey, k -> objects.get(k)));

                // Register content stream as indirect object.
                // BUG O: a previous saveNewDocument call replaces /Contents with
                // a PdfObjectReference bound to the *old* (now-stale) objects
                // map. On the second save we must resolve through that
                // reference, recover the underlying PdfStream, and re-register
                // it in the current objects map with a fresh key. Without this,
                // the page's /Contents reference would point at an object the
                // writer never emits — every page renders blank.
                PdfBase contentsVal = pd.get("Contents");
                PdfStream contentsStream = null;
                if (contentsVal instanceof PdfStream) {
                    contentsStream = (PdfStream) contentsVal;
                } else if (contentsVal instanceof PdfObjectReference) {
                    try {
                        PdfBase resolved = ((PdfObjectReference) contentsVal).dereference();
                        if (resolved instanceof PdfStream) {
                            contentsStream = (PdfStream) resolved;
                        }
                    } catch (IOException ignored) {
                        // Fall through; contentsStream stays null and we leave
                        // /Contents alone — the writer will emit a dangling ref
                        // but that's no worse than the pre-fix behaviour.
                    }
                }
                if (contentsStream != null) {
                    PdfObjectKey existingContentsKey = contentsStream.getObjectKey();
                    PdfObjectKey csKey;
                    if (existingContentsKey != null
                            && objects.get(existingContentsKey) == contentsStream) {
                        // Already registered in this save's map (e.g. via
                        // pendingImports) — no need to re-key.
                        csKey = existingContentsKey;
                    } else {
                        csKey = new PdfObjectKey(++objNum, 0);
                        contentsStream.setObjectKey(csKey);
                        objects.put(csKey, contentsStream);
                    }
                    pd.set(PdfName.of("Contents"),
                            new PdfObjectReference(csKey, k -> objects.get(k)));
                }

                // Register font dictionaries from /Resources/Font as indirect objects.
                // Type0 fonts carry nested streams (/DescendantFonts → FontDescriptor →
                // /FontFile2 with raw TTF bytes) that PDF spec §7.3.8 requires to be
                // indirect objects — direct streams confuse some parsers (including
                // our own). Walk the font dict tree and lift every PdfStream up to
                // indirect form before registering.
                //
                // BUG O: on a second save, /Resources or /Resources/Font (or the
                // per-font entries) may already be PdfObjectReferences pointing
                // into the previous save's stale objects map. Dereference each
                // step before walking; re-register the underlying dicts/streams
                // in the current map. Without this, font references dangle and
                // text disappears.
                PdfBase resVal = pd.get("Resources");
                if (resVal instanceof PdfObjectReference) {
                    try { resVal = ((PdfObjectReference) resVal).dereference(); }
                    catch (IOException ignored) { resVal = null; }
                }
                if (resVal instanceof PdfDictionary) {
                    PdfBase fontDict = ((PdfDictionary) resVal).get("Font");
                    if (fontDict instanceof PdfObjectReference) {
                        try { fontDict = ((PdfObjectReference) fontDict).dereference(); }
                        catch (IOException ignored) { fontDict = null; }
                    }
                    if (fontDict instanceof PdfDictionary) {
                        // Snapshot keys because we mutate the dict while iterating.
                        java.util.List<PdfName> fontKeys =
                                new java.util.ArrayList<>(((PdfDictionary) fontDict).keySet());
                        for (PdfName fontName : fontKeys) {
                            PdfBase fontObj = ((PdfDictionary) fontDict).get(fontName.getName());
                            if (fontObj instanceof PdfObjectReference) {
                                try {
                                    fontObj = ((PdfObjectReference) fontObj).dereference();
                                } catch (IOException ignored) {
                                    fontObj = null;
                                }
                            }
                            if (fontObj instanceof PdfDictionary) {
                                PdfDictionary fontD = (PdfDictionary) fontObj;
                                objNum = liftStreamsToIndirect(fontD, objects, objNum);
                                PdfObjectKey existingFontKey = fontD.getObjectKey();
                                PdfObjectKey fKey;
                                if (existingFontKey != null
                                        && objects.get(existingFontKey) == fontD) {
                                    fKey = existingFontKey;
                                } else {
                                    fKey = new PdfObjectKey(++objNum, 0);
                                    fontD.setObjectKey(fKey);
                                    objects.put(fKey, fontD);
                                }
                                ((PdfDictionary) fontDict).set(fontName.getName(),
                                        new PdfObjectReference(fKey, k -> objects.get(k)));
                            }
                        }
                    }
                }
            }
        }

        pagesDict.set(PdfName.KIDS, kids);
        pagesDict.set(PdfName.COUNT,
                org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(kids.size()));
        // Allocate a fresh key: 1 may already be owned by a pendingImports entry
        // because the target document's object-number allocator also starts at 1.
        PdfObjectKey pagesKey = new PdfObjectKey(++objNum, 0);
        pagesDict.setObjectKey(pagesKey);
        objects.put(pagesKey, pagesDict);

        // Catalog
        PdfDictionary catalog = new PdfDictionary();
        catalog.set(PdfName.TYPE, PdfName.of("Catalog"));
        catalog.set(PdfName.PAGES, new PdfObjectReference(pagesKey, k -> objects.get(k)));
        // Preserve user-stamped catalog entries (e.g. /Names → /EmbeddedFiles
        // for portfolios, /Collection, /OpenAction, /PageLayout, /PageMode).
        // saveNewDocument builds a fresh catalog from scratch, so without this
        // sync any setter that wrote into inMemoryCatalog before save would
        // be silently dropped.
        if (inMemoryCatalog != null) {
            for (PdfName extraKey : inMemoryCatalog.keySet()) {
                String n = extraKey.getName();
                if ("Type".equals(n) || "Pages".equals(n)) continue;
                catalog.set(extraKey, inMemoryCatalog.get(n));
            }
        }
        PdfObjectKey catalogKey = new PdfObjectKey(++objNum, 0);
        catalog.setObjectKey(catalogKey);
        objects.put(catalogKey, catalog);

        // AcroForm
        if (form != null && form.getCount() > 0) {
            PdfDictionary acroForm = form.getPdfDictionary();
            PdfArray fieldRefs = new PdfArray();
            for (org.aspose.pdf.forms.Field field : form.getFields()) {
                PdfDictionary fieldDict = field.getPdfDictionary();
                PdfObjectKey fieldKey = fieldDict.getObjectKey();
                if (fieldKey == null) {
                    fieldKey = new PdfObjectKey(++objNum, 0);
                    fieldDict.setObjectKey(fieldKey);
                }
                objects.put(fieldKey, fieldDict);
                PdfObjectReference fieldRef = new PdfObjectReference(fieldKey, k -> objects.get(k));
                fieldRefs.add(fieldRef);

                Page fieldPage = field.getPage();
                if (fieldPage != null) {
                    fieldDict.set(PdfName.of("P"), fieldPage.getPdfDictionary());
                    PdfBase annotsVal = fieldPage.getPdfDictionary().get(PdfName.ANNOTS);
                    if (annotsVal instanceof PdfArray) {
                        PdfArray annots = (PdfArray) annotsVal;
                        boolean replaced = false;
                        for (int i = 0; i < annots.size(); i++) {
                            PdfBase item = annots.get(i);
                            // The widget may already be in /Annots as the dict itself
                            // OR as an indirect reference to it (AnnotationCollection.add
                            // stores a reference — see PDFNET_42398). Match both, else a
                            // field added via the form ends up listed in /Annots twice.
                            boolean match = item == fieldDict
                                    || (item instanceof PdfObjectReference
                                        && fieldKey.equals(((PdfObjectReference) item).getKey()));
                            if (match) {
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
            acroForm.set(PdfName.of("Fields"), fieldRefs);
            PdfObjectKey acroFormKey = new PdfObjectKey(++objNum, 0);
            acroForm.setObjectKey(acroFormKey);
            objects.put(acroFormKey, acroForm);
            catalog.set(PdfName.of("AcroForm"), new PdfObjectReference(acroFormKey, k -> objects.get(k)));
        }

        // Sync XMP metadata if present
        if (metadata != null) {
            objNum = syncXmpToCatalog(catalog, objects, objNum);
        }

        // Document Info
        if (info != null && info.getPdfDictionary() != null && info.getPdfDictionary().size() > 0) {
            PdfDictionary infoDict = info.getPdfDictionary();
            PdfObjectKey infoKey = new PdfObjectKey(++objNum, 0);
            infoDict.setObjectKey(infoKey);
            objects.put(infoKey, infoDict);
            trailerInfoRef = new PdfObjectReference(infoKey, k -> objects.get(k));
        }

        // Write outlines (bookmarks) if any were added
        if (outlines != null && outlines.getCount() > 0) {
            PdfDictionary outlinesDict = outlines.getPdfDictionary();
            PdfObjectKey outlinesKey = new PdfObjectKey(++objNum, 0);
            outlinesDict.setObjectKey(outlinesKey);
            objects.put(outlinesKey, outlinesDict);
            catalog.set(PdfName.of("Outlines"),
                    new PdfObjectReference(outlinesKey, k -> objects.get(k)));
            // Register each outline item (and children) as indirect objects
            objNum = registerOutlineItems(outlines, objects, objNum);
        }

        // Trailer
        PdfDictionary trailer = new PdfDictionary();
        trailer.set(PdfName.ROOT, new PdfObjectReference(catalogKey, k -> objects.get(k)));
        if (trailerInfoRef != null) {
            trailer.set(PdfName.INFO, trailerInfoRef);
        }
        trailer.set(PdfName.of("Size"),
                org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(objects.size() + 1));

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
    private int promoteFieldKidsToIndirect(PdfDictionary fieldDict,
                                           PdfObjectReference parentRef,
                                           Page fieldPage,
                                           Map<PdfObjectKey, PdfBase> objects,
                                           int objNum) {
        PdfBase kidsVal = fieldDict.get("Kids");
        if (kidsVal instanceof PdfObjectReference) {
            try { kidsVal = ((PdfObjectReference) kidsVal).dereference(); }
            catch (IOException e) { return objNum; }
        }
        if (!(kidsVal instanceof PdfArray)) return objNum;
        PdfArray kids = (PdfArray) kidsVal;

        PdfArray pageAnnots = null;
        if (fieldPage != null) {
            PdfBase annotsVal = fieldPage.getPdfDictionary().get(PdfName.ANNOTS);
            if (annotsVal instanceof PdfArray) {
                pageAnnots = (PdfArray) annotsVal;
            }
        }

        for (int i = 0; i < kids.size(); i++) {
            PdfBase kid = kids.get(i);
            // On a second save the entry is already a PdfObjectReference bound
            // to the *previous* save's (now-stale) objects map. Capture the old
            // key so we can rewrite the matching /Annots entry, then dereference
            // to the underlying widget dict.
            PdfObjectKey oldKey = null;
            if (kid instanceof PdfObjectReference) {
                oldKey = ((PdfObjectReference) kid).getKey();
                try {
                    PdfBase resolved = ((PdfObjectReference) kid).dereference();
                    if (resolved instanceof PdfDictionary) {
                        kid = resolved;
                    } else {
                        continue;
                    }
                } catch (IOException e) {
                    continue;
                }
            }
            if (!(kid instanceof PdfDictionary)) {
                continue;
            }
            PdfDictionary kidDict = (PdfDictionary) kid;
            // Always allocate a fresh key in THIS save's numbering. Reusing the
            // prior save's key collides with whatever object now occupies it in
            // the freshly-numbered map (manifests as empty kid widgets →
            // "Bad bounding box"). The fresh ref is propagated to /Kids and
            // /Annots below so nothing dangles.
            PdfObjectKey kidKey = new PdfObjectKey(++objNum, 0);
            kidDict.setObjectKey(kidKey);
            objects.put(kidKey, kidDict);
            kidDict.set(PdfName.of("Parent"), parentRef);
            final PdfObjectKey fKidKey = kidKey;
            final PdfObjectKey fOldKey = oldKey;
            PdfObjectReference kidRef = new PdfObjectReference(fKidKey, k -> objects.get(k));
            kids.set(i, kidRef);

            // The kid widget must be reachable as a page annotation so viewers
            // render it. Replace any entry matching this kid (by identity or by
            // its previous key) with the fresh ref; append if absent.
            if (pageAnnots != null) {
                boolean present = false;
                for (int j = 0; j < pageAnnots.size(); j++) {
                    PdfBase a = pageAnnots.get(j);
                    boolean match = (a == kidDict)
                            || (a instanceof PdfObjectReference
                                && (fKidKey.equals(((PdfObjectReference) a).getKey())
                                    || (fOldKey != null
                                        && fOldKey.equals(((PdfObjectReference) a).getKey()))));
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
     * Syncs XMP metadata to the catalog as a /Metadata PdfStream.
     * Returns the updated max object number.
     */
    /**
     * Recursively registers outline items as indirect objects in the objects map.
     * Returns the updated max object number.
     */
    private static int registerOutlineItems(Iterable<OutlineItemCollection> items,
                                             Map<PdfObjectKey, PdfBase> objects, int maxObjNum) {
        for (OutlineItemCollection item : items) {
            PdfDictionary itemDict = item.getPdfDictionary();
            PdfObjectKey itemKey = new PdfObjectKey(++maxObjNum, 0);
            itemDict.setObjectKey(itemKey);
            objects.put(itemKey, itemDict);
            // Recursively register child items
            if (item.getCount() > 0) {
                maxObjNum = registerOutlineItems(item, objects, maxObjNum);
            }
        }
        return maxObjNum;
    }

    private int syncXmpToCatalog(PdfDictionary catalog, Map<PdfObjectKey, PdfBase> objects,
                                   int maxObjNum) {
        // Use XmpMetadata.getBytes() so that an unmodified setXmpMetadata
        // payload is preserved byte-for-byte instead of being re-serialised
        // through XmpWriter (which would drop anything outside <rdf:RDF>).
        byte[] xmpBytes = metadata.getBytes();
        if (xmpBytes.length == 0) return maxObjNum;

        // Create or update metadata stream
        PdfStream metaStream = new PdfStream();
        metaStream.set(PdfName.TYPE, PdfName.of("Metadata"));
        metaStream.set(PdfName.SUBTYPE, PdfName.of("XML"));
        metaStream.setDecodedData(xmpBytes);
        // XMP metadata stream MUST NOT be compressed (for readability)
        // No filter set → written as raw bytes

        PdfObjectKey metaKey = new PdfObjectKey(++maxObjNum, 0);
        metaStream.setObjectKey(metaKey);
        objects.put(metaKey, metaStream);
        catalog.set(PdfName.of("Metadata"),
                new PdfObjectReference(metaKey, k -> objects.get(k)));
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

        PdfDictionary d = new PdfDictionary();
        d.set(PdfName.of("Filter"), PdfName.of(customHandler.getFilter()));
        if (customHandler.getSubFilter() != null && !customHandler.getSubFilter().isEmpty()) {
            d.set(PdfName.of("SubFilter"), PdfName.of(customHandler.getSubFilter()));
        }
        d.set(PdfName.of("V"), PdfInteger.valueOf(customHandler.getVersion()));
        d.set(PdfName.of("R"), PdfInteger.valueOf(customHandler.getRevision()));
        d.set(PdfName.of("Length"), PdfInteger.valueOf(customHandler.getKeyLength()));
        d.set(PdfName.of("P"), PdfInteger.valueOf(permissions));
        d.set(PdfName.of("O"), new PdfString(ownerKey != null ? ownerKey : new byte[0]));
        d.set(PdfName.of("U"), new PdfString(userKey != null ? userKey : new byte[0]));
        byte[] perms = customHandler.encryptPermissions(permissions);
        if (perms != null && perms.length > 0) {
            d.set(PdfName.of("Perms"), new PdfString(perms));
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
            PdfDictionary trailer = parser.getTrailer();
            if (trailer != null) {
                PdfBase idObj = trailer.get(PdfName.of("ID"));
                if (idObj instanceof PdfArray && ((PdfArray) idObj).size() > 0) {
                    PdfBase first = ((PdfArray) idObj).get(0);
                    if (first instanceof PdfString) {
                        byte[] id = ((PdfString) first).getBytes();
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
    private int applyPendingEncryption(PDFWriter writer, PdfDictionary trailer,
                                        Map<PdfObjectKey, PdfBase> objects, int maxObjNum) {
        if (pendingEncryptor == null || pendingEncDict == null) {
            return maxObjNum;
        }

        // Register /Encrypt dict as indirect object
        PdfObjectKey encDictKey = new PdfObjectKey(++maxObjNum, 0);
        objects.put(encDictKey, pendingEncDict.getPdfDictionary());

        // Configure writer
        writer.setEncryptor(pendingEncryptor, encDictKey);

        // Add /Encrypt reference to trailer
        trailer.set(PdfName.of("Encrypt"), new PdfObjectReference(encDictKey, k -> objects.get(k)));

        // Add /ID to trailer (required for encrypted documents per §7.6.1)
        PdfArray idArray = new PdfArray();
        idArray.add(new PdfString(pendingDocumentId));
        idArray.add(new PdfString(pendingDocumentId));
        trailer.set(PdfName.of("ID"), idArray);

        // For AES-256 (V=5/R=6), Adobe Reader requires the catalog to declare
        // Adobe Extension Level 3 against base version 1.7 — otherwise it
        // rejects the file with "the document cannot be decrypted" even when
        // the cryptography is fully valid. Other readers ignore this entry.
        if (pendingEncDict.getV() >= 5) {
            PdfBase rootRef = trailer.get(PdfName.ROOT);
            if (rootRef instanceof PdfObjectReference) {
                PdfObjectKey rootKey = ((PdfObjectReference) rootRef).getKey();
                PdfBase catalogObj = objects.get(rootKey);
                if (catalogObj instanceof PdfDictionary) {
                    PdfDictionary catalog = (PdfDictionary) catalogObj;
                    PdfDictionary adbe = new PdfDictionary();
                    adbe.set(PdfName.of("BaseVersion"), PdfName.of("1.7"));
                    adbe.set(PdfName.of("ExtensionLevel"), PdfInteger.valueOf(3));
                    PdfDictionary extensions = new PdfDictionary();
                    extensions.set(PdfName.of("ADBE"), adbe);
                    catalog.set(PdfName.of("Extensions"), extensions);
                }
            }
        }

        return maxObjNum;
    }

    /**
     * Reuses the original file encryption when saving an already encrypted document
     * unless the caller explicitly requested new encryption or decryption.
     */
    private void configureExistingEncryption(PDFWriter writer, PdfDictionary trailer) throws IOException {
        if (pendingEncryptor != null || pendingEncDict != null || parser == null || !parser.isEncrypted()) {
            return;
        }
        PDFDecryptor decryptor = parser.getDecryptor();
        if (decryptor == null || !decryptor.isActive()) {
            return;
        }
        PdfBase encryptRef = trailer.get(PdfName.of("Encrypt"));
        PdfObjectKey encryptDictKey = null;
        PdfBase encryptObj;
        if (encryptRef instanceof PdfObjectReference) {
            encryptObj = parser.resolveReference(encryptRef);
            encryptDictKey = ((PdfObjectReference) encryptRef).getKey();
        } else {
            // Legacy files may inline the encryption dictionary directly in
            // the trailer. Skipping the encryptor here would write plaintext
            // bodies under a trailer that still advertises /Encrypt — readers
            // would then "decrypt" the plaintext into garbage.
            encryptObj = encryptRef;
        }
        if (!(encryptObj instanceof PdfDictionary)) {
            return;
        }
        PDFEncryptionDict encDict = new PDFEncryptionDict((PdfDictionary) encryptObj);
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
            for (PdfObjectKey key : parser.getAllObjectKeys()) {
                try {
                    PdfBase obj = parser.getObject(key);
                    materializeDecryptedStreams(obj);
                } catch (IOException e) {
                    LOG.fine(() -> "Skipping object during decrypt materialization " + key + ": " + e.getMessage());
                }
            }
            PdfDictionary trailer = parser.getTrailer();
            if (trailer != null) {
                trailer.set(PdfName.of("Encrypt"), null);
            }
        }
        requestFullRewrite();
    }

    private void materializeDecryptedStreams(PdfBase obj) throws IOException {
        if (obj == null) {
            return;
        }
        if (obj instanceof PdfStream) {
            PdfStream stream = (PdfStream) obj;
            if (stream.hasActiveDecryptor()) {
                // Decrypt in place — do NOT decode-then-re-encode through the
                // filter chain. Filter encoders are not implemented for every
                // filter (JBIG2, CCITTFax, DCT, JPX); the decode-then-encode
                // path crashes the save when an encrypted PDF contains such
                // streams (e.g. PDFNEWNET-34092 — linearized + JBIG2 imagery).
                stream.materializeDecryption();
            }
            for (PdfName key : stream.keySet()) {
                materializeDecryptedStreams(stream.get(key));
            }
            return;
        }
        if (obj instanceof PdfDictionary) {
            PdfDictionary dict = (PdfDictionary) obj;
            for (PdfName key : dict.keySet()) {
                materializeDecryptedStreams(dict.get(key));
            }
            return;
        }
        if (obj instanceof PdfArray) {
            PdfArray array = (PdfArray) obj;
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
        PdfDictionary catalog = getCatalog();
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
        if (options.getFormat() != null && options.getFormat().isPdfA()) {
            isolatePageGraphicsState();
            materializeInheritedPageResources();
            // PDF/A (ISO 19005) forbids a hybrid-reference cross-reference: the
            // file must use either a single classic xref table or a single xref
            // stream, never a table whose trailer points at a parallel /XRefStm.
            // A hybrid source would otherwise have its /XRefStm shape preserved on
            // full rewrite (PDFNET-45599); clearing it here keeps the converted
            // document compliant (PDFNET-58212).
            PdfDictionary convTrailer = parser.getTrailer();
            if (convTrailer != null) {
                convTrailer.remove(PdfName.of("XRefStm"));
            }
        }
        if (options.getFormat() == PdfFormat.PDF_UA_1) {
            normalizeStructureRootToDocument();
        }
        PdfBase metadataRef = parser.getCatalog().get(PdfName.of("Metadata"));
        PdfBase metadataObject = parser.resolveReference(metadataRef);
        if (metadataObject instanceof PdfStream) {
            metadata = new XmpMetadata(((PdfStream) metadataObject).getDecodedData());
        }
        return pdfaCompliant;
    }

    /**
     * Wraps every page's content in a balanced {@code q}/{@code Q} (save/restore
     * graphics state) pair as part of PDF/A conversion.
     * <p>
     * A page whose content stream opens with state-changing operators (e.g. a
     * leading {@code cm}) without first saving the graphics state leaks that
     * state; PDF/A processors expect each page's content to leave the graphics
     * state as it found it. Prepending {@code q} and appending {@code Q} isolates
     * the page content, adding exactly two operators per page. This mirrors
     * Aspose's conversion behaviour (PDFNET-42549, PDFNET-42676).
     * </p>
     */
    private void isolatePageGraphicsState() throws IOException {
        for (Page page : getPages()) {
            try {
                OperatorCollection ops = page.getContents();
                if (ops == null || ops.size() == 0) {
                    continue;
                }
                ops.addAt(0, new org.aspose.pdf.operators.GSave());
                ops.add(new org.aspose.pdf.operators.GRestore());
                page.markContentsDirty();
            } catch (IOException e) {
                LOG.warning(() -> "isolatePageGraphicsState: could not wrap page content: "
                        + e.getMessage());
            }
        }
    }

    /**
     * Writes each page's EFFECTIVE (possibly tree-inherited, §7.7.3.4)
     * {@code /Resources} directly into the page dictionary as part of PDF/A
     * conversion, mirroring Aspose (PDFNET_49802: after conversion every page
     * must carry its own /Resources entry).
     */
    private void materializeInheritedPageResources() {
        try {
            for (Page page : getPages()) {
                PdfDictionary pd = page.getPdfDictionary();
                if (pd.get("Resources") != null) {
                    continue;
                }
                Resources effective = page.getResources();
                PdfDictionary resDict = effective != null ? effective.getPdfDictionary() : null;
                pd.set(PdfName.of("Resources"), resDict != null ? resDict : new PdfDictionary());
            }
        } catch (IOException | RuntimeException e) {
            LOG.warning(() -> "materializeInheritedPageResources failed: " + e.getMessage());
        }
    }

    /**
     * PDF/UA-1 (ISO 14289-1 §7.1) requires the logical structure to be rooted at a single
     * top-level {@code Document} structure element. A tagged source may instead root its tree
     * at a grouping element such as {@code Part} (a legal PDF structure type, but not the
     * conventional PDF/UA document root). When converting to PDF/UA, retype a lone non-Document
     * root grouping element to {@code Document}, mirroring Adobe/Aspose conversion. No-op when
     * the root is already {@code Document} or there is no struct tree.
     */
    private void normalizeStructureRootToDocument() {
        try {
            PdfBase str = parser.resolveReference(parser.getCatalog().get(PdfName.of("StructTreeRoot")));
            if (!(str instanceof PdfDictionary)) {
                return;
            }
            PdfBase k = parser.resolveReference(((PdfDictionary) str).get(PdfName.of("K")));
            PdfDictionary root = null;
            if (k instanceof PdfDictionary) {
                root = (PdfDictionary) k;
            } else if (k instanceof PdfArray && ((PdfArray) k).size() == 1) {
                // a single top-level element wrapped in a one-entry /K array
                PdfBase only = parser.resolveReference(((PdfArray) k).get(0));
                if (only instanceof PdfDictionary) {
                    root = (PdfDictionary) only;
                }
            }
            if (root == null) {
                return;
            }
            PdfBase s = root.get(PdfName.of("S"));
            String type = s instanceof PdfName ? ((PdfName) s).getName() : null;
            if (type != null && !"Document".equals(type)) {
                root.set(PdfName.of("S"), PdfName.of("Document"));
            }
        } catch (RuntimeException | IOException e) {
            LOG.warning(() -> "normalizeStructureRootToDocument: " + e.getMessage());
        }
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
     * {@link PdfStream} value it encounters to an indirect object —
     * required by §7.3.8 ("Stream objects ... are always indirect").
     * Returns the new {@code objNum} after consuming as many object keys
     * as nested streams were found.
     */
    private int liftStreamsToIndirect(PdfDictionary dict,
                                      Map<PdfObjectKey, PdfBase> objects,
                                      int objNum) {
        // Snapshot keys — we will mutate the dictionary while iterating.
        java.util.List<PdfName> keys = new java.util.ArrayList<>(dict.keySet());
        for (PdfName key : keys) {
            PdfBase value = dict.get(key);
            // BUG O: on second save, sub-dicts/streams may already be
            // PdfObjectReferences pointing into the previous save's stale
            // objects map. Resolve through the reference so we can re-register
            // the underlying object in the current map.
            PdfBase resolvedValue = value;
            boolean wasReference = false;
            if (value instanceof PdfObjectReference) {
                try {
                    resolvedValue = ((PdfObjectReference) value).dereference();
                    wasReference = true;
                } catch (IOException ignored) {
                    resolvedValue = null;
                }
            }
            if (resolvedValue instanceof PdfStream) {
                PdfStream stream = (PdfStream) resolvedValue;
                PdfObjectKey existing = stream.getObjectKey();
                PdfObjectKey sKey;
                if (existing != null && objects.get(existing) == stream) {
                    sKey = existing;
                } else {
                    sKey = new PdfObjectKey(++objNum, 0);
                    stream.setObjectKey(sKey);
                    objects.put(sKey, stream);
                }
                if (wasReference || existing == null || objects.get(existing) != stream) {
                    dict.set(key, new PdfObjectReference(sKey, k -> objects.get(k)));
                }
            } else if (resolvedValue instanceof PdfDictionary) {
                objNum = liftStreamsToIndirect((PdfDictionary) resolvedValue, objects, objNum);
            } else if (resolvedValue instanceof org.aspose.pdf.engine.pdfobjects.PdfArray) {
                org.aspose.pdf.engine.pdfobjects.PdfArray arr =
                        (org.aspose.pdf.engine.pdfobjects.PdfArray) resolvedValue;
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase item = arr.get(i);
                    PdfBase resolvedItem = item;
                    boolean itemWasRef = false;
                    if (item instanceof PdfObjectReference) {
                        try {
                            resolvedItem = ((PdfObjectReference) item).dereference();
                            itemWasRef = true;
                        } catch (IOException ignored) {
                            resolvedItem = null;
                        }
                    }
                    if (resolvedItem instanceof PdfDictionary) {
                        objNum = liftStreamsToIndirect((PdfDictionary) resolvedItem, objects, objNum);
                    } else if (resolvedItem instanceof PdfStream) {
                        PdfStream stream = (PdfStream) resolvedItem;
                        PdfObjectKey existing = stream.getObjectKey();
                        PdfObjectKey sKey;
                        if (existing != null && objects.get(existing) == stream) {
                            sKey = existing;
                        } else {
                            sKey = new PdfObjectKey(++objNum, 0);
                            stream.setObjectKey(sKey);
                            objects.put(sKey, stream);
                        }
                        if (itemWasRef || existing == null || objects.get(existing) != stream) {
                            arr.set(i, new PdfObjectReference(sKey, k -> objects.get(k)));
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

            Paragraphs expanded = expandMultiFrameImages(paragraphs);
            if (expanded != paragraphs) {
                page.setParagraphs(expanded);
                paragraphs = expanded;
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

    /**
     * Expands every multi-frame image paragraph (multi-page TIFF) into one
     * {@link Image} per decodable frame, so pagination emits one page per
     * frame — matching Aspose.PDF, which turns a 20-frame TIFF added as a
     * single Image into a 20-page document (PDFNET-38363). Frames after the
     * first are marked {@link BaseParagraph#setInNewPage(boolean) InNewPage}.
     * Images with an explicit {@link Image#getSelectedFrame() selected frame}
     * are left untouched. Returns the original collection when nothing
     * needed expanding.
     */
    private Paragraphs expandMultiFrameImages(Paragraphs paragraphs) {
        Paragraphs expanded = null;
        int index = 0;
        for (BaseParagraph paragraph : paragraphs) {
            index++;
            if (!(paragraph instanceof Image) || ((Image) paragraph).getSelectedFrame() >= 0) {
                if (expanded != null) expanded.add(paragraph);
                continue;
            }
            Image image = (Image) paragraph;
            byte[] raw = readImageParagraphBytes(image);
            int[] frames = raw == null ? new int[0]
                    : org.aspose.pdf.engine.layout.LayoutEngine.getDecodableImageFrames(raw);
            if (frames.length <= 1) {
                if (expanded != null) expanded.add(paragraph);
                continue;
            }
            if (expanded == null) {
                // Copy the paragraphs processed so far, untouched.
                expanded = new Paragraphs();
                int copied = 0;
                for (BaseParagraph earlier : paragraphs) {
                    if (++copied >= index) break;
                    expanded.add(earlier);
                }
            }
            for (int i = 0; i < frames.length; i++) {
                Image frameImage = cloneImageForFrame(image, raw, frames[i]);
                if (i > 0) frameImage.setInNewPage(true);
                expanded.add(frameImage);
            }
        }
        return expanded != null ? expanded : paragraphs;
    }

    /** Reads an Image paragraph's source bytes (file path or stream) without consuming the original stream twice. */
    private byte[] readImageParagraphBytes(Image image) {
        try {
            if (image.getFile() != null) {
                return java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(image.getFile()));
            }
            if (image.getImageStream() != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = image.getImageStream().read(buf)) > 0) out.write(buf, 0, n);
                byte[] raw = out.toByteArray();
                // The stream is consumed; replace it so a later layout pass
                // (single-frame case) can still read the bytes.
                image.setImageStream(new java.io.ByteArrayInputStream(raw));
                return raw;
            }
        } catch (IOException e) {
            // fall through — undecodable sources keep the placeholder path
        }
        return null;
    }

    /** Clones an Image paragraph for one frame of its multi-frame source. */
    private Image cloneImageForFrame(Image source, byte[] raw, int frame) {
        Image copy = new Image();
        if (source.getFile() != null) {
            copy.setFile(source.getFile());
        } else {
            copy.setImageStream(new java.io.ByteArrayInputStream(raw));
        }
        copy.setSelectedFrame(frame);
        copy.setFixWidth(source.getFixWidth());
        copy.setFixHeight(source.getFixHeight());
        copy.setImageType(source.getImageType());
        copy.setTitle(source.getTitle());
        copy.setMargin(copyMarginInfo(source.getMargin()));
        copy.setHorizontalAlignment(source.getHorizontalAlignment());
        return copy;
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
            if ((paragraph.isInNewPage() || shouldStartNewPageAfter(previousParagraph, paragraph))
                    && !chunks.get(chunks.size() - 1).isEmpty()) {
                chunks.add(new Paragraphs());
                currentChunk = chunks.get(chunks.size() - 1);
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
                PdfDictionary catalog = getCatalog();
                org.aspose.pdf.engine.pdfa.XmpMetadataHandler handler =
                        org.aspose.pdf.engine.pdfa.XmpMetadataHandler.readFromCatalog(catalog);
                if (handler.hasPdfAId()
                        || (catalog.get(PdfName.of("Metadata")) != null
                        && catalog.get(PdfName.of("OutputIntents")) != null)) {
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

    private PdfDictionary recoverPagesDictionaryFromObjects() throws IOException {
        if (parser == null) {
            return null;
        }
        java.util.TreeMap<Integer, PdfDictionary> pageObjects = new java.util.TreeMap<>();
        for (PdfObjectKey key : parser.getAllObjectKeys()) {
            try {
                PdfBase candidate = parser.getObject(key);
                if (candidate instanceof PdfDictionary) {
                    PdfDictionary dict = (PdfDictionary) candidate;
                    String type = dict.getType();
                    if ("Page".equals(type)
                            || (dict.get(PdfName.MEDIABOX) != null && dict.get(PdfName.PARENT) != null)) {
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
        PdfArray kids = new PdfArray();
        PdfDictionary recoveredPages = new PdfDictionary();
        recoveredPages.set(PdfName.TYPE, PdfName.PAGES);
        recoveredPages.set(PdfName.KIDS, kids);
        recoveredPages.set(PdfName.COUNT, PdfInteger.valueOf(pageObjects.size()));
        for (PdfDictionary pageDict : pageObjects.values()) {
            pageDict.set(PdfName.PARENT, recoveredPages);
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
        if (pdfFormat != null) {
            return pdfFormat;
        }
        // A PDF 2.0 document (header 2.0 or catalog /Version /2.0) reports its
        // format even when never explicitly converted. Earlier versions keep the
        // historical null-until-converted behaviour (PDF/A inference keys off the
        // pdfFormat FIELD, which this getter does not mutate).
        if ("2.0".equals(getVersion())) {
            return PdfFormat.v_2_0;
        }
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
            p.flushPendingParagraphs();
            p.flushPageInfoIfNeeded();
            persistNewLayers(p);
            if (p.isContentsDirty()) {
                editedPageContentsThisSave = true;
            }
            p.flushContentsIfDirty();
        }
    }

    /**
     * Renders paragraph and header/footer overlays for the pages of a loaded
     * document. The new-document path ({@code saveNewDocument} ->
     * {@link org.aspose.pdf.engine.layout.LayoutEngine#layout}) already renders
     * these from the paragraph list, so this only runs for parser-backed
     * documents (to avoid double rendering). Each overlay is a Form XObject
     * appended to the page's existing content, registered in
     * {@link #pendingImports}.
     * <p>
     * PDFNET-38279 (header/footer) and the paragraph counterpart: a page that
     * the caller built via {@code getParagraphs().add(...)} — most commonly a
     * page freshly appended to a loaded document — would otherwise be written
     * with no content, silently dropping the authored text.
     * </p>
     *
     * @throws IOException if content-stream generation fails
     */
    private void renderPendingPageOverlays() throws IOException {
        if (parser == null || pages == null) {
            return;
        }
        int totalPages = pages.size();
        int pageNum = 0;
        for (Page page : pages) {
            pageNum++;
            // Runs before the header/footer overlay so page-numbering furniture
            // sits on top of the body.
            if (page.getParagraphs() != null && !page.getParagraphs().isEmpty()) {
                page.applyParagraphOverlay();
            }
            if (page.getHeader() != null || page.getFooter() != null) {
                page.applyHeaderFooterOverlay(pageNum, totalPages);
            }
        }
    }

    /**
     * Serialises layers the user added through {@link Page#getLayers()} /
     * {@link Page#setLayers(java.util.List)} into the document
     * (ISO 32000-1:2008, §8.11): registers the OCG dictionary as an indirect
     * object, references it from the page's {@code /Resources /Properties}
     * under the layer id, lists it in the catalog's
     * {@code /OCProperties /OCGs} (+ {@code /D /Order}), and wraps the layer's
     * operators into an {@code /OC /id BDC … EMC} block appended to the page
     * content. Layers whose id already appears in {@code /Properties} (i.e.
     * layers read from the file, or already persisted by a previous save)
     * are left untouched, so the method is idempotent.
     */
    private void persistNewLayers(Page p) {
        java.util.List<Layer> layers = p.peekLayers();
        if (layers == null || layers.isEmpty()) return;
        for (Layer layer : layers) {
            String id = layer.getId();
            if (id == null || id.isEmpty()) continue;
            try {
                PdfDictionary pageDict = p.getPdfDictionary();
                PdfBase resVal = deref(pageDict.get("Resources"));
                PdfDictionary res;
                if (resVal instanceof PdfDictionary) {
                    res = (PdfDictionary) resVal;
                } else {
                    res = new PdfDictionary();
                    pageDict.set(PdfName.of("Resources"), res);
                }
                PdfBase propsVal = deref(res.get("Properties"));
                PdfDictionary props;
                if (propsVal instanceof PdfDictionary) {
                    props = (PdfDictionary) propsVal;
                } else {
                    props = new PdfDictionary();
                    res.set(PdfName.of("Properties"), props);
                }
                if (props.get(id) != null) continue; // from file / already saved

                PdfObjectReference ocgRef = registerImportedObject(layer.getPdfDictionary());
                props.set(PdfName.of(id), ocgRef);
                registerLayerInOcProperties(ocgRef);

                if (!layer.getContents().isEmpty()) {
                    OperatorCollection ops = p.getContents();
                    ops.add(new org.aspose.pdf.operators.BDC("OC", PdfName.of(id)));
                    for (Operator op : layer.getContents()) {
                        ops.add(op);
                    }
                    ops.add(new org.aspose.pdf.operators.EMC());
                    p.markContentsDirty();
                }
            } catch (IOException e) {
                LOG.warning(() -> "Failed to persist layer '" + id + "': " + e.getMessage());
            }
        }
    }

    /** Adds an OCG reference to the catalog's /OCProperties (§8.11.4.2). */
    private void registerLayerInOcProperties(PdfObjectReference ocgRef) throws IOException {
        PdfDictionary catalog = getCatalog();
        if (catalog == null) return;
        PdfBase ocpVal = deref(catalog.get("OCProperties"));
        PdfDictionary ocp;
        if (ocpVal instanceof PdfDictionary) {
            ocp = (PdfDictionary) ocpVal;
        } else {
            ocp = new PdfDictionary();
            catalog.set(PdfName.of("OCProperties"), ocp);
        }
        PdfBase ocgsVal = deref(ocp.get("OCGs"));
        PdfArray ocgs;
        if (ocgsVal instanceof PdfArray) {
            ocgs = (PdfArray) ocgsVal;
        } else {
            ocgs = new PdfArray();
            ocp.set(PdfName.of("OCGs"), ocgs);
        }
        ocgs.add(ocgRef);
        PdfBase dVal = deref(ocp.get("D"));
        PdfDictionary d;
        if (dVal instanceof PdfDictionary) {
            d = (PdfDictionary) dVal;
        } else {
            d = new PdfDictionary();
            ocp.set(PdfName.of("D"), d);
        }
        PdfBase orderVal = deref(d.get("Order"));
        PdfArray order;
        if (orderVal instanceof PdfArray) {
            order = (PdfArray) orderVal;
        } else {
            order = new PdfArray();
            d.set(PdfName.of("Order"), order);
        }
        order.add(ocgRef);
    }

    /** Dereferences an indirect reference, returning null on failure. */
    private static PdfBase deref(PdfBase value) {
        if (value instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) value).dereference();
            } catch (IOException e) {
                return null;
            }
        }
        return value;
    }

    /**
     * @return whether the source PDF used a cross-reference stream or a
     * hybrid-reference layout ({@code /XRefStm}). Incremental appends over such
     * files are not reliably resolved on reload, so a content-stream edit must
     * be persisted via full rewrite instead (BUG-TFA-REPLACE-001).
     */
    private boolean sourceUsesXRefStream() {
        if (parser == null) return false;
        PdfDictionary trailer = parser.getTrailer();
        if (trailer == null) return false;
        if (trailer.get(PdfName.of("XRefStm")) != null) return true;
        PdfBase type = trailer.get(PdfName.TYPE);
        return type instanceof PdfName && "XRef".equals(((PdfName) type).getName());
    }

    /**
     * Ensures the LIVE page-tree root (the dictionary {@link #getPages()} is
     * actually backed by) is reachable from the catalog before a full rewrite.
     * <p>
     * When the source file's catalog {@code /Pages} reference is broken, the
     * page tree is recovered by scanning
     * ({@link #recoverPagesDictionaryFromObjects()}); that synthetic root
     * exists only in memory while the catalog still holds the dangling
     * reference. A compact rewrite that walks the trailer
     * (optimizeResources' remove-unused-objects pass) would then reach no
     * page tree at all and silently drop every page object. Registering the
     * root as an indirect object and repointing the catalog makes the saved
     * file self-consistent. No-op for healthy documents.
     * </p>
     *
     * @param objects   the object map being assembled for the rewrite
     * @param maxObjNum the current maximum object number
     * @return the possibly-incremented maximum object number
     */
    private int registerLivePageTreeRoot(Map<PdfObjectKey, PdfBase> objects, int maxObjNum) {
        if (pages == null || parser == null) {
            return maxObjNum;
        }
        PdfDictionary root = pages.getPagesDictionary();
        if (root == null) {
            return maxObjNum;
        }
        if (root.getObjectKey() == null) {
            PdfObjectKey key = new PdfObjectKey(++maxObjNum, 0);
            root.setObjectKey(key);
            objects.put(key, root);
            try {
                // Serialized as "N 0 R" because the dict now carries a key.
                parser.getCatalog().set(PdfName.PAGES, root);
            } catch (IOException e) {
                LOG.fine(() -> "Cannot repoint catalog /Pages at recovered root: " + e.getMessage());
            }
        } else if (!objects.containsKey(root.getObjectKey())) {
            objects.put(root.getObjectKey(), root);
        }
        return maxObjNum;
    }

    /**
     * Ensures the LIVE catalog (what {@link PDFParser#getCatalog()} actually
     * resolves, possibly recovered by scanning when the trailer's
     * {@code /Root} was corrupt) is present in the object map under a key the
     * trailer references. Companion to {@link #registerLivePageTreeRoot}: a
     * rewrite that copies the broken trailer verbatim produces a file whose
     * {@code /Root} points at nothing even though the source document opened
     * fine through recovery. Mutates the parser trailer so every subsequent
     * copy inherits the repaired reference. No-op for healthy documents.
     */
    private int registerLiveCatalog(Map<PdfObjectKey, PdfBase> objects, int maxObjNum) {
        if (parser == null) {
            return maxObjNum;
        }
        PdfDictionary catalog;
        try {
            catalog = parser.getCatalog();
        } catch (IOException e) {
            return maxObjNum; // no catalog recoverable at all — nothing to repair
        }
        if (catalog == null) {
            return maxObjNum;
        }
        PdfObjectKey key = catalog.getObjectKey();
        if (key == null) {
            key = new PdfObjectKey(++maxObjNum, 0);
            catalog.setObjectKey(key);
        }
        if (objects.get(key) != catalog) {
            // Also covers a corrupt xref where the /Root key resolved to junk:
            // the recovered catalog wins the slot.
            objects.put(key, catalog);
        }
        PdfDictionary trailer = parser.getTrailer();
        if (trailer != null) {
            PdfBase rootRef = trailer.get(PdfName.of("Root"));
            PdfObjectKey rootKey = rootRef instanceof PdfObjectReference
                    ? ((PdfObjectReference) rootRef).getKey() : null;
            if (!key.equals(rootKey)) {
                // Serialized as "N 0 R" because the dict carries a key.
                trailer.set(PdfName.of("Root"), catalog);
            }
        }
        return maxObjNum;
    }

    private void retainReachableObjects(Map<PdfObjectKey, PdfBase> objects, PdfDictionary trailer) {
        Set<PdfObjectKey> reachable = new LinkedHashSet<>();
        java.util.IdentityHashMap<PdfBase, Boolean> visited = new java.util.IdentityHashMap<>();
        collectReachableFromCos(trailer, objects, reachable, visited);
        objects.keySet().retainAll(reachable);
    }

    private void collectReachableFromCos(PdfBase root, Map<PdfObjectKey, PdfBase> objects,
                                         Set<PdfObjectKey> reachable,
                                         java.util.IdentityHashMap<PdfBase, Boolean> visited) {
        // Iterative DFS: object graphs can be legitimately deep (a linked
        // chain of thousands of nodes) — recursion overflows the stack.
        java.util.ArrayDeque<PdfBase> stack = new java.util.ArrayDeque<>();
        if (root == null) {
            return;
        }
        stack.push(root);
        while (!stack.isEmpty()) {
            PdfBase value = stack.pop();
            if (value instanceof PdfObjectReference) {
                PdfObjectKey refKey = ((PdfObjectReference) value).getKey();
                if (refKey != null && objects.containsKey(refKey)) {
                    reachable.add(refKey);
                }
            }
            PdfBase resolved = resolveCosBase(value, objects);
            if (resolved == null || visited.put(resolved, Boolean.TRUE) != null) {
                continue;
            }
            PdfObjectKey key = resolved.getObjectKey();
            if (key != null && objects.containsKey(key)) {
                reachable.add(key);
            }
            if (resolved instanceof PdfDictionary) {
                for (Map.Entry<PdfName, PdfBase> entry : (PdfDictionary) resolved) {
                    if (entry.getValue() != null) {
                        stack.push(entry.getValue());
                    }
                }
            } else if (resolved instanceof PdfArray) {
                for (PdfBase item : (PdfArray) resolved) {
                    if (item != null) {
                        stack.push(item);
                    }
                }
            }
        }
    }

    /**
     * Prunes unused entries from page resource dictionaries.
     * <p>
     * Resource dictionaries may be SHARED between pages (§7.8.3) — commonly a
     * single {@code /Resources} referenced by every page (LibreOffice/Writer
     * output). A dictionary is therefore pruned exactly once, against the
     * UNION of the usage of every page that references it; pruning with a
     * single page's usage would drop entries that sibling pages still paint
     * (that bug blanked every image in multi-page shared-resources files).
     * A page whose content fails to parse marks its dictionary unprunable —
     * without its usage the union would be incomplete.
     * </p>
     */
    private void pruneUnusedResourcesAcrossPages() throws IOException {
        // Aspose semantics, established by the C# regression tests:
        //  - Declared-but-unused NON-STREAM resource entries survive
        //    OptimizeResources — PDFNET-48122 keeps a /ColorSpace entry (Cs6)
        //    the content never selects, PDFNET-48125 keeps unused /Font
        //    entries. So /Font, /ColorSpace, /ExtGState, /Pattern, /Shading
        //    and /Properties are NEVER pruned by default.
        //  - RemoveUnusedStreams DOES drop STREAM-valued resources no content
        //    paints: /XObject entries never referenced by a Do operator and
        //    /Pattern entries never selected (PDFNET-35187 asserts the exact
        //    surviving pattern count per extracted page). That is where the
        //    corpus-scale size wins come from (a page extracted from a large
        //    document typically drags the whole document's /XObject map with
        //    it).
        // -Dopenpdf.prune.entries=all restores aggressive all-category
        // pruning; =off disables entry pruning entirely.
        String pruneMode = System.getProperty("openpdf.prune.entries", "xobject");
        if ("off".equals(pruneMode)) {
            return;
        }
        final boolean pruneAllCategories = "all".equals(pruneMode) || "on".equals(pruneMode);
        java.util.IdentityHashMap<PdfDictionary, ResourceUsage> unions =
                new java.util.IdentityHashMap<>();
        for (int i = 1; i <= getPages().getCount(); i++) {
            Page page = getPages().get(i);
            if (page == null) {
                continue;
            }
            Resources resources = page.getResources();
            PdfDictionary resourcesDict = resources == null ? null : resources.getPdfDictionary();
            if (resourcesDict == null) {
                continue;
            }
            ResourceUsage pageUsage = new ResourceUsage();
            try {
                pageUsage = collectResourceUsage(page.getContents());
            } catch (Exception e) {
                pageUsage.unsafe = true;
                LOG.fine(() -> "Content parse failed; resources kept unpruned: " + e.getMessage());
            }
            ResourceUsage union = unions.get(resourcesDict);
            if (union == null) {
                unions.put(resourcesDict, pageUsage);
            } else {
                union.merge(pageUsage);
            }
        }

        // Category sub-dictionaries (/XObject, /Font, ...) are frequently
        // SHARED between different resources dictionaries — e.g. a page and a
        // form XObject both pointing at the same indirect /XObject map. Usage
        // must therefore be unioned PER SUB-DICTIONARY IDENTITY before any
        // entry is removed; pruning a shared sub-dict against one consumer's
        // usage blanks the other consumer's content. Phase 1 accumulates the
        // unions while discovering used form XObjects breadth-first; phase 2
        // prunes each sub-dict exactly once.
        java.util.Map<String, java.util.IdentityHashMap<PdfDictionary, Set<String>>> subUnions =
                new LinkedHashMap<>();
        java.util.Set<PdfDictionary> unprunable =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<PdfStream> formsSeen =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        // Queue entries: [form stream, resources dict of the CONTEXT the form
        // is painted in]. A form without its own /Resources inherits its
        // consumer's dictionary (§8.10.1), so its usage must be merged there.
        java.util.ArrayDeque<Object[]> formQueue = new java.util.ArrayDeque<>();

        for (java.util.Map.Entry<PdfDictionary, ResourceUsage> e : unions.entrySet()) {
            accumulateSubDictUsage(e.getKey(), e.getValue(), subUnions, unprunable, formQueue, formsSeen);
        }
        while (!formQueue.isEmpty()) {
            Object[] entry = formQueue.poll();
            PdfStream form = (PdfStream) entry[0];
            PdfDictionary parentResources = (PdfDictionary) entry[1];
            PdfDictionary formResources = resolveDictionary(form.get(PdfName.RESOURCES));
            if (formResources == null) {
                formResources = parentResources;
            }
            if (formResources == null) {
                continue;
            }
            ResourceUsage formUsage = new ResourceUsage();
            try {
                formUsage = collectResourceUsage(
                        org.aspose.pdf.engine.parser.ContentStreamParser.parseToCollection(form));
            } catch (Exception ex) {
                formUsage.unsafe = true;
                LOG.fine(() -> "Form content parse failed; resources kept unpruned: " + ex.getMessage());
            }
            accumulateSubDictUsage(formResources, formUsage, subUnions, unprunable, formQueue, formsSeen);
        }

        for (java.util.Map.Entry<String, java.util.IdentityHashMap<PdfDictionary, Set<String>>> cat
                : subUnions.entrySet()) {
            if (!pruneAllCategories && !"XObject".equals(cat.getKey())
                    && !"Pattern".equals(cat.getKey())) {
                continue;
            }
            for (java.util.Map.Entry<PdfDictionary, Set<String>> sub : cat.getValue().entrySet()) {
                PdfDictionary subDict = sub.getKey();
                if (unprunable.contains(subDict)) {
                    continue;
                }
                Set<String> used = sub.getValue();
                for (PdfName name : new java.util.ArrayList<>(subDict.keySet())) {
                    if (!used.contains(name.getName())) {
                        subDict.remove(name);
                    }
                }
                // An emptied sub-dict stays in place: its parent key may be
                // shared by resources dictionaries we know nothing about, and
                // an empty /XObject dict is spec-legal and harmless.
            }
        }
    }

    /**
     * Merges one consumer's usage into the per-sub-dictionary unions and
     * enqueues the form XObjects that consumer actually paints.
     */
    private void accumulateSubDictUsage(PdfDictionary resourcesDict, ResourceUsage usage,
            java.util.Map<String, java.util.IdentityHashMap<PdfDictionary, Set<String>>> subUnions,
            java.util.Set<PdfDictionary> unprunable,
            java.util.ArrayDeque<Object[]> formQueue,
            java.util.Set<PdfStream> formsSeen) {
        if (resourcesDict == null) {
            return;
        }
        String[] categories = {"Font", "ExtGState", "ColorSpace", "Pattern", "Shading", "Properties"};
        Set<?>[] usageSets = {
                usage.fonts, usage.extGState, usage.colorSpaces,
                usage.patterns, usage.shadings, usage.properties,
        };
        for (int c = 0; c < categories.length; c++) {
            String category = categories[c];
            PdfDictionary sub = resolveDictionary(resourcesDict.get(PdfName.of(category)));
            if (sub == null) {
                continue;
            }
            if (usage.unsafe) {
                unprunable.add(sub);
            } else {
                @SuppressWarnings("unchecked")
                Set<String> names = (Set<String>) usageSets[c];
                subUnions.computeIfAbsent(category, k -> new java.util.IdentityHashMap<>())
                        .computeIfAbsent(sub, k -> new LinkedHashSet<>())
                        .addAll(names);
            }
        }
        PdfDictionary xObjects = resolveDictionary(resourcesDict.get(PdfName.of("XObject")));
        if (xObjects == null) {
            return;
        }
        if (usage.unsafe) {
            unprunable.add(xObjects);
            // Every form child may still be painted — walk them all so their
            // own resources are still pruned against their own usage.
            for (PdfName name : new java.util.ArrayList<>(xObjects.keySet())) {
                enqueueForm(resolveStream(xObjects.get(name)), resourcesDict, formQueue, formsSeen);
            }
            return;
        }
        subUnions.computeIfAbsent("XObject", k -> new java.util.IdentityHashMap<>())
                .computeIfAbsent(xObjects, k -> new LinkedHashSet<>())
                .addAll(usage.xObjects);
        for (String used : usage.xObjects) {
            enqueueForm(resolveStream(xObjects.get(PdfName.of(used))), resourcesDict, formQueue, formsSeen);
        }
    }

    private void enqueueForm(PdfStream candidate, PdfDictionary parentResources,
            java.util.ArrayDeque<Object[]> formQueue, java.util.Set<PdfStream> formsSeen) {
        if (candidate != null && "Form".equals(candidate.getSubtype()) && formsSeen.add(candidate)) {
            formQueue.add(new Object[]{candidate, parentResources});
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
        java.util.List<PdfBase> operands = operator.getOperands();
        if (operands == null || operands.isEmpty()) {
            return;
        }
        String opName = operator.getName();
        if ("MP".equals(opName) || "DP".equals(opName) || "BDC".equals(opName)) {
            PdfBase last = operands.get(operands.size() - 1);
            addPropertyName(usage.properties, last);
        }
    }

    private void addPropertyName(Set<String> propertyNames, PdfBase value) {
        if (value instanceof PdfName) {
            propertyNames.add(((PdfName) value).getName());
        }
    }

    private void addIfNotBlank(Set<String> names, String name) {
        if (name != null && !name.isEmpty()) {
            names.add(name);
        }
    }

    private PdfDictionary resolveDictionary(PdfBase base) {
        PdfBase resolved = resolveCosBase(base, java.util.Collections.emptyMap());
        return resolved instanceof PdfDictionary ? (PdfDictionary) resolved : null;
    }

    private PdfStream resolveStream(PdfBase base) {
        PdfBase resolved = resolveCosBase(base, java.util.Collections.emptyMap());
        return resolved instanceof PdfStream ? (PdfStream) resolved : null;
    }

    private PdfBase resolveCosBase(PdfBase base, Map<PdfObjectKey, PdfBase> objects) {
        if (base instanceof PdfObjectReference) {
            // A reference's TARGET is getKey(); getObjectKey() is the (usually
            // null) identity of the reference value itself. Parsed trailers
            // carry resolver-less references, so the map lookup must come
            // first or reachability dies at the root (optimizeResources).
            PdfObjectKey key = ((PdfObjectReference) base).getKey();
            if (key != null && objects.containsKey(key)) {
                return objects.get(key);
            }
            try {
                return ((PdfObjectReference) base).dereference();
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
        /** When true the owning dict must not be pruned (a user's content failed to parse). */
        private boolean unsafe;

        void merge(ResourceUsage other) {
            fonts.addAll(other.fonts);
            xObjects.addAll(other.xObjects);
            extGState.addAll(other.extGState);
            colorSpaces.addAll(other.colorSpaces);
            patterns.addAll(other.patterns);
            shadings.addAll(other.shadings);
            properties.addAll(other.properties);
            unsafe |= other.unsafe;
        }
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

    /**
     * Flow compaction (IR Stage 2): closes vertical holes left by deletion or
     * merge and — by default — lets content flow across page boundaries like
     * HTML, removing emptied pages. Chrome (headers, footers, page numbers,
     * watermarks) stays fixed; annotations travel with their content.
     * <p>
     * Defaults: {@code verticalGapThreshold}=30pt (corpus-calibrated),
     * {@code keepPageBreaks}=false, {@code preserveChrome}=true,
     * {@code mergeColumns}=false (multi-column pages are skipped with a
     * reason). Known limitation: page-number chrome text is NOT rewritten
     * after page removal.
     * </p>
     *
     * @param options the options (null = defaults)
     * @return the result: pages before/after, gaps closed, blocks moved,
     *         skipped pages with reasons
     * @throws IOException if page content cannot be read or written
     */
    public CompactionResult compactFlow(CompactionOptions options) throws IOException {
        return org.aspose.pdf.sdm.flow.FlowOperations.compactFlow(this, options);
    }

    /**
     * Merges documents into a new one: concatenates all pages, then — when
     * {@link MergeOptions#isFlowCompaction()} — runs {@link #compactFlow} so
     * the content of each following document begins right after the previous
     * one instead of on a fresh page.
     *
     * @param docs    the source documents in order
     * @param options the merge options (null = plain concatenation)
     * @return the merged document
     * @throws IOException if pages cannot be imported or compacted
     */
    public static Document merge(java.util.List<Document> docs, MergeOptions options)
            throws IOException {
        if (docs == null || docs.isEmpty()) {
            throw new IllegalArgumentException("docs must not be null or empty");
        }
        Document target = new Document();
        for (Document source : docs) {
            // Normalize the source through a byte round-trip: freshly built
            // documents hold DIRECT /Contents streams the page importer cannot
            // clone (it expects parsed, indirect-object documents).
            ByteArrayOutputStream srcBytes = new ByteArrayOutputStream(1 << 16);
            source.save(srcBytes);
            Document normalized = new Document(
                    new java.io.ByteArrayInputStream(srcBytes.toByteArray()));
            DocumentPageImporter importer = new DocumentPageImporter(target, normalized);
            for (int i = 1; i <= normalized.getPages().getCount(); i++) {
                // importPage clones; the page must still be spliced into the tree.
                target.getPages().add(importer.importPage(normalized.getPages().get(i)));
            }
        }
        // Round-trip through bytes: imported pages hold indirect objects that a
        // freshly built in-memory document has no parser to resolve, and the
        // flow reader (absorbers) needs full resolution.
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 16);
        target.save(buffer);
        Document merged = new Document(new java.io.ByteArrayInputStream(buffer.toByteArray()));
        if (options != null && options.isFlowCompaction()) {
            merged.compactFlow(options.getCompactionOptions());
        }
        return merged;
    }
}
