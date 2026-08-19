package org.aspose.pdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.pgm.FlowClass;
import org.aspose.pdf.pgm.FlowClassifier;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.text.Font;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextState;

/**
 * Re-flows the text <b>and images</b> of an existing single-column PDF into a
 * two- or three-column layout, wrapping by words.
 *
 * <p>Content comes from the project's <b>IR</b> (the SDM/PGM model produced by
 * {@link PdfSdmReader} + {@link FlowClassifier}): only {@link FlowClass#FLOW}
 * boxes are re-flowed, so page chrome — running headers/footers, page numbers,
 * watermarks — is left out (it is classified {@code FIXED}). Boxes are placed in
 * the model's reading order; text carries its font name and size. Raster images
 * flow <b>inline</b> like oversized words: an image is scaled to roughly a few
 * lines of the neighbouring text ({@link Options#imageHeightInLines}) and packed
 * onto the current line, so a row of small figures (e.g. hazard pictograms) lands
 * side by side and wraps to the next line when the column is full — instead of
 * stacking one per line.</p>
 *
 * <p>This is a content re-flow: it reproduces the flow words, their font/size and
 * raster images, not vector graphics or exact glyph metrics. Fill colour is not
 * carried by the IR text model and defaults to black.</p>
 *
 * <pre>
 *   ColumnReflow.reflow("in.pdf", "out-2col.pdf", 2);
 *
 *   try (Document src = new Document("in.pdf")) {
 *       ColumnReflow.Options opt = new ColumnReflow.Options();
 *       opt.columns = 3;
 *       opt.imageHeightInLines = 3;   // pictograms ~3 text lines tall, in a row
 *       Document out = ColumnReflow.reflow(src, opt);
 *       out.save("out-3col.pdf");
 *       out.close();
 *   }
 * </pre>
 */
public final class ColumnReflow {

    private ColumnReflow() {
    }

    /** Tunables for the column layout. All lengths are in PDF points (1/72"). */
    public static final class Options {
        /** Number of columns (2 or 3; any value &ge; 1 works). */
        public int columns = 2;
        /** Outer margin on all four sides. */
        public double margin = 54;
        /** Horizontal space between adjacent columns. */
        public double gutter = 18;
        /** Line advance = font size &times; this factor. */
        public double lineSpacingFactor = 1.16;
        /** A source line gap larger than this &times; line height starts a new paragraph. */
        public double paragraphGapFactor = 1.6;
        /** Extra vertical space at a paragraph break, as a fraction of line height. */
        public double paragraphSpacingFactor = 0.5;
        /**
         * Target image height in <b>lines of the surrounding text</b>: a placed
         * image is scaled so its height is about this many text lines of the
         * neighbouring font size (kept proportional, never wider than the column).
         * Images then flow inline and wrap, so adjacent figures sit in a row.
         * Raise for bigger figures, lower for icon-sized images.
         */
        public double imageHeightInLines = 3;
        /** Horizontal gap between inline images / around an image, in points. */
        public double imageGap = 3;
        /** Include IR image boxes in the flow (set false for text-only). */
        public boolean includeImages = true;
        /** Output page width; &le;0 = copy the source's first page width. */
        public double pageWidth = 0;
        /** Output page height; &le;0 = copy the source's first page height. */
        public double pageHeight = 0;
    }

    /** Convenience: reflow {@code inPath} into {@code columns} columns, save to {@code outPath}. */
    public static void reflow(String inPath, String outPath, int columns) throws IOException {
        try (Document src = new Document(inPath)) {
            Options opt = new Options();
            opt.columns = columns;
            Document out = reflow(src, opt);
            out.save(outPath);
            out.close();
        }
    }

