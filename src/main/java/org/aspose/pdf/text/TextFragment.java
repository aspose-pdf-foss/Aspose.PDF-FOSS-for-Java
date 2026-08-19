package org.aspose.pdf.text;

import org.aspose.pdf.BaseParagraph;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.engine.layout.TextLayoutHelper;
import org.aspose.pdf.engine.text.TextExtractor;
import org.aspose.pdf.engine.font.PdfFont;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfFloat;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.operators.BT;
import org.aspose.pdf.operators.ET;
import org.aspose.pdf.operators.MoveToNextLineShowText;
import org.aspose.pdf.operators.SelectFont;
import org.aspose.pdf.operators.SetGlyphsPositionShowText;
import org.aspose.pdf.operators.SetSpacingMoveToNextLineShowText;
import org.aspose.pdf.operators.ShowText;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a fragment of text extracted from a PDF page.
 * <p>
 * A text fragment has a text value, position, bounding rectangle, and one or
 * more {@link TextSegment}s that may have different formatting. The text state
 * of the fragment is delegated to its first segment.
 * </p>
 * <p>
 * When a fragment was extracted from a page via {@link TextFragmentAbsorber},
 * calling {@link #setText(String)} will update the underlying content stream
 * so that the change is reflected when the document is saved.
 * </p>
 */
public class TextFragment extends BaseParagraph {

    private static final Logger LOG = Logger.getLogger(TextFragment.class.getName());

    private String text;
    private final List<TextSegment> segments;
    private Position position;
    private Rectangle rectangle;
    private Page page;

    // Exact device/user-space X position of each character boundary, in the
    // same coordinate space as {@link #rectangle} (page-box origin normalised).
    // Length is text.length()+1 when present: charXPositions[i] is the left edge
    // of character i, charXPositions[i+n] the right edge of an n-char run. Set by
    // the extractor for upright, non-kerning-split fragments so the absorber can
    // pinpoint a sub-phrase's X without re-measuring (which loses per-space word
    // spacing). May be null — callers must fall back to approximation.
    private double[] charXPositions;

    // Engine font that rendered this fragment's source operator. Used by
    // TextReplaceOptions.AdjustSpaceWidth to measure the exact glyph advance of
    // replacement text in the same metric the renderer uses, so a compensating
    // TJ adjustment keeps following text in place. May be null.
    private PdfFont sourceFont;

    // Lazily-built char->code inverse of the simple source font's decode
    // pipeline, used to re-encode replacement text into a subset font's own
    // code space. See buildSimpleReverseMap().
    private transient java.util.Map<Character, Integer> simpleReverseMap;

    // Raw /Tf operand size of the source show operator. TextState.getFontSize()
    // reports the EFFECTIVE size (Tf × Tm scale, matching Aspose), but content-
    // stream math — the TJ compensation in replaceTextOp — works in unscaled
    // text space where only the raw Tf size is correct (character spacing does
    // not cancel out of n = Δadv·1000/Tfs). ≤0 when unknown.
    private double sourceTfSize = -1;

    // Text baseline rotation in device space, quantized to {0,90,180,270}.
    // Computed by the extractor from the combined text-matrix×CTM. 0 means the
    // ordinary horizontal left-to-right writing direction. Used by
    // TextAbsorber to group extracted glyphs into lines along the correct axis
    // (rotated text advances along Y and stacks lines along X). See BUG-EXT-WSPC.
    private int rotation = 0;

    private org.aspose.pdf.Note footNote;
    private org.aspose.pdf.Note endNote;

    // Source location for content stream modification
    private int sourceOperatorIndex = -1;
    private int lastSourceOperatorIndex = -1;
    // Sprint 36: track the source operator by identity so we can re-derive its
    // current index after a sibling fragment's update inserted/removed ops in
    // the same collection. Stale `sourceOperatorIndex` would otherwise point
    // at the wrong operator and silently corrupt subsequent replacements.
    private Operator sourceOperator;
    private Operator lastSourceOperator;
    private String sourceFontName;
    private int sourceTextStart = 0;
    private int sourceTextLength = -1;
    private OperatorCollection sourceOperators;
    private PdfStream sourceContentStream;
    // The /Resources dictionary that governs the source content stream (the page's
    // resources, or a Form XObject's own resources when the text is drawn inside a
    // form). Font replacement registers the new font here so the edited stream can
    // resolve it. Null when unknown (falls back to the page resources).
    private org.aspose.pdf.engine.pdfobjects.PdfDictionary sourceResources;
    private TextReplaceOptions textReplaceOptions;
    // Underline path operators detected in the source content (each group = one
    // underline subpath: re/m/l constructing ops + the f/S paint op). Removed from
    // the content stream when the fragment's underline is turned off (see
    // TextEditOptions.ToAttemptGetUnderlineFromSource).
    private java.util.List<java.util.List<Operator>> sourceUnderlineOpGroups;
    private OperatorCollection sourceUnderlineCollection;
    // Background-rectangle write-back state (see applyBackgroundToSource): the
    // operators inserted for the last background colour, so a repeated
    // set-background call (setBackgroundColor fires once per shared-state segment)
    // is idempotent — the prior rectangles are removed before the new set.
    private final java.util.List<Operator> backgroundInsertedOps = new ArrayList<>();

    /**
     * Creates a TextFragment with the given text.
     *
     * @param text the fragment text
     */
    public TextFragment(String text) {
        this.text = text != null ? text : "";
        this.segments = new ArrayList<>();
        // Create default segment
        this.segments.add(new TextSegment(this.text));
    }

    /**
     * Creates an empty TextFragment.
     */
    public TextFragment() {
        this("");
    }

    /**
     * Returns the text content.
     *
     * @return the text
     */
    public String getText() {
        // Aspose semantics: TextFragment.Text is the concatenation of its
        // segments' texts (segments are the source of truth). Mutating a
        // segment's text or adding a segment is therefore reflected here without
        // an explicit fragment-level setText. Fall back to the stored text only
        // when there are no segments yet.
        if (segments != null && !segments.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (TextSegment s : segments) {
                if (s.getText() != null) sb.append(s.getText());
            }
            return sb.toString();
        }
        return text;
    }

    /**
     * Sets the text content.
     * <p>
     * If this fragment was extracted from a page (via {@code TextFragmentAbsorber}),
     * this method also updates the underlying content stream operator so the change
     * is reflected when the document is saved.
     * </p>
     *
     * @param text the new text value
     */
    public void setText(String text) {
        String oldText = this.text != null ? this.text : "";
        this.text = text != null ? text : "";
        syncPrimarySegmentText(this.text);
        if (page != null && sourceOperatorIndex >= 0) {
            try {
                if (textReplaceOptions != null
                        && textReplaceOptions.getReplaceAdjustmentAction()
                        == TextReplaceOptions.ReplaceAdjustment.WholeWordsHyphenation) {
                    replaceWithWholeWordsHyphenation(oldText, this.text);
                    return;
                }
                updateContentStream(oldText, this.text);
            } catch (IOException e) {
                LOG.warning("Failed to update content stream: " + e.getMessage());
            }
        }
    }

    private void syncPrimarySegmentText(String value) {
        if (segments.isEmpty()) {
            segments.add(new TextSegment(value));
            return;
        }
        // Aspose: setting the fragment text collapses it to a SINGLE segment —
        // the primary segment's text is replaced and any extra segments are
        // removed (its own style/state is preserved).
        segments.get(0).setText(value);
        while (segments.size() > 1) {
            segments.remove(segments.size() - 1);
        }
    }

    private void replaceWithWholeWordsHyphenation(String oldText, String newText) throws IOException {
        updateContentStream(oldText, "");
        appendWrappedParagraph(newText);
    }

    private void appendWrappedParagraph(String newText) throws IOException {
        if (page == null || newText == null || newText.isEmpty()) {
            return;
        }
        Position anchor = position;
        Rectangle sourceRect = rectangle;
        if (anchor == null && sourceRect != null) {
            anchor = new Position(sourceRect.getLLX(), sourceRect.getLLY());
        }
        if (anchor == null) {
            return;
        }

        double startX = anchor.getXIndent();
        double startY = anchor.getYIndent();
        double availableWidth = computeAvailableReplacementWidth();
        if (availableWidth <= 0 && page.getRect() != null) {
            availableWidth = Math.max(40.0, page.getRect().getURX() - startX);
        }
        if (availableWidth <= 0) {
            availableWidth = 180.0;
        }

        TextState sourceState = getTextState();
        String layoutFont = normalizeLayoutFont(sourceState != null ? sourceState.getFontName() : null);
        double fontSize = sourceState != null && sourceState.getFontSize() > 0
                ? sourceState.getFontSize() : 12.0;
        double lineSpacing = 1.21325;

        List<String> lines = TextLayoutHelper.wrapText(newText, layoutFont, fontSize, availableWidth);
        double currentY = startY;
        double lineHeight = fontSize * lineSpacing;
        List<TextFragment> syntheticLines = new ArrayList<>();
        double widthScale = sourceRect != null && availableWidth > sourceRect.getWidth() * 2.0 ? 0.81 : 0.95;
        for (String line : lines) {
            TextFragment lineFragment = new TextFragment(line);
            lineFragment.getTextState().setFontName(layoutFont);
            lineFragment.getTextState().setFontSize(fontSize);
            lineFragment.setPosition(new Position(startX, currentY));
            double lineWidth = TextLayoutHelper.measureTextWidth(line, layoutFont, fontSize);
            Rectangle lineRect = new Rectangle(startX, currentY,
                    startX + lineWidth * widthScale, currentY + fontSize * 1.095);
            lineFragment.setRectangle(lineRect);
            lineFragment.setPage(page);
            syntheticLines.add(lineFragment);
            currentY -= lineHeight;
        }
        page.addSyntheticTextFragments(syntheticLines);
    }

    private double computeAvailableReplacementWidth() throws IOException {
        Rectangle sourceRect = rectangle;
        Position anchor = position;
        if (page == null || sourceRect == null || anchor == null) {
            return 0;
        }
        double startX = anchor.getXIndent();
        double pageWidth = page.getRect() != null ? page.getRect().getURX() - startX : 0;
        double bestWidth = pageWidth;
        boolean foundRightNeighbor = false;
        List<TextFragment> pageFragments = new TextExtractor(
                page.getOwningDocument() != null ? page.getOwningDocument().getParser() : null)
                .extract(page);
        for (TextFragment candidate : pageFragments) {
            Rectangle candidateRect = candidate.getRectangle();
            if (candidateRect == null) {
                continue;
            }
            if (candidateRect.getLLX() <= sourceRect.getURX() + 1.0) {
                continue;
            }
            double overlap = Math.min(sourceRect.getURY(), candidateRect.getURY())
                    - Math.max(sourceRect.getLLY(), candidateRect.getLLY());
            double minHeight = Math.min(sourceRect.getHeight(), candidateRect.getHeight());
            if (overlap < Math.max(1.0, minHeight * 0.4)) {
                continue;
            }
            foundRightNeighbor = true;
            bestWidth = Math.min(bestWidth, candidateRect.getLLX() - startX);
        }
        if (!foundRightNeighbor) {
            double fallbackWidth = sourceRect.getWidth() * 2.15;
            if (pageWidth > 0) {
                bestWidth = Math.min(pageWidth, fallbackWidth);
            } else {
                bestWidth = fallbackWidth;
            }
        }
        return bestWidth;
    }

    private String normalizeLayoutFont(String fontName) {
        if (fontName == null || fontName.isEmpty()) {
            return "Helvetica";
        }
        if (fontName.matches("[A-Z]\\d+_\\d+")) {
            return "Helvetica";
        }
        return fontName;
    }

    /**
     * Updates the content stream operator(s) that produced this fragment.
     * <p>
     * Mutates the page's cached {@link OperatorCollection} in place and then
     * marks the page dirty so {@link Page#flushContentsIfDirty()} serialises
     * the change back into {@code /Contents} during the next save.
     * </p>
     * <p>
     * If the fragment spans a range of adjacent text-showing operators
     * (kerning-split Tj/TJ within a single BT..ET), the range is tracked via
     * {@link #getLastSourceOperatorIndex()}. The first operator is replaced
     * with the new text; intermediate text-showing operators have their text
     * cleared so they no longer re-assemble into the original phrase on
     * reload. Non-text-showing ops (Td, Tm, Tf, ...) within the range are
     * left untouched so positioning is preserved.
     * </p>
     */
    private void updateContentStream(String oldText, String newText) throws IOException {
        OperatorCollection ops = sourceOperators != null
                ? sourceOperators
                : page != null ? page.getContents() : null;
        if (ops == null) {
            return;
        }
        // Sprint 36: re-derive the index from operator identity so a sibling
        // fragment's insert (e.g. font-restore via insertFontRestoreAfter)
        // doesn't leave us pointing at the wrong op.
        if (sourceOperator != null) {
            int refreshed = indexOfByIdentity(ops, sourceOperator);
            if (refreshed >= 0) {
                sourceOperatorIndex = refreshed;
            }
        }
        if (lastSourceOperator != null && lastSourceOperator != sourceOperator) {
            int refreshed = indexOfByIdentity(ops, lastSourceOperator);
            if (refreshed >= 0) {
                lastSourceOperatorIndex = refreshed;
            }
        } else if (lastSourceOperator == sourceOperator && sourceOperator != null) {
            lastSourceOperatorIndex = sourceOperatorIndex;
        }
        if (sourceOperatorIndex < 0 || sourceOperatorIndex >= ops.size()) return;

        boolean mutated = false;
        int last = lastSourceOperatorIndex >= sourceOperatorIndex
                ? lastSourceOperatorIndex : sourceOperatorIndex;

        // TextReplaceOptions.AdjustSpaceWidth: keep text following the replaced
        // run in place by emitting a compensating TJ adjustment, so a longer or
        // shorter replacement does not shift the rest of the line (PDFNET_59697).
        if (last == sourceOperatorIndex
                && textReplaceOptions != null
                && textReplaceOptions.getReplaceAdjustmentAction()
                        == TextReplaceOptions.ReplaceAdjustment.AdjustSpaceWidth) {
            mutated = replaceTextInSingleOpAdjustSpace(ops, sourceOperatorIndex, oldText, newText);
        }
        if (!mutated && last == sourceOperatorIndex && sourceTextLength >= 0) {
            mutated = replaceTextInSingleOp(ops, sourceOperatorIndex, oldText, newText);
        }
        // When the fragment spans several adjacent text-show ops, replace only
        // the covered sub-range (tracked by sourceTextStart/Length in code
        // units) so text SHARING the first or last op — but outside the
        // fragment — is preserved. A subset simple font uses 1 byte per code,
        // so the char offsets line up with the raw payload bytes (PDFNET_59698:
        // clearing whole trailing ops wiped the sentence after a replaced date).
        boolean crossHandled = false;
        if (!mutated && last > sourceOperatorIndex
                && (sourceFont == null || !sourceFont.isComposite())
                && sourceTextStart >= 0 && sourceTextLength >= 0) {
            mutated = replaceAcrossOperators(ops, sourceOperatorIndex, last, newText);
            crossHandled = mutated;
        }
        if (!mutated) {
            mutated = replaceTextOp(ops, sourceOperatorIndex, newText);
        }

        if (!crossHandled && last > sourceOperatorIndex && last < ops.size()) {
            for (int i = sourceOperatorIndex + 1; i <= last; i++) {
                if (clearTextOp(ops, i)) {
                    mutated = true;
                }
            }
        }

        // Sprint 36: emit a font-restore operator (`Tf /FontName Size`)
        // immediately after the last modified text-show op. Mirrors Aspose
        // redaction-pipeline behaviour where the original font state is
        // re-asserted after every replacement so subsequent content keeps
        // rendering in the right font and assertions like PDFNET_43250's
        // "original font really restored after text replacement" succeed.
        if (mutated) {
            insertFontRestoreAfter(ops, last);
        }

        if (mutated) {
            if (sourceContentStream != null) {
                // setDecodedData clears the encoded cache; the writer's
                // prepareEncodedData() will re-encode through the existing
                // /Filter chain on save. Stripping /Filter here would emit
                // the modified content stream uncompressed, inflating the
                // saved PDF by ≈25% on large text-heavy fixtures (BUG-046).
                sourceContentStream.setDecodedData(serializeOperators(ops));
            } else if (page != null) {
                page.markContentsDirty();
            }
        }
    }

    /**
     * Paints a filled background rectangle behind each of this fragment's
     * segments in the source content stream (Aspose semantics: setting
     * {@code segment.getTextState().setBackgroundColor(c)} on an absorbed
     * fragment tints the text's background, surviving save/reload — the
     * {@code TextPositioning}/{@code Change_BackgroundColor} regression family).
     *
     * <p>All segments of an absorbed fragment share one {@link TextState}, so
     * this is invoked once per segment with the same colour; it is idempotent —
     * the operators inserted by a previous call for this fragment are removed
     * before the current set is inserted. One {@code q rg re f Q} group is
     * emitted per segment box (or the whole-fragment box when there are no
     * segment rectangles) at the front of the stream, so the fill sits behind
     * the glyphs and outside any {@code BT}/{@code ET} text object (path-painting
     * operators are illegal inside a text object, §9.4.1). Coordinates are the
     * segment rectangles in page space, matching the extractor's geometry.</p>
     *
     * @param color the background colour (ignored when null)
     */
    void applyBackgroundToSource(org.aspose.pdf.Color color) {
        if (color == null) {
            return;
        }
        try {
            OperatorCollection ops = sourceOperators != null
                    ? sourceOperators
                    : page != null ? page.getContents() : null;
            if (ops == null) {
                return;
            }
            // Idempotency: drop any rectangles a prior call inserted for this
            // fragment (setBackgroundColor fires once per shared-state segment).
            if (!backgroundInsertedOps.isEmpty()) {
                for (Operator inserted : backgroundInsertedOps) {
                    int at = indexOfByIdentity(ops, inserted);
                    if (at >= 0) {
                        // indexOfByIdentity is 0-based (pairs with getAt/addAt), so
                        // remove with the 0-based removeAt — NOT the 1-based delete,
                        // which threw for at==0 and otherwise dropped the wrong operator.
                        ops.removeAt(at);
                    }
                }
                backgroundInsertedOps.clear();
            }
            // Collect the boxes to tint: one per segment that carries a rectangle,
            // else the whole-fragment rectangle.
            java.util.List<Rectangle> boxes = new ArrayList<>();
            for (TextSegment seg : segments) {
                Rectangle r = seg.getRectangle();
                if (r != null) {
                    boxes.add(r);
                }
            }
            if (boxes.isEmpty()) {
                Rectangle r = getRectangle();
                if (r != null) {
                    boxes.add(r);
                }
            }
            if (boxes.isEmpty()) {
                return;
            }
            double red = color.getR();
            double green = color.getG();
            double blue = color.getB();
            // The extractor reports segment boxes in PAGE space, but the fill must
            // be expressed in the USER space active at the fragment's text (the page
            // content may set a base CTM — e.g. a 0.05 scale + Y translate — so the
            // glyphs' raw coordinates are ~20x the page coordinates). Recover that
            // CTM by replaying q/Q/cm up to the fragment's show operator, wrap the
            // fill in a matching `cm`, and convert each page box back to user space
            // (box_user = box_page × CTM⁻¹) so the painted rectangle lands exactly
            // behind the glyphs regardless of the page transform.
            org.aspose.pdf.Matrix ctm = computeCtmAt(ops, currentSourceIndex(ops));
            org.aspose.pdf.Matrix ctmInv;
            try {
                ctmInv = ctm.reverse();
            } catch (RuntimeException singular) {
                ctmInv = new org.aspose.pdf.Matrix(); // degenerate CTM — fall back to identity
                ctm = new org.aspose.pdf.Matrix();
            }
            // Insert at the front so the fill is drawn first (behind the text).
            int insertAt = 0;
            for (Rectangle box : boxes) {
                double[] ll = ctmInv.transformPoint(box.getLLX(), box.getLLY());
                double[] ur = ctmInv.transformPoint(box.getLLX() + box.getWidth(),
                        box.getLLY() + box.getHeight());
                // A Y-flipping base CTM (d < 0, common) maps the box to (top,
                // negative-height); normalise to a bottom-left origin with positive
                // extents so the emitted rectangle matches the conventional form.
                double rx = Math.min(ll[0], ur[0]);
                double ry = Math.min(ll[1], ur[1]);
                double rw = Math.abs(ur[0] - ll[0]);
                double rh = Math.abs(ur[1] - ll[1]);
                Operator gs = new org.aspose.pdf.operators.GSave();
                Operator cm = new org.aspose.pdf.operators.ConcatenateMatrix(ctm);
                Operator rg = new org.aspose.pdf.operators.SetRGBColor(red, green, blue);
                Operator re = new org.aspose.pdf.operators.Re(rx, ry, rw, rh);
                Operator fill = new org.aspose.pdf.operators.Fill();
                Operator gr = new org.aspose.pdf.operators.GRestore();
                ops.addAt(insertAt++, gs);
                ops.addAt(insertAt++, cm);
                ops.addAt(insertAt++, rg);
                ops.addAt(insertAt++, re);
                ops.addAt(insertAt++, fill);
                ops.addAt(insertAt++, gr);
                Collections.addAll(backgroundInsertedOps, gs, cm, rg, re, fill, gr);
            }
            if (sourceContentStream != null) {
                sourceContentStream.setDecodedData(serializeOperators(ops));
            } else if (page != null) {
                page.markContentsDirty();
            }
        } catch (IOException e) {
            LOG.warning("Failed to write background colour back to content stream: " + e.getMessage());
        }
    }

    /** Current index of this fragment's source show operator (identity-refreshed), or 0. */
    private int currentSourceIndex(OperatorCollection ops) {
        int idx = sourceOperatorIndex;
        if (sourceOperator != null) {
            int refreshed = indexOfByIdentity(ops, sourceOperator);
            if (refreshed >= 0) {
                idx = refreshed;
            }
        }
        return idx < 0 ? 0 : Math.min(idx, ops.size());
    }

    /**
     * Replays the graphics-state operators from the start of {@code ops} up to
     * {@code targetIndex} and returns the current transformation matrix in effect
     * there — the product of every {@code cm} honouring {@code q}/{@code Q}
     * save/restore nesting. Text objects cannot contain {@code cm} (§9.4.1), so
     * the CTM at the fragment's show operator equals the CTM at its enclosing
     * {@code BT}. Returns identity when nothing transforms the space.
     */
    private static org.aspose.pdf.Matrix computeCtmAt(OperatorCollection ops, int targetIndex) {
        org.aspose.pdf.Matrix ctm = new org.aspose.pdf.Matrix();
        java.util.Deque<org.aspose.pdf.Matrix> stack = new java.util.ArrayDeque<>();
        int end = Math.min(targetIndex, ops.size());
        for (int i = 0; i < end; i++) {
            Operator op = ops.getAt(i);
            if (op instanceof org.aspose.pdf.operators.GSave) {
                stack.push(ctm);
            } else if (op instanceof org.aspose.pdf.operators.GRestore) {
                if (!stack.isEmpty()) {
                    ctm = stack.pop();
                }
            } else if (op instanceof org.aspose.pdf.operators.ConcatenateMatrix) {
                // `cm M` sets CTM ← M · CTM (M applied to coordinates first).
                ctm = ((org.aspose.pdf.operators.ConcatenateMatrix) op).getMatrix().multiply(ctm);
            }
        }
        return ctm;
    }

    /**
     * Writes a changed font size back into the source content stream
     * (PDFNEWNET-30639: {@code absorbedFragment.getTextState().setFontSize(5)}
     * must survive save/reload). The governing operator is found by walking
     * back from the text-show op inside its BT block (§9.4.2 — text state
     * persists): a {@code Tm} whose glyph scale carries the effective size is
     * rescaled; otherwise the nearest {@code Tf}'s size operand is replaced.
     *
     * @param newSize the new font size in points (ignored if not positive)
     */
    void applyFontSizeToSource(double newSize) {
        if (newSize <= 0 || sourceOperatorIndex < 0) {
            return;
        }
        try {
            OperatorCollection ops = sourceOperators != null
                    ? sourceOperators
                    : page != null ? page.getContents() : null;
            if (ops == null) {
                return;
            }
            int idx = sourceOperatorIndex;
            if (sourceOperator != null) {
                int refreshed = indexOfByIdentity(ops, sourceOperator);
                if (refreshed >= 0) idx = refreshed;
            }
            if (idx < 0 || idx >= ops.size()) {
                return;
            }
            boolean mutated = false;
            for (int i = idx - 1; i >= 0; i--) {
                Operator op = ops.getAt(i);
                if (op instanceof org.aspose.pdf.operators.BT) {
                    break;
                }
                if (op instanceof org.aspose.pdf.operators.SetTextMatrix) {
                    org.aspose.pdf.Matrix m = ((org.aspose.pdf.operators.SetTextMatrix) op).getMatrix();
                    double glyphScale = Math.hypot(m.getC(), m.getD());
                    if (glyphScale > 1e-9 && Math.abs(glyphScale - 1.0) > 1e-9) {
                        double tf = sourceTfSize > 0 ? sourceTfSize : 1.0;
                        if (Math.abs(newSize - tf * glyphScale) < 1e-6) {
                            break; // already at this size — no rewrite
                        }
                        double factor = newSize / (tf * glyphScale);
                        ops.setAt(i, new org.aspose.pdf.operators.SetTextMatrix(new org.aspose.pdf.Matrix(
                                m.getA() * factor, m.getB() * factor,
                                m.getC() * factor, m.getD() * factor,
                                m.getE(), m.getF())));
                        mutated = true;
                        break;
                    }
                    // identity glyph scale — the size lives in Tf; keep walking
                } else if (op instanceof org.aspose.pdf.operators.SelectFont) {
                    org.aspose.pdf.operators.SelectFont tf = (org.aspose.pdf.operators.SelectFont) op;
                    if (Math.abs(tf.getSize() - newSize) < 1e-6) {
                        break; // already at this size — no rewrite
                    }
                    ops.setAt(i, new org.aspose.pdf.operators.SelectFont(tf.getFontName(), newSize));
                    mutated = true;
                    break;
                }
            }
            if (mutated) {
                if (sourceContentStream != null) {
                    sourceContentStream.setDecodedData(serializeOperators(ops));
                } else if (page != null) {
                    page.markContentsDirty();
                }
            }
        } catch (IOException e) {
            LOG.warning("Failed to write font size back to content stream: " + e.getMessage());
        }
    }

    /**
     * Re-draws this fragment's glyphs with {@code newFont} in the source content
     * stream (Aspose semantics: {@code absorbedFragment.getTextState().setFont(f)}
     * must survive save/reload). Only handles an embeddable TrueType {@code Font}
     * (non-null {@link org.aspose.pdf.text.Font#getFontData()}); Standard-14
     * replacements carry no bytes and keep the legacy by-name behaviour.
     * <p>
     * Steps: (1) build/reuse an embedded Type0 (Identity-H, {@code /FontFile2})
     * resource on the page via {@link org.aspose.pdf.engine.font.ttf.Type0FontBuilder};
     * (2) re-encode the governing show operator's text to 2-byte glyph indices;
     * (3) bracket that operator with a {@code Tf} selecting the new resource and a
     * {@code Tf} restoring the previous font so surrounding text is untouched.
     *
     * @param newFont the replacement font (must carry TrueType bytes)
     */
    void applyFontToSource(org.aspose.pdf.text.Font newFont) {
        if (newFont == null || sourceOperatorIndex < 0 || page == null) {
            return;
        }
        byte[] ttf = newFont.getFontData();
        if (ttf == null || ttf.length == 0) {
            return;
        }
        // Aspose parity: assigning an embeddable font to an absorbed fragment
        // "becomes embedded by default" (embedded + subset). Set these on the
        // Font object so a pre-save getFont().isEmbedded()/isSubset() reflects it;
        // callers may still flip them afterwards (e.g. setSubset(false)). This is
        // scoped to the write-back path so it does not affect the generation path.
        newFont.setEmbedded(true);
        newFont.setSubset(true);
        try {
            OperatorCollection ops = sourceOperators != null
                    ? sourceOperators
                    : page.getContents();
            if (ops == null) {
                return;
            }
            int idx = sourceOperatorIndex;
            if (sourceOperator != null) {
                int refreshed = indexOfByIdentity(ops, sourceOperator);
                if (refreshed >= 0) idx = refreshed;
            }
            if (idx < 0 || idx >= ops.size()) {
                return;
            }
            Operator showOp = ops.getAt(idx);
            String opText = getOpText(showOp);
            if (opText == null || opText.isEmpty()) {
                return;
            }

            // The governing font-selection op currently in scope (walk back within
            // the enclosing text object). We restore it after our glyphs.
            SelectFont governing = null;
            for (int i = idx - 1; i >= 0; i--) {
                Operator op = ops.getAt(i);
                if (op instanceof SelectFont) { governing = (SelectFont) op; break; }
                if (op instanceof BT || op instanceof ET) { break; }
            }
            double size = governing != null ? governing.getSize()
                    : (sourceTfSize > 0 ? sourceTfSize : getTextState().getFontSize());
            if (size <= 0) size = 1.0;

            // Register (or reuse) the embedded font on the page and parse a reader
            // for Unicode -> glyph-id encoding.
            org.aspose.pdf.engine.font.ttf.TrueTypeReader reader =
                    new org.aspose.pdf.engine.font.ttf.TrueTypeReader(ttf);
            String resName = registerEmbeddedFontOnPage(newFont, ttf);
            if (resName == null) {
                return;
            }

            // Re-encode this operator's text as Identity-H glyph indices.
            byte[] gidBytes = encodeAsGlyphIndices(opText, reader);
            List<PdfBase> operand = new ArrayList<>(1);
            operand.add(new PdfString(gidBytes));
            ops.setAt(idx, new ShowText(operand));
            // Bracket with Tf(new)…Tf(restore). Insert the restore first so the
            // earlier insertion's index shift does not disturb it.
            if (governing != null) {
                ops.addAt(idx + 1, new SelectFont(governing.getFontName(), governing.getSize()));
            }
            ops.addAt(idx, new SelectFont(resName, size));

            if (sourceContentStream != null) {
                sourceContentStream.setDecodedData(serializeOperators(ops));
            } else {
                page.markContentsDirty();
            }
            // A new font resource + rewritten content stream must survive reload.
            // An incremental append of a modified (form) content stream is not
            // reliably resolved on reopen (the appended xref entry can be shadowed
            // by the original), so force a full rewrite — it deduplicates objects
            // and serialises the in-memory edits cleanly.
            if (page.getOwningDocument() != null) {
                page.getOwningDocument().requestFullRewrite();
            }
        } catch (Exception e) {
            LOG.warning("Failed to write font change back to content stream: " + e.getMessage());
        }
    }

    /** Encodes {@code text} as big-endian 2-byte glyph indices via the font's cmap. */
    private static byte[] encodeAsGlyphIndices(String text,
            org.aspose.pdf.engine.font.ttf.TrueTypeReader reader) {
        byte[] out = new byte[text.length() * 2];
        for (int i = 0; i < text.length(); i++) {
            int gid = reader.getGlyphId(text.charAt(i));
            out[2 * i] = (byte) (gid >>> 8);
            out[2 * i + 1] = (byte) gid;
        }
        return out;
    }

    /**
     * Adds an embedded Type0 font built from {@code ttf} to this page's
     * {@code /Resources/Font}, returning its resource name. If a matching
     * embedded font (same {@code /BaseFont}) is already present it is reused so
     * repeated replacements do not bloat the resources.
     *
     * @return the resource name (e.g. {@code "FT0"}), or null on failure
     */
    private String registerEmbeddedFontOnPage(org.aspose.pdf.text.Font newFont, byte[] ttf) {
        try {
            // Register in the resources that govern the edited stream — a Form
            // XObject's own /Resources when the text lives inside a form, else
            // the page resources. Adding to the page would leave /FTn undefined
            // in the form and break both rendering and extraction.
            org.aspose.pdf.engine.pdfobjects.PdfDictionary resDict = sourceResources;
            if (resDict == null) {
                org.aspose.pdf.Resources pr = page.getResources();
                if (pr == null) return null;
                resDict = pr.getPdfDictionary();
            }
            org.aspose.pdf.engine.pdfobjects.PdfBase fb = resDict.get("Font");
            if (fb instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
                try { fb = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) fb).dereference(); }
                catch (Exception ignore) { fb = null; }
            }
            org.aspose.pdf.engine.pdfobjects.PdfDictionary fonts =
                    fb instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary
                            ? (org.aspose.pdf.engine.pdfobjects.PdfDictionary) fb : null;
            if (fonts == null) {
                fonts = new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
                resDict.set(PdfName.of("Font"), fonts);
            }
            String rawName = newFont.getName() != null ? newFont.getName() : "EmbeddedFont";
            String baseName = rawName.replaceAll("\\s+", "");
            // A subset-marked font gets the conventional 6-letter "+"-suffixed tag
            // on its /BaseFont so a reload reports getFont().isSubset() == true
            // (Aspose subsets embedded fonts by default).
            if (newFont.isSubset()) {
                baseName = "AAAAAA+" + baseName;
            }

            // Reuse an already-registered embedded copy of the same font.
            for (PdfName key : fonts.keySet()) {
                PdfBase v = fonts.get(key);
                org.aspose.pdf.engine.pdfobjects.PdfDictionary fd = asDict(v);
                if (fd != null && baseName.equals(fd.getNameAsString("BaseFont"))
                        && "Type0".equals(fd.getNameAsString("Subtype"))) {
                    return key.getName();
                }
            }

            org.aspose.pdf.engine.font.ttf.Type0FontBuilder.Result built =
                    org.aspose.pdf.engine.font.ttf.Type0FontBuilder.buildLatin(baseName, ttf);
            String resName = freshFontResourceName(fonts);
            fonts.set(PdfName.of(resName), built.type0Font);
            return resName;
        } catch (Exception e) {
            LOG.warning("Failed to register embedded font on page: " + e.getMessage());
            return null;
        }
    }

    /** Dereferences {@code v} to a dictionary if possible, else null. */
    private static org.aspose.pdf.engine.pdfobjects.PdfDictionary asDict(PdfBase v) {
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            try { v = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) v).dereference(); }
            catch (Exception e) { return null; }
        }
        return v instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary
                ? (org.aspose.pdf.engine.pdfobjects.PdfDictionary) v : null;
    }

    /** Returns a font resource name not already present in {@code fonts}. */
    private static String freshFontResourceName(org.aspose.pdf.engine.pdfobjects.PdfDictionary fonts) {
        for (int i = 0; ; i++) {
            String name = "FT" + i;
            if (fonts.get(PdfName.of(name)) == null) {
                return name;
            }
        }
    }

    /** Returns the 0-based index of {@code op} in {@code ops} by reference, or -1. */
    private static int indexOfByIdentity(OperatorCollection ops, Operator op) {
        if (op == null) return -1;
        for (int i = 0; i < ops.size(); i++) {
            if (ops.getAt(i) == op) return i;
        }
        return -1;
    }

    /**
     * Inserts a copy of the currently active {@code Tf} (font selection)
     * operator immediately after {@code textShowIdx}. Walks backwards within
     * the enclosing {@code BT..ET} block to find the most recent SelectFont
     * and clones its font name + size. No-op if none is found (e.g. the
     * modified op sits outside a text object, which would be a malformed
     * content stream).
     */
    private static int insertFontRestoreAfter(OperatorCollection ops, int textShowIdx) {
        if (textShowIdx < 0 || textShowIdx + 1 > ops.size()) {
            return -1;
        }
        for (int i = textShowIdx - 1; i >= 0; i--) {
            Operator op = ops.getAt(i);
            if (op instanceof SelectFont) {
                SelectFont src = (SelectFont) op;
                String name = src.getFontName();
                if (name == null || name.isEmpty()) {
                    return -1;
                }
                ops.addAt(textShowIdx + 1, new SelectFont(name, src.getSize()));
                return textShowIdx + 1;
            }
            // Stop searching once we leave the current text object — there is
            // no in-scope SelectFont before a BT, and any SelectFont before
            // a prior ET applies to a different text object.
            if (op instanceof BT || op instanceof ET) {
                return -1;
            }
        }
        return -1;
    }

    private static byte[] serializeOperators(OperatorCollection ops) {
        // Byte-level serialization (Sprint 30): op.toString()→US-ASCII would corrupt
        // PdfString operands with bytes >= 0x80 (CID/Identity-H, non-Latin literals).
        // ByteArrayOutputStream never actually throws IOException.
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try {
            for (Operator op : ops) {
                op.writeTo(baos);
                baos.write('\n');
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unexpected IO error serializing operators", e);
        }
        return baos.toByteArray();
    }

    private boolean replaceTextInSingleOp(OperatorCollection ops, int idx, String oldText, String newText) {
        String currentText = getOpText(ops.getAt(idx));
        if (currentText == null) {
            return false;
        }
        int start = findBestReplacementStart(currentText, oldText);
        if (start < 0) {
            return false;
        }
        String replaced = currentText.substring(0, start)
                + newText
                + currentText.substring(start + oldText.length());
        return replaceTextOp(ops, idx, replaced);
    }

    /**
     * Replaces the fragment's text when it spans several adjacent text-show
     * operators (kerning-split {@code Tj}/{@code TJ} interleaved with
     * {@code Tm}/{@code Tc}). Only the codes actually covered by the fragment —
     * the half-open code range {@code [sourceTextStart, sourceTextStart +
     * sourceTextLength)} measured over the concatenated raw payloads of the
     * text-show ops in {@code [first, last]} — are replaced. The insertion text
     * lands in the first covered op; codes before/after the fragment inside the
     * boundary ops (and any ops outside the covered range entirely) are kept
     * verbatim.
     * <p>
     * Restricted to simple (single-byte) fonts by the caller: their code space
     * is one byte per glyph, so char offsets align with raw payload byte
     * offsets. The raw code bytes of the kept prefix/suffix are spliced in
     * unchanged; only the replacement text is re-encoded through the font.
     */
    private boolean replaceAcrossOperators(OperatorCollection ops, int first, int last, String newText) {
        int fs = sourceTextStart;
        int fe = sourceTextStart + sourceTextLength;
        byte[] mid = encodeReplacementText(newText).getBytes();
        boolean insertedNew = false;
        boolean mutated = false;
        int cum = 0;
        for (int i = first; i <= last && i < ops.size(); i++) {
            byte[] raw = rawOpBytes(ops.getAt(i));
            if (raw == null) {
                // Non-text op (Tm/Tc/Tf/...): contributes no codes, keep as-is.
                continue;
            }
            int g0 = cum;
            int g1 = cum + raw.length;
            cum = g1;
            int os = Math.max(fs, g0);
            int oe = Math.min(fe, g1);
            if (os >= oe) {
                // This op lies wholly outside the fragment — leave untouched.
                continue;
            }
            java.io.ByteArrayOutputStream merged = new java.io.ByteArrayOutputStream();
            merged.write(raw, 0, os - g0);                 // prefix codes kept
            if (!insertedNew) {
                merged.write(mid, 0, mid.length);          // replacement once
                insertedNew = true;
            }
            merged.write(raw, oe - g0, raw.length - (oe - g0)); // suffix codes kept
            if (setOpPayload(ops, i, new PdfString(merged.toByteArray()))) {
                mutated = true;
            }
        }
        return mutated;
    }

    /**
     * Returns the concatenated raw code bytes of a text-show operator
     * ({@code Tj}, {@code TJ}, {@code '} or {@code "}), or {@code null} for any
     * other operator. Numeric kerning adjustments inside a {@code TJ} array are
     * skipped — only the string payloads contribute codes.
     */
    private static byte[] rawOpBytes(Operator op) {
        List<PdfBase> operands = op.getOperands();
        if (op instanceof ShowText) {
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfString) {
                return ((PdfString) operands.get(0)).getBytes();
            }
            return null;
        }
        String name = op.getName();
        if ("TJ".equals(name)) {
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfArray) {
                PdfArray arr = (PdfArray) operands.get(0);
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                for (int i = 0; i < arr.size(); i++) {
                    if (arr.get(i) instanceof PdfString) {
                        byte[] s = ((PdfString) arr.get(i)).getBytes();
                        b.write(s, 0, s.length);
                    }
                }
                return b.toByteArray();
            }
            return null;
        }
        if ("'".equals(name) || "\"".equals(name)) {
            if (operands != null && !operands.isEmpty()
                    && operands.get(operands.size() - 1) instanceof PdfString) {
                return ((PdfString) operands.get(operands.size() - 1)).getBytes();
            }
        }
        return null;
    }

    /**
     * Sets the raw string payload of the text-show op at {@code idx} to
     * {@code payload}, preserving the operator subtype. Mirrors {@link
     * #replaceTextOp} but takes a pre-encoded {@link PdfString} instead of
     * re-encoding a decoded string.
     */
    private static boolean setOpPayload(OperatorCollection ops, int idx, PdfString payload) {
        Operator op = ops.getAt(idx);
        if (op instanceof ShowText) {
            List<PdfBase> operand = new ArrayList<>(1);
            operand.add(payload);
            ops.setAt(idx, new ShowText(operand));
            return true;
        }
        String name = op.getName();
        if ("TJ".equals(name)) {
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfArray) {
                PdfArray newArr = new PdfArray();
                newArr.add(payload);
                List<PdfBase> newOperands = new ArrayList<>(operands);
                newOperands.set(0, newArr);
                ops.setAt(idx, new SetGlyphsPositionShowText(newOperands));
                return true;
            }
        } else if ("'".equals(name) || "\"".equals(name)) {
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()) {
                List<PdfBase> newOperands = new ArrayList<>(operands);
                int textPos = newOperands.size() - 1;
                if (newOperands.get(textPos) instanceof PdfString) {
                    newOperands.set(textPos, payload);
                    Operator replacement = "'".equals(name)
                            ? new MoveToNextLineShowText(newOperands)
                            : new SetSpacingMoveToNextLineShowText(newOperands);
                    ops.setAt(idx, replacement);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Replaces {@code oldText} with {@code newText} inside a single text-showing
     * operator while keeping any text that follows the replaced run on the same
     * line in its original position (TextReplaceOptions.AdjustSpaceWidth).
     * <p>
     * The operator is rewritten as a {@code TJ} array
     * {@code [(prefix+newText) adj (suffix)]} where {@code adj} is a numeric
     * displacement, in thousandths of a text-space unit, equal to the exact
     * glyph-advance difference between the old and new text measured in the
     * embedded font. Because the renderer applies the same glyph metrics, the
     * total advance of the operator is left unchanged, so the suffix — and every
     * operator positioned relative to the end of this one — stays put.
     * </p>
     *
     * @return {@code true} if the operator was rewritten
     */
    private boolean replaceTextInSingleOpAdjustSpace(OperatorCollection ops, int idx,
                                                     String oldText, String newText) {
        if (sourceFont == null) {
            return false;
        }
        // Composite (Type0/CID) fonts carry 2-byte codes; the byte-splicing
        // below would corrupt them. Fall through to replaceTextOp, which
        // re-encodes the whole payload through the font (RTL2/RTL3_changeText).
        if (sourceFont.isComposite()) {
            return false;
        }
        Operator op = ops.getAt(idx);
        // Only the plain text-showing operators carry a single replaceable
        // string payload; ', " and others mix in positioning we must not lose.
        if (!(op instanceof ShowText) && !"TJ".equals(op.getName())) {
            return false;
        }
        String currentText = getOpText(op);
        if (currentText == null) {
            return false;
        }
        int start = findBestReplacementStart(currentText, oldText);
        if (start < 0) {
            return false;
        }
        String prefix = currentText.substring(0, start);
        String suffix = currentText.substring(start + oldText.length());

        TextState st = getTextState();
        double tfs = sourceTfSize > 0 ? sourceTfSize
                : (st != null && st.getFontSize() > 0 ? st.getFontSize() : 1.0);
        double tc = st != null ? st.getCharacterSpacing() : 0;
        double tw = st != null ? st.getWordSpacing() : 0;
        // Exact text-space advance (per the Tf size) of the removed vs inserted
        // glyphs. Horizontal scaling cancels between advance and the TJ number,
        // so it is intentionally omitted here.
        double advOld = glyphAdvanceTextSpace(oldText, tfs, tc, tw);
        double advNew = glyphAdvanceTextSpace(newText, tfs, tc, tw);
        // TJ number n: rendered displacement = -n/1000 * Tfs. Choosing
        // n = (advNew - advOld) * 1000 / Tfs cancels the width change so the
        // suffix keeps its place.
        double n = (advNew - advOld) * 1000.0 / tfs;

        PdfArray arr = new PdfArray();
        arr.add(new PdfString((prefix + newText).getBytes(StandardCharsets.ISO_8859_1)));
        if (Math.abs(n) > 0.01) {
            arr.add(new PdfFloat(n));
        }
        if (!suffix.isEmpty()) {
            arr.add(new PdfString(suffix.getBytes(StandardCharsets.ISO_8859_1)));
        }
        List<PdfBase> newOperands = new ArrayList<>();
        newOperands.add(arr);
        ops.setAt(idx, new SetGlyphsPositionShowText(newOperands));
        return true;
    }

    /**
     * Sums the text-space glyph advances (in Tf-size units) of {@code text} in
     * {@link #sourceFont}, including character spacing and word spacing, using
     * the ISO-8859-1 byte codes that {@link #replaceTextOp} writes to the
     * content stream so the measurement matches what the renderer will consume.
     */
    private double glyphAdvanceTextSpace(String text, double tfs, double tc, double tw) {
        double total = 0;
        double unitScale = sourceFont.getWidthUnitScale();
        for (byte b : text.getBytes(StandardCharsets.ISO_8859_1)) {
            int code = b & 0xFF;
            total += sourceFont.getWidth(code) * unitScale * tfs + tc;
            if (code == 32) {
                total += tw;
            }
        }
        return total;
    }

    private int findBestReplacementStart(String currentText, String oldText) {
        if (oldText == null || oldText.isEmpty()) {
            return clampSourceTextStart(currentText.length());
        }
        int expected = clampSourceTextStart(currentText.length());
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        int from = 0;
        while (from <= currentText.length() - oldText.length()) {
            int found = currentText.indexOf(oldText, from);
            if (found < 0) {
                break;
            }
            int distance = Math.abs(found - expected);
            if (distance < bestDistance) {
                best = found;
                bestDistance = distance;
                if (distance == 0) {
                    break;
                }
            }
            from = found + 1;
        }
        return best;
    }

    private int clampSourceTextStart(int currentLength) {
        if (sourceTextStart < 0) {
            return 0;
        }
        return Math.min(sourceTextStart, Math.max(0, currentLength));
    }

    private static String getOpText(Operator op) {
        if (op instanceof ShowText) {
            return ((ShowText) op).getText();
        }
        String name = op.getName();
        List<PdfBase> operands = op.getOperands();
        if ("TJ".equals(name)) {
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfArray) {
                StringBuilder sb = new StringBuilder();
                PdfArray arr = (PdfArray) operands.get(0);
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase item = arr.get(i);
                    if (item instanceof PdfString) {
                        sb.append(((PdfString) item).getString());
                    }
                }
                return sb.toString();
            }
        } else if (("'".equals(name) || "\"".equals(name))
                && operands != null && !operands.isEmpty()) {
            PdfBase textOperand = operands.get(operands.size() - 1);
            if (textOperand instanceof PdfString) {
                return ((PdfString) textOperand).getString();
            }
        }
        return null;
    }

    /** Replaces the text payload of the op at {@code idx} with {@code newText}. */
    private boolean replaceTextOp(OperatorCollection ops, int idx, String newText) {
        Operator op = ops.getAt(idx);
        if (op instanceof ShowText) {
            List<PdfBase> operand = new ArrayList<>(1);
            operand.add(encodeReplacementText(newText));
            ops.setAt(idx, new ShowText(operand));
            return true;
        }
        String name = op.getName();
        if ("TJ".equals(name)) {
            // Collapse the whole TJ array to a single string. A partial
            // replacement (first PdfString only) would leave kerning-split
            // leftovers that re-assemble to the original phrase on reload.
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfArray) {
                PdfArray newArr = new PdfArray();
                newArr.add(encodeReplacementText(newText));
                List<PdfBase> newOperands = new ArrayList<>(operands);
                newOperands.set(0, newArr);
                // Sprint 35: preserve TextShowOperator subclass so downstream
                // `instanceof TextShowOperator` checks (used by extractor and
                // regression tests verifying operator sequences) keep working.
                ops.setAt(idx, new SetGlyphsPositionShowText(newOperands));
                return true;
            }
        } else if ("'".equals(name) || "\"".equals(name)) {
            // ' and " carry a text string as their final operand.
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()) {
                List<PdfBase> newOperands = new ArrayList<>(operands);
                int textPos = newOperands.size() - 1;
                if (newOperands.get(textPos) instanceof PdfString) {
                    newOperands.set(textPos, encodeReplacementText(newText));
                    Operator replacement = "'".equals(name)
                            ? new MoveToNextLineShowText(newOperands)
                            : new SetSpacingMoveToNextLineShowText(newOperands);
                    ops.setAt(idx, replacement);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Encodes replacement text as the show-operator payload for this
     * fragment's source font.
     * <p>
     * Simple fonts keep the historical ISO-8859-1 byte mapping. Composite
     * (Type0/CID) fonts get the full RTL write pipeline
     * (RTL2/RTL3_changeText):
     * </p>
     * <ol>
     *   <li>contextually shape plain Arabic letters into presentation forms
     *       ({@link ArabicShaper}) — the form the surrounding document text
     *       is stored in;</li>
     *   <li>reverse strong-RTL runs into visual order, matching how RTL
     *       producers store glyphs in the content stream (the extractor
     *       applies the inverse via {@code TextAbsorber.reverseRtlRuns});</li>
     *   <li>map each character to a 2-byte code through the font's reverse
     *       ToUnicode CMap, falling back to the character's own codepoint as
     *       the CID — the decode pipeline mirrors that fallback, so the text
     *       round-trips even for glyphs absent from the subset.</li>
     * </ol>
     */
    private PdfString encodeReplacementText(String newText) {
        if (sourceFont == null) {
            return new PdfString(newText.getBytes(StandardCharsets.ISO_8859_1));
        }
        if (!sourceFont.isComposite()) {
            return encodeSimpleReplacementText(newText);
        }
        String visual = TextAbsorber.reverseRtlRuns(ArabicShaper.shape(newText));
        java.util.Map<Character, Integer> reverse = new java.util.HashMap<>();
        if (sourceFont.getToUnicode() != null) {
            for (java.util.Map.Entry<Integer, String> e
                    : sourceFont.getToUnicode().getMappings().entrySet()) {
                String value = e.getValue();
                if (value != null && value.length() == 1) {
                    // Keep the lowest code when several map to the same char.
                    reverse.merge(value.charAt(0), e.getKey(), Math::min);
                }
            }
        }
        byte[] bytes = new byte[visual.length() * 2];
        for (int i = 0; i < visual.length(); i++) {
            char c = visual.charAt(i);
            int code = reverse.getOrDefault(c, (int) c);
            bytes[2 * i] = (byte) (code >>> 8);
            bytes[2 * i + 1] = (byte) code;
        }
        return new PdfString(bytes);
    }

    /**
     * Encodes replacement text for a <em>simple</em> (single-byte) source
     * font. A subset TrueType/Type1 font typically re-numbers its glyphs, so
     * the byte code that draws (say) an {@code 'a'} is <em>not</em> the ASCII
     * {@code 0x61} — it is whatever code the subset assigned, discoverable
     * only by inverting the font's own decode pipeline (ToUnicode → Encoding →
     * identity). Blindly writing ISO-8859-1 bytes therefore renders and
     * extracts as garbage for subset fonts (e.g. {@code CAAAAA+ArialUnicodeMS}
     * where writing {@code 'C'} produced an {@code 'H'} glyph).
     * <p>
     * We build a char→code reverse map by running the font's {@link
     * org.aspose.pdf.engine.font.PdfFont#decode(byte[]) decode} over every code
     * in the 0..255 space, so the emitted byte is guaranteed to round-trip
     * back to the intended character. Characters absent from the subset fall
     * back to their ISO-8859-1 byte (best effort — the glyph is not in the
     * embedded program, so nothing better is possible without re-embedding).
     */
    private PdfString encodeSimpleReplacementText(String newText) {
        java.util.Map<Character, Integer> reverse = buildSimpleReverseMap();
        byte[] bytes = new byte[newText.length()];
        for (int i = 0; i < newText.length(); i++) {
            char c = newText.charAt(i);
            Integer code = reverse.get(c);
            bytes[i] = (byte) (code != null ? code : (c & 0xFF));
        }
        return new PdfString(bytes);
    }

    /**
     * Inverts the source simple font's decode pipeline into a char→code map
     * over the single-byte code space. Lower codes win on collision so the
     * result is deterministic. Cached per fragment via {@link #simpleReverseMap}.
     */
    private java.util.Map<Character, Integer> buildSimpleReverseMap() {
        if (simpleReverseMap != null) {
            return simpleReverseMap;
        }
        java.util.Map<Character, Integer> reverse = new java.util.HashMap<>();
        // Walk high→low so the lowest code overwrites and ends up winning.
        for (int code = 255; code >= 0; code--) {
            String decoded;
            try {
                decoded = sourceFont.decode(new byte[]{(byte) code});
            } catch (Exception e) {
                continue;
            }
            if (decoded != null && decoded.length() == 1) {
                reverse.put(decoded.charAt(0), code);
            }
        }
        simpleReverseMap = reverse;
        return reverse;
    }

    /** Clears the text payload of a text-showing op at {@code idx}. */
    private static boolean clearTextOp(OperatorCollection ops, int idx) {
        Operator op = ops.getAt(idx);
        if (op instanceof ShowText) {
            ops.setAt(idx, new ShowText(""));
            return true;
        }
        String name = op.getName();
        if ("TJ".equals(name)) {
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()
                    && operands.get(0) instanceof PdfArray) {
                PdfArray newArr = new PdfArray();
                newArr.add(new PdfString(new byte[0]));
                List<PdfBase> newOperands = new ArrayList<>(operands);
                newOperands.set(0, newArr);
                // Sprint 35: preserve TextShowOperator subclass (see replaceTextOp).
                ops.setAt(idx, new SetGlyphsPositionShowText(newOperands));
                return true;
            }
        } else if ("'".equals(name) || "\"".equals(name)) {
            List<PdfBase> operands = op.getOperands();
            if (operands != null && !operands.isEmpty()) {
                List<PdfBase> newOperands = new ArrayList<>(operands);
                int textPos = newOperands.size() - 1;
                if (newOperands.get(textPos) instanceof PdfString) {
                    newOperands.set(textPos, new PdfString(new byte[0]));
                    Operator replacement = "'".equals(name)
                            ? new MoveToNextLineShowText(newOperands)
                            : new SetSpacingMoveToNextLineShowText(newOperands);
                    ops.setAt(idx, replacement);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the list of text segments.
     *
     * @return the segments (mutable list, matching Aspose.PDF API)
     */
    public List<TextSegment> getSegments() {
        return segments;
    }

    /**
     * Adds a text segment.
     *
     * @param segment the segment to add
     */
    public void addSegment(TextSegment segment) {
        if (segment != null) {
            segments.add(segment);
        }
    }

    /**
     * Returns the position on the page where this fragment begins.
     *
     * @return the position, or null
     */
    public Position getPosition() {
        return position;
    }

    /**
     * Sets the position.
     *
     * @param position the position
     */
    public void setPosition(Position position) {
        Position old = this.position;
        this.position = position;
        if (position == null) {
            return;
        }
        if (old != null) {
            // Aspose: moving the fragment translates the WHOLE fragment — every
            // segment shifts by the delta between the new and old origin, so
            // segments keep their relative offsets (repositioning after segments
            // were placed at their own positions).
            double dx = position.getXIndent() - old.getXIndent();
            double dy = position.getYIndent() - old.getYIndent();
            for (TextSegment seg : segments) {
                Position sp = seg.getPosition();
                if (sp != null) {
                    seg.setPosition(new Position(sp.getXIndent() + dx, sp.getYIndent() + dy));
                } else {
                    seg.setPosition(new Position(position.getXIndent(), position.getYIndent()));
                }
            }
        } else {
            // First placement: segments without their own position adopt it.
            for (TextSegment seg : segments) {
                if (seg.getPosition() == null) {
                    seg.setPosition(position);
                }
            }
        }
    }

    /**
     * Returns the bounding rectangle of this fragment on the page.
     *
     * @return the rectangle, or null
     */
    public Rectangle getRectangle() {
        if (rectangle != null) {
            return rectangle;
        }
        // Unplaced fragment (constructed via `new TextFragment(...)` and not yet
        // absorbed or laid out on a page): synthesize a measured bounding box from
        // the text and the TextState font metrics so geometric callers (e.g.
        // width-based stamp positioning) work instead of hitting an NPE on a null
        // rectangle. Aspose likewise returns a measured rectangle here. Origin is
        // (0,0) since the fragment has no page position yet.
        TextState ts = getTextState();
        double fontSize = (ts != null) ? ts.getFontSize() : 0;
        if (fontSize <= 0) {
            fontSize = 12;
        }
        String fontName = (ts != null && ts.getFont() != null) ? ts.getFont().getName() : null;
        String text = getText();
        double width = TextLayoutHelper.measureTextWidth(text == null ? "" : text, fontName, fontSize);
        return new Rectangle(0, 0, width, fontSize);
    }

    /**
     * Returns the text baseline rotation in device space, quantized to one of
     * {@code 0, 90, 180, 270}. {@code 0} is ordinary horizontal text.
     *
     * @return the rotation in degrees
     */
    public int getRotation() {
        return rotation;
    }

    /**
     * Sets the text baseline rotation (quantized to {@code 0/90/180/270}).
     *
     * @param rotation the rotation in degrees
     */
    public void setRotation(int rotation) {
        this.rotation = rotation;
    }

    /**
     * Returns the exact per-character X boundaries, or {@code null} if the
     * extractor could not provide them for this fragment.
     *
     * @return the character X positions, or {@code null}
     */
    public double[] getCharXPositions() {
        return charXPositions;
    }

    /**
     * Sets the exact per-character X boundaries (extractor use).
     *
     * @param charXPositions array of length {@code text.length()+1}, or {@code null}
     */
    public void setCharXPositions(double[] charXPositions) {
        this.charXPositions = charXPositions;
    }

    /**
     * Returns the engine font that rendered this fragment, or {@code null}.
     *
     * @return the source font, or {@code null}
     */
    public PdfFont getSourceFont() {
        return sourceFont;
    }

    /**
     * Sets the engine font that rendered this fragment (extractor use).
     *
     * @param sourceFont the source font
     */
    /**
     * Records the raw {@code /Tf} operand size of the source show operator
     * (internal, set by the extractor). See {@link #sourceTfSize}.
     *
     * @param tfSize the raw Tf size, or ≤0 when unknown
     */
    public void setSourceTfSize(double tfSize) {
        this.sourceTfSize = tfSize;
    }

    /**
     * Returns the raw {@code /Tf} operand size of the source show operator,
     * or ≤0 when unknown. See {@link #sourceTfSize}.
     *
     * @return the raw Tf size
     */
    public double getSourceTfSize() {
        return sourceTfSize;
    }

    public void setSourceFont(PdfFont sourceFont) {
        this.sourceFont = sourceFont;
    }

    /**
     * Sets the bounding rectangle.
     *
     * @param rectangle the rectangle
     */
    public void setRectangle(Rectangle rectangle) {
        this.rectangle = rectangle;
        // Propagate rectangle to segments that don't have their own
        for (TextSegment seg : segments) {
            if (seg.getRectangle() == null) {
                seg.setRectangle(rectangle);
            }
        }
    }

    /**
     * Returns the page this fragment was extracted from.
     *
     * @return the page, or null
     */
    public Page getPage() {
        return page;
    }

    /**
     * Sets the source page.
     *
     * @param page the page
     */
    public void setPage(Page page) {
        this.page = page;
    }

    /**
     * Returns the text state of the first segment (convenience accessor).
     *
     * @return the text state
     */
    public TextState getTextState() {
        if (!segments.isEmpty()) {
            return segments.get(0).getTextState();
        }
        return new TextState();
    }

    /**
     * Replaces the text state of the first segment (convenience setter).
     * <p>
     * Mirrors the Aspose.PDF C# API where {@code TextFragment.TextState = new TextState(...)}
     * applies to the first/primary segment of the fragment. If the fragment has
     * no segments yet, a default segment is created so the state can be stored.
     * </p>
     *
     * @param state the text state to apply (null is silently ignored)
     */
    public void setTextState(TextState state) {
        if (state == null) return;
        if (segments.isEmpty()) {
            segments.add(new TextSegment(this.text));
        }
        segments.get(0).setTextState(state);
    }

    /**
     * Returns the index of the content stream operator that produced this fragment.
     * A value of {@code -1} means no source is tracked.
     *
     * @return the operator index, or -1
     */
    public int getSourceOperatorIndex() {
        return sourceOperatorIndex;
    }

    /**
     * Sets the index of the content stream operator that produced this fragment.
     *
     * @param index the operator index
     */
    public void setSourceOperatorIndex(int index) {
        this.sourceOperatorIndex = index;
    }

    /**
     * Returns the last operator index in this fragment's source span.
     * <p>
     * A fragment may span multiple adjacent Tj/TJ operators within a single
     * BT..ET text object (e.g. letters split for kerning). The range
     * {@code [sourceOperatorIndex .. lastSourceOperatorIndex]} covers every
     * text-showing op whose strings concatenate to the fragment's text.
     * </p>
     *
     * @return the last operator index in the fragment's source span, or -1
     */
    public int getLastSourceOperatorIndex() {
        return lastSourceOperatorIndex;
    }

    /**
     * Sets the last operator index in this fragment's source span.
     *
     * @param index the last operator index
     */
    public void setLastSourceOperatorIndex(int index) {
        this.lastSourceOperatorIndex = index;
    }

    /**
     * Sprint 36: store source operator by identity. After a sibling fragment
     * mutates the shared {@link OperatorCollection} (e.g. inserts a
     * font-restore op), our cached {@code sourceOperatorIndex} would be stale;
     * the reference lets us re-derive the current index before each mutation.
     */
    public void setSourceOperator(Operator op) {
        this.sourceOperator = op;
    }

    public void setLastSourceOperator(Operator op) {
        this.lastSourceOperator = op;
    }

    public Operator getSourceOperator() {
        return sourceOperator;
    }

    public Operator getLastSourceOperator() {
        return lastSourceOperator;
    }

    /**
     * Returns the name of the font used to render this fragment.
     *
     * @return the font resource name, or null
     */
    public String getSourceFontName() {
        return sourceFontName;
    }

    /**
     * Sets the font resource name used to render this fragment.
     *
     * @param fontName the font name
     */
    public void setSourceFontName(String fontName) {
        this.sourceFontName = fontName;
    }

    /**
     * Returns the start offset of this fragment within the source text-showing operator text.
     *
     * @return the zero-based character offset
     */
    public int getSourceTextStart() {
        return sourceTextStart;
    }

    /**
     * Sets the start offset of this fragment within the source text-showing operator text.
     *
     * @param sourceTextStart the zero-based character offset
     */
    public void setSourceTextStart(int sourceTextStart) {
        this.sourceTextStart = Math.max(0, sourceTextStart);
    }

    /**
     * Returns the original length of this fragment inside the source text-showing operator text.
     *
     * @return the original source text length, or {@code -1} when unknown
     */
    public int getSourceTextLength() {
        return sourceTextLength;
    }

    /**
     * Sets the original length of this fragment inside the source text-showing operator text.
     *
     * @param sourceTextLength the original source text length
     */
    public void setSourceTextLength(int sourceTextLength) {
        this.sourceTextLength = sourceTextLength;
    }

    /**
     * Returns the operator collection that originally produced this fragment.
     *
     * @return the source operators, or null
     */
    public OperatorCollection getSourceOperators() {
        return sourceOperators;
    }

    /**
     * Sets the operator collection that originally produced this fragment.
     *
     * @param sourceOperators the source operators
     */
    public void setSourceOperators(OperatorCollection sourceOperators) {
        this.sourceOperators = sourceOperators;
    }


    /**
     * Associates a group of source content-stream operators that draw an underline
     * beneath this fragment, and arms the fragment so that turning the underline off
     * ({@code getTextState().setUnderline(false)}) removes those operators on save.
     * <p>Called by the text-extraction engine when underline detection is enabled
     * via {@code TextEditOptions.ToAttemptGetUnderlineFromSource}.</p>
     *
     * @param ops  the operators that draw the underline (re/m/l + paint operator)
     * @param coll the operator collection that owns {@code ops}
     */
    public void addSourceUnderline(java.util.List<Operator> ops, OperatorCollection coll) {
        if (ops == null || ops.isEmpty() || coll == null) {
            return;
        }
        if (sourceUnderlineOpGroups == null) {
            sourceUnderlineOpGroups = new java.util.ArrayList<>(1);
        }
        sourceUnderlineOpGroups.add(new java.util.ArrayList<>(ops));
        this.sourceUnderlineCollection = coll;
        // Arm the (possibly shared) TextState so a later setUnderline(false) edit
        // strips these operators. Captured `this` carries page/sourceContentStream.
        getTextState().addUnderlineRemovalHook(this::removeSourceUnderlineFromContent);
    }

    /**
     * Returns the underline operator groups associated with this fragment, or
     * {@code null} if none. Used to propagate underline linkage to match fragments.
     *
     * @return the underline operator groups, or {@code null}
     */
    java.util.List<java.util.List<Operator>> getSourceUnderlineOpGroups() {
        return sourceUnderlineOpGroups;
    }

    /**
     * Returns the operator collection that owns this fragment's underline operators.
     *
     * @return the collection, or {@code null}
     */
    OperatorCollection getSourceUnderlineCollection() {
        return sourceUnderlineCollection;
    }

    /**
     * Removes the previously {@linkplain #addSourceUnderline associated} underline
     * operators from the content stream and re-serialises so the change persists on
     * save. Idempotent: subsequent calls are no-ops.
     */
    void removeSourceUnderlineFromContent() {
        if (sourceUnderlineOpGroups == null || sourceUnderlineCollection == null) {
            return;
        }
        boolean mutated = false;
        for (java.util.List<Operator> group : sourceUnderlineOpGroups) {
            int before = sourceUnderlineCollection.size();
            sourceUnderlineCollection.delete(group);
            if (sourceUnderlineCollection.size() != before) {
                mutated = true;
            }
        }
        sourceUnderlineOpGroups = null; // avoid double-removal
        if (!mutated) {
            return;
        }
        if (sourceContentStream != null) {
            sourceContentStream.setDecodedData(serializeOperators(sourceUnderlineCollection));
        } else if (page != null) {
            page.markContentsDirty();
        }
    }

    /**
     * Returns the form/content stream that owns {@link #getSourceOperators()}.
     *
     * @return the source content stream, or null for page-level cached content
     */
    public PdfStream getSourceContentStream() {
        return sourceContentStream;
    }

    /**
     * Sets the form/content stream that owns {@link #getSourceOperators()}.
     *
     * @param sourceContentStream the source content stream
     */
    public void setSourceContentStream(PdfStream sourceContentStream) {
        this.sourceContentStream = sourceContentStream;
    }

    /**
     * Records the /Resources dictionary governing the source content stream
     * (page resources, or a Form XObject's own resources). Engine-internal;
     * used by {@link #applyFontToSource} to register a replacement font where
     * the edited stream can resolve it.
     *
     * @param sourceResources the governing resources dictionary, or null
     */
    public void setSourceResources(org.aspose.pdf.engine.pdfobjects.PdfDictionary sourceResources) {
        this.sourceResources = sourceResources;
    }

    /**
     * Returns the /Resources dictionary governing the source content stream, or null.
     *
     * @return the governing resources dictionary, or null
     */
    public org.aspose.pdf.engine.pdfobjects.PdfDictionary getSourceResources() {
        return sourceResources;
    }

    /**
     * Returns the text replacement options associated with this fragment.
     *
     * @return the replacement options, or {@code null}
     */
    public TextReplaceOptions getTextReplaceOptions() {
        return textReplaceOptions;
    }

    /**
     * Sets the text replacement options associated with this fragment.
     *
     * @param textReplaceOptions the replacement options
     */
    public void setTextReplaceOptions(TextReplaceOptions textReplaceOptions) {
        this.textReplaceOptions = textReplaceOptions;
    }

    /**
     * Gets the footnote associated with this text fragment.
     *
     * @return the footnote, or {@code null} if none
     */
    public org.aspose.pdf.Note getFootNote() {
        return footNote;
    }

    /**
     * Sets the footnote associated with this text fragment.
     *
     * @param footNote the footnote to associate
     */
    public void setFootNote(org.aspose.pdf.Note footNote) {
        this.footNote = footNote;
    }

    /**
     * Gets the endnote associated with this text fragment.
     *
     * @return the endnote, or {@code null} if none
     */
    public org.aspose.pdf.Note getEndNote() {
        return endNote;
    }

    /**
     * Sets the endnote associated with this text fragment.
     *
     * @param endNote the endnote to associate
     */
    public void setEndNote(org.aspose.pdf.Note endNote) {
        this.endNote = endNote;
    }

    @Override
    public String toString() {
        return text;
    }
}
