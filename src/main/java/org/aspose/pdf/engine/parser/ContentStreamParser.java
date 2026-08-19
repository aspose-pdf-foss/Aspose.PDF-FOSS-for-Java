package org.aspose.pdf.engine.parser;

import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.io.RandomAccessReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses PDF content streams into a list of {@link Operator} objects.
 * <p>
 * Content streams use the same token syntax as the rest of PDF (ISO 32000-1:2008, §7.8.2),
 * but the grammar is different: there are no "obj"/"endobj" wrappers, and keywords
 * are operator names (BT, ET, Tf, Td, Tj, TJ, q, Q, cm, re, m, l, etc.).
 * </p>
 * <p>
 * Tokens are accumulated as operands until a keyword (operator name) is encountered,
 * at which point a typed {@link Operator} subclass is created with the accumulated operands.
 * </p>
 */
public final class ContentStreamParser {

    private static final Logger LOGGER = Logger.getLogger(ContentStreamParser.class.getName());

    /**
     * When true (default), a lexically malformed numeric token aborts parsing
     * of the remaining content stream, mirroring Acrobat's damaged-stream
     * behaviour. Disable with {@code -Dparser.contentAbortOnLexError=false}.
     */
    private static final boolean ABORT_ON_LEX_ERROR =
            !"false".equalsIgnoreCase(System.getProperty("parser.contentAbortOnLexError"));

    private ContentStreamParser() {
        // Utility class
    }

