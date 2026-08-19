package org.aspose.pdf.sdm.html;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.FootnoteRef;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.InlineImage;
import org.aspose.pdf.sdm.InlineOpaque;
import org.aspose.pdf.sdm.LineBreak;
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
import org.aspose.pdf.sdm.ThematicBreak;
import org.aspose.pdf.sdm.TocBlock;
import org.aspose.pdf.sdm.TocEntry;

/**
 * Structural (semantic) SDM &rarr; HTML5 writer — IR Stage 3 PART 1.
 *
 * <p>Maps the Semantic Document Model to semantic HTML5 elements:
 * Heading&rarr;{@code h1..h6}, Paragraph&rarr;{@code p}, ListBlock&rarr;{@code ul/ol},
 * Table&rarr;{@code table/colgroup/thead/tbody/tfoot} with {@code rowspan/colspan},
 * Figure&rarr;{@code figure/img/figcaption}, Quote&rarr;{@code blockquote},
 * CodeBlock&rarr;{@code pre/code}, ThematicBreak&rarr;{@code hr},
 * TocBlock&rarr;{@code nav} with nested link lists, Footnote/FootnoteRef&rarr;
 * {@code sup} + id links, LinkInline&rarr;{@code a}, Run&rarr;text with minimal inline
 * markup ({@code b/i/u/s/sup/sub}, or a classed {@code span} only when the TextStyle
 * demands font/size/colour). Opaque content degrades to a placeholder {@code div}
 * carrying a {@code data-source} attribute — degraded, never silently lost.</p>
 *
 * <p><b>CSS strategy:</b> distinct computed styles are collected into a small class set
 * emitted once inside {@code <head><style>} (no per-element inline style spam).
 * {@link BlockStyle} maps to text-align/margins/text-indent/line-height;
 * {@link TextStyle} font family/size/colour/background map to a span class.</p>
 *
 * <p><b>Images:</b> {@link org.aspose.pdf.sdm.ResourceTable} entries referenced by
 * Figure/InlineImage nodes are embedded as {@code data:} URIs by default, or written
 * as external files ({@link HtmlWriterOptions.ImageMode#EXTERNAL}).</p>
 *
 * <p>The writer never mutates the model. Output is deterministic for a given
 * (document, options) pair.</p>
 */
public final class SdmHtmlWriter {

    private static final Logger LOG = Logger.getLogger(SdmHtmlWriter.class.getName());

    private final HtmlWriterOptions options;
    /** css declaration block -> class name, in first-seen order. */
    private final Map<String, String> cssClasses = new LinkedHashMap<>();
    /** Set when a ruled (bordered) table was emitted, so the head adds border CSS. */
    private boolean usedRuledTable;
    /** node ids referenced as link targets (TOC entries) — these nodes get an id attribute. */
    private final Set<String> referencedIds = new HashSet<>();
    private SdmDocument doc;
    private int externalImageCounter;

    /**
     * Creates a writer with default options (data-URI images).
     */
    public SdmHtmlWriter() {
        this(null);
    }

    /**
     * Creates a writer with the given options.
     *
     * @param options writer options; null means defaults
     */
    public SdmHtmlWriter(HtmlWriterOptions options) {
        this.options = options == null ? new HtmlWriterOptions() : options;
    }

    /**
     * Serializes the given SDM document to a complete HTML5 document string.
     *
     * @param document the SDM document to serialize; must not be null
     * @return the HTML text (UTF-8-safe, all non-ASCII characters kept literal)
     * @throws IOException if external image files cannot be written
     *                     (only in {@link HtmlWriterOptions.ImageMode#EXTERNAL})
     */
    public String write(SdmDocument document) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        this.doc = document;
        this.cssClasses.clear();
        this.referencedIds.clear();
        this.externalImageCounter = 0;
        this.usedRuledTable = false;
        collectReferencedIds(document.getChildren());

        StringBuilder body = new StringBuilder(4096);
        for (SdmBlock block : document.getChildren()) {
            emitBlock(body, block);
        }

