package org.aspose.pdf.tests.generation;

import org.aspose.pdf.Color;
import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug M — {@link TextBuilder#formatNumber(double)} (private, but reached via
 * every {@code appendText} / position / color setter) must always emit
 * numbers with {@code '.'} as the decimal separator, regardless of the JVM's
 * default locale. PDF syntax recognises only {@code '.'} per ISO 32000-1:2008
 * §7.3.3; a locale like {@code ru/de/fr} emits commas and viewers report
 * {@code Unknown operator ',2000'} and refuse to render any content.
 *
 * <p>Each test saves/restores {@link Locale#getDefault()} in {@code @AfterEach}
 * so cross-test contamination is impossible.</p>
 */
class TextBuilderLocaleTest {

    private static final Pattern COMMA_DECIMAL = Pattern.compile("\\b\\d+,\\d+\\b");
    private static final Pattern DOT_DECIMAL   = Pattern.compile("\\b\\d+\\.\\d+\\b");

    @TempDir Path tempDir;
    private Locale savedLocale;

    @BeforeEach
    void saveLocale() { savedLocale = Locale.getDefault(); }

    @AfterEach
    void restoreLocale() { Locale.setDefault(savedLocale); }

    /**
     * Builds a 1-page document with a {@link TextFragment} placed at
     * {@code (87.2, 496.5)} with foreground colour {@code (0.1, 0.2, 0.3)}
     * — all values that go through {@code formatNumber} as fractional doubles.
     * Returns the saved file's bytes interpreted as ISO-8859-1 so PDF binary
     * survives untouched.
     */
    private String buildAndReadBytes(String label) throws IOException {
        Path out = tempDir.resolve(label + ".pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("locale-test");
            tf.setPosition(new Position(87.2, 496.5));
            tf.getTextState().setForegroundColor(Color.fromRgb(0.1, 0.2, 0.3));
            new TextBuilder(page).appendText(tf);
            doc.save(out.toString());
        }
        return new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
    }

    private void assertNoCommaDecimals(String content, String context) {
        assertFalse(COMMA_DECIMAL.matcher(content).find(),
                "Output (" + context + ") contains comma-decimal numbers, e.g. '0,1000'. "
                        + "TextBuilder.formatNumber is locale-sensitive again.");
        assertTrue(DOT_DECIMAL.matcher(content).find(),
                "Expected at least one dot-decimal number in the output (" + context + ").");
    }

    @Test
    @DisplayName("Under Russian locale, saved PDF contains no comma-decimal numbers")
    void formatNumber_underRussianLocale_writesDotDecimal() throws IOException {
        Locale.setDefault(new Locale("ru", "RU"));
        assertNoCommaDecimals(buildAndReadBytes("ru"), "ru_RU");
    }

    @Test
    @DisplayName("Under German locale, saved PDF contains no comma-decimal numbers")
    void formatNumber_underGermanLocale_writesDotDecimal() throws IOException {
        Locale.setDefault(Locale.GERMANY);
        assertNoCommaDecimals(buildAndReadBytes("de"), "de_DE");
    }

    @Test
    @DisplayName("Under French locale, saved PDF contains no comma-decimal numbers")
    void formatNumber_underFrenchLocale_writesDotDecimal() throws IOException {
        Locale.setDefault(Locale.FRANCE);
        assertNoCommaDecimals(buildAndReadBytes("fr"), "fr_FR");
    }

    @Test
    @DisplayName("Under US locale, saved PDF also contains only dot-decimals (baseline)")
    void formatNumber_underUSLocale_unchanged() throws IOException {
        Locale.setDefault(Locale.US);
        // Sanity baseline: a fix that incorrectly switched to commas would also
        // fail here.
        assertNoCommaDecimals(buildAndReadBytes("us"), "en_US");
    }

    @Test
    @DisplayName("Integer-valued coordinates are written without a decimal point")
    void formatNumber_integerValues_writeAsInt() throws IOException {
        Locale.setDefault(new Locale("ru", "RU")); // hostile locale
        Path out = tempDir.resolve("int.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("integer-coords");
            tf.setPosition(new Position(100, 200));  // both integral
            new TextBuilder(page).appendText(tf);
            doc.save(out.toString());
        }
        String content = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        // The Tm matrix at integral coords must be emitted without decimals.
        assertTrue(content.contains("1 0 0 1 100 200 Tm"),
                "Expected 'Tm' with integer coords (no decimal point) — got file body without that token.");
        // Re-assert the no-comma invariant on this file too.
        assertFalse(COMMA_DECIMAL.matcher(content).find(),
                "Integer-coord file unexpectedly contains comma-decimal tokens.");
    }

    @Test
    @DisplayName("Small fractional coordinates retain enough precision (4 decimal places)")
    void formatNumber_smallFraction_writesWithEnoughPrecision() throws IOException {
        Locale.setDefault(new Locale("ru", "RU"));
        Path out = tempDir.resolve("small.pdf");
        try (Document doc = new Document()) {
            Page page = doc.getPages().add();
            TextFragment tf = new TextFragment("precision-test");
            tf.setPosition(new Position(0.0001, 0.0002));
            new TextBuilder(page).appendText(tf);
            doc.save(out.toString());
        }
        String content = new String(Files.readAllBytes(out), StandardCharsets.ISO_8859_1);
        // The %.4f formatter prints 0.0001 / 0.0002 — both retained because
        // trailing-zero strip only removes trailing zeros, not significant ones.
        assertTrue(content.contains(".0001"),
                "Expected the X-coord '0.0001' to survive the formatter without truncation.");
        assertTrue(content.contains(".0002"),
                "Expected the Y-coord '0.0002' to survive the formatter without truncation.");
        // And no commas, of course.
        assertFalse(COMMA_DECIMAL.matcher(content).find(),
                "Small-fraction file unexpectedly contains comma-decimal tokens.");
    }
}