    /**
     * Parses raw content stream bytes into a list of operators.
     *
     * @param streamData the raw (decoded) content stream bytes
     * @return the list of operators
     * @throws IOException if parsing fails
     * @throws IllegalArgumentException if streamData is null
     */
    public static List<Operator> parse(byte[] streamData) throws IOException {
        if (streamData == null) {
            throw new IllegalArgumentException("Stream data must not be null");
        }
        if (streamData.length == 0) {
            return new ArrayList<>();
        }

        // No defensive copy: the decoded content stream buffer is handed to us
        // for parsing only and can reach hundreds of MB (corpus 33809.pdf) —
        // the clone in fromBytes() doubled peak memory and OOM'd mass runs.
        RandomAccessReader reader = RandomAccessReader.fromBytesNoCopy(streamData);
        PDFLexer lexer = new PDFLexer(reader);
        List<Operator> operators = new ArrayList<>();
        List<PdfBase> operands = new ArrayList<>();

        while (true) {
            PDFLexer.Token token;
            try {
                token = lexer.nextToken();
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING,
                        "Recovering malformed content stream after lexer error: {0}",
                        ex.getMessage());
                break;
            }
            PDFLexer.TokenType type = token.getType();

            if (type == PDFLexer.TokenType.EOF) {
                break;
            }

            // A lexically malformed number ("669.835.566", a digitless "." or
            // "-") is the signature of a damaged stream region. Acrobat stops
            // executing the rest of the content stream at this point and keeps
            // what was already painted ("An error exists on this page");
            // executing the tail instead painted page-covering garbage
            // (corpus 46075.pdf carries 278 KB of corrupted ops after a valid
            // page). Mirror Acrobat. Disable: -Dparser.contentAbortOnLexError=false.
            if (token.isMalformed() && ABORT_ON_LEX_ERROR
                    && (type == PDFLexer.TokenType.INTEGER || type == PDFLexer.TokenType.REAL)) {
                LOGGER.log(Level.WARNING,
                        "Malformed numeric token {0} — dropping the remainder of the content stream like Acrobat",
                        token);
                break;
            }

            try {
            switch (type) {
                case INTEGER:
                    operands.add(parseIntegerValue(token, "content-stream operand"));
                    break;

                case REAL:
                    operands.add(parseRealValue(token));
                    break;

                case NAME:
                    operands.add(PdfName.of(token.getValue()));
                    break;

                case LITERAL_STRING:
                    operands.add(new PdfString(token.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                    break;

                case HEX_STRING:
                    PdfString hexStr = new PdfString(token.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    hexStr.setForceHex(true);
                    operands.add(hexStr);
                    break;

                case ARRAY_OPEN:
                    operands.add(parseArray(lexer));
                    break;

                case DICT_OPEN:
                    operands.add(parseDictionary(lexer));
                    break;

                case KEYWORD:
                    String keyword = token.getValue();

                    if ("BI".equals(keyword)) {
                        // Inline image: BI <dict pairs> ID <data> EI
                        Operator biOp = parseInlineImage(lexer, reader);
                        operators.add(biOp);
                        operands.clear();
                    } else if ("true".equals(keyword)) {
                        operands.add(org.aspose.pdf.engine.pdfobjects.PdfBoolean.TRUE);
                    } else if ("false".equals(keyword)) {
                        operands.add(org.aspose.pdf.engine.pdfobjects.PdfBoolean.FALSE);
                    } else if ("null".equals(keyword)) {
                        operands.add(org.aspose.pdf.engine.pdfobjects.PdfNull.INSTANCE);
                    } else if (operands.isEmpty() && isRunOnNoOperandOps(keyword)) {
                        // Some content streams pack multiple no-operand single-letter
                        // operators back-to-back without whitespace ("QQQQQ" → Q Q Q Q Q,
                        // see PDFNEWNET-33721). Emit one operator per character.
                        for (int j = 0; j < keyword.length(); j++) {
                            String single = keyword.substring(j, j + 1);
                            operators.add(createTypedOperator(single, java.util.Collections.emptyList()));
                        }
                        operands = new ArrayList<>();
                    } else {
                        // This is an operator — create typed subclass
                        Operator op = createTypedOperator(keyword, operands);
                        operators.add(op);
                        LOGGER.log(Level.FINER, "Parsed operator: {0} with {1} operands",
                                new Object[]{keyword, operands.size()});
                        operands = new ArrayList<>();
                    }
                    break;

                default:
                    LOGGER.log(Level.WARNING, "Unexpected token type in content stream: {0}", token);
                    break;
            }
            } catch (Exception bodyEx) {
                // A malformed operator / dictionary / array (e.g. a non-name key,
                // binary inline-image residue, or out-of-range operand) must not
                // discard the whole page. Stop here and return the operators parsed
                // so far — best-effort extraction, mirroring Acrobat / pdf.js.
                LOGGER.log(Level.WARNING,
                        "Recovering malformed content stream after parse error at token {0}: {1}",
                        new Object[]{token, bodyEx.getMessage()});
                break;
            }
        }

        // Any remaining operands without an operator are discarded (malformed stream)
        if (!operands.isEmpty()) {
            LOGGER.log(Level.WARNING, "Content stream ended with {0} unconsumed operands", operands.size());
        }

        reader.close();
        return operators;
    }

    /**
     * Parses a content stream from a PdfStream and returns an OperatorCollection.
     *
     * @param stream the PdfStream containing the content stream
     * @return the parsed operator collection
     * @throws IOException if parsing or decoding fails
     * @throws IllegalArgumentException if stream is null
     */
    public static OperatorCollection parseToCollection(PdfStream stream) throws IOException {
        if (stream == null) {
            throw new IllegalArgumentException("Stream must not be null");
        }
        byte[] data = stream.getDecodedData();
        List<Operator> ops = parse(data);
        return new OperatorCollection(ops);
    }

    /**
     * Parses raw content stream bytes and returns an OperatorCollection.
     *
     * @param data the raw (decoded) content stream bytes
     * @return the parsed operator collection
     * @throws IOException if parsing fails
     */
    public static OperatorCollection parseToCollection(byte[] data) throws IOException {
        List<Operator> ops = parse(data);
        return new OperatorCollection(ops);
    }

    /** Single-letter no-operand operators that PDF content streams sometimes pack without separators. */
    private static final String RUN_ON_NO_OPERAND_OPS = "QqSsfFnBbh";

    /**
     * Returns true if {@code keyword} is a contiguous repetition of single-letter
     * no-operand operators (e.g. "QQQ", "QqQ"). Used to split "QQQQQ" into 5 Q
     * operators (PDFNEWNET-33721).
     */
    private static boolean isRunOnNoOperandOps(String keyword) {
        if (keyword == null || keyword.length() < 2) {
            return false;
        }
        for (int i = 0; i < keyword.length(); i++) {
            if (RUN_ON_NO_OPERAND_OPS.indexOf(keyword.charAt(i)) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Creates a typed Operator subclass based on the operator keyword.
     * Falls back to generic Operator for unrecognized keywords.
     */
    private static Operator createTypedOperator(String keyword, List<PdfBase> operands) {
        switch (keyword) {
            // --- Graphics state ---
            case "q":  return new org.aspose.pdf.operators.GSave(operands);
            case "Q":  return new org.aspose.pdf.operators.GRestore(operands);
            case "cm": return new org.aspose.pdf.operators.ConcatenateMatrix(operands);
            case "w":  return new org.aspose.pdf.operators.SetLineWidth(operands);
            case "J":  return new org.aspose.pdf.operators.SetLineCap(operands);
            case "j":  return new org.aspose.pdf.operators.SetLineJoin(operands);
            case "M":  return new org.aspose.pdf.operators.SetMiterLimit(operands);
            case "d":  return new org.aspose.pdf.operators.SetDash(operands);
            case "ri": return new org.aspose.pdf.operators.SetColorRenderingIntent(operands);
            case "i":  return new org.aspose.pdf.operators.SetFlat(operands);
            case "gs": return new org.aspose.pdf.operators.GS(operands);

            // --- Path construction ---
            case "m":  return new org.aspose.pdf.operators.MoveTo(operands);
            case "l":  return new org.aspose.pdf.operators.LineTo(operands);
            case "c":  return new org.aspose.pdf.operators.CurveTo(operands);
            case "v":  return new org.aspose.pdf.operators.CurveTo1(operands);
            case "y":  return new org.aspose.pdf.operators.CurveTo2(operands);
            case "h":  return new org.aspose.pdf.operators.ClosePath(operands);
            case "re": return new org.aspose.pdf.operators.Re(operands);

            // --- Path painting ---
            case "S":  return new org.aspose.pdf.operators.Stroke(operands);
            case "s":  return new org.aspose.pdf.operators.ClosePathStroke(operands);
            case "f":  return new org.aspose.pdf.operators.Fill(operands);
            case "F":  return new org.aspose.pdf.operators.ObsoleteFill(operands);
            case "f*": return new org.aspose.pdf.operators.EOFill(operands);
            case "B":  return new org.aspose.pdf.operators.FillStroke(operands);
            case "B*": return new org.aspose.pdf.operators.EOFillStroke(operands);
            case "b":  return new org.aspose.pdf.operators.ClosePathFillStroke(operands);
            case "b*": return new org.aspose.pdf.operators.ClosePathEOFillStroke(operands);
            case "n":  return new org.aspose.pdf.operators.EndPath(operands);

            // --- Clipping ---
            case "W":  return new org.aspose.pdf.operators.Clip(operands);
            case "W*": return new org.aspose.pdf.operators.EOClip(operands);

            // --- Text objects ---
            case "BT": return new org.aspose.pdf.operators.BT(operands);
            case "ET": return new org.aspose.pdf.operators.ET(operands);

            // --- Text state ---
            case "Tc": return new org.aspose.pdf.operators.SetCharacterSpacing(operands);
            case "Tw": return new org.aspose.pdf.operators.SetWordSpacing(operands);
            case "Tz": return new org.aspose.pdf.operators.SetHorizontalTextScaling(operands);
            case "TL": return new org.aspose.pdf.operators.SetTextLeading(operands);
            case "Tf": return new org.aspose.pdf.operators.SelectFont(operands);
            case "Tr": return new org.aspose.pdf.operators.SetTextRenderingMode(operands);
            case "Ts": return new org.aspose.pdf.operators.SetTextRise(operands);

            // --- Text positioning ---
            case "Td": return new org.aspose.pdf.operators.MoveTextPosition(operands);
            case "TD": return new org.aspose.pdf.operators.MoveTextPositionSetLeading(operands);
            case "Tm": return new org.aspose.pdf.operators.SetTextMatrix(operands);
            case "T*": return new org.aspose.pdf.operators.MoveToNextLine(operands);

            // --- Text showing ---
            case "Tj": return new org.aspose.pdf.operators.ShowText(operands);
            case "TJ": return new org.aspose.pdf.operators.SetGlyphsPositionShowText(operands);
            case "'":  return new org.aspose.pdf.operators.MoveToNextLineShowText(operands);
            case "\"": return new org.aspose.pdf.operators.SetSpacingMoveToNextLineShowText(operands);

            // --- Color ---
            case "CS": return new org.aspose.pdf.operators.SetColorSpaceStroke(operands);
            case "cs": return new org.aspose.pdf.operators.SetColorSpace(operands);
            case "SC": return new org.aspose.pdf.operators.SetColorStroke(operands);
            case "SCN":return new org.aspose.pdf.operators.SetAdvancedColorStroke(operands);
            case "sc": return new org.aspose.pdf.operators.SetColor(operands);
            case "scn":return new org.aspose.pdf.operators.SetAdvancedColor(operands);
            case "G":  return new org.aspose.pdf.operators.SetGrayStroke(operands);
            case "g":  return new org.aspose.pdf.operators.SetGray(operands);
            case "RG": return new org.aspose.pdf.operators.SetRGBColorStroke(operands);
            case "rg": return new org.aspose.pdf.operators.SetRGBColor(operands);
            case "K":  return new org.aspose.pdf.operators.SetCMYKColorStroke(operands);
            case "k":  return new org.aspose.pdf.operators.SetCMYKColor(operands);

            // --- Shading ---
            case "sh": return new org.aspose.pdf.operators.ShFill(operands);

            // --- XObject ---
            case "Do": return new org.aspose.pdf.operators.Do(operands);

            // --- Marked content ---
            case "BMC":return new org.aspose.pdf.operators.BMC(operands);
            case "BDC":return new org.aspose.pdf.operators.BDC(operands);
            case "EMC":return new org.aspose.pdf.operators.EMC(operands);
            case "MP": return new org.aspose.pdf.operators.MP(operands);
            case "DP": return new org.aspose.pdf.operators.DP(operands);

            // --- Compatibility ---
            case "BX": return new org.aspose.pdf.operators.BX(operands);
            case "EX": return new org.aspose.pdf.operators.EX(operands);

            // --- Type 3 font ---
            case "d0": return new org.aspose.pdf.operators.SetCharWidth(operands);
            case "d1": return new org.aspose.pdf.operators.SetCharWidthBoundingBox(operands);

            // --- Fallback ---
            default: return new Operator(keyword, operands);
        }
    }

    /**
     * Maximum container nesting inside a content stream. Real content never
     * approaches this; damaged streams full of consecutive '[' bytes would
     * otherwise recurse to StackOverflowError.
     */
    private static final int MAX_NESTING_DEPTH = 64;

    private static PdfArray parseArray(PDFLexer lexer) throws IOException {
        return parseArray(lexer, 0);
    }

    private static PdfDictionary parseDictionary(PDFLexer lexer) throws IOException {
        return parseDictionary(lexer, 0);
    }

    /** Consumes tokens until the current array is balanced (recovery for over-deep nesting). */
    private static void skipBalancedArray(PDFLexer lexer) throws IOException {
        int depth = 1;
        while (depth > 0) {
            PDFLexer.Token t = lexer.nextToken();
            if (t.getType() == PDFLexer.TokenType.EOF) {
                return;
            }
            if (t.getType() == PDFLexer.TokenType.ARRAY_OPEN) {
                depth++;
            } else if (t.getType() == PDFLexer.TokenType.ARRAY_CLOSE) {
                depth--;
            }
        }
    }

    /** Consumes tokens until the current dictionary is balanced (recovery for over-deep nesting). */
    private static void skipBalancedDictionary(PDFLexer lexer) throws IOException {
        int depth = 1;
        while (depth > 0) {
            PDFLexer.Token t = lexer.nextToken();
            if (t.getType() == PDFLexer.TokenType.EOF) {
                return;
            }
            if (t.getType() == PDFLexer.TokenType.DICT_OPEN) {
                depth++;
            } else if (t.getType() == PDFLexer.TokenType.DICT_CLOSE) {
                depth--;
            }
        }
    }

    /**
     * Parses an array from the content stream. The opening '[' has already been consumed.
     */
    private static PdfArray parseArray(PDFLexer lexer, int depth) throws IOException {
        PdfArray array = new PdfArray();
        if (depth > MAX_NESTING_DEPTH) {
            LOGGER.warning("Content-stream array nesting exceeds " + MAX_NESTING_DEPTH
                    + "; skipping over-deep contents (damaged stream)");
            skipBalancedArray(lexer);
            return array;
        }
        while (true) {
            PDFLexer.Token token;
            try {
                token = lexer.nextToken();
            } catch (IOException ex) {
                LOGGER.log(Level.WARNING,
                        "Recovering malformed content-stream array after lexer error: {0}",
                        ex.getMessage());
                break;
            }
            PDFLexer.TokenType type = token.getType();

            if (type == PDFLexer.TokenType.EOF) {
                LOGGER.warning("Unexpected EOF inside content-stream array; returning recovered partial array");
                break;
            }
            if (type == PDFLexer.TokenType.ARRAY_CLOSE) {
                break;
            }
            if (token.isMalformed() && ABORT_ON_LEX_ERROR
                    && (type == PDFLexer.TokenType.INTEGER || type == PDFLexer.TokenType.REAL)) {
                // Same damaged-stream signature as in parse(): surface it so the
                // main loop stops executing the rest of the stream (Acrobat).
                throw new IOException("Malformed numeric token in content-stream array: " + token);
            }

            switch (type) {
                case INTEGER:
                    array.add(parseIntegerValue(token, "content-stream array"));
                    break;
                case REAL:
                    array.add(parseRealValue(token));
                    break;
                case NAME:
                    array.add(PdfName.of(token.getValue()));
                    break;
                case LITERAL_STRING:
                    array.add(new PdfString(token.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                    break;
                case HEX_STRING:
                    PdfString hs = new PdfString(token.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    hs.setForceHex(true);
                    array.add(hs);
                    break;
                case ARRAY_OPEN:
                    array.add(parseArray(lexer, depth + 1));
                    break;
                case DICT_OPEN:
                    array.add(parseDictionary(lexer, depth + 1));
                    break;
                case KEYWORD:
                    // true/false/null can appear in arrays
                    if ("true".equals(token.getValue())) {
                        array.add(org.aspose.pdf.engine.pdfobjects.PdfBoolean.TRUE);
                    } else if ("false".equals(token.getValue())) {
                        array.add(org.aspose.pdf.engine.pdfobjects.PdfBoolean.FALSE);
                    } else if ("null".equals(token.getValue())) {
                        array.add(org.aspose.pdf.engine.pdfobjects.PdfNull.INSTANCE);
                    } else {
                        LOGGER.log(Level.WARNING,
                                "Recovering unexpected keyword in content-stream array as string: {0}",
                                token.getValue());
                        array.add(new PdfString(token.getValue()
                                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                    }
                    break;
                default:
                    throw new IOException("Unexpected token in array: " + token);
            }
        }
        return array;
    }

    /**
     * Parses a dictionary from the content stream. The opening '&lt;&lt;' has already been consumed.
     */
    private static PdfDictionary parseDictionary(PDFLexer lexer, int depth) throws IOException {
        PdfDictionary dict = new PdfDictionary();
        if (depth > MAX_NESTING_DEPTH) {
            LOGGER.warning("Content-stream dictionary nesting exceeds " + MAX_NESTING_DEPTH
                    + "; skipping over-deep contents (damaged stream)");
            skipBalancedDictionary(lexer);
            return dict;
        }
        while (true) {
            PDFLexer.Token keyToken = lexer.nextToken();
            if (keyToken.getType() == PDFLexer.TokenType.EOF) {
                throw new IOException("Unexpected EOF inside dictionary in content stream");
            }
            if (keyToken.getType() == PDFLexer.TokenType.DICT_CLOSE) {
                break;
            }
            if (keyToken.getType() != PDFLexer.TokenType.NAME) {
                throw new IOException("Expected name as dictionary key, got: " + keyToken);
            }

            PdfName key = PdfName.of(keyToken.getValue());

            PDFLexer.Token valToken = lexer.nextToken();
            PDFLexer.TokenType valType = valToken.getType();

            PdfBase value;
            switch (valType) {
                case INTEGER:
                    value = parseIntegerValue(valToken, "content-stream dictionary");
                    break;
                case REAL:
                    value = parseRealValue(valToken);
                    break;
                case NAME:
                    value = PdfName.of(valToken.getValue());
                    break;
                case LITERAL_STRING:
                    value = new PdfString(valToken.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    break;
                case HEX_STRING:
                    PdfString hvs = new PdfString(valToken.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    hvs.setForceHex(true);
                    value = hvs;
                    break;
                case ARRAY_OPEN:
                    value = parseArray(lexer, depth + 1);
                    break;
                case DICT_OPEN:
                    value = parseDictionary(lexer, depth + 1);
                    break;
                case KEYWORD:
                    if ("true".equals(valToken.getValue())) {
                        value = org.aspose.pdf.engine.pdfobjects.PdfBoolean.TRUE;
                    } else if ("false".equals(valToken.getValue())) {
                        value = org.aspose.pdf.engine.pdfobjects.PdfBoolean.FALSE;
                    } else if ("null".equals(valToken.getValue())) {
                        value = org.aspose.pdf.engine.pdfobjects.PdfNull.INSTANCE;
                    } else {
                        throw new IOException("Unexpected keyword as dictionary value: " + valToken.getValue());
                    }
                    break;
                default:
                    throw new IOException("Unexpected token as dictionary value: " + valToken);
            }
            dict.set(key, value);
        }
        return dict;
    }

    /**
     * Parses an inline image (BI ... ID ... EI).
     * The "BI" keyword has already been consumed.
     * Reads key-value pairs until "ID" keyword, then reads raw image data until EI marker.
     */
    private static Operator parseInlineImage(PDFLexer lexer, RandomAccessReader reader) throws IOException {
        // Parse dictionary key-value pairs until "ID"
        PdfDictionary imageDict = new PdfDictionary();
        while (true) {
            PDFLexer.Token token = lexer.nextToken();
            if (token.getType() == PDFLexer.TokenType.EOF) {
                throw new IOException("Unexpected EOF in inline image dictionary");
            }
            if (token.getType() == PDFLexer.TokenType.KEYWORD && "ID".equals(token.getValue())) {
                break;
            }

            // Key should be a name
            PdfName key;
            if (token.getType() == PDFLexer.TokenType.NAME) {
                key = PdfName.of(token.getValue());
            } else {
                throw new IOException("Expected name in inline image dict, got: " + token);
            }

            // Value
            PDFLexer.Token valToken = lexer.nextToken();
            PdfBase value;
            switch (valToken.getType()) {
                case INTEGER:
                    value = parseIntegerValue(valToken, "inline-image dictionary");
                    break;
                case REAL:
                    value = parseRealValue(valToken);
                    break;
                case NAME:
                    value = PdfName.of(valToken.getValue());
                    break;
                case LITERAL_STRING:
                    value = new PdfString(valToken.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    break;
                case HEX_STRING:
                    PdfString hvs = new PdfString(valToken.getValue().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    hvs.setForceHex(true);
                    value = hvs;
                    break;
                case ARRAY_OPEN:
                    value = parseArray(lexer);
                    break;
                case DICT_OPEN:
                    value = parseDictionary(lexer);
                    break;
                case KEYWORD:
                    if ("true".equals(valToken.getValue())) {
                        value = org.aspose.pdf.engine.pdfobjects.PdfBoolean.TRUE;
                    } else if ("false".equals(valToken.getValue())) {
                        value = org.aspose.pdf.engine.pdfobjects.PdfBoolean.FALSE;
                    } else {
                        // Abbreviated names in inline images (e.g., "AHx" for ASCIIHexDecode)
                        value = PdfName.of(valToken.getValue());
                    }
                    break;
                default:
                    throw new IOException("Unexpected token in inline image dict value: " + valToken);
            }
            imageDict.set(key, value);
        }

        // After "ID" there is a single whitespace byte, then raw image data until "\nEI" or " EI"
        // Read one whitespace byte after ID
        reader.read();
        lexer.clearPeek();

        // Read raw data until we find EI marker preceded by whitespace
        ByteArrayOutputStream imageData = new ByteArrayOutputStream();
        int prev2 = -1;
        int prev1 = -1;

        while (true) {
            int b = reader.read();
            if (b == -1) {
                LOGGER.warning("Unexpected EOF in inline image data; recovering partial inline image");
                List<PdfBase> biOperands = new ArrayList<>();
                biOperands.add(imageDict);
                biOperands.add(new PdfString(imageData.toByteArray()));
                lexer.clearPeek();
                return new org.aspose.pdf.operators.BI(biOperands);
            }

            // Check for the 'E' + 'I' terminator. Producers usually delimit it
            // with whitespace on both sides, but ISO 32000-1 §8.9.7 does not
            // require whitespace BEFORE the keyword and real-world writers
            // (e.g. the Philips Holter reports of PDFNEWNET-39178) butt EI
            // directly against the last data byte. Accept that case too, but
            // only when the bytes following EI look like a plain operator
            // stream — compressed image data readily contains stray "EI"
            // pairs, and the ASCII lookahead rejects those false positives.
            if (prev1 == 'E' && b == 'I') {
                int next = reader.peek();
                boolean boundaryOk = next == -1 || PDFLexer.isWhitespace(next) || PDFLexer.isDelimiter(next);
                if (boundaryOk
                        && (isWhitespaceOrStart(prev2) || looksLikeOperatorStreamAhead(reader))) {
                    // Found EI marker — remove trailing whitespace + 'E' from image data
                    byte[] data = imageData.toByteArray();
                    int trimLen = data.length - 1; // remove 'E'
                    if (trimLen > 0 && isWhitespaceOrStart(data[trimLen - 1] & 0xFF)) {
                        trimLen--; // also remove trailing whitespace
                    }
                    byte[] trimmed = new byte[trimLen];
                    System.arraycopy(data, 0, trimmed, 0, trimLen);

                    lexer.clearPeek();

                    List<PdfBase> biOperands = new ArrayList<>();
                    biOperands.add(imageDict);
                    biOperands.add(new PdfString(trimmed));
                    return new org.aspose.pdf.operators.BI(biOperands);
                }
            }

            imageData.write(b);
            prev2 = prev1;
            prev1 = b;
        }
    }

    /**
     * Returns true if the byte value is PDF whitespace or -1 (start of stream).
     */
    private static boolean isWhitespaceOrStart(int b) {
        return b == -1 || PDFLexer.isWhitespace(b);
    }

    /**
     * Peeks up to 24 bytes ahead and reports whether they read like a plain
     * content-operator stream (printable ASCII and whitespace only). Used to
     * validate an {@code EI} inline-image terminator that is NOT preceded by
     * whitespace: genuine terminators are followed by operators, while a
     * false "EI" inside compressed image data is almost always followed by
     * more binary bytes. The reader position is restored before returning.
     */
    private static boolean looksLikeOperatorStreamAhead(RandomAccessReader reader) throws IOException {
        long pos = reader.getPosition();
        try {
            for (int i = 0; i < 24; i++) {
                int b = reader.read();
                if (b == -1) {
                    return true;    // EOF right after EI — valid terminator
                }
                boolean printable = b >= 0x20 && b < 0x7F;
                if (!printable && !PDFLexer.isWhitespace(b)) {
                    return false;
                }
            }
            return true;
        } finally {
            reader.seek(pos);
        }
    }

    /**
     * Parses a real-number token with a small amount of recovery for malformed content streams.
     * Content streams in broken PDFs sometimes contain standalone "." / "+." / "-." tokens
     * where a numeric operand was expected. In that case we recover as {@code 0} so the
     * surrounding operators can still be parsed and downstream extractors can salvage text.
     */
    private static PdfFloat parseRealValue(PDFLexer.Token token) {
        String textValue = token.getValue();
        if (".".equals(textValue) || "+.".equals(textValue) || "-.".equals(textValue)) {
            LOGGER.log(Level.WARNING,
                    "Recovering malformed real token \"{0}\" as 0 at content stream position {1}",
                    new Object[]{textValue, token.getPosition()});
            return new PdfFloat(0.0);
        }
        return new PdfFloat(textValue);
    }

    private static PdfBase parseIntegerValue(PDFLexer.Token token, String context) {
        String textValue = token.getValue();
        try {
            return PdfInteger.valueOf(Long.parseLong(textValue));
        } catch (NumberFormatException ex) {
            LOGGER.log(Level.WARNING,
                    "Recovering malformed integer token \"{0}\" in {1} as string at content stream position {2}",
                    new Object[]{textValue, context, token.getPosition()});
            return new PdfString(textValue.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        }
    }
}
