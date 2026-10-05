package org.aspose.pdf.engine.text;

import org.aspose.pdf.Color;
import org.aspose.pdf.Matrix;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfInteger;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.font.FontRepository;
import org.aspose.pdf.engine.font.PdfFont;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextSegment;
import org.aspose.pdf.text.TextState;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Extracts text from PDF page content streams by processing text operators
 * (ISO 32000-1:2008, §9.4).
 * <p>
 * Maintains text state (font, size, position) across operator sequences and
 * produces {@link TextFragment} objects with position and styling information.
 * </p>
 */
public class TextExtractor {

    private static final Logger LOG = Logger.getLogger(TextExtractor.class.getName());

    private final FontRepository fontRepo;
    private final PDFParser parser;

    // Current state
    private PdfFont currentFont;
    private double fontSize;
    private double charSpacing;
    private double wordSpacing;
    private double horizontalScaling = 100;
    private double textLeading;
    private int renderMode;
    private double textRise;

    // Text matrix and text line matrix (ISO 32000, §9.4.2)
    private double[] textMatrix;    // Tm [a b c d e f]
    private double[] textLineMatrix; // Tlm

    // Current transformation matrix stack
    private double[] ctm;
    private final Deque<double[]> ctmStack = new ArrayDeque<>();

    // Current fill color (set by rg/g/k); saved/restored with q/Q so the
    // value active at flush time can be recorded as the fragment's
    // foreground color (PDFNEWNET_48777). Null until the first color op.
    private Color currentFillColor;
    private final Deque<Color> fillColorStack = new ArrayDeque<>();
    /** Fill colour space selected by `cs` — needed to interpret `sc`/`scn`. */
    private org.aspose.pdf.engine.colorspace.ColorSpaceBase currentFillColorSpace;
    private final Deque<org.aspose.pdf.engine.colorspace.ColorSpaceBase> fillCsStack =
            new ArrayDeque<>();

    // Results
    private final List<TextFragment> fragments = new ArrayList<>();
    // Thin filled rules painted in the content stream, in device space, used after
    // extraction to detect underline/strikeout decorations drawn as rules beneath/
    // through text (no font flag exists). Each decoration also carries the source
    // operators that drew it so an underline edit can remove them on save.
    private final List<Decoration> decorations = new ArrayList<>();
    // Rectangles added to the current path since the last paint operator.
    private final List<double[]> pendingPathRects = new ArrayList<>();
    // Line segments (device space [x1,y1,x2,y2]) added since the last paint;
    // a stroked horizontal segment is also an underline/strikeout rule.
    private final List<double[]> pendingPathLines = new ArrayList<>();
    // Content-stream operators of the current subpath (re/m/l), accumulated since
    // the last paint operator so a detected underline can be linked to its drawing ops.
    private final List<Operator> pendingPathOps = new ArrayList<>();

    /** A thin rule painted in the content stream, in device space [llx,lly,urx,ury],
     *  together with the operators that drew it and the collection that owns them. */
    private static final class Decoration {
        final double[] rect;
        final List<Operator> ops;
        final OperatorCollection coll;
        Decoration(double[] rect, List<Operator> ops, OperatorCollection coll) {
            this.rect = rect;
            this.ops = ops;
            this.coll = coll;
        }
    }
    private double pathCurX, pathCurY;
    private boolean hasPathPoint;
    private double lineWidthUser = 1.0;
    private StringBuilder currentText;
    private double currentX;
    private double currentY;
    private String currentFontName;
    // Embedding status of the active font (from its /FontDescriptor /FontFile*),
    // and whether its /BaseFont carries a subset prefix ("ABCDEF+"). Surfaced on
    // the extracted fragment's Font so getFont().isEmbedded()/isSubset() reflect
    // the document (Aspose parity).
    private boolean currentFontEmbedded;
    private boolean currentFontSubset;
    private Rectangle currentPageRect;
    private double[] fragmentStartTextMatrix;
    private double[] fragmentStartCtm;
    private double[] fragmentEndTextMatrix;
    private double[] fragmentEndCtm;
    // Exact per-character device-space X positions for the fragment currently
    // being assembled. fragCharX holds one entry per character boundary
    // (length == currentText.length()+1), so a substring at char offset i
    // starts at fragCharX[i] and ends at fragCharX[i+len]. This preserves the
    // true horizontal advance — including per-space word spacing (Tw) and
    // char spacing (Tc) — that proportional re-measurement in
    // TextFragmentAbsorber cannot reconstruct from the merged text alone.
    // Disabled (fragOffsetsValid=false) for kerning-split fragments (TJ numeric
    // adjustments) and non-1:1 byte→char decodes (CID), where the per-char
    // alignment cannot be guaranteed; those fall back to the approximation.
    private java.util.List<Double> fragCharX;
    private double fragTextX;
    private boolean fragOffsetsValid;

    // Source tracking: operator index of the first and last text-showing
    // operator contributing to the currently accumulating fragment.
    private int currentOperatorIndex = -1;
    private int firstTextOpIndex = -1;
    private int lastTextOpIndex = -1;
    private OperatorCollection currentSourceOperators;
    private PdfStream currentSourceStream;

    // Current page resources (for resolving XObject forms in Do operator)
    private Resources currentResources;

    // Tracks Form XObject streams currently being processed to prevent infinite recursion
    // when a Form XObject references itself (directly or through a chain).
    private final Set<PdfStream> activeFormXObjects = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Creates a TextExtractor.
     *
     * @param parser the PDF parser for resolving font references (may be null)
     */
    public TextExtractor(PDFParser parser) {
        this.parser = parser;
        this.fontRepo = new FontRepository();
    }

    /**
     * Extracts all text fragments from a page.
     *
     * @param page the PDF page
     * @return the list of extracted text fragments
     * @throws IOException if reading content stream or fonts fails
     */
    public List<TextFragment> extract(Page page) throws IOException {
        fragments.clear();
        resetState();

        Resources resources = page.getResources();
        this.currentResources = resources;
        this.currentPageRect = page.getRect();
        PdfDictionary fontsDict = resources != null ? resources.getFonts() : null;

        OperatorCollection ops = page.getContents();
        currentSourceOperators = ops;
        currentSourceStream = null;
        processOperators(ops, fontsDict);
        detectTextDecorations();
        fragments.addAll(page.getSyntheticTextFragments());

        return new ArrayList<>(fragments);
    }

    /**
     * Extracts all text from a page as a plain string.
     *
     * @param page the PDF page
     * @return the extracted text
     * @throws IOException if extraction fails
     */
    public String extractText(Page page) throws IOException {
        List<TextFragment> frags = extract(page);
        StringBuilder sb = new StringBuilder();
        for (TextFragment frag : frags) {
            sb.append(frag.getText());
        }
        return sb.toString();
    }

