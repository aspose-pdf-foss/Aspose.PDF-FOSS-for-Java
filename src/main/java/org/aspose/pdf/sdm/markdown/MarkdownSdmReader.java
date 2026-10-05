package org.aspose.pdf.sdm.markdown;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.LineBreak;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.sdm.ThematicBreak;

/**
 * Markdown &rarr; SDM reader — the load-side mirror of {@link SdmMarkdownWriter}.
 *
 * <p>Parses a CommonMark / GitHub-Flavored Markdown subset into the Semantic
 * Document Model, which the shared {@code SdmPdfLayout} then paginates into a
 * PDF. Recognised block constructs: ATX headings ({@code #}&hellip;{@code
 * ######}) and setext headings, fenced code blocks ({@code ```}/{@code ~~~}),
 * block quotes ({@code >}), ordered/unordered lists (nested by indentation),
 * GFM pipe tables (with per-column alignment), thematic breaks and paragraphs.
 * Inline: {@code **bold**}, {@code *italic*}, {@code ***both***},
 * {@code ~~strike~~}, {@code `code`}, {@code [text](url)}, {@code ![alt](url)},
 * footnote references {@code [^id]}, and hard line breaks. A leading YAML
 * front-matter block ({@code ---}) sets the document title/author.</p>
 *
 * <p>This is a hand-written zero-dependency parser: it favours the common,
 * well-formed cases and degrades gracefully (an unmatched marker becomes literal
 * text) rather than aiming at full CommonMark conformance.</p>
 */
public final class MarkdownSdmReader {

    private static final Logger LOG = Logger.getLogger(MarkdownSdmReader.class.getName());

    /**
     * Parses UTF-8 Markdown bytes into an SDM document.
     *
     * @param bytes the Markdown source; must not be null
     * @return the parsed SDM document
     */
    public SdmDocument read(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes must not be null");
        }
        return read(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * Parses a Markdown string into an SDM document.
     *
     * @param markdown the Markdown source; must not be null
     * @return the parsed SDM document
     */
    public SdmDocument read(String markdown) {
        if (markdown == null) {
            throw new IllegalArgumentException("markdown must not be null");
        }
        SdmDocument doc = new SdmDocument();
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>();
        for (String l : normalized.split("\n", -1)) {
            lines.add(l);
        }
        int start = readFrontMatter(lines, doc);
        List<String> body = lines.subList(start, lines.size());
        for (SdmBlock b : parseBlocks(body)) {
            doc.getChildren().add(b);
        }
        LOG.fine(() -> "MarkdownSdmReader: parsed " + doc.getChildren().size() + " top-level blocks");
        return doc;
    }

    /** Reads a leading {@code ---} YAML front-matter block; returns the body start index. */
    private int readFrontMatter(List<String> lines, SdmDocument doc) {
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return 0;
        }
        for (int i = 1; i < lines.size(); i++) {
            String l = lines.get(i).trim();
            if (l.equals("---") || l.equals("...")) {
                return i + 1;
            }
            int colon = lines.get(i).indexOf(':');
            if (colon > 0) {
                String key = lines.get(i).substring(0, colon).trim().toLowerCase();
                String val = unquoteYaml(lines.get(i).substring(colon + 1).trim());
                if (key.equals("title")) {
                    doc.getMetadata().setTitle(val);
                } else if (key.equals("author")) {
                    doc.getMetadata().setAuthor(val);
                }
            }
        }
        return 0; // no closing fence — treat as body
    }

