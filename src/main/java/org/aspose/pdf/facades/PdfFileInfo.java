package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.DocumentInfo;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.aspose.pdf.engine.security.StandardSecurityHandler;
import org.aspose.pdf.security.EncryptionParameters;
import org.aspose.pdf.security.ICustomSecurityHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Closeable;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides read-only access to PDF document metadata and properties
 * such as title, author, page count, and encryption status.
 */
public class PdfFileInfo implements Closeable {

    private static final Logger LOG = Logger.getLogger(PdfFileInfo.class.getName());

    private Document document;
    private boolean pdfFile;
    private PasswordType passwordType = PasswordType.None;
    private boolean hasOpenPassword;
    private boolean hasEditPassword;
    private String openedPassword;
    private ICustomSecurityHandler openedCustomHandler;
    /**
     * Sticky flag set when {@link #bindPdf(String)} (or its overloads) detects
     * that the underlying PDF is encrypted but no usable password was provided.
     * In that case {@link #document} stays {@code null}, but
     * {@link #isEncrypted()} should still return {@code true} so callers can
     * distinguish "not a PDF" from "PDF is locked".
     */
    private boolean encryptedButLocked;

    /**
     * Creates a new empty {@code PdfFileInfo} instance.
     * Call {@link #bindPdf(String)} or {@link #bindPdf(InputStream)} before
     * accessing properties.
     */
    public PdfFileInfo() {
    }

    /**
     * Creates a {@code PdfFileInfo} by opening the specified PDF file.
     *
     * @param inputFile path to the PDF file
     */
    public PdfFileInfo(String inputFile) {
        bindPdf(inputFile);
    }

    /**
     * Creates a {@code PdfFileInfo} by opening the specified encrypted PDF file
     * using the provided password.
     *
     * @param inputFile path to the PDF file
     * @param password password used to open the document
     */
    public PdfFileInfo(String inputFile, String password) {
        bindPdf(inputFile, password);
    }

    /**
     * Creates a {@code PdfFileInfo} for an encrypted PDF using a custom security handler.
     *
     * @param inputFile path to the PDF file
     * @param password password used to open the document
     * @param customHandler custom security handler
     */
    public PdfFileInfo(String inputFile, String password, ICustomSecurityHandler customHandler) {
        bindPdf(inputFile, password, customHandler);
    }

    /**
     * Creates a {@code PdfFileInfo} by reading from the specified input stream.
     *
     * @param stream input stream containing PDF data
     */
    public PdfFileInfo(InputStream stream) {
        bindPdf(stream);
    }

    /**
     * Creates a {@code PdfFileInfo} by reading from the specified encrypted input
     * stream using the provided password.
     *
     * @param stream input stream containing PDF data
     * @param password password used to open the document
     */
    public PdfFileInfo(InputStream stream, String password) {
        bindPdf(stream, password);
    }

    /**
     * Creates a {@code PdfFileInfo} by reading an encrypted input stream using
     * a custom security handler.
     *
     * @param stream input stream containing PDF data
     * @param password password used to open the document
     * @param customHandler custom security handler
     */
    public PdfFileInfo(InputStream stream, String password, ICustomSecurityHandler customHandler) {
        bindPdf(stream, password, customHandler);
    }

    /** Creates a {@code PdfFileInfo} bound to an already-loaded document. */
    public PdfFileInfo(Document document) {
        bindPdf(document);
    }

    /** Returns the bound document, or {@code null}. Mirrors C# {@code PdfFileInfo.Document}. */
    public Document getDocument() {
        return document;
    }

    /**
     * Binds an already opened document instance to this info reader.
     *
     * @param document the document to inspect
     * @return {@code true} if the document is non-null
     */
    public boolean bindPdf(Document document) {
        this.document = document;
        this.pdfFile = document != null;
        this.openedPassword = null;
        this.openedCustomHandler = null;
        detectPasswordCapabilities(null);
        return this.pdfFile;
    }

