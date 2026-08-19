package org.aspose.pdf.html;

import org.aspose.pdf.*;
import org.aspose.pdf.text.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * Converts a PDF {@link Document} to HTML markup.
 * <p>
 * Supports two layout modes:
 * <ul>
 *   <li><b>Fixed layout</b> (default) — each text span is absolutely positioned,
 *       faithfully reproducing the original PDF layout.</li>
 *   <li><b>Reflowable layout</b> — text is grouped into paragraphs and headings
 *       for responsive display.</li>
 * </ul>
 * Images can be embedded as Base64 data URIs or saved to an external folder.
 * </p>
 */
public class PdfToHtmlConverter {

    private static final Logger LOG = Logger.getLogger(PdfToHtmlConverter.class.getName());

    /**
     * Converts the given PDF document to an HTML string.
     *
     * @param document the PDF document to convert
     * @param options  save options controlling layout, images, etc.
     * @return the HTML string
     * @throws IOException if reading the document fails
     */
    public String convert(Document document, HtmlSaveOptions options) throws IOException {
        if (options == null) options = new HtmlSaveOptions();

        StringBuilder html = new StringBuilder(4096);
        appendHtmlHead(html, options);

        PageCollection pages = document.getPages();
        for (int i = 1; i <= pages.getCount(); i++) {
            Page page = pages.get(i);
            appendPageDiv(html, page, i, options);
        }

        html.append("</body>\n</html>\n");
        return html.toString();
    }

    private void appendHtmlHead(StringBuilder html, HtmlSaveOptions options) {
        if (options.getDocumentType() == HtmlDocumentType.Xhtml) {
            html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            html.append("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" ");
            html.append("\"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n");
        } else {
            html.append("<!DOCTYPE html>\n");
        }
        html.append("<html>\n<head>\n<meta charset=\"UTF-8\"/>\n");
        html.append("<style>\n");
        html.append("body { margin: 0; padding: 0; background: #e0e0e0; }\n");
        html.append(".page { position: relative; margin: 20px auto; background: white; ");
        html.append("overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.15); }\n");
        // Text paints above images (watermarks, logos and photos sit behind the
        // glyphs, exactly as the PDF paints them) — z-index, not DOM order, so a
        // full-page watermark emitted last never covers the body copy.
        html.append(".t { position: absolute; white-space: pre; z-index: 2; }\n");
        html.append(".i { position: absolute; z-index: 1; }\n");
        // Vector underlay: the page's path/fill/stroke/shading content rendered
        // to a raster, behind both images and text.
        html.append(".v { position: absolute; left: 0; top: 0; z-index: 0; }\n");
        // Interactive form fields sit above everything; transparent chrome so
        // the vector underlay (the printed field boxes) stays the visual frame.
        html.append(".f { position: absolute; z-index: 3; box-sizing: border-box; ");
        html.append("background: transparent; border: none; padding: 0; margin: 0; ");
        html.append("font: inherit; }\n");
        html.append("</style>\n</head>\n<body>\n");
    }

    private void appendPageDiv(StringBuilder html, Page page, int pageNum,
                                HtmlSaveOptions options) throws IOException {
        Rectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 595, 842); // A4 fallback
        double w = box.getWidth() * options.getScale();
        double h = box.getHeight() * options.getScale();

        html.append(String.format(
            "<div class=\"page\" id=\"p%d\" style=\"width:%.0fpx;height:%.0fpx;\">\n",
            pageNum, w, h));

        if (options.isFixedLayout()) {
            // Vector graphics first (DOM order matters little — .v carries
            // z-index 0): everything drawn with path/fill/stroke/shading ops
            // would otherwise be lost entirely, blanking charts and shapes.
            // Page-capped: each underlay is a full page render + embedded PNG,
            // which on a thousands-page document means hours and an OOM-sized
            // output string. -Dhtml.vectorUnderlayMaxPages overrides.
            int underlayCap = Integer.getInteger("html.vectorUnderlayMaxPages", 200);
            if (options.isRasterizeVectorGraphics() && options.isEmbedImages()) {
                if (pageNum <= underlayCap) {
                    appendVectorUnderlay(html, page, options, w, h);
                } else if (pageNum == underlayCap + 1) {
                    LOG.warning("Vector underlay capped at " + underlayCap
                            + " pages — later pages omit vector graphics"
                            + " (-Dhtml.vectorUnderlayMaxPages to raise)");
                }
            }
            // Fixed layout is a pixel-faithful visual copy: every glyph run keeps
            // its absolute PDF coordinate. Emitting detected tables as normal-flow
            // <table> blocks would (a) pull their text out of the positioned flow
            // and (b) stack the tables into a shrink-to-content column at the
            // top-left, collapsing the whole page into a narrow left strip. So in
            // this mode no <table> is emitted — the cell text positions itself.
            appendFixedLayoutContent(html, page, options, box,
                    java.util.Collections.<Rectangle>emptyList());
            appendFormFields(html, page, options, box);
        } else {
            // Reflowable layout: ruled tables render as real <table> markup
            // (PDFNET-39027); their text is excluded from the paragraph flow to
            // avoid duplicates.
            List<Rectangle> tableRects = appendTables(html, page, options, box);
            appendReflowableContent(html, page, options, box, tableRects);
        }

        appendImages(html, page, options, box);

        html.append("</div>\n");
    }

