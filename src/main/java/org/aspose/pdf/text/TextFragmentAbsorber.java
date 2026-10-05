package org.aspose.pdf.text;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.engine.text.TextExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Absorbs text fragments matching a search phrase or regex from PDF pages.
 * <p>
 * Extends {@link TextAbsorber} with text search capabilities. Provides
 * results as a {@link TextFragmentCollection}.
 * </p>
 * <p>
 * Usage pattern (matching Aspose.PDF API):
 * <pre>
 *   TextFragmentAbsorber absorber = new TextFragmentAbsorber("search text");
 *   page.accept(absorber);
 *   TextFragmentCollection results = absorber.getTextFragments();
 * </pre>
 * </p>
 */
public class TextFragmentAbsorber extends TextAbsorber {

    private static final Logger LOG = Logger.getLogger(TextFragmentAbsorber.class.getName());

    private String searchPhrase;
    /**
     * /Rotate of the page currently being searched (0 when not searching or
     * when combining multiple pages). buildSpans uses it to pick the reading
     * axis on sideways pages — see visit(Page).
     */
    private int spanPageRotation;
    private final TextFragmentCollection textFragments = new TextFragmentCollection();
    private TextSearchOptions textSearchOptions;
    // Aspose convention: TextFragmentAbsorber exposes a non-null default
    // so callers can write `absorber.getTextReplaceOptions().setReplaceAdjustmentAction(...)`
    // without a null check. PdfContentEditor keeps the null default.
    private TextReplaceOptions textReplaceOptions = new TextReplaceOptions(TextReplaceOptions.Scope.REPLACE_ALL);
    // Aspose convention: TextEditOptions is non-null by default so callers can write
    // `absorber.getTextEditOptions().setFontReplaceBehavior(...)` without a null check.
    private TextEditOptions textEditOptions = new TextEditOptions();

    /**
     * Creates a TextFragmentAbsorber that collects all text fragments (no filter).
     */
    public TextFragmentAbsorber() {
        this.searchPhrase = null;
    }

    /**
     * Creates a TextFragmentAbsorber that searches for the given phrase.
     *
     * @param phrase the search phrase (exact match or regex, depending on options)
     */
    public TextFragmentAbsorber(String phrase) {
        this.searchPhrase = phrase;
    }

    /**
     * Creates a TextFragmentAbsorber with search phrase and options.
     *
     * @param phrase  the search phrase
     * @param options the search options (regex mode, area filter, etc.)
     */
    public TextFragmentAbsorber(String phrase, TextSearchOptions options) {
        this.searchPhrase = phrase;
        this.textSearchOptions = options;
    }

    /**
     * Creates a TextFragmentAbsorber that collects all text fragments using the
     * given edit options (e.g. {@link TextEditOptions.FontReplace#RemoveUnusedFonts}).
     *
     * @param editOptions the text edit options
     */
    public TextFragmentAbsorber(TextEditOptions editOptions) {
        this.searchPhrase = null;
        if (editOptions != null) {
            this.textEditOptions = editOptions;
        }
    }

    /**
     * Sets the search phrase for this absorber.
     * Clears previous results.
     *
     * @param phrase the new search phrase
     */
    public void setPhrase(String phrase) {
        this.searchPhrase = phrase;
        this.textFragments.clear();
    }

    /**
     * Visits a page and collects matching text fragments.
     *
     * @param page the PDF page to process
     * @throws IOException if text extraction fails
     */
    @Override
    public void visit(Page page) throws IOException {
        super.visit(page);

        // Sprint 34 Bug A: pass document.getParser() so indirect references
        // inside the page (font dictionaries, XObject streams) can be resolved.
        // visitDocumentCombined (L465) already does this; the per-page path
        // was an oversight that left TFA unable to dereference indirect objects.
        TextExtractor extractor = new TextExtractor(
                page.getOwningDocument() != null ? page.getOwningDocument().getParser() : null);
        List<TextFragment> allFragments = extractor.extract(page);

        if (searchPhrase == null || searchPhrase.isEmpty()) {
            // Empty-phrase TFA extracts all visible text. Sprint 41 (PDFNEWNET_27157_3_1):
            // the TextSearchOptions area filter (and exclude/page-bounds filters) must
            // still be honoured here — previously this path bypassed shouldIncludeMatch
            // entirely, returning every fragment regardless of the rectangle filter.
            Rectangle areaFilter = textSearchOptions != null ? textSearchOptions.getRectangle() : null;
            List<TextFragment> visibleFragments = normalizeVisibleFragments(allFragments);
            for (TextFragment frag : visibleFragments) {
                frag.setPage(page);
                if (shouldIncludeMatch(frag, page, areaFilter)) {
                    textFragments.add(frag);
                }
            }
        } else {
            boolean isRegex = textSearchOptions != null && textSearchOptions.isRegularExpressionUsed();
            Rectangle areaFilter = textSearchOptions != null ? textSearchOptions.getRectangle() : null;

            // PDFNEWNET_30639: on a /Rotate 90|270 page the glyph runs advance
            // along the unrotated Y axis, so buildSpans' same-line heuristic
            // (which keys off Y) would split one visual line into many and a
            // multi-run phrase could never match. Tell buildSpans which axis
            // is the reading direction for this page.
            spanPageRotation = ((page.getRotate() % 360) + 360) % 360;
            try {
                if (isRegex) {
                    searchRegex(allFragments, page, areaFilter);
                } else {
                    searchExact(allFragments, page, areaFilter);
                }
            } finally {
                spanPageRotation = 0;
            }

            if (textSearchOptions != null && textSearchOptions.isSearchInAnnotations()) {
                searchAnnotations(page, areaFilter, isRegex);
            }
        }

        LOG.fine(() -> "TextFragmentAbsorber visited page, matched: " + textFragments.size());
    }

