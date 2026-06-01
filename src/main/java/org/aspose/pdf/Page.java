package org.aspose.pdf;

import org.aspose.pdf.engine.cos.COSArray;
import org.aspose.pdf.engine.cos.COSBase;
import org.aspose.pdf.engine.cos.COSDictionary;
import org.aspose.pdf.engine.cos.COSFloat;
import org.aspose.pdf.engine.cos.COSInteger;
import org.aspose.pdf.engine.cos.COSName;
import org.aspose.pdf.engine.cos.COSObjectReference;
import org.aspose.pdf.engine.cos.COSStream;
import org.aspose.pdf.engine.cos.COSString;
import org.aspose.pdf.engine.cos.COSCloner;
import org.aspose.pdf.engine.layout.ContentStreamBuilder;
import org.aspose.pdf.engine.parser.ContentStreamParser;
import org.aspose.pdf.engine.parser.PDFParser;

import org.aspose.pdf.operators.BDC;
import org.aspose.pdf.operators.BMC;
import org.aspose.pdf.operators.EMC;
import org.aspose.pdf.annotations.Annotation;
import org.aspose.pdf.annotations.AnnotationCollection;
import org.aspose.pdf.text.TextAbsorber;
import org.aspose.pdf.text.TextFragment;
import org.aspose.pdf.text.TextFragmentAbsorber;
import org.aspose.pdf.text.TextFragmentCollection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Represents a single PDF page (ISO 32000-1:2008, §7.7.3.3).
 * <p>
 * Wraps a page dictionary and provides access to page properties such as
 * media box, crop box, rotation, resources, and content streams.
 * Inheritable properties (/MediaBox, /CropBox, /Resources, /Rotate) are
 * resolved by walking up the /Parent chain as specified in §7.7.3.4.
 * </p>
 */
public class Page {

    private static final Logger LOG = Logger.getLogger(Page.class.getName());

    private final COSDictionary pageDict;
    private final PDFParser parser;
    private Document owningDocument;
    private int number;
    private Paragraphs paragraphs;
    private PageInfo pageInfo;
    private HeaderFooter header;
    private HeaderFooter footer;
    private boolean headerFooterOverlayApplied;
    private TocInfo tocInfo;
    private java.util.List<Layer> _layers;
    private ArtifactCollection artifactsCache;
    private final List<TextFragment> syntheticTextFragments = new ArrayList<>();

    // Cached, mutable operator view of /Contents. Populated lazily on the first
    // call to getContents(); reused across subsequent calls so that mutations
    // (e.g. TextFragment.setText) persist without a re-parse. Set dirty when
    // the cache has been mutated and needs to be re-serialised into /Contents
    // before the next save.
    private OperatorCollection contentsCache;
    private boolean contentsDirty;
    private static final COSName OPENPDF_STAMP_INFO = COSName.of("OpenPdfStampInfo");
    private static final String OPENPDF_STAMP_BEGIN = "%OPENPDF_STAMP_BEGIN:";
    private static final String OPENPDF_STAMP_END = "%OPENPDF_STAMP_END:";

    /**
     * Creates a Page wrapper around the given page dictionary.
     *
     * @param pageDict the page dictionary (must have /Type /Page)
     * @param parser   the PDF parser for resolving indirect references, may be null
     * @throws IllegalArgumentException if pageDict is null
     */
    public Page(COSDictionary pageDict, PDFParser parser) {
        if (pageDict == null) {
            throw new IllegalArgumentException("Page dictionary must not be null");
        }
        this.pageDict = pageDict;
        this.parser = parser;
        LOG.fine(() -> "Page created");
    }

    /** Returns the document that owns this page, or null if not attached. */
    public Document getOwningDocument() {
        return owningDocument;
    }

    /** Sets the owning document. Called by {@link PageCollection} during add/insert. */
    void setOwningDocument(Document owningDocument) {
        this.owningDocument = owningDocument;
    }

    /**
     * Returns the media box for this page (ISO 32000, §7.7.3.3, Table 30).
     * This is a required inheritable property.
     *
     * @return the media box rectangle, or null if not found in the page tree
     */
    public Rectangle getMediaBox() {
        COSBase value = getInheritable(COSName.MEDIABOX);
        return toRectangle(value);
    }

    /**
     * Returns the crop box for this page. Defaults to the media box if absent (§14.11.2).
     *
     * @return the crop box rectangle
     */
    public Rectangle getCropBox() {
        COSBase value = getInheritable(COSName.CROPBOX);
        if (value != null) {
            Rectangle r = toRectangle(value);
            if (r != null) return r;
        }
        return getMediaBox();
    }

    /**
     * Returns the art box. Defaults to the crop box if absent.
     *
     * @return the art box rectangle
     */
    public Rectangle getArtBox() {
        COSBase value = resolveRef(pageDict.get(COSName.ARTBOX));
        if (value instanceof COSArray) {
            return Rectangle.fromCOSArray((COSArray) value);
        }
        return getCropBox();
    }

    /**
     * Returns the bleed box. Defaults to the crop box if absent.
     *
     * @return the bleed box rectangle
     */
    public Rectangle getBleedBox() {
        COSBase value = resolveRef(pageDict.get(COSName.BLEEDBOX));
        if (value instanceof COSArray) {
            return Rectangle.fromCOSArray((COSArray) value);
        }
        return getCropBox();
    }

    /**
     * Returns the trim box. Defaults to the crop box if absent.
     *
     * @return the trim box rectangle
     */
    public Rectangle getTrimBox() {
        COSBase value = resolveRef(pageDict.get(COSName.TRIMBOX));
        if (value instanceof COSArray) {
            return Rectangle.fromCOSArray((COSArray) value);
        }
        return getCropBox();
    }

    /**
     * Returns the effective rectangle for this page (same as getCropBox).
     *
     * @return the page rectangle
     */
    public Rectangle getRect() {
        return getCropBox();
    }

    /**
     * Returns the minimal bounding box of inked content on this page in user
     * space, scanning the content stream for path operators ({@code re},
     * {@code m}, {@code l}, {@code c}), text-positioning operators
     * ({@code Tm}, {@code Td}, {@code T*}) and XObject invocations ({@code Do}).
     * The bbox is built by transforming each emitted point through the current
     * CTM (tracked across {@code q}/{@code Q}/{@code cm}) and the current text
     * matrix (for text-show operators).
     *
     * <p>This is a heuristic — it counts the start of each text run and the
     * placement origin of each XObject (rather than computing exact glyph
     * extents or recursively expanding XForm contents) — but it is sufficient
     * for cropping/fitting decisions where a tight-on-the-strokes bbox is not
     * required. When the content stream contains no drawing operators (or
     * cannot be parsed) the method falls back to the {@linkplain #getCropBox()
     * crop box}.</p>
     *
     * @return the content bounding box; never null
     */
    public Rectangle calculateContentBBox() {
        Rectangle fallback = getCropBox();
        try {
            OperatorCollection ops = getContents();
            if (ops == null || ops.size() == 0) return fallback;
            ContentBBoxCalculator calc = new ContentBBoxCalculator();
            for (int i = 1; i <= ops.size(); i++) {
                calc.visit(ops.get(i));
            }
            Rectangle computed = calc.toRectangle();
            if (computed != null) return computed;
        } catch (IOException e) {
            LOG.fine(() -> "calculateContentBBox: content stream parse failed: " + e.getMessage());
        }
        return fallback;
    }

    /**
     * Stateful walker that maintains the CTM/text-matrix stacks and accumulates
     * a bbox in user space. Package-private so unit tests can reach it.
     */
    static final class ContentBBoxCalculator {
        // CTM stack — pushed on q, popped on Q. Top is the active CTM.
        private final java.util.ArrayDeque<Matrix> ctmStack = new java.util.ArrayDeque<>();
        // Text matrix; only valid between BT and ET. Reset on BT.
        private Matrix textMatrix = Matrix.IDENTITY;
        private boolean inText = false;
        // Current path origin (from m/Re); subpath start tracked for closepath.
        private double curX = 0, curY = 0;
        // Accumulated bbox in user space.
        private double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        private boolean hasContent = false;

        ContentBBoxCalculator() {
            ctmStack.push(Matrix.IDENTITY);
        }

        void visit(Operator op) {
            if (op instanceof org.aspose.pdf.operators.GSave) {
                ctmStack.push(ctmStack.peek());
            } else if (op instanceof org.aspose.pdf.operators.GRestore) {
                if (ctmStack.size() > 1) ctmStack.pop();
            } else if (op instanceof org.aspose.pdf.operators.ConcatenateMatrix) {
                Matrix m = ((org.aspose.pdf.operators.ConcatenateMatrix) op).getMatrix();
                ctmStack.push(m.multiply(ctmStack.pop()));
            } else if (op instanceof org.aspose.pdf.operators.Re) {
                org.aspose.pdf.operators.Re re = (org.aspose.pdf.operators.Re) op;
                includeUserPoint(re.getX(), re.getY());
                includeUserPoint(re.getX() + re.getWidth(), re.getY() + re.getHeight());
                curX = re.getX();
                curY = re.getY();
            } else if (op instanceof org.aspose.pdf.operators.MoveTo) {
                org.aspose.pdf.operators.MoveTo m = (org.aspose.pdf.operators.MoveTo) op;
                curX = m.getX();
                curY = m.getY();
                includeUserPoint(curX, curY);
            } else if (op instanceof org.aspose.pdf.operators.LineTo) {
                org.aspose.pdf.operators.LineTo l = (org.aspose.pdf.operators.LineTo) op;
                curX = l.getX();
                curY = l.getY();
                includeUserPoint(curX, curY);
            } else if (op instanceof org.aspose.pdf.operators.CurveTo) {
                org.aspose.pdf.operators.CurveTo c = (org.aspose.pdf.operators.CurveTo) op;
                // Include all three control points (loose-but-safe envelope)
                includeUserPoint(c.getX1(), c.getY1());
                includeUserPoint(c.getX2(), c.getY2());
                includeUserPoint(c.getX3(), c.getY3());
                curX = c.getX3();
                curY = c.getY3();
            } else if (op instanceof org.aspose.pdf.operators.BT) {
                inText = true;
                textMatrix = Matrix.IDENTITY;
            } else if (op instanceof org.aspose.pdf.operators.ET) {
                inText = false;
            } else if (op instanceof org.aspose.pdf.operators.SetTextMatrix) {
                textMatrix = ((org.aspose.pdf.operators.SetTextMatrix) op).getMatrix();
                emitTextOriginPoint();
            } else if (op instanceof org.aspose.pdf.operators.MoveTextPosition) {
                org.aspose.pdf.operators.MoveTextPosition m =
                        (org.aspose.pdf.operators.MoveTextPosition) op;
                textMatrix = new Matrix(1, 0, 0, 1, m.getX(), m.getY()).multiply(textMatrix);
                emitTextOriginPoint();
            } else if ("Tj".equals(op.getName())
                    || "TJ".equals(op.getName())
                    || "'".equals(op.getName())
                    || "\"".equals(op.getName())) {
                emitTextOriginPoint();
            } else if (op instanceof org.aspose.pdf.operators.Do) {
                // Place the XObject's origin point (loose: assumes Do at current CTM origin)
                includeUserPoint(0, 0);
            }
        }

