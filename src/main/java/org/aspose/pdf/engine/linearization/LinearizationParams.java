package org.aspose.pdf.engine.linearization;

import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.io.RandomAccessReader;
import org.aspose.pdf.engine.parser.PDFLexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Represents the linearization parameter dictionary (Table F.1, ISO 32000-1:2008).
 * This dictionary must appear within the first 1024 bytes of a linearized PDF.
 *
 * <p>Required keys:</p>
 * <ul>
 *   <li>/Linearized — version number (1.0)</li>
 *   <li>/L — file length in bytes</li>
 *   <li>/H — array [hintStreamOffset, hintStreamLength]</li>
 *   <li>/O — object number of first page's page object</li>
 *   <li>/E — offset of end of first page section</li>
 *   <li>/N — number of pages</li>
 *   <li>/T — offset of first entry in main xref table (or of main xref stream)</li>
 * </ul>
 */
public final class LinearizationParams {

    private static final Logger LOG = Logger.getLogger(LinearizationParams.class.getName());

    private double version = 1.0;
    private long fileLength;
    private long hintStreamOffset;
    private int hintStreamLength;
    private int firstPageObjNum;
    private long endOfFirstPage;
    private int numPages;
    private long mainXRefOffset;
    private int firstPageNumber; // /P, default 0

    /**
     * Parses linearization parameters from a PdfDictionary.
     *
     * @param dict the linearization parameter dictionary
     * @return the parsed parameters, or {@code null} if the dict is not a linearization dict
     */
    public static LinearizationParams parse(PdfDictionary dict) {
        if (dict == null) return null;
        PdfBase linValue = dict.get("Linearized");
        if (linValue == null) return null;

        LinearizationParams params = new LinearizationParams();
        params.version = dict.getFloat("Linearized", 1.0f);
        params.fileLength = dict.getLong("L", 0);
        params.firstPageObjNum = dict.getInt("O", 0);
        params.endOfFirstPage = dict.getLong("E", 0);
        params.numPages = dict.getInt("N", 0);
        params.mainXRefOffset = dict.getLong("T", 0);
        params.firstPageNumber = dict.getInt("P", 0);

        // /H is an array [offset, length]
        PdfBase hArray = dict.get("H");
        if (hArray instanceof PdfArray) {
            PdfArray h = (PdfArray) hArray;
            if (h.size() >= 2) {
                params.hintStreamOffset = h.getLong(0, 0);
                params.hintStreamLength = h.getInt(1, 0);
            }
        }
        return params;
    }

    /**
     * Writes this linearization parameter dictionary as a PDF dictionary.
     * The dictionary is written as an indirect object with the given object number.
     *
     * @return the PDF dictionary
     */
    public PdfDictionary toDictionary() {
        PdfDictionary dict = new PdfDictionary();
        dict.set(PdfName.of("Linearized"), new PdfFloat(1.0f));
        dict.set(PdfName.of("L"), PdfInteger.valueOf(fileLength));
        dict.set(PdfName.of("O"), PdfInteger.valueOf(firstPageObjNum));
        dict.set(PdfName.of("E"), PdfInteger.valueOf(endOfFirstPage));
        dict.set(PdfName.of("N"), PdfInteger.valueOf(numPages));
        dict.set(PdfName.of("T"), PdfInteger.valueOf(mainXRefOffset));
        if (firstPageNumber != 0) {
            dict.set(PdfName.of("P"), PdfInteger.valueOf(firstPageNumber));
        }

        PdfArray hArray = new PdfArray();
        hArray.add(PdfInteger.valueOf(hintStreamOffset));
        hArray.add(PdfInteger.valueOf(hintStreamLength));
        dict.set(PdfName.of("H"), hArray);

        return dict;
    }

