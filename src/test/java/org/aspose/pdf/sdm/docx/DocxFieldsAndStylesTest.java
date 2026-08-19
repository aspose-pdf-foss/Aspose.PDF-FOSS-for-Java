package org.aspose.pdf.sdm.docx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.aspose.pdf.DocLoadOptions;
import org.aspose.pdf.Document;
import org.aspose.pdf.sdm.ListBlock;
import org.aspose.pdf.sdm.Paragraph;
import org.aspose.pdf.sdm.Run;
import org.aspose.pdf.sdm.SdmBlock;
import org.aspose.pdf.sdm.SdmDocument;
import org.aspose.pdf.sdm.SdmInline;
import org.aspose.pdf.text.TextAbsorber;
import org.junit.jupiter.api.Test;

/**
 * DOCX reader fidelity for hand-authored WordprocessingML: nested lists
 * ({@code w:ilvl}), the paragraph-style run cascade ({@code pStyle} &rarr;
 * styles.xml {@code rPr} with {@code basedOn}), and PAGE/NUMPAGES fields in a
 * footer part substituted with REAL page numbers at layout time (not the
 * stale cached field value).
 */
public class DocxFieldsAndStylesTest {

    private static final String W_NS =
            "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
          + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"";

    private static byte[] zip(Map<String, String> parts) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : parts.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private static String doc(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<w:document " + W_NS + "><w:body>" + body + "</w:body></w:document>";
    }

    private static String para(String pPr, String runs) {
        return "<w:p>" + (pPr.isEmpty() ? "" : "<w:pPr>" + pPr + "</w:pPr>") + runs + "</w:p>";
    }

    private static String run(String rPr, String text) {
        return "<w:r>" + (rPr.isEmpty() ? "" : "<w:rPr>" + rPr + "</w:rPr>")
                + "<w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    private static String numPara(int numId, int ilvl, String text) {
        return para("<w:numPr><w:ilvl w:val=\"" + ilvl + "\"/><w:numId w:val=\""
                + numId + "\"/></w:numPr>", run("", text));
    }

    private static String itemText(ListBlock list, int index) {
        StringBuilder sb = new StringBuilder();
        for (SdmBlock b : list.getItems().get(index).getChildren()) {
            if (b instanceof Paragraph) {
                for (SdmInline in : ((Paragraph) b).getInline()) {
                    if (in instanceof Run) {
                        sb.append(((Run) in).getText());
                    }
                }
            }
        }
        return sb.toString();
    }

    private static ListBlock nestedListOf(ListBlock list, int itemIndex) {
        for (SdmBlock b : list.getItems().get(itemIndex).getChildren()) {
            if (b instanceof ListBlock) {
                return (ListBlock) b;
            }
        }
        return null;
    }

    @Test
    public void ilvlBuildsNestedLists() throws Exception {
        String numbering = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:numbering " + W_NS + ">"
                + "<w:abstractNum w:abstractNumId=\"0\">"
                + "<w:lvl w:ilvl=\"0\"><w:numFmt w:val=\"decimal\"/></w:lvl>"
                + "<w:lvl w:ilvl=\"1\"><w:numFmt w:val=\"bullet\"/></w:lvl>"
                + "</w:abstractNum>"
                + "<w:num w:numId=\"5\"><w:abstractNumId w:val=\"0\"/></w:num>"
                + "</w:numbering>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", doc(
                numPara(5, 0, "one")
              + numPara(5, 1, "one-a")
              + numPara(5, 1, "one-b")
              + numPara(5, 0, "two")));
        parts.put("word/numbering.xml", numbering);

