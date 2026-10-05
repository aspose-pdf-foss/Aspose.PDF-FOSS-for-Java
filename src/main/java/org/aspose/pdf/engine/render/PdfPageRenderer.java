package org.aspose.pdf.engine.render;

import org.aspose.pdf.ExtGState;
import org.aspose.pdf.Matrix;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;
import org.aspose.pdf.Resources;
import org.aspose.pdf.XForm;
import org.aspose.pdf.XImage;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfName;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.engine.pdfobjects.PdfStream;
import org.aspose.pdf.engine.pdfobjects.PdfString;
import org.aspose.pdf.engine.parser.PDFParser;
import org.aspose.pdf.operators.*;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Logger;

/**
 * Core PDF page rendering engine (ISO 32000-1:2008, §8 &amp; §9).
 * <p>
 * Processes content stream operators and renders graphics, text, and images
 * onto a {@link java.awt.Graphics2D} context backed by a {@link BufferedImage}.
 * </p>
 * <p>
 * The renderer handles:
 * <ul>
 *   <li>Graphics state (q/Q, cm, w, J, j, M, d, gs)</li>
 *   <li>Color operators (rg, RG, g, G, k, K, cs, sc, scn, CS, SC, SCN)</li>
 *   <li>Path construction (m, l, c, v, y, re, h) and painting (S, s, f, F, f*, B, B*, b, b*, n)</li>
 *   <li>Clipping (W, W*)</li>
 *   <li>Text (BT, ET, Tf, Td, TD, Tm, T*, Tc, Tw, Tz, TL, Tr, Ts, Tj, TJ, ', ")</li>
 *   <li>XObjects — images (Do with /Image) and forms (Do with /Form)</li>
 * </ul>
 * </p>
 */
public class PdfPageRenderer {

    private static final Logger LOG = Logger.getLogger(PdfPageRenderer.class.getName());

    /** Maximum recursion depth for Form XObjects to prevent infinite loops. */
    private static final int MAX_FORM_DEPTH = 10;

    private final TextRenderer textRenderer = new TextRenderer();

    /** Canvas size in device pixels of the page being rendered — sizes the
     *  offscreen buffers for transparency-group compositing. */
    private int canvasPixelW;
    private int canvasPixelH;

    /** Optional-content groups hidden for this render (§8.11.4: the catalog's
     *  /OCProperties default configuration; in Acrobat-print-parity mode the
     *  /Usage /Print /PrintState overrides it). Identity-keyed — the parser
     *  caches resolved objects, so a group's dictionary is one instance. */
    private final java.util.Set<PdfDictionary> hiddenOcgs =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** Marked-content nesting: true = that BDC level suppresses painting. */
    private final Deque<Boolean> mcStack = new ArrayDeque<>();
    /** Count of active suppressing marked-content levels (0 = paint normally). */
    private int ocSuppress;

    /** When true, text-show ops advance the text matrix but paint nothing
     *  (executed at Tr=3, invisible) — used to rasterize a page's vector-only
     *  underlay for HTML export without duplicating the HTML text layer. */
    private boolean suppressText;
    /** With {@link #suppressText}: keep NON-HORIZONTAL text painted (fixed-layout
     *  targets re-emit only horizontal text as positioned frames). */
    private boolean suppressTextKeepRotated;
    /** With {@link #suppressText}: keep TYPE 3 text painted. Type 3 glyphs are
     *  drawn by content-stream procedures (§9.6.5) — they are graphics, and when
     *  the font has no /ToUnicode their codes cannot be recovered as text, so the
     *  HTML export renders them into the vector underlay instead of emitting
     *  garbage text spans. */
    private boolean suppressTextKeepType3;
    /** When true, raster images (Image XObjects and inline BI images) are
     *  skipped; vector paths, shadings and Form-XObject recursion still paint. */
    private boolean suppressRasterImages;
    {
        // Type 3 glyphs are content streams (§9.6.5); the text renderer calls
        // back into this operator machinery to execute them.
        textRenderer.setType3Executor(this::executeType3GlyphStream);
    }

    /**
     * Suppresses text painting for subsequent renders (text-show ops still
     * advance the text matrix, so surrounding graphics are unaffected).
     *
     * @param suppressText true to paint no glyphs
     */
    public void setSuppressText(boolean suppressText) {
        this.suppressText = suppressText;
    }

    /**
     * With {@link #setSuppressText}: keeps rotated (non-horizontal) text painted.
     * A fixed-layout export positions only horizontal text as editable frames,
     * so diagonal/vertical labels must stay visible in the underlay pixels.
     *
     * @param keep true to keep rotated text
     */
    public void setSuppressTextKeepRotated(boolean keep) {
        this.suppressTextKeepRotated = keep;
    }

    /**
     * With {@link #setSuppressText}: keeps Type 3 text painted so the HTML
     * vector underlay carries glyphs whose codes have no Unicode mapping.
     *
     * @param keep true to keep Type 3 text
     */
    public void setSuppressTextKeepType3(boolean keep) {
        this.suppressTextKeepType3 = keep;
    }

