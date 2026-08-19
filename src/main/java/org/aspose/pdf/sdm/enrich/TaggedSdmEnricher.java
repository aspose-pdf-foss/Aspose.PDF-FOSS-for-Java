package org.aspose.pdf.sdm.enrich;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.aspose.pdf.Document;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfArray;
import org.aspose.pdf.engine.pdfobjects.PdfBase;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.engine.pdfobjects.PdfObjectKey;
import org.aspose.pdf.engine.pdfobjects.PdfObjectReference;
import org.aspose.pdf.logicalstructure.MarkedContentReference;
import org.aspose.pdf.logicalstructure.RoleMap;
import org.aspose.pdf.logicalstructure.StructTreeRoot;
import org.aspose.pdf.logicalstructure.StructureElement;
import org.aspose.pdf.pgm.PgmModel;
import org.aspose.pdf.sdm.CodeBlock;
import org.aspose.pdf.sdm.Container;
import org.aspose.pdf.sdm.ContentRange;
import org.aspose.pdf.sdm.Figure;
import org.aspose.pdf.sdm.Footnote;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.ListItem;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Quote;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.TocBlock;
import org.aspose.pdf.sdm.TocEntry;

/**
 * Tagged-PDF structural enricher — IR Stage 3 PART 2.
 *
 * <p>Walks the document's logical structure tree ({@code /StructTreeRoot},
 * ISO 32000-1:2008 §14.7) and upgrades the shallow Stage-1 SDM: paragraphs whose
 * content is claimed by {@code H1..H6} elements are retyped to {@link Heading}s,
 * {@code L/LI(+Lbl/LBody)} regroup into {@link ListBlock}s, {@code Table/TR/TD/TH}
 * (with {@code THead/TBody/TFoot} sections and {@code RowSpan/ColSpan} attributes)
 * into {@link Table}s, {@code Figure} elements adopt the shallow image figures,
 * {@code BlockQuote}&rarr;{@link Quote}, {@code Code}&rarr;{@link CodeBlock},
 * {@code TOC/TOCI}&rarr;{@link TocBlock}, {@code Note}&rarr;{@link Footnote},
 * {@code Sect/Div/Part/Art}&rarr;{@link Container}.</p>
 *
 * <p><b>Matching.</b> Structure elements point at content through marked-content
 * references (MCID + page, §14.7.4.2). The enricher scans each page's operator
 * list for {@code BDC /tag <</MCID n>> .. EMC} spans — the SAME operator indexing
 * the Stage-1 reader recorded into every node's {@link ContentRange} — and a
 * shallow block belongs to the element whose MCID span overlaps its range.</p>
 *
 * <p><b>Invariants.</b> Enrichment retypes and regroups; it never re-creates
 * content nodes: a retyped node keeps its GUID, sourceRef, style and inline list.
 * Structure order wins over visual order (the tags are the author's intent).
 * Content not claimed by any structure element (mixed/partially-tagged documents)
 * falls through unchanged, interleaved next to its original neighbours.</p>
 *
 * <p><b>Known v1 limits (logged, not silent):</b> BDC properties given as a named
 * resource (/Properties indirection) are not resolved — only inline
 * {@code <</MCID n>>} dictionaries; a shallow paragraph that overlaps the spans of
 * TWO different block elements is claimed by the first in structure order
 * (paragraph-level granularity); TOCI link targets are not resolved.</p>
 */
public final class TaggedSdmEnricher {

    private static final Logger LOG = Logger.getLogger(TaggedSdmEnricher.class.getName());

    /** Structure types treated as block-level (their own walk step consumes content). */
    private static final Set<String> BLOCK_TYPES = new HashSet<>(java.util.Arrays.asList(
            "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI",
            "Index", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6",
            "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
            "Figure", "Formula", "Form", "Note", "Code"));

    /** All standard structure type names (§14.8.4) — names outside go through /RoleMap. */
    private static final Set<String> STANDARD_TYPES = new HashSet<>(java.util.Arrays.asList(
            "Document", "Part", "Art", "Sect", "Div", "BlockQuote", "Caption", "TOC", "TOCI",
            "Index", "NonStruct", "Private", "P", "H", "H1", "H2", "H3", "H4", "H5", "H6",
            "L", "LI", "Lbl", "LBody", "Table", "TR", "TH", "TD", "THead", "TBody", "TFoot",
            "Span", "Quote", "Note", "Reference", "BibEntry", "Code", "Link", "Annot",
            "Figure", "Formula", "Form", "Ruby", "RB", "RT", "RP", "Warichu", "WT", "WP"));

    // ------------------------------------------------------------------ state