    /**
     * Detects underline and strikeout decorations drawn as thin filled rules and
     * sets the corresponding {@link TextState} flags on the fragments they cover.
     * A rule qualifies if it is thin (height ≤ 3pt), wider than tall, and overlaps
     * a fragment's horizontal span by at least 40%. Its vertical position relative
     * to the fragment box decides the kind: lower third → underline, middle → strikeout.
     * Decoration rects are in raw device space; fragment rects were normalised to the
     * page-box origin, so the same offset is applied here before comparing.
     */
    /**
     * Upper bound on retained decoration rules per page. A thin filled rule under
     * text is how underline/strikeout is drawn; but vector-heavy pages (scientific
     * figures, hatching) can emit millions of filled rectangles. Since the detection
     * loop is O(fragments × rules), we cap the rule list: beyond this many rules,
     * decoration detection is best-effort. Underline/strikeout flags are a cosmetic
     * enhancement — far better to drop a few than to spin for minutes on a page with
     * tens of thousands of fragments. (Corpus pathology: 57236.pdf produced 5,027,972
     * filled rects × 18,363 fragments ≈ 9×10^10 iterations.)
     */
    private static final int MAX_DECORATION_RECTS = 20_000;

    /** True when a device-space rect is a thin horizontal rule (wider than tall,
     *  height ≤ 3pt) — the only shape that can be an underline/strikeout. The test
     *  is translation-invariant, so it is equivalent whether applied here or after
     *  the page-origin offset in {@link #detectTextDecorations()}. */
    private static boolean isThinRule(double[] r) {
        double w = r[2] - r[0], h = r[3] - r[1];
        return h > 0 && h <= 3.0 && w > h;
    }

    /** Adds only the thin-horizontal-rule rectangles from {@code rects} to
     *  {@link #decorations}, up to {@link #MAX_DECORATION_RECTS}. Filtering at
     *  insertion keeps the detection loop's cost bounded by the number of genuine
     *  rules rather than every filled rectangle on the page. */
    private void addThinRuleRects(List<double[]> rects, List<Operator> ops, OperatorCollection coll) {
        for (double[] r : rects) {
            if (decorations.size() >= MAX_DECORATION_RECTS) return;
            if (isThinRule(r)) decorations.add(new Decoration(r, ops, coll));
        }
    }

    /** Converts pending near-horizontal stroked line segments into thin decoration
     *  rects (a stroked rule under/through text is an underline/strikeout). */
    private void addStrokedLineRules(List<Operator> ops, OperatorCollection coll) {
        if (pendingPathLines.isEmpty()) return;
        double scaleY = ctm != null ? Math.hypot(ctm[1], ctm[3]) : 1.0;
        double h = Math.max(lineWidthUser * scaleY, 0.4);
        for (double[] ln : pendingPathLines) {
            if (decorations.size() >= MAX_DECORATION_RECTS) return;
            if (Math.abs(ln[3] - ln[1]) > 0.6) continue; // not horizontal
            double cy = (ln[1] + ln[3]) / 2;
            decorations.add(new Decoration(new double[]{
                    Math.min(ln[0], ln[2]), cy - h / 2, Math.max(ln[0], ln[2]), cy + h / 2}, ops, coll));
        }
    }

    private void clearPath() {
        pendingPathRects.clear();
        pendingPathLines.clear();
        pendingPathOps.clear();
        hasPathPoint = false;
    }

    /** Snapshot of the current subpath's constructing operators plus the paint
     *  operator {@code paintOp} — the full set to remove if this subpath turns out
     *  to be an underline that is later edited off. */
    private List<Operator> subpathOps(Operator paintOp) {
        List<Operator> sub = new ArrayList<>(pendingPathOps.size() + 1);
        sub.addAll(pendingPathOps);
        sub.add(paintOp);
        return sub;
    }

    private void detectTextDecorations() {
        if (decorations.isEmpty() || fragments.isEmpty()) {
            return;
        }
        double offX = currentPageRect != null ? currentPageRect.getLLX() : 0;
        double offY = currentPageRect != null ? currentPageRect.getLLY() : 0;

        // Build a list of candidate rules (offset-applied, thin horizontal only)
        // sorted by vertical centre. decorations is already pre-filtered to
        // thin rules at insertion, but re-check defensively. Sorting by cy lets
        // each fragment binary-search just the rules in its vertical neighbourhood
        // instead of scanning all of them — the naive O(fragments × rules) loop is
        // catastrophic on vector-heavy pages (see MAX_DECORATION_RECTS).
        // Each rule keeps a back-index into `decorations` so a matched underline
        // can be linked to the operators that drew it (for removal on edit).
        int m = decorations.size();
        double[][] rules = new double[m][];   // [x0, x1, cy, decorationIndex]
        int n = 0;
        for (int di = 0; di < m; di++) {
            double[] r = decorations.get(di).rect;
            if (!isThinRule(r)) continue;
            rules[n++] = new double[]{r[0] - offX, r[2] - offX, ((r[1] + r[3]) / 2) - offY, di};
        }
        if (n == 0) return;
        java.util.Arrays.sort(rules, 0, n, (a, b) -> Double.compare(a[2], b[2]));
        double[] cys = new double[n];
        for (int i = 0; i < n; i++) cys[i] = rules[i][2];

        for (TextFragment frag : fragments) {
            Rectangle fr = frag.getRectangle();
            if (fr == null) {
                continue;
            }
            double fLLX = fr.getLLX(), fLLY = fr.getLLY(), fURX = fr.getURX(), fURY = fr.getURY();
            double fW = fURX - fLLX, fH = fURY - fLLY;
            if (fW <= 0 || fH <= 0) {
                continue;
            }
            // A decoration sits within roughly one line-height of the text box:
            // rel = (ruleCy - fLLY)/fH must be ≤ 0.70 (strikeout upper bound); the
            // lower bound (one line-height below the box) bounds the search and
            // suppresses cross-line false positives from rules far below.
            double loCy = fLLY - fH;
            double hiCy = fLLY + 0.70 * fH;
            int j = lowerBound(cys, n, loCy);
            for (; j < n && cys[j] <= hiCy; j++) {
                double[] ru = rules[j];
                double overlap = Math.min(fURX, ru[1]) - Math.max(fLLX, ru[0]);
                if (overlap < fW * 0.4) {
                    continue; // does not run under/through this fragment
                }
                double rel = (ru[2] - fLLY) / fH; // 0 = bottom, 1 = top
                if (rel < 0.33) {
                    frag.getTextState().setUnderline(true);
                    // Link the drawing operators so setUnderline(false) can strip them.
                    Decoration d = decorations.get((int) ru[3]);
                    frag.addSourceUnderline(d.ops, d.coll);
                } else if (rel <= 0.70) {
                    frag.getTextState().setStrikeOut(true);
                }
            }
        }
    }

