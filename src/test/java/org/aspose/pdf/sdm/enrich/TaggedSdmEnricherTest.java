package org.aspose.pdf.sdm.enrich;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.aspose.pdf.Document;
import org.aspose.pdf.HtmlOutputMode;
import org.aspose.pdf.HtmlSaveOptions;
import org.aspose.pdf.Operator;
import org.aspose.pdf.OperatorCollection;
import org.aspose.pdf.Page;
import org.aspose.pdf.engine.pdfobjects.PdfDictionary;
import org.aspose.pdf.html.HtmlTagParser;
import org.aspose.pdf.logicalstructure.StructureElement;
import org.aspose.pdf.operators.BDC;
import org.aspose.pdf.operators.EMC;
import org.aspose.pdf.sdm.Heading;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmNodeType;
import org.aspose.pdf.sdm.Table;
import org.aspose.pdf.sdm.TableCell;
import org.aspose.pdf.sdm.TableRow;
import org.aspose.pdf.sdm.reader.PdfSdmReader;
import org.aspose.pdf.tagged.TaggedContent;
import org.aspose.pdf.text.Position;
import org.aspose.pdf.text.TextBuilder;
import org.aspose.pdf.text.TextFragment;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;

/**
 * IR Stage 3 PART 2 gate: the tagged-PDF structural enricher.
 *
 * <p>Fixtures are created in-test with our own tagged-PDF machinery: text is
 * painted with TextBuilder, each painted BT..ET group is wrapped in
 * {@code BDC /P <</MCID n>> .. EMC} at the operator level (the writer side of
 * §14.7.4.2), and the structure tree is built through
 * {@code Document.getTaggedContent()}. Known tree in — exact SDM tree out:
 * node types, levels, nesting, and PRESERVED GUIDs are asserted.</p>
 */
public class TaggedSdmEnricherTest {

    // ------------------------------------------------------------------ fixture

    private static void line(Page page, String text, double x, double y) throws IOException {
        TextFragment tf = new TextFragment(text);
        tf.setPosition(new Position(x, y));
        new TextBuilder(page).appendText(tf);
    }

    /**
     * Wraps every BT..ET group of the page in BDC /P &lt;&lt;/MCID i&gt;&gt; .. EMC,
     * assigning sequential MCIDs in drawing order. Returns the number of groups.
     */
    private static int wrapTextInMcids(Page page) throws IOException {
        OperatorCollection ops = page.getContents();
        List<int[]> groups = new ArrayList<>();
        int btIdx = -1;
        for (int i = 0; i < ops.size(); i++) {
            Operator op = ops.getAt(i);
            if ("BT".equals(op.getName())) {
                btIdx = i;
            } else if ("ET".equals(op.getName()) && btIdx >= 0) {
                groups.add(new int[]{btIdx, i});
                btIdx = -1;
            }
        }
        // Insert from the last group backwards so earlier indices stay valid
        // (addAt is the 0-based insert; OperatorCollection.insert is 1-based).
        for (int g = groups.size() - 1; g >= 0; g--) {
            int[] range = groups.get(g);
            PdfDictionary props = new PdfDictionary();
            props.setInt("MCID", g);
            ops.addAt(range[1] + 1, new EMC());
            ops.addAt(range[0], new BDC("P", props));
        }
        return groups.size();
    }

    /** Appends an MCR child pointing at this page's MCID. */
    private static void mcr(StructureElement elem, Page page, int mcid) {
        elem.appendMarkedContent(mcid, page.getPdfDictionary());
    }

    private static PdfSdmReader.Result project(Document doc) throws IOException {
        return new PdfSdmReader().read(doc, null);
    }

    private static String textOf(SdmBlock b) {
        if (b instanceof Paragraph) {
            return ((Paragraph) b).getText();
        }
        if (b instanceof Heading) {
            StringBuilder sb = new StringBuilder();
            ((Heading) b).getInline().forEach(in -> {
                if (in instanceof org.aspose.pdf.sdm.Run) {
                    sb.append(((org.aspose.pdf.sdm.Run) in).getText());
                }
            });
            return sb.toString();
        }
        return "";
    }

    // ------------------------------------------------------------------ tests

