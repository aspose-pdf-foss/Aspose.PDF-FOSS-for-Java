package org.aspose.pdf.facades;

import org.aspose.pdf.Document;
import org.aspose.pdf.ImageStamp;
import org.aspose.pdf.Page;
import org.aspose.pdf.Rectangle;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Legacy "mend" facade for adding raster images and {@link FormattedText}
 * annotations to existing PDFs without rebuilding the page from scratch.
 * Mirrors {@code Aspose.Pdf.Facades.PdfFileMend}.
 *
 * <p>Typical usage:</p>
 * <pre>{@code
 *   PdfFileMend mend = new PdfFileMend();
 *   mend.setInputFile("in.pdf");
 *   mend.addImage("logo.jpg", 1, 100, 100, 200, 200);
 *   mend.save("out.pdf");
 * }</pre>
 *
 * <p>For full {@link FormattedText} placement (legacy {@code AddText} API)
 * see {@link #addText(FormattedText, int, double, double)} — currently a
 * minimal stub that records intent without rendering glyphs (the C# fixture
 * PDFNEWNET-29975 needs the rich physical-segment layout pipeline that is
 * out of scope for FOSS).</p>
 */
public class PdfFileMend implements Closeable {

    private static final Logger LOG = Logger.getLogger(PdfFileMend.class.getName());

    private Document document;
    private boolean ownsDocument;

    /** Empty {@code PdfFileMend}. Use {@link #setInputFile(String)} or {@link #bindPdf(Document)}. */
    public PdfFileMend() {
    }

    /** Bound to {@code inputFile}. */
    public PdfFileMend(String inputFile) {
        setInputFile(inputFile);
    }

    /**
     * Bound to {@code inputFile} and primed to write the result to {@code outputFile}
     * when {@link #save()} is called. Mirrors the C# {@code PdfFileMend(string, string)}
     * constructor.
     */
    public PdfFileMend(String inputFile, String outputFile) {
        setInputFile(inputFile);
        this.pendingOutputFile = outputFile;
    }

    /** Bound to streams; the input is parsed eagerly, the output is wired for {@link #close()}. */
    public PdfFileMend(InputStream inputStream, OutputStream outputStream) {
        try {
            this.document = new Document(inputStream);
            this.ownsDocument = true;
            this.pendingOutputStream = outputStream;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to load PdfFileMend input stream", e);
        }
    }

    /** Bound to an already-opened document. */
    public PdfFileMend(Document document) {
        this.document = document;
        this.ownsDocument = false;
    }

    /** Output stream remembered when constructed via {@link #PdfFileMend(InputStream, OutputStream)}. */
    private OutputStream pendingOutputStream;

    /** Output file remembered when constructed via {@link #PdfFileMend(String, String)}. */
    private String pendingOutputFile;

    /**
     * Writes the bound document to the file supplied via the
     * {@link #PdfFileMend(String, String)} constructor.
     *
     * @return {@code true} on success
     * @throws IllegalStateException if no output path was supplied
     */
    public boolean save() {
        if (pendingOutputFile == null) {
            throw new IllegalStateException(
                    "save() requires an output path; use PdfFileMend(in, out) or save(path)");
        }
        return save(pendingOutputFile);
    }

    /** Returns the bound document, or {@code null} when none is set. */
    public Document getDocument() {
        return document;
    }

    /** Mirrors C# {@code InputFile} setter: opens {@code inputFile} into a new {@link Document}. */
    public void setInputFile(String inputFile) {
        bindPdf(inputFile);
    }

    /** Loads a fresh {@link Document} from {@code inputFile}. */
    public boolean bindPdf(String inputFile) {
        try {
            this.document = new Document(inputFile);
            this.ownsDocument = true;
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from file: " + inputFile, e);
            return false;
        }
    }

    /** Loads a fresh {@link Document} from {@code inputStream}. */
    public boolean bindPdf(InputStream inputStream) {
        try {
            this.document = new Document(inputStream);
            this.ownsDocument = true;
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to bind PDF from stream", e);
            return false;
        }
    }

    /** Binds an already-loaded document. */
    public boolean bindPdf(Document document) {
        this.document = document;
        this.ownsDocument = false;
        return document != null;
    }

    /**
     * Adds the image at {@code imageFile} to {@code pageNumber} positioned at
     * the rectangle {@code [llx,lly] – [urx,ury]}.
     *
     * @return {@code true} on success
     */
    public boolean addImage(String imageFile, int pageNumber,
                            double llx, double lly, double urx, double ury) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(imageFile));
            return addImageBytes(bytes, pageNumber, llx, lly, urx, ury);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "addImage failed: " + imageFile, e);
            return false;
        }
    }

    /**
     * Adds a JPEG image to the page directly, building the /XObject entry in
     * page resources and emitting the {@code q cm /imageRes Do Q} operator
     * stack. Bypasses {@code Page.addStamp(ImageStamp)} which only emits the
     * Do operator and does NOT register the image XObject — the
     * regression test {@link org.aspose.pdf.facades.PdfExtractor#extractImage}
     * walks {@code Resources/XObject} so the entry must be present for
     * downstream extractor calls to find it.
     */
    private boolean addImageBytes(byte[] jpegBytes, int pageNumber,
                                   double llx, double lly, double urx, double ury) {
        if (document == null || jpegBytes == null || jpegBytes.length == 0) {
            LOG.warning("addImageBytes requires a bound document and non-empty bytes");
            return false;
        }
        if (jpegBytes.length < 4 || (jpegBytes[0] & 0xFF) != 0xFF || (jpegBytes[1] & 0xFF) != 0xD8) {
            LOG.warning("addImageBytes only supports JPEG/JFIF input (SOI 0xFFD8 missing)");
            return false;
        }
        try {
            org.aspose.pdf.PageCollection pages = document.getPages();
            if (pageNumber < 1 || pageNumber > pages.getCount()) return false;
            Page page = pages.get(pageNumber);

            int[] dims = readJpegDimensions(jpegBytes);
            if (dims == null) {
                LOG.warning("addImageBytes: could not read JPEG dimensions");
                return false;
            }
            int width = dims[0];
            int height = dims[1];
            int components = dims[2];

            // Build the image XObject stream.
            org.aspose.pdf.engine.pdfobjects.PdfStream imgStream =
                    new org.aspose.pdf.engine.pdfobjects.PdfStream();
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Type"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("XObject"));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Subtype"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("Image"));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Width"),
                    org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(width));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Height"),
                    org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(height));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("BitsPerComponent"),
                    org.aspose.pdf.engine.pdfobjects.PdfInteger.valueOf(8));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("ColorSpace"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of(
                            components == 4 ? "DeviceCMYK"
                                    : components == 1 ? "DeviceGray" : "DeviceRGB"));
            imgStream.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Filter"),
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("DCTDecode"));
            imgStream.setEncodedData(jpegBytes);

            // Register in page Resources/XObject under a fresh name.
            org.aspose.pdf.Resources resources = page.ensureResources();
            org.aspose.pdf.engine.pdfobjects.PdfDictionary xobjects = resources.getXObjects();
            if (xobjects == null) {
                xobjects = new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
                resources.getPdfDictionary().set(
                        org.aspose.pdf.engine.pdfobjects.PdfName.of("XObject"), xobjects);
            }
            String resName;
            int idx = 1;
            do { resName = "Im" + idx++; } while (xobjects.containsKey(resName));
            org.aspose.pdf.engine.pdfobjects.PdfObjectReference imgRef =
                    document.registerImportedObject(imgStream);
            xobjects.set(org.aspose.pdf.engine.pdfobjects.PdfName.of(resName), imgRef);

            // Preserve aspect ratio of the source image inside the bounding
            // rectangle (matches Aspose.PDF.AddImage semantics — gold templates
            // show the logo centered with surrounding page content visible,
            // not stretched edge-to-edge).
            double rectW = urx - llx;
            double rectH = ury - lly;
            double imgAspect = (double) width / (double) height;
            double rectAspect = rectW / rectH;
            double drawW, drawH;
            if (imgAspect > rectAspect) {
                // Image is wider than rect (per unit height): fit to width.
                drawW = rectW;
                drawH = rectW / imgAspect;
            } else {
                // Image is taller than rect: fit to height.
                drawH = rectH;
                drawW = rectH * imgAspect;
            }
            double drawX = llx + (rectW - drawW) / 2.0;
            double drawY = lly + (rectH - drawH) / 2.0;
            // The existing content may leak a naked (un-saved) cm past its end
            // (§7.8.2 treats /Contents segments as one stream, so trailing
            // state carries into appended ops — PDFNEWNET-31408: a 0.05/flip
            // CTM shrank the added image into the wrong corner). Compensate by
            // prepending the inverse of the trailing CTM so the image draws in
            // pristine page space, as Aspose does.
            String prefix = "";
            org.aspose.pdf.Matrix trailing = computeTrailingCtm(page);
            if (trailing != null && !isIdentity(trailing)) {
                try {
                    org.aspose.pdf.Matrix inv = trailing.reverse();
                    prefix = fmt(inv.getA()) + " " + fmt(inv.getB()) + " " + fmt(inv.getC()) + " "
                            + fmt(inv.getD()) + " " + fmt(inv.getE()) + " " + fmt(inv.getF()) + " cm ";
                } catch (IllegalStateException singular) {
                    LOG.fine("Trailing CTM is singular; skipping compensation");
                }
            }
            // Emit q [inv cm] drawW 0 0 drawH drawX drawY cm /resName Do Q
            String op = "\nq " + prefix + fmt(drawW) + " 0 0 " + fmt(drawH) + " " + fmt(drawX) + " " + fmt(drawY)
                    + " cm /" + resName + " Do Q\n";
            page.appendToContentStream(op.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "addImageBytes failed", e);
            return false;
        }
    }

    /**
     * Returns {@code [width, height, components]} parsed from a JPEG SOFn
     * marker, or {@code null} when no SOFn is found.
     */
    private static int[] readJpegDimensions(byte[] data) {
        int i = 2; // skip SOI
        while (i + 3 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) return null;
            int marker = data[i + 1] & 0xFF;
            // Stand-alone (no length): 0xD0..0xD9, 0x01
            if (marker >= 0xD0 && marker <= 0xD9) { i += 2; continue; }
            if (marker == 0x01) { i += 2; continue; }
            int segLen = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            // SOF0..SOF15 (excluding DHT 0xC4, JPG 0xC8, DAC 0xCC)
            if (marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                int precision = data[i + 4] & 0xFF;
                int h = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                int w = ((data[i + 7] & 0xFF) << 8) | (data[i + 8] & 0xFF);
                int comps = data[i + 9] & 0xFF;
                if (precision != 0 && w > 0 && h > 0) {
                    return new int[]{w, h, comps};
                }
            }
            i += 2 + segLen;
        }
        return null;
    }

    /**
     * Simulates cm/q/Q over the page's content to obtain the CTM that will be
     * in effect after its last operator (mirrors the renderer, including the
     * Q-underflow reset to the initial state). Returns null when unknown.
     */
    private static org.aspose.pdf.Matrix computeTrailingCtm(Page page) {
        try {
            org.aspose.pdf.OperatorCollection ops = page.getContents();
            if (ops == null) {
                return null;
            }
            java.util.Deque<org.aspose.pdf.Matrix> stack = new java.util.ArrayDeque<>();
            org.aspose.pdf.Matrix ctm = org.aspose.pdf.Matrix.IDENTITY;
            for (int i = 0; i < ops.size(); i++) {
                org.aspose.pdf.Operator op = ops.getAt(i);
                String n = op.getName();
                if ("q".equals(n)) {
                    stack.push(ctm);
                } else if ("Q".equals(n)) {
                    ctm = stack.isEmpty() ? org.aspose.pdf.Matrix.IDENTITY : stack.pop();
                } else if (op instanceof org.aspose.pdf.operators.ConcatenateMatrix) {
                    ctm = ((org.aspose.pdf.operators.ConcatenateMatrix) op).getMatrix().multiply(ctm);
                }
            }
            return ctm;
        } catch (Exception e) {
            LOG.fine(() -> "Could not compute trailing CTM: " + e.getMessage());
            return null;
        }
    }

    private static boolean isIdentity(org.aspose.pdf.Matrix m) {
        return Math.abs(m.getA() - 1) < 1e-9 && Math.abs(m.getB()) < 1e-9
                && Math.abs(m.getC()) < 1e-9 && Math.abs(m.getD() - 1) < 1e-9
                && Math.abs(m.getE()) < 1e-9 && Math.abs(m.getF()) < 1e-9;
    }

    private static String fmt(double v) {
        if (v == (long) v) return Long.toString((long) v);
        String s = String.format(java.util.Locale.ROOT, "%.4f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }

    /**
     * Adds the image content read from {@code imageStream} to {@code pageNumber}.
     * Caller retains ownership of the stream.
     */
    public boolean addImage(InputStream imageStream, int pageNumber,
                            double llx, double lly, double urx, double ury) {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = imageStream.read(buf)) >= 0) baos.write(buf, 0, n);
            return addImageBytes(baos.toByteArray(), pageNumber, llx, lly, urx, ury);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "addImage(stream) failed", e);
            return false;
        }
    }

    /**
     * Adds {@code text} to {@code pageNumber} starting at {@code (x, y)} (PDF
     * user-space coordinates, origin at bottom-left). Each line is shown via
     * a {@code BT … ET} block sandwiched between {@code q … Q} so existing
     * page graphics state is preserved. Importantly, the {@code q}/{@code Q}
     * are emitted OUTSIDE the {@code BT}/{@code ET} block — placing
     * save/restore inside a text object is illegal per ISO 32000-1 §8.4.4.2
     * and §9.4.1, and this is exactly what regression PDFNEWNET-32565 checks.
     *
     * <p>Background colour, when set on {@link FormattedText}, is painted as
     * a filled rectangle covering the rendered text bounding box, before the
     * text run.</p>
     *
     * <p>Multiple lines (via {@link FormattedText#addNewLineText(String)})
     * are rendered top-to-bottom using {@code T*} between {@code Tj} calls.</p>
     */
    public boolean addText(FormattedText text, int pageNumber, double x, double y) {
        if (document == null || text == null) {
            LOG.warning("PdfFileMend.addText: requires a bound document and non-null text");
            return false;
        }
        try {
            org.aspose.pdf.PageCollection pages = document.getPages();
            if (pageNumber < 1 || pageNumber > pages.getCount()) {
                LOG.warning("PdfFileMend.addText: page " + pageNumber + " out of range");
                return false;
            }
            Page page = pages.get(pageNumber);

            // 1. Pick a font name. Default to Helvetica when none was supplied
            //    (matches FormattedText's fontStyleToName fallback).
            String pdfFontName = text.getFontName();
            if (pdfFontName == null || pdfFontName.isEmpty()) pdfFontName = "Helvetica";
            float fontSize = text.getFontSize();
            if (fontSize <= 0f) fontSize = 12f;

            String resName = ensureType1Font(page, pdfFontName);

            // 2. Build the content stream fragment.
            StringBuilder sb = new StringBuilder(256);
            // q/Q form a graphics-state checkpoint OUTSIDE BT/ET — the check
            // that PDFNEWNET-32565 enforces.
            sb.append("\nq\n");

            java.util.List<String> lineList = textLines(text);
            float lineSpacing = text.getLineSpacing();
            if (lineSpacing <= 0f) lineSpacing = fontSize * 1.2f;

            // Background rectangle (optional, painted before the text).
            if (text.getBackColor() != null && !lineList.isEmpty()) {
                double maxLen = 0;
                for (String ln : lineList) maxLen = Math.max(maxLen, ln.length());
                // Crude width estimate: 0.55em per character — good enough for
                // a regression smoke test that doesn't measure visual fidelity.
                double bgW = maxLen * fontSize * 0.55;
                double bgH = lineSpacing * lineList.size();
                appendRgb(sb, text.getBackColor(), false);
                sb.append(fmt(x)).append(' ').append(fmt(y - (bgH - fontSize)))
                        .append(' ').append(fmt(bgW)).append(' ').append(fmt(bgH))
                        .append(" re f\n");
            }

            // Foreground colour (default black).
            appendRgb(sb, text.getTextColor() != null
                    ? text.getTextColor() : org.aspose.pdf.Color.getBlack(), true);

            sb.append("BT\n");
            sb.append('/').append(resName).append(' ').append(fmt(fontSize)).append(" Tf\n");
            sb.append(fmt(x)).append(' ').append(fmt(y)).append(" Td\n");
            for (int i = 0; i < lineList.size(); i++) {
                if (i > 0) {
                    // Move down by lineSpacing for subsequent lines.
                    sb.append("0 -").append(fmt(lineSpacing)).append(" Td\n");
                }
                sb.append(escapeLiteralString(lineList.get(i))).append(" Tj\n");
            }
            sb.append("ET\n");
            sb.append("Q\n");

            page.appendToContentStream(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "PdfFileMend.addText failed", e);
            return false;
        }
    }

    /** Backwards-compatible float overload (matches the legacy C# signature). */
    public boolean addText(FormattedText text, int pageNumber, float x, float y) {
        return addText(text, pageNumber, (double) x, (double) y);
    }

    /** Splits a {@link FormattedText#getText()} payload back into per-line strings. */
    private static java.util.List<String> textLines(FormattedText text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : text.getText().split("\n", -1)) {
            out.add(line);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }

    /**
     * Ensures the page resources carry a Type1 font reference for {@code pdfFontName}.
     * Returns the resource name (e.g. {@code F1}) the caller should use in
     * {@code /Fxx size Tf} operators. Re-uses an existing entry whose
     * {@code /BaseFont} matches, otherwise allocates a fresh {@code Fn}.
     */
    private static String ensureType1Font(Page page, String pdfFontName) {
        org.aspose.pdf.Resources resources = page.ensureResources();
        org.aspose.pdf.engine.pdfobjects.PdfDictionary fonts = resources.getFonts();
        if (fonts == null) {
            fonts = new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
            resources.getPdfDictionary().set(
                    org.aspose.pdf.engine.pdfobjects.PdfName.of("Font"), fonts);
        }
        // Look for an already-registered entry with matching /BaseFont.
        for (org.aspose.pdf.engine.pdfobjects.PdfName key : fonts.keySet()) {
            org.aspose.pdf.engine.pdfobjects.PdfBase val = fonts.get(key.getName());
            org.aspose.pdf.engine.pdfobjects.PdfBase resolved = val;
            if (resolved instanceof org.aspose.pdf.engine.pdfobjects.PdfObjectReference) {
                try { resolved = ((org.aspose.pdf.engine.pdfobjects.PdfObjectReference) resolved).dereference(); }
                catch (IOException e) { continue; }
            }
            if (resolved instanceof org.aspose.pdf.engine.pdfobjects.PdfDictionary) {
                String base = ((org.aspose.pdf.engine.pdfobjects.PdfDictionary) resolved).getNameAsString("BaseFont");
                if (pdfFontName.equals(base)) return key.getName();
            }
        }
        // Allocate a fresh Fn key.
        String resName;
        int idx = 1;
        do { resName = "F" + idx++; } while (fonts.containsKey(resName));
        org.aspose.pdf.engine.pdfobjects.PdfDictionary fontDict =
                new org.aspose.pdf.engine.pdfobjects.PdfDictionary();
        fontDict.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Type"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of("Font"));
        fontDict.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Subtype"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of("Type1"));
        fontDict.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("BaseFont"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of(pdfFontName));
        fontDict.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("Encoding"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of("WinAnsiEncoding"));
        fonts.set(org.aspose.pdf.engine.pdfobjects.PdfName.of(resName), fontDict);
        return resName;
    }

    /** Appends an {@code r g b RG} (stroke) or {@code r g b rg} (fill) op to {@code sb}. */
    private static void appendRgb(StringBuilder sb, org.aspose.pdf.Color c, boolean fill) {
        // Color.getR/G/B already returns 0..1.
        double r = c != null ? c.getR() : 0;
        double g = c != null ? c.getG() : 0;
        double b = c != null ? c.getB() : 0;
        sb.append(fmt(r)).append(' ').append(fmt(g)).append(' ').append(fmt(b))
                .append(fill ? " rg\n" : " RG\n");
    }

    /**
     * Encodes {@code s} as a PDF literal string (parens) with the canonical
     * escape set from ISO 32000-1 §7.3.4.2: backslash, parens, CR, LF, tab,
     * backspace, form-feed, plus 8-bit bytes via three-octal {@code \DDD}.
     * Strings are encoded as WinAnsi (cp1252), matching the font's
     * {@code /Encoding /WinAnsiEncoding} stamp.
     */
    private static String escapeLiteralString(String s) {
        byte[] bytes;
        try {
            bytes = s.getBytes("Cp1252");
        } catch (java.io.UnsupportedEncodingException e) {
            bytes = s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        }
        StringBuilder out = new StringBuilder(bytes.length + 2);
        out.append('(');
        for (byte raw : bytes) {
            int b = raw & 0xFF;
            switch (b) {
                case '\\': out.append("\\\\"); break;
                case '(':  out.append("\\(");  break;
                case ')':  out.append("\\)");  break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                case 0x08: out.append("\\b");  break;
                case 0x0c: out.append("\\f");  break;
                default:
                    if (b < 0x20 || b > 0x7E) {
                        out.append('\\');
                        out.append((char) ('0' + ((b >> 6) & 0x07)));
                        out.append((char) ('0' + ((b >> 3) & 0x07)));
                        out.append((char) ('0' + (b & 0x07)));
                    } else {
                        out.append((char) b);
                    }
            }
        }
        out.append(')');
        return out.toString();
    }

    /** Saves the bound document to {@code outputFile}. */
    public boolean save(String outputFile) {
        if (document == null) return false;
        try {
            document.requestFullRewrite();
            document.save(outputFile);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PdfFileMend output: " + outputFile, e);
            return false;
        }
    }

    /** Saves the bound document to {@code outputStream}. */
    public boolean save(OutputStream outputStream) {
        if (document == null) return false;
        try {
            document.requestFullRewrite();
            document.save(outputStream);
            return true;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to save PdfFileMend output stream", e);
            return false;
        }
    }

    /**
     * Releases the bound document. If constructed with
     * {@link #PdfFileMend(InputStream, OutputStream)}, the result is auto-saved
     * to the bound output stream first.
     */
    @Override
    public void close() {
        if (pendingOutputStream != null && document != null) {
            save(pendingOutputStream);
            pendingOutputStream = null;
        } else if (pendingOutputFile != null && document != null) {
            // Mirrors the C# PdfFileMend.Close() behavior for the (in, out) ctor:
            // pending edits are committed to the output path on close so the
            // try-with-resources idiom flushes correctly.
            save(pendingOutputFile);
            pendingOutputFile = null;
        }
        if (document != null && ownsDocument) {
            try { document.close(); } catch (IOException ignored) {}
        }
        document = null;
    }
}
