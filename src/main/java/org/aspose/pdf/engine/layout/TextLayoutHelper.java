package org.aspose.pdf.engine.layout;

import org.aspose.pdf.engine.font.StandardFonts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Provides text measurement and word wrapping using PDF standard font metrics.
 * <p>
 * Text widths are computed from the 256-entry width tables in
 * {@link StandardFonts}. Each width value is in units of 1/1000 of text space,
 * so the actual width in user-space units is {@code widths[charCode] / 1000.0 * fontSize}.
 * </p>
 * <p>
 * Word wrapping handles explicit newlines ({@code \n}), word boundaries (spaces),
 * and long-word breaking when a single word exceeds the available line width.
 * </p>
 */
public final class TextLayoutHelper {

    private static final Logger LOG = Logger.getLogger(TextLayoutHelper.class.getName());

    /**
     * Default line height multiplier (leading factor) applied to font size.
     */
    private static final double LINE_HEIGHT_FACTOR = 1.2;

    private TextLayoutHelper() {
        // Utility class
    }

    /**
     * Measures the width of the given text string in user-space units.
     *
     * @param text     the text to measure
     * @param fontName the standard font name (e.g. "Helvetica")
     * @param fontSize the font size in points
     * @return the text width in user-space units (points)
     */
    public static double measureTextWidth(String text, String fontName, double fontSize) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int[] widths = getWidthsForFont(fontName);
        double totalWidth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            int code = charToWinAnsi(ch);
            if (code >= 0 && code < 256) {
                totalWidth += widths[code] / 1000.0 * fontSize;
            } else if (isFullWidth(ch)) {
                // CJK ideographs/kana/fullwidth forms advance one em — they
                // render through an embedded Identity-H font; measuring them
                // as zero glued whole Chinese lines into one unwrappable
                // "word" and overlapped columns.
                totalWidth += fontSize;
            } else {
                // Other non-WinAnsi scripts (Cyrillic, Greek, …): a rough
                // proportional average keeps wrapping sane.
                totalWidth += 0.55 * fontSize;
            }
        }
        return totalWidth;
    }

    /** True for codepoints that typeset at a full em advance (CJK ideographs,
     *  kana, Hangul, fullwidth/vertical forms, CJK punctuation). */
    private static boolean isFullWidth(char ch) {
        return (ch >= 0x1100 && ch <= 0x115F)     // Hangul Jamo
                || (ch >= 0x2E80 && ch <= 0xA4CF)  // CJK radicals … Yi
                || (ch >= 0xAC00 && ch <= 0xD7A3)  // Hangul syllables
                || (ch >= 0xF900 && ch <= 0xFAFF)  // CJK compat ideographs
                || (ch >= 0xFE30 && ch <= 0xFE4F)  // CJK compat forms
                || (ch >= 0xFF00 && ch <= 0xFF60); // fullwidth forms
    }

    /**
     * Wraps text into lines that fit within the specified maximum width.
     * <p>
     * Handles explicit newlines ({@code \n}), word boundaries (spaces),
     * and breaking of words that are too long for a single line.
     * </p>
     *
     * @param text     the text to wrap
     * @param fontName the standard font name
     * @param fontSize the font size in points
     * @param maxWidth the maximum line width in user-space units
     * @return a list of lines, each fitting within maxWidth (or as close as possible)
     */
    public static List<String> wrapText(String text, String fontName, double fontSize, double maxWidth) {
        if (text == null || text.isEmpty()) {
            return Collections.singletonList("");
        }
        if (maxWidth <= 0) {
            LOG.warning("maxWidth is non-positive (" + maxWidth + "); returning text as single line");
            return Collections.singletonList(text);
        }

        List<String> result = new ArrayList<>();

        // Split on explicit newlines first
        String[] paragraphs = text.split("\n", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) {
                result.add("");
                continue;
            }
            wrapParagraph(paragraph, fontName, fontSize, maxWidth, result);
        }
        return result;
    }

    /**
     * Returns the line height for the given font and size.
     * <p>
     * Computed as {@code fontSize * 1.2}, which is a standard default leading
     * for body text.
     * </p>
     *
     * @param fontName the standard font name
     * @param fontSize the font size in points
     * @return the line height in user-space units
     */
    public static double getLineHeight(String fontName, double fontSize) {
        return fontSize * LINE_HEIGHT_FACTOR;
    }

    /**
     * Wraps a single paragraph (no embedded newlines) into lines.
     */
    private static void wrapParagraph(String paragraph, String fontName, double fontSize,
                                       double maxWidth, List<String> result) {
        String[] words = paragraph.split(" ", -1);
        StringBuilder currentLine = new StringBuilder();
        double currentWidth = 0;
        double spaceWidth = measureTextWidth(" ", fontName, fontSize);

        // Preserve a leading space on the first line of this paragraph —
        // split(" ", -1) emits "" as the first token when the paragraph
        // starts with a space, which our word loop would otherwise drop.
        // Without this, "\n 2192 Number…" would render as "2192 Number…",
        // losing the lead indent the caller intended.
        if (paragraph.length() > 0 && paragraph.charAt(0) == ' ') {
            currentLine.append(' ');
            currentWidth = spaceWidth;
        }

        for (String word : words) {
            double wordWidth = measureTextWidth(word, fontName, fontSize);

            if (currentLine.length() == 0) {
                // First word on line
                if (wordWidth <= maxWidth) {
                    currentLine.append(word);
                    currentWidth = wordWidth;
                } else {
                    // Word is too long, break it character by character
                    breakLongWord(word, fontName, fontSize, maxWidth, result);
                    // After breaking, currentLine remains empty for next word
                }
            } else {
                // Subsequent word: check if adding space + word fits
                double newWidth = currentWidth + spaceWidth + wordWidth;
                if (newWidth <= maxWidth) {
                    currentLine.append(' ').append(word);
                    currentWidth = newWidth;
                } else {
                    // Flush current line, start new one
                    result.add(currentLine.toString());
                    currentLine.setLength(0);

                    if (wordWidth <= maxWidth) {
                        currentLine.append(word);
                        currentWidth = wordWidth;
                    } else {
                        breakLongWord(word, fontName, fontSize, maxWidth, result);
                        currentWidth = 0;
                    }
                }
            }
        }

        // Flush remaining text
        if (currentLine.length() > 0) {
            result.add(currentLine.toString());
        }
    }

    /**
     * Breaks a single long word that exceeds maxWidth into multiple lines.
     */
    private static void breakLongWord(String word, String fontName, double fontSize,
                                       double maxWidth, List<String> result) {
        int[] widths = getWidthsForFont(fontName);
        StringBuilder currentPart = new StringBuilder();
        double currentWidth = 0;

        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            int code = charToWinAnsi(ch);
            double charWidth = 0;
            if (code >= 0 && code < 256) {
                charWidth = widths[code] / 1000.0 * fontSize;
            }

            if (currentWidth + charWidth > maxWidth && currentPart.length() > 0) {
                result.add(currentPart.toString());
                currentPart.setLength(0);
                currentWidth = 0;
            }
            currentPart.append(ch);
            currentWidth += charWidth;
        }

        if (currentPart.length() > 0) {
            // Don't add to result here; let the caller handle remaining text
            // Actually, since this is a complete word break, add it
            result.add(currentPart.toString());
        }
    }

    /**
     * Returns the width table for the given font. Falls back to Helvetica if unknown.
     */
    private static int[] getWidthsForFont(String fontName) {
        if (fontName != null) {
            int[] w = StandardFonts.getWidths(fontName);
            if (w != null) {
                return w;
            }
        }
        // Default to Helvetica
        int[] w = StandardFonts.getWidths("Helvetica");
        if (w != null) {
            return w;
        }
        // Last resort: all zeros (should not happen)
        LOG.warning("No width table found, returning zero widths");
        return new int[256];
    }

    /**
     * Converts a Java char to its WinAnsiEncoding code.
     * For ASCII (0-127) and Latin-1 supplement (160-255), the mapping is identity.
     * For 128-159, checks the Windows-1252 special characters.
     *
     * @param ch the character
     * @return the WinAnsi code (0-255), or -1 if not representable
     */
    private static int charToWinAnsi(char ch) {
        return ContentStreamBuilder.unicodeToWinAnsi(ch);
    }
}
