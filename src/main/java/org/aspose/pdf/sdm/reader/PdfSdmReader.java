package org.aspose.pdf.sdm.reader;

import org.aspose.pdf.Document;
import org.aspose.pdf.Matrix;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.PageCollection;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.pgm.AnnotBoxData;
import org.aspose.pdf.pgm.ImageBoxData;
import org.aspose.pdf.pgm.PgmBox;
import org.aspose.pdf.pgm.PgmBoxKind;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.pgm.PgmPage;
import org.aspose.pdf.pgm.PgmRect;
import org.aspose.pdf.pgm.TextBoxData;
import org.aspose.pdf.pgm.VectorBoxData;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.ObjectRef;
import org.aspose.pdf.sdm.Opaque;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmIds;
import org.aspose.pdf.sdm.SourceRef;
import org.aspose.pdf.sdm.TextStyle;
import org.aspose.pdf.text.MarkupParagraph;
import org.aspose.pdf.text.MarkupSection;
import org.aspose.pdf.text.PageMarkup;
import org.aspose.pdf.text.ParagraphAbsorber;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * PDF → SDM/PGM projection (IR Stage 1, PART 2). SHALLOW semantics: text
 * becomes {@link Paragraph} nodes with {@link Run}s (no heading/list
 * recognition — that is the PDF↔HTML task), images become {@link Figure}s,
 * vector paths and annotations become {@link Opaque} nodes. The PGM side is
 * complete over visible content: TEXT, IMAGE, VECTOR boxes with
 * {@link ContentRange} locators carrying real operator indices, and
 * ANNOTATION/FIELD boxes with {@link ObjectRef} locators.
 * <p>
 * Known Stage-1 scope limits (documented, not silent): {@code sh} shading
 * paints are skipped (their extent is the current clip, which is not tracked);
 * form-XObject interiors are not entered — the placement is projected as one
 * UNKNOWN box over the transformed /BBox.
 * </p>
 */
public final class PdfSdmReader {

    private static final Logger LOG = Logger.getLogger(PdfSdmReader.class.getName());

    /** Min page text (chars) for a full-page image to count as a watermark backdrop
     *  rather than the page's actual content (a scan / full-page figure). */
    private static final int BACKGROUND_MIN_PAGE_TEXT = 200;

    /** Result pair of one projection. */
    public static final class Result {
        private final SdmDocument sdm;
        private final PgmModel pgm;

        Result(SdmDocument sdm, PgmModel pgm) {
            this.sdm = sdm;
            this.pgm = pgm;
        }

        /**
         * Returns the semantic projection.
         *
         * @return the SDM document
         */
        public SdmDocument getSdm() {
            return sdm;
        }

        /**
         * Returns the geometric projection.
         *
         * @return the PGM model
         */
        public PgmModel getPgm() {
            return pgm;
        }
    }

    private long sessionSeq;

    /**
     * Projects a document whose original file bytes are known (preferred: ids
     * are deterministic from content, IR spec §0.3).
     *
     * @param doc       the open document
     * @param fileBytes the raw bytes of the source file (null falls back to an
     *                  empty seed — ids remain deterministic per reading order
     *                  but are not tied to file content)
     * @return the SDM+PGM projection
     * @throws IOException if page content cannot be read
     */
    public Result read(Document doc, byte[] fileBytes) throws IOException {
        UUID ns = SdmIds.nsDoc(fileBytes != null ? fileBytes : new byte[0]);
        SdmDocument sdm = new SdmDocument();
        sdm.setNsDoc(ns);
        try {
            sdm.getMetadata().setTitle(doc.getInfo().getTitle());
            sdm.getMetadata().setAuthor(doc.getInfo().getAuthor());
        } catch (RuntimeException | IOException e) {
            LOG.fine(() -> "no document info: " + e);
        }
        PgmModel pgm = new PgmModel();
        PageCollection pages = doc.getPages();
        // Record the source page geometry so a PDF->HTML->PDF round-trip can
        // reproduce the original page size instead of defaulting to Letter — a
        // wide/landscape form otherwise gets crushed into portrait and explodes.
        if (pages.getCount() > 0) {
            try {
                Rectangle pr = pages.get(1).getRect();
                if (pr == null) pr = pages.get(1).getMediaBox();
                if (pr != null && pr.getWidth() > 1 && pr.getHeight() > 1) {
                    sdm.getMetadata().getCustom().put("page-width",
                            String.format(Locale.ROOT, "%.2f", pr.getWidth()));
                    sdm.getMetadata().getCustom().put("page-height",
                            String.format(Locale.ROOT, "%.2f", pr.getHeight()));
                }
            } catch (RuntimeException e) {
                LOG.fine(() -> "no page geometry: " + e);
            }
        }
        for (int i = 1; i <= pages.getCount(); i++) {
            readPage(pages.get(i), i - 1, ns, sdm, pgm);
        }
        return new Result(sdm, pgm);
    }

    /**
     * Projects a document, resolving the file bytes from
     * {@link Document#getSourcePath()} when available.
     *
     * @param doc the open document
     * @return the SDM+PGM projection
     * @throws IOException if page content cannot be read
     */
    public Result read(Document doc) throws IOException {
        byte[] bytes = null;
        String path = doc.getSourcePath();
        if (path != null) {
            try {
                bytes = Files.readAllBytes(Paths.get(path));
            } catch (IOException e) {
                LOG.fine(() -> "cannot re-read source file: " + e);
            }
        }
        return read(doc, bytes);
    }

    // ------------------------------------------------------------------ page

    /** One produced content box awaiting z assignment (sorted by opStart). */
    private static final class Entry {
        final int opStart;
        final PgmBox box;

        Entry(int opStart, PgmBox box) {
            this.opStart = opStart;
            this.box = box;
        }
    }

    /** Parser of the page currently being read — used to decode image XObjects. */
    private org.aspose.pdf.engine.parser.PDFParser currentParser;

