package org.aspose.pdf.sdm.xlsx;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Office Open XML spreadsheet ({@code .xlsx}) &rarr; Semantic Document Model
 * reader &mdash; the inverse of {@link SdmXlsxWriter} and the XLSX sibling of
 * {@link org.aspose.pdf.sdm.docx.DocxSdmReader}. Each worksheet becomes an SDM
 * {@link Table}; the shared {@code SdmPdfLayout} then paginates the model to PDF.
 *
 * <p>Parses shared strings, the style table (fonts, fills, number formats,
 * alignment), merged ranges (&rarr; colSpan/rowSpan), column widths, row heights
 * and cell-anchored drawings (&rarr; images in cells). Cell values are rendered
 * to display text through the cell's number format. Zero third-party
 * dependencies &mdash; {@code java.util.zip} + {@code javax.xml}.</p>
 */
public final class XlsxSdmReader {

    private static final Logger LOG = Logger.getLogger(XlsxSdmReader.class.getName());
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    private Map<String, byte[]> parts;
    private List<String> sharedStrings = new ArrayList<>();
    private final List<Xf> cellXfs = new ArrayList<>();
    private final List<Font> fonts = new ArrayList<>();
    private final List<Integer> fillColors = new ArrayList<>(); // -1 = none
    private final Map<Integer, String> numFmts = new HashMap<>();
    private org.aspose.pdf.sdm.ResourceTable resources;
    private int imageSeq;

    /** When true, cells become AcroForm fields and formulas become PDF JavaScript. */
    private boolean interactiveForms;

    /**
     * Enables "live" interactive-form conversion: each non-empty cell becomes an
     * AcroForm field and each formula becomes a PDF JavaScript calculate action.
     *
     * @param interactiveForms true to emit form fields and formula JavaScript
     */
    public void setInteractiveForms(boolean interactiveForms) {
        this.interactiveForms = interactiveForms;
    }

    /** Parsed cell format record. */
    private static final class Xf {
        int numFmtId;
        int fontId;
        int fillId;
        String halign;
    }

    /** Parsed font record. */
    private static final class Font {
        boolean bold;
        boolean italic;
        double size = 11;
        String name = "Calibri";
        int rgb = -1; // -1 = automatic/black
    }

    /**
     * Reads an {@code .xlsx} package into an SDM document (one table per sheet).
     *
     * @param xlsx the package bytes
     * @return the SDM document
     * @throws IOException if the package cannot be read
     */
    public SdmDocument read(byte[] xlsx) throws IOException {
        parts = unzip(xlsx);
        SdmDocument sdm = new SdmDocument();
        this.resources = sdm.getResources();
        sharedStrings = parseSharedStrings(parts.get("xl/sharedStrings.xml"));
        parseStyles(parts.get("xl/styles.xml"));

        List<String[]> sheets = parseWorkbookSheets(); // {name, targetPath}
        boolean multi = sheets.size() > 1;

        // Pre-parse every worksheet up front so interactive mode can resolve
        // cross-sheet references and detect which cells a formula reads.
        List<SheetModel> models = new ArrayList<>();
        for (int si = 0; si < sheets.size(); si++) {
            String name = sheets.get(si)[0];
            String path = sheets.get(si)[1];
            byte[] xml = parts.get(path);
            if (xml == null) {
                continue;
            }
            Map<String, int[]> images = parseSheetImages(path);
            SheetModel m = parseSheetModel(name, si, xml, images);
            if (m != null) {
                models.add(m);
            }
        }

        InteractiveContext ctx = interactiveForms ? buildInteractiveContext(models) : null;

        for (SheetModel m : models) {
            Table table = buildTable(m, ctx);
            if (table == null) {
                continue;
            }
            if (multi && m.name != null && !m.name.isEmpty()) {
                Heading h = new Heading(2);
                h.getInline().add(new Run(m.name, null));
                sdm.getChildren().add(h);
            }
            sdm.getChildren().add(table);
        }

        if (ctx != null) {
            // The layout emits the fields (+ calculate scripts); the doc-level JS
            // runtime and the AcroForm calculation order are applied on the final
            // document after pagination (see Document.initFromXlsx).
            sdm.getAttributes().put("xlsx-doc-js", ExcelFormulaTranslator.runtimeLibrary());
            sdm.getAttributes().put("xlsx-calc-order", ctx.calcOrder);
        }
        return sdm;
    }

