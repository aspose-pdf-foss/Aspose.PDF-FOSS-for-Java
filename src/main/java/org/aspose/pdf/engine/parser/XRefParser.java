package org.aspose.pdf.engine.parser;

import org.aspose.pdf.engine.pdfobjects.*;
import org.aspose.pdf.engine.io.RandomAccessReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.aspose.pdf.engine.filter.FilterFactory;

/**
 * Parser for PDF cross-reference tables and streams as defined in
 * ISO 32000-1:2008, §7.5.4 (text xref tables) and §7.5.8 (xref streams).
 *
 * <p>Supports incremental updates by following the {@code /Prev} chain in trailer
 * dictionaries. Earlier entries do NOT overwrite later ones (the most recent
 * version of an object takes precedence).</p>
 */
public final class XRefParser {

    private static final Logger LOGGER = Logger.getLogger(XRefParser.class.getName());

    private final RandomAccessReader reader;
    private final PDFLexer lexer;

    private final Map<PdfObjectKey, XRefEntry> entries = new LinkedHashMap<>();

    /**
     * Non-zero when the xref table was recovered by scanning and sits this
     * many bytes AFTER the position startxref claimed: the file is a chain of
     * appended whole-file revisions whose offsets are segment-relative
     * (PDFNET_51575). Object loaders should retry a failed entry offset with
     * this delta added before falling back to a full object scan.
     */
    private long entryOffsetAdjustment;
    /** Byte position of the %PDF- header; non-zero when the file carries leading junk. */
    private long baseOffset;
    private final java.util.Set<Long> visitedOffsets = new java.util.HashSet<>();
    private PdfDictionary trailerDictionary;

