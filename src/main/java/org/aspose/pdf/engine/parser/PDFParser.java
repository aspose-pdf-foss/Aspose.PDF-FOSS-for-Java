package org.aspose.pdf.engine.parser;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSBoolean;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSNull;
import org.aspose.pdf.engine.cos.COSObjectKey;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.engine.cos.COSString;
import org.aspose.pdf.engine.io.RandomAccessReader;
import org.aspose.pdf.engine.security.PDFDecryptor;
import org.aspose.pdf.engine.security.PDFEncryptionDict;
import org.aspose.pdf.engine.security.StandardSecurityHandler;
import org.aspose.pdf.security.EncryptionParameters;
import org.aspose.pdf.security.ICustomSecurityHandler;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Full PDF file parser implementing lazy object loading.
 * Parses the PDF header, cross-reference table, and trailer on {@link #parse()},
 * then loads individual objects on demand via {@link #getObject(COSObjectKey)}.
 *
 * <p>Conforms to ISO 32000-1:2008, §7.5 (File Structure).</p>
 */
public final class PDFParser implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(PDFParser.class.getName());
    private static final Set<String> OPTIONAL_STRING_DICT_KEYS = new HashSet<>(Arrays.asList(
            "Author", "Title", "Subject", "Keywords", "Creator", "Producer"
    ));

    private final RandomAccessReader reader;
    private final PDFLexer lexer;
    private final XRefParser xrefParser;

    private float pdfVersion;
    private COSDictionary trailer;
    private Map<COSObjectKey, XRefEntry> xrefEntries;

    /** Object cache for lazy loading (thread-safe). */
    private final Map<COSObjectKey, COSBase> objectCache = new ConcurrentHashMap<>();

    /** Guard against circular references during object loading (thread-safe). */
    private final Set<COSObjectKey> loadingInProgress = ConcurrentHashMap.newKeySet();

    /** Decryptor for encrypted PDFs. Null for unencrypted documents. */
    private PDFDecryptor decryptor;

    /** Object key of the /Encrypt dictionary — must NOT be decrypted. */
    private COSObjectKey encryptDictKey;

    /**
     * Constructs a new PDFParser for the given source.
     *
     * @param reader the random-access reader for the PDF file
     */
    public PDFParser(RandomAccessReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        this.reader = reader;
        this.lexer = new PDFLexer(reader);
        this.xrefParser = new XRefParser(reader, lexer);
    }

    /**
     * Parses the PDF file structure: header, cross-reference, and trailer.
     * Does NOT load any objects — they are loaded lazily on demand.
     *
     * @throws IOException if the file is not a valid PDF or cannot be read
     */
    public void parse() throws IOException {
        LOGGER.log(Level.FINE, "Starting PDF parse");

        // 1. Parse header: %PDF-X.Y
        parseHeader();

        try {
            // 2. Find startxref position
            long startxrefPos = XRefParser.findStartxref(reader);

            // 3. Parse xref and trailer
            xrefParser.parse(startxrefPos);
            trailer = xrefParser.getTrailerDictionary();
            xrefEntries = xrefParser.getEntries();
        } catch (IOException e) {
            if (!e.getMessage().contains("startxref")) {
                throw e;
            }
            LOGGER.log(Level.WARNING,
                    "Falling back to trailer/object scan recovery because startxref is missing: {0}",
                    e.getMessage());
            parseWithoutXref();
        }

        // Clear dirty flags set during xref/trailer parsing
        if (trailer != null) {
            clearDirtyRecursive(trailer);
        }

        LOGGER.log(Level.FINE, "PDF parsed: version={0}, objects={1}",
                new Object[]{pdfVersion, xrefEntries.size()});
    }

    private void parseWithoutXref() throws IOException {
        trailer = scanTrailerDictionary();
        xrefEntries = scanIndirectObjects();
        // Header-only / trailerless / object-less files contain no usable
        // structure — refuse to open. Aspose throws here and tests like
        // PDFNET-48368-3 (a 41-byte `%PDF-1.7\n` with junk tail) rely on it.
        // We keep the lenient recovery for files that have at least one of
        // {trailer, indirect object} — those are real damaged-PDF cases.
        if (trailer == null && xrefEntries.isEmpty()) {
            throw new IOException("PDF file has no cross-reference table, trailer, or indirect "
                    + "objects — file is not a usable PDF");
        }
        if (trailer == null) {
            trailer = new COSDictionary();
        }
        if (!trailer.containsKey(COSName.of("Size"))) {
            int maxObj = 0;
            for (COSObjectKey key : xrefEntries.keySet()) {
                maxObj = Math.max(maxObj, key.getObjectNumber());
            }
            trailer.set(COSName.of("Size"), COSInteger.valueOf(maxObj + 1L));
        }
    }

    private COSDictionary scanTrailerDictionary() throws IOException {
        byte[] marker = "trailer".getBytes(StandardCharsets.US_ASCII);
        long trailerPos = reader.findBackward(marker, reader.getLength());
        if (trailerPos < 0) {
            return null;
        }
        reader.seek(trailerPos + marker.length);
        lexer.clearPeek();
        PDFLexer.Token token = lexer.nextToken();
        while (token.getType() != PDFLexer.TokenType.DICT_OPEN) {
            if (token.getType() == PDFLexer.TokenType.EOF) {
                return null;
            }
            token = lexer.nextToken();
        }
        reader.seek(token.getPosition());
        lexer.clearPeek();
        return parseDictionary();
    }

    private Map<COSObjectKey, XRefEntry> scanIndirectObjects() throws IOException {
        reader.seek(0);
        byte[] bytes = reader.readFully((int) reader.getLength());
        String text = new String(bytes, StandardCharsets.US_ASCII);
        Pattern pattern = Pattern.compile("(?m)(\\d+)\\s+(\\d+)\\s+obj\\b");
        Matcher matcher = pattern.matcher(text);
        Map<COSObjectKey, XRefEntry> scanned = new LinkedHashMap<>();
        while (matcher.find()) {
            int objectNumber = Integer.parseInt(matcher.group(1));
            int generation = XRefParser.sanitizeGeneration(objectNumber,
                    Integer.parseInt(matcher.group(2)));
            long offset = matcher.start();
            COSObjectKey key = new COSObjectKey(objectNumber, generation);
            scanned.putIfAbsent(key, XRefEntry.inUse(objectNumber, generation, offset));
        }
        return scanned;
    }

    /**
     * Loads an object by its key (object number + generation number).
     * Objects are cached after first load.
     *
     * @param key the object key
     * @return the COS object, or {@link COSNull#INSTANCE} if not found
     * @throws IOException if the object cannot be read
     */
    public synchronized COSBase getObject(COSObjectKey key) throws IOException {
        if (key == null) {
            return COSNull.INSTANCE;
        }

        // Check cache
        COSBase cached = objectCache.get(key);
        if (cached != null) {
            return cached;
        }

        // Check for circular reference
        if (loadingInProgress.contains(key)) {
            LOGGER.log(Level.WARNING, "Circular reference detected for object {0}", key);
            return COSNull.INSTANCE;
        }

        // Find xref entry
        if (xrefEntries == null) {
            LOGGER.log(Level.WARNING, "XRef table not available (parse not called or failed)");
            return COSNull.INSTANCE;
        }
        XRefEntry entry = xrefEntries.get(key);
        if (entry == null) {
            // The xref doesn't know about this object. This happens when the
            // trailer's /Size is too small (e.g. PDFNEWNET_33329 declares Size=4
            // but references obj 5 from /Kids), or the original xref table was
            // truncated. Scan the file body for an "N G obj" header and if
            // found, register it as an in-use entry and try again. This is the
            // same recovery already used for objects whose xref entry has a
            // bad offset — extending it to "missing entirely" closes the gap
            // for stitched / partially-rewritten PDFs.
            try {
                long scanned = scanForObject(key, 0);
                if (scanned >= 0) {
                    LOGGER.log(Level.WARNING, "Object {0} missing from xref; recovered at offset {1}",
                            new Object[]{key, scanned});
                    XRefEntry recovered = XRefEntry.inUse(key.getObjectNumber(),
                            key.getGenerationNumber(), scanned);
                    xrefEntries.put(key, recovered);
                    entry = recovered;
                }
            } catch (IOException ignored) { /* recovery is best-effort */ }
            if (entry == null) {
                LOGGER.log(Level.FINE, "Object {0} not found in xref or by scanning", key);
                return COSNull.INSTANCE;
            }
        }

        switch (entry.getType()) {
            case IN_USE:
                try {
                    return loadInUseObject(key, entry);
                } catch (IOException e) {
                    // The xref entry exists but the object body could not be
                    // located at its offset nor by scanning the file. A strict
                    // reader aborts the whole document; lenient readers (and
                    // Aspose) treat an unresolvable indirect reference as null
                    // so the remainder of the file still loads and can be
                    // re-saved. PDFNEWNET_38682 / 37856 / 37430.
                    LOGGER.log(Level.WARNING, "Object {0} unresolvable ({1}); treating as null",
                            new Object[]{key, e.getMessage()});
                    return COSNull.INSTANCE;
                }
            case COMPRESSED:
                return loadCompressedObject(key, entry);
            case FREE:
                return COSNull.INSTANCE;
            default:
                return COSNull.INSTANCE;
        }
    }

    /**
     * Loads an object by object number (generation 0).
     *
     * @param objectNumber the object number
     * @return the COS object
     * @throws IOException if the object cannot be read
     */
    public COSBase getObject(int objectNumber) throws IOException {
        return getObject(new COSObjectKey(objectNumber, 0));
    }

    /**
     * Returns the trailer dictionary.
     *
     * @return the trailer dictionary
     */
    public COSDictionary getTrailer() {
        return trailer;
    }

    /**
     * Returns the PDF version (e.g. 1.4, 1.7, 2.0).
     *
     * @return the PDF version number
     */
    public float getVersion() {
        return pdfVersion;
    }

    /**
     * Returns the root catalog dictionary (the value of /Root in the trailer).
     *
     * @return the catalog dictionary
     * @throws IOException if the catalog cannot be loaded
     */
    public COSDictionary getCatalog() throws IOException {
        COSBase rootRef = trailer.get(COSName.of("Root"));
        COSBase root;
        try {
            root = resolveReference(rootRef);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING,
                    "Trailer /Root could not be loaded ({0}); scanning for catalog object",
                    e.getMessage());
            root = null;
        }
        if (root instanceof COSDictionary
                && "Catalog".equals(((COSDictionary) root).getNameAsString("Type"))) {
            return (COSDictionary) root;
        }
        // /Root referred to a non-catalog object — typical when xref offsets
        // are corrupted and the entry points into the middle of an unrelated
        // object (e.g. an image stream that also extends COSDictionary).
        // Fall through to the recovery fallback rather than returning the
        // wrong object, which would silently break every page-tree walk.
        if (root instanceof COSDictionary) {
            LOGGER.log(Level.WARNING,
                    "Trailer /Root resolved to a {0} (not /Type /Catalog); attempting recovery",
                    ((COSDictionary) root).getNameAsString("Type"));
        }
        COSDictionary recovered = findCatalogFallback();
        if (recovered != null) {
            return recovered;
        }
        throw new IOException(
                "Cannot find /Root catalog dictionary in trailer — invalid or missing root object");
    }

    private COSDictionary findCatalogFallback() throws IOException {
        if (xrefEntries == null) {
            return null;
        }
        COSDictionary recovered = findCatalogInEntries(xrefEntries);
        if (recovered != null) {
            return recovered;
        }

        Map<COSObjectKey, XRefEntry> scannedEntries = scanIndirectObjects();
        for (Map.Entry<COSObjectKey, XRefEntry> entry : scannedEntries.entrySet()) {
            xrefEntries.putIfAbsent(entry.getKey(), entry.getValue());
        }
        recovered = findCatalogInEntries(scannedEntries);
        if (recovered != null) {
            return recovered;
        }
        // Last-resort path for PDFs whose xref offsets are so corrupted that
        // both the regular getObject (xref-driven) and per-object scanForObject
        // miss the catalog. PDFNEWNET_34129 is the canonical case: trailer
        // says /Root 2 0 R, but the real catalog lives at object 348 0 and
        // the xref entry for 348 points into the middle of an integer field.
        // scanIndirectObjects() finds the real "N G obj" headers from a raw
        // file walk; here we load each scanned entry DIRECTLY from its
        // walked offset (bypassing the stale xrefEntries lookup that
        // getObject would otherwise honour) and look for /Type /Catalog.
        return findCatalogByDirectScan(scannedEntries);
    }

    /**
     * Loads each entry from its scan-derived byte offset directly, bypassing
     * the (presumably-corrupted) xref table. The first dictionary tagged
     * {@code /Type /Catalog} wins; the xref entry and trailer {@code /Root}
     * are rewritten so subsequent {@code getObject(key)} calls return the
     * same catalog rather than the integer the bad offset used to decode to.
     */
    private COSDictionary findCatalogByDirectScan(Map<COSObjectKey, XRefEntry> scannedEntries)
            throws IOException {
        for (Map.Entry<COSObjectKey, XRefEntry> e : scannedEntries.entrySet()) {
            COSObjectKey key = e.getKey();
            XRefEntry scanned = e.getValue();
            COSBase candidate;
            try {
                // Wipe any stale cache entry from an earlier failed parse and
                // load from the scanned offset.
                objectCache.remove(key);
                candidate = loadInUseObject(key, scanned);
            } catch (IOException ex) {
                LOGGER.log(Level.FINE,
                        "Direct-scan catalog probe failed for {0}: {1}",
                        new Object[]{key, ex.getMessage()});
                continue;
            }
            if (candidate instanceof COSDictionary
                    && "Catalog".equals(((COSDictionary) candidate).getNameAsString("Type"))) {
                xrefEntries.put(key, scanned);
                trailer.set(COSName.of("Root"),
                        new COSObjectReference(key, this::getObject));
                clearDirtyRecursive(trailer);
                LOGGER.log(Level.WARNING,
                        "Catalog recovered via direct body scan (key {0} at offset {1}); "
                        + "trailer /Root rewritten",
                        new Object[]{key, scanned.getByteOffset()});
                return (COSDictionary) candidate;
            }
        }
        return null;
    }

    private COSDictionary findCatalogInEntries(Map<COSObjectKey, XRefEntry> entriesToSearch) throws IOException {
        for (COSObjectKey key : new java.util.ArrayList<>(entriesToSearch.keySet())) {
            COSBase candidate;
            try {
                candidate = getObject(key);
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Skipping unreadable object during catalog scan: {0} ({1})",
                        new Object[]{key, e.getMessage()});
                continue;
            }
            if (candidate instanceof COSDictionary) {
                COSDictionary dict = (COSDictionary) candidate;
                if ("Catalog".equals(dict.getNameAsString("Type"))) {
                    trailer.set(COSName.of("Root"), new COSObjectReference(key, this::getObject));
                    clearDirtyRecursive(trailer);
                    return dict;
                }
            }
        }
        return null;
    }

    /**
     * Returns all known object keys from the cross-reference table.
     *
     * @return the set of all object keys
     */
    public Set<COSObjectKey> getAllObjectKeys() {
        if (xrefEntries == null) {
            return java.util.Collections.emptySet();
        }
        return xrefEntries.keySet();
    }

    /**
     * Initializes decryption if the PDF is encrypted.
     * Must be called after parse() and before loading any objects.
     *
     * @param password the user/owner password (null or empty for default)
     * @throws IOException if authentication fails
     */
    public void initSecurity(byte[] password) throws IOException {
        initSecurity(password, null);
    }

    /**
     * Initializes decryption if the PDF is encrypted.
     * Must be called after parse() and before loading any objects.
     *
     * @param password the user/owner password (null or empty for default)
     * @param customHandler optional custom security handler for non-standard filters
     * @throws IOException if authentication fails
     */
    public void initSecurity(byte[] password, ICustomSecurityHandler customHandler) throws IOException {
        if (trailer == null) return;
        COSBase encryptRef = trailer.get(COSName.of("Encrypt"));
        if (encryptRef == null) return; // not encrypted

        // Record the encrypt dict's object key so we don't decrypt it
        if (encryptRef instanceof COSObjectReference) {
            this.encryptDictKey = ((COSObjectReference) encryptRef).getKey();
        }

        // Load the encryption dictionary (it is NOT encrypted)
        COSBase encryptObj = resolveReference(encryptRef);
        if (!(encryptObj instanceof COSDictionary)) return;

        PDFEncryptionDict encDict = new PDFEncryptionDict((COSDictionary) encryptObj);

        // Get document ID from trailer
        byte[] documentId = null;
        COSBase idArray = trailer.get(COSName.of("ID"));
        if (idArray instanceof COSArray && ((COSArray) idArray).size() > 0) {
            COSBase firstId = ((COSArray) idArray).get(0);
            if (firstId instanceof COSString) {
                documentId = ((COSString) firstId).getBytes();
            }
        }

        if (password == null) password = new byte[0];
        String filter = encDict.getFilter();
        if (filter != null && !"Standard".equals(filter)) {
            if (customHandler == null) {
                throw new IOException("Unsupported security handler: " + filter);
            }
            String suppliedPassword = new String(password, java.nio.charset.StandardCharsets.UTF_8);
            EncryptionParameters parameters = new EncryptionParameters(
                    encDict.getO(), encDict.getU(), encDict.getP(),
                    encDict.getV(), encDict.getR(), encDict.getLength());
            customHandler.initialize(parameters);
            if (!customHandler.getFilter().equals(filter)) {
                throw new IOException("Custom security handler mismatch: expected " + filter
                        + ", got " + customHandler.getFilter());
            }
            boolean authenticated = customHandler.isUserPassword(suppliedPassword)
                    || customHandler.isOwnerPassword(suppliedPassword);
            if (!authenticated && password.length > 0) {
                authenticated = customHandler.isUserPassword("") || customHandler.isOwnerPassword("");
                if (authenticated) {
                    suppliedPassword = "";
                }
            }
            if (!authenticated) {
                throw new IOException("Invalid password for encrypted PDF");
            }
            this.decryptor = new PDFDecryptor(
                    customHandler.calculateEncryptionKey(suppliedPassword), encDict, customHandler);
        } else {
            StandardSecurityHandler handler = new StandardSecurityHandler(encDict, documentId);
            if (!handler.authenticate(password)) {
                if (password.length > 0 && !handler.authenticate(new byte[0])) {
                    throw new IOException("Invalid password for encrypted PDF");
                } else if (password.length == 0) {
                    throw new IOException("Invalid password for encrypted PDF");
                }
            }
            this.decryptor = new PDFDecryptor(handler.getEncryptionKey(), encDict);
        }
        // Clear cache since any objects loaded during initSecurity need re-reading with decryption
        objectCache.clear();
        LOGGER.fine(() -> "Encryption initialized: V=" + encDict.getV() + " R=" + encDict.getR());
    }

    /**
     * Returns true if this document is encrypted.
     */
    public boolean isEncrypted() {
        return trailer != null && trailer.get(COSName.of("Encrypt")) != null;
    }

    /**
     * Sets the decryptor directly (for testing).
     */
    public void setDecryptor(PDFDecryptor decryptor) {
        this.decryptor = decryptor;
    }

    /**
     * Returns the active decryptor, if the document was opened successfully with encryption.
     *
     * @return the decryptor, or {@code null} for unencrypted documents
     */
    public PDFDecryptor getDecryptor() {
        return decryptor;
    }

    /**
     * Resolves a COS object reference to the actual object.
     * If the input is already a direct object, returns it as-is.
     *
     * @param obj the object or reference to resolve
     * @return the resolved object
     * @throws IOException if resolution fails
     */
    public COSBase resolveReference(COSBase obj) throws IOException {
        if (obj instanceof COSObjectReference) {
            COSObjectKey key = ((COSObjectReference) obj).getKey();
            return getObject(key);
        }
        return obj;
    }

    /**
     * Parses an object body at the current lexer position.
     * This is the main recursive descent parser for COS objects.
     *
     * @return the parsed COS object
     * @throws IOException if parsing fails
     */
    public COSBase parseObjectBody() throws IOException {
        PDFLexer.Token token = lexer.peekToken();

        switch (token.getType()) {
            case INTEGER: {
                lexer.nextToken();
                long posAfterFirstInt = reader.getPosition();
                lexer.clearPeek();
                long intVal;
                try {
                    intVal = Long.parseLong(token.getValue());
                } catch (NumberFormatException e) {
                    if (consumeMalformedReferenceTail(posAfterFirstInt)) {
                        LOGGER.log(Level.WARNING,
                                "Malformed indirect reference starting with oversized integer {0}; recovering as null",
                                token.getValue());
                        return COSNull.INSTANCE;
                    }
                    // Extremely large integers (e.g. Float.MAX_VALUE written as int)
                    // fall back to real number representation
                    reader.seek(posAfterFirstInt);
                    lexer.clearPeek();
                    try {
                        double dVal = Double.parseDouble(token.getValue());
                        return new COSFloat((float) dVal);
                    } catch (NumberFormatException e2) {
                        return COSInteger.valueOf(0);
                    }
                }

                // Look ahead for "N G R" (indirect reference)
                PDFLexer.Token next = lexer.peekToken();
                if (next.getType() == PDFLexer.TokenType.INTEGER) {
                    PDFLexer.Token genToken = lexer.nextToken(); // consume gen number
                    PDFLexer.Token rToken = lexer.peekToken();
                    if (rToken.getType() == PDFLexer.TokenType.KEYWORD && "R".equals(rToken.getValue())) {
                        lexer.nextToken(); // consume 'R'
                        COSObjectKey refKey = new COSObjectKey((int) intVal, Integer.parseInt(genToken.getValue()));
                        COSObjectReference ref = new COSObjectReference(refKey);
                        ref.setResolver(key -> {
                            try {
                                return this.getObject(key);
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING, "Failed to resolve reference {0}", key);
                                return COSNull.INSTANCE;
                            }
                        });
                        return ref;
                    }
                    // Not an indirect reference — backtrack to just after the first integer
                    reader.seek(posAfterFirstInt);
                    lexer.clearPeek();
                } else {
                    // Next token is not an integer — no need to backtrack, just clear peek
                    // The peeked token stays valid in the lexer
                }

                return COSInteger.valueOf(intVal);
            }
            case REAL: {
                lexer.nextToken();
                return new COSFloat(Float.parseFloat(token.getValue()));
            }
            case NAME: {
                lexer.nextToken();
                return COSName.of(token.getValue());
            }
            case LITERAL_STRING: {
                lexer.nextToken();
                return new COSString(token.getValue().getBytes(StandardCharsets.ISO_8859_1));
            }
            case HEX_STRING: {
                lexer.nextToken();
                COSString s = new COSString(token.getValue().getBytes(StandardCharsets.ISO_8859_1));
                s.setForceHex(true);
                return s;
            }
            case KEYWORD: {
                String kw = token.getValue();
                if ("true".equals(kw)) {
                    lexer.nextToken();
                    return COSBoolean.TRUE;
                } else if ("false".equals(kw)) {
                    lexer.nextToken();
                    return COSBoolean.FALSE;
                } else if ("null".equals(kw)) {
                    lexer.nextToken();
                    return COSNull.INSTANCE;
                }
                // "endobj" with no body means an empty/null object
                if ("endobj".equals(kw)) {
                    return COSNull.INSTANCE;
                }
                // Other keywords (endstream, etc.) are not objects
                throw new IOException("Unexpected keyword while parsing object body: " + kw
                        + " at position " + token.getPosition());
            }
            case ARRAY_OPEN: {
                return parseArray();
            }
            case DICT_OPEN: {
                COSDictionary dict = parseDictionary();
                // Check if followed by "stream"
                PDFLexer.Token peek = lexer.peekToken();
                if (peek.getType() == PDFLexer.TokenType.KEYWORD && "stream".equals(peek.getValue())) {
                    return parseStream(dict);
                }
                return dict;
            }
            case EOF:
                return COSNull.INSTANCE;
            default:
                throw new IOException("Unexpected token: " + token);
        }
    }

    // ========== Private implementation methods ==========

    /**
     * Parses the PDF/FDF header (%PDF-X.Y or %FDF-X.Y).
     * FDF (Forms Data Format) files use the same COS object model as PDF (ISO 32000-1 §12.7.7).
     */
    private void parseHeader() throws IOException {
        reader.seek(0);
        String line = reader.readLine();
        if (line == null || (!line.startsWith("%PDF-") && !line.startsWith("%FDF-"))) {
            long pdfHeaderPos = reader.findForward("%PDF-".getBytes(StandardCharsets.US_ASCII), 0);
            long fdfHeaderPos = reader.findForward("%FDF-".getBytes(StandardCharsets.US_ASCII), 0);
            long headerPos = chooseHeaderPosition(pdfHeaderPos, fdfHeaderPos);
            if (headerPos < 0 || headerPos > 1024) {
                throw new IOException("Not a PDF/FDF file: missing %PDF- or %FDF- header");
            }
            LOGGER.log(Level.WARNING, "Found PDF/FDF header at offset {0}; tolerating leading junk bytes", headerPos);
            reader.seek(headerPos);
            line = reader.readLine();
            if (line == null || (!line.startsWith("%PDF-") && !line.startsWith("%FDF-"))) {
                throw new IOException("Not a PDF/FDF file: missing %PDF- or %FDF- header");
            }
        }
        // Extract leading digits.digits — some PDFs put a binary-marker comment
        // on the same line as the header, e.g. "%PDF-1.4%\u00E2\u00E3\u00CF\u00D3".
        String rest = line.substring(5);
        int end = 0;
        while (end < rest.length() && (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '.')) {
            end++;
        }
        String versionText = rest.substring(0, end).trim();
        try {
            pdfVersion = Float.parseFloat(versionText);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid PDF version in header: " + line);
        }
        LOGGER.log(Level.FINE, "PDF version: {0}", pdfVersion);
    }

    private static long chooseHeaderPosition(long pdfHeaderPos, long fdfHeaderPos) {
        if (pdfHeaderPos < 0) {
            return fdfHeaderPos;
        }
        if (fdfHeaderPos < 0) {
            return pdfHeaderPos;
        }
        return Math.min(pdfHeaderPos, fdfHeaderPos);
    }

    /**
     * Loads an in-use object from its byte offset.
     * If the xref offset is incorrect, falls back to scanning for the object header.
     */
    private COSBase loadInUseObject(COSObjectKey key, XRefEntry entry) throws IOException {
        loadingInProgress.add(key);
        try {
            long offset = entry.getByteOffset();
            if (!trySeekToObj(key, offset)) {
                // Xref offset is wrong — try scanning for the object header.
                // FINE, not WARNING: graceful recovery from a malformed xref is
                // expected for many real-world PDFs and was ~70% of test log noise
                // (Sprint 24 Part B).
                LOGGER.log(Level.WARNING, "Bad xref offset {0} for object {1}, scanning for actual position",
                        new Object[]{entry.getByteOffset(), key});
                long scannedOffset = scanForObject(key, offset);
                if (scannedOffset < 0 || !trySeekToObj(key, scannedOffset)) {
                    throw new IOException("Cannot find object " + key + " at xref offset "
                            + entry.getByteOffset() + " or by scanning");
                }
            }

            // Parse the object body
            COSBase object = parseObjectBody();
            object.setObjectKey(key);

            // Decrypt if needed (skip encryption dict itself and XRef streams)
            if (decryptor != null && decryptor.isActive() && !key.equals(encryptDictKey)) {
                object = decryptObject(object, key);
            }

            // Expect "endobj"
            PDFLexer.Token endobj = lexer.nextToken();
            // Stream objects have their own end handling, so endobj might already be consumed
            if (endobj.getType() != PDFLexer.TokenType.KEYWORD || !"endobj".equals(endobj.getValue())) {
                // Some PDFs have extra whitespace or other issues — be lenient
                LOGGER.log(Level.FINE, "Expected 'endobj' for object {0}, got: {1}", new Object[]{key, endobj});
            }

            objectCache.put(key, object);
            clearDirtyRecursive(object);
            return object;
        } finally {
            loadingInProgress.remove(key);
        }
    }

    /**
     * Tries to position the reader at the object body (after "objNum genNum obj")
     * at the given offset. Returns true if successful, false if the offset is wrong.
     * Resets reader/lexer state cleanly on failure.
     */
    private boolean trySeekToObj(COSObjectKey key, long offset) throws IOException {
        if (offset < 0 || offset > reader.getLength()) {
            return false;
        }
        // Guard: ensure the candidate is the START of the object number, not the
        // tail of a larger number like "579" being matched as "9 0 obj". The
        // preceding byte must be whitespace or out of file. Without this guard
        // the body-scan recovery happily lands inside the linearization hint
        // stream of corrupted PDFs (PDFNEWNET_32123) and returns the wrong obj.
        if (offset > 0) {
            reader.seek(offset - 1);
            int prev = reader.read();
            if (!PDFLexer.isWhitespace(prev)) {
                return false;
            }
        }
        reader.seek(offset);
        lexer.clearPeek();
        try {
            PDFLexer.Token t1 = lexer.nextToken();
            PDFLexer.Token t2 = lexer.nextToken();
            PDFLexer.Token t3 = lexer.nextToken();
            if (t3.getType() == PDFLexer.TokenType.KEYWORD && "obj".equals(t3.getValue())
                    && t1.getType() == PDFLexer.TokenType.INTEGER
                    && t2.getType() == PDFLexer.TokenType.INTEGER) {
                // Verify the object header matches the expected key — otherwise the
                // xref pointed into a hole (zero-fill, deleted region, mid-stream)
                // and the lexer just slid forward to the next obj in the file.
                try {
                    int objNum = Integer.parseInt(t1.getValue());
                    int genNum = Integer.parseInt(t2.getValue());
                    if (objNum == key.getObjectNumber() && genNum == key.getGenerationNumber()) {
                        return true;
                    }
                } catch (NumberFormatException ignored) {
                    // Token says INTEGER but value isn't parseable — treat as miss
                }
            }
        } catch (IOException e) {
            // Lexer error at bad offset — expected for corrupt xref
        }
        return false;
    }

    /**
     * Scans the entire file for an object header matching "objNum genNum obj".
     * Used as a fallback when xref offsets are incorrect.
     *
     * @param key the object key to find
     * @return the byte offset of the object header, or -1 if not found
     */
    private long scanForObject(COSObjectKey key, long expectedOffset) throws IOException {
        byte[] pattern = (key.getObjectNumber() + " " + key.getGenerationNumber() + " obj").getBytes(StandardCharsets.US_ASCII);
        // Prefer candidates at or after the broken xref offset. In corrupted
        // PDFs there may be earlier textual occurrences of "N G obj" inside
        // unrelated content, while the real indirect object still lives later
        // in the file. If forward search fails, fall back to scanning backward.
        long pos = reader.findForward(pattern, Math.max(0, expectedOffset));
        while (pos >= 0) {
            // Some malformed PDFs contain junk bytes immediately before a valid
            // indirect object header (for example "...GS>4 0 obj"). Confirm the
            // candidate by trying to parse the object header at the matched
            // position instead of requiring leading whitespace.
            if (trySeekToObj(key, pos)) {
                return pos;
            }
            pos = reader.findForward(pattern, pos + 1);
        }

        pos = reader.findBackward(pattern, Math.max(0, expectedOffset - 1));
        while (pos >= 0) {
            if (trySeekToObj(key, pos)) {
                return pos;
            }
            if (pos == 0) {
                break;
            }
            pos = reader.findBackward(pattern, pos - 1);
        }
        return -1;
    }

    /**
     * Checks if the byte at the given position is a PDF whitespace character.
     */
    private static boolean isWhitespace(RandomAccessReader reader, long pos) throws IOException {
        reader.seek(pos);
        int b = reader.read();
        return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == '\f' || b == 0;
    }

    /**
     * Loads a compressed object from an object stream.
     */
    private COSBase loadCompressedObject(COSObjectKey key, XRefEntry entry) throws IOException {
        loadingInProgress.add(key);
        try {
            // Load the object stream
            COSBase streamObj = getObject(new COSObjectKey(entry.getObjectStreamNumber(), 0));
            if (!(streamObj instanceof COSStream)) {
                throw new IOException("Object stream " + entry.getObjectStreamNumber() + " is not a stream");
            }
            COSStream objStream = (COSStream) streamObj;

            // Get stream properties (COSStream extends COSDictionary)
            int first = objStream.getInt("First", 0);
            int n = objStream.getInt("N", 0);

            // Decode the stream data
            byte[] data = objStream.getDecodedData();

            // Parse the header section: pairs of (objNum offset)
            RandomAccessReader streamReader = RandomAccessReader.fromBytes(data);
            PDFLexer streamLexer = new PDFLexer(streamReader);

            int[] objNumbers = new int[n];
            int[] offsets = new int[n];
            for (int i = 0; i < n; i++) {
                PDFLexer.Token numToken = streamLexer.nextToken();
                PDFLexer.Token offToken = streamLexer.nextToken();
                objNumbers[i] = Integer.parseInt(numToken.getValue());
                offsets[i] = Integer.parseInt(offToken.getValue());
            }

            // Find the index of our object
            int targetIndex = entry.getIndexWithinStream();
            if (targetIndex < 0 || targetIndex >= n) {
                throw new IOException("Invalid index " + targetIndex + " in object stream " + entry.getObjectStreamNumber());
            }

            // Seek to the object within the stream data
            int objectOffset = first + offsets[targetIndex];
            streamReader.seek(objectOffset);

            // Parse the object using a temporary parser for the stream data.
            // IMPORTANT: We must fix up any COSObjectReferences created during parsing
            // so their resolver points to the MAIN parser (this), not the stream parser
            // which has no xref table.
            PDFParser streamParser = new PDFParser(streamReader);
            COSBase object = streamParser.parseObjectBody();
            object.setObjectKey(key);
            fixResolvers(object);

            objectCache.put(key, object);
            clearDirtyRecursive(object);
            return object;
        } finally {
            loadingInProgress.remove(key);
        }
    }

    /**
     * Recursively fixes resolvers on COSObjectReferences within a parsed object tree
     * so they point to this (main) parser instead of a temporary stream parser.
     */
    private void fixResolvers(COSBase object) {
        if (object instanceof COSObjectReference) {
            ((COSObjectReference) object).setResolver(k -> {
                try {
                    return this.getObject(k);
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to resolve reference {0}", k);
                    return COSNull.INSTANCE;
                }
            });
        } else if (object instanceof COSDictionary) {
            for (COSName key : ((COSDictionary) object).keySet()) {
                fixResolvers(((COSDictionary) object).get(key.getName()));
            }
        } else if (object instanceof COSArray) {
            COSArray array = (COSArray) object;
            for (int i = 0; i < array.size(); i++) {
                fixResolvers(array.get(i));
            }
        }
    }

    /**
     * Parses an array object.
     */
    private COSArray parseArray() throws IOException {
        lexer.nextToken(); // consume '['
        COSArray array = new COSArray();

        while (true) {
            PDFLexer.Token peek = lexer.peekToken();
            if (peek.getType() == PDFLexer.TokenType.ARRAY_CLOSE) {
                lexer.nextToken(); // consume ']'
                break;
            }
            if (peek.getType() == PDFLexer.TokenType.EOF) {
                throw new IOException("Unexpected EOF in array");
            }
            array.add(parseObjectBody());
        }

        return array;
    }

    /**
     * Parses a dictionary object (after consuming '&lt;&lt;').
     */
    private COSDictionary parseDictionary() throws IOException {
        lexer.nextToken(); // consume '<<'
        COSDictionary dict = new COSDictionary();

        while (true) {
            PDFLexer.Token peek = lexer.peekToken();
            if (peek.getType() == PDFLexer.TokenType.DICT_CLOSE) {
                lexer.nextToken(); // consume '>>'
                break;
            }
            if (peek.getType() == PDFLexer.TokenType.EOF) {
                throw new IOException("Unexpected EOF in dictionary");
            }
            if (peek.getType() == PDFLexer.TokenType.KEYWORD && ")".equals(peek.getValue())) {
                LOGGER.log(Level.WARNING, "Ignoring stray ')' token inside dictionary at position {0}",
                        peek.getPosition());
                lexer.nextToken();
                continue;
            }

            // Key must be a name
            PDFLexer.Token keyToken = lexer.nextToken();
            if (keyToken.getType() != PDFLexer.TokenType.NAME) {
                throw new IOException("Expected name as dictionary key, got: " + keyToken);
            }
            COSName key = COSName.of(keyToken.getValue());

            // Value
            PDFLexer.Token valuePeek = lexer.peekToken();
            if (shouldTreatMissingDictionaryValue(key, valuePeek)) {
                LOGGER.log(Level.WARNING, "Treating missing value for dictionary key /{0} as null", key.getName());
                dict.set(key, COSNull.INSTANCE);
                continue;
            }
            if (shouldTreatKeywordAsNameInDictionaryValue(valuePeek)) {
                LOGGER.log(Level.WARNING,
                        "Treating bare keyword value ''{0}'' for dictionary key /{1} as a name object",
                        new Object[]{valuePeek.getValue(), key.getName()});
                lexer.nextToken();
                dict.set(key, COSName.of(valuePeek.getValue()));
                continue;
            }
            COSBase value = parseObjectBody();
            dict.set(key, value);
        }

        return dict;
    }

    private boolean consumeMalformedReferenceTail(long posAfterFirstInt) throws IOException {
        PDFLexer.Token next = lexer.peekToken();
        if (next.getType() != PDFLexer.TokenType.INTEGER) {
            reader.seek(posAfterFirstInt);
            lexer.clearPeek();
            return false;
        }
        lexer.nextToken();
        PDFLexer.Token rToken = lexer.peekToken();
        if (rToken.getType() == PDFLexer.TokenType.KEYWORD && "R".equals(rToken.getValue())) {
            lexer.nextToken();
            return true;
        }
        reader.seek(posAfterFirstInt);
        lexer.clearPeek();
        return false;
    }

    private boolean shouldTreatMissingDictionaryValue(COSName key, PDFLexer.Token valuePeek) {
        if (valuePeek.getType() == PDFLexer.TokenType.DICT_CLOSE
                || valuePeek.getType() == PDFLexer.TokenType.EOF) {
            return true;
        }
        if (valuePeek.getType() == PDFLexer.TokenType.KEYWORD
                && ("endobj".equals(valuePeek.getValue()) || "endstream".equals(valuePeek.getValue()))) {
            return true;
        }
        return OPTIONAL_STRING_DICT_KEYS.contains(key.getName())
                && valuePeek.getType() == PDFLexer.TokenType.NAME;
    }

    private boolean shouldTreatKeywordAsNameInDictionaryValue(PDFLexer.Token valuePeek) {
        if (valuePeek.getType() != PDFLexer.TokenType.KEYWORD) {
            return false;
        }
        String value = valuePeek.getValue();
        if ("true".equals(value) || "false".equals(value) || "null".equals(value)
                || "obj".equals(value) || "endobj".equals(value)
                || "stream".equals(value) || "endstream".equals(value)
                || "xref".equals(value) || "trailer".equals(value)
                || "startxref".equals(value) || "R".equals(value)
                || "n".equals(value) || "f".equals(value)) {
            return false;
        }
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return Character.isLetter(first);
    }

    /**
     * Parses a stream object. The dictionary has already been parsed.
     * Per ISO 32000-1:2008, §7.3.8.1: after "stream" keyword,
     * exactly CR+LF or LF (NOT CR alone).
     *
     * @param dict the stream's dictionary
     * @return a COSStream wrapping the dictionary and raw data
     */
    private COSStream parseStream(COSDictionary dict) throws IOException {
        // Consume "stream" keyword
        lexer.nextToken();

        // After "stream", must be CR+LF or LF (per §7.3.8.1)
        // Use peek() first to avoid consuming data bytes if EOL was already skipped
        int c = reader.peek();
        if (c == '\r') {
            reader.read(); // consume CR
            if (reader.peek() == '\n') {
                reader.read(); // consume LF
            }
        } else if (c == '\n') {
            reader.read(); // consume LF
        }
        // If c is neither CR nor LF, reader is already at stream data — do not consume

        // Remember stream data start position BEFORE resolving /Length
        long streamDataStart = reader.getPosition();

        // Read stream data based on /Length
        COSBase lengthObj = dict.get(COSName.of("Length"));
        int length = -1;
        if (lengthObj instanceof COSInteger) {
            length = ((COSInteger) lengthObj).intValue();
        } else if (lengthObj instanceof COSObjectReference) {
            // /Length might be an indirect reference — resolve it
            // IMPORTANT: resolveReference() calls getObject() which does
            // reader.seek() to another object! Must restore position after.
            COSBase resolved = resolveReference(lengthObj);
            if (resolved instanceof COSInteger) {
                length = ((COSInteger) resolved).intValue();
                dict.set(COSName.of("Length"), COSInteger.valueOf(length));
            }
            // CRITICAL: restore reader position after resolveReference moved it
            reader.seek(streamDataStart);
        }

        if (length < 0) {
            length = inferStreamLength(streamDataStart);
            dict.set(COSName.of("Length"), COSInteger.valueOf(length));
            reader.seek(streamDataStart);
        }

        byte[] rawData = reader.readFully(length);

        // Skip to "endstream"
        lexer.skipWhitespaceAndComments();
        PDFLexer.Token endstream = lexer.nextToken();
        if (endstream.getType() != PDFLexer.TokenType.KEYWORD || !"endstream".equals(endstream.getValue())) {
            LOGGER.log(Level.WARNING, "Expected 'endstream', got: {0}", endstream);
        }

        // COSStream extends COSDictionary — create stream and copy dict entries
        COSStream stream = new COSStream(rawData);
        for (java.util.Map.Entry<COSName, COSBase> entry : dict) {
            stream.set(entry.getKey(), entry.getValue());
        }
        return stream;
    }

    // ── Decryption support ──

    /**
     * Recursively decrypts an object: replaces COSString bytes with decrypted bytes,
     * and attaches the decryptor to COSStream instances.
     */
    /**
     * Best-effort fallback for malformed streams with missing or invalid /Length.
     */
    private int inferStreamLength(long streamDataStart) throws IOException {
        byte[] endstreamPattern = "endstream".getBytes(StandardCharsets.US_ASCII);
        long endstreamPos = reader.findForward(endstreamPattern, streamDataStart);
        if (endstreamPos < 0) {
            throw new IOException("Stream missing /Length entry and endstream keyword not found");
        }

        long dataEnd = endstreamPos;
        if (dataEnd > streamDataStart) {
            int last = readByteAt(dataEnd - 1);
            if (last == '\n') {
                dataEnd--;
                if (dataEnd > streamDataStart && readByteAt(dataEnd - 1) == '\r') {
                    dataEnd--;
                }
            } else if (last == '\r') {
                dataEnd--;
            }
        }

        long inferred = Math.max(0, dataEnd - streamDataStart);
        if (inferred > Integer.MAX_VALUE) {
            throw new IOException("Stream is too large to infer length");
        }
        LOGGER.log(Level.WARNING, "Inferred missing stream /Length as {0} bytes", inferred);
        return (int) inferred;
    }

    private int readByteAt(long position) throws IOException {
        long saved = reader.getPosition();
        try {
            reader.seek(position);
            return reader.read();
        } finally {
            reader.seek(saved);
        }
    }

    private COSBase decryptObject(COSBase obj, COSObjectKey key) {
        if (obj instanceof COSStream) {
            COSStream stream = (COSStream) obj;
            // Decrypt strings inside the stream dictionary
            decryptDictEntries(stream, key);
            // Attach decryptor for stream data (decrypted lazily in getDecodedData)
            stream.setDecryptor(decryptor, key.getObjectNumber(), key.getGenerationNumber());
            return stream;
        }
        if (obj instanceof COSDictionary) {
            decryptDictEntries((COSDictionary) obj, key);
            return obj;
        }
        if (obj instanceof COSArray) {
            decryptArrayEntries((COSArray) obj, key);
            return obj;
        }
        if (obj instanceof COSString) {
            return decryptString((COSString) obj, key);
        }
        return obj;
    }

    private void decryptDictEntries(COSDictionary dict, COSObjectKey key) {
        for (COSName name : new java.util.ArrayList<>(dict.keySet())) {
            COSBase val = dict.get(name);
            if (val instanceof COSString) {
                dict.set(name, decryptString((COSString) val, key));
            } else if (val instanceof COSArray) {
                decryptArrayEntries((COSArray) val, key);
            } else if (val instanceof COSDictionary && !(val instanceof COSStream)) {
                decryptDictEntries((COSDictionary) val, key);
            }
        }
    }

    private void decryptArrayEntries(COSArray arr, COSObjectKey key) {
        for (int i = 0; i < arr.size(); i++) {
            COSBase elem = arr.get(i);
            if (elem instanceof COSString) {
                arr.set(i, decryptString((COSString) elem, key));
            } else if (elem instanceof COSArray) {
                decryptArrayEntries((COSArray) elem, key);
            } else if (elem instanceof COSDictionary && !(elem instanceof COSStream)) {
                decryptDictEntries((COSDictionary) elem, key);
            }
        }
    }

    private COSString decryptString(COSString str, COSObjectKey key) {
        byte[] decrypted = decryptor.decrypt(str.getBytes(),
                key.getObjectNumber(), key.getGenerationNumber());
        COSString result = new COSString(decrypted);
        if (str.isForceHex()) result.setForceHex(true);
        return result;
    }

    /**
     * Clears the dirty flag on an object and its direct children (non-recursive
     * through references). Called after loading/parsing to ensure freshly-loaded
     * objects do not appear as modified.
     *
     * @param obj the object to clean
     */
    private void clearDirtyRecursive(COSBase obj) {
        if (obj == null) return;
        obj.setDirty(false);
        if (obj instanceof COSDictionary) {
            COSDictionary dict = (COSDictionary) obj;
            for (COSBase value : dict.values()) {
                if (value != null && !(value instanceof COSObjectReference)) {
                    clearDirtyRecursive(value);
                }
            }
        } else if (obj instanceof COSArray) {
            COSArray arr = (COSArray) obj;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item != null && !(item instanceof COSObjectReference)) {
                    clearDirtyRecursive(item);
                }
            }
        }
    }

    /**
     * Closes the underlying reader, releasing any associated resources.
     *
     * @throws IOException if an I/O error occurs
     */
    @Override
    public void close() throws IOException {
        reader.close();
    }
}