    /**
     * Reads an {@code .xlsx} package from a stream.
     *
     * @param in the input stream
     * @return the SDM document
     * @throws IOException if the package cannot be read
     */
    public SdmDocument read(InputStream in) throws IOException {
        return read(readAll(in));
    }

    // ------------------------------------------------------------------
    // Worksheet → Table
    // ------------------------------------------------------------------

    /** A worksheet parsed into a sparse cell grid plus its used dimensions. */
    private static final class SheetModel {
        String name;
        int index;
        Element ws;
        Map<Long, ParsedCell> grid;
        int nRows;
        int nCols;
        Map<String, int[]> images;
    }

    /** Parses one worksheet XML into a {@link SheetModel} (no SDM build yet). */
    private SheetModel parseSheetModel(String name, int index, byte[] sheetXml,
                                       Map<String, int[]> images) throws IOException {
        Element ws = parse(sheetXml);
        Element sheetData = firstDescendant(ws, "sheetData");
        if (sheetData == null) {
            return null;
        }
        Map<Long, ParsedCell> grid = new LinkedHashMap<>();
        int maxRow = -1;
        int maxCol = -1;
        for (Element row : childElements(sheetData, "row")) {
            for (Element c : childElements(row, "c")) {
                String ref = attrNS(c, "r");
                if (ref == null) {
                    continue;
                }
                int[] rc = refToRc(ref);
                ParsedCell pc = new ParsedCell();
                pc.row = rc[0];
                pc.col = rc[1];
                pc.styleIdx = intAttr(c, "s", 0);
                pc.type = attrNS(c, "t");
                Element v = firstChildElement(c, "v");
                pc.value = v != null ? v.getTextContent() : null;
                Element ff = firstChildElement(c, "f");
                if (ff != null) {
                    pc.formula = ff.getTextContent();
                }
                Element is = firstChildElement(c, "is");
                if (is != null) {
                    Element t = firstDescendant(is, "t");
                    if (t != null) {
                        pc.inlineStr = t.getTextContent();
                    }
                }
                grid.put(key(rc[0], rc[1]), pc);
                maxRow = Math.max(maxRow, rc[0]);
                maxCol = Math.max(maxCol, rc[1]);
            }
        }
        for (int[] a : images.values()) {
            maxRow = Math.max(maxRow, a[0]);
            maxCol = Math.max(maxCol, a[1]);
        }
        if (maxRow < 0 || maxCol < 0) {
            return null;
        }
        SheetModel m = new SheetModel();
        m.name = name;
        m.index = index;
        m.ws = ws;
        m.grid = grid;
        m.nRows = maxRow + 1;
        m.nCols = maxCol + 1;
        m.images = images;
        return m;
    }

    /** Builds the SDM {@link Table} for a worksheet, attaching field metadata in interactive mode. */
    private Table buildTable(SheetModel m, InteractiveContext ctx) {
        int nRows = m.nRows;
        int nCols = m.nCols;
        Map<Long, ParsedCell> grid = m.grid;

        // Merged ranges → per-anchor spans + covered mask.
        boolean[][] covered = new boolean[nRows][nCols];
        int[][] colSpan = new int[nRows][nCols];
        int[][] rowSpan = new int[nRows][nCols];
        for (String mref : parseMerges(m.ws)) {
            int[] rng = rangeToRc(mref); // r0,c0,r1,c1
            if (rng == null) {
                continue;
            }
            colSpan[rng[0]][rng[1]] = rng[3] - rng[1] + 1;
            rowSpan[rng[0]][rng[1]] = rng[2] - rng[0] + 1;
            for (int rr = rng[0]; rr <= rng[2] && rr < nRows; rr++) {
                for (int cc = rng[1]; cc <= rng[3] && cc < nCols; cc++) {
                    if (rr != rng[0] || cc != rng[1]) {
                        covered[rr][cc] = true;
                    }
                }
            }
        }

        Table table = new Table();
        table.getAttributes().put("border", "ruled");
        double[] colWidthsPt = parseColWidths(m.ws, nCols);
        for (int c = 0; c < nCols; c++) {
            if (colWidthsPt[c] > 0) {
                table.getColumns().add(new ColumnSpec(ColumnSpec.WidthType.POINTS,
                        colWidthsPt[c], ColumnSpec.Align.LEFT));
            } else {
                table.getColumns().add(new ColumnSpec());
            }
        }

        for (int r = 0; r < nRows; r++) {
            TableRow tr = new TableRow(TableRow.Kind.BODY);
            for (int c = 0; c < nCols; c++) {
                if (covered[r][c]) {
                    continue;
                }
                TableCell cell = new TableCell();
                int cs = Math.max(1, colSpan[r][c]);
                int rs = Math.max(1, rowSpan[r][c]);
                if (cs > 1) {
                    cell.setColSpan(cs);
                }
                if (rs > 1) {
                    cell.setRowSpan(rs);
                }
                ParsedCell pc = grid.get(key(r, c));
                String text = pc != null ? displayText(pc) : "";
                Xf xf = pc != null ? xfAt(pc.styleIdx) : null;
                applyCellStyle(cell, text, xf);
                if (ctx != null && pc != null) {
                    attachField(cell, m.index, pc, xf, text, ctx);
                }
                for (Map.Entry<String, int[]> e : m.images.entrySet()) {
                    if (e.getValue()[0] == r && e.getValue()[1] == c) {
                        addCellImage(cell, e.getKey());
                        break;
                    }
                }
                tr.getCells().add(cell);
            }
            table.getRows().add(tr);
        }
        return table;
    }