    /** H1 element retypes its paragraph to Heading level 1 — GUID preserved exactly. */
    @Test
    public void headingUpgradePreservesGuid() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Chapter Title", 72, 700);
        line(page, "Body one.", 72, 620);
        line(page, "Body two.", 72, 540);
        assertEquals(3, wrapTextInMcids(page));

        TaggedContent tc = doc.getTaggedContent();
        StructureElement root = tc.getRootElement();
        StructureElement h1 = tc.createHeaderElement(1).getStructureElement();
        StructureElement p1 = tc.createParagraphElement().getStructureElement();
        StructureElement p2 = tc.createParagraphElement().getStructureElement();
        root.appendChild(h1);
        root.appendChild(p1);
        root.appendChild(p2);
        mcr(h1, page, 0);
        mcr(p1, page, 1);
        mcr(p2, page, 2);

        PdfSdmReader.Result projection = project(doc);
        SdmDocument sdm = projection.getSdm();
        assertEquals(3, sdm.getChildren().size(), "shallow: 3 paragraphs");
        String idHead = sdm.getChildren().get(0).getId();
        String idB1 = sdm.getChildren().get(1).getId();
        String idB2 = sdm.getChildren().get(2).getId();
        assertNotNull(idHead);

        assertTrue(new TaggedSdmEnricher().enrich(doc, sdm, projection.getPgm()));

