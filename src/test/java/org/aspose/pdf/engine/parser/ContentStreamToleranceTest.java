package org.aspose.pdf.engine.parser;

import org.aspose.pdf.Operator;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 63 — content-stream parser tolerance.
 *
 * <p>A content stream with an out-of-int-range operand or a malformed inline
 * dictionary must not abort the whole page; the parser returns the operators it
 * could read (best-effort, mirroring Acrobat / pdf.js).</p>
 */
public class ContentStreamToleranceTest {

    private static List<Operator> parse(String content) {
        return assertDoesNotThrow(() ->
                ContentStreamParser.parse(content.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    public void outOfRangeOperandDoesNotThrow() {
        // 3301174862 > 2^31: previously threw ArithmeticException in Operator.getNumber.
        List<Operator> ops = parse("100 100 m 3301174862 0 l S\n");
        assertFalse(ops.isEmpty(), "operators before/at the large-coordinate op should be returned");
    }

    @Test
    public void malformedDictKeyRecoversPartialContent() {
        // The "<< 0 ..." dict has an integer where a name key is required. The
        // text-showing operators before it must still be recovered.
        List<Operator> ops = parse("BT (hello) Tj ET\n<< 0 /Bad >> q\n");
        assertFalse(ops.isEmpty(), "operators before the malformed dict should be recovered");
        boolean sawTextShow = ops.stream().anyMatch(o ->
                o instanceof org.aspose.pdf.operators.TextShowOperator);
        assertTrue(sawTextShow, "the Tj before the malformed dict should have been parsed");
    }

    @Test
    public void malformedDoubleDotNumberAbortsRemainderOfStream() {
        // "669.835.566" is the signature of a damaged Flate region (corpus
        // 46075.pdf). Acrobat stops executing the stream there and keeps what
        // was painted; the tail must NOT be executed.
        List<Operator> ops = parse("1 0 0 RG 0 0 m 669.835.566 503 l S 1 1 re f");
        assertTrue(ops.size() >= 2, "prefix ops kept, got " + ops);
        assertFalse(ops.stream().anyMatch(o -> "f".equals(o.getName())),
                "ops after the malformed token must be dropped, got " + ops);
    }

    @Test
    public void malformedDigitlessNumberAbortsRemainderOfStream() {
        // A lone "." (no digits) is equally a corruption signature.
        List<Operator> ops = parse("q 0 0 m .c.0 962 l S Q");
        assertFalse(ops.stream().anyMatch(o -> "Q".equals(o.getName())),
                "ops after the digitless token must be dropped, got " + ops);
    }

    @Test
    public void malformedNumberInsideArrayAbortsRemainderOfStream() {
        List<Operator> ops = parse("BT [ (a) 481.1849148.57 (b) ] TJ ET 1 1 re f");
        assertFalse(ops.stream().anyMatch(o -> "f".equals(o.getName())),
                "ops after a malformed array element must be dropped, got " + ops);
    }

    @Test
    public void trailingDotAndLeadingDotNumbersStayValid() {
        // "4." and ".002" are valid reals per ISO 32000 §7.3.3 — no abort.
        List<Operator> ops = parse("4. .002 m 1 1 l S");
        assertTrue(ops.stream().anyMatch(o -> "S".equals(o.getName())),
                "valid dot-numbers must not trigger the damaged-stream abort, got " + ops);
    }
}