    /** pageObjNum -> (mcid -> list of [opStart, opEnd] spans). */
    private final Map<Integer, Map<Integer, List<int[]>>> mcidSpans = new HashMap<>();
    /** pageObjNum -> shallow blocks on that page ordered by opStart. */
    private final Map<Integer, List<BlockRange>> pageBlocks = new HashMap<>();
    /** paragraph -> per-Run operator ranges (from its PGM boxes, by part index). */
    private final Map<SdmBlock, List<RunRange>> runIndex = new IdentityHashMap<>();
    private java.util.UUID nsDoc;
    /** per-page-index extractor-parity avg char width (SpacingRule.pageAvgCharWidth). */
    private double[] pageAcw = new double[0];
    /** page dictionary identity -> page object number. */
    private final Map<PdfDictionary, Integer> pageDictToObjNum = new IdentityHashMap<>();
    /** original top-level index of every shallow block. */
    private final Map<SdmBlock, Integer> origIndex = new IdentityHashMap<>();
    /** blocks already claimed by a structure element. */
    private final Set<SdmBlock> consumed = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    /** finished top-level output units, in structure order. */
    private final List<Unit> units = new ArrayList<>();
    private RoleMap roleMap;

    /** One Run of a shallow paragraph plus its operator range and geometry. */
    private static final class RunRange {
        final Run run;
        final int opStart;
        final int opEnd;
        final double x;
        final double endX;
        final double width;
        final double baseline;
        final int pageIdx;

        RunRange(Run run, int opStart, int opEnd, double x, double width, double baseline,
                 int pageIdx) {
            this.run = run;
            this.opStart = opStart;
            this.opEnd = opEnd;
            this.x = x;
            this.endX = x + width;
            this.width = width;
            this.baseline = baseline;
            this.pageIdx = pageIdx;
        }
    }

    /** A shallow block plus its page-stream operator range. */
    private static final class BlockRange {
        final SdmBlock block;
        final int opStart;
        final int opEnd;

        BlockRange(SdmBlock block, int opStart, int opEnd) {
            this.block = block;
            this.opStart = opStart;
            this.opEnd = opEnd;
        }
    }

    /** One top-level result block and the original indices it consumed. */
    private static final class Unit {
        final SdmBlock out;
        int maxOrig = -1;

        Unit(SdmBlock out) {
            this.out = out;
        }
    }

    /**
     * Returns true when the document carries a non-empty logical structure tree
     * (checked read-only — never creates {@code /StructTreeRoot}).
     *
     * @param doc the open document
     * @return true if a structure tree with at least one element exists
     */
    public static boolean isTagged(Document doc) {
        try {
            PdfDictionary catalog = doc.getCatalog();
            if (catalog == null) {
                return false;
            }
            PdfBase str = resolve(catalog.get("StructTreeRoot"));
            if (!(str instanceof PdfDictionary)) {
                return false;
            }
            return new StructTreeRoot((PdfDictionary) str, null).getRootElement() != null;
        } catch (IOException | RuntimeException e) {
            LOG.fine(() -> "isTagged check failed: " + e);
            return false;
        }
    }

    /**
     * Enriches the shallow SDM projection from the document's structure tree.
     *
     * <p>Mutates {@code sdm.getChildren()} in place. No-op (returns false) when
     * the document has no structure tree.</p>
     *
     * @param doc the open document the projection came from
     * @param sdm the shallow SDM projection (from {@code PdfSdmReader}); mutated
     * @param pgm the PGM side of the projection (box geometry; reserved for
     *            geometry-assisted matching — the v1 match is purely operator-range
     *            based, which is exact for page-stream content)
     * @return true when a structure tree was found and applied
     * @throws IOException if page content cannot be read
     */
    public boolean enrich(Document doc, SdmDocument sdm, PgmModel pgm) throws IOException {
        if (doc == null || sdm == null) {
            return false;
        }
        if (!isTagged(doc)) {
            return false;
        }
        PdfBase str = resolve(doc.getCatalog().get("StructTreeRoot"));
        StructTreeRoot root = new StructTreeRoot((PdfDictionary) str, null);
        this.roleMap = root.getRoleMap();

        this.nsDoc = sdm.getNsDoc();
        this.pageAcw = pgm != null ? SpacingRule.pageAvgCharWidth(pgm) : new double[0];
        indexPages(doc);
        indexBlocks(sdm, pgm);

        // Walk every top-level element under the StructTreeRoot in structure order.
        List<SdmBlock> head = new ArrayList<>();
        for (int i = 0; i < root.getChildren().getCount(); i++) {
            walkElement(root.getChildren().get(i), defaultPage(root.getChildren().get(i)), 0);
        }

        // Untagged fall-through: every unclaimed block keeps its place relative to
        // the nearest preceding claimed neighbour.
        List<SdmBlock> originals = new ArrayList<>(sdm.getChildren());
        Map<Unit, List<SdmBlock>> trail = new IdentityHashMap<>();
        for (SdmBlock b : originals) {
            if (consumed.contains(b)) {
                continue;
            }
            int k = origIndex.getOrDefault(b, -1);
            Unit best = null;
            for (Unit u : units) {
                if (u.maxOrig >= 0 && u.maxOrig <= k && (best == null || u.maxOrig > best.maxOrig)) {
                    best = u;
                }
            }
            if (best == null) {
                head.add(b);
            } else {
                trail.computeIfAbsent(best, u -> new ArrayList<>()).add(b);
            }
        }

        List<SdmBlock> result = new ArrayList<>(head);
        for (Unit u : units) {
            result.add(u.out);
            List<SdmBlock> t = trail.get(u);
            if (t != null) {
                result.addAll(t);
            }
        }
        sdm.getChildren().clear();
        sdm.getChildren().addAll(result);

        // Residual paragraphs that LOST runs to partial claims: their run↔box
        // alignment is gone, so the pipeline normalizer will skip them — restore
        // extractor-parity spacing here from the remaining tracked geometry.
        for (Map.Entry<SdmBlock, List<RunRange>> e : runIndex.entrySet()) {
            List<RunRange> remaining = e.getValue();
            if (!(e.getKey() instanceof Paragraph) || remaining.size() < 2) {
                continue;
            }
            Paragraph p = (Paragraph) e.getKey();
            if (remaining.size() != p.getInline().size()) {
                continue; // untouched (normalizer will handle) or inconsistent
            }
            double acw = acwOf(remaining.get(0).pageIdx);
            for (int i = remaining.size() - 1; i >= 1; i--) {
                RunRange prev = remaining.get(i - 1);
                RunRange cur = remaining.get(i);
                if (SpacingRule.shouldSpace(prev.run.getText(), prev.endX, prev.baseline,
                        cur.run.getText(), cur.x, cur.baseline, acw)) {
                    int pos = p.getInline().indexOf(cur.run);
                    if (pos > 0) {
                        p.getInline().add(pos, new Run(" ", null));
                    }
                }
            }
        }

        // The author's language wins for <html lang> when the shallow pass had none.
        try {
            PdfBase lang = doc.getCatalog().get("Lang");
            if (sdm.getMetadata().getLang() == null
                    && lang instanceof org.aspose.pdf.engine.pdfobjects.PdfString) {
                sdm.getMetadata().setLang(
                        ((org.aspose.pdf.engine.pdfobjects.PdfString) lang).getString());
            }
        } catch (RuntimeException e) {
            LOG.fine(() -> "catalog /Lang not readable: " + e);
        }
        LOG.fine(() -> "tagged enrichment: " + units.size() + " structure units, "
                + consumed.size() + " of " + originals.size() + " shallow blocks claimed");
        return true;
    }