    private void readPage(Page page, int pageIndex, UUID ns, SdmDocument sdm, PgmModel pgm)
            throws IOException {
        currentParser = page.getParser();
        // A conforming page has a /MediaBox (directly or inherited), but some
        // real-world files omit it entirely. Fall back to the MediaBox and then
        // to US Letter so the IR read does not NPE on a null rect.
        Rectangle rect = page.getRect();
        if (rect == null) {
            rect = page.getMediaBox();
        }
        if (rect == null) {
            rect = new Rectangle(0, 0, 612, 792);
        }
        PgmPage pp = new PgmPage(pageIndex, rect.getWidth(), rect.getHeight(), page.getRotate());
        pgm.addPage(pp);
        int pageObjNum = resolvePageObjNum(page, pageIndex);

        List<Entry> entries = new ArrayList<>();
        int childStart = sdm.getChildren().size();
        readText(page, pageIndex, pageObjNum, ns, sdm, entries);
        readGraphics(page, pageIndex, pageObjNum, ns, sdm, entries);

        // z-order = drawing order: stable sort by first operator index (§2.8-3).
        entries.sort((a, b) -> Integer.compare(a.opStart, b.opStart));
        int z = 0;
        for (Entry e : entries) {
            e.box.setZ(z++);
            pp.getBoxes().add(e.box);
            pgm.indexBox(e.box);
        }
        readAnnotations(page, pageIndex, ns, sdm, pgm, pp, z);

        // readText appends all text blocks, then readGraphics all image blocks —
        // so images land AFTER a page's text regardless of where they sit on the
        // page (a top logo would print below the body). Re-thread the figures
        // into the text flow by vertical position WITHOUT reordering the text
        // (text keeps ParagraphAbsorber's column-aware reading order). MUST run
        // after the boxes above are indexed into the PGM (geometry lookup).
        interleaveFiguresByGeometry(sdm, childStart, pgm);
        int pageTextChars = 0;
        for (PgmBox b : pp.getBoxes()) {
            if (b.getKind() == PgmBoxKind.TEXT && b.getData() instanceof TextBoxData) {
                String t = ((TextBoxData) b.getData()).getText();
                pageTextChars += t == null ? 0 : t.trim().length();
            }
        }
        markBackgroundFigures(sdm, childStart, rect.getWidth() * rect.getHeight(), pageTextChars);
    }

    // ------------------------------------------------------------------ text

    private void readText(Page page, int pageIndex, int pageObjNum, UUID ns,
                          SdmDocument sdm, List<Entry> entries) throws IOException {
        ParagraphAbsorber pa = new ParagraphAbsorber();
        pa.visit(page);
        List<PageMarkup> markups = pa.getPageMarkups();
        if (markups.isEmpty()) {
            return;
        }
        // Operator indices recorded on a fragment are relative to the stream
        // the text was extracted FROM. Text inside a Form XObject carries
        // indices into the FORM's stream, not the page's — a page-level
        // ContentRange would point into the wrong stream. Collect the page's
        // own content stream keys to tell the two cases apart.
        java.util.Set<PdfObjectKey> pageStreamKeys = pageContentStreamKeys(page);

        PageMarkup markup = markups.get(markups.size() - 1);
        int readingIndex = 0;
        for (MarkupSection section : markup.getSections()) {
            for (MarkupParagraph mp : section.getParagraphs()) {
                readingIndex = projectParagraph(mp, pageIndex, pageObjNum, pageStreamKeys,
                        ns, sdm, entries, readingIndex);
            }
        }
    }

