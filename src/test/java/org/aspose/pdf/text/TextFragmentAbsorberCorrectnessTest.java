package org.aspose.pdf.text;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 28 Part A/B — correctness of {@link TextFragmentAbsorber} search and the
 * extract→replace→save→reload pipeline. Written "tests first" to triage which
 * behaviours are actually broken before changing the matching engine.
 */
public class TextFragmentAbsorberCorrectnessTest {

    /** Builds a one-page PDF whose page carries the given lines as TextFragments. */
    private static byte[] buildPdf(String... lines) throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextBuilder builder = new TextBuilder(page);
        double y = 700;
        for (String line : lines) {
            TextFragment tf = new TextFragment(line);
            tf.getTextState().setFontName("Helvetica");
            tf.getTextState().setFontSize(12);
            tf.setPosition(new Position(72, y));
            builder.appendText(tf);
            y -= 20;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    /** Builds a one-page PDF with two fragments on the SAME baseline (adjacent words). */
    private static byte[] buildSameLine(String left, String right, double leftX, double rightX) throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        TextBuilder builder = new TextBuilder(page);
        TextFragment a = new TextFragment(left);
        a.getTextState().setFontName("Helvetica");
        a.getTextState().setFontSize(12);
        a.setPosition(new Position(leftX, 700));
        builder.appendText(a);
        TextFragment b = new TextFragment(right);
        b.getTextState().setFontName("Helvetica");
        b.getTextState().setFontSize(12);
        b.setPosition(new Position(rightX, 700));
        builder.appendText(b);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        doc.save(baos);
        doc.close();
        return baos.toByteArray();
    }

    private static int find(byte[] pdf, String phrase, TextSearchOptions opts) throws IOException {
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            TextFragmentAbsorber tfa = opts == null
                    ? new TextFragmentAbsorber(phrase)
                    : new TextFragmentAbsorber(phrase, opts);
            doc.getPages().get(1).accept(tfa);
            return tfa.getTextFragments().size();
        }
    }

    // ── Part A: search correctness ──

    @Test
    void exactMatch_singleFragment_findsOnce() throws Exception {
        assertEquals(1, find(buildPdf("Hello World"), "Hello", null));
    }

    @Test
    void exactMatch_multipleOccurrences_findsAll() throws Exception {
        assertEquals(3, find(buildPdf("foo bar foo baz foo"), "foo", null));
    }

    @Test
    void exactMatch_phraseNotPresent_findsNone() throws Exception {
        assertEquals(0, find(buildPdf("Hello World"), "Goodbye", null));
    }

    @Test
    void regexMatch_thisOrThat_findsBoth() throws Exception {
        assertEquals(4, find(buildPdf("this is that and this is that"), "this|that",
                new TextSearchOptions(true)));
    }

    @Test
    void caseInsensitive_findsBothCases() throws Exception {
        TextSearchOptions opts = new TextSearchOptions(false);
        opts.setCaseSensitive(false);
        assertEquals(4, find(buildPdf("Hello hello HELLO HeLLo"), "hello", opts));
    }

    @Test
    void emptySearchPhrase_returnsAllFragments() throws Exception {
        try (Document doc = new Document(new ByteArrayInputStream(buildPdf("Hello World")))) {
            TextFragmentAbsorber tfa = new TextFragmentAbsorber();
            doc.getPages().get(1).accept(tfa);
            assertTrue(tfa.getTextFragments().size() >= 1);
        }
    }

    @Test
    void exactMatch_spanningTwoFragmentsWithSpace_findsAcrossBoundary() throws Exception {
        // "Hello" and "World" as two separate Tj fragments with a visual gap;
        // the extractor should synthesise a space so "Hello World" matches.
        byte[] pdf = buildSameLine("Hello", "World", 72, 110);
        assertEquals(1, find(pdf, "Hello World", null));
    }

    @Test
    void exactMatch_spanningTwoAdjacentFragments_findsConcatenated() throws Exception {
        // Two abutting fragments with (almost) no gap → "HelloWorld".
        byte[] pdf = buildSameLine("Hello", "World", 72, 100);
        // Accept either concatenated or space-joined extraction.
        int joined = find(pdf, "HelloWorld", null);
        int spaced = find(pdf, "Hello World", null);
        assertEquals(1, joined + spaced, "phrase should match exactly once across the two fragments");
    }

    // ── Part B: replace persistence ──

    @Test
    void replace_singleFragment_persistsAfterSave() throws Exception {
        byte[] pdf = buildPdf("Hello World");
        byte[] replaced;
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            TextFragmentAbsorber tfa = new TextFragmentAbsorber("Hello");
            doc.getPages().get(1).accept(tfa);
            assertEquals(1, tfa.getTextFragments().size());
            tfa.getTextFragments().get(1).setText("Goodbye");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            replaced = baos.toByteArray();
        }
        assertEquals(1, find(replaced, "Goodbye", null), "Replacement should persist after save");
        assertEquals(0, find(replaced, "Hello", null), "Old text should be gone");
    }

    @Test
    void replace_multipleOccurrences_replacesAll() throws Exception {
        byte[] pdf = buildPdf("foo bar foo baz foo");
        byte[] replaced;
        try (Document doc = new Document(new ByteArrayInputStream(pdf))) {
            TextFragmentAbsorber tfa = new TextFragmentAbsorber("foo");
            doc.getPages().get(1).accept(tfa);
            int n = tfa.getTextFragments().size();
            for (int i = 1; i <= n; i++) {
                tfa.getTextFragments().get(i).setText("FOO");
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            replaced = baos.toByteArray();
        }
        assertEquals(0, find(replaced, "foo", null), "all 'foo' should be replaced");
        assertEquals(3, find(replaced, "FOO", null), "three 'FOO' expected");
    }
}