    // ------------------------------------------------------------------
    // Interactive (live) form conversion
    // ------------------------------------------------------------------

    /** Data computed across all sheets for interactive-form conversion. */
    private static final class InteractiveContext {
        final Map<String, Integer> sheetIndex = new HashMap<>();
        final java.util.Set<String> present = new java.util.HashSet<>();
        final java.util.Set<String> referenced = new java.util.HashSet<>();
        final Map<String, String> calcByField = new HashMap<>();       // formula field → calc JS
        final Map<String, java.util.Set<String>> depsByField = new HashMap<>();
        final java.util.LinkedHashSet<String> formulaFields = new java.util.LinkedHashSet<>();
        java.util.List<String> calcOrder = new ArrayList<>();
    }

    /** Cross-sheet analysis: presence, formula translation, referenced set, calc order. */
    private InteractiveContext buildInteractiveContext(List<SheetModel> models) {
        InteractiveContext ctx = new InteractiveContext();
        for (SheetModel m : models) {
            if (m.name != null) {
                ctx.sheetIndex.put(m.name, m.index);
            }
            for (ParsedCell pc : m.grid.values()) {
                if (isContentful(pc)) {
                    ctx.present.add(fieldName(m.index, pc.row, pc.col));
                }
            }
        }
        for (SheetModel m : models) {
            final int si = m.index;
            ExcelFormulaTranslator tr = new ExcelFormulaTranslator(m.name, (sheet, row, col) -> {
                int idx = sheet == null ? si : ctx.sheetIndex.getOrDefault(sheet, -1);
                if (idx < 0) {
                    return null;
                }
                String fn = fieldName(idx, row, col);
                return ctx.present.contains(fn) ? fn : null;
            });
            for (ParsedCell pc : m.grid.values()) {
                if (pc.formula == null || pc.formula.isEmpty()) {
                    continue;
                }
                String fn = fieldName(si, pc.row, pc.col);
                try {
                    ExcelFormulaTranslator.Result res = tr.translate(pc.formula);
                    ctx.calcByField.put(fn, res.js);
                    ctx.depsByField.put(fn, res.refs);
                    ctx.referenced.addAll(res.refs);
                    ctx.formulaFields.add(fn);
                } catch (ExcelFormulaTranslator.UnsupportedFormulaException e) {
                    LOG.fine("xlsx live: formula '" + pc.formula + "' -> static ("
                            + e.getMessage() + ")");
                }
            }
        }
        ctx.calcOrder = topoOrder(ctx.formulaFields, ctx.depsByField);
        return ctx;
    }

    /**
     * Attaches AcroForm field metadata to a cell. Policy: numeric constants →
     * editable input fields; formula cells → read-only fields that recalculate;
     * text/label cells stay static unless a formula references them (then an
     * editable field). Untranslatable formulas fall back to static text.
     */
    private void attachField(TableCell cell, int sheetIdx, ParsedCell pc, Xf xf,
                             String text, InteractiveContext ctx) {
        String fn = fieldName(sheetIdx, pc.row, pc.col);
        String calc = ctx.calcByField.get(fn);
        boolean numeric = numericCell(pc);
        boolean readonly;
        boolean makeField;
        if (calc != null) {
            makeField = true;
            readonly = true;                 // computed cell: recalculates, not typed into
        } else if (numeric) {
            makeField = true;
            readonly = false;                // an input the user can change
        } else if (ctx.referenced.contains(fn) && !isContentless(text)) {
            makeField = true;
            readonly = false;                // a referenced text input (e.g. CONCATENATE source)
        } else {
            makeField = false;               // a static label / heading
            readonly = false;
        }
        if (!makeField) {
            return;
        }
        java.util.Map<String, Object> a = cell.getAttributes();
        a.put("xlsx-field-name", fn);
        a.put("xlsx-field-readonly", readonly);
        a.put("xlsx-field-numeric", numeric);
        a.put("xlsx-field-quadding", quaddingFor(xf, numeric));
        // Numeric fields store the raw number and format via an Acrobat format
        // action (so AXL.f/parseFloat sees a clean value); text fields store the
        // display text verbatim.
        a.put("xlsx-field-value", numeric ? rawNumber(pc, text) : text);
        String fmt = formatAction(xf, numeric);
        if (fmt != null) {
            a.put("xlsx-field-format-js", fmt);
        }
        if (calc != null) {
            a.put("xlsx-field-calc-js", calc);
        }
    }