    /**
     * Reflows {@code src} into a new column-laid-out {@link Document}. The source
     * is only read; the caller owns both documents.
     */
    public static Document reflow(Document src, Options opt) throws IOException {
        if (opt.columns < 1) {
            throw new IllegalArgumentException("columns must be >= 1");
        }
        double pageW = opt.pageWidth > 0 ? opt.pageWidth : firstPageWidth(src);
        double pageH = opt.pageHeight > 0 ? opt.pageHeight : firstPageHeight(src);
        double colW = (pageW - 2 * opt.margin - (opt.columns - 1) * opt.gutter) / opt.columns;
        if (colW <= 0) {
            throw new IllegalArgumentException("columns/margins/gutter leave no room for a column");
        }
        double maxColH = pageH - 2 * opt.margin;

        List<Token> tokens = extractTokens(src, opt, colW, maxColH);
        Document out = new Document();
        Layout layout = new Layout(out, opt, pageW, pageH, colW);

        // Greedy line breaking: words AND images fill the line until it overflows.
        List<Token> line = new ArrayList<>();
        double lineWidth = 0;
        double lineMaxSize = 0;     // tallest text on the line
        double lineMaxImgH = 0;     // tallest image on the line
        for (Token t : tokens) {
            if (t.paragraphBreak) {
                layout.flushLine(line, lineMaxSize, lineMaxImgH);
                line.clear();
                lineWidth = 0;
                lineMaxSize = 0;
                lineMaxImgH = 0;
                layout.paragraphGap();
                continue;
            }
            double add = line.isEmpty() ? t.width : t.spaceBefore + t.width;
            if (!line.isEmpty() && lineWidth + add > colW) {
                layout.flushLine(line, lineMaxSize, lineMaxImgH);
                line.clear();
                lineWidth = 0;
                lineMaxSize = 0;
                lineMaxImgH = 0;
                add = t.width;
            }
            line.add(t);
            lineWidth += add;
            if (t.image != null) {
                lineMaxImgH = Math.max(lineMaxImgH, t.image.h);
            } else {
                lineMaxSize = Math.max(lineMaxSize, t.fontSize);
            }
        }
        layout.flushLine(line, lineMaxSize, lineMaxImgH);
        layout.finish();
        return out;
    }

    // ------------------------------------------------------- IR extraction

    /** A placeable word, a paragraph-break marker, or an inline image. */
    private static final class Token {
        String text;
        double width;
        double spaceBefore;
        double fontSize;
        Font font;
        Color color;
        boolean paragraphBreak;
        ImageBlock image;
    }

    /** An image to place, already scaled to its on-output display size. */
    private static final class ImageBlock {
        byte[] bytes;
        double w;
        double h;
    }

    /**
     * Builds the reading-order token stream from the IR: FLOW text and image
     * boxes only (chrome is FIXED and dropped), in the model's reading order.
     * Images become inline tokens scaled to the neighbouring text; paragraph
     * breaks come from large vertical gaps and page boundaries.
     */
    private static List<Token> extractTokens(Document src, Options opt, double colW, double maxColH)
            throws IOException {
        List<Token> out = new ArrayList<>();
        PdfSdmReader.Result model = new PdfSdmReader().read(src, null);
        FlowClassifier.classify(model.getPgm());

        boolean firstPage = true;
        double lastTextSize = 11;
        for (PgmPage page : model.getPgm().getPages()) {
            Page srcPage = src.getPages().get(page.getIndex() + 1);

            List<PgmBox> boxes = new ArrayList<>();
            for (PgmBox b : page.getBoxes()) {
                if (b.getFlowClass() != FlowClass.FLOW) {
                    continue;   // FIXED chrome / ANCHORED / ATOMIC excluded
                }
                if (b.getKind() == PgmBoxKind.TEXT
                        || (opt.includeImages && b.getKind() == PgmBoxKind.IMAGE)) {
                    boxes.add(b);
                }
            }
            boxes.sort(Comparator
                    .comparingInt((PgmBox b) -> b.getReadingIndex() >= 0
                            ? b.getReadingIndex() : Integer.MAX_VALUE)
                    .thenComparingDouble(b -> -b.getRect().getTop())
                    .thenComparingDouble(b -> b.getRect().getX()));

            if (!firstPage && !out.isEmpty()) {
                out.add(breakToken());
            }
            firstPage = false;

            double prevTop = Double.NaN;
            double prevSize = 0;
            for (PgmBox b : boxes) {
                PgmRect r = b.getRect();
                double top = r.getTop();

                if (b.getKind() == PgmBoxKind.IMAGE) {
                    byte[] bytes = resolveImageBytes(srcPage, b);
                    if (bytes == null) {
                        continue;
                    }
                    double srcW = r.getRight() - r.getX();
                    double srcH = r.getTop() - r.getY();
                    if (srcW <= 0 || srcH <= 0) {
                        continue;
                    }
                    // Paragraph break only on a big vertical gap (keeps a row of
                    // images together, but separates them from distant text).
                    if (!Double.isNaN(prevTop)) {
                        double lineH = Math.max(prevSize, lastTextSize) * opt.lineSpacingFactor;
                        if (prevTop - top > opt.paragraphGapFactor * lineH) {
                            out.add(breakToken());
                        }
                    }
                    double scale = opt.imageHeightInLines > 0
                            ? Math.min(opt.imageHeightInLines * lastTextSize * opt.lineSpacingFactor
                                    / srcH, colW / srcW)
                            : Math.min(1.0, colW / srcW);
                    scale = Math.min(scale, maxColH / srcH);
                    Token t = new Token();
                    t.image = new ImageBlock();
                    t.image.bytes = bytes;
                    t.image.w = srcW * scale;
                    t.image.h = srcH * scale;
                    t.width = t.image.w;
                    t.spaceBefore = opt.imageGap;
                    out.add(t);
                    prevTop = top;
                    prevSize = lastTextSize;
                    continue;
                }
                if (!(b.getData() instanceof TextBoxData)) {
                    continue;
                }
                TextBoxData d = (TextBoxData) b.getData();
                String text = d.getText();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                double size = d.getFontSize() > 0 ? d.getFontSize() : 11;
                if (!Double.isNaN(prevTop)) {
                    double lineH = Math.max(prevSize, size) * opt.lineSpacingFactor;
                    if (prevTop - top > opt.paragraphGapFactor * lineH) {
                        out.add(breakToken());
                    }
                }
                double width = r.getRight() - r.getX();
                double avgChar = width / Math.max(1, text.replace(" ", "").length());
                if (avgChar <= 0) {
                    avgChar = size * 0.5;
                }
                Font font = cleanFontName(d.getFontName());
                for (String w : text.trim().split("\\s+")) {
                    if (w.isEmpty()) {
                        continue;
                    }
                    Token tok = new Token();
                    tok.text = w;
                    tok.width = avgChar * w.length();
                    tok.spaceBefore = avgChar;
                    tok.fontSize = size;
                    tok.font = font;
                    tok.color = Color.BLACK;
                    out.add(tok);
                }
                prevTop = top;
                prevSize = size;
                lastTextSize = size;
            }
        }
        return out;
    }