    /**
     * Emits every ruled table of the page (detected by {@link TableAbsorber})
     * as an HTML {@code <table>} with one {@code <td>} per detected cell.
     * Border-box-only regions (a single row or column) are left to the text
     * flow. Returns the page rectangles the emitted tables cover so the
     * caller can exclude their text from the regular flow.
     */
    private List<Rectangle> appendTables(StringBuilder html, Page page,
                                         HtmlSaveOptions options, Rectangle box) {
        List<Rectangle> covered = new ArrayList<>();
        org.aspose.pdf.text.TableAbsorber absorber = new org.aspose.pdf.text.TableAbsorber();
        try {
            absorber.visit(page);
        } catch (IOException e) {
            LOG.warning("Table detection failed for HTML export: " + e.getMessage());
            return covered;
        }
        for (org.aspose.pdf.text.AbsorbedTable table : absorber.getTableList()) {
            List<org.aspose.pdf.text.AbsorbedRow> rows = table.getRowList();
            if (rows.size() < 2 || rows.get(0).getCellList().size() < 2) {
                continue;   // plain border box, not tabular content
            }
            Rectangle rect = table.getRectangle();
            // Emit the bare <table> tag (Aspose does the same; PDFNET-39027
            // checks for it literally). Cell geometry is carried by the
            // detected structure, not inline CSS.
            html.append("<table>\n");
            for (org.aspose.pdf.text.AbsorbedRow row : rows) {
                html.append("<tr>");
                for (org.aspose.pdf.text.AbsorbedCell cell : row.getCellList()) {
                    html.append("<td>").append(escapeHtml(cell.getText())).append("</td>");
                }
                html.append("</tr>\n");
            }
            html.append("</table>\n");
            if (rect != null) {
                covered.add(rect);
            }
        }
        return covered;
    }

    /** Whether the text position lies inside any of the covered rectangles. */
    private static boolean insideAny(List<Rectangle> rects, Position pos) {
        if (pos == null || rects.isEmpty()) {
            return false;
        }
        for (Rectangle r : rects) {
            if (pos.getXIndent() >= r.getLLX() - 1 && pos.getXIndent() <= r.getURX() + 1
                    && pos.getYIndent() >= r.getLLY() - 1 && pos.getYIndent() <= r.getURY() + 1) {
                return true;
            }
        }
        return false;
    }

    // ── Fixed Layout ──