    /**
     * Binds a PDF file to this info reader.
     *
     * @param inputFile path to the PDF file
     * @return {@code true} on success
     */
    public boolean bindPdf(String inputFile) {
        try {
            this.document = new Document(inputFile);
            this.pdfFile = true;
            this.encryptedButLocked = false;
            this.openedPassword = null;
            this.openedCustomHandler = null;
            detectPasswordCapabilities(null);
            return true;
        } catch (java.io.IOException e) {
            // Detect "PDF is encrypted but we don't have the password" — the
            // caller still wants isEncrypted() to report true so they can
            // prompt for credentials.  See PDFNEWNET_31695.
            String msg = e.getMessage();
            if (msg != null && msg.contains("Invalid password for encrypted PDF")) {
                // Aspose convention: an encrypted PDF that requires a user
                // password we don't have is NOT a usable "pdf file" (per
                // PDFNEWNET-32824). isEncrypted() still returns true via the
                // encryptedButLocked flag so callers can prompt for a
                // password and retry.  PDFNEWNET-31695 only asserted
                // isEncrypted, so it remains green with this change.
                LOG.log(Level.FINE, "Encrypted PDF without password: " + inputFile);
                this.pdfFile = false;
                this.encryptedButLocked = true;
                this.hasOpenPassword = true;
                return false;
            }
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
            this.pdfFile = false;
            return false;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
            this.pdfFile = false;
            return false;
        }
    }

    /**
     * Binds an encrypted PDF file to this info reader using the provided
     * password.
     *
     * @param inputFile path to the PDF file
     * @param password password used to open the document
     * @return {@code true} on success
     */
    public boolean bindPdf(String inputFile, String password) {
        try {
            this.document = new Document(inputFile, password);
            this.pdfFile = true;
            this.openedPassword = password;
            this.openedCustomHandler = null;
            detectPasswordCapabilities(password);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file with password: " + inputFile, e);
            this.pdfFile = false;
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
            this.pdfFile = true;
            this.openedPassword = null;
            this.openedCustomHandler = null;
            detectPasswordCapabilities(null);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream", e);
            this.pdfFile = false;
            return false;
        }
    }

    /**
     * Binds an encrypted PDF from an input stream using the provided password.
     *
     * @param inputStream the input stream containing PDF data
     * @param password password used to open the document
     * @return {@code true} on success
     */
    public boolean bindPdf(InputStream inputStream, String password) {
        try {
            this.document = new Document(inputStream, password);
            this.pdfFile = true;
            this.openedPassword = password;
            this.openedCustomHandler = null;
            detectPasswordCapabilities(password);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream with password", e);
            this.pdfFile = false;
            return false;
        }
    }

    /**
     * Binds an encrypted PDF file using a custom security handler.
     *
     * @param inputFile path to the PDF file
     * @param password password used to open the document
     * @param customHandler custom security handler
     * @return {@code true} on success
     */
    public boolean bindPdf(String inputFile, String password, ICustomSecurityHandler customHandler) {
        try {
            this.document = new Document(inputFile, password, customHandler);
            this.pdfFile = true;
            this.openedPassword = password;
            this.openedCustomHandler = customHandler;
            detectPasswordCapabilities(password);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind custom-secured PDF from file: " + inputFile, e);
            this.pdfFile = false;
            return false;
        }
    }

    /**
     * Binds an encrypted PDF stream using a custom security handler.
     *
     * @param inputStream PDF bytes
     * @param password password used to open the document
     * @param customHandler custom security handler
     * @return {@code true} on success
     */
    public boolean bindPdf(InputStream inputStream, String password, ICustomSecurityHandler customHandler) {
        try {
            this.document = new Document(inputStream, password, customHandler);
            this.pdfFile = true;
            this.openedPassword = password;
            this.openedCustomHandler = customHandler;
            detectPasswordCapabilities(password);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind custom-secured PDF from stream", e);
            this.pdfFile = false;
            return false;
        }
    }

