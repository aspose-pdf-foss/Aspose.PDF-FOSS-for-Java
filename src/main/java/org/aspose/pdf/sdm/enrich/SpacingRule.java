package org.aspose.pdf.sdm.enrich;

/**
 * The extractor's inter-fragment spacing decision, replicated for HTML output
 * (IR Stage 3). {@code TextAbsorber} glues two same-line fragments when the
 * horizontal gap is below {@code avgCharWidth × multiplier} (0.5 normally,
 * 1.8 between two single letters/digits — kerned lettering), and separates
 * different baselines (|Δy| &gt; 2pt) with a line break. The structural HTML
 * writer must reproduce the same binary decision or the [TXT] oracle sees
 * glued words ({@code TotalValue}) or split ones ({@code Reade r}).
 */
final class SpacingRule {

    private SpacingRule() {
    }

    /**
     * Returns true when the extractor would emit whitespace between two
     * consecutive runs of one visual paragraph.
     *
     * @param prevText     text of the left run
     * @param prevEndX     right edge of the left run's box
     * @param prevBaseline left run baseline
     * @param curText      text of the right run
     * @param curX         left edge of the right run's box
     * @param curBaseline  right run baseline
     * @param avgCharWidth average character width estimate for the paragraph
     * @return true to insert a space
     */
    static boolean shouldSpace(String prevText, double prevEndX, double prevBaseline,
                               String curText, double curX, double curBaseline,
                               double avgCharWidth) {
        if (Math.abs(curBaseline - prevBaseline) > 2.0) {
            return true; // different line — extractor emits a newline
        }
        if (prevText != null && !prevText.isEmpty()
                && prevText.charAt(prevText.length() - 1) == ' ') {
            return false; // the gap is already represented by a real space char
        }
        double mult = gapMultiplier(prevText, curText);
        return curX > prevEndX + avgCharWidth * mult;
    }

    /** Mirrors TextAbsorber.getGapMultiplier: 1.8 between two single letters/digits. */
    static double gapMultiplier(String lastText, String currentText) {
        if (lastText == null || currentText == null) {
            return 0.5;
        }
        String left = lastText.trim();
        String right = currentText.trim();
        if (left.length() == 1 && right.length() == 1
                && Character.isLetterOrDigit(left.charAt(0))
                && Character.isLetterOrDigit(right.charAt(0))) {
            return 1.8;
        }
        return 0.5;
    }

    /**
     * Average character width from run boxes: total box width over total
     * character count, with a sane floor.
     *
     * @param widths per-run box widths
     * @param texts  per-run texts
     * @return the estimate (&ge; 1pt)
     */
    static double avgCharWidth(double[] widths, String[] texts) {
        double w = 0;
        int chars = 0;
        for (int i = 0; i < widths.length; i++) {
            w += widths[i];
            chars += texts[i] == null ? 0 : texts[i].length();
        }
        if (chars == 0 || w <= 0) {
            return 5.0;
        }
        return Math.max(1.0, w / chars);
    }

    /**
     * PAGE-level average char width, mirroring TextAbsorber.estimateAvgCharWidth
     * Method 1: only fragments wider than 1pt with more than 5 chars (trailing
     * whitespace stripped) count; the result is accepted in [2,30] with &gt;10
     * chars of evidence, else the extractor's 5.0 fallback. The page scope
     * matters: a small-font phone column is glued or spaced against the BODY
     * text's char width, not its own.
     *
     * @param pgm the PGM geometry
     * @return per-page-index estimates
     */
    static double[] pageAvgCharWidth(org.aspose.pdf.pgm.PgmModel pgm) {
        int pages = pgm.getPages().size();
        double[] out = new double[pages];
        for (int p = 0; p < pages; p++) {
            double totalWidth = 0;
            int totalChars = 0;
            for (org.aspose.pdf.pgm.PgmBox box : pgm.getPage(p).getBoxes()) {
                if (!(box.getData() instanceof org.aspose.pdf.pgm.TextBoxData)) {
                    continue;
                }
                double adv = box.getRect().getW();
                String text = ((org.aspose.pdf.pgm.TextBoxData) box.getData()).getText();
                if (adv > 1 && text != null) {
                    String trimmed = text.replaceAll("[\\s\\n]+$", "");
                    if (trimmed.length() > 5) {
                        totalWidth += adv;
                        totalChars += trimmed.length();
                    }
                }
            }
            double w = totalChars > 10 ? totalWidth / totalChars : 0;
            out[p] = (w >= 2 && w <= 30) ? w : 5.0;
        }
        return out;
    }
}