    // ------------------------------------------------------------------ indexing

    private void indexPages(Document doc) throws IOException {
        int count = doc.getPages().getCount();
        for (int i = 1; i <= count; i++) {
            Page page = doc.getPages().get(i);
            PdfDictionary dict = page.getPdfDictionary();
            int objNum = pageObjNum(page, i - 1);
            if (dict != null) {
                pageDictToObjNum.put(dict, objNum);
            }
            scanMcids(page, objNum);
        }
    }

    /** Mirrors PdfSdmReader.resolvePageObjNum — MUST stay consistent with reader ids. */
    private static int pageObjNum(Page page, int pageIndex) {
        PdfDictionary dict = page.getPdfDictionary();
        PdfObjectKey key = dict != null ? dict.getObjectKey() : null;
        if (key != null && key.getObjectNumber() > 0) {
            return key.getObjectNumber();
        }
        return 800000 + pageIndex + 1;
    }

    /**
     * Scans one page's operator list for {@code BDC <</MCID n>> .. EMC} spans.
     * Operator indices are the SAME page-level indices the Stage-1 reader wrote
     * into ContentRange locators — that identity is what makes matching exact.
     */
    private void scanMcids(Page page, int pageObjNum) throws IOException {
        OperatorCollection ops = page.getContents();
        java.util.Deque<int[]> stack = new java.util.ArrayDeque<>(); // {mcid, startIdx}
        Map<Integer, List<int[]>> spans = new HashMap<>();
        for (int i = 0; i < ops.size(); i++) {
            Operator op = ops.getAt(i);
            String name = op.getName();
            if ("BDC".equals(name)) {
                int mcid = -1;
                List<PdfBase> od = op.getOperands();
                if (od.size() >= 2) {
                    PdfBase props = resolve(od.get(1));
                    if (props instanceof PdfDictionary) {
                        mcid = ((PdfDictionary) props).getInt("MCID", -1);
                    } else if (props != null) {
                        LOG.fine(() -> "BDC with named /Properties resource skipped (v1 limit)");
                    }
                }
                stack.push(new int[]{mcid, i});
            } else if ("BMC".equals(name)) {
                stack.push(new int[]{-1, i});
            } else if ("EMC".equals(name)) {
                if (!stack.isEmpty()) {
                    int[] open = stack.pop();
                    if (open[0] >= 0) {
                        spans.computeIfAbsent(open[0], m -> new ArrayList<>())
                             .add(new int[]{open[1], i});
                    }
                }
            }
        }
        if (!spans.isEmpty()) {
            mcidSpans.put(pageObjNum, spans);
        }
    }

    private void indexBlocks(SdmDocument sdm, PgmModel pgm) {
        List<SdmBlock> children = sdm.getChildren();
        for (int i = 0; i < children.size(); i++) {
            SdmBlock b = children.get(i);
            origIndex.put(b, i);
            if (b.getSourceRef() instanceof ContentRange) {
                ContentRange cr = (ContentRange) b.getSourceRef();
                pageBlocks.computeIfAbsent(cr.getPageObjNum(), p -> new ArrayList<>())
                          .add(new BlockRange(b, cr.getOpStart(), cr.getOpEnd()));
                indexRuns(b, pgm);
            }
        }
        for (List<BlockRange> list : pageBlocks.values()) {
            list.sort((a, b) -> Integer.compare(a.opStart, b.opStart));
        }
    }