    private void appendFixedLayoutContent(StringBuilder html, Page page,
                                           HtmlSaveOptions options, Rectangle box,
                                           List<Rectangle> tableRects) {
        double scale = options.getScale();
        double pageH = box.getHeight();

        TextFragmentAbsorber absorber = new TextFragmentAbsorber();
        try {
            page.accept(absorber);
        } catch (IOException e) {
            LOG.warning("Failed to extract text from page: " + e.getMessage());
            return;
        }

        for (TextFragment tf : absorber.getTextFragments()) {
            if (insideAny(tableRects, tf.getPosition())) {
                continue;   // already rendered inside a <table>
            }
            List<TextSegment> segments = tf.getSegments();
            if (segments == null || segments.isEmpty()) {
                // Use fragment-level data
                appendTextSpan(html, tf.getText(), tf.getPosition(), tf.getTextState(),
                               scale, pageH, box.getLLX(), box.getLLY(), options);
                continue;
            }
            for (TextSegment seg : segments) {
                appendTextSpan(html, seg.getText(), seg.getPosition(), seg.getTextState(),
                               scale, pageH, box.getLLX(), box.getLLY(), options);
            }
        }
    }

    private void appendTextSpan(StringBuilder html, String text, Position pos,
                                 TextState ts, double scale, double pageH,
                                 double llx, double lly, HtmlSaveOptions options) {
        if (text == null || text.trim().isEmpty()) return;
        if (pos == null) return;

        double x = (pos.getXIndent() - llx) * scale;
        double fontSize = (ts != null && ts.getFontSize() > 0) ? ts.getFontSize() * scale : 12 * scale;
        double y = (pageH - (pos.getYIndent() - lly)) * scale - fontSize * 0.8;

        StringBuilder style = new StringBuilder();
        style.append(String.format(Locale.US, "left:%.1fpx;top:%.1fpx;font-size:%.1fpx;", x, y, fontSize));

        if (ts != null) {
            String fontFamily = mapFontToCSS(ts.getFontName());
            style.append("font-family:").append(fontFamily).append(';');

            String fn = ts.getFontName();
            if (fn != null) {
                String lower = fn.toLowerCase();
                if (lower.contains("bold")) style.append("font-weight:bold;");
                if (lower.contains("italic") || lower.contains("oblique"))
                    style.append("font-style:italic;");
            }

            Color fg = ts.getForegroundColor();
            if (fg != null) {
                int r = clamp255(fg.getR());
                int g = clamp255(fg.getG());
                int b = clamp255(fg.getB());
                if (r != 0 || g != 0 || b != 0) {
                    style.append(String.format("color:rgb(%d,%d,%d);", r, g, b));
                }
            }
        }

        html.append("  <span class=\"").append(options.getCssPrefix()).append("t\" style=\"")
            .append(style).append("\">")
            .append(escapeHtml(text))
            .append("</span>\n");
    }

    // ── Reflowable Layout ──