    /**
     * Returns the document title.
     *
     * @return the title, or {@code null} if unavailable
     */
    public String getTitle() {
        return getInfoSafe() != null ? getInfoSafe().getTitle() : null;
    }

    /**
     * Returns the document author.
     *
     * @return the author, or {@code null} if unavailable
     */
    public String getAuthor() {
        return getInfoSafe() != null ? getInfoSafe().getAuthor() : null;
    }

    /**
     * Returns the document subject.
     *
     * @return the subject, or {@code null} if unavailable
     */
    public String getSubject() {
        return getInfoSafe() != null ? getInfoSafe().getSubject() : null;
    }

    /**
     * Returns the document keywords.
     *
     * @return the keywords, or {@code null} if unavailable
     */
    public String getKeywords() {
        return getInfoSafe() != null ? getInfoSafe().getKeywords() : null;
    }

    /**
     * Returns the document creator application.
     *
     * @return the creator, or {@code null} if unavailable
     */
    public String getCreator() {
        return getInfoSafe() != null ? getInfoSafe().getCreator() : null;
    }

    /**
     * Returns the PDF producer.
     *
     * @return the producer, or {@code null} if unavailable
     */
    public String getProducer() {
        return getInfoSafe() != null ? getInfoSafe().getProducer() : null;
    }

    /**
     * Returns the document creation date.
     *
     * @return the creation date, or {@code null} if unavailable
     */
    public Date getCreationDate() {
        return getInfoSafe() != null ? getInfoSafe().getCreationDate() : null;
    }

    /**
     * Returns the document modification date.
     *
     * @return the modification date, or {@code null} if unavailable
     */
    public Date getModDate() {
        return getInfoSafe() != null ? getInfoSafe().getModDate() : null;
    }

    /**
     * Returns whether the document is encrypted.
     *
     * @return {@code true} if the document is encrypted
     */
    public boolean isEncrypted() {
        if (encryptedButLocked) return true;
        return document != null && document.isEncrypted();
    }

    /**
     * Returns whether the bound file is a valid PDF.
     *
     * @return {@code true} if the file was successfully parsed as PDF
     */
    public boolean isPdfFile() {
        return pdfFile;
    }

    /**
     * Returns whether the encrypted document has an open password entry.
     *
     * @return {@code true} if an open password is present
     */
    public boolean hasOpenPassword() {
        return hasOpenPassword;
    }

    /**
     * Returns whether the encrypted document has an edit password entry.
     *
     * @return {@code true} if an edit password is present
     */
    public boolean hasEditPassword() {
        return hasEditPassword;
    }

    /**
     * Returns the role of the password used to open the document.
     *
     * @return the password type
     */
    public PasswordType getPasswordType() {
        return passwordType;
    }

    /**
     * Returns the total number of pages.
     *
     * @return the page count, or 0 on error
     */
    public int getNumberOfPages() {
        try {
            return document != null ? document.getPages().getCount() : 0;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get page count", e);
            return 0;
        }
    }

    /**
     * Returns the page width in points (1/72 inch) for the given 1-based page
     * number, taken from the page's MediaBox.
     *
     * @param pageNumber 1-based page index
     * @return page width in points, or 0 if the page is unavailable
     */
    public double getPageWidth(int pageNumber) {
        try {
            if (document == null) return 0;
            org.aspose.pdf.Rectangle r = document.getPages().get(pageNumber).getMediaBox();
            return r == null ? 0 : r.getWidth();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get page width for page " + pageNumber, e);
            return 0;
        }
    }

    /**
     * Returns the page height in points (1/72 inch) for the given 1-based page
     * number, taken from the page's MediaBox.
     *
     * @param pageNumber 1-based page index
     * @return page height in points, or 0 if the page is unavailable
     */
    public double getPageHeight(int pageNumber) {
        try {
            if (document == null) return 0;
            org.aspose.pdf.Rectangle r = document.getPages().get(pageNumber).getMediaBox();
            return r == null ? 0 : r.getHeight();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to get page height for page " + pageNumber, e);
            return 0;
        }
    }