    /**
     * Builds the per-Run operator ranges of a shallow paragraph from its PGM
     * boxes: the reader projects Run i and box(part i) from the SAME fragment,
     * so partIndex&harr;inline order is exact. Paragraphs whose boxes can't be
     * aligned (form-hosted, count mismatch) stay whole-block-claim only.
     */
    private void indexRuns(SdmBlock b, PgmModel pgm) {
        if (!(b instanceof Paragraph) || pgm == null || b.getId() == null) {
            return;
        }
        List<Run> runs = new ArrayList<>();
        for (org.aspose.pdf.sdm.SdmInline in : ((Paragraph) b).getInline()) {
            if (in instanceof Run) {
                runs.add((Run) in);
            }
        }
        List<org.aspose.pdf.pgm.PgmBox> boxes = new ArrayList<>(pgm.byId(b.getId()));
        if (runs.isEmpty() || boxes.size() != runs.size()) {
            return;
        }
        boxes.sort((x, y) -> Integer.compare(x.getPartIndex(), y.getPartIndex()));
        List<RunRange> ranges = new ArrayList<>(runs.size());
        for (int i = 0; i < runs.size(); i++) {
            org.aspose.pdf.pgm.PgmBox box = boxes.get(i);
            if (!(box.getSourceRef() instanceof ContentRange)) {
                return; // mixed provenance — whole-block only
            }
            ContentRange cr = (ContentRange) box.getSourceRef();
            double baseline = box.getData() instanceof org.aspose.pdf.pgm.TextBoxData
                    ? ((org.aspose.pdf.pgm.TextBoxData) box.getData()).getBaselineY()
                    : box.getRect().getY();
            ranges.add(new RunRange(runs.get(i), cr.getOpStart(), cr.getOpEnd(),
                    box.getRect().getX(), box.getRect().getW(), baseline, box.getPage()));
        }
        runIndex.put(b, ranges);
    }

    // ------------------------------------------------------------------ walking

    private String typeOf(StructureElement elem) {
        String s = elem.getPdfDictionary().getNameAsString("S");
        if (s == null) {
            return null;
        }
        if (STANDARD_TYPES.contains(s)) {
            return s;
        }
        if (roleMap != null) {
            org.aspose.pdf.logicalstructure.StructureTypeStandard mapped = roleMap.resolve(s);
            if (mapped != null) {
                return mapped.getName();
            }
        }
        return s;
    }

    private PdfDictionary defaultPage(StructureElement elem) {
        PdfBase pg = resolve(elem.getPdfDictionary().get("Pg"));
        return pg instanceof PdfDictionary ? (PdfDictionary) pg : null;
    }

    private PdfDictionary pageOrInherited(StructureElement elem, PdfDictionary inherited) {
        PdfDictionary own = defaultPage(elem);
        return own != null ? own : inherited;
    }

    /**
     * Walks one structure element, appending produced top-level units.
     * {@code headingDepth} tracks Sect nesting for level-less {@code H} elements.
     */
    private void walkElement(StructureElement elem, PdfDictionary pageDict, int sectDepth) {
        String type = typeOf(elem);
        if (type == null) {
            recurseChildren(elem, pageDict, sectDepth);
            return;
        }
        PdfDictionary pg = pageOrInherited(elem, pageDict);
        try {
            switch (type) {
                case "P": {
                    for (SdmBlock b : claimBlocks(elem, pg)) {
                        addUnit(b);
                    }
                    recurseBlockChildren(elem, pg, sectDepth);
                    break;
                }
                case "H":
                case "H1": case "H2": case "H3": case "H4": case "H5": case "H6": {
                    int level = type.length() == 2 ? type.charAt(1) - '0'
                            : Math.min(6, Math.max(1, sectDepth + 1));
                    for (SdmBlock b : claimBlocks(elem, pg)) {
                        addUnit(b instanceof Paragraph ? toHeading((Paragraph) b, level) : b);
                    }
                    break;
                }
                case "L":
                    addUnitKeepOrig(buildList(elem, pg, sectDepth));
                    break;
                case "Table":
                    addUnitKeepOrig(buildTable(elem, pg, sectDepth));
                    break;
                case "Figure":
                    buildFigure(elem, pg);
                    break;
                case "BlockQuote": {
                    Quote q = new Quote();
                    collectInto(q.getChildren(), elem, pg, sectDepth);
                    addUnitKeepOrig(q);
                    break;
                }
                case "Code": {
                    List<SdmBlock> blocks = claimBlocks(elem, pg);
                    if (!blocks.isEmpty()) {
                        StringBuilder text = new StringBuilder();
                        for (SdmBlock b : blocks) {
                            if (text.length() > 0) {
                                text.append('\n');
                            }
                            text.append(textOf(b));
                        }
                        CodeBlock code = new CodeBlock(text.toString(), null);
                        transplantIdentity(blocks.get(0), code);
                        addUnit(code);
                    }
                    break;
                }
                case "TOC":
                    addUnitKeepOrig(buildToc(elem, pg, 1));
                    break;
                case "Note": {
                    String refId = elem.getID() != null ? elem.getID() : "note-" + (units.size() + 1);
                    Footnote fn = new Footnote(refId);
                    collectInto(fn.getChildren(), elem, pg, sectDepth);
                    addUnitKeepOrig(fn);
                    break;
                }
                case "Sect": case "Div": case "Part": case "Art": case "Index": {
                    Container c = new Container(type);
                    collectInto(c.getChildren(), elem, pg, "Sect".equals(type) ? sectDepth + 1 : sectDepth);
                    addUnitKeepOrig(c);
                    break;
                }
                case "Document":
                case "NonStruct":
                    recurseChildren(elem, pg, sectDepth);
                    break;
                case "Caption":
                    // handled by the Table/Figure parents; at top level fall through as text
                    for (SdmBlock b : claimBlocks(elem, pg)) {
                        addUnit(b);
                    }
                    break;
                default: {
                    // Link/Span/Quote/Reference/unknown at block position: pass the
                    // claimed content through unchanged (degrade, don't lose).
                    for (SdmBlock b : claimBlocks(elem, pg)) {
                        addUnit(b);
                    }
                    recurseBlockChildren(elem, pg, sectDepth);
                }
            }
        } catch (RuntimeException e) {
            LOG.warning("tagged enrichment: element " + type + " failed (" + e + ") — content falls through");
        }
    }

