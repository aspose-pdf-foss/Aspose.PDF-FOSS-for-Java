package org.aspose.pdf.text;

import org.aspose.pdf.Document;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.text.TextExtractor;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

/**
 * Absorbs (extracts) all text from PDF pages.
 * <p>
 * Usage pattern (matching Aspose.PDF API):
 * <pre>
 *   TextAbsorber absorber = new TextAbsorber();
 *   page.accept(absorber);
 *   String text = absorber.getText();
 * </pre>
 * </p>
 */
public class TextAbsorber {

    private static final Logger LOG = Logger.getLogger(TextAbsorber.class.getName());

    // Precompiled patterns (Sprint 27 Part C). These were previously compiled on
    // every call — estimateAvgCharWidth() ran replaceAll inside per-fragment loops,
    // and the normalize* helpers recompiled on every text block.
    private static final java.util.regex.Pattern TRAILING_WHITESPACE =
            java.util.regex.Pattern.compile("[\\s\\n]+$");
    private static final java.util.regex.Pattern ALL_WHITESPACE =
            java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern LETTER_SPACED_WORDS =
            java.util.regex.Pattern.compile("(?<!\\S)([\\p{L}\\p{N}](?:\\s+[\\p{L}\\p{N}]){2,})(?!\\S)");
    private static final java.util.regex.Pattern UPPERCASE_CHUNKS =
            java.util.regex.Pattern.compile("(?<!\\S)([\\p{Lu}\\p{N}]{1,3}(?:\\s+[\\p{Lu}\\p{N}]{1,3}){1,})(?!\\S)");
    private static final java.util.regex.Pattern DUPLICATED_GLYPH_TOKEN =
            java.util.regex.Pattern.compile("(?<!\\S)[\\p{L}\\p{N}\\p{Punct}&&[^\\s]]{4,}(?!\\S)");

    private final StringBuilder extractedText = new StringBuilder();
    private TextExtractionOptions extractionOptions;
    private TextSearchOptions textSearchOptions;

    /**
     * Creates a TextAbsorber with default options.
     */
    public TextAbsorber() {
    }

    /**
     * Creates a TextAbsorber with the given extraction options.
     *
     * @param options the extraction options
     */
    public TextAbsorber(TextExtractionOptions options) {
        this.extractionOptions = options;
    }