        assertEquals(3, sdm.getChildren().size());
        SdmBlock first = sdm.getChildren().get(0);
        assertEquals(SdmNodeType.HEADING, first.getType());
        assertEquals(1, ((Heading) first).getLevel());
        assertEquals("Chapter Title", textOf(first));
        assertEquals(idHead, first.getId(), "retype PRESERVES the GUID");
        assertEquals(SdmNodeType.PARAGRAPH, sdm.getChildren().get(1).getType());
        assertEquals(idB1, sdm.getChildren().get(1).getId());
        assertEquals("Body one.", textOf(sdm.getChildren().get(1)));
        assertEquals(idB2, sdm.getChildren().get(2).getId());
    }

    /** L/LI/Lbl/LBody regroup into an ordered ListBlock; item GUIDs preserved. */
    @Test
    public void listRegroupOrderedFromLabels() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "1.", 72, 700);
        line(page, "First item", 100, 700);
        line(page, "2.", 72, 620);
        line(page, "Second item", 100, 620);
        assertEquals(4, wrapTextInMcids(page));

        TaggedContent tc = doc.getTaggedContent();
        StructureElement root = tc.getRootElement();
        StructureElement list = tc.createListElement().getStructureElement();
        root.appendChild(list);
        for (int i = 0; i < 2; i++) {
            StructureElement li = tc.createListLIElement().getStructureElement();
            StructureElement lbl = tc.createListLblElement().getStructureElement();
            StructureElement lbody = tc.createListLBodyElement().getStructureElement();
            list.appendChild(li);
            li.appendChild(lbl);
            li.appendChild(lbody);
            mcr(lbl, page, i * 2);
            mcr(lbody, page, i * 2 + 1);
        }

        PdfSdmReader.Result projection = project(doc);
        SdmDocument sdm = projection.getSdm();
        List<String> shallowIds = new ArrayList<>();
        for (SdmBlock b : sdm.getChildren()) {
            shallowIds.add(b.getId());
        }

        assertTrue(new TaggedSdmEnricher().enrich(doc, sdm, projection.getPgm()));

        assertEquals(1, sdm.getChildren().size(), "one list block");
        SdmBlock block = sdm.getChildren().get(0);
        assertEquals(SdmNodeType.LIST_BLOCK, block.getType());
        ListBlock lb = (ListBlock) block;
        assertTrue(lb.isOrdered(), "digit labels → ordered list");
        assertEquals(Integer.valueOf(1), lb.getStart());
        assertEquals(2, lb.getItems().size());
        assertEquals(2, lb.getItems().get(0).getChildren().size(), "Lbl + LBody blocks");
        assertEquals("1.", textOf(lb.getItems().get(0).getChildren().get(0)));
        assertEquals("First item", textOf(lb.getItems().get(0).getChildren().get(1)));
        assertEquals("Second item", textOf(lb.getItems().get(1).getChildren().get(1)));
        assertTrue(shallowIds.contains(lb.getItems().get(0).getChildren().get(1).getId()),
                "list item content keeps its shallow GUID");
    }

    /** Table/TR/TH/TD with THead and /A ColSpan regroup into an exact Table node. */
    @Test
    public void tableRegroupWithSectionsAndSpans() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Name", 72, 700);
        line(page, "Value", 200, 700);
        line(page, "Total", 72, 620);
        line(page, "42", 200, 620);
        assertEquals(4, wrapTextInMcids(page));

        TaggedContent tc = doc.getTaggedContent();
        StructureElement root = tc.getRootElement();
        StructureElement table = tc.createTableElement().getStructureElement();
        root.appendChild(table);

        StructureElement thead = tc.createTableTHeadElement().getStructureElement();
        table.appendChild(thead);
        StructureElement hr = tc.createTableTRElement().getStructureElement();
        thead.appendChild(hr);
        StructureElement th1 = tc.createTableTHElement().getStructureElement();
        StructureElement th2 = tc.createTableTHElement().getStructureElement();
        hr.appendChild(th1);
        hr.appendChild(th2);
        mcr(th1, page, 0);
        mcr(th2, page, 1);

        StructureElement tbody = tc.createTableTBodyElement().getStructureElement();
        table.appendChild(tbody);
        StructureElement br = tc.createTableTRElement().getStructureElement();
        tbody.appendChild(br);
        StructureElement wide = tc.createTableTDElement().getStructureElement();
        br.appendChild(wide);
        PdfDictionary attrs = new PdfDictionary();
        attrs.set(org.aspose.pdf.engine.pdfobjects.PdfName.of("O"),
                org.aspose.pdf.engine.pdfobjects.PdfName.of("Table"));
        attrs.setInt("ColSpan", 2);
        wide.getPdfDictionary().set(org.aspose.pdf.engine.pdfobjects.PdfName.of("A"), attrs);
        mcr(wide, page, 2);
        mcr(wide, page, 3);

        PdfSdmReader.Result projection = project(doc);
        SdmDocument sdm = projection.getSdm();
        assertTrue(new TaggedSdmEnricher().enrich(doc, sdm, projection.getPgm()));
        // pipeline contract: the spacing normalizer runs after enrichment
        RunSpacingNormalizer.normalize(sdm, projection.getPgm());

        assertEquals(1, sdm.getChildren().size(), "one table");
        Table t = (Table) sdm.getChildren().get(0);
        assertEquals(2, t.getRows().size());
        TableRow head = t.getRows().get(0);
        assertEquals(TableRow.Kind.HEADER, head.getKind(), "THead section → header row");
        assertEquals(2, head.getCells().size());
        assertEquals(TableCell.Kind.TH, head.getCells().get(0).getKind());
        assertEquals("Name", textOf(head.getCells().get(0).getChildren().get(0)));
        TableRow body = t.getRows().get(1);
        assertEquals(TableRow.Kind.BODY, body.getKind());
        assertEquals(1, body.getCells().size(), "one spanned cell");
        TableCell cell = body.getCells().get(0);
        assertEquals(TableCell.Kind.TD, cell.getKind());
        assertEquals(2, cell.getColSpan(), "/A ColSpan honoured");
        // both MCIDs claimed into the cell, merged in original order with
        // extractor-parity spacing (a real 128pt gap → a space)
        assertEquals(1, cell.getChildren().size(), "one merged paragraph");
        assertEquals("Total 42", textOf(cell.getChildren().get(0)));
    }

    /** Untagged content inside a tagged doc falls through unchanged, in place. */
    @Test
    public void untaggedContentFallsThroughInPlace() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Tagged head", 72, 700);
        line(page, "Untagged stray", 72, 620);
        line(page, "Tagged para", 72, 540);
        // Only lines 0 and 2 get MCIDs: wrap manually to skip the stray.
        OperatorCollection ops = page.getContents();
        List<int[]> groups = new ArrayList<>();
        int btIdx = -1;
        for (int i = 0; i < ops.size(); i++) {
            String name = ops.getAt(i).getName();
            if ("BT".equals(name)) {
                btIdx = i;
            } else if ("ET".equals(name) && btIdx >= 0) {
                groups.add(new int[]{btIdx, i});
                btIdx = -1;
            }
        }
        assertEquals(3, groups.size());
        int mcid = 1;
        for (int g = groups.size() - 1; g >= 0; g--) {
            if (g == 1) {
                continue; // the stray line stays untagged
            }
            PdfDictionary props = new PdfDictionary();
            props.setInt("MCID", mcid--);
            ops.addAt(groups.get(g)[1] + 1, new EMC());
            ops.addAt(groups.get(g)[0], new BDC("P", props));
        }

        TaggedContent tc = doc.getTaggedContent();
        StructureElement root = tc.getRootElement();
        StructureElement h2 = tc.createHeaderElement(2).getStructureElement();
        StructureElement p = tc.createParagraphElement().getStructureElement();
        root.appendChild(h2);
        root.appendChild(p);
        mcr(h2, page, 0);
        mcr(p, page, 1);

        PdfSdmReader.Result projection = project(doc);
        SdmDocument sdm = projection.getSdm();
        SdmBlock stray = sdm.getChildren().get(1);
        assertEquals("Untagged stray", textOf(stray));

        assertTrue(new TaggedSdmEnricher().enrich(doc, sdm, projection.getPgm()));

        assertEquals(3, sdm.getChildren().size());
        assertEquals(SdmNodeType.HEADING, sdm.getChildren().get(0).getType());
        assertEquals(2, ((Heading) sdm.getChildren().get(0)).getLevel());
        assertSame(stray, sdm.getChildren().get(1), "stray block untouched, same object, same place");
        assertEquals(SdmNodeType.PARAGRAPH, sdm.getChildren().get(2).getType());
        assertEquals("Tagged para", textOf(sdm.getChildren().get(2)));
    }

    /** Untagged document: enrich is a clean no-op returning false. */
    @Test
    public void untaggedDocumentNoOp() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Plain text", 72, 700);
        assertFalse(TaggedSdmEnricher.isTagged(doc));

        PdfSdmReader.Result projection = project(doc);
        SdmDocument sdm = projection.getSdm();
        int before = sdm.getChildren().size();
        assertFalse(new TaggedSdmEnricher().enrich(doc, sdm, projection.getPgm()));
        assertEquals(before, sdm.getChildren().size());
    }

    /** End-to-end: tagged fixture → save(...,HtmlSaveOptions STRUCTURAL) → correct h2/ul/p DOM. */
    @Test
    public void endToEndTaggedHtml() throws IOException {
        Document doc = new Document();
        Page page = doc.getPages().add();
        line(page, "Section Head", 72, 700);
        line(page, "Alpha item", 72, 620);
        line(page, "Beta item", 72, 540);
        line(page, "Closing text", 72, 460);
        assertEquals(4, wrapTextInMcids(page));

        TaggedContent tc = doc.getTaggedContent();
        StructureElement root = tc.getRootElement();
        StructureElement h2 = tc.createHeaderElement(2).getStructureElement();
        root.appendChild(h2);
        mcr(h2, page, 0);
        StructureElement list = tc.createListElement().getStructureElement();
        root.appendChild(list);
        for (int i = 0; i < 2; i++) {
            StructureElement li = tc.createListLIElement().getStructureElement();
            StructureElement lbody = tc.createListLBodyElement().getStructureElement();
            list.appendChild(li);
            li.appendChild(lbody);
            mcr(lbody, page, 1 + i);
        }
        StructureElement p = tc.createParagraphElement().getStructureElement();
        root.appendChild(p);
        mcr(p, page, 3);

        HtmlSaveOptions options = new HtmlSaveOptions();
        options.setOutputMode(HtmlOutputMode.STRUCTURAL);
        String html = org.aspose.pdf.testutil.HtmlText.of(doc, options);

        org.w3c.dom.Document dom = HtmlTagParser.parse(html);
        NodeList h2s = dom.getElementsByTagName("h2");
        assertEquals(1, h2s.getLength(), "one h2: " + html);
        assertEquals("Section Head", h2s.item(0).getTextContent().trim());
        NodeList uls = dom.getElementsByTagName("ul");
        assertEquals(1, uls.getLength(), "one ul");
        NodeList lis = dom.getElementsByTagName("li");
        assertEquals(2, lis.getLength(), "two items");
        assertEquals("Alpha item", lis.item(0).getTextContent().trim());
        String bodyText = dom.getElementsByTagName("body").item(0)
                .getTextContent().replaceAll("\\s+", " ").trim();
        assertTrue(bodyText.endsWith("Closing text"), "closing paragraph last: " + bodyText);
    }
}