    private void appendReflowableContent(StringBuilder html, Page page,
                                          HtmlSaveOptions options, Rectangle box,
                                          List<Rectangle> tableRects) {
        TextFragmentAbsorber absorber = new TextFragmentAbsorber();
        try {
            page.accept(absorber);
        } catch (IOException e) {
            LOG.warning("Failed to extract text from page: " + e.getMessage());
            return;
        }

        List<TextFragment> fragments = new ArrayList<>();
        for (TextFragment tf : absorber.getTextFragments()) {
            if (insideAny(tableRects, tf.getPosition())) {
                continue;   // already rendered inside a <table>
            }
            fragments.add(tf);
        }
        if (fragments.isEmpty()) return;

        // Group into lines by Y-coordinate (tolerance ±2pt)
        List<List<TextFragment>> lines = groupIntoLines(fragments, 2.0);

        // Group lines into paragraphs by inter-line gap
        List<List<List<TextFragment>>> paragraphs = groupIntoParagraphs(lines);

        for (List<List<TextFragment>> para : paragraphs) {
            double maxSize = 0;
            for (List<TextFragment> line : para) {
                for (TextFragment tf : line) {
                    TextState ts = tf.getTextState();
                    if (ts != null && ts.getFontSize() > maxSize) maxSize = ts.getFontSize();
                }
            }
            String tag = maxSize >= 20 ? "h1" : maxSize >= 16 ? "h2" : maxSize >= 13 ? "h3" : "p";

            html.append('<').append(tag).append('>');
            for (int li = 0; li < para.size(); li++) {
                List<TextFragment> line = para.get(li);
                line.sort(Comparator.comparingDouble(f ->
                    f.getPosition() != null ? f.getPosition().getXIndent() : 0));
                for (TextFragment tf : line) {
                    String text = tf.getText();
                    if (text == null || text.isEmpty()) continue;

                    TextState ts = tf.getTextState();
                    boolean isBold = false, isItalic = false;
                    if (ts != null && ts.getFontName() != null) {
                        String fn = ts.getFontName().toLowerCase();
                        isBold = fn.contains("bold");
                        isItalic = fn.contains("italic") || fn.contains("oblique");
                    }

                    if (isBold) html.append("<b>");
                    if (isItalic) html.append("<i>");
                    html.append(escapeHtml(text));
                    if (isItalic) html.append("</i>");
                    if (isBold) html.append("</b>");
                }
                if (li < para.size() - 1) html.append(' ');
            }
            html.append("</").append(tag).append(">\n");
        }
    }

    private List<List<TextFragment>> groupIntoLines(List<TextFragment> fragments, double tolerance) {
        fragments.sort(Comparator.comparingDouble(f ->
            f.getPosition() != null ? -f.getPosition().getYIndent() : 0));

        List<List<TextFragment>> lines = new ArrayList<>();
        List<TextFragment> currentLine = new ArrayList<>();
        double currentY = Double.NaN;

        for (TextFragment tf : fragments) {
            if (tf.getPosition() == null) continue;
            double y = tf.getPosition().getYIndent();
            if (Double.isNaN(currentY) || Math.abs(y - currentY) <= tolerance) {
                currentLine.add(tf);
                if (Double.isNaN(currentY)) currentY = y;
            } else {
                if (!currentLine.isEmpty()) lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(tf);
                currentY = y;
            }
        }
        if (!currentLine.isEmpty()) lines.add(currentLine);
        return lines;
    }

    private List<List<List<TextFragment>>> groupIntoParagraphs(List<List<TextFragment>> lines) {
        List<List<List<TextFragment>>> paragraphs = new ArrayList<>();
        List<List<TextFragment>> currentPara = new ArrayList<>();

        double prevY = Double.NaN;
        for (List<TextFragment> line : lines) {
            double lineY = line.get(0).getPosition().getYIndent();
            double fontSize = 12;
            TextState ts = line.get(0).getTextState();
            if (ts != null && ts.getFontSize() > 0) fontSize = ts.getFontSize();

            if (!Double.isNaN(prevY) && Math.abs(prevY - lineY) > fontSize * 2.0) {
                if (!currentPara.isEmpty()) paragraphs.add(currentPara);
                currentPara = new ArrayList<>();
            }
            currentPara.add(line);
            prevY = lineY;
        }
        if (!currentPara.isEmpty()) paragraphs.add(currentPara);
        return paragraphs;
    }

    // ── Vector underlay ──