    /** Object keys of the page's own content stream(s) (/Contents scalar or array). */
    private static java.util.Set<PdfObjectKey> pageContentStreamKeys(Page page) {
        java.util.Set<PdfObjectKey> keys = new java.util.HashSet<>();
        try {
            PdfBase contents = page.getPdfDictionary().get("Contents");
            if (contents instanceof PdfObjectReference) {
                PdfObjectReference ref = (PdfObjectReference) contents;
                keys.add(ref.getKey());
                contents = ref.dereference();
            }
            if (contents instanceof PdfArray) {
                PdfArray arr = (PdfArray) contents;
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase e = arr.get(i);
                    if (e instanceof PdfObjectReference) {
                        PdfObjectReference ref = (PdfObjectReference) e;
                        keys.add(ref.getKey());
                        e = ref.dereference();
                    }
                    if (e instanceof PdfStream && ((PdfStream) e).getObjectKey() != null) {
                        keys.add(((PdfStream) e).getObjectKey());
                    }
                }
            } else if (contents instanceof PdfStream
                    && ((PdfStream) contents).getObjectKey() != null) {
                keys.add(((PdfStream) contents).getObjectKey());
            }
        } catch (IOException e) {
            LOG.fine(() -> "cannot resolve page content streams: " + e);
        }
        return keys;
    }

    /** True when the fragment's recorded operator indices index the PAGE stream. */
    private static boolean isPageStreamFragment(TextFragment f,
                                                java.util.Set<PdfObjectKey> pageStreamKeys) {
        PdfStream src = f.getSourceContentStream();
        if (src == null) {
            return true; // extractor recorded no stream — page-level by construction
        }
        PdfObjectKey key = src.getObjectKey();
        if (key == null) {
            return true; // direct (un-keyed) stream can only be the page's own
        }
        return pageStreamKeys.contains(key);
    }

    private int projectParagraph(MarkupParagraph mp, int pageIndex, int pageObjNum,
                                 java.util.Set<PdfObjectKey> pageStreamKeys,
                                 UUID ns, SdmDocument sdm, List<Entry> entries,
                                 int readingIndex) {
        List<TextFragment> frags = new ArrayList<>();
        for (TextFragment f : mp.getFragments()) {
            if (f.getRectangle() != null) {
                frags.add(f);
            }
        }
        if (frags.isEmpty()) {
            return readingIndex;
        }

        // Paragraph provenance: the operator span of its PAGE-stream fragments.
        // Fragments extracted from Form-XObject streams carry indices into the
        // form's stream — never folded into a page-level ContentRange.
        int minOp = Integer.MAX_VALUE;
        int maxOp = -1;
        PdfObjectKey formKey = null;
        for (TextFragment f : frags) {
            int s = f.getSourceOperatorIndex();
            int e = f.getLastSourceOperatorIndex();
            if (isPageStreamFragment(f, pageStreamKeys)) {
                if (s >= 0 && e >= s) {
                    minOp = Math.min(minOp, s);
                    maxOp = Math.max(maxOp, e);
                }
            } else if (formKey == null && f.getSourceContentStream() != null) {
                formKey = f.getSourceContentStream().getObjectKey();
            }
        }

        Paragraph para = new Paragraph();
        String id;
        if (maxOp >= 0) {
            ContentRange pref = new ContentRange(pageObjNum, minOp, maxOp);
            para.setSourceRef(pref);
            id = SdmIds.nodeId(ns, pref);
        } else {
            // Form-hosted (or unindexed) paragraph: session id — an ObjectRef of
            // the form cannot mint the id, several paragraphs share the form.
            id = SdmIds.sessionNodeId(ns, "reader", sessionSeq++);
            if (formKey != null) {
                para.setSourceRef(new ObjectRef(formKey.getObjectNumber(),
                        formKey.getGenerationNumber()));
            }
        }
        para.setId(id);
        sdm.getChildren().add(para);

        int n = frags.size();
        for (int i = 0; i < n; i++) {
            TextFragment f = frags.get(i);
            Run run = new Run(f.getText(), toTextStyle(f));
            run.setId(id);
            para.getInline().add(run);

            Rectangle fr = f.getRectangle();
            PgmRect r = PgmRect.fromCorners(fr.getLLX(), fr.getLLY(), fr.getURX(), fr.getURY());
            boolean inPage = isPageStreamFragment(f, pageStreamKeys);
            int s = f.getSourceOperatorIndex();
            int e = f.getLastSourceOperatorIndex();
            SourceRef fref;
            if (inPage && s >= 0 && e >= s) {
                fref = new ContentRange(pageObjNum, s, e);
            } else if (!inPage && f.getSourceContentStream() != null
                    && f.getSourceContentStream().getObjectKey() != null) {
                // Text inside a Form XObject: editing it means regenerating THAT
                // object's stream, so the locator is the form's ObjectRef.
                PdfObjectKey k = f.getSourceContentStream().getObjectKey();
                fref = new ObjectRef(k.getObjectNumber(), k.getGenerationNumber());
            } else {
                fref = para.getSourceRef();
            }
            PgmBox box = new PgmBox(id, pageIndex, r, 0, PgmBoxKind.TEXT, fref);
            box.setPart(i, n);
            box.setReadingIndex(readingIndex++);
            double baseline = f.getPosition() != null ? f.getPosition().getYIndent() : fr.getLLY();
            TextState ts = f.getTextState();
            box.setData(new TextBoxData(baseline,
                    ts != null ? ts.getFontName() : null,
                    ts != null ? ts.getFontSize() : 0,
                    f.getText(), f.getRotation()));
            entries.add(new Entry(inPage && s >= 0 ? s : Integer.MAX_VALUE, box));
        }
        return readingIndex;
    }

    private static TextStyle toTextStyle(TextFragment f) {
        TextStyle style = new TextStyle();
        TextState ts = f.getTextState();
        if (ts != null) {
            String font = ts.getFontName() != null ? ts.getFontName() : f.getSourceFontName();
            style.setFontFamily(font);
            style.setFontSize(effectiveFontSize(f, ts.getFontSize()));
            if (font != null) {
                String lower = font.toLowerCase(Locale.ROOT);
                style.setBold(lower.contains("bold"));
                style.setItalic(lower.contains("italic") || lower.contains("oblique"));
            }
            style.setUnderline(ts.isUnderline());
            style.setStrikethrough(ts.isStrikeOut());
            style.setColor(packColor(ts.getForegroundColor()));
        }
        return style;
    }

    /**
     * The on-page displayed font size (points) for the SDM/HTML semantic layer.
     * <p>Some documents author text in a large coordinate space and shrink it with
     * a text/CTM scale (e.g. {@code Tf 150} rendered at ~10pt). {@link TextState}
     * carries the raw {@code Tf} operand, which is correct for exact operator
     * regeneration but wrong for CSS/reflow layout — a 150pt caption wraps a
     * paragraph across hundreds of pages. The fragment's on-page rectangle already
     * reflects the true scale, so when the declared size is far larger than the
     * rendered glyph-box height we fall back to that height.
     *
     * @param f        the source fragment
     * @param declared the raw {@code Tf} size from the text state
     * @return the effective on-page size, or {@code declared} when consistent
     */
    private static double effectiveFontSize(TextFragment f, double declared) {
        Rectangle r = f.getRectangle();
        if (r == null || declared <= 0) {
            return declared;
        }
        double rectH = r.getURY() - r.getLLY();
        // Normal text: rectH ~= declared (glyph box a bit taller than the em). Only
        // correct when declared dwarfs the on-page footprint (the scaled-space case).
        if (rectH > 0.5 && declared > 1.5 * rectH) {
            return rectH;
        }
        return declared;
    }

    /** Packs a public-API Color to 0xAARRGGBB (0 when null/unknown). */
    private static int packColor(org.aspose.pdf.Color color) {
        if (color == null) {
            return 0;
        }
        double[] c = color.getComponents();
        double rr;
        double gg;
        double bb;
        if (c == null) {
            return 0;
        } else if (c.length == 1) {
            rr = gg = bb = c[0];
        } else if (c.length == 3) {
            rr = c[0];
            gg = c[1];
            bb = c[2];
        } else if (c.length == 4) {
            rr = (1 - Math.min(1, c[0] + c[3]));
            gg = (1 - Math.min(1, c[1] + c[3]));
            bb = (1 - Math.min(1, c[2] + c[3]));
        } else {
            return 0;
        }
        int r = (int) Math.round(Math.max(0, Math.min(1, rr)) * 255);
        int g = (int) Math.round(Math.max(0, Math.min(1, gg)) * 255);
        int b = (int) Math.round(Math.max(0, Math.min(1, bb)) * 255);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // -------------------------------------------------- images, vectors, forms

    private void readGraphics(Page page, int pageIndex, int pageObjNum, UUID ns,
                              SdmDocument sdm, List<Entry> entries) throws IOException {
        OperatorCollection ops = page.getContents();
        PdfDictionary xobjects = page.getResources() != null
                ? page.getResources().getXObjects() : null;

        double[] ctm = {1, 0, 0, 1, 0, 0};
        Deque<double[]> stack = new ArrayDeque<>();
        Deque<Integer> colorStack = new ArrayDeque<>();
        Deque<Double> widthStack = new ArrayDeque<>();
        int fillColor = 0xFF000000; // PDF default fill colour is black
        double lineWidth = 1;       // §8.4.3.2 default
        List<double[]> pathPoints = new ArrayList<>();
        List<String> pathOps = new ArrayList<>();
        int pathStart = -1;
        double curX = 0;
        double curY = 0;
        double startX = 0;
        double startY = 0;

        for (int i = 0; i < ops.size(); i++) {
            Operator op = ops.getAt(i);
            String name = op.getName();
            List<PdfBase> od = op.getOperands();
            switch (name) {
                case "q":
                    stack.push(ctm.clone());
                    colorStack.push(fillColor);
                    widthStack.push(lineWidth);
                    break;
                case "Q":
                    if (!stack.isEmpty()) {
                        ctm = stack.pop();
                    }
                    if (!colorStack.isEmpty()) {
                        fillColor = colorStack.pop();
                    }
                    if (!widthStack.isEmpty()) {
                        lineWidth = widthStack.pop();
                    }
                    break;
                case "w":
                    if (od.size() >= 1) {
                        lineWidth = num(od.get(0));
                    }
                    break;
                case "rg":
                    if (od.size() >= 3) {
                        fillColor = rgbArgb(num(od.get(0)), num(od.get(1)), num(od.get(2)));
                    }
                    break;
                case "g":
                    if (od.size() >= 1) {
                        double v = num(od.get(0));
                        fillColor = rgbArgb(v, v, v);
                    }
                    break;
                case "k":
                    if (od.size() >= 4) {
                        double cc = num(od.get(0));
                        double mm = num(od.get(1));
                        double yy = num(od.get(2));
                        double kk = num(od.get(3));
                        fillColor = rgbArgb((1 - Math.min(1, cc + kk)),
                                (1 - Math.min(1, mm + kk)), (1 - Math.min(1, yy + kk)));
                    }
                    break;
                case "cm":
                    if (od.size() >= 6) {
                        ctm = mul(matrix(od), ctm);
                    }
                    break;
                case "m":
                    if (od.size() >= 2) {
                        if (pathStart < 0) {
                            pathStart = i;
                        }
                        curX = num(od.get(0));
                        curY = num(od.get(1));
                        startX = curX;
                        startY = curY;
                        pathPoints.add(apply(ctm, curX, curY));
                        pathOps.add(name);
                    }
                    break;
                case "l":
                    if (od.size() >= 2) {
                        if (pathStart < 0) {
                            pathStart = i;
                        }
                        curX = num(od.get(0));
                        curY = num(od.get(1));
                        pathPoints.add(apply(ctm, curX, curY));
                        pathOps.add(name);
                    }
                    break;
                case "c":
                case "v":
                case "y":
                    if (pathStart < 0) {
                        pathStart = i;
                    }
                    // Control points bound the curve — a safe bbox overestimate.
                    for (int k = 0; k + 1 < od.size(); k += 2) {
                        pathPoints.add(apply(ctm, num(od.get(k)), num(od.get(k + 1))));
                    }
                    if (od.size() >= 2) {
                        curX = num(od.get(od.size() - 2));
                        curY = num(od.get(od.size() - 1));
                    }
                    pathOps.add(name);
                    break;
                case "re":
                    if (od.size() >= 4) {
                        if (pathStart < 0) {
                            pathStart = i;
                        }
                        double x = num(od.get(0));
                        double y = num(od.get(1));
                        double w = num(od.get(2));
                        double h = num(od.get(3));
                        pathPoints.add(apply(ctm, x, y));
                        pathPoints.add(apply(ctm, x + w, y));
                        pathPoints.add(apply(ctm, x + w, y + h));
                        pathPoints.add(apply(ctm, x, y + h));
                        curX = x;
                        curY = y;
                        startX = x;
                        startY = y;
                        pathOps.add(name);
                    }
                    break;
                case "h":
                    curX = startX;
                    curY = startY;
                    pathOps.add(name);
                    break;
                case "W":
                case "W*":
                    // Clip-path definition: the following paint op (usually n)
                    // draws nothing by itself.
                    pathOps.add(name);
                    break;
                case "n":
                    pathPoints.clear();
                    pathOps.clear();
                    pathStart = -1;
                    break;
                case "S":
                case "s":
                case "f":
                case "F":
                case "f*":
                case "B":
                case "B*":
                case "b":
                case "b*":
                    if (!pathPoints.isEmpty() && pathStart >= 0) {
                        emitVector(pathPoints, pathOps, pageIndex, pageObjNum, pathStart, i,
                                name, ctm, fillColor, lineWidth, ns, sdm, entries);
                    }
                    pathPoints.clear();
                    pathOps.clear();
                    pathStart = -1;
                    break;
                case "BI":
                    emitInlineImage(ctm, pageIndex, pageObjNum, i, ns, sdm, entries);
                    break;
                case "Do":
                    if (!od.isEmpty() && od.get(0) instanceof PdfName && xobjects != null) {
                        emitXObject(((PdfName) od.get(0)).getName(), xobjects, ctm,
                                pageIndex, pageObjNum, i, ns, sdm, entries);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * True when a path built only of m/l moves traces an axis-aligned rectangle:
     * 4 distinct corners spanning exactly two X and two Y values (±0.5pt).
     */
    private static boolean isAxisAlignedRect(List<double[]> pts, List<String> drawing) {
        for (String o : drawing) {
            if (!"m".equals(o) && !"l".equals(o)) {
                return false;
            }
        }
        if (pts.size() < 4 || pts.size() > 5) {
            return false;
        }
        java.util.TreeSet<Long> xs = new java.util.TreeSet<>();
        java.util.TreeSet<Long> ys = new java.util.TreeSet<>();
        for (double[] p : pts) {
            xs.add(Math.round(p[0] * 2));
            ys.add(Math.round(p[1] * 2));
        }
        return xs.size() == 2 && ys.size() == 2;
    }

    /** Packs r,g,b in [0,1] to opaque 0xFFRRGGBB. */
    private static int rgbArgb(double r, double g, double b) {
        int ri = (int) Math.round(Math.max(0, Math.min(1, r)) * 255);
        int gi = (int) Math.round(Math.max(0, Math.min(1, g)) * 255);
        int bi = (int) Math.round(Math.max(0, Math.min(1, b)) * 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    private void emitVector(List<double[]> pts, List<String> pathOps, int pageIndex,
                            int pageObjNum, int opStart, int opEnd, String paintOp,
                            double[] ctm, int fillColor, double lineWidth, UUID ns,
                            SdmDocument sdm, List<Entry> entries) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (double[] p : pts) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        boolean stroked = paintOp.startsWith("S") || paintOp.startsWith("s")
                || paintOp.startsWith("B") || paintOp.startsWith("b");
        boolean filled = paintOp.startsWith("f") || paintOp.startsWith("F")
                || paintOp.startsWith("B") || paintOp.startsWith("b");
        if (stroked) {
            // A stroke paints lineWidth/2 beyond the path on each side (§8.4.3.2,
            // in USER space — scale by the CTM). Without this a thick vertical
            // LINE projects a 0-wide box, which region detection then discards
            // and the whole drawing vanishes from structural HTML (SQRCAP1).
            double sx = Math.hypot(ctm[0], ctm[1]);
            double sy = Math.hypot(ctm[2], ctm[3]);
            double half = Math.max(0, lineWidth) / 2;
            minX -= half * sx;
            maxX += half * sx;
            minY -= half * sy;
            maxY += half * sy;
        }

        VectorBoxData.Primitive prim = VectorBoxData.Primitive.PATH;
        double x1 = 0;
        double y1 = 0;
        double x2 = 0;
        double y2 = 0;
        List<String> drawing = new ArrayList<>();
        for (String o : pathOps) {
            if (!"W".equals(o) && !"W*".equals(o) && !"h".equals(o)) {
                drawing.add(o);
            }
        }
        if (drawing.size() == 2 && "m".equals(drawing.get(0)) && "l".equals(drawing.get(1))) {
            prim = VectorBoxData.Primitive.LINE;
            x1 = pts.get(0)[0];
            y1 = pts.get(0)[1];
            x2 = pts.get(1)[0];
            y2 = pts.get(1)[1];
        } else if (drawing.size() == 1 && "re".equals(drawing.get(0))) {
            prim = VectorBoxData.Primitive.RECT;
        } else if (isAxisAlignedRect(pts, drawing)) {
            // A rectangle authored as m·l·l·l(·h) rather than `re` — very common
            // for coloured boxes/banners. Its 4 corners span exactly 2 X and 2 Y.
            prim = VectorBoxData.Primitive.RECT;
        }

        ContentRange ref = new ContentRange(pageObjNum, opStart, opEnd);
        String id = SdmIds.nodeId(ns, ref);
        Opaque node = new Opaque(ref);
        node.setId(id);
        node.setRenderHint("vector");
        sdm.getChildren().add(node);

        PgmBox box = new PgmBox(id, pageIndex,
                PgmRect.fromCorners(minX, minY, maxX, maxY), 0, PgmBoxKind.VECTOR, ref);
        VectorBoxData data = new VectorBoxData(prim, x1, y1, x2, y2, stroked, filled);
        data.setCtm(ctm.clone());
        if (filled) {
            data.setFillColor(fillColor);
        }
        box.setData(data);
        entries.add(new Entry(opStart, box));
    }

    private void emitInlineImage(double[] ctm, int pageIndex, int pageObjNum, int opIndex,
                                 UUID ns, SdmDocument sdm, List<Entry> entries) {
        ContentRange ref = new ContentRange(pageObjNum, opIndex, opIndex);
        String id = SdmIds.nodeId(ns, ref);
        PgmRect placement = unitSquare(ctm);
        Figure fig = new Figure(null);
        fig.setId(id);
        fig.setSourceRef(ref);
        fig.getAttributes().put("inline-image", Boolean.TRUE);
        // Carry the on-page displayed size (points) so the HTML writer can size
        // the <img> to its PDF footprint instead of its intrinsic pixel size.
        fig.getAttributes().put("display-width", placement.getW());
        fig.getAttributes().put("display-height", placement.getH());
        sdm.getChildren().add(fig);

        PgmBox box = new PgmBox(id, pageIndex, placement, 0, PgmBoxKind.IMAGE, ref);
        box.setData(new ImageBoxData(null, new Matrix(ctm[0], ctm[1], ctm[2], ctm[3],
                ctm[4], ctm[5])));
        entries.add(new Entry(opIndex, box));
    }

    private void emitXObject(String xName, PdfDictionary xobjects, double[] ctm,
                             int pageIndex, int pageObjNum, int opIndex, UUID ns,
                             SdmDocument sdm, List<Entry> entries) {
        PdfBase xobj = xobjects.get(xName);
        if (xobj instanceof PdfObjectReference) {
            try {
                xobj = ((PdfObjectReference) xobj).dereference();
            } catch (IOException e) {
                LOG.fine(() -> "cannot dereference XObject " + xName + ": " + e);
                return;
            }
        }
        if (!(xobj instanceof PdfStream)) {
            return;
        }
        PdfStream stream = (PdfStream) xobj;
        String subtype = stream.getNameAsString("Subtype");
        ContentRange ref = new ContentRange(pageObjNum, opIndex, opIndex);
        String id = SdmIds.nodeId(ns, ref);

        if ("Image".equals(subtype)) {
            ResourceRef rref = storeImageResource(stream, xName, pageObjNum, sdm);
            PgmRect placement = unitSquare(ctm);
            Figure fig = new Figure(rref);
            fig.setId(id);
            fig.setSourceRef(ref);
            // Carry the on-page displayed size (points) so the HTML writer can
            // size the <img> to its PDF footprint, not its intrinsic pixels.
            fig.getAttributes().put("display-width", placement.getW());
            fig.getAttributes().put("display-height", placement.getH());
            sdm.getChildren().add(fig);

            PgmBox box = new PgmBox(id, pageIndex, placement, 0, PgmBoxKind.IMAGE, ref);
            box.setData(new ImageBoxData(rref, new Matrix(ctm[0], ctm[1], ctm[2], ctm[3],
                    ctm[4], ctm[5])));
            entries.add(new Entry(opIndex, box));
        } else if ("Form".equals(subtype)) {
            // Stage-1 scope: the form's PATH content is not linearised; the
            // placement is projected as one UNKNOWN box over the transformed /BBox.
            Opaque node = new Opaque(ref);
            node.setId(id);
            node.setRenderHint("form-xobject");
            sdm.getChildren().add(node);

            PgmRect bbox = formBBox(stream, ctm);
            PgmBox box = new PgmBox(id, pageIndex, bbox, 0, PgmBoxKind.UNKNOWN, ref);
            entries.add(new Entry(opIndex, box));

            // ...but IMAGES inside the form become first-class figures: office
            // producers routinely wrap the whole page in a single Form XObject,
            // and without entering it the page's photos simply vanish from the
            // model (the TEXT survives via the extractor's own form recursion —
            // images had no such path).
            emitFormImages(stream, applyFormMatrix(stream, ctm), pageIndex, opIndex,
                    ns, sdm, entries, 0,
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        }
    }

    /** Max Form XObject nesting for the inner-image projection. */
    private static final int MAX_FORM_IMAGE_DEPTH = 6;

    /**
     * Walks a Form XObject's content stream (tracking {@code q/Q/cm} on top of
     * the CTM the form was invoked with) and projects every inner image XObject
     * as a {@link Figure} + IMAGE box, recursing into nested forms.
     *
     * @param form        the form stream
     * @param baseCtm     the CTM in effect for the form's coordinate space
     *                    (invocation CTM x form /Matrix)
     * @param pageIndex   0-based page index
     * @param outerOpIndex the page-level operator index of the {@code Do} that
     *                    painted the (outermost) form — inner boxes keep this
     *                    index so reading-order interleave stays stable
     * @param active      forms on the current recursion path (cycle guard)
     */
    private void emitFormImages(PdfStream form, double[] baseCtm, int pageIndex, int outerOpIndex,
                                UUID ns, SdmDocument sdm, List<Entry> entries, int depth,
                                java.util.Set<PdfStream> active) {
        if (depth > MAX_FORM_IMAGE_DEPTH || !active.add(form)) {
            return;
        }
        try {
            byte[] data = form.getDecodedData();
            if (data == null || data.length == 0) {
                return;
            }
            OperatorCollection ops =
                    org.aspose.pdf.engine.parser.ContentStreamParser.parseToCollection(data);
            PdfDictionary xobjects = formXObjects(form);
            PdfObjectKey fk = form.getObjectKey();
            // Deterministic id owner: the form's object number (a page-wide form
            // is a real indirect object); synthetic fallback keeps ids unique.
            int ownerObj = fk != null && fk.getObjectNumber() > 0
                    ? fk.getObjectNumber() : 900_000 + outerOpIndex;

            double[] ctm = baseCtm.clone();
            Deque<double[]> stack = new ArrayDeque<>();
            for (int i = 0; i < ops.size(); i++) {
                Operator op = ops.getAt(i);
                List<PdfBase> od = op.getOperands();
                switch (op.getName()) {
                    case "q":
                        stack.push(ctm.clone());
                        break;
                    case "Q":
                        if (!stack.isEmpty()) {
                            ctm = stack.pop();
                        }
                        break;
                    case "cm":
                        if (od.size() >= 6) {
                            ctm = mul(matrix(od), ctm);
                        }
                        break;
                    case "Do": {
                        if (od.isEmpty() || !(od.get(0) instanceof PdfName) || xobjects == null) {
                            break;
                        }
                        PdfBase xo = xobjects.get(((PdfName) od.get(0)).getName());
                        if (xo instanceof PdfObjectReference) {
                            try {
                                xo = ((PdfObjectReference) xo).dereference();
                            } catch (IOException e) {
                                break;
                            }
                        }
                        if (!(xo instanceof PdfStream)) {
                            break;
                        }
                        PdfStream inner = (PdfStream) xo;
                        String sub = inner.getNameAsString("Subtype");
                        if ("Image".equals(sub)) {
                            ContentRange iref = new ContentRange(ownerObj, i, i);
                            String iid = SdmIds.nodeId(ns, iref);
                            ResourceRef rref = storeImageResource(inner,
                                    ((PdfName) od.get(0)).getName(), ownerObj, sdm);
                            PgmRect placement = unitSquare(ctm);
                            Figure fig = new Figure(rref);
                            fig.setId(iid);
                            fig.setSourceRef(new ObjectRef(ownerObj,
                                    fk != null ? fk.getGenerationNumber() : 0));
                            fig.getAttributes().put("display-width", placement.getW());
                            fig.getAttributes().put("display-height", placement.getH());
                            sdm.getChildren().add(fig);
                            PgmBox ibox = new PgmBox(iid, pageIndex, placement, 0,
                                    PgmBoxKind.IMAGE, iref);
                            ibox.setData(new ImageBoxData(rref, new Matrix(ctm[0], ctm[1],
                                    ctm[2], ctm[3], ctm[4], ctm[5])));
                            entries.add(new Entry(outerOpIndex, ibox));
                        } else if ("Form".equals(sub)) {
                            emitFormImages(inner, applyFormMatrix(inner, ctm), pageIndex,
                                    outerOpIndex, ns, sdm, entries, depth + 1, active);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.fine(() -> "form inner-image projection failed: " + e);
        } finally {
            active.remove(form);
        }
    }

    /** The form's /Resources /XObject dictionary, or null. */
    private static PdfDictionary formXObjects(PdfStream form) {
        PdfBase res = form.get("Resources");
        if (res instanceof PdfObjectReference) {
            try {
                res = ((PdfObjectReference) res).dereference();
            } catch (IOException e) {
                return null;
            }
        }
        if (!(res instanceof PdfDictionary)) {
            return null;
        }
        PdfBase xo = ((PdfDictionary) res).get("XObject");
        if (xo instanceof PdfObjectReference) {
            try {
                xo = ((PdfObjectReference) xo).dereference();
            } catch (IOException e) {
                return null;
            }
        }
        return xo instanceof PdfDictionary ? (PdfDictionary) xo : null;
    }

    /** Composes the form's /Matrix (default identity) with the invocation CTM. */
    private static double[] applyFormMatrix(PdfStream form, double[] ctm) {
        PdfBase m = form.get("Matrix");
        if (m instanceof PdfObjectReference) {
            try {
                m = ((PdfObjectReference) m).dereference();
            } catch (IOException e) {
                return ctm;
            }
        }
        if (m instanceof PdfArray && ((PdfArray) m).size() >= 6) {
            PdfArray a = (PdfArray) m;
            double[] fm = new double[6];
            for (int i = 0; i < 6; i++) {
                fm[i] = num(a.get(i));
            }
            return mul(fm, ctm);
        }
        return ctm;
    }

    private ResourceRef storeImageResource(PdfStream stream, String xName, int pageObjNum,
                                           SdmDocument sdm) {
        PdfObjectKey key = stream.getObjectKey();
        String resId = key != null && key.getObjectNumber() > 0
                ? "img:" + key.getObjectNumber() + ":" + key.getGenerationNumber()
                : "img:p" + pageObjNum + ":" + xName;
        if (sdm.getResources().get(resId) == null) {
            byte[] bytes;
            String mime;
            String filter = stream.getNameAsString("Filter");
            if ("DCTDecode".equals(filter)) {
                // Baseline JPEG: the raw stream bytes ARE a JPEG file, which
                // browsers render directly via data:image/jpeg.
                byte[] raw = stream.getEncodedData();
                bytes = raw != null ? raw : new byte[0];
                mime = "image/jpeg";
            } else {
                // Everything else — Flate/CCITT/raw samples (previously emitted
                // as application/octet-stream = a broken image in browsers) and
                // JPXDecode (image/jp2, not browser-supported) — is decoded to a
                // BufferedImage and re-encoded as PNG so it actually displays.
                byte[] png = decodeImageToPng(stream, xName);
                if (png != null) {
                    bytes = png;
                    mime = "image/png";
                } else {
                    byte[] raw = stream.getEncodedData();
                    bytes = raw != null ? raw : new byte[0];
                    mime = "JPXDecode".equals(filter) ? "image/jp2" : "application/octet-stream";
                }
            }
            Resource res = new Resource(Resource.Kind.IMAGE, bytes, mime);
            res.getMeta().put("width", stream.getInt("Width", 0));
            res.getMeta().put("height", stream.getInt("Height", 0));
            sdm.getResources().put(resId, res);
        }
        return new ResourceRef(resId);
    }

    /**
     * Decodes an image XObject to a {@link java.awt.image.BufferedImage} and
     * re-encodes it as PNG bytes, so non-JPEG PDF images (Flate/CCITT/raw
     * samples, JPEG 2000) become a browser-renderable {@code data:image/png}.
     * Returns {@code null} on any decode/encode failure (caller keeps raw bytes).
     */
    private byte[] decodeImageToPng(PdfStream stream, String name) {
        try {
            org.aspose.pdf.XImage xi = new org.aspose.pdf.XImage(stream, name, currentParser);
            java.awt.image.BufferedImage bi = xi.toBufferedImage();
            if (bi == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            if (!javax.imageio.ImageIO.write(bi, "png", out)) {
                return null;
            }
            return out.toByteArray();
        } catch (Throwable t) {
            LOG.fine(() -> "image decode->png failed for " + name + ": " + t);
            return null;
        }
    }

    private static PgmRect formBBox(PdfStream form, double[] ctm) {
        PdfBase bboxBase = form.get("BBox");
        double[] m = ctm;
        PdfBase matrixBase = form.get("Matrix");
        if (matrixBase instanceof PdfArray && ((PdfArray) matrixBase).size() >= 6) {
            double[] fm = new double[6];
            for (int i = 0; i < 6; i++) {
                fm[i] = num(((PdfArray) matrixBase).get(i));
            }
            m = mul(fm, ctm);
        }
        if (bboxBase instanceof PdfArray && ((PdfArray) bboxBase).size() >= 4) {
            PdfArray a = (PdfArray) bboxBase;
            double x0 = num(a.get(0));
            double y0 = num(a.get(1));
            double x1 = num(a.get(2));
            double y1 = num(a.get(3));
            double[] p1 = apply(m, x0, y0);
            double[] p2 = apply(m, x1, y0);
            double[] p3 = apply(m, x1, y1);
            double[] p4 = apply(m, x0, y1);
            double minX = Math.min(Math.min(p1[0], p2[0]), Math.min(p3[0], p4[0]));
            double minY = Math.min(Math.min(p1[1], p2[1]), Math.min(p3[1], p4[1]));
            double maxX = Math.max(Math.max(p1[0], p2[0]), Math.max(p3[0], p4[0]));
            double maxY = Math.max(Math.max(p1[1], p2[1]), Math.max(p3[1], p4[1]));
            return PgmRect.fromCorners(minX, minY, maxX, maxY);
        }
        return unitSquare(m);
    }

    // ------------------------------------------------------------ annotations

    private void readAnnotations(Page page, int pageIndex, UUID ns, SdmDocument sdm,
                                 PgmModel pgm, PgmPage pp, int zStart) {
        AnnotationCollection annots = page.getAnnotations();
        if (annots == null) {
            return;
        }
        int z = zStart;
        // AnnotationCollection.get is 1-based (Aspose API convention).
        for (int i = 1; i <= annots.getCount(); i++) {
            Annotation ann = annots.get(i);
            if (ann == null) {
                continue;
            }
            Rectangle r = ann.getRect();
            if (r == null) {
                continue;
            }
            String subtype = ann.getSubtype();
            PdfDictionary dict = ann.getPdfDictionary();
            PdfObjectKey key = dict != null ? dict.getObjectKey() : null;
            // Annotations live OUTSIDE the content stream: ObjectRef, never
            // ContentRange. Unsaved documents have no object numbers yet — a
            // deterministic synthetic number keeps ids stable per reading order.
            ObjectRef ref = key != null && key.getObjectNumber() > 0
                    ? new ObjectRef(key.getObjectNumber(), key.getGenerationNumber())
                    : new ObjectRef(900000 + pageIndex * 1000 + i, 0);
            String id = SdmIds.nodeId(ns, ref);

            // A Widget annotation is an interactive form field: project it as a
            // first-class FormField node (kind/name/value from the merged
            // widget+parent field dicts) so downstream converters can emit real
            // inputs. Every other annotation subtype stays an Opaque placeholder.
            WidgetFieldInfo fieldInfo = "Widget".equals(subtype)
                    ? WidgetFieldInfo.resolve(dict) : null;
            org.aspose.pdf.sdm.SdmBlock node;
            if (fieldInfo != null) {
                org.aspose.pdf.sdm.FormField ff =
                        new org.aspose.pdf.sdm.FormField(fieldInfo.getKind(), ref);
                fieldInfo.applyTo(ff);
                // Mirror the on-page footprint like Figure does, so HTML can
                // size the control without PGM access.
                ff.getAttributes().put("display-width", (double) (r.getURX() - r.getLLX()));
                ff.getAttributes().put("display-height", (double) (r.getURY() - r.getLLY()));
                node = ff;
            } else {
                Opaque o = new Opaque(ref);
                o.setRenderHint("annotation");
                if (subtype != null) {
                    o.getAttributes().put("subtype", subtype);
                }
                node = o;
            }
            node.setId(id);
            sdm.getChildren().add(node);

            boolean isField = "Widget".equals(subtype);
            PgmBox box = new PgmBox(id, pageIndex,
                    PgmRect.fromCorners(r.getLLX(), r.getLLY(), r.getURX(), r.getURY()),
                    z++, isField ? PgmBoxKind.FIELD : PgmBoxKind.ANNOTATION, ref);
            String fieldName = null;
            double[] quads = null;
            if (dict != null) {
                PdfBase t = dict.get("T");
                if (t != null) {
                    fieldName = String.valueOf(t);
                }
                PdfBase qp = dict.get("QuadPoints");
                if (qp instanceof PdfArray) {
                    PdfArray qa = (PdfArray) qp;
                    quads = new double[qa.size()];
                    for (int k = 0; k < qa.size(); k++) {
                        quads[k] = num(qa.get(k));
                    }
                }
            }
            box.setData(new AnnotBoxData(subtype, fieldName, quads));
            pp.getBoxes().add(box);
            pgm.indexBox(box);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int resolvePageObjNum(Page page, int pageIndex) {
        PdfDictionary dict = page.getPdfDictionary();
        PdfObjectKey key = dict != null ? dict.getObjectKey() : null;
        if (key != null && key.getObjectNumber() > 0) {
            return key.getObjectNumber();
        }
        // Unsaved in-memory documents: deterministic per page position.
        return 800000 + pageIndex + 1;
    }

    /**
     * Re-threads the {@link Figure} blocks added for one page into the page's
     * text flow by vertical position. Text (and any other non-figure block)
     * keeps its exact reading order; each figure is inserted just before the
     * first flow block that starts below the figure's top edge, so a top-of-page
     * logo precedes the body and a section icon lands next to its section.
     *
     * @param sdm   the document whose tail slice holds this page's blocks
     * @param start index of the first block added for this page
     * @param pgm   geometry (figure/text box tops)
     */
    private void interleaveFiguresByGeometry(SdmDocument sdm, int start, PgmModel pgm) {
        List<SdmBlock> children = sdm.getChildren();
        if (start >= children.size()) {
            return;
        }
        List<SdmBlock> flow = new ArrayList<>();
        List<Figure> figures = new ArrayList<>();
        for (SdmBlock b : children.subList(start, children.size())) {
            if (b instanceof Figure) {
                figures.add((Figure) b);
            } else {
                flow.add(b);
            }
        }
        if (figures.isEmpty()) {
            return;
        }
        List<SdmBlock> result = new ArrayList<>(flow);
        for (Figure fig : figures) {
            double fy = topYOf(fig, pgm);
            int insertAt = result.size();
            if (!Double.isNaN(fy)) {
                for (int i = 0; i < result.size(); i++) {
                    if (result.get(i) instanceof Figure) {
                        continue; // position relative to text/flow blocks only
                    }
                    double by = topYOf(result.get(i), pgm);
                    if (!Double.isNaN(by) && by < fy) {
                        insertAt = i;
                        break;
                    }
                }
            }
            result.add(insertAt, fig);
        }
        while (children.size() > start) {
            children.remove(children.size() - 1);
        }
        children.addAll(result);
    }

    /**
     * Flags a page's near-full-page images (≥55% of the page area) as
     * {@code background} — watermarks and page frames that must sit BEHIND the
     * text without consuming vertical flow space, so the HTML writer takes them
     * out of flow instead of pushing the body down by a whole page.
     *
     * <p>Only pages with substantial TEXT qualify: a watermark sits BEHIND text,
     * so a text-rich page with a full-page image is watermarked. An image-only
     * page (a SCAN, a full-page figure) has little/no text — there the full-page
     * image IS the content and must render fully, not as a faint 18% backdrop.</p>
     */
    private void markBackgroundFigures(SdmDocument sdm, int start, double pageArea,
                                       int pageTextChars) {
        if (pageArea <= 0 || pageTextChars < BACKGROUND_MIN_PAGE_TEXT) {
            return;
        }
        for (SdmBlock b : sdm.getChildren().subList(start, sdm.getChildren().size())) {
            if (!(b instanceof Figure)) {
                continue;
            }
            Object w = b.getAttributes().get("display-width");
            Object h = b.getAttributes().get("display-height");
            if (w instanceof Number && h instanceof Number
                    && ((Number) w).doubleValue() * ((Number) h).doubleValue() >= 0.55 * pageArea) {
                b.getAttributes().put("background", Boolean.TRUE);
            }
        }
    }

    /** Top edge (max Y, PDF coords) of a block's geometry, or NaN when unknown. */
    private static double topYOf(SdmBlock b, PgmModel pgm) {
        if (b.getId() == null) {
            return Double.NaN;
        }
        double top = Double.NaN;
        for (PgmBox box : pgm.byId(b.getId())) {
            double t = box.getRect().getTop();
            if (Double.isNaN(top) || t > top) {
                top = t;
            }
        }
        return top;
    }

    private static PgmRect unitSquare(double[] ctm) {
        double[] p1 = apply(ctm, 0, 0);
        double[] p2 = apply(ctm, 1, 0);
        double[] p3 = apply(ctm, 1, 1);
        double[] p4 = apply(ctm, 0, 1);
        double minX = Math.min(Math.min(p1[0], p2[0]), Math.min(p3[0], p4[0]));
        double minY = Math.min(Math.min(p1[1], p2[1]), Math.min(p3[1], p4[1]));
        double maxX = Math.max(Math.max(p1[0], p2[0]), Math.max(p3[0], p4[0]));
        double maxY = Math.max(Math.max(p1[1], p2[1]), Math.max(p3[1], p4[1]));
        return PgmRect.fromCorners(minX, minY, maxX, maxY);
    }

    private static double[] matrix(List<PdfBase> od) {
        double[] m = new double[6];
        for (int i = 0; i < 6; i++) {
            m[i] = num(od.get(i));
        }
        return m;
    }

    private static double[] mul(double[] m1, double[] m2) {
        return new double[]{
                m1[0] * m2[0] + m1[1] * m2[2],
                m1[0] * m2[1] + m1[1] * m2[3],
                m1[2] * m2[0] + m1[3] * m2[2],
                m1[2] * m2[1] + m1[3] * m2[3],
                m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
                m1[4] * m2[1] + m1[5] * m2[3] + m2[5]
        };
    }

    private static double[] apply(double[] m, double x, double y) {
        return new double[]{m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]};
    }

    private static double num(PdfBase v) {
        if (v instanceof PdfInteger) {
            return ((PdfInteger) v).longValue();
        }
        if (v instanceof PdfFloat) {
            return ((PdfFloat) v).doubleValue();
        }
        return 0;
    }
}