        StringBuilder out = new StringBuilder(body.length() + 512);
        out.append("<!DOCTYPE html>\n");
        SdmMetadata meta = document.getMetadata();
        String lang = meta == null ? null : meta.getLang();
        out.append(lang == null || lang.isEmpty() ? "<html>" : "<html lang=\"" + escapeAttr(lang) + "\">").append('\n');
        // Void elements are self-closed throughout: the output is polyglot
        // (valid HTML5 AND well-formed XML), so the zero-dep HtmlTagParser
        // takes its strict XML path with no lossy cleanup heuristics.
        out.append("<head>\n<meta charset=\"utf-8\"/>\n");
        String title = meta == null ? null : meta.getTitle();
        out.append("<title>").append(escapeText(title == null ? "" : title)).append("</title>\n");
        if (meta != null && meta.getAuthor() != null && !meta.getAuthor().isEmpty()) {
            out.append("<meta name=\"author\" content=\"").append(escapeAttr(meta.getAuthor())).append("\"/>\n");
        }
        if (meta != null && meta.getCreated() != null && !meta.getCreated().isEmpty()) {
            out.append("<meta name=\"dcterms.created\" content=\"").append(escapeAttr(meta.getCreated())).append("\"/>\n");
        }
        if (meta != null && meta.getModified() != null && !meta.getModified().isEmpty()) {
            out.append("<meta name=\"dcterms.modified\" content=\"").append(escapeAttr(meta.getModified())).append("\"/>\n");
        }
        if (meta != null) {
            String pw = meta.getCustom().get("page-width");
            String ph = meta.getCustom().get("page-height");
            if (pw != null && ph != null) {
                out.append("<meta name=\"page-size\" content=\"").append(escapeAttr(pw)).append(' ')
                   .append(escapeAttr(ph)).append("\"/>\n");
            }
        }
        String bodyPadding = bodyPaddingRule(meta);
        if (!cssClasses.isEmpty() || usedRuledTable || bodyPadding != null) {
            out.append("<style>\n");
            if (bodyPadding != null) {
                // Reinstate the source page's text margins as body PADDING (padding
                // can't margin-collapse and survives contexts that drop inline body
                // margins), so the reflow is inset from the edges like the original.
                out.append("html{margin:0}\n");
                out.append("body{margin:0;").append(bodyPadding).append("}\n");
            }
            for (Map.Entry<String, String> e : cssClasses.entrySet()) {
                out.append('.').append(e.getValue()).append('{').append(e.getKey()).append("}\n");
            }
            if (usedRuledTable) {
                // Ruled tables carried visible cell borders in the source PDF —
                // reinstate them so the reflowed grid reads as a table, not columns.
                out.append("table.ruled{border-collapse:collapse}\n");
                out.append("table.ruled td,table.ruled th{border:1px solid var(--rule-color,#000);"
                        + "padding:1px 5px;vertical-align:top}\n");
            }
            out.append("</style>\n");
        }
        // Also inline the inset on <body>: highest CSS priority, and honoured even
        // by simple viewers that ignore a <style> block (belt-and-suspenders with
        // the body{} rule above).
        out.append("</head>\n");
        if (bodyPadding != null) {
            out.append("<body style=\"margin:0;").append(bodyPadding).append("\">\n");
        } else {
            out.append("<body>\n");
        }
        out.append(body);
        out.append("</body>\n</html>\n");
        LOG.fine(() -> "SdmHtmlWriter: emitted " + out.length() + " chars, " + cssClasses.size() + " css classes");
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Block-level emission
    // ------------------------------------------------------------------

    private void emitBlock(StringBuilder sb, SdmBlock block) throws IOException {
        if (block == null) {
            return;
        }
        switch (block.getType()) {
            case HEADING: {
                Heading h = (Heading) block;
                String tag = "h" + h.getLevel();
                openTag(sb, tag, block);
                emitInlines(sb, h.getInline());
                sb.append("</").append(tag).append(">\n");
                break;
            }
            case PARAGRAPH: {
                Paragraph p = (Paragraph) block;
                openTag(sb, "p", block);
                emitInlines(sb, p.getInline());
                sb.append("</p>\n");
                break;
            }
            case LIST_BLOCK:
                emitList(sb, (ListBlock) block);
                break;
            case TABLE:
                emitTable(sb, (Table) block);
                break;
            case FIGURE:
                emitFigure(sb, (Figure) block);
                break;
            case FORM_FIELD:
                emitFormField(sb, (org.aspose.pdf.sdm.FormField) block);
                break;
            case QUOTE: {
                Quote q = (Quote) block;
                openTag(sb, "blockquote", block);
                sb.append('\n');
                for (SdmBlock child : q.getChildren()) {
                    emitBlock(sb, child);
                }
                sb.append("</blockquote>\n");
                break;
            }
            case CODE_BLOCK: {
                CodeBlock c = (CodeBlock) block;
                openTag(sb, "pre", block);
                if (c.getLanguage() == null || c.getLanguage().isEmpty()) {
                    sb.append("<code>");
                } else {
                    sb.append("<code class=\"language-").append(escapeAttr(c.getLanguage())).append("\">");
                }
                sb.append(escapeText(c.getText() == null ? "" : c.getText()));
                sb.append("</code></pre>\n");
                break;
            }
            case THEMATIC_BREAK:
                sb.append("<hr/>\n");
                break;
            case TOC_BLOCK:
                emitToc(sb, (TocBlock) block);
                break;
            case FOOTNOTE:
                emitFootnote(sb, (Footnote) block);
                break;
            case CONTAINER: {
                Container c = (Container) block;
                sb.append("<div");
                emitIdAndClass(sb, block);
                if (c.getRole() != null && !c.getRole().isEmpty()) {
                    sb.append(" data-role=\"").append(escapeAttr(c.getRole())).append('"');
                }
                sb.append(">\n");
                for (SdmBlock child : c.getChildren()) {
                    emitBlock(sb, child);
                }
                sb.append("</div>\n");
                break;
            }
            case OPAQUE: {
                Opaque o = (Opaque) block;
                // Degrade, don't lose: placeholder carries provenance for round-trip diagnostics.
                sb.append("<div class=\"opaque\"");
                if (o.getSourceRef() != null) {
                    sb.append(" data-source=\"").append(escapeAttr(o.getSourceRef().canonical())).append('"');
                }
                if (o.getRenderHint() != null && !o.getRenderHint().isEmpty()) {
                    sb.append(" data-render-hint=\"").append(escapeAttr(o.getRenderHint())).append('"');
                }
                sb.append("></div>\n");
                break;
            }
            default:
                LOG.fine(() -> "SdmHtmlWriter: unmapped block type " + block.getType() + " emitted as div");
                sb.append("<div data-sdm-type=\"").append(block.getType().name()).append("\"></div>\n");
        }
    }