    /**
     * Constructs an XRefParser.
     *
     * @param reader the random-access reader for the PDF file
     * @param lexer  the lexer to use for tokenization
     */
    public XRefParser(RandomAccessReader reader, PDFLexer lexer) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        if (lexer == null) {
            throw new IllegalArgumentException("lexer must not be null");
        }
        this.reader = reader;
        this.lexer = lexer;
    }

    /**
     * Clamps a malformed generation number read from an xref table or stream into the
     * legal range [0, 65535] required by {@link PdfObjectKey}.
     * <p>
     * Many real-world PDFs (including some produced by widely-used tools) write a wrong
     * generation number for the free-list head — most commonly 65536 instead of 65535 —
     * or otherwise out-of-range values in xref entries. ISO 32000-1:2008 §7.5.4 restricts
     * generations to the range above, and {@link PdfObjectKey} enforces that range,
     * so the parser must sanitize input before constructing a key. A WARNING is emitted
     * for every clamp to keep downstream issues traceable.
     * </p>
     *
     * @param objectNumber  the object number whose generation is being sanitized (for logging)
     * @param rawGeneration the raw value read from the xref entry
     * @return the sanitized generation in [0, 65535]
     */
    static int sanitizeGeneration(int objectNumber, int rawGeneration) {
        if (rawGeneration >= 0 && rawGeneration <= 65535) {
            return rawGeneration;
        }
        int clamped = Math.max(0, Math.min(65535, rawGeneration));
        // Use {N,number,#} so the integers render without locale-specific grouping
        // (e.g. avoids "65 536" under ru-RU, which would break message searches).
        LOGGER.log(Level.WARNING,
                "Malformed xref generation for object {0,number,#}: {1,number,#} (clamped to {2,number,#})",
                new Object[]{objectNumber, rawGeneration, clamped});
        return clamped;
    }

    /**
     * Parses the cross-reference starting at the given startxref position.
     * Follows the {@code /Prev} chain for incremental updates.
     *
     * @param startxrefPosition the byte offset of the xref section
     * @throws IOException if parsing fails
     */
    public void parse(long startxrefPosition) throws IOException {
        // Leading-junk files: stored offsets (startxref, /Prev) are measured
        // from the %PDF- header, not the physical file start — shift them.
        if (baseOffset != 0 && startxrefPosition >= 0) {
            startxrefPosition += baseOffset;
        }
        LOGGER.log(Level.FINE, "Parsing xref at position {0}", startxrefPosition);
        if (!visitedOffsets.add(startxrefPosition)) {
            LOGGER.log(Level.WARNING, "Cycle detected in xref chain at offset {0}, stopping", startxrefPosition);
            return;
        }

        if (startxrefPosition < 0 || startxrefPosition > reader.getLength()) {
            LOGGER.log(Level.WARNING,
                    "startxref offset {0} is outside file bounds (length={1}); scanning for xref instead",
                    new Object[]{startxrefPosition, reader.getLength()});
            long recoveredXref = scanForXref();
            if (recoveredXref >= 0) {
                startxrefPosition = recoveredXref;
            } else {
                long recoveredStream = scanForXRefStreamNear(reader.getLength(), 65536);
                if (recoveredStream >= 0) {
                    startxrefPosition = recoveredStream;
                } else {
                    throw new IOException("Cannot find cross-reference table in PDF file");
                }
            }
        }

        reader.seek(startxrefPosition);
        lexer.clearPeek();

        // Peek at first token to determine format
        PDFLexer.Token token = lexer.peekToken();

        if (token.getType() == PDFLexer.TokenType.KEYWORD && token.getValue().startsWith("xref")) {
            // Handle non-conforming PDFs where "xref" is not followed by whitespace
            // e.g. "xref1 7" instead of "xref\n1 7" — rewind so the digit part is re-read
            if (token.getValue().length() > 4) {
                reader.seek(token.getPosition());
                lexer.clearPeek();
            }
            try {
                parseTableFormat();
            } catch (IOException tableFailure) {
                // The xref table at startxref is malformed (binary type bytes from a
                // mis-stored xref stream, shifted 20-byte entries, corrupt subsection
                // header, signature /ByteRange bleeding into the table, literal "ERROR"
                // text, etc.). Discard any partially-parsed entries and reconstruct the
                // whole xref by scanning the file body for "N G obj" headers — the same
                // permissive recovery Acrobat / pdf.js use.
                LOGGER.log(Level.WARNING,
                        "xref table at {0} could not be parsed ({1}); rebuilding from object scan",
                        new Object[]{startxrefPosition, tableFailure.getMessage()});
                entries.clear();
                trailerDictionary = null;
                rebuildFromObjectScan();
            }
        } else if (token.getType() == PDFLexer.TokenType.INTEGER) {
            try {
                parseStreamFormat(startxrefPosition);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING,
                        "Startxref offset {0} did not contain a usable xref stream: {1}. Scanning for xref...",
                        new Object[]{startxrefPosition, e.getMessage()});
                long xrefPos = scanForXref();
                if (xrefPos >= 0 && xrefPos != startxrefPosition) {
                    try {
                        reader.seek(xrefPos);
                        lexer.clearPeek();
                        parseTableFormat();
                    } catch (IOException tableFailure) {
                        LOGGER.log(Level.WARNING,
                                "Scanned xref at {0} could not be parsed ({1}); rebuilding from object scan",
                                new Object[]{xrefPos, tableFailure.getMessage()});
                        rebuildFromObjectScan();
                    }
                } else {
                    long objPos = scanForXRefStreamNear(startxrefPosition, 65536);
                    if (objPos >= 0 && objPos != startxrefPosition) {
                        try {
                            parseStreamFormat(objPos);
                        } catch (IOException streamFailure) {
                            LOGGER.log(Level.WARNING,
                                    "Recovered xref stream at {0} could not be parsed ({1}); rebuilding from object scan",
                                    new Object[]{objPos, streamFailure.getMessage()});
                            rebuildFromObjectScan();
                        }
                    } else {
                        LOGGER.log(Level.WARNING,
                                "No xref recoverable from scanForXref/scanForXRefStreamNear — rebuilding from object scan");
                        rebuildFromObjectScan();
                    }
                }
            }
        } else {
            // Not a valid xref at this position (e.g. linearized PDF with startxref=0
            // pointing at %PDF header). Try to find xref by scanning.
            LOGGER.log(Level.WARNING, "No valid xref at position {0}, token: {1}. Scanning for xref...",
                    new Object[]{startxrefPosition, token});
            long xrefPos = scanForXref();
            if (xrefPos >= 0) {
                // Concatenated-revision PDFs (a growing document appended as
                // whole files) carry startxref values relative to their own
                // revision segment, not the file start. The distance between
                // where the table actually is and where startxref claimed it
                // to be equals the segment's base offset, so the same delta
                // relocates every entry offset of that table (PDFNET_51575).
                if (startxrefPosition > 0 && xrefPos > startxrefPosition) {
                    entryOffsetAdjustment = xrefPos - startxrefPosition;
                    LOGGER.log(Level.WARNING,
                            "Scanned xref found {0} bytes after the claimed startxref; "
                                    + "entry offsets will also be retried with this delta",
                            entryOffsetAdjustment);
                }
                try {
                    reader.seek(xrefPos);
                    lexer.clearPeek();
                    PDFLexer.Token scanned = lexer.peekToken();
                    if (scanned.getType() == PDFLexer.TokenType.KEYWORD && "xref".equals(scanned.getValue())) {
                        parseTableFormat();
                    } else {
                        parseStreamFormat(xrefPos);
                    }
                } catch (IOException xrefScanFailure) {
                    // The found xref table is corrupt or truncated. Fall back to
                    // rebuilding entries by scanning the entire file body for
                    // indirect object headers — the standard "permissive reader"
                    // recovery path used by Acrobat / Aspose / pdf.js.
                    LOGGER.log(Level.WARNING,
                            "Scanned xref at {0} could not be parsed ({1}); rebuilding from object scan",
                            new Object[]{xrefPos, xrefScanFailure.getMessage()});
                    rebuildFromObjectScan();
                }
            } else {
                LOGGER.log(Level.WARNING,
                        "Cannot locate xref keyword anywhere — rebuilding from object scan as last resort");
                rebuildFromObjectScan();
            }
        }

        // Follow /Prev chain for incremental updates
        if (trailerDictionary != null) {
            PdfBase prevObj = trailerDictionary.get(PdfName.of("Prev"));
            if (prevObj instanceof PdfInteger) {
                long prevOffset = ((PdfInteger) prevObj).longValue();
                if (prevOffset <= 0) {
                    LOGGER.log(Level.FINE, "Ignoring /Prev offset {0} (invalid)", prevOffset);
                } else {
                    LOGGER.log(Level.FINE, "Following /Prev chain to offset {0}", prevOffset);
                    PdfDictionary savedTrailer = trailerDictionary;
                    try {
                        parse(prevOffset);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to parse /Prev xref at offset {0}: {1}",
                                new Object[]{prevOffset, e.getMessage()});
                    }
                    trailerDictionary = savedTrailer;
                }
            }

            // Handle hybrid-reference PDFs (§7.5.8.4): /XRefStm points to
            // an additional xref stream with extra entries
            PdfBase xrefStmObj = trailerDictionary.get(PdfName.of("XRefStm"));
            if (xrefStmObj instanceof PdfInteger) {
                long xrefStmOffset = ((PdfInteger) xrefStmObj).longValue() + baseOffset;
                if (visitedOffsets.contains(xrefStmOffset)) {
                    LOGGER.log(Level.WARNING, "Cycle detected in /XRefStm at offset {0}, skipping", xrefStmOffset);
                } else {
                    LOGGER.log(Level.FINE, "Following /XRefStm to offset {0}", xrefStmOffset);
                    visitedOffsets.add(xrefStmOffset);
                    PdfDictionary savedTrailer2 = trailerDictionary;
                    try {
                        parseStreamFormat(xrefStmOffset);
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, "Failed to parse /XRefStm at offset {0}: {1}",
                                new Object[]{xrefStmOffset, e.getMessage()});
                    }
                    trailerDictionary = savedTrailer2;
                }
            }
        }
    }

    /**
     * Returns all cross-reference entries parsed so far.
     *
     * @return an unmodifiable view of the entries map
     */
    public Map<PdfObjectKey, XRefEntry> getEntries() {
        return entries;
    }

    /**
     * Returns the trailer dictionary.
     *
     * @return the trailer dictionary, or null if not yet parsed
     */
    public PdfDictionary getTrailerDictionary() {
        return trailerDictionary;
    }

    /**
     * Returns the delta to retry entry offsets with when the xref table was
     * relocated by scanning (see {@link #entries} doc), or 0.
     *
     * @return the entry-offset adjustment in bytes
     */
    public long getEntryOffsetAdjustment() {
        return entryOffsetAdjustment;
    }

    /**
     * Declares that the {@code %PDF-} header sits at {@code baseOffset} bytes
     * into the file (leading junk, e.g. a stripped container wrapper). Per the
     * ISO 32000-1 file-structure convention followed by Acrobat, every byte
     * offset stored in such a file — the {@code startxref} value, xref entry
     * offsets, {@code /Prev} and {@code /XRefStm} — is measured from the
     * header, not from the physical start of the file, so they are all
     * retried/shifted by this base.
     *
     * @param baseOffset the header position in bytes (0 = no adjustment)
     */
    public void setBaseOffset(long baseOffset) {
        this.baseOffset = baseOffset;
        if (baseOffset != 0 && entryOffsetAdjustment == 0) {
            entryOffsetAdjustment = baseOffset;
        }
    }

    /**
     * Finds the startxref position by searching backward from the end of the file.
     * Looks for the "startxref" keyword followed by a number.
     *
     * @param reader the random-access reader for the PDF file
     * @return the xref offset value
     * @throws IOException if startxref cannot be found
     */
    public static long findStartxref(RandomAccessReader reader) throws IOException {
        // Search for "startxref" backward from end of file
        byte[] pattern = "startxref".getBytes(StandardCharsets.US_ASCII);
        long pos = reader.findBackward(pattern, reader.getLength());

        if (pos == -1) {
            long recovered = recoverXrefOffsetWithoutStartxref(reader);
            if (recovered >= 0) {
                LOGGER.log(Level.WARNING,
                        "Missing 'startxref' keyword; recovered xref position {0} by scanning file tail",
                        recovered);
                return recovered;
            }
            throw new IOException("Cannot find 'startxref' keyword in PDF file");
        }

        // Position after "startxref"
        reader.seek(pos + pattern.length);

        // Skip whitespace
        while (true) {
            int c = reader.peek();
            if (c == -1) {
                throw new IOException("Unexpected EOF after 'startxref'");
            }
            if (PDFLexer.isWhitespace(c)) {
                reader.read();
            } else {
                break;
            }
        }

        // Read the number
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = reader.peek();
            if (c >= '0' && c <= '9') {
                reader.read();
                sb.append((char) c);
            } else {
                break;
            }
        }

        if (sb.length() == 0) {
            throw new IOException("Expected number after 'startxref' at position " + pos);
        }

        long xrefOffset = Long.parseLong(sb.toString());
        LOGGER.log(Level.FINE, "Found startxref: offset={0}", xrefOffset);
        return xrefOffset;
    }

    private static long recoverXrefOffsetWithoutStartxref(RandomAccessReader reader) throws IOException {
        long xrefPos = findStandaloneXrefBackward(reader);
        if (xrefPos >= 0) {
            return xrefPos;
        }
        return findNearestXrefStreamObjectBackward(reader);
    }

    private static long findStandaloneXrefBackward(RandomAccessReader reader) throws IOException {
        byte[] pattern = "xref".getBytes(StandardCharsets.US_ASCII);
        long searchFrom = reader.getLength();
        while (true) {
            long pos = reader.findBackward(pattern, searchFrom);
            if (pos < 0) {
                return -1;
            }
            if (pos >= 5) {
                reader.seek(pos - 5);
                byte[] prefix = reader.readFully(5);
                if ("start".equals(new String(prefix, StandardCharsets.US_ASCII))) {
                    searchFrom = pos - 1;
                    continue;
                }
            }
            return pos;
        }
    }

    private static long findNearestXrefStreamObjectBackward(RandomAccessReader reader) throws IOException {
        byte[] marker = "/Type/XRef".getBytes(StandardCharsets.US_ASCII);
        long searchFrom = reader.getLength();
        while (true) {
            long typePos = reader.findBackward(marker, searchFrom);
            if (typePos < 0) {
                return -1;
            }
            long windowStart = Math.max(0, typePos - 256);
            int len = (int) Math.min(512, reader.getLength() - windowStart);
            reader.seek(windowStart);
            String text = new String(reader.readFully(len), StandardCharsets.US_ASCII);
            int anchor = (int) (typePos - windowStart);
            long objectPos = findNearestObjectHeader(windowStart, text, anchor);
            if (objectPos >= 0) {
                return objectPos;
            }
            searchFrom = typePos - 1;
        }
    }

    /**
     * Scans the file for the "xref" keyword or an xref stream object.
     * Used as fallback when startxref points to an invalid location.
     *
     * @return the byte offset of the xref, or -1 if not found
     * @throws IOException if an I/O error occurs
     */
    private long scanForXref() throws IOException {
        byte[] pattern = "xref".getBytes(StandardCharsets.US_ASCII);
        long searchFrom = reader.getLength();
        while (true) {
            long pos = reader.findBackward(pattern, searchFrom);
            if (pos < 0) {
                return -1;
            }
            // Skip matches that are part of "startxref"
            if (pos >= 5) {
                reader.seek(pos - 5);
                byte[] prefix = reader.readFully(5);
                if (new String(prefix, StandardCharsets.US_ASCII).equals("start")) {
                    // This is "startxref", not a standalone "xref" — keep searching before this match
                    searchFrom = pos - 1;
                    continue;
                }
            }
            LOGGER.log(Level.FINE, "Scan found 'xref' keyword at position {0}", pos);
            return pos;
        }
    }

    /**
     * Last-resort xref recovery: walks the entire file looking for indirect
     * object headers ({@code N G obj}) and synthesises an in-use xref entry
     * for each one. Then constructs a minimal trailer dictionary by locating
     * the catalog (an indirect object whose body contains
     * {@code /Type /Catalog}) and stamping it as {@code /Root} so downstream
     * parsing can proceed against a truncated or trailer-less file.
     *
     * <p>Used when the file is corrupt enough that neither the regular xref
     * table nor an xref stream can be parsed (truncated PDFs, missing
     * trailer, mid-stream EOF). Mirrors the &quot;rebuild xref&quot; recovery
     * mode of Acrobat / pdf.js / Aspose.</p>
     */
    private void rebuildFromObjectScan() throws IOException {
        // Read the file in slabs to avoid loading the entire body when very
        // large; 4 MiB slab + 64-byte overlap to catch headers that straddle
        // boundaries.
        long fileLen = reader.getLength();
        if (fileLen <= 0) {
            throw new IOException("Cannot rebuild xref: file is empty");
        }
        final int slab = 4 * 1024 * 1024;
        final int overlap = 64;

        // Pattern matched: ASCII-only "N G obj" with whitespace between fields,
        // where N >= 1 and G >= 0. We do this with a small state machine so we
        // don't need to materialise the whole file as a String.
        int firstCatalogObject = -1;
        int firstCatalogGen = 0;

        long absStart = 0;
        while (absStart < fileLen) {
            // A rebuild scan over a multi-GB file runs for minutes; honour
            // cancellation per slab so a timed-out worker unwinds instead of
            // spinning as a zombie.
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("xref rebuild interrupted");
            }
            long thisLen = Math.min(slab, fileLen - absStart);
            // include overlap for header detection
            long readEnd = Math.min(fileLen, absStart + thisLen + overlap);
            int sliceLen = (int) (readEnd - absStart);
            reader.seek(absStart);
            byte[] buf = reader.readFully(sliceLen);

            int i = 0;
            while (i + 6 < buf.length) {              // need at least "N G obj"
                if (!isDigit(buf[i]) || (i > 0 && !isWhiteOrPdfDelim(buf[i - 1]))) {
                    i++; continue;
                }
                int numStart = i;
                while (i < buf.length && isDigit(buf[i])) i++;
                int numEnd = i;
                if (numEnd == numStart || i >= buf.length) break;
                if (!isWhite(buf[i])) continue;
                while (i < buf.length && isWhite(buf[i])) i++;
                if (!isDigit(buf[i])) continue;
                int genStart = i;
                while (i < buf.length && isDigit(buf[i])) i++;
                int genEnd = i;
                if (genEnd == genStart || i >= buf.length) break;
                if (!isWhite(buf[i])) continue;
                while (i < buf.length && isWhite(buf[i])) i++;
                if (i + 3 > buf.length) break;
                if (buf[i] == 'o' && buf[i + 1] == 'b' && buf[i + 2] == 'j'
                        && (i + 3 == buf.length || !isObjectIdChar(buf[i + 3]))) {
                    long absObjPos = absStart + numStart;
                    int objNum = parseAsciiInt(buf, numStart, numEnd);
                    int gen = parseAsciiInt(buf, genStart, genEnd);
                    if (objNum > 0 && gen >= 0) {
                        gen = sanitizeGeneration(objNum, gen);
                        PdfObjectKey key = new PdfObjectKey(objNum, gen);
                        // Keep the LAST (highest-offset) match per key: in an
                        // incrementally-updated PDF (§7.5.6) later definitions of an
                        // object supersede earlier ones, so the most recent occurrence
                        // in file order is authoritative. (Earlier code kept the first
                        // match, which resolved incrementally-updated page trees to
                        // their original revision — e.g. a 4-revision file reported
                        // /Count 1 instead of 4.) Matches Acrobat / pdf.js.
                        entries.put(key, XRefEntry.inUse(objNum, gen, absObjPos));
                        // Catalog detection: peek at the first ~512 bytes after "obj"
                        // looking for "/Type /Catalog" or "/Type/Catalog". Keep the
                        // last catalog found, for the same incremental-update reason.
                        if (looksLikeCatalog(buf, i + 3)) {
                            firstCatalogObject = objNum;
                            firstCatalogGen = gen;
                        }
                    }
                    i += 3;
                }
            }
            absStart += thisLen;
        }

        if (entries.isEmpty()) {
            throw new IOException("Cannot rebuild xref: no indirect objects found in file");
        }
        LOGGER.log(Level.WARNING,
                "Rebuilt {0} xref entries from object scan; catalog candidate = {1}",
                new Object[]{entries.size(), firstCatalogObject < 0 ? "<none>" : firstCatalogObject});

        // Synthesize a minimal trailer when none could be parsed. We only
        // create one if no real trailer was set earlier in this xref-walk —
        // a real trailer always has more accurate /Root/Info refs.
        if (trailerDictionary == null) {
            PdfDictionary t = new PdfDictionary();
            t.set(PdfName.of("Size"),
                    org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(entries.size() + 1));
            if (firstCatalogObject > 0) {
                t.set(PdfName.of("Root"),
                        new org.aspose.pdf.engine.pdfobjects.PdfObjectReference(
                                new PdfObjectKey(firstCatalogObject,
                                        sanitizeGeneration(firstCatalogObject, firstCatalogGen)),
                                k -> null));   // will be re-bound by PDFParser when it dereferences
            }
            trailerDictionary = t;
        }
    }

    private static boolean isDigit(byte b) { return b >= '0' && b <= '9'; }
    private static boolean isWhite(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n' || b == '\f' || b == 0;
    }
    private static boolean isWhiteOrPdfDelim(byte b) {
        return isWhite(b) || b == '<' || b == '>' || b == '[' || b == ']'
                || b == '(' || b == ')' || b == '{' || b == '}' || b == '/' || b == '%';
    }
    /** True if the byte may be part of a PDF identifier — used to reject "objX" prefix matches. */
    private static boolean isObjectIdChar(byte b) {
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_';
    }
    private static int parseAsciiInt(byte[] buf, int start, int end) {
        int v = 0;
        for (int i = start; i < end; i++) v = v * 10 + (buf[i] - '0');
        return v;
    }
    /** Looks for "/Type /Catalog" or "/Type/Catalog" within the first 512 bytes of an obj body. */
    private static boolean looksLikeCatalog(byte[] buf, int from) {
        int end = Math.min(buf.length, from + 512);
        // Cheap sequential search — both whitespace variants.
        byte[] needleA = "/Type /Catalog".getBytes(StandardCharsets.US_ASCII);
        byte[] needleB = "/Type/Catalog".getBytes(StandardCharsets.US_ASCII);
        return indexOfBytes(buf, from, end, needleA) >= 0
                || indexOfBytes(buf, from, end, needleB) >= 0;
    }
    private static int indexOfBytes(byte[] hay, int from, int end, byte[] needle) {
        outer:
        for (int i = from; i + needle.length <= end; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /**
     * Scans nearby a given position for an indirect object header (N M obj).
     * Returns the position of the object number, or -1 if not found.
     */
    private long scanForObjNear(long position, int range) throws IOException {
        long start = Math.max(0, position - range);
        long end = Math.min(reader.getLength(), position + range);

        // Scan for " obj" pattern in the range
        byte[] objPattern = " obj".getBytes(StandardCharsets.US_ASCII);
        reader.seek(start);
        int bufLen = (int) (end - start);
        if (bufLen <= 0) return -1;
        byte[] buf = reader.readFully(Math.min(bufLen, range * 2));
        String text = new String(buf, StandardCharsets.US_ASCII);

        int idx = 0;
        while ((idx = text.indexOf(" obj", idx)) >= 0) {
            // Walk backwards to find "N M obj" pattern start
            int objPos = idx;
            // Find the gen number before " obj"
            int genEnd = idx;
            int genStart = genEnd - 1;
            while (genStart >= 0 && text.charAt(genStart) >= '0' && text.charAt(genStart) <= '9') genStart--;
            if (genStart < genEnd - 1) {
                // Find whitespace before gen number
                if (genStart >= 0 && (text.charAt(genStart) == ' ' || text.charAt(genStart) == '\n'
                        || text.charAt(genStart) == '\r' || text.charAt(genStart) == '\t')) {
                    // Find obj number before whitespace
                    int numEnd = genStart;
                    int numStart = numEnd - 1;
                    while (numStart >= 0 && text.charAt(numStart) >= '0' && text.charAt(numStart) <= '9') numStart--;
                    numStart++;
                    if (numStart < numEnd) {
                        long candidatePos = start + numStart;
                        LOGGER.log(Level.FINE, "scanForObjNear found candidate at {0}", candidatePos);
                        return candidatePos;
                    }
                }
            }
            idx++;
        }
        return -1;
    }

    private long scanForXRefStreamNear(long position, int range) throws IOException {
        long start = Math.max(0, position - range);
        long end = Math.min(reader.getLength(), position + range);
        int len = (int) Math.min(Integer.MAX_VALUE, end - start);
        if (len <= 0) {
            return -1;
        }
        reader.seek(start);
        byte[] bytes = reader.readFully(len);
        String text = new String(bytes, StandardCharsets.US_ASCII);
        int idx = 0;
        while ((idx = text.indexOf("/Type", idx)) >= 0) {
            int xrefIdx = text.indexOf("/XRef", idx);
            if (xrefIdx < 0) {
                break;
            }
            if (xrefIdx - idx <= 96) {
                long objectPos = findNearestObjectHeader(start, text, idx);
                if (objectPos >= 0) {
                    return objectPos;
                }
            }
            idx = xrefIdx + 5;
        }
        return scanForObjNear(position, range);
    }

    private static long findNearestObjectHeader(long bufferStart, String text, int anchorIndex) {
        int searchFrom = Math.max(0, anchorIndex);
        while (searchFrom >= 0) {
            int objIdx = text.lastIndexOf(" obj", searchFrom);
            if (objIdx < 0) {
                return -1;
            }
            int genEnd = objIdx - 1;
            while (genEnd >= 0 && Character.isWhitespace(text.charAt(genEnd))) {
                genEnd--;
            }
            int genStart = genEnd;
            while (genStart >= 0 && Character.isDigit(text.charAt(genStart))) {
                genStart--;
            }
            if (genStart == genEnd) {
                searchFrom = objIdx - 1;
                continue;
            }
            int objNumEnd = genStart - 1;
            while (objNumEnd >= 0 && Character.isWhitespace(text.charAt(objNumEnd))) {
                objNumEnd--;
            }
            int objNumStart = objNumEnd;
            while (objNumStart >= 0 && Character.isDigit(text.charAt(objNumStart))) {
                objNumStart--;
            }
            if (objNumStart == objNumEnd) {
                searchFrom = objIdx - 1;
                continue;
            }
            return bufferStart + objNumStart + 1;
        }
        return -1;
    }

    /**
     * Parses a text-format cross-reference table (§7.5.4).
     * Format:
     * <pre>
     * xref
     * 0 6
     * 0000000000 65535 f \r\n
     * 0000000010 00000 n \r\n
     * ...
     * trailer
     * &lt;&lt; /Size 6 /Root 1 0 R &gt;&gt;
     * </pre>
     */
    private void parseTableFormat() throws IOException {
        // Consume "xref" keyword (may be "xref" or "xrefN M" in non-conforming PDFs)
        PDFLexer.Token token = lexer.nextToken();
        if (token.getType() != PDFLexer.TokenType.KEYWORD || !token.getValue().startsWith("xref")) {
            throw new IOException("Expected 'xref' keyword, got: " + token);
        }
        // Handle "xrefN M..." — digits glued to "xref" without whitespace
        if (token.getValue().length() > 4) {
            // Seek back so the digits after "xref" are re-lexed as integers
            reader.seek(token.getPosition() + 4);
            lexer.clearPeek();
        }

        // Parse subsections until we hit "trailer"
        while (true) {
            token = lexer.peekToken();
            if (token.getType() == PDFLexer.TokenType.KEYWORD && "trailer".equals(token.getValue())) {
                break;
            }
            if (token.getType() == PDFLexer.TokenType.EOF) {
                throw new IOException("Unexpected EOF in xref table");
            }

            // Read subsection header: firstObjectNumber count
            token = lexer.nextToken();
            if (token.getType() != PDFLexer.TokenType.INTEGER) {
                throw new IOException("Expected integer (first object number) in xref subsection, got: " + token);
            }
            int firstObjectNumber = Integer.parseInt(token.getValue());

            token = lexer.nextToken();
            if (token.getType() != PDFLexer.TokenType.INTEGER) {
                throw new IOException("Expected integer (count) in xref subsection, got: " + token);
            }
            int count = Integer.parseInt(token.getValue());

            // Some non-conforming PDFs write the free head entry (obj 0, gen 65535)
            // with a wrong subsection start (e.g. "1 7" instead of "0 7").
            // Detect and fix: if first entry would be gen 65535 free but firstObjectNumber != 0,
            // shift the numbering so the free head is object 0.
            // We'll detect this after reading the first entry and potentially fix up.
            LOGGER.log(Level.FINE, "Xref subsection: first={0}, count={1}", new Object[]{firstObjectNumber, count});

            int numberingOffset = 0; // will be set to -firstObjectNumber if off-by-one detected

            // Read entries: each is "OOOOOOOOOO GGGGG n|f" followed by EOL
            // We use the lexer to read the three tokens per entry
            for (int i = 0; i < count; i++) {
                // Corrupt tables can declare millions of entries over a huge
                // file — honour cancellation so a timed-out worker unwinds
                // instead of spinning (observed zombie: 38586.pdf).
                if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                    throw new IOException("xref table parse interrupted");
                }
                int objectNumber = firstObjectNumber + numberingOffset + i;

                token = lexer.nextToken();
                if (token.getType() == PDFLexer.TokenType.KEYWORD && "trailer".equals(token.getValue())) {
                    LOGGER.log(Level.WARNING,
                            "Xref subsection {0} {1} ended early at object {2}; recovering at trailer",
                            new Object[]{firstObjectNumber, count, objectNumber});
                    reader.seek(token.getPosition());
                    lexer.clearPeek();
                    break;
                }
                if (token.getType() != PDFLexer.TokenType.INTEGER) {
                    throw new IOException("Expected offset in xref entry for object " + objectNumber + ", got: " + token);
                }
                long offset = Long.parseLong(token.getValue());

                token = lexer.nextToken();
                if (token.getType() != PDFLexer.TokenType.INTEGER) {
                    throw new IOException("Expected generation in xref entry for object " + objectNumber + ", got: " + token);
                }
                int generation = Integer.parseInt(token.getValue());

                token = lexer.nextToken();
                if (token.getType() != PDFLexer.TokenType.KEYWORD) {
                    throw new IOException("Expected 'n' or 'f' in xref entry for object " + objectNumber + ", got: " + token);
                }
                String entryType = token.getValue();

                // Detect off-by-one in subsection numbering:
                // If the first entry is a free entry with gen=65535 (or a malformed
                // ≥65535 we'd clamp to 65535) and offset=0, but objectNumber != 0,
                // this PDF has a wrong subsection start.
                if (i == 0 && numberingOffset == 0 && "f".equals(entryType)
                        && generation >= 65535 && offset == 0 && objectNumber != 0) {
                    LOGGER.log(Level.WARNING,
                            "Xref subsection starts at {0} but first entry is free head (gen 65535). " +
                            "Adjusting numbering to start at 0.",
                            firstObjectNumber);
                    numberingOffset = -firstObjectNumber;
                    objectNumber = 0;
                }

                int sanitizedGen = sanitizeGeneration(objectNumber, generation);
                PdfObjectKey key = new PdfObjectKey(objectNumber, sanitizedGen);

                // putIfAbsent: later (more recent) entries take precedence
                if (!entries.containsKey(key)) {
                    if ("n".equals(entryType)) {
                        entries.put(key, XRefEntry.inUse(objectNumber, sanitizedGen, offset));
                    } else if ("f".equals(entryType)) {
                        entries.put(key, XRefEntry.free(objectNumber, sanitizedGen, offset));
                    } else {
                        throw new IOException("Invalid xref entry type '" + entryType + "' for object " + objectNumber);
                    }
                }
            }
        }

        // Consume "trailer" keyword
        lexer.nextToken();

        // Parse trailer dictionary using a mini object parser
        trailerDictionary = parseTrailerDictionary();
        LOGGER.log(Level.FINE, "Parsed trailer dictionary with {0} entries",
                trailerDictionary.size());
    }

    /**
     * Parses a cross-reference stream (§7.5.8).
     * The xref stream is an indirect object containing a stream
     * with /Type /XRef in its dictionary.
     */
    private void parseStreamFormat(long position) throws IOException {
        reader.seek(position);
        lexer.clearPeek();

        // Read: objNum genNum obj
        PDFLexer.Token objNumToken = lexer.nextToken();
        PDFLexer.Token genNumToken = lexer.nextToken();
        PDFLexer.Token objKeyword = lexer.nextToken();

        if (objNumToken.getType() != PDFLexer.TokenType.INTEGER
                || genNumToken.getType() != PDFLexer.TokenType.INTEGER
                || objKeyword.getType() != PDFLexer.TokenType.KEYWORD
                || !"obj".equals(objKeyword.getValue())) {
            // Try scanning nearby for the indirect object header (offset may be slightly wrong)
            long recovered = scanForObjNear(position, 256);
            if (recovered >= 0) {
                LOGGER.log(Level.WARNING, "XRef stream offset {0} was wrong, found obj at {1}",
                        new Object[]{position, recovered});
                reader.seek(recovered);
                lexer.clearPeek();
                objNumToken = lexer.nextToken();
                genNumToken = lexer.nextToken();
                objKeyword = lexer.nextToken();
                if (objNumToken.getType() != PDFLexer.TokenType.INTEGER
                        || genNumToken.getType() != PDFLexer.TokenType.INTEGER
                        || objKeyword.getType() != PDFLexer.TokenType.KEYWORD
                        || !"obj".equals(objKeyword.getValue())) {
                    throw new IOException("Expected indirect object at xref stream position " + position);
                }
            } else {
                throw new IOException("Expected indirect object at xref stream position " + position);
            }
        }

        // Parse the dictionary
        PDFLexer.Token dictOpen = lexer.nextToken();
        if (dictOpen.getType() != PDFLexer.TokenType.DICT_OPEN) {
            throw new IOException("Expected '<<' for xref stream dictionary at position " + position);
        }

        PdfDictionary streamDict = parseDictionaryContents();
        PdfBase typeObj = streamDict.get(PdfName.of("Type"));
        PdfBase wObj = streamDict.get(PdfName.of("W"));
        boolean isXrefType = typeObj instanceof PdfName && "XRef".equals(((PdfName) typeObj).getName());
        if (!isXrefType && !(wObj instanceof PdfArray)) {
            throw new IOException("Indirect object at position " + position + " is not an xref stream");
        }

        // Read stream keyword and data
        PDFLexer.Token streamToken = lexer.nextToken();
        if (streamToken.getType() != PDFLexer.TokenType.KEYWORD || !"stream".equals(streamToken.getValue())) {
            throw new IOException("Expected 'stream' keyword after xref stream dictionary");
        }

        // After "stream" keyword, skip CR+LF or LF (per §7.3.8.1)
        int c = reader.peek();
        if (c == '\r') {
            reader.read(); // consume CR
            if (reader.peek() == '\n') {
                reader.read(); // consume LF
            }
        } else if (c == '\n') {
            reader.read(); // consume LF
        }

        // Read stream data based on /Length
        PdfBase lengthObj = streamDict.get(PdfName.of("Length"));
        if (!(lengthObj instanceof PdfInteger)) {
            throw new IOException("Xref stream missing /Length");
        }
        int length = ((PdfInteger) lengthObj).intValue();
        byte[] rawData = reader.readFully(length);

        // Decode the stream data (apply filters)
        byte[] decodedData = decodeStreamData(rawData, streamDict);

        // Parse xref stream entries
        parseXRefStreamEntries(decodedData, streamDict);

        // The stream dictionary IS the trailer for this xref section.
        // We MUST overwrite unconditionally so that the /Prev chain loop in parse()
        // reads /Prev from the current level's trailer, not a stale first-level one.
        // This is symmetric with parseTableFormat() which assigns unconditionally.
        // The authoritative top-level trailer (used for document metadata, /Root,
        // /Info, /Encrypt, /ID) is saved separately by parse() BEFORE descending
        // into the /Prev chain — see the `savedTrailer` invariant there.
        trailerDictionary = streamDict;
    }

    /**
     * Decodes stream data by applying filters specified in the stream dictionary
     * using FilterFactory. Supports /DecodeParms including /Predictor.
     */
    private byte[] decodeStreamData(byte[] data, PdfDictionary streamDict) throws IOException {
        PdfBase filterObj = streamDict.get(PdfName.of("Filter"));
        if (filterObj == null) {
            return data;
        }

        // Build filter name list
        java.util.List<PdfName> filters = new java.util.ArrayList<>();
        if (filterObj instanceof PdfName) {
            filters.add((PdfName) filterObj);
        } else if (filterObj instanceof PdfArray) {
            PdfArray arr = (PdfArray) filterObj;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase f = arr.get(i);
                if (f instanceof PdfName) {
                    filters.add((PdfName) f);
                }
            }
        }

        if (filters.isEmpty()) {
            return data;
        }

        // Build decode params list
        java.util.List<PdfDictionary> params = null;
        PdfBase dpObj = streamDict.get(PdfName.of("DecodeParms"));
        if (dpObj instanceof PdfDictionary) {
            params = java.util.Collections.singletonList((PdfDictionary) dpObj);
        } else if (dpObj instanceof PdfArray) {
            PdfArray dpArr = (PdfArray) dpObj;
            params = new java.util.ArrayList<>(dpArr.size());
            for (int i = 0; i < dpArr.size(); i++) {
                PdfBase item = dpArr.get(i);
                params.add(item instanceof PdfDictionary ? (PdfDictionary) item : null);
            }
        }

        return FilterFactory.decodeChain(data, filters, params);
    }

    /**
     * Parses cross-reference stream entries from decoded stream data (§7.5.8.3).
     *
     * @param data       the decoded stream data
     * @param streamDict the stream dictionary containing /W, /Size, and optionally /Index
     */
    private void parseXRefStreamEntries(byte[] data, PdfDictionary streamDict) throws IOException {
        // Read /W array: [w1 w2 w3] — field widths
        PdfBase wObj = streamDict.get(PdfName.of("W"));
        if (!(wObj instanceof PdfArray)) {
            throw new IOException("Xref stream missing /W array");
        }
        PdfArray wArray = (PdfArray) wObj;
        if (wArray.size() != 3) {
            throw new IOException("Xref stream /W array must have 3 elements, got " + wArray.size());
        }
        int w1 = ((PdfInteger) wArray.get(0)).intValue();
        int w2 = ((PdfInteger) wArray.get(1)).intValue();
        int w3 = ((PdfInteger) wArray.get(2)).intValue();
        int entrySize = w1 + w2 + w3;

        // Read /Size
        PdfBase sizeObj = streamDict.get(PdfName.of("Size"));
        if (!(sizeObj instanceof PdfInteger)) {
            throw new IOException("Xref stream missing /Size");
        }

        // Read /Index (optional, default is [0 Size])
        int[] subsections;
        PdfBase indexObj = streamDict.get(PdfName.of("Index"));
        if (indexObj instanceof PdfArray) {
            PdfArray indexArray = (PdfArray) indexObj;
            subsections = new int[indexArray.size()];
            for (int i = 0; i < indexArray.size(); i++) {
                subsections[i] = ((PdfInteger) indexArray.get(i)).intValue();
            }
        } else {
            int size = ((PdfInteger) sizeObj).intValue();
            subsections = new int[]{0, size};
        }

        // Parse entries
        int dataOffset = 0;
        for (int s = 0; s < subsections.length; s += 2) {
            int firstObj = subsections[s];
            int count = subsections[s + 1];

            for (int i = 0; i < count; i++) {
                if (dataOffset + entrySize > data.length) {
                    throw new IOException("Xref stream data truncated");
                }

                // Read fields
                int field1 = readFieldValue(data, dataOffset, w1);
                dataOffset += w1;
                long field2 = readFieldValueLong(data, dataOffset, w2);
                dataOffset += w2;
                int field3 = readFieldValue(data, dataOffset, w3);
                dataOffset += w3;

                // Default type is 1 if w1 == 0
                int type = (w1 == 0) ? 1 : field1;
                int objectNumber = firstObj + i;

                PdfObjectKey key;
                switch (type) {
                    case 0: // Free
                        int freeGen = sanitizeGeneration(objectNumber, field3);
                        key = new PdfObjectKey(objectNumber, freeGen);
                        if (!entries.containsKey(key)) {
                            entries.put(key, XRefEntry.free(objectNumber, freeGen, field2));
                        }
                        break;
                    case 1: // In-use
                        int inUseGen = sanitizeGeneration(objectNumber, field3);
                        key = new PdfObjectKey(objectNumber, inUseGen);
                        if (!entries.containsKey(key)) {
                            entries.put(key, XRefEntry.inUse(objectNumber, inUseGen, field2));
                        }
                        break;
                    case 2: // Compressed (objects inside an object stream always have gen=0 per §7.5.7)
                        key = new PdfObjectKey(objectNumber, 0);
                        if (!entries.containsKey(key)) {
                            entries.put(key, XRefEntry.compressed(objectNumber, (int) field2, field3));
                        }
                        break;
                    default:
                        LOGGER.log(Level.WARNING, "Unknown xref entry type {0} for object {1}",
                                new Object[]{type, objectNumber});
                        break;
                }
            }
        }

        LOGGER.log(Level.FINE, "Parsed {0} xref stream entries", entries.size());
    }

    /**
     * Reads a big-endian integer field from the data.
     */
    private int readFieldValue(byte[] data, int offset, int width) {
        if (width == 0) {
            return 0;
        }
        int value = 0;
        for (int i = 0; i < width; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    /**
     * Reads a big-endian long field from the data.
     */
    private long readFieldValueLong(byte[] data, int offset, int width) {
        if (width == 0) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < width; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    /**
     * Parses a trailer dictionary. Expects the current position to be at '&lt;&lt;'.
     */
    private PdfDictionary parseTrailerDictionary() throws IOException {
        PDFLexer.Token token = lexer.nextToken();
        if (token.getType() != PDFLexer.TokenType.DICT_OPEN) {
            throw new IOException("Expected '<<' for trailer dictionary, got: " + token);
        }
        return parseDictionaryContents();
    }

    /**
     * Parses dictionary contents after '&lt;&lt;' has been consumed.
     * Reads key-value pairs until '&gt;&gt;' is encountered.
     */
    private PdfDictionary parseDictionaryContents() throws IOException {
        PdfDictionary dict = new PdfDictionary();

        while (true) {
            PDFLexer.Token token = lexer.peekToken();
            if (token.getType() == PDFLexer.TokenType.DICT_CLOSE) {
                lexer.nextToken(); // consume '>>'
                break;
            }
            if (token.getType() == PDFLexer.TokenType.EOF) {
                // Trailer/xref dictionary truncated at EOF — log and return
                // partial contents so the rest of the open path can salvage
                // whatever objects were already parsed. Throwing here aborts
                // the whole file, which is worse than a best-effort trailer.
                LOGGER.log(Level.WARNING,
                        "EOF inside dictionary literal — returning {0} parsed entries",
                        dict.size());
                break;
            }

            // Key must be a name
            token = lexer.nextToken();
            if (token.getType() != PDFLexer.TokenType.NAME) {
                throw new IOException("Expected name as dictionary key, got: " + token);
            }
            PdfName key = PdfName.of(token.getValue());

            // Value
            PdfBase value = parseObject();
            dict.set(key, value);
        }

        return dict;
    }

    /**
     * Parses a single PDF object from the current lexer position.
     * Used for trailer dictionary values and xref stream dictionary values.
     */
    PdfBase parseObject() throws IOException {
        PDFLexer.Token token = lexer.peekToken();

        switch (token.getType()) {
            case INTEGER: {
                lexer.nextToken();
                long intVal = Long.parseLong(token.getValue());

                // Look ahead for "N R" pattern (indirect reference)
                // Save position after first integer so we can backtrack
                long posAfterFirstInt = reader.getPosition();

                PDFLexer.Token next = lexer.peekToken();
                if (next.getType() == PDFLexer.TokenType.INTEGER) {
                    PDFLexer.Token genToken = lexer.nextToken(); // consume gen number
                    PDFLexer.Token rToken = lexer.peekToken();
                    if (rToken.getType() == PDFLexer.TokenType.KEYWORD && "R".equals(rToken.getValue())) {
                        lexer.nextToken(); // consume 'R'
                        return new PdfObjectReference(
                                new PdfObjectKey((int) intVal, Integer.parseInt(genToken.getValue())));
                    }
                    // Not an indirect reference — backtrack to just after first integer
                    reader.seek(posAfterFirstInt);
                    lexer.clearPeek();
                }

                return PdfInteger.valueOf(intVal);
            }
            case REAL: {
                lexer.nextToken();
                return new PdfFloat(Float.parseFloat(token.getValue()));
            }
            case NAME: {
                lexer.nextToken();
                return PdfName.of(token.getValue());
            }
            case LITERAL_STRING: {
                lexer.nextToken();
                return new PdfString(
                        token.getValue().getBytes(StandardCharsets.ISO_8859_1));
            }
            case HEX_STRING: {
                lexer.nextToken();
                PdfString s = new PdfString(
                        token.getValue().getBytes(StandardCharsets.ISO_8859_1));
                s.setForceHex(true);
                return s;
            }
            case KEYWORD: {
                lexer.nextToken();
                String kw = token.getValue();
                if ("true".equals(kw)) {
                    return PdfBoolean.TRUE;
                } else if ("false".equals(kw)) {
                    return PdfBoolean.FALSE;
                } else if ("null".equals(kw)) {
                    return PdfNull.INSTANCE;
                }
                throw new IOException("Unexpected keyword in object: " + kw);
            }
            case ARRAY_OPEN: {
                lexer.nextToken();
                PdfArray array = new PdfArray();
                while (true) {
                    PDFLexer.Token peek = lexer.peekToken();
                    if (peek.getType() == PDFLexer.TokenType.ARRAY_CLOSE) {
                        lexer.nextToken();
                        break;
                    }
                    if (peek.getType() == PDFLexer.TokenType.EOF) {
                        // Truncated/corrupted PDFs may hit EOF mid-array; return
                        // what we have so far so the trailer/xref scan can keep
                        // making progress instead of aborting the whole open.
                        LOGGER.log(Level.WARNING,
                                "EOF inside array literal — returning {0} parsed elements",
                                array.size());
                        return array;
                    }
                    array.add(parseObject());
                }
                return array;
            }
            case DICT_OPEN: {
                lexer.nextToken();
                return parseDictionaryContents();
            }
            default:
                throw new IOException("Unexpected token: " + token);
        }
    }
}