    private void recurseChildren(StructureElement elem, PdfDictionary pageDict, int sectDepth) {
        for (Object kid : elem.getAllKids()) {
            if (kid instanceof StructureElement) {
                walkElement((StructureElement) kid, pageDict, sectDepth);
            }
        }
    }

    /** Recurses only into BLOCK-typed children (inline kids were claimed already). */
    private void recurseBlockChildren(StructureElement elem, PdfDictionary pageDict, int sectDepth) {
        for (Object kid : elem.getAllKids()) {
            if (kid instanceof StructureElement) {
                StructureElement child = (StructureElement) kid;
                String t = typeOf(child);
                if (t != null && BLOCK_TYPES.contains(t)) {
                    walkElement(child, pageDict, sectDepth);
                }
            }
        }
    }

    /**
     * Collects a nested element's produced blocks into {@code target} instead of
     * the top-level unit list (used by Quote/Container/Footnote wrappers).
     */
    private void collectInto(List<SdmBlock> target, StructureElement elem,
                             PdfDictionary pageDict, int sectDepth) {
        int mark = units.size();
        recurseChildren(elem, pageDict, sectDepth);
        // also claim the wrapper's own direct content
        List<SdmBlock> own = claimBlocks(elem, pageDict);
        moveUnits(target, mark);
        target.addAll(own);
    }

    /** Moves units produced after {@code mark} out of the top-level list into target. */
    private void moveUnits(List<SdmBlock> target, int mark) {
        while (units.size() > mark) {
            Unit u = units.remove(mark);
            target.add(u.out);
        }
    }

    // ------------------------------------------------------------------ builders

    private ListBlock buildList(StructureElement list, PdfDictionary pageDict, int sectDepth) {
        List<ListItem> items = new ArrayList<>();
        Boolean ordered = null;
        Integer start = null;
        for (Object kid : list.getAllKids()) {
            if (!(kid instanceof StructureElement)) {
                continue;
            }
            StructureElement li = (StructureElement) kid;
            String t = typeOf(li);
            if (!"LI".equals(t)) {
                continue;
            }
            PdfDictionary liPg = pageOrInherited(li, pageDict);
            ListItem item = new ListItem();
            for (Object liKid : li.getAllKids()) {
                if (!(liKid instanceof StructureElement)) {
                    continue;
                }
                StructureElement part = (StructureElement) liKid;
                String pt = typeOf(part);
                PdfDictionary partPg = pageOrInherited(part, liPg);
                if ("Lbl".equals(pt)) {
                    List<SdmBlock> lblBlocks = claimBlocks(part, partPg);
                    if (ordered == null && !lblBlocks.isEmpty()) {
                        String lbl = textOf(lblBlocks.get(0)).trim();
                        if (lbl.matches("\\d+[.)]?")) {
                            ordered = Boolean.TRUE;
                            start = Integer.parseInt(lbl.replaceAll("\\D", ""));
                        } else {
                            ordered = Boolean.FALSE;
                        }
                    }
                    item.getChildren().addAll(lblBlocks);
                } else if ("LBody".equals(pt)) {
                    collectInto(item.getChildren(), part, partPg, sectDepth);
                } else {
                    collectInto(item.getChildren(), part, partPg, sectDepth);
                }
            }
            // an LI with direct content and no Lbl/LBody
            item.getChildren().addAll(claimBlocks(li, liPg));
            items.add(item);
        }
        ListBlock block = new ListBlock(Boolean.TRUE.equals(ordered),
                Boolean.TRUE.equals(ordered) ? start : null);
        block.getItems().addAll(items);
        return block;
    }

