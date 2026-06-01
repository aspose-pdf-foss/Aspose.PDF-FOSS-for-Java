package org.aspose.pdf.engine.parser;

import org.aspose.pdf.engine.io.RandomAccessReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PDF tokenizer: converts a byte stream into a sequence of PDF tokens.
 * Implements a finite state machine that recognizes all PDF token types
 * as defined in ISO 32000-1:2008, §7.2 and §7.3.
 *
 * <p>Token types include numbers, names, strings (literal and hex),
 * keywords, array/dictionary delimiters, and EOF.</p>
 */
public final class PDFLexer {

    private static final Logger LOGGER = Logger.getLogger(PDFLexer.class.getName());

    private final RandomAccessReader reader;
    private Token peekedToken;

    /**
     * Enumeration of all PDF token types.
     */
    public enum TokenType {
        /** Integer number, e.g. {@code 42}, {@code -17} */
        INTEGER,
        /** Real number, e.g. {@code 3.14}, {@code .5} */
        REAL,
        /** Name object, e.g. {@code /Type}, {@code /Name#20Test} */
        NAME,
        /** Literal string, e.g. {@code (Hello)} */
        LITERAL_STRING,
        /** Hexadecimal string, e.g. {@code <48656C6C6F>} */
        HEX_STRING,
        /** Keyword: true, false, null, obj, endobj, stream, endstream, xref, trailer, startxref, R, n, f */
        KEYWORD,
        /** Array open delimiter {@code [} */
        ARRAY_OPEN,
        /** Array close delimiter {@code ]} */
        ARRAY_CLOSE,
        /** Dictionary open delimiter {@code <<} */
        DICT_OPEN,
        /** Dictionary close delimiter {@code >>} */
        DICT_CLOSE,
        /** End of file */
        EOF
    }

    /**
     * A single PDF token with its type, string value, and file position.
     */
    public static final class Token {
        private final TokenType type;
        private final String value;
        private final long position;

        /**
         * Creates a new token.
         *
         * @param type     the token type
         * @param value    the textual value of the token
         * @param position the byte position in the source where this token starts
         */
        public Token(TokenType type, String value, long position) {
            this.type = type;
            this.value = value;
            this.position = position;
        }

        /** Returns the token type. */
        public TokenType getType() {
            return type;
        }

        /** Returns the textual value of the token. */
        public String getValue() {
            return value;
        }

        /** Returns the byte position in the source where this token starts. */
        public long getPosition() {
            return position;
        }

        @Override
        public String toString() {
            return "Token{" + type + ", \"" + value + "\", pos=" + position + "}";
        }
    }

    /**
     * Constructs a new PDFLexer reading from the given source.
     *
     * @param reader the random-access source to read PDF bytes from
     */
    public PDFLexer(RandomAccessReader reader) {
        if (reader == null) {
            throw new IllegalArgumentException("reader must not be null");
        }
        this.reader = reader;
    }

    /**
     * Reads and returns the next token from the input, consuming it.
     *
     * @return the next token
     * @throws IOException if an I/O error occurs
     */
    public Token nextToken() throws IOException {
        if (peekedToken != null) {
            Token t = peekedToken;
            peekedToken = null;
            return t;
        }
        return readToken();
    }

    /**
     * Returns the next token without consuming it.
     * Subsequent calls to {@code peekToken()} return the same token
     * until {@code nextToken()} is called.
     *
     * @return the next token
     * @throws IOException if an I/O error occurs
     */
    public Token peekToken() throws IOException {
        if (peekedToken == null) {
            peekedToken = readToken();
        }
        return peekedToken;
    }

    /**
     * Returns the current byte position in the underlying reader.
     *
     * @return the current position
     */
    public long getPosition() {
        return reader.getPosition();
    }

    /**
     * Clears any peeked (buffered) token.
     * Used when the reader position is changed externally (e.g., seek)
     * to ensure the lexer re-reads from the new position.
     */
    public void clearPeek() {
        peekedToken = null;
    }