    private static String unquoteYaml(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Block parsing
    // ------------------------------------------------------------------

    private List<SdmBlock> parseBlocks(List<String> lines) {
        List<SdmBlock> blocks = new ArrayList<>();
        int i = 0;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                i++;
                continue;
            }
            // Fenced code block.
            String fence = fenceMarker(line);
            if (fence != null) {
                int[] end = new int[1];
                blocks.add(parseFencedCode(lines, i, fence, end));
                i = end[0];
                continue;
            }
            // ATX heading.
            Heading atx = parseAtxHeading(line);
            if (atx != null) {
                blocks.add(atx);
                i++;
                continue;
            }
            // Thematic break.
            if (isThematicBreak(line)) {
                blocks.add(new ThematicBreak());
                i++;
                continue;
            }
            // Block quote.
            if (stripQuote(line) != null) {
                int[] end = new int[1];
                blocks.add(parseQuote(lines, i, end));
                i = end[0];
                continue;
            }
            // List.
            if (listMarker(line) != null) {
                int[] end = new int[1];
                blocks.add(parseList(lines, i, end));
                i = end[0];
                continue;
            }
            // GFM table: current line + a delimiter row next.
            if (i + 1 < n && line.indexOf('|') >= 0 && isTableDelimiter(lines.get(i + 1))) {
                int[] end = new int[1];
                blocks.add(parseTable(lines, i, end));
                i = end[0];
                continue;
            }
            // Paragraph (with possible setext underline).
            int[] end = new int[1];
            blocks.add(parseParagraphOrSetext(lines, i, end));
            i = end[0];
        }
        return blocks;
    }

    private Heading parseAtxHeading(String line) {
        int i = 0;
        int lead = countLeadingSpaces(line);
        if (lead > 3) {
            return null;
        }
        i = lead;
        int hashes = 0;
        while (i < line.length() && line.charAt(i) == '#') {
            hashes++;
            i++;
        }
        if (hashes < 1 || hashes > 6) {
            return null;
        }
        if (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != '\t') {
            return null; // "#text" is not a heading
        }
        String text = line.substring(i).trim();
        // Strip an optional closing run of hashes ("## Title ##").
        text = text.replaceAll("\\s+#+\\s*$", "").trim();
        Heading h = new Heading(hashes);
        for (SdmInline in : parseInlines(text)) {
            h.getInline().add(in);
        }
        return h;
    }

    private CodeBlock parseFencedCode(List<String> lines, int start, String fence, int[] end) {
        String opener = lines.get(start).trim();
        String lang = opener.substring(fence.length()).trim();
        char fenceChar = fence.charAt(0);
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        boolean closed = false;
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            String t = l.trim();
            if (t.length() >= fence.length() && allSame(t, fenceChar)) {
                closed = true;
                i++;
                break;
            }
            code.append(l).append('\n');
        }
        if (!closed) {
            LOG.fine("MarkdownSdmReader: unterminated code fence");
        }
        end[0] = i;
        // Drop the trailing newline the loop always appends.
        if (code.length() > 0 && code.charAt(code.length() - 1) == '\n') {
            code.setLength(code.length() - 1);
        }
        return new CodeBlock(code.toString(), lang.isEmpty() ? null : lang);
    }

    private Quote parseQuote(List<String> lines, int start, int[] end) {
        List<String> inner = new ArrayList<>();
        int i = start;
        for (; i < lines.size(); i++) {
            String stripped = stripQuote(lines.get(i));
            if (stripped == null) {
                // A blank line ends the quote unless the next line continues it.
                if (lines.get(i).trim().isEmpty()) {
                    break;
                }
                // Lazy continuation: a plain paragraph line under a quote.
                if (!inner.isEmpty()) {
                    inner.add(lines.get(i));
                    continue;
                }
                break;
            }
            inner.add(stripped);
        }
        end[0] = i;
        Quote q = new Quote();
        for (SdmBlock b : parseBlocks(inner)) {
            q.getChildren().add(b);
        }
        return q;
    }

    private ListBlock parseList(List<String> lines, int start, int[] end) {
        Marker first = listMarker(lines.get(start));
        ListBlock list = new ListBlock(first.ordered, first.ordered ? first.startNum : null);
        int i = start;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            Marker m = listMarker(line);
            if (m == null || m.ordered != first.ordered || m.indent != first.indent) {
                // A blank line may sit between items — peek past it.
                if (line.trim().isEmpty() && i + 1 < n) {
                    Marker next = listMarker(lines.get(i + 1));
                    if (next != null && next.ordered == first.ordered && next.indent == first.indent) {
                        i++;
                        continue;
                    }
                }
                break;
            }
            // Collect this item's lines: the remainder of the marker line plus
            // subsequent lines indented under the marker (or blank).
            List<String> itemLines = new ArrayList<>();
            itemLines.add(line.substring(m.contentStart));
            i++;
            while (i < n) {
                String cont = lines.get(i);
                if (cont.trim().isEmpty()) {
                    // Blank: part of the item only if a following line stays indented.
                    if (i + 1 < n && countLeadingSpaces(lines.get(i + 1)) >= m.contentStart
                            && listMarker(lines.get(i + 1)) == null) {
                        itemLines.add("");
                        i++;
                        continue;
                    }
                    break;
                }
                if (listMarker(cont) != null && countLeadingSpaces(cont) <= m.indent) {
                    break; // next sibling item
                }
                if (countLeadingSpaces(cont) >= m.contentStart) {
                    itemLines.add(cont.substring(m.contentStart));
                    i++;
                } else if (listMarker(cont) == null && !isBlockStart(cont)) {
                    // Lazy continuation of the item's paragraph.
                    itemLines.add(cont.trim());
                    i++;
                } else {
                    break;
                }
            }
            ListItem item = new ListItem();
            for (SdmBlock b : parseBlocks(itemLines)) {
                item.getChildren().add(b);
            }
            list.getItems().add(item);
        }
        end[0] = i;
        return list;
    }

    private Table parseTable(List<String> lines, int start, int[] end) {
        List<String> headerCells = splitTableRow(lines.get(start));
        List<ColumnSpec.Align> aligns = parseAligns(lines.get(start + 1));
        int cols = Math.max(headerCells.size(), aligns.size());
        Table table = new Table();
        for (int c = 0; c < cols; c++) {
            ColumnSpec.Align a = c < aligns.size() ? aligns.get(c) : null;
            table.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.AUTO, 0,
                    a == null ? ColumnSpec.Align.LEFT : a));
        }
        table.getRows().add(buildTableRow(headerCells, cols, TableRow.Kind.HEADER, TableCell.Kind.TH));
        int i = start + 2;
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.trim().isEmpty() || l.indexOf('|') < 0) {
                break;
            }
            table.getRows().add(buildTableRow(splitTableRow(l), cols,
                    TableRow.Kind.BODY, TableCell.Kind.TD));
        }
        end[0] = i;
        return table;
    }

    private TableRow buildTableRow(List<String> cells, int cols, TableRow.Kind rowKind,
            TableCell.Kind cellKind) {
        TableRow row = new TableRow(rowKind);
        for (int c = 0; c < cols; c++) {
            TableCell cell = new TableCell();
            cell.setKind(cellKind);
            String text = c < cells.size() ? cells.get(c) : "";
            Paragraph p = new Paragraph();
            for (SdmInline in : parseInlines(text.replace("<br>", "\n").replace("<br/>", "\n"))) {
                p.getInline().add(in);
            }
            cell.getChildren().add(p);
            row.getCells().add(cell);
        }
        return row;
    }

    private SdmBlock parseParagraphOrSetext(List<String> lines, int start, int[] end) {
        List<String> para = new ArrayList<>();
        int i = start;
        int n = lines.size();
        while (i < n) {
            String line = lines.get(i);
            if (line.trim().isEmpty()) {
                break;
            }
            // A setext underline turns the accumulated paragraph into a heading.
            if (!para.isEmpty()) {
                int lvl = setextLevel(line);
                if (lvl > 0) {
                    i++;
                    end[0] = i;
                    Heading h = new Heading(lvl);
                    for (SdmInline in : parseParagraphInlines(para)) {
                        h.getInline().add(in);
                    }
                    return h;
                }
            }
            // Another block construct interrupts the paragraph.
            if (isBlockStart(line) || parseAtxHeading(line) != null || isThematicBreak(line)
                    || (i + 1 < n && line.indexOf('|') >= 0 && isTableDelimiter(lines.get(i + 1)))) {
                if (!para.isEmpty()) {
                    break;
                }
            }
            para.add(line);
            i++;
        }
        end[0] = i;
        Paragraph p = new Paragraph();
        for (SdmInline in : parseParagraphInlines(para)) {
            p.getInline().add(in);
        }
        return p;
    }

    // ------------------------------------------------------------------
    // Inline parsing
    // ------------------------------------------------------------------

    /** Parses the wrapped lines of one paragraph, honouring hard line breaks. */
    private List<SdmInline> parseParagraphInlines(List<String> lines) {
        List<SdmInline> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean hardBreak = line.endsWith("  ") || line.endsWith("\\");
            String content = line;
            if (line.endsWith("\\")) {
                content = line.substring(0, line.length() - 1);
            }
            content = content.trim();
            out.addAll(parseInlines(content));
            if (i < lines.size() - 1) {
                if (hardBreak) {
                    out.add(new LineBreak());
                } else {
                    out.add(new Run(" ", null));
                }
            }
        }
        return out;
    }

    /** Parses a single logical line of inline Markdown. */
    List<SdmInline> parseInlines(String text) {
        List<SdmInline> out = new ArrayList<>();
        parseInlinesInto(out, text, false, false, false, false);
        return out;
    }

    private void parseInlinesInto(List<SdmInline> out, String text,
            boolean bold, boolean italic, boolean strike, boolean mono) {
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            // Backslash escape.
            if (c == '\\' && i + 1 < n && isEscapable(text.charAt(i + 1))) {
                buf.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            // Image.
            if (c == '!' && i + 1 < n && text.charAt(i + 1) == '[') {
                int[] adv = new int[1];
                SdmInline img = tryImage(text, i, adv);
                if (img != null) {
                    flush(out, buf, bold, italic, strike, mono);
                    out.add(img);
                    i = adv[0];
                    continue;
                }
            }
            // Link or footnote ref.
            if (c == '[') {
                int[] adv = new int[1];
                if (text.startsWith("[^", i)) {
                    SdmInline fn = tryFootnoteRef(text, i, adv);
                    if (fn != null) {
                        flush(out, buf, bold, italic, strike, mono);
                        out.add(fn);
                        i = adv[0];
                        continue;
                    }
                }
                SdmInline link = tryLink(text, i, adv, bold, italic, strike, mono);
                if (link != null) {
                    flush(out, buf, bold, italic, strike, mono);
                    out.add(link);
                    i = adv[0];
                    continue;
                }
            }
            // Code span.
            if (c == '`') {
                int[] adv = new int[1];
                String code = tryCodeSpan(text, i, adv);
                if (code != null) {
                    flush(out, buf, bold, italic, strike, mono);
                    out.add(new Run(code, makeStyle(bold, italic, strike, true)));
                    i = adv[0];
                    continue;
                }
            }
            // Strikethrough.
            if (c == '~' && i + 1 < n && text.charAt(i + 1) == '~' && !strike) {
                int close = text.indexOf("~~", i + 2);
                if (close > i + 1) {
                    flush(out, buf, bold, italic, strike, mono);
                    parseInlinesInto(out, text.substring(i + 2, close), bold, italic, true, mono);
                    i = close + 2;
                    continue;
                }
            }
            // Emphasis (* or _).
            if ((c == '*' || c == '_')) {
                int[] adv = new int[1];
                if (tryEmphasis(out, text, i, c, buf, bold, italic, strike, mono, adv)) {
                    i = adv[0];
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(out, buf, bold, italic, strike, mono);
    }

    private boolean tryEmphasis(List<SdmInline> out, String text, int i, char marker,
            StringBuilder buf, boolean bold, boolean italic, boolean strike, boolean mono, int[] adv) {
        int n = text.length();
        int runLen = 0;
        while (i + runLen < n && text.charAt(i + runLen) == marker) {
            runLen++;
        }
        // Cap at 3 (***). More markers: treat extras as literal after the span.
        int use = Math.min(runLen, 3);
        // A valid opener is not followed by whitespace.
        if (i + use >= n || Character.isWhitespace(text.charAt(i + use))) {
            return false;
        }
        // Find a matching closing run of at least `use` markers not preceded by space.
        int searchFrom = i + use;
        while (searchFrom < n) {
            int close = text.indexOf(marker, searchFrom);
            if (close < 0) {
                return false;
            }
            int closeLen = 0;
            while (close + closeLen < n && text.charAt(close + closeLen) == marker) {
                closeLen++;
            }
            boolean precededBySpace = close == 0 || Character.isWhitespace(text.charAt(close - 1));
            if (closeLen >= use && !precededBySpace && close > i + use) {
                flush(out, buf, bold, italic, strike, mono);
                boolean nb = bold, ni = italic;
                if (use == 1) {
                    ni = true;
                } else if (use == 2) {
                    nb = true;
                } else {
                    nb = true;
                    ni = true;
                }
                parseInlinesInto(out, text.substring(i + use, close), nb, ni, strike, mono);
                adv[0] = close + use;
                return true;
            }
            searchFrom = close + closeLen;
        }
        return false;
    }

    private SdmInline tryImage(String text, int i, int[] adv) {
        // ![alt](url)
        int bracket = i + 1; // points at '['
        int closeB = matchBracket(text, bracket);
        if (closeB < 0 || closeB + 1 >= text.length() || text.charAt(closeB + 1) != '(') {
            return null;
        }
        int closeP = text.indexOf(')', closeB + 2);
        if (closeP < 0) {
            return null;
        }
        String alt = text.substring(bracket + 1, closeB);
        String url = text.substring(closeB + 2, closeP).trim();
        adv[0] = closeP + 1;
        // No ResourceTable payload on load (the URL may be external/relative); the
        // layout keeps images as figures only when bytes are present, so represent
        // the reference as a link-styled run to avoid losing the alt text.
        LinkInline link = new LinkInline(url);
        link.getChildren().add(new Run(alt.isEmpty() ? url : alt, null));
        return link;
    }

    private SdmInline tryLink(String text, int i, int[] adv,
            boolean bold, boolean italic, boolean strike, boolean mono) {
        int closeB = matchBracket(text, i);
        if (closeB < 0 || closeB + 1 >= text.length() || text.charAt(closeB + 1) != '(') {
            return null;
        }
        int closeP = text.indexOf(')', closeB + 2);
        if (closeP < 0) {
            return null;
        }
        String inner = text.substring(i + 1, closeB);
        String url = text.substring(closeB + 2, closeP).trim();
        adv[0] = closeP + 1;
        LinkInline link = new LinkInline(url);
        parseInlinesInto(link.getChildren(), inner, bold, italic, strike, mono);
        return link;
    }

    private SdmInline tryFootnoteRef(String text, int i, int[] adv) {
        int close = text.indexOf(']', i + 2);
        if (close < 0) {
            return null;
        }
        String id = text.substring(i + 2, close);
        if (id.isEmpty() || id.indexOf(' ') >= 0) {
            return null;
        }
        adv[0] = close + 1;
        return new org.aspose.pdf.sdm.FootnoteRef(id);
    }

    private String tryCodeSpan(String text, int i, int[] adv) {
        int n = text.length();
        int tickLen = 0;
        while (i + tickLen < n && text.charAt(i + tickLen) == '`') {
            tickLen++;
        }
        String fence = text.substring(i, i + tickLen);
        int close = text.indexOf(fence, i + tickLen);
        // Ensure the closing run is exactly tickLen (not part of a longer run).
        while (close >= 0) {
            int after = close + tickLen;
            if (after < n && text.charAt(after) == '`') {
                close = text.indexOf(fence, after + 1);
                continue;
            }
            break;
        }
        if (close < 0) {
            return null;
        }
        String code = text.substring(i + tickLen, close);
        // A single leading & trailing space is stripped (CommonMark).
        if (code.length() >= 2 && code.startsWith(" ") && code.endsWith(" ")) {
            code = code.substring(1, code.length() - 1);
        }
        adv[0] = close + tickLen;
        return code;
    }

    private void flush(List<SdmInline> out, StringBuilder buf,
            boolean bold, boolean italic, boolean strike, boolean mono) {
        if (buf.length() == 0) {
            return;
        }
        out.add(new Run(buf.toString(), makeStyle(bold, italic, strike, mono)));
        buf.setLength(0);
    }

    private static TextStyle makeStyle(boolean bold, boolean italic, boolean strike, boolean mono) {
        if (!bold && !italic && !strike && !mono) {
            return null;
        }
        TextStyle st = new TextStyle();
        st.setBold(bold);
        st.setItalic(italic);
        st.setStrikethrough(strike);
        if (mono) {
            st.setFontFamily("monospace");
        }
        return st;
    }

    // ------------------------------------------------------------------
    // Low-level line helpers
    // ------------------------------------------------------------------

    private static int countLeadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    private static boolean allSame(String s, char c) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != c) {
                return false;
            }
        }
        return !s.isEmpty();
    }

    /** Returns the fence token ({@code ```}/{@code ~~~}) if the line opens a fence. */
    private static String fenceMarker(String line) {
        int lead = countLeadingSpaces(line);
        if (lead > 3) {
            return null;
        }
        String t = line.substring(lead);
        char c = t.isEmpty() ? ' ' : t.charAt(0);
        if (c != '`' && c != '~') {
            return null;
        }
        int len = 0;
        while (len < t.length() && t.charAt(len) == c) {
            len++;
        }
        if (len < 3) {
            return null;
        }
        // An info string on a backtick fence may not contain more backticks.
        return t.substring(0, len);
    }

    private static boolean isThematicBreak(String line) {
        String t = line.trim();
        if (t.length() < 3) {
            return false;
        }
        char c = t.charAt(0);
        if (c != '-' && c != '*' && c != '_') {
            return false;
        }
        int count = 0;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch == c) {
                count++;
            } else if (ch != ' ' && ch != '\t') {
                return false;
            }
        }
        return count >= 3;
    }

    private static int setextLevel(String line) {
        String t = line.trim();
        if (t.isEmpty()) {
            return 0;
        }
        if (allSame(t, '=')) {
            return 1;
        }
        if (allSame(t, '-')) {
            return 2;
        }
        return 0;
    }

    /** Strips one level of {@code >} quote prefix, or null if the line is not quoted. */
    private static String stripQuote(String line) {
        int lead = countLeadingSpaces(line);
        if (lead > 3 || lead >= line.length() || line.charAt(lead) != '>') {
            return null;
        }
        int i = lead + 1;
        if (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return line.substring(i);
    }

    private static boolean isTableDelimiter(String line) {
        String t = line.trim();
        if (t.isEmpty() || t.indexOf('-') < 0) {
            return false;
        }
        // Every cell must be a run of -, optionally wrapped in : and spaces.
        for (String cell : t.split("\\|", -1)) {
            String c = cell.trim();
            if (c.isEmpty()) {
                continue; // leading/trailing empty from border pipes
            }
            if (!c.matches(":?-+:?")) {
                return false;
            }
        }
        return t.indexOf('-') >= 0;
    }

    private static List<ColumnSpec.Align> parseAligns(String delimiterRow) {
        List<ColumnSpec.Align> aligns = new ArrayList<>();
        for (String raw : stripBorders(delimiterRow)) {
            String c = raw.trim();
            boolean left = c.startsWith(":");
            boolean right = c.endsWith(":");
            if (left && right) {
                aligns.add(ColumnSpec.Align.CENTER);
            } else if (right) {
                aligns.add(ColumnSpec.Align.RIGHT);
            } else if (left) {
                aligns.add(ColumnSpec.Align.LEFT);
            } else {
                aligns.add(ColumnSpec.Align.LEFT);
            }
        }
        return aligns;
    }

    private static List<String> splitTableRow(String line) {
        List<String> cells = new ArrayList<>();
        for (String raw : stripBorders(line)) {
            cells.add(raw.trim());
        }
        return cells;
    }

    /** Splits a table row on unescaped pipes, dropping the outer border pipes. */
    private static List<String> stripBorders(String line) {
        String t = line.trim();
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|") && !t.endsWith("\\|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\\' && i + 1 < t.length() && t.charAt(i + 1) == '|') {
                cur.append('|');
                i++;
            } else if (c == '|') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }

    private Marker listMarker(String line) {
        int indent = countLeadingSpaces(line);
        if (indent >= line.length()) {
            return null;
        }
        char c = line.charAt(indent);
        // Unordered.
        if (c == '-' || c == '+' || c == '*') {
            int after = indent + 1;
            if (after < line.length() && (line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
                // A "- - -" style line is a thematic break, not a list.
                if (isThematicBreak(line)) {
                    return null;
                }
                int cs = after;
                while (cs < line.length() && (line.charAt(cs) == ' ' || line.charAt(cs) == '\t')) {
                    cs++;
                }
                return new Marker(false, 0, indent, cs);
            }
            return null;
        }
        // Ordered.
        if (Character.isDigit(c)) {
            int j = indent;
            while (j < line.length() && Character.isDigit(line.charAt(j)) && j - indent < 9) {
                j++;
            }
            if (j < line.length() && (line.charAt(j) == '.' || line.charAt(j) == ')')) {
                int after = j + 1;
                if (after < line.length() && (line.charAt(after) == ' ' || line.charAt(after) == '\t')) {
                    int start = Integer.parseInt(line.substring(indent, j));
                    int cs = after;
                    while (cs < line.length() && (line.charAt(cs) == ' ' || line.charAt(cs) == '\t')) {
                        cs++;
                    }
                    return new Marker(true, start, indent, cs);
                }
            }
        }
        return null;
    }

    /** True when the line starts a non-paragraph block construct. */
    private boolean isBlockStart(String line) {
        return fenceMarker(line) != null
                || isThematicBreak(line)
                || stripQuote(line) != null
                || listMarker(line) != null;
    }

    private static int matchBracket(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isEscapable(char c) {
        return "\\`*_{}[]()#+-.!|<>~\"".indexOf(c) >= 0;
    }

    /** A parsed list marker: type, start number, base indent and content column. */
    private static final class Marker {
        final boolean ordered;
        final int startNum;
        final int indent;
        final int contentStart;

        Marker(boolean ordered, int startNum, int indent, int contentStart) {
            this.ordered = ordered;
            this.startNum = startNum;
            this.indent = indent;
            this.contentStart = contentStart;
        }
    }
}