    /**
     * Visits all pages of a document and collects matching text fragments.
     *
     * @param document the PDF document to process
     * @throws IOException if text extraction fails
     */
    public void visit(Document document) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (searchPhrase != null && !searchPhrase.isEmpty()) {
            visitDocumentCombined(document);
            return;
        }
        for (int i = 1; i <= document.getPages().size(); i++) {
            visit(document.getPages().get(i));
        }
    }

    /**
     * Removes all text visible to this absorber from the document by clearing
     * the source text-showing operators behind every extracted fragment.
     *
     * @param document the document to modify
     * @throws IOException if text extraction fails
     */
    public void removeAllText(Document document) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        TextFragmentAbsorber absorber = new TextFragmentAbsorber();
        absorber.setTextSearchOptions(this.textSearchOptions);
        absorber.visit(document);
        for (TextFragment fragment : absorber.getTextFragments()) {
            fragment.setText("");
        }
    }

    /**
     * Removes all text on a single page that matches this absorber's
     * {@link TextSearchOptions} (rectangle filter, etc) by clearing the
     * underlying text-showing operators. Mirrors Aspose's
     * {@code RemoveAllText(Page)} overload used by PDFNET-45497.
     *
     * @param page the page to modify
     * @throws IOException if text extraction fails
     */
    public void removeAllText(org.aspose.pdf.Page page) throws IOException {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        TextFragmentAbsorber absorber = new TextFragmentAbsorber();
        absorber.setTextSearchOptions(this.textSearchOptions);
        absorber.visit(page);
        for (TextFragment fragment : absorber.getTextFragments()) {
            fragment.setText("");
        }
    }

    /**
     * Returns the collection of matched text fragments.
     *
     * @return the text fragment collection
     */
    public TextFragmentCollection getTextFragments() {
        // Propagate the edit options (e.g. FontReplace.RemoveUnusedFonts) to each
        // fragment so a later getTextState().setFont(...) can honour them.
        for (int i = 1; i <= textFragments.size(); i++) {
            textFragments.get(i).setEditOptions(textEditOptions);
        }
        return textFragments;
    }

    /**
     * Returns the extracted text of the fragments this absorber matched.
     *
     * <p>For the empty/null-phrase "extract everything (optionally within a
     * {@link TextSearchOptions} rectangle)" mode this returns the matched
     * fragments — sorted into visual reading order and joined compactly (a single
     * space across an intra-line gap, a newline across a line break) — which
     * mirrors Aspose's {@code TextFragmentAbsorber.getText()} and honours the
     * rectangle filter. The inherited {@link TextAbsorber#getText()} instead
     * reconstructs a whole-page positional layout (proportional padding spaces,
     * ignoring the rectangle), which is correct for a plain {@link TextAbsorber}/
     * Pure extraction but not for a fragment absorber.</p>
     *
     * <p>When a real search phrase is set, behaviour is unchanged: it defers to
     * the inherited positional output so existing phrase-search callers are
     * unaffected.</p>
     *
     * @return the joined text of the matched fragments (empty-phrase mode), or
     *         the inherited positional text otherwise
     */
    @Override
    public String getText() {
        boolean extractAll = searchPhrase == null || searchPhrase.isEmpty();
        if (!extractAll || textFragments.getCount() == 0) {
            return super.getText();
        }
        java.util.List<TextFragment> list = new java.util.ArrayList<>(textFragments.getCount());
        for (TextFragment f : textFragments) {
            list.add(f);
        }
        // The empty-phrase path collects fragments in content-stream order, which
        // for column/table layouts is not reading order. Sort visually (top→bottom,
        // left→right) so buildSpans joins each row left-to-right, as Aspose does.
        list = TextAbsorber.sortByVisualPosition(list);
        StringBuilder out = new StringBuilder();
        buildSpans(list, out);
        return out.toString();
    }

    /**
     * Returns the search phrase.
     *
     * @return the search phrase, or null
     */
    public String getPhrase() {
        return searchPhrase;
    }

    /**
     * Returns the text search options.
     *
     * @return the options, never {@code null}
     */
    public TextSearchOptions getTextSearchOptions() {
        if (textSearchOptions == null) {
            textSearchOptions = new TextSearchOptions(false);
        }
        return textSearchOptions;
    }

    /**
     * Sets the text search options.
     *
     * @param options the search options
     */
    public void setTextSearchOptions(TextSearchOptions options) {
        this.textSearchOptions = options;
    }

    /**
     * Returns the text replace options. Non-null by default
     * (a {@link TextReplaceOptions} with scope {@link TextReplaceOptions.Scope#REPLACE_ALL}).
     * Returns whatever was last passed to {@link #setTextReplaceOptions}, including {@code null}.
     *
     * @return the replace options
     */
    public TextReplaceOptions getTextReplaceOptions() {
        return textReplaceOptions;
    }

    /**
     * Sets the text replace options. Accepts {@code null} to clear.
     *
     * @param options the replace options
     */
    public void setTextReplaceOptions(TextReplaceOptions options) {
        this.textReplaceOptions = options;
    }

    /**
     * Returns the text edit options. Non-null by default.
     *
     * @return the edit options, never {@code null}
     */
    public TextEditOptions getTextEditOptions() {
        if (textEditOptions == null) {
            textEditOptions = new TextEditOptions();
        }
        return textEditOptions;
    }

    /**
     * Sets the text edit options.
     *
     * @param options the edit options
     */
    public void setTextEditOptions(TextEditOptions options) {
        this.textEditOptions = options;
    }

    /**
     * Applies the given font to all collected text fragments in a single pass.
     * <p>Equivalent to iterating {@link #getTextFragments()} and setting
     * {@code fragment.getTextState().setFont(font)} on each, but performed as one
     * "mass operation" — matching {@code Aspose.Pdf.TextFragmentAbsorber.ApplyForAllFragments}.</p>
     *
     * @param font the font to apply to every fragment
     */
    public void applyForAllFragments(Font font) {
        for (TextFragment fragment : textFragments) {
            if (fragment.getTextState() != null) {
                fragment.getTextState().setFont(font);
            }
        }
    }

    private void searchExact(List<TextFragment> fragments, Page page, Rectangle areaFilter) {
        StringBuilder fullText = new StringBuilder();
        List<FragmentSpan> spans = buildSpans(fragments, fullText);
        boolean caseSensitive = textSearchOptions == null || textSearchOptions.isCaseSensitive();
        String haystack = fullText.toString();
        String needle = searchPhrase;
        if (!caseSensitive) {
            haystack = haystack.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int idx = 0;
        int found = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            TextFragment match = buildMatchFragment(searchPhrase, spans, idx, searchPhrase.length(), page);
            // Mirror searchRegex(): dedup equivalent matches. Without this, exact
            // search could emit duplicates that the regex path already suppresses
            // (asymmetry — see Sprint 29 Bug #3).
            if (shouldIncludeMatch(match, page, areaFilter) && !containsEquivalentMatch(match)) {
                textFragments.add(match);
                found++;
            }
            idx += Math.max(1, needle.length());
        }

        // RTL fallback (RTL2/RTL3_changeText): a multi-word RTL phrase is
        // supplied in logical order, but the haystack lines carry the words
        // in visual order (leftmost word first, each word already restored
        // to logical char order by reverseRtlRuns in buildSpans). Reversing
        // the token order of the needle maps logical → visual so the phrase
        // can be located; matched fragments keep the caller's logical text.
        if (found == 0) {
            String visualNeedle = rtlVisualNeedle(needle);
            if (visualNeedle != null) {
                idx = 0;
                while ((idx = haystack.indexOf(visualNeedle, idx)) >= 0) {
                    TextFragment match = buildMatchFragment(searchPhrase, spans, idx,
                            visualNeedle.length(), page);
                    if (shouldIncludeMatch(match, page, areaFilter) && !containsEquivalentMatch(match)) {
                        textFragments.add(match);
                    }
                    idx += Math.max(1, visualNeedle.length());
                }
            }
        }
    }

    /**
     * Maps a logical-order RTL phrase to the visual word order used by the
     * search haystack: the space-separated tokens are emitted in reverse.
     * Digit/Latin tokens (weak/LTR islands like {@code "777"}) keep their
     * internal order — only their position in the line flips, matching the
     * Unicode bidi display of an RTL paragraph.
     *
     * @return the visual-order needle, or {@code null} when the phrase has
     *         no strong RTL character or reversing does not change it
     */
    private static String rtlVisualNeedle(String needle) {
        boolean hasRtl = false;
        for (int i = 0; i < needle.length() && !hasRtl; i++) {
            hasRtl = TextAbsorber.isStrongRtl(needle.charAt(i));
        }
        if (!hasRtl || needle.indexOf(' ') < 0) {
            return null;
        }
        String[] tokens = needle.split(" ", -1);
        StringBuilder out = new StringBuilder(needle.length());
        for (int i = tokens.length - 1; i >= 0; i--) {
            out.append(tokens[i]);
            if (i > 0) out.append(' ');
        }
        String visual = out.toString();
        return visual.equals(needle) ? null : visual;
    }

    private void searchRegex(List<TextFragment> fragments, Page page, Rectangle areaFilter) {
        int flags = (textSearchOptions != null && !textSearchOptions.isCaseSensitive())
                ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern pattern = Pattern.compile(searchPhrase, flags);
        if (isSingleCharacterWordPattern()) {
            searchRegexSingleCharacterFragments(fragments, page, areaFilter, pattern);
            return;
        }
        StringBuilder fullText = new StringBuilder();
        List<FragmentSpan> spans = buildSpans(fragments, fullText);
        Matcher matcher = pattern.matcher(fullText.toString());
        while (matcher.find()) {
            String matchedText = matcher.group();
            if (isWhitespaceOnlyMatch(matchedText)) {
                continue;
            }
            TextFragment match = buildMatchFragment(matchedText, spans, matcher.start(), matchedText.length(), page);
            if (shouldIncludeMatch(match, page, areaFilter) && !containsEquivalentMatch(match)) {
                textFragments.add(match);
            }
        }
    }

    /**
     * A regex such as {@code [a-zA-Z0-9 ]+} that permits spaces can match a
     * lone synthetic separator space that {@link #buildSpans} inserts between
     * two non-adjacent runs (e.g. between punctuation-only watermarks drawn at
     * very different x positions). Such whitespace-only matches carry no
     * searchable content and are not surfaced by Aspose, so they are skipped
     * (PDFNET_47103). A genuinely EMPTY (zero-width) match is NOT skipped:
     * .NET Regex.Matches surfaces zero-length matches (e.g. a lazy prefix with
     * a lookahead, {@code (.{0,50}?)(?=Reviewed by)}) and Aspose returns them
     * as empty fragments (PDFNET_42073) — only our synthetic separator space
     * needs suppressing.
     */
    private static boolean isWhitespaceOnlyMatch(String matchedText) {
        return matchedText == null || (!matchedText.isEmpty() && matchedText.trim().isEmpty());
    }

    private boolean isSingleCharacterWordPattern() {
        if (searchPhrase == null) {
            return false;
        }
        String compact = searchPhrase.replaceAll("\\s+", "");
        if (!compact.startsWith("\\b") || !compact.endsWith("\\b")) {
            return false;
        }
        String body = compact.substring(2, compact.length() - 2);
        if (body.startsWith("(") && body.endsWith(")")) {
            body = body.substring(1, body.length() - 1);
        } else if (body.startsWith("[") && body.endsWith("]")) {
            body = body.substring(1, body.length() - 1);
        }
        if (body.isEmpty()) {
            return false;
        }
        String[] tokens = body.split("\\|");
        for (String token : tokens) {
            if (token.length() != 1 || !Character.isLetter(token.charAt(0))) {
                return false;
            }
        }
        return true;
    }

    private void searchRegexSingleCharacterFragments(List<TextFragment> fragments, Page page,
                                                     Rectangle areaFilter, Pattern pattern) {
        for (int i = 0; i < fragments.size(); i++) {
            TextFragment fragment = fragments.get(i);
            String text = fragment.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            TextFragment previous = i > 0 ? fragments.get(i - 1) : null;
            TextFragment next = i + 1 < fragments.size() ? fragments.get(i + 1) : null;
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                if (text.length() == 1) {
                    Rectangle rect = fragment.getRectangle();
                    if (shouldKeepAdjacent(previous, previous != null ? previous.getRectangle() : null, fragment, rect)
                            || shouldKeepAdjacent(fragment, rect, next, next != null ? next.getRectangle() : null)) {
                        continue;
                    }
                }
                TextFragment match = buildSingleSpanMatch(matcher.group(), fragment, matcher.start(),
                        matcher.end() - matcher.start(), page);
                applyRotatedSingleCharacterCoordinates(match, page);
                if (shouldIncludeMatch(match, page, areaFilter) && !containsEquivalentMatch(match)) {
                    textFragments.add(match);
                }
            }
        }
    }

    private void applyRotatedSingleCharacterCoordinates(TextFragment match, Page page) {
        Rectangle rect = match.getRectangle();
        if (rect == null || page == null) {
            return;
        }
        Rectangle rotatedRect = page.getRotationMatrix().reverse().transform(rect);
        if (rotatedRect == null || rotatedRect.getWidth() <= 0 || rotatedRect.getHeight() <= 0) {
            return;
        }
        double width = rotatedRect.getWidth();
        double correctionFactor = width < 8.0 ? 0.0218 : 0.017;
        double adjustedY = rotatedRect.getLLY() - width * correctionFactor;
        Position effectivePosition = new Position(rotatedRect.getLLX(), adjustedY);
        match.setRectangle(rotatedRect);
        match.setPosition(effectivePosition);
        for (TextSegment segment : match.getSegments()) {
            segment.setRectangle(rotatedRect);
            segment.setPosition(effectivePosition);
        }
    }

    private boolean containsEquivalentMatch(TextFragment candidate) {
        for (TextFragment existing : textFragments) {
            if (existing.getPage() != candidate.getPage()) {
                continue;
            }
            if (!safeEquals(existing.getText(), candidate.getText())) {
                continue;
            }
            if (existing.getSourceOperatorIndex() == candidate.getSourceOperatorIndex()
                    && existing.getSourceTextStart() == candidate.getSourceTextStart()
                    && existing.getSourceTextLength() == candidate.getSourceTextLength()) {
                return true;
            }
            Rectangle a = existing.getRectangle();
            Rectangle b = candidate.getRectangle();
            if (a != null && b != null
                    && Math.abs(a.getLLX() - b.getLLX()) < 0.01
                    && Math.abs(a.getLLY() - b.getLLY()) < 0.01
                    && Math.abs(a.getURX() - b.getURX()) < 0.01
                    && Math.abs(a.getURY() - b.getURY()) < 0.01) {
                return true;
            }
        }
        return false;
    }

    private boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private List<TextFragment> normalizeVisibleFragments(List<TextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return fragments;
        }
        List<TextFragment> normalized = new ArrayList<>(fragments.size());
        for (int i = 0; i < fragments.size(); i++) {
            TextFragment previous = i > 0 ? fragments.get(i - 1) : null;
            TextFragment current = fragments.get(i);
            TextFragment next = i + 1 < fragments.size() ? fragments.get(i + 1) : null;
            if (isWrapArtifactBlank(previous, current, next)) {
                continue;
            }
            normalized.add(current);
        }
        return normalized;
    }

    private boolean isWrapArtifactBlank(TextFragment previous, TextFragment current, TextFragment next) {
        if (previous == null || current == null || next == null) {
            return false;
        }
        String text = current.getText();
        if (text == null || !text.trim().isEmpty()) {
            return false;
        }
        Position previousPos = previous.getPosition();
        Position currentPos = current.getPosition();
        Position nextPos = next.getPosition();
        if (previousPos == null || currentPos == null || nextPos == null) {
            return false;
        }
        if (previous.getText() != null && previous.getText().trim().isEmpty()
                && Math.abs(previousPos.getYIndent() - currentPos.getYIndent()) <= 1.0) {
            return true;
        }
        if (Math.abs(previousPos.getYIndent() - currentPos.getYIndent()) > 0.75
                || Math.abs(nextPos.getYIndent() - currentPos.getYIndent()) > 0.75) {
            return false;
        }
        return nextPos.getXIndent() + 10.0 < currentPos.getXIndent()
                && currentPos.getXIndent() > previousPos.getXIndent();
    }

    private void searchAnnotations(Page page, Rectangle areaFilter, boolean regex) {
        for (Annotation annotation : page.getAnnotations()) {
            String contents = annotation.getContents();
            if (contents == null || contents.isEmpty()) {
                continue;
            }
            boolean matched;
            if (regex) {
                int flags = (textSearchOptions != null && !textSearchOptions.isCaseSensitive())
                        ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
                matched = Pattern.compile(searchPhrase, flags).matcher(contents).find();
            } else if (textSearchOptions != null && !textSearchOptions.isCaseSensitive()) {
                matched = contents.toLowerCase(Locale.ROOT)
                        .contains(searchPhrase.toLowerCase(Locale.ROOT));
            } else {
                matched = contents.contains(searchPhrase);
            }
            if (!matched) {
                continue;
            }
            TextFragment fragment = new TextFragment(contents);
            fragment.setPage(page);
            Rectangle rect = annotation.getRect();
            fragment.setRectangle(rect);
            if (rect != null) {
                fragment.setPosition(new Position(rect.getLLX(), rect.getLLY()));
            }
            if (shouldIncludeMatch(fragment, page, areaFilter)) {
                textFragments.add(fragment);
            }
        }
    }

    private void visitDocumentCombined(Document document) throws IOException {
        List<TextFragment> allFragments = new ArrayList<>();
        TextExtractor extractor = new TextExtractor(document.getParser());
        for (int i = 1; i <= document.getPages().size(); i++) {
            Page page = document.getPages().get(i);
            List<TextFragment> pageFragments = extractor.extract(page);
            for (TextFragment fragment : pageFragments) {
                fragment.setPage(page);
                allFragments.add(fragment);
            }
        }

        boolean isRegex = textSearchOptions != null && textSearchOptions.isRegularExpressionUsed();
        if (isRegex) {
            searchAcrossDocumentRegex(allFragments, document);
        } else {
            searchAcrossDocumentExact(allFragments, document);
        }
    }

    private static final class FragmentSpan {
        final TextFragment fragment;
        final int start;
        final int end;

        FragmentSpan(TextFragment fragment, int start, int end) {
            this.fragment = fragment;
            this.start = start;
            this.end = end;
        }
    }

    private List<FragmentSpan> buildSpans(List<TextFragment> fragments, StringBuilder out) {
        List<FragmentSpan> spans = new ArrayList<>(fragments.size());
        // On a /Rotate 90|270 page the reading direction runs along the
        // unrotated Y axis: fragments of one visual line share X and advance
        // in Y (PDFNEWNET_30639). Swap the roles of the two coordinates so
        // the same-line / gap heuristics keep working.
        boolean rotated = spanPageRotation == 90 || spanPageRotation == 270;
        double lastY = Double.NaN;
        double lastEndX = Double.NaN;
        Rectangle lastRect = null;
        TextFragment lastFragment = null;
        for (TextFragment fragment : fragments) {
            Position pos = fragment.getPosition();
            double rawY = pos != null ? pos.getYIndent() : Double.NaN;
            double rawX = pos != null ? pos.getXIndent() : Double.NaN;
            // "y" = line coordinate, "x" = advance coordinate. For /Rotate 90
            // the advance grows with +Y, for /Rotate 270 with -Y (mirror so
            // the "gap" comparison below stays monotonic).
            double y = rotated ? rawX : rawY;
            double x = !rotated ? rawX : (spanPageRotation == 90 ? rawY : -rawY);
            Rectangle rect = fragment.getRectangle();
            if (out.length() > 0 && !shouldKeepAdjacent(lastFragment, lastRect, fragment, rect)
                    && !Double.isNaN(y) && !Double.isNaN(lastY)) {
                if (Math.abs(y - lastY) > 1.0) {
                    out.append('\n');
                } else if (!Double.isNaN(x) && !Double.isNaN(lastEndX) && x > lastEndX + 1.0) {
                    out.append(' ');
                }
            }
            int start = out.length();
            // For RTL fragments the PDF stores glyphs in visual (left-to-right
            // display) order. The user-supplied search phrase is in logical
            // Unicode order, so reverse each strong-RTL run in the fragment's
            // text before appending — same transformation that TextAbsorber.visit
            // applies for getText(). reverseRtlRuns preserves length, so the
            // recorded (start,end) span stays consistent with the haystack.
            // LTR-only text is returned unchanged.
            out.append(TextAbsorber.reverseRtlRuns(fragment.getText()));
            spans.add(new FragmentSpan(fragment, start, out.length()));
            if (!rotated && rect != null && rect.getURX() > rect.getLLX()) {
                lastEndX = rect.getURX();
            } else if (rotated && rect != null && rect.getURY() > rect.getLLY()) {
                // Advance axis is Y on a rotated page; mirror for /Rotate 270
                // to match the mirrored advance coordinate above.
                lastEndX = spanPageRotation == 90 ? rect.getURY() : -rect.getLLY();
            } else if (!Double.isNaN(x)) {
                lastEndX = x + Math.max(1, fragment.getText().length()) * 5.0;
            } else {
                lastEndX = Double.NaN;
            }
            lastY = y;
            lastRect = rect;
            lastFragment = fragment;
        }
        return spans;
    }

    private boolean shouldKeepAdjacent(TextFragment previous, Rectangle previousRect,
                                       TextFragment current, Rectangle currentRect) {
        if (previous == null || current == null) {
            return false;
        }
        String previousText = previous.getText();
        String currentText = current.getText();
        if (previousText == null || currentText == null) {
            return false;
        }
        if (previousText.length() != 1 || currentText.length() != 1) {
            return false;
        }
        if (previousRect != null && currentRect != null) {
            if (intersectsWithTolerance(previousRect, currentRect, 2.5)) {
                return true;
            }
            double gapX = Math.max(0.0, Math.max(currentRect.getLLX() - previousRect.getURX(),
                    previousRect.getLLX() - currentRect.getURX()));
            double gapY = Math.max(0.0, Math.max(currentRect.getLLY() - previousRect.getURY(),
                    previousRect.getLLY() - currentRect.getURY()));
            double maxSize = Math.max(
                    Math.max(previousRect.getWidth(), previousRect.getHeight()),
                    Math.max(currentRect.getWidth(), currentRect.getHeight()));
            return gapX <= 2.5 && gapY <= Math.max(2.5, maxSize * 0.35);
        }
        Position previousPos = previous.getPosition();
        Position currentPos = current.getPosition();
        if (previousPos == null || currentPos == null) {
            return false;
        }
        double dx = Math.abs(currentPos.getXIndent() - previousPos.getXIndent());
        double dy = Math.abs(currentPos.getYIndent() - previousPos.getYIndent());
        return dx <= 8.0 && dy <= 8.0;
    }

    private boolean intersectsWithTolerance(Rectangle a, Rectangle b, double tolerance) {
        return a.getLLX() <= b.getURX() + tolerance
                && a.getURX() + tolerance >= b.getLLX()
                && a.getLLY() <= b.getURY() + tolerance
                && a.getURY() + tolerance >= b.getLLY();
    }

    private void searchAcrossDocumentExact(List<TextFragment> fragments, Document document) {
        StringBuilder fullText = new StringBuilder();
        List<FragmentSpan> spans = buildSpans(fragments, fullText);
        boolean caseSensitive = textSearchOptions == null || textSearchOptions.isCaseSensitive();
        String haystack = fullText.toString();
        String needle = searchPhrase;
        if (!caseSensitive) {
            haystack = haystack.toLowerCase(Locale.ROOT);
            needle = needle.toLowerCase(Locale.ROOT);
        }
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            String matchedText = fullText.substring(idx, Math.min(fullText.length(), idx + searchPhrase.length()));
            TextFragment match = buildDocumentMatchFragment(matchedText, spans, idx, matchedText.length(), document);
            textFragments.add(match);
            idx += Math.max(1, needle.length());
        }
    }

    private void searchAcrossDocumentRegex(List<TextFragment> fragments, Document document) {
        StringBuilder fullText = new StringBuilder();
        List<FragmentSpan> spans = buildSpans(fragments, fullText);
        int flags = (textSearchOptions != null && !textSearchOptions.isCaseSensitive())
                ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern pattern = Pattern.compile(searchPhrase, flags);
        Matcher matcher = pattern.matcher(fullText.toString());
        while (matcher.find()) {
            String matchedText = matcher.group();
            if (isWhitespaceOnlyMatch(matchedText)) {
                continue;
            }
            TextFragment match = buildDocumentMatchFragment(matchedText, spans, matcher.start(), matchedText.length(), document);
            textFragments.add(match);
        }
    }

    private FragmentSpan spanAt(List<FragmentSpan> spans, int idx) {
        for (FragmentSpan span : spans) {
            if (idx < span.end) {
                return span;
            }
        }
        return null;
    }

    private TextFragment buildMatchFragment(String phrase, List<FragmentSpan> spans, int idx, int len, Page page) {
        FragmentSpan span = spanAt(spans, idx);
        if (span == null) {
            TextFragment match = new TextFragment(phrase);
            match.setPage(page);
            return match;
        }

        List<FragmentSpan> participatingSpans = spansForRange(spans, idx, len);
        if (participatingSpans.size() <= 1) {
            return buildSingleSpanMatch(phrase, span.fragment, idx - span.start, len, page);
        }
        return buildMultiSpanMatch(phrase, participatingSpans, idx, len, page);
    }

    private List<FragmentSpan> spansForRange(List<FragmentSpan> spans, int idx, int len) {
        List<FragmentSpan> result = new ArrayList<>();
        int end = idx + Math.max(0, len);
        for (FragmentSpan span : spans) {
            if (span.end <= idx) {
                continue;
            }
            if (span.start >= end) {
                break;
            }
            result.add(span);
        }
        return result;
    }

    private TextFragment buildSingleSpanMatch(String phrase, TextFragment source, int offsetInSource, int len, Page page) {
        TextFragment match = new TextFragment(phrase);
        match.setPage(page);
        Rectangle rect = computeSubstringRectangle(source, offsetInSource, len, phrase);
        if (rect != null) {
            match.setPosition(new Position(rect.getLLX(), rect.getLLY()));
            match.setRectangle(rect);
            if (!match.getSegments().isEmpty()) {
                TextSegment seg = match.getSegments().get(0);
                seg.setPosition(new Position(rect.getLLX(), rect.getLLY()));
                seg.setRectangle(rect);
                seg.setTextState(source.getTextState());
            }
        } else {
            match.setPosition(source.getPosition());
            match.setRectangle(source.getRectangle());
        }
        match.setSourceOperatorIndex(source.getSourceOperatorIndex());
        match.setLastSourceOperatorIndex(source.getLastSourceOperatorIndex());
        match.setSourceOperator(source.getSourceOperator());
        match.setLastSourceOperator(source.getLastSourceOperator());
        match.setSourceFontName(source.getSourceFontName());
        match.setSourceFont(source.getSourceFont());
        match.setSourceTfSize(source.getSourceTfSize());
        match.setSourceTextStart(source.getSourceTextStart() + offsetInSource);
        match.setSourceTextLength(len);
        match.setSourceOperators(source.getSourceOperators());
        match.setSourceContentStream(source.getSourceContentStream());
        match.setSourceResources(source.getSourceResources());
        match.setTextReplaceOptions(getTextReplaceOptions());
        copyUnderlineLinkage(source, match);
        if (!match.getSegments().isEmpty()) {
            TextSegment seg = match.getSegments().get(0);
            int start = source.getSourceTextStart() + offsetInSource;
            seg.setStartCharIndex(start);
            seg.setEndCharIndex(start + Math.max(0, len) - 1);
        }
        // The match shares the source's TextState — route state mutations
        // (setFontSize) into the match's source-op write-back (PDFNEWNET-30639).
        if (source.getTextState() != null) {
            source.getTextState().bindSourceFragment(match);
        }
        return match;
    }

    /**
     * Propagates source-underline operator linkage from an extracted source
     * fragment to a match fragment so that turning the match's underline off
     * removes the underline operators on save. For multi-span matches this is
     * called for every participating source, so {@code match.getTextState()
     * .setUnderline(false)} strips the underline drawn under <em>all</em> of
     * them, not just the first segment (PDFNET_36417 / PDFNEWNET_39490).
     */
    private static void copyUnderlineLinkage(TextFragment source, TextFragment match) {
        if (source == null || match == null) {
            return;
        }
        List<List<Operator>> groups = source.getSourceUnderlineOpGroups();
        if (groups == null) {
            return;
        }
        for (List<Operator> group : groups) {
            match.addSourceUnderline(group, source.getSourceUnderlineCollection());
        }
    }

    private TextFragment buildMultiSpanMatch(String phrase, List<FragmentSpan> participatingSpans, int idx, int len, Page page) {
        TextFragment match = new TextFragment(phrase);
        match.getSegments().clear();
        match.setPage(page);

        Rectangle unionRect = null;
        Position firstPosition = null;
        int remaining = len;
        int currentIdx = idx;

        TextFragment firstSource = participatingSpans.get(0).fragment;
        TextFragment lastSource = participatingSpans.get(participatingSpans.size() - 1).fragment;
        match.setSourceOperatorIndex(firstSource.getSourceOperatorIndex());
        match.setLastSourceOperatorIndex(lastSource.getLastSourceOperatorIndex());
        match.setSourceOperator(firstSource.getSourceOperator());
        match.setLastSourceOperator(lastSource.getLastSourceOperator());
        match.setSourceFontName(firstSource.getSourceFontName());
        match.setSourceTfSize(firstSource.getSourceTfSize());
        match.setSourceTextStart(firstSource.getSourceTextStart() + Math.max(0, idx - participatingSpans.get(0).start));
        match.setSourceTextLength(len);
        match.setSourceOperators(firstSource.getSourceOperators());
        match.setSourceContentStream(firstSource.getSourceContentStream());
        match.setSourceResources(firstSource.getSourceResources());
        match.setTextReplaceOptions(getTextReplaceOptions());

        for (FragmentSpan participatingSpan : participatingSpans) {
            TextFragment source = participatingSpan.fragment;
            int localStart = Math.max(0, currentIdx - participatingSpan.start);
            int available = Math.max(0, source.getText().length() - localStart);
            int take = Math.min(remaining, available);
            if (take <= 0) {
                continue;
            }

            String segmentText = source.getText().substring(localStart, localStart + take);
            Rectangle segmentRect = computeSubstringRectangle(source, localStart, take, segmentText);
            Position segmentPosition = segmentRect != null
                    ? new Position(segmentRect.getLLX(), segmentRect.getLLY())
                    : source.getPosition();
            TextSegment segment = new TextSegment(segmentText);
            segment.setTextState(source.getTextState());
            segment.setPosition(segmentPosition);
            segment.setRectangle(segmentRect != null ? segmentRect : source.getRectangle());
            segment.setStartCharIndex(source.getSourceTextStart() + localStart);
            segment.setEndCharIndex(source.getSourceTextStart() + localStart + take - 1);
            match.addSegment(segment);
            copyUnderlineLinkage(source, match);

            if (firstPosition == null) {
                firstPosition = segmentPosition;
            }
            Rectangle effectiveRect = segment.getRectangle();
            if (effectiveRect != null) {
                unionRect = unionRect == null ? effectiveRect : union(unionRect, effectiveRect);
            }

            remaining -= take;
            currentIdx += take;
            if (remaining <= 0) {
                break;
            }
        }

        if (match.getSegments().isEmpty()) {
            match.addSegment(new TextSegment(phrase));
        }
        // The match's primary state is the first source's — route setFontSize
        // into the match's source-op write-back (PDFNEWNET-30639).
        if (firstSource.getTextState() != null) {
            firstSource.getTextState().bindSourceFragment(match);
        }
        if (firstPosition != null) {
            match.setPosition(firstPosition);
        }
        if (unionRect != null) {
            match.setRectangle(unionRect);
        }
        return match;
    }

    private Rectangle computeSubstringRectangle(TextFragment source, int offsetInSource, int len, String phrase) {
        Rectangle sourceRect = source.getRectangle();
        String sourceText = source.getText();
        if (sourceRect == null || sourceText == null || sourceText.isEmpty()) {
            return sourceRect;
        }
        // Defensive clamp: regex match span can land outside the source text
        // when the source fragment was assembled from multiple TJ operators
        // and the absorber's character-to-fragment mapping disagrees with the
        // raw search string (different normalisation, ligature expansion, etc).
        // Without this guard String.substring would throw later in the method.
        if (offsetInSource < 0) {
            offsetInSource = 0;
        } else if (offsetInSource > sourceText.length()) {
            offsetInSource = sourceText.length();
        }
        if (len < 0) {
            len = 0;
        }
        if (offsetInSource + len > sourceText.length()) {
            len = sourceText.length() - offsetInSource;
        }
        // Prefer exact per-character X boundaries captured by the extractor.
        // These preserve true horizontal advance (per-space word spacing Tw,
        // char spacing Tc) which proportional re-measurement below cannot
        // reconstruct from the merged fragment text — e.g. a wide tabular gap
        // encoded as a single Tw-stretched space (PDFNET_59697).
        double[] charX = source.getCharXPositions();
        if (charX != null && charX.length == sourceText.length() + 1
                && offsetInSource + len <= sourceText.length()
                && !(sourceRect.getHeight() > sourceRect.getWidth() * 3.0)) {
            double sx = charX[offsetInSource];
            double ex = charX[offsetInSource + len];
            if (ex < sx) {
                double t = sx; sx = ex; ex = t;
            }
            return new Rectangle(sx, sourceRect.getLLY(), ex, sourceRect.getURY());
        }
        if (sourceRect.getHeight() > sourceRect.getWidth() * 3.0) {
            double totalHeight = sourceRect.getHeight();
            double prefixHeight = totalHeight * offsetInSource / sourceText.length();
            double matchHeight = totalHeight * len / sourceText.length();
            Position sourcePosition = source.getPosition();
            boolean topDown = sourcePosition != null
                    && Math.abs(sourcePosition.getYIndent() - sourceRect.getURY()) <= Math.abs(sourcePosition.getYIndent() - sourceRect.getLLY());
            if (topDown) {
                double top = sourceRect.getURY() - prefixHeight;
                return new Rectangle(sourceRect.getLLX(), top - matchHeight, sourceRect.getURX(), top);
            }
            double bottom = sourceRect.getLLY() + prefixHeight;
            return new Rectangle(sourceRect.getLLX(), bottom, sourceRect.getURX(), bottom + matchHeight);
        }
        double startX = sourceRect.getLLX();
        double matchWidth = sourceRect.getURX() - sourceRect.getLLX();
        TextState sourceState = source.getTextState();
        String fontName = sourceState != null ? sourceState.getFontName() : null;
        double fontSize = sourceState != null ? sourceState.getFontSize() : 0;
        double actualSourceWidth = sourceRect.getURX() - sourceRect.getLLX();
        if (fontName != null && !fontName.isEmpty() && fontSize > 0) {
            String prefixText = sourceText.substring(0, Math.min(offsetInSource, sourceText.length()));
            double prefixWidth = TextLayoutHelper.measureTextWidth(prefixText, fontName, fontSize);
            matchWidth = TextLayoutHelper.measureTextWidth(phrase, fontName, fontSize);
            double measuredSourceWidth = TextLayoutHelper.measureTextWidth(sourceText, fontName, fontSize);
            if (measuredSourceWidth > 0 && actualSourceWidth > 0) {
                double scale = actualSourceWidth / measuredSourceWidth;
                prefixWidth *= scale;
                matchWidth *= scale;
            }
            if (actualSourceWidth > 0) {
                double prefixByChars = actualSourceWidth * offsetInSource / sourceText.length();
                double matchByChars = actualSourceWidth * len / sourceText.length();
                prefixWidth = Math.min(prefixWidth, prefixByChars);
                matchWidth = Math.min(matchWidth, matchByChars);
            }
            if (isPunctuationOnly(prefixText)) {
                prefixWidth *= 0.96535;
            }
            startX += prefixWidth;
        } else {
            double perChar = actualSourceWidth / sourceText.length();
            startX += perChar * offsetInSource;
            matchWidth = perChar * len;
        }
        double y0 = sourceRect.getLLY();
        double y1 = sourceRect.getURY();
        return new Rectangle(startX, y0, startX + matchWidth, y1);
    }

    private Rectangle union(Rectangle a, Rectangle b) {
        return new Rectangle(
                Math.min(a.getLLX(), b.getLLX()),
                Math.min(a.getLLY(), b.getLLY()),
                Math.max(a.getURX(), b.getURX()),
                Math.max(a.getURY(), b.getURY()));
    }

    private boolean isPunctuationOnly(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                return false;
            }
            if (!Character.isWhitespace(ch)
                    && "!@#$%^&*()_+-=[]{}|;:'\",.<>/?`~\\".indexOf(ch) < 0) {
                return false;
            }
        }
        return true;
    }

    private TextFragment buildDocumentMatchFragment(String phrase, List<FragmentSpan> spans, int idx, int len, Document document) {
        FragmentSpan span = spanAt(spans, idx);
        Page firstPage = span != null && span.fragment != null ? span.fragment.getPage() : null;
        TextFragment match = buildMatchFragment(phrase, spans, idx, len, firstPage);
        if (match.getPage() == null && firstPage == null) {
            try {
                if (document.getPages().size() > 0) {
                    match.setPage(document.getPages().get(1));
                }
            } catch (IOException ignored) {
                // Keep the match page-less if page lookup fails during recovery.
            }
        }
        return match;
    }

    private boolean isInArea(TextFragment frag, Rectangle area) {
        Rectangle rect = frag.getRectangle();
        if (rect != null) {
            // 1pt tolerance: our ascent estimate can exceed the producer's
            // glyph box slightly (Aspose search rectangles are typically the
            // fragment rectangle verbatim — PDFNET_51643 overshoots URY by
            // 0.5pt), and a sub-point overshoot must not drop a real match.
            final double tol = 1.0;
            return rect.getLLX() >= area.getLLX() - tol && rect.getLLY() >= area.getLLY() - tol
                    && rect.getURX() <= area.getURX() + tol && rect.getURY() <= area.getURY() + tol;
        }
        Position position = frag.getPosition();
        if (position == null) {
            return true;
        }
        double x = position.getXIndent();
        double y = position.getYIndent();
        return x >= area.getLLX() && x <= area.getURX()
                && y >= area.getLLY() && y <= area.getURY();
    }

    private boolean shouldIncludeMatch(TextFragment fragment, Page page, Rectangle areaFilter) {
        if (areaFilter != null && !isInArea(fragment, areaFilter)) {
            return false;
        }
        if (textSearchOptions != null && textSearchOptions.isLimitToPageBounds()
                && fragment.getRectangle() != null) {
            Rectangle pageRect = page.getRect();
            if (pageRect != null && !isInArea(fragment, pageRect)) {
                return false;
            }
        }
        if (textSearchOptions != null && textSearchOptions.getExcludeRectangles() != null
                && fragment.getRectangle() != null) {
            for (Rectangle excluded : textSearchOptions.getExcludeRectangles()) {
                if (excluded != null && intersects(fragment.getRectangle(), excluded)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean intersects(Rectangle a, Rectangle b) {
        return a.getLLX() < b.getURX() && a.getURX() > b.getLLX()
                && a.getLLY() < b.getURY() && a.getURY() > b.getLLY();
    }
}
