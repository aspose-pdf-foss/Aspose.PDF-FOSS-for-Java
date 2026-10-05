package org.aspose.pdf.sdm.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.aspose.pdf.ExcelSaveOptions;
import org.aspose.pdf.sdm.BlockStyle;
import org.aspose.pdf.sdm.CellValue;
import org.aspose.pdf.sdm.ColumnSpec;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.InlineImage;
import org.aspose.pdf.sdm.Resource;
import org.aspose.pdf.sdm.ResourceRef;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.SdmMetadata;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TextStyle;

/**
 * Semantic Document Model &rarr; Office Open XML spreadsheet ({@code .xlsx})
 * writer. The spreadsheet sibling of {@link org.aspose.pdf.sdm.docx.SdmDocxWriter}
 * and {@link org.aspose.pdf.sdm.html.SdmHtmlWriter}: it projects the recognised
 * {@link Table} blocks of the IR onto SpreadsheetML (ISO/IEC 29500) worksheets of
 * typed cells.
 *
 * <p>Zero third-party dependencies &mdash; the ZIP package and every XML part is
 * emitted by hand from {@code java.util.zip} (project constraint).</p>
 *
 * <p><b>Mapping.</b> Each {@link Table} becomes a worksheet (or all tables are
 * stacked on one sheet when
 * {@link ExcelSaveOptions#isMinimizeTheNumberOfWorksheets()}). A cell's text is
 * typed by {@link CellValueTyper} into a number, date, boolean or string and the
 * result is both written as a native spreadsheet value and recorded back on the
 * SDM {@link TableCell#setCellValue(CellValue) cell value}. {@code colSpan}/
 * {@code rowSpan} become {@code mergeCell} ranges; header rows/cells are bold and
 * frozen. When the document has no recognised table, a single sheet captures the
 * top-level prose so the output is never empty.</p>
 *
 * <p>The writer never mutates the document structure (only fills in cell types)
 * and is deterministic for a given document.</p>
 */
public final class SdmXlsxWriter {

    private static final Logger LOG = Logger.getLogger(SdmXlsxWriter.class.getName());

    /** Fixed ZIP timestamp (2000-01-01 UTC) — the writer is deterministic. */
    private static final long ZIP_ENTRY_TIME = 946684800000L;

    private static final String NS_MAIN =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String NS_REL =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final ExcelSaveOptions options;
    /** The document being written (for resolving cell-image bytes). */
    private SdmDocument doc;

    /** Shared string pool: text &rarr; index. */
    private final Map<String, Integer> sharedStrings = new LinkedHashMap<>();
    /** Custom number-format code &rarr; numFmtId (&ge;164). */
    private final Map<String, Integer> numFmtIds = new LinkedHashMap<>();
    /** Font descriptor XML body &rarr; fontId (0 = default Calibri 11). */
    private final Map<String, Integer> fontIndex = new LinkedHashMap<>();
    private final List<String> fonts = new ArrayList<>(); // each: inner XML of a <font>
    /** Solid-fill RGB (0xRRGGBB) &rarr; fillId (0 none, 1 gray125 reserved). */
    private final Map<Integer, Integer> fillIndex = new LinkedHashMap<>();
    private final List<Integer> fillColors = new ArrayList<>(); // parallel to fillId order (from 2)
    /** Border descriptor key {@code t|r|b|l} (each 0 none,1 thin,2 medium) &rarr; borderId. */
    private final Map<String, Integer> borderIndex = new LinkedHashMap<>();
    private final List<int[]> borders = new ArrayList<>(); // each {top,right,bottom,left}
    /** Cell-format key &rarr; cellXfs index. */
    private final Map<String, Integer> xfIndex = new LinkedHashMap<>();
    private final List<int[]> xfs = new ArrayList<>(); // {numFmtId, fontId, fillId, halign, borderId}

    private int nextNumFmtId = 164;

    /**
     * Creates a writer with default options.
     */
    public SdmXlsxWriter() {
        this(new ExcelSaveOptions());
    }

    /**
     * Creates a writer with the given options.
     *
     * @param options the Excel save options; null = defaults
     */
    public SdmXlsxWriter(ExcelSaveOptions options) {
        this.options = options == null ? new ExcelSaveOptions() : options;
    }