        private void emitTextOriginPoint() {
            if (!inText) return;
            // Text shows at user-space point (textMatrix.transform(0,0)) then CTM.
            double[] tp = textMatrix.transformPoint(0, 0);
            includeUserPoint(tp[0], tp[1]);
        }

        private void includeUserPoint(double xUser, double yUser) {
            double[] dev = ctmStack.peek().transformPoint(xUser, yUser);
            if (dev[0] < minX) minX = dev[0];
            if (dev[1] < minY) minY = dev[1];
            if (dev[0] > maxX) maxX = dev[0];
            if (dev[1] > maxY) maxY = dev[1];
            hasContent = true;
        }

        Rectangle toRectangle() {
            if (!hasContent) return null;
            return new Rectangle(minX, minY, maxX, maxY);
        }
    }

    /**
     * Returns the page rectangle, optionally considering rotation.
     *
     * @param considerRotation if {@code true}, the returned rectangle accounts for page rotation
     * @return the page rectangle
     */
    public Rectangle getPageRect(boolean considerRotation) {
        Rectangle rect = getCropBox();
        if (rect == null) {
            rect = getMediaBox();
        }
        if (considerRotation && rect != null) {
            int rotation = getRotate();
            if (rotation == 90 || rotation == 270) {
                return new Rectangle(rect.getLLX(), rect.getLLY(), rect.getLLX() + rect.getHeight(), rect.getLLY() + rect.getWidth());
            }
        }
        return rect;
    }

    /**
     * Returns the page rotation in degrees (0, 90, 180, or 270).
     * This is an inheritable property, defaulting to 0.
     *
     * @return the rotation angle
     */
    public int getRotate() {
        COSBase value = getInheritable(COSName.ROTATE);
        if (value instanceof org.aspose.pdf.engine.cos.COSInteger) {
            return ((org.aspose.pdf.engine.cos.COSInteger) value).intValue();
        }
        return 0;
    }

    /**
     * Returns the page rotation matrix.
     * <p>
     * The matrix maps default page coordinates into the rotated page space.
     * It is primarily used by legacy text/annotation workflows that need to
     * convert fragment rectangles back to page-related coordinates.
     * </p>
     *
     * @return the rotation matrix
     */
    public Matrix getRotationMatrix() {
        Rectangle rect = getRect();
        double width = rect != null ? rect.getWidth() : 0;
        double height = rect != null ? rect.getHeight() : 0;
        int rotation = ((getRotate() % 360) + 360) % 360;
        switch (rotation) {
            case 90:
                return new Matrix(0, 1, -1, 0, height, 0);
            case 180:
                return new Matrix(-1, 0, 0, -1, width, height);
            case 270:
                return new Matrix(0, -1, 1, 0, 0, width);
            default:
                return Matrix.IDENTITY;
        }
    }

    /**
     * Returns the page resources. This is an inheritable property.
     *
     * @return the Resources object, or null if not found
     */
    public Resources getResources() {
        COSBase value = getInheritable(COSName.RESOURCES);
        if (value instanceof COSDictionary) {
            return new Resources((COSDictionary) value);
        }
        // Lazy-create resources dictionary for new pages
        COSDictionary resDict = new COSDictionary();
        pageDict.set(COSName.RESOURCES, resDict);
        return new Resources(resDict);
    }

    /**
     * Returns the raw page content stream COS object, or null if absent.
     * <p>
     * If the cached operator collection has been mutated since the last flush,
     * serialises it back into {@code /Contents} first so the returned COS
     * reflects the current in-memory state.
     * </p>
     *
     * @return the raw content stream (COSStream or COSArray), or null
     */
    public COSBase getRawContents() {
        if (contentsDirty) {
            try {
                flushContentsIfDirty();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to flush cached contents: " + e.getMessage());
            }
        }
        return resolveRef(pageDict.get(COSName.CONTENTS));
    }