    /**
     * Skips whitespace characters and comments.
     * PDF whitespace: 0x00 (NUL), 0x09 (TAB), 0x0A (LF), 0x0C (FF), 0x0D (CR), 0x20 (SPACE).
     * Comments start with '%' and extend to the end of the line.
     *
     * @throws IOException if an I/O error occurs
     */
    public void skipWhitespaceAndComments() throws IOException {
        while (true) {
            int c = reader.peek();
            if (c == -1) {
                return;
            }
            if (isWhitespace(c)) {
                reader.read();
                continue;
            }
            if (c == '%') {
                // Skip comment until end of line
                reader.read(); // consume '%'
                while (true) {
                    int ch = reader.read();
                    if (ch == -1 || ch == '\n' || ch == '\r') {
                        break;
                    }
                }
                continue;
            }
            break;
        }
    }

    /**
     * Internal method to read one token from the source.
     */
    private Token readToken() throws IOException {
        skipWhitespaceAndComments();

        long pos = reader.getPosition();
        int c = reader.read();

        if (c == -1) {
            LOGGER.log(Level.FINER, "EOF at position {0}", pos);
            return new Token(TokenType.EOF, "", pos);
        }

        switch (c) {
            case '[':
                return new Token(TokenType.ARRAY_OPEN, "[", pos);
            case ']':
                return new Token(TokenType.ARRAY_CLOSE, "]", pos);
            case '(':
                return readLiteralString(pos);
            case '<': {
                int next = reader.peek();
                if (next == '<') {
                    reader.read(); // consume second '<'
                    return new Token(TokenType.DICT_OPEN, "<<", pos);
                }
                return readHexString(pos);
            }
            case '>': {
                int next = reader.peek();
                if (next == '>') {
                    reader.read(); // consume second '>'
                    return new Token(TokenType.DICT_CLOSE, ">>", pos);
                }
                throw new IOException("Unexpected '>' at position " + pos + " (expected '>>' for dict close)");
            }
            case '/':
                return readName(pos);
            default:
                // A digit or a leading '.' always begins a number. A lone '.'
                // ("." with no following digit) stays a (degenerate) REAL token
                // that ContentStreamParser intentionally recovers as 0 — see
                // ContentStreamParserTest.parseMalformedStandaloneRealAsZeroForRecovery.
                if ((c >= '0' && c <= '9') || c == '.') {
                    return readNumber(c, pos);
                }
                // BUG-LEX-001 (Sprint 22): a sign only begins a number when a
                // digit (or a leading '.') follows. A lone '+' / '-' previously
                // fell into readNumber and produced a bogus INTEGER token (value
                // "+" or "-"), which ContentStreamParser could not parse —
                // emitting 155 "Recovering malformed integer token" warnings
                // across the corpus. There is no sensible numeric value for a
                // bare sign, so treat it as a keyword/operator token instead.
                if (c == '+' || c == '-') {
                    int next = reader.peek();
                    if ((next >= '0' && next <= '9') || next == '.') {
                        return readNumber(c, pos);
                    }
                    return readKeyword(c, pos);
                }
                // Regular character → keyword
                return readKeyword(c, pos);
        }
    }

    /**
     * Reads a literal string token (§7.3.4.2).
     * Handles balanced parentheses, escape sequences, and octal escapes.
     */
    private Token readLiteralString(long pos) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int parenDepth = 1;

