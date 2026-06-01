package org.aspose.pdf.tests.engine.parser;
import org.aspose.pdf.engine.parser.*;

import org.aspose.pdf.engine.io.RandomAccessReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PDFLexer} — PDF tokenizer.
 */
public class PDFLexerTest {

    private PDFLexer lexerFor(String input) {
        return new PDFLexer(RandomAccessReader.fromBytes(input.getBytes(StandardCharsets.ISO_8859_1)));
    }

    // === Test 1: Integers ===

    @Test
    public void testIntegerPositive() throws IOException {
        PDFLexer lexer = lexerFor("42");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("42", token.getValue());
    }

    @Test
    public void testIntegerNegative() throws IOException {
        PDFLexer lexer = lexerFor("-17");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("-17", token.getValue());
    }

    @Test
    public void testIntegerZero() throws IOException {
        PDFLexer lexer = lexerFor("0");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("0", token.getValue());
    }

    @Test
    public void testIntegerPositiveSign() throws IOException {
        PDFLexer lexer = lexerFor("+5");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("+5", token.getValue());
    }

    // === Test 2: Reals ===

    @Test
    public void testRealStandard() throws IOException {
        PDFLexer lexer = lexerFor("3.14");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.REAL, token.getType());
        assertEquals("3.14", token.getValue());
    }

    @Test
    public void testRealLeadingDot() throws IOException {
        PDFLexer lexer = lexerFor(".5");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.REAL, token.getType());
        assertEquals(".5", token.getValue());
    }

    @Test
    public void testRealNegative() throws IOException {
        PDFLexer lexer = lexerFor("-0.001");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.REAL, token.getType());
        assertEquals("-0.001", token.getValue());
    }

    @Test
    public void testRealTrailingDot() throws IOException {
        PDFLexer lexer = lexerFor("1.");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.REAL, token.getType());
        assertEquals("1.", token.getValue());
    }

    // === Test 3: Names ===

    @Test
    public void testNameSimple() throws IOException {
        PDFLexer lexer = lexerFor("/Type");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, token.getType());
        assertEquals("Type", token.getValue());
    }

    @Test
    public void testNameWithHexEscape() throws IOException {
        PDFLexer lexer = lexerFor("/Name#20Test");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, token.getType());
        assertEquals("Name Test", token.getValue());
    }

    @Test
    public void testNameWithHashEscape() throws IOException {
        PDFLexer lexer = lexerFor("/#23Hash");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, token.getType());
        assertEquals("#Hash", token.getValue());
    }

    @Test
    public void testNameEmpty() throws IOException {
        // "/" alone is a valid empty name
        PDFLexer lexer = lexerFor("/ ");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, token.getType());
        assertEquals("", token.getValue());
    }

    // === Test 4: Literal strings ===

    @Test
    public void testLiteralStringSimple() throws IOException {
        PDFLexer lexer = lexerFor("(Hello)");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("Hello", token.getValue());
    }

    @Test
    public void testLiteralStringBalancedParens() throws IOException {
        PDFLexer lexer = lexerFor("(balanced (parens))");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("balanced (parens)", token.getValue());
    }

    @Test
    public void testLiteralStringEscapes() throws IOException {
        PDFLexer lexer = lexerFor("(escape \\n \\( \\))");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("escape \n ( )", token.getValue());
    }

    @Test
    public void testLiteralStringBackslash() throws IOException {
        PDFLexer lexer = lexerFor("(back\\\\slash)");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("back\\slash", token.getValue());
    }

    // === Test 5: Octal escapes ===

    @Test
    public void testLiteralStringOctal() throws IOException {
        // \101 = octal 101 = decimal 65 = 'A'
        PDFLexer lexer = lexerFor("(\\101)");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("A", token.getValue());
    }

    @Test
    public void testLiteralStringOctalShort() throws IOException {
        // \0 = NUL
        PDFLexer lexer = lexerFor("(\\0)");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("\0", token.getValue());
    }

    // === Test 6: Hex strings ===

    @Test
    public void testHexString() throws IOException {
        PDFLexer lexer = lexerFor("<48656C6C6F>");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.HEX_STRING, token.getType());
        assertEquals("Hello", token.getValue());
    }

    @Test
    public void testHexStringWithSpaces() throws IOException {
        PDFLexer lexer = lexerFor("<48 65 6C>");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.HEX_STRING, token.getType());
        assertEquals("Hel", token.getValue());
    }

    @Test
    public void testHexStringOddDigits() throws IOException {
        // <486> → 48 60 → "H`" (odd digit padded with 0)
        PDFLexer lexer = lexerFor("<486>");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.HEX_STRING, token.getType());
        assertEquals(2, token.getValue().length());
        assertEquals(0x48, token.getValue().charAt(0));
        assertEquals(0x60, token.getValue().charAt(1));
    }

    // === Test 7: Dict open/close ===

    @Test
    public void testDictOpen() throws IOException {
        PDFLexer lexer = lexerFor("<<");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.DICT_OPEN, token.getType());
    }

    @Test
    public void testDictClose() throws IOException {
        PDFLexer lexer = lexerFor(">>");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.DICT_CLOSE, token.getType());
    }

    // === Test 8: Array open/close ===

    @Test
    public void testArrayOpen() throws IOException {
        PDFLexer lexer = lexerFor("[");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.ARRAY_OPEN, token.getType());
    }

    @Test
    public void testArrayClose() throws IOException {
        PDFLexer lexer = lexerFor("]");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.ARRAY_CLOSE, token.getType());
    }

    // === Test 9: Keywords ===

    @Test
    public void testKeywordTrue() throws IOException {
        PDFLexer lexer = lexerFor("true");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("true", token.getValue());
    }

    @Test
    public void testKeywordFalse() throws IOException {
        PDFLexer lexer = lexerFor("false");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("false", token.getValue());
    }

    @Test
    public void testKeywordNull() throws IOException {
        PDFLexer lexer = lexerFor("null");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("null", token.getValue());
    }

    @Test
    public void testKeywordObj() throws IOException {
        PDFLexer lexer = lexerFor("obj");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("obj", token.getValue());
    }

    @Test
    public void testKeywordEndobj() throws IOException {
        PDFLexer lexer = lexerFor("endobj");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("endobj", token.getValue());
    }

    @Test
    public void testKeywordStream() throws IOException {
        PDFLexer lexer = lexerFor("stream");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("stream", token.getValue());
    }

    @Test
    public void testKeywordR() throws IOException {
        PDFLexer lexer = lexerFor("R");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, token.getType());
        assertEquals("R", token.getValue());
    }

    // === Test 10: Comments ===

    @Test
    public void testComment() throws IOException {
        PDFLexer lexer = lexerFor("% this is a comment\n42");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("42", token.getValue());
    }

    @Test
    public void testMultipleComments() throws IOException {
        PDFLexer lexer = lexerFor("% comment 1\n% comment 2\n42");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, token.getType());
        assertEquals("42", token.getValue());
    }

    // === Test 11: Whitespace ===

    @Test
    public void testAllWhitespaceTypes() throws IOException {
        // NUL, TAB, LF, FF, CR, SPACE between tokens
        PDFLexer lexer = lexerFor("42\u0000\t\n\f\r 43");
        PDFLexer.Token t1 = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, t1.getType());
        assertEquals("42", t1.getValue());

        PDFLexer.Token t2 = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, t2.getType());
        assertEquals("43", t2.getValue());
    }

    // === Test 12: Token sequence ===

    @Test
    public void testObjectSequence() throws IOException {
        PDFLexer lexer = lexerFor("1 0 obj << /Type /Page >> endobj");

        PDFLexer.Token t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, t.getType());
        assertEquals("1", t.getValue());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, t.getType());
        assertEquals("0", t.getValue());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, t.getType());
        assertEquals("obj", t.getValue());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.DICT_OPEN, t.getType());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, t.getType());
        assertEquals("Type", t.getValue());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.NAME, t.getType());
        assertEquals("Page", t.getValue());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.DICT_CLOSE, t.getType());

        t = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, t.getType());
        assertEquals("endobj", t.getValue());
    }

    // === Test 13: EOF ===

    @Test
    public void testEOF() throws IOException {
        PDFLexer lexer = lexerFor("");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.EOF, token.getType());
    }

    @Test
    public void testEOFAfterToken() throws IOException {
        PDFLexer lexer = lexerFor("42");
        lexer.nextToken(); // consume 42
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.EOF, token.getType());
    }

    // === Test 14: Position tracking ===

    @Test
    public void testTokenPosition() throws IOException {
        PDFLexer lexer = lexerFor("42 /Type (Hello)");

        PDFLexer.Token t1 = lexer.nextToken();
        assertEquals(0, t1.getPosition());

        PDFLexer.Token t2 = lexer.nextToken();
        assertEquals(3, t2.getPosition()); // '/' at position 3

        PDFLexer.Token t3 = lexer.nextToken();
        assertEquals(9, t3.getPosition()); // '(' at position 9
    }

    // === Test 15: peekToken ===

    @Test
    public void testPeekDoesNotConsume() throws IOException {
        PDFLexer lexer = lexerFor("42 43");

        PDFLexer.Token peeked = lexer.peekToken();
        assertEquals(PDFLexer.TokenType.INTEGER, peeked.getType());
        assertEquals("42", peeked.getValue());

        // Peek again — same token
        PDFLexer.Token peeked2 = lexer.peekToken();
        assertSame(peeked, peeked2);

        // Now consume
        PDFLexer.Token consumed = lexer.nextToken();
        assertSame(peeked, consumed);

        // Next token should be 43
        PDFLexer.Token next = lexer.nextToken();
        assertEquals("43", next.getValue());
    }

    // === Additional edge cases ===

    @Test
    public void testNameStopsAtDelimiter() throws IOException {
        PDFLexer lexer = lexerFor("/Type/Subtype");
        PDFLexer.Token t1 = lexer.nextToken();
        assertEquals("Type", t1.getValue());

        PDFLexer.Token t2 = lexer.nextToken();
        assertEquals("Subtype", t2.getValue());
    }

    @Test
    public void testDictWithContent() throws IOException {
        PDFLexer lexer = lexerFor("<< /Length 42 >>");
        assertEquals(PDFLexer.TokenType.DICT_OPEN, lexer.nextToken().getType());
        assertEquals("Length", lexer.nextToken().getValue());
        assertEquals("42", lexer.nextToken().getValue());
        assertEquals(PDFLexer.TokenType.DICT_CLOSE, lexer.nextToken().getType());
    }

    @Test
    public void testLineContinuationInString() throws IOException {
        // Backslash + newline = line continuation (both chars ignored)
        PDFLexer lexer = lexerFor("(line\\\ncontinued)");
        PDFLexer.Token token = lexer.nextToken();
        assertEquals(PDFLexer.TokenType.LITERAL_STRING, token.getType());
        assertEquals("linecontinued", token.getValue());
    }

    @Test
    public void testConstructorRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new PDFLexer(null));
    }

    // === BUG-LEX-001 (Sprint 22): signed numbers vs lone sign/dot ===

    @Test
    public void testPositiveSignedInteger() throws IOException {
        PDFLexer.Token t = lexerFor("+123 ").nextToken();
        assertEquals(PDFLexer.TokenType.INTEGER, t.getType());
        assertEquals("+123", t.getValue());
    }

    @Test
    public void testSignedRealWithLeadingDot() throws IOException {
        PDFLexer.Token t = lexerFor("-.5 ").nextToken();
        assertEquals(PDFLexer.TokenType.REAL, t.getType());
        assertEquals("-.5", t.getValue());
    }

    @Test
    public void testPositiveRealWithLeadingDot() throws IOException {
        PDFLexer.Token t = lexerFor("+.25 ").nextToken();
        assertEquals(PDFLexer.TokenType.REAL, t.getType());
        assertEquals("+.25", t.getValue());
    }

    @Test
    public void testLeadingDotReal() throws IOException {
        PDFLexer.Token t = lexerFor(".5 ").nextToken();
        assertEquals(PDFLexer.TokenType.REAL, t.getType());
        assertEquals(".5", t.getValue());
    }

    @Test
    public void testStandalonePlusIsKeywordNotInteger() throws IOException {
        // A lone '+' followed by whitespace must NOT become a bogus INTEGER
        // token "+" (which previously triggered the malformed-integer warning).
        PDFLexer.Token t = lexerFor("+ ").nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, t.getType());
        assertEquals("+", t.getValue());
    }

    @Test
    public void testStandaloneMinusIsKeywordNotInteger() throws IOException {
        PDFLexer.Token t = lexerFor("- ").nextToken();
        assertEquals(PDFLexer.TokenType.KEYWORD, t.getType());
        assertEquals("-", t.getValue());
    }

    @Test
    public void testStandaloneDotStaysDegenerateReal() throws IOException {
        // A lone '.' is intentionally left as a (degenerate) REAL token so
        // ContentStreamParser can recover it as 0 — its behaviour is unchanged
        // by the BUG-LEX-001 sign fix, which only affects '+' / '-'.
        PDFLexer.Token t = lexerFor(". ").nextToken();
        assertEquals(PDFLexer.TokenType.REAL, t.getType());
        assertEquals(".", t.getValue());
    }
}