    /**
     * Serializes the SDM document to {@code .xlsx} bytes.
     *
     * @param document the SDM document; must not be null
     * @return the {@code .xlsx} package
     * @throws IOException if the package cannot be assembled
     */
    public byte[] write(SdmDocument document) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
        write(document, buffer);
        return buffer.toByteArray();
    }

    /**
     * Serializes the SDM document to an {@code .xlsx} package on the given stream
     * (not closed).
     *
     * @param document the SDM document; must not be null
     * @param out      the destination stream
     * @throws IOException if writing fails
     */
    public void write(SdmDocument document, OutputStream out) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document must not be null");
        }
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        reset();
        this.doc = document;

        List<Table> tables = new ArrayList<>();
        collectTables(document.getChildren(), tables);

        List<Sheet> sheets = new ArrayList<>();
        if (tables.isEmpty()) {
            Sheet s = buildProseSheet(document);
            if (s != null) {
                sheets.add(s);
            }
        } else if (options.isMinimizeTheNumberOfWorksheets()) {
            sheets.add(buildCombinedSheet(tables));
        } else {
            int n = 1;
            for (Table t : tables) {
                sheets.add(buildTableSheet(t, defaultName(t, n++)));
            }
        }
        if (sheets.isEmpty()) {
            // Degenerate: emit one empty sheet so the package is valid.
            Sheet empty = new Sheet("Sheet1");
            sheets.add(empty);
        }
        uniquifyNames(sheets);

        // Assign a media file name to every cell image, per sheet, so the drawing
        // parts and [Content_Types] can be emitted deterministically.
        int mediaSeq = 0;
        List<String> mediaExts = new ArrayList<>();
        int[] drawingForSheet = new int[sheets.size()]; // 1-based drawing id, 0 = none
        java.util.Map<CellImage, String> imageMediaName = new java.util.HashMap<>();
        int drawingSeq = 0;
        for (int i = 0; i < sheets.size(); i++) {
            Sheet s = sheets.get(i);
            if (s.images.isEmpty()) {
                continue;
            }
            drawingForSheet[i] = ++drawingSeq;
            for (CellImage ci : s.images) {
                String name = "image" + (++mediaSeq) + "." + ci.ext;
                imageMediaName.put(ci, name);
                mediaExts.add(ci.ext);
            }
        }

        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(6);
        putEntry(zip, "[Content_Types].xml", buildContentTypes(sheets, drawingForSheet, mediaExts));
        putEntry(zip, "_rels/.rels", buildRootRels());
        putEntry(zip, "docProps/core.xml", buildCoreProps(document.getMetadata()));
        putEntry(zip, "xl/workbook.xml", buildWorkbook(sheets));
        putEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRels(sheets.size()));
        for (int i = 0; i < sheets.size(); i++) {
            putEntry(zip, "xl/worksheets/sheet" + (i + 1) + ".xml",
                    buildSheetXml(sheets.get(i), drawingForSheet[i]));
            if (drawingForSheet[i] > 0) {
                int dn = drawingForSheet[i];
                putEntry(zip, "xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels",
                        buildSheetRels(dn));
                putEntry(zip, "xl/drawings/drawing" + dn + ".xml",
                        buildDrawingXml(sheets.get(i)));
                putEntry(zip, "xl/drawings/_rels/drawing" + dn + ".xml.rels",
                        buildDrawingRels(sheets.get(i), imageMediaName));
                for (CellImage ci : sheets.get(i).images) {
                    putEntryBytes(zip, "xl/media/" + imageMediaName.get(ci), ci.bytes);
                }
            }
        }
        putEntry(zip, "xl/styles.xml", buildStyles());
        putEntry(zip, "xl/sharedStrings.xml", buildSharedStrings());
        zip.finish();
        LOG.fine(() -> "SdmXlsxWriter: emitted xlsx (" + sheets.size() + " sheet(s), "
                + sharedStrings.size() + " strings, " + xfs.size() + " cell styles)");
    }

    private void reset() {
        sharedStrings.clear();
        numFmtIds.clear();
        fontIndex.clear();
        fonts.clear();
        fillIndex.clear();
        fillColors.clear();
        borderIndex.clear();
        borders.clear();
        xfIndex.clear();
        xfs.clear();
        nextNumFmtId = 164;
        // font 0 = the default (Calibri 11).
        fonts.add("<sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/>");
        fontIndex.put("D", 0);
        // border 0 = none.
        borderFor(0, 0, 0, 0);
        // xf 0 must be the default cell format (no border).
        xfFor(0, 0, 0, ALIGN_NONE, 0);
    }

    // ------------------------------------------------------------------
    // Model: a laid-out sheet (a grid of placed cells)
    // ------------------------------------------------------------------

    /** A single laid-out cell anchored at (row, col). */
    private static final class Placed {
        final int row;
        final int col;
        final int rowSpan;
        final int colSpan;
        final String text;
        final boolean header;
        final TextStyle style;   // dominant run style of the source cell (may be null)
        final int fill;          // ARGB background from the cell block style (0 = none)
        final int align;         // ALIGN_* from the column spec
        Placed(int row, int col, int rowSpan, int colSpan, String text, boolean header,
                TextStyle style, int fill, int align) {
            this.row = row;
            this.col = col;
            this.rowSpan = rowSpan;
            this.colSpan = colSpan;
            this.text = text;
            this.header = header;
            this.style = style;
            this.fill = fill;
            this.align = align;
        }
    }

    /** An image anchored to a worksheet cell. */
    private static final class CellImage {
        final int row;
        final int col;
        final byte[] bytes;
        final String ext;   // png / jpeg
        final double wPt;
        final double hPt;
        CellImage(int row, int col, byte[] bytes, String ext, double wPt, double hPt) {
            this.row = row;
            this.col = col;
            this.bytes = bytes;
            this.ext = ext;
            this.wPt = wPt;
            this.hPt = hPt;
        }
    }

    /** A worksheet: its name, placed cells, extent, frozen header rows and dimensions. */
    private static final class Sheet {
        final String name;
        final List<Placed> cells = new ArrayList<>();
        final List<CellImage> images = new ArrayList<>();
        final Map<Integer, Double> rowHeightsPt = new java.util.HashMap<>();
        int rows;
        int cols;
        int freezeRows;
        double[] colWidthsPt; // may be null
        Sheet(String name) {
            this.name = name;
        }
    }

    private static final int ALIGN_NONE = 0;
    private static final int ALIGN_LEFT = 1;
    private static final int ALIGN_CENTER = 2;
    private static final int ALIGN_RIGHT = 3;

    // ------------------------------------------------------------------
    // Sheet construction
    // ------------------------------------------------------------------

    private Sheet buildTableSheet(Table table, String name) {
        Sheet sheet = new Sheet(name);
        layoutTable(table, sheet, 0);
        sheet.colWidthsPt = columnWidths(table, sheet.cols);
        return sheet;
    }

    private Sheet buildCombinedSheet(List<Table> tables) {
        Sheet sheet = new Sheet("Tables");
        int rowOffset = 0;
        for (Table t : tables) {
            int used = layoutTable(t, sheet, rowOffset);
            rowOffset += used + 1; // blank separator row between tables
        }
        // Freeze only makes sense for a single table on the sheet.
        sheet.freezeRows = 0;
        return sheet;
    }

    /**
     * Lays a table's cells into the sheet starting at {@code rowOffset}. Mirrors
     * the DOCX grid walk: column-span advances the cursor, row-span carries down.
     *
     * @return the number of rows the table occupied
     */
    private int layoutTable(Table table, Sheet sheet, int rowOffset) {
        int cols = columnCount(table);
        if (cols <= 0) {
            return 0;
        }
        List<TableRow> rows = table.getRows();
        int[] carryRows = new int[cols];
        int[] carrySpan = new int[cols];
        List<ColumnSpec> specs = table.getColumns();
        int leadingHeader = 0;
        boolean stillHeader = true;
        int r = 0;
        for (TableRow row : rows) {
            boolean rowIsHeader = row.getKind() == TableRow.Kind.HEADER;
            List<TableCell> cells = row.getCells();
            int c = 0;
            int ci = 0;
            boolean anyDataCell = false;
            boolean allTh = !cells.isEmpty();
            while (c < cols) {
                if (carryRows[c] > 0) {
                    int span = Math.max(1, carrySpan[c]);
                    carryRows[c]--;
                    c += span;
                } else if (ci < cells.size()) {
                    TableCell cell = cells.get(ci++);
                    int span = Math.max(1, Math.min(cell.getColSpan(), cols - c));
                    int rspan = Math.max(1, cell.getRowSpan());
                    boolean header = rowIsHeader || cell.getKind() == TableCell.Kind.TH;
                    if (cell.getKind() != TableCell.Kind.TH) {
                        allTh = false;
                    }
                    anyDataCell = true;
                    String text = cellText(cell);
                    // Record the typed value back on the SDM cell (model carries the type).
                    if (cell.getCellValue() == null) {
                        CellValueTyper.Typed typed = header
                                ? new CellValueTyper.Typed(CellValue.Kind.TEXT, text, 0, null, false)
                                : CellValueTyper.type(text);
                        cell.setCellValue(CellValueTyper.toCellValue(typed));
                    }
                    TextStyle style = dominantStyle(cell);
                    int fill = cell.getStyle() != null ? cell.getStyle().getBackground() : 0;
                    int align = columnAlign(specs, c);
                    sheet.cells.add(new Placed(rowOffset + r, c, rspan, span, text, header,
                            style, fill, align));
                    CellImage img = cellImage(cell, rowOffset + r, c);
                    if (img != null) {
                        sheet.images.add(img);
                    }
                    if (rspan > 1) {
                        carryRows[c] = rspan - 1;
                        carrySpan[c] = span;
                    }
                    recordRowHeight(sheet, rowOffset + r, cell);
                    c += span;
                } else {
                    c += 1; // ragged pad — leave blank
                }
            }
            if (stillHeader && (rowIsHeader || (allTh && anyDataCell))) {
                leadingHeader++;
            } else {
                stillHeader = false;
            }
            r++;
        }
        sheet.rows = Math.max(sheet.rows, rowOffset + r);
        sheet.cols = Math.max(sheet.cols, cols);
        if (rowOffset == 0) {
            sheet.freezeRows = leadingHeader;
        }
        return r;
    }

    /** The dominant run text style of a cell (first run carrying a style), or null. */
    private static TextStyle dominantStyle(TableCell cell) {
        for (SdmBlock child : cell.getChildren()) {
            TextStyle st = firstRunStyle(child);
            if (st != null) {
                return st;
            }
        }
        return null;
    }

    private static TextStyle firstRunStyle(SdmBlock block) {
        List<SdmInline> inline = block instanceof Paragraph ? ((Paragraph) block).getInline()
                : block instanceof Heading ? ((Heading) block).getInline() : null;
        if (inline != null) {
            for (SdmInline in : inline) {
                if (in instanceof Run && ((Run) in).getStyle() != null) {
                    return ((Run) in).getStyle();
                }
            }
        } else if (block instanceof Container) {
            for (SdmBlock c : ((Container) block).getChildren()) {
                TextStyle st = firstRunStyle(c);
                if (st != null) {
                    return st;
                }
            }
        }
        return null;
    }

    private static int columnAlign(List<ColumnSpec> specs, int col) {
        if (specs == null || col < 0 || col >= specs.size()) {
            return ALIGN_NONE;
        }
        switch (specs.get(col).getDefaultAlign()) {
            case RIGHT:  return ALIGN_RIGHT;
            case CENTER: return ALIGN_CENTER;
            default:     return ALIGN_LEFT;
        }
    }

    /** Records the max source cell height (from {@code cell-bounds}) as the row height. */
    private static void recordRowHeight(Sheet sheet, int row, TableCell cell) {
        Object b = cell.getAttributes().get("cell-bounds");
        if (b instanceof double[]) {
            double[] r = (double[]) b;
            if (r.length == 4) {
                double h = Math.abs(r[3] - r[1]);
                if (h > 0) {
                    Double cur = sheet.rowHeightsPt.get(row);
                    if (cur == null || h > cur) {
                        sheet.rowHeightsPt.put(row, h);
                    }
                }
            }
        }
    }

    private Sheet buildProseSheet(SdmDocument document) {
        Sheet sheet = new Sheet("Document");
        int r = 0;
        for (SdmBlock block : document.getChildren()) {
            String text = blockText(block).trim();
            if (!text.isEmpty()) {
                sheet.cells.add(new Placed(r++, 0, 1, 1, text, block instanceof Heading,
                        null, 0, ALIGN_NONE));
            }
        }
        if (sheet.cells.isEmpty()) {
            return null;
        }
        sheet.rows = r;
        sheet.cols = 1;
        return sheet;
    }

    // ------------------------------------------------------------------
    // XML: worksheet
    // ------------------------------------------------------------------

    private String buildSheetXml(Sheet sheet, int drawingId) {
        // Index placed cells by row for ordered emission.
        Map<Integer, List<Placed>> byRow = new LinkedHashMap<>();
        for (Placed p : sheet.cells) {
            byRow.computeIfAbsent(p.row, k -> new ArrayList<>()).add(p);
        }
        int rowCount = Math.max(sheet.rows, 1);
        int colCount = Math.max(sheet.cols, 1);

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<worksheet xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">");
        sb.append("<dimension ref=\"A1:").append(cellRef(rowCount - 1, colCount - 1)).append("\"/>");
        sb.append("<sheetViews><sheetView workbookViewId=\"0\">");
        if (sheet.freezeRows > 0) {
            sb.append("<pane ySplit=\"").append(sheet.freezeRows)
              .append("\" topLeftCell=\"A").append(sheet.freezeRows + 1)
              .append("\" activePane=\"bottomLeft\" state=\"frozen\"/>");
            sb.append("<selection pane=\"bottomLeft\" activeCell=\"A").append(sheet.freezeRows + 1)
              .append("\" sqref=\"A").append(sheet.freezeRows + 1).append("\"/>");
        }
        sb.append("</sheetView></sheetViews>");
        sb.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
        appendColumns(sb, sheet, colCount);

        sb.append("<sheetData>");
        for (int row = 0; row < rowCount; row++) {
            List<Placed> placed = byRow.get(row);
            Double ht = sheet.rowHeightsPt.get(row);
            sb.append("<row r=\"").append(row + 1).append("\"");
            if (ht != null && ht > 0) {
                sb.append(" ht=\"").append(round2(ht)).append("\" customHeight=\"1\"");
            }
            sb.append(">");
            // Emit every column position so grid borders are complete.
            boolean[] anchorAt = new boolean[colCount];
            Placed[] anchor = new Placed[colCount];
            if (placed != null) {
                for (Placed p : placed) {
                    if (p.col < colCount) {
                        anchorAt[p.col] = true;
                        anchor[p.col] = p;
                    }
                }
            }
            for (int col = 0; col < colCount; col++) {
                if (anchorAt[col]) {
                    appendCell(sb, row, col, anchor[col], rowCount, colCount);
                } else {
                    // Empty grid position — keep the grid + outer contour.
                    int bid = borderIdFor(row, col, 1, 1, rowCount, colCount);
                    sb.append("<c r=\"").append(cellRef(row, col)).append("\" s=\"")
                      .append(xfFor(0, 0, 0, ALIGN_NONE, bid)).append("\"/>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData>");

        appendMergeCells(sb, sheet);
        // The <drawing> reference must come after sheetData/mergeCells per schema.
        if (drawingId > 0) {
            sb.append("<drawing r:id=\"rId1\"/>");
        }
        sb.append("</worksheet>");
        return sb.toString();
    }

    private void appendColumns(StringBuilder sb, Sheet sheet, int colCount) {
        if (sheet.colWidthsPt == null) {
            return;
        }
        sb.append("<cols>");
        for (int i = 0; i < colCount && i < sheet.colWidthsPt.length; i++) {
            double pt = sheet.colWidthsPt[i];
            if (pt <= 0) {
                continue;
            }
            // Excel column width unit ~= (px - 5) / 7, px = pt * 96/72.
            double px = pt * 96.0 / 72.0;
            double width = Math.max(6.0, (px - 5.0) / 7.0 + 1.0);
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
              .append("\" width=\"").append(round2(width)).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols>");
    }

    private void appendCell(StringBuilder sb, int row, int col, Placed p, int rowCount, int colCount) {
        String ref = cellRef(row, col);
        CellValueTyper.Typed t = p.header
                ? new CellValueTyper.Typed(CellValue.Kind.TEXT, p.text, 0, null, false)
                : CellValueTyper.type(p.text);
        int fontId = fontFor(p.style, p.header);
        int fillId = fillFor(p.fill);
        int borderId = borderIdFor(row, col, p.rowSpan, p.colSpan, rowCount, colCount);
        if (t.kind == CellValue.Kind.TEXT) {
            String text = t.text;
            int style = xfFor(0, fontId, fillId, p.align, borderId);
            if (text == null || text.isEmpty()) {
                sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"/>");
                return;
            }
            int idx = internString(text);
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
              .append("\" t=\"s\"><v>").append(idx).append("</v></c>");
        } else if (t.kind == CellValue.Kind.BOOL) {
            int style = xfFor(0, fontId, fillId, p.align, borderId);
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
              .append("\" t=\"b\"><v>").append((long) t.number).append("</v></c>");
        } else {
            // NUMBER or DATE — a native numeric value with an optional number format.
            int style = xfFor(numFmtIdFor(t.numFmt), fontId, fillId, p.align, borderId);
            sb.append("<c r=\"").append(ref).append("\" s=\"").append(style)
              .append("\"><v>").append(numberText(t.number)).append("</v></c>");
        }
    }

    private void appendMergeCells(StringBuilder sb, Sheet sheet) {
        List<String> merges = new ArrayList<>();
        for (Placed p : sheet.cells) {
            if (p.rowSpan > 1 || p.colSpan > 1) {
                int r2 = p.row + p.rowSpan - 1;
                int c2 = p.col + p.colSpan - 1;
                merges.add(cellRef(p.row, p.col) + ":" + cellRef(r2, c2));
            }
        }
        if (merges.isEmpty()) {
            return;
        }
        sb.append("<mergeCells count=\"").append(merges.size()).append("\">");
        for (String m : merges) {
            sb.append("<mergeCell ref=\"").append(m).append("\"/>");
        }
        sb.append("</mergeCells>");
    }

    // ------------------------------------------------------------------
    // XML: workbook / rels / content types / core props
    // ------------------------------------------------------------------

    private String buildWorkbook(List<Sheet> sheets) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<workbook xmlns=\"").append(NS_MAIN).append("\" xmlns:r=\"").append(NS_REL).append("\">");
        sb.append("<sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(xmlAttr(sheets.get(i).name))
              .append("\" sheetId=\"").append(i + 1)
              .append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        sb.append("</sheets></workbook>");
        return sb.toString();
    }

    private String buildWorkbookRels(int sheetCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheetCount; i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1)
              .append("\" Type=\"").append(NS_REL).append("/worksheet\" Target=\"worksheets/sheet")
              .append(i + 1).append(".xml\"/>");
        }
        sb.append("<Relationship Id=\"rId").append(sheetCount + 1)
          .append("\" Type=\"").append(NS_REL).append("/styles\" Target=\"styles.xml\"/>");
        sb.append("<Relationship Id=\"rId").append(sheetCount + 2)
          .append("\" Type=\"").append(NS_REL).append("/sharedStrings\" Target=\"sharedStrings.xml\"/>");
        sb.append("</Relationships>");
        return sb.toString();
    }

    private static final long EMU_PER_PT = 12700L;
    private static final String NS_XDR =
            "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing";
    private static final String NS_A =
            "http://schemas.openxmlformats.org/drawingml/2006/main";

    /** Worksheet rels part linking the sheet to its drawing part. */
    private String buildSheetRels(int drawingId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/drawing\" Target=\"../drawings/drawing"
                + drawingId + ".xml\"/></Relationships>";
    }

    /** Drawing part: one oneCellAnchor image per cell image, at native footprint. */
    private String buildDrawingXml(Sheet sheet) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<xdr:wsDr xmlns:xdr=\"").append(NS_XDR).append("\" xmlns:a=\"").append(NS_A)
          .append("\" xmlns:r=\"").append(NS_REL).append("\">");
        int k = 0;
        for (CellImage ci : sheet.images) {
            k++;
            long cx = Math.round(ci.wPt * EMU_PER_PT);
            long cy = Math.round(ci.hPt * EMU_PER_PT);
            sb.append("<xdr:oneCellAnchor>")
              .append("<xdr:from><xdr:col>").append(ci.col).append("</xdr:col>")
              .append("<xdr:colOff>19050</xdr:colOff><xdr:row>").append(ci.row).append("</xdr:row>")
              .append("<xdr:rowOff>19050</xdr:rowOff></xdr:from>")
              .append("<xdr:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
              .append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"").append(k + 1)
              .append("\" name=\"Image ").append(k).append("\"/><xdr:cNvPicPr/></xdr:nvPicPr>")
              .append("<xdr:blipFill><a:blip r:embed=\"rId").append(k)
              .append("\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
              .append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx)
              .append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
              .append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>")
              .append("<xdr:clientData/></xdr:oneCellAnchor>");
        }
        sb.append("</xdr:wsDr>");
        return sb.toString();
    }

    /** Drawing rels part linking each anchored image to its media file. */
    private String buildDrawingRels(Sheet sheet, java.util.Map<CellImage, String> mediaName) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        int k = 0;
        for (CellImage ci : sheet.images) {
            k++;
            sb.append("<Relationship Id=\"rId").append(k).append("\" Type=\"").append(NS_REL)
              .append("/image\" Target=\"../media/").append(mediaName.get(ci)).append("\"/>");
        }
        sb.append("</Relationships>");
        return sb.toString();
    }

    private String buildContentTypes(List<Sheet> sheets, int[] drawingForSheet, List<String> mediaExts) {
        int sheetCount = sheets.size();
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        sb.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        sb.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        if (mediaExts.contains("png")) {
            sb.append("<Default Extension=\"png\" ContentType=\"image/png\"/>");
        }
        if (mediaExts.contains("jpeg")) {
            sb.append("<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>");
        }
        sb.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        for (int i = 0; i < sheetCount; i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            if (drawingForSheet[i] > 0) {
                sb.append("<Override PartName=\"/xl/drawings/drawing").append(drawingForSheet[i])
                  .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>");
            }
        }
        sb.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        sb.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        sb.append("<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
        sb.append("</Types>");
        return sb.toString();
    }

    private String buildRootRels() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + NS_REL + "/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                + "</Relationships>";
    }

    private String buildCoreProps(SdmMetadata meta) {
        String title = meta == null || meta.getTitle() == null ? "" : meta.getTitle();
        String author = meta == null || meta.getAuthor() == null ? "" : meta.getAuthor();
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                + "<dc:title>" + xml(title) + "</dc:title>"
                + "<dc:creator>" + xml(author) + "</dc:creator>"
                + "</cp:coreProperties>";
    }

    // ------------------------------------------------------------------
    // XML: styles / shared strings
    // ------------------------------------------------------------------

    private String buildStyles() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<styleSheet xmlns=\"").append(NS_MAIN).append("\">");
        // Custom number formats.
        if (!numFmtIds.isEmpty()) {
            sb.append("<numFmts count=\"").append(numFmtIds.size()).append("\">");
            for (Map.Entry<String, Integer> e : numFmtIds.entrySet()) {
                sb.append("<numFmt numFmtId=\"").append(e.getValue())
                  .append("\" formatCode=\"").append(xmlAttr(e.getKey())).append("\"/>");
            }
            sb.append("</numFmts>");
        }
        // Fonts (font 0 = default; the rest registered on demand).
        sb.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (String f : fonts) {
            sb.append("<font>").append(f).append("</font>");
        }
        sb.append("</fonts>");
        // Fills: 0 none, 1 gray125 (reserved by the schema), then registered solids.
        sb.append("<fills count=\"").append(2 + fillColors.size()).append("\">")
          .append("<fill><patternFill patternType=\"none\"/></fill>")
          .append("<fill><patternFill patternType=\"gray125\"/></fill>");
        for (int rgb : fillColors) {
            sb.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF")
              .append(String.format("%06X", rgb)).append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }
        sb.append("</fills>");
        // Borders (registered on demand; each side thin/medium/none, explicit black).
        sb.append("<borders count=\"").append(borders.size()).append("\">");
        for (int[] b : borders) {
            sb.append("<border>")
              .append(borderSide("left", b[3]))
              .append(borderSide("right", b[1]))
              .append(borderSide("top", b[0]))
              .append(borderSide("bottom", b[2]))
              .append("<diagonal/></border>");
        }
        sb.append("</borders>");
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>");
        sb.append("<cellXfs count=\"").append(xfs.size()).append("\">");
        for (int[] xf : xfs) {
            int numFmtId = xf[0];
            int fontId = xf[1];
            int fillId = xf[2];
            int align = xf[3];
            int borderId = xf[4];
            sb.append("<xf numFmtId=\"").append(numFmtId)
              .append("\" fontId=\"").append(fontId)
              .append("\" fillId=\"").append(fillId)
              .append("\" borderId=\"").append(borderId).append("\" xfId=\"0\"")
              .append(" applyBorder=\"1\"");
            if (fontId != 0) {
                sb.append(" applyFont=\"1\"");
            }
            if (fillId != 0) {
                sb.append(" applyFill=\"1\"");
            }
            if (numFmtId != 0) {
                sb.append(" applyNumberFormat=\"1\"");
            }
            sb.append(" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"");
            String h = horizName(align);
            if (h != null) {
                sb.append(" horizontal=\"").append(h).append('"');
            }
            sb.append("/></xf>");
        }
        sb.append("</cellXfs>");
        sb.append("<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>");
        sb.append("</styleSheet>");
        return sb.toString();
    }

    private String buildSharedStrings() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<sst xmlns=\"").append(NS_MAIN).append("\" count=\"").append(sharedStrings.size())
          .append("\" uniqueCount=\"").append(sharedStrings.size()).append("\">");
        for (String s : sharedStrings.keySet()) {
            sb.append("<si><t xml:space=\"preserve\">").append(xml(s)).append("</t></si>");
        }
        sb.append("</sst>");
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Style / string registries
    // ------------------------------------------------------------------

    private int internString(String s) {
        return sharedStrings.computeIfAbsent(s, k -> sharedStrings.size());
    }

    private int numFmtIdFor(String code) {
        if (code == null) {
            return 0; // General
        }
        Integer id = numFmtIds.get(code);
        if (id == null) {
            id = nextNumFmtId++;
            numFmtIds.put(code, id);
        }
        return id;
    }

    /**
     * Registers (or reuses) a font for the given source run style. A header cell
     * with no explicit style still renders bold. Maps font family, size,
     * bold/italic and a non-black colour onto an Excel font.
     */
    private int fontFor(TextStyle st, boolean header) {
        String name = "Calibri";
        int sizeHalf = 22; // 11pt
        boolean bold = header;
        boolean italic = false;
        int rgb = -1; // -1 = automatic (black)
        if (st != null) {
            if (st.getFontFamily() != null && !st.getFontFamily().isEmpty()) {
                name = normalizeFontName(st.getFontFamily());
            }
            if (st.getFontSize() > 0) {
                sizeHalf = (int) Math.round(st.getFontSize() * 2);
            }
            bold = bold || st.isBold();
            italic = st.isItalic();
            int c = st.getColor() & 0xFFFFFF;
            if (st.getColor() != 0 && !(c == 0x000000)) {
                rgb = c;
            }
        }
        if (!header && st == null) {
            return 0; // the default font
        }
        StringBuilder body = new StringBuilder();
        if (bold) {
            body.append("<b/>");
        }
        if (italic) {
            body.append("<i/>");
        }
        body.append("<sz val=\"").append(round2(sizeHalf / 2.0)).append("\"/>");
        if (rgb >= 0) {
            body.append("<color rgb=\"FF").append(String.format("%06X", rgb)).append("\"/>");
        }
        body.append("<name val=\"").append(xmlAttr(name)).append("\"/><family val=\"2\"/>");
        String key = body.toString();
        Integer idx = fontIndex.get(key);
        if (idx == null) {
            idx = fonts.size();
            fonts.add(key);
            fontIndex.put(key, idx);
        }
        return idx;
    }

    /** Registers (or reuses) a solid fill for an ARGB background (0 = none). */
    private int fillFor(int argb) {
        int rgb = argb & 0xFFFFFF;
        if (argb == 0) {
            return 0; // no fill
        }
        Integer idx = fillIndex.get(rgb);
        if (idx == null) {
            idx = 2 + fillColors.size(); // 0 none, 1 gray125 reserved
            fillColors.add(rgb);
            fillIndex.put(rgb, idx);
        }
        return idx;
    }

    private int xfFor(int numFmtId, int fontId, int fillId, int align, int borderId) {
        String key = numFmtId + "|" + fontId + "|" + fillId + "|" + align + "|" + borderId;
        Integer idx = xfIndex.get(key);
        if (idx == null) {
            idx = xfs.size();
            xfs.add(new int[]{numFmtId, fontId, fillId, align, borderId});
            xfIndex.put(key, idx);
        }
        return idx;
    }

    /** Registers (or reuses) a border with the given side weights (0 none,1 thin,2 medium). */
    private int borderFor(int top, int right, int bottom, int left) {
        String key = top + "|" + right + "|" + bottom + "|" + left;
        Integer idx = borderIndex.get(key);
        if (idx == null) {
            idx = borders.size();
            borders.add(new int[]{top, right, bottom, left});
            borderIndex.put(key, idx);
        }
        return idx;
    }

    /**
     * Border id for a cell at (row,col) spanning (rowSpan,colSpan) in a
     * rowCount&times;colCount table: thin interior rules, a medium outer contour on
     * the four table edges.
     */
    private int borderIdFor(int row, int col, int rowSpan, int colSpan, int rowCount, int colCount) {
        int top = row == 0 ? 2 : 1;
        int left = col == 0 ? 2 : 1;
        int bottom = (row + Math.max(1, rowSpan) >= rowCount) ? 2 : 1;
        int right = (col + Math.max(1, colSpan) >= colCount) ? 2 : 1;
        return borderFor(top, right, bottom, left);
    }

    /** Strips a subset tag (e.g. {@code ABCDEF+Helvetica}) and PostScript style suffixes. */
    private static String normalizeFontName(String raw) {
        String n = raw;
        int plus = n.indexOf('+');
        if (plus == 6) {
            n = n.substring(plus + 1);
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Table helpers
    // ------------------------------------------------------------------

    private static void collectTables(List<SdmBlock> blocks, List<Table> out) {
        if (blocks == null) {
            return;
        }
        for (SdmBlock b : blocks) {
            if (b instanceof Table) {
                out.add((Table) b);
            } else if (b instanceof Container) {
                collectTables(((Container) b).getChildren(), out);
            } else if (b instanceof Quote) {
                collectTables(((Quote) b).getChildren(), out);
            }
        }
    }

    private static int columnCount(Table table) {
        int cols = table.getColumns() == null ? 0 : table.getColumns().size();
        for (TableRow row : table.getRows()) {
            int sum = 0;
            for (TableCell cell : row.getCells()) {
                sum += Math.max(1, cell.getColSpan());
            }
            cols = Math.max(cols, sum);
        }
        return cols;
    }

    private static double[] columnWidths(Table table, int cols) {
        List<ColumnSpec> specs = table.getColumns();
        if (specs == null || specs.isEmpty()) {
            return null;
        }
        double[] w = new double[cols];
        boolean any = false;
        for (int i = 0; i < cols; i++) {
            if (i < specs.size() && specs.get(i).getWidthType() == ColumnSpec.WidthType.POINTS
                    && specs.get(i).getWidth() > 0) {
                w[i] = specs.get(i).getWidth();
                any = true;
            }
        }
        return any ? w : null;
    }

    /** Extracts the first embeddable image in a cell (Figure or InlineImage), or null. */
    private CellImage cellImage(TableCell cell, int row, int col) {
        for (SdmBlock child : cell.getChildren()) {
            ResourceRef ref = null;
            double wPt = 0;
            double hPt = 0;
            if (child instanceof Figure) {
                Figure f = (Figure) child;
                ref = f.getImage();
                wPt = numAttr(f, "display-width");
                hPt = numAttr(f, "display-height");
            } else if (child instanceof Container) {
                CellImage nested = cellImageIn(((Container) child).getChildren(), row, col);
                if (nested != null) {
                    return nested;
                }
            }
            CellImage ci = toCellImage(ref, wPt, hPt, row, col);
            if (ci != null) {
                return ci;
            }
        }
        return null;
    }

    private CellImage cellImageIn(List<SdmBlock> blocks, int row, int col) {
        for (SdmBlock b : blocks) {
            if (b instanceof Figure) {
                Figure f = (Figure) b;
                CellImage ci = toCellImage(f.getImage(), numAttr(f, "display-width"),
                        numAttr(f, "display-height"), row, col);
                if (ci != null) {
                    return ci;
                }
            }
        }
        return null;
    }

    private CellImage toCellImage(ResourceRef ref, double wPt, double hPt, int row, int col) {
        if (ref == null || doc == null) {
            return null;
        }
        Resource res = doc.getResources().get(ref);
        if (res == null || res.getBytes() == null || res.getBytes().length == 0) {
            return null;
        }
        String mime = res.getMime() == null ? "" : res.getMime();
        String ext = mime.contains("jpeg") || mime.contains("jpg") ? "jpeg" : "png";
        // Fall back to intrinsic pixel size (@96dpi → pt) when the footprint is unknown.
        if (wPt <= 0 || hPt <= 0) {
            try {
                java.awt.image.BufferedImage bi = javax.imageio.ImageIO.read(
                        new java.io.ByteArrayInputStream(res.getBytes()));
                if (bi != null && bi.getWidth() > 0) {
                    wPt = bi.getWidth() * 72.0 / 96.0;
                    hPt = bi.getHeight() * 72.0 / 96.0;
                }
            } catch (IOException | RuntimeException e) {
                wPt = 48;
                hPt = 48;
            }
        }
        return new CellImage(row, col, res.getBytes(), ext, Math.max(4, wPt), Math.max(4, hPt));
    }

    private static double numAttr(Figure f, String key) {
        Object v = f.getAttributes().get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    /** Concatenates the visible text of a table cell's block children. */
    private static String cellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (SdmBlock child : cell.getChildren()) {
            String t = blockText(child);
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        return sb.toString().trim();
    }

    /** Recursively concatenates the run text under a block. */
    private static String blockText(SdmBlock block) {
        StringBuilder sb = new StringBuilder();
        List<SdmInline> inline = block instanceof Paragraph ? ((Paragraph) block).getInline()
                : block instanceof Heading ? ((Heading) block).getInline() : null;
        if (inline != null) {
            for (SdmInline in : inline) {
                if (in instanceof Run && ((Run) in).getText() != null) {
                    sb.append(((Run) in).getText());
                }
            }
        } else if (block instanceof Container) {
            for (SdmBlock c : ((Container) block).getChildren()) {
                String t = blockText(c);
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
            }
        } else if (block instanceof Quote) {
            for (SdmBlock c : ((Quote) block).getChildren()) {
                sb.append(blockText(c));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Sheet naming
    // ------------------------------------------------------------------

    private static String defaultName(Table table, int index) {
        String caption = table.getCaption();
        if (caption != null && !caption.trim().isEmpty()) {
            return sanitizeSheetName(caption.trim());
        }
        return "Table " + index;
    }

    /** Excel sheet names: &le;31 chars, none of {@code []:*?/\}, non-blank. */
    private static String sanitizeSheetName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length() && sb.length() < 31; i++) {
            char c = name.charAt(i);
            if (c == '[' || c == ']' || c == ':' || c == '*' || c == '?' || c == '/' || c == '\\') {
                c = ' ';
            }
            sb.append(c);
        }
        String s = sb.toString().trim();
        if (s.startsWith("'")) {
            s = s.substring(1);
        }
        if (s.endsWith("'")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isEmpty() ? "Sheet" : s;
    }

    private static void uniquifyNames(List<Sheet> sheets) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < sheets.size(); i++) {
            String base = sheets.get(i).name;
            String name = base;
            int n = 2;
            while (!seen.add(name.toLowerCase(java.util.Locale.ROOT))) {
                String suffix = " (" + (n++) + ")";
                String trimmed = base.length() + suffix.length() > 31
                        ? base.substring(0, 31 - suffix.length()) : base;
                name = trimmed + suffix;
            }
            if (!name.equals(base)) {
                // Replace with a renamed sheet (Sheet.name is final).
                Sheet renamed = new Sheet(name);
                renamed.cells.addAll(sheets.get(i).cells);
                renamed.rows = sheets.get(i).rows;
                renamed.cols = sheets.get(i).cols;
                renamed.freezeRows = sheets.get(i).freezeRows;
                renamed.colWidthsPt = sheets.get(i).colWidthsPt;
                sheets.set(i, renamed);
            }
        }
    }

    // ------------------------------------------------------------------
    // Low-level helpers
    // ------------------------------------------------------------------

    /** A1-style reference for a 0-based (row, col). */
    static String cellRef(int row, int col) {
        return colName(col) + (row + 1);
    }

    /** Column letters for a 0-based column index (0 -> A, 26 -> AA). */
    static String colName(int col) {
        StringBuilder sb = new StringBuilder();
        int c = col;
        while (c >= 0) {
            sb.insert(0, (char) ('A' + c % 26));
            c = c / 26 - 1;
        }
        return sb.toString();
    }

    private static String numberText(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return trimZeros(String.format(java.util.Locale.ROOT, "%.10f", v));
    }

    private static String trimZeros(String s) {
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

    private static String round2(double v) {
        return trimZeros(String.format(java.util.Locale.ROOT, "%.2f", v));
    }

    /** A border-side element: none (empty), thin or medium, in explicit black. */
    private static String borderSide(String side, int weight) {
        if (weight <= 0) {
            return "<" + side + "/>";
        }
        String style = weight >= 2 ? "medium" : "thin";
        return "<" + side + " style=\"" + style + "\"><color rgb=\"FF000000\"/></" + side + ">";
    }

    private static String horizName(int align) {
        switch (align) {
            case ALIGN_LEFT:   return "left";
            case ALIGN_CENTER: return "center";
            case ALIGN_RIGHT:  return "right";
            default:           return null;
        }
    }

    private static void putEntry(ZipOutputStream zip, String name, String content) throws IOException {
        putEntryBytes(zip, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void putEntryBytes(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(ZIP_ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    /** Escapes XML text content ({@code & < >}); illegal control chars become spaces. */
    static String xml(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String rep = null;
            if (c == '&') {
                rep = "&amp;";
            } else if (c == '<') {
                rep = "&lt;";
            } else if (c == '>') {
                rep = "&gt;";
            } else if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r') || c == 0xFFFE || c == 0xFFFF) {
                rep = " ";
            }
            if (rep != null) {
                if (sb == null) {
                    sb = new StringBuilder(s.length() + 16).append(s, 0, i);
                }
                sb.append(rep);
            } else if (sb != null) {
                sb.append(c);
            }
        }
        return sb == null ? s : sb.toString();
    }

    /** Escapes an attribute value: text escaping plus {@code "}. */
    static String xmlAttr(String s) {
        return xml(s).replace("\"", "&quot;");
    }
}