    /**
     * Visits a page and extracts its text.
     *
     * @param page the PDF page to process
     * @throws IOException if text extraction fails
     */
    public void visit(Page page) throws IOException {
        List<TextFragment> fragments;
        try {
            TextExtractor extractor = new TextExtractor(
                    page.getOwningDocument() != null ? page.getOwningDocument().getParser() : null);
            fragments = extractor.extract(page);
        } catch (RuntimeException ex) {
            if (textSearchOptions != null && textSearchOptions.isIgnoreResourceFontErrors()) {
                LOG.fine(() -> "Ignoring text extraction error due to search options: " + ex.getMessage());
                return;
            }
            throw ex;
        }

        fragments = applySearchOptions(page, fragments);

        boolean rawMode = extractionOptions != null
                && extractionOptions.getFormattingMode() == TextExtractionOptions.TextFormattingMode.Raw;

        // BUG-EXT-WSPC: detect rotated (90°/270°) text. When a page is laid out
        // with rotated text the reading direction runs along Y and successive
        // lines stack along X, the opposite of ordinary horizontal text. The
        // line-grouping logic below is written for horizontal text (lines differ
        // in Y, glyphs advance in X); for rotated pages we simply remap the
        // line/advance coordinates so the same generic logic applies. Horizontal
        // pages (the overwhelming majority) take the identical original path.
        int pageRot = dominantRotation(fragments);
        boolean rotated = pageRot == 90 || pageRot == 270;

        // Pure mode: sort fragments by visual position (top→bottom, then left→right)
        // so that form-XObject appearance overlays drawn AFTER the page content
        // (e.g. AcroForm field values rendered via `Do /XiN`) merge into the
        // same visual line as the page content they cover. Raw mode keeps
        // content-stream order to preserve the original glyph sequence.
        if (!rawMode) {
            fragments = rotated ? sortByVisualPositionRotated(fragments, pageRot)
                                : sortByVisualPosition(fragments);
        }

        // Estimate average character width from fragment data
        double avgCharWidth = estimateAvgCharWidth(fragments, rotated);
        if (avgCharWidth <= 0) avgCharWidth = 5.0; // fallback

        double lastY = Double.NaN;
        double lastEndX = 0;
        String lastText = null;
        // Pre-compute which bands are RTL so neutral-only fragments inside an
        // RTL band can be reversed too (e.g. " :" → ": " when reading right
        // to left). Same 2pt band as the visual sort.
        java.util.Map<Long, Boolean> rtlBandForEmit = computeRtlBands(fragments);

        for (TextFragment fragment : fragments) {
            Position pos = fragment.getPosition();
            // For rotated pages remap coordinates so that `y` is always the
            // cross-axis used to detect line breaks and `x` is always the
            // advance position along the reading direction. The advance sign is
            // flipped for 270° so "x increases as you read" holds in both cases.
            double advSign = pageRot == 270 ? -1.0 : 1.0;
            double y = pos != null ? (rotated ? pos.getXIndent() : pos.getYIndent()) : Double.NaN;
            double x = pos != null ? (rotated ? pos.getYIndent() * advSign : pos.getXIndent()) : 0;
            // PDF stores RTL glyphs in visual (left-to-right display) order — to
            // recover logical Unicode order we reverse each Hebrew/Arabic run
            // within the fragment. Inside an RTL band, neutral-only fragments
            // (punctuation/whitespace) get reversed too so they line up
            // correctly relative to the surrounding Hebrew/Arabic text.
            boolean inRtlBand = !Double.isNaN(y)
                    && Boolean.TRUE.equals(rtlBandForEmit.get((long) Math.floor(y / 2.0)));
            String rawText = fragment.getText();
            String fragmentText = inRtlBand && !containsStrongRtl(rawText) && !containsStrongLtr(rawText)
                    ? reverseString(rawText)
                    : reverseRtlRuns(rawText);

            // BUG-EXT-SPACE fix (Sprint 24): some fonts map the inter-word space
            // glyph to U+00A0 (NBSP). Java's \s regex does not treat NBSP as
            // whitespace, so callers normalizing with \s+ get "word word" instead
            // of "word word". Aspose emits a plain space. Normalize here in the
            // extraction-output layer only — NOT in the per-fragment decode path,
            // so text-removal/replacement (which maps fragments back to content
            // operators) is unaffected.
            if (fragmentText.indexOf(' ') >= 0) {
                fragmentText = fragmentText.replace(' ', ' ');
            }

            if (extractedText.length() > 0 && !Double.isNaN(y) && !Double.isNaN(lastY)) {
                if (Math.abs(y - lastY) > 2.0) {
                    // Different line — add newline
                    extractedText.append('\n');
                    // Add indentation spaces if x > left margin (layout reconstruction;
                    // Raw mode skips this — it emits text in content-stream order only)
                    if (!rawMode && x > avgCharWidth * 2) {
                        int spaces = Math.min((int) (x / avgCharWidth), 80);
                        for (int s = 0; s < spaces; s++) {
                            extractedText.append(' ');
                        }
                    }
                } else if (x > lastEndX + avgCharWidth * getGapMultiplier(lastText, fragmentText)) {
                    // Same line but gap — add proportional spaces (layout only).
                    // Suppress the synthetic gap-space if the previous fragment
                    // text was already a space (so the gap is already represented
                    // by a real space character). This is needed for mixed-bidi
                    // lines whose RTL sub-runs were reversed by
                    // reverseConsecutiveRtlRuns: the original space fragment is
                    // preserved at the run boundary, but the visit() loop sees
                    // a jump in X from the previous fragment's right edge to the
                    // newly-first reversed fragment's left edge and would
                    // otherwise emit a second space (PDFNET_40273).
                    boolean prevIsSpace = lastText != null
                            && !lastText.isEmpty()
                            && lastText.charAt(lastText.length() - 1) == ' ';
                    if (!prevIsSpace) {
                        double gap = x - lastEndX;
                        int spaces = rawMode ? 1 : Math.min(Math.max(1, (int) (gap / avgCharWidth)), 40);
                        for (int s = 0; s < spaces; s++) {
                            extractedText.append(' ');
                        }
                    }
                }
            } else if (!rawMode && (extractedText.length() == 0 || Double.isNaN(lastY))) {
                // First fragment or first on new page — add indentation if needed.
                // Skipped in Raw mode: Aspose.PDF's Raw output contains no leading
                // position-based whitespace, only the glyphs themselves.
                if (x > avgCharWidth * 2) {
                    int spaces = Math.min((int) (x / avgCharWidth), 80);
                    for (int s = 0; s < spaces; s++) {
                        extractedText.append(' ');
                    }
                }
            }

            extractedText.append(fragmentText);

            // Estimate end X position from text length and average char width
            String text = fragmentText;
            // Use last line of text (in case fragment contains newlines)
            int lastNl = text.lastIndexOf('\n');
            String lastLine = lastNl >= 0 ? text.substring(lastNl + 1) : text;
            Rectangle fr = fragment.getRectangle();
            if (rotated) {
                // Advance runs along Y; the end of the run is the far Y edge
                // (top for 90°, bottom for 270°), normalised by advSign so it
                // stays comparable with the advance position `x` above.
                if (fr != null && fr.getHeight() > 0) {
                    lastEndX = (pageRot == 270 ? -fr.getLLY() : fr.getURY());
                } else {
                    lastEndX = x + lastLine.length() * avgCharWidth;
                }
            } else if (fr != null && fr.getURX() > fr.getLLX()) {
                lastEndX = fr.getURX();
            } else {
                lastEndX = x + lastLine.length() * avgCharWidth;
            }
            // If fragment ends with newline, reset endX
            if (text.endsWith("\n") || text.endsWith("\r")) {
                lastEndX = 0;
            }
            lastY = y;
            lastText = fragmentText;
        }

        LOG.fine(() -> "TextAbsorber visited page, total text length: " + extractedText.length());
    }

