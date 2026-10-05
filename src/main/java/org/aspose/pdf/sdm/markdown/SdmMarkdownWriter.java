package org.aspose.pdf.sdm.markdown;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.FootnoteRef;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.InlineImage;
import org.aspose.pdf.sdm.LinkInline;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.SdmMetadata;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Structural (semantic) SDM &rarr; Markdown writer — the Markdown sibling of
 * {@code SdmHtmlWriter}.
 *
 * <p>Maps the Semantic Document Model to CommonMark / GitHub-Flavored Markdown:
 * Heading&rarr;{@code #}&hellip;{@code ######}, Paragraph&rarr;text block,
 * ListBlock&rarr;{@code -}/{@code 1.} items (nested by indentation),
 * Table&rarr;GFM pipe table with per-column alignment, Figure/InlineImage&rarr;
 * {@code ![alt](src)}, Quote&rarr;{@code >} blocks, CodeBlock&rarr;fenced
 * {@code ```} blocks, ThematicBreak&rarr;{@code ---}, LinkInline&rarr;
 * {@code [text](href)}, Footnote/FootnoteRef&rarr;GFM {@code [^id]} footnotes,
 * Run&rarr;text with {@code **bold**}/{@code *italic*}/{@code ~~strike~~}.
 * Constructs Markdown has no equivalent for (font colour/size, spans, opaque
 * vector content) degrade to plain text or an HTML-comment placeholder — never
 * silently lost.</p>
 *
 * <p><b>Images.</b> With a {@link #baseDir} set and inline embedding off, image
 * resources are written as external files under {@code baseDir/resourcesDir} and
 * referenced by relative path; otherwise they are embedded as {@code data:}
 * URIs. The writer never mutates the model; output is deterministic.</p>
 */
public final class SdmMarkdownWriter {

    private static final Logger LOG = Logger.getLogger(SdmMarkdownWriter.class.getName());

    private final Path baseDir;
    private final String resourcesDir;
    private final boolean embedImages;

    private SdmDocument doc;
    private int imageCounter;
    /** Collected GFM footnote definitions, appended at the end of the document. */
    private final StringBuilder footnoteDefs = new StringBuilder();

    /**
     * Creates a writer that embeds every image as a {@code data:} URI (no
     * external files). Suitable for stream output.
     */
    public SdmMarkdownWriter() {
        this(null, "resources", true);
    }

    /**
     * Creates a writer.
     *
     * @param baseDir      directory the {@code .md} is written into; external
     *                     images are placed under {@code baseDir/resourcesDir}.
     *                     {@code null} forces inline {@code data:} URIs
     * @param resourcesDir sub-directory name for external images
     * @param embedImages  {@code true} to embed images as {@code data:} URIs even
     *                     when {@code baseDir} is set
     */
    public SdmMarkdownWriter(Path baseDir, String resourcesDir, boolean embedImages) {
        this.baseDir = baseDir;
        this.resourcesDir = (resourcesDir == null || resourcesDir.isEmpty()) ? "resources" : resourcesDir;
        this.embedImages = embedImages || baseDir == null;
    }

    /**
     * Serializes the given SDM document to a Markdown string.
     *
     * @param document the SDM document; must not be null
     * @return the Markdown text (UTF-8-safe, non-ASCII kept literal)
     * @throws IOException if an external image file cannot be written
     */
    public String write(SdmDocument document) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        this.doc = document;
        this.imageCounter = 0;
        this.footnoteDefs.setLength(0);

        StringBuilder out = new StringBuilder(4096);
        // A YAML front-matter block carries title/author when present — widely
        // understood by static-site generators and round-tripped by the reader.
        emitFrontMatter(out, document.getMetadata());
        for (SdmBlock block : document.getChildren()) {
            emitBlock(out, block, "");
        }
        if (footnoteDefs.length() > 0) {
            ensureBlankLine(out);
            out.append(footnoteDefs);
        }
        // Collapse 3+ blank lines that block joins can produce into a single one.
        final String md = out.toString().replaceAll("\n{3,}", "\n\n");
        LOG.fine(() -> "SdmMarkdownWriter: emitted " + md.length() + " chars");
        return md;
    }

    private void emitFrontMatter(StringBuilder out, SdmMetadata meta) {
        if (meta == null) {
            return;
        }
        String title = meta.getTitle();
        String author = meta.getAuthor();
        boolean has = (title != null && !title.isEmpty()) || (author != null && !author.isEmpty());
        if (!has) {
            return;
        }
        out.append("---\n");
        if (title != null && !title.isEmpty()) {
            out.append("title: ").append(yamlScalar(title)).append('\n');
        }
        if (author != null && !author.isEmpty()) {
            out.append("author: ").append(yamlScalar(author)).append('\n');
        }
        out.append("---\n\n");
    }

    private static String yamlScalar(String s) {
        // Quote when the value could be misread as YAML structure.
        if (s.indexOf(':') >= 0 || s.indexOf('#') >= 0 || s.startsWith(" ") || s.endsWith(" ")
                || s.indexOf('"') >= 0) {
            return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
        return s;
    }

    // ------------------------------------------------------------------
    // Block-level emission
    // ------------------------------------------------------------------

    private void emitBlock(StringBuilder out, SdmBlock block, String indent) throws IOException {
        if (block == null) {
            return;
        }
        switch (block.getType()) {
            case HEADING: {
                Heading h = (Heading) block;
                ensureBlankLine(out);
                int level = Math.max(1, Math.min(6, h.getLevel()));
                for (int i = 0; i < level; i++) {
                    out.append('#');
                }
                out.append(' ').append(inlineToText(h.getInline()).trim()).append('\n');
                break;
            }
            case PARAGRAPH: {
                Paragraph p = (Paragraph) block;
                String text = inlineToText(p.getInline());
                if (text.trim().isEmpty()) {
                    break;
                }
                ensureBlankLine(out);
                out.append(text.trim()).append('\n');
                break;
            }
            case LIST_BLOCK:
                ensureBlankLine(out);
                emitList(out, (ListBlock) block, indent);
                break;
            case TABLE:
                ensureBlankLine(out);
                emitTable(out, (Table) block);
                break;
            case FIGURE:
                emitFigure(out, (Figure) block);
                break;
            case QUOTE:
                ensureBlankLine(out);
                emitQuote(out, (Quote) block);
                break;
            case CODE_BLOCK: {
                CodeBlock c = (CodeBlock) block;
                ensureBlankLine(out);
                String lang = c.getLanguage() == null ? "" : c.getLanguage();
                String fence = chooseFence(c.getText());
                out.append(fence).append(lang).append('\n');
                out.append(c.getText() == null ? "" : c.getText());
                if (c.getText() != null && !c.getText().endsWith("\n")) {
                    out.append('\n');
                }
                out.append(fence).append('\n');
                break;
            }
            case THEMATIC_BREAK:
                ensureBlankLine(out);
                out.append("---\n");
                break;
            case FOOTNOTE:
                collectFootnote((Footnote) block);
                break;
            case CONTAINER:
                for (SdmBlock child : ((Container) block).getChildren()) {
                    emitBlock(out, child, indent);
                }
                break;
            case OPAQUE:
                // Vector fills / annotations / shadings carry no text. In a
                // text-first format they are dropped silently (logged at FINE)
                // rather than littering the output with invisible comments —
                // enable rasterizeVectorGraphics to keep charts as images instead.
                LOG.fine(() -> "SdmMarkdownWriter: dropped opaque " + ((Opaque) block).getRenderHint());
                break;
            default:
                LOG.fine(() -> "SdmMarkdownWriter: unmapped block type " + block.getType());
        }
    }

    private void emitList(StringBuilder out, ListBlock list, String indent) throws IOException {
        int number = list.getStart() != null ? list.getStart() : 1;
        for (ListItem item : list.getItems()) {
            String marker = list.isOrdered() ? (number++ + ". ") : "- ";
            // Continuation indent aligns wrapped content / nested blocks under the
            // marker text (marker width spaces).
            String childIndent = indent + spaces(marker.length());
            List<SdmBlock> children = item.getChildren();
            boolean first = true;
            for (SdmBlock child : children) {
                if (child instanceof Paragraph && first) {
                    String text = inlineToText(((Paragraph) child).getInline()).trim();
                    out.append(indent).append(marker).append(text).append('\n');
                    first = false;
                } else if (child instanceof ListBlock) {
                    // Nested list directly under this item.
                    emitList(out, (ListBlock) child, childIndent);
                    first = false;
                } else if (child instanceof Paragraph) {
                    out.append(childIndent)
                       .append(inlineToText(((Paragraph) child).getInline()).trim()).append('\n');
                } else {
                    // Rare: table / code / quote inside a list item — emit at the
                    // child indent through a temporary buffer.
                    StringBuilder tmp = new StringBuilder();
                    emitBlock(tmp, child, childIndent);
                    for (String line : tmp.toString().split("\n", -1)) {
                        if (line.isEmpty()) {
                            out.append('\n');
                        } else {
                            out.append(childIndent).append(line).append('\n');
                        }
                    }
                    first = false;
                }
            }
            if (children.isEmpty()) {
                out.append(indent).append(marker).append('\n');
            }
        }
    }

    private void emitQuote(StringBuilder out, Quote quote) throws IOException {
        StringBuilder inner = new StringBuilder();
        for (SdmBlock child : quote.getChildren()) {
            emitBlock(inner, child, "");
        }
        for (String line : inner.toString().split("\n", -1)) {
            if (line.isEmpty()) {
                out.append(">\n");
            } else {
                out.append("> ").append(line).append('\n');
            }
        }
    }

    private void emitTable(StringBuilder out, Table table) throws IOException {
        List<TableRow> rows = table.getRows();
        if (rows == null || rows.isEmpty()) {
            return;
        }
        int cols = 0;
        for (TableRow r : rows) {
            cols = Math.max(cols, spannedWidth(r));
        }
        if (cols == 0) {
            return;
        }
        // GFM requires exactly one header row. Use the first HEADER row, or the
        // first row as a fallback header (a table with no header still renders).
        int headerIdx = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getKind() == TableRow.Kind.HEADER) {
                headerIdx = i;
                break;
            }
        }
        boolean syntheticHeader = headerIdx < 0;
        if (syntheticHeader) {
            headerIdx = 0;
        }

        if (table.getCaption() != null && !table.getCaption().isEmpty()) {
            out.append('*').append(escapeInline(table.getCaption())).append("*\n\n");
        }

        emitTableRow(out, rows.get(headerIdx), cols);
        // Delimiter row with per-column alignment from the ColumnSpec.
        out.append('|');
        List<ColumnSpec> specs = table.getColumns();
        for (int c = 0; c < cols; c++) {
            ColumnSpec.Align a = (specs != null && c < specs.size()) ? specs.get(c).getDefaultAlign() : null;
            out.append(delimiterFor(a)).append('|');
        }
        out.append('\n');
        for (int i = 0; i < rows.size(); i++) {
            if (i == headerIdx && !syntheticHeader) {
                continue;
            }
            if (i == headerIdx && syntheticHeader) {
                continue; // already emitted as the header
            }
            emitTableRow(out, rows.get(i), cols);
        }
    }

    private void emitTableRow(StringBuilder out, TableRow row, int cols) throws IOException {
        String[] texts = new String[cols];
        int col = 0;
        for (TableCell cell : row.getCells()) {
            if (col >= cols) {
                break;
            }
            texts[col] = cellText(cell);
            // A colSpan repeats the content-bearing cell then pads (GFM has no
            // spanning); leave the spanned columns blank so the grid stays aligned.
            col += Math.max(1, cell.getColSpan());
        }
        out.append('|');
        for (int c = 0; c < cols; c++) {
            out.append(' ').append(texts[c] == null ? "" : texts[c]).append(" |");
        }
        out.append('\n');
    }

    private String cellText(TableCell cell) throws IOException {
        StringBuilder sb = new StringBuilder();
        List<SdmBlock> children = cell.getChildren();
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            if (b instanceof Paragraph) {
                if (sb.length() > 0) {
                    sb.append("<br>");
                }
                sb.append(inlineToText(((Paragraph) b).getInline()).trim());
            } else {
                // Non-paragraph content in a cell is rare; render its text flatly.
                StringBuilder tmp = new StringBuilder();
                emitBlock(tmp, b, "");
                if (sb.length() > 0) {
                    sb.append("<br>");
                }
                sb.append(tmp.toString().replace("\n", " ").trim());
            }
        }
        // Pipes must be escaped inside a cell; newlines become <br>.
        return sb.toString().replace("|", "\\|");
    }

    /** Minimum on-page footprint (points) for a figure to be worth emitting. */
    private static final double MIN_FIGURE_PT = 3.0;

    private void emitFigure(StringBuilder out, Figure fig) throws IOException {
        // Drop sub-visible vector fragments the shallow reader over-projects to
        // their own Figure (a form page can yield tens of thousands of 0.5pt
        // "images"). Mirrors SdmHtmlWriter.isDegenerateFigure.
        if (isDegenerateFigure(fig)) {
            return;
        }
        String src = resolveImageSrc(fig.getImage());
        if (src == null) {
            return;
        }
        ensureBlankLine(out);
        String alt = fig.getAlt() == null ? "" : fig.getAlt();
        out.append("![").append(escapeInline(alt)).append("](").append(src).append(")\n");
        if (fig.getCaption() != null && !fig.getCaption().isEmpty()) {
            StringBuilder cap = new StringBuilder();
            for (SdmBlock b : fig.getCaption()) {
                if (b instanceof Paragraph) {
                    cap.append(inlineToText(((Paragraph) b).getInline()));
                }
            }
            String c = cap.toString().trim();
            if (!c.isEmpty()) {
                out.append('*').append(c).append("*\n");
            }
        }
    }

    /**
     * True when a figure's recorded footprint is below the visibility floor in
     * either dimension and it carries no caption/alt — a sub-pixel vector
     * fragment the reader over-projected, worth dropping.
     */
    private boolean isDegenerateFigure(Figure fig) {
        Object w = fig.getAttributes().get("display-width");
        Object h = fig.getAttributes().get("display-height");
        if (!(w instanceof Number) || !(h instanceof Number)) {
            return false; // unknown footprint — keep, don't guess
        }
        double wp = ((Number) w).doubleValue();
        double hp = ((Number) h).doubleValue();
        if (wp >= MIN_FIGURE_PT && hp >= MIN_FIGURE_PT) {
            return false;
        }
        boolean hasCaption = fig.getCaption() != null && !fig.getCaption().isEmpty();
        boolean hasAlt = fig.getAlt() != null && !fig.getAlt().isEmpty();
        return !hasCaption && !hasAlt;
    }

    private void collectFootnote(Footnote fn) throws IOException {
        String id = fn.getRefId() == null ? "" : fn.getRefId();
        StringBuilder body = new StringBuilder();
        for (SdmBlock b : fn.getChildren()) {
            if (b instanceof Paragraph) {
                if (body.length() > 0) {
                    body.append(' ');
                }
                body.append(inlineToText(((Paragraph) b).getInline()).trim());
            }
        }
        footnoteDefs.append("[^").append(id).append("]: ").append(body).append('\n');
    }

    // ------------------------------------------------------------------
    // Inline-level emission
    // ------------------------------------------------------------------

    private String inlineToText(List<SdmInline> inlines) throws IOException {
        if (inlines == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (SdmInline inline : inlines) {
            emitInline(sb, inline);
        }
        return sb.toString();
    }

    private void emitInline(StringBuilder sb, SdmInline inline) throws IOException {
        if (inline == null) {
            return;
        }
        switch (inline.getType()) {
            case RUN:
                emitRun(sb, (Run) inline);
                break;
            case LINK_INLINE: {
                LinkInline link = (LinkInline) inline;
                String inner = inlineToText(link.getChildren());
                String href = link.getHref();
                if (href == null || href.isEmpty()) {
                    href = link.getInternalAnchor() != null ? "#" + link.getInternalAnchor() : "";
                }
                if (inner.trim().isEmpty()) {
                    inner = href;
                }
                sb.append('[').append(inner.trim()).append("](").append(href).append(')');
                break;
            }
            case INLINE_IMAGE: {
                InlineImage img = (InlineImage) inline;
                String src = resolveImageSrc(img.getImage());
                if (src != null) {
                    String alt = img.getAlt() == null ? "" : img.getAlt();
                    sb.append("![").append(escapeInline(alt)).append("](").append(src).append(')');
                }
                break;
            }
            case LINE_BREAK:
                // Hard line break: two trailing spaces before the newline.
                sb.append("  \n");
                break;
            case FOOTNOTE_REF: {
                FootnoteRef ref = (FootnoteRef) inline;
                sb.append("[^").append(ref.getRefId() == null ? "" : ref.getRefId()).append(']');
                break;
            }
            case INLINE_OPAQUE:
                break;
            default:
                LOG.fine(() -> "SdmMarkdownWriter: unmapped inline type " + inline.getType());
        }
    }

    private void emitRun(StringBuilder sb, Run run) {
        String text = run.getText();
        if (text == null || text.isEmpty()) {
            return;
        }
        TextStyle st = run.getStyle();
        boolean bold = st != null && st.isBold();
        boolean italic = st != null && st.isItalic();
        boolean strike = st != null && st.isStrikethrough();
        // Emphasis markers cannot wrap surrounding whitespace, so split the run
        // into leading space + core + trailing space and wrap only the core.
        int start = 0;
        int end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        sb.append(text, 0, start);
        if (start < end) {
            String core = escapeInline(text.substring(start, end));
            String open = "";
            String close = "";
            if (strike) {
                open += "~~";
                close = "~~" + close;
            }
            if (bold) {
                open += "**";
                close = "**" + close;
            }
            if (italic) {
                open += "*";
                close = "*" + close;
            }
            sb.append(open).append(core).append(close);
        }
        sb.append(text, end, text.length());
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    private String resolveImageSrc(ResourceRef ref) throws IOException {
        if (ref == null || doc == null) {
            return null;
        }
        Resource res = doc.getResources().get(ref);
        if (res == null || res.getBytes() == null) {
            return null;
        }
        String mime = res.getMime() == null || res.getMime().isEmpty() ? "image/png" : res.getMime();
        if (!embedImages && baseDir != null) {
            Path dir = baseDir.resolve(resourcesDir);
            Files.createDirectories(dir);
            String name = "img" + (++imageCounter) + extensionFor(mime);
            Files.write(dir.resolve(name), res.getBytes());
            return resourcesDir + "/" + name;
        }
        return org.aspose.pdf.html.HtmlImageEncoder.dataUri(res.getBytes(), mime, 0, 0);
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/jpeg": return ".jpg";
            case "image/gif":  return ".gif";
            case "image/bmp":  return ".bmp";
            case "image/tiff": return ".tif";
            default:           return ".png";
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** The number of grid columns a row occupies, honouring colSpans. */
    private static int spannedWidth(TableRow row) {
        int w = 0;
        for (TableCell c : row.getCells()) {
            w += Math.max(1, c.getColSpan());
        }
        return w;
    }

    private static String delimiterFor(ColumnSpec.Align a) {
        if (a == null) {
            return " --- ";
        }
        switch (a) {
            case CENTER: return " :---: ";
            case RIGHT:  return " ---: ";
            case LEFT:   return " :--- ";
            default:     return " --- ";
        }
    }

    /** Picks a fence long enough to not clash with backtick runs in the code. */
    private static String chooseFence(String code) {
        int longest = 0;
        if (code != null) {
            int run = 0;
            for (int i = 0; i < code.length(); i++) {
                if (code.charAt(i) == '`') {
                    run++;
                    longest = Math.max(longest, run);
                } else {
                    run = 0;
                }
            }
        }
        int n = Math.max(3, longest + 1);
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < n; i++) {
            f.append('`');
        }
        return f.toString();
    }

    /** Ensures the buffer ends with a blank line before the next block. */
    private static void ensureBlankLine(StringBuilder out) {
        if (out.length() == 0) {
            return;
        }
        if (out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        if (out.length() >= 2 && out.charAt(out.length() - 2) != '\n') {
            out.append('\n');
        }
    }

    private static String spaces(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Backslash-escapes the characters that would otherwise trigger Markdown
     * formatting in running text. Kept deliberately small — over-escaping makes
     * the output unreadable; these are the markers the writer itself emits.
     */
    static String escapeInline(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean special = c == '\\' || c == '`' || c == '*' || c == '_'
                    || c == '[' || c == ']' || c == '<' || c == '>';
            if (special) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 8).append(s, 0, i);
                }
                sb.append('\\').append(c);
            } else if (c < 0x20 && c != '\t') {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 8).append(s, 0, i);
                }
                sb.append(' ');
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }
}