        while (true) {
            int c = reader.read();
            if (c == -1) {
                LOGGER.warning("Unterminated literal string starting at position " + pos
                        + "; returning recovered partial value");
                break;
            }
            if (c == '(') {
                parenDepth++;
                baos.write(c);
            } else if (c == ')') {
                parenDepth--;
                if (parenDepth == 0) {
                    break;
                }
                baos.write(c);
            } else if (c == '\\') {
                int escaped = reader.read();
                if (escaped == -1) {
                    LOGGER.warning("Unterminated escape in literal string at position " + pos
                            + "; returning recovered partial value");
                    break;
                }
                switch (escaped) {
                    case 'n':
                        baos.write('\n');
                        break;
                    case 'r':
                        baos.write('\r');
                        break;
                    case 't':
                        baos.write('\t');
                        break;
                    case 'b':
                        baos.write('\b');
                        break;
                    case 'f':
                        baos.write('\f');
                        break;
                    case '\\':
                        baos.write('\\');
                        break;
                    case '(':
                        baos.write('(');
                        break;
                    case ')':
                        baos.write(')');
                        break;
                    case '\r':
                        // Line continuation: \<CR> or \<CR><LF>
                        int next = reader.peek();
                        if (next == '\n') {
                            reader.read();
                        }
                        break;
                    case '\n':
                        // Line continuation: \<LF>
                        break;
                    default:
                        // Octal escape: 1-3 octal digits
                        if (escaped >= '0' && escaped <= '7') {
                            int octal = escaped - '0';
                            int peek = reader.peek();
                            if (peek >= '0' && peek <= '7') {
                                reader.read();
                                octal = octal * 8 + (peek - '0');
                                peek = reader.peek();
                                if (peek >= '0' && peek <= '7') {
                                    reader.read();
                                    octal = octal * 8 + (peek - '0');
                                }
                            }
                            baos.write(octal & 0xFF);
                        } else {
                            // Unknown escape — the backslash is ignored per spec
                            baos.write(escaped);
                        }
                        break;
                }
            } else {
                baos.write(c);
            }
        }