    /**
     * Visits every page in the document and extracts its text.
     *
     * @param document the document to process
     * @throws IOException if extraction fails
     */
    public void visit(Document document) throws IOException {
        if (document == null) {
            return;
        }
        document.getPages().accept(this);
    }

    /** Builds the per-band RTL flag map used by {@link #visit(Page)} to know
     *  which Y-bands need neutral-fragment reversal. Same band size (2pt) as
     *  the visual sort so the dispatch agrees. */
    private static java.util.Map<Long, Boolean> computeRtlBands(java.util.List<TextFragment> fragments) {
        java.util.Map<Long, int[]> counts = new java.util.HashMap<>();
        for (TextFragment f : fragments) {
            Position pos = f.getPosition();
            if (pos == null) continue;
            long bucket = (long) Math.floor(pos.getYIndent() / 2.0);
            int[] c = counts.computeIfAbsent(bucket, k -> new int[2]);
            String text = f.getText();
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (isStrongRtl(ch)) c[0]++;
                if (Character.isLetter(ch)) c[1]++;
            }
        }
        java.util.Map<Long, Boolean> out = new java.util.HashMap<>();
        for (java.util.Map.Entry<Long, int[]> e : counts.entrySet()) {
            int[] c = e.getValue();
            out.put(e.getKey(), c[1] > 0 && c[0] * 2 >= c[1]);
        }
        return out;
    }

    private static boolean containsStrongRtl(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) if (isStrongRtl(s.charAt(i))) return true;
        return false;
    }

    private static boolean containsStrongLtr(String s) {
        if (s == null) return false;
        // Latin/Greek/Cyrillic letters count as strong LTR — they should NOT
        // be reversed even in an RTL band (e.g. the year "2014" in a Hebrew
        // sentence; bidi-wise a Latin run keeps its own LTR order).
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) return true;
        }
        return false;
    }

    private static String reverseString(String s) {
        if (s == null || s.length() < 2) return s;
        return new StringBuilder(s).reverse().toString();
    }

    /**
     * Returns true for Hebrew, Arabic, Syriac and Thaana characters and the
     * standard Arabic presentation forms — the strong-RTL ranges per
     * Unicode Bidirectional Algorithm (UAX #9). Used by
     * {@link #reverseRtlRuns(String)} to identify runs that must be flipped
     * from PDF visual order back to logical order.
     */
    private static boolean isStrongRtl(char c) {
        return (c >= 0x0590 && c <= 0x05FF)   // Hebrew
            || (c >= 0x0600 && c <= 0x06FF)   // Arabic
            || (c >= 0x0700 && c <= 0x074F)   // Syriac
            || (c >= 0x0780 && c <= 0x07BF)   // Thaana
            || (c >= 0x07C0 && c <= 0x08FF)   // NKo, Samaritan, Mandaic, Arabic Ext-A
            || (c >= 0xFB1D && c <= 0xFB4F)   // Hebrew presentation forms
            || (c >= 0xFB50 && c <= 0xFDFF)   // Arabic presentation forms-A
            || (c >= 0xFE70 && c <= 0xFEFF);  // Arabic presentation forms-B
    }

    private static boolean isNeutralOrWeak(char c) {
        // Neutrals (whitespace + punctuation + digits) that may appear inside
        // an RTL run. Latin letters are explicitly NOT neutral — a letter
        // boundary terminates an RTL run.
        if (c == ' ' || c == '\t' || c == ' ') return true;
        if (c >= '0' && c <= '9') return true;
        if (c == '.' || c == ',' || c == ':' || c == ';' || c == '!' || c == '?'
                || c == '-' || c == '_' || c == '/' || c == '\\'
                || c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}'
                || c == '"' || c == '\''
                || c == '+' || c == '*' || c == '=') return true;
        return false;
    }

    /**
     * Reverses each Hebrew/Arabic (strong-RTL) run inside {@code text},
     * including any neutral characters that sit between RTL letters, so that
     * a fragment carrying glyphs in PDF visual order yields logical Unicode
     * order. Pure-LTR strings are returned unchanged.
     * <p>
     * Mirrors the simple reversal pass that Aspose.PDF for .NET applies after
     * fragment extraction; it is a pragmatic substitute for the full UAX #9
     * Bidirectional Algorithm and is sufficient for typical document text
     * where RTL runs do not deeply nest LTR runs (the case for PDFNEWNET-28621
     * and the other Hebrew/Arabic regression fixtures).
     * </p>
     */
    static String reverseRtlRuns(String text) {
        if (text == null || text.isEmpty()) return text;
        // Quick check — if no RTL chars at all, skip work.
        boolean anyRtl = false;
        for (int i = 0; i < text.length(); i++) {
            if (isStrongRtl(text.charAt(i))) { anyRtl = true; break; }
        }
        if (!anyRtl) return text;

        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (!isStrongRtl(c)) {
                out.append(c);
                i++;
                continue;
            }
            // Found start of an RTL run — extend through neutrals as long as
            // another RTL char follows. The run includes interior neutrals;
            // trailing neutrals (after the last RTL char) get pushed out so
            // they remain in their LTR position.
            int start = i;
            int lastRtl = i;
            int j = i + 1;
            while (j < n) {
                char cj = text.charAt(j);
                if (isStrongRtl(cj)) {
                    lastRtl = j;
                    j++;
                } else if (isNeutralOrWeak(cj)) {
                    // tentatively include, but only commit if more RTL follows
                    j++;
                } else {
                    break;
                }
            }
            int runEnd = lastRtl + 1;
            // Reverse [start, runEnd)
            for (int k = runEnd - 1; k >= start; k--) {
                out.append(text.charAt(k));
            }
            i = runEnd;
        }
        return out.toString();
    }

    /**
     * Returns fragments ordered top-to-bottom (descending Y) and left-to-right
     * (ascending X) within ~1pt-tall bands so that fragments emitted at near-
     * identical Y get grouped into one visual line. Stable: ties preserve
     * original content-stream order. Used by Pure-mode line reconstruction so
     * that form-XObject overlays rendered after the page content can merge into
     * the page line they visually cover (PDFNEWNET-31272).
     */
    /**
     * Returns the dominant baseline rotation (0/90/180/270) across the given
     * fragments, weighted by glyph count so a few stray rotated marks don't
     * flip a horizontal page. Returns 0 when there is no clear majority.
     */
    private static int dominantRotation(java.util.List<TextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) return 0;
        java.util.Map<Integer, Integer> weight = new java.util.HashMap<>();
        int total = 0;
        for (TextFragment f : fragments) {
            String t = f.getText();
            int w = t == null ? 0 : t.length();
            if (w == 0) continue;
            int r = ((f.getRotation() % 360) + 360) % 360;
            weight.merge(r, w, Integer::sum);
            total += w;
        }
        if (total == 0) return 0;
        int best = 0, bestW = 0;
        for (java.util.Map.Entry<Integer, Integer> e : weight.entrySet()) {
            if (e.getValue() > bestW) {
                bestW = e.getValue();
                best = e.getKey();
            }
        }
        // Require a real majority before switching axes (avoids destabilising
        // ordinary horizontal pages that contain a rotated caption or stamp).
        return bestW * 2 > total ? best : 0;
    }

    /**
     * Visual-position sort for rotated (90°/270°) pages: lines stack along X,
     * glyphs advance along Y. Bands the X axis into columns (one per rotated
     * line), orders columns left-to-right, and within a column orders by Y in
     * the reading direction (ascending for 90°, descending for 270°).
     */
    private static java.util.List<TextFragment> sortByVisualPositionRotated(
            java.util.List<TextFragment> fragments, int pageRot) {
        if (fragments == null || fragments.size() < 2) return fragments;
        final double BAND = 3.0;
        final int ySign = pageRot == 270 ? -1 : 1;
        java.util.List<TextFragment> copy = new java.util.ArrayList<>(fragments);
        copy.sort((a, b) -> {
            Position pa = a.getPosition();
            Position pb = b.getPosition();
            double xa = pa != null ? pa.getXIndent() : 0;
            double xb = pb != null ? pb.getXIndent() : 0;
            long ba = (long) Math.floor(xa / BAND);
            long bb = (long) Math.floor(xb / BAND);
            if (ba != bb) {
                return Long.compare(ba, bb); // columns left → right
            }
            double ya = pa != null ? pa.getYIndent() : 0;
            double yb = pb != null ? pb.getYIndent() : 0;
            return Double.compare(ya * ySign, yb * ySign);
        });
        return copy;
    }

    private static java.util.List<TextFragment> sortByVisualPosition(
            java.util.List<TextFragment> fragments) {
        if (fragments == null || fragments.size() < 2) return fragments;
        // Quantize Y into 2pt bands so that fragments at near-identical Y
        // collapse to one bucket, then within each bucket sort by X — ascending
        // for predominantly-LTR lines, descending for predominantly-RTL lines
        // so Hebrew/Arabic emit in reading order. The bucket key is integer-valued
        // so the comparator is transitive (avoids "Comparison method violates
        // its general contract" thrown by TimSort under non-transitive
        // double-tolerance comparisons).
        final double BAND = 2.0;
        // Compute per-bucket directionality so the X comparator can flip sign
        // for RTL lines. Counts strong-RTL chars vs total letters in each band.
        // A band is treated as "pure RTL" (descending-X) ONLY when the LTR
        // letters are negligible (a few stray digits or punctuation slip
        // through Character.isLetter). For genuinely mixed lines (PDFNET_40273
        // — Arabic with embedded "English word") the descending-X flip would
        // reverse the entire visual order, including the LTR run. Mixed lines
        // therefore fall back to ascending X (visual L-to-R); when the PDF
        // publisher emits in logical-position order, that already matches
        // logical reading order. Full UAX#9 visual→logical reordering would
        // need a per-line java.text.Bidi pass; the simpler threshold catches
        // the regression fixtures without breaking pure-Hebrew tests.
        java.util.Map<Long, Boolean> rtlBand = new java.util.HashMap<>();
        java.util.Map<Long, int[]> bandCounts = new java.util.HashMap<>();
        for (TextFragment f : fragments) {
            Position pos = f.getPosition();
            double y = pos != null ? pos.getYIndent() : 0;
            long bucket = (long) Math.floor(y / BAND);
            int[] cnt = bandCounts.computeIfAbsent(bucket, k -> new int[3]);
            String text = f.getText();
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (isStrongRtl(c)) cnt[0]++;
                if (Character.isLetter(c)) cnt[1]++;
                // Count strong-LTR letters (Latin/Greek/Cyrillic) explicitly so
                // we can distinguish "RTL with a few Roman-numeral page hints"
                // from "RTL with an embedded English run".
                if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) cnt[2]++;
            }
        }
        for (java.util.Map.Entry<Long, int[]> e : bandCounts.entrySet()) {
            int[] c = e.getValue();
            // "Pure RTL" = at least half the letters are RTL AND fewer than
            // 10% of letters are strong-LTR. The LTR threshold keeps lines
            // like "section 2.1 (٢٫١)" on the descending-X path (a single
            // Latin digit shouldn't disable RTL ordering) while pushing
            // anything with a real Latin word onto the ascending-X path.
            boolean rtlMajority = c[1] > 0 && c[0] * 2 >= c[1];
            boolean ltrNegligible = c[2] * 10 < c[1];
            rtlBand.put(e.getKey(), rtlMajority && ltrNegligible);
        }

        java.util.List<TextFragment> copy = new java.util.ArrayList<>(fragments);
        copy.sort((a, b) -> {
            Position pa = a.getPosition();
            Position pb = b.getPosition();
            double ya = pa != null ? pa.getYIndent() : 0;
            double yb = pb != null ? pb.getYIndent() : 0;
            long ba = (long) Math.floor(ya / BAND);
            long bb = (long) Math.floor(yb / BAND);
            if (ba != bb) {
                return Long.compare(bb, ba); // descending Y (top→bottom)
            }
            double xa = pa != null ? pa.getXIndent() : 0;
            double xb = pb != null ? pb.getXIndent() : 0;
            // RTL band → emit right-to-left so the rightmost fragment
            // (logical-first in Hebrew/Arabic) is emitted first.
            if (Boolean.TRUE.equals(rtlBand.get(ba))) {
                return Double.compare(xb, xa);
            }
            return Double.compare(xa, xb);
        });
        // Mixed-bidi lines that fell off the descending-X "pure RTL" branch
        // still contain RTL sub-runs whose fragment order needs to be reversed
        // to recover logical reading order. PDFNET_40273 is the canonical
        // case: the publisher emits "قيمة English word الفوائد الخاصة"
        // visually (LTR base with RTL embeds), where the trailing RTL pair is
        // in visual L-to-R order but logical reading needs الخاصة before
        // الفوائد. We sort ascending X here, then reverse each maximal
        // consecutive run of RTL-containing fragments in place. Neutral-only
        // fragments (spaces, punctuation) inside such a run stay attached.
        reverseConsecutiveRtlRuns(copy, rtlBand, BAND);
        return copy;
    }

    /**
     * Within each line, reverses the order of consecutive RTL-containing
     * fragments (with embedded neutral fragments). No-op for lines flagged
     * as pure RTL (already in descending X by the comparator) or for lines
     * with no RTL fragments at all.
     */
    private static void reverseConsecutiveRtlRuns(java.util.List<TextFragment> ordered,
                                                  java.util.Map<Long, Boolean> rtlBand,
                                                  double band) {
        int n = ordered.size();
        int i = 0;
        while (i < n) {
            Position pi = ordered.get(i).getPosition();
            if (pi == null) { i++; continue; }
            long bucket = (long) Math.floor(pi.getYIndent() / band);
            if (Boolean.TRUE.equals(rtlBand.get(bucket))) {
                // Pure-RTL line — comparator already reversed it; skip to next line.
                int j = i;
                while (j < n) {
                    Position pj = ordered.get(j).getPosition();
                    if (pj == null) break;
                    long bj = (long) Math.floor(pj.getYIndent() / band);
                    if (bj != bucket) break;
                    j++;
                }
                i = j;
                continue;
            }
            // Mixed-or-LTR band. Walk fragments in this band, identify
            // maximal RTL sub-runs (each strongly-RTL fragment plus any
            // intervening neutral-only fragments), and reverse each.
            int lineStart = i;
            int lineEnd = i;
            while (lineEnd < n) {
                Position pj = ordered.get(lineEnd).getPosition();
                if (pj == null) break;
                long bj = (long) Math.floor(pj.getYIndent() / band);
                if (bj != bucket) break;
                lineEnd++;
            }
            int k = lineStart;
            while (k < lineEnd) {
                if (!containsStrongRtl(ordered.get(k).getText())) {
                    k++;
                    continue;
                }
                int runStart = k;
                int runEnd = k + 1;
                int lastRtl = k;
                while (runEnd < lineEnd) {
                    String t = ordered.get(runEnd).getText();
                    if (containsStrongRtl(t)) {
                        lastRtl = runEnd;
                        runEnd++;
                    } else if (containsStrongLtr(t)) {
                        break;
                    } else {
                        // Neutral-only — keep extending; we'll trim back to lastRtl below.
                        runEnd++;
                    }
                }
                int reverseEnd = lastRtl + 1;
                if (reverseEnd - runStart > 1) {
                    java.util.Collections.reverse(ordered.subList(runStart, reverseEnd));
                }
                k = reverseEnd;
            }
            i = lineEnd;
        }
    }

    /**
     * Estimates average character width in user space units from fragment rectangles.
     * Falls back to heuristic based on page width if rectangles are not available.
     */
    private double estimateAvgCharWidth(java.util.List<TextFragment> fragments) {
        return estimateAvgCharWidth(fragments, false);
    }

    /**
     * Estimates the average glyph advance. For rotated text the advance runs
     * along Y, so the rectangle height and the Y position differences are used
     * instead of width and X.
     */
    private double estimateAvgCharWidth(java.util.List<TextFragment> fragments, boolean rotated) {
        // Method 1: from rectangles (most reliable)
        double totalWidth = 0;
        int totalChars = 0;
        for (TextFragment f : fragments) {
            Rectangle r = f.getRectangle();
            double adv = r == null ? 0 : (rotated ? r.getHeight() : r.getWidth());
            if (r != null && adv > 1) {
                String text = TRAILING_WHITESPACE.matcher(f.getText()).replaceAll("");
                if (text.length() > 5) {
                    totalWidth += adv;
                    totalChars += text.length();
                }
            }
        }
        if (totalChars > 10) {
            double w = totalWidth / totalChars;
            if (w >= 2 && w <= 30) return w;
        }

        // Method 2: estimate from position differences between consecutive fragments on same line
        double prevX = Double.NaN;
        int prevLen = 0;
        double sumCharW = 0;
        int countCharW = 0;
        double prevY = Double.NaN;
        for (TextFragment f : fragments) {
            Position p = f.getPosition();
            if (p == null) continue;
            // x = advance axis, y = cross axis (swapped for rotated text)
            double x = rotated ? p.getYIndent() : p.getXIndent();
            double y = rotated ? p.getXIndent() : p.getYIndent();
            if (!Double.isNaN(prevY) && Math.abs(y - prevY) < 1 && prevLen > 3 && x > prevX) {
                double charW = (x - prevX) / prevLen;
                if (charW >= 2 && charW <= 20) {
                    sumCharW += charW;
                    countCharW++;
                }
            }
            String text = TRAILING_WHITESPACE.matcher(f.getText()).replaceAll("");
            prevX = x;
            prevLen = text.length();
            prevY = y;
        }
        if (countCharW > 0) {
            return sumCharW / countCharW;
        }

        // Fallback: typical char width for 10pt font
        return 5.0;
    }

    private double getGapMultiplier(String lastText, String currentText) {
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
     * Returns the extracted text.
     *
     * @return the accumulated text from all visited pages
     */
    public String getText() {
        return normalizeDuplicatedGlyphWords(normalizeLetterSpacedWords(extractedText.toString()));
    }

    /**
     * Returns the extraction options.
     *
     * @return the options, or null
     */
    public TextExtractionOptions getExtractionOptions() {
        if (extractionOptions == null) {
            extractionOptions = new TextExtractionOptions(TextExtractionOptions.TextFormattingMode.Pure);
        }
        return extractionOptions;
    }

    /**
     * Sets the extraction options.
     *
     * @param options the options
     */
    public void setExtractionOptions(TextExtractionOptions options) {
        this.extractionOptions = options;
    }

    /**
     * Returns text search options associated with this absorber.
     *
     * @return the text search options, or {@code null}
     */
    public TextSearchOptions getTextSearchOptions() {
        if (textSearchOptions == null) {
            textSearchOptions = new TextSearchOptions();
        }
        return textSearchOptions;
    }

    /**
     * Sets text search options associated with this absorber.
     *
     * @param textSearchOptions the text search options
     */
    public void setTextSearchOptions(TextSearchOptions textSearchOptions) {
        this.textSearchOptions = textSearchOptions;
    }

    /**
     * Resets the accumulated text.
     */
    public void reset() {
        extractedText.setLength(0);
    }

    private List<TextFragment> applySearchOptions(Page page, List<TextFragment> fragments) {
        if (textSearchOptions == null || fragments == null || fragments.isEmpty()) {
            return fragments;
        }
        Rectangle clip = null;
        if (textSearchOptions.isLimitToPageBounds()) {
            clip = page.getRect();
        }
        if (textSearchOptions.getRectangle() != null) {
            clip = intersect(clip, textSearchOptions.getRectangle());
        }

        Rectangle[] excludes = textSearchOptions.getExcludeRectangles();
        if (clip == null && (excludes == null || excludes.length == 0)) {
            return fragments;
        }

        java.util.ArrayList<TextFragment> filtered = new java.util.ArrayList<>(fragments.size());
        for (TextFragment fragment : fragments) {
            Rectangle rect = fragment.getRectangle();
            if (clip != null && rect != null && !rect.isIntersect(clip)) {
                continue;
            }
            if (rect != null && excludes != null) {
                boolean excluded = false;
                for (Rectangle exclude : excludes) {
                    if (exclude != null && rect.isIntersect(exclude)) {
                        excluded = true;
                        break;
                    }
                }
                if (excluded) {
                    continue;
                }
            }
            filtered.add(fragment);
        }
        return filtered;
    }

    private Rectangle intersect(Rectangle first, Rectangle second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        double llx = Math.max(first.getLLX(), second.getLLX());
        double lly = Math.max(first.getLLY(), second.getLLY());
        double urx = Math.min(first.getURX(), second.getURX());
        double ury = Math.min(first.getURY(), second.getURY());
        if (urx <= llx || ury <= lly) {
            return null;
        }
        return new Rectangle(llx, lly, urx, ury);
    }

    private String normalizeLetterSpacedWords(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        java.util.regex.Matcher matcher = LETTER_SPACED_WORDS.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(maybeCollapseLetterSpacedRun(matcher.group(1))));
        }
        matcher.appendTail(sb);
        String normalized = sb.toString();
        java.util.regex.Matcher uppercaseMatcher = UPPERCASE_CHUNKS.matcher(normalized);
        StringBuffer uppercaseSb = new StringBuffer();
        while (uppercaseMatcher.find()) {
            uppercaseMatcher.appendReplacement(uppercaseSb,
                    java.util.regex.Matcher.quoteReplacement(
                            maybeCollapseLetterSpacedRun(uppercaseMatcher.group(1))));
        }
        uppercaseMatcher.appendTail(uppercaseSb);
        return uppercaseSb.toString();
    }

    /**
     * Collapses inter-token whitespace in a "letter-spaced" run, unless the run
     * contains a multi-space separator. Multiple consecutive spaces between two
     * letter-spaced tokens (e.g. {@code "S E L L  A L L O C A T I O N"}) are a
     * deliberate signal from the PDF author that the source text is intentionally
     * rendered letter-spaced — collapsing it would lose the visible word break.
     * In that case the run is returned unchanged. PDFNEWNET-33376 is the
     * canonical test for this behavior.
     */
    private static String maybeCollapseLetterSpacedRun(String run) {
        if (run.contains("  ")) {
            return run;
        }
        return ALL_WHITESPACE.matcher(run).replaceAll("");
    }

    private String normalizeDuplicatedGlyphWords(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        java.util.regex.Matcher matcher = DUPLICATED_GLYPH_TOKEN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group();
            matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(collapseDuplicatedGlyphToken(token)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String collapseDuplicatedGlyphToken(String token) {
        if (token == null || token.length() < 4) {
            return token;
        }
        boolean allSame = true;
        char first = token.charAt(0);
        boolean allDigits = Character.isDigit(first);
        for (int i = 1; i < token.length(); i++) {
            char ch = token.charAt(i);
            if (ch != first) {
                allSame = false;
            }
            if (!Character.isDigit(ch)) {
                allDigits = false;
            }
            if (!allSame && !allDigits) {
                break;
            }
        }
        // Repeated single-character tokens like "444444" are valid extracted text
        // in some legacy PDFs (PDFNEWNET_7590) and must not be collapsed.
        if (allSame) {
            return token;
        }
        // Numeric-only tokens may legitimately contain long repeated runs
        // (for example page-order regression PDFs like 444444333333...). The
        // duplicated-glyph heuristic is meant for doubled letters, not for
        // compact numeric sequences.
        if (allDigits) {
            return token;
        }
        StringBuilder collapsed = new StringBuilder(token.length());
        int pairCount = 0;
        int singleCount = 0;
        for (int i = 0; i < token.length(); i++) {
            char current = token.charAt(i);
            if (i + 1 < token.length() && token.charAt(i + 1) == current) {
                collapsed.append(current);
                pairCount++;
                i++;
            } else {
                collapsed.append(current);
                singleCount++;
            }
        }
        if (pairCount >= 2 && pairCount > singleCount * 2) {
            return collapsed.toString();
        }
        return token;
    }
}