    /** A cell is contentful (worth a field) if it has a formula or non-blank display text. */
    private boolean isContentful(ParsedCell pc) {
        if (pc == null) {
            return false;
        }
        if (pc.formula != null && !pc.formula.isEmpty()) {
            return true;
        }
        return !isContentless(displayText(pc));
    }

    private static boolean isContentless(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** True if the cell's cached value is a plain number (not a shared string/bool/error/text). */
    private static boolean numericCell(ParsedCell pc) {
        if (pc == null || pc.inlineStr != null) {
            return false;
        }
        String t = pc.type;
        if ("s".equals(t) || "str".equals(t) || "b".equals(t) || "e".equals(t)) {
            return false;
        }
        if (pc.value == null || pc.value.trim().isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(pc.value.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The raw numeric string for a numeric field's /V, falling back to display text. */
    private static String rawNumber(ParsedCell pc, String text) {
        if (pc.value != null && !pc.value.trim().isEmpty()) {
            return pc.value.trim();
        }
        return text;
    }

    /** Acrobat field quadding: numbers right, else honour the cell's horizontal alignment. */
    private static int quaddingFor(Xf xf, boolean numeric) {
        String align = xf != null ? xf.halign : null;
        if ("center".equals(align)) {
            return 1;
        }
        if ("right".equals(align)) {
            return 2;
        }
        if ("left".equals(align)) {
            return 0;
        }
        return numeric ? 2 : 0;
    }

    /** Builds the {@code /AA /F} format-action JavaScript for a numeric field, or {@code null}. */
    private String formatAction(Xf xf, boolean numeric) {
        if (!numeric) {
            return null;
        }
        String fmt = xf != null ? numFmts.get(xf.numFmtId) : null;
        int numFmtId = xf != null ? xf.numFmtId : 0;
        boolean percent = (fmt != null && fmt.contains("%")) || numFmtId == 9 || numFmtId == 10;
        int dec = fmt != null && fmt.contains(".00") ? 2 : 0;
        if (percent) {
            return "AFPercent_Format(" + dec + ",0)";
        }
        boolean grouped = fmt != null && fmt.contains("#,##0");
        // AFNumber_Format(nDec, sepStyle, negStyle, currStyle, strCurrency, bCurrencyPrepend)
        int sep = grouped ? 0 : 1; // 0 = 1,234.56 (grouped) ; 1 = 1234.56 (no group)
        return "AFNumber_Format(" + dec + "," + sep + ",0,0,\"\",false)";
    }

    /** Topological order of formula fields (dependencies first); insertion order on cycle. */
    private static java.util.List<String> topoOrder(java.util.LinkedHashSet<String> nodes,
                                                     Map<String, java.util.Set<String>> deps) {
        java.util.List<String> out = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        java.util.Set<String> stack = new java.util.HashSet<>();
        for (String n : nodes) {
            visit(n, nodes, deps, done, stack, out);
        }
        return out;
    }

    private static void visit(String n, java.util.LinkedHashSet<String> nodes,
                              Map<String, java.util.Set<String>> deps, java.util.Set<String> done,
                              java.util.Set<String> stack, java.util.List<String> out) {
        if (done.contains(n) || stack.contains(n)) {
            return; // already placed, or a cycle — break it
        }
        stack.add(n);
        java.util.Set<String> ds = deps.get(n);
        if (ds != null) {
            for (String d : ds) {
                if (nodes.contains(d)) {
                    visit(d, nodes, deps, done, stack, out);
                }
            }
        }
        stack.remove(n);
        if (done.add(n)) {
            out.add(n);
        }
    }

    /** The PDF field name for a cell: {@code S<sheet>_<A1>} (dot-free, hierarchy-safe). */
    static String fieldName(int sheetIdx, int row, int col) {
        return "S" + sheetIdx + "_" + a1(row, col);
    }

    /** 0-based (row,col) → A1 string. */
    static String a1(int row, int col) {
        StringBuilder sb = new StringBuilder();
        int c = col + 1;
        while (c > 0) {
            int rem = (c - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return sb.append(row + 1).toString();
    }

    /** Builds the cell's paragraph/run + fill from the style, if any. */
    private void applyCellStyle(TableCell cell, String text, Xf xf) {
        TextStyle ts = null;
        String align = null;
        if (xf != null) {
            if (xf.fontId >= 0 && xf.fontId < fonts.size()) {
                Font f = fonts.get(xf.fontId);
                ts = new TextStyle();
                ts.setBold(f.bold);
                ts.setItalic(f.italic);
                ts.setFontSize(f.size);
                if (f.name != null) {
                    ts.setFontFamily(f.name);
                }
                if (f.rgb >= 0) {
                    ts.setColor(0xFF000000 | f.rgb);
                }
            }
            int fill = fillColorAt(xf.fillId);
            if (fill >= 0) {
                BlockStyle bs = new BlockStyle();
                bs.setBackground(0xFF000000 | fill);
                cell.setStyle(bs);
            }
            align = xf.halign;
        }
        Paragraph p = new Paragraph();
        if (align != null) {
            BlockStyle ps = new BlockStyle();
            if ("center".equals(align)) {
                ps.setAlign(BlockStyle.Align.CENTER);
            } else if ("right".equals(align)) {
                ps.setAlign(BlockStyle.Align.RIGHT);
            }
            p.setStyle(ps);
        }
        p.getInline().add(new Run(text == null ? "" : text, ts));
        cell.getChildren().add(p);
    }

    private void addCellImage(TableCell cell, String mediaPath) {
        byte[] bytes = parts.get(mediaPath);
        if (bytes == null || resources == null) {
            return;
        }
        String mime = mediaPath.endsWith(".jpeg") || mediaPath.endsWith(".jpg")
                ? "image/jpeg" : "image/png";
        ResourceRef ref = resources.put("xlimg:" + (imageSeq++),
                new Resource(Resource.Kind.IMAGE, bytes, mime));
        Figure fig = new Figure(ref);
        // Give the figure a footprint so the layout sizes it (px @96dpi → pt).
        try {
            java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(new ByteArrayInputStream(bytes));
            if (bi != null && bi.getWidth() > 0) {
                fig.getAttributes().put("display-width", bi.getWidth() * 72.0 / 96.0);
                fig.getAttributes().put("display-height", bi.getHeight() * 72.0 / 96.0);
            }
        } catch (IOException | RuntimeException ignore) {
            // no intrinsic size — the layout will use a default
        }
        cell.getChildren().add(0, fig);
    }

    // ------------------------------------------------------------------
    // Value formatting
    // ------------------------------------------------------------------

    private String displayText(ParsedCell pc) {
        if (pc.inlineStr != null) {
            return pc.inlineStr;
        }
        if ("s".equals(pc.type)) {
            int idx = parseIntSafe(pc.value, -1);
            return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : "";
        }
        if ("b".equals(pc.type)) {
            return "1".equals(pc.value == null ? "" : pc.value.trim()) ? "TRUE" : "FALSE";
        }
        if ("str".equals(pc.type) || "e".equals(pc.type)) {
            return pc.value == null ? "" : pc.value;
        }
        if (pc.value == null || pc.value.isEmpty()) {
            return "";
        }
        double d;
        try {
            d = Double.parseDouble(pc.value.trim());
        } catch (NumberFormatException e) {
            return pc.value;
        }
        Xf xf = xfAt(pc.styleIdx);
        String fmt = xf != null ? numFmts.get(xf.numFmtId) : null;
        return formatNumber(d, fmt, xf != null ? xf.numFmtId : 0);
    }

    /** Renders a numeric value to display text through a subset of number formats. */
    private static String formatNumber(double d, String fmt, int numFmtId) {
        // Dates (custom yyyy/dd.mm/mm/dd or builtin 14-22/45-47).
        boolean dateFmt = fmt != null && (fmt.contains("yy") || fmt.contains("mm-")
                || (fmt.contains("mm") && fmt.contains("dd")))
                || (numFmtId >= 14 && numFmtId <= 22);
        if (dateFmt && d >= 1) {
            LocalDate date = EXCEL_EPOCH.plusDays((long) d);
            if (fmt != null && fmt.contains("dd") && fmt.indexOf('.') >= 0) {
                return String.format(Locale.ROOT, "%02d.%02d.%04d",
                        date.getDayOfMonth(), date.getMonthValue(), date.getYear());
            }
            if (fmt != null && fmt.indexOf('/') >= 0) {
                return String.format(Locale.ROOT, "%02d/%02d/%04d",
                        date.getMonthValue(), date.getDayOfMonth(), date.getYear());
            }
            return String.format(Locale.ROOT, "%04d-%02d-%02d",
                    date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        }
        if (fmt != null && fmt.contains("%")) {
            boolean dec = fmt.contains(".00");
            double pct = d * 100.0;
            return (dec ? trim(String.format(Locale.ROOT, "%.2f", pct))
                    : String.valueOf(Math.round(pct))) + "%";
        }
        String currency = currencyPrefix(fmt);
        boolean grouped = fmt != null && fmt.contains("#,##0");
        boolean twoDec = fmt != null && fmt.contains("0.00");
        String num;
        if (twoDec) {
            num = String.format(Locale.ROOT, grouped ? "%,.2f" : "%.2f", d);
        } else if (grouped) {
            num = String.format(Locale.ROOT, "%,.0f", d);
        } else {
            num = trim(String.format(Locale.ROOT, "%.6f", d));
        }
        return currency + num;
    }

    private static String currencyPrefix(String fmt) {
        if (fmt == null) {
            return "";
        }
        int q1 = fmt.indexOf('"');
        if (q1 >= 0) {
            int q2 = fmt.indexOf('"', q1 + 1);
            if (q2 > q1) {
                return fmt.substring(q1 + 1, q2);
            }
        }
        return "";
    }

    private static String trim(String s) {
        if (s.indexOf('.') < 0) {
            return s;
        }
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    // ------------------------------------------------------------------
    // Styles
    // ------------------------------------------------------------------

    private void parseStyles(byte[] xml) throws IOException {
        if (xml == null) {
            return;
        }
        Element root = parse(xml);
        Element numFmtsEl = firstDescendant(root, "numFmts");
        if (numFmtsEl != null) {
            for (Element nf : childElements(numFmtsEl, "numFmt")) {
                int id = intAttr(nf, "numFmtId", -1);
                String code = attrNS(nf, "formatCode");
                if (id >= 0 && code != null) {
                    numFmts.put(id, code);
                }
            }
        }
        Element fontsEl = firstDescendant(root, "fonts");
        if (fontsEl != null) {
            for (Element fe : childElements(fontsEl, "font")) {
                Font f = new Font();
                f.bold = firstChildElement(fe, "b") != null;
                f.italic = firstChildElement(fe, "i") != null;
                Element sz = firstChildElement(fe, "sz");
                if (sz != null) {
                    f.size = parseDoubleSafe(attrNS(sz, "val"), 11);
                }
                Element nm = firstChildElement(fe, "name");
                if (nm != null && attrNS(nm, "val") != null) {
                    f.name = attrNS(nm, "val");
                }
                Element col = firstChildElement(fe, "color");
                if (col != null) {
                    f.rgb = parseArgb(attrNS(col, "rgb"));
                }
                fonts.add(f);
            }
        }
        Element fillsEl = firstDescendant(root, "fills");
        if (fillsEl != null) {
            for (Element fe : childElements(fillsEl, "fill")) {
                Element pf = firstChildElement(fe, "patternFill");
                int rgb = -1;
                if (pf != null && "solid".equals(attrNS(pf, "patternType"))) {
                    Element fg = firstChildElement(pf, "fgColor");
                    if (fg != null) {
                        rgb = parseArgb(attrNS(fg, "rgb"));
                    }
                }
                fillColors.add(rgb);
            }
        }
        Element cellXfsEl = firstDescendant(root, "cellXfs");
        if (cellXfsEl != null) {
            for (Element xe : childElements(cellXfsEl, "xf")) {
                Xf xf = new Xf();
                xf.numFmtId = intAttr(xe, "numFmtId", 0);
                xf.fontId = intAttr(xe, "fontId", 0);
                xf.fillId = intAttr(xe, "fillId", 0);
                Element al = firstChildElement(xe, "alignment");
                if (al != null) {
                    xf.halign = attrNS(al, "horizontal");
                }
                cellXfs.add(xf);
            }
        }
    }

    private Xf xfAt(int idx) {
        return idx >= 0 && idx < cellXfs.size() ? cellXfs.get(idx) : null;
    }

    /** Fill RGB for a fill index, or -1 for none / reserved gray125. */
    private int fillColorAt(int fillId) {
        if (fillId <= 1 || fillId >= fillColors.size()) {
            return -1; // 0 none, 1 gray125 reserved
        }
        return fillColors.get(fillId);
    }

    // ------------------------------------------------------------------
    // Workbook / drawings
    // ------------------------------------------------------------------

    private List<String[]> parseWorkbookSheets() throws IOException {
        List<String[]> out = new ArrayList<>();
        byte[] wb = parts.get("xl/workbook.xml");
        if (wb == null) {
            return out;
        }
        Map<String, String> rels = parseRels("xl/_rels/workbook.xml.rels", "xl/");
        Element root = parse(wb);
        Element sheetsEl = firstDescendant(root, "sheets");
        if (sheetsEl == null) {
            return out;
        }
        for (Element sh : childElements(sheetsEl, "sheet")) {
            String name = attrNS(sh, "name");
            String rid = attrNS(sh, "id"); // r:id
            String target = rid != null ? rels.get(rid) : null;
            if (target != null) {
                out.add(new String[]{name, target});
            }
        }
        return out;
    }

    /** Maps a cell "row,col" (as "r,c" key string) to the media path of an anchored image. */
    private Map<String, int[]> parseSheetImages(String sheetPath) {
        // Returns media-path -> {row, col}.
        Map<String, int[]> result = new LinkedHashMap<>();
        try {
            String relsPath = relsPathFor(sheetPath);
            Map<String, String> sheetRels = parseRels(relsPath, dir(sheetPath));
            String drawingPath = null;
            for (String v : sheetRels.values()) {
                if (v != null && v.contains("drawings/")) {
                    drawingPath = v;
                    break;
                }
            }
            if (drawingPath == null || !parts.containsKey(drawingPath)) {
                return result;
            }
            Map<String, String> drawRels = parseRels(relsPathFor(drawingPath), dir(drawingPath));
            Element dr = parse(parts.get(drawingPath));
            NodeList anchors = dr.getElementsByTagNameNS("*", "oneCellAnchor");
            collectAnchors(anchors, drawRels, result);
            collectAnchors(dr.getElementsByTagNameNS("*", "twoCellAnchor"), drawRels, result);
        } catch (Exception e) {
            LOG.fine("xlsx drawing parse skipped: " + e);
        }
        return result;
    }

    private void collectAnchors(NodeList anchors, Map<String, String> drawRels,
                                Map<String, int[]> result) {
        for (int i = 0; i < anchors.getLength(); i++) {
            Element anchor = (Element) anchors.item(i);
            Element from = firstDescendant(anchor, "from");
            if (from == null) {
                continue;
            }
            int col = parseIntSafe(text(firstDescendant(from, "col")), 0);
            int row = parseIntSafe(text(firstDescendant(from, "row")), 0);
            Element blip = firstDescendant(anchor, "blip");
            if (blip == null) {
                continue;
            }
            String embed = attrNS(blip, "embed");
            String media = embed != null ? drawRels.get(embed) : null;
            if (media != null && parts.containsKey(media)) {
                result.put(media, new int[]{row, col});
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared strings / merges / cols
    // ------------------------------------------------------------------

    private List<String> parseSharedStrings(byte[] xml) throws IOException {
        List<String> out = new ArrayList<>();
        if (xml == null) {
            return out;
        }
        Element root = parse(xml);
        for (Element si : childElements(root, "si")) {
            StringBuilder sb = new StringBuilder();
            // <si><t>..</t></si> or <si><r><t>..</t></r>...</si>
            NodeList ts = si.getElementsByTagNameNS("*", "t");
            for (int i = 0; i < ts.getLength(); i++) {
                sb.append(ts.item(i).getTextContent());
            }
            out.add(sb.toString());
        }
        return out;
    }

    private List<String> parseMerges(Element ws) {
        List<String> out = new ArrayList<>();
        Element mc = firstDescendant(ws, "mergeCells");
        if (mc == null) {
            return out;
        }
        for (Element m : childElements(mc, "mergeCell")) {
            String ref = attrNS(m, "ref");
            if (ref != null) {
                out.add(ref);
            }
        }
        return out;
    }

    private double[] parseColWidths(Element ws, int nCols) {
        double[] out = new double[nCols];
        Element cols = firstDescendant(ws, "cols");
        if (cols == null) {
            return out;
        }
        for (Element col : childElements(cols, "col")) {
            int min = intAttr(col, "min", 1);
            int max = intAttr(col, "max", min);
            double width = parseDoubleSafe(attrNS(col, "width"), 0);
            if (width <= 0) {
                continue;
            }
            // Inverse of the writer's width = (px-5)/7 + 1, px = pt*96/72.
            double px = (width - 1) * 7 + 5;
            double pt = px * 72.0 / 96.0;
            for (int c = min - 1; c <= max - 1 && c < nCols; c++) {
                if (c >= 0) {
                    out[c] = pt;
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    /** A raw parsed cell. */
    private static final class ParsedCell {
        int row;
        int col;
        int styleIdx;
        String type;
        String value;
        String inlineStr;
        String formula;
    }

    private static long key(int r, int c) {
        return ((long) r << 20) | (c & 0xFFFFF);
    }

    private static int[] refToRc(String ref) {
        int i = 0;
        int col = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        int row = 0;
        while (i < ref.length() && Character.isDigit(ref.charAt(i))) {
            row = row * 10 + (ref.charAt(i) - '0');
            i++;
        }
        return new int[]{row - 1, col - 1};
    }

    private static int[] rangeToRc(String range) {
        int colon = range.indexOf(':');
        if (colon < 0) {
            return null;
        }
        int[] a = refToRc(range.substring(0, colon));
        int[] b = refToRc(range.substring(colon + 1));
        return new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1])};
    }

    private static int parseArgb(String rgb) {
        if (rgb == null || rgb.isEmpty()) {
            return -1;
        }
        try {
            long v = Long.parseLong(rgb, 16);
            return (int) (v & 0xFFFFFF); // drop alpha
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Map<String, String> parseRels(String relsPath, String baseDir) throws IOException {
        Map<String, String> out = new HashMap<>();
        byte[] xml = parts.get(relsPath);
        if (xml == null) {
            return out;
        }
        Element root = parse(xml);
        for (Element rel : childElements(root, "Relationship")) {
            String id = attrNS(rel, "Id");
            String target = attrNS(rel, "Target");
            if (id == null || target == null) {
                continue;
            }
            out.put(id, normalizePath(baseDir, target));
        }
        return out;
    }

    /** Resolves a possibly-relative rels target against a base directory. */
    private static String normalizePath(String baseDir, String target) {
        if (target.startsWith("/")) {
            return target.substring(1);
        }
        String path = baseDir + target;
        // Collapse "../" segments.
        String[] segs = path.split("/");
        java.util.Deque<String> stack = new java.util.ArrayDeque<>();
        for (String s : segs) {
            if (s.equals("..")) {
                if (!stack.isEmpty()) {
                    stack.removeLast();
                }
            } else if (!s.equals(".") && !s.isEmpty()) {
                stack.addLast(s);
            }
        }
        return String.join("/", stack);
    }

    private static String dir(String path) {
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(0, i + 1) : "";
    }

    private static String relsPathFor(String path) {
        int i = path.lastIndexOf('/');
        String d = i >= 0 ? path.substring(0, i + 1) : "";
        String name = i >= 0 ? path.substring(i + 1) : path;
        return d + "_rels/" + name + ".rels";
    }

    private static String text(Element el) {
        return el == null ? null : el.getTextContent();
    }

    private static int parseIntSafe(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDoubleSafe(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static int intAttr(Element el, String name, int def) {
        return parseIntSafe(attrNS(el, name), def);
    }

    private static org.w3c.dom.Document parseDoc(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return dbf.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("malformed spreadsheet part: " + e.getMessage(), e);
        }
    }

    private static Element parse(byte[] xml) throws IOException {
        return parseDoc(xml).getDocumentElement();
    }

    private static String local(Element el) {
        String n = el.getLocalName();
        return n != null ? n : el.getTagName();
    }

    private static Element firstChildElement(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(local((Element) n))) {
                return (Element) n;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && localName.equals(local((Element) n))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstDescendant(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    private static String attrNS(Element el, String localName) {
        if (el == null) {
            return null;
        }
        String v = el.getAttribute(localName);
        if (v != null && !v.isEmpty()) {
            return v;
        }
        org.w3c.dom.NamedNodeMap attrs = el.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String ln = a.getLocalName();
            if (localName.equals(ln != null ? ln : a.getNodeName())) {
                return a.getNodeValue();
            }
        }
        return null;
    }

    private Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        Map<String, byte[]> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                out.put(e.getName(), bos.toByteArray());
            }
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

}