    /** True when the resource-named current font is a Type 3 font. Cheap dict
     *  lookup (Subtype only), no glyph-program loading. */
    private static boolean isType3Font(GraphicsState state, Resources resources) {
        if (resources == null) return false;
        String fn = state.getFontName();
        if (fn == null) return false;
        org.aspose.pdf.engine.pdfobjects.PdfDictionary fonts = resources.getFonts();
        if (fonts == null) return false;
        try {
            org.aspose.pdf.engine.pdfobjects.PdfBase f = fonts.get(fn);
            if (f instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
                f = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) f).dereference();
            }
            return f instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary
                && "Type3".equals(((org.aspose.pdf.engine.pdfobjects.PdfDictionary) f)
                        .getNameAsString("Subtype"));
        } catch (Exception e) {
            return false;
        }
    }

    /** True when the current combined text transform (Tm x CTM) is not horizontal. */
    private static boolean isRotatedText(GraphicsState state) {
        org.aspose.pdf.Matrix tm = state.getTextMatrix();
        org.aspose.pdf.Matrix combined = tm.multiply(state.getCTM());
        // The text x-axis maps to (a, b): horizontal text keeps |b| ~ 0
        // (allowing the usual y-flip renderers apply via the CTM).
        double a = combined.getA();
        double b = combined.getB();
        return Math.abs(b) > 0.05 * (Math.abs(a) + 1e-6);
    }

    /**
     * Suppresses raster-image painting (Image XObjects and inline images) for
     * subsequent renders; vector content still paints.
     *
     * @param suppressRasterImages true to paint no raster images
     */
    public void setSuppressRasterImages(boolean suppressRasterImages) {
        this.suppressRasterImages = suppressRasterImages;
    }

    /**
     * Renders a PDF page to a BufferedImage at the specified DPI.
     *
     * @param page the PDF page to render
     * @param dpiX horizontal resolution in DPI
     * @param dpiY vertical resolution in DPI
     * @return the rendered image
     * @throws IOException if reading the content stream fails
     */
    public BufferedImage renderPage(Page page, double dpiX, double dpiY) throws IOException {
        // Acrobat print-parity: a document /OutputIntents CMYK profile (PDF/X
        // print condition, e.g. FOGRA27) governs Acrobat's DeviceCMYK print
        // conversion — install it for this render (thread-local; cleared in
        // the finally below).
        boolean outputIntentSet = false;
        boolean rgbShiftSet = false;
        if (Boolean.getBoolean("render.acrobatPrintParity")) {
            // Acrobat's print flattener kicks in when the page uses ANY
            // transparency feature; only then does its print pipeline convert
            // through the document's color intents. Opaque pages print with
            // Acrobat's default CMYK handling (the measured lattice) and
            // DeviceRGB identity — measured on two PDF/X-1a files with the
            // IDENTICAL FOGRA27 intent: 35126 (ca=0.5 present, gold =
            // intent-converted (146,178,193)) vs 9781444123166 crops (opaque,
            // gold = default lattice (153,151,151) for 0.4K, zero pixels at
            // the intent-converted values). So BOTH the /OutputIntents CMYK
            // transform and the AdobeRGB shift (see RgbPrintShift) are gated
            // on the same per-page transparency scan.
            boolean flattened = pageHasTransparency(page);
            if (flattened || "false".equals(System.getProperty("render.printParityIntentFlattenGate"))) {
                outputIntentSet = installOutputIntent(page);
            }
            if (flattened && org.aspose.pdf.engine.colorspace.RgbPrintShift.enabled()) {
                org.aspose.pdf.engine.colorspace.RgbPrintShift.setActive(true);
                rgbShiftSet = true;
            }
        }
        try {
            return renderPageInternal(page, dpiX, dpiY);
        } finally {
            if (outputIntentSet) {
                org.aspose.pdf.engine.colorspace.CmykPrintLut.clearOutputIntent();
            }
            if (rgbShiftSet) {
                org.aspose.pdf.engine.colorspace.RgbPrintShift.clear();
            }
        }
    }

    /**
     * Detects whether the page carries any transparency feature that would
     * push Acrobat's print pipeline through its transparency flattener:
     * a form-XObject transparency group, an ExtGState with alpha &lt; 1, a
     * soft mask or a non-Normal blend mode, or an image with an /SMask (or
     * JPX /SMaskInData). Walks form XObjects, tiling patterns and annotation
     * normal appearances recursively (depth-capped, cycle-safe).
     */
    private boolean pageHasTransparency(Page page) {
        try {
            PdfDictionary dict = page.getPdfDictionary();
            if (dict == null) return false;
            java.util.Set<PdfBase> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            // NOTE: a page-level /Group /S /Transparency alone is NOT a
            // trigger — PowerPoint exports stamp it on every page and
            // Acrobat still prints such pages identity when the content is
            // opaque (corpus 55919 p49: "0.6 0 0 rg" printed exactly as
            // (153,0,0); our shifted (180,0,0) failed at neigh 0.018).
            // /Resources is INHERITABLE (§7.7.3.4) — corpus 38922 keeps it on
            // the /Pages node; reading only the page dict missed the /ca<1
            // ExtGState there (Acrobat shifted p46, we didn't: our blue
            // (45,97,209) vs gold (0,97,212) = shift(ours) exactly). Walk the
            // /Parent chain by hand — Page.getResources() would lazy-CREATE
            // an empty dict on resource-less pages, mutating the document.
            PdfBase resObj = resolveRef(dict.get("Resources"));
            PdfBase node = dict;
            for (int up = 0; resObj == null && node instanceof PdfDictionary && up < 32; up++) {
                node = resolveRef(((PdfDictionary) node).get("Parent"));
                if (node instanceof PdfDictionary) {
                    resObj = resolveRef(((PdfDictionary) node).get("Resources"));
                }
            }
            if (resourcesHaveTransparency(resObj, seen, 0)) {
                return true;
            }
            PdfBase annots = resolveRef(dict.get("Annots"));
            if (annots instanceof PdfArray) {
                PdfArray arr = (PdfArray) annots;
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase a = resolveRef(arr.get(i));
                    if (!(a instanceof PdfDictionary)) continue;
                    PdfBase ap = resolveRef(((PdfDictionary) a).get("AP"));
                    if (!(ap instanceof PdfDictionary)) continue;
                    PdfBase n = resolveRef(((PdfDictionary) ap).get("N"));
                    if (n instanceof PdfDictionary && !(n instanceof PdfStream)) {
                        // Appearance state sub-dictionary — check every state.
                        for (PdfName key : ((PdfDictionary) n).keySet()) {
                            PdfBase st = resolveRef(((PdfDictionary) n).get(key));
                            if (st instanceof PdfStream
                                    && formIsTransparent((PdfStream) st, seen, 0)) {
                                return true;
                            }
                        }
                    } else if (n instanceof PdfStream
                            && formIsTransparent((PdfStream) n, seen, 0)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine(() -> "transparency scan failed: " + e);
        }
        return false;
    }

    /** Transparency scan of one resources dictionary (recursive). */
    private boolean resourcesHaveTransparency(PdfBase resObj, java.util.Set<PdfBase> seen,
                                              int depth) {
        if (!(resObj instanceof PdfDictionary) || depth > 12 || !seen.add(resObj)) {
            return false;
        }
        PdfDictionary res = (PdfDictionary) resObj;

        PdfBase egs = resolveRef(res.get("ExtGState"));
        if (egs instanceof PdfDictionary) {
            for (PdfName key : ((PdfDictionary) egs).keySet()) {
                PdfBase gsObj = resolveRef(((PdfDictionary) egs).get(key));
                if (!(gsObj instanceof PdfDictionary)) continue;
                PdfDictionary gs = (PdfDictionary) gsObj;
                if (alphaBelowOne(gs.get("CA")) || alphaBelowOne(gs.get("ca"))) return true;
                PdfBase sm = resolveRef(gs.get("SMask"));
                if (sm instanceof PdfDictionary) return true;
                PdfBase bm = resolveRef(gs.get("BM"));
                String bmName = bm instanceof PdfName ? ((PdfName) bm).getName()
                        : (bm instanceof PdfArray && ((PdfArray) bm).size() > 0
                           && resolveRef(((PdfArray) bm).get(0)) instanceof PdfName
                           ? ((PdfName) resolveRef(((PdfArray) bm).get(0))).getName() : null);
                if (bmName != null && !"Normal".equals(bmName) && !"Compatible".equals(bmName)) {
                    return true;
                }
            }
        }

        PdfBase xobjs = resolveRef(res.get("XObject"));
        if (xobjs instanceof PdfDictionary) {
            for (PdfName key : ((PdfDictionary) xobjs).keySet()) {
                PdfBase xo = resolveRef(((PdfDictionary) xobjs).get(key));
                if (!(xo instanceof PdfStream)) continue;
                PdfStream st = (PdfStream) xo;
                PdfBase sub = resolveRef(st.get("Subtype"));
                String subName = sub instanceof PdfName ? ((PdfName) sub).getName() : "";
                if ("Image".equals(subName)) {
                    if (resolveRef(st.get("SMask")) instanceof PdfStream) return true;
                    PdfBase smd = resolveRef(st.get("SMaskInData"));
                    if (smd instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger
                            && ((org.aspose.pdf.engine.pdfobjects.PdfInteger) smd).intValue() > 0) {
                        return true;
                    }
                } else if (formIsTransparent(st, seen, depth)) {
                    return true;
                }
            }
        }

        PdfBase pats = resolveRef(res.get("Pattern"));
        if (pats instanceof PdfDictionary) {
            for (PdfName key : ((PdfDictionary) pats).keySet()) {
                PdfBase pat = resolveRef(((PdfDictionary) pats).get(key));
                if (pat instanceof PdfStream) { // tiling pattern cell
                    if (resourcesHaveTransparency(
                            resolveRef(((PdfStream) pat).get("Resources")), seen, depth + 1)) {
                        return true;
                    }
                } else if (pat instanceof PdfDictionary) { // shading pattern
                    PdfBase pgs = resolveRef(((PdfDictionary) pat).get("ExtGState"));
                    if (pgs instanceof PdfDictionary
                            && (alphaBelowOne(((PdfDictionary) pgs).get("CA"))
                                || alphaBelowOne(((PdfDictionary) pgs).get("ca"))
                                || resolveRef(((PdfDictionary) pgs).get("SMask"))
                                        instanceof PdfDictionary)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Form XObject: transparency in its resources (recursively).
     *  A form-level /Group /S /Transparency alone is NOT a trigger — the
     *  99991 experiment printed an opaque form group IDENTITY, same as the
     *  page-level group; only real alpha/SMask/blend content counts. */
    private boolean formIsTransparent(PdfStream form, java.util.Set<PdfBase> seen, int depth) {
        return resourcesHaveTransparency(resolveRef(form.get("Resources")), seen, depth + 1);
    }

    /** True when the value is a number strictly below 1 (constant alpha). */
    private boolean alphaBelowOne(PdfBase v) {
        v = resolveRef(v);
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) v).intValue() < 1;
        }
        if (v instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfFloat) v).floatValue() < 0.999f;
        }
        return false;
    }

    /**
     * Installs the document's /OutputIntents DestOutputProfile (if any, CMYK
     * only) as the print-parity CMYK transform for the current thread.
     *
     * @return true when a profile was installed (caller must clear)
     */
    private boolean installOutputIntent(Page page) {
        try {
            org.aspose.pdf.engine.parser.PDFParser p = page.getParser();
            if (p == null) return false;
            PdfDictionary catalog = p.getCatalog();
            if (catalog == null) return false;
            PdfBase intents = resolveRef(catalog.get("OutputIntents"));
            if (!(intents instanceof PdfArray)) return false;
            PdfArray arr = (PdfArray) intents;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase item = resolveRef(arr.get(i));
                if (!(item instanceof PdfDictionary)) continue;
                // A U.S. Web Coated (SWOP) intent IS Acrobat's default CMYK
                // working space — the measured print lattice already encodes
                // Acrobat's own rendering of it (with Adobe-CMM black-point
                // handling), while the JDK-CMM colorimetric conversion of the
                // same profile diverges visibly: corpus 36697 (PDF/X, SWOP)
                // solid magenta printed (246,86,160) = the measured lattice,
                // but the ICC-driven lattice gave the crushed (236,0,140).
                // Keep the measured lattice for SWOP; install only genuinely
                // different print conditions (e.g. FOGRA27, corpus session-4).
                if (isSwopIntent((PdfDictionary) item)) continue;
                PdfBase prof = resolveRef(((PdfDictionary) item).get("DestOutputProfile"));
                if (prof instanceof PdfStream) {
                    org.aspose.pdf.engine.colorspace.CmykPrintLut.setOutputIntent(
                            ((PdfStream) prof).getDecodedData());
                    return true;
                }
            }
        } catch (Exception e) {
            LOG.fine(() -> "OutputIntent install failed: " + e);
        }
        return false;
    }

    /** True when the output intent names the U.S. Web Coated (SWOP) condition. */
    private static boolean isSwopIntent(PdfDictionary intent) {
        for (String key : new String[]{"OutputConditionIdentifier", "OutputCondition", "Info"}) {
            PdfBase v = resolveRef(intent.get(key));
            if (v instanceof PdfString) {
                String s = ((PdfString) v).getString();
                if (s != null) {
                    String u = s.toUpperCase(java.util.Locale.ROOT);
                    if (u.contains("SWOP") || u.contains("CGATS TR 001")
                            || u.contains("U.S. WEB COATED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The page box a render maps to the raster: the CropBox (§14.11.2) clamped
     * to the MediaBox, falling back to the MediaBox and then US Letter. Pixel
     * (0,0) of {@link #renderPage} is the top-left of this box — exposed so
     * callers cropping page regions out of a render share the exact mapping.
     *
     * @param page the page
     * @return the effective box (never null)
     */
    public static Rectangle effectiveRenderBox(Page page) {
        // Pages are displayed/printed clipped to the CROP box (§14.11.2), not
        // the media box — printer's-marks documents (corpus 40971) carry crop
        // marks and colour bars in the MediaBox margin that Acrobat never
        // shows. getCropBox() falls back to MediaBox when absent; clamp to the
        // MediaBox so a malformed CropBox cannot blow up the raster.
        Rectangle mediaBox = page.getCropBox();
        Rectangle media = page.getMediaBox();
        if (mediaBox == null) {
            mediaBox = media;
        } else if (media != null) {
            double llx = Math.max(Math.min(mediaBox.getLLX(), mediaBox.getURX()),
                                  Math.min(media.getLLX(), media.getURX()));
            double lly = Math.max(Math.min(mediaBox.getLLY(), mediaBox.getURY()),
                                  Math.min(media.getLLY(), media.getURY()));
            double urx = Math.min(Math.max(mediaBox.getLLX(), mediaBox.getURX()),
                                  Math.max(media.getLLX(), media.getURX()));
            double ury = Math.min(Math.max(mediaBox.getLLY(), mediaBox.getURY()),
                                  Math.max(media.getLLY(), media.getURY()));
            if (urx > llx && ury > lly) {
                mediaBox = new Rectangle(llx, lly, urx, ury);
            }
        }
        if (mediaBox == null) {
            mediaBox = new Rectangle(0, 0, 612, 792); // US Letter default
        }
        return mediaBox;
    }

    private BufferedImage renderPageInternal(Page page, double dpiX, double dpiY) throws IOException {
        Rectangle mediaBox = effectiveRenderBox(page);

        double pageW = Math.abs(mediaBox.getWidth());
        double pageH = Math.abs(mediaBox.getHeight());

        // For 90/270 degree rotation, swap display dimensions.
        // /Rotate may be negative or >= 360 (§7.7.3.3 only requires a multiple
        // of 90) — normalize into [0, 360): corpus Test3.pdf uses -90 (≡ 270),
        // which Acrobat rotates but an exact-match switch silently dropped.
        int rotation = ((page.getRotate() % 360) + 360) % 360;
        double displayW = (rotation == 90 || rotation == 270) ? pageH : pageW;
        double displayH = (rotation == 90 || rotation == 270) ? pageW : pageH;

        // Round (not ceil) — matches the reference renderers' raster sizing.
        // ceil made e.g. a 1232.45pt page 1712px instead of 1711px and the
        // half-pixel scale skew drifted every thin stroke vs the reference
        // (corpus 25716-2: constant ~2800px changed region on every page).
        int pixelW = Math.max(1, (int) Math.floor(displayW * dpiX / 72.0 + 0.5));
        int pixelH = Math.max(1, (int) Math.floor(displayH * dpiY / 72.0 + 0.5));
        this.canvasPixelW = pixelW;
        this.canvasPixelH = pixelH;

        BufferedImage image = new BufferedImage(pixelW, pixelH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();

        // Rendering hints. Text anti-aliasing uses GASP (greyscale, font-driven)
        // not LCD subpixel — Aspose's gold renderings quantise to ~33 grey
        // levels (multiples of 32 brightness), which matches the GASP/greyscale
        // path. LCD subpixel AA produces 256 distinct colours per text glyph
        // and pushes pHash distance above threshold even when content is
        // pixel-aligned identical.
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_GASP);
        g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        // Smooth image scaling: the Java2D default (nearest neighbour) turns
        // downscaled scans into ragged strokes — corpus 25716-2 draws whole
        // pages as 3424px-wide CCITT fax images scaled ~2:1.
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // PDF coordinate system: origin at bottom-left; Java 2D: origin at top-left
        // Transform: flip Y axis and scale from user-space (72 DPI) to pixel space
        g2d.translate(0, pixelH);
        g2d.scale(dpiX / 72.0, -dpiY / 72.0);

        // Handle page rotation
        if (rotation != 0) {
            applyRotation(g2d, rotation, pageW, pageH);
        }

        // Offset for MediaBox origin (if not at 0,0)
        if (mediaBox.getLLX() != 0 || mediaBox.getLLY() != 0) {
            g2d.translate(-mediaBox.getLLX(), -mediaBox.getLLY());
        }

        // §11.4.7: a page with a /Group /S /Transparency entry is an ISOLATED
        // group — its content composites against a fully transparent initial
        // backdrop and the group RESULT is then imposed on the white medium.
        // Painting straight onto a pre-filled white canvas breaks non-Normal
        // blend modes at page level: corpus PDFKITNET-21900 p3 paints its
        // black spread background with /BM /Screen, and Screen over opaque
        // white is always white, erasing the background Acrobat keeps. For
        // pure Normal content the two orders are identical (src-over is
        // associative), so the transparent backdrop is safe whenever the
        // group entry is present. Kill switch: render.pageGroupBackdrop.
        boolean transparentBackdrop = pageHasTransparencyGroup(page)
                && !"false".equals(System.getProperty("render.pageGroupBackdrop"));
        if (!transparentBackdrop) {
            // White background — MediaBox coordinates in the (now offset) user space
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect((int) mediaBox.getLLX(), (int) mediaBox.getLLY(),
                    (int) Math.ceil(pageW), (int) Math.ceil(pageH));
        }

        // Optional-content visibility for this page's document (§8.11.4).
        initOcgVisibility(page);

        // Process content stream. §7.8.2: a /Contents ARRAY is one logical
        // stream — state carries across segments (real-world pages rely on a
        // segment-1 cm applying to segment 2: PDFNEWNET-34130_1, PDFNET_37834).
        // The opt-in -Drender.contentStreamIsolation=true renders each segment
        // with a fresh graphics state instead (diagnostic aid for producers
        // that assume Acrobat-style per-stream q/Q repair).
        try {
            Resources resources = page.getResources();
            java.util.List<PdfStream> segments =
                    "true".equals(System.getProperty("render.contentStreamIsolation"))
                            ? pageContentSegments(page) : null;
            if (segments != null && segments.size() > 1) {
                for (PdfStream segment : segments) {
                    try {
                        OperatorCollection segOps =
                                org.aspose.pdf.engine.parser.ContentStreamParser.parseToCollection(segment);
                        processOperators(segOps, resources, g2d, null, 0);
                    } catch (Exception e) {
                        LOG.fine(() -> "Error rendering content segment: " + e.getMessage());
                    }
                }
            } else {
                OperatorCollection ops = page.getContents();
                if (ops != null) {
                    processOperators(ops, resources, g2d, null, 0);
                }
            }
        } catch (Exception e) {
            LOG.warning(() -> "Error rendering page: " + e.getMessage());
        }

        // Render annotation appearances on top of the page content
        // (ISO 32000-1 §12.5.5). For each Annot with a Normal Appearance
        // stream (/AP /N), map the appearance Form's /BBox to the
        // annotation's /Rect and render its content like an inline Form
        // XObject. Without this step pages that put their visible text in
        // FreeText annotations (e.g. PDFNEWNET_31744) come out blank.
        try {
            renderAnnotations(page, g2d);
        } catch (Exception e) {
            LOG.warning(() -> "Error rendering annotations: " + e.getMessage());
        }

        g2d.dispose();
        if (transparentBackdrop) {
            // Impose the page-group result on the white medium (§11.4.7).
            BufferedImage flat = new BufferedImage(pixelW, pixelH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D fg = flat.createGraphics();
            fg.setColor(java.awt.Color.WHITE);
            fg.fillRect(0, 0, pixelW, pixelH);
            fg.drawImage(image, 0, 0, null);
            fg.dispose();
            return flat;
        }
        return image;
    }

    /**
     * True when the page dictionary carries a /Group attribute dictionary of
     * subtype /Transparency (§11.4.7) — such a page is rendered as an
     * isolated transparency group over a transparent backdrop.
     */
    private boolean pageHasTransparencyGroup(Page page) {
        try {
            PdfDictionary dict = page.getPdfDictionary();
            if (dict == null) return false;
            PdfBase g = resolveRef(dict.get("Group"));
            if (!(g instanceof PdfDictionary)) return false;
            PdfBase s = resolveRef(((PdfDictionary) g).get("S"));
            return s instanceof PdfName && "Transparency".equals(((PdfName) s).getName());
        } catch (Exception e) {
            return false;
        }
    }

    /** Iterates page annotations and draws each one's Normal Appearance stream. */
    private void renderAnnotations(Page page, Graphics2D g2d) {
        org.aspose.pdf.engine.pdfobjects.PdfDictionary pageDict = page.getPdfDictionary();
        if (pageDict == null) return;
        org.aspose.pdf.engine.pdfobjects.PdfBase annotsVal = pageDict.get(org.aspose.pdf.engine.pdfobjects.PdfName.ANNOTS);
        annotsVal = resolveRef(annotsVal);
        if (!(annotsVal instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return;
        org.aspose.pdf.engine.pdfobjects.PdfArray annots = (org.aspose.pdf.engine.pdfobjects.PdfArray) annotsVal;
        Resources pageResources = page.getResources();
        for (int i = 0; i < annots.size(); i++) {
            org.aspose.pdf.engine.pdfobjects.PdfBase item = resolveRef(annots.get(i));
            if (!(item instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary)) continue;
            org.aspose.pdf.engine.pdfobjects.PdfDictionary annot =
                    (org.aspose.pdf.engine.pdfobjects.PdfDictionary) item;
            renderOneAnnotation(annot, pageResources, g2d);
        }
    }

    private void renderOneAnnotation(org.aspose.pdf.engine.pdfobjects.PdfDictionary annot,
                                      Resources pageResources, Graphics2D g2d) {
        // Skip hidden / invisible annotations (PDF flags bits 1, 2, 6).
        org.aspose.pdf.engine.pdfobjects.PdfBase fVal = annot.get("F");
        int flags = 0;
        if (fVal instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
            flags = ((org.aspose.pdf.engine.pdfobjects.PdfInteger) fVal).intValue();
        }
        if ((flags & 0x02) != 0 || (flags & 0x01) != 0 || (flags & 0x20) != 0) return;

        // Acrobat print-parity mode (harness-only, -Drender.acrobatPrintParity=true):
        // Acrobat's silent print ("Comments & Forms: Document") omits comment
        // markup even when the Print flag is set: rubber stamps (corpus golds)
        // and file-attachment paperclips (corpus 45780 prints as a blank page),
        // plus note/popup/sound icons. Skip them so renders compare against
        // Acrobat-printed golds. Normal (view-semantics) rendering paints them.
        if (Boolean.getBoolean("render.acrobatPrintParity")) {
            org.aspose.pdf.engine.pdfobjects.PdfBase sub = resolveRef(annot.get("Subtype"));
            if (sub instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
                switch (((org.aspose.pdf.engine.pdfobjects.PdfName) sub).getName()) {
                    case "Stamp":
                    case "FileAttachment":
                    case "Text":
                    case "Popup":
                    case "Sound":
                        return;
                    default:
                        break;
                }
            }
            // Print semantics (§12.5.3): an annotation is printed only when
            // its Print flag (bit 3) is set. Screen rendering shows such
            // annotations (e.g. submit buttons), Acrobat's print output does
            // not — skip them so renders compare against printed golds.
            if ((flags & 0x04) == 0) return;
        }

        org.aspose.pdf.engine.pdfobjects.PdfBase ap = resolveRef(annot.get("AP"));
        if (!(ap instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary)) return;
        org.aspose.pdf.engine.pdfobjects.PdfBase n =
                resolveRef(((org.aspose.pdf.engine.pdfobjects.PdfDictionary) ap).get("N"));
        // /N may be a stream (single appearance) or a dict keyed by AS state.
        if (n instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary) {
            org.aspose.pdf.engine.pdfobjects.PdfBase asName = annot.get("AS");
            if (asName instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
                n = resolveRef(((org.aspose.pdf.engine.pdfobjects.PdfDictionary) n)
                        .get(((org.aspose.pdf.engine.pdfobjects.PdfName) asName).getName()));
            }
        }
        if (!(n instanceof org.aspose.pdf.engine.pdfobjects.PdfStream)) return;
        org.aspose.pdf.engine.pdfobjects.PdfStream apStream =
                (org.aspose.pdf.engine.pdfobjects.PdfStream) n;

        org.aspose.pdf.engine.pdfobjects.PdfBase rectVal = resolveRef(annot.get("Rect"));
        if (!(rectVal instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)
                || ((org.aspose.pdf.engine.pdfobjects.PdfArray) rectVal).size() != 4) return;
        Rectangle annotRect = Rectangle.fromPdfArray((org.aspose.pdf.engine.pdfobjects.PdfArray) rectVal);
        if (annotRect == null) return;

        org.aspose.pdf.engine.pdfobjects.PdfBase bboxVal = resolveRef(apStream.get("BBox"));
        Rectangle bbox = null;
        if (bboxVal instanceof org.aspose.pdf.engine.pdfobjects.PdfArray
                && ((org.aspose.pdf.engine.pdfobjects.PdfArray) bboxVal).size() == 4) {
            bbox = Rectangle.fromPdfArray((org.aspose.pdf.engine.pdfobjects.PdfArray) bboxVal);
        }
        if (bbox == null) bbox = annotRect;

        // §12.5.5: the appearance /Matrix maps form space to the annotation's
        // coordinate space; the fit to /Rect is computed against the BBox AFTER
        // it is transformed by that matrix. Ignoring /Matrix mapped an unrotated
        // BBox onto a rotated /Rect, so a 90°-rotated field appearance (corpus
        // 43484 KozGoPr6N text fields, /Matrix [0 1 -1 0]) was fitted with an
        // extreme non-uniform scale and its text collapsed into a smear.
        Matrix apMatrix = new Matrix(1, 0, 0, 1, 0, 0);
        org.aspose.pdf.engine.pdfobjects.PdfBase mVal = resolveRef(apStream.get("Matrix"));
        if (mVal instanceof org.aspose.pdf.engine.pdfobjects.PdfArray
                && ((org.aspose.pdf.engine.pdfobjects.PdfArray) mVal).size() == 6) {
            org.aspose.pdf.engine.pdfobjects.PdfArray ma =
                    (org.aspose.pdf.engine.pdfobjects.PdfArray) mVal;
            apMatrix = new Matrix(ma.getFloat(0, 1), ma.getFloat(1, 0), ma.getFloat(2, 0),
                    ma.getFloat(3, 1), ma.getFloat(4, 0), ma.getFloat(5, 0));
        }

        // Transform the four BBox corners by /Matrix and take the enclosing box.
        double[][] corners = {
            {bbox.getLLX(), bbox.getLLY()}, {bbox.getURX(), bbox.getLLY()},
            {bbox.getURX(), bbox.getURY()}, {bbox.getLLX(), bbox.getURY()}
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] c : corners) {
            double tx0 = apMatrix.getA() * c[0] + apMatrix.getC() * c[1] + apMatrix.getE();
            double ty0 = apMatrix.getB() * c[0] + apMatrix.getD() * c[1] + apMatrix.getF();
            minX = Math.min(minX, tx0); maxX = Math.max(maxX, tx0);
            minY = Math.min(minY, ty0); maxY = Math.max(maxY, ty0);
        }
        double tbW = maxX - minX;
        double tbH = maxY - minY;
        if (tbW == 0 || tbH == 0) return;

        double sx = annotRect.getWidth() / tbW;
        double sy = annotRect.getHeight() / tbH;
        double tx = annotRect.getLLX() - minX * sx;
        double ty = annotRect.getLLY() - minY * sy;

        // The appearance stream is a Form XObject. Wrap it via XForm so we
        // get an OperatorCollection and the form's /Resources. Render in a
        // pushed graphics state with the fit matrix A THEN the form /Matrix
        // applied (content coords -> /Matrix -> A -> page).
        try {
            XForm form = new XForm(apStream, "AP", null);
            OperatorCollection formOps = form.getContents();
            if (formOps == null) return;
            Resources formRes = form.getResources();
            if (formRes == null) formRes = pageResources;

            GraphicsState state = new GraphicsState();
            state.concatMatrix(new Matrix(sx, 0, 0, sy, tx, ty));
            state.concatMatrix(apMatrix);

            Deque<GraphicsState> stack = new ArrayDeque<>();
            GraphicsState apInitial = state.clone();
            for (Operator op : formOps) {
                if (Thread.currentThread().isInterrupted()) break; // cancelled
                try {
                    state = processOperator(op, state, stack, apInitial, formRes, g2d, null, 1);
                } catch (Exception ignore) { /* tolerate per-op errors */ }
            }
        } catch (Exception e) {
            LOG.fine(() -> "Annotation appearance render failed: " + e.getMessage());
        }
    }

    private static org.aspose.pdf.engine.pdfobjects.PdfBase resolveRef(
            org.aspose.pdf.engine.pdfobjects.PdfBase b) {
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
            try { return ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) b).dereference(); }
            catch (Exception e) { return null; }
        }
        return b;
    }

    /**
     * Processes a sequence of content stream operators.
     */
    private void processOperators(OperatorCollection ops, Resources resources,
                                  Graphics2D g2d, PDFParser parser, int formDepth) {
        Deque<GraphicsState> stateStack = new ArrayDeque<>();
        GraphicsState state = new GraphicsState();
        // Snapshot for Q-underflow recovery (see the "Q" case): a restore on
        // an empty stack resets to the state the content started with.
        GraphicsState initialState = state.clone();

        for (Operator op : ops) {
            // Honour cancellation: render work is CPU-bound and never blocks
            // on I/O, so a cancelled worker (mass-testing timeout, UI abort)
            // otherwise spins for the rest of the page — observed as leaked
            // "zombie" worker threads pinning a core for 15+ minutes.
            if (Thread.currentThread().isInterrupted()) {
                LOG.fine("Render interrupted; abandoning remaining operators");
                break;
            }
            try {
                state = processOperator(op, state, stateStack, initialState,
                        resources, g2d, parser, formDepth);
            } catch (Exception e) {
                LOG.fine(() -> "Skipping operator " + op.getName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Resolves the page's {@code /Contents} into its stream segments, or
     * {@code null} when it is a single stream (no isolation needed).
     */
    private java.util.List<PdfStream> pageContentSegments(org.aspose.pdf.Page page) {
        try {
            PdfBase contents = page.getPdfDictionary().get("Contents");
            if (contents instanceof PdfObjectReference) {
                contents = ((PdfObjectReference) contents).dereference();
            }
            if (!(contents instanceof PdfArray)) {
                return null;
            }
            PdfArray arr = (PdfArray) contents;
            java.util.List<PdfStream> result = new java.util.ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                PdfBase item = arr.get(i);
                if (item instanceof PdfObjectReference) {
                    item = ((PdfObjectReference) item).dereference();
                }
                if (item instanceof PdfStream) {
                    result.add((PdfStream) item);
                }
            }
            return result;
        } catch (Exception e) {
            LOG.fine(() -> "Failed to resolve /Contents segments: " + e.getMessage());
            return null;
        }
    }

    /**
     * Dispatches a single operator. Returns the (potentially replaced) state —
     * callers must use the returned value to handle Q (restore) correctly.
     */
    private GraphicsState processOperator(Operator op, GraphicsState state,
                                 Deque<GraphicsState> stateStack, GraphicsState initialState,
                                 Resources resources,
                                 Graphics2D g2d, PDFParser parser, int formDepth)
            throws IOException {

        // Use the typed operator subclasses where available
        String name = op.getName();

        // Inside a hidden optional-content block (§8.11.3): marks are not
        // painted, but state, clipping and text-position side effects still
        // apply. Painting ops are skipped here (path ops still finish so a
        // pending W clip installs); text-show ops run below with rendering
        // mode 3 (invisible) so the text matrix advances correctly.
        if (ocSuppress > 0) {
            switch (name) {
                case "Do":
                case "sh":
                case "BI":
                case "EI":
                    return state;
                case "f":
                case "F":
                case "f*":
                case "B":
                case "B*":
                case "S":
                    finishPathOp(g2d, state);
                    return state;
                case "s":
                case "b":
                case "b*":
                    state.closePath();
                    finishPathOp(g2d, state);
                    return state;
                case "Tj":
                case "TJ":
                case "'":
                case "\"": {
                    int savedTr = state.getTextRenderingMode();
                    state.setTextRenderingMode(3);
                    try {
                        return processOperatorUnchecked(op, state, stateStack, initialState,
                                resources, g2d, parser, formDepth, name);
                    } finally {
                        state.setTextRenderingMode(savedTr);
                    }
                }
                default:
                    break; // state-changing ops execute normally
            }
        }
        // Vector-underlay mode: glyphs are not painted but the text matrix
        // must still advance (same Tr=3 trick as hidden optional content).
        // In keep-rotated mode, non-horizontal text stays painted: a fixed-layout
        // target re-emits only HORIZONTAL text as positioned frames (Word cannot
        // place diagonal text), so rotated labels must survive in the pixels.
        if (suppressText && !(suppressTextKeepRotated && isRotatedText(state))
                && !(suppressTextKeepType3 && isType3Font(state, resources))) {
            switch (name) {
                case "Tj":
                case "TJ":
                case "'":
                case "\"": {
                    int savedTr = state.getTextRenderingMode();
                    state.setTextRenderingMode(3);
                    try {
                        return processOperatorUnchecked(op, state, stateStack, initialState,
                                resources, g2d, parser, formDepth, name);
                    } finally {
                        state.setTextRenderingMode(savedTr);
                    }
                }
                default:
                    break;
            }
        }
        return processOperatorUnchecked(op, state, stateStack, initialState, resources,
                g2d, parser, formDepth, name);
    }

    private GraphicsState processOperatorUnchecked(Operator op, GraphicsState state,
                                 Deque<GraphicsState> stateStack, GraphicsState initialState,
                                 Resources resources,
                                 Graphics2D g2d, PDFParser parser, int formDepth, String name)
            throws IOException {

        switch (name) {
            // ======== Graphics State ========
            case "q":
                stateStack.push(state.clone());
                break;
            case "Q":
                if (!stateStack.isEmpty()) {
                    state = stateStack.pop();
                    // Re-apply clip from restored state
                    applyClip(g2d, state);
                } else if (initialState != null) {
                    // Q-underflow: more restores than saves. Acrobat resets to
                    // the state the content stream started with, discarding
                    // accumulated naked-state changes (an un-saved global cm),
                    // so content appended after such a stream — a producer
                    // pattern for page furniture — draws in pristine page
                    // space (PDFNEWNET-31408: the mended-in image otherwise
                    // inherits a leaked 0.05/flip CTM and renders as a
                    // thumbnail in the wrong corner).
                    state = initialState.clone();
                    applyClip(g2d, state);
                }
                break;
            case "cm":
                if (op instanceof ConcatenateMatrix) {
                    state.concatMatrix(((ConcatenateMatrix) op).getMatrix());
                }
                break;
            case "w":
                if (op instanceof SetLineWidth) {
                    state.setLineWidth(((SetLineWidth) op).getWidth());
                }
                break;
            case "J":
                if (op instanceof SetLineCap) {
                    state.setLineCap(((SetLineCap) op).getLineCap());
                }
                break;
            case "j":
                if (op instanceof SetLineJoin) {
                    state.setLineJoin(((SetLineJoin) op).getLineJoin());
                }
                break;
            case "M":
                if (op instanceof SetMiterLimit) {
                    state.setMiterLimit(((SetMiterLimit) op).getMiterLimit());
                }
                break;
            case "d":
                if (op instanceof SetDash) {
                    SetDash sd = (SetDash) op;
                    double[] da = sd.getDashArray();
                    float[] fda = null;
                    if (da != null && da.length > 0) {
                        fda = new float[da.length];
                        for (int i = 0; i < da.length; i++) fda[i] = (float) da[i];
                    }
                    state.setDash(fda, (float) sd.getDashPhase());
                }
                break;
            case "gs":
                if (op instanceof GS) {
                    applyExtGState(state, resources, ((GS) op).getDictName());
                }
                break;

            // ======== Color — Fill ========
            case "rg":
                if (op instanceof SetRGBColor) {
                    SetRGBColor rgb = (SetRGBColor) op;
                    state.setFillColorRGB(rgb.getR(), rgb.getG(), rgb.getB());
                }
                break;
            case "g":
                if (op instanceof SetGray) {
                    state.setFillColorGray(((SetGray) op).getGray());
                }
                break;
            case "k":
                if (op instanceof SetCMYKColor) {
                    SetCMYKColor cmyk = (SetCMYKColor) op;
                    state.setFillColorCMYK(cmyk.getC(), cmyk.getM(), cmyk.getY(), cmyk.getK());
                }
                break;
            case "sc":
            case "scn":
                applyAdvancedColor(op, state, false);
                break;
            case "cs":
                // Color space selection (§8.6.8) — must be tracked: an scn in
                // a Separation/DeviceN/ICC space is NOT a gray/RGB value.
                // 29077.pdf paints its body text with "/CS1 cs 1 scn"; treating
                // the lone tint as gray rendered white-on-white (invisible).
                state.setFillColorSpace(resolveColorSpaceOperand(op, resources, parser));
                break;

            // ======== Color — Stroke ========
            case "RG":
                if (op instanceof SetRGBColorStroke) {
                    SetRGBColorStroke rgb = (SetRGBColorStroke) op;
                    state.setStrokeColorRGB(rgb.getR(), rgb.getG(), rgb.getB());
                }
                break;
            case "G":
                if (op instanceof SetGrayStroke) {
                    state.setStrokeColorGray(((SetGrayStroke) op).getGray());
                }
                break;
            case "K":
                if (op instanceof SetCMYKColorStroke) {
                    SetCMYKColorStroke cmyk = (SetCMYKColorStroke) op;
                    state.setStrokeColorCMYK(cmyk.getC(), cmyk.getM(), cmyk.getY(), cmyk.getK());
                }
                break;
            case "SC":
            case "SCN":
                applyAdvancedColor(op, state, true);
                break;
            case "CS":
                state.setStrokeColorSpace(resolveColorSpaceOperand(op, resources, parser));
                break;

            // ======== Path Construction ========
            case "m":
                if (op instanceof MoveTo) {
                    state.moveTo(((MoveTo) op).getX(), ((MoveTo) op).getY());
                }
                break;
            case "l":
                if (op instanceof LineTo) {
                    state.lineTo(((LineTo) op).getX(), ((LineTo) op).getY());
                }
                break;
            case "c":
                if (op instanceof CurveTo) {
                    CurveTo ct = (CurveTo) op;
                    state.curveTo(ct.getX1(), ct.getY1(), ct.getX2(), ct.getY2(), ct.getX3(), ct.getY3());
                }
                break;
            case "v":
                if (op instanceof CurveTo1) {
                    CurveTo1 ct = (CurveTo1) op;
                    state.curveToV(ct.getX2(), ct.getY2(), ct.getX3(), ct.getY3());
                }
                break;
            case "y":
                if (op instanceof CurveTo2) {
                    CurveTo2 ct = (CurveTo2) op;
                    state.curveToY(ct.getX1(), ct.getY1(), ct.getX3(), ct.getY3());
                }
                break;
            case "re":
                if (op instanceof Re) {
                    Re re = (Re) op;
                    state.rect(re.getX(), re.getY(), re.getWidth(), re.getHeight());
                }
                break;
            case "h":
                state.closePath();
                break;

            // ======== Path Painting ========
            case "S":
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "s":
                state.closePath();
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "f":
            case "F":
                fillPath(g2d, state, Path2D.WIND_NON_ZERO, resources, new int[]{formDepth}, parser);
                finishPathOp(g2d, state);
                break;
            case "f*":
                fillPath(g2d, state, Path2D.WIND_EVEN_ODD, resources, new int[]{formDepth}, parser);
                finishPathOp(g2d, state);
                break;
            case "B":
                fillPath(g2d, state, Path2D.WIND_NON_ZERO, resources, new int[]{formDepth}, parser);
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "B*":
                fillPath(g2d, state, Path2D.WIND_EVEN_ODD, resources, new int[]{formDepth}, parser);
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "b":
                state.closePath();
                fillPath(g2d, state, Path2D.WIND_NON_ZERO, resources, new int[]{formDepth}, parser);
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "b*":
                state.closePath();
                fillPath(g2d, state, Path2D.WIND_EVEN_ODD, resources, new int[]{formDepth}, parser);
                strokePath(g2d, state);
                finishPathOp(g2d, state);
                break;
            case "n":
                // End path without painting
                finishPathOp(g2d, state);
                break;

            // ======== Clipping ========
            case "W":
                state.setPendingClip();
                break;
            case "W*":
                state.setPendingClipEvenOdd();
                break;

            // ======== Text ========
            case "BT":
                state.beginText();
                break;
            case "ET":
                break;
            case "Tf":
                if (op instanceof SelectFont) {
                    SelectFont sf = (SelectFont) op;
                    state.setFont(sf.getFontName(), sf.getSize());
                }
                break;
            case "Td":
                if (op instanceof MoveTextPosition) {
                    MoveTextPosition mtp = (MoveTextPosition) op;
                    state.moveTextPosition(mtp.getX(), mtp.getY());
                }
                break;
            case "TD": {
                // TD sets leading = -ty, then does Td
                List<PdfBase> operands = op.getOperands();
                if (operands.size() >= 2) {
                    double tx = getNumber(operands.get(0));
                    double ty = getNumber(operands.get(1));
                    state.setTextLeading(-ty);
                    state.moveTextPosition(tx, ty);
                }
                break;
            }
            case "Tm":
                if (op instanceof SetTextMatrix) {
                    state.setTextMatrix(((SetTextMatrix) op).getMatrix());
                }
                break;
            case "T*":
                state.nextLine();
                break;
            case "Tc":
                if (op instanceof SetCharacterSpacing) {
                    state.setCharSpacing(((SetCharacterSpacing) op).getCharSpace());
                }
                break;
            case "Tw":
                if (op instanceof SetWordSpacing) {
                    state.setWordSpacing(((SetWordSpacing) op).getWordSpace());
                }
                break;
            case "Tz":
                if (op instanceof SetHorizontalTextScaling) {
                    state.setHorizontalScaling(((SetHorizontalTextScaling) op).getScale());
                }
                break;
            case "TL":
                if (op instanceof SetTextLeading) {
                    state.setTextLeading(((SetTextLeading) op).getLeading());
                }
                break;
            case "Tr":
                if (op instanceof SetTextRenderingMode) {
                    state.setTextRenderingMode(((SetTextRenderingMode) op).getMode());
                }
                break;
            case "Ts":
                if (op instanceof SetTextRise) {
                    state.setTextRise(((SetTextRise) op).getRise());
                }
                break;
            case "Tj":
                if (op instanceof ShowText) {
                    PdfBase strOp = op.getOperands().isEmpty() ? null : op.getOperands().get(0);
                    byte[] raw = (strOp instanceof PdfString) ? ((PdfString) strOp).getBytes() : new byte[0];
                    textRenderer.renderText(g2d, state, raw, resources, parser);
                }
                break;
            case "TJ":
                if (op instanceof SetGlyphsPositionShowText) {
                    PdfArray arr = ((SetGlyphsPositionShowText) op).getArray();
                    textRenderer.renderTJArray(g2d, state, arr, resources, parser);
                }
                break;
            case "'":
                if (op instanceof MoveToNextLineShowText) {
                    state.nextLine();
                    PdfBase strOp = op.getOperands().isEmpty() ? null : op.getOperands().get(0);
                    byte[] raw = (strOp instanceof PdfString) ? ((PdfString) strOp).getBytes() : new byte[0];
                    textRenderer.renderText(g2d, state, raw, resources, parser);
                }
                break;
            case "\"":
                if (op instanceof SetSpacingMoveToNextLineShowText) {
                    SetSpacingMoveToNextLineShowText dq = (SetSpacingMoveToNextLineShowText) op;
                    state.setWordSpacing(dq.getWordSpacing());
                    state.setCharSpacing(dq.getCharSpacing());
                    state.nextLine();
                    List<PdfBase> operands = op.getOperands();
                    PdfBase strOp = operands.size() >= 3 ? operands.get(2) : null;
                    byte[] raw = (strOp instanceof PdfString) ? ((PdfString) strOp).getBytes() : new byte[0];
                    textRenderer.renderText(g2d, state, raw, resources, parser);
                }
                break;

            // ======== XObjects ========
            case "Do":
                if (op instanceof Do) {
                    renderXObject(g2d, state, ((Do) op).getXObjectName(), resources, parser, formDepth);
                }
                break;

            // ======== Marked Content ========
            // Only /OC blocks affect rendering: content inside a hidden
            // optional-content group is not painted (§8.11.3). State and
            // clipping operators inside the block still execute.
            case "BDC": {
                boolean hidden = isHiddenOcBlock(op, resources);
                mcStack.push(hidden);
                if (hidden) ocSuppress++;
                break;
            }
            case "BMC":
                mcStack.push(Boolean.FALSE);
                break;
            case "EMC":
                if (!mcStack.isEmpty() && mcStack.pop()) ocSuppress--;
                break;
            case "MP":
            case "DP":
                break;

            // ======== Type 3 glyph metrics (§9.6.5) ========
            // d0/d1 declare the glyph's width/bbox inside a CharProc stream;
            // the advance is taken from /Widths, so nothing to do here.
            case "d0":
            case "d1":
                break;

            // ======== Shading Fill ========
            case "sh": {
                if (op instanceof ShFill) {
                    String shadingName = ((ShFill) op).getShadingName();
                    PdfDictionary shadings = resources.getShadings();
                    if (shadings != null) {
                        PdfBase shadObj = shadings.get(shadingName);
                        if (shadObj instanceof PdfObjectReference) {
                            try { shadObj = ((PdfObjectReference) shadObj).dereference(); }
                            catch (IOException ex) { shadObj = null; }
                        }
                        if (shadObj instanceof PdfDictionary) {
                            try {
                                org.aspose.pdf.engine.pattern.Shading shading =
                                    org.aspose.pdf.engine.pattern.Shading.parse(shadObj, parser);
                                if (shading != null) {
                                    // Shading coordinates live in CURRENT user
                                    // space (§8.7.4.3) = base device transform ×
                                    // the state CTM. g2d only carries the base
                                    // transform (paths are CTM-transformed per
                                    // op), so compose the CTM in — otherwise a
                                    // shading inside a scaled form evaluates t
                                    // out of range and paints one flat color
                                    // (corpus 10734: gradient banners all dark).
                                    AffineTransform shadingToDevice =
                                        new AffineTransform(g2d.getTransform());
                                    shadingToDevice.concatenate(
                                        matrixToTransform(state.getCTM()));
                                    // §11.6.5.2: an ExtGState /SMask modulates sh
                                    // fills too — pattern tiles that set a
                                    // /Luminosity mask before "sh" (corpus
                                    // PDFJAVA-39739 trifold panels) must paint
                                    // through the mask, not opaque.
                                    if (state.getSoftMask() != null
                                            && !"false".equals(System.getProperty("render.softMaskPaint"))
                                            && canvasPixelW > 0 && canvasPixelH > 0) {
                                        BufferedImage buf = new BufferedImage(
                                                canvasPixelW, canvasPixelH,
                                                BufferedImage.TYPE_INT_ARGB);
                                        Graphics2D og = buf.createGraphics();
                                        try {
                                            og.setRenderingHints(g2d.getRenderingHints());
                                            og.setClip(new java.awt.Rectangle(
                                                    0, 0, canvasPixelW, canvasPixelH));
                                            og.setTransform(g2d.getTransform());
                                            if (g2d.getClip() != null) og.clip(g2d.getClip());
                                            org.aspose.pdf.engine.pattern.ShadingRenderer.render(
                                                og, shading, shadingToDevice, og.getClipBounds());
                                        } finally {
                                            og.dispose();
                                        }
                                        applySoftMaskToBuffer(buf, state.getSoftMask(), g2d,
                                                state, resources, parser, formDepth);
                                        AffineTransform savedT = g2d.getTransform();
                                        java.awt.Composite savedC = g2d.getComposite();
                                        try {
                                            g2d.setTransform(new AffineTransform());
                                            g2d.setComposite(BlendComposite.groupComposite(
                                                    state.getBlendMode(),
                                                    state.getNonStrokingAlpha()));
                                            g2d.drawImage(buf, 0, 0, null);
                                        } finally {
                                            g2d.setComposite(savedC);
                                            g2d.setTransform(savedT);
                                        }
                                    } else {
                                        org.aspose.pdf.engine.pattern.ShadingRenderer.render(
                                            g2d, shading, shadingToDevice, g2d.getClipBounds());
                                    }
                                }
                            } catch (IOException ex) {
                                LOG.fine(() -> "Failed to render shading: " + ex.getMessage());
                            }
                        }
                    }
                }
                break;
            }

            // ======== Inline Images ========
            case "BI":
                // The parser folds the whole BI..ID..EI object into one BI
                // operator: operands[0] = image dict, operands[1] = raw data.
                if (!suppressRasterImages) {
                    renderInlineImage(g2d, state, op, parser);
                }
                break;
            case "ID":
            case "EI":
                // Consumed by the parser into BI's operands — nothing here.
                break;

            default:
                LOG.finest(() -> "Unhandled operator: " + op.getName());
                break;
        }
        return state;
    }

    // ======== Path Painting ========

    private void fillPath(Graphics2D g2d, GraphicsState state, int windingRule) {
        fillPath(g2d, state, windingRule, null, null, null);
    }

    /**
     * @param resources    page (or form/pattern) resources — needed to resolve
     *                     a fill Pattern by name. May be null when the
     *                     caller knows there's no pattern fill in flight.
     * @param formDepthBox single-element int[] holding the current form
     *                     recursion depth so pattern content streams can
     *                     guard against infinite recursion. May be null →
     *                     defaults to depth 0.
     */
    private void fillPath(Graphics2D g2d, GraphicsState state, int windingRule,
                           Resources resources, int[] formDepthBox, PDFParser parser) {
        GeneralPath path = state.getCurrentPath();
        if (path.getBounds2D().isEmpty()) return;

        // §11.6.5.2: an ExtGState /SMask modulates EVERY painting operator,
        // not just transparency-group composites. Direct path fills (e.g. a
        // shading-pattern fill under a /Luminosity mask — corpus 49703
        // ex99-25_slide8 gray "fan") previously painted fully opaque because
        // the mask was only honoured in renderFormOffscreen. Render the fill
        // offscreen, multiply its alpha by the mask, composite once.
        if (state.getSoftMask() != null
                && !"false".equals(System.getProperty("render.softMaskPaint"))
                && canvasPixelW > 0 && canvasPixelH > 0) {
            BufferedImage buf = new BufferedImage(canvasPixelW, canvasPixelH,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D og = buf.createGraphics();
            try {
                og.setRenderingHints(g2d.getRenderingHints());
                // Full-device clip first (identity space): shading fills need
                // non-null clip bounds to know their target area.
                og.setClip(new java.awt.Rectangle(0, 0, canvasPixelW, canvasPixelH));
                og.setTransform(g2d.getTransform());
                if (g2d.getClip() != null) og.clip(g2d.getClip());
                GraphicsState fillState = state.clone();
                fillState.setSoftMask(null);
                fillState.setBlendMode("Normal");
                fillPath(og, fillState, windingRule, resources, formDepthBox, parser);
            } finally {
                og.dispose();
            }
            int depth = formDepthBox != null ? formDepthBox[0] : 0;
            applySoftMaskToBuffer(buf, state.getSoftMask(), g2d, state, resources,
                    parser, depth);
            AffineTransform savedT = g2d.getTransform();
            java.awt.Composite savedC = g2d.getComposite();
            try {
                g2d.setTransform(new AffineTransform());
                // /ca was already applied by the inner fill's composite; the
                // outer composite only carries the blend mode.
                g2d.setComposite(BlendComposite.groupComposite(state.getBlendMode(), 1f));
                g2d.drawImage(buf, 0, 0, null);
            } finally {
                g2d.setComposite(savedC);
                g2d.setTransform(savedT);
            }
            return;
        }

        AffineTransform saved = g2d.getTransform();
        Shape savedClip = g2d.getClip();
        try {
            applyCtmTransform(g2d, state);
            // Honours /ca and /BM (Multiply — corpus 30894 highlight annotations).
            g2d.setComposite(BlendComposite.fillComposite(state));
            path.setWindingRule(windingRule);

            String patternName = state.getFillPatternName();
            if (patternName != null && resources != null) {
                int depth = formDepthBox != null ? formDepthBox[0] : 0;
                // §8.7.3.1: a Pattern's /Matrix maps pattern space to the DEFAULT
                // (initial) coordinate system of the content stream in which the
                // pattern is used — NOT the CTM in effect at the fill. `saved` was
                // captured before applyCtmTransform, so it is exactly that default
                // space (page base, or the enclosing form's base for a nested fill).
                AffineTransform patternBase = saved;
                // Shading patterns (PatternType 2) paint a gradient inside the
                // path; tiling patterns (PatternType 1) tile a cell.
                if (renderShadingPatternFill(g2d, state, path, resources, patternName, parser, patternBase)) {
                    return;
                }
                if (renderTilingPatternFill(g2d, state, path, resources, patternName, depth, patternBase)) {
                    return;
                }
                // Fall through to solid colour if pattern couldn't be rendered
            }
            g2d.setColor(state.getFillColor());
            g2d.fill(path);
        } finally {
            g2d.setTransform(saved);
            g2d.setClip(savedClip);
        }
    }

    /**
     * Fills {@code path} with a shading Pattern (PatternType 2, §8.7.4.3): the
     * pattern's /Shading is painted, clipped to the path, with the pattern
     * /Matrix mapping shading space into the current coordinate system. Returns
     * {@code true} on success, {@code false} so the caller can fall back.
     * <p>Without this, shading-pattern fills dropped to a solid fill colour —
     * black for the gradient-built emoji of corpus 59149.</p>
     */
    private boolean renderShadingPatternFill(Graphics2D g2d, GraphicsState state,
                                             GeneralPath path, Resources resources,
                                             String patternName, PDFParser parser,
                                             AffineTransform patternBase) {
        if (Boolean.getBoolean("openpdf.shadingpattern.disable")) return false;
        try {
            PdfDictionary patterns = resources.getPdfDictionary() != null
                    ? (PdfDictionary) resolveRef(resources.getPdfDictionary().get("Pattern"))
                    : null;
            if (patterns == null) return false;
            PdfBase patBase = resolveRef(patterns.get(patternName));
            if (!(patBase instanceof PdfDictionary)) return false;
            PdfDictionary patDict = (PdfDictionary) patBase;
            if (intOf(patDict.get("PatternType"), 1) != 2) return false;

            PdfBase shadObj = resolveRef(patDict.get("Shading"));
            if (!(shadObj instanceof PdfDictionary)) return false;
            org.aspose.pdf.engine.pattern.Shading shading =
                    org.aspose.pdf.engine.pattern.Shading.parse(shadObj, parser);
            if (shading == null) return false;

            Matrix patMatrix = matrixFromPdfArray(resolveRef(patDict.get("Matrix")));
            if (patMatrix == null) patMatrix = new Matrix(1, 0, 0, 1, 0, 0);

            // §8.7.3.1: the pattern /Matrix maps shading space into the content
            // stream's DEFAULT coordinate system (patternBase), NOT the CTM active
            // at the fill. Using g2d.getTransform() (= base × CTM) shifted the
            // gradient by any `cm` in flight — e.g. corpus 34156 fills its page
            // background pattern under a `1 0 0 1 0 792 cm`, pushing the shading a
            // full page off-screen. The shading→device map is base × patternMatrix.
            AffineTransform shadingToDevice = new AffineTransform(patternBase);
            shadingToDevice.concatenate(matrixToTransform(patMatrix));

            Shape savedClip = g2d.getClip();
            try {
                g2d.clip(path);
                java.awt.Rectangle cb = g2d.getClipBounds();
                if (cb == null || cb.isEmpty()) return true; // nothing to paint
                org.aspose.pdf.engine.pattern.ShadingRenderer.render(g2d, shading, shadingToDevice, cb);
            } finally {
                g2d.setClip(savedClip);
            }
            return true;
        } catch (Exception e) {
            LOG.fine(() -> "Shading pattern fill failed for " + patternName + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Paints {@code path} (in user space) with the named Tiling Pattern from
     * the current resources. Returns {@code true} on success.
     *
     * <p>The pattern's content stream is rendered inside the user-space clip
     * defined by {@code path}, with the pattern's /Matrix prepended to the
     * current transform. {@code XStep}/{@code YStep} are honoured by tiling
     * the content across the path's bounding box. Tiling beyond the clip is
     * cut off naturally by Java2D's clip; for the common single-tile case
     * (XStep ≥ BBox.W and YStep ≥ BBox.H) only one iteration runs.</p>
     */
    private boolean renderTilingPatternFill(Graphics2D g2d, GraphicsState state,
                                             GeneralPath path, Resources resources,
                                             String patternName, int formDepth,
                                             AffineTransform patternBase) {
        if (Boolean.getBoolean("openpdf.pattern.disable")) return false;
        try {
            org.aspose.pdf.engine.pdfobjects.PdfDictionary patterns =
                    resources.getPdfDictionary() != null
                            ? (org.aspose.pdf.engine.pdfobjects.PdfDictionary) resolveRef(
                                    resources.getPdfDictionary().get("Pattern"))
                            : null;
            if (patterns == null) return false;
            org.aspose.pdf.engine.pdfobjects.PdfBase patBase = resolveRef(patterns.get(patternName));
            if (!(patBase instanceof org.aspose.pdf.engine.pdfobjects.PdfStream)) return false;
            org.aspose.pdf.engine.pdfobjects.PdfStream patStream =
                    (org.aspose.pdf.engine.pdfobjects.PdfStream) patBase;

            int patternType = intOf(patStream.get("PatternType"), 1);
            if (patternType != 1) return false; // Shading patterns (type 2) — TODO

            // Pattern matrix (default identity).
            Matrix patMatrix = matrixFromPdfArray(resolveRef(patStream.get("Matrix")));
            if (patMatrix == null) patMatrix = new Matrix(1, 0, 0, 1, 0, 0);
            // BBox + tiling intervals.
            Rectangle bbox = rectFromPdfArray(resolveRef(patStream.get("BBox")));
            double xStep = numberOf(resolveRef(patStream.get("XStep")), 0);
            double yStep = numberOf(resolveRef(patStream.get("YStep")), 0);
            if (bbox == null || xStep == 0 || yStep == 0) return false;

            // Pattern's own resources.
            org.aspose.pdf.engine.pdfobjects.PdfDictionary patResDict =
                    (org.aspose.pdf.engine.pdfobjects.PdfDictionary) resolveRef(patStream.get("Resources"));
            Resources patResources = patResDict != null
                    ? new Resources(patResDict, null)
                    : resources;

            // Parse pattern content stream into operators.
            byte[] patBytes = patStream.getDecodedData();
            java.util.List<Operator> patOps =
                    org.aspose.pdf.engine.parser.ContentStreamParser.parse(patBytes);
            if (patOps == null) return false;

            java.awt.geom.AffineTransform patAffine = new java.awt.geom.AffineTransform(
                    patMatrix.getA(), patMatrix.getB(),
                    patMatrix.getC(), patMatrix.getD(),
                    patMatrix.getE(), patMatrix.getF());
            // §8.7.3.1: pattern space maps to the content stream's DEFAULT space
            // (patternBase) via the pattern /Matrix, NOT the CTM at the fill.
            // patternToDevice = patternBase × patMatrix.
            java.awt.geom.AffineTransform patternToDevice = new java.awt.geom.AffineTransform(patternBase);
            patternToDevice.concatenate(patAffine);
            // Compute the fill path's bounds in PATTERN space to bound the tile
            // loop. The path is in current user space; device = currentTransform ×
            // user, and pattern = patternToDevice⁻¹ × device, so
            // userToPattern = patternToDevice⁻¹ × currentTransform.
            java.awt.geom.AffineTransform userToPattern;
            try { userToPattern = patternToDevice.createInverse(); }
            catch (java.awt.geom.NoninvertibleTransformException e) { return false; }
            userToPattern.concatenate(g2d.getTransform());
            java.awt.geom.Rectangle2D userBounds = path.getBounds2D();
            java.awt.geom.Rectangle2D patBounds = userToPattern.createTransformedShape(userBounds).getBounds2D();

            int iMin = (int) Math.floor((patBounds.getMinX() - bbox.getURX()) / xStep);
            int iMax = (int) Math.ceil((patBounds.getMaxX() - bbox.getLLX()) / xStep);
            int jMin = (int) Math.floor((patBounds.getMinY() - bbox.getURY()) / yStep);
            int jMax = (int) Math.ceil((patBounds.getMaxY() - bbox.getLLY()) / yStep);
            long tileCount = (long) (iMax - iMin + 1) * (jMax - jMin + 1);
            // Fine repeating texture patterns (e.g. PDFNEWNET_38922's 8×8 red-dot
            // page background, ~6000 tiles, or PDFNEWNET_31977's cross-hatch) ARE
            // the visual content — dropping them leaves a flat solid fill whose
            // structure diverges wildly from the gold (SSIM 0.20 for 38922).
            // Rendering them via per-tile replay is both correct and cheap
            // (~0.15 ms/tile; tile count is dpi-independent, driven by user-space
            // bounds ÷ step) and matches Aspose more closely, not less — 31977's
            // pHash distance drops from a borderline pass to 4 once tiled. We
            // keep a generous upper bound only as a runtime backstop for
            // pathological step/area ratios; the in-loop interrupt check and the
            // global RenderBudget interrupt true runaways. Override via
            // -Dopenpdf.pattern.maxtiles for diagnostics.
            if (tileCount > Long.getLong("openpdf.pattern.maxtiles", 100_000L)) {
                LOG.fine(() -> "Pattern tiling skipped for " + patternName + " (tileCount=" + tileCount + ")");
                return false;
            }
            // Clip to the fill path in the CURRENT transform (base × CTM), then
            // switch g2d to pattern space (patternToDevice) for cell replay. The
            // clip is tracked by Java2D in device space, so it survives the
            // transform change and correctly masks the tiled cells.
            g2d.clip(path);
            java.awt.geom.AffineTransform afterCtm = g2d.getTransform();
            g2d.setTransform(patternToDevice);

            for (int j = jMin; j <= jMax; j++) {
                for (int i = iMin; i <= iMax; i++) {
                    java.awt.geom.AffineTransform tile = new java.awt.geom.AffineTransform(g2d.getTransform());
                    g2d.translate(i * xStep, j * yStep);
                    // The cell content runs with a FRESH GraphicsState. Its
                    // clipPath must be seeded with the path clip — otherwise
                    // the first Q inside the cell calls applyClip(null) and
                    // BLOWS AWAY the clip, splattering the cell across the
                    // page (corpus 16222: a 123pt photo painted 20× over the
                    // article). Clip shapes pass through getClip()/setClip()
                    // in CURRENT user-space coordinates, so both the seed and
                    // the post-cell restore are taken at THIS tile transform —
                    // capturing once outside the loop would shift the clip by
                    // i·XStep/j·YStep per tile.
                    java.awt.Shape tileClip = g2d.getClip();
                    GraphicsState patState = new GraphicsState();
                    if (tileClip != null) {
                        patState.setClipPath(new java.awt.geom.GeneralPath(tileClip));
                    }
                    java.util.Deque<GraphicsState> stack = new java.util.ArrayDeque<>();
                    GraphicsState patInitial = patState.clone();
                    for (Operator po : patOps) {
                        if (Thread.currentThread().isInterrupted()) break; // cancelled
                        try {
                            patState = processOperator(po, patState, stack, patInitial,
                                    patResources, g2d, null, formDepth + 1);
                        } catch (Exception e) { /* tolerate per-op */ }
                    }
                    g2d.setClip(tileClip); // undo any clip the cell left behind
                    g2d.setTransform(tile);
                }
            }
            g2d.setTransform(afterCtm);
            return true;
        } catch (Exception e) {
            LOG.fine(() -> "Tiling pattern fill failed for " + patternName + ": " + e.getMessage());
            return false;
        }
    }

    private static Matrix matrixFromPdfArray(org.aspose.pdf.engine.pdfobjects.PdfBase b) {
        if (!(b instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return null;
        org.aspose.pdf.engine.pdfobjects.PdfArray a = (org.aspose.pdf.engine.pdfobjects.PdfArray) b;
        if (a.size() != 6) return null;
        return new Matrix(numberOf(a.get(0), 1), numberOf(a.get(1), 0),
                          numberOf(a.get(2), 0), numberOf(a.get(3), 1),
                          numberOf(a.get(4), 0), numberOf(a.get(5), 0));
    }

    private static Rectangle rectFromPdfArray(org.aspose.pdf.engine.pdfobjects.PdfBase b) {
        if (!(b instanceof org.aspose.pdf.engine.pdfobjects.PdfArray)) return null;
        org.aspose.pdf.engine.pdfobjects.PdfArray a = (org.aspose.pdf.engine.pdfobjects.PdfArray) b;
        if (a.size() != 4) return null;
        return Rectangle.fromPdfArray(a);
    }

    private static double numberOf(org.aspose.pdf.engine.pdfobjects.PdfBase b, double def) {
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger)
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) b).intValue();
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat)
            return ((org.aspose.pdf.engine.pdfobjects.PdfFloat) b).doubleValue();
        return def;
    }

    private static int intOf(org.aspose.pdf.engine.pdfobjects.PdfBase b, int def) {
        if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger)
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) b).intValue();
        return def;
    }

    private void strokePath(Graphics2D g2d, GraphicsState state) {
        GeneralPath path = state.getCurrentPath();
        // Skip only a path with no segments at all. Do NOT use
        // Rectangle2D.isEmpty() here: an axis-aligned line (the common
        // "table rule" `x y m  x2 y l  S`) has a zero-height/width bounding
        // box, isEmpty() reports true, and the stroke would be dropped.
        if (path.getPathIterator(null).isDone()) return;

        AffineTransform saved = g2d.getTransform();
        try {
            applyCtmTransform(g2d, state);
            if (state.getStrokingAlpha() < 1.0f) {
                // Under print-parity, Normal-mode alpha strokes flatten in ink
                // space like fills (see BlendComposite.groupComposite).
                g2d.setComposite(org.aspose.pdf.engine.colorspace.CmykPrintLut.inkBlendActive()
                        ? BlendComposite.groupComposite(null, state.getStrokingAlpha())
                        : AlphaComposite.getInstance(
                                AlphaComposite.SRC_OVER, state.getStrokingAlpha()));
            } else {
                g2d.setComposite(AlphaComposite.SrcOver);
            }
            g2d.setColor(state.getStrokeColor());
            g2d.setStroke(deviceClampedStroke(state.createStroke(), g2d.getTransform()));
            g2d.draw(path);
        } finally {
            g2d.setTransform(saved);
        }
    }

    /**
     * Clamps a stroke so it never rasterises thinner than one device pixel.
     * <p>
     * ISO 32000 §8.4.3.2: a line width of 0 denotes the thinnest line the
     * device can render — NOT an invisible line. Java2D does not honour that
     * convention (a 0-width stroke under the anti-aliased pipeline draws
     * nothing), and a small positive width under a down-scaling CTM (e.g.
     * 0.05 in corpus 29903.pdf) anti-aliases to invisibility. Reference
     * renderers clamp the effective device width instead; we use the same
     * 0.25-device-pixel floor as Adobe Reader and PDFBox, so a hairline
     * anti-aliases to the same ~25% coverage grey as the reference engine.
     * </p>
     *
     * @param stroke    the stroke built from the graphics state (user-space width)
     * @param transform the full current transform (CTM + device scale)
     * @return the original stroke, or a copy with the width raised to 0.25 device px
     */
    private static BasicStroke deviceClampedStroke(BasicStroke stroke, AffineTransform transform) {
        double scale = Math.sqrt(Math.abs(transform.getDeterminant()));
        if (scale <= 0 || !Double.isFinite(scale)) return stroke;
        float minUserWidth = (float) (0.25 / scale); // 0.25 device px in user units
        if (stroke.getLineWidth() >= minUserWidth) return stroke;
        return new BasicStroke(minUserWidth, stroke.getEndCap(), stroke.getLineJoin(),
                stroke.getMiterLimit(), stroke.getDashArray(), stroke.getDashPhase());
    }

    /**
     * Finishes a path operation: apply pending clip, then clear the path.
     */
    private void finishPathOp(Graphics2D g2d, GraphicsState state) {
        if (state.hasPendingClip()) {
            GeneralPath path = (GeneralPath) state.getCurrentPath().clone();
            int rule = state.isPendingClipEvenOdd()
                    ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO;
            path.setWindingRule(rule);

            // Transform path by CTM for clipping in device space
            AffineTransform ctmTransform = matrixToTransform(state.getCTM());
            Shape transformedClip = ctmTransform.createTransformedShape(path);

            if (state.getClipPath() != null) {
                Area existing = new Area(state.getClipPath());
                existing.intersect(new Area(transformedClip));
                state.setClipPath(new GeneralPath(existing));
            } else {
                state.setClipPath(new GeneralPath(transformedClip));
            }
            applyClip(g2d, state);
            state.clearPendingClip();
        }
        state.clearPath();
    }

    // ======== XObjects ========

    private void renderXObject(Graphics2D g2d, GraphicsState state, String xobjName,
                               Resources resources, PDFParser parser, int formDepth) {
        if (resources == null || xobjName == null) return;
        PdfDictionary xobjects = resources.getXObjects();
        if (xobjects == null) return;

        PdfBase val = xobjects.get(xobjName);
        if (val instanceof PdfObjectReference) {
            try {
                val = ((PdfObjectReference) val).dereference();
            } catch (IOException e) {
                LOG.fine(() -> "Failed to dereference XObject " + xobjName);
                return;
            }
        }
        if (!(val instanceof PdfStream)) return;
        PdfStream stream = (PdfStream) val;

        // §8.11.3: an XObject may carry its own /OC membership.
        if (!hiddenOcgs.isEmpty() && isOcHidden(stream.get("OC"))) {
            return;
        }

        String subtype = stream.getNameAsString("Subtype");
        if ("Image".equals(subtype)) {
            if (suppressRasterImages) {
                return;
            }
            renderImage(g2d, state, stream, xobjName, parser);
        } else if ("Form".equals(subtype)) {
            renderForm(g2d, state, stream, xobjName, resources, parser, formDepth);
        }
    }

    /**
     * Renders an inline image (BI..ID..EI, §8.9.7). The content stream parser
     * delivers it as a single BI operator whose operands are the image
     * dictionary and the raw (still encoded) data. Abbreviated keys/values
     * (Table 93/94) are expanded to their canonical names and the result is
     * wrapped in a synthetic {@link PdfStream} so the regular
     * {@link #renderImage} path (incl. stencil-mask handling for Type 3
     * bitmap glyphs) applies unchanged.
     */
    private void renderInlineImage(Graphics2D g2d, GraphicsState state,
                                   Operator op, PDFParser parser) {
        try {
            List<PdfBase> operands = op.getOperands();
            if (operands.size() < 2) return;
            PdfBase dictOp = operands.get(0);
            PdfBase dataOp = operands.get(1);
            if (!(dictOp instanceof PdfDictionary)
                    || !(dataOp instanceof org.aspose.pdf.engine.pdfobjects.PdfString)) {
                return;
            }
            PdfDictionary expanded = expandInlineImageDict((PdfDictionary) dictOp);
            byte[] raw = ((org.aspose.pdf.engine.pdfobjects.PdfString) dataOp).getBytes();
            PdfStream stream = new PdfStream(expanded, raw);
            renderImage(g2d, state, stream, "InlineImage", parser);
        } catch (Exception e) {
            LOG.fine(() -> "Failed to render inline image: " + e.getMessage());
        }
    }

    /** Abbreviated → full inline-image dictionary keys (§8.9.7, Table 93). */
    private static final java.util.Map<String, String> INLINE_KEYS = new java.util.HashMap<>();
    /** Abbreviated → full filter and colour-space names (Table 94 + §8.9.5.2). */
    private static final java.util.Map<String, String> INLINE_NAMES = new java.util.HashMap<>();
    static {
        INLINE_KEYS.put("W", "Width");
        INLINE_KEYS.put("H", "Height");
        INLINE_KEYS.put("BPC", "BitsPerComponent");
        INLINE_KEYS.put("CS", "ColorSpace");
        INLINE_KEYS.put("D", "Decode");
        INLINE_KEYS.put("DP", "DecodeParms");
        INLINE_KEYS.put("F", "Filter");
        INLINE_KEYS.put("IM", "ImageMask");
        INLINE_KEYS.put("I", "Interpolate");
        INLINE_KEYS.put("L", "Length");
        INLINE_NAMES.put("G", "DeviceGray");
        INLINE_NAMES.put("RGB", "DeviceRGB");
        INLINE_NAMES.put("CMYK", "DeviceCMYK");
        INLINE_NAMES.put("I", "Indexed");
        INLINE_NAMES.put("AHx", "ASCIIHexDecode");
        INLINE_NAMES.put("A85", "ASCII85Decode");
        INLINE_NAMES.put("LZW", "LZWDecode");
        INLINE_NAMES.put("Fl", "FlateDecode");
        INLINE_NAMES.put("RL", "RunLengthDecode");
        INLINE_NAMES.put("CCF", "CCITTFaxDecode");
        INLINE_NAMES.put("DCT", "DCTDecode");
    }

    /** Expands abbreviated inline-image keys and name values to canonical form. */
    private static PdfDictionary expandInlineImageDict(PdfDictionary src) {
        PdfDictionary out = new PdfDictionary();
        out.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Subtype"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of("Image"));
        for (org.aspose.pdf.engine.pdfobjects.PdfName keyName : src.keySet()) {
            String key = keyName.getName();
            String fullKey = INLINE_KEYS.getOrDefault(key, key);
            PdfBase val = src.get(key);
            if (("ColorSpace".equals(fullKey) || "Filter".equals(fullKey))) {
                val = expandInlineName(val);
            }
            out.set(org.aspose.pdf.engine.pdfobjects.PdfName.of(fullKey), val);
        }
        return out;
    }

    /** Expands a name or an array of names via {@link #INLINE_NAMES}. */
    private static PdfBase expandInlineName(PdfBase val) {
        if (val instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
            String n = ((org.aspose.pdf.engine.pdfobjects.PdfName) val).getName();
            String full = INLINE_NAMES.get(n);
            return full != null ? org.aspose.pdf.engine.pdfobjects.PdfName.of(full) : val;
        }
        if (val instanceof PdfArray) {
            PdfArray in = (PdfArray) val;
            PdfArray out = new PdfArray();
            for (int i = 0; i < in.size(); i++) {
                out.add(expandInlineName(in.get(i)));
            }
            return out;
        }
        return val;
    }

    private void renderImage(Graphics2D g2d, GraphicsState state,
                             PdfStream stream, String name, PDFParser parser) {
        try {
            // Optional raster cap (system property, 0/absent = unlimited):
            // a single scanned image XObject can dwarf the page raster —
            // 13000×9000 RGB is ~470 MB of INT_RGB before scaling down to the
            // page. Mass-testing harnesses set this so a handful of oversized
            // scans across worker threads cannot OOM the shared heap; normal
            // library use is unaffected by default.
            long maxPx = Long.getLong("aspose.pdf.maxImageRasterPixels", 0L);
            if (maxPx > 0) {
                long w = stream.getInt("Width", 0);
                long h = stream.getInt("Height", 0);
                if (w * h > maxPx) {
                    LOG.warning(() -> "Skipping image " + name + ": " + w + "x" + h
                            + " exceeds aspose.pdf.maxImageRasterPixels=" + maxPx);
                    return;
                }
            }
            XImage ximg = new XImage(stream, name, parser);
            BufferedImage img;
            if (ximg.isImageMask()) {
                // Stencil mask (PDF §8.9.6.4): bit 0 paints with current non-stroking
                // color, bit 1 is transparent. The bare {@link XImage#toBufferedImage}
                // preview shows the inverted bit-pattern as opaque pixels — that is the
                // wrong thing to drop on the page. Build a real ARGB mask painted with
                // the current fill colour.
                img = buildStencilMaskImage(ximg, state.getFillColor());
            } else {
                img = ximg.toBufferedImage();
            }
            if (img == null) return;

            // The image is placed into a 1×1 unit square by default.
            // The CTM transforms this square to the desired location and size.
            Matrix ctm = state.getCTM();
            AffineTransform imgTransform = new AffineTransform(
                    ctm.getA(), ctm.getB(),
                    ctm.getC(), ctm.getD(),
                    ctm.getE(), ctm.getF());

            // Scale from unit square to image pixels (image maps to 1×1 in user space)
            imgTransform.concatenate(new AffineTransform(
                    1.0 / Math.max(1, img.getWidth()), 0,
                    0, -1.0 / Math.max(1, img.getHeight()),
                    0, 1));

            AffineTransform saved = g2d.getTransform();
            java.awt.Composite savedComposite = g2d.getComposite();
            try {
                // Extreme downscales (300-dpi scan drawn onto a thumbnail-
                // sized area) make the single native drawImage transform run
                // for minutes — an uninterruptible native call observed as a
                // leaked worker after timeout. Pre-halve the raster until it
                // is within 2× of its device footprint: each halving is a
                // fast pass over ever-smaller data, total work ~4/3 of one
                // pass, and the averaging improves quality vs. point
                // sampling. Only kicks in at ≥16× area ratio.
                BufferedImage toDraw = img;
                AffineTransform deviceTf = new AffineTransform(g2d.getTransform());
                deviceTf.concatenate(imgTransform);
                double devArea = Math.abs(deviceTf.getDeterminant())
                        * (double) img.getWidth() * img.getHeight();
                long imgArea = (long) img.getWidth() * img.getHeight();
                if (devArea >= 1 && imgArea > 16L * devArea) {
                    toDraw = halveToFit(img, devArea);
                    // Map the (smaller) raster into the same unit square.
                    imgTransform.concatenate(new AffineTransform(
                            (double) img.getWidth() / toDraw.getWidth(), 0,
                            0, (double) img.getHeight() / toDraw.getHeight(),
                            0, 0));
                }
                // Honours /ca and /BM (Multiply) for image paints (§11.3.5).
                g2d.setComposite(BlendComposite.fillComposite(state));
                g2d.drawImage(toDraw, imgTransform, null);
            } finally {
                g2d.setTransform(saved);
                g2d.setComposite(savedComposite);
            }
        } catch (Exception e) {
            LOG.fine(() -> "Failed to render image " + name + ": " + e.getMessage());
        }
    }

    /**
     * Repeatedly halves an image until its area is within 4× of the target
     * device area (so the final drawImage scales by at most ~2× per axis).
     * Interruptible between passes.
     */
    private static BufferedImage halveToFit(BufferedImage img, double devArea) {
        BufferedImage cur = img;
        while ((long) cur.getWidth() * cur.getHeight() > 4L * devArea
                && cur.getWidth() > 2 && cur.getHeight() > 2
                && !Thread.currentThread().isInterrupted()) {
            int nw = Math.max(1, cur.getWidth() / 2);
            int nh = Math.max(1, cur.getHeight() / 2);
            int type = cur.getColorModel().hasAlpha()
                    ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage half = new BufferedImage(nw, nh, type);
            Graphics2D hg = half.createGraphics();
            try {
                hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                hg.drawImage(cur, 0, 0, nw, nh, null);
            } finally {
                hg.dispose();
            }
            cur = half;
        }
        return cur;
    }

    /**
     * Builds a stencil-mask BufferedImage: every "paint" sample (source bit 0)
     * gets the supplied fill colour with full opacity; every "transparent"
     * sample (source bit 1) gets alpha=0 so the page colour shows through.
     *
     * <p>Reads the raw decoded mask bytes directly to avoid the
     * preview-oriented {@link XImage#toBufferedImage} mapping.</p>
     */
    private static BufferedImage buildStencilMaskImage(XImage ximg, java.awt.Color fill) throws IOException {
        int w = ximg.getWidth();
        int h = ximg.getHeight();
        if (w <= 0 || h <= 0) return null;
        byte[] data = ximg.getDecodedData();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int rowBytes = (w + 7) / 8;
        // Honour /Decode [1 0] inversion — common for masks where the encoder
        // stored 1=paint instead of the spec default 0=paint.
        boolean invertDecode = false;
        org.aspose.pdf.engine.pdfobjects.PdfBase decode = ximg.getPdfStream().get("Decode");
        if (decode instanceof org.aspose.pdf.engine.pdfobjects.PdfArray) {
            org.aspose.pdf.engine.pdfobjects.PdfArray a = (org.aspose.pdf.engine.pdfobjects.PdfArray) decode;
            if (a.size() >= 1) {
                Object first = a.get(0);
                if (first instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
                    invertDecode = ((org.aspose.pdf.engine.pdfobjects.PdfInteger) first).intValue() == 1;
                } else if (first instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
                    invertDecode = Math.round(((org.aspose.pdf.engine.pdfobjects.PdfFloat) first).floatValue()) == 1;
                }
            }
        }
        int paintArgb = (fill == null ? 0xFF000000 : (0xFF000000 | (fill.getRGB() & 0x00FFFFFF)));
        for (int y = 0; y < h; y++) {
            int rowBase = y * rowBytes;
            for (int x = 0; x < w; x++) {
                int byteIdx = rowBase + (x >> 3);
                if (byteIdx >= data.length) break;
                int bit = (data[byteIdx] >> (7 - (x & 7))) & 1;
                if (invertDecode) bit ^= 1;
                out.setRGB(x, y, bit == 0 ? paintArgb : 0x00000000);
            }
        }
        return out;
    }

    private void renderForm(Graphics2D g2d, GraphicsState state,
                            PdfStream stream, String name, Resources parentResources,
                            PDFParser parser, int formDepth) {
        if (formDepth >= MAX_FORM_DEPTH) {
            LOG.warning(() -> "Max form XObject recursion depth reached for " + name);
            return;
        }

        // §11.6.6: a /Group /S /Transparency form composites as a UNIT — the
        // outer constant alpha applies to the group's result, while alphas and
        // blend mode are reset to defaults (1.0 / Normal) before its content
        // stream executes. Painting inner ops straight onto the canvas made
        // 30%-alpha overlay forms opaque, because forms routinely re-assert
        // their own /ExtGState with ca=1 inside (corpus 38917 aerial-map
        // overlays). Render such groups offscreen and composite once.
        float groupAlpha = state.getNonStrokingAlpha();
        boolean needsComposite = groupAlpha < 0.999f
                || state.getSoftMask() != null
                || !"Normal".equals(state.getBlendMode());
        if (needsComposite && isTransparencyGroup(stream)) {
            renderFormOffscreen(g2d, state, stream, name, parentResources, parser,
                    formDepth, groupAlpha);
            return;
        }

        try {
            XForm form = new XForm(stream, name, parser);
            OperatorCollection formOps = form.getContents();
            if (formOps == null) return;

            // Form's own resources, falling back to parent
            Resources formRes = form.getResources();
            if (formRes == null) formRes = parentResources;

            // Apply form matrix
            Matrix formMatrix = form.getMatrix();
            GraphicsState formState = state.clone();
            formState.concatMatrix(formMatrix);

            // Process form content stream. processOperator returns the
            // (potentially replaced) state — it MUST be carried forward,
            // otherwise Q never restores state inside the form and q/W/Q
            // clip blocks accumulate by intersection until everything is
            // clipped away (llPDFLib-style per-cell clipping).
            Deque<GraphicsState> formStack = new ArrayDeque<>();
            GraphicsState formInitial = formState.clone();
            for (Operator op : formOps) {
                if (Thread.currentThread().isInterrupted()) break; // cancelled
                try {
                    formState = processOperator(op, formState, formStack, formInitial,
                            formRes, g2d, parser, formDepth + 1);
                } catch (Exception e) {
                    LOG.fine(() -> "Error in form " + name + " operator " + op.getName());
                }
            }
            // The form may have left a narrower device clip behind (unbalanced
            // q/W without a closing Q) — restore the caller's clip.
            applyClip(g2d, state);
        } catch (Exception e) {
            LOG.fine(() -> "Failed to render form XObject " + name + ": " + e.getMessage());
        }
    }

    // ======== Optional content (§8.11) ========

    /**
     * Builds the hidden-OCG set for this render from the catalog's
     * /OCProperties: the default configuration /D (/BaseState, /ON, /OFF);
     * under {@code -Drender.acrobatPrintParity=true} each group's
     * /Usage /Print /PrintState overrides it (§8.11.4.4) — Acrobat's print
     * path uses the print usage, so e.g. non-printing artwork layers vanish
     * from its output even when visible on screen.
     */
    private void initOcgVisibility(Page page) {
        hiddenOcgs.clear();
        mcStack.clear();
        ocSuppress = 0;
        try {
            org.aspose.pdf.engine.parser.PDFParser p = page.getParser();
            if (p == null) return;
            PdfDictionary catalog = p.getCatalog();
            if (catalog == null) return;
            PdfBase ocPropsVal = resolveRef(catalog.get("OCProperties"));
            if (!(ocPropsVal instanceof PdfDictionary)) return;
            PdfDictionary ocProps = (PdfDictionary) ocPropsVal;

            java.util.List<PdfDictionary> allOcgs = new java.util.ArrayList<>();
            PdfBase ocgsVal = resolveRef(ocProps.get("OCGs"));
            if (ocgsVal instanceof PdfArray) {
                PdfArray arr = (PdfArray) ocgsVal;
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase g = resolveRef(arr.get(i));
                    if (g instanceof PdfDictionary) allOcgs.add((PdfDictionary) g);
                }
            }

            PdfBase dVal = resolveRef(ocProps.get("D"));
            PdfDictionary config = dVal instanceof PdfDictionary ? (PdfDictionary) dVal : null;
            if (config != null) {
                PdfBase base = resolveRef(config.get("BaseState"));
                boolean baseOff = base instanceof org.aspose.pdf.engine.pdfobjects.PdfName
                        && "OFF".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) base).getName());
                if (baseOff) hiddenOcgs.addAll(allOcgs);
                applyOcgStateList(config.get("OFF"), true);
                applyOcgStateList(config.get("ON"), false);
            }

            if (Boolean.getBoolean("render.acrobatPrintParity")) {
                for (PdfDictionary ocg : allOcgs) {
                    PdfBase usage = resolveRef(ocg.get("Usage"));
                    if (!(usage instanceof PdfDictionary)) continue;
                    PdfBase print = resolveRef(((PdfDictionary) usage).get("Print"));
                    if (!(print instanceof PdfDictionary)) continue;
                    PdfBase st = resolveRef(((PdfDictionary) print).get("PrintState"));
                    if (st instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
                        if ("OFF".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) st).getName())) {
                            hiddenOcgs.add(ocg);
                        } else {
                            hiddenOcgs.remove(ocg);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.fine(() -> "OCProperties parse failed: " + e.getMessage());
        }
    }

    /** Adds/removes every OCG in an /ON or /OFF array to the hidden set. */
    private void applyOcgStateList(PdfBase listVal, boolean hide) {
        PdfBase resolved = resolveRef(listVal);
        if (!(resolved instanceof PdfArray)) return;
        PdfArray arr = (PdfArray) resolved;
        for (int i = 0; i < arr.size(); i++) {
            PdfBase g = resolveRef(arr.get(i));
            if (!(g instanceof PdfDictionary)) continue;
            if (hide) hiddenOcgs.add((PdfDictionary) g);
            else hiddenOcgs.remove(g);
        }
    }

    /**
     * True when the membership expression of an /OC entry — an OCG or OCMD
     * dictionary — is hidden. OCMDs use the default AnyOn policy: visible
     * when any member group is on (§8.11.2.2; /VE expressions not evaluated).
     */
    private boolean isOcHidden(PdfBase ocVal) {
        PdfBase oc = resolveRef(ocVal);
        if (!(oc instanceof PdfDictionary)) return false;
        PdfDictionary dict = (PdfDictionary) oc;
        String type = dict.getNameAsString("Type");
        if ("OCMD".equals(type)) {
            PdfBase members = resolveRef(dict.get("OCGs"));
            if (members instanceof PdfDictionary) {
                return hiddenOcgs.contains(members);
            }
            if (members instanceof PdfArray) {
                PdfArray arr = (PdfArray) members;
                boolean any = false;
                for (int i = 0; i < arr.size(); i++) {
                    PdfBase g = resolveRef(arr.get(i));
                    if (g instanceof PdfDictionary) {
                        any = true;
                        if (!hiddenOcgs.contains(g)) return false; // one on → visible
                    }
                }
                return any;
            }
            return false;
        }
        return hiddenOcgs.contains(dict);
    }

    /** Resolves a BDC /OC properties operand (name into /Properties, or inline dict). */
    private boolean isHiddenOcBlock(Operator op, Resources resources) {
        if (hiddenOcgs.isEmpty() || !(op instanceof BDC)) return false;
        BDC bdc = (BDC) op;
        if (!"OC".equals(bdc.getTag())) return false;
        PdfBase props = bdc.getProperties();
        if (props instanceof org.aspose.pdf.engine.pdfobjects.PdfName && resources != null) {
            PdfDictionary propDict = resources.getProperties();
            if (propDict == null) return false;
            props = propDict.get(((org.aspose.pdf.engine.pdfobjects.PdfName) props).getName());
        }
        return isOcHidden(props);
    }

    /** True when the form XObject declares a transparency group (§11.6.6). */
    private boolean isTransparencyGroup(PdfStream stream) {
        PdfBase grp = resolveRef(stream.get("Group"));
        if (!(grp instanceof PdfDictionary)) return false;
        PdfBase s = resolveRef(((PdfDictionary) grp).get("S"));
        return s instanceof org.aspose.pdf.engine.pdfobjects.PdfName
                && "Transparency".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) s).getName());
    }

    /**
     * Renders a transparency-group form XObject into an offscreen ARGB buffer
     * and composites the result onto the canvas once, with the caller's
     * constant alpha (§11.6.6). Inside the group the alpha constants and blend
     * mode start at their defaults (1.0 / Normal); the group is treated as
     * isolated (transparent backdrop) — an approximation for non-isolated
     * groups that is exact for the dominant overlay-with-ca use case.
     */
    private void renderFormOffscreen(Graphics2D g2d, GraphicsState state,
                                     PdfStream stream, String name, Resources parentResources,
                                     PDFParser parser, int formDepth, float groupAlpha) {
        // NOTE: BufferedImage graphics report a MAX_VALUE-sized device — the
        // real canvas size is tracked by renderPage.
        if (canvasPixelW <= 0 || canvasPixelH <= 0) return;
        // Size the offscreen buffer to the group's actual device footprint
        // (current clip ∩ form /BBox ∩ canvas) instead of the whole page.
        // Overlay-heavy pages nest hundreds of small transparency-group forms;
        // a full-canvas buffer per group is O(pageArea) each and dominates
        // wall-clock on large pages (PDFNET_39298: a 4362×3622pt page at 300dpi
        // is ~274 MP, ×704 groups = a 300s timeout). Sub-rect buffers make each
        // cost proportional to the group, not the page.
        java.awt.Rectangle dev = offscreenDeviceBounds(g2d, state, stream, parser);
        if (dev.width <= 0 || dev.height <= 0) return;
        int bufW = dev.width;
        int bufH = dev.height;
        BufferedImage buf = new BufferedImage(bufW, bufH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D og = buf.createGraphics();
        try {
            og.setRenderingHints(g2d.getRenderingHints());
            // Full-buffer clip first (identity space): shading fills need
            // non-null clip bounds to know their target area.
            og.setClip(new java.awt.Rectangle(0, 0, bufW, bufH));
            // Device→buffer shift so device pixel (dev.x,dev.y) lands at (0,0).
            java.awt.geom.AffineTransform bt =
                    java.awt.geom.AffineTransform.getTranslateInstance(-dev.x, -dev.y);
            bt.concatenate(g2d.getTransform());
            og.setTransform(bt);
            if (g2d.getClip() != null) og.clip(g2d.getClip());

            GraphicsState groupState = state.clone();
            groupState.setNonStrokingAlpha(1.0f);
            groupState.setStrokingAlpha(1.0f);
            groupState.setBlendMode("Normal");
            groupState.setSoftMask(null);
            renderForm(og, groupState, stream, name, parentResources, parser, formDepth);
        } finally {
            og.dispose();
        }

        // §11.6.5.2: modulate the group's alpha by the soft mask, if one is set.
        if (state.getSoftMask() != null) {
            applySoftMaskToBuffer(buf, state.getSoftMask(), g2d, state,
                    parentResources, parser, formDepth, dev.x, dev.y);
        }

        java.awt.geom.AffineTransform savedTransform = g2d.getTransform();
        java.awt.Composite savedComposite = g2d.getComposite();
        try {
            g2d.setTransform(new java.awt.geom.AffineTransform());
            g2d.setComposite(BlendComposite.groupComposite(
                    state.getBlendMode(), Math.max(0f, Math.min(1f, groupAlpha))));
            g2d.drawImage(buf, dev.x, dev.y, null);
        } finally {
            g2d.setComposite(savedComposite);
            g2d.setTransform(savedTransform);
        }
    }

    /**
     * Device-space rectangle a transparency group can actually paint into:
     * the current clip intersected with the form's {@code /BBox} (§8.10.1
     * clips form content) and the page canvas, padded 1px for anti-aliased
     * edges. Falls back to clip∩canvas when the BBox is absent/unusable.
     */
    private java.awt.Rectangle offscreenDeviceBounds(Graphics2D g2d, GraphicsState state,
                                                     PdfStream stream, PDFParser parser) {
        java.awt.Rectangle region = new java.awt.Rectangle(0, 0, canvasPixelW, canvasPixelH);
        java.awt.Shape clip = g2d.getClip();
        if (clip != null) {
            region = region.intersection(
                    g2d.getTransform().createTransformedShape(clip).getBounds());
        }
        try {
            XForm form = new XForm(stream, "", parser);
            Rectangle bbox = form.getBBox();
            if (bbox != null) {
                java.awt.geom.AffineTransform t =
                        new java.awt.geom.AffineTransform(g2d.getTransform());
                t.concatenate(matrixToTransform(form.getMatrix().multiply(state.getCTM())));
                java.awt.geom.Rectangle2D r2 = new java.awt.geom.Rectangle2D.Double(
                        Math.min(bbox.getLLX(), bbox.getURX()),
                        Math.min(bbox.getLLY(), bbox.getURY()),
                        Math.abs(bbox.getWidth()), Math.abs(bbox.getHeight()));
                region = region.intersection(t.createTransformedShape(r2).getBounds());
            }
        } catch (Exception e) {
            LOG.fine(() -> "Offscreen bounds: BBox unusable, using clip bounds");
        }
        if (region.width > 0 && region.height > 0) {
            region.grow(1, 1);
            region = region.intersection(
                    new java.awt.Rectangle(0, 0, canvasPixelW, canvasPixelH));
        }
        return region;
    }

    /**
     * Multiplies the alpha channel of {@code buf} by the soft mask (§11.6.5):
     * the mask's /G transparency group is rendered offscreen under the same
     * device transform; /S /Luminosity converts the result's luminance to
     * alpha over a black backdrop (or /BC), /S /Alpha uses its alpha channel.
     */
    private void applySoftMaskToBuffer(BufferedImage buf, PdfDictionary mask,
                                       Graphics2D g2d, GraphicsState state,
                                       Resources parentResources, PDFParser parser,
                                       int formDepth) {
        applySoftMaskToBuffer(buf, mask, g2d, state, parentResources, parser, formDepth, 0, 0);
    }

    /**
     * Offset-aware variant: {@code buf} covers the device sub-rectangle whose
     * top-left is ({@code offX},{@code offY}), so the mask group is rendered
     * with the same device→buffer shift.
     */
    private void applySoftMaskToBuffer(BufferedImage buf, PdfDictionary mask,
                                       Graphics2D g2d, GraphicsState state,
                                       Resources parentResources, PDFParser parser,
                                       int formDepth, int offX, int offY) {
        PdfBase gVal = resolveRef(mask.get("G"));
        if (!(gVal instanceof PdfStream)) return;
        PdfBase sVal = resolveRef(mask.get("S"));
        boolean luminosity = !(sVal instanceof org.aspose.pdf.engine.pdfobjects.PdfName)
                || "Luminosity".equals(((org.aspose.pdf.engine.pdfobjects.PdfName) sVal).getName());

        BufferedImage maskBuf = new BufferedImage(buf.getWidth(), buf.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = maskBuf.createGraphics();
        try {
            mg.setRenderingHints(g2d.getRenderingHints());
            if (luminosity) {
                // Luminosity backdrop: black unless /BC gives another level.
                float bc = 0f;
                PdfBase bcVal = resolveRef(mask.get("BC"));
                if (bcVal instanceof org.aspose.pdf.engine.pdfobjects.PdfArray
                        && ((org.aspose.pdf.engine.pdfobjects.PdfArray) bcVal).size() > 0) {
                    PdfBase c0 = resolveRef(((org.aspose.pdf.engine.pdfobjects.PdfArray) bcVal).get(0));
                    if (c0 instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
                        bc = ((org.aspose.pdf.engine.pdfobjects.PdfInteger) c0).intValue();
                    } else if (c0 instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
                        bc = ((org.aspose.pdf.engine.pdfobjects.PdfFloat) c0).floatValue();
                    }
                }
                int level = (int) (Math.max(0f, Math.min(1f, bc)) * 255);
                mg.setColor(new java.awt.Color(level, level, level));
                mg.fillRect(0, 0, maskBuf.getWidth(), maskBuf.getHeight());
            }
            // Without a clip the shading renderer has no target bounds and
            // silently skips (gradient masks came out flat). Set the full
            // device area while the transform is still identity.
            mg.setClip(new java.awt.Rectangle(0, 0, maskBuf.getWidth(), maskBuf.getHeight()));
            // Same device→buffer shift as the group buffer this mask modulates.
            java.awt.geom.AffineTransform mt =
                    java.awt.geom.AffineTransform.getTranslateInstance(-offX, -offY);
            mt.concatenate(g2d.getTransform());
            mg.setTransform(mt);
            if (g2d.getClip() != null) {
                mg.clip(g2d.getClip());
            }
            GraphicsState maskState = state.clone();
            maskState.setNonStrokingAlpha(1.0f);
            maskState.setStrokingAlpha(1.0f);
            maskState.setBlendMode("Normal");
            maskState.setSoftMask(null);
            // Render the mask group in the CTM captured at gs-time
            // (§11.6.5.2) — the caller may have issued cm since.
            if (state.getSoftMaskCtm() != null) {
                maskState.setCTM(state.getSoftMaskCtm());
            }
            // The mask is now also applied mid-path (direct fill under
            // /SMask): the caller's PENDING path must not leak into the mask
            // group — corpus 49703's mask re-declares the same triangle and
            // the doubled subpath cancels itself under even-odd filling.
            maskState.clearPath();
            // A luminosity/alpha mask is an ALPHA source, not page color:
            // Acrobat derives the mask from group luminosity before any
            // print color conversion — suspend the print-parity RGB shift
            // (it darkens shadow tones and would skew the alpha ramp).
            boolean shiftWas = org.aspose.pdf.engine.colorspace.RgbPrintShift.active();
            if (shiftWas) org.aspose.pdf.engine.colorspace.RgbPrintShift.setActive(false);
            try {
                renderForm(mg, maskState, (PdfStream) gVal, "SMask", parentResources,
                        parser, formDepth);
            } finally {
                if (shiftWas) org.aspose.pdf.engine.colorspace.RgbPrintShift.setActive(true);
            }
        } catch (Exception e) {
            LOG.fine(() -> "Soft-mask render failed: " + e.getMessage());
            return;
        } finally {
            mg.dispose();
        }

        int w = buf.getWidth(), h = buf.getHeight();
        int[] row = new int[w];
        int[] mrow = new int[w];
        for (int y = 0; y < h; y++) {
            buf.getRGB(0, y, w, 1, row, 0, w);
            maskBuf.getRGB(0, y, w, 1, mrow, 0, w);
            for (int x = 0; x < w; x++) {
                int m = mrow[x];
                int factor;
                if (luminosity) {
                    // Unpainted mask pixels keep the backdrop; luminance→alpha.
                    factor = (77 * ((m >> 16) & 0xFF) + 150 * ((m >> 8) & 0xFF)
                            + 29 * (m & 0xFF)) >> 8;
                } else {
                    factor = (m >>> 24);
                }
                int a = (row[x] >>> 24) * factor / 255;
                row[x] = (a << 24) | (row[x] & 0x00FFFFFF);
            }
            buf.setRGB(0, y, w, 1, row, 0, w);
        }
    }

    /**
     * Executes a Type 3 glyph-description content stream (§9.6.5). The glyph
     * state's CTM was pre-multiplied with the font matrix by the caller, so
     * the glyph's path/image operators land at the right spot on the page.
     * Mirrors {@link #renderForm}: per-operator tolerance, state carried
     * through Q, and the caller's device clip restored afterwards.
     */
    private void executeType3GlyphStream(Graphics2D g2d, GraphicsState glyphState,
                                         OperatorCollection ops, Resources resources,
                                         PDFParser parser) {
        java.awt.Shape savedClip = g2d.getClip();
        try {
            Deque<GraphicsState> stack = new ArrayDeque<>();
            GraphicsState state = glyphState;
            GraphicsState glyphInitial = glyphState.clone();
            for (Operator op : ops) {
                try {
                    state = processOperator(op, state, stack, glyphInitial, resources, g2d, parser, 1);
                } catch (Exception e) {
                    LOG.fine(() -> "Type3 glyph operator " + op.getName()
                            + " failed: " + e.getMessage());
                }
            }
        } finally {
            g2d.setClip(savedClip);
        }
    }

    // ======== ExtGState ========

    private void applyExtGState(GraphicsState state, Resources resources, String gsName) {
        if (resources == null || gsName == null) return;
        PdfDictionary gsDict = resources.getExtGState();
        if (gsDict == null) return;

        PdfBase val = gsDict.get(gsName);
        if (val instanceof PdfObjectReference) {
            try { val = ((PdfObjectReference) val).dereference(); }
            catch (IOException e) { return; }
        }
        if (!(val instanceof PdfDictionary)) return;

        PdfDictionary gsd = (PdfDictionary) val;
        ExtGState gs = new ExtGState(gsd);
        double lw = gs.getLineWidth();
        if (lw >= 0) state.setLineWidth(lw);
        int lc = gs.getLineCap();
        if (lc >= 0) state.setLineCap(lc);
        int lj = gs.getLineJoin();
        if (lj >= 0) state.setLineJoin(lj);
        double ml = gs.getMiterLimit();
        if (ml >= 0) state.setMiterLimit(ml);

        // §8.4.5 / Table 58: a `gs` ExtGState modifies ONLY the parameters it
        // actually declares; keys that are absent must leave the current
        // graphics state untouched. Re-reading a missing key and writing back
        // its DEFAULT clobbers state set by an earlier `gs`. Corpus 45870
        // (zoning map) does exactly this: `/GSF_alpha_0000 gs` (<</ca 0>>)
        // makes the fill fully transparent so a zone's teal wash is invisible
        // and only its dashed outline paints — but the next op is a
        // stroke-only `/GSS_alpha_FFFF gs` (<</CA 1>>) with no /ca. Applying a
        // default ca=1 there reset the fill alpha to opaque, so we painted a
        // solid teal polygon over the whole map and hid every coloured region
        // beneath it. Guard each parameter on its key's presence.
        if (gsd.get("CA") != null) state.setStrokingAlpha((float) gs.getStrokingAlpha());
        if (gsd.get("ca") != null) state.setNonStrokingAlpha((float) gs.getNonStrokingAlpha());
        if (gsd.get("BM") != null) state.setBlendMode(gs.getBlendMode());

        // /SMask (§11.6.5.1): a dictionary installs a soft mask, /None clears it.
        PdfBase sm = resolveRef(((PdfDictionary) val).get("SMask"));
        if (sm instanceof PdfDictionary) {
            state.setSoftMask((PdfDictionary) sm);
            // §11.6.5.2: the mask group's coordinate space is fixed NOW — a
            // cm between gs and the masked paint op must not move the mask
            // (corpus 34703 panel gloss: "1 0 0 -1" flip after gs pushed the
            // mask off-canvas, BC=1 made the gloss fully opaque white).
            state.setSoftMaskCtm(state.getCTM());
        } else if (sm != null) {
            state.setSoftMask(null);
        }
    }

    // ======== Advanced color ========

    /**
     * Resolves the cs/CS operand (a color-space name — either a device space
     * or a key into the resources /ColorSpace dictionary) to a ColorSpaceBase.
     * Returns null on failure so sc/scn falls back to by-count mapping.
     */
    private org.aspose.pdf.engine.colorspace.ColorSpaceBase resolveColorSpaceOperand(
            Operator op, Resources resources, PDFParser parser) {
        List<PdfBase> operands = op.getOperands();
        if (operands.isEmpty()) return null;
        try {
            return org.aspose.pdf.engine.colorspace.ColorSpaceBase.resolve(
                    operands.get(0), resources, parser);
        } catch (Exception e) {
            LOG.fine(() -> "Failed to resolve color space operand: " + e.getMessage());
            return null;
        }
    }

    private void applyAdvancedColor(Operator op, GraphicsState state, boolean stroke) {
        List<PdfBase> operands = op.getOperands();
        if (operands.isEmpty()) return;

        // Pattern colorspace: scn/SCN ends with the pattern resource name
        // (e.g. `/CS0 cs /P0 scn`). Stash the name so the next f/B/etc. can
        // look up the Tiling/Shading Pattern and paint with it.
        PdfBase last = operands.get(operands.size() - 1);
        if (last instanceof org.aspose.pdf.engine.pdfobjects.PdfName) {
            String name = ((org.aspose.pdf.engine.pdfobjects.PdfName) last).getName();
            if (stroke) state.setStrokePatternName(name);
            else state.setFillPatternName(name);
            return;
        }
        if (stroke) state.setStrokePatternName(null);
        else state.setFillPatternName(null);

        // Try to extract numeric components
        int numComponents = 0;
        for (PdfBase b : operands) {
            if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger
                    || b instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
                numComponents++;
            }
        }

        // The components belong to the color space selected by cs/CS — a
        // single Separation tint or N DeviceN tints must run through the
        // tint transform, not be misread as gray/RGB by component count.
        org.aspose.pdf.engine.colorspace.ColorSpaceBase activeCs =
                stroke ? state.getStrokeColorSpace() : state.getFillColorSpace();
        if (activeCs != null && numComponents > 0
                && numComponents == activeCs.getNumberOfComponents()) {
            double[] comps = new double[numComponents];
            int ci = 0;
            for (PdfBase b : operands) {
                if (b instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger
                        || b instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
                    comps[ci++] = getNumber(b);
                }
            }
            try {
                java.awt.Color color = new java.awt.Color(activeCs.toRGBInt(comps), false);
                if (stroke) state.setStrokeColor(color);
                else state.setFillColor(color);
                return;
            } catch (Exception e) {
                LOG.fine(() -> "Color space conversion failed, falling back: " + e.getMessage());
            }
        }

        if (numComponents == 3) {
            double r = getNumber(operands.get(0));
            double g = getNumber(operands.get(1));
            double b = getNumber(operands.get(2));
            if (stroke) state.setStrokeColorRGB(r, g, b);
            else state.setFillColorRGB(r, g, b);
        } else if (numComponents == 4) {
            double c = getNumber(operands.get(0));
            double m = getNumber(operands.get(1));
            double y = getNumber(operands.get(2));
            double k = getNumber(operands.get(3));
            if (stroke) state.setStrokeColorCMYK(c, m, y, k);
            else state.setFillColorCMYK(c, m, y, k);
        } else if (numComponents == 1) {
            double gray = getNumber(operands.get(0));
            if (stroke) state.setStrokeColorGray(gray);
            else state.setFillColorGray(gray);
        }
    }

    // ======== Helpers ========

    private void applyCtmTransform(Graphics2D g2d, GraphicsState state) {
        Matrix ctm = state.getCTM();
        g2d.transform(matrixToTransform(ctm));
    }

    private AffineTransform matrixToTransform(Matrix m) {
        return new AffineTransform(m.getA(), m.getB(), m.getC(), m.getD(), m.getE(), m.getF());
    }

    private void applyClip(Graphics2D g2d, GraphicsState state) {
        GeneralPath clip = state.getClipPath();
        if (clip != null) {
            g2d.setClip(clip);
        } else {
            g2d.setClip(null);
        }
    }

    private void applyRotation(Graphics2D g2d, int degrees, double pageW, double pageH) {
        // /Rotate is the number of degrees the page is rotated CLOCKWISE for
        // display (ISO 32000-1 §7.7.3.3). Our enclosing transform already did
        // translate(0, pixelH); scale(s, -s); i.e. user space has Y pointing up
        // and visible region is [0, displayW] x [0, displayH] in user-space units
        // (where displayW/H were chosen to fit the rotated page).
        //
        // We map PDF user coords (px, py) ∈ [0, pageW] x [0, pageH] into that
        // visible region so the page appears rotated CW by /Rotate degrees.
        //
        // 90°  CW: (px,py) -> (py,            pageW - px)  rotate(-90°), translate(0, pageW)
        // 180°    : (px,py) -> (pageW - px,   pageH - py)  rotate(180°), translate(pageW, pageH)
        // 270° CW: (px,py) -> (pageH - py,    px       )  rotate(+90°), translate(pageH, 0)
        //
        // In Java2D, `translate(tx, ty); rotate(theta)` composes as M = T * R,
        // so points are rotated first then translated. Java2D's rotate(+theta)
        // is mathematically CCW (matrix [cos -sin; sin cos]); after our Y-flip
        // it appears as CW on screen, but for the matrix math here we work in
        // post-flip user space where Y is up and `rotate(+theta)` is CCW.
        switch (degrees) {
            case 90:
                g2d.translate(0, pageW);
                g2d.rotate(Math.toRadians(-90));
                break;
            case 180:
                g2d.translate(pageW, pageH);
                g2d.rotate(Math.toRadians(180));
                break;
            case 270:
                g2d.translate(pageH, 0);
                g2d.rotate(Math.toRadians(90));
                break;
            default:
                break;
        }
    }

    private static double getNumber(PdfBase val) {
        if (val instanceof org.aspose.pdf.engine.pdfobjects.PdfInteger) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfInteger) val).intValue();
        }
        if (val instanceof org.aspose.pdf.engine.pdfobjects.PdfFloat) {
            return ((org.aspose.pdf.engine.pdfobjects.PdfFloat) val).doubleValue();
        }
        return 0;
    }
}