    /** Returns the index of the first element of {@code cys[0..len)} that is
     *  ≥ {@code target} (i.e. a standard lower-bound binary search). */
    private static int lowerBound(double[] cys, int len, double target) {
        int lo = 0, hi = len;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cys[mid] < target) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private void processOperators(OperatorCollection ops, PdfDictionary fontsDict) throws IOException {
        for (int i = 0; i < ops.size(); i++) {
            Operator op = ops.getAt(i);
            currentOperatorIndex = i;
            try {
                processOperator(op, fontsDict);
            } catch (Exception e) {
                LOG.fine(() -> "Error processing operator " + op.getName() + ": " + e.getMessage());
            }
        }
    }

    private void processOperator(Operator op, PdfDictionary fontsDict) throws IOException {
        String name = op.getName();
        List<PdfBase> operands = op.getOperands();

        switch (name) {
            // -- Graphics state --
            case "q":
                ctmStack.push(ctm.clone());
                fillColorStack.push(currentFillColor);
                fillCsStack.push(currentFillColorSpace);
                break;
            case "Q":
                if (!ctmStack.isEmpty()) {
                    ctm = ctmStack.pop();
                }
                if (!fillColorStack.isEmpty()) {
                    currentFillColor = fillColorStack.pop();
                }
                if (!fillCsStack.isEmpty()) {
                    currentFillColorSpace = fillCsStack.pop();
                }
                break;

            // -- Fill color (non-stroking). A pending run is flushed FIRST so
            // its foreground color is the one that was active while its glyphs
            // were shown — a mid-BT color change starts a new fragment (Aspose
            // fragment boundaries include color changes).
            case "rg":
                if (operands.size() >= 3) {
                    flushPendingTextBeforeStateChange();
                    currentFillColor = Color.fromRgb(getNumber(operands.get(0)),
                            getNumber(operands.get(1)), getNumber(operands.get(2)));
                }
                break;
            case "g":
                if (operands.size() >= 1) {
                    flushPendingTextBeforeStateChange();
                    currentFillColor = Color.fromGray(getNumber(operands.get(0)));
                }
                break;
            case "k":
                if (operands.size() >= 4) {
                    flushPendingTextBeforeStateChange();
                    currentFillColor = Color.fromCmyk(getNumber(operands.get(0)),
                            getNumber(operands.get(1)), getNumber(operands.get(2)),
                            getNumber(operands.get(3)));
                }
                break;
            case "cs":
                // Fill colour space selection (§8.6.8): remember it so a following
                // `sc`/`scn` can be interpreted. Text coloured via `/CS0 cs …
                // scn` (the common producer idiom — this file paints every
                // heading/link colour this way) was otherwise left black because
                // only rg/g/k were tracked (corpus 35469 blue "AcroTeX.Net"
                // title lost its colour on PDF->HTML/DOCX).
                if (!operands.isEmpty()) {
                    try {
                        currentFillColorSpace =
                                org.aspose.pdf.engine.colorspace.ColorSpaceBase.resolve(
                                        operands.get(0), currentResources, parser);
                    } catch (RuntimeException e) {
                        currentFillColorSpace = null;
                    }
                }
                break;
            case "sc":
            case "scn":
                applyScnFillColor(operands);
                break;
            case "cm":
                if (operands.size() >= 6) {
                    double[] m = new double[6];
                    for (int i = 0; i < 6; i++) m[i] = getNumber(operands.get(i));
                    ctm = multiplyMatrix(m, ctm);
                }
                break;

            // -- Path construction / painting (underline & strikeout detection) --
            // We don't render paths, but a thin filled rectangle beneath or
            // through a text run is how PDFs draw underline/strikeout (no font
            // flag carries it). Capture rectangle subpaths in device space and,
            // on a fill paint operator, record them for post-extraction matching.
            case "w":
                if (operands.size() >= 1) lineWidthUser = getNumber(operands.get(0));
                break;
            case "re":
                if (operands.size() >= 4) {
                    double[] id = {1, 0, 0, 1, 0, 0};
                    double rx = getNumber(operands.get(0)), ry = getNumber(operands.get(1));
                    double rw = getNumber(operands.get(2)), rh = getNumber(operands.get(3));
                    double[] a = transformPoint(id, ctm, rx, ry);
                    double[] b = transformPoint(id, ctm, rx + rw, ry + rh);
                    pendingPathRects.add(new double[]{
                            Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                            Math.max(a[0], b[0]), Math.max(a[1], b[1])});
                    pendingPathOps.add(op);
                }
                break;
            case "m":
                if (operands.size() >= 2) {
                    double[] p = transformPoint(new double[]{1, 0, 0, 1, 0, 0}, ctm,
                            getNumber(operands.get(0)), getNumber(operands.get(1)));
                    pathCurX = p[0]; pathCurY = p[1]; hasPathPoint = true;
                    pendingPathOps.add(op);
                }
                break;
            case "l":
                if (operands.size() >= 2 && hasPathPoint) {
                    double[] p = transformPoint(new double[]{1, 0, 0, 1, 0, 0}, ctm,
                            getNumber(operands.get(0)), getNumber(operands.get(1)));
                    pendingPathLines.add(new double[]{pathCurX, pathCurY, p[0], p[1]});
                    pathCurX = p[0]; pathCurY = p[1];
                    pendingPathOps.add(op);
                }
                break;
            case "f": case "F": case "f*":
            case "b": case "b*": case "B": case "B*": {
                List<Operator> sub = subpathOps(op);
                addThinRuleRects(pendingPathRects, sub, currentSourceOperators);
                addStrokedLineRules(sub, currentSourceOperators);
                clearPath();
                break;
            }
            case "S": case "s": {
                List<Operator> sub = subpathOps(op);
                addStrokedLineRules(sub, currentSourceOperators);
                clearPath();
                break;
            }
            case "n":
                clearPath();
                break;

            // -- Text object --
            case "BT":
                textMatrix = new double[]{1, 0, 0, 1, 0, 0};
                textLineMatrix = new double[]{1, 0, 0, 1, 0, 0};
                currentText = new StringBuilder();
                break;
            case "ET":
                flushText();
                break;

            // -- Text state --
            case "Tf":
                if (operands.size() >= 2) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    String fontResourceName = ((PdfName) operands.get(0)).getName();
                    fontSize = getNumber(operands.get(1));
                    // Default to the resource alias; replace with the dictionary's
                    // /BaseFont entry when it is available so callers see the real
                    // font family ("CourierNew") instead of the per-page alias ("F1").
                    currentFontName = fontResourceName;
                    currentFontEmbedded = false;
                    currentFontSubset = false;
                    if (fontsDict != null) {
                        currentFont = fontRepo.getFont(fontsDict, fontResourceName, parser);
                        currentFontEmbedded = isFontEmbedded(currentFont);
                        org.aspose.pdf.engine.pdfobjects.PdfBase entry = fontsDict.get(fontResourceName);
                        if (entry instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
                            try {
                                entry = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) entry).dereference();
                            } catch (java.io.IOException e) {
                                entry = null;
                            }
                        }
                        if (entry instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary) {
                            String baseFont = ((org.aspose.pdf.engine.pdfobjects.PdfDictionary) entry).getNameAsString("BaseFont");
                            if (baseFont != null && !baseFont.isEmpty()) {
                                currentFontSubset = !baseFont.equals(stripSubsetPrefix(baseFont));
                                currentFontName = stripSubsetPrefix(baseFont);
                            }
                        }
                    }
                }
                break;
            case "Tc":
                if (operands.size() >= 1) charSpacing = getNumber(operands.get(0));
                break;
            case "Tw":
                if (operands.size() >= 1) wordSpacing = getNumber(operands.get(0));
                break;
            case "Tz":
                if (operands.size() >= 1) horizontalScaling = getNumber(operands.get(0));
                break;
            case "TL":
                if (operands.size() >= 1) textLeading = getNumber(operands.get(0));
                break;
            case "Tr":
                if (operands.size() >= 1) renderMode = (int) getNumber(operands.get(0));
                break;
            case "Ts":
                if (operands.size() >= 1) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    textRise = getNumber(operands.get(0));
                }
                break;

            // -- Text positioning --
            case "Td":
                if (operands.size() >= 2) {
                    double tx = getNumber(operands.get(0));
                    double ty = getNumber(operands.get(1));
                    if (ty != 0 && currentText != null && currentText.length() > 0) {
                        // New line — flush current text, add newline
                        flushText();
                        currentText = new StringBuilder();
                    }
                    if (ty == 0 && tx != 0 && currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    double[] translate = {1, 0, 0, 1, tx, ty};
                    textLineMatrix = multiplyMatrix(translate, textLineMatrix);
                    textMatrix = textLineMatrix.clone();
                }
                break;
            case "TD":
                if (operands.size() >= 2) {
                    double tx = getNumber(operands.get(0));
                    double ty = getNumber(operands.get(1));
                    textLeading = -ty;
                    if (ty != 0 && currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    if (ty == 0 && tx != 0 && currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    double[] translate = {1, 0, 0, 1, tx, ty};
                    textLineMatrix = multiplyMatrix(translate, textLineMatrix);
                    textMatrix = textLineMatrix.clone();
                }
                break;
            case "Tm":
                if (operands.size() >= 6) {
                    double[] tm = new double[6];
                    for (int i = 0; i < 6; i++) tm[i] = getNumber(operands.get(i));
                    if (currentText != null && currentText.length() > 0
                            && textMatrix != null
                            && matricesDiffer(textMatrix, tm)) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    textMatrix = tm;
                    textLineMatrix = tm.clone();
                }
                break;
            case "T*":
                if (currentText != null && currentText.length() > 0) {
                    flushText();
                    currentText = new StringBuilder();
                }
                double[] tstar = {1, 0, 0, 1, 0, -textLeading};
                textLineMatrix = multiplyMatrix(tstar, textLineMatrix);
                textMatrix = textLineMatrix.clone();
                break;

            // -- Text showing --
            case "Tj":
                if (operands.size() >= 1 && operands.get(0) instanceof PdfString) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showString((PdfString) operands.get(0));
                }
                break;
            case "TJ":
                if (operands.size() >= 1 && operands.get(0) instanceof PdfArray) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showStringArray((PdfArray) operands.get(0));
                }
                break;
            case "'":
                // Move to next line and show string
                doTStar();
                if (operands.size() >= 1 && operands.get(0) instanceof PdfString) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showString((PdfString) operands.get(0));
                }
                break;
            case "\"":
                // Set spacing, move, show string
                if (operands.size() >= 3) {
                    wordSpacing = getNumber(operands.get(0));
                    charSpacing = getNumber(operands.get(1));
                    doTStar();
                    if (operands.get(2) instanceof PdfString) {
                        if (currentText != null && currentText.length() > 0) {
                            flushText();
                            currentText = new StringBuilder();
                        }
                        showString((PdfString) operands.get(2));
                    }
                }
                break;

            // -- XObject (Form) invocation --
            case "Do":
                if (operands.size() >= 1 && operands.get(0) instanceof PdfName) {
                    String xobjName = ((PdfName) operands.get(0)).getName();
                    processFormXObject(xobjName, fontsDict);
                }
                break;

            default:
                // Ignore non-text operators
                break;
        }
    }

    /**
     * Processes a Form XObject invoked by the Do operator.
     * Recursively extracts text from the form's content stream.
     */
    private void processFormXObject(String xobjName, PdfDictionary pageFontsDict) {
        if (currentResources == null) return;

        try {
            PdfDictionary resDict = currentResources.getPdfDictionary();
            PdfBase xobjDictBase = resDict.get("XObject");
            if (xobjDictBase instanceof PdfObjectReference) {
                xobjDictBase = ((PdfObjectReference) xobjDictBase).dereference();
            }
            if (!(xobjDictBase instanceof PdfDictionary)) return;

            PdfBase formBase = ((PdfDictionary) xobjDictBase).get(xobjName);
            if (formBase instanceof PdfObjectReference) {
                formBase = ((PdfObjectReference) formBase).dereference();
            }
            if (!(formBase instanceof PdfStream)) return;

            PdfStream formStream = (PdfStream) formBase;
            String subtype = formStream.getNameAsString("Subtype");
            if (!"Form".equals(subtype)) return;

            // Cycle detection: skip if this Form XObject is already being processed
            // (prevents StackOverflow when forms reference themselves)
            if (!activeFormXObjects.add(formStream)) {
                LOG.fine(() -> "Skipping recursive Form XObject: " + xobjName);
                return;
            }

            try {
                // Get form's own Resources (or fall back to page resources)
                PdfBase formResBase = formStream.get("Resources");
                if (formResBase instanceof PdfObjectReference) {
                    formResBase = ((PdfObjectReference) formResBase).dereference();
                }
                PdfDictionary formFontsDict = pageFontsDict; // default to page fonts
                Resources formResources = currentResources;
                if (formResBase instanceof PdfDictionary) {
                    formResources = new Resources((PdfDictionary) formResBase);
                    PdfDictionary ff = formResources.getFonts();
                    if (ff != null) formFontsDict = ff;
                }

                // Parse form content stream
                byte[] data = formStream.getDecodedData();
                if (data == null || data.length == 0) return;

                OperatorCollection formOps = org.aspose.pdf.engine.parser.ContentStreamParser.parseToCollection(data);

                // Save and set resources context, then process
                Resources savedResources = this.currentResources;
                OperatorCollection savedSourceOperators = this.currentSourceOperators;
                PdfStream savedSourceStream = this.currentSourceStream;
                // ISO 32000-1 §8.10.1: painting a form XObject with `Do` is
                // equivalent to  q / cm(form /Matrix) / clip(/BBox) / content / Q.
                // The form's /Matrix maps form space into the coordinate system
                // in effect at the Do, so it MUST be concatenated onto the CTM.
                // Ignoring it collapses every glyph the form draws to raw form
                // space and loses its page placement — e.g. a form whose /Matrix
                // translates +773 in Y (51V13971 static-text layer) sinks all
                // its text off the bottom of the page. Save/restore the CTM and
                // text matrices so the form is isolated from the caller.
                double[] savedCtm = ctm != null ? ctm.clone() : null;
                double[] savedTextMatrix = textMatrix;
                double[] savedTextLineMatrix = textLineMatrix;
                double[] formMatrix = readMatrix(formStream.get("Matrix"));
                if (formMatrix != null) {
                    ctm = multiplyMatrix(formMatrix,
                            ctm != null ? ctm : new double[]{1, 0, 0, 1, 0, 0});
                }
                this.currentResources = formResources;
                this.currentSourceOperators = formOps;
                this.currentSourceStream = formStream;
                processOperators(formOps, formFontsDict);
                this.currentSourceOperators = savedSourceOperators;
                this.currentSourceStream = savedSourceStream;
                this.currentResources = savedResources;
                ctm = savedCtm;
                textMatrix = savedTextMatrix;
                textLineMatrix = savedTextLineMatrix;
            } finally {
                activeFormXObjects.remove(formStream);
            }

        } catch (Exception e) {
            LOG.fine(() -> "Error processing Form XObject " + xobjName + ": " + e.getMessage());
        }
    }

    private void showString(PdfString str) throws IOException {
        byte[] bytes = str.getBytes();
        String decoded;
        if (currentFont != null) {
            decoded = currentFont.decode(bytes);
        } else {
            // No font — use raw bytes as Latin-1
            decoded = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        }

        if (currentText == null) {
            currentText = new StringBuilder();
        }

        // Track source operators for this fragment — first is set once on the
        // opening text op, last advances with every subsequent text-showing op
        // so TextFragment.setText can scrub the full span of adjacent
        // kerning-split operators.
        if (currentText.length() == 0) {
            firstTextOpIndex = currentOperatorIndex;
        }
        lastTextOpIndex = currentOperatorIndex;

        // Record position before appending
        if (currentText.length() == 0 && textMatrix != null) {
            double[] pos = transformPoint(0, 0);
            currentX = pos[0];
            currentY = pos[1];
            fragmentStartTextMatrix = textMatrix.clone();
            fragmentStartCtm = ctm != null ? ctm.clone() : null;
            // Begin exact per-character offset tracking for this fragment.
            fragCharX = new java.util.ArrayList<>();
            fragCharX.add(currentX);
            fragTextX = 0;
            fragOffsetsValid = true;
        }

        // Capture per-character device-space X by walking the same glyph
        // advances advanceTextPosition() uses. Only reliable when the decode is
        // 1:1 (one byte == one decoded char); otherwise mark offsets invalid.
        if (fragOffsetsValid) {
            if (currentFont != null && fragmentStartTextMatrix != null
                    && decoded.length() == bytes.length) {
                double unitScale = currentFont.getWidthUnitScale();
                for (byte b : bytes) {
                    int code = b & 0xFF;
                    double w = currentFont.getWidth(code) * unitScale;
                    double adv = (w * fontSize + charSpacing) * (horizontalScaling / 100.0);
                    if (code == 32) {
                        adv += wordSpacing * (horizontalScaling / 100.0);
                    }
                    fragTextX += adv;
                    fragCharX.add(transformPoint(fragmentStartTextMatrix, fragmentStartCtm,
                            fragTextX, 0)[0]);
                }
            } else {
                fragOffsetsValid = false;
            }
        }

        currentText.append(decoded);

        // Advance text matrix by the width of the string
        advanceTextPosition(bytes);
        fragmentEndTextMatrix = textMatrix != null ? textMatrix.clone() : null;
        fragmentEndCtm = ctm != null ? ctm.clone() : null;
    }

    private void showStringArray(PdfArray arr) throws IOException {
        boolean splitBeforeNextString = false;
        for (int i = 0; i < arr.size(); i++) {
            PdfBase elem = arr.get(i);
            if (elem instanceof PdfString) {
                if (splitBeforeNextString && currentText != null && currentText.length() > 0) {
                    flushText();
                    currentText = new StringBuilder();
                }
                splitBeforeNextString = false;
                showString((PdfString) elem);
            } else if (elem instanceof PdfInteger || elem instanceof PdfFloat) {
                // Numeric adjustment: displacement in thousandths of text space unit.
                // Exact per-char offsets can't follow mid-fragment kerning jumps
                // (and synthetic-space insertion), so disable them for this
                // fragment and let the absorber fall back to approximation.
                fragOffsetsValid = false;
                double adjustment = getNumber(elem);
                // Negative = move right (advance), positive = move left (kerning)
                double tx = -adjustment / 1000.0 * fontSize * (horizontalScaling / 100.0);
                if (textMatrix != null) {
                    double[] translate = {1, 0, 0, 1, tx, 0};
                    textMatrix = multiplyMatrix(translate, textMatrix);
                }
                // Only inject a synthetic space when the TJ gap is clearly a word break,
                // not normal letter tracking. Normal tracking in modern PDFs runs -200..-300
                // per glyph-pair (PDFNET-36968) — those must not be treated as spaces.
                // Real word gaps are typically at least ~30% of the em.
                if (tx > fontSize * 0.3 && currentText != null && currentText.length() > 0
                        && currentText.charAt(currentText.length() - 1) != ' ') {
                    currentText.append(' ');
                }
                if (Math.abs(tx) > fontSize * 0.45) {
                    splitBeforeNextString = true;
                }
            }
        }
    }

    private void advanceTextPosition(byte[] bytes) {
        if (textMatrix == null || currentFont == null) return;

        double totalWidth = 0;
        double unitScale = currentFont.getWidthUnitScale();
        // Composite fonts (Type0 / Identity-H) encode each glyph as a 2-byte
        // code; simple fonts use 1 byte. Walking byte-by-byte for a composite
        // font would look up a width for each half of the code (e.g. the high
        // 0x00 byte), inflating the advance and shifting every fragment that
        // follows on the line. Match the renderer's code stride.
        int stride = currentFont.isComposite() ? 2 : 1;
        for (int i = 0; i + stride <= bytes.length; i += stride) {
            int code = stride == 2
                    ? (((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF))
                    : (bytes[i] & 0xFF);
            double w = currentFont.getWidth(code) * unitScale;
            totalWidth += (w * fontSize + charSpacing) * (horizontalScaling / 100.0);
            // Word spacing (Tw) applies only to the single-byte space code 32.
            if (stride == 1 && code == 32) {
                totalWidth += wordSpacing * (horizontalScaling / 100.0);
            }
        }

        double[] translate = {1, 0, 0, 1, totalWidth, 0};
        textMatrix = multiplyMatrix(translate, textMatrix);
    }

    /**
     * Flushes the pending run before a text-state change (fill color, ...) so
     * the flushed fragment keeps the state its glyphs were shown with —
     * mirrors the {@code Tf} handling.
     */
    private void flushPendingTextBeforeStateChange() {
        if (currentText != null && currentText.length() > 0) {
            flushText();
            currentText = new StringBuilder();
        }
    }

    private void flushText() {
        if (currentText == null || currentText.length() == 0) return;

        String text = currentText.toString();
        TextFragment fragment = new TextFragment(text);

        // Normalize extracted coordinates to the page-box lower-left origin.
        // PDF allows a MediaBox/CropBox with a non-zero (even negative) origin,
        // e.g. [0 -219 572 0]; content is then drawn at negative user-space Y.
        // Aspose reports text positions/rectangles relative to the page box's
        // lower-left corner (0-based), so a TextSearchOptions rectangle like
        // (0,0,1000,1000) matches. We subtract the origin here; for the common
        // [0 0 w h] case offX=offY=0, leaving coordinates unchanged.
        double offX = currentPageRect != null ? currentPageRect.getLLX() : 0;
        double offY = currentPageRect != null ? currentPageRect.getLLY() : 0;

        // Set position
        Position pos = new Position(currentX - offX, currentY - offY);
        fragment.setPosition(pos);

        // Set text state on the first segment
        TextState state = fragment.getTextState();
        state.setFontName(currentFontName);
        // Surface embedding/subset status (from the /FontDescriptor) so
        // getFont().isEmbedded()/isSubset() reflect the document. Stored as flags
        // rather than a Font object to avoid perturbing the rendering/layout paths
        // that read TextState.getFont().
        state.setFontEmbeddingInfo(currentFontEmbedded, currentFontSubset);
        // Report the EFFECTIVE font size: many producers set "/F1 1 Tf" and
        // carry the real size in the text matrix (e.g. Tm [0 14 -14 0 …] on a
        // rotated page) AND/OR a scaling CTM (e.g. "0.24 0 0 0.24 cm" then
        // "58 0 0 58 Tm /F1 1 Tf", real size 1×58×0.24 = 13.9pt — corpus 46005,
        // where ignoring the CTM blew every glyph up ~4×). Aspose reports the
        // visual size, i.e. Tf × vertical scale of the full text→device map.
        // The glyph height direction (0,1) maps through Tm to (c,d), then through
        // the CTM linear part; the scale is the magnitude of that final vector.
        double effectiveFontSize = fontSize;
        double[] tmForScale = fragmentStartTextMatrix != null ? fragmentStartTextMatrix : textMatrix;
        double[] ctmForScale = fragmentStartCtm != null ? fragmentStartCtm : ctm;
        if (tmForScale != null) {
            double vx = tmForScale[2];   // (0,1) through Tm -> (c,d)
            double vy = tmForScale[3];
            if (ctmForScale != null) {   // then through the CTM linear part
                double nx = ctmForScale[0] * vx + ctmForScale[2] * vy;
                double ny = ctmForScale[1] * vx + ctmForScale[3] * vy;
                vx = nx; vy = ny;
            }
            double combinedScale = Math.hypot(vx, vy);
            if (combinedScale > 1e-9 && Math.abs(combinedScale - 1.0) > 1e-9) {
                effectiveFontSize = fontSize * combinedScale;
            }
        }
        state.setFontSize(effectiveFontSize);
        // Content-stream edit math (TJ compensation) needs the raw Tf size —
        // see TextFragment#setSourceTfSize.
        fragment.setSourceTfSize(fontSize);
        state.setCharacterSpacing(charSpacing);
        state.setWordSpacing(wordSpacing);
        state.setHorizontalScaling(horizontalScaling);
        state.setRenderingMode(renderMode);
        // Derive the font style (bold/italic) from the resolved BaseFont name
        // so a styled run survives a save→reload round trip (PDFNEWNET_48777).
        state.setFontStyle(detectFontStyle(currentFontName));
        // Record the fill color active at flush time as the foreground color.
        if (currentFillColor != null) {
            state.setForegroundColor(currentFillColor);
        }

        // Set segment position and rectangle
        if (!fragment.getSegments().isEmpty()) {
            TextSegment seg = fragment.getSegments().get(0);
            seg.setPosition(pos);
            // Approximate the fragment bounds in text space, then map them through
            // the current text matrix and CTM. This keeps rectangle-based search
            // aligned with the actual rendered text even when the effective font
            // size comes from Tm scaling rather than Tf alone.
            double width = estimateTextWidth(text);
            double ascent = fontSize > 0 ? fontSize * 0.895 : 9.0;
            double descent = fontSize > 0 ? -fontSize * 0.2 : -2.0;
            if (currentFont != null && currentFont.getFontMetrics() != null) {
                double metricCapHeight = currentFont.getFontMetrics().getCapHeight();
                double metricAscent = currentFont.getFontMetrics().getAscent();
                double metricDescent = currentFont.getFontMetrics().getDescent();
                if (metricCapHeight > 0) {
                    ascent = Math.max(ascent, metricCapHeight / 1000.0 * fontSize);
                } else if (metricAscent > 0) {
                    ascent = Math.max(ascent, metricAscent / 1000.0 * fontSize);
                }
                if (metricDescent < 0) {
                    descent = metricDescent / 1000.0 * fontSize;
                }
            }
            Rectangle rect = transformTextBounds(fragmentStartTextMatrix, fragmentStartCtm,
                    fragmentEndTextMatrix != null ? fragmentEndTextMatrix : textMatrix,
                    fragmentEndCtm != null ? fragmentEndCtm : ctm,
                    width, descent + textRise, ascent + textRise);
            rect = clampToPage(rect);
            if ((offX != 0 || offY != 0) && rect != null) {
                rect = new Rectangle(rect.getLLX() - offX, rect.getLLY() - offY,
                        rect.getURX() - offX, rect.getURY() - offY);
            }
            seg.setRectangle(rect);
            fragment.setRectangle(rect);
        }

        // Determine the text baseline rotation in device space (BUG-EXT-WSPC):
        // map the text-space x-axis unit vector through Tm×CTM and quantize the
        // resulting angle to 0/90/180/270. Rotated text advances along Y and
        // stacks lines along X, which the absorber needs to group lines along
        // the correct axis instead of inserting a newline between every glyph.
        double[] rotTm = fragmentStartTextMatrix != null ? fragmentStartTextMatrix : textMatrix;
        double[] rotCtm = fragmentStartCtm != null ? fragmentStartCtm : ctm;
        if (rotTm != null) {
            double[] origin = transformPoint(rotTm, rotCtm, 0, 0);
            double[] axis = transformPoint(rotTm, rotCtm, 1, 0);
            double dx = axis[0] - origin[0];
            double dy = axis[1] - origin[1];
            if (dx != 0 || dy != 0) {
                fragment.setRotation(quantizeRotation(Math.toDegrees(Math.atan2(dy, dx))));
            }
        }

        // Attach exact per-character X positions (normalised to the page-box
        // origin, matching the fragment rectangle/position space) so the
        // absorber can locate sub-phrases precisely. Only when the boundary
        // count lines up with the text length and the fragment is upright —
        // for rotated text the X coordinate alone does not bound a sub-phrase.
        if (fragOffsetsValid && fragCharX != null
                && fragCharX.size() == text.length() + 1
                && fragment.getRotation() == 0) {
            double[] charX = new double[fragCharX.size()];
            for (int i = 0; i < charX.length; i++) {
                charX[i] = fragCharX.get(i) - offX;
            }
            fragment.setCharXPositions(charX);
        }

        // Record source operator range for content stream modification
        fragment.setSourceOperatorIndex(firstTextOpIndex);
        fragment.setLastSourceOperatorIndex(lastTextOpIndex);
        fragment.setSourceFontName(currentFontName);
        fragment.setSourceFont(currentFont);
        fragment.setSourceTextStart(0);
        fragment.setSourceTextLength(text.length());
        fragment.setSourceOperators(currentSourceOperators);
        fragment.setSourceContentStream(currentSourceStream);
        fragment.setSourceResources(currentResources != null ? currentResources.getPdfDictionary() : null);
        // Sprint 36: also record the source operators by identity so a
        // sibling fragment's later mutation can shift indices without
        // corrupting this fragment's reference (see TextFragment).
        if (currentSourceOperators != null) {
            if (firstTextOpIndex >= 0 && firstTextOpIndex < currentSourceOperators.size()) {
                fragment.setSourceOperator(currentSourceOperators.getAt(firstTextOpIndex));
            }
            if (lastTextOpIndex >= 0 && lastTextOpIndex < currentSourceOperators.size()) {
                fragment.setLastSourceOperator(currentSourceOperators.getAt(lastTextOpIndex));
            }
        }
        // Route later state mutations (setFontSize) back into the source ops
        // (PDFNEWNET-30639); a phrase-search match re-binds to itself.
        state.bindSourceFragment(fragment);

        fragments.add(fragment);
        currentText = new StringBuilder();
        firstTextOpIndex = -1;
        lastTextOpIndex = -1;
        fragmentStartTextMatrix = null;
        fragmentStartCtm = null;
        fragmentEndTextMatrix = null;
        fragmentEndCtm = null;
        fragCharX = null;
        fragTextX = 0;
        fragOffsetsValid = false;
    }

    /** Quantizes a device-space baseline angle (degrees) to {0,90,180,270}. */
    private static int quantizeRotation(double deg) {
        double d = deg % 360.0;
        if (d < 0) d += 360.0;
        int q = (int) Math.round(d / 90.0) * 90;
        return q % 360;
    }

    private double estimateTextWidth(String text) {
        if (currentFont == null) return text.length() * fontSize * 0.5;
        double width = 0;
        double unitScale = currentFont.getWidthUnitScale();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double w = currentFont.getWidth(c) * unitScale;
            width += w * fontSize * (horizontalScaling / 100.0);
        }
        return width;
    }

    private void doTStar() {
        if (currentText != null && currentText.length() > 0) {
            flushText();
            currentText = new StringBuilder();
        }
        double[] tstar = {1, 0, 0, 1, 0, -textLeading};
        textLineMatrix = multiplyMatrix(tstar, textLineMatrix);
        textMatrix = textLineMatrix.clone();
    }

    private double[] transformPoint(double x, double y) {
        return transformPoint(textMatrix, ctm, x, y);
    }

    private double[] transformPoint(double[] tm, double[] currentCtm, double x, double y) {
        if (tm == null) return new double[]{x, y};
        // Apply text matrix then CTM
        double tx = tm[0] * x + tm[2] * y + tm[4];
        double ty = tm[1] * x + tm[3] * y + tm[5];
        // Apply CTM
        double[] effectiveCtm = currentCtm != null ? currentCtm : new double[]{1, 0, 0, 1, 0, 0};
        double rx = effectiveCtm[0] * tx + effectiveCtm[2] * ty + effectiveCtm[4];
        double ry = effectiveCtm[1] * tx + effectiveCtm[3] * ty + effectiveCtm[5];
        return new double[]{rx, ry};
    }

    private Rectangle transformTextBounds(double[] startTm, double[] startCtm,
                                          double[] endTm, double[] endCtm,
                                          double width, double lowerY, double upperY) {
        double[] p0 = transformPoint(startTm, startCtm, 0, lowerY);
        double[] p2 = transformPoint(startTm, startCtm, 0, upperY);
        double[] p1;
        double[] p3;
        if (endTm != null) {
            p1 = transformPoint(endTm, endCtm, 0, lowerY);
            p3 = transformPoint(endTm, endCtm, 0, upperY);
        } else {
            p1 = transformPoint(startTm, startCtm, width, lowerY);
            p3 = transformPoint(startTm, startCtm, width, upperY);
        }
        double llx = Math.min(Math.min(p0[0], p1[0]), Math.min(p2[0], p3[0]));
        double lly = Math.min(Math.min(p0[1], p1[1]), Math.min(p2[1], p3[1]));
        double urx = Math.max(Math.max(p0[0], p1[0]), Math.max(p2[0], p3[0]));
        double ury = Math.max(Math.max(p0[1], p1[1]), Math.max(p2[1], p3[1]));
        return new Rectangle(llx, lly, urx, ury);
    }

    private Rectangle clampToPage(Rectangle rect) {
        if (rect == null || currentPageRect == null) {
            return rect;
        }
        double llx = Math.max(rect.getLLX(), currentPageRect.getLLX());
        double lly = Math.max(rect.getLLY(), currentPageRect.getLLY());
        double urx = Math.min(rect.getURX(), currentPageRect.getURX());
        double ury = Math.min(rect.getURY(), currentPageRect.getURY());
        if (urx < llx) {
            urx = llx;
        }
        if (ury < lly) {
            ury = lly;
        }
        return new Rectangle(llx, lly, urx, ury);
    }

    private boolean matricesDiffer(double[] left, double[] right) {
        if (left == null || right == null) {
            return left != right;
        }
        for (int i = 0; i < 6; i++) {
            if (Math.abs(left[i] - right[i]) > 0.01) {
                return true;
            }
        }
        return false;
    }

    private void resetState() {
        currentFont = null;
        fontSize = 0;
        charSpacing = 0;
        wordSpacing = 0;
        horizontalScaling = 100;
        textLeading = 0;
        renderMode = 0;
        textRise = 0;
        textMatrix = null;
        textLineMatrix = null;
        ctm = new double[]{1, 0, 0, 1, 0, 0};
        ctmStack.clear();
        currentText = null;
        currentX = 0;
        currentY = 0;
        currentFontName = null;
        currentFontEmbedded = false;
        currentFontSubset = false;
        currentPageRect = null;
        fragmentStartTextMatrix = null;
        fragmentStartCtm = null;
        fragmentEndTextMatrix = null;
        fragmentEndCtm = null;
        currentFillColor = null;
        fillColorStack.clear();
        currentFillColorSpace = null;
        fillCsStack.clear();
        decorations.clear();
        pendingPathRects.clear();
        pendingPathLines.clear();
        pendingPathOps.clear();
        hasPathPoint = false;
        lineWidthUser = 1.0;
        fontRepo.clear();
    }

    /**
     * Derives a {@link org.aspose.pdf.text.FontStyles} bitmask from a font
     * name. PDF standard-14 and most embedded fonts encode weight/slant in
     * the name ("Helvetica-Bold", "Arial,BoldItalic", "Times-Oblique"), which
     * is the only style signal preserved through a save→reload cycle for
     * non-embedded fonts.
     *
     * @param fontName the resolved BaseFont name (may be null)
     * @return Bold|Italic bitmask, or 0 (Regular) when no marker is present
     */
    private static int detectFontStyle(String fontName) {
        if (fontName == null) {
            return 0;
        }
        String n = fontName.toLowerCase();
        int style = 0;
        if (n.contains("bold")) {
            style |= org.aspose.pdf.text.FontStyles.Bold;
        }
        if (n.contains("italic") || n.contains("oblique")) {
            style |= org.aspose.pdf.text.FontStyles.Italic;
        }
        return style;
    }

    /**
     * Multiplies two 3x3 matrices represented as [a, b, c, d, e, f].
     * Result = m1 * m2 (post-multiply).
     */
    private static double[] multiplyMatrix(double[] m1, double[] m2) {
        return new double[]{
            m1[0] * m2[0] + m1[1] * m2[2],
            m1[0] * m2[1] + m1[1] * m2[3],
            m1[2] * m2[0] + m1[3] * m2[2],
            m1[2] * m2[1] + m1[3] * m2[3],
            m1[4] * m2[0] + m1[5] * m2[2] + m2[4],
            m1[4] * m2[1] + m1[5] * m2[3] + m2[5]
        };
    }

    private static double getNumber(PdfBase val) {
        if (val instanceof PdfInteger) return ((PdfInteger) val).intValue();
        if (val instanceof PdfFloat) return ((PdfFloat) val).doubleValue();
        return 0;
    }

    /**
     * Applies an {@code sc}/{@code scn} fill colour to {@link #currentFillColor},
     * mirroring the renderer's {@code applyAdvancedColor}: the numeric operands
     * are run through the colour space chosen by {@code cs} (a Separation/DeviceN
     * tint or ICC/Device components), falling back to a by-count interpretation
     * (3=RGB, 4=CMYK, 1=gray). A pattern name operand carries no solid colour and
     * leaves the current colour unchanged.
     */
    private void applyScnFillColor(List<PdfBase> operands) {
        if (operands.isEmpty()) {
            return;
        }
        if (operands.get(operands.size() - 1) instanceof PdfName) {
            return; // pattern fill — no solid text colour
        }
        int n = 0;
        for (PdfBase b : operands) {
            if (b instanceof PdfInteger || b instanceof PdfFloat) {
                n++;
            }
        }
        if (n == 0) {
            return;
        }
        double[] comps = new double[n];
        int ci = 0;
        for (PdfBase b : operands) {
            if (b instanceof PdfInteger || b instanceof PdfFloat) {
                comps[ci++] = getNumber(b);
            }
        }
        flushPendingTextBeforeStateChange();
        if (currentFillColorSpace != null
                && n == currentFillColorSpace.getNumberOfComponents()) {
            try {
                int rgb = currentFillColorSpace.toRGBInt(comps);
                currentFillColor = Color.fromArgb((rgb >> 16) & 0xFF,
                        (rgb >> 8) & 0xFF, rgb & 0xFF);
                return;
            } catch (RuntimeException ignore) {
                // fall through to by-count interpretation
            }
        }
        if (n == 3) {
            currentFillColor = Color.fromRgb(comps[0], comps[1], comps[2]);
        } else if (n == 4) {
            currentFillColor = Color.fromCmyk(comps[0], comps[1], comps[2], comps[3]);
        } else if (n == 1) {
            currentFillColor = Color.fromGray(comps[0]);
        }
    }

    /**
     * Reads a 6-element PDF transformation matrix [a b c d e f] from a
     * dictionary value (e.g. a form XObject's {@code /Matrix}). Dereferences an
     * indirect array and its elements. Returns {@code null} when the value is
     * absent or not a well-formed 6-number array, so callers can treat a
     * missing /Matrix as identity (per ISO 32000-1 §8.10.1 the default is the
     * identity matrix).
     */
    private static double[] readMatrix(PdfBase val) throws IOException {
        if (val instanceof PdfObjectReference) {
            val = ((PdfObjectReference) val).dereference();
        }
        if (!(val instanceof PdfArray)) return null;
        PdfArray arr = (PdfArray) val;
        if (arr.size() < 6) return null;
        double[] m = new double[6];
        for (int i = 0; i < 6; i++) {
            PdfBase e = arr.get(i);
            if (e instanceof PdfObjectReference) e = ((PdfObjectReference) e).dereference();
            m[i] = getNumber(e);
        }
        return m;
    }

    /**
     * Strips the 6-uppercase-letter subset prefix from a PDF BaseFont value
     * (ISO 32000-1:2008 §9.6.4). For example {@code "KQHRYC+hakuyoxingshu7000"}
     * → {@code "hakuyoxingshu7000"}. The PDF spec mandates exactly six
     * uppercase ASCII letters followed by a single {@code '+'} when a font
     * is subset-embedded; anything else is returned unchanged.
     */
    /**
     * Returns whether {@code font} is embedded, i.e. its font descriptor
     * carries a font program stream (/FontFile, /FontFile2, or /FontFile3).
     * For Type0 fonts the descriptor is that of the descendant CIDFont.
     */
    private static boolean isFontEmbedded(org.aspose.pdf.engine.font.PdfFont font) {
        if (font == null) return false;
        org.aspose.pdf.engine.font.FontDescriptor fd = font.getFontDescriptor();
        if (fd == null) return false;
        return fd.getFontFile() != null || fd.getFontFile2() != null || fd.getFontFile3() != null;
    }

    static String stripSubsetPrefix(String baseFont) {
        if (baseFont == null || baseFont.length() < 8) return baseFont;
        if (baseFont.charAt(6) != '+') return baseFont;
        for (int i = 0; i < 6; i++) {
            char c = baseFont.charAt(i);
            if (c < 'A' || c > 'Z') return baseFont;
        }
        return baseFont.substring(7);
    }
}