    /**
     * Resolves the raster bytes of an IR IMAGE box: its {@link ContentRange}
     * points at the {@code Do} operator, whose /XObject name resolves to an
     * {@link XImage} on the source page, saved to a portable PNG/JPEG.
     */
    private static byte[] resolveImageBytes(Page srcPage, PgmBox box) {
        try {
            if (!(box.getSourceRef() instanceof ContentRange)) {
                return null;
            }
            ContentRange cr = (ContentRange) box.getSourceRef();
            OperatorCollection ops = srcPage.getContents();
            int idx = cr.getOpEnd();
            if (idx < 0 || idx >= ops.size()) {
                return null;
            }
            Operator op = ops.getAt(idx);
            if (!"Do".equals(op.getName()) || op.getOperands().isEmpty()
                    || !(op.getOperands().get(0) instanceof PdfName)) {
                return null;
            }
            String name = ((PdfName) op.getOperands().get(0)).getName();
            XImage img = srcPage.getResources().getImages().get(name);
            if (img == null) {
                return null;
            }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            img.save(b);
            return b.size() > 0 ? b.toByteArray() : null;
        } catch (Exception e) {
            return null;   // best-effort: an unreadable image never breaks the reflow
        }
    }

    private static Token breakToken() {
        Token t = new Token();
        t.paragraphBreak = true;
        return t;
    }