        // Use ISO-8859-1 to preserve raw bytes 1:1 as chars
        String value = new String(baos.toByteArray(), StandardCharsets.ISO_8859_1);
        LOGGER.log(Level.FINER, "Literal string at {0}: length={1}", new Object[]{pos, value.length()});
        return new Token(TokenType.LITERAL_STRING, value, pos);
    }

    /**
     * Reads a hexadecimal string token (§7.3.4.3).
     * Hex digits are read until '&gt;', whitespace is ignored.
     * If odd number of hex digits, a trailing 0 is appended.
     */
    private Token readHexString(long pos) throws IOException {
        StringBuilder hexChars = new StringBuilder();

        while (true) {
            int c = reader.read();
            if (c == -1) {
                // Be lenient: malformed PDFs sometimes truncate a hex string at
                // EOF. Per §7.3.4.3 an odd-trailing-nibble pads with 0, so the
                // safest recovery is to treat EOF as if a closing '>' were found.
                LOGGER.warning("Unterminated hex string at position " + pos
                        + "; treating EOF as closing '>'");
                break;
            }
            if (c == '>') {
                break;
            }
            if (isWhitespace(c)) {
                continue;
            }
            if (isHexDigit(c)) {
                hexChars.append((char) c);
            } else {
                // Be lenient: skip non-hex characters (common in corrupted PDFs)
                LOGGER.fine("Skipping non-hex character 0x" + Integer.toHexString(c)
                        + " in hex string at position " + reader.getPosition());
            }
        }

        // Odd number of hex digits → pad with trailing 0
        if (hexChars.length() % 2 != 0) {
            hexChars.append('0');
        }

        // Decode hex to bytes, then to ISO-8859-1 string to preserve raw bytes
        byte[] decoded = new byte[hexChars.length() / 2];
        for (int i = 0; i < decoded.length; i++) {
            int high = Character.digit(hexChars.charAt(i * 2), 16);
            int low = Character.digit(hexChars.charAt(i * 2 + 1), 16);
            decoded[i] = (byte) ((high << 4) | low);
        }

        String value = new String(decoded, StandardCharsets.ISO_8859_1);
        LOGGER.log(Level.FINER, "Hex string at {0}: {1} hex chars", new Object[]{pos, hexChars.length()});
        return new Token(TokenType.HEX_STRING, value, pos);
    }

    /**
     * Reads a name token (§7.3.5).
     * The leading '/' has already been consumed. Decodes {@code #XX} hex escapes.
     */
    private Token readName(long pos) throws IOException {
        StringBuilder sb = new StringBuilder();

        while (true) {
            int c = reader.peek();
            if (c == -1 || isWhitespace(c) || isDelimiter(c)) {
                break;
            }
            reader.read();
            if (c == '#') {
                // Hex-encoded byte: #XX
                int h1 = reader.read();
                int h2 = reader.read();
                if (h1 == -1 || h2 == -1 || !isHexDigit(h1) || !isHexDigit(h2)) {
                    throw new IOException("Invalid hex escape in name at position " + pos);
                }
                int decoded = (Character.digit(h1, 16) << 4) | Character.digit(h2, 16);
                sb.append((char) decoded);
            } else {
                sb.append((char) c);
            }
        }

        LOGGER.log(Level.FINER, "Name at {0}: /{1}", new Object[]{pos, sb.toString()});
        return new Token(TokenType.NAME, sb.toString(), pos);
    }

    /**
     * Reads a number token (integer or real).
     * The first character has already been read.
     */
    private Token readNumber(int firstChar, long pos) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append((char) firstChar);
        boolean hasDecimalPoint = (firstChar == '.');

        while (true) {
            int c = reader.peek();
            if (c >= '0' && c <= '9') {
                reader.read();
                sb.append((char) c);
            } else if (c == '.' && !hasDecimalPoint) {
                reader.read();
                sb.append('.');
                hasDecimalPoint = true;
            } else {
                break;
            }
        }

        TokenType type = hasDecimalPoint ? TokenType.REAL : TokenType.INTEGER;
        LOGGER.log(Level.FINER, "{0} at {1}: {2}", new Object[]{type, pos, sb.toString()});
        return new Token(type, sb.toString(), pos);
    }

    /**
     * Reads a keyword token (true, false, null, obj, endobj, stream, endstream,
     * xref, trailer, startxref, R, n, f).
     * The first character has already been read.
     */
    private Token readKeyword(int firstChar, long pos) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append((char) firstChar);

        while (true) {
            int c = reader.peek();
            if (c == -1 || isWhitespace(c) || isDelimiter(c)) {
                break;
            }
            // PDF operator names contain only letters and a handful of suffix
            // chars (* ' "); they never contain digits, '+' or '-'. Some content
            // streams pack operators back-to-back with their following number
            // operand ("nq0.000000" → n q 0.000000), so stopping the keyword at
            // the first digit lets the lexer recover the boundary instead of
            // swallowing the number into the operator name (PDFNEWNET-33721).
            if (c == '+' || c == '-' || c == '.' || (c >= '0' && c <= '9')) {
                break;
            }
            reader.read();
            sb.append((char) c);
        }

        String kw = sb.toString();
        LOGGER.log(Level.FINER, "Keyword at {0}: {1}", new Object[]{pos, kw});
        return new Token(TokenType.KEYWORD, kw, pos);
    }

    /**
     * Tests whether a byte is PDF whitespace (§7.2.2, Table 1).
     *
     * @param c the byte value to test
     * @return true if the byte is PDF whitespace
     */
    static boolean isWhitespace(int c) {
        return c == 0x00 || c == 0x09 || c == 0x0A || c == 0x0C || c == 0x0D || c == 0x20;
    }

    /**
     * Tests whether a byte is a PDF delimiter (§7.2.2, Table 2).
     *
     * @param c the byte value to test
     * @return true if the byte is a PDF delimiter
     */
    static boolean isDelimiter(int c) {
        return c == '(' || c == ')' || c == '<' || c == '>' || c == '['
                || c == ']' || c == '{' || c == '}' || c == '/' || c == '%';
    }

    /**
     * Tests whether a character is a valid hexadecimal digit.
     *
     * @param c the character to test
     * @return true if 0-9, a-f, or A-F
     */
    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