    /**
     * Detects whether a PDF is linearized by scanning the first 1024 bytes
     * for a linearization parameter dictionary.
     *
     * @param reader the random access reader positioned at file start
     * @return the parsed parameters, or {@code null} if not linearized
     * @throws IOException if an I/O error occurs
     */
    public static LinearizationParams detect(RandomAccessReader reader) throws IOException {
        reader.seek(0);
        int scanLen = (int) Math.min(1024, reader.getLength());
        byte[] header = new byte[scanLen];
        int read = reader.read(header, 0, scanLen);
        if (read < 20) return null;

        String headerStr = new String(header, 0, read, StandardCharsets.US_ASCII);

        // Look for a dictionary containing /Linearized within the first object
        int linIdx = headerStr.indexOf("/Linearized");
        if (linIdx < 0) return null;

        // Find the enclosing "obj" / "endobj" to locate the dictionary
        // Parse using the lexer from the start of the first object
        int objIdx = headerStr.indexOf("obj");
        if (objIdx < 0 || objIdx > linIdx) return null;

        // Find the "<<" that starts the dictionary
        int dictStart = headerStr.indexOf("<<", objIdx);
        if (dictStart < 0 || dictStart > linIdx) return null;

        // Parse the dictionary using our lexer
        try {
            reader.seek(dictStart);
            PDFLexer lexer = new PDFLexer(reader);
            // parseDictionary expects the lexer to be positioned at '<<'
            // We need a minimal parser — just read the dict manually
            LinearizationParams params = parseLinDictFromReader(reader, dictStart, read);
            // A linearization dict is only VALID when /L equals the actual file
            // length (ISO 32000-1 Annex F.3.1). An incremental update appended
            // after the linearized generation leaves the dict in place but breaks
            // the invariant — the file is no longer linearized (PDFNEWNET_35605).
            if (params != null && params.fileLength != reader.getLength()) {
                LOG.fine(() -> "Stale linearization dict: /L " + params.fileLength
                        + " != file length " + reader.getLength());
                return null;
            }
            return params;
        } catch (Exception e) {
            LOG.fine(() -> "Failed to parse linearization dict: " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses the linearization dict from raw bytes at the given offset.
     */
    private static LinearizationParams parseLinDictFromReader(
            RandomAccessReader reader, int dictStart, int maxLen) throws IOException {
        // Simple: read bytes and extract key-value pairs
        reader.seek(dictStart);
        byte[] buf = new byte[Math.min(512, maxLen - dictStart)];
        int n = reader.read(buf, 0, buf.length);
        String dictStr = new String(buf, 0, n, StandardCharsets.US_ASCII);

        // Minimal extraction of numeric values from the linearization dict
        LinearizationParams params = new LinearizationParams();
        params.version = extractFloat(dictStr, "/Linearized", 1.0);
        params.fileLength = extractLong(dictStr, "/L");
        params.firstPageObjNum = (int) extractLong(dictStr, "/O");
        params.endOfFirstPage = extractLong(dictStr, "/E");
        params.numPages = (int) extractLong(dictStr, "/N");
        params.mainXRefOffset = extractLong(dictStr, "/T");
        params.firstPageNumber = (int) extractLong(dictStr, "/P");

        // Extract /H array
        int hIdx = dictStr.indexOf("/H");
        if (hIdx >= 0) {
            int bracketStart = dictStr.indexOf('[', hIdx);
            int bracketEnd = dictStr.indexOf(']', bracketStart);
            if (bracketStart >= 0 && bracketEnd > bracketStart) {
                String hContent = dictStr.substring(bracketStart + 1, bracketEnd).trim();
                String[] parts = hContent.split("\\s+");
                if (parts.length >= 2) {
                    params.hintStreamOffset = Long.parseLong(parts[0].trim());
                    params.hintStreamLength = Integer.parseInt(parts[1].trim());
                }
            }
        }

        if (params.fileLength == 0 && params.numPages == 0) return null;
        return params;
    }

    private static double extractFloat(String dict, String key, double def) {
        int searchFrom = 0;
        while (true) {
            int idx = dict.indexOf(key, searchFrom);
            if (idx < 0) return def;
            int afterKey = idx + key.length();
            if (afterKey < dict.length() && Character.isLetter(dict.charAt(afterKey))) {
                searchFrom = afterKey;
                continue;
            }
            String after = dict.substring(afterKey).trim();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < after.length() && i < 20; i++) {
                char c = after.charAt(i);
                if (Character.isDigit(c) || c == '.' || c == '-') sb.append(c);
                else if (sb.length() > 0) break;
            }
            try { return Double.parseDouble(sb.toString()); }
            catch (NumberFormatException e) { return def; }
        }
    }

    private static long extractLong(String dict, String key) {
        // Search for key followed by a non-alphabetic character to avoid
        // matching /L inside /Linearized etc.
        int searchFrom = 0;
        while (true) {
            int idx = dict.indexOf(key, searchFrom);
            if (idx < 0) return 0;
            int afterKey = idx + key.length();
            // Ensure the match is a complete key (next char is space, digit, or '[')
            if (afterKey < dict.length()) {
                char next = dict.charAt(afterKey);
                if (Character.isLetter(next)) {
                    // Part of a longer name like /Linearized — skip
                    searchFrom = afterKey;
                    continue;
                }
            }
            String after = dict.substring(afterKey).trim();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < after.length() && i < 20; i++) {
                char c = after.charAt(i);
                if (Character.isDigit(c) || c == '-') sb.append(c);
                else if (sb.length() > 0) break;
            }
            try { return Long.parseLong(sb.toString()); }
            catch (NumberFormatException e) { return 0; }
        }
    }

    // ─── Getters and setters ─────────────────────────────────────

    /** Returns the linearization version (always 1.0). */
    public double getVersion() { return version; }

    /** Returns the total file length (/L). */
    public long getFileLength() { return fileLength; }
    public void setFileLength(long fileLength) { this.fileLength = fileLength; }

    /** Returns the primary hint stream byte offset (/H[0]). */
    public long getHintStreamOffset() { return hintStreamOffset; }
    public void setHintStreamOffset(long offset) { this.hintStreamOffset = offset; }

    /** Returns the primary hint stream length (/H[1]). */
    public int getHintStreamLength() { return hintStreamLength; }
    public void setHintStreamLength(int length) { this.hintStreamLength = length; }

    /** Returns the first page's page object number (/O). */
    public int getFirstPageObjNum() { return firstPageObjNum; }
    public void setFirstPageObjNum(int num) { this.firstPageObjNum = num; }

    /** Returns the offset of end of first page section (/E). */
    public long getEndOfFirstPage() { return endOfFirstPage; }
    public void setEndOfFirstPage(long offset) { this.endOfFirstPage = offset; }

    /** Returns the number of pages (/N). */
    public int getNumPages() { return numPages; }
    public void setNumPages(int n) { this.numPages = n; }

    /** Returns the offset of the main xref table (/T). */
    public long getMainXRefOffset() { return mainXRefOffset; }
    public void setMainXRefOffset(long offset) { this.mainXRefOffset = offset; }

    /** Returns the first page number (/P), default 0. */
    public int getFirstPageNumber() { return firstPageNumber; }
    public void setFirstPageNumber(int num) { this.firstPageNumber = num; }

    /**
     * Validates that /L matches the actual file length.
     * If mismatched, the file has been modified and linearization is invalid.
     *
     * @param actualFileLength the actual file length in bytes
     * @return {@code true} if the linearization is still valid
     */
    public boolean isValid(long actualFileLength) {
        return fileLength == actualFileLength;
    }
}