    /**
     * Renders the page's vector content (paths, fills, strokes, shadings —
     * text and raster images suppressed) and, when it is not blank, emits it
     * as an absolutely positioned full-page {@code <img class="v">} behind the
     * text and image layers. This is what keeps charts, filled shapes, rules
     * and gradients visible in fixed-layout HTML.
     */
    private void appendVectorUnderlay(StringBuilder html, Page page,
                                      HtmlSaveOptions options, double wPx, double hPx) {
        try {
            org.aspose.pdf.engine.render.PdfPageRenderer renderer =
                    new org.aspose.pdf.engine.render.PdfPageRenderer();
            renderer.setSuppressText(true);
            renderer.setSuppressRasterImages(true);
            // 2x the CSS pixel density so the underlay stays crisp when the
            // browser composites it under the text.
            double dpi = 144.0 * options.getScale();
            BufferedImage img = renderer.renderPage(page, dpi, dpi);
            if (img == null || isBlank(img)) {
                return;
            }
            html.append(String.format(Locale.US,
                "  <img class=\"v\" style=\"width:%.0fpx;height:%.0fpx;\" " +
                "src=\"data:image/png;base64,%s\"/>\n",
                wPx, hPx, imageToBase64(img)));
        } catch (Exception e) {
            LOG.fine("Vector underlay render failed: " + e.getMessage());
        }
    }