    private void emitList(StringBuilder sb, ListBlock list) throws IOException {
        String tag = list.isOrdered() ? "ol" : "ul";
        sb.append('<').append(tag);
        emitIdAndClass(sb, list);
        if (list.isOrdered() && list.getStart() != null && list.getStart() != 1) {
            sb.append(" start=\"").append(list.getStart()).append('"');
        }
        sb.append(">\n");
        for (ListItem item : list.getItems()) {
            sb.append("<li>");
            emitBlocksCompact(sb, item.getChildren());
            sb.append("</li>\n");
        }
        sb.append("</").append(tag).append(">\n");
    }

    private void emitTable(StringBuilder sb, Table table) throws IOException {
        boolean ruled = "ruled".equals(table.getAttributes().get("border"));
        sb.append("<table");
        if (table.getId() != null && referencedIds.contains(table.getId())) {
            sb.append(" id=\"").append(escapeAttr(table.getId())).append('"');
        }
        String styleClass = table.getStyle() != null ? blockStyleClass(table.getStyle()) : null;
        String cls = ruled ? (styleClass == null ? "ruled" : styleClass + " ruled") : styleClass;
        if (cls != null) {
            sb.append(" class=\"").append(cls).append('"');
        }
        if (ruled) {
            // Per-table rule colour flows to the cell-border rule via a CSS var.
            Object bc = table.getAttributes().get("border-color");
            if (bc instanceof String && isCssColor((String) bc)) {
                sb.append(" style=\"--rule-color:").append((String) bc).append('"');
            }
        }
        sb.append('>');
        if (ruled) {
            usedRuledTable = true;
        }
        sb.append('\n');
        if (table.getCaption() != null && !table.getCaption().isEmpty()) {
            sb.append("<caption>").append(escapeText(table.getCaption())).append("</caption>\n");
        }
        List<ColumnSpec> cols = table.getColumns();
        if (cols != null && !cols.isEmpty()) {
            sb.append("<colgroup>\n");
            for (ColumnSpec col : cols) {
                sb.append("<col");
                if (col.getWidthType() == ColumnSpec.WidthType.POINTS) {
                    sb.append(" class=\"").append(cssClass("width:" + fmt(col.getWidth()) + "pt")).append('"');
                } else if (col.getWidthType() == ColumnSpec.WidthType.PERCENT) {
                    sb.append(" class=\"").append(cssClass("width:" + fmt(col.getWidth()) + "%")).append('"');
                }
                sb.append("/>\n");
            }
            sb.append("</colgroup>\n");
        }
        StringBuilder thead = new StringBuilder();
        StringBuilder tbody = new StringBuilder();
        StringBuilder tfoot = new StringBuilder();
        for (TableRow row : table.getRows()) {
            StringBuilder target;
            switch (row.getKind()) {
                case HEADER: target = thead; break;
                case FOOTER: target = tfoot; break;
                default: target = tbody;
            }
            target.append("<tr>");
            for (TableCell cell : row.getCells()) {
                String cellTag = cell.getKind() == TableCell.Kind.TH ? "th" : "td";
                target.append('<').append(cellTag);
                if (cell.getRowSpan() > 1) {
                    target.append(" rowspan=\"").append(cell.getRowSpan()).append('"');
                }
                if (cell.getColSpan() > 1) {
                    target.append(" colspan=\"").append(cell.getColSpan()).append('"');
                }
                target.append('>');
                emitBlocksCompact(target, cell.getChildren());
                // Newline after each cell so text extraction of the HTML does not
                // glue adjacent cells' text into one token ("TORINODossier"): a
                // block-boundary must carry whitespace (Link-2 writer fidelity).
                target.append("</").append(cellTag).append(">\n");
            }
            target.append("</tr>\n");
        }
        if (thead.length() > 0) {
            sb.append("<thead>\n").append(thead).append("</thead>\n");
        }
        if (tbody.length() > 0) {
            sb.append("<tbody>\n").append(tbody).append("</tbody>\n");
        }
        if (tfoot.length() > 0) {
            sb.append("<tfoot>\n").append(tfoot).append("</tfoot>\n");
        }
        sb.append("</table>\n");
    }