    private Table buildTable(StructureElement table, PdfDictionary pageDict, int sectDepth) {
        Table out = new Table();
        for (Object kid : table.getAllKids()) {
            if (!(kid instanceof StructureElement)) {
                continue;
            }
            StructureElement child = (StructureElement) kid;
            String t = typeOf(child);
            PdfDictionary childPg = pageOrInherited(child, pageDict);
            if ("TR".equals(t)) {
                out.getRows().add(buildRow(child, childPg, TableRow.Kind.BODY, sectDepth));
            } else if ("THead".equals(t) || "TBody".equals(t) || "TFoot".equals(t)) {
                TableRow.Kind kind = "THead".equals(t) ? TableRow.Kind.HEADER
                        : "TFoot".equals(t) ? TableRow.Kind.FOOTER : TableRow.Kind.BODY;
                for (Object trKid : child.getAllKids()) {
                    if (trKid instanceof StructureElement
                            && "TR".equals(typeOf((StructureElement) trKid))) {
                        StructureElement tr = (StructureElement) trKid;
                        out.getRows().add(buildRow(tr, pageOrInherited(tr, childPg), kind, sectDepth));
                    }
                }
            } else if ("Caption".equals(t)) {
                StringBuilder cap = new StringBuilder();
                for (SdmBlock b : claimBlocks(child, childPg)) {
                    if (cap.length() > 0) {
                        cap.append(' ');
                    }
                    cap.append(textOf(b));
                }
                if (cap.length() > 0) {
                    out.setCaption(cap.toString());
                }
            }
        }
        return out;
    }

    private TableRow buildRow(StructureElement tr, PdfDictionary pageDict,
                              TableRow.Kind kind, int sectDepth) {
        TableRow row = new TableRow(kind);
        for (Object kid : tr.getAllKids()) {
            if (!(kid instanceof StructureElement)) {
                continue;
            }
            StructureElement cellElem = (StructureElement) kid;
            String t = typeOf(cellElem);
            if (!"TD".equals(t) && !"TH".equals(t)) {
                continue;
            }
            TableCell cell = new TableCell();
            if ("TH".equals(t)) {
                cell.setKind(TableCell.Kind.TH);
            }
            int[] spans = cellSpans(cellElem);
            cell.setRowSpan(spans[0]);
            cell.setColSpan(spans[1]);
            collectInto(cell.getChildren(), cellElem, pageOrInherited(cellElem, pageDict), sectDepth);
            row.getCells().add(cell);
        }
        return row;
    }

    /** Reads {@code /A} table attributes (§14.8.5.7): returns {rowSpan, colSpan}. */
    private static int[] cellSpans(StructureElement cell) {
        int rowSpan = 1;
        int colSpan = 1;
        PdfBase a = resolve(cell.getPdfDictionary().get("A"));
        List<PdfDictionary> dicts = new ArrayList<>();
        if (a instanceof PdfDictionary) {
            dicts.add((PdfDictionary) a);
        } else if (a instanceof PdfArray) {
            PdfArray arr = (PdfArray) a;
            for (int i = 0; i < arr.size(); i++) {
                PdfBase e = resolve(arr.get(i));
                if (e instanceof PdfDictionary) {
                    dicts.add((PdfDictionary) e);
                }
            }
        }
        for (PdfDictionary d : dicts) {
            rowSpan = Math.max(rowSpan, d.getInt("RowSpan", 1));
            colSpan = Math.max(colSpan, d.getInt("ColSpan", 1));
        }
        return new int[]{rowSpan, colSpan};
    }

    private void buildFigure(StructureElement elem, PdfDictionary pageDict) {
        List<SdmBlock> blocks = claimBlocks(elem, pageDict);
        Figure figure = null;
        List<SdmBlock> rest = new ArrayList<>();
        for (SdmBlock b : blocks) {
            if (figure == null && b instanceof Figure) {
                figure = (Figure) b;
            } else {
                rest.add(b);
            }
        }
        String alt = elem.getAlternateDescription();
        if (figure == null) {
            // Figure element with no shallow image (e.g. vector art): keep the
            // claimed text as a Figure caption so nothing is lost.
            figure = new Figure(null);
        }
        if (alt != null && !alt.isEmpty()) {
            figure.setAlt(alt);
        }
        figure.getCaption().addAll(rest);
        addUnit(figure);
    }

    private TocBlock buildToc(StructureElement toc, PdfDictionary pageDict, int level) {
        TocBlock block = new TocBlock();
        // Visited set keyed by the underlying DICTIONARY: broken structure trees
        // (corpus 37882) contain /K cycles, and getAllKids() wraps the same dict
        // in a fresh StructureElement each visit, so element identity would not
        // detect them — dict identity does. Ends the recursion instead of
        // overflowing the stack.
        java.util.Set<PdfDictionary> visited =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(toc.getPdfDictionary());
        fillToc(block, toc, pageDict, level, visited);
        return block;
    }