    /**
     * Returns document permissions as a facade-compatible privilege object.
     *
     * @return current document privilege, or {@code null} if unavailable
     */
    public DocumentPrivilege getDocumentPrivilege() {
        if (document == null) {
            return null;
        }
        if (!document.isEncrypted()) {
            return DocumentPrivilege.getAllowAll();
        }
        try {
            PDFParser parser = document.getParser();
            if (parser == null) {
                return null;
            }
            PDFEncryptionDict encDict = getEncryptionDict(parser);
            return encDict != null ? new DocumentPrivilege(encDict.getP()) : null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to read document privilege", e);
            return null;
        }
    }

    /**
     * Adds (or overwrites) a custom entry in the document's /Info dictionary.
     * Mirrors the C# {@code PdfFileInfo.SetMetaInfo(string, string)} overload.
     * <p>
     * Standard keys (Title, Author, Subject, Keywords, Creator, Producer,
     * CreationDate, ModDate) are still respected — passing one of them here
     * has the same effect as calling the typed setter.
     * </p>
     *
     * @param name  the metadata key (becomes a /Name in /Info)
     * @param value the metadata value, written as a UTF-8 PDF string
     */
    public void setMetaInfo(String name, String value) {
        if (document == null || name == null || name.isEmpty()) {
            return;
        }
        try {
            DocumentInfo info = document.getOrCreateInfo();
            org.aspose.pdf.engine.pdfobjects.PdfDictionary infoDict = info.getPdfDictionary();
            if (value == null) {
                infoDict.remove(org.aspose.pdf.engine.pdfobjects.PdfName.of(name));
            } else {
                infoDict.set(org.aspose.pdf.engine.pdfobjects.PdfName.of(name),
                        new org.aspose.pdf.engine.pdfobjects.PdfString(
                                value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "setMetaInfo('" + name + "') failed", e);
        }
    }

    /**
     * Saves the bound document — together with any {@link #setMetaInfo} edits —
     * to {@code outputFile}. Mirrors C# {@code PdfFileInfo.SaveNewInfo(string)}.
     *
     * @param outputFile path to the output PDF
     * @return {@code true} on success
     */
    public boolean saveNewInfo(String outputFile) {
        if (document == null) {
            LOG.warning("saveNewInfo: no document bound");
            return false;
        }
        try {
            document.save(outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "saveNewInfo failed for " + outputFile, e);
            return false;
        }
    }

    /**
     * Stream variant of {@link #saveNewInfo(String)}.
     *
     * @param outputStream destination stream
     * @return {@code true} on success
     */
    public boolean saveNewInfo(OutputStream outputStream) {
        if (document == null) {
            LOG.warning("saveNewInfo: no document bound");
            return false;
        }
        try {
            document.save(outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "saveNewInfo to stream failed", e);
            return false;
        }
    }

    /**
     * Clears the standard document metadata entries from the bound document.
     */
    public void clearInfo() {
        DocumentInfo info = getInfoSafe();
        if (info != null) {
            info.clear();
        }
    }

    /**
     * Closes this info reader and releases the bound document.
     */
    public void close() {
        if (document != null) {
            try {
                document.close();
            } catch (IOException e) {
                LOG.log(Level.FINE, "Error closing document", e);
            }
            document = null;
        }
        passwordType = PasswordType.None;
        hasOpenPassword = false;
        hasEditPassword = false;
        openedPassword = null;
        openedCustomHandler = null;
        pdfFile = false;
    }

    /**
     * Safely retrieves the DocumentInfo, returning {@code null} on any error.
     */
    private DocumentInfo getInfoSafe() {
        try {
            return document != null ? document.getInfo() : null;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to get document info", e);
            return null;
        }
    }

    private void detectPasswordCapabilities(String password) {
        passwordType = PasswordType.None;
        hasOpenPassword = false;
        hasEditPassword = false;
        if (document == null || !document.isEncrypted()) {
            return;
        }
        try {
            PDFParser parser = document.getParser();
            if (parser == null) {
                return;
            }
            PDFEncryptionDict encDict = getEncryptionDict(parser);
            if (encDict == null) {
                return;
            }
            if (openedCustomHandler != null || (encDict.getFilter() != null && !"Standard".equals(encDict.getFilter()))) {
                detectCustomPasswordCapabilities(encDict, password);
                return;
            }
            // /U and /O are ALWAYS present in an encrypted PDF (they hold the
            // computed validation hashes even for an empty password —
            // ISO 32000-1:2008, §7.6.3.4). "Has a password" therefore means the
            // EMPTY password does not authenticate, not that the entry exists
            // (PDFNEWNET-33768: owner-only protection ⇒ HasOpenPassword=false).
            StandardSecurityHandler handler = new StandardSecurityHandler(encDict, getDocumentId(parser));
            hasOpenPassword = !handler.authenticateUserPassword(new byte[0]);
            hasEditPassword = !handler.authenticateOwnerPassword(new byte[0]);
            if (password == null) {
                return;
            }
            byte[] passwordBytes = password.getBytes(StandardCharsets.ISO_8859_1);
            if (handler.authenticateUserPassword(passwordBytes)) {
                passwordType = PasswordType.User;
            } else if (handler.authenticateOwnerPassword(passwordBytes)) {
                passwordType = PasswordType.Owner;
            }
            switch (passwordType) {
                case User:
                    hasOpenPassword = password != null && !password.isEmpty();
                    hasEditPassword = !handler.authenticateOwnerPassword(passwordBytes);
                    break;
                case Owner:
                    hasOpenPassword = !handler.authenticateUserPassword(new byte[0]);
                    hasEditPassword = true;
                    break;
                case None:
                default:
                    break;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to detect password capabilities", e);
        }
    }

    private void detectCustomPasswordCapabilities(PDFEncryptionDict encDict, String password) {
        if (openedCustomHandler == null) {
            return;
        }
        try {
            openedCustomHandler.initialize(new EncryptionParameters(
                    encDict.getO(), encDict.getU(), encDict.getP(),
                    encDict.getV(), encDict.getR(), encDict.getLength()));
            if (password == null) {
                return;
            }
            if (openedCustomHandler.isUserPassword(password)) {
                passwordType = PasswordType.User;
                hasOpenPassword = !password.isEmpty();
                hasEditPassword = !openedCustomHandler.isOwnerPassword(password);
            } else if (openedCustomHandler.isOwnerPassword(password)) {
                passwordType = PasswordType.Owner;
                hasOpenPassword = !openedCustomHandler.isUserPassword("");
                hasEditPassword = true;
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Failed to detect custom password capabilities", e);
        }
    }

    private PDFEncryptionDict getEncryptionDict(PDFParser parser) throws IOException {
        PdfBase encryptRef = parser.getTrailer().get(PdfName.of("Encrypt"));
        if (encryptRef == null) {
            return null;
        }
        PdfBase encryptObj = parser.resolveReference(encryptRef);
        if (!(encryptObj instanceof PdfDictionary)) {
            return null;
        }
        return new PDFEncryptionDict((PdfDictionary) encryptObj);
    }

    private byte[] getDocumentId(PDFParser parser) {
        PdfBase idArray = parser.getTrailer().get(PdfName.of("ID"));
        if (!(idArray instanceof PdfArray) || ((PdfArray) idArray).size() == 0) {
            return null;
        }
        PdfBase firstId = ((PdfArray) idArray).get(0);
        return firstId instanceof PdfString ? ((PdfString) firstId).getBytes() : null;
    }
}