    private void emitFigure(StringBuilder sb, Figure fig) throws IOException {
        // Drop sub-visible fragments. The shallow Stage-1 reader projects every
        // tiny vector fill / clip / rule fragment to its own Figure; a form page
        // can yield tens of thousands of e.g. 0.5pt x 0.2pt "images". They carry
        // no visible content and no text, but each becomes a layout block that
        // balloons the page count and the HTML size on the round-trip. Skip any
        // figure whose recorded on-page footprint is below a visibility floor and
        // that carries no caption/alt text of its own.
        if (isDegenerateFigure(fig)) {
            return;
        }
        // A near-full-page watermark / page frame is rendered as an out-of-flow
        // backdrop: it sits BEHIND the text (z-index:-1) at its PDF size and
        // reduced opacity, and consumes no vertical space — so the semantic flow
        // is not pushed down by a whole blank page.
        if (Boolean.TRUE.equals(fig.getAttributes().get("background"))) {
            String bg = resolveImageSrc(fig.getImage(),
                    numAttr(fig, "display-width"), numAttr(fig, "display-height"));
            if (bg != null) {
                sb.append("<img class=\"bg\" src=\"").append(bg).append('"');
                // opacity 0.45: the source watermark image is already a mid-grey
                // diagonal; at 0.18 it washed out to invisible against white in a
                // browser. z-index:-1 keeps it behind the text (which has no opaque
                // background), so the text stays legible over the faded backdrop.
                appendImageSize(sb, fig, "position:absolute;left:50%;transform:translateX(-50%);"
                        + "z-index:-1;opacity:0.45;pointer-events:none;");
                sb.append("/>\n");
            }
            return;
        }
        // A caption-less image figure is emitted inline-block so that a run of
        // small images placed side-by-side in the PDF (e.g. a row of GHS hazard
        // pictograms — one <figure> each) flows back into a horizontal row and
        // wraps, instead of every image stacking on its own line.
        boolean imageOnly = fig.getCaption() == null || fig.getCaption().isEmpty();
        sb.append("<figure");
        emitIdAndClass(sb, fig);
        if (imageOnly) {
            sb.append(" style=\"display:inline-block;vertical-align:top;margin:0 3pt 0 0\"");
        }
        sb.append(">\n");
        String src = resolveImageSrc(fig.getImage(),
                numAttr(fig, "display-width"), numAttr(fig, "display-height"));
        if (src != null) {
            sb.append("<img src=\"").append(src).append('"');
            if (fig.getAlt() != null && !fig.getAlt().isEmpty()) {
                sb.append(" alt=\"").append(escapeAttr(fig.getAlt())).append('"');
            }
            appendImageSize(sb, fig, null);
            sb.append("/>\n");
        } else if (fig.getImage() != null) {
            // Unresolvable resource — degrade with provenance, don't drop the node.
            sb.append("<img data-resource=\"").append(escapeAttr(fig.getImage().getId())).append("\" alt=\"")
              .append(escapeAttr(fig.getAlt() == null ? "" : fig.getAlt())).append("\"/>\n");
        }
        if (fig.getCaption() != null && !fig.getCaption().isEmpty()) {
            sb.append("<figcaption>");
            emitBlocksCompact(sb, fig.getCaption());
            sb.append("</figcaption>\n");
        }
        sb.append("</figure>\n");
    }

    /** Minimum on-page footprint (points) for a figure to be worth emitting. */
    private static final double MIN_FIGURE_PT = 3.0;

    /**
     * True when a figure's recorded footprint is below the visibility floor in
     * either dimension and it carries no caption/alt of its own. Such nodes are
     * sub-pixel vector fragments the reader over-projected; emitting them only
     * bloats the markup and explodes pagination.
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

    /**
     * Sizes the {@code <img>} to the figure's on-page PDF footprint (in points)
     * when the reader recorded it — so a figure keeps its document proportions
     * instead of ballooning to the image's intrinsic pixel resolution.
     */
    private void appendImageSize(StringBuilder sb, Figure fig, String extraCss) {
        Object w = fig.getAttributes().get("display-width");
        Object h = fig.getAttributes().get("display-height");
        String pre = extraCss == null ? "" : extraCss;
        if (w instanceof Number && h instanceof Number) {
            double wp = ((Number) w).doubleValue();
            double hp = ((Number) h).doubleValue();
            if (wp > 0 && hp > 0) {
                sb.append(" style=\"").append(pre).append("width:").append(fmt(Math.round(wp * 10) / 10.0))
                  .append("pt;height:").append(fmt(Math.round(hp * 10) / 10.0)).append("pt\"");
                return;
            }
        }
        if (!pre.isEmpty()) {
            sb.append(" style=\"").append(pre).append('"');
        }
    }