    private void fillToc(TocBlock block, StructureElement toc, PdfDictionary pageDict, int level,
                         java.util.Set<PdfDictionary> visited) {
        if (level > 64) {
            return; // degenerate nesting — cap alongside the cycle guard
        }
        for (Object kid : toc.getAllKids()) {
            if (!(kid instanceof StructureElement)) {
                continue;
            }
            StructureElement child = (StructureElement) kid;
            if (!visited.add(child.getPdfDictionary())) {
                continue; // /K cycle — already emitted this element
            }
            String t = typeOf(child);
            PdfDictionary childPg = pageOrInherited(child, pageDict);
            if ("TOCI".equals(t)) {
                StringBuilder text = new StringBuilder();
                for (SdmBlock b : claimBlocks(child, childPg)) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(textOf(b));
                }
                block.getEntries().add(new TocEntry(level, text.toString(), null));
                // nested TOC inside the TOCI → deeper entries
                for (Object sub : child.getAllKids()) {
                    if (sub instanceof StructureElement
                            && "TOC".equals(typeOf((StructureElement) sub))) {
                        StructureElement subToc = (StructureElement) sub;
                        if (!visited.add(subToc.getPdfDictionary())) {
                            continue;
                        }
                        fillToc(block, subToc, pageOrInherited(subToc, childPg), level + 1, visited);
                    }
                }
            } else if ("TOC".equals(t)) {
                fillToc(block, child, childPg, level + 1, visited);
            }
        }
    }

    // ------------------------------------------------------------------ claiming

    /**
     * Claims the shallow blocks whose operator ranges overlap this element's own
     * marked-content spans (its direct MCRs plus those of inline descendants —
     * the walk of BLOCK-typed children claims their content separately).
     * Claimed blocks are consumed exactly once, in structure (MCR) order.
     */
    private List<SdmBlock> claimBlocks(StructureElement elem, PdfDictionary pageDict) {
        // Phase 1: unite ALL of this element's marked-content spans and collect,
        // per shallow block (first-touch order), the covered Runs. One element
        // frequently owns many MCIDs of one visual paragraph (kerned lettering,
        // hyphenated words) — claiming per-span would shred the text.
        Map<SdmBlock, List<RunRange>> covered = new LinkedHashMap<>();
        Set<SdmBlock> wholes = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Map<SdmBlock, Integer> blockPage = new IdentityHashMap<>();
        for (MarkedContentReference mcr : collectOwnMcrs(elem, pageDict)) {
            PdfDictionary pg = mcr.getPage() != null ? mcr.getPage() : pageDict;
            Integer pageNum = pg != null ? pageDictToObjNum.get(pg) : null;
            if (pageNum == null) {
                continue;
            }
            Map<Integer, List<int[]>> spans = mcidSpans.get(pageNum);
            List<BlockRange> blocks = pageBlocks.get(pageNum);
            if (spans == null || blocks == null) {
                continue;
            }
            List<int[]> mcSpans = spans.get(mcr.getMCID());
            if (mcSpans == null) {
                continue;
            }
            for (int[] span : mcSpans) {
                for (BlockRange br : blocks) {
                    if (br.opStart > span[1] || br.opEnd < span[0]
                            || consumed.contains(br.block)) {
                        continue;
                    }
                    blockPage.put(br.block, pageNum);
                    List<RunRange> ranges = runIndex.get(br.block);
                    if (ranges == null) {
                        // No per-run geometry (Figure/Opaque/form-hosted): whole claim.
                        if (covered.putIfAbsent(br.block, new ArrayList<>()) == null) {
                            wholes.add(br.block);
                        }
                        continue;
                    }
                    List<RunRange> hit = covered.computeIfAbsent(br.block, k -> new ArrayList<>());
                    for (RunRange rr : ranges) {
                        if (rr.opStart >= span[0] && rr.opEnd <= span[1] && !hit.contains(rr)) {
                            hit.add(rr);
                        }
                    }
                }
            }
        }

        // Phase 2: per block — whole claim when everything left is covered
        // (the block itself survives, GUID and original run order intact);
        // otherwise split ONE sub-paragraph carrying the covered Runs in
        // ORIGINAL inline order with extractor-parity spacing between pieces.
        List<SdmBlock> out = new ArrayList<>();
        for (Map.Entry<SdmBlock, List<RunRange>> e : covered.entrySet()) {
            SdmBlock block = e.getKey();
            if (wholes.contains(block)) {
                consumed.add(block);
                out.add(block);
                continue;
            }
            List<RunRange> ranges = runIndex.get(block);
            List<RunRange> hit = e.getValue();
            if (hit.isEmpty() || ranges == null) {
                continue;
            }
            if (hit.size() == ranges.size()) {
                consumed.add(block);
                runIndex.remove(block);
                out.add(block);
                continue;
            }
            hit.sort((a, b) -> Integer.compare(ranges.indexOf(a), ranges.indexOf(b)));
            Paragraph original = (Paragraph) block;
            Paragraph sub = new Paragraph();
            double acw = acwOf(hit.get(0).pageIdx);
            int min = Integer.MAX_VALUE;
            int max = -1;
            RunRange prev = null;
            for (RunRange rr : hit) {
                if (prev != null && SpacingRule.shouldSpace(prev.run.getText(), prev.endX,
                        prev.baseline, rr.run.getText(), rr.x, rr.baseline, acw)) {
                    sub.getInline().add(new Run(" ", null));
                }
                sub.getInline().add(rr.run);
                original.getInline().remove(rr.run);
                min = Math.min(min, rr.opStart);
                max = Math.max(max, rr.opEnd);
                prev = rr;
            }
            ranges.removeAll(hit);
            sub.setStyle(original.getStyle());
            ContentRange subRef = new ContentRange(blockPage.get(block), min, max);
            sub.setSourceRef(subRef);
            sub.setId(nsDoc != null ? org.aspose.pdf.sdm.SdmIds.nodeId(nsDoc, subRef) : null);
            origIndex.put(sub, origIndex.getOrDefault(original, -1));
            if (ranges.isEmpty() || original.getInline().isEmpty()) {
                consumed.add(original);
                runIndex.remove(original);
            }
            LOG.fine(() -> "split sub-paragraph ops " + subRef.getOpStart() + "-"
                    + subRef.getOpEnd() + " out of a merged visual paragraph");
            out.add(sub);
        }
        return out;
    }

    /** Collects this element's MCRs, recursing through INLINE children only. */
    private List<MarkedContentReference> collectOwnMcrs(StructureElement elem,
                                                        PdfDictionary pageDict) {
        List<MarkedContentReference> out = new ArrayList<>();
        PdfDictionary pg = pageOrInherited(elem, pageDict);
        for (Object kid : elem.getAllKids()) {
            if (kid instanceof MarkedContentReference) {
                MarkedContentReference mcr = (MarkedContentReference) kid;
                out.add(mcr.getPage() != null ? mcr
                        : new MarkedContentReference(mcr.getMCID(), pg));
            } else if (kid instanceof StructureElement) {
                StructureElement child = (StructureElement) kid;
                String t = typeOf(child);
                if (t == null || !BLOCK_TYPES.contains(t)) {
                    out.addAll(collectOwnMcrs(child, pg));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ retyping

    /**
     * Retypes a shallow Paragraph into a Heading, PRESERVING its identity:
     * GUID, sourceRef, style, attributes and the very same inline list content.
     */
    private Heading toHeading(Paragraph p, int level) {
        Heading h = new Heading(level);
        transplantIdentity(p, h);
        h.getInline().addAll(p.getInline());
        h.setStyle(p.getStyle());
        return h;
    }

    /**
     * Copies id, sourceRef and open attributes from one node to another — and
     * the original top-level position, so retyped units still anchor the
     * fall-through interleaving of unclaimed neighbours.
     */
    private void transplantIdentity(SdmBlock from, SdmBlock to) {
        to.setId(from.getId());
        to.setSourceRef(from.getSourceRef());
        to.getAttributes().putAll(from.getAttributes());
        Integer idx = origIndex.get(from);
        if (idx != null) {
            origIndex.put(to, idx);
        }
    }

    private void addUnit(SdmBlock out) {
        Unit u = new Unit(out);
        u.maxOrig = maxOrigOf(out);
        units.add(u);
    }

    /** Adds a grouping unit whose consumed originals live in its subtree. */
    private void addUnitKeepOrig(SdmBlock out) {
        addUnit(out);
    }

    /** Greatest original top-level index consumed anywhere inside this block. */
    private int maxOrigOf(SdmBlock b) {
        int max = origIndex.getOrDefault(b, -1);
        List<SdmBlock> children = childrenOf(b);
        for (SdmBlock c : children) {
            max = Math.max(max, maxOrigOf(c));
        }
        return max;
    }

    private static List<SdmBlock> childrenOf(SdmBlock b) {
        List<SdmBlock> out = new ArrayList<>();
        if (b instanceof Container) {
            out.addAll(((Container) b).getChildren());
        } else if (b instanceof Quote) {
            out.addAll(((Quote) b).getChildren());
        } else if (b instanceof Footnote) {
            out.addAll(((Footnote) b).getChildren());
        } else if (b instanceof ListBlock) {
            for (ListItem li : ((ListBlock) b).getItems()) {
                out.addAll(li.getChildren());
            }
        } else if (b instanceof Table) {
            for (TableRow r : ((Table) b).getRows()) {
                for (TableCell c : r.getCells()) {
                    out.addAll(c.getChildren());
                }
            }
        } else if (b instanceof Figure) {
            out.addAll(((Figure) b).getCaption());
        }
        return out;
    }

    // ------------------------------------------------------------------ misc

    private double acwOf(int pageIdx) {
        return pageIdx >= 0 && pageIdx < pageAcw.length ? pageAcw[pageIdx] : 5.0;
    }

    private static String textOf(SdmBlock b) {
        StringBuilder sb = new StringBuilder();
        List<SdmInline> inline = null;
        if (b instanceof Paragraph) {
            inline = ((Paragraph) b).getInline();
        } else if (b instanceof Heading) {
            inline = ((Heading) b).getInline();
        }
        if (inline != null) {
            for (SdmInline in : inline) {
                if (in instanceof Run) {
                    sb.append(((Run) in).getText());
                }
            }
        }
        return sb.toString();
    }

    private static PdfBase resolve(PdfBase obj) {
        if (obj instanceof PdfObjectReference) {
            try {
                return ((PdfObjectReference) obj).dereference();
            } catch (IOException e) {
                return null;
            }
        }
        return obj;
    }
}