    /**
     * Returns the parsed content stream operators (like Aspose's page.Contents).
     * <p>
     * The first call parses {@code /Contents} and caches the resulting
     * {@link OperatorCollection}. Subsequent calls return the same cached
     * instance so callers observe each other's mutations, and so changes made
     * via {@link TextFragment#setText(String)} (or any direct mutation through
     * the returned collection) persist until the cache is flushed by a save.
     * </p>
     *
     * @return the OperatorCollection, empty if no content stream
     * @throws IOException if parsing the content stream fails
     */
    public OperatorCollection getContents() throws IOException {
        if (contentsCache != null) {
            return contentsCache;
        }
        COSBase raw = resolveRef(pageDict.get(COSName.CONTENTS));
        OperatorCollection parsed;
        if (raw instanceof COSStream) {
            parsed = ContentStreamParser.parseToCollection((COSStream) raw);
        } else if (raw instanceof COSArray) {
            COSArray arr = (COSArray) raw;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = resolveRef(arr.get(i));
                if (item instanceof COSStream) {
                    byte[] data = ((COSStream) item).getDecodedData();
                    baos.write(data);
                    baos.write('\n');
                }
            }
            parsed = ContentStreamParser.parseToCollection(baos.toByteArray());
        } else {
            parsed = new OperatorCollection();
        }
        contentsCache = parsed;
        contentsDirty = false;
        return contentsCache;
    }

    /**
     * Marks the cached operator collection as dirty, so that the next
     * {@link #flushContentsIfDirty()} (invoked by {@code Document.save}) will
     * serialise the current cache back into {@code /Contents}.
     */
    public void markContentsDirty() {
        this.contentsDirty = true;
    }

    /** @return whether this page's cached content operators have unsaved edits. */
    public boolean isContentsDirty() {
        return contentsDirty && contentsCache != null;
    }

    /**
     * If the cache is dirty, serialises it into the page's {@code /Contents}
     * stream. Preserves the indirect reference of any existing content stream
     * so incremental save can detect the change via object identity.
     *
     * @throws IOException if serialisation fails
     */
    /**
     * Propagates the page-level {@link PageInfo} dimensions back to the
     * page's {@code /MediaBox} if the user has changed them through
     * {@link #getPageInfo()}{@code .setWidth(...)} / {@code setHeight(...)} /
     * {@code setIsLandscape(...)}.
     * <p>
     * Without this sync the in-memory PageInfo object is updated but the saved
     * page MediaBox keeps the original dimensions — so callers that build a
     * page with {@code page.getPageInfo().setWidth(600)} would see no effect
     * after save+reload (PDFNEWNET-37215, PDFNEWNET-37323).
     * </p>
     */
    public void flushPageInfoIfNeeded() {
        if (pageInfo == null) {
            return;
        }
        double w = pageInfo.getWidth();
        double h = pageInfo.getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Rectangle current = getMediaBox();
        if (current == null
                || Math.abs(current.getWidth() - w) > 0.001
                || Math.abs(current.getHeight() - h) > 0.001
                || Math.abs(current.getLLX()) > 0.001
                || Math.abs(current.getLLY()) > 0.001) {
            setMediaBox(new Rectangle(0, 0, w, h));
        }
    }

    public void flushContentsIfDirty() throws IOException {
        if (!contentsDirty || contentsCache == null) return;
        // Byte-level serialization (Sprint 30): op.toString() routes operands through
        // US-ASCII and would corrupt any COSString carrying bytes >= 0x80 (CID/Identity-H
        // glyph codes, non-Latin literals). writeTo preserves the exact bytes.
        java.io.ByteArrayOutputStream contentBytes = new java.io.ByteArrayOutputStream();
        for (Operator op : contentsCache) {
            op.writeTo(contentBytes);
            contentBytes.write('\n');
        }
        byte[] data = contentBytes.toByteArray();

        COSBase raw = resolveRef(pageDict.get(COSName.CONTENTS));
        if (raw instanceof COSStream) {
            // Mutate the existing stream in place so its indirect reference is
            // preserved — incremental save tracks modifications via object key
            // and would otherwise miss a wholesale replacement.
            //
            // setDecodedData() invalidates the cached encoded bytes; the writer's
            // prepareEncodedData() will re-encode through the existing /Filter
            // chain (typically FlateDecode). Stripping the filter would emit
            // the modified content stream uncompressed, inflating the saved
            // PDF by ≈25% on text-heavy fixtures (BUG-046).
            COSStream existing = (COSStream) raw;
            existing.setDecodedData(data);
        } else if (raw instanceof COSArray) {
            // /Contents was a sequence of streams. Collapse the rewritten
            // operator-collection bytes into the first stream and clear the
            // remaining ones so re-parsing returns the same operator order.
            COSArray arr = (COSArray) raw;
            COSStream first = null;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = resolveRef(arr.get(i));
                if (item instanceof COSStream) {
                    if (first == null) {
                        first = (COSStream) item;
                        first.setDecodedData(data);
                    } else {
                        // Empty out tail streams; their /Filter (if any) re-encodes
                        // an empty payload to a few bytes — far cheaper than
                        // duplicating the rewritten content N times.
                        ((COSStream) item).setDecodedData(new byte[0]);
                    }
                }
            }
            if (first == null) {
                COSStream stream = new COSStream();
                stream.setDecodedData(data);
                pageDict.set(COSName.CONTENTS, stream);
            }
        } else {
            COSStream stream = new COSStream();
            stream.setDecodedData(data);
            pageDict.set(COSName.CONTENTS, stream);
        }
        contentsDirty = false;
    }

    /**
     * Clears the cached operator collection, forcing the next
     * {@link #getContents()} call to re-parse from {@code /Contents}.
     * Intended for {@link Document#close()} to release memory.
     */
    public void clearContentsCache() {
        this.contentsCache = null;
        this.contentsDirty = false;
    }

    /**
     * Registers synthetic in-memory text fragments associated with this page.
     * These fragments are visible to extractors on the current page instance
     * without requiring a reparse of persisted page content.
     *
     * @param fragments the fragments to add
     */
    public void addSyntheticTextFragments(List<TextFragment> fragments) {
        if (fragments == null || fragments.isEmpty()) {
            return;
        }
        syntheticTextFragments.addAll(fragments);
    }

    /**
     * Returns synthetic in-memory text fragments associated with this page.
     *
     * @return an immutable view of synthetic text fragments
     */
    public List<TextFragment> getSyntheticTextFragments() {
        return Collections.unmodifiableList(syntheticTextFragments);
    }

    /**
     * Returns the typed annotation collection for this page.
     * Creates an empty collection if no /Annots array exists.
     *
     * @return the annotation collection
     */
    public AnnotationCollection getAnnotations() {
        COSBase value = resolveRef(pageDict.get(COSName.ANNOTS));
        COSArray annotsArray;
        if (value instanceof COSArray) {
            annotsArray = (COSArray) value;
        } else {
            annotsArray = new COSArray();
            pageDict.set(COSName.ANNOTS, annotsArray);
        }
        return new AnnotationCollection(annotsArray, this, parser);
    }

    /**
     * Returns the underlying COS dictionary for this page.
     *
     * @return the raw page dictionary
     */
    public COSDictionary getCOSDictionary() {
        return pageDict;
    }

    /**
     * Returns the page background colour as set by {@link #setBackground(Color)},
     * or {@code null} if no explicit background has been applied.
     */
    public Color getBackground() {
        return this.backgroundColor;
    }

    /**
     * Sets a solid background colour for this page. The implementation
     * prepends a marked-content section ({@code /Background BMC q rg <bbox> re f Q EMC})
     * to the page's content stream so PDF viewers paint the rectangle behind
     * all other content. Setting the background to {@link Color#WHITE} (or
     * {@code null}) removes any previously-set background.
     * Mirrors C# {@code Page.Background}.
     *
     * @param color the background colour, or null/white to remove
     */
    public void setBackground(Color color) {
        try {
            removeBackgroundContent();
            if (color == null || colorIsWhite(color)) {
                this.backgroundColor = null;
                return;
            }
            this.backgroundColor = color;
            prependBackgroundContent(color);
        } catch (java.io.IOException e) {
            // Defensive: log and continue — page contents stay as-is.
            java.util.logging.Logger.getLogger(Page.class.getName())
                    .warning("setBackground failed: " + e.getMessage());
        }
    }

    /** Stored background colour applied via {@link #setBackground(Color)}. */
    private Color backgroundColor;

    private static boolean colorIsWhite(Color c) {
        double[] d = c.getComponents();
        for (double v : d) if (v < 0.999) return false;
        return d.length > 0;
    }

    /**
     * Walks the page's existing content stream and removes any prior
     * marked-content {@code /Background BMC ... EMC} block this method
     * inserted previously. Idempotent.
     */
    private void removeBackgroundContent() throws java.io.IOException {
        org.aspose.pdf.engine.cos.COSBase contents = pageDict.get("Contents");
        if (contents instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
            contents = ((org.aspose.pdf.engine.cos.COSObjectReference) contents).dereference();
        }
        if (contents instanceof org.aspose.pdf.engine.cos.COSStream) {
            stripBackgroundFromStream((org.aspose.pdf.engine.cos.COSStream) contents);
        } else if (contents instanceof org.aspose.pdf.engine.cos.COSArray) {
            org.aspose.pdf.engine.cos.COSArray arr =
                    (org.aspose.pdf.engine.cos.COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                org.aspose.pdf.engine.cos.COSBase item = arr.get(i);
                if (item instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
                    item = ((org.aspose.pdf.engine.cos.COSObjectReference) item).dereference();
                }
                if (item instanceof org.aspose.pdf.engine.cos.COSStream) {
                    stripBackgroundFromStream((org.aspose.pdf.engine.cos.COSStream) item);
                }
            }
        }
    }

    private static void stripBackgroundFromStream(org.aspose.pdf.engine.cos.COSStream stream)
            throws java.io.IOException {
        byte[] data = stream.getDecodedData();
        if (data == null || data.length == 0) return;
        String text = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        int start = text.indexOf("/Background BMC");
        if (start < 0) return;
        int end = text.indexOf("EMC", start);
        if (end < 0) return;
        end += 3; // include EMC
        // Trim trailing newline if present
        while (end < text.length() && (text.charAt(end) == '\n' || text.charAt(end) == '\r')) {
            end++;
        }
        String trimmed = text.substring(0, start) + text.substring(end);
        stream.setDecodedData(trimmed.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /**
     * Inserts a marked-content background section at the START of the page's
     * content stream (so it paints behind every other content operator).
     */
    private void prependBackgroundContent(Color color) throws java.io.IOException {
        Rectangle box = getRect();
        if (box == null) return;
        double x = box.getLLX(), y = box.getLLY();
        double w = box.getURX() - box.getLLX();
        double h = box.getURY() - box.getLLY();
        double[] rgb = color.getComponents();
        double r = rgb.length > 0 ? rgb[0] : 0;
        double g = rgb.length > 1 ? rgb[1] : r;
        double b = rgb.length > 2 ? rgb[2] : r;

        StringBuilder sb = new StringBuilder(96);
        sb.append("/Background BMC\n");
        sb.append("q\n");
        sb.append(formatNumber(r)).append(' ').append(formatNumber(g)).append(' ')
                .append(formatNumber(b)).append(" rg\n");
        sb.append(formatNumber(x)).append(' ').append(formatNumber(y)).append(' ')
                .append(formatNumber(w)).append(' ').append(formatNumber(h)).append(" re\n");
        sb.append("f\n");
        sb.append("Q\n");
        sb.append("EMC\n");
        byte[] header = sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

        // Prepend to the first content stream (creating one if /Contents was
        // absent or non-stream).
        org.aspose.pdf.engine.cos.COSBase contents = pageDict.get("Contents");
        if (contents instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
            contents = ((org.aspose.pdf.engine.cos.COSObjectReference) contents).dereference();
        }
        org.aspose.pdf.engine.cos.COSStream firstStream = null;
        if (contents instanceof org.aspose.pdf.engine.cos.COSStream) {
            firstStream = (org.aspose.pdf.engine.cos.COSStream) contents;
        } else if (contents instanceof org.aspose.pdf.engine.cos.COSArray) {
            org.aspose.pdf.engine.cos.COSArray arr =
                    (org.aspose.pdf.engine.cos.COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                org.aspose.pdf.engine.cos.COSBase item = arr.get(i);
                if (item instanceof org.aspose.pdf.engine.cos.COSObjectReference) {
                    item = ((org.aspose.pdf.engine.cos.COSObjectReference) item).dereference();
                }
                if (item instanceof org.aspose.pdf.engine.cos.COSStream) {
                    firstStream = (org.aspose.pdf.engine.cos.COSStream) item;
                    break;
                }
            }
        }
        if (firstStream == null) {
            firstStream = new org.aspose.pdf.engine.cos.COSStream();
            pageDict.set(COSName.of("Contents"), firstStream);
        }
        byte[] existing = firstStream.getDecodedData();
        byte[] combined = new byte[header.length + existing.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(existing, 0, combined, header.length, existing.length);
        firstStream.setDecodedData(combined);
        // Invalidate any cached operator list so a follow-up getContents()
        // re-parses including the new background.
        clearContentsCache();
    }

    private static String formatNumber(double v) {
        if (v == (long) v) return Long.toString((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.4f", v);
        if (s.contains(".")) {
            s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return s;
    }

    /**
     * Returns the 1-based page number (set by PageCollection).
     *
     * @return the page number
     */
    public int getNumber() {
        // Page index is recorded by PageCollection during ensureFlattened();
        // mutations like insert/delete/clear invalidate that cache without
        // updating the per-Page field. Re-trigger the flatten lookup so that
        // Page.getNumber() always reflects the current document order — this
        // is what callers expect when they do `pages.insert(p.getNumber() + 1)`
        // (PDFNEWNET-47713).
        if (owningDocument != null) {
            try {
                owningDocument.getPages().refreshNumber(this);
            } catch (Exception ignored) {
                // Fall back to the stored number on any failure.
            }
        }
        return number;
    }

    /**
     * Sets the 1-based page number. Called by PageCollection during flattening.
     *
     * @param number the page number (1-based)
     */
    public void setNumber(int number) {
        this.number = number;
    }

    /**
     * Sets the media box for this page (ISO 32000, §7.7.3.3, Table 30).
     *
     * @param rect the media box rectangle
     * @throws IllegalArgumentException if rect is null
     */
    public void setMediaBox(Rectangle rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Rectangle must not be null");
        }
        pageDict.set(COSName.MEDIABOX, rect.toCOSArray());
    }

    /**
     * Sets the crop box for this page (ISO 32000, §14.11.2).
     *
     * @param rect the crop box rectangle
     * @throws IllegalArgumentException if rect is null
     */
    public void setCropBox(Rectangle rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Rectangle must not be null");
        }
        pageDict.set(COSName.CROPBOX, rect.toCOSArray());
    }

    /**
     * Sets the art box for this page.
     *
     * @param rect the art box rectangle
     * @throws IllegalArgumentException if rect is null
     */
    public void setArtBox(Rectangle rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Rectangle must not be null");
        }
        pageDict.set(COSName.ARTBOX, rect.toCOSArray());
    }

    /**
     * Sets the bleed box for this page.
     *
     * @param rect the bleed box rectangle
     * @throws IllegalArgumentException if rect is null
     */
    public void setBleedBox(Rectangle rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Rectangle must not be null");
        }
        pageDict.set(COSName.BLEEDBOX, rect.toCOSArray());
    }

    /**
     * Sets the trim box for this page.
     *
     * @param rect the trim box rectangle
     * @throws IllegalArgumentException if rect is null
     */
    public void setTrimBox(Rectangle rect) {
        if (rect == null) {
            throw new IllegalArgumentException("Rectangle must not be null");
        }
        pageDict.set(COSName.TRIMBOX, rect.toCOSArray());
    }

    /**
     * Sets the page rotation in degrees.
     * Must be a multiple of 90 (ISO 32000-1:2008, §7.7.3.3, Table 30).
     *
     * @param degrees the rotation angle (0, 90, 180, or 270)
     * @throws IllegalArgumentException if degrees is not 0, 90, 180, or 270
     */
    public void setRotation(int degrees) {
        if (degrees != 0 && degrees != 90 && degrees != 180 && degrees != 270) {
            throw new IllegalArgumentException("Rotation must be 0, 90, 180, or 270, got: " + degrees);
        }
        pageDict.set(COSName.ROTATE, org.aspose.pdf.engine.cos.COSInteger.valueOf(degrees));
    }

    /**
     * Sets the page rotation using the {@link Rotation} enum.
     * Updates the /Rotate entry in the page dictionary (ISO 32000-1:2008, §7.7.3.3, Table 30).
     *
     * @param rotation the rotation enum value; {@code null} is treated as {@link Rotation#None}
     */
    public void setRotate(Rotation rotation) {
        if (rotation == null) rotation = Rotation.None;
        setRotation(rotation.getDegrees());
    }

    /**
     * Accepts a text absorber to extract text from this page.
     * <p>
     * Works with both {@link TextAbsorber} and its subclass
     * {@link org.aspose.pdf.text.TextFragmentAbsorber} via polymorphism.
     * </p>
     *
     * @param absorber the text absorber
     * @throws IOException if text extraction fails
     */
    public void accept(TextAbsorber absorber) throws IOException {
        if (absorber == null) {
            throw new IllegalArgumentException("Absorber must not be null");
        }
        absorber.visit(this);
    }

    /**
     * Accepts an image placement absorber to find images on this page.
     *
     * @param absorber the image placement absorber
     * @throws IOException if content stream processing fails
     */
    public void accept(ImagePlacementAbsorber absorber) throws IOException {
        if (absorber == null) {
            throw new IllegalArgumentException("Absorber must not be null");
        }
        absorber.visit(this);
    }

    /**
     * Returns the list of layers (Optional Content Groups) on this page.
     * Returns the layers list for this page.
     * If no layers have been set, returns an empty mutable list.
     *
     * @return the layers list
     */
    public java.util.List<Layer> getLayers() {
        if (_layers == null) {
            _layers = new java.util.ArrayList<>();
        }
        return _layers;
    }

    /**
     * Sets the layers for this page.
     *
     * @param layers the layers list
     */
    public void setLayers(java.util.List<Layer> layers) {
        this._layers = layers;
    }

    /**
     * Classifies the dominant colour content of this page —
     * {@link ColorType#Rgb} if the content has any chromatic colour,
     * {@link ColorType#Grayscale} if it uses only gray midtones,
     * {@link ColorType#BlackAndWhite} if every observed colour is solid
     * black or solid white, or {@link ColorType#Undefined} for an empty /
     * unreadable content stream.
     *
     * <p>Inspection walks every paint-style operator
     * ({@code rg/RG/g/G/k/K/sc/SC/scn/SCN}) plus the {@code /ColorSpace} of
     * each {@code Do} image XObject and tracks the worst-case colour family
     * seen. Vector and image content are weighted equally — a single RGB
     * image promotes the whole page to {@link ColorType#Rgb}.</p>
     *
     * @return the page colour classification
     */
    public ColorType getColorType() {
        return PageColorClassifier.classify(this);
    }

    /**
     * Returns the collection of artifacts found on this page.
     * <p>
     * Parses the page's content stream operators and identifies artifact sequences
     * marked by {@code BMC}/{@code BDC}...{@code EMC} operator pairs with the
     * {@code /Artifact} tag (ISO 32000-1:2008, §14.8.2.2).
     * </p>
     * <p>
     * For {@code BDC} operators, the properties dictionary is parsed to extract
     * the artifact type, subtype, and bounding box.
     * </p>
     *
     * @return the artifact collection (never {@code null}; may be empty)
     */
    public ArtifactCollection getArtifacts() {
        if (artifactsCache != null) {
            return artifactsCache;
        }
        ArtifactCollection collection = new ArtifactCollection() {
            @Override
            public void add(Artifact artifact) {
                if (artifact != null && artifact.getRectangle() == null) {
                    Rectangle pageRect = getRect();
                    if (pageRect != null) {
                        artifact.setRectangle(new Rectangle(
                                pageRect.getLLX(),
                                pageRect.getLLY(),
                                pageRect.getURX(),
                                pageRect.getURY()));
                    }
                }
                super.add(artifact);
                appendArtifactToContents(artifact);
            }
        };
        try {
            OperatorCollection ops = getContents();
            Resources resources = getResources();
            int size = ops.size();
            int i = 0;
            while (i < size) {
                Operator op = ops.getAt(i);
                boolean isBDCArtifact = (op instanceof BDC) && "Artifact".equals(((BDC) op).getTag());
                boolean isBMCArtifact = (op instanceof BMC) && "Artifact".equals(((BMC) op).getTag());

                if (isBDCArtifact || isBMCArtifact) {
                    // Create artifact from properties (BDC) or plain (BMC)
                    Artifact artifact;
                    if (isBDCArtifact) {
                        COSDictionary props = resolveArtifactProperties(((BDC) op).getProperties(), resources);
                        artifact = createArtifact(props);
                    } else {
                        artifact = new Artifact();
                    }

                    // Collect operators between BMC/BDC and matching EMC
                    java.util.List<Operator> contentOps = new java.util.ArrayList<>();
                    i++; // move past the BDC/BMC
                    int depth = 1;
                    while (i < size && depth > 0) {
                        Operator inner = ops.getAt(i);
                        if (inner instanceof EMC) {
                            depth--;
                            if (depth == 0) {
                                break; // matched EMC found
                            }
                        } else if (inner instanceof BDC || inner instanceof BMC) {
                            depth++;
                        }
                        contentOps.add(inner);
                        i++;
                    }
                    artifact.setContents(contentOps);
                    collection.add(artifact);
                }
                i++;
            }
        } catch (IOException e) {
            LOG.warning(() -> "Failed to parse artifacts from content stream: " + e.getMessage());
        }
        artifactsCache = collection;
        return artifactsCache;
    }

    /**
     * Persists an artifact added via {@code page.getArtifacts().add(...)} by
     * appending a {@code /Artifact BMC ... EMC} marked-content sequence to the
     * page's content stream. Without this, the artifact would only exist in the
     * in-memory cache and the next save+reopen would not see it (PDFNEWNET-37126,
     * PDFNEWNET-41031).
     * <p>
     * The current implementation emits a minimal BMC/EMC pair — full appearance
     * generation (drawing the background image, watermark text, etc) belongs to
     * the renderer and is out of scope here. {@link #getArtifacts} re-discovers
     * the BMC on reopen via the {@code Artifact} tag, so size-based assertions
     * round-trip correctly.
     * </p>
     */
    private void appendArtifactToContents(Artifact artifact) {
        if (artifact == null) {
            return;
        }
        try {
            java.util.List<Operator> contentOps = artifact.getContents();
            boolean hasExplicitOps = contentOps != null && !contentOps.isEmpty();
            if (!hasExplicitOps) {
                // No parsed operators — give the subclass a chance to
                // synthesise a complete /Artifact BMC ... EMC sequence from
                // its high-level properties (background colour, watermark
                // text/font/opacity, ...). The synthesised bytes already
                // include the BMC/EMC wrapper, so we write them via the raw
                // content-stream path; this avoids re-creating every PDF
                // operator (cm, re, rg, BT, Tf, Tj, ...) in the typed
                // Operator hierarchy.
                byte[] synth = artifact.synthesizeContentBytes(this);
                if (synth != null && synth.length > 0) {
                    if (artifact.isBackground()) {
                        prependToContentStream(synth);
                    } else {
                        appendToContentStream(synth);
                    }
                    return;
                }
            }
            OperatorCollection ops = getContents();
            ops.add(new BMC("Artifact"));
            if (hasExplicitOps) {
                for (Operator op : contentOps) {
                    ops.add(op);
                }
            }
            ops.add(new EMC());
            markContentsDirty();
        } catch (IOException e) {
            LOG.warning(() -> "Failed to serialise artifact into /Contents: " + e.getMessage());
        }
    }

    private COSDictionary resolveArtifactProperties(COSBase properties, Resources resources) {
        COSBase resolved = resolveRef(properties);
        if (resolved instanceof COSDictionary) {
            return (COSDictionary) resolved;
        }
        if (resolved instanceof COSName && resources != null) {
            COSDictionary propertiesDict = resources.getProperties();
            if (propertiesDict != null) {
                COSBase named = resolveRef(propertiesDict.get((COSName) resolved));
                if (named instanceof COSDictionary) {
                    return (COSDictionary) named;
                }
            }
        }
        return null;
    }

    private Artifact createArtifact(COSDictionary properties) {
        Artifact parsed = properties != null ? new Artifact(properties) : new Artifact();
        Artifact result = parsed.getSubtype() == Artifact.ArtifactSubtype.Watermark
                ? new WatermarkArtifact()
                : parsed;
        if (result != parsed) {
            result.setType(parsed.getType());
            result.setSubtype(parsed.getSubtype());
            result.setRectangle(parsed.getRectangle());
            result.setBackground(parsed.isBackground());
            result.setImage(parsed.getImage());
            result.setOpacity(parsed.getOpacity());
            result.setRotation(parsed.getRotation());
            result.setText(parsed.getText());
        }
        return result;
    }

    /**
     * Convenience method to set the page size by setting the media box.
     * Creates a media box from (0, 0) to (width, height).
     *
     * @param width  the page width in points
     * @param height the page height in points
     * @throws IllegalArgumentException if width or height is not positive
     */
    public void setPageSize(double width, double height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Width and height must be positive");
        }
        setMediaBox(new Rectangle(0, 0, width, height));
    }

    /**
     * Returns the paragraph collection for this page. Creates an empty collection on first access.
     *
     * @return the paragraphs
     */
    public Paragraphs getParagraphs() {
        if (paragraphs == null) {
            paragraphs = new Paragraphs();
        }
        return paragraphs;
    }

    /**
     * Sets the paragraph collection for this page.
     *
     * @param paragraphs the paragraphs
     */
    public void setParagraphs(Paragraphs paragraphs) {
        this.paragraphs = paragraphs;
    }

    /**
     * Returns the page info (dimensions and margins) for this page.
     *
     * @return the page info
     */
    public PageInfo getPageInfo() {
        if (pageInfo == null) {
            Rectangle mb = getMediaBox();
            if (mb != null) {
                pageInfo = new PageInfo(mb.getWidth(), mb.getHeight());
            } else {
                pageInfo = new PageInfo();
            }
        }
        return pageInfo;
    }

    /**
     * Sets the page info.
     *
     * @param pageInfo the page info
     */
    public void setPageInfo(PageInfo pageInfo) {
        this.pageInfo = pageInfo;
    }

    /**
     * Returns the header for this page, or null if none.
     *
     * @return the header, or null
     */
    public HeaderFooter getHeader() {
        return header;
    }

    /**
     * Sets the header for this page.
     *
     * @param header the header
     */
    public void setHeader(HeaderFooter header) {
        this.header = header;
        this.headerFooterOverlayApplied = false;
    }

    /**
     * Returns the footer for this page, or null if none.
     *
     * @return the footer, or null
     */
    public HeaderFooter getFooter() {
        return footer;
    }

    /**
     * Sets the footer for this page.
     *
     * @param footer the footer
     */
    public void setFooter(HeaderFooter footer) {
        this.footer = footer;
        this.headerFooterOverlayApplied = false;
    }

    /**
     * Renders this page's header and/or footer (set via {@link #setHeader}/
     * {@link #setFooter}) as a Form XObject overlay appended to the page's
     * existing content stream.
     * <p>
     * PDFNET-38279: when a header/footer is applied to the pages of a document
     * loaded from disk, the layout engine's full page-rebuild path does not run
     * (it would discard the original page content). This method instead renders
     * only the header/footer into a standalone Form XObject — which carries its
     * own {@code /Resources}, so there is no name collision with the page's
     * existing resources — and invokes it with a {@code Do} after the original
     * content. The overlay's text is therefore both visible and extractable
     * (text extraction recurses into Form XObjects).
     * </p>
     * <p>
     * No-op when the page has neither a header nor a footer, or when the overlay
     * was already applied (idempotent across repeated saves).
     * </p>
     *
     * @param pageNumber 1-based page number, for {@code $p}/{@code $P} substitution
     * @param totalPages total page count, for {@code $P} substitution
     * @throws IOException if content-stream generation fails
     */
    public void applyHeaderFooterOverlay(int pageNumber, int totalPages) throws java.io.IOException {
        if (headerFooterOverlayApplied || (header == null && footer == null)) {
            return;
        }
        org.aspose.pdf.engine.layout.LayoutEngine engine =
                new org.aspose.pdf.engine.layout.LayoutEngine();
        engine.setPageNumbering(pageNumber, totalPages);
        org.aspose.pdf.engine.layout.LayoutEngine.HeaderFooterOverlay overlay =
                engine.buildHeaderFooterOverlay(this);
        if (overlay == null || overlay.content.length == 0) {
            return;
        }

        Rectangle box = getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 612, 792);

        // Wrap the rendered header/footer as a Form XObject. The overlay content
        // is authored in page user space, so no placement matrix is needed and
        // BBox is the full media box (clip only).
        org.aspose.pdf.engine.cos.COSStream form = new org.aspose.pdf.engine.cos.COSStream();
        form.set("Type", COSName.of("XObject"));
        form.set("Subtype", COSName.of("Form"));
        form.set("BBox", box.toCOSArray());
        form.set("Resources", overlay.resources);
        form.setDecodedData(overlay.content);

        // Register as an indirect object so the form stream serializes correctly
        // (streams must be indirect; this also routes save() through full rewrite).
        org.aspose.pdf.engine.cos.COSObjectReference formRef = owningDocument != null
                ? owningDocument.registerImportedObject(form)
                : null;

        Resources resources = ensureResources();
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            xObjects = new COSDictionary();
            resources.getCOSDictionary().set(COSName.of("XObject"), xObjects);
        }
        String resName = createUniqueXObjectName(xObjects, "FmHF", 0);
        xObjects.set(COSName.of(resName), formRef != null ? formRef : form);

        String ops = "\nq\n/" + resName + " Do\nQ\n";
        appendToContentStream(ops.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        clearContentsCache();
        headerFooterOverlayApplied = true;
    }

    /** Returns the TOC info for this page, or null. */
    public TocInfo getTocInfo() {
        return tocInfo;
    }

    /** Sets the TOC info, making this page a table-of-contents page. */
    public void setTocInfo(TocInfo tocInfo) {
        this.tocInfo = tocInfo;
    }

    /**
     * Flattens all annotations on this page by baking their normal appearance
     * streams into the page content stream, then removing the /Annots entry
     * (ISO 32000-1:2008, §12.5.5).
     * <p>
     * For each annotation that is not hidden and has a normal appearance stream
     * (/AP /N), a CTM is computed to map the appearance's BBox to the annotation's
     * Rect. The appearance content is wrapped in a {@code q ... cm ... Q} block
     * and appended to the page content stream.
     * Annotations without a normal appearance are silently skipped.
     * </p>
     *
     * @throws IOException if reading appearance streams or appending content fails
     */
    public void flattenAnnotations() throws IOException {
        COSBase annotsVal = resolveRef(pageDict.get(COSName.ANNOTS));
        if (!(annotsVal instanceof COSArray)) return;
        COSArray annotsArray = (COSArray) annotsVal;

        for (int i = 0; i < annotsArray.size(); i++) {
            COSBase item = resolveRef(annotsArray.get(i));
            if (!(item instanceof COSDictionary)) continue;
            COSDictionary annotDict = (COSDictionary) item;

            // Skip hidden annotations (bit 2 of /F)
            int flags = 0;
            COSBase fVal = annotDict.get("F");
            if (fVal instanceof org.aspose.pdf.engine.cos.COSInteger) {
                flags = ((org.aspose.pdf.engine.cos.COSInteger) fVal).intValue();
            }
            if ((flags & 0x02) != 0) continue;

            // Get /AP/N appearance stream
            COSBase ap = resolveRef(annotDict.get("AP"));
            if (!(ap instanceof COSDictionary)) continue;
            COSBase n = resolveRef(((COSDictionary) ap).get("N"));
            if (!(n instanceof COSStream)) continue;
            COSStream apStream = (COSStream) n;

            // Get annotation Rect
            COSBase rectVal = resolveRef(annotDict.get("Rect"));
            if (!(rectVal instanceof COSArray) || ((COSArray) rectVal).size() != 4) continue;
            Rectangle annotRect = Rectangle.fromCOSArray((COSArray) rectVal);
            if (annotRect == null) continue;

            // Get appearance BBox (defaults to Rect if absent)
            COSBase bboxVal = resolveRef(apStream.get("BBox"));
            Rectangle bbox;
            if (bboxVal instanceof COSArray && ((COSArray) bboxVal).size() == 4) {
                bbox = Rectangle.fromCOSArray((COSArray) bboxVal);
            } else {
                bbox = annotRect;
            }
            if (bbox == null) continue;

            // Compute CTM: scale from BBox to Rect, translate to Rect origin
            double bboxW = bbox.getWidth();
            double bboxH = bbox.getHeight();
            if (bboxW == 0 || bboxH == 0) continue;

            double sx = annotRect.getWidth() / bboxW;
            double sy = annotRect.getHeight() / bboxH;
            double tx = annotRect.getLLX() - bbox.getLLX() * sx;
            double ty = annotRect.getLLY() - bbox.getLLY() * sy;

            // Place the appearance as a Form XObject invocation rather than
            // inlining its content. This keeps the appearance's own internal
            // operators (including any nested `cm`) out of the page content
            // stream, so the only matrix the flatten step contributes is the
            // BBox->Rect placement CTM (ISO 32000-1:2008 §12.5.5, Aspose-compat).
            // BUG-F4 fix (Sprint 21): inlining previously leaked the appearance's
            // internal cm operators into the page content. Verified zero
            // regressions vs the inline path across flatten-using regression
            // classes (text extraction + rendering both recurse into Do forms).
            apStream.set("Type", COSName.of("XObject"));
            apStream.set("Subtype", COSName.of("Form"));
            COSDictionary xObjects = getResources().getXObjects();
            if (xObjects == null) {
                xObjects = new COSDictionary();
                getResources().getCOSDictionary().set(COSName.of("XObject"), xObjects);
            }
            String resName = createUniqueXObjectName(xObjects, "FmFlat", 0);
            xObjects.set(COSName.of(resName), apStream);

            StringBuilder sb = new StringBuilder();
            sb.append("\nq\n");
            sb.append(formatDouble(sx)).append(' ')
              .append("0 0 ")
              .append(formatDouble(sy)).append(' ')
              .append(formatDouble(tx)).append(' ')
              .append(formatDouble(ty)).append(" cm\n");
            sb.append('/').append(resName).append(" Do\n");
            sb.append("Q\n");

            appendToContentStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        // Remove /Annots from page dictionary
        pageDict.remove(COSName.ANNOTS);
    }

    /**
     * Merges resources from an appearance stream into the page resources.
     * Ensures fonts, XObjects, etc. referenced by the appearance are available
     * in the page's resource dictionary.
     *
     * @param apStream the appearance stream whose resources should be merged
     */
    private void mergeAppearanceResources(COSStream apStream) {
        COSBase apResVal = resolveRef(apStream.get("Resources"));
        if (!(apResVal instanceof COSDictionary)) return;
        COSDictionary apRes = (COSDictionary) apResVal;

        // Ensure page has a Resources dictionary
        COSBase pageResVal = getInheritable(COSName.RESOURCES);
        COSDictionary pageRes;
        if (pageResVal instanceof COSDictionary) {
            pageRes = (COSDictionary) pageResVal;
        } else {
            pageRes = new COSDictionary();
            pageDict.set(COSName.RESOURCES, pageRes);
        }

        // Merge each sub-dictionary (Font, XObject, ExtGState, etc.)
        String[] categories = {"Font", "XObject", "ExtGState", "ColorSpace", "Pattern", "Shading"};
        for (String cat : categories) {
            COSBase apCatVal = resolveRef(apRes.get(cat));
            if (!(apCatVal instanceof COSDictionary)) continue;
            COSDictionary apCat = (COSDictionary) apCatVal;

            COSBase pageCatVal = resolveRef(pageRes.get(cat));
            COSDictionary pageCat;
            if (pageCatVal instanceof COSDictionary) {
                pageCat = (COSDictionary) pageCatVal;
            } else {
                pageCat = new COSDictionary();
                pageRes.set(COSName.of(cat), pageCat);
            }

            for (COSName key : apCat.keySet()) {
                if (pageCat.get(key) == null) {
                    pageCat.set(key, apCat.get(key));
                }
            }
        }
    }

    /**
     * Formats a double value for use in PDF content streams,
     * outputting integers without a decimal point.
     */
    private static String formatDouble(double val) {
        if (val == (long) val) return String.valueOf((long) val);
        return String.valueOf(val);
    }

    // ======================== Stamp Support ========================

    /**
     * Adds a stamp to this page by delegating to the stamp's {@link Stamp#put(Page)} method.
     * <p>
     * This is the generic entry point for applying any stamp type. The stamp
     * determines how it renders itself onto the page.
     * </p>
     *
     * @param stamp the stamp to add; must not be {@code null}
     * @throws IOException if content stream generation or merging fails
     */
    public void addStamp(Stamp stamp) throws IOException {
        if (stamp == null) throw new IllegalArgumentException("Stamp must not be null");
        stamp.put(this);
    }

    /**
     * Adds a text stamp to this page by generating content stream operators.
     * <p>
     * The stamp text is rendered at the position determined by the stamp's
     * alignment and indent settings. If {@code isBackground()} is true, the
     * stamp is prepended before existing content; otherwise it is appended.
     * </p>
     *
     * @param stamp the text stamp to add
     * @throws IOException if content stream generation fails
     */
    public void addStamp(TextStamp stamp) throws IOException {
        if (stamp == null) throw new IllegalArgumentException("Stamp must not be null");
        Rectangle box = getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 612, 792);

        // Apply margins to position calculation
        double xIndent = stamp.getXIndent() + stamp.getLeftMargin();
        double yIndent = stamp.getYIndent() + stamp.getBottomMargin();
        double recordedWidth = stamp.getWidth() > 0 ? stamp.getWidth() : estimateTextStampWidth(stamp);
        double recordedHeight = stamp.getHeight() > 0 ? stamp.getHeight() : estimateTextStampHeight(stamp);
        double x = resolveStampX(stamp.getHorizontalAlignment(), xIndent, recordedWidth, box);
        double y = resolveStampY(stamp.getVerticalAlignment(), yIndent, recordedHeight, box);

        Document targetDocument = owningDocument;
        COSObjectReference formRef = stamp.getCachedFormReference();
        if (formRef == null || stamp.getCachedTargetDocument() != targetDocument) {
            COSStream formStream = createTextStampFormXObject(stamp, recordedWidth, recordedHeight);
            formRef = targetDocument.registerImportedObject(formStream);
            stamp.cacheFormReference(targetDocument, formRef);
        }

        Resources resources = ensureResources();
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            xObjects = new COSDictionary();
            resources.getCOSDictionary().set(COSName.of("XObject"), xObjects);
        }
        String resourceName = createUniqueXObjectName(xObjects, "Fm", stamp.getStampId());
        xObjects.set(COSName.of(resourceName), formRef);

        ContentStreamBuilder builder = new ContentStreamBuilder();
        builder.saveState();
        double rotation = stamp.getRotateAngle();
        if (rotation != 0) {
            double rad = Math.toRadians(rotation);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            builder.concatMatrix(cos, sin, -sin, cos, x, y);
        } else {
            builder.concatMatrix(1, 0, 0, 1, x, y);
        }
        builder.drawXObject(resourceName);
        builder.restoreState();

        byte[] ops = wrapStampContent(builder.toByteArray(), stamp.getStampId(), "TextStamp", resourceName);
        if (stamp.isBackground()) {
            prependToContentStream(ops);
        } else {
            appendToContentStream(ops);
        }
        recordStampInfo("TextStamp", stamp.getStampId(), stamp.getValue(), x, y,
                recordedWidth, recordedHeight, resourceName);
    }

    /**
     * Adds an image stamp to this page by generating content stream operators.
     * <p>
     * The image is added as an XObject resource and rendered at the position
     * determined by the stamp's alignment and indent settings.
     * </p>
     *
     * @param stamp the image stamp to add
     * @throws IOException if image loading or content stream generation fails
     */
    public void addStamp(ImageStamp stamp) throws IOException {
        if (stamp == null) throw new IllegalArgumentException("Stamp must not be null");

        // Materialise the image bytes. ImageStamp may carry either a file path
        // or an InputStream; prefer the stream when set.
        byte[] imageBytes = readImageBytes(stamp);
        if (imageBytes == null || imageBytes.length == 0) {
            throw new IllegalArgumentException(
                    "ImageStamp must carry image data (set file or imageStream)");
        }

        // Build the Image XObject COSStream up-front so we can register it
        // under a unique resource name before emitting the Do operator.
        COSStream imageXObject = XImage.createImageStream(imageBytes);

        Resources resources = ensureResources();
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            xObjects = new COSDictionary();
            resources.getCOSDictionary().set(COSName.of("XObject"), xObjects);
        }
        String imageRes = createUniqueXObjectName(xObjects, "Im", stamp.getStampId());
        xObjects.set(COSName.of(imageRes), imageXObject);

        Rectangle box = getMediaBox();
        if (box == null) box = new Rectangle(0, 0, 612, 792);

        double w = stamp.getWidth() > 0 ? stamp.getWidth() : 100;
        double h = stamp.getHeight() > 0 ? stamp.getHeight() : 100;
        double x = box.getLLX() + stamp.getXIndent();
        double y = box.getLLY() + stamp.getYIndent();

        ContentStreamBuilder builder = new ContentStreamBuilder();
        builder.saveState();

        double rotation = stamp.getRotateAngle();
        if (rotation != 0) {
            double rad = Math.toRadians(rotation);
            double cos = Math.cos(rad);
            double sin = Math.sin(rad);
            builder.concatMatrix(cos, sin, -sin, cos, x, y);
            builder.concatMatrix(w, 0, 0, h, 0, 0);
        } else {
            builder.concatMatrix(w, 0, 0, h, x, y);
        }

        builder.drawXObject(imageRes);
        builder.restoreState();

        byte[] ops = wrapStampContent(builder.toByteArray(), stamp.getStampId(), "ImageStamp", imageRes);
        if (stamp.isBackground()) {
            prependToContentStream(ops);
        } else {
            appendToContentStream(ops);
        }
        recordStampInfo("ImageStamp", stamp.getStampId(), null, x, y, w, h, imageRes);
    }

    private static byte[] readImageBytes(ImageStamp stamp) throws IOException {
        InputStream is = stamp.getImageStream();
        if (is != null) {
            return readAllBytes(is);
        }
        String file = stamp.getFile();
        if (file != null && !file.isEmpty()) {
            try (InputStream fis = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(file))) {
                return readAllBytes(fis);
            }
        }
        return null;
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    /**
     * Adds a page stamp to this page by overlaying the source page content.
     *
     * @param stamp the page stamp to add
     * @throws IOException if content stream processing fails
     */
    public void addStamp(PdfPageStamp stamp) throws IOException {
        if (stamp == null) throw new IllegalArgumentException("Stamp must not be null");

        Page source = stamp.getSourcePage();
        if (source == null) throw new IllegalArgumentException("Source page must not be null");
        Document targetDocument = owningDocument;
        if (targetDocument == null) {
            throw new IOException("Page is not attached to a document");
        }

        COSObjectReference formRef = stamp.getCachedFormReference();
        if (formRef == null || stamp.getCachedTargetDocument() != targetDocument) {
            Rectangle sourceRect = source.getRect();
            if (sourceRect == null) {
                sourceRect = new Rectangle(0, 0, 612, 792);
            }
            COSStream formStream = createStampFormXObject(targetDocument, source, sourceRect);
            formRef = targetDocument.registerImportedObject(formStream);
            stamp.cacheFormReference(targetDocument, formRef);
        }

        Resources resources = ensureResources();
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            xObjects = new COSDictionary();
            resources.getCOSDictionary().set(COSName.of("XObject"), xObjects);
        }
        String resourceName = createUniqueXObjectName(xObjects, "FmStamp", stamp.getStampId());
        xObjects.set(COSName.of(resourceName), formRef);

        ContentStreamBuilder builder = new ContentStreamBuilder();
        builder.saveState();
        builder.drawXObject(resourceName);
        builder.restoreState();
        byte[] wrapped = wrapStampContent(builder.toByteArray(), stamp.getStampId(), "PdfPageStamp", resourceName);

        if (stamp.isBackground()) {
            prependToContentStream(wrapped);
        } else {
            appendToContentStream(wrapped);
        }
        Rectangle rect = source.getRect();
        recordStampInfo("PdfPageStamp", stamp.getStampId(), null, 0, 0,
                rect != null ? rect.getWidth() : 0, rect != null ? rect.getHeight() : 0, resourceName);
    }

    /**
     * Returns stamp metadata records stored on this page.
     *
     * @return a mutable array of metadata dictionaries
     */
    public COSArray getStampInfoRecords() {
        COSBase existing = resolveRef(pageDict.get(OPENPDF_STAMP_INFO));
        if (existing instanceof COSArray) {
            return (COSArray) existing;
        }
        COSArray records = new COSArray();
        pageDict.set(OPENPDF_STAMP_INFO, records);
        return records;
    }

    public boolean removeStampById(int stampId) throws IOException {
        boolean removed = false;
        COSArray records = getStampInfoRecords();
        java.util.List<String> resourceNames = new java.util.ArrayList<>();
        for (int i = records.size() - 1; i >= 0; i--) {
            COSBase item = records.get(i);
            if (!(item instanceof COSDictionary)) {
                continue;
            }
            COSDictionary dict = (COSDictionary) item;
            if (dict.getInt("StampId", Integer.MIN_VALUE) != stampId) {
                continue;
            }
            COSBase resourceBase = dict.get("ResourceName");
            if (resourceBase instanceof COSString) {
                resourceNames.add(((COSString) resourceBase).getString());
            }
            records.remove(i);
            removed = true;
        }
        removed |= removeStampedContentStreams(stampId);
        for (String resourceName : resourceNames) {
            removeXObjectResource(resourceName);
        }
        return removed;
    }

    private void recordStampInfo(String type, int stampId, String text,
                                 double x, double y, double width, double height) {
        recordStampInfo(type, stampId, text, x, y, width, height, null);
    }

    private void recordStampInfo(String type, int stampId, String text,
                                 double x, double y, double width, double height,
                                 String resourceName) {
        COSDictionary info = new COSDictionary();
        info.set("Type", new COSString(type));
        info.set("StampId", COSInteger.valueOf(stampId));
        if (text != null) {
            info.set("Text", new COSString(text));
        }
        info.set("X", new COSFloat(x));
        info.set("Y", new COSFloat(y));
        info.set("Width", new COSFloat(width));
        info.set("Height", new COSFloat(height));
        if (resourceName != null && !resourceName.isEmpty()) {
            info.set("ResourceName", new COSString(resourceName));
        }
        getStampInfoRecords().add(info);
    }

    private double estimateTextStampWidth(TextStamp stamp) {
        String value = stamp.getValue();
        if (value == null) {
            return 0;
        }
        double fontSize = stamp.getTextState().getFontSize();
        if (fontSize <= 0) {
            fontSize = 12;
        }
        return Math.max(fontSize, value.length() * fontSize * 0.5);
    }

    private double estimateTextStampHeight(TextStamp stamp) {
        double fontSize = stamp.getTextState().getFontSize();
        return fontSize > 0 ? fontSize * 1.2 : 14.4;
    }

    private COSStream createTextStampFormXObject(TextStamp stamp, double width, double height) {
        COSStream formStream = new COSStream();
        formStream.set("Type", COSName.of("XObject"));
        formStream.set("Subtype", COSName.of("Form"));
        formStream.set("BBox", new Rectangle(0, 0, Math.max(width, 1.0), Math.max(height, 1.0)).toCOSArray());

        ContentStreamBuilder builder = new ContentStreamBuilder();
        builder.beginText();
        String fontName = stamp.getTextState().getFontName();
        if (fontName == null || fontName.isEmpty()) {
            fontName = "Helvetica";
        }
        double fontSize = stamp.getTextState().getFontSize();
        if (fontSize <= 0) {
            fontSize = 12;
        }
        String fontRes = builder.registerFont(fontName);
        builder.setFont(fontRes, fontSize);

        Color fg = stamp.getTextState().getForegroundColor();
        if (fg != null) {
            builder.setRGBFillColor(fg.getR(), fg.getG(), fg.getB());
        }

        builder.setTextMatrix(1, 0, 0, 1, 0, 0);
        builder.showText(stamp.getValue() != null ? stamp.getValue() : "");
        builder.endText();

        formStream.setDecodedData(builder.toByteArray());
        COSDictionary resources = new COSDictionary();
        mergeFontResources(resources, builder);
        formStream.set("Resources", resources);
        return formStream;
    }

    /**
     * Resolves the X position based on horizontal alignment.
     */
    private double resolveStampX(HorizontalAlignment align, double indent, double width, Rectangle box) {
        if (align == null) align = HorizontalAlignment.None;
        switch (align) {
            case Center: return box.getLLX() + (box.getWidth() - width) / 2;
            case Right:  return box.getLLX() + box.getWidth() - indent - width;
            default:     return box.getLLX() + indent;
        }
    }

    /**
     * Resolves the Y position based on vertical alignment.
     */
    private double resolveStampY(VerticalAlignment align, double indent, double height, Rectangle box) {
        if (align == null) align = VerticalAlignment.None;
        switch (align) {
            case Center: return box.getLLY() + (box.getHeight() - height) / 2;
            case Top:    return box.getLLY() + box.getHeight() - indent - height;
            default:     return box.getLLY() + indent;
        }
    }

    /**
     * Wraps text into lines that fit within the given width.
     * Uses an approximate character width based on the font size.
     *
     * @param text       the text to wrap
     * @param fontName   the font name (used for width estimation)
     * @param fontSize   the font size in points
     * @param availWidth the available width in points
     * @return list of text lines
     */
    private java.util.List<String> wrapText(String text, String fontName, double fontSize, double availWidth) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (text == null || text.isEmpty()) {
            lines.add("");
            return lines;
        }
        // Approximate average character width as 0.5 * fontSize for standard fonts
        double avgCharWidth = fontSize * 0.5;
        if (avgCharWidth <= 0) avgCharWidth = 6;
        int maxCharsPerLine = (int) (availWidth / avgCharWidth);
        if (maxCharsPerLine <= 0) maxCharsPerLine = 1;

        String[] words = text.split(" ");
        StringBuilder currentLine = new StringBuilder();
        for (String word : words) {
            if (currentLine.length() == 0) {
                currentLine.append(word);
            } else if (currentLine.length() + 1 + word.length() <= maxCharsPerLine) {
                currentLine.append(' ').append(word);
            } else {
                lines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
            }
        }
        if (currentLine.length() > 0) {
            lines.add(currentLine.toString());
        }
        return lines;
    }

    /**
     * Merges font resources from a ContentStreamBuilder into this page's resources.
     */
    private void mergeFontResources(ContentStreamBuilder builder) {
        Resources res = ensureResources();
        mergeFontResources(res.getCOSDictionary(), builder);
    }

    private void mergeFontResources(COSDictionary resDict, ContentStreamBuilder builder) {
        COSBase fontsBase = resDict.get("Font");
        COSDictionary fontsDict;
        if (fontsBase instanceof COSDictionary) {
            fontsDict = (COSDictionary) fontsBase;
        } else {
            fontsDict = new COSDictionary();
            resDict.set(COSName.of("Font"), fontsDict);
        }

        for (java.util.Map.Entry<String, String> entry : builder.getFontResources().entrySet()) {
            String baseFont = entry.getKey();
            String resName = entry.getValue();
            if (fontsDict.get(resName) == null) {
                COSDictionary fontDict = new COSDictionary();
                fontDict.set(COSName.of("Type"), COSName.of("Font"));
                fontDict.set(COSName.of("Subtype"), COSName.of("Type1"));
                fontDict.set(COSName.of("BaseFont"), COSName.of(baseFont));
                fontsDict.set(COSName.of(resName), fontDict);
            }
        }
    }

    /**
     * Prepends raw content stream data before this page's existing /Contents.
     * <p>
     * If /Contents is absent, creates a new COSStream. If /Contents exists,
     * inserts the new stream before the existing content.
     * </p>
     *
     * @param data the content stream bytes to prepend
     */
    public void prependToContentStream(byte[] data) {
        if (data == null) throw new IllegalArgumentException("Data must not be null");
        // Flush any pending cache mutations into /Contents before prepending,
        // then invalidate so the next getContents() re-parses the combined
        // content stream including the freshly prepended bytes.
        try { flushContentsIfDirty(); } catch (IOException ignored) {}
        clearContentsCache();
        COSStream newStream = new COSStream();
        newStream.setDecodedData(data);

        COSBase existing = resolveRef(pageDict.get(COSName.CONTENTS));
        if (existing == null) {
            pageDict.set(COSName.CONTENTS, newStream);
        } else if (existing instanceof COSStream) {
            COSArray arr = new COSArray();
            arr.add(newStream);
            arr.add(existing);
            pageDict.set(COSName.CONTENTS, arr);
        } else if (existing instanceof COSArray) {
            COSArray arr = (COSArray) existing;
            // Insert at beginning
            COSArray newArr = new COSArray();
            newArr.add(newStream);
            for (int i = 0; i < arr.size(); i++) {
                newArr.add(arr.get(i));
            }
            pageDict.set(COSName.CONTENTS, newArr);
        }
    }

    /**
     * Appends raw content stream data to this page's /Contents.
     * <p>
     * If /Contents is absent, creates a new COSStream. If /Contents is a COSStream,
     * wraps the existing stream and the new stream in a COSArray. If /Contents is
     * already a COSArray, appends a new COSStream to it.
     * </p>
     *
     * @param data the content stream bytes to append
     * @throws IllegalArgumentException if data is null
     */
    public void appendToContentStream(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Data must not be null");
        }
        // Flush any pending cache mutations into /Contents before appending,
        // then invalidate so the next getContents() re-parses the combined
        // content stream including the freshly appended bytes.
        try { flushContentsIfDirty(); } catch (IOException ignored) {}
        clearContentsCache();
        COSStream newStream = new COSStream();
        newStream.setDecodedData(data);

        COSBase existing = resolveRef(pageDict.get(COSName.CONTENTS));
        if (existing == null) {
            pageDict.set(COSName.CONTENTS, newStream);
        } else if (existing instanceof COSStream) {
            COSArray arr = new COSArray();
            arr.add(existing);
            arr.add(newStream);
            pageDict.set(COSName.CONTENTS, arr);
        } else if (existing instanceof COSArray) {
            ((COSArray) existing).add(newStream);
        }
    }

    private boolean removeStampedContentStreams(int stampId) throws IOException {
        String marker = OPENPDF_STAMP_BEGIN + stampId + ":";
        COSBase existing = resolveRef(pageDict.get(COSName.CONTENTS));
        if (existing == null) {
            return false;
        }
        if (existing instanceof COSStream) {
            byte[] data = ((COSStream) existing).getDecodedData();
            if (containsStampMarker(data, marker)) {
                ((COSStream) existing).setDecodedData(new byte[0]);
                clearContentsCache();
                return true;
            }
            return false;
        }
        if (existing instanceof COSArray) {
            COSArray array = (COSArray) existing;
            COSArray kept = new COSArray();
            boolean removed = false;
            for (int i = 0; i < array.size(); i++) {
                COSBase item = array.get(i);
                COSBase resolved = resolveRef(item);
                if (resolved instanceof COSStream
                        && containsStampMarker(((COSStream) resolved).getDecodedData(), marker)) {
                    removed = true;
                    continue;
                }
                kept.add(item);
            }
            if (removed) {
                if (kept.size() == 0) {
                    pageDict.remove(COSName.CONTENTS);
                } else if (kept.size() == 1) {
                    pageDict.set(COSName.CONTENTS, kept.get(0));
                } else {
                    pageDict.set(COSName.CONTENTS, kept);
                }
                clearContentsCache();
            }
            return removed;
        }
        return false;
    }

    private boolean containsStampMarker(byte[] data, String marker) {
        if (data == null || data.length == 0) {
            return false;
        }
        return new String(data, java.nio.charset.StandardCharsets.US_ASCII).contains(marker);
    }

    private void removeXObjectResource(String resourceName) throws IOException {
        if (resourceName == null || resourceName.isEmpty()) {
            return;
        }
        Resources resources = getResources();
        if (resources == null) {
            return;
        }
        COSDictionary xObjects = resources.getXObjects();
        if (xObjects == null) {
            return;
        }
        xObjects.remove(COSName.of(resourceName));
        if (xObjects.isEmpty()) {
            resources.getCOSDictionary().remove(COSName.of("XObject"));
        }
    }

    private String createUniqueXObjectName(COSDictionary xObjects, String prefix, int stampId) {
        String base = prefix + (stampId > 0 ? stampId : Integer.toUnsignedString(System.identityHashCode(this)));
        String candidate = base;
        int counter = 1;
        while (xObjects.get(COSName.of(candidate)) != null) {
            counter++;
            candidate = base + "_" + counter;
        }
        return candidate;
    }

    private byte[] wrapStampContent(byte[] content, int stampId, String type, String resourceName) {
        StringBuilder sb = new StringBuilder();
        sb.append(OPENPDF_STAMP_BEGIN).append(stampId).append(':').append(type);
        if (resourceName != null && !resourceName.isEmpty()) {
            sb.append(':').append(resourceName);
        }
        sb.append('\n');
        byte[] prefix = sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] suffix = (OPENPDF_STAMP_END + stampId + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] wrapped = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, wrapped, 0, prefix.length);
        System.arraycopy(content, 0, wrapped, prefix.length, content.length);
        System.arraycopy(suffix, 0, wrapped, prefix.length + content.length, suffix.length);
        return wrapped;
    }

    private COSStream createStampFormXObject(Document targetDocument, Page sourcePage, Rectangle sourceRect) throws IOException {
        COSStream formStream = new COSStream();
        formStream.set("Type", COSName.of("XObject"));
        formStream.set("Subtype", COSName.of("Form"));
        formStream.set("BBox", sourceRect.toCOSArray());

        COSDictionary sourceResources = sourcePage.getResources() != null
                ? sourcePage.getResources().getCOSDictionary()
                : null;
        if (sourceResources != null) {
            COSCloner cloner = new COSCloner(targetDocument::registerImportedObject);
            COSBase clonedResources = cloner.cloneAny(sourceResources);
            if (clonedResources instanceof COSDictionary) {
                formStream.set("Resources", clonedResources);
            }
        }

        byte[] sourceData = readPageContentBytes(sourcePage);
        formStream.setDecodedData(sourceData != null ? sourceData : new byte[0]);
        return formStream;
    }

    private byte[] readPageContentBytes(Page sourcePage) throws IOException {
        COSBase sourceContents = sourcePage.getRawContents();
        if (sourceContents instanceof COSObjectReference) {
            sourceContents = ((COSObjectReference) sourceContents).dereference();
        }
        if (sourceContents instanceof COSStream) {
            return ((COSStream) sourceContents).getDecodedData();
        }
        if (sourceContents instanceof COSArray) {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            COSArray arr = (COSArray) sourceContents;
            for (int i = 0; i < arr.size(); i++) {
                COSBase item = arr.get(i);
                if (item instanceof COSObjectReference) {
                    try {
                        item = ((COSObjectReference) item).dereference();
                    } catch (Exception ignored) {
                    }
                }
                if (item instanceof COSStream) {
                    byte[] decoded = ((COSStream) item).getDecodedData();
                    if (decoded != null) {
                        baos.write(decoded);
                    }
                }
            }
            return baos.toByteArray();
        }
        return null;
    }

    /**
     * Ensures this page has a /Resources dictionary, creating one if absent.
     *
     * @return the Resources object for this page (never null)
     */
    public Resources ensureResources() {
        Resources res = getResources();
        if (res == null) {
            COSDictionary resDict = new COSDictionary();
            pageDict.set(COSName.RESOURCES, resDict);
            res = new Resources(resDict);
        }
        return res;
    }

    /**
     * Replaces the page content stream with the given operator collection.
     * <p>
     * Serializes the operators to content stream syntax and stores them
     * as a new COSStream in the page dictionary's /Contents entry.
     * </p>
     *
     * @param operators the operator collection to set as the page content
     * @throws IOException if serialization fails
     */
    public void setContents(OperatorCollection operators) throws IOException {
        if (operators == null) {
            throw new IllegalArgumentException("Operators must not be null");
        }
        StringBuilder sb = new StringBuilder();
        for (Operator op : operators) {
            sb.append(op.toString()).append('\n');
        }
        byte[] data = sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        COSStream stream = new COSStream();
        stream.setDecodedData(data);
        pageDict.set(COSName.CONTENTS, stream);
        // Replace the cache: the caller-supplied collection becomes the new
        // authoritative view; nothing is dirty because we just wrote it.
        this.contentsCache = operators;
        this.contentsDirty = false;
    }

    /**
     * Looks up an inheritable property by walking up the /Parent chain
     * (ISO 32000-1:2008, §7.7.3.4).
     *
     * @param key the property name
     * @return the resolved value, or null if not found in the tree
     */
    private COSBase getInheritable(COSName key) {
        COSDictionary current = pageDict;
        while (current != null) {
            COSBase value = current.get(key);
            if (value != null) {
                return resolveRef(value);
            }
            COSBase parent = current.get(COSName.PARENT);
            parent = resolveRef(parent);
            current = (parent instanceof COSDictionary) ? (COSDictionary) parent : null;
        }
        return null;
    }

    /**
     * Resolves an indirect object reference. If the value is a COSObjectReference,
     * dereferences it. Otherwise returns the value as-is.
     *
     * @param value the COS value to resolve
     * @return the resolved value, or null
     */
    private COSBase resolveRef(COSBase value) {
        if (value == null) {
            return null;
        }
        if (value instanceof COSObjectReference) {
            try {
                return ((COSObjectReference) value).dereference();
            } catch (IOException e) {
                LOG.warning(() -> "Failed to dereference: " + e.getMessage());
                return null;
            }
        }
        return value;
    }

    /**
     * Converts a COS value to a Rectangle if it is a COSArray of 4 elements.
     *
     * @param value the COS value
     * @return the Rectangle, or null
     */
    private Rectangle toRectangle(COSBase value) {
        if (value instanceof COSArray) {
            COSArray array = (COSArray) value;
            if (array.size() == 4) {
                return Rectangle.fromCOSArray(array);
            }
        }
        return null;
    }

    /**
     * Returns {@code true} if the page has no visible content (empty or absent content stream).
     * <p>
     * Examines the content stream operators and returns {@code false} if any
     * text-showing, image-drawing, or path-painting operator is found.
     * </p>
     *
     * @return {@code true} if the page appears blank
     * @throws IOException if content stream parsing fails
     */
    public boolean isBlank() throws IOException {
        COSBase contents = pageDict.get(COSName.CONTENTS);
        if (contents == null) return true;
        OperatorCollection ops = getContents();
        for (Operator op : ops) {
            String n = op.getName();
            if ("Tj".equals(n) || "TJ".equals(n) || "Do".equals(n) ||
                "re".equals(n) || "m".equals(n) || "l".equals(n) ||
                "f".equals(n) || "F".equals(n) || "S".equals(n) ||
                "B".equals(n) || "b".equals(n)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces all occurrences of {@code searchText} with {@code replaceText}
     * in this page's content stream.
     *
     * @param searchText  the text to find
     * @param replaceText the replacement text
     * @return the number of replacements made
     * @throws IOException if content stream processing fails
     */
    public int replaceText(String searchText, String replaceText) throws IOException {
        if (searchText == null || searchText.isEmpty()) return 0;

        TextFragmentAbsorber absorber = new TextFragmentAbsorber(searchText);
        accept(absorber);

        TextFragmentCollection matches = absorber.getTextFragments();
        int count = 0;
        for (int i = 0; i < matches.size(); i++) {
            TextFragment frag = matches.get(i);
            frag.setText(replaceText);
            count++;
        }
        return count;
    }
}