    /**
     * Emits a {@link org.aspose.pdf.sdm.FormField} as a real HTML control:
     * {@code <input>}, {@code <textarea>}, {@code <select>} or {@code <button>}.
     * The control carries {@code data-source} provenance and enough attributes
     * ({@code name}, {@code value}, {@code checked}, {@code maxlength},
     * {@code data-field-kind}) for {@link HtmlSdmReader} to rebuild the node
     * losslessly.
     */
    private void emitFormField(StringBuilder sb, org.aspose.pdf.sdm.FormField f) {
        String src = f.getSourceRef() != null ? f.getSourceRef().canonical() : null;
        double w = numAttr(f, "display-width");
        double h = numAttr(f, "display-height");
        switch (f.getKind()) {
            case TEXT:
            case SIGNATURE:
                if (f.isMultiline()) {
                    sb.append("<textarea class=\"ff\"");
                    commonFieldAttrs(sb, f, src);
                    if (f.isReadOnly()) {
                        sb.append(" readonly");
                    }
                    appendFieldSize(sb, w, h, true);
                    sb.append('>').append(escapeText(f.getValue() == null ? "" : f.getValue()))
                      .append("</textarea>\n");
                } else {
                    sb.append("<input class=\"ff\" type=\"text\"");
                    if (f.getKind() == org.aspose.pdf.sdm.FormField.Kind.SIGNATURE) {
                        sb.append(" data-field-kind=\"signature\"");
                    }
                    commonFieldAttrs(sb, f, src);
                    if (f.getValue() != null) {
                        sb.append(" value=\"").append(escapeAttr(f.getValue())).append('"');
                    }
                    if (f.getMaxLen() != null) {
                        sb.append(" maxlength=\"").append(f.getMaxLen()).append('"');
                    }
                    if (f.isReadOnly()) {
                        sb.append(" readonly");
                    }
                    appendFieldSize(sb, w, 0, false);
                    sb.append("/>\n");
                }
                break;
            case CHECKBOX:
            case RADIO:
                sb.append("<input class=\"ff\" type=\"")
                  .append(f.getKind() == org.aspose.pdf.sdm.FormField.Kind.RADIO ? "radio" : "checkbox")
                  .append('"');
                commonFieldAttrs(sb, f, src);
                if (f.getExportValue() != null) {
                    sb.append(" value=\"").append(escapeAttr(f.getExportValue())).append('"');
                }
                if (f.isChecked()) {
                    sb.append(" checked");
                }
                if (f.isReadOnly()) {
                    // readonly is not valid HTML on checkable inputs; carry the
                    // flag as data so the reader can round-trip it.
                    sb.append(" data-readonly=\"true\"");
                }
                sb.append("/>\n");
                break;
            case COMBOBOX:
            case LISTBOX:
                sb.append("<select class=\"ff\"");
                if (f.getKind() == org.aspose.pdf.sdm.FormField.Kind.LISTBOX) {
                    sb.append(" data-field-kind=\"listbox\" size=\"")
                      .append(Math.max(2, Math.min(8, f.getOptions().size()))).append('"');
                }
                commonFieldAttrs(sb, f, src);
                if (f.isReadOnly()) {
                    sb.append(" data-readonly=\"true\"");
                }
                appendFieldSize(sb, w, 0, false);
                sb.append(">\n");
                for (String opt : f.getOptions()) {
                    String o = opt == null ? "" : opt;
                    sb.append("<option");
                    if (f.getValue() != null && f.getValue().equals(o)) {
                        sb.append(" selected");
                    }
                    sb.append('>').append(escapeText(o)).append("</option>\n");
                }
                sb.append("</select>\n");
                break;
            case BUTTON:
                sb.append("<button class=\"ff\" type=\"button\"");
                commonFieldAttrs(sb, f, src);
                sb.append('>')
                  .append(escapeText(f.getValue() != null ? f.getValue()
                          : f.getName() == null ? "" : f.getName()))
                  .append("</button>\n");
                break;
            default:
                break;
        }
    }

    /** Shared name + provenance attributes for every control flavour. */
    private void commonFieldAttrs(StringBuilder sb, org.aspose.pdf.sdm.FormField f, String src) {
        if (f.getName() != null && !f.getName().isEmpty()) {
            sb.append(" name=\"").append(escapeAttr(f.getName())).append('"');
        }
        if (src != null) {
            sb.append(" data-source=\"").append(escapeAttr(src)).append('"');
        }
    }

    /** Sizes a control to its PDF footprint; height only for multi-line. */
    private void appendFieldSize(StringBuilder sb, double w, double h, boolean withHeight) {
        if (w <= 0) {
            return;
        }
        sb.append(" style=\"width:").append(fmt(Math.round(w * 10) / 10.0)).append("pt");
        if (withHeight && h > 0) {
            sb.append(";height:").append(fmt(Math.round(h * 10) / 10.0)).append("pt");
        }
        sb.append('"');
    }

