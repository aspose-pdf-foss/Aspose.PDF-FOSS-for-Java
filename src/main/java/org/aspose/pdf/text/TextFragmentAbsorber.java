package org.aspose.pdf.text;

import org.aspose.pdf.Document;
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
    private final TextFragmentCollection textFragments = new TextFragmentCollection();
    private TextSearchOptions textSearchOptions;
    // Aspose convention: TextFragmentAbsorber exposes a non-null default
    // so callers can write `absorber.getTextReplaceOptions().setReplaceAdjustmentAction(...)`
    // without a null check. PdfContentEditor keeps the null default.
    private TextReplaceOptions textReplaceOptions = new TextReplaceOptions(TextReplaceOptions.Scope.REPLACE_ALL);

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

            if (isRegex) {
                searchRegex(allFragments, page, areaFilter);
            } else {
                searchExact(allFragments, page, areaFilter);
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
        return textFragments;
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
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            TextFragment match = buildMatchFragment(searchPhrase, spans, idx, searchPhrase.length(), page);
            // Mirror searchRegex(): dedup equivalent matches. Without this, exact
            // search could emit duplicates that the regex path already suppresses
            // (asymmetry — see Sprint 29 Bug #3).
            if (shouldIncludeMatch(match, page, areaFilter) && !containsEquivalentMatch(match)) {
                textFragments.add(match);
            }
            idx += Math.max(1, needle.length());
        }
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
     * (PDFNET_47103). A genuinely empty pattern match is also skipped.
     */
    private static boolean isWhitespaceOnlyMatch(String matchedText) {
        return matchedText == null || matchedText.trim().isEmpty();
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
        double lastY = Double.NaN;
        double lastEndX = Double.NaN;
        Rectangle lastRect = null;
        TextFragment lastFragment = null;
        for (TextFragment fragment : fragments) {
            Position pos = fragment.getPosition();
            double y = pos != null ? pos.getYIndent() : Double.NaN;
            double x = pos != null ? pos.getXIndent() : Double.NaN;
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
            if (rect != null && rect.getURX() > rect.getLLX()) {
                lastEndX = rect.getURX();
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
        match.setSourceTextStart(source.getSourceTextStart() + offsetInSource);
        match.setSourceTextLength(len);
        match.setSourceOperators(source.getSourceOperators());
        match.setSourceContentStream(source.getSourceContentStream());
        match.setTextReplaceOptions(getTextReplaceOptions());
        if (!match.getSegments().isEmpty()) {
            TextSegment seg = match.getSegments().get(0);
            int start = source.getSourceTextStart() + offsetInSource;
            seg.setStartCharIndex(start);
            seg.setEndCharIndex(start + Math.max(0, len) - 1);
        }
        return match;
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
        match.setSourceTextStart(firstSource.getSourceTextStart() + Math.max(0, idx - participatingSpans.get(0).start));
        match.setSourceTextLength(len);
        match.setSourceOperators(firstSource.getSourceOperators());
        match.setSourceContentStream(firstSource.getSourceContentStream());
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
            return rect.getLLX() >= area.getLLX() && rect.getLLY() >= area.getLLY()
                    && rect.getURX() <= area.getURX() && rect.getURY() <= area.getURY();
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