        SdmDocument sdm = new DocxSdmReader().read(zip(parts));
        assertEquals(1, sdm.getChildren().size(), "one root list");
        ListBlock root = (ListBlock) sdm.getChildren().get(0);
        assertTrue(root.isOrdered(), "level 0 is decimal");
        assertEquals(2, root.getItems().size(), "two level-0 items");
        assertEquals("one", itemText(root, 0));
        assertEquals("two", itemText(root, 1));
        ListBlock nested = nestedListOf(root, 0);
        assertNotNull(nested, "level-1 items nest under item 'one'");
        assertFalse(nested.isOrdered(), "level 1 is bullet");
        assertEquals(2, nested.getItems().size(), "two nested items");
        assertEquals("one-a", itemText(nested, 0));
        assertEquals("one-b", itemText(nested, 1));
        assertNotNull(nestedListOf(root, 0), "nested list rides in the first item");
    }

    @Test
    public void pStyleCascadeStylesPlainRuns() throws Exception {
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles " + W_NS + ">"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Base\">"
                + "<w:rPr><w:sz w:val=\"28\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Strong1\">"
                + "<w:basedOn w:val=\"Base\"/>"
                + "<w:rPr><w:b/><w:color w:val=\"FF0000\"/></w:rPr></w:style>"
                + "</w:styles>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", doc(
                para("<w:pStyle w:val=\"Strong1\"/>",
                        run("", "styled plain")
                      + run("<w:sz w:val=\"20\"/>", "own size"))));
        parts.put("word/styles.xml", styles);

        SdmDocument sdm = new DocxSdmReader().read(zip(parts));
        Paragraph p = (Paragraph) sdm.getChildren().get(0);
        Run plain = (Run) p.getInline().get(0);
        assertNotNull(plain.getStyle(), "cascade produces a style");
        assertTrue(plain.getStyle().isBold(), "bold from pStyle");
        assertEquals(14.0, plain.getStyle().getFontSize(), 0.01, "size from basedOn chain");
        assertEquals(0xFFFF0000, plain.getStyle().getColor(), "colour from pStyle");
        Run own = (Run) p.getInline().get(1);
        assertEquals(10.0, own.getStyle().getFontSize(), 0.01, "explicit run size wins");
        assertTrue(own.getStyle().isBold(), "bold still inherited");
    }

    @Test
    public void footerPageFieldsBecomeTokens() throws Exception {
        Map<String, String> parts = footerFieldPackage(3);
        DocxSdmReader reader = new DocxSdmReader();
        reader.read(zip(parts));
        List<Object[]> footer = reader.getFooterLines();
        assertEquals(1, footer.size(), "one footer line");
        String text = (String) footer.get(0)[0];
        assertEquals("Page ${PAGE} of ${NUMPAGES}", text,
                "PAGE/NUMPAGES become substitutable tokens, cached '1' is dropped");
    }

    @Test
    public void loadedPdfCarriesRealPageNumbers() throws Exception {
        // Enough paragraphs to paginate, then check the LAST page's footer says
        // "Page N of N" with the true count (cached value in the field was "1").
        byte[] docx = zip(footerFieldPackage(120));
        try (Document pdf = new Document(new ByteArrayInputStream(docx), new DocLoadOptions())) {
            int n = pdf.getPages().getCount();
            assertTrue(n >= 2, "content paginates: " + n + " page(s)");
            TextAbsorber first = new TextAbsorber();
            first.visit(pdf.getPages().get(1));
            assertTrue(first.getText().contains("Page 1 of " + n),
                    "page 1 footer: " + snippet(first.getText()));
            TextAbsorber last = new TextAbsorber();
            last.visit(pdf.getPages().get(n));
            assertTrue(last.getText().contains("Page " + n + " of " + n),
                    "page " + n + " footer: " + snippet(last.getText()));
        }
    }

    private static String snippet(String s) {
        s = s == null ? "" : s.replaceAll("\\s+", " ");
        return s.length() > 160 ? s.substring(s.length() - 160) : s;
    }

    /** A package whose footer is "Page {PAGE} of {NUMPAGES}" (complex field +
     *  fldSimple, both with a stale cached value of "1"). */
    private static Map<String, String> footerFieldPackage(int paragraphs) {
        StringBuilder body = new StringBuilder();
        for (int i = 1; i <= paragraphs; i++) {
            body.append(para("", run("", "Body paragraph number " + i
                    + " with enough words to occupy a full line of the page.")));
        }
        body.append("<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rId7\"/>")
            .append("<w:pgSz w:w=\"12240\" w:h=\"15840\"/>")
            .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" ")
            .append("w:left=\"1440\" w:header=\"720\" w:footer=\"720\"/></w:sectPr>");

        String footer = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:ftr " + W_NS + ">"
                + "<w:p><w:r><w:t xml:space=\"preserve\">Page </w:t></w:r>"
                // complex field: begin / instrText PAGE / separate / cached "1" / end
                + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
                + "<w:r><w:instrText xml:space=\"preserve\"> PAGE \\* MERGEFORMAT </w:instrText></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
                + "<w:r><w:t>1</w:t></w:r>"
                + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
                + "<w:r><w:t xml:space=\"preserve\"> of </w:t></w:r>"
                // simple field with a stale cached value
                + "<w:fldSimple w:instr=\" NUMPAGES \\* MERGEFORMAT \">"
                + "<w:r><w:t>1</w:t></w:r></w:fldSimple>"
                + "</w:p></w:ftr>";

        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId7\" "
                + "Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" "
                + "Target=\"footer1.xml\"/></Relationships>";

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("word/document.xml", doc(body.toString()));
        parts.put("word/_rels/document.xml.rels", rels);
        parts.put("word/footer1.xml", footer);
        return parts;
    }
}