    /** Wraps an IR font name in a {@link Font}, dropping a "ABCDEF+" subset prefix. */
    private static Font cleanFontName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        String clean = name;
        if (name.length() > 7 && name.charAt(6) == '+'
                && name.substring(0, 6).chars().allMatch(Character::isUpperCase)) {
            clean = name.substring(7);
        }
        Font f = new Font(clean);
        f.setEmbedded(false);
        return f;
    }

    // --------------------------------------------------------------- layout

    /** Places wrapped lines (words + inline images) into columns/pages. */
    private static final class Layout {
        private final Document doc;
        private final Options opt;
        private final double pageW;
        private final double pageH;
        private final double colW;
        private final double top;
        private final double bottom;

        private Page page;
        private TextBuilder builder;
        private int col;
        private double cursorTop;
        private boolean anythingOnPage;

        Layout(Document doc, Options opt, double pageW, double pageH, double colW)
                throws IOException {
            this.doc = doc;
            this.opt = opt;
            this.pageW = pageW;
            this.pageH = pageH;
            this.colW = colW;
            this.top = pageH - opt.margin;
            this.bottom = opt.margin;
            newPage();
        }

        private void newPage() throws IOException {
            page = doc.getPages().add();
            page.setPageSize(pageW, pageH);
            builder = new TextBuilder(page);
            col = 0;
            cursorTop = top;
            anythingOnPage = false;
        }

        private double colX() {
            return opt.margin + col * (colW + opt.gutter);
        }

        private void nextColumn() throws IOException {
            col++;
            if (col >= opt.columns) {
                newPage();
            } else {
                cursorTop = top;
            }
        }

        void paragraphGap() {
            if (cursorTop < top) {
                cursorTop -= opt.paragraphSpacingFactor * 12;
            }
        }

        /** Renders one wrapped line — text words at the baseline, images as blocks. */
        void flushLine(List<Token> items, double lineMaxSize, double lineMaxImgH)
                throws IOException {
            if (items.isEmpty()) {
                return;
            }
            double textLineH = lineMaxSize > 0 ? lineMaxSize * opt.lineSpacingFactor : 0;
            double imgLineH = lineMaxImgH > 0 ? lineMaxImgH + 2 * opt.imageGap : 0;
            double lineH = Math.max(textLineH, imgLineH);
            if (lineH <= 0) {
                return;
            }
            if (cursorTop - lineH < bottom && cursorTop < top) {
                nextColumn();
            }
            double baseline = cursorTop - (lineMaxSize > 0 ? lineMaxSize : 11) * 0.80;
            double x = colX();
            for (Token it : items) {
                if (it.image != null) {
                    // Sit the image on the line, bottom-aligned to the line's bottom band.
                    double yBottom = cursorTop - lineH + Math.max(0, (imgLineH - it.image.h) / 2)
                            + opt.imageGap;
                    embedImage(x, yBottom, it.image);
                    x += it.image.w + it.spaceBefore;
                } else {
                    TextFragment tf = new TextFragment(it.text);
                    TextState ts = tf.getTextState();
                    ts.setFontSize(it.fontSize);
                    if (it.font != null) {
                        ts.setFont(it.font);
                    }
                    ts.setForegroundColor(it.color != null ? it.color : Color.BLACK);
                    tf.setPosition(new Position(x, baseline));
                    try {
                        builder.appendText(tf);
                    } catch (Exception fontIssue) {
                        TextFragment plain = new TextFragment(it.text);
                        plain.getTextState().setFontSize(it.fontSize);
                        plain.setPosition(new Position(x, baseline));
                        builder.appendText(plain);
                    }
                    x += it.width + it.spaceBefore;
                }
            }
            cursorTop -= lineH;
            anythingOnPage = true;
        }

        /** Embeds image bytes on the current page and draws them at (x, yBottom). */
        private void embedImage(double x, double yBottom, ImageBlock ib) throws IOException {
            XImageCollection imgs = page.ensureResources().getImages();
            Set<String> before = new HashSet<>(Arrays.asList(imgs.getNames()));
            imgs.add(new ByteArrayInputStream(ib.bytes));
            String name = null;
            for (String n : page.getResources().getImages().getNames()) {
                if (!before.contains(n)) {
                    name = n;
                    break;
                }
            }
            if (name != null) {
                String draw = String.format(Locale.US,
                        "q\n%.4f 0 0 %.4f %.4f %.4f cm\n/%s Do\nQ\n", ib.w, ib.h, x, yBottom, name);
                page.appendToContentStream(draw.getBytes(StandardCharsets.US_ASCII));
                anythingOnPage = true;
            }
        }

        void finish() throws IOException {
            if (doc.getPages().getCount() > 1 && !anythingOnPage) {
                doc.getPages().delete(doc.getPages().getCount());
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private static double firstPageWidth(Document src) {
        try {
            return src.getPages().get(1).getRect().getWidth();
        } catch (Exception e) {
            return 595;
        }
    }

    private static double firstPageHeight(Document src) {
        try {
            return src.getPages().get(1).getRect().getHeight();
        } catch (Exception e) {
            return 842;
        }
    }
}