    /** True when every sampled pixel is white or fully transparent. */
    private static boolean isBlank(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int step = Math.max(1, Math.min(w, h) / 512); // sample, don't scan 4k²
        for (int y = 0; y < h; y += step) {
            for (int x = 0; x < w; x += step) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24;
                if (a < 8) {
                    continue;
                }
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (r < 248 || g < 248 || b < 248) {
                    return false;
                }
            }
        }
        return true;
    }

    // ── Images ──

    private void appendImages(StringBuilder html, Page page,
                               HtmlSaveOptions options, Rectangle box) {
        if (!options.isEmbedImages()) return;

        double scale = options.getScale();
        double pageH = box.getHeight();

        ImagePlacementAbsorber imgAbsorber = new ImagePlacementAbsorber();
        try {
            page.accept(imgAbsorber);
        } catch (IOException e) {
            LOG.warning("Failed to extract images from page: " + e.getMessage());
            return;
        }

        for (ImagePlacement placement : imgAbsorber.getImagePlacements()) {
            Rectangle rect = placement.getRectangle();
            if (rect == null) continue;

            double x = (rect.getLLX() - box.getLLX()) * scale;
            double y = (pageH - (rect.getURY() - box.getLLY())) * scale;
            double w = rect.getWidth() * scale;
            double h = rect.getHeight() * scale;

            try {
                BufferedImage bimg = placement.getImage().toBufferedImage();
                if (bimg == null) continue;
                // Cap the embedded raster at 2x its on-page CSS pixel size: the
                // browser can never show more detail, and full-resolution scans
                // (a 300-dpi A4 photo per page across hundreds of pages) blow
                // the base64 output — and the heap — by an order of magnitude.
                html.append(String.format(Locale.US,
                    "  <img class=\"i\" style=\"left:%.0fpx;top:%.0fpx;width:%.0fpx;height:%.0fpx;\" " +
                    "src=\"%s\"/>\n",
                    x, y, w, h, HtmlImageEncoder.dataUri(bimg, w, h)));
            } catch (Exception e) {
                LOG.fine("Failed to convert image: " + e.getMessage());
            }
        }
    }

    // ── Utilities ──

    private static String imageToBase64(BufferedImage img) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** Maps a PDF font name to a CSS font-family string. */
    public static String mapFontToCSS(String pdfFontName) {
        if (pdfFontName == null) return "'Helvetica',sans-serif";
        String lower = pdfFontName.toLowerCase();
        if (lower.contains("courier")) return "'Courier New',monospace";
        if (lower.contains("times")) return "'Times New Roman',serif";
        if (lower.contains("arial") || lower.contains("helvetica")) return "'Helvetica','Arial',sans-serif";
        if (lower.contains("symbol")) return "'Symbol',serif";
        if (lower.contains("zapf")) return "'ZapfDingbats',serif";
        return "'" + pdfFontName + "',sans-serif";
    }

    /** Escapes special HTML characters in text. */
    /**
     * Emits every Widget annotation of the page as an absolutely positioned,
     * transparent HTML control ({@code <input>}/{@code <select>}/
     * {@code <textarea>}/{@code <button>}) so the exported page is fillable.
     * The printed field frames come from the vector underlay; the controls add
     * only the interaction. Field facts (type, name, value, options) are
     * resolved through the shared IR helper
     * {@link org.aspose.pdf.sdm.reader.WidgetFieldInfo}.
     */
    private void appendFormFields(StringBuilder html, Page page,
                                  HtmlSaveOptions options, Rectangle box) {
        org.aspose.pdf.annotations.AnnotationCollection annots = page.getAnnotations();
        if (annots == null) {
            return;
        }
        double scale = options.getScale();
        double pageH = box.getHeight();
        for (int i = 1; i <= annots.getCount(); i++) {
            org.aspose.pdf.annotations.Annotation ann = annots.get(i);
            if (ann == null || !"Widget".equals(ann.getSubtype())) {
                continue;
            }
            Rectangle r = ann.getRect();
            if (r == null || r.getWidth() <= 0 || r.getHeight() <= 0) {
                continue;
            }
            org.aspose.pdf.sdm.reader.WidgetFieldInfo info =
                    org.aspose.pdf.sdm.reader.WidgetFieldInfo.resolve(ann.getPdfDictionary());
            if (info == null) {
                continue;
            }
            double x = (r.getLLX() - box.getLLX()) * scale;
            double y = (pageH - (r.getURY() - box.getLLY())) * scale;
            double w = r.getWidth() * scale;
            double h = r.getHeight() * scale;
            String pos = String.format(Locale.US,
                    "left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx;", x, y, w, h);
            String name = info.getName() == null ? "" :
                    " name=\"" + escapeHtml(info.getName()) + "\"";
            String ro = info.isReadOnly() ? " readonly" : "";
            switch (info.getKind()) {
                case CHECKBOX:
                case RADIO: {
                    html.append("<input class=\"f\" type=\"")
                        .append(info.getKind() == org.aspose.pdf.sdm.FormField.Kind.RADIO
                                ? "radio" : "checkbox")
                        .append('"').append(name);
                    if (info.getExportValue() != null) {
                        html.append(" value=\"").append(escapeHtml(info.getExportValue())).append('"');
                    }
                    if (info.isChecked()) {
                        html.append(" checked");
                    }
                    html.append(" style=\"").append(pos).append("\"/>\n");
                    break;
                }
                case COMBOBOX:
                case LISTBOX: {
                    html.append("<select class=\"f\"").append(name)
                        .append(" style=\"").append(pos).append("\">\n");
                    for (String opt : info.getOptions()) {
                        String o = opt == null ? "" : opt;
                        html.append("<option");
                        if (o.equals(info.getValue())) {
                            html.append(" selected");
                        }
                        html.append('>').append(escapeHtml(o)).append("</option>\n");
                    }
                    html.append("</select>\n");
                    break;
                }
                case BUTTON: {
                    html.append("<button class=\"f\" type=\"button\"").append(name)
                        .append(" style=\"").append(pos).append("\">")
                        .append(escapeHtml(info.getValue() == null ? "" : info.getValue()))
                        .append("</button>\n");
                    break;
                }
                default: { // TEXT / SIGNATURE
                    if (info.isMultiline()) {
                        html.append("<textarea class=\"f\"").append(name).append(ro)
                            .append(" style=\"").append(pos).append("\">")
                            .append(escapeHtml(info.getValue() == null ? "" : info.getValue()))
                            .append("</textarea>\n");
                    } else {
                        html.append("<input class=\"f\" type=\"text\"").append(name).append(ro);
                        if (info.getValue() != null) {
                            html.append(" value=\"").append(escapeHtml(info.getValue())).append('"');
                        }
                        if (info.getMaxLen() != null) {
                            html.append(" maxlength=\"").append(info.getMaxLen()).append('"');
                        }
                        html.append(" style=\"").append(pos).append("\"/>\n");
                    }
                }
            }
        }
    }

    public static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }

    private static int clamp255(double v) {
        return Math.max(0, Math.min(255, (int) (v * 255)));
    }
}
