package org.aspose.pdf.engine.text;

import org.aspose.pdf.Color;
import org.aspose.pdf.Matrix;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Resources;
import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.engine.cos.COSString;
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

    // Results
    private final List<TextFragment> fragments = new ArrayList<>();
    private StringBuilder currentText;
    private double currentX;
    private double currentY;
    private String currentFontName;
    private Rectangle currentPageRect;
    private double[] fragmentStartTextMatrix;
    private double[] fragmentStartCtm;
    private double[] fragmentEndTextMatrix;
    private double[] fragmentEndCtm;

    // Source tracking: operator index of the first and last text-showing
    // operator contributing to the currently accumulating fragment.
    private int currentOperatorIndex = -1;
    private int firstTextOpIndex = -1;
    private int lastTextOpIndex = -1;
    private OperatorCollection currentSourceOperators;
    private COSStream currentSourceStream;

    // Current page resources (for resolving XObject forms in Do operator)
    private Resources currentResources;

    // Tracks Form XObject streams currently being processed to prevent infinite recursion
    // when a Form XObject references itself (directly or through a chain).
    private final Set<COSStream> activeFormXObjects = Collections.newSetFromMap(new IdentityHashMap<>());

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
        COSDictionary fontsDict = resources != null ? resources.getFonts() : null;

        OperatorCollection ops = page.getContents();
        currentSourceOperators = ops;
        currentSourceStream = null;
        processOperators(ops, fontsDict);
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

    private void processOperators(OperatorCollection ops, COSDictionary fontsDict) throws IOException {
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

    private void processOperator(Operator op, COSDictionary fontsDict) throws IOException {
        String name = op.getName();
        List<COSBase> operands = op.getOperands();

        switch (name) {
            // -- Graphics state --
            case "q":
                ctmStack.push(ctm.clone());
                fillColorStack.push(currentFillColor);
                break;
            case "Q":
                if (!ctmStack.isEmpty()) {
                    ctm = ctmStack.pop();
                }
                if (!fillColorStack.isEmpty()) {
                    currentFillColor = fillColorStack.pop();
                }
                break;

            // -- Fill color (non-stroking). Recorded so the color active when
            // a text run is flushed becomes the fragment's foreground color.
            case "rg":
                if (operands.size() >= 3) {
                    currentFillColor = Color.fromRgb(getNumber(operands.get(0)),
                            getNumber(operands.get(1)), getNumber(operands.get(2)));
                }
                break;
            case "g":
                if (operands.size() >= 1) {
                    currentFillColor = Color.fromGray(getNumber(operands.get(0)));
                }
                break;
            case "k":
                if (operands.size() >= 4) {
                    currentFillColor = Color.fromCmyk(getNumber(operands.get(0)),
                            getNumber(operands.get(1)), getNumber(operands.get(2)),
                            getNumber(operands.get(3)));
                }
                break;
            case "cm":
                if (operands.size() >= 6) {
                    double[] m = new double[6];
                    for (int i = 0; i < 6; i++) m[i] = getNumber(operands.get(i));
                    ctm = multiplyMatrix(m, ctm);
                }
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
                    String fontResourceName = ((COSName) operands.get(0)).getName();
                    fontSize = getNumber(operands.get(1));
                    // Default to the resource alias; replace with the dictionary's
                    // /BaseFont entry when it is available so callers see the real
                    // font family ("CourierNew") instead of the per-page alias ("F1").
                    currentFontName = fontResourceName;
                    if (fontsDict != null) {
                        currentFont = fontRepo.getFont(fontsDict, fontResourceName, parser);
                        org.aspose.pdf.engine.cos.COSBase entry = fontsDict.get(fontResourceName);
                        if (entry instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
                            try {
                                entry = ((org.aspose.pdf.engine.cos.COSObjectReference) entry).dereference();
                            } catch (java.io.IOException e) {
                                entry = null;
                            }
                        }
                        if (entry instanceof org.aspose.pdf.engine.cos.COSDictionary) {
                            String baseFont = ((org.aspose.pdf.engine.cos.COSDictionary) entry).getNameAsString("BaseFont");
                            if (baseFont != null && !baseFont.isEmpty()) {
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
                if (operands.size() >= 1 && operands.get(0) instanceof COSString) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showString((COSString) operands.get(0));
                }
                break;
            case "TJ":
                if (operands.size() >= 1 && operands.get(0) instanceof COSArray) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showStringArray((COSArray) operands.get(0));
                }
                break;
            case "'":
                // Move to next line and show string
                doTStar();
                if (operands.size() >= 1 && operands.get(0) instanceof COSString) {
                    if (currentText != null && currentText.length() > 0) {
                        flushText();
                        currentText = new StringBuilder();
                    }
                    showString((COSString) operands.get(0));
                }
                break;
            case "\"":
                // Set spacing, move, show string
                if (operands.size() >= 3) {
                    wordSpacing = getNumber(operands.get(0));
                    charSpacing = getNumber(operands.get(1));
                    doTStar();
                    if (operands.get(2) instanceof COSString) {
                        if (currentText != null && currentText.length() > 0) {
                            flushText();
                            currentText = new StringBuilder();
                        }
                        showString((COSString) operands.get(2));
                    }
                }
                break;

            // -- XObject (Form) invocation --
            case "Do":
                if (operands.size() >= 1 && operands.get(0) instanceof COSName) {
                    String xobjName = ((COSName) operands.get(0)).getName();
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
    private void processFormXObject(String xobjName, COSDictionary pageFontsDict) {
        if (currentResources == null) return;

        try {
            COSDictionary resDict = currentResources.getCOSDictionary();
            COSBase xobjDictBase = resDict.get("XObject");
            if (xobjDictBase instanceof COSObjectReference) {
                xobjDictBase = ((COSObjectReference) xobjDictBase).dereference();
            }
            if (!(xobjDictBase instanceof COSDictionary)) return;

            COSBase formBase = ((COSDictionary) xobjDictBase).get(xobjName);
            if (formBase instanceof COSObjectReference) {
                formBase = ((COSObjectReference) formBase).dereference();
            }
            if (!(formBase instanceof COSStream)) return;

            COSStream formStream = (COSStream) formBase;
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
                COSBase formResBase = formStream.get("Resources");
                if (formResBase instanceof COSObjectReference) {
                    formResBase = ((COSObjectReference) formResBase).dereference();
                }
                COSDictionary formFontsDict = pageFontsDict; // default to page fonts
                Resources formResources = currentResources;
                if (formResBase instanceof COSDictionary) {
                    formResources = new Resources((COSDictionary) formResBase);
                    COSDictionary ff = formResources.getFonts();
                    if (ff != null) formFontsDict = ff;
                }

                // Parse form content stream
                byte[] data = formStream.getDecodedData();
                if (data == null || data.length == 0) return;

                OperatorCollection formOps = org.aspose.pdf.engine.parser.ContentStreamParser.parseToCollection(data);

                // Save and set resources context, then process
                Resources savedResources = this.currentResources;
                OperatorCollection savedSourceOperators = this.currentSourceOperators;
                COSStream savedSourceStream = this.currentSourceStream;
                this.currentResources = formResources;
                this.currentSourceOperators = formOps;
                this.currentSourceStream = formStream;
                processOperators(formOps, formFontsDict);
                this.currentSourceOperators = savedSourceOperators;
                this.currentSourceStream = savedSourceStream;
                this.currentResources = savedResources;
            } finally {
                activeFormXObjects.remove(formStream);
            }

        } catch (Exception e) {
            LOG.fine(() -> "Error processing Form XObject " + xobjName + ": " + e.getMessage());
        }
    }

    private void showString(COSString str) throws IOException {
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
        }

        currentText.append(decoded);

        // Advance text matrix by the width of the string
        advanceTextPosition(bytes);
        fragmentEndTextMatrix = textMatrix != null ? textMatrix.clone() : null;
        fragmentEndCtm = ctm != null ? ctm.clone() : null;
    }

    private void showStringArray(COSArray arr) throws IOException {
        boolean splitBeforeNextString = false;
        for (int i = 0; i < arr.size(); i++) {
            COSBase elem = arr.get(i);
            if (elem instanceof COSString) {
                if (splitBeforeNextString && currentText != null && currentText.length() > 0) {
                    flushText();
                    currentText = new StringBuilder();
                }
                splitBeforeNextString = false;
                showString((COSString) elem);
            } else if (elem instanceof COSInteger || elem instanceof COSFloat) {
                // Numeric adjustment: displacement in thousandths of text space unit
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
        for (byte b : bytes) {
            int code = b & 0xFF;
            double w = currentFont.getWidth(code) / 1000.0;
            totalWidth += (w * fontSize + charSpacing) * (horizontalScaling / 100.0);
            // Add word spacing for space character (code 32)
            if (code == 32) {
                totalWidth += wordSpacing * (horizontalScaling / 100.0);
            }
        }

        double[] translate = {1, 0, 0, 1, totalWidth, 0};
        textMatrix = multiplyMatrix(translate, textMatrix);
    }

    private void flushText() {
        if (currentText == null || currentText.length() == 0) return;

        String text = currentText.toString();
        TextFragment fragment = new TextFragment(text);

        // Set position
        Position pos = new Position(currentX, currentY);
        fragment.setPosition(pos);

        // Set text state on the first segment
        TextState state = fragment.getTextState();
        state.setFontName(currentFontName);
        state.setFontSize(fontSize);
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

        // Record source operator range for content stream modification
        fragment.setSourceOperatorIndex(firstTextOpIndex);
        fragment.setLastSourceOperatorIndex(lastTextOpIndex);
        fragment.setSourceFontName(currentFontName);
        fragment.setSourceTextStart(0);
        fragment.setSourceTextLength(text.length());
        fragment.setSourceOperators(currentSourceOperators);
        fragment.setSourceContentStream(currentSourceStream);
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

        fragments.add(fragment);
        currentText = new StringBuilder();
        firstTextOpIndex = -1;
        lastTextOpIndex = -1;
        fragmentStartTextMatrix = null;
        fragmentStartCtm = null;
        fragmentEndTextMatrix = null;
        fragmentEndCtm = null;
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
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            double w = currentFont.getWidth(c) / 1000.0;
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
        currentPageRect = null;
        fragmentStartTextMatrix = null;
        fragmentStartCtm = null;
        fragmentEndTextMatrix = null;
        fragmentEndCtm = null;
        currentFillColor = null;
        fillColorStack.clear();
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

    private static double getNumber(COSBase val) {
        if (val instanceof COSInteger) return ((COSInteger) val).intValue();
        if (val instanceof COSFloat) return ((COSFloat) val).doubleValue();
        return 0;
    }

    /**
     * Strips the 6-uppercase-letter subset prefix from a PDF BaseFont value
     * (ISO 32000-1:2008 §9.6.4). For example {@code "KQHRYC+hakuyoxingshu7000"}
     * → {@code "hakuyoxingshu7000"}. The PDF spec mandates exactly six
     * uppercase ASCII letters followed by a single {@code '+'} when a font
     * is subset-embedded; anything else is returned unchanged.
     */
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