    /** A numeric attribute value, or 0 when absent/not a number. */
    private static double numAttr(org.aspose.pdf.sdm.SdmNode node, String key) {
        Object v = node.getAttributes().get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    private void emitToc(StringBuilder sb, TocBlock toc) {
        sb.append("<nav class=\"toc\"");
        if (referencedIds.contains(toc.getId()) && toc.getId() != null) {
            sb.append(" id=\"").append(escapeAttr(toc.getId())).append('"');
        }
        sb.append(">\n");
        int depth = 0;
        boolean liOpen = false;
        for (TocEntry entry : toc.getEntries()) {
            int level = Math.max(1, entry.getLevel());
            while (depth < level) {
                sb.append("<ul>\n");
                depth++;
                liOpen = false;
            }
            while (depth > level) {
                if (liOpen) {
                    sb.append("</li>\n");
                }
                sb.append("</ul>\n");
                depth--;
                liOpen = true; // the parent <li> that contained the nested list is still open
            }
            if (liOpen) {
                sb.append("</li>\n");
            }
            sb.append("<li>");
            if (entry.getTargetId() != null && !entry.getTargetId().isEmpty()) {
                sb.append("<a href=\"#").append(escapeAttr(entry.getTargetId())).append("\">")
                  .append(escapeText(entry.getText() == null ? "" : entry.getText())).append("</a>");
            } else {
                sb.append(escapeText(entry.getText() == null ? "" : entry.getText()));
            }
            liOpen = true;
        }
        while (depth > 0) {
            if (liOpen) {
                sb.append("</li>\n");
                liOpen = true;
            }
            sb.append("</ul>\n");
            depth--;
        }
        sb.append("</nav>\n");
    }

    private void emitFootnote(StringBuilder sb, Footnote fn) throws IOException {
        String refId = fn.getRefId() == null ? "" : fn.getRefId();
        sb.append("<div class=\"footnote\" id=\"fn-").append(escapeAttr(refId)).append("\">");
        sb.append("<sup><a href=\"#fnref-").append(escapeAttr(refId)).append("\">")
          .append(escapeText(refId)).append("</a></sup> ");
        emitBlocksCompact(sb, fn.getChildren());
        sb.append("</div>\n");
    }

    /**
     * Emits a block list in a compact context (li/td/figcaption/footnote): a single
     * Paragraph child is inlined without its {@code <p>} wrapper; anything else is
     * emitted as full blocks.
     */
    private void emitBlocksCompact(StringBuilder sb, List<SdmBlock> blocks) throws IOException {
        if (blocks == null || blocks.isEmpty()) {
            return;
        }
        if (blocks.size() == 1 && blocks.get(0) instanceof Paragraph) {
            Paragraph p = (Paragraph) blocks.get(0);
            BlockStyle st = p.getStyle();
            String cls = st == null ? null : blockStyleClass(st);
            if (cls != null) {
                sb.append("<span class=\"").append(cls).append("\">");
                emitInlines(sb, p.getInline());
                sb.append("</span>");
            } else {
                emitInlines(sb, p.getInline());
            }
            return;
        }
        for (SdmBlock b : blocks) {
            emitBlock(sb, b);
        }
    }

    // ------------------------------------------------------------------
    // Inline-level emission
    // ------------------------------------------------------------------

    private void emitInlines(StringBuilder sb, List<SdmInline> inlines) throws IOException {
        if (inlines == null) {
            return;
        }
        for (SdmInline inline : inlines) {
            emitInline(sb, inline);
        }
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
                sb.append("<a href=\"").append(escapeAttr(link.getHref() == null ? "" : link.getHref())).append("\">");
                emitInlines(sb, link.getChildren());
                sb.append("</a>");
                break;
            }
            case INLINE_IMAGE: {
                InlineImage img = (InlineImage) inline;
                String src = resolveImageSrc(img.getImage());
                if (src != null) {
                    sb.append("<img src=\"").append(src).append('"');
                } else {
                    sb.append("<img data-resource=\"")
                      .append(escapeAttr(img.getImage() == null ? "" : img.getImage().getId())).append('"');
                }
                if (img.getAlt() != null && !img.getAlt().isEmpty()) {
                    sb.append(" alt=\"").append(escapeAttr(img.getAlt())).append('"');
                }
                sb.append("/>");
                break;
            }
            case LINE_BREAK:
                sb.append("<br/>\n");
                break;
            case FOOTNOTE_REF: {
                FootnoteRef ref = (FootnoteRef) inline;
                String refId = ref.getRefId() == null ? "" : ref.getRefId();
                sb.append("<sup class=\"fnref\" id=\"fnref-").append(escapeAttr(refId)).append("\">")
                  .append("<a href=\"#fn-").append(escapeAttr(refId)).append("\">")
                  .append(escapeText(refId)).append("</a></sup>");
                break;
            }
            case INLINE_OPAQUE: {
                InlineOpaque o = (InlineOpaque) inline;
                sb.append("<span class=\"opaque\"");
                if (o.getSourceRef() != null) {
                    sb.append(" data-source=\"").append(escapeAttr(o.getSourceRef().canonical())).append('"');
                }
                sb.append("></span>");
                break;
            }
            default:
                LOG.fine(() -> "SdmHtmlWriter: unmapped inline type " + inline.getType());
        }
    }

    private void emitRun(StringBuilder sb, Run run) {
        String text = run.getText() == null ? "" : run.getText();
        TextStyle st = run.getStyle();
        StringBuilder close = new StringBuilder();
        if (st != null) {
            String cls = textStyleClass(st);
            if (cls != null) {
                sb.append("<span class=\"").append(cls).append("\">");
                close.insert(0, "</span>");
            }
            if (st.isBold()) {
                sb.append("<b>");
                close.insert(0, "</b>");
            }
            if (st.isItalic()) {
                sb.append("<i>");
                close.insert(0, "</i>");
            }
            if (st.isUnderline()) {
                sb.append("<u>");
                close.insert(0, "</u>");
            }
            if (st.isStrikethrough()) {
                sb.append("<s>");
                close.insert(0, "</s>");
            }
            if (st.getVertAlign() == TextStyle.VertAlign.SUPER) {
                sb.append("<sup>");
                close.insert(0, "</sup>");
            } else if (st.getVertAlign() == TextStyle.VertAlign.SUB) {
                sb.append("<sub>");
                close.insert(0, "</sub>");
            }
        }
        sb.append(escapeText(text));
        sb.append(close);
    }

    // ------------------------------------------------------------------
    // Styles → CSS classes
    // ------------------------------------------------------------------

    /** Opens a block tag, appending id (when link-targeted) and style class. No trailing newline. */
    /**
     * Builds a {@code body} padding declaration from the page-margin metadata the
     * pipeline recorded (left/top/right in points), so the reflowed content is
     * inset from the edges like the source page. Returns null when no margins were
     * recorded (a bare SDM written directly, e.g. in unit tests).
     */
    private static String bodyPaddingRule(SdmMetadata meta) {
        if (meta == null) {
            return null;
        }
        String left = meta.getCustom().get("margin-left");
        String top = meta.getCustom().get("margin-top");
        String right = meta.getCustom().get("margin-right");
        if (left == null && top == null && right == null) {
            return null;
        }
        String t = top == null ? "0" : top;
        String r = right == null ? "0" : right;
        String l = left == null ? "0" : left;
        // padding: top right bottom left — reuse the top inset for the bottom.
        // !important so a browser reader/dark-mode/screenshot extension that injects
        // "body{padding:0!important}" cannot flatten the page inset.
        return "padding:" + t + "pt " + r + "pt " + t + "pt " + l + "pt !important";
    }

    /** True for a {@code #rgb}/{@code #rrggbb} literal — the only colours we emit. */
    private static boolean isCssColor(String s) {
        if (s == null || s.isEmpty() || s.charAt(0) != '#') {
            return false;
        }
        int n = s.length();
        if (n != 4 && n != 7) {
            return false;
        }
        for (int i = 1; i < n; i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private void openTag(StringBuilder sb, String tag, SdmBlock block) {
        sb.append('<').append(tag);
        emitIdAndClass(sb, block);
        sb.append('>');
    }

    private void emitIdAndClass(StringBuilder sb, SdmBlock block) {
        if (block.getId() != null && referencedIds.contains(block.getId())) {
            sb.append(" id=\"").append(escapeAttr(block.getId())).append('"');
        }
        BlockStyle style = block.getStyle();
        if (style != null) {
            String cls = blockStyleClass(style);
            if (cls != null) {
                sb.append(" class=\"").append(cls).append('"');
            }
        }
    }

    /**
     * Maps a BlockStyle to a CSS declaration block; returns the shared class name,
     * or null when every property is at its default (no class needed).
     */
    private String blockStyleClass(BlockStyle st) {
        StringBuilder css = new StringBuilder();
        if (st.getAlign() != null && st.getAlign() != BlockStyle.Align.LEFT) {
            css.append("text-align:").append(st.getAlign().name().toLowerCase()).append(';');
        }
        if (st.getDirection() == BlockStyle.Direction.RTL) {
            css.append("direction:rtl;");
        }
        if (st.getIndentStart() != 0) {
            css.append("margin-left:").append(fmt(st.getIndentStart())).append("pt;");
        }
        if (st.getIndentEnd() != 0) {
            css.append("margin-right:").append(fmt(st.getIndentEnd())).append("pt;");
        }
        if (st.getIndentFirstLine() != 0) {
            css.append("text-indent:").append(fmt(st.getIndentFirstLine())).append("pt;");
        }
        if (st.getSpaceBefore() != 0) {
            css.append("margin-top:").append(fmt(st.getSpaceBefore())).append("pt;");
        }
        if (st.getSpaceAfter() != 0) {
            css.append("margin-bottom:").append(fmt(st.getSpaceAfter())).append("pt;");
        }
        if (st.getLineHeight() != 0) {
            css.append("line-height:").append(fmt(st.getLineHeight())).append(';');
        }
        if (st.getBackground() != 0) {
            // Padding gives the tint the callout/field look it had in the PDF.
            css.append("background-color:").append(hexColor(st.getBackground()))
               .append(";padding:1px 3px;");
        }
        if (css.length() == 0) {
            return null;
        }
        return cssClass(stripTrailingSemicolon(css));
    }

    /**
     * Maps TextStyle font/size/colour/background/family to a span class; bold/italic/
     * underline/strike/vertAlign are NOT included (they map to semantic tags).
     * Returns null when no span is needed.
     */
    private String textStyleClass(TextStyle st) {
        StringBuilder css = new StringBuilder();
        if (st.getFontFamily() != null && !st.getFontFamily().isEmpty()) {
            css.append("font-family:'").append(st.getFontFamily().replace("'", "")).append("';");
        }
        if (st.getFontSize() > 0) {
            css.append("font-size:").append(fmt(st.getFontSize())).append("pt;");
        }
        if (st.getColor() != 0) {
            css.append("color:").append(hexColor(st.getColor())).append(';');
        }
        if (st.getBackground() != 0) {
            css.append("background-color:").append(hexColor(st.getBackground())).append(';');
        }
        if (css.length() == 0) {
            return null;
        }
        return cssClass(stripTrailingSemicolon(css));
    }

    private String cssClass(CharSequence declarations) {
        return cssClasses.computeIfAbsent(declarations.toString(), d -> "c" + (cssClasses.size() + 1));
    }

    private static String stripTrailingSemicolon(StringBuilder css) {
        if (css.length() > 0 && css.charAt(css.length() - 1) == ';') {
            css.setLength(css.length() - 1);
        }
        return css.toString();
    }

    private static String hexColor(int argb) {
        return String.format("#%06x", argb & 0xFFFFFF);
    }

    /** Formats a double without a trailing ".0" for whole values. */
    private static String fmt(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return Long.toString((long) v);
        }
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------
    // Images
    // ------------------------------------------------------------------

    /**
     * Resolves a resource reference to an img src value (data URI or external file),
     * or null when the reference cannot be resolved.
     */
    private String resolveImageSrc(ResourceRef ref) throws IOException {
        return resolveImageSrc(ref, 0, 0);
    }

    /**
     * Resolves an image resource to a {@code src} value. When the on-page
     * footprint (points) is known, embedded rasters are capped near that size —
     * the ResourceTable keeps the source's FULL resolution for lossless
     * round-trips, but re-embedding a 300-dpi scan per figure floods the HTML
     * (100MB of image bytes on one corpus doc OOM'd the writer).
     *
     * @param ref the resource reference
     * @param wPt on-page width in points (0 = unknown, embed verbatim)
     * @param hPt on-page height in points (0 = unknown, embed verbatim)
     * @return the src value, or null when unresolvable
     * @throws IOException when external image write fails
     */
    private String resolveImageSrc(ResourceRef ref, double wPt, double hPt) throws IOException {
        if (ref == null || doc == null) {
            return null;
        }
        Resource res = doc.getResources().get(ref);
        if (res == null || res.getBytes() == null) {
            return null;
        }
        String mime = res.getMime() == null || res.getMime().isEmpty() ? "image/png" : res.getMime();
        if (options.getImageMode() == HtmlWriterOptions.ImageMode.EXTERNAL) {
            Path dir = options.getExternalImagesDir();
            if (dir == null) {
                throw new IOException("HtmlWriterOptions: EXTERNAL image mode requires externalImagesDir");
            }
            Files.createDirectories(dir);
            String ext = extensionFor(mime);
            String name = "img" + (++externalImageCounter) + ext;
            Files.write(dir.resolve(name), res.getBytes());
            return escapeAttr(options.getExternalImagesPrefix() + name);
        }
        // CSS px at 96dpi = pt * 96/72.
        return org.aspose.pdf.html.HtmlImageEncoder.dataUri(
                res.getBytes(), mime, wPt * 96 / 72, hPt * 96 / 72);
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
    // Link-target collection
    // ------------------------------------------------------------------

    private void collectReferencedIds(List<SdmBlock> blocks) {
        if (blocks == null) {
            return;
        }
        for (SdmBlock b : blocks) {
            if (b instanceof TocBlock) {
                for (TocEntry e : ((TocBlock) b).getEntries()) {
                    if (e.getTargetId() != null && !e.getTargetId().isEmpty()) {
                        referencedIds.add(e.getTargetId());
                    }
                }
            } else if (b instanceof Container) {
                collectReferencedIds(((Container) b).getChildren());
            } else if (b instanceof Quote) {
                collectReferencedIds(((Quote) b).getChildren());
            } else if (b instanceof ListBlock) {
                for (ListItem li : ((ListBlock) b).getItems()) {
                    collectReferencedIds(li.getChildren());
                }
            } else if (b instanceof Table) {
                for (TableRow r : ((Table) b).getRows()) {
                    for (TableCell c : r.getCells()) {
                        collectReferencedIds(c.getChildren());
                    }
                }
            } else if (b instanceof Footnote) {
                collectReferencedIds(((Footnote) b).getChildren());
            }
        }
    }

    // ------------------------------------------------------------------
    // Escaping
    // ------------------------------------------------------------------

    /**
     * Escapes text content: {@code & < >}; control characters that are illegal
     * in both HTML and XML (C0 except tab/newline/CR — PDF text streams do
     * carry them) are emitted as spaces so the output stays parseable.
     */
    static String escapeText(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            if (c == '&') {
                rep = "&amp;";
            } else if (c == '<') {
                rep = "&lt;";
            } else if (c == '>') {
                rep = "&gt;";
            } else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') {
                rep = " ";
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16).append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** Escapes attribute values: {@code & < > "}. */
    static String escapeAttr(String s) {
        if (s == null) {
            return "";
        }
        return escapeText(s).replace("\"", "&quot;");
    }
}
